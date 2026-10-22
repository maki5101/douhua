package dev.douhua;

/**
 * 豆花 App 和特权服务之间的通信协议。两边编在同一个 APK 里，改一处两边同时生效。
 * 所有多字节整数都是大端（DataInputStream / DataOutputStream 的默认）。
 *
 * 分工：服务独占外屏触摸屏、识别手势、驱动虚拟鼠标、判断手指落在哪个按钮上；
 * App 负责画界面、告诉服务各区域的位置、震动、执行动作。
 */
public final class Proto {
    private Proto() {}

    /**
     * 服务只监听本机回环地址。不用 Unix socket 是因为系统安全策略（SELinux）不允许普通 App 连 shell 身份的
     * Unix socket（实测 Permission denied）；TCP 回环允许，身份由服务查 /proc/net/tcp 校验。
     */
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 37601;
    public static final int VERSION = 15;
    /** 虚拟鼠标 / 键盘的厂商号（pid.codes 的开源项目厂商号）；App 靠它认出自己的虚拟键盘发来的键。 */
    public static final int HID_VENDOR = 0x1209;

    // ═══════════ 客户端 → 服务 ═══════════

    /** 按键：displayId:i32, keyCode:i32, metaState:i32。服务负责按下修饰键 → 按键 → 松开。 */
    public static final int CMD_KEY = 2;
    /** 点一下鼠标键：button:u8（BUTTON_*）。 */
    public static final int CMD_CLICK = 3;
    /**
     * 手势参数：pointerSpeed:f32, acceleration:f32, scrollStepDp:f32, naturalScroll:u8, pinchZoom:u8, edgeScroll:u8,
     * edgeScrollHorizontal:u8, edgeWidthDp:f32, scrollTicks:u8, longPressDrag:u8, longPressMs:i32,
     * tapToDrag:u8, dragWindowMs:i32, density:f32（每 dp 多少像素）, slowFactor:f32, texture:u8（移动时的颗粒震动）。
     */
    public static final int CMD_CONFIG = 4;
    /**
     * 界面布局：active:u8, relay:u8, displayId:i32, rotation:u8, width:i32, height:i32, count:u16,
     * 然后 count 个区域 {type:u8, id:i32, left:i32, top:i32, right:i32, bottom:i32}（type 是 REGION_*），坐标是屏幕像素。
     * 键盘面板的整块范围（{@link #REGION_KEYBOARD}）排在它的键后面。
     * active=1 时服务接管这块屏的触摸屏。relay=1（蓝牙模式）时分流出的鼠标、键盘报文不写手机自己的虚拟设备，
     * 发回 App（{@link #MSG_MOUSE} / {@link #MSG_KEYBOARD}），由 App 从蓝牙发出去。
     */
    public static final int CMD_LAYOUT = 5;
    /** 心跳：App 主线程每秒一次。服务超过 HEARTBEAT_TIMEOUT_MS 收不到就释放触摸屏。 */
    public static final int CMD_HEARTBEAT = 6;
    /** 虚拟键盘按键：key:u16（同 REGION_KEY 的 id）, down:u8。服务没接管触摸屏时，App 自己收到键盘区的触摸就发这个。 */
    public static final int CMD_HID_KEY = 7;
    /**
     * 转屏：displayId:i32, rotation:u8（Surface.ROTATION_*）。服务把这块屏锁定到这个方向
     * （和系统导航栏的旋转建议按钮做的一样）；连接断开时恢复原来的方向锁定。
     */
    public static final int CMD_ROTATE = 8;
    /** 滚一格（动作"滚轮向上 / 向下"）：wheel:i8（正 = 向上）。按住连续滚的节奏由 App 定，一格发一次。 */
    public static final int CMD_WHEEL = 9;
    /**
     * 保活：没有负载。App 的连接线程空闲 {@link #PING_INTERVAL_MS} 就发一个，只表示"连接还活着"。
     * 它不是心跳：服务只用它判断连接死没死（{@link #IDLE_TIMEOUT_MS}、新连接能不能顶掉旧的），
     * 不进独占条件——App 主线程卡住、连接线程还在发保活时，触摸屏照样要按心跳超时释放。
     */
    public static final int CMD_PING = 10;
    /**
     * 调试用的触点记录：flags:u8，bit0 = 记键盘触点。只有可调试包的 App 会发（tools/type-log.sh 经 adb 广播打开，
     * 见 DebugMoves 的 DEBUG_KEYLOG）。服务把落在键盘范围里的每次按下 / 抬起写进 logcat（标签 douhua-keys，
     * 格式见 TouchRouter.Trace），不写服务的日志文件；每条连接默认关，断开就没了。
     */
    public static final int CMD_TRACE = 11;
    /**
     * 音量键要截哪些：volUpTap:u8, volUpHold:u8, volDownTap:u8, volDownHold:u8（1 = 这一半配了动作）。
     * 全 0 = 一个都不截。连上时、改设置后、通话状态变化时发；第一次收到之前服务不碰任何实体键设备。
     * 配了动作的键整个归豆花（服务判断点一下 / 按住，结果发 {@link #MSG_HARD_KEY}）；和它在同一个设备上、没配动作的键
     * 由服务原样注入补发，照常调音量。电源键不截（带电源键的设备从不独占，见 server.KeyDevices）。
     * 协议 15 起去掉了电源那一个字节（14 是 5 个字节）。
     */
    public static final int CMD_HARD_KEYS = 12;
    /** 按住 / 松开鼠标左键：down:u8。DeX 模式下实体键按住配了"左键"时用，和 REGION_HOLD_LEFT 共用服务里的计数。 */
    public static final int CMD_HOLD_LEFT = 13;

    public static final int REGION_PAD = 0;
    /** 普通按钮：服务报告按下 / 松开 / 点击，由 App 决定做什么。 */
    public static final int REGION_BUTTON = 1;
    /** "左键"按钮：按住期间服务直接按住鼠标左键。 */
    public static final int REGION_HOLD_LEFT = 2;
    /**
     * 键盘键：id = HID 用法码 | (自带修饰键 << 8)。自带修饰键用 HID 修饰键位（1 Ctrl、2 Shift、4 Alt、8 Win），
     * 比如"@" = 0x1F | (2 << 8)（Shift+2），"复制" = 0x06 | (1 << 8)（Ctrl+C）。服务直接操作虚拟键盘；
     * 报给 App 的按钮编号是 KEY_ID_BASE + id。
     */
    public static final int REGION_KEY = 3;
    /**
     * 键盘面板的整块范围（不是键，id 不用、填 0），排在它的键后面。按在这里却没落在任何键上（键间的缝、
     * 第二行两头的空位、外圈边距）时，分给范围里最近的键（TouchRouter.nearestKey）。旧服务不认识它，
     * 会当成按钮 id 0（按钮条的"退出"），所以加它时协议升到了 14。
     */
    public static final int REGION_KEYBOARD = 4;
    public static final int KEY_ID_BASE = 0x10000;

    public static final long HEARTBEAT_INTERVAL_MS = 1000;
    public static final long HEARTBEAT_TIMEOUT_MS = 3000;
    /** App 的连接线程空闲这么久就发一个 {@link #CMD_PING}。 */
    public static final long PING_INTERVAL_MS = 2000;
    /** 服务这么久收不到这条连接的任何消息（连保活都没有）就结束会话：对面多半已经不在了，只是断开的包没送到。 */
    public static final int IDLE_TIMEOUT_MS = 10000;
    /** App 连上端口后这么久收不到 hello，就提示"服务没响应"（服务卡住了）。 */
    public static final int HELLO_TIMEOUT_MS = 5000;

    // ═══════════ 服务 → 客户端 ═══════════

    /** 连上后服务先发一条：version:u8, ok:u8（HELLO_*）, message:UTF。 */
    public static final int MSG_HELLO = 0x80;
    /** 服务出错，message 说明哪里坏了。 */
    public static final int HELLO_FAILED = 0;
    /** 正常；message 不空时是附带的提醒（比如按键注入暂时不可用）。 */
    public static final int HELLO_OK = 1;
    /** 另一个连接正在用服务、而且还在说话：服务发完这条就关掉这条连接，App 照常每秒重试。message 为空。 */
    public static final int HELLO_BUSY = 2;
    /** 震动：kind:u8（GestureEngine.HAPTIC_*）。 */
    public static final int MSG_HAPTIC = 0x81;
    /** 三指 / 四指手势：fingers:u8, kind:u8（GestureEngine.GESTURE_*）。 */
    public static final int MSG_GESTURE = 0x82;
    /** 按钮：id:i32, action:u8（BUTTON_DOWN / BUTTON_UP / BUTTON_TAP）。 */
    public static final int MSG_BUTTON = 0x83;
    /**
     * 触摸屏接管状态：grabbed:u8, reason:u8（GRAB_*）, detail:UTF。
     * 服务只发原因代码和细节（屏幕编号、错误原文），文字由 App 按系统语言拼——服务进程读不到 App 的多语言资源。
     */
    public static final int MSG_GRAB = 0x84;
    public static final int GRAB_OK = 0;
    public static final int GRAB_PAUSED = 1;
    /** 心跳超时：App 卡住了，触摸屏已还给系统。 */
    public static final int GRAB_UNRESPONSIVE = 2;
    public static final int GRAB_ROTATING = 3;
    /** 这块屏没有触摸屏；detail = 屏幕编号。 */
    public static final int GRAB_NO_TOUCHSCREEN = 4;
    /** 接管失败；detail = 错误原文。 */
    public static final int GRAB_FAILED = 5;
    public static final int GRAB_DISCONNECTED = 6;
    public static final int GRAB_RELEASED = 7;
    /** 锁定中的修饰键：mask:u8（HID 修饰键位：1 Ctrl、2 Shift、4 Alt、8 Win）。点一下修饰键锁定，打完下一个键自动解开。 */
    public static final int MSG_MODIFIERS = 0x85;
    /** 转发的鼠标报文（relay 模式）：buttons:u8, dx:i16, dy:i16, wheel:i8, pan:i8。 */
    public static final int MSG_MOUSE = 0x86;
    /** 转发的键盘报文（relay 模式）：HID 键盘报文原样 8 字节（修饰键位、保留、6 个键）。 */
    public static final int MSG_KEYBOARD = 0x87;
    /** 实体按键（服务已判断好）：key:u8（HARD_VOL_UP / HARD_VOL_DOWN）, event:u8（HARD_TAP / HARD_HOLD_START / HARD_HOLD_END）。 */
    public static final int MSG_HARD_KEY = 0x88;
    /** 和 HardKeys.VOL_UP / VOL_DOWN 一致。 */
    public static final int HARD_VOL_UP = 0;
    public static final int HARD_VOL_DOWN = 1;
    public static final int HARD_TAP = 0;
    public static final int HARD_HOLD_START = 1;
    public static final int HARD_HOLD_END = 2;
    /**
     * 实体按键的独占状态：grabbed:u8, unavailable:u8（位掩码：HARD_BIT_*）, reason:u8（HARD_*）, detail:UTF。
     * unavailable = 配了动作、却截不了的键。文字由 App 按语言拼，服务只发代码和细节（设备名、多出来的键码、错误原文）。
     */
    public static final int MSG_HARD_GRAB = 0x89;
    public static final int HARD_BIT_VOL_UP = 1;
    public static final int HARD_BIT_VOL_DOWN = 2;
    public static final int HARD_OK = 0;
    /** 没找到带这个键的设备。 */
    public static final int HARD_NO_DEVICE = 1;
    /**
     * 和别的输入（电源键、别的键、开关、触摸）在同一个设备上，整个设备一起独占代价太大，不截；
     * 也用于找不到能旁观的电源键设备（截了电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）会失灵）。具体原因在 detail 里。
     */
    public static final int HARD_SHARED = 2;
    /** 服务注入不了按键：不截任何实体键（不然同设备上没配的音量键补发不出去）。 */
    public static final int HARD_NO_INJECTOR = 3;
    public static final int HARD_FAILED = 4;
    /** 不再截了（App 不在前台、心跳超时、配置改成全 0、来电响铃 / 通话中）。 */
    public static final int HARD_RELEASED = 5;
    /** 截不了的键不止一个、原因又各不相同（每个键的原因都在 detail 里）。 */
    public static final int HARD_MIXED = 6;

    public static final int BUTTON_DOWN = 0;
    /** 松开但不算点击（手指滑出了按钮，或被取消）。 */
    public static final int BUTTON_UP = 1;
    /** 在按钮里按下又松开。 */
    public static final int BUTTON_TAP = 2;

    // ═══════════ 鼠标按键位 ═══════════

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_RIGHT = 2;
    public static final int BUTTON_MIDDLE = 4;
}
