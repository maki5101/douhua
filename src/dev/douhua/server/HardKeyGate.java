package dev.douhua.server;

import dev.douhua.HardKeys;
import dev.douhua.Proto;

/**
 * 服务独占音量键设备后，决定每个按下 / 松开怎么处理。纯 Java，离线测（{@code tools/test.sh}）。调用方（KeyReader）单线程调用。
 *
 * <ul>
 * <li><b>配了动作的音量键</b>：交给一个 {@link HardKeys}（和蓝牙模式 App 里用的同一个类、同一个规矩）——点一下 / 开始按住 /
 *     结束按住发给 App（{@link Output#toApp}）。设了动作的键整个归豆花：没设的那一半什么都不做，不调音量。</li>
 * <li><b>没配动作、却和配了的键在同一个（被独占的）设备上的音量键</b>：真按下时注入按下、真松开时注入松开，downTime 用原始的，
 *     系统自己连发，照常调音量。</li>
 * <li><b>电源键和截住的音量键同时按着</b>（电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）；电源键所在的设备从不独占，只旁观，见 {@link #observe}）
 *     → 按原始时间补发吃掉的音量键按下，再交还系统；音量那边在按住中就补一个 HOLD_END。</li>
 * </ul>
 *
 * 事件分三路进来：独占着的描述符上读到的（系统没看到）走 {@link #key}；没独占时读到的（系统也收到了）走 {@link #system}；
 * 只旁观的设备走 {@link #observe}（只看电源键）。
 *
 * 交还系统（{@link Output#handBack}）之后 KeyReader 改成不独占地读，等音量键和电源键都空闲满 {@link #COOLDOWN}
 * 才重新独占（{@link #readyToGrab}）。刚建出来时也算"在系统手里"：KeyReader 第一次独占也要等所有键都松开。
 * 注入过的每一个"按下"在所有退出路径上都配一个"松开"（否则系统一直连发、音量被推到头）——见 {@link #releaseAll}。
 *
 * 时间都用调用方的单一时钟（KeyReader 传 SystemClock.uptimeMillis，也当 Android 的 downTime / eventTime）。
 */
final class HardKeyGate {
    /** 交还系统后，键都空闲满这么久才重新独占。 */
    static final long COOLDOWN = 2000;

    interface Output {
        /** 注入一次音量键按下 / 松开给系统（照常调音量）。downTime = 原始按下时刻，eventTime = 这次事件时刻。 */
        void inject(int linuxCode, boolean down, long downTime, long eventTime);

        /** 发给 App（服务已判断好）：key = Proto.HARD_VOL_UP / VOL_DOWN，event = Proto.HARD_TAP / HOLD_START / HOLD_END。 */
        void toApp(int key, int event);

        /** 把独占的音量键设备交还系统（KeyReader 改成不独占地读）。why 只写日志。 */
        void handBack(String why);
    }

    // 内部计时 id（HardKeys 的两个音量槽）
    private static final int SCHED_VOL_UP = 0, SCHED_VOL_DOWN = 1;
    private final long[] deadline = {-1, -1};

    // 下标：0 音量下(114)、1 音量上(115)
    private final boolean[] isDown = new boolean[2];
    private final long[] downTime = new long[2];
    /** 注入过按下、还欠一个松开（交还后真松开时配上、releaseAll 补上）。 */
    private final boolean[] owe = new boolean[2];
    /** 按着、但系统没看到这次按下（键已在系统手里时从独占的描述符上读到的）：它的松开系统会当没头的丢掉，不用补。 */
    private final boolean[] hidden = new boolean[2];
    /** 旁观的设备上电源键按着没有：系统自己收，这里只用来认组合键、算空闲。 */
    private boolean powerDown;

    private final Output out;
    private final HardKeys vol = new HardKeys(new VolOut());
    private boolean volUpOwned, volDownOwned;

    /** 键在系统手里（还没独占，或交还了）：真按下 / 松开系统自己收，这里只记状态、给补发的按下配松开。 */
    private boolean systemOwns = true;
    /** 最近一次有键动的时刻；刚建出来时算很久以前（第一次独占不用等冷却）。 */
    private long lastActive = Long.MIN_VALUE / 4;
    /** HardKeys 的回调里要用的"当前时刻"（调 vol.* 前设好）。 */
    private long now;

    HardKeyGate(Output out) {
        this.out = out;
    }

    /** 哪个音量键配了动作（配了才归豆花判断；没配但在被独占设备上的键由本类透传）。 */
    void config(boolean volUpTap, boolean volUpHold, boolean volDownTap, boolean volDownHold) {
        vol.setConfig(HardKeys.VOL_UP, volUpTap, volUpHold);
        vol.setConfig(HardKeys.VOL_DOWN, volDownTap, volDownHold);
        volUpOwned = volUpTap || volUpHold;
        volDownOwned = volDownTap || volDownHold;
    }

    // ═══════════ 输入 ═══════════

    /**
     * 独占着的描述符上读到的音量键事件（系统没看到）。value：0 松开、1 按下、2 内核连发（忽略）。
     * 键已经在系统手里时也可能读到（交还系统那一刻、独占撞上竞态马上放开时还没读完的），见 {@link #unseen}。
     */
    void key(int linuxCode, int value, long t) {
        if (value == 2) return;   // 内核连发：不算
        int k = idx(linuxCode);
        if (k < 0) return;
        now = t;
        lastActive = t;
        boolean down = value == 1;
        if (systemOwns) {
            unseen(k, linuxCode, down, t);
            return;
        }
        if (down) {
            if (isDown[k]) return;
            isDown[k] = true;
            downTime[k] = t;
            if (comboNow()) {
                handBackCombo(t);
                return;
            }
            if (owned(linuxCode)) vol.key(volIndex(linuxCode), true, t);
            else passDown(linuxCode, k, t);
        } else {
            if (!isDown[k]) {
                strayUp(linuxCode, t);
                return;
            }
            isDown[k] = false;
            if (owned(linuxCode)) vol.key(volIndex(linuxCode), false, t);
            else passUp(linuxCode, k, t);
        }
    }

    /**
     * 没独占时读到的、系统也收到了的事件（要截的设备还在系统手里：还没独占、或交还了）。不注入，只记状态、
     * 给补发出去的按下配上松开。
     */
    void system(int linuxCode, int value, long t) {
        if (value == 2) return;
        int k = idx(linuxCode);
        if (k < 0) return;
        now = t;
        lastActive = t;
        hidden[k] = false;
        if (value == 1) {
            isDown[k] = true;
            downTime[k] = t;
            return;
        }
        isDown[k] = false;
        if (owe[k]) {   // 给补发出去的按下配上松开（系统那边真松开没头，会被丢掉）
            owe[k] = false;
            inject(linuxCode, false, downTime[k], t);
        }
    }

    /**
     * 只旁观、没独占的设备（带电源键的）上的事件：系统自己收，这里不注入，只看电源键——认出"电源键和截住的音量键一起按"
     * 的组合键（截屏、电源 + 音量上的快捷方式等）、算空闲。旁观设备上的音量键也是系统自己收，和截住的键无关，不看。
     */
    void observe(int linuxCode, int value, long t) {
        if (value == 2 || linuxCode != KeyDevices.KEY_POWER) return;
        now = t;
        lastActive = t;
        powerDown = value == 1;
        if (powerDown && !systemOwns && comboNow()) handBackCombo(t);
    }

    /** 到点的计时（KeyReader 每轮 poll 后调 {@link #pump}）。 */
    void onTimer(int id, long t) {
        now = t;
        if (id == SCHED_VOL_UP) vol.onTimer(HardKeys.VOL_UP, t);
        else if (id == SCHED_VOL_DOWN) vol.onTimer(HardKeys.VOL_DOWN, t);
    }

    /** 每轮 poll 后让到点的计时触发（调用方的单线程里）。 */
    void pump(long t) {
        for (int id = 0; id < deadline.length; id++) {
            if (deadline[id] >= 0 && t >= deadline[id]) {
                deadline[id] = -1;
                onTimer(id, t);
            }
        }
    }

    /** 下一个计时的绝对时刻（没有返回 Long.MAX_VALUE）：KeyReader 据此决定 poll 多久。 */
    long nextDeadline() {
        long m = Long.MAX_VALUE;
        for (long d : deadline) if (d >= 0) m = Math.min(m, d);
        return m;
    }

    /** 键现在在系统手里（还没独占，或交还了）。 */
    boolean systemOwns() {
        return systemOwns;
    }

    /** 在系统手里、所有键（含旁观的电源键）都松开、补的松开都补完、空闲满 {@link #COOLDOWN}：可以独占了。 */
    boolean readyToGrab(long t) {
        if (!systemOwns || powerDown) return false;
        for (int k = 0; k < 2; k++) if (isDown[k] || owe[k]) return false;
        return t - lastActive >= COOLDOWN;
    }

    /** KeyReader 独占成功了：从这里起按下 / 松开归本类判断。 */
    void grabbed() {
        systemOwns = false;
        for (int i = 0; i < 2; i++) {
            isDown[i] = false;
            owe[i] = false;
            hidden[i] = false;
        }
        for (int i = 0; i < deadline.length; i++) deadline[i] = -1;
        vol.forget();
    }

    /**
     * 放手（停止拦截、连接断开、出错）：注入过还没松开的键补松开，音量按住中的发 HOLD_END。
     * 注入过的按下必配松开——不然系统一直连发。之后又算"在系统手里"。
     */
    void releaseAll(long t) {
        now = t;
        vol.reset();   // 按住中 → HOLD_END；PENDING → 取消计时
        for (int k = 0; k < 2; k++) {
            if (owe[k]) {
                inject(linuxOf(k), false, downTime[k], t);
                owe[k] = false;
            }
            isDown[k] = false;
        }
        for (int i = 0; i < deadline.length; i++) deadline[i] = -1;
        systemOwns = true;
    }

    // ═══════════ 透传 ═══════════

    /** 透传：没配的键原样交给系统（照常调音量）。 */
    private void passDown(int linux, int k, long t) {
        owe[k] = true;
        downTime[k] = t;
        inject(linux, true, t, t);
    }

    private void passUp(int linux, int k, long t) {
        if (!owe[k]) return;
        owe[k] = false;
        inject(linux, false, downTime[k], t);
    }

    /**
     * 独占着却收到一个没见过按下的松开：这个键在独占前就按着了（KeyReader 打开设备之前），系统多半看到了按下，
     * 松开却被独占吞了——不补的话系统一直以为它按着，一直连发、音量被推到头。补一对按下 + 松开
     * （注入的松开止不住物理设备那一路的连发，要先注入一个按下接过连发再松开；最坏多调一格音量）。
     */
    private void strayUp(int linux, long t) {
        inject(linux, true, t, t);
        inject(linux, false, t, t);
    }

    // ═══════════ 组合键 ═══════════

    /** 电源键（旁观的）和某个截住的音量键现在同时按着。 */
    private boolean comboNow() {
        return powerDown && (isDown[0] || isDown[1]);
    }

    private void handBackCombo(long t) {
        // 先把音量键的 HardKeys 状态收掉：按住中 → HOLD_END；PENDING → 取消计时
        for (int k = 0; k < 2; k++) {
            if (isDown[k] && owned(linuxOf(k))) vol.cancel(volIndex(linuxOf(k)));
        }
        // 吞掉过、系统里还没按下的键（isDown 且没 owe）：按原始时间顺序补发按下（eventTime 也用原始的，
        // 系统认组合键看两次按下隔多久），再交还系统
        int first = downTime[0] <= downTime[1] ? 0 : 1;
        for (int k : new int[]{first, 1 - first}) {
            if (!isDown[k] || owe[k]) continue;
            inject(linuxOf(k), true, downTime[k], downTime[k]);
            owe[k] = true;
        }
        handBack(t, "电源键和音量键一起按");
    }

    // ═══════════ 在系统手里 ═══════════

    /**
     * 键已在系统手里，却从独占的描述符上读到（系统没看到）：按下 → 系统不知道这一下（记 hidden，真松开不用补）；
     * 松开 → 补发过按下的配上松开；系统看到了按下（独占前、竞态里）的照 {@link #strayUp} 补。
     */
    private void unseen(int k, int linux, boolean down, long t) {
        if (down) {
            isDown[k] = true;
            downTime[k] = t;
            hidden[k] = true;
            return;
        }
        if (owe[k]) {
            owe[k] = false;
            inject(linux, false, downTime[k], t);
        } else if (isDown[k] && !hidden[k]) {
            strayUp(linux, t);
        }
        isDown[k] = false;
        hidden[k] = false;
    }

    private void handBack(long t, String why) {
        systemOwns = true;
        lastActive = t;
        out.handBack(why);
    }

    // ═══════════ 工具 ═══════════

    private boolean owned(int linux) {
        return linux == KeyDevices.KEY_VOLUMEUP ? volUpOwned : linux == KeyDevices.KEY_VOLUMEDOWN && volDownOwned;
    }

    /** linux 键码 → HardKeys 的音量槽（VOL_UP / VOL_DOWN）。 */
    private static int volIndex(int linux) {
        return linux == KeyDevices.KEY_VOLUMEUP ? HardKeys.VOL_UP : HardKeys.VOL_DOWN;
    }

    /** 只认音量键：独占的设备上不会有电源键（KeyDevices 不截带电源键的设备），万一读到也不碰。 */
    private static int idx(int linux) {
        switch (linux) {
            case KeyDevices.KEY_VOLUMEDOWN: return 0;
            case KeyDevices.KEY_VOLUMEUP: return 1;
            default: return -1;
        }
    }

    private static int linuxOf(int k) {
        return k == 0 ? KeyDevices.KEY_VOLUMEDOWN : KeyDevices.KEY_VOLUMEUP;
    }

    private void inject(int linux, boolean down, long downTime, long eventTime) {
        out.inject(linux, down, downTime, eventTime);
    }

    /** 音量键的 HardKeys 回调：tap/hold → 发给 App；计时 → 内部 deadline。 */
    private final class VolOut implements HardKeys.Output {
        @Override
        public void tap(int key) {
            out.toApp(key, Proto.HARD_TAP);
        }

        @Override
        public void holdStart(int key) {
            out.toApp(key, Proto.HARD_HOLD_START);
        }

        /** 被取消的（releaseAll 收尾、电源键组合交还系统时的 vol.cancel）报 HARD_HOLD_CANCEL（协议 25）：App 照样收尾、只是不出松开声。 */
        @Override
        public void holdEnd(int key, boolean canceled) {
            out.toApp(key, canceled ? Proto.HARD_HOLD_CANCEL : Proto.HARD_HOLD_END);
        }

        @Override
        public void schedule(int key, long delayMs) {
            deadline[key == HardKeys.VOL_UP ? SCHED_VOL_UP : SCHED_VOL_DOWN] = now + delayMs;
        }

        @Override
        public void cancelSchedule(int key) {
            deadline[key == HardKeys.VOL_UP ? SCHED_VOL_UP : SCHED_VOL_DOWN] = -1;
        }
    }
}
