package dev.douhua;

import android.content.Context;
import android.net.ssl.SSLSockets;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.cert.X509Certificate;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import dev.douhua.adb.AdbPublicKey;
import dev.douhua.adb.AdbTls;
import dev.douhua.adb.SelfSignedCert;

/**
 * 豆花自己的 adb 密钥（配对时交给 adbd、之后连无线调试用）。
 * RSA 2048 私钥用 AndroidKeyStore 里的 AES-256-GCM 密钥（不出安全硬件）加密，存在 App 私有目录 files/adb/key.enc
 * （格式：12 字节 IV + 密文 + 16 字节标签，AAD = "douhua-adbkey"）。不进 SharedPreferences（设置页"恢复默认"会清）。
 * 私钥只在内存里用，日志、界面、异常信息里都不出现；拿到这把私钥 = 能以 shell 身份连这台手机的 adbd。
 */
final class AdbKeys {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_ALIAS = "douhua_adbkey_wrap";
    private static final byte[] AAD = "douhua-adbkey".getBytes(StandardCharsets.US_ASCII);
    /** adbd "已配对的设备"里显示的名字。 */
    static final String KEY_NAME = "douhua";

    /** 加密存取出错（AndroidKeyStore 不可用、密钥被系统清掉、文件坏了）。 */
    static final class KeyStoreException extends Exception {
        KeyStoreException(String what, Throwable cause) {
            super(what + "：" + cause.getClass().getSimpleName(), null);
        }
    }

    private final File file;
    /** 这把是 {@link #loadOrCreate} 刚生成的（一定没配对过）。 */
    private boolean created;
    private PrivateKey privateKey;
    private RSAPublicKey publicKey;
    private SSLContext ssl;

    private AdbKeys(File file) {
        this.file = file;
    }

    static File keyFile(Context context) {
        return new File(new File(context.getFilesDir(), "adb"), "key.enc");
    }

    static boolean exists(Context context) {
        return keyFile(context).isFile();
    }

    /**
     * 读出已存的密钥；没有返回 null。
     * 确定作废的也当没有（删掉文件、返回 null）：AndroidKeyStore 里没有包装密钥（换机迁移过来的、系统清掉了），
     * 或者文件和包装密钥对不上（GCM 校验失败）。Keystore 偶发的读失败（没解锁等）抛 KeyStoreException、不删文件。
     */
    static AdbKeys load(Context context) throws KeyStoreException {
        File f = keyFile(context);
        if (!f.isFile()) return null;
        AdbKeys k = new AdbKeys(f);
        try {
            byte[] stored = readAll(f);
            SecretKey wrap = wrapKey(false);
            if (wrap == null) return discard(f, "Keystore 里没有包装密钥");
            byte[] pkcs8;
            try {
                pkcs8 = decrypt(wrap, stored);
            } catch (AEADBadTagException e) {
                return discard(f, "和包装密钥对不上");
            }
            PrivateKey priv = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            java.util.Arrays.fill(pkcs8, (byte) 0);
            k.setKey(priv);
        } catch (GeneralSecurityException | IOException e) {
            throw new KeyStoreException("读不出 adb 密钥", e);
        }
        return k;
    }

    private static AdbKeys discard(File f, String why) {
        android.util.Log.w("douhua", "存的 adb 密钥作废了（" + why + "），删掉，要重新配对");
        if (!f.delete()) android.util.Log.w("douhua", "删不掉 adb 密钥文件");
        return null;
    }

    /** 读出已存的；没有或读不出就新生成一把存起来（旧的读不出等于作废，要重新配对）。 */
    static AdbKeys loadOrCreate(Context context) throws KeyStoreException {
        try {
            AdbKeys k = load(context);
            if (k != null) return k;
        } catch (KeyStoreException e) {
            android.util.Log.w("douhua", "旧的 adb 密钥读不出（" + e.getMessage() + "），换一把新的");
        }
        File f = keyFile(context);
        AdbKeys k = new AdbKeys(f);
        k.created = true;
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
            KeyPair pair = g.generateKeyPair();
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] enc = encrypt(pkcs8);
            java.util.Arrays.fill(pkcs8, (byte) 0);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不了目录");
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(enc);
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IOException("改不了名");
            k.setKey(pair.getPrivate());
        } catch (GeneralSecurityException | IOException e) {
            throw new KeyStoreException("生成 adb 密钥失败", e);
        }
        return k;
    }

    private void setKey(PrivateKey priv) throws GeneralSecurityException {
        privateKey = priv;
        if (!(priv instanceof RSAPrivateCrtKey)) throw new GeneralSecurityException("不是 RSA CRT 私钥");
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    /** 这把是 loadOrCreate 刚生成的：调用方要把"已配对"清掉。 */
    boolean created() {
        return created;
    }

    /** 配对时 PeerInfo 里发的公钥（adb 格式）。 */
    byte[] publicKeyBytes() {
        return AdbPublicKey.encodeBytes(publicKey, KEY_NAME);
    }

    BigInteger modulus() {
        return publicKey.getModulus();
    }

    /** TLS 1.3，拿这把密钥的自签证书证明自己。 */
    synchronized SSLContext ssl() throws GeneralSecurityException {
        if (ssl == null) {
            X509Certificate cert = SelfSignedCert.create(publicKey, privateKey, System.currentTimeMillis());
            ssl = AdbTls.context(privateKey, cert);
        }
        return ssl;
    }

    /** 平台公开接口（API 31+）导出 TLS 密钥材料，不用隐藏 API。 */
    static final AdbTls.Exporter EXPORTER = (SSLSocket socket, String label, byte[] context, int length) -> {
        if (!SSLSockets.isSupportedSocket(socket)) throw new IOException("系统的 TLS 不支持导出密钥材料");
        return SSLSockets.exportKeyingMaterial(socket, label, context, length);
    };

    /** 删掉存的密钥（重新配对前不需要：配对会把同一把公钥再加一次）。 */
    static void delete(Context context) {
        File f = keyFile(context);
        if (f.isFile() && !f.delete()) android.util.Log.w("douhua", "删不掉 adb 密钥文件");
    }

    // ═══════════ AndroidKeyStore 包一层 ═══════════

    /** create = 没有就生成一把（加密时）；解密时传 false，没有返回 null（新生成的解不开旧文件）。 */
    private static SecretKey wrapKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        java.security.Key k = ks.getKey(WRAP_ALIAS, null);
        if (k instanceof SecretKey) return (SecretKey) k;
        if (!create) return null;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static byte[] encrypt(byte[] plain) throws GeneralSecurityException, IOException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrapKey(true));   // IV 由 Keystore 随机生成
        c.updateAAD(AAD);
        byte[] body = c.doFinal(plain);
        byte[] iv = c.getIV();
        if (iv.length != 12) throw new GeneralSecurityException("IV 长度不对");
        byte[] out = new byte[12 + body.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(body, 0, out, 12, body.length);
        return out;
    }

    private static byte[] decrypt(SecretKey wrap, byte[] stored) throws GeneralSecurityException {
        if (stored.length < 12 + 16) throw new AEADBadTagException("文件太短");   // 截断的文件：同样确定作废
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, wrap, new GCMParameterSpec(128, stored, 0, 12));
        c.updateAAD(AAD);
        return c.doFinal(stored, 12, stored.length - 12);
    }

    private static byte[] readAll(File f) throws IOException {
        long n = f.length();
        if (n <= 0 || n > 16 * 1024) throw new IOException("文件大小不对");
        byte[] b = new byte[(int) n];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new IOException("文件读不全");
                off += r;
            }
        }
        return b;
    }
}
