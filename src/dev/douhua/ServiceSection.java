package dev.douhua;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * 设置页"通用"页最上面一节"豆花儿服务"：手机自己拉起服务要满足的每一项，实时显示有没有满足，没满足的给一句说明和一个直达系统页面的按钮；
 * 配对的分步引导（含公共网络别选"始终允许"）、自动启动开关和退避说明、常驻提醒（无线调试开着时提醒关掉）。
 *
 * 各项怎么判断（都不要位置权限）：
 * - Wi-Fi：ConnectivityManager 的 NetworkCallback，只认 TRANSPORT_WIFI 的网络（不要求能上网：同一个路由器下就能无线调试）；不读网络名。
 * - 开发者选项 / USB 调试 / 无线调试：Settings.Global 的 development_settings_enabled / adb_enabled / adb_wifi_enabled，
 *   ContentObserver 盯着。adb_wifi_enabled 是 @hide 但标了 @Readable，普通 App 读得到。
 * - 配对、服务、启动进度：{@link SelfStart}（它的 Listener 通知变化，服务状态靠它连一次端口读 hello）。
 * 观察者只在设置页可见时（onStart 到 onStop）挂着。
 */
final class ServiceSection implements SelfStart.Listener {
    private static final String KEY_DEV = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED;
    private static final String KEY_ADB = Settings.Global.ADB_ENABLED;
    /** Settings.Global.ADB_WIFI_ENABLED 是隐藏常量，只能写字面值。 */
    private static final String KEY_ADB_WIFI = "adb_wifi_enabled";
    /** AOSP 开发者选项里"USB 调试""无线调试"两项的 key：带上它打开开发者选项会滚到并高亮那一项（三星不认也无害）。 */
    private static final String PREF_USB = "enable_adb";
    private static final String PREF_WIRELESS = "toggle_adb_wireless";
    private static final String EXTRA_HIGHLIGHT = ":settings:fragment_args_key";
    /** 设置页 onRequestPermissionsResult 用来认出通知权限的请求。 */
    static final int REQ_NOTIFICATIONS = 3;
    /**
     * 通知里"去关掉无线调试"按钮打开设置页时带的 extra：设置页一打开就转去无线调试那一页（{@link #openWirelessSettings}）。
     * 绕设置页一下是为了复用 {@link #open} 的退路（开发者选项打不开就退到"关于手机"），PendingIntent 自己没法试第二个。
     */
    static final String EXTRA_OPEN_WIRELESS = "dev.douhua.open_wireless";

    private static final int WHITE = 0xFFE8E8E8;
    private static final int GRAY = 0xFF9AA0A6;
    private static final int LINK = 0xFF8AB4F8;
    private static final int AMBER = 0xFFF2B866;
    private static final int GREEN = 0xFF81C995;
    private static final int RED = 0xFFE07A5F;

    private final Activity activity;
    private final Prefs prefs;
    private final SelfStart selfStart;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager cm;

    private Row wifiRow, devRow, usbRow, wirelessRow, pairRow, serviceRow;
    private TextView notifWarning, resultLine, untrustedWarning, pausedLine;
    private LinearLayout guide;
    private Switch autoSwitch;

    private boolean started;
    /** 现在连着的 Wi-Fi 网络（NetworkCallback 维护）；回调还没来之前用 {@link #wifiGuess}。 */
    private final Set<Network> wifiNetworks = new HashSet<>();
    private boolean wifiCallbackSeen;

    ServiceSection(Activity activity, Prefs prefs) {
        this.activity = activity;
        this.prefs = prefs;
        this.selfStart = SelfStart.get(activity);
        this.density = activity.getResources().getDisplayMetrics().density;
        this.cm = activity.getSystemService(ConnectivityManager.class);
    }

    // ═══════════ 生命周期（跟着设置页 onStart / onStop） ═══════════

    void start() {
        if (started) return;
        started = true;
        android.content.ContentResolver cr = activity.getContentResolver();
        cr.registerContentObserver(Settings.Global.getUriFor(KEY_DEV), false, settingsObserver);
        cr.registerContentObserver(Settings.Global.getUriFor(KEY_ADB), false, settingsObserver);
        cr.registerContentObserver(Settings.Global.getUriFor(KEY_ADB_WIFI), false, settingsObserver);
        wifiNetworks.clear();
        wifiCallbackSeen = false;
        if (cm != null) {
            // 默认的 NetworkRequest 要求能上网、不是 VPN；无线调试只要连着 Wi-Fi，去掉"能上网"
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            try {
                cm.registerNetworkCallback(req, wifiCallback, main);
            } catch (RuntimeException e) {
                android.util.Log.w("douhua", "盯不了 Wi-Fi 状态：" + e);
            }
        }
        selfStart.addListener(this);
        selfStart.probeService();
        refresh();
    }

    void stop() {
        if (!started) return;
        started = false;
        activity.getContentResolver().unregisterContentObserver(settingsObserver);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
                // 没注册上
            }
        }
        selfStart.removeListener(this);
        main.removeCallbacks(probeLater);
    }

    private final ContentObserver settingsObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refresh();
            // 关掉 USB 调试 / 开发者选项会把服务带走：过一会儿重新看服务还在不在
            if (uri != null && !uri.equals(Settings.Global.getUriFor(KEY_ADB_WIFI))) {
                main.removeCallbacks(probeLater);
                main.postDelayed(probeLater, 1500);
            }
        }
    };

    private final Runnable probeLater = new Runnable() {
        @Override
        public void run() {
            if (started) selfStart.probeService();
        }
    };

    private final ConnectivityManager.NetworkCallback wifiCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            wifiCallbackSeen = true;
            wifiNetworks.add(network);
            refresh();
        }

        @Override
        public void onLost(Network network) {
            wifiCallbackSeen = true;
            wifiNetworks.remove(network);
            refresh();
        }
    };

    @Override
    public void onSelfStartChanged() {
        refresh();
    }

    /** 通知权限对话框答完（设置页转过来）：没给就直接带去系统的通知设置。 */
    void onNotificationPermissionResult() {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            openNotificationSettings();
        }
        refresh();
    }

    // ═══════════ 界面 ═══════════

    /** 设置页每次重建都调：往 list 里加这一节的全部行。 */
    void build(LinearLayout list) {
        TextView title = text(activity.getString(R.string.sec_service), 13, GRAY);
        title.setPadding(0, dp(24), 0, dp(6));
        list.addView(title);
        list.addView(text(activity.getString(R.string.svc_intro), 14, GRAY));

        wifiRow = new Row(list, R.string.svc_wifi, R.string.svc_wifi_hint, R.string.svc_wifi_btn, v -> openWifi());
        devRow = new Row(list, R.string.svc_dev, R.string.svc_dev_hint, R.string.svc_dev_btn, v -> openAbout());
        usbRow = new Row(list, R.string.svc_usb, R.string.svc_usb_hint, R.string.svc_usb_btn, v -> openDeveloper(PREF_USB));
        wirelessRow = new Row(list, R.string.svc_wireless, R.string.svc_wireless_manual, R.string.svc_wireless_btn,
                v -> openDeveloper(PREF_WIRELESS));
        pairRow = new Row(list, R.string.svc_pair, R.string.svc_pair_hint, R.string.svc_pair_btn, v -> onPairButton());
        notifWarning = link(list, activity.getString(R.string.svc_notif_off), AMBER, v -> fixNotifications());
        guide = new LinearLayout(activity);
        guide.setOrientation(LinearLayout.VERTICAL);
        guide.setPadding(dp(12), dp(4), 0, dp(4));
        guide.addView(text(activity.getString(R.string.svc_guide_title), 15, WHITE));
        int[] steps = {R.string.svc_guide_1, R.string.svc_guide_2, R.string.svc_guide_3, R.string.svc_guide_4, R.string.svc_guide_5};
        for (int s : steps) {
            TextView t = text(activity.getString(s), 14, GRAY);
            t.setPadding(0, dp(4), 0, 0);
            guide.addView(t);
        }
        // 第 3 步要在系统弹窗里选"始终允许"：只在信得过的网络上选
        TextView pub = text(activity.getString(R.string.svc_public_wifi), 14, AMBER);
        pub.setPadding(0, dp(6), 0, 0);
        guide.addView(pub);
        list.addView(guide);
        serviceRow = new Row(list, R.string.svc_service, R.string.svc_start_hint, R.string.svc_start_btn, v -> startService());
        resultLine = text("", 14, AMBER);
        resultLine.setPadding(0, dp(4), 0, dp(4));
        list.addView(resultLine);
        // 系统弹了"是否允许在此网络上使用无线调试"：同时说清楚哪些网络别选"始终允许"
        untrustedWarning = link(list, activity.getString(R.string.svc_warn_untrusted) + "\n\n" + activity.getString(R.string.svc_public_wifi),
                AMBER, v -> openDeveloper(PREF_WIRELESS));

        autoSwitch = new Switch(activity);
        autoSwitch.setText(R.string.svc_auto);
        autoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        autoSwitch.setPadding(0, dp(8), 0, dp(4));
        autoSwitch.setChecked(prefs.autoStartService());
        autoSwitch.setOnCheckedChangeListener((b, checked) -> prefs.setAutoStartService(checked));
        list.addView(autoSwitch);
        list.addView(text(activity.getString(R.string.svc_auto_hint), 14, GRAY));
        pausedLine = text(activity.getString(R.string.svc_auto_paused), 14, AMBER);
        pausedLine.setPadding(0, dp(6), 0, dp(4));
        list.addView(pausedLine);

        TextView warn = text(activity.getString(R.string.svc_warn_usb), 14, AMBER);
        warn.setPadding(0, dp(10), 0, dp(4));
        list.addView(warn);
        refresh();
    }

    /** 按现在的状态更新每一行。观察者、SelfStart 的通知、页面重建都走这里。 */
    private void refresh() {
        if (wifiRow == null) return;
        boolean wifi = wifiCallbackSeen ? !wifiNetworks.isEmpty() : wifiGuess();
        boolean dev = global(KEY_DEV);
        boolean usb = global(KEY_ADB);
        boolean wireless = global(KEY_ADB_WIFI);
        boolean canToggle = activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        boolean rejected = selfStart.keyRejected();
        // canStart：配对成功过（密钥被判作废也算：误判时手动启动成功就能解开，不至于只能重新配对）；paired：而且没被拒
        boolean canStart = selfStart.isPaired();
        boolean paired = canStart && !rejected;
        int step = selfStart.step();
        boolean busy = step != SelfStart.STEP_IDLE;
        int result = selfStart.lastResult();
        boolean running = selfStart.serviceState() == SelfStart.SERVICE_RUNNING;

        wifiRow.set(wifi ? Mark.OK : Mark.BAD, s(wifi ? R.string.svc_wifi_on : R.string.svc_wifi_off), null, !wifi);
        devRow.set(dev ? Mark.OK : Mark.BAD, s(dev ? R.string.svc_on : R.string.svc_off), null, !dev);
        // 开发者选项没开时 USB 调试开不了：说明里先指去上一项，按钮等开发者选项开了再给
        usbRow.set(usb ? Mark.OK : Mark.BAD, s(usb ? R.string.svc_on : R.string.svc_off),
                dev ? null : s(R.string.svc_usb_need_dev), !usb && dev);

        if (wireless) {
            // 开着、又不是豆花临时开的（那种用完马上自己关）：是用户自己开的，豆花不能替他关（他的电脑可能正经无线调试连着手机），
            // 只提醒 + 给跳转。配对、启动进行中不提醒：配对要它开着，启动时可能正是豆花开的
            boolean remind = !busy && !selfStart.wirelessIsOurs();
            wirelessRow.set(Mark.OK, s(R.string.svc_wireless_on), remind ? s(R.string.svc_wireless_left_on) : null, remind);
            wirelessRow.showHint(remind);
            wirelessRow.hintColor(remind ? AMBER : GRAY);
            wirelessRow.button.setText(R.string.svc_wireless_close_btn);
        } else if (canToggle) {
            wirelessRow.set(Mark.NEUTRAL, s(R.string.svc_wireless_off), s(R.string.svc_wireless_auto), false);
            wirelessRow.showHint(true);
        } else {
            wirelessRow.set(Mark.BAD, s(R.string.svc_wireless_off), s(R.string.svc_wireless_manual), true);
        }
        if (!wireless) wirelessRow.button.setText(R.string.svc_wireless_btn);

        // 配对：前面几项（Wi-Fi、开发者选项、USB 调试）都打勾才给按钮，免得配对成功了服务却起不来
        boolean ready = wifi && dev && usb;
        String pairValue = rejected ? s(R.string.svc_key_rejected) : s(paired ? R.string.svc_paired : R.string.svc_not_paired);
        String pairHint = rejected ? s(R.string.svc_key_rejected_hint)
                : !ready && !paired ? s(R.string.svc_pair_need_above) : s(R.string.svc_pair_hint);
        pairRow.set(paired ? Mark.OK : Mark.BAD, pairValue, pairHint, ready && !busy);
        pairRow.showHint(!paired);
        pairRow.button.setText(paired ? R.string.svc_repair_btn : R.string.svc_pair_btn);
        if (step == SelfStart.STEP_PAIR_SEARCH || step == SelfStart.STEP_PAIRING) {
            // 找端口时留一个"取消配对"：通知被划掉（Android 14 起前台服务的通知也能划掉）就只剩这里能停。
            // 正在配对（最多十来秒）时不给，免得取消到一半
            pairRow.set(Mark.WORKING, s(R.string.svc_pairing_now), s(R.string.svc_pairing_now_hint), step == SelfStart.STEP_PAIR_SEARCH);
            pairRow.showHint(true);
            pairRow.button.setText(R.string.svc_pair_cancel_btn);
        }
        notifWarning.setVisibility(!paired && !notificationsEnabled() ? View.VISIBLE : View.GONE);
        guide.setVisibility(paired && step != SelfStart.STEP_PAIR_SEARCH ? View.GONE : View.VISIBLE);

        refreshService(paired, canStart, ready, step);

        // 上次的失败原因存着跨进程（开机任务静默失败的也在）：服务现在在跑就不提，免得和"✓ 运行中"同时出现
        boolean failed = result != SelfStart.RESULT_NONE && result != SelfStart.RESULT_OK && !busy && !running;
        resultLine.setText(failed ? activity.getString(R.string.svc_last_result, resultText(activity, result)) : "");
        resultLine.setVisibility(failed ? View.VISIBLE : View.GONE);
        // 系统把无线调试又关了（这个 Wi-Fi 没被信任）：SelfStart 认出来报 RESULT_WIFI_UNTRUSTED；现在开着就不提
        untrustedWarning.setVisibility(result == SelfStart.RESULT_WIFI_UNTRUSTED && !wireless && !busy && !running
                ? View.VISIBLE : View.GONE);

        if (autoSwitch.isChecked() != prefs.autoStartService()) autoSwitch.setChecked(prefs.autoStartService());
        // 自动启动在退避中（被系统拒过，豆花读不到 Wi-Fi 名称、分不清网络，只能整体暂停）：说清楚为什么、怎么恢复
        pausedLine.setVisibility(prefs.autoStartService() && selfStart.autoPaused() ? View.VISIBLE : View.GONE);
    }

    /** paired：配对过、没被拒（决定说明文字）；canStart：配对过（决定给不给启动按钮，被拒了也给，见 refresh）。 */
    private void refreshService(boolean paired, boolean canStart, boolean ready, int step) {
        if (step == SelfStart.STEP_STARTING) {
            serviceRow.set(Mark.WORKING, s(R.string.svc_starting), null, false);
            return;
        }
        boolean idle = step == SelfStart.STEP_IDLE;
        int state = selfStart.serviceState();
        switch (state) {
            case SelfStart.SERVICE_RUNNING:
                serviceRow.set(Mark.OK, s(R.string.svc_running), null, false);
                // 服务在跑也留一个"重启服务"（比如想换成刚装的版本、或者觉得它不对劲）
                serviceRow.button.setText(R.string.svc_restart_btn);
                serviceRow.button.setVisibility(canStart && idle ? View.VISIBLE : View.GONE);
                break;
            case SelfStart.SERVICE_OLD_VERSION:
                serviceRow.set(Mark.BAD, activity.getString(R.string.svc_old_version, selfStart.serviceVersion(), Proto.VERSION),
                        paired ? null : s(R.string.svc_need_pair), canStart && idle);
                serviceRow.button.setText(R.string.svc_restart_btn);
                break;
            case SelfStart.SERVICE_STUCK:
                serviceRow.set(Mark.BAD, s(R.string.svc_stuck), paired ? null : s(R.string.svc_need_pair), canStart && idle);
                serviceRow.button.setText(R.string.svc_restart_btn);
                break;
            case SelfStart.SERVICE_NOT_RUNNING:
                serviceRow.set(Mark.BAD, s(R.string.svc_not_running),
                        !paired ? s(R.string.svc_need_pair) : ready ? s(R.string.svc_start_hint) : s(R.string.svc_pair_need_above),
                        canStart && idle);
                serviceRow.showHint(true);
                serviceRow.button.setText(R.string.svc_start_btn);
                break;
            default:
                serviceRow.set(Mark.WORKING, s(R.string.svc_checking), null, false);
                break;
        }
    }

    // ═══════════ 按钮 ═══════════

    /** 配对一项的按钮：找配对端口时是"取消配对"，平时是"开始配对 / 重新配对"。 */
    private void onPairButton() {
        if (selfStart.step() == SelfStart.STEP_PAIR_SEARCH) {
            PairingService.stop(activity);
            selfStart.stopPairingSearch();   // 服务那边也会停；这里先停，界面马上回去
            refresh();
            return;
        }
        startPairing();
    }

    private void startPairing() {
        if (!notificationsEnabled()) {   // 配对码要在通知里输：通知被关了就白配
            fixNotifications();
            return;
        }
        PairingService.start(activity);
        // 接着带去开发者选项、滚到"无线调试"：用户在那里打开它、点"使用配对码配对设备"
        openDeveloper(PREF_WIRELESS);
    }

    private void startService() {
        selfStart.startService(null);   // 进度和结果走 Listener
        refresh();
    }

    /** 开发者选项里的"无线调试"（打不开退到"关于手机"）。设置页带 {@link #EXTRA_OPEN_WIRELESS} 打开时也走这里。 */
    void openWirelessSettings() {
        openDeveloper(PREF_WIRELESS);
    }

    /** 给通知用：打开设置页（停在"通用"页，从系统设置回来看得到这一节）并转去无线调试那一页（见 {@link #EXTRA_OPEN_WIRELESS}）。 */
    static Intent openWirelessIntent(Context c) {
        return SettingsActivity.serviceIntent(c)
                .putExtra(EXTRA_OPEN_WIRELESS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void openWifi() {
        open(new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }

    /** "关于手机"：三星在 关于手机 → 软件信息 → 编译编号，别的手机多半直接是"版本号"，说明里两种都写了。 */
    private void openAbout() {
        open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
    }

    /** 开发者选项（highlight = 要滚到的那一项）；没开开发者选项时有的系统打不开这页，退到"关于手机"。 */
    private void openDeveloper(String highlight) {
        Intent dev = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(EXTRA_HIGHLIGHT, highlight);
        open(dev, new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
    }

    private void fixNotifications() {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        NotificationChannel ch = nm != null ? nm.getNotificationChannel(PairingService.CHANNEL) : null;
        if (nm != null && nm.areNotificationsEnabled() && ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            // 整体开着、只关了"配对"这一类：直接到这一类的设置
            open(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, PairingService.CHANNEL));
            return;
        }
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
    }

    /** 依次试，第一个打得开的就用；全都不行最后打开系统设置首页。 */
    private void open(Intent... intents) {
        for (Intent i : intents) {
            try {
                activity.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // 这台手机没有这一页（或者不让外部打开）：试下一个
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            // 连设置首页都没有：没办法了
        }
    }

    // ═══════════ 判断 ═══════════

    private boolean global(String key) {
        try {
            return Settings.Global.getInt(activity.getContentResolver(), key, 0) != 0;
        } catch (SecurityException e) {
            return false;   // 系统不让读（不该发生：这三个都标了 @Readable）
        }
    }

    /** NetworkCallback 回来之前的猜测：当前默认网络带 Wi-Fi（开着 VPN 时 VPN 的能力里也带底下的 Wi-Fi）。 */
    private boolean wifiGuess() {
        if (cm == null) return false;
        try {
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 通知能不能出来：有权限、整体没关、"配对"这一类没关（还没建过这一类就当开着）。 */
    private boolean notificationsEnabled() {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = nm.getNotificationChannel(PairingService.CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** SelfStart 的结果码 → 给用户看的一句话（设置页、配对通知共用）。 */
    static String resultText(Context c, int result) {
        switch (result) {
            case SelfStart.RESULT_OK: return c.getString(R.string.res_ok);
            case SelfStart.RESULT_NO_WIFI: return c.getString(R.string.res_no_wifi);
            case SelfStart.RESULT_ADB_OFF: return c.getString(R.string.res_adb_off);
            case SelfStart.RESULT_WIRELESS_OFF: return c.getString(R.string.res_wireless_off);
            case SelfStart.RESULT_WIFI_UNTRUSTED: return c.getString(R.string.res_wifi_untrusted);
            case SelfStart.RESULT_NOT_FOUND: return c.getString(R.string.res_not_found);
            case SelfStart.RESULT_NOT_PAIRED: return c.getString(R.string.res_not_paired);
            case SelfStart.RESULT_KEY_REJECTED: return c.getString(R.string.res_key_rejected);
            case SelfStart.RESULT_WRONG_CODE: return c.getString(R.string.res_wrong_code);
            case SelfStart.RESULT_CONNECT_FAILED: return c.getString(R.string.res_connect_failed);
            case SelfStart.RESULT_KEYSTORE: return c.getString(R.string.res_keystore);
            case SelfStart.RESULT_SERVICE_FAILED: return c.getString(R.string.res_service_failed);
            default: return c.getString(R.string.res_error);
        }
    }

    // ═══════════ 小部件 ═══════════

    private enum Mark {
        OK("✓", GREEN), BAD("✗", RED), NEUTRAL("○", GRAY), WORKING("…", GRAY);

        final String symbol;
        final int color;

        Mark(String symbol, int color) {
            this.symbol = symbol;
            this.color = color;
        }
    }

    /** 一项：第一行"✓ 名字：状态"，下面一句说明、一个按钮（没满足时才显示）。 */
    private final class Row {
        final String label;
        final String defaultHint;
        final TextView mark;
        final TextView title;
        final TextView hint;
        final TextView button;

        Row(LinearLayout list, int labelRes, int hintRes, int buttonRes, View.OnClickListener onClick) {
            label = activity.getString(labelRes);
            defaultHint = activity.getString(hintRes);
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(10), 0, 0);
            mark = text("", 16, GRAY);
            mark.setMinWidth(dp(24));
            title = text("", 16, WHITE);
            line.addView(mark);
            line.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            list.addView(line);
            hint = text(defaultHint, 14, GRAY);
            hint.setPadding(dp(24), dp(2), 0, 0);
            list.addView(hint);
            button = text(activity.getString(buttonRes), 16, LINK);
            button.setPadding(dp(24), dp(8), 0, dp(4));
            button.setOnClickListener(onClick);
            list.addView(button);
        }

        /** hintText 为 null 用这一项默认的说明；说明默认只在没满足（mark 不是 OK）时显示，showHint 可以改。 */
        void set(Mark m, String value, String hintText, boolean showButton) {
            mark.setText(m.symbol);
            mark.setTextColor(m.color);
            title.setText(activity.getString(R.string.value_format, label, value));
            hint.setText(hintText != null ? hintText : defaultHint);
            hint.setTextColor(GRAY);
            hint.setVisibility(m == Mark.OK ? View.GONE : View.VISIBLE);
            button.setVisibility(showButton ? View.VISIBLE : View.GONE);
        }

        void showHint(boolean show) {
            hint.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        /** 说明的颜色（每次 set 都回到灰色）：提醒类用琥珀色。 */
        void hintColor(int color) {
            hint.setTextColor(color);
        }
    }

    private TextView link(LinearLayout list, String s, int color, View.OnClickListener onClick) {
        TextView t = text(s, 14, color);
        t.setPadding(0, dp(6), 0, dp(6));
        t.setOnClickListener(onClick);
        list.addView(t);
        return t;
    }

    private String s(int res) {
        return activity.getString(res);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
