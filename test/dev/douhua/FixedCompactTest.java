package dev.douhua;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 实体键盘第 3 批（2026-11-01，方案 design-batch3 §2 / §4 / §5 几何那部分）的离线测试：75%、65%、苹果紧凑三种尺寸的几何（键数、每行铺满、行表、
 * 方向键对齐、⋯ 槽、范围）、哪些组合拼得出（苹果紧凑只有 Mac 模式、ANSI / ISO）、逐级缩的梯子、Fn 键和 Fn 层的数据（每个码都有名字或字、
 * 和底层不一样、不是修饰键）。外加 FixedLayoutTest 那几组通用的扫描（摆到像素、真 TouchRouter 和 FixedPanel.keyAt 逐点一致、键帽齐全连 Fn 层、
 * 特殊键只有一段、按钮跨段抬起照样是点击、默认位置、小红点 + 切口 + 三个鼠标键、小红点分流），每组单独打一行。
 * 计数借 FixedLayoutTest 的（扫描在那边），这里最后打一行总数。尺寸按 1dp = 1px 算。从仓库根目录跑（读 res/raw 的键帽数据）。
 * Fn 层的机制（协议 21）：规矩本身（按住 / 点一下 / 锁、按下那一刻定码、两根手指、收尾）在 KeyHoldsTest；这里查真的实体键盘上三条触摸路径一样
 * （{@link #fnPaths}：报的区域、面板自己收触摸、经 key() 的那一路同一个 id、同样的报文），Fn 层显示着时的键帽在 FixedLayoutTest.fnCaps。
 */
public final class FixedCompactTest {
    private static final int GAP = 2;
    /** 同 FixedLayoutTest.T（KeyboardScreen 的 baseSizes，1dp = 1px）。 */
    private static final KeyboardPlan.Sizes T = new KeyboardPlan.Sizes(8, 96, 32, 64, 32, 3, 10, 44, 48, 4, 56, GAP, 20);
    private static final String[] NEW = {FixedLayout.P75, FixedLayout.P65, FixedLayout.APPLE};
    private static final String[] SIXTEEN = {FixedLayout.P75, FixedLayout.P65};
    private static final String[] APPLE_ONLY = {FixedLayout.APPLE};
    private static final String[] SHAPES = FixedLayout.SHAPES;
    private static final String[] MODES = {FixedLayout.WIN, FixedLayout.MAC};
    private static final String LABEL = "（75% / 65% / 苹果紧凑）";

    public static void main(String[] args) throws IOException {
        FixedLayoutTest.load();
        counts();
        rowTables();
        rowsFill();
        bottomAndZRows();
        sameAsTkl();
        arrowsAligned();
        noOverlap();
        menuSlot();
        rangesU();
        availability();
        ladders();
        levels();
        fnLayer();
        fnNames();
        glyphs();
        appleArrowSeam();
        stickHoles();
        fnPaths();
        // ── FixedLayoutTest 那几组通用的扫描，三种新尺寸另跑一次 ──
        FixedLayoutTest.placement(SHAPES, NEW, new int[][]{{464, 230}, {512, 230}, {600, 260}, {751, 300}, {933, 352}, {1800, 700}});
        FixedLayoutTest.routing(SHAPES, NEW, new int[][]{{512, 230}, {751, 283}}, LABEL, false);
        FixedLayoutTest.caps(NEW, new int[][]{{933, 352}, {512, 230}}, LABEL);
        FixedLayoutTest.specialOneSegment(NEW, SHAPES, LABEL);
        FixedLayoutTest.buttonTap(NEW);
        FixedLayoutTest.defaultsScan(NEW, LABEL);
        FixedLayoutTest.stickPlan(SHAPES, NEW, new int[][]{{464, 230}, {512, 230}, {560, 270}, {751, 475}, {933, 704}, {933, 340}, {1200, 500}, {1800, 800}}, LABEL);
        FixedLayoutTest.stickRouting(SHAPES, NEW, new int[][]{{751, 475}, {933, 704}, {1200, 500}}, LABEL);
        System.err.println("实体键盘第 3 批（75% / 65% / 苹果紧凑 / Fn 层的数据）：" + FixedLayoutTest.checks + " 项检查，" + FixedLayoutTest.failures + " 项失败");
        if (FixedLayoutTest.failures > 0) System.exit(1);
    }

    static FixedLayout layout(String shape, String size, String mode) {
        return FixedLayoutTest.layout(shape, size, mode);
    }

    static String name(FixedLayout l) {
        return FixedLayoutTest.name(l);
    }

    /** 拼得出的 形状 × 尺寸 × 模式 都跑一遍。 */
    interface Each {
        void run(FixedLayout l);
    }

    static void each(String[] sizes, Each e) {
        for (String sz : sizes) for (String sh : SHAPES) for (String m : MODES) {
            if (FixedLayout.available(sh, sz, m)) e.run(layout(sh, sz, m));
        }
    }

    static boolean has(FixedLayout l, int id) {
        return l.find(id) != null;
    }

    // ── 键数、用法码各一次、有哪些没有哪些 ──
    static void counts() {
        Map<String, int[]> want = new LinkedHashMap<>();   // 尺寸 → {ANSI, ISO, ABNT2}，两种模式一样（苹果紧凑只有 Mac、没有 ABNT2）
        want.put(FixedLayout.P75, new int[]{83, 84, 85});
        want.put(FixedLayout.P65, new int[]{68, 69, 70});
        want.put(FixedLayout.APPLE, new int[]{77, 78, -1});
        int[] combos = {0};
        each(NEW, l -> {
            combos[0]++;
            String n = name(l);
            int w = want.get(l.size)[Arrays.asList(SHAPES).indexOf(l.shape)];
            check(l.keys.size() == w, n + " 键数 " + l.keys.size() + "（应为 " + w + "）");
            check(l.keys(true).size() == l.keys.size() + 1, n + " 槽里放 ⋯ 时多一个键");
            Set<Integer> ids = new HashSet<>();
            boolean dup = false;
            for (FixedLayout.Key k : l.keys(true)) dup |= !ids.add(k.id);
            check(!dup, n + " 每个用法码 / 按钮编号只出现一次（含 ⋯）");
            for (FixedLayout.Key k : l.keys(true)) {
                check(k.special ? (k.id == FixedLayout.MENU_ID || k.id == FixedLayout.FN_ID) : (k.id >= 0x04 && k.id <= 0xE7),
                        n + " " + k + " 的编号在范围里（特殊键只有 ⋯、Fn）");
            }
            // 0x64：ISO 型才有；65% 的 Mac ISO 例外——那里 1 左边那个键照苹果对调发 0x64，位置被 Esc 占了，0x64 只在 Fn 层（Fn+Esc），Z 左边那个发 0x35
            boolean want64 = l.iso() && !(FixedLayout.P65.equals(l.size) && l.mac() && !l.abnt2());
            check(ids.contains(0x64) == want64 && ids.contains(0x87) == l.abnt2(), n + " 0x64 当且仅当 ISO 型（65% Mac ISO 除外）、0x87 当且仅当 ABNT2");
            check(!ids.contains(0x32) && ids.contains(0x31), n + " 回车左边那个键发 0x31，不发 0x32");
            check(l.find(FixedLayout.FN_ID) != null && l.find(FixedLayout.FN_ID).special && l.hasFn(), n + " 有一个 Fn 键（特殊键）、有 Fn 层");
            check((l.mac() ? FixedLayout.FN_NAME_MAC : FixedLayout.FN_NAME).equals(l.find(FixedLayout.FN_ID).name), n + " Fn 键名：Windows Fn、Mac fn 🌐");
            check(!ids.contains(FixedLayout.GLOBE_ID), n + " 没有单独的 🌐（Mac 模式的 🌐 就是 fn🌐 这一个键）");
            check(!ids.contains(0x53) && !ids.contains(0x65) && !ids.contains(0x49) && !ids.contains(0x47) && !ids.contains(0x48),
                    n + " 没有小键盘、Menu、Ins、ScrLk、Pause（Menu、Ins、ScrLk、Pause 在 Fn 层）");
            check(has(l, 0x50) && has(l, 0x51) && has(l, 0x52) && has(l, 0x4F), n + " 方向键都在（用户：小尺寸一定要有方向键）");
            boolean fRow = true;
            for (int i = 0; i < 12; i++) fRow &= has(l, 0x3A + i);
            boolean noF = true;
            for (int i = 0; i < 12; i++) noF &= !has(l, 0x3A + i);
            switch (l.size) {
                case FixedLayout.P75:
                    check(fRow && has(l, 0x29) && has(l, 0x4C) && has(l, 0x4A) && has(l, 0x4B) && has(l, 0x4E) && has(l, 0x4D), n + " F1–F12、Esc、Del、Home、PgUp、PgDn、End");
                    check(has(l, 0x46) != l.mac() && has(l, 0x68) == l.mac(), n + " F 行 PrtSc（Windows）/ F13（Mac）");
                    check(has(l, 0xE4) != l.mac(), n + " 右 Ctrl 只在 Windows 模式（Mac 的那格是 ⌥）");
                    break;
                case FixedLayout.P65:
                    check(noF && !has(l, 0x46) && !has(l, 0x68), n + " 没有 F 行");
                    check(has(l, 0x29) && has(l, 0x4C) && has(l, 0x4B) && has(l, 0x4E) && has(l, 0x4D) && !has(l, 0x4A), n + " Esc、Del、PgUp、PgDn、End，没有 Home（Fn 层出）");
                    // 1 左边那个键被 Esc 占了：只剩 Z 左边那个（ISO Windows 0x64、ISO Mac 照苹果对调 0x35、ABNT2 0x64）
                    int zLeft = l.iso() ? FixedLayoutTest.keyAtU(l, 4, 1.25f) : -1;
                    check(FixedLayoutTest.keyAtU(l, 1, 0) == 0x29 && (zLeft < 0 ? !has(l, 0x35) && !has(l, 0x64) : !has(l, zLeft == 0x35 ? 0x64 : 0x35)),
                            n + " 数字行最左是 Esc，1 左边那个键（` / § / ^）没有了（Fn+Esc 出它）");
                    break;
                default:
                    check(fRow && has(l, 0x29) && !has(l, 0x46) && !has(l, 0x68), n + " 苹果紧凑：F1–F12、Esc，没有 PrtSc / F13");
                    check(!has(l, 0x4A) && !has(l, 0x4B) && !has(l, 0x4C) && !has(l, 0x4D) && !has(l, 0x4E), n + " 苹果紧凑没有 Home / End / PgUp / PgDn / Del（fn+方向、fn+delete 出）");
                    check(!has(l, 0xE4) && has(l, 0xE6) && has(l, 0xE7) && l.mac(), n + " 苹果紧凑：右边 ⌘ ⌥、没有右 ⌃，只有 Mac 模式");
                    break;
            }
        });
        check(combos[0] == 2 * 3 * 2 + 2, "三种新尺寸拼得出的组合：75% / 65% 各 3 形状 × 2 模式，苹果紧凑 ANSI / ISO × Mac（" + combos[0] + "）");
    }

    // ── 行表：每种尺寸自己的；TKL / 全尺寸 / 笔记本照旧是标准的 ──
    static void rowTables() {
        float[] stdBottom = {1, 2.25f, 3.25f, 4.25f, 5.25f, 6.25f};
        for (String sz : new String[]{FixedLayout.TKL, FixedLayout.FULL, FixedLayout.LAPTOP, FixedLayout.P75}) for (String m : MODES) {
            FixedLayout l = layout(FixedLayout.ANSI, sz, m);
            boolean ok = true;
            for (int r = 0; r < FixedLayout.ROWS; r++) {
                ok &= near(l.rowTop(r), FixedLayout.ROW_TOP[r]);
                ok &= near(l.rowBottom(r), r == FixedLayout.ROWS - 1 ? l.height : stdBottom[r]);
            }
            check(ok, name(l) + " 行表照标准的（F 行、隔 0.25u、五行；最下一排到总高）");
            check(near(l.height, l.laptop() ? 6.5f : 6.25f), name(l) + " 总高");
        }
        for (String m : MODES) {
            FixedLayout l = layout(FixedLayout.ANSI, FixedLayout.P65, m);
            check(Arrays.equals(rows(l, true), new float[]{0, 0, 1, 2, 3, 4}) && Arrays.equals(rows(l, false), new float[]{0, 1, 2, 3, 4, 5}) && near(l.height, 5),
                    name(l) + " 65% 没有 F 行：第 0 行零高，数字行从 0 起，5u 高");
        }
        FixedLayout a = layout(FixedLayout.ANSI, FixedLayout.APPLE, FixedLayout.MAC);
        check(Arrays.equals(rows(a, true), new float[]{0, 0.5f, 1.5f, 2.5f, 3.5f, 4.5f}) && Arrays.equals(rows(a, false), new float[]{0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f})
                && near(a.height, 5.5f), "苹果紧凑：F 行半行高、和数字行之间不隔，5.5u 高");
        each(NEW, l -> {
            boolean ok = l.clusterCount() == 1;
            for (FixedLayout.Key k : l.keys(true)) {
                ok &= FixedLayoutTest.sixteenth(k.x) && FixedLayoutTest.sixteenth(k.w) && FixedLayoutTest.sixteenth(k.x2) && FixedLayoutTest.sixteenth(k.w2)
                        && FixedLayoutTest.sixteenth(k.y0) && FixedLayoutTest.sixteenth(k.y1) && k.cluster == FixedLayout.MAIN;
            }
            check(ok, name(l) + " 一个区块、坐标都是 1/16u 的整数倍");
            check(near(l.width, l.apple() ? 14.5f : 16), name(l) + " 总宽 " + l.width);
        });
    }

    static float[] rows(FixedLayout l, boolean top) {
        float[] out = new float[FixedLayout.ROWS];
        for (int r = 0; r < out.length; r++) out[r] = top ? l.rowTop(r) : l.rowBottom(r);
        return out;
    }

    // ── 每行从 0 铺到总宽、首尾相接（⋯ 放在槽里时；同一格里上下两个半高的键算一格）；F 行没放 ⋯ 时 ⋯ 槽那格空着 ──
    static void rowsFill() {
        each(NEW, l -> {
            for (int r = 0; r < FixedLayout.ROWS; r++) {
                List<float[]> s = new ArrayList<>();
                for (FixedLayout.Key k : l.keys(true)) {
                    float[] sp = k.span(r);
                    if (sp == null) continue;
                    boolean same = false;
                    for (float[] o : s) same |= near(o[0], sp[0]) && near(o[1], sp[1]);
                    if (!same) s.add(sp);
                }
                s.sort((a, b) -> Float.compare(a[0], b[0]));
                if (r == 0 && FixedLayout.P65.equals(l.size)) {
                    check(s.isEmpty(), name(l) + " 65% 第 0 行没有键");
                    continue;
                }
                boolean ok = !s.isEmpty() && near(s.get(0)[0], 0) && near(s.get(s.size() - 1)[1], l.width);
                for (int i = 1; i < s.size(); i++) ok &= near(s.get(i)[0], s.get(i - 1)[1]);
                check(ok, name(l) + " 第 " + r + " 行（带 ⋯）从 0 铺到 " + l.width + "u、首尾相接");
            }
            // 没放 ⋯ 时：⋯ 槽那格空着（75% 在 Esc 和 F1 之间、苹果紧凑在 F 行最右）；65% 的槽在最下一排，Ctrl Super Alt 各 1.25u 铺满
            List<float[]> f = FixedLayoutTest.spans(l, l.menu.row, -1, false);
            if (l.menu.row == 0) {
                boolean covered = false;
                for (float[] s : f) covered |= s[0] < l.menu.x + l.menu.w - 1e-4f && s[1] > l.menu.x + 1e-4f;
                check(!covered, name(l) + " 没放 ⋯ 时槽那格空着");
            } else {
                boolean ok = near(f.get(0)[0], 0);
                for (int i = 1; i < f.size(); i++) ok &= near(f.get(i)[0], f.get(i - 1)[1]);
                check(ok, name(l) + " 没放 ⋯ 时最下一排照样铺满（Ctrl · Super · Alt 各 1.25u）");
            }
        });
    }

    // ── 最下一排、Z 行的顺序和宽度 ──
    static void bottomAndZRows() {
        int fn = FixedLayout.FN_ID;
        int[] win = {0xE0, 0xE3, 0xE2, 0x2C, 0xE6, fn, 0xE4, 0x50, 0x51, 0x4F};
        int[] mac = {0xE0, 0xE2, 0xE3, 0x2C, 0xE7, 0xE6, fn, 0x50, 0x51, 0x4F};
        int[] apple = {fn, 0xE0, 0xE2, 0xE3, 0x2C, 0xE7, 0xE6, 0x50, 0x4F};
        each(NEW, l -> {
            String n = name(l);
            int[] row5 = wholeRowIds(l, 5);
            check(Arrays.equals(row5, l.apple() ? apple : l.mac() ? mac : win), n + " 最下一排（整行高的）：" + Arrays.toString(row5));
            FixedLayout.Key sp = l.find(0x2C);
            check(l.apple() ? near(sp.x, 4.25f) && near(sp.w, 5) : near(sp.x, 3.75f) && near(sp.w, 6.25f), n + " 空格的位置（苹果紧凑 4.25–9.25、75% / 65% 3.75–10 同 TKL）");
            check(near(l.stickU(), 6.75f) && FixedLayoutTest.keyAtU(l, 4, 6.25f) == 0x05 && near(l.find(0x05).x + 0.5f, 6.75f),
                    n + " 红点那条线 6.75u = G|H 的缝 = B 的中线");
            if (l.apple()) {
                check(near(sp.x + sp.w / 2, l.stickU()), n + " 苹果紧凑的空格以红点为中线（同笔记本）");
                check(near(l.find(0xE3).w, 1.25f) && near(l.find(0xE7).w, 1.25f) && near(l.find(fn).w, 1) && near(l.find(fn).x, 0), n + " 两个 ⌘ 1.25u、fn🌐 在左下角");
            } else {
                check(near(l.find(fn).x, l.mac() ? 12 : 11) && near(l.find(fn).w, 1), n + " Fn 的位置（Windows 在 AltGr 右边、Mac 在右 ⌥ 右边）");
            }
            int[] row4 = FixedLayoutTest.rowIds(l, 4);
            List<Integer> want = new ArrayList<>(Arrays.asList(0xE1));
            if (l.iso()) want.add(FixedLayoutTest.keyAtU(layout(l.shape, FixedLayout.TKL, l.mode), 4, 1.25f));   // Z 左边那个键同 TKL（Mac ISO 对调）
            for (int u : new int[]{0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10, 0x36, 0x37, 0x38}) want.add(u);
            if (l.abnt2()) want.add(0x87);
            want.add(0xE5);
            if (!l.apple()) {
                want.add(0x52);
                want.add(0x4D);
            }
            check(toList(row4).equals(want), n + " Z 行：" + Arrays.toString(row4));
            FixedLayout.Key rs = l.find(0xE5), ls = l.find(0xE1);
            float rsW = l.apple() ? 2.25f : l.abnt2() ? 0.75f : 1.75f;
            check(near(rs.w, rsW) && near(ls.w, l.iso() ? 1.25f : 2.25f), n + " 右 Shift " + rs.w + "u（应为 " + rsW + "）、左 Shift " + ls.w + "u");
        });
    }

    /** 第 row 行整行高的键（y0 是 NaN）的 id，按 x 排好。 */
    static int[] wholeRowIds(FixedLayout l, int row) {
        List<FixedLayout.Key> ks = new ArrayList<>();
        for (FixedLayout.Key k : l.keys) if (k.span(row) != null && Float.isNaN(k.y0)) ks.add(k);
        ks.sort((a, b) -> Float.compare(a.span(row)[0], b.span(row)[0]));
        int[] out = new int[ks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ks.get(i).id;
        return out;
    }

    static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int v : a) out.add(v);
        return out;
    }

    // ── 75% / 65% 主键区 0–15u 的第 1–3 行（75% 连数字行，65% 只看 1–0 和后面）、Z 行 ⇧ 到 / 和 TKL 同一个位置、同一个码；右边那一列 ──
    static void sameAsTkl() {
        each(SIXTEEN, l -> {
            FixedLayout t = layout(l.shape, FixedLayout.TKL, l.mode);
            String n = name(l);
            for (int r = 1; r <= 4; r++) {
                boolean ok = true;
                for (FixedLayout.Key k : t.keys) {
                    float[] s = k.span(r);
                    if (s == null || k.cluster != FixedLayout.MAIN || k.row != r && !(k.lShape() && k.row + 1 == r)) continue;
                    if (r == 4 && s[0] >= 12.25f - 1e-4f) continue;   // 右 Shift 起不一样（缩短了）
                    if (r == 1 && s[0] < 1 - 1e-4f && FixedLayout.P65.equals(l.size)) continue;   // 65% 的 Esc 占了 1 左边那个键
                    FixedLayout.Key o = l.find(k.id);
                    ok &= o != null && o.span(r) != null && near(o.span(r)[0], s[0]) && near(o.span(r)[1], s[1]);
                }
                check(ok, n + " 第 " + r + " 行主键区和 TKL 同一个位置、同一个码");
            }
            int[] col = FixedLayout.P75.equals(l.size) ? new int[]{0x4C, 0x4A, 0x4B, 0x4E, 0x4D, 0x4F} : new int[]{-1, 0x4C, 0x4B, 0x4E, 0x4D, 0x4F};
            boolean ok = true;
            for (int r = 0; r < FixedLayout.ROWS; r++) ok &= col[r] < 0 ? FixedLayoutTest.keyAtU(l, r, 15) == -1 : FixedLayoutTest.keyAtU(l, r, 15) == col[r];
            check(ok, n + " 右边那一列（15–16u）：" + (FixedLayout.P75.equals(l.size) ? "Del Home PgUp PgDn End →" : "Del PgUp PgDn End →"));
        });
    }

    // ── 方向键：↑ 正对 ↓（倒 T），ABNT2 也是；苹果紧凑 ↑↓ 一格上下两半，← → 整高 ──
    static void arrowsAligned() {
        each(NEW, l -> {
            FixedLayout.Key up = l.find(0x52), down = l.find(0x51), left = l.find(0x50), right = l.find(0x4F);
            String n = name(l);
            check(near(up.x, down.x) && near(up.w, down.w) && near(left.x + left.w, down.x) && near(down.x + down.w, right.x), n + " ↑ 正对 ↓、← ↓ → 挨着");
            if (l.apple()) {
                check(up.row == 5 && down.row == 5 && near(up.y0, 4.5f) && near(up.y1, FixedLayout.APPLE_ARROW_SPLIT) && near(down.y0, FixedLayout.APPLE_ARROW_SPLIT)
                        && near(down.y1, 5.5f) && Float.isNaN(left.y0) && Float.isNaN(right.y0), n + " ↑↓ 一格上下两个半高、← → 整高");
                check(near(l.segTop(up, 5), 4.5f) && near(l.segBottom(down, 5), 5.5f) && near(l.segTop(left, 5), 4.5f) && near(l.segBottom(left, 5), 5.5f),
                        n + " segTop / segBottom：↑↓ 用自己那截");
            } else {
                check(up.row == 4 && down.row == 5 && near(up.x, 14) && near(right.x + right.w, 16), n + " 倒 T 方向键在右下角（↑ 在 14–15u）");
            }
        });
    }

    // ── 键不重叠（横竖都看，苹果紧凑的 ↑↓ 在同一行里竖着分开）、都在外框里 ──
    static void noOverlap() {
        each(NEW, l -> {
            for (boolean menu : new boolean[]{false, true}) {
                List<float[]> boxes = new ArrayList<>();
                for (FixedLayout.Key k : l.keys(menu)) {
                    for (int r = 0; r < FixedLayout.ROWS; r++) {
                        float[] s = k.span(r);
                        if (s != null) boxes.add(new float[]{s[0], l.segTop(k, r), s[1], l.segBottom(k, r)});
                    }
                }
                boolean ok = true;
                for (int i = 0; i < boxes.size(); i++) {
                    float[] a = boxes.get(i);
                    ok &= a[0] >= -1e-4f && a[2] <= l.width + 1e-4f && a[1] >= -1e-4f && a[3] <= l.height + 1e-4f && a[2] > a[0] && a[3] > a[1];
                    for (int j = i + 1; j < boxes.size(); j++) {
                        float[] b = boxes.get(j);
                        ok &= Math.min(a[2], b[2]) - Math.max(a[0], b[0]) <= 1e-4f || Math.min(a[3], b[3]) - Math.max(a[1], b[1]) <= 1e-4f;
                    }
                }
                check(ok, name(l) + (menu ? "（带 ⋯）" : "") + " 键不重叠、在外框里");
            }
        });
    }

    // ── ⋯ 槽：75% 在 Esc 和 F1 之间；65% 在左下角 Ctrl 右边（Ctrl 1 · ⋯ 0.75 · Super / ⌥ 1 · Alt / ⌘ 1，空格不动）；苹果紧凑在 F 行最右 ──
    static void menuSlot() {
        each(NEW, l -> {
            String n = name(l);
            FixedLayout.Key mk = l.menu;
            check(mk.special && mk.id == FixedLayout.MENU_ID, n + " ⋯ 槽里是 ⋯ 键");
            List<FixedLayout.Key> km = l.keys(true);
            switch (l.size) {
                case FixedLayout.P75:
                    check(mk.row == 0 && near(mk.x, 1) && near(mk.w, 1) && km.get(1) == mk, n + " ⋯ 槽在 Esc 和 F1 之间（同 TKL）");
                    break;
                case FixedLayout.P65: {
                    check(mk.row == 5 && near(mk.x, 1) && near(mk.w, 0.75f), n + " ⋯ 槽在左下角 Ctrl 右边、0.75u");
                    int second = l.mac() ? 0xE2 : 0xE3, third = l.mac() ? 0xE3 : 0xE2;
                    FixedLayout.Key c = find(km, 0xE0), s2 = find(km, second), s3 = find(km, third), sp = find(km, 0x2C);
                    check(near(c.x, 0) && near(c.w, 1) && near(s2.x, 1.75f) && near(s2.w, 1) && near(s3.x, 2.75f) && near(s3.w, 1)
                            && near(sp.x, 3.75f) && near(sp.w, 6.25f) && km.indexOf(mk) == km.indexOf(c) + 1,
                            n + " 放 ⋯ 时 Ctrl 1 · ⋯ 0.75 · " + (l.mac() ? "⌥ · ⌘" : "Super · Alt") + " 各 1，空格不动（3.75–10）");
                    FixedLayout.Key c0 = l.find(0xE0), s20 = l.find(second), s30 = l.find(third);
                    check(near(c0.w, 1.25f) && near(s20.x, 1.25f) && near(s20.w, 1.25f) && near(s30.x, 2.5f) && near(s30.w, 1.25f) && c0.name.equals(c.name)
                            && s20.name.equals(s2.name), n + " 没放 ⋯ 时三个修饰键各 1.25u（名字一样）");
                    break;
                }
                default:
                    check(mk.row == 0 && near(mk.x, 13.5f) && near(mk.w, 1) && km.indexOf(mk) == km.indexOf(find(km, 0x45)) + 1,
                            n + " ⋯ 槽在 F 行最右（触控 ID 那格）、排在 F12 后面");
                    break;
            }
        });
    }

    static FixedLayout.Key find(List<FixedLayout.Key> ks, int id) {
        for (FixedLayout.Key k : ks) if (k.id == id) return k;
        return null;
    }

    // ── 键盘范围（u）：不重叠、每段恰好在一块同行的范围里、铺满整块（没有空格子）、放没放 ⋯ 一样；苹果紧凑 ↑↓ 上下各一块，← / → 的范围不跨过它们 ──
    static void rangesU() {
        each(NEW, l -> {
            String n = name(l);
            List<FixedLayout.Range> rs = l.ranges(false), rm = l.ranges(true);
            check(sameRanges(rs, rm), n + " 放没放 ⋯ 范围一样");
            boolean ok = true;
            for (int i = 0; i < rs.size(); i++) for (int j = i + 1; j < rs.size(); j++) ok &= !FixedLayoutTest.overlap(rs.get(i), rs.get(j));
            check(ok, n + " 范围之间不重叠");
            for (boolean menu : new boolean[]{false, true}) for (FixedLayout.Key k : l.keys(menu)) {
                for (int r = 0; r < FixedLayout.ROWS; r++) {
                    float[] s = k.span(r);
                    if (s == null) continue;
                    int in = 0;
                    boolean same = true;
                    for (FixedLayout.Range g : rs) {
                        if (s[0] >= g.left - 1e-4f && s[1] <= g.right + 1e-4f && l.segTop(k, r) >= g.top - 1e-4f && l.segBottom(k, r) <= g.bottom + 1e-4f) {
                            in++;
                            same &= g.row == r;
                        }
                    }
                    check(in == 1 && same, n + (menu ? "（带 ⋯）" : "") + " " + k + " 第 " + r + " 行那段恰好在一块同行的范围里（" + in + "）");
                }
            }
            // 铺满：每一点都恰好在一块里（三种新尺寸每行都铺到面板边，没有空格子）
            int holes = 0, twice = 0;
            for (float y = 0.03f; y < l.height; y += 0.0625f) for (float x = 0.03f; x < l.width; x += 0.0625f) {
                int c = 0;
                for (FixedLayout.Range g : rs) if (x >= g.left && x < g.right && y >= g.top && y < g.bottom) c++;
                if (c == 0) holes++;
                if (c > 1) twice++;
            }
            check(holes == 0 && twice == 0, n + " 范围铺满整块、没有重的（空 " + holes + "、重 " + twice + "）");
            check(FixedLayoutTest.inAny(rs, l.menu.x + l.menu.w / 2, (l.rowTop(l.menu.row) + l.rowBottom(l.menu.row)) / 2), n + " ⋯ 槽在它那一行的范围里（空着时分给最近的键）");
            if (l.apple()) {
                int row5 = 0, bands = 0;
                for (FixedLayout.Range g : rs) {
                    if (g.row != 5) continue;
                    row5++;
                    if (near(g.left, 12.5f) && near(g.right, 13.5f)) bands++;
                    check(!(g.left < 13 && g.right > 13) || (near(g.left, 12.5f) && near(g.right, 13.5f)), n + " 最下一排整行高那组的范围不跨过 ↑↓ 那一格：" + g);
                }
                check(row5 == 4 && bands == 2, n + " 最下一排四块范围：← 以左一块、↑ 一块、↓ 一块、→ 一块（" + row5 + "）");
                check(FixedLayoutTest.inAny(rs, 14, 0.25f), n + " 右上角触控 ID 那格空着时在 F 行那一块里");
            }
            if (FixedLayout.P65.equals(l.size)) {
                boolean none = true;
                for (FixedLayout.Range g : rs) none &= g.row != 0;
                check(none, n + " 65% 第 0 行没有范围");
            }
        });
        // 原来的尺寸：⋯ 槽算进范围后结果和以前一样（放没放 ⋯ 一样）
        for (String sz : FixedLayout.SIZES_DESC) for (String sh : SHAPES) for (String m : MODES) {
            FixedLayout l = layout(sh, sz, m);
            check(sameRanges(l.ranges(false), l.ranges(true)), name(l) + " 放没放 ⋯ 范围一样（1B / 笔记本）");
        }
    }

    static boolean sameRanges(List<FixedLayout.Range> a, List<FixedLayout.Range> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            FixedLayout.Range x = a.get(i), y = b.get(i);
            if (x.row != y.row || x.cluster != y.cluster || !near(x.left, y.left) || !near(x.right, y.right) || !near(x.top, y.top) || !near(x.bottom, y.bottom)) return false;
        }
        return true;
    }

    // ── 拼不拼得出：苹果紧凑只有 Mac 模式、只有 ANSI / ISO；不可用的组合 forEntry / forLegends 是 null，build 照 Mac / ISO 拼（设置页算键数用） ──
    static void availability() {
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : SHAPES) for (String m : MODES) {
            boolean want = !FixedLayout.APPLE.equals(sz) || (FixedLayout.MAC.equals(m) && !FixedLayout.ABNT2.equals(sh));
            check(FixedLayout.available(sh, sz, m) == want, sz + "/" + sh + "/" + m + " 拼不拼得出：" + want);
            FixedLegends.Table t = FixedLayoutTest.table(FixedLayout.ABNT2.equals(sh) ? "br" : "us", m);
            FixedLegends.Layout entry = FixedLegends.find(FixedLayoutTest.catalog(), FixedLayout.ABNT2.equals(sh) ? "br" : "us");
            FixedLayout e = FixedLayout.forEntry(sh, sz, m, t, entry), g = FixedLayout.forLegends(sh, sz, m, t, ".");
            check(want ? e != null && g != null && e.size.equals(sz) && e.mode.equals(m) && e.shape.equals(sh) : e == null && g == null,
                    sz + "/" + sh + "/" + m + " forEntry / forLegends " + (want ? "拼出这一块" : "是 null（退回豆花布局）"));
        }
        FixedLayout c = FixedLayout.build(FixedLayout.ABNT2, FixedLayout.APPLE, FixedLayout.WIN, true, ".");
        check(c.apple() && c.mac() && FixedLayout.ISO.equals(c.shape) && !c.altGr, "build 苹果紧凑 × ABNT2 × Windows：照 Mac / ISO 拼");
        check(FixedLayout.build(FixedLayout.ANSI, FixedLayout.APPLE, FixedLayout.WIN, false, ".").keys.size() == 77, "build 苹果紧凑 ANSI × Windows：键数照 Mac 模式（77）");
        for (String z : FixedLayout.ALL_SIZES) check(FixedLayout.validSize(z).equals(z), "validSize 认 " + z);
        check(FixedLayout.TKL.equals(FixedLayout.validSize("bogus")) && FixedLayout.TKL.equals(FixedLayout.validSize(null)) && FixedLayout.TKL.equals(FixedLayout.validSize("60")),
                "validSize：认不出的当 TKL（60% 不做）");
        check(Arrays.asList(FixedLayout.ALL_SIZES).equals(Arrays.asList(FixedLayout.APPLE, FixedLayout.LAPTOP, FixedLayout.P65, FixedLayout.P75, FixedLayout.TKL, FixedLayout.FULL)),
                "ALL_SIZES 从小到大：苹果紧凑 · 笔记本 · 65% · 75% · TKL · 全尺寸");
        float prev = 0;
        boolean asc = true;
        for (String z : FixedLayout.ALL_SIZES) {
            float w = layout(FixedLayout.ANSI, z, FixedLayout.MAC).width;
            asc &= w >= prev;
            prev = w;
        }
        check(asc, "ALL_SIZES 的宽一个不比一个窄");
        check(Arrays.asList(FixedLayout.SIZES_DESC).equals(Arrays.asList(FixedLayout.FULL, FixedLayout.TKL, FixedLayout.LAPTOP)), "SIZES_DESC 照旧是 1B / 笔记本那三种");
        for (String sz : FixedLayout.SIZES_DESC) {
            FixedLayout l = layout(FixedLayout.ANSI, sz, FixedLayout.WIN);
            check(!l.hasFn() && l.fnKeys().isEmpty() && l.find(FixedLayout.FN_ID) == null && l.fn(0x52) == null, name(l) + " 没有 Fn 键、Fn 层（笔记本也不加）");
        }
    }

    // ── 逐级缩的梯子：全尺寸 → TKL → 75% → 笔记本；65% → 笔记本；苹果紧凑只有自己 ──
    static void ladders() {
        Map<String, List<String>> want = new LinkedHashMap<>();
        want.put(FixedLayout.FULL, Arrays.asList(FixedLayout.FULL, FixedLayout.TKL, FixedLayout.P75, FixedLayout.LAPTOP));
        want.put(FixedLayout.TKL, Arrays.asList(FixedLayout.TKL, FixedLayout.P75, FixedLayout.LAPTOP));
        want.put(FixedLayout.P75, Arrays.asList(FixedLayout.P75, FixedLayout.LAPTOP));
        want.put(FixedLayout.P65, Arrays.asList(FixedLayout.P65, FixedLayout.LAPTOP));
        want.put(FixedLayout.LAPTOP, Arrays.asList(FixedLayout.LAPTOP));
        want.put(FixedLayout.APPLE, Arrays.asList(FixedLayout.APPLE));
        for (Map.Entry<String, List<String>> e : want.entrySet()) {
            List<String> got = FixedLayout.ladder(e.getKey());
            check(got.equals(e.getValue()), "ladder(" + e.getKey() + ") = " + e.getValue() + "（得到 " + got + "）");
            float prev = Float.MAX_VALUE;
            boolean shrink = true;
            for (String z : got) {
                float w = layout(FixedLayout.ANSI, z, FixedLayout.MAC).width;
                shrink &= w <= prev;
                prev = w;
            }
            check(shrink, "ladder(" + e.getKey() + ") 只缩不涨");
        }
    }

    // ── 放得下（一格 ≥ 32dp）和逐级缩挑哪一级 ──
    static void levels() {
        FixedLayout p75 = layout(FixedLayout.ANSI, FixedLayout.P75, FixedLayout.WIN), p65 = layout(FixedLayout.ANSI, FixedLayout.P65, FixedLayout.WIN),
                apple = layout(FixedLayout.ANSI, FixedLayout.APPLE, FixedLayout.MAC);
        check(KeyboardPlan.fixedFits(p75, 512, T) && !KeyboardPlan.fixedFits(p75, 511, T), "75% 一格 32 起（512 / 16 = 32）");
        check(KeyboardPlan.fixedFits(p65, 512, T) && !KeyboardPlan.fixedFits(p65, 511, T), "65% 一格 32 起（同 75%）");
        check(KeyboardPlan.fixedFits(apple, 464, T) && !KeyboardPlan.fixedFits(apple, 463, T), "苹果紧凑一格 32 起（464 / 14.5 = 32）");
        int[][] full = {{720, 0}, {719, 1}, {584, 1}, {583, 2}, {512, 2}, {511, 3}, {480, 3}, {479, -1}};
        List<FixedLayout> fromFull = ladder(FixedLayout.ANSI, FixedLayout.FULL, FixedLayout.WIN);
        for (int[] q : full) check(KeyboardPlan.fixedLevel(fromFull, q[0], T) == q[1], "选全尺寸 " + q[0] + " 宽 → 第 " + q[1] + " 级（得到 " + KeyboardPlan.fixedLevel(fromFull, q[0], T) + "）");
        List<FixedLayout> from65 = ladder(FixedLayout.ISO, FixedLayout.P65, FixedLayout.MAC), fromApple = ladder(FixedLayout.ISO, FixedLayout.APPLE, FixedLayout.MAC);
        check(KeyboardPlan.fixedLevel(from65, 2000, T) == 0 && KeyboardPlan.fixedLevel(from65, 511, T) == 1 && KeyboardPlan.fixedLevel(from65, 479, T) == -1,
                "选 65%：宽窗口照旧 65%、511 缩成笔记本、479 暂用豆花布局");
        check(KeyboardPlan.fixedLevel(fromApple, 2000, T) == 0 && KeyboardPlan.fixedLevel(fromApple, 464, T) == 0 && KeyboardPlan.fixedLevel(fromApple, 463, T) == -1,
                "选苹果紧凑：放不下直接豆花布局（不换笔记本）");
        check(ladder(FixedLayout.ABNT2, FixedLayout.APPLE, FixedLayout.MAC).isEmpty() && ladder(FixedLayout.ANSI, FixedLayout.APPLE, FixedLayout.WIN).isEmpty(),
                "苹果紧凑 × ABNT2 / Windows：梯子是空的（退回豆花布局）");
        int widths = 0;
        boolean ok = true;
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : SHAPES) for (String m : MODES) {
            List<FixedLayout> lad = ladder(sh, sz, m);
            for (int w = 300; w <= 2600; w += 3) {
                widths++;
                int lv = KeyboardPlan.fixedLevel(lad, w, T);
                for (int j = 0; j < lad.size(); j++) {
                    boolean fits = KeyboardPlan.fixedFits(lad.get(j), w, T);
                    if (j < lv || lv < 0) ok &= !fits;
                    if (j == lv) ok &= fits;
                }
            }
        }
        check(ok, "逐级缩扫了 " + widths + " 种（尺寸 × 形状 × 模式 × 宽度）：挑的是第一个放得下的");
    }

    /** 同 FixedData.ladder（Android 部分不进离线编译）：选的尺寸往下每级 forEntry 一块，拼不出就是空的。 */
    static List<FixedLayout> ladder(String shape, String size, String mode) {
        String lang = FixedLayout.ABNT2.equals(shape) ? "br" : FixedLayout.ISO.equals(shape) ? "de" : "us";
        FixedLegends.Table t = FixedLayoutTest.table(lang, mode);
        FixedLegends.Layout entry = FixedLegends.find(FixedLayoutTest.catalog(), lang);
        List<FixedLayout> out = new ArrayList<>();
        for (String z : FixedLayout.ladder(size)) {
            FixedLayout l = FixedLayout.forEntry(shape, z, mode, t, entry);
            if (l == null) return new ArrayList<>();
            out.add(l);
        }
        return out;
    }

    // ── Fn 层的数据（方案 §3.2）：照规格逐项对、每项的底层键在、码合法（不是修饰键、和底层不一样）、名字对 ──
    static void fnLayer() {
        int[] total = {0};
        each(NEW, l -> {
            String n = name(l);
            // 1 左边那个键本来发的码（照 TKL 同形状同模式的第 1 行最左那个键）
            int grave = FixedLayoutTest.keyAtU(layout(l.shape, FixedLayout.TKL, l.mode), 1, 0);
            Map<Integer, Integer> want = new TreeMap<>();
            int[][] arrows = {{0x52, 0x4B}, {0x51, 0x4E}, {0x50, 0x4A}, {0x4F, 0x4D}};
            for (int[] a : arrows) want.put(a[0], a[1]);
            if (l.apple()) {
                want.put(0x2A, 0x4C);
            } else {
                if (FixedLayout.P65.equals(l.size)) {
                    want.put(0x29, grave);
                    for (int i = 0; i < 10; i++) want.put(0x1E + i, 0x3A + i);
                    want.put(0x2D, 0x44);
                    want.put(0x2E, 0x45);
                    want.put(0x4B, 0x4A);
                    want.put(0x4E, 0x4D);
                }
                want.put(0x13, l.mac() ? 0x68 : 0x46);
                want.put(0x2F, l.mac() ? 0x69 : 0x47);
                want.put(0x30, l.mac() ? 0x6A : 0x48);
                if (!l.mac()) {
                    want.put(0x4C, 0x49);
                    want.put(0xE4, 0x65);
                }
            }
            Map<Integer, Integer> got = new TreeMap<>();
            for (FixedLayout.FnKey f : l.fnKeys()) got.put(f.base, f.usage);
            check(got.equals(want), n + " Fn 层照规格：" + l.fnKeys());
            total[0] += got.size();
            Set<Integer> bases = new HashSet<>();
            for (FixedLayout.FnKey f : l.fnKeys()) {
                FixedLayout.Key k = l.find(f.base);
                check(k != null && !k.special && bases.add(f.base) && l.fn(f.base) == f, n + " " + f + " 的底层键在、不是特殊键、只有一项");
                check(f.usage != f.base && f.usage >= 0x04 && f.usage <= 0xA4 && !(f.usage >= 0xE0 && f.usage <= 0xE7)
                        && f.usage != FixedLayout.MENU_ID && f.usage != FixedLayout.FN_ID && f.usage != FixedLayout.GLOBE_ID,
                        n + " " + f + " 发的是键盘页的普通键（不是修饰键、不是按钮编号）、和底层不一样");
                check(f.charKey() == (f.base == 0x29 && FixedLayout.P65.equals(l.size)) && (f.charKey() ? f.usage == grave : f.name.equals(FixedLayout.fnName(f.usage))),
                        n + " " + f + " 名字（只有 65% 的 Esc → 1 左边那个键是出字的，字按码查）");
            }
            check(l.fn(0x04) == null && l.fn(0x2C) == null && l.fn(FixedLayout.FN_ID) == null, n + " 没列的键（a、空格、Fn 自己）没有 Fn 层");
            // 65% 的 Fn+Esc 发的码在这块键盘的底层没有别的键发（1 左边那个键被 Esc 占了），字表里有它
            if (FixedLayout.P65.equals(l.size)) {
                check(!has(l, grave), n + " Fn+Esc 发的 " + Integer.toHexString(grave) + " 底层没有别的键发");
            }
            // 和底层另一个键、或者 Fn 层另一个键发同一个码的（方案原样，Fn 层的机制按码计数松开）：只有这几个
            List<Integer> shared = l.fnSharedUsages();
            List<Integer> wantShared = l.apple() ? new ArrayList<>() : FixedLayout.P65.equals(l.size) ? Arrays.asList(0x4A, 0x4B, 0x4D, 0x4E)
                    : l.mac() ? Arrays.asList(0x4A, 0x4B, 0x4D, 0x4E, 0x68) : Arrays.asList(0x46, 0x4A, 0x4B, 0x4D, 0x4E);
            check(shared.equals(wantShared), n + " Fn 层和别的键发同一个码的只有 " + wantShared + "（得到 " + shared + "）");
        });
        check(total[0] > 0, "Fn 层查了 " + total[0] + " 项");
    }

    // ── Fn 层写着名字的码，名字和别的尺寸里发同一个码的键一样（PrtSc、Ins、F13、☰……） ──
    static void fnNames() {
        Map<Integer, String> names = new TreeMap<>();
        for (String sz : FixedLayout.SIZES_DESC) for (String sh : SHAPES) for (String m : MODES) {
            for (FixedLayout.Key k : layout(sh, sz, m).keys) if (!k.charKey() && !k.special && k.id != 0x2C) names.putIfAbsent(k.id, k.name);
        }
        Set<Integer> seen = new HashSet<>();
        each(NEW, l -> {
            for (FixedLayout.FnKey f : l.fnKeys()) {
                if (f.charKey() || !seen.add(f.usage)) continue;
                check(f.name.equals(names.get(f.usage)), "Fn 层 " + Integer.toHexString(f.usage) + " 写 " + f.name + "，同别的尺寸的键（" + names.get(f.usage) + "）");
            }
        });
        check(seen.size() >= 16, "Fn 层写着名字的码查了 " + seen.size() + " 种");
        check(FixedLayout.fnName(0x35) == null && FixedLayout.fnName(0x64) == null && FixedLayout.fnName(0x04) == null, "fnName：出字的码是 null");
    }

    // ── 键帽上的字：Fn 键、Fn 层（FixedPanel.fnGlyphs）、主次键帽 ──
    static void glyphs() {
        FixedPanel w = FixedLayoutTest.panel("us", FixedLayout.ANSI, FixedLayout.P75, FixedLayout.WIN), m = FixedLayoutTest.panel("us", FixedLayout.ANSI, FixedLayout.P75, FixedLayout.MAC);
        eqs(FixedLayoutTest.glyphs(w, FixedLayout.FN_ID, 0), "中Fn", "75% Windows 的 Fn 键帽");
        eqs(FixedLayoutTest.glyphs(m, FixedLayout.FN_ID, 0), "上fn 下🌐", "75% Mac 的 fn🌐 键帽：上 fn、下地球");
        check(w.functionKey(w.index(FixedLayout.FN_ID)) && m.functionKey(m.index(FixedLayout.FN_ID)), "Fn 键是功能键帽");
        check(w.preview(w.index(FixedLayout.FN_ID), 0) == null && !FixedPanel.active(w.keys().get(w.index(FixedLayout.FN_ID)), 0xFF, true),
                "Fn 键不放大、不跟修饰键的高亮（Fn 的高亮看 Fn 的状态，FixedPanel.active(i, …, fn)）");
        eqs(fn(w, 0x13, 0), "中PrtSc", "75% Windows Fn+P = PrtSc");
        eqs(fn(m, 0x13, 0), "中F13", "75% Mac Fn+P = F13");
        eqs(fn(w, 0x4C, 0), "中Ins", "75% Windows Fn+Del = Ins");
        eqs(fn(m, 0x4C, 0), null, "75% Mac 的 Del 没有 Fn 层");
        eqs(fn(w, 0x52, 0), "中PgUp", "Fn+↑ = PgUp");
        eqs(fn(w, 0xE4, 0), "中☰", "75% Windows Fn+右 Ctrl = Menu");
        eqs(fn(w, 0x04, 0), null, "a 没有 Fn 层");
        check(w.fnGlyphs(w.index(FixedLayout.FN_ID), 0, false) == null, "Fn 键自己没有 Fn 层");
        FixedPanel u65 = FixedLayoutTest.panel("us", FixedLayout.ANSI, FixedLayout.P65, FixedLayout.WIN);
        eqs(FixedLayoutTest.glyphs(u65, 0x29, 0), "中Esc", "65% 数字行最左是 Esc");
        eqs(fn(u65, 0x29, 0), "上~ 下`", "65% us Fn+Esc = ` ~（照 1 左边那个键的字）");
        eqs(fn(u65, 0x29, 0x02), "上~ 下`（暗）", "65% us Fn+Esc 锁着 Shift：~ 亮");
        eqs(fn(u65, 0x1E, 0), "中F1", "65% Fn+1 = F1");
        eqs(fn(u65, 0x2E, 0), "中F12", "65% Fn+= = F12");
        eqs(fn(u65, 0x4B, 0), "中Home", "65% Fn+PgUp = Home");
        eqs(fn(u65, 0x4E, 0), "中End", "65% Fn+PgDn = End");
        FixedPanel dw = FixedLayoutTest.panel("de", FixedLayout.ISO, FixedLayout.P65, FixedLayout.WIN), dm = FixedLayoutTest.panel("de", FixedLayout.ISO, FixedLayout.P65, FixedLayout.MAC);
        eqs(fn(dw, 0x29, 0), "上° 下^（死）", "65% win:de Fn+Esc 发 0x35（同 TKL 的 ^ 键）");
        eqs(fn(dm, 0x29, 0), "上° 下^（死） 角„", "65% mac:de Fn+Esc 发 0x64（照苹果 ISO 对调，同 TKL 的 ^ 键）");
        FixedPanel ap = FixedLayoutTest.panel("us", FixedLayout.ANSI, FixedLayout.APPLE, FixedLayout.MAC);
        eqs(FixedLayoutTest.glyphs(ap, FixedLayout.FN_ID, 0), "上fn 下🌐", "苹果紧凑的 fn🌐");
        eqs(fn(ap, 0x2A, 0), "中Del", "苹果紧凑 fn+delete = 向前删除");
        eqs(fn(ap, 0x51, 0), "中PgDn", "苹果紧凑 fn+↓ = PgDn");
        eqs(FixedLayoutTest.glyphs(ap, 0x52, 0), "中↑", "苹果紧凑 ↑ 键帽");
        FixedPanel ai = FixedLayoutTest.panel("de", FixedLayout.ISO, FixedLayout.APPLE, FixedLayout.MAC);
        eqs(FixedLayoutTest.glyphs(ai, 0x64, 0), "上° 下^（死） 角„", "苹果紧凑 mac:de 1 左边（发 0x64，同 TKL 的 Mac ISO）");
        check(FixedLayoutTest.keyAtU(ai.layout(), 1, 0) == 0x64 && FixedLayoutTest.keyAtU(ai.layout(), 4, 1.25f) == 0x35, "苹果紧凑 ISO：1 左边 0x64、Z 左边 0x35（照苹果 ISO 对调）");
        check(ai.layout().find(0x28).lShape() && near(ai.layout().find(0x28).x, 13.5f) && near(ai.layout().find(0x28).w, 1) && near(ai.layout().find(0x28).x2, 13.75f)
                && near(ai.layout().find(0x28).w2, 0.75f), "苹果紧凑 ISO 回车 L 形：上半 1u、下半 0.75u、右边对齐");
        float[] shape = ai.shape(ai.index(0x28));
        check(shape.length == 12 && shape[6] > shape[0] && shape[2] == ai.box(ai.index(0x28))[2], "苹果紧凑 ISO 回车画成 L 形（六个角、下半段比上半段窄、右边一条直线）");
        // 拼音的 Mac 模式 Caps 写 中/英（同 TKL）
        FixedLegends.Layout py = FixedLegends.find(FixedLayoutTest.catalog(), "cn-pinyin");
        for (String sz : NEW) {
            FixedLayout l = FixedLayout.forEntry(FixedLayout.ANSI, sz, FixedLayout.MAC, FixedLayoutTest.table("cn-pinyin", FixedLayout.MAC), py);
            check("中/英".equals(l.find(0x39).name), "cn-pinyin " + name(l) + " Caps 写 中/英");
        }
    }

    /** 第 id 个键（按用法码找）Fn 层的字，格式同 FixedLayoutTest.glyphs；没有 Fn 层是 null。 */
    static String fn(FixedPanel p, int id, int mods) {
        List<FixedPanel.Glyph> gs = p.fnGlyphs(p.index(id), mods, false);
        if (gs == null) return null;
        StringBuilder sb = new StringBuilder();
        for (FixedPanel.Glyph g : gs) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(new String[]{"中", "上", "下", "角"}[g.slot]).append(g.text);
            if (g.dead) sb.append("（死）");
            if (g.dim) sb.append("（暗）");
        }
        return sb.toString();
    }

    // ── Fn 层的机制（协议 21）在真的实体键盘上：三条触摸路径同一个 id、同样的报文 ──

    /**
     * 每种拼得出的 75% / 65% / 苹果紧凑 × 带不带 ⋯ × 两种窗口：
     * ① FixedPanel.regions 报的每一段：类型是 FixedLayout.regionType（⋯ 按钮，Fn 和别的是键），id 是 keyId——Fn 键 Windows 模式 Proto.FN_KEY、
     *   Mac 模式 Proto.FN_GLOBE_KEY，带 Fn 层的键 Proto.fnLayerKey(底层, Fn 层)，别的就是用法码；一块面板里每个键的 id 各不相同（indexOfKeyId 对得回来）。
     * ② 分流（真 TouchRouter + 报的区域，服务接管、本地分流）和面板自己收触摸（keyAt → keyId，经 CMD_HID_KEY / local.key）逐点同一个 id。
     * ③ 每个走分流的键：区域那一路（按在段的正中）和 key() 那一路，单按、按住 Fn 再按、先松 Fn 再松，报文、修饰键 / Fn 状态、🌐 一模一样；
     *   单按出底层的码，按住 Fn 出 Fn 层的码（没有 Fn 层的照旧）；Mac 模式单点 fn🌐 出 🌐（和 TKL Mac 模式的 🌐 键同一个按钮编号），Windows 模式单点 Fn 什么都不出。
     */
    static void fnPaths() {
        int[] tally = new int[4];   // 面板、逐点、走分流的键、出了 Fn 层的码
        String[] first = new String[1];
        int[] bad = new int[1];
        for (String sz : NEW) for (String sh : SHAPES) for (String m : MODES) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            FixedLayout l = layout(sh, sz, m);
            for (boolean menu : new boolean[]{false, true}) for (int[] wh : new int[][]{{933, 352}, {512, 230}}) {
                FixedPanel p = new FixedPanel();
                p.setBoard(l, FixedLayoutTest.geoTable(sh, m));
                p.setMenuKey(menu);
                p.place(wh[0], wh[1], GAP, 10);
                tally[0]++;
                String what = name(l) + (menu ? "（带 ⋯）" : "") + " " + wh[0] + "×" + wh[1];
                String why = fnPanel(p, wh, tally);
                if (why != null) {
                    bad[0]++;
                    if (first[0] == null) first[0] = what + "：" + why;
                }
            }
        }
        check(bad[0] == 0, "Fn 层三条触摸路径：" + tally[0] + " 块面板" + (first[0] == null ? "" : "，比如 " + first[0]));
        check(tally[2] > 0 && tally[3] > 0, "真按过走分流的键、真出过 Fn 层的码");
        // TKL Mac 模式的 🌐 键（按钮）点一下：和 fn🌐 单点报的是同一个按钮编号的点击
        FixedLayout tkl = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.MAC);
        FixedPanel p = new FixedPanel();
        p.setBoard(tkl, FixedLayoutTest.geoTable(FixedLayout.ANSI, FixedLayout.MAC));
        p.place(933, 352, GAP, 10);
        KeyHoldsTest.H h = new KeyHoldsTest.H(realRegions(p));
        int[] c = center(p, p.index(FixedLayout.GLOBE_ID));
        h.to(1000);
        h.t.down(0, c[0], c[1], 1000);
        h.to(1100);
        h.t.up(0, 1100);
        check(h.r.globes == 1 && FixedLayout.regionType(tkl.find(FixedLayout.GLOBE_ID)) == Proto.REGION_BUTTON,
                "TKL Mac 模式的 🌐 键（按钮）点一下：报的也是 Proto.GLOBE_BUTTON_ID 的点击");
        System.err.println("  Fn 层三条触摸路径" + LABEL + "：" + tally[0] + " 块面板，逐点 " + tally[1] + " 个、走分流的键 " + tally[2] + " 个（出 Fn 层的码 " + tally[3] + " 次）");
    }

    /** 一块面板的 ① ② ③（见 {@link #fnPaths}），不对返回原因。 */
    static String fnPanel(FixedPanel p, int[] wh, int[] tally) {
        FixedLayout l = p.layout();
        List<int[]> rs = new ArrayList<>();
        p.regions(0, 0, rs);
        int fnIndex = -1;
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < p.keys().size(); i++) {
            FixedLayout.Key k = p.keys().get(i);
            FixedLayout.FnKey f = l.fn(k.id);
            int want = FixedLayout.fnKey(k) ? (l.mac() ? Proto.FN_GLOBE_KEY : Proto.FN_KEY)
                    : !k.special && f != null ? Proto.fnLayerKey(k.id, f.usage) : k.id;
            if (FixedLayout.fnKey(k)) fnIndex = i;
            if (p.keyId(i) != want) return k + " 报的 id " + Integer.toHexString(p.keyId(i)) + "，应为 " + Integer.toHexString(want);
            if (!ids.add(want) || p.indexOfKeyId(want) != i) return k + " 的 id 和别的键重了";
            if (p.sendsKey(i) != (FixedLayout.regionType(k) == Proto.REGION_KEY) || (k.special && !FixedLayout.fnKey(k)) == p.sendsKey(i)) {
                return k + " 走不走分流不对（⋯ 是按钮，Fn 是键）";
            }
            if (f != null && (Proto.fnBase(want) != k.id || Proto.fnCode(want) != f.usage || !Proto.isFnLayerKey(want))) return k + " Fn 层编码取不回来";
        }
        if (fnIndex < 0 || FixedLayout.regionType(p.keys().get(fnIndex)) != Proto.REGION_KEY) return "没有 Fn 键，或者 Fn 不是键";
        List<FixedPanel.Seg> segs = p.segs();
        for (int j = 0; j < segs.size(); j++) {
            int[] r = rs.get(j);
            FixedPanel.Seg sg = segs.get(j);
            if (r[0] != FixedLayout.regionType(p.keys().get(sg.key)) || r[1] != p.keyId(sg.key) || r[2] != sg.left || r[3] != sg.top) {
                return "第 " + j + " 段报的区域和键对不上：" + p.keys().get(sg.key);
            }
        }
        for (int j = segs.size(); j < rs.size(); j++) if (rs.get(j)[0] != Proto.REGION_KEYBOARD) return "段后面应该全是键盘范围";
        // ② 逐点：分流和面板自己收触摸同一个 id
        TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
        TouchRouter.Region[] real = realRegions(p);
        router.setRegions(real);
        for (int y = 0; y < wh[1]; y += 3) for (int x = 0; x < wh[0]; x += 3) {
            tally[1]++;
            TouchRouter.Region hit = router.hit(x, y);
            int i = p.keyAt(x, y);
            int a = hit == null ? -1 : hit.id, b = i < 0 ? -1 : p.keyId(i);
            if (a != b || (hit != null && hit.type != FixedLayout.regionType(p.keys().get(i)))) {
                return "(" + x + ", " + y + ") 分流 " + Integer.toHexString(a) + "、面板自己收 " + Integer.toHexString(b);
            }
        }
        // ③ 每个走分流的键：两条路同样的序列
        int[] fc = center(p, fnIndex);
        int fnId = p.keyId(fnIndex);
        for (int i = 0; i < p.keys().size(); i++) {
            if (!p.sendsKey(i) || i == fnIndex) continue;
            tally[2]++;
            FixedLayout.Key k = p.keys().get(i);
            FixedLayout.FnKey f = l.fn(k.id);
            int id = p.keyId(i);
            int[] c = center(p, i);
            KeyHoldsTest.H a = new KeyHoldsTest.H(real), b = new KeyHoldsTest.H(real);
            long t = 1000;
            // 单按
            touch(a, 0, c, true, t);
            b.kd(id, t);
            touch(a, 0, c, false, t += 50);
            b.ku(id, t);
            String alone = a.r.keys.isEmpty() ? "" : a.r.keys.get(0);
            // 按住 Fn 再按、松开
            touch(a, 1, fc, true, t += 100);
            b.kd(fnId, t);
            int n = a.r.keys.size();
            touch(a, 0, c, true, t += 50);
            b.kd(id, t);
            String withFn = a.r.keys.size() > n ? a.r.keys.get(n) : "";
            touch(a, 0, c, false, t += 50);
            b.ku(id, t);
            touch(a, 1, fc, false, t += 50);
            b.ku(fnId, t);
            // 按住 Fn 按下、先松 Fn 再松
            touch(a, 1, fc, true, t += 100);
            b.kd(fnId, t);
            touch(a, 0, c, true, t += 50);
            b.kd(id, t);
            touch(a, 1, fc, false, t += 50);
            b.ku(fnId, t);
            touch(a, 0, c, false, t += 50);
            b.ku(id, t);
            a.to(t += 3000);
            b.to(t);
            if (!a.r.keys.equals(b.r.keys) || !a.r.mods.equals(b.r.mods) || a.r.globes != b.r.globes) {
                return k + "：区域那一路 " + a.r.keys + a.r.mods + " 和 key() 那一路 " + b.r.keys + b.r.mods + " 不一样";
            }
            String last = a.r.keys.isEmpty() ? "" : a.r.keys.get(a.r.keys.size() - 1);
            if (!last.equals(String.format("[%02x|]", a.r.lastL)) || a.r.lastF != 0 || a.r.globes != 0) return k + "：收尾后还按着 " + last;
            if (k.id == KeyHolds.CAPS) continue;   // Caps 点一下按着 60ms 再松，报文另有规矩（KeyHoldsTest）
            int base = k.id, fn = f != null ? f.usage : base;
            if (f != null) tally[3]++;
            if (!alone.equals(expect(base)) || !withFn.equals(expect(fn))) {
                return k + "：单按 " + alone + "（应为 " + expect(base) + "），按住 Fn " + withFn + "（应为 " + expect(fn) + "）";
            }
        }
        // Fn 自己单点：Mac 模式出 🌐，Windows 模式什么都不出；两条路一样
        KeyHoldsTest.H a = new KeyHoldsTest.H(real), b = new KeyHoldsTest.H(real);
        touch(a, 0, fc, true, 1000);
        b.kd(fnId, 1000);
        touch(a, 0, fc, false, 1150);
        b.ku(fnId, 1150);
        if (a.r.globes != (l.mac() ? 1 : 0) || b.r.globes != a.r.globes || !a.r.keys.isEmpty() || !b.r.keys.isEmpty()) {
            return "单点 Fn：🌐 " + a.r.globes + " / " + b.r.globes + "（" + (l.mac() ? "Mac 模式应出 🌐" : "Windows 模式什么都不出") + "）";
        }
        return null;
    }

    /** 单按一个键（不是 Fn 层的码时就是底层的）第一帧报文：修饰键是修饰键位，别的是 [00|码]。 */
    static String expect(int usage) {
        if (usage >= 0xE0 && usage <= 0xE7) return String.format("[%02x|]", 1 << (usage - 0xE0));
        return String.format("[00|%02x]", usage);
    }

    /** FixedPanel.regions 真报的区域（服务、本地分流收到的那一份）。 */
    static TouchRouter.Region[] realRegions(FixedPanel p) {
        List<int[]> rs = new ArrayList<>();
        p.regions(0, 0, rs);
        TouchRouter.Region[] out = new TouchRouter.Region[rs.size()];
        for (int i = 0; i < out.length; i++) {
            int[] r = rs.get(i);
            out[i] = new TouchRouter.Region(r[0], r[1], r[2], r[3], r[4], r[5]);
        }
        return out;
    }

    /** 第 i 个键第一段的正中（面板坐标）。 */
    static int[] center(FixedPanel p, int i) {
        for (FixedPanel.Seg s : p.segs()) if (s.key == i) return new int[]{(s.left + s.right) / 2, (s.top + s.bottom) / 2};
        throw new IllegalStateException("没有段：" + p.keys().get(i));
    }

    /** 区域那一路：第 slot 根手指在 c 按下 / 抬起（先把到点的定时器走掉）。 */
    static void touch(KeyHoldsTest.H h, int slot, int[] c, boolean down, long t) {
        h.to(t);
        if (down) h.t.down(slot, c[0], c[1], t);
        else h.t.up(slot, t);
        h.t.frame(t);
    }

    // ── 苹果紧凑 ↑↓ 周围的缝（真 TouchRouter 和面板自己收触摸两条路）：两层之间上半给 ↑、下半给 ↓，一个点都不给 ← / →；
    //    ← | ↑↓、↑↓ | → 两条竖缝按中线分（↑↓ 那边按上下层）。同笔记本方向键的教训：两层和整行高的键放在一块里，缝会全分给整行高的那个 ──
    static void appleArrowSeam() {
        int seam = 0, side = 0, bad = 0, combos = 0;
        String first = null;
        for (String sh : new String[]{FixedLayout.ANSI, FixedLayout.ISO}) for (boolean menu : new boolean[]{false, true}) for (boolean holed : new boolean[]{false, true})
            for (int[] wh : new int[][]{{464, 230}, {520, 250}, {751, 300}, {933, 352}, {1800, 700}}) {
                FixedLayout l = layout(sh, FixedLayout.APPLE, FixedLayout.MAC);
                FixedPanel p = new FixedPanel();
                p.setBoard(l, FixedLayoutTest.geoTable(sh, FixedLayout.MAC));
                p.setMenuKey(menu);
                if (holed) p.setHole(FixedPanel.stickHole(l, wh[0], T));
                p.place(wh[0], wh[1], GAP, 10);
                TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
                router.setRegions(FixedLayoutTest.regions(p));
                combos++;
                String what = name(l) + (menu ? "（带 ⋯）" : "") + (holed ? " 让位" : "") + " " + wh[0] + "×" + wh[1];
                int split = FixedLayoutTest.uy(l, FixedLayout.APPLE_ARROW_SPLIT, wh[1]);
                int leftEdge = FixedLayoutTest.ux(l, 12.5f, wh[0]), rightEdge = FixedLayoutTest.ux(l, 13.5f, wh[0]);
                int[] up = p.box(p.index(0x52)), down = p.box(p.index(0x51)), lb = p.box(p.index(0x50)), rb = p.box(p.index(0x4F));
                for (int y = up[3]; y < down[1]; y++) for (int x = leftEdge; x < rightEdge; x++) {
                    seam++;
                    int a = FixedLayoutTest.id(p, FixedLayoutTest.route(router, x, y)), b = FixedLayoutTest.id(p, p.keyAt(x, y));
                    int want = y < split ? 0x52 : 0x51;
                    if (a != want || b != want) {
                        bad++;
                        if (first == null) first = what + " (" + x + ", " + y + ") 应为 " + Integer.toHexString(want) + "：分流 " + Integer.toHexString(a) + "、面板 " + Integer.toHexString(b);
                    }
                }
                for (int y = lb[1]; y < lb[3]; y++) {
                    for (int x = lb[2]; x < up[0]; x++) {
                        side++;
                        int a = FixedLayoutTest.id(p, FixedLayoutTest.route(router, x, y)), b = FixedLayoutTest.id(p, p.keyAt(x, y));
                        int want = x < leftEdge ? 0x50 : y < split ? 0x52 : 0x51;
                        if (a != want || b != want) {
                            bad++;
                            if (first == null) first = what + " 左竖缝 (" + x + ", " + y + ") 应为 " + Integer.toHexString(want) + "：分流 " + Integer.toHexString(a) + "、面板 " + Integer.toHexString(b);
                        }
                    }
                    for (int x = up[2]; x < rb[0]; x++) {
                        side++;
                        int a = FixedLayoutTest.id(p, FixedLayoutTest.route(router, x, y)), b = FixedLayoutTest.id(p, p.keyAt(x, y));
                        int want = x >= rightEdge ? 0x4F : y < split ? 0x52 : 0x51;
                        if (a != want || b != want) {
                            bad++;
                            if (first == null) first = what + " 右竖缝 (" + x + ", " + y + ") 应为 " + Integer.toHexString(want) + "：分流 " + Integer.toHexString(a) + "、面板 " + Integer.toHexString(b);
                        }
                    }
                }
                check(down[1] - up[3] == 2 * GAP && up[0] == down[0] && up[2] == down[2], what + " ↑ ↓ 上下隔 2 × gap、左右对齐");
                check(lb[1] == up[1] && lb[3] == down[3] && rb[1] == up[1] && rb[3] == down[3], what + " ← → 整行高：上沿对 ↑、下沿对 ↓");
            }
        check(bad == 0 && seam > 0 && side > 0, "苹果紧凑 ↑↓ 周围的缝：两层之间 " + seam + " 个点、两边竖缝 " + side + " 个点，" + bad + " 个分错" + (first == null ? "" : "，比如 " + first));
        System.err.println("  苹果紧凑 ↑↓ 周围的缝：" + combos + " 种（形状 × ⋯ × 让位 × 窗口），两层之间 " + seam + " 个点、两边竖缝 " + side + " 个点");
    }

    // ── 小红点状态的空位：一格 ≥ 32dp 就一定让得出三个鼠标键，对称、空格两段够长（75% / 65% 照 TKL 的规则 ≥ 1 份；苹果紧凑照笔记本多那一步 ≥ 0.75 份）──
    static void stickHoles() {
        each(NEW, l -> {
            int bad = 0;
            String first = null;
            int min = Math.round(32 * l.width);
            FixedLayout.Key space = l.find(0x2C);
            float minSpace = l.apple() ? FixedPanel.LAPTOP_SPACE_MIN : KeyRows.STICK_SPACE_MIN;
            for (int pw = min; pw <= 2600; pw++) {
                int[] hole = FixedPanel.stickHole(l, pw, T);
                float unit = pw / l.width;
                int cx = FixedPanel.px(l.stickU(), pw, l.width), left = FixedPanel.px(space.x, pw, l.width), right = FixedPanel.px(space.x + space.w, pw, l.width);
                String why = null;
                if (hole == null) why = "让不出空位";
                else if (Math.abs(hole[0] + hole[1] - 2 * cx) > 1) why = "空位不以红点为中线";
                else if (hole[1] - hole[0] < T.holeWidth(T.minKey)) why = "空位放不下三个 minKey 的键";
                else if (hole[0] - left < unit * minSpace - 1 || right - hole[1] < unit * minSpace - 1) why = "空格不到 " + minSpace + " 份";
                if (why != null && bad++ == 0) first = pw + "：" + why + " " + Arrays.toString(hole);
            }
            check(bad == 0, name(l) + " 面板 " + min + "–2600 宽每一种都让得出空位、对称、键 ≥ minKey、空格 ≥ " + minSpace + " 份" + (first == null ? "" : "（" + bad + " 种不对，比如 " + first + "）"));
        });
        // 苹果紧凑多的那一步：不加的话 464–约 520 宽让不出（同笔记本）
        FixedLayout a = layout(FixedLayout.ANSI, FixedLayout.APPLE, FixedLayout.MAC);
        int[] h = FixedPanel.stickHole(a, 464, T);
        int left = FixedPanel.px(4.25f, 464, a.width);
        check(h != null && h[1] - h[0] == T.holeWidth(T.minKey) && h[0] - left < 464 / 14.5f, "苹果紧凑 464 宽：三个键各 minKey、空格缩到 1 份以下（笔记本那一步）");
        // 整个摆法：页宽一格 ≥ 32dp ⇒ 有三个鼠标键、中键正对红点（默认位置 + 随机存过的位置，后者经 fitFixedRect 撑到一格 32dp）
        Random rnd = new Random(20261102);
        int plans = 0, bad = 0;
        String first = null;
        for (String sz : NEW) for (String sh : SHAPES) for (String m : MODES) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            FixedLayout l = layout(sh, sz, m);
            int min = Math.round(32 * l.width);
            for (int w = min; w <= 2600; w += 13) for (int hh : new int[]{150, 230, 300, 475, 704}) for (int k = 0; k < 2; k++) {
                float[] saved = k == 0 ? null : new float[]{rnd.nextFloat() * 0.9f, rnd.nextFloat() * 0.5f, 0, 1};
                if (saved != null) saved[2] = Math.min(1, saved[0] + 0.02f + rnd.nextFloat() * 0.6f);
                KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, w, hh, saved, l, T);
                plans++;
                int pw = p.keyboard[2] - p.keyboard[0], ph = p.keyboard[3] - p.keyboard[1];
                String why = null;
                if (p.keys == null || p.hole == null || p.middleKey == null) why = "没有三个鼠标键";
                else {
                    int[] pt = FixedPanel.stickPoint(l, pw, ph, GAP);
                    int mid = (p.middleKey[0] + p.middleKey[1]) / 2 - p.keyboard[0];
                    if (Math.abs(mid - pt[0]) > 1) why = "中键没正对红点";
                    else if (p.keys[1] != p.keyboard[1] + FixedPanel.spaceRowTop(l, ph)) why = "三个鼠标键不在空格那一排";
                }
                if (why != null && bad++ == 0) first = name(l) + " " + w + "×" + hh + " " + Arrays.toString(saved) + "：" + why;
            }
        }
        check(bad == 0, "75% / 65% / 苹果紧凑 + 小红点 + 一格 ≥ 32dp ⇒ 有三个鼠标键、中键正对红点：扫了 " + plans + " 种" + (first == null ? "" : "，" + bad + " 种不对，比如 " + first));
        System.err.println("  75% / 65% / 苹果紧凑 + 小红点的三个鼠标键：" + plans + " 种摆法都有");
    }

    // ── 工具 ──
    static boolean near(float a, float b) {
        return FixedLayoutTest.near(a, b);
    }

    static void eqs(String got, String want, String what) {
        FixedLayoutTest.eqs(got, want, what);
    }

    static void check(boolean ok, String what) {
        FixedLayoutTest.check(ok, what);
    }
}
