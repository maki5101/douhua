package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体键盘的键帽字（res/raw/kbd_*.txt）和布局清单（res/raw/kbd_catalog.txt）的读法，纯 Java（离线测，FixedLegendsTest）。
 * 两份文件都是 tools/kbd/gen_layouts.py 生成的（勿手改），格式照 tools/kbd/kbdtable.py 的 read_table_file / read_catalog：
 *   #!platform mac            机读的元数据（平台、数据源版本、原始文件的 sha256）
 *   @de                       一个布局一段
 *   0f	l	L	@	ﬂ          HID 用法码（十六进制）、普通、Shift、第三层、第三层+Shift，tab 分隔；空格子 = 没有字
 *   @de2 = de                 完全一样的表写成别名
 * 一格里 `\*` 开头 = 死键，`\\` = 反斜杠，反斜杠 + u + 四位十六进制 = 看不见的字（BMP 以外是代理对，同 Java）。
 * 第三层：Mac 是 Option，Windows 是 AltGr（右 Alt）。
 *
 * 第 1B 批（2026-11-01）只读 Windows（kbd_win）和 Mac（kbd_mac）两份：用户定的是每块实体键盘自己有 Windows / Mac 两种模式，
 * 连 Linux、安卓也用 Windows 模式（kbd_linux / kbd_android 留着，这批不读）。
 * Mac 模式读不对调的那一段（`<id>`，不读 `<id>.iso`）：豆花在 Mac 模式下照真苹果 ISO 键盘发码（1 左边那个键发 0x64、Z 左边那个键发 0x35，
 * 见 FixedLayout），不对调那段按用法码查出来就是苹果键盘那个位置上印的字。
 */
final class FixedLegends {
    private FixedLegends() {
    }

    static final int NORMAL = 0, SHIFT = 1, THIRD = 2, THIRD_SHIFT = 3;

    /** 一格：字（死键是带间距的附加符号，比如 ´）和是不是死键。 */
    static final class Cell {
        final String text;
        final boolean dead;

        Cell(String text, boolean dead) {
            this.text = text;
            this.dead = dead;
        }

        @Override
        public String toString() {
            return (dead ? "死键 " : "") + text;
        }
    }

    /** 一个布局在一家主机上的字：用法码 × 四层。 */
    static final class Table {
        final String id;
        private final Cell[][] cells = new Cell[256][];

        Table(String id) {
            this.id = id;
        }

        /** 这个用法码在这一层（NORMAL / SHIFT / THIRD / THIRD_SHIFT）出的字，没有字返回 null。 */
        Cell get(int usage, int layer) {
            if (usage < 0 || usage > 255 || layer < 0 || layer > 3) return null;
            Cell[] c = cells[usage];
            return c == null ? null : c[layer];
        }

        /** 这个用法码有没有一行（四层里至少有一个字）。 */
        boolean has(int usage) {
            return usage >= 0 && usage <= 255 && cells[usage] != null;
        }

        /** 有没有第三层（AltGr / Option）的字：Windows 的美式没有（右 Alt 只是 Alt），键帽就写 Alt 不写 AltGr。 */
        boolean hasThird() {
            for (Cell[] c : cells) if (c != null && (c[THIRD] != null || c[THIRD_SHIFT] != null)) return true;
            return false;
        }

        /** 有字的用法码，从小到大。 */
        List<Integer> usages() {
            List<Integer> out = new ArrayList<>();
            for (int u = 0; u < 256; u++) if (cells[u] != null) out.add(u);
            return out;
        }
    }

    /** 一份字表文件：元数据（#! 行，键 → 每行后面的几段）和各段（别名已经展开成同一张表）。 */
    static final class TableFile {
        final Map<String, List<String[]>> meta;
        final Map<String, Table> sections;

        TableFile(Map<String, List<String[]>> meta, Map<String, Table> sections) {
            this.meta = meta;
            this.sections = sections;
        }

        /** 元数据 key 的第一行（比如 "platform" → {"mac"}）；没有返回 null。 */
        String[] first(String key) {
            List<String[]> v = meta.get(key);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    /** 布局清单的一行。shapes = 能选的形状（FixedLayout.ANSI / ISO），shape = 默认的；kpdl = 小键盘小数点出的字。 */
    static final class Layout {
        final String id, shape, kpdl, nameMac, nameWin, nameLinux, nameAndroid;
        final List<String> shapes;

        Layout(String id, String shape, List<String> shapes, String kpdl, String nameMac, String nameWin, String nameLinux,
               String nameAndroid) {
            this.id = id;
            this.shape = shape;
            this.shapes = Collections.unmodifiableList(new ArrayList<>(shapes));
            this.kpdl = kpdl;
            this.nameMac = nameMac;
            this.nameWin = nameWin;
            this.nameLinux = nameLinux;
            this.nameAndroid = nameAndroid;
        }

        /** 这个布局能不能选这种形状。 */
        boolean allows(String s) {
            return shapes.contains(s);
        }
    }

    /**
     * 每个布局按哪种语言 + 地区起名字（设置页、⋯ 菜单写的名字，界面语言下的叫法由 strings.xml 的 kbd_lang_* 给，没有的用 Locale 自己翻）。
     * 第 2 批布局多了以后挪进 tools/kbd/catalog.json（现在清单里没有这一栏）。
     */
    static final Map<String, String> LOCALE_TAGS;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("us", "en-US");
        m.put("de", "de-DE");
        LOCALE_TAGS = Collections.unmodifiableMap(m);
    }

    // ═══════════ 读文件 ═══════════

    /** 读一份字表文件（kbd_win.txt / kbd_mac.txt 的全文）。格式不对抛 IllegalArgumentException（照 kbdtable.py，宁可读不出来也不画错字）。 */
    static TableFile parseTables(String text) {
        Map<String, List<String[]>> meta = new LinkedHashMap<>();
        Map<String, Table> sections = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Table cur = null;
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.startsWith("#!")) {
                String[] parts = line.substring(2).trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    meta.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(Arrays.copyOfRange(parts, 1, parts.length));
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("@")) {
                String body = line.substring(1);
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    String a = body.substring(0, eq).trim(), b = body.substring(eq + 1).trim();
                    if (!name(a) || !name(b)) throw new IllegalArgumentException("第 " + (n + 1) + " 行段名不对：" + line);
                    aliases.put(a, b);
                    cur = null;
                } else {
                    String id = body.trim();
                    if (!name(id)) throw new IllegalArgumentException("第 " + (n + 1) + " 行段名不对：" + line);
                    cur = sections.get(id);
                    if (cur == null) {
                        cur = new Table(id);
                        sections.put(id, cur);
                    }
                }
                continue;
            }
            if (cur == null) throw new IllegalArgumentException("第 " + (n + 1) + " 行不在任何段里");
            String[] cols = line.split("\t", -1);
            int usage;
            try {
                usage = Integer.parseInt(cols[0], 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("第 " + (n + 1) + " 行用法码不对：" + cols[0]);
            }
            if (usage < 0 || usage > 255) throw new IllegalArgumentException("第 " + (n + 1) + " 行用法码超出 0–ff");
            Cell[] cells = new Cell[4];
            for (int i = 0; i < 4 && i + 1 < cols.length; i++) cells[i] = parseCell(cols[i + 1], n + 1);
            cur.cells[usage] = cells;
        }
        for (Map.Entry<String, String> a : aliases.entrySet()) {
            Table t = sections.get(a.getValue());
            if (t == null) throw new IllegalArgumentException("别名 " + a.getKey() + " 指向不存在的 " + a.getValue());
            sections.put(a.getKey(), t);
        }
        return new TableFile(meta, sections);
    }

    /** 读布局清单（kbd_catalog.txt 的全文）：id、shape、shapes、kpdl、name_mac、name_win、name_linux、name_android。 */
    static List<Layout> parseCatalog(String text) {
        List<Layout> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            if (c.length < 8) throw new IllegalArgumentException("清单这一行不够 8 栏：" + line);
            for (int i = 0; i < c.length; i++) c[i] = unescape(c[i]);
            out.add(new Layout(c[0], c[1], Arrays.asList(c[2].split(",")), c[3], c[4], c[5], c[6], c[7]));
        }
        return out;
    }

    /** 清单里 id 这一行；没有返回 null。 */
    static Layout find(List<Layout> catalog, String id) {
        if (catalog == null || id == null) return null;
        for (Layout l : catalog) if (l.id.equals(id)) return l;
        return null;
    }

    private static boolean name(String s) {
        return !s.isEmpty() && s.matches("[\\w.-]+");
    }

    private static Cell parseCell(String s, int line) {
        if (s.isEmpty()) return null;
        boolean dead = s.startsWith("\\*");
        String text = unescape(dead ? s.substring(2) : s);
        if (text.isEmpty()) throw new IllegalArgumentException("第 " + line + " 行有空的死键格");
        return new Cell(text, dead);
    }

    /** 反转义：`\\` → 反斜杠，反斜杠 + u + 四位十六进制 → 那个字（代理对两个挨着写，拼起来就是原来的字）；别的反斜杠认不出就抛异常。 */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
            } else if (s.startsWith("\\\\", i)) {
                out.append('\\');
                i += 2;
            } else if (i + 6 <= s.length() && s.charAt(i + 1) == 'u' && hex4(s, i + 2)) {
                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                throw new IllegalArgumentException("认不出的转义：" + s.substring(i, Math.min(s.length(), i + 6)));
            }
        }
        return out.toString();
    }

    private static boolean hex4(String s, int at) {
        for (int i = at; i < at + 4; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /**
     * 键帽上画得出来吗：私用区（苹果标 U+F8FF，别的字体里没有）、控制字符、格式字符、组合附加符号（单独画是一个虚框）、空白都不画（返回 null），
     * 别的照原样。死键的字是带间距的附加符号（´ ` ^ ¨ ~），画得出来。
     */
    static String printable(String text) {
        if (text == null || text.isEmpty()) return null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int type = Character.getType(cp);
            if (type == Character.PRIVATE_USE || type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return null;
            i += Character.charCount(cp);
        }
        return text;
    }
}
