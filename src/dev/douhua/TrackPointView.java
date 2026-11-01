package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 小红点的样子（键盘页 ⋯ 菜单里"指点"选小红点时）：整块是按得到的范围（正方形），正中一个红色圆帽，帽上 3 × 3 九个小凹点
 * （同空中鼠标页的圆钮，RoundPad.dots）。两种画法（2026-11-01 照真的 ThinkPad 键盘放进键盘里以后）：
 * - 嵌在键里（{@link #setEmbedded}，挨着的四个键各让出一个角）：先用页面底色画一个方块（角上只圆 {@link #HOLE_CORNER_DP}）盖住那四个角——
 *   键上挖出的洞，洞的边就是按得到的范围；红帽占洞宽的 {@link #CAP_EMBEDDED}，按着时洞的边描一圈 focus 色。
 * - 不压键（分裂时两半中间那一列、键盘外面）：一圈淡的圆标出范围，红帽占范围的 {@link #CAP_FREE}（64dp 的范围约 45dp 的帽），按着时圆圈换 focus 色。
 * 按着时帽子亮一点。红点一直是红的（Theme.stick，按主题调亮 / 调暗到对页面和触控板底色都 ≥ 3:1）。不画手指偏了多少：服务接管时 App 收不到触点。
 * 触摸不在这里处理：分流（TouchRouter → TrackPoint）按报上去的区域认它。
 */
final class TrackPointView extends View {
    /** 红帽直径占范围边长多少：不压键时（外面一圈淡圆）、嵌在键里时（洞只比帽子大一圈）。 */
    static final float CAP_FREE = 0.7f;
    static final float CAP_EMBEDDED = 0.8f;
    /**
     * 嵌在键里时洞的圆角（dp）。洞要和按得到的范围一样大：报给分流的区域、面板自己收触摸时的洞（KeyFingers）、这个 view 自己收触摸都是整个正方形，
     * 角上露出的一点键看着是键、按上去却归红点（分流时轻点 = 左键）。原来圆角是半边长的 0.6（手机竖屏约 13dp），四个角各露出 g / h / v / b 约 36dp²、
     * 对角线方向深约 5dp；现在只圆 2dp，露出的不到 1dp²、深不到 1dp。
     */
    static final float HOLE_CORNER_DP = 2f;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;
    private boolean pressed;
    private boolean embedded;

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

    /** 嵌在键里（画成键上挖的洞）还是不压键（一圈淡圆）。KeyboardScreen 量尺寸时按 KeyboardPlan 的摆法设。 */
    void setEmbedded(boolean on) {
        if (on == embedded) return;
        embedded = on;
        invalidate();
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
            box.set(cx - half, cy - half, cx + half, cy + half);
            float corner = Math.min(HOLE_CORNER_DP * density, half);
            hole.setColor(t.bg);
            c.drawRoundRect(box, corner, corner, hole);
            if (pressed) {
                ring.setColor(t.focus);
                box.inset(sw / 2, sw / 2);
                c.drawRoundRect(box, corner, corner, ring);
            }
            r = half * CAP_EMBEDDED;
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
