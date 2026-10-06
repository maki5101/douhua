package dev.douhua;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** 主界面和键盘面板共用的样式：纯黑底、暗灰边框、按下时稍亮。 */
final class Style {
    static final int BORDER = 0xFF2A2A2A;
    static final int BORDER_ACTIVE = 0xFF707070;
    static final int TEXT = 0xFF8A8A8A;
    static final int TEXT_ACTIVE = 0xFFDDDDDD;
    static final int PRESSED = 0xFF1C1C1C;

    private Style() {}

    static float density(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    static int dp(Context c, float v) {
        return Math.round(v * density(c));
    }

    static GradientDrawable border(Context c, int strokeColor, int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 10));
        d.setStroke(Math.max(1, Math.round(1.5f * density(c))), strokeColor);
        d.setColor(fillColor);
        return d;
    }

    /** 带边框的按钮。 */
    static TextView button(Context c, int textSp) {
        TextView b = new TextView(c);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        b.setTextColor(TEXT);
        b.setBackground(border(c, BORDER, 0));
        b.setMaxLines(1);
        // 德语、法语之类的按钮名比中文长：放不下就自动缩小字号，不截断
        b.setAutoSizeTextTypeUniformWithConfiguration(8, Math.max(9, textSp), 1, TypedValue.COMPLEX_UNIT_SP);
        return b;
    }

    /** active = 高亮（面板开着、修饰键锁定中）；pressed = 手指按着。 */
    static void look(View v, boolean active, boolean pressed) {
        v.setBackground(border(v.getContext(), active ? BORDER_ACTIVE : BORDER, pressed ? PRESSED : 0));
        if (v instanceof TextView) ((TextView) v).setTextColor(active ? TEXT_ACTIVE : TEXT);
    }
}
