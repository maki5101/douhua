package dev.douhua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实体键盘的几何（第 1B 批，2026-11-01），纯 Java（离线测，FixedLayoutTest）：每个键在哪、多宽、发哪个 HID 用法码，单位 u（1u = 一个字母键连缝）。
 * 豆花布局（KeyRows）每行按份数铺满，画不出实体键盘的 L 形回车、两行高的键、几个区块之间的空，所以另起一套，KeyRows 不动。
 *
 * 一块 = 形状（ANSI / ISO）× 尺寸（TKL / 全尺寸）× 模式（Windows / Mac）：形状只管主键区五行，模式只管最下一排、编辑区和几个键名，
 * 尺寸只管有没有数字小键盘。语言（键帽上的字）不在这里，在 FixedLegends（出字的键 name 是 null，字按用法码查表）。
 *
 * 竖着：F 行（第 0 行）、隔 0.25u、主键区五行（第 1–5 行：数字行、Q 行、A 行、Z 行、空格那一排），一共 6.25u 高（{@link #ROW_TOP}）。
 * 横着：主键区 0–15u、编辑区 15.25–18.25u（F 行那三个键、Ins Home PgUp / Del End PgDn、倒 T 方向键）、全尺寸再加小键盘 18.5–22.5u。
 * 主键区每行凑满 15u（宽度照主流机械键盘：Tab 1.5、Caps 1.75、左 Shift 2.25（ISO 1.25）、右 Shift 2.75、底行 1.25 × 3 + 空格 6.25 + 1.25 × 4，
 * 凭记忆，见方案 [F] §2）；F 行 Esc、空 1u、F1–F4、空 0.5u、F5–F8、空 0.5u、F9–F12。
 * Esc 和 F1 之间那 1u 是 ⋯ 槽（{@link #menu}）：小触控板上摆不出 ⋯ 时 ⋯ 放在这里（KeyRows.MENU_BUTTON_ID），不然那格空着、分给最近的 F 键。
 *
 * 发什么码（照真键盘）：
 * - Windows 模式（PC 惯例）：1 左边那个键 0x35，ISO 的 Z 左边那个键 0x64，ISO 回车左边那个键 0x31（不发 0x32：有 0x31 的键盘上从来不出现 0x32，
 *   各家主机都把两者当一回事，方案 §3 第 1 条）；底行 Ctrl · Super · Alt · 空格 · AltGr / Alt · Super · ☰（Menu 0x65）· Ctrl；
 *   F 行右边 PrtSc ScrLk Pause。连 Linux、安卓也用这个模式。
 * - Mac 模式（照苹果键盘，连 iPad 也用它）：底行 ⌃ · ⌥ · ⌘ · 空格 · ⌘ · ⌥ · 🌐 · ⌃（Option 发 Alt 的用法码 0xE2 / 0xE6、Command 发 GUI 0xE3 / 0xE7，
 *   照真苹果键盘；🌐 是 KeyRows.GLOBE_BUTTON_ID，同豆花布局的 🌐 切换输入法）；PrtSc ScrLk Pause 换成 F13 F14 F15（苹果的全尺寸键盘就是 F13 起），
 *   Ins 那格空着（苹果那格是 fn，第 3 批）、没有 Menu 键，小键盘的 Num 写 Clear。
 *   **ISO 的那两个键照真苹果 ISO 键盘发**：1 左边那个键（§ / ^）发 0x64、Z 左边那个键（` / <）发 0x35，和 PC 惯例正好对调。
 *   依据：Linux 的 hid-apple 驱动给苹果 ISO 键盘专门把 KEY_GRAVE（0x35）和 KEY_102ND（0x64）对调回来（apple_iso_keyboard[] 表、
 *   模块参数 iso_layout "Swap the backtick/tilde and greater-than/less-than keys"，默认按国家码自动开，2026-11-01 读了 torvalds/linux 的 hid-apple.c），
 *   说明苹果 ISO 键盘这两个位置报的码和 PC 惯例相反；macOS 的虚拟键码 kVK_ISO_Section（0x0A，就是 § 那个键）在不对调时对应 HID 0x64
 *   （Chromium dom_code_data.inc，协调者核过）。主机怎么认（macOS 把豆花当 ANSI 还是 ISO、iPad 一律当美式）是主机的事，没在真机上验证。
 */
final class FixedLayout {
    static final String ANSI = "ansi", ISO = "iso";
    static final String TKL = "tkl", FULL = "full";
    static final String WIN = "win", MAC = "mac";

    /** 区块：主键区（连 F 行的 Esc–F12）、编辑区（连 F 行右边那三个、方向键）、数字小键盘。 */
    static final int MAIN = 0, EDIT = 1, NUMPAD = 2;

    /** 每行的上沿（u）：第 0 行是 F 行，隔 0.25u 是主键区五行。每行 1u 高。 */
    static final float[] ROW_TOP = {0, 1.25f, 2.25f, 3.25f, 4.25f, 5.25f};
    static final int ROWS = ROW_TOP.length;
    static final float HEIGHT = 6.25f;
    /** 各区块的左右边（u）。 */
    static final float MAIN_RIGHT = 15, EDIT_LEFT = 15.25f, EDIT_RIGHT = 18.25f, PAD_LEFT = 18.5f, PAD_RIGHT = 22.5f;

    /** ⋯ 菜单键、🌐（编号同豆花布局，MainActivity 一样处理）。 */
    static final int MENU_ID = KeyRows.MENU_BUTTON_ID;
    static final int GLOBE_ID = KeyRows.GLOBE_BUTTON_ID;

    /** 空格的键名占位（界面语言的"空格"由 App 换上，FixedPanel.glyphs）。 */
    static final String SPACE_NAME = "Space";

    /** 一个键。 */
    static final class Key {
        /** HID 用法码；特殊键（⋯、🌐）是按钮编号。 */
        final int id;
        final boolean special;
        final int cluster;
        /** 从第几行起（0–5），占几行（小键盘的 + 和 Enter 两行；ISO 回车另有 {@link #x2}）。 */
        final int row, rows;
        /** 第一行那段的左边、宽（u）。 */
        final float x, w;
        /** ISO 回车下半段（第 row + 1 行）的左边、宽；不是 L 形的宽是 0。 */
        final float x2, w2;
        /** 键帽上写的名字（功能键、小键盘、空格）；出字的键（字母、数字、标点）是 null，字从 FixedLegends 按用法码查。 */
        final String name;
        /** 同一个键的 KeyRows.Key（id、名字、宽、special）：KeyRows.functionKey 判主次、KeyboardPlan 算切口都按它。 */
        final KeyRows.Key face;

        Key(int id, boolean special, int cluster, int row, int rows, float x, float w, float x2, float w2, String name) {
            this.id = id;
            this.special = special;
            this.cluster = cluster;
            this.row = row;
            this.rows = rows;
            this.x = x;
            this.w = w;
            this.x2 = x2;
            this.w2 = w2;
            this.name = name;
            this.face = new KeyRows.Key(id, name == null ? "" : name, null, w, special);
        }

        boolean lShape() {
            return w2 > 0;
        }

        /** 这个键在第 r 行占的 {左, 右}（u）；不在这一行返回 null。 */
        float[] span(int r) {
            if (lShape()) {
                if (r == row) return new float[]{x, x + w};
                if (r == row + 1) return new float[]{x2, x2 + w2};
                return null;
            }
            return r >= row && r < row + rows ? new float[]{x, x + w} : null;
        }

        /** 是出字的键（字从 FixedLegends 来）。 */
        boolean charKey() {
            return name == null && !special;
        }

        @Override
        public String toString() {
            return name != null ? name : String.format("0x%02X", id);
        }
    }

    final String shape, size, mode;
    /** 不含 ⋯ 键（⋯ 槽里放不放它看小触控板上摆没摆出 ⋯，FixedPanel.setMenuKey）。 */
    final List<Key> keys;
    /** ⋯ 槽里的那个键（Esc 和 F1 之间那 1u）。 */
    final Key menu;
    final float width;
    /**
     * Windows 模式下右 Alt 是 AltGr（这个布局在 Windows 上有第三层的字，键帽写 AltGr）：锁住它时键帽上第三层的字亮、别的压暗（FixedPanel.layer）。
     * 美式没有第三层，右 Alt 写 Alt，锁住它和锁左 Alt 一样什么都不变（两种 Alt + 字母都打不出字）。Mac 模式一律假（任一 Option 是第三层，不看它）。
     */
    final boolean altGr;

    private FixedLayout(String shape, String size, String mode, List<Key> keys, Key menu, float width, boolean altGr) {
        this.shape = shape;
        this.size = size;
        this.mode = mode;
        this.keys = Collections.unmodifiableList(keys);
        this.menu = menu;
        this.width = width;
        this.altGr = altGr;
    }

    boolean mac() {
        return MAC.equals(mode);
    }

    boolean iso() {
        return ISO.equals(shape);
    }

    boolean full() {
        return FULL.equals(size);
    }

    /** 这一块显示的键：menuKey = ⋯ 放在槽里（小触控板上没摆出 ⋯）。顺序就是报区域、画的顺序。 */
    List<Key> keys(boolean menuKey) {
        if (!menuKey) return keys;
        List<Key> out = new ArrayList<>(keys);
        out.add(1, menu);   // 跟在 Esc 后面
        return out;
    }

    /** 用法码 / 按钮编号是 id 的键（不含 ⋯）；没有返回 null。 */
    Key find(int id) {
        for (Key k : keys) if (k.id == id) return k;
        return null;
    }

    /** 区块的左右边（u）。 */
    static float clusterLeft(int cluster) {
        return cluster == MAIN ? 0 : cluster == EDIT ? EDIT_LEFT : PAD_LEFT;
    }

    static float clusterRight(int cluster) {
        return cluster == MAIN ? MAIN_RIGHT : cluster == EDIT ? EDIT_RIGHT : PAD_RIGHT;
    }

    /** 第 r 行的上沿、下沿（u）。 */
    static float rowTop(int r) {
        return ROW_TOP[r];
    }

    static float rowBottom(int r) {
        return ROW_TOP[r] + 1;
    }

    /** 能选的值，认不出的换成默认的（ANSI、TKL、Windows）。 */
    static String validShape(String s) {
        return ISO.equals(s) ? ISO : ANSI;
    }

    static String validSize(String s) {
        return FULL.equals(s) ? FULL : TKL;
    }

    static String validMode(String s) {
        return MAC.equals(s) ? MAC : WIN;
    }

    /**
     * 照字表拼一块（FixedData.layout 用）：右 Alt 写不写 AltGr 看字表有没有第三层。字表是 null（键帽数据读不出来，或者这个布局这种模式没有那一段）
     * 就不拼、返回 null——KeyboardScreen 拿不到布局就照样显示豆花布局（⋯ 菜单写明），不显示一块出字的键全是空白的实体键盘。
     */
    static FixedLayout forLegends(String shape, String size, String mode, FixedLegends.Table legends, String kpdl) {
        return legends == null ? null : build(shape, size, mode, legends.hasThird(), kpdl);
    }

    /**
     * 拼一块键盘。altGr = 右 Alt 写 AltGr（这个布局在 Windows 上有第三层的字；美式没有，写 Alt），Mac 模式不看；
     * kpdl = 小键盘小数点出的字（布局清单的 kpdl，德语是 ","）。
     */
    static FixedLayout build(String shape, String size, String mode, boolean altGr, String kpdl) {
        shape = validShape(shape);
        size = validSize(size);
        mode = validMode(mode);
        boolean mac = MAC.equals(mode), iso = ISO.equals(shape), full = FULL.equals(size);
        List<Key> k = new ArrayList<>();

        // ── 第 0 行：F 行 ──
        k.add(key(0x29, MAIN, 0, 0, 1, "Esc"));
        for (int i = 0; i < 12; i++) {
            float x = i < 4 ? 2 + i : i < 8 ? 6.5f + (i - 4) : 11 + (i - 8);
            k.add(key(0x3A + i, MAIN, 0, x, 1, "F" + (i + 1)));
        }
        if (mac) {
            for (int i = 0; i < 3; i++) k.add(key(0x68 + i, EDIT, 0, EDIT_LEFT + i, 1, "F" + (13 + i)));
        } else {
            k.add(key(0x46, EDIT, 0, EDIT_LEFT, 1, "PrtSc"));
            k.add(key(0x47, EDIT, 0, EDIT_LEFT + 1, 1, "ScrLk"));
            k.add(key(0x48, EDIT, 0, EDIT_LEFT + 2, 1, "Pause"));
        }

        // ── 第 1 行：数字行 ──
        // 1 左边那个键：ANSI 两种模式都是 0x35；ISO 时 Windows 0x35、Mac 照真苹果 ISO 键盘发 0x64（见类注释）
        k.add(key(iso && mac ? 0x64 : 0x35, MAIN, 1, 0, 1, null));
        for (int i = 0; i < 10; i++) k.add(key(0x1E + i, MAIN, 1, 1 + i, 1, null));
        k.add(key(0x2D, MAIN, 1, 11, 1, null));
        k.add(key(0x2E, MAIN, 1, 12, 1, null));
        k.add(key(0x2A, MAIN, 1, 13, 2, "⌫"));
        if (!mac) k.add(key(0x49, EDIT, 1, EDIT_LEFT, 1, "Ins"));
        k.add(key(0x4A, EDIT, 1, EDIT_LEFT + 1, 1, "Home"));
        k.add(key(0x4B, EDIT, 1, EDIT_LEFT + 2, 1, "PgUp"));

        // ── 第 2 行：Q 行 ──
        k.add(key(0x2B, MAIN, 2, 0, 1.5f, "Tab"));
        letters(k, 2, 1.5f, "qwertyuiop");
        k.add(key(0x2F, MAIN, 2, 11.5f, 1, null));
        k.add(key(0x30, MAIN, 2, 12.5f, 1, null));
        if (iso) {
            // L 形回车：上半 1.5u（第 2 行），下半 1.25u（第 3 行）
            k.add(new Key(0x28, false, MAIN, 2, 1, 13.5f, 1.5f, 13.75f, 1.25f, "↵"));
        } else {
            k.add(key(0x31, MAIN, 2, 13.5f, 1.5f, null));
        }
        k.add(key(0x4C, EDIT, 2, EDIT_LEFT, 1, "Del"));
        k.add(key(0x4D, EDIT, 2, EDIT_LEFT + 1, 1, "End"));
        k.add(key(0x4E, EDIT, 2, EDIT_LEFT + 2, 1, "PgDn"));

        // ── 第 3 行：A 行 ──
        k.add(key(0x39, MAIN, 3, 0, 1.75f, "Caps"));
        letters(k, 3, 1.75f, "asdfghjkl");
        k.add(key(0x33, MAIN, 3, 10.75f, 1, null));
        k.add(key(0x34, MAIN, 3, 11.75f, 1, null));
        if (iso) k.add(key(0x31, MAIN, 3, 12.75f, 1, null));   // 回车左边那个键（德语 # '）
        else k.add(key(0x28, MAIN, 3, 12.75f, 2.25f, "↵"));

        // ── 第 4 行：Z 行 ──
        if (iso) {
            k.add(key(0xE1, MAIN, 4, 0, 1.25f, "⇧"));
            k.add(key(mac ? 0x35 : 0x64, MAIN, 4, 1.25f, 1, null));   // Z 左边那个键：Windows 0x64、Mac 0x35（见类注释）
        } else {
            k.add(key(0xE1, MAIN, 4, 0, 2.25f, "⇧"));
        }
        letters(k, 4, 2.25f, "zxcvbnm");
        k.add(key(0x36, MAIN, 4, 9.25f, 1, null));
        k.add(key(0x37, MAIN, 4, 10.25f, 1, null));
        k.add(key(0x38, MAIN, 4, 11.25f, 1, null));
        k.add(key(0xE5, MAIN, 4, 12.25f, 2.75f, "⇧"));
        k.add(key(0x52, EDIT, 4, EDIT_LEFT + 1, 1, "↑"));

        // ── 第 5 行：空格那一排 ──
        float m = 1.25f;
        if (mac) {
            k.add(key(0xE0, MAIN, 5, 0, m, "⌃"));
            k.add(key(0xE2, MAIN, 5, m, m, "⌥"));
            k.add(key(0xE3, MAIN, 5, 2 * m, m, "⌘"));
            k.add(key(0x2C, MAIN, 5, 3 * m, 6.25f, SPACE_NAME));
            k.add(key(0xE7, MAIN, 5, 10, m, "⌘"));
            k.add(key(0xE6, MAIN, 5, 10 + m, m, "⌥"));
            k.add(new Key(GLOBE_ID, true, MAIN, 5, 1, 10 + 2 * m, m, 0, 0, "🌐"));
            k.add(key(0xE4, MAIN, 5, 10 + 3 * m, m, "⌃"));
        } else {
            k.add(key(0xE0, MAIN, 5, 0, m, "Ctrl"));
            k.add(key(0xE3, MAIN, 5, m, m, "Super"));
            k.add(key(0xE2, MAIN, 5, 2 * m, m, "Alt"));
            k.add(key(0x2C, MAIN, 5, 3 * m, 6.25f, SPACE_NAME));
            k.add(key(0xE6, MAIN, 5, 10, m, altGr ? "AltGr" : "Alt"));
            k.add(key(0xE7, MAIN, 5, 10 + m, m, "Super"));
            k.add(key(0x65, MAIN, 5, 10 + 2 * m, m, "☰"));
            k.add(key(0xE4, MAIN, 5, 10 + 3 * m, m, "Ctrl"));
        }
        k.add(key(0x50, EDIT, 5, EDIT_LEFT, 1, "←"));
        k.add(key(0x51, EDIT, 5, EDIT_LEFT + 1, 1, "↓"));
        k.add(key(0x4F, EDIT, 5, EDIT_LEFT + 2, 1, "→"));

        // ── 数字小键盘（全尺寸）──
        if (full) {
            float p = PAD_LEFT;
            k.add(key(0x53, NUMPAD, 1, p, 1, mac ? "Clear" : "Num"));
            k.add(key(0x54, NUMPAD, 1, p + 1, 1, "/"));
            k.add(key(0x55, NUMPAD, 1, p + 2, 1, "*"));
            k.add(key(0x56, NUMPAD, 1, p + 3, 1, "-"));
            String[] digits = {"7", "8", "9", "4", "5", "6", "1", "2", "3"};
            int[] usages = {0x5F, 0x60, 0x61, 0x5C, 0x5D, 0x5E, 0x59, 0x5A, 0x5B};
            for (int i = 0; i < 9; i++) k.add(key(usages[i], NUMPAD, 2 + i / 3, p + i % 3, 1, digits[i]));
            k.add(new Key(0x57, false, NUMPAD, 2, 2, p + 3, 1, 0, 0, "+"));
            k.add(new Key(0x58, false, NUMPAD, 4, 2, p + 3, 1, 0, 0, "↵"));
            k.add(key(0x62, NUMPAD, 5, p, 2, "0"));
            k.add(key(0x63, NUMPAD, 5, p + 2, 1, kpdl == null || kpdl.isEmpty() ? "." : kpdl));
        }
        Key menu = new Key(MENU_ID, true, MAIN, 0, 1, 1, 1, 0, 0, "⋯");
        return new FixedLayout(shape, size, mode, k, menu, full ? PAD_RIGHT : EDIT_RIGHT, !mac && altGr);
    }

    private static Key key(int id, int cluster, int row, float x, float w, String name) {
        return new Key(id, false, cluster, row, 1, x, w, 0, 0, name);
    }

    /** 一行字母键（a–z 的用法码 0x04 起，按字母算），从 x 起一个挨一个。 */
    private static void letters(List<Key> k, int row, float x, String s) {
        for (int i = 0; i < s.length(); i++) k.add(key(0x04 + (s.charAt(i) - 'a'), MAIN, row, x + i, 1, null));
    }

    // ═══════════ 键盘范围（每区块每行一块） ═══════════

    /** 一块键盘范围（u）：哪个区块、哪一行，{左, 上, 右, 下}。 */
    static final class Range {
        final int cluster, row;
        final float left, top, right, bottom;

        Range(int cluster, int row, float left, float top, float right, float bottom) {
            this.cluster = cluster;
            this.row = row;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override
        public String toString() {
            return "范围 区块" + cluster + " 第" + row + "行 [" + left + ", " + right + "]";
        }
    }

    /** 同一行里两个键之间隔着不超过这么宽（u）还算同一段（F 行 Esc 和 F1 之间那 1u 的 ⋯ 槽、F4 | F5 的半格）。 */
    static final float SEGMENT_GAP = 1.01f;

    /**
     * 键盘范围（REGION_KEYBOARD），每个区块的每一行各一块（方案 §5.3，[F] / [I] 说法不一时定的）：按在缝里只分给同一行这一段里最近的键。
     * 整块一块的话，按在 TKL 方向键 ↑ 正上方那格空白里，主键区同一行的回车 / ' 竖着的距离是 0，会赢过近得多的 ↑。
     * - 横着：从这一段最左的键到最右的键；这一段挨着区块的边时伸到和相邻区块之间空隙的中线，最外面的伸到面板边。
     * - 竖着：伸到和上下行之间的中线，最上、最下伸到面板边（行和行是挨着的，F 行和数字行之间的 0.25u 各分一半）。
     * - 一行里没有键的地方不在任何范围里（方向键上面那几格、↑ 两边、Mac 模式 Ins 那格），按了什么都不发生，和真键盘一样。
     * - 一段之内的小缝（F4 和 F5 之间那半格、空着的 ⋯ 槽）在范围里，分给最近的键。
     * menuKey 同 {@link #keys(boolean)}（槽里放没放 ⋯，结果一样：槽在 Esc 和 F1 中间，本来就在这一段里）。
     */
    List<Range> ranges(boolean menuKey) {
        List<Range> out = new ArrayList<>();
        List<Key> ks = keys(menuKey);
        int clusters = full() ? 3 : 2;
        for (int c = 0; c < clusters; c++) {
            float cl = clusterLeft(c), cr = clusterRight(c);
            float outLeft = c == 0 ? 0 : (clusterRight(c - 1) + cl) / 2;
            float outRight = c == clusters - 1 ? width : (cr + clusterLeft(c + 1)) / 2;
            for (int r = 0; r < ROWS; r++) {
                List<float[]> spans = new ArrayList<>();
                for (Key k : ks) {
                    if (k.cluster != c) continue;
                    float[] s = k.span(r);
                    if (s != null) spans.add(s);
                }
                if (spans.isEmpty()) continue;
                spans.sort((a, b) -> Float.compare(a[0], b[0]));
                float top = r == 0 ? 0 : r == 1 ? (rowBottom(0) + rowTop(1)) / 2 : rowTop(r);
                float bottom = r == 0 ? (rowBottom(0) + rowTop(1)) / 2 : r == ROWS - 1 ? HEIGHT : rowBottom(r);
                float segL = spans.get(0)[0], segR = spans.get(0)[1];
                for (int i = 1; i <= spans.size(); i++) {
                    if (i < spans.size() && spans.get(i)[0] - segR <= SEGMENT_GAP) {
                        segR = Math.max(segR, spans.get(i)[1]);
                        continue;
                    }
                    float l = Math.abs(segL - cl) < 1e-3f ? outLeft : segL;
                    float rr = Math.abs(segR - cr) < 1e-3f ? outRight : segR;
                    out.add(new Range(c, r, l, top, rr, bottom));
                    if (i < spans.size()) {
                        segL = spans.get(i)[0];
                        segR = spans.get(i)[1];
                    }
                }
            }
        }
        return out;
    }

    /** 小红点那一点在 u 里的横坐标：G 的右边（= G|H 那条缝 = B 的中线，实体键盘最下一排右错半个键，B 正好骑在 G|H 下面）。 */
    float stickU() {
        Key g = find(0x0A);
        return g.x + g.w;
    }
}
