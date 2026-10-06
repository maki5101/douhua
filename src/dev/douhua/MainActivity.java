package dev.douhua;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.Manifest;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面有两屏：
 * - 触控板屏：左边一竖条按钮（左手拇指够得着），右边是触控板，可以展开数字键盘；
 * - 键盘屏：整屏是外接键盘（大小位置可调），键盘上方空出来的地方是小触控板。
 * 整个界面纯黑，只有按钮和触控板的暗灰边框。
 *
 * 触摸由豆花服务处理：豆花在前台时，服务独占这块屏的触摸屏，按这里报上去的区域位置识别手势、判断按到了哪个按钮 / 键，
 * 再把按钮事件、震动、三指四指手势发回来，这里负责画面和执行动作。键盘键由服务直接发给虚拟 USB 键盘。
 * 服务没接管时（"贴字"面板打开、服务没连上），按钮和键按普通方式响应触摸。
 *
 * 窗口平时设成"不可获得焦点"，DeX 里的窗口不会因为碰了手机屏而变成未激活。"贴字"面板打开时、没有外接屏时允许，
 * 见 {@link #updateFocusable}。
 */
public final class MainActivity extends Activity
        implements Link.Listener, KeyboardPanel.Listener, KeyboardScreen.Listener, DisplayManager.DisplayListener {

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
    private String linkMessage = "";
    private boolean grabbed;
    private String grabMessage = "";
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
        applyPrefs();
        refreshDisplay();
        displays.registerDisplayListener(this, main);
        link.start();
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
        main.removeCallbacks(heartbeat);
        pushLayout();
        super.onPause();
    }

    @Override
    protected void onStop() {
        closeTextBar();
        link.stop();
        grabbed = false;
        displays.unregisterDisplayListener(this);
        super.onStop();
    }

    /** 设置页改完回来：按新设置更新按钮和震动强度，参数发给服务。 */
    private void applyPrefs() {
        haptics.setLevel(prefs.haptic());
        haptics.setTickStrength(prefs.tickStrength());
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

    /** 服务独占触摸屏的条件之一：豆花在前台、"贴字"面板没开、键盘不在调整模式（拖动要 App 自己收触摸）。 */
    private void pushLayout() {
        if (!link.isConnected() || pad == null) return;
        boolean keyboardShown = keyboardScreen.getVisibility() == View.VISIBLE;
        if (keyboardShown ? keyboardScreen.getWidth() == 0 : pad.getWidth() == 0) return;
        boolean active = resumed && textBar.getVisibility() != View.VISIBLE && !keyboardScreen.isAdjusting();
        List<int[]> regions = new ArrayList<>();
        if (keyboardShown) {
            for (int i = 0; i < kbToolbar.size(); i++) {
                if (kbToolbar.get(i).getVisibility() == View.VISIBLE) {
                    addRegion(regions, kbToolbar.get(i), Proto.REGION_BUTTON, KB_CLOSE_ID + i);
                }
            }
            keyboard.collectRegions(regions);
            if (keyboardScreen.isPadUsable()) addRegion(regions, miniPad, Proto.REGION_PAD, 0);
            sendLayout(active, regions);
            return;
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
        sendLayout(active, regions);
    }

    private void sendLayout(boolean active, List<int[]> regions) {
        Display d = getDisplay();
        Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        link.layout(active, d != null ? d.getDisplayId() : Display.DEFAULT_DISPLAY, d != null ? d.getRotation() : 0,
                bounds.width(), bounds.height(), regions);
        updateStatus();
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
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID
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
            if (hold) haptics.play(GestureEngine.HAPTIC_CLICK);
        } else if (hold) {
            haptics.play(GestureEngine.HAPTIC_DRAG_END);
        } else if (action == Proto.BUTTON_TAP) {
            haptics.play(GestureEngine.HAPTIC_CLICK);
            run(a);
        }
    }

    @Override
    public void onLocalKey(int key, boolean down) {
        link.hidKey(key, down);
    }

    /** 键盘上的"符" / "Fn"，和快捷行里不是键盘组合键的动作。 */
    @Override
    public void onSpecialKey(int id) {
        if (id == KeyboardPanel.SYMBOLS_BUTTON_ID || id == KeyboardPanel.FN_BUTTON_ID) {
            keyboard.onLayerButton(id);
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
    public void onModifiers(int mask) {
        keyboard.setModifiers(mask);
    }

    // ═══════════ 动作 ═══════════

    private void run(Actions.Action a) {
        switch (a.kind) {
            case HID:   // 虚拟键盘按一下这个组合键
                link.hidKey(a.code, true);
                link.hidKey(a.code, false);
                break;
            case KEY:
                key(a.code, a.meta);
                break;
            case CLICK:
                link.click(a.code);
                break;
            case HOLD_LEFT:
                link.click(Proto.BUTTON_LEFT);   // 分给手势时就是单击
                break;
            case KEYBOARD:
                setKeyboardScreen(keyboardScreen.getVisibility() != View.VISIBLE);
                break;
            case NUMPAD:
                if (keyboardScreen.getVisibility() == View.VISIBLE) setKeyboardScreen(false);
                showPanel(numpad.getVisibility() == View.VISIBLE ? null : numpad);
                break;
            case PHONE_TEXT:
                if (textBar.getVisibility() == View.VISIBLE) closeTextBar();
                else openTextBar();
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

    private void key(int keyCode, int meta) {
        link.key(targetDisplay, keyCode, meta);
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
        link.rotate(d.getDisplayId(), target);
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
            link.config(prefs.engineConfig(), density);
            Display d = getDisplay();
            if (prefs.rotation() >= 0 && d != null) link.rotate(d.getDisplayId(), prefs.rotation());
            pushLayout();
        } else {
            grabbed = false;
            keyboard.setModifiers(0);
        }
        updateStatus();
    }

    @Override
    public void onGrab(boolean isGrabbed, int reason, String detail) {
        grabbed = isGrabbed;
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
        if (!linkOk) {
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

    private int dp(float v) {
        return Math.round(v * density);
    }
}
