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

/**
 * 一次 App 连接。把这些串起来：App 发来的布局 / 参数 / 心跳 → 何时独占触摸屏；
 * 触摸 → 落在触控板的交给手势引擎、落在键盘键上的直接按虚拟键盘、落在按钮上的报告给 App；
 * 引擎输出 → 虚拟鼠标、Ctrl + 滚轮缩放、震动、三指四指手势。
 *
 * 键盘修饰键（Ctrl / Shift / Alt / Win）点一下锁定、立即按下，打完下一个普通键自动解开，再点一下也能解开；
 * 锁定期间点触控板就是 Ctrl + 点击之类。
 *
 * 独占的条件：App 说 active、参数已到、心跳没超时（{@link Proto#HEARTBEAT_TIMEOUT_MS}）。
 * 任何一条不满足，100ms 内释放。连接断开时一切释放、虚拟鼠标移除。
 *
 * 线程：命令线程读 App 的消息；监管线程负责独占和读触摸；定时器线程跑轻点拖动的等待。
 * 手势引擎和按钮状态都只在持有 lock 时访问。
 */
final class Session implements GestureEngine.Output, TouchReader.Listener {
    private static final int SLOTS = MtDecoder.SLOTS;
    private static final int KIND_NONE = 0;
    private static final int KIND_PAD = 1;
    private static final int KIND_BUTTON = 2;
    private static final int KIND_HOLD = 3;
    private static final int KIND_KEY = 4;

    /** 屏幕上的一块区域（像素）。 */
    static final class Region {
        final int type, id, left, top, right, bottom;

        Region(int type, int id, int left, int top, int right, int bottom) {
            this.type = type;
            this.id = id;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean contains(float x, float y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /** App 发来的布局，整体替换、不改内部。 */
    static final class Layout {
        static final Layout INACTIVE = new Layout(false, -1, 0, 0, 0, new Region[0]);

        final boolean active;
        final int displayId, rotation, width, height;
        final Region[] regions;

        Layout(boolean active, int displayId, int rotation, int width, int height, Region[] regions) {
            this.active = active;
            this.displayId = displayId;
            this.rotation = rotation;
            this.width = width;
            this.height = height;
            this.regions = regions;
        }

        Region hit(float x, float y) {
            for (Region r : regions) if (r.contains(x, y)) return r;
            return null;
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
    private GestureEngine engine;
    private float density = 1;
    private final int[] slotKind = new int[SLOTS];
    private final Region[] slotRegion = new Region[SLOTS];
    private final float[] lastX = new float[SLOTS];
    private final float[] lastY = new float[SLOTS];
    private int holds;
    /** 点一下锁定的修饰键。 */
    private int latched;
    /** 开合缩放期间按住的 Ctrl。 */
    private boolean zoomCtrl;
    /** 正按着的"自带修饰键"的键（"@"、"复制"之类），它们的修饰键要一起按着。 */
    private final java.util.List<Integer> chordKeys = new java.util.ArrayList<>();
    /** 引擎最近一次报给鼠标的按键状态（缩放时发滚轮要带上）。 */
    private int lastButtons;

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
                releaseContacts();
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
                        if (engine != null) engine.click(button);
                    }
                    break;
                }
                case Proto.CMD_CONFIG:
                    readConfig();
                    break;
                case Proto.CMD_LAYOUT:
                    layout = readLayout();
                    lastHeartbeat = SystemClock.uptimeMillis();
                    break;
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
                        if (down) keyDown(usage);
                        else keyUp(usage);
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
        synchronized (lock) {
            if (engine != null) engine.cancel();
            engine = new GestureEngine(this, c);
            density = d > 0 ? d : 1;
            clearSlots();
        }
    }

    private Layout readLayout() throws IOException {
        boolean active = in.readUnsignedByte() == 1;
        int displayId = in.readInt();
        int rotation = in.readUnsignedByte();
        int width = in.readInt();
        int height = in.readInt();
        int count = in.readUnsignedShort();
        Region[] regions = new Region[count];
        for (int i = 0; i < count; i++) {
            regions[i] = new Region(in.readUnsignedByte(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
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
            ready = engine != null;
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
                        });
            } catch (IOException e) {
                Server.log("接管触摸屏失败：" + e.getMessage());
                sendGrab(false, Proto.GRAB_FAILED, e.getMessage());
                waitForChange(l);
            } finally {
                synchronized (lock) {
                    releaseContacts();
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
        float x = p[0];
        float y = p[1];
        Region r = layout.hit(x, y);
        if (debug) {
            Server.log(String.format(Locale.ROOT, "按下 #%d (%.0f,%.0f) 方向 %d → %s", slot, x, y, grabLayout.rotation,
                    r == null ? "空白处" : "区域 type=" + r.type + " id=" + r.id));
        }
        synchronized (lock) {
            lastX[slot] = x;
            lastY[slot] = y;
            slotRegion[slot] = r;
            if (r == null || engine == null) {
                slotKind[slot] = KIND_NONE;
                return;
            }
            switch (r.type) {
                case Proto.REGION_PAD: {
                    slotKind[slot] = KIND_PAD;
                    // 按在触控板边缘带里：左右两条单指上下滑 = 竖向滚动，上下两条单指左右滑 = 横向滚动
                    float edge = engine.config().edgeWidthDp * density;
                    int flags = 0;
                    if (x - r.left < edge || r.right - x < edge) flags |= GestureEngine.EDGE_VERTICAL;
                    if (y - r.top < edge || r.bottom - y < edge) flags |= GestureEngine.EDGE_HORIZONTAL;
                    engine.down(slot, x / density, y / density, t, flags);
                    break;
                }
                case Proto.REGION_HOLD_LEFT:
                    slotKind[slot] = KIND_HOLD;
                    if (holds++ == 0) engine.setExternalButtons(Proto.BUTTON_LEFT);
                    sendButton(r.id, Proto.BUTTON_DOWN);
                    break;
                case Proto.REGION_KEY:
                    slotKind[slot] = KIND_KEY;
                    keyDown(r.id);
                    sendButton(Proto.KEY_ID_BASE + r.id, Proto.BUTTON_DOWN);
                    break;
                default:
                    slotKind[slot] = KIND_BUTTON;
                    sendButton(r.id, Proto.BUTTON_DOWN);
                    break;
            }
        }
    }

    @Override
    public void onMove(int slot, float rawX, float rawY) {
        float[] p = toScreen(rawX, rawY);
        float x = p[0];
        float y = p[1];
        synchronized (lock) {
            lastX[slot] = x;
            lastY[slot] = y;
            if (slotKind[slot] == KIND_PAD && engine != null) engine.move(slot, x / density, y / density);
        }
    }

    @Override
    public void onFrame(long t) {
        synchronized (lock) {
            if (engine != null) engine.endFrame(t);
        }
    }

    @Override
    public void onUp(int slot, long t) {
        synchronized (lock) {
            int kind = slotKind[slot];
            Region r = slotRegion[slot];
            slotKind[slot] = KIND_NONE;
            slotRegion[slot] = null;
            if (engine == null) return;
            switch (kind) {
                case KIND_PAD:
                    engine.up(slot, t);
                    break;
                case KIND_HOLD:
                    if (--holds <= 0) {
                        holds = 0;
                        engine.setExternalButtons(0);
                    }
                    sendButton(r.id, Proto.BUTTON_UP);
                    break;
                case KIND_BUTTON:
                    sendButton(r.id, r.contains(lastX[slot], lastY[slot]) ? Proto.BUTTON_TAP : Proto.BUTTON_UP);
                    break;
                case KIND_KEY:
                    keyUp(r.id);
                    sendButton(Proto.KEY_ID_BASE + r.id, Proto.BUTTON_UP);
                    break;
                default:
                    break;
            }
        }
    }

    // ═══════════ 虚拟键盘（调用方持有 lock） ═══════════

    /** key = HID 用法码 | (自带修饰键 << 8)，见 Proto.REGION_KEY。 */
    private void keyDown(int key) {
        int usage = key & 0xFF;
        int chord = (key >> 8) & 0xFF;
        try {
            if (chord == 0 && UhidKeyboard.isModifier(usage)) {
                latched ^= UhidKeyboard.modifierBit(usage);
                applyModifiers();
                sendModifiers();
                return;
            }
            if (chord != 0) {
                chordKeys.add(key);
                applyModifiers();   // 先按下修饰键，再按键
            }
            keyboard.press(usage);
        } catch (IOException e) {
            Server.log("写虚拟键盘失败：" + e.getMessage());
        }
    }

    private void keyUp(int key) {
        int usage = key & 0xFF;
        int chord = (key >> 8) & 0xFF;
        if (chord == 0 && UhidKeyboard.isModifier(usage)) return;   // 修饰键是点一下锁定，松手不算
        try {
            keyboard.release(usage);   // 先松开键，再松开它自带的修饰键
            chordKeys.remove(Integer.valueOf(key));
            boolean hadLatched = latched != 0;
            latched = 0;               // 打完一个键，锁定的修饰键自动解开
            applyModifiers();
            if (hadLatched) sendModifiers();
        } catch (IOException e) {
            Server.log("写虚拟键盘失败：" + e.getMessage());
        }
    }

    private void applyModifiers() throws IOException {
        int chords = 0;
        for (int k : chordKeys) chords |= (k >> 8) & 0xFF;
        keyboard.setModifiers(latched | chords | (zoomCtrl ? UhidKeyboard.MOD_CTRL : 0));
    }

    private void sendModifiers() {
        send(new byte[]{(byte) Proto.MSG_MODIFIERS, (byte) latched});
    }

    /** 释放触摸屏时：手势作废、按住的鼠标键和键盘键松开、按着的按钮报告松开。调用方持有 lock。 */
    private void releaseContacts() {
        if (engine != null) engine.cancel();
        for (int s = 0; s < SLOTS; s++) {
            if (slotRegion[s] == null) continue;
            if (slotKind[s] == KIND_BUTTON || slotKind[s] == KIND_HOLD) {
                sendButton(slotRegion[s].id, Proto.BUTTON_UP);
            } else if (slotKind[s] == KIND_KEY) {
                sendButton(Proto.KEY_ID_BASE + slotRegion[s].id, Proto.BUTTON_UP);
            }
        }
        clearSlots();
        boolean hadLatched = latched != 0;
        latched = 0;
        zoomCtrl = false;
        chordKeys.clear();
        try {
            keyboard.releaseAll();
        } catch (IOException e) {
            Server.log("写虚拟键盘失败：" + e.getMessage());
        }
        if (hadLatched) sendModifiers();
    }

    private void clearSlots() {
        for (int s = 0; s < SLOTS; s++) {
            slotKind[s] = KIND_NONE;
            slotRegion[s] = null;
        }
        holds = 0;
    }

    // ═══════════ 手势引擎输出 ═══════════

    @Override
    public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (stats != null) stats.add(dx, dy);
        lastButtons = buttons;
        try {
            mouse.send(buttons, dx, dy, wheel, pan);
        } catch (IOException e) {
            Server.log("写虚拟鼠标失败：" + e.getMessage());
        }
    }

    /** 缩放 = 按住 Ctrl 滚滚轮（浏览器、DeX 里的通用缩放方式）。 */
    @Override
    public void zoom(int steps) {
        try {
            if (!zoomCtrl) {
                zoomCtrl = true;
                applyModifiers();
            }
            mouse.send(lastButtons, 0, 0, steps, 0);
        } catch (IOException e) {
            Server.log("缩放失败：" + e.getMessage());
        }
    }

    @Override
    public void zoomEnd() {
        if (!zoomCtrl) return;
        zoomCtrl = false;
        try {
            applyModifiers();
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
    public void schedule(long delayMs) {
        cancelSchedule();
        pendingTimer = timer.schedule(() -> {
            synchronized (lock) {
                if (engine != null) engine.onTimer(SystemClock.uptimeMillis());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancelSchedule() {
        if (pendingTimer != null) pendingTimer.cancel(false);
        pendingTimer = null;
    }

    // ═══════════ 发给 App ═══════════

    private void sendButton(int id, int action) {
        send(new byte[]{(byte) Proto.MSG_BUTTON, (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id,
                (byte) action});
    }

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
