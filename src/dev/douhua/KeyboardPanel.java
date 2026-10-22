package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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
 *   功能行   Esc Tab Shift Ctrl Alt Super Caps Fn Del
 *   数字行   1–0（锁定 Shift 时显示 !@#…）
 *   字母三行 q–p / a–l / ⇧ z–m ⌫
 *   底行     符 ，空格 。↵
 *   快捷行   默认 复制 粘贴 剪切 撤销 全选 ← ↑ ↓ →，9 个位置都能在设置里换（选项是共用的动作清单 Actions）
 * "符"层把数字行和字母三行换成符号（一按就出，不用配 Shift），"Fn"层换成 F1–F12 和 Home/End/翻页。
 * 修饰键点一下锁定（高亮），打完下一个键自动解开。
 *
 * 平时触摸由服务处理（按键的位置判断按到了哪个键）；服务没接管、也没走本地分流时，面板整块自己收触摸
 * （{@link #onTouchEvent}，按手指编号分给键）并转发给服务。
 * 键与键之间的缝、第二行两头的空位、外圈边距不是死区：分给最近的键（规则只有一份，TouchRouter.nearestKey；
 * 分流时由面板整块的范围 REGION_KEYBOARD 触发，面板自己收触摸时由 {@link KeyFingers#pick} 调它）。
 * 三层（字母 / 符号 / Fn）的中间四行各建一次缓存着，换层只切显示。
 */
final class KeyboardPanel extends LinearLayout {
    interface Listener {
        /** 服务没接管时，键被按下 / 松开。key 同 Proto.REGION_KEY 的 id。 */
        void onLocalKey(int key, boolean down);

        void onKeyHaptic(int id, boolean special);

        /** 服务没接管时，非 HID 的特殊键（符 / Fn / 快捷行里的非键盘动作）被点了。 */
        void onSpecialKey(int id);
    }

    /** 按下字符键时的放大提示，由外面的容器画在键盘上方（键盘自己画不出自己的边界）。 */
    interface PreviewHost {
        void showKeyPreview(View key, String label);

        /** 只在 key 正是当前放大的那个键时收起（同时按着几个键，显示最后按下的）。 */
        void hideKeyPreview(View key);
    }

    /** "符"和"Fn"切换键的按钮编号（不是 HID 键，由 App 自己处理）。 */
    static final int SYMBOLS_BUTTON_ID = 300;
    static final int FN_BUTTON_ID = 301;
    /** 🌐：切换输入法（动作 lang_switch），由 App 执行。 */
    static final int GLOBE_BUTTON_ID = 302;
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

    /**
     * 功能行。Shift 和字母区的 ⇧ 是同一个键（左 Shift，一起高亮）：符号层、Fn 层没有 ⇧，
     * 用户要在任何一层都能锁 Shift（Shift+方向键、Shift+F 键、Ctrl+Shift 组合）。
     */
    private static final Key[] TOP = {
            k(0x29, "Esc"), k(0x2B, "Tab"), k(0xE1, "Shift"), k(0xE0, "Ctrl"), k(0xE2, "Alt"), k(0xE3, "Super"),
            k(0x39, "Caps"), new Key(FN_BUTTON_ID, "Fn", null, 1, true), k(0x4C, "Del"),
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

    /** 把彩色 emoji 画成灰的：去掉饱和度，再压暗到和普通键名差不多。 */
    private static final ColorMatrixColorFilter GRAY_EMOJI;

    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.75f, 0.75f, 0.75f, 1);
        m.postConcat(dim);
        GRAY_EMOJI = new ColorMatrixColorFilter(m);
    }

    private static final Key[] BOTTOM = {
            new Key(SYMBOLS_BUTTON_ID, "?123", null, 1.5f, true), k(0x36, ","), wide(0x2C, "Space", 5),
            k(0x37, "."), wide(0x28, "↵", 1.5f),
    };

    /**
     * 连着苹果设备时多一个 🌐（切换输入法，iPad 上实测管用；Linux 等主机默认没绑这个键，不显示），空格让出一份。
     */
    private static final Key[] BOTTOM_APPLE = {
            new Key(SYMBOLS_BUTTON_ID, "?123", null, 1.5f, true), new Key(GLOBE_BUTTON_ID, "🌐", null, 1, true),
            k(0x36, ","), wide(0x2C, "Space", 4),
            k(0x37, "."), wide(0x28, "↵", 1.5f),
    };

    /** 键盘总行数（功能行 + 中间四行 + 底行 + 快捷行），用来算合适的高度。 */
    static final int ROWS = 7;

    /** 一个键和它的 view，外加上次画上去的样子（没变就不重画，见 {@link #look}）。 */
    private static final class Cap {
        final Key key;
        final TextView view;
        /** 上次 Style.look 画的 (高亮 << 1) | 按下；-1 = 还没画过。 */
        int look = -1;
        boolean pressed;

        Cap(Key key, TextView view) {
            this.key = key;
            this.view = view;
        }
    }

    private final Listener listener;
    private PreviewHost previewHost;
    private final int gap;
    /** 快捷行（设置里可改）。 */
    private Key[] shortcuts = new Key[0];
    private int layer = LAYER_LETTERS;
    private int modifiers;
    /** 功能行的键（各层共用）。 */
    private final List<Cap> top = new ArrayList<>();
    /**
     * 中间四行每层一份（下标 = LAYER_*）：建一次缓存着，换层只切显示，不拆掉重建所有键
     * （重建几十个自动缩字号的 TextView 会让主线程停一下，蓝牙模式下报文也从主线程发）。
     */
    private final LinearLayout[] middles = new LinearLayout[3];
    private final List<List<Cap>> middleCaps = new ArrayList<>();
    /** 底行 + 快捷行的键（各层共用；换快捷行、换主机时只重建这两行）。 */
    private final List<Cap> bottom = new ArrayList<>();
    private final List<View> bottomRows = new ArrayList<>();
    /** 当前显示的键（不含空位），顺序同报给服务的区域：功能行、中间四行、底行、快捷行。 */
    private final List<Cap> caps = new ArrayList<>();
    /** 键自己收触摸时每根手指按的键（caps 的下标）。 */
    private final KeyFingers fingers = new KeyFingers();

    KeyboardPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.gap = Style.dp(context, 2);
        setOrientation(VERTICAL);
        addRow(this, TOP, top);
        for (int l = 0; l < middles.length; l++) {
            Key[][] rows = l == LAYER_SYMBOLS ? SYMBOLS : l == LAYER_FN ? FN : LETTERS;
            LinearLayout block = new LinearLayout(context);
            block.setOrientation(VERTICAL);
            List<Cap> list = new ArrayList<>();
            for (Key[] row : rows) addRow(block, row, list);
            middles[l] = block;
            middleCaps.add(list);
            addView(block, new LayoutParams(LayoutParams.MATCH_PARENT, 0, rows.length));
        }
        rebuildBottom();
    }

    void setPreviewHost(PreviewHost host) {
        previewHost = host;
    }

    /**
     * 换快捷行（设置页改完回来时调用）。键盘组合键直接当键（按住会连发）；其它动作当特殊键，
     * 按钮编号 ROW_ACTION_BASE_ID + 位置，由宿主执行。
     */
    void setShortcuts(List<Actions.Action> list) {
        shortcutActions = new ArrayList<>(list);
        List<Key> row = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Actions.Action a = list.get(i);
            if (a.kind == Actions.Kind.NONE) continue;
            String label = a.shortLabel(getContext());
            if (a.kind == Actions.Kind.HID) row.add(new Key(a.hidCode(apple), label, null, 1, false));
            else row.add(new Key(ROW_ACTION_BASE_ID + i, label, null, 1, true));
        }
        shortcuts = row.toArray(new Key[0]);
        rebuildBottom();
    }

    /** 服务报来的锁定修饰键：高亮对应键，锁定 Shift 时显示大写和上档符号。 */
    void setModifiers(int mask) {
        modifiers = mask;
        refreshLabels();
    }

    /** "符" / "Fn" 被点了。 */
    void onLayerButton(int id) {
        int target = id == FN_BUTTON_ID ? LAYER_FN : LAYER_SYMBOLS;
        dropTouches();
        layer = layer == target ? LAYER_LETTERS : target;
        showLayer();
    }

    /**
     * 把每个键在屏幕上的位置报给服务，最后是面板整块的范围（REGION_KEYBOARD）：按在缝、空位、边距上时分给范围里最近的键。
     * 范围就是面板自己，键盘外面的空白、工具栏、小触控板都不在里面。只报当前这一层（藏起来的层不在 caps 里）。
     */
    void collectRegions(List<int[]> regions) {
        int[] loc = new int[2];
        for (Cap c : caps) {
            TextView v = c.view;
            v.getLocationOnScreen(loc);
            regions.add(new int[]{c.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, c.key.id,
                    loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()});
        }
        getLocationOnScreen(loc);
        regions.add(new int[]{Proto.REGION_KEYBOARD, 0, loc[0], loc[1], loc[0] + getWidth(), loc[1] + getHeight()});
    }

    /** 画按下的样子。id 同 Key.id。 */
    void showPressed(int id, boolean pressed) {
        for (Cap c : caps) {
            if (c.key.id == id) {
                look(c, pressed);
                preview(c, pressed);
            }
        }
    }

    /** 所有键松开的样子、收起放大提示（服务断了，它转来的按下等不到松开）。 */
    void clearPressed() {
        for (Cap c : caps) {
            look(c, false);
            preview(c, false);
        }
    }

    // ═══════════ 内部 ═══════════

    /**
     * 连着苹果设备：修饰键按苹果的写法标（Ctrl ⌃、Alt ⌥、GUI ⌘，GUI 键平时叫 Super），
     * 快捷行里的内置快捷键按苹果的习惯发（复制 = ⌘C……，见 Actions.Action.hidCode）。
     */
    private boolean apple;
    private List<Actions.Action> shortcutActions = new ArrayList<>();

    void setAppleHost(boolean apple) {
        if (apple == this.apple) return;
        this.apple = apple;
        setShortcuts(shortcutActions);   // 重建底行和快捷行（键的编号跟着变），区域会随重新布局报上去
    }

    /** 重建底行和快捷行（换主机、换快捷行）；功能行和中间三层不动。 */
    private void rebuildBottom() {
        dropTouches();
        for (View row : bottomRows) removeView(row);
        bottomRows.clear();
        bottom.clear();
        bottomRows.add(addRow(this, apple ? BOTTOM_APPLE : BOTTOM, bottom));
        if (shortcuts.length > 0) bottomRows.add(addRow(this, shortcuts, bottom));
        showLayer();
    }

    /** 显示当前层：别的层藏起来（GONE，不占地方、不量），当前显示的键换成这一层的，键帽和高亮跟上。 */
    private void showLayer() {
        for (int l = 0; l < middles.length; l++) middles[l].setVisibility(l == layer ? VISIBLE : GONE);
        caps.clear();
        caps.addAll(top);
        caps.addAll(middleCaps.get(layer));
        caps.addAll(bottom);
        refreshLabels();
    }

    /**
     * 键要换掉了（换层、重建底行）：面板自己收的手指按着的键先松开；所有键画回松开的样子、收起放大提示
     * （服务转来的按下等不到这些键的松开了，藏起来的层下次显示时也不该还按着）。
     */
    private void dropTouches() {
        releaseFingers();
        for (Cap c : caps) {
            look(c, false);
            preview(c, false);
        }
    }

    /** 加一行键到 parent，键记进 list（空位不记），返回这一行。 */
    private LinearLayout addRow(LinearLayout parent, Key[] row, List<Cap> list) {
        LinearLayout line = new LinearLayout(getContext());
        for (Key key : row) {
            // 一个字（含 🌐 这种占两个 char 的）用大字，多个字的键名用小字
            boolean oneChar = key.label.codePointCount(0, key.label.length()) <= 1;
            TextView v = Style.button(getContext(), oneChar ? 18 : 13);
            if (key.id == GLOBE_BUTTON_ID) v.getPaint().setColorFilter(GRAY_EMOJI);   // 🌐 是彩色 emoji，去色调暗，和别的键一个色调
            LayoutParams p = new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight);
            p.setMargins(gap, gap, gap, gap);
            if (key.id == Key.SPACER) v.setVisibility(View.INVISIBLE);
            else list.add(new Cap(key, v));
            line.addView(v, p);
        }
        parent.addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        return line;
    }

    /** 键帽文字和高亮：只改文字真变了的键（开着自动缩字号，setText 要重新排版、试字号），样子没变的键不重画。 */
    private void refreshLabels() {
        for (Cap c : caps) {
            String label = label(c.key);
            if (!label.contentEquals(c.view.getText())) c.view.setText(label);
            look(c, c.pressed);
        }
    }

    private String label(Key key) {
        String label = key.label;
        if ((modifiers & SHIFT) != 0 && key.shifted != null) label = key.shifted;
        // 跟着系统语言的键名
        if (key.id == 0x2C) label = getContext().getString(R.string.key_space);
        if (key.id == 0xE0) label = apple ? "⌃" : "Ctrl";
        if (key.id == 0xE2) label = apple ? "⌥" : "Alt";
        if (key.id == 0xE3) label = apple ? "⌘" : "Super";
        if (key.id == SYMBOLS_BUTTON_ID) {
            label = layer == LAYER_SYMBOLS ? "ABC" : getContext().getString(R.string.key_symbols);
        }
        return label;
    }

    /** 字符键按下时放大显示（看清按到了哪个），松开收起。 */
    private void preview(Cap c, boolean pressed) {
        if (previewHost == null) return;
        if (!pressed) previewHost.hideKeyPreview(c.view);
        else if (isCharKey(c)) previewHost.showKeyPreview(c.view, c.view.getText().toString());
    }

    /** 字符键：键帽只有一个字的字母、数字、符号。修饰键、⇧ ⌫ ↵、空格、F 键、快捷行不放大。 */
    private boolean isCharKey(Cap c) {
        Key key = c.key;
        if (key.special) return false;
        for (Key s : shortcuts) if (s == key) return false;
        String t = c.view.getText().toString();
        return t.length() == 1 && "⇧⌫↵".indexOf(t) < 0;
    }

    /** 高亮（换层键、锁定的修饰键）和按下的样子；和上次画的一样就不动（Style.look 每次都新建背景）。 */
    private void look(Cap c, boolean pressed) {
        int id = c.key.id;
        boolean active = (id == SYMBOLS_BUTTON_ID && layer == LAYER_SYMBOLS)
                || (id == FN_BUTTON_ID && layer == LAYER_FN)
                || (id >= 0xE0 && id <= 0xE3 && (modifiers & (1 << (id - 0xE0))) != 0);
        c.pressed = pressed;
        int state = (active ? 2 : 0) | (pressed ? 1 : 0);
        if (state == c.look) return;
        c.look = state;
        Style.look(c.view, active, pressed);
    }

    // ═══════════ 键自己收触摸（服务没接管、没走本地分流时） ═══════════

    /** 面板整块收触摸，不让各键自己收：按手指编号分给键（{@link KeyFingers}），多指打字、缝里的手指都按同一条规则。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return true;
    }

    /**
     * 每根手指按下时定键（包含的键优先，缝、空位、边距分给最近的键，规则同分流 TouchRouter.nearestKey），
     * 抬起时松开它自己那个键；ACTION_CANCEL 全部松开。以前各键自己收触摸：第一根手指按在缝里时，同一次手势里
     * 后来的手指全被吞掉、缝里那个键要等所有手指抬起才松开；第一根按在键 A、第二根按在缝里时，A 收到的是
     * "多指中的一根抬起"，被忽略，A 一直按着。
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int index = keyAt(e.getX(i), e.getY(i));
                if (index < 0) break;   // 在键盘范围外（多指时别处的手指也可能派到这里）
                int old = fingers.down(e.getPointerId(i), index);
                if (old != KeyFingers.NONE) release(old, false);
                press(index);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = fingers.up(e.getPointerId(e.getActionIndex()));
                if (index != KeyFingers.NONE) release(index, true);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseFingers();
                break;
            default:
                break;
        }
        return true;
    }

    /** 面板自己收的手指按着的键全部松开（不算点击）。 */
    private void releaseFingers() {
        for (int index : fingers.cancel()) release(index, false);
    }

    /** 面板坐标 (x, y) 该算哪个键（caps 的下标），范围外 / 没有键返回 -1。 */
    private int keyAt(float x, float y) {
        TouchRouter.Region[] rs = new TouchRouter.Region[caps.size()];
        int[] at = new int[2];
        for (int i = 0; i < rs.length; i++) {
            Cap c = caps.get(i);
            offsetInPanel(c.view, at);
            // 编号直接用下标（只在这里用，不是 Key.id）
            rs[i] = new TouchRouter.Region(c.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                    at[0], at[1], at[0] + c.view.getWidth(), at[1] + c.view.getHeight());
        }
        TouchRouter.Region area = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, getWidth(), getHeight());
        TouchRouter.Region r = KeyFingers.pick(rs, area, x, y);
        return r == null ? -1 : r.id;
    }

    /** v 的左上角在面板里的位置：一层层加上父视图的位置（面板里没有滚动、没有平移）。 */
    private void offsetInPanel(View v, int[] out) {
        int x = 0, y = 0;
        for (View p = v; p != this; p = (View) p.getParent()) {
            x += p.getLeft();
            y += p.getTop();
        }
        out[0] = x;
        out[1] = y;
    }

    /** 先发键，再震动、画按下的样子（震动是一次 binder 调用，画背景也要时间，别让报文等它们）。 */
    private void press(int index) {
        Cap c = caps.get(index);
        if (!c.key.special) listener.onLocalKey(c.key.id, true);
        listener.onKeyHaptic(c.key.id, c.key.special);
        look(c, true);
        preview(c, true);
    }

    /** tap = 手指正常抬起（特殊键这时才算点了）；被取消、被顶掉的只松开。 */
    private void release(int index, boolean tap) {
        Cap c = caps.get(index);
        if (!c.key.special) {
            listener.onLocalKey(c.key.id, false);
        } else if (tap) {
            post(() -> listener.onSpecialKey(c.key.id));
        }
        look(c, false);
        preview(c, false);
    }
}
