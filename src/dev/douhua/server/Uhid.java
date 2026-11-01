package dev.douhua.server;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 通过 /dev/uhid 造一个 USB HID 设备。系统把它当真设备处理（鼠标、键盘都一样）。
 * 每打开一次 /dev/uhid 就是一个设备；关掉文件描述符，设备随之消失（按住的键也就松开了）。
 * 结构体布局见 Linux include/uapi/linux/uhid.h。
 *
 * 带 {@link Requests} 的（虚拟鼠标：描述符里有滚轮的分辨率倍率）另开一个读线程（douhua-uhid），回应内核的 GET / SET_REPORT
 * （布局见 {@link UhidEvents}）：内核在添加设备时（hidinput_connect）来设倍率，收不到回复要干等 5 秒才退回 ×1，
 * 虚拟鼠标就晚 5 秒出现。别的事件（START / OPEN / CLOSE / OUTPUT）读掉不管。
 * 关闭顺序：先让读线程退出、再关 fd（{@link #close}）——fd 号会被马上复用（新会话紧接着开新的 uhid），
 * 晚一步 poll 的读线程会读到别的设备的请求、把它的倍率记错。
 */
final class Uhid implements Closeable {
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final short BUS_USB = 0x03;
    private static final int VENDOR = dev.douhua.Proto.HID_VENDOR;
    /** 关闭时最多等读线程这么久（它每轮 poll ≤ 100ms，正常一轮就退出）。 */
    private static final long READER_EXIT_WAIT_MS = 300;
    /** 建好这么久内核还没来设 Feature 报告：记一句（描述符被拒、或者内核不认倍率，照旧一格一格）。 */
    private static final long NO_SET_LOG_MS = 5000;

    /** 内核的 Feature 报告请求（读线程里调）。 */
    interface Requests {
        /** 内核来问 Feature 报告 rnum 的当前值：返回报告内容（不带编号），null = 没有这个报告。 */
        byte[] getFeature(int rnum);

        /** 内核来设 Feature 报告：返回收下没有。 */
        boolean setFeature(int rnum, byte[] data);
    }

    private final String name;
    private final FileDescriptor fd;
    private final ByteBuffer input;
    private final Requests requests;
    /** 关过了：再关什么都不做，再发就报错（文件描述符号会被复用，不能把报文写进别的文件）。 */
    private boolean closed;
    /** fd 已经关了（close 或读线程退出时关，只关一次）。 */
    private boolean fdClosed;
    private Thread reader;

    Uhid(String name, int product, byte[] reportDescriptor, int reportSize) throws IOException {
        this(name, product, reportDescriptor, reportSize, null);
    }

    /** requests 不为 null 就开读线程回应内核的 Feature 请求（描述符里有 Feature 报告时必须有，见类注释）。 */
    Uhid(String name, int product, byte[] reportDescriptor, int reportSize, Requests requests) throws IOException {
        this.name = name;
        this.requests = requests;
        input = ByteBuffer.allocate(4 + 2 + reportSize).order(ByteOrder.nativeOrder());
        try {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
        try {
            byte[] req = create2(name, product, reportDescriptor);
            Os.write(fd, req, 0, req.length);
        } catch (ErrnoException e) {
            close();
            throw e.rethrowAsIOException();
        } catch (IOException e) {
            close();
            throw e;
        }
        if (requests != null) {
            // 内核在 worker 里添加设备、马上就来要 Feature 报告：写完 CREATE2 立刻开始读
            Thread t = new Thread(this::readLoop, "douhua-uhid");
            t.setDaemon(true);
            synchronized (this) {
                reader = t;
            }
            t.start();
        }
    }

    /** 发一帧输入报文（HID 报文里的多字节字段由调用方按小端填好）。 */
    synchronized void send(byte[] report, int length) throws IOException {
        if (closed) throw new IOException("虚拟设备已移除");
        input.clear();
        input.putInt(UHID_INPUT2);
        input.putShort((short) length);
        input.put(report, 0, length);
        try {
            Os.write(fd, input.array(), 0, input.position());
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * 幂等，和 send 互斥：被顶掉的旧会话收不了尾时，新会话会替它关。
     * 有读线程时先等它退出再关 fd（不持锁等：它回复内核时要拿这把锁）；万一 300ms 还没退出，fd 留给它退出时自己关，
     * 绝不在它还可能 poll 的时候关（关了 fd 号马上被复用）。
     */
    @Override
    public void close() {
        Thread r;
        synchronized (this) {
            if (closed) return;
            closed = true;
            r = reader;
        }
        if (r != null && r != Thread.currentThread()) {
            try {
                r.join(READER_EXIT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (r != null && r.isAlive() && r != Thread.currentThread()) {
                Server.log("虚拟设备 " + name + " 的读线程 " + READER_EXIT_WAIT_MS + "ms 没退出：等它退出时再关设备");
                return;
            }
            closeFd();
        }
    }

    /** 只在持锁时调。 */
    private void closeFd() {
        if (fdClosed) return;
        fdClosed = true;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // 关闭失败也没什么可补救的，设备会在进程退出时消失
        }
    }

    /** 读线程：poll 100ms 一轮，每轮看一眼关没关；回应 GET / SET_REPORT，别的读掉。出错就退出（设备照常，倍率留在当时的值）。 */
    private void readLoop() {
        byte[] buf = new byte[UhidEvents.EVENT_MAX];
        StructPollfd pfd = new StructPollfd();
        pfd.fd = fd;
        pfd.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = {pfd};
        long created = SystemClock.uptimeMillis();
        boolean setSeen = false;
        boolean noSetLogged = false;
        int otherLogged = 0;   // START / OPEN 这类只记前几个
        try {
            while (!isClosed()) {
                pfd.revents = 0;
                int n = Os.poll(fds, 100);
                if (isClosed()) break;
                if (!setSeen && !noSetLogged && SystemClock.uptimeMillis() - created >= NO_SET_LOG_MS) {
                    noSetLogged = true;
                    Server.log("虚拟设备 " + name + " 建好 " + NO_SET_LOG_MS / 1000 + " 秒内核没来设 Feature 报告（滚轮倍率）：照旧一格一格");
                }
                if (n <= 0) continue;
                if ((pfd.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                    throw new IOException("设备出错（revents=" + pfd.revents + "）");
                }
                if ((pfd.revents & OsConstants.POLLIN) == 0) continue;
                int len = Os.read(fd, buf, 0, buf.length);
                UhidEvents.Request q = UhidEvents.parse(buf, len, ByteOrder.nativeOrder());
                long at = SystemClock.uptimeMillis() - created;
                if (q == null) {
                    Server.log("虚拟设备 " + name + " 读到认不出的事件（" + len + " 字节），+" + at + "ms");
                    continue;
                }
                byte[] reply = null;
                if (q.type == UhidEvents.GET_REPORT) {
                    byte[] data = q.rtype == UhidEvents.FEATURE_REPORT ? requests.getFeature(q.rnum) : null;
                    reply = UhidEvents.getReportReply(q.id, data != null ? 0 : UhidEvents.EIO, data, ByteOrder.nativeOrder());
                    // 诊断（2026-11-01 桌面模式下内核一直没来设倍率）：内核问了什么、回了什么都记，弄清是没问还是回得不对
                    Server.log("虚拟设备 " + name + " 内核来问报告（编号 " + q.rnum + "，类型 " + q.rtype + "，请求 " + q.id
                            + "），+" + at + "ms → 回 " + (data != null ? UhidEvents.hex(data) : "EIO"));
                } else if (q.type == UhidEvents.SET_REPORT) {
                    boolean ok = q.rtype == UhidEvents.FEATURE_REPORT && requests.setFeature(q.rnum, q.data);
                    if (ok) setSeen = true;
                    reply = UhidEvents.setReportReply(q.id, ok ? 0 : UhidEvents.EIO, ByteOrder.nativeOrder());
                    Server.log("虚拟设备 " + name + " 内核来设报告（编号 " + q.rnum + "，类型 " + q.rtype + "，数据 " + UhidEvents.hex(q.data)
                            + "），+" + at + "ms → " + (ok ? "收下" : "EIO"));
                } else if (otherLogged < 8) {
                    otherLogged++;
                    Server.log("虚拟设备 " + name + " 内核发来 " + UhidEvents.typeName(q.type) + "，+" + at + "ms");
                }
                if (reply != null) reply(reply);
            }
        } catch (ErrnoException | IOException e) {
            if (!isClosed()) Server.log("读虚拟设备 " + name + " 出错，不再回应内核的 Feature 请求：" + e.getMessage());
        } finally {
            synchronized (this) {
                if (closed) closeFd();   // close() 等不及先走了：fd 由这里关
            }
        }
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private synchronized void reply(byte[] event) throws ErrnoException, java.io.InterruptedIOException {
        if (closed) return;
        Os.write(fd, event, 0, event.length);
    }

    private static byte[] create2(String name, int product, byte[] desc) {
        // name[128] phys[64] uniq[64] rd_size:u16 bus:u16 vendor:u32 product:u32 version:u32 country:u32 rd_data[]
        ByteBuffer buf = ByteBuffer.allocate(4 + 256 + 2 + 2 + 16 + desc.length).order(ByteOrder.nativeOrder());
        buf.putInt(UHID_CREATE2);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        buf.put(nameBytes, 0, Math.min(nameBytes.length, 127));
        buf.position(4 + 256);
        buf.putShort((short) desc.length);
        buf.putShort(BUS_USB);
        buf.putInt(VENDOR);
        buf.putInt(product);
        buf.putInt(1);   // version
        buf.putInt(0);   // country
        buf.put(desc);
        return buf.array();
    }
}
