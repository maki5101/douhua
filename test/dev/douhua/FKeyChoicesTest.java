package dev.douhua;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置 → 键盘 → 实体键盘"F1–F12 的第二功能"那一节（协议 24，2026-11-02）的离线测试：FKeyChoices（列表、选中哪项、自定义组合键存成什么、
 * 预设那一行、什么时候藏）+ Boards 的存取 + 端到端（设置页选的每一项写进 Boards，照 FixedLayout 拼区域 id 的同一条路 FKeyFunctions.regionId 拼好，
 * 经真 TouchRouter / KeyHolds 按下去，发的就是列表上写的那个码）。
 *
 * 动作清单里的快捷键照真的 Actions.java 源码解析（Actions 依赖安卓、离线编不到）：hid("id", …, 用法码, 修饰键) 和 apple(…) 两种行，
 * 苹果设备的码照 Actions.hid 的规则（带 Ctrl 的换成 ⌘，apple(…) 另指定的照它）。
 * 另外照源码核对：FKeyFunctions 里每个有名字的功能 SettingsActivity 里都有 R.string.fkf_<id>、九个 strings.xml 都有；这一节的字九个文件一一对应、占位符一样。
 * 只证明规则照设计跑；列表长什么样、字放不放得下、点起来顺不顺要真机看。
 */
public final class FKeyChoicesTest {
    static final String WIN = FixedLayout.WIN, MAC = FixedLayout.MAC;
    /** 修饰键位（同 Combos / Hid：Ctrl 1、Shift 2、Alt 4、GUI 8；Combos 引用了 Actions、离线编不到，用 Hid 的）。 */
    static final int CTRL = Hid.MOD_CTRL, SHIFT = Hid.MOD_SHIFT, ALT = Hid.MOD_ALT, GUI = Hid.MOD_META;
    /** 自定义组合键能挑的键（同 Combos.KEY_USAGES：字母、数字、F1–F12、Enter Esc ⌫ Tab 空格、编辑区、方向键、标点、PrtSc）。 */
    static final int[] USAGES;

    static {
        List<Integer> u = new ArrayList<>();
        for (int c = 0x04; c <= 0x2C; c++) u.add(c);
        for (int c = 0x2D; c <= 0x38; c++) if (c != 0x32) u.add(c);
        for (int c = 0x3A; c <= 0x46; c++) u.add(c);
        for (int c = 0x49; c <= 0x52; c++) u.add(c);
        USAGES = new int[u.size()];
        for (int i = 0; i < USAGES.length; i++) USAGES[i] = u.get(i);
    }

    static final String[] DIRS = {"values", "values-zh", "values-ja", "values-ko", "values-fr", "values-de", "values-it", "values-es", "values-ar"};

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws IOException {
        List<String[]> actions = parseActions();
        check(actions.size() >= 30, "从 Actions.java 解析出发 HID 的动作（" + actions.size() + " 个，太少说明源码的写法变了，解析要跟着改）");
        listShape(actions);
        roundTrip(actions);
        offeredByMode(actions);
        customAndCanonical(actions);
        presetRow();
        endToEnd(actions);
        randomSettings(actions);
        names();
        System.err.println("F 行第二功能的设置页：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 动作清单（照 Actions.java 源码） ═══════════

    /** Actions.java 里发 HID 的动作：{id, Windows 的码, 苹果设备的码}（十进制字符串），按清单的顺序。 */
    static List<String[]> parseActions() throws IOException {
        String src = FixedLegendsTest.read("src/dev/douhua/Actions.java");
        Pattern hid = Pattern.compile("hid\\(\"(\\w+)\", [^;]*?, (0x[0-9A-Fa-f]+|letter\\('([a-z])'\\)), ([A-Z0-9 |]+)\\);");
        Pattern apple = Pattern.compile("apple\\(\"(\\w+)\", (0x[0-9A-Fa-f]+|letter\\('([a-z])'\\)), ([A-Z0-9 |]+)\\);");
        Map<String, int[]> codes = new java.util.LinkedHashMap<>();
        Matcher m = hid.matcher(src);
        while (m.find()) {
            int usage = usage(m.group(2), m.group(3));
            int mods = mods(m.group(4));
            int appleMods = (mods & CTRL) != 0 ? (mods & ~CTRL) | GUI : mods;   // 同 Actions.hid
            codes.put(m.group(1), new int[]{usage | mods << 8, usage | appleMods << 8});
        }
        m = apple.matcher(src);
        int overrides = 0;
        while (m.find()) {
            int[] c = codes.get(m.group(1));
            if (c == null) continue;
            c[1] = usage(m.group(2), m.group(3)) | mods(m.group(4)) << 8;
            overrides++;
        }
        check(overrides >= 5, "苹果设备另指定的组合键也解析到了（" + overrides + " 个）");
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : codes.entrySet()) {
            out.add(new String[]{e.getKey(), String.valueOf(e.getValue()[0]), String.valueOf(e.getValue()[1])});
        }
        return out;
    }

    private static int usage(String expr, String letter) {
        return letter != null ? 0x04 + (letter.charAt(0) - 'a') : Integer.parseInt(expr.substring(2), 16);
    }

    private static int mods(String expr) {
        int m = 0;
        for (String p : expr.split("\\|")) {
            switch (p.trim()) {
                case "CTRL": m |= CTRL; break;
                case "SHIFT": m |= SHIFT; break;
                case "ALT": m |= ALT; break;
                case "WIN": m |= GUI; break;
                case "0": break;
                default: throw new IllegalStateException("认不出的修饰键：" + p);
            }
        }
        return m;
    }

    /** 设置页传给 FKeyChoices 的那份（同 SettingsActivity.fkeyShortcuts：Mac 模式取苹果设备的码）。 */
    static List<FKeyChoices.Shortcut> shortcuts(List<String[]> actions, String mode) {
        List<FKeyChoices.Shortcut> out = new ArrayList<>();
        for (String[] a : actions) out.add(new FKeyChoices.Shortcut(a[0], Integer.parseInt(MAC.equals(mode) ? a[2] : a[1])));
        return out;
    }

    static List<FKeyChoices.Item> items(List<String[]> actions, String mode) {
        return FKeyChoices.items(mode, shortcuts(actions, mode));
    }

    // ═══════════ 列表的样子 ═══════════

    /**
     * "无"第一、"自定义组合键…"最后；分组标题按 FKeyFunctions 的分组从小到大各一个、每组下面至少一项、组里的项都属于这一组；
     * 码不重（有名字的键盘键和快捷键之间也不重：截屏 0x46 = PrtSc 只列一次）；快捷键用法码不是 0、存 "key:XXXX"。
     */
    static void listShape(List<String[]> actions) {
        for (String mode : new String[]{WIN, MAC}) {
            List<FKeyChoices.Item> items = items(actions, mode);
            check(items.get(0).type == FKeyChoices.FUNCTION && "none".equals(items.get(0).id), mode + "：\"无\"排第一");
            FKeyChoices.Item last = items.get(items.size() - 1);
            check(last.type == FKeyChoices.CUSTOM && last.id == null, mode + "：最后一项是\"自定义组合键…\"");
            int customs = 0, lastGroup = -1, shortcuts = 0;
            boolean groupsOk = true, headerFollowed = true, codesUnique = true, shortcutOk = true;
            Set<Integer> codes = new HashSet<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                FKeyChoices.Item it = items.get(i);
                if (it.type == FKeyChoices.CUSTOM) customs++;
                if (it.type == FKeyChoices.HEADER) {
                    groupsOk &= it.group > lastGroup && !it.selectable();
                    lastGroup = it.group;
                    headerFollowed &= i + 1 < items.size() && items.get(i + 1).selectable();
                    continue;
                }
                check(it.selectable(), mode + "：" + it + " 点得了");
                if (i > 0 && it.type != FKeyChoices.CUSTOM) groupsOk &= it.group == lastGroup;
                if (it.code != 0) codesUnique &= codes.add(it.code);
                if (it.id != null) check(ids.add(it.id), mode + "：id 不重：" + it.id);
                if (it.type == FKeyChoices.SHORTCUT) {
                    shortcuts++;
                    shortcutOk &= (it.code & 0xFF) != 0 && it.id.equals(FKeyFunctions.keyFunctionId(it.code)) && it.actionId != null
                            && it.group == FKeyChoices.GROUP_SHORTCUTS;
                }
                if (it.type == FKeyChoices.FUNCTION) check(it.function != null && it.function.id.equals(it.id), mode + "：" + it.id + " 带着功能本身");
            }
            check(customs == 1, mode + "：\"自定义组合键…\"只有一项");
            check(groupsOk, mode + "：分组标题从小到大各一个，组里的项都属于这一组");
            check(headerFollowed, mode + "：每个分组标题下面至少一项");
            check(codesUnique, mode + "：发的码不重（有名字的键盘键、快捷键之间）");
            check(shortcutOk && shortcuts >= 25, mode + "：快捷键 " + shortcuts + " 个，用法码不是 0、存 key:XXXX");
            check(lastGroup == FKeyChoices.GROUP_SHORTCUTS, mode + "：最后一组是快捷键");
            // 动作清单的截屏（PrtSc 0x46）和有名字的"截屏"重了：只列一次（有名字的那个）
            boolean shot = false;
            for (FKeyChoices.Item it : items) shot |= it.type == FKeyChoices.SHORTCUT && "screenshot".equals(it.actionId) && it.code == 0x46;
            check(!shot, mode + "：动作清单的截屏（PrtSc）不重复列");
            // 复制：Windows Ctrl+C、Mac ⌘C（同动作清单连苹果设备时的发法）
            int copy = MAC.equals(mode) ? 0x06 | GUI << 8 : 0x06 | CTRL << 8;
            boolean found = false;
            for (FKeyChoices.Item it : items) found |= it.type == FKeyChoices.SHORTCUT && "copy".equals(it.actionId) && it.code == copy;
            check(found, mode + "：复制是 " + FKeyFaces.comboLabel(copy, mode));
        }
        // 快捷键码为 0 / 重复的不列；没传快捷键时只有有名字的 + 自定义
        List<FKeyChoices.Shortcut> odd = Arrays.asList(new FKeyChoices.Shortcut("zero", 0x0100), new FKeyChoices.Shortcut("a", 0x0104),
                new FKeyChoices.Shortcut("dup", 0x0104), new FKeyChoices.Shortcut("prt", 0x46));
        List<FKeyChoices.Item> it = FKeyChoices.items(WIN, odd);
        int sc = 0;
        for (FKeyChoices.Item i : it) if (i.type == FKeyChoices.SHORTCUT) sc++;
        check(sc == 1, "用法码 0、重复的、和有名字功能重了的快捷键都不列（剩 " + sc + " 个）");
        List<FKeyChoices.Item> bare = FKeyChoices.items(WIN, null);
        boolean noShort = true;
        for (FKeyChoices.Item i : bare) noShort &= i.type != FKeyChoices.SHORTCUT && i.group != FKeyChoices.GROUP_SHORTCUTS || i.type == FKeyChoices.CUSTOM;
        check(noShort && bare.get(bare.size() - 1).type == FKeyChoices.CUSTOM, "没有快捷键：不出\"快捷键\"标题，最后照样是自定义组合键");
    }

    /** 列表里每一项（"自定义组合键…"以外）存进去再读出来：describe 回到它自己、selected 是它的下标。 */
    static void roundTrip(List<String[]> actions) {
        for (String mode : new String[]{WIN, MAC}) {
            List<FKeyChoices.Item> items = items(actions, mode);
            int n = 0;
            boolean ok = true;
            String bad = null;
            for (int i = 0; i < items.size(); i++) {
                FKeyChoices.Item it = items.get(i);
                if (it.type != FKeyChoices.FUNCTION && it.type != FKeyChoices.SHORTCUT) continue;
                n++;
                boolean good = FKeyChoices.describe(items, it.id, mode) == it && FKeyChoices.selected(items, it.id, mode) == i;
                if (!good && bad == null) bad = it.toString();
                ok &= good;
            }
            check(ok, mode + "：" + n + " 项存进去读回来都是它自己" + (bad == null ? "" : "（" + bad + " 不是）"));
            check(FKeyChoices.selected(items, null, mode) == 0 && FKeyChoices.selected(items, "gone", mode) == 0
                    && FKeyChoices.selected(items, "key:0000", mode) == 0 && FKeyChoices.selected(items, "none", mode) == 0,
                    mode + "：null、认不出的、用法码 0、\"无\"都标在\"无\"上");
            FKeyChoices.Item none = FKeyChoices.describe(new ArrayList<>(), "gone", mode);
            check(none != null && "none".equals(none.id) && none.function != null, mode + "：列表是空的也不给 null（行上写\"无\"）");
        }
    }

    /** Mac 模式不列只在 Windows 上有意思的三个组合键；存着的照样认得出（行上写名字 + 实际发的组合），列表里哪个都不标。 */
    static void offeredByMode(List<String[]> actions) {
        List<FKeyChoices.Item> win = items(actions, WIN), mac = items(actions, MAC);
        Set<String> winIds = new HashSet<>(), macIds = new HashSet<>();
        for (FKeyChoices.Item i : win) if (i.type == FKeyChoices.FUNCTION) winIds.add(i.id);
        for (FKeyChoices.Item i : mac) if (i.type == FKeyChoices.FUNCTION) macIds.add(i.id);
        Set<String> all = new HashSet<>();
        for (FKeyFunctions.Function f : FKeyFunctions.all()) all.add(f.id);
        check(winIds.equals(all), "Windows 模式列出全部有名字的功能（" + winIds.size() + " / " + all.size() + "）");
        Set<String> expectMac = new HashSet<>(all);
        expectMac.removeAll(FKeyChoices.WINDOWS_ONLY);
        check(macIds.equals(expectMac), "Mac 模式少的正好是 Super+P、Super+N、Copilot 键");
        for (String id : FKeyChoices.WINDOWS_ONLY) {
            check(FKeyFunctions.get(id) != null && FKeyFunctions.get(id).kind == FKeyFunctions.KEY, id + " 是组合键");
            FKeyChoices.Item d = FKeyChoices.describe(mac, id, MAC);
            check(d.type == FKeyChoices.FUNCTION && id.equals(d.id) && !mac.contains(d) && d.code == (FKeyFunctions.get(id).code & 0xFFFF),
                    "Mac 模式存着 " + id + "：认得出、不在列表里、码照旧");
            check(FKeyChoices.selected(mac, id, MAC) == -1, "Mac 模式存着 " + id + "：列表里哪个都不标");
        }
        // ThinkPad 那套在 Mac 模式下照样存得进、认得出（用户自己选的）
        for (String id : FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD)) {
            check(FKeyChoices.describe(mac, id, MAC).id.equals(id), "Mac 模式 ThinkPad 那套的 " + id + " 认得出");
        }
        for (String id : FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE)) {
            check(FKeyChoices.selected(win, id, WIN) >= 0 && FKeyChoices.selected(mac, id, MAC) >= 0, "苹果那套的 " + id + " 两种模式都列着");
        }
    }

    /** 自定义组合键：码正好是列着的有名字功能就存名字，不然 key:XXXX；读回来是快捷键 / 有名字的 / 不在列表里的自定义。 */
    static void customAndCanonical(List<String[]> actions) {
        List<FKeyChoices.Item> win = items(actions, WIN), mac = items(actions, MAC);
        int superP = 0x13 | GUI << 8, snipWin = 0x16 | (GUI | SHIFT) << 8, snipMac = 0x21 | (GUI | SHIFT) << 8;
        eq(FKeyChoices.canonical(win, superP), "display", "Windows 模式 Super+P = 切换显示器");
        eq(FKeyChoices.canonical(mac, superP), "key:0813", "Mac 模式 ⌘P 不是切换显示器，存组合键");
        eq(FKeyChoices.canonical(win, snipWin), "snip", "Windows 模式 Super+Shift+S = 截屏工具");
        eq(FKeyChoices.canonical(mac, snipMac), "snip", "Mac 模式 ⇧⌘4 = 截屏工具");
        eq(FKeyChoices.canonical(win, snipMac), "key:0a21", "Windows 模式 Super+Shift+4 不是截屏工具");
        eq(FKeyChoices.canonical(win, 0x46), "prtsc", "PrtSc");
        eq(FKeyChoices.canonical(mac, 0x68), "f13", "F13");
        eq(FKeyChoices.canonical(win, 0x06 | CTRL << 8), "key:0106", "Ctrl+C 存组合键");
        FKeyChoices.Item copy = FKeyChoices.describe(win, "key:0106", WIN);
        check(copy.type == FKeyChoices.SHORTCUT && "copy".equals(copy.actionId), "key:0106 读回是快捷键\"复制\"");
        FKeyChoices.Item macCopy = FKeyChoices.describe(mac, "key:0806", MAC);
        check(macCopy.type == FKeyChoices.SHORTCUT && "copy".equals(macCopy.actionId), "Mac 模式 key:0806（⌘C）读回是快捷键\"复制\"");
        check(FKeyChoices.describe(mac, "key:0106", MAC).type == FKeyChoices.CUSTOM, "Mac 模式 Ctrl+C 不是\"复制\"，是自定义组合键");
        FKeyChoices.Item p = FKeyChoices.describe(win, "key:0813", WIN);
        check(p.type == FKeyChoices.FUNCTION && "display".equals(p.id), "Windows 模式存成 key:0813 的也认成\"切换显示器\"");
        int weird = 0x2C | (CTRL | ALT | SHIFT) << 8;   // Ctrl+Shift+Alt+Space：动作清单里没有
        String id = FKeyChoices.canonical(win, weird);
        FKeyChoices.Item c = FKeyChoices.describe(win, id, WIN);
        check(c.type == FKeyChoices.CUSTOM && id.equals(c.id) && c.code == weird && !win.contains(c), "动作清单里没有的组合：自定义（带 id 和码，不在列表里）");
        int customAt = win.size() - 1;
        check(FKeyChoices.selected(win, id, WIN) == customAt, "自定义组合键标在\"自定义组合键…\"那一项上");
        eq(FKeyFaces.comboLabel(c.code, WIN), "Ctrl+Alt+Shift+Space", "行上写组合本身（Windows）");
        eq(FKeyFaces.comboLabel(snipMac, MAC), "⇧⌘4", "行上写组合本身（Mac）");
    }

    // ═══════════ 预设那一行、什么时候藏 ═══════════

    static void presetRow() {
        eq(Arrays.toString(FKeyChoices.presetChoices(FKeyFunctions.PRESET_THINKPAD)), "[thinkpad, apple, none]", "不是自定义：三项");
        eq(Arrays.toString(FKeyChoices.presetChoices(FKeyFunctions.PRESET_CUSTOM)), "[thinkpad, apple, none, custom]", "自定义：多一项排最后");
        check(FKeyChoices.keysShown(FKeyFunctions.PRESET_THINKPAD) && FKeyChoices.keysShown(FKeyFunctions.PRESET_APPLE)
                && FKeyChoices.keysShown(FKeyFunctions.PRESET_CUSTOM) && !FKeyChoices.keysShown(FKeyFunctions.PRESET_NONE),
                "选\"不用\"时\"平时按出\"和 12 行藏起来，别的都显示");
        BoardsTest.MapStore s = new BoardsTest.MapStore();
        Boards b = new Boards(s, null);
        eq(b.fkeyPreset(WIN) + "/" + b.fkeyPreset(MAC), "thinkpad/apple", "没存过：Windows ThinkPad、Mac 苹果");
        b.setFkey(MAC, 3, "lock");
        eq(b.fkeyPreset(MAC) + "/" + b.fkeyPreset(WIN), "custom/thinkpad", "改 Mac 的一个：Mac 自定义、Windows 不变");
        for (int n = 1; n <= 12; n++) b.setFkey(WIN, n, "none");
        eq(b.fkeyPreset(WIN), "none", "逐个改成\"无\"：认成\"不用\"（12 行藏起来）");
        b.applyFkeyPreset(MAC, FKeyFunctions.PRESET_APPLE);
        eq(b.fkeyPreset(MAC), "apple", "换回苹果那套：认得回来");
        b.applyFkeyPreset(WIN, FKeyFunctions.PRESET_THINKPAD);
        b.setFkey(WIN, 7, FKeyChoices.canonical(items(new ArrayList<>(), WIN), 0x13 | GUI << 8));
        eq(b.fkeyPreset(WIN), "thinkpad", "自定义组合键拼出 Super+P 存成\"display\"：预设照样认得出");
        boolean only = true;
        for (String k : s.written) only &= k.startsWith("kbx_");
        check(only, "只写了 kbx_ 开头的键");
    }

    // ═══════════ 端到端：设置页选的每一项，按下去发的就是列表上写的那个码 ═══════════

    /**
     * 每种模式、列表里每一项（外加一个自定义组合键）：写进 Boards 的 F1，照 FixedLayout 拼 F 键 id 的同一条路（FKeyFunctions.regionId(n, get(Boards.fkey))）拼好，
     * 经真 TouchRouter / KeyHolds 按下松开：平时出第二功能（翻转开）时发的正好是这一项的码（多媒体 / 系统键那两个槽、键盘报文的修饰键 + 键），松开配对；
     * 翻转关时出 F1 本身。"无"两面都是 F1。
     */
    static void endToEnd(List<String[]> actions) {
        int n = 0;
        for (String mode : new String[]{WIN, MAC}) {
            List<FKeyChoices.Item> items = items(actions, mode);
            List<String> ids = new ArrayList<>();
            for (FKeyChoices.Item it : items) if (it.type == FKeyChoices.FUNCTION || it.type == FKeyChoices.SHORTCUT) ids.add(it.id);
            ids.add(FKeyChoices.canonical(items, 0x2C | (CTRL | ALT) << 8));   // 自定义组合键 Ctrl+Alt+Space
            ids.add("display");   // Mac 模式存着的不在列表里的那种
            for (String id : ids) {
                BoardsTest.MapStore s = new BoardsTest.MapStore();
                Boards b = new Boards(s, null);
                b.setMode(mode);
                b.setFkey(mode, 1, id);
                eq(b.fkey(mode, 1), id, mode + " " + id + "：存进去读回来一样");
                eq(String.valueOf(new Boards(s, null).spec().fkeys[0]), id, mode + " " + id + "：Spec 带着它（FixedLayout.forEntry 照它拼）");
                FKeyFunctions.Function f = FKeyFunctions.get(b.fkey(mode, 1));
                int region = FKeyFunctions.regionId(1, f, mode);
                for (boolean flip : new boolean[]{true, false}) {
                    TouchRouter.Region[] regions = {KeyHoldsTest.key(region, 0), new TouchRouter.Region(Proto.REGION_PAD, 0, 0, 0, 1000, 900)};
                    KeyHoldsTest.H h = new KeyHoldsTest.H(regions);
                    FKeysTest.flip(h, flip);
                    h.td(0, 0, 1000);
                    h.tu(0, 1100, false);
                    String what = mode + " " + id + (flip ? "（平时第二功能）" : "（平时 F1）");
                    boolean second = flip && f != null && f.kind != FKeyFunctions.NONE;
                    if (!second) {
                        eq(h.r.keys, KeyHoldsTest.list("[00|3a]", "[00|]"), what + "：出 F1");
                        check(h.r.controls.isEmpty(), what + "：多媒体 / 系统键两个槽不动");
                    } else if (f.kind == FKeyFunctions.CONSUMER) {
                        eq(h.r.controls, KeyHoldsTest.list("c" + Integer.toHexString(f.code(mode)), "c0"), what + "：多媒体键按下、松开");
                        check(h.r.keys.isEmpty(), what + "：键盘报文不动");
                    } else if (f.kind == FKeyFunctions.SYSTEM) {
                        eq(h.r.controls, KeyHoldsTest.list("s" + Integer.toHexString(f.code(mode)), "s0"), what + "：系统键按下、松开");
                        check(h.r.keys.isEmpty(), what + "：键盘报文不动");
                    } else {
                        int code = f.code(mode) & 0xFFFF;
                        FKeyChoices.Item shown = FKeyChoices.describe(items, id, mode);
                        check(shown.code == code, what + "：行上写的组合（" + FKeyFaces.comboLabel(shown.code, mode) + "）就是发的（" + FKeyFaces.comboLabel(code, mode) + "）");
                        String pressed = String.format("[%02x|%02x]", code >> 8, code & 0xFF);
                        check(h.r.keys.contains(pressed), what + "：按下时主机收到 " + pressed + "（" + h.r.keys + "）");
                        String lastKeys = h.r.keys.isEmpty() ? "" : h.r.keys.get(h.r.keys.size() - 1);
                        eq(lastKeys, "[00|]", what + "：松开后什么都没按着");
                        check(h.r.controls.isEmpty(), what + "：多媒体 / 系统键两个槽不动");
                    }
                    n++;
                }
            }
        }
        System.err.println("  设置页每一项按下去：" + n + " 次");
    }

    // ═══════════ 随机：预设、逐个改、翻转、换模式 ═══════════

    /**
     * 随机改（挑预设、逐个配列表里的某一项或自定义组合键、翻"平时按出"、换模式）：预设那一行永远是 12 个现算的、写在它能选的几项里；
     * 12 行每一行都认得出（标在列表里某一项上，或者是 Mac 模式存着的那三个）；只写 kbx_ 开头的键，豆花布局的 kb_ 一个都没变。
     */
    static void randomSettings(List<String[]> actions) {
        Random rnd = new Random(20261102);
        Map<String, List<FKeyChoices.Item>> lists = new java.util.HashMap<>();
        lists.put(WIN, items(actions, WIN));
        lists.put(MAC, items(actions, MAC));
        String[] presets = {FKeyFunctions.PRESET_THINKPAD, FKeyFunctions.PRESET_APPLE, FKeyFunctions.PRESET_NONE};
        int steps = 0, rowsBad = 0, presetBad = 0;
        for (int round = 0; round < 30; round++) {
            BoardsTest.MapStore s = new BoardsTest.MapStore();
            Map<String, Object> before = BoardsTest.douhuaKeys();
            s.map.putAll(before);
            Boards b = new Boards(s, null);
            for (int i = 0; i < 80; i++) {
                steps++;
                String mode = rnd.nextBoolean() ? WIN : MAC;
                List<FKeyChoices.Item> items = lists.get(mode);
                switch (rnd.nextInt(5)) {
                    case 0: b.applyFkeyPreset(mode, presets[rnd.nextInt(3)]); break;
                    case 1: {
                        FKeyChoices.Item it = items.get(rnd.nextInt(items.size()));
                        if (it.type == FKeyChoices.FUNCTION || it.type == FKeyChoices.SHORTCUT) b.setFkey(mode, 1 + rnd.nextInt(12), it.id);
                        break;
                    }
                    case 2: b.setFkey(mode, 1 + rnd.nextInt(12), FKeyChoices.canonical(items, USAGES[rnd.nextInt(USAGES.length)] | rnd.nextInt(16) << 8)); break;
                    case 3: b.setFrowFlip(mode, rnd.nextBoolean()); break;
                    default: b.setMode(mode); break;
                }
                for (String m : new String[]{WIN, MAC}) {
                    String p = b.fkeyPreset(m);
                    if (!p.equals(FKeyFunctions.presetOf(b.fkeys(m))) || !Arrays.asList(FKeyChoices.presetChoices(p)).contains(p)) presetBad++;
                    List<FKeyChoices.Item> its = lists.get(m);
                    for (int k = 1; k <= 12; k++) {
                        String id = b.fkey(m, k);
                        int at = FKeyChoices.selected(its, id, m);
                        boolean ok = at >= 0 ? its.get(at).selectable() : MAC.equals(m) && FKeyChoices.WINDOWS_ONLY.contains(id);
                        if (!ok) rowsBad++;
                    }
                }
            }
            Map<String, Object> after = new TreeMap<>();
            for (Map.Entry<String, Object> e : s.map.entrySet()) if (e.getKey().startsWith("kb_")) after.put(e.getKey(), e.getValue());
            check(after.equals(before), "第 " + round + " 轮：豆花布局的 kb_ 一个都没变");
            boolean only = true;
            for (String k : s.written) only &= k.startsWith("kbx_");
            check(only, "第 " + round + " 轮：只写了 kbx_ 开头的键");
        }
        check(presetBad == 0, "预设那一行永远是 12 个现算的、写在能选的几项里（" + presetBad + " 次不是）");
        check(rowsBad == 0, "12 行每一行都认得出（" + rowsBad + " 次不是）");
        System.err.println("  设置随机序列：" + steps + " 步");
    }

    // ═══════════ 名字：九种语言、设置页的每个功能 ═══════════

    /**
     * FKeyFunctions 里每个有名字的功能（"无"、F13–F24 以外）：SettingsActivity 里有 R.string.fkf_<id>、九个 strings.xml 都有 fkf_<id>；
     * 每个分组都有标题（fkg_*，九个文件都有）；这一节的字（sec_kbd_fkeys、kbd_fkeys_*、fkf_*、fkg_*）九个文件一一对应、占位符一样、名字不重。
     */
    static void names() throws IOException {
        String settings = FixedLegendsTest.read("src/dev/douhua/SettingsActivity.java");
        List<String> want = new ArrayList<>();
        for (FKeyFunctions.Function f : FKeyFunctions.all()) {
            if (f.id.equals("none") || f.id.matches("f\\d+")) continue;
            want.add("fkf_" + f.id);
            check(settings.contains("case \"" + f.id + "\": return getString(R.string.fkf_" + f.id + ");"), "设置页给 " + f.id + " 取名字");
        }
        String[] groups = {"sound", "media", "screen", "desktop", "open", "input", "system", "fkeys", "combo", "shortcuts"};
        for (String g : groups) {
            want.add("fkg_" + g);
            check(settings.contains("R.string.fkg_" + g), "设置页用了分组标题 fkg_" + g);
        }
        Pattern p = Pattern.compile("<string name=\"((?:sec_kbd_fkeys|kbd_fkeys_\\w+|fkf_\\w+|fkg_\\w+))\"[^>]*>(.*?)</string>");
        Map<String, String> base = null;
        for (String d : DIRS) {
            String x = FixedLegendsTest.read("res/" + d + "/strings.xml");
            Map<String, String> got = new java.util.LinkedHashMap<>();
            Matcher m = p.matcher(x);
            int dup = 0;
            while (m.find()) if (got.put(m.group(1), placeholders(m.group(2))) != null) dup++;
            check(dup == 0, d + "：名字不重");
            for (String w : want) check(got.containsKey(w), d + " 有 " + w);
            if (base == null) base = got;
            else {
                check(got.keySet().equals(base.keySet()), d + "：这一节的条目和英文的一一对应");
                check(got.equals(base), d + "：占位符和英文的一样");
            }
        }
        check(base != null && base.containsKey("kbd_fkeys_mode") && base.get("kbd_fkeys_mode").equals("%1$s"), "kbd_fkeys_mode 带一个 %1$s（模式名）");
    }

    /** 一条字里的占位符（%1$s 这类），按出现的顺序拼起来。 */
    private static String placeholders(String s) {
        Matcher m = Pattern.compile("%\\d+\\$[sd]").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) b.append(m.group());
        return b.toString();
    }

    static void eq(Object got, Object want, String what) {
        check(want.equals(got), what + "（得到 " + got + "，应为 " + want + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
