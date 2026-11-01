package dev.douhua;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Theme 的离线测试：十八套齐全、先深色后浅色，纯黑和 2026-10-30 以前的 Style 常量一模一样（功能键和普通键一样）、
 * 其余每套文字对底色的对比度够（含功能键的字）、触控板和键盘分得开、功能键和普通键帽分得开；
 * 每套（含纯黑）的 ⋯（键帽形状的强调色底 + 反差字、不带框的强调色字）都看得清、在键堆里显眼。
 * 最后打印每套的取值和对比度（交差、写 CLAUDE.md 用）。
 */
public final class ThemeTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        list();
        blackUnchanged();
        for (Theme t : Theme.ALL) {
            if (t != Theme.DEFAULT) readable(t);
            separable(t);
            stickRed(t);
            menu(t);
        }
        helpers();
        if (args.length > 0 && args[0].equals("print")) print();
        System.err.println("主题：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    /** 设置页的顺序：先深色组、后浅色组（组里照这个顺序）。 */
    static final String[] ORDER = {"black", "dark", "nord", "dracula", "catppuccin", "tokyo", "gruvbox", "everforest",
            "solarized_dark", "rosepine", "monokai", "ocean", "contrast", "glight", "light", "latte", "sakura", "mint"};
    /** 要翻译的（strings.xml 里有）：纯黑、深色、海蓝、高对比、浅色、樱花、薄荷；其余用配色方案自己的名字。 */
    static final Set<String> TRANSLATED = new HashSet<>(java.util.Arrays.asList(
            "black", "dark", "ocean", "contrast", "glight", "sakura", "mint"));

    static void list() {
        check(Theme.ALL.length == 18, "十八套");
        check(Theme.DEFAULT.id.equals("black"), "默认是纯黑");
        Set<String> ids = new HashSet<>();
        for (Theme t : Theme.ALL) check(ids.add(t.id), "名字不重复：" + t.id);
        for (int i = 0; i < ORDER.length && i < Theme.ALL.length; i++) {
            check(Theme.ALL[i].id.equals(ORDER[i]), "第 " + (i + 1) + " 套是 " + ORDER[i] + "（实际 " + Theme.ALL[i].id + "）");
            check(Theme.ALL[i].light == (i >= 13), ORDER[i] + "：前十三套是深色、后五套是浅色");
        }
        check(Theme.byId("nord").id.equals("nord"), "按名字找得到");
        check(Theme.byId("没有这套") == Theme.DEFAULT && Theme.byId("") == Theme.DEFAULT, "找不到的退回纯黑");
        // 以前叫"浅色"的 Solarized Light：id 不变（存下的设置照用），名字固定成 Solarized Light，"浅色"给了 Gboard 浅色
        check(Theme.byId("light").id.equals("light") && "Solarized Light".equals(Theme.byId("light").fixedName),
                "id light 还在，叫 Solarized Light");
        for (Theme t : Theme.ALL) {
            check((t.fixedName == null) == TRANSLATED.contains(t.id), t.id + "：" + (TRANSLATED.contains(t.id)
                    ? "要翻译（fixedName 为 null）" : "用配色方案自己的名字"));
        }
        for (Theme t : Theme.ALL) {
            for (int c : slots(t)) check((c >>> 24) == 0xFF, t.id + "：除了纯黑的键帽，槽位都不透明 " + hex(c));
        }
    }

    /** 纯黑 = 以前的 Style / TrackpadView / KeyboardScreen / MainActivity 里写死的颜色。 */
    static void blackUnchanged() {
        Theme t = Theme.DEFAULT;
        check(t.bg == 0xFF000000, "页面底色 Color.BLACK");
        check(t.key == 0 && t.keyFill() == 0xFF000000, "键帽底透明（以前 fill 0）");
        check(t.text == 0xFF8A8A8A && t.border == 0xFF2A2A2A && t.pressed == 0xFF1C1C1C, "Style.TEXT / BORDER / PRESSED");
        check(t.fnKey == t.key && t.fnText == t.text && t.fnPressed == t.pressed && !t.fnDistinct(),
                "功能键和普通键一模一样（透明底、灰字、按下 #1C1C1C）");
        check(t.accent == 0xFF707070 && t.activeText == 0xFFDDDDDD, "Style.BORDER_ACTIVE / TEXT_ACTIVE");
        check(t.strong == 0xFFDDDDDD, "输入框文字、调整提示字 0xDDDDDD");
        check(t.padFill == 0xFF000000 && t.padBorder == 0xFF2A2A2A, "触控板：黑底、COLOR_OUTLINE");
        check(t.status == 0xFF4A4A4A && t.statusBad == 0xFF8A5A30, "状态字 COLOR_STATUS_OK / BAD");
        check(t.edge == 0xFF1E1E1E, "边缘滚动带的线");
        check(t.dim == 0xFF5A5A5A && t.dimBorder == 0xFF1E1E1E && t.hint == 0xFF555555, "角上小按钮、输入框提示字");
        check(t.menuFill == 0xFF121212, "⋯ 菜单底板");
        // 2026-11-01 新加的 ⋯ 四个槽位：写死成 focus 的蓝（灰的强调色推出来不显眼）
        check(t.menuKey == 0xFF8AB4F8 && t.menuKeyText == 0xFF000000 && t.menuKeyPressed == 0xFF7599D3 && t.menuDots == 0xFF8AB4F8,
                "⋯：蓝底黑字、按下 #7599D3、不带框的 ⋯ 蓝字");
        check(t.menuKeyPressed == Theme.blend(t.menuKey, t.menuKeyText, 0.15f), "⋯ 按下 = 蓝往黑掺一成半（同别的主题的推法）");
        check(t.previewFill == 0xFF2E2E2E && t.previewBorder == 0xFF8A8A8A && t.previewText == 0xFFF2F2F2, "放大提示");
        check(t.focus == 0xFF8AB4F8 && t.ring == 0xFF707070, "调整框 / 拖把手 / 空中鼠标状态、圆钮的圈");
        check(t.shade() == 0xB0000000, "调整时的压暗层");
        check(t.borderDp == 1.5f, "边框 1.5dp");
        float[] e = t.emojiMap();
        check(e[0] == 0.75f && e[1] == 0.75f && e[2] == 0.75f && e[3] == 0 && e[4] == 0 && e[5] == 0, "🌐 去色后乘 0.75");
    }

    /** 文字对它底下的颜色：正文 4.5:1，边框这类非文字 3:1。 */
    static void readable(Theme t) {
        int key = t.keyFill();
        ratio(t, "键帽文字 / 键帽", t.text, key, 4.5);
        ratio(t, "键帽文字 / 页面（空中鼠标页状态）", t.text, t.bg, 4.5);
        ratio(t, "键帽文字 / 按下", t.text, t.pressed, 3);
        ratio(t, "高亮文字 / 键帽", t.activeText, key, 4.5);
        ratio(t, "高亮文字 / 按下", t.activeText, t.pressed, 3);
        int fn = t.fnFill();
        ratio(t, "功能键文字 / 功能键", t.fnText, fn, 4.5);
        ratio(t, "功能键文字 / 功能键按下", t.fnText, t.fnPressed, 3);
        // 高亮的功能键照旧画在普通键帽底色上（上面"高亮文字 / 键帽"已经测了），这里只要锁住和没锁的底色分得开
        check(Theme.deltaE(fn, key) >= Theme.FN_DELTA_E, t.id + "：锁住的功能键（普通键帽底色）和没锁的分得开");
        ratio(t, "高亮边框 / 键帽", t.accent, key, 3);
        ratio(t, "亮文字 / 页面", t.strong, t.bg, 4.5);
        ratio(t, "状态字 / 触控板", t.status, t.padFill, 4.5);
        ratio(t, "出问题的状态字 / 触控板", t.statusBad, t.padFill, 4.5);
        ratio(t, "角上小按钮的字 / 触控板", t.dim, t.padFill, 4.5);
        ratio(t, "输入框提示字 / 页面", t.hint, t.bg, 4.5);
        ratio(t, "放大提示", t.previewText, t.previewFill, 4.5);
        ratio(t, "空中鼠标页右上角的状态 / 页面", t.focus, t.bg, 4.5);
        ratio(t, "调整框 / 键帽", t.focus, key, 3);
        ratio(t, "圆钮的圈 / 键帽", t.ring, key, 3);
        ratio(t, "调整提示字 / 压暗层", t.strong, Theme.over(t.shade(), key), 4.5);
        ratio(t, "调整提示字 / 雾下透出的键帽字", t.strong, Theme.over(t.shade(), t.text), 4.5);
        // 调整模式不切回字母层：Fn 层时提示字压着的是功能键
        ratio(t, "调整提示字 / 压暗层下的功能键", t.strong, Theme.over(t.shade(), fn), 4.5);
        ratio(t, "调整提示字 / 雾下透出的功能键字", t.strong, Theme.over(t.shade(), t.fnText), 4.5);
        if (!t.light) ratio(t, "🌐 去色后的中灰 / 功能键", emoji(t, 128), t.fnFill(), 3);
        ratio(t, "菜单里的字 / 菜单底板", t.text, t.menuFill, 4.5);
        check(t.status != t.statusBad, t.id + "：正常和出问题的状态字不同色");
        // 高对比里所有字都是亮色（照三星高对比键盘），角上小按钮和正文一样亮
        check(t.dim != t.text || t.light || t.id.equals("contrast"), t.id + "：角上小按钮比正文暗");
        if (t.id.equals("contrast")) {   // 高对比的次要字、圆钮的圈按 AAA（7:1）
            for (int bg : new int[]{t.padFill, t.bg}) {
                ratio(t, "高对比：状态字", t.status, bg, 7);
                ratio(t, "高对比：出问题的状态字", t.statusBad, bg, 7);
                ratio(t, "高对比：角上小按钮的字", t.dim, bg, 7);
                ratio(t, "高对比：输入框提示字", t.hint, bg, 7);
            }
            ratio(t, "高对比：圆钮的圈 / 键帽", t.ring, key, 7);
            check(t.fnText == 0xFFFFFFFF && t.text == 0xFFFFD600, "高对比：功能键字是白（强调色），字母照旧黄");
        }
    }

    /**
     * 小红点的红帽（2026-10-31 用户：一直是红色，深色主题自动调亮）：每套（含纯黑）对页面底色、触控板底色都 ≥ 3:1，而且还是红的
     * （红通道比绿、蓝都高出一截）；纯黑、深色这种本来就够的就是 ThinkPad 红原样。
     */
    static void stickRed(Theme t) {
        ratio(t, "小红点 / 页面", t.stick, t.bg, 3);
        ratio(t, "小红点 / 触控板", t.stick, t.padFill, 3);
        int r = (t.stick >> 16) & 0xFF, g = (t.stick >> 8) & 0xFF, b = t.stick & 0xFF;
        check(r - Math.max(g, b) >= 64, t.id + "：小红点还是红的 " + hex(t.stick));
        if (Theme.contrast(Theme.THINKPAD_RED, t.bg) >= 3 && Theme.contrast(Theme.THINKPAD_RED, t.padFill) >= 3) {
            check(t.stick == Theme.THINKPAD_RED, t.id + "：本来就够的不推");
        }
    }

    /**
     * ⋯ 用强调色（2026-11-01 用户：各处的 ⋯ 找不到）：十八套都测，纯黑也要显眼。
     * 键帽形状的 ⋯（豆花布局功能行最右、实体键盘 Esc 和 F1 中间的槽）不画边框，靠底色在键堆里显出来：底对页面、普通键帽、功能键都 ≥ 3:1
     * （非文字的界线），字对底 ≥ 4.5:1、对按下的底 ≥ 3:1，按下看得出来；不带框的 ⋯（触控板顶上、手柄页顶上）字对触控板底、页面 ≥ 4.5:1、
     * 对按着时垫的那块（pressed）≥ 3:1。推出来的 ⋯ 还带着强调色的色相（不是推到纯黑 / 纯白）——高对比的强调色本来就是白，除外。
     */
    static void menu(Theme t) {
        int key = t.keyFill(), fn = t.fnFill();
        ratio(t, "⋯ 键 / 页面", t.menuKey, t.bg, 3);
        ratio(t, "⋯ 键 / 键帽", t.menuKey, key, 3);
        ratio(t, "⋯ 键 / 功能键", t.menuKey, fn, 3);
        ratio(t, "⋯ 键的字 / ⋯ 键", t.menuKeyText, t.menuKey, 4.5);
        ratio(t, "⋯ 键的字 / ⋯ 键按下", t.menuKeyText, t.menuKeyPressed, 3);
        check(Theme.contrast(t.menuKeyPressed, t.menuKey) >= 1.15, t.id + "：⋯ 键按下看得出来 "
                + fmt(Theme.contrast(t.menuKeyPressed, t.menuKey)));
        check(Theme.deltaE(t.menuKey, fn) >= Theme.FN_DELTA_E && Theme.deltaE(t.menuKey, key) >= Theme.FN_DELTA_E,
                t.id + "：⋯ 键和功能键、普通键帽分得开（色差）");
        ratio(t, "不带框的 ⋯ / 触控板", t.menuDots, t.padFill, 4.5);
        ratio(t, "不带框的 ⋯ / 页面（手柄页）", t.menuDots, t.bg, 4.5);
        ratio(t, "不带框的 ⋯ / 按着时的底", t.menuDots, t.pressed, 3);
        if (t != Theme.DEFAULT) {
            if (t.accent != 0xFFFFFFFF) {
                check(!grey(t.menuKey) && !grey(t.menuDots), t.id + "：⋯ 还带着强调色 " + hex(t.menuKey) + " / " + hex(t.menuDots));
            }
            // 本来就够的不推（强调色原样）
            if (Theme.contrast(t.accent, t.bg) >= 3 && Theme.contrast(t.accent, key) >= 3 && Theme.contrast(t.accent, fn) >= 3) {
                check(t.menuKey == t.accent, t.id + "：⋯ 键本来就够的不推");
            }
            // 字是页面色或正文色（或它们推出来的）：深色主题多半是深字、浅色主题是浅字
            check(t.menuKeyText == Theme.reach(t.bg, t.menuKey, 4.5) || t.menuKeyText == Theme.reach(t.text, t.menuKey, 4.5),
                    t.id + "：⋯ 键的字从页面色 / 正文色里挑");
        }
        // 和原来的暗字（Style.bare 以前用的 dim）一眼分得开
        check(Theme.deltaE(t.menuDots, t.dim) >= Theme.FN_DELTA_E || t.id.equals("contrast"),
                t.id + "：不带框的 ⋯ 和原来的暗字分得开（色差 " + fmt(Theme.deltaE(t.menuDots, t.dim)) + "）");
    }

    /** 灰的（三个通道差不到 16）。 */
    private static boolean grey(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 16;
    }

    /** 灰度 v（0–255）的 emoji 像素经 {@link Theme#emojiMap} 后的颜色。 */
    static int emoji(Theme t, int v) {
        float[] k = t.emojiMap();
        int out = 0xFF000000;
        for (int i = 0; i < 3; i++) {
            int c = Math.max(0, Math.min(255, Math.round(v * k[i] + k[3 + i])));
            out |= c << (16 - 8 * i);
        }
        return out;
    }

    /** 触控板一眼和键盘分得开：边框是强调色（对触控板底、页面底都 ≥ 3:1）、和键帽边框不同色；键帽和页面也分得开。 */
    static void separable(Theme t) {
        if (t == Theme.DEFAULT) return;   // 纯黑就是以前的样子（灰边）
        ratio(t, "触控板边框 / 触控板", t.padBorder, t.padFill, 3);
        ratio(t, "触控板边框 / 页面", t.padBorder, t.bg, 3);
        ratio(t, "键帽边框 / 页面", t.border, t.bg, 3);
        if (t.keyFill() != t.bg) ratio(t, "键帽边框 / 键帽", t.border, t.keyFill(), 3);
        check(t.padBorder != t.border, t.id + "：触控板边框和键帽边框不同色");
        check(Theme.contrast(t.pressed, t.keyFill()) >= 1.15, t.id + "：按下看得出来");
        // 功能键：和普通键帽、页面底色都看得出差别（色差，算上色相：樱花、薄荷的页面本身带着强调色的色调）、按下看得出来
        check(t.fnDistinct(), t.id + "：功能键和普通键分开上色");
        double dk = Theme.deltaE(t.fnFill(), t.keyFill()), db = Theme.deltaE(t.fnFill(), t.bg);
        check(dk >= Theme.FN_DELTA_E, t.id + "：功能键 / 普通键帽 色差 " + fmt(dk) + " ≥ " + Theme.FN_DELTA_E);
        check(db >= Theme.FN_DELTA_E, t.id + "：功能键 / 页面 色差 " + fmt(db) + " ≥ " + Theme.FN_DELTA_E);
        check(Theme.contrast(t.fnPressed, t.fnFill()) >= 1.15, t.id + "：功能键按下看得出来 "
                + fmt(Theme.contrast(t.fnPressed, t.fnFill())));
        if (t.keyFill() != t.bg) {
            check(Theme.contrast(t.keyFill(), t.bg) >= 1.1, t.id + "：键帽和页面底色分得开 "
                    + fmt(Theme.contrast(t.keyFill(), t.bg)));
        } else {
            ratio(t, "键帽和页面同色：靠边框", t.border, t.bg, 3);
        }
    }

    static void helpers() {
        check(Math.abs(Theme.contrast(0xFF000000, 0xFFFFFFFF) - 21) < 1e-9, "黑白 21:1");
        check(Theme.contrast(0xFF777777, 0xFF777777) == 1, "同色 1:1");
        check(Theme.blend(0xFF000000, 0xFFFFFFFF, 0.5f) == 0xFF808080, "混一半");
        check(Theme.over(0x00123456, 0xFF000000) == 0xFF000000, "透明盖上去等于底");
        check(Theme.over(0x80FFFFFF, 0xFF000000) == 0xFF808080, "半透明白盖在黑上");
        int r = Theme.reach(0xFF333333, 0xFF000000, 4.5);
        check(Theme.contrast(r, 0xFF000000) >= 4.5 && Theme.luminance(r) > Theme.luminance(0xFF333333), "暗底上往亮推");
        int d = Theme.reach(0xFFCCCCCC, 0xFFFFFFFF, 4.5);
        check(Theme.contrast(d, 0xFFFFFFFF) >= 4.5 && Theme.luminance(d) < Theme.luminance(0xFFCCCCCC), "亮底上往暗推");
        check(Theme.reach(0xFFFFFFFF, 0xFF000000, 4.5) == 0xFFFFFFFF, "已经够的不动");
        check(Theme.deltaE(0xFF3C4043, 0xFF3C4043) == 0, "同色色差 0");
        check(Math.abs(Theme.deltaE(0xFF000000, 0xFFFFFFFF) - 100) < 0.05, "黑白色差 100（L* 0 → 100）");
        int m = Theme.dimmest(0xFF000000, 0xFFFFFFFF, 4.5, 0xFF000000);
        check(Theme.contrast(m, 0xFF000000) >= 4.5 && Theme.contrast(m, 0xFF000000) < 5, "刚好够 4.5:1，不多亮");
        // reachAll：一次看几个底，方向只看第一个
        int a = Theme.reachAll(0xFF333333, new int[]{0xFF000000, 0xFF202020}, new double[]{3, 4.5});
        check(Theme.contrast(a, 0xFF000000) >= 3 && Theme.contrast(a, 0xFF202020) >= 4.5 && Theme.luminance(a) > Theme.luminance(0xFF333333),
                "reachAll：暗底上往亮推到每个底都够");
        check(Theme.reachAll(0xFFFFFFFF, new int[]{0xFF000000}, new double[]{4.5}) == 0xFFFFFFFF, "reachAll：已经够的不动");
        check(Theme.reachAll(0xFF808080, new int[]{0xFF000000}, new double[]{4.5}) == Theme.reach(0xFF808080, 0xFF000000, 4.5),
                "reachAll：一个底时同 reach");
        // 对白、对深灰都要 7:1：没有这样的颜色，推到头（白底 → 往黑推）就是黑
        check(Theme.reachAll(0xFF808080, new int[]{0xFFFFFFFF, 0xFF202020}, new double[]{7, 7}) == 0xFF000000,
                "reachAll：推到头还不够就是那一头的黑");
    }

    static void print() {
        System.out.println("| 槽位 | " + join(t -> t.id) + " |");
        StringBuilder sep = new StringBuilder("|---|");
        for (int i = 0; i < Theme.ALL.length; i++) sep.append("---|");
        System.out.println(sep);
        String[] names = {"bg", "key", "text", "border", "pressed", "accent", "activeText", "strong", "padFill", "padBorder",
                "status", "statusBad", "previewFill", "previewBorder", "previewText", "focus", "ring", "dim", "dimBorder",
                "hint", "edge", "menuFill", "fnKey", "fnText", "fnPressed", "stick", "menuKey", "menuKeyText", "menuKeyPressed",
                "menuDots", "shade"};
        for (int i = 0; i < names.length; i++) {
            final int slot = i;
            System.out.println("| " + names[i] + " | " + join(t -> hex(printed(t)[slot])) + " |");
        }
        System.out.println("| borderDp | " + join(t -> String.valueOf(t.borderDp)) + " |");
        System.out.println();
        System.out.println("| 对比度 | " + join(t -> t.id) + " |");
        System.out.println(sep);
        row("字 / 键帽", t -> Theme.contrast(t.text, t.keyFill()));
        row("字 / 页面", t -> Theme.contrast(t.text, t.bg));
        row("高亮字 / 键帽", t -> Theme.contrast(t.activeText, t.keyFill()));
        row("功能键字 / 功能键", t -> Theme.contrast(t.fnText, t.fnFill()));
        row("功能键 / 键帽 色差", t -> Theme.deltaE(t.fnFill(), t.keyFill()));
        row("功能键 / 页面 色差", t -> Theme.deltaE(t.fnFill(), t.bg));
        row("功能键按下 / 功能键", t -> Theme.contrast(t.fnPressed, t.fnFill()));
        row("键帽边 / 功能键", t -> Theme.contrast(t.border, t.fnFill()));
        row("高亮边 / 键帽", t -> Theme.contrast(t.accent, t.keyFill()));
        row("状态字 / 触控板", t -> Theme.contrast(t.status, t.padFill));
        row("出问题状态 / 触控板", t -> Theme.contrast(t.statusBad, t.padFill));
        row("角上小字 / 触控板", t -> Theme.contrast(t.dim, t.padFill));
        row("提示字 / 页面", t -> Theme.contrast(t.hint, t.bg));
        row("空鼠状态 / 页面", t -> Theme.contrast(t.focus, t.bg));
        row("触控板边 / 触控板", t -> Theme.contrast(t.padBorder, t.padFill));
        row("键帽 / 页面", t -> Theme.contrast(t.keyFill(), t.bg));
        row("键帽边 / 键帽", t -> Theme.contrast(t.border, t.keyFill()));
        row("键帽边 / 页面", t -> Theme.contrast(t.border, t.bg));
        row("调整提示 / 透出的字", t -> Theme.contrast(t.strong, Theme.over(t.shade(), t.text)));
        row("🌐 中灰 / 功能键", t -> Theme.contrast(emoji(t, 128), t.fnFill()));
        row("小红点 / 页面", t -> Theme.contrast(t.stick, t.bg));
        row("小红点 / 触控板", t -> Theme.contrast(t.stick, t.padFill));
        row("⋯ 键 / 页面", t -> Theme.contrast(t.menuKey, t.bg));
        row("⋯ 键 / 键帽", t -> Theme.contrast(t.menuKey, t.keyFill()));
        row("⋯ 键 / 功能键", t -> Theme.contrast(t.menuKey, t.fnFill()));
        row("⋯ 键的字 / ⋯ 键", t -> Theme.contrast(t.menuKeyText, t.menuKey));
        row("⋯ 键的字 / 按下", t -> Theme.contrast(t.menuKeyText, t.menuKeyPressed));
        row("不带框的 ⋯ / 触控板", t -> Theme.contrast(t.menuDots, t.padFill));
        row("不带框的 ⋯ / 页面", t -> Theme.contrast(t.menuDots, t.bg));
        row("不带框的 ⋯ / 按下", t -> Theme.contrast(t.menuDots, t.pressed));
    }

    // ── 小工具 ──

    private interface Cell {
        String of(Theme t);
    }

    private interface Num {
        double of(Theme t);
    }

    private static String join(Cell c) {
        StringBuilder b = new StringBuilder();
        for (Theme t : Theme.ALL) {
            if (b.length() > 0) b.append(" | ");
            b.append(c.of(t));
        }
        return b.toString();
    }

    private static void row(String name, Num n) {
        System.out.println("| " + name + " | " + join(t -> fmt(n.of(t))) + " |");
    }

    private static int[] slots(Theme t) {
        return new int[]{t.bg, t.keyFill(), t.text, t.border, t.pressed, t.accent, t.activeText, t.strong, t.padFill,
                t.padBorder, t.status, t.statusBad, t.previewFill, t.previewBorder, t.previewText, t.focus, t.ring, t.dim,
                t.dimBorder, t.hint, t.edge, t.menuFill, t.fnFill(), t.fnText, t.fnPressed, t.stick, t.menuKey, t.menuKeyText,
                t.menuKeyPressed, t.menuDots};
    }

    private static int[] printed(Theme t) {
        int[] s = slots(t);
        int[] out = java.util.Arrays.copyOf(s, s.length + 1);
        out[s.length] = t.shade();
        return out;
    }

    private static void ratio(Theme t, String what, int fg, int bg, double min) {
        double r = Theme.contrast(fg, bg);
        check(r >= min, t.id + "：" + what + " " + fmt(r) + " ≥ " + min);
    }

    private static String hex(int c) {
        if ((c >>> 24) != 0xFF) return String.format(Locale.ROOT, "#%08X", c);   // 带透明度的（雾层）连 alpha 一起印
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
