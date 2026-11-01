package dev.douhua;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.view.Display;

/**
 * 豆花服务发现接上（或拔掉）外接显示器时叫醒这里：接上就弹一条通知"点这里把手机当触控板"，拔掉就收回。
 * 清单里要求调用方持有 DUMP 权限，只有服务（shell 身份）能触发，别的 App 触发不了。
 */
public final class DisplayReceiver extends BroadcastReceiver {
    static final String ACTION_ADDED = "dev.douhua.DISPLAY_ADDED";
    static final String ACTION_REMOVED = "dev.douhua.DISPLAY_REMOVED";
    private static final String CHANNEL = "display";
    private static final String TAG = "douhua";

    @Override
    public void onReceive(Context context, Intent intent) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        int id = intent.getIntExtra("id", 0);
        if (ACTION_REMOVED.equals(intent.getAction())) {
            nm.cancel(id);
            return;
        }
        if (!ACTION_ADDED.equals(intent.getAction())) return;
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "接上了外接屏，但没有通知权限：打开一次豆花，在弹出的对话框里允许通知");
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_HIGH));

        // 在手机屏上打开豆花（从外接屏上的通知栏点也一样，比如三星 DeX 的，不会开到外接屏上去）
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        PendingIntent pi = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, options.toBundle());

        String name = intent.getStringExtra("name");
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(Icon.createWithBitmap(icon()))
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(name == null || name.isEmpty() ? context.getString(R.string.notif_text)
                        : context.getString(R.string.notif_text_named, name))
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build();
        nm.notify(id, n);
    }

    /** 通知栏小图标：一块圆角触控板。没有图片资源，直接画。 */
    static Bitmap icon() {
        int size = 96;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8);
        c.drawRoundRect(new RectF(10, 18, 86, 78), 12, 12, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(38, 48, 7, p);
        c.drawCircle(58, 48, 7, p);
        return b;
    }
}
