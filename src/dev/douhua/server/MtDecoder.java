package dev.douhua.server;

/**
 * Linux 多点触控协议 B（带 slot）的解码器。纯 Java、不依赖 Android，能离线测试（tools/test.sh）。
 *
 * 喂入原始事件（type, code, value）；每到 SYN_REPORT，把这一帧的变化按"按下 → 移动 → 帧结束 → 抬起"的顺序交给监听者，
 * 和 GestureEngine 要的顺序一致。
 * - 手掌（MT_TOOL_PALM）不算手指；手指中途被判成手掌，按取消处理（{@link Listener#cancel}）。
 * - 开始读之前就已经按着的手指（没见过它的 tracking id）一律忽略，直到它抬起、重新按下；但会记下"见过"（{@link #sawPreGrab}）：
 *   独占开始时按着的真手指，系统那边永远等不到它抬起（EVIOCGRAB 不补抬起），分屏透传时 Session 据此马上放开。
 *   这只能事后发现，堵不全，见 {@link #sawPreGrab}。
 * - 内核丢了数据（SYN_DROPPED）：把所有手指按取消处理，按着的手指也作废，直到重新按下。
 * - 外部要求停止（{@link #releaseAll}）：按抬起处理。
 */
final class MtDecoder {
    interface Listener {
        void down(int slot, int rawX, int rawY);

        void move(int slot, int rawX, int rawY);

        void frame();

        void up(int slot);

        /**
         * 这根手指不是正常抬起，而是作废了（中途变成手掌、内核丢了数据）。默认当抬起：TouchRouter 那一路照旧；
         * 注入回系统的那一路要发 CANCEL，不然系统会当成一次真的抬起（= 点击）。
         */
        default void cancel(int slot) {
            up(slot);
        }
    }

    static final int EV_SYN = 0;
    static final int EV_ABS = 3;
    static final int SYN_REPORT = 0;
    static final int SYN_DROPPED = 3;
    static final int ABS_MT_SLOT = 0x2f;
    static final int ABS_MT_POSITION_X = 0x35;
    static final int ABS_MT_POSITION_Y = 0x36;
    static final int ABS_MT_TOOL_TYPE = 0x37;
    static final int ABS_MT_TRACKING_ID = 0x39;
    /** 多点触控的 ABS_MT_* 编号范围（ABS_MT_SLOT 之后，到 ABS_MT_TOOL_Y 为止）。 */
    static final int ABS_MT_FIRST = 0x30;
    static final int ABS_MT_LAST = 0x3d;
    static final int MT_TOOL_PALM = 2;
    static final int SLOTS = 16;

    private final Listener listener;
    private int slot;
    private boolean dropping;

    /** 内核眼里每个 slot 当前的 tracking id，-1 = 空。 */
    private final int[] tracking = new int[SLOTS];
    private final int[] x = new int[SLOTS];
    private final int[] y = new int[SLOTS];
    private final int[] tool = new int[SLOTS];
    /** 已经向监听者报告过按下、还没报告抬起。 */
    private final boolean[] reported = new boolean[SLOTS];
    private final boolean[] moved = new boolean[SLOTS];
    /** 同一帧里旧手指抬起、新手指落在了同一个 slot。 */
    private final boolean[] replaced = new boolean[SLOTS];
    /** 这根手指的开头没看到（开始读之前就按着，或丢过数据），抬起之前都不理它。 */
    private final boolean[] stale = new boolean[SLOTS];
    /** 从开始读起还没见过这个 slot 上有手指开头（丢数据不算，只看开始读之前）。 */
    private final boolean[] preGrab = new boolean[SLOTS];
    /** 这一帧里 preGrab 的 slot 上来了数据（坐标、抬起等）：帧结束时它还没开头就是开始读之前就按着的手指。 */
    private final boolean[] preGrabTouched = new boolean[SLOTS];
    /** 这一帧里 preGrab 的 slot 上来的第一个多点触控事件就是抬起。 */
    private final boolean[] preGrabLifted = new boolean[SLOTS];
    private boolean sawPreGrab;
    /** 认出的独占前手指里，有一根来的第一个事件就是抬起（它的抬起已经被独占吞了）。 */
    private boolean sawPreGrabLift;

    MtDecoder(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < SLOTS; i++) {
            tracking[i] = -1;
            stale[i] = true;
            preGrab[i] = true;
        }
    }

    /**
     * 见过开始读之前就按着的手指（它在这之后动了、或抬起了），直到 {@link #clearSawPreGrab}。
     * 按帧判断：同一帧里先来坐标、后来 tracking id 的新手指不算（帧结束时它已经有开头了）。
     * 只能事后发现：读到这根手指的事件时，这些事件已经被独占描述符读走了。要是它独占后的第一个事件就是抬起（轻点；
     * 静止的手指坐标不变、内核不发事件），这次抬起已经被吞了，放开独占也补不回来，系统要等同一个 slot 下一次按下才会补上抬起，
     * 在那之前另一边的应用一直收着一个按住的触摸（{@link #sawPreGrabLift}）。这个缺口靠独占前不独占地开着触摸屏
     * （Session 的 plain 描述符 + {@link LiftWatch}，独占后先读空它，独占前按下没抬起的就马上放开、一个独占事件都不读）来堵；
     * 一个事件都没发的手指仍然看不出来。
     */
    boolean sawPreGrab() {
        return sawPreGrab;
    }

    /** 认出的独占前手指里有一根第一个事件就是抬起：系统那边多半留着这根按着的手指（只用来记日志）。 */
    boolean sawPreGrabLift() {
        return sawPreGrabLift;
    }

    /** 不需要因为它停下时（全屏）清掉，免得切到分屏后拿一根早就抬起的手指当理由放开。 */
    void clearSawPreGrab() {
        sawPreGrab = false;
        sawPreGrabLift = false;
    }

    /**
     * 开始读之前内核切到的 slot（独占前不独占地读过的那一路知道）：之后的事件不一定再带 ABS_MT_SLOT。
     * 超出范围的传 -1（之后的事件忽略到下一个 ABS_MT_SLOT）。
     */
    void setSlot(int s) {
        slot = s >= 0 && s < SLOTS ? s : -1;
    }

    void feed(int type, int code, int value) {
        if (type == EV_SYN) {
            if (code == SYN_DROPPED) {
                dropping = true;
            } else if (code == SYN_REPORT) {
                checkPreGrab();
                if (dropping) {
                    dropping = false;
                    abandonAll();
                } else {
                    commit();
                }
            }
            return;
        }
        if (type != EV_ABS || dropping) return;
        if (slot >= 0 && preGrab[slot] && code >= ABS_MT_FIRST && code <= ABS_MT_LAST) {
            if (code == ABS_MT_TRACKING_ID && value >= 0) {
                preGrab[slot] = false;   // 看到了开头：开始读之后才按下的
                preGrabTouched[slot] = false;
                preGrabLifted[slot] = false;
            } else {
                if (code == ABS_MT_TRACKING_ID && !preGrabTouched[slot]) preGrabLifted[slot] = true;
                preGrabTouched[slot] = true;
            }
        }
        switch (code) {
            case ABS_MT_SLOT:
                slot = value >= 0 && value < SLOTS ? value : -1;
                break;
            case ABS_MT_TRACKING_ID:
                if (slot < 0) break;
                if (value >= 0) {
                    if (reported[slot] && tracking[slot] >= 0 && value != tracking[slot]) replaced[slot] = true;
                    if (tracking[slot] < 0 || value != tracking[slot]) {
                        stale[slot] = false;   // 看到了这根手指的开头
                        tool[slot] = 0;
                    }
                }
                tracking[slot] = value;
                break;
            case ABS_MT_POSITION_X:
                if (slot >= 0) {
                    x[slot] = value;
                    moved[slot] = true;
                }
                break;
            case ABS_MT_POSITION_Y:
                if (slot >= 0) {
                    y[slot] = value;
                    moved[slot] = true;
                }
                break;
            case ABS_MT_TOOL_TYPE:
                if (slot >= 0) tool[slot] = value;
                break;
            default:
                break;
        }
    }

    private void checkPreGrab() {
        for (int s = 0; s < SLOTS; s++) {
            if (!preGrabTouched[s]) continue;
            preGrabTouched[s] = false;
            boolean lifted = preGrabLifted[s];
            preGrabLifted[s] = false;
            if (preGrab[s]) {
                sawPreGrab = true;
                if (lifted) sawPreGrabLift = true;
                preGrab[s] = false;
            }
        }
    }

    /** 外部要求停止（比如释放触摸屏）：已报告的手指全部按抬起处理。 */
    void releaseAll() {
        release(false);
    }

    private void release(boolean cancel) {
        for (int s = 0; s < SLOTS; s++) {
            if (reported[s]) {
                reported[s] = false;
                if (cancel) listener.cancel(s);
                else listener.up(s);
            }
        }
    }

    private void commit() {
        for (int s = 0; s < SLOTS; s++) {
            if (replaced[s] && reported[s]) {
                reported[s] = false;
                listener.up(s);
            }
        }
        for (int s = 0; s < SLOTS; s++) {
            if (!reported[s] && !stale[s] && tracking[s] >= 0 && tool[s] != MT_TOOL_PALM) {
                reported[s] = true;
                moved[s] = false;
                listener.down(s, x[s], y[s]);
            }
        }
        for (int s = 0; s < SLOTS; s++) {
            if (reported[s] && moved[s] && tracking[s] >= 0) listener.move(s, x[s], y[s]);
        }
        listener.frame();
        for (int s = 0; s < SLOTS; s++) {
            if (reported[s] && tracking[s] < 0) {
                reported[s] = false;
                listener.up(s);
            } else if (reported[s] && tool[s] == MT_TOOL_PALM) {
                reported[s] = false;
                listener.cancel(s);
            }
            moved[s] = false;
            replaced[s] = false;
            if (tracking[s] < 0) stale[s] = false;
        }
    }

    private void abandonAll() {
        release(true);
        for (int s = 0; s < SLOTS; s++) {
            stale[s] = true;
            moved[s] = false;
            replaced[s] = false;
        }
    }
}
