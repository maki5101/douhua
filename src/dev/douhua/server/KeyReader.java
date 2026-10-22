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
import java.util.ArrayList;
import java.util.List;

/**
 * 独占并读手机的音量键设备（带电源键的设备从不独占，只旁观），按下 / 松开交给 {@link HardKeyGate}。照 {@link TouchReader} 的样子：
 * {@link #run} 在调用线程里阻塞，每轮 poll 最多 100ms，问一次 keepGoing，返回 false 就放开所有设备并返回。
 *
 * 每块设备开两个文件描述符：plain 从头到尾开着、从不独占（没独占时系统和这里都收得到）；grabFd 只在独占时开着
 * （EVIOCGRAB 只能靠关掉来解除）。交还系统时先把 grabFd 读空再关掉，关掉的那一刻起事件就从 plain 进来。
 * 还有一个漏洞堵不上：最后一次读空到关掉之间（微秒级）到的事件跟着 grabFd 一起丢了（每块设备读空了马上关，窗口尽量小）。
 * 丢的要是松开，系统那边会停在按着的状态（补发过的音量键按下，或者音量键的系统连发），要等下一次按同一个键
 * 或会话结束 releaseAll 才恢复。要堵得按 EVIOCGKEY 查物理状态，现在的 ioctl 通路读不到这些键码，没做。
 *
 * 独占的时机由 gate 定（{@link HardKeyGate#readyToGrab}：所有键都松开、空闲满冷却）。独占前的竞态：
 * 按下要是在独占生效之前送到，系统也看到了按下，松开却会被独占吞掉，系统那边就停不下连发。EVIOCGRAB 返回时
 * 独占前的投递都已经完成（内核 evdev_grab 里有 synchronize_rcu），所以这时把 plain 上没读的事件读完：
 * 有按键动过就马上放开，等松开、冷却完再来。grabFd 上独占前就收到的同一批事件按时间戳丢掉（不晚于 plain 上最后一个的）。
 *
 * 时间：gate 用读到事件时的 SystemClock.uptimeMillis（和 Android 的 downTime 同一套）；内核时间戳只用来去重。
 */
final class KeyReader {
    interface KeepGoing {
        boolean check();
    }

    interface Listener {
        /** 独占状态变了：true 独占上了，false 交还系统 / 放开了。 */
        void exclusive(boolean on);
    }

    /** 64 位内核的 struct input_event：timeval(16) + type(2) + code(2) + value(4)。和 TouchReader 同一个假设。 */
    private static final int EVENT_SIZE = 24;
    private static final int EV_KEY = 1;

    /** 一块设备。 */
    private static final class Dev {
        final KeyDevices.Device info;
        /** 只旁观（认组合键），从不独占。 */
        final boolean watchOnly;
        FileDescriptor plain;
        FileDescriptor grabFd;
        /** plain 上读到的最后一个事件的内核时间（微秒）。 */
        long lastPlainUs = Long.MIN_VALUE;
        /** grabFd 上不晚于它的事件是独占前就送到的重复（plain 上已经读过），丢掉。 */
        long skipUntilUs = Long.MIN_VALUE;

        Dev(KeyDevices.Device info, boolean watchOnly) {
            this.info = info;
            this.watchOnly = watchOnly;
        }
    }

    private final List<Dev> devs = new ArrayList<>();
    private final HardKeyGate gate;
    private final byte[] buf = new byte[EVENT_SIZE * 64];
    private final ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
    private boolean exclusive;

    private KeyReader(HardKeyGate gate) {
        this.gate = gate;
    }

    /**
     * grab：要独占的设备；watch：只旁观的设备（带电源键的；打不开一块只记日志、认不出它上面的电源键和截住的音量键一起按，
     * 一块都打不开就抛 IOException、什么都不截：截了电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）会失灵）。
     * 出错抛 IOException（此时已全部放开）。不管怎么退出，gate 都会 releaseAll：注入过的按下全部配上松开。
     */
    static void run(List<KeyDevices.Device> grab, List<KeyDevices.Device> watch, HardKeyGate gate,
                    KeepGoing keepGoing, Listener listener) throws IOException {
        KeyReader r = new KeyReader(gate);
        try {
            for (KeyDevices.Device d : grab) {
                Dev x = new Dev(d, false);
                x.plain = Evdev.open(d.path);
                r.devs.add(x);
            }
            for (KeyDevices.Device d : watch) {
                Dev x = new Dev(d, true);
                try {
                    x.plain = Evdev.open(d.path);
                    r.devs.add(x);
                } catch (IOException e) {
                    Server.log("旁观实体键设备 " + d.name + " 打不开（认不出它上面的电源键和截住的音量键一起按）：" + e.getMessage());
                }
            }
            boolean watching = false;
            for (Dev x : r.devs) if (x.watchOnly) watching = true;
            if (!watch.isEmpty() && !watching) {
                throw new IOException("旁观的电源键设备都打不开，不截音量键（截了电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）会失灵）");
            }
            r.loop(keepGoing, listener);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        } finally {
            // 先关独占（系统马上收回实体键，补发卡住也不影响），再给注入过的按下补松开，最后关掉旁观用的
            r.closeGrabs();
            if (r.exclusive) {
                r.exclusive = false;
                listener.exclusive(false);
            }
            gate.releaseAll(SystemClock.uptimeMillis());
            for (Dev d : r.devs) {
                if (d.plain != null) Evdev.close(d.plain);
                d.plain = null;
            }
        }
    }

    private void loop(KeepGoing keepGoing, Listener listener) throws IOException, ErrnoException {
        while (keepGoing.check()) {
            long now = SystemClock.uptimeMillis();
            if (!exclusive && gate.readyToGrab(now) && grabAll()) {
                exclusive = true;
                gate.grabbed();
                listener.exclusive(true);
            }
            StructPollfd[] fds = new StructPollfd[devs.size()];
            for (int i = 0; i < fds.length; i++) {
                Dev d = devs.get(i);
                StructPollfd p = new StructPollfd();
                p.fd = source(d);
                p.events = (short) OsConstants.POLLIN;
                fds[i] = p;
            }
            long next = gate.nextDeadline();
            long wait = next == Long.MAX_VALUE ? 100 : Math.max(0, Math.min(100, next - now));
            Os.poll(fds, (int) wait);
            for (int i = 0; i < fds.length; i++) {
                int rev = fds[i].revents;
                if (rev == 0) continue;
                Dev d = devs.get(i);
                if ((rev & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                    throw new IOException("实体键设备 " + d.info.name + " 不见了");
                }
                if ((rev & OsConstants.POLLIN) != 0) readOnce(d, fds[i].fd);
            }
            gate.pump(SystemClock.uptimeMillis());
            if (exclusive && gate.systemOwns()) {   // gate 要交还系统（电源键和音量键一起按）
                releaseGrabs();
                exclusive = false;
                listener.exclusive(false);
            }
        }
    }

    /** 现在从哪个描述符读：独占着的设备读 grabFd（plain 收不到东西），别的读 plain。 */
    private FileDescriptor source(Dev d) {
        return exclusive && !d.watchOnly ? d.grabFd : d.plain;
    }

    /**
     * 把要独占的设备全部独占上。独占前送到的事件（系统也收到了）都在 plain 上：读完，交给 gate（键还在系统手里）。
     * 读完以后 gate 不再"可以独占"（有键按着、刚动过）就是撞上了竞态：马上放开，返回 false，等松开、冷却完再来。
     */
    private boolean grabAll() throws IOException, ErrnoException {
        try {
            for (Dev d : devs) {
                if (d.watchOnly) continue;
                d.grabFd = Evdev.open(d.info.path);
                Evdev.grab(d.grabFd);
            }
        } catch (IOException e) {
            closeGrabs();
            throw new IOException("独占实体键设备失败：" + e.getMessage(), e);
        }
        for (Dev d : devs) {
            if (d.watchOnly) continue;
            drain(d, d.plain);
            d.skipUntilUs = d.lastPlainUs;
        }
        if (gate.readyToGrab(SystemClock.uptimeMillis())) return true;
        // 放开前把 grabFd 上独占之后到的也读完：独占前按下的键（系统看到了按下）要是正好这时松开，
        // 松开只到了这里，gate 会替系统补上（HardKeyGate.unseen）
        drainAndCloseGrabs();
        Server.log("独占实体键时正好有键在动（系统也收到了），先放开，等松开再来");
        return false;
    }

    /** 交还系统：独占期间到的、还没读的先读完（这时 gate 已在系统手里，补发的按下照样配上松开），再关掉 grabFd。 */
    private void releaseGrabs() throws IOException, ErrnoException {
        drainAndCloseGrabs();
    }

    /** 每块设备读空了马上关掉它的 grabFd（不等别的设备读完再统一关）：读空到关掉之间到的事件会丢，窗口越小越好。 */
    private void drainAndCloseGrabs() throws IOException, ErrnoException {
        for (Dev d : devs) {
            if (d.grabFd == null) continue;
            drain(d, d.grabFd);
            Evdev.close(d.grabFd);   // 关掉即释放独占
            d.grabFd = null;
        }
    }

    private void closeGrabs() {
        for (Dev d : devs) {
            if (d.grabFd != null) Evdev.close(d.grabFd);   // 关掉即释放独占
            d.grabFd = null;
        }
    }

    /** 把这个描述符上现在能读的事件全读完（不阻塞）。 */
    private void drain(Dev d, FileDescriptor fd) throws IOException, ErrnoException {
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        StructPollfd[] one = {p};
        while (true) {
            p.revents = 0;
            if (Os.poll(one, 0) <= 0 || (p.revents & OsConstants.POLLIN) == 0) return;
            readOnce(d, fd);
        }
    }

    /** 读一次（poll 说能读了才调，不会阻塞），按键事件交给 gate。 */
    private void readOnce(Dev d, FileDescriptor fd) throws IOException, ErrnoException {
        int n = Os.read(fd, buf, 0, buf.length);
        if (n <= 0) throw new IOException("实体键设备 " + d.info.name + " 读不到数据了");
        boolean fromGrab = fd == d.grabFd;
        long t = SystemClock.uptimeMillis();
        for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
            long us = bb.getLong(off) * 1_000_000L + bb.getLong(off + 8);
            if (!fromGrab) {
                d.lastPlainUs = us;
            } else if (us <= d.skipUntilUs) {
                continue;   // 独占前就送到的，plain 上已经交给过 gate
            }
            int type = bb.getShort(off + 16) & 0xFFFF;
            if (type != EV_KEY) continue;
            int code = bb.getShort(off + 18) & 0xFFFF;
            int value = bb.getInt(off + 20);
            if (d.watchOnly) {
                gate.observe(code, value, t);
            } else if (fromGrab) {
                gate.key(code, value, t);      // 独占着读到的：系统没看到
            } else {
                gate.system(code, value, t);   // 没独占时读到的：系统也收到了
            }
        }
    }
}
