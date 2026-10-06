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
    public static final int VERSION = 9;

    // ═══════════ 客户端 → 服务 ═══════════

    /** 按键：displayId:i32, keyCode:i32, metaState:i32。服务负责按下修饰键 → 按键 → 松开。 */
    public static final int CMD_KEY = 2;
    /** 点一下鼠标键：button:u8（BUTTON_*）。 */
    public static final int CMD_CLICK = 3;
    /**
     * 手势参数：pointerSpeed:f32, acceleration:f32, scrollStepDp:f32, naturalScroll:u8, pinchZoom:u8, edgeScroll:u8,
     * edgeScrollHorizontal:u8, edgeWidthDp:f32, scrollTicks:u8, longPressDrag:u8, longPressMs:i32,
     * tapToDrag:u8, dragWindowMs:i32, density:f32（每 dp 多少像素）。
     */
    public static final int CMD_CONFIG = 4;
    /**
     * 界面布局：active:u8, displayId:i32, rotation:u8, width:i32, height:i32, count:u16,
     * 然后 count 个区域 {type:u8, id:i32, left:i32, top:i32, right:i32, bottom:i32}，坐标是屏幕像素。
     * active=1 时服务接管这块屏的触摸屏。
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
    public static final int KEY_ID_BASE = 0x10000;

    public static final long HEARTBEAT_INTERVAL_MS = 1000;
    public static final long HEARTBEAT_TIMEOUT_MS = 3000;

    // ═══════════ 服务 → 客户端 ═══════════

    /** 连上后服务先发一条：version:u8, ok:u8, message:UTF。ok=0 时 message 说明哪里坏了。 */
    public static final int MSG_HELLO = 0x80;
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
