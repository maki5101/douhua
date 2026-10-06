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
    }

    interface KeepGoing {
        boolean check();
    }

    /** 64 位内核的 struct input_event：timeval(16) + type(2) + code(2) + value(4)。 */
    private static final int EVENT_SIZE = 24;

    private TouchReader() {}

    /** 独占成功后调用 onGrabbed；出错抛 IOException（此时已释放）。 */
    static void run(TouchDevice dev, Listener listener, KeepGoing keepGoing, Runnable onGrabbed) throws IOException {
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
        });
        try {
            Evdev.grab(fd);
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
                    decoder.feed(type, code, value);
                }
            }
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        } finally {
            decoder.releaseAll();
            Evdev.close(fd);   // 关掉即释放独占
        }
    }
}
