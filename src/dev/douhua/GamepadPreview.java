package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * 设置页"手柄"里一块手柄页的样子：按 {@link GamepadPlan} 摆在主要目标窗口那个比例（Fold 内屏分屏下半边，930 × 350dp）里、缩小了画，
 * 画法同手柄页（{@link GamepadPainter}，没有按着的键）。labels = 写不写字（上面那块大预览写，预设网格里的小图不写）。
 * 颜色用传进来的主题（设置页不碰主界面的当前主题）；一直按物理左右摆（阿拉伯文下也不镜像，同手柄页）。
 */
final class GamepadPreview extends View {
    /** 按这个窗口大小（dp）摆。 */
    static final float PAGE_W = 930, PAGE_H = 350;
    private static final float[] EDGES = {20, 20, 20, 20};

    private final boolean labels;
    private final float density;
    private final GamepadPainter painter = new GamepadPainter();
    private final Hid.Gamepad neutral = new Hid.Gamepad();
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dots = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private GamepadSpec spec;
    private GamepadPlan.Plan plan;
    private Theme theme = Theme.DEFAULT;
    private boolean borders = true;

    GamepadPreview(Context c, boolean labels) {
        super(c);
        this.labels = labels;
        density = Style.density(c);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        frame.setStyle(Paint.Style.STROKE);
        dots.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(GamepadSpec s, Theme t, boolean keyBorders) {
        if (s == null) return;
        if (spec == null || !s.key().equals(spec.key())) plan = GamepadPlan.plan(s, PAGE_W, PAGE_H, EDGES);
        spec = s;
        theme = t;
        borders = keyBorders;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * PAGE_H / PAGE_W));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = (labels ? 12 : 8) * density;
        box.set(0, 0, w, h);
        bg.setColor(theme.bg);
        canvas.drawRoundRect(box, r, r, bg);
        frame.setStrokeWidth(Math.max(1, density));
        frame.setColor(0xFF5F6368);   // 纯黑的页面贴着设置页的深色底也看得出边（同主题色块）
        box.inset(frame.getStrokeWidth() / 2, frame.getStrokeWidth() / 2);
        canvas.drawRoundRect(box, r, r, frame);
        if (plan == null || w <= 0) return;
        float k = w / PAGE_W;
        painter.draw(canvas, spec, plan, neutral, theme, borders, k, labels ? 5 * density : 0);
        if (labels) {   // 顶上正中的 ⋯（手柄页上是个 TextView，这里照样写一个；同 Style.bare 用强调色 menuDots）
            dots.setColor(theme.menuDots);
            dots.setTextSize(18 * k * 1.4f);
            float[] m = plan.menu;
            Paint.FontMetrics fm = dots.getFontMetrics();
            canvas.drawText("⋯", (m[0] + m[2]) / 2 * k, (m[1] + m[3]) / 2 * k - (fm.ascent + fm.descent) / 2, dots);
        }
    }
}
