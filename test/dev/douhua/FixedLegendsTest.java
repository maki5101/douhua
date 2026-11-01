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
 * 抽查值（期望值是方案 [D] §5 原型实际跑出来的、1A 的 check_tables.py 也锁着；第 2 批另加的是凭常识写的、和数据核对一致）、
 * 每个布局每种有数据的模式都过的不变量（个别布局不成立的写明例外和理由），以及 Mac 模式按苹果 ISO 键盘发码时查到的字。
 * 第 2 批（2026-11-01）：清单 34 个布局，按 #!columns 认栏；没有 Mac 数据的（拉美）字表里没有那一段、拼出来是 null（App 退回豆花布局）。
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
        names(catalog);
        spotValues(win, mac);
        batch2Spots(win, mac);
        invariants(win, mac, catalog);
        missingModes(win, mac, catalog);
        macIso(mac, catalog);
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
        eq(us.nameMac, "U.S.", "美式在 Mac 里叫 U.S.");
        eq(us.nameMacCldr, "U.S.", "美式在 CLDR 里叫 U.S.");
        eq(de.nameWin, "German", "德语在 Windows 里叫 German");
        // ── 第 2 批 ──
        check(catalog.size() == 34, "清单 34 个布局（" + catalog.size() + "）");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (FixedLegends.Layout l : catalog) {
            check(ids.add(l.id), l.id + " 不重复");
            check(l.locale.matches("[a-z]{2,3}(-[A-Z]{2}|-\\d{3})?"), l.id + " 的语言标签像样：" + l.locale);
            check(l.hasMode(FixedLayout.WIN) || l.hasMode(FixedLayout.MAC), l.id + " 至少有一种模式的数据");
            check(l.hasMode(FixedLayout.WIN) == !MAC_ONLY.contains(l.id), l.id + (MAC_ONLY.contains(l.id) ? " 只有 Mac 有（Windows 没有这一套）" : " 有 Windows 的数据"));
            check(l.shapes.contains(l.shape), l.id + " 的默认形状在能选的里面");
            check(l.kpdl.equals(".") || l.kpdl.equals(","), l.id + " 的小键盘小数点是 . 或 ,");
            check(l.hasMode(FixedLayout.MAC) == !l.nameMac.isEmpty(), l.id + " 有 Mac 数据才有 Mac 的名字");
            check(l.hasMode(FixedLayout.WIN) == !l.nameWin.isEmpty(), l.id + " 有 Windows 数据才有 Windows 的名字");
            // 小键盘小数点：两种模式各自全锁住（Windows 照微软布局表，Mac 照 xkb）
            String[] dl = KPDL.get(l.id);
            check(dl != null, l.id + " 的小键盘小数点在测试的表里");
            if (dl == null) continue;
            eq(l.kpdl, dl[1], l.id + " 的小键盘小数点（xkb，Mac 模式用）");
            eq(l.kpdlWin, dl[0], l.id + " 的 Windows 小键盘小数点（微软布局表）");
            if (l.hasMode(FixedLayout.WIN)) eq(l.kpdl(FixedLayout.WIN), dl[0], l.id + " Windows 模式的小数点键帽照 kpdl_win");
            eq(l.kpdl(FixedLayout.MAC), dl[1], l.id + " Mac 模式的小数点键帽照 kpdl");
        }
        check(KPDL.size() == catalog.size(), "测试的小数点表和清单一样多（" + KPDL.size() + "）");
        // 只有 Mac 有的那一套：Windows 模式没数据（设置里灰掉、存着的组合退回豆花布局），不拿普通的意大利 / 瑞典顶上
        for (String id : MAC_ONLY) {
            FixedLegends.Layout l = FixedLegends.find(catalog, id);
            check(l != null && !l.hasMode(FixedLayout.WIN) && l.hasMode(FixedLayout.MAC) && l.nameWin.isEmpty() && l.kpdlWin.isEmpty(),
                    id + " 只有 Mac：没有 Windows 的数据、名字、小数点");
        }
        FixedLegends.Layout latam = FixedLegends.find(catalog, "latam"), br = FixedLegends.find(catalog, "br"),
                pinyin = FixedLegends.find(catalog, "cn-pinyin"), se = FixedLegends.find(catalog, "se"),
                legacy = FixedLegends.find(catalog, "se-legacy"), pl = FixedLegends.find(catalog, "pl");
        check(latam != null && br != null && pinyin != null && se != null && legacy != null && pl != null, "清单里有拉美、巴西、拼音、瑞典两套、波兰");
        if (latam == null || br == null || pinyin == null || se == null || legacy == null || pl == null) return;
        check(latam.hasMode(FixedLayout.WIN) && !latam.hasMode(FixedLayout.MAC), "拉美只有 Windows 的数据（CLDR 43 里没有 macOS 的）");
        eq(latam.nameMac, "", "拉美没有 Mac 的名字");
        eq(br.shape, "abnt2", "巴西默认 ABNT2");
        check(br.shapes.size() == 1 && br.allows("abnt2"), "巴西只能 ABNT2");
        eq(br.kpdl, ",", "巴西小键盘小数点（0x63）是逗号");
        eq(br.kpco, ".", "巴西 ABNT2 小键盘多的那个键（0x85）是句点");
        for (FixedLegends.Layout l : catalog) if (!l.allows("abnt2")) eq(l.kpco, "", l.id + " 不是 ABNT2，kpco 空着");
        eq(pinyin.capsMac, "中/英", "拼音 Mac 模式的 Caps 写 中/英");
        check(pinyin.shapes.size() == 1 && pinyin.allows("ansi"), "拼音只有 ANSI");
        eq(pinyin.nameWin, "Chinese (Simplified) - US", "拼音在 Windows 里叫 Chinese (Simplified) - US");
        eq(se.nameMacCldr, "Swedish - Pro", "瑞典：CLDR 里叫 Swedish - Pro");
        eq(se.nameMac, "Swedish", "瑞典：macOS 12 起叫 Swedish（mac_names.json，社区文档核过）");
        eq(legacy.nameMacCldr, "Swedish", "瑞典旧版：CLDR 里叫 Swedish");
        eq(legacy.nameMac, "Swedish – Legacy", "瑞典旧版：现在叫 Swedish – Legacy");
        check(pl.allows("ansi") && pl.allows("iso") && pl.shape.equals("ansi"), "波兰（Programmers）默认 ANSI、能选 ISO");
        for (FixedLegends.Layout l : catalog) if (!l.capsMac.isEmpty()) check(l == pinyin, l.id + " 不该改 Caps 的字");
    }

    /** 只有 Mac 有的那一套（Windows 没有：Mac 的意大利 QZERTY、瑞典旧版）。 */
    static final java.util.Set<String> MAC_ONLY = new java.util.HashSet<>(java.util.Arrays.asList("it-qzerty", "se-legacy"));

    /**
     * 小键盘小数点（0x63）：布局 → {Windows（kpdl_win，照微软布局表：kbdlayout.info 按微软键盘 DLL 导出的 KLC 的 DECIMAL 一行，2026-11-01 逐个核过；
     * 没有 Windows 数据的空着）, xkb（kpdl，Mac 模式用）}。荷兰、冰岛、捷克、斯洛伐克两边不一样（xkb 那四个没引 kpdl(comma)）。
     */
    static final java.util.Map<String, String[]> KPDL = new java.util.HashMap<>();

    static {
        String[][] rows = {{"us", ".", "."}, {"us-intl", ".", "."}, {"gb", ".", "."}, {"ie", ".", "."}, {"nl", ",", "."}, {"ro", ",", ","},
                {"fr", ".", "."}, {"be", ".", "."}, {"ca-fr", ".", "."}, {"de", ",", ","}, {"at", ",", ","}, {"ch-de", ".", "."}, {"ch-fr", ".", "."},
                {"se", ",", ","}, {"se-legacy", "", ","}, {"fi", ",", ","}, {"dk", ",", ","}, {"no", ",", ","}, {"is", ",", "."}, {"es", ".", "."},
                {"latam", ".", "."}, {"pt", ".", "."}, {"br", ",", ","}, {"it", ".", "."}, {"it-qzerty", "", "."}, {"hr", ",", ","}, {"si", ",", ","},
                {"cz", ",", "."}, {"sk", ",", "."}, {"hu", ",", ","}, {"pl", ",", ","}, {"tr-q", ",", ","}, {"tr-f", ",", ","}, {"cn-pinyin", ".", "."}};
        for (String[] r : rows) KPDL.put(r[0], new String[]{r[1], r[2]});
    }

    /** 起名字：区分词小写的（要翻译的）九个 strings.xml 都有 kbd_var_<它>；别的照原样写，得是大写字母（QZERTY、Q、F）。 */
    static void names(List<FixedLegends.Layout> catalog) throws IOException {
        String[] dirs = {"values", "values-zh", "values-ja", "values-ko", "values-fr", "values-de", "values-it", "values-es", "values-ar"};
        java.util.Set<String> words = new java.util.TreeSet<>();
        for (FixedLegends.Layout l : catalog) {
            if (l.variantWord()) words.add(l.variant);
            else if (!l.variant.isEmpty()) check(l.variant.matches("[A-Z0-9]+"), l.id + " 的区分词不翻译就得是大写字母：" + l.variant);
        }
        check(words.equals(new java.util.TreeSet<>(java.util.Arrays.asList("intl", "legacy", "pinyin"))),
                "要翻译的区分词就这三个（加了要在 FixedData.variantName 里加一条）：" + words);
        for (String d : dirs) {
            String x = read("res/" + d + "/strings.xml");
            for (String w : words) check(x.contains("<string name=\"kbd_var_" + w + "\">"), d + " 有 kbd_var_" + w);
        }
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

    // ── 第 2 批的抽查值（方案 §6.4 第 2 批那几条 + 调研 §5 原型跑出来的；后面几条是凭常识写的期望值，和数据核对一致；check_tables.py 锁着同一批） ──
    static void batch2Spots(FixedLegends.TableFile win, FixedLegends.TableFile mac) {
        int n = FixedLegends.NORMAL, sh = FixedLegends.SHIFT, th = FixedLegends.THIRD;
        Object[][] spots = {
                // 平台, 布局, 用法码, 层, 字, 说明
                {mac, "fr", 0x64, n, "@", "mac:fr 左上角（键码 10，Mac 模式 ISO 发 0x64）= @"},
                {mac, "fr", 0x64, sh, "#", "mac:fr 左上角 Shift = #"},
                {win, "fr", 0x27, th, "@", "win:fr AltGr+0 = @"},
                {mac, "fr", 0x25, n, "!", "mac:fr 8 键普通层 = !"},
                {win, "fr", 0x25, n, "_", "win:fr 8 键普通层 = _"},
                {mac, "se", 0x1F, th, "@", "mac:se（原 Swedish - Pro）⌥2 = @"},
                {mac, "se-legacy", 0x31, th, "@", "mac:se-legacy ⌥' = @"},
                {mac, "gb", 0x1F, sh, "@", "mac:gb Shift+2 = @"},
                {win, "gb", 0x34, sh, "@", "win:gb Shift+' = @"},
                {win, "gb", 0x1F, sh, "\"", "win:gb Shift+2 = \""},
                {mac, "gb", 0x64, n, "§", "mac:gb 左上角（0x64）= §"},
                {win, "gb", 0x35, n, "`", "win:gb 左上角 = `"},
                {win, "ch-de", 0x1F, th, "@", "win:ch-de AltGr+2 = @"},
                {mac, "ch-de", 0x0A, th, "@", "mac:ch-de ⌥G = @"},
                {win, "es", 0x1F, th, "@", "win:es AltGr+2 = @"},
                {mac, "es", 0x1F, th, "@", "mac:es（Spanish – ISO）⌥2 = @"},
                {win, "latam", 0x14, th, "@", "win:latam AltGr+Q = @"},
                {win, "it", 0x33, th, "@", "win:it AltGr+ò = @"},
                {mac, "it", 0x33, th, "@", "mac:it（Italian - Pro）⌥ò = @"},
                {mac, "it-qzerty", 0x1A, n, "z", "mac:it-qzerty 是 QZERTY"},
                {win, "pt", 0x1F, th, "@", "win:pt AltGr+2 = @"},
                {win, "br", 0x1F, sh, "@", "win:br Shift+2 = @"},
                {win, "br", 0x87, n, "/", "win:br 0x87 = /"},
                {win, "br", 0x87, sh, "?", "win:br 0x87 Shift = ?"},
                {mac, "br", 0x87, n, "/", "mac:br 0x87 = /"},
                {win, "tr-q", 0x0C, n, "ı", "win:tr-q I 的位置出 ı"},
                {win, "tr-q", 0x34, n, "i", "win:tr-q ' 的位置出 i"},
                {win, "tr-f", 0x14, n, "f", "win:tr-f Q 的位置出 f"},
                {win, "pl", 0x04, th, "ą", "win:pl AltGr+A = ą"},
                {win, "hu", 0x19, th, "@", "win:hu AltGr+V = @"},
                {win, "cz", 0x19, th, "@", "win:cz AltGr+V = @"},
                {win, "hr", 0x14, th, "\\", "win:hr AltGr+Q = \\"},
                {win, "nl", 0x35, n, "@", "win:nl 左上角 = @"},
                {win, "ro", 0x33, n, "ș", "win:ro ; 的位置 = ș（下加逗号）"},
                {mac, "ca-fr", 0x64, n, "/", "mac:ca-fr 左上角（0x64）= /"},
                {win, "ca-fr", 0x35, n, "/", "win:ca-fr 左上角 = /"},
                {mac, "at", 0x0F, th, "@", "mac:at ⌥L = @"},
        };
        for (Object[] sp : spots) {
            FixedLegends.Table t = ((FixedLegends.TableFile) sp[0]).sections.get((String) sp[1]);
            check(t != null, sp[1] + " 有这一段");
            if (t != null) cell(t, (Integer) sp[2], (Integer) sp[3], (String) sp[4], (String) sp[5]);
        }
        FixedLegends.Cell apos = win.sections.get("us-intl").get(0x34, FixedLegends.NORMAL);
        check(apos != null && apos.dead && apos.text.equals("'"), "win:us-intl ' 是死键");
        check(win.sections.get("at") == win.sections.get("de"), "win:at 就是德语那张表（别名）");
        check(win.sections.get("cn-pinyin") == win.sections.get("us") && mac.sections.get("cn-pinyin") == mac.sections.get("us"),
                "拼音的字就是美式的（别名）");
        check(!win.sections.get("cn-pinyin").hasThird(), "win:cn-pinyin 没有第三层（右 Alt 写 Alt）");
    }

    private static void cell(FixedLegends.Table t, int usage, int layer, String want, String what) {
        FixedLegends.Cell c = t.get(usage, layer);
        eq(c == null ? null : c.text, want, what);
    }

    /** 带间距的附加符号（死键只该出现在这些上）。 */
    private static final String DIACRITICS = "´`^¨~˜ˆ°'\"¸˛˘˝ˇ˙¯˚";
    /** 死键不是附加符号、但数据里就是这么写的（同 tools/kbd/kbdtable.py 的 DEAD_EXCEPTIONS）：平台:布局 → 那几个字。 */
    private static final java.util.Map<String, String> DEAD_EXCEPTIONS = new java.util.HashMap<>();
    /** 26 个字母键的普通层正好是 a–z 不成立的（同 check_tables.py 的 LETTER_EXCEPTIONS）：平台:布局（* = 每家都是）。 */
    private static final java.util.Set<String> LETTER_EXCEPTIONS = new java.util.HashSet<>();

    static {
        DEAD_EXCEPTIONS.put("mac:cz", ",-");    // Mac 捷克 ⌥- / ⌥= 是写成 , 和 - 的死键（组合出 ģ、ā）
        DEAD_EXCEPTIONS.put("mac:sk", ",-");    // 斯洛伐克同捷克
        DEAD_EXCEPTIONS.put("mac:hu", "–");     // Mac 匈牙利 ⌥⇧, 是写成 – 的死键（组合出 ł）
        // AZERTY：M 在 ; 的位置、M 的位置出 ,
        LETTER_EXCEPTIONS.add("*:fr");
        LETTER_EXCEPTIONS.add("*:be");
        LETTER_EXCEPTIONS.add("mac:it-qzerty");   // Mac 旧版意大利是 QZERTY（同 AZERTY 的 M）；Windows 没有这一套
        LETTER_EXCEPTIONS.add("*:tr-q");          // I 的位置出无点的 ı，i 在 ' 的位置
        LETTER_EXCEPTIONS.add("*:tr-f");          // F 排法：q w x 不在字母键上
    }

    // ── 不变量：每个布局每种有数据的模式都过 ──
    static void invariants(FixedLegends.TableFile win, FixedLegends.TableFile mac, List<FixedLegends.Layout> catalog) {
        int tables = 0;
        for (FixedLegends.TableFile f : new FixedLegends.TableFile[]{win, mac}) {
            String plat = f.first("platform")[0];
            for (FixedLegends.Layout l : catalog) {
                if (!l.hasMode(plat)) continue;
                FixedLegends.Table t = f.sections.get(l.id);
                String id = plat + ":" + l.id;
                check(t != null, id + " 有这一段");
                if (t == null) continue;
                tables++;
                // 字母：26 个字母键普通层都有字，普通层凑得齐 a–z；26 个字母键正好是 a–z（例外见 LETTER_EXCEPTIONS）
                StringBuilder all = new StringBuilder(), keys = new StringBuilder();
                boolean filled = true;
                for (int u : t.usages()) {
                    FixedLegends.Cell c = t.get(u, FixedLegends.NORMAL);
                    if (c != null) all.append(c.text);
                }
                for (int u = 0x04; u <= 0x1D; u++) {
                    FixedLegends.Cell c = t.get(u, FixedLegends.NORMAL);
                    filled &= c != null;
                    if (c != null) keys.append(c.text);
                }
                check(filled, id + " 26 个字母键普通层都有字");
                String missing = "";
                for (char ch = 'a'; ch <= 'z'; ch++) if (all.indexOf(String.valueOf(ch)) < 0) missing += ch;
                check(missing.isEmpty(), id + " 普通层凑得齐 a–z（缺 " + missing + "）");
                char[] sorted = keys.toString().toCharArray();
                java.util.Arrays.sort(sorted);
                boolean exact = new String(sorted).equals("abcdefghijklmnopqrstuvwxyz");
                boolean exception = LETTER_EXCEPTIONS.contains(id) || LETTER_EXCEPTIONS.contains("*:" + l.id);
                check(exact != exception, id + " 字母键普通层" + (exact ? "正好是 a–z，例外表却写了它" : "不是 a–z：" + keys));
                // 形状要的键
                if (l.allows("iso") || l.allows("abnt2")) check(t.has(0x64), id + " 0x64 有字（ISO / ABNT2 Z 左边那个键）");
                if (l.allows("abnt2")) check(t.has(0x87), id + " 0x87 有字（ABNT2 右 Shift 左边那个键）");
                else check(!t.has(0x87), id + " 不是 ABNT2，没有 0x87 的字");
                for (int u : t.usages()) {
                    for (int layer = 0; layer < 4; layer++) {
                        FixedLegends.Cell c = t.get(u, layer);
                        if (c == null) continue;
                        boolean ctrl = false;
                        for (char ch : c.text.toCharArray()) ctrl |= Character.getType(ch) == Character.CONTROL;
                        if (ctrl) check(false, id + " " + Integer.toHexString(u) + " 有控制字符");
                        String ok = DEAD_EXCEPTIONS.getOrDefault(id, "");
                        if (c.dead && DIACRITICS.indexOf(c.text) < 0 && ok.indexOf(c.text) < 0) {
                            check(false, id + " " + Integer.toHexString(u) + " 死键不是附加符号：" + c.text);
                        }
                        if (c.text.codePointCount(0, c.text.length()) > 2) check(false, id + " " + Integer.toHexString(u) + " 一格太长：" + c.text);
                    }
                }
            }
        }
        check(tables == 65, "查了 65 张表（32 个有 Windows 数据的 + 33 个有 Mac 数据的）：" + tables);
    }

    // ── 没有数据的模式：字表里没有那一段，拼出来是 null（App 退回豆花布局，FixedData.ladder） ──
    static void missingModes(FixedLegends.TableFile win, FixedLegends.TableFile mac, List<FixedLegends.Layout> catalog) {
        int missing = 0;
        for (FixedLegends.Layout l : catalog) {
            for (String m : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
                FixedLegends.Table t = (FixedLayout.MAC.equals(m) ? mac : win).sections.get(l.id);
                check((t != null) == l.hasMode(m), l.id + " " + m + "：清单的 modes 和字表有没有这一段对得上");
                if (t == null) {
                    missing++;
                    check(FixedLayout.forLegends(l.shape, FixedLayout.TKL, m, null, l.kpdl) == null, l.id + " " + m + " 没数据：拼不出键盘（退回豆花布局）");
                }
            }
        }
        check(missing == 3, "只有拉美的 Mac 模式、意大利 QZERTY 和瑞典旧版的 Windows 模式没有数据（" + missing + "）");
    }

    // ── Mac：<id>.iso 是 <id> 把 0x35 / 0x64 对调（锁住对调），Mac 模式发苹果 ISO 的码、查不对调那段 ──
    static void macIso(FixedLegends.TableFile mac, List<FixedLegends.Layout> catalog) {
        for (FixedLegends.Layout l : catalog) {
            if (!l.hasMode(FixedLayout.MAC)) continue;
            String id = l.id;
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
        // 清单：按 #!columns 认栏，多出来的栏不管、顺序随便；没有这一行 / 缺栏 / 栏数不对都抛异常
        String cols = "#!columns name_android x id locale variant shape shapes modes kpdl kpdl_win kpco caps_mac name_mac name_mac_cldr name_win name_linux\n";
        List<FixedLegends.Layout> c = FixedLegends.parseCatalog("# 注释\n" + cols + "A\t?\tzz\ten-US\t\tansi\tansi,iso\twin\t.\t,\t\t\t\t\tW\tL\n");
        check(c.size() == 1 && c.get(0).id.equals("zz") && c.get(0).nameAndroid.equals("A") && c.get(0).nameWin.equals("W")
                && c.get(0).shapes.size() == 2 && c.get(0).hasMode("win") && !c.get(0).hasMode("mac") && c.get(0).nameMac.isEmpty(),
                "清单按 #!columns 认栏（顺序随便、多一栏不管、空的 modes 栏以外都读得出）");
        check(c.size() == 1 && c.get(0).kpdl.equals(".") && c.get(0).kpdlWin.equals(",") && c.get(0).kpdl(FixedLayout.WIN).equals(",")
                && c.get(0).kpdl(FixedLayout.MAC).equals(".") && c.get(0).kpdl("bogus").equals(","), "小数点按模式取：Windows（认不出的模式当 Windows）照 kpdl_win、Mac 照 kpdl");
        List<FixedLegends.Layout> c2 = FixedLegends.parseCatalog(cols + "A\t?\tzz\ten-US\t\tansi\tansi\tmac\t,\t\t\t\t\t\t\tL\n");
        check(c2.size() == 1 && !c2.get(0).hasMode("win") && c2.get(0).kpdl(FixedLayout.WIN).equals(",") && c2.get(0).kpdl(FixedLayout.MAC).equals(","),
                "kpdl_win 空着（只有 Mac 的数据）：两种模式都照 kpdl");
        String[] bad = {"zz\ten-US\n",
                cols.replace(" kpco", "") + "A\t?\tzz\ten-US\t\tansi\tansi\twin\t.\t,\t\t\t\tW\tL\n",
                cols + "A\t?\tzz\ten-US\n",
                cols + "A\t?\tz z\ten-US\t\tansi\tansi\twin\t.\t,\t\t\t\t\tW\tL\n"};
        String[] why = {"没有 #!columns", "缺 kpco 那一栏", "栏数不对", "id 有空格"};
        for (int i = 0; i < bad.length; i++) {
            threw = false;
            try {
                FixedLegends.parseCatalog(bad[i]);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "清单" + why[i] + "：抛异常");
        }
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
