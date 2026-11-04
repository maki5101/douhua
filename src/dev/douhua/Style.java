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

    /** 键帽；pair 不是 null 时主字旁边并排另画一个字（{@link PairKey}：小红点状态的逗号句号合键，按住 0.4 秒出它）。 */
    static TextView keyButton(Context c, int textSp, String pair) {
        TextView b = setup(pair == null ? new TextView(c) : new PairKey(c, pair), textSp, theme, keyBorders);
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
        float stroke = active || (keyBorders && theme.material == null) ? theme.borderDp : 0;
        int fill = pressed ? (f ? theme.fnPressed : theme.pressed) : (f ? theme.fnKey : theme.key);
        float density = density(v.getContext());
        v.setBackground(new CutCap(dp(v.getContext(), 10), stroke > 0 ? Math.max(1, Math.round(stroke * density)) : 0,
                active ? theme.accent : theme.border, fill, cut[0], cut[1], cut[2], theme.material, pressed, density));
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
        // 有材质的主题不描轮廓（键和页面靠底色和下沿的影子分开），高亮的照旧画强调色的边
        float stroke = active || (borders && t.material == null) ? t.borderDp : 0;
        int fill = pressed ? (f ? t.fnPressed : t.pressed) : (f ? t.fnKey : t.key);
        if (t.material == null) {
            v.setBackground(border(v.getContext(), stroke, active ? t.accent : t.border, fill));
        } else {
            // 有材质的主题：平的底色 + 下沿的淡影子（CutCap 不切口的用法），圆角和平面的一样
            float density = density(v.getContext());
            v.setBackground(new CutCap(dp(v.getContext(), 10), stroke > 0 ? Math.max(1, Math.round(stroke * density)) : 0,
                    active ? t.accent : t.border, fill, 0, 0, CutCap.NO_CUT, t.material, pressed, density));
        }
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
        float density = density(v.getContext());
        if (cut == null && theme.material == null) v.setBackground(border(v.getContext(), 0, 0, fill));
        else v.setBackground(new CutCap(dp(v.getContext(), 10), 0, 0, fill, cut == null ? 0 : cut[0], cut == null ? 0 : cut[1],
                cut == null ? CutCap.NO_CUT : cut[2], theme.material, pressed, density));
        if (v instanceof TextView) ((TextView) v).setTextColor(theme.menuKeyText);
    }

    /**
     * 两个字并排的键帽（2026-11-01 晚，小红点状态的逗号句号合键：用户："那个点儿太小了，而且为什么这么靠近边缘？你就直接跟它，把它跟那个逗号并列啊，
     * 然后把它颜色放浅"；以前是大字 "," + 右上角小字 "."）：主字（TextView 自己的文字，自动缩字号、末尾省略的规矩照旧，只是不由它画）
     * 和 pair 并排、一样的字号、挨在一起居中（中间隔半个字号宽的空，别糊成 ",."）；主字照当前字色，pair 用浅一档的 Theme.softText
     * （对键帽 ≥ 3:1，ThemeTest 锁住）。两个字放不下就一起缩（同 FixedKeyboardView.fit，最小 7sp）。上下内边距（被小红点切到时挪字）照算。
     */
    static final class PairKey extends TextView {
        private final String pair;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PairKey(Context c, String pair) {
            super(c);
            this.pair = pair;
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 不调 super.onDraw：主字也由这里画（TextView 画的话会画在正中，和 pair 叠在一起）。背景照常由 View.draw 先画
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            String main = getText().toString();
            paint.setTypeface(getTypeface());
            float size = getTextSize();
            paint.setTextSize(size);
            float room = w - getPaddingLeft() - getPaddingRight() - 2 * dp(getContext(), 2);
            float total = paint.measureText(main) + size * PAIR_GAP + paint.measureText(pair);
            if (total > room && room > 0) {
                size = Math.max(sp(getContext(), 7), size * room / total);
                paint.setTextSize(size);
                total = paint.measureText(main) + size * PAIR_GAP + paint.measureText(pair);
            }
            float left = getPaddingLeft() + (w - getPaddingLeft() - getPaddingRight() - total) / 2f;
            Paint.FontMetrics fm = paint.getFontMetrics();
            float cy = (getPaddingTop() + h - getPaddingBottom()) / 2f;
            float base = cy - (fm.ascent + fm.descent) / 2f;
            float x = getScrollX() + left, y = getScrollY() + base;
            paint.setColor(getCurrentTextColor());
            canvas.drawText(main, x, y, paint);
            paint.setColor(theme.softText);
            canvas.drawText(pair, x + paint.measureText(main) + size * PAIR_GAP, y, paint);
        }
    }

    /** 两个字并排时中间隔多宽（字号的倍数）。 */
    static final float PAIR_GAP = 0.5f;

    /**
     * 被小红点切掉一块的键帽（照真 ThinkPad：G、H、B 被和红点同心的圆弧切掉）：圆角矩形减去一个圆（Path 的 DIFFERENCE），
     * 底色、边框都沿着圆弧走。同 GradientDrawable：边框画在往里缩半条边框的矩形上（底色也是），圆也往外扩半条，
     * 所以边框外沿正好在键帽的边、切口的边（cr）上，和没切的键帽的边框对得齐。
     */
    static final class CutCap extends Drawable {
        /** cr 写它 = 不切口（有材质的主题拿它画普通的键帽）。 */
        static final float NO_CUT = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path hole = new Path();
        private final RectF box = new RectF();
        private final float corner, strokeW, cx, cy, cr;
        private final int fillColor, strokeColor;
        private int alpha = 255;
        /** 材质（null = 平面，和以前一模一样）、按着没有、影子多少像素。 */
        private final Theme.Material material;
        private final boolean down;
        private final float shadowPx;
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shadowPath = new Path();

        CutCap(float corner, float strokeW, int strokeColor, int fillColor, float cx, float cy, float cr) {
            this(corner, strokeW, strokeColor, fillColor, cx, cy, cr, null, false, 1);
        }

        /**
         * 有材质的键帽（2026-11-04，Theme.Material）：平的底色，下面一道往下挪的淡影子（键帽本身让出这条，影子在键帽自己的范围里，
         * 不画到别的键上）。按着：没有影子、整个往下沉半条。material 是 null 时完全是原来的画法。
         */
        CutCap(float corner, float strokeW, int strokeColor, int fillColor, float cx, float cy, float cr,
               Theme.Material material, boolean down, float density) {
            this.corner = corner;
            this.strokeW = strokeW;
            this.cx = cx;
            this.cy = cy;
            this.cr = cr;
            this.fillColor = fillColor;
            this.strokeColor = strokeColor;
            this.material = material;
            this.down = down;
            this.shadowPx = material == null ? 0 : Math.max(1, Math.round(Theme.Material.SHADOW_DP * density));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeW);
            applyAlpha();
        }

        /** 颜色本身的透明度再乘上 setAlpha 给的（同 GradientDrawable）。 */
        private void applyAlpha() {
            fill.setColor(withAlpha(fillColor));
            stroke.setColor(withAlpha(strokeColor));
            if (material != null) shadow.setColor(withAlpha(material.shadowColor()));
        }

        private int withAlpha(int c) {
            return (c & 0xFFFFFF) | ((Color.alpha(c) * alpha / 255) << 24);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float inset = strokeW / 2;
            box.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            if (material != null) {
                // 影子让出一条：平时键帽在上、影子露在下沿；按着时整个往下沉半条
                if (down) box.offset(0, shadowPx / 2);
                box.bottom -= down ? shadowPx / 2 : shadowPx;
            }
            float r = Math.max(0, Math.min(corner, Math.min(box.width(), box.height()) / 2));
            path.reset();
            path.addRoundRect(box, r, r, Path.Direction.CW);
            if (cr != NO_CUT) {
                hole.reset();
                hole.addCircle(b.left + cx, b.top + cy, cr + inset, Path.Direction.CW);
                path.op(hole, Path.Op.DIFFERENCE);
            }
            if (material != null) {
                shadowPath.reset();
                path.offset(0, shadowPx, shadowPath);
            }
        }

        @Override
        public void draw(Canvas c) {
            if (material != null && !down) c.drawPath(shadowPath, shadow);
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
