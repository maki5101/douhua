package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
 * 颜色全从当前主题取（Style.theme，主次键帽同 KeyRows.functionKey）；修饰键左右八个都画高亮（锁住的、"现在松手就锁"的），Caps 开着时也亮；
 * 锁住或按着 Shift / 第三层时会出的那个字照常、别的压暗，字母跟着 Caps 换大小写（FixedPanel.upper）；
 * 死键用强调色（activeText，对键帽够 4.5:1）。被小红点切到的键沿圆弧切掉一块，直接用豆花布局那个 Style.CutCap 画（圆角矩形减去圆）。
 * Fn（协议 21，75% / 65% / 苹果紧凑）：Fn 键单次锁时画修饰键那种高亮，永久锁 / 快永久锁了画强调色实底（协议 22，FixedPanel.fnLockLook）；
 * Fn 锁着（单次或永久）或按着时带 Fn 层的键帽整片换成 Fn 层的字（主次照 Fn 层那个码），
 * 不换层、不重报区域——按下发哪个码由分流按下那一刻定（KeyHolds），这里只画。报的 id、自己收触摸时发的 id 都是 FixedPanel.keyId。
 * F1–F12 的第二功能（协议 24）：画什么照 FixedPanel.glyphsAt（fnState 带 F 行翻转那一位，MainActivity 照设置填）：两面都印时上面第二功能、下面小字 F1，
 * 不会出的那面压暗；图标是 FKeyFaces 的笔画，按键帽大小缩放、颜色同字（跟主题，不用彩色 emoji）。
 */
final class FixedKeyboardView extends View implements KeyboardFace {
    private final KeyboardPanel.Listener listener;
    private KeyboardPanel.PreviewHost previewHost;
    private final FixedPanel model = new FixedPanel();
    private final KeyFingers fingers = new KeyFingers();
    private final int gap;
    private final float corner, pad, labelRoom;
    /** 换层看的修饰键（latched | held：锁住或按着都换）、高亮看的（latched | armed）、Fn 的状态（Proto.FN_* 位）、豆花这边显示的大写锁定，见 {@link #setKeyState}。 */
    private int mods, shown, fnState;
    private boolean capsOn;
    /** 每个键（model.keys 的下标）按着没有。 */
    private boolean[] pressed = new boolean[0];
    /** 每个键的每个键帽（FixedPanel.capBoxes）的形状：按 {@link #applyTheme} 时的边框粗细缩进半条边框（同 GradientDrawable / Style.CutCap）。 */
    private Path[][] paths = new Path[0][];
    private float strokeW;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 量行高用的字号（像素；FontMetrics 在很小的字号下按像素取整，量大一点再除）。 */
    private static final float REF_TEXT = 100;
    /** 行高 ÷ 字号（键帽字的画笔，第一次 fit 时量）。 */
    private float lineFactor;
    /** 拿画键帽字的那支笔量字宽（FixedPanel.fitText 用；会改它的字号，量完由 fit 设成最后定的那个）。 */
    private final FixedPanel.TextWidth measure = (s, size) -> {
        text.setTextSize(size);
        return text.measureText(s);
    };
    /** 第二功能的图标（FKeyFaces 的笔画）：描边的圆头圆角、填充的。 */
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG), iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path(), iconCut = new Path();
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
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
        iconFill.setStyle(Paint.Style.FILL);
        applyTheme();
    }

    /** 逐级缩的几级（选的尺寸往下，FixedData.ladder），字表各级共用。 */
    private List<FixedLayout> ladder = new ArrayList<>();
    private FixedLegends.Table legends;

    /**
     * 换一块键盘（设置页改了语言 / 形状 / 尺寸 / 模式）：逐级缩的几级（第 0 级是选的尺寸；读不出数据是空的，KeyboardScreen 就照样显示豆花布局）。
     * 先摆第 0 级，量尺寸时 KeyboardScreen 按窗口挑一级（{@link #useLevel}）。按着的键先松开。
     */
    void setBoards(List<FixedLayout> ladder, FixedLegends.Table legends) {
        this.ladder = ladder == null ? new ArrayList<>() : new ArrayList<>(ladder);
        this.legends = legends;
        dropTouches();
        model.setBoard(this.ladder.isEmpty() ? null : this.ladder.get(0), legends);
        resizeState();
        requestLayout();
        invalidate();
    }

    /**
     * 换到第 i 级（KeyboardScreen 量尺寸、挑出的那一级变了时，在量这块面板之前调）：和现在显示的是同一块就什么都不做；
     * 不然面板自己收的手指先松开（不算点击）、按下的样子和放大提示收掉，再换键（数组跟着换，下标对得上新的键）。
     */
    void useLevel(int i) {
        if (i < 0 || i >= ladder.size() || ladder.get(i) == model.layout()) return;
        dropTouches();
        model.setBoard(ladder.get(i), legends);
        resizeState();
        requestLayout();
        invalidate();
    }

    /** 逐级缩的几级（读不出数据是空的）。 */
    List<FixedLayout> ladder() {
        return ladder;
    }

    /** 现在显示的这块（没设过 / 读不出数据是 null：KeyboardScreen 就照样显示豆花布局）。 */
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
    public void setKeyState(int latched, int held, int armed, int fn, boolean caps) {
        int m = latched | held, sh = latched | armed;
        if (m == mods && sh == shown && fn == fnState && caps == capsOn) return;
        mods = m;
        shown = sh;
        fnState = fn;
        capsOn = caps;
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

    /** id = 报的那个 id（FixedPanel.keyId：Fn 键、带 Fn 层的键是协议 21 的编码，服务转来的按下就是它）。 */
    @Override
    public void showPressed(int id, boolean down) {
        List<FixedLayout.Key> keys = model.keys();
        for (int i = 0; i < keys.size() && i < pressed.length; i++) {
            if (model.keyId(i) != id) continue;
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

    /**
     * 面板自己收的手指按着的键松开（不算点击，KEY_CANCEL）、按下的样子和放大提示收掉（换键盘、逐级缩换级、换 ⋯ / 空位前）。
     * 实体键盘没有换层，这几样都是改设置 / 换窗口，不在打字中途，照旧全部当取消（豆花布局换层时按 Key 对象保住还显示着的键）。
     */
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
        boolean fnLayer = FixedPanel.fnShown(fnState);
        for (int i = 0; i < keys.size() && i < paths.length; i++) {
            if (paths[i] == null) continue;
            FixedLayout.Key k = keys.get(i);
            boolean active = model.active(i, shown, capsOn, fnState);
            boolean fn = model.functionKey(i, fnLayer) && !active;
            boolean down = i < pressed.length && pressed[i];
            // ⋯ 槽：强调色的底 + 反差字、不画边框（同豆花布局的 ⋯ 键，Style.menuKeyLook）。键帽的路径往里缩了半条边框，
            // 别的键描边时外沿才到键帽的边：⋯ 照样"描边"、只是描成底色，大小和旁边的键一样（按键边框关着时都不描，也一样）。
            // Fn 永久锁着 / 快永久锁了（协议 22）画成同样的实底（像亮着的 Fn Lock 灯），单次锁照旧是修饰键那种强调色的边
            boolean lockLook = model.fnLockLook(i, fnState);
            boolean menu = KeyRows.menuKey(k.id) || lockLook;
            int fillColor = menu ? (down ? t.menuKeyPressed : t.menuKey)
                    : down ? (fn ? t.fnPressed : t.pressed) : (fn ? t.fnKey : t.key);
            int strokeColor = menu ? fillColor : active ? t.accent : t.border;
            boolean edge = active || borders;
            float[] cut = model.cut(i);
            if (cut != null && !k.lShape()) {
                // 被小红点切掉一块：和豆花布局同一个画法（Style.CutCap：圆角矩形减去圆，边框沿圆弧走、外沿对齐键帽的边）
                int[] b = model.box(i);
                Style.CutCap cap = new Style.CutCap(corner, edge ? strokeW : 0, strokeColor, fillColor,
                        cut[0] - b[0], cut[1] - b[1], cut[2]);
                cap.setBounds(b[0], b[1], b[2], b[3]);
                cap.draw(canvas);
            } else {
                fill.setColor(fillColor);
                stroke.setColor(strokeColor);
                for (Path p : paths[i]) {
                    canvas.drawPath(p, fill);
                    if (edge) canvas.drawPath(p, stroke);
                }
            }
            int base = menu ? t.menuKeyText : active ? t.activeText : fn ? t.fnText : t.text;
            List<FixedPanel.Glyph> glyphs = model.glyphsAt(i, mods, capsOn, fnState, space);
            for (int[] b : model.capBoxes(i)) drawGlyphs(canvas, i, b, glyphs, base, t, model.named(i, fnLayer), lockLook);
        }
    }

    /**
     * 一个键帽上的字：正中 / 上下两层 / 角上小字，被切到的键整块字竖着挪（FixedPanel.shift）、角上小字放离切口最远的角。
     * solid = 实底的 Fn 键（永久锁）：🌐 照字色剪影画（去色映射是按功能键的底和字算的，画在强调色实底上看不清）。
     */
    private void drawGlyphs(Canvas canvas, int i, int[] b, List<FixedPanel.Glyph> glyphs, int base, Theme t, boolean named, boolean solid) {
        float w = b[2] - b[0], h = b[3] - b[1];
        if (w <= 0 || h <= 0) return;
        // 字和图标都裁在这个键帽里：放不下的字 fit 已经末尾省略了，这里再兜一层，绝不画到隔壁的键上
        canvas.save();
        canvas.clipRect(b[0], b[1], b[2], b[3]);
        float shift = model.shift(i);
        boolean hasCorner = false;
        for (FixedPanel.Glyph g : glyphs) hasCorner |= g.slot == FixedPanel.Glyph.CORNER;
        float cx = (b[0] + b[2]) / 2f;
        // 有角上小字的两层字键，上下两个字往左让一点（角上的字在右边）
        float pairX = hasCorner ? b[0] + w * 0.42f : cx;
        float big = Math.min(Style.sp(getContext(), 18), h * 0.46f), mid = Math.min(Style.sp(getContext(), 14), h * 0.30f),
                small = Math.min(Style.sp(getContext(), 10), h * 0.22f), name = Math.min(Style.sp(getContext(), 13), h * 0.34f);
        for (FixedPanel.Glyph g : glyphs) {
            int color = g.dead ? t.activeText : base;
            if (g.dim) color = (color & 0x00FFFFFF) | ((Math.round(((color >>> 24) & 0xFF) * 0.4f)) << 24);
            text.setColor(color);
            text.setColorFilter(!g.text.equals("🌐") ? null : solid ? new PorterDuffColorFilter(base, PorterDuff.Mode.SRC_IN) : emoji);
            if (g.icon != 0) {
                // 第二功能的图标（协议 24）：正中（只画会出的那一面）或上半（两面都印）
                boolean upper = g.slot == FixedPanel.Glyph.UPPER;
                float size = upper ? Math.min(h * 0.42f, w * 0.6f) : Math.min(h * 0.55f, w * 0.62f);
                drawIcon(canvas, g.icon, cx, (upper ? b[1] + h * 0.38f : (b[1] + b[3]) / 2f) + shift, size, color);
                continue;
            }
            switch (g.slot) {
                case FixedPanel.Glyph.UPPER: {   // 第二功能的短字（组合键、F13……），两面都印时在上半（长组合键可能折成两三行）
                    String[] s = fit(g.text, mid, g.slot, w, h, hasCorner);
                    text.setTextAlign(Paint.Align.CENTER);
                    drawCentered(canvas, s, cx, b[1] + h * 0.38f + shift);
                    break;
                }
                case FixedPanel.Glyph.LOWER: {   // 两面都印时下面的小字 F1
                    String[] s = fit(g.text, small, g.slot, w, h, hasCorner);
                    text.setTextAlign(Paint.Align.CENTER);
                    drawCentered(canvas, s, cx, b[1] + h * 0.80f + shift);
                    break;
                }
                case FixedPanel.Glyph.CENTER: {
                    float size = named && g.text.codePointCount(0, g.text.length()) > 1 ? name : big;
                    String[] s = fit(g.text, size, g.slot, w, h, hasCorner);
                    text.setTextAlign(Paint.Align.CENTER);
                    drawCentered(canvas, s, cx, (b[1] + b[3]) / 2f + shift);
                    break;
                }
                case FixedPanel.Glyph.TOP:
                case FixedPanel.Glyph.BOTTOM: {
                    String[] s = fit(g.text, mid, g.slot, w, h, hasCorner);
                    text.setTextAlign(Paint.Align.CENTER);
                    float y = b[1] + h * (g.slot == FixedPanel.Glyph.TOP ? 0.30f : 0.70f) + shift;
                    drawCentered(canvas, s, pairX, y);
                    break;
                }
                default: {
                    String s = fit(g.text, small, FixedPanel.Glyph.CORNER, w, h, hasCorner)[0];   // 角上小字只许一行（textRooms 给的 vroom 是 0）
                    int c = model.corner(i);
                    boolean right = c == FixedPanel.CORNER_BR || c == FixedPanel.CORNER_TR;
                    boolean bottom = c == FixedPanel.CORNER_BR || c == FixedPanel.CORNER_BL;
                    text.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
                    Paint.FontMetrics fm = text.getFontMetrics();
                    float x = right ? b[2] - pad - Style.dp(getContext(), 1) : b[0] + pad + Style.dp(getContext(), 1);
                    float y = bottom ? b[3] - pad - fm.descent : b[1] + pad - fm.ascent;
                    canvas.drawText(s, x, y, text);
                    break;
                }
            }
        }
        text.setColorFilter(null);
        canvas.restore();
    }

    /**
     * 画第二功能的图标（FKeyFaces.icon 的笔画，24 × 24 的方格缩放成 size × size、中心在 (cx, cy)），颜色 color（同字的颜色，压暗的照样带透明度）。
     * 认不出的笔画跳过（照理不会，FixedFKeysTest 锁住每个图标的笔画都认得、坐标在方格里）。
     */
    private void drawIcon(Canvas canvas, int icon, float cx, float cy, float size, int color) {
        float k = size / FKeyFaces.BOX, x0 = cx - size / 2, y0 = cy - size / 2;
        iconStroke.setColor(color);
        iconStroke.setStrokeWidth(FKeyFaces.STROKE * k);
        iconFill.setColor(color);
        for (float[] op : FKeyFaces.icon(icon)) {
            switch ((int) op[0]) {
                case FKeyFaces.LINE:
                    canvas.drawLine(x0 + op[1] * k, y0 + op[2] * k, x0 + op[3] * k, y0 + op[4] * k, iconStroke);
                    break;
                case FKeyFaces.POLYLINE:
                case FKeyFaces.POLYGON: {
                    iconPath.reset();
                    for (int j = 1; j + 1 < op.length; j += 2) {
                        float x = x0 + op[j] * k, y = y0 + op[j + 1] * k;
                        if (j == 1) iconPath.moveTo(x, y);
                        else iconPath.lineTo(x, y);
                    }
                    boolean polygon = (int) op[0] == FKeyFaces.POLYGON;
                    if (polygon) iconPath.close();
                    canvas.drawPath(iconPath, polygon ? iconFill : iconStroke);
                    break;
                }
                case FKeyFaces.RECT:
                    tmp.set(x0 + op[1] * k, y0 + op[2] * k, x0 + op[3] * k, y0 + op[4] * k);
                    canvas.drawRoundRect(tmp, op[5] * k, op[5] * k, op[6] != 0 ? iconFill : iconStroke);
                    break;
                case FKeyFaces.CIRCLE:
                    canvas.drawCircle(x0 + op[1] * k, y0 + op[2] * k, op[3] * k, op[4] != 0 ? iconFill : iconStroke);
                    break;
                case FKeyFaces.OVAL:
                    tmp.set(x0 + op[1] * k, y0 + op[2] * k, x0 + op[3] * k, y0 + op[4] * k);
                    canvas.drawOval(tmp, iconStroke);
                    break;
                case FKeyFaces.ARC: {
                    float ax = x0 + op[1] * k, ay = y0 + op[2] * k, r = op[3] * k;
                    tmp.set(ax - r, ay - r, ax + r, ay + r);
                    canvas.drawArc(tmp, op[4], op[5], false, iconStroke);
                    break;
                }
                case FKeyFaces.CRESCENT:
                    iconPath.reset();
                    iconPath.addCircle(x0 + op[1] * k, y0 + op[2] * k, op[3] * k, Path.Direction.CW);
                    iconCut.reset();
                    iconCut.addCircle(x0 + op[4] * k, y0 + op[5] * k, op[6] * k, Path.Direction.CW);
                    iconPath.op(iconCut, Path.Op.DIFFERENCE);
                    canvas.drawPath(iconPath, iconFill);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 把字 s 放进宽 w、高 h 的键帽的这个位置（slot = FixedPanel.Glyph 的位置；corner = 这个键有角上小字）：字号从 size 起，放不下就缩（最小 7sp），
     * 到 7sp 一行还放不下，组合键折成两三行，再放不下末尾省略"…"（规则在 FixedPanel.textRooms / fitText，离线测）。设好画笔的字号，返回要画的那几行。
     */
    private String[] fit(String s, float size, int slot, float w, float h, boolean corner) {
        float[] rooms = FixedPanel.textRooms(slot, w, h, pad, Style.dp(getContext(), 1), corner);
        if (lineFactor <= 0) {   // 行高 ÷ 字号（同 drawCentered 几行之间隔的那个）；字体不换，量一次
            text.setTextSize(REF_TEXT);
            Paint.FontMetrics fm = text.getFontMetrics();
            lineFactor = (fm.descent - fm.ascent) / REF_TEXT;
        }
        FixedPanel.FittedText f = FixedPanel.fitText(s, size, Style.sp(getContext(), 7), rooms, lineFactor, measure);
        text.setTextSize(f.size);
        return f.lines;
    }

    private void drawCentered(Canvas canvas, String s, float x, float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, text);
    }

    /** 几行字整块的中心在 (x, cy)，行高 = 这个字号的 descent − ascent（和 fit 里算 lineFactor 的一样）。 */
    private void drawCentered(Canvas canvas, String[] lines, float x, float cy) {
        if (lines.length == 1) {
            drawCentered(canvas, lines[0], x, cy);
            return;
        }
        Paint.FontMetrics fm = text.getFontMetrics();
        float lh = fm.descent - fm.ascent;
        for (int j = 0; j < lines.length; j++) {
            drawCentered(canvas, lines[j], x, cy + (j - (lines.length - 1) / 2f) * lh);
        }
    }

    /** 按下时放大提示（照锁着的修饰键会出的那个字；功能键、修饰键、F 键、方向键、小键盘不放大）。 */
    private void preview(int i, boolean down) {
        if (previewHost == null || i < 0 || i >= model.keys().size()) return;
        Object token = model.keys().get(i);
        if (!down) {
            previewHost.hideKeyPreviewToken(token);
            return;
        }
        String label = model.preview(i, mods, capsOn, FixedPanel.fnShown(fnState));
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

    /** 先发键（Fn 键、带 Fn 层的键发协议 21 的编码，FixedPanel.keyId），再震动、画按下的样子（同 KeyboardPanel）。 */
    private void press(int index) {
        boolean key = model.sendsKey(index);
        int id = model.keyId(index);
        if (key) listener.onLocalKey(id, Proto.KEY_DOWN);
        listener.onKeyHaptic(id, !key);
        pressed[index] = true;
        preview(index, true);
        invalidate();
    }

    /** tap = 手指正常抬起（特殊键这时才算点了；键发 KEY_UP）；被取消、被顶掉的发 KEY_CANCEL（修饰键不锁、Caps 什么都不出）。 */
    private void release(int index, boolean tap) {
        if (index < 0 || index >= model.keys().size()) return;
        int id = model.keyId(index);
        boolean key = model.sendsKey(index);
        if (key) {
            listener.onLocalKey(id, tap ? Proto.KEY_UP : Proto.KEY_CANCEL);
        } else if (tap) {
            post(() -> listener.onSpecialKey(id));
        }
        listener.onKeyRelease(id, !key, !tap);   // 松开声（被取消的不出），同 KeyboardPanel
        if (index < pressed.length) pressed[index] = false;
        preview(index, false);
        invalidate();
    }

    /** 现在显示的键（调试用）。 */
    List<FixedLayout.Key> keys() {
        return new ArrayList<>(model.keys());
    }
}
