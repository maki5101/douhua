package dev.douhua.server;

import java.io.Closeable;
import java.io.IOException;

/**
 * 虚拟 USB 键盘：DeX 把它当真键盘，打字、快捷键、长按连发、外接键盘下的输入法都是原生的。
 * 按键用 HID 用法码（Usage Page 0x07），修饰键是报文第一个字节的位。
 */
final class UhidKeyboard implements Closeable {
    static final int MOD_CTRL = 0x01;
    static final int MOD_SHIFT = 0x02;
    static final int MOD_ALT = 0x04;
    static final int MOD_META = 0x08;

    private static final int REPORT_SIZE = 8;
    private static final int MAX_KEYS = 6;

    /** 标准 boot 键盘：8 个修饰键位 + 保留字节 + 5 个指示灯（输出）+ 6 个同时按下的键。 */
    private static final byte[] REPORT_DESC = {
            0x05, 0x01,                    // Usage Page (Generic Desktop)
            0x09, 0x06,                    // Usage (Keyboard)
            (byte) 0xA1, 0x01,             // Collection (Application)
            0x05, 0x07,                    //   Usage Page (Keyboard)
            0x19, (byte) 0xE0,             //   Usage Minimum (Left Control)
            0x29, (byte) 0xE7,             //   Usage Maximum (Right GUI)
            0x15, 0x00,                    //   Logical Minimum (0)
            0x25, 0x01,                    //   Logical Maximum (1)
            0x75, 0x01,                    //   Report Size (1)
            (byte) 0x95, 0x08,             //   Report Count (8)
            (byte) 0x81, 0x02,             //   Input (Data,Var,Abs) 修饰键
            (byte) 0x95, 0x01,             //   Report Count (1)
            0x75, 0x08,                    //   Report Size (8)
            (byte) 0x81, 0x01,             //   Input (Const) 保留
            (byte) 0x95, 0x05,             //   Report Count (5)
            0x75, 0x01,                    //   Report Size (1)
            0x05, 0x08,                    //   Usage Page (LEDs)
            0x19, 0x01,                    //   Usage Minimum (Num Lock)
            0x29, 0x05,                    //   Usage Maximum (Kana)
            (byte) 0x91, 0x02,             //   Output (Data,Var,Abs) 指示灯
            (byte) 0x95, 0x01,             //   Report Count (1)
            0x75, 0x03,                    //   Report Size (3)
            (byte) 0x91, 0x01,             //   Output (Const) 补齐
            (byte) 0x95, 0x06,             //   Report Count (6)
            0x75, 0x08,                    //   Report Size (8)
            0x15, 0x00,                    //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x00,       //   Logical Maximum (255)
            0x05, 0x07,                    //   Usage Page (Keyboard)
            0x19, 0x00,                    //   Usage Minimum (0)
            0x2A, (byte) 0xFF, 0x00,       //   Usage Maximum (255)
            (byte) 0x81, 0x00,             //   Input (Data,Array,Abs) 按下的键
            (byte) 0xC0,                   // End Collection
    };

    private final Uhid dev;
    private final byte[] report = new byte[REPORT_SIZE];
    private final int[] keys = new int[MAX_KEYS];
    private int modifiers;

    UhidKeyboard(String name) throws IOException {
        dev = new Uhid(name, 0xD0A1, REPORT_DESC, REPORT_SIZE);
    }

    static boolean isModifier(int usage) {
        return usage >= 0xE0 && usage <= 0xE7;
    }

    static int modifierBit(int usage) {
        return 1 << (usage - 0xE0);
    }

    synchronized void setModifiers(int mask) throws IOException {
        if (mask == modifiers) return;
        modifiers = mask;
        send();
    }

    synchronized void press(int usage) throws IOException {
        int free = -1;
        for (int i = 0; i < MAX_KEYS; i++) {
            if (keys[i] == usage) return;
            if (keys[i] == 0 && free < 0) free = i;
        }
        if (free < 0) return;   // 最多同时 6 个键，和真键盘一样
        keys[free] = usage;
        send();
    }

    synchronized void release(int usage) throws IOException {
        for (int i = 0; i < MAX_KEYS; i++) {
            if (keys[i] == usage) {
                keys[i] = 0;
                send();
                return;
            }
        }
    }

    synchronized void releaseAll() throws IOException {
        modifiers = 0;
        for (int i = 0; i < MAX_KEYS; i++) keys[i] = 0;
        send();
    }

    @Override
    public void close() {
        dev.close();
    }

    private void send() throws IOException {
        report[0] = (byte) modifiers;
        report[1] = 0;
        int n = 2;
        for (int k : keys) {
            if (k != 0) report[n++] = (byte) k;
        }
        while (n < REPORT_SIZE) report[n++] = 0;
        dev.send(report, REPORT_SIZE);
    }
}
