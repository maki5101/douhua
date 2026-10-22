package dev.douhua.adb;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.AEADBadTagException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * 无线调试配对的客户端（"使用配对码配对设备"那个端口）。纯 JDK（导出密钥材料由调用方给，见 {@link AdbTls.Exporter}）。
 * 协议照 AOSP adb pairing_connection/pairing_connection.cpp：
 * <ol>
 * <li>TCP 连上，TLS 1.3 握手（自签证书，互不校验）；</li>
 * <li>导出 64 字节密钥材料（标签 "adb-label\0"），口令 = 配对码 UTF-8 + 这 64 字节；</li>
 * <li>互发 SPAKE2 消息（包头 6 字节：版本 1、类型 0、长度 u32 大端）；</li>
 * <li>AES-128-GCM 加密互发 PeerInfo（8192 字节：类型 1 字节 + 数据补 0；类型 1 = PEER_INFO），客户端发 ADB_RSA_PUB_KEY。</li>
 * </ol>
 * 配对码、口令、导出的密钥材料都不写日志、不进异常信息。
 */
public final class PairingClient {
    public static final int HEADER_VERSION = 1;
    public static final int TYPE_SPAKE2_MSG = 0;
    public static final int TYPE_PEER_INFO = 1;
    public static final int PEER_INFO_SIZE = 8192;
    public static final int MAX_PAYLOAD = PEER_INFO_SIZE * 2;
    public static final int PEER_RSA_PUB_KEY = 0;
    public static final int PEER_DEVICE_GUID = 1;

    /**
     * 配对码不对。主路径：我们解不开对方的 PeerInfo（cause 是 AEADBadTagException）；兜底：对方没发 PeerInfo 就断开（cause 是 EOF / SSLException）。
     * cause 只是异常类型，不含配对码、口令。
     */
    public static final class WrongCodeException extends IOException {
        WrongCodeException(Throwable cause) {
            super("配对码不对", cause);
        }
    }

    private final SSLContext ssl;
    private final AdbTls.Exporter exporter;
    private final int timeoutMs;

    public PairingClient(SSLContext ssl, AdbTls.Exporter exporter, int timeoutMs) {
        this.ssl = ssl;
        this.exporter = exporter;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 配对。publicKey = {@link AdbPublicKey#encodeBytes} 的结果。成功返回对方报的设备 guid（可能是空串）。
     * 配对码不对抛 {@link WrongCodeException}；连不上 / 断了抛别的 IOException。
     */
    public String pair(String host, int port, String code, byte[] publicKey) throws IOException {
        if (publicKey.length > PEER_INFO_SIZE - 2) throw new IllegalArgumentException("公钥太长");
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            plain.setTcpNoDelay(true);
            SSLSocket tls = AdbTls.clientOver(ssl, plain, host, port);
            byte[] exported = exporter.export(tls, AdbTls.EXPORTER_LABEL, null, AdbTls.EXPORTED_KEY_SIZE);
            if (exported == null || exported.length != AdbTls.EXPORTED_KEY_SIZE) {
                throw new IOException("导出 TLS 密钥材料失败");
            }
            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            byte[] password = new byte[codeBytes.length + exported.length];
            System.arraycopy(codeBytes, 0, password, 0, codeBytes.length);
            System.arraycopy(exported, 0, password, codeBytes.length, exported.length);
            PairingAuth auth = new PairingAuth(true, password);
            java.util.Arrays.fill(password, (byte) 0);

            DataInputStream in = new DataInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();
            writePacket(out, TYPE_SPAKE2_MSG, auth.message());
            byte[] theirMsg = readPacket(in, TYPE_SPAKE2_MSG);
            try {
                auth.initCipher(theirMsg);
            } catch (GeneralSecurityException e) {
                throw new IOException("对方的 SPAKE2 消息不对");
            }

            byte[] ours;
            try {
                ours = auth.encrypt(peerInfo(PEER_RSA_PUB_KEY, publicKey));
            } catch (GeneralSecurityException e) {
                throw new IOException("加密 PeerInfo 失败");
            }
            writePacket(out, TYPE_PEER_INFO, ours);
            byte[] theirs;
            try {
                theirs = readPacket(in, TYPE_PEER_INFO);
            } catch (EOFException e) {
                // AOSP DoExchangePeerInfo 先写自己的 PeerInfo 再读对方的：配对码错时 adbd 照样先发来它的 PeerInfo，
                // 我们 decrypt 抛 AEADBadTagException（下面那条）；读到 EOF / TLS 断开只是兜底（对方提前断开等）
                throw new WrongCodeException(e);
            } catch (javax.net.ssl.SSLException e) {
                throw new WrongCodeException(e);
            }
            byte[] info;
            try {
                info = auth.decrypt(theirs);
            } catch (AEADBadTagException e) {
                // 真 adbd 上配对码错走的就是这条（口令不同 → 两边的 AES 密钥不同）。别删这条分类
                throw new WrongCodeException(e);
            } catch (GeneralSecurityException e) {
                throw new IOException("解密 PeerInfo 失败");
            }
            if (info.length != PEER_INFO_SIZE) throw new IOException("对方的 PeerInfo 长度不对");
            return info[0] == PEER_DEVICE_GUID ? cString(info, 1) : "";
        } finally {
            try {
                plain.close();
            } catch (IOException ignored) {
                // 已经关了
            }
        }
    }

    // ═══════════ 包格式（测试服务端也用） ═══════════

    /** 8192 字节的 PeerInfo：类型 + 数据，后面补 0（末尾至少留一个 0）。 */
    public static byte[] peerInfo(int type, byte[] data) {
        byte[] p = new byte[PEER_INFO_SIZE];
        p[0] = (byte) type;
        System.arraycopy(data, 0, p, 1, Math.min(data.length, PEER_INFO_SIZE - 2));
        return p;
    }

    public static void writePacket(OutputStream out, int type, byte[] payload) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(6);   // 默认大端
        h.put((byte) HEADER_VERSION).put((byte) type).putInt(payload.length);
        out.write(h.array());
        out.write(payload);
        out.flush();
    }

    /** 读一个包，类型必须是 wantType；版本、长度不对抛 IOException。 */
    public static byte[] readPacket(DataInputStream in, int wantType) throws IOException {
        int version = in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int length = in.readInt();
        if (version != HEADER_VERSION) throw new IOException("配对包版本不对：" + version);
        if (type != wantType) throw new IOException("配对包类型不对：" + type);
        if (length <= 0 || length > MAX_PAYLOAD) throw new IOException("配对包长度不对：" + length);
        byte[] b = new byte[length];
        in.readFully(b);
        return b;
    }

    static String cString(byte[] b, int from) {
        int end = from;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, from, end - from, StandardCharsets.UTF_8);
    }
}
