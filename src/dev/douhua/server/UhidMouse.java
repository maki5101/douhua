package dev.douhua.server;

import java.io.Closeable;
import java.io.IOException;

/**
 * 虚拟 USB 鼠标：DeX 上的光标、悬停、右键菜单、滚轮都是原生的。
 */
final class UhidMouse implements Closeable {
    private static final int REPORT_SIZE = 7;

    /** 5 个按键 + 16 位相对 X/Y + 竖向滚轮 + 横向滚轮（AC Pan）。 */
    private static final byte[] REPORT_DESC = {
            0x05, 0x01,                    // Usage Page (Generic Desktop)
            0x09, 0x02,                    // Usage (Mouse)
            (byte) 0xA1, 0x01,             // Collection (Application)
            0x09, 0x01,                    //   Usage (Pointer)
            (byte) 0xA1, 0x00,             //   Collection (Physical)
            0x05, 0x09,                    //     Usage Page (Button)
            0x19, 0x01,                    //     Usage Minimum (1)
            0x29, 0x05,                    //     Usage Maximum (5)
            0x15, 0x00,                    //     Logical Minimum (0)
            0x25, 0x01,                    //     Logical Maximum (1)
            (byte) 0x95, 0x05,             //     Report Count (5)
            0x75, 0x01,                    //     Report Size (1)
            (byte) 0x81, 0x02,             //     Input (Data,Var,Abs)
            (byte) 0x95, 0x01,             //     Report Count (1)
            0x75, 0x03,                    //     Report Size (3)
            (byte) 0x81, 0x01,             //     Input (Const) 补齐到一个字节
            0x05, 0x01,                    //     Usage Page (Generic Desktop)
            0x09, 0x30,                    //     Usage (X)
            0x09, 0x31,                    //     Usage (Y)
            0x16, 0x01, (byte) 0x80,       //     Logical Minimum (-32767)
            0x26, (byte) 0xFF, 0x7F,       //     Logical Maximum (32767)
            0x75, 0x10,                    //     Report Size (16)
            (byte) 0x95, 0x02,             //     Report Count (2)
            (byte) 0x81, 0x06,             //     Input (Data,Var,Rel)
            0x09, 0x38,                    //     Usage (Wheel)
            0x15, (byte) 0x81,             //     Logical Minimum (-127)
            0x25, 0x7F,                    //     Logical Maximum (127)
            0x75, 0x08,                    //     Report Size (8)
            (byte) 0x95, 0x01,             //     Report Count (1)
            (byte) 0x81, 0x06,             //     Input (Data,Var,Rel)
            0x05, 0x0C,                    //     Usage Page (Consumer)
            0x0A, 0x38, 0x02,              //     Usage (AC Pan)
            0x15, (byte) 0x81,             //     Logical Minimum (-127)
            0x25, 0x7F,                    //     Logical Maximum (127)
            0x75, 0x08,                    //     Report Size (8)
            (byte) 0x95, 0x01,             //     Report Count (1)
            (byte) 0x81, 0x06,             //     Input (Data,Var,Rel)
            (byte) 0xC0,                   //   End Collection
            (byte) 0xC0,                   // End Collection
    };

    private final Uhid dev;
    private final byte[] report = new byte[REPORT_SIZE];

    UhidMouse(String name) throws IOException {
        dev = new Uhid(name, 0xD0A0, REPORT_DESC, REPORT_SIZE);
    }

    /** 发一帧鼠标报文。buttons 是当前按下状态；其余是相对量，超范围的截断。 */
    synchronized void send(int buttons, int dx, int dy, int wheel, int pan) throws IOException {
        dx = clamp(dx, 32767);
        dy = clamp(dy, 32767);
        report[0] = (byte) (buttons & 0x1F);
        report[1] = (byte) dx;          // HID 报文里的多字节字段固定小端
        report[2] = (byte) (dx >> 8);
        report[3] = (byte) dy;
        report[4] = (byte) (dy >> 8);
        report[5] = (byte) clamp(wheel, 127);
        report[6] = (byte) clamp(pan, 127);
        dev.send(report, REPORT_SIZE);
    }

    @Override
    public void close() {
        dev.close();
    }

    private static int clamp(int v, int limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
