package dev.douhua.server;

import java.io.Closeable;
import java.io.IOException;

import dev.douhua.Hid;

/**
 * 虚拟 USB 鼠标：DeX 上的光标、悬停、右键菜单、滚轮都是原生的。描述符和报文格式见 {@link Hid}。
 */
final class UhidMouse implements Closeable {
    private static final int REPORT_SIZE = Hid.MOUSE_REPORT_SIZE;

    private final Uhid dev;
    private final byte[] report = new byte[REPORT_SIZE];

    UhidMouse(String name) throws IOException {
        dev = new Uhid(name, 0xD0A0, Hid.MOUSE_DESC, REPORT_SIZE);
    }

    /** 发一帧鼠标报文。buttons 是当前按下状态；其余是相对量，超范围的截断。 */
    synchronized void send(int buttons, int dx, int dy, int wheel, int pan) throws IOException {
        Hid.mouseReport(report, buttons, dx, dy, wheel, pan);
        dev.send(report, REPORT_SIZE);
    }

    @Override
    public void close() {
        dev.close();
    }
}
