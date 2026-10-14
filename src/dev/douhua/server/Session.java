package dev.douhua.server;

import android.os.SystemClock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executors;
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
 * 线程：命令线程读 App 的消息；监管线程负责独占和读触摸；定时器线程跑轻点拖动的等待。
 * router 只在持有 lock 时访问。
 */
final class Session implements TouchRouter.Output, TouchReader.Listener {
    /** App 发来的布局，整体替换、不改内部。 */
    static final class Layout {
        static final Layout INACTIVE = new Layout(false, -1, 0, 0, 0, new TouchRouter.Region[0]);

        final boolean active;
        final int displayId, rotation, width, height;
        final TouchRouter.Region[] regions;

        Layout(boolean active, int displayId, int rotation, int width, int height, TouchRouter.Region[] regions) {
            this.active = active;
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

    private final DataInputStream in;
    private final DataOutputStream out;
    private final UhidMouse mouse;
    private final UhidKeyboard keyboard;
    private final Injector injector;
    private final boolean debug;

    private final Object lock = new Object();
    private final TouchRouter router = new TouchRouter(this);

    private volatile Layout layout = Layout.INACTIVE;
    /** 正在用的触摸屏和接管时的布局：触摸坐标按它们换算到当前方向。 */
    private volatile TouchDevice grabDevice;
    private volatile Layout grabLayout = Layout.INACTIVE;
    private volatile long lastHeartbeat;
    private volatile boolean alive = true;
    private volatile boolean grabbed;

    /** 转过哪块屏、转之前的方向锁定（`wm user-rotation` 的输出，比如 "lock 0" / "free"），断开时恢复。 */
    private int rotatedDisplay = -1;
    private String originalRotation;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "douhua-timer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingTimer;
    private final MouseStats stats;

    Session(DataInputStream in, DataOutputStream out, UhidMouse mouse, UhidKeyboard keyboard, Injector injector,
            boolean debug) {
        this.in = in;
        this.out = out;
        this.mouse = mouse;
        this.keyboard = keyboard;
        this.injector = injector;
        this.debug = debug;
        this.stats = debug ? new MouseStats() : null;
    }

    /** 阻塞到连接断开。 */
    void run() {
        Thread supervisor = new Thread(this::supervise, "douhua-touch");
        supervisor.start();
        try {
            commandLoop();
        } catch (IOException e) {
            Server.log("连接出错：" + e.getMessage());
        } finally {
            alive = false;
            try {
                supervisor.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            synchronized (lock) {
                router.releaseAll();
            }
            timer.shutdownNow();
            restoreRotation();
        }
    }

    // ═══════════ App 发来的命令 ═══════════

    private void commandLoop() throws IOException {
        while (true) {
            int cmd = in.read();
            if (cmd < 0) return;
            switch (cmd) {
                case Proto.CMD_KEY: {
                    int displayId = in.readInt();
                    int keyCode = in.readInt();
                    int meta = in.readInt();
                    if (injector == null) break;
                    try {
                        injector.key(displayId, keyCode, meta);
                    } catch (ReflectiveOperationException e) {
                        Server.log("注入按键 " + keyCode + " 失败：" + e);
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
                    lastHeartbeat = SystemClock.uptimeMillis();
                    break;
                }
                case Proto.CMD_HEARTBEAT:
                    lastHeartbeat = SystemClock.uptimeMillis();
                    break;
                case Proto.CMD_ROTATE: {
                    int displayId = in.readInt();
                    int rotation = in.readUnsignedByte();
                    rotate(displayId, rotation);
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
                default:
                    throw new IOException("收到未知命令 " + cmd + "，可能 App 和服务版本不一致");
            }
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
        synchronized (lock) {
            router.setConfig(c, d);
        }
    }

    private Layout readLayout() throws IOException {
        boolean active = in.readUnsignedByte() == 1;
        int displayId = in.readInt();
        int rotation = in.readUnsignedByte();
        int width = in.readInt();
        int height = in.readInt();
        int count = in.readUnsignedShort();
        TouchRouter.Region[] regions = new TouchRouter.Region[count];
        for (int i = 0; i < count; i++) {
            regions[i] = new TouchRouter.Region(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }
        return new Layout(active, displayId, rotation, width, height, regions);
    }

    // ═══════════ 转屏 ═══════════

    /**
     * 把屏幕锁定到某个方向。App 自己请求方向不管用：这块屏设了"忽略 App 的方向请求"，
     * 系统只会把 App 画面转过来塞进竖屏，触摸坐标就对不上了。
     */
    private void rotate(int displayId, int rotation) {
        if (rotation < 0 || rotation > 3) return;
        String id = String.valueOf(displayId);
        if (rotatedDisplay != displayId) {
            restoreRotation();
            originalRotation = shell("wm", "user-rotation", "-d", id).trim();
            rotatedDisplay = displayId;
        }
        shell("wm", "user-rotation", "-d", id, "lock", String.valueOf(rotation));
        Server.log("屏幕 " + displayId + " 转到方向 " + rotation + "（原来是 " + originalRotation + "）");
    }

    private void restoreRotation() {
        if (rotatedDisplay < 0 || originalRotation == null) return;
        String id = String.valueOf(rotatedDisplay);
        String[] parts = originalRotation.split("\\s+");
        if (parts.length == 2 && parts[0].equals("lock") && parts[1].matches("[0-3]")) {
            shell("wm", "user-rotation", "-d", id, "lock", parts[1]);
        } else if (parts[0].equals("free")) {
            shell("wm", "user-rotation", "-d", id, "free");
        } else {
            Server.log("看不懂原来的方向（" + originalRotation + "），不恢复");
        }
        Server.log("屏幕 " + rotatedDisplay + " 恢复成原来的方向：" + originalRotation);
        rotatedDisplay = -1;
        originalRotation = null;
    }

    private static String shell(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = p.getInputStream().read(chunk)) > 0) buf.write(chunk, 0, n);
            p.waitFor();
            return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            Server.log("执行 " + String.join(" ", cmd) + " 失败：" + e);
            return "";
        }
    }

    // ═══════════ 独占触摸屏 ═══════════

    private boolean wanted(Layout l) {
        boolean ready;
        synchronized (lock) {
            ready = router.ready();
        }
        return alive && l.active && ready
                && SystemClock.uptimeMillis() - lastHeartbeat < Proto.HEARTBEAT_TIMEOUT_MS;
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
            grabDevice = dev;
            grabLayout = l;
            try {
                TouchReader.run(dev, this,
                        () -> wanted(layout) && layout.sameScreen(l),
                        () -> {
                            grabbed = true;
                            Server.log("已接管触摸屏：" + dev);
                            sendGrab(true, Proto.GRAB_OK, "");
                            focusExternal(l.displayId);
                        });
            } catch (IOException e) {
                Server.log("接管触摸屏失败：" + e.getMessage());
                sendGrab(false, Proto.GRAB_FAILED, e.getMessage());
                waitForChange(l);
            } finally {
                synchronized (lock) {
                    router.releaseAll();
                }
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
        if (!alive) return Proto.GRAB_DISCONNECTED;
        if (SystemClock.uptimeMillis() - lastHeartbeat >= Proto.HEARTBEAT_TIMEOUT_MS) return Proto.GRAB_UNRESPONSIVE;
        if (!now.active) return Proto.GRAB_PAUSED;
        if (!now.sameScreen(before)) return Proto.GRAB_ROTATING;
        return Proto.GRAB_RELEASED;
    }

    /** 出错后别马上重试同样的事：等布局变了、或不再需要独占再说。 */
    private void waitForChange(Layout l) {
        while (alive && layout == l && wanted(l)) sleep(200);
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
                    r == null ? "空白处" : r.toString()));
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

    @Override
    public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (stats != null) stats.add(dx, dy);
        try {
            mouse.send(buttons, dx, dy, wheel, pan);
        } catch (IOException e) {
            Server.log("写虚拟鼠标失败：" + e.getMessage());
        }
    }

    @Override
    public void keyboard(byte[] report) {
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
        pendingTimer = timer.schedule(() -> {
            synchronized (lock) {
                router.onTimer(SystemClock.uptimeMillis());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
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
