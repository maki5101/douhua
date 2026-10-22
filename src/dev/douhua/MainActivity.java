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
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
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
import java.util.List;

/**
 * 主界面有两屏：
 * - 触控板屏：左边一竖条按钮（左手拇指够得着），右边是触控板，可以展开数字键盘；
 * - 键盘屏：整屏是外接键盘（大小位置可调），键盘上方空出来的地方是小触控板。
 * 整个界面纯黑，只有按钮和触控板的暗灰边框。
 *
 * 两种连接方式，界面、手势、动作都一样，区别只在触摸谁来收、键盘鼠标发到哪：
 * - DeX：豆花在前台时，服务独占这块屏的触摸屏，按这里报上去的区域位置分流（{@link TouchRouter}），
 *   鼠标键盘发给虚拟 USB 设备，再把按钮事件、震动、三指四指手势发回来，这里负责画面和执行动作。
 *   服务没接管时（"贴字"面板打开、服务没连上），按钮和键按普通方式响应触摸。
 * - 蓝牙：鼠标键盘经 {@link BtHid} 发给配对的电脑 / 平板 / 电视。服务开着时和 DeX 一样由服务独占触摸屏、分流
 *   （系统的下拉通知栏、上滑回桌面、侧滑返回都碰不到），只是报文发回这里（relay），由这里从蓝牙发出去；
 *   服务没开或没接管时，这里自己收触摸（{@link #dispatchTouchEvent}），交给同一个 TouchRouter 分流。
 *
 * 窗口平时设成"不可获得焦点"，DeX 里的窗口不会因为碰了手机屏而变成未激活。"贴字"面板打开时、没有外接屏时允许，
 * 见 {@link #updateFocusable}。
 */
public final class MainActivity extends Activity
        implements Link.Listener, KeyboardPanel.Listener, KeyboardScreen.Listener, DisplayManager.DisplayListener,
        BtHid.Listener, AirMouse.Host {

    /** 按钮条收起后，触控板四角的小按钮（左上、右上、右下可自定义；左下 ☰ 固定，是回到按钮条和设置的入口）。 */
    private static final int CORNER_BASE_ID = 500;
    /** 空中鼠标页上的按钮（其它按钮编号：0–15、300–302、400–403、500–503、600 起、0x10000 起）。 */
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
    /** 键盘屏工具栏的按钮编号。 */
    private static final int KB_CLOSE_ID = 400;
    private static final int KB_ADJUST_ID = 401;
    private static final int KB_RESET_ID = 402;
    private static final int KB_TOOLBAR_POS_ID = 403;
    /** 左侧按钮条的区域编号：0 = 退出，1 = 旋转，2 = 设置，之后是可设定的位置。 */
    /** 按钮条最上面固定的；下面是 Prefs 里可增减的按钮，最下面固定"收起"。 */
    private static final String[] FIXED_ACTIONS = {"exit", "settings"};
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
            main.postDelayed(this, Proto.HEARTBEAT_INTERVAL_MS);
        }
    };
    private Prefs prefs;
    private Haptics haptics;
    private DisplayManager displays;
    private float density;

    private TrackpadView pad;
    private final List<TextView> cornerButtons = new ArrayList<>();
    private LinearLayout right;
    private LinearLayout mainRow;
    private KeyboardScreen keyboardScreen;
    private TrackpadView miniPad;
    private final List<TextView> kbToolbar = new ArrayList<>();
    /** 键盘最下面一排的动作（下标 = 位置）。 */
    private final List<Actions.Action> keyboardRow = new ArrayList<>();
    private ButtonStrip strip;
    private LinearLayout textBar;
    private EditText input;
    private LinearLayout numpad;
    private KeyboardPanel keyboard;
    /** 下标就是区域编号。 */
    private final List<TextView> stripButtons = new ArrayList<>();
    private final List<TextView> numpadButtons = new ArrayList<>();
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
    // DeX 模式下由服务独占按键设备、判断好发来（onHardKey(int, int)） ──
    private final Actions.Action[][] hardKeyActions = new Actions.Action[2][2];
    private final HardKeys hardKeys = new HardKeys(new HardKeysOutput());
    private final Runnable[] hardKeyTimers = {
            () -> hardKeys.onTimer(HardKeys.VOL_UP, SystemClock.uptimeMillis()),
            () -> hardKeys.onTimer(HardKeys.VOL_DOWN, SystemClock.uptimeMillis()),
    };
    /** 服务发来、开始了还没结束的按住（下标 = 键，值 = 开始时的动作）：断开、暂停、服务放开实体键时补结束，不会重复。 */
    private final Actions.Action[] serverHeld = new Actions.Action[2];
    /** DeX 模式下实体键经服务按着左键的来源：第一个按下、最后一个松开时才发 CMD_HOLD_LEFT。 */
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
    /** 蓝牙模式下的触摸分流（DeX 模式下这件事在服务里做）。 */
    private final TouchRouter local = new TouchRouter(new LocalOutput());
    private final Runnable localTimer = () -> local.onTimer(SystemClock.uptimeMillis());
    /** 这次手势由 local 分流（按下时定，一直到全部抬起）。 */
    private boolean localGesture;
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
        haptics = new Haptics(this);
        displays = getSystemService(DisplayManager.class);
        audio = getSystemService(AudioManager.class);
        bt = BtHid.get(this);
        selfStart = SelfStart.get(this);
        air = new AirMouse(this, bt, prefs, this);
        // 只在可调试包里有：adb 让手机发任意位移（量回正中用）、开键盘触点日志（打字测试用）
        debugMoves = DebugMoves.register(this, bt, this::onKeyLogChanged);
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
        setContentView(buildUi());
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        linkSeen = false;
        autoStartAsked = false;
        selfStart.addListener(selfStartListener);
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
        local.releaseAll();   // 蓝牙模式：按着的键、鼠标键都松开（DeX 模式下 local 本来就闲着）
        localGesture = false;
        hardKeys.reset();   // 音量键按住中的先收尾（按住移动 / 拖动 / 左键松开）
        endServerHolds();   // 服务那一路的同理（服务随后放开实体键，发来的结束按住不会再做一次）
        stopWheel();
        leftHolds = 0;
        releaseAir();   // 空中指针：停跟随、松开按着的键（空中鼠标页本身留着，回来接着用）
        main.removeCallbacks(airExitLater);
        pushLayout();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (debugMoves != null) debugMoves.unregister(this);
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
        }
        grabbed = false;
        if (btAcquired) {
            bt.release(this);
            btAcquired = false;
        }
        displays.unregisterDisplayListener(this);
        if (audio != null) audio.removeOnModeChangedListener(audioMode);
        selfStart.removeListener(selfStartListener);
        super.onStop();
    }

    /** 手机自己拉起服务的进度变了（开始启动 / 启动完）：状态字跟着变。 */
    private final SelfStart.Listener selfStartListener = this::updateStatus;

    /** 设置页改完回来：按新设置更新按钮和震动强度，参数发给服务（蓝牙模式下给本地分流）。 */
    private void applyPrefs() {
        btMode = prefs.btMode();
        updateHostKind();
        if (!btMode && airOn) setAirMode(false);
        air.setSensitivity(prefs.airSensitivity());
        air.loadFilter();
        updateAirAbs();   // 屏幕比例可能刚改过
        loadHardKeys();
        sendHardKeys();
        local.setConfig(prefs.engineConfig(), density);
        applyBackSwallow();
        if (!btMode) requestOrientationSelf(-1);   // 蓝牙模式下等连服务的结果再定，见 onLinkStatus
        haptics.setLevel(prefs.haptic());
        haptics.setTickStrength(prefs.tickStrength());
        haptics.setDragHum(prefs.dragHum());
        applyStripHidden(prefs.stripHidden());
        GestureEngine.Config cfg = prefs.engineConfig();
        textureOn = cfg.texture;
        scrollTicksOn = cfg.scrollTicks != GestureEngine.TICKS_OFF;
        float edge = cfg.edgeWidthDp * density;
        pad.setEdgeGuides(edge, cfg.edgeScroll, cfg.edgeScrollHorizontal);
        miniPad.setEdgeGuides(edge, cfg.edgeScroll, cfg.edgeScrollHorizontal);
        rebuildStrip();
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
        }
        keyboardScreen.setToolbarAtBottom(prefs.keyboardToolbarBottom());
        link.config(prefs.engineConfig(), density);
        updateFocusable();
    }

    // ═══════════ 界面 ═══════════

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int pad8 = dp(8);
        // 贴边：触摸由服务独占，系统的边缘手势碰不到，不用再给系统栏、刘海留边；只给"贴字"面板的输入法让位
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(0, 0, 0, ime.bottom);
            return insets;
        });
        // 横竖屏切换、面板开合、按钮变化都会重新布局；布局一变就把新位置告诉服务
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> pushLayout());

        mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(mainRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 左侧按钮条（y 轴）：矮屏自动分列 ──
        strip = new ButtonStrip(this, dp(60), dp(52), dp(6));
        mainRow.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rebuildStrip();

        // ── 右侧："贴字"面板在上；下面是触控板 + 数字键盘 / 外接键盘 ──
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.leftMargin = pad8;
        mainRow.addView(right, rp);

        textBar = buildTextBar();
        textBar.setVisibility(View.GONE);
        right.addView(textBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pad = new TrackpadView(this);
        numpad = buildNumpad();
        numpad.setVisibility(View.GONE);
        right.addView(new PadArea(this, buildPadFrame(), numpad, pad8),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ── 键盘屏 ──
        LinearLayout toolbar = new LinearLayout(this);
        addToolbarButton(toolbar, getString(R.string.kb_close), KB_CLOSE_ID);
        addToolbarButton(toolbar, getString(R.string.kb_adjust), KB_ADJUST_ID);
        addToolbarButton(toolbar, getString(R.string.kb_reset), KB_RESET_ID).setVisibility(View.GONE);
        addToolbarButton(toolbar, "", KB_TOOLBAR_POS_ID).setVisibility(View.GONE);
        toolbar.setGravity(Gravity.CENTER);
        layoutToolbar(false);
        miniPad = new TrackpadView(this);
        keyboard = new KeyboardPanel(this, this);
        // 键盘和数字键盘按实体键盘排，阿拉伯语这类从右往左的语言下也不镜像
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        numpad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboardScreen = new KeyboardScreen(this, toolbar, miniPad, keyboard, this);
        keyboardScreen.setVisibility(View.GONE);
        root.addView(keyboardScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildAirOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** 触控板 + 四角的小按钮（按钮条收起时才显示）。 */
    private View buildPadFrame() {
        FrameLayout frame = new FrameLayout(this);
        frame.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i <= Prefs.CORNER_COUNT; i++) {
            int id = CORNER_BASE_ID + i;
            TextView b = Style.button(this, 12);
            b.setTextColor(0xFF5A5A5A);
            b.setBackground(Style.border(this, 0xFF1E1E1E, 0));
            if (i == Prefs.CORNER_COUNT) {
                b.setText("☰");
                b.setTag(Actions.get("toggle_strip"));
            }
            b.setOnTouchListener((v, e) -> localTouch(v, e, id));
            b.setVisibility(View.GONE);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(48), dp(40), CORNER_GRAVITY[i]);
            p.setMargins(dp(8), dp(8), dp(8), dp(8));
            frame.addView(b, p);
            cornerButtons.add(b);
        }
        return frame;
    }

    /** 收起 / 显示左侧按钮条；收起时触控板四角显示小按钮。 */
    private void applyStripHidden(boolean hidden) {
        strip.setVisibility(hidden ? View.GONE : View.VISIBLE);
        for (TextView b : cornerButtons) b.setVisibility(hidden ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) right.getLayoutParams();
        rp.leftMargin = hidden ? 0 : dp(8);
        right.setLayoutParams(rp);
    }

    private TextView addToolbarButton(LinearLayout toolbar, String label, int id) {
        TextView b = Style.button(this, 14);
        b.setText(label);
        b.setOnTouchListener((v, e) -> localTouch(v, e, id));
        toolbar.addView(b);
        kbToolbar.add(b);
        return b;
    }

    /**
     * 键盘工具栏：平时两个按钮定宽、挤在中间，左右都离屏幕边远一点（拿手机的手指常压在边上，以前一个按钮占半个屏宽，
     * 贴着边很容易误触）；调整模式四个按钮铺满一行。宽度定死不用 wrap_content：按钮字号是自动缩小的，和 wrap_content 不搭。
     */
    private void layoutToolbar(boolean adjusting) {
        for (TextView b : kbToolbar) {
            LinearLayout.LayoutParams p = adjusting
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    : new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT);
            int m = dp(adjusting ? 3 : 8);
            p.setMargins(m, 0, m, 0);
            b.setLayoutParams(p);
        }
    }

    /** 按钮条：固定的 + 设置里的若干个 + "收起"。按钮编号就是在条里的下标，设置改了个数就整条重建。 */
    private void rebuildStrip() {
        strip.removeAllViews();
        stripButtons.clear();
        for (String id : FIXED_ACTIONS) addStripButton(Actions.get(id));
        int n = prefs.slotCount();
        for (int i = 0; i < n; i++) {
            Actions.Action a = Actions.get(prefs.slot(i));
            addStripButton(a);
            if (a.kind == Actions.Kind.NONE) stripButtons.get(stripButtons.size() - 1).setVisibility(View.INVISIBLE);
        }
        addStripButton(Actions.get("toggle_strip"));
    }

    private void addStripButton(Actions.Action fixed) {
        TextView b = Style.button(this, 15);
        int id = stripButtons.size();
        if (fixed != null) {
            b.setText(fixed.shortLabel(this));
            b.setTag(fixed);
        }
        b.setOnTouchListener((v, e) -> localTouch(v, e, id));
        stripButtons.add(b);
        strip.addView(b);
    }

    /** "贴字"面板：用手机自己的输入法（语音、中文）打一句，经剪贴板贴到 DeX。 */
    private LinearLayout buildTextBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row1 = new LinearLayout(this);
        input = new EditText(this);
        input.setTextColor(Style.TEXT_ACTIVE);
        input.setHintTextColor(0xFF555555);
        input.setHint(R.string.text_hint);
        input.setBackground(Style.border(this, Style.BORDER, 0));
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

    private LinearLayout buildNumpad() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < NUMPAD_LABELS.length; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int c = 0; c < NUMPAD_LABELS[r].length; c++) {
                int usage = NUMPAD_USAGES[r][c];
                TextView b = Style.button(this, 18);
                b.setText(NUMPAD_LABELS[r][c]);
                b.setOnTouchListener((v, e) -> {
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN) {   // 先发键，再震、再画按下的样子
                        onLocalKey(usage, true);
                        keyHaptic();
                        Style.look(v, false, true);
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        onLocalKey(usage, false);
                        Style.look(v, false, false);
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
            default: return false;
        }
    }

    /**
     * 窗口能不能获得焦点。平时不能（不抢 DeX 的焦点）；两种情况要能：
     * - "贴字"面板开着：输入法要焦点；
     * - 没有外接屏：虚拟键盘的按键只能发到手机屏上，豆花是这块屏的当前应用，窗口却不收焦点，
     *   系统等 5 秒没人接，就弹"豆花没有响应"。
     */
    private void updateFocusable() {
        // 音量键设了动作要收按键：接着外接屏时也让窗口能获得焦点
        boolean focusable = textBar.getVisibility() == View.VISIBLE || targetDisplay == Display.INVALID_DISPLAY
                || hardKeysActive();
        if (focusable) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    /** 没有外接屏时，豆花虚拟键盘的键会送到这里：吞掉，免得 Esc 被系统当返回键把豆花关了。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (onHardKey(e)) return true;   // 音量键设了动作：归豆花（音量不变）
        InputDevice dev = e.getDevice();
        if (textBar.getVisibility() != View.VISIBLE && dev != null && dev.getVendorId() == Proto.HID_VENDOR) return true;
        return super.dispatchKeyEvent(e);
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsets.Type.systemBars());
    }

    // ═══════════ 告诉服务各区域在哪 ═══════════

    /**
     * 把各区域的位置交给分流的一方：DeX 模式发给服务，蓝牙模式给本地的 TouchRouter。
     * 服务独占触摸屏的条件之一是 active：豆花在前台、"贴字"面板没开、键盘不在调整模式（拖动要 App 自己收触摸）。
     * 蓝牙模式下也一样报，带上 relay：服务接管后把报文发回来（{@link #onRelayMouse}）；本地的 TouchRouter 同时备着，
     * 服务没开、没接管时用。
     */
    private void pushLayout() {
        if (pad == null) return;
        boolean keyboardShown = keyboardScreen.getVisibility() == View.VISIBLE;
        // 键盘在最上面（空中鼠标页上调出来的也是），先看它
        if (keyboardShown ? keyboardScreen.getWidth() == 0 : airOn ? airOverlay.getWidth() == 0 : pad.getWidth() == 0) return;
        List<int[]> regions = collectRegions(keyboardShown);
        if (btMode) {
            TouchRouter.Region[] rs = new TouchRouter.Region[regions.size()];
            for (int i = 0; i < rs.length; i++) {
                int[] r = regions.get(i);
                rs[i] = new TouchRouter.Region(r[0], r[1], r[2], r[3], r[4], r[5]);
            }
            local.setRegions(rs);
        }
        updateGestureExclusion();
        if (link.isConnected()) sendLayout(localActive(), regions);
        updateStatus();
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

    /** 豆花在前台、"贴字"面板没开、键盘不在调整模式：触摸由分流处理（否则按普通方式交给各个按钮）。 */
    private boolean localActive() {
        return resumed && textBar.getVisibility() != View.VISIBLE && !keyboardScreen.isAdjusting();
    }

    /**
     * 区域列表，每项 {type, id, left, top, right, bottom}（屏幕像素）；叠在上面的排在前面。
     * 只报看得见的那一屏：键盘（空中鼠标页上调出来的也是）、空中鼠标页、主界面三组互斥。
     * 键盘开着时连空中鼠标页的也报的话，键盘空隙里看不见的圆钮、格子照样按得到。
     */
    private List<int[]> collectRegions(boolean keyboardShown) {
        List<int[]> regions = new ArrayList<>();
        if (keyboardShown) {
            for (int i = 0; i < kbToolbar.size(); i++) {
                if (kbToolbar.get(i).getVisibility() == View.VISIBLE) {
                    addRegion(regions, kbToolbar.get(i), Proto.REGION_BUTTON, KB_CLOSE_ID + i);
                }
            }
            keyboard.collectRegions(regions);
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
        // 四角的小按钮叠在触控板上，要排在触控板前面（服务按顺序找第一个命中的区域）
        for (int i = 0; i < cornerButtons.size(); i++) {
            TextView b = cornerButtons.get(i);
            if (b.getVisibility() == View.VISIBLE) {
                addRegion(regions, b, holdLeft(b) ? Proto.REGION_HOLD_LEFT : Proto.REGION_BUTTON, CORNER_BASE_ID + i);
            }
        }
        addRegion(regions, pad, Proto.REGION_PAD, 0);
        for (int id = 0; id < stripButtons.size(); id++) {
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

    private void sendLayout(boolean active, List<int[]> regions) {
        Display d = getDisplay();
        Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        link.layout(active, btMode, d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY, d != null ? d.getRotation() : 0,
                bounds.width(), bounds.height(), regions);
    }

    /** "左键"按钮：鼠标左键由分流直接按住 / 松开（REGION_HOLD_LEFT），和触控板的手势合在一起算拖动。 */
    private static boolean holdLeft(View b) {
        return b.getTag() instanceof Actions.Action && ((Actions.Action) b.getTag()).kind == Actions.Kind.HOLD_LEFT;
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
            keyboard.showPressed(key, down);
            for (int i = 0; i < numpadButtons.size(); i++) {
                if (numpadUsages.get(i) == key) Style.look(numpadButtons.get(i), false, down);
            }
            return;
        }
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID || id == KeyboardPanel.GLOBE_BUTTON_ID
                || (id >= KeyboardPanel.ROW_ACTION_BASE_ID && id < KeyboardPanel.ROW_ACTION_BASE_ID + keyboardRow.size())) {
            if (down) onKeyHaptic(id, true);
            keyboard.showPressed(id, down);
            if (action == Proto.BUTTON_TAP) onSpecialKey(id);
            return;
        }
        if (id >= CORNER_BASE_ID && id < CORNER_BASE_ID + cornerButtons.size()) {
            TextView b = cornerButtons.get(id - CORNER_BASE_ID);
            b.setBackground(Style.border(this, 0xFF1E1E1E, down ? Style.PRESSED : 0));
            buttonAction((Actions.Action) b.getTag(), action, AirMouse.SRC_CORNER + id - CORNER_BASE_ID);
            return;
        }
        if (id >= KB_CLOSE_ID && id <= KB_TOOLBAR_POS_ID) {
            TextView b = kbToolbar.get(id - KB_CLOSE_ID);
            Style.look(b, id == KB_ADJUST_ID && keyboardScreen.isAdjusting(), down);
            if (action == Proto.BUTTON_TAP) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                onToolbar(id);
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

    /** 滚一格：DeX 模式交给服务的虚拟鼠标；蓝牙模式直接发，带上两路按着的键。 */
    private void wheel(int n) {
        if (btMode) bt.sendMouse(touchButtons | air.buttons(), 0, 0, n, 0);
        else link.wheel(n);
        if (scrollTicksOn) haptics.play(GestureEngine.HAPTIC_TICK);
    }

    private void stopWheel() {
        wheelDir = 0;
        wheelSource = Integer.MIN_VALUE;
        main.removeCallbacks(wheelRepeat);
    }

    /**
     * 实体键按住"左键"：蓝牙模式经空中指针那一路发；DeX 模式鼠标在服务里，经服务的虚拟鼠标按住（CMD_HOLD_LEFT，
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

    /** 两路里还按着左键没有（蓝牙模式才知道；DeX 模式下左键的状态在服务那边）。 */
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

    /** 键盘上的"符" / "Fn" / 🌐，和快捷行里不是键盘组合键的动作。 */
    @Override
    public void onSpecialKey(int id) {
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID) {
            keyboard.onLayerButton(id);
        } else if (id == KeyboardPanel.GLOBE_BUTTON_ID) {
            run(Actions.get("lang_switch"));
        } else if (id >= KeyboardPanel.ROW_ACTION_BASE_ID) {
            int i = id - KeyboardPanel.ROW_ACTION_BASE_ID;
            if (i < keyboardRow.size()) run(keyboardRow.get(i));
        }
    }

    // ═══════════ 键盘屏 ═══════════

    /** 键盘屏开 / 关。空中鼠标页上调出来时键盘盖在页面上面，空中鼠标暂停（见 {@link #airPaused}），收起回到页面。 */
    private void setKeyboardScreen(boolean on) {
        boolean was = keyboardScreen.getVisibility() == View.VISIBLE;
        if (on) {
            closeTextBar();
            keyboardScreen.setRect(prefs.keyboardRect(keyboardScreen.isLandscape()));
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

    private void onToolbar(int id) {
        boolean landscape = keyboardScreen.isLandscape();
        switch (id) {
            case KB_CLOSE_ID:
                setKeyboardScreen(false);
                break;
            case KB_ADJUST_ID:
                setAdjusting(!keyboardScreen.isAdjusting());
                break;
            case KB_RESET_ID:
                prefs.resetKeyboardRect(landscape);
                keyboardScreen.setRect(prefs.keyboardRect(landscape));
                break;
            case KB_TOOLBAR_POS_ID: {
                boolean bottom = !prefs.keyboardToolbarBottom();
                prefs.setKeyboardToolbarBottom(bottom);
                keyboardScreen.setToolbarAtBottom(bottom);
                updateToolbarLabels();
                break;
            }
            default:
                break;
        }
    }

    /** 调整模式：服务释放触摸屏，拖动由 App 自己收。 */
    private void setAdjusting(boolean on) {
        keyboardScreen.setAdjusting(on);
        kbToolbar.get(KB_RESET_ID - KB_CLOSE_ID).setVisibility(on ? View.VISIBLE : View.GONE);
        kbToolbar.get(KB_TOOLBAR_POS_ID - KB_CLOSE_ID).setVisibility(on ? View.VISIBLE : View.GONE);
        layoutToolbar(on);
        updateToolbarLabels();
        for (TextView b : kbToolbar) Style.look(b, b == kbToolbar.get(1) && on, false);
        updateStatus();
        pushLayout();
    }

    private void updateToolbarLabels() {
        kbToolbar.get(KB_ADJUST_ID - KB_CLOSE_ID).setText(keyboardScreen.isAdjusting() ? R.string.kb_done : R.string.kb_adjust);
        kbToolbar.get(KB_TOOLBAR_POS_ID - KB_CLOSE_ID).setText(
                prefs.keyboardToolbarBottom() ? R.string.kb_toolbar_up : R.string.kb_toolbar_down);
    }

    @Override
    public void onKeyboardRectChanged(boolean landscape, float[] rect) {
        prefs.setKeyboardRect(landscape, rect);
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
        if (keyboardScreen.getVisibility() == View.VISIBLE) {
            keyboardScreen.setRect(prefs.keyboardRect(keyboardScreen.isLandscape()));
        }
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
        keyboard.setModifiers(mask);
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
            case TOGGLE_STRIP: {
                boolean hidden = !prefs.stripHidden();
                prefs.setStripHidden(hidden);
                applyStripHidden(hidden);
                break;
            }
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
            default:
                break;
        }
    }

    /** 键盘上没有的系统键：DeX 模式注入到 DeX 屏，蓝牙模式发对应的媒体键（没有通用媒体键的提示一下）。 */
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
            link.rotate(d.getDisplayId(), target);
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
            Display d = getDisplay();
            if (prefs.rotation() >= 0 && d != null) link.rotate(d.getDisplayId(), prefs.rotation());
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
                // 屏幕按钮的滚轮经服务转来；蓝牙模式下音量键的不归服务（DeX 模式下服务截的音量键上面 endServerHolds 已收尾）
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
                if (wasGrabbed) {   // 服务转来的键按下等不到松开了：按下的样子、放大提示收起
                    keyboard.clearPressed();
                    for (TextView b : numpadButtons) Style.look(b, false, false);
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
            // DeX 模式下没接管时按钮是 View 自己收的（localTouch），接管后系统收不到触摸，按着的按钮等不到松开
            if (wheelSource >= AirMouse.SRC_ROUND) stopWheel();
            leftHolds = 0;
            haptics.stopHum();
            updateActiveButtons();
        }
        if (!isGrabbed && air.buttons() == 0) haptics.stopHum();
        if (isGrabbed && btMode) {
            // 服务接管后系统收不到触摸，本地分流里正按着的手指等不到抬起：先收尾，按着的键松开
            local.releaseAll();
            localGesture = false;
        }
        grabMessage = grabText(reason, detail);
        updateStatus();
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

    /** 服务只发原因代码，文字在这里按系统语言拼。 */
    private String grabText(int reason, String detail) {
        switch (reason) {
            case Proto.GRAB_PAUSED: return getString(R.string.grab_paused);
            case Proto.GRAB_UNRESPONSIVE: return getString(R.string.grab_unresponsive);
            case Proto.GRAB_ROTATING: return getString(R.string.grab_rotating);
            case Proto.GRAB_NO_TOUCHSCREEN: return getString(R.string.grab_no_touchscreen, detail);
            case Proto.GRAB_FAILED: return getString(R.string.grab_failed, detail);
            case Proto.GRAB_RELEASED: return getString(R.string.grab_released);
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
            s = targetName.isEmpty() ? getString(R.string.status_no_dex) : targetName;
            if (!linkMessage.isEmpty()) s += " · " + linkMessage;
            ok = true;
        }
        pad.setStatus(s, ok);
        miniPad.setStatus(s, ok);
        boolean tappable = statusTappable();
        pad.setStatusTappable(tappable);
        miniPad.setStatusTappable(tappable);
    }

    /**
     * 状态字能不能点（点了打开设置页，最上面就是"豆花儿服务"那一节）：服务没开 / 没响应 / 版本不对的时候。
     * DeX 模式下这时没人接管触摸，触控板本来就不动；蓝牙模式下没开服务是正常配置，只有卡住才算。
     */
    private boolean statusTappable() {
        return btMode ? linkStuck : !linkOk;
    }

    /** 这一下按在可点的状态字上吗（看得见的那块触控板）。和角上看得见的小按钮重叠时归按钮（文字很长时也点得到 ☰）。 */
    private boolean onStatus(MotionEvent e) {
        if (!statusTappable()) return false;
        float x = e.getRawX(), y = e.getRawY();
        // 只看最上面那一屏：键盘屏盖着主界面（触控板还算"显示着"），空中鼠标页上没有状态字
        if (keyboardScreen.getVisibility() == View.VISIBLE) return miniPad.statusHit(x, y);
        if (airOn) return false;
        int[] at = new int[2];
        for (TextView b : cornerButtons) {
            if (!b.isShown()) continue;
            b.getLocationOnScreen(at);
            if (x >= at[0] && x < at[0] + b.getWidth() && y >= at[1] && y < at[1] + b.getHeight()) return false;
        }
        return pad.statusHit(x, y);
    }

    // ═══════════ 空中鼠标 ═══════════

    /**
     * 空中鼠标页：盖住触控板屏；在页上调出的键盘反过来盖住它（这时页面隐藏，见 applyAirOverlayVisibility）。
     * 上面一行状态，中间是可设定的大按钮和圆钮，下面"回正中"和"按住退出"。默认隐藏。
     */
    private View buildAirOverlay() {
        airOverlay = new FrameLayout(this);
        airOverlay.setBackgroundColor(Color.BLACK);
        airOverlay.setClickable(true);   // 没有服务、服务没接管时，挡住底下的按钮
        airOverlay.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(16));
        airOverlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        airStatus = airText("", 14, Style.TEXT);
        airStatus.setSingleLine(true);
        airStatus.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(airStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        airState = airText("", 16, 0xFF8AB4F8);
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
        for (int bit = 0; bit < 4; bit++) {
            if ((latchedMods & (1 << bit)) == 0) continue;
            link.hidKey(0xE0 + bit, true);
            link.hidKey(0xE0 + bit, false);
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
     * 告诉服务截哪些音量键（连上时、改设置后、通话状态变了时）。DeX 模式按配置；蓝牙模式 App 自己收，全发 0。
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

    /** 音量键有没有设动作（蓝牙模式才由 App 自己收；DeX 模式下由服务截，窗口照旧不抢 DeX 的焦点）。 */
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
        // 点状态字（服务没开 / 没响应时）：整次手势归它，不当触控板的点击、也不分给按钮
        if (action == MotionEvent.ACTION_DOWN) statusPress = onStatus(e);
        if (statusPress) {
            if (action == MotionEvent.ACTION_UP) {
                statusPress = false;
                if (onStatus(e)) startActivity(new Intent(this, SettingsActivity.class));
            } else if (action == MotionEvent.ACTION_CANCEL) {
                statusPress = false;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) localGesture = btMode && localActive() && local.ready();
        if (!localGesture) return super.dispatchTouchEvent(e);
        routeLocal(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) localGesture = false;
        return true;
    }

    /**
     * MotionEvent → TouchRouter。区域是屏幕坐标（getLocationOnScreen），触摸也换成屏幕坐标；
     * 系统把一帧帧的触摸攒成一批（历史点），逐帧喂进去，手势引擎看到的和服务读触摸屏时一样细。
     */
    private void routeLocal(MotionEvent e) {
        float offX = e.getRawX() - e.getX();
        float offY = e.getRawY() - e.getY();
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
                local.up(e.getPointerId(e.getActionIndex()), e.getEventTime());
                break;
            case MotionEvent.ACTION_CANCEL:
                local.releaseAll();
                break;
            default:
                break;
        }
    }

    /** 本地分流的结果：鼠标键盘发蓝牙，其余和服务发来的消息走同一套处理。 */
    private final class LocalOutput implements TouchRouter.Output {
        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            sendTouchMouse(buttons, dx, dy, wheel, pan);
        }

        @Override
        public void keyboard(byte[] report) {
            bt.sendKeyboard(report);
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
    }

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

    /** 蓝牙连着苹果设备时，修饰键按苹果写法标、内置快捷键按苹果习惯发（⌘C 而不是 Ctrl+C）；DeX 和别的主机照通用的。 */
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
     * 蓝牙模式下系统的边缘返回手势还在（DeX 模式下触摸被服务独占，系统碰不到）：从左右边缘往里横划移光标会被当成返回。
     * 请系统别在左右两条边上认返回手势；导航栏是"划出才显示"的隐藏状态，系统不限制这块的高度。
     */
    private void updateGestureExclusion() {
        View root = getWindow().getDecorView();
        List<Rect> rects = new ArrayList<>();
        if (btMode) {
            int w = dp(32);
            rects.add(new Rect(0, 0, w, root.getHeight()));
            rects.add(new Rect(root.getWidth() - w, 0, root.getWidth(), root.getHeight()));
        }
        root.setSystemGestureExclusionRects(rects);
    }

    /** 排除区以外的地方还是可能触发返回：蓝牙模式下吞掉返回，豆花不会被意外关掉。 */
    private void applyBackSwallow() {
        if (btMode == backSwallowed) return;
        if (btMode) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, swallowBack);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(swallowBack);
        }
        backSwallowed = btMode;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
