package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
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

    /** sp 换成像素（跟着系统字体大小变，同 setTextSize(COMPLEX_UNIT_SP)）。 */
    static int sp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics()));
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

    /**
     * 键帽（整屏键盘、数字键盘）：边框跟"按键边框"开关。缩到最小字号还放不下的键名末尾省略（…），不在词中间断开、只露半截
     * （小红点状态下缩短的空格、分到空位两边的快捷键，德 / 法 / 西 / 意 / 日 / 韩语的短名可能放不下，2026-11-01 审查发现）。
     */
    static TextView keyButton(Context c, int textSp) {
        return keyButton(c, textSp, null);
    }

    /** 键帽；corner 不是 null 时右上角另画一个小字（{@link CornerKey}：小红点状态的逗号句号合键，按住 0.4 秒出它）。 */
    static TextView keyButton(Context c, int textSp, String corner) {
        TextView b = setup(corner == null ? new TextView(c) : new CornerKey(c, corner), textSp, theme, keyBorders);
        b.setEllipsize(TextUtils.TruncateAt.END);
        return b;
    }

    static TextView button(Context c, int textSp, Theme t, boolean borders) {
        return setup(new TextView(c), textSp, t, borders);
    }

    private static TextView setup(TextView b, int textSp, Theme t, boolean borders) {
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

    /**
     * 同上，cut = 被小红点切掉的那块 {圆心 x, 圆心 y, 半径}（键帽自己的坐标，KeyboardPanel 给；null = 没切）：
     * 底色、按下、高亮、边框都照圆角矩形减去这个圆画（{@link CutCap}），颜色和别的键帽一模一样。
     */
    static void keyLook(View v, boolean fn, boolean active, boolean pressed, float[] cut) {
        look(v, theme, keyBorders, fn, active, pressed);
        if (cut == null) return;
        boolean f = fn && !active;
        float stroke = active || keyBorders ? theme.borderDp : 0;
        int fill = pressed ? (f ? theme.fnPressed : theme.pressed) : (f ? theme.fnKey : theme.key);
        float density = density(v.getContext());
        v.setBackground(new CutCap(dp(v.getContext(), 10), stroke > 0 ? Math.max(1, Math.round(stroke * density)) : 0,
                active ? theme.accent : theme.border, fill, cut[0], cut[1], cut[2]));
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

    /**
     * 不带框的 ⋯（主界面 / 键盘屏触控板顶上正中、手柄页顶上正中）：只有字、不带框（用户 2026-10-31：带框放角上丑），
     * 字是强调色（Theme.menuDots，2026-11-01 用户：⋯ 用强调色，以前的暗字找不到），按着时一块按下的底色。
     */
    static void bare(TextView v, boolean pressed) {
        v.setTextColor(theme.menuDots);
        v.setBackground(pressed ? border(v.getContext(), 0, 0, theme.pressed) : null);
    }

    /**
     * ⋯ 做成键帽时（豆花布局功能行最右的 ⋯ 键；实体键盘的 ⋯ 槽由 FixedKeyboardView 自己画、颜色同这里）：强调色的底（按下时 menuKeyPressed）、
     * 反差字、不画边框（照 Gboard 的回车键；"按键边框"开关不管它，高亮也没有）。cut 同 {@link #keyLook(View, boolean, boolean, boolean, float[])}：
     * 被小红点切到时沿圆弧画（功能行碰不到红点，防一手），颜色一样。
     */
    static void menuKeyLook(View v, boolean pressed, float[] cut) {
        int fill = pressed ? theme.menuKeyPressed : theme.menuKey;
        if (cut == null) v.setBackground(border(v.getContext(), 0, 0, fill));
        else v.setBackground(new CutCap(dp(v.getContext(), 10), 0, 0, fill, cut[0], cut[1], cut[2]));
        if (v instanceof TextView) ((TextView) v).setTextColor(theme.menuKeyText);
    }

    /**
     * 右上角带一个小字的键帽（2026-11-01，小红点状态的逗号句号合键：大字 ","、右上角 "."，照 Gboard 长按候选的惯例放右上角）。
     * 大字照常（自动缩字号、末尾省略）；小字字号 min(10sp, 键高 × 0.22)、颜色 = 当前字色（对键帽 ≥ 4.5:1 由 ThemeTest 保证）、
     * 离右边 4dp、离上边 3dp（同实体键盘角上小字的大小）。
     */
    static final class CornerKey extends TextView {
        private final String corner;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CornerKey(Context c, String corner) {
            super(c);
            this.corner = corner;
            paint.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            paint.setColor(getCurrentTextColor());
            paint.setTextSize(Math.min(sp(getContext(), 10), getHeight() * 0.22f));
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(corner, getScrollX() + getWidth() - dp(getContext(), 4), getScrollY() + dp(getContext(), 3) - fm.ascent, paint);
        }
    }

    /**
     * 被小红点切掉一块的键帽（照真 ThinkPad：G、H、B 被和红点同心的圆弧切掉）：圆角矩形减去一个圆（Path 的 DIFFERENCE），
     * 底色、边框都沿着圆弧走。同 GradientDrawable：边框画在往里缩半条边框的矩形上（底色也是），圆也往外扩半条，
     * 所以边框外沿正好在键帽的边、切口的边（cr）上，和没切的键帽的边框对得齐。
     */
    static final class CutCap extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path hole = new Path();
        private final RectF box = new RectF();
        private final float corner, strokeW, cx, cy, cr;
        private final int fillColor, strokeColor;
        private int alpha = 255;

        CutCap(float corner, float strokeW, int strokeColor, int fillColor, float cx, float cy, float cr) {
            this.corner = corner;
            this.strokeW = strokeW;
            this.cx = cx;
            this.cy = cy;
            this.cr = cr;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeW);
            applyAlpha();
        }

        /** 颜色本身的透明度再乘上 setAlpha 给的（同 GradientDrawable）。 */
        private void applyAlpha() {
            fill.setColor(withAlpha(fillColor));
            stroke.setColor(withAlpha(strokeColor));
        }

        private int withAlpha(int c) {
            return (c & 0xFFFFFF) | ((Color.alpha(c) * alpha / 255) << 24);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float inset = strokeW / 2;
            box.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            float r = Math.max(0, Math.min(corner, Math.min(box.width(), box.height()) / 2));
            path.reset();
            path.addRoundRect(box, r, r, Path.Direction.CW);
            hole.reset();
            hole.addCircle(b.left + cx, b.top + cy, cr + inset, Path.Direction.CW);
            path.op(hole, Path.Op.DIFFERENCE);
        }

        @Override
        public void draw(Canvas c) {
            if (Color.alpha(fill.getColor()) != 0) c.drawPath(path, fill);
            if (strokeW > 0) c.drawPath(path, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = Math.max(0, Math.min(255, alpha));
            applyAlpha();
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
            stroke.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
