package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * ⋯ 菜单里有哪几项、按什么顺序排、哪一屏显示哪几项（三屏共用一份菜单，MainActivity 照这里建项、开菜单时只显示这一屏的）。纯 Java（离线测，MenuOrderTest）。
 *
 * **按使用频率排**（2026-11-01 晚，用户："你设置肯定放第一个呀，退出放最后一个，倒数第二个放那个手柄，你肯定按使用频率来按顺序排"；协调者定的顺序）：
 * - 主界面：设置 · 键盘 · 旋转 · 手柄 · 退出
 * - 键盘页：设置 · 收起键盘 · 键盘（豆花 / 实体）· 指点 · 布局 · 调整大小 · 旋转 · 手柄 · 退出
 * - 手柄页：设置 · 预设 · 旋转 · 返回触控板 · 退出（"返回触控板"在手柄页相当于"手柄"那一格）
 * 三屏的顺序合得成一条（{@link #ORDER}），每屏只是挑出自己的那几项（{@link #onScreen}）。以后加项照这条规矩插：设置永远第一个、退出永远最后、
 * 手柄（手柄页上是返回触控板）永远倒数第二，别的按用得多少排在中间。菜单的排法（KeyboardPlan.menuGrid：先排满第一列）照这个顺序走。
 * 编号的值没变（区域上报、按钮事件按编号认，和顺序无关）。
 */
final class MenuOrder {
    private MenuOrder() {}

    /** 菜单在哪一屏。 */
    static final int SCREEN_MAIN = 0, SCREEN_KEYBOARD = 1, SCREEN_GAMEPAD = 2;

    /** 键盘页才有的：收起键盘、调整大小、布局（自动 / 分裂 / 合并轮换）、指点（触控板 / 小红点 / 关轮换）。 */
    static final int KB_CLOSE = 401, KB_ADJUST = 402, KB_LAYOUT = 403, KB_POINTER = 404;
    /** 主界面才有的"键盘"（打开键盘页）；三屏都有的设置、旋转、退出（同 Actions 的 settings / rotate / exit）。 */
    static final int KEYBOARD = 410, SETTINGS = 411, ROTATE = 412, EXIT = 413;
    /** 主界面、键盘页的"手柄"（打开手柄页，只在桌面模式有）。 */
    static final int GAMEPAD = 414;
    /** 手柄页才有的：预设（轮换）、返回触控板。 */
    static final int GP_PRESET = 415, GP_BACK = 416;
    /** 键盘页的"键盘：豆花 / 实体（…）"（第 1B 批）。 */
    static final int BOARD = 418;

    /** 全部项，按显示的顺序（三屏的顺序合成的一条）。 */
    static final int[] ORDER = {
            SETTINGS, KEYBOARD, KB_CLOSE, BOARD, KB_POINTER, KB_LAYOUT, KB_ADJUST, GP_PRESET, ROTATE, GAMEPAD, GP_BACK, EXIT,
    };

    /**
     * 这一项在这一屏（SCREEN_*）的菜单里有没有。"手柄"只在桌面模式（desktop）有；"布局"在小红点状态（stick，豆花布局的指点）下没有
     * （小红点固定合并；选过的布局留着，换回触控板 / 关时照它），显示实体键盘（fixed）时也没有（实体键盘不分裂，"布局"是豆花布局的）。
     */
    static boolean onScreen(int id, int screen, boolean desktop, boolean stick, boolean fixed) {
        switch (id) {
            case KEYBOARD: return screen == SCREEN_MAIN;
            case BOARD: return screen == SCREEN_KEYBOARD;
            case KB_LAYOUT: return screen == SCREEN_KEYBOARD && !stick && !fixed;
            case KB_CLOSE:
            case KB_ADJUST:
            case KB_POINTER: return screen == SCREEN_KEYBOARD;
            case GAMEPAD: return screen != SCREEN_GAMEPAD && desktop;
            case GP_PRESET:
            case GP_BACK: return screen == SCREEN_GAMEPAD;
            default: return true;   // 设置、旋转、退出
        }
    }

    /** 这一屏菜单里显示的项，按顺序。 */
    static List<Integer> items(int screen, boolean desktop, boolean stick, boolean fixed) {
        List<Integer> out = new ArrayList<>();
        for (int id : ORDER) if (onScreen(id, screen, desktop, stick, fixed)) out.add(id);
        return out;
    }

    /** 项的编号 → 在 ORDER 里的下标（MainActivity 的菜单项按这个下标建），不是菜单项返回 -1。 */
    static int index(int id) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == id) return i;
        return -1;
    }
}
