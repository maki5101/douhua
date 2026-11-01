package dev.douhua.server;

import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;

import dev.douhua.Hid;

/**
 * 虚拟 USB 鼠标：系统里的光标、悬停、右键菜单、滚轮都是原生的。描述符和报文格式见 {@link Hid}。
 * 描述符一直带滚轮的分辨率倍率（{@link Hid#MOUSE_DESC_HIRES}，输入报文和以前一个字节都不差）：内核添加设备时来设倍率，
 * 由 {@link Uhid} 的读线程交到这里（{@link #setFeature}）；之后滚轮按倍率换算（{@link Hid.WheelOut}）。
 * 内核的 hid-input 把所有滚轮都报成 REL_WHEEL_HI_RES，倍率 ×8 时连续滚动就是平滑的；Android 那一侧认不认高精度滚轮
 * 看系统开关（high_resolution_scroll），不认就只读内核攒满一格的 REL_WHEEL，照旧一格一格。
 */
final class UhidMouse implements Closeable, Uhid.Requests {
    private static final int REPORT_SIZE = Hid.MOUSE_REPORT_SIZE;

    private final byte[] report = new byte[REPORT_SIZE];
    /** 滚轮单位 → 计数（倍率由读线程设）。在构造里先建好：读线程在 Uhid 构造里就开始跑了。 */
    private final Hid.WheelOut wheel = new Hid.WheelOut();
    private final Uhid dev;

    UhidMouse(String name) throws IOException {
        dev = new Uhid(name, 0xD0A0, Hid.MOUSE_DESC_HIRES, REPORT_SIZE, this);
    }

    /** 发一帧鼠标报文。buttons 是当前按下状态；dx / dy 是相对计数（超范围的截断），wheel / pan 是 1/120 格（按倍率换算、超了拆帧）。 */
    synchronized void send(int buttons, int dx, int dy, int wheelUnits, int panUnits) throws IOException {
        wheel.mouse(report, buttons, dx, dy, wheelUnits, panUnits, SystemClock.uptimeMillis(), r -> dev.send(r, REPORT_SIZE));
    }

    /** 读线程里调：内核问当前的倍率（Linux 同一个报告里不止一个字段时先 GET 再 SET）。 */
    @Override
    public synchronized byte[] getFeature(int rnum) {
        return new byte[]{(byte) wheel.featureByte()};
    }

    /** 读线程里调：内核设了倍率（hidinput_connect 设成逻辑最大 = ×8；设备复位后会重设）。 */
    @Override
    public synchronized boolean setFeature(int rnum, byte[] data) {
        int b = Hid.featureByte(data, -1);
        if (b < 0) return false;
        wheel.setMultipliers(Hid.multiplierV(b), Hid.multiplierH(b));
        Server.log("内核设了滚轮倍率：竖 ×" + wheel.multV() + " 横 ×" + wheel.multH());
        return true;
    }

    @Override
    public void close() {
        dev.close();
    }
}
