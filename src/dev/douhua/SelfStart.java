package dev.douhua;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.douhua.adb.AdbConnection;
import dev.douhua.adb.PairingClient;

/**
 * 不用电脑、不依赖 Shizuku，豆花自己经无线调试连本机 adbd、用 shell 身份拉起豆花服务。进程里一份。
 *
 * 启动一次（{@link #startService}，单线程 worker 上跑）：
 * <ol>
 * <li>开发者选项、USB 调试必须开着（服务跟着 adbd：两个调试开关都关了 init 会连进程组一起杀，见 CLAUDE.md 决策记录）；</li>
 * <li>无线调试关着：有 WRITE_SECURE_SETTINGS 且连着 Wi-Fi 就自己打开（"是豆花开的"先落盘再打开，见 {@link #KEY_OWN_WIRELESS}），否则报给界面引导用户；</li>
 * <li>mDNS 找 _adb-tls-connect._tcp 端口，用配对过的密钥连 127.0.0.1（{@link AdbConnection}）；</li>
 * <li>没授权过就 pm grant WRITE_SECURE_SETTINGS；停旧服务；和 tools/start-server.sh 一样起服务（{@link ServerCommands}）；</li>
 * <li>等服务端口发 hello、版本对上；</li>
 * <li>无线调试是豆花开的就马上关掉（USB 调试开着 adbd 不停，服务不死）；用户自己开的不动。
 * 打开后被系统关回去（这个 Wi-Fi 没被信任、系统弹了"是否允许"）而放弃的，再盯 3 分钟：用户这时才点"允许"就接着启动、用完照样关
 * （{@link #startWatch}）。</li>
 * </ol>
 * 自动的路径（{@link #autoStart}、开机任务、{@link #onServiceConnected} 的重启）被系统拒过（结果是 Wi-Fi 未信任）之后退避：
 * 不再自己去打开无线调试，直到手动启动成功一次（盯守中用户点了"允许"、接着启动成功也算；规则在 {@link AutoStartBackoff}，
 * 标记 {@link #KEY_AUTO_BACKOFF} 落盘）。
 * 配对（{@link #startPairingSearch} / {@link #submitPairingCode}）：mDNS 找 _adb-tls-pairing._tcp，配对码由 PairingService 的通知收进来，
 * {@link PairingClient} 配对，成功才记"已配对"（state 里的 paired；密钥文件在配对前就生成了，不能拿它在不在当已配对）。
 *
 * 不改 adb_allowed_connection_time（不动全局安全设置）：密钥 7 天不用会被系统作废，作废后 TLS 握手被拒（致命警报，连两次）
 * → keyRejected → 界面提示重新配对。只是被断开（无线调试正好被关、Wi-Fi 漫游）不算。
 * 所有回调在主线程；状态有变化就通知全部 Listener。配对码、私钥不写日志。
 */
final class SelfStart {
    private static final String TAG = "douhua";
    /** 启动过程也记到私有目录的这个文件里（logcat 在三星上几秒就被别的程序刷掉，事后抓不到）；不含密钥、配对码。 */
    private static final String DIAG_FILE = "selfstart-log.txt";
    private static final long DIAG_MAX_BYTES = 64 * 1024;
    /** Settings.Global.ADB_WIFI_ENABLED 是隐藏常量，只能写字面值。 */
    static final String KEY_ADB_WIFI = "adb_wifi_enabled";

    static final int STEP_IDLE = 0;
    /** 在找配对端口（_adb-tls-pairing._tcp），等用户在系统里点"使用配对码配对设备"。 */
    static final int STEP_PAIR_SEARCH = 1;
    /** 拿到配对码，正在配对。 */
    static final int STEP_PAIRING = 2;
    /** 正在连 adbd / 授权 / 停旧服务 / 起服务（含打开、关掉无线调试）。 */
    static final int STEP_STARTING = 3;

    static final int RESULT_NONE = 0;
    static final int RESULT_OK = 1;
    static final int RESULT_NO_WIFI = 2;
    /** 开发者选项或 USB 调试没开（没开 USB 调试时服务起来了也会在关无线调试时被杀，所以不起）。 */
    static final int RESULT_ADB_OFF = 3;
    /** 无线调试关着，又没有 WRITE_SECURE_SETTINGS 自己打开。 */
    static final int RESULT_WIRELESS_OFF = 4;
    /** 打开无线调试后被系统关掉（多半是这个 Wi-Fi 没被信任）。 */
    static final int RESULT_WIFI_UNTRUSTED = 5;
    /** 等不到端口（mDNS 超时）。 */
    static final int RESULT_NOT_FOUND = 6;
    static final int RESULT_NOT_PAIRED = 7;
    /** 密钥被 adbd 拒绝（7 天没用被作废 / 用户撤销了 USB 调试授权）：要重新配对。 */
    static final int RESULT_KEY_REJECTED = 8;
    static final int RESULT_WRONG_CODE = 9;
    /** 连不上端口 / 握手断了（不是被拒）。 */
    static final int RESULT_CONNECT_FAILED = 10;
    /** 密钥存取出错（AndroidKeyStore）。 */
    static final int RESULT_KEYSTORE = 11;
    /** 命令执行了，服务端口等不到监听。 */
    static final int RESULT_SERVICE_FAILED = 12;
    static final int RESULT_ERROR = 13;
    /** 自动的路径在退避中、无线调试又关着：没去打开它，什么都没做。不记成"上次的结果"。 */
    static final int RESULT_PAUSED = 14;

    static final int SERVICE_UNKNOWN = 0;
    static final int SERVICE_NOT_RUNNING = 1;
    /** 在跑、版本和 App 一致（服务回"忙"也算）。 */
    static final int SERVICE_RUNNING = 2;
    static final int SERVICE_OLD_VERSION = 3;
    static final int SERVICE_STUCK = 4;

    interface Listener {
        void onSelfStartChanged();
    }

    interface Callback {
        void onResult(int result);
    }

    interface PairingCallback {
        /** 找到（或换了）配对端口。 */
        void onPairingPort(int port);
    }

    /** 等 mDNS 报出连接端口：打开无线调试后一般 1–3 秒。 */
    private static final long FIND_PORT_MS = 10_000;
    /** 无线调试被系统关回去（网络没被信任，系统弹"是否允许"）后再等用户点"允许"多久。 */
    private static final long UNTRUSTED_GRACE_MS = 30_000;
    private static final int ADB_TIMEOUT_MS = 8_000;
    private static final int PAIR_TIMEOUT_MS = 10_000;
    /** 起服务后等 hello 多久（start-server.sh 等 3 秒监听，这里多给点）。 */
    private static final long WAIT_SERVICE_MS = 6_000;
    /** 放弃后再盯"无线调试被用户允许打开"多久（系统的确认弹窗不会自己消失）。 */
    private static final long WATCH_MS = 3 * 60_000;

    /**
     * state 里："无线调试是豆花打开的"，值是打开（或接手）的时刻（墙上时钟毫秒）。打开前用 commit 落盘：
     * 进程在关掉之前没了（用户划掉、被杀），下次起来照样认得、关掉。过了 {@link #OWN_WIRELESS_MAX_MS} 就不认（免得以后关掉用户自己开的）。
     */
    static final String KEY_OWN_WIRELESS = "own_wireless_at";
    /** 一次启动（含找端口、宽限、命令、等 hello）加上放弃后的盯守，远小于这个。 */
    private static final long OWN_WIRELESS_MAX_MS = 15 * 60_000;
    /** state 里：自动启动在退避中（见 {@link AutoStartBackoff}）。worker 上用 commit 落盘。 */
    static final String KEY_AUTO_BACKOFF = "auto_backoff";

    private static SelfStart instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    /** 私有的一份（不是 "douhua"：设置页"恢复默认"会清空那份）。只存状态，不存密钥。 */
    private final SharedPreferences state;
    private final List<Listener> listeners = new ArrayList<>();

    // 以下只在主线程读写
    private boolean starting;
    private boolean pairing;
    private AdbMdns pairSearch;
    private PairingCallback pairCallback;
    private int lastResult = RESULT_NONE;
    private int serviceState = SERVICE_UNKNOWN;
    private int serviceVersion;
    private final AtomicInteger probeSeq = new AtomicInteger();
    /** 放弃后在盯无线调试的开关（见 startWatch）；null = 没在盯。 */
    private ContentObserver watch;
    private boolean watchOnlyIfNeeded;
    /** 盯守是哪一种启动留下的：接着的那次启动沿用它判断受不受退避限制；成功了一律解除退避（用户在弹框里点了允许）。 */
    private boolean watchAuto;

    private SelfStart(Context context) {
        app = context.getApplicationContext();
        state = app.getSharedPreferences("selfstart", Context.MODE_PRIVATE);
        lastResult = state.getInt("last_result", RESULT_NONE);
        HandlerThread t = new HandlerThread("douhua-selfstart");
        t.start();
        worker = new Handler(t.getLooper());
        worker.post(this::cleanupStaleWireless);   // 排在任何一次启动前面
    }

    static synchronized SelfStart get(Context context) {
        if (instance == null) instance = new SelfStart(context);
        return instance;
    }

    // ═══════════ 状态（主线程读） ═══════════

    int step() {
        if (starting) return STEP_STARTING;
        if (pairing) return STEP_PAIRING;
        if (pairSearch != null) return STEP_PAIR_SEARCH;
        return STEP_IDLE;
    }

    int lastResult() {
        return lastResult;
    }

    /** 配对成功过、密钥文件还在。只看文件不行：配对前就生成并存下了密钥，配对失败文件也留着。 */
    boolean isPaired() {
        return state.getBoolean("paired", false) && AdbKeys.exists(app);
    }

    boolean keyRejected() {
        return state.getBoolean("key_rejected", false);
    }

    int serviceState() {
        return serviceState;
    }

    /** 自动启动在退避中：被系统拒过（Wi-Fi 未信任），手动启动成功前不再自己去打开无线调试。 */
    boolean autoPaused() {
        return state.getBoolean(KEY_AUTO_BACKOFF, false);
    }

    /** 现在开着的无线调试是豆花自己临时打开的（用完会自己关；设置页据此不提醒用户去关）。 */
    boolean wirelessIsOurs() {
        return ownWireless();
    }

    int serviceVersion() {
        return serviceState == SERVICE_RUNNING || serviceState == SERVICE_OLD_VERSION ? serviceVersion : 0;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onSelfStartChanged();
    }

    private void setResult(int r) {
        lastResult = r;
        state.edit().putInt("last_result", r).apply();
    }

    // ═══════════ 服务状态 ═══════════

    /** 后台连一次服务端口读 hello，结果更新 serviceState 并通知 Listener。 */
    void probeService() {
        int seq = probeSeq.incrementAndGet();
        Thread t = new Thread(() -> {
            int[] r = probeNow();
            main.post(() -> {
                if (seq != probeSeq.get() || starting) return;   // 有更新的探测，或者正在启动（启动完自己会更新）
                applyProbe(r);
                changed();
            });
        }, "douhua-probe");
        t.setDaemon(true);
        t.start();
    }

    private void applyProbe(int[] r) {
        serviceState = r[0];
        serviceVersion = r[1];
    }

    /**
     * 连服务端口读 hello：{状态, 版本}。连接被拒 = 没在跑；连接超时（回环上只可能是 accept 队列满）或等不到 hello = 卡住。
     * 注意：服务对每条连接先建虚拟设备再发 hello，探测一次就建一次再拆掉，所以只在需要时探测、不轮询。
     */
    static int[] probeNow() {
        try (Socket s = new Socket()) {
            try {
                s.connect(new InetSocketAddress(Proto.HOST, Proto.PORT), 1000);
            } catch (SocketTimeoutException e) {
                return new int[]{SERVICE_STUCK, 0};
            }
            s.setSoTimeout(Proto.HELLO_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(s.getInputStream());
            try {
                if (in.readUnsignedByte() != Proto.MSG_HELLO) return new int[]{SERVICE_STUCK, 0};
                int version = in.readUnsignedByte();
                return new int[]{version == Proto.VERSION ? SERVICE_RUNNING : SERVICE_OLD_VERSION, version};
            } catch (SocketTimeoutException e) {
                return new int[]{SERVICE_STUCK, 0};
            }
        } catch (ConnectException e) {
            return new int[]{SERVICE_NOT_RUNNING, 0};
        } catch (IOException e) {
            // 连上又马上被断开（比如服务拒绝了 uid）：当没在跑处理，启动时会重启
            return new int[]{SERVICE_NOT_RUNNING, 0};
        }
    }

    // ═══════════ 配对 ═══════════

    /** 开始找配对端口；找到 / 换了就回调。重复调用只换回调。 */
    void startPairingSearch(PairingCallback cb) {
        pairCallback = cb;
        stopWatch(true);   // 用户要去开无线调试配对了：不能再把它当成"豆花开的"关掉
        if (pairSearch == null) {
            pairSearch = new AdbMdns(app, AdbMdns.TLS_PAIRING, port -> {
                if (pairCallback != null) pairCallback.onPairingPort(port);
            });
            pairSearch.start();
            changed();
        }
    }

    void stopPairingSearch() {
        pairCallback = null;
        if (pairSearch != null) {
            pairSearch.stop();
            pairSearch = null;
            changed();
        }
    }

    /** 用配对码配对（code 已去掉空白），结果：OK / WRONG_CODE / CONNECT_FAILED / KEYSTORE / ERROR。成功即存好密钥、清掉 keyRejected。 */
    void submitPairingCode(String code, int port, Callback cb) {
        pairing = true;
        changed();
        worker.post(() -> {
            int r = pairNow(code, port);
            main.post(() -> {
                pairing = false;
                if (r == RESULT_OK) state.edit().putBoolean("key_rejected", false).putBoolean("paired", true).apply();
                changed();
                if (cb != null) cb.onResult(r);
            });
        });
    }

    private int pairNow(String code, int port) {
        AdbKeys keys;
        try {
            keys = AdbKeys.loadOrCreate(app);
        } catch (AdbKeys.KeyStoreException e) {
            log(Log.WARN, "配对：" + e.getMessage());
            return RESULT_KEYSTORE;
        }
        if (keys.created()) state.edit().putBoolean("paired", false).commit();   // 新生成的一定没配对过（旧的作废了也走这里）
        try {
            PairingClient c = new PairingClient(keys.ssl(), AdbKeys.EXPORTER, PAIR_TIMEOUT_MS);
            c.pair("127.0.0.1", port, code, keys.publicKeyBytes());
            log(Log.INFO, "无线调试配对成功");
            return RESULT_OK;
        } catch (PairingClient.WrongCodeException e) {
            log(Log.INFO, "无线调试配对：配对码不对");
            return RESULT_WRONG_CODE;
        } catch (IOException e) {
            log(Log.WARN, "无线调试配对失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return RESULT_CONNECT_FAILED;
        } catch (java.security.GeneralSecurityException | RuntimeException e) {
            log(Log.WARN, "无线调试配对出错：" + e.getClass().getSimpleName());
            return RESULT_ERROR;
        }
    }

    // ═══════════ 启动 ═══════════

    /** 连 adbd → pm grant WRITE_SECURE_SETTINGS → 停旧服务 → 起服务 → 等端口发 hello；服务在跑也重启。cb 可为 null。 */
    void startService(Callback cb) {
        run(false, false, cb);
    }

    /** App 回到前台、连不上服务时调：自动启动开着、服务没在跑 / 卡住 / 版本不一致才（重）启动；在跑就什么都不做。失败只记日志。 */
    void autoStart() {
        autoStart(null);
    }

    /** 同上，带结果（开机任务用）；没做任何事回调 RESULT_NONE，退避中、无线调试关着回调 RESULT_PAUSED。 */
    void autoStart(Callback cb) {
        if (!new Prefs(app).autoStartService() || !isPaired() || keyRejected()) {
            if (cb != null) cb.onResult(RESULT_NONE);
            return;
        }
        if (pausedNow()) {
            // 先在这里挡掉，免得界面闪一下"正在启动"；startNow 里还有一道（这之间无线调试可能被关掉）
            log(Log.INFO, "自动启动服务暂停中（之前被系统拒过，无线调试关着），等手动启动");
            if (cb != null) cb.onResult(RESULT_PAUSED);
            return;
        }
        run(true, true, cb);
    }

    /** 退避中、无线调试又关着：自动的路径这时只能去打开它（会弹框），所以不动。 */
    private boolean pausedNow() {
        return !AutoStartBackoff.mayOpenWireless(true, autoPaused()) && !global(KEY_ADB_WIFI);
    }

    /**
     * App 连上服务时调（MainActivity），serverApk = 服务 hello 里报的、它是从哪个 APK 起的（协议 16 起；空 = 不知道）。
     * 和这次 App 的 APK 路径不一样 = App 之后更新过、服务还跑着旧代码（协议版本没变也一样；每次安装 APK 都换目录），
     * 自动启动开着就重启一次。不管服务是谁起的：电脑装完新包再起的服务（含 start-server.sh --debug）路径一样，不会被动；
     * 装包之前就在跑的旧服务（不论谁起的）会被重启成新代码（不带 debug）。
     */
    void onServiceConnected(String serverApk) {
        serviceState = SERVICE_RUNNING;
        serviceVersion = Proto.VERSION;
        String mine = app.getApplicationInfo().sourceDir;
        if (serverApk == null || serverApk.isEmpty() || serverApk.equals(mine) || starting) return;
        if (!new Prefs(app).autoStartService() || !isPaired() || keyRejected()) return;
        if (pausedNow()) return;   // 退避中：不记"试过"，退避解除后（或无线调试开着时）下次连上再试
        String tried = serverApk + "\n" + mine;
        if (tried.equals(state.getString("restart_tried", ""))) return;   // 对这个旧服务试过一次了，失败也不反复
        state.edit().putString("restart_tried", tried).apply();
        log(Log.INFO, "App 更新过，服务还跑着旧的 APK，自动重启");
        run(false, true, null);
    }

    /** auto = 自动的路径（受退避限制、成功了也不解除退避）；手动的是设置页的按钮和配对成功后那次。 */
    private void run(boolean onlyIfNeeded, boolean auto, Callback cb) {
        run(onlyIfNeeded, auto, false, cb);
    }

    /**
     * allowedByUser = 用户在系统弹框里点了"允许"、盯守接着的这次启动：成功了按手动算、解除退避
     * （用户亲手确认了这个网络；见 {@link AutoStartBackoff#after}）。是否受退避限制仍按 auto（这时无线调试已经开着，本来也不受限）。
     */
    private void run(boolean onlyIfNeeded, boolean auto, boolean allowedByUser, Callback cb) {
        if (starting) {
            if (cb != null) cb.onResult(RESULT_NONE);
            return;
        }
        starting = true;
        stopWatch(false);   // 正在盯"用户允许"的话交给这一次（"豆花开的"标记留着，startNow 照它认）
        changed();
        worker.post(() -> {
            Outcome o = new Outcome();
            int r;
            try {
                r = startNow(onlyIfNeeded, auto, o);
            } catch (RuntimeException e) {
                log(Log.WARN, "启动服务出错：" + e);
                r = RESULT_ERROR;
            }
            // startNow 提前返回（服务已经好了、密钥读不出……）没接手的、但开着又是豆花开的：照样关
            if (!o.openedWireless && ownWireless() && global(KEY_ADB_WIFI)) o.openedWireless = true;
            // 豆花打开的无线调试被系统关回去了（这个 Wi-Fi 没被信任，确认弹窗还在）而放弃：之后用户点"允许"它会重新打开，要接着盯
            boolean watchAfter = o.openedWireless && (r == RESULT_WIFI_UNTRUSTED
                    || ((r == RESULT_CONNECT_FAILED || r == RESULT_NOT_FOUND) && !global(KEY_ADB_WIFI)));
            closeWirelessIfOurs(o, watchAfter);
            boolean backoff = autoPaused();
            boolean backoffAfter = AutoStartBackoff.after(backoff, auto, allowedByUser,
                    r == RESULT_OK, r == RESULT_WIFI_UNTRUSTED);
            if (backoffAfter != backoff) {
                state.edit().putBoolean(KEY_AUTO_BACKOFF, backoffAfter).commit();
                log(Log.INFO, backoffAfter ? "无线调试被系统拒了：自动启动先暂停，等手动启动成功一次"
                        : allowedByUser ? "用户允许了无线调试、启动成功，自动启动恢复" : "手动启动成功，自动启动恢复");
            }
            int[] probe = probeNow();
            int result = r;
            main.post(() -> {
                starting = false;
                if (result != RESULT_NONE && result != RESULT_PAUSED) setResult(result);
                if (result == RESULT_KEY_REJECTED) state.edit().putBoolean("key_rejected", true).apply();
                if (result == RESULT_OK) state.edit().putBoolean("key_rejected", false).apply();
                applyProbe(probe);
                if (watchAfter) startWatch(onlyIfNeeded, auto);
                changed();
                if (cb != null) cb.onResult(result);
            });
        });
    }

    /** 这一次启动里要收尾的东西。 */
    private static final class Outcome {
        /** 无线调试是豆花打开的（这次打开的，或者接手上次没关上的）：结束时关掉。 */
        boolean openedWireless;
    }

    /** worker 线程。onlyIfNeeded：服务好好的就什么都不做（返回 RESULT_NONE）；auto：退避中不自己打开无线调试（返回 RESULT_PAUSED）。 */
    private int startNow(boolean onlyIfNeeded, boolean auto, Outcome o) {
        if (onlyIfNeeded) {
            int[] p = probeNow();
            if (p[0] == SERVICE_RUNNING) return RESULT_NONE;
            log(Log.INFO, "自动启动服务：服务状态 " + p[0] + "（版本 " + p[1] + "）");
        }
        if (!global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) || !global(Settings.Global.ADB_ENABLED)) return RESULT_ADB_OFF;
        if (!isPaired()) return RESULT_NOT_PAIRED;   // 没配对成功过：连上去也只会被拒，别误报成"密钥被作废"
        AdbKeys keys;
        try {
            keys = AdbKeys.load(app);
        } catch (AdbKeys.KeyStoreException e) {
            log(Log.WARN, "启动服务：" + e.getMessage());
            return RESULT_KEYSTORE;
        }
        if (keys == null) return RESULT_NOT_PAIRED;

        boolean wasOn = global(KEY_ADB_WIFI);
        if (wasOn && ownWireless()) {
            // 开着、但是豆花开的：上次进程中途没了没关上，或者放弃等待之后用户才在系统弹窗里点了"允许"。照样用完关掉
            o.openedWireless = true;
            markOwnWireless();
            log(Log.INFO, "无线调试开着，是豆花之前打开的（用完关掉）");
        } else if (!wasOn) {
            if (!AutoStartBackoff.mayOpenWireless(auto, autoPaused())) {
                log(Log.INFO, "自动启动服务暂停中（之前被系统拒过），不去打开无线调试");
                return RESULT_PAUSED;
            }
            if (!canWriteSecure()) return RESULT_WIRELESS_OFF;
            if (!wifiConnected()) return RESULT_NO_WIFI;
            markOwnWireless();   // 先落盘再打开：打开之后进程没了，下次起来也认得
            if (!putWireless(true)) {
                clearOwnWireless();
                return RESULT_WIRELESS_OFF;
            }
            o.openedWireless = true;
            log(Log.INFO, "打开了无线调试（用完关掉）");
        } else if (state.contains(KEY_OWN_WIRELESS)) {
            clearOwnWireless();   // 过期的标记：开着的是用户自己的
        }

        int port = findConnectPort(o.openedWireless);
        if (port < 0) return port == -2 ? RESULT_WIFI_UNTRUSTED : RESULT_NOT_FOUND;

        boolean up;
        for (int attempt = 1; ; attempt++) {
            try {
                up = runCommands(keys, port);
                break;
            } catch (AdbConnection.KeyRejectedException e) {
                if (!global(KEY_ADB_WIFI)) {
                    // 握手中途无线调试被关了（系统认为网络不受信任 / Wi-Fi 变了 / 用户关的）：adbd 踢掉连接，不是密钥的问题
                    log(Log.WARN, "启动服务：连 adbd 时无线调试被关掉了，不算密钥被拒");
                    return RESULT_CONNECT_FAILED;
                }
                if (attempt >= 2) {
                    log(Log.WARN, "启动服务：adbd 连着两次不认豆花的密钥（作废或被撤销），要重新配对");
                    return RESULT_KEY_REJECTED;
                }
                log(Log.INFO, "启动服务：adbd 拒绝了握手，重新找端口再试一次");
                sleep(500);
                port = findConnectPort(false);
                if (port < 0) return RESULT_CONNECT_FAILED;
            } catch (IOException e) {
                log(Log.WARN, "启动服务：连 adbd 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
                return RESULT_CONNECT_FAILED;
            } catch (java.security.GeneralSecurityException e) {
                log(Log.WARN, "启动服务：TLS 准备失败：" + e.getClass().getSimpleName());
                return RESULT_ERROR;
            }
        }

        if (up) {
            log(Log.INFO, "豆花服务已由手机自己拉起");
            return RESULT_OK;
        }
        log(Log.WARN, "命令执行了，但 " + WAIT_SERVICE_MS / 1000 + " 秒内服务没响应（日志在 " + ServerCommands.LOG + "）");
        return RESULT_SERVICE_FAILED;
    }

    /**
     * 连 adbd，执行：没授权过就 pm grant WRITE_SECURE_SETTINGS → 停旧服务 → 起服务，连接一直开到服务应答（或等到点）才断。
     * 起服务的 sh 一退出就断开的话，adbd 拆连接时会把刚起来的服务一起带走（主力机 2026-10-22 实测：断开 17ms 后服务没了，日志是空的）；
     * 启动命令里另加了 setsid，两道保险。返回服务有没有应答。
     */
    private boolean runCommands(AdbKeys keys, int port) throws IOException, java.security.GeneralSecurityException {
        try (AdbConnection c = AdbConnection.open(keys.ssl(), "127.0.0.1", port, ADB_TIMEOUT_MS)) {
            if (!canWriteSecure()) {
                String out = c.shell(ServerCommands.grantSecureSettings(app.getPackageName()), ADB_TIMEOUT_MS).trim();
                if (canWriteSecure()) {
                    log(Log.INFO, "WRITE_SECURE_SETTINGS 已授权");
                } else {
                    // pm 的输出只有包名、权限名和异常类名（比如清单里没申请时的 SecurityException），不含密钥
                    log(Log.WARN, "pm grant WRITE_SECURE_SETTINGS 没生效：" + firstLine(out));
                }
            }
            String stop = c.shell(ServerCommands.stopServer(), ADB_TIMEOUT_MS).trim();
            log(Log.INFO, "停旧服务：" + firstLine(stop));
            if (stop.endsWith("alive")) log(Log.WARN, "旧服务停不掉，照样试着起新的");
            String apk = app.getApplicationInfo().sourceDir;
            String started = c.shell(ServerCommands.startServer(apk, Process.myUid(), false), ADB_TIMEOUT_MS).trim();
            log(Log.INFO, "起服务命令发出去了（" + (c.shellV2() ? "shell 协议 v2" : "老 shell 协议") + "，退出码 " + c.lastExitCode()
                    + "），输出：" + firstLine(started) + "；等服务应答（连接一直开着）");
            long until = SystemClock.uptimeMillis() + WAIT_SERVICE_MS;
            while (SystemClock.uptimeMillis() < until) {
                sleep(300);
                if (probeNow()[0] == SERVICE_RUNNING) return true;
            }
            return false;
        }
    }

    private static String firstLine(String s) {
        if (s.isEmpty()) return "（没有输出）";
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    /**
     * mDNS 等连接端口。返回端口；-1 = 等不到；-2 = 无线调试被系统关回去了、宽限期里也没再打开（网络没被信任）。
     * opened = 无线调试是这次豆花打开的（只有这时才判断"被系统关回去"）。
     */
    private int findConnectPort(boolean opened) {
        final int[] found = {-1};
        final Object lock = new Object();
        AdbMdns[] holder = new AdbMdns[1];
        main.post(() -> {
            holder[0] = new AdbMdns(app, AdbMdns.TLS_CONNECT, port -> {
                synchronized (lock) {
                    found[0] = port;
                    lock.notifyAll();
                }
            });
            holder[0].start();
        });
        try {
            long start = SystemClock.uptimeMillis();
            long deadline = start + FIND_PORT_MS;
            boolean sawOff = false;
            while (true) {
                synchronized (lock) {
                    if (found[0] > 0) return found[0];
                    lock.wait(250);
                    if (found[0] > 0) return found[0];
                }
                long now = SystemClock.uptimeMillis();
                if (opened && !sawOff && !global(KEY_ADB_WIFI) && now - start > 500) {
                    // 系统把它关回去了：这个 Wi-Fi 没被信任，系统弹了"是否允许"。给用户点"允许"的时间
                    sawOff = true;
                    deadline = Math.max(deadline, now + UNTRUSTED_GRACE_MS);
                    log(Log.INFO, "无线调试被系统关回去了（这个 Wi-Fi 多半没被信任），等用户允许");
                }
                if (now >= deadline) return sawOff && !global(KEY_ADB_WIFI) ? -2 : -1;
            }
        } catch (InterruptedException e) {
            return -1;
        } finally {
            main.post(() -> {
                if (holder[0] != null) holder[0].stop();
            });
        }
    }

    /**
     * 无线调试是豆花打开的：关掉（只要 USB 调试开着，adbd 不停，服务不死）。关掉了才清"豆花开的"标记（关不掉留着，下次再关）；
     * keepOwnership = 接着要盯"用户允许"（startWatch），标记留着。
     */
    private void closeWirelessIfOurs(Outcome o, boolean keepOwnership) {
        if (!o.openedWireless) return;
        if (!putWireless(false)) return;
        log(Log.INFO, "关掉了豆花打开的无线调试");
        if (!keepOwnership) clearOwnWireless();
    }

    /** 写 adb_wifi_enabled，成功返回 true（没有 WRITE_SECURE_SETTINGS 时被拒）。 */
    private boolean putWireless(boolean on) {
        try {
            Settings.Global.putInt(app.getContentResolver(), KEY_ADB_WIFI, on ? 1 : 0);
            return true;
        } catch (SecurityException e) {
            log(Log.WARN, (on ? "打开" : "关掉") + "无线调试被拒：" + e.getMessage());
            return false;
        }
    }

    // ═══════════ "无线调试是豆花开的" ═══════════

    private void markOwnWireless() {
        state.edit().putLong(KEY_OWN_WIRELESS, System.currentTimeMillis()).commit();
    }

    private void clearOwnWireless() {
        state.edit().remove(KEY_OWN_WIRELESS).commit();
    }

    /** 有"豆花开的"标记、没过期。 */
    private boolean ownWireless() {
        long at = state.getLong(KEY_OWN_WIRELESS, 0);
        return at != 0 && Math.abs(System.currentTimeMillis() - at) <= OWN_WIRELESS_MAX_MS;
    }

    /** worker 线程，进程刚起来时：上次豆花打开的无线调试没来得及关（进程中途没了）就关掉。 */
    private void cleanupStaleWireless() {
        if (!state.contains(KEY_OWN_WIRELESS)) return;
        if (ownWireless() && global(KEY_ADB_WIFI)) {
            log(Log.INFO, "上次豆花打开的无线调试没关上（进程中途没了），现在关掉");
            if (!putWireless(false)) return;   // 关不掉：标记留着，下次再试
        }
        clearOwnWireless();
    }

    /**
     * 主线程。豆花打开的无线调试被系统关回去（这个 Wi-Fi 没被信任，系统弹了"是否允许在此网络上使用无线调试"）而放弃之后：
     * 弹窗不会自己消失，用户过一阵才点"允许"，系统会把无线调试重新打开。盯 {@link #WATCH_MS}：这期间它一打开就接着启动
     * （"豆花开的"标记一直留着，那次启动照它认、用完关掉）。下面几种情况提前不盯：又开始了一次启动（交给它）、开始配对、
     * 断了 Wi-Fi、到点（后三种同时清掉标记）。进程中途没了：下次起来 cleanupStaleWireless 把开着的关掉。
     */
    private void startWatch(boolean onlyIfNeeded, boolean auto) {
        stopWatch(false);
        watchOnlyIfNeeded = onlyIfNeeded;
        watchAuto = auto;
        watch = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                if (watch != this) return;
                if (!wifiConnected()) {
                    log(Log.INFO, "断了 Wi-Fi，不再等无线调试被允许");
                    stopWatch(true);
                    return;
                }
                if (!global(KEY_ADB_WIFI)) return;
                log(Log.INFO, "用户允许了无线调试，接着启动服务");
                run(watchOnlyIfNeeded, watchAuto, true, null);   // run 里会停掉盯守（标记留着）；成功即解除退避
            }
        };
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY_ADB_WIFI), false, watch);
        main.postDelayed(watchTimeout, WATCH_MS);
        log(Log.INFO, "无线调试被系统关回去了，" + WATCH_MS / 60_000 + " 分钟内用户允许了就接着启动");
    }

    private final Runnable watchTimeout = () -> {
        if (watch == null) return;
        log(Log.INFO, "等无线调试被允许超时了");
        stopWatch(true);
    };

    /** 主线程。clearOwnership：同时清掉"豆花开的"标记（worker 上做，免得和正在跑的启动抢；那时一定没在启动）。 */
    private void stopWatch(boolean clearOwnership) {
        if (watch == null) return;
        app.getContentResolver().unregisterContentObserver(watch);
        watch = null;
        main.removeCallbacks(watchTimeout);
        if (clearOwnership) worker.post(this::clearOwnWireless);
    }

    // ═══════════ 小工具 ═══════════

    private boolean global(String key) {
        ContentResolver cr = app.getContentResolver();
        try {
            return Settings.Global.getInt(cr, key, 0) != 0;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean canWriteSecure() {
        return app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** 连着任何一个 Wi-Fi 网络（不要求能上网，不读网络名）。 */
    @SuppressWarnings("deprecation")   // getAllNetworks：只看传输类型，够用
    boolean wifiConnected() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (SecurityException e) {
            return false;
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 同时写 logcat 和私有目录的 selfstart-log.txt（超过 64KB 先改名 .old）。调试包里用 adb shell run-as dev.douhua cat files/selfstart-log.txt 看。 */
    private void log(int priority, String msg) {
        Log.println(priority, TAG, msg);
        try {
            java.io.File f = new java.io.File(app.getFilesDir(), DIAG_FILE);
            if (f.length() > DIAG_MAX_BYTES) f.renameTo(new java.io.File(app.getFilesDir(), DIAG_FILE + ".old"));
            String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date())
                    + " " + msg + "\n";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
                out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // 诊断文件写不了不影响启动
        }
    }
}
