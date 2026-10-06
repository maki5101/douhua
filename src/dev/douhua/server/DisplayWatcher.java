package dev.douhua.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 盯着外接显示器：每 1.5 秒查一次屏幕列表，发现新接上的外接屏（有线 / 无线投屏）就叫豆花发一条通知，
 * 拔掉时叫它收回通知——和三星自带的"把手机当触控板"通知一样。服务启动时已经接着的也算一次"接上"。
 *
 * 用的是 DisplayManagerGlobal（隐藏 API，反射）；通知由 App 发（服务用 am broadcast 叫醒它），
 * 这样点通知打开的是豆花自己。
 */
final class DisplayWatcher implements Runnable {
    static final String ACTION_ADDED = "dev.douhua.DISPLAY_ADDED";
    static final String ACTION_REMOVED = "dev.douhua.DISPLAY_REMOVED";
    private static final String RECEIVER = "dev.douhua/.DisplayReceiver";
    private static final int TYPE_EXTERNAL = 2;
    private static final int TYPE_WIFI = 3;
    private static final long INTERVAL_MS = 1500;

    private final Set<Integer> known = new HashSet<>();
    private Object global;
    private Method getDisplayIds;
    private Method getDisplayInfo;

    static void start() {
        Thread t = new Thread(new DisplayWatcher(), "douhua-displays");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        try {
            Class<?> cls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            global = cls.getMethod("getInstance").invoke(null);
            getDisplayIds = cls.getMethod("getDisplayIds");
            getDisplayInfo = cls.getMethod("getDisplayInfo", int.class);
        } catch (ReflectiveOperationException e) {
            Server.log("盯外接屏不可用，不会弹通知：" + e);
            return;
        }
        while (true) {
            try {
                poll();
            } catch (ReflectiveOperationException | RuntimeException e) {
                Server.log("查屏幕列表出错：" + e);
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void poll() throws ReflectiveOperationException {
        int[] ids = (int[]) getDisplayIds.invoke(global);
        Set<Integer> present = new HashSet<>();
        for (int id : ids) {
            Object info = getDisplayInfo.invoke(global, id);
            if (info == null) continue;
            int type = field(info, "type");
            if (type != TYPE_EXTERNAL && type != TYPE_WIFI) continue;
            present.add(id);
            if (known.add(id)) {
                String name = String.valueOf(info.getClass().getField("name").get(info));
                Server.log("接上了外接屏 " + id + "（" + name + "），通知豆花");
                broadcast(ACTION_ADDED, id, name);
            }
        }
        List<Integer> gone = new ArrayList<>();
        for (int id : known) if (!present.contains(id)) gone.add(id);
        for (int id : gone) {
            known.remove(id);
            Server.log("外接屏 " + id + " 拔掉了，收回通知");
            broadcast(ACTION_REMOVED, id, "");
        }
    }

    private static int field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getField(name);
        return f.getInt(o);
    }

    /** -f 0x20：豆花从没打开过（"已停止"状态）也叫得醒。 */
    private static void broadcast(String action, int id, String name) {
        try {
            Process p = new ProcessBuilder("am", "broadcast", "-f", "0x20", "-n", RECEIVER, "-a", action,
                    "--ei", "id", String.valueOf(id), "--es", "name", name)
                    .redirectErrorStream(true).start();
            p.getInputStream().close();
            p.waitFor();
        } catch (Exception e) {
            Server.log("叫豆花发通知失败：" + e);
        }
    }
}
