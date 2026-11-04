package dev.douhua;

/**
 * 贴字面板"打出来"的发送节拍（2026-11-02，方案 design-typeout §5–§7）：照 {@link TypeOut.Plan} 一个键一个键按下、松开。纯 Java（离线测，TypeOutTest，假时钟）。
 *
 * 速度：固定、可调（设置 → 键盘最下面"打出来"那一节的滑块，{@link #RATE_MIN}–{@link #RATE_MAX} 键 / 秒，默认 {@link #RATE_DEFAULT}）。
 * 一个键占 1 / 速度 秒：按下到松开 {@link #holdMs}（一半，但至少 {@link MinHold#MIN_MS}——人能点出来的最短，2026-11-04），剩下的是松开到下一个按下
 * {@link #gapMs}；小数的毫秒攒着，平均下来正好。最快 {@link #RATE_MAX} 键 / 秒（按住 40 毫秒 + 隔 10 毫秒）。
 * 下一下从这一下真正发生的时刻起算：主线程卡一下只会变慢、不会补发一串（BIOS、KVM、远程控制台、输入法遇到太快的输入会丢字、乱序，要实测）。
 * 不做随机节奏、不模仿人手（方案第七节：那是渗透工具的做法）。
 *
 * 停（{@link #stop}，原因 END_*）：随时能停；正按着的那个键配一个松开（KEY_UP），停了以后没有按着的键。不自动续发：要再点一次"打出来"，从头打。
 * 停的原因由宿主（MainActivity）判断：按了停止、连接断了、退后台、收起面板、焦点交不出去、焦点回到豆花、窗口变了。
 *
 * 等焦点（桌面模式，{@link #start} 的 gated）：键经服务的虚拟键盘发给"拿着键盘焦点的窗口"，点"打出来"那一下焦点在豆花自己（贴字面板），
 * 宿主先请服务把焦点交给分屏的另一半，豆花丢了焦点（{@link #gateOpen}）再过 {@link #GATE_SETTLE_MS} 开始；{@link #GATE_TIMEOUT_MS} 还没丢就停（END_FOCUS），一个键都不发。
 *
 * 定时器由宿主给（MainActivity 用主线程 Handler，单线程，撤掉就不会再跑）：再排取代上一次；早到的回调照截止时刻再排、什么都不多做，空闲时的回调什么都不做。
 */
final class TypeOutSender {
    interface Host {
        /** 按下 / 松开一个键（key 同 Proto.REGION_KEY 的 id，action = Proto.KEY_DOWN / KEY_UP）。 */
        void key(int key, int action);

        /** 过 delayMs 毫秒调一次 {@link #onTimer}（再排取代上一次）。 */
        void schedule(long delayMs);

        void cancelSchedule();

        /**
         * 状态、进度变了（界面重画）。约定（宿主靠它出停止按钮、改焦点、画进度、结束时恢复输入框 / 写结果 / 震一下；TypeOutTest.changedContract 锁住）：
         * 开始时一次（等焦点时是 WAITING，不等是 RUNNING、在第一个键之前）、焦点交出去时一次、每个键松开后一次；结束（打完 / 停）的那一次调的时候已经空闲、{@link #end} 是最后的原因。
         */
        void changed();
    }

    /** 速度（键 / 秒）的范围、步长、默认值：设置页的滑块和 Prefs 都照它。 */
    static final int RATE_MIN = 5, RATE_MAX = 20, RATE_STEP = 1, RATE_DEFAULT = 15;
    /** 桌面模式等焦点交出去最多等多久；交出去以后再等多久才按第一个键（让系统把焦点窗口换好）。 */
    static final long GATE_TIMEOUT_MS = 1500, GATE_SETTLE_MS = 150;

    static final int IDLE = 0, WAITING = 1, RUNNING = 2;
    /** 上一次怎么结束的：还没打过 / 打完了 / 按了停止 / 连接断了 / 退后台 / 收起面板 / 焦点交不出去 / 焦点回到豆花 / 窗口变了。 */
    static final int END_NONE = 0, END_DONE = 1, END_USER = 2, END_LOST = 3, END_PAUSED = 4, END_CLOSED = 5, END_FOCUS = 6,
            END_FOCUS_BACK = 7, END_WINDOW = 8;

    private final Host host;
    private int state = IDLE;
    private TypeOut.Plan plan;
    private double hold, gap;
    private double carry;
    /** 已经按下并松开的键数；down 时 keys[released] 正按着。 */
    private int released;
    private boolean down;
    private long due;
    private int end = END_NONE;
    private int done;
    private boolean midChar;

    TypeOutSender(Host host) {
        this.host = host;
    }

    /** 存坏了的速度截到范围里。 */
    static int clampRate(int rate) {
        return Math.max(RATE_MIN, Math.min(RATE_MAX, rate));
    }

    /** 按下到松开多少毫秒：半个键（500 / 速度），至少 {@link MinHold#MIN_MS}。 */
    static double holdMs(int rate) {
        return Math.max(MinHold.MIN_MS, 500.0 / clampRate(rate));
    }

    /** 松开到下一个按下多少毫秒：一个键的时长减去按住的。 */
    static double gapMs(int rate) {
        return 1000.0 / clampRate(rate) - holdMs(rate);
    }

    /**
     * 开始打。gated = 先等焦点（桌面模式，见类注释）；不等的话现在就按下第一个键。正在打的时候、plan 没有键时不开始（没有键就算打完了，
     * 结果 END_DONE）。返回开没开始。
     */
    boolean start(TypeOut.Plan p, int rate, boolean gated, long now) {
        if (state != IDLE || p == null) return false;
        plan = p;
        hold = holdMs(rate);
        gap = gapMs(rate);
        carry = 0;
        released = 0;
        down = false;
        done = 0;
        midChar = false;
        end = END_NONE;
        if (p.keys.length == 0) {
            finish(END_DONE);
            return false;
        }
        if (gated) {
            state = WAITING;
            due = now + GATE_TIMEOUT_MS;
            host.schedule(GATE_TIMEOUT_MS);
            host.changed();
        } else {
            state = RUNNING;
            host.changed();
            step(now);
        }
        return true;
    }

    /** 焦点交出去了（桌面模式）：过 {@link #GATE_SETTLE_MS} 按第一个键。不在等焦点时什么都不做。 */
    void gateOpen(long now) {
        if (state != WAITING) return;
        state = RUNNING;
        due = now + GATE_SETTLE_MS;
        host.schedule(GATE_SETTLE_MS);
        host.changed();
    }

    /** 宿主的定时器到点。 */
    void onTimer(long now) {
        if (state == IDLE) return;
        if (now < due) {   // 早到的（过期的回调）：照截止时刻再排
            host.schedule(due - now);
            return;
        }
        if (state == WAITING) {
            finish(END_FOCUS);
            return;
        }
        step(now);
    }

    /** 按下下一个键，或者松开正按着的那个。 */
    private void step(long now) {
        if (down) {
            down = false;
            host.key(plan.keys[released], Proto.KEY_UP);
            released++;
            done = plan.doneAfter(released);
            if (released >= plan.keys.length) {
                finish(END_DONE);
                return;
            }
            host.changed();
        } else {
            down = true;
            host.key(plan.keys[released], Proto.KEY_DOWN);
        }
        long d = nextDelay();
        due = now + d;
        host.schedule(d);
    }

    /** 下一下隔多少毫秒：刚按下的等 hold、刚松开的等 gap，小数攒着（12 键 / 秒 = 41、42、41、42……）。 */
    private long nextDelay() {
        carry += down ? hold : gap;
        long d = (long) carry;
        carry -= d;
        return Math.max(1, d);
    }

    /** 停（reason = END_* 里不是 END_DONE / END_NONE 的）：正按着的键配一个松开，不再续发。空闲时什么都不做。 */
    void stop(int reason) {
        if (state == IDLE) return;
        if (down) {
            down = false;
            host.key(plan.keys[released], Proto.KEY_UP);
            released++;
            done = plan.doneAfter(released);
        }
        finish(reason);
    }

    private void finish(int reason) {
        state = IDLE;
        end = reason;
        midChar = plan != null && plan.midChar(released);
        host.cancelSchedule();
        host.changed();
    }

    int state() {
        return state;
    }

    boolean busy() {
        return state != IDLE;
    }

    /** 打完了几个字（换行、空格也算一个；死键拼的字两个键都松开才算）。 */
    int done() {
        return done;
    }

    /** 这一次一共要打几个字（打不出的不算）。还没开始过是 0。 */
    int total() {
        return plan == null ? 0 : plan.total();
    }

    /** 上一次怎么结束的（END_*）；正在打 / 还没打过是 END_NONE。 */
    int end() {
        return state == IDLE ? end : END_NONE;
    }

    /** 上一次停在一个字的中间（死键按了、后面那个键还没按：主机那边可能还等着拼字）。 */
    boolean midChar() {
        return state == IDLE && midChar;
    }

    /** 已经按下并松开的键数（测试用）。 */
    int released() {
        return released;
    }

    /** 正按着一个键（测试用）。 */
    boolean keyDown() {
        return down;
    }
}
