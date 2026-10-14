package dev.douhua;

/**
 * 虚拟鼠标和键盘的 HID 报文：描述符、报文格式、键盘按键状态。
 * DeX 模式下服务经 /dev/uhid 造 USB 设备，蓝牙模式下 App 经 BluetoothHidDevice 当蓝牙设备，两边发出去的字节完全一样，
 * 蓝牙只是把几个设备合进一个描述符、每帧前面多一个报文编号。纯 Java（离线测试）。
 */
public final class Hid {
    public static final int MOD_CTRL = 0x01;
    public static final int MOD_SHIFT = 0x02;
    public static final int MOD_ALT = 0x04;
    public static final int MOD_META = 0x08;

    public static final int MOUSE_REPORT_SIZE = 7;
    public static final int KEYBOARD_REPORT_SIZE = 8;
    public static final int CONSUMER_REPORT_SIZE = 2;

    /** 蓝牙描述符里的报文编号。 */
    public static final int REPORT_KEYBOARD = 1;
    public static final int REPORT_MOUSE = 2;
    public static final int REPORT_CONSUMER = 3;

    /** 鼠标：5 个按键 + 16 位相对 X/Y + 竖向滚轮 + 横向滚轮（AC Pan）。 */
    public static final byte[] MOUSE_DESC = {
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

    /** 标准 boot 键盘：8 个修饰键位 + 保留字节 + 5 个指示灯（输出）+ 6 个同时按下的键。 */
    public static final byte[] KEYBOARD_DESC = {
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

    /** 媒体键（只蓝牙用）：一次一个 16 位的 Consumer 用法码，0 = 松开。返回、主屏、播放、音量这类键盘上没有的键。 */
    public static final byte[] CONSUMER_DESC = {
            0x05, 0x0C,                    // Usage Page (Consumer)
            0x09, 0x01,                    // Usage (Consumer Control)
            (byte) 0xA1, 0x01,             // Collection (Application)
            0x15, 0x00,                    //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x03,       //   Logical Maximum (0x3FF)
            0x19, 0x00,                    //   Usage Minimum (0)
            0x2A, (byte) 0xFF, 0x03,       //   Usage Maximum (0x3FF)
            0x75, 0x10,                    //   Report Size (16)
            (byte) 0x95, 0x01,             //   Report Count (1)
            (byte) 0x81, 0x00,             //   Input (Data,Array,Abs)
            (byte) 0xC0,                   // End Collection
    };

    private Hid() {}

    public static boolean isModifier(int usage) {
        return usage >= 0xE0 && usage <= 0xE7;
    }

    public static int modifierBit(int usage) {
        return 1 << (usage - 0xE0);
    }

    /** 蓝牙用：键盘 + 鼠标 + 媒体键合成一个描述符，各自带报文编号。 */
    public static byte[] combinedDescriptor() {
        byte[] k = withReportId(KEYBOARD_DESC, REPORT_KEYBOARD);
        byte[] m = withReportId(MOUSE_DESC, REPORT_MOUSE);
        byte[] c = withReportId(CONSUMER_DESC, REPORT_CONSUMER);
        byte[] all = new byte[k.length + m.length + c.length];
        System.arraycopy(k, 0, all, 0, k.length);
        System.arraycopy(m, 0, all, k.length, m.length);
        System.arraycopy(c, 0, all, k.length + m.length, c.length);
        return all;
    }

    /** 在最外层 Collection (Application) 后面插一个 Report ID 项。三个描述符开头都是 用法页 / 用法 / 集合 各 2 字节。 */
    static byte[] withReportId(byte[] desc, int id) {
        if (desc[4] != (byte) 0xA1 || desc[5] != 0x01) throw new IllegalArgumentException("描述符开头不是 Collection (Application)");
        byte[] out = new byte[desc.length + 2];
        System.arraycopy(desc, 0, out, 0, 6);
        out[6] = (byte) 0x85;   // Report ID
        out[7] = (byte) id;
        System.arraycopy(desc, 6, out, 8, desc.length - 6);
        return out;
    }

    /** 一帧鼠标报文。buttons 是当前按下状态；其余是相对量，超范围的截断。多字节字段按 HID 规定小端。 */
    public static void mouseReport(byte[] out, int buttons, int dx, int dy, int wheel, int pan) {
        dx = clamp(dx, 32767);
        dy = clamp(dy, 32767);
        out[0] = (byte) (buttons & 0x1F);
        out[1] = (byte) dx;
        out[2] = (byte) (dx >> 8);
        out[3] = (byte) dy;
        out[4] = (byte) (dy >> 8);
        out[5] = (byte) clamp(wheel, 127);
        out[6] = (byte) clamp(pan, 127);
    }

    private static int clamp(int v, int limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    /** 虚拟键盘当前按着什么：修饰键位 + 最多 6 个键（和真键盘一样，第 7 个键按不下去）。 */
    public static final class Keyboard {
        private static final int MAX_KEYS = 6;
        private final int[] keys = new int[MAX_KEYS];
        private int modifiers;

        /** 返回 true = 状态变了，要发一帧报文（下同）。 */
        public boolean setModifiers(int mask) {
            if (mask == modifiers) return false;
            modifiers = mask;
            return true;
        }

        public boolean press(int usage) {
            int free = -1;
            for (int i = 0; i < MAX_KEYS; i++) {
                if (keys[i] == usage) return false;
                if (keys[i] == 0 && free < 0) free = i;
            }
            if (free < 0) return false;
            keys[free] = usage;
            return true;
        }

        public boolean release(int usage) {
            for (int i = 0; i < MAX_KEYS; i++) {
                if (keys[i] == usage) {
                    keys[i] = 0;
                    return true;
                }
            }
            return false;
        }

        /** 全部松开。总是返回 true：松开时发一帧干净的报文，保证对方不会留着按住的键。 */
        public boolean releaseAll() {
            modifiers = 0;
            for (int i = 0; i < MAX_KEYS; i++) keys[i] = 0;
            return true;
        }

        public void report(byte[] out) {
            out[0] = (byte) modifiers;
            out[1] = 0;
            int n = 2;
            for (int k : keys) {
                if (k != 0) out[n++] = (byte) k;
            }
            while (n < KEYBOARD_REPORT_SIZE) out[n++] = 0;
        }
    }
}
