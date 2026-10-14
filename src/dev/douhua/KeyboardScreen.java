package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * 键盘界面：一条工具栏（收起 / 调整，可以放上面或下面），其余是键盘；键盘没占满时，键盘上方空出来的地方是小触控板。
 *
 * 键盘的大小和位置可以调（照 Gboard 的"调整大小"）：进入调整模式后，拖键盘的上下左右边改大小、拖中间整体挪位置。
 * 横屏和竖屏各记一份。调整模式下服务不接管触摸（拖动要 App 自己收触摸）。
 *
 * 轻微对齐（不能太强，手机屏小）：
 * - 拖中间时明显是上下拖就只上下动，明显是左右拖就只左右动；之后侧向又拖出一大截才放开限制。
 * - 吸附只作用于"显示的位置"，手指的位置照常累计，继续拖就能拖过吸附点；吸附范围 SNAP_DP。
 * - 挪位置 / 改宽度时，居中优先于贴边：缩一点宽度不会被拽回两边贴边。吸上时轻震一下。
 */
final class KeyboardScreen extends ViewGroup implements KeyboardPanel.PreviewHost {
    interface Listener {
        /** 调整完一次（手指抬起），存起来。rect 是键盘在可用区域里的位置，0–1。 */
        void onKeyboardRectChanged(boolean landscape, float[] rect);

        /** 吸附到了居中 / 边缘（轻震一下）。 */
        void onKeyboardSnap();
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_MOVE = 1;
    private static final int HANDLE_LEFT = 2;
    private static final int HANDLE_TOP = 3;
    private static final int HANDLE_RIGHT = 4;
    private static final int HANDLE_BOTTOM = 5;
    private static final float MIN_WIDTH = 0.4f;
    private static final float MIN_HEIGHT = 0.25f;
    private static final float SNAP_DP = 10;
    /** 拖中间时，先拖出这么远再判断方向。 */
    private static final float AXIS_DECIDE_DP = 10;
    /** 锁定方向后，侧向累计超过这么远就放开限制。 */
    private static final float AXIS_RELEASE_DP = 48;
    private static final int AXIS_UNDECIDED = 0;
    private static final int AXIS_FREE = 1;
    private static final int AXIS_VERTICAL = 2;
    private static final int AXIS_HORIZONTAL = 3;

    private final View toolbar;
    private final TrackpadView miniPad;
    private final KeyboardPanel keyboard;
    private final Listener listener;
    private final int toolbarHeight;
    private final int gap;
    private final int grab;
    private final int minPadHeight;

    /** 键盘在可用区域里的位置 {左, 上, 右, 下}，0–1。 */
    private final float[] rect = new float[4];
    private boolean adjusting;
    private boolean toolbarBottom;
    private int handle = HANDLE_NONE;
    private float lastX, lastY;
    /** 手指拖到的位置（不含吸附）；rect 是吸附后显示的位置。 */
    private final float[] raw = new float[4];
    private int axis;
    private float moveX, moveY;
    /** 当前吸上了哪些（位标记），用来在新吸上时震一下。 */
    private int snapped;

    // 可用区域（工具栏下面），像素
    private int areaLeft, areaTop, areaWidth, areaHeight;
    private boolean padUsable;

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    // ── 按键放大提示 ──
    /** 松开后再留这么久才收起：打得快也来得及看清。 */
    private static final long PREVIEW_LINGER_MS = 90;
    private final Paint previewFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF previewRect = new RectF();
    private String previewLabel;
    private View previewKey;
    private final Runnable hidePreview = () -> {
        previewLabel = null;
        previewKey = null;
        invalidate();
    };

    KeyboardScreen(Context context, View toolbar, TrackpadView miniPad, KeyboardPanel keyboard, Listener listener) {
        super(context);
        this.toolbar = toolbar;
        this.miniPad = miniPad;
        this.keyboard = keyboard;
        this.listener = listener;
        toolbarHeight = Style.dp(context, 48);
        gap = Style.dp(context, 8);
        grab = Style.dp(context, 28);
        minPadHeight = Style.dp(context, 56);
        addView(toolbar);
        addView(miniPad);
        addView(keyboard);
        setWillNotDraw(false);

        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Style.dp(context, 2));
        frame.setColor(0xFF8AB4F8);
        shade.setColor(0xB0000000);
        grip.setColor(0xFF8AB4F8);
        hint.setColor(0xFFDDDDDD);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, getResources().getDisplayMetrics()));
        previewFill.setColor(0xFF2E2E2E);
        previewBorder.setStyle(Paint.Style.STROKE);
        previewBorder.setStrokeWidth(Style.dp(context, 1.5f));
        previewBorder.setColor(0xFF8A8A8A);
        previewText.setColor(0xFFF2F2F2);
        previewText.setTextAlign(Paint.Align.CENTER);
        keyboard.setPreviewHost(this);
    }

    // ═══════════ 按键放大提示 ═══════════

    /** 在按下的键正上方浮出放大的字（不挡住按着的键）；上面没地方时贴着本容器的上边。 */
    @Override
    public void showKeyPreview(View key, String label) {
        removeCallbacks(hidePreview);
        int[] me = new int[2];
        int[] k = new int[2];
        getLocationInWindow(me);
        key.getLocationInWindow(k);
        float kx = k[0] - me[0];
        float ky = k[1] - me[1];
        float h = Math.max(key.getHeight() * 1.7f, Style.dp(getContext(), 52));
        float w = Math.max(key.getWidth() * 1.5f, h * 0.8f);
        float left = Math.max(0, Math.min(getWidth() - w, kx + key.getWidth() / 2f - w / 2));
        float top = Math.max(0, ky - h - Style.dp(getContext(), 2));
        previewRect.set(left, top, left + w, top + h);
        previewText.setTextSize(h * 0.55f);
        previewLabel = label;
        previewKey = key;
        invalidate();
    }

    @Override
    public void hideKeyPreview(View key) {
        if (key == previewKey) postDelayed(hidePreview, PREVIEW_LINGER_MS);
    }

    void setRect(float[] r) {
        System.arraycopy(r, 0, rect, 0, 4);
        requestLayout();
    }

    boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    boolean isAdjusting() {
        return adjusting;
    }

    void setToolbarAtBottom(boolean bottom) {
        toolbarBottom = bottom;
        requestLayout();
        invalidate();
    }

    void setAdjusting(boolean on) {
        adjusting = on;
        invalidate();
    }

    /** 键盘上方空出来的地方够不够当触控板。 */
    boolean isPadUsable() {
        return padUsable;
    }

    // ═══════════ 布局 ═══════════

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        areaLeft = 0;
        areaTop = toolbarBottom ? 0 : toolbarHeight + gap;
        areaWidth = w;
        areaHeight = Math.max(0, h - toolbarHeight - gap);
        toolbar.measure(exactly(w), exactly(toolbarHeight));
        RectF k = keyboardRect();
        keyboard.measure(exactly(Math.round(k.width())), exactly(Math.round(k.height())));
        int padHeight = Math.round(k.top) - areaTop - gap;
        padUsable = padHeight >= minPadHeight;
        miniPad.measure(exactly(w), exactly(Math.max(0, padHeight)));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int ty = toolbarBottom ? getHeight() - toolbarHeight : 0;
        toolbar.layout(0, ty, toolbar.getMeasuredWidth(), ty + toolbarHeight);
        RectF k = keyboardRect();
        keyboard.layout(Math.round(k.left), Math.round(k.top), Math.round(k.right), Math.round(k.bottom));
        if (padUsable) {
            miniPad.layout(0, areaTop, getWidth(), areaTop + miniPad.getMeasuredHeight());
        } else {
            miniPad.layout(0, 0, 0, 0);
        }
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
    }

    private RectF keyboardRect() {
        return new RectF(areaLeft + rect[0] * areaWidth, areaTop + rect[1] * areaHeight,
                areaLeft + rect[2] * areaWidth, areaTop + rect[3] * areaHeight);
    }

    // ═══════════ 调整模式 ═══════════

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (previewLabel != null && !adjusting) {
            float r = Style.dp(getContext(), 10);
            canvas.drawRoundRect(previewRect, r, r, previewFill);
            canvas.drawRoundRect(previewRect, r, r, previewBorder);
            Paint.FontMetrics fm = previewText.getFontMetrics();
            float baseline = previewRect.centerY() - (fm.ascent + fm.descent) / 2;
            canvas.drawText(previewLabel, previewRect.centerX(), baseline, previewText);
        }
        if (!adjusting) return;
        RectF k = keyboardRect();
        canvas.drawRect(k, shade);
        canvas.drawRect(k, frame);
        float len = Style.dp(getContext(), 36);
        float thick = Style.dp(getContext(), 6);
        float cx = k.centerX();
        float cy = k.centerY();
        drawGrip(canvas, cx - len / 2, k.top - thick / 2, cx + len / 2, k.top + thick / 2);
        drawGrip(canvas, cx - len / 2, k.bottom - thick / 2, cx + len / 2, k.bottom + thick / 2);
        drawGrip(canvas, k.left - thick / 2, cy - len / 2, k.left + thick / 2, cy + len / 2);
        drawGrip(canvas, k.right - thick / 2, cy - len / 2, k.right + thick / 2, cy + len / 2);
        canvas.drawText(getContext().getString(R.string.kb_adjust_hint), cx, cy, hint);
    }

    private void drawGrip(Canvas c, float l, float t, float r, float b) {
        tmp.set(l, t, r, b);
        c.drawRoundRect(tmp, b - t, b - t, grip);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        // 调整模式下工具栏照常能点
        boolean inToolbar = toolbarBottom ? e.getY() >= getHeight() - toolbarHeight : e.getY() <= toolbarHeight;
        return adjusting && !inToolbar;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!adjusting) return false;
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handle = pickHandle(x, y);
                lastX = x;
                lastY = y;
                System.arraycopy(rect, 0, raw, 0, 4);
                axis = AXIS_UNDECIDED;
                moveX = moveY = 0;
                snapped = 0;
                return handle != HANDLE_NONE;
            case MotionEvent.ACTION_MOVE:
                drag((x - lastX) / areaWidth, (y - lastY) / areaHeight);
                lastX = x;
                lastY = y;
                requestLayout();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (handle != HANDLE_NONE) listener.onKeyboardRectChanged(isLandscape(), rect.clone());
                handle = HANDLE_NONE;
                return true;
            default:
                return true;
        }
    }

    private int pickHandle(float x, float y) {
        RectF k = keyboardRect();
        boolean inX = x > k.left - grab && x < k.right + grab;
        boolean inY = y > k.top - grab && y < k.bottom + grab;
        if (inX && Math.abs(y - k.top) < grab) return HANDLE_TOP;
        if (inX && Math.abs(y - k.bottom) < grab) return HANDLE_BOTTOM;
        if (inY && Math.abs(x - k.left) < grab) return HANDLE_LEFT;
        if (inY && Math.abs(x - k.right) < grab) return HANDLE_RIGHT;
        if (k.contains(x, y)) return HANDLE_MOVE;
        return HANDLE_NONE;
    }

    /** dx / dy 是占可用区域的比例。先更新手指位置 raw，再按吸附规则算出显示位置 rect。 */
    private void drag(float dx, float dy) {
        switch (handle) {
            case HANDLE_MOVE: {
                float[] d = lockAxis(dx, dy);
                float w = raw[2] - raw[0];
                float h = raw[3] - raw[1];
                raw[0] = clamp(raw[0] + d[0], 0, 1 - w);
                raw[1] = clamp(raw[1] + d[1], 0, 1 - h);
                raw[2] = raw[0] + w;
                raw[3] = raw[1] + h;
                break;
            }
            case HANDLE_LEFT:
                raw[0] = clamp(raw[0] + dx, 0, raw[2] - MIN_WIDTH);
                break;
            case HANDLE_RIGHT:
                raw[2] = clamp(raw[2] + dx, raw[0] + MIN_WIDTH, 1);
                break;
            case HANDLE_TOP:
                raw[1] = clamp(raw[1] + dy, 0, raw[3] - MIN_HEIGHT);
                break;
            case HANDLE_BOTTOM:
                raw[3] = clamp(raw[3] + dy, raw[1] + MIN_HEIGHT, 1);
                break;
            default:
                return;
        }
        int now = snap();
        if ((now & ~snapped) != 0) listener.onKeyboardSnap();   // 新吸上了什么：轻震
        snapped = now;
    }

    /** 拖中间时的方向限制：明显上下拖就只上下动，明显左右拖就只左右动。 */
    private float[] lockAxis(float dx, float dy) {
        float px = dx * areaWidth;
        float py = dy * areaHeight;
        moveX += px;
        moveY += py;
        float decide = Style.dp(getContext(), AXIS_DECIDE_DP);
        if (axis == AXIS_UNDECIDED && Math.hypot(moveX, moveY) > decide) {
            if (Math.abs(moveY) > 2 * Math.abs(moveX)) axis = AXIS_VERTICAL;
            else if (Math.abs(moveX) > 2 * Math.abs(moveY)) axis = AXIS_HORIZONTAL;
            else axis = AXIS_FREE;
            // 判断方向前拖的这一小段补上（只补允许的方向），键盘不会落后手指
            float bx = axis == AXIS_VERTICAL ? 0 : moveX / areaWidth;
            float by = axis == AXIS_HORIZONTAL ? 0 : moveY / areaHeight;
            moveX = moveY = 0;
            return new float[]{bx, by};
        }
        float release = Style.dp(getContext(), AXIS_RELEASE_DP);
        if (axis == AXIS_VERTICAL) {
            if (Math.abs(moveX) > release) axis = AXIS_FREE;   // 侧向拖出一大截：放开
            else return new float[]{0, dy};
        } else if (axis == AXIS_HORIZONTAL) {
            if (Math.abs(moveY) > release) axis = AXIS_FREE;
            else return new float[]{dx, 0};
        } else if (axis == AXIS_UNDECIDED) {
            return new float[]{0, 0};   // 方向还没判断出来，先不动，免得一开始就歪
        }
        return new float[]{dx, dy};
    }

    /** 按 raw 算显示位置 rect（见 KeyboardSnap），返回吸上了哪些。 */
    private int snap() {
        float sx = Style.dp(getContext(), SNAP_DP) / Math.max(1f, areaWidth);
        float sy = Style.dp(getContext(), SNAP_DP) / Math.max(1f, areaHeight);
        int h;
        switch (handle) {
            case HANDLE_MOVE: h = KeyboardSnap.MOVE; break;
            case HANDLE_LEFT: h = KeyboardSnap.LEFT; break;
            case HANDLE_RIGHT: h = KeyboardSnap.RIGHT; break;
            case HANDLE_TOP: h = KeyboardSnap.TOP; break;
            case HANDLE_BOTTOM: h = KeyboardSnap.BOTTOM; break;
            default: return 0;
        }
        return KeyboardSnap.apply(raw, rect, h, sx, sy);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
