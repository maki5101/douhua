package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * 一排左 / 中 / 右三个鼠标键（照 ThinkPad 小红点下面那三个）：键盘页选小红点时键盘最下面那排、触控板上沿 / 下沿的三键条都是它。
 * 中键窄、左右键宽（{@link #setMiddle}，宽度由 KeyboardPlan.middleKey 定，约 2.2 : 1 : 2.2，中键对着红点），没设时三个键平分；键与键之间隔 gap。
 * 键帽照主题画（底色、边框同按钮条，一直有边框），不写字，照真 ThinkPad（2026-11-01 对着联想用户指南的插图核过）：
 * 左 / 右键靠用户那条长边（下沿，屏幕下方那条边；三键条在触控板上沿时也一样）横着一道红线、几乎占满键宽；
 * 中键没有红线，上沿附近横着一排 7 个小点。真机的中键是上宽下窄的梯形，这里照旧画圆角矩形（和区域一样是矩形）。不写字也就不用翻译。
 * 触摸不在这里处理：服务接管 / 本地分流时按报上去的区域（Proto.REGION_MOUSE_BUTTON）认；服务连着没接管时 MainActivity 给每个键装触摸监听。
 */
final class MouseKeys extends ViewGroup {
    /** 下标 0 左、1 中、2 右对应的鼠标键位。 */
    static final int[] MASKS = {Proto.BUTTON_LEFT, Proto.BUTTON_MIDDLE, Proto.BUTTON_RIGHT};

    private final Key[] keys = new Key[3];
    private final int gap;
    /** 中键的左右边（本 view 里的 x）；midLeft < 0 = 没设，三个键平分。 */
    private int midLeft = -1, midRight = -1;

    MouseKeys(Context c, int gap) {
        super(c);
        this.gap = gap;
        for (int i = 0; i < 3; i++) {
            keys[i] = new Key(c, MASKS[i]);
            addView(keys[i]);
        }
    }

    /** 第 i 个键（0 左、1 中、2 右）。 */
    View key(int i) {
        return keys[i];
    }

    /** 按着的鼠标键位（掩码）画成按下的样子。 */
    void setPressedMask(int mask) {
        for (Key k : keys) k.setPressedLook((mask & k.mask) != 0);
    }

    /** 换了主题：重画。 */
    void applyTheme() {
        for (Key k : keys) k.invalidate();
    }

    /**
     * 中键从 left 到 right（本 view 里的 x，KeyboardPlan.middleKey 减去这一排的左边）：左键是 [0, left − gap)，右键是 [right + gap, 宽)。
     * 父 view 量尺寸、摆放时调，接着就量它（forceLayout：只标自己要重新量、重新摆，不在父 view 的测量途中往上请求布局）。
     */
    void setMiddle(int left, int right) {
        if (left == midLeft && right == midRight) return;
        midLeft = left;
        midRight = right;
        forceLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < 3; i++) {
            keys[i].measure(MeasureSpec.makeMeasureSpec(Math.max(0, right(i, w) - left(i, w)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < 3; i++) keys[i].layout(left(i, w), 0, right(i, w), b - t);
    }

    private int left(int i, int w) {
        if (midLeft >= 0 && midRight <= w) return i == 0 ? 0 : i == 1 ? midLeft : midRight + gap;
        return Math.round(i * (w + gap) / 3f);
    }

    private int right(int i, int w) {
        if (midLeft >= 0 && midRight <= w) return i == 0 ? midLeft - gap : i == 1 ? midRight : w;
        return Math.round((i + 1) * (w + gap) / 3f) - gap;
    }

    /** 一个鼠标键。 */
    static final class Key extends View {
        final int mask;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float density;
        private boolean pressed;

        Key(Context c, int mask) {
            super(c);
            this.mask = mask;
            density = Style.density(c);
            stroke.setStyle(Paint.Style.STROKE);
        }

        void setPressedLook(boolean on) {
            if (on == pressed) return;
            pressed = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme t = Style.theme();   // 画的时候现取：换主题只要 invalidate
            float sw = Math.max(1, Math.round(t.borderDp * density));
            box.set(sw / 2, sw / 2, getWidth() - sw / 2, getHeight() - sw / 2);
            float radius = 10 * density;
            fill.setColor(Theme.over(pressed ? t.pressed : t.key, t.bg));
            c.drawRoundRect(box, radius, radius, fill);
            stroke.setStrokeWidth(sw);
            stroke.setColor(t.border);
            c.drawRoundRect(box, radius, radius, stroke);
            if (mask == Proto.BUTTON_MIDDLE) {
                // 中键：上沿附近一排 7 个小点（真机在靠键盘那一侧）；点距最多 5dp，键窄时（三个一样宽）收紧，整排不进两头的圆角
                mark.setColor(t.text);
                float step = Math.min(5 * density, Math.max(0, getWidth() - 2 * radius - 4 * density) / 6f);
                float dr = Math.min(1.2f * density, step * 0.3f);
                float cy = sw + 7 * density;
                for (int i = -3; i <= 3; i++) c.drawCircle(getWidth() / 2f + i * step, cy, dr, mark);
                return;
            }
            // 左 / 右键：靠用户那条长边（下沿）横着一道红线，2dp 粗，两头各让开圆角、几乎占满键宽
            mark.setColor(t.stick);
            float lw = 2 * density;
            float bottom = getHeight() - sw - 3 * density;
            float inset = sw + 5 * density;
            box.set(inset, bottom - lw, getWidth() - inset, bottom);
            c.drawRoundRect(box, lw / 2, lw / 2, mark);
        }
    }
}
