// Copyright 2020 The BoringSSL Authors
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
// Java 移植，豆花儿：移植自 BoringSSL crypto/curve25519/curve25519.cc 里 SPAKE2 用到的群运算
// （x25519_ge_tobytes / x25519_ge_frombytes_vartime / x25519_sc_reduce / 点加 / 点乘）。
// 改动：域运算和点运算改用 java.math.BigInteger 重写（不是逐行翻译 fiat 生成的域运算），输入输出逐字节保持一致。

package dev.douhua.adb;

import java.math.BigInteger;

/**
 * SPAKE2（adb 无线配对）用的 edwards25519 群运算，和 BoringSSL 逐字节一致。纯 JDK。
 * <p>
 * 用 BigInteger 写：一次配对只做几次点乘，不追求速度，也<b>不是常数时间</b>。时序侧信道不在威胁模型里：
 * 本机进程、一次性的临时密钥、对方（adbd）只等一个回合，没有反复测时间的机会。
 * <p>
 * 公式照 RFC 8032 §5.1（a = −1 的扭曲爱德华兹曲线、扩展坐标的完备加法公式——对所有点包括小阶点都成立）。
 * 点乘按整数倍算、不先对 L 取模：SPAKE2 的口令标量故意加过 L 的倍数（见 Spake2），对方给的点也可能带小阶分量，
 * 取模就和 BoringSSL 算的不一样了。
 */
final class Ed25519Group {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    /** 素数阶子群的阶（BoringSSL spake25519.cc 的 kOrder）。 */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D2 = D.add(D).mod(P);
    /** sqrt(−1) = 2^((P−1)/4)。 */
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger SQRT_EXP = P.add(BigInteger.valueOf(3)).shiftRight(3);   // (P+3)/8

    static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    /** 基点：y = 4/5，x 取偶。 */
    static final Point BASE;

    static {
        BigInteger y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger x = recoverX(y, 0);
        BASE = new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    private Ed25519Group() {
    }

    /** 扩展坐标 X:Y:Z:T（x = X/Z，y = Y/Z，xy = T/Z），不可变；各分量都在 [0, P)。 */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    private static BigInteger mul(BigInteger a, BigInteger b) {
        return a.multiply(b).mod(P);
    }

    /** RFC 8032 §5.1.4 的加法（add-2008-hwcd-3），完备：a == b、单位元、小阶点都能直接用。 */
    static Point add(Point a, Point b) {
        BigInteger pa = mul(a.y.subtract(a.x), b.y.subtract(b.x));
        BigInteger pb = mul(a.y.add(a.x), b.y.add(b.x));
        BigInteger pc = mul(mul(a.t, D2), b.t);
        BigInteger pd = mul(a.z.shiftLeft(1), b.z);
        BigInteger e = pb.subtract(pa);
        BigInteger f = pd.subtract(pc);
        BigInteger g = pd.add(pc);
        BigInteger h = pb.add(pa);
        return new Point(mul(e, f), mul(g, h), mul(f, g), mul(e, h));
    }

    static Point negate(Point a) {
        return new Point(P.subtract(a.x).mod(P), a.y, a.z, P.subtract(a.t).mod(P));
    }

    static Point sub(Point a, Point b) {
        return add(a, negate(b));
    }

    /** k·p，k ≥ 0 的整数倍，不先对 L 取模（BoringSSL 的点乘就是整数倍）。从高位到低位倍加。 */
    static Point scalarMult(BigInteger k, Point p) {
        if (k.signum() < 0) throw new IllegalArgumentException("标量不能是负数");
        Point r = IDENTITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    /** 射影相等：X1·Z2 == X2·Z1 且 Y1·Z2 == Y2·Z1。 */
    static boolean equal(Point a, Point b) {
        return mul(a.x, b.z).equals(mul(b.x, a.z)) && mul(a.y, b.z).equals(mul(b.y, a.z));
    }

    /** = x25519_ge_tobytes：y（规约到 [0,P)）小端 32 字节，最高位放 x 的奇偶。 */
    static byte[] encode(Point p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = mul(p.x, zi);
        BigInteger y = mul(p.y, zi);
        byte[] s = toLE(y, 32);
        if (x.testBit(0)) s[31] ^= (byte) 0x80;
        return s;
    }

    /**
     * = x25519_ge_frombytes_vartime：y 取低 255 位后对 P 取模（不拒绝 y ≥ P），求 x，没有平方根返回 null；
     * x 的奇偶按最高位调。BoringSSL 的怪处照抄：x = 0 而最高位是 1 时不拒绝（取反还是 0）。
     * （BoringSSL 求根的写法是 x = u·(uv)^((P−5)/8) 再按需乘 sqrt(−1)；满足 v·x² = u 的根只有 ±x 两个，
     * 奇偶一定下来结果唯一，所以换一种求根写法结果不变。）
     */
    static Point decode(byte[] s32) {
        if (s32 == null || s32.length != 32) return null;
        byte[] b = s32.clone();
        int sign = (b[31] >> 7) & 1;
        b[31] &= 0x7f;
        BigInteger y = fromLE(b).mod(P);
        BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new Point(x, y, BigInteger.ONE, mul(x, y));
    }

    /** 由 y 求 x：x² = (y² − 1) / (d·y² + 1)；没有平方根返回 null。sign 是要的 x 的奇偶（x = 0 时不管）。 */
    private static BigInteger recoverX(BigInteger y, int sign) {
        BigInteger yy = mul(y, y);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = mul(D, yy).add(BigInteger.ONE).mod(P);   // d 不是平方数，v 永远不为 0
        BigInteger xx = mul(u, v.modInverse(P));
        BigInteger x = xx.modPow(SQRT_EXP, P);
        if (!mul(x, x).equals(xx)) {
            x = mul(x, SQRT_M1);
            if (!mul(x, x).equals(xx)) return null;
        }
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x).mod(P);
        return x;
    }

    /** = x25519_sc_reduce：64 字节小端数对 L 取模。 */
    static BigInteger scReduce(byte[] s64) {
        if (s64.length != 64) throw new IllegalArgumentException("要 64 字节");
        return fromLE(s64).mod(L);
    }

    /** 小端无符号数。 */
    static BigInteger fromLE(byte[] b) {
        byte[] be = new byte[b.length + 1];   // 前面多一个 0，保证是正数
        for (int i = 0; i < b.length; i++) be[be.length - 1 - i] = b[i];
        return new BigInteger(be);
    }

    /** 小端 length 字节，不够补 0，超长抛 IllegalArgumentException。 */
    static byte[] toLE(BigInteger v, int length) {
        if (v.signum() < 0 || v.bitLength() > length * 8) {
            throw new IllegalArgumentException("放不进 " + length + " 字节");
        }
        byte[] be = v.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < length && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
