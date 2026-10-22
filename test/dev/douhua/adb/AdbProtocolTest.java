package dev.douhua.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * 豆花自己连 adbd 的协议部分（消息帧、adb 公钥格式、自签证书、配对客户端、连接客户端）的离线测试。纯 JDK。
 * <p>
 * adb 公钥格式的对照：本机 `adb keygen` 生成的一对里只取公钥文件的 Base64 部分（不带 user@host 注释，不放私钥），
 * 解出 n、e 后用我们的编码器重新编码，必须逐字节等于 adb 自己写的（n0inv、rr 两个派生值就对上了）。
 * 配对客户端 / 连接客户端跑在本进程里的测试服务端上：JDK 17 没有导出 TLS 密钥材料的接口，两边用同一个假导出器，
 * 只验证包格式、SPAKE2 / AES-GCM / PeerInfo 流程和报错分类；和真 adb 的互通另见交接说明。
 */
public final class AdbProtocolTest {
    private static int failures;
    private static int checks;

    /** `adb keygen` 生成的公钥（只有公钥的 Base64，注释已去掉）。 */
    static final String ADB_KEYGEN_PUB =
            "QAAAAG1cn7Ob8jQ/8CpvyycqJ9Py3c+7luvW5OXyIipCbJYJzRIZHJ4bAXbIv0FDkVrvT9ezD1TVruuBJW7FCVl64Ga2rOcRQ3hbYxnPzUgjjehrE0LO"
            + "9cU/Y18LTvbwOrvVGn+bi8S86rwmuPKRUMCuB51RoPQh2ZPmBtSNOKdkYGd+UV/cAc1/JgkP3GatYkPrTmV48D07Pfp4WbXxbL3MQtm/+PXK+NpHnKui"
            + "K91ZaW/8KgvauSKU5pZPM68I++ycrs7CBUilBue5aHWyEdeaAH8GzgGmIkZk9sXKlYVrVWeD+tfJtmBVHezIdCTrrv0IvoyL4vNWBidlwSlhiocPVRfO"
            + "I7/QmPLLb0prucl7dZhr7uMbTutH+ksn9luy12+hXaSuxbxe9Lm0pO4z6mJDPqZMdlbC6fK3p4CzhGjtVO68DvS/vxbzKIiQ7X9awQfhQ1Td7dU3ZM5e"
            + "aIDtklDjZ+uQOz9CLuv2z7y4Q/w6lWTD2NugVMdWNtSCbSM++7nIBTYWYqH2LAiTa6LtA8EBN4QjL59NszvcfjWAybhTGiKdSjlAmIR4/N0bkBdwec0R"
            + "EdlVKQp9Ph5U6VYGi9oAJuXa/N3FXizK1WRH3oIK8N5wexDgDPXy9tpJF7dirFhjHBdjcedM+PYg9660LxvYZUDIzfDfUcG/56wEOTARKTXrLcSxzgEA"
            + "AQA=";

    private static final byte[] FAKE_EXPORT = new byte[64];

    static {
        for (int i = 0; i < 64; i++) FAKE_EXPORT[i] = (byte) (i * 7 + 3);
    }

    private static final AdbTls.Exporter FAKE_EXPORTER = (s, label, ctx, len) -> {
        if (!AdbTls.EXPORTER_LABEL.equals(label) || ctx != null || len != 64) throw new IOException("导出参数不对");
        return FAKE_EXPORT.clone();
    };

    public static void main(String[] args) throws Exception {
        messages();
        shellV2Packets();
        publicKey();
        KeyPair kp = rsa();
        KeyPair other = rsa();
        X509Certificate cert = certificate(kp);
        X509Certificate otherCert = SelfSignedCert.create(other.getPublic(), other.getPrivate(), System.currentTimeMillis());
        SSLContext mine = AdbTls.context(kp.getPrivate(), cert);
        SSLContext theirs = AdbTls.context(other.getPrivate(), otherCert);
        pairing(mine, theirs, kp);
        // 假 adbd 自己的证书用另一把；只信 kp 的公钥（照 adbd：不在 adb_keys 就在 TLS 层拒绝）
        SSLContext adbd = adbdContext(other, otherCert, kp.getPublic());
        connection(mine, theirs, adbd);
        System.out.println("adb 协议测试：" + checks + " 项，失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 消息帧 ═══════════

    static void messages() throws IOException {
        AdbMessage m = AdbMessage.withString(AdbMessage.A_CNXN, AdbMessage.A_VERSION, AdbMessage.MAX_PAYLOAD, "host::");
        byte[] b = m.encode();
        check(b.length == 24 + 7, "CNXN 长度 = 24 + \"host::\\0\"");
        eq(hex(Arrays.copyOf(b, 24)),
                "434e584e" + "00000001" + "00000400" + "07000000" + "32020000" + "bcb1a7b1",
                "CNXN 头逐字节（小端：命令、版本、最大数据、长度、字节和、magic）");
        check(b[b.length - 1] == 0, "字符串数据末尾带 \\0");
        AdbMessage back = AdbMessage.read(new ByteArrayInputStream(b));
        check(back.command == AdbMessage.A_CNXN && back.arg0 == AdbMessage.A_VERSION
                && back.arg1 == AdbMessage.MAX_PAYLOAD && Arrays.equals(back.data, m.data), "CNXN 编码后读回一样");

        byte[] noData = new AdbMessage(AdbMessage.A_OKAY, 5, 9, null).encode();
        check(noData.length == 24, "没数据的消息只有头");
        AdbMessage okay = AdbMessage.read(new ByteArrayInputStream(noData));
        check(okay.command == AdbMessage.A_OKAY && okay.arg0 == 5 && okay.arg1 == 9 && okay.data.length == 0, "OKAY 读回");

        byte[] highBytes = {(byte) 0xff, (byte) 0x80, 1};
        check(AdbMessage.checksum(highBytes) == 0xff + 0x80 + 1, "字节和按无符号算");

        byte[] bad = b.clone();
        bad[20] ^= 1;
        expectIo(bad, "magic 不符被拒");
        byte[] badSum = b.clone();
        badSum[16] ^= 1;
        expectIo(badSum, "字节和不对被拒");
        byte[] zeroSum = b.clone();
        zeroSum[16] = zeroSum[17] = zeroSum[18] = zeroSum[19] = 0;
        try {
            AdbMessage.read(new ByteArrayInputStream(zeroSum));
            check(true, "字节和为 0（新协议不算）照收");
        } catch (IOException e) {
            check(false, "字节和为 0（新协议不算）照收");
        }
        byte[] huge = new AdbMessage(AdbMessage.A_WRTE, 1, 1, null).encode();
        huge[12] = 0;
        huge[13] = 0;
        huge[14] = (byte) 0x80;   // 8MB
        expectIo(huge, "长度离谱被拒");
        try {
            AdbMessage.read(new ByteArrayInputStream(Arrays.copyOf(b, 30)));
            check(false, "数据不全抛 EOF");
        } catch (EOFException e) {
            check(true, "数据不全抛 EOF");
        }
        check(AdbMessage.A_STLS == 0x534c5453 && AdbMessage.A_OKAY == 0x59414b4f && AdbMessage.A_WRTE == 0x45545257,
                "命令码 = 四个 ASCII 字母小端");
        check("STLS".equals(AdbMessage.name(AdbMessage.A_STLS)), "命令名");
    }

    static void expectIo(byte[] b, String what) {
        try {
            AdbMessage.read(new ByteArrayInputStream(b));
            check(false, what);
        } catch (EOFException e) {
            check(false, what + "（抛的是 EOF）");
        } catch (IOException e) {
            check(true, what);
        }
    }

    // ═══════════ adb 公钥格式 ═══════════

    static void publicKey() throws Exception {
        BigInteger[] ne = AdbPublicKey.decode(ADB_KEYGEN_PUB + " someone@somewhere");
        check(ne[0].bitLength() == 2048, "adb keygen 的模数 2048 位");
        check(ne[1].equals(BigInteger.valueOf(65537)), "指数 65537");
        String ours = AdbPublicKey.encode(ne[0], ne[1], "douhua");
        eq(ours.split(" ")[0], ADB_KEYGEN_PUB, "重新编码 = adb keygen 写的（n0inv、rr 对上）");
        check(ours.endsWith(" douhua"), "名字在空格后面");
        check(Base64.getDecoder().decode(ADB_KEYGEN_PUB).length == AdbPublicKey.ENCODED_SIZE, "524 字节");

        KeyPair kp = rsa();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] bytes = AdbPublicKey.encodeBytes(pub, "douhua");
        BigInteger[] back = AdbPublicKey.decode(new String(bytes, StandardCharsets.UTF_8));
        check(back[0].equals(pub.getModulus()) && back[1].equals(pub.getPublicExponent()), "新生成的钥匙编码后解回一样");
        byte[] bin = AdbPublicKey.encodeBinary(pub.getModulus(), pub.getPublicExponent());
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        check(bb.getInt() == 64, "modulus_size_words = 64");
        long n0inv = bb.getInt() & 0xffffffffL;
        long n0 = pub.getModulus().mod(BigInteger.ONE.shiftLeft(32)).longValue();
        check(((n0 * n0inv) & 0xffffffffL) == 0xffffffffL, "n0inv · N[0] ≡ -1 (mod 2^32)");
        try {
            AdbPublicKey.encodeBinary(BigInteger.ONE.shiftLeft(1023).add(BigInteger.ONE), BigInteger.valueOf(65537));
            check(false, "1024 位被拒");
        } catch (IllegalArgumentException e) {
            check(true, "1024 位被拒");
        }
        try {
            AdbPublicKey.decode(Base64.getEncoder().encodeToString(new byte[100]));
            check(false, "长度不对的公钥被拒");
        } catch (IllegalArgumentException e) {
            check(true, "长度不对的公钥被拒");
        }
    }

    // ═══════════ 自签证书 ═══════════

    static X509Certificate certificate(KeyPair kp) throws Exception {
        long now = 1_790_000_000_000L;   // 2026 年
        X509Certificate c = SelfSignedCert.create(kp.getPublic(), kp.getPrivate(), now);
        c.verify(kp.getPublic());   // 签名对（不对会抛）
        check(true, "证书签名用自己的公钥验得过");
        check(c.getVersion() == 3, "v3");
        check(c.getSerialNumber().equals(BigInteger.ONE), "序列号 1");
        check("SHA256withRSA".equalsIgnoreCase(c.getSigAlgName()), "签名算法 SHA256withRSA（实际 " + c.getSigAlgName() + "）");
        String dn = c.getSubjectX500Principal().getName();
        check(dn.contains("CN=Adb") && dn.contains("O=Android") && dn.contains("C=US"), "主体 C=US, O=Android, CN=Adb（" + dn + "）");
        check(c.getSubjectX500Principal().equals(c.getIssuerX500Principal()), "签发者 = 主体");
        check(c.getNotBefore().getTime() == now, "生效时间");
        check(c.getNotAfter().getTime() - now == 10L * 365 * 24 * 3600 * 1000, "有效期 10 年");
        check(c.getBasicConstraints() == Integer.MAX_VALUE, "basicConstraints CA:TRUE");
        boolean[] ku = c.getKeyUsage();
        check(ku != null && ku[0] && ku[5] && ku[6] && !ku[1] && !ku[2], "keyUsage = digitalSignature | keyCertSign | cRLSign");
        check(c.getCriticalExtensionOIDs().contains("2.5.29.19") && c.getCriticalExtensionOIDs().contains("2.5.29.15"),
                "basicConstraints、keyUsage 是 critical");
        byte[] skiExt = c.getExtensionValue("2.5.29.14");
        byte[] want = SelfSignedCert.ski(kp.getPublic().getEncoded());
        check(skiExt != null && skiExt.length == 24 && Arrays.equals(Arrays.copyOfRange(skiExt, 4, 24), want),
                "subjectKeyIdentifier = 公钥位串的 SHA-1");
        check(Arrays.equals(c.getPublicKey().getEncoded(), kp.getPublic().getEncoded()), "证书里的公钥就是这把");
        check(SelfSignedCert.time(2524608000000L)[0] == 0x18, "2050 年起用 GeneralizedTime");
        check(SelfSignedCert.time(now)[0] == 0x17, "2050 年以前用 UTCTime");
        eq(hex(SelfSignedCert.oid("1.2.840.113549.1.1.11")), "06092a864886f70d01010b", "OID 编码");
        check(SelfSignedCert.tlv(0x04, new byte[300])[1] == (byte) 0x82, "长度 ≥ 256 用两字节长度");
        return c;
    }

    // ═══════════ 配对客户端 ═══════════

    static void pairing(SSLContext client, SSLContext server, KeyPair kp) throws Exception {
        byte[] pub = AdbPublicKey.encodeBytes((RSAPublicKey) kp.getPublic(), "douhua");
        // 1. 配对码对：服务端拿到我们的公钥，我们拿到它的 guid
        AtomicReference<byte[]> got = new AtomicReference<>();
        int port = pairServer(server, "123456", "adb-TESTGUID", got, false);
        String guid = new PairingClient(client, FAKE_EXPORTER, 5000).pair("127.0.0.1", port, "123456", pub);
        eq(guid, "adb-TESTGUID", "配对成功，拿到对方的 guid");
        lastPairServer.join(5000);   // AOSP 顺序下服务端先发自己的、后读我们的：等它读完
        byte[] info = got.get();
        check(info != null && info.length == PairingClient.PEER_INFO_SIZE && info[0] == PairingClient.PEER_RSA_PUB_KEY,
                "服务端收到 8192 字节、类型 ADB_RSA_PUB_KEY 的 PeerInfo");
        check(info != null && PairingClient.cString(info, 1).equals(new String(pub, StandardCharsets.UTF_8)),
                "PeerInfo 里是 adb 格式的公钥");

        // 2. 配对码错（真 adbd 的路）：AOSP 先发自己的 PeerInfo 再读对方的，我们解不开它的 → WrongCode（cause 是 AEADBadTag）
        AtomicReference<byte[]> gotWrong = new AtomicReference<>();
        port = pairServer(server, "654321", "x", gotWrong, false);
        try {
            new PairingClient(client, FAKE_EXPORTER, 5000).pair("127.0.0.1", port, "123456", pub);
            check(false, "配对码错（对方先发 PeerInfo，解不开）报 WrongCode");
        } catch (PairingClient.WrongCodeException e) {
            check(e.getCause() instanceof javax.crypto.AEADBadTagException,
                    "配对码错（对方先发 PeerInfo，解不开）报 WrongCode，走的是解密失败那条（" + e.getCause() + "）");
        }
        lastPairServer.join(5000);
        check(gotWrong.get() == null, "配对码错时服务端也解不开我们的 PeerInfo");
        // 3. 兜底：对方收了我们的 PeerInfo、没发它的就断开 → 同样 WrongCode（cause 是 EOF / TLS 断开）
        port = pairServer(server, "654321", "x", new AtomicReference<>(), true);
        try {
            new PairingClient(client, FAKE_EXPORTER, 5000).pair("127.0.0.1", port, "123456", pub);
            check(false, "对方提前断开报 WrongCode");
        } catch (PairingClient.WrongCodeException e) {
            check(!(e.getCause() instanceof javax.crypto.AEADBadTagException), "对方提前断开报 WrongCode，走的是断开那条（" + e.getCause() + "）");
        }
        // 4. 端口没人：普通 IOException，不是 WrongCode
        int dead;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dead = s.getLocalPort();
        }
        try {
            new PairingClient(client, FAKE_EXPORTER, 2000).pair("127.0.0.1", dead, "123456", pub);
            check(false, "端口没人抛 IOException");
        } catch (PairingClient.WrongCodeException e) {
            check(false, "端口没人不该报 WrongCode");
        } catch (IOException e) {
            check(true, "端口没人抛 IOException");
        }
        // 包格式
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PairingClient.writePacket(bo, PairingClient.TYPE_PEER_INFO, new byte[]{9, 8, 7});
        eq(hex(bo.toByteArray()), "0101" + "00000003" + "090807", "配对包头：版本 1、类型、长度大端");
        try {
            byte[] wrong = bo.toByteArray();
            wrong[0] = 2;
            PairingClient.readPacket(new DataInputStream(new ByteArrayInputStream(wrong)), PairingClient.TYPE_PEER_INFO);
            check(false, "版本 2 的配对包被拒");
        } catch (IOException e) {
            check(true, "版本 2 的配对包被拒");
        }
        try {
            PairingClient.readPacket(new DataInputStream(new ByteArrayInputStream(bo.toByteArray())), PairingClient.TYPE_SPAKE2_MSG);
            check(false, "类型不对的配对包被拒");
        } catch (IOException e) {
            check(true, "类型不对的配对包被拒");
        }
    }

    /** 最近一个 pairServer 的线程（测试等它收完尾再看 got）。 */
    static Thread lastPairServer;

    /**
     * 本进程里的配对服务端（照 adbd 的 pairing server：Bob、SERVER_NAME；顺序照 AOSP pairing_connection.cpp 的
     * DoExchangePeerInfo：先加密写出自己的 PeerInfo，再读对方的，解不开就断开）。
     * closeEarly：读完对方的 PeerInfo 就断开、不发自己的（AOSP 不是这个顺序，只为测客户端的兜底分支）。
     * 返回端口；解开的客户端 PeerInfo 放进 got（解不开留 null）。
     */
    static int pairServer(SSLContext ssl, String code, String guid, AtomicReference<byte[]> got, boolean closeEarly)
            throws IOException {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (ServerSocket s0 = ss; Socket raw = s0.accept()) {
                raw.setSoTimeout(5000);
                SSLSocket tls = (SSLSocket) ssl.getSocketFactory().createSocket(raw, null, raw.getPort(), true);
                tls.setUseClientMode(false);
                tls.setEnabledProtocols(new String[]{"TLSv1.3"});
                tls.startHandshake();
                byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
                byte[] pw = Arrays.copyOf(codeBytes, codeBytes.length + 64);
                System.arraycopy(FAKE_EXPORT, 0, pw, codeBytes.length, 64);
                PairingAuth auth = new PairingAuth(false, pw);
                DataInputStream in = new DataInputStream(tls.getInputStream());
                OutputStream out = tls.getOutputStream();
                byte[] theirMsg = PairingClient.readPacket(in, PairingClient.TYPE_SPAKE2_MSG);
                PairingClient.writePacket(out, PairingClient.TYPE_SPAKE2_MSG, auth.message());
                auth.initCipher(theirMsg);
                if (closeEarly) {
                    PairingClient.readPacket(in, PairingClient.TYPE_PEER_INFO);
                    return;
                }
                byte[] mine = auth.encrypt(PairingClient.peerInfo(PairingClient.PEER_DEVICE_GUID,
                        guid.getBytes(StandardCharsets.UTF_8)));
                PairingClient.writePacket(out, PairingClient.TYPE_PEER_INFO, mine);
                byte[] enc = PairingClient.readPacket(in, PairingClient.TYPE_PEER_INFO);
                try {
                    got.set(auth.decrypt(enc));
                } catch (javax.crypto.AEADBadTagException e) {
                    return;   // adbd：解不开就断开
                }
                in.read();   // 等对方关
            } catch (Exception ignored) {
                // 测试服务端：出错就关
            }
        }, "pair-server");
        t.setDaemon(true);
        t.start();
        lastPairServer = t;
        return ss.getLocalPort();
    }

    // ═══════════ 连接客户端 ═══════════

    static void connection(SSLContext client, SSLContext stranger, SSLContext adbd) throws Exception {
        // 1. 正常：CNXN → STLS → TLS → CNXN → shell，输出分两次 WRTE
        AtomicReference<String> opened = new AtomicReference<>();
        int port = fakeAdbd(adbd, false, opened, "line1\n", "line2\n");
        try (AdbConnection c = AdbConnection.open(client, "127.0.0.1", port, 5000)) {
            check(c.banner().startsWith("device::"), "收到 adbd 的 CNXN（" + c.banner() + "）");
            String out = c.shell("echo hi", 5000);
            eq(out, "line1\nline2\n", "shell 输出拼起来");
            eq(opened.get(), "shell:echo hi", "OPEN 的服务名");
        }
        // 2. 密钥不在 adb_keys：adbd 在 TLS 层拒绝（致命警报）→ KeyRejectedException
        port = fakeAdbd(adbd, false, new AtomicReference<>(), "x");
        try (AdbConnection c = AdbConnection.open(stranger, "127.0.0.1", port, 5000)) {
            check(false, "没配对的密钥被拒报 KeyRejected");
        } catch (AdbConnection.KeyRejectedException e) {
            check(true, "没配对的密钥被拒报 KeyRejected");
        }
        // 2b. 握手成功后对方直接断开（无线调试正好被关、adbd 踢掉所有 TLS 连接）：普通 IOException，不算密钥被拒
        port = fakeAdbd(adbd, true, new AtomicReference<>(), "x");
        try (AdbConnection c = AdbConnection.open(client, "127.0.0.1", port, 5000)) {
            check(false, "握手后被断开抛 IOException");
        } catch (AdbConnection.KeyRejectedException e) {
            check(false, "握手后被断开不该报 KeyRejected");
        } catch (IOException e) {
            check(true, "握手后被断开抛普通 IOException（" + e.getClass().getSimpleName() + "）");
        }
        check(!AdbConnection.isRejection(new EOFException()), "EOF 不算被拒");
        check(!AdbConnection.isRejection(new java.net.SocketException("Connection reset")), "reset 不算被拒");
        check(!AdbConnection.isRejection(new javax.net.ssl.SSLException("closed", new EOFException())), "包着 EOF 的 SSLException 不算被拒");
        check(AdbConnection.isRejection(new javax.net.ssl.SSLHandshakeException("Received fatal alert: certificate_unknown")),
                "致命警报算被拒");
        // 3. 端口没人：ConnectException，不是 KeyRejected
        int dead;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dead = s.getLocalPort();
        }
        try (AdbConnection c = AdbConnection.open(client, "127.0.0.1", dead, 2000)) {
            check(false, "端口没人抛 ConnectException");
        } catch (java.net.ConnectException e) {
            check(true, "端口没人抛 ConnectException");
        }
    }

    /**
     * 本进程里的假 adbd（照 AOSP：收 CNXN 回 STLS，收 STLS 后 TLS 服务端、要客户端证书，证书公钥不在 adb_keys 就在 TLS 层拒绝
     * （ssl 的信任管理器抛错 → 致命警报，见 {@link #adbdContext}）；然后发 CNXN，收 OPEN 回 OKAY + 每段输出一条 WRTE（等对方 OKAY）+ CLSE）。
     * kick：握手成功后马上断开、不发警报（adbd 关无线调试时踢掉所有 TLS 连接就是这样）。
     */
    static int fakeAdbd(SSLContext ssl, boolean kick, AtomicReference<String> opened, String... outputs)
            throws IOException {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (ServerSocket s0 = ss; Socket raw = s0.accept()) {
                raw.setSoTimeout(5000);
                InputStream in = raw.getInputStream();
                OutputStream out = raw.getOutputStream();
                AdbMessage m = AdbMessage.read(in);
                if (m.command != AdbMessage.A_CNXN) return;
                new AdbMessage(AdbMessage.A_STLS, AdbMessage.A_STLS_VERSION, 0, null).writeTo(out);
                m = AdbMessage.read(in);
                if (m.command != AdbMessage.A_STLS) return;
                SSLSocket tls = (SSLSocket) ssl.getSocketFactory().createSocket(raw, null, raw.getPort(), true);
                tls.setUseClientMode(false);
                tls.setNeedClientAuth(true);
                tls.setEnabledProtocols(new String[]{"TLSv1.3"});
                tls.startHandshake();   // 不在 adb_keys：这里抛错，警报已经发给对方
                if (kick) {
                    raw.close();
                    return;
                }
                in = tls.getInputStream();
                out = tls.getOutputStream();
                AdbMessage.withString(AdbMessage.A_CNXN, AdbMessage.A_VERSION, 4096, "device::ro.product.name=test;").writeTo(out);
                m = AdbMessage.read(in);
                if (m.command != AdbMessage.A_OPEN) return;
                int local = m.arg0;
                opened.set(new String(m.data, 0, m.data.length - 1, StandardCharsets.UTF_8));
                int remote = 77;
                new AdbMessage(AdbMessage.A_OKAY, remote, local, null).writeTo(out);
                for (String o : outputs) {
                    new AdbMessage(AdbMessage.A_WRTE, remote, local, o.getBytes(StandardCharsets.UTF_8)).writeTo(out);
                    AdbMessage ack = AdbMessage.read(in);
                    if (ack.command != AdbMessage.A_OKAY || ack.arg1 != remote) return;
                }
                new AdbMessage(AdbMessage.A_CLSE, remote, local, null).writeTo(out);
                AdbMessage.read(in);   // 对方回 CLSE
            } catch (Exception ignored) {
                // 测试服务端：出错就关
            }
        }, "fake-adbd");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    /** 假 adbd 的 TLS：自己的证书 + 只信 trusted 这把公钥（不信就抛 CertificateException，JDK 发致命警报，和 adbd 的校验回调返回 0 一样）。 */
    static SSLContext adbdContext(KeyPair kp, X509Certificate cert, PublicKey trusted) throws Exception {
        char[] pw = "test".toCharArray();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("adbd", kp.getPrivate(), pw, new java.security.cert.Certificate[]{cert});
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, pw);
        javax.net.ssl.TrustManager tm = new javax.net.ssl.X509ExtendedTrustManager() {
            private void verify(X509Certificate[] chain) throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0 || !Arrays.equals(chain[0].getPublicKey().getEncoded(), trusted.getEncoded())) {
                    // 等客户端把这一轮握手消息写完再拒：JDK 服务端一看到证书就拒、马上关，客户端还在写后面几条，会碰上"断开的管道"；
                    // 真 adbd（BoringSSL）对面的客户端整轮一次写出，看到的是致命警报（scratchpad 的 BoringSSL 端到端核对过）
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    throw new java.security.cert.CertificateException("不在 adb_keys");
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws java.security.cert.CertificateException {
                verify(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                    throws java.security.cert.CertificateException {
                verify(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext c = SSLContext.getInstance("TLSv1.3");
        c.init(kmf.getKeyManagers(), new javax.net.ssl.TrustManager[]{tm}, null);
        return c;
    }

    // ═══════════ 小工具 ═══════════

    static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    /** shell 协议 v2 的分包：跨 WRTE 断开也拆得对，标准输出 / 标准错误合在一起，退出码单独记，输出有上限。 */
    static void shellV2Packets() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        AdbConnection.ShellV2 v = new AdbConnection.ShellV2();
        byte[] a = AdbConnection.ShellV2.packet(AdbConnection.ShellV2.ID_STDOUT, "ab".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] b = AdbConnection.ShellV2.packet(AdbConnection.ShellV2.ID_STDERR, "c".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] e = AdbConnection.ShellV2.packet(AdbConnection.ShellV2.ID_EXIT, new byte[]{7});
        byte[] all = new byte[a.length + b.length + e.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(e, 0, all, a.length + b.length, e.length);
        // 拆成 3 字节、5 字节、剩下的，几处都断在包头 / 数据中间
        v.feed(java.util.Arrays.copyOfRange(all, 0, 3), out, 64);
        check(out.size() == 0, "v2：包头没收全时不出数据");
        v.feed(java.util.Arrays.copyOfRange(all, 3, 8), out, 64);
        v.feed(java.util.Arrays.copyOfRange(all, 8, all.length), out, 64);
        eq(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), "abc", "v2：标准输出 + 标准错误");
        check(v.exitCode == 7, "v2：退出码 7（实际 " + v.exitCode + "）");
        check(AdbConnection.ShellV2.packet(4, new byte[0]).length == 5, "v2：关标准输入是 5 字节的空包");
        java.io.ByteArrayOutputStream small = new java.io.ByteArrayOutputStream();
        new AdbConnection.ShellV2().feed(a, small, 1);
        check(small.size() == 1, "v2：输出有上限");
    }

    static void eq(String got, String want, String what) {
        check(got.equals(want), what + (got.equals(want) ? "" : "（期望 " + want + "，实际 " + got + "）"));
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
