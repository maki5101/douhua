package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * 外接键盘的键位和排布，纯 Java（离线测）：有哪些键、每行怎么排、分裂时哪个键归左半 / 右半、每个键摆在哪。
 * 键帽 view、触摸、高亮在 KeyboardPanel。
 *
 * 两种布局：
 * - 整块：每行铺满键盘宽度（键按 weight 分）。
 * - 分裂（键盘区宽 ≥ 2 × 高，比如大屏分屏的下半边、手机横屏；也可以在 ⋯ 菜单的"布局"里强制，见 KeyboardPlan）：
 *   照 Gboard 的分裂键盘，左右两半各占 {@link #SIDE} 宽，中间留给触控板；触控板藏起来 / 放在别处时两半往中间伸，
 *   只隔一条缝（{@link #leftEnd(int, int)}）。字母层按实体键盘从中间切开（左 q–t / a–g / ⇧ z–v，右 y–p / h–l / b n m ⌫），
 *   两半里每行的总份数一样（{@link #HALF_UNITS}），同一层的键一样宽、错开的位置和整块时一样；
 *   切口那边空出来的半格、一格是空位（按在空位上照样分给这一半里最近的键）。
 *   功能行、符号层、Fn 层、快捷行按个数对半分（奇数时多的放左边）；底行 ?123 , 空格 | 空格 . ↵，两边各一个空格。
 * 同一个 Key 对象 = 同一个键帽：两种布局里同一个键用的是同一个对象（KeyboardPanel 按对象认键帽），
 * 只有分裂时的两个空格是另外的对象（份数和整块时不一样）。
 * 选小红点时（只有整块，2026-11-01 起小红点状态不分裂）它嵌在 g、h、v、b 交界那一点（{@link #stickPoint}；多大、压着键多少由 KeyboardPlan 定），
 * 左 / 中 / 右三个鼠标键竖着跨底行（空格那一排）和快捷行：这两行在中间让出同一段空位（{@link #place} 的 hole），
 * 底行变成 ?123 [,.] 空格（缩短，{@link #STICK_SPACE} 份，只有它跟着空位变）│ 空位 │ 空格 ↵（逗号句号合成一个键、右边放第二个空格，2026-11-01），
 * 快捷行的键按个数分到空位两边（{@link #spreadLeft}）；
 * 快捷行分开后键太窄（KeyboardPlan.shortcutsSpread）就不让位、照旧铺满，三个鼠标键只占空格那一排（{@link #place} 的 spread）。
 */
final class KeyRows {
    private KeyRows() {
    }

    /** "符"和"Fn"切换键的按钮编号（不是 HID 键，由 App 自己处理）。 */
    static final int SYMBOLS_BUTTON_ID = 300;
    static final int FN_BUTTON_ID = 301;
    /** 🌐：切换输入法（动作 lang_switch），由 App 执行。 */
    static final int GLOBE_BUTTON_ID = 302;
    /** ⋯：键盘界面的菜单（收起键盘 / 调整大小 / 布局 / 触控板），小触控板没摆出来时放在功能行最右边，由 App 执行。 */
    static final int MENU_BUTTON_ID = 303;

    /**
     * 这个键是不是 ⋯（豆花布局功能行最右的 ⋯ 键、实体键盘 Esc 和 F1 中间的 ⋯ 槽，FixedLayout.MENU_ID 是同一个编号）：
     * 键帽画成强调色的底 + 反差字、不画边框（Theme.menuKey，Style.menuKeyLook；2026-11-01 用户：⋯ 用强调色）。两块面板都按它挑画法。
     */
    static boolean menuKey(int id) {
        return id == MENU_BUTTON_ID;
    }

    static final int SHIFT = 2;
    static final int LAYER_LETTERS = 0;
    static final int LAYER_SYMBOLS = 1;
    static final int LAYER_FN = 2;

    /** 分裂时一半占键盘宽度的比例；中间剩下的（也是约三分之一）给触控板。 */
    static final float SIDE = 1f / 3;
    /** 分裂时字母层每行的份数（整块时一行 10 份，从中间切开、各带上错开的半格）。 */
    static final float HALF_UNITS = 5.5f;

    /**
     * 一个键。id = HID 用法码 | (自带修饰键 << 8)，或者合并键（Proto.dualKey：点一下 / 按住 0.4 秒出两个字）；SPACER = 空位；切换键用 *_BUTTON_ID。
     * corner = 键帽右上角的小字（合并键按住出的那个字；null = 没有）。
     */
    static final class Key {
        static final int SPACER = -1;

        final int id;
        final String label;
        final String shifted;
        final float weight;
        final boolean special;
        final String corner;

        Key(int id, String label, String shifted, float weight, boolean special) {
            this(id, label, shifted, weight, special, null);
        }

        Key(int id, String label, String shifted, float weight, boolean special, String corner) {
            this.id = id;
            this.label = label;
            this.shifted = shifted;
            this.weight = weight;
            this.special = special;
            this.corner = corner;
        }

        boolean spacer() {
            return id == SPACER;
        }

        @Override
        public String toString() {
            return spacer() ? "_" : label;
        }
    }

    /**
     * 一行在两种布局下的样子：whole 是整块时的整行，left / right 是分裂时的左右两半。
     * 小红点状态（整块、{@link #place} 给了 hole）下让出空位的行：before / after 是空位左边、右边的键（底行；
     * before 的最后一个键——缩短的空格——伸缩到空位的左边，其余照整行时的份数宽），spread = 键按个数分到空位两边（快捷行，顺序不变；
     * 只在 {@link #place} 的 spread 为真时真的分，不然照 whole 铺满）；两样都没有的行不让位，照 whole 摆。
     * 每个键都要有键帽：KeyboardPanel 照 {@link #capKeys} 建（含 before / after）。
     */
    static final class Row {
        final Key[] whole, left, right;
        final Key[] before, after;
        final boolean spread;

        Row(Key[] whole, Key[] left, Key[] right) {
            this(whole, left, right, null, null, false);
        }

        Row(Key[] whole, Key[] left, Key[] right, Key[] before, Key[] after, boolean spread) {
            this.whole = whole;
            this.left = left;
            this.right = right;
            this.before = before;
            this.after = after;
            this.spread = spread;
        }

        /** 小红点状态下这一行让不让出空位（底行、快捷行）。 */
        boolean holed() {
            return before != null || spread;
        }
    }

    /** 一个键摆好的位置（键盘面板里的像素，已经扣掉键四周的缝）。 */
    static final class Placed {
        final Key key;
        final int left, top, right, bottom;

        Placed(Key key, int left, int top, int right, int bottom) {
            this.key = key;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    // ═══════════ 键帽上的字、高亮（2026-11-01：按住修饰键也换字，Caps 的显示） ═══════════

    /** 右 Shift 的修饰键位（豆花布局只有左 Shift，实体键盘的右 Shift 按着时两块面板收的是同一份状态）。 */
    static final int RSHIFT = 0x20;

    /** 字母键（a–z、不带自带修饰键、不是特殊键）：跟着 Shift / Caps 换大小写。 */
    static boolean letterKey(Key key) {
        return !key.special && key.id >= 0x04 && key.id <= 0x1D;
    }

    /**
     * 键帽上写哪个字（KeyboardPanel 再按界面语言换空格、修饰键这些键名）：mods = 锁住 | 按着的修饰键（MSG_MODIFIERS 的 latched | held：
     * 按住 Shift 时键帽就变大写，用户原话"我只要按住 Shift，我再按字母键，它就是大写"），caps = 豆花这边显示的大写锁定（只有 App 一份）。
     * 字母：连着苹果设备 Shift ∨ Caps（macOS / iPadOS 大写锁定开着时 Shift 不反转），别的主机 Shift ⊕ Caps（Windows / Linux / 安卓：
     * 大写锁定 + Shift 出小写）；数字行的上档字只看 Shift，符号层、别的键不看 Caps。
     */
    static String capText(Key key, int mods, boolean caps, boolean apple) {
        boolean shift = (mods & (SHIFT | RSHIFT)) != 0;
        boolean upper = letterKey(key) ? (apple ? shift || caps : shift ^ caps) : shift;
        return upper && key.shifted != null ? key.shifted : key.label;
    }

    /**
     * 画不画高亮（锁住的样子：普通键帽底 + 强调色边框 + 高亮字）：修饰键（左右八个）看 shown = latched | armed（锁住的、单独按住到了 0.4 秒
     * "现在松手就锁"的；按着的样子另外画），Caps 看 caps（豆花这边显示的大写锁定）。换层键（?123 / Fn）由面板自己看层。
     */
    static boolean lit(Key key, int shown, boolean caps) {
        if (key.special) return false;
        if (key.id == 0x39) return caps;
        return key.id >= 0xE0 && key.id <= 0xE7 && (shown & (1 << (key.id - 0xE0))) != 0;
    }

    // ═══════════ 主次键帽（主题的功能键颜色） ═══════════

    /**
     * 这个键算不算功能键（键帽用主题的功能键颜色：底色掺一点强调色、字是强调色，照 Gboard / 三星键盘分主次）。
     * 普通键 = 字母、数字、空格、标点（含符号层里自带 Shift 的符号）；其余都是功能键：修饰键、Esc、Tab、Caps、⌫、↵、Del、
     * F1–F12、Home / End / PgUp / PgDn / Ins / PrtSc、方向键，特殊键（?123、Fn、🌐、⋯、快捷行里的非键盘动作），
     * 以及快捷行里的每个键（shortcut：快捷行放的都是动作，哪怕是自定义的"Shift + 字母"，也不当字母键）。
     * 自带 Ctrl / Alt / GUI 的键（"复制" = Ctrl+C）也是功能键。
     * 实体键盘（第 1B 批，FixedLayout）用同一条规则，另外几个只在实体键盘上有的键：ISO 的 Z 左边那个键（0x64，德语 < > |）算标点（普通键；
     * 0x32 本来就在标点那一段里，现在回车左边那个键发 0x31）；小键盘的数字和小数点（0x59–0x63）是普通键，
     * 其余（Num、/ * - +、小键盘回车）是功能键，同主界面数字键盘的 {@link #numpadFunctionKey}。豆花布局里没有这些用法码，结果不变。
     */
    static boolean functionKey(Key key, boolean shortcut) {
        if (key.special || shortcut) return true;
        int usage = key.id & 0xFF;
        int mods = (key.id >> 8) & 0xFF;
        if ((mods & ~(SHIFT | 0x20)) != 0) return true;   // 左右 Shift 以外的修饰键：组合键
        boolean letterOrDigit = usage >= 0x04 && usage <= 0x27;   // a–z、1–0
        boolean punct = usage == 0x2C || (usage >= 0x2D && usage <= 0x38)   // 空格、- = [ ] \ # ; ' ` , . /
                || usage == 0x64;   // ISO 的 Z 左边那个键
        boolean keypad = usage >= 0x59 && usage <= 0x63;   // 小键盘 1–9、0、小数点
        return !(letterOrDigit || punct || keypad);
    }

    /**
     * 数字键盘（主界面的 7 8 9 ⌫ / 4 5 6 + / 1 2 3 - / 0 . Tab ↵）上哪些是功能键：数字和小数点是普通键，
     * 运算符（+ -）、⌫、Tab、↵ 是功能键。"-" 在整屏键盘上是标点（普通键），在数字键盘上和 + 一样算运算符，所以分开判断。
     */
    static boolean numpadFunctionKey(int usage) {
        boolean digit = usage >= 0x1E && usage <= 0x27;
        return !(digit || usage == 0x37);
    }

    // ═══════════ 键 ═══════════

    static Key k(int usage, String label) {
        return new Key(usage, label, null, 1, false);
    }

    /** 锁定 Shift 时键帽换成 shifted（数字行）。 */
    static Key k(int usage, String label, String shifted) {
        return new Key(usage, label, shifted, 1, false);
    }

    static Key wide(int usage, String label, float weight) {
        return new Key(usage, label, null, weight, false);
    }

    /** 自带 Shift 的符号键。 */
    static Key shift(int usage, String label) {
        return new Key(usage | (SHIFT << 8), label, null, 1, false);
    }

    static Key spacer(float weight) {
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

    /** row 的 [from, to)。 */
    private static Key[] slice(Key[] row, int from, int to) {
        Key[] out = new Key[to - from];
        System.arraycopy(row, from, out, 0, out.length);
        return out;
    }

    /**
     * 功能行。Shift 和字母区的 ⇧ 是同一个键（左 Shift，一起高亮）：符号层、Fn 层没有 ⇧，
     * 用户要在任何一层都能锁 Shift（Shift+方向键、Shift+F 键、Ctrl+Shift 组合）。
     */
    private static final Key[] TOP = {
            k(0x29, "Esc"), k(0x2B, "Tab"), k(0xE1, "Shift"), k(0xE0, "Ctrl"), k(0xE2, "Alt"), k(0xE3, "Super"),
            k(0x39, "Caps"), new Key(FN_BUTTON_ID, "Fn", null, 1, true), k(0x4C, "Del"),
    };

    /** ⋯ 菜单键（和 Fn、?123 一样一份宽、由 App 处理）。小触控板摆出来时 ⋯ 是触控板角上的小方块，功能行不带它。 */
    private static final Key MENU_KEY = new Key(MENU_BUTTON_ID, "⋯", null, 1, true);

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

    private static final Key SYMBOLS_KEY = new Key(SYMBOLS_BUTTON_ID, "?123", null, 1.5f, true);
    private static final Key GLOBE_KEY = new Key(GLOBE_BUTTON_ID, "🌐", null, 1, true);
    private static final Key COMMA = k(0x36, ",");
    private static final Key PERIOD = k(0x37, ".");
    private static final Key ENTER = wide(0x28, "↵", 1.5f);

    private static final Key[] BOTTOM = {SYMBOLS_KEY, COMMA, wide(0x2C, "Space", 5), PERIOD, ENTER};

    /**
     * 连着苹果设备时多一个 🌐（切换输入法，iPad 上实测管用；Linux 等主机默认没绑这个键，不显示），空格让出一份。
     */
    private static final Key[] BOTTOM_APPLE = {SYMBOLS_KEY, GLOBE_KEY, COMMA, wide(0x2C, "Space", 4), PERIOD, ENTER};

    /**
     * 小红点状态下底行缩短的空格平时多少份（空位右边是右空格 ↵，左边 ?123 [,.] 和它）：2026-11-01 用户要三个鼠标键占空格那一排，
     * 空格缩短但要留得住——1.5 份（同 ?123、↵）；手机竖屏这样排不下三个 44dp 的鼠标键时才再缩，最短 {@link #STICK_SPACE_MIN} 份
     * （一个字母键宽），见 KeyboardPlan.stickHole。
     */
    static final float STICK_SPACE = 1.5f;
    static final float STICK_SPACE_MIN = 1f;
    /** 小红点状态下的空格（又一个键帽，同分裂的两个空格；连不连苹果设备共用）。 */
    private static final Key STICK_SPACE_KEY = wide(0x2C, "Space", STICK_SPACE);
    /**
     * 小红点状态的逗号键（2026-11-01 用户）：点一下 = ","，按住 0.4 秒 = "."（TouchRouter / KeyHolds 判断，按住不连发）；
     * 键帽大字 ","、右上角小字 "."。占逗号原来的 1 份，主次键帽照 id & 0xFF = 0x36（标点）是普通键。
     */
    static final Key COMMA_PERIOD = new Key(Proto.dualKey(0x36, 0x37), ",", null, 1, false, ".");
    /**
     * 小红点状态右边的空格：给右手（用户原话"右手边没有空格"），照 . 原来的 1 份；同分裂键盘的两个空格，另一个键帽、同一个 id。
     * 份数一个都没变（合并键占逗号的、右空格占句号的），所以空位多宽、红点多大、三个鼠标键多宽全都不变（KeyboardPlan.stickHole）。
     */
    static final Key STICK_SPACE_R = wide(0x2C, "Space", 1);

    /**
     * 分裂时底行：?123 , 空格 | 空格 . ↵，两半都凑够 HALF_UNITS 份；🌐 放左半（左边的空格让出一份）。
     * 小红点状态（整块）：?123 [,.] 空格（缩短）│ 空位 │ 空格 ↵，🌐 照旧跟在 ?123 后面。
     */
    private static final Row BOTTOM_ROW = new Row(BOTTOM,
            new Key[]{SYMBOLS_KEY, COMMA, wide(0x2C, "Space", 3)},
            new Key[]{wide(0x2C, "Space", 3), PERIOD, ENTER},
            new Key[]{SYMBOLS_KEY, COMMA_PERIOD, STICK_SPACE_KEY}, new Key[]{STICK_SPACE_R, ENTER}, false);
    private static final Row BOTTOM_ROW_APPLE = new Row(BOTTOM_APPLE,
            new Key[]{SYMBOLS_KEY, GLOBE_KEY, COMMA, wide(0x2C, "Space", 2)},
            new Key[]{wide(0x2C, "Space", 3), PERIOD, ENTER},
            new Key[]{SYMBOLS_KEY, GLOBE_KEY, COMMA_PERIOD, STICK_SPACE_KEY}, new Key[]{STICK_SPACE_R, ENTER}, false);

    // ═══════════ 行 ═══════════

    /** 按个数对半分成一行：前一半归左（奇数时多的放左边），每半铺满自己那一半的宽度。 */
    static Row halves(Key[] row) {
        int n = (row.length + 1) / 2;
        return new Row(row, slice(row, 0, n), slice(row, n, row.length));
    }

    static final Row[] TOP_ROWS = {halves(TOP)};
    /**
     * 功能行带 ⋯（最右边）：十个键对半分，左 Esc–Alt、右 Super Caps Fn Del ⋯，分裂时 ⋯ 在右半最右。
     * 其余九个键和不带 ⋯ 时是同一批对象（键帽、高亮跟着走）。
     */
    static final Row[] TOP_ROWS_MENU = {halves(concat(TOP, new Key[]{MENU_KEY}))};

    /** 功能行：menu = 带 ⋯ 键（小触控板没摆出来，菜单只能从键盘上开）。 */
    static Row[] topRows(boolean menu) {
        return menu ? TOP_ROWS_MENU : TOP_ROWS;
    }

    /**
     * 字母层分裂：从中间切开，每半每行 HALF_UNITS 份，键和整块时一样宽、错开的位置一样。
     *   左   1 2 3 4 5 _½      右   _½ 6 7 8 9 0
     *        q w e r t _½           _½ y u i o p
     *        _½ a s d f g           g h j k l _½
     *        ⇧ z x c v              v b n m ⌫
     * 第二行原来两头的半格空位留在外侧（a 的左边、l 的右边），整块时就这样；切口那边让 h、b 对着整块时的位置
     * （比 y 往右错半格），空出来的那一格右半再放一个 g、一个 v（用户 2026-10-30："两边都放"，哪只手按都行）：
     * 和两个空格一样是另外的 Key 对象、发同一个键。数字行跟着第一行。
     */
    private static Row[] letterRows() {
        Key[] num = LETTERS[0], top = LETTERS[1], home = LETTERS[2], low = LETTERS[3];
        return new Row[]{
                new Row(num, concat(slice(num, 0, 5), new Key[]{spacer(0.5f)}),
                        concat(new Key[]{spacer(0.5f)}, slice(num, 5, 10))),
                new Row(top, concat(slice(top, 0, 5), new Key[]{spacer(0.5f)}),
                        concat(new Key[]{spacer(0.5f)}, slice(top, 5, 10))),
                // home = _ a s d f g h j k l _
                new Row(home, slice(home, 0, 6), concat(new Key[]{letter('g')}, slice(home, 6, 11))),
                // low = ⇧ z x c v b n m ⌫
                new Row(low, slice(low, 0, 5), concat(new Key[]{letter('v')}, slice(low, 5, 9))),
        };
    }

    private static Row[] halvesRows(Key[][] rows) {
        Row[] out = new Row[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = halves(rows[i]);
        return out;
    }

    /** 中间四行，下标 = LAYER_*。 */
    static final Row[][] LAYERS = {letterRows(), halvesRows(SYMBOLS), halvesRows(FN)};

    /** 底行 + 快捷行（快捷行空着就没有这一行）。快捷行在小红点状态下按个数分到空位两边（spread）。 */
    static Row[] bottomRows(boolean apple, Key[] shortcuts) {
        Row bottom = apple ? BOTTOM_ROW_APPLE : BOTTOM_ROW;
        if (shortcuts.length == 0) return new Row[]{bottom};
        Row h = halves(shortcuts);
        return new Row[]{bottom, new Row(h.whole, h.left, h.right, null, null, true)};
    }

    /** 一层显示的全部行，从上到下：功能行（menu = 带 ⋯）、这一层的中间四行、bottom（底行 + 快捷行，见 {@link #bottomRows}）。 */
    static Row[] rows(boolean menu, int layer, Row[] bottom) {
        Row[] top = topRows(menu);
        Row[] mid = LAYERS[layer];
        Row[] all = new Row[top.length + mid.length + bottom.length];
        System.arraycopy(top, 0, all, 0, top.length);
        System.arraycopy(mid, 0, all, top.length, mid.length);
        System.arraycopy(bottom, 0, all, top.length + mid.length, bottom.length);
        return all;
    }

    /** 三层各自的全部行（下标 = LAYER_*，同 {@link #rows}）：小红点嵌在键里时按每一层的键算它切掉多少（KeyboardPlan.stickRadius）。 */
    static Row[][] layers(boolean menu, Row[] bottom) {
        Row[][] out = new Row[LAYERS.length][];
        for (int i = 0; i < out.length; i++) out[i] = rows(menu, i, bottom);
        return out;
    }

    // ═══════════ 小红点嵌在哪 ═══════════

    /** 字母层第二行 g|h 那条缝在第几份（前面半格空位 + a s d f g）；第三行 v|b 同样在这里（1.5 份的 ⇧ + z x c v），见 {@link #stickPoint}。 */
    static final float STICK_UNITS = unitsBefore(LETTERS[2], "h");
    /** 字母层整块时一行多少份。 */
    static final float LETTER_UNITS = unitsBefore(LETTERS[2], null);

    /** row 里 label 那个键前面一共多少份（label = null：整行）。 */
    static float unitsBefore(Key[] row, String label) {
        float sum = 0;
        for (Key key : row) {
            if (label != null && label.equals(key.label)) break;
            sum += key.weight;
        }
        return sum;
    }

    /**
     * 小红点嵌在键盘里的那一点（面板坐标 {x, y}；2026-11-01 用户："像真的 ThinkPad 键盘一样，指点杆直接放键盘中间"）：
     * 字母层第二行 g|h 的缝、第三行 v|b 的缝和两行之间的缝交在一起的地方（ThinkPad 的 G、H、B 中间）。
     * g|h、v|b 都在一行的 {@link #STICK_UNITS} / {@link #LETTER_UNITS} 处（取整同 {@link #place}，正好是那条缝的中线）。
     * 高度 = 中间四行的第三行（a–l）和第四行（z–m）之间那条线；rowCount = 这一层一共几行（三层一样多）。
     * 符号层、Fn 层那里是别的键（不一定正好四个键交在这一点），红点照样在这儿，不跟着换层挪。
     * 只有整块：小红点状态不分裂（2026-11-01 用户），以前分裂时放在两半中间。
     */
    static int[] stickPoint(int width, int height, int rowCount) {
        int y = rowCount > 0 ? Math.round((float) height * (TOP_ROWS.length + 3) / rowCount) : height / 2;
        return new int[]{Math.round(width * STICK_UNITS / LETTER_UNITS), y};
    }

    // ═══════════ 小红点状态：底行、快捷行让出的空位 ═══════════

    /**
     * 让空位的底行在份数上是什么样，返回 {空位左边的键共多少份（空格按 STICK_SPACE 份）, 右边的键共多少份, 整行多少份}；
     * rows 里没有让位的底行返回 null。空位左边最多能到 [0] 份处、最少到 [0] − (STICK_SPACE − STICK_SPACE_MIN) 份处；
     * 右边最远到 [2] − [1] 份处（右边那个空格原来的左边 = 整行时 . 的左边），KeyboardPlan.stickHole 在这中间挑。
     */
    static float[] holeUnits(Row[] rows) {
        for (Row row : rows) {
            if (row.before == null) continue;
            return new float[]{unitsBefore(row.before, null), unitsBefore(row.after, null), unitsBefore(row.whole, null)};
        }
        return null;
    }

    /** 第一行让位的行（底行）的上沿（面板像素，取整同 {@link #place}）；没有让位的行返回 -1。 */
    static int holeTop(Row[] rows, int height) {
        int n = rows.length;
        for (int i = 0; i < n; i++) if (rows[i].holed()) return Math.round((float) height * i / n);
        return -1;
    }

    /**
     * 空位的下沿（面板像素，取整同 {@link #place}）：最后一行真让了位的行的下沿——底行一直让；快捷行只在 spread（快捷行也让位，
     * KeyboardPlan.Plan.spread）时算。没有让位的行返回 -1。快捷行不让位时三个鼠标键只占空格那一排，下沿是底行的下沿。
     */
    static int holeBottom(Row[] rows, int height, boolean spread) {
        int n = rows.length;
        for (int i = n - 1; i >= 0; i--) {
            if (rows[i].before != null || (spread && rows[i].spread)) return Math.round((float) height * (i + 1) / n);
        }
        return -1;
    }

    /**
     * 快捷行 n 个键分到空位两边：左边宽 left、右边宽 right（像素），返回左边放几个（其余放右边，顺序不变）。
     * 按两边的宽度比例分、四舍五入，让两边的键差不多宽；两边都有地方、键又不止一个时每边至少一个（不留一整边空着）。
     */
    static int spreadLeft(int n, int left, int right) {
        if (n <= 0) return 0;
        if (left <= 0) return 0;
        if (right <= 0) return n;
        int k = Math.round((float) n * left / (left + right));
        if (n >= 2) k = Math.max(1, Math.min(n - 1, k));
        return Math.max(0, Math.min(n, k));
    }

    // ═══════════ 分裂 / 排布 ═══════════

    /** 键盘区（键盘界面整页）宽 ≥ 2 × 高就分裂。 */
    static boolean shouldSplit(int width, int height) {
        return height > 0 && width >= 2L * height;
    }

    /** 分裂时中间那截留给触控板：两半各占 {@link #SIDE} 宽（见 {@link #leftEnd(int, int)} 的 middle）。 */
    static final int MIDDLE_PAD = -1;

    /** 分裂时左半的右边界（面板里的 x），两半各三分之一宽（中间放触控板）。 */
    static int leftEnd(int width) {
        return leftEnd(width, MIDDLE_PAD);
    }

    /** 分裂时右半的左边界；和左半对称。 */
    static int rightStart(int width) {
        return rightStart(width, MIDDLE_PAD);
    }

    /**
     * 分裂时左半的右边界。middle = {@link #MIDDLE_PAD}：两半各三分之一宽，中间给触控板；
     * middle ≥ 0：两半中间隔这么宽——默认是中间不放触控板（藏起来了，或者放到了键盘上方 / 下方），两半往中间伸、只隔一条缝；
     * 用户调过分裂的大小时是调出来的宽度（中间可能放着触控板，见 KeyboardPlan）。
     */
    static int leftEnd(int width, int middle) {
        if (middle < 0) return Math.round(width * SIDE);
        return Math.max(0, width - Math.min(middle, width)) / 2;
    }

    /** 分裂时右半的左边界；和左半对称（宽度除不尽时多的一像素归中间那截）。 */
    static int rightStart(int width, int middle) {
        return width - leftEnd(width, middle);
    }

    /**
     * 分裂时左半的键盘范围（REGION_KEYBOARD，不是键）右边界。中间放触控板（{@link #MIDDLE_PAD}）时就是 {@link #leftEnd(int, int)}，
     * 中间那截什么都不是；两半只隔一条缝时伸到缝的中线，缝分给各自那一半最近的键（同整块键盘的缝），t|y、g|h、b|n 之间不留死区。
     * middle ≥ 0 但两半被拉开了（不只是一条缝）时 KeyboardPanel 不用这个、用 {@link #leftEnd(int, int)}。
     */
    static int areaEnd(int width, int middle) {
        if (middle < 0) return leftEnd(width, middle);
        return (leftEnd(width, middle) + rightStart(width, middle)) / 2;
    }

    /** 分裂时右半的键盘范围左边界：只隔一条缝时和左半的右边界是同一条中线（中线那一像素归右半）。 */
    static int areaStart(int width, int middle) {
        return middle < 0 ? rightStart(width, middle) : areaEnd(width, middle);
    }

    /** 分裂时一份（字母键连缝）有多宽：一半的宽度 / {@link #HALF_UNITS}。用来判断分裂了还按不按得准。 */
    static float splitUnit(int width, int middle) {
        return leftEnd(width, middle) / HALF_UNITS;
    }

    /** 这些行在这种布局下显示哪些键（不含空位），顺序同 {@link #place}：一行行往下，每行先左半后右半。 */
    static List<Key> keys(Row[] rows, boolean split) {
        return keys(rows, split, false);
    }

    /**
     * 这些行在所有布局下（整块、分裂、小红点让位）可能显示的键（不含空位、同一个对象只算一次），KeyboardPanel 照它给每个键建键帽。
     * 和 {@link #keys} 的规则放在一处：{@link Row#before} / {@link Row#after}（小红点状态的底行，比如缩短的空格）以前漏了，
     * 一进小红点状态就拿不到键帽、闪退（2026-11-01 审查发现）；KeyRowsTest 锁住"keys 给的每个键都在这里"。
     */
    static List<Key> capKeys(Row[] rows) {
        List<Key> out = new ArrayList<>();
        java.util.IdentityHashMap<Key, Boolean> seen = new java.util.IdentityHashMap<>();
        for (Row row : rows) {
            for (Key[] part : new Key[][]{row.whole, row.left, row.right, row.before, row.after}) {
                if (part == null) continue;
                for (Key key : part) {
                    if (key.spacer() || seen.put(key, Boolean.TRUE) != null) continue;
                    out.add(key);
                }
            }
        }
        return out;
    }

    /** 同上，hole = 小红点状态、底行和快捷行让出空位（只在整块时有用）：底行换成 before + after，快捷行照旧（左边的在前）。 */
    static List<Key> keys(Row[] rows, boolean split, boolean hole) {
        List<Key> out = new ArrayList<>();
        for (Row row : rows) {
            if (split) {
                addKeys(out, row.left);
                addKeys(out, row.right);
            } else if (hole && row.before != null) {
                addKeys(out, row.before);
                addKeys(out, row.after);
            } else {
                addKeys(out, row.whole);
            }
        }
        return out;
    }

    private static void addKeys(List<Key> out, Key[] keys) {
        for (Key key : keys) if (!key.spacer()) out.add(key);
    }

    /**
     * 把 rows 摆进 width × height 的面板：每行一样高；每行（分裂时每半）按 weight 分宽度，键的四边各缩进 gap。
     * 边界都取整、相邻的键共用同一条边界，缝处处一样宽。顺序同 {@link #keys}。
     */
    static List<Placed> place(Row[] rows, int width, int height, boolean split, int gap) {
        return place(rows, width, height, split, MIDDLE_PAD, gap);
    }

    /** 同上，middle 见 {@link #leftEnd(int, int)}（只在分裂时有用）。 */
    static List<Placed> place(Row[] rows, int width, int height, boolean split, int middle, int gap) {
        return place(rows, width, height, split, middle, gap, null, false);
    }

    /**
     * 同上，hole = 小红点状态下底行、快捷行让出的空位 {左, 右}（面板像素，KeyboardPlan.stickHole 定；null = 不让，分裂时不看）：
     * 让位的行（{@link Row#holed}）在 [0, 左]、[右, width] 两段里摆，空位里什么都不摆（三个鼠标键在那儿）。
     * 底行：before 里除了最后一个（缩短的空格）照整行时的份数宽（取整同整行），空格从前一个键的右边伸到空位的左边；
     * after 在 [右, width] 里按份数分（空位右边比右空格原来的左边靠左时右空格和 ↵ 按 1 : 1.5 一起变宽）。
     * 快捷行：spread（快捷行也让位，KeyboardPlan.Plan.spread）时按 {@link #spreadLeft} 分到两段、各自铺满；不然照旧铺满整行
     * （让位后快捷键太窄时，三个鼠标键只占空格那一排）。
     */
    static List<Placed> place(Row[] rows, int width, int height, boolean split, int middle, int gap, int[] hole, boolean spread) {
        List<Placed> out = new ArrayList<>();
        int n = rows.length;
        for (int i = 0; i < n; i++) {
            int top = Math.round((float) height * i / n);
            int bottom = Math.round((float) height * (i + 1) / n);
            Row row = rows[i];
            if (split) {
                placeRow(out, row.left, 0, leftEnd(width, middle), top, bottom, gap);
                placeRow(out, row.right, rightStart(width, middle), width, top, bottom, gap);
            } else if (hole != null && row.before != null) {
                placeFlex(out, row.before, width / unitsBefore(row.whole, null), hole[0], top, bottom, gap);
                placeRow(out, row.after, hole[1], width, top, bottom, gap);
            } else if (hole != null && spread && row.spread) {
                int k = spreadLeft(row.whole.length, hole[0], width - hole[1]);
                placeRow(out, slice(row.whole, 0, k), 0, hole[0], top, bottom, gap);
                placeRow(out, slice(row.whole, k, row.whole.length), hole[1], width, top, bottom, gap);
            } else {
                placeRow(out, row.whole, 0, width, top, bottom, gap);
            }
        }
        return out;
    }

    /** keys 从 0 开始照 unit（每份多少像素）排、边界取整，最后一个键伸缩到 end 为止（缩短的空格）。 */
    private static void placeFlex(List<Placed> out, Key[] keys, float unit, int end, int top, int bottom, int gap) {
        float used = 0;
        int left = 0;
        for (int i = 0; i < keys.length; i++) {
            Key key = keys[i];
            used += key.weight;
            int right = i == keys.length - 1 ? end : Math.round(unit * used);
            if (!key.spacer()) out.add(new Placed(key, left + gap, top + gap, right - gap, bottom - gap));
            left = right;
        }
    }

    /**
     * 分裂时这一半（right = 右半）的键盘范围该到哪儿为止：这一半最后一行有键的那一行的下沿（取整同 {@link #place}），
     * 一行都没有就是 0。快捷行只有 1 个键时右半那一行是空的，范围要是盖到底，按在那里会被 nearestKey 分给上一行的空格 / ↵。
     */
    static int halfBottom(Row[] rows, int height, boolean right) {
        int n = rows.length;
        for (int i = n - 1; i >= 0; i--) {
            for (Key key : right ? rows[i].right : rows[i].left) {
                if (!key.spacer()) return Math.round((float) height * (i + 1) / n);
            }
        }
        return 0;
    }

    private static void placeRow(List<Placed> out, Key[] keys, int x0, int x1, int top, int bottom, int gap) {
        float total = 0;
        for (Key key : keys) total += key.weight;
        if (total <= 0) return;
        float used = 0;
        int left = x0;
        for (Key key : keys) {
            used += key.weight;
            int right = Math.round(x0 + (x1 - x0) * used / total);
            if (!key.spacer()) out.add(new Placed(key, left + gap, top + gap, right - gap, bottom - gap));
            left = right;
        }
    }
}
