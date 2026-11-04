package dev.douhua.server;

import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.douhua.ConfigCodec;
import dev.douhua.GamepadConfig;
import dev.douhua.GestureEngine;
import dev.douhua.Hid;
import dev.douhua.MinHold;
import dev.douhua.Proto;
import dev.douhua.TouchRouter;

/**
 * 一次 App 连接。把这些串起来：App 发来的布局 / 参数 / 心跳 → 何时独占触摸屏；
 * 触摸 → {@link TouchRouter} 分流（触控板 → 手势引擎、键盘键 → 虚拟键盘、按钮 → 报告给 App）；
 * 分流结果 → 虚拟 USB 鼠标 / 键盘（协议 24 起键盘多一个多媒体 / 系统键接口 {@link UhidControls}）、发给 App 的震动、三指四指手势、按钮事件。
 *
 * 独占的条件：App 说 active、参数已到、心跳没超时（{@link Proto#HEARTBEAT_TIMEOUT_MS}）。
 * 任何一条不满足，100ms 内释放。连接断开时一切释放、虚拟鼠标移除。
 *
 * 线程：命令线程（douhua-session，每条连接一个）读 App 的消息；监管线程负责独占和读触摸；
 * 实体键线程（douhua-keys）负责独占和读音量键（{@link #superviseKeys}；电源键从不独占）；
 * 定时器线程跑轻点拖动的等待、惯性滚动的每一帧、修饰键 / Caps / 合并键按住到点（router 的第二个定时器，keyTimer）和切键盘焦点（router 的定时器撤掉 / 被取代后，已经到点、在等锁的那次不调，见 {@link OneShotTimer}）；节拍线程（douhua-tick）在小红点 / 中键滚动杆 / 左键杆按着时按固定间隔推它（{@link #startTicks}）；
 * 交焦点线程（douhua-focus）在分屏里豆花拿着键盘焦点时把焦点交给另一半（{@link Proto#CMD_FOCUS_OTHER}，{@link TaskFocus}，几次 binder，不和定时器挤）。
 * router 只在持有 lock 时访问。
 * 惯性滚动转着时：释放触摸屏（TouchReader 的 finally → router.releaseAll）、会话结束、换参数（setConfig）都走 engine.cancel 停下；
 * 看到任何一根手指按下（含分屏透传归系统的）也停。
 *
 * 实体键的独占条件和触摸屏一样（{@link #wanted}），另外要 App 发过 CMD_HARD_KEYS、至少要截一个键、按键注入可用、
 * 没在来电响铃 / 通话中（{@link CallState}）；
 * 不依赖触摸屏接管成功。不满足 100ms 内放开，注入过的按下全部配上松开（{@link HardKeyGate#releaseAll}）。
 *
 * 会话结束的路：App 断开、10 秒收不到任何消息（{@link Proto#IDLE_TIMEOUT_MS}，对面多半已经不在了）、
 * 被新连接顶掉（{@link #kick}，见 {@link SessionPolicy}）。三条路收尾一样，只有被顶掉的不恢复方向锁定
 * （原值留给下一个会话，见 {@link RotationLock}）。新旧会话会短暂并存：同一时刻只有一个能独占触摸屏
 * （{@link TouchGrabLock}），新会话等旧会话收完尾才建自己的虚拟设备。
 *
 * 分屏 / 小窗（桌面模式，App 报的窗口没占满整块屏）：照样独占整块触摸屏，按每根手指按下的位置分（{@link TouchSplit}）：
 * 豆花窗口里的交给 TouchRouter，窗口外的、从系统手势带按下的注入回系统（{@link Passthrough}）。透传不可用时分屏下不独占
 * （{@link #passBlock}），触摸交还系统、触控板暂停。注入出去的每个 DOWN 在所有退出路径上都配 CANCEL
 * （释放独占：TouchReader 的 onStop；会话结束：{@link Passthrough#close}；服务兜底退出前：{@link Passthrough#cancelBeforeExit}）。
 *
 * 手柄（协议 19 起，桌面模式）：第一次用到才建第三个 uhid 设备（{@link UhidGamepad}：布局里第一次出现手柄页的区域、或第一次收到
 * 不是中立的 CMD_GAMEPAD），留到会话结束（游戏里手柄消失多半弹"手柄已断开"；会话一开就建的话每次串流电脑上都多一个空手柄）。
 * 两路合并（{@link GamepadMux}）：服务自己分流出的（接管时）+ App 本地分流发来的（没接管时，CMD_GAMEPAD）；开始接管时清掉 App 那路。
 * 所有收尾路径回中立：releaseAll（释放独占、会话结束）发全零，关设备前也先写一帧全零。
 */
final class Session implements TouchRouter.Output, TouchReader.Listener {
    /** App 发来的布局，整体替换、不改内部。 */
    static final class Layout {
        static final Layout INACTIVE = new Layout(false, false, -1, 0, 0, 0, TouchSplit.Window.FULL, new TouchRouter.Region[0]);

        final boolean active;
        /** 蓝牙模式：鼠标键盘报文发回 App，不写虚拟设备（见 Proto.CMD_LAYOUT）。 */
        final boolean relay;
        /** width / height 是整块屏（当前方向）的尺寸，不是豆花窗口的。 */
        final int displayId, rotation, width, height;
        /** 豆花窗口在屏上的位置、占没占满、系统手势带宽度（分屏透传用）。 */
        final TouchSplit.Window window;
        final TouchRouter.Region[] regions;

        Layout(boolean active, boolean relay, int displayId, int rotation, int width, int height,
               TouchSplit.Window window, TouchRouter.Region[] regions) {
            this.active = active;
            this.relay = relay;
            this.displayId = displayId;
            this.rotation = rotation;
            this.width = width;
            this.height = height;
            this.window = window;
            this.regions = regions;
        }

        /** 桌面模式下豆花窗口没占满整块屏：要分屏透传。蓝牙模式分屏时 App 不让服务接管（报 inactive），这里不算。 */
        boolean split() {
            return !relay && !window.full;
        }

        /** 屏幕、方向、尺寸都没变（变了就要重新取换算矩阵）。 */
        boolean sameScreen(Layout o) {
            return displayId == o.displayId && rotation == o.rotation && width == o.width && height == o.height;
        }
    }

    /** 全服务一把：同一时刻只有一个会话能独占触摸屏。 */
    private static final TouchGrabLock GRAB = new TouchGrabLock();
    /** 全服务一把：同一时刻只有一个会话能打开 / 独占实体键设备（和触摸屏那把分开，持有范围从打开到关掉）。 */
    private static final TouchGrabLock KEY_GRAB = new TouchGrabLock();
    /** 全服务一份的方向锁定记录：新旧会话交接时不会互相覆盖。 */
    /** 服务兜底退出前、会话结束时，最多等注入线程这么久把取消发出去。 */
    private static final long EXIT_CANCEL_WAIT_MS = 300;
    private static final RotationLock ROTATION = new RotationLock(cmd -> Shell.run(5000, cmd), Server::log);

    private final Socket socket;
    private final int uid;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Injector injector;
    private final boolean debug;
    /** 在 run() 里建；新会话可能替收不了尾的旧会话关掉它们（{@link #closeDevices}）。 */
    private volatile UhidMouse mouse;
    private volatile UhidKeyboard keyboard;
    /**
     * 虚拟键盘的多媒体 / 系统键接口（协议 24，"Douhua Keyboard Controls"）：和键盘同建同关。建失败了只记日志、会话照常
     * （F 行的第二功能在桌面模式下没反应，打字、鼠标不受影响），这时它是 null。
     */
    private volatile UhidControls controls;
    /** 虚拟手柄：第一次用到才建（只在命令线程里建），会话结束才关；建失败了这个会话不再试。 */
    private volatile UhidGamepad gamepad;
    private boolean gamepadFailed;
    /** 虚拟设备已经关了（会话收尾 / 被新会话替着关了）：之后不再建手柄。 */
    private volatile boolean devicesClosed;
    /** 手柄的两路（lock 里用）。 */
    private final GamepadMux pads = new GamepadMux();
    private final LogThrottle gamepadLog = new LogThrottle(60_000);

    private final Object lock = new Object();
    private final TouchRouter router = new TouchRouter(this);

    private volatile Layout layout = Layout.INACTIVE;
    /** 正在用的触摸屏和接管时的布局：触摸坐标按它们换算到当前方向。 */
    private volatile TouchDevice grabDevice;
    private volatile Layout grabLayout = Layout.INACTIVE;
    /** 心跳（独占条件之一）和释放原因：哪些命令算心跳由它定（保活不算）。 */
    private final GrabCondition grabCondition = new GrabCondition();
    /** 最近一次收到任何消息（含保活）的时间：判断这条连接死没死，不进独占条件。 */
    private volatile long lastMessage = SystemClock.uptimeMillis();
    private volatile boolean alive = true;
    private volatile boolean grabbed;
    /** 被新连接顶掉了。 */
    private volatile boolean kicked;
    /** 发出了 HELLO_OK：只有真连上过的会话断开时才恢复方向。 */
    private volatile boolean connected;
    private final CountDownLatch ended = new CountDownLatch(1);

    /**
     * App 要截哪些音量键（{@link Proto#CMD_HARD_KEYS} 的 4 个字节压成位：1 音量+点一下、2 音量+按住、4 音量−点一下、
     * 8 音量−按住）；-1 = 还没收到（这之前不碰任何实体键设备）。
     */
    private volatile int hardMask = -1;
    /** 实体键线程正独占着设备（收尾时卡住、还独占着就得让服务退出，不然音量键一直失灵）。 */
    private volatile boolean keysExclusive;
    /** 补发实体键失败了：注入不可用，马上放开实体键（同设备上没配的音量键补发不出去）。 */
    private volatile boolean injectBroken;
    private final LogThrottle injectLog = new LogThrottle(60_000);
    /** 来电响铃 / 通话中不截实体键；只在实体键线程里用。 */
    private final CallState calls = new CallState();

    /** 分屏透传：哪根手指归系统、把它们注入回去。split 只在监管线程里用。 */
    private final TouchSplit split = new TouchSplit();
    private final Passthrough pass;
    /** 上一次记进日志的"透传不可用、不接管"的原因（同样的不重复记）。 */
    private String lastNoPass;
    /** 正在等系统眼里按着的手指抬起（分屏时不能带着它们独占），只在监管线程里用。 */
    private boolean waitingLift;
    /**
     * 分屏时在 dumpsys 之前就不独占地开着的触摸屏和它上面看到的手指（{@link LiftWatch}），等抬起时一直开着；
     * 独占后由 TouchReader 先读空它。只在监管线程里用。
     */
    private FileDescriptor plain;
    private String plainPath;
    private LiftWatch watch;
    /** 上次找到的触摸屏路径和它对应的屏：开 plain 不用先多跑一次 dumpsys。只在监管线程里用。 */
    private String knownPath;
    private int knownPathDisplay = -1;
    /**
     * 因为"独占前就按着"放开了，App 没收到释放、还以为接管着：再接管时不记日志、不报 GRAB_OK。
     * 报了别的接管状态（{@link #sendGrab} 里清）、不再需要独占时补报释放就清掉。只在监管线程里用。
     */
    private boolean quietRegrab;
    /**
     * TouchReader 正在收尾（onStop 之后）：接着报出来的抬起是收尾补的（还按着的手指按抬起处理），不是真的松手——当取消式抬起，
     * 不起惯性（不然放开触摸屏那一刻手指正在边缘带上划，会白白"放开"震一下，紧接着 releaseAll 又停）。下次接管时清掉。只在监管线程里用。
     */
    private boolean stopping;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "douhua-timer");
        t.setDaemon(true);
        return t;
    });
    /** TouchRouter 的一次性定时器（轻点拖动的等待、长按、惯性的每一帧）：撤掉 / 取代之后已经在等锁的那次回调不调（见 {@link OneShotTimer}）。 */
    private final OneShotTimer routerTimer = new OneShotTimer(timer, lock, () -> router.onTimer(SystemClock.uptimeMillis()));
    /**
     * TouchRouter 按键专用的一次性定时器（修饰键 / Caps / 合并键按住到点，KeyHolds；协议 20）：和引擎那个槽分开，同样撤掉 / 取代之后在等锁的那次不调。
     * onKeyTimer 本身也按截止时刻 + 当前状态处理，过期的回调什么都不多做。
     */
    private final OneShotTimer keyTimer = new OneShotTimer(timer, lock, () -> router.onKeyTimer(SystemClock.uptimeMillis()));
    /**
     * 写 uhid 之前的最后一道：每个键 / 鼠标键至少按住 {@link MinHold#MIN_MS}（2026-11-04；双指右键这类按下松开连着发的，Moonlight 会漏）。
     * 只管桌面模式写虚拟设备那一路；蓝牙模式转回 App 的由 BtHid 自己那份管。调用方持有 lock，到点的回调也在 lock 里（OneShotTimer）。
     */
    private final MinHold hold = new MinHold(new UhidSink());
    private final OneShotTimer holdTimer = new OneShotTimer(timer, lock, () -> hold.onTimer(SystemClock.uptimeMillis()));
    /**
     * 小红点的节拍：单独一个线程，不和定时器线程挤（那边切键盘焦点要等 binder，系统服务卡住时红点会跟着停）。
     * 桌面模式 8ms 一拍，蓝牙模式（转发）12ms（电脑约 11.25ms 收一次报文，发得更密会挤在一起、被对方当成加速）。
     */
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "douhua-tick");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> tickTask;
    static final long TICK_MS = 8, TICK_RELAY_MS = 12;
    /**
     * 交焦点（{@link Proto#CMD_FOCUS_OTHER}）：单独一个线程。一次是 getTasks + setFocusedTask + 再列一次核对，三次 binder；
     * 不放命令线程（卡住时心跳读不进来，触摸屏会被当成 App 没响应释放）、不放定时器线程（惯性、按键到点不被系统服务拖住），
     * 更不放读触摸的线程。排着一次没做时再来的只更新屏幕编号、不再排（{@link #focusQueued}）。线程第一次用到才起。
     */
    private final ExecutorService focusWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "douhua-focus");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean focusQueued = new AtomicBoolean();
    private volatile int focusDisplay;
    /**
     * 交焦点的日志：诊断模式每次都记；平时同一条连接里同一行 60 秒内只记一次。每条连接一份（每次从设置页回来都是新连接，
     * 所以进出一次设置记一行，不跨连接限频——一次进出一行不算刷屏，还看得出每次交没交）。只在 douhua-focus 线程里用。
     */
    private final LogThrottle focusLog = new LogThrottle(60_000);
    private final MouseStats stats;

    Session(Socket socket, int uid, Injector injector, boolean debug) throws IOException {
        this.socket = socket;
        this.uid = uid;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(socket.getOutputStream());
        this.injector = injector;
        this.debug = debug;
        this.stats = debug ? new MouseStats() : null;
        this.pass = new Passthrough(injector);
    }

    /**
     * 在调用线程里跑完整个会话，阻塞到连接断开。old 是还没收完尾的旧会话（被这条连接顶掉的、或者自己正在收尾的；
     * 没有就是 null）：先等它收完尾，再建虚拟设备、发 hello。
     */
    void run(Session old) {
        Thread supervisor = null;
        Thread keys = null;
        try {
            if (old != null) takeOver(old);
            if (!alive) return;   // 等旧会话的时候自己也被顶掉了
            try {
                mouse = new UhidMouse("Douhua Touchpad");
                keyboard = new UhidKeyboard("Douhua Keyboard");
            } catch (IOException e) {
                String what = mouse == null ? "虚拟鼠标" : "虚拟键盘";
                Server.log("创建" + what + "失败：" + e.getMessage());
                Server.hello(out, Proto.HELLO_FAILED, "创建" + what + "失败：" + e.getMessage());
                return;
            }
            try {
                controls = new UhidControls("Douhua Keyboard Controls");
                if (devicesClosed) controls.close();   // 刚建好就被新会话替着关了设备：自己关掉，别漏一个
            } catch (IOException e) {
                Server.log("创建虚拟键盘的多媒体键接口失败（F 行的第二功能没反应，打字照常）：" + e.getMessage());
            }
            String inject = injector.status();
            Server.hello(out, Proto.HELLO_OK, inject == null ? "" : "按键注入不可用：" + inject);
            connected = true;
            Server.log("App 已连接（uid=" + uid + "），虚拟鼠标和键盘已创建");
            socket.setSoTimeout(Proto.IDLE_TIMEOUT_MS);
            supervisor = new Thread(this::supervise, "douhua-touch");
            supervisor.start();
            keys = new Thread(this::superviseKeys, "douhua-keys");
            keys.start();
            commandLoop();
        } catch (SocketTimeoutException e) {
            Server.log("App " + Proto.IDLE_TIMEOUT_MS / 1000 + " 秒没有任何消息（连保活都没有），断开");
        } catch (IOException e) {
            Server.log(kicked ? "被新连接顶掉，收尾" : "连接出错：" + e.getMessage());
        } finally {
            finish(supervisor, keys);
        }
    }

    /**
     * 收尾。关 socket 放在最前面：卡在回调里写 socket 的线程（持着 lock 和 out）也一起叫醒。
     * 监管线程 / 实体键线程 2 秒还没退出、又还占着触摸屏 / 独占着实体键，就让服务退出——只有进程退出能保证内核收回独占，
     * 比外屏一直锁死、音量键一直失灵好。
     */
    private void finish(Thread supervisor, Thread keys) {
        alive = false;
        closeQuietly();
        long deadline = SystemClock.uptimeMillis() + SessionPolicy.OLD_SESSION_WAIT_MS;
        join(supervisor, deadline);
        join(keys, deadline);
        if (supervisor != null && supervisor.isAlive() && GRAB.heldBy(this)) {
            Server.log("监管线程 " + SessionPolicy.OLD_SESSION_WAIT_MS / 1000 + " 秒没退出、还占着触摸屏：服务退出，"
                    + "让系统收回触摸屏和虚拟设备（要重新运行 tools/start-server.sh）");
            pass.cancelBeforeExit(EXIT_CANCEL_WAIT_MS);   // 别在系统里留一根按着的注入手指
            System.exit(3);
        }
        if (keys != null && keys.isAlive() && keysExclusive) {
            Server.log("实体键线程 " + SessionPolicy.OLD_SESSION_WAIT_MS / 1000 + " 秒没退出、还独占着实体键：服务退出，"
                    + "让系统收回音量键（要重新运行 tools/start-server.sh）");
            pass.cancelBeforeExit(EXIT_CANCEL_WAIT_MS);
            System.exit(3);
        }
        synchronized (lock) {
            router.releaseAll();
            hold.flushNow();   // 留着没按够的松开现在发（定时器马上要停、设备马上要关）
            holdTimer.cancel();
        }
        pass.close(EXIT_CANCEL_WAIT_MS);   // 正常情况下释放独占时已经取消过，这里兜底，并等排着的事件发完
        timer.shutdownNow();
        ticker.shutdownNow();   // releaseAll 已经停了节拍，这里兜底
        focusWorker.shutdownNow();   // 正在做的那一次（几次 binder）做完就结束；排着的不做了
        closeDevices();
        if (connected) Server.log("App 已断开，虚拟鼠标和键盘" + (gamepad != null ? "、手柄" : "") + "已移除");
        if (connected && !kicked) ROTATION.restore();
        ended.countDown();
    }

    /**
     * 等旧会话（被顶掉的，或者正在收尾的）收完尾，新旧两组虚拟设备不并存、方向的恢复和锁定不交错。
     * 它 {@link SessionPolicy#OLD_SESSION_WAIT_MS} 还没收完：还占着触摸屏就让服务退出；
     * 没占触摸屏就替它关掉虚拟设备，新会话照常。
     */
    private static void takeOver(Session old) {
        if (old.awaitEnd(SessionPolicy.OLD_SESSION_WAIT_MS)) return;
        if (GRAB.heldBy(old) || old.keysExclusive) {
            Server.log("旧连接 " + SessionPolicy.OLD_SESSION_WAIT_MS / 1000 + " 秒还没收完尾、还占着触摸屏或实体键：服务退出，"
                    + "让系统收回触摸屏、实体键和虚拟设备（要重新运行 tools/start-server.sh）");
            old.pass.cancelBeforeExit(EXIT_CANCEL_WAIT_MS);
            System.exit(3);
        }
        old.closeDevices();
        Server.log("旧连接 " + SessionPolicy.OLD_SESSION_WAIT_MS / 1000 + " 秒还没收完尾（没占触摸屏），已替它移除虚拟鼠标和键盘");
    }

    /** 被新连接顶掉：不阻塞。关 socket 叫醒卡在读命令上的命令线程；alive 为假，监管线程 100ms 内放开触摸屏。 */
    void kick() {
        kicked = true;
        alive = false;
        closeQuietly();
    }

    /** 多久没收到这条连接的任何消息了。 */
    long silentMs() {
        return SystemClock.uptimeMillis() - lastMessage;
    }

    boolean isEnded() {
        return ended.getCount() == 0;
    }

    /** 已经开始收尾（App 断开了、超时了或被顶掉了），只是还没收完。 */
    boolean isClosing() {
        return !alive;
    }

    boolean awaitEnd(long ms) {
        try {
            return ended.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return isEnded();
        }
    }

    /**
     * 关掉虚拟鼠标、键盘（连多媒体 / 系统键那个接口）和手柄（设备随之消失，按着的键由内核补发松开；手柄、多媒体 / 系统键先写全零）。
     * 幂等，别的线程也能调。
     */
    void closeDevices() {
        devicesClosed = true;
        UhidKeyboard k = keyboard;
        UhidControls c = controls;
        UhidMouse m = mouse;
        UhidGamepad g = gamepad;
        if (k != null) k.close();
        if (c != null) c.close();
        if (m != null) m.close();
        if (g != null) g.close();
    }

    /**
     * 第一次用到手柄：建 uhid 手柄（命令线程里，建在锁外，建好再发布）。建好补写一次现在的合并状态（建之前写不进去）。
     * 建失败记日志、告诉 App 一次（MSG_GAMEPAD 带失败位），这个会话不再试。
     */
    private void ensureGamepad() {
        if (gamepad != null || gamepadFailed || devicesClosed || !alive) return;
        UhidGamepad g;
        try {
            g = new UhidGamepad("Douhua Gamepad");
        } catch (IOException e) {
            gamepadFailed = true;
            Server.log("创建虚拟手柄失败：" + e.getMessage());
            byte[] packet = new byte[2 + Hid.GAMEPAD_REPORT_SIZE];
            packet[0] = (byte) Proto.MSG_GAMEPAD;
            packet[1] = (byte) Proto.GAMEPAD_FLAG_FAILED;
            send(packet);
            return;
        }
        synchronized (lock) {
            gamepad = g;
            writeGamepad(pads.deviceCreated());   // 建之前就按着的补写一次（null = 全零，不用写）
        }
        if (devicesClosed) {   // 刚建好就被新会话替着关了设备：自己关掉，别漏一个
            g.close();
            return;
        }
        Server.log("虚拟手柄已创建（第一次进手柄页）");
    }

    /** 布局里有手柄页的区域吗。 */
    private static boolean hasGamepad(TouchRouter.Region[] regions) {
        for (TouchRouter.Region r : regions) if (r.type == Proto.REGION_GAMEPAD) return true;
        return false;
    }

    /** 写 uhid 手柄（调用方持有 lock）；还没建就不写（GamepadMux 记着状态，建好补写）。 */
    private void writeGamepad(byte[] report) {
        UhidGamepad g = gamepad;
        if (g == null || report == null) return;
        try {
            g.send(report);
            String ok = gamepadLog.ok(SystemClock.uptimeMillis());
            if (ok != null) Server.log("写虚拟手柄" + ok);
        } catch (IOException e) {
            String line = gamepadLog.error("写虚拟手柄失败：" + e.getMessage(), SystemClock.uptimeMillis());
            if (line != null) Server.log(line);
        }
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 已经关了
        }
    }

    /** 等线程结束，最多等到 deadline（uptimeMillis）。 */
    private static void join(Thread t, long deadline) {
        if (t == null) return;
        long left = deadline - SystemClock.uptimeMillis();
        if (left <= 0) return;
        try {
            t.join(left);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ═══════════ App 发来的命令 ═══════════

    /** 读命令直到连接断开（返回）或出错 / 读超时（抛异常）。 */
    private void commandLoop() throws IOException {
        while (true) {
            int cmd = in.read();
            if (cmd < 0) return;
            lastMessage = SystemClock.uptimeMillis();
            switch (cmd) {
                case Proto.CMD_PING:
                    // 只说明连接还活着（上面已经记下时间）；不是心跳，不刷新心跳（GrabCondition.onMessage）
                    break;
                case Proto.CMD_KEY: {
                    int displayId = in.readInt();
                    int keyCode = in.readInt();
                    int meta = in.readInt();
                    try {
                        injector.key(displayId, keyCode, meta);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Server.log("注入按键 " + keyCode + " 失败：" + LogThrottle.describe(e));
                    }
                    break;
                }
                case Proto.CMD_CLICK: {
                    int button = in.readUnsignedByte();
                    synchronized (lock) {
                        router.click(button);
                    }
                    break;
                }
                case Proto.CMD_CONFIG:
                    readConfig();
                    break;
                case Proto.CMD_LAYOUT: {
                    Layout l = readLayout();
                    Layout old = layout;
                    if (hasGamepad(l.regions)) ensureGamepad();   // 先建好手柄，再把布局交给分流
                    synchronized (lock) {
                        router.setRegions(l.regions);
                    }
                    // 透传判过不可用的，App 回前台、全屏 ↔ 分屏切换时再给一次机会
                    if ((l.active && !old.active) || l.window.full != old.window.full) pass.retry();
                    if (l.split() && !l.window.sameAs(old.window)) Server.log("分屏：豆花窗口 " + l.window);
                    layout = l;
                    break;
                }
                case Proto.CMD_HEARTBEAT:
                    // 心跳：switch 后面的 grabCondition.onMessage 记下时间（布局也算心跳）
                    break;
                case Proto.CMD_WHEEL: {
                    int n = in.readByte();
                    synchronized (lock) {
                        router.wheel(n);
                    }
                    break;
                }
                case Proto.CMD_ROTATE: {
                    int displayId = in.readInt();
                    int rotation = in.readUnsignedByte();
                    ROTATION.lock(displayId, rotation);
                    break;
                }
                case Proto.CMD_HID_KEY: {
                    // 协议 20：key:i32（含合并键）, action:u8（KEY_UP / KEY_DOWN / KEY_CANCEL）；时间用收到的时刻（同一个时基）
                    int key = in.readInt();
                    int action = in.readUnsignedByte();
                    synchronized (lock) {
                        router.key(key, action, SystemClock.uptimeMillis());
                    }
                    break;
                }
                case Proto.CMD_UNLATCH:
                    synchronized (lock) {
                        router.unlatch(SystemClock.uptimeMillis());   // 锁着的修饰键全部解开，按着的不动
                    }
                    break;
                case Proto.CMD_FOCUS_OTHER:
                    requestFocusOther(in.readInt());   // 协议 22：交给 douhua-focus 线程，命令线程不等 binder
                    break;
                case Proto.CMD_TRACE: {
                    // 键盘触点写 logcat（和 App 本地分流写的同一个标签、同一种格式），不写服务的日志文件：等于按键记录，不落盘
                    boolean keys = (in.readUnsignedByte() & 1) != 0;
                    synchronized (lock) {
                        router.setTrace(keys ? s -> Log.d(TouchRouter.TRACE_TAG, s) : null);
                    }
                    Server.log("键盘触点调试日志" + (keys ? "开（logcat 标签 " + TouchRouter.TRACE_TAG + "）" : "关"));
                    break;
                }
                case Proto.CMD_HARD_KEYS: {
                    int mask = 0;
                    for (int i = 0; i < 4; i++) {
                        if (in.readUnsignedByte() == 1) mask |= 1 << i;
                    }
                    if (mask != hardMask) Server.log("App 要截的实体键：" + describeHard(mask));
                    hardMask = mask;
                    break;
                }
                case Proto.CMD_HOLD_LEFT: {
                    boolean down = in.readUnsignedByte() == 1;
                    synchronized (lock) {
                        router.holdLeft(down);
                    }
                    break;
                }
                case Proto.CMD_GAMEPAD: {
                    // App 本地分流出的手柄状态（服务没接管时）：和服务自己那路合并再写
                    byte[] report = new byte[Hid.GAMEPAD_REPORT_SIZE];
                    in.readFully(report);
                    if (!Hid.Gamepad.neutral(report)) ensureGamepad();
                    synchronized (lock) {
                        writeGamepad(pads.fromApp(report));
                    }
                    break;
                }
                case Proto.CMD_GAMEPAD_CONFIG: {
                    GamepadConfig c = GamepadConfig.read(in);
                    synchronized (lock) {
                        router.setGamepadConfig(c);   // 手柄这一路回中立
                    }
                    break;
                }
                default:
                    throw new IOException("收到未知命令 " + cmd + "，可能 App 和服务版本不一致");
            }
            // 处理完再记：布局先换上、再算心跳（监管线程不会拿新心跳配旧布局）
            grabCondition.onMessage(cmd, SystemClock.uptimeMillis());
        }
    }

    /** 换参数 = 换一个引擎：进行中的手势作废，转着的惯性也停。 */
    private void readConfig() throws IOException {
        GestureEngine.Config c = new GestureEngine.Config();
        float d = ConfigCodec.read(in, c);
        synchronized (lock) {
            router.setConfig(c, d);
        }
    }

    private Layout readLayout() throws IOException {
        boolean active = in.readUnsignedByte() == 1;
        boolean relay = in.readUnsignedByte() == 1;
        int displayId = in.readInt();
        int rotation = in.readUnsignedByte();
        int width = in.readInt();
        int height = in.readInt();
        boolean full = in.readUnsignedByte() == 1;
        int winLeft = in.readInt(), winTop = in.readInt(), winRight = in.readInt(), winBottom = in.readInt();
        int gestLeft = in.readInt(), gestTop = in.readInt(), gestRight = in.readInt(), gestBottom = in.readInt();
        TouchSplit.Window window = full ? TouchSplit.Window.FULL
                : new TouchSplit.Window(false, winLeft, winTop, winRight, winBottom, gestLeft, gestTop, gestRight, gestBottom);
        int count = in.readUnsignedShort();
        TouchRouter.Region[] regions = new TouchRouter.Region[count];
        for (int i = 0; i < count; i++) {
            regions[i] = new TouchRouter.Region(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }
        return new Layout(active, relay, displayId, rotation, width, height, window, regions);
    }

    // ═══════════ 独占触摸屏 ═══════════

    private boolean wanted(Layout l) {
        boolean ready;
        synchronized (lock) {
            ready = router.ready();
        }
        return alive && l.active && ready
                && grabCondition.fresh(SystemClock.uptimeMillis());
    }

    private void supervise() {
        try {
            superviseLoop();
        } finally {
            closePlain();
        }
    }

    private void superviseLoop() {
        while (alive) {
            Layout l = layout;
            if (!wanted(l)) {
                endWait();
                closePlain();
                // "独占前就按着"放开后 App 还以为接管着：补报释放
                if (quietRegrab) sendGrab(false, releaseReason(l), "");
                sleep(100);
                continue;
            }
            String noPass = passBlock(l);
            if (noPass != null) {
                // 分屏时没法把另一边的触摸注入回去：不独占，触摸整块交还系统（触控板暂停），等布局变了再看
                endWait();
                closePlain();
                if (!noPass.equals(lastNoPass)) Server.log("分屏时透传不可用，不接管触摸屏：" + noPass);
                lastNoPass = noPass;
                sendGrab(false, Proto.GRAB_NO_PASSTHROUGH, noPass);
                waitForChange(l);
                continue;
            }
            lastNoPass = null;
            if (!l.split()) {
                endWait();
                closePlain();   // 全屏不用等抬起
            }
            TouchDevice dev;
            try {
                if (l.split()) {
                    // 分屏时不能带着系统那边按着的手指独占（EVIOCGRAB 不补抬起，按在另一边应用上的那根就一直按着）。
                    // 先不独占地开着触摸屏（LiftWatch），再跑 dumpsys：dumpsys 之后到独占之间按下的，独占后读 plain 也看得到
                    if (plain == null) {
                        String path = knownPathDisplay == l.displayId ? knownPath : null;
                        if (path == null) path = remember(l.displayId, TouchDevice.find(l.displayId)).path;
                        plain = Evdev.open(path);
                        plainPath = path;
                        watch = new LiftWatch();
                    }
                    TouchReader.drain(plain, watch);
                    if (watch.anyDown() && !watch.forgetIfQuiet(SystemClock.uptimeMillis())) {
                        // 内核那边看得到按着、还没抬起的手指：不跑 dumpsys，读着 plain 等它抬起
                        // （3 秒没动静就交给下面 dumpsys 那一道，免得漏看了抬起就永远等下去）
                        startWait("内核那边看到有手指按着");
                        LiftWatch w = watch;
                        TouchReader.watchFor(plain, w, LiftWatch.MAX_WAIT_MS, () -> !w.anyDown() || !alive || layout != l);
                        continue;
                    }
                }
                dev = remember(l.displayId, TouchDevice.find(l.displayId));   // 每轮现取：顺带看系统眼里还有没有手指按着
                if (l.split()) {
                    if (!dev.path.equals(plainPath)) {
                        closePlain();   // 设备换了编号：按新路径重开
                        continue;
                    }
                    TouchReader.drain(plain, watch);
                    if (watch.anyDown()) continue;
                    long now = SystemClock.uptimeMillis();
                    long w = watch.onPressed(dev.pressed, now);
                    if (w == LiftWatch.STALE) {
                        Server.log("分屏：系统记着 " + dev.pressed + " 根手指按着，但内核那边 " + watch.waited(now)
                                + "ms 没动静，当作旧状态（多半是之前独占时吞掉了抬起），接管");
                    } else if (w > 0) {
                        // 退避（150ms 起翻倍、封顶 1 秒）：dumpsys input 持着输入系统的锁转储，别拖慢用户正在摸的另一边应用
                        startWait("系统眼里这块触摸屏还有 " + dev.pressed + " 根手指按着");
                        TouchReader.watchFor(plain, watch, w, () -> !alive || layout != l);
                        continue;
                    }
                }
            } catch (TouchDevice.NotFound e) {
                endWait();
                closePlain();
                sendGrab(false, Proto.GRAB_NO_TOUCHSCREEN, String.valueOf(l.displayId));
                waitForChange(l);
                continue;
            } catch (IOException e) {
                endWait();
                closePlain();
                sendGrab(false, Proto.GRAB_FAILED, e.getMessage());
                waitForChange(l);
                continue;
            }
            endWait();
            // 同一时刻只有一个会话能独占：被顶掉的旧会话还没放手时在这里等（每 100ms 再看一眼还要不要独占）。
            // 持有范围从打开触摸屏之前到关掉之后
            if (!GRAB.acquire(this, () -> wanted(layout) && layout.sameScreen(l))) continue;
            grabDevice = dev;
            grabLayout = l;
            boolean quiet = quietRegrab;
            int result = TouchReader.STOPPED;
            try {
                // 切了模式（本机 ↔ 蓝牙）也重新接管：按着的键在原来那一路上松开，不会一半写虚拟设备、一半发回 App。
                // 分屏时读到独占前就按着的手指就马上放开（见 TouchReader.run）
                result = TouchReader.run(dev, plain, watch, this,
                        () -> keepGrab(l),
                        () -> layout.split(),
                        () -> {
                            split.clear();
                            stopping = false;
                            pass.start(l.displayId);
                            grabbed = true;
                            // 接管了：App 收不到触摸了，它那路的手柄状态作废（它的全零可能晚到，合并不怕先后）
                            synchronized (lock) {
                                writeGamepad(pads.clearApp());
                            }
                            if (quiet) return;   // "独占前就按着"放开后又接上：App 那边一直以为接管着，不重复记、不重复报
                            Server.log("已接管触摸屏：" + dev + (l.relay ? "（蓝牙模式，报文发回 App）" : ""));
                            sendGrab(true, Proto.GRAB_OK, "");
                            // 切键盘焦点是一次 binder 调用，挪到定时器线程：已独占、还没进读循环的这段时间不检查独占条件，
                            // 系统服务卡住时不能把监管线程一起卡住（破坏"100ms 内释放"）。
                            // 分屏 / 小窗时不切：焦点跟着用户点哪边走（点窗口外注入的 DOWN 不带 NO_FOCUS_CHANGE），
                            // 重新接管时切回外接屏会把焦点从用户刚点过的另一边应用抢走
                            if (!l.relay && !l.split()) {
                                try {
                                    timer.execute(() -> focusExternal(l.displayId));
                                } catch (RejectedExecutionException ignored) {
                                    // 会话正在收尾
                                }
                            }
                        });
            } catch (IOException e) {
                Server.log("接管触摸屏失败：" + e.getMessage());
                sendGrab(false, Proto.GRAB_FAILED, e.getMessage());
                waitForChange(l);
            } finally {
                synchronized (lock) {
                    router.releaseAll();
                }
                GRAB.release(this);
                // 独占期间 plain 收不到事件，记的状态作废；RACED 时独占描述符一个事件都没读，plain 还准，留着等那根手指抬起
                if (result != TouchReader.RACED) closePlain();
            }
            if (result == TouchReader.RACED) {
                Server.log("分屏：dumpsys 之后、独占之前有根手指按下（系统收到了它的按下），一个独占事件都没读就放开，等它抬起");
                continue;   // 下一轮 plain 上看得到那根手指，报 GRAB_WAIT_LIFT、等它抬起
            }
            if (!grabbed) continue;
            grabbed = false;
            if (result == TouchReader.PRE_GRAB) {
                // 放开让系统收到它的抬起。dumpsys 不一定拦得住它（系统没见过它的开头时不算按着、格式读不出来时不拦），
                // 下一轮可能又接管、又认出它：限频，App 那边不报（一直当接管着），日志只记第一次
                if (!quietRegrab) {
                    Server.log("已释放触摸屏（分屏：有根手指在独占前就按着，放开让系统收到它的抬起；"
                            + "它抬起前反复接管 / 放开不再记，也不报给 App）");
                }
                quietRegrab = true;
                sleep(150);
                continue;
            }
            String noPassNow = passBlock(layout);
            if (noPassNow != null && wanted(layout)) {
                // 透传中途不可用（注入被拒 / 出错 / 卡住）：下一轮循环开头会记日志、报给 App
                Server.log("已释放触摸屏（分屏透传不可用）");
            } else {
                int why = releaseReason(l);
                Server.log("已释放触摸屏（原因 " + why + "）");
                sendGrab(false, why, "");
            }
        }
    }

    /** 开始 / 继续等系统那边的手指抬起：只在刚开始等时记日志、报 GRAB_WAIT_LIFT。没独占，屏幕照常归系统。 */
    private void startWait(String why) {
        if (!waitingLift) {
            Server.log("分屏：" + why + "，等抬起再接管");
            sendGrab(false, Proto.GRAB_WAIT_LIFT, "");
        }
        waitingLift = true;
    }

    private void endWait() {
        waitingLift = false;
        if (watch != null) watch.stopWaiting();
    }

    private TouchDevice remember(int displayId, TouchDevice dev) {
        knownPath = dev.path;
        knownPathDisplay = displayId;
        return dev;
    }

    private void closePlain() {
        if (plain != null) Evdev.close(plain);
        plain = null;
        plainPath = null;
        watch = null;
    }

    /**
     * App 说豆花窗口拿着键盘焦点（桌面模式分屏 / 小窗、没接外接屏）：排到 douhua-focus 线程上交给另一半（小窗时 FocusPick 不交）。
     * 已经排着一次就只换屏幕编号（做的时候用最新的），不重复排。
     */
    private void requestFocusOther(int displayId) {
        focusDisplay = displayId;
        if (!focusQueued.compareAndSet(false, true)) return;
        try {
            focusWorker.execute(() -> {
                focusQueued.set(false);
                handFocus(focusDisplay);
            });
        } catch (RejectedExecutionException e) {
            focusQueued.set(false);   // 会话正在收尾
        }
    }

    /** 在 douhua-focus 线程上：挑任务、setFocusedTask、核对（{@link TaskFocus}），记日志。 */
    private void handFocus(int displayId) {
        if (!alive) return;
        TaskFocus.Result r = TaskFocus.handOff(displayId, true);
        String line = "分屏交焦点（屏幕 " + displayId + "）：" + r.line;
        if (debug) {
            Server.log(line);
            if (r.detail != null) Server.log("这块屏上看得见的任务（和挑中的那个）：\n" + r.detail);   // 看不见的只记个数，见 FocusPick.describe
            return;
        }
        String l = focusLog.error(line, SystemClock.uptimeMillis());
        if (l != null) Server.log(l);
    }

    /**
     * 接管触摸屏时，把键盘输入的目标切到外接屏，不然在手机屏上打开豆花后打的字会送到手机屏、
     * 卡住整个输入队列，见 {@link DexFocus}。没接外接屏就不动。
     */
    private void focusExternal(int appDisplay) {
        for (int id : DisplayWatcher.externals()) {
            if (id == appDisplay) continue;
            if (DexFocus.moveTo(id)) Server.log("键盘输入切到外接屏 " + id);
            return;
        }
    }

    /**
     * 独占着的时候每轮（≤ 100ms）问一次还要不要接着独占：独占条件照旧、屏幕没变、模式没变，
     * 分屏时透传还能用（顺手查注入线程是不是卡住了）。
     */
    private boolean keepGrab(Layout l) {
        Layout now = layout;
        pass.checkStuck(SystemClock.uptimeMillis());
        return wanted(now) && now.sameScreen(l) && now.relay == l.relay && passBlock(now) == null;
    }

    /** 分屏时透传不可用的原因（这时不该独占）；全屏、蓝牙模式、透传能用时为 null。 */
    private String passBlock(Layout l) {
        return l.split() ? pass.unavailable(l.displayId) : null;
    }

    /** Proto.GRAB_*。 */
    private int releaseReason(Layout before) {
        Layout now = layout;
        return grabCondition.releaseReason(alive, now.active, now.sameScreen(before), SystemClock.uptimeMillis());
    }

    /** 出错后别马上重试同样的事：等布局变了、或不再需要独占再说。 */
    private void waitForChange(Layout l) {
        while (alive && layout == l && wanted(l)) sleep(200);
    }

    // ═══════════ 独占实体键（只有音量键；电源键所在的设备只旁观） ═══════════

    /** 实体键的独占条件：和触摸屏同一个 {@link #wanted}（不管触摸屏接管成功没有），再加上 App 至少要截一个键。 */
    private boolean wantedKeys(Layout l, int mask) {
        return mask > 0 && wanted(l);
    }

    /** 实体键线程里用：{@link #wantedKeys} 再加上没在来电响铃 / 通话中（{@link CallState}，音量键要能静音、调通话音量）。 */
    private boolean wantedKeysNow(Layout l, int mask) {
        return wantedKeys(l, mask) && !calls.busy(SystemClock.uptimeMillis());
    }

    /**
     * 实体键线程：满足条件就找设备（{@link KeyDevices}）、告诉 App 哪些截住了 / 截不了（MSG_HARD_GRAB），
     * 再交给 {@link KeyReader}（每 100ms 问一次还要不要截）。按键注入不可用时绝不独占：同设备上没配的音量键补发不出去，
     * 音量会整个失灵；截着时补发失败一次也算注入不可用（{@link Injector#status} 只看拿不拿得到代理，不真注入）。
     * 来电响铃 / 通话中不截。出错后等配置变了、或不再需要截了再重试。
     */
    private void superviseKeys() {
        boolean reported = false;   // 给 App 发过状态：不再截时补一条"已放开"
        while (alive) {
            Layout l = layout;
            int mask = hardMask;
            if (!wantedKeysNow(l, mask)) {
                if (reported) {
                    reported = false;
                    sendHardGrab(0, 0, Proto.HARD_RELEASED, "");
                }
                sleep(100);
                continue;
            }
            int want = hardBits(mask);
            reported = true;
            String inject = injector.status();
            if (inject != null) {
                Server.log("按键注入不可用，不截实体键（不然音量键会失灵）：" + inject);
                sendHardGrab(0, want, Proto.HARD_NO_INJECTOR, inject);
                waitForKeysChange(mask);
                continue;
            }
            KeyDevices.Plan plan;
            try {
                plan = KeyDevices.plan(KeyDevices.read(), new KeyDevices.Config((mask & 3) != 0, (mask & 12) != 0));
            } catch (IOException e) {
                Server.log("读实体键设备列表失败：" + e.getMessage());
                sendHardGrab(0, want, Proto.HARD_FAILED, e.getMessage());
                waitForKeysChange(mask);
                continue;
            }
            sendHardGrab(plan.grabbedBits, plan.unavailableBits, plan.reason, plan.detail);
            if (plan.unavailableBits != 0) Server.log("有配了动作的实体键截不了：" + plan.detail);
            if (plan.grab.isEmpty()) {
                waitForKeysChange(mask);
                continue;
            }
            // 新旧会话交接时旧会话可能还开着设备：等它关掉（每 100ms 再看一眼还要不要截）
            if (!KEY_GRAB.acquire(this, () -> wantedKeys(layout, hardMask) && hardMask == mask)) continue;
            String failure = null;
            boolean injectFailed = false;
            try {
                injectBroken = false;
                HardKeyGate gate = new HardKeyGate(new GateOut());
                gate.config((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
                String names = deviceNames(plan.grab);
                KeyReader.run(plan.grab, plan.watch, gate,
                        () -> !injectBroken && wantedKeysNow(layout, hardMask) && hardMask == mask && layout.relay == l.relay,
                        on -> {
                            keysExclusive = on;
                            Server.log(on ? "已独占实体键：" + names : "实体键已交还系统：" + names);
                        });
                injectFailed = injectBroken;   // 只在本线程（GateOut.inject）里写
            } catch (IOException | RuntimeException e) {
                failure = LogThrottle.describe(e);
            } finally {
                keysExclusive = false;
                KEY_GRAB.release(this);
            }
            if (failure != null) {
                Server.log("截实体键出错：" + failure);
                sendHardGrab(0, want, Proto.HARD_FAILED, failure);
                waitForKeysChange(mask);
            } else if (injectFailed) {
                // 不能马上重来：Injector.status() 照样报可用，新的 gate 又不知道哪个键按着，会立刻再独占
                Server.log("补发实体键失败，不再截实体键，等配置变了或 App 回前台再试");
                sendHardGrab(0, want, Proto.HARD_NO_INJECTOR, "补发失败");
                waitForKeysChange(mask);
            }
        }
    }

    /** 出错后别马上重试：等配置变了、或不再需要截了（App 退到后台、心跳超时）再说。 */
    private void waitForKeysChange(int mask) {
        while (alive && hardMask == mask && wantedKeys(layout, mask)) sleep(200);
    }

    /** CMD_HARD_KEYS 的位 → 配了动作的键（Proto.HARD_BIT_*）。 */
    private static int hardBits(int mask) {
        return ((mask & 3) != 0 ? Proto.HARD_BIT_VOL_UP : 0) | ((mask & 12) != 0 ? Proto.HARD_BIT_VOL_DOWN : 0);
    }

    private static String describeHard(int mask) {
        if (mask == 0) return "无";
        String[] names = {"音量+点一下", "音量+按住", "音量−点一下", "音量−按住"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (b.length() > 0) b.append("、");
            b.append(names[i]);
        }
        return b.toString();
    }

    private static String deviceNames(java.util.List<KeyDevices.Device> ds) {
        StringBuilder b = new StringBuilder();
        for (KeyDevices.Device d : ds) {
            if (b.length() > 0) b.append("，");
            b.append(d.name).append("（").append(d.path).append("）");
        }
        return b.toString();
    }

    /** Linux 键码 → Android 键码（只补发音量键）。 */
    private static int androidKey(int linux) {
        return linux == KeyDevices.KEY_VOLUMEUP ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    /** gate 的输出（实体键线程里调）：补发 → 注入；判断结果 → 发给 App。 */
    private final class GateOut implements HardKeyGate.Output {
        @Override
        public void inject(int linuxCode, boolean down, long downTime, long eventTime) {
            try {
                injector.keyState(androidKey(linuxCode), down, downTime, eventTime);
                String ok = injectLog.ok(SystemClock.uptimeMillis());
                if (ok != null) Server.log("补发实体键" + ok);
            } catch (ReflectiveOperationException | RuntimeException e) {
                injectBroken = true;   // KeyReader 100ms 内放开实体键
                String line = injectLog.error("补发实体键失败，放开实体键：" + LogThrottle.describe(e), SystemClock.uptimeMillis());
                if (line != null) Server.log(line);
            }
        }

        @Override
        public void toApp(int key, int event) {
            send(new byte[]{(byte) Proto.MSG_HARD_KEY, (byte) key, (byte) event});
        }

        @Override
        public void handBack(String why) {
            Server.log(why + "：实体键交还系统，都空闲 " + HardKeyGate.COOLDOWN / 1000 + " 秒后再截");
        }
    }

    // ═══════════ 触摸（监管线程回调） ═══════════

    /**
     * 触摸屏给的是没转过的物理屏坐标（矩阵不含旋转时），按接管时的屏幕方向转成当前方向的坐标，
     * 才能和 App 报来的区域对上。
     */
    private float[] toScreen(float x, float y) {
        TouchDevice dev = grabDevice;
        Layout l = grabLayout;
        if (dev == null || !dev.unrotated()) return new float[]{x, y};
        boolean sideways = (l.rotation & 1) == 1;
        float naturalW = sideways ? l.height : l.width;
        float naturalH = sideways ? l.width : l.height;
        return ScreenRotation.apply(x, y, l.rotation, naturalW, naturalH);
    }

    // 分屏时归系统的手指（split 记着）走 pass，其余照旧给 router。归属按下时定，抬起才忘。

    @Override
    public void onDown(int slot, float rawX, float rawY, long t) {
        float[] p = toScreen(rawX, rawY);
        Layout l = layout;
        TouchSplit.Window w = l.split() ? l.window : TouchSplit.Window.FULL;
        // 手势带里的点落在豆花的键、按钮上就归豆花（轻点交给系统的话会被发回豆花窗口、又被 App 吞掉，按键就丢了）
        boolean own = false;
        if (w.inGestureBand(p[0], p[1])) {
            synchronized (lock) {
                own = router.onTarget(p[0], p[1]);
            }
        }
        int to = split.down(slot, p[0], p[1], w, own);
        if (to != TouchSplit.ROUTER) {
            // 碰屏幕任何地方惯性都马上停，按在另一边应用上的手指也算（这根照样注入给系统）
            synchronized (lock) {
                router.stopSpin(true);
            }
            pass.down(slot, p[0], p[1], t, to == TouchSplit.SYSTEM_IN_WINDOW);
            if (debug) {
                Server.log(String.format(Locale.ROOT, "按下 #%d (%.0f,%.0f) 方向 %d → 系统（分屏透传%s）", slot, p[0], p[1],
                        grabLayout.rotation, to == TouchSplit.SYSTEM_IN_WINDOW ? "，手势带，不挪焦点" : ""));
            }
            return;
        }
        TouchRouter.Region r;
        synchronized (lock) {
            r = router.down(slot, p[0], p[1], t);
        }
        if (debug) {
            Server.log(String.format(Locale.ROOT, "按下 #%d (%.0f,%.0f) 方向 %d → %s", slot, p[0], p[1], grabLayout.rotation,
                    r == null ? "空白处" : r.contains(p[0], p[1]) ? r.toString() : r + "（键盘缝里，分给最近的键）"));
        }
    }

    @Override
    public void onMove(int slot, float rawX, float rawY) {
        float[] p = toScreen(rawX, rawY);
        if (split.isSystem(slot)) {
            pass.move(slot, p[0], p[1]);
            return;
        }
        synchronized (lock) {
            router.move(slot, p[0], p[1]);
        }
    }

    @Override
    public void onFrame(long t) {
        pass.frame(t);
        synchronized (lock) {
            router.frame(t);
        }
    }

    @Override
    public void onUp(int slot, long t) {
        if (split.up(slot)) {
            pass.up(slot, t);
            return;
        }
        synchronized (lock) {
            router.up(slot, t, stopping);
        }
    }

    @Override
    public void onCancel(int slot, long t) {
        if (split.up(slot)) {
            // 系统那边整条流取消（带 FLAG_CANCELED）；别的还按着的系统侧手指作废到抬起
            pass.cancelAll(t);
            return;
        }
        synchronized (lock) {
            router.up(slot, t, true);   // TouchRouter 那一路照旧当抬起，只是不起惯性（手掌、丢数据不是"拨"）
        }
    }

    @Override
    public void onStop(long t) {
        stopping = true;
        pass.cancelAll(t);
    }

    // ═══════════ 分流结果（调用方持有 lock） ═══════════
    // 去哪一路按最近一次接管时的布局：桌面模式写虚拟设备，蓝牙模式发回 App。接管结束后的松开（releaseAll）也走同一路。

    @Override
    public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (stats != null) stats.add(dx, dy);
        if (grabLayout.relay) {
            // 滚轮是 1/120 格，App 按蓝牙主机设的倍率换算成计数（协议 18 起 i16）
            short x = clamp16(dx), y = clamp16(dy), w = clamp16(wheel), h = clamp16(pan);
            send(new byte[]{(byte) Proto.MSG_MOUSE, (byte) buttons, (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y,
                    (byte) (w >> 8), (byte) w, (byte) (h >> 8), (byte) h});
            return;
        }
        hold.mouse(buttons, dx, dy, wheel, pan, SystemClock.uptimeMillis());
    }

    private static short clamp16(int v) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, v));
    }

    /** 调试：边缘滚动松手的速度，校准"多快算拨"用（服务 --debug 时记）。 */
    @Override
    public void edgeRelease(float speedDpPerMs, float spinPerSecond) {
        if (!debug) return;
        Server.log(String.format(Locale.ROOT, "边缘松手 %.2fdp/ms → %s", speedDpPerMs,
                spinPerSecond > 0 ? String.format(Locale.ROOT, "惯性 %.0f 格/秒", spinPerSecond) : "没起惯性"));
    }

    @Override
    public void keyboard(byte[] report) {
        if (grabLayout.relay) {
            byte[] packet = new byte[1 + report.length];
            packet[0] = (byte) Proto.MSG_KEYBOARD;
            System.arraycopy(report, 0, packet, 1, report.length);
            send(packet);
            return;
        }
        hold.keyboard(report, SystemClock.uptimeMillis());
    }

    /**
     * 多媒体页现在按着的码（协议 24，F 行的第二功能；调用方持有 lock）：桌面模式写 "Douhua Keyboard Controls" 的报告 3，
     * 蓝牙模式转回 App（MSG_CONSUMER）由蓝牙发。去哪一路同键盘：按最近一次接管时的布局，收尾的 0 也走同一路。
     */
    @Override
    public void consumer(int usage) {
        controlsOut(Proto.PAGE_CONSUMER, usage);
    }

    /** 系统键那一组现在按着的码（睡眠、勿扰、麦克风静音），同 {@link #consumer}（报告 6）。 */
    @Override
    public void system(int usage) {
        controlsOut(Proto.PAGE_GENERIC_DESKTOP, usage);
    }

    private void controlsOut(int page, int usage) {
        if (grabLayout.relay) {
            send(new byte[]{(byte) Proto.MSG_CONSUMER, (byte) page, (byte) (usage >> 8), (byte) usage});
            return;
        }
        if (page == Proto.PAGE_CONSUMER) hold.consumer(usage, SystemClock.uptimeMillis());
        else hold.system(usage, SystemClock.uptimeMillis());
    }

    /** {@link #hold} 放行的报文写进虚拟设备（调用方持有 lock）。 */
    private final class UhidSink implements MinHold.Sink {
        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            try {
                Session.this.mouse.send(buttons, dx, dy, wheel, pan);
            } catch (IOException e) {
                Server.log("写虚拟鼠标失败：" + e.getMessage());
            }
        }

        @Override
        public void keyboard(byte[] report) {
            try {
                Session.this.keyboard.send(report);
            } catch (IOException e) {
                Server.log("写虚拟键盘失败：" + e.getMessage());
            }
        }

        @Override
        public void consumer(int usage) {
            controls(Proto.PAGE_CONSUMER, usage);
        }

        @Override
        public void system(int usage) {
            controls(Proto.PAGE_GENERIC_DESKTOP, usage);
        }

        private void controls(int page, int usage) {
            UhidControls c = controls;
            if (c == null) return;   // 没建成（会话开头记过日志）
            try {
                if (page == Proto.PAGE_CONSUMER) c.sendConsumer(usage);
                else c.sendSystem(usage);
            } catch (IOException e) {
                Server.log("写虚拟键盘的多媒体键接口失败：" + e.getMessage());
            }
        }

        @Override
        public void schedule(long delayMs) {
            holdTimer.schedule(delayMs);
        }
    }

    /**
     * 服务分流出的手柄状态（调用方持有 lock）：和 App 那路合并写 uhid，原样发给 App 画（MSG_GAMEPAD）。
     * 蓝牙模式（转发）第一批不出手柄：App 在蓝牙模式下进不了手柄页，这里收到的只可能是收尾的全零，丢掉。
     */
    @Override
    public void gamepad(byte[] report) {
        if (grabLayout.relay) return;
        writeGamepad(pads.fromRouter(report));
        byte[] packet = new byte[2 + Hid.GAMEPAD_REPORT_SIZE];
        packet[0] = (byte) Proto.MSG_GAMEPAD;
        System.arraycopy(report, 0, packet, 2, Hid.GAMEPAD_REPORT_SIZE);
        send(packet);
    }

    @Override
    public void gesture(int fingers, int kind) {
        send(new byte[]{(byte) Proto.MSG_GESTURE, (byte) fingers, (byte) kind});
    }

    @Override
    public void haptic(int kind) {
        send(new byte[]{(byte) Proto.MSG_HAPTIC, (byte) kind});
    }

    @Override
    public void button(int id, int action) {
        send(new byte[]{(byte) Proto.MSG_BUTTON, (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id,
                (byte) action});
    }

    @Override
    public void modifiers(int latched, int held, int armed, int fn) {
        send(new byte[]{(byte) Proto.MSG_MODIFIERS, (byte) latched, (byte) held, (byte) armed, (byte) fn});   // 协议 21：第四个字节是 Fn
    }

    @Override
    public void caps(boolean sent) {
        send(new byte[]{(byte) Proto.MSG_CAPS, (byte) (sent ? 1 : 0)});
    }

    @Override
    public void schedule(long delayMs) {
        routerTimer.schedule(delayMs);
    }

    @Override
    public void cancelSchedule() {
        routerTimer.cancel();
    }

    @Override
    public void scheduleKeys(long delayMs) {
        keyTimer.schedule(delayMs);
    }

    @Override
    public void cancelKeys() {
        keyTimer.cancel();
    }

    /** 小红点 / 中键滚动杆 / 左键杆按着：按固定节拍推（调用方持有 lock）。释放触摸屏 → router.releaseAll → stopTicks，所以放开之后不会还在推光标。 */
    @Override
    public void startTicks() {
        if (tickTask != null) return;
        long period = grabLayout.relay ? TICK_RELAY_MS : TICK_MS;
        try {
            tickTask = ticker.scheduleAtFixedRate(() -> {
                synchronized (lock) {
                    router.onTick(SystemClock.uptimeMillis());
                }
            }, period, period, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 会话已经收尾、节拍线程停了
        }
    }

    @Override
    public void stopTicks() {
        if (tickTask != null) tickTask.cancel(false);
        tickTask = null;
    }

    @Override
    public void note(String s) {
        Server.log(s);
    }

    // ═══════════ 发给 App ═══════════

    /** 只在监管线程里调。 */
    private void sendGrab(boolean isGrabbed, int reason, String detail) {
        if (!isGrabbed) quietRegrab = false;
        synchronized (out) {
            try {
                out.writeByte(Proto.MSG_GRAB);
                out.writeByte(isGrabbed ? 1 : 0);
                out.writeByte(reason);
                out.writeUTF(detail == null ? "" : detail);
                out.flush();
            } catch (IOException ignored) {
                // 连接断了，命令线程会收尾
            }
        }
    }

    private void sendHardGrab(int grabbedBits, int unavailableBits, int reason, String detail) {
        synchronized (out) {
            try {
                out.writeByte(Proto.MSG_HARD_GRAB);
                out.writeByte(grabbedBits);
                out.writeByte(unavailableBits);
                out.writeByte(reason);
                out.writeUTF(detail == null ? "" : detail);
                out.flush();
            } catch (IOException ignored) {
                // 连接断了，命令线程会收尾
            }
        }
    }

    private void send(byte[] packet) {
        synchronized (out) {
            try {
                out.write(packet);
                out.flush();
            } catch (IOException ignored) {
                // 连接断了，命令线程会收尾
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 诊断模式下的鼠标包统计：攒满 1 秒、或停顿超过 1 秒后来了新包时，记一行。 */
    private static final class MouseStats {
        private long windowStart;
        private long last;
        private long maxGap;
        private int packets;
        private int sumDx;
        private int sumDy;

        void add(int dx, int dy) {
            long now = SystemClock.uptimeMillis();
            if (packets > 0 && (now - windowStart >= 1000 || now - last >= 1000)) flush();
            if (packets == 0) {
                windowStart = now;
            } else {
                maxGap = Math.max(maxGap, now - last);
            }
            last = now;
            packets++;
            sumDx += dx;
            sumDy += dy;
        }

        private void flush() {
            Server.log(String.format(Locale.ROOT, "鼠标包 %d 个 / %dms，最长间隔 %dms，位移 (%d,%d)",
                    packets, last - windowStart, maxGap, sumDx, sumDy));
            packets = 0;
            maxGap = 0;
            sumDx = sumDy = 0;
        }
    }
}
