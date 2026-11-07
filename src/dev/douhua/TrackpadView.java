package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

/**
 * 触控板区域的画面：底色、背景图（可选，PadImage）、边缘滚动带的细线、底部一行状态字、一圈边框，颜色跟当前主题（Style.theme，画的时候现取，换主题只要 invalidate）。
 * 纯黑主题是全黑底 + 暗灰边框（外屏要一直亮着，黑色省电）。
 * 有背景图时：状态字、触控板顶上的 ⋯ 下面各垫一块不透明的触控板底色（字色不变，对比度和没图时一模一样），
 * 边缘滚动带的细线下面垫一条 3dp 的底色；边框最后画，盖住图的边。
 * 触摸不在这里处理：豆花服务独占触摸屏、按这个区域在屏幕上的位置识别手势（背景图只是画面，"从触控板出发划出去照样动光标"不受影响）。
 */
final class TrackpadView extends View {
    private final float density;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String status = "";
    private boolean statusOk;
    /** 状态字能点（服务没开 / 没响应时，点了打开设置页）：画下划线。点的判断在 MainActivity。 */
    private boolean statusTappable;
    private final int[] onScreen = new int[2];
    /** 边缘滚动带的宽度（像素），0 = 不画。 */
    private float edgeWidth;
    private boolean edgeVertical;
    private boolean edgeHorizontal;
    private final Paint edgeGuide = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 背景图（两块触控板共用一份，null = 不画）和这块自己的画笔。 */
    private PadImage image;
    private final PadImage.Brush brush = new PadImage.Brush();
    /** 有图时垫在字、⋯、细线下面的不透明底色。 */
    private final Paint under = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF inner = new RectF();
    private final RectF chip = new RectF();
    /** 有材质的主题的触控板纹理（Theme.Material、PadTexture）：BitmapShader 平铺，叠在底色上、背景图下面。 */
    private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 纹理那一块只生成一次（进程里按 种类 × 密度 × 浓淡 缓存；生成一块 256 × 256 的要几毫秒）。 */
    private static android.graphics.Shader textureShader;
    private static String textureKey;
    /** 触控板顶上的 ⋯ 的中心（本 View 坐标，键盘页 KeyboardScreen、主界面 PadStack 摆的），NaN = 这块触控板上没有 ⋯。 */
    private float menuX = Float.NaN, menuY = Float.NaN;
    /** 状态字两边各让出多宽（像素）：默认圆角那么宽（18dp）；主界面下面两个角有小按钮时让过按钮（{@link #setStatusSide}）。 */
    private float statusSide;

    TrackpadView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        text.setTextAlign(Paint.Align.CENTER);
        edgeGuide.setStrokeWidth(density);
        statusSide = 18 * density;
    }

    void setImage(PadImage image) {
        this.image = image;
        invalidate();
    }

    /** 背景图来了 / 没了 / 设置变了：重画；没了就放掉画笔里对旧位图的引用。 */
    void imageChanged() {
        if (image == null || !image.shown()) brush.clear();
        invalidate();
    }

    /** ⋯ 摆在这块触控板上时它的中心（本 View 坐标）；不在这块上传 NaN。有图时 ⋯ 下面垫一块底色。 */
    void setMenuChip(float x, float y) {
        if (Float.compare(x, menuX) == 0 && Float.compare(y, menuY) == 0) return;
        menuX = x;
        menuY = y;
        invalidate();
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

    /**
     * 状态字两边各让出多宽（像素，不小于圆角的 18dp）：主界面左下、右下角有小按钮时（2026-11-01 起四个角默认都有）让过按钮，
     * 长的状态字在两个按钮中间用省略号截掉，不压在按钮上。
     */
    void setStatusSide(float px) {
        float side = Math.max(18 * density, px);
        if (side == statusSide) return;
        statusSide = side;
        invalidate();
    }

    void setStatusTappable(boolean tappable) {
        if (tappable == statusTappable) return;
        statusTappable = tappable;
        text.setUnderlineText(tappable);
        invalidate();
    }

    /**
     * 屏幕坐标 (rawX, rawY) 落在底部状态字上吗：只算文字实际画出来的那一块，四周各放宽一点手指好点
     * （左右 12dp、上下 8dp）。不能整条宽度都算：底边是横向边缘滚动带，两头还有左下、右下角的小按钮。
     */
    boolean statusHit(float rawX, float rawY) {
        if (status.isEmpty() || !isShown() || getWidth() == 0) return false;
        getLocationOnScreen(onScreen);
        Paint.FontMetrics fm = text.getFontMetrics();
        float half = Math.min(text.measureText(status), statusRoom()) / 2 + 12 * density;
        float cx = onScreen[0] + getWidth() / 2f;
        float baseline = onScreen[1] + statusBaseline();
        return rawX >= cx - half && rawX < cx + half
                && rawY >= baseline + fm.ascent - 8 * density && rawY < baseline + fm.descent + 8 * density;
    }

    /** 状态字最多画多宽：两边各让出 statusSide（圆角那么宽，或者让过下面两个角的小按钮）。画和点的判断共用。 */
    private float statusRoom() {
        return Math.max(0, getWidth() - 2 * statusSide);
    }

    /** 状态字的基线（相对这个 View 顶边）：画和点的判断共用。 */
    private float statusBaseline() {
        return getHeight() - 14 * density;
    }

    /** 这一套主题的纹理（同样的种类、密度、浓淡复用同一张小图）。 */
    private static android.graphics.Shader textureShader(Theme.Material m, float density) {
        String key = m.texture + "@" + density + "x" + m.textureStrength + "/" + m.tint + (m.invert ? "i" : "");
        if (!key.equals(textureKey)) {
            int n = PadTexture.tileSize(m.texture, density);
            android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(PadTexture.pixels(m.texture, density, m.textureStrength, m.tint, m.invert), n, n,
                    android.graphics.Bitmap.Config.ARGB_8888);
            textureShader = new android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
            textureKey = key;
        }
        return textureShader;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Theme t = Style.theme();
        // 有材质的主题：边框只是一道 1dp 的细线（触控板靠纹理和底色跟键盘分开，粗描边看着假，2026-11-04 用户）
        outline.setStrokeWidth((t.material != null ? 1 : t.borderDp) * density);
        outline.setColor(t.padBorder);
        fill.setColor(t.padFill);
        edgeGuide.setColor(t.edge);
        under.setColor(t.padFill);   // 每套主题的 padFill 都不透明（PadFitTest 锁住），垫的块里的字和没图时一样看得清
        float stroke = outline.getStrokeWidth();
        float inset = stroke / 2;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = 18 * density;
        canvas.drawRoundRect(box, r, r, fill);
        if (t.material != null && t.material.texture != PadTexture.NONE) {
            grain.setShader(textureShader(t.material, density));
            canvas.drawRoundRect(box, r, r, grain);
        }
        // 背景图：和底色同一个圆角形状，按边框以内的内框摆（边框最后画，盖住图的边）
        inner.set(stroke, stroke, getWidth() - stroke, getHeight() - stroke);
        boolean onImage = image != null && image.draw(canvas, box, r, inner, brush);
        if (edgeWidth > 0 && edgeVertical) {
            edgeLine(canvas, edgeWidth, r, edgeWidth, getHeight() - r, onImage);
            edgeLine(canvas, getWidth() - edgeWidth, r, getWidth() - edgeWidth, getHeight() - r, onImage);
        }
        if (edgeWidth > 0 && edgeHorizontal) {
            edgeLine(canvas, r, edgeWidth, getWidth() - r, edgeWidth, onImage);
            edgeLine(canvas, r, getHeight() - edgeWidth, getWidth() - r, getHeight() - edgeWidth, onImage);
        }
        // ⋯ 字形周围一块 32 × 20dp 的底（不是整个 56 × 36 的触摸范围）；按下时 ⋯ 自己的按下底色照旧盖在上面
        if (onImage && menuX >= 0 && menuX <= getWidth() && menuY >= 0 && menuY <= getHeight()) {
            float hw = 16 * density, hh = 10 * density;
            chip.set(menuX - hw, menuY - hh, menuX + hw, menuY + hh);
            canvas.drawRoundRect(chip, hh, hh, under);
        }
        if (!status.isEmpty()) {
            text.setColor(statusOk ? t.status : t.statusBad);
            // 单行居中画，放不下就在末尾用省略号截掉（分屏时触控板窄、状态字长）：不截的话两头一起被裁，开头的原因看不见
            float room = statusRoom();
            CharSequence shown = text.measureText(status) > room
                    ? TextUtils.ellipsize(status, text, room, TextUtils.TruncateAt.END) : status;
            if (onImage && shown.length() > 0) {
                // 字后面垫一块全圆角的底：左右 8dp、上下 3dp
                Paint.FontMetrics fm = text.getFontMetrics();
                float half = text.measureText(shown, 0, shown.length()) / 2 + 8 * density;
                float cx = getWidth() / 2f, base = statusBaseline();
                chip.set(cx - half, base + fm.ascent - 3 * density, cx + half, base + fm.descent + 3 * density);
                float cr = chip.height() / 2;
                canvas.drawRoundRect(chip, cr, cr, under);
            }
            canvas.drawText(shown, 0, shown.length(), getWidth() / 2f, statusBaseline(), text);
        }
        canvas.drawRoundRect(box, r, r, outline);
    }

    /** 边缘滚动带的一条细线；在图上时先垫一条 3dp 宽的底色（任何图上都和没图时一样淡、但看得见）。 */
    private void edgeLine(Canvas canvas, float x0, float y0, float x1, float y1, boolean onImage) {
        if (onImage) {
            under.setStrokeWidth(3 * density);
            canvas.drawLine(x0, y0, x1, y1, under);
        }
        canvas.drawLine(x0, y0, x1, y1, edgeGuide);
    }
}
