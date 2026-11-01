package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 小红点的样子（键盘页 ⋯ 菜单里"指点"选小红点时）：整块是按得到的范围（正方形，按得到的是它的内切圆，TouchRouter.Region.contains），
 * 正中一个红色圆帽，帽上 3 × 3 九个小凹点（同空中鼠标页的圆钮，RoundPad.dots）。两种画法：
 * - 嵌在键里（{@link #setEmbedded}，2026-11-01 照真 ThinkPad 改）：挨着的键自己被和红点同心的圆弧切掉一块（KeyboardPanel、Style.CutCap），
 *   这里只画红帽——半径 = 范围半径 − 缝 / 2（缝是红帽和键上切口之间那一圈，范围的边在缝的中线上）；按着时缝的里半圈描一圈 focus 色。
 *   以前用页面底色画一个方块盖住四个键的角（"直接叠加"，用户嫌丑），方块的四个角看着是键、按上去归红点。
 * - 不压键（键盘外面）：一圈淡的圆标出范围，红帽占范围的 {@link #CAP_FREE}（64dp 的范围约 45dp 的帽），按着时圆圈换 focus 色。
 * 按着时帽子亮一点。红点一直是红的（Theme.stick，按主题调亮 / 调暗到对页面和触控板底色都 ≥ 3:1）。不画手指偏了多少：服务接管时 App 收不到触点。
 * 触摸不在这里处理：分流（TouchRouter → TrackPoint）按报上去的区域认它；服务连着没接管时 MainActivity 让嵌在键里的红点一根手指都不收
 * （全落到底下的键盘面板上，面板按内切圆把红点那块挖掉），放在键盘外面时整块吃掉。
 */
final class TrackPointView extends View {
    /** 红帽直径占范围边长多少：不压键时（外面一圈淡圆）。 */
    static final float CAP_FREE = 0.7f;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean pressed;
    private boolean embedded;
    /** 嵌在键里时红帽和切口之间的缝（像素）。 */
    private float gapPx;

    TrackPointView(Context c) {
        super(c);
        density = Style.density(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1, Math.round(1.5f * density)));
    }

    void setPressedLook(boolean on) {
        if (on == pressed) return;
        pressed = on;
        invalidate();
    }

    /**
     * 嵌在键里（只画红帽，键自己切口）还是不压键（一圈淡圆），gap = 嵌在键里时红帽和切口之间的缝（像素，KeyboardPlan.Sizes.stickGap）。
     * KeyboardScreen 量尺寸时按 KeyboardPlan 的摆法设。
     */
    void setEmbedded(boolean on, float gap) {
        if (on == embedded && gap == gapPx) return;
        embedded = on;
        gapPx = gap;
        invalidate();
    }

    /** 嵌在键里吗（{@link #setEmbedded}）。 */
    boolean isEmbedded() {
        return embedded;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float half = Math.min(cx, cy);
        if (half <= 0) return;
        Theme t = Style.theme();   // 画的时候现取：换主题只要 invalidate
        float sw = ring.getStrokeWidth();
        float r;
        if (embedded) {
            r = Math.max(1, half - gapPx / 2);
            if (pressed) {   // 缝的里半圈（红帽的边到范围的边）描一圈，不碰键上的切口
                ring.setColor(t.focus);
                float w = Math.max(1, Math.min(sw, half - r));
                ring.setStrokeWidth(w);
                c.drawCircle(cx, cy, half - w / 2, ring);
                ring.setStrokeWidth(sw);
            }
        } else {
            ring.setColor(pressed ? t.focus : t.ring);
            c.drawCircle(cx, cy, half - sw, ring);
            r = half * CAP_FREE;
        }
        int red = pressed ? Theme.blend(t.stick, 0xFFFFFFFF, 0.2f) : t.stick;
        cap.setColor(red);
        c.drawCircle(cx, cy, r, cap);
        dot.setColor(Theme.blend(red, 0xFF000000, 0.35f));   // 凹点：红色压暗 35%
        RoundPad.dots(c, cx, cy, r, dot, density);
    }
}
