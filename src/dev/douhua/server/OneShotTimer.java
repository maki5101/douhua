package dev.douhua.server;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * TouchRouter 的一次性定时器（{@code Output.schedule / cancelSchedule} 在服务里的实现）：再排一次取代上一次，撤掉就不再到点。
 *
 * 为什么要令牌：{@link ScheduledFuture#cancel} 撤不掉已经开始跑的任务——到点了、正卡在 synchronized(lock) 上等监管线程放锁的那一次，
 * 撤掉之后照样会拿到锁往下调。引擎只有一个定时器槽、不认回调是哪一次排的，过期的回调会被当成随后新排的那一个：
 * 惯性每 16ms 一帧，这一帧刚到点时同一帧落下两根手指（先按在键上停了惯性，再按在触控板上排了长按），过期的那一帧就把长按提前触发、左键按下进了拖动。
 * 现在每排一次发一个新令牌，回调拿到锁先看自己是不是最新那个，不是（被撤掉 / 被取代了）就什么都不做。
 *
 * schedule / cancel 必须在持有 lock 时调（Session 里 router 只在持锁时访问，router 调 Output 的这两个方法时本来就持着）；
 * 回调也在持锁时跑，回调里可以再排（惯性一帧接一帧）。纯 JDK（tools/test.sh 离线测）。
 */
final class OneShotTimer {
    private final ScheduledExecutorService exec;
    private final Object lock;
    private final Runnable fire;
    private ScheduledFuture<?> pending;
    /** 最新排的那一次；null = 没排着。回调里比的是引用。 */
    private Object token;

    OneShotTimer(ScheduledExecutorService exec, Object lock, Runnable fire) {
        this.exec = exec;
        this.lock = lock;
        this.fire = fire;
    }

    /** 过 delayMs 后在持锁时调 fire；排着的上一次作废。 */
    void schedule(long delayMs) {
        cancel();
        Object mine = new Object();
        token = mine;
        try {
            pending = exec.schedule(() -> {
                synchronized (lock) {
                    if (token != mine) return;   // 撤掉 / 被取代之后才拿到锁的那一次：过期了
                    token = null;
                    pending = null;
                    fire.run();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 会话已经收尾、定时器停了（监管线程比收尾晚退出时）
            token = null;
        }
    }

    /** 排着的那一次作废（已经到点、正等锁的也算）；没排着什么都不做。 */
    void cancel() {
        token = null;
        if (pending != null) pending.cancel(false);
        pending = null;
    }
}
