package dev.douhua.server;

/**
 * 分屏透传接管前，判断"系统那边还有没有手指按着"。纯 Java、不依赖 Android（tools/test.sh 离线测）。
 *
 * 两路消息：
 * - 内核那边：Session 不独占地开着触摸屏（plain 描述符，系统和这里都收得到），事件喂给 {@link #feed}，按 slot 记
 *   "见过按下没抬起"（DOWN）/ "见过抬起"（UP）/ "不知道"（开着之前就按着、一个事件都没来的）。开头没看到、但来了坐标的
 *   也算按着（开着之前就按下、还在动）。丢过数据（SYN_DROPPED）全部回到"不知道"。
 * - 系统那边：dumpsys input 的 "Last Cooked Touch: pointerCount=N"（{@link TouchDevice#pressed}），交给 {@link #onPressed}。
 *
 * 等抬起的节奏（{@link #onPressed}）：第一次等 {@link #FIRST_WAIT_MS}，之后每轮翻倍、封顶 {@link #MAX_WAIT_MS}
 * （dumpsys input 持着 InputReader / InputDispatcher 的锁转储，别在用户正摸另一边应用时每 150ms 跑一次）。
 * 系统眼里的手指可能永远不抬：它只是 InputReader 最后一次收到事件时的状态，独占那一刻就冻住了
 * （比如全屏时带着手指接管，抬起被服务读走了）。所以等了 ≥ {@link #STALE_AFTER_MS}、最近 {@link #QUIET_MS}
 * 内核那边一个多点触控事件都没有、dumpsys 的数也没变、内核那边也没有见过按下没抬起的手指，就当系统记的是旧状态，照常接管。
 * 代价：开着 plain 之前就按下、之后一动不动的真手指（另一边应用上的长按）看不出来，会被当成旧状态——独占后它的抬起到不了系统，
 * 那个应用一直收着一个按住的触摸，和没有这道检查时一样。
 */
final class LiftWatch {
    static final long FIRST_WAIT_MS = 150;
    static final long MAX_WAIT_MS = 1000;
    static final long STALE_AFTER_MS = 1500;
    static final long QUIET_MS = 1000;
    static final long FORGET_AFTER_MS = 3000;
    /** {@link #onPressed} 的返回值：系统记的是旧状态，照常接管。 */
    static final long STALE = -1;

    private static final int UNKNOWN = 0;
    private static final int DOWN = 1;
    private static final int UP = 2;
    private static final int SLOTS = MtDecoder.SLOTS;

    private final int[] state = new int[SLOTS];
    /**
     * 内核那边见过按下没抬起、却 {@link #FORGET_AFTER_MS} 没有任何动静：把这些手指改回"不知道"、返回 true，
     * 交给 dumpsys 那一道（{@link #onPressed}，还会再等至少 {@link #STALE_AFTER_MS}）。
     * 防的是这里漏看了抬起（独占那一刻正好放开、读空 plain 到关掉独占之间到的事件会丢）就永远等下去、永远不接管。
     */
    boolean forgetIfQuiet(long now) {
        if (!anyDown() || now - lastActivity < FORGET_AFTER_MS) return false;
        for (int s = 0; s < SLOTS; s++) if (state[s] == DOWN) state[s] = UNKNOWN;
        return true;
    }

    /** 内核眼里当前的 slot（开着之前切到哪个不知道，按 0 算，和 MtDecoder 同一个假设）。 */
    private int slot;
    private long lastEventUs = Long.MIN_VALUE;
    /** 最近一次"有动静"（多点触控事件、丢数据、dumpsys 的数变了）的时间，毫秒（SystemClock.uptimeMillis 那一套）。 */
    private long lastActivity = Long.MIN_VALUE / 2;   // 相减不溢出

    private boolean waiting;
    private long waitStart;
    private int lastPressed;
    private long nextWait = FIRST_WAIT_MS;

    /** 喂一个 plain 上读到的事件。us：内核时间戳（微秒，只用来和独占描述符去重）；now：读到的时间（毫秒）。 */
    void feed(int type, int code, int value, long us, long now) {
        lastEventUs = us;
        if (type == MtDecoder.EV_SYN) {
            if (code == MtDecoder.SYN_DROPPED) {
                for (int s = 0; s < SLOTS; s++) state[s] = UNKNOWN;
                lastActivity = now;
            }
            return;
        }
        if (type != MtDecoder.EV_ABS) return;
        if (code == MtDecoder.ABS_MT_SLOT) {
            slot = value >= 0 && value < SLOTS ? value : -1;
            lastActivity = now;
            return;
        }
        if (code < MtDecoder.ABS_MT_FIRST || code > MtDecoder.ABS_MT_LAST) return;
        lastActivity = now;
        if (slot < 0) return;
        if (code == MtDecoder.ABS_MT_TRACKING_ID) {
            state[slot] = value >= 0 ? DOWN : UP;
        } else if (state[slot] == UNKNOWN) {
            state[slot] = DOWN;   // 开头没看到、还在动：开着之前就按下的
        }
    }

    /** 内核那边有见过按着、还没抬起的手指。 */
    boolean anyDown() {
        for (int s : state) if (s == DOWN) return true;
        return false;
    }

    /** 内核眼里当前的 slot（-1 = 超出范围）：交给独占后的 MtDecoder，后面的事件不一定再带 ABS_MT_SLOT。 */
    int slot() {
        return slot;
    }

    /** 最后一个事件的内核时间（微秒）；没有 = Long.MIN_VALUE。独占描述符上不晚于它的是重复的。 */
    long lastEventUs() {
        return lastEventUs;
    }

    /**
     * dumpsys 报系统眼里按着 pressed 根（-1 = 读不出来，不拦）。返回 0 = 可以接管；大于 0 = 再等这么多毫秒再查；
     * {@link #STALE} = 等够了、内核那边一直没动静，当作旧状态接管。
     */
    long onPressed(int pressed, long now) {
        if (pressed <= 0) return 0;
        if (!waiting) {
            waiting = true;
            waitStart = now;
            lastPressed = pressed;
            nextWait = FIRST_WAIT_MS;
        } else if (pressed != lastPressed) {
            lastPressed = pressed;
            lastActivity = now;
        }
        if (now - waitStart >= STALE_AFTER_MS && now - lastActivity >= QUIET_MS && !anyDown()) return STALE;
        long w = nextWait;
        nextWait = Math.min(nextWait * 2, MAX_WAIT_MS);
        return w;
    }

    /** 不再等了（接管了、不需要独占了）：下次从头计时、退避从头来。 */
    void stopWaiting() {
        waiting = false;
        nextWait = FIRST_WAIT_MS;
    }

    /** 已经等了多久（毫秒；没在等返回 0）。 */
    long waited(long now) {
        return waiting ? now - waitStart : 0;
    }
}
