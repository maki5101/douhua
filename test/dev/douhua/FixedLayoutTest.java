package dev.douhua;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 实体键盘（第 1B 批）的离线测试：FixedLayout（u 里的几何）、FixedPanel（摆到像素、区域、面板自己收触摸、键帽上的字）、
 * KeyboardPlan.planFixed（默认位置、触控板、⋯、窄窗口退回豆花布局、小红点在真 ThinkPad 的位置 + 切口 + 空格那一排的三个鼠标键）。
 * 每个 形状 × 尺寸 × 模式 都跑；交给真正的 TouchRouter（按面板报区域的顺序）和 KeyFingers 逐点核对（照 KeyRowsTest / KeyboardPlanTest 的做法）。
 * 笔记本尺寸和逐级缩（2026-11-01，方案 design-laptop §8）：1B 的扫描带上尺寸参数，原来那次照旧传 TKL / 全尺寸、打印的统计行逐字不变，
 * 笔记本另调一次、单独打一行；外加笔记本自己的几何、范围（Mac 的 Ins 空格子、方向键两层各一块）、方向键周围的缝真分给谁、特殊键只有一段、按钮跨段抬起照样算点击、
 * 逐级缩挑哪一级（KeyboardPlan.fixedLevel）、存的位置撑到一格 32dp（fitFixedRect）、逐级缩的状态（FixedSizing，含随机序列）。
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
    /** 笔记本另跑一次（1B 的 SIZES 不动，原来那次的统计行逐字不变）。 */
    private static final String[] LAPTOP_ONLY = {FixedLayout.LAPTOP};
    private static final String LAPTOP_LABEL = "（笔记本）";
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
        placement(SIZES, SIZES_PX);
        routing(SIZES, new int[][]{{933, 352}, {751, 283}}, "");
        pins();
        caps(SIZES, new int[][]{{933, 352}, {600, 230}}, "");
        glyphs();
        capsGlyphs();
        functionKeys();
        fits();
        planDefaults();
        defaultsKeepDragLimits();
        stickPlan(SIZES, new int[][]{{751, 475}, {933, 704}, {933, 340}, {1200, 500}, {1800, 800}, {600, 300}, {1000, 380}}, "");
        stickRouting(new String[]{FixedLayout.TKL}, new int[][]{{933, 704}, {1200, 500}, {751, 475}, {1800, 800}}, "");
        // ── 笔记本尺寸、逐级缩（2026-11-01）──
        laptopCounts();
        laptopRows();
        laptopBottom();
        laptopNoOverlap();
        laptopRanges();
        placement(LAPTOP_ONLY, new int[][]{{480, 230}, {520, 250}, {583, 280}, {751, 300}, {933, 352}, {1800, 700}});
        laptopArrowGap();
        laptopArrowSeam();
        routing(LAPTOP_ONLY, new int[][]{{480, 230}, {520, 250}, {751, 300}}, LAPTOP_LABEL);
        laptopPins();
        specialOneSegment();
        buttonTap();
        caps(LAPTOP_ONLY, new int[][]{{933, 352}, {480, 230}}, LAPTOP_LABEL);
        laptopGlyphs();
        levels();
        laptopDefaults();
        stickPlan(LAPTOP_ONLY, new int[][]{{480, 230}, {520, 250}, {560, 270}, {751, 475}, {933, 704}, {933, 340}, {1800, 800}}, LAPTOP_LABEL);
        laptopStickHole();
        stickRouting(LAPTOP_ONLY, new int[][]{{751, 475}, {933, 704}, {1200, 500}}, LAPTOP_LABEL);
        fitFixed();
        sizing();
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
                        if (s[0] >= g.left - 1e-4f && s[1] <= g.right + 1e-4f && l.rowTop(r) >= g.top - 1e-4f
                                && l.rowBottom(r) <= g.bottom + 1e-4f) {
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
    static void placement(String[] sizes, int[][] windows) {
        for (String sh : SHAPES) for (String sz : sizes) for (String m : MODES) for (int[] wh : windows) {
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
    static void routing(String[] sizes, int[][] windows, String label) {
        int points = 0, inKeys = 0, inGaps = 0, nothing = 0, bad = 0;
        String first = null;
        for (String sh : SHAPES) for (String sz : sizes) for (String m : MODES) for (int[] wh : windows) {
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
        check(bad == 0, "实体键盘分流" + label + "扫了 " + points + " 个点（键上 " + inKeys + "、缝里 " + inGaps + "、空格子 " + nothing + "），"
                + bad + " 个不对" + (first == null ? "" : "，比如 " + first));
        check(inKeys > 0 && inGaps > 0 && nothing > 0, "扫到了键上、缝里、空格子里的点" + label);
        System.err.println("  实体键盘分流扫描" + label + "：" + points + " 个点（键上 " + inKeys + "、缝里 " + inGaps + "、空格子 " + nothing + "）");
    }

    static int ux(FixedLayout l, float u, int w) {
        return FixedPanel.px(u, w, l.width);
    }

    static int uy(FixedLayout l, float u, int h) {
        return FixedPanel.px(u, h, l.height);
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
            int ay = (uy(l, 3.25f, h) + uy(l, 4.25f, h)) / 2;
            int above = route(router, ux(l, 16.75f, w), ay);
            check(above == -1, name(l) + " TKL 里 ↑ 正上方一格什么都没有（不是回车 / '）：" + id(p, above));
            int nearUp = route(router, ux(l, 16.75f, w), uy(l, 4.25f, h) + 1);
            check(id(p, nearUp) == 0x52, name(l) + " ↑ 上面那条缝（在 ↑ 那一行里）给 ↑");
            int f45 = id(p, route(router, ux(l, 6.25f, w), uy(l, 0.5f, h)));
            check(f45 == 0x3D || f45 == 0x3E, name(l) + " F4 和 F5 之间那半格给 F4 或 F5：" + Integer.toHexString(f45));
            int slot = id(p, route(router, ux(l, 1.5f, w), uy(l, 0.5f, h)));
            check(slot == 0x29 || slot == 0x3A, name(l) + " 空着的 ⋯ 槽给 Esc 或 F1：" + Integer.toHexString(slot));
            p.setMenuKey(true);
            p.place(w, h, GAP, 10);
            router.setRegions(regions(p));
            check(id(p, route(router, ux(l, 1.5f, w), uy(l, 0.5f, h))) == FixedLayout.MENU_ID, name(l) + " 槽里放了 ⋯ 就是 ⋯");
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
            int b = ux(l, 13.75f, w), y3 = (uy(l, 3.25f, h) + uy(l, 4.25f, h)) / 2;
            check(id(p, route(router, b - 1, y3)) == 0x31, name(l) + " 回车下半段左边的缝：靠左给 0x31（#）");
            check(id(p, route(router, b + 1, y3)) == 0x28, name(l) + " 回车下半段左边的缝：靠右给回车");
            check(id(p, route(router, ux(l, 14.5f, w), uy(l, 3.25f, h))) == 0x28, name(l) + " L 形回车里面两行之间那条缝给回车");
            check(id(p, route(router, ux(l, 13.6f, w), y3)) == 0x31, name(l) + " 回车上半段正下方、0x31 上面一点给 0x31");
        }
    }

    // ── 会显示的键都有键帽（不变量"键盘上会显示的每个键都有键帽"，实体键盘同样适用） ──
    static void caps(String[] sizes, int[][] windows, String label) {
        int combos = 0;
        int[] mods = {0, 0x02, 0x20, 0x40, 0x04, 0x42, 0x44, 0x22};
        for (FixedLegends.Layout lang : catalog) {
            for (String sh : lang.shapes) for (String sz : sizes) for (String m : MODES) for (boolean menu : new boolean[]{false, true}) {
                FixedLegends.Table t = table(lang.id, m);
                FixedLayout l = FixedLayout.build(sh, sz, m, t.hasThird(), lang.kpdl);
                for (int[] wh : windows) {
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
                            for (int md : mods) for (boolean cl : new boolean[]{false, true}) {   // Caps 显示开 / 关（2026-11-01）
                                List<FixedPanel.Glyph> gs = p.glyphs(i, md, cl, "Space");
                                boolean any = false;
                                for (FixedPanel.Glyph g : gs) {
                                    if (g.text == null || g.text.isEmpty()) bad = k + " 有空的字";
                                    if (g.slot != FixedPanel.Glyph.CORNER) any = true;
                                }
                                if (!any) {
                                    ok = false;
                                    bad = k + " 锁着 " + Integer.toHexString(md) + (cl ? " + Caps" : "") + " 时键帽上没有字";
                                }
                            }
                        }
                        check(ok && bad == null, what + " 每个键都有键帽和字" + (bad == null ? "" : "：" + bad));
                        if (l.laptop()) laptopCapShapes(p, holed, what);
                    }
                }
            }
        }
        System.err.println("  键帽齐全" + label + "：" + combos + " 种（布局 × 形状 × 尺寸 × 模式 × ⋯ × 让位 × 窗口）");
    }

    static FixedPanel panel(String lang, String shape, String size, String mode) {
        FixedLegends.Table t = table(lang, mode);
        FixedPanel p = new FixedPanel();
        p.setBoard(FixedLayout.build(shape, size, mode, t.hasThird(), FixedLegends.find(catalog, lang).kpdl), t);
        p.place(933, 352, GAP, 10);
        return p;
    }

    static String glyphs(FixedPanel p, int id, int mods) {
        return glyphs(p, id, mods, false);
    }

    static String glyphs(FixedPanel p, int id, int mods, boolean caps) {
        StringBuilder sb = new StringBuilder();
        for (FixedPanel.Glyph g : p.glyphs(p.index(id), mods, caps, "空格")) {
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

    /**
     * Caps 的显示（2026-11-01）：字母类的键 Windows 模式 Shift ⊕ Caps、Mac 模式 Shift ∨ Caps；德语 ä ö ü 跟着 Caps，ß、数字、标点、第三层不跟；
     * 按着（held）的 Shift / AltGr / Option 换层同锁着（mods 本来就是 latched | held，一个掩码）；Caps 键在显示开着时高亮、修饰键看 latched | armed。
     */
    static void capsGlyphs() {
        FixedPanel de = panel("de", FixedLayout.ISO, FixedLayout.TKL, FixedLayout.WIN);
        FixedPanel md = panel("de", FixedLayout.ISO, FixedLayout.TKL, FixedLayout.MAC);
        FixedPanel us = panel("us", FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN);
        FixedPanel um = panel("us", FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.MAC);
        eqs(glyphs(us, 0x04, 0, true), "中A", "win:us Caps：a 画成 A");
        eqs(glyphs(us, 0x04, 0x02, true), "中a", "win:us Caps + Shift：小写（Windows / Linux / 安卓）");
        eqs(glyphs(us, 0x04, 0x20, true), "中a", "win:us Caps + 右 Shift：小写");
        eqs(glyphs(um, 0x04, 0, true), "中A 角å", "mac:us Caps：A（角上 Option 层照旧，大写锁定下的 Å 字表里没有、不画）");
        eqs(glyphs(um, 0x04, 0x02, true), "中A 角å（暗）", "mac:us Caps + Shift：照样大写（macOS 不反转）");
        eqs(glyphs(um, 0x04, 0x02, false), "中A 角å（暗）", "mac:us Shift：大写");
        eqs(glyphs(us, 0x1F, 0, true), "上@ 下2", "win:us Caps：数字键不变");
        eqs(glyphs(us, 0x36, 0, true), glyphs(us, 0x36, 0, false), "win:us Caps：标点不变");
        eqs(glyphs(de, 0x2F, 0, true), "中Ü", "win:de Caps：ü 画成 Ü");
        eqs(glyphs(de, 0x2F, 0x02, true), "中ü", "win:de Caps + Shift：ü");
        eqs(glyphs(md, 0x2F, 0x02, true), "中Ü 角•（暗）", "mac:de Caps + Shift：Ü");
        eqs(glyphs(de, 0x2D, 0, true), glyphs(de, 0x2D, 0, false), "win:de Caps：ß 那个键不变（Shift 层是 ?，不是字母那种键）");
        eqs(glyphs(de, 0x14, 0x40, true), "中Q（暗） 角@", "win:de Caps + AltGr：字母照 Caps 画大写、压暗，角上的第三层不跟 Caps");
        eqs(glyphs(md, 0x0F, 0x04, true), "中L（暗） 角@", "mac:de Caps + Option：同上");
        // 放大提示：字母照 Caps 换大小写，第三层不看
        eqs(us.preview(us.index(0x04), 0, true), "A", "放大提示：Caps 时 A");
        eqs(us.preview(us.index(0x04), 0x02, true), "a", "放大提示：Windows Caps + Shift 时 a");
        eqs(um.preview(um.index(0x04), 0x02, true), "A", "放大提示：Mac Caps + Shift 时 A");
        eqs(de.preview(de.index(0x14), 0x40, true), "@", "放大提示：Caps + AltGr+Q 照样 @");
        eqs(us.preview(us.index(0x1F), 0, true), "2", "放大提示：Caps 时数字不变");
        eqs(us.preview(us.index(0x04), 0), "a", "放大提示：不带 caps 的旧方法照旧");
        // 高亮
        FixedLayout.Key capsKey = us.keys().get(us.index(0x39)), shift = us.keys().get(us.index(0xE1));
        check(FixedPanel.active(capsKey, 0, true) && !FixedPanel.active(capsKey, 0xFF, false), "Caps 键：只看显示的大写锁定");
        check(FixedPanel.active(shift, 0x02, false) && !FixedPanel.active(shift, 0, true), "Shift：看 latched | armed 那一位");
        check(!FixedPanel.active(us.keys().get(us.index(0x04)), 0xFF, true), "字母不高亮");
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
        // ⋯ 槽（2026-11-01：强调色的底 + 反差字，和豆花布局的 ⋯ 键同一条 KeyRows.menuKey）：槽里放了 ⋯ 时正好一个键是它，没放时一个都没有
        for (String mode : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
            FixedLayout l = layout(FixedLayout.ISO, FixedLayout.FULL, mode);
            for (boolean menu : new boolean[]{false, true}) {
                int n = 0;
                for (FixedLayout.Key k : l.keys(menu)) {
                    if (!KeyRows.menuKey(k.id)) continue;
                    n++;
                    check("⋯".equals(k.name) && k.special && k.id == FixedLayout.MENU_ID, mode + "：⋯ 槽的键就是 ⋯");
                }
                check(n == (menu ? 1 : 0), mode + (menu ? "：槽里放了 ⋯，正好一个 ⋯" : "：槽空着，没有 ⋯") + "（" + n + "）");
            }
        }
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
        defaultsScan(SIZES, "");
    }

    /** 每种窗口的默认位置守调大小的约束（{@link #defaultsKeepDragLimits} 的扫描，尺寸带参数：笔记本另跑一次）。 */
    static void defaultsScan(String[] sizes, String label) {
        int windows = 0, raisedW = 0, raisedH = 0, bad = 0;
        String firstBad = null;
        for (String sh : SHAPES) for (String sz : sizes) for (String m : MODES) {
            FixedLayout l = layout(sh, sz, m);
            float natural = l.width / (l.height * KeyboardPlan.FIXED_ROW_ASPECT);
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
        check(bad == 0, "每种窗口的默认位置都在键盘区里、贴底、不比 fixedMinWidth 窄、不比 MIN_HEIGHT 矮、一格 ≥ minKey" + label
                + (firstBad == null ? "" : "（" + bad + " 种不对，比如 " + firstBad + "）"));
        check(raisedW > 0 && raisedH > 0, "扫到了按最窄放宽的和按最矮加高的窗口" + label);
        System.err.println("  默认位置守调大小的约束" + label + "：" + windows + " 种（布局 × 窗口 × 触控板开关），按最窄放宽 " + raisedW + "、按最矮加高 " + raisedH);
    }

    // ── 小红点：真 ThinkPad 的位置、切口、空格那一排的三个鼠标键 ──
    static void stickPlan(String[] sizes, int[][] windows, String label) {
        int embedded = 0, outside = 0, ghb = 0, plans = 0;
        for (String sh : SHAPES) for (String sz : sizes) for (String m : MODES) for (int[] wh : windows) {
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
                check(p.keys[1] == k[1] + FixedPanel.spaceRowTop(l, ph) && p.keys[3] == k[3], what + " 三个鼠标键占空格那一排（行高）");
                int mid = (p.middleKey[0] + p.middleKey[1]) / 2 - k[0];
                check(Math.abs(mid - pt[0]) <= 1, what + " 中键正对红点");
                List<FixedPanel.Seg> sp = new ArrayList<>();
                for (FixedPanel.Seg s : panel.segs()) if (panel.keys().get(s.key).id == 0x2C) sp.add(s);
                float unit = pw / l.width;
                // 笔记本的空格最短 0.75 份（FixedPanel.LAPTOP_SPACE_MIN，只在 1 份时让不出三个 minKey 的键时才往下缩）
                float minSpace = l.laptop() ? FixedPanel.LAPTOP_SPACE_MIN : KeyRows.STICK_SPACE_MIN;
                check(sp.size() == 2 && sp.get(0).right == p.hole[0] - GAP && sp.get(1).left == p.hole[1] + GAP
                                && sp.get(0).right - sp.get(0).left >= unit * minSpace - 2 * GAP - 1
                                && sp.get(1).right - sp.get(1).left >= unit * minSpace - 2 * GAP - 1,
                        what + " 空格切成左右两段（发同一个键）、各至少 " + (l.laptop() ? "0.75" : "1") + " 份");
                check(panel.capBoxes(panel.index(0x2C)).size() == 2, what + " 两段空格各一个键帽");
                if (l.laptop() && sp.size() == 2) {
                    int a = sp.get(0).right - sp.get(0).left, b = sp.get(1).right - sp.get(1).left;
                    check(Math.abs(a - b) <= 1, what + " 笔记本的空格以红点为中线：两段一样长（" + a + " / " + b + "）");
                }
            }
            if (p.stickAt == KeyboardPlan.STICK_KEYS) {
                embedded++;
                float cx = (p.stick[0] + p.stick[2]) / 2f - k[0], cy = (p.stick[1] + p.stick[3]) / 2f - k[1];
                check(Math.abs(cx - pt[0]) <= 0.5f && Math.abs(cy - pt[1]) <= 0.5f, what + " 嵌在键里：圆心就是那一点");
                check(cy + p.cut <= FixedPanel.spaceRowTop(l, ph), what + " 切口不进空格那一排");
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
        check(embedded > 0, "有嵌在键里的情况" + label);
        check(ghb == embedded, "嵌在键里时都切到 G、H、B" + label);
        System.err.println("  实体键盘小红点" + label + "：" + plans + " 种摆法，嵌在键里 " + embedded + "、放外面 " + outside);
    }

    // ── 小红点、三个鼠标键和键的分流逐点核对（同 KeyboardPlanTest.stickRouting）──
    static void stickRouting(String[] sizes, int[][] windows, String label) {
        int points = 0, inStick = 0, inTiles = 0, corners = 0, bad = 0;
        String first = null;
        for (String sz : sizes) for (String sh : SHAPES) for (String m : MODES) for (int[] wh : windows) {
            FixedLegends.Table t = table(sh.equals(FixedLayout.ISO) ? "de" : "us", m);
            FixedLayout l = FixedLayout.build(sh, sz, m, t.hasThird(), ".");
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
        check(bad == 0, "实体键盘红点、鼠标键和键的分流" + label + "扫了 " + points + " 个点（红点圆里 " + inStick + "、空位里 " + inTiles
                + "、方块角上的键 " + corners + "），" + bad + " 个不对" + (first == null ? "" : "，比如 " + first));
        check(inStick > 0 && inTiles > 0 && corners > 0, "扫到了红点圆里、空位里、方块角上的点" + label);
        System.err.println("  实体键盘小红点分流扫描" + label + "：" + points + " 个点（红点圆里 " + inStick + "、空位里 " + inTiles + "、方块角上的键 " + corners + "）");
    }

    // ═══════════ 笔记本尺寸（2026-11-01，方案 design-laptop §8）═══════════

    static FixedLayout laptop(String shape, String mode) {
        return layout(shape, FixedLayout.LAPTOP, mode);
    }

    /** 1/16u 的整数倍（笔记本所有坐标都取这个，浮点里是精确值）。 */
    static boolean sixteenth(float u) {
        return Float.isNaN(u) || Math.abs(u * 16 - Math.round(u * 16)) < 1e-6f;
    }

    /** 第 5 行里整行高的键（y0 是 NaN）/ 上层（y0 = 5.25）/ 下层（y0 = 5.875）的用法码，按 x 排好。layer：0 整行、1 上层、2 下层。 */
    static int[] bottomIds(List<FixedLayout.Key> ks, int layer) {
        List<FixedLayout.Key> out = new ArrayList<>();
        for (FixedLayout.Key k : ks) {
            if (k.row != 5) continue;
            int at = Float.isNaN(k.y0) ? 0 : near(k.y0, FixedLayout.ROW_TOP[5]) ? 1 : 2;
            if (at == layer) out.add(k);
        }
        out.sort((a, b) -> Float.compare(a.x, b.x));
        int[] ids = new int[out.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = out.get(i).id;
        return ids;
    }

    // ── 键数、用法码各一次、有哪些没有哪些 ──
    static void laptopCounts() {
        int[][] want = {{83, 84}, {82, 83}};   // [模式][形状]：Windows 83 / 84（ANSI / ISO）、Mac 82 / 83（没有 Ins）
        for (int m = 0; m < 2; m++) for (int sh = 0; sh < 2; sh++) {
            FixedLayout l = laptop(SHAPES[sh], MODES[m]);
            check(l.keys.size() == want[m][sh], name(l) + " 键数 " + l.keys.size() + "（应为 " + want[m][sh] + "）");
            check(l.keys(true).size() == l.keys.size() + 1, name(l) + " 槽里放 ⋯ 时多一个键");
            Set<Integer> ids = new HashSet<>();
            boolean dup = false;
            for (FixedLayout.Key k : l.keys(true)) dup |= !ids.add(k.id);
            check(!dup, name(l) + " 每个用法码 / 按钮编号只出现一次（含 ⋯）");
            check(l.laptop() && !l.full() && l.clusterCount() == 1 && near(l.width, 15) && near(l.height, 6.5f), name(l) + " 15u 宽、6.5u 高、一个区块");
            boolean main = true;
            for (FixedLayout.Key k : l.keys(true)) main &= k.cluster == FixedLayout.MAIN && k.x + k.w <= 15 + 1e-4f;
            check(main, name(l) + " 每个键都在主键区（0–15u）");
            check(ids.contains(0x64) == l.iso() && !ids.contains(0x32) && ids.contains(0x31), name(l) + " 0x64 只在 ISO、不发 0x32");
            check(!ids.contains(0x53) && !ids.contains(0x62), name(l) + " 没有小键盘");
            for (int u : new int[]{0x4A, 0x4D, 0x4C, 0x4B, 0x4E, 0x52, 0x50, 0x51, 0x4F, 0xE0, 0xE2, 0xE3, 0xE4, 0x2C, 0x29, 0x3A, 0x45}) {
                check(ids.contains(u), name(l) + " 有 " + Integer.toHexString(u));
            }
            if (l.mac()) {
                for (int u : new int[]{0x46, 0x49, 0x68, 0x69, 0x6A, 0xE6, 0x47, 0x48, 0x65}) check(!ids.contains(u), name(l) + " 没有 " + Integer.toHexString(u));
                check(ids.contains(FixedLayout.GLOBE_ID) && ids.contains(0xE7), name(l) + " 有 🌐、右 ⌘");
            } else {
                check(ids.contains(0x46) && ids.contains(0x49) && ids.contains(0xE6), name(l) + " 有 PrtSc、Ins、右 Alt");
                for (int u : new int[]{0x47, 0x48, 0x65, 0xE7, FixedLayout.GLOBE_ID}) check(!ids.contains(u), name(l) + " 没有 " + Integer.toHexString(u));
            }
        }
        check(FixedLayout.LAPTOP.equals(FixedLayout.validSize("laptop")) && FixedLayout.TKL.equals(FixedLayout.validSize("bogus"))
                && FixedLayout.TKL.equals(FixedLayout.validSize(null)), "validSize 认笔记本，认不出的当 TKL");
    }

    // ── 笔记本的几何：第 1–4 行同 TKL、F 行、最下一排两层、⋯ 槽、空格正对红点、坐标都是 1/16u ──
    static void laptopRows() {
        for (String sh : SHAPES) for (String m : MODES) {
            FixedLayout l = laptop(sh, m), t = layout(sh, FixedLayout.TKL, m);
            for (int r = 1; r <= 4; r++) {
                List<float[]> a = spans(l, r, FixedLayout.MAIN, false), b = spans(t, r, FixedLayout.MAIN, false);
                boolean same = a.size() == b.size();
                for (int i = 0; same && i < a.size(); i++) same = near(a.get(i)[0], b.get(i)[0]) && near(a.get(i)[1], b.get(i)[1]);
                check(same, name(l) + " 第 " + r + " 行和 TKL 的主键区一模一样");
                check(Arrays.equals(rowIdsMain(l, r), rowIdsMain(t, r)), name(l) + " 第 " + r + " 行的用法码和 TKL 一样");
            }
            List<FixedLayout.Key> f = new ArrayList<>();
            for (FixedLayout.Key k : l.keys) if (k.row == 0) f.add(k);
            f.sort((x, y) -> Float.compare(x.x, y.x));
            check(f.size() == (l.mac() ? 16 : 17), name(l) + " F 行 " + f.size() + " 个键");
            FixedLayout.Key esc = l.find(0x29), f1 = l.find(0x3A), f5 = l.find(0x3E), f9 = l.find(0x42), home = l.find(0x4A), end = l.find(0x4D),
                    del = l.find(0x4C), ins = l.find(0x49);
            check(near(esc.x, 0) && near(esc.w, 1.125f) && near(f1.x, 1.125f) && near(f5.x, 4.5625f) && near(f9.x, 8) && near(l.find(0x45).x + l.find(0x45).w, 11.25f)
                    && near(home.x, 11.4375f) && near(end.x, 12.25f) && near(del.x, 13.875f) && near(del.x + del.w, 15)
                    && (l.mac() ? ins == null : near(ins.x, 13.0625f) && near(ins.w, 0.8125f)), name(l) + " F 行的位置（Esc 1.125、F 键 0.8125、组间 0.1875、Del 到 15）");
            boolean fRow = true;
            for (int i = 1; i < f.size(); i++) fRow &= f.get(i).x >= f.get(i - 1).x + f.get(i - 1).w - 1e-4f;
            check(fRow, name(l) + " F 行不重叠");
            // 最下一排上下两层，各从 0 铺到 15、首尾相接（整行的键算进两层，方向键只算自己那层）
            for (boolean menu : new boolean[]{false, true}) {
                for (int layer = 1; layer <= 2; layer++) {
                    List<float[]> s = new ArrayList<>();
                    for (FixedLayout.Key k : l.keys(menu)) {
                        if (k.row != 5) continue;
                        boolean in = Float.isNaN(k.y0) || (layer == 1 ? near(k.y0, 5.25f) : near(k.y0, 5.875f));
                        if (in) s.add(new float[]{k.x, k.x + k.w});
                    }
                    s.sort((x, y) -> Float.compare(x[0], y[0]));
                    boolean ok = !s.isEmpty() && near(s.get(0)[0], 0) && near(s.get(s.size() - 1)[1], 15);
                    for (int i = 1; i < s.size(); i++) ok &= near(s.get(i)[0], s.get(i - 1)[1]);
                    check(ok, name(l) + (menu ? "（带 ⋯）" : "") + " 最下一排" + (layer == 1 ? "上" : "下") + "层从 0 铺到 15u、首尾相接");
                }
            }
            FixedLayout.Key ctrlWide = l.keys(false).get(indexOfId(l.keys(false), 0xE0)), ctrlNarrow = l.keys(true).get(indexOfId(l.keys(true), 0xE0));
            check(near(ctrlWide.x, 0) && near(ctrlWide.w, 2.25f) && near(ctrlNarrow.w, 1.25f) && ctrlWide != ctrlNarrow, name(l) + " 左 Ctrl：⋯ 不在槽里 2.25u、在槽里 1.25u");
            check(l.menu.row == 5 && near(l.menu.x, 1.25f) && near(l.menu.w, 1) && Float.isNaN(l.menu.y0), name(l) + " ⋯ 槽在左下角 Ctrl 右边（1.25–2.25、整行高）");
            FixedLayout.Key sp = l.find(0x2C);
            check(near(sp.x, 4.25f) && near(sp.w, 5) && near(l.stickU(), 6.75f) && near(sp.x + sp.w / 2, l.stickU()), name(l) + " 空格 4.25–9.25、正好以红点（6.75）为中线");
            check(near(l.rowTop(5), 5.25f) && near(l.rowBottom(5), 6.5f) && near(l.rowBottom(4), 5.25f) && near(l.rowBottom(0), 1)
                    && near(t.rowBottom(5), 6.25f) && near(t.height, 6.25f), name(l) + " 行表：最下一排 5.25–6.5（TKL 照旧 6.25）");
            boolean grid = true;
            for (FixedLayout.Key k : l.keys(true)) grid &= sixteenth(k.x) && sixteenth(k.w) && sixteenth(k.y0) && sixteenth(k.y1) && sixteenth(k.x2) && sixteenth(k.w2);
            check(grid, name(l) + " 坐标都是 1/16u 的整数倍");
            FixedLayout.Key up = l.find(0x52), down = l.find(0x51);
            check(near(up.y0, 5.25f) && near(up.y1, 5.875f) && near(down.y0, 5.875f) && near(down.y1, 6.5f) && near(up.x, down.x) && near(up.w, 0.9375f),
                    name(l) + " ↑ 在上层、↓ 在下层，各 0.625 行高、0.9375u 宽");
            check(near(l.segTop(up, 5), 5.25f) && near(l.segBottom(down, 5), 6.5f) && near(l.segTop(sp, 5), 5.25f) && near(l.segBottom(sp, 5), 6.5f),
                    name(l) + " segTop / segBottom：方向键用自己那截、别的键占整行");
        }
        check(FixedLayout.ladder(FixedLayout.FULL).equals(Arrays.asList(FixedLayout.FULL, FixedLayout.TKL, FixedLayout.LAPTOP))
                && FixedLayout.ladder(FixedLayout.TKL).equals(Arrays.asList(FixedLayout.TKL, FixedLayout.LAPTOP))
                && FixedLayout.ladder(FixedLayout.LAPTOP).equals(Arrays.asList(FixedLayout.LAPTOP))
                && FixedLayout.ladder("bogus").equals(Arrays.asList(FixedLayout.TKL, FixedLayout.LAPTOP))
                && FixedLayout.ladder(null).equals(Arrays.asList(FixedLayout.TKL, FixedLayout.LAPTOP)), "ladder：从选的尺寸往下，只缩不涨，认不出的当 TKL");
    }

    static int indexOfId(List<FixedLayout.Key> ks, int id) {
        for (int i = 0; i < ks.size(); i++) if (ks.get(i).id == id) return i;
        return -1;
    }

    /** 主键区第 row 行的用法码（按 x 排好）。 */
    static int[] rowIdsMain(FixedLayout l, int row) {
        List<FixedLayout.Key> ks = new ArrayList<>();
        for (FixedLayout.Key k : l.keys) if (k.cluster == FixedLayout.MAIN && k.span(row) != null) ks.add(k);
        ks.sort((a, b) -> Float.compare(a.span(row)[0], b.span(row)[0]));
        int[] out = new int[ks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ks.get(i).id;
        return out;
    }

    // ── 最下一排的顺序、键名、⋯ 紧跟左 Ctrl、列表顺序 ──
    static void laptopBottom() {
        int[] pc = {0xE0, 0xE3, 0xE2, 0x2C, 0xE6, 0x46, 0xE4};
        int[] macRow = {0xE0, 0xE2, 0xE3, 0x2C, 0xE7, FixedLayout.GLOBE_ID, 0xE4};
        int[] up = {0x4B, 0x52, 0x4E}, down = {0x50, 0x51, 0x4F};
        for (String sh : SHAPES) {
            FixedLayout w = laptop(sh, FixedLayout.WIN), m = laptop(sh, FixedLayout.MAC);
            check(Arrays.equals(bottomIds(w.keys, 0), pc), name(w) + " 底行 Ctrl Super Alt 空格 AltGr PrtSc Ctrl：" + Arrays.toString(bottomIds(w.keys, 0)));
            check(Arrays.equals(bottomIds(m.keys, 0), macRow), name(m) + " 底行 ⌃ ⌥ ⌘ 空格 ⌘ 🌐 ⌃：" + Arrays.toString(bottomIds(m.keys, 0)));
            for (FixedLayout l : new FixedLayout[]{w, m}) {
                check(Arrays.equals(bottomIds(l.keys, 1), up) && Arrays.equals(bottomIds(l.keys, 2), down), name(l) + " 方向键：上层 PgUp ↑ PgDn、下层 ← ↓ →");
                int[] withMenu = bottomIds(l.keys(true), 0);
                check(withMenu.length == 8 && withMenu[0] == 0xE0 && withMenu[1] == FixedLayout.MENU_ID, name(l) + " 带 ⋯ 时 ⋯ 紧跟在左 Ctrl 后面");
                int i0 = indexOfId(l.keys(true), 0xE0);
                check(l.keys(true).get(i0 + 1).id == FixedLayout.MENU_ID, name(l) + " 列表里 ⋯ 也紧跟左 Ctrl");
                // 列表顺序：底行从左到右，接着 PgUp ↑ PgDn、← ↓ →（报区域、nearestKey 一样近时的先后）
                List<FixedLayout.Key> ks = l.keys;
                int[] tail = new int[6];
                for (int i = 0; i < 6; i++) tail[i] = ks.get(ks.size() - 6 + i).id;
                check(Arrays.equals(tail, new int[]{0x4B, 0x52, 0x4E, 0x50, 0x51, 0x4F}), name(l) + " 列表最后是方向键两层");
                check("PgUp".equals(l.find(0x4B).name) && "↑".equals(l.find(0x52).name) && "PgDn".equals(l.find(0x4E).name)
                        && "←".equals(l.find(0x50).name) && "↓".equals(l.find(0x51).name) && "→".equals(l.find(0x4F).name), name(l) + " 方向键的键名");
            }
            check("Ctrl".equals(w.find(0xE0).name) && "Super".equals(w.find(0xE3).name) && "Alt".equals(w.find(0xE2).name)
                    && "AltGr".equals(w.find(0xE6).name) && "PrtSc".equals(w.find(0x46).name) && "Ctrl".equals(w.find(0xE4).name), name(w) + " PC 的键名");
            check("⌃".equals(m.find(0xE0).name) && "⌥".equals(m.find(0xE2).name) && "⌘".equals(m.find(0xE3).name) && "⌘".equals(m.find(0xE7).name)
                    && "🌐".equals(m.find(FixedLayout.GLOBE_ID).name) && "⌃".equals(m.find(0xE4).name), name(m) + " Mac 的键名");
            check(m.find(FixedLayout.GLOBE_ID).special && near(m.find(FixedLayout.GLOBE_ID).x, 10.25f), name(m) + " 🌐 在 PrtSc 那格");
        }
        FixedLayout us = FixedLayout.build(FixedLayout.ANSI, FixedLayout.LAPTOP, FixedLayout.WIN, false, ".");
        check("Alt".equals(us.find(0xE6).name) && !us.altGr, "笔记本：没有第三层的布局右 Alt 写 Alt");
        check(!FixedLayout.build(FixedLayout.ISO, FixedLayout.LAPTOP, FixedLayout.MAC, true, ".").altGr, "笔记本 Mac 模式 altGr 一律假");
    }

    // ── 不重叠（横竖都看：方向键在同一行里竖着分两层）、在外框里 ──
    static void laptopNoOverlap() {
        for (String sh : SHAPES) for (String m : MODES) for (boolean menu : new boolean[]{false, true}) {
            FixedLayout l = laptop(sh, m);
            List<FixedLayout.Key> ks = l.keys(menu);
            boolean ok = true, inside = true;
            String bad = null;
            for (int i = 0; i < ks.size(); i++) {
                FixedLayout.Key a = ks.get(i);
                for (int r = 0; r < FixedLayout.ROWS; r++) {
                    float[] sa = a.span(r);
                    if (sa == null) continue;
                    inside &= sa[0] >= -1e-4f && sa[1] <= l.width + 1e-4f && l.segTop(a, r) >= l.rowTop(r) - 1e-4f && l.segBottom(a, r) <= l.rowBottom(r) + 1e-4f;
                    for (int j = i + 1; j < ks.size(); j++) {
                        FixedLayout.Key b = ks.get(j);
                        float[] sb = b.span(r);
                        if (sb == null) continue;
                        boolean x = Math.min(sa[1], sb[1]) - Math.max(sa[0], sb[0]) > 1e-4f;
                        boolean y = Math.min(l.segBottom(a, r), l.segBottom(b, r)) - Math.max(l.segTop(a, r), l.segTop(b, r)) > 1e-4f;
                        if (x && y) {
                            ok = false;
                            bad = a + " / " + b;
                        }
                    }
                }
            }
            check(ok, name(l) + (menu ? "（带 ⋯）" : "") + " 键不重叠（横竖都看）" + (bad == null ? "" : "：" + bad));
            check(inside, name(l) + (menu ? "（带 ⋯）" : "") + " 每个键在外框、自己那一行里");
        }
    }

    // ── 键盘范围：Windows 8 块、Mac 9 块（F 行 Ins 那格挖掉；最下一排三块：方向键以外一块、方向键上下两层各一块），不重叠，每段恰好在一块同行的范围里 ──
    static void laptopRanges() {
        for (String sh : SHAPES) for (String m : MODES) for (boolean menu : new boolean[]{false, true}) {
            FixedLayout l = laptop(sh, m);
            String what = name(l) + (menu ? "（带 ⋯）" : "");
            List<FixedLayout.Range> rs = l.ranges(menu);
            check(rs.size() == (l.mac() ? 9 : 8), what + " 范围 " + rs.size() + " 块（Windows 8、Mac 9）");
            boolean ok = true;
            for (int i = 0; i < rs.size(); i++) for (int j = i + 1; j < rs.size(); j++) ok &= !overlap(rs.get(i), rs.get(j));
            check(ok, what + " 范围之间不重叠");
            for (FixedLayout.Key k : l.keys(menu)) {
                for (int r = 0; r < FixedLayout.ROWS; r++) {
                    float[] s = k.span(r);
                    if (s == null) continue;
                    int in = 0;
                    boolean sameRow = true;
                    for (FixedLayout.Range g : rs) {
                        if (s[0] >= g.left - 1e-4f && s[1] <= g.right + 1e-4f && l.segTop(k, r) >= g.top - 1e-4f && l.segBottom(k, r) <= g.bottom + 1e-4f) {
                            in++;
                            sameRow &= g.row == r && g.cluster == FixedLayout.MAIN;
                        }
                    }
                    check(in == 1 && sameRow, what + " " + k + " 第 " + r + " 行那段恰好在一块同行的范围里（" + in + "）");
                }
            }
            check(inAny(rs, 13.47f, 0.5f) != l.mac(), what + " F 行 Ins 那格：Windows 是 Ins、在范围里；Mac 空着、不在任何范围里");
            check(inAny(rs, 0.01f, 6.49f) && inAny(rs, 14.99f, 6.49f) && inAny(rs, 6.75f, 6.49f), what + " 最下一排（到 6.5）在范围里、伸到面板边");
            // 方向键两层各一块：两层之间那条缝在 5.875 一分为二，上半归上层那块、下半归下层那块（分给谁另由 laptopArrowSeam 用真 TouchRouter 扫）
            boolean main = false, upper = false, lower = false;
            int bottomRanges = 0;
            for (FixedLayout.Range g : rs) if (g.row == 5) {
                bottomRanges++;
                main |= near(g.left, 0) && near(g.right, 12.1875f) && near(g.top, 5.25f) && near(g.bottom, 6.5f);
                upper |= near(g.left, 12.1875f) && near(g.right, 15) && near(g.top, 5.25f) && near(g.bottom, FixedLayout.LAPTOP_ARROW_SPLIT);
                lower |= near(g.left, 12.1875f) && near(g.right, 15) && near(g.top, FixedLayout.LAPTOP_ARROW_SPLIT) && near(g.bottom, 6.5f);
            }
            check(bottomRanges == 3 && main && upper && lower,
                    what + " 最下一排三块：方向键以外 0–12.1875 × 5.25–6.5、上层 12.1875–15 × 5.25–5.875、下层 12.1875–15 × 5.875–6.5（" + bottomRanges + " 块）");
            boolean keysOnlyUpper = true, keysOnlyLower = true;
            for (FixedLayout.Key k : l.keys(menu)) {
                if (k.span(5) == null) continue;
                boolean inUpper = k.x >= 12.1875f - 1e-4f && l.segBottom(k, 5) <= FixedLayout.LAPTOP_ARROW_SPLIT + 1e-4f;
                boolean inLower = k.x >= 12.1875f - 1e-4f && l.segTop(k, 5) >= FixedLayout.LAPTOP_ARROW_SPLIT - 1e-4f;
                boolean arrowUp = k.id == 0x4B || k.id == 0x52 || k.id == 0x4E, arrowDown = k.id == 0x50 || k.id == 0x51 || k.id == 0x4F;
                keysOnlyUpper &= inUpper == arrowUp;
                keysOnlyLower &= inLower == arrowDown;
            }
            check(keysOnlyUpper && keysOnlyLower, what + " 上层那块里只有 PgUp ↑ PgDn、下层那块里只有 ← ↓ →（右 Ctrl、空格这些整行高的键不在里面）");
            if (l.mac()) {
                boolean left = false, right = false;
                for (FixedLayout.Range g : rs) if (g.row == 0) {
                    left |= near(g.left, 0) && near(g.right, 13.0625f);
                    right |= near(g.left, 13.875f) && near(g.right, 15);
                }
                check(left && right, what + " Mac F 行两块：Esc–End、Del");
                check(l.blanks.size() == 1 && near(l.blanks.get(0)[1], 13.0625f) && near(l.blanks.get(0)[2], 13.875f), what + " 声明了 Ins 那格空格子");
            } else {
                check(l.blanks.isEmpty(), what + " Windows 没有空格子");
            }
        }
        check(layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.MAC).blanks.isEmpty() && layout(FixedLayout.ISO, FixedLayout.FULL, FixedLayout.MAC).blanks.isEmpty(),
                "TKL / 全尺寸不声明空格子（Ins 格本来就在段边上、不在范围里）");
    }

    // ── 方向键上下两层之间也隔 2 × gap ──
    static void laptopArrowGap() {
        for (String m : MODES) for (int[] wh : new int[][]{{480, 230}, {751, 300}, {933, 352}, {1800, 700}}) {
            FixedPanel p = new FixedPanel();
            p.setBoard(laptop(FixedLayout.ANSI, m), table("us", m));
            p.place(wh[0], wh[1], GAP, 10);
            for (int[] pair : new int[][]{{0x4B, 0x50}, {0x52, 0x51}, {0x4E, 0x4F}}) {
                int[] a = p.box(p.index(pair[0])), b = p.box(p.index(pair[1]));
                check(b[1] - a[3] == 2 * GAP && a[0] == b[0] && a[2] == b[2], m + " " + wh[0] + "×" + wh[1] + " " + Integer.toHexString(pair[0]) + " 和 "
                        + Integer.toHexString(pair[1]) + " 上下隔 2 × gap、左右对齐");
            }
            int[] sp = p.box(p.index(0x2C)), pgUp = p.box(p.index(0x4B)), left = p.box(p.index(0x50));
            check(sp[1] == pgUp[1] && sp[3] == left[3], m + " " + wh[0] + "×" + wh[1] + " 空格整行高：上沿对 PgUp、下沿对 ←");
        }
    }

    // ── 方向键周围的缝真分给谁（真 TouchRouter 和面板自己收触摸两条路，2026-11-01 审查后加的）：
    //    上下两层之间那条缝上半给上层、下半给下层（同一列），一个点都不给右 Ctrl；右 Ctrl 和方向键之间那条竖缝按中线分：左边 Ctrl、右边方向键（按层）。
    //    以前最下一排整排一块范围，nearestKey 先比竖向，整行高的右 Ctrl 竖向距离是 0，两层之间的缝全分给了它 ──
    static void laptopArrowSeam() {
        int seam = 0, side = 0, bad = 0, combos = 0;
        String first = null;
        int[] upIds = {0x4B, 0x52, 0x4E}, downIds = {0x50, 0x51, 0x4F};
        for (String sh : SHAPES) for (String m : MODES) for (boolean menu : new boolean[]{false, true}) for (boolean holed : new boolean[]{false, true})
            for (int[] wh : new int[][]{{480, 230}, {520, 250}, {751, 300}, {933, 352}, {1800, 700}}) {
                FixedLayout l = laptop(sh, m);
                FixedPanel p = new FixedPanel();
                p.setBoard(l, table("us", m));
                p.setMenuKey(menu);
                if (holed) p.setHole(FixedPanel.stickHole(l, wh[0], T));
                p.place(wh[0], wh[1], GAP, 10);
                TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
                router.setRegions(regions(p));
                combos++;
                String what = name(l) + (menu ? "（带 ⋯）" : "") + (holed ? " 让位" : "") + " " + wh[0] + "×" + wh[1];
                int split = uy(l, FixedLayout.LAPTOP_ARROW_SPLIT, wh[1]), edge = ux(l, 12.1875f, wh[0]);
                int[] up0 = p.box(p.index(upIds[0])), down0 = p.box(p.index(downIds[0])), ctrl = p.box(p.index(0xE4));
                // 上下两层之间的缝：从上层下沿到下层上沿，横着从方向键块的左边（和右 Ctrl 共用的那条线）到面板右边
                for (int y = up0[3]; y < down0[1]; y++) for (int x = edge; x < wh[0]; x++) {
                    seam++;
                    int a = id(p, route(router, x, y)), b = id(p, p.keyAt(x, y));
                    // 哪一列：过了两列之间那条分界线（左边那列的右边 + gap = 右边那列的左边 − gap）才算右边那列；正压在线上两列一样近，nearestKey 取列表里靠前的（左边那列）
                    int col = 0;
                    for (int c = 1; c < 3; c++) if (x > p.box(p.index(upIds[c]))[0] - GAP) col = c;
                    int want = y < split ? upIds[col] : downIds[col];
                    if (a != want || b != want) {
                        bad++;
                        if (first == null) first = what + " (" + x + ", " + y + ") 应为 " + Integer.toHexString(want) + "：分流 " + Integer.toHexString(a)
                                + "、面板 " + Integer.toHexString(b);
                    }
                }
                // 右 Ctrl 和方向键之间那条竖缝：整个最下一排高
                for (int y = ctrl[1]; y < ctrl[3]; y++) for (int x = ctrl[2]; x < up0[0]; x++) {
                    side++;
                    int a = id(p, route(router, x, y)), b = id(p, p.keyAt(x, y));
                    int want = x < edge ? 0xE4 : y < split ? upIds[0] : downIds[0];
                    if (a != want || b != want) {
                        bad++;
                        if (first == null) first = what + " 竖缝 (" + x + ", " + y + ") 应为 " + Integer.toHexString(want) + "：分流 " + Integer.toHexString(a)
                                + "、面板 " + Integer.toHexString(b);
                    }
                }
            }
        check(bad == 0 && seam > 0 && side > 0, "笔记本方向键周围的缝：两层之间 " + seam + " 个点、右 Ctrl 旁竖缝 " + side + " 个点，" + bad + " 个分错"
                + (first == null ? "" : "，比如 " + first));
        System.err.println("  笔记本方向键周围的缝：" + combos + " 种（形状 × 模式 × ⋯ × 让位 × 窗口），两层之间 " + seam + " 个点、右 Ctrl 旁竖缝 " + side + " 个点");
    }

    // ── 钉死几个点（真 TouchRouter 和面板自己收触摸两条路）──
    static void laptopPins() {
        for (String m : MODES) for (int[] wh : new int[][]{{751, 300}, {480, 230}}) {
            FixedLayout l = laptop(FixedLayout.ANSI, m);
            int w = wh[0], h = wh[1];
            String what = name(l) + " " + w + "×" + h;
            for (boolean menu : new boolean[]{false, true}) {
                FixedPanel p = new FixedPanel();
                p.setBoard(l, table("us", m));
                p.setMenuKey(menu);
                p.place(w, h, GAP, 10);
                TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
                router.setRegions(regions(p));
                float[][] pts = {{13.59375f, 5.5625f, 0x52}, {13.59375f, 6.1875f, 0x51}, {12.65625f, 5.5625f, 0x4B}, {12.65625f, 6.1875f, 0x50},
                        {14.53125f, 5.5625f, 0x4E}, {14.53125f, 6.1875f, 0x4F}, {0.1f, 6.4f, 0xE0}, {1.5f, 5.8f, menu ? FixedLayout.MENU_ID : 0xE0}};
                for (float[] q : pts) {
                    int x = ux(l, q[0], w), y = uy(l, q[1], h);
                    int a = id(p, route(router, x, y)), b = id(p, p.keyAt(x, y));
                    check(a == (int) q[2] && b == (int) q[2], what + (menu ? "（带 ⋯）" : "") + " (" + q[0] + ", " + q[1] + ") 是 " + Integer.toHexString((int) q[2])
                            + "（分流 " + Integer.toHexString(a) + "、面板 " + Integer.toHexString(b) + "）");
                }
                boolean space = true;
                for (float yu = 5.3f; yu <= 6.451f; yu += 0.05f) {
                    int x = ux(l, 6.75f, w), y = uy(l, yu, h);
                    space &= id(p, route(router, x, y)) == 0x2C && id(p, p.keyAt(x, y)) == 0x2C;
                }
                check(space, what + (menu ? "（带 ⋯）" : "") + " 空格正中竖着的每一点都是空格（没有横穿空格的缝）");
                int x = ux(l, 13.46875f, w), y = uy(l, 0.5f, h);
                if (l.mac()) {
                    check(router.hit(x, y) == null && p.keyAt(x, y) == -1, what + " Mac F 行 Ins 那格正中：两条路都是什么都没有");
                } else {
                    check(id(p, route(router, x, y)) == 0x49 && id(p, p.keyAt(x, y)) == 0x49, what + " Windows F 行 Ins 那格是 Ins");
                }
            }
        }
    }

    // ── 特殊键（⋯、🌐，REGION_BUTTON）只有一段：跨段抬起不算点击（不变量）。三种尺寸都扫 ──
    static void specialOneSegment() {
        int combos = 0;
        for (String sz : FixedLayout.SIZES_DESC) for (String sh : SHAPES) for (String m : MODES) for (boolean menu : new boolean[]{false, true})
            for (boolean holed : new boolean[]{false, true}) for (int[] wh : new int[][]{{933, 352}, {600, 230}, {480, 230}, {1800, 700}}) {
                FixedLayout l = layout(sh, sz, m);
                FixedPanel p = new FixedPanel();
                p.setBoard(l, table("us", m));
                p.setMenuKey(menu);
                if (holed) p.setHole(FixedPanel.stickHole(l, wh[0], T));
                p.place(wh[0], wh[1], GAP, 10);
                combos++;
                boolean ok = true;
                int specials = 0;
                for (int i = 0; i < p.keys().size(); i++) {
                    if (!p.keys().get(i).special) continue;
                    specials++;
                    int n = 0;
                    for (FixedPanel.Seg s : p.segs()) if (s.key == i) n++;
                    ok &= n == 1;
                }
                check(ok && specials == (menu ? 1 : 0) + (l.mac() ? 1 : 0), name(l) + (menu ? " ⋯" : "") + (holed ? " 让位" : "") + " " + wh[0] + "×" + wh[1]
                        + " 每个特殊键恰好一段（" + specials + " 个）");
            }
        System.err.println("  特殊键只有一段：" + combos + " 种（尺寸 × 形状 × 模式 × ⋯ × 让位 × 窗口）");
    }

    // ── ⋯ / 🌐：按下、在键里挪到靠下沿 / 靠左边、抬起，真 TouchRouter 报 BUTTON_TAP（MainActivity 只认它）──
    static void buttonTap() {
        for (String sz : FixedLayout.SIZES_DESC) for (String m : MODES) for (int[] wh : new int[][]{{751, 300}, {480, 230}, {933, 352}}) {
            FixedLayout l = layout(FixedLayout.ANSI, sz, m);
            FixedPanel p = new FixedPanel();
            p.setBoard(l, table("us", m));
            p.setMenuKey(true);
            p.place(wh[0], wh[1], GAP, 10);
            TouchRouterTest.Rec rec = new TouchRouterTest.Rec();
            TouchRouter router = new TouchRouter(rec);
            router.setConfig(new GestureEngine.Config(), 1);
            router.setRegions(regions(p));
            for (int i = 0; i < p.keys().size(); i++) {
                if (!p.keys().get(i).special) continue;
                FixedPanel.Seg seg = null;
                for (FixedPanel.Seg s : p.segs()) if (s.key == i) seg = s;
                if (seg == null) continue;
                float cx = (seg.left + seg.right) / 2f, cy = (seg.top + seg.bottom) / 2f;
                for (float[] to : new float[][]{{seg.left + 1, seg.bottom - 1}, {seg.left + 1, cy}, {cx, seg.top}}) {
                    rec.log.clear();
                    router.down(0, cx, cy, 0);
                    router.move(0, to[0], to[1]);
                    router.up(0, 20);
                    check(rec.log.contains("b" + i + ":" + Proto.BUTTON_TAP), name(l) + " " + wh[0] + "×" + wh[1] + " " + p.keys().get(i)
                            + " 挪到 (" + to[0] + ", " + to[1] + ") 抬起是点击：" + rec.log);
                    check(p.keyAt(cx, cy) == i && p.keyAt(to[0], to[1]) == i, name(l) + " " + p.keys().get(i) + " 面板自己收触摸：按下、抬起的点都是它");
                }
            }
        }
    }

    /** caps 里笔记本多看两样：最下一排除方向键外外框都是整行高（1.25 行）、让位时空格两个键帽。 */
    static void laptopCapShapes(FixedPanel p, boolean holed, String what) {
        FixedLayout l = p.layout();
        int top = FixedPanel.px(l.rowTop(5), p.height(), l.height) + GAP, bottom = FixedPanel.px(l.height, p.height(), l.height) - GAP;
        boolean ok = true;
        for (int i = 0; i < p.keys().size(); i++) {
            FixedLayout.Key k = p.keys().get(i);
            if (k.row != 5 || !Float.isNaN(k.y0)) continue;
            int[] b = p.box(i);
            ok &= b[1] == top && b[3] == bottom;
        }
        check(ok, what + " 最下一排（方向键以外）的键帽整行高");
        if (holed && p.hole() != null) check(p.capBoxes(p.index(0x2C)).size() == 2, what + " 让位时空格两个键帽");
    }

    // ── 键帽的字、主次键帽 ──
    static void laptopGlyphs() {
        FixedLegends.Table t = table("de", FixedLayout.WIN);
        FixedPanel p = new FixedPanel();
        p.setBoard(FixedLayout.build(FixedLayout.ISO, FixedLayout.LAPTOP, FixedLayout.WIN, t.hasThird(), ","), t);
        p.place(751, 300, GAP, 10);
        eqs(glyphs(p, 0x46, 0), "中PrtSc", "笔记本 PrtSc 键帽");
        eqs(glyphs(p, 0x4B, 0), "中PgUp", "笔记本 PgUp 键帽");
        eqs(glyphs(p, 0xE6, 0), "中AltGr", "笔记本 win:de 右 Alt 写 AltGr");
        eqs(glyphs(p, 0x14, 0), "中q 角@", "笔记本 win:de q：一个字母 + 角上 @（第 1–4 行同 TKL）");
        for (int u : new int[]{0x46, 0x4B, 0x4E, 0x52, 0x4A, 0x4D, 0x49, 0x4C, 0x29, 0x3A, 0xE0, 0xE4}) check(p.functionKey(p.index(u)), "笔记本功能键帽：" + Integer.toHexString(u));
        for (int u : new int[]{0x2C, 0x04, 0x1E, 0x64}) check(!p.functionKey(p.index(u)), "笔记本普通键帽：" + Integer.toHexString(u));
        check(p.preview(p.index(0x52), 0) == null && p.preview(p.index(0x46), 0) == null, "笔记本方向键、PrtSc 不放大");
    }

    // ── 逐级缩：挑哪一级（KeyboardPlan.fixedLevel）──
    static void levels() {
        FixedLayout full = layout(FixedLayout.ANSI, FixedLayout.FULL, FixedLayout.WIN), tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN),
                lap = laptop(FixedLayout.ANSI, FixedLayout.WIN);
        check(KeyboardPlan.fixedFits(lap, 480, T) && !KeyboardPlan.fixedFits(lap, 479, T), "笔记本一格 32 起（480 / 15 = 32）");
        List<FixedLayout> fromFull = Arrays.asList(full, tkl, lap), fromTkl = Arrays.asList(tkl, lap), fromLap = Arrays.asList(lap);
        int[][] wantFull = {{720, 0}, {719, 1}, {584, 1}, {583, 2}, {480, 2}, {479, -1}, {2600, 0}};
        for (int[] q : wantFull) check(KeyboardPlan.fixedLevel(fromFull, q[0], T) == q[1], "选全尺寸 " + q[0] + " 宽 → 第 " + q[1] + " 级（得到 " + KeyboardPlan.fixedLevel(fromFull, q[0], T) + "）");
        check(KeyboardPlan.fixedLevel(fromTkl, 2000, T) == 0 && KeyboardPlan.fixedLevel(fromTkl, 583, T) == 1 && KeyboardPlan.fixedLevel(fromTkl, 479, T) == -1,
                "选 TKL：宽窗口照旧 TKL（只缩不涨）、583 缩成笔记本、479 暂用豆花布局");
        check(KeyboardPlan.fixedLevel(fromLap, 2000, T) == 0 && KeyboardPlan.fixedLevel(fromLap, 480, T) == 0 && KeyboardPlan.fixedLevel(fromLap, 479, T) == -1,
                "选笔记本：放不下直接豆花布局");
        check(KeyboardPlan.fixedLevel(new ArrayList<>(), 2000, T) == -1 && KeyboardPlan.fixedLevel(fromFull, 0, T) == -1 && KeyboardPlan.fixedLevel(null, 2000, T) == -1,
                "ladder 是空的（数据读不出来）/ 还没量好：-1");
        int widths = 0;
        boolean ok = true;
        for (List<FixedLayout> ladder : Arrays.asList(fromFull, fromTkl, fromLap)) {
            for (int w = 300; w <= 2600; w++) {
                widths++;
                int lv = KeyboardPlan.fixedLevel(ladder, w, T);
                for (int j = 0; j < ladder.size(); j++) {
                    boolean fits = KeyboardPlan.fixedFits(ladder.get(j), w, T);
                    if (j < lv || lv < 0) ok &= !fits;   // 比它大的每一级都放不下（都放不下才 -1）
                    if (j == lv) ok &= fits;
                }
            }
        }
        check(ok, "逐级缩扫了 " + widths + " 种宽度：挑的是第一个放得下的");
    }

    // ── 笔记本没调过大小的默认位置（自然比例按 6.5 行高）──
    static void laptopDefaults() {
        FixedLayout lap = laptop(FixedLayout.ANSI, FixedLayout.WIN);
        float natural = 15 / (6.5f * KeyboardPlan.FIXED_ROW_ASPECT);
        KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 751, 475, null, lap, T);
        int kh = p.keyboard[3] - p.keyboard[1];
        check(p.keyboard[0] == 0 && p.keyboard[2] == 751 && p.keyboard[3] == 475 && Math.abs(kh - 751 / natural) <= 1, "笔记本 751×475：满宽、贴底、高按自然比例（6.5 行）：" + kh);
        check(p.padSide == KeyboardPlan.PAD_TOP && p.menuArea != null, "笔记本 751×475：触控板在上面、⋯ 在触控板上");
        KeyboardPlan.Plan wide = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 1600, 400, null, lap, T);
        check(wide.keyboard[0] == 0 && wide.keyboard[3] == 400 && Math.abs(wide.keyboard[2] - natural * 400) <= 1 && wide.padSide == KeyboardPlan.PAD_RIGHT,
                "笔记本宽窗口：满高、按自然宽度靠左，右边给触控板");
        KeyboardPlan.Plan stick = KeyboardPlan.planFixed(KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, 751, 475, null, lap, T);
        check(stick.menuArea == null && stick.pad == null, "笔记本小红点：没有触控板，⋯ 在左下角的槽里");
        defaultsScan(LAPTOP_ONLY, LAPTOP_LABEL);
    }

    // ── 笔记本 + 小红点：一格 ≥ 32dp 就一定有三个鼠标键、中键正对红点（协调者 2026-11-01 定：空格两段最短 0.75 份）──
    static void laptopStickHole() {
        for (String sh : SHAPES) for (String m : MODES) {
            FixedLayout l = laptop(sh, m);
            String n = name(l);
            int bad = 0;
            String first = null;
            for (int pw = 480; pw <= 2600; pw++) {
                int[] hole = FixedPanel.stickHole(l, pw, T);
                float unit = pw / 15f;
                int cx = FixedPanel.px(l.stickU(), pw, l.width), left = FixedPanel.px(4.25f, pw, l.width), right = FixedPanel.px(9.25f, pw, l.width);
                String why = null;
                if (hole == null) why = "让不出空位";
                else if (Math.abs(hole[0] + hole[1] - 2 * cx) > 1) why = "空位不以红点为中线";
                else if (hole[1] - hole[0] < T.holeWidth(T.minKey)) why = "空位放不下三个 minKey 的键";
                else if (hole[0] - left < unit * FixedPanel.LAPTOP_SPACE_MIN - 1 || right - hole[1] < unit * FixedPanel.LAPTOP_SPACE_MIN - 1) why = "空格不到 0.75 份";
                else if (pw >= 720 && hole[1] - hole[0] < T.holeWidth(T.keyW)) why = "720 宽起三个键该各 ≥ keyW";
                else if (pw >= 545 && (hole[0] - left < unit - 1 || right - hole[1] < unit - 1)) why = "545 宽起空格该照 TKL 的规则 ≥ 1 份";
                if (why != null && bad++ == 0) first = pw + "：" + why + " " + Arrays.toString(hole);
            }
            check(bad == 0, n + " 面板 480–2600 宽每一种都让得出空位、对称、键 ≥ minKey、空格 ≥ 0.75 份" + (first == null ? "" : "（" + bad + " 种不对，比如 " + first + "）"));
            int[] h720 = FixedPanel.stickHole(l, 720, T), h719 = FixedPanel.stickHole(l, 719, T), h500 = FixedPanel.stickHole(l, 500, T);
            check(h720 != null && h720[1] - h720[0] >= T.holeWidth(T.keyW) && h719 != null && h719[1] - h719[0] < T.holeWidth(T.keyW),
                    n + " 720 宽时三个键各 ≥ 44、719 宽时不是（同 TKL 的规则：先缩空格到 1 份）");
            int left500 = FixedPanel.px(4.25f, 500, 15);
            check(h500 != null && h500[1] - h500[0] == T.holeWidth(T.minKey) && h500[0] - left500 < 500 / 15f && h500[0] - left500 >= 500 / 15f * 0.75f,
                    n + " 500 宽：三个键各 minKey、空格缩到 0.75–1 份之间（笔记本多的那一步）");
            FixedLayout tkl = layout(sh, FixedLayout.TKL, m);
            check(FixedPanel.stickHole(tkl, 480, T) == null && FixedPanel.stickHole(l, 480, T) != null,
                    n + " 面板 480 宽：TKL 照旧让不出、笔记本让得出（多的那一步只给笔记本）");
        }
        // 整个摆法：页宽一格 ≥ 32dp ⇒ 有三个鼠标键、中键正对红点（默认位置 + 随机存过的位置，后者经 fitFixedRect 撑到一格 32dp）
        Random rnd = new Random(20261101);
        int plans = 0, bad = 0;
        String first = null;
        for (String sh : SHAPES) for (String m : MODES) {
            FixedLayout l = laptop(sh, m);
            for (int w = 480; w <= 2600; w += 7) for (int h : new int[]{150, 200, 230, 300, 475, 704, 900}) for (int k = 0; k < 3; k++) {
                float[] saved = k == 0 ? null : new float[]{rnd.nextFloat() * 0.9f, rnd.nextFloat() * 0.5f, 0, 1};
                if (saved != null) saved[2] = Math.min(1, saved[0] + 0.02f + rnd.nextFloat() * 0.6f);
                KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, w, h, saved, l, T);
                plans++;
                int pw = p.keyboard[2] - p.keyboard[0], ph = p.keyboard[3] - p.keyboard[1];
                String why = null;
                if (p.keys == null || p.hole == null || p.middleKey == null) why = "没有三个鼠标键";
                else {
                    int[] pt = FixedPanel.stickPoint(l, pw, ph, GAP);
                    int mid = (p.middleKey[0] + p.middleKey[1]) / 2 - p.keyboard[0];
                    int a = p.middleKey[0] - p.keys[0], b = p.middleKey[1] - p.middleKey[0], c = p.keys[2] - p.middleKey[1];
                    if (Math.abs(mid - pt[0]) > 1) why = "中键没正对红点";
                    else if (Math.min(a, Math.min(b, c)) < T.minKey + 2 * T.capGap - 1) why = "鼠标键比 minKey 窄：" + a + " / " + b + " / " + c;
                    else if (pw / 15f < T.minKey - 0.1f) why = "面板一格不到 minKey：" + pw;
                }
                if (why != null && bad++ == 0) first = name(l) + " " + w + "×" + h + " " + Arrays.toString(saved) + "：" + why;
            }
        }
        check(bad == 0, "笔记本 + 小红点 + 一格 ≥ 32dp ⇒ 有三个鼠标键、中键正对红点：扫了 " + plans + " 种" + (first == null ? "" : "，" + bad + " 种不对，比如 " + first));
        System.err.println("  笔记本 + 小红点的三个鼠标键：" + plans + " 种摆法（页宽 480–2600 × 高 × 默认 / 随机存过的位置）都有");
    }

    // ── 存的位置在窄窗口里撑到一格 32dp（KeyboardPlan.fitFixedRect），存的那份不改 ──
    static void fitFixed() {
        FixedLayout tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN), lap = laptop(FixedLayout.ANSI, FixedLayout.WIN);
        float[] saved = {0.1f, 0.2f, 0.9f, 1};
        float[] r = KeyboardPlan.fitFixedRect(saved, tkl, 1000, T);
        check(Arrays.equals(r, saved) && r != saved, "1000 宽 TKL：够宽的照用（新数组）");
        float[] c = {0.2f, 0.3f, 0.5f, 1}, c0 = c.clone();
        r = KeyboardPlan.fitFixedRect(c, lap, 500, T);
        check(Math.abs(r[0]) < 1e-5f && Math.abs(r[1] - 0.3f) < 1e-6f && Math.abs(r[2] - 0.96f) < 1e-4f && r[3] == 1 && Arrays.equals(c, c0),
                "500 宽笔记本存 {0.2, 0.3, 0.5, 1}：按中线撑到 0.96、挪进 [0, 1]，高不动，存的没改：" + Arrays.toString(r));
        r = KeyboardPlan.fitFixedRect(new float[]{0.7f, 0, 0.9f, 1}, lap, 500, T);
        check(Math.abs(r[0] - 0.04f) < 1e-4f && r[2] == 1, "靠右的撑开后贴右边：" + Arrays.toString(r));
        KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, 500, 300, c, lap, T);
        check(Arrays.equals(p.rect, KeyboardPlan.fitFixedRect(c, lap, 500, T)) && (p.keyboard[2] - p.keyboard[0]) / 15f >= T.minKey - 0.1f && Arrays.equals(c, c0),
                "planFixed 用撑过的那份（Plan.rect）、面板一格 ≥ minKey、存的没改");
        Random rnd = new Random(7);
        int n = 0, stretched = 0, bad = 0;
        String first = null;
        for (String sz : FixedLayout.SIZES_DESC) {
            FixedLayout l = layout(FixedLayout.ISO, sz, FixedLayout.MAC);
            for (int w = 300; w <= 2600; w += 11) {
                if (!KeyboardPlan.fixedFits(l, w, T)) continue;
                for (int k = 0; k < 6; k++) {
                    float a = rnd.nextFloat(), b = rnd.nextFloat();
                    float[] s = {Math.min(a, b), rnd.nextFloat() * 0.5f, Math.max(a, b) + 0.001f, 0.6f + rnd.nextFloat() * 0.4f};
                    s[2] = Math.min(1, s[2]);
                    float[] f = KeyboardPlan.fitFixedRect(s, l, w, T);
                    n++;
                    float min = KeyboardPlan.fixedMinWidth(l, w, T);
                    boolean ok;
                    if (s[2] - s[0] >= min) {
                        ok = Arrays.equals(f, s);
                    } else {
                        stretched++;
                        ok = f[0] >= 0 && f[2] <= 1 && f[1] == s[1] && f[3] == s[3] && (Math.round(f[2] * w) - Math.round(f[0] * w)) / l.width >= T.minKey - 0.1f;
                    }
                    if (!ok && bad++ == 0) first = name(l) + " " + w + " " + Arrays.toString(s) + " → " + Arrays.toString(f);
                }
            }
        }
        check(bad == 0 && stretched > 0, "fitFixedRect 扫了 " + n + " 种（撑过的 " + stretched + "）：撑过的一格都 ≥ minKey、没撑的和存的一样" + (first == null ? "" : "，比如 " + first));
    }

    // ── 逐级缩的状态（FixedSizing）──
    static void sizing() {
        FixedLayout full = layout(FixedLayout.ANSI, FixedLayout.FULL, FixedLayout.WIN), tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN),
                lap = laptop(FixedLayout.ANSI, FixedLayout.WIN);
        List<FixedLayout> ladder = Arrays.asList(full, tkl, lap);
        float[] A = {0.1f, 0.1f, 0.9f, 1}, C = {0.2f, 0.3f, 0.5f, 1}, D = {0.05f, 0, 0.95f, 1};
        Map<String, float[]> saved = new HashMap<>();
        saved.put(FixedLayout.FULL, A.clone());
        saved.put(FixedLayout.LAPTOP, C.clone());
        FixedSizing z = new FixedSizing();
        check(z.level() == -1 && z.layout() == null && z.size() == null && z.planInput() == null && z.beginDrag() == null && z.endDrag() == null && z.reset() == null,
                "sizing：一开始什么都没显示");
        z.setLadder(ladder);
        z.setSaved(saved);
        saved.get(FixedLayout.FULL)[0] = 0.5f;   // 改传进去的 map 不影响它（存的是拷贝）
        // 宽度来回变
        check(z.update(true, 800, T) && z.level() == 0 && FixedLayout.FULL.equals(z.size()) && Arrays.equals(z.planInput(), A), "sizing：800 宽 → 全尺寸，用存的 A");
        check(!z.update(true, 800, T), "sizing：同一宽度再量：没换");
        check(z.update(true, 700, T) && z.level() == 1 && z.planInput() == null, "sizing：700 宽 → TKL（没存过，默认位置）");
        z.planned(D);
        check(Arrays.equals(z.work(), D) && z.planInput() == null, "sizing：默认位置抄进工作副本、planInput 仍是 null");
        check(z.update(true, 500, T) && z.level() == 2 && z.layout() == lap && Arrays.equals(z.planInput(), C), "sizing：500 宽 → 笔记本，用存的 C");
        check(z.update(true, 470, T) && z.level() == -1 && z.layout() == null && z.size() == null && z.planInput() == null, "sizing：470 宽 → 豆花布局");
        check(!z.update(false, 2000, T) && z.level() == -1, "sizing：没选实体键盘：-1（和上次一样，没换）");
        check(z.update(true, 800, T) && Arrays.equals(z.planInput(), A), "sizing：回到 800 宽又是全尺寸 A");
        // 只点一下（从撑开过的那份开始，没拖动就换回存的那份、不存）
        z.update(true, 500, T);
        float[] P = KeyboardPlan.fitFixedRect(C, lap, 500, T);
        z.planned(P);
        check(!Arrays.equals(P, C), "sizing：500 宽时存的 C 被撑开过");
        float[] w = z.beginDrag();
        check(w == z.work() && Arrays.equals(w, P) && z.dragging(), "sizing：从显示着的（撑开过的）那份开始拖");
        check(z.endDrag() == null && Arrays.equals(z.planInput(), C) && !z.dragging(), "sizing：没拖动：不存、换回 C");
        // 拖了
        z.planned(P);
        w = z.beginDrag();
        w[0] = 0.01f;
        FixedSizing.Save sv = z.endDrag();
        check(sv != null && FixedLayout.LAPTOP.equals(sv.size) && Arrays.equals(sv.rect, w) && Arrays.equals(z.savedFor(FixedLayout.LAPTOP), w)
                && Arrays.equals(z.planInput(), w), "sizing：拖了：存进笔记本那份，下次 planInput 就是它");
        // 从默认位置拖了一下又没动：仍是默认位置
        z.update(true, 700, T);
        z.planned(D);
        z.beginDrag();
        check(z.planInput() != null, "sizing：拖的途中是用户的（量尺寸时不换回默认位置）");
        check(z.endDrag() == null && z.planInput() == null && z.savedFor(FixedLayout.TKL) == null, "sizing：默认位置点一下没拖：还是默认位置、不存");
        // 拖动中换级
        z.planned(D);
        w = z.beginDrag();
        w[2] = 0.8f;
        check(z.update(true, 800, T), "sizing：拖动中页宽变了、换了级");
        check(z.endDrag() == null && z.savedFor(FixedLayout.TKL) == null && Arrays.equals(z.planInput(), A), "sizing：拖动中换级：这一下作废，TKL 没存、全尺寸还是 A");
        // 拖动中 setSaved / setLadder：作废
        z.planned(A);
        w = z.beginDrag();
        w[0] = 0.3f;
        Map<String, float[]> s2 = new HashMap<>();
        s2.put(FixedLayout.FULL, A.clone());
        z.setSaved(s2);
        check(z.endDrag() == null && Arrays.equals(z.savedFor(FixedLayout.FULL), A) && Arrays.equals(z.planInput(), A), "sizing：拖动中换了存的那几份：作废");
        z.planned(A);
        w = z.beginDrag();
        w[0] = 0.3f;
        z.setLadder(ladder);
        check(z.endDrag() == null && Arrays.equals(z.savedFor(FixedLayout.FULL), A), "sizing：拖动中换了键盘：作废");
        check(z.update(true, 800, T) && z.level() == 0, "sizing：换了键盘后同一宽度也报换了（菜单跟着换字）");
        // 恢复默认
        z.setSaved(saved);   // full: 改过的 0.5（拷贝时已经是改过的），laptop: C
        z.update(true, 500, T);
        check(FixedLayout.LAPTOP.equals(z.reset()) && z.planInput() == null && z.savedFor(FixedLayout.LAPTOP) == null && z.savedFor(FixedLayout.FULL) != null,
                "sizing：恢复默认只清显示着的笔记本那份");
        z.update(false, 500, T);
        check(z.reset() == null && z.beginDrag() == null, "sizing：没显示时恢复默认 / 拖都是空的");
        // 随机序列（照 KeyboardScreen 的用法：量尺寸 → planFixed → planned；拖的时候只在没作废时改工作副本）
        Random rnd = new Random(1101);
        Map<String, float[]> model = new HashMap<>();
        FixedSizing y = new FixedSizing();
        y.setLadder(ladder);
        String begun = null;
        int steps = 0, saves = 0, bad = 0;
        String first = null;
        // 权重：量尺寸 2、按下 2、拖 3、抬起 2、换存的那几份 1、恢复默认 1、换键盘 1（拖动多一点，才有足够多的"拖完存下"）
        int[] kinds = {0, 0, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7};
        for (int i = 0; i < 6000; i++) {
            steps++;
            String why = null;
            switch (kinds[rnd.nextInt(kinds.length)]) {
                case 0: {   // 量尺寸
                    int pw = 300 + rnd.nextInt(2300);
                    boolean changed = y.update(rnd.nextInt(6) != 0, pw, T);
                    if (changed) begun = null;
                    if (y.level() >= 0) {
                        KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, pw, 300 + rnd.nextInt(500), y.planInput(), y.layout(), T);
                        y.planned(p.rect);
                    }
                    break;
                }
                case 2: {
                    float[] wk = y.beginDrag();
                    begun = wk == null ? null : y.size();
                    if (wk == null && y.level() >= 0) why = "显示着却拖不了";
                    break;
                }
                case 3:
                    if (y.dragging()) {   // KeyboardScreen：作废以后 handle 清了、不再改
                        float[] wk = y.work();
                        wk[0] = Math.max(0, Math.min(0.5f, wk[0] + (rnd.nextFloat() - 0.5f) * 0.1f));
                    }
                    break;
                case 4: {
                    String sizeNow = y.size();
                    FixedSizing.Save s = y.endDrag();
                    if (s != null) {
                        saves++;
                        if (!s.size.equals(begun) || !s.size.equals(sizeNow)) why = "存进了别的尺寸：" + s.size + "（按下时 " + begun + "、现在 " + sizeNow + "）";
                        model.put(s.size, s.rect.clone());
                    }
                    begun = null;
                    break;
                }
                case 5: {
                    Map<String, float[]> m = new HashMap<>();
                    for (String sz : FixedLayout.SIZES_DESC) if (rnd.nextBoolean()) m.put(sz, new float[]{rnd.nextFloat() * 0.4f, 0, 0.6f + rnd.nextFloat() * 0.4f, 1});
                    y.setSaved(m);
                    model.clear();
                    for (Map.Entry<String, float[]> e : m.entrySet()) model.put(e.getKey(), e.getValue().clone());
                    begun = null;
                    break;
                }
                case 6: {
                    String r = y.reset();
                    if (r != null) model.remove(r);
                    begun = null;
                    break;
                }
                default: {
                    if (rnd.nextInt(4) == 0) {
                        y.setLadder(rnd.nextBoolean() ? ladder : Arrays.asList(tkl, lap));
                        begun = null;
                    }
                    break;
                }
            }
            float[] in = y.planInput();
            if (why == null && in != null && !y.dragging() && !Arrays.equals(in, model.get(y.size()))) why = "planInput 既不是存的那份、也没在拖";
            for (String sz : FixedLayout.SIZES_DESC) {
                if (why == null && !Arrays.equals(y.savedFor(sz), model.get(sz))) why = sz + " 存的那份和预期不一样";
            }
            if (why != null && bad++ == 0) first = "第 " + i + " 步：" + why;
        }
        check(bad == 0 && saves > 0, "sizing 随机序列 " + steps + " 步（存了 " + saves + " 次）" + (first == null ? "" : "：" + first));
        System.err.println("  逐级缩状态随机序列：" + steps + " 步，存了 " + saves + " 次");
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
