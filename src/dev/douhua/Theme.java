package dev.douhua;

/**
 * 主界面的配色（设置 → 外观 → 主题）：键盘、触控板、按钮条、数字键盘、空中鼠标页、贴字面板都从当前主题取色（经 Style）。
 * 纯 Java、不 import android.*：十八套的值和对比度在 tools/test.sh 里离线测（ThemeTest）。设置页自己的配色不跟主题变。
 *
 * 每套只写几个"底子"（页面底色、键帽、键帽文字、强调色、触控板底色和边框，有的写了键帽边框），其余按固定规则推出来
 * （{@link Spec#build}）：灰色说明字、状态字取"离底色最近、对比度还够 4.5:1"的那个颜色，强调色当文字用时不够 4.5:1 就往亮 / 暗推。
 * 键帽分主次两种（照 Gboard / 三星键盘）：字母、数字、空格、标点用 key / text；功能键（修饰键、⌫ ↵、F 键、方向键、?123、Fn、🌐、⋯、
 * 快捷行……，哪些算见 KeyRows.functionKey）用 fnKey / fnText——键帽底色掺一点强调色、字用强调色。
 * 纯黑是 2026-10-30 以前的样子，每个槽位都照原来的常量写死（功能键和普通键一样），选它和以前一模一样（ThemeTest 逐个锁住）。
 */
final class Theme {
    /** 存进设置的名字。 */
    final String id;
    /** 配色方案自己的名字（Nord、Dracula……，各语言都不翻译）；null = 要翻译（纯黑 / 深色 / 海蓝 / 高对比 / 浅色 / 樱花 / 薄荷），设置页按 id 取 strings.xml。 */
    final String fixedName;
    /** 浅色主题（页面底色亮）：按下变暗、彩色 emoji 去色后压得更暗。 */
    final boolean light;

    /** 页面底色（主界面、键盘屏、空中鼠标页）。 */
    final int bg;
    /** 键帽 / 按钮底色；纯黑是透明（透出页面底色，和以前一样）。 */
    final int key;
    /** 键帽 / 按钮文字。 */
    final int text;
    /** 键帽 / 按钮边框（"按键边框"开着时画）。 */
    final int border;
    /** 手指按着时的键帽底色。 */
    final int pressed;
    /**
     * 功能键的键帽底色（纯黑和普通键帽一样是透明）、文字、手指按着时的底色。
     * 高亮的功能键（锁住的修饰键、换层键）不用这几个，照旧是普通键帽底色 + accent 边框 + activeText（Style.look）：
     * 功能键的字本来就是强调色，要是底色也照旧掺着，锁住和没锁只差一圈边框。
     */
    final int fnKey, fnText, fnPressed;
    /** 高亮的键帽边框（锁住的修饰键、换层键、开着的面板对应的按钮）、⋯ 菜单的边框。 */
    final int accent;
    /** 高亮的键帽文字。 */
    final int activeText;
    /** 亮一档的文字：贴字面板的输入框、调整键盘时的提示字。 */
    final int strong;
    /** 触控板底色和边框（每套都和键盘分得开：自己的底色 + 强调色的边）。 */
    final int padFill, padBorder;
    /** 触控板底部的状态字：正常 / 出问题。 */
    final int status, statusBad;
    /** 按键放大提示：底、边、字。 */
    final int previewFill, previewBorder, previewText;
    /** 调整键盘时的框和拖把手、空中鼠标页右上角的状态字。 */
    final int focus;
    /** 空中鼠标页圆钮平时的圈（跟随中用 activeText）。 */
    final int ring;
    /** 触控板角上的小按钮（含键盘屏触控板角上的 ⋯）：字、边框。 */
    final int dim, dimBorder;
    /** 贴字面板输入框里的灰色提示字。 */
    final int hint;
    /** 触控板里边缘滚动带的细线。 */
    final int edge;
    /** ⋯ 菜单的底板。 */
    final int menuFill;
    /**
     * 小红点的红帽、三键条左 / 右键上的细红线：一直是红的（这是它的身份），从 ThinkPad 红往亮（深色主题）/ 往暗（浅色主题）推到
     * 对页面底色和触控板底色都 ≥ 3:1（非文字的界线，WCAG 1.4.11），见 {@link #stickRed}。
     */
    final int stick;
    /** 键帽边框、触控板边框多粗（dp）。 */
    final float borderDp;
    /** 调整键盘时压在键盘上的那层（{@link #shade()}）。 */
    private final int shade;

    private Theme(Spec s) {
        id = s.id;
        fixedName = s.fixedName;
        bg = s.bg;
        key = s.key;
        text = s.text;
        accent = s.accent;
        padFill = s.padFill;
        padBorder = s.padBorder;
        light = luminance(bg) > 0.4;
        int keyFill = over(key, bg);
        // 键帽边框：离键帽底色最近、对键帽和页面都够 3:1 的那个（非文字的界线，WCAG 1.4.11）。
        // 原来固定往文字色混两成，浅色主题只有 1.3–1.5:1、和用户嫌"难以分辨"的纯黑一样淡
        border = or(s.border, dimmest(keyFill, text, 3, keyFill, bg));
        pressed = or(s.pressed, blend(keyFill, text, 0.15f));
        // 功能键：键帽底色掺一点强调色（和普通键帽、页面底色都看得出差别为止，见 fnTint），字用强调色，按下照普通键的推法
        fnKey = or(s.fnKey, fnTint(keyFill, accent, bg));
        int fnFill = over(fnKey, bg);
        fnPressed = or(s.fnPressed, blend(fnFill, text, 0.15f));
        fnText = or(s.fnText, reach(reach(accent, fnFill, TEXT_RATIO), fnPressed, 3));
        // 高亮的键（锁住的 Shift）被按着时也得看清：对按下的底色再保证 3:1。
        // 高亮的功能键（锁住的修饰键、换层键）照旧画在普通键帽的底色上（见 Style.look），不在功能键的底色上
        activeText = or(s.activeText, reach(reach(accent, keyFill, TEXT_RATIO), pressed, 3));
        strong = or(s.strong, text);
        status = or(s.status, dimmest(padFill, text, TEXT_RATIO, padFill));
        statusBad = or(s.statusBad, reach(s.bad, padFill, TEXT_RATIO));
        previewFill = or(s.previewFill, blend(keyFill, text, 0.12f));
        previewBorder = or(s.previewBorder, accent);
        // 放大提示的字：亮文字不够 4.5:1 就再推亮 / 推暗（Solarized Dark、Everforest 的正文本来就不亮，盖在掺了字色的提示底上不够）
        previewText = or(s.previewText, reach(strong, previewFill, TEXT_RATIO));
        focus = or(s.focus, reach(accent, bg, TEXT_RATIO));
        ring = or(s.ring, reach(blend(keyFill, text, 0.45f), keyFill, 3));
        dim = or(s.dim, dimmest(padFill, text, TEXT_RATIO, padFill, bg));
        dimBorder = or(s.dimBorder, blend(padFill, padBorder, 0.45f));
        hint = or(s.hint, dim);
        edge = or(s.edge, blend(padFill, padBorder, 0.3f));
        menuFill = or(s.menuFill, blend(bg, accent, 0.08f));
        stick = stickRed(bg, padFill);
        borderDp = s.borderDp;
        shade = shadeFor(bg, keyFill, text, fnFill, fnText, strong);
    }

    /** ThinkPad 小红点的红（凭记忆的近似值，不是官方色号）。 */
    static final int THINKPAD_RED = 0xFFE2231A;

    /** 小红点的红：ThinkPad 红往远离页面底色的方向（暗底往白、亮底往黑）推，推到对 bg、padFill 都 ≥ 3:1 为止。 */
    static int stickRed(int bg, int padFill) {
        int far = luminance(bg) > 0.4 ? 0xFF000000 : 0xFFFFFFFF;
        for (int i = 0; i <= 50; i++) {
            int d = blend(THINKPAD_RED, far, i / 50f);
            if (contrast(d, bg) >= 3 && contrast(d, padFill) >= 3) return d;
        }
        return far;
    }

    /** 正文对比度的目标（WCAG AA）。 */
    static final double TEXT_RATIO = 4.5;
    /** 功能键底色至少掺多少强调色（"一点点颜色"）。 */
    static final float FN_TINT = 0.15f;
    /** 功能键底色和普通键帽、页面底色至少差多少（CIE76 色差；并排看一眼分得出，2.3 左右是刚能察觉）。 */
    static final double FN_DELTA_E = 8;

    /** 先深色、后浅色（设置页按 {@link #light} 分两组，组里照这个顺序排）。 */
    static final Theme[] ALL = {
            // ① 纯黑：2026-10-30 以前的 Style 常量，一个不改（外屏一直亮着，黑底最省电、最不烧屏）；功能键和普通键一样
            new Spec("black", null).base(0xFF000000, 0x00000000, 0xFF8A8A8A, 0xFF707070, 0xFF000000, 0xFF2A2A2A)
                    .border(0xFF2A2A2A).pressed(0xFF1C1C1C).fn(0x00000000, 0xFF8A8A8A, 0xFF1C1C1C)
                    .activeText(0xFFDDDDDD).strong(0xFFDDDDDD)
                    .status(0xFF4A4A4A, 0xFF8A5A30).preview(0xFF2E2E2E, 0xFF8A8A8A, 0xFFF2F2F2).focus(0xFF8AB4F8)
                    .ring(0xFF707070).dim(0xFF5A5A5A, 0xFF1E1E1E).hint(0xFF555555).edge(0xFF1E1E1E).menuFill(0xFF121212)
                    .build(),
            // ② 深色（Gboard 深色）；触控板边框用 Google 蓝 400（原来的 #5F6368 是灰的，对触控板底色只有 2.3:1，和键盘分不开）
            new Spec("dark", null).base(0xFF202124, 0xFF3C4043, 0xFFE8EAED, 0xFF8AB4F8, 0xFF2A2D33, 0xFF669DF6)
                    .bad(0xFFF28B82).build(),
            // ③ Nord；键帽边框不再写死 nord3 #4C566A（对键帽只有 1.36:1），按规则推
            new Spec("nord", "Nord").base(0xFF2E3440, 0xFF3B4252, 0xFFECEFF4, 0xFF88C0D0, 0xFF353C4A, 0xFF81A1C1)
                    .bad(0xFFD08770).build(),
            // ④ Dracula
            new Spec("dracula", "Dracula").base(0xFF282A36, 0xFF44475A, 0xFFF8F8F2, 0xFFFF79C6, 0xFF21222C, 0xFFBD93F9)
                    .bad(0xFFFFB86C).build(),
            // ⑤ Catppuccin Mocha
            new Spec("catppuccin", "Catppuccin").base(0xFF1E1E2E, 0xFF313244, 0xFFCDD6F4, 0xFFF5C2E7, 0xFF181825, 0xFFCBA6F7)
                    .bad(0xFFFAB387).build(),
            // ⑥ Tokyo Night
            new Spec("tokyo", "Tokyo Night").base(0xFF1A1B26, 0xFF24283B, 0xFFC0CAF5, 0xFFBB9AF7, 0xFF16161E, 0xFF7AA2F7)
                    .bad(0xFFFF9E64).build(),
            // ⑦ Gruvbox Dark
            new Spec("gruvbox", "Gruvbox").base(0xFF282828, 0xFF3C3836, 0xFFEBDBB2, 0xFFFE8019, 0xFF1D2021, 0xFFFABD2F)
                    .bad(0xFFFB4934).build(),
            // ⑧ Everforest（Dark Medium）
            new Spec("everforest", "Everforest").base(0xFF2D353B, 0xFF3D484D, 0xFFD3C6AA, 0xFFA7C080, 0xFF343F44, 0xFF83C092)
                    .bad(0xFFE67E80).build(),
            // ⑨ Solarized Dark
            new Spec("solarized_dark", "Solarized Dark").base(0xFF002B36, 0xFF073642, 0xFF93A1A1, 0xFF2AA198, 0xFF00212B, 0xFF268BD2)
                    .bad(0xFFDC322F).build(),
            // ⑩ Rosé Pine
            new Spec("rosepine", "Rosé Pine").base(0xFF191724, 0xFF26233A, 0xFFE0DEF4, 0xFFEBBCBA, 0xFF1F1D2E, 0xFFF6C177)
                    .bad(0xFFEB6F92).build(),
            // ⑪ Monokai
            new Spec("monokai", "Monokai").base(0xFF272822, 0xFF3E3D32, 0xFFF8F8F2, 0xFFA6E22E, 0xFF1E1F1C, 0xFFF92672)
                    .bad(0xFFFD971F).build(),
            // ⑫ 海蓝
            new Spec("ocean", null).base(0xFF0B2A42, 0xFF133F5E, 0xFFE3F2FD, 0xFF4FC3F7, 0xFF08213A, 0xFF26C6DA)
                    .bad(0xFFFF8A65).build(),
            // ⑬ 高对比（照三星高对比键盘）：黑底黄字黄边、边框粗一点；触控板白边。按下的底色比推出来的（#262000，几乎看不出）亮。
            // 状态字、角上小按钮、输入框提示字、圆钮的圈也都是亮黄（推出来的是"刚好 4.5:1"的暗土黄，高对比里不该有暗字）；
            // 角上小按钮的边框照推出来的灰（#737373）。功能键照规则推：字是强调色白，底是掺了白的深灰
            new Spec("contrast", null).base(0xFF000000, 0xFF000000, 0xFFFFD600, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF)
                    .border(0xFFFFD600).pressed(0xFF4D4000).status(0xFFFFD600, 0xFFFF8A80).dim(0xFFFFD600, 0xFF737373)
                    .hint(0xFFFFD600).ring(0xFFFFD600).borderDp(2.5f).build(),
            // ── 浅色 ──
            // ⑭ 浅色（Gboard 浅色）；触控板边框比 Google 蓝 500 #4285F4 暗一点（原值对页面 #E8EAED 只有 2.96:1）
            new Spec("glight", null).base(0xFFE8EAED, 0xFFFFFFFF, 0xFF202124, 0xFF1A73E8, 0xFFF1F3F4, 0xFF4182EF)
                    .bad(0xFFD93025).build(),
            // ⑮ Solarized Light（id 还叫 light：以前它叫"浅色"，存下的设置照用）；键帽文字比 Solarized 的 #586E75 暗一点
            // （原值在键帽 #EEE8D5 上只有 4.4:1）；键帽边框用 Solarized 的 base00 #657B83：浅底上键和键之间要靠它分（键帽对页面只有 1.14:1）
            new Spec("light", "Solarized Light").base(0xFFFDF6E3, 0xFFEEE8D5, 0xFF4A6068, 0xFFCB4B16, 0xFFF5EEDB, 0xFF268BD2)
                    .border(0xFF657B83).bad(0xFFDC322F).build(),
            // ⑯ Catppuccin Latte
            new Spec("latte", "Catppuccin Latte").base(0xFFDCE0E8, 0xFFEFF1F5, 0xFF4C4F69, 0xFF8839EF, 0xFFE6E9EF, 0xFF1E66F5)
                    .bad(0xFFD20F39).build(),
            // ⑰ 樱花
            new Spec("sakura", null).base(0xFFFCE4EC, 0xFFFFFFFF, 0xFF4A2633, 0xFFC2185B, 0xFFF8D0DE, 0xFFAD1457)
                    .bad(0xFFC62828).build(),
            // ⑱ 薄荷
            new Spec("mint", null).base(0xFFDDF1EA, 0xFFFFFFFF, 0xFF17372D, 0xFF00796B, 0xFFCBE9DE, 0xFF00897B)
                    .bad(0xFFC62828).build(),
    };

    static final Theme DEFAULT = ALL[0];

    /** 按存的名字找；没有（旧版本、存坏了）就用纯黑。 */
    static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return DEFAULT;
    }

    /** 键帽实际的底色（纯黑的键帽是透明的，透出页面底色）。 */
    int keyFill() {
        return over(key, bg);
    }

    /** 功能键实际的底色（纯黑透明）。 */
    int fnFill() {
        return over(fnKey, bg);
    }

    /** 功能键的样子要不要和普通键分开（纯黑不分，键帽、字、按下都一样）。 */
    boolean fnDistinct() {
        return fnKey != key || fnText != text || fnPressed != pressed;
    }

    /**
     * 功能键的底色：键帽底色往强调色掺，从 {@link #FN_TINT} 起，加到和普通键帽、页面底色的色差都 ≥ {@link #FN_DELTA_E} 为止（最多四成）。
     * 樱花、薄荷这种页面本身带着强调色色调的浅色主题，掺一成半时功能键和页面几乎一样（色差约 3），"按键边框"关着就只剩字。
     */
    private static int fnTint(int keyFill, int accent, int bg) {
        int c = blend(keyFill, accent, FN_TINT);
        for (int i = Math.round(FN_TINT * 100); i <= 40; i++) {
            c = blend(keyFill, accent, i / 100f);
            if (deltaE(c, keyFill) >= FN_DELTA_E && deltaE(c, bg) >= FN_DELTA_E) break;
        }
        return c;
    }

    /** 调整键盘时压在键盘上的那层：页面底色、约七成不透明（纯黑就是以前的 0xB0000000；浅色主题是一层发白的雾，不是压黑）。 */
    int shade() {
        return shade;
    }

    /**
     * 雾层的不透明度：从 0xB0 起，加到提示字（strong）对"雾盖住的键帽底"和"雾下透出的键帽字"都够 4.5:1 为止，功能键的底和字也算
     * （调整模式不切回字母层：在 Fn 层点"调整大小"，提示字压着的全是 F1–F12、Home 这类功能键的字）。
     * 提示字正好压在中间几行键的字母上，浅色主题里两者同一色调，0xB0 时只有 3.9:1、字形互相干扰。纯黑在 0xB0 就够（10:1）。
     */
    private static int shadeFor(int bg, int keyFill, int text, int fnFill, int fnText, int strong) {
        int a = 0xB0;
        for (; a < 0xF0; a += 8) {
            int s = (a << 24) | (bg & 0xFFFFFF);
            if (contrast(strong, over(s, keyFill)) >= TEXT_RATIO && contrast(strong, over(s, text)) >= TEXT_RATIO
                    && contrast(strong, over(s, fnFill)) >= TEXT_RATIO && contrast(strong, over(s, fnText)) >= TEXT_RATIO) break;
        }
        return (a << 24) | (bg & 0xFFFFFF);
    }

    /**
     * 彩色 emoji（🌐）去色后怎么上色：先去掉饱和度得到灰度 v（0–255），每个通道再算 v × scale + offset。
     * 返回 {scaleR, scaleG, scaleB, offsetR, offsetG, offsetB}，offset 的单位是 0–255（和 ColorMatrix 的平移一样）。
     * <ul>
     * 🌐 是功能键，下面的"键帽 / 文字"都是功能键的（fnKey / fnText）。
     * <li>纯黑（键帽透明）：整体乘 0.75 的灰，和以前一样；浅色：按文字色调乘 0.55，在浅键帽上暗下来、看得清。</li>
     * <li>其它深色主题：黑 → 键帽底色、白 → 键帽文字色，线性映射过去。原来也是乘 0.75，可这几套的键帽本身是中灰，
     *     压暗后的地球和键帽一样亮，经线完全看不见；高对比的功能键字是白的，映射就是黑 → 深灰底、白 → 白。
     *     功能键的字是强调色、不够亮时，白那头再往白推一点（中灰对键帽够 3:1）。</li>
     * </ul>
     */
    float[] emojiMap() {
        // 🌐 是功能键：按功能键的字和底色映射（纯黑两者和普通键一样）
        int r = (fnText >> 16) & 0xFF, g = (fnText >> 8) & 0xFF, b = fnText & 0xFF;
        if (light || (fnKey >>> 24) == 0) {
            float k = light ? 0.55f : 0.75f;
            float max = Math.max(1, Math.max(r, Math.max(g, b)));
            return new float[]{k * r / max, k * g / max, k * b / max, 0, 0, 0};
        }
        int f = fnFill();
        // 功能键的字是强调色，没有正文那么亮：中灰（地球的大块海陆）落在键帽和它的正中间，只有 2.3:1 左右。
        // 白那头从字色往白推，推到中灰对键帽够 3:1 为止（色调照旧跟着字）
        int top = fnText;
        for (int i = 0; i <= 50; i++) {
            top = blend(fnText, 0xFFFFFFFF, i / 50f);
            if (contrast(blend(f, top, 0.5f), f) >= 3) break;
        }
        r = (top >> 16) & 0xFF;
        g = (top >> 8) & 0xFF;
        b = top & 0xFF;
        int fr = (f >> 16) & 0xFF, fg = (f >> 8) & 0xFF, fb = f & 0xFF;
        return new float[]{(r - fr) / 255f, (g - fg) / 255f, (b - fb) / 255f, fr, fg, fb};
    }

    // ═══════════ 颜色计算（WCAG 2 相对亮度和对比度） ═══════════

    static double luminance(int c) {
        return 0.2126 * channel((c >> 16) & 0xFF) + 0.7152 * channel((c >> 8) & 0xFF) + 0.0722 * channel(c & 0xFF);
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** 两个不透明颜色的对比度（1–21）。 */
    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** 两个不透明颜色的 CIE76 色差（CIELAB 里的直线距离，D65 白点）：看着差多少，比对比度多算了色相。 */
    static double deltaE(int a, int b) {
        double[] p = lab(a), q = lab(b);
        double dl = p[0] - q[0], da = p[1] - q[1], db = p[2] - q[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double[] lab(int c) {
        double r = channel((c >> 16) & 0xFF), g = channel((c >> 8) & 0xFF), b = channel(c & 0xFF);
        double x = labF((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047);
        double y = labF(0.2126 * r + 0.7152 * g + 0.0722 * b);
        double z = labF((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883);
        return new double[]{116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double labF(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /** a 到 b 之间按 t 取（0 = a，1 = b），结果不透明。 */
    static int blend(int a, int b, float t) {
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int ca = (a >> shift) & 0xFF, cb = (b >> shift) & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    /** 带透明度的 fg 盖在不透明的 bg 上，看起来是什么颜色。 */
    static int over(int fg, int bg) {
        float a = ((fg >>> 24) & 0xFF) / 255f;
        return blend(bg, fg, a);
    }

    /** c 对 against 的对比度不够 ratio 就往远离 against 的方向（暗底往白、亮底往黑）推，推到够为止。 */
    static int reach(int c, int against, double ratio) {
        int far = luminance(against) > 0.4 ? 0xFF000000 : 0xFFFFFFFF;
        for (int i = 0; i <= 50; i++) {
            int d = blend(c, far, i / 50f);
            if (contrast(d, against) >= ratio) return d;
        }
        return far;
    }

    /** from 到 to 之间最靠近 from、对 against 里每个颜色的对比度都够 ratio 的那个（灰色说明字：够看清就行，别比正文还亮）。 */
    static int dimmest(int from, int to, double ratio, int... against) {
        for (int i = 0; i <= 100; i++) {
            int d = blend(from, to, i / 100f);
            boolean ok = true;
            for (int a : against) ok &= contrast(d, a) >= ratio;
            if (ok) return d;
        }
        return to;
    }

    private static int or(Integer set, int derived) {
        return set != null ? set : derived;
    }

    /** 一套主题的写法：base 必填，其余不写就按规则推（见 Theme 构造）。 */
    private static final class Spec {
        final String id;
        final String fixedName;
        int bg, key, text, accent, padFill, padBorder;
        int bad = 0xFFE07A5F;
        float borderDp = 1.5f;
        Integer border, pressed, fnKey, fnText, fnPressed, activeText, strong, status, statusBad, previewFill, previewBorder, previewText,
                focus, ring, dim, dimBorder, hint, edge, menuFill;

        Spec(String id, String fixedName) {
            this.id = id;
            this.fixedName = fixedName;
        }

        Spec base(int bg, int key, int text, int accent, int padFill, int padBorder) {
            this.bg = bg;
            this.key = key;
            this.text = text;
            this.accent = accent;
            this.padFill = padFill;
            this.padBorder = padBorder;
            return this;
        }

        Spec border(int c) {
            border = c;
            return this;
        }

        Spec pressed(int c) {
            pressed = c;
            return this;
        }

        /** 功能键的键帽、文字、按下（纯黑照普通键写死）。 */
        Spec fn(int key, int text, int pressed) {
            fnKey = key;
            fnText = text;
            fnPressed = pressed;
            return this;
        }

        Spec activeText(int c) {
            activeText = c;
            return this;
        }

        Spec strong(int c) {
            strong = c;
            return this;
        }

        /** 出问题时状态字的底子（不够 4.5:1 会被推亮 / 推暗）。 */
        Spec bad(int c) {
            bad = c;
            return this;
        }

        Spec status(int ok, int bad) {
            status = ok;
            statusBad = bad;
            return this;
        }

        Spec preview(int fill, int border, int text) {
            previewFill = fill;
            previewBorder = border;
            previewText = text;
            return this;
        }

        Spec focus(int c) {
            focus = c;
            return this;
        }

        Spec ring(int c) {
            ring = c;
            return this;
        }

        Spec dim(int text, int border) {
            dim = text;
            dimBorder = border;
            return this;
        }

        Spec hint(int c) {
            hint = c;
            return this;
        }

        Spec edge(int c) {
            edge = c;
            return this;
        }

        Spec menuFill(int c) {
            menuFill = c;
            return this;
        }

        Spec borderDp(float dp) {
            borderDp = dp;
            return this;
        }

        Theme build() {
            return new Theme(this);
        }
    }
}
