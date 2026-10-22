package dev.douhua.adb;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * adb 无线配对的认证层：SPAKE2 + AES-128-GCM。对应 AOSP packages/modules/adb 的
 * pairing_auth/pairing_auth.cpp（PairingAuthCtx）和 pairing_auth/aes_128_gcm.cpp（Aes128Gcm）。纯 JDK。
 * <p>
 * 口令 = 配对码的 UTF-8 字节 + TLS 导出的 64 字节密钥材料（两边各自从同一条 TLS 连接导出，由 PairingClient 拼好传进来）。
 * 口令错了 SPAKE2 照样"成功"，只是两边的密钥不一样，第一次 {@link #decrypt} 就抛 AEADBadTagException。
 * <p>
 * 配对码、共享密钥、AES 密钥都不出现在异常信息里。
 */
public final class PairingAuth {
    /** "adb pair client" / "adb pair server" 加末尾 \0，各 16 字节（AOSP 用 sizeof，含 \0）。 */
    public static final byte[] CLIENT_NAME = "adb pair client\0".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] SERVER_NAME = "adb pair server\0".getBytes(StandardCharsets.US_ASCII);
    /** HKDF info，不含 \0（AOSP 用 sizeof(info) - 1）。 */
    public static final byte[] HKDF_INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII);
    public static final int GCM_TAG_BYTES = 16;
    private static final int KEY_BYTES = 16;
    private static final int NONCE_BYTES = 12;

    private final Spake2 spake2;
    private final byte[] ourMsg;
    private byte[] aesKey;   // initCipher 之后才有
    private boolean cipherTried;
    private long encSequence, decSequence;

    /** client = true：Alice、自己叫 CLIENT_NAME；false：Bob、SERVER_NAME。password = 配对码 UTF-8 字节 + 导出的 64 字节。 */
    public PairingAuth(boolean client, byte[] password) {
        this(client, password, null);
    }

    /** 测试 / 互通工具用：随机数由调用方给（null = SecureRandom）。 */
    PairingAuth(boolean client, byte[] password, byte[] random64) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("口令不能为空");
        spake2 = client
                ? new Spake2(Spake2.ROLE_ALICE, CLIENT_NAME, SERVER_NAME)
                : new Spake2(Spake2.ROLE_BOB, SERVER_NAME, CLIENT_NAME);
        ourMsg = random64 == null ? spake2.generateMessage(password) : spake2.generateMessage(password, random64);
    }

    /** 自己的 32 字节 SPAKE2 消息。 */
    public byte[] message() {
        return ourMsg.clone();
    }

    /**
     * 处理对方消息、派生 AES-128 密钥：HKDF-SHA256(ikm = 64 字节共享密钥, salt = 无, info = HKDF_INFO, 16)。
     * 只能调一次，不管成没成功（同 AOSP 的 InitCipher）；再调抛 IllegalStateException。
     */
    public void initCipher(byte[] theirMessage) throws GeneralSecurityException {
        if (cipherTried) throw new IllegalStateException("initCipher 只能调一次");
        cipherTried = true;
        byte[] keyMaterial = spake2.processMessage(theirMessage);
        try {
            aesKey = Hkdf.sha256(keyMaterial, null, HKDF_INFO, KEY_BYTES);
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
        }
    }

    /** AES-128-GCM，无 AAD，nonce 12 字节 = 8 字节小端发送计数 + 4 个 0，每次加密后计数 +1。输出 = 密文 + 16 字节标签。 */
    public byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] out = gcm(Cipher.ENCRYPT_MODE, key(), encSequence, plain);
        encSequence++;
        return out;
    }

    /** 同上，用接收计数。标签不对抛 AEADBadTagException（= 配对码不对）；成功才 +1。 */
    public byte[] decrypt(byte[] cipherText) throws GeneralSecurityException {
        byte[] out = gcm(Cipher.DECRYPT_MODE, key(), decSequence, cipherText);
        decSequence++;
        return out;
    }

    private byte[] key() {
        if (aesKey == null) throw new IllegalStateException("还没 initCipher（或 initCipher 失败了）");
        return aesKey;
    }

    /**
     * 一次 AES-128-GCM（Aes128Gcm::Encrypt / Decrypt）：nonce 前 8 字节是小端计数（AOSP 把 uint64 按机器序 memcpy 进去，
     * ARM / x86 都是小端），后 4 字节 0。每条消息新建 Cipher 实例（GCM 加密不许同一实例重复用 IV）。
     */
    static byte[] gcm(int mode, byte[] key16, long sequence, byte[] in) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (sequence >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key16, "AES"), new GCMParameterSpec(GCM_TAG_BYTES * 8, nonce));
        return c.doFinal(in);
    }
}
