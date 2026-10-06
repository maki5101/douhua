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
 * 一次手势里只要出现过多根手指，就一直按多指处理到全部抬起，避免抬起一根时光标乱跳。
 */
public final class GestureEngine {

    public interface Output {
        /** buttons 是当前按下状态；dx/dy/wheel/pan 是相对量。 */
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

    /** 按下点在左右边缘带里（单指上下滑 = 竖向滚动）。 */
    public static final int EDGE_VERTICAL = 1;
    /** 按下点在上下边缘带里（单指左右滑 = 横向滚动）。 */
    public static final int EDGE_HORIZONTAL = 2;

    public static final int TICKS_OFF = 0;
    public static final int TICKS_EDGE = 1;
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
        /** 滚动"咔嗒"震动：TICKS_OFF / TICKS_EDGE（只在边缘滚动时）/ TICKS_ALL（双指滚动也震）。 */
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
        /** 按下到抬起不超过这么久、且没移动，才算轻点。 */
        public int tapMaxMs = 250;
        public float tapSlopDp = 6f;
        public float multiTapSlopDp = 12f;
        /** 三指 / 四指滑多远算一次滑动。 */
        public float swipeThresholdDp = 48f;
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

    private static final int TWO_UNDECIDED = 0;
    private static final int TWO_SCROLL = 1;
    private static final int TWO_PINCH = 2;

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

    // ── 跨手势：轻点拖动、双击 ──
    private int timerAction = TIMER_NONE;
    /** 本次手势是在轻点后的等待窗口里开始的（可能变成拖动或双击）。 */
    private boolean secondTouch;
    private boolean dragging;
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

        if (maxFingers == 1 && longDrag) {
            pointer(dx, dy, dt);   // 长按后移动 = 拖动（左键按着）
        } else if (maxFingers == 1 && edgeMode != 0) {
            if (moved) edgeScroll(dx, dy);
        } else if (maxFingers == 1) {
            pointer(dx, dy, dt);
        } else if (maxFingers == 2 && activeCount == 2) {
            twoFingers(dx, dy);
        } else if (maxFingers >= 3) {
            swipe(dx, dy);
        }
    }

    public void up(int id, long t) {
        if (id < 0 || id >= MAX_POINTERS || !active[id]) return;
        active[id] = false;
        activeCount--;
        if (activeCount > 0) return;
        finishGesture(t);
    }

    /** 系统取消了触摸、或触摸屏被释放：松开一切，不算点击。 */
    public void cancel() {
        for (int i = 0; i < MAX_POINTERS; i++) active[i] = false;
        activeCount = 0;
        if (timerAction != TIMER_NONE) out.cancelSchedule();
        timerAction = TIMER_NONE;
        secondTouch = false;
        if (dragging || longDrag) out.haptic(HAPTIC_DRAG_END);
        dragging = false;
        longDrag = false;
        endZoom();
        external = 0;
        if (buttons != 0) setButtons(0);
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
            default:
                break;
        }
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

    // ═══════════ 内部 ═══════════

    private void startGesture(long t, int edge) {
        gestureStart = t;
        edgeMode = edge;
        lastFrameTime = t;
        maxFingers = 0;
        moved = false;
        remX = remY = 0;
        velocity = -1;
        scrollAccX = scrollAccY = 0;
        swipeAccX = swipeAccY = 0;
        swipeFired = false;
        twoMode = TWO_UNDECIDED;
        secondTouch = false;
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
        if (cfg.longPressDrag && !secondTouch) {
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
        long duration = t - gestureStart;
        int limit = maxFingers == 1 ? cfg.tapMaxMs : cfg.tapMaxMs + 50;
        boolean isTap = !moved && duration <= limit;

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
            scroll(dx, dy);
        } else if (twoMode == TWO_PINCH) {
            int steps = (int) ((dist - pinchCounted) / cfg.pinchStepDp);
            if (steps != 0) {
                pinchCounted += steps * cfg.pinchStepDp;
                zooming = true;
                out.zoom(steps);
            }
        }
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
        if (edgeMode == EDGE_VERTICAL) scroll(0, dy);
        else scroll(dx, 0);
    }

    private int firstActive() {
        for (int i = 0; i < MAX_POINTERS; i++) if (active[i]) return i;
        return 0;
    }

    private void scroll(float dx, float dy) {
        scrollAccX += dx;
        scrollAccY += dy;
        int stepsY = (int) (scrollAccY / cfg.scrollStepDp);
        int stepsX = (int) (scrollAccX / cfg.scrollStepDp);
        scrollAccY -= stepsY * cfg.scrollStepDp;
        scrollAccX -= stepsX * cfg.scrollStepDp;
        if (stepsX == 0 && stepsY == 0) return;
        // 滚轮正值 = 往上滚（内容下移）；横向 AC Pan 正值 = 往右滚（内容左移）。
        // 自然滚动时内容跟着手指走：手指下移 → 内容下移 → 滚轮正。
        int wheel = cfg.naturalScroll ? stepsY : -stepsY;
        int pan = cfg.naturalScroll ? -stepsX : stepsX;
        out.mouse(buttons | external, 0, 0, wheel, pan);
        boolean tick = cfg.scrollTicks == TICKS_ALL || (cfg.scrollTicks == TICKS_EDGE && edgeMode != 0);
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
