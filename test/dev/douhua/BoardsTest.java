package dev.douhua;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Boards 的离线测试（方案 §4.4）：一份"豆花布局的每个键都存过值"的表，来回切换、改实体键盘的每一项、在实体键盘上换指点 / 调大小 / 恢复默认，
 * 最后 kb_ 开头的键集合和每个值都和开始时逐个相等；Boards 只写 kbd_ / kbx_ 开头的键。另测默认值、形状跟着布局走、大小位置按尺寸 × 指点 × 横竖屏分开
 * （2026-11-01 笔记本尺寸：三个尺寸各存各的，存取都显式给尺寸——逐级缩时存的是显示着的那个尺寸，savedRects 一次给齐三份）。
 */
public final class BoardsTest {
    private static int failures;
    private static int checks;

    /** 跑在 HashMap 上的存储，记下写过哪些键。 */
    static final class MapStore implements Boards.Store {
        final Map<String, Object> map = new HashMap<>();
        final List<String> written = new ArrayList<>();

        @Override public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
        @Override public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
        @Override public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
        @Override public boolean contains(String k) { return map.containsKey(k); }
        @Override public void putString(String k, String v) { written.add(k); map.put(k, v); }
        @Override public void putInt(String k, int v) { written.add(k); map.put(k, v); }
        @Override public void putFloat(String k, float v) { written.add(k); map.put(k, v); }
        @Override public void remove(String k) { written.add(k); map.remove(k); }
    }

    public static void main(String[] args) throws IOException {
        List<FixedLegends.Layout> catalog = FixedLegends.parseCatalog(FixedLegendsTest.read("res/raw/kbd_catalog.txt"));
        defaults(catalog);
        shapeFollowsLang(catalog);
        rects(catalog);
        keepsDouhua(catalog);
        abnt2Shape(catalog);
        modes(catalog);
        names(catalog);
        System.err.println("键盘切换 / 状态保留：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static void defaults(List<FixedLegends.Layout> catalog) {
        Boards b = new Boards(new MapStore(), catalog);
        check(!b.fixedSelected(), "没选过：豆花布局（老用户升级后和以前一样）");
        eq(b.spec().toString(), "us/ansi/tkl/win", "实体键盘默认：美式 ANSI TKL Windows 模式");
        check(b.pointer() == KeyboardPlan.POINTER_PAD, "实体键盘的指点默认触控板");
        check(b.savedRect(b.size(), true) == null && b.savedRect(b.size(), false) == null && b.savedRects(true).isEmpty(), "没调过大小位置");
        b.select(true);
        check(b.fixedSelected(), "选实体键盘");
        b.select(false);
        check(!b.fixedSelected(), "换回豆花布局");
        MapStore s = new MapStore();
        s.putString(Boards.KEY_LANG, "xx");
        eq(new Boards(s, catalog).lang(), "us", "清单里没有的布局（以后删了）当美式");
        eq(new Boards(new MapStore(), null).spec().toString(), "us/ansi/tkl/win", "清单读不出来也有默认值");
    }

    static void shapeFollowsLang(List<FixedLegends.Layout> catalog) {
        MapStore s = new MapStore();
        Boards b = new Boards(s, catalog);
        b.setShape(FixedLayout.ISO);
        eq(b.shape(), "iso", "美式能选 ISO");
        b.setLang("de");
        eq(b.shape(), "iso", "德语只有 ISO");
        b.setShape(FixedLayout.ANSI);
        eq(b.shape(), "iso", "德语选了 ANSI 也按 ISO 画（存的值不动）");
        b.setLang("us");
        eq(b.shape(), "ansi", "换回美式：存的 ANSI 还在");
        b.setSize("bogus");
        eq(b.size(), "tkl", "认不出的尺寸当 TKL");
        b.setSize(FixedLayout.LAPTOP);
        eq(b.size(), "laptop", "笔记本尺寸");
        MapStore raw = new MapStore();
        raw.putString(Boards.KEY_SIZE, "huge");
        eq(new Boards(raw, catalog).size(), "tkl", "存着认不出的尺寸也当 TKL");
        b.setMode(FixedLayout.MAC);
        eq(b.mode(), "mac", "Mac 模式");
    }

    static void rects(List<FixedLegends.Layout> catalog) {
        MapStore s = new MapStore();
        Boards b = new Boards(s, catalog);
        float[] r1 = {0.1f, 0.2f, 0.9f, 1};
        String tkl = FixedLayout.TKL;
        b.setRect(tkl, true, r1);
        check(eq4(b.savedRect(tkl, true), r1), "存下横屏的");
        check(b.savedRect(tkl, false) == null, "竖屏的另一份");
        check(b.savedRect(FixedLayout.FULL, true) == null, "全尺寸另一份（换尺寸各回各的）");
        b.setSize(FixedLayout.FULL);
        check(eq4(b.savedRect(tkl, true), r1), "选的尺寸换了，TKL 那份照样在（存取按给的尺寸，不按选的）");
        b.setPointer(KeyboardPlan.POINTER_STICK);
        check(b.savedRect(tkl, true) == null, "小红点另一份（有触控板时避让、没有时不避让）");
        b.setPointer(KeyboardPlan.POINTER_PAD);
        b.setLang("de");
        b.setMode(FixedLayout.MAC);
        check(eq4(b.savedRect(tkl, true), r1), "换语言、模式不挪");
        // 笔记本（逐级缩时存的是显示着的那个尺寸）：三个尺寸各存各的
        float[] r2 = {0, 0.3f, 0.7f, 1}, r3 = {0.2f, 0, 1, 0.9f};
        b.setRect(FixedLayout.LAPTOP, true, r2);
        check(eq4(b.savedRect(FixedLayout.LAPTOP, true), r2) && eq4(b.savedRect(tkl, true), r1), "存笔记本不碰 TKL");
        b.setRect(FixedLayout.FULL, true, r3);
        java.util.Map<String, float[]> all = b.savedRects(true);
        check(all.size() == 3 && eq4(all.get(FixedLayout.FULL), r3) && eq4(all.get(tkl), r1) && eq4(all.get(FixedLayout.LAPTOP), r2), "savedRects：三份都在、值对得上");
        check(b.savedRects(false).isEmpty(), "savedRects：竖屏一份都没存");
        b.resetRect(FixedLayout.LAPTOP, true);
        check(b.savedRect(FixedLayout.LAPTOP, true) == null && eq4(b.savedRect(tkl, true), r1) && eq4(b.savedRect(FixedLayout.FULL, true), r3),
                "恢复默认只清给的那个尺寸");
        all = b.savedRects(true);
        check(all.size() == 2 && !all.containsKey(FixedLayout.LAPTOP), "savedRects 只含存过的那几项");
        b.resetRect(tkl, true);
        check(b.savedRect(tkl, true) == null, "恢复默认只清这一份");
        check(Boards.rectKey(FixedLayout.LAPTOP, KeyboardPlan.POINTER_PAD, true).equals("kbx_laptop_pad_land_"), "笔记本的键名 kbx_laptop_pad_land_");
        for (String size : FixedLayout.SIZES_DESC) {
            for (int p = 0; p < 3; p++) {
                for (boolean land : new boolean[]{true, false}) {
                    String k = Boards.rectKey(size, p, land);
                    check(!k.startsWith("kb_") && k.startsWith("kbx_"), "键名不以 kb_ 开头：" + k);
                }
            }
        }
        check(!Boards.rectKey(FixedLayout.TKL, 0, true).equals(Boards.rectKey(FixedLayout.TKL, 0, false)), "横竖屏键名不同");
    }

    /** 豆花布局的键：Prefs 里 kb_ 开头的全部（含已经不读的旧键）。 */
    static Map<String, Object> douhuaKeys() {
        Map<String, Object> m = new TreeMap<>();
        m.put("kb_layout", 1);
        m.put("kb_pointer", 1);
        m.put("kb_pad", Boolean.TRUE);
        m.put("kb_toolbar_bottom", Boolean.FALSE);
        int n = 0;
        for (String state : new String[]{"", "stick_", "none_"}) {
            for (String kind : new String[]{"", "split_"}) {
                for (String o : new String[]{"land_", "port_"}) {
                    for (String e : new String[]{"l", "t", "r", "b"}) m.put("kb_" + state + kind + o + e, 0.01f * ++n);
                }
            }
        }
        for (int i = 0; i < Prefs_KEYBOARD_ROW_COUNT; i++) m.put("kb_sc_" + i, "combo:" + (0x104 + i) + ":x" + i);
        return m;
    }

    /** Prefs.KEYBOARD_ROW_COUNT（Prefs 依赖 Android，不进离线编译）。 */
    private static final int Prefs_KEYBOARD_ROW_COUNT = 9;

    static void keepsDouhua(List<FixedLegends.Layout> catalog) {
        Random rnd = new Random(20261101);
        int ops = 0, shapeBad = 0;
        for (int round = 0; round < 40; round++) {
            MapStore s = new MapStore();
            Map<String, Object> before = douhuaKeys();
            s.map.putAll(before);
            s.map.put("theme", "nord");
            Boards b = new Boards(s, catalog);
            for (int i = 0; i < 60; i++) {
                ops++;
                switch (rnd.nextInt(9)) {
                    case 0: b.select(rnd.nextBoolean()); break;
                    case 1: b.setLang(rnd.nextInt(3) == 0 ? "xx" : catalog.get(rnd.nextInt(catalog.size())).id); break;   // 第 2 批：清单里的任一个（"xx" = 清单里没有的）
                    case 2: b.setShape(rnd.nextInt(4) == 0 ? "jis" : FixedLayout.SHAPES[rnd.nextInt(FixedLayout.SHAPES.length)]); break;
                    case 3: b.setSize(FixedLayout.SIZES_DESC[rnd.nextInt(3)]); break;
                    case 4: b.setMode(rnd.nextBoolean() ? FixedLayout.WIN : FixedLayout.MAC); break;
                    case 5: b.setPointer(rnd.nextInt(3)); break;
                    case 6: b.setRect(FixedLayout.SIZES_DESC[rnd.nextInt(3)], rnd.nextBoolean(), new float[]{rnd.nextFloat() * 0.3f, rnd.nextFloat() * 0.3f, 0.7f, 1}); break;
                    case 7: b.resetRect(FixedLayout.SIZES_DESC[rnd.nextInt(3)], rnd.nextBoolean()); break;
                    default: b.spec(); b.savedRect(FixedLayout.SIZES_DESC[rnd.nextInt(3)], rnd.nextBoolean()); b.savedRects(rnd.nextBoolean()); b.fixedSelected(); b.modeAvailable(); break;
                }
                // 读出来的形状一定是这个布局能选的（拼出来的键盘和清单对得上）
                FixedLegends.Layout l = FixedLegends.find(catalog, b.lang());
                if (l != null && !l.allows(b.shape())) shapeBad++;
            }
            Map<String, Object> after = new TreeMap<>();
            for (Map.Entry<String, Object> e : s.map.entrySet()) if (e.getKey().startsWith("kb_")) after.put(e.getKey(), e.getValue());
            check(after.equals(before), "第 " + round + " 轮：kb_ 开头的键集合和每个值都没变");
            boolean only = true;
            for (String k : s.written) only &= k.startsWith("kbd_") || k.startsWith("kbx_");
            check(only, "第 " + round + " 轮：只写了 kbd_ / kbx_ 开头的键");
            check("nord".equals(s.map.get("theme")), "第 " + round + " 轮：别的设置（主题）没动");
        }
        check(shapeBad == 0, "随机序列里读出来的形状都是这个布局能选的（" + shapeBad + " 步不是）");
        System.err.println("  键盘切换随机序列：" + ops + " 步");
    }

    // ── 第 2 批：巴西只有 ABNT2；存的形状不动，换回别的布局照旧 ──
    static void abnt2Shape(List<FixedLegends.Layout> catalog) {
        MapStore s = new MapStore();
        Boards b = new Boards(s, catalog);
        b.setShape(FixedLayout.ISO);
        b.setLang("br");
        eq(b.shape(), "abnt2", "巴西只有 ABNT2（存的 ISO 不用）");
        eq(b.spec().toString(), "br/abnt2/tkl/win", "巴西的 spec");
        b.setShape(FixedLayout.ABNT2);
        eq(b.shape(), "abnt2", "巴西选 ABNT2");
        eq((String) s.map.get(Boards.KEY_SHAPE), "abnt2", "存的就是 abnt2（validShape 认得，不换成 ANSI）");
        b.setLang("us");
        eq(b.shape(), "ansi", "美式不能选 ABNT2：按美式的默认 ANSI 画（存的 abnt2 不动）");
        eq((String) s.map.get(Boards.KEY_SHAPE), "abnt2", "存的值没动");
        b.setLang("br");
        eq(b.shape(), "abnt2", "换回巴西还是 ABNT2");
        b.setLang("de");
        eq(b.shape(), "iso", "德语只有 ISO");
        MapStore raw = new MapStore();
        raw.putString(Boards.KEY_LANG, "br");
        raw.putString(Boards.KEY_SHAPE, "jis");
        eq(new Boards(raw, catalog).shape(), "abnt2", "存着认不出的形状：按布局的默认");
        eq(new Boards(raw, null).shape(), "ansi", "清单读不出来：认不出的形状当 ANSI");
        MapStore raw2 = new MapStore();
        raw2.putString(Boards.KEY_SHAPE, "abnt2");
        eq(new Boards(raw2, null).shape(), "abnt2", "清单读不出来：存的 abnt2 照用（FixedLayout 认得）");
        // 清单里每个布局：没存过形状时读出来就是它的默认形状，而且 FixedLayout 认得
        for (FixedLegends.Layout l : catalog) {
            MapStore m = new MapStore();
            Boards x = new Boards(m, catalog);
            x.setLang(l.id);
            eq(x.shape(), l.shape, l.id + " 没存过形状：默认 " + l.shape);
            check(FixedLayout.validShape(x.shape()).equals(x.shape()), l.id + " 的形状 FixedLayout 认得");
        }
    }

    // ── 第 2 批：某布局某模式没有键帽数据（拉美没有 Mac 的，意大利 QZERTY、瑞典旧版没有 Windows 的）——设置页灰掉的规则 ──
    static void modes(List<FixedLegends.Layout> catalog) {
        check(!Boards.modeAvailable(catalog, "latam", FixedLayout.MAC) && Boards.modeAvailable(catalog, "latam", FixedLayout.WIN), "拉美：Mac 模式灰掉、Windows 能选");
        check(Boards.modeAvailable(catalog, "us", FixedLayout.MAC) && Boards.modeAvailable(catalog, "br", FixedLayout.MAC), "美式、巴西两种模式都有");
        check(Boards.modeAvailable(catalog, "latam", "bogus"), "认不出的模式当 Windows：拉美有");
        check(Boards.modeAvailable(catalog, "xx", FixedLayout.MAC) && Boards.modeAvailable(null, "latam", FixedLayout.MAC), "清单里没有 / 清单读不出来：不灰（拼不拼得出另看字表）");
        int grey = 0;
        for (FixedLegends.Layout l : catalog) for (String m : new String[]{FixedLayout.WIN, FixedLayout.MAC}) {
            check(Boards.modeAvailable(catalog, l.id, m) == l.hasMode(m), l.id + " " + m + "：灰不灰和清单的 modes 一致");
            if (!l.hasMode(m)) grey++;
        }
        check(grey == 3, "现在三处没数据（拉美的 Mac，意大利 QZERTY、瑞典旧版的 Windows）：" + grey);
        for (String id : new String[]{"it-qzerty", "se-legacy"}) {
            check(!Boards.modeAvailable(catalog, id, FixedLayout.WIN) && Boards.modeAvailable(catalog, id, FixedLayout.MAC),
                    id + "：Windows 模式灰掉（Windows 没有这一套，不拿普通的顶上）、Mac 能选");
            check(!Boards.modeAvailable(catalog, id, "bogus"), id + "：认不出的模式当 Windows，灰掉");
        }
        MapStore s = new MapStore();
        Boards b = new Boards(s, catalog);
        b.setLang("latam");
        check(b.modeAvailable(), "拉美 + Windows 模式：有数据");
        b.setMode(FixedLayout.MAC);
        check(!b.modeAvailable(), "拉美 + Mac 模式：没数据（键盘页退回豆花布局）");
        eq(b.spec().toString(), "latam/iso/tkl/mac", "没数据的组合存的值不动（不悄悄改成 Windows）");
        b.setLang("es");
        check(b.modeAvailable(), "换成西班牙：Mac 模式有数据");
        // 只有 Mac 的：Mac 模式下选它有数据；存着它 + Windows 模式（设置页选不出来）时没数据、存的值不动（键盘页退回豆花布局）
        b.setLang("it-qzerty");
        check(b.modeAvailable(), "意大利 QZERTY + Mac 模式：有数据");
        b.setMode(FixedLayout.WIN);
        check(!b.modeAvailable(), "意大利 QZERTY + Windows 模式：没数据（键盘页退回豆花布局）");
        eq(b.spec().toString(), "it-qzerty/iso/tkl/win", "没数据的组合存的值不动（不悄悄改成 Mac、不换成普通意大利）");
    }

    // ── 第 2 批：名字（同一种语言有几个地区才带地区、区分词接在后面、首字母大写）和设置页的语言列表（按名字排、没数据的灰掉）──
    static void names(List<FixedLegends.Layout> catalog) {
        java.util.function.Function<String, String> words = w -> "[" + w + "]";
        String join = "%1$s · %2$s";
        String[] uis = {"zh", "en", "ja", "ko", "fr", "de", "it", "es", "ar"};
        for (String tag : uis) {
            java.util.Locale ui = java.util.Locale.forLanguageTag(tag);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (FixedLegends.Layout l : catalog) {
                String n = Boards.langName(catalog, l, ui, words, join);
                check(seen.add(n), tag + "：" + l.id + " 的名字不和别的重名：" + n);
                int cp = n.codePointAt(0);
                check(!Character.isLowerCase(cp), tag + "：" + l.id + " 的名字首字母不是小写：" + n);
                check(l.variant.isEmpty() == !n.contains(" · "), tag + "：" + l.id + " 有区分词才接 · ：" + n);
            }
            // 列表：每个布局一项、按名字排（Collator）、Mac 模式下拉美灰掉
            for (String mode : new String[]{FixedLayout.WIN, FixedLayout.MAC, "bogus"}) {
                List<Boards.LangChoice> cs = Boards.langChoices(catalog, mode, ui, words, join);
                check(cs.size() == catalog.size(), tag + " " + mode + "：列表 " + cs.size() + " 项");
                java.text.Collator c = java.text.Collator.getInstance(ui);
                boolean sorted = true;
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (int i = 0; i < cs.size(); i++) {
                    ids.add(cs.get(i).layout.id);
                    if (i > 0) sorted &= c.compare(cs.get(i - 1).name, cs.get(i).name) <= 0;
                    boolean want = cs.get(i).layout.hasMode(FixedLayout.MAC.equals(mode) ? FixedLayout.MAC : FixedLayout.WIN);
                    check(cs.get(i).enabled == want, tag + " " + mode + "：" + cs.get(i).layout.id + (want ? " 能选" : " 灰掉"));
                    check(cs.get(i).name.equals(Boards.langName(catalog, cs.get(i).layout, ui, words, join)), tag + "：列表里的名字就是 langName");
                }
                check(sorted, tag + " " + mode + "：按名字排");
                check(ids.size() == catalog.size(), tag + " " + mode + "：每个布局正好一项");
                // 同一种语言的挨在一起（按名字排的自然结果：语言名在前）
                List<String> langs = new ArrayList<>();
                for (Boards.LangChoice x : cs) {
                    String lg = java.util.Locale.forLanguageTag(x.layout.locale).getLanguage();
                    if (langs.isEmpty() || !langs.get(langs.size() - 1).equals(lg)) langs.add(lg);
                }
                check(langs.size() == new java.util.HashSet<>(langs).size(), tag + " " + mode + "：同一种语言的几项挨在一起：" + langs);
            }
        }
        // 带不带地区
        java.util.Set<String> withRegion = new java.util.TreeSet<>();
        for (FixedLegends.Layout l : catalog) if (Boards.regionNeeded(catalog, l)) withRegion.add(l.id);
        check(withRegion.equals(new java.util.TreeSet<>(java.util.Arrays.asList("us", "us-intl", "gb", "fr", "be", "ca-fr", "ch-fr", "de", "at", "ch-de",
                "es", "latam", "pt", "br"))), "同一种语言有几个地区的才带地区（英、法、德、西、葡）：" + withRegion);
        check(!Boards.regionNeeded(null, catalog.get(0)), "清单是 null：不带地区");
        // 英文界面的几个名字（JDK 的语言数据；Android 上是 ICU 的，写法可能略有不同）
        java.util.Locale en = java.util.Locale.ENGLISH;
        String[][] want = {{"us", "English (United States)"}, {"us-intl", "English (United States) · [intl]"}, {"gb", "English (United Kingdom)"},
                {"se", "Swedish"}, {"se-legacy", "Swedish · [legacy]"}, {"ca-fr", "French (Canada)"}, {"no", "Norwegian"}, {"latam", "Spanish (Latin America)"},
                {"br", "Portuguese (Brazil)"}, {"tr-q", "Turkish · Q"}, {"it-qzerty", "Italian · QZERTY"}, {"cn-pinyin", "Chinese · [pinyin]"}, {"de", "German (Germany)"}};
        for (String[] w : want) eq(Boards.langName(catalog, FixedLegends.find(catalog, w[0]), en, words, join), w[1], "英文界面 " + w[0]);
        eq(Boards.langName(catalog, FixedLegends.find(catalog, "fr"), java.util.Locale.FRENCH, words, join).substring(0, 1), "F", "法文界面首字母大写（français → Français）");
        eq(Boards.langName(catalog, FixedLegends.find(catalog, "se-legacy"), en, null, join), "Swedish · legacy", "没给翻译：区分词照写");
        eq(Boards.langName(catalog, FixedLegends.find(catalog, "se-legacy"), en, w -> null, join), "Swedish · legacy", "翻译返回 null：照写");
        check(Boards.langChoices(null, FixedLayout.WIN, en, words, join).isEmpty(), "清单是 null：空列表");
    }

    private static boolean eq4(float[] a, float[] b) {
        if (a == null || b == null) return a == b;
        for (int i = 0; i < 4; i++) if (Math.abs(a[i] - b[i]) > 1e-6f) return false;
        return true;
    }

    static void eq(String got, String want, String what) {
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
