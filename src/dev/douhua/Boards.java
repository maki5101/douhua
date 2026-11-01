package dev.douhua;

import java.util.List;

/**
 * 键盘页用哪块键盘（豆花布局 / 实体键盘）和实体键盘自己的设置，纯 Java（离线测，BoardsTest）。跑在一个只有读写删的小接口上（{@link Store}）：
 * App 里接 SharedPreferences（Prefs.store），测试里接 HashMap。
 *
 * 状态保留（用户 2026-11-01："豆花布局整个保留：切到实体键盘再切回来"）：豆花布局的设置全是 kb_ 开头的键（kb_layout、kb_pointer / kb_pad、
 * kb_land_* / kb_port_*、kb_split_*、kb_stick_*、kb_none_*、kb_sc_*），这里一个都不读不写；实体键盘的全存在不以 kb_ 开头的键下：
 *   kbd_board              当前用哪块：douhua（默认，老用户升级后和以前一样）/ fixed
 *   kbx_lang               布局（kbd_catalog 的 id：us、de），默认 us
 *   kbx_shape              形状 ansi / iso；这个布局不能选的（德语只有 ISO）读的时候换成布局的默认形状，存的值不动（换回能选的布局时还在）
 *   kbx_size               tkl（默认）/ full
 *   kbx_mode               win（默认，连 Linux、安卓也用它）/ mac（连 iPad 也用它）
 *   kbx_pointer            键盘页的指点（KeyboardPlan.POINTER_*），和豆花布局的 kb_pointer 分开记：在实体键盘上换成小红点不会把豆花布局也换了
 *   kbx_<尺寸>_<指点>_<land|port>_<l|t|r|b>   大小位置（键盘区的 0–1，没有 = 默认）：按尺寸 × 指点 × 横竖屏各一份
 *                          （换语言、形状、模式不挪；全尺寸和 TKL 来回换各回各的；有触控板时避让、没有时不避让，同豆花布局按指点分开记）
 * 只有一块实体键盘（用户 2026-11-01 要的是"选哪块实体键盘：语言、形状、尺寸、Windows / Mac"，⋯ 菜单里豆花 / 实体两块切换）；
 * 方案里的"键盘列表、按主机记住、主机系统、互换开关"都不做（用户："主机怎么读那是主机的事，不是键盘设计者应该考虑的问题"）。
 * 切换只改 kbd_board；换语言等只改 kbx_ 的那几个。"恢复默认"（Prefs.resetAll）照旧整个清空，实体键盘的设置一起清掉。
 */
final class Boards {
    /** 存储：get 不到给默认值，put / remove 马上生效（App 里是 SharedPreferences.apply）。 */
    interface Store {
        String getString(String key, String def);

        int getInt(String key, int def);

        float getFloat(String key, float def);

        boolean contains(String key);

        void putString(String key, String value);

        void putInt(String key, int value);

        void putFloat(String key, float value);

        void remove(String key);
    }

    static final String DOUHUA = "douhua", FIXED = "fixed";
    static final String KEY_BOARD = "kbd_board";
    static final String KEY_LANG = "kbx_lang", KEY_SHAPE = "kbx_shape", KEY_SIZE = "kbx_size", KEY_MODE = "kbx_mode",
            KEY_POINTER = "kbx_pointer";
    /** 没选过布局（或者选的布局清单里没有了）时用它。 */
    static final String DEFAULT_LANG = "us";

    /** 实体键盘是哪一块：布局 id、形状、尺寸、模式（都已经按清单核过，能直接拿去拼）。 */
    static final class Spec {
        final String lang, shape, size, mode;

        Spec(String lang, String shape, String size, String mode) {
            this.lang = lang;
            this.shape = shape;
            this.size = size;
            this.mode = mode;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Spec)) return false;
            Spec s = (Spec) o;
            return lang.equals(s.lang) && shape.equals(s.shape) && size.equals(s.size) && mode.equals(s.mode);
        }

        @Override
        public int hashCode() {
            return (lang + "/" + shape + "/" + size + "/" + mode).hashCode();
        }

        @Override
        public String toString() {
            return lang + "/" + shape + "/" + size + "/" + mode;
        }
    }

    private final Store store;
    private final List<FixedLegends.Layout> catalog;

    /** catalog = 布局清单（kbd_catalog.txt，FixedLegends.parseCatalog）；null / 空的时候只认 {@link #DEFAULT_LANG}、ANSI。 */
    Boards(Store store, List<FixedLegends.Layout> catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    // ═══════════ 用哪块 ═══════════

    /** 选的是实体键盘（不然是豆花布局）。 */
    boolean fixedSelected() {
        return FIXED.equals(store.getString(KEY_BOARD, DOUHUA));
    }

    /** 换成实体键盘 / 豆花布局：只写 kbd_board。 */
    void select(boolean fixed) {
        store.putString(KEY_BOARD, fixed ? FIXED : DOUHUA);
    }

    // ═══════════ 实体键盘是哪一块 ═══════════

    /** 布局 id：存的那个在清单里就用它，不然 {@link #DEFAULT_LANG}（清单里也没有就是清单第一个）。 */
    String lang() {
        String id = store.getString(KEY_LANG, DEFAULT_LANG);
        if (FixedLegends.find(catalog, id) != null) return id;
        if (catalog == null || catalog.isEmpty() || FixedLegends.find(catalog, DEFAULT_LANG) != null) return DEFAULT_LANG;
        return catalog.get(0).id;
    }

    void setLang(String id) {
        store.putString(KEY_LANG, id);
    }

    /** 形状：这个布局能选的话用存的，不然用布局的默认形状（存的值不动）。 */
    String shape() {
        FixedLegends.Layout l = FixedLegends.find(catalog, lang());
        String s = store.getString(KEY_SHAPE, null);
        if (l == null) return FixedLayout.validShape(s);
        if (s != null && l.allows(s)) return s;
        return FixedLayout.validShape(l.shape);
    }

    void setShape(String shape) {
        store.putString(KEY_SHAPE, FixedLayout.validShape(shape));
    }

    String size() {
        return FixedLayout.validSize(store.getString(KEY_SIZE, FixedLayout.TKL));
    }

    void setSize(String size) {
        store.putString(KEY_SIZE, FixedLayout.validSize(size));
    }

    String mode() {
        return FixedLayout.validMode(store.getString(KEY_MODE, FixedLayout.WIN));
    }

    void setMode(String mode) {
        store.putString(KEY_MODE, FixedLayout.validMode(mode));
    }

    Spec spec() {
        return new Spec(lang(), shape(), size(), mode());
    }

    // ═══════════ 指点、大小位置（实体键盘自己的一份） ═══════════

    int pointer() {
        return KeyboardPlan.validPointer(store.getInt(KEY_POINTER, KeyboardPlan.POINTER_PAD));
    }

    void setPointer(int pointer) {
        store.putInt(KEY_POINTER, KeyboardPlan.validPointer(pointer));
    }

    /** 大小位置的键名前缀：kbx_<尺寸>_<指点>_<land|port>_。 */
    static String rectKey(String size, int pointer, boolean landscape) {
        int p = KeyboardPlan.validPointer(pointer);
        String ptr = p == KeyboardPlan.POINTER_STICK ? "stick" : p == KeyboardPlan.POINTER_NONE ? "none" : "pad";
        return "kbx_" + FixedLayout.validSize(size) + "_" + ptr + "_" + (landscape ? "land_" : "port_");
    }

    /** 现在这个尺寸、这种指点状态下存的大小位置 {左, 上, 右, 下}（0–1）；没存过返回 null（KeyboardPlan.fixedDefaultRect 按窗口给）。 */
    float[] savedRect(boolean landscape) {
        String p = rectKey(size(), pointer(), landscape);
        if (!store.contains(p + "l")) return null;
        return new float[]{store.getFloat(p + "l", 0), store.getFloat(p + "t", 0), store.getFloat(p + "r", 1), store.getFloat(p + "b", 1)};
    }

    void setRect(boolean landscape, float[] r) {
        String p = rectKey(size(), pointer(), landscape);
        store.putFloat(p + "l", r[0]);
        store.putFloat(p + "t", r[1]);
        store.putFloat(p + "r", r[2]);
        store.putFloat(p + "b", r[3]);
    }

    /** 调整栏的"恢复默认"：只清现在这个尺寸、这种指点、这个方向的那份。 */
    void resetRect(boolean landscape) {
        String p = rectKey(size(), pointer(), landscape);
        for (String e : new String[]{"l", "t", "r", "b"}) store.remove(p + e);
    }
}
