// Copyright 2016 The BoringSSL Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Java 移植，豆花儿：移植自 BoringSSL crypto/curve25519/spake25519.cc（SPAKE2_generate_msg / SPAKE2_process_msg）。
// 改动：M、N 不用预计算表，直接从编码解出点再做整数倍点乘（Ed25519Group）；状态不对抛异常而不是返回 0。

package dev.douhua.adb;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SPAKE2 over edwards25519，和 BoringSSL（adbd 配对用的那份）逐字节兼容。纯 JDK。
 * <p>
 * 流程：两边各 {@link #generateMessage} 发 32 字节，再 {@link #processMessage} 处理对方的，得到 64 字节共享密钥；
 * 口令不一样时两边的密钥不一样（这里察觉不到，要靠后面的 AES-GCM 解不开来发现）。
 * <p>
 * BoringSSL 的"口令标量补丁"照做（adb 用的默认值就开着）：口令哈希规约后补成 8 的倍数（加 L、2L、4L），
 * 详见 spake25519.cc 里 "Due to a copy-paste error" 那段注释。
 * <p>
 * 口令、口令哈希、私有标量都不出现在异常信息里。
 */
public final class Spake2 {
    public static final int ROLE_ALICE = 0;   // spake2_role_alice（adb 的客户端）
    public static final int ROLE_BOB = 1;     // spake2_role_bob（adbd）

    /** BoringSSL 源码注释里给的编码（测试里用种子重新算一遍核对）。 */
    static final byte[] N_ENCODED = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");
    static final byte[] M_ENCODED = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    static final String SEED_M = "edwards25519 point generation seed (M)";
    static final String SEED_N = "edwards25519 point generation seed (N)";

    private static final Ed25519Group.Point M = Ed25519Group.decode(M_ENCODED);
    private static final Ed25519Group.Point N = Ed25519Group.decode(N_ENCODED);

    private static final int INIT = 0, MSG_GENERATED = 1, KEY_GENERATED = 2;

    private final int role;
    private final byte[] myName, theirName;
    private int state = INIT;
    private BigInteger privateKey;      // 8 的倍数
    private BigInteger passwordScalar;  // ≡ 口令哈希 mod L，且是 8 的倍数
    private byte[] passwordHash;        // SHA-512(口令)，64 字节
    private byte[] myMsg;

    /** 名字原样拷贝（含调用方给的末尾 \0）。 */
    public Spake2(int role, byte[] myName, byte[] theirName) {
        if (role != ROLE_ALICE && role != ROLE_BOB) throw new IllegalArgumentException("角色只能是 ALICE / BOB");
        this.role = role;
        this.myName = myName.clone();
        this.theirName = theirName.clone();
    }

    /** 生成要发给对方的 32 字节消息。只能调一次（否则 IllegalStateException）。随机数用 SecureRandom 取 64 字节。 */
    public byte[] generateMessage(byte[] password) {
        byte[] random64 = new byte[64];
        new SecureRandom().nextBytes(random64);
        try {
            return generateMessage(password, random64);
        } finally {
            Arrays.fill(random64, (byte) 0);
        }
    }

    /** 同上，随机数由调用方给（64 字节）。只给测试和互通工具用。 */
    byte[] generateMessage(byte[] password, byte[] random64) {
        if (state != INIT) throw new IllegalStateException("SPAKE2 消息已经生成过");
        if (random64.length != 64) throw new IllegalArgumentException("随机数要 64 字节");

        // priv = 8 · (random mod L)：乘上余因子 8，处理对方的点时小阶分量自然消掉
        privateKey = Ed25519Group.scReduce(random64).shiftLeft(3);
        Ed25519Group.Point t = Ed25519Group.scalarMult(privateKey, Ed25519Group.BASE);

        passwordHash = sha512(password);
        BigInteger w = Ed25519Group.scReduce(passwordHash);
        // 口令标量补丁：每一步看上一步更新后的 w；结果 w ≡ 原值 mod L、是 8 的倍数、< 8L
        BigInteger order = Ed25519Group.L;
        if (w.testBit(0)) w = w.add(order);
        order = order.shiftLeft(1);
        if (w.testBit(1)) w = w.add(order);
        order = order.shiftLeft(1);
        if (w.testBit(2)) w = w.add(order);
        passwordScalar = w;

        Ed25519Group.Point mask = Ed25519Group.scalarMult(w, role == ROLE_ALICE ? M : N);
        myMsg = Ed25519Group.encode(Ed25519Group.add(t, mask));
        state = MSG_GENERATED;
        return myMsg.clone();
    }

    /**
     * 处理对方的 32 字节消息，返回 64 字节共享密钥。对方的消息长度不对、点不在曲线上抛 GeneralSecurityException；
     * 还没生成自己的消息或已经处理过一次抛 IllegalStateException。
     */
    public byte[] processMessage(byte[] theirMessage) throws GeneralSecurityException {
        if (state != MSG_GENERATED) throw new IllegalStateException("SPAKE2 状态不对：要先生成消息，且只能处理一次");
        if (theirMessage == null || theirMessage.length != 32) {
            throw new GeneralSecurityException("SPAKE2 对方消息长度不对");
        }
        Ed25519Group.Point qStar = Ed25519Group.decode(theirMessage);
        if (qStar == null) throw new GeneralSecurityException("SPAKE2 对方的点不在曲线上");

        // 去掉对方的掩码：Q = Q* − w·(N 或 M)
        Ed25519Group.Point peersMask = Ed25519Group.scalarMult(passwordScalar, role == ROLE_ALICE ? N : M);
        Ed25519Group.Point q = Ed25519Group.sub(qStar, peersMask);
        byte[] dh = Ed25519Group.encode(Ed25519Group.scalarMult(privateKey, q));

        MessageDigest sha = sha512Digest();
        if (role == ROLE_ALICE) {
            updateWithLengthPrefix(sha, myName);
            updateWithLengthPrefix(sha, theirName);
            updateWithLengthPrefix(sha, myMsg);
            updateWithLengthPrefix(sha, theirMessage);
        } else {
            updateWithLengthPrefix(sha, theirName);
            updateWithLengthPrefix(sha, myName);
            updateWithLengthPrefix(sha, theirMessage);
            updateWithLengthPrefix(sha, myMsg);
        }
        updateWithLengthPrefix(sha, dh);
        updateWithLengthPrefix(sha, passwordHash);
        byte[] key = sha.digest();

        state = KEY_GENERATED;
        Arrays.fill(passwordHash, (byte) 0);
        Arrays.fill(dh, (byte) 0);
        privateKey = null;
        passwordScalar = null;
        return key;
    }

    /** = update_with_length_prefix：8 字节小端长度 + 数据。 */
    private static void updateWithLengthPrefix(MessageDigest sha, byte[] data) {
        long len = data.length;
        byte[] le = new byte[8];
        for (int i = 0; i < 8; i++) {
            le[i] = (byte) len;
            len >>>= 8;
        }
        sha.update(le);
        sha.update(data);
    }

    private static MessageDigest sha512Digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("没有 SHA-512", e);   // JDK / Android 都必带，到不了这里
        }
    }

    private static byte[] sha512(byte[] data) {
        return sha512Digest().digest(data);
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
