package dev.douhua.adb;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;

/**
 * adb 无线配对密码学部分（Ed25519Group、Spake2、Hkdf、PairingAuth）的离线测试。
 * <p>
 * VECTORS / ODD 两组固定向量取自真正的 BoringSSL：编 BoringSSL 的 libcrypto，另编一份没改过的 spake25519.cc、
 * 只把 RAND_bytes 换成由我们给随机数，用它当 Alice / Bob 算出消息、共享密钥、HKDF 出的 AES 密钥和
 * AES-128-GCM 密文（照 AOSP adb pairing_auth 的用法）。输入（配对码之外）全由标签的 SHA-512 派生，测试里同样派生。
 * 互通测试本身（真随机双向几百轮）和导出脚本不进仓库。
 */
public final class AdbCryptoTest {
    private static int failures;
    private static int checks;

    // ===== 以下两组由 BoringSSL 导出，别手改 =====
    // {配对码, 口令哈希规约后 mod 8, Alice 消息, Bob 消息, 共享密钥, AES 密钥, Alice 发的第 0 条密文, Alice 发的第 1 条密文, Bob 发的第 0 条密文（明文同 Alice 第 1 条）}
    static final String[][] VECTORS = {
        {"271828", "1",
         "88a6b9516f2abac58245859ef0a099c82b0cf39338a8dde5cce73a26d76ff9d0",
         "8b073457a4496d000029144641543aa1deff5109a4814fd3ca0c7713a9d4c107",
         "c1f134061829ef4ed34f402f216bb7e46f6ea707d7e43d2a6290fe55f7c3e141b4820de965a2e6db8141cd96f2e3d782203f88c5933061823616854397e10f21",
         "10656320658b941feeeb5dab10f7cd77",
         "1d7537cb0b05e767ec38c1923a3304085629eed0f16617cfb3fddcf3760154680cd4670882b25fb009",
         "02e7cf5f70cdbaf3de1f22731956d388c9c233c75708e48548250ae22a79f596894dced623956b10969e8d0076ad566d57acd8a83612deae65a48a90b17d7423dbb58110048e8786eedf4810531a37ae",
         "5b9c223ce0c40b032d142c13085cc06970e6b8ac5cae75449446fbf874f0951f119f7bc576ad82807845aa2f9cfa2790469fd26e0470ca01ad0688e5351b61894c8f2e5a96a09793d72bf23e08a387ff"},
        {"279747", "4",
         "b261cfe30529408b464f2cca3a5de49a905ef2e922bf351e369c36e52f079720",
         "8d99cada3773afa9980c54a138cde4cd386b2e73c2966bc1255483e9eb31ce18",
         "1cfdaa46053a41538ab24d9d12518d1bfe42df9cd2d0c238354871d32df408c35e3060f1640d168e207f8ca8b10be836add9d04e401153d567e17253c9f44ab7",
         "9594adae38c301f4f57fb16c47cdb0e4",
         "ea79f5cbd0451caecf1817f0dc19a57f2d6de05199961e9ea9d422f5f7a9e04ce039f910ca68ca62d5",
         "dee2882a44250c657cdbf77e1e401678c8e2af181f9de013df77bcb63692df96bab11c7f145d65772e6aaba11e32111de4de61aa62d83c9de495a1fdb7cd3bb7242eff7afb7976f4707497ed4e8a69d5",
         "253b7a7d3e4ddc6da53565cdb44a5f12bb9bccffa7b9873b5183b39c78ca3a11b359ae8ec1906cbae391d5e974c34bba4f7df744728c36f97ddca2ae2dd88d2c91d8f4273126ac65ebabe8d4797a9b56"},
        {"287666", "2",
         "bb3d84222b66253b148ed6820c2626f034d0e3bbdee4b7e94f34d7bfcbe33277",
         "7690b68353099d00d1235b8ef8a9f2a2a8f109c556ffc9650142ae044a127975",
         "2c37958c98e151dde2fd61866b5856e8a2ed9bf48574d44342cbc7eafabf2d9cc334756112be321abf15a3d15c3d46dc39286efcd48ad57393f9560611ea77ef",
         "60ffed1ed8568739625e2937ece22ac8",
         "e35e5e888a3ea115b7c23963faaf7264623a0b44d4998f621787209355ddad06fdaa25db4c76bf4465",
         "970aae42585d469b5c4e8b3bce3cd29ba54552f7f8def46857c5cfacb983f0aebd0ff023a24f5e41a2b696ebf6150570d2800b094c88c6e0ffe6c08e808ea5c19a95b7e0cd794f396afe370039346c4d",
         "0c0b364da2cb9b0ebe0d7679c9c44f0b2417886b64878811b436f30fed3d29b47f5aa057a29c3499352f727fec2f6c014b2df823269cb785a41de1433c026fd0f5fc95e6dde3836e81f78c922b11d329"},
        {"295585", "6",
         "91ba90212e28b7e1cce004a0413ce3b3ca971e923971eb71fe7495def09f55a1",
         "c7eb40d3e082816aa8573217b07de1786ba9345d4ba81767eb24ac72c5e0db0e",
         "62d34ca77759db715d48c9b98cea9bf6f4b1ff3d12e65385b5c73eecabca0820f5691e8a29f5183c5503df092356f22b2773c1c4718cc41569eca15cd2b558c0",
         "84adf46a9d0ae6c1de1bcf6407a99746",
         "34939595e1d928dfa44b1475ada4e769625cd9a47f673ad540728f1b0830b6391ba033bdaa8ddcea0c",
         "a8d1116882cc1fedc151c3dd5c7b38e9bf4b5f4f5018c2b43f1098e60bc8b7f89c3550be0a297f173e9c1302ff0571ed9cc80ce52139769dbf771a028568d668071b2b82fee10c9c0292c632de28e297",
         "c8ab5836853d6c8b06ec1c42f13b639419226a4ddae538bfc6928cbdb3f63231229625e488bf2b0acc78807686d962604c572d8b3a340b92efc6e2b672a3f7367c404d3bd7a71648f68b883a41555adc"},
        {"303504", "5",
         "bb70def5bc7595f0083933d629e8622493d4a4badc0c402cc7bb1056cdceeed5",
         "910d42c50a06268209319c3c467f452eae04c3dbec0b074babfa3ba41ea6dc27",
         "3fe770b44afb2f2f910e8928d672bd26ea57d449619b8ca2e479c1fa7af07cadf09a03065e51f1b316a6adde3d6828c6812125d389adc9d8b4a0fdc6ecd47af9",
         "11bdd9fc06ccc8956e95c4548ef59584",
         "47823c9dbb940ea166c2cc7d066fd0f89dcbc86e874d305a28f95322f15f4a2fab1031161f337aaf75",
         "3c642d8b7ca62f157c55ac30b4b68b899f433312e871e74e1bb42b4d342a507a0eb263d9646a1221c322e7fa4babd25ac98bb167bdd318a95f488cc20ea934cf38a1454eac76d56f7a584283880fdf36",
         "b5ab60358b42a23cb04ce6f436d93cbfe7272275a26e854b3aa49c9d4b2f78d68bdb91709750d64f215748351b1c82ea167c483e05ef839b8ea74a0ef06620214c3fca48e07f37234ab08c220080e9f3"},
        {"319342", "7",
         "abee366081a6c178f78fe51168661d05c6fd6fa7cbc9576d87a5f8e071840556",
         "a3dc1ffcb1a3bfc96fc1cf5935243c256c8c8153f94d981fbac5fd047aeeb3fe",
         "309cccd60378cb609c0dfa8a165f058b4496e719d6a63d0ee6d3f7c27139421e8429448125727291f1b8840c2a35d77f1dda8114e8f47e3abd6c8103895a3941",
         "7713742d89e652fab8359ae49fa6c8bf",
         "cf61632f36d6c23f728232166c89249fd053fa3dd921a11e179a92f8ca660a6f1e8c0ce1961bfaf908",
         "010da440647cbd4f404b924676bb6330f799b6f5243cc65849712b7558f6bb583facc1e3a8db0ff0e2c796fda7c7ef27faccb1629acb1a2e362d176a116e4b0e6f1242e3416353b538d4e5ac5bf0d56f",
         "162ded114a6329ae1f6547846c9c7b0f6c04b244a7bf2852fd274520cd9f3500d7d19f885cdf6b1ed485366b5708f654dc942e45e68ecf4aaf78b19451f0751a63dd561a709cdcad056f5ed14d0c4fb0"},
        {"327261", "3",
         "a38c43d065a7a4224bf292cfad67f047ae962a0be1aa16f7aade171be23ad7a5",
         "d7a59042efb9bd51f9f39ec4df4fde70932aa4c171e477b491d071a09d591714",
         "b01e0d3d680a170094cedef216482897390affa2b49c9a27c26ce9090bf39c3c50583a43958a56c01d3aa067ed53d435be981919e59914fc170fa741e40b5157",
         "70ad069a46c67eb4a7fd4dbd388f6627",
         "161813815804d5019faa4e74f242f6c62e327da3eacfe5b7288ccb078ca01e43778e8b53a2b013c468",
         "108612483103b565cd08c3a37245374f2fff1d6f3b8715e6fcd4a94f05c5ad0deb9ddb739be668da8453c8190114b15fc6dd375b3e812027c9ba7918f80041105b590c8d1a0e636511bf86bcf2fa655b",
         "ab65e4ab2188f0b0b50fabc73cf1732c82cf4844cd315aa051ab3bafdb5b99348010f3027dce3d57bc98a572233d4c642943f10fadade8ff71438b4fe9a2d35b4dcad262d24f5edda0908917a8d6f053"},
        {"335180", "0",
         "6b2c05052587365d3fe2625de1826108a0b115544109f680da0da53273024e3e",
         "a75f83cd574810edd22a5a63d68ab295d9a4ac340c5274d2e61e8aa0bcfaa8b2",
         "374bf2b6c708edb6d8a9dd744f10c8e625640d51c31040e4e1171e961a7ad20c42578b9bcc27b9cc462af31060cd2702e71925c490876efed4c9dbdb68940940",
         "3c2670bd60c9db8e2ff033aa54166b0f",
         "2e781754ea2aeec99a71b97facfecd67222b2c9827f949455168311d6aa40f2cffd0b2a6e3ced7ce6d",
         "f63940990bea48579d25290ee48df57bba25f82a7c9add42c7832965dfc5747aa1ed10c32634bfb7edb5c5bb3080827c3a93eb3dadab3771c53997fef7c43cf923d7c9bd0284d2d857023de9c738c9e3",
         "fc22b72f65c5d1c7ce8560096b85423c7e0fa7544f6ab9b4795ebe7d89dd65a1fc02322a7a9295302c8ab92c17a6ee3560eccf025eb592b944bfd2fc849e16b5f68c9b56c737827051aadc06acd8ef24"},
    };

    // {角色 a/b, 对方消息, BoringSSL 算出的密钥或 FAIL}；口令 = "123456" + 派生的 64 字节，随机数派生自角色
    static final String[][] ODD = {
        {"a", "0100000000000000000000000000000000000000000000000000000000000080",
         "817f05f2f0d59a99267e72fa6622dee2d104840f7b9ae492b04150b539a7224c956d7a7b03755fcb55bc263b54d02b73fe9e176e6b50d6e28738734ff6d52d6b"},
        {"b", "0100000000000000000000000000000000000000000000000000000000000080",
         "dd0f99b934de2ca103b3783a56df07986bc41d963bffb3007ee173a4fffcd137a9c481e27ce80dae338d461a5334ceea3cebd7c5a63aa9a77bdd0727ac297a67"},
        {"a", "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
         "d920bfada82b82bd72c8b0812499bb628b3057ed1c2eb433692573c5914fc8ada7db2f58f25b048cc550382da95aafd872ffa90e58f1b94bbb2c522ae876e0a5"},
        {"b", "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
         "9bd31c3705202d994765bc6619c4faf5826ef604f0f0df63222c2ee4182cb65b14666d85603bafa374b3a497a1425c806c5360608e656563113d806b532ba263"},
        {"a", "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
         "9731419e9ec1ad54113fd7175f1bd62c70139ae7f9964008c0bec46b9506fcc102ec668d2212dd302dd705a4cadfc5bdaca4168ce98b74aa7ac9f2b682bfe07b"},
        {"b", "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
         "0f629f809ec682845a37037f992b132456c3a07ea4dac97aa78b8ae550ef532f8e1450885c8523ae65b4905ca113db2ce2247170a8a9885536f1e3784e1fbd70"},
        {"a", "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
         "2b5431713a544f8bf4d85d085d58f27949e5a92eca779f212879d4d8f3185351aef4491a12de7564d1d63b1c83db038f0d102bbea09083a6e732d3294d7e3cf3"},
        {"b", "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
         "67239f4eee86b8ea84bba3c36ef059be087dc666ace399abb6c3dd63cc60a2d00dfa51ad877b3e6c7c680c69a700d08d10ec14d000203f2c530015dde668ce57"},
        {"a", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
         "672e6c86face94dc612aae3aad3c962eec5c906c6c2f3ce4ba2ef2ef781f32f5107610bd2187e6e81a37a0a5195f673f07f505ceb181508bf5d2a7e29ce4e7c7"},
        {"b", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
         "b0b3d24628ee3127e7246bb0b718824fa9f1e87c112b854a6a3d1f9275bad69e3a58d69957ccc3053d245016fa648b3a306ad14b774a5d3ac5cd951601dd742b"},
        {"a", "0200000000000000000000000000000000000000000000000000000000000000",
         "FAIL"},
        {"b", "0200000000000000000000000000000000000000000000000000000000000000",
         "FAIL"},
        {"a", "0000000000000000000000000000000000000000000000000000000000000080",
         "3007cbb92cc6025996a9068abe03fbd6a36909a8093be58ecbc4e36f50784460954d862f1308ad3b01af229354a9ff5e9b66b81a1bdec8b93a4c818cd1148205"},
        {"b", "0000000000000000000000000000000000000000000000000000000000000080",
         "3b4122939ab8f5b1127498e66a8110c83933e6f4073dc12f434293960d9c175953fbedc7a661a9ca6db9b15613a8c700692157fe960dffd550092b7d4b6137bf"},
        {"a", "436678c52d2a8a9f3c73ddc442fadea1d1e1e9166b2a3c07bcfce65aac73832c",
         "992b138d9c806a5157406718b22ef02df09701a6047c7e87e7c129b08b3b3b3a5390eb1ae66749e92d4132a5d79883fc3e64cb3ab256ad88103753b68213bac1"},
        {"b", "436678c52d2a8a9f3c73ddc442fadea1d1e1e9166b2a3c07bcfce65aac73832c",
         "15d6056556880338437919d0030749fd7629dfae510bb2bfaa6b931d235fba25832f1e5e7a74ef4133dd01395754af3b8b36a7bf36f5d8e3623a08df066134c1"},
    };
    // ===== 导出的向量到此 =====

    public static void main(String[] args) throws Exception {
        group();
        spakeConstants();
        spakeVectors();
        spakeOdd();
        spakeState();
        hkdf();
        gcm();
        pairingAuth();

        System.err.println("adb 配对密码学：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ---------- Ed25519Group ----------

    static void group() throws Exception {
        eq(hex(Ed25519Group.encode(Ed25519Group.BASE)),
                "5866666666666666666666666666666666666666666666666666666666666666", "基点编码");
        check(Ed25519Group.equal(Ed25519Group.scalarMult(Ed25519Group.L, Ed25519Group.BASE), Ed25519Group.IDENTITY),
                "L·B = 单位元");
        check(Ed25519Group.equal(Ed25519Group.scalarMult(Ed25519Group.L.add(BigInteger.ONE), Ed25519Group.BASE),
                Ed25519Group.BASE), "(L+1)·B = B");
        Ed25519Group.Point b2 = Ed25519Group.add(Ed25519Group.BASE, Ed25519Group.BASE);
        Ed25519Group.Point b3 = Ed25519Group.add(b2, Ed25519Group.BASE);
        check(Ed25519Group.equal(Ed25519Group.scalarMult(BigInteger.valueOf(3), Ed25519Group.BASE), b3), "3·B = B+B+B");
        check(Ed25519Group.equal(Ed25519Group.sub(b3, b2), Ed25519Group.BASE), "3B − 2B = B");
        check(Ed25519Group.equal(Ed25519Group.scalarMult(BigInteger.ZERO, b3), Ed25519Group.IDENTITY), "0·P = 单位元");

        // RFC 8032 §7.1 TEST 1 / TEST 2 的公钥：SHA-512(私钥) 前 32 字节按规矩修剪后乘基点（python cryptography 核对过）
        String[][] rfc8032 = {
                {"9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"},
                {"4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                        "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"}};
        for (String[] v : rfc8032) {
            byte[] h = Arrays.copyOf(MessageDigest.getInstance("SHA-512").digest(unhex(v[0])), 32);
            h[0] &= (byte) 248;
            h[31] &= 127;
            h[31] |= 64;
            Ed25519Group.Point a = Ed25519Group.scalarMult(Ed25519Group.fromLE(h), Ed25519Group.BASE);
            eq(hex(Ed25519Group.encode(a)), v[1], "RFC 8032 公钥 " + v[0].substring(0, 8));
            check(Ed25519Group.equal(Ed25519Group.decode(unhex(v[1])), a), "RFC 8032 公钥解码回原点");
        }

        // 编码 / 解码来回
        for (int k = 1; k < 40; k += 7) {
            Ed25519Group.Point p = Ed25519Group.scalarMult(BigInteger.valueOf(k).pow(20), Ed25519Group.BASE);
            byte[] e = Ed25519Group.encode(p);
            check(Ed25519Group.equal(Ed25519Group.decode(e), p), "编码解码来回 k=" + k);
            check(Arrays.equals(Ed25519Group.encode(Ed25519Group.negate(p)), flipSign(e)), "取反只改符号位 k=" + k);
        }

        // BoringSSL 的解码怪处
        BigInteger p = Ed25519Group.P;
        check(Ed25519Group.equal(Ed25519Group.decode(le(p.add(BigInteger.ONE), false)), Ed25519Group.IDENTITY),
                "y = P+1（非规范）照收，等于单位元");
        check(Ed25519Group.equal(Ed25519Group.decode(le(BigInteger.ONE, true)), Ed25519Group.IDENTITY),
                "x = 0 带符号位照收");
        check(Ed25519Group.decode(le(BigInteger.valueOf(2), false)) == null, "y = 2 不在曲线上");
        check(Ed25519Group.decode(new byte[31]) == null, "长度不对返回 null");
        eq(hex(Ed25519Group.encode(Ed25519Group.decode(le(p.add(BigInteger.ONE), false)))),
                hex(le(BigInteger.ONE, false)), "非规范编码解码后再编码是规范的");

        // 标量
        byte[] ff = new byte[64];
        Arrays.fill(ff, (byte) 0xff);
        check(Ed25519Group.scReduce(ff).equals(BigInteger.ONE.shiftLeft(512).subtract(BigInteger.ONE).mod(Ed25519Group.L)),
                "scReduce(2^512−1)");
        check(Ed25519Group.fromLE(unhex("0100000000000080")).equals(BigInteger.ONE.shiftLeft(63).add(BigInteger.ONE)),
                "小端最高位不当符号位");
        eq(hex(Ed25519Group.toLE(BigInteger.valueOf(0x0102), 4)), "02010000", "toLE 补 0");
        boolean threw = false;
        try {
            Ed25519Group.toLE(BigInteger.ONE.shiftLeft(32), 4);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "toLE 超长抛异常");
    }

    // ---------- Spake2 ----------

    /** M、N 按 BoringSSL 注释里的 Python 脚本从种子重新生成：SHA-256(种子)，解不出点就再 SHA-256，直到能解。 */
    static void spakeConstants() throws Exception {
        String[][] seeds = {{Spake2.SEED_M, hex(Spake2.M_ENCODED)}, {Spake2.SEED_N, hex(Spake2.N_ENCODED)}};
        for (String[] s : seeds) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] v = sha.digest(s[0].getBytes(StandardCharsets.US_ASCII));
            Ed25519Group.Point pt;
            while ((pt = Ed25519Group.decode(v)) == null) v = sha.digest(v);
            eq(hex(Ed25519Group.encode(pt)), s[1], "从种子生成 " + s[0]);
        }
        eq(hex(PairingAuth.CLIENT_NAME), hex("adb pair client\0".getBytes(StandardCharsets.US_ASCII)), "客户端名字含 \\0");
        check(PairingAuth.CLIENT_NAME.length == 16 && PairingAuth.SERVER_NAME.length == 16, "名字各 16 字节");
        check(PairingAuth.HKDF_INFO.length == 32, "HKDF info 32 字节、不含 \\0");
    }

    static byte[] h(String label) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(label.getBytes(StandardCharsets.UTF_8));
    }

    /** 配对码 + 64 字节（模拟 TLS 导出的密钥材料）。 */
    static byte[] password(String code, byte[] exported) {
        byte[] c = code.getBytes(StandardCharsets.UTF_8);
        byte[] pw = Arrays.copyOf(c, c.length + exported.length);
        System.arraycopy(exported, 0, pw, c.length, exported.length);
        return pw;
    }

    static byte[] vectorPassword(String code) throws Exception {
        return password(code, h("douhua spake2 vector exported " + code));
    }

    static Spake2 side(boolean alice) {
        return alice ? new Spake2(Spake2.ROLE_ALICE, PairingAuth.CLIENT_NAME, PairingAuth.SERVER_NAME)
                : new Spake2(Spake2.ROLE_BOB, PairingAuth.SERVER_NAME, PairingAuth.CLIENT_NAME);
    }

    static void spakeVectors() throws Exception {
        boolean[] residues = new boolean[8];
        for (String[] v : VECTORS) {
            String code = v[0];
            byte[] pw = vectorPassword(code);
            int res = Ed25519Group.scReduce(MessageDigest.getInstance("SHA-512").digest(pw)).intValue() & 7;
            eq(String.valueOf(res), v[1], "口令标量 mod 8（" + code + "）");
            residues[res] = true;

            Spake2 a = side(true), b = side(false);
            byte[] ma = a.generateMessage(pw, h("douhua spake2 vector alice " + code));
            byte[] mb = b.generateMessage(pw, h("douhua spake2 vector bob " + code));
            eq(hex(ma), v[2], "Alice 消息和 BoringSSL 一致（" + code + "）");
            eq(hex(mb), v[3], "Bob 消息和 BoringSSL 一致（" + code + "）");
            eq(hex(a.processMessage(unhex(v[3]))), v[4], "Alice 密钥和 BoringSSL 一致（" + code + "）");
            eq(hex(b.processMessage(unhex(v[2]))), v[4], "Bob 密钥和 BoringSSL 一致（" + code + "）");
        }
        boolean all = true;
        for (boolean r : residues) all &= r;
        check(all, "向量覆盖口令标量补丁的 8 种低 3 位");
    }

    static void spakeOdd() throws Exception {
        byte[] pw = password("123456", h("douhua spake2 odd exported"));
        for (String[] v : ODD) {
            Spake2 s = side(v[0].equals("a"));
            s.generateMessage(pw, h("douhua spake2 odd " + v[0]));
            String got;
            try {
                got = hex(s.processMessage(unhex(v[1])));
            } catch (GeneralSecurityException e) {
                got = "FAIL";
            }
            eq(got, v[2], "边界消息 " + v[1].substring(0, 8) + "…" + v[1].substring(56) + "（" + v[0] + "）和 BoringSSL 一致");
        }
    }

    static void spakeState() throws Exception {
        // 真随机：两个 Java 端互相配对，口令一样密钥一样，口令不一样密钥不一样
        byte[] pw = password("654321", h("state"));
        Spake2 a = side(true), b = side(false);
        byte[] ma = a.generateMessage(pw), mb = b.generateMessage(pw);
        check(ma.length == 32 && mb.length == 32, "消息 32 字节");
        byte[] ka = a.processMessage(mb), kb = b.processMessage(ma);
        check(ka.length == 64 && Arrays.equals(ka, kb), "真随机：口令一样密钥一样");
        Spake2 c = side(true), d = side(false);
        byte[] mc = c.generateMessage(pw), md = d.generateMessage(password("654320", h("state")));
        check(!Arrays.equals(c.processMessage(md), d.processMessage(mc)), "真随机：配对码差一位密钥不一样");
        Spake2 e = side(true), f = side(true);
        check(!Arrays.equals(e.generateMessage(pw), f.generateMessage(pw)), "每次随机数不一样，消息不一样");

        check(throwsState(() -> a.generateMessage(pw)), "生成过消息再生成抛 IllegalStateException");
        check(throwsState(() -> a.processMessage(mb)), "处理过再处理抛 IllegalStateException");
        check(throwsState(() -> side(true).processMessage(mb)), "没生成消息就处理抛 IllegalStateException");
        Spake2 g = side(true);
        g.generateMessage(pw);
        boolean threw = false;
        try {
            g.processMessage(new byte[31]);
        } catch (GeneralSecurityException ex) {
            threw = true;
        }
        check(threw, "对方消息 31 字节抛 GeneralSecurityException");
    }

    interface Call {
        void run() throws Exception;
    }

    static boolean throwsState(Call c) throws Exception {
        try {
            c.run();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    // ---------- Hkdf ----------

    /** RFC 5869 附录 A 的 SHA-256 三组（A.1–A.3；python cryptography 核对过）。 */
    static void hkdf() throws Exception {
        eq(hex(Hkdf.sha256(rep(0x0b, 22), range(0x00, 0x0d), range(0xf0, 0xfa), 42)),
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", "RFC 5869 A.1");
        eq(hex(Hkdf.sha256(range(0x00, 0x50), range(0x60, 0xb0), range(0xb0, 0x100), 82)),
                "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da32756"
                        + "00c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87", "RFC 5869 A.2");
        String a3 = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8";
        eq(hex(Hkdf.sha256(rep(0x0b, 22), new byte[0], new byte[0], 42)), a3, "RFC 5869 A.3（空 salt、空 info）");
        eq(hex(Hkdf.sha256(rep(0x0b, 22), null, null, 42)), a3, "salt / info 给 null 同空");
        eq(hex(Hkdf.sha256(rep(0x0b, 22), new byte[32], new byte[0], 42)), a3, "空 salt = 32 个 0");
        check(Hkdf.sha256(rep(1, 8), null, null, 0).length == 0, "长度 0");
    }

    // ---------- AES-128-GCM ----------

    static void gcm() throws Exception {
        // McGrew & Viega GCM 规范 Test Case 2：密钥全 0、IV 全 0（= 计数 0 的 nonce）、明文 16 个 0
        eq(hex(PairingAuth.gcm(Cipher.ENCRYPT_MODE, new byte[16], 0, new byte[16])),
                "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf", "GCM Test Case 2（计数 0）");
        // nonce 布局：计数 0x0102030405060708 → 08 07 06 05 04 03 02 01 00 00 00 00（python cryptography 独立算的）
        byte[] key = range(0, 16);
        byte[] plain = "douhua pairing gcm".getBytes(StandardCharsets.US_ASCII);
        String ct = "c254ca38182567a2dc96998a125efea1c306a5b8c6a30c36d93239a8ce2855203a48";
        eq(hex(PairingAuth.gcm(Cipher.ENCRYPT_MODE, key, 0x0102030405060708L, plain)), ct, "GCM nonce = 小端计数 + 4 个 0");
        eq(hex(PairingAuth.gcm(Cipher.DECRYPT_MODE, key, 0x0102030405060708L, unhex(ct))), hex(plain), "GCM 解密");
        boolean bad = false;
        try {
            PairingAuth.gcm(Cipher.DECRYPT_MODE, key, 0x0102030405060709L, unhex(ct));
        } catch (AEADBadTagException e) {
            bad = true;
        }
        check(bad, "计数不对解不开（AEADBadTagException）");
    }

    // ---------- PairingAuth（整条链：SPAKE2 → HKDF → GCM，对 BoringSSL 的密文） ----------

    static void pairingAuth() throws Exception {
        for (String[] v : VECTORS) {
            String code = v[0];
            byte[] pw = vectorPassword(code);
            byte[] p0 = ("douhua plaintext 0 " + code).getBytes(StandardCharsets.UTF_8);
            byte[] p1 = h("douhua plaintext 1 " + code);

            PairingAuth a = new PairingAuth(true, pw, h("douhua spake2 vector alice " + code));
            eq(hex(a.message()), v[2], "PairingAuth 客户端消息（" + code + "）");
            a.initCipher(unhex(v[3]));
            eq(hex(a.encrypt(p0)), v[6], "客户端第 0 条密文和 BoringSSL 一致（" + code + "）");
            eq(hex(a.encrypt(p1)), v[7], "客户端第 1 条密文和 BoringSSL 一致（" + code + "）");
            eq(hex(a.decrypt(unhex(v[8]))), hex(p1), "客户端解开 BoringSSL 服务端的密文（" + code + "）");

            PairingAuth b = new PairingAuth(false, pw, h("douhua spake2 vector bob " + code));
            eq(hex(b.message()), v[3], "PairingAuth 服务端消息（" + code + "）");
            b.initCipher(unhex(v[2]));
            boolean outOfOrder = false;
            try {
                b.decrypt(unhex(v[7]));   // 先收第 1 条：计数对不上
            } catch (AEADBadTagException e) {
                outOfOrder = true;
            }
            check(outOfOrder, "乱序的密文解不开（" + code + "）");
            eq(hex(b.decrypt(unhex(v[6]))), hex(p0), "解失败不推进计数，第 0 条照样解得开（" + code + "）");
            eq(hex(b.decrypt(unhex(v[7]))), hex(p1), "服务端解开第 1 条（" + code + "）");
            eq(hex(b.encrypt(p1)), v[8], "服务端第 0 条密文和 BoringSSL 一致（" + code + "）");
        }

        // 配对码错一位：SPAKE2 照样走完，第一条就解不开
        String code = VECTORS[0][0];
        PairingAuth good = new PairingAuth(true, vectorPassword(code));
        PairingAuth wrong = new PairingAuth(false, password("000000", h("douhua spake2 vector exported " + code)));
        good.initCipher(wrong.message());
        wrong.initCipher(good.message());
        boolean bad = false;
        try {
            wrong.decrypt(good.encrypt(new byte[]{1, 2, 3}));
        } catch (AEADBadTagException e) {
            bad = true;
        }
        check(bad, "配对码错了第一条就 AEADBadTagException");

        PairingAuth once = new PairingAuth(true, vectorPassword(code));
        boolean rejected = false;
        try {
            once.initCipher(unhex("0200000000000000000000000000000000000000000000000000000000000000"));
        } catch (GeneralSecurityException e) {
            rejected = true;
        }
        check(rejected, "对方的点不在曲线上：initCipher 抛 GeneralSecurityException");
        check(throwsState(() -> once.initCipher(unhex(VECTORS[0][3]))), "initCipher 失败后不能再试");
        check(throwsState(() -> once.encrypt(new byte[]{1})), "没有密钥时加密抛 IllegalStateException");
    }

    // ---------- 工具 ----------

    static byte[] flipSign(byte[] e) {
        byte[] c = e.clone();
        c[31] ^= (byte) 0x80;
        return c;
    }

    static byte[] le(BigInteger v, boolean sign) {
        byte[] b = Ed25519Group.toLE(v, 32);
        if (sign) b[31] ^= (byte) 0x80;
        return b;
    }

    static byte[] rep(int b, int n) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) b);
        return out;
    }

    static byte[] range(int from, int to) {
        byte[] out = new byte[to - from];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (from + i);
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        return Spake2.hex(s);
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
