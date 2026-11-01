package dev.douhua.server;

import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分屏透传：独占着触摸屏时，把归系统的手指（{@link TouchSplit}）注入回系统，另一边的应用、分隔条、系统手势照常能用。
 * 每个会话一份。
 *
 * 线程：读触摸的线程（监管线程）只调 down / move / frame / up / cancelAll，这些只改 {@link InjectStream}、把事件排进队列，
 * 不碰系统服务；真正的注入在单独的 douhua-inject 线程里做。理由：injectInputEvent 是同步 binder 调用，系统服务卡住时
 * 会卡住调用的线程，读线程一卡就问不到"还要不要独占"，破坏"不满足条件 100ms 内释放"。
 * 注入线程一次调用超过 {@link #STUCK_MS} 没返回（{@link #checkStuck}，读线程每轮问一次），透传就判不可用，分屏下释放独占。
 *
 * 判不可用（{@link #unavailable} 不为 null）的情况：MotionEvent.setDisplayId 反射不到；注入抛异常（含没权限）；超时；
 * 新流的 DOWN 被系统拒绝、补救（{@link #recover}）后还被拒；流中间的事件被拒；这块屏清残留彻底失败过（{@link #DEAD_DISPLAYS}，
 * 进程级，重启手机才好，新会话一开始就不独占）。不可用时分屏下不独占（Session 退回让系统自己收触摸），
 * 已经注入出去的流照样补 CANCEL（{@link #streamOk} 为真时 CANCEL 不受"不可用"挡）。
 *
 * 收尾（{@link #close} / {@link #cancelBeforeExit}）后注入线程不再发流里的任何事件，也不再清残留：只按系统实际收下的
 * 按着集合（{@link #held}）补一个 CANCEL。不然 close 只等 300ms，排着的事件会在会话结束后接着发，和新会话的注入流
 * （设备号 -1，同一份校验状态）交错，把新会话按着的手指抬起或取消。
 *
 * 残留：Android 16 起注入流的校验状态只在屏幕被移除时才清。上一个服务在注入手指按着时被杀掉，系统里就留着"按着"的
 * 设备 -1 手指，之后这块屏上所有注入的 DOWN 都被拒（连 adb shell input tap 也不行）。CANCEL 要带的手指集合必须和系统记着的
 * 完全相等（input_verifier.rs），所以系统每收下一个改变按着集合的事件（含 DOWN），就把集合落盘
 * （{@link #DOWN_FILE}，只有屏幕编号和 slot 编号）。
 * 但 DOWN 被拒不一定是残留：设备号 -1 的校验状态所有没带 targetUid 的注入者共用（adb shell input、scrcpy 的控制……），
 * 别人正按着时豆花的 DOWN 也被拒。所以 DOWN 被拒时（{@link #recover}）：先按记录发一次 CANCEL（记录是豆花自己的）；
 * 记录不在 / 对不上，就等 {@link #RESEND_WAIT_MS} 再发一次这个 DOWN（别人的短手势多半已经结束）；还被拒，
 * 而且有残留的证据（服务启动时记录文件就在 = 上一个服务没正常收尾，或者这个进程一次 DOWN 都还没被收下），才猜：
 * 单根 {0}…{15}、再两根的组合，每次最多 {@link #GUESS_BUDGET_MS}，一个进程只走一遍（{@link #guessNext}，跨会话接着走），
 * 猜中了就不再猜；猜中后不补发这次的 DOWN（这一下手势丢掉，排在后面的事件也不再带着旧时间补发）。被拒的猜测只是被拒，
 * 系统会往 logcat 打一份分发状态。走完都没中 = 记录丢了又是三根以上的残留，只能重启手机。
 */
final class Passthrough implements InjectStream.Sink {
    /** 注入一次超过这么久没返回就判不可用。 */
    static final long STUCK_MS = 500;
    /** 系统那边按着的注入手指的记录（格式见 {@link InjectStream#formatDown}）：下一个服务开局清残留用。 */
    static final String DOWN_FILE = "/data/local/tmp/douhua-inject-down";
    /** DOWN 被拒、记录对不上时，等这么久再发一次（别的注入者的短手势多半已经结束）。 */
    static final long RESEND_WAIT_MS = 300;
    /** 一次猜残留最多花这么久（每次被拒系统都在分发器的锁里打一份分发状态，排在后面的触摸跟着晚）。 */
    static final long GUESS_BUDGET_MS = 300;
    private static final String DEAD_WHY = "注入的触摸被系统拒绝，清残留也没用（系统里多半留着按着的注入手指，重启手机可解）";

    /** 清残留彻底失败过的屏（进程级）：之后的会话一开始就判不可用，分屏下不独占，不再每回前台都被吃掉一次点击、再猜一轮。 */
    private static final Set<Integer> DEAD_DISPLAYS = ConcurrentHashMap.newKeySet();
    /** 服务启动时（第一个会话建 Passthrough 时）残留记录就在：上一个服务没正常收尾。 */
    private static final boolean RECORD_AT_START = new File(DOWN_FILE).exists();
    /** 这个进程有 DOWN 被系统收下过。 */
    private static volatile boolean everAccepted;
    /** 猜残留的清单（单根，再两根）和走到哪了（进程级，跨会话接着走）；猜中过就不再猜。 */
    private static final int[][] GUESSES = guesses();
    private static final AtomicInteger GUESS_CURSOR = new AtomicInteger();
    private static volatile boolean guessHit;

    private final Injector injector;
    private final InjectStream stream = new InjectStream(this);
    /** 单线程、无界队列（事件都很小；注入线程卡住时读线程照样不阻塞，超时后整个透传判不可用）。 */
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "douhua-inject");
        t.setDaemon(true);
        return t;
    });

    /** 注入到哪块屏（接管时定）。 */
    private volatile int displayId;
    /** 不可用的原因；null = 可用。 */
    private volatile String broken;
    /** 注入线程正在调用的开始时间；0 = 空闲。 */
    private volatile long busySince;
    /** 会话收尾了：注入线程只补 CANCEL（见类注释）。 */
    private volatile boolean closed;

    // ── 只在注入线程里用 ──
    /** 这一条流的 DOWN 系统收了：之后的事件（含 CANCEL）照发；没收就整条流都不发（发了也只是被拒、刷日志）。 */
    private boolean streamOk;
    /** 系统实际收下的、这个会话注入的按着的手指（按收下的事件算，不按流里排着的）。 */
    private int[] held = new int[0];
    private final LogThrottle rejectLog = new LogThrottle(60_000);
    /** 写残留记录失败只记一次。 */
    private boolean recordFailed;

    Passthrough(Injector injector) {
        this.injector = injector;
    }

    /** 往 display 这块屏透传不可用的原因；null = 能透传。 */
    String unavailable(int display) {
        String why = injector.motionStatus();
        if (why != null) return why;
        if (DEAD_DISPLAYS.contains(display)) return DEAD_WHY;
        return broken;
    }

    /** 读线程每轮调一次：注入线程卡在一次调用里太久了就判不可用。 */
    void checkStuck(long now) {
        long since = busySince;
        if (since != 0 && now - since > STUCK_MS && broken == null) {
            fail("注入触摸 " + (now - since) + "ms 没返回（系统服务卡住了？）");
        }
    }

    /** 再给一次机会（App 回前台、全屏 ↔ 分屏切换时）：注入线程没卡着就清掉不可用（清残留彻底失败的屏不算，见 {@link #DEAD_DISPLAYS}）。 */
    void retry() {
        if (broken != null && busySince == 0) {
            Server.log("分屏透传：再试一次（上次不可用：" + broken + "）");
            broken = null;
        }
    }

    /** 开始接管：注入到这块屏。上一轮的流在收尾时已经取消过。 */
    synchronized void start(int display) {
        displayId = display;
    }

    /** noFocus：按在豆花窗口里手势带的手指（{@link TouchSplit#SYSTEM_IN_WINDOW}），DOWN 带 NO_FOCUS_CHANGE。 */
    synchronized void down(int slot, float x, float y, long t, boolean noFocus) {
        stream.down(slot, x, y, t, noFocus);
    }

    synchronized void move(int slot, float x, float y) {
        stream.move(slot, x, y);
    }

    synchronized void frame(long t) {
        stream.frame(t);
    }

    synchronized void up(int slot, long t) {
        stream.up(slot, t);
    }

    /** 整条流取消（带 FLAG_CANCELED），还按着的手指作废到抬起。没有按着的什么都不发；可以重复调。 */
    synchronized void cancelAll(long t) {
        stream.cancelAll(t);
    }

    /**
     * 服务马上要 System.exit 时：取消，最多等 waitMs 让注入线程把系统实际按着的取消掉。
     * 注入线程卡着就等不到，只能留下残留（下一个服务开局清）。
     */
    void cancelBeforeExit(long waitMs) {
        closed = true;
        cancelAll(SystemClock.uptimeMillis());
        submitCancelHeld();
        drain(waitMs);
    }

    /** 会话结束：取消、等注入线程把系统实际按着的取消掉（最多 waitMs）、停掉注入线程。之后注入线程只会补 CANCEL。 */
    void close(long waitMs) {
        closed = true;
        cancelAll(SystemClock.uptimeMillis());
        submitCancelHeld();
        worker.shutdown();
        try {
            worker.awaitTermination(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 队列里没有流的事件时（流早就空了）也让注入线程看一眼还有没有系统按着的。 */
    private void submitCancelHeld() {
        int display = displayId;
        try {
            worker.execute(() -> cancelHeld(display));
        } catch (RejectedExecutionException ignored) {
            // 已经停了
        }
    }

    private void drain(long waitMs) {
        CountDownLatch done = new CountDownLatch(1);
        try {
            worker.execute(done::countDown);
            done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 已经停了
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ═══════════ 注入线程 ═══════════

    @Override
    public void emit(InjectStream.Event e) {
        int display = displayId;
        try {
            worker.execute(() -> inject(display, e));
        } catch (RejectedExecutionException ignored) {
            // 会话已经收尾
        }
    }

    private void inject(int display, InjectStream.Event e) {
        if (closed) {
            cancelHeld(display);   // 收尾后：流里的事件一律不发，只取消系统实际按着的
            return;
        }
        int masked = e.masked();
        if (masked == InjectStream.ACTION_DOWN) {
            streamOk = false;
            if (unavailable(display) != null) return;   // 不可用了：新流不再开（Session 随后释放独占）
        } else if (!streamOk) {
            return;   // 这条流的 DOWN 没进去
        }
        boolean ok;
        int recovered = REJECTED;
        try {
            ok = call(display, e);
            if (!ok && masked == InjectStream.ACTION_DOWN) {
                recovered = recover(display, e);
                ok = recovered == RECOVERED;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            fail("注入触摸出错：" + LogThrottle.describe(ex));
            return;
        }
        if (masked == InjectStream.ACTION_DOWN) {
            streamOk = ok;
            if (ok) {
                everAccepted = true;
                setHeld(display, e.downAfter());
            } else if (recovered == CLEARED) {
                // 清掉了，但这一下手势不补发（排在后面的事件跟着 !streamOk 不发），下一个 DOWN 照常
            } else {
                fail(DEAD_DISPLAYS.contains(display) ? DEAD_WHY : "注入的触摸被系统拒绝（DOWN）");
            }
            return;
        }
        if (!ok) {
            // 流中间被拒：系统那边的状态和我们记的对不上了，可能留下了按着的手指。这条流不再发，透传判不可用
            // （Session 释放独占、换布局时再试）；再试时下一个 DOWN 被拒还会补救一次（记录里是系统最后收下的集合）
            streamOk = false;
            String line = rejectLog.error("注入的触摸被系统拒绝（" + e + "）", SystemClock.uptimeMillis());
            if (line != null) Server.log(line);
            fail("注入的触摸被系统拒绝（" + InjectStream.name(masked) + "）");
            return;
        }
        if (masked != InjectStream.ACTION_MOVE) setHeld(display, e.downAfter());
        if (masked == InjectStream.ACTION_UP || masked == InjectStream.ACTION_CANCEL) streamOk = false;
    }

    /** 收尾后：系统实际还按着这个会话注入的手指就补一个 CANCEL（集合正好是系统收下的）；没有就什么都不发。 */
    private void cancelHeld(int display) {
        streamOk = false;
        if (held.length == 0) return;
        int[] ids = held;
        held = new int[0];
        try {
            if (call(display, InjectStream.cancelOf(ids, SystemClock.uptimeMillis()))) record(display, new int[0]);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 取消不掉：记录还在，下一个服务开局清
        }
    }

    /** 系统收下了改变按着集合的事件：记下（内存里一份，落盘一份）。 */
    private void setHeld(int display, int[] ids) {
        held = ids;
        record(display, ids);
    }

    /** 落盘现在按着哪些（一根不剩就删掉记录）。写不了只影响下次清残留（要猜）。 */
    private void record(int display, int[] ids) {
        File f = new File(DOWN_FILE);
        try {
            if (ids.length == 0) {
                if (f.exists() && !f.delete()) throw new IOException("删不掉");
                return;
            }
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(InjectStream.formatDown(display, ids).getBytes(StandardCharsets.US_ASCII));
            }
        } catch (IOException | RuntimeException ex) {
            if (!recordFailed) Server.log("分屏透传：写残留记录 " + DOWN_FILE + " 失败（只影响下次清残留）：" + LogThrottle.describe(ex));
            recordFailed = true;
        }
    }

    /** 上一个服务留下的记录：屏幕对得上才返回按着的编号，没有 / 读不懂返回 null。 */
    private static int[] readRecord(int display) {
        File f = new File(DOWN_FILE);
        if (!f.isFile() || f.length() > 256) return null;
        try (FileInputStream i = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = i.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return InjectStream.parseDown(new String(buf, 0, n, StandardCharsets.US_ASCII), display);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** 调一次注入，前后记下"正忙"，让读线程看得出卡住了。 */
    private boolean call(int display, InjectStream.Event e) throws ReflectiveOperationException {
        busySince = Math.max(1, SystemClock.uptimeMillis());
        try {
            return injector.motion(display, e);
        } finally {
            busySince = 0;
        }
    }

    /** {@link #recover} 的结果：这次的 DOWN 收下了。 */
    private static final int RECOVERED = 0;
    /** 清掉了按着的注入手指，但这次的 DOWN 不补发。 */
    private static final int CLEARED = 1;
    /** 还被拒。 */
    private static final int REJECTED = 2;

    /** DOWN 被拒后的补救，顺序和理由见类注释。 */
    private int recover(int display, InjectStream.Event down) throws ReflectiveOperationException {
        int[] rec = readRecord(display);
        if (rec != null && rec.length > 0 && !closed && call(display, InjectStream.cancelOf(rec, down.eventTime))) {
            held = new int[0];
            record(display, new int[0]);
            Server.log("分屏透传：按残留记录清掉了系统里按着的注入手指（" + rec.length + " 根，多半是上一个服务没正常收尾时留下的）");
            return call(display, down) ? RECOVERED : REJECTED;
        }
        if (closed) return REJECTED;
        sleepQuietly(RESEND_WAIT_MS);   // 不在 call 里，读线程的 checkStuck 不算它
        if (closed) return REJECTED;
        if (call(display, down)) {
            Server.log("分屏透传：注入的触摸被拒一次，" + RESEND_WAIT_MS + "ms 后重发收下了（多半是别的注入者，比如 adb input / scrcpy，正好按着）");
            return RECOVERED;
        }
        boolean suspected = GUESS_CURSOR.get() > 0 || RECORD_AT_START || !everAccepted;
        if (guessHit || !suspected) return REJECTED;
        int cleared = guess(display, down.eventTime);
        if (cleared > 0) {
            held = new int[0];
            record(display, new int[0]);
            Server.log("分屏透传：清掉了系统里按着的注入手指 " + cleared + " 根（可能是上一个服务的残留，"
                    + "也可能是 adb input / scrcpy 等别的注入者正在进行的触摸）；这一下触摸丢掉，下一下照常");
            return CLEARED;
        }
        if (GUESS_CURSOR.get() >= GUESSES.length && !guessHit) DEAD_DISPLAYS.add(display);
        return REJECTED;
    }

    /**
     * 接着清单往下猜，最多 {@link #GUESS_BUDGET_MS}，返回清掉了几根（0 = 没中）。CANCEL 的手指集合要和系统记着的完全相等，
     * 所以至多一个会被收下，收下就停、以后不再猜。
     */
    private int guess(int display, long t) throws ReflectiveOperationException {
        long deadline = SystemClock.uptimeMillis() + GUESS_BUDGET_MS;
        while (!closed && SystemClock.uptimeMillis() < deadline) {
            int[] ids = guessNext();
            if (ids == null) return 0;
            if (call(display, InjectStream.cancelOf(ids, t))) {
                guessHit = true;
                return ids.length;
            }
        }
        return 0;
    }

    /** 清单的下一项（进程级，几个会话的注入线程共用）；走完了返回 null。 */
    private static int[] guessNext() {
        int i = GUESS_CURSOR.getAndIncrement();
        if (i >= GUESSES.length) {
            GUESS_CURSOR.set(GUESSES.length);
            return null;
        }
        return GUESSES[i];
    }

    private static int[][] guesses() {
        int[][] singles = InjectStream.residueGuesses(false);
        int[][] pairs = InjectStream.residueGuesses(true);
        int[][] out = new int[singles.length + pairs.length][];
        System.arraycopy(singles, 0, out, 0, singles.length);
        System.arraycopy(pairs, 0, out, singles.length, pairs.length);
        return out;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(String why) {
        if (broken == null) Server.log("分屏透传不可用：" + why);
        broken = why;
    }
}
