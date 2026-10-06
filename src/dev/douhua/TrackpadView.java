package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * 触控板区域的画面：一圈暗灰色边框和底部一行状态字，其余全黑（外屏要一直亮着，黑色省电）。
 * 触摸不在这里处理：豆花服务独占触摸屏、按这个区域在屏幕上的位置识别手势。
 */
final class TrackpadView extends View {
    private static final int COLOR_OUTLINE = 0xFF2A2A2A;
    private static final int COLOR_STATUS_OK = 0xFF4A4A4A;
    private static final int COLOR_STATUS_BAD = 0xFF8A5A30;

    private final float density;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String status = "";
    private boolean statusOk;
    /** 边缘滚动带的宽度（像素），0 = 不画。 */
    private float edgeWidth;
    private boolean edgeVertical;
    private boolean edgeHorizontal;
    private final Paint edgeGuide = new Paint(Paint.ANTI_ALIAS_FLAG);

    TrackpadView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * density);
        outline.setColor(COLOR_OUTLINE);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        text.setTextAlign(Paint.Align.CENTER);
        edgeGuide.setStrokeWidth(density);
        edgeGuide.setColor(0xFF1E1E1E);
    }

    /** 边缘滚动开着时，在边缘带内侧画浅线：左右两条（竖向滚动）、上下两条（横向滚动）。 */
    void setEdgeGuides(float widthPx, boolean vertical, boolean horizontal) {
        edgeWidth = widthPx;
        edgeVertical = vertical;
        edgeHorizontal = horizontal;
        invalidate();
    }

    void setStatus(String status, boolean ok) {
        this.status = status;
        this.statusOk = ok;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = outline.getStrokeWidth() / 2;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = 18 * density;
        canvas.drawRoundRect(box, r, r, outline);
        if (edgeWidth > 0 && edgeVertical) {
            canvas.drawLine(edgeWidth, r, edgeWidth, getHeight() - r, edgeGuide);
            canvas.drawLine(getWidth() - edgeWidth, r, getWidth() - edgeWidth, getHeight() - r, edgeGuide);
        }
        if (edgeWidth > 0 && edgeHorizontal) {
            canvas.drawLine(r, edgeWidth, getWidth() - r, edgeWidth, edgeGuide);
            canvas.drawLine(r, getHeight() - edgeWidth, getWidth() - r, getHeight() - edgeWidth, edgeGuide);
        }
        if (!status.isEmpty()) {
            text.setColor(statusOk ? COLOR_STATUS_OK : COLOR_STATUS_BAD);
            canvas.drawText(status, getWidth() / 2f, getHeight() - 14 * density, text);
        }
    }
}
