package dev.douhua;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

/**
 * 无线调试配对：前台服务 + 通知里直接输入配对码（RemoteInput）。
 *
 * 为什么在通知里输：系统设置的配对窗口一离开就关（AOSP WirelessDebuggingFragment.onPause 里撤掉弹窗并停止配对），
 * 用户不能切回豆花输码；下拉通知栏不会让设置页 onPause，弹窗还在。Shizuku、App Manager 都是这么做的。
 *
 * 前台服务类型选 shortService（和 Shizuku 一样）：配对 + 启动服务是一件几分钟内做完的事，正好是它的用途；
 * 不用额外权限，也不用像 specialUse 那样写用途声明。代价是最多跑约 3 分钟，到点系统调 {@link #onTimeout(int)}，
 * 这里收尾、发一条"等太久了"的通知带"重新开始"。照文档，通知里每回复一次（又一次 startForegroundService + startForeground）
 * 会重新计时（没在真机上验证）。
 *
 * 协议都在 {@link SelfStart}（找端口、配对、起服务）；这里只管通知。配对码只经 RemoteInput 进来、交给 SelfStart，
 * 不写日志、不进通知文字、不存。
 */
public final class PairingService extends Service {
    private static final String TAG = "douhua";
    static final String CHANNEL = "pairing";
    private static final int NOTIFICATION_ID = 0x5e1f;

    private static final String ACTION_START = "dev.douhua.PAIR_START";
    private static final String ACTION_REPLY = "dev.douhua.PAIR_REPLY";
    private static final String ACTION_STOP = "dev.douhua.PAIR_STOP";
    private static final String EXTRA_PORT = "port";
    private static final String KEY_CODE = "pairing_code";

    private static final int REQ_REPLY = 1;
    private static final int REQ_STOP = 2;
    private static final int REQ_RETRY = 3;
    private static final int REQ_OPEN = 4;
    private static final int REQ_WIRELESS = 5;

    private NotificationManager nm;
    private SelfStart selfStart;
    /** 已经退出前台、stopSelf 过：之后 SelfStart 晚到的回调只更新通知。 */
    private boolean finished;

    /** 设置页"开始配对"调：开前台服务、开始找配对端口。 */
    static void start(Context context) {
        context.startForegroundService(new Intent(context, PairingService.class).setAction(ACTION_START));
    }

    /** 设置页"取消配对"调（App 在前台）：和通知上的"停止"一样，停止找端口、收起通知。 */
    static void stop(Context context) {
        context.startForegroundService(new Intent(context, PairingService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        selfStart = SelfStart.get(this);
        // 高优先级：配对码在系统的配对窗口上方弹出来，下拉就能直接输；不响铃
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.pair_channel),
                NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            // 也是 startForegroundService 来的（通知按钮、设置页"取消配对"），照规矩先进前台再退；
            // finished 先清掉：之前收过尾的实例也要再退一次前台，不然这次进的前台没人退
            finished = false;
            foreground(progress(getString(R.string.pair_stopping)));
            finish(null);
            return START_NOT_STICKY;
        }
        finished = false;
        if (ACTION_REPLY.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence typed = results != null ? results.getCharSequence(KEY_CODE) : null;
            String code = typed == null ? "" : typed.toString().replaceAll("\\s+", "");
            int port = intent.getIntExtra(EXTRA_PORT, -1);
            if (code.isEmpty() || port <= 0) {
                // 没输东西（或者服务进程死过、端口丢了）：回到等配对码
                foreground(port > 0 ? input(port, getString(R.string.pair_empty_code), true) : searching());
                search();
                return START_NOT_STICKY;
            }
            foreground(progress(getString(R.string.pair_working)));
            selfStart.submitPairingCode(code, port, result -> onPaired(result, port));
            return START_NOT_STICKY;
        }
        // 开始 / 重新开始
        foreground(searching());
        search();
        return START_NOT_STICKY;
    }

    private void search() {
        selfStart.startPairingSearch(port -> {
            if (!finished) update(input(port, getString(R.string.pair_found_text), false));
        });
    }

    private void onPaired(int result, int port) {
        if (result == SelfStart.RESULT_OK) {
            selfStart.stopPairingSearch();
            new Prefs(this).setAutoStartService(true);   // 配对成功：默认自动启动，设置页里能关
            update(progress(getString(R.string.pair_ok_starting)));
            selfStart.startService(this::onStarted);
            return;
        }
        if (result == SelfStart.RESULT_WRONG_CODE) {
            // 系统的配对窗口可能还开着（同一个端口能再输），也可能已经关了（要重新点，端口会变，search 会换上新的）
            update(input(port, getString(R.string.pair_wrong_code), true));
            return;
        }
        Log.w(TAG, "无线调试配对失败，结果码 " + result);
        finish(done(getString(R.string.pair_failed_title), ServiceSection.resultText(this, result), true));
    }

    private void onStarted(int result) {
        if (result == SelfStart.RESULT_OK) {
            finish(started());
        } else {
            Log.w(TAG, "配对成功，但启动服务失败，结果码 " + result);
            finish(done(getString(R.string.pair_start_failed_title),
                    getString(R.string.pair_start_failed_text, ServiceSection.resultText(this, result)), false));
        }
    }

    /** shortService 到点（约 3 分钟，Android 14 起才有）：必须马上停，否则系统当 ANR。 */
    @Override
    public void onTimeout(int startId) {
        Log.w(TAG, "配对等太久，前台服务到点了");
        finish(done(getString(R.string.pair_timeout_title), getString(R.string.pair_timeout_text), true));
    }

    @Override
    public void onDestroy() {
        if (!finished) selfStart.stopPairingSearch();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ═══════════ 前台 / 收尾 ═══════════

    private void foreground(Notification n) {
        try {
            // shortService 是 Android 14 加的；13 上不分类型（也没有 3 分钟限制）
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (ForegroundServiceStartNotAllowedException e) {
            // 后台不让进前台（正常路径都是用户点出来的，不该走到这）：至少把通知亮出来
            Log.w(TAG, "配对服务进不了前台：" + e.getMessage());
            nm.notify(NOTIFICATION_ID, n);
        }
    }

    private void update(Notification n) {
        nm.notify(NOTIFICATION_ID, n);
    }

    /** 停止找端口、退出前台；n 不为空就留一条结果通知（同一个编号，换成可划掉的）。 */
    private void finish(Notification n) {
        selfStart.stopPairingSearch();
        if (!finished) {
            finished = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        if (n != null) nm.notify(NOTIFICATION_ID, n);
    }

    // ═══════════ 通知 ═══════════

    private Notification.Builder base(String title, String text) {
        Intent open = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(Icon.createWithBitmap(DisplayReceiver.icon()))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(this, REQ_OPEN, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setShowWhen(false);
    }

    /** 在找配对端口：告诉用户去系统里点哪里。 */
    private Notification searching() {
        return base(getString(R.string.pair_searching_title), getString(R.string.pair_searching_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction())
                .build();
    }

    /** 找到配对端口：挂输入框。alertAgain = 再弹一次（配对码不对、没输东西时）。 */
    private Notification input(int port, String text, boolean alertAgain) {
        RemoteInput remote = new RemoteInput.Builder(KEY_CODE)
                .setLabel(getString(R.string.pair_code_label))
                .build();
        // 端口放进 Intent：用户输码前服务进程被杀了也不丢。RemoteInput 要往里填结果，所以必须 MUTABLE（显式 Intent，不受隐式可变的限制）
        Intent reply = new Intent(this, PairingService.class).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port);
        PendingIntent pi = PendingIntent.getForegroundService(this, REQ_REPLY, reply,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action action = new Notification.Action.Builder(null, getString(R.string.pair_input_action), pi)
                .addRemoteInput(remote)
                .build();
        return base(getString(R.string.pair_found_title), text)
                .setOngoing(true)
                .setOnlyAlertOnce(!alertAgain)
                .setCategory(Notification.CATEGORY_MESSAGE)   // 有的系统只给这类通知在横幅里直接回复
                .addAction(action)
                .addAction(stopAction())
                .build();
    }

    private Notification progress(String text) {
        return base(text, "")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build();
    }

    /** 结果：可划掉；retry = 带"重新开始"（重新找配对端口）。 */
    private Notification done(String title, String text, boolean retry) {
        Notification.Builder b = base(title, text).setAutoCancel(true);
        if (retry) {
            Intent again = new Intent(this, PairingService.class).setAction(ACTION_START);
            PendingIntent pi = PendingIntent.getForegroundService(this, REQ_RETRY, again,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.pair_retry), pi).build());
        }
        return b.build();
    }

    /**
     * 配对成功、服务起来了。无线调试是用户为了配对自己打开的，豆花不能替他关（他的电脑可能正经无线调试连着手机），
     * 还开着就在同一条通知里提醒关掉、带一个跳到无线调试那一页的按钮；已经关了（或者是豆花自己临时开的、会自己关）就不提。
     */
    private Notification started() {
        boolean on;
        try {
            on = Settings.Global.getInt(getContentResolver(), SelfStart.KEY_ADB_WIFI, 0) != 0;
        } catch (SecurityException e) {
            on = false;   // 读不到（不该发生：这一项标了 @Readable）就不提
        }
        if (!on || selfStart.wirelessIsOurs()) {
            return done(getString(R.string.pair_done_title), getString(R.string.pair_done_text), false);
        }
        String text = getString(R.string.pair_done_text) + "\n\n" + getString(R.string.pair_done_wireless_on);
        PendingIntent pi = PendingIntent.getActivity(this, REQ_WIRELESS, ServiceSection.openWirelessIntent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return base(getString(R.string.pair_done_title), text)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.svc_wireless_close_btn), pi).build())
                .build();
    }

    private Notification.Action stopAction() {
        Intent stop = new Intent(this, PairingService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getForegroundService(this, REQ_STOP, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(null, getString(R.string.pair_stop), pi).build();
    }
}
