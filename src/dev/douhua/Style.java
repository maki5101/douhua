package dev.douhua;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * 主界面和键盘面板共用的样式：颜色都从当前主题（{@link Theme}，设置 → 外观）取。
 * 当前主题由 MainActivity 在建界面之前、设置改完回来时设（{@link #setTheme}）；换了以后已经建好的 view 由 MainActivity 逐个重新上色。
 * 设置页的预览不碰当前主题，用带 Theme 参数的那几个方法。
 */
final class Style {
    private static Theme theme = Theme.DEFAULT;
    /**
     * 键帽画边框（设置里"按键边框"）；关掉只靠键帽底色区分（纯黑 / 高对比的键帽和页面同色，关掉就只剩字）。
     * 只管键帽（整屏键盘、数字键盘，经 {@link #keyButton} / {@link #keyLook}）；按钮条、空中鼠标页的格子、⋯ 菜单项、
     * 调整栏、贴字面板的按钮一直有边框（这些是大块的触摸区，没边框就看不出从哪到哪）。
     */
    private static boolean keyBorders = true;

    private Style() {}

    static Theme theme() {
        return theme;
    }

    static boolean keyBorders() {
        return keyBorders;
    }

    static void setTheme(Theme t, boolean borders) {
        theme = t;
        keyBorders = borders;
    }

    static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    static int dp(Context c, float v) {
        return Math.round(v * density(c));
    }

    /** 圆角框，边框粗细跟当前主题（高对比粗一点）。 */
    static GradientDrawable border(Context c, int strokeColor, int fillColor) {
        return border(c, theme.borderDp, strokeColor, fillColor);
    }

    /** strokeDp = 0：不画边框。 */
    static GradientDrawable border(Context c, float strokeDp, int strokeColor, int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 10));
        if (strokeDp > 0) d.setStroke(Math.max(1, Math.round(strokeDp * density(c))), strokeColor);
        d.setColor(fillColor);
        return d;
    }

    /** 带边框的按钮（按钮条、空中鼠标页的格子、菜单项……），当前主题；边框一直画，不跟"按键边框"开关。 */
    static TextView button(Context c, int textSp) {
        return button(c, textSp, theme, true);
    }

    /** 键帽（整屏键盘、数字键盘）：边框跟"按键边框"开关。 */
    static TextView keyButton(Context c, int textSp) {
        return button(c, textSp, theme, keyBorders);
    }

    static TextView button(Context c, int textSp, Theme t, boolean borders) {
        TextView b = new TextView(c);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        look(b, t, borders, false, false);
        b.setMaxLines(1);
        // 德语、法语之类的按钮名比中文长：放不下就自动缩小字号，不截断
        b.setAutoSizeTextTypeUniformWithConfiguration(8, Math.max(9, textSp), 1, TypedValue.COMPLEX_UNIT_SP);
        return b;
    }

    /** active = 高亮（面板开着、修饰键锁定中）；pressed = 手指按着。一直画边框（键帽用 {@link #keyLook}）。 */
    static void look(View v, boolean active, boolean pressed) {
        look(v, theme, true, active, pressed);
    }

    /** 键帽的样子：边框跟"按键边框"开关。 */
    static void keyLook(View v, boolean active, boolean pressed) {
        look(v, theme, keyBorders, false, active, pressed);
    }

    /** 键帽的样子，fn = 功能键（KeyRows.functionKey / numpadFunctionKey）：底色掺一点强调色、字是强调色。 */
    static void keyLook(View v, boolean fn, boolean active, boolean pressed) {
        look(v, theme, keyBorders, fn, active, pressed);
    }

    static void look(View v, Theme t, boolean borders, boolean active, boolean pressed) {
        look(v, t, borders, false, active, pressed);
    }

    /**
     * 按键边框关着时平时不画边框，高亮的照样画强调色的边（锁住的修饰键要一眼看得出来）。
     * fn = 功能键：平时、按下用功能键那套颜色；高亮时照旧是普通键帽的底色 + 强调色的边 + 高亮的字
     * （功能键的字本来就是强调色，底色再照旧掺着的话，锁住和没锁只差一圈边框）。纯黑的功能键颜色和普通键一样。
     */
    static void look(View v, Theme t, boolean borders, boolean fn, boolean active, boolean pressed) {
        boolean f = fn && !active;
        float stroke = active || borders ? t.borderDp : 0;
        int fill = pressed ? (f ? t.fnPressed : t.pressed) : (f ? t.fnKey : t.key);
        v.setBackground(border(v.getContext(), stroke, active ? t.accent : t.border, fill));
        if (v instanceof TextView) ((TextView) v).setTextColor(active ? t.activeText : f ? t.fnText : t.text);
    }

    /**
     * 触控板角上的小按钮：暗字、暗边框、平时透出触控板底色；不跟"按键边框"开关。
     * onImage = 触控板上有背景图：平时的底改成不透明的触控板底色（字、边框和没图时一模一样看得清），按下照旧。
     */
    static void small(TextView v, boolean pressed, boolean onImage) {
        v.setTextColor(theme.dim);
        v.setBackground(border(v.getContext(), theme.dimBorder, pressed ? theme.pressed : onImage ? theme.padFill : 0));
    }

    /** 键盘屏触控板顶上的 ⋯：只有暗字、不带框（用户 2026-10-31：带框放角上丑），按着时一块按下的底色。 */
    static void bare(TextView v, boolean pressed) {
        v.setTextColor(theme.dim);
        v.setBackground(pressed ? border(v.getContext(), 0, 0, theme.pressed) : null);
    }
}
