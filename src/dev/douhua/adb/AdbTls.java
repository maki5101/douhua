package dev.douhua.adb;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * adb 的 TLS 1.3：两边都用自签证书、互不校验对方证书（配对时靠 SPAKE2 防中间人，连接时 adbd 只比公钥）。纯 JDK。
 * 只连本机 127.0.0.1 上的 adbd；不校验服务端证书就是协议本身的做法（AOSP adb 客户端同样接受任何证书）。
 */
public final class AdbTls {
    /** RFC 5705 导出密钥材料的标签，含末尾 \0（AOSP 用 sizeof）。 */
    public static final String EXPORTER_LABEL = "adb-label\0";
    public static final int EXPORTED_KEY_SIZE = 64;

    /** 导出密钥材料（Android 上是 android.net.ssl.SSLSockets.exportKeyingMaterial，桌面测试另给）。 */
    public interface Exporter {
        byte[] export(SSLSocket socket, String label, byte[] context, int length) throws java.io.IOException;
    }

    private static final String ALIAS = "adb";

    private AdbTls() {}

    /** 客户端 / 服务端都能用的 SSLContext：拿这把私钥和证书证明自己，对方的证书一律接受。 */
    public static SSLContext context(PrivateKey key, X509Certificate cert) throws GeneralSecurityException {
        SSLContext c = SSLContext.getInstance("TLSv1.3");
        c.init(new KeyManager[]{new KeyManagerImpl(key, cert)}, new TrustManager[]{new TrustAll()}, new SecureRandom());
        return c;
    }

    /** 在已连上的 socket 上开 TLS 客户端，只开 TLS 1.3，握手完返回。 */
    public static SSLSocket clientOver(SSLContext c, Socket plain, String host, int port) throws java.io.IOException {
        SSLSocket s = (SSLSocket) c.getSocketFactory().createSocket(plain, host, port, true);
        s.setUseClientMode(true);
        s.setEnabledProtocols(new String[]{"TLSv1.3"});
        s.startHandshake();
        return s;
    }

    private static final class KeyManagerImpl extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate cert;

        KeyManagerImpl(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (keyTypes == null) return ALIAS;
            for (String t : keyTypes) {
                if ("RSA".equals(t) || "RSASSA-PSS".equals(t)) return ALIAS;
            }
            return null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            return chooseClientAlias(keyTypes, issuers, null);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return "RSA".equals(keyType) || "RSASSA-PSS".equals(keyType) ? ALIAS : null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return chooseServerAlias(keyType, issuers, null);
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[]{cert} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? key : null;
        }
    }

    /** 对方证书一律接受（见类注释）。 */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
