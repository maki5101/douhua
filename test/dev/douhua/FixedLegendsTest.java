package dev.douhua;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FixedLegends 的离线测试：直接读仓库里的 res/raw/kbd_*.txt（App 读的就是这几份），核对格式能解析、
 * 抽查值（期望值是方案 [D] §5 原型实际跑出来的、1A 的 check_tables.py 也锁着）、不变量，以及 Mac 模式按苹果 ISO 键盘发码时查到的字。
 * 从仓库根目录跑（tools/test.sh）。
 */
public final class FixedLegendsTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws IOException {
        FixedLegends.TableFile win = FixedLegends.parseTables(read("res/raw/kbd_win.txt"));
        FixedLegends.TableFile mac = FixedLegends.parseTables(read("res/raw/kbd_mac.txt"));
        List<FixedLegends.Layout> catalog = FixedLegends.parseCatalog(read("res/raw/kbd_catalog.txt"));
        escapes();
        printable();
        header(win, mac);
        catalog(catalog);
        spotValues(win, mac);
        invariants(win, mac, catalog);
        macIso(mac);
        badInput();
        System.err.println("实体键盘键帽数据：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    // ── 反转义 ──
    static void escapes() {
        eq(FixedLegends.unescape("\\\\"), "\\", "\\\\ 是反斜杠");
        eq(FixedLegends.unescape("\\u00E9x"), "éx", "\\u00E9 是 é");
        eq(FixedLegends.unescape("\\uD83D\\uDE00"), "😀", "代理对拼回去");
        boolean threw = false;
        try {
            FixedLegends.unescape("\\q");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "认不出的转义抛异常（宁可读不出来也不画错字）");
    }

    static void printable() {
        check(FixedLegends.printable("") == null, "苹果标（私用区）不画");
        check(FixedLegends.printable("́") == null, "组合附加符号单独不画");
        check(FixedLegends.printable(" ") == null, "不换行空格不画");
        eq(FixedLegends.printable("´"), "´", "带间距的附加符号（死键）画得出来");
        eq(FixedLegends.printable("€"), "€", "€ 画得出来");
    }

    // ── 文件头里的数据源版本和 sources.json 一致 ──
    static void header(FixedLegends.TableFile win, FixedLegends.TableFile mac) throws IOException {
        String sources = read("tools/kbd/sources.json");
        Matcher m = Pattern.compile("\"cldr\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\"([^\"]+)\"", Pattern.DOTALL).matcher(sources);
        check(m.find(), "sources.json 里找得到 CLDR 的版本");
        String version = m.find(0) ? m.group(1) : "?";
        for (FixedLegends.TableFile f : new FixedLegends.TableFile[]{win, mac}) {
            String[] src = f.first("source");
            check(src != null && src.length >= 2 && src[0].equals("cldr") && src[1].equals(version),
                    "文件头的数据源是 CLDR " + version + "：" + (src == null ? "没有" : String.join(" ", src)));
        }
        eq(win.first("platform")[0], "win", "kbd_win 的平台");
        eq(mac.first("platform")[0], "mac", "kbd_mac 的平台");
    }

    static void catalog(List<FixedLegends.Layout> catalog) {
        FixedLegends.Layout us = FixedLegends.find(catalog, "us"), de = FixedLegends.find(catalog, "de");
        check(us != null && de != null, "清单里有美式、德语");
        if (us == null || de == null) return;
        eq(us.shape, "ansi", "美式默认 ANSI");
        check(us.allows("ansi") && us.allows("iso"), "美式能选 ANSI、ISO（\"美国 ISO\"）");
        eq(de.shape, "iso", "德语默认 ISO");
        check(de.allows("iso") && !de.allows("ansi"), "德语只能 ISO");
        eq(us.kpdl, ".", "美式小键盘小数点");
        eq(de.kpdl, ",", "德语小键盘小数点是逗号");
        eq(us.nameMac, "U.S.", "美式在 Mac 里叫 U.S.（CLDR 的旧名）");
        eq(de.nameWin, "German", "德语在 Windows 里叫 German");
        for (FixedLegends.Layout l : catalog) check(FixedLegends.LOCALE_TAGS.containsKey(l.id), l.id + " 有语言标签（起名字用）");
    }

    // ── 抽查值（方案 §6.4） ──
    static void spotValues(FixedLegends.TableFile win, FixedLegends.TableFile mac) {
        FixedLegends.Table winDe = win.sections.get("de"), winUs = win.sections.get("us");
        FixedLegends.Table macDe = mac.sections.get("de"), macUs = mac.sections.get("us");
        check(winDe != null && winUs != null && macDe != null && macUs != null, "四段都在");
        if (winDe == null || winUs == null || macDe == null || macUs == null) return;
        cell(macDe, 0x0F, FixedLegends.THIRD, "@", "mac:de ⌥L = @");
        cell(macDe, 0x14, FixedLegends.THIRD, "«", "mac:de ⌥Q = «");
        cell(winDe, 0x14, FixedLegends.THIRD, "@", "win:de AltGr+Q = @");
        cell(winDe, 0x64, FixedLegends.THIRD, "|", "win:de AltGr + Z 左边那个键 = |");
        cell(winDe, 0x08, FixedLegends.THIRD, "€", "win:de AltGr+E = €");
        cell(winDe, 0x24, FixedLegends.THIRD, "{", "win:de AltGr+7 = {");
        cell(winDe, 0x1C, FixedLegends.NORMAL, "z", "win:de 是 QWERTZ（Y 的位置出 z）");
        cell(winUs, 0x64, FixedLegends.NORMAL, "\\", "win:us 的 0x64 出 \\");
        cell(winUs, 0x64, FixedLegends.SHIFT, "|", "win:us 的 0x64 Shift 出 |");
        check(!winUs.hasThird(), "win:us 没有第三层（右 Alt 写 Alt）");
        check(winDe.hasThird(), "win:de 有第三层（右 Alt 写 AltGr）");
        check(macUs.hasThird(), "mac:us 有 Option 层");
        FixedLegends.Cell dead = winDe.get(0x35, FixedLegends.NORMAL);
        check(dead != null && dead.dead && dead.text.equals("^"), "win:de 1 左边那个键是死键 ^");
        FixedLegends.Cell acute = winDe.get(0x2E, FixedLegends.NORMAL);
        check(acute != null && acute.dead && acute.text.equals("´"), "win:de ´ 是死键");
    }

    private static void cell(FixedLegends.Table t, int usage, int layer, String want, String what) {
        FixedLegends.Cell c = t.get(usage, layer);
        eq(c == null ? null : c.text, want, what);
    }

    /** 带间距的附加符号（死键只该出现在这些上）。 */
    private static final String DIACRITICS = "´`^¨~˜ˆ°'\"¸˛˘˝ˇ˙¯";

    // ── 不变量 ──
    static void invariants(FixedLegends.TableFile win, FixedLegends.TableFile mac, List<FixedLegends.Layout> catalog) {
        for (FixedLegends.TableFile f : new FixedLegends.TableFile[]{win, mac}) {
            String plat = f.first("platform")[0];
            for (FixedLegends.Layout l : catalog) {
                FixedLegends.Table t = f.sections.get(l.id);
                check(t != null, plat + ":" + l.id + " 有这一段");
                if (t == null) continue;
                int letters = 0;
                for (int u = 0x04; u <= 0x1D; u++) {
                    FixedLegends.Cell c = t.get(u, FixedLegends.NORMAL);
                    if (c != null && c.text.length() == 1 && Character.isLetter(c.text.charAt(0))) letters++;
                }
                check(letters == 26, plat + ":" + l.id + " 26 个字母键普通层都是字母（" + letters + "）");
                check(t.has(0x64), plat + ":" + l.id + " 0x64 有字（ISO 形状的那个键）");
                for (int u : t.usages()) {
                    for (int layer = 0; layer < 4; layer++) {
                        FixedLegends.Cell c = t.get(u, layer);
                        if (c == null) continue;
                        boolean ctrl = false;
                        for (char ch : c.text.toCharArray()) ctrl |= Character.getType(ch) == Character.CONTROL;
                        if (ctrl) check(false, plat + ":" + l.id + " " + Integer.toHexString(u) + " 有控制字符");
                        if (c.dead && DIACRITICS.indexOf(c.text) < 0) {
                            check(false, plat + ":" + l.id + " " + Integer.toHexString(u) + " 死键不是附加符号：" + c.text);
                        }
                    }
                }
            }
        }
        checks++;
    }

    // ── Mac：<id>.iso 是 <id> 把 0x35 / 0x64 对调（锁住对调），Mac 模式发苹果 ISO 的码、查不对调那段 ──
    static void macIso(FixedLegends.TableFile mac) {
        for (String id : new String[]{"us", "de"}) {
            FixedLegends.Table plain = mac.sections.get(id), iso = mac.sections.get(id + ".iso");
            check(plain != null && iso != null, "mac:" + id + " 两段都在");
            if (plain == null || iso == null) continue;
            for (int layer = 0; layer < 4; layer++) {
                check(same(plain.get(0x64, layer), iso.get(0x35, layer)) && same(plain.get(0x35, layer), iso.get(0x64, layer)),
                        "mac:" + id + " 第 " + layer + " 层：.iso 段把 0x35 / 0x64 对调");
            }
        }
        FixedLegends.Table us = mac.sections.get("us"), de = mac.sections.get("de");
        // Mac 模式 ISO：1 左边那个键发 0x64、Z 左边那个键发 0x35（FixedLayout），按发的码查不对调那段，就是苹果键盘上印的字
        FixedLayout iso = FixedLayout.build(FixedLayout.ISO, FixedLayout.TKL, FixedLayout.MAC, false, ".");
        int topLeft = keyAt(iso, 1, 0.5f), zLeft = keyAt(iso, 4, 1.75f);
        check(topLeft == 0x64 && zLeft == 0x35, "Mac 模式 ISO：1 左边发 0x64、Z 左边发 0x35（照真苹果 ISO 键盘）");
        cell(us, topLeft, FixedLegends.NORMAL, "§", "mac:us + ISO：左上角是 §");
        cell(us, zLeft, FixedLegends.NORMAL, "`", "mac:us + ISO：Z 左边是 `");
        FixedLegends.Cell caret = de.get(topLeft, FixedLegends.NORMAL);
        check(caret != null && caret.dead && caret.text.equals("^"), "mac:de + ISO：左上角是死键 ^");
        cell(de, zLeft, FixedLegends.NORMAL, "<", "mac:de + ISO：Z 左边是 <");
        FixedLayout winIso = FixedLayout.build(FixedLayout.ISO, FixedLayout.TKL, FixedLayout.WIN, true, ",");
        check(keyAt(winIso, 1, 0.5f) == 0x35 && keyAt(winIso, 4, 1.75f) == 0x64, "Windows 模式 ISO：1 左边 0x35、Z 左边 0x64（PC 惯例）");
        FixedLayout ansiMac = FixedLayout.build(FixedLayout.ANSI, FixedLayout.TKL, FixedLayout.MAC, false, ".");
        check(keyAt(ansiMac, 1, 0.5f) == 0x35, "Mac 模式 ANSI：1 左边照旧 0x35");
        cell(us, 0x35, FixedLegends.NORMAL, "`", "mac:us + ANSI：左上角是 `");
    }

    /** 第 row 行横坐标 u 上的键的 id，没有返回 -1。 */
    static int keyAt(FixedLayout l, int row, float u) {
        for (FixedLayout.Key k : l.keys) {
            float[] s = k.span(row);
            if (s != null && u >= s[0] && u < s[1]) return k.id;
        }
        return -1;
    }

    private static boolean same(FixedLegends.Cell a, FixedLegends.Cell b) {
        if (a == null || b == null) return a == b;
        return a.text.equals(b.text) && a.dead == b.dead;
    }

    static void badInput() {
        boolean threw = false;
        try {
            FixedLegends.parseTables("04\ta\tA\n");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "不在任何段里的键：抛异常");
        threw = false;
        try {
            FixedLegends.parseTables("@x = y\n");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "别名指向不存在的段：抛异常");
        FixedLegends.TableFile f = FixedLegends.parseTables("#!platform win\n@a\n04\ta\tA\n@b = a\n");
        check(f.sections.get("b") == f.sections.get("a"), "别名展开成同一张表");
        check(f.sections.get("a").get(0x04, FixedLegends.THIRD) == null, "没写的格子是 null");
    }

    // ── 工具 ──
    static void eq(String got, String want, String what) {
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
