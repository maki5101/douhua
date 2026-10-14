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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Display;
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
        BtHid.Listener {

    /** 按钮条收起后，触控板四角的小按钮（左上、右上、右下可自定义；左下 ☰ 固定，是回到按钮条和设置的入口）。 */
    private static final int CORNER_BASE_ID = 500;
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
    private static final String[] FIXED_ACTIONS = {"exit", "rotate", "settings"};
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
    private boolean grabbed;
    private String grabMessage = "";
    // ── 蓝牙模式 ──
    private boolean btMode;
    private BtHid bt;
    private boolean btAcquired;
    private int btState = BtHid.STATE_OFF;
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
        bt = BtHid.get(this);
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
        linkSeen = false;
        applyPrefs();
        refreshDisplay();
        displays.registerDisplayListener(this, main);
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
        pushLayout();
        super.onPause();
    }

    @Override
    protected void onStop() {
        closeTextBar();
        link.stop();
        grabbed = false;
        if (btAcquired) {
            bt.release(this);
            btAcquired = false;
        }
        displays.unregisterDisplayListener(this);
        super.onStop();
    }

    /** 设置页改完回来：按新设置更新按钮和震动强度，参数发给服务（蓝牙模式下给本地分流）。 */
    private void applyPrefs() {
        btMode = prefs.btMode();
        updateGuiLabel();
        local.setConfig(prefs.engineConfig(), density);
        applyBackSwallow();
        if (!btMode) requestOrientationSelf(-1);   // 蓝牙模式下等连服务的结果再定，见 onLinkStatus
        haptics.setLevel(prefs.haptic());
        haptics.setTickStrength(prefs.tickStrength());
        haptics.setDragHum(prefs.dragHum());
        applyStripHidden(prefs.stripHidden());
        GestureEngine.Config cfg = prefs.engineConfig();
        float edge = cfg.edgeWidthDp * density;
        pad.setEdgeGuides(edge, cfg.edgeScroll, cfg.edgeScrollHorizontal);
        miniPad.setEdgeGuides(edge, cfg.edgeScroll, cfg.edgeScrollHorizontal);
        for (int i = 0; i < Prefs.SLOT_COUNT; i++) {
            Actions.Action a = Actions.get(prefs.slot(i));
            TextView b = stripButtons.get(FIXED_ACTIONS.length + i);
            b.setText(a.shortLabel(this));
            b.setTag(a);
            b.setVisibility(a.kind == Actions.Kind.NONE ? View.INVISIBLE : View.VISIBLE);
        }
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
        for (String id : FIXED_ACTIONS) addStripButton(Actions.get(id));
        for (int i = 0; i < Prefs.SLOT_COUNT; i++) addStripButton(null);
        addStripButton(Actions.get("toggle_strip"));

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
        miniPad = new TrackpadView(this);
        keyboard = new KeyboardPanel(this, this);
        // 键盘和数字键盘按实体键盘排，阿拉伯语这类从右往左的语言下也不镜像
        keyboard.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        numpad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        keyboardScreen = new KeyboardScreen(this, toolbar, miniPad, keyboard, this);
        keyboardScreen.setVisibility(View.GONE);
        root.addView(keyboardScreen, new FrameLayout.LayoutParams(
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
        TextView b = Style.button(this, 15);
        b.setText(label);
        b.setOnTouchListener((v, e) -> localTouch(v, e, id));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        toolbar.addView(b, p);
        kbToolbar.add(b);
        return b;
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
                    if (a == MotionEvent.ACTION_DOWN) {
                        Style.look(v, false, true);
                        onKeyHaptic();
                        onLocalKey(usage, true);
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        Style.look(v, false, false);
                        onLocalKey(usage, false);
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
    }

    private boolean isActive(View b) {
        if (!(b.getTag() instanceof Actions.Action)) return false;
        switch (((Actions.Action) b.getTag()).kind) {
            case KEYBOARD: return keyboardScreen.getVisibility() == View.VISIBLE;
            case NUMPAD: return numpad.getVisibility() == View.VISIBLE;
            case PHONE_TEXT: return textBar.getVisibility() == View.VISIBLE;
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
        boolean focusable = textBar.getVisibility() == View.VISIBLE || targetDisplay == Display.INVALID_DISPLAY;
        if (focusable) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    /** 没有外接屏时，豆花虚拟键盘的键会送到这里：吞掉，免得 Esc 被系统当返回键把豆花关了。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
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
        if (keyboardShown ? keyboardScreen.getWidth() == 0 : pad.getWidth() == 0) return;
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

    /** 豆花在前台、"贴字"面板没开、键盘不在调整模式：触摸由分流处理（否则按普通方式交给各个按钮）。 */
    private boolean localActive() {
        return resumed && textBar.getVisibility() != View.VISIBLE && !keyboardScreen.isAdjusting();
    }

    /** 区域列表，每项 {type, id, left, top, right, bottom}（屏幕像素）；叠在上面的排在前面。 */
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
        // 四角的小按钮叠在触控板上，要排在触控板前面（服务按顺序找第一个命中的区域）
        for (int i = 0; i < cornerButtons.size(); i++) {
            if (cornerButtons.get(i).getVisibility() == View.VISIBLE) {
                addRegion(regions, cornerButtons.get(i), Proto.REGION_BUTTON, CORNER_BASE_ID + i);
            }
        }
        addRegion(regions, pad, Proto.REGION_PAD, 0);
        for (int id = 0; id < stripButtons.size(); id++) {
            TextView b = stripButtons.get(id);
            if (b.getVisibility() != View.VISIBLE || !(b.getTag() instanceof Actions.Action)) continue;
            boolean hold = ((Actions.Action) b.getTag()).kind == Actions.Kind.HOLD_LEFT;
            addRegion(regions, b, hold ? Proto.REGION_HOLD_LEFT : Proto.REGION_BUTTON, id);
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
            if (down) onKeyHaptic();
            keyboard.showPressed(key, down);
            for (int i = 0; i < numpadButtons.size(); i++) {
                if (numpadUsages.get(i) == key) Style.look(numpadButtons.get(i), false, down);
            }
            return;
        }
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID || id == KeyboardPanel.GLOBE_BUTTON_ID
                || (id >= KeyboardPanel.ROW_ACTION_BASE_ID && id < KeyboardPanel.ROW_ACTION_BASE_ID + keyboardRow.size())) {
            if (down) onKeyHaptic();
            keyboard.showPressed(id, down);
            if (action == Proto.BUTTON_TAP) onSpecialKey(id);
            return;
        }
        if (id >= CORNER_BASE_ID && id < CORNER_BASE_ID + cornerButtons.size()) {
            TextView b = cornerButtons.get(id - CORNER_BASE_ID);
            b.setBackground(Style.border(this, 0xFF1E1E1E, down ? Style.PRESSED : 0));
            if (action == Proto.BUTTON_TAP) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                run((Actions.Action) b.getTag());
            }
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
        if (id < 0 || id >= stripButtons.size()) return;
        TextView b = stripButtons.get(id);
        if (!(b.getTag() instanceof Actions.Action)) return;
        Actions.Action a = (Actions.Action) b.getTag();
        boolean hold = a.kind == Actions.Kind.HOLD_LEFT;   // 左键的按住 / 松开由服务直接处理，这里只管画面和震动
        Style.look(b, isActive(b), down);
        if (down) {
            if (hold) {
                haptics.play(GestureEngine.HAPTIC_CLICK);
                haptics.startHum();   // 按着"左键"按钮 = 拖动中
            }
        } else if (hold) {
            haptics.play(GestureEngine.HAPTIC_DRAG_END);
        } else if (action == Proto.BUTTON_TAP) {
            haptics.play(GestureEngine.HAPTIC_CLICK);
            run(a);
        }
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

    private void setKeyboardScreen(boolean on) {
        if (on) {
            closeTextBar();
            keyboardScreen.setRect(prefs.keyboardRect(keyboardScreen.isLandscape()));
        } else {
            setAdjusting(false);
        }
        mainRow.setVisibility(on ? View.GONE : View.VISIBLE);
        keyboardScreen.setVisibility(on ? View.VISIBLE : View.GONE);
        updateActiveButtons();
        updateStatus();
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
        // 横竖屏各有一份键盘大小
        if (keyboardScreen.getVisibility() == View.VISIBLE) {
            keyboardScreen.setRect(prefs.keyboardRect(keyboardScreen.isLandscape()));
        }
    }

    @Override
    public void onKeyHaptic() {
        haptics.play(GestureEngine.HAPTIC_CLICK);
    }

    @Override
    public void onRelayMouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (btMode) bt.sendMouse(buttons, dx, dy, wheel, pan);
    }

    @Override
    public void onRelayKeyboard(byte[] report) {
        if (btMode) bt.sendKeyboard(report);
    }

    @Override
    public void onModifiers(int mask) {
        keyboard.setModifiers(mask);
    }

    // ═══════════ 动作 ═══════════

    private void run(Actions.Action a) {
        switch (a.kind) {
            case HID:   // 虚拟键盘按一下这个组合键
                onLocalKey(a.code, true);
                onLocalKey(a.code, false);
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
        if (btMode) local.click(button);
        else link.click(button);
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
        haptics.play(kind);
    }

    @Override
    public void onLinkStatus(boolean connected, String message) {
        linkOk = connected;
        linkMessage = message;
        if (connected) {
            linkSeen = true;
            requestOrientationSelf(-1);   // 方向交给服务
            link.config(prefs.engineConfig(), density);
            Display d = getDisplay();
            if (prefs.rotation() >= 0 && d != null) link.rotate(d.getDisplayId(), prefs.rotation());
            pushLayout();
        } else {
            // 蓝牙模式下这次一直没连上服务：没有服务，自己请求方向。连上过又断的不请求，免得服务重启时被塞进横框
            if (btAcquired && !linkSeen && prefs.rotation() >= 0) requestOrientationSelf(prefs.rotation());
            grabbed = false;
            keyboard.setModifiers(0);
            haptics.stopHum();   // 断了就收不到"拖动结束"了
        }
        updateStatus();
    }

    @Override
    public void onGrab(boolean isGrabbed, int reason, String detail) {
        grabbed = isGrabbed;
        if (!isGrabbed) haptics.stopHum();
        if (isGrabbed && btMode) {
            // 服务接管后系统收不到触摸，本地分流里正按着的手指等不到抬起：先收尾，按着的键松开
            local.releaseAll();
            localGesture = false;
        }
        grabMessage = grabText(reason, detail);
        updateStatus();
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
        if (btMode) {
            s = btStatusText();
            ok = btState == BtHid.STATE_CONNECTED;
        } else if (!linkOk) {
            s = linkMessage;
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
    }

    // ═══════════ 蓝牙模式 ═══════════

    /** 蓝牙模式下自己收触摸，整次手势（按下到全部抬起）交给本地分流；其余情况照常分给各个按钮。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
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
            bt.sendMouse(buttons, dx, dy, wheel, pan);
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
        btState = state;
        btDevice = deviceName;
        if (state != BtHid.STATE_CONNECTED) local.releaseAll();   // 断了：别让对方那头留着按住的键
        updateGuiLabel();
        updateStatus();
    }

    /** 键盘上 GUI 键的名字：蓝牙连着苹果设备时叫 ⌘，其余（DeX、别的主机）一律叫 Super。 */
    private void updateGuiLabel() {
        boolean apple = btMode && btState == BtHid.STATE_CONNECTED && BtHid.isApple(bt.connectedDevice());
        keyboard.setGuiLabel(apple ? "⌘" : "Super");
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
