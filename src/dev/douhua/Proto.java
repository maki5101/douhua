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
    /**
     * 协议版本。25（2026-11-02，声音第一批的事件层）：两处小改，都是给声音分"发生了什么"，震动、按键、动作的行为一点不变——
     * ① "按住到点"（修饰键快锁了、Caps 只切显示、合并键出第二个字、Fn 快永久锁了、Fn+Esc 翻转 F 行，KeyHolds.Host.holdReached）
     * 单独一种震动 GestureEngine.HAPTIC_HOLD（以前用 HAPTIC_CLICK，App 分不出是"鼠标点了一下"还是"修饰键快锁了"）；App 照点一下震（Haptics 把它当 HAPTIC_CLICK）；
     * ② "被取消"的标记：{@link #MSG_BUTTON} 的松开（BUTTON_UP / BUTTON_TAP）带 {@link #BUTTON_CANCELED}（手掌、丢数据、服务释放触摸屏时补报的抬起、
     * releaseAll 收尾）；触控板上的拖动被取消着结束（手掌、收尾、换参数）单独一种震动 GestureEngine.HAPTIC_DRAG_CANCEL（App 照拖动结束震）；
     * 实体键的按住被收尾补着结束（releaseAll、电源键组合交还系统）报 {@link #HARD_HOLD_CANCEL}（App 照 HARD_HOLD_END 收尾）。
     * App 照旧处理，只有声音看它（被取消的不出松开声）。旧服务不发这几样，所以升号；
     * 版本不一致时同 24 那条：Link 读 hello 就断开、报"版本不一致"，靠"连不上 → 自动启动"（SelfStart.autoStart）重启服务，
     * 或者 deploy 脚本 / tools/start-server.sh 重起。
     * 24（2026-11-02）：实体键盘 F1–F12 的第二功能（音量、亮度、媒体、睡眠……）和"F 行翻转"（FnLock）：
     * F 行键的区域 id 带两面（{@link #frowKey}），Esc 在 Fn 层是 F 行翻转开关（{@link #FN_LOCK_KEY}）；CMD_CONFIG 末尾加 frowFlip:u8；
     * MSG_MODIFIERS 第四个字节多一位 {@link #FROW_FLIP}；新消息 {@link #MSG_CONSUMER}（蓝牙模式下服务接管时把多媒体 / 系统键转回 App 由蓝牙发）。
     * 桌面模式另有第四个 uhid 设备 "Douhua Keyboard Controls"（{@link #HID_CONTROLS_PRODUCT}，多媒体 + 系统键）。旧服务会把 F 行键的 id 当普通键、
     * 按出乱码，所以升号；版本不一致时同 23 那条：Link 读 hello 就断开、报"版本不一致"，靠"连不上 → 自动启动"（SelfStart.autoStart）重启服务，
     * 或者 tools/start-server.sh / 设置页"启动服务"手动重起。
     * 23（2026-11-01 晚）：CMD_CONFIG 末尾加了小红点推光标的加速、最快速度（stickAccel:f32、stickMaxSpeed:f32）。
     * 22 那一版已经打过包、用户可能装着，CMD_CONFIG 变长后新旧混着跑会读错，所以单独升一号。版本不一致时 App 的 Link 在读 hello 里的 APK 路径之前就断开、
     * 报"版本不一致"（按 APK 路径重启那条路走不到）；开着"自动启动服务"、配对过、密钥没被拒、不在退避中时 SelfStart.autoStart 探到旧版本会自己重启它，
     * 不然要手动重起服务（tools/start-server.sh，或设置页"启动服务"）。
     * 22（2026-11-01 晚）：加了 {@link #CMD_FOCUS_OTHER}（分屏里豆花拿着键盘焦点时交给另一半）；
     * 同一批 CMD_CONFIG 末尾加了中键滑动速度（leverSpeed:f32）、MSG_MODIFIERS 第四个字节多了 {@link #FN_LOCKED}、Fn 换了规矩
     * （点一下单次锁、按住 0.4 秒永久锁，KeyHolds），都算在 22 里。
     */
    public static final int VERSION = 25;
    /** 虚拟鼠标 / 键盘的厂商号（pid.codes 的开源项目厂商号）；App 靠它认出自己的虚拟键盘发来的键。 */
    public static final int HID_VENDOR = 0x1209;
    /**
     * 虚拟手柄（"Douhua Gamepad"，服务第三个 uhid 设备，协议 19 起）的产品号。鼠标 0xD0A0、键盘 0xD0A1。
     * App 靠厂商号 + 它认出自己的手柄：落到豆花自己窗口上的手柄键、摇杆一律吞掉（不吞的话 B = 返回、Home = 回桌面）。
     */
    public static final int HID_GAMEPAD_PRODUCT = 0xD0A2;
    /**
     * 多媒体键 + 系统键（"Douhua Keyboard Controls"，服务第四个 uhid 设备，协议 24 起；和虚拟键盘同建同关）的产品号。
     * 照真 USB 键盘"一个打字接口 + 一个带报告编号的接口放多媒体 / 系统键"（QMK 就这么分）：描述符是蓝牙那两段（多媒体报告 3、系统键报告 6，
     * Hid.controlsDescriptor），现有的虚拟键盘一个字节都不动。App 不吞它的键（MainActivity.dispatchKeyEvent）：音量、媒体这些归主机（这台手机）处理。
     */
    public static final int HID_CONTROLS_PRODUCT = 0xD0A3;

    // ═══════════ 客户端 → 服务 ═══════════

    /** 按键：displayId:i32, keyCode:i32, metaState:i32。服务负责按下修饰键 → 按键 → 松开。 */
    public static final int CMD_KEY = 2;
    /** 点一下鼠标键：button:u8（BUTTON_*）。 */
    public static final int CMD_CLICK = 3;
    /**
     * 手势参数：pointerSpeed:f32, acceleration:f32, scrollStepDp:f32, naturalScroll:u8, pinchZoom:u8, edgeScroll:u8,
     * edgeScrollHorizontal:u8, edgeWidthDp:f32, scrollTicks:u8, longPressDrag:u8, longPressMs:i32,
     * tapToDrag:u8, dragWindowMs:i32, density:f32（每 dp 多少像素）, slowFactor:f32, texture:u8（移动时的颗粒震动），
     * inertia:u8（惯性滚动）, inertiaTauMs:i32（阻尼）, inertiaMinSpeed:f32（多快算拨，dp/ms）, smoothScroll:u8（连续滚动），
     * stickSpeed:f32（小红点灵敏度）, stickTap:u8（轻点红点 = 左键）, stickInertia:u8（小红点负惯性）, leverSpeed:f32（中键滑动的滚动速度倍数），
     * stickAccel:f32（小红点推光标的加速，0–1）, stickMaxSpeed:f32（加速 100% 时小红点推满的速度，计数 / 秒）。
     * frowFlip:u8（实体键盘 F 行平时出第二功能 = 1，"F 行翻转"，KeyHolds；不是手势参数，跟着 CMD_CONFIG 走）。
     * inertia 到 stickInertia 七项是协议 18 加的，leverSpeed 是协议 22 加的，stickAccel、stickMaxSpeed 是协议 23 加的，frowFlip 是协议 24 加的。
     * 编解码只在 {@link ConfigCodec} 一处（App、服务、自测共用）。
     */
    public static final int CMD_CONFIG = 4;
    /**
     * 界面布局：active:u8, relay:u8, displayId:i32, rotation:u8, width:i32, height:i32,
     * full:u8, winLeft:i32, winTop:i32, winRight:i32, winBottom:i32, gestLeft:i32, gestTop:i32, gestRight:i32, gestBottom:i32,
     * count:u16, 然后 count 个区域 {type:u8, id:i32, left:i32, top:i32, right:i32, bottom:i32}（type 是 REGION_*），坐标是屏幕像素。
     * width / height 是整块屏（当前方向）的尺寸。full = 豆花窗口占满整块屏；win* = 豆花窗口在屏上的范围（右、下不含）；
     * gest* = 贴着窗口四条边的系统手势带宽度（窗口的 systemGestures 插图）。协议 17 起有 full 到 gestBottom 这一段：
     * 桌面模式下没占满（分屏 / 小窗）时，服务把按在窗口外、手势带里的手指注入回系统（分屏透传，见 server.TouchSplit）。
     * 键盘面板的整块范围（{@link #REGION_KEYBOARD}）排在它的键后面；手柄面键簇的范围（{@link #REGION_GAMEPAD}）排在它的成员前面。
     * active=1 时服务接管这块屏的触摸屏。relay=1（蓝牙模式）时分流出的鼠标、键盘报文不写手机自己的虚拟设备，
     * 发回 App（{@link #MSG_MOUSE} / {@link #MSG_KEYBOARD}），由 App 从蓝牙发出去。
     */
    public static final int CMD_LAYOUT = 5;
    /** 心跳：App 主线程每秒一次。服务超过 HEARTBEAT_TIMEOUT_MS 收不到就释放触摸屏。 */
    public static final int CMD_HEARTBEAT = 6;
    /**
     * 虚拟键盘按键：key:i32（同 REGION_KEY 的 id，含合并键 {@link #dualKey}、协议 21 起的 Fn 键 {@link #FN_KEY} 和带 Fn 层的键 {@link #fnLayerKey}）,
     * action:u8（{@link #KEY_UP} / {@link #KEY_DOWN} / {@link #KEY_CANCEL}）。
     * 服务没接管触摸屏时，App 自己收到键盘区的触摸、动作里的组合键就发这个；服务的分流按同一份规则判点一下 / 按住 / 锁（KeyHolds），时间用收到的时刻。
     * 协议 20 起（以前是 key:u16, down:u8）：i32 是合并键的 id 放不进 16 位；CANCEL 是被取消的手指不能当"点了一下"（不然取消一下就发 Caps / 逗号）。
     * 不算心跳。
     */
    public static final int CMD_HID_KEY = 7;
    public static final int KEY_UP = 0, KEY_DOWN = 1, KEY_CANCEL = 2;
    /**
     * 转屏：displayId:i32, rotation:u8（Surface.ROTATION_*）。服务把这块屏锁定到这个方向
     * （和系统导航栏的旋转建议按钮做的一样）；连接断开时恢复原来的方向锁定。
     */
    public static final int CMD_ROTATE = 8;
    /**
     * 滚一格（动作"滚轮向上 / 向下"）：wheel:i8（格数，正 = 向上）。按住连续滚的节奏由 App 定，一格发一次。
     * 服务里换成 1/120 格（Hid.WHEEL_UNIT）再按主机倍率出；转着的惯性先停。
     */
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
    /** 按住 / 松开鼠标左键：down:u8。桌面模式下实体键按住配了"左键"时用，和 REGION_HOLD_LEFT 共用服务里的计数。 */
    public static final int CMD_HOLD_LEFT = 13;
    /**
     * 手柄报文（协议 19 起）：report[13]（Hid.GAMEPAD_DESC 的输入报文原样）。桌面模式下服务没接管、手柄页的触摸由 App 本地分流时，
     * App 把分出来的手柄状态发给服务；服务和自己分流出的那一路合并（server.GamepadMux）再写 uhid 手柄。
     * 第一次收到不是中立的报文、而手柄还没建时先建。不算心跳。
     */
    public static final int CMD_GAMEPAD = 14;
    /**
     * 手柄参数（协议 19 起）：length:u16, 然后 length 字节（GamepadConfig 的字段，编解码只在 GamepadConfig 一处）。
     * 读的一方只读认识的字段、多的跳过、少的用默认：以后加参数不用再升协议。换预设、进出手柄页、连上时 App 都发一次，
     * 服务的分流收到后手柄那一路回到中立。不算心跳。
     */
    public static final int CMD_GAMEPAD_CONFIG = 15;
    /**
     * 清掉锁住的修饰键（协议 20 起）：不带参数。服务的分流把锁定全部解开（HID 里这些位松开，同一位还有手指按着的照旧按着；快锁了的、待定的不动；
     * 协议 21 起锁住的 Fn 也解开）。
     * 进空中鼠标、手柄页时 App 发（不然 Ctrl 一直按着、每次点击都成了 Ctrl+点击）。取代以前"逐个点一下"：现在没锁着的修饰键点一下
     * 就是给主机一次真单点（Super 打开开始菜单）；也不带 App 记的锁定掩码，那份可能晚一步。不算心跳。
     */
    public static final int CMD_UNLATCH = 16;
    /**
     * 把键盘焦点交给这块屏上另一个看得见的应用（协议 22 起）：displayId:i32（豆花窗口所在的屏）。
     * 桌面模式分屏 / 小窗、没接外接屏时，豆花窗口一拿到键盘焦点（比如从设置页回来、onResume 清掉了 yieldFocus），
     * 豆花自己的虚拟键盘、手柄的键就送到豆花窗口、被豆花吞掉，整块键盘打不出字（2026-11-01 主力机）。App 这时发它（什么时候发见 FocusHandoff），
     * 服务在单独的线程（douhua-focus）上列这块屏的任务、挑分屏另一半里最近用过的、看得见的、不是豆花 / 桌面 / SystemUI 的那个，
     * 调 IActivityTaskManager.setFocusedTask——效果等于用户点了它一下（server.TaskFocus、server.FocusPick）。
     * 豆花的任务没拿着焦点（请求晚到、用户已经点了别处）、豆花不在分屏里（小窗：交了会把后面的全屏应用挪到小窗上面、盖住豆花）
     * 或者没找到可交的，什么都不做。不算心跳。
     */
    public static final int CMD_FOCUS_OTHER = 17;

    public static final int REGION_PAD = 0;
    /** 普通按钮：服务报告按下 / 松开 / 点击，由 App 决定做什么。 */
    public static final int REGION_BUTTON = 1;
    /** "左键"按钮：按住期间服务直接按住鼠标左键。 */
    public static final int REGION_HOLD_LEFT = 2;
    /**
     * 键盘键：id = HID 用法码 | (自带修饰键 << 8)。自带修饰键用 HID 修饰键位（1 Ctrl、2 Shift、4 Alt、8 Win），
     * 比如"@" = 0x1F | (2 << 8)（Shift+2），"复制" = 0x06 | (1 << 8)（Ctrl+C）。服务直接操作虚拟键盘；
     * 报给 App 的按钮编号是 KEY_ID_BASE + id。
     * 另有合并键（协议 20 起，小红点状态的逗号句号）：id = {@link #dualKey}(点一下的键, 按住 0.4 秒的键)，两个都是上面那种 16 位的键 id。
     * 协议 21 起还有实体键盘 75% / 65% / 苹果紧凑的 Fn 键（{@link #FN_KEY} / {@link #FN_GLOBE_KEY}）和带 Fn 层的键（{@link #fnLayerKey}）。
     * 协议 24 起还有 F 行键（{@link #frowKey}：F1–F12 带第二功能、Esc 带 F 行翻转开关）。
     * 几种 id 的范围互不重叠（ServerPartsTest 锁住）：普通键 0–0xFFFF；合并键 0x10000–0x3FFFFFFF；Fn 那一段 0x40000000–0x40FFFFFF；F 行键 0x41000000–0x44FFFFFF。
     * KEY_ID_BASE + id 都还是正数（App 只看 ≥ KEY_ID_BASE）。
     */
    public static final int REGION_KEY = 3;

    /**
     * 合并键的 id：tap | hold << 16（hold ≠ 0 且 ≤ 0x3FFF）。点一下 = tap，按住 0.4 秒 = hold（只发一次点按），见 KeyHolds。
     * 报给 App 的按钮编号照旧 KEY_ID_BASE + id（App 只看 ≥ KEY_ID_BASE）。
     * hold 协议 21 起最多 0x3FFF（以前 0x7FFF）：第 30 位让给 Fn 那一段（{@link #FN_FLAG}）；以前最大的 hold 加上 KEY_ID_BASE 会溢出成负数，
     * 现在最大的合并键加上它照样是正数。真用到的 hold 只有句号 0x37。
     */
    public static int dualKey(int tap, int hold) {
        return (tap & 0xFFFF) | (hold & 0x3FFF) << 16;
    }

    public static boolean isDualKey(int id) {
        return id > 0xFFFF && id < FN_FLAG;
    }

    public static int dualTap(int id) {
        return id & 0xFFFF;
    }

    public static int dualHold(int id) {
        return (id >>> 16) & 0x3FFF;
    }

    /**
     * Fn 那一段（协议 21 起）的标志位：id 的高 8 位正好是 0x40 的都是 Fn 那一段——Fn 键自己（{@link #FN_KEY} / {@link #FN_GLOBE_KEY}，
     * 第 16–23 位是 0）和带 Fn 层的键（{@link #fnLayerKey}，第 16–23 位是 Fn 层的码，不是 0）。
     */
    public static final int FN_FLAG = 0x40000000;
    /**
     * 实体键盘 75% / 65% 的 Fn 键（Windows 模式），协议 22 起的规矩（KeyHolds）：按住 Fn 再按别的键 = 那几下出 Fn 层（组合，松手回来、不锁）；
     * 单独点一下 = 单次锁（下一个普通键出 Fn 层、那个键松开时解开，修饰键不消耗它）；单独按住 0.4 秒 = 永久锁（Fn Sticky，照 ThinkPad BIOS 的叫法；协议 24 起 FnLock 指 F 行翻转，见 FN_LOCK_KEY；没有修饰键那种 1 秒上限，
     * 之后按多少个键都出 Fn 层）；锁着（单次或永久）时单独再按一下 Fn = 松手解开、这一下不再锁；锁着时把 Fn 当组合用（按住 Fn 打别的键），永久锁照旧，单次锁被那个键用掉。
     * 不发 HID（真键盘的 Fn 也不发），只改带 Fn 层的键按下时发哪个码。（协议 21 时同修饰键：单独按住 0.4–1 秒锁一个键、单点什么都不做。）
     */
    public static final int FN_KEY = FN_FLAG;
    /**
     * Mac 模式的 fn🌐（75% / 65% / 苹果紧凑）：组合、按住 0.4 秒永久锁、锁着时单独按一下解开同 {@link #FN_KEY}；不同的是单独点一下 = 🌐
     * （报 {@link #GLOBE_BUTTON_ID} 的点击，和 🌐 键走同一条路）、没有单次锁，锁着时单独按一下只解开、不出 🌐。
     */
    public static final int FN_GLOBE_KEY = FN_FLAG | 1;
    /** 🌐 的按钮编号（App 的 🌐 键、实体键盘 Mac 模式的 🌐，KeyRows.GLOBE_BUTTON_ID 就是它）：分流对 fn🌐 的单点报这个编号的 BUTTON_TAP。 */
    public static final int GLOBE_BUTTON_ID = 302;

    /**
     * 带 Fn 层的键的 id：FN_FLAG | fn << 16 | base。base = 底层的键 id（16 位，现在都是用法码，可以是修饰键：Windows 模式右 Ctrl 的 Fn 层是 Menu），
     * fn = Fn 层的用法码（1–0xFF）。按下那一刻 Fn 按着或锁着就发 fn、不然发 base，记下来，松开松的是按下时的那个（KeyHolds）。
     */
    public static int fnLayerKey(int base, int fn) {
        return FN_FLAG | (fn & 0xFF) << 16 | (base & 0xFFFF);
    }

    public static boolean isFnLayerKey(int id) {
        return (id & 0xFF000000) == FN_FLAG && ((id >>> 16) & 0xFF) != 0;
    }

    /** Fn 键自己（{@link #FN_KEY} / {@link #FN_GLOBE_KEY}）。 */
    public static boolean isFnKey(int id) {
        return id == FN_KEY || id == FN_GLOBE_KEY;
    }

    public static int fnBase(int id) {
        return id & 0xFFFF;
    }

    public static int fnCode(int id) {
        return (id >>> 16) & 0xFF;
    }

    /**
     * F 行键（协议 24 起，实体键盘 F1–F12 带第二功能、Esc 带 F 行翻转开关）的类型，放在 Fn 那一段的第 24–29 位（{@link #fnLayerKey} 那几位是 0）：
     * FROW_KEY = 第二功能是键盘键 / 组合键（第二功能 = 用法码 | 自带修饰键 << 8，同 REGION_KEY 的 id）、FROW_CONSUMER = 多媒体页的码、
     * FROW_SYSTEM = 系统键那一组（Generic Desktop 的 System Control）的码、FROW_FN_LOCK = Esc 的 F 行翻转开关（第二功能填 0）。
     * 别往大里用：类型到 0x3F 时 id 到 0x7FFFFFFF，加上 KEY_ID_BASE 会溢出成负数（ServerPartsTest 锁住"KEY_ID_BASE + id 是正数"）。
     */
    public static final int FROW_KEY = 1, FROW_CONSUMER = 2, FROW_SYSTEM = 3, FROW_FN_LOCK = 4;

    /**
     * F 行键的 id：FN_FLAG | type << 24 | second << 8 | base。base = 底层的用法码（F1–F12 是 0x3A–0x45、Esc 0x29，8 位），second = 第二功能（16 位，
     * 见 {@link #FROW_KEY}）。哪一面在按下那一刻定（KeyHolds：类型 1–3 按"Fn 起没起作用 ^ F 行翻转"，类型 4 按"Fn 起没起作用"），记下来，
     * 松开松的是按下时定的那个。翻转状态在 KeyHolds 里、不进 id：面板自己收触摸那条路松开时会重新算 id，按着时翻了的话 id 对不上就卡键。
     * 第二功能是"无"的 F 键不用它，报普通的用法码（FKeyFunctions.regionId）。
     */
    public static int frowKey(int type, int base, int second) {
        return FN_FLAG | (type & 0x3F) << 24 | (second & 0xFFFF) << 8 | (base & 0xFF);
    }

    /** F 行键（类型 1–4）。和合并键、Fn 键自己、带 Fn 层的键都不重叠（ServerPartsTest 锁住）。 */
    public static boolean isFrowKey(int id) {
        int type = (id >>> 24) & 0x3F;
        return (id & 0xC0000000) == FN_FLAG && type >= FROW_KEY && type <= FROW_FN_LOCK;
    }

    public static int frowType(int id) {
        return (id >>> 24) & 0x3F;
    }

    public static int frowBase(int id) {
        return id & 0xFF;
    }

    public static int frowSecond(int id) {
        return (id >>> 8) & 0xFFFF;
    }

    /** Esc 的 F 行翻转开关（FnLock）：Fn 起作用时按它 = 翻转 F 行平时出哪一面（不发 HID、震一下），不起作用时就是 Esc。 */
    public static final int FN_LOCK_KEY = frowKey(FROW_FN_LOCK, 0x29, 0);
    /**
     * 键盘面板的整块范围（不是键，id 不用、填 0），排在它的键后面。按在这里却没落在任何键上（键间的缝、
     * 第二行两头的空位、外圈边距）时，分给范围里最近的键（TouchRouter.nearestKey）。旧服务不认识它，
     * 会当成按钮 id 0（按钮条的"退出"），所以加它时协议升到了 14。
     */
    public static final int REGION_KEYBOARD = 4;
    /**
     * 小红点（模拟 ThinkPad TrackPoint，键盘页 ⋯ 菜单里"指点"选小红点时才有）：id = {@link #STICK_ID}。
     * 按住往哪边推光标就往哪边走、推得越远越快（TouchRouter → TrackPoint，按节拍发）。协议 18 起。
     * 报的是正方形，按得到的是它的内切圆（TouchRouter.Region.contains，2026-11-01 起；协议没变，旧服务照正方形认，要重启服务）。
     */
    public static final int REGION_STICK = 5;
    /**
     * 三个鼠标键（选小红点时底行、快捷行让出的空位里，或者触控板上沿 / 下沿的三键条）：id = {@link #MOUSE_BUTTON_ID_BASE} | 鼠标键位
     * （BUTTON_LEFT / RIGHT / MIDDLE）。左 / 右键按住就按住；中键按着时推红点 / 单指划触控板 = 滚动，一格都没滚过就松开 = 中键单击。
     * 左键和 REGION_HOLD_LEFT、CMD_HOLD_LEFT 共用服务里的计数。协议 18 起。
     */
    public static final int REGION_MOUSE_BUTTON = 6;
    /**
     * 手柄页的控件（协议 19 起）：id 见 {@link #gamepadId}。种类在 id 里（单键 / 面键簇 / 十字键 / 摇杆），服务不用另收一张控件表。
     * 面键簇的范围（种类 {@link #GP_KIND_CLUSTER}）排在它的成员（种类单键、整个落在范围里）前面：按在范围里一律先命中簇，
     * 成员只给几何（圆心、半径），永远不会被直接命中（同键盘范围和键）。旧服务不认识的区域类型什么都不做。
     */
    public static final int REGION_GAMEPAD = 7;
    /** 手柄控件编号段：0x4000 | 种类 << 10 | 副本 << 8 | 参数（最大 0x4FFF）。 */
    public static final int GAMEPAD_ID_BASE = 0x4000;
    /** 单键（参数 = 输出码 GP_*）、面键簇（参数 = 第几簇）、十字键（参数 = 方向数 4 / 8）、摇杆（参数 0 左、1 右）。 */
    public static final int GP_KIND_BUTTON = 0, GP_KIND_CLUSTER = 1, GP_KIND_DPAD = 2, GP_KIND_STICK = 3;
    /**
     * 手柄的输出码（按位置，不按键帽上的字）：0–14 = 报文里第 1–15 键（Linux BTN_SOUTH… / 安卓 Generic.kl 的 BUTTON_A…），
     * 16 / 17 = 左 / 右扳机（Brake / Accelerator 轴，按下 = 255）。第 4 键是西（Xbox X）、第 5 键是北（Xbox Y）：
     * 安卓 Generic.kl 把 0x133 映成 BUTTON_X、0x134 映成 BUTTON_Y，Moonlight、SDL 都这么认。键 3、6、9、10 空着。
     */
    public static final int GP_SOUTH = 0, GP_EAST = 1, GP_WEST = 3, GP_NORTH = 4, GP_L1 = 6, GP_R1 = 7,
            GP_SELECT = 10, GP_START = 11, GP_HOME = 12, GP_L3 = 13, GP_R3 = 14, GP_LT = 16, GP_RT = 17;
    /**
     * N64 的四颗 C 键：右摇杆推满上 / 右 / 下 / 左（Z / Rz 轴，模拟器一般把 C 键当右摇杆）。只在 App 和服务的 GamepadTouch 里换成轴，
     * 报文、描述符、协议都不变（不升协议：旧服务不认识这几个输出码时什么都不发）。18–23 空着（以后"十字键拆成四颗键"用）。
     */
    public static final int GP_C_UP = 24, GP_C_RIGHT = 25, GP_C_DOWN = 26, GP_C_LEFT = 27;
    /** 输出码的个数（数组大小）。 */
    public static final int GP_OUTPUTS = 28;

    /** 手柄控件的 id：kind = GP_KIND_*，copy = 同一个输出的第几个键帽（0–3），param 见 GP_KIND_*。 */
    public static int gamepadId(int kind, int copy, int param) {
        return GAMEPAD_ID_BASE | (kind & 3) << 10 | (copy & 3) << 8 | (param & 0xFF);
    }

    public static boolean isGamepadId(int id) {
        return id >= GAMEPAD_ID_BASE && id < GAMEPAD_ID_BASE + 0x1000;
    }

    public static int gamepadKind(int id) {
        return (id >> 10) & 3;
    }

    public static int gamepadParam(int id) {
        return id & 0xFF;
    }

    /** 三个鼠标键的按钮编号基数（| 键位）；要小于 KEY_ID_BASE（App 把 ≥ KEY_ID_BASE 的编号都当键盘键）。 */
    public static final int MOUSE_BUTTON_ID_BASE = 0x800;
    /** 小红点的按钮编号（只用来让 App 画按下的样子）。 */
    public static final int STICK_ID = 0x810;
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

    /**
     * 连上后服务先发一条：version:u8, ok:u8（HELLO_*）, message:UTF, apk:UTF。
     * apk（协议 16 起）：服务是从哪个 APK 起的（它的 CLASSPATH），不知道时为空。App 拿它和自己的 sourceDir 比：
     * 每次安装 APK 都换目录，不一样 = App 更新后服务还跑着旧代码（SelfStart.onServiceConnected）。
     * 读的一方先核对 version 再读 apk（旧服务没有这一段，版本不对就别往下读）。
     */
    public static final int MSG_HELLO = 0x80;
    /** 服务出错，message 说明哪里坏了。 */
    public static final int HELLO_FAILED = 0;
    /** 正常；message 不空时是附带的提醒（比如按键注入暂时不可用）。 */
    public static final int HELLO_OK = 1;
    /** 另一个连接正在用服务、而且还在说话：服务发完这条就关掉这条连接，App 照常每秒重试。message 为空。 */
    public static final int HELLO_BUSY = 2;
    /** 震动：kind:u8（GestureEngine.HAPTIC_*；协议 18 起多了惯性的"放开" 6、"刹住" 7；协议 25 起多了"按住到点" 8、"拖动被取消着结束" 9）。 */
    public static final int MSG_HAPTIC = 0x81;
    /** 三指 / 四指手势：fingers:u8, kind:u8（GestureEngine.GESTURE_*）。 */
    public static final int MSG_GESTURE = 0x82;
    /** 按钮：id:i32, action:u8（BUTTON_DOWN / BUTTON_UP / BUTTON_TAP；协议 25 起松开可能带 {@link #BUTTON_CANCELED}）。 */
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
    /** 分屏 / 小窗时没法把另一边的触摸注入回系统：不独占，触摸交还系统（触控板暂停）；detail = 原因原文。 */
    public static final int GRAB_NO_PASSTHROUGH = 8;
    /**
     * 分屏 / 小窗时系统眼里这块触摸屏还有手指按着（多半按在另一边的应用上）：等全部抬起再接管，这之前触摸归系统、触控板暂停
     * （这时独占，系统永远等不到那根手指抬起）。
     */
    public static final int GRAB_WAIT_LIFT = 9;
    /**
     * 修饰键的状态：latched:u8, held:u8, armed:u8（HID 修饰键位：1 Ctrl、2 Shift、4 Alt、8 Win，右边四个 << 4）, fn:u8（{@link #FN_LATCHED} 等位）。
     * latched = 锁住的（单独按住 0.4–1 秒松手锁，打完下一个键自动解开）；held = 现在有手指按着的；armed = 单独按住到了 0.4 秒、"现在松手就锁"的。
     * App 照 latched | armed 画高亮、照 latched | held 换键帽上的字。协议 20 起三个字节（以前只有 latched 一个）；
     * 协议 21 起第四个字节是 Fn 的状态（实体键盘 75% / 65% / 苹果紧凑）：App 照单次锁画 Fn 键的高亮、照永久锁 | 快永久锁了画强调色实底，
     * 照单次锁 | 永久锁 | 按着把带 Fn 层的键帽整片换成 Fn 层的字。协议 22 起多了 {@link #FN_LOCKED}，FN_LATCHED / FN_ARMED 的意思跟着 Fn 的新规矩变了（KeyHolds）。
     * 协议 24 起多了 {@link #FROW_FLIP}：App 照它画 F 行亮哪一面、存进设置（和设置页"平时按出"是同一份状态）。
     */
    public static final int MSG_MODIFIERS = 0x85;
    /**
     * MSG_MODIFIERS 第四个字节的位（协议 22 起）：FN_LATCHED = 单次锁（Windows 模式单点 Fn，只管下一个普通键）、FN_HELD = 有手指按着、
     * FN_ARMED = 单独按住到了 0.4 秒、"现在松手就永久锁"、FN_LOCKED = 永久锁（Fn Sticky，再单独按一下 Fn 才解开；不是 F 行翻转 FnLock，那个是 FROW_FLIP）。
     * 协议 21 时 1 是"锁住、只管下一个键"、4 是"0.4–1 秒、现在松手就锁"，没有 8。
     */
    public static final int FN_LATCHED = 1, FN_HELD = 2, FN_ARMED = 4, FN_LOCKED = 8;
    /**
     * MSG_MODIFIERS 第四个字节的位（协议 24 起）：F 行翻转着（"平时按出第二功能"，Fn 起作用时出 F1–F12）。只翻 F 行，Fn 层别的键只看 Fn 起没起作用。
     * Fn+Esc 翻它（KeyHolds），设置页也能改（经 CMD_CONFIG 的 frowFlip）；它是设置、不是锁：releaseAll、CMD_UNLATCH 都不清。
     */
    public static final int FROW_FLIP = 16;
    /**
     * 转发的鼠标报文（relay 模式）：buttons:u8, dx:i16, dy:i16, wheel:i16, pan:i16。wheel / pan 是 1/120 格（Hid.WHEEL_UNIT），
     * App 按蓝牙主机设的倍率换算成计数（Hid.WheelOut）。协议 18 以前是 i8 的格数。
     */
    public static final int MSG_MOUSE = 0x86;
    /** 转发的键盘报文（relay 模式）：HID 键盘报文原样 8 字节（修饰键位、保留、6 个键）。 */
    public static final int MSG_KEYBOARD = 0x87;
    /** 实体按键（服务已判断好）：key:u8（HARD_VOL_UP / HARD_VOL_DOWN）, event:u8（HARD_TAP / HARD_HOLD_START / HARD_HOLD_END / HARD_HOLD_CANCEL）。 */
    public static final int MSG_HARD_KEY = 0x88;
    /** 和 HardKeys.VOL_UP / VOL_DOWN 一致。 */
    public static final int HARD_VOL_UP = 0;
    public static final int HARD_VOL_DOWN = 1;
    public static final int HARD_TAP = 0;
    public static final int HARD_HOLD_START = 1;
    public static final int HARD_HOLD_END = 2;
    /**
     * 按住被取消着结束（协议 25 起）：服务放开实体键（releaseAll：停止拦截、通话、心跳超时）、电源键组合交还系统时补的结束。
     * App 照 HARD_HOLD_END 收尾，只是不出松开声（同 {@link #BUTTON_CANCELED}）。以前这几种也报 HARD_HOLD_END。
     */
    public static final int HARD_HOLD_CANCEL = 3;
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
    /**
     * 手柄报文（协议 19 起）：flags:u8, report[13]。桌面模式下服务分流出的手柄状态（App 拿它画按下的键、十字键、摇杆）；
     * flags bit0 = relay（第三批蓝牙用，现在恒 0），bit7 = 建虚拟手柄失败（这一条的 report 全零，原因在服务日志里）。
     */
    public static final int MSG_GAMEPAD = 0x8A;
    public static final int GAMEPAD_FLAG_RELAY = 1, GAMEPAD_FLAG_FAILED = 0x80;
    /**
     * Caps 的显示该翻一下了（协议 20 起）：sent:u8（1 = 这次给主机发了 Caps Lock（点一下），0 = 只切显示（按住 0.4 秒））。
     * 显示状态只有 App 一份（主机的大写锁定跨连接都在，服务的分流和 App 的本地分流各自 releaseAll，放进哪个都会丢），服务只报"翻一下"。
     */
    public static final int MSG_CAPS = 0x8B;
    /**
     * 转发的多媒体 / 系统键（relay 模式，协议 24 起）：page:u8（{@link #PAGE_CONSUMER} / {@link #PAGE_GENERIC_DESKTOP}）, usage:u16（0 = 全松开）。
     * 照真键盘的报告：每一页一次只报一个码（按着的码变了才发）；App 交给 BtHid 的报告 3（多媒体）/ 报告 6（系统键）。
     */
    public static final int MSG_CONSUMER = 0x8C;
    /** HID 的用法页：多媒体（Consumer）、Generic Desktop（系统键那一组 System Control 在这一页）。 */
    public static final int PAGE_CONSUMER = 0x0C, PAGE_GENERIC_DESKTOP = 0x01;

    public static final int BUTTON_DOWN = 0;
    /** 松开但不算点击（手指滑出了按钮，或被取消）。 */
    public static final int BUTTON_UP = 1;
    /** 在按钮里按下又松开。 */
    public static final int BUTTON_TAP = 2;
    /**
     * 松开带的"被取消"标记（协议 25 起）：BUTTON_UP / BUTTON_TAP 再或上这一位 = 这根手指是被取消的（手掌、丢数据、服务释放触摸屏时补报的抬起、
     * releaseAll / 换参数收尾），按下从不带。只加信息、不改意思：被取消的手指松开时还在按钮里照样是 BUTTON_TAP（和以前一样，TouchRouter.up），
     * App 收到先去掉这一位（{@link #buttonAction}）再照旧处理；只有声音看它（被取消的松开不出声，SoundEvents）。
     */
    public static final int BUTTON_CANCELED = 0x80;

    /** 去掉 {@link #BUTTON_CANCELED} 那一位：BUTTON_DOWN / BUTTON_UP / BUTTON_TAP。 */
    public static int buttonAction(int action) {
        return action & ~BUTTON_CANCELED;
    }

    /** 这次松开是被取消的（{@link #BUTTON_CANCELED}）。 */
    public static boolean buttonCanceled(int action) {
        return (action & BUTTON_CANCELED) != 0;
    }

    // ═══════════ 鼠标按键位 ═══════════

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_RIGHT = 2;
    public static final int BUTTON_MIDDLE = 4;
}
