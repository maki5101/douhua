package dev.douhua.server;

import java.io.Closeable;
import java.io.IOException;

import dev.douhua.Hid;
import dev.douhua.Proto;

/**
 * 虚拟 USB 键盘的第二个接口（"Douhua Keyboard Controls"，0x1209 / 0xD0A3，协议 24 起）：多媒体键（报告 3）+ 系统键那一组（报告 6），
 * 实体键盘 F1–F12 的第二功能（音量、亮度、媒体、睡眠、勿扰、麦克风静音……）从这里发。描述符就是蓝牙那两段（{@link Hid#controlsDescriptor}），
 * 字节一模一样。照真 USB 键盘"一个打字接口 + 一个带报告编号的接口放多媒体 / 系统键"（QMK 就这么分）：现有的虚拟键盘（{@link UhidKeyboard}）
 * 一个字节都不用动，每次连接重新造，不存在"主机记着旧描述符"的问题。
 * 和虚拟键盘同建同关（真键盘一插上两个接口都在）；按着哪个码由 {@link dev.douhua.TouchRouter} 的两个槽记，这里只管发报文。
 * 没有 Feature 报告，不要读线程。关之前先写两帧 0（多媒体、系统键都松开）。
 */
final class UhidControls implements Closeable {
    private final Uhid dev;
    private final byte[] report = new byte[Hid.CONTROLS_REPORT_SIZE];
    private boolean closed;

    UhidControls(String name) throws IOException {
        dev = new Uhid(name, Proto.HID_CONTROLS_PRODUCT, Hid.controlsDescriptor(), Hid.CONTROLS_REPORT_SIZE);
    }

    /** 多媒体页现在按着的码（0 = 松开）。 */
    synchronized void sendConsumer(int usage) throws IOException {
        send(Hid.REPORT_CONSUMER, usage);
    }

    /** 系统键那一组现在按着的码（0 = 松开）。 */
    synchronized void sendSystem(int usage) throws IOException {
        send(Hid.REPORT_SYSTEM, usage);
    }

    private void send(int reportId, int usage) throws IOException {
        Hid.controlsReport(report, reportId, usage);
        dev.send(report, Hid.CONTROLS_REPORT_SIZE);
    }

    /** 先写两帧 0（多媒体、系统键都松开），再关（设备随之消失）。幂等。 */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            try {
                send(Hid.REPORT_CONSUMER, 0);
                send(Hid.REPORT_SYSTEM, 0);
            } catch (IOException ignored) {
                // 写不进去也照样关：关掉 fd 设备就消失了
            }
        }
        dev.close();
    }
}
