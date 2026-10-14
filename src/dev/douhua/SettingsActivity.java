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

/** 设置页。改动即时保存，回到触控板时生效。 */
public final class SettingsActivity extends Activity implements BtHid.Listener {
    private Prefs prefs;
    private LinearLayout list;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        density = getResources().getDisplayMetrics().density;
        bt = BtHid.get(this);
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
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
        unregisterReceiver(bondReceiver);
        closeAudioProxies();
        if (btAcquired) {
            bt.release(this);
            btAcquired = false;
        }
        super.onStop();
    }

    /** 选了蓝牙模式就用着 BtHid（显示状态、能切设备）；切回 DeX 就立刻停掉。 */
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

    private void build() {
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(list);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
        setContentView(scroll);

        GestureEngine.Config def = new GestureEngine.Config();

        list.addView(text(getString(R.string.settings_title), 22, 0xFFE8E8E8));
        header(R.string.sec_connection);
        connection();
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
        choice(R.string.ticks, "ticks", def.scrollTicks,
                new int[]{R.string.ticks_off, R.string.ticks_edge, R.string.ticks_all});
        list.addView(text(getString(R.string.tick_strength), 16, 0xFFE8E8E8));
        levels(Prefs.TICK_STRENGTH_LABELS, prefs.tickStrength(), strength -> {
            prefs.setTickStrength(strength);
            Haptics h = new Haptics(this);   // 选了就连震几下，像滚了几格
            h.setLevel(prefs.haptic());
            h.setTickStrength(strength);
            for (int i = 0; i < 4; i++) list.postDelayed(() -> h.play(GestureEngine.HAPTIC_TICK), i * 70L);
        });

        header(R.string.sec_haptics);
        hapticChoice();
        list.addView(text(getString(R.string.drag_hum), 16, 0xFFE8E8E8));
        levels(Prefs.HAPTIC_LABELS, prefs.dragHum(), level -> {
            prefs.setDragHum(level);
            Haptics h = new Haptics(this);   // 选了就试震 0.6 秒
            h.setLevel(prefs.haptic());
            h.setDragHum(level);
            h.startHum();
            list.postDelayed(h::stopHum, 600);
        });

        header(R.string.sec_gestures);
        for (int i = 0; i < Prefs.GESTURE_KEYS.length; i++) {
            final int index = i;
            actionRow(getString(Prefs.GESTURE_LABELS[i]), () -> prefs.gesture(index), id -> prefs.setGesture(index, id));
        }

        header(R.string.sec_strip);
        toggle(R.string.strip_hidden, "strip_hidden", false);
        for (int i = 0; i < Prefs.SLOT_COUNT; i++) {
            final int index = i;
            actionRow(getString(R.string.nth, i + 1), () -> prefs.slot(index), id -> prefs.setSlot(index, id));
        }

        header(R.string.sec_corners);
        for (int i = 0; i < Prefs.CORNER_COUNT; i++) {
            final int index = i;
            actionRow(getString(Prefs.CORNER_LABELS[i]), () -> prefs.corner(index), id -> prefs.setCorner(index, id));
        }

        header(R.string.sec_kb_row);
        for (int i = 0; i < Prefs.KEYBOARD_ROW_COUNT; i++) {
            final int index = i;
            actionRow(getString(R.string.nth, i + 1), () -> prefs.keyboardRow(index), id -> prefs.setKeyboardRow(index, id));
        }

        header(0);
        TextView reset = text(getString(R.string.reset_all), 16, 0xFFE07A5F);
        reset.setPadding(0, dp(12), 0, dp(12));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.reset, (d, w) -> {
                    prefs.resetAll();
                    build();
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        list.addView(reset);
        version();
    }

    // ═══════════ 版本号（彩蛋） ═══════════

    /** 连点几下版本号算"啪啪啪"：每下离上一下不超过这么久。 */
    private static final long EGG_TAP_GAP_MS = 600;
    private static final int EGG_TAPS = 5;
    private int eggTaps;
    private long lastEggTap;

    /** 最下面一行"豆花儿 0.1.1"。快速连点 5 下蹦出一只睡着的小鸟。 */
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
                list.post(this::build);   // 蓝牙那几项跟着出现 / 消失
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
            default: res = R.string.bt_state_starting; break;
        }
        btStatus.setText(getString(res, deviceName));
        if (state == BtHid.STATE_CONNECTED || state == BtHid.STATE_WAITING) refreshBtDevices();
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
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
     * 选一个动作。所有能自定义的位置（按钮条、角上按钮、三指四指手势、键盘最下面一排）都用这一份清单，
     * 最后一项是"自定义组合键"。
     */
    private void actionRow(String label, Getter getter, Setter setter) {
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
