package dev.douhua.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * 用配对过的密钥连无线调试端口，执行 shell 命令。纯 JDK。
 * 协议照 AOSP adb docs/dev/protocol.md + adb.cpp / transport.cpp：
 * 明文发 CNXN → adbd 回 STLS → 回 STLS → 同一条 socket 升级 TLS 1.3（adbd 强制要客户端证书，拿证书里的公钥比 adb_keys）
 * → adbd 发 CNXN → OPEN "shell:命令" → OKAY → WRTE…（每条回 OKAY）→ CLSE。
 * 一个连接同一时间只跑一条命令，够拉起服务用。
 */
public final class AdbConnection implements Closeable {
    /**
     * adbd 拒绝了我们的密钥：TLS 层报错（adbd 校验客户端证书失败，BoringSSL 发致命警报）。7 天没用被作废，或用户撤销了调试授权。
     * 只是对方断开（EOF / reset，比如无线调试正好被系统或用户关掉、Wi-Fi 漫游时 adbd 踢掉所有 TLS 连接）不算，照普通 IOException 抛；
     * 调用方仍要自己再核实一次（见 SelfStart.startNow），TLS 库对"连接被断"也可能报成 SSLException。
     */
    public static final class KeyRejectedException extends IOException {
        KeyRejectedException(String detail) {
            super("adbd 不认这把密钥：" + detail);
        }
    }

    private static final int OUTPUT_LIMIT = 64 * 1024;

    private final Socket plain;
    private SSLSocket tls;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private String banner = "";

    private AdbConnection(Socket plain) {
        this.plain = plain;
    }

    /** 连上并完成认证。端口没人监听抛 ConnectException；密钥被拒抛 {@link KeyRejectedException}。 */
    public static AdbConnection open(SSLContext ssl, String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        AdbConnection c = new AdbConnection(s);
        boolean ok = false;
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            c.in = new DataInputStream(s.getInputStream());
            c.out = s.getOutputStream();
            AdbMessage.withString(AdbMessage.A_CNXN, AdbMessage.A_VERSION, AdbMessage.MAX_PAYLOAD, "host::features=shell_v2,cmd").writeTo(c.out);   // 和电脑上的 adb 一样声明 shell_v2
            AdbMessage m = AdbMessage.read(c.in);
            if (m.command == AdbMessage.A_STLS) {
                new AdbMessage(AdbMessage.A_STLS, AdbMessage.A_STLS_VERSION, 0, null).writeTo(c.out);
                try {
                    c.tls = AdbTls.clientOver(ssl, s, host, port);
                    c.in = new DataInputStream(c.tls.getInputStream());
                    c.out = c.tls.getOutputStream();
                    // TLS 1.3 下 adbd 校验客户端证书在我们的握手"完成"之后：被拒体现在这第一次读上（致命警报）
                    m = AdbMessage.read(c.in);
                } catch (IOException e) {
                    if (isRejection(e)) throw new KeyRejectedException(e.getClass().getSimpleName());
                    throw e;
                }
            } else if (m.command == AdbMessage.A_AUTH) {
                // 老的 RSA 签名认证只在 USB / adb tcpip 上用；无线调试端口一定走 TLS
                throw new IOException("对方要老式 AUTH 认证，不是无线调试端口");
            }
            if (m.command != AdbMessage.A_CNXN) throw new IOException("没等到 CNXN，收到 " + AdbMessage.name(m.command));
            int end = m.data.length;
            while (end > 0 && m.data[end - 1] == 0) end--;   // banner 末尾带 \0
            c.banner = new String(m.data, 0, end, StandardCharsets.UTF_8);
            ok = true;
            return c;
        } finally {
            if (!ok) c.close();
        }
    }

    /**
     * TLS 握手 / 握手后第一次读的错误像不像"密钥被拒"：是 SSLException（收到致命警报、握手失败），
     * 而且不是 TLS 库把底下的 EOF / reset 包了一层。不按异常文字认（各 TLS 实现写法不同，太脆）。
     */
    static boolean isRejection(IOException e) {
        if (!(e instanceof SSLException)) return false;   // EOFException、SocketException（reset）、超时：对方断开，不是拒绝
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            if (c instanceof EOFException || c instanceof SocketException) return false;
        }
        return true;
    }

    /** adbd 的 CNXN 里带的设备描述（"device::ro.product.name=…;features=…"）。 */
    public String banner() {
        return banner;
    }

    /** adbd 支持 shell 协议 v2（CNXN 的 features 里有 shell_v2）：和电脑上 adb shell 走同一条路。 */
    public boolean shellV2() {
        int i = banner.indexOf("features=");
        if (i < 0) return false;
        int end = banner.indexOf(';', i);
        String features = banner.substring(i + 9, end < 0 ? banner.length() : end);
        for (String f : features.split(",")) if (f.equals("shell_v2")) return true;
        return false;
    }

    /**
     * 执行一条 shell 命令，等它结束（adbd 发 CLSE），返回输出（标准输出 + 标准错误，最多 64KB，多的丢掉）。
     * timeoutMs 是两次收到数据之间最多等多久（不是整条命令的总时长）。
     * adbd 支持就走 shell 协议 v2（"shell,v2,raw:"，电脑上 adb shell 也是这条）：2026-10-22 主力机上用老的 "shell:"
     * 起服务，服务刚起来就被整个杀掉（服务日志只有启动命令那一行），电脑走 v2 同一条命令却好好的。
     */
    public String shell(String command, int timeoutMs) throws IOException {
        boolean v2 = shellV2();
        int local = nextLocalId++;
        plainOrTls().setSoTimeout(timeoutMs);
        AdbMessage.withString(AdbMessage.A_OPEN, local, 0, (v2 ? "shell,v2,raw:" : "shell:") + command).writeTo(out);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ShellV2 packets = v2 ? new ShellV2() : null;
        int remote = 0;
        boolean stdinClosed = false;
        while (true) {
            AdbMessage m = AdbMessage.read(in);
            if (m.arg1 != local) continue;   // 不是这条流的（不该有）
            switch (m.command) {
                case AdbMessage.A_OKAY:
                    remote = m.arg0;
                    if (v2 && !stdinClosed) {
                        // 我们不往命令的标准输入写东西：先告诉 adbd 标准输入关了（电脑上 adb shell 读到输入结束也这么发）
                        stdinClosed = true;
                        new AdbMessage(AdbMessage.A_WRTE, local, remote, ShellV2.packet(ShellV2.ID_CLOSE_STDIN, new byte[0])).writeTo(out);
                    }
                    break;
                case AdbMessage.A_WRTE:
                    remote = m.arg0;
                    if (v2) {
                        packets.feed(m.data, output, OUTPUT_LIMIT);
                    } else {
                        int room = OUTPUT_LIMIT - output.size();
                        if (room > 0) output.write(m.data, 0, Math.min(room, m.data.length));
                    }
                    new AdbMessage(AdbMessage.A_OKAY, local, remote, null).writeTo(out);
                    break;
                case AdbMessage.A_CLSE:
                    if (remote != 0 || m.arg0 != 0) new AdbMessage(AdbMessage.A_CLSE, local, m.arg0, null).writeTo(out);
                    if (remote == 0 && output.size() == 0) throw new IOException("adbd 拒绝打开 shell");
                    lastExitCode = v2 ? packets.exitCode : -1;
                    return new String(output.toByteArray(), StandardCharsets.UTF_8);
                default:
                    throw new IOException("shell 流里收到 " + AdbMessage.name(m.command));
            }
        }
    }

    /** 上一条 shell 命令的退出码（v2 才有；拿不到是 -1）。 */
    public int lastExitCode() {
        return lastExitCode;
    }

    private int lastExitCode = -1;

    /**
     * shell 协议 v2 的分包（AOSP packages/modules/adb shell_protocol.h）：每包 id:u8 + 长度:u32（小端）+ 数据；
     * 1 标准输出、2 标准错误、3 退出码（1 字节）、4 关标准输入。包可能跨 WRTE 断开，所以攒着拆。
     */
    static final class ShellV2 {
        static final int ID_STDOUT = 1, ID_STDERR = 2, ID_EXIT = 3, ID_CLOSE_STDIN = 4;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int exitCode = -1;

        static byte[] packet(int id, byte[] data) {
            byte[] p = new byte[5 + data.length];
            p[0] = (byte) id;
            int n = data.length;
            p[1] = (byte) n;
            p[2] = (byte) (n >>> 8);
            p[3] = (byte) (n >>> 16);
            p[4] = (byte) (n >>> 24);
            System.arraycopy(data, 0, p, 5, n);
            return p;
        }

        void feed(byte[] data, ByteArrayOutputStream output, int limit) {
            pending.write(data, 0, data.length);
            byte[] buf = pending.toByteArray();
            int pos = 0;
            while (buf.length - pos >= 5) {
                int id = buf[pos] & 0xFF;
                long n = (buf[pos + 1] & 0xFFL) | (buf[pos + 2] & 0xFFL) << 8 | (buf[pos + 3] & 0xFFL) << 16 | (buf[pos + 4] & 0xFFL) << 24;
                if (buf.length - pos - 5 < n) break;   // 这一包还没收全
                int start = pos + 5;
                int len = (int) n;
                if (id == ID_STDOUT || id == ID_STDERR) {
                    int room = limit - output.size();
                    if (room > 0) output.write(buf, start, Math.min(room, len));
                } else if (id == ID_EXIT && len >= 1) {
                    exitCode = buf[start] & 0xFF;
                }
                pos = start + len;
            }
            pending.reset();
            pending.write(buf, pos, buf.length - pos);
        }
    }

    private Socket plainOrTls() {
        return tls != null ? tls : plain;
    }

    @Override
    public void close() {
        try {
            if (tls != null) tls.close();
        } catch (IOException ignored) {
            // 已经关了
        }
        try {
            plain.close();
        } catch (IOException ignored) {
            // 已经关了
        }
    }
}
