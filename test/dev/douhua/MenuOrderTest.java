package dev.douhua;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MenuOrder 的离线测试（⋯ 菜单按使用频率排，2026-11-01 晚）：每一屏 × 桌面 / 蓝牙模式 × 小红点 × 实体键盘，显示出来的项和顺序照协调者定的那张表；
 * 不管哪一屏、哪种状态，设置一定第一个、退出一定最后、手柄（手柄页上是返回触控板）在的话一定倒数第二；编号不重复、编号的值没变（区域、按钮事件按编号认）。
 */
public final class MenuOrderTest {
    private static int failures;
    private static int checks;

    static final int S = MenuOrder.SETTINGS, K = MenuOrder.KEYBOARD, R = MenuOrder.ROTATE, G = MenuOrder.GAMEPAD, X = MenuOrder.EXIT,
            CLOSE = MenuOrder.KB_CLOSE, BOARD = MenuOrder.BOARD, PTR = MenuOrder.KB_POINTER, LAYOUT = MenuOrder.KB_LAYOUT, ADJ = MenuOrder.KB_ADJUST,
            PRESET = MenuOrder.GP_PRESET, BACK = MenuOrder.GP_BACK;

    public static void main(String[] args) {
        ids();
        tables();
        invariants();
        System.err.println("⋯ 菜单的顺序：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    /** 编号的值照旧（MainActivity 以前写死的那几个；改了的话区域上报、按钮事件对不上旧的分支）、不重复、和别的按钮编号不撞。 */
    static void ids() {
        int[][] want = {{S, 411}, {K, 410}, {R, 412}, {G, 414}, {X, 413}, {CLOSE, 401}, {ADJ, 402}, {LAYOUT, 403}, {PTR, 404},
                {PRESET, 415}, {BACK, 416}, {BOARD, 418}};
        for (int[] w : want) check(w[0] == w[1], "编号 " + w[1] + " 没变（" + w[0] + "）");
        Set<Integer> seen = new HashSet<>();
        for (int id : MenuOrder.ORDER) check(seen.add(id), "ORDER 里不重复：" + id);
        check(MenuOrder.ORDER.length == 12, "十二项");
        for (int i = 0; i < MenuOrder.ORDER.length; i++) check(MenuOrder.index(MenuOrder.ORDER[i]) == i, "index 对得回来：" + MenuOrder.ORDER[i]);
        check(MenuOrder.index(405) == -1 && MenuOrder.index(408) == -1 && MenuOrder.index(400) == -1, "点外面、底板、⋯ 自己不是菜单项");
        for (int id : MenuOrder.ORDER) {
            check(id != Proto.GLOBE_BUTTON_ID && id < Proto.KEY_ID_BASE && id < Proto.MOUSE_BUTTON_ID_BASE, "菜单项编号不和 🌐、键、鼠标键的编号撞：" + id);
        }
    }

    /** 协调者定的表（方案第 6 条）：每屏每种状态显示哪几项、什么顺序。 */
    static void tables() {
        // 主界面：设置 · 键盘 · 旋转 · 手柄 · 退出（手柄只在桌面模式）
        for (boolean stick : new boolean[]{false, true}) for (boolean fixed : new boolean[]{false, true}) {
            eq(items(MenuOrder.SCREEN_MAIN, true, stick, fixed), list(S, K, R, G, X), "主界面（桌面模式）");
            eq(items(MenuOrder.SCREEN_MAIN, false, stick, fixed), list(S, K, R, X), "主界面（蓝牙模式：没有手柄）");
        }
        // 键盘页：设置 · 收起键盘 · 键盘 · 指点 · 布局 · 调整大小 · 旋转 · 手柄 · 退出；布局只在豆花布局、不是小红点时有
        eq(items(MenuOrder.SCREEN_KEYBOARD, true, false, false), list(S, CLOSE, BOARD, PTR, LAYOUT, ADJ, R, G, X), "键盘页（豆花布局、桌面模式）");
        eq(items(MenuOrder.SCREEN_KEYBOARD, false, false, false), list(S, CLOSE, BOARD, PTR, LAYOUT, ADJ, R, X), "键盘页（豆花布局、蓝牙模式）");
        eq(items(MenuOrder.SCREEN_KEYBOARD, true, true, false), list(S, CLOSE, BOARD, PTR, ADJ, R, G, X), "键盘页（豆花布局小红点：没有布局）");
        eq(items(MenuOrder.SCREEN_KEYBOARD, true, false, true), list(S, CLOSE, BOARD, PTR, ADJ, R, G, X), "键盘页（实体键盘：没有布局）");
        eq(items(MenuOrder.SCREEN_KEYBOARD, false, true, true), list(S, CLOSE, BOARD, PTR, ADJ, R, X), "键盘页（实体键盘、蓝牙模式）");
        // 手柄页：设置 · 预设 · 旋转 · 返回触控板 · 退出（手柄页只在桌面模式进得去，蓝牙模式照样排得出）
        for (boolean desktop : new boolean[]{false, true}) for (boolean stick : new boolean[]{false, true}) for (boolean fixed : new boolean[]{false, true}) {
            eq(items(MenuOrder.SCREEN_GAMEPAD, desktop, stick, fixed), list(S, PRESET, R, BACK, X), "手柄页");
        }
    }

    /** 每一屏每种状态：设置第一、退出最后、手柄 / 返回触控板在的话倒数第二、旋转紧挨在它们前面；每项至少在一屏出现。 */
    static void invariants() {
        Set<Integer> shownSomewhere = new HashSet<>();
        int combos = 0;
        for (int screen = 0; screen < 3; screen++) for (int m = 0; m < 8; m++) {
            boolean desktop = (m & 1) != 0, stick = (m & 2) != 0, fixed = (m & 4) != 0;
            List<Integer> l = items(screen, desktop, stick, fixed);
            combos++;
            shownSomewhere.addAll(l);
            String what = "屏 " + screen + (desktop ? " 桌面" : " 蓝牙") + (stick ? " 小红点" : "") + (fixed ? " 实体" : "") + " " + l;
            check(!l.isEmpty() && l.get(0) == S, what + "：设置第一个");
            check(l.get(l.size() - 1) == X, what + "：退出最后一个");
            int g = l.indexOf(G) >= 0 ? l.indexOf(G) : l.indexOf(BACK);
            check(g < 0 || g == l.size() - 2, what + "：手柄（返回触控板）倒数第二");
            int r = l.indexOf(R);
            check(r == l.size() - (g < 0 ? 2 : 3), what + "：旋转紧挨着手柄 / 退出前面");
            check(new HashSet<>(l).size() == l.size(), what + "：不重复");
        }
        check(shownSomewhere.size() == MenuOrder.ORDER.length && combos == 24, "每一项至少在一屏出现");
    }

    static List<Integer> items(int screen, boolean desktop, boolean stick, boolean fixed) {
        return MenuOrder.items(screen, desktop, stick, fixed);
    }

    static List<Integer> list(int... ids) {
        List<Integer> out = new ArrayList<>();
        for (int id : ids) out.add(id);
        return out;
    }

    static void eq(Object actual, Object expected, String what) {
        check(expected.equals(actual), what + "（期望 " + expected + "，实际 " + actual + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
