package dev.douhua;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * 用 NsdManager 找本机 adbd 广播的无线调试端口（系统框架 AdbDebuggingManager 用 mDNS 发出来）。
 * 只认地址是本机网卡地址的那条（同一个局域网里别的手机也会广播），再确认 127.0.0.1 上这个端口真有人在监听
 * （绑不上 = 有人在用；关掉无线调试后旧的 mDNS 记录还会留一会儿）。做法参考 Shizuku 的 AdbMdns.kt（Apache-2.0），没有照抄代码。
 * 回调在主线程。
 */
final class AdbMdns {
    static final String TLS_PAIRING = "_adb-tls-pairing._tcp";
    static final String TLS_CONNECT = "_adb-tls-connect._tcp";
    private static final String TAG = "douhua";

    interface Listener {
        /** 找到（或换了）端口。 */
        void onPort(int port);
    }

    private final NsdManager nsd;
    private final String type;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 老版本的 resolveService 同一时间只能解析一个（多了报 FAILURE_ALREADY_ACTIVE），排队一个个来。 */
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving;
    private boolean running;
    private boolean discovering;
    private int lastPort = -1;

    AdbMdns(Context context, String type, Listener listener) {
        this.nsd = context.getSystemService(NsdManager.class);
        this.type = type;
        this.listener = listener;
    }

    /** 主线程调。 */
    void start() {
        if (running || nsd == null) return;
        running = true;
        lastPort = -1;
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery);
            discovering = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "开始找 " + type + " 失败：" + e);
        }
    }

    /** 主线程调。 */
    void stop() {
        if (!running) return;
        running = false;
        synchronized (pending) {
            pending.clear();
        }
        if (discovering) {
            discovering = false;
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
                // 已经停了
            }
        }
    }

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override
        public void onDiscoveryStarted(String serviceType) {}

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(TAG, "找 " + serviceType + " 失败，错误码 " + errorCode);
            main.post(() -> discovering = false);
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {}

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            synchronized (pending) {
                pending.add(info);
            }
            resolveNext();
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {}
    };

    @SuppressWarnings("deprecation")   // resolveService 在 API 34 起不推荐，但 33 上只有它；行为照旧
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (pending) {
            if (resolving || !running) return;
            next = pending.poll();
            if (next == null) return;
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    done(null);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    done(info);
                }
            });
        } catch (RuntimeException e) {
            done(null);
        }
    }

    private void done(NsdServiceInfo info) {
        synchronized (pending) {
            resolving = false;
        }
        if (info != null && isLocal(info.getHost()) && listening(info.getPort())) {
            int port = info.getPort();
            main.post(() -> {
                if (running && port != lastPort) {
                    lastPort = port;
                    listener.onPort(port);
                }
            });
        }
        resolveNext();
    }

    private static boolean isLocal(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.equals(host)) return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /** 127.0.0.1 上这个端口有没有人在监听（绑不上 = 有）。 */
    static boolean listening(int port) {
        if (port <= 0 || port > 65535) return false;
        try (ServerSocket s = new ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1", port), 1);
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
