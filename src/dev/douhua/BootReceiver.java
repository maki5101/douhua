package dev.douhua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后自动启动服务的入口（设置里"自动启动服务"开着才做）。开机时 Wi-Fi 往往还没连上，
 * 无线调试也只能在连着（受信任的）Wi-Fi 时打开，所以这里不直接连，只排一个"等 Wi-Fi、有截止时间"的任务（SelfStartJob），
 * 失败也只记日志，不打扰用户。
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "douhua";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!new Prefs(context).autoStartService()) return;
        try {
            SelfStartJob.schedule(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "开机自动启动服务没排上：" + e);
        }
    }
}
