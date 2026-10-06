package dev.douhua.server;

/**
 * Linux 多点触控协议 B（带 slot）的解码器。纯 Java、不依赖 Android，能离线测试（tools/test.sh）。
 *
 * 喂入原始事件（type, code, value）；每到 SYN_REPORT，把这一帧的变化按"按下 → 移动 → 帧结束 → 抬起"的顺序交给监听者，
 * 和 GestureEngine 要的顺序一致。
 * - 手掌（MT_TOOL_PALM）不算手指；手指中途被判成手掌，按抬起处理。
 * - 开始读之前就已经按着的手指（没见过它的 tracking id）一律忽略，直到它抬起、重新按下。
 * - 内核丢了数据（SYN_DROPPED）：把所有手指按抬起处理，按着的手指也作废，直到重新按下。
 */
final class MtDecoder {
    interface Listener {
        void down(int slot, int rawX, int rawY);

        void move(int slot, int rawX, int rawY);

        void frame();

        void up(int slot);
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

    MtDecoder(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < SLOTS; i++) {
            tracking[i] = -1;
            stale[i] = true;
        }
    }

    void feed(int type, int code, int value) {
        if (type == EV_SYN) {
            if (code == SYN_DROPPED) {
                dropping = true;
            } else if (code == SYN_REPORT) {
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

    /** 外部要求停止（比如释放触摸屏）：已报告的手指全部按抬起处理。 */
    void releaseAll() {
        for (int s = 0; s < SLOTS; s++) {
            if (reported[s]) {
                reported[s] = false;
                listener.up(s);
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
            if (reported[s] && (tracking[s] < 0 || tool[s] == MT_TOOL_PALM)) {
                reported[s] = false;
                listener.up(s);
            }
            moved[s] = false;
            replaced[s] = false;
            if (tracking[s] < 0) stale[s] = false;
        }
    }

    private void abandonAll() {
        releaseAll();
        for (int s = 0; s < SLOTS; s++) {
            stale[s] = true;
            moved[s] = false;
            replaced[s] = false;
        }
    }
}
