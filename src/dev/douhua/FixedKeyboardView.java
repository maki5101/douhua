package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 实体键盘的面板（第 1B 批，2026-11-01）：一个 View 自己画全部键帽（不学 KeyboardPanel 每个键一个 TextView：L 形回车没法用矩形背景画，
 * 一个键画两三层字，全尺寸一百多个自动缩字号的 TextView 建起来会卡主线程）。几何、区域、哪根手指按的是哪个键、键帽上画哪几个字
 * 都在纯 Java 的 {@link FixedPanel}（离线测），这里只按它的结果画和收触摸。
 *
 * 平时触摸由服务处理（按报上去的区域分键）；服务没接管、也没走本地分流时，面板自己收触摸（{@link #onTouchEvent}，按手指编号分给键，
 * 同 KeyboardPanel：KeyFingers + TouchRouter.nearestKey），嵌着的小红点圆、让给三个鼠标键的空位不算任何键。
 * 颜色全从当前主题取（Style.theme，主次键帽同 KeyRows.functionKey）；修饰键左右八个都画高亮；锁住 Shift / 第三层时会出的那个字照常、别的压暗；
 * 死键用强调色（activeText，对键帽够 4.5:1）。被小红点切到的键沿圆弧切掉一块，直接用豆花布局那个 Style.CutCap 画（圆角矩形减去圆）。
 */
final class FixedKeyboardView extends View implements KeyboardFace {
    private final KeyboardPanel.Listener listener;
    private KeyboardPanel.PreviewHost previewHost;
    private final FixedPanel model = new FixedPanel();
    private final KeyFingers fingers = new KeyFingers();
    private final int gap;
    private final float corner, pad, labelRoom;
    private int modifiers;
    /** 每个键（model.keys 的下标）按着没有。 */
    private boolean[] pressed = new boolean[0];
    /** 每个键的每个键帽（FixedPanel.capBoxes）的形状：按 {@link #applyTheme} 时的边框粗细缩进半条边框（同 GradientDrawable / Style.CutCap）。 */
    private Path[][] paths = new Path[0][];
    private float strokeW;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorFilter emoji;
    private final RectF tmp = new RectF();

    FixedKeyboardView(Context context, KeyboardPanel.Listener listener) {
        super(context);
        this.listener = listener;
        gap = Style.dp(context, 2);
        corner = Style.dp(context, 10);
        pad = Style.dp(context, 3);
        labelRoom = Style.sp(context, KeyboardScreen.LABEL_ROOM_SP);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        applyTheme();
    }

    /** 换一块键盘（设置页改了语言 / 形状 / 尺寸 / 模式）。按着的键先松开。 */
    void setBoard(FixedLayout layout, FixedLegends.Table legends) {
        dropTouches();
        model.setBoard(layout, legends);
        resizeState();
        requestLayout();
        invalidate();
    }

    /** 当前这块（没设过 / 读不出数据是 null：KeyboardScreen 就照样显示豆花布局）。 */
    FixedLayout layout() {
        return model.layout();
    }

    private void resizeState() {
        pressed = new boolean[model.keys().size()];
        paths = new Path[model.keys().size()][];
    }

    @Override
    public void setPreviewHost(KeyboardPanel.PreviewHost host) {
        previewHost = host;
    }

    @Override
    public void setModifiers(int mask) {
        if (mask == modifiers) return;
        modifiers = mask;
        invalidate();
    }

    @Override
    public void setMenuKey(boolean on) {
        if (on == model.menuKey()) return;
        dropTouches();
        model.setMenuKey(on);
        resizeState();
        requestLayout();
    }

    @Override
    public void setHole(int[] hole, boolean spread) {
        if (Arrays.equals(hole, model.hole())) return;
        dropTouches();
        model.setHole(hole);
        requestLayout();
    }

    @Override
    public void setStick(int[] hit, float cut) {
        int[] h = model.stickHit();
        if (Arrays.equals(hit, h) && (hit == null || cut == stickCut)) return;
        stickCut = hit == null ? 0 : cut;
        model.setStick(hit, cut);
        requestLayout();
    }

    private float stickCut;

    @Override
    public int[] menuKeyRect() {
        return model.menuKeyRect();
    }

    @Override
    public void collectRegions(List<int[]> regions) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        model.regions(loc[0], loc[1], regions);
    }

    @Override
    public void showPressed(int id, boolean down) {
        List<FixedLayout.Key> keys = model.keys();
        for (int i = 0; i < keys.size() && i < pressed.length; i++) {
            if (keys.get(i).id != id) continue;
            pressed[i] = down;
            preview(i, down);
        }
        invalidate();
    }

    @Override
    public void releaseTouches() {
        dropTouches();
    }

    @Override
    public void clearPressed() {
        for (int i = 0; i < pressed.length; i++) {
            if (pressed[i]) preview(i, false);
            pressed[i] = false;
        }
        invalidate();
    }

    @Override
    public void applyTheme() {
        Theme t = Style.theme();
        strokeW = Math.max(1, Math.round(t.borderDp * Style.density(getContext())));
        stroke.setStrokeWidth(strokeW);
        emoji = KeyboardPanel.grayEmoji();
        buildPaths();
        invalidate();
    }

    /** 面板自己收的手指按着的键松开（不算点击）、按下的样子和放大提示收掉（换键盘、换 ⋯ / 空位前）。 */
    private void dropTouches() {
        for (int index : fingers.cancel()) release(index, false);
        clearPressed();
    }

    // ═══════════ 摆放和画 ═══════════

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        model.place(w, h, gap, labelRoom);
        if (pressed.length != model.keys().size()) resizeState();
        buildPaths();
    }

    private void buildPaths() {
        int n = model.keys().size();
        if (paths.length != n) paths = new Path[n][];
        if (model.width() <= 0) return;
        float in = strokeW / 2;
        for (int i = 0; i < n; i++) {
            List<int[]> boxes = model.capBoxes(i);
            Path[] ps = new Path[boxes.size()];
            for (int j = 0; j < ps.length; j++) {
                int[] b = boxes.get(j);
                Path p = new Path();
                float[] s = model.shape(i);   // L 形回车：上半段 {左, 上, 右}、下半段 {左, 下}，见 FixedPanel.shape；别的键是四个角
                if (s.length >= 12) {
                    Path top = round(s[0] + in, s[1] + in, s[2] - in, s[11] - in);
                    Path low = round(s[8] + in, s[1] + in, s[4] - in, s[5] - in);
                    top.op(low, Path.Op.UNION);
                    p = top;
                } else {
                    p.addPath(round(b[0] + in, b[1] + in, b[2] - in, b[3] - in));
                }
                float[] c = model.cut(i);
                if (c != null) {
                    Path hole = new Path();
                    hole.addCircle(c[0], c[1], c[2] + in, Path.Direction.CW);
                    p.op(hole, Path.Op.DIFFERENCE);
                }
                ps[j] = p;
            }
            paths[i] = ps;
        }
    }

    private Path round(float l, float t, float r, float b) {
        Path p = new Path();
        float rad = Math.max(0, Math.min(corner, Math.min(r - l, b - t) / 2));
        tmp.set(l, t, Math.max(l, r), Math.max(t, b));
        p.addRoundRect(tmp, rad, rad, Path.Direction.CW);
        return p;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (model.layout() == null || model.width() <= 0) return;
        Theme t = Style.theme();
        boolean borders = Style.keyBorders();
        String space = getContext().getString(R.string.key_space);
        List<FixedLayout.Key> keys = model.keys();
        for (int i = 0; i < keys.size() && i < paths.length; i++) {
            if (paths[i] == null) continue;
            FixedLayout.Key k = keys.get(i);
            boolean active = FixedPanel.active(k, modifiers);
            boolean fn = model.functionKey(i) && !active;
            boolean down = i < pressed.length && pressed[i];
            int fillColor = down ? (fn ? t.fnPressed : t.pressed) : (fn ? t.fnKey : t.key);
            int strokeColor = active ? t.accent : t.border;
            float[] cut = model.cut(i);
            if (cut != null && !k.lShape()) {
                // 被小红点切掉一块：和豆花布局同一个画法（Style.CutCap：圆角矩形减去圆，边框沿圆弧走、外沿对齐键帽的边）
                int[] b = model.box(i);
                Style.CutCap cap = new Style.CutCap(corner, active || borders ? strokeW : 0, strokeColor, fillColor,
                        cut[0] - b[0], cut[1] - b[1], cut[2]);
                cap.setBounds(b[0], b[1], b[2], b[3]);
                cap.draw(canvas);
            } else {
                fill.setColor(fillColor);
                stroke.setColor(strokeColor);
                for (Path p : paths[i]) {
                    canvas.drawPath(p, fill);
                    if (active || borders) canvas.drawPath(p, stroke);
                }
            }
            int base = active ? t.activeText : fn ? t.fnText : t.text;
            List<FixedPanel.Glyph> glyphs = model.glyphs(i, modifiers, space);
            for (int[] b : model.capBoxes(i)) drawGlyphs(canvas, i, b, glyphs, base, t);
        }
    }

    /** 一个键帽上的字：正中 / 上下两层 / 角上小字，被切到的键整块字竖着挪（FixedPanel.shift）、角上小字放离切口最远的角。 */
    private void drawGlyphs(Canvas canvas, int i, int[] b, List<FixedPanel.Glyph> glyphs, int base, Theme t) {
        float w = b[2] - b[0], h = b[3] - b[1];
        if (w <= 0 || h <= 0) return;
        float shift = model.shift(i);
        boolean hasCorner = false;
        for (FixedPanel.Glyph g : glyphs) hasCorner |= g.slot == FixedPanel.Glyph.CORNER;
        float cx = (b[0] + b[2]) / 2f;
        // 有角上小字的两层字键，上下两个字往左让一点（角上的字在右边）
        float pairX = hasCorner ? b[0] + w * 0.42f : cx;
        float big = Math.min(Style.sp(getContext(), 18), h * 0.46f), mid = Math.min(Style.sp(getContext(), 14), h * 0.30f),
                small = Math.min(Style.sp(getContext(), 10), h * 0.22f), name = Math.min(Style.sp(getContext(), 13), h * 0.34f);
        boolean named = !model.keys().get(i).charKey();
        for (FixedPanel.Glyph g : glyphs) {
            int color = g.dead ? t.activeText : base;
            if (g.dim) color = (color & 0x00FFFFFF) | ((Math.round(((color >>> 24) & 0xFF) * 0.4f)) << 24);
            text.setColor(color);
            text.setColorFilter(g.text.equals("🌐") ? emoji : null);
            switch (g.slot) {
                case FixedPanel.Glyph.CENTER: {
                    float size = named && g.text.codePointCount(0, g.text.length()) > 1 ? name : big;
                    fit(g.text, size, w - 2 * pad);
                    text.setTextAlign(Paint.Align.CENTER);
                    drawCentered(canvas, g.text, cx, (b[1] + b[3]) / 2f + shift);
                    break;
                }
                case FixedPanel.Glyph.TOP:
                case FixedPanel.Glyph.BOTTOM: {
                    fit(g.text, mid, (hasCorner ? w * 0.6f : w) - 2 * pad);
                    text.setTextAlign(Paint.Align.CENTER);
                    float y = b[1] + h * (g.slot == FixedPanel.Glyph.TOP ? 0.30f : 0.70f) + shift;
                    drawCentered(canvas, g.text, pairX, y);
                    break;
                }
                default: {
                    fit(g.text, small, w * 0.45f);
                    int c = model.corner(i);
                    boolean right = c == FixedPanel.CORNER_BR || c == FixedPanel.CORNER_TR;
                    boolean bottom = c == FixedPanel.CORNER_BR || c == FixedPanel.CORNER_BL;
                    text.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
                    Paint.FontMetrics fm = text.getFontMetrics();
                    float x = right ? b[2] - pad - Style.dp(getContext(), 1) : b[0] + pad + Style.dp(getContext(), 1);
                    float y = bottom ? b[3] - pad - fm.descent : b[1] + pad - fm.ascent;
                    canvas.drawText(g.text, x, y, text);
                    break;
                }
            }
        }
        text.setColorFilter(null);
    }

    /** 字号从 size 起，放不下 room 宽就缩（最小 7sp）。 */
    private void fit(String s, float size, float room) {
        float min = Style.sp(getContext(), 7);
        text.setTextSize(size);
        float tw = text.measureText(s);
        if (tw > room && room > 0) text.setTextSize(Math.max(min, size * room / tw));
    }

    private void drawCentered(Canvas canvas, String s, float x, float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text);
    }

    /** 按下时放大提示（照锁着的修饰键会出的那个字；功能键、修饰键、F 键、方向键、小键盘不放大）。 */
    private void preview(int i, boolean down) {
        if (previewHost == null || i < 0 || i >= model.keys().size()) return;
        Object token = model.keys().get(i);
        if (!down) {
            previewHost.hideKeyPreviewToken(token);
            return;
        }
        String label = model.preview(i, modifiers);
        if (label == null) return;
        int[] b = model.box(i);
        previewHost.showKeyPreviewAt(this, b[0], b[1], b[2], b[3], label, token);
    }

    // ═══════════ 自己收触摸（服务没接管、没走本地分流时） ═══════════

    /**
     * 每根手指按下时定键（FixedPanel.keyAt：包含的键优先，缝里交给这一行这一段里最近的键，同分流），抬起时松开它自己那个键；
     * ACTION_CANCEL 全部松开。按在空格子里（方向键上面那几格）、嵌着的红点上、空格那一排让给三个鼠标键的空位上的手指不算任何键。
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int index = model.keyAt(e.getX(i), e.getY(i));
                if (index < 0 || index >= pressed.length) break;
                int old = fingers.down(e.getPointerId(i), index);
                if (old != KeyFingers.NONE) release(old, false);
                press(index);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = fingers.up(e.getPointerId(e.getActionIndex()));
                if (index != KeyFingers.NONE) release(index, true);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int index : fingers.cancel()) release(index, false);
                break;
            default:
                break;
        }
        return true;
    }

    /** 先发键，再震动、画按下的样子（同 KeyboardPanel）。 */
    private void press(int index) {
        FixedLayout.Key k = model.keys().get(index);
        if (!k.special) listener.onLocalKey(k.id, true);
        listener.onKeyHaptic(k.id, k.special);
        pressed[index] = true;
        preview(index, true);
        invalidate();
    }

    /** tap = 手指正常抬起（特殊键这时才算点了）；被取消、被顶掉的只松开。 */
    private void release(int index, boolean tap) {
        if (index < 0 || index >= model.keys().size()) return;
        FixedLayout.Key k = model.keys().get(index);
        if (!k.special) {
            listener.onLocalKey(k.id, false);
        } else if (tap) {
            int id = k.id;
            post(() -> listener.onSpecialKey(id));
        }
        if (index < pressed.length) pressed[index] = false;
        preview(index, false);
        invalidate();
    }

    /** 现在显示的键（调试用）。 */
    List<FixedLayout.Key> keys() {
        return new ArrayList<>(model.keys());
    }
}
