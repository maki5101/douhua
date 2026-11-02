package dev.douhua;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 实体键盘 F1–F12 的第二功能（协议 24，2026-11-02，方案 design-fkeys）几何和键帽那一半的离线测试；发码、定码（KeyHolds、两个槽、收尾归零）在 FKeysTest。
 * - FKeyFaces：每个能选的功能两种模式下都有图标或字、多媒体 / 系统键的都有图标、图标的笔画都认得且在方格里、组合键 / F13 / 键名的写法。
 * - FixedLayout：配了第二功能的 F 键报 Proto.frowKey（两面都在 id 里、取得回来）、Esc 在至少一个配了时是 FN_LOCK_KEY、65% 不上、
 *   一块面板里 id 各不相同、KEY_ID_BASE + id 是正数；配不配第二功能几何一点不变（键、范围一模一样）；TKL / 全尺寸 / 笔记本的 Fn 键在哪、
 *   笔记本 ⋯ 槽在 F 行 Ins 那格、Fn+Del = Ins。
 * - Boards：默认值按模式分（Windows ThinkPad、Mac 苹果），spec 带着这种模式的 12 个功能、改了比得出来、toString 不变。
 * - 键帽（FixedPanel.glyphsAt）：所有尺寸 × 形状 × 模式 × 几套配法 × ⋯ × 让位 × 窗口 × Fn 的状态（按着 / 单次锁 / 永久锁 / 翻转）扫一遍：
 *   每个键都有字，F 键两面都印 / 只画会出的那一面、哪面亮照"Fn 起作用 ^ 翻转"，Esc 的 FnLock，别的键不看翻转、和以前的 glyphs 一样。
 * - 三条触摸路径（真 TouchRouter + FixedPanel.regions 报的区域、面板自己收触摸 keyAt → keyId、经 key() 的那一路）逐点同一个 id，
 *   每个 F 键、Esc 单按 / 按着 Fn / Fn+Esc 翻转后，两条路的报文一样、出的是该出的那一面，收尾全松。
 * - 小红点照旧：笔记本 / 75% / 65% 的 Windows 模式有红点和三个鼠标键，TKL / 全尺寸 / Mac 模式照触控板。
 * 尺寸按 1dp = 1px 算。从仓库根目录跑（读 res/raw 的键帽数据）。
 */
public final class FixedFKeysTest {
    private static int checks, failures;
    private static final int GAP = 2;
    /** 同 FixedLayoutTest.T（KeyboardScreen 的 baseSizes，1dp = 1px）。 */
    private static final KeyboardPlan.Sizes T = new KeyboardPlan.Sizes(8, 96, 32, 64, 32, 3, 10, 44, 48, 4, 56, GAP, 20);
    private static final String[] MODES = {FixedLayout.WIN, FixedLayout.MAC};
    private static final int FLIP = Proto.FROW_FLIP;
    /** 扫键帽用的 Fn 状态：平时、按着、单次锁、永久锁、快永久锁了，各带不带翻转。 */
    private static final int[] FN_STATES = {0, Proto.FN_HELD, Proto.FN_LATCHED, Proto.FN_LOCKED, Proto.FN_HELD | Proto.FN_ARMED,
            FLIP, FLIP | Proto.FN_HELD, FLIP | Proto.FN_LATCHED, FLIP | Proto.FN_LOCKED, FLIP | Proto.FN_LOCKED | Proto.FN_HELD};

    /** 几套配法：null（都没配）、"不用"、ThinkPad、苹果、自定义一套（各种发法 + 认不出的 id）。 */
    static final String[] CUSTOM = {"f13", FKeyFunctions.keyFunctionId(0x06 | Hid.MOD_CTRL << 8), "sleep", "mic_mute", "copilot", "snip",
            "show_apps", "lock", "emoji", "bogus-id", "none", FKeyFunctions.keyFunctionId(0x4A)};

    /** 两套随机配法（种子固定）：从全部功能、随机组合键、认不出的 id 里挑。 */
    static final String[] RANDOM_A = randomConfig(new Random(24)), RANDOM_B = randomConfig(new Random(2026));

    static String[][] configs() {
        return new String[][]{null, FKeyFunctions.preset(FKeyFunctions.PRESET_NONE), FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD),
                FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE), CUSTOM, RANDOM_A, RANDOM_B};
    }

    static String cname(String[] c) {
        if (c == null) return "没配";
        String p = FKeyFunctions.presetOf(c);
        return FKeyFunctions.PRESET_CUSTOM.equals(p) ? "自定义" : p;
    }

    public static void main(String[] args) throws IOException {
        FixedLayoutTest.load();
        faces();
        ids();
        geometrySame();
        newKeys();
        boards();
        glyphScan();
        glyphPins();
        textFit();
        paths();
        stick();
        System.err.println("实体键盘 F 行第二功能（几何 / 键帽）：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static FixedLayout layout(String shape, String size, String mode, String[] fkeys) {
        return FixedLayout.build(shape, size, mode, true, ".", null, null, fkeys);
    }

    static String name(FixedLayout l) {
        return FixedLayoutTest.name(l);
    }

    interface Each {
        void run(FixedLayout l, String[] config);
    }

    /** 每种拼得出的 尺寸 × 形状 × 模式 × 配法。 */
    static void each(Each e) {
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : FixedLayout.SHAPES) for (String m : MODES) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            for (String[] c : configs()) e.run(layout(sh, sz, m, c), c);
        }
    }

    // ═══════════ FKeyFaces ═══════════

    static void faces() {
        int withIcon = 0;
        for (FKeyFunctions.Function f : FKeyFunctions.all()) {
            for (String m : MODES) {
                FKeyFaces.Face face = FKeyFaces.face(f, m);
                if (f.kind == FKeyFunctions.NONE) {
                    check(face == null, f + " 是\"无\"：不画第二功能");
                    continue;
                }
                check(face != null && face.text != null && !face.text.isEmpty(), f + " " + m + " 有图标或字（不出没字的键）");
                if (face == null) continue;
                boolean control = f.kind == FKeyFunctions.CONSUMER || f.kind == FKeyFunctions.SYSTEM;
                check(!control || face.icon != 0, f + " 多媒体 / 系统键那一组都有图标");
                check(face.icon == 0 || face.icon >= 1 && face.icon <= FKeyFaces.ICON_COUNT, f + " 图标编号在范围里");
                check(FKeyFaces.hasIcon(f.id) == (face.icon != 0), f + " hasIcon 和 face 对得上");
                if (face.icon != 0 && FixedLayout.WIN.equals(m)) withIcon++;
            }
        }
        check(withIcon >= 20, "二十来个功能有图标（" + withIcon + "）");
        // 图标的笔画：每个都有、笔画认得、参数个数对、坐标在方格里
        Set<String> drawn = new HashSet<>();
        for (int id = 1; id <= FKeyFaces.ICON_COUNT; id++) {
            float[][] ops = FKeyFaces.icon(id);
            check(ops.length > 0, "图标 " + id + " 有笔画");
            StringBuilder sig = new StringBuilder();
            for (float[] op : ops) {
                sig.append(Arrays.toString(op));
                String bad = badOp(op);
                check(bad == null, "图标 " + id + " 的笔画 " + Arrays.toString(op) + (bad == null ? "" : "：" + bad));
            }
            check(drawn.add(sig.toString()), "图标 " + id + " 和别的图标不一样");
        }
        check(FKeyFaces.icon(0).length == 0 && FKeyFaces.icon(FKeyFaces.ICON_COUNT + 1).length == 0, "认不出的图标编号是空的");
        // 组合键、F13、键名
        eq(FKeyFaces.face(FKeyFunctions.get("display"), FixedLayout.WIN).text, "Super+P", "Super+P（Windows）");
        eq(FKeyFaces.face(FKeyFunctions.get("display"), FixedLayout.MAC).text, "⌘P", "⌘P（Mac）");
        eq(FKeyFaces.face(FKeyFunctions.get("snip"), FixedLayout.WIN).text, "Super+Shift+S", "截屏工具 Windows：Super+Shift+S");
        eq(FKeyFaces.face(FKeyFunctions.get("snip"), FixedLayout.MAC).text, "⇧⌘4", "截屏工具 Mac：⇧⌘4（发的也是 ⇧⌘4）");
        eq(FKeyFaces.face(FKeyFunctions.get("copilot"), FixedLayout.WIN).text, "Super+Shift+F23", "Copilot 键写组合本身");
        eq(FKeyFaces.face(FKeyFunctions.get("notifications"), FixedLayout.WIN).text, "Super+N", "通知中心");
        eq(FKeyFaces.face(FKeyFunctions.get("f13"), FixedLayout.WIN).text, "F13", "F13");
        eq(FKeyFaces.face(FKeyFunctions.get("f24"), FixedLayout.MAC).text, "F24", "F24");
        eq(FKeyFaces.face(FKeyFunctions.get(FKeyFunctions.keyFunctionId(0x06 | Hid.MOD_CTRL << 8)), FixedLayout.WIN).text, "Ctrl+C", "自定义组合键 Ctrl+C");
        eq(FKeyFaces.face(FKeyFunctions.get(FKeyFunctions.keyFunctionId(0x06 | Hid.MOD_CTRL << 8)), FixedLayout.MAC).text, "⌃C", "Mac 写 ⌃C");
        eq(FKeyFaces.comboLabel(0x04 | (Hid.MOD_CTRL | Hid.MOD_ALT | Hid.MOD_SHIFT | Hid.MOD_META) << 8, FixedLayout.WIN), "Super+Ctrl+Alt+Shift+A", "Windows 顺序 Super Ctrl Alt Shift");
        eq(FKeyFaces.comboLabel(0x04 | (Hid.MOD_CTRL | Hid.MOD_ALT | Hid.MOD_SHIFT | Hid.MOD_META) << 8, FixedLayout.MAC), "⌃⌥⇧⌘A", "Mac 顺序 ⌃ ⌥ ⇧ ⌘");
        eq(FKeyFaces.comboLabel(0x04 | 0x40 << 8, FixedLayout.WIN), "Alt+A", "右边的修饰键折到左边");
        eq(FKeyFaces.face(FKeyFunctions.get(FKeyFunctions.keyFunctionId(0x4A)), FixedLayout.WIN).text, "Home", "单个键写键名");
        check(FKeyFaces.face(FKeyFunctions.get("prtsc"), FixedLayout.WIN).icon == FKeyFaces.SCREENSHOT, "PrtSc 画截屏的图标");
        for (int u = 0x04; u <= 0x73; u++) check(!FKeyFaces.keyName(u).isEmpty(), "键名 " + Integer.toHexString(u) + " 不是空的");
        eq(FKeyFaces.keyName(0x1E), "1", "1");
        eq(FKeyFaces.keyName(0x27), "0", "0");
        eq(FKeyFaces.keyName(0x52), "↑", "↑");
        check(FKeyFaces.face(null, FixedLayout.WIN) == null && FKeyFaces.face(FKeyFunctions.get("none"), FixedLayout.WIN) == null, "null / 无：不画");
    }

    /** 一笔画得不对的原因（认不出、参数个数不对、坐标不在方格里、半径 / 宽高不是正的），对的返回 null。 */
    static String badOp(float[] op) {
        if (op.length == 0) return "空的";
        int kind = (int) op[0];
        int n = op.length - 1;
        float box = FKeyFaces.BOX;
        switch (kind) {
            case FKeyFaces.LINE:
                if (n != 4) return "参数个数";
                break;
            case FKeyFaces.POLYLINE:
            case FKeyFaces.POLYGON:
                if (n < 4 || n % 2 != 0 || (kind == FKeyFaces.POLYGON && n < 6)) return "参数个数";
                break;
            case FKeyFaces.RECT:
                if (n != 6 || op[3] <= op[1] || op[4] <= op[2] || op[5] < 0 || (op[6] != 0 && op[6] != 1)) return "矩形";
                return inBox(op, 1, 4);
            case FKeyFaces.CIRCLE:
                if (n != 4 || op[3] <= 0 || (op[4] != 0 && op[4] != 1)) return "圆";
                return op[1] - op[3] >= -0.01f && op[1] + op[3] <= box + 0.01f && op[2] - op[3] >= -0.01f && op[2] + op[3] <= box + 0.01f ? null : "圆出了方格";
            case FKeyFaces.OVAL:
                if (n != 4 || op[3] <= op[1] || op[4] <= op[2]) return "椭圆";
                break;
            case FKeyFaces.ARC:
                if (n != 5 || op[3] <= 0 || op[5] == 0) return "圆弧";
                return op[1] - op[3] >= -0.01f && op[1] + op[3] <= box + 0.01f && op[2] - op[3] >= -0.01f && op[2] + op[3] <= box + 0.01f ? null : "圆弧出了方格";
            case FKeyFaces.CRESCENT:
                if (n != 6 || op[3] <= 0 || op[6] <= 0) return "月牙";
                return op[1] - op[3] >= -0.01f && op[1] + op[3] <= box + 0.01f && op[2] - op[3] >= -0.01f && op[2] + op[3] <= box + 0.01f ? null : "月牙出了方格";
            default:
                return "认不出的笔画 " + kind;
        }
        return inBox(op, 1, op.length - 1);
    }

    static String inBox(float[] op, int from, int to) {
        for (int i = from; i <= to; i++) if (op[i] < 0 || op[i] > FKeyFaces.BOX) return "坐标 " + op[i] + " 不在 0–24 里";
        return null;
    }

    // ═══════════ 报的 id ═══════════

    static void ids() {
        int[] frowSeen = {0}, lockSeen = {0};
        each((l, c) -> {
            String n = name(l) + " " + cname(c);
            boolean capable = !FixedLayout.P65.equals(l.size);
            check(l.frowCapable() == capable && FixedLayout.frowCapable(l.size) == capable, n + " 上不上第二功能：65% 不上、别的都上");
            boolean any = false;
            for (int k = 1; k <= 12; k++) {
                FKeyFunctions.Function want = capable && c != null ? FKeyFunctions.get(c[k - 1]) : null;
                if (want != null && want.kind == FKeyFunctions.NONE) want = null;
                FKeyFunctions.Function got = l.frow(k);
                check(got == want || got != null && want != null && got.id.equals(want.id), n + " F" + k + " 配的是 " + want + "（得到 " + got + "）");
                any |= want != null;
            }
            check(l.escFnLock() == any, n + " Esc 是不是翻转开关：至少一个 F 键配了第二功能时才是");
            for (boolean menu : new boolean[]{false, true}) {
                Set<Integer> seen = new HashSet<>();
                for (FixedLayout.Key k : l.keys(menu)) {
                    int id = l.keyId(k);
                    check(seen.add(id), n + (menu ? "（带 ⋯）" : "") + " " + k + " 的 id 和别的键重了");
                    check(Proto.KEY_ID_BASE + id > 0, n + " " + k + " KEY_ID_BASE + id 是正数");
                    int fi = l.frowIndex(k);
                    if (fi > 0) {
                        FKeyFunctions.Function f = l.frow(fi);
                        if (f == null) {
                            check(id == FKeyFunctions.fUsage(fi), n + " 没配的 F" + fi + " 报用法码");
                        } else {
                            frowSeen[0]++;
                            check(Proto.isFrowKey(id) && Proto.frowType(id) == f.kind && Proto.frowBase(id) == FKeyFunctions.fUsage(fi)
                                    && Proto.frowSecond(id) == f.code(l.mode) && id == FKeyFunctions.regionId(fi, f, l.mode),
                                    n + " F" + fi + "（" + f + "）的 id 两面都取得回来");
                        }
                        check(k.id == FKeyFunctions.fUsage(fi) && !k.special, n + " frowIndex 认的是 F 键");
                    } else if (!k.special && k.id >= 0x3A && k.id <= 0x45) {
                        check(false, n + " " + k + " 是 F 键却不认");
                    }
                    if (!k.special && k.id == 0x29) {
                        if (l.escFnLock()) lockSeen[0]++;
                        int want = l.escFnLock() ? Proto.FN_LOCK_KEY : l.fn(0x29) != null ? Proto.fnLayerKey(0x29, l.fn(0x29).usage) : 0x29;
                        check(id == want, n + " Esc 报 " + Integer.toHexString(id) + "（应为 " + Integer.toHexString(want) + "）");
                    }
                    if (FixedLayout.fnKey(k)) check(id == (l.mac() ? Proto.FN_GLOBE_KEY : Proto.FN_KEY), n + " Fn 键报 FN_KEY / FN_GLOBE_KEY");
                }
            }
        });
        check(frowSeen[0] > 0 && lockSeen[0] > 0, "真查过配了第二功能的 F 键（" + frowSeen[0] + "）、Esc 的翻转开关（" + lockSeen[0] + "）");
        // 65% 给了配法也不上：F 键在 Fn 层（Fn+数字），Esc 照旧 Fn+Esc = 1 左边那个键
        FixedLayout p65 = layout(FixedLayout.ANSI, FixedLayout.P65, FixedLayout.WIN, FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD));
        check(!p65.escFnLock() && p65.frow(1) == null && p65.keyId(p65.find(0x29)) == Proto.fnLayerKey(0x29, 0x35), "65% 不上第二功能：Esc 照旧 Fn+Esc = `");
        // forEntry 带 fkeys 和 build 一样
        FixedLegends.Layout us = FixedLegends.find(FixedLayoutTest.catalog(), "us");
        String[] tp = FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD);
        FixedLayout e = FixedLayout.forEntry(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN, FixedLayoutTest.table("us", FixedLayout.WIN), us, tp);
        check(e.frow(1) != null && "mute".equals(e.frow(1).id) && e.escFnLock() && e.frow(8) == null, "forEntry 照 fkeys 配（ThinkPad：F1 静音、F8 无）");
        check(FixedLayout.forEntry(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN, FixedLayoutTest.table("us", FixedLayout.WIN), us).frow(1) == null,
                "forEntry 不带 fkeys：都没配");
    }

    // ═══════════ 几何：配不配第二功能一点不变 ═══════════

    static void geometrySame() {
        int n = 0;
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : FixedLayout.SHAPES) for (String m : MODES) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            FixedLayout base = layout(sh, sz, m, null);
            for (String[] c : configs()) {
                FixedLayout l = layout(sh, sz, m, c);
                for (boolean menu : new boolean[]{false, true}) {
                    n++;
                    check(sameKeys(base.keys(menu), l.keys(menu)) && FixedCompactTest.sameRanges(base.ranges(menu), l.ranges(menu))
                                    && base.width == l.width && base.height == l.height,
                            name(l) + " " + cname(c) + (menu ? "（带 ⋯）" : "") + " 配不配第二功能，键和范围一模一样");
                }
            }
        }
        check(n > 0, "几何比了 " + n + " 种");
    }

    static boolean sameKeys(List<FixedLayout.Key> a, List<FixedLayout.Key> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            FixedLayout.Key x = a.get(i), y = b.get(i);
            if (x.id != y.id || x.special != y.special || x.row != y.row || x.rows != y.rows || x.x != y.x || x.w != y.w || x.x2 != y.x2 || x.w2 != y.w2
                    || !String.valueOf(x.name).equals(String.valueOf(y.name))) return false;
        }
        return true;
    }

    // ═══════════ TKL / 全尺寸 / 笔记本加的 Fn、笔记本的 ⋯ 槽 ═══════════

    static void newKeys() {
        for (String sh : FixedLayout.SHAPES) for (String m : MODES) {
            boolean mac = FixedLayout.MAC.equals(m);
            for (String sz : new String[]{FixedLayout.TKL, FixedLayout.FULL}) {
                FixedLayout l = layout(sh, sz, m, null);
                FixedLayout.Key fn = l.find(FixedLayout.FN_ID);
                check(fn != null && fn.special && fn.row == 5 && FixedLayout.regionType(fn) == Proto.REGION_KEY
                                && (mac ? near(fn.x, 12.5f) && FixedLayout.FN_NAME_MAC.equals(fn.name) : near(fn.x, 11.25f) && FixedLayout.FN_NAME.equals(fn.name)),
                        name(l) + " Fn：" + (mac ? "fn🌐 在原来 🌐 那格" : "Fn 在原来右 Super 那格") + "、是键（REGION_KEY）");
                check(mac ? l.find(0xE7) != null && l.find(FixedLayout.GLOBE_ID) == null : l.find(0xE7) == null && l.find(0x65) != null,
                        name(l) + (mac ? " 右 ⌘ 照旧、没有单独的 🌐" : " 没有右 Super、☰ 照旧"));
                check(l.fnKeys().isEmpty(), name(l) + " Fn 层只有 F 行（fnKeys 是空的）");
            }
            FixedLayout lap = layout(sh, FixedLayout.LAPTOP, m, FKeyFunctions.preset(FKeyFunctions.defaultPreset(m)));
            FixedLayout.Key fn = lap.find(FixedLayout.FN_ID), ctrl = lap.find(0xE0);
            check(fn != null && fn.row == 5 && near(fn.x, 1.25f) && near(fn.w, 1) && near(ctrl.x, 0) && near(ctrl.w, 1.25f),
                    name(lap) + " 左下：Ctrl 1.25 · Fn 1（真 ThinkPad 的 Fn 位置）");
            check(lap.menu.row == 0 && near(lap.menu.x, 13.0625f) && near(lap.menu.w, 0.8125f), name(lap) + " ⋯ 槽在 F 行 Ins 那格");
            check(mac ? lap.fn(0x4C) == null : lap.fn(0x4C) != null && lap.fn(0x4C).usage == 0x49, name(lap) + (mac ? " Mac 模式没有 Ins，Del 不换" : " Fn+Del = Ins"));
            check(lap.find(FixedLayout.GLOBE_ID) != null == mac, name(lap) + " 🌐（PrtSc 那格）只在 Mac 模式");
            // Fn+Del 的 id
            FixedLayout.Key del = lap.find(0x4C);
            check(lap.keyId(del) == (mac ? 0x4C : Proto.fnLayerKey(0x4C, 0x49)), name(lap) + " Del 报的 id");
        }
    }

    // ═══════════ Boards：存哪套、默认值、spec ═══════════

    static void boards() {
        BoardsTest.MapStore store = new BoardsTest.MapStore();
        Boards b = new Boards(store, FixedLayoutTest.catalog());
        check(Arrays.equals(b.fkeys(FixedLayout.WIN), FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD)), "Windows 模式默认 ThinkPad 那套");
        check(Arrays.equals(b.fkeys(FixedLayout.MAC), FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE)), "Mac 模式默认苹果那套");
        check(!b.frowFlip(FixedLayout.WIN) && b.frowFlip(FixedLayout.MAC), "默认平时按出：Windows F1–F12、Mac 第二功能");
        Boards.Spec s0 = b.spec();
        check(Arrays.equals(s0.fkeys, b.fkeys(FixedLayout.WIN)) && "us/ansi/tkl/win".equals(s0.toString()), "spec 带着现在这种模式的 12 个功能，toString 不变");
        b.setFkey(FixedLayout.WIN, 3, "f13");
        Boards.Spec s1 = b.spec();
        check(!s1.equals(s0) && "f13".equals(s1.fkeys[2]), "改了一个 F 键，spec 比得出来（MainActivity.applyBoards 重拼）");
        check(s1.equals(b.spec()) && s1.hashCode() == b.spec().hashCode(), "没改时 spec 一样");
        b.setMode(FixedLayout.MAC);
        check(Arrays.equals(b.spec().fkeys, FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE)), "换成 Mac 模式：spec 带 Mac 那套");
        boolean onlyKbx = true;
        for (String k : store.map.keySet()) onlyKbx &= k.startsWith("kbx_") || k.startsWith("kbd_");
        check(onlyKbx, "存的都是 kbx_ / kbd_ 开头（不写 kb_ 开头的键）：" + store.map.keySet());
        // 照 spec 拼出来的 F 键配的就是存的
        FixedLayout l = FixedLayout.forEntry(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN, FixedLayoutTest.table("us", FixedLayout.WIN),
                FixedLegends.find(FixedLayoutTest.catalog(), "us"), s1.fkeys);
        check(l.frow(3) != null && "f13".equals(l.frow(3).id) && Proto.frowSecond(l.keyId(l.find(0x3C))) == 0x68, "照 spec 拼：F3 配了 F13");
    }

    // ═══════════ 键帽 ═══════════

    /** F 键 / Esc 的字（格式：位置 + 字 / 图标，压暗的带"（暗）"），给钉死的点用。 */
    static String glyphs(FixedPanel p, int index, int fn) {
        StringBuilder sb = new StringBuilder();
        for (FixedPanel.Glyph g : p.glyphsAt(index, 0, false, fn, "空格")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(new String[]{"中", "上", "下", "角", "上面", "下面"}[g.slot]);
            sb.append(g.icon != 0 ? "图标" + g.icon : g.text);
            if (g.dim) sb.append("（暗）");
        }
        return sb.toString();
    }

    static void glyphScan() {
        int combos = 0, frowKeys = 0, twoFaces = 0, oneFace = 0;
        String bad = null;
        int[] mods = {0, 0x02, 0x40, 0x04, 0x22};
        int[][] windows = {{933, 352}, {600, 230}, {480, 230}};
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : FixedLayout.SHAPES) for (String m : MODES) for (String[] c : configs()) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            FixedLegends.Table t = FixedLayoutTest.geoTable(sh, m);
            FixedLayout l = layout(sh, sz, m, c);
            for (boolean menu : new boolean[]{false, true}) for (boolean holed : new boolean[]{false, true}) for (int[] wh : windows) {
                FixedPanel p = new FixedPanel();
                p.setBoard(l, t);
                p.setMenuKey(menu);
                if (holed) p.setHole(FixedPanel.stickHole(l, wh[0], T));
                p.place(wh[0], wh[1], GAP, 10);
                combos++;
                String what = name(l) + " " + cname(c) + (menu ? " ⋯" : "") + (holed ? " 让位" : "") + " " + wh[0] + "×" + wh[1];
                for (int i = 0; i < p.keys().size(); i++) {
                    FixedLayout.Key k = p.keys().get(i);
                    int fi = p.frowIndex(i);
                    boolean esc = p.escFnLock(i);
                    if (fi > 0) frowKeys++;
                    for (int fn : FN_STATES) for (int md : mods) for (boolean caps : new boolean[]{false, true}) {
                        List<FixedPanel.Glyph> gs = p.glyphsAt(i, md, caps, fn, "Space");
                        String why = null;
                        boolean any = false;
                        for (FixedPanel.Glyph g : gs) {
                            if (g.text == null || g.text.isEmpty()) why = "有空的字";
                            if (g.icon != 0 && FKeyFaces.icon(g.icon).length == 0) why = "认不出的图标";
                            if (g.slot != FixedPanel.Glyph.CORNER) any = true;
                        }
                        if (!any) why = "没有字";
                        boolean second = FixedPanel.secondShown(fn), lock = FixedPanel.fnShown(fn);
                        if (fi > 0) {
                            FKeyFaces.Face face = FKeyFaces.face(l.frow(fi), l.mode);
                            String fName = "F" + fi;
                            if (l.frowTwoFaces()) {
                                twoFaces++;
                                boolean ok = gs.size() == 2 && gs.get(0).slot == FixedPanel.Glyph.UPPER && gs.get(0).icon == face.icon && gs.get(0).text.equals(face.text)
                                        && gs.get(0).dim == !second && gs.get(1).slot == FixedPanel.Glyph.LOWER && gs.get(1).text.equals(fName) && gs.get(1).dim == second;
                                if (!ok) why = "两面都印：上第二功能、下 " + fName + "，会出的亮、不会出的暗（" + gs + "）";
                            } else {
                                oneFace++;
                                boolean ok = gs.size() == 1 && gs.get(0).slot == FixedPanel.Glyph.CENTER && !gs.get(0).dim
                                        && (second ? gs.get(0).icon == face.icon && gs.get(0).text.equals(face.text) : gs.get(0).icon == 0 && gs.get(0).text.equals(fName));
                                if (!ok) why = "放不下两层：只画会出的那一面（" + gs + "）";
                            }
                        } else if (esc) {
                            boolean ok = l.frowTwoFaces()
                                    ? gs.size() == 2 && gs.get(0).slot == FixedPanel.Glyph.CENTER && "Esc".equals(gs.get(0).text) && gs.get(0).dim == lock
                                    && gs.get(1).slot == FixedPanel.Glyph.CORNER && FixedPanel.FN_LOCK_NAME.equals(gs.get(1).text) && gs.get(1).dim == !lock
                                    : gs.size() == 1 && gs.get(0).slot == FixedPanel.Glyph.CENTER && (lock ? FixedPanel.FN_LOCK_NAME : "Esc").equals(gs.get(0).text);
                            if (!ok) why = "Esc：正中 Esc、角上 FnLock，Fn 起作用时 FnLock 亮（" + gs + "）";
                        } else {
                            // 别的键：不看翻转，和以前的 glyphs 一样
                            String noFlip = String.valueOf(p.glyphsAt(i, md, caps, fn & ~FLIP, "Space"));
                            String old = String.valueOf(p.glyphs(i, md, caps, lock, "Space"));
                            if (!String.valueOf(gs).equals(noFlip) || !String.valueOf(gs).equals(old)) why = "不是 F 行的键却跟着翻转变了";
                            if (!k.special && k.id >= 0x3A && k.id <= 0x45 && !(gs.size() == 1 && ("F" + (k.id - 0x39)).equals(gs.get(0).text))) {
                                why = "没配第二功能的 F 键只写 F1";
                            }
                        }
                        if ((fi > 0 || esc) && (!p.functionKey(i) || !p.functionKey(i, lock) || p.preview(i, md, caps, lock) != null || !p.named(i, lock))) {
                            why = "F 行的键是功能键、不放大、写名字";
                        }
                        if (why != null && bad == null) bad = what + " " + k + " Fn 状态 " + fn + " 修饰键 " + Integer.toHexString(md) + "：" + why;
                    }
                }
            }
        }
        check(bad == null, "F 行第二功能的键帽" + (bad == null ? "" : "：" + bad));
        check(twoFaces > 0 && oneFace > 0, "两面都印、只画一面的都扫到了（" + twoFaces + " / " + oneFace + "）");
        System.err.println("  F 行键帽：" + combos + " 种（尺寸 × 形状 × 模式 × 配法 × ⋯ × 让位 × 窗口），配了第二功能的 F 键 " + frowKeys + " 个次");
    }

    /** 钉死几个键帽。 */
    static void glyphPins() {
        String[] tp = FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD), ap = FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE);
        FixedPanel w = panel(FixedLayout.TKL, FixedLayout.WIN, tp), m = panel(FixedLayout.TKL, FixedLayout.MAC, ap);
        eq(glyphs(w, w.index(0x3A), 0), "上面图标" + FKeyFaces.MUTE + "（暗） 下面F1", "TKL Windows 平时：F1 亮、静音的图标暗");
        eq(glyphs(w, w.index(0x3A), Proto.FN_HELD), "上面图标" + FKeyFaces.MUTE + " 下面F1（暗）", "按着 Fn：静音亮、F1 暗");
        eq(glyphs(w, w.index(0x3A), FLIP), "上面图标" + FKeyFaces.MUTE + " 下面F1（暗）", "翻转了：平时静音亮");
        eq(glyphs(w, w.index(0x3A), FLIP | Proto.FN_LOCKED), "上面图标" + FKeyFaces.MUTE + "（暗） 下面F1", "翻转 + 永久锁：F1 亮");
        eq(glyphs(w, w.index(0x40), 0), "上面Super+P（暗） 下面F7", "F7：组合键写组合本身");
        eq(glyphs(w, w.index(0x41), Proto.FN_HELD), "中F8", "F8 没配：只写 F8，按着 Fn 也一样");
        eq(glyphs(w, w.index(0x29), 0), "中Esc 角FnLock（暗）", "Esc 平时：Esc 亮、FnLock 暗");
        eq(glyphs(w, w.index(0x29), Proto.FN_LATCHED), "中Esc（暗） 角FnLock", "Fn 单次锁：FnLock 亮");
        eq(glyphs(m, m.index(0x3A), FLIP), "上面图标" + FKeyFaces.BRIGHT_DOWN + " 下面F1（暗）", "TKL Mac 默认平时第二功能：亮度− 亮");
        eq(glyphs(m, m.index(0x3F), FLIP), "上面图标" + FKeyFaces.DND + " 下面F6（暗）", "Mac F6 勿扰（月牙）");
        FixedPanel lw = panel(FixedLayout.LAPTOP, FixedLayout.WIN, tp), am = panel(FixedLayout.APPLE, FixedLayout.MAC, ap);
        eq(glyphs(lw, lw.index(0x3A), 0), "中F1", "笔记本放不下两层：平时只画 F1");
        eq(glyphs(lw, lw.index(0x3A), Proto.FN_HELD), "中图标" + FKeyFaces.MUTE, "笔记本按着 Fn：整片换成第二功能");
        eq(glyphs(lw, lw.index(0x43), Proto.FN_HELD), "中图标" + FKeyFaces.SCREENSHOT, "笔记本 F10 PrtSc：截屏的图标");
        eq(glyphs(lw, lw.index(0x44), Proto.FN_HELD), "中Super+Shift+S", "笔记本 F11 截屏工具：写组合本身");
        eq(glyphs(lw, lw.index(0x29), Proto.FN_HELD), "中FnLock", "笔记本按着 Fn：Esc 写 FnLock");
        eq(glyphs(am, am.index(0x3A), FLIP), "中图标" + FKeyFaces.BRIGHT_DOWN, "苹果紧凑（半行高）平时第二功能：只画亮度−");
        eq(glyphs(am, am.index(0x3A), FLIP | Proto.FN_HELD), "中F1", "苹果紧凑按着 fn：F1");
        FixedPanel c = panel(FixedLayout.P75, FixedLayout.WIN, CUSTOM);
        eq(glyphs(c, c.index(0x3A), Proto.FN_HELD), "上面F13 下面F1（暗）", "75% 自定义 F1 = F13");
        eq(glyphs(c, c.index(0x3B), Proto.FN_HELD), "上面Ctrl+C 下面F2（暗）", "自定义组合键 Ctrl+C");
        eq(glyphs(c, c.index(0x44), Proto.FN_HELD), "中F11", "认不出的 id 当没配：只写 F11");
        eq(glyphs(c, c.index(0x45), Proto.FN_HELD), "上面Home 下面F12（暗）", "单个键写键名");
    }

    static FixedPanel panel(String size, String mode, String[] fkeys) {
        FixedPanel p = new FixedPanel();
        p.setBoard(layout(FixedLayout.ANSI, size, mode, fkeys), FixedLayoutTest.table("us", mode));
        p.place(933, 352, GAP, 10);
        return p;
    }

    // ═══════════ 字放不放得进键帽 ═══════════

    /**
     * 估的字宽（em，偏宽一点：Noto Sans 的大约值，Android 默认 Roboto 还窄 5% 左右）：大写 0.72、M W 0.95，小写 0.58、m w 0.9、i l j 0.3，
     * 数字 0.6，空格 / 标点 0.42，"…" 0.9，别的（⇧ ⌘ ⌃ ⌥、箭头、汉字……）1.0。只用来查 FixedPanel.fitText 的结果放不放得进，不是真字体。
     */
    static final FixedPanel.TextWidth EST = (str, size) -> {
        float em = 0;
        for (int i = 0; i < str.length(); ) {
            int c = str.codePointAt(i);
            i += Character.charCount(c);
            if (c == 'M' || c == 'W') em += 0.95f;
            else if (c >= 'A' && c <= 'Z') em += 0.72f;
            else if (c == 'm' || c == 'w') em += 0.9f;
            else if (c == 'i' || c == 'l' || c == 'j') em += 0.3f;
            else if (c >= 'a' && c <= 'z') em += 0.58f;
            else if (c >= '0' && c <= '9') em += 0.6f;
            else if (c == ' ' || (c >= 0x21 && c <= 0x7E)) em += 0.42f;
            else if (c == 0x2026) em += 0.9f;
            else em += 1.0f;
        }
        return em * size;
    };

    /** 估的行高 ÷ 字号（Noto Sans 的 ascender + descender ≈ 1.36，偏高；Roboto 的 FontMetrics 约 1.17）。 */
    static final float EST_LINE = 1.36f;

    /**
     * 字放进键帽（FixedPanel.textRooms / fitText，FixedKeyboardView 画每一个字都走它）：
     * ① 规则本身：放得下不动、放不下先缩、到最小字号还比 room 宽但放得进 hard 不动；一行放不进 hard 的组合键在 "+" / ⌃⌥⇧⌘ 后面折行（挑字号最大的折法、
     *    行高加起来不超过 vroom），折不下才末尾省略到刚好放得下、连"…"都放不下只写"…"；只有一个字的不省略；只许一行的位置（vroom 0）不折。
     * ② 每种尺寸在一格正好 32dp（逐级缩的门槛，最窄的时候）和几种常见窗口里，每个键的每个字（F 行各种配法 × Fn 状态 × 修饰键）按估的字宽放完：每行都不出键帽、
     *    几行不超过 vroom，折行的拼回去就是原字、省略的是原字的开头 + "…"；一行放得下的绝不折、不省略（键帽上原有的字和以前画得一样）。
     * ③ 钉死：默认 ThinkPad 预设 F11 的 "Super+Shift+S"（以前在一格 32dp 时每边伸出键帽 6–12dp，审查发现）在 933 宽的 TKL 上折成 "Super+" / "Shift+S"。
     */
    static void textFit() {
        FixedPanel.TextWidth per = (str, size) -> str.codePointCount(0, str.length()) * 0.6f * size;   // 每个字 0.6em
        FixedPanel.FittedText f = FixedPanel.fitText("Esc", 14, 7, new float[]{100, 110, 0}, 1.2f, per);
        check(f.text().equals("Esc") && f.size == 14, "放得下：字和字号都不动");
        f = FixedPanel.fitText("PrtSc", 14, 7, new float[]{30, 40, 0}, 1.2f, per);   // 14 号宽 42，缩到 30 / (5 × 0.6) = 10
        check(f.text().equals("PrtSc") && near(f.size, 10), "放不下 room：按比例缩到刚好（" + f.size + "）");
        f = FixedPanel.fitText("Super+P", 14, 7, new float[]{20, 30, 100}, 1.2f, per);   // 7 号宽 29.4：比 room 宽、放得进 hard
        check(f.text().equals("Super+P") && f.size == 7, "到最小字号还比 room 宽、放得进 hard：一行、不折（" + f.text() + "）");
        f = FixedPanel.fitText("Super+Shift+S", 14, 7, new float[]{30, 30, 40}, 1.2f, per);   // 一行 7 号 54.6 放不下；两行 "Super+" / "Shift+S" 最长 7 个字
        check(f.text().equals("Super+\nShift+S") && near(f.size, 30 / (7 * 0.6f)),
                "一行放不下的组合键：在 + 后面折成两行（三行字号更大也不折三行）、字号照最长那行（" + f.text().replace('\n', '/') + " " + f.size + "）");
        f = FixedPanel.fitText("Super+Shift+S", 14, 7, new float[]{26, 28, 15}, 1.2f, per);   // 两行要 2 × 1.2 × 7 = 16.8 > 15：折不下
        check(f.lines.length == 1 && f.text().endsWith(FixedPanel.ELLIPSIS) && f.size == 7, "折行高不够：一行、末尾省略（" + f.text() + "）");
        f = FixedPanel.fitText("Super+Shift+S", 14, 7, new float[]{20, 24, 0}, 1.2f, per);   // 只许一行；7 号每个字 4.2：放得下 5 个字 = 4 个 + "…"
        check(f.text().equals("Supe…") && f.size == 7, "只许一行的位置：末尾省略到刚好放得下（" + f.text() + "）");
        f = FixedPanel.fitText("Super+Ctrl+Alt+X", 14, 7, new float[]{26, 26, 40}, 1.2f, per);   // 两行最好也要 10 个字（7 号 42）；三行最长 6 个字
        check(f.text().equals("Super+\nCtrl+\nAlt+X"), "两行放不下才折三行（" + f.text().replace('\n', '/') + "）");
        f = FixedPanel.fitText("⌃⌥⇧⌘F23", 14, 7, new float[]{20, 22, 40}, 1.2f, per);
        check(f.lines.length >= 2 && String.join("", f.lines).equals("⌃⌥⇧⌘F23"), "Mac 的写法在修饰键符号后面折（" + f.text().replace('\n', '/') + "）");
        f = FixedPanel.fitText("Super+Shift+S", 14, 7, new float[]{20, 3, 40}, 1.2f, per);
        check(f.text().equals(FixedPanel.ELLIPSIS), "连两个字都放不下：只写\"…\"（" + f.text() + "）");
        f = FixedPanel.fitText("🌐", 14, 7, new float[]{2, 3, 40}, 1.2f, per);
        check(f.text().equals("🌐"), "只有一个字（一个码点）的不省略");
        f = FixedPanel.fitText("Scroll Lock", 14, 7, new float[]{20, 34, 40}, 1.2f, per);   // 没有能折的地方；7 号放得下 8 个字：截在空格后面要去掉空格
        check(f.lines.length == 1 && f.text().equals("Scroll…"), "不是组合键不折；省略号前面不留空格（" + f.text() + "）");
        f = FixedPanel.fitText("Esc", 5, 7, new float[]{6, 8, 0}, 1.2f, per);   // 起始字号比最小值还小、放不下 room：不放大
        check(f.size <= 5, "起始字号本来比 7 号小的：只缩不放大（" + f.size + "）");
        eq(String.join("|", FixedPanel.breakTokens("Super+Shift+S")), "Super+|Shift+|S", "breakTokens：每个 + 后面");
        eq(String.join("|", FixedPanel.breakTokens("⇧⌘4")), "⇧|⌘|4", "breakTokens：⌃⌥⇧⌘ 后面");
        eq(String.join("|", FixedPanel.breakTokens("A+")), "A+", "breakTokens：最后一段不空");
        for (int slot = 0; slot <= FixedPanel.Glyph.LOWER; slot++) for (boolean corner : new boolean[]{false, true}) for (float w : new float[]{8, 22, 28, 60}) {
            float[] r = FixedPanel.textRooms(slot, w, 31, 3, 1, corner);
            boolean multi = slot == FixedPanel.Glyph.CENTER || slot == FixedPanel.Glyph.UPPER;
            check(r[0] <= r[1] && r[1] >= 0 && r[1] <= w && (r[2] > 0) == multi,
                    "textRooms 位置 " + slot + " 宽 " + w + (corner ? " 带角上小字" : "") + "：room ≤ hard ≤ 键帽宽、只有正中和上半那面许折行（" + Arrays.toString(r) + "）");
        }
        float[] up = FixedPanel.textRooms(FixedPanel.Glyph.UPPER, 28, 31, 3, 1, false), cn = FixedPanel.textRooms(FixedPanel.Glyph.CORNER, 28, 31, 3, 1, false);
        float uv = FixedPanel.UPPER_VROOM;
        check(near(up[0], 22) && near(up[1], 26) && near(up[2], uv * 31) && near(cn[0], 12.6f) && near(cn[1], 23) && cn[2] == 0,
                "textRooms：上半 22 / 26 / " + uv + "h、角上 12.6 / 23 / 0（键帽 28 × 31、内边距 3、离边 1）");
        // 上半那面折行的那一块不碰下面的小字 F1：几行中心 0.38h（FixedKeyboardView.drawGlyphs）、高 ≤ UPPER_VROOM·h；
        // F1 中心 0.80h、字号 ≤ 0.22h、字框半高约 0.6 个字号 → F1 的上沿不高于 0.668h
        check(0.38f + uv / 2 <= 0.80f - 0.6f * 0.22f && 0.38f - uv / 2 >= 0.05f, "上半那面折行的范围和下面的 F1 不重叠、不出键帽上沿");

        // ② 扫各种尺寸
        int lines = 0, cut = 0, wrapped = 0, panels = 0;
        String bad = null;
        int[] mods = {0, 0x02, 0x40, 0x04};
        float pad = 3, edge = 1, min = 7;
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : FixedLayout.SHAPES) for (String m : MODES) for (String[] c : configs()) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            FixedLegends.Table t = FixedLayoutTest.geoTable(sh, m);
            FixedLayout l = layout(sh, sz, m, c);
            int narrow = (int) Math.ceil(l.width * 32);
            int[][] windows = {{narrow, Math.round(narrow / l.width * 1.1f * l.height)}, {933, 352}, {1200, 500}};
            for (boolean menu : new boolean[]{false, true}) for (int[] wh : windows) {
                if (!KeyboardPlan.fixedFits(l, wh[0], T)) continue;
                FixedPanel p = new FixedPanel();
                p.setBoard(l, t);
                p.setMenuKey(menu);
                p.place(wh[0], wh[1], GAP, 10);
                panels++;
                for (int i = 0; i < p.keys().size(); i++) {
                    for (int fn : FN_STATES) for (int md : mods) {
                        List<FixedPanel.Glyph> gs = p.glyphsAt(i, md, false, fn, "Space");
                        boolean corner = false;
                        for (FixedPanel.Glyph g : gs) corner |= g.slot == FixedPanel.Glyph.CORNER;
                        boolean named = p.named(i, FixedPanel.fnShown(fn));
                        for (int[] b : p.capBoxes(i)) {
                            float w = b[2] - b[0], h = b[3] - b[1];
                            if (w <= 0 || h <= 0) continue;
                            for (FixedPanel.Glyph g : gs) {
                                if (g.icon != 0) continue;
                                float size;
                                switch (g.slot) {   // 同 FixedKeyboardView.drawGlyphs 的起始字号（1dp = 1sp = 1px）
                                    case FixedPanel.Glyph.CENTER:
                                        size = named && g.text.codePointCount(0, g.text.length()) > 1 ? Math.min(13, h * 0.34f) : Math.min(18, h * 0.46f);
                                        break;
                                    case FixedPanel.Glyph.LOWER:
                                    case FixedPanel.Glyph.CORNER:
                                        size = Math.min(10, h * 0.22f);
                                        break;
                                    default:
                                        size = Math.min(14, h * 0.30f);
                                        break;
                                }
                                float[] r = FixedPanel.textRooms(g.slot, w, h, pad, edge, corner);
                                FixedPanel.FittedText ft = FixedPanel.fitText(g.text, size, min, r, EST_LINE, EST);
                                lines++;
                                String why = null;
                                boolean single = g.text.codePointCount(0, g.text.length()) <= 1;
                                for (String line : ft.lines) if (!single && EST.width(line, ft.size) > r[1] + 1e-3f) why = "有一行比键帽宽";
                                if (ft.size < Math.min(min, size) - 1e-3f) why = "字号比最小值还小";
                                boolean fitsOne = EST.width(g.text, Math.min(min, size)) <= r[1] || single;
                                if (ft.lines.length > 1) {
                                    wrapped++;
                                    if (!String.join("", ft.lines).equals(g.text)) why = "折行拼回去不是原字";
                                    if (ft.lines.length * EST_LINE * ft.size > r[2] + 1e-3f) why = "折的几行比 vroom 高";
                                    if (fitsOne) why = "一行放得下却折了";
                                } else if (!ft.lines[0].equals(g.text)) {
                                    cut++;
                                    String head = ft.lines[0].substring(0, ft.lines[0].length() - FixedPanel.ELLIPSIS.length());
                                    if (!ft.lines[0].endsWith(FixedPanel.ELLIPSIS) || !g.text.startsWith(head)) why = "省略的不是原字的开头 + …";
                                    if (fitsOne) why = "一行放得下却省略了";
                                }
                                if (why != null && bad == null) {
                                    bad = name(l) + " " + cname(c) + " " + wh[0] + "×" + wh[1] + " " + p.keys().get(i) + " \"" + g.text + "\" → \""
                                            + ft.text().replace('\n', '/') + "\" " + ft.size + " 号（键帽 " + w + " × " + h + "、" + Arrays.toString(r) + "）：" + why;
                                }
                            }
                        }
                    }
                }
            }
        }
        check(bad == null, "每种尺寸每个字都放得进键帽" + (bad == null ? "" : "：" + bad));
        check(wrapped > 0 && cut > 0 && lines > 100000, "扫到了要折行的、要省略的（" + wrapped + " / " + cut + " / " + lines + " 行）");
        // ③ 钉死：默认 ThinkPad 预设 F11，933 宽的 TKL（一格约 51dp）上折成两行；一格 32dp（最窄）时也放得进键帽（折行或省略，按估的字宽）
        for (int width : new int[]{933, 0}) {
            FixedLayout l = layout(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.WIN, FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD));
            FixedPanel p = new FixedPanel();
            p.setBoard(l, FixedLayoutTest.table("us", FixedLayout.WIN));
            int pw = width > 0 ? width : (int) Math.ceil(l.width * 32);
            p.place(pw, width > 0 ? 352 : Math.round(32 * 1.1f * l.height), GAP, 10);
            int[] b = p.capBoxes(p.index(0x44)).get(0);
            float w = b[2] - b[0], h = b[3] - b[1];
            List<FixedPanel.Glyph> gs = p.glyphsAt(p.index(0x44), 0, false, 0, "Space");
            FixedPanel.FittedText upper = FixedPanel.fitText(gs.get(0).text, Math.min(14, h * 0.30f), min,
                    FixedPanel.textRooms(FixedPanel.Glyph.UPPER, w, h, pad, edge, false), 1.17f, EST);
            boolean ok = "Super+Shift+S".equals(gs.get(0).text) && (width > 0 ? upper.text().equals("Super+\nShift+S") : EST.width(upper.lines[0], upper.size) <= w);
            check(ok, "TKL " + pw + " 宽（键帽 " + w + " × " + h + "）：F11 上面写 \"" + upper.text().replace('\n', '/') + "\" " + upper.size + " 号");
        }
        System.err.println("  键帽上的字：" + panels + " 块面板、" + lines + " 个字，折行 " + wrapped + "、省略 " + cut + "（估的字宽；真字体只在真机上看）");
    }

    // ═══════════ 三条触摸路径 ═══════════

    /**
     * 每种 尺寸 × 形状 × 模式 × 配法（不含 65%：不上第二功能）× 带不带 ⋯ × 两种窗口：
     * ① 逐点（隔 4px）：分流（真 TouchRouter + FixedPanel.regions 报的区域）和面板自己收触摸（keyAt → keyId）同一个 id、同一种区域。
     * ② 每个 F 键和 Esc：区域那一路（按在段的正中）和 key() 那一路，同一串操作（单按、按着 Fn 按、Fn+Esc 翻转后单按、再翻回来）报文一模一样，
     *    出的是该出的那一面（{@link #expect}），最后多媒体 / 系统键的槽、键盘报文全松开。
     */
    static void paths() {
        int panels = 0, points = 0, keysRun = 0, seconds = 0;
        String first = null;
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : FixedLayout.SHAPES) for (String m : MODES) for (String[] c : configs()) {
            if (!FixedLayout.available(sh, sz, m) || FixedLayout.P65.equals(sz)) continue;
            FixedLayout l = layout(sh, sz, m, c);
            for (boolean menu : new boolean[]{false, true}) for (int[] wh : new int[][]{{933, 352}, {600, 260}}) {
                FixedPanel p = new FixedPanel();
                p.setBoard(l, FixedLayoutTest.geoTable(sh, m));
                p.setMenuKey(menu);
                p.place(wh[0], wh[1], GAP, 10);
                panels++;
                String what = name(l) + " " + cname(c) + (menu ? "（带 ⋯）" : "") + " " + wh[0] + "×" + wh[1];
                TouchRouter.Region[] real = FixedCompactTest.realRegions(p);
                TouchRouter router = new TouchRouter(new KeyRowsTest.Quiet());
                router.setRegions(real);
                for (int y = 0; y < wh[1] && first == null; y += 4) for (int x = 0; x < wh[0]; x += 4) {
                    points++;
                    TouchRouter.Region hit = router.hit(x, y);
                    int i = p.keyAt(x, y);
                    int a = hit == null ? -1 : hit.id, b = i < 0 ? -1 : p.keyId(i);
                    if (a != b || (hit != null && hit.type != FixedLayout.regionType(p.keys().get(i)))) {
                        first = what + " (" + x + ", " + y + ") 分流 " + Integer.toHexString(a) + "、面板 " + Integer.toHexString(b);
                        break;
                    }
                }
                int fnIndex = p.index(FixedLayout.FN_ID), escIndex = p.index(0x29);
                if (fnIndex < 0 || escIndex < 0) {
                    if (first == null) first = what + " 没有 Fn / Esc";
                    continue;
                }
                for (int i = 0; i < p.keys().size() && first == null; i++) {
                    int fi = p.frowIndex(i);
                    if (fi == 0 && i != escIndex) continue;
                    keysRun++;
                    for (boolean flip0 : new boolean[]{false, true}) {
                        String why = runKey(p, real, i, fnIndex, escIndex, flip0);
                        if (why != null) {
                            first = what + " " + p.keys().get(i) + "（翻转 " + flip0 + "）：" + why;
                            break;
                        }
                        if (fi > 0) seconds++;
                    }
                }
            }
        }
        check(first == null, "F 行三条触摸路径" + (first == null ? "" : "：" + first));
        check(keysRun > 0 && seconds > 0, "真按过 F 行的键（" + keysRun + "）、出过第二功能");
        System.err.println("  F 行三条触摸路径：" + panels + " 块面板，逐点 " + points + " 个，F 行的键 " + keysRun + " 个（两种翻转各跑一遍）");
    }

    /** 两条路（区域 / key()）各跑一遍第 i 个键（F 键或 Esc），不对返回原因。flip0 = 起始的 F 行翻转。 */
    static String runKey(FixedPanel p, TouchRouter.Region[] real, int i, int fnIndex, int escIndex, boolean flip0) {
        FixedLayout l = p.layout();
        int id = p.keyId(i), fnId = p.keyId(fnIndex), escId = p.keyId(escIndex);
        int[] c = FixedCompactTest.center(p, i), fc = FixedCompactTest.center(p, fnIndex), ec = FixedCompactTest.center(p, escIndex);
        KeyHoldsTest.H a = new KeyHoldsTest.H(real), b = new KeyHoldsTest.H(real);
        a.t.setFrowFlip(flip0);
        b.t.setFrowFlip(flip0);
        List<String> outA = new ArrayList<>(), outB = new ArrayList<>();
        long t = 1000;
        boolean flip = flip0;
        // 单按
        outA.add(press(a, true, 0, c, id, t, t + 50));
        outB.add(press(b, false, 0, c, id, t, t + 50));
        String alone = outA.get(0);
        t += 200;
        // 按着 Fn 按
        FixedCompactTest.touch(a, 1, fc, true, t);
        b.kd(fnId, t);
        outA.add(press(a, true, 0, c, id, t + 50, t + 100));
        outB.add(press(b, false, 0, c, id, t + 50, t + 100));
        String withFn = outA.get(1);
        FixedCompactTest.touch(a, 1, fc, false, t + 150);
        b.ku(fnId, t + 150);
        boolean toggles = l.escFnLock();
        if (i == escIndex && toggles) flip = !flip;   // 测的就是 Esc：按着 Fn 按它本身就翻了一次
        t += 300;
        // Fn+Esc 翻转（Esc 是翻转开关时）
        int clicks = a.r.clicks;
        FixedCompactTest.touch(a, 1, fc, true, t);
        b.kd(fnId, t);
        FixedCompactTest.touch(a, 2, ec, true, t + 50);
        b.kd(escId, t + 50);
        FixedCompactTest.touch(a, 2, ec, false, t + 100);
        b.ku(escId, t + 100);
        FixedCompactTest.touch(a, 1, fc, false, t + 150);
        b.ku(fnId, t + 150);
        if (toggles) {
            flip = !flip;
            a.to(t + 50 + KeyHolds.FLIP_BUZZ_MS);   // 翻转的确认震动晚 FLIP_BUZZ_MS（当场震会被 Esc 的"嗒"盖掉）
            b.to(t + 50 + KeyHolds.FLIP_BUZZ_MS);
            if (a.r.clicks != clicks + 1) return "Fn+Esc 翻转没震一下";
            if (a.t.frowFlip() != flip || b.t.frowFlip() != flip || ((a.r.lastF & FLIP) != 0) != flip) return "Fn+Esc 没翻转 / 没报 FROW_FLIP";
        } else if (a.t.frowFlip() != flip0 || b.t.frowFlip() != flip0) {
            return "Esc 不是翻转开关却翻了";
        }
        t += 300;
        String afterToggle = press(a, true, 0, c, id, t, t + 50);
        String afterToggleB = press(b, false, 0, c, id, t, t + 50);
        if (!afterToggle.equals(afterToggleB)) return "翻转后单按两条路不一样：" + afterToggle + " / " + afterToggleB;
        for (int k = 0; k < outA.size(); k++) if (!outA.get(k).equals(outB.get(k))) return "第 " + k + " 步两条路不一样：" + outA.get(k) + " / " + outB.get(k);
        if (!a.r.keys.equals(b.r.keys) || !a.r.controls.equals(b.r.controls) || !a.r.mods.equals(b.r.mods)) {
            return "两条路的报文不一样：" + a.r.keys + a.r.controls + " / " + b.r.keys + b.r.controls;
        }
        // 该出的那一面
        int fi = p.frowIndex(i);
        if (fi > 0) {
            FKeyFunctions.Function f = l.frow(fi);
            String base = expectKey(FKeyFunctions.fUsage(fi)), second = expectSecond(f, l.mode);
            if (!alone.equals(flip0 ? second : base)) return "单按出 " + alone + "（应为 " + (flip0 ? second : base) + "）";
            if (!withFn.equals(flip0 ? base : second)) return "按着 Fn 出 " + withFn + "（应为 " + (flip0 ? base : second) + "）";
            if (!afterToggle.equals(flip ? second : base)) return "翻转后单按出 " + afterToggle;
        } else {
            // Esc 自己：单按出 Esc；按着 Fn：是翻转开关时什么都不发（翻转），不然出 Esc
            if (!alone.equals(expectKey(0x29))) return "Esc 单按出 " + alone;
            if (!withFn.equals(toggles ? "" : expectKey(0x29))) return "按着 Fn 按 Esc 出 " + withFn;
        }
        a.to(t + 5000);
        if (a.r.lastConsumer != 0 || a.r.lastSystem != 0 || !(a.r.keys.isEmpty() || a.lastKeys().equals("[00|]"))) {
            return "最后没全松开：" + a.lastKeys() + " c" + a.r.lastConsumer + " s" + a.r.lastSystem;
        }
        return null;
    }

    /** 按下第 slot 根手指（区域那一路按在 c，key() 那一路发 id）、按着时出的东西（新的键盘报文 + 多媒体 / 系统键）、再松开。 */
    static String press(KeyHoldsTest.H h, boolean region, int slot, int[] c, int id, long down, long up) {
        int nk = h.r.keys.size(), nc = h.r.controls.size();
        if (region) FixedCompactTest.touch(h, slot, c, true, down);
        else h.kd(id, down);
        StringBuilder sb = new StringBuilder();
        for (int k = nk; k < h.r.keys.size(); k++) sb.append(h.r.keys.get(k));
        for (int k = nc; k < h.r.controls.size(); k++) sb.append(h.r.controls.get(k));
        if (region) FixedCompactTest.touch(h, slot, c, false, up);
        else h.ku(id, up);
        return sb.toString();
    }

    /** 单个键按下的那一帧（KeyHoldsTest.R 的写法）。 */
    static String expectKey(int usage) {
        return String.format("[00|%02x]", usage);
    }

    /** 第二功能按下时出的东西：多媒体 "c" + 码、系统键 "s" + 码、组合键先按修饰键再按键（自带修饰键的那条路）。 */
    static String expectSecond(FKeyFunctions.Function f, String mode) {
        int code = f.code(mode);
        switch (f.kind) {
            case FKeyFunctions.CONSUMER:
                return "c" + Integer.toHexString(code);
            case FKeyFunctions.SYSTEM:
                return "s" + Integer.toHexString(code);
            default: {
                int mods = (code >> 8) & 0xFF;
                String key = String.format("[%02x|%02x]", mods, code & 0xFF);
                return mods == 0 ? key : String.format("[%02x|]", mods) + key;
            }
        }
    }

    // ═══════════ 小红点照旧 ═══════════

    static void stick() {
        int given = 0, refused = 0;
        for (String sz : FixedLayout.ALL_SIZES) for (String sh : FixedLayout.SHAPES) for (String m : MODES) {
            if (!FixedLayout.available(sh, sz, m)) continue;
            FixedLayout l = layout(sh, sz, m, FKeyFunctions.preset(FKeyFunctions.defaultPreset(m)));
            boolean want = FixedLayout.WIN.equals(m) && (FixedLayout.LAPTOP.equals(sz) || FixedLayout.P75.equals(sz) || FixedLayout.P65.equals(sz));
            check(l.stickAllowed() == want, name(l) + " 给不给小红点照旧（笔记本 / 75% / 65% 的 Windows 模式给）");
            for (int[] wh : new int[][]{{933, 352}, {751, 300}, {1200, 500}}) {
                if (!KeyboardPlan.fixedFits(l, wh[0], T)) continue;
                KeyboardPlan.Plan p = KeyboardPlan.planFixed(KeyboardPlan.POINTER_STICK, KeyboardPlan.KEYS_OFF, wh[0], wh[1], null, l, T);
                if (want) {
                    given++;
                    check(p.stickAt != KeyboardPlan.STICK_NONE && p.hole != null && p.middleKey != null,
                            name(l) + " " + wh[0] + "×" + wh[1] + " 照旧有红点和三个鼠标键");
                    // 红点、三个鼠标键不碰 Fn 键（Fn 在空格外面）
                    FixedPanel panel = new FixedPanel();
                    panel.setBoard(l, FixedLayoutTest.geoTable(sh, m));
                    panel.setMenuKey(true);
                    panel.setHole(p.hole);
                    panel.place(p.keyboard[2] - p.keyboard[0], p.keyboard[3] - p.keyboard[1], GAP, 10);
                    int[] fb = panel.box(panel.index(FixedLayout.FN_ID));
                    check(p.hole == null || fb[2] <= p.hole[0] || fb[0] >= p.hole[1], name(l) + " Fn 键不在让给三个鼠标键的空位里");
                } else {
                    refused++;
                    check(p.stickAt == KeyboardPlan.STICK_NONE && p.hole == null, name(l) + " " + wh[0] + "×" + wh[1] + " 照旧不给小红点");
                }
            }
        }
        check(given > 0 && refused > 0, "小红点：给的 " + given + "、不给的 " + refused);
    }

    // ═══════════ 工具 ═══════════

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    static void eq(String got, String want, String what) {
        check(want.equals(got), what + "：得到 " + got + "，应为 " + want);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }

    /** 随机配法：从全部功能、随机组合键（字母 + 随机自带修饰键）、认不出的 id 里挑。 */
    static String[] randomConfig(Random rnd) {
        List<FKeyFunctions.Function> all = FKeyFunctions.all();
        String[] out = new String[12];
        for (int i = 0; i < 12; i++) {
            int r = rnd.nextInt(all.size() + 3);
            out[i] = r < all.size() ? all.get(r).id : r == all.size() ? FKeyFunctions.keyFunctionId(0x04 + rnd.nextInt(26) | rnd.nextInt(16) << 8) : "bogus";
        }
        return out;
    }
}
