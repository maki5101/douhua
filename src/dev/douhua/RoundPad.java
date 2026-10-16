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
        fill.setColor(pressed ? Style.PRESSED : 0xFF000000);
        c.drawCircle(cx, cy, r, fill);
        ring.setColor(active ? Style.TEXT_ACTIVE : Style.BORDER_ACTIVE);
        c.drawCircle(cx, cy, r, ring);
        dot.setColor(active ? Style.TEXT_ACTIVE : Style.TEXT);
        float gap = r * 0.3f;
        float dr = Math.max(1.5f * Style.density(getContext()), r * 0.055f);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) c.drawCircle(cx + i * gap, cy + j * gap, dr, dot);
        }
    }
}
