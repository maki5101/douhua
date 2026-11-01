package dev.douhua;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * 主界面有两屏：
 * - 触控板屏：默认只有触控板 + 四个角上的小按钮（可自定义）+ 顶上正中的 ⋯（键盘 / 设置 / 旋转 / 退出），可以展开数字键盘；
 *   "自定义按键"（原来的左侧按钮条，设置里打开，默认关）开着时左边多一竖条按钮（左手拇指够得着）；
 * - 键盘屏：整屏是外接键盘（大小位置可调），键盘旁边空出来的地方是小触控板，⋯ 同样在它顶上正中。
 * 另有两页独立的：空中鼠标页（蓝牙模式）、手柄页（{@link GamepadScreen}，桌面模式：服务的 uhid 手柄；⋯ 按住 0.6 秒开菜单）。
 * 几屏的 ⋯ 菜单是同一份（{@link MenuLayer}，盖在最上面）。颜色跟设置里的主题（默认纯黑，只有按钮和触控板的暗灰边框）。
 *
 * 两种连接方式，界面、手势、动作都一样，区别只在触摸谁来收、键盘鼠标发到哪：
 * - 桌面模式：豆花在前台时，服务独占这块屏的触摸屏，按这里报上去的区域位置分流（{@link TouchRouter}），
 *   鼠标键盘发给虚拟 USB 设备，再把按钮事件、震动、三指四指手势发回来，这里负责画面和执行动作。
 *   服务没接管时（"贴字"面板打开、服务没连上），按钮和键按普通方式响应触摸。
 * - 蓝牙：鼠标键盘经 {@link BtHid} 发给配对的电脑 / 平板 / 电视。服务开着时和桌面模式一样由服务独占触摸屏、分流
 *   （系统的下拉通知栏、上滑回桌面、侧滑返回都碰不到），只是报文发回这里（relay），由这里从蓝牙发出去；
 *   服务没开或没接管时，这里自己收触摸（{@link #dispatchTouchEvent}），交给同一个 TouchRouter 分流。
 *
 * 窗口平时设成"不可获得焦点"，外接屏上的窗口不会因为碰了手机屏而变成未激活。"贴字"面板打开时、没有外接屏时允许，
 * 见 {@link #updateFocusable}。
 */
public final class MainActivity extends Activity
        implements Link.Listener, KeyboardPanel.Listener, KeyboardScreen.Listener, DisplayManager.DisplayListener,
        BtHid.Listener, AirMouse.Host {

    /** 主界面触控板四角的小按钮（下标同 Prefs.corner：左上、右上、右下、左下，都可自定义，一直显示；以前左下是固定的 ☰）。 */
    private static final int CORNER_BASE_ID = 500;
    /** 空中鼠标页上的按钮（其它按钮编号：0–11 自定义按键、300–303 键盘的特殊键、400–417 ⋯ 和菜单、500–503 角上、600 起键盘最下面一排、0x800 起鼠标键 / 小红点、0x4000 起手柄控件、0x10000 起键）。 */
    private static final int AIR_EXIT_ID = 700;
    private static final int AIR_CENTER_ID = 701;
    /** 空中鼠标页的圆钮：点一下（不到 HardKeys.HOLD_MS 就松开）回正中，按住移动。 */
    private static final int AIR_ROUND_ID = 702;
    /** 空中鼠标页上可设定的大按钮：710 + 第几个（2 × Prefs.AIR_ROWS_MAX 个以内）。 */
    private static final int AIR_SLOT_BASE_ID = 710;
    /** 退出空中鼠标要按住这么久：拿手机当遥控器时手掌容易误碰屏幕。 */
    private static final long AIR_EXIT_HOLD_MS = 600;
    /** "滚轮向上 / 向下"按住：先滚一格，停 WHEEL_DELAY_MS 再每 WHEEL_REPEAT_MS 匀速滚一格（像按住键盘的键连发）。 */
    private static final long WHEEL_DELAY_MS = 250, WHEEL_REPEAT_MS = 80;
    private static final int[] CORNER_GRAVITY = {
            android.view.Gravity.TOP | android.view.Gravity.LEFT, android.view.Gravity.TOP | android.view.Gravity.RIGHT,
            android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT, android.view.Gravity.BOTTOM | android.view.Gravity.LEFT,
    };
    /** 键盘屏：小触控板顶上正中的 ⋯（触控板没摆出来时 ⋯ 是功能行上的键，KeyboardPanel.MENU_BUTTON_ID）。 */
    private static final int KB_MENU_ID = 400;
    /** 键盘屏 ⋯ 菜单才有的四项：收起键盘、调整大小、布局（自动 / 分裂 / 合并轮换）、指点（触控板 / 小红点 / 关轮换）。 */
    private static final int KB_CLOSE_ID = 401;
    private static final int KB_ADJUST_ID = 402;
    private static final int KB_LAYOUT_ID = 403;
    private static final int KB_POINTER_ID = 404;
    /** 菜单开着时盖住整页的"点外面关菜单"（排在菜单项、菜单底板后面）。 */
    private static final int MENU_OUTSIDE_ID = 405;
    /** 调整模式那条栏：完成、恢复默认。 */
    private static final int KB_DONE_ID = 406;
    private static final int KB_RESET_ID = 407;
    /** 菜单底板（项与项之间、四周的边）：按了什么都不做，不当"点外面"。 */
    private static final int MENU_PANEL_ID = 408;
    /** 主界面触控板顶上正中的 ⋯。 */
    private static final int PAD_MENU_ID = 409;
    /** 主界面 ⋯ 菜单才有的"键盘"（打开键盘屏），两屏都有的设置、旋转、退出（同 Actions 的 settings / rotate / exit）。 */
    private static final int MENU_KEYBOARD_ID = 410;
    private static final int MENU_SETTINGS_ID = 411;
    private static final int MENU_ROTATE_ID = 412;
    private static final int MENU_EXIT_ID = 413;
    /** 主界面、键盘屏 ⋯ 菜单里的"手柄"（打开手柄页，只在桌面模式显示）。 */
    private static final int MENU_GAMEPAD_ID = 414;
    /** 键盘屏 ⋯ 菜单最上面的"键盘：豆花 / 实体（…）"：点一下在豆花布局和实体键盘之间换，菜单不关（第 1B 批，2026-11-01）。 */
    private static final int MENU_BOARD_ID = 418;
    /** 手柄页 ⋯ 菜单才有的：预设（轮换）、返回触控板。 */
    private static final int GP_PRESET_ID = 415;
    private static final int GP_BACK_ID = 416;
    /** 手柄页顶上正中的 ⋯：按住 GP_MENU_HOLD_MS 才开菜单（游戏里拇指乱碰，单击一开菜单区域就只剩菜单）。 */
    private static final int GP_MENU_ID = 417;
    private static final long GP_MENU_HOLD_MS = AIR_EXIT_HOLD_MS;
    /** 菜单在哪一屏（{@link #onMenuScreen}）。 */
    private static final int SCREEN_MAIN = 0, SCREEN_KEYBOARD = 1, SCREEN_GAMEPAD = 2;
    /**
     * ⋯ 菜单里的项，按显示的顺序（常用的在前、退出最后）：主界面 = 键盘、手柄、设置、旋转、退出；
     * 键盘屏 = 键盘（豆花 / 实体）、收起键盘、调整大小、布局（只在显示豆花布局时）、指点、手柄、设置、旋转、退出；手柄页 = 预设、返回触控板、设置、旋转、退出。
     * 不是这一屏的项打开菜单时 GONE（{@link #onMenuScreen}）；"手柄"只在桌面模式有。
     */
    private static final int[] MENU_IDS = {
            MENU_KEYBOARD_ID, MENU_BOARD_ID, KB_CLOSE_ID, KB_ADJUST_ID, KB_LAYOUT_ID, KB_POINTER_ID, MENU_GAMEPAD_ID, GP_PRESET_ID, GP_BACK_ID,
            MENU_SETTINGS_ID, MENU_ROTATE_ID, MENU_EXIT_ID,
    };
    private static final int[] MENU_LABELS = {
            R.string.act_keyboard, R.string.kbd_menu_douhua, R.string.kb_close, R.string.kb_adjust, R.string.kb_layout_auto, R.string.kb_pointer_pad,
            R.string.act_gamepad_s, R.string.act_gamepad_s, R.string.gp_exit,
            R.string.act_settings, R.string.act_rotate, R.string.act_exit,
    };
    /** 数字键盘：标签和 HID 用法码。 */
    private static final String[][] NUMPAD_LABELS = {
            {"7", "8", "9", "⌫"},
            {"4", "5", "6", "+"},
            {"1", "2", "3", "-"},
            {"0", ".", "Tab", "↵"},
    };
    private static final int[][] NUMPAD_USAGES = {
            {0x24, 0x25, 0x26, 0x2A},
            {0x21, 0x22, 0x23, 0x57},   // + 用小键盘的加号，不用按 Shift
            {0x1E, 0x1F, 0x20, 0x2D},
            {0x27, 0x37, 0x2B, 0x28},
    };

    private final Link link = new Link(this, this);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            link.heartbeat();
            checkWindowMoved();   // 兜底：万一挪窗口不走布局遍历，最多晚 1 秒对上
            main.postDelayed(this, Proto.HEARTBEAT_INTERVAL_MS);
        }
    };
    private Prefs prefs;
    private Haptics haptics;
    private DisplayManager displays;
    private float density;

    /** 整个界面的根（页面底色跟主题）。 */
    private FrameLayout root;
    /** 界面现在是按哪套主题、按键边框开没开上的色（{@link #applyTheme}：设置里改了才重新上色）。 */
    private Theme shownTheme;
    private boolean shownBorders;
    private TrackpadView pad;
    /** 四角的小按钮（下标同 Prefs.corner）。 */
    private final List<TextView> cornerButtons = new ArrayList<>();
    /** 主界面触控板顶上正中的 ⋯（PadStack 摆）。 */
    private TextView padMenuButton;
    /** 触控板背景图（设置 → 外观）：两块触控板共用一份，后台解码。 */
    private PadImage padImage;
    /** 角上的小按钮现在按"在图上"上的样子吗（垫不透明的触控板底色）：图来了 / 没了才重新上样子。 */
    private boolean cornersOnImage;
    private LinearLayout right;
    private LinearLayout mainRow;
    private KeyboardScreen keyboardScreen;
    private TrackpadView miniPad;
    /** 键盘页的小红点、三个鼠标键（选小红点时键盘最下面那排或者小触控板的三键条，同一组）；主界面触控板的三键条和装它的那层。 */
    private TrackPointView kbStick;
    private MouseKeys kbMouseKeys;
    private MouseKeys padMouseKeys;
    private PadStack padStack;
    /** 分流报来按着几根手指的鼠标键（下标 0 左、1 右、2 中）：两组三键条一起画按下的样子。 */
    private final int[] mouseKeysHeld = new int[3];
    /** 键盘屏触控板顶上的 ⋯、调整模式那条栏的两个按钮。 */
    private TextView kbMenuButton;
    private TextView kbDone, kbReset;
    /** ⋯ 菜单（两屏共用一份，根视图最上面一层）：底板、项（下标同 MENU_IDS）、盖住整页的"点外面"。 */
    private MenuLayer menuLayer;
    private View menuOutside;
    private final List<TextView> menuItems = new ArrayList<>();
    private final int[] anchorLoc = new int[2];
    /** 菜单开着时返回（手势 / 导航键）先关菜单。 */
    private final OnBackInvokedCallback closeMenu = () -> setMenu(false);
    private boolean menuBack;
    /** 键盘最下面一排的动作（下标 = 位置）。 */
    private final List<Actions.Action> keyboardRow = new ArrayList<>();
    /** "自定义按键"（左边一列，原来的左侧按钮条）：设置里打开才显示，见 {@link #applyCustomKeys}。 */
    private ButtonStrip strip;
    private LinearLayout textBar;
    private EditText input;
    /**
     * 输入框原来的光标、选择把手（左 / 中 / 右）和选中高亮：窗口是系统深色主题，这些是它的青绿色（#80CBC4），
     * 在浅色主题的米色底上看不清，所以纯黑以外按主题重新着色（{@link #tintInput}）；换回纯黑时装回原来的，和以前一模一样。
     */
    private Drawable inputCursor, inputHandleLeft, inputHandle, inputHandleRight;
    private int inputHighlight;
    /** 贴字面板上的按钮（发送、⌫、↵、Tab、收起），换主题时重新上色。 */
    private final List<TextView> textBarButtons = new ArrayList<>();
    private LinearLayout numpad;
    private KeyboardPanel keyboard;
    /** 实体键盘那块面板（第 1B 批）和"用哪块、实体键盘是哪一块"的设置（纯 Java 的 Boards，跑在 Prefs 上）。 */
    private FixedKeyboardView fixedKeyboard;
    private Boards boards;
    /** 自定义按键，下标就是区域编号（= Prefs.slot 的下标）。 */
    private final List<TextView> stripButtons = new ArrayList<>();
    private final List<TextView> numpadButtons = new ArrayList<>();
    /** 数字键盘上 View 自己收触摸时正按着的键（桌面模式服务没接管时）：服务接管那一刻要替它们松开，见 {@link #releaseNumpad}。 */
    private final List<View> numpadHeld = new ArrayList<>();
    private final List<Integer> numpadUsages = new ArrayList<>();

    private boolean resumed;
    private int targetDisplay = Display.INVALID_DISPLAY;
    private String targetName = "";
    private boolean linkOk;
    /** 这次回到前台后连上过服务没有：一次都没连上才算"没有服务"，蓝牙模式下改由 App 自己请求方向。 */
    private boolean linkSeen;
    /** App 自己向系统请求的方向（Surface.ROTATION_*，-1 = 没请求），见 {@link #requestOrientationSelf}。 */
    private int requestedRotation = -1;
    private String linkMessage = "";
    /** 服务在运行却没响应（卡住了）：蓝牙模式下也要提示，用户得去重启它。 */
    private boolean linkStuck;
    /** 手机自己拉起服务（无线调试连本机 adbd）。 */
    private SelfStart selfStart;
    /** 这次回到前台后已经叫过 SelfStart.autoStart：连不上服务时每秒报一次，只叫一次。 */
    private boolean autoStartAsked;
    /** 这次按下落在可点的状态字上：整次手势归它，抬起时还在状态字上就打开设置页。 */
    private boolean statusPress;
    private boolean grabbed;
    private String grabMessage = "";
    /** 豆花的窗口占满整块屏（不在分屏 / 小窗里），pushLayout 时更新；不占满时不让服务独占触摸屏，见 {@link #serverActive}。 */
    private boolean windowFull = true;
    /**
     * 豆花是系统里最上面那个 resumed 的 Activity（onTopResumedActivityChanged）。别的应用以弹出式小窗浮在全屏豆花上面时
     * 豆花照样 resumed、窗口也照样占满，只有这个会变假。只在蓝牙模式、没接外接屏时用，见 {@link #ownsScreen}。
     */
    private boolean topResumed = true;
    /**
     * 报区域那一刻窗口左上角在屏幕上的位置。本地分流按它把窗口坐标换成屏幕坐标：和区域用的是同一时刻的位置，
     * 小窗被拖走（只挪位置不改大小，不会重新布局、不重报区域）后触点和区域照样对得上。
     */
    private final int[] layoutOrigin = new int[2];
    /**
     * 屏幕边缘留白（设置 → 外观的"左右""上下"，dp）和现在各页往里收了多少（左 / 上 / 右 / 下，像素），见 {@link #updateEdgeMargins}。
     */
    private int edgeLrDp = EdgeMargin.DEFAULT_DP, edgeTbDp = EdgeMargin.DEFAULT_DP;
    private final int[] pageMargins = new int[4];
    /** 上次报给服务的手势带宽度（左 / 上 / 右 / 下，像素，上边含小窗的标题栏），{@link #dispatchTouchEvent} 认"窗口范围过时了"用。 */
    private final int[] reportedBand = new int[4];
    private final int[] tmpLoc = new int[2];
    // ── 蓝牙模式 ──
    private boolean btMode;
    private BtHid bt;
    private boolean btAcquired;
    private int btState = BtHid.STATE_OFF;
    /** 蓝牙连着的是苹果设备（见 updateHostKind）。 */
    private boolean appleHost;
    // ── 空中鼠标（只在蓝牙模式） ──
    private AirMouse air;
    private DebugMoves debugMoves;
    /** 键盘触点调试日志开着（只在可调试包，租期见 DebugMoves.keyLogUntil）。 */
    private boolean keyLogOn;
    private final Runnable keyLogCheck = this::onKeyLogChanged;
    /** 空中鼠标模式开着（只在内存里，进程没了就关）。 */
    private boolean airOn;
    private FrameLayout airOverlay;
    // ── 手柄页（只在桌面模式） ──
    /** 手柄页开着（只在内存里，同 airOn）。 */
    private boolean gamepadOn;
    private GamepadScreen gamepadScreen;
    /** 手柄页顶上正中的 ⋯。 */
    private TextView gpMenuButton;
    /** 服务建虚拟手柄失败了（MSG_GAMEPAD 带失败位）：状态字提示，断开重连再说。 */
    private boolean gamepadFailed;
    private final Runnable gpMenuLater = () -> {
        haptics.play(GestureEngine.HAPTIC_CLICK);   // 按住到点、菜单打开："嗒"（同空中鼠标页的按住退出）
        Style.bare(gpMenuButton, false);
        setMenu(true);
    };
    private TextView airStatus;
    private TextView airState;
    private TextView airCenter;
    private TextView airExit;
    private LinearLayout airGrid;
    private final List<TextView> airSlots = new ArrayList<>();
    private RoundPad airRound;
    /** 圆钮在第几、几 + 1 行之间（Prefs.airRoundAt）。 */
    private int airRoundAt = 1;
    private boolean airRoundPressed;
    /** 圆钮点一下锁定了移动（再点一下解锁）。 */
    private boolean airRoundLocked;
    /** 按住左键后等着开始跟随的（来源 → 到点开始跟随）。 */
    private final java.util.Map<Integer, Runnable> leftFollowPending = new java.util.HashMap<>();
    private static final long LEFT_FOLLOW_DELAY_MS = 200;
    /** 按着的"左键"有几个（屏幕按钮、音量键）：松开一个时别的还按着，就还在拖、接着嗡。 */
    private int leftHolds;
    /** onStop 之后：读线程在断开前塞进主线程的转发报文丢掉，免得把刚松开的键又按下去。 */
    private boolean stopped;
    private long airRoundDownAt;
    /** 移动"颗粒"开没开（设置里的开关；空中鼠标那一路在 airHaptic 里按它过滤）。 */
    private boolean textureOn;
    /** 滚动"咔嗒"开没开：滚轮按钮滚一格也咔嗒一下，和触控板滚动一样。 */
    private boolean scrollTicksOn;
    // ── 滚轮按钮按住时连续滚 ──
    private int wheelDir;
    private int wheelSource = Integer.MIN_VALUE;
    private final Runnable wheelRepeat = new Runnable() {
        @Override
        public void run() {
            if (wheelDir == 0) return;
            wheel(wheelDir);
            main.postDelayed(this, WHEEL_REPEAT_MS);
        }
    };
    // ── 音量键：[键][0 = 点一下, 1 = 按住]，null = 没设，不截。蓝牙模式下 App 自己收（hardKeys），
    // 桌面模式下由服务独占按键设备、判断好发来（onHardKey(int, int)） ──
    private final Actions.Action[][] hardKeyActions = new Actions.Action[2][2];
    private final HardKeys hardKeys = new HardKeys(new HardKeysOutput());
    private final Runnable[] hardKeyTimers = {
            () -> hardKeys.onTimer(HardKeys.VOL_UP, SystemClock.uptimeMillis()),
            () -> hardKeys.onTimer(HardKeys.VOL_DOWN, SystemClock.uptimeMillis()),
    };
    /** 服务发来、开始了还没结束的按住（下标 = 键，值 = 开始时的动作）：断开、暂停、服务放开实体键时补结束，不会重复。 */
    private final Actions.Action[] serverHeld = new Actions.Action[2];
    /** 桌面模式下实体键经服务按着左键的来源：第一个按下、最后一个松开时才发 CMD_HOLD_LEFT。 */
    private final java.util.Set<Integer> serverLeftSources = new java.util.HashSet<>();
    private AudioManager audio;
    /**
     * 通话 / 来电响铃时音量键一个都不截（要能用音量键静音、调通话音量）：模式一变就重新告诉服务。
     * 这是第二道保险：振动 / 静音模式下来电不改音频模式，响铃由服务自己查 Telecom 放开（server.CallState）。
     */
    private final AudioManager.OnModeChangedListener audioMode = mode -> sendHardKeys();
    /** 上次提示过的"实体键截不了"（截不了的键位 | 原因 << 8）：同样的不重复提示，配置改了重来。 */
    private int hardToasted = -1;
    /** 上次读到的实体键配置（位同 CMD_HARD_KEYS），用来判断配置改没改。 */
    private int hardConfig = -1;
    /** 触摸那一路（服务转发 / 本地分流）最近报的鼠标键；和空中鼠标那一路并起来发，谁也不会松掉对方按着的键。 */
    private int touchButtons;
    /** 服务那边锁住的修饰键（MSG_MODIFIERS）：进空中鼠标时要让服务解开，不然 Ctrl 一直按着、每次点击都成了 Ctrl+点击。 */
    private int latchedMods;
    private final Runnable airExitLater = () -> {
        haptics.play(GestureEngine.HAPTIC_CLICK);
        setAirMode(false);
    };
    private String btDevice = "";
    /** 蓝牙模式下的触摸分流（桌面模式下这件事在服务里做）。 */
    private final TouchRouter local = new TouchRouter(new LocalOutput());
    private final Runnable localTimer = () -> local.onTimer(SystemClock.uptimeMillis());
    /** 这次手势由 local 分流（按下时定，一直到全部抬起）。 */
    private boolean localGesture;
    /** 这次手势是服务注入回系统的（分屏透传），见 {@link #dispatchTouchEvent}。 */
    private boolean injectedGesture;
    /** 这次注入回来的手势按在 decor 自带的标题栏上：整次交给 decor（拖动、关闭小窗）。 */
    private boolean injectedCaption;
    /** 上次报给服务时 decor 自带标题栏的高度（窗口坐标，和报的上边手势带同一时刻）；全屏时 0。 */
    private int reportedDecorCap;
    /** 蓝牙模式下拦住系统的返回手势：从屏幕边缘横划移光标时不能把豆花关掉（要走有"退出"按钮）。 */
    private final OnBackInvokedCallback swallowBack = () -> { };
    private boolean backSwallowed;

    /** 手机实际朝向（度），转屏时决定横屏朝哪边。-1 = 不知道（平放）。 */
    private int tilt = OrientationEventListener.ORIENTATION_UNKNOWN;
    private OrientationEventListener tiltListener;

    // ═══════════ 生命周期 ═══════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        boards = prefs.boards(FixedData.catalog(this));
        haptics = new Haptics(this);
        displays = getSystemService(DisplayManager.class);
        audio = getSystemService(AudioManager.class);
        bt = BtHid.get(this);
        selfStart = SelfStart.get(this);
        air = new AirMouse(this, bt, prefs, this);
        // 只在可调试包里有：adb 让手机发任意位移（量回正中用）、开键盘触点日志（打字测试用）、逐键发给主机（实体键盘布局的实测扫描）
        debugMoves = DebugMoves.register(this, bt, this::onKeyLogChanged, new DebugMoves.KeySink() {
            @Override
            public String notReady() {
                if (btMode && !bt.isConnected()) return "蓝牙没连着主机";
                if (!btMode && !link.isConnected()) return "桌面模式没连上服务（键经服务的虚拟键盘发）";
                if (latchedMods != 0) return "键盘上锁着修饰键（先点一下解开，不然第一个键会带着它）";
                return null;
            }

            @Override
            public void key(int key, boolean down) {
                onLocalKey(key, down);
            }
        });
        onKeyLogChanged();   // 界面重建时接上还没到期的租期
        density = getResources().getDisplayMetrics().density;
        linkMessage = getString(R.string.status_connecting);
        tiltListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                tilt = degrees;
            }
        };
        // 外接屏接上时要弹通知，Android 13 起要用户同意
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        // 连摄像头开孔那一条也铺满
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        w.setAttributes(attrs);
        // 先定主题再建界面：建的时候就是对的颜色，不用先画一遍纯黑再换
        Style.setTheme(Theme.byId(prefs.theme()), prefs.keyBorders());
        shownTheme = Style.theme();
        shownBorders = Style.keyBorders();
        edgeLrDp = prefs.edgeMarginDp(true);
        edgeTbDp = prefs.edgeMarginDp(false);
        setContentView(buildUi());
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        linkSeen = false;
        autoStartAsked = false;
        selfStart.addListener(selfStartListener);
        PadImage.setImportedHook(padBgImported);
        applyPrefs();
        refreshDisplay();
        Display disp = getDisplay();
        if (disp != null) air.setRotation(disp.getRotation());
        displays.registerDisplayListener(this, main);
        if (audio != null) audio.addOnModeChangedListener(getMainExecutor(), audioMode);
        link.start();
        if (btMode) {
            bt.acquire(this);
            btAcquired = true;
            // 通知权限的对话框还没答完时先不问（同一时刻只能问一组），答完在 onRequestPermissionsResult 里接着问
            boolean notifPending = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
            if (!BtHid.hasPermission(this) && !notifPending) requestBtPermissions();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        yieldFocus = false;   // 重新回到前台：先照常能拿焦点，等焦点被别的应用拿走再让（onWindowFocusChanged）
        updateFocusable();
        hideSystemBars();
        if (tiltListener.canDetectOrientation()) tiltListener.enable();
        main.post(heartbeat);
        pushLayout();
        hardKeys.forget();   // 停着的时候收不到按键，之前记的按下作废
    }

    @Override
    protected void onPause() {
        // 先让服务释放触摸屏：别的界面（设置页、来电）需要触摸
        resumed = false;
        tiltListener.disable();
        haptics.stopHum();
        main.removeCallbacks(heartbeat);
        local.releaseAll();   // 蓝牙模式：按着的键、鼠标键都松开（桌面模式下 local 本来就闲着）
        localGesture = false;
        hardKeys.reset();   // 音量键按住中的先收尾（按住移动 / 拖动 / 左键松开）
        endServerHolds();   // 服务那一路的同理（服务随后放开实体键，发来的结束按住不会再做一次）
        stopWheel();
        leftHolds = 0;
        releaseAir();   // 空中指针：停跟随、松开按着的键（空中鼠标页本身留着，回来接着用）
        main.removeCallbacks(airExitLater);
        main.removeCallbacks(gpMenuLater);   // 手柄页本身留着（手柄这一路由上面的 local.releaseAll、服务释放触摸屏时回中立）
        pushLayout();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (debugMoves != null) debugMoves.unregister(this);
        padImage.destroy();
        main.removeCallbacks(keyLogCheck);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        closeTextBar();
        link.stop();
        stopped = true;
        if (btMode) {
            // 服务释放触摸屏后发回的"松开"可能还在路上，连接就关了：主机那头别留着按住的鼠标键、键盘键
            touchButtons = 0;
            bt.sendMouse(air.buttons(), 0, 0, 0, 0);
            bt.sendKeyboard(new byte[Hid.KEYBOARD_REPORT_SIZE]);
            latchedMods = 0;
            keyboard.setModifiers(0);
            fixedKeyboard.setModifiers(0);
        }
        grabbed = false;
        if (btAcquired) {
            bt.release(this);
            btAcquired = false;
        }
        displays.unregisterDisplayListener(this);
        if (audio != null) audio.removeOnModeChangedListener(audioMode);
        selfStart.removeListener(selfStartListener);
        PadImage.removeImportedHook(padBgImported);
        super.onStop();
    }

    /** 手机自己拉起服务的进度变了（开始启动 / 启动完）：状态字跟着变。 */
    private final SelfStart.Listener selfStartListener = this::updateStatus;

    /** 设置页里选的背景图读取中就回到了主界面（onStart 的 applyPrefs 读到的还是旧图）：导入成功后照新的 Prefs 换上（后台解码，同 applyPrefs）。 */
    private final Runnable padBgImported = () -> padImage.apply(prefs.padBg(), maxWindowSide());

    /** 设置页改完回来：按新设置更新按钮和震动强度，参数发给服务（蓝牙模式下给本地分流）。 */
    private void applyPrefs() {
        applyTheme();
        edgeLrDp = prefs.edgeMarginDp(true);
        edgeTbDp = prefs.edgeMarginDp(false);
        updateEdgeMargins();
        btMode = prefs.btMode();
        updateHostKind();
        if (!btMode && airOn) setAirMode(false);
        if (btMode && gamepadOn) setGamepadMode(false);   // 手柄页第一批只在桌面模式
        air.setSensitivity(prefs.airSensitivity());
        air.loadFilter();
        updateAirAbs();   // 屏幕比例可能刚改过
        loadHardKeys();
        sendHardKeys();
        local.setConfig(prefs.engineConfig(), density);
        local.setGamepadConfig(prefs.gamepadConfig());
        gamepadScreen.setSpec(prefs.gamepadSpec());   // 设置 → 手柄里换了预设 / 改了自定义（内容没变不算换）
        applyBackSwallow();
        if (!btMode) requestOrientationSelf(-1);   // 蓝牙模式下等连服务的结果再定，见 onLinkStatus
        haptics.setLevel(prefs.haptic());
        haptics.setTickStrength(prefs.tickStrength());
        haptics.setDragHum(prefs.dragHum());
        boolean tuning = Haptics.tuning(this);   // 惯性"放开 / 刹住"的波形只在调试版里能挑，正式包用默认
        haptics.setSpinStyles(tuning ? prefs.spinStyle(true) : -1, tuning ? prefs.spinStyle(false) : -1);
        if (btMode) bt.descriptorChanged();   // "连续滚动"开关变了：蓝牙用新描述符重新注册（对方要重新配对）
        GestureEngine.Config cfg = prefs.engineConfig();
        textureOn = cfg.texture;
        scrollTicksOn = cfg.scrollTicks != GestureEngine.TICKS_OFF;
        float edge = cfg.edgeWidthDp * density;
        pad.setEdgeGuides(edge, cfg.edgeScroll, cfg.edgeScrollHorizontal);
        miniPad.setEdgeGuides(edge, cfg.edgeScroll, cfg.edgeScrollHorizontal);
        // 背景图：文件换了 / 要的更大才后台重新解（不放进 applyTheme：那个方法主题没变就直接返回）
        padImage.apply(prefs.padBg(), maxWindowSide());
        rebuildStrip();
        applyCustomKeys();
        rebuildAirSlots();
        updateActiveButtons();
        keyboardRow.clear();
        for (int i = 0; i < Prefs.KEYBOARD_ROW_COUNT; i++) keyboardRow.add(Actions.get(prefs.keyboardRow(i)));
        keyboard.setShortcuts(keyboardRow);
        for (int i = 0; i < Prefs.CORNER_COUNT; i++) {
            Actions.Action a = Actions.get(prefs.corner(i));
            TextView b = cornerButtons.get(i);
            b.setText(a.shortLabel(this));
            b.setTag(a);
            b.setVisibility(a.kind == Actions.Kind.NONE ? View.GONE : View.VISIBLE);   // 设成"无"的角不放按钮（也不报区域）
        }
        // 底部状态字让过下面两个角（下标 2 右下、3 左下）的小按钮（8dp 边距 + 48dp 宽 + 6dp 空）：长的在中间截掉，不压在按钮上
        boolean bottomCorners = cornerButtons.get(2).getVisibility() == View.VISIBLE || cornerButtons.get(3).getVisibility() == View.VISIBLE;
        pad.setStatusSide(bottomCorners ? dp(62) : 0);
        keyboardScreen.setKeyboardLayout(prefs.keyboardLayoutMode());
        keyboardScreen.setPointer(prefs.keyboardPointer());
        applyBoards();
        keyboardScreen.setPadKeys(prefs.padKeys());
        padStack.setKeys(prefs.padKeys());
        updateMenuLabels();
        link.config(prefs.engineConfig(), density);
        link.gamepadConfig(prefs.gamepadConfig());
        updateFocusable();
    }

    // ═══════════ 界面 ═══════════

    /**
     * 设置里换了主题 / 按键边框开关：已经建好的界面逐个重新上色（不重建，按着的键、打开的面板都不受影响）。
     * 按钮条、空中鼠标页的格子 applyPrefs 接着会整条重建，这里不管；触控板、圆钮画的时候现取颜色，只要重画。
     */
    private void applyTheme() {
        Theme t = Theme.byId(prefs.theme());
        boolean borders = prefs.keyBorders();
        if (t == shownTheme && borders == shownBorders) return;
        Style.setTheme(t, borders);
        shownTheme = t;
        shownBorders = borders;
        root.setBackgroundColor(t.bg);
        airOverlay.setBackgroundColor(t.bg);
        for (int i = 0; i < numpadButtons.size(); i++) numpadLook(i, numpadHeld.contains(numpadButtons.get(i)));
        for (TextView b : menuItems) Style.look(b, false, false);
        for (TextView b : textBarButtons) Style.look(b, false, false);
        Style.look(kbDone, false, false);
        Style.look(kbReset, false, false);
        Style.look(airCenter, false, false);
        Style.look(airExit, false, false);
        for (TextView b : cornerButtons) Style.small(b, false, cornersOnImage);
        Style.bare(kbMenuButton, false);
        Style.bare(padMenuButton, false);
        menuLayer.panel().setBackground(Style.border(this, t.accent, t.menuFill));
        input.setTextColor(t.strong);
        input.setHintTextColor(t.hint);
        input.setBackground(Style.border(this, t.border, 0));
        tintInput(t);
        airStatus.setTextColor(t.text);
        airState.setTextColor(t.focus);
        keyboard.applyTheme();
        fixedKeyboard.applyTheme();
        keyboardScreen.applyTheme();
        pad.invalidate();
        miniPad.invalidate();
        kbStick.invalidate();
        kbMouseKeys.applyTheme();
        padMouseKeys.applyTheme();
        airRound.invalidate();
        gamepadScreen.applyTheme();
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Style.theme().bg);
        int pad8 = dp(8);
        // 贴边：触摸由服务独占，系统的边缘手势碰不到，不用再给系统栏、刘海留边；只给"贴字"面板的输入法让位
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(0, 0, 0, ime.bottom);
            return insets;
        });
        // 横竖屏切换、面板开合、按钮变化都会重新布局；布局一变就把新位置告诉服务。整棵树摆完了才到这里：
        // ⋯ 菜单先跟着 ⋯ 挪（只有 ⋯ 那一支重新布局时菜单那一层自己不一定重摆），再报区域
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            menuLayer.place();
            pushLayout();
        });
        // 小窗只挪位置、大小不变时根视图不重新布局（上面那个监听不触发），但窗口会走一次布局遍历：在这里比一下位置，变了才重报。
        // 不重报的话服务按旧位置分手指：豆花新位置上的触摸全被注入回系统、又被 App 吞掉，整个窗口点不动
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this::checkWindowMoved);

        mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(mainRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 自定义按键（左边一列，y 轴）：设置里打开才显示，矮屏自动分列 ──
        strip = new ButtonStrip(this, dp(60), dp(52), dp(6));
        mainRow.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rebuildStrip();

        // ── 右侧："贴字"面板在上；下面是触控板 + 数字键盘 / 外接键盘 ──
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.setMarginStart(pad8);   // 和自定义按键之间的缝（从右往左的语言下自定义按键在右边，缝也跟着在右边）
        mainRow.addView(right, rp);
        applyCustomKeys();

        textBar = buildTextBar();
        textBar.setVisibility(View.GONE);
        right.addView(textBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        padImage = new PadImage(this, prefs, this::onPadImageChanged);
        pad = new TrackpadView(this);
        pad.setImage(padImage);
        numpad = buildNumpad();
        numpad.setVisibility(View.GONE);
        padMouseKeys = buildMouseKeys();
        // 触控板顶上正中的 ⋯：同键盘屏，只有暗字、不带框
        padMenuButton = Style.button(this, 18);
        padMenuButton.setText("⋯");
        Style.bare(padMenuButton, false);
        padMenuButton.setOnTouchListener((v, e) -> localTouch(v, e, PAD_MENU_ID));
        padStack = new PadStack(this, buildPadFrame(), pad, padMouseKeys, padMenuButton);
        right.addView(new PadArea(this, padStack, numpad, pad8),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ── 键盘屏：键盘 + 小触控板占满整页，⋯ 和调整栏由 KeyboardScreen 摆 ──
        miniPad = new TrackpadView(this);
        miniPad.setImage(padImage);
        keyboard = new KeyboardPanel(this, this);
        // 键盘和数字键盘按实体键盘排，阿拉伯语这类从右往左的语言下也不镜像
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        numpad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        // 触控板顶上正中的 ⋯：只有暗字、不带框
        kbMenuButton = Style.button(this, 18);
        kbMenuButton.setText("⋯");
        Style.bare(kbMenuButton, false);
        kbMenuButton.setOnTouchListener((v, e) -> localTouch(v, e, KB_MENU_ID));
        LinearLayout adjustBar = new LinearLayout(this);
        adjustBar.setGravity(Gravity.CENTER);   // 按钮宽度由 KeyboardScreen 量尺寸时定（窄窗口一起缩）
        // 栏整块吃掉落在按钮之间、两头空处的触摸：不然往下传给叠在底下的键盘，调整模式里打出字来（同 ⋯ 菜单的底板）
        adjustBar.setClickable(true);
        kbDone = addBarButton(adjustBar, R.string.kb_done, KB_DONE_ID);
        kbReset = addBarButton(adjustBar, R.string.kb_reset, KB_RESET_ID);
        kbStick = new TrackPointView(this);
        // 服务连着没接管时（View 自己收触摸）小红点不动，和触控板一样。嵌在键里时一根手指都不收，全落到底下的键盘面板上：
        // 面板按手指编号分（KeyFingers.pick），红点圆里的不算任何键、方块四个角归被切了一块的键（同分流的 REGION_STICK 按内切圆）。
        // 不能只在按下时看圆：红点收下第一根手指后，ViewGroup 把落在它方块里的后来的手指直接交给它、不再问（2026-11-01 审查发现），
        // 第二根手指按在 g 被切掉的那个角上就被吞了。放在键盘外面时底下什么都没有，照旧整块吃掉（不然第一根落空，后来按在键上的手指也收不到）
        kbStick.setOnTouchListener((v, e) -> !kbStick.isEmbedded());
        kbMouseKeys = buildMouseKeys();
        // 实体键盘那块面板（自己画全部键帽；选了、放得下才显示，KeyboardScreen 管）；同豆花布局，按实体键盘排、不跟着从右往左
        fixedKeyboard = new FixedKeyboardView(this, this);
        fixedKeyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboardScreen = new KeyboardScreen(this, miniPad, keyboard, fixedKeyboard, kbStick, kbMouseKeys, kbMenuButton, adjustBar, this);
        keyboardScreen.setVisibility(View.GONE);
        root.addView(keyboardScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildAirOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // ── 手柄页：独立一页，盖住主界面（不开时 GONE）；顶上正中的 ⋯ 按住 0.6 秒开菜单 ──
        gpMenuButton = Style.button(this, 18);
        gpMenuButton.setText("⋯");
        Style.bare(gpMenuButton, false);
        gpMenuButton.setOnTouchListener((v, e) -> localTouch(v, e, GP_MENU_ID));
        gamepadScreen = new GamepadScreen(this, gpMenuButton);
        gamepadScreen.setVisibility(View.GONE);
        root.addView(gamepadScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // ── ⋯ 菜单：几屏共用，盖在最上面（不开时 GONE） ──
        root.addView(buildMenu(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateEdgeMargins();   // 还没布局：按系统给的窗口范围先收好，第一次布局就是对的
        return root;
    }

    /**
     * 屏幕边缘留白（设置 → 外观，2026-11-01）：五页（主界面、键盘屏、空中鼠标页、手柄页、⋯ 菜单那一层）整体往里收，贴着屏幕边的那几条边
     * 各留一条页面底色（根视图的底色），那一条不报区域、View 自己收触摸时也没有东西接（EdgeMargin 算哪几条边留、留多少）。
     * 按窗口在屏上的范围（同报给服务的：decor 的 getLocationOnScreen + 大小）和整块屏（getMaximumWindowMetrics）判断，
     * 当前方向的坐标，所以转屏后"左右"还是当前方向的左右两条边；decor 还没量好时先按系统给的窗口范围（getCurrentWindowMetrics）。
     * 键盘屏、菜单那一层另外知道各边收了多少：它们"离屏幕边 20dp"的规矩照旧按屏幕边算（EdgeMargin.edges）。
     * 布局完一次（窗口大小变了、小窗挪了）、心跳兜底、设置改了都来这里比一下，变了才重新摆（摆完根视图的布局监听照常重报区域）。
     */
    private void updateEdgeMargins() {
        if (menuLayer == null) return;   // buildUi 还没建完
        View decor = getWindow().getDecorView();
        int[] window;
        if (decor.getWidth() > 0 && decor.getHeight() > 0) {
            decor.getLocationOnScreen(tmpLoc);
            window = new int[]{tmpLoc[0], tmpLoc[1], tmpLoc[0] + decor.getWidth(), tmpLoc[1] + decor.getHeight()};
        } else {
            Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            window = new int[]{b.left, b.top, b.right, b.bottom};
        }
        Rect s = getWindowManager().getMaximumWindowMetrics().getBounds();
        int[] m = EdgeMargin.margins(window, new int[]{s.left, s.top, s.right, s.bottom}, dp(edgeLrDp), dp(edgeTbDp));
        if (Arrays.equals(m, pageMargins)) return;
        System.arraycopy(m, 0, pageMargins, 0, 4);
        for (View page : new View[]{mainRow, keyboardScreen, airOverlay, gamepadScreen, menuLayer}) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) page.getLayoutParams();
            p.setMargins(m[0], m[1], m[2], m[3]);   // 按物理左右（不是 start / end）：从右往左的语言下也一样
            page.setLayoutParams(p);
        }
        int[] e = EdgeMargin.edges(m, dp(20));
        keyboardScreen.setEdgeInsets(e);
        gamepadScreen.setEdgeInsets(e);
        menuLayer.setEdgeInsets(e);
    }

    /** 触控板 + 四角的小按钮（一直显示；动作、设成"无"时藏起来在 applyPrefs 里定）。按物理左右放，从右往左的语言下也不镜像。 */
    private View buildPadFrame() {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < Prefs.CORNER_COUNT; i++) {
            int id = CORNER_BASE_ID + i;
            TextView b = Style.button(this, 12);
            Style.small(b, false, cornersOnImage);
            b.setOnTouchListener((v, e) -> localTouch(v, e, id));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(48), dp(40), CORNER_GRAVITY[i]);
            p.setMargins(dp(8), dp(8), dp(8), dp(8));
            frame.addView(b, p);
            cornerButtons.add(b);
        }
        return frame;
    }

    /**
     * 一排左 / 中 / 右三个鼠标键（键盘页最下面那排和三键条一组、主界面触控板的三键条一组）。服务接管 / 本地分流时按报的区域分流
     * （Proto.REGION_MOUSE_BUTTON）；服务连着没接管时 View 自己收触摸：轻点 = 点一下这个键（{@link #mouseKeyTouch}），按住拖不了。
     */
    private MouseKeys buildMouseKeys() {
        MouseKeys keys = new MouseKeys(this, dp(4));
        for (int i = 0; i < 3; i++) {
            int mask = MouseKeys.MASKS[i];
            keys.key(i).setOnTouchListener((v, e) -> mouseKeyTouch(v, e, mask));
        }
        return keys;
    }

    /** 服务没接管时三个鼠标键自己收触摸：按下画样子、震，抬起还在键上就点一下这个键（桌面模式经服务，蓝牙模式经本地分流发）。 */
    private boolean mouseKeyTouch(View v, MotionEvent e, int mask) {
        int id = Proto.MOUSE_BUTTON_ID_BASE | mask;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onButton(id, Proto.BUTTON_DOWN);
                return true;
            case MotionEvent.ACTION_UP:
                onButton(id, Proto.BUTTON_UP);
                if (e.getX() >= 0 && e.getX() <= v.getWidth() && e.getY() >= 0 && e.getY() <= v.getHeight()) {
                    if (btMode) local.click(mask);
                    else link.click(mask);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                onButton(id, Proto.BUTTON_UP);
                return true;
            default:
                return true;
        }
    }

    /**
     * "自定义按键"显示 / 藏起（设置里的开关，默认关；开着但一个按钮都没有也不占地方）。藏起时触控板贴着左边，四个角的小按钮照旧
     * （以前按钮条和角上的按钮二选一，2026-11-01 起角上的一直在，自定义按键是另加的）。
     */
    private void applyCustomKeys() {
        boolean on = prefs.customKeys() && !stripButtons.isEmpty();
        strip.setVisibility(on ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) right.getLayoutParams();
        int margin = on ? dp(8) : 0;
        if (rp.getMarginStart() == margin) return;
        rp.setMarginStart(margin);
        right.setLayoutParams(rp);
    }

    /** 调整栏上的按钮（完成、恢复默认）。 */
    private TextView addBarButton(LinearLayout bar, int label, int id) {
        TextView b = Style.button(this, 14);
        // 窄窗口里按钮一个只有六七十 dp 时，法语、德语这类长名字缩到最小字号一行也放不下：准许折两行（自动缩字号会把行数算进去）
        b.setMaxLines(2);
        b.setText(label);
        b.setOnTouchListener((v, e) -> localTouch(v, e, id));
        // 宽度、边距由 KeyboardScreen.fitBar 按窗口宽度定：定宽挤在中间、离屏幕边远一点
        bar.addView(b, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT));
        return b;
    }

    /**
     * ⋯ 菜单（两屏共用一份，MenuLayer）：项按 MENU_IDS 的顺序建好，打开时只显示当前那一屏的（{@link #setMenu}）；
     * 豆花自己画（服务独占触摸屏时 App 收不到手指，PopupMenu / Dialog 用不了）。排法、位置由 MenuLayer 定（贴着 ⋯、不出屏，
     * 项多窗口矮时压矮 / 分列）。底板可点（服务没接管时挡住底下的"点外面"）。
     */
    private View buildMenu() {
        menuOutside = new View(this);
        menuOutside.setOnTouchListener((v, e) -> localTouch(v, e, MENU_OUTSIDE_ID));
        menuLayer = new MenuLayer(this, menuOutside, this::menuAnchor);
        ViewGroup panel = menuLayer.panel();
        panel.setBackground(Style.border(this, Style.theme().accent, Style.theme().menuFill));
        for (int i = 0; i < MENU_IDS.length; i++) {
            TextView b = Style.button(this, 15);
            b.setMaxLines(2);
            b.setText(MENU_LABELS[i]);
            int id = MENU_IDS[i];
            b.setOnTouchListener((v, e) -> localTouch(v, e, id));
            panel.addView(b);
            menuItems.add(b);
        }
        return menuLayer;
    }

    /** ⋯ 现在在哪（窗口坐标，MenuLayer 摆菜单时问）：手柄页、键盘屏开着时是它们的 ⋯（键盘屏的在触控板顶上或者功能行上），不然是主界面触控板顶上的。 */
    private boolean menuAnchor(int[] out) {
        if (gamepadOn) {
            gpMenuButton.getLocationInWindow(anchorLoc);
            out[0] = anchorLoc[0];
            out[1] = anchorLoc[1];
            out[2] = anchorLoc[0] + gpMenuButton.getWidth();
            out[3] = anchorLoc[1] + gpMenuButton.getHeight();
            return true;
        }
        if (keyboardScreen.getVisibility() == View.VISIBLE) {
            boolean center = keyboardScreen.menuAnchor(out);
            keyboardScreen.getLocationInWindow(anchorLoc);
            out[0] += anchorLoc[0];
            out[1] += anchorLoc[1];
            out[2] += anchorLoc[0];
            out[3] += anchorLoc[1];
            return center;
        }
        padMenuButton.getLocationInWindow(anchorLoc);
        out[0] = anchorLoc[0];
        out[1] = anchorLoc[1];
        out[2] = anchorLoc[0] + padMenuButton.getWidth();
        out[3] = anchorLoc[1] + padMenuButton.getHeight();
        return true;
    }

    /**
     * 这一项在这一屏（SCREEN_*）的菜单里有没有。"手柄"只在桌面模式（desktop）有；"布局"在小红点状态（stick）下没有
     * （小红点固定合并，2026-11-01；选过的布局留着，换回触控板 / 关时照它），显示实体键盘（fixed）时也没有（实体键盘不分裂，"布局"是豆花布局的）。
     */
    private static boolean onMenuScreen(int id, int screen, boolean desktop, boolean stick, boolean fixed) {
        switch (id) {
            case MENU_KEYBOARD_ID: return screen == SCREEN_MAIN;
            case MENU_BOARD_ID: return screen == SCREEN_KEYBOARD;
            case KB_LAYOUT_ID: return screen == SCREEN_KEYBOARD && !stick && !fixed;
            case KB_CLOSE_ID:
            case KB_ADJUST_ID:
            case KB_POINTER_ID: return screen == SCREEN_KEYBOARD;
            case MENU_GAMEPAD_ID: return screen != SCREEN_GAMEPAD && desktop;
            case GP_PRESET_ID:
            case GP_BACK_ID: return screen == SCREEN_GAMEPAD;
            default: return true;   // 设置、旋转、退出
        }
    }

    /** 菜单项编号 → 下标（MENU_IDS），不是菜单项返回 -1。 */
    private static int menuIndex(int id) {
        for (int i = 0; i < MENU_IDS.length; i++) if (MENU_IDS[i] == id) return i;
        return -1;
    }

    /**
     * 自定义按键：设置里的若干个（2026-11-01 起没有写死的"退出 / 设置 / 收起"，都进了 ⋯ 菜单）。按钮编号就是下标，
     * 设置改了个数就整条重建；设成"无"的空着、位置留着。
     */
    private void rebuildStrip() {
        strip.removeAllViews();
        stripButtons.clear();
        int n = prefs.slotCount();
        for (int i = 0; i < n; i++) {
            Actions.Action a = Actions.get(prefs.slot(i));
            TextView b = Style.button(this, 15);
            int id = stripButtons.size();
            b.setText(a.shortLabel(this));
            b.setTag(a);
            if (a.kind == Actions.Kind.NONE) b.setVisibility(View.INVISIBLE);
            b.setOnTouchListener((v, e) -> localTouch(v, e, id));
            stripButtons.add(b);
            strip.addView(b);
        }
    }

    /** "贴字"面板：用手机自己的输入法（语音、中文）打一句，经剪贴板贴到正在输入的地方。 */
    private LinearLayout buildTextBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        input = new EditText(this);
        input.setTextColor(Style.theme().strong);
        input.setHintTextColor(Style.theme().hint);
        input.setHint(R.string.text_hint);
        input.setBackground(Style.border(this, Style.theme().border, 0));
        inputCursor = input.getTextCursorDrawable();
        inputHandleLeft = input.getTextSelectHandleLeft();
        inputHandle = input.getTextSelectHandle();
        inputHandleRight = input.getTextSelectHandleRight();
        inputHighlight = input.getHighlightColor();
        tintInput(Style.theme());
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT);   // 单行回车 = 发送
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL) {
                sendText();
                return true;
            }
            return false;
        });
        row1.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row1.addView(smallButton(getString(R.string.text_send), v -> sendText()), smallParams());
        bar.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(smallButton("⌫", v -> key(KeyEvent.KEYCODE_DEL, 0)), smallParams());
        row2.addView(smallButton("↵", v -> key(KeyEvent.KEYCODE_ENTER, 0)), smallParams());
        row2.addView(smallButton("Tab", v -> key(KeyEvent.KEYCODE_TAB, 0)), smallParams());
        row2.addView(smallButton(getString(R.string.text_hide), v -> closeTextBar()), smallParams());
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2.topMargin = dp(6);
        r2.bottomMargin = dp(8);
        bar.addView(row2, r2);
        return bar;
    }

    /** 输入框的光标用主题的亮文字色、选择把手和选中高亮用 focus（够 4.5:1 的强调色）；纯黑装回系统原来的。 */
    private void tintInput(Theme t) {
        if (t == Theme.DEFAULT) {
            if (inputCursor != null) input.setTextCursorDrawable(inputCursor);
            if (inputHandleLeft != null) input.setTextSelectHandleLeft(inputHandleLeft);
            if (inputHandle != null) input.setTextSelectHandle(inputHandle);
            if (inputHandleRight != null) input.setTextSelectHandleRight(inputHandleRight);
            input.setHighlightColor(inputHighlight);
            return;
        }
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(t.strong);
        cursor.setSize(dp(2), -1);
        input.setTextCursorDrawable(cursor);
        Drawable h = tinted(inputHandleLeft, t.focus);
        if (h != null) input.setTextSelectHandleLeft(h);
        h = tinted(inputHandle, t.focus);
        if (h != null) input.setTextSelectHandle(h);
        h = tinted(inputHandleRight, t.focus);
        if (h != null) input.setTextSelectHandleRight(h);
        input.setHighlightColor((t.focus & 0x00FFFFFF) | 0x66000000);
    }

    /** 原来那个把手的一份副本、染成 color（不直接 mutate 原来的：换回纯黑时要装回没染过的）。 */
    private Drawable tinted(Drawable original, int color) {
        if (original == null || original.getConstantState() == null) return null;
        Drawable d = original.getConstantState().newDrawable(getResources()).mutate();
        d.setTint(color);
        return d;
    }

    private LinearLayout buildNumpad() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < NUMPAD_LABELS.length; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int c = 0; c < NUMPAD_LABELS[r].length; c++) {
                int usage = NUMPAD_USAGES[r][c];
                TextView b = Style.keyButton(this, 18);
                b.setText(NUMPAD_LABELS[r][c]);
                boolean fn = KeyRows.numpadFunctionKey(usage);   // 运算符、⌫、Tab、↵ 用主题的功能键颜色
                Style.keyLook(b, fn, false, false);
                b.setOnTouchListener((v, e) -> {
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN) {   // 先发键，再震、再画按下的样子
                        numpadHeld.add(v);
                        onLocalKey(usage, true);
                        keyHaptic();
                        Style.keyLook(v, fn, false, true);
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        // 接管时已经替它松开过（releaseNumpad）的，迟到的抬起不再发
                        if (numpadHeld.remove(v)) onLocalKey(usage, false);
                        Style.keyLook(v, fn, false, false);
                    }
                    return true;
                });
                numpadButtons.add(b);
                numpadUsages.add(usage);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                p.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(b, p);
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        return grid;
    }

    private TextView smallButton(String label, View.OnClickListener onClick) {
        TextView b = Style.button(this, 14);
        b.setText(label);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        textBarButtons.add(b);
        b.setOnClickListener(v -> {
            haptics.play(GestureEngine.HAPTIC_CLICK);
            onClick.onClick(v);
        });
        return b;
    }

    private LinearLayout.LayoutParams smallParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.leftMargin = dp(6);
        return p;
    }

    /** 面板开着时，对应按钮高亮。 */
    private void updateActiveButtons() {
        for (TextView b : stripButtons) Style.look(b, isActive(b), false);
        for (TextView b : airSlots) Style.look(b, isActive(b), false);
        if (airRound != null) airRound.setState(air.following(), airRoundPressed);
    }

    private boolean isActive(View b) {
        if (!(b.getTag() instanceof Actions.Action)) return false;
        switch (((Actions.Action) b.getTag()).kind) {
            case KEYBOARD: return keyboardScreen.getVisibility() == View.VISIBLE;
            case NUMPAD: return numpad.getVisibility() == View.VISIBLE;
            case PHONE_TEXT: return textBar.getVisibility() == View.VISIBLE;
            case AIR_MOVE: return air.following() && !air.dragging();
            case AIR_DRAG: return air.dragging();
            case GAMEPAD: return gamepadOn;
            default: return false;
        }
    }

    /**
     * 窗口能不能获得焦点。平时不能（不抢外接屏的焦点）；两种情况要能：
     * - "贴字"面板开着：输入法要焦点；
     * - 没有外接屏：虚拟键盘的按键只能发到手机屏上，豆花是这块屏的当前应用，窗口却不收焦点，
     *   系统等 5 秒没人接，就弹"豆花没有响应"。
     */
    private void updateFocusable() {
        // 音量键设了动作要收按键：接着外接屏时也让窗口能获得焦点
        boolean yield = yieldFocus && !btMode && !windowFull;
        boolean focusable = textBar.getVisibility() == View.VISIBLE || (targetDisplay == Display.INVALID_DISPLAY && !yield)
                || hardKeysActive();
        if (focusable) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    /**
     * 桌面模式分屏 / 小窗时，焦点一旦被别的应用拿走（用户点了另一边的 Moonlight 之类），豆花的窗口就不再拿焦点：
     * 光标跑到豆花这半边点一下也抢不走，另一边的应用就不会因为丢焦点被系统收回鼠标捕获（用户 2026-10-30：
     * Moonlight 不抓豆花的鼠标、光标跑出它的边界；推测是焦点被豆花抢了，先这么改，不行再回退）。
     * 不是一进分屏就不拿：刚开进分屏时豆花是当前应用，窗口要是不收焦点，虚拟键盘的键没人接，5 秒后系统弹"豆花没有响应"。
     * 回到全屏（pushLayout）、重新回到前台（onResume）时清掉；贴字面板开着时照样临时拿焦点（updateFocusable）。
     */
    private boolean yieldFocus;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && !btMode && !windowFull && resumed && textBar.getVisibility() != View.VISIBLE && !yieldFocus) {
            yieldFocus = true;
            updateFocusable();
        }
    }

    /**
     * 没有外接屏时，豆花虚拟键盘的键会送到这里：吞掉，免得 Esc 被系统当返回键把豆花关了。
     * 豆花自己的手柄（0x1209 / 0xD0A2）的键一律吞（贴字面板开着也吞）：没人处理的手柄键安卓会换成别的键——B → 返回、Home → 回桌面、
     * Select → 菜单（Generic.kcm 的后备键），豆花窗口拿着焦点时一按 B 就把自己关了。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (onHardKey(e)) return true;   // 音量键设了动作：归豆花（音量不变）
        InputDevice dev = e.getDevice();
        if (ownGamepad(dev)) return true;
        if (textBar.getVisibility() != View.VISIBLE && dev != null && dev.getVendorId() == Proto.HID_VENDOR) return true;
        return super.dispatchKeyEvent(e);
    }

    /**
     * 豆花自己的手柄的摇杆 / 方向键（MotionEvent）同样吞掉：不吞的话没人接的 HAT / X / Y 会被系统合成方向键（ViewRootImpl 的
     * SyntheticJoystickHandler）。只认手柄的产品号：虚拟鼠标（同一个厂商号）的悬停、滚轮照常。
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (ownGamepad(e.getDevice())) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    private static boolean ownGamepad(InputDevice dev) {
        return dev != null && dev.getVendorId() == Proto.HID_VENDOR && dev.getProductId() == Proto.HID_GAMEPAD_PRODUCT;
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsets.Type.systemBars());
    }

    // ═══════════ 告诉服务各区域在哪 ═══════════

    /**
     * 把各区域的位置交给分流的一方：桌面模式发给服务，蓝牙模式给本地的 TouchRouter。
     * 服务独占触摸屏的条件之一是 active：豆花在前台、"贴字"面板没开、键盘不在调整模式（拖动要 App 自己收触摸）、
     * 窗口占满整块屏（{@link #serverActive}）。
     * 蓝牙模式下也一样报，带上 relay：服务接管后把报文发回来（{@link #onRelayMouse}）；本地的 TouchRouter 同时备着，
     * 服务没开、没接管时用。
     */
    private void pushLayout() {
        if (pad == null) return;
        boolean wasFull = windowFull;
        windowFull = fillsDisplay();
        if (windowFull && yieldFocus) {   // 回到全屏：不再让焦点（豆花是这块屏唯一的应用，窗口不收焦点会"没有响应"）
            yieldFocus = false;
            updateFocusable();
        }
        // 分屏 / 小窗时没让服务锁方向（锁的是整块屏），回到全屏补上选过的方向。同一个方向重复锁一次无害
        if (!wasFull && windowFull && link.isConnected() && prefs.rotation() >= 0) {
            Display d = getDisplay();
            if (d != null) link.rotate(d.getDisplayId(), prefs.rotation());
        }
        boolean keyboardShown = keyboardScreen.getVisibility() == View.VISIBLE;
        // 手柄页、键盘在最上面（空中鼠标页上调出来的键盘也是），先看它们
        if (gamepadOn ? gamepadScreen.getWidth() == 0 : keyboardShown ? keyboardScreen.getWidth() == 0
                : airOn ? airOverlay.getWidth() == 0 : pad.getWidth() == 0) return;
        getWindow().getDecorView().getLocationOnScreen(layoutOrigin);
        List<int[]> regions = collectRegions(keyboardShown);
        // 本地分流：蓝牙模式一直备着；桌面模式只在手柄页（服务没接管时手柄页的触摸归它，经 CMD_GAMEPAD 发给服务）
        if (btMode || gamepadOn) {
            TouchRouter.Region[] rs = new TouchRouter.Region[regions.size()];
            for (int i = 0; i < rs.length; i++) {
                int[] r = regions.get(i);
                rs[i] = new TouchRouter.Region(r[0], r[1], r[2], r[3], r[4], r[5]);
            }
            local.setRegions(rs);
        } else {
            local.setRegions(new TouchRouter.Region[0]);
        }
        updateGestureExclusion();
        if (link.isConnected()) sendLayout(serverActive(), regions);
        updateStatus();
    }

    /** 窗口在屏幕上的位置和上次报区域时不一样了（小窗被拖走）：重报。先看屏幕边缘留白要不要换（小窗拖到贴着屏幕边、拖离屏幕边）。 */
    private void checkWindowMoved() {
        if (pad == null) return;
        updateEdgeMargins();
        getWindow().getDecorView().getLocationOnScreen(tmpLoc);
        if (tmpLoc[0] != layoutOrigin[0] || tmpLoc[1] != layoutOrigin[1]) pushLayout();
    }

    /**
     * 键盘触点调试日志（打字测试，tools/type-log.sh 经 adb 广播打开、每 3 秒续一次租期）：开着时本地分流和服务的分流
     * 都把键盘上的触点写 logcat（TouchRouter.Trace）。租期一到自己关，不靠工具退出时那一下。
     */
    private void onKeyLogChanged() {
        main.removeCallbacks(keyLogCheck);
        long left = debugMoves == null ? 0 : DebugMoves.keyLogUntil - SystemClock.uptimeMillis();
        boolean on = left > 0;
        if (on) main.postDelayed(keyLogCheck, left + 1);   // 到期再查一次（续过就接着等）
        if (on == keyLogOn) return;
        keyLogOn = on;
        local.setTrace(on ? s -> Log.d(TouchRouter.TRACE_TAG, s) : null);
        link.trace(on);
        Log.i("douhua", "键盘触点调试日志" + (on ? "开" : "关"));
    }

    /**
     * 豆花在前台、"贴字"面板没开、键盘不在调整模式：触摸由分流处理（否则按普通方式交给各个按钮）。
     * 只管分流能不能用；分屏 / 小窗里蓝牙模式照样由本地分流收自己窗口里的触摸。让不让服务独占触摸屏另看 {@link #serverActive}。
     */
    private boolean localActive() {
        return resumed && textBar.getVisibility() != View.VISIBLE && !keyboardScreen.isAdjusting();
    }

    /**
     * 报给服务的 active（服务独占触摸屏、截实体键的条件之一）：分流能用，而且窗口占满整块屏。
     * 分屏 / 小窗时整块触摸屏一独占，另一边的应用、分隔条、系统手势全碰不到，所以这时不让服务接管：
     * 服务照"不在前台"那条路释放，蓝牙模式退回本地分流（只收豆花自己的窗口），桌面模式触控板暂停、按钮和键盘照常。
     */
    private boolean serverActive() {
        return localActive() && ownsScreen();
    }

    /**
     * 蓝牙模式下豆花独自用着整块屏：窗口占满（{@link #windowFull}），而且是最上面那个 Activity（别的应用的小窗没浮在上面）；
     * 接着外接屏时窗口不收焦点、本来就不是顶层，不看这一条。不满足就不让服务独占，本地分流只收豆花自己窗口里的触摸。
     * 桌面模式恒为真：分屏 / 小窗里照样让服务独占整块屏（用户 2026-10-29 实测：Moonlight 在上、豆花在下分屏，不独占的话
     * 触控板动不了光标——桌面模式下没有本地鼠标那条路；手指点到豆花窗口上，系统还把键盘焦点给了豆花，虚拟键盘打的字被豆花自己吞掉，
     * 打一个字之后全进不了 Moonlight）。
     */
    private boolean ownsScreen() {
        if (!btMode) return true;
        if (!windowFull) return false;
        return targetDisplay != Display.INVALID_DISPLAY || topResumed;
    }

    /** 别的应用的小窗浮上来 / 关掉（或者用户点回豆花）：重新判断要不要让服务接管。 */
    @Override
    public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        topResumed = top;
        pushLayout();
    }

    /**
     * 窗口占满整块屏吗：不在多窗口模式（分屏、三星的弹出式视图小窗都算多窗口），窗口范围也等于这块屏能给的最大范围
     * （兜住别的没占满的情况，比如被系统按比例塞进中间的框）。
     */
    private boolean fillsDisplay() {
        if (isInMultiWindowMode()) return false;
        WindowManager wm = getWindowManager();
        return wm.getCurrentWindowMetrics().getBounds().equals(wm.getMaximumWindowMetrics().getBounds());
    }

    /** 进出分屏 / 小窗：重新判断要不要让服务接管（窗口大小变了的情况布局监听也会报，这里兜住大小没变的）。 */
    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration newConfig) {
        super.onMultiWindowModeChanged(inMultiWindow, newConfig);
        pushLayout();
    }

    /**
     * 区域列表，每项 {type, id, left, top, right, bottom}（屏幕像素）；叠在上面的排在前面。
     * 只报看得见的那一屏：⋯ 菜单开着时只有菜单；不然手柄页、键盘（空中鼠标页上调出来的也是）、空中鼠标页、主界面四组互斥。
     * 键盘开着时连空中鼠标页的也报的话，键盘空隙里看不见的圆钮、格子照样按得到。
     */
    private List<int[]> collectRegions(boolean keyboardShown) {
        List<int[]> regions = new ArrayList<>();
        if (menuLayer.isOpen()) {
            // ⋯ 菜单开着（主界面、键盘屏一样）：只报这一屏的菜单项、菜单底板，后面一块盖住整页——点在菜单外面只关菜单，
            // 底下的键、触控板、角上的按钮、自定义按键一个都按不到。
            // 分屏 / 小窗时"点外面"不覆盖窗口四边的手势带，交给系统认手势（sendLayout 里裁，手势带要到那里才算出来）
            for (int i = 0; i < menuItems.size(); i++) {
                TextView b = menuItems.get(i);
                if (b.getVisibility() == View.VISIBLE) addRegion(regions, b, Proto.REGION_BUTTON, MENU_IDS[i]);
            }
            addRegion(regions, menuLayer.panel(), Proto.REGION_BUTTON, MENU_PANEL_ID);
            addRegion(regions, menuOutside, Proto.REGION_BUTTON, MENU_OUTSIDE_ID);
            return regions;
        }
        if (gamepadOn) {   // 手柄页（独立一页）：⋯ 在最前，然后手柄控件（簇的范围在它的成员前面）
            addRegion(regions, gpMenuButton, Proto.REGION_BUTTON, GP_MENU_ID);
            gamepadScreen.collectRegions(regions);
            return regions;
        }
        if (keyboardShown) {
            if (keyboardScreen.isAdjusting()) {
                addRegion(regions, kbDone, Proto.REGION_BUTTON, KB_DONE_ID);
                addRegion(regions, kbReset, Proto.REGION_BUTTON, KB_RESET_ID);
            }
            // ⋯ 叠在触控板顶上，排在触控板前面。小红点嵌在键里（g、h、v、b 被切掉一块）时必须排在键和键盘范围前面：
            // 按在红点圆里的归红点，不归底下的键，缝里的点也分不到红点圆里去（TouchRouter.hit 按顺序找第一个；圆外、方块四角归键）。
            // 三个鼠标键在键盘里时铺满底行、快捷行让出的空位，同样排在键盘范围前面：空位里每一点都归某个鼠标键，不被分给旁边的键
            if (keyboardScreen.isMenuButtonShown()) addRegion(regions, kbMenuButton, Proto.REGION_BUTTON, KB_MENU_ID);
            if (keyboardScreen.isStickShown()) addRegion(regions, kbStick, Proto.REGION_STICK, Proto.STICK_ID);
            if (keyboardScreen.areMouseKeysShown()) addMouseKeys(regions, kbMouseKeys);
            keyboardScreen.face().collectRegions(regions);   // 显示着的那块（豆花布局 / 实体键盘），只报它
            if (keyboardScreen.isPadUsable()) addRegion(regions, miniPad, Proto.REGION_PAD, 0);
            return regions;
        }
        if (airOn) {   // 空中鼠标页：只报页上的按钮，碰到别处没有反应
            addRegion(regions, airRound, Proto.REGION_BUTTON, AIR_ROUND_ID);   // 叠在四个格子的角上，排最前
            for (int i = 0; i < airSlots.size(); i++) {
                TextView b = airSlots.get(i);
                if (b.getVisibility() != View.VISIBLE) continue;
                addRegion(regions, b, holdLeft(b) ? Proto.REGION_HOLD_LEFT : Proto.REGION_BUTTON, AIR_SLOT_BASE_ID + i);
            }
            addRegion(regions, airCenter, Proto.REGION_BUTTON, AIR_CENTER_ID);
            addRegion(regions, airExit, Proto.REGION_BUTTON, AIR_EXIT_ID);
            return regions;
        }
        // ⋯ 和四角的小按钮叠在触控板上，要排在触控板前面（服务按顺序找第一个命中的区域）；
        // 窄触控板上 ⋯ 会挨着左上 / 右上角的按钮，⋯ 排最前（画的时候也在最上面）
        addRegion(regions, padMenuButton, Proto.REGION_BUTTON, PAD_MENU_ID);
        for (int i = 0; i < cornerButtons.size(); i++) {
            TextView b = cornerButtons.get(i);
            if (b.getVisibility() == View.VISIBLE) {
                addRegion(regions, b, holdLeft(b) ? Proto.REGION_HOLD_LEFT : Proto.REGION_BUTTON, CORNER_BASE_ID + i);
            }
        }
        if (padStack.keysShown()) addMouseKeys(regions, padMouseKeys);
        addRegion(regions, pad, Proto.REGION_PAD, 0);
        // 自定义按键：整列藏着时（设置里关着，默认）一个都不报（按钮自己的 visibility 还是 VISIBLE）
        for (int id = 0; strip.getVisibility() == View.VISIBLE && id < stripButtons.size(); id++) {
            TextView b = stripButtons.get(id);
            if (b.getVisibility() != View.VISIBLE || !(b.getTag() instanceof Actions.Action)) continue;
            addRegion(regions, b, holdLeft(b) ? Proto.REGION_HOLD_LEFT : Proto.REGION_BUTTON, id);
        }
        if (numpad.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < numpadButtons.size(); i++) {
                addRegion(regions, numpadButtons.get(i), Proto.REGION_KEY, numpadUsages.get(i));
            }
        }
        return regions;
    }

    /**
     * 报给服务：尺寸是整块屏的（服务按它把触摸屏坐标转到当前方向；分屏时窗口比屏小，以前报窗口大小，横过来就转错），
     * 再带上窗口在屏上的范围（和区域同一时刻、同一套坐标：layoutOrigin + 根视图大小）、占没占满、
     * 贴着窗口边的系统手势带宽度（窗口的 systemGestures 插图）——桌面模式分屏时服务据此把另一边的、手势带里的手指注入回系统。
     */
    private void sendLayout(boolean active, List<int[]> regions) {
        Display d = getDisplay();
        Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
        View root = getWindow().getDecorView();
        int[] window = {layoutOrigin[0], layoutOrigin[1], layoutOrigin[0] + root.getWidth(), layoutOrigin[1] + root.getHeight()};
        WindowInsets wi = root.getRootWindowInsets();
        Insets g = wi != null ? wi.getInsets(WindowInsets.Type.systemGestures()) : Insets.NONE;
        int top = g.top;
        if (!windowFull) {
            // 小窗的标题栏（拖动、最小化、关闭）也算上边的手势带、归系统，不然接管期间小窗拖不动、关不掉。两种画法都兜住：
            // WM Shell 画在任务范围内的（captionBar 插图），和 decor 里自带的（老式 DecorCaptionView / 三星：内容视图比 decor 低一截）。
            // 全屏时服务不看手势带，不算（前提：全屏时内容视图顶边和 decor 顶边对齐）
            int capTop = wi != null ? wi.getInsets(WindowInsets.Type.captionBar()).top : 0;
            top = Math.max(top, Math.max(capTop, decorCapOf()));
        }
        // decor 自带的标题栏是豆花窗口里的 View：注入回来的手指按在这一截时要交给它（dispatchTouchEvent），不能吞掉
        reportedDecorCap = windowFull ? 0 : decorCapOf();
        reportedBand[0] = g.left;
        reportedBand[1] = top;
        reportedBand[2] = g.right;
        reportedBand[3] = g.bottom;
        if (!windowFull) {
            // 分屏 / 小窗时 ⋯ 菜单的"点外面"裁掉四边的手势带（含标题栏）：带里是空白处，照透传规则交给系统认手势（返回、回桌面、拖小窗）。
            // 系统没认成手势、注入回豆花窗口的，dispatchTouchEvent 里 injectedGesture 那条吞掉，菜单留着。菜单项、底板是真按钮，不裁
            int il = window[0] + reportedBand[0], it = window[1] + reportedBand[1];
            int ir = window[2] - reportedBand[2], ib = window[3] - reportedBand[3];
            for (Iterator<int[]> i = regions.iterator(); i.hasNext(); ) {
                int[] r = i.next();
                if (r[1] != MENU_OUTSIDE_ID) continue;
                r[2] = Math.max(r[2], il);
                r[3] = Math.max(r[3], it);
                r[4] = Math.min(r[4], ir);
                r[5] = Math.min(r[5], ib);
                if (r[2] >= r[4] || r[3] >= r[5]) i.remove();
            }
        }
        link.layout(active, btMode, d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY, d != null ? d.getRotation() : 0,
                screen.width(), screen.height(), windowFull, window, reportedBand.clone(), regions);
    }

    /** decor 里自带的标题栏有多高（内容视图比 decor 低的那一截，老式 DecorCaptionView / 三星小窗）；没有为 0。 */
    private int decorCapOf() {
        View content = findViewById(android.R.id.content);
        if (content == null) return 0;
        content.getLocationOnScreen(tmpLoc);
        return Math.max(0, tmpLoc[1] - layoutOrigin[1]);
    }

    /** "左键"按钮：鼠标左键由分流直接按住 / 松开（REGION_HOLD_LEFT），和触控板的手势合在一起算拖动。 */
    private static boolean holdLeft(View b) {
        return b.getTag() instanceof Actions.Action && ((Actions.Action) b.getTag()).kind == Actions.Kind.HOLD_LEFT;
    }

    /** 三个鼠标键各报一块（id = MOUSE_BUTTON_ID_BASE | 键位）。 */
    private static void addMouseKeys(List<int[]> regions, MouseKeys keys) {
        for (int i = 0; i < 3; i++) {
            addRegion(regions, keys.key(i), Proto.REGION_MOUSE_BUTTON, Proto.MOUSE_BUTTON_ID_BASE | MouseKeys.MASKS[i]);
        }
    }

    private static void addRegion(List<int[]> regions, View v, int type, int id) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        regions.add(new int[]{type, id, loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()});
    }

    // ═══════════ 按钮和键 ═══════════

    /** 服务没接管时，左侧按钮按普通方式响应触摸，走和服务事件同一套处理。 */
    private boolean localTouch(View v, MotionEvent e, int id) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onButton(id, Proto.BUTTON_DOWN);
                return true;
            case MotionEvent.ACTION_UP:
                boolean inside = e.getX() >= 0 && e.getX() <= v.getWidth() && e.getY() >= 0 && e.getY() <= v.getHeight();
                onButton(id, inside ? Proto.BUTTON_TAP : Proto.BUTTON_UP);
                return true;
            case MotionEvent.ACTION_CANCEL:
                onButton(id, Proto.BUTTON_UP);
                return true;
            default:
                return true;
        }
    }

    @Override
    public void onButton(int id, int action) {
        boolean down = action == Proto.BUTTON_DOWN;
        if (id >= Proto.KEY_ID_BASE) {   // 键盘键：按键本身服务已经发了，这里只管画面和震动
            int key = id - Proto.KEY_ID_BASE;
            if (down) keyHaptic();
            keyboardScreen.face().showPressed(key, down);
            for (int i = 0; i < numpadButtons.size(); i++) {
                if (numpadUsages.get(i) == key) numpadLook(i, down);
            }
            return;
        }
        if (Proto.isGamepadId(id)) {   // 手柄控件：报文本身分流已经发了，按下的样子看报文画；这里只震能打断的"嗒"（同键盘键）
            if (down) keyHaptic();
            return;
        }
        if (id == GP_MENU_ID) {   // 手柄页的 ⋯：按住 0.6 秒才开菜单，不到就松开什么都不做
            Style.bare(gpMenuButton, down && !menuLayer.isOpen());
            main.removeCallbacks(gpMenuLater);
            if (down && gamepadOn && !menuLayer.isOpen()) main.postDelayed(gpMenuLater, GP_MENU_HOLD_MS);
            return;
        }
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID || id == KeyboardPanel.GLOBE_BUTTON_ID
                || id == KeyboardPanel.MENU_BUTTON_ID || (id >= KeyboardPanel.ROW_ACTION_BASE_ID && id < KeyboardPanel.ROW_ACTION_BASE_ID + keyboardRow.size())) {
            if (down) onKeyHaptic(id, true);
            keyboardScreen.face().showPressed(id, down);
            if (action == Proto.BUTTON_TAP) onSpecialKey(id);
            return;
        }
        if (id == Proto.STICK_ID) {   // 小红点：只画样子（轻点的"嗒"由分流在轻点成立时发）
            kbStick.setPressedLook(down);
            return;
        }
        if (id >= Proto.MOUSE_BUTTON_ID_BASE && id < Proto.MOUSE_BUTTON_ID_BASE + 8) {
            mouseKeyButton(id - Proto.MOUSE_BUTTON_ID_BASE, down);
            return;
        }
        if (id >= CORNER_BASE_ID && id < CORNER_BASE_ID + cornerButtons.size()) {
            TextView b = cornerButtons.get(id - CORNER_BASE_ID);
            Style.small(b, down, cornersOnImage);
            buttonAction((Actions.Action) b.getTag(), action, AirMouse.SRC_CORNER + id - CORNER_BASE_ID);
            return;
        }
        if (id == KB_MENU_ID || id == PAD_MENU_ID) {
            Style.bare(id == KB_MENU_ID ? kbMenuButton : padMenuButton, down);
            if (action == Proto.BUTTON_TAP) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                setMenu(!menuLayer.isOpen());
            }
            return;
        }
        if (id == MENU_OUTSIDE_ID) {
            if (!down) setMenu(false);   // 抬起时关（按下就关的话，这次手势剩下的部分会落到新报的区域上）
            return;
        }
        if (id == MENU_PANEL_ID) return;
        int menuIndex = menuIndex(id);
        if (menuIndex >= 0) {
            Style.look(menuItems.get(menuIndex), false, down);
            if (action == Proto.BUTTON_TAP && menuLayer.isOpen()) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                onMenuItem(id);
            }
            return;
        }
        if (id == KB_DONE_ID || id == KB_RESET_ID) {
            Style.look(id == KB_DONE_ID ? kbDone : kbReset, false, down);
            if (action == Proto.BUTTON_TAP && keyboardScreen.isAdjusting()) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                onAdjustBar(id);
            }
            return;
        }
        boolean airPageId = id == AIR_ROUND_ID || id == AIR_EXIT_ID || id == AIR_CENTER_ID
                || (id >= AIR_SLOT_BASE_ID && id < AIR_SLOT_BASE_ID + airSlots.size());
        if (airPageId && airPaused()) {
            // 键盘盖着空中鼠标页：页上的按钮看不见了。分流按按下时记的区域报，键盘打开前就按着的手指抬起时仍报到这里，
            // 轻点当松开：按住类照常松开，点按类不做（不然一个 TAP 就锁定移动、在暂停中跟起来）
            if (down) return;
            action = Proto.BUTTON_UP;
        }
        if (id == AIR_ROUND_ID) {   // 圆钮：按着就移动；点一下（没到 HOLD_MS 就松开、还在圆钮上）锁定移动，再点一下解锁
            airRoundPressed = down;
            if (down) {
                airRoundDownAt = SystemClock.uptimeMillis();
                haptics.play(GestureEngine.HAPTIC_CLICK);
                air.holdFollow(AirMouse.SRC_ROUND, true);
            } else {
                air.holdFollow(AirMouse.SRC_ROUND, false);
                if (action == Proto.BUTTON_TAP && SystemClock.uptimeMillis() - airRoundDownAt < HardKeys.HOLD_MS) {
                    airRoundLocked = !airRoundLocked;
                    air.holdFollow(AirMouse.SRC_ROUND_LOCK, airRoundLocked);
                    if (!airRoundLocked) haptics.play(GestureEngine.HAPTIC_DRAG_END);   // 解锁再"嗒"一下，和锁定分得开
                }
            }
            airRound.setState(air.following(), airRoundPressed);
            return;
        }
        if (id >= AIR_SLOT_BASE_ID && id < AIR_SLOT_BASE_ID + airSlots.size()) {
            TextView b = airSlots.get(id - AIR_SLOT_BASE_ID);
            buttonAction((Actions.Action) b.getTag(), action, AirMouse.SRC_AIR_PAGE + id - AIR_SLOT_BASE_ID);
            Style.look(b, isActive(b), down);
            return;
        }
        if (id == AIR_EXIT_ID) {   // 按住 0.6 秒才退出
            Style.look(airExit, false, down);
            main.removeCallbacks(airExitLater);
            if (down) main.postDelayed(airExitLater, AIR_EXIT_HOLD_MS);
            return;
        }
        if (id == AIR_CENTER_ID) {   // 回正中（绝对坐标）
            Style.look(airCenter, false, down);
            if (action == Proto.BUTTON_TAP) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                centerPointer();
            }
            return;
        }
        if (id < 0 || id >= stripButtons.size()) return;
        TextView b = stripButtons.get(id);
        if (!(b.getTag() instanceof Actions.Action)) return;
        buttonAction((Actions.Action) b.getTag(), action, AirMouse.SRC_STRIP + id);
        Style.look(b, isActive(b), down);
    }

    /**
     * 按钮条、角上按钮、空中鼠标页的按钮共用：按住类的动作按下 / 松开各做一半，别的动作轻点（松手时还在按钮上）才做。
     * source 区分是哪个按钮按着（AirMouse.SRC_*），一个松开不影响别的。
     */
    private void buttonAction(Actions.Action a, int action, int source) {
        boolean down = action == Proto.BUTTON_DOWN;
        switch (a.kind) {
            case HOLD_LEFT:   // 鼠标左键由分流直接按住 / 松开（REGION_HOLD_LEFT），这里只管震动
                leftFollow(source, down);
                holdLeftFeedback(down);
                break;
            case AIR_MOVE:
            case AIR_DRAG:
            case WHEEL:
                if (down) actionDown(a, source);
                else actionUp(a, source);
                break;
            default:
                if (action == Proto.BUTTON_TAP) fire(a);
                break;
        }
    }

    /** 按住类动作的按下（按钮按下、音量键按住到点）。不是按住类的动作在这时做一次。 */
    private void actionDown(Actions.Action a, int source) {
        switch (a.kind) {
            case AIR_MOVE:
                if (!airReady() || airPaused()) return;   // 键盘盖着空中鼠标页：不跟随（兜底）
                haptics.play(GestureEngine.HAPTIC_CLICK);
                air.holdFollow(source, true);
                break;
            case AIR_DRAG:
                if (airReady() && !airPaused()) air.holdDrag(source, true);   // 震动（开始拖动 + 持续轻震）由 AirMouse 发
                break;
            case HOLD_LEFT:   // 音量键按住（屏幕上的"左键"按钮走分流，不经过这里）
                holdLeft(source, true);
                leftFollow(source, true);
                holdLeftFeedback(true);
                break;
            case WHEEL:   // 震动是每格的咔嗒（同触控板滚动），不另外"嗒"
                wheelSource = source;
                wheelDir = a.code;
                main.removeCallbacks(wheelRepeat);
                wheel(wheelDir);
                main.postDelayed(wheelRepeat, WHEEL_DELAY_MS);
                break;
            default:
                fire(a);
                break;
        }
    }

    private void actionUp(Actions.Action a, int source) {
        switch (a.kind) {
            case AIR_MOVE:
                air.holdFollow(source, false);
                break;
            case AIR_DRAG:
                air.holdDrag(source, false);
                break;
            case HOLD_LEFT:
                holdLeft(source, false);
                leftFollow(source, false);
                holdLeftFeedback(false);
                break;
            case WHEEL:
                if (source == wheelSource) stopWheel();
                break;
            default:
                break;
        }
    }

    /**
     * 滚一格：桌面模式、蓝牙模式下服务接管着（转发）时交给服务的分流（服务那边转着的惯性先停，转发模式下报文照常发回来由蓝牙发）；
     * 蓝牙模式下服务没接管时直接发（滚轮单位 1/120 格），带上两路按着的键，本地分流转着的惯性先停。
     */
    private void wheel(int n) {
        if (btMode && !(grabbed && link.isConnected())) {
            local.stopSpin(false);
            bt.sendMouse(touchButtons | air.buttons(), 0, 0, n * Hid.WHEEL_UNIT, 0);
        } else {
            link.wheel(n);
        }
        if (scrollTicksOn) haptics.play(GestureEngine.HAPTIC_TICK);
    }

    private void stopWheel() {
        wheelDir = 0;
        wheelSource = Integer.MIN_VALUE;
        main.removeCallbacks(wheelRepeat);
    }

    /**
     * 实体键按住"左键"：蓝牙模式经空中指针那一路发；桌面模式鼠标在服务里，经服务的虚拟鼠标按住（CMD_HOLD_LEFT，
     * 和屏幕上的"左键"按钮共用服务里的计数）。
     */
    private void holdLeft(int source, boolean down) {
        if (btMode) {
            air.holdLeft(source, down);
            return;
        }
        boolean was = !serverLeftSources.isEmpty();
        if (down) serverLeftSources.add(source);
        else serverLeftSources.remove(source);
        boolean now = !serverLeftSources.isEmpty();
        if (now != was) link.holdLeft(now);
    }

    /**
     * 三个鼠标键按下 / 松开（鼠标键本身由分流按，这里只管样子和震动）：左键和"左键"按钮一样（"嗒" + 持续轻震，松开停震再"嗒"，
     * 共用计数）；右键、中键按下"嗒"一下（右键按住拖少见，不嗡）。中键滚动的咔嗒由分流发。两组三键条一起画（只有一组看得见）。
     */
    private void mouseKeyButton(int mask, boolean down) {
        int i = mask == Proto.BUTTON_LEFT ? 0 : mask == Proto.BUTTON_RIGHT ? 1 : mask == Proto.BUTTON_MIDDLE ? 2 : -1;
        if (i < 0) return;
        if (!down && mouseKeysHeld[i] == 0) return;   // 收尾时已经清过（接管、断开），迟到的松开不再震
        mouseKeysHeld[i] = Math.max(0, mouseKeysHeld[i] + (down ? 1 : -1));
        int pressed = (mouseKeysHeld[0] > 0 ? Proto.BUTTON_LEFT : 0) | (mouseKeysHeld[1] > 0 ? Proto.BUTTON_RIGHT : 0)
                | (mouseKeysHeld[2] > 0 ? Proto.BUTTON_MIDDLE : 0);
        kbMouseKeys.setPressedMask(pressed);
        padMouseKeys.setPressedMask(pressed);
        if (mask == Proto.BUTTON_LEFT) holdLeftFeedback(down);   // 自己按计数（还有别的来源按着左键就接着嗡）
        else if (down) haptics.play(GestureEngine.HAPTIC_CLICK);
    }

    /** 三个鼠标键、小红点都画回松开的样子（接管 / 断开时，等不到它们的松开了）。 */
    private void clearMouseKeys() {
        java.util.Arrays.fill(mouseKeysHeld, 0);
        kbMouseKeys.setPressedMask(0);
        padMouseKeys.setPressedMask(0);
        kbStick.setPressedLook(false);
    }

    /** "左键"按着 = 拖动中：按下"嗒"一下再持续轻震，松开停震再"嗒"一下。屏幕上的"左键"按钮、音量键都是这一套。 */
    private void holdLeftFeedback(boolean down) {
        leftHolds = Math.max(0, leftHolds + (down ? 1 : -1));
        if (down) {
            haptics.play(GestureEngine.HAPTIC_CLICK);
            haptics.startHum();
        } else if (leftHolds > 0 || leftHeld()) {
            haptics.play(GestureEngine.HAPTIC_TICK);   // 别的来源还按着左键：还在拖，"嗒"一下接着嗡
        } else {
            haptics.play(GestureEngine.HAPTIC_DRAG_END);
        }
    }

    /**
     * 空中鼠标页里按住左键 = 光标跟着手机走（拖动），用户 2026-10-15："左键点击按住的时候自动解锁移动"，
     * 要的是边按边拖、不用另外按着圆钮。按下 LEFT_FOLLOW_DELAY_MS 后才开始跟：快点一下就是单击，按的那一下手抖不带走光标。
     * （第一版理解反了，做成按左键就停掉锁定的移动，结果按着左键根本拖不动。）
     * 只在空中鼠标页：主界面上按着"左键"是配合触控板拖动，手机转动不该带着光标走。
     */
    private void leftFollow(int source, boolean down) {
        Runnable pending = leftFollowPending.remove(source);
        if (pending != null) main.removeCallbacks(pending);
        if (!down) {
            air.holdFollow(source, false);
            return;
        }
        if (!airOn || airPaused() || !btMode) return;   // 键盘盖着空中鼠标页时也不跟（兜底）
        Runnable r = () -> {
            leftFollowPending.remove(source);
            air.holdFollow(source, true);
        };
        leftFollowPending.put(source, r);
        main.postDelayed(r, LEFT_FOLLOW_DELAY_MS);
    }

    /** 两路里还按着左键没有（蓝牙模式才知道；桌面模式下左键的状态在服务那边）。 */
    private boolean leftHeld() {
        return btMode && ((touchButtons | air.buttons()) & Proto.BUTTON_LEFT) != 0;
    }

    /** 回正中；按着鼠标键（拖动中）时不回：甩角会把拖着的东西带到角上、路过热角。 */
    private void centerPointer() {
        if ((touchButtons | air.buttons()) != 0) {
            haptics.play(GestureEngine.HAPTIC_GESTURE);
            return;
        }
        air.center();
    }

    /** 点一下就做的动作：震一下再做（按钮轻点、音量键点一下）。滚轮的震动是那一格的咔嗒，不另外"嗒"。 */
    private void fire(Actions.Action a) {
        if (a.kind == Actions.Kind.NONE) return;
        if (a.kind != Actions.Kind.WHEEL) haptics.play(GestureEngine.HAPTIC_CLICK);
        run(a);
    }

    /** 空中指针只在蓝牙模式、有陀螺仪时能用；不能用就提示一下。 */
    private boolean airReady() {
        if (!btMode) {
            toast(R.string.air_bt_only);
            return false;
        }
        if (!AirMouse.supported(this)) {
            toast(R.string.air_no_gyro);
            return false;
        }
        return true;
    }

    @Override
    public void onLocalKey(int key, boolean down) {
        if (btMode) local.key(key, down);
        else link.hidKey(key, down);
    }

    /** 键盘上的"符" / "Fn" / 🌐 / ⋯，和快捷行里不是键盘组合键的动作。 */
    @Override
    public void onSpecialKey(int id) {
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID) {
            keyboard.onLayerButton(id);
        } else if (id == KeyboardPanel.GLOBE_BUTTON_ID) {
            run(Actions.get("lang_switch"));
        } else if (id == KeyboardPanel.MENU_BUTTON_ID) {
            setMenu(!menuLayer.isOpen());
        } else if (id >= KeyboardPanel.ROW_ACTION_BASE_ID) {
            int i = id - KeyboardPanel.ROW_ACTION_BASE_ID;
            if (i < keyboardRow.size()) run(keyboardRow.get(i));
        }
    }

    // ═══════════ 键盘屏 ═══════════

    /** 键盘屏开 / 关。空中鼠标页上调出来时键盘盖在页面上面，空中鼠标暂停（见 {@link #airPaused}），收起回到页面。 */
    private void setKeyboardScreen(boolean on) {
        if (on && gamepadOn) setGamepadMode(false);   // 手柄页上不能调出键盘：先退出手柄页
        boolean was = keyboardScreen.getVisibility() == View.VISIBLE;
        setMenu(false);   // 菜单是上一屏的（两屏的项不一样）
        if (on) {
            closeTextBar();
            loadKeyboardRects();
        } else {
            setAdjusting(false);
        }
        if (airOn && on && !was) pauseAir();
        mainRow.setVisibility(on ? View.GONE : View.VISIBLE);
        keyboardScreen.setVisibility(on ? View.VISIBLE : View.GONE);
        applyAirOverlayVisibility();
        if (airOn && !on && was) resumeAirPage();
        updateActiveButtons();
        updateStatus();
        updateAirOverlay();
        pushLayout();
    }

    /**
     * ⋯ 菜单开 / 关（两屏触控板顶上的 ⋯、功能行上的 ⋯ 键、点外面、返回、换屏都走这里）。键盘调整模式里、空中鼠标页上（没有 ⋯）不开。
     * 打开时只显示当前那一屏的项（{@link #onMenuScreen}）。开着时区域只报菜单（collectRegions），
     * 返回先关菜单（OnBackInvokedCallback，优先于蓝牙模式吞返回的那个）。
     */
    private void setMenu(boolean on) {
        boolean keyboardShown = keyboardScreen.getVisibility() == View.VISIBLE;
        if (on && !gamepadOn && (keyboardShown ? keyboardScreen.isAdjusting() : airOn)) return;
        if (on == menuLayer.isOpen()) return;
        if (on) refreshMenuItems();
        main.removeCallbacks(gpMenuLater);
        menuLayer.setOpen(on);
        keyboardScreen.setMenuOpen(on);   // 键盘屏开着菜单时不画按键放大提示
        for (TextView b : menuItems) Style.look(b, false, false);
        Style.bare(kbMenuButton, false);
        Style.bare(padMenuButton, false);
        Style.bare(gpMenuButton, false);
        if (on != menuBack) {
            if (on) {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, closeMenu);
            } else {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeMenu);
            }
            menuBack = on;
        }
        updateMenuLabels();
        pushLayout();
    }

    /** 菜单里显示这一屏的项（打开时、显示的那块键盘换了时）。 */
    private void refreshMenuItems() {
        boolean keyboardShown = keyboardScreen.getVisibility() == View.VISIBLE;
        int screen = gamepadOn ? SCREEN_GAMEPAD : keyboardShown ? SCREEN_KEYBOARD : SCREEN_MAIN;
        boolean stick = prefs.keyboardPointer() == KeyboardPlan.POINTER_STICK;
        boolean fixed = keyboardScreen.isFixedShown();
        for (int i = 0; i < MENU_IDS.length; i++) {
            menuItems.get(i).setVisibility(onMenuScreen(MENU_IDS[i], screen, !btMode, stick, fixed) ? View.VISIBLE : View.GONE);
        }
    }

    /** 菜单项被点了：布局轮换、换键盘时菜单不关（好连着点到想要的那种），其余关了菜单再做。 */
    private void onMenuItem(int id) {
        switch (id) {
            case MENU_KEYBOARD_ID:
                setKeyboardScreen(true);   // 里面关菜单
                break;
            case MENU_GAMEPAD_ID:
                setGamepadMode(true);   // 里面关菜单
                break;
            case GP_BACK_ID:
                setGamepadMode(false);
                break;
            case GP_PRESET_ID: {
                // 预设轮换（同"布局"那项：菜单不关，好连着点到想要的；存过自定义的话最后轮到它）；换了以后两路的手柄都回中立
                prefs.setGamepadPreset(prefs.nextGamepadPreset());
                resetGamepad();
                gamepadScreen.setSpec(prefs.gamepadSpec());
                updateMenuLabels();
                break;
            }
            case MENU_SETTINGS_ID:
                if (gamepadOn) {   // 手柄页上的"设置"：直接停在"手柄"那一页（预设、自定义、手感都在那）
                    setMenu(false);
                    startActivity(SettingsActivity.tabIntent(this, SettingsActivity.TAB_GAMEPAD));
                    break;
                }
                // 不在手柄页：往下落，同 Actions 里的 settings（停在上次那一页）
            case MENU_ROTATE_ID:
            case MENU_EXIT_ID:
                // 同 Actions 里的 settings / rotate / exit（照按钮条上"设置"的做法打开设置页）；"嗒"已经震过，不再 fire
                setMenu(false);
                run(Actions.get(id == MENU_SETTINGS_ID ? "settings" : id == MENU_ROTATE_ID ? "rotate" : "exit"));
                break;
            case KB_CLOSE_ID:
                setKeyboardScreen(false);   // 里面关菜单
                break;
            case KB_ADJUST_ID:
                setAdjusting(true);   // 里面关菜单
                break;
            case KB_LAYOUT_ID: {
                // 分裂 / 合拢时按着的键、放大提示、按下的样子由 KeyboardPanel.setSplit 收掉；菜单跟着 ⋯ 挪，区域随重新布局报上去
                int mode = KeyboardPlan.nextMode(prefs.keyboardLayoutMode());
                prefs.setKeyboardLayoutMode(mode);
                keyboardScreen.setKeyboardLayout(mode);
                updateMenuLabels();
                break;
            }
            case KB_POINTER_ID: {
                // 触控板 → 小红点 → 关；⋯ 可能跟着换地方，关菜单。显示的是实体键盘时换它自己那份（Boards），不碰豆花布局的 kb_pointer
                if (keyboardScreen.isFixedShown()) {
                    int pointer = KeyboardPlan.nextPointer(boards.pointer());
                    boards.setPointer(pointer);
                    keyboardScreen.setFixedPointer(pointer);
                } else {
                    int pointer = KeyboardPlan.nextPointer(prefs.keyboardPointer());
                    prefs.setKeyboardPointer(pointer);
                    keyboardScreen.setPointer(pointer);
                }
                loadKeyboardRects();   // 每种指点状态各有一份键盘位置（有触控板时避让它，没有时不避让）
                setMenu(false);
                break;
            }
            case MENU_BOARD_ID:
                switchBoard(!boards.fixedSelected());   // 菜单不关（好连点）；⋯ 挪了位置菜单跟着走
                break;
            default:
                break;
        }
    }

    /** 调整栏：完成 = 退出调整模式；恢复默认只恢复眼下这种布局的（分裂 / 合并各一份），回到按窗口给的默认位置。 */
    private void onAdjustBar(int id) {
        if (id == KB_DONE_ID) {
            setAdjusting(false);
            return;
        }
        boolean landscape = keyboardScreen.isLandscape();
        if (keyboardScreen.isFixedShown()) {   // 实体键盘：只恢复它现在这个尺寸、这种指点、这个方向的那份
            boards.resetRect(landscape);
            keyboardScreen.setFixedRect(null);
            return;
        }
        int pointer = prefs.keyboardPointer();   // 只恢复眼下这种指点状态的那份（触控板 / 小红点 / 关各记各的）
        if (keyboardScreen.isSplit()) {
            prefs.resetSplitRect(landscape, pointer);
            keyboardScreen.setSplitRect(null);
        } else {
            prefs.resetKeyboardRect(landscape, pointer);
            keyboardScreen.setRect(null);
        }
    }

    /**
     * 调整模式：服务释放触摸屏，拖动由 App 自己收。分裂时两半对称着调（KeyboardScreen）。
     * 临时出现一条"完成 / 恢复默认"，触控板角上的 ⋯ 藏起来；菜单先关掉。
     */
    private void setAdjusting(boolean on) {
        if (on) setMenu(false);
        keyboardScreen.setAdjusting(on);
        Style.look(kbDone, false, false);
        Style.look(kbReset, false, false);
        updateStatus();
        pushLayout();
    }

    /** 菜单里"键盘""布局""指点"几项写着当前选的（"指点"是显示着的那块键盘的）。 */
    private void updateMenuLabels() {
        int mode = prefs.keyboardLayoutMode();
        menuItems.get(menuIndex(KB_LAYOUT_ID)).setText(mode == KeyboardPlan.MODE_SPLIT ? R.string.kb_layout_split
                : mode == KeyboardPlan.MODE_MERGE ? R.string.kb_layout_merge : R.string.kb_layout_auto);
        TextView board = menuItems.get(menuIndex(MENU_BOARD_ID));
        if (!boards.fixedSelected()) board.setText(R.string.kbd_menu_douhua);
        else if (fixedKeyboard.layout() == null) board.setText(R.string.kbd_menu_no_data);   // 键帽数据读不出来：照样显示豆花布局（FixedLayout.forLegends）
        else if (keyboardScreen.getVisibility() == View.VISIBLE && !keyboardScreen.fixedFitsNow()) board.setText(R.string.kbd_menu_narrow);
        else board.setText(getString(R.string.kbd_menu_fixed, FixedData.shortName(this, boards.spec())));
        int pointer = keyboardScreen.isFixedShown() ? boards.pointer() : prefs.keyboardPointer();
        menuItems.get(menuIndex(KB_POINTER_ID)).setText(pointer == KeyboardPlan.POINTER_STICK ? R.string.kb_pointer_stick
                : pointer == KeyboardPlan.POINTER_NONE ? R.string.kb_pointer_off : R.string.kb_pointer_pad);
        menuItems.get(menuIndex(GP_PRESET_ID)).setText(getString(R.string.gp_preset, GamepadScreen.name(this, prefs.gamepadSpec())));
    }

    @Override
    public void onKeyboardRectChanged(boolean landscape, boolean split, boolean fixed, float[] rect) {
        if (fixed) {   // 实体键盘存它自己那份（现在这个尺寸、这种指点），不碰豆花布局的 kb_*
            boards.setRect(landscape, rect);
            return;
        }
        int pointer = prefs.keyboardPointer();
        if (split) prefs.setSplitRect(landscape, pointer, rect);
        else prefs.setKeyboardRect(landscape, pointer, rect);
    }

    /** 横竖屏 × 指点三种状态（触控板 / 小红点 / 关）各有一份合并、一份分裂的键盘大小位置。 */
    private void loadKeyboardRects() {
        boolean landscape = keyboardScreen.isLandscape();
        int pointer = prefs.keyboardPointer();
        keyboardScreen.setRect(prefs.savedKeyboardRect(landscape, pointer));
        keyboardScreen.setSplitRect(prefs.savedSplitRect(landscape, pointer));
        keyboardScreen.setFixedRect(boards.savedRect(landscape));   // 实体键盘自己那份（尺寸 × 指点 × 横竖屏）
    }

    @Override
    public void onKeyboardFaceChanged() {
        // 实体键盘放不下 / 又放得下了（转屏、拖分隔条）：菜单开着的话"布局"那一项跟着出现 / 消失，"键盘""指点"的字跟着换
        if (menuLayer.isOpen()) refreshMenuItems();
        updateMenuLabels();
        pushLayout();
    }

    /**
     * 实体键盘的设置（设置页改完回来、打开时）：是哪一块（语言、形状、尺寸、模式）、选没选它、它自己的指点。
     * 设置没变就不重建面板（setBoard 会松开按着的键）。
     */
    private void applyBoards() {
        Boards.Spec spec = boards.spec();
        if (!spec.equals(shownSpec) || fixedKeyboard.layout() == null) {
            shownSpec = spec;
            fixedKeyboard.setBoard(FixedData.layout(this, spec), FixedData.table(this, spec.lang, spec.mode));
        }
        keyboardScreen.setFixedWanted(boards.fixedSelected());
        keyboardScreen.setFixedPointer(boards.pointer());
        keyboardScreen.setFixedRect(boards.savedRect(keyboardScreen.isLandscape()));
    }

    /** 面板上现在是哪一块实体键盘（applyBoards 比它，没变不重建）。 */
    private Boards.Spec shownSpec;

    /**
     * 换键盘（⋯ 菜单"键盘"那一项）：只改"用哪块"（Boards.select 只写 kbd_board），豆花布局的设置（kb_ 开头的）一个都不写、面板不销毁，
     * 层（字母 / ?123 / Fn）和锁住的修饰键照旧（方案 §4.3）。要藏起来的那块松开面板自己收的手指、收掉按下的样子；
     * 服务那边按着的手指不用管（TouchRouter 按手指记着按下时的区域，抬起照旧松开原来的键；菜单开着时只报菜单，键盘上一般也没手指）。
     */
    private void switchBoard(boolean fixed) {
        if (fixed == boards.fixedSelected()) return;
        KeyboardFace hidden = keyboardScreen.face();
        hidden.releaseTouches();
        hidden.clearPressed();
        boards.select(fixed);
        keyboardScreen.setFixedWanted(fixed);
        updateMenuLabels();
        pushLayout();
    }

    @Override
    public void onKeyboardSnap() {
        haptics.play(GestureEngine.HAPTIC_TICK);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Display disp = getDisplay();
        if (disp != null) air.setRotation(disp.getRotation());
        // 横竖屏各有一份键盘大小
        if (keyboardScreen.getVisibility() == View.VISIBLE) loadKeyboardRects();
        // Fold 展开、挪到外接屏：窗口最大能有多大变了，背景图要的比手上的大 5% 以上就重新解
        padImage.setMaxSide(maxWindowSide());
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 进程退到后台了：放掉背景图的位图（最多约 32MB），下次 onStart 的 applyPrefs 重新解。去设置页（UI_HIDDEN）不放，回来不闪
        if (level >= TRIM_MEMORY_BACKGROUND) padImage.release();
    }

    /** 窗口最大可能的长边（像素）：背景图按它解码，画在任何形状的触控板上都不用再放大。 */
    private int maxWindowSide() {
        Rect b = getWindowManager().getMaximumWindowMetrics().getBounds();
        return Math.max(b.width(), b.height());
    }

    /** 背景图来了 / 没了 / 设置变了：两块触控板重画；"有没有图"变了给角上的小按钮换底（⋯ 的底由触控板自己垫）。 */
    private void onPadImageChanged() {
        pad.imageChanged();
        miniPad.imageChanged();
        boolean on = padImage.shown();
        if (on == cornersOnImage) return;
        cornersOnImage = on;
        for (TextView b : cornerButtons) Style.small(b, false, on);
    }

    @Override
    public void onKeyHaptic(int id, boolean special) {
        // 快捷行里的"滚轮"：那一格自己咔嗒（同触控板滚动），按下不再"嗒"
        int i = id - KeyboardPanel.ROW_ACTION_BASE_ID;
        if (special && i >= 0 && i < keyboardRow.size() && keyboardRow.get(i).kind == Actions.Kind.WHEEL) return;
        keyHaptic();
    }

    private void keyHaptic() {
        haptics.playKey();
    }

    @Override
    public void onRelayMouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (btMode && !stopped) sendTouchMouse(buttons, dx, dy, wheel, pan);
    }

    @Override
    public void onRelayKeyboard(byte[] report) {
        if (btMode && !stopped) bt.sendKeyboard(report);
    }

    @Override
    public void onModifiers(int mask) {
        latchedMods = mask;
        keyboard.setModifiers(mask);   // 两块都收：切换键盘时锁住的修饰键还按着，新显示的那块上有这个键就亮
        fixedKeyboard.setModifiers(mask);
    }

    // ═══════════ 动作 ═══════════

    private void run(Actions.Action a) {
        switch (a.kind) {
            case HID:   // 虚拟键盘按一下这个组合键
                onLocalKey(a.hidCode(appleHost), true);
                onLocalKey(a.hidCode(appleHost), false);
                break;
            case KEY:
                key(a.code, a.meta);
                break;
            case CLICK:
                click(a.code);
                break;
            case HOLD_LEFT:
                click(Proto.BUTTON_LEFT);   // 分给手势时就是单击
                break;
            case WHEEL:   // 只能点一下的地方：滚一格
                wheel(a.code);
                break;
            case KEYBOARD:
                setKeyboardScreen(keyboardScreen.getVisibility() != View.VISIBLE);
                break;
            case NUMPAD:
                if (gamepadOn) setGamepadMode(false);   // 数字键盘在主界面上：先退出手柄页
                if (keyboardScreen.getVisibility() == View.VISIBLE) setKeyboardScreen(false);
                showPanel(numpad.getVisibility() == View.VISIBLE ? null : numpad);
                break;
            case PHONE_TEXT:
                if (btMode) {   // 打的字经手机剪贴板贴过去，剪贴板到不了蓝牙那头
                    toast(R.string.bt_no_phone_text);
                } else if (textBar.getVisibility() == View.VISIBLE) {
                    closeTextBar();
                } else {
                    openTextBar();
                }
                break;
            case SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case EXIT:
                finish();
                break;
            case ROTATE:
                rotate();
                break;
            case AIR_MOUSE:
                // 键盘盖着空中鼠标页时 = 收起键盘回到空中鼠标页（人看到的是键盘，多半是想回去，不是退出）
                if (airPaused()) setKeyboardScreen(false);
                else if (airReady()) setAirMode(!airOn);
                break;
            case AIR_MOVE:   // 只能点一下的地方（手势、音量键点一下）：点一下开始跟随，再点一下停
                if (airReady() && !airPaused()) air.toggleFollow();   // 暂停中（小触控板手势、键盘最下面一排）不跟随，不提示
                break;
            case AIR_DRAG:
                if (airReady() && !airPaused()) air.toggleDrag();
                break;
            case AIR_CENTER:
                if (airReady()) centerPointer();
                break;
            case GAMEPAD:
                if (btMode) toast(R.string.gp_desktop_only);   // 蓝牙那批（第三批）之前进不了
                else setGamepadMode(!gamepadOn);
                break;
            default:
                break;
        }
    }

    /** 键盘上没有的系统键：桌面模式注入到外接屏（没接外接屏就发给当前焦点），蓝牙模式发对应的媒体键（没有通用媒体键的提示一下）。 */
    private void key(int keyCode, int meta) {
        if (!btMode) {
            link.key(targetDisplay, keyCode, meta);
            return;
        }
        int usage = BtHid.consumerUsage(keyCode);
        if (usage != 0) bt.pressConsumer(usage);
        else toast(R.string.bt_no_such_key);
    }

    private void click(int button) {
        if (!btMode) {
            link.click(button);
            return;
        }
        // 蓝牙：直接发，带上两路按着的键（经本地分流的话，转发模式下会把服务那一路按着的左键盖掉）
        int held = touchButtons | air.buttons();
        bt.sendMouse(held | button, 0, 0, 0, 0);
        bt.sendMouse(held, 0, 0, 0, 0);
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    /** 数字键盘开 / 关（panel = null 表示关）。 */
    private void showPanel(View panel) {
        if (panel != null) closeTextBar();
        numpad.setVisibility(panel == numpad ? View.VISIBLE : View.GONE);
        updateActiveButtons();
    }

    /**
     * 横竖屏切换：让服务把整块屏锁定到新方向（和系统导航栏的旋转建议按钮一样），整块屏真的转过去，
     * 触摸坐标也跟着系统走。App 自己请求方向不管用：这块屏设了"忽略 App 的方向请求"。
     * 竖 → 横时朝哪边看手机实际朝向：左侧朝上（顺时针转了 90°）→ ROTATION_270，否则 ROTATION_90。
     * 选的方向存起来，下次连上服务时自动转过去；豆花退出时服务恢复原来的方向。
     */
    private void rotate() {
        Display d = getDisplay();
        if (d == null) return;
        int current = d.getRotation();
        int target;
        if (current == Surface.ROTATION_0 || current == Surface.ROTATION_180) {
            target = tilt >= 45 && tilt < 135 ? Surface.ROTATION_270 : Surface.ROTATION_90;
        } else {
            target = Surface.ROTATION_0;
        }
        prefs.setRotation(target);
        if (link.isConnected()) {
            // 分屏 / 小窗时只记下选的方向：服务锁的是整块屏，另一边的应用会跟着转；回到全屏时 pushLayout 补上
            if (fillsDisplay()) link.rotate(d.getDisplayId(), target);
            else toast(R.string.rotate_split);
        } else if (btMode) {
            // 没有服务：只能请求系统转。一般手机管用；三星外屏这类"忽略 App 方向请求"的屏转不了
            requestOrientationSelf(target);
        }
    }

    /**
     * App 自己向系统请求方向（-1 = 不请求），同一个方向不重复请求。只在蓝牙模式下没有服务时用：
     * 有服务时由服务锁定整块屏，App 不能先请求——三星外屏忽略 App 的方向请求，系统会把豆花塞进竖屏中间的横框，
     * 服务紧接着转屏后窗口和触摸恢复了，画面却留在横框的位置，要再转一次屏才好（2026-10-14 主力机日志）。
     */
    private void requestOrientationSelf(int rotation) {
        if (rotation == requestedRotation) return;
        requestedRotation = rotation;
        setRequestedOrientation(rotation < 0 ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : orientationFor(rotation));
    }

    private static int orientationFor(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case Surface.ROTATION_180: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case Surface.ROTATION_270: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default: return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    private void openTextBar() {
        if (gamepadOn) setGamepadMode(false);   // 贴字面板在主界面上：先退出手柄页
        setMenu(false);
        showPanel(null);
        if (keyboardScreen.getVisibility() == View.VISIBLE) setKeyboardScreen(false);
        textBar.setVisibility(View.VISIBLE);
        updateActiveButtons();
        pushLayout();   // 先让服务释放触摸屏，输入法要靠触摸
        updateFocusable();   // 输入法要求窗口能获得焦点；收起时再改回去
        input.requestFocus();
        input.post(() -> {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.ime());
        });
    }

    private void closeTextBar() {
        if (textBar.getVisibility() != View.VISIBLE) return;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.hide(WindowInsets.Type.ime());
        input.clearFocus();
        textBar.setVisibility(View.GONE);
        updateFocusable();
        updateActiveButtons();
        pushLayout();
    }

    /**
     * 打的字经剪贴板贴过去：按键只能发按键码，发不了汉字。
     * 代价是会覆盖剪贴板原来的内容。
     */
    private void sendText() {
        String text = input.getText().toString();
        if (text.isEmpty()) {
            key(KeyEvent.KEYCODE_ENTER, 0);   // 空着按发送 = 回车，方便连续操作
            return;
        }
        ClipData clip = ClipData.newPlainText(getString(R.string.app_name), text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);   // 系统的"已复制"提示里不显示内容
        clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        key(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON);
        input.setText("");
    }

    // ═══════════ 服务回调 ═══════════

    @Override
    public void onGesture(int fingers, int kind) {
        run(Actions.get(prefs.gestureFor(fingers, kind)));
    }

    @Override
    public void onHaptic(int kind) {
        // 空中指针那一路按着左键（音量键按住左键）时在触控板上划 = 拖动：和"左键"按钮一样只有持续轻震，没有颗粒
        if (kind == GestureEngine.HAPTIC_TEXTURE && air.buttons() != 0) return;
        if (kind == GestureEngine.HAPTIC_DRAG_END && (air.buttons() & Proto.BUTTON_LEFT) != 0) kind = GestureEngine.HAPTIC_TICK;
        haptics.play(kind);
    }

    @Override
    public void onLinkStatus(boolean connected, boolean stuck, String message) {
        boolean wasConnected = linkOk;
        boolean wasGrabbed = grabbed;
        linkOk = connected;
        linkStuck = stuck;
        linkMessage = message;
        if (connected) {
            linkSeen = true;
            selfStart.onServiceConnected(link.serverApk());   // 服务还跑着 App 更新前的 APK：重启成新代码
            requestOrientationSelf(-1);   // 方向交给服务
            link.config(prefs.engineConfig(), density);
            link.gamepadConfig(prefs.gamepadConfig());
            gamepadFailed = false;
            // 新会话的手柄从中立开始：本地分流里还按着的手柄键也松掉（不然两边对不上，抬起时才补上）
            if (gamepadOn) local.setGamepadConfig(prefs.gamepadConfig());
            Display d = getDisplay();
            // 锁方向锁的是整块屏：分屏 / 小窗时不锁，免得另一边的应用跟着转（回到全屏时 pushLayout 补上）。
            // 这里直接问 fillsDisplay，windowFull 可能还没被 pushLayout 更新过；顺手记下，pushLayout 就不会再补一次
            windowFull = fillsDisplay();
            if (prefs.rotation() >= 0 && d != null && windowFull) link.rotate(d.getDisplayId(), prefs.rotation());
            if (keyLogOn) link.trace(true);   // 服务每条连接默认不记
            sendHardKeys();
            pushLayout();
        } else {
            // 连不上服务（没在跑 / 卡住 / 版本不一致 / 忙）：设置里开着"自动启动服务"就让 SelfStart 看一眼，
            // 该（重）启动就启动；忙（别的连接在用）它会认出服务在跑，什么都不做
            if (!autoStartAsked && prefs.autoStartService()) {
                autoStartAsked = true;
                selfStart.autoStart();
            }
            // 蓝牙模式下这次一直没连上服务：没有服务，自己请求方向。连上过又断的不请求，免得服务重启时被塞进横框
            if (btAcquired && !linkSeen && prefs.rotation() >= 0) requestOrientationSelf(prefs.rotation());
            grabbed = false;
            if (wasConnected) {   // 服务那一路的实体键按住等不到结束了；服务那边的左键随会话结束松开
                endServerHolds();
                serverLeftSources.clear();
                gamepadScreen.setReport(false, new byte[Hid.GAMEPAD_REPORT_SIZE]);   // 服务那一路的手柄随会话结束消失
            }
            // 连着的服务断了才收尾：服务没开时这里每秒来一次（连不上就重试），不能每次都打断本地分流里正按着的东西。
            // 蓝牙模式下服务没接管时，按钮、修饰键、拖动都归本地分流，也不用收
            if (wasConnected && (!btMode || wasGrabbed)) {
                if (btMode && touchButtons != 0) {   // 服务那一路等不到它发的松开了
                    touchButtons = 0;
                    bt.sendMouse(air.buttons(), 0, 0, 0, 0);
                }
                // 键盘同理：服务收尾时先关连接再松键，那份全零报文发不过来，主机那头锁住的修饰键、按着的键会一直按着
                if (btMode) bt.sendKeyboard(new byte[Hid.KEYBOARD_REPORT_SIZE]);
                // 屏幕按钮的滚轮经服务转来；蓝牙模式下音量键的不归服务（桌面模式下服务截的音量键上面 endServerHolds 已收尾）
                if (wheelSource >= AirMouse.SRC_ROUND) stopWheel();
                air.releaseFrom(AirMouse.SRC_ROUND);   // 屏幕按钮按住的空中移动 / 拖动同理
                leftFollowPending.entrySet().removeIf(e -> {   // 屏幕"左键"等着开始跟随的也作罢（等不到它的松开）
                    if (e.getKey() < AirMouse.SRC_ROUND) return false;
                    main.removeCallbacks(e.getValue());
                    return true;
                });
                airRoundPressed = false;
                leftHolds = 0;
                latchedMods = 0;
                keyboard.setModifiers(0);
                fixedKeyboard.setModifiers(0);
                if (wasGrabbed) {   // 服务转来的键按下等不到松开了：按下的样子、放大提示收起
                    keyboard.clearPressed();
                    fixedKeyboard.clearPressed();
                    for (int i = 0; i < numpadButtons.size(); i++) numpadLook(i, false);
                    clearMouseKeys();
                }
                if (air.buttons() == 0) haptics.stopHum();   // 收不到"拖动结束"了（空中指针那一路不归服务管）
                updateActiveButtons();
            }
        }
        updateStatus();
    }

    @Override
    public void onGrab(boolean isGrabbed, int reason, String detail) {
        boolean was = grabbed;
        grabbed = isGrabbed;
        if (isGrabbed && !was && !btMode) {
            // 桌面模式下没接管时按钮是 View 自己收的（localTouch），接管后系统收不到触摸，按着的按钮等不到松开
            if (wheelSource >= AirMouse.SRC_ROUND) stopWheel();
            leftHolds = 0;
            haptics.stopHum();
            clearMouseKeys();   // View 自己收的鼠标键只在抬起时点一下，没按住什么，只要画回去
            updateActiveButtons();
            // 键盘、数字键盘上自己按下的键同理（分屏 / 小窗回到全屏时手指可能正按在键上）：不松开的话服务的虚拟键盘一直按着、系统里连发
            keyboard.releaseTouches();
            fixedKeyboard.releaseTouches();
            releaseNumpad();
        }
        if (!isGrabbed && air.buttons() == 0) haptics.stopHum();
        if (isGrabbed && (btMode || gamepadOn)) {
            // 服务接管后系统收不到触摸，本地分流里正按着的手指等不到抬起：先收尾，按着的键松开（桌面模式手柄页：手柄回中立）
            local.releaseAll();
            localGesture = false;
        }
        grabMessage = grabText(reason, detail);
        updateStatus();
    }

    /** 数字键盘上 View 自己收触摸时按着的键全部松开（经同一路发松开），画回松开的样子。 */
    private void releaseNumpad() {
        for (View v : numpadHeld) {
            int i = numpadButtons.indexOf(v);
            if (i >= 0) onLocalKey(numpadUsages.get(i), false);
        }
        numpadHeld.clear();
        for (int i = 0; i < numpadButtons.size(); i++) numpadLook(i, false);
    }

    /** 数字键盘第 i 个键的样子：数字、小数点是普通键，运算符、⌫、Tab、↵ 是功能键（KeyRows.numpadFunctionKey）。 */
    private void numpadLook(int i, boolean pressed) {
        Style.keyLook(numpadButtons.get(i), KeyRows.numpadFunctionKey(numpadUsages.get(i)), false, pressed);
    }

    /** 服务截住的实体键（已判断好点一下 / 按住）：照蓝牙模式本地那一路执行动作，来源编号 = 键（AirMouse.SRC_VOL_*）。 */
    @Override
    public void onHardKey(int key, int event) {
        if (key != HardKeys.VOL_UP && key != HardKeys.VOL_DOWN) return;
        if (event == Proto.HARD_HOLD_END) {
            endServerHold(key);
            return;
        }
        if (!resumed || stopped) return;   // 不在前台：不做（服务随后会放开实体键）
        if (event == Proto.HARD_TAP) {
            Actions.Action a = hardKeyActions[key][0];
            if (a != null) fire(a);
        } else if (event == Proto.HARD_HOLD_START && serverHeld[key] == null) {
            Actions.Action a = hardKeyActions[key][1];
            if (a == null) return;
            serverHeld[key] = a;
            actionDown(a, key);
        }
    }

    /** 服务那一路的按住结束（收到 HOLD_END，或断开 / 暂停 / 服务放开实体键时补）：只结束一次。 */
    private void endServerHold(int key) {
        Actions.Action a = serverHeld[key];
        if (a == null) return;
        serverHeld[key] = null;
        actionUp(a, key);
    }

    private void endServerHolds() {
        endServerHold(HardKeys.VOL_UP);
        endServerHold(HardKeys.VOL_DOWN);
    }

    /**
     * 实体键的独占状态。服务不截了（grabbed 为 0）：按住的补结束。配了动作却截不了的键提示一次
     * （同样的不重复提示，配置改了重来）；文字在这里按语言和原因拼，出错（HARD_FAILED）时带上服务发来的细节
     * （同触摸屏的 grab_failed），别的原因细节只写在服务日志里。
     */
    @Override
    public void onHardGrab(int grabbedBits, int unavailable, int reason, String detail) {
        if (grabbedBits == 0) endServerHolds();
        if (reason == Proto.HARD_NO_INJECTOR) {
            toastHardOnce(reason << 8, getString(R.string.hard_keys_no_injector));
            return;
        }
        if (unavailable == 0) return;
        List<String> names = new ArrayList<>();
        if ((unavailable & Proto.HARD_BIT_VOL_UP) != 0) names.add(getString(R.string.key_name_volume_up));
        if ((unavailable & Proto.HARD_BIT_VOL_DOWN) != 0) names.add(getString(R.string.key_name_volume_down));
        String keys = android.icu.text.ListFormatter.getInstance().format(names);
        String text;
        switch (reason) {
            case Proto.HARD_SHARED: text = getString(R.string.hard_keys_unavailable, keys); break;
            case Proto.HARD_NO_DEVICE: text = getString(R.string.hard_keys_no_device, keys); break;
            case Proto.HARD_MIXED: text = getString(R.string.hard_keys_mixed, keys, detail); break;
            default: text = getString(R.string.hard_keys_failed, keys, detail); break;   // HARD_FAILED
        }
        toastHardOnce(unavailable | (reason << 8), text);
    }

    private void toastHardOnce(int what, String text) {
        if (what == hardToasted) return;
        hardToasted = what;
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** 服务分流出的手柄状态（画按下的样子）；带失败位 = 服务建虚拟手柄失败（状态字提示，原因在服务日志里）。 */
    @Override
    public void onGamepad(int flags, byte[] report) {
        if ((flags & Proto.GAMEPAD_FLAG_FAILED) != 0) {
            gamepadFailed = true;
            updateStatus();
            return;
        }
        if (gamepadOn && !stopped) gamepadScreen.setReport(false, report);
    }

    /** 服务只发原因代码，文字在这里按系统语言拼。 */
    private String grabText(int reason, String detail) {
        switch (reason) {
            case Proto.GRAB_PAUSED: return getString(R.string.grab_paused);
            case Proto.GRAB_UNRESPONSIVE: return getString(R.string.grab_unresponsive);
            case Proto.GRAB_ROTATING: return getString(R.string.grab_rotating);
            case Proto.GRAB_NO_TOUCHSCREEN: return getString(R.string.grab_no_touchscreen, detail);
            case Proto.GRAB_FAILED: return getString(R.string.grab_failed, detail);
            case Proto.GRAB_RELEASED: return getString(R.string.grab_released);
            case Proto.GRAB_NO_PASSTHROUGH: return getString(R.string.grab_no_passthrough, detail);
            case Proto.GRAB_WAIT_LIFT: return getString(R.string.grab_wait_lift);
            default: return "";
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        refreshDisplay();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        refreshDisplay();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        // 转 180° 时不走 onConfigurationChanged（方向、尺寸都没变），空中鼠标的方向在这里跟上
        Display own = getDisplay();
        if (own != null && own.getDisplayId() == displayId) air.setRotation(own.getRotation());
        refreshDisplay();
    }

    private void refreshDisplay() {
        Display own = getDisplay();
        Display d = DexDisplay.find(displays, own != null ? own.getDisplayId() : Display.DEFAULT_DISPLAY);
        targetDisplay = d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        targetName = d != null ? d.getName() : "";
        updateFocusable();
        if (btMode && !topResumed) pushLayout();   // 外接屏接上 / 拔掉会改变"顶层"这个条件算不算（ownsScreen），重新报
        updateStatus();
    }

    private void updateStatus() {
        if (pad == null) return;
        String s;
        boolean ok;
        boolean selfStarting = !linkOk && selfStart.step() == SelfStart.STEP_STARTING;
        if (btMode) {
            s = btStatusText();
            // 蓝牙模式下没开服务是正常配置，不提示；开了却卡住，系统手势就挡不住了，要告诉用户去重启
            if (selfStarting) s += " · " + getString(R.string.status_self_starting);
            else if (linkStuck) s += " · " + linkMessage;
            else if (linkOk && !ownsScreen()) s += " · " + getString(R.string.status_split_bt);   // 没开服务时分屏和全屏一样，不提
            ok = btState == BtHid.STATE_CONNECTED;
        } else if (!linkOk) {
            s = selfStarting ? getString(R.string.status_self_starting) : linkMessage;
            ok = false;
        } else if (textBar.getVisibility() == View.VISIBLE) {
            s = getString(R.string.status_text_mode);
            ok = true;
        } else if (keyboardScreen.isAdjusting()) {
            s = getString(R.string.status_adjusting);
            ok = true;
        } else if (!grabbed) {
            s = grabMessage.isEmpty() ? getString(R.string.status_taking_over) : grabMessage;
            ok = false;
        } else {
            // 分屏 / 小窗时没接外接屏是常态（比如和 Moonlight 分屏），不提示"没找到外接屏"
            s = !targetName.isEmpty() ? targetName : windowFull ? getString(R.string.status_no_dex) : "";
            if (!linkMessage.isEmpty()) s = s.isEmpty() ? linkMessage : s + " · " + linkMessage;
            ok = true;
        }
        pad.setStatus(s, ok);
        miniPad.setStatus(s, ok);
        boolean tappable = statusTappable();
        pad.setStatusTappable(tappable);
        miniPad.setStatusTappable(tappable);
        // 手柄页：平时写"按住 ⋯ 打开菜单"；服务没连上 / 建手柄失败时写原因（服务没接管不算问题：手柄页那时走本地分流）
        if (gamepadScreen == null) return;
        if (gamepadFailed) gamepadScreen.setStatus(getString(R.string.gp_create_failed), false);
        else if (!linkOk) gamepadScreen.setStatus(selfStarting ? getString(R.string.status_self_starting) : linkMessage, false);
        else gamepadScreen.setStatus(getString(R.string.gp_hold_menu), true);
        gamepadScreen.setStatusTappable(tappable);
    }

    /**
     * 状态字能不能点（点了打开设置页的"通用"页，最上面就是"豆花儿服务"那一节）：服务没开 / 没响应 / 版本不对的时候。
     * 桌面模式下这时没人接管触摸，触控板本来就不动；蓝牙模式下没开服务是正常配置，只有卡住才算。
     */
    private boolean statusTappable() {
        return btMode ? linkStuck : !linkOk;
    }

    /** 这一下按在可点的状态字上吗（看得见的那块触控板）。和角上看得见的小按钮、⋯ 重叠时归它们（文字很长时也点得到角上的按钮）。 */
    private boolean onStatus(MotionEvent e) {
        if (!statusTappable()) return false;
        // ⋯ 菜单开着时整页归菜单（点外面关菜单）
        if (menuLayer.isOpen()) return false;
        float x = e.getRawX(), y = e.getRawY();
        if (gamepadOn) return !hits(gpMenuButton, x, y) && gamepadScreen.statusHit(x, y);
        // 只看最上面那一屏：键盘屏盖着主界面（触控板还算"显示着"），空中鼠标页上没有状态字
        if (keyboardScreen.getVisibility() == View.VISIBLE) {
            if (keyboardScreen.isMenuButtonShown() && hits(kbMenuButton, x, y)) return false;
            return miniPad.statusHit(x, y);
        }
        if (airOn) return false;
        if (hits(padMenuButton, x, y)) return false;
        for (TextView b : cornerButtons) {
            if (b.isShown() && hits(b, x, y)) return false;
        }
        return pad.statusHit(x, y);
    }

    /** 屏幕坐标 (x, y) 落在 v 上吗。 */
    private boolean hits(View v, float x, float y) {
        v.getLocationOnScreen(tmpLoc);
        return x >= tmpLoc[0] && x < tmpLoc[0] + v.getWidth() && y >= tmpLoc[1] && y < tmpLoc[1] + v.getHeight();
    }

    // ═══════════ 空中鼠标 ═══════════

    /**
     * 空中鼠标页：盖住触控板屏；在页上调出的键盘反过来盖住它（这时页面隐藏，见 applyAirOverlayVisibility）。
     * 上面一行状态，中间是可设定的大按钮和圆钮，下面"回正中"和"按住退出"。默认隐藏。
     */
    private View buildAirOverlay() {
        airOverlay = new FrameLayout(this);
        airOverlay.setBackgroundColor(Style.theme().bg);
        airOverlay.setClickable(true);   // 没有服务、服务没接管时，挡住底下的按钮
        airOverlay.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(16));
        airOverlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        airStatus = airText("", 14, Style.theme().text);
        airStatus.setSingleLine(true);
        airStatus.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(airStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        airState = airText("", 16, Style.theme().focus);
        airState.setPadding(dp(12), 0, 0, 0);
        top.addView(airState);
        col.addView(top);

        // 格子和叠在上面的圆钮
        FrameLayout gridFrame = new FrameLayout(this);
        airGrid = new LinearLayout(this);
        airGrid.setOrientation(LinearLayout.VERTICAL);
        gridFrame.addView(airGrid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        airRound = new RoundPad(this);
        airRound.setOnTouchListener((v, e) -> localTouch(v, e, AIR_ROUND_ID));
        gridFrame.addView(airRound, new FrameLayout.LayoutParams(dp(96), dp(96)));
        airGrid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeAirRound());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        gp.topMargin = dp(12);
        gp.bottomMargin = dp(12);
        col.addView(gridFrame, gp);

        // 两个按钮永远贴在底部（横屏矮屏时也退得出去）
        LinearLayout bottom = new LinearLayout(this);
        airCenter = Style.button(this, 15);
        airCenter.setText(R.string.air_center_btn);
        airCenter.setOnTouchListener((v, e) -> localTouch(v, e, AIR_CENTER_ID));
        bottom.addView(airCenter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        airExit = Style.button(this, 15);
        airExit.setText(R.string.air_exit);
        airExit.setOnTouchListener((v, e) -> localTouch(v, e, AIR_EXIT_ID));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ep.leftMargin = dp(8);
        bottom.addView(airExit, ep);
        col.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return airOverlay;
    }

    /** 按设置排空中鼠标页：每行两个大按钮（"无"的空着、位置留着，圆钮按格子定位），圆钮放到选的两行之间。 */
    private void rebuildAirSlots() {
        airGrid.removeAllViews();
        airSlots.clear();
        int rows = prefs.airRows();
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (r > 0) rp.topMargin = dp(8);
            airGrid.addView(row, rp);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                Actions.Action a = Actions.get(prefs.airSlot(i));
                TextView b = Style.button(this, 20);
                b.setText(a.shortLabel(this));
                b.setTag(a);
                if (a.kind == Actions.Kind.NONE) b.setVisibility(View.INVISIBLE);
                int id = AIR_SLOT_BASE_ID + i;
                b.setOnTouchListener((v, e) -> localTouch(v, e, id));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (c == 1) bp.leftMargin = dp(8);
                row.addView(b, bp);
                airSlots.add(b);
            }
        }
        airRoundAt = prefs.airRoundAt();
        placeAirRound();
    }

    /** 圆钮放在第 airRoundAt、airRoundAt + 1 行之间、两列中间的交叉点；直径取格子短边的 0.7，最大 112dp。 */
    private void placeAirRound() {
        if (airGrid.getChildCount() <= airRoundAt) return;
        View above = airGrid.getChildAt(airRoundAt - 1);
        View below = airGrid.getChildAt(airRoundAt);
        if (below.getHeight() == 0) return;
        int cell = Math.min((airGrid.getWidth() - dp(8)) / 2, above.getHeight());
        int d = Math.min(dp(112), Math.round(cell * 0.7f));
        ViewGroup.LayoutParams p = airRound.getLayoutParams();
        if (p.width != d || p.height != d) {
            p.width = d;
            p.height = d;
            airRound.setLayoutParams(p);
        }
        airRound.setX(airGrid.getLeft() + (airGrid.getWidth() - d) / 2f);
        airRound.setY(airGrid.getTop() + (above.getBottom() + below.getTop() - d) / 2f);
    }

    private TextView airText(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /**
     * 进出空中鼠标页。进的时候先把触摸那一路收尾（拖动、轻点等待），页面盖上，区域只剩页上的按钮。
     * 从键盘屏进来先收起键盘（键盘开着时盖在页面上面，不收起就看不出进了）；退出后回到触控板屏。
     * 暂停中（键盘盖着页面）退出的，键盘留下当主界面的键盘。
     */
    private void setAirMode(boolean on) {
        if (on == airOn) return;
        if (on && gamepadOn) setGamepadMode(false);   // 两页互斥（模式不同，本来碰不到一起，兜底）
        setMenu(false);   // 空中鼠标页上没有 ⋯（从键盘屏进来的话 setKeyboardScreen 也会关）
        if (on && keyboardScreen.getVisibility() == View.VISIBLE) setKeyboardScreen(false);   // airOn 还是假：普通的收起
        airOn = on;
        updateAirAbs();
        local.releaseAll();
        localGesture = false;
        if (on) unlatchServiceModifiers();
        if (!on) releaseAir();
        stopWheel();
        main.removeCallbacks(airExitLater);
        applyAirOverlayVisibility();
        pushLayout();
        updateAirOverlay();
    }

    /** 空中鼠标暂停：空中鼠标模式开着、键盘盖在页面上面。只看键盘看不看得见，不另记状态。 */
    private boolean airPaused() {
        return airOn && keyboardScreen.getVisibility() == View.VISIBLE;
    }

    /**
     * 空中鼠标页显示与否：模式开着、键盘没盖上来才显示。键盘开着时页面 GONE 而不是调层次：
     * 键盘屏没有背景，页面还在的话会从小触控板和键盘的空隙里透出来；没接管、调整键盘时，可点的页面还会先把触摸吃掉。
     */
    private void applyAirOverlayVisibility() {
        airOverlay.setVisibility(airOn && keyboardScreen.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    /**
     * 键盘盖上空中鼠标页：照 onPause 的顺序收尾。先 hardKeys.reset()（音量键按住中的先 holdEnd，按住移动 / 左键 / 滚轮
     * 正常收尾、leftHolds 跟着减），再 releaseAir()——反过来的话 air.releaseAll() 先清掉了左键，音量键"左键"的震动收不了尾。
     * 不清 leftHolds、不动本地分流：触摸没有丢，正按着的手指抬起时照常收到松开。收起后不自动恢复跟随，要人再按。
     */
    private void pauseAir() {
        hardKeys.reset();
        stopWheel();
        releaseAir();
        main.removeCallbacks(airExitLater);
    }

    /** 收起键盘回到空中鼠标页：本地分流收尾，键盘上锁住的修饰键解开（不然每次空中点击都成了 Ctrl+点击，同进页面时）。 */
    private void resumeAirPage() {
        local.releaseAll();   // 本地分流里锁住的修饰键也在这里解开
        localGesture = false;
        unlatchServiceModifiers();
    }

    /** 服务那边锁住的修饰键：按一下就解开（服务的 TouchRouter 里修饰键是按下时切换）。只在蓝牙模式、服务接管着时。 */
    private void unlatchServiceModifiers() {
        if (!btMode || !grabbed || !link.isConnected()) return;
        for (int usage : Hid.modifierUsages(latchedMods)) {   // 左右八个都看（实体键盘上右边四个也能锁住）
            link.hidKey(usage, true);
            link.hidKey(usage, false);
        }
    }

    private void updateAirOverlay() {
        if (airOverlay == null || !airOn) return;
        airStatus.setText(bt.isConnected() ? getString(R.string.bt_state_connected, btDevice) : getString(R.string.air_waiting));
        int state = air.dragging() ? R.string.air_dragging : air.following() ? R.string.air_following : 0;
        airState.setVisibility(state == 0 ? View.GONE : View.VISIBLE);
        if (state != 0) airState.setText(state);
    }

    /** 触摸那一路的鼠标报文：记下它按着的键，并上空中鼠标按着的键再发。 */
    private void sendTouchMouse(int buttons, int dx, int dy, int wheel, int pan) {
        // 触控板挪过光标（空中鼠标页上调出的键盘带小触控板）：激光笔式记的位置作废，下次跟随先回正中。没挪过就接着原来的位置
        if (dx != 0 || dy != 0) air.forgetPosition();
        touchButtons = buttons;
        bt.sendMouse(buttons | air.buttons(), dx, dy, wheel, pan);
    }

    @Override
    public void airMouse(int airButtons, int dx, int dy) {
        bt.sendMouse(touchButtons | airButtons, dx, dy, 0, 0);
    }

    @Override
    public void airHaptic(int kind) {
        // 移动"颗粒"和触控板一个规矩：设置里开着才有；按着左键（拖动）时是持续轻震，不发颗粒
        if (kind == GestureEngine.HAPTIC_TEXTURE && (!textureOn || touchButtons != 0)) return;
        // 触摸那一路还按着左键：空中拖动结束了也还在拖，接着嗡
        if (kind == GestureEngine.HAPTIC_DRAG_END && (touchButtons & Proto.BUTTON_LEFT) != 0) kind = GestureEngine.HAPTIC_TICK;
        haptics.play(kind);
    }

    /**
     * 空中鼠标页开着时空中指针用激光笔式（绝对坐标，对方的鼠标加速不起作用）；页面外照普通鼠标发相对位移。
     * 对方屏幕的宽高比不知道，先按苹果设备 4:3、别的 16:9 估（不准时横竖快慢差一点，横线竖线照样直）。
     */
    private void updateAirAbs() {
        android.bluetooth.BluetoothDevice d = bt.connectedDevice();
        float set = d != null ? prefs.hostAspect(d.getAddress()) : 0f;   // 设置里给这台主机选过屏幕比例就用它
        float aspect = set > 0 ? set : d != null && BtHid.isApple(d) ? 4f / 3 : 16f / 9;
        air.setAbsoluteMode(airOn && btMode, aspect);
    }

    /** 空中指针全部放开，圆钮的锁定也解开。 */
    private void releaseAir() {
        for (Runnable r : leftFollowPending.values()) main.removeCallbacks(r);
        leftFollowPending.clear();
        air.releaseAll();
        airRoundLocked = false;
        airRoundPressed = false;
    }

    @Override
    public void airChanged() {
        updateAirOverlay();
        updateActiveButtons();
    }

    // ═══════════ 手柄页 ═══════════

    /**
     * 进出手柄页（独立一页：没有触控板、没有键盘，只报手柄控件的区域）。第一批只在桌面模式：服务的第三个 uhid 设备（"Douhua Gamepad"）
     * 第一次报手柄页的区域时才建，留到会话结束。进的时候关菜单、收键盘、收贴字面板、退出空中鼠标（互斥）、本地分流收尾、解开服务那边锁住的修饰键
     * （不然 Ctrl 一直按着），两路的手柄都回中立；手柄页开着时吞返回、左右边设排除区（服务没接管时系统手势还在）。不存盘（同空中鼠标）。
     */
    private void setGamepadMode(boolean on) {
        if (on == gamepadOn) return;
        if (on && btMode) {
            toast(R.string.gp_desktop_only);
            return;
        }
        setMenu(false);
        if (on) {
            if (airOn) setAirMode(false);
            if (keyboardScreen.getVisibility() == View.VISIBLE) setKeyboardScreen(false);
            closeTextBar();
        }
        main.removeCallbacks(gpMenuLater);
        gamepadOn = on;
        local.releaseAll();
        localGesture = false;
        if (on) unlatchDesktopModifiers();
        resetGamepad();
        gamepadScreen.setVisibility(on ? View.VISIBLE : View.GONE);
        applyBackSwallow();
        updateActiveButtons();
        updateStatus();
        pushLayout();
    }

    /** 手柄两路都回中立：服务的分流（CMD_GAMEPAD_CONFIG）、本地分流，画的也清掉。进出手柄页、换预设时。 */
    private void resetGamepad() {
        GamepadConfig c = prefs.gamepadConfig();
        local.setGamepadConfig(c);
        link.gamepadConfig(c);
        gamepadScreen.clearReports();
    }

    /** 桌面模式下服务那边锁住的修饰键：按一下就解开（服务的 TouchRouter 里修饰键是按下时切换；服务没接管时也在服务里记着）。 */
    private void unlatchDesktopModifiers() {
        if (btMode || !link.isConnected()) return;
        for (int usage : Hid.modifierUsages(latchedMods)) {   // 左右八个都看（实体键盘上右边四个也能锁住）
            link.hidKey(usage, true);
            link.hidKey(usage, false);
        }
    }

    // ═══════════ 实体按键 ═══════════

    /** 按设置读音量键的动作（"无" = 不截）。 */
    private void loadHardKeys() {
        for (int k = 0; k < 2; k++) {
            for (int h = 0; h < 2; h++) {
                Actions.Action a = Actions.get(prefs.hardKey(k, h == 1));
                hardKeyActions[k][h] = a == null || a.kind == Actions.Kind.NONE ? null : a;
            }
            hardKeys.setConfig(k, hardKeyActions[k][0] != null, hardKeyActions[k][1] != null);
        }
        int cfg = 0;
        for (int i = 0; i < 4; i++) if (hardKeyActions[i / 2][i % 2] != null) cfg |= 1 << i;
        if (cfg != hardConfig) hardToasted = -1;   // 配置改了：截不了的话再提示一次
        hardConfig = cfg;
    }

    /**
     * 告诉服务截哪些音量键（连上时、改设置后、通话状态变了时）。桌面模式按配置；蓝牙模式 App 自己收，全发 0。
     * 通话、来电响铃时全发 0，服务全部放开：要能用音量键静音、调通话音量。
     * 只看音频模式：振动 / 静音模式下来电模式不变，那种响铃靠服务自己查 Telecom（server.CallState）。
     */
    private void sendHardKeys() {
        if (!link.isConnected()) return;
        boolean vol = !btMode && !inCall();
        link.hardKeys(vol && hardKeyActions[0][0] != null, vol && hardKeyActions[0][1] != null,
                vol && hardKeyActions[1][0] != null, vol && hardKeyActions[1][1] != null);
    }

    /**
     * 通话中或来电响铃（音频模式不是 NORMAL）：实体键交还系统。看不到振动 / 静音模式下的响铃（模式不变），
     * 那种情况服务截的键由服务查 Telecom 放开；App 自己收的音量键响铃时系统会先截去静音，到不了这里。
     */
    private boolean inCall() {
        return audio != null && audio.getMode() != AudioManager.MODE_NORMAL;
    }

    /** 音量键有没有设动作（蓝牙模式才由 App 自己收；桌面模式下由服务截，窗口照旧不抢外接屏的焦点）。 */
    private boolean hardKeysActive() {
        return btMode && (hardKeys.mapped(HardKeys.VOL_UP) || hardKeys.mapped(HardKeys.VOL_DOWN));
    }

    /** 音量键：设了动作就交给 HardKeys 判断点一下 / 按住，并吃掉（音量不变）；没设照常调音量。 */
    private boolean onHardKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        int k = code == KeyEvent.KEYCODE_VOLUME_UP ? HardKeys.VOL_UP : HardKeys.VOL_DOWN;
        if (!btMode || !resumed || !hardKeys.mapped(k)) return false;
        int action = e.getAction();
        if (inCall() || airPaused()) {
            // 通话中（调通话音量）、键盘盖着空中鼠标页：音量键交还系统调音量。之前按下、还没松开的那一下（连发的按下、最后的松开）
            // 照旧吞掉，松开当取消、不算点一下（按住中的补结束）——不然系统收到没头的松开，HardKeys 也一直以为它按着，回来后第一下被吃掉
            if (!hardKeys.isPressed(k)) return false;
            if (action == KeyEvent.ACTION_UP) hardKeys.cancel(k);
            return true;
        }
        if (action == KeyEvent.ACTION_UP && (e.getFlags() & KeyEvent.FLAG_CANCELED) != 0) {
            hardKeys.cancel(k);   // 被系统取消的按键：当放手，不算点一下
        } else if (action == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            hardKeys.key(k, true, e.getEventTime());
        } else if (action == KeyEvent.ACTION_UP) {
            hardKeys.key(k, false, e.getEventTime());
        }
        return true;   // 系统连发的按下也吃掉
    }

    private final class HardKeysOutput implements HardKeys.Output {
        @Override
        public void tap(int key) {
            Actions.Action a = hardKeyActions[key][0];
            if (a != null) fire(a);
        }

        @Override
        public void holdStart(int key) {
            Actions.Action a = hardKeyActions[key][1];
            if (a != null) actionDown(a, key);   // 来源编号 = 键（AirMouse.SRC_VOL_UP / SRC_VOL_DOWN）
        }

        @Override
        public void holdEnd(int key) {
            Actions.Action a = hardKeyActions[key][1];
            if (a != null) actionUp(a, key);
        }

        @Override
        public void schedule(int key, long delayMs) {
            main.removeCallbacks(hardKeyTimers[key]);
            main.postDelayed(hardKeyTimers[key], delayMs);
        }

        @Override
        public void cancelSchedule(int key) {
            main.removeCallbacks(hardKeyTimers[key]);
        }
    }

    // ═══════════ 蓝牙模式 ═══════════

    /** 蓝牙模式下自己收触摸，整次手势（按下到全部抬起）交给本地分流；其余情况照常分给各个按钮。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        // 惯性滚动（本地分流里转着的）碰屏幕任何地方都马上停：归本地分流的按下由 local.down 停（按在触控板上的那一下不算点击），
        // 不归它的（注入回来的、点状态字、贴字面板 / 调整键盘时分给按钮的）在这里停
        boolean press = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        // 桌面模式分屏透传：服务把从豆花窗口边上系统手势带里按下、又没落在键 / 按钮上的手指（触控板、空白处）注入回系统
        // （设备号 -1 = 注入的，带 NO_FOCUS_CHANGE，不会把焦点挪过来），系统认手势之前也会把它发给豆花窗口。整次手势吞掉，
        // 不然在边上轻点会点到底下的东西。服务接管时豆花本来收不到真的触摸
        if (action == MotionEvent.ACTION_DOWN) {
            boolean injected = !btMode && link.isConnected() && e.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD;
            // decor 自带的标题栏（拖动、最小化、关闭）是豆花窗口里的 View，注入回来的手指只有交给它才拖得动小窗；
            // 只有 WM Shell 画在单独窗口里的标题栏（captionBar）不经过这里
            injectedCaption = injected && !windowFull && e.getY() < reportedDecorCap;
            injectedGesture = injected && !injectedCaption;
            // 注入的手指只该落在报过的手势带里；落在窗口中间说明服务手里的窗口范围过时了（小窗挪了没重报）：马上重报，这次照样吞掉
            if (injectedGesture && !windowFull && insideUnbanded(e.getX(), e.getY())) pushLayout();
        }
        if (injectedCaption) {
            if (press) local.stopSpin(true);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) injectedCaption = false;
            return super.dispatchTouchEvent(e);
        }
        if (injectedGesture) {
            if (press) local.stopSpin(true);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) injectedGesture = false;
            return true;
        }
        // 点状态字（服务没开 / 没响应时）：整次手势归它，不当触控板的点击、也不分给按钮
        if (action == MotionEvent.ACTION_DOWN) statusPress = onStatus(e);
        if (statusPress) {
            if (press) local.stopSpin(true);
            if (action == MotionEvent.ACTION_UP) {
                statusPress = false;
                if (onStatus(e)) startActivity(SettingsActivity.serviceIntent(this));   // 停在"通用"页（服务那一节在最上面）
            } else if (action == MotionEvent.ACTION_CANCEL) {
                statusPress = false;
            }
            return true;
        }
        // 桌面模式的手柄页也走本地分流（服务没接管时；接管时真的触摸到不了这里）：手柄控件要跟着手指移动，每个 View 自己收就是把分流再抄一份
        if (action == MotionEvent.ACTION_DOWN) localGesture = (btMode || gamepadOn) && localActive() && local.ready();
        if (!localGesture) {
            if (press) local.stopSpin(true);
            return super.dispatchTouchEvent(e);
        }
        routeLocal(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) localGesture = false;
        return true;
    }

    /** 窗口坐标 (x, y) 在窗口里、又不在上次报给服务的手势带里。 */
    private boolean insideUnbanded(float x, float y) {
        View root = getWindow().getDecorView();
        if (x < 0 || y < 0 || x >= root.getWidth() || y >= root.getHeight()) return false;
        return x >= reportedBand[0] && y >= reportedBand[1]
                && x < root.getWidth() - reportedBand[2] && y < root.getHeight() - reportedBand[3];
    }

    /**
     * MotionEvent → TouchRouter。区域是屏幕坐标（getLocationOnScreen），触摸也换成屏幕坐标：窗口坐标加上报区域那一刻
     * 窗口的位置（layoutOrigin）。全屏时窗口在 (0, 0)；分屏 / 小窗时窗口不在原点，小窗还可能被拖走而不重报区域，
     * 用同一时刻的位置，触点和区域才对得上（用 getRawX 的话拖走小窗后会错位）。
     * 系统把一帧帧的触摸攒成一批（历史点），逐帧喂进去，手势引擎看到的和服务读触摸屏时一样细。
     */
    private void routeLocal(MotionEvent e) {
        float offX = layoutOrigin[0];
        float offY = layoutOrigin[1];
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                local.down(e.getPointerId(i), e.getX(i) + offX, e.getY(i) + offY, e.getEventTime());
                local.frame(e.getEventTime());
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int h = 0; h < e.getHistorySize(); h++) {
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        local.move(e.getPointerId(i), e.getHistoricalX(i, h) + offX, e.getHistoricalY(i, h) + offY);
                    }
                    local.frame(e.getHistoricalEventTime(h));
                }
                for (int i = 0; i < e.getPointerCount(); i++) {
                    local.move(e.getPointerId(i), e.getX(i) + offX, e.getY(i) + offY);
                }
                local.frame(e.getEventTime());
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                // 带 FLAG_CANCELED 的（系统认出手掌、取消了这一根）：照旧当抬起，只是不起惯性、中键不补单击（同服务那一路）
                local.up(e.getPointerId(e.getActionIndex()), e.getEventTime(),
                        (e.getFlags() & MotionEvent.FLAG_CANCELED) != 0);
                break;
            case MotionEvent.ACTION_CANCEL:
                local.releaseAll();
                break;
            default:
                break;
        }
    }

    /**
     * 本地分流的结果：鼠标键盘发蓝牙（桌面模式下本地分流只管手柄页，鼠标键盘不发，防御一下），手柄画出来、桌面模式下发给服务（CMD_GAMEPAD），
     * 其余和服务发来的消息走同一套处理。
     */
    private final class LocalOutput implements TouchRouter.Output {
        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            if (btMode) sendTouchMouse(buttons, dx, dy, wheel, pan);
        }

        @Override
        public void keyboard(byte[] report) {
            if (btMode) bt.sendKeyboard(report);
        }

        @Override
        public void gamepad(byte[] report) {
            gamepadScreen.setReport(true, report);
            if (!btMode) link.gamepad(report);   // 蓝牙手柄是第三批：蓝牙模式下进不了手柄页，这里只可能是收尾的全零
        }

        @Override
        public void haptic(int kind) {
            onHaptic(kind);
        }

        @Override
        public void gesture(int fingers, int kind) {
            onGesture(fingers, kind);
        }

        @Override
        public void button(int id, int action) {
            onButton(id, action);
        }

        @Override
        public void modifiers(int latched) {
            onModifiers(latched);
        }

        @Override
        public void schedule(long delayMs) {
            main.removeCallbacks(localTimer);
            main.postDelayed(localTimer, delayMs);
        }

        @Override
        public void cancelSchedule() {
            main.removeCallbacks(localTimer);
        }

        @Override
        public void startTicks() {
            if (localTicking) return;
            localTicking = true;
            ticker.postDelayed(localTick, LOCAL_TICK_MS);
        }

        @Override
        public void stopTicks() {
            localTicking = false;
            ticker.removeCallbacks(localTick);
        }

        @Override
        public void note(String s) {
            Log.i("douhua", s);
        }
    }

    /**
     * 本地分流的小红点节拍（蓝牙模式没开服务时）：12ms 一拍（同空中鼠标的相对位移：电脑约 11.25ms 收一次报文，发得更密会挤在一起、
     * 被对方当成加速）。用异步 Handler：红点按下时界面在重画，普通消息会被同步屏障挡到下一帧画完（"打字延迟"那条同一个坑）。
     */
    private static final long LOCAL_TICK_MS = 12;
    private final Handler ticker = Handler.createAsync(Looper.getMainLooper());
    private boolean localTicking;
    private final Runnable localTick = new Runnable() {
        @Override
        public void run() {
            if (!localTicking) return;
            local.onTick(SystemClock.uptimeMillis());
            if (localTicking) ticker.postDelayed(this, LOCAL_TICK_MS);   // onTick 里可能停了
        }
    };

    @Override
    public void onBtState(int state, String deviceName) {
        // 只在"连着的断了"那一刻收尾：没连上时每 5 秒重连一次，状态在连接中 / 等待之间来回变，每次都收会打断正按着的按钮
        boolean lost = btState == BtHid.STATE_CONNECTED && state != BtHid.STATE_CONNECTED;
        btState = state;
        btDevice = deviceName;
        if (lost) {
            local.releaseAll();   // 别让对方那头留着按住的键
            touchButtons = 0;
        }
        updateHostKind();
        updateAirAbs();
        if (lost) releaseAir();   // 停跟随、松开按着的键
        updateAirOverlay();
        updateStatus();
    }

    /** 蓝牙连着苹果设备时，修饰键按苹果写法标、内置快捷键按苹果习惯发（⌘C 而不是 Ctrl+C）；桌面模式和别的主机照通用的。 */
    private void updateHostKind() {
        appleHost = btMode && btState == BtHid.STATE_CONNECTED && BtHid.isApple(bt.connectedDevice());
        keyboard.setAppleHost(appleHost);
    }

    private String btStatusText() {
        switch (btState) {
            case BtHid.STATE_NO_PERMISSION: return getString(R.string.bt_state_no_permission);
            case BtHid.STATE_BT_DISABLED: return getString(R.string.bt_state_disabled);
            case BtHid.STATE_UNSUPPORTED: return getString(R.string.bt_state_unsupported);
            case BtHid.STATE_WAITING: return getString(R.string.bt_state_waiting);
            case BtHid.STATE_CONNECTING: return getString(R.string.bt_state_connecting, btDevice);
            case BtHid.STATE_CONNECTED: return getString(R.string.bt_state_connected, btDevice);
            case BtHid.STATE_FAILED: return getString(R.string.bt_state_failed);
            case BtHid.STATE_STUCK: return getString(R.string.bt_state_stuck);
            default: return getString(R.string.bt_state_starting);
        }
    }

    private void requestBtPermissions() {
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 2);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 2) bt.retry();
        else if (requestCode == 1 && btMode && !BtHid.hasPermission(this)) requestBtPermissions();
    }

    /**
     * 蓝牙模式下系统的边缘返回手势还在（桌面模式下触摸被服务独占，系统碰不到）：从左右边缘往里横划移光标会被当成返回。
     * 请系统别在左右两条边上认返回手势；导航栏是"划出才显示"的隐藏状态，系统不限制这块的高度。
     */
    private void updateGestureExclusion() {
        View root = getWindow().getDecorView();
        List<Rect> rects = new ArrayList<>();
        if (btMode || gamepadOn) {   // 桌面模式的手柄页：服务没接管时系统手势没被挡住，左右边一划摇杆就成了返回
            int w = dp(32);
            rects.add(new Rect(0, 0, w, root.getHeight()));
            rects.add(new Rect(root.getWidth() - w, 0, root.getWidth(), root.getHeight()));
        }
        root.setSystemGestureExclusionRects(rects);
    }

    /** 排除区以外的地方还是可能触发返回：蓝牙模式下、手柄页上吞掉返回，豆花不会被意外关掉（手柄页从 ⋯ 菜单离开）。 */
    private void applyBackSwallow() {
        boolean want = btMode || gamepadOn;
        if (want == backSwallowed) return;
        if (want) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, swallowBack);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(swallowBack);
        }
        backSwallowed = want;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
