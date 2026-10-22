package dev.douhua.adb;

import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 5869 HKDF，只做 SHA-256（adb 配对派生 AES 密钥用）。纯 JDK。 */
public final class Hkdf {
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    /** RFC 5869 HKDF-SHA256。salt 为 null 或空 = 32 个 0（Java 的 SecretKeySpec 不收空密钥，必须换成全 0 的 32 字节）。 */
    public static byte[] sha256(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        if (length < 0 || length > 255 * HASH_LEN) throw new IllegalArgumentException("HKDF 输出长度超出范围");
        if (salt == null || salt.length == 0) salt = new byte[HASH_LEN];   // RFC 5869 §2.2：不给就是 HashLen 个 0
        if (info == null) info = new byte[0];

        // 提取：PRK = HMAC(salt, IKM)
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        // 扩展：T(i) = HMAC(PRK, T(i-1) || info || i)
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        java.util.Arrays.fill(prk, (byte) 0);
        java.util.Arrays.fill(t, (byte) 0);
        return out;
    }
}
