package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.Arrays;

/**
 * 键盘界面：整页都是键盘区（键盘 + 小触控板），没有工具栏那一行（2026-10-30，用户嫌四个按钮占地方）。
 * 收起键盘 / 调整大小 / 布局 / 指点（和设置、旋转、退出）收进 ⋯ 菜单：小触控板摆出来时 ⋯ 在触控板顶上正中、不带框（{@link #menuButton}，
 * 见 KeyboardPlan.menuTop），放不下就是键盘功能行最右边的键（KeyboardPanel.setMenuKey）。
 * 菜单本身（连盖住整页的"点外面关菜单"）和主界面共用一份 MenuLayer，摆在根视图最上面，贴着这里的 ⋯ 弹出（{@link #menuAnchor}）；
 * 菜单里的项、点了做什么由 MainActivity 管。
 * 调整模式里临时出现一条"完成 / 恢复默认"（{@link #adjustBar}），离屏幕边 edgeInset，压着键盘少的那边（上 / 下）。
 * 屏幕边缘留白（2026-11-01）时这一整屏由 MainActivity 往里收（键盘区跟着变小，存的大小位置是键盘区的比例、不动），
 * "离屏幕边 edgeInset"照旧按屏幕边算：收进去多少，这里离各边就少留多少（{@link #setEdgeInsets}）。
 * 怎么摆（分不分裂、触控板放哪儿）由 {@link KeyboardPlan} 算，每次量尺寸时按当时的宽高、布局设置、触控板开关重算，
 * 拖分隔条、进出分屏、转屏、Fold 换屏都跟着变。
 *
 * 分裂键盘（2026-10-30）：布局"自动"时键盘区宽 ≥ 2 × 高（大屏分屏的下半边、手机横屏）就分成左右两半，占满键盘区，
 * 触控板放在两半中间、和键盘一样高；"分裂" / "合并"强制（2026-10-30 键盘布局按钮）。
 * 合并时触控板放在键盘旁边空得最多的那一侧（上 / 右 / 左 / 下），太小（剩一条）就不放；触控板也可以整个藏起来。
 * 指点（⋯ 菜单里"指点"：触控板 / 小红点 / 关，2026-10-31）：小红点时照真的 ThinkPad 键盘摆（2026-11-01）——不放触控板、不分裂，
 * 红点（{@link #stick}）在键盘里 g、h、v、b 交界那一点（挨着的键帽被圆弧切掉一块，KeyboardPanel.setStick），左 / 中 / 右三个键（{@link #mouseKeys}）
 * 竖着跨底行和快捷行、铺满这两行在中间让出的空位（KeyboardPanel.setHole；快捷行分开后太挤就不让位，三个键只占底行），摆法见 KeyboardPlan；
 * 触控板时可以从它上沿 / 下沿切一条三键条（设置里的开关，同一组三个键 view）。
 *
 * 键盘的大小和位置可以调（照 Gboard 的"调整大小"）：进入调整模式后，拖键盘的上下左右边改大小、拖中间整体挪位置。
 * 横屏和竖屏各记一份，合并、分裂各记一份。调整模式下服务不接管触摸（拖动要 App 自己收触摸）。
 * 分裂时两半对称着调（2026-10-30）：拖哪一半的边，另一半镜像跟着动——内侧边 = 两半一起变宽 / 变窄（中间的触控板跟着变窄 / 变宽），
 * 外侧边 = 两半一起往里收 / 推回屏幕边，上下边 = 一起变高变矮，拖中间 = 一起上下挪（不左右挪：一挪就不对称了）。几何和约束在 KeyboardPlan。
 *
 * 实体键盘（第 1B 批，2026-11-01）：设置里 / ⋯ 菜单里选了实体键盘（{@link #setFixedWanted}），而且这个窗口放得下（KeyboardPlan.fixedFits：一格 ≥ 32dp），
 * 就显示实体键盘那块面板（FixedKeyboardView），照 KeyboardPlan.planFixed 摆；放不下时这个窗口里暂时显示豆花布局（选的不变，⋯ 菜单写明），
 * 横过来 / 拖宽了自动回去（{@link #isFixedShown}、Listener.onKeyboardFaceChanged）。两块面板都在、同一时刻只显示一块，换的时候藏起来的那块
 * 松开手指、收掉按下的样子；豆花布局的状态（层、分裂、大小位置）不动。实体键盘自己记指点和大小位置（Boards），不分裂、调大小同合并的拖法。
 *
 * 轻微对齐（不能太强，手机屏小）：
 * - 拖中间时明显是上下拖就只上下动，明显是左右拖就只左右动；之后侧向又拖出一大截才放开限制。
 * - 吸附只作用于"显示的位置"，手指的位置照常累计，继续拖就能拖过吸附点；吸附范围 SNAP_DP。
 * - 挪位置 / 改宽度时，居中优先于贴边：缩一点宽度不会被拽回两边贴边。吸上时轻震一下。
 */
final class KeyboardScreen extends ViewGroup implements KeyboardPanel.PreviewHost {
    interface Listener {
        /**
         * 调整完一次（手指抬起），存起来。split = 调的是分裂键盘：rect 是左半格子（KeyboardPlan），不然是合并键盘在可用区域里的位置，都是 0–1；
         * fixed = 调的是实体键盘（存进它自己那份，Boards）。
         */
        void onKeyboardRectChanged(boolean landscape, boolean split, boolean fixed, float[] rect);

        /** 显示的那块面板换了（实体键盘放不下 / 又放得下了）：菜单里的项、字跟着换。量尺寸时发现的，post 过来。 */
        void onKeyboardFaceChanged();

        /** 吸附到了居中 / 边缘（轻震一下）。 */
        void onKeyboardSnap();
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_MOVE = 1;
    private static final int HANDLE_LEFT = 2;
    private static final int HANDLE_TOP = 3;
    private static final int HANDLE_RIGHT = 4;
    private static final int HANDLE_BOTTOM = 5;
    private static final float MIN_WIDTH = KeyboardPlan.MIN_WIDTH;
    private static final float MIN_HEIGHT = KeyboardPlan.MIN_HEIGHT;
    private static final float SNAP_DP = 10;
    /** 拖中间时，先拖出这么远再判断方向。 */
    private static final float AXIS_DECIDE_DP = 10;
    /** 锁定方向后，侧向累计超过这么远就放开限制。 */
    private static final float AXIS_RELEASE_DP = 48;
    private static final int AXIS_UNDECIDED = 0;
    private static final int AXIS_FREE = 1;
    private static final int AXIS_VERTICAL = 2;
    private static final int AXIS_HORIZONTAL = 3;

    private final TrackpadView miniPad;
    private final KeyboardPanel keyboard;
    /** 实体键盘那块面板（选了、放得下才显示，{@link #fixedShown}）。 */
    private final FixedKeyboardView fixed;
    /** 小红点、三个鼠标键（键盘最下面那排，或者触控板的三键条：同一组 view，只有一种会显示）。 */
    private final TrackPointView stick;
    private final MouseKeys mouseKeys;
    /** 触控板顶上正中的 ⋯（只有字、不带框，按着时有一块淡底）。 */
    private final View menuButton;
    /** 调整模式那条栏：完成、恢复默认。 */
    private final LinearLayout adjustBar;
    private final Listener listener;
    /** 触控板上的 ⋯、调整栏离屏幕边至少这么远（拿手机的手指常压在边上，贴边的按钮容易误触，用户反馈过）。 */
    private final int edgeInset;
    /**
     * 换成离本页各边（左、上、右、下）至少多远：本页因屏幕边缘留白往里收了的边少留那么多（EdgeMargin.edges），
     * 没收的边（没开留白、分屏时不贴屏幕边的那条）还是 edgeInset。⋯、调整栏、三键条、放在键盘外面的红点都按它。
     */
    private final int[] edges = new int[4];
    private final int gap;
    private final int grab;
    /** 触控板上的 ⋯ 按得到的范围多大、离触控板顶边多远。 */
    private final int menuButtonW, menuButtonH, cornerMargin;
    /** 调整栏多高，按钮最宽多少、两边各留多少。 */
    private final int barHeight, barButtonMax, barMargin;
    /** 尺寸门槛；sizes 是它按 {@link #edges} 换了离边距离的那份。 */
    private final KeyboardPlan.Sizes baseSizes;
    private KeyboardPlan.Sizes sizes;

    /** 合并时键盘在可用区域里的位置 {左, 上, 右, 下}，0–1；custom 为假时是默认位置（每次量尺寸按窗口重算）。 */
    private final float[] rect = new float[4];
    /** 小红点嵌在键里时红帽和键上切口之间的缝（dp，KeyboardPlan.Sizes.stickGap）。 */
    static final float STICK_GAP_DP = 3;
    /**
     * 键上的字离切口多远（sp，KeyboardPlan.Sizes.labelRoom；面板挪字也按它）：键上的字是 18sp / 13sp、跟着系统字体大小变，
     * 这块也得跟着变（按 dp 算的话字体调大以后挪开的字压进切口，2026-11-01 审查发现）。系统字体大小一改 Activity 就重建，这里跟着重算。
     */
    static final float LABEL_ROOM_SP = 10;
    /** 用户调过大小位置（存着的，或者正在拖）；没调过的跟着窗口用默认位置（KeyboardPlan.defaultRect）。 */
    private boolean custom;
    /** 按下时 custom 和 rect 是什么样：抬起时 rect 没变（只是点了一下、没拖动）就恢复 custom、不存。 */
    private boolean customAtDown;
    private final float[] rectAtDown = new float[4];
    /** 分裂时左半格子（KeyboardPlan），0–1；splitCustom 为假时是默认的（每次量尺寸按窗口重算）。 */
    private final float[] splitCell = new float[4];
    private boolean splitCustom;
    /** 上次量尺寸时 KeyboardPlan 实际用的格子（存下的守住约束后，或者默认的）：分裂时从它开始拖。 */
    private final float[] planCell = new float[4];
    private final float[] cellBeforeDown = new float[4];
    /** 这一下拖的是分裂键盘；拖的是右半（左右位移要镜像成左半的）；拖的是实体键盘。 */
    private boolean dragSplit, mirror, dragFixed;
    /** 选了实体键盘；这次量尺寸实际显示的是实体键盘（选了、而且这个窗口放得下）。 */
    private boolean fixedWanted, fixedShown;
    /** 实体键盘自己的指点、大小位置（0–1；fixedCustom 为假时是默认位置，每次量尺寸按窗口重算）。 */
    private int fixedPointer = KeyboardPlan.POINTER_PAD;
    private final float[] fixedRect = new float[4];
    private boolean fixedCustom;
    private int layoutMode = KeyboardPlan.MODE_AUTO;
    /** 指点：KeyboardPlan.POINTER_*；触控板的三键条：KeyboardPlan.KEYS_*。 */
    private int pointer = KeyboardPlan.POINTER_PAD;
    private int keysMode = KeyboardPlan.KEYS_OFF;
    private boolean adjusting;
    private boolean menuOpen;
    /** 这一下按在调整栏上（调整模式里不当拖动，交给栏上的按钮）。 */
    private boolean barTouch;
    private int handle = HANDLE_NONE;
    private float lastX, lastY;
    /** 手指拖到的位置（不含吸附）；rect 是吸附后显示的位置。 */
    private final float[] raw = new float[4];
    private int axis;
    private float moveX, moveY;
    /** 当前吸上了哪些（位标记），用来在新吸上时震一下。 */
    private int snapped;

    // 可用区域（整页），像素
    private int areaLeft, areaTop, areaWidth, areaHeight;
    private boolean padUsable;
    /** 小红点、三个鼠标键摆出来了。 */
    private boolean stickShown, keysShown;
    /** ⋯ 放在触控板顶上；不然在键盘功能行上。 */
    private boolean menuOnPad;
    /** 触控板上的 ⋯ 的左上角，调整栏的左上角（本容器坐标），onMeasure 算好。 */
    private final int[] menuButtonAt = new int[2];
    private int barLeft, barTop;
    /** 键盘分成了左右两半。 */
    private boolean split;
    /** 键盘面板、小触控板的位置（本容器坐标），onMeasure 按 KeyboardPlan 算好。分裂时 kbRect 从左半的左边到右半的右边。 */
    private final RectF kbRect = new RectF();
    /** 分裂时左半、右半的框（本容器坐标）。 */
    private final RectF leftRect = new RectF();
    private final RectF rightRect = new RectF();
    private final RectF padRect = new RectF();
    private final RectF stickRect = new RectF();
    private final RectF keysRect = new RectF();

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 调整模式里的提示字（StaticLayout 折行、居中，Paint 自己按左对齐）。 */
    private final TextPaint hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** 提示字先缩字号也要尽量不超过这么多行。 */
    private static final int HINT_LINES = 3;
    private final RectF tmp = new RectF();

    // ── 按键放大提示 ──
    /** 松开后再留这么久才收起：打得快也来得及看清。 */
    private static final long PREVIEW_LINGER_MS = 90;
    private final Paint previewFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF previewRect = new RectF();
    private String previewLabel;
    /** 现在放大的是哪个键（豆花布局是键帽的 view，实体键盘是 FixedLayout.Key）。 */
    private Object previewKey;
    private final Runnable hidePreview = () -> {
        previewLabel = null;
        previewKey = null;
        invalidate();
    };

    /**
     * menuButton、adjustBar（里面是按钮）由 MainActivity 建好（按钮编号、触摸、文字归它管），这里只管摆和显示。
     */
    KeyboardScreen(Context context, TrackpadView miniPad, KeyboardPanel keyboard, FixedKeyboardView fixed, TrackPointView stick,
                   MouseKeys mouseKeys, View menuButton, LinearLayout adjustBar, Listener listener) {
        super(context);
        this.miniPad = miniPad;
        this.keyboard = keyboard;
        this.fixed = fixed;
        this.stick = stick;
        this.mouseKeys = mouseKeys;
        this.menuButton = menuButton;
        this.adjustBar = adjustBar;
        this.listener = listener;
        edgeInset = Style.dp(context, 20);
        gap = Style.dp(context, 8);
        grab = Style.dp(context, 28);
        menuButtonW = Style.dp(context, 56);   // 不画框，只是手指按得到的范围
        menuButtonH = Style.dp(context, 36);
        cornerMargin = 0;   // 紧贴触控板顶边（⋯ 字画在这块的正中）
        barHeight = Style.dp(context, 36);
        barButtonMax = Style.dp(context, 104);
        barMargin = Style.dp(context, 8);
        // 触控板短边 96dp（约 15 毫米）：一根手指按上去还能来回划；以前只看高 ≥ 56dp，剩一条也照放、摁不了。
        // 分裂时一份（字母键连缝）至少 32dp（约 5 毫米，手机竖屏整块键盘一份约 40dp）：再窄拇指按不准。
        // 小红点按得到的范围（圆）直径最大 64dp（约 10 毫米；放在键盘外面时就这么大，画出来的红帽约 45dp），嵌在键里时按键的大小定、至少 32dp，
        // 红帽和键上切口之间缝 3dp、键上的字离切口 10sp（跟着系统字体大小）；
        // 三个鼠标键每个至少 44dp 宽（2026-11-01 用户嫌 36dp 高的"基本上没法点"；三键条 48dp 高，键盘里跨两行）、隔 4dp，中键至少 56dp；
        // 键帽四边缩进 2dp（同 KeyboardPanel）；离屏幕边 20dp。
        baseSizes = new KeyboardPlan.Sizes(gap, Style.dp(context, 96), Style.dp(context, 32), Style.dp(context, 64), Style.dp(context, 32),
                Style.dp(context, STICK_GAP_DP), Style.sp(context, LABEL_ROOM_SP),
                Style.dp(context, 44), Style.dp(context, 48), Style.dp(context, 4), Style.dp(context, 56), Style.dp(context, 2), edgeInset);
        Arrays.fill(edges, edgeInset);
        sizes = baseSizes;
        // 叠放次序：触控板、键盘、小红点、三个鼠标键、⋯（压在触控板顶上）、调整栏（菜单在根视图的 MenuLayer 里，盖在这一整屏上面）
        addView(miniPad);
        addView(keyboard);
        addView(fixed);
        fixed.setVisibility(GONE);
        addView(stick);
        addView(mouseKeys);
        addView(menuButton);
        addView(adjustBar);
        adjustBar.setVisibility(GONE);
        setWillNotDraw(false);

        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Style.dp(context, 2));
        hint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, getResources().getDisplayMetrics()));
        previewBorder.setStyle(Paint.Style.STROKE);
        previewBorder.setStrokeWidth(Style.dp(context, 1.5f));
        previewText.setTextAlign(Paint.Align.CENTER);
        applyTheme();
        keyboard.setPreviewHost(this);
        fixed.setPreviewHost(this);
    }

    /**
     * 按当前主题给自己画的东西上色：调整模式的框、拖把手、压暗层（浅色主题是一层发白的雾）、提示字，按键放大提示。
     * 键盘、触控板、⋯ 和菜单是子 view，由 MainActivity 各自重新上色。
     */
    void applyTheme() {
        Theme t = Style.theme();
        frame.setColor(t.focus);
        grip.setColor(t.focus);
        shade.setColor(t.shade());
        hint.setColor(t.strong);
        previewFill.setColor(t.previewFill);
        previewBorder.setColor(t.previewBorder);
        previewText.setColor(t.previewText);
        invalidate();
    }

    // ═══════════ 按键放大提示 ═══════════

    /** 在按下的键正上方浮出放大的字（不挡住按着的键）；上面没地方时贴着本容器的上边。 */
    @Override
    public void showKeyPreview(View key, String label) {
        showPreview(key, 0, 0, key.getWidth(), key.getHeight(), label, key);
    }

    @Override
    public void hideKeyPreview(View key) {
        hideKeyPreviewToken(key);
    }

    /** 实体键盘：键帽是 panel 里的一块矩形（panel 自己的坐标）。 */
    @Override
    public void showKeyPreviewAt(View panel, int left, int top, int right, int bottom, String label, Object token) {
        showPreview(panel, left, top, right, bottom, label, token);
    }

    @Override
    public void hideKeyPreviewToken(Object token) {
        if (token == previewKey) postDelayed(hidePreview, PREVIEW_LINGER_MS);
    }

    /** 键是 view 里的 {kl, kt, kr, kb}（view 自己的坐标），token 认是哪个键。 */
    private void showPreview(View view, int kl, int kt, int kr, int kb, String label, Object token) {
        removeCallbacks(hidePreview);
        int[] me = new int[2];
        int[] k = new int[2];
        getLocationInWindow(me);
        view.getLocationInWindow(k);
        float kx = k[0] - me[0] + kl;
        float ky = k[1] - me[1] + kt;
        int kw = kr - kl, kh = kb - kt;
        float h = Math.max(kh * 1.7f, Style.dp(getContext(), 52));
        float w = Math.max(kw * 1.5f, h * 0.8f);
        float left = Math.max(0, Math.min(getWidth() - w, kx + kw / 2f - w / 2));
        float top = Math.max(0, ky - h - Style.dp(getContext(), 2));
        previewRect.set(left, top, left + w, top + h);
        previewText.setTextSize(h * 0.55f);
        previewLabel = label;
        previewKey = token;
        invalidate();
    }

    /**
     * 本页离各边至少多远 {左, 上, 右, 下}（像素，EdgeMargin.edges 算好的）：MainActivity 因屏幕边缘留白把本页往里收了以后告诉这里，
     * 收进去的边少留（照旧离屏幕边 edgeInset）。
     */
    void setEdgeInsets(int[] e) {
        if (Arrays.equals(e, edges)) return;
        System.arraycopy(e, 0, edges, 0, 4);
        sizes = baseSizes.withEdges(edges);
        requestLayout();
    }

    /** 存下的合并键盘位置（0–1）；null = 没调过，用默认位置（跟着窗口：宽窗口靠左三分之二，其它同以前）。 */
    void setRect(float[] saved) {
        custom = saved != null;
        if (custom) System.arraycopy(saved, 0, rect, 0, 4);
        requestLayout();
    }

    /** 存下的分裂左半格子（0–1）；null = 没调过，用默认的（三分之一 / 往中间伸满，见 KeyboardPlan）。 */
    void setSplitRect(float[] saved) {
        splitCustom = saved != null;
        if (splitCustom) System.arraycopy(saved, 0, splitCell, 0, 4);
        requestLayout();
    }

    /**
     * 选了实体键盘（true）还是豆花布局。真显示哪块要等量尺寸时看这个窗口放不放得下（{@link #isFixedShown}）；
     * 实体键盘还没设过（{@link FixedKeyboardView#layout} 是 null）时照样显示豆花布局。
     */
    void setFixedWanted(boolean on) {
        fixedWanted = on;
        requestLayout();
    }

    /** 实体键盘的指点（KeyboardPlan.POINTER_*，Boards 里实体键盘自己那份）。 */
    void setFixedPointer(int p) {
        fixedPointer = KeyboardPlan.validPointer(p);
        requestLayout();
    }

    /** 实体键盘存下的大小位置（0–1，现在这个尺寸、指点、方向的那份）；null = 没调过，用默认位置（KeyboardPlan.fixedDefaultRect）。 */
    void setFixedRect(float[] saved) {
        fixedCustom = saved != null;
        if (fixedCustom) System.arraycopy(saved, 0, fixedRect, 0, 4);
        requestLayout();
    }

    /** 这次量尺寸显示的是实体键盘吗（选了、而且放得下）。 */
    boolean isFixedShown() {
        return fixedShown;
    }

    /** 按上次量的宽度，实体键盘在这个窗口里放得下吗（刚换键盘、还没重新量的时候，⋯ 菜单的字照它写）。 */
    boolean fixedFitsNow() {
        return KeyboardPlan.fixedFits(fixed.layout(), areaWidth, sizes);
    }

    /** 显示着的那块面板（区域、按下的样子只给它）。 */
    KeyboardFace face() {
        return fixedShown ? fixed : keyboard;
    }

    /** 布局：KeyboardPlan.MODE_*（自动 / 分裂 / 合并）。 */
    void setKeyboardLayout(int mode) {
        layoutMode = KeyboardPlan.validMode(mode);
        requestLayout();
    }

    /** 指点：KeyboardPlan.POINTER_*（触控板 / 小红点 / 关）。没摆出来的不报区域（isPadUsable / isStickShown 为假）。 */
    void setPointer(int p) {
        pointer = KeyboardPlan.validPointer(p);
        requestLayout();
    }

    /** 触控板的三键条：KeyboardPlan.KEYS_*（不要 / 下沿 / 上沿）；切不下就不切（areMouseKeysShown 为假）。 */
    void setPadKeys(int keys) {
        keysMode = keys;
        requestLayout();
    }

    boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    boolean isAdjusting() {
        return adjusting;
    }

    /** 调整模式：显示"完成 / 恢复默认"那条栏，触控板上的 ⋯ 藏起来（菜单里的事调整模式里不做）。菜单由 MainActivity 先关掉。 */
    void setAdjusting(boolean on) {
        adjusting = on;
        adjustBar.setVisibility(on ? VISIBLE : GONE);
        requestLayout();
        invalidate();
    }

    /** ⋯ 菜单开着（菜单本身在 MenuLayer 里）：开着时不画按键放大提示。 */
    void setMenuOpen(boolean on) {
        menuOpen = on;
        invalidate();
    }

    /** 触控板上的 ⋯ 显示着吗（显示着才报它的区域）。 */
    boolean isMenuButtonShown() {
        return menuButton.getVisibility() == VISIBLE;
    }

    /** 小触控板摆出来了吗（设成显示、而且有够大的地方）。 */
    boolean isPadUsable() {
        return padUsable;
    }

    /** 小红点摆出来了吗（选了小红点、而且有地方；嵌在键里时区域要排在键前面，MainActivity 本来就这么排）。 */
    boolean isStickShown() {
        return stickShown;
    }

    /** 三个鼠标键摆出来了吗（选小红点时键盘最下面那排，或者触控板的三键条）。 */
    boolean areMouseKeysShown() {
        return keysShown;
    }

    /** 键盘现在分成了左右两半吗。 */
    boolean isSplit() {
        return split;
    }

    // ═══════════ 布局 ═══════════

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        areaLeft = 0;
        areaTop = 0;
        areaWidth = w;
        areaHeight = h;
        // 选了实体键盘、这个窗口放得下才显示它，不然这个窗口里暂时显示豆花布局（换了要藏起来的那块先松开手指、收掉按下的样子）
        boolean showFixed = fixedWanted && KeyboardPlan.fixedFits(fixed.layout(), areaWidth, sizes);
        if (showFixed != fixedShown) {
            fixedShown = showFixed;
            KeyboardFace hidden = showFixed ? keyboard : fixed;
            hidden.releaseTouches();
            hidden.clearPressed();
            keyboard.setVisibility(showFixed ? GONE : VISIBLE);
            fixed.setVisibility(showFixed ? VISIBLE : GONE);
            handle = HANDLE_NONE;   // 正拖着的那一下作废（拖的是另一块）
            post(listener::onKeyboardFaceChanged);
        }
        KeyboardPlan.Plan plan;
        if (fixedShown) {
            plan = KeyboardPlan.planFixed(fixedPointer, keysMode, areaWidth, areaHeight, fixedCustom ? fixedRect : null, fixed.layout(), sizes);
            if (!fixedCustom) System.arraycopy(plan.rect, 0, fixedRect, 0, 4);
        } else {
            // 小红点嵌在键里能多大、底行和快捷行让多宽的空位要按键算：功能行带 ⋯（选小红点时没有触控板，⋯ 一定在功能行上）、现在的底行和快捷行
            plan = KeyboardPlan.plan(layoutMode, pointer, keysMode, areaWidth, areaHeight,
                    custom ? rect : null, splitCustom ? splitCell : null, isLandscape(), sizes, keyboard.layers(true));
            if (!custom) System.arraycopy(plan.rect, 0, rect, 0, 4);   // 默认位置跟着窗口；调整模式从这里开始拖
            if (plan.split) {
                System.arraycopy(plan.splitCell, 0, planCell, 0, 4);
                if (!splitCustom) System.arraycopy(plan.splitCell, 0, splitCell, 0, 4);
            }
        }
        if (plan.split != split) {
            split = plan.split;
            handle = HANDLE_NONE;   // 正拖着的那一下作废（拖的是另一种布局的大小）；"恢复默认"两种布局都显示，不用通知谁
        }
        // ⋯ 放触控板顶上正中（三键条在上沿时在三键条正下方）还是功能行上（选小红点、指点关着时没有触控板，一定在功能行上）：
        // 那块摆出来、离屏幕边够远的地方放得下就放那儿（areaLeft / areaTop 是 0，plan 的坐标就是本容器坐标）
        int[] corner = plan.menuArea == null ? null
                : KeyboardPlan.menuTop(plan.menuArea, w, h, menuButtonW, menuButtonH, cornerMargin + plan.menuMargin, edges);
        menuOnPad = corner != null;
        if (menuOnPad) System.arraycopy(corner, 0, menuButtonAt, 0, 2);
        int showButton = menuOnPad && !adjusting ? VISIBLE : GONE;
        if (menuButton.getVisibility() != showButton) menuButton.setVisibility(showButton);
        menuButton.measure(exactly(menuButtonW), exactly(menuButtonH));
        KeyboardFace face = face();
        face.setMenuKey(!menuOnPad);   // 量键盘之前换好显示的键
        if (!fixedShown) keyboard.setSplit(split, plan.middle, plan.seam);
        face.setHole(plan.hole, plan.spread);   // 小红点状态：底行（快捷行分得开时连快捷行；实体键盘是空格那一排）在中间让出空位给三个鼠标键
        int[] k = plan.keyboard;
        kbRect.set(areaLeft + k[0], areaTop + k[1], areaLeft + k[2], areaTop + k[3]);
        if (split) {
            int[] a = plan.leftHalf, b = plan.rightHalf;
            leftRect.set(areaLeft + a[0], areaTop + a[1], areaLeft + a[2], areaTop + a[3]);
            rightRect.set(areaLeft + b[0], areaTop + b[1], areaLeft + b[2], areaTop + b[3]);
        } else {
            leftRect.setEmpty();
            rightRect.setEmpty();
        }
        // 红点嵌在键里：挨着的键帽沿圆弧切掉一块（面板量尺寸时按它画、挪字），面板自己收触摸时红点圆里不算任何键。在量面板之前设
        boolean inKeys = plan.stickAt == KeyboardPlan.STICK_KEYS;
        face.setStick(inKeys ? new int[]{plan.stick[0] - k[0], plan.stick[1] - k[1], plan.stick[2] - k[0], plan.stick[3] - k[1]} : null,
                plan.cut);
        (fixedShown ? fixed : keyboard).measure(exactly(k[2] - k[0]), exactly(k[3] - k[1]));
        padUsable = plan.pad != null;
        if (padUsable) {
            int[] p = plan.pad;
            padRect.set(areaLeft + p[0], areaTop + p[1], areaLeft + p[2], areaTop + p[3]);
        } else {
            padRect.setEmpty();
        }
        miniPad.measure(exactly(Math.round(padRect.width())), exactly(Math.round(padRect.height())));
        stickShown = plan.stick != null;
        keysShown = plan.keys != null;
        setRect(stickRect, plan.stick);
        setRect(keysRect, plan.keys);
        // 红点嵌在键里只画红帽（键自己切口），不然画一圈淡圆；三个鼠标键在键盘里时铺满空位（键帽各缩进同键盘的键），三键条时一排三个键帽
        stick.setEmbedded(inKeys, sizes.stickGap);
        mouseKeys.setTiled(plan.hole != null, sizes.capGap);
        if (plan.middleKey != null) mouseKeys.setMiddle(plan.middleKey[0] - plan.keys[0], plan.middleKey[1] - plan.keys[0]);
        stick.measure(exactly(Math.round(stickRect.width())), exactly(Math.round(stickRect.height())));
        mouseKeys.measure(exactly(Math.round(keysRect.width())), exactly(Math.round(keysRect.height())));

        // 调整栏：按钮挤在中间，放上面还是下面看哪边压着键盘少（拖键盘时跟着让开）
        int barW = fitBar(w);
        adjustBar.measure(exactly(barW), exactly(barHeight));
        barLeft = (w - barW) / 2;
        barTop = KeyboardPlan.adjustBarTop(h, barHeight, edges[1], edges[3], Math.round(kbRect.top), Math.round(kbRect.bottom));
    }

    /** plan 里的矩形（键盘区坐标，null = 没有）换成本容器坐标。 */
    private void setRect(RectF out, int[] r) {
        if (r == null) out.setEmpty();
        else out.set(areaLeft + r[0], areaTop + r[1], areaLeft + r[2], areaTop + r[3]);
    }

    /**
     * ⋯ 在哪（本容器坐标 {左, 上, 右, 下}，写进 out）：触控板顶上的 ⋯（返回真：菜单和它居中对齐），或者功能行上的键（返回假：
     * 按它在页面左半 / 右半对齐）；万一都没有（还没量好）当它在页面正中。MenuLayer 摆菜单时问（经 MainActivity 换成窗口坐标）。
     */
    boolean menuAnchor(int[] out) {
        if (menuOnPad) {
            out[0] = menuButtonAt[0];
            out[1] = menuButtonAt[1];
            out[2] = menuButtonAt[0] + menuButtonW;
            out[3] = menuButtonAt[1] + menuButtonH;
            return true;
        }
        int[] k = face().menuKeyRect();   // 键盘摆好以后才问，键的位置是新的
        if (k == null) {
            out[0] = out[2] = getWidth() / 2;
            out[1] = out[3] = getHeight() / 2;
            return false;
        }
        int dx = Math.round(kbRect.left), dy = Math.round(kbRect.top);
        out[0] = k[0] + dx;
        out[1] = k[1] + dy;
        out[2] = k[2] + dx;
        out[3] = k[3] + dy;
        return false;
    }

    /**
     * 调整栏按钮的排法：一样宽、挤在中间，左右都离屏幕边至少 edgeInset（两边要的不一样时按要得多的那边算，栏照旧在正中），
     * 窗口窄放不下时一起缩（KeyboardPlan.barButtonWidth；
     * 字号本来就自动缩）。宽度定死不用 wrap_content：按钮字号是自动缩小的，和 wrap_content 不搭。
     * 在量栏之前直接改 LayoutParams 的字段（不 setLayoutParams，免得在测量途中再请求布局）。返回整条栏多宽。
     */
    private int fitBar(int w) {
        int count = adjustBar.getChildCount();
        int each = KeyboardPlan.barButtonWidth(w, count, Math.max(edges[0], edges[2]), barMargin, barButtonMax);
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) adjustBar.getChildAt(i).getLayoutParams();
            if (p.width != each || p.weight != 0 || p.leftMargin != barMargin || p.rightMargin != barMargin
                    || p.height != LayoutParams.MATCH_PARENT) {
                p.width = each;
                p.height = LayoutParams.MATCH_PARENT;
                p.weight = 0;
                p.leftMargin = barMargin;
                p.rightMargin = barMargin;
                changed = true;
            }
        }
        if (changed) adjustBar.forceLayout();   // 栏自己没请求布局时，同样的尺寸也要重新量
        return count * (each + 2 * barMargin);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        RectF k = kbRect;
        View shown = fixedShown ? fixed : keyboard, other = fixedShown ? keyboard : fixed;
        shown.layout(Math.round(k.left), Math.round(k.top), Math.round(k.right), Math.round(k.bottom));
        other.layout(0, 0, 0, 0);
        if (padUsable) {
            int pl = Math.round(padRect.left), pt = Math.round(padRect.top);
            miniPad.layout(pl, pt, pl + miniPad.getMeasuredWidth(), pt + miniPad.getMeasuredHeight());
        } else {
            miniPad.layout(0, 0, 0, 0);
        }
        layoutAt(stick, stickRect);
        layoutAt(mouseKeys, keysRect);
        menuButton.layout(menuButtonAt[0], menuButtonAt[1], menuButtonAt[0] + menuButtonW, menuButtonAt[1] + menuButtonH);
        // ⋯ 摆在小触控板上时告诉触控板它在哪：有背景图时触控板在 ⋯ 下面垫一块底色（⋯ 只在触控板上，不在时不垫）
        if (padUsable && menuButton.getVisibility() == VISIBLE) {
            miniPad.setMenuChip(menuButtonAt[0] + menuButtonW / 2f - miniPad.getLeft(), menuButtonAt[1] + menuButtonH / 2f - miniPad.getTop());
        } else {
            miniPad.setMenuChip(Float.NaN, Float.NaN);
        }
        adjustBar.layout(barLeft, barTop, barLeft + adjustBar.getMeasuredWidth(), barTop + barHeight);
    }

    /** 摆到 r（空的就摆成 0 × 0，不显示也不挡触摸）。 */
    private static void layoutAt(View v, RectF r) {
        int l = Math.round(r.left), t = Math.round(r.top);
        v.layout(l, t, l + v.getMeasuredWidth(), t + v.getMeasuredHeight());
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
    }

    // ═══════════ 调整模式 ═══════════

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (previewLabel != null && !adjusting && !menuOpen) {
            float r = Style.dp(getContext(), 10);
            canvas.drawRoundRect(previewRect, r, r, previewFill);
            canvas.drawRoundRect(previewRect, r, r, previewBorder);
            Paint.FontMetrics fm = previewText.getFontMetrics();
            float baseline = previewRect.centerY() - (fm.ascent + fm.descent) / 2;
            canvas.drawText(previewLabel, previewRect.centerX(), baseline, previewText);
        }
        if (!adjusting) return;
        RectF k = kbRect;
        canvas.drawRect(k, shade);   // 分裂时连中间那截一起压暗（调整模式里触控板、红点本来就不能用）；小红点状态连着键盘里的三个鼠标键
        if (split) {   // 两半各一个框、各一套拖把手（对称），提示字横跨两半写在中间
            drawFrame(canvas, leftRect);
            drawFrame(canvas, rightRect);
            drawHint(canvas, getContext().getString(R.string.kb_adjust_split_hint), k);
        } else {
            drawFrame(canvas, k);
            drawHint(canvas, getContext().getString(R.string.kb_adjust_hint), k);
        }
        // 调整栏可能叠在键盘上（键盘占满高的时候）：压暗和框画完再画它一遍，盖在最上面
        drawChild(canvas, adjustBar, getDrawingTime());
    }

    /** 框 + 四条边中间的拖把手。 */
    private void drawFrame(Canvas canvas, RectF k) {
        canvas.drawRect(k, frame);
        float len = Style.dp(getContext(), 36);
        float thick = Style.dp(getContext(), 6);
        float cx = k.centerX();
        float cy = k.centerY();
        drawGrip(canvas, cx - len / 2, k.top - thick / 2, cx + len / 2, k.top + thick / 2);
        drawGrip(canvas, cx - len / 2, k.bottom - thick / 2, cx + len / 2, k.bottom + thick / 2);
        drawGrip(canvas, k.left - thick / 2, cy - len / 2, k.left + thick / 2, cy + len / 2);
        drawGrip(canvas, k.right - thick / 2, cy - len / 2, k.right + thick / 2, cy + len / 2);
    }

    /**
     * 提示字画在键盘正中，居中折行：先按 15sp 折，超过三行再缩字号，最小 11sp；到了 11sp 还超过三行就照样多折几行
     * （键盘区高度够）。以前一行到底按宽度等比缩、没有下限，手机竖屏强制分裂时德语、法语的提示缩到 5–6sp，看不清。
     */
    private void drawHint(Canvas canvas, String text, RectF k) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, dm);
        float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, dm);
        int room = Math.max(1, Math.round(k.width() - 2 * gap));
        float size = max;
        StaticLayout l;
        while (true) {
            hint.setTextSize(size);
            l = StaticLayout.Builder.obtain(text, 0, text.length(), hint, room)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            if (l.getLineCount() <= HINT_LINES || size <= min) break;
            size = Math.max(min, size * 0.9f);
        }
        canvas.save();
        canvas.translate(k.centerX() - room / 2f, k.centerY() - l.getHeight() / 2f);
        l.draw(canvas);
        canvas.restore();
    }

    private void drawGrip(Canvas c, float l, float t, float r, float b) {
        tmp.set(l, t, r, b);
        c.drawRoundRect(tmp, b - t, b - t, grip);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        // 调整模式下栏上的按钮照常能点：按下时落在栏上，这一下整个归栏（手指拖出栏也不半路截过来当拖动）
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            barTouch = onBar(e.getX(), e.getY());
        }
        // 第一根手指在栏上时又来一根落在栏外（多半在键盘上）：截下来。栏上的按钮收到 CANCEL（不算点），
        // 键盘收不到这根手指，调整模式里不打字；之后这次手势归 onTouchEvent，handle 是 HANDLE_NONE，什么都不拖
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && adjusting && barTouch) {
            int i = e.getActionIndex();
            if (!onBar(e.getX(i), e.getY(i))) return true;
        }
        return adjusting && !barTouch;
    }

    /** (x, y) 落在调整栏上吗（栏显示着时）。 */
    private boolean onBar(float x, float y) {
        return adjustBar.getVisibility() == VISIBLE && x >= adjustBar.getLeft() && x < adjustBar.getRight()
                && y >= adjustBar.getTop() && y < adjustBar.getBottom();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!adjusting) return false;
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragSplit = split;
                dragFixed = fixedShown;
                handle = split ? pickSplitHandle(x, y) : pickHandle(x, y);
                lastX = x;
                lastY = y;
                // 分裂时从实际摆出来的格子开始（存下的在这个窗口里可能被约束撑开过）；按下前的那份留着，没拖动就换回去
                System.arraycopy(splitCell, 0, cellBeforeDown, 0, 4);
                if (dragSplit && handle != HANDLE_NONE) System.arraycopy(planCell, 0, splitCell, 0, 4);
                float[] cur = current();
                System.arraycopy(cur, 0, raw, 0, 4);
                axis = AXIS_UNDECIDED;
                moveX = moveY = 0;
                snapped = 0;
                customAtDown = dragSplit ? splitCustom : dragFixed ? fixedCustom : custom;
                System.arraycopy(cur, 0, rectAtDown, 0, 4);
                // 从默认位置开始拖：之后就是用户自己的位置了（抬起时存）。拖的途中就要置上，不然量尺寸时会把它换回默认位置
                if (handle != HANDLE_NONE) {
                    if (dragSplit) splitCustom = true;
                    else if (dragFixed) fixedCustom = true;
                    else custom = true;
                }
                return handle != HANDLE_NONE;
            }
            case MotionEvent.ACTION_MOVE:
                if (handle == HANDLE_NONE) return true;   // 没按到把手（含从栏上截过来的那次手势）：不拖、不重新布局
                drag((x - lastX) / areaWidth, (y - lastY) / areaHeight);
                lastX = x;
                lastY = y;
                requestLayout();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (handle != HANDLE_NONE) {
                    // 比 rect 而不是记"动过没有"：方向还没判断出来时不动、拖出去又吸回原位，都不算调过。
                    // 不然点一下就把这个窗口的默认位置（宽窗口是三分之二宽）存进横 / 竖屏那一份，别的窗口也跟着用它
                    if (Arrays.equals(current(), rectAtDown)) {
                        if (dragSplit) {
                            splitCustom = customAtDown;
                            System.arraycopy(cellBeforeDown, 0, splitCell, 0, 4);
                        } else if (dragFixed) {
                            fixedCustom = customAtDown;
                        } else {
                            custom = customAtDown;
                        }
                    } else {
                        listener.onKeyboardRectChanged(isLandscape(), dragSplit, dragFixed, current().clone());
                    }
                }
                handle = HANDLE_NONE;
                return true;
            default:
                return true;
        }
    }

    /** 这一下拖的是哪份：分裂的左半格子、实体键盘的位置，或者合并的键盘位置（都是吸附后显示的那份）。 */
    private float[] current() {
        return dragSplit ? splitCell : dragFixed ? fixedRect : rect;
    }

    /**
     * 合并（和实体键盘）拖边时键盘至少多宽（占键盘区的比例）：合并同以前 {@link #MIN_WIDTH}；实体键盘还要一格不窄于 minKey
     * （同显示它的门槛，KeyboardPlan.fixedMinWidth；默认位置也不比它窄），不然拖窄了键按不准。
     */
    private float minWidth() {
        if (!dragFixed) return MIN_WIDTH;
        return KeyboardPlan.fixedMinWidth(fixed.layout(), areaWidth, sizes);
    }

    private int pickHandle(float x, float y) {
        RectF k = kbRect;
        boolean inX = x > k.left - grab && x < k.right + grab;
        boolean inY = y > k.top - grab && y < k.bottom + grab;
        if (inX && Math.abs(y - k.top) < grab) return HANDLE_TOP;
        if (inX && Math.abs(y - k.bottom) < grab) return HANDLE_BOTTOM;
        if (inY && Math.abs(x - k.left) < grab) return HANDLE_LEFT;
        if (inY && Math.abs(x - k.right) < grab) return HANDLE_RIGHT;
        if (k.contains(x, y)) return HANDLE_MOVE;
        return HANDLE_NONE;
    }

    /**
     * 分裂时按到哪：两半的上下边、外侧边、内侧边（按左半说：外侧 = LEFT、内侧 = RIGHT），按在右半上记 mirror（左右位移镜像），
     * 按在那一半里面 = 拖中间。按在两条内侧边中线的哪边就只看哪一半（KeyboardPlan.onRightHalf）：两半合拢时两条内侧边的把手范围重叠，
     * 按在右半内侧边上要算右半的（镜像成左半内侧边，往右拖 = 两半一起变窄），不能先判左半。
     */
    private int pickSplitHandle(float x, float y) {
        mirror = KeyboardPlan.onRightHalf(x, leftRect.right, rightRect.left);
        RectF k = mirror ? rightRect : leftRect;
        boolean inX = x > k.left - grab && x < k.right + grab;
        boolean inY = y > k.top - grab && y < k.bottom + grab;
        if (inX && Math.abs(y - k.top) < grab) return HANDLE_TOP;
        if (inX && Math.abs(y - k.bottom) < grab) return HANDLE_BOTTOM;
        float outer = mirror ? k.right : k.left;
        float inner = mirror ? k.left : k.right;
        if (inY && Math.abs(x - outer) < grab) return HANDLE_LEFT;
        if (inY && Math.abs(x - inner) < grab) return HANDLE_RIGHT;
        if (k.contains(x, y)) return HANDLE_MOVE;
        mirror = false;
        return HANDLE_NONE;
    }

    /** dx / dy 是占可用区域的比例。先更新手指位置 raw，再按吸附规则算出显示位置 rect（分裂时 splitCell）。 */
    private void drag(float dx, float dy) {
        if (dragSplit) {
            if (handle == HANDLE_NONE) return;
            // 拖的是右半：左右位移镜像成左半的；两半对称，拖中间只上下挪
            KeyboardPlan.dragSplit(raw, snapHandle(), mirror ? -dx : dx, dy, areaWidth, sizes);
            int now = snap();
            if ((now & ~snapped) != 0) listener.onKeyboardSnap();
            snapped = now;
            return;
        }
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
                raw[0] = clamp(raw[0] + dx, 0, raw[2] - minWidth());
                break;
            case HANDLE_RIGHT:
                raw[2] = clamp(raw[2] + dx, raw[0] + minWidth(), 1);
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

    /** 按 raw 算显示位置 rect / splitCell（见 KeyboardSnap），返回吸上了哪些。 */
    private int snap() {
        float sx = Style.dp(getContext(), SNAP_DP) / Math.max(1f, areaWidth);
        float sy = Style.dp(getContext(), SNAP_DP) / Math.max(1f, areaHeight);
        int h = snapHandle();
        if (h == 0) return 0;
        return dragSplit ? KeyboardSnap.applySplit(raw, splitCell, h, sx, sy) : KeyboardSnap.apply(raw, dragFixed ? fixedRect : rect, h, sx, sy);
    }

    /** HANDLE_* → KeyboardSnap 的边（分裂时按左半说），没按到边是 0。 */
    private int snapHandle() {
        switch (handle) {
            case HANDLE_MOVE: return KeyboardSnap.MOVE;
            case HANDLE_LEFT: return KeyboardSnap.LEFT;
            case HANDLE_RIGHT: return KeyboardSnap.RIGHT;
            case HANDLE_TOP: return KeyboardSnap.TOP;
            case HANDLE_BOTTOM: return KeyboardSnap.BOTTOM;
            default: return 0;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
