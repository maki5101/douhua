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
 * 屏幕列表直接问系统的显示服务（IDisplayManager，隐藏 API，反射），问不到才退回 DisplayManagerGlobal：
 * 实测（主力机，2026-10-11）服务常驻时，DisplayManagerGlobal 看得到外接屏拔掉、却看不到重新接上的新屏
 * （眼镜断开重连后屏幕编号从 9 变成 11，一直没发现，通知也就没再弹）。推测是它在没注册显示器监听的进程里
 * 缓存了屏幕列表；直接问服务每次都是现查。
 * 通知由 App 发（服务用 am broadcast 叫醒它），这样点通知打开的是豆花自己。
 */
final class DisplayWatcher implements Runnable {
    static final String ACTION_ADDED = "dev.douhua.DISPLAY_ADDED";
    static final String ACTION_REMOVED = "dev.douhua.DISPLAY_REMOVED";
    private static final String RECEIVER = "dev.douhua/.DisplayReceiver";
    private static final int TYPE_EXTERNAL = 2;
    private static final int TYPE_WIFI = 3;
    private static final long INTERVAL_MS = 1500;

    /** 最近一次查到的外接屏编号：Session 接管触摸屏时用它把键盘输入切过去（见 DexFocus）。 */
    private static volatile int[] externals = new int[0];

    private final Set<Integer> known = new HashSet<>();
    /** 问屏幕列表的对象和方法：IDisplayManager（首选）或 DisplayManagerGlobal（退路）。 */
    private Object source;
    private Method getDisplayIds;
    private Object[] idsArgs;
    private Method getDisplayInfo;

    /** 现在接着的外接屏（有线 / 无线投屏）；盯外接屏不可用时是空的。 */
    static int[] externals() {
        return externals;
    }

    static void start() {
        Thread t = new Thread(new DisplayWatcher(), "douhua-displays");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        try {
            useDisplayService();
        } catch (ReflectiveOperationException | RuntimeException e) {
            Server.log("直接问显示服务不行（" + e + "），改用 DisplayManagerGlobal（外接屏重新接上可能发现不了）");
            try {
                Class<?> cls = Class.forName("android.hardware.display.DisplayManagerGlobal");
                source = cls.getMethod("getInstance").invoke(null);
                getDisplayIds = cls.getMethod("getDisplayIds");
                idsArgs = new Object[0];
                getDisplayInfo = cls.getMethod("getDisplayInfo", int.class);
            } catch (ReflectiveOperationException e2) {
                Server.log("盯外接屏不可用，不会弹通知：" + e2);
                return;
            }
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

    /** IDisplayManager：Android 13 起 getDisplayIds 带一个"含停用的屏"参数，更早的没有。 */
    private void useDisplayService() throws ReflectiveOperationException {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "display");
        if (binder == null) throw new IllegalStateException("没有 display 服务");
        Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
        source = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Class<?> api = Class.forName("android.hardware.display.IDisplayManager");
        try {
            getDisplayIds = api.getMethod("getDisplayIds", boolean.class);
            idsArgs = new Object[]{false};
        } catch (NoSuchMethodException e) {
            getDisplayIds = api.getMethod("getDisplayIds");
            idsArgs = new Object[0];
        }
        getDisplayInfo = api.getMethod("getDisplayInfo", int.class);
        getDisplayIds.invoke(source, idsArgs);   // 先试一次，不行就走退路
    }

    private void poll() throws ReflectiveOperationException {
        int[] ids = (int[]) getDisplayIds.invoke(source, idsArgs);
        Set<Integer> present = new HashSet<>();
        for (int id : ids) {
            Object info = getDisplayInfo.invoke(source, id);
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
        int[] now = new int[present.size()];
        int n = 0;
        for (int id : present) now[n++] = id;
        externals = now;
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
