package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 手机上的外接键盘。每个键对应一个 HID 用法码（可以自带修饰键，比如"@" = Shift+2、"复制" = Ctrl+C），
 * 按下后由豆花服务通过虚拟 USB 键盘发给 DeX——DeX 看到的就是一块真键盘。
 *
 * 字母区照 Gboard 排（手机打字的习惯最重要）；修饰键、数字、快捷键各占一排，不往字母区里硬塞：
 *   功能行   Esc Tab Ctrl Alt Win Caps Fn Del
 *   数字行   1–0（锁定 Shift 时显示 !@#…）
 *   字母三行 q–p / a–l / ⇧ z–m ⌫
 *   底行     符 ，空格 。↵
 *   快捷行   默认 复制 粘贴 剪切 撤销 全选 ← ↑ ↓ →，9 个位置都能在设置里换（选项是共用的动作清单 Actions）
 * "符"层把数字行和字母三行换成符号（一按就出，不用配 Shift），"Fn"层换成 F1–F12 和 Home/End/翻页。
 * 修饰键点一下锁定（高亮），打完下一个键自动解开。
 *
 * 平时触摸由服务处理（按键的位置判断按到了哪个键）；服务没接管时，键自己响应触摸并转发给服务。
 */
final class KeyboardPanel extends LinearLayout {
    interface Listener {
        /** 服务没接管时，键被按下 / 松开。key 同 Proto.REGION_KEY 的 id。 */
        void onLocalKey(int key, boolean down);

        void onKeyHaptic();

        /** 服务没接管时，非 HID 的特殊键（符 / Fn / 快捷行里的非键盘动作）被点了。 */
        void onSpecialKey(int id);
    }

    /** "符"和"Fn"切换键的按钮编号（不是 HID 键，由 App 自己处理）。 */
    static final int SYMBOLS_BUTTON_ID = 300;
    static final int FN_BUTTON_ID = 301;
    /** 快捷行里不是键盘组合键的动作（返回、键盘界面……）：按钮编号 = 这个 + 位置。 */
    static final int ROW_ACTION_BASE_ID = 600;

    private static final int SHIFT = 2;
    private static final int LAYER_LETTERS = 0;
    private static final int LAYER_SYMBOLS = 1;
    private static final int LAYER_FN = 2;

    /** 一个键。id = HID 用法码 | (自带修饰键 << 8)；SPACER = 空位；切换键用 *_BUTTON_ID。 */
    static final class Key {
        static final int SPACER = -1;

        final int id;
        final String label;
        final String shifted;
        final float weight;
        final boolean special;

        Key(int id, String label, String shifted, float weight, boolean special) {
            this.id = id;
            this.label = label;
            this.shifted = shifted;
            this.weight = weight;
            this.special = special;
        }
    }

    private static Key k(int usage, String label) {
        return new Key(usage, label, null, 1, false);
    }

    /** 锁定 Shift 时键帽换成 shifted（数字行）。 */
    private static Key k(int usage, String label, String shifted) {
        return new Key(usage, label, shifted, 1, false);
    }

    private static Key wide(int usage, String label, float weight) {
        return new Key(usage, label, null, weight, false);
    }

    /** 自带 Shift 的符号键。 */
    private static Key shift(int usage, String label) {
        return new Key(usage | (SHIFT << 8), label, null, 1, false);
    }

    private static Key spacer(float weight) {
        return new Key(Key.SPACER, "", null, weight, false);
    }

    private static Key letter(char c) {
        return new Key(0x04 + (c - 'a'), String.valueOf(c), String.valueOf(Character.toUpperCase(c)), 1, false);
    }

    private static Key[] letters(String s) {
        Key[] row = new Key[s.length()];
        for (int i = 0; i < s.length(); i++) row[i] = letter(s.charAt(i));
        return row;
    }

    private static Key[] concat(Key[]... parts) {
        List<Key> all = new ArrayList<>();
        for (Key[] p : parts) for (Key key : p) all.add(key);
        return all.toArray(new Key[0]);
    }

    private static final Key[] TOP = {
            k(0x29, "Esc"), k(0x2B, "Tab"), k(0xE0, "Ctrl"), k(0xE2, "Alt"), k(0xE3, "Win"), k(0x39, "Caps"),
            new Key(FN_BUTTON_ID, "Fn", null, 1, true), k(0x4C, "Del"),
    };

    private static final Key[][] LETTERS = {
            {k(0x1E, "1", "!"), k(0x1F, "2", "@"), k(0x20, "3", "#"), k(0x21, "4", "$"), k(0x22, "5", "%"),
                    k(0x23, "6", "^"), k(0x24, "7", "&"), k(0x25, "8", "*"), k(0x26, "9", "("), k(0x27, "0", ")")},
            letters("qwertyuiop"),
            concat(new Key[]{spacer(0.5f)}, letters("asdfghjkl"), new Key[]{spacer(0.5f)}),
            concat(new Key[]{wide(0xE1, "⇧", 1.5f)}, letters("zxcvbnm"), new Key[]{wide(0x2A, "⌫", 1.5f)}),
    };

    private static final Key[][] SYMBOLS = {
            {k(0x1E, "1"), k(0x1F, "2"), k(0x20, "3"), k(0x21, "4"), k(0x22, "5"),
                    k(0x23, "6"), k(0x24, "7"), k(0x25, "8"), k(0x26, "9"), k(0x27, "0")},
            {shift(0x1E, "!"), shift(0x1F, "@"), shift(0x20, "#"), shift(0x21, "$"), shift(0x22, "%"),
                    shift(0x23, "^"), shift(0x24, "&"), shift(0x25, "*"), shift(0x26, "("), shift(0x27, ")")},
            {k(0x2D, "-"), shift(0x2D, "_"), k(0x2E, "="), shift(0x2E, "+"), k(0x2F, "["), k(0x30, "]"),
                    shift(0x2F, "{"), shift(0x30, "}"), k(0x31, "\\"), shift(0x31, "|")},
            {k(0x33, ";"), shift(0x33, ":"), k(0x34, "'"), shift(0x34, "\""), k(0x35, "`"), shift(0x35, "~"),
                    shift(0x36, "<"), shift(0x37, ">"), k(0x38, "/"), shift(0x38, "?"), wide(0x2A, "⌫", 1.5f)},
    };

    private static final Key[][] FN = {
            {k(0x3A, "F1"), k(0x3B, "F2"), k(0x3C, "F3"), k(0x3D, "F4"), k(0x3E, "F5"), k(0x3F, "F6")},
            {k(0x40, "F7"), k(0x41, "F8"), k(0x42, "F9"), k(0x43, "F10"), k(0x44, "F11"), k(0x45, "F12")},
            {k(0x4A, "Home"), k(0x4D, "End"), k(0x4B, "PgUp"), k(0x4E, "PgDn")},
            {k(0x49, "Ins"), k(0x4C, "Del"), k(0x46, "PrtSc"), wide(0x2A, "⌫", 1.5f)},
    };

    private static final Key[] BOTTOM = {
            new Key(SYMBOLS_BUTTON_ID, "?123", null, 1.5f, true), k(0x36, ","), wide(0x2C, "Space", 5),
            k(0x37, "."), wide(0x28, "↵", 1.5f),
    };

    /** 键盘总行数（功能行 + 中间四行 + 底行 + 快捷行），用来算合适的高度。 */
    static final int ROWS = 7;

    private final Listener listener;
    private final int gap;
    /** 快捷行（设置里可改）。 */
    private Key[] shortcuts = new Key[0];
    private int layer = LAYER_LETTERS;
    private int modifiers;
    /** 当前显示的键（下标对应 views）。 */
    private final List<Key> keys = new ArrayList<>();
    private final List<TextView> views = new ArrayList<>();

    KeyboardPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.gap = Style.dp(context, 2);
        setOrientation(VERTICAL);
        rebuild();
    }

    /**
     * 换快捷行（设置页改完回来时调用）。键盘组合键直接当键（按住会连发）；其它动作当特殊键，
     * 按钮编号 ROW_ACTION_BASE_ID + 位置，由宿主执行。
     */
    void setShortcuts(List<Actions.Action> list) {
        List<Key> row = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Actions.Action a = list.get(i);
            if (a.kind == Actions.Kind.NONE) continue;
            String label = a.shortLabel(getContext());
            if (a.kind == Actions.Kind.HID) row.add(new Key(a.code, label, null, 1, false));
            else row.add(new Key(ROW_ACTION_BASE_ID + i, label, null, 1, true));
        }
        shortcuts = row.toArray(new Key[0]);
        rebuild();
    }

    /** 服务报来的锁定修饰键：高亮对应键，锁定 Shift 时显示大写和上档符号。 */
    void setModifiers(int mask) {
        modifiers = mask;
        refreshLabels();
    }

    /** "符" / "Fn" 被点了。 */
    void onLayerButton(int id) {
        int target = id == FN_BUTTON_ID ? LAYER_FN : LAYER_SYMBOLS;
        layer = layer == target ? LAYER_LETTERS : target;
        rebuild();
    }

    /** 把每个键在屏幕上的位置报给服务。 */
    void collectRegions(List<int[]> regions) {
        int[] loc = new int[2];
        for (int i = 0; i < views.size(); i++) {
            Key key = keys.get(i);
            if (key.id == Key.SPACER) continue;
            TextView v = views.get(i);
            v.getLocationOnScreen(loc);
            regions.add(new int[]{key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, key.id,
                    loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()});
        }
    }

    /** 画按下的样子。id 同 Key.id。 */
    void showPressed(int id, boolean pressed) {
        for (int i = 0; i < views.size(); i++) {
            if (keys.get(i).id == id) look(i, pressed);
        }
    }

    // ═══════════ 内部 ═══════════

    private void rebuild() {
        removeAllViews();
        keys.clear();
        views.clear();
        addRow(TOP);
        Key[][] middle = layer == LAYER_SYMBOLS ? SYMBOLS : layer == LAYER_FN ? FN : LETTERS;
        for (Key[] row : middle) addRow(row);
        addRow(BOTTOM);
        if (shortcuts.length > 0) addRow(shortcuts);
        refreshLabels();
    }

    private void addRow(Key[] row) {
        LinearLayout line = new LinearLayout(getContext());
        for (Key key : row) {
            TextView v = Style.button(getContext(), key.label.length() > 1 && !key.label.equals("⌫") ? 13 : 18);
            int index = views.size();
            keys.add(key);
            views.add(v);
            LayoutParams p = new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight);
            p.setMargins(gap, gap, gap, gap);
            if (key.id == Key.SPACER) {
                v.setVisibility(View.INVISIBLE);
            } else {
                v.setOnTouchListener((view, e) -> localTouch(index, e));
            }
            line.addView(v, p);
        }
        addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void refreshLabels() {
        boolean shift = (modifiers & SHIFT) != 0;
        for (int i = 0; i < views.size(); i++) {
            Key key = keys.get(i);
            String label = key.label;
            if (shift && key.shifted != null) label = key.shifted;
            // 跟着系统语言的键名
            if (key.id == 0x2C) label = getContext().getString(R.string.key_space);
            if (key.id == SYMBOLS_BUTTON_ID) {
                label = layer == LAYER_SYMBOLS ? "ABC" : getContext().getString(R.string.key_symbols);
            }
            views.get(i).setText(label);
            look(i, false);
        }
    }

    private void look(int i, boolean pressed) {
        Key key = keys.get(i);
        boolean active = (key.id == SYMBOLS_BUTTON_ID && layer == LAYER_SYMBOLS)
                || (key.id == FN_BUTTON_ID && layer == LAYER_FN)
                || (key.id >= 0xE0 && key.id <= 0xE3 && (modifiers & (1 << (key.id - 0xE0))) != 0);
        Style.look(views.get(i), active, pressed);
    }

    /** 服务没接管时，键自己响应触摸。 */
    @SuppressLint("ClickableViewAccessibility")
    private boolean localTouch(int index, MotionEvent e) {
        Key key = keys.get(index);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                look(index, true);
                listener.onKeyHaptic();
                if (!key.special) listener.onLocalKey(key.id, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                look(index, false);
                if (!key.special) {
                    listener.onLocalKey(key.id, false);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    post(() -> listener.onSpecialKey(key.id));
                }
                return true;
            default:
                return true;
        }
    }
}
