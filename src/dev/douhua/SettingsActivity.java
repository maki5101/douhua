package dev.douhua;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * 设置页。改动即时保存，回到触控板时生效。
 *
 * 分七个标签页（顶上一排标签固定，不随页面滚动）：通用、触控板、键盘、按钮与按键、空中鼠标、手柄、外观。
 * "键盘"页（第 1B 批，2026-11-01）：用哪块键盘（豆花布局 / 实体键盘）、实体键盘是哪一块（语言、形状、尺寸、Windows / Mac 模式）、
 * 豆花布局的快捷行（从"按钮与按键"挪过来）。"通用"页最下面加了"开源许可"（键帽数据和 adb 配对代码的许可全文）。
 * 每次 {@link #build} 七页的内容都照旧全建出来，只显示选中那一页（其它 GONE）：豆花儿服务一节（ServiceSection 的观察者、实时刷新）、
 * 蓝牙设备列表、音频提醒都引用建好的 view，只建当前页会漏引用；切页只改显示，不重建，进行到一半的东西（配对引导、填了一半的分辨率）都在。
 * "外观"页最后一节"声音"（声音第一批第 ④ 步，2026-11-02）：套装、音量、哪些出声、试听台（真的声音 + 真的震动，同一个 Sounds、同一套 SoundEvents 规则），
 * 调试版另有候选区（{@link #soundTuning}）。这页开着时也 acquire 那条声音管道（主界面 → 设置页时先 acquire、主界面后 release，管道不断）。
 */
public final class SettingsActivity extends Activity implements BtHid.Listener {
    /** 标签页的 id：存进 Prefs（记住上次停在哪一页），入口用 {@link #EXTRA_TAB} 指定；顺序就是标签的顺序。 */
    static final String TAB_GENERAL = "general";
    static final String TAB_PAD = "pad";
    static final String TAB_KEYBOARD = "keyboard";
    static final String TAB_BUTTONS = "buttons";
    static final String TAB_AIR = "air";
    static final String TAB_GAMEPAD = "gamepad";
    static final String TAB_LOOK = "look";
    private static final String[] TABS = {TAB_GENERAL, TAB_PAD, TAB_KEYBOARD, TAB_BUTTONS, TAB_AIR, TAB_GAMEPAD, TAB_LOOK};
    private static final int[] TAB_LABELS = {R.string.tab_general, R.string.tab_pad, R.string.tab_keyboard, R.string.tab_buttons,
            R.string.tab_air, R.string.tab_gamepad, R.string.tab_look};
    /** 打开设置页时停在哪一页（值是 TAB_*）；不带就停在上次那一页。 */
    static final String EXTRA_TAB = "dev.douhua.settings_tab";

    /**
     * 选中的标签：设置页自己的强调色（链接色 #8AB4F8）淡一层当底，字用亮一档的 Google 蓝 200；没选中的灰字。
     * 底叠在窗口底色（Theme.Material 的 #303030）上是 #465060，链接色的字在上面只有 3.9:1（比没选中的灰字 5.0:1 还淡），换成 #AECBFA 是 4.9:1。
     */
    private static final int TAB_ON_TEXT = 0xFFAECBFA;
    private static final int TAB_ON_FILL = 0x3D8AB4F8;
    private static final int TAB_OFF_TEXT = 0xFF9AA0A6;

    /**
     * 打开设置页、停在"通用"页最上面（"豆花儿服务"那一节）：主界面点状态字（服务没开 / 没响应）、配对的通知、
     * 通知里"去关掉无线调试"都走这里。以前那一节在整页最上面，打开就看得到；分页后要指定页。
     */
    static Intent serviceIntent(Context c) {
        return tabIntent(c, TAB_GENERAL);
    }

    /** 打开设置页、停在这一页最上面（手柄页 ⋯ 菜单里的"设置"停在"手柄"）。 */
    static Intent tabIntent(Context c, String tab) {
        return new Intent(c, SettingsActivity.class).putExtra(EXTRA_TAB, tab);
    }

    private Prefs prefs;
    /** 用哪块键盘、实体键盘的设置（纯 Java 的 Boards，跑在 Prefs 上）。 */
    private Boards boards;
    /** 正在往里加行的那一页（build 时依次指向各页；下面那些加行的小方法都往它里面加）。 */
    private LinearLayout list;
    /** 整页滚动的那块和它里面放五页的容器：只建一次，重建时只换里面的页，滚动位置因此留着（重新布局时按新内容截一下）。 */
    private ScrollView scroll;
    private LinearLayout pagesBox;
    private LinearLayout[] pages;
    private TextView[] tabViews;
    /** 现在显示的是第几页（TABS 的下标）。 */
    private int tab;
    private float density;
    private BtHid bt;
    private boolean btAcquired;
    /** 蓝牙状态那一行、设备列表（状态变了、配对了新设备时刷新）。 */
    private TextView btStatus;
    private LinearLayout btDevices;
    /** 连的那台还连着手机的通话 / 媒体音频时的提醒，见 {@link #refreshAudioWarning}。 */
    private TextView btAudioWarning;
    /** 系统的通话、媒体音频服务，只用来查连的那台有没有连着它们；这页可见时拿着。 */
    private BluetoothProfile headset;
    private BluetoothProfile a2dp;
    private boolean started;
    /** "通用"页最上面一节"豆花儿服务"（手机自己拉起服务的各项状态、配对引导）。 */
    private ServiceSection service;
    /** "外观"页的"触控板背景图"一节（选图、预览、显示方式……）。 */
    private PadBgSection padBg;

    // ── "外观"页的"声音"一节（声音第一批第 ④ 步） ──
    /** 进程一份的声音引擎（主界面也用它：试听的就是实际用的）。 */
    private Sounds sounds;
    /** 试听台的事件层：和主界面同一套规则（SoundEvents），只是交给它的是试听台的按键。 */
    private SoundEvents benchEvents;
    /** 试听台、候选区的震动：照设置配（强度、咔嗒强度、持续轻震、调试版的惯性波形和震动延后），同主界面。 */
    private Haptics benchHaptics;
    private AudioManager audio;
    /** 通话中 / 来电响铃时不出声（同 MainActivity：AudioManager 的模式不是 NORMAL）。 */
    private final AudioManager.OnModeChangedListener audioMode = mode -> sounds.setSuppressed(inCall());
    /** 点一项套装后放的那一小段、候选区的"连打"、诊断行的刷新：各一个 Handler，换一个 / 关页面时整个撤掉。 */
    private final Handler demoHandler = new Handler(Looper.getMainLooper());
    private final Handler burstHandler = new Handler(Looper.getMainLooper());
    private final Handler diagHandler = new Handler(Looper.getMainLooper());
    /** 点一项套装后等声音算好（Sounds.ready）最多等几次（每次 50 ms）。 */
    private static final int DEMO_WAIT_TRIES = 80;
    private int demoTries;
    /** 套装以外那些（音量、开关、试听台、候选区）：套装是"关"时整块藏起来。 */
    private LinearLayout soundBox;
    /** 换了主题：试听台照新主题重画（build 时置空，appearance 里的刷新先于这一节建好）。 */
    private Runnable benchTheme;
    /** 试听台按键时照这套主题画按下的样子（benchTheme 更新）。 */
    private Theme benchT;
    private boolean benchBorders;
    /** 试听台的"左键"按着几个（持续轻震的计数，同 MainActivity.holdLeftFeedback）。 */
    private int benchLeftHolds;
    /** 试听台那一小块触控板认轻点 / 划（每次按下照当时的设置新建）和跟着的那根手指。 */
    private SoundBench.Pad benchPad;
    private int benchPadPointer = -1;
    /** 调试版候选区：每个槽那几行（换套时重填）、"打乱顺序"开着没有和这一轮的种子、诊断行。 */
    private LinearLayout soundCands;
    private boolean soundShuffle;
    private long shuffleSeed;
    private TextView soundDiag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        boards = prefs.boards(FixedData.catalog(this));
        density = getResources().getDisplayMetrics().density;
        bt = BtHid.get(this);
        service = new ServiceSection(this, prefs);
        padBg = new PadBgSection(this, prefs);
        sounds = Sounds.get(this);
        audio = getSystemService(AudioManager.class);
        benchEvents = new SoundEvents((id, key, delayMs) -> sounds.play(id, key, delayMs));
        benchHaptics = new Haptics(this);
        // 转屏重建、从最近任务里回来：停在上次那一页（每次切页都存了），入口指定的页、转去无线调试都不再来一次
        boolean fresh = savedInstanceState == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
        tab = tabIndex(prefs.settingsTab());
        String asked = fresh ? getIntent().getStringExtra(EXTRA_TAB) : null;
        if (asked != null) {
            tab = tabIndex(asked);
            prefs.setSettingsTab(TABS[tab]);
        }
        buildShell();
        build();
        if (fresh) openWirelessIfAsked(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 设置页开着时又从通知点进来（"去关掉无线调试"带 SINGLE_TOP）：换到指定的页、滚到顶
        String asked = intent.getStringExtra(EXTRA_TAB);
        if (asked != null) selectTab(tabIndex(asked));
        openWirelessIfAsked(intent);
    }

    /** 通知里点了"去关掉无线调试"（ServiceSection.openWirelessIntent）：转去开发者选项的无线调试那一页。 */
    private void openWirelessIfAsked(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(ServiceSection.EXTRA_OPEN_WIRELESS, false)) return;
        intent.removeExtra(ServiceSection.EXTRA_OPEN_WIRELESS);
        service.openWirelessSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        service.start();   // 从系统设置回来（开了开发者选项、配对完……）：各项马上刷新
        padBg.start();   // 背景图导入完时这页不在前台的话，回来刷新
        // 声音：照设置配好再用着管道（套装"关"时什么都不建）；主界面这时还没 release，管道不断
        applySound();
        sounds.acquire();
        if (audio != null) audio.addOnModeChangedListener(getMainExecutor(), audioMode);
        if (sounds.tuning()) diagHandler.post(diagTick);
        updateBtUse();
        openAudioProxies();
        // 刚配对好一台（系统的配对确认框不一定让这页 onResume）：列表马上刷新；
        // 对方连上 / 断开手机的通话、媒体音频：提醒跟着出现 / 消失
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(bondReceiver, f);
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            refreshBtDevices();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        refreshBtDevices();   // 从系统的配对对话框回来：列表里可能多了一台
    }

    @Override
    protected void onStop() {
        started = false;
        service.stop();
        padBg.stop();
        // 声音：还没放完的那一小段、连打、诊断刷新都撤掉，试听台按着的"左键"的持续轻震停掉，再放开管道（计数到 0 就关）
        demoHandler.removeCallbacksAndMessages(null);
        burstHandler.removeCallbacksAndMessages(null);
        diagHandler.removeCallbacksAndMessages(null);
        benchLeftHolds = 0;
        benchHaptics.stopHum();
        if (audio != null) audio.removeOnModeChangedListener(audioMode);
        sounds.release();
        unregisterReceiver(bondReceiver);
        closeAudioProxies();
        if (btAcquired) {
            bt.release(this);
            btAcquired = false;
        }
        super.onStop();
    }

    /** 选了蓝牙模式就用着 BtHid（显示状态、能切设备）；切回桌面模式就立刻停掉。 */
    private void updateBtUse() {
        if (prefs.btMode() && !btAcquired) {
            bt.acquire(this);
            btAcquired = true;
        } else if (!prefs.btMode() && btAcquired) {
            bt.release(this);
            btAcquired = false;
            bt.stopNow();
        }
    }

    // ═══════════ 标签页 ═══════════

    /**
     * 只建一次的外壳：标题、一排标签（放不下就横着滑）、一条分隔线，下面是整页滚动的那块。
     * 标签和标题在滚动的那块外面，所以不随页面滚走。
     */
    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });

        TextView title = text(getString(R.string.settings_title), 22, 0xFFE8E8E8);
        title.setPadding(dp(20), dp(16), dp(20), dp(8));
        root.addView(title);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(8));   // 加上标签自己的 12dp，字和下面内容的 20dp 边距对齐
        tabViews = new TextView[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = text(getString(TAB_LABELS[i]), 15, TAB_OFF_TEXT);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setOnClickListener(v -> selectTab(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(4));
            row.addView(t, lp);
            tabViews[i] = t;
        }
        tabScroll.addView(row);
        root.addView(tabScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0xFF3C4043);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        scroll = new ScrollView(this);
        pagesBox = new LinearLayout(this);
        pagesBox.setOrientation(LinearLayout.VERTICAL);
        pagesBox.setPadding(dp(20), 0, dp(20), dp(32));
        scroll.addView(pagesBox);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    /** 点了标签（或者入口指定了页）：换过去、记下来、滚到顶。 */
    private void selectTab(int index) {
        // 正在填的输入框（空中鼠标页的分辨率）跟着那一页藏起来：先收起输入法，免得键盘留在屏幕上、字不知道打到哪
        View focus = getCurrentFocus();
        if (focus != null && index != tab) {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        tab = index;
        prefs.setSettingsTab(TABS[index]);
        showTab();
        // 上一页还在惯性滑动时光 scrollTo 不够：ScrollView 下一帧 computeScroll 照滑动的绝对位置把新页拽回去。
        // 先回顶、再从顶上来一次零速的 fling，顶掉还在走的那次（零速：时长 0、终点就是起点，下一帧就结束、位置不动）。
        // 顺序不能反：先 fling(0) 的话起点是旧位置，下一帧又被拽回去
        scroll.scrollTo(0, 0);
        scroll.fling(0);
    }

    /** 只显示选中那一页，标签换样子，选中的标签挪进看得见的地方（窄屏上标签横着滑）。 */
    private void showTab() {
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == tab;
            TextView t = tabViews[i];
            t.setSelected(on);
            t.setTextColor(on ? TAB_ON_TEXT : TAB_OFF_TEXT);
            t.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            if (on) {
                android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
                fill.setCornerRadius(dp(18));
                fill.setColor(TAB_ON_FILL);
                t.setBackground(fill);
            } else {
                t.setBackground(null);
            }
        }
        TextView on = tabViews[tab];
        // 刚建好时还没量过尺寸：等布局完再挪
        on.post(() -> on.requestRectangleOnScreen(new android.graphics.Rect(0, 0, on.getWidth(), on.getHeight()), false));
    }

    private static int tabIndex(String id) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(id)) return i;
        return 0;   // 没存过 / 认不出（以后删了某一页）：通用
    }

    /** 加删一行、换了连接方式之类：等这次点击处理完整页重建（停在原来那一页、滚动位置尽量不动）。 */
    private void rebuildLater() {
        scroll.post(this::build);
    }

    /** 每一页从上到下有哪些节；六页都建，只显示 {@link #tab} 那一页。 */
    private void build() {
        pagesBox.removeAllViews();
        // 声音那一节引用的 view：下面重建；外观那一节的主题刷新先于声音那一节建好，先置空免得去画上一回的
        benchTheme = null;
        soundCands = null;
        soundDiag = null;
        pages = new LinearLayout[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pagesBox.addView(pages[i], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        GestureEngine.Config def = new GestureEngine.Config();

        // 通用：豆花儿服务、连接方式（含蓝牙设备）、恢复默认、版本号
        list = pages[0];
        service.build(list);
        header(R.string.sec_connection);
        connection();
        header(0);
        TextView reset = text(getString(R.string.reset_all), 16, 0xFFE07A5F);
        reset.setPadding(0, dp(12), 0, dp(12));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.reset, (d, w) -> {
                    prefs.resetAll();
                    padBg.reset();   // 背景图的文件一起删（Prefs 清了以后它就是没人引用的垃圾）
                    applySound();   // 声音回到"关"：管道马上关掉
                    updateBtUse();   // 恢复默认就是桌面模式：蓝牙键鼠立刻停
                    build();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        list.addView(reset);
        licenses();
        version();

        // 触控板：指针、轻点、滚动、三指四指手势、角上的小按钮、小红点和三键条
        list = pages[1];
        header(R.string.sec_pointer);
        slider(R.string.pointer_speed, "speed", def.pointerSpeed, 0.5f, 6f, 0.1f, "%.1f");
        slider(R.string.acceleration, "accel", def.acceleration, 0f, 3f, 0.1f, "%.1f");
        list.addView(text(getString(R.string.precision), 16, 0xFFE8E8E8));
        levels(Prefs.HAPTIC_LABELS, prefs.getInt("precision", 2), level -> prefs.putInt("precision", level));

        header(R.string.sec_tap);
        toggle(R.string.tap_drag, "tap_drag", def.tapToDrag);
        sliderInt(R.string.drag_window, "drag_ms", def.dragWindowMs, 120, 400, 10);
        toggle(R.string.long_press, "long_press", def.longPressDrag);
        sliderInt(R.string.long_press_ms, "long_press_ms", def.longPressMs, 250, 1000, 50);

        header(R.string.sec_scroll);
        scrollSpeed(def.scrollStepDp);
        toggle(R.string.natural_scroll, "natural", def.naturalScroll);
        toggle(R.string.pinch_zoom, "pinch", def.pinchZoom);
        toggle(R.string.edge_v, "edge", def.edgeScroll);
        toggle(R.string.edge_h, "edge_h", def.edgeScrollHorizontal);
        sliderInt(R.string.edge_width, "edge_width_i", Math.round(def.edgeWidthDp), 12, 64, 2);
        inertia(def);
        smoothScroll();
        toggle(R.string.ticks, "scroll_ticks", prefs.scrollTicks());
        list.addView(text(getString(R.string.tick_strength), 16, 0xFFE8E8E8));
        levels(Prefs.TICK_STRENGTH_LABELS, prefs.tickStrength(), strength -> {
            prefs.setTickStrength(strength);
            Haptics h = new Haptics(this);   // 选了就连震几下，像滚了几格
            h.setLevel(prefs.haptic());
            h.setTickStrength(strength);
            for (int i = 0; i < 4; i++) scroll.postDelayed(() -> h.play(GestureEngine.HAPTIC_TICK), i * 70L);
        });
        if (Haptics.tuning(this)) spinTuning();

        header(R.string.sec_gestures);
        for (int i = 0; i < Prefs.GESTURE_KEYS.length; i++) {
            final int index = i;
            actionRow(getString(Prefs.GESTURE_LABELS[i]), () -> prefs.gesture(index), id -> prefs.setGesture(index, id));
        }

        header(R.string.sec_corners);
        for (int index : Prefs.CORNER_ORDER) {
            actionRow(getString(Prefs.CORNER_LABELS[index]), () -> prefs.corner(index), id -> prefs.setCorner(index, id));
        }

        trackpoint(def);

        // 键盘：用哪块键盘、实体键盘是哪一块，豆花布局的快捷行（从"按钮与按键"挪过来）
        list = pages[2];
        keyboardSection();
        header(R.string.sec_kbd_douhua);
        list.addView(text(getString(R.string.sec_kb_row), 14, 0xFF9AA0A6));
        for (int i = 0; i < Prefs.KEYBOARD_ROW_COUNT; i++) {
            final int index = i;
            actionRow(getString(R.string.nth, i + 1), () -> prefs.keyboardRow(index), id -> prefs.setKeyboardRow(index, id));
        }
        // 贴字面板的"打出来"（2026-11-02）：速度固定、可调（键 / 秒），说明里写按哪个布局找键、为什么匀速；回到主界面下次点"打出来"时用
        header(R.string.sec_typeout);
        sliderInt(R.string.typeout_rate, Prefs.TYPEOUT_RATE, TypeOutSender.RATE_DEFAULT, TypeOutSender.RATE_MIN, TypeOutSender.RATE_MAX,
                TypeOutSender.RATE_STEP, " " + getString(R.string.typeout_rate_unit));
        list.addView(text(getString(R.string.typeout_rate_hint), 14, 0xFF9AA0A6));

        // 按钮与按键：自定义按键（左边一列）、音量键
        list = pages[3];
        header(R.string.sec_strip);
        customKeys();
        hardKeysSection();

        // 空中鼠标
        list = pages[4];
        airSection();

        // 手柄：预设（网格 + 自定义）、摇杆、十字键、面键的手感，Moonlight 里怎么设
        list = pages[5];
        gamepadSection();

        // 外观：主题、按键边框……，屏幕边缘留白，触控板背景图，震动
        list = pages[6];
        appearance();
        edgeMargin();
        header(R.string.sec_pad_bg);
        padBg.build(list);
        header(R.string.sec_haptics);
        hapticChoice();
        list.addView(text(getString(R.string.drag_hum), 16, 0xFFE8E8E8));
        levels(Prefs.HAPTIC_LABELS, prefs.dragHum(), level -> {
            prefs.setDragHum(level);
            Haptics h = new Haptics(this);   // 选了就试震 0.6 秒
            h.setLevel(prefs.haptic());
            h.setDragHum(level);
            h.startHum();
            scroll.postDelayed(h::stopHum, 600);
        });
        toggle(R.string.texture, "texture", def.texture);
        soundSection();

        showTab();
    }

    /**
     * "自定义按键"（主界面左边一列，原来的左侧按钮条）：最上面一个开关（默认关），开着时下面列出每个按钮（可删、可加，上限照旧）。
     * 2026-11-01 起条上没有写死的"退出 / 设置 / 收起"（都进了触控板顶上的 ⋯ 菜单），开关只在这里。关着时列表藏起来、配置留着。
     */
    private void customKeys() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Switch s = new Switch(this);
        s.setText(R.string.custom_keys);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.customKeys());
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.putBool(Prefs.CUSTOM_KEYS, on);
            box.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        list.addView(s);
        LinearLayout outer = list;
        list = box;
        int slots = prefs.slotCount();
        for (int i = 0; i < slots; i++) {
            final int index = i;
            LinearLayout row = actionRow(getString(R.string.nth, i + 1), () -> prefs.slot(index), id -> prefs.setSlot(index, id));
            TextView remove = text("✕", 18, 0xFF9A9A9A);
            remove.setPadding(dp(16), 0, dp(4), 0);
            remove.setOnClickListener(v -> {
                prefs.removeSlot(index);
                rebuildLater();
            });
            row.addView(remove);
        }
        if (slots < Prefs.MAX_SLOTS) {
            TextView add = text(getString(R.string.strip_add, Prefs.MAX_SLOTS), 16, 0xFF8AB4F8);
            add.setPadding(0, dp(12), 0, dp(12));
            add.setOnClickListener(v -> {
                prefs.addSlot("none");   // 加一行"无"，再点它选动作
                rebuildLater();
            });
            list.addView(add);
        }
        list = outer;
        list.addView(box);
        box.setVisibility(s.isChecked() ? View.VISIBLE : View.GONE);
    }

    // ═══════════ 键盘 ═══════════

    /**
     * "键盘"页上面两节：用哪块键盘（豆花布局 / 实体键盘，同 ⋯ 菜单里的"键盘"那一项），实体键盘是哪一块。
     * 实体键盘一块 = 语言（键帽上画哪套字）× 形状（ANSI / ISO / ABNT2，德语只有 ISO、巴西只有 ABNT2）× 尺寸（笔记本 / TKL / 全尺寸，逐级缩的起点）× 模式（Windows / Mac）；
     * 模式是用户 2026-11-01 定的（"第三方键盘都可以调一个 Windows 模式、一个 Mac 模式，Mac 模式跟 iPad 就是通用的"），不按主机认、不问主机是什么系统。
     * 改了回到键盘页才生效（MainActivity.applyBoards）。换语言、形状要重画选项（德语只有 ISO），整页重建。
     * 第 2 批（2026-11-01）：语言 34 个，点开是一个按名字排的列表（每项下面一行小字写它在 Mac / Windows 里叫什么，{@link #langDialog}）；
     * 某布局在某种模式下没有键帽数据（拉美没有 Mac 的；意大利 QZERTY、瑞典旧版只有 Mac 的）：选着那种模式时列表里这个布局灰掉、写原因，选着这个布局时"模式"里那种模式灰掉
     * （Boards.langChoices / modeAvailable），不会选出一块没字的键盘；存着的组合要是正好没数据，键盘页照旧退回豆花布局，这里标红一行写原因。
     * 第 3 批（2026-11-01）：尺寸六种（苹果紧凑 / 笔记本 / 65% / 75% / TKL / 全尺寸，从小到大，点开是列表），苹果紧凑只有 Mac 模式、只有 ANSI / ISO
     * （Boards.sizeAvailable）：选着 Windows 模式 / ABNT2 形状时尺寸里的苹果紧凑灰掉，选着苹果紧凑时模式里的 Windows、形状里的 ABNT2、
     * 语言列表里只有 ABNT2 的布局（巴西）灰掉，都写原因；存着的组合正好配不上时同"没数据的模式"，标红一行。
     */
    private void keyboardSection() {
        header(R.string.sec_kbd_board);
        String[] boardNames = {getString(R.string.kbd_board_douhua), getString(R.string.kbd_board_fixed)};
        choiceRow(getString(R.string.kbd_board), boardNames, boards.fixedSelected() ? 1 : 0, i -> boards.select(i == 1));
        list.addView(text(getString(R.string.kbd_board_note), 13, 0xFF9AA0A6));

        header(R.string.sec_kbd_fixed);
        List<FixedLegends.Layout> catalog = FixedData.catalog(this);
        if (catalog.isEmpty()) {   // 照理不会：数据是打进 APK 的
            list.addView(text(getString(R.string.kbd_no_data), 14, 0xFFE07A5F));
            return;
        }
        Boards.Spec spec = boards.spec();
        langRow(spec);
        FixedLegends.Layout lang = FixedLegends.find(catalog, spec.lang);
        if (lang != null) {
            // 两种都有 / 只有 Windows（拉美）/ 只有 Mac（意大利 QZERTY、瑞典旧版：Windows 没有这一套）
            String hosts = !lang.hasMode(FixedLayout.WIN) ? getString(R.string.kbd_host_names_mac, lang.nameMac)
                    : lang.hasMode(FixedLayout.MAC) ? getString(R.string.kbd_host_names, lang.nameMac, lang.nameWin)
                    : getString(R.string.kbd_host_names_win, lang.nameWin);
            list.addView(text(hosts, 13, 0xFF9AA0A6));
            List<String> shapes = lang.shapes;
            String[] shapeNames = new String[shapes.size()];
            boolean[] shapeOn = new boolean[shapes.size()];
            int sc = 0;
            for (int i = 0; i < shapeNames.length; i++) {
                String sh = shapes.get(i);
                shapeNames[i] = getString(FixedLayout.ISO.equals(sh) ? R.string.kbd_shape_iso
                        : FixedLayout.ABNT2.equals(sh) ? R.string.kbd_shape_abnt2 : R.string.kbd_shape_ansi);
                // 苹果紧凑选着时 ABNT2 灰掉（按 Mac 模式问：模式那一边另由"模式"那一行灰掉）
                shapeOn[i] = Boards.sizeAvailable(spec.size, sh, FixedLayout.MAC);
                if (!shapeOn[i]) shapeNames[i] = getString(R.string.kbd_apple_unavailable, shapeNames[i]);
                if (sh.equals(spec.shape)) sc = i;
            }
            choiceRow(getString(R.string.kbd_shape), shapeNames, allOn(shapeOn) ? null : shapeOn, sc, i -> {
                boards.setShape(shapes.get(i));
                rebuildLater();   // 尺寸后面写的键数跟着变
            });
        }
        // 从小到大：苹果紧凑 / 笔记本（照 ThinkPad）/ 65% / 75% / TKL / 全尺寸（FixedLayout.ALL_SIZES），键数照当前的形状、模式现算
        // （灰掉的苹果紧凑照 Mac 模式、ANSI / ISO 算）。选的是逐级缩的起点（窗口放不下就往小换，见 kbd_size_note）
        String[] sizes = FixedLayout.ALL_SIZES;
        String[] sizeNames = new String[sizes.length];
        boolean[] sizeOn = new boolean[sizes.length];
        int sizeAt = 0;
        for (int i = 0; i < sizes.length; i++) {
            int keys = FixedLayout.build(spec.shape, sizes[i], spec.mode, false, ".").keys.size();
            sizeNames[i] = getString(sizeLabel(sizes[i]), keys);
            sizeOn[i] = Boards.sizeAvailable(sizes[i], spec.shape, spec.mode);
            if (!sizeOn[i]) sizeNames[i] = getString(R.string.kbd_size_apple_only, sizeNames[i]);
            if (sizes[i].equals(spec.size)) sizeAt = i;
        }
        choiceRow(getString(R.string.kbd_size), sizeNames, allOn(sizeOn) ? null : sizeOn, sizeAt, i -> {
            boards.setSize(sizes[i]);
            rebuildLater();   // 选着苹果紧凑时模式里的 Windows、形状里的 ABNT2、语言列表里的巴西跟着灰掉
        });
        list.addView(text(getString(R.string.kbd_size_note), 13, 0xFF9AA0A6));
        // 模式：这个布局没有数据的那种灰掉、写原因（拉美没有 Mac 的，意大利 QZERTY、瑞典旧版没有 Windows 的）；选着苹果紧凑时 Windows 也灰掉
        String[] modes = {FixedLayout.WIN, FixedLayout.MAC};
        int[] modeLabels = {R.string.kbd_mode_win, R.string.kbd_mode_mac};
        String[] modeNames = new String[modes.length];
        boolean[] modeOn = new boolean[modes.length];
        for (int i = 0; i < modes.length; i++) {
            boolean data = Boards.modeAvailable(catalog, spec.lang, modes[i]);
            // 按 ANSI 问：形状那一边另由"形状"那一行灰掉
            boolean fits = Boards.sizeAvailable(spec.size, FixedLayout.ANSI, modes[i]);
            modeOn[i] = data && fits;
            modeNames[i] = !data ? getString(R.string.kbd_mode_unavailable, getString(modeLabels[i]))
                    : !fits ? getString(R.string.kbd_apple_unavailable, getString(modeLabels[i])) : getString(modeLabels[i]);
        }
        choiceRow(getString(R.string.kbd_mode), modeNames, modeOn, FixedLayout.MAC.equals(spec.mode) ? 1 : 0, i -> {
            boards.setMode(modes[i]);
            rebuildLater();   // 键数（Mac 没有 Ins）、语言列表里灰掉哪些跟着变
        });
        list.addView(text(getString(R.string.kbd_mode_hint), 13, 0xFF9AA0A6));
        if (!boards.modeAvailable()) {
            // 存着的组合没有数据（设置页选不出来，以后清单变了之类）：键盘页退回豆花布局，这里写明原因
            list.addView(text(getString(R.string.kbd_no_mode_data, FixedData.langName(this, spec.lang),
                    getString(FixedLayout.MAC.equals(spec.mode) ? R.string.kbd_mode_mac : R.string.kbd_mode_win)), 14, 0xFFE07A5F));
        } else if (!boards.sizeAvailable()) {
            // 存着的组合尺寸配不上（苹果紧凑 × Windows / ABNT2；设置页选不出来，比如换成只有 ABNT2 的布局之前就选着苹果紧凑——那一项已灰掉）：同上
            list.addView(text(getString(R.string.kbd_no_size_data), 14, 0xFFE07A5F));
        } else if (FixedData.table(this, spec.lang, spec.mode) == null) {   // 照理不会；读不出来时键盘页照样显示豆花布局（FixedLayout.forEntry）
            list.addView(text(getString(R.string.kbd_no_data), 14, 0xFFE07A5F));
        }
        TextView note = text(getString(R.string.kbd_host_note), 13, 0xFF9AA0A6);
        note.setPadding(0, dp(8), 0, 0);
        list.addView(note);
        if (!prefs.btMode()) {
            // 桌面模式：主机就是这台手机，系统设置的实体键盘里给"Douhua Keyboard"选同一种布局（公开的 ACTION_HARD_KEYBOARD_SETTINGS；三星那一页在哪没核）
            TextView open = text(getString(R.string.kbd_open_hw), 15, 0xFF8AB4F8);
            open.setPadding(0, dp(12), 0, dp(12));
            open.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_HARD_KEYBOARD_SETTINGS));
                } catch (android.content.ActivityNotFoundException e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS));
                }
            });
            list.addView(open);
        }
        fkeySection(spec);
    }

    // ═══════════ F1–F12 的第二功能（协议 24） ═══════════

    /**
     * 实体键盘那一节下面的"F1–F12 的第二功能"（2026-11-02，方案 design-fkeys §2.1，用户照推荐定的）。显示现在这种模式（Windows / Mac）的那一套
     * （两种模式各存一套，Boards.fkey* / frowFlip，键名都是 kbx_ 开头，不碰豆花布局的 kb_）：
     * - 预设：ThinkPad 风格 / 苹果风格 / 不用（只出 F1–F12），逐个改过、哪个都对不上时多一项灰着的"自定义"（Boards.fkeyPreset 按 12 个现算）；
     * - 平时按出：F1–F12 / 第二功能——和键盘上的 Fn+Esc 是同一份状态（Boards.frowFlip，Fn+Esc 翻了 App 照分流报的存回来）；
     * - F1–F12 各一行，点开是分组的列表（FKeyChoices：真键盘能发的功能、动作清单里的键盘快捷键、自定义组合键）。
     * 选"不用"时后两样藏起来（FKeyChoices.keysShown）。65% 没有 F 行那句一直写；蓝牙模式下写系统键要重新配对一次（同"连续滚动"那条）。
     * 改了回到键盘页才生效：MainActivity.applyPrefs 把翻转发给服务和本地分流，applyBoards 比得出 Spec 里的 12 个功能变了、F 键的 id 重拼。
     */
    private void fkeySection(Boards.Spec spec) {
        String mode = spec.mode;
        boolean mac = FixedLayout.MAC.equals(mode);
        header(R.string.sec_kbd_fkeys);
        list.addView(text(getString(R.string.kbd_fkeys_mode, getString(mac ? R.string.kbd_mode_mac : R.string.kbd_mode_win)), 13, 0xFF9AA0A6));
        String preset = boards.fkeyPreset(mode);
        String[] presets = FKeyChoices.presetChoices(preset);
        String[] presetNames = new String[presets.length];
        boolean[] presetOn = new boolean[presets.length];
        int presetAt = 0;
        for (int i = 0; i < presets.length; i++) {
            presetNames[i] = getString(presetName(presets[i]));
            presetOn[i] = !FKeyFunctions.PRESET_CUSTOM.equals(presets[i]);   // "自定义"只是写现在的样子，点不了
            if (presets[i].equals(preset)) presetAt = i;
        }
        choiceRow(getString(R.string.kbd_fkeys_preset), presetNames, allOn(presetOn) ? null : presetOn, presetAt, i -> {
            if (!presetOn[i]) return;
            boards.applyFkeyPreset(mode, presets[i]);
            rebuildLater();   // 12 行、"平时按出"显示不显示跟着变
        });
        if (FKeyChoices.keysShown(preset)) {
            String[] faces = {getString(R.string.kbd_fkeys_normal_f), getString(R.string.kbd_fkeys_normal_second)};
            choiceRow(getString(R.string.kbd_fkeys_normal), faces, boards.frowFlip(mode) ? 1 : 0, i -> boards.setFrowFlip(mode, i == 1));
            list.addView(text(getString(R.string.kbd_fkeys_hint), 13, 0xFF9AA0A6));
            List<FKeyChoices.Item> items = FKeyChoices.items(mode, fkeyShortcuts(mac));
            for (int n = 1; n <= 12; n++) fkeyRow(n, mode, items);
        }
        list.addView(text(getString(R.string.kbd_fkeys_65), 13, 0xFF9AA0A6));
        if (prefs.btMode()) {
            TextView bt = text(getString(R.string.kbd_fkeys_bt), 13, 0xFFE0A33E);
            bt.setPadding(0, dp(6), 0, 0);
            list.addView(bt);
        }
    }

    /** 预设的名字（FKeyFunctions.PRESET_*）。 */
    private static int presetName(String preset) {
        switch (preset) {
            case FKeyFunctions.PRESET_THINKPAD:
                return R.string.kbd_fkeys_preset_thinkpad;
            case FKeyFunctions.PRESET_APPLE:
                return R.string.kbd_fkeys_preset_apple;
            case FKeyFunctions.PRESET_NONE:
                return R.string.kbd_fkeys_preset_none;
            default:
                return R.string.kbd_fkeys_preset_custom;
        }
    }

    /**
     * 动作清单里的键盘快捷键（Actions 里发 HID 的那些：复制、Alt+Tab、浏览器后退……），按清单的顺序，码是这种模式下按的那个
     * （Mac 模式照动作清单连苹果设备时的发法：⌘C 而不是 Ctrl+C）。豆花自己的动作、鼠标、靠注入安卓按键的不是键盘发的，不取。
     */
    private static List<FKeyChoices.Shortcut> fkeyShortcuts(boolean mac) {
        List<FKeyChoices.Shortcut> out = new java.util.ArrayList<>();
        for (Actions.Action a : Actions.all()) {
            if (a.kind == Actions.Kind.HID) out.add(new FKeyChoices.Shortcut(a.id, a.hidCode(mac)));
        }
        return out;
    }

    /** "F1"……那一行：现在配的写在右边（同 {@link #choiceRow} 的样子），点一下弹 {@link #fkeyDialog}。 */
    private void fkeyRow(int n, String mode, List<FKeyChoices.Item> items) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        String label = "F" + n;
        TextView name = text(label, 16, 0xFFE8E8E8);
        TextView value = text(fkeyLabel(FKeyChoices.describe(items, boards.fkey(mode, n), mode), mode), 15, 0xFF8AB4F8);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
        row.setOnClickListener(v -> fkeyDialog(label, n, mode, items));
        list.addView(row);
    }

    /**
     * 一个 F 键的选择列表（八十来项，分组）：分组标题灰字、点不了；现在配的那项蓝字加粗，打开时滚到它附近（同 {@link #langDialog} 的样子）。
     * 选了就存（Boards.setFkey）、整页重建（"预设"那一行可能变成自定义 / 认回某个预设）；"自定义组合键…"另弹 {@link #fkeyCombo}。
     */
    private void fkeyDialog(String label, int n, String mode, List<FKeyChoices.Item> items) {
        int cur = FKeyChoices.selected(items, boards.fkey(mode, n), mode);
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return items.get(position).selectable();
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                FKeyChoices.Item it = items.get(position);
                TextView t;
                if (!it.selectable()) {
                    t = text(fkeyLabel(it, mode), 13, 0xFF9AA0A6);
                    t.setPadding(dp(24), dp(16), dp(24), dp(4));
                } else {
                    boolean current = position == cur;
                    t = text(fkeyLabel(it, mode), 16, current ? 0xFF8AB4F8 : 0xFFE8E8E8);
                    if (current) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    t.setPadding(dp(24), dp(10), dp(24), dp(10));
                }
                t.setEnabled(it.selectable());
                return t;
            }
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(label)
                .setAdapter(adapter, (dlg, which) -> {
                    FKeyChoices.Item it = items.get(which);
                    if (!it.selectable()) return;   // ListView 本来就不派分组标题，保险
                    if (it.type == FKeyChoices.CUSTOM) {
                        fkeyCombo(label, n, mode, items);
                        return;
                    }
                    boards.setFkey(mode, n, it.id);
                    rebuildLater();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.show();
        if (cur >= 0) d.getListView().setSelection(Math.max(0, cur - 2));
    }

    /**
     * F 键的"自定义组合键…"：同动作清单的那个对话框（勾修饰键、挑一个键），不要按钮名字（键帽上写组合本身）；Mac 模式修饰键写 ⌃ ⇧ ⌥ ⌘。
     * 现在配的是自定义组合键 / 快捷键时照它预先勾好。拼出来的码正好是某个有名字功能的就存那个名字（FKeyChoices.canonical）。
     */
    private void fkeyCombo(String label, int n, String mode, List<FKeyChoices.Item> items) {
        boolean mac = FixedLayout.MAC.equals(mode);
        String[] mods = mac ? new String[]{"⌃ Ctrl", "⇧ Shift", "⌥ Opt", "⌘ Cmd"} : new String[]{"Ctrl", "Shift", "Alt", "Super"};
        FKeyChoices.Item now = FKeyChoices.describe(items, boards.fkey(mode, n), mode);
        comboDialog(getString(R.string.custom_combo_title, label), mods, false, now.code, (key, text) -> {
            boards.setFkey(mode, n, FKeyChoices.canonical(items, key));
            rebuildLater();
        });
    }

    /** 列表的一项 / 行上的那一项写什么：分组标题、功能名字（键盘键那一类后面接"· 组合"）、快捷键"名字 · 组合"、自定义组合键写组合本身。 */
    private String fkeyLabel(FKeyChoices.Item it, String mode) {
        switch (it.type) {
            case FKeyChoices.HEADER:
                return getString(fkeyGroupName(it.group));
            case FKeyChoices.SHORTCUT: {
                String name = Actions.get(it.actionId).label(this);
                String combo = FKeyFaces.comboLabel(it.code, mode);
                return name.equals(combo) ? name : name + " · " + combo;
            }
            case FKeyChoices.CUSTOM:
                return it.id == null ? getString(R.string.custom_combo) : FKeyFaces.comboLabel(it.code, mode);
            default: {
                FKeyFunctions.Function f = it.function;
                String name = fkeyName(f.id);
                if (f.kind != FKeyFunctions.KEY || f.group == FKeyFunctions.GROUP_FKEYS) return name;
                return name + " · " + FKeyFaces.comboLabel(f.code(mode) & 0xFFFF, mode);
            }
        }
    }

    /** 分组标题（FKeyFunctions.GROUP_* 和 FKeyChoices.GROUP_SHORTCUTS）。 */
    private static int fkeyGroupName(int group) {
        switch (group) {
            case FKeyFunctions.GROUP_SOUND:
                return R.string.fkg_sound;
            case FKeyFunctions.GROUP_MEDIA:
                return R.string.fkg_media;
            case FKeyFunctions.GROUP_SCREEN:
                return R.string.fkg_screen;
            case FKeyFunctions.GROUP_DESKTOP:
                return R.string.fkg_desktop;
            case FKeyFunctions.GROUP_OPEN:
                return R.string.fkg_open;
            case FKeyFunctions.GROUP_INPUT:
                return R.string.fkg_input;
            case FKeyFunctions.GROUP_SYSTEM:
                return R.string.fkg_system;
            case FKeyFunctions.GROUP_FKEYS:
                return R.string.fkg_fkeys;
            case FKeyFunctions.GROUP_COMBO:
                return R.string.fkg_combo;
            default:
                return R.string.fkg_shortcuts;
        }
    }

    /**
     * 有名字功能的名字（九种语言，strings.xml 的 fkf_<id>）；F13–F24 写键名（不翻译），"无"同动作清单的"无"。
     * FKeyChoicesTest 照源码核对：FKeyFunctions 里每个有名字的功能这里都有一个 R.string.fkf_<id>、九个 strings.xml 都有那一条。
     */
    private String fkeyName(String id) {
        if (id.matches("f\\d+")) return "F" + id.substring(1);
        switch (id) {
            case "none": return getString(R.string.act_none);
            case "mute": return getString(R.string.fkf_mute);
            case "vol_down": return getString(R.string.fkf_vol_down);
            case "vol_up": return getString(R.string.fkf_vol_up);
            case "mic_mute": return getString(R.string.fkf_mic_mute);
            case "play_pause": return getString(R.string.fkf_play_pause);
            case "prev_track": return getString(R.string.fkf_prev_track);
            case "next_track": return getString(R.string.fkf_next_track);
            case "stop": return getString(R.string.fkf_stop);
            case "rewind": return getString(R.string.fkf_rewind);
            case "fast_forward": return getString(R.string.fkf_fast_forward);
            case "bright_down": return getString(R.string.fkf_bright_down);
            case "bright_up": return getString(R.string.fkf_bright_up);
            case "show_windows": return getString(R.string.fkf_show_windows);
            case "show_apps": return getString(R.string.fkf_show_apps);
            case "lock": return getString(R.string.fkf_lock);
            case "search": return getString(R.string.fkf_search);
            case "calculator": return getString(R.string.fkf_calculator);
            case "mail": return getString(R.string.fkf_mail);
            case "browser_home": return getString(R.string.fkf_browser_home);
            case "my_computer": return getString(R.string.fkf_my_computer);
            case "settings": return getString(R.string.fkf_settings);
            case "next_layout": return getString(R.string.fkf_next_layout);
            case "dictation": return getString(R.string.fkf_dictation);
            case "emoji": return getString(R.string.fkf_emoji);
            case "sleep": return getString(R.string.fkf_sleep);
            case "dnd": return getString(R.string.fkf_dnd);
            case "prtsc": return getString(R.string.fkf_prtsc);
            case "display": return getString(R.string.fkf_display);
            case "notifications": return getString(R.string.fkf_notifications);
            case "snip": return getString(R.string.fkf_snip);
            case "copilot": return getString(R.string.fkf_copilot);
            default: return id;   // 照理不会（FKeyChoicesTest 锁住）；写 id 不闪退
        }
    }

    /** 都能选（没有要灰掉的项时 choiceRow 照旧用系统的单选列表，样子和以前一样）。 */
    private static boolean allOn(boolean[] on) {
        for (boolean b : on) if (!b) return false;
        return true;
    }

    /** 尺寸那一项的字（带 %1$d 键数）：FixedLayout.ALL_SIZES 里的每一种。 */
    private static int sizeLabel(String size) {
        switch (size) {
            case FixedLayout.APPLE:
                return R.string.kbd_size_apple;
            case FixedLayout.LAPTOP:
                return R.string.kbd_size_laptop;
            case FixedLayout.P65:
                return R.string.kbd_size_65;
            case FixedLayout.P75:
                return R.string.kbd_size_75;
            case FixedLayout.FULL:
                return R.string.kbd_size_full;
            default:
                return R.string.kbd_size_tkl;
        }
    }

    /** "布局"那一行：名字 + 现在选的写在右边（同 {@link #choiceRow} 的样子），点一下弹 {@link #langDialog}。 */
    private void langRow(Boards.Spec spec) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView name = text(getString(R.string.kbd_lang), 16, 0xFFE8E8E8);
        TextView value = text(FixedData.langName(this, spec.lang), 15, 0xFF8AB4F8);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        row.setOnClickListener(v -> langDialog(spec));
        list.addView(row);
    }

    /**
     * 选语言的列表（三十多项，单选框列不下、看不清）：按界面语言的名字排（Boards.langChoices），每项两行——名字、下面一行小字写它在 Mac / Windows 里叫什么
     * （清单的 name_mac 是 macOS 现名、name_win 是 Windows 里的名字），现在选的那项蓝字；现在的模式下没有数据的灰掉、点不了，第三行写原因。
     * 打开时滚到现在选的那项。
     */
    private void langDialog(Boards.Spec spec) {
        List<Boards.LangChoice> choices = FixedData.langChoices(this, spec.mode, spec.size);
        String modeName = getString(FixedLayout.MAC.equals(spec.mode) ? R.string.kbd_mode_mac : R.string.kbd_mode_win);
        int cur = 0;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i).layout.id.equals(spec.lang)) cur = i;
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return choices.size();
            }

            @Override
            public Object getItem(int position) {
                return choices.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return choices.get(position).enabled;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                Boards.LangChoice c = choices.get(position);
                FixedLegends.Layout l = c.layout;
                boolean current = l.id.equals(spec.lang);
                LinearLayout box = new LinearLayout(SettingsActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(24), dp(10), dp(24), dp(10));
                TextView n = text(c.name, 16, !c.enabled ? 0xFF6E7378 : current ? 0xFF8AB4F8 : 0xFFE8E8E8);
                if (current) n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                box.addView(n);
                String hosts = !l.hasMode(FixedLayout.WIN) ? getString(R.string.kbd_lang_hosts_mac, l.nameMac)
                        : l.hasMode(FixedLayout.MAC) ? getString(R.string.kbd_lang_hosts, l.nameMac, l.nameWin)
                        : getString(R.string.kbd_lang_hosts_win, l.nameWin);
                box.addView(text(hosts, 12, c.enabled ? 0xFF9AA0A6 : 0xFF6E7378));
                if (!c.enabled) {
                    box.addView(text(c.modeOk ? getString(R.string.kbd_lang_no_apple) : getString(R.string.kbd_lang_no_mode, modeName), 12, 0xFFE0A33E));
                }
                box.setEnabled(c.enabled);
                return box;
            }
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.kbd_lang)
                .setAdapter(adapter, (dlg, which) -> {
                    Boards.LangChoice c = choices.get(which);
                    if (!c.enabled) return;   // ListView 本来就不派灰掉的项，保险
                    boards.setLang(c.layout.id);
                    rebuildLater();   // 能选的形状、主机里的名字、哪种模式灰掉跟着变
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.show();
        d.getListView().setSelection(Math.max(0, cur - 2));
    }

    // ═══════════ 开源许可 ═══════════

    /**
     * "通用"页最下面（版本号上面）一行"开源许可"：点开是 res/raw/licenses.txt 的全文（实体键盘键帽数据的三家：Unicode CLDR、AOSP、xkeyboard-config，
     * 和 adb 配对移植的 BoringSSL）。这几家都要求随软件附上版权和许可声明（方案 §6.3），发正式包前必须有。
     */
    private void licenses() {
        TextView row = text(getString(R.string.licenses), 16, 0xFF8AB4F8);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setOnClickListener(v -> {
            String body = FixedData.text(this, R.raw.licenses);
            TextView t = text(body == null ? "" : body, 12, 0xFFE8E8E8);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setTextIsSelectable(true);
            t.setPadding(dp(20), dp(12), dp(20), dp(12));
            ScrollView sv = new ScrollView(this);
            sv.addView(t);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.licenses)
                    .setView(sv)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
        list.addView(row);
    }

    // ═══════════ 版本号（彩蛋） ═══════════

    /** 连点几下版本号算"啪啪啪"：每下离上一下不超过这么久。 */
    private static final long EGG_TAP_GAP_MS = 600;
    private static final int EGG_TAPS = 5;
    private int eggTaps;
    private long lastEggTap;

    /** "通用"页最下面一行"豆花儿 0.1.1"。快速连点 5 下蹦出一只睡着的小鸟。 */
    private void version() {
        String v;
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            v = "";
        }
        TextView t = text(getString(R.string.app_name) + " " + v, 13, 0xFF5F6368);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(24), 0, dp(8));
        t.setOnClickListener(view -> {
            long now = android.os.SystemClock.uptimeMillis();
            eggTaps = now - lastEggTap <= EGG_TAP_GAP_MS ? eggTaps + 1 : 1;
            lastEggTap = now;
            if (eggTaps >= EGG_TAPS) {
                eggTaps = 0;
                showEgg();
            }
        });
        list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 小鸟从中间弹出来（带一点回弹），震一下；点哪都收起。 */
    private void showEgg() {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setBackgroundColor(0xCC000000);
        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setImageResource(R.drawable.egg);
        img.setAdjustViewBounds(true);
        int side = Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        box.addView(img, new android.widget.FrameLayout.LayoutParams(side * 9 / 10, side * 9 / 10, Gravity.CENTER));
        box.setOnClickListener(v -> d.dismiss());
        d.setContentView(box);
        d.show();
        img.setScaleX(0.2f);
        img.setScaleY(0.2f);
        img.setAlpha(0f);
        img.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        Haptics h = new Haptics(this);
        h.setLevel(prefs.haptic());
        h.play(GestureEngine.HAPTIC_GESTURE);
    }

    // ═══════════ 各种行 ═══════════

    /** titleRes = 0：只留一段空白。 */
    private void header(int titleRes) {
        TextView t = text(titleRes == 0 ? "" : getString(titleRes), 13, 0xFF9AA0A6);
        t.setPadding(0, dp(24), 0, dp(6));
        list.addView(t);
    }

    private void slider(int labelRes, String key, float def, float min, float max, float step, String fmt) {
        slider(labelRes, key, def, min, max, step, fmt, null);
    }

    /** 同上，拖动时存完再调 changed（null = 不调；别的行里现算的说明跟着变）。 */
    private void slider(int labelRes, String key, float def, float min, float max, float step, String fmt, Runnable changed) {
        String label = getString(labelRes);
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        int steps = Math.round((max - min) / step);
        bar.setMax(steps);
        float value = prefs.getFloat(key, def);
        bar.setProgress(Math.round((value - min) / step));
        title.setText(getString(R.string.value_format, label, String.format(Locale.ROOT, fmt, value)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            float v = min + p * step;
            prefs.putFloat(key, v);
            title.setText(getString(R.string.value_format, label, String.format(Locale.ROOT, fmt, v)));
            if (changed != null) changed.run();
        }));
        list.addView(title);
        list.addView(bar);
    }

    private void sliderInt(int labelRes, String key, int def, int min, int max, int step) {
        sliderInt(labelRes, key, def, min, max, step, "");
    }

    /** 同上，数后面接 unit（"%" 之类；空 = 不接）。存坏了的值（超出范围）显示时截到范围里。 */
    private void sliderInt(int labelRes, String key, int def, int min, int max, int step, String unit) {
        sliderInt(labelRes, key, def, min, max, step, unit, null);
    }

    /** 同上，拖动时存完再调 changed（null = 不调）。返回 {标题, 滑块}（要灰掉这一行时用）。 */
    private View[] sliderInt(int labelRes, String key, int def, int min, int max, int step, String unit, Runnable changed) {
        String label = getString(labelRes);
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        int value = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        bar.setProgress((value - min) / step);
        title.setText(getString(R.string.value_format, label, value + unit));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int v = min + p * step;
            prefs.putInt(key, v);
            title.setText(getString(R.string.value_format, label, v + unit));
            if (changed != null) changed.run();
        }));
        list.addView(title);
        list.addView(bar);
        return new View[]{title, bar};
    }

    /** 滚动速度 1–10 档，换算成"滚一格要划多少 dp"（档位越高，划得越少就滚一格）。 */
    private void scrollSpeed(float defStep) {
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax(9);
        int level = Math.round(100f / prefs.getFloat("scroll_step", defStep));
        level = Math.max(1, Math.min(10, level));
        bar.setProgress(level - 1);
        title.setText(getString(R.string.value_format, getString(R.string.scroll_speed), String.valueOf(level)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int l = p + 1;
            prefs.putFloat("scroll_step", 100f / l);
            title.setText(getString(R.string.value_format, getString(R.string.scroll_speed), String.valueOf(l)));
        }));
        list.addView(title);
        list.addView(bar);
    }

    private void toggle(int labelRes, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(labelRes);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.getBool(key, def));
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, checked) -> prefs.putBool(key, checked));
        list.addView(s);
    }

    /** 几选一，存成下标。 */
    private void choice(int labelRes, String key, int def, int[] labels) {
        list.addView(text(getString(labelRes), 16, 0xFFE8E8E8));
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int current = prefs.getInt(key, def);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == current) b.setChecked(true);
            final int value = i;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) prefs.putInt(key, value);
            });
        }
        list.addView(g);
    }

    // ═══════════ 外观 ═══════════

    /**
     * 外观：一小块预览（一行键 + 一小块触控板）、主题（深色、浅色两组色块网格，ThemeGrid）、按键边框开关、一句费电 / 烧屏的说明。
     * 切了马上在预览和色块里变，主界面回去时才换（MainActivity.applyTheme）。预览、色块用带 Theme 参数的 Style 方法，不碰主界面的当前主题；
     * 设置页自己的配色不跟主题变。
     */
    private void appearance() {
        header(R.string.sec_appearance);
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // 键盘按实体键盘排，从右往左的语言下也不镜像（同主界面）
        int p = dp(10);
        preview.setPadding(p, p, p, p);
        list.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(136)));

        list.addView(text(getString(R.string.theme_label), 16, 0xFFE8E8E8));
        // 深色、浅色两组（Theme.ALL 先深色后浅色，组里照它的顺序）
        ThemeGrid[] grids = new ThemeGrid[2];
        Runnable refresh = () -> {
            drawPreview(preview, Theme.byId(prefs.theme()), prefs.keyBorders());
            for (ThemeGrid g : grids) g.show(Theme.byId(prefs.theme()).id, prefs.keyBorders());
            padBg.themeChanged();   // 背景图那一节的预览也按主题画
            refreshGamepad();   // "手柄"那一页的预览、网格也按主题画
            if (benchTheme != null) benchTheme.run();   // 声音那一节的试听台也按主题画
        };
        int[] titles = {R.string.theme_group_dark, R.string.theme_group_light};
        for (int group = 0; group < 2; group++) {
            java.util.List<Theme> ts = new java.util.ArrayList<>();
            for (Theme t : Theme.ALL) if (t.light == (group == 1)) ts.add(t);
            String[] names = new String[ts.size()];
            for (int i = 0; i < names.length; i++) names[i] = themeName(ts.get(i));
            TextView title = text(getString(titles[group]), 13, 0xFF9AA0A6);
            title.setPadding(0, dp(group == 0 ? 6 : 14), 0, dp(6));
            list.addView(title);
            grids[group] = new ThemeGrid(this, ts.toArray(new Theme[0]), names, t -> {
                prefs.setTheme(t.id);
                refresh.run();
            });
            list.addView(grids[group], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        refresh.run();

        Switch borders = new Switch(this);
        borders.setText(R.string.key_borders);
        borders.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        borders.setChecked(prefs.keyBorders());
        borders.setPadding(0, dp(8), 0, dp(8));
        borders.setOnCheckedChangeListener((b, checked) -> {
            prefs.setKeyBorders(checked);
            refresh.run();
        });
        list.addView(borders);
        list.addView(text(getString(R.string.theme_note), 13, 0xFF9A9A9A));
    }

    /**
     * 屏幕边缘留白：左右、上下两个滑块（dp，0–48、步长 2，默认各 8）+ 一句说明。主界面回去时（applyPrefs）才把各页往里收（EdgeMargin）。
     */
    private void edgeMargin() {
        header(R.string.sec_edge_margin);
        sliderInt(R.string.edge_margin_lr, Prefs.EDGE_MARGIN_LR, EdgeMargin.DEFAULT_DP, 0, EdgeMargin.MAX_DP, EdgeMargin.STEP_DP);
        sliderInt(R.string.edge_margin_tb, Prefs.EDGE_MARGIN_TB, EdgeMargin.DEFAULT_DP, 0, EdgeMargin.MAX_DP, EdgeMargin.STEP_DP);
        list.addView(text(getString(R.string.edge_margin_note), 13, 0xFF9A9A9A));
    }

    /** 主题的名字：配色方案自己的名字（Nord……）各语言都不翻译，纯黑 / 深色 / 海蓝 / 高对比 / 浅色 / 樱花 / 薄荷翻译。 */
    private String themeName(Theme t) {
        if (t.fixedName != null) return t.fixedName;
        switch (t.id) {
            case "dark":
                return getString(R.string.theme_dark);
            case "ocean":
                return getString(R.string.theme_ocean);
            case "contrast":
                return getString(R.string.theme_contrast);
            case "glight":
                return getString(R.string.theme_light);
            case "sakura":
                return getString(R.string.theme_sakura);
            case "mint":
                return getString(R.string.theme_mint);
            default:
                return getString(R.string.theme_black);
        }
    }

    /** 预览：页面底色上一行键（⇧ 是锁住的样子，⌫ 是功能键的颜色，中间是普通键）+ 一小块触控板（底色、边框、状态字），整块重画。 */
    private void drawPreview(LinearLayout box, Theme t, boolean keyBorders) {
        box.removeAllViews();
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(t.bg);
        box.setBackground(bg);
        LinearLayout keys = new LinearLayout(this);
        String[] labels = {"⇧", "a", "s", "d", "f", "⌫"};
        for (int i = 0; i < labels.length; i++) {
            TextView k = Style.button(this, 16, t, keyBorders);
            k.setText(labels[i]);
            // ⇧ 锁住（高亮照旧是普通键帽底色 + 强调色的边），⌫ 没锁的功能键
            if (i == 0 || i == labels.length - 1) Style.look(k, t, keyBorders, true, i == 0, false);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) kp.leftMargin = dp(4);
            keys.addView(k, kp);
        }
        box.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // 触控板：同 TrackpadView（18dp 圆角、主题的边框粗细），底部一行状态字
        android.graphics.drawable.GradientDrawable pad = new android.graphics.drawable.GradientDrawable();
        pad.setCornerRadius(dp(18));
        pad.setColor(t.padFill);
        pad.setStroke(Math.max(1, Math.round(t.borderDp * density)), t.padBorder);
        TextView padText = text(getString(R.string.theme_preview_pad), 12, t.status);
        padText.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        padText.setPadding(0, 0, 0, dp(8));
        padText.setBackground(pad);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f);
        pp.topMargin = dp(8);
        box.addView(padText, pp);
    }

    // ═══════════ 声音（声音第一批第 ④ 步，"外观"页最后一节） ═══════════

    /** 套装的名字（按 SoundBank.PACK_* 的顺序）。 */
    private static final int[] SOUND_PACK_LABELS = {R.string.sound_pack_off, R.string.sound_pack_light, R.string.sound_pack_wood};
    /** 调试版候选区每个槽的名字（按 SoundBank.SLOT_* 的顺序）。 */
    private static final int[] SOUND_SLOT_LABELS = {R.string.sound_slot_key, R.string.sound_slot_big, R.string.sound_slot_space,
            R.string.sound_slot_enter, R.string.sound_slot_key_up, R.string.sound_slot_mouse_down, R.string.sound_slot_mouse_up,
            R.string.sound_slot_pad, R.string.sound_slot_scroll, R.string.sound_slot_catch};

    /**
     * "声音"一节（震动后面）：套装（关 / 轻 / 木与枝，点一项就放一遍 SoundBench.DEMO）、一句说明；套装不是"关"时下面才有：音量、
     * 键盘 / 鼠标键 / 滚轮 / 手柄四个开关（手柄默认关）、试听台（{@link #soundBench}），调试版另有候选区（{@link #soundTuning}）。
     * 套装、音量马上交给 Sounds（进程一份，主界面也用它），开关、震动延后回到主界面时 MainActivity.applySound 照新设置配。
     */
    private void soundSection() {
        header(R.string.sec_sound);
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int current = prefs.soundPack();
        for (int i = 0; i < SOUND_PACK_LABELS.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(SOUND_PACK_LABELS[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (i == current) b.setChecked(true);
            final int pack = i;
            b.setOnClickListener(v -> pickSoundPack(pack));   // 点一下（选中的也一样）就放一遍
        }
        list.addView(g);
        list.addView(text(getString(R.string.sound_note), 13, 0xFF9A9A9A));

        soundBox = new LinearLayout(this);
        soundBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout outer = list;
        list = soundBox;
        soundVolume();
        toggle(R.string.sound_keys, Prefs.SOUND_KEYS, prefs.soundKeys());
        toggle(R.string.sound_mouse, Prefs.SOUND_MOUSE, prefs.soundMouse());
        toggle(R.string.sound_scroll, Prefs.SOUND_SCROLL, prefs.soundScroll());
        toggle(R.string.sound_gamepad, Prefs.SOUND_GAMEPAD, prefs.soundGamepad());
        TextView benchTitle = text(getString(R.string.sound_bench), 16, 0xFFE8E8E8);
        benchTitle.setPadding(0, dp(12), 0, 0);
        list.addView(benchTitle);
        list.addView(text(getString(R.string.sound_bench_hint), 14, 0xFF9AA0A6));
        soundBench();
        if (sounds.tuning()) soundTuning();
        list = outer;
        list.addView(soundBox);
        soundBox.setVisibility(current != SoundBank.PACK_OFF ? View.VISIBLE : View.GONE);
    }

    /** 选了一套（或者又点了选中的那一项）：存下、交给 Sounds，等它算好再放那一小段；候选区换成这一套的候选。 */
    private void pickSoundPack(int pack) {
        boolean changed = pack != prefs.soundPack();
        prefs.setSoundPack(pack);
        applySound();
        if (soundBox != null) soundBox.setVisibility(pack != SoundBank.PACK_OFF ? View.VISIBLE : View.GONE);
        if (changed) fillSoundCands();
        demoHandler.removeCallbacksAndMessages(null);
        burstHandler.removeCallbacksAndMessages(null);
        if (pack == SoundBank.PACK_OFF) return;
        demoTries = 0;
        demoHandler.post(demoWait);
    }

    /** 等这一套算好、管道开着（算好之前交的声音会被丢），再照 SoundBench.DEMO 的时间一个个交出去。 */
    private final Runnable demoWait = new Runnable() {
        @Override
        public void run() {
            if (prefs.soundPack() == SoundBank.PACK_OFF) return;
            if (!sounds.ready()) {
                if (++demoTries < DEMO_WAIT_TRIES) demoHandler.postDelayed(this, 50);
                return;
            }
            for (int[] d : SoundBench.DEMO) {
                final int sound = d[1], key = d[2];
                demoHandler.postDelayed(() -> sounds.play(sound, key), d[0]);
            }
        }
    };

    /** 音量滑块（0–100，Sounds.DEFAULT_VOLUME 默认，每格 0.4 dB）：拖的时候马上生效，松手放一下按键声听听多响。 */
    private void soundVolume() {
        String label = getString(R.string.sound_volume);
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        int value = prefs.soundVolume();
        bar.setProgress(value);
        title.setText(getString(R.string.value_format, label, String.valueOf(value)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                prefs.putInt(Prefs.SOUND_VOLUME, progress);
                title.setText(getString(R.string.value_format, label, String.valueOf(progress)));
                applySound();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                sounds.play(SoundBank.KEY_DOWN, 0x04);
                sounds.play(SoundBank.KEY_UP, 0x04, SoundBench.KEY_HOLD_MS);
            }
        });
        list.addView(title);
        list.addView(bar);
    }

    /**
     * 照设置配好声音（Sounds 是进程一份，主界面回来时 MainActivity.applySound 再照同样的设置配一次，没变就什么都不做）和试听台的事件层、震动。
     * 设置页里任何一项声音的设置改了都调它；试听台按下时也调一次 {@link #syncBench}（别的页改了震动、滚动咔嗒的开关）。
     */
    private void applySound() {
        int pack = prefs.soundPack();
        sounds.configure(pack, prefs.soundVolume(), prefs.soundChoices(pack), prefs.soundOffsetDb(), prefs.soundRandomScale());
        sounds.setSuppressed(inCall());
        syncBench();
    }

    /** 试听台的事件层和震动照现在的设置（同 MainActivity：声音的四个开关、滚动咔嗒的震动开关、震动强度……、调试版的惯性波形和震动延后）。 */
    private void syncBench() {
        int pack = prefs.soundPack();
        benchEvents.configure(pack != SoundBank.PACK_OFF, prefs.soundKeys(), prefs.soundMouse(), prefs.soundScroll(), prefs.soundGamepad(),
                prefs.scrollTicks());
        benchHaptics.setLevel(prefs.haptic());
        benchHaptics.setTickStrength(prefs.tickStrength());
        benchHaptics.setDragHum(prefs.dragHum());
        boolean tuning = sounds.tuning();
        benchHaptics.setSpinStyles(tuning ? prefs.spinStyle(true) : -1, tuning ? prefs.spinStyle(false) : -1);
        benchHaptics.setDelayMs(tuning && pack != SoundBank.PACK_OFF ? prefs.soundHapticDelay() : 0);
    }

    private boolean inCall() {
        return audio != null && audio.getMode() != AudioManager.MODE_NORMAL;
    }

    /**
     * 试听台（正式版也有）：一排键 ⇧ a s d ⌫ Space ↵、一小块触控板（轻点 = 点击、划 = 滚轮几格）、三个鼠标键，照现在选的主题画（同上面的预览）。
     * 按下去就是真的声音 + 真的震动：和主界面同一个 Sounds、同一套 SoundEvents 规则、同样配的 Haptics；按键松开只出声不震、被取消（页面抢走手指）的松开不出声。
     * 这里没有服务：打开设置页时主界面已经暂停，触摸屏早还给了系统，就是普通的 View 收触摸。按下时不让外面的 ScrollView 抢走手指（划触控板时页面不跟着滚）。
     */
    private void soundBench() {
        LinearLayout bench = new LinearLayout(this);
        bench.setOrientation(LinearLayout.VERTICAL);
        bench.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // 键盘按实体键盘排，从右往左的语言下也不镜像（同主界面）
        int p = dp(10);
        bench.setPadding(p, p, p, p);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        list.addView(bench, blp);
        benchT = Theme.byId(prefs.theme());
        benchBorders = prefs.keyBorders();

        // 一排键
        LinearLayout keys = new LinearLayout(this);
        TextView[] caps = new TextView[SoundBench.KEYS.length];
        for (int i = 0; i < caps.length; i++) {
            final int id = SoundBench.KEYS[i];
            final boolean fn = SoundBench.keyFn(i);
            TextView k = Style.button(this, 16, benchT, benchBorders);
            k.setText(SoundBench.KEY_LABELS[i]);
            Style.look(k, benchT, benchBorders, fn, false, false);
            k.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:   // 先出声、再震、再画按下的样子（同面板自己收触摸）
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        syncBench();
                        benchEvents.keyDown(id);
                        benchHaptics.playKey();
                        Style.look(v, benchT, benchBorders, fn, false, true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        benchEvents.keyUp(id, e.getActionMasked() == MotionEvent.ACTION_CANCEL);
                        Style.look(v, benchT, benchBorders, fn, false, false);
                        break;
                    default:
                        break;
                }
                return true;
            });
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, SoundBench.KEY_WEIGHTS[i]);
            if (i > 0) kp.leftMargin = dp(4);
            keys.addView(k, kp);
            caps[i] = k;
        }
        bench.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // 一小块触控板
        TextView pad = text(getString(R.string.sound_bench_pad), 12, benchT.status);
        pad.setGravity(Gravity.CENTER);
        pad.setPadding(dp(8), 0, dp(8), 0);
        pad.setOnTouchListener((v, e) -> {
            benchPadTouch(v, e);
            return true;
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        pp.topMargin = dp(8);
        bench.addView(pad, pp);

        // 三个鼠标键（同主界面的样子：中键窄、左右键宽 2.2 : 1 : 2.2，左右键下沿一道红线、中键上沿一排小点）
        MouseKeys mk = new MouseKeys(this, dp(4));
        for (int i = 0; i < 3; i++) {
            MouseKeys.Key key = (MouseKeys.Key) mk.key(i);
            final int mask = MouseKeys.MASKS[i];
            key.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        syncBench();
                        key.setPressedLook(true);
                        benchEvents.mouseDown(mask);
                        benchMouseFeedback(mask, true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        key.setPressedLook(false);
                        benchEvents.mouseUp(mask, e.getActionMasked() == MotionEvent.ACTION_CANCEL);
                        benchMouseFeedback(mask, false);
                        break;
                    default:
                        break;
                }
                return true;
            });
        }
        mk.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            if (w == or - ol) return;
            int mid = Math.round(w / 5.4f);   // 约 2.2 : 1 : 2.2（同 KeyboardPlan.middleKey 的比例）
            int left = (w - mid) / 2;
            mk.post(() -> {   // 布局回调里不直接重新布局
                mk.setMiddle(left, left + mid);
                mk.requestLayout();
            });
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        mp.topMargin = dp(8);
        bench.addView(mk, mp);

        benchTheme = () -> {
            benchT = Theme.byId(prefs.theme());
            benchBorders = prefs.keyBorders();
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(benchT.bg);
            bench.setBackground(bg);
            for (int i = 0; i < caps.length; i++) Style.look(caps[i], benchT, benchBorders, SoundBench.keyFn(i), false, false);
            android.graphics.drawable.GradientDrawable padBgShape = new android.graphics.drawable.GradientDrawable();
            padBgShape.setCornerRadius(dp(18));
            padBgShape.setColor(benchT.padFill);
            padBgShape.setStroke(Math.max(1, Math.round(benchT.borderDp * density)), benchT.padBorder);
            pad.setBackground(padBgShape);
            pad.setTextColor(benchT.status);
            mk.setTheme(benchT);
        };
        benchTheme.run();
    }

    /**
     * 试听台那一小块触控板：一根手指，轻点（抬起时认出）= 像 MacBook 触控板的一对 + 点一下的震动，划 = 每划过一格（"滚动速度"那一格）一声刻度 + 咔嗒
     * （同主界面：滚动咔嗒的震动开关关着就不震，SoundEvents.haptic 说只为出声的也不震）。第二根手指不理；跟着的那根抬起 / 被取消就什么都不算。
     */
    private void benchPadTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                syncBench();
                GestureEngine.Config c = prefs.engineConfig();
                benchPad = new SoundBench.Pad(c.tapSlopDp * density, c.scrollStepDp * density, c.tapMaxMs);
                benchPadPointer = e.getPointerId(0);
                benchPad.down(e.getX(), e.getY(), e.getEventTime());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int i = benchPadPointer < 0 || benchPad == null ? -1 : e.findPointerIndex(benchPadPointer);
                if (i < 0) break;
                int n = benchPad.move(e.getX(i), e.getY(i), e.getEventTime());
                for (int k = 0; k < n; k++) benchScrollTick();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (benchPad != null && e.getPointerId(e.getActionIndex()) == benchPadPointer) {   // 跟着的那根先抬起：这次不算了
                    benchPad.cancel();
                    benchPadPointer = -1;
                }
                break;
            case MotionEvent.ACTION_UP: {
                if (benchPad == null || e.getPointerId(0) != benchPadPointer) break;
                int n = benchPad.move(e.getX(), e.getY(), e.getEventTime());
                for (int k = 0; k < n; k++) benchScrollTick();
                if (benchPad.up(e.getX(), e.getY(), e.getEventTime()) && benchEvents.haptic(GestureEngine.HAPTIC_CLICK, SoundEvents.FROM_ROUTER)) {
                    benchHaptics.play(GestureEngine.HAPTIC_CLICK);
                }
                benchPadPointer = -1;
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (benchPad != null) benchPad.cancel();
                benchPadPointer = -1;
                break;
            default:
                break;
        }
    }

    /** 试听台滚了一格：先出声（滚轮出声开着才有），再照"滚动咔嗒"的震动开关震（同主界面：引擎只在开关开着、或只为出声时才发咔嗒）。 */
    private void benchScrollTick() {
        boolean vibrate = benchEvents.haptic(GestureEngine.HAPTIC_TICK, SoundEvents.FROM_ROUTER);
        if (vibrate && prefs.scrollTicks()) benchHaptics.play(GestureEngine.HAPTIC_TICK);
    }

    /** 试听台三个鼠标键的震动：同 MainActivity.mouseKeyButton——左键"嗒" + 持续轻震（按着几个计数），松开停震再"嗒"；右键、中键按下"嗒"一下。 */
    private void benchMouseFeedback(int mask, boolean down) {
        if (mask != Proto.BUTTON_LEFT) {
            if (down) benchHaptics.play(GestureEngine.HAPTIC_CLICK);
            return;
        }
        if (!down && benchLeftHolds == 0) return;
        benchLeftHolds = Math.max(0, benchLeftHolds + (down ? 1 : -1));
        if (down) {
            benchHaptics.play(GestureEngine.HAPTIC_CLICK);
            benchHaptics.startHum();
        } else if (benchLeftHolds > 0) {
            benchHaptics.play(GestureEngine.HAPTIC_TICK);   // 还有一个按着：接着嗡
        } else {
            benchHaptics.play(GestureEngine.HAPTIC_DRAG_END);
        }
    }

    // ── 调试版候选区 ──

    /**
     * 调试版才有（同惯性波形那一节的规矩）：这一套每个槽 2–4 个候选（A / B / C / D，后面写参数），点一下 = 放一遍 + 震一下、选中的存下来
     * （Prefs.setSoundChoice，每套各记各的；主界面回去就用它）；"连打"用这个候选每秒 7 下（SoundBench.BURST_HZ）连放 2 秒；"打乱顺序"开着时按钮背后是哪个随机换、不写参数，
     * 关掉就揭晓；微调滑块（音量偏移、随机幅度、震动延后）；最下面一行诊断（每半秒刷新，Sounds.Diag.line）。正式包不显示，一律用默认（A）。
     * 挑定以后把默认值改过去、这一节删掉。
     */
    private void soundTuning() {
        TextView title = text(getString(R.string.sound_try), 14, 0xFF9AA0A6);
        title.setPadding(0, dp(16), 0, 0);
        list.addView(title);
        Switch shuffle = new Switch(this);
        shuffle.setText(R.string.sound_shuffle);
        shuffle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        shuffle.setChecked(soundShuffle);
        shuffle.setPadding(0, dp(8), 0, dp(8));
        shuffle.setOnCheckedChangeListener((b, on) -> {
            soundShuffle = on;
            if (on) shuffleSeed = System.nanoTime();   // 每打乱一次换一种排法
            fillSoundCands();
        });
        list.addView(shuffle);
        soundCands = new LinearLayout(this);
        soundCands.setOrientation(LinearLayout.VERTICAL);
        list.addView(soundCands);
        fillSoundCands();
        sliderInt(R.string.sound_offset, Prefs.SOUND_OFFSET, 0, SoundBench.OFFSET_MIN_DB, SoundBench.OFFSET_MAX_DB, 1, " dB", this::applySound);
        sliderInt(R.string.sound_random, Prefs.SOUND_RANDOM, 100, 0, SoundBench.RANDOM_MAX_PCT, SoundBench.RANDOM_STEP_PCT, "%", this::applySound);
        sliderInt(R.string.sound_haptic_delay, Prefs.SOUND_HAPTIC_DELAY, 0, 0, SoundBench.DELAY_MAX_MS, SoundBench.DELAY_STEP_MS, " ms", this::syncBench);
        list.addView(text(getString(R.string.sound_haptic_delay_hint), 14, 0xFF9AA0A6));
        TextView diagTitle = text(getString(R.string.sound_diag), 14, 0xFF9AA0A6);
        diagTitle.setPadding(0, dp(12), 0, 0);
        list.addView(diagTitle);
        soundDiag = text("", 11, 0xFF9AA0A6);
        soundDiag.setTypeface(android.graphics.Typeface.MONOSPACE);   // 不设成可选中：可选中就能拿焦点，每半秒改字时 ScrollView 会往它那儿跳
        list.addView(soundDiag);
    }

    /** 候选区每个槽那几行（这一套的候选；换套、打乱 / 揭晓时重填）。 */
    private void fillSoundCands() {
        if (soundCands == null) return;
        soundCands.removeAllViews();
        int pack = prefs.soundPack();
        if (pack == SoundBank.PACK_OFF) return;
        for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
            SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
            if (cs.length == 0) continue;
            final int s = slot;
            // 第 i 个按钮背后是哪个候选：打乱时照这一轮的种子排，不然照原来的顺序
            final int[] perm = SoundBench.order(cs.length, soundShuffle, shuffleSeed * 31 + slot);
            TextView name = text(getString(SOUND_SLOT_LABELS[slot]), 16, 0xFFE8E8E8);
            name.setPadding(0, dp(12), 0, dp(4));
            soundCands.addView(name);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView[] buttons = new TextView[cs.length];
            TextView desc = text("", 13, 0xFF9AA0A6);
            Runnable refresh = () -> {
                int chosen = chosenCandidate(pack, s, cs.length);
                for (int i = 0; i < buttons.length; i++) candLook(buttons[i], perm[i] == chosen);
                if (soundShuffle) {
                    desc.setVisibility(View.GONE);   // 打乱时不写参数（写了就知道是哪个）
                } else {
                    String d = cs[chosen].recipe == null ? getString(R.string.sound_cand_silent) : cs[chosen].desc();
                    desc.setText(getString(R.string.value_format, cs[chosen].code, d));
                    desc.setVisibility(View.VISIBLE);
                }
            };
            for (int i = 0; i < cs.length; i++) {
                final int actual = perm[i];
                TextView b = text(SoundBench.code(i), 16, 0xFFE8E8E8);
                b.setGravity(Gravity.CENTER);
                b.setOnClickListener(v -> {
                    prefs.setSoundChoice(pack, s, actual);
                    applySound();
                    refresh.run();
                    auditionHit(s, actual, 0, false);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(40));
                lp.setMarginEnd(dp(6));
                row.addView(b, lp);
                buttons[i] = b;
            }
            TextView burst = text(getString(R.string.sound_burst), 15, 0xFF8AB4F8);
            burst.setGravity(Gravity.CENTER);
            burst.setPadding(dp(12), 0, dp(12), 0);
            burst.setOnClickListener(v -> startBurst(s, chosenCandidate(pack, s, cs.length)));
            row.addView(burst, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
            soundCands.addView(row);
            soundCands.addView(desc);
            refresh.run();
        }
    }

    /** 这一套这个槽现在选的是第几个候选（存的越界了当第 0 个，同混音器）。 */
    private int chosenCandidate(int pack, int slot, int count) {
        int c = prefs.soundChoices(pack)[slot];
        return c >= 0 && c < count ? c : 0;
    }

    /** 候选按钮的样子：选中的同选中的标签（淡蓝底、亮蓝粗字），别的一圈灰边。 */
    private void candLook(TextView b, boolean on) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp(8));
        if (on) {
            d.setColor(TAB_ON_FILL);
        } else {
            d.setColor(0);
            d.setStroke(Math.max(1, dp(1)), 0xFF5F6368);
        }
        b.setBackground(d);
        b.setTextColor(on ? TAB_ON_TEXT : 0xFFE8E8E8);
        b.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    /** 候选区的一下：照 SoundBench.hit 交出去（正在试的那一半用这个候选），再照 SoundBench.haptic 震（滚轮、惯性刹住照"滚动咔嗒"的开关）。 */
    private void auditionHit(int slot, int cand, int n, boolean burst) {
        syncBench();
        for (SoundBench.Shot s : SoundBench.hit(slot, n, burst)) {
            if (s.candidate) sounds.audition(s.sound, s.key, cand, s.delayMs);
            else sounds.play(s.sound, s.key, s.delayMs);
        }
        int h = SoundBench.haptic(slot);
        if (h == SoundBench.HAPTIC_KEY) {
            benchHaptics.playKey();
        } else if (h >= 0) {
            boolean scrollKind = h == GestureEngine.HAPTIC_TICK || h == GestureEngine.HAPTIC_SPIN_CATCH;
            if (!scrollKind || prefs.scrollTicks()) benchHaptics.play(h);
        }
    }

    /** "连打"：这个候选每秒 SoundBench.BURST_HZ 下连放 SoundBench.BURST_MS 毫秒（再点一次从头来，换套 / 关页面就停）。 */
    private void startBurst(int slot, int cand) {
        burstHandler.removeCallbacksAndMessages(null);
        for (int n = 0; n < SoundBench.BURST_HITS; n++) {
            final int k = n;
            burstHandler.postDelayed(() -> auditionHit(slot, cand, k, true), (long) n * SoundBench.BURST_GAP_MS);
        }
    }

    /** 诊断行每半秒刷新一次（只在调试版、这页开着时）。 */
    private final Runnable diagTick = new Runnable() {
        @Override
        public void run() {
            if (soundDiag != null) soundDiag.setText(sounds.diag().line());
            diagHandler.postDelayed(this, 500);
        }
    };

    // ═══════════ 连接方式 ═══════════

    private void connection() {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int[] labels = {R.string.conn_dex, R.string.conn_bt};
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if ((i == 1) == prefs.btMode()) b.setChecked(true);
            final boolean useBt = i == 1;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (!checked || useBt == prefs.btMode()) return;
                prefs.setBtMode(useBt);
                updateBtUse();
                if (useBt && !BtHid.hasPermission(this)) requestBtPermissions();
                rebuildLater();   // 蓝牙那几项跟着出现 / 消失
            });
        }
        list.addView(g);
        btStatus = null;
        btDevices = null;
        btAudioWarning = null;
        if (!prefs.btMode()) return;

        btStatus = text("", 14, 0xFF9AA0A6);
        btStatus.setPadding(0, dp(6), 0, dp(6));
        list.addView(btStatus);
        list.addView(text(getString(R.string.bt_host), 16, 0xFFE8E8E8));
        btDevices = new LinearLayout(this);
        btDevices.setOrientation(LinearLayout.VERTICAL);
        list.addView(btDevices);
        btAudioWarning = text("", 14, 0xFFF2B866);
        btAudioWarning.setPadding(0, dp(6), 0, dp(6));
        btAudioWarning.setVisibility(View.GONE);
        btAudioWarning.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        list.addView(btAudioWarning);
        TextView pair = text(getString(R.string.bt_pair), 16, 0xFF8AB4F8);
        pair.setPadding(0, dp(12), 0, dp(6));
        pair.setOnClickListener(v -> {
            if (!BtHid.hasPermission(this)) {
                requestBtPermissions();
                return;
            }
            // 系统对话框：让别的设备在 2 分钟内能搜到这台手机。豆花一直开着，对方配对时才看得到"键盘鼠标"
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120));
        });
        list.addView(pair);
        TextView hint = text(getString(R.string.bt_pair_hint), 13, 0xFF9A9A9A);
        list.addView(hint);
        onBtState(bt.state(), BtHid.deviceName(bt.connectedDevice()));
        refreshBtDevices();
    }

    private void refreshBtDevices() {
        if (btDevices == null) return;
        refreshAudioWarning();
        btDevices.removeAllViews();
        List<BluetoothDevice> devices = bt.hostCandidates();
        if (devices.isEmpty()) {
            btDevices.addView(text(getString(R.string.bt_no_bonded), 14, 0xFF9A9A9A));
            return;
        }
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        String host = prefs.btHost();
        for (BluetoothDevice d : devices) {
            RadioButton b = new RadioButton(this);
            b.setText(BtHid.deviceName(d));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (d.getAddress().equals(host)) b.setChecked(true);
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked && !d.getAddress().equals(prefs.btHost())) bt.connect(d);
            });
        }
        btDevices.addView(g);
    }

    @Override
    public void onBtState(int state, String deviceName) {
        refreshSmoothHost();   // 连上 / 断开、主机设了滚轮倍率（BtHid 会再报一次同样的状态）
        if (btStatus == null) return;
        int res;
        switch (state) {
            case BtHid.STATE_NO_PERMISSION: res = R.string.bt_state_no_permission; break;
            case BtHid.STATE_BT_DISABLED: res = R.string.bt_state_disabled; break;
            case BtHid.STATE_UNSUPPORTED: res = R.string.bt_state_unsupported; break;
            case BtHid.STATE_WAITING: res = R.string.bt_state_waiting; break;
            case BtHid.STATE_CONNECTING: res = R.string.bt_state_connecting; break;
            case BtHid.STATE_CONNECTED: res = R.string.bt_state_connected; break;
            case BtHid.STATE_FAILED: res = R.string.bt_state_failed; break;
            case BtHid.STATE_STUCK: res = R.string.bt_state_stuck; break;
            default: res = R.string.bt_state_starting; break;
        }
        btStatus.setText(getString(res, deviceName));
        if (state == BtHid.STATE_CONNECTED || state == BtHid.STATE_WAITING) refreshBtDevices();
    }

    // ═══════════ 滚动：惯性、连续滚动 ═══════════

    /** 连续滚动开着、蓝牙连着一台时：这台主机开没开平滑滚动（设了滚轮倍率没有）。 */
    private TextView smoothHost;

    /**
     * 惯性滚动：开关；开着才显示阻尼（10 档，下面一行"中等的一拨大约转几秒"随档位变）和"多快算拨"（dp/ms）。
     * 存进 Prefs，回到主界面 applyPrefs 时连同别的手势参数一起发给服务 / 本地分流。
     */
    private void inertia(GestureEngine.Config def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Switch s = new Switch(this);
        s.setText(R.string.inertia);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.getBool(Prefs.INERTIA, def.inertia));
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.putBool(Prefs.INERTIA, on);
            box.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        list.addView(s);

        LinearLayout outer = list;
        list = box;
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax(ScrollInertia.LEVELS - 1);
        TextView hint = text("", 14, 0xFF9AA0A6);
        IntConsumer show = level -> {
            title.setText(getString(R.string.value_format, getString(R.string.inertia_damping), String.valueOf(level)));
            double sec = ScrollInertia.stopSeconds(ScrollInertia.TYPICAL_SPEED, ScrollInertia.tauMs(level) / 1000.0);
            hint.setText(getString(R.string.inertia_damping_hint, String.format(Locale.ROOT, "%.1f", sec)));
        };
        int level = prefs.inertiaDamping();
        bar.setProgress(level - 1);
        show.accept(level);
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            prefs.putInt(Prefs.INERTIA_DAMPING, p + 1);
            show.accept(p + 1);
        }));
        list.addView(title);
        list.addView(bar);
        list.addView(hint);
        slider(R.string.inertia_speed, Prefs.INERTIA_SPEED, def.inertiaMinSpeed,
                Prefs.INERTIA_SPEED_MIN, Prefs.INERTIA_SPEED_MAX, Prefs.INERTIA_SPEED_STEP, "%.1f");
        list.addView(text(getString(R.string.inertia_speed_hint), 14, 0xFF9AA0A6));
        list = outer;
        list.addView(box);
        box.setVisibility(s.isChecked() ? View.VISIBLE : View.GONE);
    }

    /**
     * 小红点（键盘页 ⋯ 菜单里"指点"换成它）：灵敏度、轻点 = 左键、负惯性，一段说明；触控板旁边的三键条：开关 + 放上沿 / 下沿（开着才显示）。
     * 存进 Prefs，回到主界面 applyPrefs 时连同别的手势参数一起发给服务 / 本地分流（三键条只是界面上的事，不发）。
     */
    private void trackpoint(GestureEngine.Config def) {
        header(R.string.sec_trackpoint);
        slider(R.string.stick_speed, Prefs.STICK_SPEED, def.stickSpeed, Prefs.STICK_SPEED_MIN, Prefs.STICK_SPEED_MAX,
                Prefs.STICK_SPEED_STEP, "%.1f", this::refreshStickAccel);   // 灵敏度一变，"横穿 1920 像素约几秒"跟着变
        stickAccel();
        toggle(R.string.stick_tap, Prefs.STICK_TAP, def.stickTap);
        toggle(R.string.stick_inertia, Prefs.STICK_INERTIA, def.stickInertia);
        // 中键滑动（中键自己当滚动杆）的滚动速度：百分数，只管它（按住中键推红点的不变）
        sliderInt(R.string.lever_speed, Prefs.LEVER_SPEED, Prefs.LEVER_SPEED_DEFAULT, Prefs.LEVER_SPEED_MIN, Prefs.LEVER_SPEED_MAX,
                Prefs.LEVER_SPEED_STEP, "%");
        list.addView(text(getString(R.string.lever_speed_hint), 14, 0xFF9AA0A6));
        list.addView(text(getString(R.string.stick_hint), 14, 0xFF9AA0A6));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Switch s = new Switch(this);
        s.setText(R.string.mouse_keys);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.getBool(Prefs.PAD_KEYS, false));
        s.setPadding(0, dp(16), 0, dp(8));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.putBool(Prefs.PAD_KEYS, on);
            box.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        list.addView(s);
        LinearLayout outer = list;
        list = box;
        choice(R.string.mouse_keys_pos, Prefs.PAD_KEYS_POS, 0, Prefs.PAD_KEYS_POS_LABELS);
        list = outer;
        list.addView(box);
        box.setVisibility(s.isChecked() ? View.VISIBLE : View.GONE);
    }

    /** "加速"下面那行说明、"最快速度"那一行（标题、滑块，加速 0% 时灰掉）和下面那行说明：三个滑块（含灵敏度）一动就照 TrackPoint 现算。 */
    private TextView stickAccelHint, stickMaxHint;
    private View[] stickMaxRow;

    /**
     * 小红点推光标（和左键杆）的"加速""最快速度"（协议 23）：推满时的速度 = 平台 + 加速 × (最快 − 平台)（TrackPoint.moveTop）。
     * 中键滚动的曲线、触控板不跟它们。最快速度的值（加速 100% 时推满的速度）后面接单位（计数 / 秒）；加速 0% 时最快速度那一行灰掉、说明里写不起作用，
     * 加速在 0%–100% 之间时说明里先写一句"这个数是加速 100% 时的、现在实际约多少"（不然标题写 1200、下面的秒数按 825 算，对不上）。
     */
    private void stickAccel() {
        sliderInt(R.string.stick_accel, Prefs.STICK_ACCEL, Prefs.STICK_ACCEL_DEFAULT, Prefs.STICK_ACCEL_MIN, Prefs.STICK_ACCEL_MAX,
                Prefs.STICK_ACCEL_STEP, "%", this::refreshStickAccel);
        stickAccelHint = text("", 14, 0xFF9AA0A6);
        list.addView(stickAccelHint);
        stickMaxRow = sliderInt(R.string.stick_max_speed, Prefs.STICK_MAX, Prefs.STICK_MAX_DEFAULT, Prefs.STICK_MAX_MIN, Prefs.STICK_MAX_MAX,
                Prefs.STICK_MAX_STEP, " " + getString(R.string.stick_max_speed_unit), this::refreshStickAccel);
        stickMaxHint = text("", 14, 0xFF9AA0A6);
        list.addView(stickMaxHint);
        refreshStickAccel();
    }

    private void refreshStickAccel() {
        if (stickAccelHint == null) return;
        GestureEngine.Config c = prefs.engineConfig();   // 和发给服务的同一套换算（百分数 → 0–1、截到范围里）
        boolean off = !(c.stickAccel > 0);
        // 倍数照写出来的数挑那句：一位小数是 "1.0" 时 ratioText 写两位，两位还是 1.00（或加速 0）才用"不再变快"那句
        String ratio = TrackPoint.ratioText(c.stickAccel, c.stickMaxSpeed);
        stickAccelHint.setText(ratio == null ? getString(R.string.stick_accel_hint_off) : getString(R.string.stick_accel_hint, ratio));
        String secs = getString(R.string.stick_max_speed_hint, TrackPoint.tenths(TrackPoint.crossSeconds(c.stickAccel, c.stickMaxSpeed, c.stickSpeed)));
        String top = TrackPoint.topSpeedText(c.stickAccel, c.stickMaxSpeed);   // 加速在 0%–100% 之间时推到底实际多快（滑块上的数是 100% 时的）
        String first = off ? getString(R.string.stick_max_speed_off)
                : top == null ? null
                : getString(R.string.stick_max_speed_actual, prefs.stickAccelPct() + "%", top + " " + getString(R.string.stick_max_speed_unit));
        stickMaxHint.setText(first == null ? secs : first + "\n" + secs);
        for (View v : stickMaxRow) {
            v.setEnabled(!off);
            v.setAlpha(off ? 0.4f : 1f);
        }
    }

    /** 连续滚动：开关 + 说明（蓝牙开 / 关后要重新配对）+ 蓝牙连着时这台主机开没开。蓝牙模式下切开关马上用新描述符重新注册。 */
    private void smoothScroll() {
        Switch s = new Switch(this);
        s.setText(R.string.smooth_scroll);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(prefs.smoothScroll());
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.putBool(Prefs.SMOOTH_SCROLL, on);
            if (btAcquired) bt.descriptorChanged();
            refreshSmoothHost();
        });
        list.addView(s);
        list.addView(text(getString(R.string.smooth_scroll_hint), 14, 0xFF9AA0A6));
        smoothHost = text("", 14, 0xFFE8B04A);
        list.addView(smoothHost);
        refreshSmoothHost();
    }

    private void refreshSmoothHost() {
        if (smoothHost == null) return;
        BluetoothDevice d = prefs.btMode() && prefs.smoothScroll() && bt.state() == BtHid.STATE_CONNECTED ? bt.connectedDevice() : null;
        if (d == null) {
            smoothHost.setVisibility(View.GONE);
            return;
        }
        boolean on = bt.hostWheelMultiplier() > 1;
        smoothHost.setText(getString(on ? R.string.smooth_host_on : R.string.smooth_host_off, BtHid.deviceName(d)));
        smoothHost.setTextColor(on ? 0xFF9AA0A6 : 0xFFE8B04A);
        smoothHost.setVisibility(View.VISIBLE);
    }

    /**
     * 调试版才有：惯性"放开""刹住"各三种候选波形，点一下震一下、选中的存下来（Prefs.spinStyle），回到主界面生效；
     * 正式包不显示，一律用默认（Haptics.defaultSpinStyle）。挑定以后把默认改过去、这一节删掉。
     */
    private void spinTuning() {
        list.addView(text(getString(R.string.spin_try), 14, 0xFF9AA0A6));
        Haptics h = new Haptics(this);
        int[][] names = {
                {R.string.spin_wave_spin, R.string.spin_wave_fall, R.string.spin_wave_quick_fall},
                {R.string.spin_wave_thud, R.string.spin_wave_rise, R.string.spin_wave_heavy},
        };
        for (int k = 0; k < 2; k++) {
            boolean start = k == 0;
            list.addView(text(getString(start ? R.string.spin_start : R.string.spin_catch), 16, 0xFFE8E8E8));
            RadioGroup g = new RadioGroup(this);
            g.setOrientation(RadioGroup.VERTICAL);
            int current = prefs.spinStyle(start);
            if (current < 0) current = h.defaultSpinStyle(start);
            for (int i = 0; i < Haptics.SPIN_STYLES; i++) {
                RadioButton b = new RadioButton(this);
                String name = getString(names[k][i]);
                b.setText(h.spinSupported(start, i) ? name : getString(R.string.spin_wave_unsupported, name));
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                b.setId(View.generateViewId());
                g.addView(b);
                if (i == current) b.setChecked(true);
                final int style = i;
                b.setOnClickListener(v -> {   // 点一下（选中的也一样）就震一下
                    prefs.setSpinStyle(start, style);
                    h.setLevel(prefs.haptic());
                    h.setTickStrength(prefs.tickStrength());
                    h.trySpin(start, style);
                });
            }
            list.addView(g);
        }
    }

    // ═══════════ 手柄 ═══════════

    /** 自定义里"面键"能选的排法（显示的顺序）和名字。 */
    private static final int[] GP_FACES = {GamepadSpec.FACE_NONE, GamepadSpec.FACE_ROW2, GamepadSpec.FACE_DIAG2, GamepadSpec.FACE_THREE,
            GamepadSpec.FACE_DIAMOND, GamepadSpec.FACE_ARC4, GamepadSpec.FACE_SIX, GamepadSpec.FACE_ARCADE6, GamepadSpec.FACE_EIGHT,
            GamepadSpec.FACE_N64, GamepadSpec.FACE_GC};
    private static final int[] GP_FACE_NAMES = {R.string.gp_face_none, R.string.gp_face_row2, R.string.gp_face_diag2, R.string.gp_face_three,
            R.string.gp_face_diamond, R.string.gp_face_arc4, R.string.gp_face_six, R.string.gp_face_arcade6, R.string.gp_face_eight,
            R.string.gp_face_n64, R.string.gp_face_gc};
    /** 自定义里"键帽上的字"能选的字样（显示的顺序；括号里是南 东 西 北四个位置印的字）。 */
    private static final int[] GP_LABELS = {GamepadSpec.LABELS_XBOX, GamepadSpec.LABELS_NINTENDO, GamepadSpec.LABELS_PLAYSTATION,
            GamepadSpec.LABELS_SEGA, GamepadSpec.LABELS_ARCADE, GamepadSpec.LABELS_NUMBER};

    /** "手柄"页的预览、预设网格、自定义那一节（选的不是自定义时藏起来）和自定义里跟着别的开关显示 / 藏起来的两行。 */
    private GamepadPreview gpPreview;
    private GamepadGrid gpGrid;
    private String[] gpIds;
    private LinearLayout gpCustomBox;
    private View gpDpadTop, gpThumbs;
    private Switch gpBumpers, gpTriggers;

    /**
     * 手柄（手柄页的东西都在这：只在桌面模式能进，蓝牙模式下照样能先设好）：一块预览（选的那一套在分屏下半边的样子）、预设网格（最后一格自定义）、
     * 自定义那一节（选中自定义才显示）、摇杆 / 十字键 / 面键的手感、一段"Moonlight 里建议这样设"。改动即时存，回到主界面 applyPrefs 时
     * 换手柄页的样子、参数发给服务和本地分流（手柄两路都回中立）。
     */
    private void gamepadSection() {
        if (prefs.btMode()) {
            TextView note = text(getString(R.string.gp_desktop_only), 14, 0xFFE8B04A);
            note.setPadding(0, dp(16), 0, 0);
            list.addView(note);
        }
        header(R.string.sec_gp_preset);
        gpPreview = new GamepadPreview(this, true);
        list.addView(gpPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GamepadSpec[] presets = GamepadSpec.PRESETS;
        gpIds = new String[presets.length + 1];
        String[] names = new String[gpIds.length];
        for (int i = 0; i < presets.length; i++) {
            gpIds[i] = presets[i].id;
            names[i] = GamepadScreen.name(this, presets[i]);
        }
        gpIds[presets.length] = GamepadSpec.CUSTOM_ID;
        names[presets.length] = getString(R.string.gp_custom);
        gpGrid = new GamepadGrid(this, gpIds, names, this::pickGamepad);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = dp(12);
        list.addView(gpGrid, gp);
        gamepadCustom();

        GamepadConfig def = new GamepadConfig();
        header(R.string.sec_gp_stick);
        sliderInt(R.string.gp_stick_radius, Prefs.GP_STICK_R, Math.round(def.stickRadiusDp), 24, 120, 4);
        sliderInt(R.string.gp_deadzone, Prefs.GP_DEADZONE, Math.round(def.deadzone * 100), 0, 30, 1);
        sliderInt(R.string.gp_anti_deadzone, Prefs.GP_ANTI_DZ, Math.round(def.antiDeadzone * 100), 0, 30, 1);
        list.addView(text(getString(R.string.gp_anti_deadzone_hint), 14, 0xFF9AA0A6));
        toggle(R.string.gp_stick_float, Prefs.GP_FLOAT, def.floatingCenter);
        toggle(R.string.gp_stick_follow, Prefs.GP_FOLLOW, def.followBase);
        toggle(R.string.gp_rim_ticks, Prefs.GP_RIM_TICKS, def.rimTicks);

        header(R.string.sec_gp_dpad);
        toggle(R.string.gp_dpad8, Prefs.GP_DPAD8, def.dpad8);
        sliderInt(R.string.gp_diagonal, Prefs.GP_DIAG, Math.round(def.diagonalDeg), 10, 60, 5);
        sliderInt(R.string.gp_dpad_deadzone, Prefs.GP_DPAD_DZ, Math.round(def.dpadDeadzone * 100), 10, 50, 5);
        toggle(R.string.gp_dpad_ticks, Prefs.GP_DPAD_TICKS, def.dpadTicks);

        // 面键：簇里滑动换键（用户 2026-11-01 照推荐定的"搓键"，默认开）；开着才显示"两颗中间一起按"和"滑出多远全松"
        header(R.string.sec_gp_face);
        LinearLayout slideBox = new LinearLayout(this);
        slideBox.setOrientation(LinearLayout.VERTICAL);
        Switch slide = new Switch(this);
        slide.setText(R.string.gp_slide);
        slide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        slide.setChecked(prefs.getBool(Prefs.GP_SLIDE, def.clusterSlide));
        slide.setPadding(0, dp(8), 0, dp(8));
        slide.setOnCheckedChangeListener((b, on) -> {
            prefs.putBool(Prefs.GP_SLIDE, on);
            slideBox.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        list.addView(slide);
        list.addView(text(getString(R.string.gp_slide_hint), 14, 0xFF9AA0A6));
        LinearLayout outer = list;
        list = slideBox;
        sliderInt(R.string.gp_chord, Prefs.GP_CHORD, Math.round(def.chordDp), 0, 20, 1);
        sliderInt(R.string.gp_exit_dp, Prefs.GP_EXIT_DP, Math.round(def.clusterExitDp), 0, 60, 4);
        list = outer;
        list.addView(slideBox);
        slideBox.setVisibility(slide.isChecked() ? View.VISIBLE : View.GONE);

        header(R.string.sec_gp_moonlight);
        list.addView(text(getString(R.string.gp_moonlight_note), 14, 0xFF9AA0A6));
        refreshGamepad();
    }

    /**
     * 自定义那一节：从一套出发（存着的自定义；没存过是现在选的预设），勾左手放什么、面键怎么排、印什么字、右摇杆、肩键、扳机、中间三颗、摇杆按下；
     * 自动排（GamepadPlan），上面的预览跟着变。最下面"从某个预设重新开始"整套换成那个预设的样子。
     */
    private void gamepadCustom() {
        gpCustomBox = new LinearLayout(this);
        gpCustomBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout outer = list;
        list = gpCustomBox;
        header(R.string.gp_custom);
        list.addView(text(getString(R.string.gp_custom_hint), 14, 0xFF9AA0A6));
        GamepadSpec c = customOrTemplate();
        // 左手：十字键、左摇杆各一个开关，两个都开着时再选哪个在上
        final boolean[] left = {c.hasDpad(), c.hasLeftStick(), c.left == GamepadSpec.LEFT_DPAD_TOP};
        Runnable applyLeft = () -> editCustom(b -> b.left = left[0] && left[1]
                ? (left[2] ? GamepadSpec.LEFT_DPAD_TOP : GamepadSpec.LEFT_STICK_TOP)
                : left[0] ? GamepadSpec.LEFT_DPAD : left[1] ? GamepadSpec.LEFT_STICK : GamepadSpec.LEFT_NONE);
        customSwitch(getString(R.string.gp_c_dpad), left[0], on -> {
            left[0] = on;
            applyLeft.run();
        });
        customSwitch(getString(R.string.gp_c_lstick), left[1], on -> {
            left[1] = on;
            applyLeft.run();
        });
        gpDpadTop = customSwitch(getString(R.string.gp_c_dpad_top), left[2], on -> {
            left[2] = on;
            applyLeft.run();
        });
        String[] faces = new String[GP_FACES.length];
        int faceAt = 0;
        for (int i = 0; i < faces.length; i++) {
            faces[i] = getString(GP_FACE_NAMES[i]);
            if (GP_FACES[i] == c.face) faceAt = i;
        }
        choiceRow(getString(R.string.gp_c_face), faces, faceAt, i -> editCustom(b -> b.face = GP_FACES[i]));
        String[] styles = {getString(R.string.gp_labels_xbox), getString(R.string.gp_labels_nintendo), getString(R.string.gp_labels_ps),
                getString(R.string.gp_labels_sega), getString(R.string.gp_labels_arcade), getString(R.string.gp_labels_number)};
        int styleAt = 0;
        for (int i = 0; i < GP_LABELS.length; i++) if (GP_LABELS[i] == c.labels) styleAt = i;
        choiceRow(getString(R.string.gp_c_labels), styles, styleAt, i -> editCustom(b -> b.labels = GP_LABELS[i]));
        customSwitch(getString(R.string.gp_c_rstick), c.rightStick, on -> editCustom(b -> b.rightStick = on));
        // 字写"靠里那对 / 外角那对"和这一套里实际叫什么（refreshGamepad 跟着字样改）：Wii 经典手柄里靠里那对叫 ZL ZR、外角那对叫 L R，写死字母会正好说反
        gpBumpers = customSwitch("", c.l1 || c.r1, on -> editCustom(b -> b.l1 = b.r1 = on));
        gpTriggers = customSwitch("", c.lt || c.rt, on -> editCustom(b -> b.lt = b.rt = on));
        customSwitch("Select", c.select, on -> editCustom(b -> b.select = on));   // 键名不翻译
        customSwitch(getString(R.string.gp_c_home), c.home, on -> editCustom(b -> b.home = on));
        customSwitch("Start", c.start, on -> editCustom(b -> b.start = on));
        gpThumbs = customSwitch(getString(R.string.gp_c_thumbs), c.thumbs, on -> editCustom(b -> b.thumbs = on));
        TextView from = text(getString(R.string.gp_custom_from), 16, 0xFF8AB4F8);
        from.setPadding(0, dp(12), 0, dp(12));
        from.setOnClickListener(v -> {
            GamepadSpec[] presets = GamepadSpec.PRESETS;
            String[] names = new String[presets.length];
            for (int i = 0; i < presets.length; i++) names[i] = GamepadScreen.name(this, presets[i]);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.gp_custom_from)
                    .setItems(names, (d, which) -> {
                        prefs.setGamepadCustom(presets[which].asCustom());
                        prefs.setGamepadPreset(GamepadSpec.CUSTOM_ID);
                        rebuildLater();   // 上面的开关照新的一套重建
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        list.addView(from);
        list = outer;
        list.addView(gpCustomBox);
    }

    /** 存着的自定义；没存过是现在选的那一套复制出来的样子（选"自定义"那一格时就从它开始）。 */
    private GamepadSpec customOrTemplate() {
        GamepadSpec c = prefs.gamepadCustom();
        return c != null ? c : prefs.gamepadSpec().asCustom();
    }

    /** 改自定义的一项：存下来、选中自定义，预览和网格跟着变。 */
    private void editCustom(java.util.function.Consumer<GamepadSpec.Builder> change) {
        GamepadSpec.Builder b = new GamepadSpec.Builder(customOrTemplate());
        change.accept(b);
        prefs.setGamepadCustom(b.build());
        prefs.setGamepadPreset(GamepadSpec.CUSTOM_ID);
        refreshGamepad();
    }

    /** 网格里点了一格：选那一套；第一次选"自定义"时从现在选的预设复制一份，自定义那一节照它重建。 */
    private void pickGamepad(String id) {
        if (GamepadSpec.CUSTOM_ID.equals(id) && prefs.gamepadCustom() == null) {
            prefs.setGamepadCustom(prefs.gamepadSpec().asCustom());
            prefs.setGamepadPreset(id);
            rebuildLater();
            return;
        }
        prefs.setGamepadPreset(id);
        refreshGamepad();
    }

    /** 预览、网格按现在选的、主题、按键边框重画；自定义那一节选中自定义才显示，里面"十字键放上面""摇杆能按下"有那两样才显示。 */
    private void refreshGamepad() {
        if (gpGrid == null) return;
        Theme t = Theme.byId(prefs.theme());
        boolean borders = prefs.keyBorders();
        GamepadSpec selected = prefs.gamepadSpec();
        gpPreview.show(selected, t, borders);
        GamepadSpec custom = customOrTemplate();
        GamepadSpec[] specs = new GamepadSpec[gpIds.length];
        for (int i = 0; i < GamepadSpec.PRESETS.length; i++) specs[i] = GamepadSpec.PRESETS[i];
        specs[specs.length - 1] = custom;
        gpGrid.show(specs, prefs.gamepadPreset(), t, borders);
        gpCustomBox.setVisibility(selected.isCustom() ? View.VISIBLE : View.GONE);
        gpDpadTop.setVisibility(custom.hasDpad() && custom.hasLeftStick() ? View.VISIBLE : View.GONE);
        gpThumbs.setVisibility(custom.hasLeftStick() || custom.rightStick ? View.VISIBLE : View.GONE);
        gpBumpers.setText(getString(R.string.gp_c_bumpers,
                custom.shoulderLabel(Proto.GP_L1), custom.shoulderLabel(Proto.GP_R1)));
        gpTriggers.setText(getString(R.string.gp_c_triggers,
                custom.shoulderLabel(Proto.GP_LT), custom.shoulderLabel(Proto.GP_RT)));
    }

    /** 自定义里的一个开关（不直接存 Prefs，交给 onChange）。 */
    private Switch customSwitch(String label, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(checked);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((b, on) -> onChange.accept(on));
        list.addView(s);
        return s;
    }

    /** 几选一（名字 + 选中的写在右边，点一下弹单选框），同 {@link #actionRow} 的样子。 */
    private void choiceRow(String label, String[] options, int checked, IntConsumer onPick) {
        choiceRow(label, options, null, checked, onPick);
    }

    /** 同上，enabled[i] 假的那一项灰掉、点不了（null = 都能选）；写原因由调用方写进 options[i]。 */
    private void choiceRow(String label, String[] options, boolean[] enabled, int checked, IntConsumer onPick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView name = text(label, 16, 0xFFE8E8E8);
        TextView value = text(options[checked], 15, 0xFF8AB4F8);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        int[] current = {checked};
        row.setOnClickListener(v -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(label).setNegativeButton(R.string.cancel, null);
            android.content.DialogInterface.OnClickListener pick = (d, which) -> {
                if (enabled != null && !enabled[which]) return;
                d.dismiss();
                current[0] = which;
                value.setText(options[which]);
                onPick.accept(which);
            };
            if (enabled == null) {
                b.setSingleChoiceItems(options, current[0], pick);
            } else {
                // 灰掉的项：适配器说它不能点（ListView 不派点击）、画成禁用的样子
                ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, options) {
                    @Override
                    public boolean areAllItemsEnabled() {
                        return false;
                    }

                    @Override
                    public boolean isEnabled(int position) {
                        return enabled[position];
                    }

                    @Override
                    public View getView(int position, View convert, ViewGroup parent) {
                        View view = super.getView(position, convert, parent);
                        view.setEnabled(enabled[position]);
                        return view;
                    }
                };
                b.setSingleChoiceItems(a, current[0], pick);
            }
            b.show();
        });
        list.addView(row);
    }

    // ═══════════ 空中鼠标 ═══════════

    /** 只在蓝牙模式：灵敏度、消抖参数、回正中说明、空中鼠标页的按钮。桌面模式下这一页只有一句"只在蓝牙模式下能用"（连接方式在"通用"页）。 */
    private void airSection() {
        header(R.string.sec_air);
        if (!prefs.btMode()) {
            list.addView(text(getString(R.string.air_bt_only), 14, 0xFF9AA0A6));
            return;
        }
        sliderInt(R.string.air_sensitivity, "air_sens", 6, 1, 10, 1);
        // 消抖（AirPointer 的 1€ 滤波 + 软死区），用户自己调；范围比默认值两边都放得很宽
        AirPointer.Config def = new AirPointer.Config();
        airSpace(def.space);
        int space = prefs.getInt(Prefs.AIR_SPACE, def.space);
        float[] fix = AirPointer.defaultFix(space);
        slider(R.string.air_fix_v, Prefs.airFixKey(space, true), fix[0], -20f, 20f, 0.5f, "%+.1f°");
        slider(R.string.air_fix_h, Prefs.airFixKey(space, false), fix[1], -20f, 20f, 0.5f, "%+.1f°");
        slider(R.string.air_min_cutoff, Prefs.AIR_MIN_CUTOFF, def.minCutoffHz, 0.1f, 5f, 0.1f, "%.1f Hz");
        slider(R.string.air_beta, Prefs.AIR_BETA, def.beta, 0f, 5f, 0.1f, "%.1f");
        slider(R.string.air_speed_cutoff, Prefs.AIR_SPEED_CUTOFF, def.speedCutoffHz, 0.2f, 5f, 0.1f, "%.1f Hz");
        slider(R.string.air_deadzone, Prefs.AIR_DEADZONE, def.tightenDps, 0f, 15f, 0.5f, "%.1f °/s");
        slider(R.string.air_path, Prefs.AIR_PATH, def.pathSmoothMs, 0f, 400f, 10f, "%.0f ms");
        slider(R.string.air_string, Prefs.AIR_STRING, def.stringDeg, 0f, 1.5f, 0.05f, "%.2f°");
        TextView resetFilter = text(getString(R.string.air_filter_reset), 16, 0xFF8AB4F8);
        resetFilter.setPadding(0, dp(12), 0, dp(12));
        resetFilter.setOnClickListener(v -> {
            prefs.resetAirFilter();
            rebuildLater();
        });
        list.addView(resetFilter);
        hostAspect();
        TextView note = text(getString(R.string.air_abs_note), 14, 0xFF9AA0A6);
        note.setPadding(0, dp(6), 0, dp(6));
        list.addView(note);
        airButtons();
    }

    /** 方向换算：手机怎么转算左右、怎么转算上下（AirPointer.SPACE_*），凭手感选。 */
    private void airSpace(int def) {
        list.addView(text(getString(R.string.air_space), 16, 0xFFE8E8E8));
        int[] values = {AirPointer.SPACE_PLAYER, AirPointer.SPACE_OBLIQUE, AirPointer.SPACE_WORLD};
        int[] labels = {R.string.air_space_player, R.string.air_space_oblique, R.string.air_space_world};
        int now = prefs.getInt(Prefs.AIR_SPACE, def);
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (values[i] == now) b.setChecked(true);
            final int v = values[i];
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (!checked) return;
                prefs.putInt(Prefs.AIR_SPACE, v);
                rebuildLater();   // 下面的角度校正按换算分开存：换了换算要重画
            });
        }
        list.addView(g);
    }

    /** 连着的这台（没连就上次那台）的屏幕比例：激光笔式里上下走多快按它算。没连过任何主机就不显示。 */
    private void hostAspect() {
        BluetoothDevice host = bt.connectedDevice();
        if (host == null) host = bt.preferredHost();
        if (host == null) return;
        final String address = host.getAddress();
        list.addView(text(getString(R.string.air_aspect, BtHid.deviceName(host)), 16, 0xFFE8E8E8));
        String[] labels = {getString(R.string.air_aspect_auto), "16:9", "16:10", "3:2", "4:3", "21:9"};
        float[] values = {0f, 16f / 9, 16f / 10, 3f / 2, 4f / 3, 21f / 9};
        float now = prefs.hostAspect(address);
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (Math.abs(values[i] - now) < 1e-3) b.setChecked(true);
            final float v = values[i];
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) prefs.setHostAspect(address, v);
            });
        }
        list.addView(g);
        // 按分辨率填（宽 × 高）：比例对不上常见档位的屏（比如串流时切成 2816×1940）
        list.addView(text(getString(R.string.air_aspect_custom), 15, 0xFF9AA0A6));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] wh = prefs.hostAspectWh(address).split("x");
        EditText w = numberField(wh.length == 2 ? wh[0] : ""), h = numberField(wh.length == 2 ? wh[1] : "");
        TextView times = text("×", 18, 0xFFE8E8E8);
        times.setPadding(dp(12), 0, dp(12), 0);
        row.addView(w);
        row.addView(times);
        row.addView(h);
        list.addView(row);
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                int ww = parsePositive(w.getText().toString()), hh = parsePositive(h.getText().toString());
                if (ww <= 0 || hh <= 0) return;
                g.clearCheck();   // 填了分辨率：档位一个都不选
                prefs.setHostAspect(address, ww / (float) hh);
                prefs.setHostAspectWh(address, ww + "x" + hh);
            }
        };
        w.addTextChangedListener(watcher);
        h.addTextChangedListener(watcher);
    }

    private EditText numberField(String value) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setMinEms(4);
        e.setTextColor(0xFFE8E8E8);
        return e;
    }

    private static int parsePositive(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 空中鼠标页的按钮：每行两个，加减整行；圆钮固定，只选放在哪四个格子中间（按格子编号说，和页面上一样从上往下数）。 */
    private void airButtons() {
        list.addView(text(getString(R.string.air_buttons), 16, 0xFFE8E8E8));
        int rows = prefs.airRows();
        for (int i = 0; i < rows * 2; i++) {
            final int index = i;
            LinearLayout row = actionRow(getString(R.string.nth, i + 1), () -> prefs.airSlot(index), id -> prefs.setAirSlot(index, id));
            if (i % 2 == 1 && rows > Prefs.AIR_ROWS_MIN) {   // 每行第二个后面放 ✕：删掉这一行
                TextView remove = text("✕", 18, 0xFF9A9A9A);
                remove.setPadding(dp(16), 0, dp(4), 0);
                final int r = i / 2;
                remove.setOnClickListener(v -> {
                    prefs.removeAirRow(r);
                    rebuildLater();
                });
                row.addView(remove);
            }
        }
        if (rows < Prefs.AIR_ROWS_MAX) {
            TextView add = text(getString(R.string.air_row_add, Prefs.AIR_ROWS_MAX), 16, 0xFF8AB4F8);
            add.setPadding(0, dp(12), 0, dp(12));
            add.setOnClickListener(v -> {
                prefs.addAirRow();
                rebuildLater();
            });
            list.addView(add);
        }
        list.addView(text(getString(R.string.air_round_at), 16, 0xFFE8E8E8));
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        int at = prefs.airRoundAt();
        for (int k = 1; k < rows; k++) {
            RadioButton b = new RadioButton(this);
            b.setText(getString(R.string.air_round_between, 2 * k - 1, 2 * k, 2 * k + 1, 2 * k + 2));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setId(View.generateViewId());
            g.addView(b);
            if (k == at) b.setChecked(true);
            final int pos = k;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) prefs.setAirRoundAt(pos);
            });
        }
        list.addView(g);
    }

    /**
     * 音量键（两种模式都有）：点一下 / 按住各选一个动作，"无"不截。
     * 蓝牙模式下由 App 自己收；桌面模式下要服务独占按键设备（server.HardKeyGate），所以多一句服务的说明。
     */
    private void hardKeysSection() {
        header(R.string.sec_hard_keys);
        list.addView(text(getString(R.string.hard_keys_hint), 14, 0xFF9AA0A6));
        if (!prefs.btMode()) list.addView(text(getString(R.string.hard_keys_service_hint), 14, 0xFF9AA0A6));
        int[][] labels = {{R.string.hk_up_tap, R.string.hk_up_hold}, {R.string.hk_down_tap, R.string.hk_down_hold}};
        for (int k = 0; k < 2; k++) {
            for (int h = 0; h < 2; h++) {
                final int key = k;
                final boolean hold = h == 1;
                actionRow(getString(labels[k][h]), () -> prefs.hardKey(key, hold), id -> prefs.setHardKey(key, hold, id));
            }
        }
    }

    // ═══════════ 对方还连着手机的音频 ═══════════
    //
    // 电脑之类配对时常把自己当耳机，顺带连上手机的通话（HFP）和媒体音频（A2DP）。这时蓝牙按耳机省电，
    // 链路几百毫秒才通一次，键鼠报文排队：一卡一卡、停手后光标还在走（2026-10-14 实测，见 docs/decisions.md）。
    // App 没权限替用户断开这两项，只能提醒去系统蓝牙设置里关。

    private void openAudioProxies() {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) return;
        if (headset == null) adapter.getProfileProxy(this, audioProxies, BluetoothProfile.HEADSET);
        if (a2dp == null) adapter.getProfileProxy(this, audioProxies, BluetoothProfile.A2DP);
    }

    private void closeAudioProxies() {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter != null && headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        headset = null;
        a2dp = null;
    }

    private final BluetoothProfile.ServiceListener audioProxies = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (!started) {   // 这页已经不可见了：用不上，还回去
                BluetoothManager bm = getSystemService(BluetoothManager.class);
                if (bm != null && bm.getAdapter() != null) bm.getAdapter().closeProfileProxy(profile, proxy);
                return;
            }
            if (profile == BluetoothProfile.HEADSET) headset = proxy;
            else if (profile == BluetoothProfile.A2DP) a2dp = proxy;
            refreshAudioWarning();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) headset = null;
            else if (profile == BluetoothProfile.A2DP) a2dp = null;
            refreshAudioWarning();
        }
    };

    /** 连着的那台（没连上就看上次那台）还连着手机的通话或媒体音频：显示提醒，点一下打开系统蓝牙设置。 */
    private void refreshAudioWarning() {
        if (btAudioWarning == null) return;
        BluetoothDevice host = bt.connectedDevice();
        if (host == null) host = bt.preferredHost();
        boolean on = host != null && (audioConnected(headset, host) || audioConnected(a2dp, host));
        if (on) btAudioWarning.setText(getString(R.string.bt_audio_warning, BtHid.deviceName(host)));
        btAudioWarning.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private static boolean audioConnected(BluetoothProfile profile, BluetoothDevice device) {
        try {
            return profile != null && profile.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException e) {
            return false;   // 还没给"附近的设备"权限：查不了，不提醒
        }
    }

    private void requestBtPermissions() {
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 2);
    }

    /** 选图回来（背景图：照片选择器 / 文件选择器）。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        padBg.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ServiceSection.REQ_NOTIFICATIONS) {
            service.onNotificationPermissionResult();
            return;
        }
        bt.retry();
        refreshBtDevices();
    }

    private void hapticChoice() {
        levels(Prefs.HAPTIC_LABELS, prefs.haptic(), level -> {
            prefs.setHaptic(level);
            Haptics h = new Haptics(this);   // 选了就震一下，直接感受强度
            h.setLevel(level);
            h.play(GestureEngine.HAPTIC_CLICK);
        });
    }

    /** 横排的几档（关 / 小 / 中 / 大 这类），选中时回调下标。 */
    private void levels(int[] labels, int current, IntConsumer onPick) {
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setId(View.generateViewId());
            g.addView(b, new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i == current) b.setChecked(true);
            final int value = i;
            b.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) onPick.accept(value);
            });
        }
        list.addView(g);
    }

    interface Getter {
        String get();
    }

    interface Setter {
        void set(String actionId);
    }

    /**
     * 选一个动作。所有能自定义的位置（自定义按键、角上按钮、三指四指手势、键盘最下面一排）都用这一份清单，
     * 最后一项是"自定义组合键"。
     */
    private LinearLayout actionRow(String label, Getter getter, Setter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView name = text(label, 16, 0xFFE8E8E8);
        TextView value = text(Actions.get(getter.get()).label(this), 15, 0xFF8AB4F8);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        Setter save = id -> {
            setter.set(id);
            value.setText(Actions.get(id).label(this));
        };
        row.setOnClickListener(v -> {
            List<Actions.Action> all = Actions.all();
            String[] labels = new String[all.size() + 1];
            int checked = -1;
            String current = getter.get();
            for (int i = 0; i < all.size(); i++) {
                labels[i] = all.get(i).label(this);
                if (all.get(i).id.equals(current)) checked = i;
            }
            labels[all.size()] = getString(R.string.custom_combo);
            if (checked < 0) checked = all.size();   // 当前是自定义组合键
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        d.dismiss();
                        if (which == all.size()) customCombo(label, save);
                        else save.set(all.get(which).id);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        list.addView(row);
        return row;
    }

    /** 自己勾修饰键、挑一个键；按钮上显示的字不填就用 "Ctrl+Shift+T" 这样的名字。 */
    private void customCombo(String title, Setter save) {
        comboDialog(getString(R.string.custom_combo_title, title), new String[]{"Ctrl", "Shift", "Alt", "Super"}, true, 0,
                (key, text) -> save.set(Actions.combo(key, text.isEmpty() ? Combos.describe(key) : text)));
    }

    /** 组合键对话框点了"确定"：key = 用法码 | 修饰键 << 8（Combos 的位），text = 填的名字（没有名字框时是 ""）。 */
    interface ComboPicked {
        void on(int key, String text);
    }

    /**
     * 组合键对话框：四个修饰键（Combos 的位顺序 Ctrl / Shift / Alt / Super，modNames 是它们写什么）、挑一个键（Combos.KEY_LABELS），
     * withName 时多一个"按钮上写什么"的框。initial ≠ 0 时照它预先勾好、选好键（键不在 Combos 的清单里就停在第一个）。
     */
    private void comboDialog(String title, String[] modNames, boolean withName, int initial, ComboPicked onOk) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        LinearLayout mods = new LinearLayout(this);
        CheckBox[] checks = new CheckBox[4];
        int initialMods = (initial >> 8) & 0xFF;
        for (int i = 0; i < 4; i++) {
            checks[i] = new CheckBox(this);
            checks[i].setText(modNames[i]);
            checks[i].setChecked((initialMods & (1 << i)) != 0);   // Combos.CTRL / SHIFT / ALT / WIN = 1 / 2 / 4 / 8
            mods.addView(checks[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(mods);
        Spinner keys = new Spinner(this);
        keys.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Combos.KEY_LABELS));
        for (int i = 0; i < Combos.KEY_USAGES.length; i++) {
            if (initial != 0 && Combos.KEY_USAGES[i] == (initial & 0xFF)) keys.setSelection(i);
        }
        box.addView(keys);
        EditText name = null;
        if (withName) {
            name = new EditText(this);
            name.setHint(R.string.custom_combo_hint);
            box.addView(name);
        }
        EditText nameBox = name;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    int key = Combos.combine(Combos.KEY_USAGES[keys.getSelectedItemPosition()],
                            checks[0].isChecked(), checks[1].isChecked(), checks[2].isChecked(), checks[3].isChecked());
                    onOk.on(key, nameBox == null ? "" : nameBox.getText().toString().trim());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    interface IntCallback {
        void on(int progress);
    }

    /** 只关心拖动中的数值变化。 */
    private static final class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final IntCallback cb;

        SimpleSeek(IntCallback cb) {
            this.cb = cb;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) cb.on(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
