package dev.douhua;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 键盘页用哪块键盘（豆花布局 / 实体键盘）和实体键盘自己的设置，纯 Java（离线测，BoardsTest）。跑在一个只有读写删的小接口上（{@link Store}）：
 * App 里接 SharedPreferences（Prefs.store），测试里接 HashMap。
 *
 * 状态保留（用户 2026-11-01："豆花布局整个保留：切到实体键盘再切回来"）：豆花布局的设置全是 kb_ 开头的键（kb_layout、kb_pointer / kb_pad、
 * kb_land_* / kb_port_*、kb_split_*、kb_stick_*、kb_none_*、kb_sc_*），这里一个都不读不写；实体键盘的全存在不以 kb_ 开头的键下：
 *   kbd_board              当前用哪块：douhua（默认，老用户升级后和以前一样）/ fixed
 *   kbx_lang               布局（kbd_catalog 的 id：us、de……第 2 批起 34 个），默认 us
 *   kbx_shape              形状 ansi / iso / abnt2（第 2 批）；这个布局不能选的（德语只有 ISO、巴西只有 ABNT2）读的时候换成布局的默认形状，
 *                          存的值不动（换回能选的布局时还在）
 *   kbx_size               tkl（默认）/ full / laptop（笔记本，2026-11-01）：逐级缩的起点（放不下就往小换，FixedLayout.ladder），存的值不跟着缩
 *   kbx_mode               win（默认，连 Linux、安卓也用它）/ mac（连 iPad 也用它）
 *   kbx_pointer            键盘页的指点（KeyboardPlan.POINTER_*），和豆花布局的 kb_pointer 分开记：在实体键盘上换成小红点不会把豆花布局也换了
 *   kbx_<尺寸>_<指点>_<land|port>_<l|t|r|b>   大小位置（键盘区的 0–1，没有 = 默认）：按尺寸 × 指点 × 横竖屏各一份
 *                          （换语言、形状、模式不挪；全尺寸、TKL、笔记本来回换各回各的；有触控板时避让、没有时不避让，同豆花布局按指点分开记）。
 *                          尺寸是**显示着的**那个（逐级缩成笔记本时取、存的都是 kbx_laptop_…），所以存取都显式给尺寸，不按选的尺寸
 * 只有一块实体键盘（用户 2026-11-01 要的是"选哪块实体键盘：语言、形状、尺寸、Windows / Mac"，⋯ 菜单里豆花 / 实体两块切换）；
 * 方案里的"键盘列表、按主机记住、主机系统、互换开关"都不做（用户："主机怎么读那是主机的事，不是键盘设计者应该考虑的问题"）。
 * 切换只改 kbd_board；换语言等只改 kbx_ 的那几个。"恢复默认"（Prefs.resetAll）照旧整个清空，实体键盘的设置一起清掉。
 *
 * 第 2 批（2026-11-01）多了设置页语言列表的规则（{@link #langName}、{@link #langChoices}、{@link #modeAvailable}）：布局叫什么、按名字排、
 * 某布局在某种模式下没有键帽数据（拉美没有 Mac 的）时灰掉。灰掉只管设置页能不能选；存着的组合要是正好没数据（以后清单变了之类），
 * 键盘页照 1B 的规则退回豆花布局（FixedData.ladder 是空的），存的值不动。
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

    /**
     * size 这个尺寸（显示着的那个，不一定是选的：逐级缩）、现在这种指点状态下存的大小位置 {左, 上, 右, 下}（0–1）；
     * 没存过返回 null（KeyboardPlan.fixedDefaultRect 按窗口给）。
     */
    float[] savedRect(String size, boolean landscape) {
        String p = rectKey(size, pointer(), landscape);
        if (!store.contains(p + "l")) return null;
        return new float[]{store.getFloat(p + "l", 0), store.getFloat(p + "t", 0), store.getFloat(p + "r", 1), store.getFloat(p + "b", 1)};
    }

    void setRect(String size, boolean landscape, float[] r) {
        String p = rectKey(size, pointer(), landscape);
        store.putFloat(p + "l", r[0]);
        store.putFloat(p + "t", r[1]);
        store.putFloat(p + "r", r[2]);
        store.putFloat(p + "b", r[3]);
    }

    /** 调整栏的"恢复默认"：只清 size 这个尺寸（显示着的）、这种指点、这个方向的那份。 */
    void resetRect(String size, boolean landscape) {
        String p = rectKey(size, pointer(), landscape);
        for (String e : new String[]{"l", "t", "r", "b"}) store.remove(p + e);
    }

    /** 三个尺寸各自在现在这种指点、这个方向下存的那份（尺寸 → {左, 上, 右, 下}；没存过的不放进去）：逐级缩换到哪一级都有它自己那份（FixedSizing）。 */
    Map<String, float[]> savedRects(boolean landscape) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String size : FixedLayout.SIZES_DESC) {
            float[] r = savedRect(size, landscape);
            if (r != null) out.put(size, r);
        }
        return out;
    }

    // ═══════════ 设置页的语言列表（第 2 批） ═══════════

    /**
     * 布局在界面语言 ui 里叫什么（设置页的列表、⋯ 菜单都用它）：语言名按清单的 locale 用 Locale 翻（Android 上是 ICU 的名字）；
     * 同一种语言在清单里有几个地区（英语的美国 / 英国、法语的法国 / 比利时 / 加拿大 / 瑞士、德语、西班牙语、葡萄牙语）才带上地区（"法语（加拿大）"，
     * 格式照 Locale.getDisplayName），只有一个地区的不带（"瑞典语""捷克语"）；首字母大写（法语、西语界面里语言名小写开头，列表里一项项单列）。
     * 有区分词时接在后面（join = strings.xml 的 kbd_name_short "%1$s · %2$s"）：小写的区分词交给 words 翻译（kbd_var_*，返回 null 就照写），
     * 别的（QZERTY、Q、F）照原样。1B 的"美式""德语"两个专门的名字不用了（英语（美国）挨着英语（英国）排，德语带上"德国"和奥地利、瑞士分开）。
     */
    static String langName(List<FixedLegends.Layout> catalog, FixedLegends.Layout l, Locale ui, Function<String, String> words, String join) {
        Locale loc = Locale.forLanguageTag(l.locale);
        String base = regionNeeded(catalog, l) ? loc.getDisplayName(ui) : Locale.forLanguageTag(loc.getLanguage()).getDisplayName(ui);
        if (base.isEmpty()) base = l.id;
        int first = base.offsetByCodePoints(0, 1);
        base = base.substring(0, first).toUpperCase(ui) + base.substring(first);
        if (l.variant.isEmpty()) return base;
        String w = l.variantWord() && words != null ? words.apply(l.variant) : null;
        return String.format(Locale.ROOT, join, base, w == null ? l.variant : w);
    }

    /** 名字里要不要带地区：清单里另有一项语言相同、地区不同（按 locale 的语言和地区比，区分词不算）。 */
    static boolean regionNeeded(List<FixedLegends.Layout> catalog, FixedLegends.Layout l) {
        if (catalog == null) return false;
        Locale a = Locale.forLanguageTag(l.locale);
        for (FixedLegends.Layout o : catalog) {
            Locale b = Locale.forLanguageTag(o.locale);
            if (b.getLanguage().equals(a.getLanguage()) && !b.getCountry().equals(a.getCountry())) return true;
        }
        return false;
    }

    /** 设置页语言列表的一项：清单的一行、界面语言里的名字、在现在的模式下能不能选（没有这种模式的键帽数据就灰掉）。 */
    static final class LangChoice {
        final FixedLegends.Layout layout;
        final String name;
        final boolean enabled;

        LangChoice(FixedLegends.Layout layout, String name, boolean enabled) {
            this.layout = layout;
            this.name = name;
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return name + (enabled ? "" : "（灰）");
        }
    }

    /**
     * 设置页的语言列表：清单里每个布局一项，按界面语言的名字排（Collator，中文按拼音、名字一样时按 id），
     * mode（现在选的模式）下没有键帽数据的灰掉（enabled 假：拉美在 Mac 模式下），不藏起来（看得见、写着原因）。
     */
    static List<LangChoice> langChoices(List<FixedLegends.Layout> catalog, String mode, Locale ui, Function<String, String> words, String join) {
        List<LangChoice> out = new ArrayList<>();
        if (catalog == null) return out;
        String m = FixedLayout.validMode(mode);
        for (FixedLegends.Layout l : catalog) out.add(new LangChoice(l, langName(catalog, l, ui, words, join), l.hasMode(m)));
        Collator c = Collator.getInstance(ui);
        out.sort((a, b) -> {
            int r = c.compare(a.name, b.name);
            return r != 0 ? r : a.layout.id.compareTo(b.layout.id);
        });
        return out;
    }

    /**
     * 布局 lang 在模式 mode 下有没有键帽数据（设置页的"模式"那一行没有的灰掉）。清单读不出来 / 清单里没有这个布局时当有：
     * 只管设置页灰不灰，拼不拼得出来另由 FixedData.ladder 看字表（拼不出就退回豆花布局）。
     */
    static boolean modeAvailable(List<FixedLegends.Layout> catalog, String lang, String mode) {
        FixedLegends.Layout l = FixedLegends.find(catalog, lang);
        return l == null || l.hasMode(FixedLayout.validMode(mode));
    }

    /** 现在选的布局在现在选的模式下有没有键帽数据（没有的话键盘页退回豆花布局，⋯ 菜单、设置页写明原因）。 */
    boolean modeAvailable() {
        return modeAvailable(catalog, lang(), mode());
    }
}
