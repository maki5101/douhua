package dev.douhua.adb;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * adb 自己的 RSA 公钥格式（adbkey.pub、配对时 PeerInfo 里发的那串）。纯 JDK。
 * 出处：AOSP system/core/libcrypto_utils android_pubkey.c（RSAPublicKey 结构，524 字节、小端）：
 * modulus_size_words(=64) | n0inv(= -1/N mod 2^32) | modulus(256 字节小端) | rr(= 2^4096 mod N，256 字节小端) | exponent。
 * 文本形式 = Base64(524 字节) + " " + 名字。只认 2048 位。
 */
public final class AdbPublicKey {
    public static final int MODULUS_BYTES = 256;
    public static final int ENCODED_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4;

    private AdbPublicKey() {}

    /** 524 字节的二进制结构。 */
    public static byte[] encodeBinary(BigInteger n, BigInteger e) {
        if (n.bitLength() != MODULUS_BYTES * 8) throw new IllegalArgumentException("只支持 2048 位 RSA");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MODULUS_BYTES / 4);
        b.putInt(n0inv.intValue());
        b.put(le(n));
        b.put(le(rr));
        b.putInt(e.intValue());
        return b.array();
    }

    /** "Base64 名字"（不带换行、不带 \0）。名字只是标签，adbd 显示在"已授权的电脑"里。 */
    public static String encode(RSAPublicKey key, String name) {
        return encode(key.getModulus(), key.getPublicExponent(), name);
    }

    public static String encode(BigInteger n, BigInteger e, String name) {
        return Base64.getEncoder().encodeToString(encodeBinary(n, e)) + " " + name;
    }

    /** 配对时 PeerInfo 里放的字节（同 adbkey.pub 的内容，UTF-8）。 */
    public static byte[] encodeBytes(RSAPublicKey key, String name) {
        return encode(key, name).getBytes(StandardCharsets.UTF_8);
    }

    /** 解析 Base64 部分，返回 {n, e}；格式不对抛 IllegalArgumentException。测试和核对用。 */
    public static BigInteger[] decode(String text) {
        String b64 = text.trim().split("\\s+")[0];
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length != ENCODED_SIZE) throw new IllegalArgumentException("长度不是 " + ENCODED_SIZE);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != MODULUS_BYTES / 4) throw new IllegalArgumentException("modulus_size_words 不对");
        b.getInt();   // n0inv，由 n 决定
        byte[] n = new byte[MODULUS_BYTES];
        b.get(n);
        byte[] rr = new byte[MODULUS_BYTES];
        b.get(rr);
        int e = b.getInt();
        return new BigInteger[]{fromLe(n), BigInteger.valueOf(e & 0xffffffffL)};
    }

    static byte[] le(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[MODULUS_BYTES];
        for (int i = 0; i < MODULUS_BYTES && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static BigInteger fromLe(byte[] le) {
        byte[] be = new byte[le.length + 1];
        for (int i = 0; i < le.length; i++) be[le.length - i] = le[i];
        return new BigInteger(be);
    }
}
