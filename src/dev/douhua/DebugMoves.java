package dev.douhua;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 只在可调试包里有：电脑上用 adb 让手机经蓝牙发任意一串鼠标位移，量回正中的跳、对方的加速时用（配合主机上记光标坐标）。
 * <pre>adb shell am broadcast -a dev.douhua.DEBUG_MOVES --es moves "dx,dy,ms;dx,dy,ms;..."</pre>
 * ms 是距上一帧的间隔（第一帧距收到广播）。每帧的发送时刻写日志（标签 douhua）。
 * 另一个动作开关空中鼠标的调试日志（AirMouse.debugLog），gap 可选、改激光笔式的发送间隔（毫秒，−1 = 默认）：
 * <pre>adb shell am broadcast -a dev.douhua.DEBUG_AIRLOG --ez on true [--ei gap 5] [--es cfg 截止,β,判断在动,死区,轨迹ms,半径|default]</pre>
 * cfg 临时换一组消抖参数（描红测试的 A/B/C 轮，下次开始跟随时生效），default 换回设置里的；
 * --ei space N 临时换方向换算（AirPointer.SPACE_*，−1 = 默认）。
 * 只收持有 DUMP 权限的发送方（adb shell 有，普通 App 没有）；正式包里根本不注册——否则任何 App 都能挪对方的光标。
 */
final class DebugMoves extends BroadcastReceiver {
    static final String ACTION = "dev.douhua.DEBUG_MOVES";
    static final String ACTION_AIRLOG = "dev.douhua.DEBUG_AIRLOG";
    private static final String TAG = "douhua";

    private final CenterRunner runner;

    private DebugMoves(BtHid bt) {
        runner = new CenterRunner(new Handler(Looper.getMainLooper()), (dx, dy) -> {
            bt.sendMouse(0, dx, dy, 0, 0);
            Log.i(TAG, "调试位移 t=" + SystemClock.uptimeMillis() + " " + dx + "," + dy);
        });
    }

    /** 可调试包才注册，返回 null 表示没注册。 */
    static DebugMoves register(Context c, BtHid bt) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null;
        DebugMoves d = new DebugMoves(bt);
        IntentFilter f = new IntentFilter(ACTION);
        f.addAction(ACTION_AIRLOG);
        c.registerReceiver(d, f, Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
        return d;
    }

    void unregister(Context c) {
        runner.cancel();
        c.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_AIRLOG.equals(intent.getAction())) {
            // 只改带上的那几项：tools/air-log.sh 记录期间每 3 秒补发一次"开"（豆花中途重启时内存里的开关会丢），
            // 不带的项保持原样，免得把描红每轮设的间隔 / 换算冲掉
            AirMouse.debugLog = intent.getBooleanExtra("on", false);
            if (intent.hasExtra("gap")) AirMouse.debugAbsGapMs = intent.getIntExtra("gap", -1);
            String cfg = intent.getStringExtra("cfg");
            if (cfg != null) {
                float[] f = null;
                if (!cfg.equals("default")) {
                    try {
                        String[] p = cfg.split(",");
                        f = new float[6];
                        for (int i = 0; i < 6; i++) f[i] = Float.parseFloat(p[i].trim());
                    } catch (RuntimeException e) {
                        Log.w(TAG, "调试消抖参数格式不对：" + cfg);
                        return;
                    }
                }
                AirMouse.debugFilter = f;
                Log.i(TAG, "空中鼠标调试消抖参数：" + cfg);
            }
            if (intent.hasExtra("space")) AirMouse.debugSpace = intent.getIntExtra("space", -1);
            if (!AirMouse.debugLog) {   // 关调试日志时一并撤销临时参数
                AirMouse.debugFilter = null;
                AirMouse.debugSpace = -1;
                AirMouse.debugAbsGapMs = -1;
            }
            if (intent.hasExtra("gap") || !AirMouse.debugLog) {   // 补发的"开"不刷日志
                Log.i(TAG, "空中鼠标调试日志 " + (AirMouse.debugLog ? "开" : "关") + "，激光笔式发送间隔 "
                        + (AirMouse.debugAbsGapMs >= 0 ? AirMouse.debugAbsGapMs + "ms" : "默认"));
            }
            return;
        }
        String s = intent.getStringExtra("moves");
        if (s == null) return;
        List<CenterRunner.Move> moves = new ArrayList<>();
        try {
            for (String f : s.split(";")) {
                if (f.trim().isEmpty()) continue;
                String[] p = f.split(",");
                moves.add(new CenterRunner.Move(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim())));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "调试位移格式不对：" + s);
            return;
        }
        Log.i(TAG, "调试位移开始 " + moves.size() + " 帧 t=" + SystemClock.uptimeMillis());
        runner.play(moves, () -> Log.i(TAG, "调试位移播完 t=" + SystemClock.uptimeMillis()));
    }
}
