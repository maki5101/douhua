package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * TouchRouter 的离线测试：从服务的 Session 里搬出来以后，桌面模式的行为要和原来一样，蓝牙模式也靠它。
 * 屏幕布局：触控板 (0,0)-(1000,1000)；左键按钮、普通按钮、Ctrl 键、C 键、"复制"（Ctrl+C）键在右边。密度 1。
 * 键盘的缝分给最近的键另用一份照 KeyboardPanel 搭的布局（{@link #keyboardRegions}）。
 */
public final class TouchRouterTest {
    static final int PAD = 0, HOLD = 1, BUTTON = 7, CTRL = 0xE0, KEY_C = 0x06, COPY = 0x06 | (Hid.MOD_CTRL << 8);

    static final class Rec implements TouchRouter.Output {
        final List<String> log = new ArrayList<>();
        long scheduled = -1;
        /** 宿主眼里有没有挂着的定时器（惯性转着时一直挂着）。 */
        boolean pending;

        /** 光标位移加起来（计数）、带位移的帧里没按着左键的有几帧（左键杆的测试用）。 */
        int dxSum, dySum, movesWithoutLeft;

        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            log.add("m" + buttons + (dx != 0 || dy != 0 ? " d" : "") + (wheel != 0 ? " w" + wheel : "") + (pan != 0 ? " p" + pan : ""));
            dxSum += dx;
            dySum += dy;
            if ((dx != 0 || dy != 0) && (buttons & Proto.BUTTON_LEFT) == 0) movesWithoutLeft++;
        }

        @Override
        public void keyboard(byte[] r) {
            StringBuilder s = new StringBuilder("k" + r[0]);
            for (int i = 2; i < 8; i++) if (r[i] != 0) s.append(' ').append(Integer.toHexString(r[i] & 0xFF));
            log.add(s.toString());
        }

        /** 多媒体 / 系统键（协议 24）：记成 "c" / "s" + 十六进制的码。 */
        @Override
        public void consumer(int usage) {
            log.add("c" + Integer.toHexString(usage));
        }

        @Override
        public void system(int usage) {
            log.add("s" + Integer.toHexString(usage));
        }

        @Override
        public void haptic(int kind) {
            log.add("h" + kind);
        }

        @Override
        public void gesture(int fingers, int kind) {
            log.add("g" + fingers + "/" + kind);
        }

        @Override
        public void button(int id, int action) {
            log.add("b" + id + ":" + action);
        }

        /** 修饰键状态记成 "mod锁住/按着/快锁了"（十进制；Fn 不是 0 时后面再跟 "/fn" + Fn 的状态，协议 21）。 */
        @Override
        public void modifiers(int latched, int held, int armed, int fn) {
            log.add("mod" + latched + "/" + held + "/" + armed + (fn != 0 ? "/fn" + fn : ""));
        }

        @Override
        public void caps(boolean sent) {
            log.add("caps" + (sent ? 1 : 0));
        }

        /** 按键定时器（KeyHolds）：挂着没有、最近一次排的延迟、排 / 撤各几次。 */
        boolean keyPending;
        long keyDelay = -1;
        int keySchedules, keyCancels;

        @Override
        public void scheduleKeys(long delayMs) {
            keyPending = true;
            keyDelay = delayMs;
            keySchedules++;
        }

        @Override
        public void cancelKeys() {
            keyPending = false;
            keyCancels++;
        }

        @Override
        public void schedule(long delayMs) {
            scheduled = delayMs;
            pending = true;
        }

        @Override
        public void cancelSchedule() {
            scheduled = -1;
            pending = false;
        }

        /** 宿主的节拍开着没有、开 / 停各调了几次（要配对）。 */
        boolean ticking;
        int tickStarts, tickStops;

        @Override
        public void startTicks() {
            ticking = true;
            tickStarts++;
        }

        @Override
        public void stopTicks() {
            ticking = false;
            tickStops++;
        }

        /** 手柄报文（十六进制），最后一份另记着。 */
        final List<byte[]> pads = new ArrayList<>();

        @Override
        public void gamepad(byte[] report) {
            pads.add(report.clone());
            StringBuilder s = new StringBuilder("pad");
            for (byte b : report) s.append(String.format("%02x", b & 0xFF));
            log.add(s.toString());
        }

        List<String> only(String prefix) {
            List<String> l = new ArrayList<>();
            for (String s : log) if (s.startsWith(prefix)) l.add(s);
            return l;
        }
    }

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        notReadyIgnoresTouches();
        padTapClicks();
        keyPressesAndReleases();
        modifierLatchesUntilNextKey();
        modifierTapTwiceUnlatches();
        modifierTapIsTap();
        modifierHoldTooLong();
        modifierUsedNeverLatches();
        chordKeyHoldsItsModifier();
        holdLeftHoldsButton();
        holdLeftCommand();
        buttonTapAndSlideOff();
        padEdgeScrolls();
        pinchHoldsCtrl();
        releaseAllLetsGo();
        keyFromAppSide();
        regionsInFrontWin();
        regionsSwapKeepsHeldSlot();
        descriptorHasReportIds();
        gapInRowGoesToNearer();
        gapBetweenRows();
        spacerGoesToOwnRow();
        onKeyUnchanged();
        outsideKeyboard();
        neighbourOutsideNotCandidate();
        specialKeyInGap();
        exactTieTakesEarlier();
        frontRegionStillWins();
        emptyKeyboard();
        gapNotReady();
        releaseAllGapKey();
        gapModifierLatches();
        unknownTypeDoesNothing();
        nearestKeyAlone();
        traceLines();
        onTargetForPassthrough();
        zoomIsWholeNotches();
        spinStopsOnKeySilently();
        spinStopsAnywhereWithCatch();
        spinCatchOnPadIsNotClick();
        spinStopsOnAppKeyAndStopSpin();
        spinNotWithCtrlLatched();
        spinNotWithRightCtrlLatched();
        spinNotFromCanceledFinger();
        spinStopsOnNewConfig();
        stickFirstFingerOnly();
        stickMovesOnTicks();
        stickTapClicks();
        stickWithLeftDrags();
        mouseButtonsShareCount();
        middleClickWhenNotScrolled();
        middleScrollWithStick();
        middleScrollWithPad();
        middleCanceledNoClick();
        releaseAllStopsStick();
        stickNewConfigStopsTicks();
        stickTargetsAndNotKeys();
        stickHitIsCircle();
        stickStopsSpin();
        leverScrollsBothWays();
        leverSlowerThanStick();
        leverDeadZoneClicks();
        leverStartedNoClick();
        leverCanceledAndReleaseAll();
        leverOutsideKey();
        leverOnPadBar();
        leverSecondFingerIgnored();
        leverTicksPairWithStick();
        leverTicksRandom();
        leverModes();
        leverAddsToStickAndPad();
        leverStopsSpin();
        leverNewConfigStops();
        leverIdlePause();
        leverYieldsToStick();
        leverYieldsToPad();
        leverYieldsToZoomAndWheel();
        leverFirstThenStickAdds();
        leftLeverTapClicks();
        leftLeverDrags();
        leftLeverSameAsStick();
        leftLeverOutsideKey();
        leftLeverOnPadBar();
        leftLeverSecondFingerIgnored();
        leftLeverOnlyOnMouseKey();
        leftLeverCanceledAndReleaseAll();
        leftLeverLateHoldLeftRelease();
        leftLeverStopsSpin();
        leftLeverIdlePause();
        leftLeverYieldsToStick();
        leftLeverYieldsToPad();
        leftLeverFirstThenStickAdds();
        leftLeverTicksRandom();
        stickAccelThroughRouter();
        stickAccelLeavesScrollAndPad();
        gamepadRoutes();
        gamepadOwnedAtDown();
        gamepadTargets();
        gamepadReleaseAllNeutral();
        gamepadOneReportPerFrame();
        gamepadUnusedSendsNothing();
        gamepadStopsSpinSilently();
        protocol25Flags();
        System.err.println("触摸分流：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static TouchRouter router(Rec r) {
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_HOLD_LEFT, 3, 1000, 0, 1100, 100),
                new TouchRouter.Region(Proto.REGION_BUTTON, BUTTON, 1000, 100, 1100, 200),
                new TouchRouter.Region(Proto.REGION_KEY, CTRL, 1000, 200, 1100, 300),
                new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 1000, 300, 1100, 400),
                new TouchRouter.Region(Proto.REGION_KEY, COPY, 1000, 400, 1100, 500),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),
        });
        return t;
    }

    /** 分屏透传问的"落在键 / 按钮上吗"：键、按钮、"左键"、键盘范围算，触控板、空白处不算；不改状态。 */
    static void onTargetForPassthrough() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        check(t.onTarget(1050, 50), "\"左键\"按钮：算");
        check(t.onTarget(1050, 150), "按钮：算");
        check(t.onTarget(1050, 350), "键：算");
        check(!t.onTarget(500, 500), "触控板：不算");
        check(!t.onTarget(1500, 1500), "空白处：不算");
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 0, 0, 100, 100),
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, 300, 300),
        });
        check(t.onTarget(200, 200), "键盘范围（缝里，分给最近的键）：算");
        eq(r.log.size(), 0, "只问不按：没有任何输出");
    }

    // ═══════════ 惯性滚动、滚轮单位 ═══════════

    /** 左边缘带快速往下一划（1.25dp/ms，密度 1），马上松手：起惯性。返回松手时间。 */
    static long flick(TouchRouter t, long t0) {
        t.down(0, 10, 300, t0);
        for (int i = 1; i <= 8; i++) {
            t.move(0, 10, 300 + i * 10);
            t.frame(t0 + i * 8);
        }
        t.up(0, t0 + 69);
        return t0 + 69;
    }

    /** 定时器到点 n 次（挂着才到）。 */
    static void tick(TouchRouter t, Rec r, long t0, int n) {
        for (int i = 1; i <= n && r.pending; i++) {
            r.pending = false;
            t.onTimer(t0 + i * 16);
        }
    }

    static boolean wheelSince(Rec r, int at) {
        for (String s : r.log.subList(at, r.log.size())) if (s.startsWith("m") && s.contains(" w")) return true;
        return false;
    }

    static final String SPIN_START = "h" + GestureEngine.HAPTIC_SPIN_START, SPIN_CATCH = "h" + GestureEngine.HAPTIC_SPIN_CATCH;

    static void zoomIsWholeNotches() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 400, 500, 1000);
        t.down(1, 600, 500, 1000);
        for (int i = 1; i <= 8; i++) {
            t.move(0, 400 - i * 10, 500);
            t.move(1, 600 + i * 10, 500);
            t.frame(1000 + i * 16);
        }
        boolean whole = true, any = false;
        for (String s : r.only("m")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(" w(-?\\d+)").matcher(s);
            if (!m.find()) continue;
            any = true;
            whole &= Integer.parseInt(m.group(1)) % Hid.WHEEL_UNIT == 0;
        }
        check(any && whole, "开合缩放：滚轮是整格（120 的倍数）");
    }

    static void spinStopsOnKeySilently() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        long up = flick(t, 1000);
        check(r.log.contains(SPIN_START) && r.pending, "边缘一拨：起惯性");
        tick(t, r, up, 3);
        int at = r.log.size();
        t.down(1, 1050, 350, up + 60);   // 按 C 键
        check(!r.pending, "转着时按键：马上停");
        check(!r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "按键停不震（键自己会\"嗒\"）");
        check(r.log.subList(at, r.log.size()).contains("k0 6"), "这个键照常按下");
        tick(t, r, up + 60, 5);
        check(!wheelSince(r, at), "停了不再滚");
    }

    static void spinStopsAnywhereWithCatch() {
        float[][] where = {{1050, 150}, {1500, 1500}, {1050, 50}};
        String[] what = {"按钮", "空白处", "\"左键\"按钮"};
        for (int k = 0; k < where.length; k++) {
            Rec r = new Rec();
            TouchRouter t = router(r);
            long up = flick(t, 1000);
            int at = r.log.size();
            t.down(1, where[k][0], where[k][1], up + 30);
            check(!r.pending, "转着时碰" + what[k] + "：马上停");
            check(r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "碰" + what[k] + "停：震\"刹住\"");
        }
        Rec r = new Rec();
        TouchRouter t = router(r);
        flick(t, 1000);
        t.down(1, 1050, 150, 1100);
        check(r.log.contains("b" + BUTTON + ":" + Proto.BUTTON_DOWN), "碰的那一下照常生效（按钮报告按下）");
    }

    static void spinCatchOnPadIsNotClick() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        long up = flick(t, 1000);
        int at = r.log.size();
        t.down(0, 500, 500, up + 30);   // 触控板中间
        t.frame(up + 30);
        t.up(0, up + 90);
        tick(t, r, up + 90, 30);
        check(r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "碰触控板：抓住，震\"刹住\"");
        check(!r.log.subList(at, r.log.size()).contains("m1"), "抓住的那一下不算点击");
    }

    static void spinStopsOnAppKeyAndStopSpin() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        flick(t, 1000);
        int at = r.log.size();
        t.key(COPY, Proto.KEY_DOWN, 1100);
        check(!r.pending, "界面发来的键（组合键动作、服务没接管时键盘自己收的）：停");
        check(!r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "不震");
        t.key(COPY, Proto.KEY_UP, 1150);
        flick(t, 2000);
        t.stopSpin(true);
        check(!r.pending && r.log.contains(SPIN_CATCH), "stopSpin（分屏透传归系统的手指、App 那边别的按下）：停，震\"刹住\"");
    }

    static void spinNotWithCtrlLatched() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(1, 1050, 250, 300);   // 单独按住 Ctrl 0.5 秒：锁定
        t.onKeyTimer(700);
        t.up(1, 800);
        check(r.log.contains("mod1/0/0"), "Ctrl 锁住");
        flick(t, 1000);
        check(!r.log.contains(SPIN_START) && !r.pending, "锁着 Ctrl：边缘一拨不起惯性（Ctrl + 滚轮是缩放）");
        t.releaseAll();
        flick(t, 2000);
        check(r.log.contains(SPIN_START), "放手（解开 Ctrl）后照常起");
    }

    /**
     * 实体键盘上锁住的右 Ctrl（0xE4，掩码 0x10）也是 Ctrl：不起惯性、转着的停、连续模式照旧按格（不然是停不下来的缩放 / 一小段一小段地缩放）。
     * 以前只看左 Ctrl（2026-11-01 审查发现）。换参数（setConfig 换引擎）时也照锁着的右 Ctrl 告诉新引擎。
     */
    static void spinNotWithRightCtrlLatched() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.key(0xE4, Proto.KEY_DOWN, 300);   // 界面发来的右 Ctrl（实体键盘面板自己收触摸时）：单独按住 0.5 秒锁定
        t.onKeyTimer(700);
        t.key(0xE4, Proto.KEY_UP, 800);
        check(r.log.contains("mod16/0/0"), "右 Ctrl 锁住（掩码 0x10）");
        flick(t, 1000);
        check(!r.log.contains(SPIN_START) && !r.pending, "锁着右 Ctrl：边缘一拨不起惯性");
        t.setConfig(new GestureEngine.Config(), 1);   // 换参数：新引擎也知道右 Ctrl 锁着
        flick(t, 2000);
        check(!r.log.contains(SPIN_START) && !r.pending, "锁着右 Ctrl 换参数后：边缘一拨照样不起惯性");
        t.releaseAll();
        flick(t, 3000);
        check(r.log.contains(SPIN_START) && r.pending, "解开后照常起");
        int at = r.log.size();
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_KEY, 0xE4, 1000, 200, 1100, 300),   // 实体键盘的右 Ctrl 键（按在屏幕上）
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),
        });
        t.down(1, 1050, 250, 3100);
        t.up(1, 3150);
        check(!r.pending, "转着时按右 Ctrl：停");
        check(r.log.subList(at, r.log.size()).contains("k16"), "右 Ctrl 照常按下（键盘报文里是 0x10）");
        // 连续滚动开着、锁着右 Ctrl：照旧按格（Ctrl + 滚轮是缩放）
        Rec r2 = new Rec();
        TouchRouter t2 = router(r2);
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        t2.setConfig(c, 1);
        t2.key(0xE4, Proto.KEY_DOWN, 300);
        t2.onKeyTimer(700);
        t2.key(0xE4, Proto.KEY_UP, 800);
        t2.down(0, 10, 300, 1000);
        for (int i = 1; i <= 20; i++) {
            t2.move(0, 10, 300 + i * 7);
            t2.frame(1000 + i * 16);
        }
        boolean whole = true, any = false;
        for (String s : r2.only("m")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(" w(-?\\d+)").matcher(s);
            if (!m.find()) continue;
            any = true;
            whole &= Integer.parseInt(m.group(1)) % Hid.WHEEL_UNIT == 0;
        }
        check(any && whole, "连续滚动开着、锁着右 Ctrl：边缘滚动照旧整格");
    }

    static void spinNotFromCanceledFinger() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 10, 300, 1000);
        for (int i = 1; i <= 8; i++) {
            t.move(0, 10, 300 + i * 10);
            t.frame(1000 + i * 8);
        }
        t.up(0, 1069, true);   // 手掌 / 丢数据
        check(!r.log.contains(SPIN_START) && !r.pending, "被取消的手指松手：不起惯性");
    }

    static void spinStopsOnNewConfig() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        long up = flick(t, 1000);
        t.setConfig(new GestureEngine.Config(), 1);
        check(!r.pending, "换参数：转着的惯性停，定时器撤掉");
        int at = r.log.size();
        t.onTimer(up + 16);   // 撤晚了、已经排着的那一次
        check(!wheelSince(r, at), "换参数后晚到的定时器不出滚轮");
    }

    static void notReadyIgnoresTouches() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setRegions(new TouchRouter.Region[]{new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 0, 0, 100, 100)});
        check(!t.ready(), "参数没到：不算就绪");
        t.down(0, 50, 50, 1000);
        t.up(0, 1050);
        eq(r.log, list(), "参数没到：触摸一概不理");
    }

    static void padTapClicks() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 500, 500, 1000);
        t.frame(1000);
        t.up(0, 1060);
        eq(r.only("m"), list("m1"), "触控板轻点：按下左键（等轻点拖动窗口）");
        t.onTimer(1300);
        eq(r.only("m"), list("m1", "m0"), "到点松开");
        check(r.log.contains("h" + GestureEngine.HAPTIC_CLICK), "轻点震一下");
    }

    static void keyPressesAndReleases() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 350, 1000);
        t.up(0, 1080);
        eq(r.log, list("k0 6", "b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + Proto.BUTTON_DOWN,
                "k0", "b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + Proto.BUTTON_UP), "C 键：按下发键、松开发空报文，界面收到按下 / 松开");
    }

    /** 2026-11-01 起：单独按住 0.4–1 秒松手才锁（以前点一下就锁），锁了以后打完下一个键自动解开。 */
    static void modifierLatchesUntilNextKey() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);   // 按住 Ctrl
        eq(r.only("k"), list("k1"), "Ctrl 按下马上发");
        check(r.keyPending && r.keyDelay == KeyHolds.HOLD_MS, "排 0.4 秒的按键定时器");
        t.onKeyTimer(1400);
        check(r.log.contains("mod0/1/1") && r.log.contains("h" + GestureEngine.HAPTIC_HOLD), "到 0.4 秒：快锁了（高亮）+ 震一下（协议 25 起是\"按住到点\"那一种）");
        check(!r.log.contains("h" + GestureEngine.HAPTIC_CLICK), "按住到点不报 HAPTIC_CLICK（那是\"点了一下\"，App 据此出鼠标声）");
        t.up(0, 1500);
        eq(r.only("k"), list("k1"), "0.4–1 秒松手：锁住，HID 里照旧按着（主机看不到松开）");
        check(r.log.contains("mod1/0/0") && !r.keyPending, "告诉界面 Ctrl 锁定了，定时器撤了");
        t.down(0, 1050, 350, 1600);   // 再点 C
        t.up(0, 1650);
        eq(r.only("k"), list("k1", "k1 6", "k1", "k0"), "Ctrl 锁着按 C = Ctrl+C，松开后 Ctrl 自动解开");
        check(r.log.contains("mod0/0/0"), "告诉界面解锁了");
    }

    static void modifierTapTwiceUnlatches() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);
        t.onKeyTimer(1400);
        t.up(0, 1500);
        t.down(0, 1050, 250, 2000);   // 锁着时再按一下：这一下是解开
        t.onKeyTimer(2400);   // 没排着也照样调：什么都不做
        t.up(0, 2500);
        eq(r.only("k"), list("k1", "k0"), "锁住后再点一下就解开（按下时不发、松手松开）");
        eq(r.only("mod"), list("mod0/1/0", "mod0/1/1", "mod1/0/0", "mod0/1/0", "mod0/0/0"), "界面跟着变：解锁那一下按多久都不再快锁");
        eq(r.only("h"), list("h" + GestureEngine.HAPTIC_HOLD), "只在第一次到 0.4 秒时震");
    }

    /** 单点修饰键（< 0.4 秒）：按下 / 松开各一帧、不锁、不震（主机上单点 Shift 切中英文照常）。 */
    static void modifierTapIsTap() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);
        t.up(0, 1120);
        eq(r.only("k"), list("k1", "k0"), "点一下：按下、松开");
        eq(r.only("mod"), list("mod0/1/0", "mod0/0/0"), "按着时界面知道（键帽换字），松开不锁");
        check(r.only("h").isEmpty() && !r.keyPending, "不震、定时器撤了");
        t.onKeyTimer(1400);   // 撤晚了、已经到点的那次
        eq(r.only("k"), list("k1", "k0"), "过期的定时器什么都不做");
        check(r.only("h").isEmpty(), "过期的定时器不震");
    }

    /** 单独按住超过 1 秒：高亮退回、松手不锁。 */
    static void modifierHoldTooLong() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);
        t.onKeyTimer(1400);
        check(r.keyPending && r.keyDelay == KeyHolds.LOCK_MAX_MS - KeyHolds.HOLD_MS, "到 0.4 秒后再排到 1 秒");
        r.keyPending = false;   // 宿主的定时器到点了（到点的那次不再挂着）
        t.onKeyTimer(2000);
        check(r.log.contains("mod0/1/0") && r.only("mod").get(r.only("mod").size() - 1).equals("mod0/1/0"), "1 秒：高亮退回");
        check(!r.keyPending, "不再等");
        t.up(0, 2500);
        eq(r.only("k"), list("k1", "k0"), "松手照常松开、不锁");
        eq(r.only("h").size(), 1, "只在 0.4 秒震一次，退回不震");
    }

    /** Ctrl 按着时点 C / 点触控板 / 开合：组合，不锁（不管按了多久）。 */
    static void modifierUsedNeverLatches() {
        // 按着 Ctrl 0.5 秒（已经亮了）再点 C：C 按下那一刻高亮退回，松手不锁
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);
        t.onKeyTimer(1400);
        t.down(1, 1050, 350, 1500);
        check(r.only("mod").get(r.only("mod").size() - 1).equals("mod0/1/0"), "C 按下：快锁了的退回");
        t.up(1, 1550);
        t.up(0, 1700);
        eq(r.only("k"), list("k1", "k1 6", "k1", "k0"), "Ctrl+C，松开 Ctrl 就松");
        check(!r.log.contains("mod1/0/0"), "不锁");
        // 按着 Ctrl 时点触控板（左键按下那一帧算用过）
        Rec r2 = new Rec();
        TouchRouter t2 = router(r2);
        t2.down(0, 1050, 250, 1000);
        t2.down(1, 500, 500, 1100);
        t2.frame(1100);
        t2.up(1, 1150);
        t2.onKeyTimer(1400);
        t2.onTimer(1500);
        t2.up(0, 1600);
        check(!r2.log.contains("mod1/0/0") && !r2.log.contains("mod0/1/1"), "Ctrl + 点触控板：不锁、不快锁");
        check(r2.only("m").contains("m1"), "点击照发（带着 Ctrl 的键盘报文）");
    }


    static void chordKeyHoldsItsModifier() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 450, 1000);
        t.up(0, 1080);
        eq(r.only("k"), list("k1", "k1 6", "k1", "k0"), "复制键：先按 Ctrl 再按 C，先松 C 再松 Ctrl");
        eq(r.only("mod"), list(), "自带的修饰键不算锁定、也不算按着（键帽不换字）");
    }

    static void holdLeftHoldsButton() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(1, 1050, 50, 1000);
        eq(r.only("m"), list("m1"), "按住左键按钮：鼠标左键按下");
        t.down(0, 500, 500, 1010);   // 另一根手指在触控板上拖
        for (int i = 1; i <= 5; i++) {
            t.move(0, 500 + i * 20, 500);
            t.frame(1010 + i * 16);
        }
        check(r.log.contains("m1 d"), "按着左键时移动 = 拖动");
        t.up(0, 1200);
        t.up(1, 1210);
        eq(r.only("m").get(r.only("m").size() - 1), "m0", "松开左键按钮：左键松开");
        check(r.log.contains("b3:" + Proto.BUTTON_DOWN) && r.log.contains("b3:" + Proto.BUTTON_UP), "界面收到按下 / 松开");
    }

    static void holdLeftCommand() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.holdLeft(true);
        eq(r.only("m"), list("m1"), "holdLeft(true)：按住左键");
        t.holdLeft(true);
        eq(r.only("m"), list("m1"), "再按一次只加计数，不重发");
        t.holdLeft(false);
        eq(r.only("m"), list("m1"), "还有一个按着：不松");
        t.holdLeft(false);
        eq(r.only("m"), list("m1", "m0"), "最后一个松开：左键松");
        t.holdLeft(false);
        eq(r.only("m"), list("m1", "m0"), "多出来的松开不理（不变负、什么都不发）");
        t.holdLeft(true);
        eq(r.only("m"), list("m1", "m0", "m1"), "多出来的松开之后再按：照常按下（计数没被减成负的）");
        t.holdLeft(false);
        eq(r.only("m"), list("m1", "m0", "m1", "m0"), "再松：照常松开");

        // 和屏幕上的"左键"按钮（REGION_HOLD_LEFT）共用计数
        Rec r2 = new Rec();
        TouchRouter t2 = router(r2);
        t2.down(1, 1050, 50, 1000);   // 按住"左键"按钮
        eq(r2.only("m"), list("m1"), "按钮按下：左键按下");
        t2.holdLeft(true);            // 实体键也按住左键
        eq(r2.only("m"), list("m1"), "叠加：不重发");
        t2.up(1, 1100);               // 松开按钮，实体键还按着
        eq(r2.only("m"), list("m1"), "按钮松了、实体键还按着：不松");
        t2.holdLeft(false);
        eq(r2.only("m"), list("m1", "m0"), "都松了：左键松");

        // releaseAll 把计数归零：之后多出来的松开不会把左键按下又发出去
        Rec r3 = new Rec();
        TouchRouter t3 = router(r3);
        t3.holdLeft(true);
        t3.releaseAll();
        eq(r3.only("m"), list("m1", "m0"), "releaseAll：实体键按着的左键（CMD_HOLD_LEFT）也发松开");
        t3.holdLeft(true);
        eq(r3.only("m").get(r3.only("m").size() - 1), "m1", "releaseAll 归零后再按：照常按下");

        // releaseAll 之前按下、之后才松开的实体键（转屏重新接管时音量键还按着）：迟到的松开不减收尾以后才按下的左键
        String[] how = {"releaseAll", "换参数"};
        for (int k = 0; k < 2; k++) {
            Rec r4 = new Rec();
            TouchRouter t4 = router(r4);
            t4.holdLeft(true);
            if (k == 0) t4.releaseAll();
            else t4.setConfig(new GestureEngine.Config(), 1);
            t4.down(1, 1050, 50, 1000);   // 收尾以后按住"左键"按钮
            eq(last(r4.only("m")), "m1", how[k] + "后按住\"左键\"按钮：左键按下");
            int at = r4.only("m").size();
            t4.holdLeft(false);   // 实体键迟到的松开
            eq(r4.only("m").size(), at, how[k] + "之前按下的实体键迟到的松开：不理，\"左键\"按钮按着的左键不松");
            t4.up(1, 1100);
            eq(last(r4.only("m")), "m0", how[k] + "后：松开\"左键\"按钮才松");
            t4.holdLeft(true);
            eq(last(r4.only("m")), "m1", how[k] + "后实体键再按：照常按下");
            t4.holdLeft(false);
            eq(last(r4.only("m")), "m0", how[k] + "后实体键再松：照常松开（这一份是它自己的）");
        }
    }

    static void buttonTapAndSlideOff() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 150, 1000);
        t.up(0, 1050);
        eq(r.only("b"), list("b7:" + Proto.BUTTON_DOWN, "b7:" + Proto.BUTTON_TAP), "按钮上按下抬起 = 点击");
        Rec r2 = new Rec();
        TouchRouter t2 = router(r2);
        t2.down(0, 1050, 150, 1000);
        t2.move(0, 500, 150);
        t2.up(0, 1100);
        eq(r2.only("b"), list("b7:" + Proto.BUTTON_DOWN, "b7:" + Proto.BUTTON_UP), "滑出按钮再抬起 = 不算点击");
    }

    static void padEdgeScrolls() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 10, 500, 1000);   // 左边缘带（默认 28dp）
        for (int i = 1; i <= 10; i++) {
            t.move(0, 10, 500 + i * 10);
            t.frame(1000 + i * 16);
        }
        t.up(0, 1200);
        boolean wheel = false;
        for (String s : r.only("m")) if (s.contains(" w")) wheel = true;
        check(wheel, "按在左边缘带上下滑 = 滚轮");
        check(!r.log.contains("m0 d"), "边缘滚动不动光标");
    }

    static void pinchHoldsCtrl() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 400, 500, 1000);
        t.down(1, 600, 500, 1000);
        for (int i = 1; i <= 8; i++) {   // 两指张开
            t.move(0, 400 - i * 10, 500);
            t.move(1, 600 + i * 10, 500);
            t.frame(1000 + i * 16);
        }
        check(r.only("k").contains("k1"), "开合：按住 Ctrl");
        boolean wheel = false;
        for (String s : r.only("m")) if (s.contains(" w")) wheel = true;
        check(wheel, "开合：滚滚轮");
        t.up(0, 1200);
        t.up(1, 1200);
        List<String> k = r.only("k");
        eq(k.get(k.size() - 1), "k0", "手指抬起：Ctrl 松开");
    }

    static void releaseAllLetsGo() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 400);   // 锁定 Ctrl（单独按住 0.5 秒）
        t.onKeyTimer(800);
        t.up(0, 900);
        t.down(1, 1050, 150, 1100);   // 按着普通按钮
        t.down(2, 1050, 350, 1100);   // 按着 C
        t.releaseAll();
        List<String> k = r.only("k");
        eq(k.get(k.size() - 1), "k0", "放手：键全部松开");
        // 协议 25：收尾报的松开带"被取消"（界面先去掉这一位照旧处理，只有声音看它）
        check(r.log.contains("b7:" + (Proto.BUTTON_UP | Proto.BUTTON_CANCELED)), "按着的按钮报告松开（带被取消）");
        check(r.log.contains("b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + (Proto.BUTTON_UP | Proto.BUTTON_CANCELED)), "按着的键报告松开（带被取消）");
        check(!r.log.contains("b7:" + Proto.BUTTON_UP) && !r.log.contains("b7:" + Proto.BUTTON_TAP), "收尾不报不带标记的松开 / 点击");
        check(r.log.contains("mod0/0/0"), "锁定的修饰键解开");
        check(!r.keyPending, "按键定时器撤了");
        int before = r.log.size();
        t.up(1, 1200);
        t.up(2, 1200);
        eq(r.log.size(), before, "放手后再抬起不重复报告");
    }

    static void keyFromAppSide() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.key(COPY, Proto.KEY_DOWN, 1000);
        t.key(COPY, Proto.KEY_UP, 1050);
        eq(r.only("k"), list("k1", "k1 6", "k1", "k0"), "界面直接发的组合键（动作里的复制）");
        eq(r.only("b"), list(), "界面自己发的键不回报按钮事件");
    }

    static void regionsInFrontWin() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_BUTTON, 500, 0, 0, 100, 100),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),
        });
        TouchRouter.Region hit = t.down(0, 50, 50, 1000);
        check(hit != null && hit.id == 500, "叠在触控板上的角按钮排在前面，先命中");
    }

    /**
     * 按着时整体换区域（空中鼠标页上开 / 收键盘）：还按着的手指照旧报给按下时的区域，换区域之后的新触摸只看新列表。
     * 键照常松开；按钮抬起时仍对原来的编号报 TAP——所以 MainActivity.onButton 要对暂停中的空中鼠标页按钮兜底。
     */
    static void regionsSwapKeepsHeldSlot() {
        final int ROUND = 702;   // 空中鼠标页的圆钮，盖在原来 C 键的位置上
        TouchRouter.Region[] airPage = {new TouchRouter.Region(Proto.REGION_BUTTON, ROUND, 900, 250, 1100, 450)};
        TouchRouter.Region[] keyboardPage = {
                new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 1000, 300, 1100, 400),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),
        };
        String cDown = "b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + Proto.BUTTON_DOWN;
        String cUp = "b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + Proto.BUTTON_UP;

        // 键盘 → 空中鼠标页（收起键盘时还按着 C）
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 350, 1000);
        t.setRegions(airPage);
        t.up(0, 1100);
        eq(r.only("k"), list("k0 6", "k0"), "按着 C 时换成空中鼠标页：抬起时 C 照常松开");
        eq(r.only("b"), list(cDown, cUp), "按钮报告仍是 C 键的按下 / 松开，不报给圆钮");
        r.log.clear();
        t.down(1, 1050, 350, 1200);
        t.up(1, 1250);
        eq(r.only("b"), list("b" + ROUND + ":" + Proto.BUTTON_DOWN, "b" + ROUND + ":" + Proto.BUTTON_TAP),
                "换区域之后在原来 C 的位置按：是新列表里的圆钮");
        eq(r.only("k"), list(), "不再出键");

        // 空中鼠标页 → 键盘（按着圆钮时键盘盖上来）
        Rec r2 = new Rec();
        TouchRouter t2 = router(r2);
        t2.setRegions(airPage);
        t2.down(0, 1050, 350, 1000);
        t2.setRegions(keyboardPage);
        t2.up(0, 1100);
        eq(r2.only("b"), list("b" + ROUND + ":" + Proto.BUTTON_DOWN, "b" + ROUND + ":" + Proto.BUTTON_TAP),
                "按着圆钮时换成键盘：抬起时仍对圆钮报 TAP（界面要把它当松开）");
        eq(r2.only("k"), list(), "换区域本身不按键");
        r2.log.clear();
        t2.down(1, 1050, 350, 1200);
        t2.up(1, 1250);
        eq(r2.only("b"), list(cDown, cUp), "之后同一位置的新触摸：落到键盘的 C 上");
        eq(r2.only("k"), list("k0 6", "k0"), "C 照常按下松开");
    }

    static void descriptorHasReportIds() {
        byte[] d = Hid.combinedDescriptor();
        eq(d.length, Hid.KEYBOARD_DESC.length + Hid.MOUSE_DESC.length + Hid.CONSUMER_DESC.length + 6, "合并描述符长度");
        int ids = 0;
        for (int i = 0; i + 1 < d.length; i++) {
            if (d[i] == (byte) 0x85 && d[i - 2] == (byte) 0xA1) ids |= 1 << d[i + 1];
        }
        eq(ids, (1 << Hid.REPORT_KEYBOARD) | (1 << Hid.REPORT_MOUSE) | (1 << Hid.REPORT_CONSUMER), "三个报文编号都在 Collection 后面");
        byte[] m = new byte[Hid.MOUSE_REPORT_SIZE];
        Hid.mouseReport(m, 1, -2, 300, 1, -1);
        eq(list(m[0] + "", m[1] + "", m[2] + "", m[3] + "", m[4] + "", m[5] + "", m[6] + ""),
                list("1", "-2", "-1", "44", "1", "1", "-1"), "鼠标报文：小端 16 位");
    }

    // ═══════════ 键盘的缝分给最近的键 ═══════════

    static final int TOOLBAR = 900, SYM = 300;
    static final int Q = 0x14, W = 0x1A, P = 0x13, A = 0x04, S = 0x16, L = 0x0F, SPACE = 0x2C;

    /**
     * 照 KeyboardPanel 搭（间距 = 每个键四边各留 5，行高 100），键盘面板 (0,600)-(1000,900)：
     *   第一行 q–p 十个键，每格 100 宽；
     *   第二行 两头各半格空位（不报），中间 a–l 九个键；
     *   第三行 Ctrl、?123（按钮，1.5 格）、空格（5 格）、"."、↵（1.5 格）。
     * 前面是工具栏按钮 (0,560)-(200,600)（紧贴面板上沿，在面板外），后面是小触控板 (0,300)-(1000,550)。
     */
    static List<TouchRouter.Region> keyboardKeys() {
        List<TouchRouter.Region> l = new ArrayList<>();
        String row0 = "qwertyuiop";
        for (int i = 0; i < 10; i++) l.add(key(0x04 + row0.charAt(i) - 'a', i * 100, 600, 100));
        String row1 = "asdfghjkl";
        for (int i = 0; i < 9; i++) l.add(key(0x04 + row1.charAt(i) - 'a', 50 + i * 100, 700, 100));
        l.add(key(CTRL, 0, 800, 100));
        l.add(new TouchRouter.Region(Proto.REGION_BUTTON, SYM, 105, 805, 245, 895));
        l.add(key(SPACE, 250, 800, 500));
        l.add(key(0x37, 750, 800, 100));
        l.add(key(0x28, 850, 800, 150));
        return l;
    }

    /** 一格从 (x, y) 起、宽 w、高 100，键在里面四边各缩 5。 */
    static TouchRouter.Region key(int id, int x, int y, int w) {
        return new TouchRouter.Region(Proto.REGION_KEY, id, x + 5, y + 5, x + w - 5, y + 95);
    }

    static TouchRouter.Region[] keyboardRegions() {
        List<TouchRouter.Region> l = new ArrayList<>();
        l.add(new TouchRouter.Region(Proto.REGION_BUTTON, TOOLBAR, 0, 560, 200, 600));
        l.addAll(keyboardKeys());
        l.add(new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 600, 1000, 900));
        l.add(new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 300, 1000, 550));
        return l.toArray(new TouchRouter.Region[0]);
    }

    static TouchRouter keyboardRouter(Rec r) {
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(keyboardRegions());
        return t;
    }

    /** 在 (x, y) 点一下，返回分流的全部输出。 */
    static List<String> tap(float x, float y) {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        t.down(0, x, y, 1000);
        t.up(0, 1080);
        return r.log;
    }

    static String keyId(TouchRouter.Region r) {
        return r == null ? "null" : r.type + ":" + Integer.toHexString(r.id);
    }

    static void gapInRowGoesToNearer() {
        eq(tap(99, 650), tap(50, 650), "同一行 q、w 之间的缝，靠 q 那边 = 按 q（报文、按钮事件和按在 q 上一样）");
        eq(tap(101, 650), tap(150, 650), "靠 w 那边 = 按 w");
        eq(tap(99, 650).get(0), "k0 " + Integer.toHexString(Q), "确实发了 q");
    }

    static void gapBetweenRows() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        eq(keyId(t.hit(160, 698)), keyId(t.hit(150, 650)), "两行之间的缝，离上一行近 = 上一行（w）");
        eq(keyId(t.hit(160, 702)), Proto.REGION_KEY + ":" + Integer.toHexString(S), "离下一行近 = 下一行，行里再找横向最近的（s）");
        eq(keyId(t.hit(270, 700)), Proto.REGION_KEY + ":" + Integer.toHexString(0x08), "正好在两行中线上、上下两个键横向都是 0 = 列表里靠前的（上一行 e）");
        eq(keyId(t.hit(300, 700)), Proto.REGION_KEY + ":" + Integer.toHexString(0x07), "竖向一样近时再比横向：e、r 之间的缝对着 d = d");
    }

    static void spacerGoesToOwnRow() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        // 左空位靠上：离 q 底边 15、离 a 左边 35，按纯欧氏距离会给 q；先定行就归 a
        eq(keyId(t.hit(20, 710)), Proto.REGION_KEY + ":" + Integer.toHexString(A), "第二行左空位靠上 = a，不是 q");
        eq(keyId(t.hit(985, 710)), Proto.REGION_KEY + ":" + Integer.toHexString(L), "第二行右空位靠上 = l，不是 p");
        eq(keyId(t.hit(2, 790)), Proto.REGION_KEY + ":" + Integer.toHexString(A), "左空位贴着面板边 = a");
    }

    static void onKeyUnchanged() {
        Rec r = new Rec();
        TouchRouter with = keyboardRouter(r);
        List<TouchRouter.Region> plain = new ArrayList<>();
        for (TouchRouter.Region g : keyboardRegions()) if (g.type != Proto.REGION_KEYBOARD) plain.add(g);
        TouchRouter without = new TouchRouter(r);
        without.setRegions(plain.toArray(new TouchRouter.Region[0]));
        int same = 0, gap = 0, bad = 0;
        for (int x = -3; x < 1010; x += 7) {
            for (int y = 280; y < 920; y += 7) {
                TouchRouter.Region a = without.hit(x, y), b = with.hit(x, y);
                boolean inKeyboard = x >= 0 && x < 1000 && y >= 600 && y < 900;
                if (a != null || !inKeyboard) {
                    if (keyId(a).equals(keyId(b))) same++;
                    else bad++;
                } else {
                    if (b != null && b.type != Proto.REGION_KEYBOARD) gap++;
                    else bad++;
                }
            }
        }
        eq(bad, 0, "加键盘范围前后：按在键上 / 键盘外的结果逐点一样，缝里的都分到了键");
        check(same > 1000 && gap > 300, "网格确实覆盖了键上和缝里（键上 / 外面 " + same + " 点，缝里 " + gap + " 点）");
        // 原来的 router() 布局加一块范围（键都贴着，没有缝）：每个点都不变
        Rec r2 = new Rec();
        TouchRouter old = router(r2);
        TouchRouter boxed = router(r2);
        List<TouchRouter.Region> rs = new ArrayList<>();
        rs.add(new TouchRouter.Region(Proto.REGION_HOLD_LEFT, 3, 1000, 0, 1100, 100));
        rs.add(new TouchRouter.Region(Proto.REGION_BUTTON, BUTTON, 1000, 100, 1100, 200));
        rs.add(new TouchRouter.Region(Proto.REGION_KEY, CTRL, 1000, 200, 1100, 300));
        rs.add(new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 1000, 300, 1100, 400));
        rs.add(new TouchRouter.Region(Proto.REGION_KEY, COPY, 1000, 400, 1100, 500));
        rs.add(new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 1000, 0, 1100, 500));
        rs.add(new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000));
        boxed.setRegions(rs.toArray(new TouchRouter.Region[0]));
        int diff = 0;
        for (int x = 0; x < 1150; x += 9) for (int y = 0; y < 1050; y += 9) if (!keyId(old.hit(x, y)).equals(keyId(boxed.hit(x, y)))) diff++;
        eq(diff, 0, "原来的布局加上键盘范围：逐点结果不变");
    }

    static void outsideKeyboard() {
        eq(tap(500, 950), list(), "键盘外面的空白：什么都不发");
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        eq(keyId(t.hit(100, 580)), Proto.REGION_BUTTON + ":" + Integer.toHexString(TOOLBAR), "工具栏按钮照常命中");
        eq(keyId(t.hit(500, 400)), Proto.REGION_PAD + ":0", "小触控板照常命中");
        eq(keyId(t.hit(500, 560)), "null", "小触控板和键盘之间的空白（不在键盘范围里）不分给键");
    }

    static void neighbourOutsideNotCandidate() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        // (150, 601)：离紧贴面板上沿的工具栏按钮只有 1，离 w 有 4；工具栏不整个在键盘范围里，不当候选
        eq(keyId(t.hit(150, 601)), Proto.REGION_KEY + ":" + Integer.toHexString(W), "面板外紧挨着的工具栏按钮不会被当成最近的键");
    }

    static void specialKeyInGap() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        t.down(0, 101, 850, 1000);   // Ctrl 和 ?123 之间的缝，靠 ?123
        t.up(0, 1080);
        eq(r.only("b"), list("b" + SYM + ":" + Proto.BUTTON_DOWN, "b" + SYM + ":" + Proto.BUTTON_TAP), "缝里分到 ?123，手指一直停在缝里松开 = 点击");
        Rec r2 = new Rec();
        TouchRouter t2 = keyboardRouter(r2);
        t2.down(0, 101, 850, 1000);
        t2.move(0, 500, 850);   // 滑到空格上
        t2.up(0, 1080);
        eq(r2.only("b"), list("b" + SYM + ":" + Proto.BUTTON_DOWN, "b" + SYM + ":" + Proto.BUTTON_UP), "滑到别的键上松开 = 不算点击");
        Rec r3 = new Rec();
        TouchRouter t3 = keyboardRouter(r3);
        t3.down(0, 101, 850, 1000);
        t3.move(0, 248, 850);   // 滑到 ?123 和空格之间的缝，靠 ?123
        t3.up(0, 1080);
        eq(r3.only("b"), list("b" + SYM + ":" + Proto.BUTTON_DOWN, "b" + SYM + ":" + Proto.BUTTON_TAP), "滑到另一条还归 ?123 的缝里松开 = 点击");
    }

    static void exactTieTakesEarlier() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        for (int i = 0; i < 3; i++) eq(keyId(t.hit(100, 650)), Proto.REGION_KEY + ":" + Integer.toHexString(Q), "q、w 正中间 = 列表里靠前的 q（第 " + (i + 1) + " 次）");
    }

    static void frontRegionStillWins() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        List<TouchRouter.Region> l = new ArrayList<>();
        l.add(new TouchRouter.Region(Proto.REGION_BUTTON, 777, 0, 600, 50, 650));   // 排在前面、和键盘范围重叠
        for (TouchRouter.Region g : keyboardRegions()) l.add(g);
        t.setRegions(l.toArray(new TouchRouter.Region[0]));
        eq(keyId(t.hit(2, 602)), Proto.REGION_BUTTON + ":" + Integer.toHexString(777), "排在键盘范围前面的区域（按在边距里）照样先命中");
        eq(keyId(t.hit(20, 620)), Proto.REGION_BUTTON + ":" + Integer.toHexString(777), "排在键前面的也照样先命中");
    }

    static void emptyKeyboard() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 600, 1000, 900),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),   // 排在后面的触控板不接
        });
        check(t.down(0, 500, 700, 1000) == null, "键盘范围里一个键都没有 = 没按到");
        t.up(0, 1080);
        eq(r.log, list(), "什么都不发");
    }

    static void gapNotReady() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setRegions(keyboardRegions());
        t.down(0, 99, 650, 1000);
        t.up(0, 1080);
        eq(r.log, list(), "参数没到：按在缝里也什么都不发");
    }

    static void releaseAllGapKey() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        t.down(0, 99, 650, 1000);   // 缝里分到 q
        t.releaseAll();
        List<String> k = r.only("k");
        eq(k.get(k.size() - 1), "k0", "放手：分到的键松开");
        check(r.log.contains("b" + (Proto.KEY_ID_BASE + Q) + ":" + (Proto.BUTTON_UP | Proto.BUTTON_CANCELED)), "报告分到的那个键松开（带被取消）");
    }

    static void gapModifierLatches() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        t.down(0, 2, 850, 1000);   // Ctrl 左边的边距
        t.onKeyTimer(1400);
        t.up(0, 1500);
        eq(r.only("k"), list("k1"), "缝里分到 Ctrl：照样单独按住 0.5 秒锁定");
        t.down(0, 100, 750, 1600);   // a 的正中
        t.up(0, 1650);
        eq(r.only("k"), list("k1", "k1 4", "k1", "k0"), "锁着 Ctrl 按 a，打完自动解开");
        eq(r.only("mod"), list("mod0/1/0", "mod0/1/1", "mod1/0/0", "mod0/0/0"), "界面跟着变");
    }

    static void unknownTypeDoesNothing() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(new TouchRouter.Region[]{new TouchRouter.Region(99, 0, 0, 0, 100, 100)});
        t.down(0, 50, 50, 1000);
        t.up(0, 1080);
        eq(r.log, list(), "不认识的区域类型：什么都不做（不再当成按钮 id 0 = 退出）");
    }

    static void nearestKeyAlone() {
        TouchRouter.Region area = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 600, 1000, 900);
        check(TouchRouter.nearestKey(new TouchRouter.Region[0], area, 10, 610) == null, "nearestKey：空数组 = null");
        eq(keyId(TouchRouter.nearestKey(keyboardRegions(), area, 1500, 20)), Proto.REGION_KEY + ":" + Integer.toHexString(P),
                "nearestKey：点在范围外也照样找最近的（右上角 p）");
        TouchRouter.Region[] pads = {new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 600, 1000, 900), area};
        check(TouchRouter.nearestKey(pads, area, 10, 610) == null, "nearestKey：只有触控板 = null");
    }

    static void traceLines() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        List<String> lines = new ArrayList<>();
        t.setTrace(lines::add);
        int keys = keyboardKeys().size();
        eq(lines.get(0), "L 0 600 1000 900 " + keys, "打开时先记布局：键盘范围 + 键数");
        eq(lines.size(), 1 + keys, "后面每个键一行 K（工具栏、小触控板不算）");
        eq(lines.get(1), "K " + Proto.REGION_KEY + " 14 5 605 95 695", "K 行：类型、十六进制 id、矩形");
        t.setRegions(keyboardRegions());
        eq(lines.size(), 1 + keys, "同样的布局重报不重复记");
        TouchRouter.Region[] changed = keyboardRegions();
        changed[1] = new TouchRouter.Region(Proto.REGION_KEY, 0x1E, 5, 605, 95, 695);   // 换层：q 的位置变成 1
        t.setRegions(changed);
        eq(lines.size(), 2 + 2 * keys, "键变了再记一遍");
        eq(lines.get(1 + keys + 1), "K " + Proto.REGION_KEY + " 1e 5 605 95 695", "记的是新键");
        t.setRegions(keyboardRegions());
        lines.clear();
        t.down(0, 99, 650, 1000);   // 缝里
        eq(lines, list("p 1000 0 99.0 650.0 - 14 5 605 95 695"), "缝里的触点：hit 是 -，near 是分到的键");
        t.up(0, 1080);
        eq(lines.get(1), "u 1080 0 99.0 650.0", "抬起记 u");
        lines.clear();
        t.down(1, 150.25f, 650, 1100);   // 按在 w 上
        eq(lines, list("p 1100 1 150.3 650.0 1a 1a 105 605 195 695"), "按在键上：hit == near（坐标一位小数，四舍五入）");
        t.move(1, 160, 655);
        t.up(1, 1150);
        eq(lines.get(1), "u 1150 1 160.0 655.0", "抬起记最后的位置");
        lines.clear();
        t.down(2, 500, 400, 1200);   // 小触控板
        t.up(2, 1250);
        t.down(2, 100, 580, 1300);   // 工具栏
        t.up(2, 1350);
        eq(lines, list(), "键盘外面（小触控板、工具栏）的触摸不记");
        // 小红点嵌在键里（范围在键盘范围里面、排在键前面）：按在红点上不是按键，不记；布局里也不多出它
        TouchRouter.Region[] base = keyboardRegions();
        TouchRouter.Region[] withStick = new TouchRouter.Region[base.length + 1];
        withStick[0] = new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, 90, 640, 110, 660);
        System.arraycopy(base, 0, withStick, 1, base.length);
        t.setRegions(withStick);
        t.down(3, 100, 650, 1500);
        t.up(3, 1510);
        eq(lines, list(), "按在嵌着的红点上：不记 p / u，布局也没变（红点不是键）");
        // 小红点状态下三个鼠标键铺满底行、快捷行的空位（也在键盘范围里面、排在键前面）：按在上面同样不记
        TouchRouter.Region[] withMouse = new TouchRouter.Region[base.length + 1];
        withMouse[0] = new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 90, 640, 110, 660);
        System.arraycopy(base, 0, withMouse, 1, base.length);
        t.setRegions(withMouse);
        t.down(3, 100, 650, 1520);
        t.up(3, 1530);
        eq(lines, list(), "按在键盘里的鼠标键上：不记 p / u，布局也没变（鼠标键不是键）");
        t.setRegions(keyboardRegions());
        t.setTrace(null);
        t.down(0, 99, 650, 1400);
        t.up(0, 1450);
        t.setRegions(changed);
        eq(lines, list(), "关掉以后什么都不记");
        Rec r2 = new Rec();
        TouchRouter none = router(r2);
        List<String> l2 = new ArrayList<>();
        none.setTrace(l2::add);
        eq(l2, list("L 0"), "没有键盘时只记 L 0");
    }

    // ═══════════ 小红点、三个鼠标键 ═══════════

    static final int STICK = Proto.STICK_ID;
    static final int M_LEFT = Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_LEFT;
    static final int M_MIDDLE = Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_MIDDLE;
    static final int M_RIGHT = Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_RIGHT;

    /** 在 router() 的布局上加：红点 (2000,0)-(2100,100)，下面左 / 中 / 右三个键（各 30 宽、隔 5）。 */
    static TouchRouter stickRouter(Rec r, GestureEngine.Config c) {
        TouchRouter t = new TouchRouter(r);
        t.setConfig(c, 1);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_STICK, STICK, 2000, 0, 2100, 100),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 2000, 110, 2030, 150),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_MIDDLE, 2035, 110, 2065, 150),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_RIGHT, 2070, 110, 2100, 150),
                new TouchRouter.Region(Proto.REGION_HOLD_LEFT, 3, 1000, 0, 1100, 100),
                new TouchRouter.Region(Proto.REGION_BUTTON, BUTTON, 1000, 100, 1100, 200),
                new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 1000, 300, 1100, 400),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),
        });
        return t;
    }

    static TouchRouter stickRouter(Rec r) {
        return stickRouter(r, new GestureEngine.Config());
    }

    /** 推红点：按在 (2050, 50)，挪到 (2050 + dx, 50 + dy)，再按 8ms 一拍跑 ms 毫秒。返回最后的时间。 */
    static long push(TouchRouter t, int slot, float dx, float dy, long t0, int ms) {
        t.down(slot, 2050, 50, t0);
        t.move(slot, 2050 + dx, 50 + dy);
        t.frame(t0);
        long now = t0;
        while (now < t0 + ms) {
            now += 8;
            t.onTick(now);
        }
        return now;
    }

    static void stickFirstFingerOnly() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 50, 1000);
        check(r.log.contains("b" + STICK + ":" + Proto.BUTTON_DOWN), "按在红点上：报按下（画样子）");
        check(r.ticking && r.tickStarts == 1, "红点按着：开节拍");
        int at = r.log.size();
        t.down(2, 2060, 60, 1010);
        t.move(2, 2090, 60);
        t.frame(1010);
        t.up(2, 1020);
        eq(r.log.size(), at, "第二根手指按在红点上：不理（不报、不动）");
        check(r.ticking, "第二根抬起：节拍照开着");
        t.up(1, 1100);
        check(r.log.contains("b" + STICK + ":" + Proto.BUTTON_UP), "第一根抬起：报松开");
        check(!r.ticking && r.tickStarts == 1 && r.tickStops == 1, "抬起：节拍停，开停配对");
    }

    static void stickMovesOnTicks() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = push(t, 1, 15, 0, 1000, 500);
        check(r.log.contains("m0 d") && r.only("m0 w").isEmpty(), "推红点：按节拍出位移，不按键");
        t.up(1, now + 4);
        int at = r.log.size();
        t.onTick(now + 8);   // 迟到的节拍
        eq(r.log.size(), at, "抬起后迟到的节拍：什么都不出");
        check(!r.only("m1").contains("m1"), "推完松手不算轻点（按了 500ms）");
    }

    static void stickTapClicks() {
        // "轻点红点 = 左键"默认关（2026-11-01 用户要关）：打开时点一下左键
        GestureEngine.Config on = new GestureEngine.Config();
        on.stickTap = true;
        Rec r = new Rec();
        TouchRouter t = stickRouter(r, on);
        t.down(1, 2050, 50, 1000);
        t.onTick(1008);
        t.up(1, 1100);
        eq(r.only("m"), list("m1", "m0"), "轻点红点（开着）：点一下左键");
        check(r.log.contains("h" + GestureEngine.HAPTIC_CLICK), "轻点红点（开着）：嗒一下");
        // 默认（关）：轻点什么都不发
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        t2.down(1, 2050, 50, 1000);
        t2.onTick(1008);
        t2.up(1, 1100);
        check(r2.only("m").isEmpty(), "轻点红点（默认关）：不点");
    }

    static void stickWithLeftDrags() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.texture = true;
        TouchRouter t = stickRouter(r, c);
        t.down(2, 2015, 130, 1000);   // 按住三键条的左键
        eq(r.only("m"), list("m1"), "按住左键：左键按下");
        push(t, 1, 15, 0, 1000, 1000);
        check(r.log.contains("m1 d"), "按着左键推红点 = 拖动");
        check(!r.log.contains("h" + GestureEngine.HAPTIC_TEXTURE), "拖动时不发颗粒");
        t.up(2, 2100);
        check(r.log.get(r.log.size() - 1).startsWith("m0") || r.only("m").contains("m0"), "松开左键：左键松");
        // 不按键时推：有颗粒
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2, c);
        push(t2, 1, 15, 0, 1000, 1000);
        check(r2.log.contains("h" + GestureEngine.HAPTIC_TEXTURE), "推着走：发颗粒");
    }

    static void mouseButtonsShareCount() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2015, 130, 1000);   // 三键条左键
        eq(r.only("m"), list("m1"), "三键条左键：按下");
        t.holdLeft(true);               // 实体键也按住左键
        t.down(2, 1050, 50, 1010);    // "左键"按钮也按住
        eq(r.only("m"), list("m1"), "三个来源共用计数：不重发");
        t.up(1, 1100);
        t.holdLeft(false);
        eq(r.only("m"), list("m1"), "还有一个按着：不松");
        t.down(3, 2085, 130, 1110);   // 右键
        eq(r.only("m"), list("m1", "m3"), "左键按着时按右键：两个都按着");
        t.up(2, 1200);
        eq(r.only("m"), list("m1", "m3", "m2"), "左键全松了、右键还按着");
        t.up(3, 1300);
        eq(r.only("m"), list("m1", "m3", "m2", "m0"), "右键也松了");
        check(r.log.contains("b" + M_LEFT + ":" + Proto.BUTTON_DOWN) && r.log.contains("b" + M_RIGHT + ":" + Proto.BUTTON_UP),
                "界面收到三键条的按下 / 松开（画样子、震动）");
    }

    static void middleClickWhenNotScrolled() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);
        check(r.only("m").isEmpty(), "中键按下：先不发中键");
        t.up(1, 1500);
        eq(r.only("m"), list("m4", "m0"), "没滚过就松开：补一次中键单击");
        check(!r.log.contains("h" + GestureEngine.HAPTIC_CLICK), "补的单击不再震（按下时界面已经嗒过）");
        // 红点按着、在死区里没动，松开中键：照样是中键单击
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        t2.down(1, 2050, 130, 1000);
        push(t2, 2, 1, 1, 1000, 300);
        t2.up(1, 1400);
        check(r2.only("m").contains("m4"), "红点在死区里没动：中键单击");
    }

    static void middleScrollWithStick() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);   // 按住中键
        long now = push(t, 2, 0, 15, 1000, 1000);   // 往下推红点
        check(r.log.contains("m0 w-" + Hid.WHEEL_UNIT), "按住中键往下推：往下翻（整格，滚轮负）");
        check(!r.log.contains("m0 d"), "滚动时不出位移");
        check(r.log.contains("h" + GestureEngine.HAPTIC_TICK), "每格咔嗒");
        t.up(2, now);
        t.up(1, now + 10);
        check(!r.only("m").contains("m4"), "滚过了：松开中键不补单击");
        // 先推着走，再按中键：从下一拍起变成滚动
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        long n2 = push(t2, 2, 15, 0, 1000, 300);
        check(r2.log.contains("m0 d"), "没按中键：移动");
        int at = r2.log.size();
        t2.down(1, 2050, 130, n2);
        for (long x = n2 + 8; x <= n2 + 600; x += 8) t2.onTick(x);
        List<String> after = r2.log.subList(at, r2.log.size());
        check(after.contains("m0 p" + Hid.WHEEL_UNIT) && !after.contains("m0 d"), "推着时按下中键：变成横向滚动、不再移动");
    }

    static void middleScrollWithPad() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);   // 按住中键
        t.down(0, 500, 300, 1000);
        for (int i = 1; i <= 10; i++) {
            t.move(0, 500, 300 + i * 10);   // 单指往下划 100dp
            t.frame(1000 + i * 16);
        }
        t.up(0, 1200);
        check(r.log.contains("m0 w-" + Hid.WHEEL_UNIT), "按住中键单指往下划：往下翻（不跟自然滚动）");
        check(!r.log.contains("m0 d"), "中键滚动时不动光标");
        t.up(1, 1300);
        check(!r.only("m").contains("m4"), "触控板上滚过：松开中键不补单击");
        // 中键按着时在触控板上单指轻点：照旧是左键单击
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        t2.down(1, 2050, 130, 1000);
        t2.down(0, 500, 300, 1010);
        t2.up(0, 1080);
        check(r2.only("m").contains("m1"), "中键按着时单指轻点：照旧左键单击");
    }

    /**
     * 中键按着时手指被取消（手掌、丢数据；服务释放触摸屏时 TouchReader 收尾补报的抬起也走 up(…, true)）：照样松开、
     * 解开滚动，但不补中键单击（同 releaseAll）。左 / 右键被取消照常松开。
     */
    static void middleCanceledNoClick() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);   // 中键
        t.up(1, 1500, true);
        check(!r.only("m").contains("m4"), "中键被取消：不补中键单击");
        check(r.log.contains("b" + M_MIDDLE + ":" + (Proto.BUTTON_UP | Proto.BUTTON_CANCELED)), "中键被取消：界面照样收到松开（协议 25 带被取消）");
        int at = r.log.size();
        t.down(0, 500, 300, 1600);
        for (int i = 1; i <= 10; i++) {
            t.move(0, 500, 300 + i * 10);
            t.frame(1600 + i * 16);
        }
        t.up(0, 1800);
        check(r.log.subList(at, r.log.size()).contains("m0 d") && !wheelSince(r, at), "中键被取消后滚动解开了：单指划照常移动光标");
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        t2.down(1, 2015, 130, 1000);   // 左键
        t2.down(3, 2085, 130, 1000);   // 右键
        t2.up(1, 1100, true);
        t2.up(3, 1100, true);
        eq(r2.only("m"), list("m1", "m3", "m2", "m0"), "左 / 右键被取消：照常松开");
    }

    static void releaseAllStopsStick() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);   // 中键
        t.down(3, 2085, 130, 1000);   // 右键
        push(t, 2, 15, 0, 1000, 200);
        t.releaseAll();
        check(!r.ticking && r.tickStarts == r.tickStops, "releaseAll：节拍停（开停配对）");
        int canceledUp = Proto.BUTTON_UP | Proto.BUTTON_CANCELED;
        check(r.log.contains("b" + STICK + ":" + canceledUp) && r.log.contains("b" + M_MIDDLE + ":" + canceledUp)
                && r.log.contains("b" + M_RIGHT + ":" + canceledUp), "releaseAll：红点、中键、右键报松开（带被取消）");
        check(!r.only("m").contains("m4") && !r.only("m").contains("m6"), "releaseAll：中键不补单击");
        eq(r.only("m").get(r.only("m").size() - 1), "m0", "releaseAll：右键松开");
        int at = r.log.size();
        t.onTick(1300);
        t.up(1, 1310);
        t.up(2, 1310);
        t.up(3, 1310);
        eq(r.log.size(), at, "releaseAll 之后迟到的节拍、抬起：什么都不出");
    }

    static void stickNewConfigStopsTicks() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        push(t, 1, 15, 0, 1000, 100);
        t.setConfig(new GestureEngine.Config(), 1);
        check(!r.ticking && r.tickStarts == r.tickStops, "换参数：节拍停");
        int at = r.log.size();
        t.onTick(1200);
        eq(r.log.size(), at, "换参数后：不出东西");
    }

    static void stickTargetsAndNotKeys() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        check(t.onTarget(2050, 50) && t.onTarget(2015, 130), "分屏透传：红点、三个鼠标键算豆花的");
        // 键盘范围里摆着红点和一个键：缝里的点离红点近也只分给键（红点、鼠标键不是"最近的键"的候选）
        TouchRouter.Region area = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, 400, 200);
        TouchRouter.Region key = new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 0, 0, 100, 100);
        TouchRouter.Region[] rs = {
                new TouchRouter.Region(Proto.REGION_STICK, STICK, 200, 0, 300, 100),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 200, 120, 300, 180),
                key, area,
        };
        check(TouchRouter.nearestKey(rs, area, 250, 110) == key, "nearestKey 不选红点、鼠标键");
    }

    /**
     * 小红点按得到的是报上来那个正方形的内切圆（2026-11-01）：嵌在键里时正方形的四个角压着挨着的键（键帽沿圆弧切口），
     * 按在角上归键、按在圆里归红点；别的区域照旧按矩形。服务、App 本地分流、KeyFingers 都走 Region.contains，三处一致。
     */
    static void stickHitIsCircle() {
        TouchRouter.Region stick = new TouchRouter.Region(Proto.REGION_STICK, STICK, 200, 0, 300, 100);
        TouchRouter.Region key = new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 150, 0, 250, 100);   // 被红点压着左半截的键
        TouchRouter.Region area = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, 400, 200);
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(new TouchRouter.Region[]{stick, key, area});
        int bad = 0, in = 0;
        for (int y = 0; y < 100; y++) {
            for (int x = 200; x < 300; x++) {
                float dx = x - 250, dy = y - 50;
                boolean circle = dx * dx + dy * dy < 2500;
                if (circle) in++;
                TouchRouter.Region got = t.hit(x, y);
                TouchRouter.Region want = circle ? stick : x < 250 ? key : TouchRouter.nearestKey(new TouchRouter.Region[]{key}, area, x, y);
                if (got != want) bad++;
            }
        }
        check(bad == 0 && in > 7800 && in < 7900, "正方形里逐点：圆里（" + in + " 个点）归红点，四个角归键 / 缝里分给最近的键（错 " + bad + "）");
        check(t.hit(205, 5) == key && t.hit(250, 50) == stick && t.hit(250, 1) == stick && t.hit(299, 50) == stick,
                "左上角归键；圆心、上边正中、右边正中归红点");
        check(t.onTarget(205, 5), "角上（键）分屏透传时也算豆花的");
        TouchRouter.Region down = t.down(0, 205, 5, 100);
        check(down == key && r.log.contains("b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + Proto.BUTTON_DOWN), "在角上按下：按的是键，不是红点");
        t.up(0, 110);
        check(!r.log.contains("b" + STICK + ":" + Proto.BUTTON_DOWN), "红点没被按");
        // 不是正方形时按短边的一半当半径；别的区域类型照旧是整个矩形
        TouchRouter.Region wide = new TouchRouter.Region(Proto.REGION_STICK, STICK, 0, 0, 200, 100);
        check(wide.contains(100, 1) && !wide.contains(10, 50) && !wide.contains(160, 50), "长方形：短边一半的圆");
        TouchRouter.Region b = new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 0, 0, 100, 100);
        check(b.contains(0, 0) && b.contains(99, 99) && key.contains(150, 0), "别的区域四个角照样算");
    }

    static void stickStopsSpin() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long up = flick(t, 1000);
        check(r.pending, "惯性转着");
        int at = r.log.size();
        t.down(1, 2050, 50, up + 30);
        check(!r.pending, "碰红点：惯性马上停");
        check(r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "碰红点停：震\"刹住\"");
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        long up2 = flick(t2, 1000);
        t2.down(1, 2015, 130, up2 + 30);
        check(!r2.pending, "碰三键条：惯性马上停");
    }

    // ═══════════ 中键滚动杆（按在中键上的那根手指自己推） ═══════════

    /** log 里从 at 起所有鼠标帧的竖向滚轮加起来（1/120 格）。 */
    static int wheelSum(Rec r, int at) {
        int sum = 0;
        for (String s : r.log.subList(at, r.log.size())) {
            if (!s.startsWith("m")) continue;
            int i = s.indexOf(" w");
            if (i < 0) continue;
            int j = s.indexOf(' ', i + 2);
            sum += Integer.parseInt(s.substring(i + 2, j < 0 ? s.length() : j));
        }
        return sum;
    }

    /** 中键上按下（中键正中 (2050, 130)），挪到 (2050 + dx, 130 + dy)，再按 8ms 一拍跑 ms 毫秒。返回最后的时间。 */
    static long lever(TouchRouter t, int slot, float dx, float dy, long t0, int ms) {
        t.down(slot, 2050, 130, t0);
        t.move(slot, 2050 + dx, 130 + dy);
        t.frame(t0);
        long now = t0;
        while (now < t0 + ms) {
            now += 8;
            t.onTick(now);
        }
        return now;
    }

    static GestureEngine.Config smoothCfg() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        return c;
    }

    static void leverScrollsBothWays() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = lever(t, 1, 0, 15, 1000, 1000);   // 按住中键往下挪
        check(r.log.contains("m0 w-" + Hid.WHEEL_UNIT), "按住中键往下挪：往下翻（整格，滚轮负），同按住中键往下推红点");
        check(!r.log.contains("m0 d"), "中键滚动杆不动光标");
        check(r.log.contains("h" + GestureEngine.HAPTIC_TICK), "每格咔嗒");
        check(r.ticking && r.tickStarts == 1, "中键按着：开节拍");
        t.up(1, now + 4);
        check(!r.only("m").contains("m4"), "滚过了：松开不补中键单击");
        check(!r.ticking && r.tickStops == 1, "松开：节拍停（开停配对）");
        int at = r.log.size();
        t.onTick(now + 12);
        eq(r.log.size(), at, "松开后迟到的节拍：什么都不出");
        Rec u = new Rec();
        TouchRouter tu = stickRouter(u);
        long n2 = lever(tu, 1, 0, -15, 1000, 1000);
        check(u.log.contains("m0 w" + Hid.WHEEL_UNIT) && wheelSum(u, 0) > 0, "按住中键往上挪：往上翻");
        tu.up(1, n2 + 4);
        check(!u.only("m").contains("m4"), "往上滚过了：也不补单击");
    }

    /** 同样推 15dp、同样的节拍：中键自己推比"按住中键推红点"慢（滚动杆自己一条曲线，位移从挪出轻点容差起算），量照 TrackPoint.leverRate。 */
    static void leverSlowerThanStick() {
        for (boolean smooth : new boolean[]{false, true}) {
            GestureEngine.Config c = smooth ? smoothCfg() : new GestureEngine.Config();
            Rec a = new Rec();
            TouchRouter ta = stickRouter(a, c);
            ta.down(1, 2050, 130, 1000);   // 按住中键不动
            push(ta, 2, 0, 15, 1000, 1000);   // 推红点
            Rec b = new Rec();
            TouchRouter tb = stickRouter(b, c);
            lever(tb, 1, 0, 15, 1000, 1000);   // 中键自己推
            int wa = wheelSum(a, 0), wb = wheelSum(b, 0);
            check(wb < 0 && wb > wa, (smooth ? "连续" : "按格") + "：中键自己推比按住中键推红点慢（" + wb + " vs " + wa + "）");
            float want = TrackPoint.leverRate(15, c.tapSlopDp);
            check(Math.abs(-wb / (float) Hid.WHEEL_UNIT - want) <= 1, (smooth ? "连续" : "按格") + "：1 秒 15dp 滚了 " + (-wb / (float) Hid.WHEEL_UNIT) + " 格（应约 " + want + "）");
        }
    }

    /** 按住不挪（死区里抖、或者出了死区没出轻点容差）就松开：中键单击，一格都不滚。 */
    static void leverDeadZoneClicks() {
        for (float d : new float[]{0, 2, 4.5f}) {
            Rec r = new Rec();
            TouchRouter t = stickRouter(r, smoothCfg());   // 连续模式：小数格也出，最容易误滚
            long now = lever(t, 1, d, d == 0 ? 0 : 1, 1000, 1000);
            check(wheelSum(r, 0) == 0 && r.only("m").isEmpty(), "中键挪 " + d + "dp 按 1 秒：不滚、不先发中键");
            t.up(1, now + 4);
            eq(r.only("m"), list("m4", "m0"), "中键挪 " + d + "dp 松开：补一次中键单击");
            check(!r.ticking && r.tickStarts == 1 && r.tickStops == 1, "中键挪 " + d + "dp：节拍开停配对");
        }
        // 开着"轻点红点 = 左键"时轻点中键：照样只是中键单击（滚动杆不算轻点，不出左键）
        GestureEngine.Config tap = new GestureEngine.Config();
        tap.stickTap = true;
        Rec r = new Rec();
        TouchRouter t = stickRouter(r, tap);
        t.down(1, 2050, 130, 1000);
        t.onTick(1008);
        t.up(1, 1080);
        eq(r.only("m"), list("m4", "m0"), "开着轻点 = 左键时轻点中键：只有中键单击");
    }

    /** 挪出了轻点容差就算要滚了：按格模式还没攒满一格就松开，也不补单击（不会意外粘贴）。 */
    static void leverStartedNoClick() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = lever(t, 1, 0, 7, 1000, 40);
        eq(wheelSum(r, 0), 0, "挪 7dp、40ms：按格还没攒满一格");
        t.up(1, now + 4);
        check(r.only("m").isEmpty(), "挪出了轻点容差：松开不补中键单击");
    }

    static void leverCanceledAndReleaseAll() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = lever(t, 1, 0, 15, 1000, 300);
        t.up(1, now + 4, true);   // 手掌 / 服务收尾补报的抬起
        check(!r.only("m").contains("m4") && !r.ticking && r.tickStarts == r.tickStops, "滚动杆被取消：不补单击、节拍停");
        int at = r.log.size();
        t.onTick(now + 12);
        eq(r.log.size(), at, "被取消后迟到的节拍：什么都不出");
        Rec c = new Rec();
        TouchRouter tc = stickRouter(c);
        long n2 = lever(tc, 1, 0, 1, 1000, 300);   // 没滚过
        tc.up(1, n2 + 4, true);
        check(c.only("m").isEmpty(), "没滚过但被取消：不补单击");
        Rec a = new Rec();
        TouchRouter ta = stickRouter(a);
        long n3 = lever(ta, 1, 0, 15, 1000, 300);
        check(a.ticking, "滚动杆推着：节拍开着");
        ta.releaseAll();
        check(!a.ticking && a.tickStarts == a.tickStops, "releaseAll：节拍停（开停配对）");
        check(a.log.contains("b" + M_MIDDLE + ":" + (Proto.BUTTON_UP | Proto.BUTTON_CANCELED)) && !a.only("m").contains("m4"), "releaseAll：中键报松开（带被取消）、不补单击");
        int at2 = a.log.size();
        ta.onTick(n3 + 8);
        ta.move(1, 2050, 200);
        ta.frame(n3 + 10);
        ta.onTick(n3 + 16);
        ta.up(1, n3 + 20);
        eq(a.log.size(), at2, "releaseAll 之后迟到的节拍、移动、抬起：什么都不出");
        check(!a.ticking, "releaseAll 之后：节拍没被重新打开");
        Rec a2 = new Rec();
        TouchRouter tb = stickRouter(a2);
        long n4 = lever(tb, 1, 0, 1, 1000, 300);   // 没滚过时 releaseAll
        tb.releaseAll();
        check(a2.only("m").isEmpty(), "没滚过时 releaseAll：也不补单击");
        tb.onTick(n4 + 8);
        check(a2.only("m").isEmpty(), "之后的节拍不出东西");
    }

    /** 手指挪出中键的范围照样算（归属按下时定）：挪到所有区域外面、挪到红点上都还是滚动杆。 */
    static void leverOutsideKey() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = lever(t, 1, 0, 270, 1000, 1000);   // 挪到 (2050, 400)：什么区域都不是
        int notches = -wheelSum(r, 0) / Hid.WHEEL_UNIT;
        check(notches >= TrackPoint.LEVER_MAX - 1 && notches <= TrackPoint.LEVER_MAX + 1, "挪出中键很远：照样滚、封顶在滚动杆的最快（" + notches + " 格 / 秒）");
        t.move(1, 2050, 50);   // 挪到红点上（往上 80dp）
        t.frame(now);
        int at = r.log.size();
        for (long x = now + 8; x <= now + 300; x += 8) t.onTick(x);
        check(wheelSum(r, at) > 0 && !r.log.contains("b" + STICK + ":" + Proto.BUTTON_DOWN), "挪到红点上：还是滚动杆在滚（往上），红点没被按");
    }

    /** 触控板下沿的三键条（主界面、键盘页的触控板）：中键往上挪进触控板，照样是滚动杆、不动光标。 */
    static void leverOnPadBar() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 2);   // 密度 2：位移按 dp 算
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 0, 900, 400, 1000),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_MIDDLE, 400, 900, 600, 1000),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_RIGHT, 600, 900, 1000, 1000),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 890),
        });
        t.down(3, 500, 950, 1000);
        t.move(3, 500, 920);   // 往上 30 像素 = 15dp
        t.frame(1000);
        long now = 1000;
        for (; now < 2000; ) {
            now += 8;
            t.onTick(now);
        }
        int w = wheelSum(r, 0);
        t.move(3, 500, 650);   // 挪进触控板
        t.frame(now);
        for (long end = now + 300; now < end; ) {
            now += 8;
            t.onTick(now);
        }
        check(w > 0 && wheelSum(r, 0) > w, "三键条的中键往上挪：往上翻，挪进触控板也还是滚动杆");
        check(!r.log.contains("m0 d"), "挪进触控板不动光标");
        // 1 秒、15dp：照滚动杆那条曲线（密度 2 下也按 dp）
        float notches = TrackPoint.leverRate(15, new GestureEngine.Config().tapSlopDp);
        check(Math.abs(w / (float) Hid.WHEEL_UNIT - notches) <= 1, "密度 2：1 秒 15dp 滚了 " + w / Hid.WHEEL_UNIT + " 格（应约 " + notches + "）");
        t.up(3, now + 4);
        check(!r.only("m").contains("m4") && !r.ticking, "松开：不补单击、节拍停");
    }

    /** 同时两根手指按在中键上：只有第一根当滚动杆；第一根先抬起，留下的那根也不接手（同红点"第二根手指不理"）。 */
    static void leverSecondFingerIgnored() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r, smoothCfg());
        t.down(1, 2050, 130, 1000);   // 第一根：不动
        t.down(2, 2040, 130, 1000);
        t.move(2, 2040, 160);   // 第二根往下挪 30dp
        t.frame(1000);
        for (long now = 1008; now <= 1500; now += 8) t.onTick(now);
        eq(wheelSum(r, 0), 0, "第二根按在中键上挪：不滚");
        eq(r.tickStarts, 1, "第二根按下：不再开一次节拍");
        t.up(1, 1500);
        check(!r.ticking && r.tickStops == 1, "第一根（滚动杆）抬起：节拍停");
        check(r.only("m").isEmpty(), "还有一根按着中键：不补单击");
        t.move(2, 2040, 200);
        t.frame(1510);
        for (long now = 1518; now <= 1800; now += 8) t.onTick(now);
        eq(wheelSum(r, 0), 0, "留下的那根不接手当滚动杆");
        t.up(2, 1800);
        eq(r.only("m"), list("m4", "m0"), "两根都松了、一格没滚过：补一次中键单击");
        // 滚动杆抬起后再按下的新手指：又是滚动杆
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        t2.down(1, 2050, 130, 1000);
        t2.down(2, 2040, 130, 1000);
        t2.up(1, 1100);
        long now = lever(t2, 3, 0, 15, 1200, 600);
        check(wheelSum(r2, 0) < 0, "滚动杆抬起后新按下的手指：又是滚动杆");
        t2.up(3, now);
        t2.up(2, now + 4);
        check(!r2.only("m").contains("m4") && r2.tickStarts == r2.tickStops && !r2.ticking, "滚过了不补单击；节拍开停配对");
    }

    /** 红点、中键滚动杆共用一套节拍：任一按着就开着，两个都松了才停；交错开停都配对。 */
    static void leverTicksPairWithStick() {
        // 先中键、后红点；先松中键、后松红点
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);
        check(r.ticking && r.tickStarts == 1, "中键按下：开节拍");
        t.down(2, 2050, 50, 1010);
        eq(r.tickStarts, 1, "红点再按下：节拍已经开着，不重开");
        t.up(1, 1100);
        check(r.ticking && r.tickStops == 0, "中键松了、红点还按着：节拍照开");
        t.up(2, 1200);
        check(!r.ticking && r.tickStarts == 1 && r.tickStops == 1, "红点也松了：节拍停");
        // 先红点、后中键；先松红点、后松中键
        Rec q = new Rec();
        TouchRouter tq = stickRouter(q);
        tq.down(2, 2050, 50, 1000);
        tq.down(1, 2050, 130, 1010);
        eq(q.tickStarts, 1, "红点先按、中键后按：只开一次");
        tq.up(2, 1100);
        check(q.ticking && q.tickStops == 0, "红点松了、中键还按着：节拍照开");
        tq.move(1, 2050, 150);
        tq.frame(1110);
        int at = q.log.size();
        for (long x = 1118; x <= 1600; x += 8) tq.onTick(x);
        check(wheelSum(q, at) < 0, "红点松了以后：中键滚动杆照样按节拍滚");
        tq.up(1, 1600);
        check(!q.ticking && q.tickStarts == 1 && q.tickStops == 1, "中键也松了：节拍停");
        int at2 = q.log.size();
        tq.onTick(1608);
        eq(q.log.size(), at2, "都松了以后迟到的节拍：什么都不出");
        // 只按右键不开节拍；按左键开（左键杆，2026-11-01 晚起是第三个来源），松了停
        Rec l = new Rec();
        TouchRouter tl = stickRouter(l);
        tl.down(3, 2085, 130, 1000);
        check(!l.ticking && l.tickStarts == 0, "只按右键：不开节拍");
        tl.down(1, 2015, 130, 1000);
        check(l.ticking && l.tickStarts == 1, "按左键：开节拍（左键杆）");
        tl.up(1, 1100);
        check(!l.ticking && l.tickStops == 1, "左键松了：节拍停");
    }

    /**
     * 随机交错：红点、中键（含第二根）、左键、触控板上的手指乱序按下 / 挪 / 抬起 / 取消，夹着 releaseAll、换参数、节拍、帧。
     * 每一步之后：节拍开着 ⇔ 红点、中键滚动杆、左键杆（2026-11-01 晚起）有一个按着、杆没让位；开停配对；三个都没按着时节拍不出任何滚轮、位移。
     * 左键杆让位的模型：没开始时出现了光标位移（只能是别的来源）；更细的三个来源交错见 {@link #leftLeverTicksRandom}。
     */
    static void leverTicksRandom() {
        java.util.Random rnd = new java.util.Random(20261101L);
        int bad = 0, steps = 0;
        for (int seq = 0; seq < 300; seq++) {
            Rec r = new Rec();
            TouchRouter t = stickRouter(r, rnd.nextBoolean() ? smoothCfg() : new GestureEngine.Config());
            int[] where = new int[6];   // 每个 slot 按在哪：0 没按，1 红点，2 中键，3 左键，4 触控板
            int stickOwner = -1, leverOwner = -1, leftOwner = -1;
            // 滚动杆的模型：按下点、挪出过轻点容差没有（= 开始了）、让位了没有（没开始时出现了滚轮 = 别的来源先滚了）
            float leverX = 0, leverY = 0;
            boolean leverStarted = false, leverYielded = false;
            // 左键杆的模型：同上，让位看的是光标位移
            float leftX = 0, leftY = 0;
            boolean leftStarted = false, leftYielded = false;
            float slop = new GestureEngine.Config().tapSlopDp;
            long now = 1000;
            for (int step = 0; step < 80; step++) {
                steps++;
                now += 1 + rnd.nextInt(12);
                int slot = rnd.nextInt(6);
                int op = rnd.nextInt(10);
                int logAt = r.log.size();
                if (op <= 2 && where[slot] == 0) {
                    int kind = 1 + rnd.nextInt(4);
                    float[][] at = {{2050, 50}, {2050, 130}, {2015, 130}, {500, 500}};
                    t.down(slot, at[kind - 1][0], at[kind - 1][1], now);
                    where[slot] = kind;
                    if (kind == 1 && stickOwner < 0) stickOwner = slot;
                    if (kind == 2 && leverOwner < 0) {
                        leverOwner = slot;
                        leverX = at[1][0];
                        leverY = at[1][1];
                        leverStarted = leverYielded = false;
                    }
                    if (kind == 3 && leftOwner < 0) {
                        leftOwner = slot;
                        leftX = at[2][0];
                        leftY = at[2][1];
                        leftStarted = leftYielded = false;
                    }
                    t.frame(now);
                } else if (op <= 4 && where[slot] != 0) {
                    float mx = 2050 + rnd.nextInt(41) - 20, my = 130 + rnd.nextInt(41) - 20;
                    t.move(slot, mx, my);
                    if (slot == leverOwner && !leverYielded && Math.hypot(mx - leverX, my - leverY) > slop) leverStarted = true;
                    if (slot == leftOwner && !leftYielded && Math.hypot(mx - leftX, my - leftY) > slop) leftStarted = true;
                    t.frame(now);
                } else if (op <= 6 && where[slot] != 0) {
                    t.up(slot, now, rnd.nextInt(3) == 0);
                    where[slot] = 0;
                    if (slot == stickOwner) stickOwner = -1;
                    if (slot == leverOwner) leverOwner = -1;
                    if (slot == leftOwner) leftOwner = -1;
                } else if (op == 7) {
                    int at = r.log.size();
                    t.onTick(now);
                    if (stickOwner < 0 && leverOwner < 0 && leftOwner < 0) {
                        for (String s : r.log.subList(at, r.log.size())) if (s.startsWith("m")) bad++;
                    }
                } else if (op == 8 && rnd.nextInt(8) == 0) {
                    if (rnd.nextBoolean()) t.releaseAll();
                    else t.setConfig(rnd.nextBoolean() ? smoothCfg() : new GestureEngine.Config(), 1);
                    java.util.Arrays.fill(where, 0);   // 收尾以后这些手指的抬起什么都不出，当作都没按着
                    stickOwner = leverOwner = leftOwner = -1;
                } else {
                    t.onTick(now);
                }
                if (leverOwner >= 0 && !leverStarted && !leverYielded) {   // 滚动杆没开始时出了滚轮：只能是别的来源，滚动杆让位
                    for (String s : r.log.subList(logAt, r.log.size())) {
                        if (s.startsWith("m") && (s.contains(" w") || s.contains(" p"))) leverYielded = true;
                    }
                }
                if (leftOwner >= 0 && !leftStarted && !leftYielded) {   // 左键杆没开始时光标动了：只能是别的来源，左键杆让位
                    for (String s : r.log.subList(logAt, r.log.size())) {
                        if (s.startsWith("m") && s.contains(" d")) leftYielded = true;
                    }
                }
                boolean want = stickOwner >= 0 || (leverOwner >= 0 && !leverYielded) || (leftOwner >= 0 && !leftYielded);
                if (r.ticking != want) bad++;
                if (r.tickStarts - r.tickStops != (r.ticking ? 1 : 0)) bad++;
            }
            for (int s = 0; s < 6; s++) if (where[s] != 0) t.up(s, now + 1, false);
            if (r.ticking || r.tickStarts != r.tickStops) bad++;
        }
        check(bad == 0, "随机交错 300 组 × 80 步（" + steps + " 步）：节拍开着 ⇔ 红点 / 中键滚动杆 / 左键杆（没让位）按着、开停配对、都没按着时节拍不出东西（错 " + bad + "）");
    }

    static void leverModes() {
        Rec n = new Rec();
        TouchRouter tn = stickRouter(n);
        lever(tn, 1, 0, 15, 1000, 1000);
        boolean whole = true, any = false;
        for (String s : n.only("m0 w")) {
            any = true;
            whole &= Integer.parseInt(s.substring(4)) % Hid.WHEEL_UNIT == 0;
        }
        check(any && whole, "按格模式：滚动杆每次整格");
        Rec c = new Rec();
        TouchRouter tc = stickRouter(c, smoothCfg());
        lever(tc, 1, 0, 15, 1000, 1000);
        boolean partial = false;
        for (String s : c.only("m0 w")) partial |= Integer.parseInt(s.substring(4)) % Hid.WHEEL_UNIT != 0;
        check(partial, "连续模式：滚动杆出小数格");
        int ticks = 0;
        for (String s : c.log) if (s.equals("h" + GestureEngine.HAPTIC_TICK)) ticks++;
        check(Math.abs(ticks - (-wheelSum(c, 0)) / Hid.WHEEL_UNIT) <= 1, "连续模式：每滚过一格的距离咔嗒一下（" + ticks + " 下）");
    }

    /** 同时推红点、或者单指划触控板：各出各的，滚轮叠加。 */
    static void leverAddsToStickAndPad() {
        GestureEngine.Config c = smoothCfg();
        Rec one = new Rec();
        TouchRouter t1 = stickRouter(one, c);
        lever(t1, 1, 0, 15, 1000, 1000);
        Rec both = new Rec();
        TouchRouter t2 = stickRouter(both, c);
        t2.down(1, 2050, 130, 1000);
        t2.move(1, 2050, 145);   // 中键往下 15dp
        t2.down(2, 2050, 50, 1000);
        t2.move(2, 2050, 65);   // 红点也往下 15dp
        t2.frame(1000);
        for (long now = 1008; now <= 2000; now += 8) t2.onTick(now);
        Rec st = new Rec();   // 只有红点那一路：按住中键不动、推红点
        TouchRouter ts = stickRouter(st, c);
        ts.down(1, 2050, 130, 1000);
        push(ts, 2, 0, 15, 1000, 1000);
        int w1 = wheelSum(one, 0), w2 = wheelSum(both, 0), ws = wheelSum(st, 0);
        check(Math.abs(w2 - (w1 + ws)) <= 2, "中键和红点一起往下推：滚轮叠加（滚动杆 " + w1 + " + 红点 " + ws + " → " + w2 + "）");
        Rec pad = new Rec();
        TouchRouter tp = stickRouter(pad, c);
        tp.down(1, 2050, 130, 1000);
        tp.move(1, 2050, 145);
        tp.frame(1000);
        tp.down(0, 500, 300, 1000);
        long now = 1000;
        for (int i = 1; i <= 30; i++) {
            tp.move(0, 500, 300 + i * 10);
            now = 1000 + i * 16;
            tp.frame(now);
            tp.onTick(now);
        }
        int wp = wheelSum(pad, 0);
        Rec lone = new Rec();
        TouchRouter tl = stickRouter(lone, c);
        lever(tl, 1, 0, 15, 1000, (int) (now - 1000));
        check(wp < wheelSum(lone, 0) && !pad.log.contains("m0 d"), "中键滚动杆 + 单指划触控板：两个都滚（" + wp + " vs 只有滚动杆 " + wheelSum(lone, 0) + "），不动光标");
    }

    /** 惯性转着时按中键：先停惯性（震"刹住"），之后中键照样当滚动杆。 */
    static void leverStopsSpin() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long up = flick(t, 1000);
        check(r.pending, "惯性转着");
        int at = r.log.size();
        long now = lever(t, 1, 0, -15, up + 30, 600);
        check(!r.pending, "按中键：惯性马上停");
        check(r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "按中键停：震\"刹住\"");
        check(wheelSum(r, at) > 0, "停了以后中键往上挪：往上翻（滚动杆照常）");
        t.up(1, now);
        check(!r.only("m").contains("m4"), "滚过了：不补单击");
    }

    static void leverNewConfigStops() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = lever(t, 1, 0, 15, 1000, 200);
        t.setConfig(new GestureEngine.Config(), 1);
        check(!r.ticking && r.tickStarts == r.tickStops, "换参数：滚动杆的节拍停");
        int at = r.log.size();
        t.onTick(now + 8);
        t.move(1, 2050, 200);
        t.frame(now + 10);
        t.onTick(now + 16);
        t.up(1, now + 20);
        eq(r.log.size(), at, "换参数后迟到的节拍、移动、抬起：什么都不出（不补单击）");
    }

    static void leverIdlePause() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 0);
        t.move(1, 2050, 145);
        t.frame(0);
        long now = 0;
        while (r.ticking && now < 30000) {
            now += 8;
            t.onTick(now);
        }
        check(!r.ticking && now > TrackPoint.IDLE_PAUSE_MS && now < TrackPoint.IDLE_PAUSE_MS + 20, "中键按着 20 秒没事件：停节拍（" + now + "ms）");
        eq(r.tickStops, 1, "暂停：停了一次");
        int w = wheelSum(r, 0);
        t.move(1, 2050, 146);
        t.frame(now + 10);
        check(r.ticking && r.tickStarts == 2, "再动：重新开节拍");
        for (long x = now + 18; x <= now + 1018; x += 8) t.onTick(x);   // 滚动杆慢：多给一秒攒满一格
        check(wheelSum(r, 0) < w, "接着滚");
        t.up(1, now + 30);
        check(!r.ticking && r.tickStarts == r.tickStops && !r.only("m").contains("m4"), "松开：节拍停、滚过了不补单击");
    }

    /** log 里从 at 起所有鼠标帧的横向滚轮加起来（1/120 格）。 */
    static int panSum(Rec r, int at) {
        int sum = 0;
        for (String s : r.log.subList(at, r.log.size())) {
            if (!s.startsWith("m")) continue;
            int i = s.indexOf(" p");
            if (i < 0) continue;
            int j = s.indexOf(' ', i + 2);
            sum += Integer.parseInt(s.substring(i + 2, j < 0 ? s.length() : j));
        }
        return sum;
    }

    /**
     * 按着中键当修饰键（拇指不打算动、慢慢往右下漂），另一根手指往下推红点：拇指漂出轻点容差也不另外滚（滚动杆让位），
     * 滚轮和拇指不漂时一模一样（= 没有滚动杆以前）；红点松了、拇指接着按 2 秒还在漂，什么都不出；拇指松开不补中键单击。
     */
    static void leverYieldsToStick() {
        for (boolean smooth : new boolean[]{false, true}) {
            String mode = smooth ? "连续" : "按格";
            GestureEngine.Config c = smooth ? smoothCfg() : new GestureEngine.Config();
            int base = 0;
            for (float drift : new float[]{0, 4, 7, 10, 30}) {
                Rec r = new Rec();
                TouchRouter t = stickRouter(r, c);
                t.down(1, 2050, 130, 1000);   // 拇指按住中键
                t.frame(1000);
                t.down(2, 2050, 50, 1010);
                t.move(2, 2050, 65);   // 红点往下 15dp
                t.frame(1010);
                for (long now = 1018; now <= 2000; now += 8) {
                    float d = drift * Math.min(1f, (now - 1018) / 600f);
                    t.move(1, 2050 + d, 130 + d * 0.3f);
                    t.frame(now);
                    t.onTick(now);
                }
                int w = wheelSum(r, 0), p = panSum(r, 0);
                if (drift == 0) base = w;
                check(w == base && w < 0 && p == 0, mode + "：拇指漂 " + drift + "dp + 推红点往下：只有红点在滚（竖 " + w + "、横 " + p + "，拇指不漂时竖 " + base + "）");
                t.up(2, 2000);
                check(!r.ticking && r.tickStarts == 1 && r.tickStops == 1, mode + "：拇指漂 " + drift + "dp，红点松了：节拍停（滚动杆让位了，不留着）");
                int at = r.log.size();
                for (long now = 2008; now <= 4000; now += 8) {   // 拇指接着按 2 秒、接着漂（宿主不该再调节拍，调了也不出）
                    t.move(1, 2050 + drift + (now - 2000) / 100f, 130 + (now - 2000) / 50f);
                    t.frame(now);
                    t.onTick(now);
                }
                check(wheelSum(r, at) == 0 && panSum(r, at) == 0 && !r.ticking, mode + "：拇指漂 " + drift + "dp，红点松了以后拇指再挪 40dp：什么都不滚、不开节拍");
                t.up(1, 4004);
                check(!r.only("m").contains("m4") && r.tickStarts == r.tickStops, mode + "：拇指漂 " + drift + "dp 松开：滚过了不补中键单击、节拍开停配对");
            }
        }
        // 红点先按着（在挪光标），拇指再按中键、漂：红点换成滚动模式先滚了，滚动杆让位
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(2, 2050, 50, 1000);
        t.move(2, 2050, 65);
        t.frame(1000);
        for (long now = 1008; now <= 1200; now += 8) t.onTick(now);
        t.down(1, 2050, 130, 1200);
        for (long now = 1208; now <= 2200; now += 8) {
            t.move(1, 2050 + Math.min(10f, (now - 1208) / 60f), 130);
            t.frame(now);
            t.onTick(now);
        }
        check(wheelSum(r, 0) < 0 && panSum(r, 0) == 0, "红点先按、拇指后按中键再漂 10dp：只有红点滚（横 " + panSum(r, 0) + "）");
    }

    /** 按着中键当修饰键、另一根手指单指划触控板（中键滚动）：拇指漂了也不另外滚；拇指松开不补中键单击。 */
    static void leverYieldsToPad() {
        for (boolean smooth : new boolean[]{false, true}) {
            String mode = smooth ? "连续" : "按格";
            GestureEngine.Config c = smooth ? smoothCfg() : new GestureEngine.Config();
            int base = 0;
            for (float drift : new float[]{0, 7, 10, 30}) {
                Rec r = new Rec();
                TouchRouter t = stickRouter(r, c);
                t.down(1, 2050, 130, 1000);
                t.frame(1000);
                t.down(0, 500, 300, 1010);
                t.frame(1010);
                long now = 1010;
                boolean stopped = false;
                for (int i = 1; i <= 60; i++) {
                    now = 1010 + i * 16;
                    float d = drift * Math.min(1f, i / 40f);
                    t.move(0, 500, 300 + i * 5);   // 触控板往下划 300dp
                    t.move(1, 2050 + d, 130);
                    t.frame(now);
                    if (wheelSum(r, 0) != 0 && !r.ticking) stopped = true;
                    t.onTick(now);
                    t.onTick(now + 8);
                }
                int w = wheelSum(r, 0), p = panSum(r, 0);
                if (drift == 0) base = w;
                check(w == base && w != 0 && p == 0, mode + "：拇指漂 " + drift + "dp + 划触控板：只有触控板在滚（竖 " + w + "、横 " + p + "，拇指不漂时竖 " + base + "）");
                check(stopped, mode + "：拇指漂 " + drift + "dp：触控板一滚，滚动杆让位、节拍马上停（红点没按着，节拍没用了）");
                t.up(0, now + 10);
                int at = r.log.size();
                t.move(1, 2050 + drift + 20, 160);
                t.frame(now + 20);
                for (long x = now + 28; x <= now + 600; x += 8) t.onTick(x);
                check(wheelSum(r, at) == 0 && panSum(r, at) == 0, mode + "：拇指漂 " + drift + "dp，触控板那根松了以后拇指再挪：不滚");
                t.up(1, now + 610);
                check(!r.only("m").contains("m4") && !r.ticking && r.tickStarts == 1 && r.tickStops == 1,
                        mode + "：拇指漂 " + drift + "dp 松开：不补中键单击、节拍开停各一次");
            }
        }
    }

    /** 中键按着、滚动杆还没开始时开合缩放 / 滚轮动作出了滚轮：滚动杆同样让位。 */
    static void leverYieldsToZoomAndWheel() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2050, 130, 1000);
        t.down(3, 400, 500, 1000);
        t.down(4, 600, 500, 1000);
        for (int i = 1; i <= 8; i++) {   // 两指张开 = 开合缩放（Ctrl + 滚轮）
            t.move(3, 400 - i * 10, 500);
            t.move(4, 600 + i * 10, 500);
            t.frame(1000 + i * 16);
        }
        check(wheelSum(r, 0) != 0 && !r.ticking, "中键按着时开合：滚动杆让位、节拍停");
        t.up(3, 1200);
        t.up(4, 1200);
        int at = r.log.size();
        t.move(1, 2050, 160);   // 拇指再往下挪 30dp
        t.frame(1210);
        for (long x = 1218; x <= 1800; x += 8) t.onTick(x);
        eq(wheelSum(r, at), 0, "开合以后拇指挪：不滚");
        t.up(1, 1800);
        check(!r.only("m").contains("m4"), "开合过：不补中键单击");
        Rec w = new Rec();
        TouchRouter tw = stickRouter(w);
        tw.down(1, 2050, 130, 1000);
        tw.wheel(1);   // 动作"滚轮向上"
        check(!w.ticking, "中键按着时按滚轮动作：滚动杆让位、节拍停");
        int at2 = w.log.size();
        tw.move(1, 2050, 160);
        tw.frame(1010);
        for (long x = 1018; x <= 1500; x += 8) tw.onTick(x);
        eq(wheelSum(w, at2), 0, "滚轮动作以后拇指挪：不滚");
        tw.up(1, 1500);
        check(w.tickStarts == 1 && w.tickStops == 1, "节拍开停配对");
    }

    /** 先用滚动杆滚起来（挪出了轻点容差），再加红点：不让位，两个叠加；挪出了容差、按格还没攒满一格时加红点也一样。 */
    static void leverFirstThenStickAdds() {
        GestureEngine.Config c = smoothCfg();
        Rec lone = new Rec();
        TouchRouter tl = stickRouter(lone, c);
        lever(tl, 1, 0, 15, 1000, 1000);
        Rec both = new Rec();
        TouchRouter tb = stickRouter(both, c);
        long now = lever(tb, 1, 0, 15, 1000, 300);   // 滚动杆先单独滚 300ms
        int w300 = wheelSum(both, 0);
        tb.down(2, 2050, 50, now);
        tb.move(2, 2050, 65);
        tb.frame(now);
        while (now < 2000) {
            now += 8;
            tb.onTick(now);
        }
        Rec ref = new Rec();   // 红点那一路从头推 696ms 该出多少：按住中键不动、红点从头推 696ms
        TouchRouter tr = stickRouter(ref, c);
        tr.down(1, 2050, 130, 1000);
        push(tr, 2, 0, 15, 1000, 696);
        int wl = wheelSum(lone, 0), wb = wheelSum(both, 0), wr = wheelSum(ref, 0);
        check(w300 < 0 && wb < wl && Math.abs(wb - (wl + wr)) <= 3,
                "滚动杆先滚、后加红点：不让位，叠加（只有滚动杆 " + wl + " + 红点 " + wr + "，一起 " + wb + "）");
        tb.up(2, now);
        check(both.ticking, "红点松了、滚动杆还按着：节拍照开");
        int at = both.log.size();
        for (long x = now + 8; x <= now + 200; x += 8) tb.onTick(x);
        check(wheelSum(both, at) < 0, "红点松了：滚动杆接着滚");
        tb.up(1, now + 200);
        check(!both.ticking && both.tickStarts == both.tickStops, "都松了：节拍停、配对");
        // 按格：挪了 12dp（挪出了轻点容差、也出了滚动杆的死区）、40ms 还没攒满一格，这时加红点：滚动杆已经开始了，不让位
        Rec n = new Rec();
        TouchRouter tn = stickRouter(n);
        long n0 = lever(tn, 1, 0, 12, 1000, 40);
        eq(wheelSum(n, 0), 0, "按格：滚动杆挪 12dp、40ms 还没出滚轮");
        tn.down(2, 2050, 50, n0);
        tn.move(2, 2050, 65);
        tn.frame(n0);
        for (long x = n0 + 8; x <= n0 + 1000; x += 8) tn.onTick(x);
        tn.up(2, n0 + 1000);
        check(n.ticking, "滚动杆开始了以后红点先出了滚轮：滚动杆不让位，节拍照开");
        int at3 = n.log.size();
        for (long x = n0 + 1008; x <= n0 + 2000; x += 8) tn.onTick(x);
        check(wheelSum(n, at3) < 0, "红点松了：滚动杆接着滚（12dp，爬行）");
        tn.up(1, n0 + 2000);
    }

    // ═══════════ 小红点推光标的加速、最快速度（协议 23） ═══════════

    /** 经真 ConfigCodec 写一遍、读回来（同 App → 服务那一路），密度 1。 */
    static GestureEngine.Config viaCodec(GestureEngine.Config c) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            ConfigCodec.write(new java.io.DataOutputStream(b), c, 1f);
            GestureEngine.Config d = new GestureEngine.Config();
            ConfigCodec.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(b.toByteArray())), d);
            return d;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 设置里的"加速""最快速度"经 CMD_CONFIG 到服务的 TouchRouter：红点推到底、左键杆推到底 1 秒走的 = 平台 + 加速 × (最快 − 平台)；
     * 加速 0% 时推到底和推一半一样快、最快速度不起作用；默认值时和以前一样（最快 1200）。
     */
    static void stickAccelThroughRouter() {
        float[][] settings = {{1f, 1200f}, {0f, 3000f}, {0f, 500f}, {0.5f, 2000f}, {1f, 3000f}, {0.25f, 500f}};
        for (float[] st : settings) {
            GestureEngine.Config c = new GestureEngine.Config();
            c.stickInertia = false;   // 关掉负惯性：起步不多冲一截，1 秒正好按曲线算
            c.stickAccel = st[0];
            c.stickMaxSpeed = st[1];
            GestureEngine.Config d = viaCodec(c);
            check(d.stickAccel == st[0] && d.stickMaxSpeed == st[1], "经 CMD_CONFIG 往返：加速 " + st[0] + "、最快 " + st[1]);
            float top = TrackPoint.MOVE_PLATEAU + st[0] * (st[1] - TrackPoint.MOVE_PLATEAU);
            Rec a = new Rec();
            push(stickRouter(a, d), 2, 200, 0, 1000, 1000);   // 推到很远：封顶
            check(Math.abs(a.dxSum - top) <= 2 && a.dySum == 0, "加速 " + st[0] + "、最快 " + st[1] + "：红点推到底 1 秒走 " + a.dxSum + "（应约 " + top + "）");
            Rec b = new Rec();
            leftLever(stickRouter(b, d), 1, 0, 270, 1000, 1000);
            check(Math.abs(b.dySum - top) <= 2 && b.dxSum == 0 && b.movesWithoutLeft == 0,
                    "加速 " + st[0] + "、最快 " + st[1] + "：左键杆推到底 1 秒拖 " + b.dySum + "（应约 " + top + "，都带着左键）");
            Rec h = new Rec();
            push(stickRouter(h, d), 2, TrackPoint.HALF_DP, 0, 1000, 1000);   // 推一半：平台，不跟这两项
            check(Math.abs(h.dxSum - TrackPoint.MOVE_PLATEAU) <= 1, "加速 " + st[0] + "、最快 " + st[1] + "：推一半照旧是平台（" + h.dxSum + "）");
        }
        // 默认值（加速 100%、最快 1200）经 CMD_CONFIG 以后，红点 / 左键杆出的报文序列和不带这两项时（新建的 Config）逐条一样
        GestureEngine.Config def = viaCodec(new GestureEngine.Config());
        boolean same = true;
        for (float m : new float[]{4, 10, 15, 20, 25, 32, 60}) {
            Rec a = new Rec(), b = new Rec();
            push(stickRouter(a), 2, m, m / 3, 1000, 700);
            push(stickRouter(b, def), 2, m, m / 3, 1000, 700);
            same &= a.log.equals(b.log) && a.dxSum == b.dxSum && a.dySum == b.dySum;
            Rec c1 = new Rec(), c2 = new Rec();
            leftLever(stickRouter(c1), 1, -(slop() + m), 0, 1000, 700);
            leftLever(stickRouter(c2, def), 1, -(slop() + m), 0, 1000, 700);
            same &= c1.log.equals(c2.log) && c1.dxSum == c2.dxSum;
        }
        check(same, "默认值经 CMD_CONFIG：红点、左键杆出的报文和默认 Config 逐条一样");
    }

    /** 加速、最快速度不管中键滚动（按住中键推红点、中键滑动杆）和触控板：换了这两项，这几样出的报文逐条一样。 */
    static void stickAccelLeavesScrollAndPad() {
        GestureEngine.Config base = new GestureEngine.Config();
        GestureEngine.Config odd = new GestureEngine.Config();
        odd.stickAccel = 0f;
        odd.stickMaxSpeed = 3000f;
        GestureEngine.Config odd2 = new GestureEngine.Config();
        odd2.stickAccel = 1f;
        odd2.stickMaxSpeed = 3000f;
        boolean same = true;
        for (GestureEngine.Config o : new GestureEngine.Config[]{odd, odd2}) {
            for (float m : new float[]{8, 20, 40}) {
                // 按住中键推红点 = 滚动
                Rec a = new Rec(), b = new Rec();
                for (Object[] run : new Object[][]{{a, base}, {b, o}}) {
                    TouchRouter t = stickRouter((Rec) run[0], (GestureEngine.Config) run[1]);
                    t.down(3, 2050, 130, 1000);   // 中键
                    t.frame(1000);
                    push(t, 2, 0, m, 1000, 800);
                }
                same &= a.log.equals(b.log) && wheelSum(a, 0) != 0;
                // 中键滑动杆（按在中键上的那根手指自己推）
                Rec c = new Rec(), d = new Rec();
                for (Object[] run : new Object[][]{{c, base}, {d, o}}) {
                    TouchRouter t = stickRouter((Rec) run[0], (GestureEngine.Config) run[1]);
                    t.down(3, 2050, 130, 1000);
                    t.move(3, 2050, 130 + slop() + m);
                    t.frame(1000);
                    for (long x = 1008; x <= 1800; x += 8) t.onTick(x);
                    t.up(3, 1800);
                }
                same &= c.log.equals(d.log) && wheelSum(c, 0) != 0;
                // 触控板单指划
                Rec e = new Rec(), f = new Rec();
                for (Object[] run : new Object[][]{{e, base}, {f, o}}) {
                    TouchRouter t = stickRouter((Rec) run[0], (GestureEngine.Config) run[1]);
                    t.down(4, 300, 300, 1000);
                    for (int i = 1; i <= 30; i++) {
                        t.move(4, 300 + i * m / 4, 300 + i);
                        t.frame(1000 + i * 8);
                    }
                    t.up(4, 1300);
                }
                same &= e.log.equals(f.log) && e.dxSum > 0;
            }
        }
        check(same, "加速 / 最快速度换了：按住中键推红点、中键滑动杆、触控板出的报文逐条一样（只管推光标）");
    }

    // ═══════════ 左键杆（按在左键上的那根手指自己推光标 = 拖动） ═══════════

    /** 左键上按下（左键正中 (2015, 130)），挪到 (2015 + dx, 130 + dy)，再按 8ms 一拍跑 ms 毫秒。返回最后的时间。 */
    static long leftLever(TouchRouter t, int slot, float dx, float dy, long t0, int ms) {
        t.down(slot, 2015, 130, t0);
        t.move(slot, 2015 + dx, 130 + dy);
        t.frame(t0);
        long now = t0;
        while (now < t0 + ms) {
            now += 8;
            t.onTick(now);
        }
        return now;
    }

    static float slop() {
        return new GestureEngine.Config().tapSlopDp;
    }

    static String last(List<String> l) {
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    /** 按下马上按住左键；轻点 = 单击、连点两下 = 双击；在轻点容差里挪着按多久都不动光标，松开就是一次按下 / 松开。 */
    static void leftLeverTapClicks() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2015, 130, 1000);
        eq(r.only("m"), list("m1"), "按下左键：马上按住左键");
        check(r.ticking && r.tickStarts == 1, "按下左键：开节拍（左键杆）");
        t.onTick(1008);
        t.up(1, 1080);
        eq(r.only("m"), list("m1", "m0"), "轻点左键：单击");
        check(!r.ticking && r.tickStops == 1, "松开：节拍停");
        t.down(1, 2015, 130, 1200);
        t.onTick(1208);
        t.up(1, 1260);
        t.down(2, 2016, 131, 1320);
        t.onTick(1328);
        t.up(2, 1380);
        eq(r.only("m"), list("m1", "m0", "m1", "m0", "m1", "m0"), "再连点两下：双击照旧（一个位移都没有）");
        check(!r.ticking && r.tickStarts == 3 && r.tickStops == 3, "三次按下：节拍开停各三次");
        for (float d : new float[]{0, 2, 4.5f, 5.5f}) {
            for (boolean smooth : new boolean[]{false, true}) {
                GestureEngine.Config c = smooth ? smoothCfg() : new GestureEngine.Config();
                c.stickTap = true;   // 开着"轻点红点 = 左键"也不另外多点一下
                Rec q = new Rec();
                TouchRouter tq = stickRouter(q, c);
                long now = leftLever(tq, 1, d, d == 0 ? 0 : 1, 1000, 1000);
                check(q.dxSum == 0 && q.dySum == 0, "左键挪 " + d + "dp（轻点容差里）按 1 秒：不动光标");
                tq.up(1, now + 4);
                eq(q.only("m"), list("m1", "m0"), "左键挪 " + d + "dp 松开：只是一次按下 / 松开（单击）");
                check(!q.ticking && q.tickStarts == 1 && q.tickStops == 1, "左键挪 " + d + "dp：节拍开停配对");
            }
        }
        // 点左键时指肚滚出容差一点（离按下点容差 + 0.2 到容差 + 死区 − 0.1dp，8–40ms 滚到，按住 200ms 松开）：默认参数（负惯性开）下
        // 位移从容差那一圈起算还在死区里，负惯性放大起步也不越过死区——照样只是一次单击（审查后加的）
        int rolls = 0, rollBad = 0;
        for (boolean smooth : new boolean[]{false, true}) {
            GestureEngine.Config c = smooth ? smoothCfg() : new GestureEngine.Config();
            check(c.stickInertia, "默认参数负惯性开着（下面这组测的就是它）");
            for (float over = 0.2f; over < TrackPoint.DEAD_DP - 0.05f; over += 0.2f) {
                for (int rampMs = 8; rampMs <= 40; rampMs += 8) {
                    Rec q = new Rec();
                    TouchRouter tq = stickRouter(q, c);
                    tq.down(1, 2015, 130, 1000);
                    tq.frame(1000);
                    long now = 1000;
                    while (now < 1200) {
                        now += 8;
                        float k = (slop() + over) * Math.min(1f, (now - 1000) / (float) rampMs);
                        tq.move(1, 2015 + k, 130);
                        tq.frame(now);
                        tq.onTick(now);
                    }
                    tq.up(1, now + 4);
                    rolls++;
                    if (q.dxSum != 0 || q.dySum != 0 || !q.only("m").equals(list("m1", "m0")) || q.ticking) rollBad++;
                }
            }
        }
        check(rollBad == 0, "点左键时指肚滚出容差、还在容差 + 死区以内（" + rolls + " 种，负惯性开）：只是一次单击，不动光标（错 " + rollBad + "）");
    }

    /** 挪出轻点容差：按着左键推光标 = 拖动；位移都带着左键，不震、不发颗粒（持续轻震是 App 按左键的按下 / 松开做的）；松开左键松开、节拍停。 */
    static void leftLeverDrags() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.texture = true;
        c.stickInertia = false;
        float s = c.tapSlopDp;
        Rec r = new Rec();
        TouchRouter t = stickRouter(r, c);
        long now = leftLever(t, 1, s + 15, 0, 1000, 1000);   // 往右挪到离按下点容差 + 15dp
        float want = TrackPoint.dragLeverSpeed(s + 15, s) * (now - 1000) / 1000f;
        check(Math.abs(r.dxSum - want) <= 1 && r.dySum == 0, "左键往右挪出容差 15dp 按 1 秒：光标往右走 " + r.dxSum + "（应约 " + want + "）");
        check(r.log.contains("m1 d") && r.movesWithoutLeft == 0, "左键杆推出来的位移都带着左键：拖动");
        check(r.only("h").isEmpty(), "左键杆推着：不发颗粒、不震（颗粒开着也一样）");
        check(wheelSum(r, 0) == 0 && panSum(r, 0) == 0, "左键杆不滚");
        check(r.ticking && r.tickStarts == 1, "推着：节拍开着");
        t.up(1, now + 4);
        eq(last(r.only("m")), "m0", "松开：左键松开（拖动结束）");
        check(!r.ticking && r.tickStops == 1, "松开：节拍停");
        int at = r.log.size();
        t.onTick(now + 12);
        eq(r.log.size(), at, "松开后迟到的节拍：什么都不出");
        Rec u = new Rec();
        TouchRouter tu = stickRouter(u, c);
        long n2 = leftLever(tu, 1, 0, -(s + 15), 1000, 600);
        check(u.dySum < 0 && u.dxSum == 0 && u.movesWithoutLeft == 0, "左键往上挪：往上拖（" + u.dySum + "）");
        tu.move(1, 2015 + 1, 130 - 1);   // 挪回按下点附近：停
        tu.frame(n2);
        int y = u.dySum;
        for (long x = n2 + 8; x <= n2 + 500; x += 8) tu.onTick(x);
        check(u.dySum == y && u.ticking, "挪回按下点附近：光标停（左键还按着、节拍照开）");
        tu.move(1, 2015, 130 + s + 15);
        tu.frame(n2 + 500);
        for (long x = n2 + 508; x <= n2 + 1000; x += 8) tu.onTick(x);
        check(u.dySum > y, "再往下推：接着拖（往下）");
        tu.up(1, n2 + 1000);
        eq(last(u.only("m")), "m0", "松开：左键松开");
    }

    /**
     * 同样的位移、同样的节拍：左键杆离按下点（容差 + m）出的光标位移和推红点 m 一模一样（顺着轴逐计数相等，斜着差不过 1），
     * 负惯性开 / 关、灵敏度 1 / 2；只差在左键杆的位移带着左键。
     */
    static void leftLeverSameAsStick() {
        float[][] dirs = {{1, 0}, {0, 1}, {0, -1}, {-0.6f, 0.8f}};
        int cases = 0, bad = 0;
        for (boolean inertia : new boolean[]{false, true}) {
            for (float speed : new float[]{1, 2}) {
                for (float[] d : dirs) {
                    for (float m : new float[]{5, 15, 28}) {
                        GestureEngine.Config c = new GestureEngine.Config();
                        c.stickInertia = inertia;
                        c.stickSpeed = speed;
                        Rec a = new Rec(), b = new Rec();
                        TouchRouter ta = stickRouter(a, c), tb = stickRouter(b, c);
                        push(ta, 2, d[0] * m, d[1] * m, 1000, 1000);
                        float k = c.tapSlopDp + m;
                        leftLever(tb, 1, d[0] * k, d[1] * k, 1000, 1000);
                        int tol = d[0] != 0 && d[1] != 0 ? 1 : 0;
                        cases++;
                        if (Math.abs(a.dxSum - b.dxSum) > tol || Math.abs(a.dySum - b.dySum) > tol || b.movesWithoutLeft != 0
                                || (a.dxSum == 0 && a.dySum == 0)) {
                            bad++;
                            if (bad <= 3) System.err.println("  左键杆和红点不一样：负惯性 " + inertia + "、灵敏度 " + speed + "、方向 (" + d[0] + ", " + d[1]
                                    + ")、m " + m + "：红点 (" + a.dxSum + ", " + a.dySum + ") 左键杆 (" + b.dxSum + ", " + b.dySum + ")");
                        }
                    }
                }
            }
        }
        check(bad == 0, "左键杆离按下点（容差 + m）= 推红点 m：" + cases + " 种出的光标位移一样，都带着左键（错 " + bad + "）");
    }

    /** 手指挪出左键（挪到所有区域外面、挪到红点上、挪进触控板）照样是左键杆（归属按下时定）：红点不被按，触控板不当手势。 */
    static void leftLeverOutsideKey() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = leftLever(t, 1, 0, 270, 1000, 1000);   // 挪到 (2015, 400)：什么区域都不是
        check(Math.abs(r.dySum - TrackPoint.MOVE_MAX) <= 2 && r.dxSum == 0, "挪出左键很远：照样拖、封顶在红点的最快（" + r.dySum + " 计数 / 秒）");
        t.move(1, 2050, 50);   // 挪到红点上
        t.frame(now);
        int y = r.dySum;
        for (long x = now + 8; x <= now + 300; x += 8) t.onTick(x);
        check(r.dySum < y && !r.log.contains("b" + STICK + ":" + Proto.BUTTON_DOWN), "挪到红点上：还是左键杆在拖（往右上），红点没被按");
        t.move(1, 500, 500);   // 挪进触控板
        t.frame(now + 300);
        int before = r.log.size();
        int x0 = r.dxSum;
        t.move(1, 480, 520);
        t.frame(now + 316);
        eq(r.log.size(), before, "挪进触控板再动：触控板不当手势，节拍以外什么都不出");
        for (long x = now + 324; x <= now + 600; x += 8) t.onTick(x);
        check(r.dxSum < x0 && r.movesWithoutLeft == 0, "挪进触控板：还是左键杆在拖（往左下）");
        t.up(1, now + 600);
        eq(last(r.only("m")), "m0", "松开：左键松开");
        check(!r.only("g").iterator().hasNext() && !r.ticking, "没有手势、节拍停");
    }

    /** 触控板下沿的三键条（主界面、键盘页的触控板）：左键往上挪进触控板，照样是左键杆，不按触控板算（密度 2：位移按 dp）。 */
    static void leftLeverOnPadBar() {
        Rec r = new Rec();
        TouchRouter t = new TouchRouter(r);
        GestureEngine.Config c = new GestureEngine.Config();
        c.stickInertia = false;   // 关掉负惯性：起步不多冲一截，1 秒正好按曲线算
        t.setConfig(c, 2);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 0, 900, 400, 1000),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_MIDDLE, 400, 900, 600, 1000),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_RIGHT, 600, 900, 1000, 1000),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 890),
        });
        float s = slop();
        t.down(3, 200, 950, 1000);
        t.move(3, 200, 950 - 2 * (s + 15));   // 往上 2 × (容差 + 15) 像素 = 容差 + 15dp，还在左键里
        t.frame(1000);
        long now = 1000;
        while (now < 2000) {
            now += 8;
            t.onTick(now);
        }
        int y1 = r.dySum;
        float want = TrackPoint.moveSpeed(15);
        check(Math.abs(-y1 - want) <= 1 && r.dxSum == 0, "密度 2：左键往上挪容差 + 15dp 按 1 秒，光标往上走 " + (-y1) + "（应约 " + want + "）");
        int at = r.log.size();
        t.move(3, 200, 650);   // 挪进触控板
        t.frame(now);
        t.move(3, 260, 600);
        t.frame(now + 8);
        eq(r.log.size(), at, "挪进触控板：触控板不当手势（节拍以外不出位移）");
        for (long end = now + 300; now < end; ) {
            now += 8;
            t.onTick(now);
        }
        check(r.dySum < y1 && r.movesWithoutLeft == 0, "挪进触控板：还是左键杆在拖");
        t.up(3, now + 4);
        eq(last(r.only("m")), "m0", "松开：左键松开");
        check(!r.ticking && r.tickStarts == 1 && r.tickStops == 1, "节拍开停配对");
    }

    /** 同时两根手指按在左键上：只有第一根当杆；第一根先抬起，留下的那根也不接手，左键照旧按到两根都松（共用计数）。 */
    static void leftLeverSecondFingerIgnored() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2015, 130, 1000);   // 第一根：不动
        t.down(2, 2010, 130, 1000);
        t.move(2, 2010, 160);   // 第二根往下挪 30dp
        t.frame(1000);
        for (long now = 1008; now <= 1500; now += 8) t.onTick(now);
        check(r.dxSum == 0 && r.dySum == 0, "第二根按在左键上挪：不动光标");
        eq(r.tickStarts, 1, "第二根按下：不再开一次节拍");
        t.up(1, 1500);
        check(!r.ticking && r.tickStops == 1, "第一根（杆）抬起：节拍停");
        check(!r.only("m").contains("m0"), "还有一根按着左键：左键不松");
        t.move(2, 2010, 200);
        t.frame(1510);
        for (long now = 1518; now <= 1800; now += 8) t.onTick(now);
        check(r.dxSum == 0 && r.dySum == 0, "留下的那根不接手当杆");
        t.up(2, 1800);
        eq(r.only("m"), list("m1", "m0"), "两根都松了：左键松开（一次按下 / 松开）");
        Rec r2 = new Rec();
        TouchRouter t2 = stickRouter(r2);
        t2.down(1, 2015, 130, 1000);
        t2.down(2, 2010, 130, 1000);
        t2.up(1, 1100);
        long now = leftLever(t2, 3, slop() + 15, 0, 1200, 600);
        check(r2.dxSum > 0, "杆抬起后新按下的手指：又是左键杆");
        t2.up(3, now);
        t2.up(2, now + 4);
        check(r2.tickStarts == r2.tickStops && !r2.ticking, "节拍开停配对");
        eq(last(r2.only("m")), "m0", "都松了：左键松开");
    }

    /** "左键"按钮（REGION_HOLD_LEFT）、CMD_HOLD_LEFT、右键都不是杆；"左键"按钮和左键杆共用左键的计数。 */
    static void leftLeverOnlyOnMouseKey() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 1050, 50, 1000);   // "左键"按钮
        t.move(1, 1050, 90);
        t.frame(1000);
        for (long now = 1008; now <= 1500; now += 8) t.onTick(now);
        check(r.dxSum == 0 && r.dySum == 0 && r.tickStarts == 0, "\"左键\"按钮按着挪：不推光标、不开节拍");
        t.up(1, 1500);
        t.holdLeft(true);
        check(r.tickStarts == 0, "CMD_HOLD_LEFT：不开节拍");
        t.holdLeft(false);
        t.down(2, 2085, 130, 1600);   // 右键
        t.move(2, 2085, 170);
        t.frame(1600);
        for (long now = 1608; now <= 2000; now += 8) t.onTick(now);
        check(r.dxSum == 0 && r.dySum == 0 && r.tickStarts == 0, "右键按着挪：不推光标、不开节拍");
        t.up(2, 2000);
        // "左键"按钮按着时左键杆拖完松开：左键还按着（"左键"按钮那一份），杆停；"左键"按钮也松了才松开
        Rec q = new Rec();
        TouchRouter tq = stickRouter(q);
        tq.down(1, 1050, 50, 1000);
        long now = leftLever(tq, 2, slop() + 15, 0, 1000, 500);
        check(q.dxSum > 0 && q.movesWithoutLeft == 0, "\"左键\"按钮按着、左键杆推：拖");
        tq.up(2, now);
        check(!q.only("m").contains("m0") && !q.ticking, "左键杆松了、\"左键\"按钮还按着：左键不松、节拍停");
        tq.up(1, now + 10);
        eq(last(q.only("m")), "m0", "\"左键\"按钮也松了：左键松开");
    }

    static void leftLeverCanceledAndReleaseAll() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long now = leftLever(t, 1, slop() + 15, 0, 1000, 300);
        check(r.dxSum > 0, "拖着");
        t.up(1, now + 4, true);   // 手掌 / 服务收尾补报的抬起
        check(!r.ticking && r.tickStarts == r.tickStops, "左键杆被取消：节拍停");
        eq(last(r.only("m")), "m0", "被取消：左键照常松开");
        int at = r.log.size();
        t.onTick(now + 12);
        eq(r.log.size(), at, "被取消后迟到的节拍：什么都不出");
        Rec a = new Rec();
        TouchRouter ta = stickRouter(a);
        long n3 = leftLever(ta, 1, slop() + 15, 0, 1000, 300);
        check(a.ticking, "左键杆推着：节拍开着");
        ta.releaseAll();
        check(!a.ticking && a.tickStarts == a.tickStops, "releaseAll：节拍停（开停配对）");
        check(a.log.contains("b" + M_LEFT + ":" + (Proto.BUTTON_UP | Proto.BUTTON_CANCELED)) && "m0".equals(last(a.only("m"))), "releaseAll：左键报松开（带被取消）、左键松开");
        int at2 = a.log.size();
        ta.onTick(n3 + 8);
        ta.move(1, 2100, 200);
        ta.frame(n3 + 10);
        ta.onTick(n3 + 16);
        ta.up(1, n3 + 20);
        eq(a.log.size(), at2, "releaseAll 之后迟到的节拍、移动、抬起：什么都不出");
        check(!a.ticking, "releaseAll 之后：节拍没被重新打开");
        Rec c = new Rec();
        TouchRouter tc = stickRouter(c);
        long n4 = leftLever(tc, 1, slop() + 15, 0, 1000, 300);
        tc.setConfig(new GestureEngine.Config(), 1);
        check(!c.ticking && c.tickStarts == c.tickStops, "换参数：左键杆的节拍停");
        eq(last(c.only("m")), "m0", "换参数：左键松开");
        int at3 = c.log.size();
        tc.onTick(n4 + 8);
        tc.move(1, 2100, 200);
        tc.frame(n4 + 10);
        tc.onTick(n4 + 16);
        tc.up(1, n4 + 20);
        eq(c.log.size(), at3, "换参数后迟到的节拍、移动、抬起：什么都不出");
    }

    /**
     * 实体键（CMD_HOLD_LEFT）按着时收尾（releaseAll / 换参数：转屏重新接管、重发参数），之后拇指按住左键挪出容差拖东西，这时才松开实体键：
     * 迟到的松开只减实体键自己那一份（收尾清成 0 了，不理），左键杆按着的左键不松，杆推出的每一帧都带着左键；拇指抬起才松（审查发现）。
     */
    static void leftLeverLateHoldLeftRelease() {
        String[] how = {"releaseAll", "换参数"};
        for (int k = 0; k < 2; k++) {
            Rec r = new Rec();
            TouchRouter t = stickRouter(r);
            t.holdLeft(true);
            if (k == 0) t.releaseAll();
            else t.setConfig(new GestureEngine.Config(), 1);
            long now = leftLever(t, 1, slop() + 15, 0, 1000, 300);
            check(r.dxSum > 0 && r.movesWithoutLeft == 0, how[k] + "后左键杆拖着：位移都带着左键");
            int m = r.only("m").size(), x = r.dxSum;
            t.holdLeft(false);   // 实体键迟到的松开
            eq(r.only("m").size(), m, how[k] + "之前按下的实体键迟到的松开：什么都不发（左键不松）");
            for (long end = now + 500; now < end; ) {
                now += 8;
                t.onTick(now);
            }
            check(r.dxSum > x && r.movesWithoutLeft == 0, how[k] + "：实体键松开以后左键杆接着拖，位移都带着左键（不带左键的 " + r.movesWithoutLeft + " 帧）");
            check(!r.only("m").subList(m, r.only("m").size()).contains("m0"), how[k] + "：拖到一半左键没被松开");
            t.up(1, now + 4);
            eq(last(r.only("m")), "m0", how[k] + "：拇指抬起，左键松开");
            check(!r.ticking && r.tickStarts == r.tickStops, how[k] + "：节拍停、开停配对");
        }
    }

    /** 惯性转着时按左键：先停惯性（震"刹住"），之后左键照样当杆。 */
    static void leftLeverStopsSpin() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        long up = flick(t, 1000);
        check(r.pending, "惯性转着");
        int at = r.log.size();
        long now = leftLever(t, 1, slop() + 15, 0, up + 30, 600);
        check(!r.pending, "按左键：惯性马上停");
        check(r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "按左键停：震\"刹住\"");
        check(r.dxSum > 0, "停了以后左键往右挪：拖（左键杆照常）");
        t.up(1, now);
        eq(last(r.only("m")), "m0", "松开：左键松开");
    }

    static void leftLeverIdlePause() {
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(1, 2015, 130, 0);
        t.move(1, 2015 + slop() + 15, 130);
        t.frame(0);
        long now = 0;
        while (r.ticking && now < 30000) {
            now += 8;
            t.onTick(now);
        }
        check(!r.ticking && now > TrackPoint.IDLE_PAUSE_MS && now < TrackPoint.IDLE_PAUSE_MS + 20, "左键按着 20 秒没事件：停节拍（" + now + "ms）");
        eq(r.tickStops, 1, "暂停：停了一次");
        int x = r.dxSum;
        t.move(1, 2015 + slop() + 16, 130);
        t.frame(now + 10);
        check(r.ticking && r.tickStarts == 2, "再动：重新开节拍");
        for (long y = now + 18; y <= now + 200; y += 8) t.onTick(y);
        check(r.dxSum > x, "接着拖");
        t.up(1, now + 210);
        check(!r.ticking && r.tickStarts == r.tickStops, "松开：节拍停");
        eq(last(r.only("m")), "m0", "松开：左键松开");
    }

    /**
     * 拇指按住左键当"按着左键"用（不打算动、慢慢往右下漂），另一根手指往下推红点拖东西：拇指漂出轻点容差也不另外推光标（左键杆让位），
     * 光标和拇指不漂时一模一样；红点松了、拇指接着按 2 秒还在漂，什么都不出；拇指松开左键松开。
     */
    static void leftLeverYieldsToStick() {
        for (boolean inertia : new boolean[]{false, true}) {
            String mode = inertia ? "负惯性开" : "负惯性关";
            GestureEngine.Config c = new GestureEngine.Config();
            c.stickInertia = inertia;
            int baseX = 0, baseY = 0;
            for (float drift : new float[]{0, 4, 7, 10, 30}) {
                Rec r = new Rec();
                TouchRouter t = stickRouter(r, c);
                t.down(1, 2015, 130, 1000);   // 拇指按住左键
                t.frame(1000);
                t.down(2, 2050, 50, 1010);
                t.move(2, 2050, 65);   // 红点往下 15dp
                t.frame(1010);
                for (long now = 1018; now <= 2000; now += 8) {
                    float d = drift * Math.min(1f, (now - 1018) / 600f);
                    t.move(1, 2015 + d, 130 + d * 0.3f);
                    t.frame(now);
                    t.onTick(now);
                }
                if (drift == 0) {
                    baseX = r.dxSum;
                    baseY = r.dySum;
                }
                check(r.dxSum == baseX && r.dySum == baseY && baseY > 0 && baseX == 0 && r.movesWithoutLeft == 0,
                        mode + "：拇指漂 " + drift + "dp + 推红点往下：只有红点在拖（" + r.dxSum + ", " + r.dySum + "，拇指不漂时 " + baseX + ", " + baseY + "）");
                t.up(2, 2000);
                check(!r.ticking && r.tickStarts == 1 && r.tickStops == 1, mode + "：拇指漂 " + drift + "dp，红点松了：节拍停（左键杆让位了，不留着）");
                int x = r.dxSum, y = r.dySum;
                for (long now = 2008; now <= 4000; now += 8) {   // 拇指接着按 2 秒、接着漂（宿主不该再调节拍，调了也不出）
                    t.move(1, 2015 + drift + (now - 2000) / 100f, 130 + (now - 2000) / 50f);
                    t.frame(now);
                    t.onTick(now);
                }
                check(r.dxSum == x && r.dySum == y && !r.ticking, mode + "：拇指漂 " + drift + "dp，红点松了以后拇指再挪 40dp：光标不动、不开节拍");
                t.up(1, 4004);
                check("m0".equals(last(r.only("m"))) && r.tickStarts == r.tickStops, mode + "：拇指漂 " + drift + "dp 松开：左键松开、节拍开停配对");
            }
        }
        // 红点先按着（在挪光标），拇指再按左键、漂：红点先动了光标，左键杆让位
        Rec r = new Rec();
        TouchRouter t = stickRouter(r);
        t.down(2, 2050, 50, 1000);
        t.move(2, 2050, 65);
        t.frame(1000);
        for (long now = 1008; now <= 1200; now += 8) t.onTick(now);
        t.down(1, 2015, 130, 1200);
        for (long now = 1208; now <= 2200; now += 8) {
            t.move(1, 2015 + Math.min(10f, (now - 1208) / 60f), 130);
            t.frame(now);
            t.onTick(now);
        }
        check(r.dySum > 0 && r.dxSum == 0, "红点先按、拇指后按左键再漂 10dp：只有红点在动（横 " + r.dxSum + "）");
    }

    /** 拇指按住左键、另一根手指单指划触控板拖东西：拇指漂了也不另外推光标，触控板一动左键杆就让位、节拍停。 */
    static void leftLeverYieldsToPad() {
        int baseX = 0, baseY = 0;
        for (float drift : new float[]{0, 7, 10, 30}) {
            Rec r = new Rec();
            TouchRouter t = stickRouter(r);
            t.down(1, 2015, 130, 1000);
            t.frame(1000);
            t.down(0, 500, 300, 1010);
            t.frame(1010);
            long now = 1010;
            boolean stopped = false;
            for (int i = 1; i <= 60; i++) {
                now = 1010 + i * 16;
                float d = drift * Math.min(1f, i / 40f);
                t.move(0, 500, 300 + i * 5);   // 触控板往下划 300dp
                t.move(1, 2015 + d, 130);
                t.frame(now);
                if (r.dySum != 0 && !r.ticking) stopped = true;
                t.onTick(now);
                t.onTick(now + 8);
            }
            if (drift == 0) {
                baseX = r.dxSum;
                baseY = r.dySum;
            }
            check(r.dxSum == baseX && r.dySum == baseY && baseY > 0 && baseX == 0 && r.movesWithoutLeft == 0,
                    "拇指漂 " + drift + "dp + 划触控板：只有触控板在拖（" + r.dxSum + ", " + r.dySum + "，拇指不漂时 " + baseX + ", " + baseY + "）");
            check(stopped, "拇指漂 " + drift + "dp：触控板一动，左键杆让位、节拍马上停（红点没按着，节拍没用了）");
            t.up(0, now + 10);
            int x = r.dxSum, y = r.dySum;
            t.move(1, 2015 + drift + 20, 160);
            t.frame(now + 20);
            for (long z = now + 28; z <= now + 600; z += 8) t.onTick(z);
            check(r.dxSum == x && r.dySum == y, "拇指漂 " + drift + "dp，触控板那根松了以后拇指再挪：光标不动");
            t.up(1, now + 610);
            check("m0".equals(last(r.only("m"))) && !r.ticking && r.tickStarts == 1 && r.tickStops == 1,
                    "拇指漂 " + drift + "dp 松开：左键松开、节拍开停各一次");
        }
    }

    /**
     * 先用左键杆拖起来（挪出了轻点容差），再加红点：不让位，两个叠加；挪出了容差、还在死区里（还没出位移）时加红点也一样。
     * 只看光标位移：中键按着、红点滚动（出的是滚轮）时左键杆不让位，之后照样推光标（中键按着也是移动、不是滚动）。
     */
    static void leftLeverFirstThenStickAdds() {
        GestureEngine.Config c = new GestureEngine.Config();
        float s = c.tapSlopDp;
        Rec lone = new Rec();
        TouchRouter tl = stickRouter(lone, c);
        leftLever(tl, 1, 0, s + 15, 1000, 1000);
        Rec both = new Rec();
        TouchRouter tb = stickRouter(both, c);
        long now = leftLever(tb, 1, 0, s + 15, 1000, 300);   // 左键杆先单独拖 300ms
        int y300 = both.dySum;
        tb.down(2, 2050, 50, now);
        tb.move(2, 2050, 65);
        tb.frame(now);
        while (now < 2000) {
            now += 8;
            tb.onTick(now);
        }
        Rec ref = new Rec();   // 红点那一路从头推 696ms 该出多少
        TouchRouter tr = stickRouter(ref, c);
        push(tr, 2, 0, 15, 1000, 696);
        check(y300 > 0 && Math.abs(both.dySum - (lone.dySum + ref.dySum)) <= 2 && both.movesWithoutLeft == 0,
                "左键杆先拖、后加红点：不让位，叠加（只有左键杆 " + lone.dySum + " + 红点 " + ref.dySum + "，一起 " + both.dySum + "）");
        tb.up(2, now);
        check(both.ticking, "红点松了、左键杆还按着：节拍照开");
        int y = both.dySum;
        for (long x = now + 8; x <= now + 200; x += 8) tb.onTick(x);
        check(both.dySum > y, "红点松了：左键杆接着拖");
        tb.up(1, now + 200);
        check(!both.ticking && both.tickStarts == both.tickStops, "都松了：节拍停、配对");
        // 挪出了轻点容差、还在死区里（还没出位移），这时加红点：左键杆已经开始了，不让位
        Rec n = new Rec();
        TouchRouter tn = stickRouter(n, c);
        long n0 = leftLever(tn, 1, s + 1, 0, 1000, 200);
        check(n.dxSum == 0 && n.dySum == 0, "左键挪出容差 1dp：开始了但还在死区里，没出位移");
        tn.down(2, 2050, 50, n0);
        tn.move(2, 2050, 65);
        tn.frame(n0);
        for (long x = n0 + 8; x <= n0 + 500; x += 8) tn.onTick(x);
        tn.up(2, n0 + 500);
        check(n.dySum > 0 && n.ticking, "左键杆开始了以后红点先动了光标：左键杆不让位，节拍照开");
        tn.move(1, 2015 + s + 15, 130);
        tn.frame(n0 + 510);
        int nx = n.dxSum;
        for (long x = n0 + 518; x <= n0 + 1000; x += 8) tn.onTick(x);
        check(n.dxSum > nx, "红点松了：左键杆接着拖（往右）");
        tn.up(1, n0 + 1000);
        // 中键按着、红点滚动：出的是滚轮，左键杆不让位；之后左键挪出容差照样推光标（不滚）
        Rec w = new Rec();
        TouchRouter tw = stickRouter(w, c);
        tw.down(1, 2015, 130, 1000);   // 拇指按住左键（左键杆，还没开始）
        tw.down(3, 2050, 130, 1000);   // 中键
        tw.down(2, 2050, 50, 1000);
        tw.move(2, 2050, 65);   // 红点往下推 = 滚动
        tw.frame(1000);
        for (long x = 1008; x <= 1500; x += 8) tw.onTick(x);
        check(wheelSum(w, 0) < 0 && w.dxSum == 0 && w.dySum == 0 && w.ticking, "左键 + 中键按着推红点：只滚，左键杆没让位、节拍照开");
        tw.move(1, 2015 + s + 15, 130);
        tw.frame(1500);
        int wh = wheelSum(w, 0);
        for (long x = 1508; x <= 2000; x += 8) tw.onTick(x);
        check(w.dxSum > 0 && w.dySum == 0 && w.movesWithoutLeft == 0, "之后左键挪出容差：推光标（中键按着也是移动、不是滚动）：" + w.dxSum);
        check(wheelSum(w, 0) < wh, "红点照样在滚（各出各的）");
        tw.up(2, 2000);
        tw.up(3, 2000);
        tw.up(1, 2000);
        check(!w.ticking && w.tickStarts == w.tickStops, "都松了：节拍停、配对");
    }

    /**
     * 三个来源随机交错：红点、中键（含第二根）、左键（含第二根）、触控板、"左键"按钮上的手指乱序按下 / 小挪（轻点容差里）/ 大挪 / 抬起 / 取消，
     * 夹着 CMD_HOLD_LEFT、releaseAll、换参数、节拍、帧；挪都是相对按下点的（小挪在容差里，杆不开始；大挪开始），触控板的大挪会动光标。
     * 收尾以后实体键照样当还按着（App 不知道服务这边收尾了），之后照样发松开（迟到的松开，审查后加的）。
     * 每一步之后：节拍开着 ⇔ 红点、中键滚动杆、左键杆有一个按着、杆没让位；开停配对；三个都不在推时节拍不出任何东西；
     * 节拍出来的光标位移只有红点、左键杆推得出：红点没按着时都带着左键。全部抬起后左键松开。
     */
    static void leftLeverTicksRandom() {
        java.util.Random rnd = new java.util.Random(202611012L);
        int bad = 0, steps = 0, yields = 0, starts = 0, late = 0;
        float slop = slop();
        float[][] at = {{2050, 50}, {2050, 130}, {2015, 130}, {500, 500}, {1050, 50}};
        for (int seq = 0; seq < 400; seq++) {
            Rec r = new Rec();
            TouchRouter t = stickRouter(r, rnd.nextBoolean() ? smoothCfg() : new GestureEngine.Config());
            int[] where = new int[8];   // 0 没按，1 红点，2 中键，3 左键，4 触控板，5 "左键"按钮
            float[] ox = new float[8], oy = new float[8];
            int stickOwner = -1, leverOwner = -1, leftOwner = -1;
            boolean leverStarted = false, leverYielded = false, leftStarted = false, leftYielded = false;
            int holdLeft = 0, cmdHolds = 0;   // 实体键按着几份（App 那边）、其中收尾以后才按下的（服务那边还认的）
            long now = 1000;
            for (int step = 0; step < 100; step++) {
                steps++;
                now += 1 + rnd.nextInt(12);
                int slot = rnd.nextInt(8);
                int op = rnd.nextInt(12);
                int logAt = r.log.size();
                boolean tickOp = false;
                if (op <= 2 && where[slot] == 0) {
                    int kind = 1 + rnd.nextInt(5);
                    float x = at[kind - 1][0] + rnd.nextInt(5) - 2, y = at[kind - 1][1] + rnd.nextInt(5) - 2;
                    t.down(slot, x, y, now);
                    where[slot] = kind;
                    ox[slot] = x;
                    oy[slot] = y;
                    if (kind == 1 && stickOwner < 0) stickOwner = slot;
                    if (kind == 2 && leverOwner < 0) {
                        leverOwner = slot;
                        leverStarted = leverYielded = false;
                    }
                    if (kind == 3 && leftOwner < 0) {
                        leftOwner = slot;
                        leftStarted = leftYielded = false;
                    }
                    t.frame(now);
                } else if (op <= 5 && where[slot] != 0) {
                    float range = rnd.nextBoolean() ? 4 : 30;   // 小挪（轻点容差里，不会开始）、大挪
                    float mx = ox[slot] + (rnd.nextFloat() * 2 - 1) * range, my = oy[slot] + (rnd.nextFloat() * 2 - 1) * range;
                    t.move(slot, mx, my);
                    float dist = (float) Math.hypot(mx - ox[slot], my - oy[slot]);
                    if (slot == leverOwner && !leverYielded && dist > slop) leverStarted = true;
                    if (slot == leftOwner && !leftYielded && dist > slop) leftStarted = true;
                    t.frame(now);
                } else if (op <= 7 && where[slot] != 0) {
                    t.up(slot, now, rnd.nextInt(3) == 0);
                    where[slot] = 0;
                    if (slot == stickOwner) stickOwner = -1;
                    if (slot == leverOwner) leverOwner = -1;
                    if (slot == leftOwner) leftOwner = -1;
                } else if (op == 8) {
                    if (holdLeft > 0 && rnd.nextBoolean()) {
                        t.holdLeft(false);
                        holdLeft--;
                        if (cmdHolds > 0) cmdHolds--;
                        else late++;   // 收尾之前按下的：迟到的松开
                    } else {
                        t.holdLeft(true);
                        holdLeft++;
                        cmdHolds++;
                    }
                } else if (op == 9 && rnd.nextInt(8) == 0) {
                    if (rnd.nextBoolean()) t.releaseAll();
                    else t.setConfig(rnd.nextBoolean() ? smoothCfg() : new GestureEngine.Config(), 1);
                    java.util.Arrays.fill(where, 0);   // 收尾以后这些手指的抬起什么都不出，当作都没按着
                    stickOwner = leverOwner = leftOwner = -1;
                    // holdLeft 不清：实体键还按着（App 不知道服务这边收尾了），之后照样会发迟到的松开——不能减收尾以后才按下的左键（左键杆）
                    cmdHolds = 0;
                } else {
                    tickOp = true;
                    t.onTick(now);
                }
                if (leverOwner >= 0 && !leverStarted && !leverYielded) {
                    for (String s : r.log.subList(logAt, r.log.size())) {
                        if (s.startsWith("m") && (s.contains(" w") || s.contains(" p"))) leverYielded = true;
                    }
                }
                if (leftOwner >= 0 && !leftStarted && !leftYielded) {   // 左键杆没开始时光标动了：只能是别的来源，左键杆让位
                    for (String s : r.log.subList(logAt, r.log.size())) {
                        if (s.startsWith("m") && s.contains(" d")) {
                            leftYielded = true;
                            yields++;
                        }
                    }
                }
                if (tickOp) {
                    boolean leftLive = leftOwner >= 0 && !leftYielded;
                    for (String s : r.log.subList(logAt, r.log.size())) {
                        if (!s.startsWith("m")) continue;
                        if (stickOwner < 0 && (leverOwner < 0 || leverYielded) && !leftLive) bad++;   // 没有来源在推：节拍什么都不出
                        if (s.contains(" d")) {
                            if (stickOwner < 0 && !leftLive) bad++;   // 节拍出的位移只能是红点、左键杆推的
                            if (stickOwner < 0 && (Integer.parseInt(s.substring(1, s.indexOf(' '))) & Proto.BUTTON_LEFT) == 0) bad++;
                            if (stickOwner < 0) starts++;
                        }
                    }
                }
                boolean want = stickOwner >= 0 || (leverOwner >= 0 && !leverYielded) || (leftOwner >= 0 && !leftYielded);
                if (r.ticking != want) bad++;
                if (r.tickStarts - r.tickStops != (r.ticking ? 1 : 0)) bad++;
            }
            for (int s = 0; s < 8; s++) if (where[s] != 0) t.up(s, now + 1, false);
            while (holdLeft-- > 0) t.holdLeft(false);
            if (r.ticking || r.tickStarts != r.tickStops) bad++;
            for (int i = 0; r.pending && i < 10; i++) t.onTimer(now + 1000 * (i + 1));   // 触控板轻点后引擎按着左键等第二下（轻点拖动），到点才松
            List<String> m = r.only("m");
            if (!m.isEmpty() && (Integer.parseInt(m.get(m.size() - 1).replaceAll("^m(\\d+).*", "$1")) & Proto.BUTTON_LEFT) != 0) bad++;   // 全部抬起：左键松开
        }
        check(bad == 0 && yields > 0 && starts > 0 && late > 0, "三个来源随机交错 400 组 × 100 步（" + steps + " 步，左键杆让位 " + yields + " 次、左键杆单独推出位移 " + starts
                + " 帧、实体键迟到的松开 " + late + " 次）：节拍开着 ⇔ 有来源在推、开停配对、节拍不出多余的东西、红点没按着时节拍出的位移都带着左键、全部抬起后左键松开（错 " + bad + "）");
    }

    // ═══════════ 协议 25：松开带"被取消"、按住到点单独一种震动 ═══════════

    /**
     * 每条报给界面的按钮事件：按下从不带 Proto.BUTTON_CANCELED；正常抬起不带；被取消的抬起（up 的 canceled）、releaseAll、换参数（手柄那一路）带。
     * 只加信息不改意思：去掉标记后和以前一模一样（被取消的手指还在按钮里照样是 TAP）。按住到点报 HAPTIC_HOLD，触控板轻点照旧 HAPTIC_CLICK。
     */
    static void protocol25Flags() {
        int up = Proto.BUTTON_UP, tap = Proto.BUTTON_TAP, cancel = Proto.BUTTON_CANCELED;
        // 正常抬起：不带
        Rec n = new Rec();
        TouchRouter tn = stickRouter(n);
        tn.down(0, 1050, 350, 1000);   // C
        tn.down(1, 1050, 150, 1000);   // 按钮
        tn.down(2, 1050, 50, 1000);    // "左键"
        tn.down(3, 2085, 130, 1000);   // 右键
        tn.down(4, 2050, 50, 1000);    // 红点
        tn.up(0, 1100);
        tn.up(1, 1100);
        tn.up(2, 1100);
        tn.up(3, 1100);
        tn.up(4, 1100);
        eq(n.only("b"), list("b" + (Proto.KEY_ID_BASE + KEY_C) + ":0", "b" + BUTTON + ":0", "b3:0", "b" + M_RIGHT + ":0", "b" + STICK + ":0",
                "b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + up, "b" + BUTTON + ":" + tap, "b3:" + up, "b" + M_RIGHT + ":" + up, "b" + STICK + ":" + up),
                "正常按下 / 抬起：都不带被取消（按钮还在里面是 TAP）");
        // 被取消的抬起：带（按钮还在里面照样是 TAP，只是带上标记）
        Rec c = new Rec();
        TouchRouter tc = stickRouter(c);
        tc.down(0, 1050, 350, 1000);
        tc.down(1, 1050, 150, 1000);
        tc.down(2, 1050, 50, 1000);
        tc.down(3, 2085, 130, 1000);
        tc.down(4, 2050, 50, 1000);
        tc.down(5, 1050, 150, 1000);   // 按钮，再挪出去
        tc.move(5, 1500, 1500);
        for (int s = 0; s <= 5; s++) tc.up(s, 1100, true);
        eq(c.only("b").subList(6, 12), list("b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + (up | cancel), "b" + BUTTON + ":" + (tap | cancel), "b3:" + (up | cancel),
                "b" + M_RIGHT + ":" + (up | cancel), "b" + STICK + ":" + (up | cancel), "b" + BUTTON + ":" + (up | cancel)),
                "被取消的抬起：都带被取消；去掉标记和以前一样（还在按钮里 = TAP，挪出去 = UP）");
        // releaseAll：按着的键、按钮、"左键"（REGION_HOLD_LEFT）、鼠标键、红点报的松开都带被取消（审查发现"左键"那一种以前没有测试锁住：
        // 去掉它的标记，按着"左键"时服务释放触摸屏 / 退后台就会多出一声鼠标"哒"）
        Rec ra = new Rec();
        TouchRouter tra = stickRouter(ra);
        tra.down(0, 1050, 350, 1000);   // C
        tra.down(1, 1050, 150, 1000);   // 按钮
        tra.down(2, 1050, 50, 1000);    // "左键"
        tra.down(3, 2085, 130, 1000);   // 右键
        tra.down(4, 2050, 50, 1000);    // 红点
        tra.releaseAll();
        List<String> rel = ra.only("b");
        eq(rel.subList(5, rel.size()), list("b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + (up | cancel), "b" + BUTTON + ":" + (up | cancel), "b3:" + (up | cancel),
                "b" + M_RIGHT + ":" + (up | cancel), "b" + STICK + ":" + (up | cancel)),
                "releaseAll：键、按钮、\"左键\"、鼠标键、红点的松开都带被取消（\"左键\"是 b3:" + (up | cancel) + "）");
        // 按下从来不带、正常的也不带：随便按一通，只有收尾那些带
        boolean downsClean = true;
        for (String e : c.only("b")) if (e.endsWith(":" + (Proto.BUTTON_DOWN | cancel))) downsClean = false;
        check(downsClean, "按下从来不带被取消");
        // 换参数：按着的键留着，之后照常抬起不带标记
        Rec k = new Rec();
        TouchRouter tk = router(k);
        tk.down(0, 1050, 350, 1000);
        tk.setConfig(new GestureEngine.Config(), 1);
        tk.up(0, 1100);
        eq(last(k.only("b")), "b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + up, "换参数时按着的键：之后正常抬起不带标记");
        // 手柄：正常抬起不带；被取消的抬起、releaseAll、setConfig、setGamepadConfig 带
        for (int w = 0; w < 5; w++) {
            Rec g = new Rec();
            TouchRouter tg = gamepadRouter(g);
            tg.down(0, 2037, 37, 100);   // A
            tg.down(1, 2632, 22, 100);   // Start
            tg.frame(100);
            String what;
            if (w == 0) {
                what = "正常抬起";
                tg.up(0, 120);
                tg.up(1, 120);
            } else if (w == 1) {
                what = "被取消的抬起";
                tg.up(0, 120, true);
                tg.up(1, 120, true);
            } else if (w == 2) {
                what = "releaseAll";
                tg.releaseAll();
            } else if (w == 3) {
                what = "setConfig";
                tg.setConfig(new GestureEngine.Config(), 1);
            } else {
                what = "setGamepadConfig";
                tg.setGamepadConfig(new GamepadConfig());
            }
            int downs = 0, ups = 0, flagged = 0;
            for (String e : g.only("b")) {
                int id = Integer.parseInt(e.substring(1, e.indexOf(':')));
                int a = Integer.parseInt(e.substring(e.indexOf(':') + 1));
                if (!Proto.isGamepadId(id)) continue;
                if (a == Proto.BUTTON_DOWN) downs++;
                else ups++;
                if (Proto.buttonCanceled(a)) flagged++;
            }
            check(downs == 2 && ups == 2 && flagged == (w == 0 ? 0 : 2), "手柄 " + what + "：两个按下配两个松开，" + (w == 0 ? "都不带" : "都带") + "被取消");
            // 收尾之后再按：正常的不带（标记只在那一刻）
            int at = g.log.size();
            tg.down(2, 2037, 37, 200);
            tg.frame(200);
            tg.up(2, 220);
            boolean clean = true;
            for (String e : g.log.subList(at, g.log.size())) if (e.startsWith("b") && Proto.buttonCanceled(Integer.parseInt(e.substring(e.indexOf(':') + 1)))) clean = false;
            check(clean, "手柄 " + what + " 之后再正常按一下：不带被取消");
        }
        // 按住到点 = HAPTIC_HOLD；触控板轻点 = HAPTIC_CLICK
        Rec h = new Rec();
        TouchRouter th = router(h);
        th.down(0, 1050, 250, 1000);   // Ctrl 按住到点
        th.onKeyTimer(1400);
        th.up(0, 1500);
        th.down(1, 500, 500, 1600);   // 触控板轻点
        th.up(1, 1650);
        eq(h.only("h"), list("h" + GestureEngine.HAPTIC_HOLD, "h" + GestureEngine.HAPTIC_CLICK), "按住到点报 HAPTIC_HOLD、触控板轻点报 HAPTIC_CLICK");
    }

    // ═══════════ 手柄页 ═══════════

    static final int GP_CLUSTER = Proto.gamepadId(Proto.GP_KIND_CLUSTER, 0, 0);
    static final int GP_A = Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_SOUTH), GP_B = Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_EAST);
    static final int GP_DPAD = Proto.gamepadId(Proto.GP_KIND_DPAD, 0, 8), GP_STICK = Proto.gamepadId(Proto.GP_KIND_STICK, 0, 0);
    static final int GP_START = Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_START);

    /**
     * 触控板 (0,0)–(1000,1000) 加一页手柄（只为测分流，真的手柄页上没有触控板）：面键簇范围 (2000,0)–(2140,80) 排在两颗成员前面
     * （A (2008,8)–(2066,66)、B (2074,8)–(2132,66)），十字键 (2200,0)–(2336,136)，摇杆 (2400,0)–(2550,150)，Start (2600,0)–(2664,44)。
     */
    static TouchRouter gamepadRouter(Rec r) {
        TouchRouter t = new TouchRouter(r);
        t.setConfig(new GestureEngine.Config(), 1);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_CLUSTER, 2000, 0, 2140, 80),
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_A, 2008, 8, 2066, 66),
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_B, 2074, 8, 2132, 66),
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_DPAD, 2200, 0, 2336, 136),
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_STICK, 2400, 0, 2550, 150),
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_START, 2600, 0, 2664, 44),
                new TouchRouter.Region(Proto.REGION_PAD, PAD, 0, 0, 1000, 1000),
        });
        return t;
    }

    static Hid.Gamepad lastPad(Rec r) {
        Hid.Gamepad g = new Hid.Gamepad();
        g.parse(r.pads.isEmpty() ? new byte[Hid.GAMEPAD_REPORT_SIZE] : r.pads.get(r.pads.size() - 1));
        return g;
    }

    /** REGION_GAMEPAD 走手柄：簇的范围排在成员前面，按在 A 上命中的是簇（成员永远不被直接命中），簇里挑 A。 */
    static void gamepadRoutes() {
        Rec r = new Rec();
        TouchRouter t = gamepadRouter(r);
        TouchRouter.Region hit = t.down(0, 2037, 37, 100);
        check(hit != null && hit.id == GP_CLUSTER, "按在 A 上：命中的是簇（成员不被直接命中）");
        check(lastPad(r).pressed(Proto.GP_SOUTH) && !lastPad(r).pressed(Proto.GP_EAST), "簇里挑最近的 A");
        check(r.log.contains("b" + GP_A + ":" + Proto.BUTTON_DOWN), "报 A 按下（App 震\"嗒\"）");
        t.move(0, 2103, 37);
        t.frame(110);
        check(lastPad(r).pressed(Proto.GP_EAST) && !lastPad(r).pressed(Proto.GP_SOUTH), "簇里滑到 B：换成 B");
        t.up(0, 120);
        check(Hid.Gamepad.neutral(r.pads.get(r.pads.size() - 1)), "抬起：全零");
        t.down(1, 2630, 20, 200);
        check(lastPad(r).pressed(Proto.GP_START), "单键：Start");
        t.up(1, 210, true);
        check(!lastPad(r).pressed(Proto.GP_START), "被取消的抬起：手柄照样松开");
        check(r.only("m").isEmpty(), "手柄页的触摸不动鼠标");
    }

    /** 每根手指归谁在按下那一刻定：从十字键出发滑到 A 上不按 A（十字键照样认角度）；从 Start 滑到 B 上也不按 B。 */
    static void gamepadOwnedAtDown() {
        Rec r = new Rec();
        TouchRouter t = gamepadRouter(r);
        t.down(0, 2268, 68, 100);
        t.move(0, 2037, 37);
        t.frame(110);
        Hid.Gamepad g = lastPad(r);
        check(!g.pressed(Proto.GP_SOUTH) && g.hat == Hid.Gamepad.HAT_LEFT, "从十字键滑到 A 上：不按 A，十字键照样是左");
        t.down(1, 2630, 20, 120);
        t.move(1, 2103, 37);
        t.frame(130);
        check(lastPad(r).pressed(Proto.GP_START) && !lastPad(r).pressed(Proto.GP_EAST), "从 Start 滑到 B 上：Start 照样按着、不按 B");
    }

    static void gamepadTargets() {
        Rec r = new Rec();
        TouchRouter t = gamepadRouter(r);
        check(t.onTarget(2037, 37) && t.onTarget(2268, 68) && t.onTarget(2475, 75) && t.onTarget(2630, 20),
                "分屏透传：手柄控件都算豆花的");
        eq(r.log.size(), 0, "只问不按：没有输出");
    }

    /** releaseAll、换参数（setConfig）、换手柄参数（setGamepadConfig）：手柄回中立，按下都配了松开。 */
    static void gamepadReleaseAllNeutral() {
        for (int k = 0; k < 3; k++) {
            Rec r = new Rec();
            TouchRouter t = gamepadRouter(r);
            t.down(0, 2037, 37, 100);
            t.down(1, 2300, 68, 100);
            t.down(2, 2475, 75, 100);
            t.move(2, 2475 + 40, 75);
            t.down(3, 2630, 20, 100);
            t.frame(110);
            Hid.Gamepad g = lastPad(r);
            check(g.pressed(Proto.GP_SOUTH) && g.hat != 0 && g.lx != 0 && g.pressed(Proto.GP_START), "按上了");
            String what = k == 0 ? "releaseAll" : k == 1 ? "setConfig" : "setGamepadConfig";
            if (k == 0) t.releaseAll();
            else if (k == 1) t.setConfig(new GestureEngine.Config(), 1);
            else t.setGamepadConfig(new GamepadConfig());
            check(Hid.Gamepad.neutral(r.pads.get(r.pads.size() - 1)), what + "：手柄全零");
            int downs = 0, ups = 0;
            for (String s : r.log) {
                if (!s.startsWith("b")) continue;
                int id = Integer.parseInt(s.substring(1, s.indexOf(':')));
                if (!Proto.isGamepadId(id)) continue;
                if (s.endsWith(":" + Proto.BUTTON_DOWN)) downs++;
                else ups++;
            }
            eq(ups, downs, what + "：每个手柄键的按下都配了松开");
            int n = r.pads.size();
            t.move(2, 2475 - 40, 75);
            t.frame(130);
            t.up(0, 140);
            t.up(2, 140);
            eq(r.pads.size(), n, what + "之后迟到的移动、抬起：手柄什么都不发");
        }
    }

    static void gamepadOneReportPerFrame() {
        Rec r = new Rec();
        TouchRouter t = gamepadRouter(r);
        t.down(2, 2475, 75, 100);
        int n = r.pads.size();
        t.move(2, 2480, 75);
        t.move(2, 2490, 75);
        t.move(2, 2500, 80);
        eq(r.pads.size(), n, "摇杆轴变了：攒到这一帧报完");
        t.frame(110);
        eq(r.pads.size(), n + 1, "一帧只出一份手柄报文");
    }

    static void gamepadUnusedSendsNothing() {
        Rec r = new Rec();
        TouchRouter t = gamepadRouter(r);
        t.releaseAll();
        t.setConfig(new GestureEngine.Config(), 1);
        t.setGamepadConfig(new GamepadConfig());
        eq(r.pads.size(), 0, "没用过手柄：收尾不发手柄报文（服务那边不会因此建手柄）");
    }

    /** 惯性转着时碰手柄控件：马上停，不震"刹住"（控件自己会"嗒"），这一下照常生效。 */
    static void gamepadStopsSpinSilently() {
        Rec r = new Rec();
        TouchRouter t = gamepadRouter(r);
        long up = flick(t, 1000);
        check(r.pending, "惯性转着");
        int at = r.log.size();
        t.down(1, 2630, 20, up + 30);
        check(!r.pending, "碰手柄控件：惯性马上停");
        check(!r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "碰手柄控件停：不震\"刹住\"");
        check(lastPad(r).pressed(Proto.GP_START), "碰的那一下照常生效（Start 按下）");
    }

    // ═══════════ 工具 ═══════════

    static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!actual.equals(expected)) {
            failures++;
            System.err.println("✗ " + what + "：期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
