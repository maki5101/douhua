package dev.douhua;

/**
 * 虚拟鼠标和键盘的 HID 报文：描述符、报文格式、键盘按键状态。
 * 桌面模式下服务经 /dev/uhid 造 USB 设备，蓝牙模式下 App 经 BluetoothHidDevice 当蓝牙设备，两边发出去的字节完全一样，
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
    /**
     * 绝对坐标指针（回正中、空中鼠标页的激光笔式移动；蓝牙描述符里一直带）：1 字节占位 + X 2 字节 + Y 2 字节。
     * 占位那个字节就是旧版的按键字节，报文排布和旧版一模一样，见 ABS_POINTER_DESC。
     */
    public static final int REPORT_ABS = 4;
    public static final int ABS_REPORT_SIZE = 5;
    public static final int ABS_MAX = 32767, ABS_CENTER = 16384;

    /**
     * 滚轮量的单位：一格 = 120（和 Windows、Linux 的高精度滚轮、Android、Moonlight 一个单位）。手势引擎 → 分流 → 服务 / 蓝牙这一路的
     * wheel / pan 全是这个单位，到最后一步才按主机设的分辨率倍率换算成报文里的计数（{@link WheelOut}）。
     */
    public static final int WHEEL_UNIT = 120;
    /** 分辨率倍率（Resolution Multiplier）的最大值：主机设了，每 1/8 格报一个计数（8 能整除 120；滚轮字段 8 位，一帧最多约 15.9 格）。 */
    public static final int RES_MULT = 8;
    /** 带倍率的鼠标描述符里 Feature 报告的长度（竖、横两个倍率各 2 位 + 4 位补齐）。 */
    public static final int FEATURE_REPORT_SIZE = 1;

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

    /**
     * 带分辨率倍率的鼠标（连续滚动用）：输入报文和 {@link #MOUSE_DESC} 一个字节都不差（规矩：已有报告的字节排布不能动，
     * 没重新配对的主机按旧描述符读也照旧）。多出来的只有一个 Feature 报告（不占输入报文的位）：竖滚轮、横滚轮各一个
     * "Resolution Multiplier"（逻辑 0..1 = 物理 ×1..×8），各自和它管的滚轮包在一个 Logical 集合里（倍率只作用于同一个
     * Logical 集合里的控件，Linux hid-core 的 hid_apply_multiplier）。主机设成 1（×8）就表示能处理小数格，之后每 1/8 格报一个计数；
     * 不设（默认 0 = ×1）照旧一格一格。写法照微软 "Enhanced Wheel Support" 和 QEMU 2026 高精度滚轮补丁。
     * 桌面模式下 uhid 一直用它（服务回应内核的 SET / GET_REPORT，见 server.Uhid）；蓝牙模式跟着"连续滚动"开关（BtHid）。
     * 不用 4 字节数据的项：蓝牙栈 check_descriptor 把尺寸码 3 当 3 字节走（HidTest 锁住）。
     */
    public static final byte[] MOUSE_DESC_HIRES = {
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
            (byte) 0xA1, 0x02,             //     Collection (Logical)：竖滚轮和它的倍率
            0x09, 0x48,                    //       Usage (Resolution Multiplier)
            0x15, 0x00,                    //       Logical Minimum (0)
            0x25, 0x01,                    //       Logical Maximum (1)
            0x35, 0x01,                    //       Physical Minimum (1)
            0x45, 0x08,                    //       Physical Maximum (8)
            0x75, 0x02,                    //       Report Size (2)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0xB1, 0x02,             //       Feature (Data,Var,Abs)
            0x35, 0x00,                    //       Physical Minimum (0) 复位，别让后面的输入项继承 1..8
            0x45, 0x00,                    //       Physical Maximum (0)
            0x09, 0x38,                    //       Usage (Wheel)
            0x15, (byte) 0x81,             //       Logical Minimum (-127)
            0x25, 0x7F,                    //       Logical Maximum (127)
            0x75, 0x08,                    //       Report Size (8)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0x81, 0x06,             //       Input (Data,Var,Rel)
            (byte) 0xC0,                   //     End Collection
            (byte) 0xA1, 0x02,             //     Collection (Logical)：横滚轮和它的倍率
            0x09, 0x48,                    //       Usage (Resolution Multiplier)
            0x15, 0x00,                    //       Logical Minimum (0)
            0x25, 0x01,                    //       Logical Maximum (1)
            0x35, 0x01,                    //       Physical Minimum (1)
            0x45, 0x08,                    //       Physical Maximum (8)
            0x75, 0x02,                    //       Report Size (2)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0xB1, 0x02,             //       Feature (Data,Var,Abs)
            0x35, 0x00,                    //       Physical Minimum (0)
            0x45, 0x00,                    //       Physical Maximum (0)
            0x75, 0x04,                    //       Report Size (4)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0xB1, 0x01,             //       Feature (Const) 补齐到一个字节
            0x05, 0x0C,                    //       Usage Page (Consumer)
            0x0A, 0x38, 0x02,              //       Usage (AC Pan)
            0x15, (byte) 0x81,             //       Logical Minimum (-127)
            0x25, 0x7F,                    //       Logical Maximum (127)
            0x75, 0x08,                    //       Report Size (8)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0x81, 0x06,             //       Input (Data,Var,Rel)
            (byte) 0xC0,                   //     End Collection
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

    /**
     * 绝对坐标指针：顶层必须是 Mouse（写 Pointer 会被 macOS 当摇杆），按键和 X/Y 都放在 Physical 集合里，集合完整闭合
     * （照 QEMU usb-tablet 和 MouseLink 的写法）。不用 Digitizer 页：Windows 走笔栈、iOS 丢弃、Android 当触摸屏。
     * 只用来"回正中"一步到位，不用它点击；按键字节照抄当前按键状态（和相对鼠标合成一个设备的主机上共用左键）。
     */
    public static final byte[] ABS_POINTER_DESC = {
            0x05, 0x01,                    // Usage Page (Generic Desktop)
            0x09, 0x02,                    // Usage (Mouse)
            (byte) 0xA1, 0x01,             // Collection (Application)
            0x09, 0x01,                    //   Usage (Pointer)
            (byte) 0xA1, 0x00,             //   Collection (Physical)
            // 不带按键：iPad 带按键时拿它当主指针看按键，普通鼠标那一路的点击就不灵了（2026-10-15 用户实测）。
            // 但第一个字节留着当占位：旧版这里是 5 个按键 + 3 位补齐，主机按配对时的描述符读报文，
            // 去掉这个字节、没重新配对的主机就错一位读（Linux 实测：回正中跑到左上角、移动时贴着顶边乱飞，
            // 低字节还被当成按键乱点）。报文第一个字节照发当前按键：按旧描述符读的主机（Linux 上和相对鼠标合成一个设备）
            // 左键不会被它松开，按新描述符读的主机当占位丢掉。
            (byte) 0x95, 0x01,             //     Report Count (1)
            0x75, 0x08,                    //     Report Size (8)
            (byte) 0x81, 0x01,             //     Input (Const) 占位
            0x05, 0x01,                    //     Usage Page (Generic Desktop)
            0x09, 0x30,                    //     Usage (X)
            0x09, 0x31,                    //     Usage (Y)
            0x15, 0x00,                    //     Logical Minimum (0)
            0x26, (byte) 0xFF, 0x7F,       //     Logical Maximum (32767)
            0x75, 0x10,                    //     Report Size (16)
            (byte) 0x95, 0x02,             //     Report Count (2)
            (byte) 0x81, 0x02,             //     Input (Data,Var,Abs)
            (byte) 0xC0,                   //   End Collection
            (byte) 0xC0,                   // End Collection
    };

    private Hid() {}

    public static boolean isModifier(int usage) {
        return usage >= 0xE0 && usage <= 0xE7;
    }

    public static int modifierBit(int usage) {
        return 1 << (usage - 0xE0);
    }

    /**
     * 蓝牙用：鼠标 + 键盘 + 媒体键合成一个描述符，各自带报文编号。
     * 鼠标排第一是 2026-10-15 排查 iPad 点击无效时改的，后来发现那是 iPadOS 自己的毛病（别的鼠标当时也点不了），
     * 不是顺序的问题；这个顺序在 iPad、Linux 上都验过，就留着。顺序不影响报文编号和格式，换顺序不用重新配对。
     */
    public static byte[] combinedDescriptor() {
        return combinedDescriptor(false, false);
    }

    public static byte[] combinedDescriptor(boolean absolute) {
        return combinedDescriptor(absolute, false);
    }

    /**
     * absolute = 末尾再加绝对坐标指针（报告 ID 4）。前面 1/2/3 的字节一个都不变，没重新配对的主机照旧能用。
     * hiresWheel = 鼠标用带分辨率倍率的描述符（{@link #MOUSE_DESC_HIRES}；Feature 报告也是 ID 2，和输入报告同号不同类型）。
     */
    public static byte[] combinedDescriptor(boolean absolute, boolean hiresWheel) {
        byte[] m = withReportId(hiresWheel ? MOUSE_DESC_HIRES : MOUSE_DESC, REPORT_MOUSE);
        byte[] k = withReportId(KEYBOARD_DESC, REPORT_KEYBOARD);
        byte[] c = withReportId(CONSUMER_DESC, REPORT_CONSUMER);
        byte[] a = absolute ? withReportId(ABS_POINTER_DESC, REPORT_ABS) : new byte[0];
        byte[] all = new byte[m.length + k.length + c.length + a.length];
        System.arraycopy(m, 0, all, 0, m.length);
        System.arraycopy(k, 0, all, m.length, k.length);
        System.arraycopy(c, 0, all, m.length + k.length, c.length);
        System.arraycopy(a, 0, all, m.length + k.length + c.length, a.length);
        return all;
    }

    /** 一帧绝对坐标报文：第一个字节是当前按键（旧描述符的主机认，新描述符当占位），x/y 截到 0..32767，小端。 */
    public static void absReport(byte[] out, int buttons, int x, int y) {
        x = Math.max(0, Math.min(ABS_MAX, x));
        y = Math.max(0, Math.min(ABS_MAX, y));
        out[0] = (byte) (buttons & 0x1F);
        out[1] = (byte) x;
        out[2] = (byte) (x >> 8);
        out[3] = (byte) y;
        out[4] = (byte) (y >> 8);
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

    // ═══════════ 滚轮的分辨率倍率 ═══════════

    /** 两个倍率（×1 / ×8）→ Feature 报告的那一个字节：位 0–1 竖、位 2–3 横（0 = ×1，1 = ×8），位 4–7 补齐。 */
    public static int multiplierByte(int vertical, int horizontal) {
        return (vertical > 1 ? 1 : 0) | (horizontal > 1 ? 1 : 0) << 2;
    }

    /** Feature 字节里的竖滚轮倍率（1 或 RES_MULT；2、3 超出逻辑范围，当 ×1）。 */
    public static int multiplierV(int b) {
        return (b & 3) == 1 ? RES_MULT : 1;
    }

    public static int multiplierH(int b) {
        return ((b >> 2) & 3) == 1 ? RES_MULT : 1;
    }

    /**
     * 主机设的 Feature 报告数据 → 那一个字节；认不出返回 -1。蓝牙栈（AOSP bta_hd_set_report_act）把类型和报告编号剥掉再交给 App，
     * 数据就是报告本身；两个字节、第一个正好是 id 的当作还带着编号（别家改过蓝牙栈也认）。uhid 那边没有报告编号，传 id = -1：
     * 内核的 __hid_request 给没编号的报告也在最前面留一个编号字节（0），uhid 原样转来，所以两个字节、第一个是 0 时取第二个。
     */
    public static int featureByte(byte[] data, int id) {
        if (data == null || data.length == 0) return -1;
        int lead = id >= 0 ? id : 0;
        if (data.length >= 2 && (data[0] & 0xFF) == lead) return data[1] & 0xFF;
        return data[0] & 0xFF;
    }

    /** 逐帧收报文的（UhidMouse 写 /dev/uhid，BtHid 发蓝牙）。 */
    public interface ReportSink<E extends Exception> {
        void send(byte[] report) throws E;
    }

    /**
     * 滚轮量 → 报文里的计数：引擎这一路的单位是 1/120 格（{@link #WHEEL_UNIT}），主机设了倍率 ×8 就每 15 个单位一个计数、
     * 没设（×1）就攒满 120 出一格；除不尽的余数留到下一帧。按格滚（总是 120 的倍数）余数永远是 0，和以前一个字节都不差。
     * 主机设了倍率后缩放、"滚轮向上 / 向下"、按格模式也全按倍率换算（不然一设倍率这些就慢了 8 倍）。
     * 余数在方向变了、或停了 {@link #REMAINDER_RESET_MS} 时清掉：不然低精度主机上留着的半格，下次轻轻一碰就出一格。
     * 一帧超过 ±127 计数就拆成几帧，只有第一帧带位移。不加锁：调用方（UhidMouse、BtHid）保证同一时刻只有一个线程在调。
     */
    public static final class WheelOut {
        public static final long REMAINDER_RESET_MS = 300;
        private int multV = 1, multH = 1;
        private int remV, remH;
        private long lastV, lastH;
        /** 上一帧发出去的按键（设备刚建好 / 主机刚连上时什么都没按着）。 */
        private int lastButtons;

        /** 主机设的倍率（1 或 RES_MULT）。换了倍率余数作废。 */
        public void setMultipliers(int vertical, int horizontal) {
            multV = vertical == RES_MULT ? RES_MULT : 1;
            multH = horizontal == RES_MULT ? RES_MULT : 1;
            remV = remH = 0;
        }

        /** 回到 ×1（设备复位、蓝牙新连上一台：规范里复位后默认 ×1，等主机重新来设）。 */
        public void reset() {
            setMultipliers(1, 1);
            lastButtons = 0;
        }

        public int multV() {
            return multV;
        }

        public int multH() {
            return multH;
        }

        /** 主机来问时回的 Feature 字节。 */
        public int featureByte() {
            return multiplierByte(multV, multH);
        }

        /** 一帧鼠标（wheelUnits / panUnits 是 1/120 格）：换算、拆帧，逐帧写进 report 交给 sink。now 是毫秒时间（清余数用）。 */
        public <E extends Exception> void mouse(byte[] report, int buttons, int dx, int dy, int wheelUnits, int panUnits,
                                                long now, ReportSink<E> sink) throws E {
            int w = 0, p = 0;
            if (wheelUnits != 0) {
                if (now - lastV >= REMAINDER_RESET_MS || (remV != 0 && (remV > 0) != (wheelUnits > 0))) remV = 0;
                lastV = now;
                int total = remV + wheelUnits;
                int step = WHEEL_UNIT / multV;   // 一个计数多少单位：×8 时 15，×1 时 120
                w = total / step;                // 向零取整
                remV = total - w * step;
            }
            if (panUnits != 0) {
                if (now - lastH >= REMAINDER_RESET_MS || (remH != 0 && (remH > 0) != (panUnits > 0))) remH = 0;
                lastH = now;
                int total = remH + panUnits;
                int step = WHEEL_UNIT / multH;
                p = total / step;
                remH = total - p * step;
            }
            // 这一帧只攒了余数、别的都没变（计数是 0、没有位移、按键和上一帧一样）：不用发，等于以前不满一格就不出报文
            if (w == 0 && p == 0 && dx == 0 && dy == 0 && buttons == lastButtons && (wheelUnits != 0 || panUnits != 0)) return;
            lastButtons = buttons;
            do {
                int fw = clamp(w, 127);
                int fp = clamp(p, 127);
                mouseReport(report, buttons, dx, dy, fw, fp);
                sink.send(report);
                w -= fw;
                p -= fp;
                dx = dy = 0;
            } while (w != 0 || p != 0);
        }
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
