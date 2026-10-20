package dev.douhua.server;

/**
 * 全服务一把锁：同一时刻只有一个会话能独占触摸屏。持有范围从打开触摸屏之前一直到文件描述符关掉之后。
 *
 * 以前靠"同一时间只服务一个连接"来保证，现在新连接能顶掉不说话的旧连接，新旧会话会短暂并存：
 * 新会话的监管线程拿不到锁就一片一片地等（每 100ms 再看一眼还要不要独占），旧会话放手后接上。
 * 锁要是出了 bug 还有内核兜底：第二次 EVIOCGRAB 返回 EBUSY，绝不会两边都独占成功。
 * 纯 Java，能离线测。
 */
final class TouchGrabLock {
    interface Wanted {
        boolean check();
    }

    /** 等锁时多久再看一眼 wanted。 */
    static final long SLICE_MS = 100;

    private Object owner;

    /**
     * 拿锁：拿到返回 true；还没拿到就不再需要独占了（wanted 为假）、或者线程被中断，返回 false。
     * wanted 在锁外检查（它会去拿会话自己的锁，别和这把锁套在一起）。同一个 owner 重复拿直接成功。
     */
    boolean acquire(Object who, Wanted wanted) {
        while (true) {
            if (!wanted.check()) return false;
            synchronized (this) {
                if (owner == null || owner == who) {
                    owner = who;
                    return true;
                }
                try {
                    wait(SLICE_MS);   // 放锁时 notifyAll 会提前叫醒
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** 放锁：只有持有者调用才生效。 */
    synchronized void release(Object who) {
        if (owner != who) return;
        owner = null;
        notifyAll();
    }

    synchronized boolean heldBy(Object who) {
        return owner == who;
    }
}
