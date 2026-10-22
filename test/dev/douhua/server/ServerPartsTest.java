package dev.douhua.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.douhua.Proto;

/**
 * 服务端纯 Java 部件的离线测试：多点触控解码（MtDecoder）、从 dumpsys input 里找触摸屏（TouchDevice）、
 * 会话交接用的部件（TouchGrabLock、SessionPolicy、RotationLock）、心跳和释放原因（GrabCondition）、日志限频（LogThrottle）、
 * 带超时的子进程（Shell）、实体键（KeyDevices、HardKeyGate）。
 * 不依赖 Android、不需要 JUnit：tools/test.sh 用电脑上的 JDK 直接编译运行。
 */
public final class ServerPartsTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        singleFinger();
        twoFingers();
        fingerAlreadyDownIsIgnored();
        palmIsNotAFinger();
        droppedEventsReleaseEverything();
        newFingerInSameSlotSameFrame();
        orderWithinFrame();
        findsCoverTouchscreen();
        findsInnerTouchscreen();
        missingDisplayFails();
        rotationMatchesRealDevice();
        rotationRoundTrip();
        realMatrixIsUnrotated();
        logThrottle();
        logThrottleDescribe();
        grabLock();
        sessionPolicy();
        grabCondition();
        rotationLock();
        shellRun();
        keyDevicesParse();
        keyDevicesExcludes();
        keyDevicesPlan();
        keyDevicesOddFormat();
        keyDevicesWatch();
        keyDevicesReadFails();
        gateStartsWithSystem();
        gatePassThrough();
        gateStrayUp();
        gateVolumeConfigured();
        gateVolumeHalf();
        gateIgnoresPower();
        gateCombo();
        gateObserveVolumeIgnored();
        gateUnseen();
        gateCooldown();
        gateReleaseAll();

        System.err.println("服务端部件：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ MtDecoder ═══════════

    static final class Rec implements MtDecoder.Listener {
        final List<String> log = new ArrayList<>();

        @Override
        public void down(int slot, int x, int y) {
            log.add("down" + slot + "@" + x + "," + y);
        }

        @Override
        public void move(int slot, int x, int y) {
            log.add("move" + slot + "@" + x + "," + y);
        }

        @Override
        public void frame() {
            log.add("frame");
        }

        @Override
        public void up(int slot) {
            log.add("up" + slot);
        }

        /** 去掉 frame，方便比对。 */
        List<String> events() {
            List<String> r = new ArrayList<>();
            for (String s : log) if (!s.equals("frame")) r.add(s);
            return r;
        }
    }

    static void abs(MtDecoder d, int code, int value) {
        d.feed(MtDecoder.EV_ABS, code, value);
    }

    static void syn(MtDecoder d) {
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_REPORT, 0);
    }

    static void singleFinger() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 10);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 100);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 200);
        syn(d);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 110);
        syn(d);
        syn(d);   // 没变化的帧
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        eq(r.events(), list("down0@100,200", "move0@110,200", "up0"), "单指：按下、移动、抬起");
        eq(count(r.log, "frame"), 4, "每个 SYN_REPORT 一帧");
    }

    static void twoFingers() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 10);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 10);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 50);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 10);
        syn(d);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 20);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 20);
        syn(d);
        eq(r.events(), list("down0@10,10", "down1@50,10", "move0@10,20", "move1@50,20"), "双指各自跟踪");
    }

    static void fingerAlreadyDownIsIgnored() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        // 开始读之前就按着：只看到位置更新，没看到 tracking id
        abs(d, MtDecoder.ABS_MT_POSITION_X, 300);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 7);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 5);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 6);
        syn(d);
        eq(r.events(), list("down0@5,6"), "之前就按着的手指不理，重新按下才算");
    }

    static void palmIsNotAFinger() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 9);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);
        syn(d);
        eq(r.events(), list("down1@9,0", "up1"), "手掌不算；手指中途变手掌按抬起处理");
    }

    static void droppedEventsReleaseEverything() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 1);
        syn(d);
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_DROPPED, 0);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 99);
        syn(d);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 50);   // 那根手指还按着，但已作废
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 3);
        syn(d);
        eq(r.events(), list("down0@1,0", "up0", "down0@3,0"), "丢数据：全部抬起，作废的手指不再报告");
    }

    static void newFingerInSameSlotSameFrame() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);   // 同一帧里换了一根手指
        abs(d, MtDecoder.ABS_MT_POSITION_X, 8);
        syn(d);
        eq(r.events(), list("down0@1,0", "up0", "down0@8,0"), "同一 slot 换手指：先抬起旧的再按下新的");
    }

    static void orderWithinFrame() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        syn(d);
        r.log.clear();
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        syn(d);
        eq(r.log, list("down1@0,0", "frame", "up0"), "一帧里：按下 → 帧结束 → 抬起");
    }

    // ═══════════ TouchDevice ═══════════

    /**
     * 按真机 dumpsys input 的格式缩写的样本（外屏 = sec_touchscreen2）。
     * 矩阵照抄真机转屏后的输出：仍然只是缩放（加一点点平移），不含旋转——旋转由 ScreenRotation 另做。
     */
    static final String DUMP = String.join("\n",
            "INPUT MANAGER (dumpsys input)",
            "Event Hub State:",
            "  BuiltInKeyboardId: -2",
            "  Devices:",
            "    5: sec_touchscreen",
            "      Classes: KEYBOARD | TOUCH | TOUCH_MT",
            "      Path: /dev/input/event11",
            "      Enabled: true",
            "    7: sec_touchscreen2",
            "      Classes: KEYBOARD | TOUCH | TOUCH_MT",
            "      Path: /dev/input/event9",
            "      Enabled: true",
            "    4: sec_touchpad",
            "      Classes: KEYBOARD | TOUCH | TOUCH_MT | TOUCHPAD",
            "      Path: /dev/input/event12",
            "      Enabled: false",
            "",
            "Input Reader State (Nums of device: 3):",
            "  Device 9: sec_touchpad",
            "    Sources: KEYBOARD | MOUSE | TOUCHPAD",
            "      Viewport INTERNAL: displayId=0, uniqueId=local:1, port=148, orientation=0",
            "        RawToDisplay Transform: (ROT_0) (SCALE )",
            "            9.0000  0.0000  0.0000",
            "            0.0000  9.0000  0.0000",
            "            0.0000  0.0000  1.0000",
            "  Device 8: sec_touchscreen",
            "    Sources: KEYBOARD | TOUCHSCREEN",
            "      Viewport INTERNAL: displayId=1, uniqueId=local:2, port=147, orientation=0",
            "        RawToDisplay Transform: (ROT_0) (SCALE )",
            "            0.5977  0.0000  0.0000",
            "            0.0000  0.4512  0.0000",
            "            0.0000  0.0000  1.0000",
            "  Device 7: sec_touchscreen2",
            "    Sources: KEYBOARD | TOUCHSCREEN",
            "      Viewport INTERNAL: displayId=0, uniqueId=local:1, port=148, orientation=0",
            "        RawToDisplay Transform: (ROT_0) (SCALE TRANSLATE)",
            "            0.3047  0.0000  0.3047",
            "            0.0000  0.4814  0.0000",
            "            0.0000  0.0000  1.0000",
            "",
            "Input Dispatcher State:",
            "  DispatchEnabled: true");

    static void findsCoverTouchscreen() throws IOException {
        TouchDevice t = TouchDevice.parse(DUMP, 0);
        eq(t.name, "sec_touchscreen2", "屏幕 0 对应外屏触摸屏（跳过同样绑在屏幕 0 的触控板设备）");
        eq(t.path, "/dev/input/event9", "设备路径");
        near(t.mapX(4095, 0), 1248f, "原始 x 0–4095 → 竖屏 x 0–1248");
        near(t.mapY(0, 4095), 1971.3f, "原始 y 0–4095 → 竖屏 y 0–1972");
        check(t.unrotated(), "真机矩阵不含旋转");
    }

    static void findsInnerTouchscreen() throws IOException {
        TouchDevice t = TouchDevice.parse(DUMP, 1);
        eq(t.path, "/dev/input/event11", "屏幕 1 对应内屏触摸屏");
        near(t.mapX(4095, 4095), 2447.6f, "竖屏缩放");
    }

    static void missingDisplayFails() {
        try {
            TouchDevice.parse(DUMP, 5);
            check(false, "没有触摸屏的屏幕应该报错");
        } catch (IOException e) {
            check(e.getMessage().contains("5"), "报错要说清是哪块屏：" + e.getMessage());
        }
    }

    // ═══════════ ScreenRotation ═══════════

    /** 真机横屏（方向 1）时系统记录的变换：x = 原 y，y = 1248 − 原 x。 */
    static void rotationMatchesRealDevice() {
        float[] p = ScreenRotation.apply(100, 300, 1, 1248, 1972);
        near(p[0], 300, "方向 1：x = 原 y");
        near(p[1], 1148, "方向 1：y = 竖屏宽 − 原 x");
        p = ScreenRotation.apply(0, 0, 1, 1248, 1972);
        near(p[1], 1248, "方向 1：竖屏左上角转到横屏左下角");
        p = ScreenRotation.apply(100, 300, 0, 1248, 1972);
        near(p[0], 100, "方向 0 不变");
        near(p[1], 300, "方向 0 不变");
    }

    /** 每个方向都要把整块竖屏正好铺满对应方向的屏幕（四个角落在四个角上）。 */
    static void rotationRoundTrip() {
        float w = 1248, h = 1972;
        for (int r = 0; r < 4; r++) {
            float lw = (r & 1) == 1 ? h : w;
            float lh = (r & 1) == 1 ? w : h;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] c : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
                float[] p = ScreenRotation.apply(c[0], c[1], r, w, h);
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
            check(minX == 0 && maxX == lw && minY == 0 && maxY == lh, "方向 " + r + " 铺满 " + lw + "x" + lh);
        }
        float[] a = ScreenRotation.apply(10, 20, 1, w, h);
        float[] b = ScreenRotation.apply(10, 20, 3, w, h);
        check(a[0] + b[0] == h && a[1] + b[1] == w, "方向 1 和 3 互为 180° 翻转");
    }

    /** 真机的矩阵（转屏后也是）只是缩放：要自己转。 */
    static void realMatrixIsUnrotated() {
        TouchDevice real = new TouchDevice("sec_touchscreen2", "/dev/input/event9",
                new float[]{0.3047f, 0, 0.3047f, 0, 0.4814f, 0});
        check(real.unrotated(), "真机矩阵判定为不含旋转");
        TouchDevice rotated = new TouchDevice("x", "y", new float[]{0, 0.4814f, 0, -0.3047f, 0, 1248});
        check(!rotated.unrotated(), "含旋转的矩阵不再自己转");
    }

    // ═══════════ LogThrottle ═══════════

    static void logThrottle() {
        LogThrottle t = new LogThrottle(60_000);
        check(t.ok(0) == null, "从没出过错时成功不记");
        eq(t.error("坏了", 1000), "坏了", "第一次出错要记");
        boolean quiet = true;
        for (int i = 1; i <= 40; i++) quiet &= t.error("坏了", 1000 + i * 1000L) == null;
        check(quiet, "60 秒内同样的错误重复 40 次一行都不记");
        String summary = t.error("坏了", 61_000);
        check(summary != null && summary.contains("又出现 40 次"), "到 60 秒记一行汇总（实际 " + summary + "）");
        check(t.error("坏了", 62_000) == null, "汇总之后又从头限频");
        eq(t.error("另一种错", 63_000), "另一种错", "换成不同的错误立刻记");
        String back = t.ok(71_000);
        check(back != null && back.contains("恢复了") && back.contains("出错 44 次") && back.contains("持续 70 秒"),
                "出错后第一次成功记一行恢复（实际 " + back + "）");
        check(t.ok(72_000) == null, "恢复之后再成功不记");
        eq(t.error("坏了", 80_000), "坏了", "恢复后再出同样的错照样先记");
    }

    static void logThrottleDescribe() {
        String d = LogThrottle.describe(new java.lang.reflect.InvocationTargetException(
                new RuntimeException(new IllegalStateException("x"))));
        check(d.contains("java.lang.reflect.InvocationTargetException") && d.contains("java.lang.RuntimeException")
                && d.contains("java.lang.IllegalStateException") && d.endsWith("x"), "原因链每层的类名和最里层的消息都在（实际 " + d + "）");
        check(d.indexOf("InvocationTargetException") < d.indexOf("RuntimeException")
                && d.indexOf("RuntimeException") < d.indexOf("IllegalStateException"), "按从外到里的顺序");
        eq(LogThrottle.describe(new java.io.IOException("断了")), "java.io.IOException：断了", "没有原因时就一层");
    }

    // ═══════════ TouchGrabLock ═══════════

    static void grabLock() throws Exception {
        TouchGrabLock lock = new TouchGrabLock();
        Object a = "A", b = "B";
        check(lock.acquire(a, () -> true), "A 拿到锁");
        check(lock.acquire(a, () -> true), "同一个 owner 重复拿直接成功");
        check(lock.heldBy(a) && !lock.heldBy(b), "heldBy 认得持有者");

        // B 等着；不再需要时约 100ms 内放弃
        java.util.concurrent.atomic.AtomicBoolean wanted = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<Boolean> got = new java.util.concurrent.atomic.AtomicReference<>();
        Thread tb = new Thread(() -> got.set(lock.acquire(b, wanted::get)));
        tb.start();
        tb.join(300);
        check(tb.isAlive() && got.get() == null, "A 持有时 B 的 acquire 一直阻塞");
        long t0 = System.nanoTime();
        wanted.set(false);
        tb.join(1000);
        long waited = (System.nanoTime() - t0) / 1_000_000;
        check(!tb.isAlive() && Boolean.FALSE.equals(got.get()) && waited < 300,
                "B 不再需要后约 100ms 内返回 false（实际 " + waited + "ms）");
        check(lock.heldBy(a), "B 放弃后锁还在 A 手里");

        lock.release(b);
        check(lock.heldBy(a), "不是持有者调 release 什么都不做");

        // A 放手，等着的 B 马上接上
        got.set(null);
        Thread tb2 = new Thread(() -> got.set(lock.acquire(b, () -> true)));
        tb2.start();
        tb2.join(200);
        check(tb2.isAlive(), "B 又在等");
        t0 = System.nanoTime();
        lock.release(a);
        tb2.join(1000);
        waited = (System.nanoTime() - t0) / 1_000_000;
        check(!tb2.isAlive() && Boolean.TRUE.equals(got.get()) && waited < 250,
                "A 放手后 B 约 150ms 内拿到锁（实际 " + waited + "ms）");
        check(lock.heldBy(b) && !lock.heldBy(a), "锁换到 B 手里");
        check(!lock.acquire(a, () -> false), "不需要时直接返回 false");
        lock.release(b);
        check(!lock.heldBy(b), "B 放手后没人持有");
    }

    // ═══════════ SessionPolicy ═══════════

    static void sessionPolicy() {
        eq(SessionPolicy.admit(false, 0), SessionPolicy.ACCEPT, "没有旧会话 → 接");
        eq(SessionPolicy.admit(false, 99_999), SessionPolicy.ACCEPT, "旧会话已结束 → 接");
        eq(SessionPolicy.admit(true, 0), SessionPolicy.BUSY, "旧会话刚说过话 → 忙");
        eq(SessionPolicy.admit(true, 4999), SessionPolicy.BUSY, "沉默 4999ms → 忙");
        eq(SessionPolicy.admit(true, 5000), SessionPolicy.KICK_OLD, "沉默 5000ms → 顶掉");
        check(SessionPolicy.OLD_SESSION_WAIT_MS < dev.douhua.Proto.HELLO_TIMEOUT_MS,
                "等旧会话收尾的时间短于 App 等 hello 的时间");
        check(dev.douhua.Proto.PING_INTERVAL_MS < SessionPolicy.KICK_SILENT_MS
                && SessionPolicy.KICK_SILENT_MS < dev.douhua.Proto.IDLE_TIMEOUT_MS, "保活间隔 < 顶掉的沉默 < 读超时");
        eq(SessionPolicy.OLD_SESSION_WAIT_MS, 2000L, "旧会话 2 秒收不完尾（还占着触摸屏就退出服务）");
    }

    // ═══════════ GrabCondition ═══════════

    /** 只有心跳和布局算心跳；保活（App 主线程卡住时连接线程照发）不算，3 秒后照样释放、原因是"没响应"。 */
    static void grabCondition() {
        GrabCondition c = new GrabCondition();
        long t0 = 100_000;   // 像 uptimeMillis：远大于超时
        check(!c.fresh(t0), "一次心跳都没收到：不新鲜");
        c.onMessage(Proto.CMD_HEARTBEAT, t0);
        check(c.fresh(t0 + 2999), "心跳后 2999ms：新鲜");
        check(!c.fresh(t0 + 3000), "心跳后 3000ms：超时");
        for (long t = t0 + 500; t <= t0 + 3000; t += 500) {
            c.onMessage(Proto.CMD_PING, t);   // 心跳停了，保活每 0.5 秒照来
            c.onMessage(Proto.CMD_CONFIG, t);
            c.onMessage(Proto.CMD_HARD_KEYS, t);
        }
        check(!c.fresh(t0 + 3000), "只收保活 / 别的命令：3 秒后不新鲜");
        eq(c.releaseReason(true, true, true, t0 + 3000), Proto.GRAB_UNRESPONSIVE, "只收保活：释放原因是没响应");
        eq(c.releaseReason(false, true, true, t0 + 3000), Proto.GRAB_DISCONNECTED, "断开优先于没响应");
        c.onMessage(Proto.CMD_HEARTBEAT, t0 + 3100);
        check(c.fresh(t0 + 3100), "又收到心跳：新鲜");
        eq(c.releaseReason(true, false, true, t0 + 3100), Proto.GRAB_PAUSED, "心跳新鲜、App 暂停：原因是暂停");
        eq(c.releaseReason(true, true, false, t0 + 3100), Proto.GRAB_ROTATING, "换了方向 / 尺寸：原因是转屏");
        eq(c.releaseReason(true, true, true, t0 + 3100), Proto.GRAB_RELEASED, "都正常：其它");
        c.onMessage(Proto.CMD_LAYOUT, t0 + 6000);
        check(c.fresh(t0 + 8999), "布局也算心跳");
    }

    // ═══════════ RotationLock ═══════════

    /** 只记命令的假 shell：读方向时回 original。 */
    static final class FakeWm implements RotationLock.Cmd {
        final List<String> cmds = new ArrayList<>();
        String original = "free";

        @Override
        public String run(String... cmd) {
            String c = String.join(" ", cmd);
            cmds.add(c);
            return cmd.length == 4 ? original + "\n" : "";
        }
    }

    static void rotationLock() {
        FakeWm wm = new FakeWm();
        List<String> log = new ArrayList<>();
        RotationLock r = new RotationLock(wm, log::add);
        r.lock(0, 1);
        eq(wm.cmds, list("wm user-rotation -d 0", "wm user-rotation -d 0 lock 1"), "第一次锁先读原值再锁");
        wm.cmds.clear();
        r.lock(0, 3);
        eq(wm.cmds, list("wm user-rotation -d 0 lock 3"), "再锁同一块屏不重读原值");
        wm.cmds.clear();
        r.restore();
        eq(wm.cmds, list("wm user-rotation -d 0 free"), "恢复成原来的 free");
        wm.cmds.clear();
        r.restore();
        eq(wm.cmds, list(), "连调两次 restore，第二次什么都不发");

        wm.original = "lock 2";
        r.lock(0, 1);
        wm.cmds.clear();
        r.restore();
        eq(wm.cmds, list("wm user-rotation -d 0 lock 2"), "原值是 lock 2 就恢复成 lock 2");

        wm.original = "看不懂";
        r.lock(0, 1);
        wm.cmds.clear();
        r.restore();
        eq(wm.cmds, list(), "原值读不懂时不恢复");
        check(log.get(log.size() - 1).contains("不恢复"), "读不懂时记一行");

        wm.original = "free";
        r.lock(0, 1);
        wm.original = "lock 0";
        wm.cmds.clear();
        r.lock(5, 2);
        eq(wm.cmds, list("wm user-rotation -d 0 free", "wm user-rotation -d 5", "wm user-rotation -d 5 lock 2"),
                "换一块屏时先恢复上一块");
        wm.cmds.clear();
        r.lock(5, 9);
        eq(wm.cmds, list(), "方向不在 0–3 不理");

        RotationLock broken = new RotationLock(cmd -> {
            throw new IOException("超过 5 秒没结束");
        }, log::add);
        broken.lock(0, 1);
        broken.restore();
        check(log.get(log.size() - 1).contains("不恢复"), "读原值失败时不恢复、也不抛异常");
    }

    // ═══════════ Shell ═══════════

    static void shellRun() throws Exception {
        eq(Shell.run(1000, "echo", "hi"), "hi\n", "读回输出");
        eq(Shell.run(1000, "sh", "-c", "echo oops; exit 3"), "oops\n", "退出码不是 0 也照样返回输出");
        Shell.Result split = Shell.runSplit(1000, "sh", "-c", "echo out; echo err >&2; exit 3");
        check(split.exit == 3 && split.out.equals("out\n") && split.err.equals("err\n"),
                "runSplit：输出、错误分开，带退出码（实际 " + split.exit + " / " + split.out + " / " + split.err + "）");
        String marker = "4.3217";   // 认得出是哪个进程的 sleep 参数
        long t0 = System.nanoTime();
        String msg = null;
        try {
            Shell.run(300, "sleep", marker);
        } catch (IOException e) {
            msg = e.getMessage();
        }
        long took = (System.nanoTime() - t0) / 1_000_000;
        check(msg != null && took < 1000, "超时 1 秒内抛 IOException（实际 " + took + "ms）");
        check(msg != null && msg.contains("0.3 秒"), "消息里带超时秒数（实际 " + msg + "）");
        java.io.File proc = new java.io.File("/proc");
        if (proc.isDirectory()) {
            boolean alive = false;
            for (java.io.File d : proc.listFiles()) {
                if (!d.getName().matches("\\d+")) continue;
                try {
                    byte[] raw = java.nio.file.Files.readAllBytes(new java.io.File(d, "cmdline").toPath());
                    String[] argv = new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\u0000");
                    if (argv.length >= 2 && argv[0].endsWith("sleep") && argv[1].equals(marker)) alive = true;
                } catch (IOException ignored) {
                    // 进程刚好结束
                }
            }
            check(!alive, "超时的进程已经被结束掉");
        }
    }

    // ═══════════ KeyDevices ═══════════

    /**
     * 照 getevent -i 的格式拼一块设备。events 每一项是 events 段里的一行（不带缩进）：以 "+" 开头的是续行
     * （getevent 用 16 个空格缩进），别的是类型行（4 个空格缩进）。
     */
    static String dev(int n, String bus, String vendor, String name, String... events) {
        StringBuilder b = new StringBuilder();
        b.append("add device ").append(n).append(": /dev/input/event").append(n).append('\n');
        b.append("  bus:      ").append(bus).append('\n');
        b.append("  vendor    ").append(vendor).append('\n');
        b.append("  product   0001\n");
        b.append("  version   0100\n");
        b.append("  name:     \"").append(name).append("\"\n");
        b.append("  location: \"").append(name).append("/input0\"\n");
        b.append("  id:       \"\"\n");
        b.append("  version:  1.0.1\n");
        b.append("  events:\n");
        for (String e : events) {
            if (e.startsWith("+")) b.append("                ").append(e.substring(1)).append('\n');
            else b.append("    ").append(e).append('\n');
        }
        b.append("  input props:\n");
        b.append("    <none>\n");
        return b.toString();
    }

    static final String KEY_VOL = "KEY (0001): 0072  0073 ";          // 音量下(114) + 音量上(115)
    static final String KEY_VOLDOWN_POWER = "KEY (0001): 0072  0074 "; // 音量下 + 电源(116)
    static final String KEY_VOLUP = "KEY (0001): 0073 ";               // 只有音量上

    static List<KeyDevices.Device> parse(String out) {
        return KeyDevices.parse(out, "");
    }

    static void keyDevicesParse() {
        String out = dev(2, "0019", "0001", "gpio_keys", KEY_VOL)
                + dev(0, "0000", "0000", "qpnp_pon", KEY_VOLDOWN_POWER);
        List<KeyDevices.Device> ds = parse(out);
        eq(ds.size(), 2, "解析出两块设备");
        KeyDevices.Device gpio = ds.get(0);
        check(gpio.volUp && gpio.volDown && !gpio.power, "gpio_keys：音量上 + 音量下，没有电源");
        eq(gpio.path, "/dev/input/event2", "gpio_keys：路径取自 add device 行");
        eq(gpio.name, "gpio_keys", "名字去掉引号");
        check(gpio.bus == 0x19 && gpio.vendor == 1 && gpio.product == 1, "bus / vendor（没有冒号的行）/ product 按十六进制");
        check(gpio.problem == null, "gpio_keys：解析完整");
        check(gpio.whyNotGrabbable() == null, "gpio_keys：能单独截");
        check(!gpio.watchable(), "gpio_keys：没有电源键，不用旁观");
        KeyDevices.Device pon = ds.get(1);
        check(pon.volDown && pon.power && !pon.volUp, "qpnp_pon：音量下 + 电源");
        String why = pon.whyNotGrabbable();
        check(why != null && why.contains("电源键"), "带电源键的设备绝不截，原因写明（实际 " + why + "）");
        check(pon.watchable(), "带电源键的机身设备：可以旁观");
        eq(parse("").size(), 0, "空输出：没有设备");
        eq(KeyDevices.parse("", null).size(), 0, "没有标准错误也行");
    }

    static void keyDevicesExcludes() {
        String out = dev(1, "0003", "05ac", "usb-kbd", KEY_VOL)                          // USB 总线
                + dev(2, "0005", "004c", "bt-kbd", KEY_VOL)                               // 蓝牙总线
                + dev(3, "0019", "1209", "douhua-vk", KEY_VOL)                            // 豆花虚拟键盘（厂商号）
                + dev(4, "0018", "0000", "touchscreen", "KEY (0001): 0073  014a ",
                        "ABS (0003): 0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0",
                        "+0036  : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0")  // 带 ABS
                + dev(5, "0019", "0000", "hall", KEY_VOLUP, "SW  (0005): 0000 ")        // 合盖霍尔（SW；getevent 把类型名补到三个字符，两个空格）
                + dev(6, "0019", "0000", "side-key", "KEY (0001): 0073  00ac ")          // 带 KEY_HOMEPAGE(172)
                + dev(7, "0006", "0000", "uinput-vol", KEY_VOL)                           // 别的工具 uinput 造的（虚拟总线）
                + dev(9, "0019", "0000", "only-power", "KEY (0001): 0074 ")               // 只有电源键
                + dev(10, "0019", "0000", "vibrator", KEY_VOL, "FF  (0015): 0050 ")       // 带力反馈（FF 也补到三个字符）
                + dev(11, "0019", "0000", "odd-type", KEY_VOL, "??? (0013): 0000 ");      // 合成样本：getevent 的 "???" 标签，只测解析宽容
        // 没有带 REP 的样本：getevent 的 EVIOCGBIT 查不到 REP / PWR / FF_STATUS，真机输出里不会出现这几种类型行
        List<KeyDevices.Device> ds = parse(out);
        eq(ds.size(), 10, "十块设备");
        for (KeyDevices.Device d : ds) check(d.whyNotGrabbable() != null, d.name + "：不能单独截");
        check(find(ds, "usb-kbd").whyNotGrabbable().contains("0x3"), "USB：总线原因");
        check(find(ds, "bt-kbd").whyNotGrabbable().contains("0x5"), "蓝牙：总线原因");
        String virt = find(ds, "uinput-vol").whyNotGrabbable();
        check(virt.contains("0x6"), "虚拟总线：总线原因（只带音量键也不截；实际 " + virt + "）");
        check(find(ds, "douhua-vk").whyNotGrabbable().contains("豆花"), "豆花虚拟键盘：厂商号原因");
        KeyDevices.Device touch = find(ds, "touchscreen");
        check(touch.whyNotGrabbable().contains("ABS"), "触摸屏：事件类型（ABS）原因");
        check(touch.events.get(KeyDevices.EV_ABS).contains(0x36), "ABS 的续行（带 : value …）也解析出代码");
        check(touch.problem == null, "触摸屏的 ABS 行不算解析出错");
        for (String n : new String[]{"hall", "vibrator", "odd-type"}) {
            check(find(ds, n).problem == null, n + "：类型行解析完整（实际 " + find(ds, n).problem + "）");
        }
        check(find(ds, "hall").whyNotGrabbable().contains("事件类型 SW"), "合盖霍尔：事件类型（SW）原因（实际 " + find(ds, "hall").whyNotGrabbable() + "）");
        check(find(ds, "vibrator").whyNotGrabbable().contains("事件类型 FF"), "力反馈：事件类型（FF）原因");
        check(find(ds, "odd-type").whyNotGrabbable().contains("0x13"), "没有名字的类型：按十六进制写原因");
        check(find(ds, "side-key").whyNotGrabbable().contains("172"), "带 HOMEPAGE：原因里有多出来的键码 172");
        check(find(ds, "only-power").whyNotGrabbable().contains("电源键"), "只有电源键：也不截");
        // MSC 是允许的（很多 gpio-keys 报 MSC_SCAN）
        KeyDevices.Device msc = parse(dev(1, "0019", "0001", "gpio-msc", KEY_VOL, "MSC (0004): 0004 ")).get(0);
        check(msc.whyNotGrabbable() == null, "带 MSC 的音量键设备：能截");
    }

    /** 真机上会碰到的格式：按着的 '*'、一行放不下的续行、打不开的设备、设备块之前的杂行、认不出的行、缺段、HID 描述符。 */
    static void keyDevicesOddFormat() {
        String out = "some junk before the first device\n"
                + dev(2, "0019", "0001", "gpio_keys", "KEY (0001): 0072* 0073 ")             // 音量下正按着
                + dev(3, "0019", "0000", "many-keys", "KEY (0001): 0001  0002  0003  0004  0005  0006  0007  0008 ",
                        "+0009  0072 ")                                                          // 续行里还有音量下
                + dev(4, "0019", "0000", "weird", KEY_VOL, "this is not a type line")         // 认不出的行
                + dev(5, "0019", "0000", "bare-type", KEY_VOL, "001f: 0000 ")                 // 合成样本：只测没有名字的类型行能解析
                + "add device 6: /dev/input/event6\n"
                + "  bus:      0019\r\n"                                                       // CRLF
                + "  vendor    0000\n"
                + "  name:     \"no-events\"\n"                                                 // 没有 events 段
                + dev(7, "0019", "0001", "gpio-hid", KEY_VOLUP)
                + "  HID descriptor: 0019:0001:0001.0001\n\n    05 01 09 02\n\n";               // input props 之后的都不看
        String err = "could not open /dev/input/event9, Permission denied\n"
                + "could not get driver version for /dev/input/mice, Not a typewriter\n";
        List<KeyDevices.Device> ds = KeyDevices.parse(out, err);
        eq(ds.size(), 7, "六块设备 + 一块打不开的（杂行、别的错误跳过）");
        KeyDevices.Device gpio = find(ds, "gpio_keys");
        check(gpio.volUp && gpio.volDown && gpio.problem == null, "带 '*' 的代码照常解析");
        check(gpio.whyNotGrabbable() == null, "正按着的键不影响能不能截");
        KeyDevices.Device many = find(ds, "many-keys");
        check(many.volDown && many.extraKeyCount == 9, "续行里的代码归同一个类型（实际别的键 " + many.extraKeyCount + " 个）");
        check(many.whyNotGrabbable().contains("别的键"), "别的键多：不截");
        KeyDevices.Device weird = find(ds, "weird");
        check(weird.problem != null && weird.whyNotGrabbable().contains("没认全"), "events 段里认不出的行：当不能截");
        KeyDevices.Device bare = find(ds, "bare-type");
        check(bare.problem == null && bare.events.containsKey(0x1f), "没有名字的类型按十六进制认");
        check(bare.whyNotGrabbable().contains("0x1f"), "多出的类型：不截");
        KeyDevices.Device noEv = find(ds, "no-events");
        check(noEv.problem != null && noEv.whyNotGrabbable() != null, "没有 events 段：当不能截");
        KeyDevices.Device hid = find(ds, "gpio-hid");
        check(hid.volUp && hid.problem == null && hid.whyNotGrabbable() == null, "HID 描述符那几行不影响解析");
        KeyDevices.Device bad = find(ds, "/dev/input/event9");
        check(bad.openError != null && bad.openError.contains("Permission denied"), "打不开的设备：记下原因");
        check(bad.whyNotGrabbable().contains("打不开") && !bad.watchable(), "打不开的设备：不截、不旁观");
        check(bad.describe().contains("打不开"), "Server keys 里看得到打不开的设备");
        check(gpio.describe().contains("/dev/input/event2") && gpio.describe().contains("能截"), "describe：路径、能截");
    }

    static void keyDevicesPlan() {
        // gpio（音量上 + 音量下）+ 单独的电源键设备：配了音量就截 gpio，旁观电源键那块
        List<KeyDevices.Device> gpio = parse(dev(2, "0019", "0001", "gpio_keys", KEY_VOL)
                + dev(0, "0000", "0000", "pwr", "KEY (0001): 0074 "));
        KeyDevices.Plan p = KeyDevices.plan(gpio, new KeyDevices.Config(true, true));
        eq(p.grab.size(), 1, "gpio：音量都配了 → 截这块");
        check(p.watch.size() == 1 && p.watch.get(0).name.equals("pwr"), "旁观电源键那块");
        eq(p.grabbedBits, Proto.HARD_BIT_VOL_UP | Proto.HARD_BIT_VOL_DOWN, "音量上 + 音量下都归豆花");
        eq(p.unavailableBits, 0, "没有截不了的");
        KeyDevices.Plan one = KeyDevices.plan(gpio, new KeyDevices.Config(true, false));
        eq(one.grabbedBits, Proto.HARD_BIT_VOL_UP, "只配音量上：只有它归豆花（音量下由 gate 透传）");

        // 有的手机：音量上单独一块，音量下和电源键在一起 → 只截音量上，音量下报"和电源键同一设备"，旁观 pmic
        List<KeyDevices.Device> split = parse(dev(2, "0019", "0001", "gpio_keys", KEY_VOLUP)
                + dev(0, "0000", "0000", "pmic", KEY_VOLDOWN_POWER));
        KeyDevices.Plan s = KeyDevices.plan(split, new KeyDevices.Config(true, true));
        check(s.grab.size() == 1 && s.grab.get(0).name.equals("gpio_keys"), "只截音量上那块，绝不截带电源键的 pmic");
        eq(s.grabbedBits, Proto.HARD_BIT_VOL_UP, "音量上归豆花");
        eq(s.unavailableBits, Proto.HARD_BIT_VOL_DOWN, "音量下截不了");
        eq(s.reason, Proto.HARD_SHARED, "原因：和别的输入在同一设备");
        check(s.detail.contains("音量下") && s.detail.contains("电源键"), "细节：音量下和电源键在同一个设备上（实际 " + s.detail + "）");
        check(s.watch.size() == 1 && s.watch.get(0).name.equals("pmic"), "pmic 只旁观（认截屏组合键）");

        // 只有 pmic：音量下截不了、音量上哪儿都没有 → 原因不同 → HARD_MIXED，细节两条都有
        List<KeyDevices.Device> pmic = parse(dev(0, "0000", "0000", "pmic", KEY_VOLDOWN_POWER));
        KeyDevices.Plan mixed = KeyDevices.plan(pmic, new KeyDevices.Config(true, true));
        eq(mixed.grab.size(), 0, "不独占带电源键的设备");
        eq(mixed.watch.size(), 0, "什么都没截：也不旁观");
        eq(mixed.unavailableBits, Proto.HARD_BIT_VOL_UP | Proto.HARD_BIT_VOL_DOWN, "音量上 + 音量下都截不了");
        eq(mixed.reason, Proto.HARD_MIXED, "原因不同 → 混合");
        check(mixed.detail.contains("没找到") && mixed.detail.contains("电源键"), "细节里两个键的原因都有（实际 " + mixed.detail + "）");

        // 配了的键哪儿都没有 → HARD_NO_DEVICE；有打不开的设备时细节里提一句
        List<KeyDevices.Device> none = KeyDevices.parse(dev(0, "0000", "0000", "pmic", KEY_VOLDOWN_POWER),
                "could not open /dev/input/event3, Permission denied\n");
        KeyDevices.Plan nd = KeyDevices.plan(none, new KeyDevices.Config(true, false));
        eq(nd.unavailableBits, Proto.HARD_BIT_VOL_UP, "音量上截不了");
        eq(nd.reason, Proto.HARD_NO_DEVICE, "原因：没找到带这个键的设备");
        check(nd.detail.contains("1 个设备 getevent 打不开"), "细节里提到打不开的设备（实际 " + nd.detail + "）");

        KeyDevices.Plan off = KeyDevices.plan(split, new KeyDevices.Config(false, false));
        check(off.grab.isEmpty() && off.watch.isEmpty() && off.unavailableBits == 0, "什么都没配：不截、不旁观、不报");

        // 看不到电源键在哪（电源键那块 getevent 打不开）：截了电源键 + 音量键的系统组合键会失灵 → 什么都不截，报 HARD_SHARED
        List<KeyDevices.Device> blind = KeyDevices.parse(dev(2, "0019", "0001", "gpio_keys", "KEY (0001): 0072 "),
                "could not open /dev/input/event0, Permission denied\n");
        KeyDevices.Plan b = KeyDevices.plan(blind, new KeyDevices.Config(false, true));
        check(b.grab.isEmpty() && b.watch.isEmpty() && b.grabbedBits == 0, "找不到电源键设备：不截音量下");
        eq(b.unavailableBits, Proto.HARD_BIT_VOL_DOWN, "音量下截不了");
        eq(b.reason, Proto.HARD_SHARED, "原因：HARD_SHARED");
        check(b.detail.contains("电源键") && b.detail.contains("1 个设备 getevent 打不开"), "细节：找不到电源键、有打不开的设备（实际 " + b.detail + "）");

        // 电源键所在的设备带 ABS（不能旁观）：同样不截
        List<KeyDevices.Device> absPwr = parse(dev(2, "0019", "0001", "gpio_keys", "KEY (0001): 0072 ")
                + dev(3, "0018", "0000", "touch-pwr", "KEY (0001): 0074 ",
                        "ABS (0003): 0035  : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0"));
        KeyDevices.Plan ap = KeyDevices.plan(absPwr, new KeyDevices.Config(false, true));
        check(ap.grab.isEmpty() && ap.watch.isEmpty(), "电源键设备不能旁观：不截音量下");
        eq(ap.reason, Proto.HARD_SHARED, "电源键设备不能旁观：原因 HARD_SHARED");

        // 配了音量上、却哪儿都没有；音量下本来截得住但看不到电源键 → 两种原因 → HARD_MIXED
        KeyDevices.Plan mix2 = KeyDevices.plan(blind, new KeyDevices.Config(true, true));
        eq(mix2.reason, Proto.HARD_MIXED, "音量上没找到、音量下因为看不到电源键：混合");
        check(mix2.detail.contains("没找到") && mix2.detail.contains("系统组合键"), "细节两条都有（实际 " + mix2.detail + "）");
    }

    /** 要截点什么时，机身上带电源键的设备只旁观；外接的、触摸屏、豆花自己的、虚拟总线的、不带电源键的不旁观。 */
    static void keyDevicesWatch() {
        String out = dev(1, "0019", "0001", "gpio_keys", KEY_VOLUP)
                + dev(2, "0000", "0000", "pmic", KEY_VOLDOWN_POWER)
                + dev(3, "0019", "0000", "side-key", "KEY (0001): 0074  00ac ")
                + dev(4, "0019", "0000", "hall", "SW  (0005): 0000 ")
                + dev(5, "0018", "0000", "touch", "KEY (0001): 0074 ", "ABS (0003): 0035  : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0")
                + dev(6, "0003", "05ac", "usb-kbd", KEY_VOLDOWN_POWER)
                + dev(7, "0006", "1209", "douhua-vk", KEY_VOLDOWN_POWER)
                + dev(8, "0006", "0000", "uinput-pwr", KEY_VOLDOWN_POWER);
        List<KeyDevices.Device> ds = parse(out);
        KeyDevices.Plan p = KeyDevices.plan(ds, new KeyDevices.Config(true, false));
        check(p.grab.size() == 1 && p.grab.get(0).name.equals("gpio_keys"), "只截 gpio_keys");
        List<String> watched = new ArrayList<>();
        for (KeyDevices.Device d : p.watch) watched.add(d.name);
        eq(watched, list("pmic", "side-key"), "旁观：带电源键的 pmic、side-key；不旁观合盖霍尔、触摸屏、外接键盘、豆花虚拟键盘、虚拟总线设备");
    }

    /** 电脑上没有 getevent：read() 抛 IOException、原因写明（服务那边走 HARD_FAILED）。有 getevent 的机器上跳过。 */
    static void keyDevicesReadFails() {
        boolean has = false;
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (new java.io.File(dir, "getevent").canExecute()) has = true;
        }
        if (has) return;
        String msg = null;
        try {
            KeyDevices.read();
        } catch (IOException e) {
            msg = e.getMessage();
        }
        check(msg != null && msg.contains("getevent"), "没有 getevent：抛 IOException，原因里有 getevent（实际 " + msg + "）");
    }

    static KeyDevices.Device find(List<KeyDevices.Device> ds, String name) {
        for (KeyDevices.Device d : ds) if (d.name.equals(name)) return d;
        throw new IllegalStateException("没找到 " + name);
    }

    // ═══════════ HardKeyGate ═══════════

    static final int VOLDOWN = KeyDevices.KEY_VOLUMEDOWN, VOLUP = KeyDevices.KEY_VOLUMEUP, POWER = KeyDevices.KEY_POWER;
    static final int DOWN = 1, UP = 0, REPEAT = 2;

    static final class GateRec implements HardKeyGate.Output {
        final List<String> log = new ArrayList<>();

        @Override
        public void inject(int linux, boolean down, long dt, long ev) {
            log.add("i" + linux + (down ? "+" : "-") + "/" + dt + "/" + ev);
        }

        @Override
        public void toApp(int key, int event) {
            log.add("a" + key + ":" + event);
        }

        @Override
        public void handBack(String why) {
            log.add("HB");
        }
    }

    /** 建一个已经独占好的 gate（刚建出来时键在系统手里，KeyReader 独占成功后才调 grabbed）。 */
    static HardKeyGate gate(GateRec r) {
        HardKeyGate g = new HardKeyGate(r);
        g.grabbed();
        return g;
    }

    /** 没配的键：按下当场注入按下（原始 downTime），松开注入松开；连发忽略；没见过按下的松开不注入。 */
    static void gatePassThrough() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, true, false);   // 只音量下配了（音量上成了被独占设备上没配的键）
        g.key(VOLUP, DOWN, 100);
        g.key(VOLUP, REPEAT, 150);   // 内核连发：忽略
        g.key(VOLUP, UP, 300);
        eq(r.log, list("i115+/100/100", "i115-/100/300"), "透传：按下 / 松开注入，downTime 同是原始时间");
        g.key(VOLUP, DOWN, 400);
        g.key(VOLUP, DOWN, 420);     // 按着时又来一个按下：不算
        g.key(VOLUP, UP, 500);
        eq(r.log.subList(2, r.log.size()), list("i115+/400/400", "i115-/400/500"), "重复的按下不多注入");
    }

    /**
     * 独占着却收到没见过按下的松开（键在打开设备之前就按着，系统多半看到了按下）：补一对按下 + 松开
     * （接过系统那一路的连发再松开，最坏多调一格）。
     */
    static void gateStrayUp() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(true, false, false, false);
        g.key(VOLDOWN, UP, 400);
        eq(r.log, list("i114+/400/400", "i114-/400/400"), "音量下（透传键）：补按下 + 松开");
        g.key(VOLUP, UP, 500);
        eq(r.log.subList(2, r.log.size()), list("i115+/500/500", "i115-/500/500"), "音量上（配了动作）：同样补，不发给 App");
        for (String x : r.log) check(!x.startsWith("a"), "没见过按下的松开不算点一下");
    }

    /** 刚建出来时键在系统手里：按键不注入、不发给 App；所有键松开就能独占（第一次不用等冷却），按过键要空闲满冷却。 */
    static void gateStartsWithSystem() {
        GateRec r = new GateRec();
        HardKeyGate g = new HardKeyGate(r);
        g.config(true, false, false, false);
        check(g.systemOwns(), "刚建出来：键在系统手里");
        check(g.readyToGrab(0), "没按过键：马上能独占");
        g.system(VOLUP, DOWN, 100);
        check(!g.readyToGrab(5000), "键还按着：不独占");
        g.system(VOLUP, UP, 200);
        eq(r.log, list(), "系统手里的键：不注入、不发给 App");
        check(!g.readyToGrab(2199), "松开不到冷却：不独占");
        check(g.readyToGrab(2200), "空闲满冷却：可以独占");
        g.grabbed();
        check(!g.systemOwns(), "独占后归本类判断");
        g.key(VOLUP, DOWN, 3000);
        eq(r.log, list("a0:0"), "独占后：音量上点一下发给 App");
    }

    /** 配了动作的音量键：点一下 / 开始按住 / 结束按住发给 App，顺序对。 */
    static void gateVolumeConfigured() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(true, true, false, false);   // 音量上 点一下 + 按住
        g.key(VOLUP, DOWN, 0);
        g.key(VOLUP, UP, 150);
        eq(r.log, list("a0:0"), "没按够 → 点一下（HARD_VOL_UP, TAP）");
        g.key(VOLUP, DOWN, 200);
        g.pump(500);
        g.key(VOLUP, UP, 800);
        eq(r.log, list("a0:0", "a0:1", "a0:2"), "按够 → 开始、结束按住");
    }

    /** 设了动作的键整个归豆花：只配按住时短按一下什么都不做（不注入）；只配点一下时按下当场就算、按住也不调音量。 */
    static void gateVolumeHalf() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, true, false, false);   // 音量上 只按住
        g.key(VOLUP, DOWN, 0);
        g.key(VOLUP, UP, 100);
        eq(r.log, list(), "只配按住、短按：什么都不做，不调音量");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(true, false, false, false);  // 音量上 只点一下
        g2.key(VOLUP, DOWN, 0);
        eq(r2.log, list("a0:0"), "只配点一下：按下当场就算");
        g2.pump(1000);
        g2.key(VOLUP, UP, 1500);
        eq(r2.log, list("a0:0"), "按住也不调音量（不注入）");
    }

    /** 独占的描述符上读到电源键（不该有：带电源键的设备不截）：什么都不做，不注入、不发给 App。 */
    static void gateIgnoresPower() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(true, true, true, true);
        g.key(POWER, DOWN, 0);
        g.pump(1000);
        g.key(POWER, UP, 1200);
        g.system(POWER, DOWN, 1300);
        g.system(POWER, UP, 1400);
        eq(r.log, list(), "电源键不进 gate 的判断，也从不注入");
    }

    /**
     * 电源键（旁观的设备上）和截住的音量键一起按（截屏）：按原始时间补发吃掉的音量键按下 + handBack；
     * 音量在按住中先补 HOLD_END；电源键本身系统自己收，从不注入。
     */
    static void gateCombo() {
        // 先按音量下（配了按住，吞进 PENDING），再按电源
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, true);
        g.key(VOLDOWN, DOWN, 0);
        g.observe(POWER, DOWN, 100);
        eq(r.log, list("i114+/0/0", "HB"), "组合键：按原始时间补发音量下的按下，再交还");
        g.system(VOLDOWN, UP, 400);   // 交还后真松开：给补发的按下配松开
        g.observe(POWER, UP, 450);
        eq(r.log.subList(2, r.log.size()), list("i114-/0/400"), "交还后：补发的按下配上松开，电源键不注入");

        // 先按电源、后按音量下（只配了点一下）：不当点一下，补发音量下按下、交还
        GateRec r1 = new GateRec();
        HardKeyGate g1 = gate(r1);
        g1.config(false, false, true, false);
        g1.observe(POWER, DOWN, 0);
        g1.key(VOLDOWN, DOWN, 60);
        eq(r1.log, list("i114+/60/60", "HB"), "电源先按着 + 音量下：不当点一下，补发、交还");
        g1.observe(POWER, REPEAT, 70);
        check(!g1.readyToGrab(10000), "电源还按着：不独占");

        // 两个音量键都吞着（只配按住），再按电源：按按下时间排序补发（音量上在前，虽然下标在后）
        GateRec rs = new GateRec();
        HardKeyGate gs = gate(rs);
        gs.config(false, true, false, true);
        gs.key(VOLUP, DOWN, 0);
        gs.key(VOLDOWN, DOWN, 50);
        gs.observe(POWER, DOWN, 100);
        eq(rs.log, list("i115+/0/0", "i114+/50/50", "HB"), "组合键：按原始按下时间排序补发");
        gs.system(VOLDOWN, UP, 300);
        gs.system(VOLUP, UP, 400);
        eq(rs.log.subList(3, rs.log.size()), list("i114-/50/300", "i115-/0/400"), "排序补发后：各配一个松开");

        // 透传的音量下先按（按下已注入过），再按电源：不重复补发，只交还
        GateRec ro = new GateRec();
        HardKeyGate go = gate(ro);
        go.config(true, false, false, false);   // 只配音量上；音量下透传
        go.key(VOLDOWN, DOWN, 0);
        go.observe(POWER, DOWN, 80);
        eq(ro.log, list("i114+/0/0", "HB"), "组合键：已透传的音量下不重复补发按下");
        go.system(VOLDOWN, UP, 300);
        eq(ro.log.subList(2, ro.log.size()), list("i114-/0/300"), "交还后：每个按下只配一个松开");

        // 音量下在按住中按电源：先补 HOLD_END 再补发
        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, true);
        g2.key(VOLDOWN, DOWN, 0);
        g2.pump(300);                  // 音量下进入按住
        g2.observe(POWER, DOWN, 350);
        eq(r2.log, list("a1:1", "a1:2", "i114+/0/0", "HB"), "组合键：按住中补 HOLD_END 再补发");

        // 电源按一下松开后再按音量：不是组合键，照常点一下
        GateRec r3 = new GateRec();
        HardKeyGate g3 = gate(r3);
        g3.config(true, false, false, false);
        g3.observe(POWER, DOWN, 0);
        g3.observe(POWER, UP, 100);
        g3.key(VOLUP, DOWN, 200);
        g3.key(VOLUP, UP, 250);
        eq(r3.log, list("a0:0"), "电源松开后再按音量：不是组合键");
    }

    /** 旁观设备上的音量键（系统自己收）和截住的键无关：不认组合键、不挡独占。 */
    static void gateObserveVolumeIgnored() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(true, false, false, false);
        g.observe(VOLDOWN, DOWN, 0);
        g.key(VOLUP, DOWN, 50);
        g.key(VOLUP, UP, 80);
        g.observe(VOLDOWN, UP, 100);
        eq(r.log, list("a0:0"), "旁观的音量下 + 截住的音量上：不是组合键，照常点一下");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = new HardKeyGate(r2);
        g2.config(true, false, false, false);
        g2.observe(VOLDOWN, DOWN, 0);
        check(g2.readyToGrab(0), "旁观的音量键按着：不挡独占");
    }

    /**
     * 键在系统手里时从独占的描述符上读到的（系统没看到）：独占撞上竞态马上放开时，系统看到了按下、松开却只到了 grabFd
     * → 替系统补一对；交还那一刻还没读完的按下系统不知道，它的真松开（plain）不用补。
     */
    static void gateUnseen() {
        GateRec r = new GateRec();
        HardKeyGate g = new HardKeyGate(r);
        g.config(true, false, false, false);
        g.system(VOLDOWN, DOWN, 100);  // 竞态：独占前按下，系统看到了
        g.key(VOLDOWN, UP, 150);       // 松开只到了 grabFd
        eq(r.log, list("i114+/150/150", "i114-/150/150"), "竞态里的松开：替系统补一对");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, true);
        g2.key(VOLDOWN, DOWN, 0);
        g2.observe(POWER, DOWN, 100);  // 组合键 → 交还
        g2.key(VOLUP, DOWN, 110);      // 交还那一刻 grabFd 上还没读完的：系统没看到
        g2.system(VOLUP, UP, 500);     // 真松开从 plain 来：系统会丢掉没头的松开，这里也不补
        g2.system(VOLDOWN, UP, 600);
        g2.observe(POWER, UP, 650);
        eq(r2.log, list("i114+/0/0", "HB", "i114-/0/600"), "交还时系统没看到的按下：松开不补");
        check(!g2.readyToGrab(2649), "冷却没满：不独占");
        check(g2.readyToGrab(2650), "都松开、冷却满（从电源键松开算）：重新独占");
    }

    /** 交还系统后，键都空闲满 2000ms 才允许重新独占。 */
    static void gateCooldown() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, true, false);
        g.observe(POWER, DOWN, 0);
        g.key(VOLDOWN, DOWN, 50);      // 组合键 → handBack
        g.system(VOLDOWN, UP, 300);
        check(!g.readyToGrab(2400), "电源还按着：不独占");
        g.observe(POWER, UP, 500);
        check(!g.readyToGrab(2499), "松开后不到 2000ms：不许重新独占");
        check(g.readyToGrab(2500), "满 2000ms：允许重新独占");
    }

    /** releaseAll：透传中按着的音量键补松开、音量按住中发 HOLD_END；组合键补发的按下也补松开。 */
    static void gateReleaseAll() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, true, false, false);   // 音量上 只按住
        g.key(VOLUP, DOWN, 0);        // PENDING
        g.key(VOLDOWN, DOWN, 50);     // 没配 → 透传按下
        g.pump(300);                  // 音量上进入按住
        g.releaseAll(400);
        eq(r.log, list("i114+/50/50", "a0:1", "a0:2", "i114-/50/400"),
                "releaseAll：透传的音量下补松开、音量上按住发 HOLD_END");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, true);
        g2.key(VOLDOWN, DOWN, 0);
        g2.observe(POWER, DOWN, 100);  // 组合键 → 补发音量下按下 + handBack
        g2.releaseAll(500);
        eq(r2.log, list("i114+/0/0", "HB", "i114-/0/500"), "releaseAll：组合键补发的音量下补松开");
        check(g2.systemOwns(), "releaseAll 之后键算在系统手里");
    }

    // ═══════════ 工具 ═══════════

    static int count(List<String> l, String s) {
        int n = 0;
        for (String x : l) if (x.equals(s)) n++;
        return n;
    }

    static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
    }

    static void near(float actual, float expected, String what) {
        check(Math.abs(actual - expected) < 1f, what + "（期望约 " + expected + "，实际 " + actual + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }

    static void eq(Object actual, Object expected, String what) {
        check(expected.equals(actual), what + "（期望 " + expected + "，实际 " + actual + "）");
    }
}
