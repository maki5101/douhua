package dev.douhua.server;

import java.io.Closeable;
import java.io.IOException;

import dev.douhua.Hid;
import dev.douhua.Proto;

/**
 * 虚拟 USB 手柄（"Douhua Gamepad"，0x1209 / 0xD0A2，协议 19 起）：这台手机的 Android 把它当普通手柄（Generic.kl），
 * Moonlight 认了转给电脑上的 Sunshine，Sunshine 给游戏造一个 Xbox 手柄。描述符见 {@link Hid#GAMEPAD_DESC}，状态由
 * {@link dev.douhua.GamepadTouch}（服务的分流）和 App 本地分流两路合并（{@link GamepadMux}），这里只管发报文。
 * 单独一个设备、不并进鼠标：安卓 InputReader 把厂商 / 产品 / 总线 / 版本都一样的节点并成一个 InputDevice，并进去鼠标也成了"手柄"。
 * 没有 Feature 报告，不要读线程。第一次用到（手柄页第一次报区域）才建，留到会话结束；关之前先写一帧全零。
 */
final class UhidGamepad implements Closeable {
    private final Uhid dev;
    private boolean closed;

    UhidGamepad(String name) throws IOException {
        dev = new Uhid(name, Proto.HID_GAMEPAD_PRODUCT, Hid.GAMEPAD_DESC, Hid.GAMEPAD_REPORT_SIZE);
    }

    synchronized void send(byte[] report) throws IOException {
        dev.send(report, Hid.GAMEPAD_REPORT_SIZE);
    }

    /** 先写一帧全零（键全松、摇杆回中），再关（设备随之消失）。幂等。 */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            try {
                dev.send(new byte[Hid.GAMEPAD_REPORT_SIZE], Hid.GAMEPAD_REPORT_SIZE);
            } catch (IOException ignored) {
                // 写不进去也照样关：关掉 fd 设备就消失了
            }
        }
        dev.close();
    }
}
