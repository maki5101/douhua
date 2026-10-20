package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * TouchRouter 的离线测试：从服务的 Session 里搬出来以后，DeX 模式的行为要和原来一样，蓝牙模式也靠它。
 * 屏幕布局：触控板 (0,0)-(1000,1000)；左键按钮、普通按钮、Ctrl 键、C 键、"复制"（Ctrl+C）键在右边。密度 1。
 * 键盘的缝分给最近的键另用一份照 KeyboardPanel 搭的布局（{@link #keyboardRegions}）。
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
