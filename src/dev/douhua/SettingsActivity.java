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
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
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
 * 分五个标签页（顶上一排标签固定，不随页面滚动）：通用、触控板、按钮与按键、空中鼠标、外观。
 * 每次 {@link #build} 五页的内容都照旧全建出来，只显示选中那一页（其它 GONE）：豆花儿服务一节（ServiceSection 的观察者、实时刷新）、
 * 蓝牙设备列表、音频提醒都引用建好的 view，只建当前页会漏引用；切页只改显示，不重建，进行到一半的东西（配对引导、填了一半的分辨率）都在。
 */
public final class SettingsActivity extends Activity implements BtHid.Listener {
    /** 标签页的 id：存进 Prefs（记住上次停在哪一页），入口用 {@link #EXTRA_TAB} 指定；顺序就是标签的顺序。 */
    static final String TAB_GENERAL = "general";
    static final String TAB_PAD = "pad";
    static final String TAB_BUTTONS = "buttons";
    static final String TAB_AIR = "air";
    static final String TAB_LOOK = "look";
    private static final String[] TABS = {TAB_GENERAL, TAB_PAD, TAB_BUTTONS, TAB_AIR, TAB_LOOK};
    private static final int[] TAB_LABELS = {R.string.tab_general, R.string.tab_pad, R.string.tab_buttons, R.string.tab_air, R.string.tab_look};
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
        return new Intent(c, SettingsActivity.class).putExtra(EXTRA_TAB, TAB_GENERAL);
    }

    private Prefs prefs;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        density = getResources().getDisplayMetrics().density;
        bt = BtHid.get(this);
        service = new ServiceSection(this, prefs);
        padBg = new PadBgSection(this, prefs);
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

    /** 每一页从上到下有哪些节；五页都建，只显示 {@link #tab} 那一页。 */
    private void build() {
        pagesBox.removeAllViews();
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
                    updateBtUse();   // 恢复默认就是桌面模式：蓝牙键鼠立刻停
                    build();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        list.addView(reset);
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

        // 按钮与按键：自定义按键（左边一列）、键盘最下面一排、音量键
        list = pages[2];
        header(R.string.sec_strip);
        customKeys();

        header(R.string.sec_kb_row);
        for (int i = 0; i < Prefs.KEYBOARD_ROW_COUNT; i++) {
            final int index = i;
            actionRow(getString(R.string.nth, i + 1), () -> prefs.keyboardRow(index), id -> prefs.setKeyboardRow(index, id));
        }
        hardKeysSection();

        // 空中鼠标
        list = pages[3];
        airSection();

        // 外观：主题、按键边框……，触控板背景图，震动
        list = pages[4];
        appearance();
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
        }));
        list.addView(title);
        list.addView(bar);
    }

    private void sliderInt(int labelRes, String key, int def, int min, int max, int step) {
        String label = getString(labelRes);
        TextView title = text("", 16, 0xFFE8E8E8);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        int value = prefs.getInt(key, def);
        bar.setProgress((value - min) / step);
        title.setText(getString(R.string.value_format, label, String.valueOf(value)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(p -> {
            int v = min + p * step;
            prefs.putInt(key, v);
            title.setText(getString(R.string.value_format, label, String.valueOf(v)));
        }));
        list.addView(title);
        list.addView(bar);
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
                Prefs.STICK_SPEED_STEP, "%.1f");
        toggle(R.string.stick_tap, Prefs.STICK_TAP, def.stickTap);
        toggle(R.string.stick_inertia, Prefs.STICK_INERTIA, def.stickInertia);
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
    // 链路几百毫秒才通一次，键鼠报文排队：一卡一卡、停手后光标还在走（2026-10-14 实测，见 CLAUDE.md）。
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        LinearLayout mods = new LinearLayout(this);
        String[] modNames = {"Ctrl", "Shift", "Alt", "Super"};
        CheckBox[] checks = new CheckBox[4];
        for (int i = 0; i < 4; i++) {
            checks[i] = new CheckBox(this);
            checks[i].setText(modNames[i]);
            mods.addView(checks[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(mods);
        Spinner keys = new Spinner(this);
        keys.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Combos.KEY_LABELS));
        box.addView(keys);
        EditText name = new EditText(this);
        name.setHint(R.string.custom_combo_hint);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.custom_combo_title, title))
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    int key = Combos.combine(Combos.KEY_USAGES[keys.getSelectedItemPosition()],
                            checks[0].isChecked(), checks[1].isChecked(), checks[2].isChecked(), checks[3].isChecked());
                    String text = name.getText().toString().trim();
                    save.set(Actions.combo(key, text.isEmpty() ? Combos.describe(key) : text));
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
