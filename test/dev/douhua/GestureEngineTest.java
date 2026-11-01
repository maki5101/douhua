package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * GestureEngine 的离线测试，不依赖 Android、不需要 JUnit：tools/test.sh 用电脑上的 JDK 直接编译运行。
 * 每个用例把引擎的输出记成一行行字符串，再和期望比对。
 */
public final class GestureEngineTest {

    /** 把引擎输出记成字符串："m1" = 鼠标键状态 1；"m0 d=4,0" = 移动；"m0 w=1,0" = 滚动；"h0" = 震动；"g3:1" = 三指上滑。 */
    static final class Rec implements GestureEngine.Output {
        final List<String> log = new ArrayList<>();
        long scheduled = -1;
        /** 宿主眼里有没有挂着的定时器（schedule 之后、cancel 或到点之前）。 */
        boolean pending;
        /** 最近一次 edgeRelease 报的（速度, 起转速度）。 */
        float[] release;

        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            StringBuilder s = new StringBuilder("m").append(buttons);
            if (dx != 0 || dy != 0) s.append(" d=").append(dx).append(',').append(dy);
            if (wheel != 0 || pan != 0) s.append(" w=").append(wheel).append(',').append(pan);
            log.add(s.toString());
        }

        @Override
        public void zoom(int steps) {
            log.add("z" + steps);
        }

        @Override
        public void zoomEnd() {
            log.add("zEnd");
        }

        @Override
        public void gesture(int fingers, int kind) {
            log.add("g" + fingers + ":" + kind);
        }

        @Override
        public void haptic(int kind) {
            log.add("h" + kind);
        }

        @Override
        public void schedule(long delayMs) {
            scheduled = delayMs;
            pending = true;
            log.add("s" + delayMs);
        }

        @Override
        public void cancelSchedule() {
            scheduled = -1;
            pending = false;
            log.add("c");
        }

        @Override
        public void edgeRelease(float speed, float spin) {
            release = new float[]{speed, spin};
        }

        /** 只看鼠标键状态变化（去掉移动、震动），方便比对点击序列。 */
        List<String> buttons() {
            List<String> r = new ArrayList<>();
            for (String s : log) if (s.matches("m\\d+")) r.add(s);
            return r;
        }

        boolean has(String entry) {
            return log.contains(entry);
        }
    }

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        singleTapClicksAfterDragWindow();
        singleTapWithoutTapToDragIsImmediate();
        doubleTap();
        tripleTap();
        tapThenHoldAndMoveDrags();
        tapThenWaitIsNotDrag();
        twoFingerTapIsRightClick();
        twoFingerScrollNatural();
        twoFingerScrollTraditional();
        threeFingerSwipeFiresOnce();
        fourFingerTap();
        movingIsNotClick();
        longPressIsNotClick();
        pointerMoveScalesAndKeepsRemainder();
        tapThenTwoFingersCompletesClick();
        cancelReleasesEverything();
        externalButtonCombines();
        pinchOutZoomsIn();
        pinchInZoomsOut();
        pinchDisabledScrolls();
        parallelMoveScrollsNotZoom();
        edgeSwipeScrolls();
        edgeTapStillClicks();
        edgeScrollDisabledMovesPointer();
        tapThenEdgeSwipeIsNotDrag();
        bottomEdgeSwipeScrollsHorizontally();
        cornerPicksDirectionByMovement();
        edgeScrollTicks();
        twoFingerScrollTicksLikeEdge();
        longPressDrags();
        longPressReleaseIsClick();
        moveCancelsLongPress();
        longPressDisabled();
        secondFingerEndsLongDrag();
        fineMoveIsNotLongPress();
        jitterStillLongPress();
        slowMovesLessThanFast();
        textureGrainsByDistance();
        inertiaFromFastEdgeFlick();
        inertiaSmoothSlowsDown();
        inertiaNotFromSlowScroll();
        inertiaNotAfterPause();
        inertiaNotFromOneFrame();
        inertiaCatchOnPad();
        inertiaCatchIsNotLongPress();
        inertiaCatchThenMoveAndFlickAgain();
        inertiaCatchTwoFingerTapIsNotRightClick();
        inertiaStopsOnCancelWheelAndStopSpin();
        inertiaOffAndTicksOff();
        inertiaNotFromTwoFingersOrCtrl();
        inertiaNotFromCanceledFinger();
        inertiaDirections();
        inertiaFollowsRealTime();
        inertiaHardCap();
        inertiaKeepsExternalButton();
        smoothEdgeUnitsAndTicks();
        smoothTwoFingerLocksAxis();
        smoothCtrlIsByNotch();
        inertiaRandomSequences();
        scrollHoldScrollsNotMoves();
        scrollHoldCancelsLongPress();
        scrollHoldNoFlingAndSmooth();
        scrollHoldMidGesture();
        externalMoveAndScroll();

        System.err.println(checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 用例 ═══════════

    static void singleTapClicksAfterDragWindow() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        check(r.has("h0"), "单击要震动");
        eq(r.buttons(), list("m1"), "单击：先按下左键，等拖动窗口");
        eq(r.scheduled, 220L, "拖动窗口 220ms");
        e.onTimer(1300);
        eq(r.buttons(), list("m1", "m0"), "单击：窗口到点后松开");
    }

    static void singleTapWithoutTapToDragIsImmediate() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.tapToDrag = false;
        GestureEngine e = new GestureEngine(r, c);
        tap(e, 0, 1000);
        eq(r.buttons(), list("m1", "m0"), "关掉轻点拖动后，单击立即完成");
        eq(r.scheduled, -1L, "不需要定时器");
    }

    static void doubleTap() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        tap(e, 0, 1100);   // 在 220ms 窗口内
        eq(r.buttons(), list("m1", "m0"), "双击：第二下抬起时先松开第一下");
        eq(r.scheduled, (long) GestureEngine.DOUBLE_GAP_MS, "隔一会儿再按第二下（系统要求两次点击至少隔 40ms）");
        e.onTimer(1220);
        eq(r.buttons(), list("m1", "m0", "m1"), "按下第二下");
        eq(r.scheduled, (long) GestureEngine.DOUBLE_HOLD_MS, "按住一会儿");
        e.onTimer(1260);
        eq(r.buttons(), list("m1", "m0", "m1", "m0"), "松开第二下，双击完成");
        e.onTimer(1500);
        eq(r.buttons().size(), 4, "之后定时器不该再动");
    }

    static void tripleTap() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        tap(e, 0, 1100);
        tap(e, 0, 1180);   // 双击第二下还没发完就点第三下
        eq(r.buttons(), list("m1", "m0", "m1", "m0", "m1"), "三击：先把第二下补完，第三下照常按下");
    }

    static void tapThenHoldAndMoveDrags() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        e.down(0, 50, 50, 1100);
        moveTo(e, 0, 80, 50, 1150);
        check(r.has("h1"), "拖动开始要震动");
        int before = r.buttons().size();
        check(r.log.stream().anyMatch(s -> s.startsWith("m1 d=")), "拖动中移动时左键保持按下");
        e.up(0, 1300);
        check(r.has("h2"), "拖动结束要震动");
        eq(r.buttons().get(r.buttons().size() - 1), "m0", "抬手松开左键");
        eq(r.buttons().size(), before + 1, "拖动只在结束时松一次");
    }

    static void tapThenWaitIsNotDrag() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        e.onTimer(1220);
        e.down(0, 50, 50, 1300);
        moveTo(e, 0, 80, 50, 1350);
        e.up(0, 1400);
        check(!r.has("h1"), "窗口过了再按下移动，不算拖动");
        eq(r.buttons(), list("m1", "m0"), "只有前面那一次单击");
    }

    static void twoFingerTapIsRightClick() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.down(1, 80, 50, 1010);
        e.up(1, 1080);
        e.up(0, 1090);
        eq(r.buttons(), list("m2", "m0"), "双指轻点 = 右键");
        check(r.has("h0"), "右键也要震动");
    }

    static void twoFingerScrollNatural() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.down(1, 80, 50, 1000);
        // 两根手指一起往下移 70dp：先超过 12dp 判定移动，再按 20dp 一格滚
        for (int i = 1; i <= 7; i++) {
            e.move(0, 50, 50 + i * 10);
            e.move(1, 80, 50 + i * 10);
            e.endFrame(1000 + i * 16);
        }
        int wheel = sumWheel(r);
        check(wheel > 0, "自然滚动：手指下移 → 滚轮正（往上滚），实际 " + wheel);
        e.up(0, 1200);
        e.up(1, 1200);
        eq(r.buttons(), list(), "滚动不产生点击");
    }

    static void twoFingerScrollTraditional() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.naturalScroll = false;
        GestureEngine e = new GestureEngine(r, c);
        e.down(0, 50, 50, 1000);
        e.down(1, 80, 50, 1000);
        for (int i = 1; i <= 7; i++) {
            e.move(0, 50, 50 + i * 10);
            e.move(1, 80, 50 + i * 10);
            e.endFrame(1000 + i * 16);
        }
        check(sumWheel(r) < 0, "传统滚动：手指下移 → 滚轮负");
    }

    static void threeFingerSwipeFiresOnce() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        for (int id = 0; id < 3; id++) e.down(id, 50 + id * 20, 200, 1000);
        for (int i = 1; i <= 15; i++) {
            for (int id = 0; id < 3; id++) e.move(id, 50 + id * 20, 200 - i * 10);
            e.endFrame(1000 + i * 16);
        }
        for (int id = 0; id < 3; id++) e.up(id, 1300);
        eq(count(r, "g3:" + GestureEngine.GESTURE_UP), 1, "三指上滑只触发一次");
        check(r.has("h3"), "三指手势要震动");
        eq(r.buttons(), list(), "三指滑动不产生点击");
    }

    static void fourFingerTap() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        for (int id = 0; id < 4; id++) e.down(id, 50 + id * 20, 200, 1000 + id * 5);
        for (int id = 0; id < 4; id++) e.up(id, 1100);
        check(r.has("g4:" + GestureEngine.GESTURE_TAP), "四指轻点");
    }

    static void movingIsNotClick() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        moveTo(e, 0, 70, 50, 1050);
        e.up(0, 1100);
        eq(r.buttons(), list(), "移动后抬起不算点击");
        check(!r.has("h0"), "移动不震动");
    }

    static void longPressIsNotClick() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.up(0, 1600);
        eq(r.buttons(), list(), "按太久不算轻点");
    }

    static void pointerMoveScalesAndKeepsRemainder() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.pointerSpeed = 1.5f;
        c.acceleration = 0;
        c.slowFactor = 1;   // 只测倍率和余数，不要精细移动
        GestureEngine e = new GestureEngine(r, c);
        e.down(0, 0, 0, 1000);
        int total = 0;
        for (int i = 1; i <= 10; i++) {   // 每帧 1dp，共 10dp → 15 个计数
            e.move(0, i, 0);
            e.endFrame(1000 + i * 16);
        }
        for (String s : r.log) {
            if (s.startsWith("m0 d=")) total += Integer.parseInt(s.substring(5, s.indexOf(',')));
        }
        eq(total, 15, "10dp × 1.5 = 15，余数不丢");
    }

    static void tapThenTwoFingersCompletesClick() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        e.down(0, 50, 50, 1100);
        e.down(1, 80, 50, 1105);
        eq(r.buttons(), list("m1", "m0"), "轻点后接双指：先完成单击");
        for (int i = 1; i <= 5; i++) {
            e.move(0, 50, 50 + i * 10);
            e.move(1, 80, 50 + i * 10);
            e.endFrame(1105 + i * 16);
        }
        e.up(0, 1300);
        e.up(1, 1300);
        check(sumWheel(r) != 0, "然后正常滚动");
        eq(r.buttons(), list("m1", "m0"), "不多出点击");
    }

    static void cancelReleasesEverything() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        e.down(0, 50, 50, 1100);
        moveTo(e, 0, 90, 50, 1150);
        e.cancel();
        eq(r.buttons().get(r.buttons().size() - 1), "m0", "取消时松开所有键");
    }

    static void externalButtonCombines() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.setExternalButtons(Proto.BUTTON_LEFT);
        e.down(0, 0, 0, 1000);
        moveTo(e, 0, 30, 0, 1050);
        e.up(0, 1100);
        e.setExternalButtons(0);
        check(r.log.stream().anyMatch(s -> s.startsWith("m1 d=")), "左侧按住左键时，移动带着左键（拖动）");
        eq(r.buttons().get(r.buttons().size() - 1), "m0", "松开左侧按钮后左键松开");
    }

    /** 两指从间距 d0 匀速变到 d1，中心不动。 */
    static void pinch(GestureEngine e, float d0, float d1) {
        e.down(0, 100 - d0 / 2, 200, 1000);
        e.down(1, 100 + d0 / 2, 200, 1000);
        for (int i = 1; i <= 10; i++) {
            float d = d0 + (d1 - d0) * i / 10f;
            e.move(0, 100 - d / 2, 200);
            e.move(1, 100 + d / 2, 200);
            e.endFrame(1000 + i * 16);
        }
        e.up(0, 1300);
        e.up(1, 1300);
    }

    static int sumZoom(Rec r) {
        int sum = 0;
        for (String s : r.log) if (s.matches("z-?\\d+")) sum += Integer.parseInt(s.substring(1));
        return sum;
    }

    static void pinchOutZoomsIn() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        pinch(e, 60, 180);   // 张开 120dp，每 24dp 一格
        eq(sumZoom(r), 5, "两指张开 = 放大");
        eq(r.log.get(r.log.size() - 1), "zEnd", "手势结束时通知松开 Ctrl");
        check(sumWheel(r) == 0, "开合不滚动");
        eq(r.buttons(), list(), "开合不产生点击");
    }

    static void pinchInZoomsOut() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        pinch(e, 180, 60);
        eq(sumZoom(r), -5, "两指捏合 = 缩小");
    }

    static void pinchDisabledScrolls() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.pinchZoom = false;
        GestureEngine e = new GestureEngine(r, c);
        pinch(e, 60, 180);
        eq(sumZoom(r), 0, "关掉开合缩放后不缩放");
    }

    static void parallelMoveScrollsNotZoom() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.down(1, 80, 50, 1000);
        for (int i = 1; i <= 7; i++) {
            e.move(0, 50, 50 + i * 10);
            e.move(1, 81, 50 + i * 10);   // 间距有点抖动，但主要是一起移动
            e.endFrame(1000 + i * 16);
        }
        e.up(0, 1200);
        e.up(1, 1200);
        eq(sumZoom(r), 0, "两指一起移动是滚动，不是缩放");
        check(sumWheel(r) != 0, "确实滚动了");
    }

    /** 单指从边缘按下，往下滑 70dp。 */
    static void edgeSwipe(GestureEngine e, long t) {
        e.down(0, 2, 100, t, GestureEngine.EDGE_VERTICAL);
        for (int i = 1; i <= 7; i++) {
            e.move(0, 2, 100 + i * 10);
            e.endFrame(t + i * 16);
        }
        e.up(0, t + 200);
    }

    static void edgeSwipeScrolls() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        edgeSwipe(e, 1000);
        check(sumWheel(r) > 0, "边缘单指下滑 = 滚动（自然滚动：滚轮正），实际 " + sumWheel(r));
        check(r.log.stream().noneMatch(s -> s.contains(" d=")), "边缘滚动不移动光标");
        eq(r.buttons(), list(), "边缘滑动不产生点击");
    }

    static void edgeTapStillClicks() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 2, 100, 1000, GestureEngine.EDGE_VERTICAL);
        e.up(0, 1060);
        e.onTimer(1300);
        eq(r.buttons(), list("m1", "m0"), "边缘带里轻点照样是单击");
    }

    static void edgeScrollDisabledMovesPointer() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.edgeScroll = false;
        GestureEngine e = new GestureEngine(r, c);
        edgeSwipe(e, 1000);
        eq(sumWheel(r), 0, "关掉边缘滚动后不滚动");
        check(r.log.stream().anyMatch(s -> s.contains(" d=")), "而是移动光标");
    }

    static void tapThenEdgeSwipeIsNotDrag() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        tap(e, 0, 1000);
        edgeSwipe(e, 1100);   // 轻点后 40ms 就去边缘滑
        check(!r.has("h1"), "轻点后马上边缘滑动，不算拖动");
        eq(r.buttons(), list("m1", "m0"), "前面的单击正常完成");
        check(sumWheel(r) > 0, "边缘照样滚动");
    }

    static int sumPan(Rec r) {
        int sum = 0;
        for (String s : r.log) {
            int i = s.indexOf(" w=");
            if (i >= 0) sum += Integer.parseInt(s.substring(s.indexOf(',', i) + 1));
        }
        return sum;
    }

    static void bottomEdgeSwipeScrollsHorizontally() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 100, 500, 1000, GestureEngine.EDGE_HORIZONTAL);
        for (int i = 1; i <= 7; i++) {
            e.move(0, 100 - i * 10, 503);   // 往左滑，带一点竖向抖动
            e.endFrame(1000 + i * 16);
        }
        e.up(0, 1200);
        check(sumPan(r) > 0, "上下边缘带往左滑 = 横向滚动（自然滚动：往右滚），实际 " + sumPan(r));
        eq(sumWheel(r), 0, "横向边缘滚动不竖向滚");
        check(r.log.stream().noneMatch(s -> s.contains(" d=")), "不移动光标");
    }

    static void cornerPicksDirectionByMovement() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 2, 2, 1000, GestureEngine.EDGE_VERTICAL | GestureEngine.EDGE_HORIZONTAL);
        for (int i = 1; i <= 7; i++) {
            e.move(0, 3, 2 + i * 10);   // 角上按下、往下滑
            e.endFrame(1000 + i * 16);
        }
        e.up(0, 1200);
        check(sumWheel(r) > 0 && sumPan(r) == 0, "角上按下往下滑 = 竖向滚动");
    }

    static void edgeScrollTicks() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        edgeSwipe(e, 1000);
        eq(count(r, "h" + GestureEngine.HAPTIC_TICK), countWheelEvents(r), "边缘滚动每滚一下咔嗒一下");
        check(countWheelEvents(r) > 0, "确实滚了");
    }

    static void twoFingerScrollTicksLikeEdge() {
        for (int ticks : new int[]{GestureEngine.TICKS_ALL, GestureEngine.TICKS_OFF}) {
            Rec r = new Rec();
            GestureEngine.Config c = new GestureEngine.Config();
            c.scrollTicks = ticks;
            GestureEngine e = new GestureEngine(r, c);
            e.down(0, 50, 50, 1000);
            e.down(1, 80, 50, 1000);
            for (int i = 1; i <= 7; i++) {
                e.move(0, 50, 50 + i * 10);
                e.move(1, 80, 50 + i * 10);
                e.endFrame(1000 + i * 16);
            }
            e.up(0, 1200);
            e.up(1, 1200);
            if (ticks == GestureEngine.TICKS_ALL) {
                check(countWheelEvents(r) > 0, "双指确实滚了");
                eq(count(r, "h" + GestureEngine.HAPTIC_TICK), countWheelEvents(r), "双指滚动和边缘滚动一样，每滚一下咔嗒一下");
            } else {
                eq(count(r, "h" + GestureEngine.HAPTIC_TICK), 0, "关了就不咔嗒");
            }
        }
    }

    static void longPressDrags() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        eq(r.scheduled, 450L, "按下就开始等长按");
        e.onTimer(1450);
        eq(r.buttons(), list("m1"), "按住不动到点：按下左键");
        check(r.has("h1"), "震一下提示可以拖了");
        moveTo(e, 0, 120, 50, 1550);
        check(r.log.stream().anyMatch(s -> s.startsWith("m1 d=")), "接着移动 = 拖动（左键按着）");
        e.up(0, 1600);
        eq(r.buttons(), list("m1", "m0"), "松手松开左键");
        check(r.has("h2"), "拖动结束震一下");
    }

    static void longPressReleaseIsClick() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.onTimer(1450);
        e.up(0, 1700);
        eq(r.buttons(), list("m1", "m0"), "长按后不动就松手 = 一次点击");
    }

    static void moveCancelsLongPress() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        moveTo(e, 0, 90, 50, 1100);
        check(r.has("c"), "动了就取消长按");
        e.onTimer(1450);
        eq(r.buttons(), list(), "移动光标中不会突然按下左键");
    }

    static void longPressDisabled() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.longPressDrag = false;
        GestureEngine e = new GestureEngine(r, c);
        e.down(0, 50, 50, 1000);
        eq(r.scheduled, -1L, "关掉长按拖动后不等长按");
        e.onTimer(1450);
        e.up(0, 1700);
        eq(r.buttons(), list(), "按久了也不点击");
    }

    static void secondFingerEndsLongDrag() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.onTimer(1450);
        e.down(1, 80, 50, 1500);
        eq(r.buttons(), list("m1", "m0"), "长按拖动中再按一根手指：结束拖动");
    }

    static int countWheelEvents(Rec r) {
        int n = 0;
        for (String s : r.log) if (s.contains(" w=")) n++;
        return n;
    }

    static void fineMoveIsNotLongPress() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        for (int i = 1; i <= 25; i++) {   // 400ms 慢慢挪 4dp：还在轻点容差（6dp）以内，但这是在微调光标
            e.move(0, 50 + i * 0.16f, 50);
            e.endFrame(1000 + i * 16);
        }
        check(r.has("c"), "微调光标挪过长按容差：取消长按");
        e.onTimer(1450);
        eq(r.buttons(), list(), "微调光标时不会变成拖动");
    }

    static void jitterStillLongPress() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        for (int i = 1; i <= 25; i++) {   // 按住不动时触摸点的轻微抖动（±1dp）
            e.move(0, 50 + (i % 2 == 0 ? 1f : -1f), 50);
            e.endFrame(1000 + i * 16);
        }
        e.onTimer(1450);
        eq(r.buttons(), list("m1"), "手指基本不动（轻微抖动）照样算长按");
    }

    static void slowMovesLessThanFast() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.pointerSpeed = 1;
        c.acceleration = 0;
        Rec slow = new Rec();
        GestureEngine e1 = new GestureEngine(slow, c);
        e1.down(0, 0, 0, 1000);
        for (int i = 1; i <= 40; i++) {   // 每帧 0.5dp（约 5 毫米/秒），共 20dp
            e1.move(0, i * 0.5f, 0);
            e1.endFrame(1000 + i * 16);
        }
        Rec fast = new Rec();
        GestureEngine e2 = new GestureEngine(fast, c);
        e2.down(0, 0, 0, 1000);
        for (int i = 1; i <= 2; i++) {    // 每帧 10dp（约 10 厘米/秒），共 20dp
            e2.move(0, i * 10f, 0);
            e2.endFrame(1000 + i * 16);
        }
        int s = sumDx(slow);
        int f = sumDx(fast);
        check(s < 14 && s > 8, "慢慢挪 20dp：光标放慢（得到 " + s + "，应在 8–14 之间）");
        eq(f, 20, "快速划 20dp：不放慢");
    }

    static int sumDx(Rec r) {
        int total = 0;
        for (String s : r.log) {
            if (s.startsWith("m0 d=")) total += Integer.parseInt(s.substring(5, s.indexOf(',')));
        }
        return total;
    }

    // ═══════════ 惯性滚动 ═══════════

    /** 单指从左边缘带往下快速一划：每 8ms 走 step dp、frames 帧，最后一帧后 liftGap 毫秒松手。返回松手时间。 */
    static long flick(GestureEngine e, long t, float step, int frames, long liftGap) {
        e.down(0, 2, 100, t, GestureEngine.EDGE_VERTICAL);
        float y = 100;
        long ft = t;
        for (int i = 1; i <= frames; i++) {
            y += step;
            ft = t + i * 8;
            e.move(0, 2, y);
            e.endFrame(ft);
        }
        e.up(0, ft + liftGap);
        return ft + liftGap;
    }

    /** 宿主的定时器：每 16ms 到一次点，直到不再转（最多 maxFrames 帧）。返回每帧的滚轮量（单位，竖 + 横）。 */
    static List<Integer> spin(GestureEngine e, Rec r, long t, int maxFrames) {
        List<Integer> frames = new ArrayList<>();
        boolean timed = true;
        for (int n = 0; n < maxFrames && e.spinning(); n++) {
            timed &= r.pending && r.scheduled == GestureEngine.SPIN_FRAME_MS;
            t += GestureEngine.SPIN_FRAME_MS;
            int before = r.log.size();
            r.pending = false;
            e.onTimer(t);
            int sum = 0;
            for (String s : r.log.subList(before, r.log.size())) sum += wheelOf(s) + panOf(s);
            frames.add(sum);
        }
        check(timed, "转着时一直挂着一个 16ms 的定时器");
        return frames;
    }

    static int wheelOf(String s) {
        int i = s.indexOf(" w=");
        return i < 0 ? 0 : Integer.parseInt(s.substring(i + 3, s.indexOf(',', i)));
    }

    static int panOf(String s) {
        int i = s.indexOf(" w=");
        return i < 0 ? 0 : Integer.parseInt(s.substring(s.indexOf(',', i) + 1));
    }

    static int sum(List<Integer> l) {
        int s = 0;
        for (int v : l) s += v;
        return s;
    }

    static int countFrom(Rec r, int from, String entry) {
        int n = 0;
        for (String s : r.log.subList(from, r.log.size())) if (s.equals(entry)) n++;
        return n;
    }

    static final String SPIN_START = "h" + GestureEngine.HAPTIC_SPIN_START, SPIN_CATCH = "h" + GestureEngine.HAPTIC_SPIN_CATCH,
            TICK = "h" + GestureEngine.HAPTIC_TICK;

    static void inertiaFromFastEdgeFlick() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        GestureEngine e = new GestureEngine(r, c);
        long up = flick(e, 1000, 10, 8, 5);   // 1.25dp/ms，80dp = 正好 4 格
        int at = r.log.size();
        check(e.spinning(), "边缘快速一划松手：起惯性");
        eq(count(r, SPIN_START), 1, "拨出去震一下\"放开\"");
        eq(r.scheduled, (long) GestureEngine.SPIN_FRAME_MS, "每 16ms 一帧");
        check(r.release != null && Math.abs(r.release[0] - 1.25f) < 0.01f && Math.abs(r.release[1] - 62.5f) < 0.1f,
                "报给宿主的松手速度 1.25dp/ms、起转 62.5 格/秒");
        List<Integer> frames = spin(e, r, up, 2000);
        check(!e.spinning(), "自己停下了");
        double tau = c.inertiaTauMs / 1000.0;
        double want = ScrollInertia.distance(62.5, tau);
        double got = sum(frames) / (double) Hid.WHEEL_UNIT;
        check(Math.abs(got - want) < 1, "按格模式惯性总共滚了 " + got + " 格，闭式解 " + want + "（差不到一格）");
        check(Math.abs(frames.size() * 0.016 - ScrollInertia.stopSeconds(62.5, tau)) <= 0.016,
                "停止时间和闭式解差不到一帧（" + frames.size() + " 帧）");
        check(sum(frames) > 0, "自然滚动：手指往下拨 → 滚轮正");
        eq(countFrom(r, at, TICK), 0, "转的时候不咔嗒");
        eq(countFrom(r, at, SPIN_CATCH), 0, "自然停下不震");
        check(frames.get(0) % Hid.WHEEL_UNIT == 0, "按格模式每帧都是整格");
        int before = r.log.size();
        e.onTimer(up + 60000);
        eq(r.log.size(), before, "停了以后定时器晚到也没有输出");
    }

    static void inertiaSmoothSlowsDown() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        GestureEngine e = new GestureEngine(r, c);
        long up = flick(e, 1000, 10, 8, 5);
        List<Integer> frames = spin(e, r, up, 2000);
        boolean down = true;
        for (int i = 1; i < frames.size(); i++) down &= frames.get(i) <= frames.get(i - 1) + 1;
        check(down, "连续模式：每帧滚的量单调变少（取整差 1 不算）");
        check(frames.get(0) > 50 && frames.get(0) < 200, "第一帧约 1 格（62.5 格/秒 × 16ms）：" + frames.get(0));
        double want = ScrollInertia.distance(62.5, c.inertiaTauMs / 1000.0) * Hid.WHEEL_UNIT;
        check(Math.abs(sum(frames) - want) <= 1, "连续模式总量 " + sum(frames) + " 单位 ≈ 闭式 " + want);
    }

    static void inertiaNotFromSlowScroll() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        flick(e, 1000, 2, 30, 5);   // 0.25dp/ms：正常慢慢滚
        check(!e.spinning(), "慢慢滚松手：不起惯性");
        eq(count(r, SPIN_START), 0, "不震\"放开\"");
        check(r.release != null && r.release[1] == 0, "报给宿主：没起惯性");
    }

    static void inertiaNotAfterPause() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        flick(e, 1000, 10, 8, 50);   // 划得快，但停了 50ms 才抬
        check(!e.spinning(), "停住了再抬（最后一帧离松手 > 40ms）：不起惯性");
    }

    static void inertiaNotFromOneFrame() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        flick(e, 1000, 10, 1, 5);
        check(!e.spinning(), "只有一帧：算不出速度，不起惯性");
    }

    static void inertiaCatchOnPad() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        long up = flick(e, 1000, 10, 8, 5);
        spin(e, r, up, 3);
        long t = up + 48;
        int at = r.log.size();
        e.down(0, 50, 50, t);
        check(!e.spinning(), "转着时碰触控板：马上停");
        eq(countFrom(r, at, SPIN_CATCH), 1, "\"刹住\"震一下");
        check(!r.pending, "抓住时没挂长按定时器");
        e.up(0, t + 60);
        e.onTimer(t + 500);
        eq(r.buttons(), list(), "抓停的那一下不算点击");
        boolean wheelAfter = false;
        for (String s : r.log.subList(at, r.log.size())) wheelAfter |= s.contains(" w=");
        check(!wheelAfter, "抓住以后不再出滚轮");
    }

    static void inertiaCatchIsNotLongPress() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        long up = flick(e, 1000, 10, 8, 5);
        e.down(0, 50, 50, up + 30);
        e.onTimer(up + 600);   // 就算有晚到的定时器
        e.up(0, up + 700);
        eq(r.buttons(), list(), "抓住后按着不动 500ms：不起长按拖动");
        check(!r.has("h" + GestureEngine.HAPTIC_DRAG_START), "不震\"开始拖动\"");
    }

    static void inertiaCatchThenMoveAndFlickAgain() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        long up = flick(e, 1000, 10, 8, 5);
        e.down(0, 50, 50, up + 30);
        moveTo(e, 0, 90, 50, up + 100);
        e.up(0, up + 150);
        check(r.log.stream().anyMatch(s -> s.startsWith("m0 d=")), "抓住后动了照常移动光标");
        long up2 = flick(e, up + 400, 10, 8, 5);
        check(e.spinning(), "接着再拨一下：又转起来");
        eq(count(r, SPIN_START), 2, "第二次拨也震\"放开\"");
        e.down(0, 2, 100, up2 + 20, GestureEngine.EDGE_VERTICAL);
        check(!e.spinning(), "在边缘带上抓也算抓");
        e.up(0, up2 + 40);
        eq(r.buttons(), list(), "边缘带上抓停也不算点击");
    }

    static void inertiaCatchTwoFingerTapIsNotRightClick() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        long up = flick(e, 1000, 10, 8, 5);
        e.down(0, 50, 50, up + 30);
        e.down(1, 80, 50, up + 35);
        e.up(0, up + 90);
        e.up(1, up + 95);
        eq(r.buttons(), list(), "两指抓住后两指轻点：不算右键");
    }

    static void inertiaStopsOnCancelWheelAndStopSpin() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        long up = flick(e, 1000, 10, 8, 5);
        e.cancel();
        check(!e.spinning() && !r.pending, "cancel（释放触摸屏、断开、退后台、换参数）：停，定时器撤掉");
        int at = r.log.size();
        e.onTimer(up + 16);
        eq(r.log.size(), at, "cancel 之后定时器晚到也没有输出");
        eq(count(r, SPIN_CATCH), 0, "cancel 停不震");

        Rec r2 = new Rec();
        GestureEngine w = engine(r2);
        flick(w, 1000, 10, 8, 5);
        int at2 = r2.log.size();
        w.wheel(1);
        check(!w.spinning(), "\"滚轮向上\"动作：先停");
        eq(r2.log.subList(at2, r2.log.size()), list("c", "m0 w=120,0"), "停（不震）再滚这一格（120 单位）");

        Rec r3 = new Rec();
        GestureEngine s = engine(r3);
        flick(s, 1000, 10, 8, 5);
        check(s.stopSpin(true), "stopSpin：刚才在转");
        eq(count(r3, SPIN_CATCH), 1, "stopSpin(true) 震\"刹住\"");
        check(!s.stopSpin(true), "不在转时 stopSpin 什么都不做");
        eq(count(r3, SPIN_CATCH), 1, "不在转时不震");
        flick(s, 2000, 10, 8, 5);
        s.stopSpin(false);
        eq(count(r3, SPIN_CATCH), 1, "stopSpin(false)（按键）不震");
    }

    static void inertiaOffAndTicksOff() {
        Rec r = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.inertia = false;
        GestureEngine e = new GestureEngine(r, c);
        flick(e, 1000, 10, 8, 5);
        check(!e.spinning(), "关掉惯性：不起");

        Rec q = new Rec();
        GestureEngine.Config d = new GestureEngine.Config();
        d.scrollTicks = GestureEngine.TICKS_OFF;
        GestureEngine f = new GestureEngine(q, d);
        long up = flick(f, 1000, 10, 8, 5);
        check(f.spinning(), "咔嗒关了照样起惯性");
        f.down(0, 50, 50, up + 30);
        check(!q.has(SPIN_START) && !q.has(SPIN_CATCH), "咔嗒关了：放开、刹住都不震（滚动类反馈一个开关管）");
    }

    static void inertiaNotFromTwoFingersOrCtrl() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 50, 50, 1000);
        e.down(1, 80, 50, 1000);
        for (int i = 1; i <= 8; i++) {   // 两指快速往下划（2.5dp/ms），马上松手
            e.move(0, 50, 50 + i * 20);
            e.move(1, 80, 50 + i * 20);
            e.endFrame(1000 + i * 8);
        }
        e.up(0, 1068);
        e.up(1, 1068);
        check(sumWheel(r) > 0 && !e.spinning(), "双指滚动不带惯性");

        Rec p = new Rec();
        GestureEngine z = engine(p);
        pinch(z, 60, 180);
        check(!z.spinning(), "开合不起惯性");

        Rec q = new Rec();
        GestureEngine c = engine(q);
        c.setCtrlHeld(true);
        flick(c, 1000, 10, 8, 5);
        check(!c.spinning(), "锁着 Ctrl（Ctrl + 滚轮是缩放）：不起惯性");
        c.setCtrlHeld(false);
        flick(c, 2000, 10, 8, 5);
        check(c.spinning(), "Ctrl 松开后照常起");
        int at = q.log.size();
        c.setCtrlHeld(true);
        check(!c.spinning(), "转着时按下 Ctrl：马上停");
        eq(countFrom(q, at, SPIN_CATCH), 0, "按 Ctrl 停不震");
    }

    static void inertiaNotFromCanceledFinger() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 2, 100, 1000, GestureEngine.EDGE_VERTICAL);
        for (int i = 1; i <= 8; i++) {
            e.move(0, 2, 100 + i * 10);
            e.endFrame(1000 + i * 8);
        }
        e.up(0, 1069, true);   // 手掌 / 丢数据：取消式抬起
        check(!e.spinning(), "被取消的手指松手：不起惯性");
        check(sumWheel(r) > 0, "取消前滚的照旧");
    }

    static void inertiaDirections() {
        Rec up = new Rec();
        GestureEngine u = engine(up);
        long t = flick(u, 1000, -10, 8, 5);   // 往上拨
        spin(u, up, t, 2000);
        check(sumWheel(up) < 0, "自然滚动：往上拨 → 滚轮负（手指走、惯性接着往同一边）");

        Rec tr = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.naturalScroll = false;
        GestureEngine e = new GestureEngine(tr, c);
        long t2 = flick(e, 1000, 10, 8, 5);
        spin(e, tr, t2, 2000);
        check(sumWheel(tr) < 0, "传统滚动：往下拨 → 滚轮负");

        Rec h = new Rec();
        GestureEngine x = engine(h);
        x.down(0, 100, 500, 1000, GestureEngine.EDGE_HORIZONTAL);
        for (int i = 1; i <= 8; i++) {
            x.move(0, 100 - i * 10, 501);
            x.endFrame(1000 + i * 8);
        }
        x.up(0, 1069);
        check(x.spinning(), "横向边缘带往左拨：起惯性");
        int at = h.log.size();
        spin(x, h, 1069, 2000);
        int pan = 0, wheel = 0;
        for (String s : h.log.subList(at, h.log.size())) {
            pan += panOf(s);
            wheel += wheelOf(s);
        }
        check(pan > 0 && wheel == 0, "横向惯性只出 AC Pan（自然滚动：往左拨 → 往右滚）");

        Rec k = new Rec();
        GestureEngine corner = engine(k);
        corner.down(0, 2, 2, 1000, GestureEngine.EDGE_VERTICAL | GestureEngine.EDGE_HORIZONTAL);
        for (int i = 1; i <= 8; i++) {
            corner.move(0, 3, 2 + i * 10);
            corner.endFrame(1000 + i * 8);
        }
        corner.up(0, 1069);
        spin(corner, k, 1069, 2000);
        check(sumWheel(k) > 0 && sumPan(k) == 0, "角上按下往下拨：按定下来的竖向转");
    }

    /** 定时器晚到（宿主忙）：按真实经过的时间推进，总距离不变。 */
    static void inertiaFollowsRealTime() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        Rec r = new Rec();
        GestureEngine e = new GestureEngine(r, c);
        long up = flick(e, 1000, 10, 8, 5);
        int at = r.log.size();
        long t = up;
        long[] gaps = {16, 70, 16, 200, 33, 16, 500};
        for (int i = 0; e.spinning() && i < 2000; i++) {
            t += i < gaps.length ? gaps[i] : 16;
            r.pending = false;
            e.onTimer(t);
        }
        int units = 0;
        for (String s : r.log.subList(at, r.log.size())) units += wheelOf(s);
        double want = ScrollInertia.distance(62.5, c.inertiaTauMs / 1000.0) * Hid.WHEEL_UNIT;
        check(Math.abs(units - want) <= 1, "定时器忽快忽慢：总量 " + units + " ≈ " + want);
    }

    static void inertiaHardCap() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.inertiaTauMs = 10_000_000;   // 几乎不减速
        Rec r = new Rec();
        GestureEngine e = new GestureEngine(r, c);
        long up = flick(e, 1000, 10, 8, 5);
        List<Integer> frames = spin(e, r, up, 5000);
        check(!e.spinning(), "15 秒兜底：停了");
        check(frames.size() * GestureEngine.SPIN_FRAME_MS <= ScrollInertia.MAX_MS + GestureEngine.SPIN_FRAME_MS,
                "最多转 15 秒（" + frames.size() + " 帧）");
    }

    static void inertiaKeepsExternalButton() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.setExternalButtons(Proto.BUTTON_LEFT);   // 按着"左键"按钮拖东西
        long up = flick(e, 1000, 10, 8, 5);
        int at = r.log.size();
        spin(e, r, up, 50);
        boolean held = true, any = false;
        for (String s : r.log.subList(at, r.log.size())) {
            if (!s.contains(" w=")) continue;
            any = true;
            held &= s.startsWith("m1 ");
        }
        check(any && held, "外部按着左键时，惯性的报文带着左键（拖着东西自动滚）");
    }

    // ═══════════ 连续滚动 ═══════════

    /** 边缘带往下滑：7dp（过了轻点容差才开始滚）、再 3 × 6dp，一共滚 25dp，慢、停了再抬（不起惯性）。 */
    static Rec slowEdge25(GestureEngine.Config c) {
        Rec r = new Rec();
        GestureEngine e = new GestureEngine(r, c);
        e.down(0, 2, 100, 1000, GestureEngine.EDGE_VERTICAL);
        float[] ys = {107, 113, 119, 125};
        for (int i = 0; i < ys.length; i++) {
            e.move(0, 2, ys[i]);
            e.endFrame(1016 + i * 16);
        }
        e.up(0, 1200);
        check(!e.spinning(), "慢慢滚不起惯性");
        return r;
    }

    static void smoothEdgeUnitsAndTicks() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        Rec r = slowEdge25(c);
        eq(sumWheel(r), 150, "连续模式：25dp（每格 20dp）= 150 个 1/120 格");
        check(countWheelEvents(r) == 4, "每帧都出（4 帧 4 个报文）");
        eq(count(r, TICK), 1, "滚过一格的距离咔嗒一下");
        Rec g = slowEdge25(new GestureEngine.Config());
        eq(sumWheel(g), Hid.WHEEL_UNIT, "按格模式：25dp = 1 格（120），余数留着");
        eq(count(g, TICK), 1, "按格模式每格咔嗒");
    }

    static void smoothTwoFingerLocksAxis() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        double deg = Math.toRadians(10);
        for (int mode = 0; mode < 2; mode++) {
            double a = mode == 0 ? deg : Math.toRadians(45);
            Rec r = new Rec();
            GestureEngine e = new GestureEngine(r, c);
            e.down(0, 50, 50, 1000);
            e.down(1, 80, 50, 1000);
            for (int i = 1; i <= 12; i++) {   // 一共走 60dp
                float dx = (float) (Math.sin(a) * 5 * i), dy = (float) (Math.cos(a) * 5 * i);
                e.move(0, 50 + dx, 50 + dy);
                e.move(1, 80 + dx, 50 + dy);
                e.endFrame(1000 + i * 16);
            }
            e.up(0, 1300);
            e.up(1, 1300);
            if (mode == 0) {
                check(sumWheel(r) > 0, "双指斜 10° 往下：竖着滚");
                eq(sumPan(r), 0, "连续模式双指斜 10°：锁住竖向，横向一点都不出");
            } else {
                check(sumWheel(r) > 0 && sumPan(r) != 0, "斜 45° 滚：两个方向都出（不锁）");
            }
        }
    }

    static void smoothCtrlIsByNotch() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        Rec r = new Rec();
        GestureEngine e = new GestureEngine(r, c);
        e.setCtrlHeld(true);
        e.down(0, 2, 100, 1000, GestureEngine.EDGE_VERTICAL);
        float[] ys = {107, 113, 119, 125};
        for (int i = 0; i < ys.length; i++) {
            e.move(0, 2, ys[i]);
            e.endFrame(1016 + i * 16);
        }
        e.up(0, 1200);
        eq(sumWheel(r), Hid.WHEEL_UNIT, "锁着 Ctrl（缩放）时连续模式照旧按格");
    }

    /**
     * 乱序几万步（按下 / 移动 / 抬起 / 定时器到点 / cancel / stopSpin / 滚轮动作）：cancel 之后定时器晚到也没有输出；
     * 转着的时候宿主手里一定挂着定时器；松开的按键最后都发了。
     */
    static void inertiaRandomSequences() {
        java.util.Random rnd = new java.util.Random(20261031);
        int bad = 0, cancels = 0, spins = 0;
        for (int round = 0; round < 200; round++) {
            Rec r = new Rec();
            GestureEngine.Config c = new GestureEngine.Config();
            c.smoothScroll = rnd.nextBoolean();
            GestureEngine e = new GestureEngine(r, c);
            boolean[] down = new boolean[3];
            float[] y = new float[3];
            long t = 1000;
            for (int step = 0; step < 300; step++) {
                t += 1 + rnd.nextInt(12);
                int op = rnd.nextInt(10);
                int id = rnd.nextInt(3);
                if (op <= 1 && !down[id]) {
                    down[id] = true;
                    y[id] = 100 + rnd.nextInt(200);
                    e.down(id, rnd.nextBoolean() ? 2 : 100, y[id], t, rnd.nextBoolean() ? GestureEngine.EDGE_VERTICAL : 0);
                } else if (op <= 4) {
                    for (int i = 0; i < 3; i++) {
                        if (!down[i]) continue;
                        y[i] += rnd.nextInt(41) - 10;
                        e.move(i, 2, y[i]);
                    }
                    e.endFrame(t);
                } else if (op == 5 && down[id]) {
                    down[id] = false;
                    e.up(id, t, rnd.nextInt(10) == 0);
                } else if (op == 6 && r.pending) {
                    r.pending = false;
                    e.onTimer(t);
                } else if (op == 7 && rnd.nextInt(8) == 0) {
                    e.cancel();
                    cancels++;
                    down[0] = down[1] = down[2] = false;
                    int at = r.log.size();
                    e.onTimer(t + 16);
                    for (String s : r.log.subList(at, r.log.size())) if (s.startsWith("m") || s.startsWith("s")) bad++;
                } else if (op == 8) {
                    e.stopSpin(rnd.nextBoolean());
                } else if (op == 9 && rnd.nextInt(10) == 0) {
                    e.wheel(rnd.nextBoolean() ? 1 : -1);
                }
                if (e.spinning()) {
                    spins++;
                    if (!r.pending) bad++;
                }
            }
        }
        eq(bad, 0, "乱序：cancel 后没有输出、转着时一直挂着定时器（cancel " + cancels + " 次，转着的步数 " + spins + "）");
        check(spins > 0, "乱序里确实转起来过");
    }

    // ═══════════ 工具 ═══════════

    static void textureGrainsByDistance() {
        String grain = "h" + GestureEngine.HAPTIC_TEXTURE;
        GestureEngine.Config c = new GestureEngine.Config();
        c.texture = true;
        Rec r = new Rec();
        GestureEngine e = new GestureEngine(r, c);
        e.down(0, 0, 0, 1000);
        for (int i = 1; i <= 20; i++) {   // 每帧 3dp、隔 30ms，共 60dp
            e.move(0, i * 3f, 0);
            e.endFrame(1000 + i * 30);
        }
        e.up(0, 1700);
        eq(count(r, grain), 6, "颗粒：每 10dp 一颗");

        Rec fast = new Rec();
        GestureEngine f = new GestureEngine(fast, c);
        f.down(0, 0, 0, 1000);
        for (int i = 1; i <= 10; i++) {   // 每帧 30dp、隔 10ms：距离够 30 颗，但两颗至少隔 25ms
            f.move(0, i * 30f, 0);
            f.endFrame(1000 + i * 10);
        }
        f.up(0, 1200);
        eq(count(fast, grain), 4, "颗粒：划得再快也不密于 25ms 一颗");

        Rec off = new Rec();
        GestureEngine o = new GestureEngine(off, new GestureEngine.Config());
        o.down(0, 0, 0, 1000);
        for (int i = 1; i <= 20; i++) {
            o.move(0, i * 3f, 0);
            o.endFrame(1000 + i * 30);
        }
        eq(count(off, grain), 0, "颗粒：默认关");

        Rec held = new Rec();
        GestureEngine h = new GestureEngine(held, c);
        h.setExternalButtons(Proto.BUTTON_LEFT);   // 按着"左键"按钮拖动
        h.down(0, 0, 0, 1000);
        for (int i = 1; i <= 20; i++) {
            h.move(0, i * 3f, 0);
            h.endFrame(1000 + i * 30);
        }
        eq(count(held, grain), 0, "颗粒：按着左键拖动时不发（有持续轻震）");
    }

    // ═══════════ 中键滚动、小红点的输出 ═══════════

    /** 中键按着：单指往下划 = 往下翻（滚轮负，不跟自然滚动），不出位移；松开中键后又是移动。 */
    static void scrollHoldScrollsNotMoves() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.setScrollHold(true);
        e.down(0, 300, 300, 1000);
        for (int i = 1; i <= 10; i++) {
            e.move(0, 300, 300 + i * 10);
            e.endFrame(1000 + i * 16);
        }
        e.up(0, 1200);
        check(sumWheel(r) < 0 && sumWheel(r) % Hid.WHEEL_UNIT == 0, "中键按着单指往下划：往下翻、整格 " + sumWheel(r));
        check(r.log.stream().noneMatch(x -> x.contains(" d=")), "中键滚动不动光标");
        check(r.has("h" + GestureEngine.HAPTIC_TICK), "每格咔嗒");
        eq(r.buttons(), list(), "滑过了：不是点击");
        // 自然滚动关着也一样（方向不跟开关）
        Rec r2 = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.naturalScroll = false;
        GestureEngine e2 = new GestureEngine(r2, c);
        e2.setScrollHold(true);
        e2.down(0, 300, 300, 1000);
        for (int i = 1; i <= 10; i++) {
            e2.move(0, 300, 300 + i * 10);
            e2.endFrame(1000 + i * 16);
        }
        eq(sumWheel(r2), sumWheel(r), "中键滚动方向不跟自然滚动开关");
        // 往右划 = 往右翻
        Rec r3 = new Rec();
        GestureEngine e3 = engine(r3);
        e3.setScrollHold(true);
        e3.down(0, 300, 300, 1000);
        for (int i = 1; i <= 10; i++) {
            e3.move(0, 300 + i * 10, 300);
            e3.endFrame(1000 + i * 16);
        }
        check(r3.log.stream().anyMatch(x -> x.endsWith(",120")), "中键按着往右划：往右翻（横向正）");
        // 松开中键：又是移动
        Rec r4 = new Rec();
        GestureEngine e4 = engine(r4);
        e4.setScrollHold(true);
        e4.setScrollHold(false);
        e4.down(0, 300, 300, 1000);
        for (int i = 1; i <= 5; i++) {
            e4.move(0, 300 + i * 10, 300);
            e4.endFrame(1000 + i * 16);
        }
        check(r4.log.stream().anyMatch(x -> x.contains(" d=")) && sumWheel(r4) == 0, "松开中键：单指照常移动");
        // 中键按着时单指轻点：照旧左键单击
        Rec r5 = new Rec();
        GestureEngine e5 = engine(r5);
        e5.setScrollHold(true);
        tap(e5, 0, 1000);
        e5.onTimer(1300);
        eq(r5.buttons(), list("m1", "m0"), "中键按着时轻点：照旧左键单击");
    }

    static void scrollHoldCancelsLongPress() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 300, 300, 1000);
        check(r.pending, "按住：长按计时开着");
        e.setScrollHold(true);
        check(!r.pending, "按下中键：长按计时取消");
        e.up(0, 1600);
        Rec r2 = new Rec();
        GestureEngine e2 = engine(r2);
        e2.setScrollHold(true);
        e2.down(0, 300, 300, 1000);
        check(!r2.pending, "中键按着时按下：不起长按");
    }

    static void scrollHoldNoFlingAndSmooth() {
        // 从边缘带里按下、按着中键快速一划松手：是中键滚动，不起惯性
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.setScrollHold(true);
        e.down(0, 2, 100, 1000, GestureEngine.EDGE_VERTICAL);
        for (int i = 1; i <= 8; i++) {
            e.move(0, 2, 100 + i * 12);
            e.endFrame(1000 + i * 8);
        }
        e.up(0, 1066);
        check(!e.spinning() && !r.has("h" + GestureEngine.HAPTIC_SPIN_START), "中键滚动快速松手：不起惯性");
        check(sumWheel(r) < 0, "边缘带里按下的也按中键滚动的方向（往下 = 往下翻）");
        // 连续模式：按 1/120 格出、斜着划先只出主方向
        Rec r2 = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.smoothScroll = true;
        GestureEngine e2 = new GestureEngine(r2, c);
        e2.setScrollHold(true);
        e2.down(0, 300, 300, 1000);
        for (int i = 1; i <= 10; i++) {
            e2.move(0, 300 + i, 300 + i * 6);
            e2.endFrame(1000 + i * 16);
        }
        boolean partial = false, side = false;
        for (String x : r2.log) {
            int w = x.indexOf(" w=");
            if (w < 0) continue;
            int wheel = Integer.parseInt(x.substring(w + 3, x.indexOf(',', w)));
            int pan = Integer.parseInt(x.substring(x.indexOf(',', w) + 1));
            partial |= wheel % Hid.WHEEL_UNIT != 0;
            side |= pan != 0;
        }
        check(partial && sumWheel(r2) < 0, "连续模式的中键滚动：出小数格");
        check(!side, "竖着为主：横向锁掉");
    }

    static void scrollHoldMidGesture() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.down(0, 300, 300, 1000);
        for (int i = 1; i <= 5; i++) {
            e.move(0, 300 + i * 10, 300);
            e.endFrame(1000 + i * 16);
        }
        check(r.log.stream().anyMatch(x -> x.contains(" d=")), "先移动");
        int at = r.log.size();
        e.setScrollHold(true);
        for (int i = 1; i <= 10; i++) {
            e.move(0, 350, 300 + i * 10);
            e.endFrame(1100 + i * 16);
        }
        List<String> after = r.log.subList(at, r.log.size());
        check(after.stream().noneMatch(x -> x.contains(" d=")) && after.stream().anyMatch(x -> x.contains(" w=")),
                "划到一半按下中键：从下一帧起变成滚动");
        e.setScrollHold(false);
        int at2 = r.log.size();
        for (int i = 1; i <= 5; i++) {
            e.move(0, 350 + i * 10, 400);
            e.endFrame(1300 + i * 16);
        }
        check(r.log.subList(at2, r.log.size()).stream().anyMatch(x -> x.contains(" d=")), "松开中键：接着移动");
        e.cancel();
        check(!e.scrollHold(), "cancel：中键状态清掉");
    }

    static void externalMoveAndScroll() {
        Rec r = new Rec();
        GestureEngine e = engine(r);
        e.setExternalButtons(Proto.BUTTON_LEFT);
        e.externalMove(3, -2);
        e.externalScroll(-120, 0);
        e.externalMove(0, 0);
        e.externalScroll(0, 0);
        eq(r.log, list("m1", "m1 d=3,-2", "m1 w=-120,0"), "小红点的位移、滚轮带着按着的键；0 不发");
        check(e.heldButtons() == Proto.BUTTON_LEFT, "heldButtons = 外部按着的");
    }

    static GestureEngine engine(Rec r) {
        return new GestureEngine(r, new GestureEngine.Config());
    }

    static void tap(GestureEngine e, int id, long t) {
        e.down(id, 50, 50, t);
        e.up(id, t + 60);
    }

    /** 分 5 帧把手指挪到 (x, y)。 */
    static void moveTo(GestureEngine e, int id, float x, float y, long t) {
        for (int i = 1; i <= 5; i++) {
            float fx = 50 + (x - 50) * i / 5f;
            float fy = 50 + (y - 50) * i / 5f;
            e.move(id, fx, fy);
            e.endFrame(t - 50 + i * 10);
        }
    }

    static int sumWheel(Rec r) {
        int sum = 0;
        for (String s : r.log) {
            int i = s.indexOf(" w=");
            if (i >= 0) sum += Integer.parseInt(s.substring(i + 3, s.indexOf(',', i)));
        }
        return sum;
    }

    static int count(Rec r, String entry) {
        int n = 0;
        for (String s : r.log) if (s.equals(entry)) n++;
        return n;
    }

    static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
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
