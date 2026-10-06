package dev.douhua.server;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

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
 */
final class Uhid implements Closeable {
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;
    private static final short BUS_USB = 0x03;
    /** pid.codes 的开源项目厂商号。 */
    private static final int VENDOR = 0x1209;

    private final FileDescriptor fd;
    private final ByteBuffer input;

    Uhid(String name, int product, byte[] reportDescriptor, int reportSize) throws IOException {
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
    }

    /** 发一帧输入报文（HID 报文里的多字节字段由调用方按小端填好）。 */
    synchronized void send(byte[] report, int length) throws IOException {
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

    @Override
    public void close() {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // 关闭失败也没什么可补救的，设备会在进程退出时消失
        }
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
