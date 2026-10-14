package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * TouchRouter 的离线测试：从服务的 Session 里搬出来以后，DeX 模式的行为要和原来一样，蓝牙模式也靠它。
 * 屏幕布局：触控板 (0,0)-(1000,1000)；左键按钮、普通按钮、Ctrl 键、C 键、"复制"（Ctrl+C）键在右边。密度 1。
 */
public final class TouchRouterTest {
    static final int PAD = 0, HOLD = 1, BUTTON = 7, CTRL = 0xE0, KEY_C = 0x06, COPY = 0x06 | (Hid.MOD_CTRL << 8);

    static final class Rec implements TouchRouter.Output {
        final List<String> log = new ArrayList<>();
        long scheduled = -1;

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
        }

        @Override
        public void cancelSchedule() {
            scheduled = -1;
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
        buttonTapAndSlideOff();
        padEdgeScrolls();
        pinchHoldsCtrl();
        releaseAllLetsGo();
        keyFromAppSide();
        regionsInFrontWin();
        descriptorHasReportIds();
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
