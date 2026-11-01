package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 空中鼠标页的圆钮：一个圆，中间 3×3 九个小点（像 ThinkPad 小红点帽上的防滑纹）。不透明，叠在四个格子的角上要把它们盖住。
 * active = 跟随中（点更亮、边框更亮），pressed = 手指按着。
 */
final class RoundPad extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;
    private boolean pressed;

    RoundPad(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1, Math.round(2f * Style.density(c))));
    }

    void setState(boolean active, boolean pressed) {
        if (active == this.active && pressed == this.pressed) return;
        this.active = active;
        this.pressed = pressed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - ring.getStrokeWidth();
        if (r <= 0) return;
        Theme t = Style.theme();   // 画的时候现取：换主题只要 invalidate
        fill.setColor(pressed ? t.pressed : t.keyFill());
        c.drawCircle(cx, cy, r, fill);
        ring.setColor(active ? t.activeText : t.ring);
        c.drawCircle(cx, cy, r, ring);
        dot.setColor(active ? t.activeText : t.text);
        dots(c, cx, cy, r, dot, Style.density(getContext()));
    }

    /** 圆（圆心 cx, cy，半径 r）上 3 × 3 九个小点，像 ThinkPad 小红点帽上的防滑纹；键盘页的小红点（TrackPointView）也用。 */
    static void dots(Canvas c, float cx, float cy, float r, Paint p, float density) {
        float gap = r * 0.3f;
        float dr = Math.max(1.5f * density, r * 0.055f);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) c.drawCircle(cx + i * gap, cy + j * gap, dr, p);
        }
    }
}
