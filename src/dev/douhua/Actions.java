package dev.douhua;

import android.content.Context;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有能自定义的位置共用的动作清单：左侧按钮条、触控板四角的小按钮、三指 / 四指手势、键盘最下面一排。
 * 加新动作只在这里加一行；id 存进设置，改名不能改 id。
 *
 * 另有"自定义组合键"：id 形如 "combo:263:Ctrl+C"（见 {@link #combo}），任何位置都能放。
 */
final class Actions {
    enum Kind {
        /** 什么都不做。 */
        NONE,
        /**
         * 虚拟键盘按一下组合键（code = HID 用法码 | 修饰键 << 8，同 Proto.REGION_KEY 的 id）。
         * 和真键盘按下一样，发给当前有焦点的窗口。放在键盘那排时按住不放会连发。
         */
        HID,
        /** 往 DeX 屏幕注入一个系统按键（返回、主屏这类键盘上没有的键）。code = KeyEvent 键码，meta = 修饰键。 */
        KEY,
        /** 点一下鼠标键（code = Proto.BUTTON_*）。 */
        CLICK,
        /** 按住期间鼠标左键一直按着（只在左侧按钮上有意义；放在别处等同单击）。 */
        HOLD_LEFT,
        /** 打开 / 关闭键盘界面（虚拟 USB 键盘）。 */
        KEYBOARD,
        /** 打开 / 关闭"贴字"面板：用手机自己的输入法（语音、中文）打字，经剪贴板贴到 DeX。 */
        PHONE_TEXT,
        /** 打开 / 关闭数字键盘。 */
        NUMPAD,
        /** 打开设置。 */
        SETTINGS,
        /** 退出豆花（触摸屏立刻还给系统）。 */
        EXIT,
        /** 横竖屏切换。 */
        ROTATE,
        /** 收起 / 显示左侧按钮条（收起后触控板四角放小按钮）。 */
        TOGGLE_STRIP,
    }

    static final class Action {
        final String id;
        final Kind kind;
        final int code;
        final int meta;
        /** 名字：资源编号（随系统语言），为 0 时用原文。 */
        private final int labelRes;
        private final int shortRes;
        private final String labelText;
        private final String shortText;

        Action(String id, int labelRes, String labelText, int shortRes, String shortText, Kind kind, int code, int meta) {
            this.id = id;
            this.labelRes = labelRes;
            this.labelText = labelText;
            this.shortRes = shortRes;
            this.shortText = shortText;
            this.kind = kind;
            this.code = code;
            this.meta = meta;
        }

        /** 选择列表里显示的全名。 */
        String label(Context c) {
            return labelRes != 0 ? c.getString(labelRes) : labelText;
        }

        /** 按钮 / 键上显示的短名。 */
        String shortLabel(Context c) {
            return shortRes != 0 ? c.getString(shortRes) : shortText;
        }
    }

    // HID 修饰键位（Proto.REGION_KEY）
    private static final int CTRL = Combos.CTRL;
    private static final int SHIFT = Combos.SHIFT;
    private static final int ALT = Combos.ALT;
    private static final int WIN = Combos.WIN;

    private static final String COMBO_PREFIX = "combo:";
    private static final Map<String, Action> ALL = new LinkedHashMap<>();

    static {
        add("none", R.string.act_none, null, 0, "", Kind.NONE, 0, 0);

        // ── 豆花自己 ──
        add("keyboard", R.string.act_keyboard, null, R.string.act_keyboard_s, null, Kind.KEYBOARD, 0, 0);
        add("phone_text", R.string.act_phone_text, null, R.string.act_phone_text_s, null, Kind.PHONE_TEXT, 0, 0);
        add("numpad", R.string.act_numpad, null, R.string.act_numpad_s, null, Kind.NUMPAD, 0, 0);
        add("settings", R.string.act_settings, null, R.string.act_settings_s, null, Kind.SETTINGS, 0, 0);
        add("exit", R.string.act_exit, null, R.string.act_exit_s, null, Kind.EXIT, 0, 0);
        add("rotate", R.string.act_rotate, null, R.string.act_rotate_s, null, Kind.ROTATE, 0, 0);
        add("toggle_strip", R.string.act_toggle_strip, null, R.string.act_toggle_strip_s, null, Kind.TOGGLE_STRIP, 0, 0);

        // ── 鼠标 ──
        add("hold_left", R.string.act_hold_left, null, R.string.act_hold_left_s, null, Kind.HOLD_LEFT, Proto.BUTTON_LEFT, 0);
        add("right_click", R.string.act_right_click, null, R.string.act_right_click_s, null, Kind.CLICK, Proto.BUTTON_RIGHT, 0);
        add("middle_click", R.string.act_middle_click, null, R.string.act_middle_click_s, null, Kind.CLICK, Proto.BUTTON_MIDDLE, 0);

        // ── 系统 / DeX（键盘上没有的键，注入） ──
        key("back", R.string.act_back, null, R.string.act_back_s, null, KeyEvent.KEYCODE_BACK);
        key("home", R.string.act_home, null, R.string.act_home_s, null, KeyEvent.KEYCODE_HOME);
        key("recents", R.string.act_recents, null, R.string.act_recents_s, null, KeyEvent.KEYCODE_APP_SWITCH);
        key("all_apps", R.string.act_all_apps, null, R.string.act_all_apps_s, null, KeyEvent.KEYCODE_ALL_APPS);
        key("notifications", R.string.act_notifications, null, R.string.act_notifications_s, null, KeyEvent.KEYCODE_NOTIFICATION);
        hid("switch_next", R.string.act_switch_next, null, R.string.act_switch_next_s, null, 0x2B, ALT);
        hid("switch_prev", R.string.act_switch_prev, null, R.string.act_switch_prev_s, null, 0x2B, ALT | SHIFT);
        hid("desktop", R.string.act_desktop, null, R.string.act_desktop_s, null, letter('d'), WIN);
        hid("close_window", R.string.act_close_window, null, R.string.act_close_window_s, null, 0x3D, ALT);
        hid("screenshot", R.string.act_screenshot, null, R.string.act_screenshot_s, null, 0x46, 0);

        // ── 编辑 ──
        hid("copy", R.string.act_copy, null, R.string.act_copy_s, null, letter('c'), CTRL);
        hid("paste", R.string.act_paste, null, R.string.act_paste_s, null, letter('v'), CTRL);
        hid("cut", R.string.act_cut, null, R.string.act_cut_s, null, letter('x'), CTRL);
        hid("undo", R.string.act_undo, null, R.string.act_undo_s, null, letter('z'), CTRL);
        hid("redo", R.string.act_redo, null, R.string.act_redo_s, null, letter('z'), CTRL | SHIFT);
        hid("select_all", R.string.act_select_all, null, R.string.act_select_all_s, null, letter('a'), CTRL);
        hid("find", R.string.act_find, null, R.string.act_find_s, null, letter('f'), CTRL);
        hid("save", R.string.act_save, null, R.string.act_save_s, null, letter('s'), CTRL);

        // ── 浏览器 ──
        hid("browser_back", R.string.act_browser_back, null, R.string.act_browser_back_s, null, 0x50, ALT);
        hid("browser_forward", R.string.act_browser_forward, null, R.string.act_browser_forward_s, null, 0x4F, ALT);
        hid("refresh", R.string.act_refresh, null, R.string.act_refresh_s, null, 0x3E, 0);
        hid("new_tab", R.string.act_new_tab, null, R.string.act_new_tab_s, null, letter('t'), CTRL);
        hid("close_tab", R.string.act_close_tab, null, R.string.act_close_tab_s, null, letter('w'), CTRL);
        hid("reopen_tab", R.string.act_reopen_tab, null, R.string.act_reopen_tab_s, null, letter('t'), CTRL | SHIFT);
        hid("zoom_in", R.string.act_zoom_in, null, R.string.act_zoom_in_s, null, 0x2E, CTRL);
        hid("zoom_out", R.string.act_zoom_out, null, R.string.act_zoom_out_s, null, 0x2D, CTRL);

        // ── 单个键 ──
        hid("left", 0, "←", 0, "←", 0x50, 0);
        hid("up", 0, "↑", 0, "↑", 0x52, 0);
        hid("down", 0, "↓", 0, "↓", 0x51, 0);
        hid("right", 0, "→", 0, "→", 0x4F, 0);
        hid("home_key", R.string.act_home_key, null, 0, "Home", 0x4A, 0);
        hid("end_key", R.string.act_end_key, null, 0, "End", 0x4D, 0);
        hid("page_up", R.string.act_page_up, null, 0, "PgUp", 0x4B, 0);
        hid("page_down", R.string.act_page_down, null, 0, "PgDn", 0x4E, 0);
        hid("escape", 0, "Esc", 0, "Esc", 0x29, 0);
        hid("tab", 0, "Tab", 0, "Tab", 0x2B, 0);
        hid("enter", R.string.act_enter, null, 0, "↵", 0x28, 0);
        hid("backspace", R.string.act_backspace, null, 0, "⌫", 0x2A, 0);
        hid("delete", R.string.act_delete, null, 0, "Del", 0x4C, 0);

        // ── 媒体（键盘上没有，注入） ──
        key("play_pause", R.string.act_play_pause, null, R.string.act_play_pause_s, null, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        key("volume_up", R.string.act_volume_up, null, R.string.act_volume_up_s, null, KeyEvent.KEYCODE_VOLUME_UP);
        key("volume_down", R.string.act_volume_down, null, R.string.act_volume_down_s, null, KeyEvent.KEYCODE_VOLUME_DOWN);
        key("mute", R.string.act_mute, null, R.string.act_mute_s, null, KeyEvent.KEYCODE_VOLUME_MUTE);
        // 苹果键盘上 🌐 那个键：蓝牙发标准的"下一个键盘布局"媒体键，DeX 注入安卓的切换输入法键
        key("lang_switch", R.string.act_lang_switch, null, 0, "🌐", KeyEvent.KEYCODE_LANGUAGE_SWITCH);
    }

    private Actions() {}

    private static int letter(char c) {
        return 0x04 + (c - 'a');
    }

    private static void add(String id, int labelRes, String labelText, int shortRes, String shortText,
            Kind kind, int code, int meta) {
        ALL.put(id, new Action(id, labelRes, labelText, shortRes, shortText, kind, code, meta));
    }

    private static void key(String id, int labelRes, String labelText, int shortRes, String shortText, int keyCode) {
        add(id, labelRes, labelText, shortRes, shortText, Kind.KEY, keyCode, 0);
    }

    private static void hid(String id, int labelRes, String labelText, int shortRes, String shortText, int usage, int mods) {
        add(id, labelRes, labelText, shortRes, shortText, Kind.HID, usage | (mods << 8), 0);
    }

    /** 自定义组合键的 id：key = HID 用法码 | 修饰键 << 8，label = 按钮上显示的字。 */
    static String combo(int key, String label) {
        return COMBO_PREFIX + key + ":" + label;
    }

    /** 未知 id（比如旧版本存的）按"无"处理。 */
    static Action get(String id) {
        if (id != null && id.startsWith(COMBO_PREFIX)) {
            String rest = id.substring(COMBO_PREFIX.length());
            int i = rest.indexOf(':');
            if (i > 0) {
                try {
                    int key = Integer.parseInt(rest.substring(0, i));
                    String label = rest.substring(i + 1);
                    return new Action(id, 0, label + " (" + Combos.describe(key) + ")", 0, label, Kind.HID, key, 0);
                } catch (NumberFormatException ignored) {
                    // 坏数据，按"无"
                }
            }
        }
        Action a = ALL.get(id);
        return a != null ? a : ALL.get("none");
    }

    static List<Action> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
    }
}
