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

        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            log.add("m" + buttons + (dx != 0 || dy != 0 ? " d" : "") + (wheel != 0 ? " w" + wheel : "") + (pan != 0 ? " p" + pan : ""));
        }

        @Override
        public void keyboard(byte[] r) {
            StringBuilder s = new StringBuilder("k" + r[0]);
            for (int i = 2; i < 8; i++) if (r[i] != 0) s.append(' ').append(Integer.toHexString(r[i] & 0xFF));
            log.add(s.toString());
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

        @Override
        public void modifiers(int latched) {
            log.add("mod" + latched);
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
        gamepadRoutes();
        gamepadOwnedAtDown();
        gamepadTargets();
        gamepadReleaseAllNeutral();
        gamepadOneReportPerFrame();
        gamepadUnusedSendsNothing();
        gamepadStopsSpinSilently();
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
        t.key(COPY, true);
        check(!r.pending, "界面发来的键（组合键动作、服务没接管时键盘自己收的）：停");
        check(!r.log.subList(at, r.log.size()).contains(SPIN_CATCH), "不震");
        t.key(COPY, false);
        flick(t, 2000);
        t.stopSpin(true);
        check(!r.pending && r.log.contains(SPIN_CATCH), "stopSpin（分屏透传归系统的手指、App 那边别的按下）：停，震\"刹住\"");
    }

    static void spinNotWithCtrlLatched() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(1, 1050, 250, 900);   // 点一下 Ctrl：锁定
        t.up(1, 950);
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
        t.key(0xE4, true);   // 界面发来的右 Ctrl（实体键盘面板自己收触摸时）：点一下锁定
        t.key(0xE4, false);
        check(r.log.contains("mod16"), "右 Ctrl 锁住（掩码 0x10）");
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
        t2.key(0xE4, true);
        t2.key(0xE4, false);
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

    static void modifierLatchesUntilNextKey() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);   // 点 Ctrl
        t.up(0, 1050);
        eq(r.only("k"), list("k1"), "点一下 Ctrl：立刻按下并锁定");
        check(r.log.contains("mod1"), "告诉界面 Ctrl 锁定了");
        t.down(0, 1050, 350, 1100);   // 再点 C
        t.up(0, 1150);
        eq(r.only("k"), list("k1", "k1 6", "k1", "k0"), "Ctrl 锁着按 C = Ctrl+C，松开后 Ctrl 自动解开");
        check(r.log.contains("mod0"), "告诉界面解锁了");
    }

    static void modifierTapTwiceUnlatches() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 250, 1000);
        t.up(0, 1050);
        t.down(0, 1050, 250, 1100);
        t.up(0, 1150);
        eq(r.only("k"), list("k1", "k0"), "Ctrl 再点一下就解开");
        eq(r.only("mod"), list("mod1", "mod0"), "界面跟着变");
    }

    static void chordKeyHoldsItsModifier() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.down(0, 1050, 450, 1000);
        t.up(0, 1080);
        eq(r.only("k"), list("k1", "k1 6", "k1", "k0"), "复制键：先按 Ctrl 再按 C，先松 C 再松 Ctrl");
        eq(r.only("mod"), list(), "自带的修饰键不算锁定");
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
        eq(r.only("m"), list("m1", "m0", "m0"), "减到 0 以下截在 0（不会变负）");

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
        t.down(0, 1050, 250, 1000);   // 锁定 Ctrl
        t.up(0, 1050);
        t.down(1, 1050, 150, 1100);   // 按着普通按钮
        t.down(2, 1050, 350, 1100);   // 按着 C
        t.releaseAll();
        List<String> k = r.only("k");
        eq(k.get(k.size() - 1), "k0", "放手：键全部松开");
        check(r.log.contains("b7:" + Proto.BUTTON_UP), "按着的按钮报告松开");
        check(r.log.contains("b" + (Proto.KEY_ID_BASE + KEY_C) + ":" + Proto.BUTTON_UP), "按着的键报告松开");
        check(r.log.contains("mod0"), "锁定的修饰键解开");
        int before = r.log.size();
        t.up(1, 1200);
        t.up(2, 1200);
        eq(r.log.size(), before, "放手后再抬起不重复报告");
    }

    static void keyFromAppSide() {
        Rec r = new Rec();
        TouchRouter t = router(r);
        t.key(COPY, true);
        t.key(COPY, false);
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
        check(r.log.contains("b" + (Proto.KEY_ID_BASE + Q) + ":" + Proto.BUTTON_UP), "报告分到的那个键松开");
    }

    static void gapModifierLatches() {
        Rec r = new Rec();
        TouchRouter t = keyboardRouter(r);
        t.down(0, 2, 850, 1000);   // Ctrl 左边的边距
        t.up(0, 1050);
        eq(r.only("k"), list("k1"), "缝里分到 Ctrl：照样点一下锁定");
        t.down(0, 100, 750, 1100);   // a 的正中
        t.up(0, 1150);
        eq(r.only("k"), list("k1", "k1 4", "k1", "k0"), "锁着 Ctrl 按 a，打完自动解开");
        eq(r.only("mod"), list("mod1", "mod0"), "界面跟着变");
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
        check(r.log.contains("b" + M_MIDDLE + ":" + Proto.BUTTON_UP), "中键被取消：界面照样收到松开");
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
        check(r.log.contains("b" + STICK + ":" + Proto.BUTTON_UP) && r.log.contains("b" + M_MIDDLE + ":" + Proto.BUTTON_UP)
                && r.log.contains("b" + M_RIGHT + ":" + Proto.BUTTON_UP), "releaseAll：红点、中键、右键报松开");
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
