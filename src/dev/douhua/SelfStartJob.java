package dev.douhua;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

/**
 * 开机后等连上 Wi-Fi 再自动启动服务（BootReceiver 排上）。无线调试只能在连着 Wi-Fi 时打开，开机时 Wi-Fi 往往还没连上。
 * 约束：任何 Wi-Fi（不要求能上网）；截止 10 分钟：到点还没 Wi-Fi 系统也会跑一次，SelfStart 报"没 Wi-Fi"，只记日志。
 * 失败都静默（记日志），不弹通知。自动启动在退避中（之前被系统拒过、见 AutoStartBackoff）且无线调试关着时什么都不做。
 */
public final class SelfStartJob extends JobService {
    private static final String TAG = "douhua";
    private static final int JOB_ID = 0x5e1f01;
    private static final long DEADLINE_MS = 10 * 60 * 1000;

    /** BootReceiver 调：排一个"连上 Wi-Fi 就跑、有截止时间"的任务。 */
    static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null) return;
        NetworkRequest wifi = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, SelfStartJob.class))
                .setRequiredNetwork(wifi)
                .setOverrideDeadline(DEADLINE_MS)
                .build();
        int r = js.schedule(job);
        if (r != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "开机自动启动服务的任务没排上");
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        SelfStart s = SelfStart.get(this);
        s.autoStart(result -> {
            if (result == SelfStart.RESULT_PAUSED) {
                Log.i(TAG, "开机自动启动服务暂停中：之前被系统拒过、无线调试关着，等手动启动");
            } else if (result != SelfStart.RESULT_NONE && result != SelfStart.RESULT_OK) {
                Log.w(TAG, "开机自动启动服务没成功，结果码 " + result);
            } else if (result == SelfStart.RESULT_OK) {
                Log.i(TAG, "开机自动启动了服务");
            }
            jobFinished(params, false);
        });
        return true;   // 在后台做，做完 jobFinished
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;   // 系统叫停（比如 Wi-Fi 断了）：不重排，下次打开豆花时还会自动启动
    }
}
