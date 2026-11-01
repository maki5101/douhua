package dev.douhua.server;

import java.util.Arrays;

import dev.douhua.Hid;

/**
 * 桌面模式下 uhid 手柄的两路（纯 Java，离线测）：服务自己分流出的（触摸屏独占着时，TouchRouter → Session.gamepad）和
 * App 本地分流发来的（服务连着没接管时，Proto.CMD_GAMEPAD）。写给 uhid 的是合并结果（{@link Hid.Gamepad#merge}：键按位或、
 * 方向键取不回中的那路（都不回中取服务那路）、每个摇杆取离中心远的那路、扳机取大的），所以两路谁先谁后都不会把对方刚按的松掉。
 * 服务开始接管时自己把 App 那路清零（{@link #clearApp}）：App 的 onGrab 也会补一帧全零，但可能晚到。
 * 不加锁：Session 在 lock 里调。
 */
final class GamepadMux {
    private final byte[] router = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private final byte[] app = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private final byte[] merged = new byte[Hid.GAMEPAD_REPORT_SIZE];
    /** 上次交出去写 uhid 的（设备刚建好时是全零）。 */
    private final byte[] written = new byte[Hid.GAMEPAD_REPORT_SIZE];

    /** 服务那路的新报文：返回要写的合并结果，和上次写的一样返回 null。 */
    byte[] fromRouter(byte[] report) {
        System.arraycopy(report, 0, router, 0, router.length);
        return update();
    }

    /** App 那路的新报文（CMD_GAMEPAD）。 */
    byte[] fromApp(byte[] report) {
        System.arraycopy(report, 0, app, 0, app.length);
        return update();
    }

    /** 服务开始接管：App 那路清零（它这时已经收不到触摸了）。 */
    byte[] clearApp() {
        Arrays.fill(app, (byte) 0);
        return update();
    }

    /**
     * 设备刚建好（建好之前写不进去）：返回要补写的现在的合并结果，同时记成"上次写的"——不然补写了按着、written 还是全零，
     * 之后回到全零时 update 当没变、松开写不出去，电脑上一直按着。现在的合并结果是全零（设备刚建好本来就是全零）返回 null。
     */
    byte[] deviceCreated() {
        Hid.Gamepad.merge(router, app, merged);
        System.arraycopy(merged, 0, written, 0, written.length);
        return Hid.Gamepad.neutral(merged) ? null : merged;
    }

    private byte[] update() {
        Hid.Gamepad.merge(router, app, merged);
        if (Arrays.equals(merged, written)) return null;
        System.arraycopy(merged, 0, written, 0, written.length);
        return merged;
    }
}
