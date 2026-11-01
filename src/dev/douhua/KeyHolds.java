package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按着时要计时的键（2026-11-01，用户："Shift 键单点就是 Shift 键及时触发，长按变成锁定，然后超过长按的时间就取消锁定……
 * 我只要按住 Shift，我再按字母键，它就是大写。然后这个时候，我是不需要它锁定的"）：修饰键（0xE0–0xE7、不带自带修饰键）、
 * Caps（0x39、不带自带修饰键）、合并键（{@link Proto#dualKey}，小红点状态的逗号句号），外加锁住的修饰键（latched）和"现在哪些键有手指按着"。
 * 纯 Java、不加锁（同 TouchRouter：TouchRouter 持有一份，调用方保证同一时刻只有一个线程在调）。HID 报文、自带修饰键的键、缩放的 Ctrl
 * 还是 TouchRouter 管，这里经 {@link Host} 回调它（Host 的 pressKey / releaseKey 是原语，不回调这里，没有递归）。
 *
 * 修饰键（左右八个，含 AltGr、Mac 的 ⌃ ⌥ ⌘）：按下马上发（同真键盘）。
 * - 按住期间用过（按了任何别的键；鼠标键按下 / 松开、带着按住的鼠标键移动、滚轮、缩放、三指四指手势——TouchRouter 调 {@link #used}），
 *   或者按下那一刻已经有别的键的手指 / 鼠标键按着 = 组合键：松手就松，不锁、不到点震；
 * - 单独点一下（不到 {@link #HOLD_MS}）= 点了一下：松手就松，不锁；
 * - 单独按住 {@link #HOLD_MS}–{@link #LOCK_MAX_MS} 松手 = 锁（到 0.4 秒震一下 + armed 高亮"现在松手就锁"；松手时 HID 照旧按着，主机看不到松开）；
 * - 单独按住超过 {@link #LOCK_MAX_MS} = 高亮退回、松手不锁。
 * 锁了以后同以前：打完下一个普通键（松开时）解开；再按一下锁住的键也解开（这一下松手就松，按多久都不再锁）。修饰键按下 / 松开不解开别的锁定。
 * Caps：按下先不发；不到 0.4 秒松手 = 点一下：发一次 Caps Lock（按着 {@link #CAPS_TAP_MS} 再松）+ 显示翻一下（Host.caps(true)）；
 * 按住到 0.4 秒 = 只翻显示（Host.caps(false)）、不发键码、震一下，松手什么都不做。
 * 合并键：不到 0.4 秒松手 = tap 键点一下；按住到 0.4 秒 = hold 键点一下（只发一次，不连发）+ 震一下，松手什么都不做。
 * 待定的键（Caps、合并键按下了、还没定）在别的键按下、手指按着的修饰键松开（正常松开；被取消的不算）、缩放开始（TouchRouter 调 {@link #settle}）之前先当点一下结算：
 * 出字的顺序和修饰键跟按下的先后一致（不然"，空格"会变成"空格，"、按住 Shift 点合并键再先松 Shift 会丢掉 Shift）。
 * 锁定被解开（打完普通键、Caps 松开、CMD_UNLATCH）不结算：锁定是给下一个键用的，被先按下的那个键用掉了
 * （锁着 Shift 快打 "I."：I 抬起解开 Shift 时合并键还按着，在那一刻结算会出 "I<"）。
 *
 * Fn（第 3 批，协议 21，实体键盘 75% / 65% / 苹果紧凑；{@link Proto#FN_KEY} / {@link Proto#FN_GLOBE_KEY}）。协议 22 起（2026-11-01 晚，用户：
 * "fn 没必要跟别的那个功能键一样啊……点一下，就是单次锁，如果点住，就变成永久锁。单次锁就是说，它在摁功能键就自动触发反转，永久锁就是不管他按几个功能键，
 * 就一直锁住"）不再照修饰键那套：
 * - 按住 Fn 再按别的键 = 组合（那个键出 Fn 层，松手就回底层），同以前；
 * - Windows 模式的 Fn 单独点一下（不到 {@link #HOLD_MS}）= **单次锁**（{@link Proto#FN_LATCHED}）：下一个普通键出 Fn 层、它松开时解开
 *   （同锁住的修饰键"打完下一个普通键解开"；修饰键按下 / 松开不消耗它：单次锁 → Shift → 1 = Shift+F1）；
 *   Mac 模式的 fn🌐 单独点一下照旧 = 🌐（{@link Host#globe}；它有单独的用途，没有单次锁）；
 * - 单独按住到 0.4 秒 = **永久锁**（Fn Lock，{@link Proto#FN_LOCKED}）：到点震一下 + {@link Proto#FN_ARMED}（"松手就永久锁"），按多久都算（修饰键那条
 *   "超过 1 秒不锁"对 Fn 不适用）；之后按多少个键都出 Fn 层，直到再单独按一下 Fn；
 * - 锁着（单次或永久）时单独再按一下 Fn（点或按住都算）：松手解开，这一下不再锁、fn🌐 也不出 🌐；锁着时把 Fn 当组合用（按住它按了别的键、
 *   动了鼠标，或者按下那一刻已经有别的键按着）：照旧出 Fn 层，Fn 松手后锁照旧（单次锁照样被那个键用掉：它松开时解开）。
 * "用过"、按下时别的键 / 鼠标键按着、被取消（不锁、不解开、不出 🌐）同修饰键；CMD_UNLATCH、releaseAll 两种锁都清。Fn 不发 HID。
 * 带 Fn 层的键（{@link Proto#fnLayerKey}）：**按下那一刻**按 Fn 按着或锁着定发 Fn 层的码还是底层的码、记下来，之后完全当那个码处理
 * （底层是修饰键的——Windows 模式右 Ctrl——照样有修饰键的点一下 / 锁）；松开松的是按下时记下的那个（先松 Fn 再松这个键，松的照样是 Fn 层的码）。
 * 同一个带 Fn 层的键已经有手指按着时，后来的手指跟着第一根的码（真键盘上已经按下的键不会再按下一次；面板自己收触摸那条路的 CMD_HID_KEY
 * 只带 id、分不出哪根手指，三条路要一样就只能按 id 记），最后一根松开才忘掉。
 *
 * 按着的键按 id 计数（同一个用法码两根手指：⇧ 和功能行的 Shift、两个空格副本；带 Fn 层的键按定下来的码计数）。计数已经是 0 的松开（releaseAll 之后迟到的）整个不理。
 * 普通键照旧每个松开都松 HID（两根手指按两个副本时先抬起的那根就松开）。
 * 到点按"截止时刻 + 当前状态"处理（{@link #onTimer}），不认是哪一次排的：每个带时间的入口先把到了截止时刻的处理掉，
 * 所以结果只看事件的时间、不看定时器晚到多少；过期的回调什么都不会多做（比 CLAUDE.md"撤掉 / 取代了的定时器绝不再到点"多一层保险）。
 */
final class KeyHolds {
    /** 修饰键"现在松手就锁"、Caps 只切显示、合并键出 hold 键。 */
    static final long HOLD_MS = 400;
    /** 修饰键单独按住超过这么久：高亮退回、松手不锁。 */
    static final long LOCK_MAX_MS = 1000;
    /** 点一下 Caps：按下 0x39 后按着多久再松（零长度的按下有被主机吞掉的风险，同"双击要隔开发"那条）。 */
    static final long CAPS_TAP_MS = 60;
    static final int CAPS = 0x39;

    interface Host {
        /** 原语：普通键按下（自带修饰键的先按、发报文）。不回调 KeyHolds。 */
        void pressKey(int key);

        /** 原语：普通键松开（先松键、再松它自带的修饰键，发报文）。"打完一个键解开锁定"由 KeyHolds 自己做。 */
        void releaseKey(int key);

        /** latched / held / armed 变了（或者可能变了）：HID 的修饰键字节照新的算（变了才发）、报界面（变了才报）。 */
        void modsChanged();

        /** 鼠标键按着（左 / 右 / 中，含中键按着还没发出去的）。 */
        boolean mouseBusy();

        /** 按住到点（修饰键 armed、Caps 只切显示、合并键出 hold 键）：震一下。 */
        void holdReached();

        /** Caps 的显示该翻了；sent = 这次真的给主机发了 Caps Lock。 */
        void caps(boolean sent);

        /** Mac 模式的 fn🌐 单独点了一下：切输入法（TouchRouter 报 Proto.GLOBE_BUTTON_ID 的 BUTTON_TAP，和 🌐 键一样由 App 执行）。 */
        void globe();

        /** 过 delayMs 后调 {@link #onTimer}；再调取代上一次（TouchRouter.Output.scheduleKeys）。 */
        void schedule(long delayMs);

        void cancel();
    }

    /**
     * 修饰键每一位的阶段：没按着 / 单独按着、还没到 0.4 秒 / 到了、松手就锁 / 这一下绝不锁了（组合过、超过 1 秒、解锁中）。
     * UNLOCK 只给 Fn：锁着（单次或永久）时按下的，松手（没用过、没被取消）就解开；用过了（当组合用）变 SPENT、锁照旧。
     */
    private static final int NONE = 0, PENDING = 1, ARMED = 2, SPENT = 3, UNLOCK = 4;
    /** Caps、合并键的阶段（PENDING 同上）：到点了（Caps 切过显示 / 合并键发过 hold 键）/ 结算过了（当点一下发出去了）。 */
    private static final int HELD = 2, DONE = 3;

    private static final class Timed {
        int phase = PENDING;
        long downAt;
    }

    /** 修饰键八位之后的第 9 格是 Fn（阶段、按下时刻和修饰键放在一起算：到点、用过、排定时器同一份代码）。 */
    private static final int FN = 8;

    /** 带 Fn 层的键按下时定下来的码，和这个 id 还有几根手指按着。 */
    private static final class FnPress {
        int code, n;
    }

    private final Host host;
    /** 每个键 id 有几根手指（或几个来源）按着；0 的不留。带 Fn 层的键按定下来的码记（{@link #fnPresses} 记 id → 码）。 */
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final int[] modPhase = new int[FN + 1];
    private final long[] modDownAt = new long[FN + 1];
    private int latched;
    /** Fn 单次锁（只管下一个普通键；Windows 模式单点 Fn）。 */
    private boolean fnOnce;
    /** Fn 永久锁（单独按住 Fn / fn🌐 到 0.4 秒；再单独按一下才解开）。 */
    private boolean fnLock;
    /** 这次按下的 Fn 是 Mac 模式的 fn🌐（单点出 🌐，不是单次锁）。 */
    private boolean fnGlobe;
    /** 按着的带 Fn 层的键：id → 按下那一刻定的码。 */
    private final Map<Integer, FnPress> fnPresses = new HashMap<>();
    /** 按着的 Caps / 合并键（按下的先后）。 */
    private final Map<Integer, Timed> timed = new LinkedHashMap<>();
    /** 点了一下 Caps：0x39 还按着，到 capsReleaseAt 松开。 */
    private boolean capsReleasing;
    private long capsReleaseAt;
    /** 宿主那边排着一次（在 scheduledAt 到点）。 */
    private boolean scheduled;
    private long scheduledAt;
    /** 最近一次带时间的入口给的时间（排定时器时算还要等多久）。 */
    private long last = Long.MIN_VALUE;

    KeyHolds(Host host) {
        this.host = host;
    }

    static boolean isModifier(int key) {
        return key >= 0xE0 && key <= 0xE7;
    }

    /** 按住要计时、按下先不发的键：Caps、合并键。 */
    static boolean isTimed(int key) {
        return key == CAPS || Proto.isDualKey(key);
    }

    /** 锁住的修饰键（HID 修饰键位）。 */
    int latched() {
        return latched;
    }

    /** 有手指按着的修饰键。 */
    int held() {
        int m = 0;
        for (int b = 0; b < 8; b++) if (count(0xE0 + b) > 0) m |= 1 << b;
        return m;
    }

    /** "现在松手就锁"的修饰键（单独按住到了 0.4 秒、还没到 1 秒、没用过）。 */
    int armed() {
        int m = 0;
        for (int b = 0; b < 8; b++) if (modPhase[b] == ARMED) m |= 1 << b;
        return m;
    }

    /** Fn 的状态（Proto.FN_LATCHED / FN_HELD / FN_ARMED / FN_LOCKED 位，同 MSG_MODIFIERS 第四个字节）。 */
    int fn() {
        int s = 0;
        if (fnOnce) s |= Proto.FN_LATCHED;
        if (fnCount() > 0) s |= Proto.FN_HELD;
        if (modPhase[FN] == ARMED) s |= Proto.FN_ARMED;
        if (fnLock) s |= Proto.FN_LOCKED;
        return s;
    }

    /** Fn 层这时生效吗（带 Fn 层的键现在按下会发 Fn 层的码）：Fn 按着、单次锁或永久锁着。 */
    boolean fnActive() {
        return fnCount() > 0 || fnOnce || fnLock;
    }

    /** 宿主那边挂着定时器没有（测试用）。 */
    boolean scheduled() {
        return scheduled;
    }

    /** 有没有键的手指按着（测试用）。 */
    boolean anyHeld() {
        return !counts.isEmpty();
    }

    // ═══════════ 入口 ═══════════

    /** 任何键按下（触摸分流按在键上的、界面发来的 key()）。 */
    void down(int key, long t) {
        begin(t);
        if (Proto.isFnLayerKey(key)) key = resolve(key);   // 按下那一刻定码，之后当那个码处理
        settleExcept(key, t);
        boolean busy = anyHeldExcept(key) || host.mouseBusy();
        boolean used = markUsedExcept(key);
        int n = count(key);
        int fnBefore = fnCount();
        counts.put(key, n + 1);
        if (Proto.isFnKey(key)) {
            if (fnBefore == 0) {
                fnGlobe = key == Proto.FN_GLOBE_KEY;
                if (busy) {                    // 按下时已经有别的键 / 鼠标键按着：组合的一部分，绝不锁、不解开、不出 🌐
                    modPhase[FN] = SPENT;
                } else if (fnOnce || fnLock) { // 锁着时按下：松手（没当组合用、没被取消）就解开，按多久都不再锁（按着时照样是 Fn 层）
                    modPhase[FN] = UNLOCK;
                } else {
                    modPhase[FN] = PENDING;
                    modDownAt[FN] = t;
                }
            }
            host.modsChanged();   // 报界面：Fn 按着（带 Fn 层的键帽整片换字）
        } else if (isModifier(key)) {
            if (n == 0) {
                int b = key - 0xE0, bit = 1 << b;
                if ((latched & bit) != 0) {   // 这一下是"解开"：松手就松，按多久都不再锁
                    latched &= ~bit;
                    modPhase[b] = SPENT;
                } else if (busy) {             // 按下时已经有别的键 / 鼠标键按着：组合的一部分，绝不锁
                    modPhase[b] = SPENT;
                } else {
                    modPhase[b] = PENDING;
                    modDownAt[b] = t;
                }
            }
            host.modsChanged();   // HID 里 b 按着（按下马上发）
        } else if (isTimed(key)) {
            if (n == 0) {
                Timed d = new Timed();
                d.downAt = t;
                timed.put(key, d);
            }
            if (used) host.modsChanged();
        } else {
            host.pressKey(key);
            if (used) host.modsChanged();
        }
        reschedule();
    }

    /**
     * 键松开；canceled = 被取消的（手掌、丢数据、收尾补报的、面板换键时消失了的）：修饰键、Fn 松开但不锁（fn🌐 不出 🌐）、不结算别的待定键，
     * Caps、合并键什么都不出。带 Fn 层的键松的是按下时定的码。
     */
    void up(int key, long t, boolean canceled) {
        if (Proto.isFnLayerKey(key)) {
            FnPress p = fnPresses.get(key);
            if (p == null) return;   // releaseAll 之后迟到的
            if (--p.n <= 0) fnPresses.remove(key);
            key = p.code;
        }
        int n = count(key);
        if (n == 0) return;   // releaseAll 之后迟到的松开：整个不理（不松、不解开新的锁定、不结算）
        begin(t);
        if (Proto.isFnKey(key)) {
            // Fn 不发 HID，松开不结算待定的 Caps / 合并键（它们出的码和 Fn 无关）
            put(key, n - 1);
            if (fnCount() == 0) {
                int phase = modPhase[FN];
                if (!canceled) {
                    if (phase == UNLOCK) {            // 锁着时单独按了一下：解开（两种锁）
                        fnOnce = false;
                        fnLock = false;
                    } else if (phase == ARMED) {      // 单独按住到了 0.4 秒：永久锁
                        fnLock = true;
                    } else if (phase == PENDING) {    // 单独点一下：fn🌐 = 🌐，Windows 模式的 Fn = 单次锁
                        if (fnGlobe) host.globe();
                        else fnOnce = true;
                    }
                }
                modPhase[FN] = NONE;
                host.modsChanged();
            }
        } else if (isModifier(key)) {
            // HID 要变了：待定的键先当点一下发出去，还带着这个修饰键。被取消的不结算：收尾补报的抬起按 slot / 手指编号的先后来，
            // 修饰键那根排在前面时会把待定的 Caps / 合并键当点一下发出去（凭空一次 Caps Lock、一个 "<"）；留着由它们自己的抬起
            // （被取消就什么都不出）或接下来的 releaseAll（丢掉）收
            if (n == 1 && !canceled) settleExcept(-1, t);
            put(key, n - 1);
            if (n == 1) {
                int b = key - 0xE0;
                if (modPhase[b] == ARMED && !canceled) latched |= 1 << b;   // HID 照旧按着，主机看不到松开
                modPhase[b] = NONE;
                host.modsChanged();
            }
        } else if (isTimed(key)) {
            put(key, n - 1);
            if (n == 1) {
                Timed d = timed.remove(key);
                if (d != null && d.phase == PENDING && !canceled) tap(key, t);
            }
        } else {
            put(key, n - 1);
            host.releaseKey(key);
            unlatchAfterKey();
        }
        reschedule();
    }

    /** 按着的修饰键、Fn"用过了"（鼠标键、滚轮、缩放、手势；TouchRouter 调，t 是它最近的时间）。都没按着时什么都不做。 */
    void used(long t) {
        begin(t);
        if (markUsedExcept(-1)) host.modsChanged();
        reschedule();
    }

    /** 待定的键当点一下结算（缩放开始、zoomCtrl 按下之前 TouchRouter 调）。 */
    void settle(long t) {
        begin(t);
        settleExcept(-1, t);
        reschedule();
    }

    /** 定时器到点（宿主照 Host.schedule 调）：只处理截止时刻 ≤ t、状态还对得上的，再按剩下最早的截止时刻重排。 */
    void onTimer(long t) {
        scheduled = false;
        begin(t);
        reschedule();
    }

    /**
     * CMD_UNLATCH：清掉全部锁定（HID 里这些位松开，同一位还有手指按着的照旧按着；Fn 的单次锁、永久锁也解开）；按着的、armed、待定的不动
     * （按着的 Fn 这时在等"松手解开"的话，松手时已经没有锁可解，什么都不多做）。
     */
    void unlatch(long t) {
        begin(t);
        if (latched != 0 || fnOnce || fnLock) {
            latched = 0;
            fnOnce = false;
            fnLock = false;
            host.modsChanged();
        }
        reschedule();
    }

    /**
     * 全部归零：计数、阶段、锁定（连 Fn 的单次锁、永久锁）、按着的带 Fn 层的键记的码、待定的 Caps / 合并键丢掉（不发）、Caps 的 60ms 松开不再等，撤掉定时器。
     * 报文由 TouchRouter 的 keyboard.releaseAll 发。
     */
    void releaseAll() {
        counts.clear();
        Arrays.fill(modPhase, NONE);
        latched = 0;
        fnOnce = false;
        fnLock = false;
        fnPresses.clear();
        timed.clear();
        capsReleasing = false;
        scheduled = false;
        host.cancel();
    }

    // ═══════════ 内部 ═══════════

    private int count(int key) {
        Integer n = counts.get(key);
        return n == null ? 0 : n;
    }

    /** 按着的 Fn（两种 Fn 键合起来数：一块键盘只有一个，界面发来的 key() 两种都可能有）。 */
    private int fnCount() {
        return count(Proto.FN_KEY) + count(Proto.FN_GLOBE_KEY);
    }

    /** 带 Fn 层的键按下：这个 id 已经有手指按着就跟着它的码，不然按 Fn 现在生不生效定（Fn 层的码 / 底层的码）、记下来。 */
    private int resolve(int id) {
        FnPress p = fnPresses.get(id);
        if (p == null) {
            p = new FnPress();
            p.code = fnActive() ? Proto.fnCode(id) : Proto.fnBase(id);
            fnPresses.put(id, p);
        }
        p.n++;
        return p.code;
    }

    private void put(int key, int n) {
        if (n <= 0) counts.remove(key);
        else counts.put(key, n);
    }

    private boolean anyHeldExcept(int key) {
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getKey() != key && e.getValue() > 0) return true;
        }
        return false;
    }

    /** 每个入口先记下时间、把到了截止时刻的处理掉（同定时器准时到点）。 */
    private void begin(long t) {
        if (t > last) last = t;
        advance(t);
    }

    /** 截止时刻 ≤ t 的都处理掉：Caps 的 60ms 松开、修饰键 armed / 退回、Caps 只切显示、合并键出 hold 键。 */
    private void advance(long t) {
        if (capsReleasing && t >= capsReleaseAt) {
            capsReleasing = false;
            host.releaseKey(CAPS);
            unlatchAfterKey();   // 同打完一个普通键
        }
        boolean changed = false;
        for (int b = 0; b <= FN; b++) {   // 修饰键八位和 Fn
            if (b == FN) {   // Fn：到 0.4 秒就是"松手就永久锁"，按多久都算（没有 1 秒的上限；定时器晚到也照样到点、震一下）
                if (modPhase[FN] == PENDING && t >= modDownAt[FN] + HOLD_MS) {
                    modPhase[FN] = ARMED;
                    host.holdReached();
                    changed = true;
                }
                continue;
            }
            if (modPhase[b] == PENDING && t >= modDownAt[b] + HOLD_MS) {
                if (t >= modDownAt[b] + LOCK_MAX_MS) {
                    modPhase[b] = SPENT;   // 晚到超过 1 秒（定时器卡住了）：直接当超过 1 秒，不震
                } else {
                    modPhase[b] = ARMED;
                    host.holdReached();
                    changed = true;
                }
            }
            if (modPhase[b] == ARMED && t >= modDownAt[b] + LOCK_MAX_MS) {
                modPhase[b] = SPENT;   // 高亮退回，不震
                changed = true;
            }
        }
        if (changed) host.modsChanged();
        for (Map.Entry<Integer, Timed> e : new ArrayList<>(timed.entrySet())) {
            Timed d = e.getValue();
            if (d.phase != PENDING || t < d.downAt + HOLD_MS) continue;
            int key = e.getKey();
            d.phase = HELD;
            if (key == CAPS) {
                host.caps(false);   // 只切显示，不发键码
            } else {
                emit(Proto.dualHold(key));
            }
            host.holdReached();
        }
    }

    /** 待定的 Caps / 合并键（except 这个 id 不算）当点一下发出去。 */
    private void settleExcept(int except, long t) {
        List<Map.Entry<Integer, Timed>> list = new ArrayList<>(timed.entrySet());
        for (Map.Entry<Integer, Timed> e : list) {
            Timed d = e.getValue();
            if (e.getKey() == except || d.phase != PENDING) continue;
            d.phase = DONE;   // 先改状态再发（host 的原语不回调这里，先改是双保险）
            tap(e.getKey(), t);
        }
    }

    /**
     * 按着的修饰键和 Fn（except 这个 id 不算）标"用过"：PENDING / ARMED → SPENT；Fn 的 UNLOCK（锁着时按下的）也 → SPENT：当组合用了，松手不解开。
     * 返回 armed 变了没有（要报界面）。
     */
    private boolean markUsedExcept(int except) {
        boolean armedChanged = false;
        for (int b = 0; b <= FN; b++) {
            if (b == FN ? Proto.isFnKey(except) : 0xE0 + b == except) continue;
            if (modPhase[b] == ARMED) armedChanged = true;
            if (modPhase[b] == PENDING || modPhase[b] == ARMED || modPhase[b] == UNLOCK) modPhase[b] = SPENT;
        }
        return armedChanged;
    }

    /** Caps / 合并键点一下。 */
    private void tap(int key, long t) {
        if (key == CAPS) tapCaps(t);
        else emit(Proto.dualTap(key));
    }

    /** 发一次 Caps Lock（按着 60ms 再松）、显示翻一下。上一下的 60ms 还没到（几乎不会）先松开上一下，不然这一下的按下发不出去。 */
    private void tapCaps(long t) {
        if (capsReleasing) {
            capsReleasing = false;
            host.releaseKey(CAPS);
            unlatchAfterKey();
        }
        host.pressKey(CAPS);
        capsReleasing = true;
        capsReleaseAt = t + CAPS_TAP_MS;
        host.caps(true);
    }

    /** 普通键点一下（按下、松开两帧挨着发），同打完一个普通键解开锁定。 */
    private void emit(int key) {
        host.pressKey(key);
        host.releaseKey(key);
        unlatchAfterKey();
    }

    /** 打完一个普通键：锁住的修饰键、Fn 的单次锁都解开（永久锁不动）。 */
    private void unlatchAfterKey() {
        if (latched == 0 && !fnOnce) return;
        latched = 0;
        fnOnce = false;
        host.modsChanged();
    }

    /** 按剩下最早的截止时刻排定时器（正好排在那一刻，不早到；已经排着同一刻的不重排）；什么都不等了就撤掉。 */
    private void reschedule() {
        boolean has = false;
        long next = 0;
        if (capsReleasing) {
            has = true;
            next = capsReleaseAt;
        }
        for (int b = 0; b <= FN; b++) {
            long at;
            if (modPhase[b] == PENDING) at = modDownAt[b] + HOLD_MS;
            else if (modPhase[b] == ARMED && b != FN) at = modDownAt[b] + LOCK_MAX_MS;   // Fn 快锁了没有 1 秒的上限，不再等
            else continue;
            if (!has || at < next) next = at;
            has = true;
        }
        for (Timed d : timed.values()) {
            if (d.phase != PENDING) continue;
            long at = d.downAt + HOLD_MS;
            if (!has || at < next) next = at;
            has = true;
        }
        if (!has) {
            if (scheduled) {
                scheduled = false;
                host.cancel();
            }
            return;
        }
        if (scheduled && scheduledAt == next) return;
        scheduled = true;
        scheduledAt = next;
        host.schedule(Math.max(0, next - last));
    }
}
