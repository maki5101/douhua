package dev.douhua.server;

import java.io.Closeable;
import java.io.IOException;

import dev.douhua.Hid;

/**
 * 虚拟 USB 键盘：DeX 把它当真键盘，打字、快捷键、长按连发、外接键盘下的输入法都是原生的。
 * 描述符见 {@link Hid#KEYBOARD_DESC}；按着哪些键由 {@link dev.douhua.TouchRouter} 记，这里只管发报文。
 */
final class UhidKeyboard implements Closeable {
    private final Uhid dev;

    UhidKeyboard(String name) throws IOException {
        dev = new Uhid(name, 0xD0A1, Hid.KEYBOARD_DESC, Hid.KEYBOARD_REPORT_SIZE);
    }

    synchronized void send(byte[] report) throws IOException {
        dev.send(report, Hid.KEYBOARD_REPORT_SIZE);
    }

    @Override
    public void close() {
        dev.close();
    }
}
