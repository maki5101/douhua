package dev.douhua.server;

import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.douhua.GestureEngine;
import dev.douhua.Proto;
import dev.douhua.TouchRouter;

/**
 * 一次 App 连接。把这些串起来：App 发来的布局 / 参数 / 心跳 → 何时独占触摸屏；
 * 触摸 → {@link TouchRouter} 分流（触控板 → 手势引擎、键盘键 → 虚拟键盘、按钮 → 报告给 App）；
 * 分流结果 → 虚拟 USB 鼠标 / 键盘、发给 App 的震动、三指四指手势、按钮事件。
 *
 * 独占的条件：App 说 active、参数已到、心跳没超时（{@link Proto#HEARTBEAT_TIMEOUT_MS}）。
 * 任何一条不满足，100ms 内释放。连接断开时一切释放、虚拟鼠标移除。
 *
 * 线程：命令线程（douhua-session，每条连接一个）读 App 的消息；监管线程负责独占和读触摸；
 * 实体键线程（douhua-keys）负责独占和读音量 / 电源键（{@link #superviseKeys}）；
 * 定时器线程跑轻点拖动的等待和切键盘焦点。router 只在持有 lock 时访问。
 *
 * 实体键的独占条件和触摸屏一样（{@link #wanted}），另外要 App 发过 CMD_HARD_KEYS、至少要截一个键、按键注入可用、
 * 没在来电响铃 / 通话中（{@link CallState}）；
 * 不依赖触摸屏接管成功。不满足 100ms 内放开，注入过的按下全部配上松开（{@link HardKeyGate#releaseAll}）。
 *
 * 会话结束的路：App 断开、10 秒收不到任何消息（{@link Proto#IDLE_TIMEOUT_MS}，对面多半已经不在了）、
 * 被新连接顶掉（{@link #kick}，见 {@link SessionPolicy}）。三条路收尾一样，只有被顶掉的不恢复方向锁定
 * （原值留给下一个会话，见 {@link RotationLock}）。新旧会话会短暂并存：同一时刻只有一个能独占触摸屏
 * （{@link TouchGrabLock}），新会话等旧会话收完尾才建自己的虚拟设备。
 */
final class Session implements TouchRouter.Output, TouchReader.Listener {
    /** App 发来的布局，整体替换、不改内部。 */
    static final class Layout {
        static final Layout INACTIVE = new Layout(false, false, -1, 0, 0, 0, new TouchRouter.Region[0]);

        final boolean active;
        /** 蓝牙模式：鼠标键盘报文发回 App，不写虚拟设备（见 Proto.CMD_LAYOUT）。 */
        final boolean relay;
        final int displayId, rotation, width, height;
        final TouchRouter.Region[] regions;

        Layout(boolean active, boolean relay, int displayId, int rotation, int width, int height,
               TouchRouter.Region[] regions) {
            this.active = active;
            this.relay = relay;
            this.displayId = displayId;
            this.rotation = rotation;
            this.width = width;
            this.height = height;
            this.regions = regions;
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
     * App 要截哪些实体键（{@link Proto#CMD_HARD_KEYS} 的 5 个字节压成位：1 音量+点一下、2 音量+按住、4 音量−点一下、
     * 8 音量−按住、16 电源点一下）；-1 = 还没收到（这之前不碰任何实体键设备）。
     */
    private volatile int hardMask = -1;
    /** 实体键线程正独占着设备（收尾时卡住、还独占着就得让服务退出，不然音量 / 电源键一直失灵）。 */
    private volatile boolean keysExclusive;
    /** 补发实体键失败了：注入不可用，马上放开实体键（没配的键、长按电源键都补发不出去）。 */
    private volatile boolean injectBroken;
    private final LogThrottle injectLog = new LogThrottle(60_000);
    /** 来电响铃 / 通话中不截实体键；只在实体键线程里用。 */
    private final CallState calls = new CallState();

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "douhua-timer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingTimer;
    private final MouseStats stats;

    Session(Socket socket, int uid, Injector injector, boolean debug) throws IOException {
        this.socket = socket;
        this.uid = uid;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(socket.getOutputStream());
        this.injector = injector;
        this.debug = debug;
        this.stats = debug ? new MouseStats() : null;
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
     * 比外屏一直锁死、音量和电源键一直失灵好。
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
            System.exit(3);
        }
        if (keys != null && keys.isAlive() && keysExclusive) {
            Server.log("实体键线程 " + SessionPolicy.OLD_SESSION_WAIT_MS / 1000 + " 秒没退出、还独占着实体键：服务退出，"
                    + "让系统收回音量 / 电源键（要重新运行 tools/start-server.sh）");
            System.exit(3);
        }
        synchronized (lock) {
            router.releaseAll();
        }
        timer.shutdownNow();
        closeDevices();
        if (connected) Server.log("App 已断开，虚拟鼠标和键盘已移除");
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

    /** 关掉虚拟鼠标和键盘（设备随之消失，按着的键由内核补发松开）。幂等，别的线程也能调。 */
    void closeDevices() {
        UhidKeyboard k = keyboard;
        UhidMouse m = mouse;
        if (k != null) k.close();
        if (m != null) m.close();
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
                    synchronized (lock) {
                        router.setRegions(l.regions);
                    }
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
                    int usage = in.readUnsignedShort();
                    boolean down = in.readUnsignedByte() == 1;
                    synchronized (lock) {
                        router.key(usage, down);
                    }
                    break;
                }
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
                    for (int i = 0; i < 5; i++) {
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
                default:
                    throw new IOException("收到未知命令 " + cmd + "，可能 App 和服务版本不一致");
            }
            // 处理完再记：布局先换上、再算心跳（监管线程不会拿新心跳配旧布局）
            grabCondition.onMessage(cmd, SystemClock.uptimeMillis());
        }
    }

    private void readConfig() throws IOException {
        GestureEngine.Config c = new GestureEngine.Config();
        c.pointerSpeed = in.readFloat();
        c.acceleration = in.readFloat();
        c.scrollStepDp = in.readFloat();
        c.naturalScroll = in.readUnsignedByte() == 1;
        c.pinchZoom = in.readUnsignedByte() == 1;
        c.edgeScroll = in.readUnsignedByte() == 1;
        c.edgeScrollHorizontal = in.readUnsignedByte() == 1;
        c.edgeWidthDp = in.readFloat();
        c.scrollTicks = in.readUnsignedByte();
        c.longPressDrag = in.readUnsignedByte() == 1;
        c.longPressMs = in.readInt();
        c.tapToDrag = in.readUnsignedByte() == 1;
        c.dragWindowMs = in.readInt();
        float d = in.readFloat();
        c.slowFactor = in.readFloat();
        c.texture = in.readUnsignedByte() == 1;
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
        int count = in.readUnsignedShort();
        TouchRouter.Region[] regions = new TouchRouter.Region[count];
        for (int i = 0; i < count; i++) {
            regions[i] = new TouchRouter.Region(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }
        return new Layout(active, relay, displayId, rotation, width, height, regions);
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
        while (alive) {
            Layout l = layout;
            if (!wanted(l)) {
                sleep(100);
                continue;
            }
            TouchDevice dev;
            try {
                dev = TouchDevice.find(l.displayId);
            } catch (TouchDevice.NotFound e) {
                sendGrab(false, Proto.GRAB_NO_TOUCHSCREEN, String.valueOf(l.displayId));
                waitForChange(l);
                continue;
            } catch (IOException e) {
                sendGrab(false, Proto.GRAB_FAILED, e.getMessage());
                waitForChange(l);
                continue;
            }
            // 同一时刻只有一个会话能独占：被顶掉的旧会话还没放手时在这里等（每 100ms 再看一眼还要不要独占）。
            // 持有范围从打开触摸屏之前到关掉之后
            if (!GRAB.acquire(this, () -> wanted(layout) && layout.sameScreen(l))) continue;
            grabDevice = dev;
            grabLayout = l;
            try {
                // 切了模式（DeX ↔ 蓝牙）也重新接管：按着的键在原来那一路上松开，不会一半写虚拟设备、一半发回 App
                TouchReader.run(dev, this,
                        () -> wanted(layout) && layout.sameScreen(l) && layout.relay == l.relay,
                        () -> {
                            grabbed = true;
                            Server.log("已接管触摸屏：" + dev + (l.relay ? "（蓝牙模式，报文发回 App）" : ""));
                            sendGrab(true, Proto.GRAB_OK, "");
                            // 切键盘焦点是一次 binder 调用，挪到定时器线程：已独占、还没进读循环的这段时间不检查独占条件，
                            // 系统服务卡住时不能把监管线程一起卡住（破坏"100ms 内释放"）
                            if (!l.relay) {
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
            }
            if (grabbed) {
                grabbed = false;
                int why = releaseReason(l);
                Server.log("已释放触摸屏（原因 " + why + "）");
                sendGrab(false, why, "");
            }
        }
    }

    /**
     * 接管触摸屏时，把键盘输入的目标切到外接屏（DeX），不然在手机屏上打开豆花后打的字会送到手机屏、
     * 卡住整个输入队列，见 {@link DexFocus}。没接外接屏就不动。
     */
    private void focusExternal(int appDisplay) {
        for (int id : DisplayWatcher.externals()) {
            if (id == appDisplay) continue;
            if (DexFocus.moveTo(id)) Server.log("键盘输入切到外接屏 " + id);
            return;
        }
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

    // ═══════════ 独占实体键（音量、电源） ═══════════

    /** 实体键的独占条件：和触摸屏同一个 {@link #wanted}（不管触摸屏接管成功没有），再加上 App 至少要截一个键。 */
    private boolean wantedKeys(Layout l, int mask) {
        return mask > 0 && wanted(l);
    }

    /** 实体键线程里用：{@link #wantedKeys} 再加上没在来电响铃 / 通话中（{@link CallState}，电源键要能静音 / 挂断）。 */
    private boolean wantedKeysNow(Layout l, int mask) {
        return wantedKeys(l, mask) && !calls.busy(SystemClock.uptimeMillis());
    }

    /**
     * 实体键线程：满足条件就找设备（{@link KeyDevices}）、告诉 App 哪些截住了 / 截不了（MSG_HARD_GRAB），
     * 再交给 {@link KeyReader}（每 100ms 问一次还要不要截）。按键注入不可用时绝不独占：没配的键、长按电源键都补发不出去，
     * 音量和电源会整个失灵；截着时补发失败一次也算注入不可用（{@link Injector#status} 只看拿不拿得到代理，不真注入）。
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
                Server.log("按键注入不可用，不截实体键（不然音量、电源会失灵）：" + inject);
                sendHardGrab(0, want, Proto.HARD_NO_INJECTOR, inject);
                waitForKeysChange(mask);
                continue;
            }
            KeyDevices.Plan plan;
            try {
                plan = KeyDevices.plan(KeyDevices.parse(KeyDevices.readProc()),
                        new KeyDevices.Config((mask & 3) != 0, (mask & 12) != 0, (mask & 16) != 0));
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
                gate.config((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0, (mask & 16) != 0);
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
        return ((mask & 3) != 0 ? Proto.HARD_BIT_VOL_UP : 0) | ((mask & 12) != 0 ? Proto.HARD_BIT_VOL_DOWN : 0)
                | ((mask & 16) != 0 ? Proto.HARD_BIT_POWER : 0);
    }

    private static String describeHard(int mask) {
        if (mask == 0) return "无";
        String[] names = {"音量+点一下", "音量+按住", "音量−点一下", "音量−按住", "电源点一下"};
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

    /** Linux 键码 → Android 键码。 */
    private static int androidKey(int linux) {
        switch (linux) {
            case KeyDevices.KEY_VOLUMEUP: return KeyEvent.KEYCODE_VOLUME_UP;
            case KeyDevices.KEY_VOLUMEDOWN: return KeyEvent.KEYCODE_VOLUME_DOWN;
            default: return KeyEvent.KEYCODE_POWER;
        }
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

    @Override
    public void onDown(int slot, float rawX, float rawY, long t) {
        float[] p = toScreen(rawX, rawY);
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
        synchronized (lock) {
            router.move(slot, p[0], p[1]);
        }
    }

    @Override
    public void onFrame(long t) {
        synchronized (lock) {
            router.frame(t);
        }
    }

    @Override
    public void onUp(int slot, long t) {
        synchronized (lock) {
            router.up(slot, t);
        }
    }

    // ═══════════ 分流结果（调用方持有 lock） ═══════════
    // 去哪一路按最近一次接管时的布局：DeX 模式写虚拟设备，蓝牙模式发回 App。接管结束后的松开（releaseAll）也走同一路。

    @Override
    public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (stats != null) stats.add(dx, dy);
        if (grabLayout.relay) {
            short x = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, dx));
            short y = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, dy));
            send(new byte[]{(byte) Proto.MSG_MOUSE, (byte) buttons, (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y,
                    (byte) Math.max(-127, Math.min(127, wheel)), (byte) Math.max(-127, Math.min(127, pan))});
            return;
        }
        try {
            mouse.send(buttons, dx, dy, wheel, pan);
        } catch (IOException e) {
            Server.log("写虚拟鼠标失败：" + e.getMessage());
        }
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
        try {
            keyboard.send(report);
        } catch (IOException e) {
            Server.log("写虚拟键盘失败：" + e.getMessage());
        }
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
    public void modifiers(int latched) {
        send(new byte[]{(byte) Proto.MSG_MODIFIERS, (byte) latched});
    }

    @Override
    public void schedule(long delayMs) {
        cancelSchedule();
        try {
            pendingTimer = timer.schedule(() -> {
                synchronized (lock) {
                    router.onTimer(SystemClock.uptimeMillis());
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 会话已经收尾、定时器停了（监管线程比收尾晚退出时）
        }
    }

    @Override
    public void cancelSchedule() {
        if (pendingTimer != null) pendingTimer.cancel(false);
        pendingTimer = null;
    }

    // ═══════════ 发给 App ═══════════

    private void sendGrab(boolean isGrabbed, int reason, String detail) {
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
