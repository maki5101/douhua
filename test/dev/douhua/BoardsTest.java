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
 * 最后 kb_ 开头的键集合和每个值都和开始时逐个相等；Boards 只写 kbd_ / kbx_ 开头的键。另测默认值、形状跟着布局走、大小位置按尺寸 × 指点 × 横竖屏分开。
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
        System.err.println("键盘切换 / 状态保留：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static void defaults(List<FixedLegends.Layout> catalog) {
        Boards b = new Boards(new MapStore(), catalog);
        check(!b.fixedSelected(), "没选过：豆花布局（老用户升级后和以前一样）");
        eq(b.spec().toString(), "us/ansi/tkl/win", "实体键盘默认：美式 ANSI TKL Windows 模式");
        check(b.pointer() == KeyboardPlan.POINTER_PAD, "实体键盘的指点默认触控板");
        check(b.savedRect(true) == null && b.savedRect(false) == null, "没调过大小位置");
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
        b.setMode(FixedLayout.MAC);
        eq(b.mode(), "mac", "Mac 模式");
    }

    static void rects(List<FixedLegends.Layout> catalog) {
        MapStore s = new MapStore();
        Boards b = new Boards(s, catalog);
        float[] r1 = {0.1f, 0.2f, 0.9f, 1};
        b.setRect(true, r1);
        check(eq4(b.savedRect(true), r1), "存下横屏的");
        check(b.savedRect(false) == null, "竖屏的另一份");
        b.setSize(FixedLayout.FULL);
        check(b.savedRect(true) == null, "全尺寸另一份（换尺寸各回各的）");
        b.setSize(FixedLayout.TKL);
        check(eq4(b.savedRect(true), r1), "换回 TKL 还在");
        b.setPointer(KeyboardPlan.POINTER_STICK);
        check(b.savedRect(true) == null, "小红点另一份（有触控板时避让、没有时不避让）");
        b.setPointer(KeyboardPlan.POINTER_PAD);
        b.setLang("de");
        b.setMode(FixedLayout.MAC);
        check(eq4(b.savedRect(true), r1), "换语言、模式不挪");
        b.resetRect(true);
        check(b.savedRect(true) == null, "恢复默认只清这一份");
        for (String size : new String[]{FixedLayout.TKL, FixedLayout.FULL}) {
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
        int ops = 0;
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
                    case 1: b.setLang(rnd.nextBoolean() ? "us" : "de"); break;
                    case 2: b.setShape(rnd.nextBoolean() ? FixedLayout.ANSI : FixedLayout.ISO); break;
                    case 3: b.setSize(rnd.nextBoolean() ? FixedLayout.TKL : FixedLayout.FULL); break;
                    case 4: b.setMode(rnd.nextBoolean() ? FixedLayout.WIN : FixedLayout.MAC); break;
                    case 5: b.setPointer(rnd.nextInt(3)); break;
                    case 6: b.setRect(rnd.nextBoolean(), new float[]{rnd.nextFloat() * 0.3f, rnd.nextFloat() * 0.3f, 0.7f, 1}); break;
                    case 7: b.resetRect(rnd.nextBoolean()); break;
                    default: b.spec(); b.savedRect(rnd.nextBoolean()); b.fixedSelected(); break;
                }
            }
            Map<String, Object> after = new TreeMap<>();
            for (Map.Entry<String, Object> e : s.map.entrySet()) if (e.getKey().startsWith("kb_")) after.put(e.getKey(), e.getValue());
            check(after.equals(before), "第 " + round + " 轮：kb_ 开头的键集合和每个值都没变");
            boolean only = true;
            for (String k : s.written) only &= k.startsWith("kbd_") || k.startsWith("kbx_");
            check(only, "第 " + round + " 轮：只写了 kbd_ / kbx_ 开头的键");
            check("nord".equals(s.map.get("theme")), "第 " + round + " 轮：别的设置（主题）没动");
        }
        System.err.println("  键盘切换随机序列：" + ops + " 步");
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
