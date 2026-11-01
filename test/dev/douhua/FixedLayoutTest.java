package dev.douhua;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 实体键盘（第 1B 批）的离线测试：FixedLayout（u 里的几何）、FixedPanel（摆到像素、区域、面板自己收触摸、键帽上的字）、
 * KeyboardPlan.planFixed（默认位置、触控板、⋯、窄窗口退回豆花布局、小红点在真 ThinkPad 的位置 + 切口 + 空格那一排的三个鼠标键）。
 * 每个 形状 × 尺寸 × 模式 都跑；交给真正的 TouchRouter（按面板报区域的顺序）和 KeyFingers 逐点核对（照 KeyRowsTest / KeyboardPlanTest 的做法）。
 * 尺寸按 1dp = 1px 算。从仓库根目录跑（读 res/raw 的键帽数据）。
 */
public final class FixedLayoutTest {
    private static int failures;
    private static int checks;

    private static final int GAP = 2;
    /** 同 KeyboardScreen 的 baseSizes（1dp = 1px）。 */
    private static final KeyboardPlan.Sizes T = new KeyboardPlan.Sizes(8, 96, 32, 64, 32, 3, 10, 44, 48, 4, 56, GAP, 20);
    private static final String[] SHAPES = {FixedLayout.ANSI, FixedLayout.ISO};
    private static final String[] SIZES = {FixedLayout.TKL, FixedLayout.FULL};
    private static final String[] MODES = {FixedLayout.WIN, FixedLayout.MAC};

    private static FixedLegends.TableFile win, mac;
    private static List<FixedLegends.Layout> catalog;

    public static void main(String[] args) throws IOException {
        win = FixedLegends.parseTables(FixedLegendsTest.read("res/raw/kbd_win.txt"));
        mac = FixedLegends.parseTables(FixedLegendsTest.read("res/raw/kbd_mac.txt"));
        catalog = FixedLegends.parseCatalog(FixedLegendsTest.read("res/raw/kbd_catalog.txt"));
        counts();
        rowsFill();
        noOverlap();
        isoEnter();
        bottomRows();
        rangesU();
        placement();
        routing();
        pins();
        caps();
        glyphs();
        functionKeys();
        fits();
        planDefaults();
        defaultsKeepDragLimits();
        stickPlan();
        stickRouting();
        System.err.println("实体键盘几何 / 分流：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static FixedLayout layout(String shape, String size, String mode) {
        return FixedLayout.build(shape, size, mode, true, ".");
    }

    /** 这一块里某个用法码的键写的名字（没有这个键是 ""）。 */
    static String keyName(FixedLayout l, int id) {
        for (FixedLayout.Key k : l.keys) if (k.id == id) return String.valueOf(k.name);
        return "";
    }

    static String name(FixedLayout l) {
        return l.shape + "/" + l.size + "/" + l.mode;
    }

    static FixedLegends.Table table(String lang, String mode) {
        return (FixedLayout.MAC.equals(mode) ? mac : win).sections.get(lang);
    }

    // ── 键数、用法码各一次、0x64 只在 ISO ──
    static void counts() {
        int[][] want = {   // [模式][形状 * 2 + 尺寸]：ANSI TKL、ANSI 全尺寸、ISO TKL、ISO 全尺寸
                {87, 104, 88, 105},   // Windows：TKL 87 / 88、全尺寸 104 / 105
                {86, 103, 87, 104},   // Mac：没有 Ins（那格空着）
        };
        for (int m = 0; m < 2; m++) {
            for (int sh = 0; sh < 2; sh++) {
                for (int sz = 0; sz < 2; sz++) {
                    FixedLayout l = layout(SHAPES[sh], SIZES[sz], MODES[m]);
                    check(l.keys.size() == want[m][sh * 2 + sz], name(l) + " 键数 " + l.keys.size() + "（应为 " + want[m][sh * 2 + sz] + "）");
                    Set<Integer> ids = new HashSet<>();
                    boolean dup = false;
                    for (FixedLayout.Key k : l.keys(true)) dup |= !ids.add(k.id);
                    check(!dup, name(l) + " 每个用法码 / 按钮编号只出现一次（含 ⋯）");
                    check(ids.contains(0x64) == l.iso(), name(l) + " 有 0x64 当且仅当 ISO");
                    check(!ids.contains(0x32), name(l) + " 不发 0x32（回车左边那个键发 0x31）");
                    check(ids.contains(0x31), name(l) + " 有 0x31");
                    check(ids.contains(0x49) != l.mac(), name(l) + " Ins 只在 Windows 模式");
                    check(ids.contains(0x46) != l.mac() && ids.contains(0x68) == l.mac(), name(l) + " PrtSc（Windows）/ F13（Mac）");
                    check(ids.contains(0x65) != l.mac() && ids.contains(FixedLayout.GLOBE_ID) == l.mac(), name(l) + " Menu（Windows）/ 🌐（Mac）");
                    check(ids.contains(0x53) == l.full(), name(l) + " 小键盘只在全尺寸");
                    for (FixedLayout.Key k : l.keys(true)) {
                        check(k.special ? (k.id == FixedLayout.MENU_ID || k.id == FixedLayout.GLOBE_ID) : (k.id >= 0x04 && k.id <= 0xE7),
                                name(l) + " " + k + " 的编号在范围里");
                    }
                }
            }
        }
    }

    /** 第 row 行、cluster 区块里的各段（u），按 x 排好。 */
    static List<float[]> spans(FixedLayout l, int row, int cluster, boolean menu) {
        List<float[]> out = new ArrayList<>();
        for (FixedLayout.Key k : l.keys(menu)) {
            if (cluster >= 0 && k.cluster != cluster) continue;
            float[] s = k.span(row);
            if (s != null) out.add(s);
        }
        out.sort((a, b) -> Float.compare(a[0], b[0]));
        return out;
    }

    // ── 主键区每行凑满 15u、首尾相接 ──
    static void rowsFill() {
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) {
            FixedLayout l = layout(sh, sz, m);
            for (int r = 1; r < FixedLayout.ROWS; r++) {
                List<float[]> s = spans(l, r, FixedLayout.MAIN, false);
                boolean ok = !s.isEmpty() && near(s.get(0)[0], 0) && near(s.get(s.size() - 1)[1], FixedLayout.MAIN_RIGHT);
                for (int i = 1; i < s.size(); i++) ok &= near(s.get(i)[0], s.get(i - 1)[1]);
                check(ok, name(l) + " 主键区第 " + r + " 行从 0 铺到 15u、首尾相接");
            }
            check(near(l.width, l.full() ? 22.5f : 18.25f), name(l) + " 总宽");
            // F 行：Esc、空 1u（⋯ 槽）、F1–F4、空半格、F5–F8、空半格、F9–F12
            List<float[]> f = spans(l, 0, FixedLayout.MAIN, false);
            check(f.size() == 13 && near(f.get(0)[1], 1) && near(f.get(1)[0], 2) && near(f.get(5)[0], 6.5f) && near(f.get(9)[0], 11)
                    && near(f.get(12)[1], 15), name(l) + " F 行的位置");
            check(near(l.menu.x, 1) && l.menu.row == 0 && near(l.menu.w, 1), name(l) + " ⋯ 槽在 Esc 和 F1 之间");
        }
    }

    // ── 键不重叠、都在外框里（含 ⋯） ──
    static void noOverlap() {
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) {
            FixedLayout l = layout(sh, sz, m);
            for (int r = 0; r < FixedLayout.ROWS; r++) {
                List<float[]> s = spans(l, r, -1, true);
                boolean ok = true;
                for (int i = 0; i < s.size(); i++) {
                    ok &= s.get(i)[0] >= -1e-4f && s.get(i)[1] <= l.width + 1e-4f && s.get(i)[1] > s.get(i)[0];
                    if (i > 0) ok &= s.get(i)[0] >= s.get(i - 1)[1] - 1e-4f;
                }
                check(ok, name(l) + " 第 " + r + " 行键不重叠、在外框里");
            }
            for (FixedLayout.Key k : l.keys(true)) {
                check(k.row >= 0 && k.row + Math.max(k.rows, k.lShape() ? 2 : 1) <= FixedLayout.ROWS, name(l) + " " + k + " 的行在范围里");
                check(k.cluster == FixedLayout.MAIN ? k.x + k.w <= FixedLayout.MAIN_RIGHT + 1e-4f
                        : k.cluster == FixedLayout.EDIT ? k.x >= FixedLayout.EDIT_LEFT - 1e-4f && k.x + k.w <= FixedLayout.EDIT_RIGHT + 1e-4f
                        : k.x >= FixedLayout.PAD_LEFT - 1e-4f, name(l) + " " + k + " 在自己的区块里");
            }
        }
    }

    // ── ISO 回车两段拼成 L 形、和左边的 0x31 不重叠 ──
    static void isoEnter() {
        for (String m : MODES) {
            FixedLayout l = layout(FixedLayout.ISO, FixedLayout.TKL, m);
            FixedLayout.Key e = l.find(0x28), k31 = l.find(0x31);
            check(e != null && e.lShape() && e.row == 2, name(l) + " 回车是 L 形、从 Q 那一行起");
            if (e == null || k31 == null) continue;
            float[] top = e.span(2), low = e.span(3);
            check(near(top[0], 13.5f) && near(top[1], 15) && near(low[0], 13.75f) && near(low[1], 15), name(l) + " 回车上半 1.5u、下半 1.25u、右边对齐");
            check(k31.row == 3 && near(k31.x + k31.w, low[0]), name(l) + " 回车左边紧挨着 0x31（德语 # '）");
            FixedLayout a = layout(FixedLayout.ANSI, FixedLayout.TKL, m);
            check(!a.find(0x28).lShape() && a.find(0x31).row == 2, name(a) + " ANSI 回车是一字形、\\ 在 Q 那一行");
        }
    }

    // ── 最下一排的顺序 ──
    static void bottomRows() {
        int[] pc = {0xE0, 0xE3, 0xE2, 0x2C, 0xE6, 0xE7, 0x65, 0xE4};
        int[] macRow = {0xE0, 0xE2, 0xE3, 0x2C, 0xE7, 0xE6, FixedLayout.GLOBE_ID, 0xE4};
        for (String sh : SHAPES) {
            FixedLayout w = layout(sh, FixedLayout.TKL, FixedLayout.WIN), m = layout(sh, FixedLayout.TKL, FixedLayout.MAC);
            check(Arrays.equals(rowIds(w, 5), pc), name(w) + " 底行 Ctrl Super Alt 空格 AltGr Super ☰ Ctrl：" + Arrays.toString(rowIds(w, 5)));
            check(Arrays.equals(rowIds(m, 5), macRow), name(m) + " 底行 ⌃ ⌥ ⌘ 空格 ⌘ ⌥ 🌐 ⌃（Option 发 Alt、Command 发 GUI）");
            check("⌘".equals(m.find(0xE3).name) && "⌥".equals(m.find(0xE2).name) && "⌃".equals(m.find(0xE0).name), name(m) + " Mac 的键名");
            check("Super".equals(w.find(0xE3).name) && "AltGr".equals(w.find(0xE6).name), name(w) + " PC 的键名（GUI 叫 Super）");
        }
        FixedLayout us = FixedLayout.build(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN, false, ".");
        check("Alt".equals(us.find(0xE6).name), "没有第三层的布局右 Alt 写 Alt");
        FixedLayout de = FixedLayout.build(FixedLayout.ISO, FixedLayout.FULL, FixedLayout.WIN, true, ",");
        check(",".equals(de.find(0x63).name), "德语小键盘小数点写逗号");
        check("Clear".equals(layout(FixedLayout.ANSI, FixedLayout.FULL, FixedLayout.MAC).find(0x53).name), "Mac 模式小键盘 Num 写 Clear");
    }

    static int[] rowIds(FixedLayout l, int row) {
        List<FixedLayout.Key> ks = new ArrayList<>();
        for (FixedLayout.Key k : l.keys) if (k.cluster == FixedLayout.MAIN && k.span(row) != null) ks.add(k);
        ks.sort((a, b) -> Float.compare(a.span(row)[0], b.span(row)[0]));
        int[] out = new int[ks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ks.get(i).id;
        return out;
    }

    // ── 键盘范围（u）：每区块每行一块、不重叠、每段恰好在一块里、没有键的格子不在任何一块里 ──
    static void rangesU() {
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) for (boolean menu : new boolean[]{false, true}) {
            FixedLayout l = layout(sh, sz, m);
            List<FixedLayout.Range> rs = l.ranges(menu);
            boolean ok = true;
            for (int i = 0; i < rs.size(); i++) {
                for (int j = i + 1; j < rs.size(); j++) ok &= !overlap(rs.get(i), rs.get(j));
            }
            check(ok, name(l) + (menu ? "（带 ⋯）" : "") + " 范围之间不重叠");
            for (FixedLayout.Key k : l.keys(menu)) {
                for (int r = 0; r < FixedLayout.ROWS; r++) {
                    float[] s = k.span(r);
                    if (s == null) continue;
                    int in = 0;
                    boolean sameRow = true;
                    for (FixedLayout.Range g : rs) {
                        if (s[0] >= g.left - 1e-4f && s[1] <= g.right + 1e-4f && FixedLayout.rowTop(r) >= g.top - 1e-4f
                                && FixedLayout.rowBottom(r) <= g.bottom + 1e-4f) {
                            in++;
                            sameRow &= g.row == r && g.cluster == k.cluster;
                        }
                    }
                    check(in == 1 && sameRow, name(l) + " " + k + " 第 " + r + " 行那段恰好在一块同行同区块的范围里（" + in + "）");
                }
            }
            check(!inAny(rs, 16.75f, 3.75f), name(l) + " ↑ 正上方那格（编辑区 A 那一行）不在任何范围里");
            check(!inAny(rs, 15.75f, 4.75f) && !inAny(rs, 17.75f, 4.75f), name(l) + " ↑ 两边不在任何范围里");
            check(inAny(rs, 15.75f, 1.75f) != l.mac(), name(l) + " Ins 那格：Windows 有键在范围里、Mac 空着不在");
            check(inAny(rs, 1.5f, 0.5f), name(l) + " ⋯ 槽在 F 行那一段里（空着时分给最近的 F 键）");
            check(inAny(rs, 0.01f, 3.5f) && inAny(rs, l.width - 0.01f, 5.9f), name(l) + " 最外圈伸到面板边");
            check(inAny(rs, 15.125f, 2.75f) || l.mac(), name(l) + " 主键区和编辑区之间的空隙分到两边的中线");
        }
    }

    static boolean inAny(List<FixedLayout.Range> rs, float x, float y) {
        for (FixedLayout.Range g : rs) if (x >= g.left && x < g.right && y >= g.top && y < g.bottom) return true;
        return false;
    }

    static boolean overlap(FixedLayout.Range a, FixedLayout.Range b) {
        return Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1e-4f && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1e-4f;
    }

    private static final int[][] SIZES_PX = {{751, 283}, {933, 352}, {1800, 700}, {600, 230}, {1000, 300}, {933, 704}};

    // ── 摆到像素：段不重叠、在面板里，相邻键共用边界，范围不重叠、每段恰好在一块里 ──
    static void placement() {
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) for (int[] wh : SIZES_PX) {
            FixedLayout l = layout(sh, sz, m);
            FixedPanel p = new FixedPanel();
            p.setBoard(l, table("us", m));
            p.setMenuKey(true);
            p.place(wh[0], wh[1], GAP, 10);
            String what = name(l) + " " + wh[0] + "×" + wh[1];
            List<FixedPanel.Seg> segs = p.segs();
            boolean inside = true, apart = true;
            for (int i = 0; i < segs.size(); i++) {
                FixedPanel.Seg a = segs.get(i);
                inside &= a.left >= 0 && a.top >= 0 && a.right <= wh[0] && a.bottom <= wh[1] && a.right > a.left && a.bottom > a.top;
                for (int j = i + 1; j < segs.size(); j++) {
                    FixedPanel.Seg b = segs.get(j);
                    apart &= Math.min(a.right, b.right) <= Math.max(a.left, b.left) || Math.min(a.bottom, b.bottom) <= Math.max(a.top, b.top);
                }
            }
            check(inside, what + " 每段在面板里、不是空的");
            check(apart, what + " 段不重叠");
            // 同一行首尾相接的两个键之间正好隔 2 × gap
            boolean shared = true;
            for (FixedPanel.Seg a : segs) for (FixedPanel.Seg b : segs) {
                if (a.top == b.top && a.bottom == b.bottom && b.left > a.right && b.left - a.right < 3 * GAP) shared &= b.left - a.right == 2 * GAP;
            }
            check(shared, what + " 相邻的键共用边界（缝处处 2 × gap）");
            List<int[]> rs = p.ranges();
            boolean rOk = true;
            for (int i = 0; i < rs.size(); i++) for (int j = i + 1; j < rs.size(); j++) {
                int[] a = rs.get(i), b = rs.get(j);
                rOk &= Math.min(a[2], b[2]) <= Math.max(a[0], b[0]) || Math.min(a[3], b[3]) <= Math.max(a[1], b[1]);
            }
            check(rOk, what + " 范围不重叠");
            boolean each = true;
            for (FixedPanel.Seg s : segs) {
                int in = 0;
                for (int[] r : rs) if (s.left >= r[0] && s.right <= r[2] && s.top >= r[1] && s.bottom <= r[3]) in++;
                each &= in == 1;
            }
            check(each, what + " 每段恰好在一块范围里（是那块范围里 nearestKey 的候选）");
        }
    }

    /** 面板报的区域（id 用键的下标，方便和 keyAt 比）：段在前、范围在后，同 FixedPanel.regions 的顺序。 */
    static TouchRouter.Region[] regions(FixedPanel p) {
        List<TouchRouter.Region> out = new ArrayList<>();
        for (FixedPanel.Seg s : p.segs()) {
            out.add(new TouchRouter.Region(p.keys().get(s.key).special ? Proto.REGION_BUTTON : Proto.REGION_KEY, s.key, s.left, s.top, s.right, s.bottom));
        }
        for (int[] r : p.ranges()) out.add(new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, r[0], r[1], r[2], r[3]));
        return out.toArray(new TouchRouter.Region[0]);
    }

    /** 按真正的 TouchRouter 分流：第几个键，没有 -1。 */
    static int route(TouchRouter router, float x, float y) {
        TouchRouter.Region r = router.hit(x, y);
        return r == null ? -1 : r.id;
    }

    // ── 分流（TouchRouter）和面板自己收触摸（KeyFingers）逐点一致；按在键上是那个键、缝里是同一行这一段里最近的、空格子里什么都没有 ──
    static void routing() {
        int points = 0, inKeys = 0, inGaps = 0, nothing = 0, bad = 0;
        String first = null;
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) for (int[] wh : new int[][]{{933, 352}, {751, 283}}) {
            FixedLayout l = layout(sh, sz, m);
            for (boolean menu : new boolean[]{false, true}) {
                FixedPanel p = new FixedPanel();
                p.setBoard(l, table("us", m));
                p.setMenuKey(menu);
                p.place(wh[0], wh[1], GAP, 10);
                TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
                router.setRegions(regions(p));
                for (int y = 0; y < wh[1]; y += 2) {
                    for (int x = 0; x < wh[0]; x += 2) {
                        points++;
                        int a = route(router, x, y), b = p.keyAt(x, y);
                        String why = null;
                        if (a != b) why = "分流 " + a + "、面板自己收 " + b;
                        FixedPanel.Seg on = null;
                        for (FixedPanel.Seg s : p.segs()) if (x >= s.left && x < s.right && y >= s.top && y < s.bottom) on = s;
                        if (on != null) {
                            inKeys++;
                            if (why == null && a != on.key) why = "按在 " + p.keys().get(on.key) + " 上却是 " + a;
                        } else if (a >= 0) {
                            inGaps++;
                            // 缝里分到的键和这一点在同一行（这一点所在的范围里）
                            int[] range = null;
                            for (int[] r : p.ranges()) if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) range = r;
                            boolean ok = false;
                            for (FixedPanel.Seg s : p.segs()) {
                                if (s.key == a && range != null && s.left >= range[0] && s.right <= range[2] && s.top >= range[1] && s.bottom <= range[3]) ok = true;
                            }
                            if (why == null && !ok) why = "缝里分给了别的行 / 区块的键 " + p.keys().get(a);
                        } else {
                            nothing++;
                        }
                        if (why != null) {
                            bad++;
                            if (first == null) first = name(l) + " " + wh[0] + "×" + wh[1] + " (" + x + ", " + y + ")：" + why;
                        }
                    }
                }
            }
        }
        check(bad == 0, "实体键盘分流扫了 " + points + " 个点（键上 " + inKeys + "、缝里 " + inGaps + "、空格子 " + nothing + "），"
                + bad + " 个不对" + (first == null ? "" : "，比如 " + first));
        check(inKeys > 0 && inGaps > 0 && nothing > 0, "扫到了键上、缝里、空格子里的点");
        System.err.println("  实体键盘分流扫描：" + points + " 个点（键上 " + inKeys + "、缝里 " + inGaps + "、空格子 " + nothing + "）");
    }

    static int ux(FixedLayout l, float u, int w) {
        return FixedPanel.px(u, w, l.width);
    }

    static int uy(float u, int h) {
        return FixedPanel.px(u, h, FixedLayout.HEIGHT);
    }

    /** 第 i 个键的 id（-1 → -1）。 */
    static int id(FixedPanel p, int i) {
        return i < 0 ? -1 : p.keys().get(i).id;
    }

    // ── 钉死几个点 ──
    static void pins() {
        int w = 933, h = 352;
        for (String m : MODES) {
            FixedLayout l = layout(FixedLayout.ANSI, FixedLayout.TKL, m);
            FixedPanel p = new FixedPanel();
            p.setBoard(l, table("us", m));
            p.place(w, h, GAP, 10);
            TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
            router.setRegions(regions(p));
            int ay = (uy(3.25f, h) + uy(4.25f, h)) / 2;
            int above = route(router, ux(l, 16.75f, w), ay);
            check(above == -1, name(l) + " TKL 里 ↑ 正上方一格什么都没有（不是回车 / '）：" + id(p, above));
            int nearUp = route(router, ux(l, 16.75f, w), uy(4.25f, h) + 1);
            check(id(p, nearUp) == 0x52, name(l) + " ↑ 上面那条缝（在 ↑ 那一行里）给 ↑");
            int f45 = id(p, route(router, ux(l, 6.25f, w), uy(0.5f, h)));
            check(f45 == 0x3D || f45 == 0x3E, name(l) + " F4 和 F5 之间那半格给 F4 或 F5：" + Integer.toHexString(f45));
            int slot = id(p, route(router, ux(l, 1.5f, w), uy(0.5f, h)));
            check(slot == 0x29 || slot == 0x3A, name(l) + " 空着的 ⋯ 槽给 Esc 或 F1：" + Integer.toHexString(slot));
            p.setMenuKey(true);
            p.place(w, h, GAP, 10);
            router.setRegions(regions(p));
            check(id(p, route(router, ux(l, 1.5f, w), uy(0.5f, h))) == FixedLayout.MENU_ID, name(l) + " 槽里放了 ⋯ 就是 ⋯");
            int[] mk = p.menuKeyRect();
            check(mk != null && mk[0] > ux(l, 1, w) && mk[2] < ux(l, 2, w), name(l) + " ⋯ 键的位置");
        }
        for (String m : MODES) {
            FixedLayout l = layout(FixedLayout.ISO, FixedLayout.TKL, m);
            FixedPanel p = new FixedPanel();
            p.setBoard(l, table("de", m));
            p.place(w, h, GAP, 10);
            TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
            router.setRegions(regions(p));
            int b = ux(l, 13.75f, w), y3 = (uy(3.25f, h) + uy(4.25f, h)) / 2;
            check(id(p, route(router, b - 1, y3)) == 0x31, name(l) + " 回车下半段左边的缝：靠左给 0x31（#）");
            check(id(p, route(router, b + 1, y3)) == 0x28, name(l) + " 回车下半段左边的缝：靠右给回车");
            check(id(p, route(router, ux(l, 14.5f, w), uy(3.25f, h))) == 0x28, name(l) + " L 形回车里面两行之间那条缝给回车");
            check(id(p, route(router, ux(l, 13.6f, w), y3)) == 0x31, name(l) + " 回车上半段正下方、0x31 上面一点给 0x31");
        }
    }

    // ── 会显示的键都有键帽（不变量"键盘上会显示的每个键都有键帽"，实体键盘同样适用） ──
    static void caps() {
        int combos = 0;
        int[] mods = {0, 0x02, 0x20, 0x40, 0x04, 0x42, 0x44, 0x22};
        for (FixedLegends.Layout lang : catalog) {
            for (String sh : lang.shapes) for (String sz : SIZES) for (String m : MODES) for (boolean menu : new boolean[]{false, true}) {
                FixedLegends.Table t = table(lang.id, m);
                FixedLayout l = FixedLayout.build(sh, sz, m, t.hasThird(), lang.kpdl);
                for (int[] wh : new int[][]{{933, 352}, {600, 230}}) {
                    for (boolean holed : new boolean[]{false, true}) {
                        FixedPanel p = new FixedPanel();
                        p.setBoard(l, t);
                        p.setMenuKey(menu);
                        if (holed) p.setHole(FixedPanel.stickHole(l, wh[0], T));
                        p.place(wh[0], wh[1], GAP, 10);
                        combos++;
                        String what = lang.id + " " + name(l) + (menu ? " ⋯" : "") + (holed ? " 让位" : "") + " " + wh[0] + "×" + wh[1];
                        boolean ok = true;
                        String bad = null;
                        for (int i = 0; i < p.keys().size(); i++) {
                            FixedLayout.Key k = p.keys().get(i);
                            if (k.charKey() && !t.has(k.id)) {
                                ok = false;
                                bad = k + " 字表里没有";
                            }
                            int[] b = p.box(i);
                            if (b[2] <= b[0] || b[3] <= b[1] || p.capBoxes(i).isEmpty()) {
                                ok = false;
                                bad = k + " 没有键帽";
                            }
                            for (int md : mods) {
                                List<FixedPanel.Glyph> gs = p.glyphs(i, md, "Space");
                                boolean any = false;
                                for (FixedPanel.Glyph g : gs) {
                                    if (g.text == null || g.text.isEmpty()) bad = k + " 有空的字";
                                    if (g.slot != FixedPanel.Glyph.CORNER) any = true;
                                }
                                if (!any) {
                                    ok = false;
                                    bad = k + " 锁着 " + Integer.toHexString(md) + " 时键帽上没有字";
                                }
                            }
                        }
                        check(ok && bad == null, what + " 每个键都有键帽和字" + (bad == null ? "" : "：" + bad));
                    }
                }
            }
        }
        System.err.println("  键帽齐全：" + combos + " 种（布局 × 形状 × 尺寸 × 模式 × ⋯ × 让位 × 窗口）");
    }

    static FixedPanel panel(String lang, String shape, String size, String mode) {
        FixedLegends.Table t = table(lang, mode);
        FixedPanel p = new FixedPanel();
        p.setBoard(FixedLayout.build(shape, size, mode, t.hasThird(), FixedLegends.find(catalog, lang).kpdl), t);
        p.place(933, 352, GAP, 10);
        return p;
    }

    static String glyphs(FixedPanel p, int id, int mods) {
        StringBuilder sb = new StringBuilder();
        for (FixedPanel.Glyph g : p.glyphs(p.index(id), mods, "空格")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(new String[]{"中", "上", "下", "角"}[g.slot]).append(g.text);
            if (g.dead) sb.append("（死）");
            if (g.dim) sb.append("（暗）");
        }
        return sb.toString();
    }

    // ── 键帽上画哪几个字 ──
    static void glyphs() {
        FixedPanel de = panel("de", FixedLayout.ISO, FixedLayout.TKL, FixedLayout.WIN);
        eqs(glyphs(de, 0x1F, 0), "上\" 下2 角²", "win:de 2：上 Shift、下普通、角上 AltGr");
        eqs(glyphs(de, 0x1F, 0x02), "上\" 下2（暗） 角²（暗）", "锁着 Shift：上半亮、别的暗");
        eqs(glyphs(de, 0x1F, 0x40), "上\"（暗） 下2（暗） 角²", "锁着 AltGr（右 Alt）：角上亮");
        eqs(glyphs(de, 0x1F, 0x04), "上\" 下2 角²", "Windows 模式左 Alt 不是 AltGr");
        eqs(glyphs(de, 0x14, 0), "中q 角@", "win:de q：一个字母 + 角上 @");
        eqs(glyphs(de, 0x14, 0x20), "中Q 角@（暗）", "锁着右 Shift：大写");
        eqs(glyphs(de, 0x35, 0), "上° 下^（死）", "win:de 左上角：死键 ^");
        eqs(glyphs(de, 0x2D, 0x42), "上?（暗） 下ß（暗） 角ẞ", "AltGr + Shift：角上换成第四层 ẞ");
        eqs(glyphs(de, 0xE6, 0), "中AltGr", "win:de 右 Alt 写 AltGr");
        eqs(glyphs(de, 0x2C, 0), "中空格", "空格写界面语言的名字");
        FixedPanel md = panel("de", FixedLayout.ISO, FixedLayout.TKL, FixedLayout.MAC);
        eqs(glyphs(md, 0x0F, 0), "中l 角@", "mac:de l：角上 Option 层的 @");
        eqs(glyphs(md, 0x0F, 0x04), "中l（暗） 角@", "Mac 左 Option 也是第三层");
        eqs(glyphs(md, 0x0F, 0x40), "中l（暗） 角@", "Mac 右 Option 也是第三层");
        eqs(glyphs(md, 0x64, 0), "上° 下^（死） 角„", "mac:de 左上角（发 0x64）：死键 ^，Option 层 „");
        eqs(glyphs(md, 0x30, 0), "上* 下+ 角±", "mac:de + 键（Option+Shift 的苹果标不画）");
        FixedPanel us = panel("us", FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN);
        eqs(glyphs(us, 0xE6, 0), "中Alt", "win:us 右 Alt 写 Alt");
        // win:us 没有第三层，右 Alt 写 Alt：锁住它和锁左 Alt 一样不换层（以前整块键盘的字全压暗，2026-11-01 审查发现）
        eqs(glyphs(us, 0x04, 0x40), "中a", "win:us 锁右 Alt：字母不压暗（和左 Alt 一样）");
        eqs(glyphs(us, 0x04, 0x04), "中a", "win:us 锁左 Alt：字母不压暗");
        eqs(glyphs(us, 0x1F, 0x40), "上@ 下2", "win:us 锁右 Alt：数字键两层都不压暗");
        eqs(glyphs(us, 0x04, 0x60), "中A", "win:us 锁右 Alt + 右 Shift：照 Shift 出大写");
        eqs(us.preview(us.index(0x04), 0x40), "a", "win:us 锁右 Alt 时放大提示照普通层");
        FixedLayout usWin = us.layout(), deWin = de.layout(), deMac = md.layout();
        check(!usWin.altGr && deWin.altGr && !deMac.altGr, "altGr：win:de 真、win:us 假、Mac 模式一律假");
        check(FixedPanel.layer(0x40, usWin) == FixedLegends.NORMAL && FixedPanel.layer(0x40, deWin) == FixedLegends.THIRD
                        && FixedPanel.layer(0x04, deWin) == FixedLegends.NORMAL && FixedPanel.layer(0x04, deMac) == FixedLegends.THIRD
                        && FixedPanel.layer(0x40, deMac) == FixedLegends.THIRD && FixedPanel.layer(0x42, deWin) == FixedLegends.THIRD_SHIFT
                        && FixedPanel.layer(0x42, usWin) == FixedLegends.SHIFT && FixedPanel.layer(0x40, null) == FixedLegends.NORMAL,
                "layer：第三层只认 Mac 的任一 Option、Windows 有 AltGr 的布局的右 Alt");
        FixedLayout built = FixedLayout.build(FixedLayout.ISO, FixedLayout.TKL, FixedLayout.MAC, true, ".");
        check(!built.altGr, "Mac 模式拼的时候给了 altGr 也记成假");
        // 放大提示：照锁着的修饰键会出的字
        eqs(de.preview(de.index(0x14), 0x40), "@", "放大提示：AltGr+Q = @");
        eqs(de.preview(de.index(0x14), 0x02), "Q", "放大提示：Shift+Q = Q");
        eqs(de.preview(de.index(0x14), 0), "q", "放大提示：q");
        eqs(de.preview(de.index(0x35), 0), "^", "放大提示：死键画附加符号");
        check(de.preview(de.index(0x29), 0) == null && de.preview(de.index(0x52), 0) == null && de.preview(de.index(0xE1), 0) == null,
                "Esc、方向键、修饰键不放大");
        eqs(md.preview(md.index(0x0F), 0x04), "@", "放大提示：Mac ⌥L = @");
        check(FixedPanel.active(de.keys().get(de.index(0xE6)), 0x40) && FixedPanel.active(de.keys().get(de.index(0xE4)), 0x10)
                && !FixedPanel.active(de.keys().get(de.index(0xE4)), 0x01), "右边四个修饰键锁住时也高亮");
    }

    static void functionKeys() {
        FixedPanel p = panel("de", FixedLayout.ISO, FixedLayout.FULL, FixedLayout.WIN);
        for (int u : new int[]{0x04, 0x1E, 0x2C, 0x35, 0x64, 0x31, 0x59, 0x62, 0x63}) {
            check(!p.functionKey(p.index(u)), "普通键帽：" + Integer.toHexString(u));
        }
        for (int u : new int[]{0x29, 0x3A, 0x2A, 0x28, 0xE1, 0xE6, 0x53, 0x57, 0x58, 0x52, 0x65}) {
            check(p.functionKey(p.index(u)), "功能键帽：" + Integer.toHexString(u));
        }
        FixedPanel mp = panel("us", FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.MAC);
        check(mp.functionKey(mp.index(FixedLayout.GLOBE_ID)), "🌐 是功能键");
    }

    // ── 窄窗口退回豆花布局 ──
    static void fits() {
        FixedLayout tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN), full = layout(FixedLayout.ANSI, FixedLayout.FULL, FixedLayout.WIN);
        check(KeyboardPlan.fixedFits(tkl, 584, T) && !KeyboardPlan.fixedFits(tkl, 583, T), "TKL 一格 32 起（584 / 18.25 = 32）");
        check(KeyboardPlan.fixedFits(full, 720, T) && !KeyboardPlan.fixedFits(full, 719, T), "全尺寸一格 32 起（720 / 22.5 = 32）");
        check(!KeyboardPlan.fixedFits(tkl, 411, T) && !KeyboardPlan.fixedFits(tkl, 475, T), "手机竖屏、Fold 外屏竖屏放不下 TKL：暂用豆花布局");
        check(KeyboardPlan.fixedFits(tkl, 751, T) && KeyboardPlan.fixedFits(full, 933, T) && !KeyboardPlan.fixedFits(full, 704, T),
                "Fold 外屏横屏放得下 TKL、内屏横屏放得下全尺寸、内屏竖屏放不下全尺寸");
        check(!KeyboardPlan.fixedFits(null, 2000, T) && !KeyboardPlan.fixedFits(tkl, 0, T), "没有布局 / 还没量好：不显示");
        // 字表读不出来（FixedData 的 table 是 null）时不拼布局，KeyboardScreen 照样显示豆花布局，不显示一块没有字的实体键盘
        for (String m : MODES) for (String lang : new String[]{"us", "de"}) {
            FixedLegends.Table t = table(lang, m);
            String sh = "de".equals(lang) ? FixedLayout.ISO : FixedLayout.ANSI;
            FixedLayout l = FixedLayout.forLegends(sh, FixedLayout.TKL, m, t, ".");
            FixedLayout want = FixedLayout.build(sh, FixedLayout.TKL, m, t.hasThird(), ".");
            check(l != null && l.altGr == want.altGr && l.keys.size() == want.keys.size() && keyName(l, 0xE6).equals(keyName(want, 0xE6))
                            && keyName(l, 0xE6).equals(m.equals(FixedLayout.MAC) ? keyName(want, 0xE6) : t.hasThird() ? "AltGr" : "Alt"),
                    m + ":" + lang + " 有字表：照字表拼（右 Alt 写不写 AltGr 看有没有第三层）");
            check(FixedLayout.forLegends(sh, FixedLayout.TKL, m, null, ".") == null, m + ":" + lang + " 字表是 null：不拼");
            check(!KeyboardPlan.fixedFits(FixedLayout.forLegends(sh, FixedLayout.FULL, m, null, "."), 4000, T),
                    m + ":" + lang + " 字表是 null：再宽的窗口也不显示实体键盘");
        }
        check(table("xx", FixedLayout.WIN) == null && FixedLayout.forLegends(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN, table("xx", FixedLayout.WIN), ".") == null,
                "字表里没有这个布局那一段：同样不拼");
    }

    // ── 默认位置、触控板、⋯ ──
    static void planDefaults() {
        FixedLayout tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN);
        KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 751, 475, null, tkl, T);
        int kh = p.keyboard[3] - p.keyboard[1];
        check(p.keyboard[0] == 0 && p.keyboard[2] == 751 && p.keyboard[3] == 475, "窄窗口（Fold 外屏横屏）：键盘满宽、贴底");
        check(Math.abs(kh - 751 / (18.25f / (6.25f * 1.1f))) <= 1, "高按自然比例（行高 ≈ 键宽 × 1.1）：" + kh);
        check(p.padSide == KeyboardPlan.PAD_TOP && p.pad != null && p.pad[3] == p.keyboard[1] - 8, "触控板在上面那块");
        check(p.menuArea != null && KeyboardPlan.menuTop(p.menuArea, 751, 475, 56, 36, 0, 20) != null, "⋯ 放触控板顶上");
        check(!p.split && p.stick == null && p.keys == null && p.hole == null, "不分裂、没有红点和鼠标键");
        KeyboardPlan.Plan wide = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 1600, 400, null, tkl, T);
        check(wide.keyboard[0] == 0 && wide.keyboard[1] == 0 && wide.keyboard[3] == 400 && wide.padSide == KeyboardPlan.PAD_RIGHT,
                "宽窗口：键盘满高、按自然宽度靠左，右边给触控板");
        KeyboardPlan.Plan tight = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 933, 340, null, tkl, T);
        check(tight.pad == null && tight.menuArea == null && Math.abs(tight.keyboard[0] + tight.keyboard[2] - 933) <= 1,
                "内屏横屏分屏下半边：右边那条放不下触控板，键盘居中、⋯ 在槽里");
        KeyboardPlan.Plan none = KeyboardPlan.planFixed(KeyboardPlan.POINTER_NONE, KeyboardPlan.KEYS_OFF, 751, 475, null, tkl, T);
        check(none.pad == null && none.menuArea == null && none.keyboard[2] == 751, "指点关着：没有触控板、⋯ 在槽里");
        float[] saved = {0.1f, 0.2f, 0.9f, 1};
        KeyboardPlan.Plan s = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 1000, 500, saved, tkl, T);
        check(s.keyboard[0] == 100 && s.keyboard[1] == 100 && s.keyboard[2] == 900 && s.keyboard[3] == 500 && Arrays.equals(s.rect, saved),
                "存过的大小位置照用");
        KeyboardPlan.Plan bar = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_TOP, 751, 475, null, tkl, T);
        check(bar.keys != null && bar.middleKey != null && bar.menuArea != null && bar.menuArea[1] == bar.keys[3], "三键条在上沿时 ⋯ 在它下面");
    }

    // ── 没调过大小的默认位置守调大小的约束（不比 fixedMinWidth 窄、不比 MIN_HEIGHT 矮）：调大小时第一下不跳；一格不窄于 minKey ──
    static void defaultsKeepDragLimits() {
        FixedLayout tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN), full = layout(FixedLayout.ISO, FixedLayout.FULL, FixedLayout.WIN);
        // 又宽又矮的小窗（2026-11-01 审查给的例子）：以前按自然比例一格约 25dp，现在按一格 32dp 的宽摆，触控板照样在右边
        KeyboardPlan.Plan tiny = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 780, 170, null, tkl, T);
        check(tiny.keyboard[0] == 0 && tiny.keyboard[2] == 584 && tiny.keyboard[1] == 0 && tiny.keyboard[3] == 170
                && tiny.padSide == KeyboardPlan.PAD_RIGHT, "780×170 TKL：键盘一格 32（584 宽）、满高、靠左，右边给触控板");
        KeyboardPlan.Plan tinyNone = KeyboardPlan.planFixed(KeyboardPlan.POINTER_NONE, KeyboardPlan.KEYS_OFF, 780, 170, null, tkl, T);
        check(tinyNone.keyboard[2] - tinyNone.keyboard[0] >= 583 && Math.abs(tinyNone.keyboard[0] + tinyNone.keyboard[2] - 780) <= 1,
                "780×170 指点关着：一格 32、居中");
        float[] tall = KeyboardPlan.fixedDefaultRect(full, 800, 1280, true, T);
        check(Math.abs(tall[1] - (1 - KeyboardPlan.MIN_HEIGHT)) < 1e-5f && tall[3] == 1 && tall[0] == 0 && tall[2] == 1,
                "平板竖屏 800×1280 全尺寸：按自然比例不到四分之一高，按 MIN_HEIGHT（满宽、贴底）");
        float[] normal = KeyboardPlan.fixedDefaultRect(tkl, 1600, 400, true, T);
        check(Math.abs(normal[2] - 18.25f / (6.25f * 1.1f) * 400 / 1600) < 1e-5f, "平常的宽窗口照旧按自然比例");
        int windows = 0, raisedW = 0, raisedH = 0, bad = 0;
        String firstBad = null;
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) {
            FixedLayout l = layout(sh, sz, m);
            float natural = l.width / (FixedLayout.HEIGHT * KeyboardPlan.FIXED_ROW_ASPECT);
            for (int w = 300; w <= 2600; w += 20) for (int h = 100; h <= 1600; h += 20) {
                if (!KeyboardPlan.fixedFits(l, w, T)) continue;
                for (boolean padOn : new boolean[]{true, false}) {
                    windows++;
                    String what = name(l) + " " + w + "×" + h + (padOn ? " 触控板" : "");
                    float[] r = KeyboardPlan.fixedDefaultRect(l, w, h, padOn, T);
                    float min = KeyboardPlan.fixedMinWidth(l, w, T);
                    boolean ok = r[0] >= 0 && r[1] >= 0 && r[2] <= 1 && r[3] == 1 && r[0] < r[2] && r[1] < r[3]
                            && r[2] - r[0] >= min - 1e-5f && r[3] - r[1] >= KeyboardPlan.MIN_HEIGHT - 1e-5f
                            && (Math.round(r[2] * w) - Math.round(r[0] * w)) / l.width >= T.minKey - 0.1f;
                    if (!ok && bad++ == 0) firstBad = what + "：" + Arrays.toString(r) + "，最窄 " + min;
                    if ((float) w / h > natural && natural * h / w < min) raisedW++;
                    if ((float) w / h <= natural && w / natural / h < KeyboardPlan.MIN_HEIGHT) raisedH++;
                }
            }
        }
        check(bad == 0, "每种窗口的默认位置都在键盘区里、贴底、不比 fixedMinWidth 窄、不比 MIN_HEIGHT 矮、一格 ≥ minKey"
                + (firstBad == null ? "" : "（" + bad + " 种不对，比如 " + firstBad + "）"));
        check(raisedW > 0 && raisedH > 0, "扫到了按最窄放宽的和按最矮加高的窗口");
        System.err.println("  默认位置守调大小的约束：" + windows + " 种（布局 × 窗口 × 触控板开关），按最窄放宽 " + raisedW + "、按最矮加高 " + raisedH);
    }

    // ── 小红点：真 ThinkPad 的位置、切口、空格那一排的三个鼠标键 ──
    static void stickPlan() {
        int embedded = 0, outside = 0, ghb = 0, plans = 0;
        int[][] windows = {{751, 475}, {933, 704}, {933, 340}, {1200, 500}, {1800, 800}, {600, 300}, {1000, 380}};
        for (String sh : SHAPES) for (String sz : SIZES) for (String m : MODES) for (int[] wh : windows) {
            FixedLegends.Table t = table(sh.equals(FixedLayout.ISO) ? "de" : "us", m);
            FixedLayout l = FixedLayout.build(sh, sz, m, t.hasThird(), ".");
            if (!KeyboardPlan.fixedFits(l, wh[0], T)) continue;
            plans++;
            String what = name(l) + " " + wh[0] + "×" + wh[1];
            KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, wh[0], wh[1], null, l, T);
            check(p.pad == null && !p.split, what + " 小红点：没有触控板、不分裂");
            int[] k = p.keyboard;
            int pw = k[2] - k[0], ph = k[3] - k[1];
            int[] pt = FixedPanel.stickPoint(l, pw, ph, GAP);
            FixedPanel panel = new FixedPanel();
            panel.setBoard(l, t);
            panel.setMenuKey(true);
            panel.setHole(p.hole);
            // 红点那一点：横着是 B 的中线（= G|H 的缝），竖着在 G / H 键帽的下沿
            panel.place(pw, ph, GAP, 10);
            int[] bBox = panel.box(panel.index(0x05)), gBox = panel.box(panel.index(0x0A)), hBox = panel.box(panel.index(0x0B));
            check(Math.abs(pt[0] * 2 - (bBox[0] + bBox[2])) <= 2 && Math.abs(pt[0] * 2 - (gBox[2] + hBox[0])) <= 2,
                    what + " 红点横着在 B 的中线、G|H 的缝上");
            check(pt[1] == gBox[3] && pt[1] == hBox[3] && pt[1] < bBox[1], what + " 红点竖着在 G、H 键帽的下沿，B 上方");
            // 空位：空格那一排、以红点为中线对称、够三个键
            check(p.hole != null && p.keys != null, what + " 空格那一排让出了空位");
            if (p.hole != null) {
                check(Math.abs(p.hole[0] + p.hole[1] - 2 * pt[0]) <= 1, what + " 空位以红点为中线对称");
                check(p.hole[1] - p.hole[0] >= T.holeWidth(T.minKey), what + " 空位放得下三个鼠标键");
                check(p.keys[1] == k[1] + FixedPanel.spaceRowTop(ph) && p.keys[3] == k[3], what + " 三个鼠标键占空格那一排（行高）");
                int mid = (p.middleKey[0] + p.middleKey[1]) / 2 - k[0];
                check(Math.abs(mid - pt[0]) <= 1, what + " 中键正对红点");
                List<FixedPanel.Seg> sp = new ArrayList<>();
                for (FixedPanel.Seg s : panel.segs()) if (panel.keys().get(s.key).id == 0x2C) sp.add(s);
                float unit = pw / l.width;
                check(sp.size() == 2 && sp.get(0).right == p.hole[0] - GAP && sp.get(1).left == p.hole[1] + GAP
                                && sp.get(0).right - sp.get(0).left >= unit * KeyRows.STICK_SPACE_MIN - 2 * GAP - 1,
                        what + " 空格切成左右两段（发同一个键）、各至少 1 份");
                check(panel.capBoxes(panel.index(0x2C)).size() == 2, what + " 两段空格各一个键帽");
            }
            if (p.stickAt == KeyboardPlan.STICK_KEYS) {
                embedded++;
                float cx = (p.stick[0] + p.stick[2]) / 2f - k[0], cy = (p.stick[1] + p.stick[3]) / 2f - k[1];
                check(Math.abs(cx - pt[0]) <= 0.5f && Math.abs(cy - pt[1]) <= 0.5f, what + " 嵌在键里：圆心就是那一点");
                check(cy + p.cut <= FixedPanel.spaceRowTop(ph), what + " 切口不进空格那一排");
                panel.setStick(new int[]{p.stick[0] - k[0], p.stick[1] - k[1], p.stick[2] - k[0], p.stick[3] - k[1]}, p.cut);
                panel.place(pw, ph, GAP, 10);
                Set<Integer> cut = new HashSet<>();
                for (int i = 0; i < panel.keys().size(); i++) if (panel.cut(i) != null) cut.add(panel.keys().get(i).id);
                boolean real = cut.contains(0x0A) && cut.contains(0x0B) && cut.contains(0x05);
                if (real) ghb++;
                check(real, what + " 切口切到 G、H、B：" + cut);
                check(!cut.contains(0x11), what + " N 不切（真 ThinkPad 上 V、N 都不挨着红点）：" + cut);
                check(panel.corner(panel.index(0x0A)) != FixedPanel.CORNER_BR, what + " G 被切在右下角：角上的小字挪到别的角");
                for (int i = 0; i < panel.keys().size(); i++) {
                    if (panel.cut(i) == null) continue;
                    KeyRows.Placed q = new KeyRows.Placed(panel.keys().get(i).face, panel.box(i)[0], panel.box(i)[1], panel.box(i)[2], panel.box(i)[3]);
                    check(KeyboardPlan.cutShare(q, cx, cy, p.cut) <= KeyboardPlan.STICK_MAX_SHARE + 1e-3f,
                            what + " " + panel.keys().get(i) + " 被切掉的不超过四分之一");
                }
            } else if (p.stickAt == KeyboardPlan.STICK_OUTSIDE) {
                outside++;
            }
        }
        check(embedded > 0, "有嵌在键里的情况");
        check(ghb == embedded, "嵌在键里时都切到 G、H、B");
        System.err.println("  实体键盘小红点：" + plans + " 种摆法，嵌在键里 " + embedded + "、放外面 " + outside);
    }

    // ── 小红点、三个鼠标键和键的分流逐点核对（同 KeyboardPlanTest.stickRouting）──
    static void stickRouting() {
        int points = 0, inStick = 0, inTiles = 0, corners = 0, bad = 0;
        String first = null;
        int[][] windows = {{933, 704}, {1200, 500}, {751, 475}, {1800, 800}};
        for (String sh : SHAPES) for (String m : MODES) for (int[] wh : windows) {
            FixedLegends.Table t = table(sh.equals(FixedLayout.ISO) ? "de" : "us", m);
            FixedLayout l = FixedLayout.build(sh, FixedLayout.TKL, m, t.hasThird(), ".");
            KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, wh[0], wh[1], null, l, T);
            if (p.stickAt != KeyboardPlan.STICK_KEYS || p.keys == null) continue;
            int[] k = p.keyboard;
            int pw = k[2] - k[0], ph = k[3] - k[1];
            FixedPanel panel = new FixedPanel();
            panel.setBoard(l, t);
            panel.setMenuKey(true);
            panel.setHole(p.hole);
            panel.setStick(new int[]{p.stick[0] - k[0], p.stick[1] - k[1], p.stick[2] - k[0], p.stick[3] - k[1]}, p.cut);
            panel.place(pw, ph, GAP, 10);
            // 面板坐标里的区域：红点、三个鼠标键排在键和范围前面（MainActivity.collectRegions）
            TouchRouter.Region stick = new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, p.stick[0] - k[0], p.stick[1] - k[1],
                    p.stick[2] - k[0], p.stick[3] - k[1]);
            TouchRouter.Region[] tiles = new TouchRouter.Region[3];
            for (int i = 0; i < 3; i++) {
                int a = i == 0 ? p.keys[0] : i == 1 ? p.middleKey[0] : p.middleKey[1];
                int b = i == 0 ? p.middleKey[0] : i == 1 ? p.middleKey[1] : p.keys[2];
                tiles[i] = new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, Proto.MOUSE_BUTTON_ID_BASE | (1 << i), a - k[0], p.keys[1] - k[1],
                        b - k[0], p.keys[3] - k[1]);
            }
            TouchRouter.Region[] keysAndRanges = regions(panel);
            List<TouchRouter.Region> with = new ArrayList<>();
            with.add(stick);
            with.addAll(Arrays.asList(tiles));
            with.addAll(Arrays.asList(keysAndRanges));
            TouchRouter a = new TouchRouter(new KeyRowsTest.Quiet()), b = new TouchRouter(new KeyRowsTest.Quiet());
            a.setRegions(with.toArray(new TouchRouter.Region[0]));
            b.setRegions(keysAndRanges);
            TouchRouter.Region[] all = with.toArray(new TouchRouter.Region[0]);
            FixedPanel plain = new FixedPanel();
            plain.setBoard(l, t);
            plain.setMenuKey(true);
            plain.setHole(p.hole);
            plain.place(pw, ph, GAP, 10);
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    boolean nearStick = x >= stick.left - 30 && x < stick.right + 30 && y >= stick.top - 30 && y < stick.bottom + 30;
                    boolean nearHole = y >= p.keys[1] - k[1] - 4;
                    if (!nearStick && !nearHole && (x % 3 != 0 || y % 3 != 0)) continue;
                    if (nearHole && !nearStick && (x % 2 != 0 || y % 2 != 0)) continue;
                    points++;
                    TouchRouter.Region got = a.hit(x, y), bare = b.hit(x, y);
                    int finger = panel.keyAt(x, y), bareFinger = plain.keyAt(x, y);
                    TouchRouter.Region tile = null;
                    for (TouchRouter.Region tr : tiles) if (tr.contains(x, y)) tile = tr;
                    String why = null;
                    if (stick.contains(x, y)) {
                        inStick++;
                        if (got != stick) why = "红点圆里却归了 " + got;
                        else if (finger >= 0) why = "面板自己收触摸：红点圆里却分给了键";
                    } else if (tile != null) {
                        inTiles++;
                        if (got != tile) why = "空位里却归了 " + got;
                        else if (finger >= 0) why = "面板自己收触摸：空位里却分给了键";
                    } else {
                        if (got != bare && !(got != null && bare != null && got.id == bare.id && got.type == bare.type)) {
                            why = "红点、空位外和没有它们时不一样";
                        } else if (finger != bareFinger) {
                            why = "面板自己收触摸：洞外和没洞时不一样";
                        }
                        boolean square = x >= stick.left && x < stick.right && y >= stick.top && y < stick.bottom;
                        for (FixedPanel.Seg s : panel.segs()) {
                            if (x < s.left || x >= s.right || y < s.top || y >= s.bottom) continue;
                            if (square) corners++;
                            if (why == null && (got == null || got.id != s.key)) why = "看着是键（" + panel.keys().get(s.key) + "）按上去却是 " + got;
                        }
                    }
                    TouchRouter.Region nk = null;
                    for (TouchRouter.Region r : all) {
                        if (r.type == Proto.REGION_KEYBOARD && r.contains(x, y)) nk = TouchRouter.nearestKey(all, r, x, y);
                    }
                    if (why == null && nk != null && (nk.type == Proto.REGION_STICK || nk.type == Proto.REGION_MOUSE_BUTTON)) {
                        why = "nearestKey 选了红点 / 鼠标键";
                    }
                    if (why != null) {
                        bad++;
                        if (first == null) first = name(l) + " " + wh[0] + "×" + wh[1] + " (" + x + ", " + y + ")：" + why;
                    }
                }
            }
        }
        check(bad == 0, "实体键盘红点、鼠标键和键的分流扫了 " + points + " 个点（红点圆里 " + inStick + "、空位里 " + inTiles
                + "、方块角上的键 " + corners + "），" + bad + " 个不对" + (first == null ? "" : "，比如 " + first));
        check(inStick > 0 && inTiles > 0 && corners > 0, "扫到了红点圆里、空位里、方块角上的点");
        System.err.println("  实体键盘小红点分流扫描：" + points + " 个点（红点圆里 " + inStick + "、空位里 " + inTiles + "、方块角上的键 " + corners + "）");
    }

    // ── 工具 ──
    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    static void eqs(String got, String want, String what) {
        check(want == null ? got == null : want.equals(got), what + "（得到 " + got + "，应为 " + want + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
