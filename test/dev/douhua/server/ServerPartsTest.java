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
        gateStartsWithSystem();
        gatePassThrough();
        gateStrayUp();
        gateVolumeConfigured();
        gateVolumeHalf();
        gatePowerShort();
        gatePowerLong();
        gatePowerDouble();
        gatePowerMulti();
        gateCombo();
        gateComboWatched();
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

    /** KEY= 的字按 64 位一字、最高位的字在前：第二个字（倒数第二）= 位 64..127，音量 / 电源落在它的 50/51/52 位。 */
    static final String GPIO = "0 c000000000000 0";        // 音量下(114)+音量上(115)
    static final String POWER_ONLY = "0 10000000000000 0";  // 电源(116)
    static final String VOLDOWN_POWER = "0 14000000000000 0"; // 音量下(114)+电源(116)
    static final String VOLUP_HOME = "100000000000 8000000000000 0"; // 音量上(115)+KEY_HOMEPAGE(172)

    static String block(String bus, String vendor, String name, String ev, String key, String... extra) {
        StringBuilder b = new StringBuilder();
        b.append("I: Bus=").append(bus).append(" Vendor=").append(vendor).append(" Product=0000 Version=0000\n");
        b.append("N: Name=\"").append(name).append("\"\n");
        b.append("H: Handlers=").append(name).append(" ").append("event").append(name.hashCode() & 7).append(" \n");
        if (ev != null) b.append("B: EV=").append(ev).append("\n");
        if (key != null) b.append("B: KEY=").append(key).append("\n");
        for (String e : extra) b.append(e).append("\n");
        return b.toString();
    }

    static void keyDevicesParse() {
        String proc = block("0019", "0001", "gpio-keys", "3", GPIO)
                + "\n\n"   // 块之间多个空行
                + block("0019", "0000", "pmic_key", "3", VOLDOWN_POWER);
        List<KeyDevices.Device> ds = KeyDevices.parse(proc);
        eq(ds.size(), 2, "解析出两块设备");
        KeyDevices.Device gpio = ds.get(0);
        check(gpio.volUp && gpio.volDown && !gpio.power, "gpio-keys：音量上 + 音量下，没有电源");
        check(gpio.path != null && gpio.path.startsWith("/dev/input/event"), "gpio-keys：解析出 event 路径");
        check(gpio.whyNotGrabbable() == null, "gpio-keys：能单独截");
        KeyDevices.Device pmic = ds.get(1);
        check(pmic.volDown && pmic.power && !pmic.volUp, "pmic：音量下 + 电源");
        check(pmic.whyNotGrabbable() == null, "pmic：设备本身能截（配置另说）");
    }

    static void keyDevicesExcludes() {
        String proc = block("0003", "05ac", "usb-kbd", "3", "0 8000000000000 0")       // USB 总线
                + "\n" + block("0005", "004c", "bt-kbd", "3", "0 8000000000000 0")      // 蓝牙总线
                + "\n" + block("0019", "1209", "douhua-vk", "3", GPIO)                   // 豆花虚拟键盘（厂商号）
                + "\n" + block("0018", "0000", "touchscreen", "b", "0 8000000000000 0", "B: ABS=3")  // 带 ABS
                + "\n" + block("0019", "0000", "hall", "21", null, "B: SW=1")            // 合盖霍尔（EV_SW）
                + "\n" + block("0019", "0000", "side-key", "3", VOLUP_HOME)             // 带 KEY_HOMEPAGE(172)
                + "\n" + block("0006", "0000", "uinput-vol", "3", GPIO);                // 别的工具 uinput 造的音量键（虚拟总线）
        List<KeyDevices.Device> ds = KeyDevices.parse(proc);
        eq(ds.size(), 7, "七块设备");
        for (KeyDevices.Device d : ds) check(d.whyNotGrabbable() != null, d.name + "：不能单独截");
        check(find(ds, "usb-kbd").whyNotGrabbable().contains("0x3"), "USB：总线原因");
        check(find(ds, "bt-kbd").whyNotGrabbable().contains("0x5"), "蓝牙：总线原因");
        String virt = find(ds, "uinput-vol").whyNotGrabbable();
        check(virt != null && virt.contains("0x6"), "虚拟总线：总线原因（只带音量键也不截；实际 " + virt + "）");
        check(find(ds, "douhua-vk").whyNotGrabbable().contains("豆花"), "豆花虚拟键盘：厂商号原因");
        check(find(ds, "touchscreen").whyNotGrabbable().contains("事件类型"), "触摸屏：事件类型（ABS）原因");
        check(find(ds, "hall").whyNotGrabbable().contains("事件类型"), "合盖霍尔：事件类型（SW）原因");
        check(find(ds, "side-key").whyNotGrabbable().contains("172"), "带 HOMEPAGE：原因里有多出来的键码 172");
    }

    static void keyDevicesPlan() {
        // gpio（音量上 + 音量下）：配了音量就能截
        List<KeyDevices.Device> gpio = KeyDevices.parse(block("0019", "0001", "gpio-keys", "3", GPIO));
        KeyDevices.Plan p = KeyDevices.plan(gpio, new KeyDevices.Config(true, true, false));
        eq(p.grab.size(), 1, "gpio：音量都配了 → 截这块");
        eq(p.grabbedBits, Proto.HARD_BIT_VOL_UP | Proto.HARD_BIT_VOL_DOWN, "音量上 + 音量下都归豆花");
        eq(p.unavailableBits, 0, "没有截不了的");

        // pmic（音量下 + 电源）：电源没配 → 不截这块，音量下报"和电源键同一设备"
        List<KeyDevices.Device> pmic = KeyDevices.parse(block("0019", "0000", "pmic", "3", VOLDOWN_POWER));
        KeyDevices.Plan off = KeyDevices.plan(pmic, new KeyDevices.Config(false, true, false));
        eq(off.grab.size(), 0, "电源没配 → 不独占带电源的设备");
        eq(off.grabbedBits, 0, "一个键都没截住");
        eq(off.unavailableBits, Proto.HARD_BIT_VOL_DOWN, "音量下截不了");
        eq(off.reason, Proto.HARD_SHARED, "原因：和别的输入在同一设备");
        check(off.detail.contains("电源键"), "细节：和电源键在同一个设备上");

        // 电源配了 → 截这块，音量下 + 电源都归豆花
        KeyDevices.Plan on = KeyDevices.plan(pmic, new KeyDevices.Config(false, true, true));
        eq(on.grab.size(), 1, "电源配了 → 截带电源的设备");
        eq(on.grabbedBits, Proto.HARD_BIT_VOL_DOWN | Proto.HARD_BIT_POWER, "音量下 + 电源都归豆花");

        // 配了的键哪儿都没有 → HARD_NO_DEVICE
        KeyDevices.Plan none = KeyDevices.plan(pmic, new KeyDevices.Config(true, false, false));
        eq(none.unavailableBits, Proto.HARD_BIT_VOL_UP, "音量上截不了");
        eq(none.reason, Proto.HARD_NO_DEVICE, "原因：没找到带这个键的设备");

        // 两个键截不了、原因不同（音量上没有设备、音量下和没配的电源键同一设备）→ HARD_MIXED，细节两条都有
        KeyDevices.Plan mixed = KeyDevices.plan(pmic, new KeyDevices.Config(true, true, false));
        eq(mixed.unavailableBits, Proto.HARD_BIT_VOL_UP | Proto.HARD_BIT_VOL_DOWN, "音量上 + 音量下都截不了");
        eq(mixed.reason, Proto.HARD_MIXED, "原因不同 → 混合");
        check(mixed.detail.contains("没找到") && mixed.detail.contains("电源键"), "细节里两个键的原因都有（实际 " + mixed.detail + "）");
    }

    /** 真机格式：KEY= 省掉了前导的零字（字数不一样）、空的 B: 行、块之间夹着只有空格的行、没有 event 节点的块。 */
    static void keyDevicesOddFormat() {
        String proc = "I: Bus=0019 Vendor=0001 Product=0001 Version=0100\n"
                + "N: Name=\"gpio-keys\"\n"
                + "P: Phys=gpio-keys/input0\n"
                + "S: Sysfs=/devices/platform/gpio-keys/input/input2\n"
                + "U: Uniq=\n"
                + "H: Handlers=kbd event2 \n"
                + "B: PROP=0\n"
                + "B: EV=3\n"
                + "B: KEY=c000000000000 0\n"
                + "   \n\n"
                + "I: Bus=0019 Vendor=0000 Product=0000 Version=0000\n"
                + "N: Name=\"qpnp_pon\"\n"
                + "H: Handlers=kbd event0 \n"
                + "B: EV=3\n"
                + "B: KEY=14000000000000 0\n"
                + "\n"
                + "I: Bus=0019 Vendor=0000 Product=0000 Version=0000\n"
                + "N: Name=\"empty\"\n"
                + "H: Handlers=kbd event5 \n"
                + "B: EV=3\n"
                + "B: KEY=\n"
                + "\n"
                + "I: Bus=0019 Vendor=0000 Product=0000 Version=0000\n"
                + "N: Name=\"no-node\"\n"
                + "H: Handlers=kbd \n"
                + "B: EV=3\n"
                + "B: KEY=10000000000000 0\n";
        List<KeyDevices.Device> ds = KeyDevices.parse(proc);
        eq(ds.size(), 4, "四块设备（只有空格的行也算分隔）");
        KeyDevices.Device gpio = find(ds, "gpio-keys");
        check(gpio.volUp && gpio.volDown && !gpio.power, "两个字的 KEY=：音量上 + 音量下");
        eq(gpio.path, "/dev/input/event2", "event 路径");
        check(gpio.whyNotGrabbable() == null, "gpio-keys 能单独截");
        KeyDevices.Device pon = find(ds, "qpnp_pon");
        check(pon.power && pon.volDown && !pon.volUp, "qpnp_pon：电源 + 音量下");
        KeyDevices.Device empty = find(ds, "empty");
        check(!empty.hasAnyTarget(), "空的 KEY=：没有键");
        check(empty.whyNotGrabbable() != null, "没有音量 / 电源键：不截");
        KeyDevices.Device noNode = find(ds, "no-node");
        check(noNode.power && noNode.path == null, "没有 event 节点：解析出键、路径为空");
        check(noNode.whyNotGrabbable().contains("event"), "没有 event 节点：不截，原因写明");
    }

    /** 要截点什么时，其余机身上带音量 / 电源键的按键设备只旁观；外接的、触摸屏、豆花自己的不旁观；什么都不截时不旁观。 */
    static void keyDevicesWatch() {
        String proc = block("0019", "0001", "gpio-keys", "3", GPIO)
                + "\n" + block("0019", "0000", "pmic", "3", VOLDOWN_POWER)
                + "\n" + block("0019", "0000", "side-key", "3", VOLUP_HOME)
                + "\n" + block("0018", "0000", "touch", "b", POWER_ONLY, "B: ABS=3")
                + "\n" + block("0003", "05ac", "usb-kbd", "3", "0 8000000000000 0")
                + "\n" + block("0006", "1209", "douhua-vk", "3", GPIO)
                + "\n" + block("0006", "0000", "uinput-vol", "3", GPIO);
        List<KeyDevices.Device> ds = KeyDevices.parse(proc);
        KeyDevices.Plan p = KeyDevices.plan(ds, new KeyDevices.Config(true, false, false));
        check(p.grab.size() == 1 && p.grab.get(0).name.equals("gpio-keys"), "只截 gpio-keys（不截虚拟总线上的 uinput-vol）");
        List<String> watched = new ArrayList<>();
        for (KeyDevices.Device d : p.watch) watched.add(d.name);
        eq(watched, list("pmic", "side-key"), "旁观：带电源键没截的 pmic、带别的键的 side-key；不旁观触摸屏、外接键盘、豆花虚拟键盘、虚拟总线设备");
        KeyDevices.Plan none = KeyDevices.plan(ds, new KeyDevices.Config(false, false, false));
        check(none.grab.isEmpty() && none.watch.isEmpty(), "什么都不截：也不旁观");
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
        g.config(false, false, false, false, true);   // 只电源配了（音量成了被独占设备上没配的键）
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
     * 独占着却收到没见过按下的松开（键在打开设备之前就按着，系统多半看到了按下）：音量补一对按下 + 松开
     * （接过系统那一路的连发再松开，最坏多调一格），电源只补松开（补按下就成了按一下电源键）。
     */
    static void gateStrayUp() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(true, false, false, false, true);
        g.key(VOLDOWN, UP, 400);
        eq(r.log, list("i114+/400/400", "i114-/400/400"), "音量下（透传键）：补按下 + 松开");
        g.key(VOLUP, UP, 500);
        eq(r.log.subList(2, r.log.size()), list("i115+/500/500", "i115-/500/500"), "音量上（配了动作）：同样补，不发给 App");
        g.key(POWER, UP, 600);
        eq(r.log.subList(4, r.log.size()), list("i116-/600/600"), "电源：只补松开");
        for (String x : r.log) check(!x.startsWith("a"), "没见过按下的松开不算点一下");
    }

    /** 刚建出来时键在系统手里：按键不注入、不发给 App；所有键松开就能独占（第一次不用等冷却），按过键要空闲满冷却。 */
    static void gateStartsWithSystem() {
        GateRec r = new GateRec();
        HardKeyGate g = new HardKeyGate(r);
        g.config(true, false, false, false, true);
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
        g.config(true, true, false, false, false);   // 音量上 点一下 + 按住
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
        g.config(false, true, false, false, false);   // 音量上 只按住
        g.key(VOLUP, DOWN, 0);
        g.key(VOLUP, UP, 100);
        eq(r.log, list(), "只配按住、短按：什么都不做，不调音量");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(true, false, false, false, false);  // 音量上 只点一下
        g2.key(VOLUP, DOWN, 0);
        eq(r2.log, list("a0:0"), "只配点一下：按下当场就算");
        g2.pump(1000);
        g2.key(VOLUP, UP, 1500);
        eq(r2.log, list("a0:0"), "按住也不调音量（不注入）");
    }

    /** 电源键单独短按：按着不到 350ms 松开、从第一次按下算 500ms 内没有第二次按下 → 执行豆花动作，一次都不注入。 */
    static void gatePowerShort() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, false, true);
        g.key(POWER, DOWN, 0);
        g.pump(349);
        eq(r.log, list(), "按着 349ms：还不算长按");
        g.key(POWER, UP, 349);
        g.pump(499);
        eq(r.log, list(), "第 499ms：还在等第二次按下");
        g.pump(500);
        eq(r.log, list("a2:0"), "单独短按：第 500ms 发 POWER TAP，没有注入");
    }

    /** 每 10ms 调一次 pump（像 KeyReader 每轮 poll），from 到 to 都含。 */
    static void pumpEvery10(HardKeyGate g, long from, long to) {
        for (long t = from; t <= to; t += 10) g.pump(t);
    }

    /** 电源键长按：按满 350ms → 补发按下（原始 downTime）+ handBack；真松开补松开。 */
    static void gatePowerLong() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, false, true);
        g.key(POWER, DOWN, 0);
        g.pump(349);
        eq(r.log, list(), "按着 349ms：还不算长按");
        g.pump(350);
        eq(r.log, list("i116+/0/350", "HB"), "按满 350ms：补发按下 + 交还");
        g.system(POWER, UP, 2000);    // 交还后真松开从 plain 读到
        eq(r.log, list("i116+/0/350", "HB", "i116-/0/2000"), "长按：补发按下 + 交还 + 松开");
    }

    /**
     * 电源键双击：补发第一下按下 / 松开（原始时间）、第二下按下，+ handBack；真松开补松开。
     * 中间照 KeyReader 每 10ms pump 一次，第二下放在第 495ms：从第一次按下算 500ms 内都算连按。
     */
    static void gatePowerDouble() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, false, true);
        g.key(POWER, DOWN, 0);
        pumpEvery10(g, 10, 90);
        g.key(POWER, UP, 100);
        pumpEvery10(g, 110, 490);
        g.key(POWER, DOWN, 495);
        g.system(POWER, UP, 600);
        eq(r.log, list("i116+/0/0", "i116-/0/100", "i116+/495/495", "HB", "i116-/495/600"),
                "双击（第二下在第 495ms）：按原始时间补发，第二下交还后补松开");

        // 第二下晚于 500ms：第一下先当单独短按发给 App，第二下重新开始一轮
        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, false, true);
        g2.key(POWER, DOWN, 0);
        g2.key(POWER, UP, 100);
        pumpEvery10(g2, 110, 510);
        eq(r2.log, list("a2:0"), "第 500ms 没有第二次按下：发 POWER TAP");
        g2.key(POWER, DOWN, 520);
        g2.key(POWER, UP, 600);
        pumpEvery10(g2, 610, 1010);
        g2.pump(1019);
        eq(r2.log, list("a2:0"), "第二下从头算：第 1019ms 前不发");
        g2.pump(1020);
        eq(r2.log, list("a2:0", "a2:0"), "第二下自己又是一次单独短按（从它按下算 500ms）");
    }

    /** 连按 5 下：一个都不到 App；第一下补发、第二下补发后交还，之后的按下都不再注入（交给原生）。 */
    static void gatePowerMulti() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, false, true);
        g.key(POWER, DOWN, 0);
        pumpEvery10(g, 10, 40);
        g.key(POWER, UP, 50);
        pumpEvery10(g, 60, 90);
        g.key(POWER, DOWN, 100);     // 第二次按下 → 补发 + handBack
        eq(r.log, list("i116+/0/0", "i116-/0/50", "i116+/100/100", "HB"), "连按：第二下补发后交还");
        g.system(POWER, UP, 150);     // 补发的第二下的松开
        int after = r.log.size();
        g.system(POWER, DOWN, 200);  // 第三下起：交还期间，系统原生收，不再注入
        g.system(POWER, UP, 250);
        g.system(POWER, DOWN, 300);
        g.system(POWER, UP, 350);
        eq(r.log.size(), after, "连按后面几下不再注入（交给原生）");
        for (String s : r.log) check(!s.startsWith("a"), "连按：一个事件都不到 App");
    }

    /** 电源键和音量键一起按：按原始时间补发吃掉的部分 + handBack；音量在按住中补 HOLD_END。 */
    static void gateCombo() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, true, true);   // 音量下 只按住；电源配了
        g.key(VOLDOWN, DOWN, 0);      // 音量下吞进 PENDING
        g.key(POWER, DOWN, 100);      // 100ms 内按电源 → 组合键
        eq(r.log, list("i114+/0/0", "i116+/100/100", "HB"), "组合键：按原始时间补发音量下、电源的按下，再交还");
        g.system(VOLDOWN, UP, 400);   // 交还后真松开：给补发的按下配松开
        g.system(POWER, UP, 450);
        eq(r.log.subList(3, r.log.size()), list("i114-/0/400", "i116-/100/450"), "交还后：两个补发的按下都配上松开");

        // 先按电源、后按音量下（音量下下标在前）：补发按按下时间排，电源在前
        GateRec rs = new GateRec();
        HardKeyGate gs = gate(rs);
        gs.config(false, false, false, false, true);   // 只配电源；音量下透传
        gs.key(POWER, DOWN, 0);
        gs.key(VOLDOWN, DOWN, 100);
        eq(rs.log, list("i116+/0/0", "i114+/100/100", "HB"), "组合键：按原始按下时间排序补发（电源在前）");
        gs.system(VOLDOWN, UP, 300);
        gs.system(POWER, UP, 400);
        eq(rs.log.subList(3, rs.log.size()), list("i114-/100/300", "i116-/0/400"), "排序补发后：各配一个松开");

        // 透传的音量下先按（按下已注入过），再按电源：组合键只补发电源，音量下不再注入第二次按下
        GateRec ro = new GateRec();
        HardKeyGate go = gate(ro);
        go.config(false, false, false, false, true);
        go.key(VOLDOWN, DOWN, 0);
        go.key(POWER, DOWN, 80);
        eq(ro.log, list("i114+/0/0", "i116+/80/80", "HB"), "组合键：已透传的音量下不重复补发按下");
        go.system(VOLDOWN, UP, 300);
        go.system(POWER, UP, 320);
        eq(ro.log.subList(3, ro.log.size()), list("i114-/0/300", "i116-/80/320"), "交还后：每个按下只配一个松开");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, true, true);
        g2.key(VOLDOWN, DOWN, 0);
        g2.pump(300);                 // 音量下进入按住
        g2.key(POWER, DOWN, 350);     // 组合键：音量下在按住中 → 补 HOLD_END
        eq(r2.log, list("a1:1", "a1:2", "i114+/0/0", "i116+/350/350", "HB"), "组合键：按住中补 HOLD_END 再补发");

        // 电源键单独短按等着 500ms 时按音量：电源已松开，不算组合键，照常点一下
        GateRec r3 = new GateRec();
        HardKeyGate g3 = gate(r3);
        g3.config(true, false, false, false, true);
        g3.key(POWER, DOWN, 0);
        g3.key(POWER, UP, 100);
        g3.key(VOLUP, DOWN, 200);
        g3.key(VOLUP, UP, 250);
        g3.pump(500);
        eq(r3.log, list("a0:0", "a2:0"), "电源松开后再按音量：不是组合键");
    }

    /**
     * 只旁观的设备上的键也认组合键：电源键（配了，独占着）按着时，另一个没截的设备上按了音量下 → 补发电源按下、交还；
     * 反过来旁观的电源键按着时按独占着的音量键 → 补发音量按下、交还。旁观的键本身从不注入。
     */
    static void gateComboWatched() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, false, true);
        g.key(POWER, DOWN, 0);
        g.observe(VOLDOWN, DOWN, 80);
        eq(r.log, list("i116+/0/0", "HB"), "旁观的音量下 + 电源：补发电源按下、交还");
        g.observe(VOLDOWN, UP, 300);
        g.system(POWER, UP, 320);
        eq(r.log.subList(2, r.log.size()), list("i116-/0/320"), "旁观的键不注入；电源配上松开");
        check(!g.readyToGrab(2319), "交还后冷却没满：不独占");
        check(g.readyToGrab(2320), "冷却满了：重新独占");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, true, false, false);   // 音量下 只点一下
        g2.observe(POWER, DOWN, 0);   // 电源在旁观的设备上（系统自己收）
        g2.key(VOLDOWN, DOWN, 60);
        eq(r2.log, list("i114+/60/60", "HB"), "旁观的电源 + 独占的音量下：不当点一下，补发音量按下、交还");
        g2.observe(POWER, REPEAT, 70);
        check(!g2.readyToGrab(10000), "旁观的电源还按着：不独占");
    }

    /**
     * 键在系统手里时从独占的描述符上读到的（系统没看到）：独占撞上竞态马上放开时，系统看到了按下、松开却只到了 grabFd
     * → 替系统补（音量补一对、电源补松开）；交还那一刻还没读完的按下系统不知道，它的真松开（plain）不用补。
     */
    static void gateUnseen() {
        GateRec r = new GateRec();
        HardKeyGate g = new HardKeyGate(r);
        g.config(true, false, false, false, true);
        g.system(VOLDOWN, DOWN, 100);  // 竞态：独占前按下，系统看到了
        g.key(VOLDOWN, UP, 150);       // 松开只到了 grabFd
        eq(r.log, list("i114+/150/150", "i114-/150/150"), "竞态里的松开：替系统补一对");
        g.system(POWER, DOWN, 200);
        g.key(POWER, UP, 250);
        eq(r.log.subList(2, r.log.size()), list("i116-/250/250"), "电源：只补松开");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, false, true);
        g2.key(POWER, DOWN, 0);
        g2.pump(350);                  // 长按 → 交还
        g2.key(VOLUP, DOWN, 360);      // 交还那一刻 grabFd 上还没读完的：系统没看到
        g2.system(VOLUP, UP, 500);     // 真松开从 plain 来：系统会丢掉没头的松开，这里也不补
        g2.system(POWER, UP, 600);
        eq(r2.log, list("i116+/0/350", "HB", "i116-/0/600"), "交还时系统没看到的按下：松开不补");
        check(!g2.readyToGrab(2599), "冷却没满：不独占");
        check(g2.readyToGrab(2600), "都松开、冷却满：重新独占");
    }

    /** 交还系统后，两个键都空闲满 2000ms 才允许重新独占。 */
    static void gateCooldown() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, false, false, false, true);
        g.key(POWER, DOWN, 0);
        g.pump(350);                  // 长按 → handBack
        g.system(POWER, UP, 500);     // 松开，补松开
        check(!g.readyToGrab(2499), "松开后不到 2000ms：不许重新独占");
        check(g.readyToGrab(2500), "满 2000ms：允许重新独占");
    }

    /** releaseAll：透传中按着的音量键补松开、音量按住中发 HOLD_END；长按补发的电源键也补松开。 */
    static void gateReleaseAll() {
        GateRec r = new GateRec();
        HardKeyGate g = gate(r);
        g.config(false, true, false, false, false);   // 音量上 只按住
        g.key(VOLUP, DOWN, 0);        // PENDING
        g.key(VOLDOWN, DOWN, 50);     // 没配 → 透传按下
        g.pump(300);                  // 音量上进入按住
        g.releaseAll(400);
        eq(r.log, list("i114+/50/50", "a0:1", "a0:2", "i114-/50/400"),
                "releaseAll：透传的音量下补松开、音量上按住发 HOLD_END");

        GateRec r2 = new GateRec();
        HardKeyGate g2 = gate(r2);
        g2.config(false, false, false, false, true);
        g2.key(POWER, DOWN, 0);
        g2.pump(350);                 // 长按 → 补发电源按下 + handBack
        g2.releaseAll(500);
        eq(r2.log, list("i116+/0/350", "HB", "i116-/0/500"), "releaseAll：长按补发的电源键补松开");

        GateRec r3 = new GateRec();
        HardKeyGate g3 = gate(r3);
        g3.config(false, false, false, false, true);
        g3.key(POWER, DOWN, 0);
        g3.key(POWER, UP, 100);       // 短按，等着 500ms
        g3.releaseAll(200);
        g3.pump(600);
        eq(r3.log, list(), "releaseAll：电源键等着的那一下作废，不再发给 App");
        check(g3.systemOwns(), "releaseAll 之后键算在系统手里");
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
