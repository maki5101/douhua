package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 实体键盘的几何（第 1B 批，2026-11-01），纯 Java（离线测，FixedLayoutTest）：每个键在哪、多宽、发哪个 HID 用法码，单位 u（1u = 一个字母键连缝）。
 * 豆花布局（KeyRows）每行按份数铺满，画不出实体键盘的 L 形回车、两行高的键、几个区块之间的空，所以另起一套，KeyRows 不动。
 *
 * 一块 = 形状（ANSI / ISO / ABNT2）× 尺寸（笔记本 / TKL / 全尺寸）× 模式（Windows / Mac）：形状只管主键区五行（ABNT2 还管小键盘右列），模式只管最下一排、编辑区和几个键名，
 * 尺寸管有没有编辑区、数字小键盘（笔记本另有自己的 F 行和最下一排，见下面）。语言（键帽上的字）不在这里，在 FixedLegends（出字的键 name 是 null，字按用法码查表）。
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
 *
 * 巴西 ABNT2（{@link #ABNT2}，第 2 批，2026-11-01；尺寸照本机 xkb 的 geometry/pc 里的 abnt2，形态调研 kbd-form §2.4）：主键区第 1–3 行、回车同 ISO，
 * 第 4 行左 Shift 1.25 · Z 左边那个键（0x64，巴西是 \ |）· Z–M · , . ; · **「/ ?」键 1u（0x87 International1，和 JIS 的ろ同一个码）** · **右 Shift 只有 1.75u**；
 * 全尺寸的小键盘右列 + 不是两行高，拆成两个 1u：+（0x57）在 7 8 9 那一行、**0x85（Keypad Comma，微软表里写 "Brazilian Keypad ."）在 4 5 6 那一行**，
 * 键帽写清单的 kpco（巴西是 "."），左下的小数点（0x63）写 kpdl（巴西是 ","）。键数比 ISO 多两个（全尺寸 Windows 107，TKL / 笔记本多一个 0x87）。
 * **Mac 模式的 ABNT2 不对调 1 左边和 Z 左边那两个键**（照 PC 惯例 1 左边 0x35、Z 左边 0x64），和 ISO 不一样：苹果不做 ABNT2 键盘，没有"苹果键盘怎么发"可照；
 * CLDR 里 macOS 的 Brazilian-ABNT2 把 ' " 放在 0x35（键码 50）、\ | 放在 0x64（键码 10），不对调那段按 PC 惯例的码查出来正好是 ABNT2 键帽上印的字
 * （1 左边 ' "、Z 左边 \ |），和 Windows 模式一样；对调的话 1 左边会画成 \ |。主机怎么认（macOS 把豆花当哪种键盘）照旧是主机的事，没在真机上验证。
 *
 * 笔记本尺寸（{@link #LAPTOP}，2026-11-01，用户："没有 Insert 那些键的情况下，把方向键也得塞进来，就是有点类似于笔记本键盘那种"）：照 ThinkPad 排，
 * 尺寸从联想 TrackPoint Keyboard II 用户指南的整机插图上量的（方案 design-laptop §2.1；那块外接键盘就是 ThinkPad 笔记本的排法），15u 宽 = 主键区的宽，只有一个区块。
 * - F 行（1 行高，照 TKL）：Esc 1.125 · F1–F12 各 0.8125（四个一组，组间 0.1875）· 隔 0.1875 · Home End Ins 各 0.8125 · Del 1.125。
 *   真机 F 行只有约 0.7 个键高，这里照 TKL 用 1 行高（F 键本来就窄，再矮太难按），第 0–4 行的竖向排法和 TKL 一模一样。
 * - 第 1–4 行：和 TKL 的主键区一模一样（右 Shift 照旧 2.75u，不缩短：真 ThinkPad 的右 Shift 是满的，Z 那一行也挤不下 PgUp ↑ PgDn；
 *   ABNT2 同 TKL 的 ABNT2：右 Shift 1.75u、左边是 / ?）。
 * - 最下一排：一行、1.25 行高（{@link #height} 6.5）。Ctrl ·（⋯ 槽）· Super · Alt · 空格 5u（正好以红点为中线）· AltGr / Alt · PrtSc · Ctrl，
 *   右下角 2.8125u 是方向键，照真机分上下两层（{@link Key#y0} / {@link Key#y1}，各 0.625 行）：上层 PgUp ↑ PgDn、下层 ← ↓ →。
 *   真机下层比整排往下伸出一截，这里改成整排加高到 1.25 行，别的键跟着高、下面不留空着的机身。最下一排是一行，只有方向键竖着分两层：
 *   拆成两行的话 ⋯、🌐 会变成上下两段，跨段抬起就不算点击（TouchRouter.up 的按钮要 contains 或 same(hit)）。
 *   键盘范围：方向键以外一块（Ctrl–右 Ctrl），方向键上下两层各一块（{@link #ranges}：不分开的话两层之间那条缝全分给右 Ctrl）。
 * - 左下角放 Ctrl，第二格是 ⋯ 槽（真机这一格是 Fn；这批没有 Fn 层）：小触控板上摆出了 ⋯ 时左 Ctrl 伸过去占满 2.25u，摆不出时那 1u 是 ⋯ 键（{@link #keys(boolean)}）。
 *   Ctrl 在角上：逐级缩时左手小指的位置和 TKL / 全尺寸一样；拿手机的手最容易碰到角，碰到 Ctrl 不会弹菜单。
 * - Mac 模式位置不变：⌃ ·（⋯）· ⌥ · ⌘ · 空格 · ⌘ · 🌐 · ⌃（PrtSc 那格照 1B 的"Menu 位 → 🌐"；ThinkPad 没有右 Super，Mac 也就没有右 ⌥，左 ⌥ 照样能锁第三层）；
 *   F 行 Ins 那格空着，声明成空格子（{@link #blanks}），不在任何范围里，按了什么都不发生。
 * - 所有坐标都是 1/16u 的整数倍（浮点里是精确值，相邻两个键前一个的 x + w 和后一个的 x 是同一个数，摆到像素后一定共用边界）：
 *   右 Ctrl 和三个方向键各 0.9375，没取真机的 1 + 3 × 0.917。
 * - 键数：Windows 83 / 84 / 85（ANSI / ISO / ABNT2），Mac 82 / 83 / 84。比 TKL 少 ScrLk、Pause、右 Super、Menu（真机靠 Fn 组合键出，第 3 批做 Fn 层时再说）。
 *
 * 中文拼音（清单的 caps_mac，第 2 批）：Mac 模式 Caps 键帽写"中/英"（苹果中文键盘印的就是它），Windows 模式照旧写 Caps；发的码不变（0x39）。
 */
final class FixedLayout {
    static final String ANSI = "ansi", ISO = "iso", ABNT2 = "abnt2";
    /** 能拼的形状（JIS / 韩国第 5 批）。 */
    static final String[] SHAPES = {ANSI, ISO, ABNT2};
    static final String TKL = "tkl", FULL = "full", LAPTOP = "laptop";
    /** 从大到小（逐级缩的顺序，{@link #ladder}）。 */
    static final String[] SIZES_DESC = {FULL, TKL, LAPTOP};
    static final String WIN = "win", MAC = "mac";

    /** 区块：主键区（连 F 行的 Esc–F12）、编辑区（连 F 行右边那三个、方向键）、数字小键盘。 */
    static final int MAIN = 0, EDIT = 1, NUMPAD = 2;

    /**
     * 每行的上沿（u）：第 0 行是 F 行，隔 0.25u 是主键区五行，三种尺寸一样。每行 1u 高，只有笔记本的最下一排 1.25u 高
     * （下沿、总高按这一块取：{@link #rowBottom}、{@link #height}）。
     */
    static final float[] ROW_TOP = {0, 1.25f, 2.25f, 3.25f, 4.25f, 5.25f};
    static final int ROWS = ROW_TOP.length;
    /** 空格那一排（三个鼠标键从它的上沿铺到面板下沿，红点的切口不过它的上沿）。 */
    static final int SPACE_ROW = 5;
    /** 笔记本最下一排多高（u）、方向键上下两层的分界（u，= 94/16）。 */
    static final float LAPTOP_BOTTOM_ROW = 1.25f, LAPTOP_ARROW_SPLIT = 5.875f;
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
        /** 只占这一行竖着的一截（u，笔记本的方向键上下两层）；NaN = 占整行（别的键都是）。 */
        final float y0, y1;

        Key(int id, boolean special, int cluster, int row, int rows, float x, float w, float x2, float w2, String name) {
            this(id, special, cluster, row, rows, x, w, x2, w2, name, Float.NaN, Float.NaN);
        }

        Key(int id, boolean special, int cluster, int row, int rows, float x, float w, float x2, float w2, String name, float y0, float y1) {
            this.y0 = y0;
            this.y1 = y1;
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
    /** 不含 ⋯ 键（⋯ 槽里放不放它看小触控板上摆没摆出 ⋯，FixedPanel.setMenuKey）。键数、{@link #find} 都按这一份。 */
    final List<Key> keys;
    /** ⋯ 放在槽里时显示的键：TKL / 全尺寸是 keys 在 Esc 后面插一个 ⋯；笔记本的左 Ctrl 换成 1.25u 的另一个 Key、紧跟着 ⋯。 */
    private final List<Key> keysMenu;
    /** ⋯ 槽里的那个键（TKL / 全尺寸：Esc 和 F1 之间那 1u；笔记本：左下角 Ctrl 右边那 1u）。 */
    final Key menu;
    /** 总宽、总高（u）：TKL 18.25 / 全尺寸 22.5 / 笔记本 15；笔记本 6.5 高（最下一排 1.25 行），别的 6.25。 */
    final float width, height;
    /**
     * 声明过的空格子（每项 {行, 左, 右}，u）：两段之间的缝里有它就不并成一段（{@link #ranges}），格子不在任何范围里、按了什么都不发生。
     * 现在只有 Mac 模式笔记本 F 行的 Ins 那格（只有 0.8125u，不声明的话会被当成段内小缝并进范围，右半格分给 Del）。
     */
    final List<float[]> blanks;
    /**
     * Windows 模式下右 Alt 是 AltGr（这个布局在 Windows 上有第三层的字，键帽写 AltGr）：锁住它时键帽上第三层的字亮、别的压暗（FixedPanel.layer）。
     * 美式没有第三层，右 Alt 写 Alt，锁住它和锁左 Alt 一样什么都不变（两种 Alt + 字母都打不出字）。Mac 模式一律假（任一 Option 是第三层，不看它）。
     */
    final boolean altGr;

    private FixedLayout(String shape, String size, String mode, List<Key> keys, List<Key> keysMenu, Key menu, float width, float height,
                        boolean altGr, List<float[]> blanks) {
        this.shape = shape;
        this.size = size;
        this.mode = mode;
        this.keys = Collections.unmodifiableList(keys);
        this.keysMenu = Collections.unmodifiableList(keysMenu);
        this.menu = menu;
        this.width = width;
        this.height = height;
        this.altGr = altGr;
        this.blanks = Collections.unmodifiableList(blanks);
    }

    boolean mac() {
        return MAC.equals(mode);
    }

    /** ISO 型（L 形回车、Z 左边多一个键 0x64）：ISO 和 ABNT2 都是。 */
    boolean iso() {
        return ISO.equals(shape) || ABNT2.equals(shape);
    }

    /** 巴西 ABNT2（比 ISO 多 / ? 键 0x87、全尺寸小键盘多 0x85）。 */
    boolean abnt2() {
        return ABNT2.equals(shape);
    }

    boolean full() {
        return FULL.equals(size);
    }

    boolean laptop() {
        return LAPTOP.equals(size);
    }

    /** 几个区块：全尺寸 3（主键区、编辑区、小键盘）、TKL 2、笔记本 1。 */
    int clusterCount() {
        return full() ? 3 : laptop() ? 1 : 2;
    }

    /** 这一块显示的键：menuKey = ⋯ 放在槽里（小触控板上没摆出 ⋯）。顺序就是报区域、画的顺序。 */
    List<Key> keys(boolean menuKey) {
        return menuKey ? keysMenu : keys;
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

    /** 第 r 行的上沿、下沿（u）：最下一排的下沿是总高（笔记本 6.5），别的行 1u 高。 */
    float rowTop(int r) {
        return ROW_TOP[r];
    }

    float rowBottom(int r) {
        return r == ROWS - 1 ? height : ROW_TOP[r] + 1;
    }

    /** 键 k 在第 r 行那一段的上沿、下沿（u）：带竖向子区间的（笔记本的方向键）用它，别的占整行。 */
    float segTop(Key k, int r) {
        return Float.isNaN(k.y0) ? rowTop(r) : k.y0;
    }

    float segBottom(Key k, int r) {
        return Float.isNaN(k.y1) ? rowBottom(r) : k.y1;
    }

    /** 能选的值，认不出的换成默认的（ANSI、TKL、Windows）。 */
    static String validShape(String s) {
        return ISO.equals(s) ? ISO : ABNT2.equals(s) ? ABNT2 : ANSI;
    }

    static String validSize(String s) {
        return FULL.equals(s) ? FULL : LAPTOP.equals(s) ? LAPTOP : TKL;
    }

    /**
     * 逐级缩（2026-11-01，用户："你设定一个最小的键帽的大小……只要小于这个，你就缩那个键盘的阵列。缩完了还不放不进去，你就再缩"）：
     * 从选的尺寸往下的几级，ladder(FULL) = [全尺寸, TKL, 笔记本]，ladder(TKL) = [TKL, 笔记本]，ladder(LAPTOP) = [笔记本]。
     * 选的是起点，只缩不涨（选 TKL 的不会在宽窗口里换成全尺寸）；认不出的当 TKL。哪一级放得下由 KeyboardPlan.fixedLevel 判断。
     */
    static List<String> ladder(String selected) {
        String s = validSize(selected);
        List<String> out = new ArrayList<>();
        boolean on = false;
        for (String z : SIZES_DESC) {
            on |= z.equals(s);
            if (on) out.add(z);
        }
        return out;
    }

    static String validMode(String s) {
        return MAC.equals(s) ? MAC : WIN;
    }

    /**
     * 照字表拼一块（FixedData.ladder 用，逐级缩的每一级各拼一块）：右 Alt 写不写 AltGr 看字表有没有第三层。字表是 null（键帽数据读不出来，或者这个布局这种模式没有那一段）
     * 就不拼、返回 null——KeyboardScreen 拿不到布局就照样显示豆花布局（⋯ 菜单写明），不显示一块出字的键全是空白的实体键盘。
     */
    static FixedLayout forLegends(String shape, String size, String mode, FixedLegends.Table legends, String kpdl) {
        return legends == null ? null : build(shape, size, mode, legends.hasThird(), kpdl);
    }

    /**
     * 同 {@link #forLegends}，小键盘的字、Mac 模式的 Caps 键帽照布局清单的这一行（小数点按模式取 Layout.kpdl(mode)——Windows 模式照微软布局表的 kpdl_win、
     * Mac 模式照 xkb 的 kpdl；kpco、caps_mac；entry 是 null 时照默认的：小数点 "."、Caps）。FixedData.ladder 用这个。
     */
    static FixedLayout forEntry(String shape, String size, String mode, FixedLegends.Table legends, FixedLegends.Layout entry) {
        if (legends == null) return null;
        return entry == null ? build(shape, size, mode, legends.hasThird(), ".")
                : build(shape, size, mode, legends.hasThird(), entry.kpdl(mode), entry.kpco, entry.capsMac);
    }

    /**
     * 拼一块键盘。altGr = 右 Alt 写 AltGr（这个布局在 Windows 上有第三层的字；美式没有，写 Alt），Mac 模式不看；
     * kpdl = 小键盘小数点出的字（布局清单的 kpdl，德语是 ","）。
     */
    static FixedLayout build(String shape, String size, String mode, boolean altGr, String kpdl) {
        return build(shape, size, mode, altGr, kpdl, null, null);
    }

    /**
     * 同上，另给 kpco = ABNT2 小键盘多的那个键（0x85）写的字（清单的 kpco，巴西是 "."；空 / null 写 "."，别的形状没有这个键），
     * capsMac = Mac 模式 Caps 键帽写什么（清单的 caps_mac，中文拼音是"中/英"；空 / null 照旧写 Caps，Windows 模式不看）。
     */
    static FixedLayout build(String shape, String size, String mode, boolean altGr, String kpdl, String kpco, String capsMac) {
        shape = validShape(shape);
        size = validSize(size);
        mode = validMode(mode);
        boolean mac = MAC.equals(mode), abnt2 = ABNT2.equals(shape), iso = ISO.equals(shape) || abnt2;
        boolean full = FULL.equals(size), laptop = LAPTOP.equals(size);
        // 1 左边、Z 左边那两个键照真苹果 ISO 键盘对调：只在 Mac 模式的 ISO（ABNT2 不对调，见类注释）
        boolean appleSwap = mac && iso && !abnt2;
        List<Key> k = new ArrayList<>();
        List<float[]> blanks = new ArrayList<>();

        // ── 第 0 行：F 行 ──
        if (laptop) {
            // 笔记本（照 ThinkPad）：Esc 1.125 · F1–F12 各 0.8125、四个一组、组间 0.1875 · 隔 0.1875 · Home End Ins 各 0.8125 · Del 1.125
            float f = 0.8125f, sep = 0.1875f;
            k.add(key(0x29, MAIN, 0, 0, 1.125f, "Esc"));
            float x = 1.125f;
            for (int i = 0; i < 12; i++) {
                if (i == 4 || i == 8) x += sep;
                k.add(key(0x3A + i, MAIN, 0, x, f, "F" + (i + 1)));
                x += f;
            }
            x += sep;   // 11.4375
            k.add(key(0x4A, MAIN, 0, x, f, "Home"));
            k.add(key(0x4D, MAIN, 0, x + f, f, "End"));
            // Mac 模式没有 Ins：那格空着、声明成空格子（不在任何范围里，见 blanks）
            if (mac) blanks.add(new float[]{0, x + 2 * f, x + 3 * f});
            else k.add(key(0x49, MAIN, 0, x + 2 * f, f, "Ins"));
            k.add(key(0x4C, MAIN, 0, x + 3 * f, MAIN_RIGHT - (x + 3 * f), "Del"));
        } else {
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
        }

        // ── 第 1 行：数字行 ──
        // 1 左边那个键：ANSI 两种模式都是 0x35；ISO 时 Windows 0x35、Mac 照真苹果 ISO 键盘发 0x64（见类注释）；ABNT2 两种模式都是 0x35
        k.add(key(appleSwap ? 0x64 : 0x35, MAIN, 1, 0, 1, null));
        for (int i = 0; i < 10; i++) k.add(key(0x1E + i, MAIN, 1, 1 + i, 1, null));
        k.add(key(0x2D, MAIN, 1, 11, 1, null));
        k.add(key(0x2E, MAIN, 1, 12, 1, null));
        k.add(key(0x2A, MAIN, 1, 13, 2, "⌫"));
        if (!laptop) {   // 编辑区（笔记本没有：Home End Ins Del 在 F 行，PgUp PgDn 在方向键旁边）
            if (!mac) k.add(key(0x49, EDIT, 1, EDIT_LEFT, 1, "Ins"));
            k.add(key(0x4A, EDIT, 1, EDIT_LEFT + 1, 1, "Home"));
            k.add(key(0x4B, EDIT, 1, EDIT_LEFT + 2, 1, "PgUp"));
        }

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
        if (!laptop) {
            k.add(key(0x4C, EDIT, 2, EDIT_LEFT, 1, "Del"));
            k.add(key(0x4D, EDIT, 2, EDIT_LEFT + 1, 1, "End"));
            k.add(key(0x4E, EDIT, 2, EDIT_LEFT + 2, 1, "PgDn"));
        }

        // ── 第 3 行：A 行 ──
        k.add(key(0x39, MAIN, 3, 0, 1.75f, mac && capsMac != null && !capsMac.isEmpty() ? capsMac : "Caps"));
        letters(k, 3, 1.75f, "asdfghjkl");
        k.add(key(0x33, MAIN, 3, 10.75f, 1, null));
        k.add(key(0x34, MAIN, 3, 11.75f, 1, null));
        if (iso) k.add(key(0x31, MAIN, 3, 12.75f, 1, null));   // 回车左边那个键（德语 # '）
        else k.add(key(0x28, MAIN, 3, 12.75f, 2.25f, "↵"));

        // ── 第 4 行：Z 行 ──
        if (iso) {
            k.add(key(0xE1, MAIN, 4, 0, 1.25f, "⇧"));
            k.add(key(appleSwap ? 0x35 : 0x64, MAIN, 4, 1.25f, 1, null));   // Z 左边那个键：Windows 0x64、Mac 0x35（见类注释）；ABNT2 两种模式都是 0x64
        } else {
            k.add(key(0xE1, MAIN, 4, 0, 2.25f, "⇧"));
        }
        letters(k, 4, 2.25f, "zxcvbnm");
        k.add(key(0x36, MAIN, 4, 9.25f, 1, null));
        k.add(key(0x37, MAIN, 4, 10.25f, 1, null));
        k.add(key(0x38, MAIN, 4, 11.25f, 1, null));
        if (abnt2) {
            // ABNT2：右 Shift 左边多一个 / ?（0x87），右 Shift 只剩 1.75u
            k.add(key(0x87, MAIN, 4, 12.25f, 1, null));
            k.add(key(0xE5, MAIN, 4, 13.25f, 1.75f, "⇧"));
        } else {
            k.add(key(0xE5, MAIN, 4, 12.25f, 2.75f, "⇧"));
        }
        if (!laptop) k.add(key(0x52, EDIT, 4, EDIT_LEFT + 1, 1, "↑"));

        // ── 第 5 行：空格那一排 ──
        float m = 1.25f;
        Key narrowCtrl = null;
        if (laptop) {
            // 笔记本（照 ThinkPad）：一行、1.25 行高。Ctrl（⋯ 不在槽里 2.25u，在槽里 1.25u + ⋯ 1u）· Super · Alt · 空格 5u · AltGr / Alt · PrtSc · Ctrl，
            // 右下角是方向键两层（上 PgUp ↑ PgDn、下 ← ↓ →）。Mac 模式位置不变：⌃ ·（⋯）· ⌥ · ⌘ · 空格 · ⌘ · 🌐 · ⌃
            String ctrl = mac ? "⌃" : "Ctrl";
            k.add(key(0xE0, MAIN, 5, 0, 2.25f, ctrl));
            narrowCtrl = key(0xE0, MAIN, 5, 0, m, ctrl);
            if (mac) {
                k.add(key(0xE2, MAIN, 5, 2.25f, 1, "⌥"));
                k.add(key(0xE3, MAIN, 5, 3.25f, 1, "⌘"));
            } else {
                k.add(key(0xE3, MAIN, 5, 2.25f, 1, "Super"));
                k.add(key(0xE2, MAIN, 5, 3.25f, 1, "Alt"));
            }
            k.add(key(0x2C, MAIN, 5, 4.25f, 5, SPACE_NAME));
            if (mac) {
                k.add(key(0xE7, MAIN, 5, 9.25f, 1, "⌘"));
                k.add(new Key(GLOBE_ID, true, MAIN, 5, 1, 10.25f, 1, 0, 0, "🌐"));
                k.add(key(0xE4, MAIN, 5, 11.25f, 0.9375f, "⌃"));
            } else {
                k.add(key(0xE6, MAIN, 5, 9.25f, 1, altGr ? "AltGr" : "Alt"));
                k.add(key(0x46, MAIN, 5, 10.25f, 1, "PrtSc"));
                k.add(key(0xE4, MAIN, 5, 11.25f, 0.9375f, "Ctrl"));
            }
            float ax = 12.1875f, aw = 0.9375f, top = ROW_TOP[5], mid = LAPTOP_ARROW_SPLIT, bottom = ROW_TOP[5] + LAPTOP_BOTTOM_ROW;
            k.add(arrow(0x4B, ax, aw, top, mid, "PgUp"));
            k.add(arrow(0x52, ax + aw, aw, top, mid, "↑"));
            k.add(arrow(0x4E, ax + 2 * aw, aw, top, mid, "PgDn"));
            k.add(arrow(0x50, ax, aw, mid, bottom, "←"));
            k.add(arrow(0x51, ax + aw, aw, mid, bottom, "↓"));
            k.add(arrow(0x4F, ax + 2 * aw, aw, mid, bottom, "→"));
        } else if (mac) {
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
        if (!laptop) {
            k.add(key(0x50, EDIT, 5, EDIT_LEFT, 1, "←"));
            k.add(key(0x51, EDIT, 5, EDIT_LEFT + 1, 1, "↓"));
            k.add(key(0x4F, EDIT, 5, EDIT_LEFT + 2, 1, "→"));
        }

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
            if (abnt2) {
                // ABNT2：右列的 + 只有一行高，下面 4 5 6 那一行是 0x85（巴西写 "."）
                k.add(key(0x57, NUMPAD, 2, p + 3, 1, "+"));
                k.add(key(0x85, NUMPAD, 3, p + 3, 1, kpco == null || kpco.isEmpty() ? "." : kpco));
            } else {
                k.add(new Key(0x57, false, NUMPAD, 2, 2, p + 3, 1, 0, 0, "+"));
            }
            k.add(new Key(0x58, false, NUMPAD, 4, 2, p + 3, 1, 0, 0, "↵"));
            k.add(key(0x62, NUMPAD, 5, p, 2, "0"));
            k.add(key(0x63, NUMPAD, 5, p + 2, 1, kpdl == null || kpdl.isEmpty() ? "." : kpdl));
        }
        // ⋯ 槽：TKL / 全尺寸在 Esc 和 F1 之间（F 行），笔记本在左下角 Ctrl 右边（最下一排、整行高）。
        // ⋯ 在槽里时显示的那份：TKL / 全尺寸在 Esc 后面插 ⋯；笔记本的左 Ctrl 换成 1.25u 的、紧跟着 ⋯
        Key menu = laptop ? new Key(MENU_ID, true, MAIN, 5, 1, m, 1, 0, 0, "⋯") : new Key(MENU_ID, true, MAIN, 0, 1, 1, 1, 0, 0, "⋯");
        List<Key> km = new ArrayList<>(k);
        if (laptop) {
            int c = indexOf(km, 0xE0);
            km.set(c, narrowCtrl);
            km.add(c + 1, menu);
        } else {
            km.add(1, menu);   // 跟在 Esc 后面
        }
        float width = full ? PAD_RIGHT : laptop ? MAIN_RIGHT : EDIT_RIGHT;
        float height = ROW_TOP[ROWS - 1] + (laptop ? LAPTOP_BOTTOM_ROW : 1);
        return new FixedLayout(shape, size, mode, k, km, menu, width, height, !mac && altGr, blanks);
    }

    private static Key key(int id, int cluster, int row, float x, float w, String name) {
        return new Key(id, false, cluster, row, 1, x, w, 0, 0, name);
    }

    /** 笔记本的方向键（PgUp / PgDn 也算）：第 5 行里竖着只占 y0–y1 那一截（上下两层）。 */
    private static Key arrow(int id, float x, float w, float y0, float y1, String name) {
        return new Key(id, false, MAIN, 5, 1, x, w, 0, 0, name, y0, y1);
    }

    /** 列表里用法码是 id 的第一个键的下标，没有 -1。 */
    private static int indexOf(List<Key> ks, int id) {
        for (int i = 0; i < ks.size(); i++) if (ks.get(i).id == id) return i;
        return -1;
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
     * - 一段之内的小缝（F4 和 F5 之间那半格、空着的 ⋯ 槽）在范围里，分给最近的键；声明过的空格子（{@link #blanks}，Mac 模式笔记本 F 行的 Ins 那格）不并，
     *   格子两边各是一段。
     * - 行的上下沿、区块数按这一块取（笔记本最下一排 1.25 行高、只有一个区块）。
     * - **只占一行里竖着一截的键（笔记本的方向键两层）单独报，每层一块**（2026-11-01 审查后改的）：缝里交给 TouchRouter.nearestKey 先比竖向，
     *   和整行高的键（右 Ctrl、空格……）放在同一块里的话，上下两层之间那条缝里整行高的键竖向距离是 0、两层的方向键都大于 0，缝全分给最近的右 Ctrl，
     *   一个都分不到方向键（按在 ↑ / ↓ 交界附近 = 按了 Ctrl；按住方向键 0.4–1 秒松手还会把 Ctrl 锁住）。所以同一行里竖向子区间一样的键
     *   （{@link Key#y0} / {@link Key#y1}）自成一组，照同样的规则分段；组的上下沿伸到和上下相邻那组之间的中线，最上、最下的伸到这一行的上下沿
     *   （笔记本：上层 5.25–5.875、下层 5.875–6.5，横着从和右 Ctrl 共用的那条边 12.1875 到面板边）。整行高的键照旧一组（最下一排其余的键一块，
     *   ⋯、🌐 照旧只有一段）。前提：两种键在一行里不交错（笔记本的方向键挤在右下角，挨着右 Ctrl、中间没有缝），不然两组的范围会重叠——测试查不重叠。
     * menuKey 同 {@link #keys(boolean)}（槽里放没放 ⋯，结果一样：TKL / 全尺寸的槽在 Esc 和 F1 中间，笔记本的在左 Ctrl 右边，本来就在这一段里）。
     */
    List<Range> ranges(boolean menuKey) {
        List<Range> out = new ArrayList<>();
        List<Key> ks = keys(menuKey);
        int clusters = clusterCount();
        for (int c = 0; c < clusters; c++) {
            float cl = clusterLeft(c), cr = clusterRight(c);
            float outLeft = c == 0 ? 0 : (clusterRight(c - 1) + cl) / 2;
            float outRight = c == clusters - 1 ? width : (cr + clusterLeft(c + 1)) / 2;
            for (int r = 0; r < ROWS; r++) {
                float top = r == 0 ? 0 : r == 1 ? (rowBottom(0) + rowTop(1)) / 2 : rowTop(r);
                float bottom = r == 0 ? (rowBottom(0) + rowTop(1)) / 2 : r == ROWS - 1 ? height : rowBottom(r);
                // 整行高的键一组；只占一截的键按 (y0, y1) 分组，组按上沿排
                List<float[]> spans = new ArrayList<>();
                List<float[]> bands = new ArrayList<>();
                List<List<float[]>> bandSpans = new ArrayList<>();
                for (Key k : ks) {
                    if (k.cluster != c) continue;
                    float[] s = k.span(r);
                    if (s == null) continue;
                    if (Float.isNaN(k.y0)) {
                        spans.add(s);
                        continue;
                    }
                    int b = 0;
                    while (b < bands.size() && !(bands.get(b)[0] == k.y0 && bands.get(b)[1] == k.y1)) b++;
                    if (b == bands.size()) {
                        bands.add(new float[]{k.y0, k.y1});
                        bandSpans.add(new ArrayList<>());
                    }
                    bandSpans.get(b).add(s);
                }
                addSegments(out, c, r, spans, top, bottom, cl, cr, outLeft, outRight);
                Integer[] order = new Integer[bands.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Float.compare(bands.get(a)[0], bands.get(b)[0]));
                for (int i = 0; i < order.length; i++) {
                    float[] band = bands.get(order[i]);
                    float bt = i == 0 ? top : (bands.get(order[i - 1])[1] + band[0]) / 2;
                    float bb = i == order.length - 1 ? bottom : (band[1] + bands.get(order[i + 1])[0]) / 2;
                    addSegments(out, c, r, bandSpans.get(order[i]), bt, bb, cl, cr, outLeft, outRight);
                }
            }
        }
        return out;
    }

    /**
     * 一组键（同一区块、同一行、竖着占同一截）的范围：从左到右并成几段（隔着 ≤ {@link #SEGMENT_GAP} 的小缝、中间没有声明过的空格子就并），
     * 每段一块 [top, bottom]；挨着区块边的伸到 outLeft / outRight。
     */
    private void addSegments(List<Range> out, int c, int r, List<float[]> spans, float top, float bottom,
                             float cl, float cr, float outLeft, float outRight) {
        if (spans.isEmpty()) return;
        spans.sort((a, b) -> Float.compare(a[0], b[0]));
        float segL = spans.get(0)[0], segR = spans.get(0)[1];
        for (int i = 1; i <= spans.size(); i++) {
            if (i < spans.size() && spans.get(i)[0] - segR <= SEGMENT_GAP && !blankBetween(r, segR, spans.get(i)[0])) {
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

    /** 第 r 行 [left, right] 这条缝里有没有声明过的空格子。 */
    private boolean blankBetween(int r, float left, float right) {
        for (float[] b : blanks) if ((int) b[0] == r && b[1] >= left - 1e-4f && b[2] <= right + 1e-4f) return true;
        return false;
    }

    /** 小红点那一点在 u 里的横坐标：G 的右边（= G|H 那条缝 = B 的中线，实体键盘最下一排右错半个键，B 正好骑在 G|H 下面）。 */
    float stickU() {
        Key g = find(0x0A);
        return g.x + g.w;
    }
}
