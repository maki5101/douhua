package dev.douhua;

/**
 * 手机实体按键（音量 +、音量 −）的"点一下 / 按住"，具体做什么由设置里选的动作决定。纯 Java（离线测试）。
 * <ul>
 * <li>只设了"点一下"：按下当场就算（不用等），一直到松开都不再算。</li>
 * <li>设了"按住"：按住超过 {@link #HOLD_MS} 算按住（holdStart），松开时 holdEnd；没到就松开算点一下（设了的话）。</li>
 * </ul>
 * 设了动作的键整个归豆花：没设的那一半（只设了点一下时的按住、只设了按住时的短按）什么都不做，也不调音量。
 * 蓝牙模式下 App 自己用它，DeX 模式下服务独占实体键设备后用同一个类判断（server.HardKeyGate），两边一个规矩。
 * 电源键编号 {@link #POWER} 只给 Prefs 和设置页当键用，不进这个状态机（电源键的判断在 server.HardKeyGate 里）。
 * 时间都用 KeyEvent.getEventTime() 那套（uptimeMillis）。
 */
public final class HardKeys {
    public static final int VOL_UP = 0, VOL_DOWN = 1;
    /** 电源键：只当键编号用（Prefs、设置页），不进这个状态机。 */
    public static final int POWER = 2;
    public static final int HOLD_MS = 300;

    public interface Output {
        void tap(int key);

        void holdStart(int key);

        void holdEnd(int key);

        /** 按住计时：再调一次就覆盖这个键上一次的。 */
        void schedule(int key, long delayMs);

        void cancelSchedule(int key);
    }

    private static final int IDLE = 0, PENDING = 1, HOLDING = 2, SWALLOW = 3;

    private final Output out;
    private final boolean[] hasTap = new boolean[2];
    private final boolean[] hasHold = new boolean[2];
    private final int[] state = new int[2];
    private final boolean[] pressed = new boolean[2];

    public HardKeys(Output out) {
        this.out = out;
    }

    public void setConfig(int key, boolean tap, boolean hold) {
        hasTap[key] = tap;
        hasHold[key] = hold;
    }

    /** 这个键设了动作没有（没设就别吃掉，照常调音量）。 */
    public boolean mapped(int key) {
        return hasTap[key] || hasHold[key];
    }

    /** 这个键的按下还没收到松开（空中鼠标暂停期间只吞这种键，新按下的交还系统调音量）。 */
    public boolean isPressed(int key) {
        return pressed[key];
    }

    public void key(int key, boolean down, long t) {
        if (key != VOL_UP && key != VOL_DOWN) return;
        if (down) {
            if (pressed[key]) return;   // 按着时又来一个按下：不算
            pressed[key] = true;
            if (hasHold[key]) {
                state[key] = PENDING;
                out.schedule(key, HOLD_MS);
            } else {
                state[key] = SWALLOW;
                if (hasTap[key]) out.tap(key);
            }
            return;
        }
        if (!pressed[key]) return;   // 没按下过的松开：不算
        pressed[key] = false;
        int s = state[key];
        state[key] = IDLE;
        if (s == PENDING) {
            out.cancelSchedule(key);
            if (hasTap[key]) out.tap(key);
        } else if (s == HOLDING) {
            out.holdEnd(key);
        }
    }

    public void onTimer(int key, long t) {
        if (state[key] != PENDING) return;
        state[key] = HOLDING;
        out.holdStart(key);
    }

    /** 被系统取消的按键（FLAG_CANCELED 的松开）：当放手，不算点一下。 */
    public void cancel(int key) {
        if (state[key] == HOLDING) out.holdEnd(key);
        if (state[key] == PENDING) out.cancelSchedule(key);
        pressed[key] = false;
        state[key] = IDLE;
    }

    /** 放手：按住中的先 holdEnd，计时作废；还按着的键等松开以后再重新算。 */
    public void reset() {
        for (int k = 0; k < 2; k++) {
            if (state[k] == HOLDING) out.holdEnd(k);
            if (state[k] == PENDING) out.cancelSchedule(k);
            state[k] = pressed[k] ? SWALLOW : IDLE;
        }
    }

    /** 外面停过一阵（收不到按键）：按下的记录全部作废。 */
    public void forget() {
        for (int k = 0; k < 2; k++) {
            pressed[k] = false;
            state[k] = IDLE;
        }
    }
}
