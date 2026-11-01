package dev.douhua.server;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

/**
 * 独占触摸屏并读原始触摸。独占期间系统收不到这块屏的任何触摸——这正是目的：
 * 系统里只剩虚拟鼠标一个输入设备，不会被"同一时刻只认一个设备"的规则（比如无障碍输入过滤器）掐断。
 *
 * {@link #run} 在调用线程里阻塞执行；每 100ms 问一次 keepGoing，返回 false 就释放触摸屏并返回。
 * 坐标用系统的换算矩阵转成屏幕像素后交给监听者。
 */
final class TouchReader {
    interface Listener {
        void onDown(int slot, float x, float y, long t);

        void onMove(int slot, float x, float y);

        void onFrame(long t);

        void onUp(int slot, long t);

        /** 这根手指作废了（中途变成手掌、内核丢了数据），不是正常抬起。 */
        void onCancel(int slot, long t);

        /**
         * 要停了（释放独占、出错），在把还按着的手指按抬起报出来（onUp）之前调：注入回系统的那一路要先发 CANCEL，
         * 不然随后的抬起会被系统当成一次真的点击。
         */
        void onStop(long t);
    }

    interface KeepGoing {
        boolean check();
    }

    /** 64 位内核的 struct input_event：timeval(16) + type(2) + code(2) + value(4)。 */
    private static final int EVENT_SIZE = 24;

    /** {@link #run} 的返回值：正常停下（独占条件不满足、出错以外的）。 */
    static final int STOPPED = 0;
    /** 独占着读到了独占前就按着的手指（{@link MtDecoder#sawPreGrab}），放开了。 */
    static final int PRE_GRAB = 1;
    /** 刚独占上、一个独占事件都没读，就从 plain 上看到独占前按下没抬起的手指，马上放开了（没调 onGrabbed）。 */
    static final int RACED = 2;

    private TouchReader() {}

    /**
     * 独占成功后调用 onGrabbed；出错抛 IOException（此时已释放）。
     *
     * plain / watch（分屏透传时才有，否则 null）：Session 在 dumpsys 之前就不独占地开着同一块触摸屏（{@link LiftWatch}）。
     * EVIOCGRAB 返回时独占前的投递都已完成（内核 evdev_grab 里有 synchronize_rcu），所以独占后先把 plain 读空：
     * 独占前按下、还没抬起的手指（dumpsys 之后到独占之间按下的）就马上放开、返回 {@link #RACED}——独占描述符一个事件都没读，
     * 后面的抬起照常到系统（读空 plain 到关掉独占之间那一点点时间里到的事件会丢，和 KeyReader 一样）。
     * 没有就把独占描述符上不晚于 plain 最后一个事件的丢掉（两边都收到的重复，不能当新手指交给 router），只留 ABS_MT_SLOT 给解码器跟着切。
     * plain 由调用方关。
     *
     * stopOnPreGrab 为真（分屏透传）时，一读到独占前就按着的手指（{@link MtDecoder#sawPreGrab}）就放开、返回 {@link #PRE_GRAB}：
     * 系统那边永远等不到这根手指抬起（EVIOCGRAB 不补抬起），它按在另一边的应用上，那个应用就一直收着一个按住的触摸；
     * 放开后它的后续事件（含抬起）照常到系统。为假时（全屏）认出过也清掉，不留到切分屏以后。正常停下返回 {@link #STOPPED}。
     */
    static int run(TouchDevice dev, FileDescriptor plain, LiftWatch watch, Listener listener, KeepGoing keepGoing,
                   BooleanSupplier stopOnPreGrab, Runnable onGrabbed) throws IOException {
        FileDescriptor fd = Evdev.open(dev.path);
        MtDecoder decoder = new MtDecoder(new MtDecoder.Listener() {
            private long frameTime;

            @Override
            public void down(int slot, int rawX, int rawY) {
                frameTime = SystemClock.uptimeMillis();
                listener.onDown(slot, dev.mapX(rawX, rawY), dev.mapY(rawX, rawY), frameTime);
            }

            @Override
            public void move(int slot, int rawX, int rawY) {
                listener.onMove(slot, dev.mapX(rawX, rawY), dev.mapY(rawX, rawY));
            }

            @Override
            public void frame() {
                frameTime = SystemClock.uptimeMillis();
                listener.onFrame(frameTime);
            }

            @Override
            public void up(int slot) {
                listener.onUp(slot, SystemClock.uptimeMillis());
            }

            @Override
            public void cancel(int slot) {
                listener.onCancel(slot, SystemClock.uptimeMillis());
            }
        });
        try {
            Evdev.grab(fd);
            long skipUntilUs = Long.MIN_VALUE;
            if (plain != null) {
                drain(plain, watch);
                if (watch.anyDown()) return RACED;
                skipUntilUs = watch.lastEventUs();
                decoder.setSlot(watch.slot());
            }
            onGrabbed.run();
            byte[] buf = new byte[EVENT_SIZE * 64];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
            StructPollfd pfd = new StructPollfd();
            pfd.fd = fd;
            pfd.events = (short) OsConstants.POLLIN;
            StructPollfd[] fds = {pfd};
            while (keepGoing.check()) {
                pfd.revents = 0;
                if (Os.poll(fds, 100) <= 0 || (pfd.revents & OsConstants.POLLIN) == 0) continue;
                int n = Os.read(fd, buf, 0, buf.length);
                if (n <= 0) throw new IOException("触摸屏读不到数据了");
                for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
                    int type = bb.getShort(off + 16) & 0xFFFF;
                    int code = bb.getShort(off + 18) & 0xFFFF;
                    int value = bb.getInt(off + 20);
                    if (skipUntilUs != Long.MIN_VALUE && timeUs(bb, off) <= skipUntilUs) {
                        // 独占前就送到、plain 上已经看过的：只跟着切 slot
                        if (type == MtDecoder.EV_ABS && code == MtDecoder.ABS_MT_SLOT) decoder.feed(type, code, value);
                        continue;
                    }
                    decoder.feed(type, code, value);
                }
                if (decoder.sawPreGrab()) {
                    if (stopOnPreGrab.getAsBoolean()) {
                        if (decoder.sawPreGrabLift()) {
                            Server.log("独占前就按着的手指，独占后第一个事件就是抬起：这次抬起系统收不到了，"
                                    + "系统可能留着一根按着的手指（同一个 slot 下次按下时系统才补上抬起）");
                        }
                        return PRE_GRAB;
                    }
                    decoder.clearSawPreGrab();   // 全屏时认出过的旧手指不留到切分屏以后
                }
            }
            return STOPPED;
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        } finally {
            try {
                listener.onStop(SystemClock.uptimeMillis());   // 先取消注入回系统的手指，再按抬起收尾
                decoder.releaseAll();
            } finally {
                Evdev.close(fd);   // 关掉即释放独占
            }
        }
    }

    /** 不独占地开着的触摸屏（plain）上现在能读的全读完、喂给 watch（不阻塞）。 */
    static void drain(FileDescriptor plain, LiftWatch watch) throws IOException {
        watchFor(plain, watch, 0, () -> false);
    }

    /**
     * 不独占地读 plain、喂给 watch，最多 ms 毫秒（每轮 poll ≤ 100ms），until 为真就提前返回（每读一批、每轮都问）。
     * ms = 0：只读现在能读的。设备不见了抛 IOException。
     */
    static void watchFor(FileDescriptor plain, LiftWatch watch, long ms, BooleanSupplier until) throws IOException {
        byte[] buf = new byte[EVENT_SIZE * 64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        StructPollfd pfd = new StructPollfd();
        pfd.fd = plain;
        pfd.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = {pfd};
        long end = SystemClock.uptimeMillis() + ms;
        try {
            while (true) {
                long left = end - SystemClock.uptimeMillis();
                pfd.revents = 0;
                int r = Os.poll(fds, (int) Math.max(0, Math.min(100, left)));
                if ((pfd.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                    throw new IOException("触摸屏不见了");
                }
                if (r > 0 && (pfd.revents & OsConstants.POLLIN) != 0) {
                    int n = Os.read(plain, buf, 0, buf.length);
                    if (n <= 0) throw new IOException("触摸屏读不到数据了");
                    long now = SystemClock.uptimeMillis();
                    for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
                        watch.feed(bb.getShort(off + 16) & 0xFFFF, bb.getShort(off + 18) & 0xFFFF, bb.getInt(off + 20),
                                timeUs(bb, off), now);
                    }
                    if (until.getAsBoolean()) return;
                    if (ms > 0 && SystemClock.uptimeMillis() >= end) return;
                    continue;   // ms = 0 时读到空为止（读空了 poll 返回 0）
                }
                if (until.getAsBoolean() || left <= 0) return;
            }
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /** 事件的内核时间（微秒）：timeval 的秒和微秒。 */
    private static long timeUs(ByteBuffer bb, int off) {
        return bb.getLong(off) * 1_000_000L + bb.getLong(off + 8);
    }
}
