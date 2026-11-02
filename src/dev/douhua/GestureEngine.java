package dev.douhua;

/**
 * 触控板手势识别。纯 Java、不依赖 Android，所以能在电脑上直接跑测试（tools/test.sh）。
 *
 * 坐标单位是 dp，时间单位是毫秒（uptimeMillis 时基）。宿主按顺序喂事件：
 * 手指按下 {@link #down}、每帧先对每根手指 {@link #move} 再 {@link #endFrame}、手指抬起 {@link #up}；
 * 引擎要定时器时调 {@link Output#schedule}，到点后宿主调 {@link #onTimer}。
 *
 * 规则（参照 Mac 触控板）：
 * - 单指移动 = 移动光标；单指轻点 = 左键；双指轻点 = 右键
 * - 双指一起移动 = 滚动；双指开合 = 缩放（宿主用 Ctrl + 滚轮实现）
 * - 单指从触控板左右边缘带开始上下滑 = 竖向滚动，从上下边缘带开始左右滑 = 横向滚动
 *   （老式笔记本的边缘滚动；宿主判断按下点在哪条边缘带里，四个角两种都算、按实际滑的方向定）
 * - 滚动每一格可以"咔嗒"震一下，像转滚轮
 * - 轻点一下、马上再按住移动 = 拖动（松手结束）；轻点两下 = 双击
 * - 单指按住不动一会儿（震一下）= 按下左键，接着移动就是拖动，松手结束
 * - 三指 / 四指的轻点和上下左右滑，交给宿主按设置执行
 * - 惯性滚动（{@link Config#inertia}）：边缘滚动快速一划松手，滚轮接着转、按阻尼慢慢停（{@link ScrollInertia}）；
 *   转着时碰触控板就"抓住"马上停（这一下不算点击、不起长按，动了照常）。碰屏幕别的地方由宿主调 {@link #stopSpin}。
 *   双指滚动不带惯性。转的时候用的是引擎唯一那个定时器槽（TIMER_SPIN，每 {@link #SPIN_FRAME_MS} 一帧），
 *   {@link #cancel}（触摸屏释放、断开、退后台、换参数）一定让它停。
 * - 连续滚动（{@link Config#smoothScroll}）：不按格，按 1/120 格（{@link Hid#WHEEL_UNIT}）平滑地出，每滚过一格的距离照样咔嗒一下；
 *   锁着 Ctrl（缩放）时照旧按格。
 * - 中键按着（三键条 / 键盘最下面那排的中键，宿主调 {@link #setScrollHold}）：单指在触控板上划 = 滚动（ThinkPad 的中键滚动），
 *   方向"推哪边视野往哪边走"、不跟自然滚动开关，不起长按、不起惯性；单指轻点照旧是左键单击。
 * - 小红点（{@link TrackPoint}，宿主按节拍推）的移动和滚动经 {@link #externalMove} / {@link #externalScroll} 出，
 *   按着的鼠标键（引擎自己的 + 外部的）只有这里一份。
 * 一次手势里只要出现过多根手指，就一直按多指处理到全部抬起，避免抬起一根时光标乱跳。
 */
public final class GestureEngine {

    public interface Output {
        /** buttons 是当前按下状态；dx/dy 是相对计数；wheel/pan 是相对量，单位 1/120 格（{@link Hid#WHEEL_UNIT}，正 = 往上 / 往右）。 */
        void mouse(int buttons, int dx, int dy, int wheel, int pan);

        /** 缩放 steps 格：正 = 放大（手指张开），负 = 缩小。一次开合手势里可能调用多次。 */
        void zoom(int steps);

        /** 这次开合手势结束（宿主在这里松开 Ctrl）。 */
        void zoomEnd();

        /** fingers 是 3 或 4，kind 是 GESTURE_*。 */
        void gesture(int fingers, int kind);

        void haptic(int kind);

        /** 请宿主在 delayMs 之后调 onTimer（再次调用即覆盖上一次）。 */
        void schedule(long delayMs);

        void cancelSchedule();

        /**
         * 调试用：一次边缘滚动松手时量到的速度（dp/ms，带方向）和起转的速度（格/秒；0 = 没起惯性）。
         * 服务 --debug 时记进日志，用来校准"多快算拨"（{@link Config#inertiaMinSpeed}）。
         */
        default void edgeRelease(float speedDpPerMs, float spinPerSecond) {}
    }

    public static final int GESTURE_TAP = 0;
    public static final int GESTURE_UP = 1;
    public static final int GESTURE_DOWN = 2;
    public static final int GESTURE_LEFT = 3;
    public static final int GESTURE_RIGHT = 4;

    public static final int HAPTIC_CLICK = 0;
    public static final int HAPTIC_DRAG_START = 1;
    public static final int HAPTIC_DRAG_END = 2;
    public static final int HAPTIC_GESTURE = 3;
    /** 滚动一格的"咔嗒"。 */
    public static final int HAPTIC_TICK = 4;
    /** 移动光标时的"颗粒"：极轻的一下，模拟摸着不光滑的表面（{@link Config#texture}）。 */
    public static final int HAPTIC_TEXTURE = 5;
    /** 惯性滚动"放开"：边缘快速一划松手、滚轮接着转。转的时候不震。 */
    public static final int HAPTIC_SPIN_START = 6;
    /** 惯性滚动"刹住"：转着时碰了屏幕，马上停。自然停下、按键停、cancel 停都不震。 */
    public static final int HAPTIC_SPIN_CATCH = 7;

    /** 按下点在左右边缘带里（单指上下滑 = 竖向滚动）。 */
    public static final int EDGE_VERTICAL = 1;
    /** 按下点在上下边缘带里（单指左右滑 = 横向滚动）。 */
    public static final int EDGE_HORIZONTAL = 2;

    public static final int TICKS_OFF = 0;
    public static final int TICKS_ALL = 2;

    /** 可调参数。设置页改的就是这些。 */
    public static final class Config {
        /** 每 dp 手指位移对应多少鼠标计数。 */
        public float pointerSpeed = 2.0f;
        /** 加速度：手指每毫秒移动 1dp 时，速度放大 (1 + acceleration) 倍。0 = 不加速。 */
        public float acceleration = 1.0f;
        /**
         * 精细移动：慢慢挪时光标速度再乘上这个（1 = 不放慢），挪得越快越接近 1，
         * 到 PRECISION_FULL_SPEED 完全恢复。学 Mac 触控板：慢的时候能精确落到小目标上，快的时候照样一甩就过去。
         */
        public float slowFactor = 0.55f;
        /** 双指移动多少 dp 滚一格。 */
        public float scrollStepDp = 20f;
        /** 自然滚动：内容跟着手指走（Mac 默认）。 */
        public boolean naturalScroll = true;
        /** 单指从左右边缘带开始上下滑 = 竖向滚动。 */
        public boolean edgeScroll = true;
        /** 单指从上下边缘带开始左右滑 = 横向滚动。 */
        public boolean edgeScrollHorizontal = true;
        /** 边缘带宽度（宿主用它判断按下的位置在不在边缘带里）。 */
        public float edgeWidthDp = 28f;
        /** 滚动"咔嗒"震动：TICKS_OFF / TICKS_ALL（双指滚动、边缘滚动一样震；以前的"只在边缘"也当开）。 */
        public int scrollTicks = TICKS_ALL;
        /** 单指按住不动 longPressMs 毫秒 = 按下左键（之后移动就是拖动）。 */
        public boolean longPressDrag = true;
        public int longPressMs = 450;
        /** 长按要手指基本不动：离按下点超过这么远就不算长按。比轻点的容差小——微调光标时手指也只挪一点点，不能被当成长按。 */
        public float longPressSlopDp = 2.5f;
        /** 双指开合 = 缩放。关掉的话双指只滚动。 */
        public boolean pinchZoom = true;
        /** 两指间距变化多少 dp 缩放一格。 */
        public float pinchStepDp = 24f;
        /** 轻点后按住移动 = 拖动。关掉的话单击没有延迟。 */
        public boolean tapToDrag = true;
        /** 轻点后等多久看有没有第二次按下。 */
        public int dragWindowMs = 220;
        /** 移动光标时，手指每划过 textureStepDp 发一颗"颗粒"震动（HAPTIC_TEXTURE）；拖动、按着左键、滚动时不发。 */
        public boolean texture = false;
        /** 两颗之间手指划过的距离（约 1.6 毫米）。 */
        public float textureStepDp = 10f;
        /** 两颗之间至少隔多久：划得再快也不密成一片嗡嗡声。 */
        public int textureMinGapMs = 25;
        /** 按下到抬起不超过这么久、且没移动，才算轻点。 */
        public int tapMaxMs = 250;
        public float tapSlopDp = 6f;
        public float multiTapSlopDp = 12f;
        /** 三指 / 四指滑多远算一次滑动。 */
        public float swipeThresholdDp = 48f;
        /** 惯性滚动：边缘滚动快速一划松手，接着转、慢慢停（双指滚动不带）。 */
        public boolean inertia = true;
        /** 惯性的阻尼时间常数 τ（毫秒，ScrollInertia.tauMs 按档位给）：越小停得越快。 */
        public int inertiaTauMs = ScrollInertia.tauMs(ScrollInertia.DEFAULT_LEVEL);
        /** 松手时手指沿滚动方向至少这么快（dp/ms）才算"拨"、起惯性；慢慢滚照旧松手就停。 */
        public float inertiaMinSpeed = 0.6f;
        /** 连续滚动：按 1/120 格平滑地出，不按格（主机设了分辨率倍率才真平滑，见 Hid.WheelOut）。 */
        public boolean smoothScroll = false;
        /** 小红点灵敏度：TrackPoint 的速度曲线整体乘这个（设置里 0.3–3.0）。 */
        public float stickSpeed = 1.0f;
        /** 轻点红点 = 左键（TrackPoint 的 press to select）。 */
        public boolean stickTap = false;   // 2026-11-01 用户要关（真 ThinkPad 的"按红点 = 点击"也默认关）
        /** 小红点的负惯性（起步、停下更利落，见 TrackPoint）。 */
        public boolean stickInertia = true;
        /**
         * 中键滑动（中键自己当滚动杆，TrackPoint 的 lever）的滚动速度倍数：乘到滚动杆那条曲线（TrackPoint.LEVER_*）上，只管它，按住中键推红点的不变
         * （设置里 25%–200%、步长 5%，默认 100%；协议 22 加的，2026-11-01 晚用户："小红点的中键滑动速度还是太快"）。
         */
        public float leverSpeed = 1.0f;
        /**
         * 小红点推光标（和左键杆）的加速（0–1）：平台以后那一段陡升按它缩，推满时的速度 = 平台 + 加速 × (最快速度 − 平台)（TrackPoint.moveTop）。
         * 0 = 推过平台以后不再变快。设置里 0%–100%、步长 5%，默认 100%（= 以前的曲线；范围、默认值在 TrackPoint.ACCEL_PCT_*，百分数换算走 TrackPoint.accelFraction）；
         * 协议 23 加的，2026-11-01 晚用户："小红点也有点快不太好控制"。中键滚动的曲线、触控板都不跟它。
         */
        public float stickAccel = TrackPoint.accelFraction(TrackPoint.ACCEL_PCT_DEFAULT);
        /**
         * 加速 100% 时小红点推满的速度（计数 / 秒，不含灵敏度）：加速不到 100% 时实际推满的速度按比例比它低（TrackPoint.moveTop），加速 0 时不起作用。
         * 设置里 500–3000、步长 100，默认 TrackPoint.TOP_SPEED_DEFAULT（= MOVE_MAX 1200，= 以前）；协议 23。
         */
        public float stickMaxSpeed = TrackPoint.TOP_SPEED_DEFAULT;
        /**
         * 实体键盘 F 行平时按出第二功能（"F 行翻转"，协议 24）。不是手势参数，手势引擎不看：跟着 CMD_CONFIG 走，TouchRouter.setConfig 交给 KeyHolds
         * （Fn+Esc 翻的也是它）。App 按当前模式的设置填（Boards.frowFlip，Windows 模式默认假、Mac 模式默认真）。
         */
        public boolean frowFlip = false;
    }

    private static final int MAX_POINTERS = 16;
    /** 计算加速度时速度的上限（dp/ms），防止一次抖动把光标甩飞。 */
    private static final float MAX_VELOCITY = 3f;
    /** 速度到这里（dp/ms，约 5 厘米/秒）精细移动的放慢完全消失。 */
    private static final float PRECISION_FULL_SPEED = 0.3f;
    /** 平滑速度的时间常数（ms）：按单帧算的速度忽大忽小，倍率跟着抖，光标会发飘。 */
    private static final float VELOCITY_SMOOTH_MS = 30f;
    /** 两指间距变化超过这么多、且比两指整体移动多，才判成开合（否则是滚动）。 */
    private static final float PINCH_DECIDE_DP = 16f;
    /**
     * 双击第二下：先松开第一下，隔 DOUBLE_GAP_MS 再按下，按住 DOUBLE_HOLD_MS 再松开。
     * 系统判断双击要求两次点击之间至少隔 40ms，挨得太紧反而不算双击。
     */
    static final int DOUBLE_GAP_MS = 60;
    static final int DOUBLE_HOLD_MS = 40;

    private static final int TIMER_NONE = 0;
    /** 轻点后左键按着，到点松开（期间没有第二次按下）。 */
    private static final int TIMER_RELEASE_TAP = 1;
    /** 双击：到点按下第二下。 */
    private static final int TIMER_DOUBLE_DOWN = 2;
    /** 双击：到点松开第二下。 */
    private static final int TIMER_DOUBLE_UP = 3;
    /** 单指按住不动：到点按下左键，进入拖动。 */
    private static final int TIMER_LONG_PRESS = 4;
    /** 惯性滚动在转：每帧推进一步。和别的用途在时间上互斥（起转时一定是空的，转着时任何按下都先停它）。 */
    private static final int TIMER_SPIN = 5;

    /** 惯性每帧隔多久（约 62 帧/秒；蓝牙一个报文约 11.25ms，不会排队）。 */
    static final int SPIN_FRAME_MS = 16;
    /** 松手速度：取松手前这么久里首尾两帧的差。 */
    static final int FLING_WINDOW_MS = 60;
    /** 最后一帧离松手超过这么久（手指停住了再抬）：不算拨。 */
    static final int FLING_LAST_GAP_MS = 40;
    /** 记最近多少帧边缘滚动的位置（240Hz 的触摸屏 60ms 也有 15 帧）。 */
    private static final int FLING_SAMPLES = 16;
    /** 连续模式双指滚动：两指一起走了这么远再定锁不锁主方向。 */
    private static final float AXIS_LOCK_DP = 30f;

    private static final int TWO_UNDECIDED = 0;
    private static final int TWO_SCROLL = 1;
    private static final int TWO_PINCH = 2;

    /** 连续模式双指滚动的方向锁：还没定 / 只竖 / 只横 / 两个都要（斜着滚）。 */
    private static final int AXIS_UNDECIDED = 0;
    private static final int AXIS_V = 1;
    private static final int AXIS_H = 2;
    private static final int AXIS_BOTH = 3;
    /** scroll() 这一帧先不出哪一轴（攒着）：方向锁还没定时，非主方向那一轴。 */
    private static final int HOLD_NONE = 0;
    private static final int HOLD_X = 1;
    private static final int HOLD_Y = 2;

    private final Output out;
    private final Config cfg;

    private final boolean[] active = new boolean[MAX_POINTERS];
    private final float[] downX = new float[MAX_POINTERS];
    private final float[] downY = new float[MAX_POINTERS];
    private final float[] lastX = new float[MAX_POINTERS];
    private final float[] lastY = new float[MAX_POINTERS];
    private final float[] curX = new float[MAX_POINTERS];
    private final float[] curY = new float[MAX_POINTERS];
    private int activeCount;

    // ── 本次手势 ──
    private long gestureStart;
    private long lastFrameTime;
    private int maxFingers;
    private boolean moved;
    private float remX, remY;
    /** 平滑后的手指速度（dp/ms），-1 = 本次手势还没算过。 */
    private float velocity;
    private float scrollAccX, scrollAccY;
    private float swipeAccX, swipeAccY;
    private boolean swipeFired;
    private int twoMode;
    private float twoStartDist, twoStartX, twoStartY;
    private float pinchCounted;
    private boolean zooming;
    /** 本次手势从哪条边缘带开始（EDGE_* 的组合，0 = 不是边缘手势）；两种都有时按实际滑的方向定下来。 */
    private int edgeMode;
    /** 本次手势一开始抓住了转着的惯性：不算轻点、不起长按（动了照常移动 / 滚动）。 */
    private boolean caught;
    /** 本次手势里有手指是被取消的（手掌、丢数据）：松手不起惯性。 */
    private boolean noFling;
    /** 边缘滚动：沿滚动方向累计划过的 dp，和最近几帧的（时间, 位置），松手时算速度。 */
    private float edgeTravel;
    private final long[] flingT = new long[FLING_SAMPLES];
    private final float[] flingPos = new float[FLING_SAMPLES];
    private int flingCount, flingHead;
    /** 连续模式下"每滚过一格的距离咔嗒一下"：上一下咔嗒之后出了多少单位（带方向，来回抖不会一直响）。 */
    private int tickAccX, tickAccY;
    /** 连续模式双指滚动的方向锁（AXIS_*）。 */
    private int twoAxis;

    // ── 惯性（转着 = timerAction == TIMER_SPIN） ──
    /** 速度大小（格/秒）、方向（+1 / -1，同松手前手指的走向）、竖还是横。 */
    private double spinSpeed;
    private int spinSign;
    private boolean spinVertical;
    /** 上一帧推进到的时间、起转时间（15 秒兜底）。 */
    private long spinLast, spinStart;
    /** 宿主告诉的：Ctrl 按着（键盘锁了 Ctrl、自带 Ctrl 的键按着、开合缩放中）。Ctrl + 滚轮是缩放：不起惯性、连续模式照旧按格。 */
    private boolean ctrlHeld;

    // ── 中键滚动（宿主告诉中键按着没有） ──
    private boolean scrollHold;
    /** 本次手势里有过中键滚动的帧：松手不起惯性。 */
    private boolean holdUsed;
    /** 正在中键滚动（上一帧就是）：没在的话这一帧从头算方向锁。 */
    private boolean holdScrolling;
    /** 这一段中键滚动手指走了多远（dp，带方向，定方向锁用）。 */
    private float holdTx, holdTy;

    // ── 跨手势：轻点拖动、双击 ──
    private int timerAction = TIMER_NONE;
    /** 本次手势是在轻点后的等待窗口里开始的（可能变成拖动或双击）。 */
    private boolean secondTouch;
    private boolean dragging;
    /** "颗粒"：攒着的手指移动距离、上一颗的时间。 */
    private float textureDist;
    private long lastTextureTime;
    /** 长按触发的拖动（左键按着，松手时松开）。 */
    private boolean longDrag;

    /** 引擎自己按住的键。 */
    private int buttons;
    /** 外部按住的键（左侧按钮条上的"左键"）。 */
    private int external;

    public GestureEngine(Output out, Config cfg) {
        this.out = out;
        this.cfg = cfg;
    }

    public Config config() {
        return cfg;
    }

    // ═══════════ 输入 ═══════════

    public void down(int id, float x, float y, long t) {
        down(id, x, y, t, 0);
    }

    /** edge：这根手指按在哪条边缘带里（EDGE_* 的组合；只对一次手势的第一根手指有意义）。 */
    public void down(int id, float x, float y, long t, int edge) {
        if (id < 0 || id >= MAX_POINTERS || active[id]) return;
        if (!cfg.edgeScroll) edge &= ~EDGE_VERTICAL;
        if (!cfg.edgeScrollHorizontal) edge &= ~EDGE_HORIZONTAL;
        if (activeCount == 0) startGesture(t, edge);
        active[id] = true;
        downX[id] = lastX[id] = curX[id] = x;
        downY[id] = lastY[id] = curY[id] = y;
        activeCount++;
        maxFingers = Math.max(maxFingers, activeCount);

        if (activeCount == 2) {
            twoMode = TWO_UNDECIDED;
            float[] p = twoPoints();
            twoStartDist = (float) Math.hypot(p[2] - p[0], p[3] - p[1]);
            twoStartX = (p[0] + p[2]) / 2;
            twoStartY = (p[1] + p[3]) / 2;
            pinchCounted = twoStartDist;
        }
        if (activeCount > 1 && timerAction == TIMER_LONG_PRESS) {
            out.cancelSchedule();   // 第二根手指来了：不是长按
            timerAction = TIMER_NONE;
        }
        if (activeCount > 1 && longDrag) {
            // 长按拖动中又按下一根手指：结束拖动，按多指处理
            longDrag = false;
            setButtons(buttons & ~Proto.BUTTON_LEFT);
            out.haptic(HAPTIC_DRAG_END);
        }
        if (activeCount > 1 && secondTouch) {
            // 轻点后接着来的是多指手势：先把上一次单击完成，再按多指处理
            secondTouch = false;
            if (dragging) {
                dragging = false;
                out.haptic(HAPTIC_DRAG_END);
            }
            setButtons(buttons & ~Proto.BUTTON_LEFT);
        }
    }

    public void move(int id, float x, float y) {
        if (id < 0 || id >= MAX_POINTERS || !active[id]) return;
        curX[id] = x;
        curY[id] = y;
    }

    public void endFrame(long t) {
        if (activeCount == 0) return;
        float sumDx = 0, sumDy = 0;
        float slop = maxFingers > 1 ? cfg.multiTapSlopDp : cfg.tapSlopDp;
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (!active[i]) continue;
            sumDx += curX[i] - lastX[i];
            sumDy += curY[i] - lastY[i];
            lastX[i] = curX[i];
            lastY[i] = curY[i];
            if (timerAction == TIMER_LONG_PRESS
                    && Math.hypot(curX[i] - downX[i], curY[i] - downY[i]) > cfg.longPressSlopDp) {
                out.cancelSchedule();   // 手指挪了一点点（比如在微调光标）：不是长按
                timerAction = TIMER_NONE;
            }
            if (!moved && Math.hypot(curX[i] - downX[i], curY[i] - downY[i]) > slop) {
                moved = true;
                if (timerAction == TIMER_LONG_PRESS) {   // 动了：不是长按
                    out.cancelSchedule();
                    timerAction = TIMER_NONE;
                }
                if (secondTouch && maxFingers == 1) {
                    dragging = true;
                    out.haptic(HAPTIC_DRAG_START);
                }
            }
        }
        float dx = sumDx / activeCount;
        float dy = sumDy / activeCount;
        long dt = Math.max(1, t - lastFrameTime);
        lastFrameTime = t;

        boolean holdFrame = maxFingers == 1 && scrollHold && !longDrag && !dragging;
        if (!holdFrame) holdScrolling = false;
        if (maxFingers == 1 && longDrag) {
            pointer(dx, dy, dt);   // 长按后移动 = 拖动（左键按着）
        } else if (holdFrame) {
            // 中键按着：单指划 = 滚动（边缘带里按下的也是，不起惯性）；同边缘滚动，挪出轻点的容差才开始，轻点照旧是单击
            if (moved) holdScroll(dx, dy);
            else holdScrolling = false;
        } else if (maxFingers == 1 && edgeMode != 0) {
            if (moved) {
                edgeScroll(dx, dy);
                recordFling(t);
            }
        } else if (maxFingers == 1) {
            pointer(dx, dy, dt);
            if (!dragging && external == 0) texture(dx, dy, t);   // 拖动时有持续轻震，颗粒会打断它
        } else if (maxFingers == 2 && activeCount == 2) {
            twoFingers(dx, dy);
        } else if (maxFingers >= 3) {
            swipe(dx, dy);
        }
    }

    public void up(int id, long t) {
        up(id, t, false);
    }

    /** canceled：这根手指是被取消的（手掌、丢数据）：照旧当抬起收尾，但这次手势松手不起惯性。 */
    public void up(int id, long t, boolean canceled) {
        if (id < 0 || id >= MAX_POINTERS || !active[id]) return;
        if (canceled) noFling = true;
        active[id] = false;
        activeCount--;
        if (activeCount > 0) return;
        finishGesture(t);
    }

    /** 系统取消了触摸、或触摸屏被释放：松开一切，不算点击。转着的惯性也停（不震）。 */
    public void cancel() {
        for (int i = 0; i < MAX_POINTERS; i++) active[i] = false;
        activeCount = 0;
        if (timerAction != TIMER_NONE) out.cancelSchedule();
        timerAction = TIMER_NONE;
        secondTouch = false;
        if (dragging || longDrag) out.haptic(HAPTIC_DRAG_END);
        dragging = false;
        longDrag = false;
        scrollHold = false;
        holdScrolling = false;
        endZoom();
        // 只靠 external 按着的键（实体键按住的"左键"，CMD_HOLD_LEFT）也要发松开
        boolean held = (buttons | external) != 0;
        external = 0;
        buttons = 0;
        if (held) out.mouse(0, 0, 0, 0, 0);
    }

    public void onTimer(long t) {
        int action = timerAction;
        timerAction = TIMER_NONE;
        switch (action) {
            case TIMER_RELEASE_TAP:
            case TIMER_DOUBLE_UP:
                setButtons(buttons & ~Proto.BUTTON_LEFT);
                break;
            case TIMER_DOUBLE_DOWN:
                setButtons(buttons | Proto.BUTTON_LEFT);
                timerAction = TIMER_DOUBLE_UP;
                out.schedule(DOUBLE_HOLD_MS);
                break;
            case TIMER_LONG_PRESS:
                if (activeCount == 1 && !moved) {
                    longDrag = true;
                    setButtons(buttons | Proto.BUTTON_LEFT);
                    out.haptic(HAPTIC_DRAG_START);
                }
                break;
            case TIMER_SPIN:
                spinStep(t);
                break;
            default:
                break;
        }
    }

    /**
     * 惯性转着时停下（宿主在屏幕别的地方看到按下时调：键、按钮、"左键"、空白处、分屏透传归系统的手指都算）。
     * feedback = 震一下"刹住"（跟"滚动咔嗒"开关走）。返回刚才在不在转。按在触控板上的不用调，{@link #down} 自己抓。
     */
    public boolean stopSpin(boolean feedback) {
        if (timerAction != TIMER_SPIN) return false;
        out.cancelSchedule();
        timerAction = TIMER_NONE;
        if (feedback && cfg.scrollTicks != TICKS_OFF) out.haptic(HAPTIC_SPIN_CATCH);
        return true;
    }

    /** 惯性在转（测试用）。 */
    boolean spinning() {
        return timerAction == TIMER_SPIN;
    }

    /** Ctrl 按着没有（宿主每次修饰键变了都告诉）：按着时不起惯性、转着的马上停（不然转着的滚轮接上 Ctrl 就成了缩放）、连续模式照旧按格。 */
    public void setCtrlHeld(boolean held) {
        ctrlHeld = held;
        if (held) stopSpin(false);
    }

    /**
     * 中键按着没有（宿主在三键条 / 键盘最下面那排的中键第一个按下、最后一个松开时告诉）。按着时单指在触控板上划 = 滚动、不起长按；
     * 手势进行到一半按下 / 松开也行，从下一帧起换。
     */
    public void setScrollHold(boolean on) {
        if (on == scrollHold) return;
        scrollHold = on;
        if (on && timerAction == TIMER_LONG_PRESS) {   // 按着中键不起长按拖动
            out.cancelSchedule();
            timerAction = TIMER_NONE;
        }
    }

    /** 中键按着吗（测试用）。 */
    boolean scrollHold() {
        return scrollHold;
    }

    /** 现在按着的鼠标键（引擎自己的 | 外部的）：小红点按着键时不发颗粒（是拖动，有持续轻震）。 */
    int heldButtons() {
        return buttons | external;
    }

    /** 这一刻滚动按连续模式出吗（锁着 Ctrl 时照旧按格）：小红点的中键滚动照这个出。 */
    boolean smoothNow() {
        return smooth();
    }

    /** 小红点推出来的光标位移（计数），按着的键照带。 */
    public void externalMove(int dx, int dy) {
        if (dx != 0 || dy != 0) out.mouse(buttons | external, dx, dy, 0, 0);
    }

    /** 小红点中键滚动出来的滚轮（1/120 格，正 = 往上 / 往右），按着的键照带。 */
    public void externalScroll(int wheel, int pan) {
        if (wheel != 0 || pan != 0) out.mouse(buttons | external, 0, 0, wheel, pan);
    }

    /** 外部按住 / 松开某个键（左侧按钮条用）。 */
    public void setExternalButtons(int mask) {
        external = mask;
        out.mouse(buttons | external, 0, 0, 0, 0);
    }

    /** 点一下某个键（三指轻点映射成中键之类）。 */
    public void click(int button) {
        out.mouse(buttons | external | button, 0, 0, 0, 0);
        out.mouse(buttons | external, 0, 0, 0, 0);
    }

    /** 滚轮滚 n 格（正 = 向上），按着的键照带（动作"滚轮向上 / 向下"用）。转着的惯性先停（相当于手又摸到了滚轮），不震。 */
    public void wheel(int n) {
        stopSpin(false);
        out.mouse(buttons | external, 0, 0, n * Hid.WHEEL_UNIT, 0);
    }

    // ═══════════ 内部 ═══════════

    private void startGesture(long t, int edge) {
        // 转着的惯性：碰触控板 = 抓住，马上停、"刹住"震一下；这次手势不算轻点、不起长按（不然抓一下就点到了光标底下的东西）
        caught = stopSpin(true);
        gestureStart = t;
        edgeMode = edge;
        lastFrameTime = t;
        maxFingers = 0;
        moved = false;
        remX = remY = 0;
        textureDist = 0;
        velocity = -1;
        scrollAccX = scrollAccY = 0;
        tickAccX = tickAccY = 0;
        twoAxis = AXIS_UNDECIDED;
        edgeTravel = 0;
        flingCount = 0;
        noFling = false;
        swipeAccX = swipeAccY = 0;
        swipeFired = false;
        twoMode = TWO_UNDECIDED;
        secondTouch = false;
        holdUsed = false;
        holdScrolling = false;
        switch (timerAction) {
            case TIMER_RELEASE_TAP:
                out.cancelSchedule();
                if (edge != 0) {
                    // 轻点完马上去边缘滚动：先把单击完成，不当成拖动
                    setButtons(buttons & ~Proto.BUTTON_LEFT);
                } else {
                    // 轻点后的等待窗口里又按下了：可能是拖动或双击，先不松开
                    secondTouch = true;
                }
                break;
            case TIMER_DOUBLE_DOWN:
                // 双击还没发完就又按下了（三击）：把第二下立刻补完
                out.cancelSchedule();
                setButtons(buttons | Proto.BUTTON_LEFT);
                setButtons(buttons & ~Proto.BUTTON_LEFT);
                break;
            case TIMER_DOUBLE_UP:
                out.cancelSchedule();
                setButtons(buttons & ~Proto.BUTTON_LEFT);
                break;
            default:
                break;
        }
        timerAction = TIMER_NONE;
        longDrag = false;
        if (cfg.longPressDrag && !secondTouch && !caught && !scrollHold) {
            timerAction = TIMER_LONG_PRESS;
            out.schedule(cfg.longPressMs);
        }
    }

    private void finishGesture(long t) {
        endZoom();
        if (timerAction == TIMER_LONG_PRESS) {
            out.cancelSchedule();
            timerAction = TIMER_NONE;
        }
        if (longDrag) {
            longDrag = false;
            setButtons(buttons & ~Proto.BUTTON_LEFT);
            out.haptic(HAPTIC_DRAG_END);
            return;
        }
        if (maxFingers == 1 && edgeMode != 0 && moved && !holdUsed) {
            fling(t);   // 边缘滚动松手：够快就起惯性（动过了，不会是轻点）。中键滚动过的不起
            return;
        }
        long duration = t - gestureStart;
        int limit = maxFingers == 1 ? cfg.tapMaxMs : cfg.tapMaxMs + 50;
        boolean isTap = !moved && duration <= limit && !caught;

        if (secondTouch) {
            secondTouch = false;
            if (dragging) {
                dragging = false;
                setButtons(buttons & ~Proto.BUTTON_LEFT);
                out.haptic(HAPTIC_DRAG_END);
            } else if (isTap) {
                // 双击：松开第一下，隔一会儿再点第二下
                setButtons(buttons & ~Proto.BUTTON_LEFT);
                timerAction = TIMER_DOUBLE_DOWN;
                out.schedule(DOUBLE_GAP_MS);
                out.haptic(HAPTIC_CLICK);
            } else {
                // 按住没动、时间又长：只是把第一下单击完成
                setButtons(buttons & ~Proto.BUTTON_LEFT);
            }
            return;
        }
        if (!isTap) return;

        if (maxFingers == 1) {
            out.haptic(HAPTIC_CLICK);
            setButtons(buttons | Proto.BUTTON_LEFT);
            if (cfg.tapToDrag) {
                timerAction = TIMER_RELEASE_TAP;
                out.schedule(cfg.dragWindowMs);
            } else {
                setButtons(buttons & ~Proto.BUTTON_LEFT);
            }
        } else if (maxFingers == 2) {
            out.haptic(HAPTIC_CLICK);
            setButtons(buttons | Proto.BUTTON_RIGHT);
            setButtons(buttons & ~Proto.BUTTON_RIGHT);
        } else {
            out.haptic(HAPTIC_GESTURE);
            out.gesture(Math.min(maxFingers, 4), GESTURE_TAP);
        }
    }

    private void pointer(float dx, float dy, long dt) {
        float v = Math.min((float) Math.hypot(dx, dy) / dt, MAX_VELOCITY);
        velocity = velocity < 0 ? v : velocity + dt / (VELOCITY_SMOOTH_MS + dt) * (v - velocity);
        float slow = cfg.slowFactor + (1 - cfg.slowFactor) * Math.min(1f, velocity / PRECISION_FULL_SPEED);
        float gain = cfg.pointerSpeed * (1 + cfg.acceleration * velocity) * slow;
        float fx = dx * gain + remX;
        float fy = dy * gain + remY;
        int ix = (int) fx;   // 向零取整，余数留到下一帧，慢慢挪也不会丢
        int iy = (int) fy;
        remX = fx - ix;
        remY = fy - iy;
        if (ix != 0 || iy != 0) out.mouse(buttons | external, ix, iy, 0, 0);
    }

    /** 手指每划过 textureStepDp 发一颗；离上一颗太近就跳过（距离照扣，不攒着补发）。 */
    private void texture(float dx, float dy, long t) {
        if (!cfg.texture) return;
        textureDist += (float) Math.hypot(dx, dy);
        if (textureDist < cfg.textureStepDp) return;
        textureDist %= cfg.textureStepDp;
        if (t - lastTextureTime < cfg.textureMinGapMs) return;
        lastTextureTime = t;
        out.haptic(HAPTIC_TEXTURE);
    }

    /** 双指：先判断是滚动还是开合，判定后这次手势就一直按它处理。 */
    private void twoFingers(float dx, float dy) {
        float[] p = twoPoints();
        float dist = (float) Math.hypot(p[2] - p[0], p[3] - p[1]);
        if (twoMode == TWO_UNDECIDED) {
            float spread = Math.abs(dist - twoStartDist);
            float travel = (float) Math.hypot((p[0] + p[2]) / 2 - twoStartX, (p[1] + p[3]) / 2 - twoStartY);
            if (cfg.pinchZoom && spread > PINCH_DECIDE_DP && spread > travel) {
                twoMode = TWO_PINCH;
            } else if (travel > cfg.multiTapSlopDp) {
                twoMode = TWO_SCROLL;
            }
        }
        if (twoMode == TWO_SCROLL) {
            lockedScroll((p[0] + p[2]) / 2 - twoStartX, (p[1] + p[3]) / 2 - twoStartY, dx, dy, cfg.naturalScroll);
        } else if (twoMode == TWO_PINCH) {
            int steps = (int) ((dist - pinchCounted) / cfg.pinchStepDp);
            if (steps != 0) {
                pinchCounted += steps * cfg.pinchStepDp;
                zooming = true;
                out.zoom(steps);
            }
        }
    }

    /**
     * 两个方向都能滚的滚动（双指滚动、中键滚动）：tx / ty 是这一段手指一共走了多远（定方向锁用），dx / dy 是这一帧的。
     * 连续模式：手指不可能纯竖，另一轴每帧都带出一点点，内容会横着抖。走过 AXIS_LOCK_DP 时一个方向占到 2 倍以上
     * 就锁住它（像 Mac 的"轨道"），斜着滚的不锁；没定之前先只出主方向，另一轴攒着（锁定后丢掉、不锁就补出来）。按格模式不用：攒不满一格。
     */
    private void lockedScroll(float tx, float ty, float dx, float dy, boolean natural) {
        int hold = HOLD_NONE;
        if (smooth()) {
            if (twoAxis == AXIS_UNDECIDED && Math.hypot(tx, ty) >= AXIS_LOCK_DP) {
                float ax = Math.abs(tx), ay = Math.abs(ty);
                twoAxis = ay >= 2 * ax ? AXIS_V : ax >= 2 * ay ? AXIS_H : AXIS_BOTH;
            }
            if (twoAxis == AXIS_V) {
                dx = 0;
                scrollAccX = 0;
            } else if (twoAxis == AXIS_H) {
                dy = 0;
                scrollAccY = 0;
            } else if (twoAxis == AXIS_UNDECIDED) {
                hold = Math.abs(ty) >= Math.abs(tx) ? HOLD_X : HOLD_Y;
            }
        }
        scroll(dx, dy, false, hold, natural);
    }

    /**
     * 中键按着时单指划：滚动，方向"推哪边视野往哪边走"（手指往下 = 往下翻，同拖滚动条 / 推小红点），不跟自然滚动开关。
     * 刚进中键滚动（这次手势的第一帧，或者中键半路按下）时攒的量、方向锁从头算。
     */
    private void holdScroll(float dx, float dy) {
        if (!holdScrolling) {
            holdScrolling = true;
            holdTx = holdTy = 0;
            scrollAccX = scrollAccY = 0;
            tickAccX = tickAccY = 0;
            twoAxis = AXIS_UNDECIDED;
        }
        holdUsed = true;
        holdTx += dx;
        holdTy += dy;
        lockedScroll(holdTx, holdTy, dx, dy, false);
    }

    private void endZoom() {
        if (!zooming) return;
        zooming = false;
        out.zoomEnd();
    }

    /** 当前两根手指的坐标 {x1, y1, x2, y2}（只在恰好两根手指按着时调用）。 */
    private float[] twoPoints() {
        float[] p = new float[4];
        int n = 0;
        for (int i = 0; i < MAX_POINTERS && n < 2; i++) {
            if (!active[i]) continue;
            p[n * 2] = curX[i];
            p[n * 2 + 1] = curY[i];
            n++;
        }
        return p;
    }

    /** 边缘滚动：四个角两种都算，第一次滑过阈值时按主要方向定下来。 */
    private void edgeScroll(float dx, float dy) {
        if (edgeMode == (EDGE_VERTICAL | EDGE_HORIZONTAL)) {
            int id = firstActive();
            float tx = Math.abs(curX[id] - downX[id]);
            float ty = Math.abs(curY[id] - downY[id]);
            edgeMode = ty >= tx ? EDGE_VERTICAL : EDGE_HORIZONTAL;
        }
        float d = edgeMode == EDGE_VERTICAL ? dy : dx;
        edgeTravel += d;
        if (edgeMode == EDGE_VERTICAL) scroll(0, d, false, HOLD_NONE);
        else scroll(d, 0, false, HOLD_NONE);
    }

    /** 记一帧边缘滚动的位置（松手时算速度用），只留最近 FLING_SAMPLES 帧。 */
    private void recordFling(long t) {
        flingT[flingHead] = t;
        flingPos[flingHead] = edgeTravel;
        flingHead = (flingHead + 1) % FLING_SAMPLES;
        if (flingCount < FLING_SAMPLES) flingCount++;
    }

    /**
     * 松手速度（dp/ms，带方向）：松手前 FLING_WINDOW_MS 里首尾两帧的差。最后一帧离松手超过 FLING_LAST_GAP_MS（停住了再抬）、
     * 窗口里不到两帧都当 0。拨到一半反向再松手的，按松手前那一小段的方向算。
     */
    private float flingSpeed(long tUp) {
        if (flingCount < 2) return 0;
        int newest = (flingHead + FLING_SAMPLES - 1) % FLING_SAMPLES;
        if (tUp - flingT[newest] > FLING_LAST_GAP_MS) return 0;
        int oldest = newest;
        for (int i = 1; i < flingCount; i++) {
            int k = (newest + FLING_SAMPLES - i) % FLING_SAMPLES;
            if (tUp - flingT[k] > FLING_WINDOW_MS) break;
            oldest = k;
        }
        long dt = flingT[newest] - flingT[oldest];
        if (oldest == newest || dt <= 0) return 0;
        return (flingPos[newest] - flingPos[oldest]) / dt;
    }

    /**
     * 边缘滚动松手：开着惯性、不是被取消的、没锁着 Ctrl、够快，就起转。起转速度 = 手指的滚动速度换算成格/秒，不放大
     * （内容接着手上的劲儿走），截到 ScrollInertia.MAX_SPEED。
     */
    private void fling(long t) {
        float v = flingSpeed(t);
        boolean go = cfg.inertia && !noFling && !ctrlHeld && cfg.scrollStepDp > 0 && Math.abs(v) >= cfg.inertiaMinSpeed
                && (edgeMode == EDGE_VERTICAL || edgeMode == EDGE_HORIZONTAL);
        double speed = go ? Math.min(ScrollInertia.MAX_SPEED, Math.abs(v) / cfg.scrollStepDp * 1000) : 0;
        out.edgeRelease(v, (float) speed);
        if (!go) return;
        spinSpeed = speed;
        spinSign = v > 0 ? 1 : -1;
        spinVertical = edgeMode == EDGE_VERTICAL;
        spinLast = spinStart = t;
        timerAction = TIMER_SPIN;
        out.schedule(SPIN_FRAME_MS);
        if (cfg.scrollTicks != TICKS_OFF) out.haptic(HAPTIC_SPIN_START);
    }

    /** 惯性推进到 t：按真实经过的时间走（闭式解，定时器晚了也不丢距离），走的格数折成"手指划过的 dp"喂给 scroll（不咔嗒）。 */
    private void spinStep(long t) {
        long dt = t - spinLast;
        if (dt > 0) {
            spinLast = t;
            double[] r = ScrollInertia.advance(spinSpeed, dt / 1000.0, cfg.inertiaTauMs / 1000.0);
            spinSpeed = r[0];
            float d = (float) (r[1] * cfg.scrollStepDp) * spinSign;
            if (spinVertical) scroll(0, d, true, HOLD_NONE);
            else scroll(d, 0, true, HOLD_NONE);
        }
        if (spinSpeed <= 0 || t - spinStart >= ScrollInertia.MAX_MS) return;   // 自然停下（或兜底的 15 秒到了）：不震
        timerAction = TIMER_SPIN;
        out.schedule(SPIN_FRAME_MS);
    }

    /** 这一刻按连续模式出（锁着 Ctrl 时照旧按格：Ctrl + 滚轮是缩放，应用的缩放一级一级的，小数格各应用处理不一）。 */
    private boolean smooth() {
        return cfg.smoothScroll && !ctrlHeld;
    }

    private int firstActive() {
        for (int i = 0; i < MAX_POINTERS; i++) if (active[i]) return i;
        return 0;
    }

    /**
     * 手指（或惯性）沿滚动方向划过 dx / dy（dp）：攒着，按格模式攒满 scrollStepDp 出一格（= WHEEL_UNIT 个单位），
     * 连续模式按 WHEEL_UNIT / scrollStepDp 个单位每 dp 出、余数留着。spin = 惯性推进的：不咔嗒。
     * hold = 这一帧先不出哪一轴（照攒，见 twoFingers 的方向锁）。
     */
    private void scroll(float dx, float dy, boolean spin, int hold) {
        scroll(dx, dy, spin, hold, cfg.naturalScroll);
    }

    /** natural = 内容跟着手指走（自然滚动）；中键滚动固定为假（视野跟着手指走）。 */
    private void scroll(float dx, float dy, boolean spin, int hold, boolean natural) {
        scrollAccX += dx;
        scrollAccY += dy;
        boolean smooth = smooth();
        int per = smooth ? Hid.WHEEL_UNIT : 1;   // 一格出几个：按格 1 格，连续 120 个单位
        int nY = hold == HOLD_Y ? 0 : (int) (scrollAccY * per / cfg.scrollStepDp);
        int nX = hold == HOLD_X ? 0 : (int) (scrollAccX * per / cfg.scrollStepDp);
        scrollAccY -= nY * cfg.scrollStepDp / per;
        scrollAccX -= nX * cfg.scrollStepDp / per;
        if (nX == 0 && nY == 0) return;
        // 滚轮正值 = 往上滚（内容下移）；横向 AC Pan 正值 = 往右滚（内容左移）。
        // 自然滚动时内容跟着手指走：手指下移 → 内容下移 → 滚轮正。
        int unit = smooth ? 1 : Hid.WHEEL_UNIT;
        int wheel = (natural ? nY : -nY) * unit;
        int pan = (natural ? -nX : nX) * unit;
        out.mouse(buttons | external, 0, 0, wheel, pan);
        if (spin || cfg.scrollTicks == TICKS_OFF) return;   // 惯性转的时候不咔嗒（像罗技滚轮自由转时没有段落）
        if (!smooth) {
            out.haptic(HAPTIC_TICK);
            return;
        }
        // 连续模式：每滚过一格的距离咔嗒一下（像带棘轮的平滑滚轮）
        tickAccX += nX;
        tickAccY += nY;
        boolean tick = false;
        if (Math.abs(tickAccY) >= Hid.WHEEL_UNIT) {
            tickAccY -= tickAccY > 0 ? Hid.WHEEL_UNIT : -Hid.WHEEL_UNIT;
            tick = true;
        }
        if (Math.abs(tickAccX) >= Hid.WHEEL_UNIT) {
            tickAccX -= tickAccX > 0 ? Hid.WHEEL_UNIT : -Hid.WHEEL_UNIT;
            tick = true;
        }
        if (tick) out.haptic(HAPTIC_TICK);
    }

    private void swipe(float dx, float dy) {
        if (swipeFired) return;
        swipeAccX += dx;
        swipeAccY += dy;
        if (Math.hypot(swipeAccX, swipeAccY) < cfg.swipeThresholdDp) return;
        int kind;
        if (Math.abs(swipeAccX) > Math.abs(swipeAccY)) {
            kind = swipeAccX > 0 ? GESTURE_RIGHT : GESTURE_LEFT;
        } else {
            kind = swipeAccY > 0 ? GESTURE_DOWN : GESTURE_UP;
        }
        swipeFired = true;
        out.haptic(HAPTIC_GESTURE);
        out.gesture(Math.min(maxFingers, 4), kind);
    }

    private void setButtons(int mask) {
        buttons = mask;
        out.mouse(buttons | external, 0, 0, 0, 0);
    }
}
