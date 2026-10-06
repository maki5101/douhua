package dev.douhua;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 自检：不开界面，测一下 App 能不能连上豆花服务（tools/selftest.sh 调用）。
 * 清单里要求调用方持有 DUMP 权限，只有 adb shell 能触发，别的 App 触发不了。
 */
public final class SelfTestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            String result;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(Proto.HOST, Proto.PORT), 1000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                int tag = in.readUnsignedByte();
                int version = in.readUnsignedByte();
                boolean ok = in.readUnsignedByte() == 1;
                String message = in.readUTF();
                result = "连接成功：tag=" + tag + " version=" + version
                        + " ok=" + ok + (message.isEmpty() ? "" : " " + message);
            } catch (Exception e) {
                result = "连接失败：" + e;
            }
            pending.setResultData(result);
            pending.finish();
        }, "douhua-selftest").start();
    }
}
