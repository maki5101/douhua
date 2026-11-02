package dev.douhua;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 主线程 → 混音线程的请求队列：定长、无锁、不分配内存（按键时主线程只往这里写一个 long）。纯 JDK，离线测（SoundTest，含几个线程同时写）。
 *
 * 多个线程写、一个线程读（写的一般只有主线程；万一别的线程也写也不会乱）。写的先用 CAS 抢一个位置，再把数放进去；
 * 0 表示"这一格还没写好 / 已经读走"，所以放进来的数不能是 0（SoundMixer.request 打包时最高位以下总有一位是 1）。
 * 读的看到 0 就停（抢了位置还没写完的那一个下一轮再读），读走一格先清成 0、再推进读指针。满了就丢掉这一个（返回 false），不等。
 */
public final class SoundQueue {
    private final AtomicLongArray slots;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();

    /** capacity 取 2 的幂（不是的话往上取）。 */
    public SoundQueue(int capacity) {
        int n = 1;
        while (n < capacity) n <<= 1;
        slots = new AtomicLongArray(n);
        mask = n - 1;
    }

    /** 放一个数（不能是 0）。满了返回 false。任何线程都能调，不阻塞、不分配。 */
    public boolean offer(long v) {
        if (v == 0) throw new IllegalArgumentException("0 不能放进队列");
        while (true) {
            long t = tail.get();
            if (t - head.get() >= slots.length()) return false;
            if (tail.compareAndSet(t, t + 1)) {
                slots.set((int) (t & mask), v);
                return true;
            }
        }
    }

    /** 取一个；没有（或者下一格还没写好）返回 0。只能一个线程调。 */
    public long poll() {
        long h = head.get();
        if (h == tail.get()) return 0;
        int i = (int) (h & mask);
        long v = slots.get(i);
        if (v == 0) return 0;
        slots.set(i, 0);
        head.set(h + 1);
        return v;
    }

    /** 粗看有没有东西（只给混音线程判断要不要醒着用）。 */
    public boolean isEmpty() {
        return head.get() == tail.get();
    }

    /** 全部丢掉（只能读的那个线程调）。 */
    public void clear() {
        while (poll() != 0) { /* 丢 */ }
    }
}
