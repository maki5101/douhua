package dev.douhua;

/**
 * 虚拟鼠标、键盘和手柄的 HID 报文：描述符、报文格式、键盘按键状态、手柄状态和两路合并。
 * 桌面模式下服务经 /dev/uhid 造 USB 设备，蓝牙模式下 App 经 BluetoothHidDevice 当蓝牙设备，两边发出去的字节完全一样，
 * 蓝牙只是把几个设备合进一个描述符、每帧前面多一个报文编号。纯 Java（离线测试）。
 */
public final class Hid {
    public static final int MOD_CTRL = 0x01;
    public static final int MOD_SHIFT = 0x02;
    public static final int MOD_ALT = 0x04;
    public static final int MOD_META = 0x08;
    /** 右边四个修饰键是左边的 << 4（用法码 0xE4–0xE7；实体键盘上都能单独锁住，豆花布局只有左边四个）。 */
    public static final int MOD_RCTRL = 0x10;

    public static final int MOUSE_REPORT_SIZE = 7;
    public static final int KEYBOARD_REPORT_SIZE = 8;
    public static final int CONSUMER_REPORT_SIZE = 2;
    /** 手柄报文（{@link #GAMEPAD_DESC}）：键 2 字节 + 方向键 1 字节 + 四个摇杆轴各 2 字节 + 两个扳机各 1 字节。 */
    public static final int GAMEPAD_REPORT_SIZE = 13;

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
    /**
     * 带倍率的鼠标描述符里 Feature 报告的长度：蓝牙那份（{@link #MOUSE_DESC_HIRES}）竖、横两个倍率各 2 位 + 4 位补齐，
     * uhid 那份（{@link #MOUSE_DESC_HIRES_V}）只有竖向倍率 2 位 + 6 位补齐，都是 1 个字节、竖向都在位 0–1。
     */
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
     * 只有蓝牙模式用，跟着"连续滚动"开关（BtHid）；桌面模式的 uhid 用只带竖向倍率的 {@link #MOUSE_DESC_HIRES_V}。
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

    /**
     * 桌面模式 uhid 的鼠标：和 {@link #MOUSE_DESC_HIRES} 一样，只是横滚轮（AC Pan）那个 Logical 集合里没有倍率——
     * 整个描述符只有一个 Feature 字段（竖向倍率 2 位 + 6 位补齐，1 个字节），横向一直 ×1、照旧一格一格。输入报文还是和
     * {@link #MOUSE_DESC} 一个字节都不差（HidTest 逐字段比）。
     * 为什么只留一个：主线内核 hid-input.c 的 __hidinput_change_resolution_multipliers 在一个 Feature 报告里有不止一个
     * 字段（maxfield > 1）时要先 GET 当前值再 SET，设备带 HID_QUIRK_NO_INIT_REPORTS 就直接放弃、一个请求都不发；只有一个字段时
     * 不用先 GET、直接 SET。主力机三星内核建两个倍率的那份时只发来 START、OPEN，一个 GET / SET 都没有（2026-11-01 服务日志），
     * 推测落在"放弃"那条路上（三星内核看不到，没核实）。补齐的常量项没有用法，内核不把它算成字段。
     */
    public static final byte[] MOUSE_DESC_HIRES_V = {
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
            0x75, 0x06,                    //       Report Size (6)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0xB1, 0x01,             //       Feature (Const) 补齐到一个字节
            0x35, 0x00,                    //       Physical Minimum (0) 复位，别让后面的输入项继承 1..8
            0x45, 0x00,                    //       Physical Maximum (0)
            0x09, 0x38,                    //       Usage (Wheel)
            0x15, (byte) 0x81,             //       Logical Minimum (-127)
            0x25, 0x7F,                    //       Logical Maximum (127)
            0x75, 0x08,                    //       Report Size (8)
            (byte) 0x95, 0x01,             //       Report Count (1)
            (byte) 0x81, 0x06,             //       Input (Data,Var,Rel)
            (byte) 0xC0,                   //     End Collection
            (byte) 0xA1, 0x02,             //     Collection (Logical)：横滚轮，不带倍率（一直 ×1）
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

    /**
     * 手柄（桌面模式服务的第三个 uhid 设备 "Douhua Gamepad"，以后蓝牙那批前面插报告编号 5）：顶层 Game Pad（不是 Joystick：
     * Linux 按顶层集合定按键页落到哪：Game Pad 的第 n 个键 → BTN_GAMEPAD + n − 1，安卓 Generic.kl 再映成 BUTTON_A…；
     * Joystick 的落到 BUTTON_1…，Moonlight 用不了）。15 个键 + 方向键（Hat）+ 左摇杆 X/Y、右摇杆 Z/Rz（安卓的约定）
     * + 扳机 Simulation 页的 Brake（左）/ Accelerator（右，Linux ABS_BRAKE / ABS_GAS → 安卓 LTRIGGER / RTRIGGER）。
     * 这套是安卓 Generic.kl 和 Moonlight 第一个分支直接认的。
     * 方向键逻辑 1..8（1 = 上，顺时针），0 越界 = 回中（Linux hid-input 的 hat 换算：越界 → 回中），所以**全零报文 = 中立**：
     * 键全松、摇杆回中（轴有符号，0 = 中）、扳机归零，任何收尾发全零都不会变成"上一直按着"。
     * 扳机只发模拟轴，不另发数字的 L2 / R2 键（第 9、10 键留着、恒 0）。不用 4 字节数据的项（同别的描述符）。
     */
    public static final byte[] GAMEPAD_DESC = {
            0x05, 0x01,                    // Usage Page (Generic Desktop)
            0x09, 0x05,                    // Usage (Game Pad)
            (byte) 0xA1, 0x01,             // Collection (Application)
            0x05, 0x09,                    //   Usage Page (Button)
            0x19, 0x01,                    //   Usage Minimum (1)
            0x29, 0x0F,                    //   Usage Maximum (15)
            0x15, 0x00,                    //   Logical Minimum (0)
            0x25, 0x01,                    //   Logical Maximum (1)
            0x75, 0x01,                    //   Report Size (1)
            (byte) 0x95, 0x0F,             //   Report Count (15)
            (byte) 0x81, 0x02,             //   Input (Data,Var,Abs)
            (byte) 0x95, 0x01,             //   Report Count (1)
            (byte) 0x81, 0x01,             //   Input (Const) 1 位补齐
            0x05, 0x01,                    //   Usage Page (Generic Desktop)
            0x09, 0x39,                    //   Usage (Hat Switch)
            0x15, 0x01,                    //   Logical Minimum (1)：1 = 上，顺时针；0 越界 = 回中
            0x25, 0x08,                    //   Logical Maximum (8)
            0x35, 0x00,                    //   Physical Minimum (0)
            0x46, 0x3B, 0x01,              //   Physical Maximum (315)
            0x65, 0x14,                    //   Unit (度)
            0x75, 0x04,                    //   Report Size (4)
            (byte) 0x95, 0x01,             //   Report Count (1)
            (byte) 0x81, 0x42,             //   Input (Data,Var,Abs,Null State)
            0x65, 0x00,                    //   Unit (无) 复位，别让后面的轴继承"度"
            0x45, 0x00,                    //   Physical Maximum (0) 复位
            0x75, 0x04,                    //   Report Size (4)
            (byte) 0x95, 0x01,             //   Report Count (1)
            (byte) 0x81, 0x01,             //   Input (Const) 4 位补齐
            0x09, 0x01,                    //   Usage (Pointer)
            (byte) 0xA1, 0x00,             //   Collection (Physical)
            0x09, 0x30,                    //     Usage (X)   左摇杆
            0x09, 0x31,                    //     Usage (Y)
            0x09, 0x32,                    //     Usage (Z)   右摇杆
            0x09, 0x35,                    //     Usage (Rz)
            0x16, 0x01, (byte) 0x80,       //     Logical Minimum (-32767)
            0x26, (byte) 0xFF, 0x7F,       //     Logical Maximum (32767)
            0x75, 0x10,                    //     Report Size (16)
            (byte) 0x95, 0x04,             //     Report Count (4)
            (byte) 0x81, 0x02,             //     Input (Data,Var,Abs)
            (byte) 0xC0,                   //   End Collection
            0x05, 0x02,                    //   Usage Page (Simulation Controls)
            0x09, (byte) 0xC5,             //   Usage (Brake)        左扳机
            0x09, (byte) 0xC4,             //   Usage (Accelerator)  右扳机
            0x15, 0x00,                    //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x00,       //   Logical Maximum (255)
            0x75, 0x08,                    //   Report Size (8)
            (byte) 0x95, 0x02,             //   Report Count (2)
            (byte) 0x81, 0x02,             //   Input (Data,Var,Abs)
            (byte) 0xC0,                   // End Collection
    };

    private Hid() {}

    public static boolean isModifier(int usage) {
        return usage >= 0xE0 && usage <= 0xE7;
    }

    public static int modifierBit(int usage) {
        return 1 << (usage - 0xE0);
    }

    /** 修饰键掩码里有没有 Ctrl（左右都算：实体键盘上锁住右 Ctrl 也是 Ctrl + 滚轮 = 缩放）。 */
    public static boolean ctrl(int mask) {
        return (mask & (MOD_CTRL | MOD_RCTRL)) != 0;
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

    /**
     * 两个倍率（×1 / ×8）→ Feature 报告的那一个字节：位 0–1 竖、位 2–3 横（0 = ×1，1 = ×8），位 4–7 补齐。
     * uhid 那份（{@link #MOUSE_DESC_HIRES_V}）位 2–7 都是补齐：横向传 1，出来的就只有竖向那两位。
     */
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

        /**
         * uhid 那份（{@link #MOUSE_DESC_HIRES_V}）内核设倍率：只看竖向那两位（位 2–7 是补齐，有杂值也不管），横向不带倍率、一直 ×1；
         * 认不出（空 / null）返回 false、倍率不变。UhidMouse.setFeature 只调它（UhidMouse 依赖 android.*、不进离线编译，换算锁在这里）。
         */
        public boolean setUhidFeature(byte[] data) {
            int b = Hid.featureByte(data, -1);
            if (b < 0) return false;
            setMultipliers(multiplierV(b), 1);
            return true;
        }

        /** uhid 那份内核来问时回的 Feature 报告：1 个字节，只有竖向那两位（补齐位全 0）。UhidMouse.getFeature 只调它。 */
        public byte[] uhidFeatureReport() {
            return new byte[]{(byte) multiplierByte(multV, 1)};
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

    /**
     * 手柄现在的状态和它的报文（{@link #GAMEPAD_DESC}，13 字节，多字节字段小端）。纯数据，按位置记：
     * buttons 位 n = 第 n + 1 键（= 输出码 n，Proto.GP_*）；hat 0 = 回中、1–8 = 上起顺时针；
     * 摇杆 −32767..32767（Y 往下为正）；扳机 0..255。全零 = 中立（见 GAMEPAD_DESC）。
     */
    public static final class Gamepad {
        public static final int HAT_UP = 1, HAT_UP_RIGHT = 2, HAT_RIGHT = 3, HAT_DOWN_RIGHT = 4,
                HAT_DOWN = 5, HAT_DOWN_LEFT = 6, HAT_LEFT = 7, HAT_UP_LEFT = 8;
        public static final int AXIS_MAX = 32767;

        public int buttons, hat, lx, ly, rx, ry, lt, rt;

        public void clear() {
            buttons = hat = lx = ly = rx = ry = lt = rt = 0;
        }

        public void report(byte[] out) {
            int b = buttons & 0x7FFF;
            out[0] = (byte) b;
            out[1] = (byte) (b >> 8);
            out[2] = (byte) (hat >= 0 && hat <= 8 ? hat : 0);
            put16(out, 3, lx);
            put16(out, 5, ly);
            put16(out, 7, rx);
            put16(out, 9, ry);
            out[11] = (byte) Math.max(0, Math.min(255, lt));
            out[12] = (byte) Math.max(0, Math.min(255, rt));
        }

        /** 从报文读回来（App 画手柄页、测试用）。 */
        public void parse(byte[] r) {
            buttons = (r[0] & 0xFF) | (r[1] & 0x7F) << 8;
            hat = r[2] & 0x0F;
            if (hat > 8) hat = 0;
            lx = get16(r, 3);
            ly = get16(r, 5);
            rx = get16(r, 7);
            ry = get16(r, 9);
            lt = r[11] & 0xFF;
            rt = r[12] & 0xFF;
        }

        /** 这个输出码（Proto.GP_*）按着没有：键看位，扳机看轴，N64 的 C 键看右摇杆往那边推过一半没有。 */
        public boolean pressed(int output) {
            int half = AXIS_MAX / 2;
            switch (output) {
                case Proto.GP_LT: return lt > 0;
                case Proto.GP_RT: return rt > 0;
                case Proto.GP_C_UP: return ry < -half;
                case Proto.GP_C_DOWN: return ry > half;
                case Proto.GP_C_LEFT: return rx < -half;
                case Proto.GP_C_RIGHT: return rx > half;
                default: return output >= 0 && output < 15 && (buttons & (1 << output)) != 0;
            }
        }

        /** 方向键的两个分量（−1 / 0 / 1，y 往下为正）。 */
        public static int hatX(int hat) {
            switch (hat) {
                case HAT_UP_RIGHT: case HAT_RIGHT: case HAT_DOWN_RIGHT: return 1;
                case HAT_DOWN_LEFT: case HAT_LEFT: case HAT_UP_LEFT: return -1;
                default: return 0;
            }
        }

        public static int hatY(int hat) {
            switch (hat) {
                case HAT_UP_LEFT: case HAT_UP: case HAT_UP_RIGHT: return -1;
                case HAT_DOWN_RIGHT: case HAT_DOWN: case HAT_DOWN_LEFT: return 1;
                default: return 0;
            }
        }

        /** 全零 = 中立。 */
        public static boolean neutral(byte[] report) {
            for (int i = 0; i < GAMEPAD_REPORT_SIZE; i++) if (report[i] != 0) return false;
            return true;
        }

        /**
         * 两路合并（桌面模式：a = 服务自己分流出的、b = App 本地分流发来的，server.GamepadMux）：键按位或；方向键取不回中的那路
         * （都不回中取 a）；每个摇杆取离中心远的那路（两个分量一起取，不拼）；扳机取大的。out 可以就是 a 或 b。
         */
        public static void merge(byte[] a, byte[] b, byte[] out) {
            int ha = a[2] & 0x0F, hb = b[2] & 0x0F;
            byte b0 = (byte) (a[0] | b[0]), b1 = (byte) ((a[1] | b[1]) & 0x7F);
            byte hat = (byte) (ha != 0 && ha <= 8 ? ha : hb <= 8 ? hb : 0);
            int alx = get16(a, 3), aly = get16(a, 5), blx = get16(b, 3), bly = get16(b, 5);
            int arx = get16(a, 7), ary = get16(a, 9), brx = get16(b, 7), bry = get16(b, 9);
            boolean leftA = (long) alx * alx + (long) aly * aly >= (long) blx * blx + (long) bly * bly;
            boolean rightA = (long) arx * arx + (long) ary * ary >= (long) brx * brx + (long) bry * bry;
            int lt = Math.max(a[11] & 0xFF, b[11] & 0xFF), rt = Math.max(a[12] & 0xFF, b[12] & 0xFF);
            out[0] = b0;
            out[1] = b1;
            out[2] = hat;
            put16(out, 3, leftA ? alx : blx);
            put16(out, 5, leftA ? aly : bly);
            put16(out, 7, rightA ? arx : brx);
            put16(out, 9, rightA ? ary : bry);
            out[11] = (byte) lt;
            out[12] = (byte) rt;
        }

        private static void put16(byte[] out, int at, int v) {
            v = Math.max(-AXIS_MAX, Math.min(AXIS_MAX, v));
            out[at] = (byte) v;
            out[at + 1] = (byte) (v >> 8);
        }

        private static int get16(byte[] r, int at) {
            return (short) ((r[at] & 0xFF) | (r[at + 1] & 0xFF) << 8);
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
