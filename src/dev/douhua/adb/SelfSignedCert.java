package dev.douhua.adb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * adb 用的自签证书，手写 DER（不带 BouncyCastle）。纯 JDK。
 * 内容照 AOSP adb crypto/x509_generator.cpp：v3、序列号 1、有效期 10 年、主体 = 签发者 = C=US, O=Android, CN=Adb，
 * 扩展 basicConstraints（critical, CA:TRUE）、keyUsage（critical, keyCertSign | cRLSign | digitalSignature）、
 * subjectKeyIdentifier（公钥位串的 SHA-1），SHA256withRSA 签名。
 * adbd 只拿证书里的公钥去和 adb_keys 比（daemon/auth.cpp），证书其它字段不校验；照抄只是为了和官方客户端一样。
 */
public final class SelfSignedCert {
    private static final long LIFETIME_MS = 10L * 365 * 24 * 60 * 60 * 1000;

    private SelfSignedCert() {}

    /** 生成证书。publicKey 必须是 RSA，privateKey 是它的私钥。now 是生效时间（毫秒）。 */
    public static X509Certificate create(PublicKey publicKey, PrivateKey privateKey, long now)
            throws GeneralSecurityException {
        byte[] spki = publicKey.getEncoded();   // X.509 SubjectPublicKeyInfo 的 DER
        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), nul());   // sha256WithRSAEncryption
        byte[] name = seq(
                set(seq(oid("2.5.4.6"), printable("US"))),
                set(seq(oid("2.5.4.10"), utf8("Android"))),
                set(seq(oid("2.5.4.3"), utf8("Adb"))));
        byte[] validity = seq(time(now), time(now + LIFETIME_MS));
        byte[] extensions = explicit(3, seq(
                seq(oid("2.5.29.19"), bool(true), octets(seq(bool(true)))),
                seq(oid("2.5.29.15"), bool(true), octets(keyUsage())),
                seq(oid("2.5.29.14"), octets(octets(ski(spki))))));
        byte[] tbs = seq(
                explicit(0, integer(BigInteger.valueOf(2))),   // v3
                integer(BigInteger.ONE),
                sigAlg,
                name,
                validity,
                name,
                spki,
                extensions);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey);
        s.update(tbs);
        byte[] cert = seq(tbs, sigAlg, bitString(s.sign()));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(cert));
    }

    /** keyUsage 位串：digitalSignature(0)、keyCertSign(5)、cRLSign(6) → 1000 0110，未用 1 位。 */
    private static byte[] keyUsage() {
        return tlv(0x03, new byte[]{1, (byte) 0x86});
    }

    /** subjectKeyIdentifier "hash"：SubjectPublicKeyInfo 里公钥位串（去掉未用位那个字节）的 SHA-1（RFC 5280 4.2.1.2 方法 1）。 */
    static byte[] ski(byte[] spki) throws GeneralSecurityException {
        Der d = new Der(spki);
        Der inner = new Der(d.contents());   // SEQUENCE { AlgorithmIdentifier, BIT STRING }
        inner.skip();
        byte[] bits = inner.contents();
        byte[] key = new byte[bits.length - 1];
        System.arraycopy(bits, 1, key, 0, key.length);
        return MessageDigest.getInstance("SHA-1").digest(key);
    }

    // ═══════════ DER 编码 ═══════════

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = content.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else if (n < 0x10000) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write(n >> 8);
            out.write(n);
        }
        out.write(content, 0, n);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    static byte[] seq(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    static byte[] explicit(int n, byte[] inner) {
        return tlv(0xa0 | n, inner);
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    static byte[] bool(boolean b) {
        return tlv(0x01, new byte[]{(byte) (b ? 0xff : 0)});
    }

    static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] octets(byte[] b) {
        return tlv(0x04, b);
    }

    static byte[] bitString(byte[] b) {
        return tlv(0x03, concat(new byte[]{0}, b));
    }

    static byte[] printable(String s) {
        return tlv(0x13, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] utf8(String s) {
        return tlv(0x0c, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int shift = 0;
            while ((v >> (shift + 7)) != 0) shift += 7;
            for (; shift > 0; shift -= 7) out.write((int) (0x80 | ((v >> shift) & 0x7f)));
            out.write((int) (v & 0x7f));
        }
        return tlv(0x06, out.toByteArray());
    }

    /** 2050 年以前用 UTCTime，以后用 GeneralizedTime（RFC 5280 4.1.2.5）。 */
    static byte[] time(long ms) {
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat gen = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT);
        gen.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date d = new Date(ms / 1000 * 1000);
        int year = Integer.parseInt(gen.format(d).substring(0, 4));
        if (year < 2050) return tlv(0x17, utc.format(d).getBytes(StandardCharsets.US_ASCII));
        return tlv(0x18, gen.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    /** 最小的 DER 读取器：只给 ski() 拆 SubjectPublicKeyInfo 用。 */
    private static final class Der {
        private final byte[] b;
        private int pos;

        Der(byte[] b) {
            this.b = b;
        }

        private int[] header() {
            int p = pos + 1;
            int len = b[p++] & 0xff;
            if (len >= 0x80) {
                int n = len & 0x7f;
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (b[p++] & 0xff);
            }
            return new int[]{p, len};
        }

        byte[] contents() {
            int[] h = header();
            byte[] out = new byte[h[1]];
            System.arraycopy(b, h[0], out, 0, h[1]);
            pos = h[0] + h[1];
            return out;
        }

        void skip() {
            int[] h = header();
            pos = h[0] + h[1];
        }
    }
}
