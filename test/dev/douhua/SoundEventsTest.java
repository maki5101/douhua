package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 声音的事件层（SoundEvents，声音第一批第 ③ 步）：不依赖 Android 的部分（tools/test.sh 直接跑）。
 * <ul>
 * <li>声音关着：什么都不放；haptic 一律"照旧震"、wantTicks = 震动开关（和以前逐字一样）。</li>
 * <li>照方案 2.3 的表一条条：键按下 / 松开 / 被取消、特殊键（只给按过的松开出声）、鼠标键、点击类动作的一对、滚轮动作、手柄；
 *     分流报来的震动：轻点 = 像 MacBook 触控板的一对、开始 / 结束拖动、滚一格、惯性放开不出声 / 刹住、按住到点 / 颗粒 / 手势不出声；四个开关各管各的。</li>
 * <li>"滚轮出声、滚轮不震"：只在声音开着、滚轮出声、震动开关关着时，发来的滚轮咔嗒和惯性放开 / 刹住不震。</li>
 * <li>特殊键发声的键 id：功能键、最下一排、每个特殊键自己一组。</li>
 * <li>真 TouchRouter + GestureEngine 跑触摸序列（宿主照 MainActivity 的接法：按钮 → routerButton / 三个鼠标键 → mouseDown / mouseUp /
 *     "左键"按钮（REGION_HOLD_LEFT）→ mouseDown / mouseUp，震动 → haptic）：轻点、双指轻点、双击、长按拖动（松手、手指被取消、releaseAll 收尾）、
 *     滚动、惯性、键、修饰键按住到点、合并键、三个鼠标键、"左键"按钮、被取消的手指、releaseAll、手柄页。</li>
 * <li>面板自己收触摸那条路的映射（panelDown / panelUp：普通键当键、特殊键当特殊键、canceled 不出声）和分流报来的同一个键出的一样。
 *     测不到的：面板（KeyboardPanel / FixedKeyboardView，Android 类）交出的 special、canceled = !tap 对不对、MainActivity 有没有调——靠读代码、真机。</li>
 * </ul>
 * 这些只证明"什么时候放哪个声音"照表走，证明不了好不好听、三星上多晚出声。
 */
public final class SoundEventsTest {
    private static int failures;
    private static int checks;

    /** 记下交出去的声音："名字 键(十六进制)"，晚放的后面跟 " +毫秒"。 */
    static final class Rec implements SoundEvents.Out {
        final List<String> plays = new ArrayList<>();

        @Override
        public void play(int sound, int key, int delayMs) {
            plays.add(NAMES[sound] + " " + Integer.toHexString(key) + (delayMs > 0 ? " +" + delayMs : ""));
        }
    }

    static final String[] NAMES = new String[SoundBank.SOUNDS];

    static {
        NAMES[SoundBank.KEY_DOWN] = "kd";
        NAMES[SoundBank.KEY_UP] = "ku";
        NAMES[SoundBank.MOUSE_DOWN] = "md";
        NAMES[SoundBank.MOUSE_UP] = "mu";
        NAMES[SoundBank.PAD_DOWN] = "pd";
        NAMES[SoundBank.PAD_UP] = "pu";
        NAMES[SoundBank.SCROLL] = "sc";
        NAMES[SoundBank.CATCH] = "ca";
        NAMES[SoundBank.GAMEPAD_DOWN] = "gd";
        NAMES[SoundBank.GAMEPAD_UP] = "gu";
        NAMES[SoundBank.GAMEPAD_TICK] = "gt";
    }

    static final int[] KINDS = {GestureEngine.HAPTIC_CLICK, GestureEngine.HAPTIC_DRAG_START, GestureEngine.HAPTIC_DRAG_END, GestureEngine.HAPTIC_GESTURE,
            GestureEngine.HAPTIC_TICK, GestureEngine.HAPTIC_TEXTURE, GestureEngine.HAPTIC_SPIN_START, GestureEngine.HAPTIC_SPIN_CATCH, GestureEngine.HAPTIC_HOLD,
            GestureEngine.HAPTIC_DRAG_CANCEL};
    static final int[] FROMS = {SoundEvents.FROM_ROUTER, SoundEvents.FROM_GAMEPAD, SoundEvents.FROM_AIR};
    static final String PAIR_PAD = "pd 0", PAIR_PAD_UP = "pu 0 +" + SoundEvents.CLICK_GAP_MS;

    public static void main(String[] args) {
        offIsSilentAndUnchanged();
        keys();
        specialKeys();
        specialKeyIds();
        mouse();
        hapticTable();
        toggles();
        ticksForSoundOnly();
        samePathsSameKey();
        routerTap();
        routerTwoFingerTap();
        routerDoubleTap();
        routerLongPressDrag();
        routerScroll();
        routerSpin();
        routerKeys();
        routerHoldIsSilent();
        routerMouseKeys();
        routerHoldLeft();
        routerGamepad();
        System.err.println("声音的事件层：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static SoundEvents events(Rec r, boolean on) {
        SoundEvents e = new SoundEvents(r);
        e.configure(on, true, true, true, true, true);
        return e;
    }

    /** 每个公开的入口都调一遍（各种参数）。 */
    static void everything(SoundEvents e) {
        e.keyDown(0x04);
        e.keyUp(0x04, false);
        e.keyUp(0x04, true);
        e.specialDown(KeyRows.SYMBOLS_BUTTON_ID);
        e.specialUp(KeyRows.SYMBOLS_BUTTON_ID, false);
        e.mouseDown(Proto.BUTTON_LEFT);
        e.mouseUp(Proto.BUTTON_RIGHT, false);
        e.click(Proto.BUTTON_MIDDLE);
        e.wheel();
        e.gamepadDown(Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_SOUTH));
        e.gamepadUp(Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_SOUTH), false);
        e.routerButton(Proto.KEY_ID_BASE + 0x05, Proto.BUTTON_DOWN);
        e.routerButton(Proto.KEY_ID_BASE + 0x05, Proto.BUTTON_UP);
        for (int k : KINDS) for (int f : FROMS) e.haptic(k, f);
    }

    // ═══════════ 声音关着 ═══════════

    /** 声音关着（默认）：什么都不放；haptic 不管什么一律返回 true（发来的就是该震的）；wantTicks = 震动开关。 */
    static void offIsSilentAndUnchanged() {
        boolean silent = true, vibrate = true, ticks = true;
        for (int m = 0; m < 64; m++) {
            Rec r = new Rec();
            SoundEvents e = new SoundEvents(r);
            boolean scrollHaptic = (m & 32) != 0;
            e.configure(false, (m & 1) != 0, (m & 2) != 0, (m & 4) != 0, (m & 8) != 0, scrollHaptic);
            everything(e);
            silent &= r.plays.isEmpty();
            for (int k : KINDS) for (int f : FROMS) vibrate &= e.haptic(k, f);
            ticks &= e.wantTicks() == scrollHaptic && !e.on();
        }
        check(silent, "声音关着：四个开关怎么设都一个声音不放");
        check(vibrate, "声音关着：每种震动从哪来都照旧震");
        check(ticks, "声音关着：要不要滚轮咔嗒 = 用户的震动开关（发给服务的参数和以前一样）");
    }

    // ═══════════ 一条条 ═══════════

    static void keys() {
        Rec r = new Rec();
        SoundEvents e = events(r, true);
        e.keyDown(0x04);
        e.keyUp(0x04, false);
        eq(r.plays, list("kd 4", "ku 4"), "键按下 / 正常松开");
        r.plays.clear();
        e.keyDown(0x2C);
        e.keyUp(0x2C, true);
        eq(r.plays, list("kd 2c"), "被取消的松开不出声");
        r.plays.clear();
        int dual = Proto.dualKey(0x36, 0x37);
        e.keyDown(dual);
        e.keyUp(dual, false);
        eq(r.plays, list("kd " + Integer.toHexString(dual), "ku " + Integer.toHexString(dual)), "合并键：照它的 id（SoundVary 按点一下的那个查类别）");
    }

    /** 特殊键：按下出声；只给按过的松开出声（分流对 fn🌐 单点只报 🌐 的 TAP、没有按下）；被取消的不出声；收尾 reset 后迟到的松开不出声。 */
    static void specialKeys() {
        Rec r = new Rec();
        SoundEvents e = events(r, true);
        int sym = KeyRows.SYMBOLS_BUTTON_ID, globe = KeyRows.GLOBE_BUTTON_ID, menu = KeyRows.MENU_BUTTON_ID, row = KeyboardPanel_ROW_ACTION_BASE + 3;
        String s = Integer.toHexString(SoundEvents.specialKey(sym));
        e.specialDown(sym);
        e.specialUp(sym, false);
        eq(r.plays, list("kd " + s, "ku " + s), "特殊键按下 / 松开");
        r.plays.clear();
        e.specialUp(globe, false);
        eq(r.plays, list(), "没按过的松开（fn🌐 单点报的 🌐 点击）：不出声");
        e.specialDown(menu);
        e.specialUp(menu, true);
        e.specialUp(menu, false);
        eq(r.plays, list("kd " + Integer.toHexString(SoundEvents.specialKey(menu))), "被取消的松开不出声；配过一次就不再配");
        r.plays.clear();
        // 两根手指按同一个特殊键（两个空格那种副本）：两次松开都出声
        e.specialDown(row);
        e.specialDown(row);
        e.specialUp(row, false);
        e.specialUp(row, false);
        e.specialUp(row, false);
        String rk = Integer.toHexString(SoundEvents.specialKey(row));
        eq(r.plays, list("kd " + rk, "kd " + rk, "ku " + rk, "ku " + rk), "同一个特殊键按两次：配两次松开，第三次不配");
        r.plays.clear();
        e.specialDown(sym);
        e.reset();
        e.specialUp(sym, false);
        eq(r.plays, list("kd " + s), "收尾 reset 之后迟到的松开：不出声");
        // 记满了照样出声（按下），松开认得的那些照常配
        r.plays.clear();
        for (int i = 0; i < SoundEvents.MAX_SPECIAL + 2; i++) e.specialDown(700 + i);
        int ups = 0;
        for (int i = 0; i < SoundEvents.MAX_SPECIAL + 2; i++) {
            int before = r.plays.size();
            e.specialUp(700 + i, false);
            if (r.plays.size() > before) ups++;
        }
        check(r.plays.size() == SoundEvents.MAX_SPECIAL + 2 + ups && ups == SoundEvents.MAX_SPECIAL, "同时按着 " + (SoundEvents.MAX_SPECIAL + 2)
                + " 个特殊键：按下都出声，记得住的 " + SoundEvents.MAX_SPECIAL + " 个松开出声");
        // 都松开以后又空出来了
        r.plays.clear();
        e.specialDown(sym);
        e.specialUp(sym, false);
        eq(r.plays.size(), 2, "松开以后位置空出来，接着照常配");
    }

    /** 特殊键发声的键 id：SoundVary 当它是功能键、在最下一排；每个特殊键各不相同，也不和任何普通键 / 组合键撞（互斥的组各是各的）。 */
    static void specialKeyIds() {
        int[] ids = {KeyRows.SYMBOLS_BUTTON_ID, KeyRows.FN_BUTTON_ID, KeyRows.GLOBE_BUTTON_ID, KeyRows.MENU_BUTTON_ID};
        Set<Integer> seen = new HashSet<>();
        boolean ok = true;
        List<Integer> all = new ArrayList<>();
        for (int id : ids) all.add(id);
        for (int i = 0; i < 12; i++) all.add(KeyboardPanel_ROW_ACTION_BASE + i);
        for (int id : all) {
            int k = SoundEvents.specialKey(id);
            ok &= SoundVary.keyClass(k) == SoundVary.CLASS_FUNCTION && SoundVary.row(k) == 5 && SoundVary.usage(k) == SoundEvents.SPECIAL_USAGE
                    && k > 0xFFFF && !Proto.isFnKey(k) && !Proto.isFnLayerKey(k) && !Proto.isFrowKey(k) && seen.add(k);
        }
        check(ok, "特殊键（?123 / Fn / 🌐 / ⋯ / 快捷行 12 个）：功能键、最下一排、各不相同、不和普通键 / Fn / F 行键撞");
    }

    static void mouse() {
        Rec r = new Rec();
        SoundEvents e = events(r, true);
        e.mouseDown(Proto.BUTTON_LEFT);
        e.mouseUp(Proto.BUTTON_LEFT, false);
        e.mouseDown(Proto.BUTTON_MIDDLE);
        e.mouseUp(Proto.BUTTON_MIDDLE, true);
        eq(r.plays, list("md 1", "mu 1", "md 4"), "鼠标键按下 / 松开；被取消的不出声");
        r.plays.clear();
        e.click(Proto.BUTTON_RIGHT);
        eq(r.plays, list("md 2", "mu 2 +" + SoundEvents.CLICK_GAP_MS), "点击类动作：按下 + 45 ms 后松开（一对）");
        check(SoundEvents.CLICK_GAP_MS >= 40 && SoundEvents.CLICK_GAP_MS <= 60 && SoundEvents.CLICK_GAP_MS <= SoundMixer.MAX_DELAY_MS,
                "一对的间隔在方案的 40–60 ms 里、请求写得下");
        r.plays.clear();
        e.wheel();
        e.gamepadDown(0x4001);
        e.gamepadUp(0x4001, false);
        e.gamepadDown(0x4002);
        e.gamepadUp(0x4002, true);
        eq(r.plays, list("sc 0", "gd 4001", "gu 4001", "gd 4002"), "滚轮动作一格；手柄按下 / 松开，被取消的不出声");
    }

    /** 分流报来的震动 → 声音（全开着），和返回的"震不震"（震动开关开着：一律震）。 */
    static void hapticTable() {
        Object[][] table = {
                // kind, from, 期望的声音
                {GestureEngine.HAPTIC_CLICK, SoundEvents.FROM_ROUTER, list(PAIR_PAD, PAIR_PAD_UP)},
                {GestureEngine.HAPTIC_CLICK, SoundEvents.FROM_GAMEPAD, list()},
                {GestureEngine.HAPTIC_CLICK, SoundEvents.FROM_AIR, list()},
                {GestureEngine.HAPTIC_DRAG_START, SoundEvents.FROM_ROUTER, list("pd 0")},
                {GestureEngine.HAPTIC_DRAG_START, SoundEvents.FROM_AIR, list("md 1")},
                {GestureEngine.HAPTIC_DRAG_START, SoundEvents.FROM_GAMEPAD, list()},
                {GestureEngine.HAPTIC_DRAG_END, SoundEvents.FROM_ROUTER, list("pu 0")},
                {GestureEngine.HAPTIC_DRAG_END, SoundEvents.FROM_AIR, list("mu 1")},
                {GestureEngine.HAPTIC_DRAG_END, SoundEvents.FROM_GAMEPAD, list()},
                // 拖动被取消着结束（协议 25：手掌、收尾、换参数；空中鼠标收尾放掉的）：不出松开声
                {GestureEngine.HAPTIC_DRAG_CANCEL, SoundEvents.FROM_ROUTER, list()},
                {GestureEngine.HAPTIC_DRAG_CANCEL, SoundEvents.FROM_AIR, list()},
                {GestureEngine.HAPTIC_DRAG_CANCEL, SoundEvents.FROM_GAMEPAD, list()},
                {GestureEngine.HAPTIC_TICK, SoundEvents.FROM_ROUTER, list("sc 0")},
                {GestureEngine.HAPTIC_TICK, SoundEvents.FROM_GAMEPAD, list("gt 0")},
                {GestureEngine.HAPTIC_TICK, SoundEvents.FROM_AIR, list()},
                {GestureEngine.HAPTIC_SPIN_START, SoundEvents.FROM_ROUTER, list()},
                {GestureEngine.HAPTIC_SPIN_CATCH, SoundEvents.FROM_ROUTER, list("ca 0")},
                {GestureEngine.HAPTIC_HOLD, SoundEvents.FROM_ROUTER, list()},
                {GestureEngine.HAPTIC_HOLD, SoundEvents.FROM_GAMEPAD, list()},
                {GestureEngine.HAPTIC_TEXTURE, SoundEvents.FROM_ROUTER, list()},
                {GestureEngine.HAPTIC_TEXTURE, SoundEvents.FROM_AIR, list()},
                {GestureEngine.HAPTIC_GESTURE, SoundEvents.FROM_ROUTER, list()},
        };
        for (Object[] row : table) {
            Rec r = new Rec();
            SoundEvents e = events(r, true);
            boolean vib = e.haptic((Integer) row[0], (Integer) row[1]);
            eq(r.plays, row[2], "震动 " + row[0] + " 从 " + row[1] + " 来：放的声音");
            check(vib, "震动 " + row[0] + " 从 " + row[1] + " 来：震动开关开着时照旧震");
        }
    }

    /** 四个开关各管各的：关掉一个，只有它那一类不出声。 */
    static void toggles() {
        String[] names = {"键盘", "鼠标键", "滚轮", "手柄"};
        for (int off = 0; off < 4; off++) {
            Rec r = new Rec();
            SoundEvents e = new SoundEvents(r);
            e.configure(true, off != 0, off != 1, off != 2, off != 3, true);
            Rec all = new Rec();
            SoundEvents a = new SoundEvents(all);
            a.configure(true, true, true, true, true, true);
            everything(e);
            everything(a);
            List<String> missing = new ArrayList<>(all.plays);
            for (String p : r.plays) missing.remove(p);
            boolean onlyThat = true;
            for (String p : missing) {
                String n = p.substring(0, 2);
                boolean belongs;
                switch (off) {
                    case 0: belongs = n.equals("kd") || n.equals("ku"); break;
                    case 1: belongs = n.equals("md") || n.equals("mu") || n.equals("pd") || n.equals("pu"); break;
                    case 2: belongs = n.equals("sc") || n.equals("ca"); break;
                    default: belongs = n.equals("gd") || n.equals("gu") || n.equals("gt"); break;
                }
                onlyThat &= belongs;
            }
            boolean nothingLeft = true;
            for (String p : r.plays) {
                String n = p.substring(0, 2);
                switch (off) {
                    case 0: nothingLeft &= !n.equals("kd") && !n.equals("ku"); break;
                    case 1: nothingLeft &= !n.equals("md") && !n.equals("mu") && !n.equals("pd") && !n.equals("pu"); break;
                    case 2: nothingLeft &= !n.equals("sc") && !n.equals("ca"); break;
                    default: nothingLeft &= !n.equals("gd") && !n.equals("gu") && !n.equals("gt"); break;
                }
            }
            check(!missing.isEmpty() && onlyThat && nothingLeft, "关掉" + names[off] + "：只有" + names[off] + "那一类不出声（少了 " + missing.size() + " 个）");
        }
    }

    /**
     * 滚轮出声、滚轮不震：wantTicks = 震动开关 || (声音开着 && 滚轮出声)；haptic 只在声音开着、滚轮出声、震动开关关着时，
     * 对滚一格（分流来的）、惯性放开 / 刹住返回 false（这时它们照样出声），别的一律 true。
     */
    static void ticksForSoundOnly() {
        boolean ok = true;
        for (int m = 0; m < 8; m++) {
            boolean on = (m & 1) != 0, scroll = (m & 2) != 0, haptic = (m & 4) != 0;
            Rec r = new Rec();
            SoundEvents e = new SoundEvents(r);
            e.configure(on, true, true, scroll, true, haptic);
            ok &= e.wantTicks() == (haptic || (on && scroll));
            boolean soundOnly = on && scroll && !haptic;
            for (int k : KINDS) {
                for (int f : FROMS) {
                    boolean vib = e.haptic(k, f);
                    boolean tickLike = (k == GestureEngine.HAPTIC_TICK && f == SoundEvents.FROM_ROUTER)
                            || k == GestureEngine.HAPTIC_SPIN_START || k == GestureEngine.HAPTIC_SPIN_CATCH;
                    if (vib != !(soundOnly && tickLike)) {
                        ok = false;
                        check(false, "震不震：声音 " + on + " 滚轮出声 " + scroll + " 震动开关 " + haptic + " 种类 " + k + " 从 " + f);
                    }
                }
            }
        }
        check(ok, "滚轮咔嗒：只为出声才让引擎发的滚一格 / 惯性放开 / 刹住不震，别的照旧；手柄的咔嗒（自己的开关）、空中鼠标的照旧震");
    }

    /**
     * 面板自己收触摸那条路（MainActivity.onKeyHaptic / onKeyRelease → panelDown / panelUp）和分流报来的（routerButton(KEY_ID_BASE + 同一个 id)；
     * 特殊键是 onButton → onKeyHaptic / specialUp）出的声音一样：普通键当键（键 id 原样）、特殊键当特殊键（只给按过的出松开声）、被取消的不出声。
     * 只锁住事件层这一层；面板交出的 special、canceled = !tap（Android 类）测不到。
     */
    static void samePathsSameKey() {
        int[] ids = {0x04, 0x2C, 0x28, 0xE1, 0x1F | (Hid.MOD_SHIFT << 8), Proto.dualKey(0x36, 0x37), Proto.FN_KEY, Proto.FN_GLOBE_KEY,
                Proto.fnLayerKey(0x52, 0x4B), Proto.frowKey(Proto.FROW_CONSUMER, 0x3A, 0xE2), Proto.FN_LOCK_KEY};
        boolean ok = true;
        for (int id : ids) {
            Rec a = new Rec(), b = new Rec();
            SoundEvents ea = events(a, true), eb = events(b, true);
            ea.panelDown(id, false);
            ea.panelUp(id, false, false);
            ea.panelDown(id, false);
            ea.panelUp(id, false, true);
            boolean handled = eb.routerButton(Proto.KEY_ID_BASE + id, Proto.BUTTON_DOWN);
            handled &= eb.routerButton(Proto.KEY_ID_BASE + id, Proto.BUTTON_UP);
            handled &= eb.routerButton(Proto.KEY_ID_BASE + id, Proto.BUTTON_DOWN);
            handled &= eb.routerButton(Proto.KEY_ID_BASE + id, Proto.BUTTON_UP | Proto.BUTTON_CANCELED);
            ok &= handled && a.plays.equals(b.plays) && a.plays.size() == 3;
        }
        check(ok, "面板自己收触摸 / 分流报来的同一个键：出的一样（普通、组合、合并、Fn、Fn 层、F 行键），被取消的都不出松开声");
        // 特殊键：面板那条路（panelDown / panelUp special）和分流那条路（specialDown / specialUp，MainActivity.onButton）一样；没按过的松开不出声
        int[] specials = {KeyRows.SYMBOLS_BUTTON_ID, KeyboardPanel_ROW_ACTION_BASE + 2};
        boolean sp = true;
        for (int id : specials) {
            Rec a = new Rec(), b = new Rec();
            SoundEvents ea = events(a, true), eb = events(b, true);
            ea.panelUp(id, true, false);   // 没按过：不出声
            ea.panelDown(id, true);
            ea.panelUp(id, true, false);
            ea.panelDown(id, true);
            ea.panelUp(id, true, true);
            eb.specialUp(id, false);
            eb.specialDown(id);
            eb.specialUp(id, false);
            eb.specialDown(id);
            eb.specialUp(id, true);
            String k = Integer.toHexString(SoundEvents.specialKey(id));
            sp &= a.plays.equals(b.plays) && a.plays.equals(list("kd " + k, "ku " + k, "kd " + k));
        }
        check(sp, "特殊键：面板那条路和分流那条路一样（当特殊键，只给按过的出松开声，被取消的不出）");
        Rec r = new Rec();
        SoundEvents e = events(r, true);
        boolean others = !e.routerButton(KeyRows.SYMBOLS_BUTTON_ID, Proto.BUTTON_DOWN) && !e.routerButton(Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_LEFT, Proto.BUTTON_DOWN)
                && !e.routerButton(Proto.STICK_ID, Proto.BUTTON_DOWN) && !e.routerButton(7, Proto.BUTTON_TAP);
        check(others && r.plays.isEmpty(), "routerButton 只认键盘键和手柄控件，别的编号不认、不出声");
    }

    // ═══════════ 真 TouchRouter 跑 ═══════════

    static final int KEY_C = 0x06, SHIFT = 0xE1, DUAL = Proto.dualKey(0x36, 0x37);
    static final int M_LEFT = Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_LEFT, M_MIDDLE = Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_MIDDLE,
            M_RIGHT = Proto.MOUSE_BUTTON_ID_BASE | Proto.BUTTON_RIGHT;
    static final int GP_A = Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_SOUTH);
    /** 按钮条 / 角上的"左键"按钮（REGION_HOLD_LEFT，编号随便取一个界面按钮的）。 */
    static final int HOLD_LEFT_ID = 3;
    /** 豆花布局快捷行动作的按钮编号基数（KeyboardPanel.ROW_ACTION_BASE_ID，那个类离线编不到）。 */
    static final int KeyboardPanel_ROW_ACTION_BASE = 600;

    /**
     * 宿主：照 MainActivity 的接法把分流的输出交给事件层——按钮先 routerButton（键盘键、手柄），三个鼠标键 mouseDown / mouseUp（MainActivity.mouseKeyButton），
     * 震动 haptic（手柄页上是 FROM_GAMEPAD），记下震了哪些（按住到点照点一下震）。
     */
    static final class Host implements TouchRouter.Output {
        final Rec sounds = new Rec();
        final SoundEvents ev = new SoundEvents(sounds);
        final List<Integer> vibrations = new ArrayList<>();
        boolean gamepadPage;
        boolean pending, keyPending;

        @Override public void mouse(int buttons, int dx, int dy, int wheel, int pan) { }
        @Override public void keyboard(byte[] report) { }
        @Override public void consumer(int usage) { }
        @Override public void system(int usage) { }
        @Override public void gesture(int fingers, int kind) { }
        @Override public void modifiers(int latched, int held, int armed, int fn) { }
        @Override public void caps(boolean sent) { }
        @Override public void schedule(long delayMs) { pending = true; }
        @Override public void cancelSchedule() { pending = false; }
        @Override public void scheduleKeys(long delayMs) { keyPending = true; }
        @Override public void cancelKeys() { keyPending = false; }
        @Override public void startTicks() { }
        @Override public void stopTicks() { }
        @Override public void gamepad(byte[] report) { }

        @Override
        public void haptic(int kind) {
            if (ev.haptic(kind, gamepadPage ? SoundEvents.FROM_GAMEPAD : SoundEvents.FROM_ROUTER)) {   // 同 MainActivity.onHaptic / Haptics：按住到点照点一下震、拖动被取消照拖动结束震
                vibrations.add(kind == GestureEngine.HAPTIC_HOLD ? GestureEngine.HAPTIC_CLICK
                        : kind == GestureEngine.HAPTIC_DRAG_CANCEL ? GestureEngine.HAPTIC_DRAG_END : kind);
            }
        }

        @Override
        public void button(int id, int action) {
            if (ev.routerButton(id, action)) return;
            if (id >= Proto.MOUSE_BUTTON_ID_BASE && id < Proto.MOUSE_BUTTON_ID_BASE + 8) {
                int mask = id - Proto.MOUSE_BUTTON_ID_BASE;
                if (Proto.buttonAction(action) == Proto.BUTTON_DOWN) ev.mouseDown(mask);
                else ev.mouseUp(mask, Proto.buttonCanceled(action));
            } else if (id == HOLD_LEFT_ID) {   // 同 MainActivity.buttonAction 的 HOLD_LEFT：按下"咔"、松开"哒"，被取消的不出声
                if (Proto.buttonAction(action) == Proto.BUTTON_DOWN) ev.mouseDown(Proto.BUTTON_LEFT);
                else ev.mouseUp(Proto.BUTTON_LEFT, Proto.buttonCanceled(action));
            }
        }
    }

    /** 触控板 (0,0)–(1000,1000)；右边一列键 C、Shift、合并键、三个鼠标键；手柄 A。 */
    static TouchRouter router(Host h, GestureEngine.Config c) {
        TouchRouter t = new TouchRouter(h);
        t.setConfig(c, 1);
        t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_KEY, KEY_C, 1000, 300, 1100, 400),
                new TouchRouter.Region(Proto.REGION_KEY, SHIFT, 1000, 400, 1100, 500),
                new TouchRouter.Region(Proto.REGION_KEY, DUAL, 1000, 500, 1100, 600),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_LEFT, 2000, 110, 2030, 150),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_MIDDLE, 2035, 110, 2065, 150),
                new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, M_RIGHT, 2070, 110, 2100, 150),
                new TouchRouter.Region(Proto.REGION_GAMEPAD, GP_A, 3000, 0, 3060, 60),
                new TouchRouter.Region(Proto.REGION_HOLD_LEFT, HOLD_LEFT_ID, 4000, 0, 4100, 100),
                new TouchRouter.Region(Proto.REGION_PAD, 0, 0, 0, 1000, 1000),
        });
        return t;
    }

    static Host host(boolean on) {
        Host h = new Host();
        h.ev.configure(on, true, true, true, true, true);
        return h;
    }

    static TouchRouter router(Host h) {
        GestureEngine.Config c = new GestureEngine.Config();
        c.scrollTicks = h.ev.wantTicks() ? GestureEngine.TICKS_ALL : GestureEngine.TICKS_OFF;   // 同 MainActivity.engineConfig
        return router(h, c);
    }

    static void timers(TouchRouter t, Host h, long t0, int n) {
        for (int i = 1; i <= n && h.pending; i++) {
            h.pending = false;
            t.onTimer(t0 + i * 16);
        }
    }

    /** 单指轻点：认出是轻点那一刻放一对（像 MacBook 触控板）；之后定时器松开左键不再出声；震一下点一下。 */
    static void routerTap() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 500, 500, 1000);
        t.frame(1000);
        eq(h.sounds.plays, list(), "按下时还不知道是不是轻点：不出声");
        t.up(0, 1060);
        eq(h.sounds.plays, list(PAIR_PAD, PAIR_PAD_UP), "抬起认出轻点：一对（按下 + 45 ms 后松开）");
        timers(t, h, 1060, 5);   // 0.22 秒后松开左键
        eq(h.sounds.plays.size(), 2, "定时器松开左键：不再出声");
        eq(h.vibrations, ints(GestureEngine.HAPTIC_CLICK), "震动照旧：点一下");
        // 声音关着：一个不放、震动一样
        Host off = host(false);
        TouchRouter to = router(off);
        to.down(0, 500, 500, 1000);
        to.up(0, 1060);
        timers(to, off, 1060, 5);
        check(off.sounds.plays.isEmpty() && off.vibrations.equals(h.vibrations), "声音关着：不出声、震动一模一样");
    }

    static void routerTwoFingerTap() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 400, 500, 1000);
        t.down(1, 600, 500, 1000);
        t.frame(1000);
        t.up(0, 1080);
        t.up(1, 1080);
        eq(h.sounds.plays, list(PAIR_PAD, PAIR_PAD_UP), "双指轻点（右键）：一对");
    }

    static void routerDoubleTap() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 500, 500, 1000);
        t.up(0, 1050);
        t.down(0, 500, 500, 1120);   // 0.22 秒的窗口里再点
        t.up(0, 1170);
        timers(t, h, 1170, 8);
        eq(h.sounds.plays, list(PAIR_PAD, PAIR_PAD_UP, PAIR_PAD, PAIR_PAD_UP), "双击：每一下一对");
    }

    static void routerLongPressDrag() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 500, 500, 1000);
        t.frame(1000);
        h.pending = false;
        t.onTimer(1450);   // 长按到点：左键按下
        eq(h.sounds.plays, list("pd 0"), "长按拖动开始：触控板按下");
        for (int i = 1; i <= 5; i++) {
            t.move(0, 500 + i * 20, 500);
            t.frame(1450 + i * 16);
        }
        t.up(0, 1600);
        eq(h.sounds.plays, list("pd 0", "pu 0"), "松手结束拖动：触控板松开");
        eq(h.vibrations, ints(GestureEngine.HAPTIC_DRAG_START, GestureEngine.HAPTIC_DRAG_END), "震动照旧：开始 / 结束拖动");
        // 拖着的时候手指被取消（手掌、收尾补报的抬起）、releaseAll（释放触摸屏 / 退后台）、换参数：不是松了手，不出"哒"；震动照旧开始 / 结束拖动
        // （审查发现以前三种都出 PAD_UP）
        for (int w = 0; w < 3; w++) {
            Host c = host(true);
            TouchRouter tc = router(c);
            tc.down(0, 500, 500, 1000);
            tc.frame(1000);
            c.pending = false;
            tc.onTimer(1450);
            tc.move(0, 560, 500);
            tc.frame(1500);
            String what;
            if (w == 0) {
                what = "手指被取消";
                tc.up(0, 1600, true);
            } else if (w == 1) {
                what = "releaseAll";
                tc.releaseAll();
            } else {
                what = "换参数";
                tc.setConfig(new GestureEngine.Config(), 1);
            }
            eq(c.sounds.plays, list("pd 0"), "拖着的时候" + what + "：只有开始的那一声、没有松开声");
            eq(c.vibrations, ints(GestureEngine.HAPTIC_DRAG_START, GestureEngine.HAPTIC_DRAG_END), "拖着的时候" + what + "：震动照旧（拖动结束）");
        }
    }

    /** 双指滚：每一格的咔嗒一声（快了 SoundVary 再合并）。"滚轮出声、不震"：引擎照样发咔嗒，只出声不震；声音、震动都关：引擎不发。 */
    static void routerScroll() {
        for (int m = 0; m < 3; m++) {
            Host h = new Host();
            boolean on = m != 2, haptic = m == 0;
            h.ev.configure(on, true, true, true, true, haptic);
            TouchRouter t = router(h);
            t.down(0, 400, 300, 1000);
            t.down(1, 600, 300, 1000);
            t.frame(1000);
            for (int i = 1; i <= 20; i++) {
                t.move(0, 400, 300 + i * 10);
                t.move(1, 600, 300 + i * 10);
                t.frame(1000 + i * 16);
            }
            t.up(0, 1400);
            t.up(1, 1400);
            int ticks = 0;
            for (String p : h.sounds.plays) if (p.startsWith("sc")) ticks++;
            int buzz = 0;
            for (int v : h.vibrations) if (v == GestureEngine.HAPTIC_TICK) buzz++;
            if (m == 0) check(ticks > 3 && buzz == ticks && h.sounds.plays.size() == ticks, "滚动（声音、震动都开）：每格一声一震（" + ticks + " 格）");
            else if (m == 1) check(ticks > 3 && buzz == 0 && h.sounds.plays.size() == ticks, "滚动（震动开关关着、滚轮出声）：引擎照样发，只出声不震（" + ticks + " 格）");
            else check(ticks == 0 && buzz == 0, "滚动（声音关、震动开关关）：和以前一样，引擎不发、什么都没有");
        }
    }

    /** 惯性：边缘一拨放开不出声；转着时碰触控板刹住，轻一声；碰键静默停（只有键自己的声音）。 */
    static void routerSpin() {
        Host h = host(true);
        TouchRouter t = router(h);
        long up = flick(t, 1000);
        check(h.pending, "边缘一拨：起惯性");
        boolean noSpinSound = true;
        for (String p : h.sounds.plays) noSpinSound &= p.startsWith("sc");
        check(noSpinSound && h.vibrations.contains(GestureEngine.HAPTIC_SPIN_START), "放开：震一下、不出声（滑的那几格照常咔嗒）");
        int at = h.sounds.plays.size();
        t.down(1, 500, 500, up + 30);   // 碰触控板
        eq(h.sounds.plays.subList(at, h.sounds.plays.size()), list("ca 0"), "刹住：轻一声");
        t.up(1, up + 80);
        eq(h.sounds.plays.subList(at, h.sounds.plays.size()), list("ca 0"), "抓住的那一下不算点击：没有触控板的一对");
        Host k = host(true);
        TouchRouter tk = router(k);
        long u2 = flick(tk, 1000);
        int at2 = k.sounds.plays.size();
        tk.down(1, 1050, 350, u2 + 30);   // 碰 C 键
        eq(k.sounds.plays.subList(at2, k.sounds.plays.size()), list("kd 6"), "转着时按键：静默停，只有按键声");
    }

    static long flick(TouchRouter t, long t0) {
        t.down(0, 10, 300, t0);
        for (int i = 1; i <= 8; i++) {
            t.move(0, 10, 300 + i * 10);
            t.frame(t0 + i * 8);
        }
        t.up(0, t0 + 69);
        return t0 + 69;
    }

    /** 键：按下 / 松开；被取消的抬起、releaseAll 收尾都不出松开声。 */
    static void routerKeys() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 1050, 350, 1000);
        t.up(0, 1080);
        eq(h.sounds.plays, list("kd 6", "ku 6"), "按键：按下、松开");
        h.sounds.plays.clear();
        t.down(0, 1050, 350, 1100);
        t.up(0, 1150, true);   // 手掌 / 服务收尾补报的抬起
        eq(h.sounds.plays, list("kd 6"), "被取消的抬起：只有按下声");
        h.sounds.plays.clear();
        t.down(0, 1050, 350, 1200);
        t.releaseAll();
        t.up(0, 1250);   // 迟到的抬起（slot 已经清了）
        eq(h.sounds.plays, list("kd 6"), "releaseAll 收尾、之后迟到的抬起：只有按下声");
    }

    /** 修饰键按住到点（快锁了）、合并键按住出第二个字：震一下，但不出声（软件状态）；按下、松开照常。 */
    static void routerHoldIsSilent() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 1050, 450, 1000);   // Shift
        t.onKeyTimer(1400);
        t.up(0, 1500);
        eq(h.sounds.plays, list("kd e1", "ku e1"), "Shift 按住 0.5 秒锁住：按下、松开两声，快锁了那一下不出声");
        eq(h.vibrations, ints(GestureEngine.HAPTIC_CLICK), "快锁了那一下照旧震（照点一下震）");
        Host d = host(true);
        TouchRouter td = router(d);
        td.down(0, 1050, 550, 1000);   // 合并键
        td.onKeyTimer(1400);           // 按住 0.4 秒出句号
        td.up(0, 1600);
        String id = Integer.toHexString(DUAL);
        eq(d.sounds.plays, list("kd " + id, "ku " + id), "合并键按住出句号：只有按下、松开两声");
        check(d.vibrations.size() == 1, "出句号那一下照旧震一下");
    }

    /** 三个鼠标键：按下 / 松开（右键、中键），被取消、releaseAll 不出松开声。 */
    static void routerMouseKeys() {
        Host h = host(true);
        TouchRouter t = router(h);
        t.down(0, 2085, 130, 1000);   // 右键
        t.up(0, 1080);
        t.down(1, 2050, 130, 1100);   // 中键
        t.up(1, 1180, true);
        t.down(2, 2015, 130, 1200);   // 左键
        t.releaseAll();
        eq(h.sounds.plays, list("md 2", "mu 2", "md 4", "md 1"), "三个鼠标键：右键一对；中键被取消、左键 releaseAll 只有按下声");
    }

    /**
     * 按钮条 / 角上的"左键"按钮（REGION_HOLD_LEFT，分流直接按住左键，MainActivity.buttonAction 出声）：按下"咔"、松开"哒"；
     * 被取消的手指、releaseAll（服务释放触摸屏、退后台、心跳超时）只有按下声（审查发现这一种的 releaseAll 以前没测）。
     */
    static void routerHoldLeft() {
        for (int w = 0; w < 3; w++) {
            Host h = host(true);
            TouchRouter t = router(h);
            t.down(0, 4050, 50, 1000);
            String what;
            if (w == 0) {
                what = "松手";
                t.up(0, 1300);
            } else if (w == 1) {
                what = "手指被取消";
                t.up(0, 1300, true);
            } else {
                what = "releaseAll";
                t.releaseAll();
            }
            eq(h.sounds.plays, w == 0 ? list("md 1", "mu 1") : list("md 1"), "\"左键\"按钮" + what + "：" + (w == 0 ? "按下、松开" : "只有按下声"));
        }
    }

    /** 手柄页：键按下 / 松开（手柄开关开着），被取消的不出松开声；手柄开关关着（默认）一个都不放；震动照旧。 */
    static void routerGamepad() {
        for (int m = 0; m < 2; m++) {
            Host h = new Host();
            h.gamepadPage = true;
            h.ev.configure(true, true, true, true, m == 1, true);
            TouchRouter t = router(h);
            t.down(0, 3030, 30, 1000);
            t.up(0, 1080);
            t.down(0, 3030, 30, 1100);
            t.releaseAll();
            String a = Integer.toHexString(GP_A);
            if (m == 1) eq(h.sounds.plays, list("gd " + a, "gu " + a, "gd " + a), "手柄（开着）：按下、松开；收尾的松开不出声");
            else eq(h.sounds.plays, list(), "手柄（默认关）：不出声");
        }
    }

    // ═══════════ 小工具 ═══════════

    static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    static List<Integer> ints(int... v) {
        List<Integer> l = new ArrayList<>();
        for (int x : v) l.add(x);
        return l;
    }

    static void eq(Object actual, Object expected, String what) {
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        if (!ok) System.err.println("✗ " + what + "：期望 " + expected + "，实际 " + actual);
        check(ok, what);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
