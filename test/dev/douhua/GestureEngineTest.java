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
            log.add("s" + delayMs);
        }

        @Override
        public void cancelSchedule() {
            scheduled = -1;
            log.add("c");
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
        twoFingerScrollTicksOnlyWhenAll();
        longPressDrags();
        longPressReleaseIsClick();
        moveCancelsLongPress();
        longPressDisabled();
        secondFingerEndsLongDrag();

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

    static void twoFingerScrollTicksOnlyWhenAll() {
        Rec r = new Rec();
        GestureEngine.Config edgeOnly = new GestureEngine.Config();
        edgeOnly.scrollTicks = GestureEngine.TICKS_EDGE;
        GestureEngine e = new GestureEngine(r, edgeOnly);
        e.down(0, 50, 50, 1000);
        e.down(1, 80, 50, 1000);
        for (int i = 1; i <= 7; i++) {
            e.move(0, 50, 50 + i * 10);
            e.move(1, 80, 50 + i * 10);
            e.endFrame(1000 + i * 16);
        }
        e.up(0, 1200);
        e.up(1, 1200);
        eq(count(r, "h" + GestureEngine.HAPTIC_TICK), 0, "选了「只在边缘」时双指滚动不咔嗒");

        Rec r2 = new Rec();
        GestureEngine.Config c = new GestureEngine.Config();
        c.scrollTicks = GestureEngine.TICKS_ALL;
        GestureEngine e2 = new GestureEngine(r2, c);
        e2.down(0, 50, 50, 1000);
        e2.down(1, 80, 50, 1000);
        for (int i = 1; i <= 7; i++) {
            e2.move(0, 50, 50 + i * 10);
            e2.move(1, 80, 50 + i * 10);
            e2.endFrame(1000 + i * 16);
        }
        check(count(r2, "h" + GestureEngine.HAPTIC_TICK) > 0, "选了「所有滚动」时双指滚动也咔嗒");
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

    // ═══════════ 工具 ═══════════

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
