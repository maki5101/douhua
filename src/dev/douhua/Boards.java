package dev.douhua;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   kbx_size               tkl（默认）/ full / laptop（笔记本，2026-11-01）/ 75 / 65 / apple（第 3 批：75%、65%、苹果紧凑）：逐级缩的起点
 *                          （放不下就往小换，FixedLayout.ladder），存的值不跟着缩
 *   kbx_mode               win（默认，连 Linux、安卓也用它）/ mac（连 iPad 也用它）
 *   kbx_pointer            键盘页的指点（KeyboardPlan.POINTER_*），和豆花布局的 kb_pointer 分开记：在实体键盘上换成小红点不会把豆花布局也换了。
 *                          存着小红点、显示着的那一级不给小红点（FixedLayout.stickAllowed：TKL、全尺寸、Mac 模式，2026-11-01 晚）时照触控板
 *                          （{@link #pointer(String)}），存的值不动
 *   kbx_<尺寸>_<指点>_<land|port>_<l|t|r|b>   大小位置（键盘区的 0–1，没有 = 默认）：按尺寸 × 指点 × 横竖屏各一份
 *                          （换语言、形状、模式不挪；六种尺寸来回换各回各的；有触控板时避让、没有时不避让，同豆花布局按指点分开记）。
 *                          尺寸是**显示着的**那个（逐级缩成笔记本时取、存的都是 kbx_laptop_…），所以存取都显式给尺寸，不按选的尺寸
 * 只有一块实体键盘（用户 2026-11-01 要的是"选哪块实体键盘：语言、形状、尺寸、Windows / Mac"，⋯ 菜单里豆花 / 实体两块切换）；
 * 方案里的"键盘列表、按主机记住、主机系统、互换开关"都不做（用户："主机怎么读那是主机的事，不是键盘设计者应该考虑的问题"）。
 * 切换只改 kbd_board；换语言等只改 kbx_ 的那几个。"恢复默认"（Prefs.resetAll）照旧整个清空，实体键盘的设置一起清掉。
 *
 * 第 2 批（2026-11-01）多了设置页语言列表的规则（{@link #langName}、{@link #langChoices}、{@link #modeAvailable}）：布局叫什么、按名字排、
 * 某布局在某种模式下没有键帽数据（拉美没有 Mac 的）时灰掉。灰掉只管设置页能不能选；存着的组合要是正好没数据（以后清单变了之类），
 * 键盘页照 1B 的规则退回豆花布局（FixedData.ladder 是空的），存的值不动。
 *
 * 协议 24（2026-11-02）：F1–F12 的第二功能、F 行翻转，Windows / Mac 两种模式各一套：
 *   kbx_fkey_<win|mac>_<1..12>  第 n 个 F 键配的功能 id（FKeyFunctions；没存过照这种模式的默认预设）
 *   kbx_frow_flip_<win|mac>     F 行平时按出第二功能（1 / 0；没存过 Windows 0、Mac 1）
 * 第 3 批（2026-11-01）：苹果紧凑只有 Mac 模式、只有 ANSI / ISO（FixedLayout.available）。同"没数据的模式"那条：设置页把不可用的那一边灰掉
 * （{@link #sizeAvailable}：选 Windows 模式 / ABNT2 形状时尺寸里的苹果紧凑、选着苹果紧凑时模式里的 Windows、形状里的 ABNT2、语言列表里只有 ABNT2 的布局），
 * 存着的组合正好不可用时键盘页退回豆花布局（FixedData.ladder 是空的），⋯ 菜单、设置页写原因，存的值不动。
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

    /**
     * 实体键盘是哪一块：布局 id、形状、尺寸、模式（都已经按清单核过，能直接拿去拼）；协议 24 起另带这种模式下 F1–F12 配的第二功能
     * （{@link #fkeys(String)}，下标 0 = F1，拼进 F 键报的 id：FixedLayout.forEntry），设置页改了第二功能 MainActivity.applyBoards 比得出来、重拼。
     * toString 只写前四样（"us/ansi/tkl/win"）。
     */
    static final class Spec {
        final String lang, shape, size, mode;
        final String[] fkeys;

        Spec(String lang, String shape, String size, String mode) {
            this(lang, shape, size, mode, null);
        }

        Spec(String lang, String shape, String size, String mode, String[] fkeys) {
            this.lang = lang;
            this.shape = shape;
            this.size = size;
            this.mode = mode;
            this.fkeys = fkeys == null ? null : fkeys.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Spec)) return false;
            Spec s = (Spec) o;
            return lang.equals(s.lang) && shape.equals(s.shape) && size.equals(s.size) && mode.equals(s.mode) && Arrays.equals(fkeys, s.fkeys);
        }

        @Override
        public int hashCode() {
            return (lang + "/" + shape + "/" + size + "/" + mode).hashCode() * 31 + Arrays.hashCode(fkeys);
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
        String mode = mode();
        return new Spec(lang(), shape(), size(), mode, fkeys(mode));
    }

    // ═══════════ F1–F12 的第二功能、F 行翻转（协议 24，Windows / Mac 两种模式各一套） ═══════════

    /** F 行翻转（"平时按出第二功能"）的键名：kbx_frow_flip_<win|mac>；存 1 / 0。 */
    static String frowFlipKey(String mode) {
        return "kbx_frow_flip_" + FixedLayout.validMode(mode);
    }

    /** 第 n 个 F 键（1–12）的第二功能的键名：kbx_fkey_<win|mac>_<n>；存功能的 id（FKeyFunctions）。 */
    static String fkeyKey(String mode, int n) {
        return "kbx_fkey_" + FixedLayout.validMode(mode) + "_" + n;
    }

    /**
     * 这种模式下 F 行平时按出第二功能吗（设置页"平时按出"和键盘上 Fn+Esc 是同一份；Fn+Esc 翻了由 App 照服务 / 本地分流报的 Proto.FROW_FLIP 存回来）。
     * 没存过照 FKeyFunctions.defaultFrowFlip：Windows 模式平时 F1–F12，Mac 模式平时第二功能（用户 2026-11-02 定的，照 Keychron）。
     */
    boolean frowFlip(String mode) {
        return store.getInt(frowFlipKey(mode), FKeyFunctions.defaultFrowFlip(FixedLayout.validMode(mode)) ? 1 : 0) == 1;
    }

    /** 现在这种模式的 F 行翻转（CMD_CONFIG 发的就是它）。 */
    boolean frowFlip() {
        return frowFlip(mode());
    }

    void setFrowFlip(String mode, boolean flip) {
        store.putInt(frowFlipKey(mode), flip ? 1 : 0);
    }

    /** 第 n 个 F 键（1–12）在这种模式下配的功能 id；没存过照这种模式的默认预设（Windows ThinkPad 那套、Mac 苹果那套）。 */
    String fkey(String mode, int n) {
        String m = FixedLayout.validMode(mode);
        String def = FKeyFunctions.preset(FKeyFunctions.defaultPreset(m))[n - 1];
        return store.getString(fkeyKey(m, n), def);
    }

    /** 这种模式下 12 个 F 键配的功能 id（下标 0 = F1）。 */
    String[] fkeys(String mode) {
        String[] ids = new String[12];
        for (int n = 1; n <= 12; n++) ids[n - 1] = fkey(mode, n);
        return ids;
    }

    void setFkey(String mode, int n, String id) {
        store.putString(fkeyKey(mode, n), id);
    }

    /** 整套换成一个预设（FKeyFunctions.PRESET_*）：12 个都写。 */
    void applyFkeyPreset(String mode, String preset) {
        String[] ids = FKeyFunctions.preset(preset);
        for (int n = 1; n <= 12; n++) setFkey(mode, n, ids[n - 1]);
    }

    /**
     * 这种模式现在的 12 个对得上哪个预设（FKeyFunctions.PRESET_*；逐个改过、哪个都对不上是 PRESET_CUSTOM）：设置页"预设"那一行照它写。
     * 不另存（方案写的 kbx_fkey_preset_<模式> 没要）：按 12 个现算，存两份会对不上。
     */
    String fkeyPreset(String mode) {
        return FKeyFunctions.presetOf(fkeys(mode));
    }

    // ═══════════ 指点、大小位置（实体键盘自己的一份） ═══════════

    /** 存的指点（KeyboardPlan.POINTER_*；显示着的那一级实际用的见 {@link #pointer(String)}）。 */
    int pointer() {
        return KeyboardPlan.validPointer(store.getInt(KEY_POINTER, KeyboardPlan.POINTER_PAD));
    }

    /**
     * 显示着 size 这一级（逐级缩）时实际用的指点：存的是小红点、这一级 / 这种模式不给小红点（FixedLayout.stickAllowed）时照触控板，存的值不动
     * （同 KeyboardPlan.fixedPointer；大小位置也照它存取，用的是触控板那份）。
     */
    int pointer(String size) {
        int p = pointer();
        return p == KeyboardPlan.POINTER_STICK && !FixedLayout.stickAllowed(size, mode()) ? KeyboardPlan.POINTER_PAD : p;
    }

    /** ⋯ 菜单"指点"点一下：显示着 size 这一级时从实际用的那个往下轮（不给小红点时跳过它：触控板 ↔ 关）。返回新的，不存（调用方 setPointer）。 */
    int nextPointer(String size) {
        return KeyboardPlan.nextPointer(pointer(size), FixedLayout.stickAllowed(size, mode()));
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
     * 没存过返回 null（KeyboardPlan.fixedDefaultRect 按窗口给）。指点按这一级实际用的那个（{@link #pointer(String)}：存着小红点、这一级不给时是触控板那份）。
     */
    float[] savedRect(String size, boolean landscape) {
        String p = rectKey(size, pointer(size), landscape);
        if (!store.contains(p + "l")) return null;
        return new float[]{store.getFloat(p + "l", 0), store.getFloat(p + "t", 0), store.getFloat(p + "r", 1), store.getFloat(p + "b", 1)};
    }

    void setRect(String size, boolean landscape, float[] r) {
        String p = rectKey(size, pointer(size), landscape);
        store.putFloat(p + "l", r[0]);
        store.putFloat(p + "t", r[1]);
        store.putFloat(p + "r", r[2]);
        store.putFloat(p + "b", r[3]);
    }

    /** 调整栏的"恢复默认"：只清 size 这个尺寸（显示着的）、这种指点、这个方向的那份。 */
    void resetRect(String size, boolean landscape) {
        String p = rectKey(size, pointer(size), landscape);
        for (String e : new String[]{"l", "t", "r", "b"}) store.remove(p + e);
    }

    /** 每个尺寸（六种，FixedLayout.ALL_SIZES）在现在这种指点、这个方向下存的那份（尺寸 → {左, 上, 右, 下}；没存过的不放进去）：逐级缩换到哪一级都有它自己那份（FixedSizing）。 */
    Map<String, float[]> savedRects(boolean landscape) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String size : FixedLayout.ALL_SIZES) {
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

    /**
     * 设置页语言列表的一项：清单的一行、界面语言里的名字、在现在的模式和尺寸下能不能选（enabled）。灰掉的原因：modeOk 假 = 没有这种模式的键帽数据；
     * modeOk 真而 enabled 假 = 现在选的尺寸配不上这个布局的形状（第 3 批：苹果紧凑没有 ABNT2，巴西只有 ABNT2）。
     */
    static final class LangChoice {
        final FixedLegends.Layout layout;
        final String name;
        final boolean enabled, modeOk;

        LangChoice(FixedLegends.Layout layout, String name, boolean enabled) {
            this(layout, name, enabled, enabled);
        }

        LangChoice(FixedLegends.Layout layout, String name, boolean enabled, boolean modeOk) {
            this.layout = layout;
            this.name = name;
            this.enabled = enabled;
            this.modeOk = modeOk;
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
        return langChoices(catalog, mode, null, ui, words, join);
    }

    /**
     * 同上，另看现在选的尺寸 size（null = 不看）：这个布局能选的形状没有一种配得上这个尺寸的也灰掉（第 3 批：苹果紧凑只有 ANSI / ISO，巴西只有 ABNT2），
     * 原因看 {@link LangChoice#modeOk}。
     */
    static List<LangChoice> langChoices(List<FixedLegends.Layout> catalog, String mode, String size, Locale ui, Function<String, String> words,
                                        String join) {
        List<LangChoice> out = new ArrayList<>();
        if (catalog == null) return out;
        String m = FixedLayout.validMode(mode);
        for (FixedLegends.Layout l : catalog) {
            boolean modeOk = l.hasMode(m);
            boolean shapeOk = size == null;
            // 只看形状配不配得上（按 Mac 模式问：模式那一边由"模式"那一行灰掉，苹果紧凑选着时 Windows 选不了）
            if (!shapeOk) for (String sh : l.shapes) shapeOk |= FixedLayout.available(sh, size, FixedLayout.MAC);
            out.add(new LangChoice(l, langName(catalog, l, ui, words, join), modeOk && shapeOk, modeOk));
        }
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

    // ═══════════ 尺寸配不配得上（第 3 批） ═══════════

    /**
     * 尺寸 size 配不配得上形状 shape、模式 mode（FixedLayout.available：苹果紧凑只有 Mac 模式、只有 ANSI / ISO）。设置页按它灰掉
     * 尺寸里的苹果紧凑（选着 Windows / ABNT2 时）、模式里的 Windows 和形状里的 ABNT2（选着苹果紧凑时）。
     */
    static boolean sizeAvailable(String size, String shape, String mode) {
        return FixedLayout.available(shape, size, mode);
    }

    /** 现在选的尺寸配不配得上现在的形状（这个布局读出来的那种）、模式；配不上键盘页退回豆花布局，⋯ 菜单、设置页写明原因，存的值不动。 */
    boolean sizeAvailable() {
        return sizeAvailable(size(), shape(), mode());
    }
}
