package dev.douhua.server;

import android.os.SystemClock;

import java.io.IOException;
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
 *
 * 扛住框架重启：系统框架（system_server）重启后，取到的显示服务代理就死了。以前只在开头取一次，
 * 之后每 1.5 秒刷一行同样的错、永不重取。现在任何一次出错都把代理丢掉、下一轮重新取（先试直连），
 * 日志限频（LogThrottle）并展开原因。DisplayManagerGlobal 那条退路恢复不了：它是进程单例，
 * 里面的代理死了就一直是死的，所以每次重取都先试直连。
 */
final class DisplayWatcher implements Runnable {
    static final String ACTION_ADDED = "dev.douhua.DISPLAY_ADDED";
    static final String ACTION_REMOVED = "dev.douhua.DISPLAY_REMOVED";
    private static final String RECEIVER = "dev.douhua/.DisplayReceiver";
    private static final int TYPE_EXTERNAL = 2;
    private static final int TYPE_WIFI = 3;
    private static final long INTERVAL_MS = 1500;
    private static final long LOG_INTERVAL_MS = 60_000;
    private static final long BROADCAST_TIMEOUT_MS = 10_000;

    /** 最近一次查到的外接屏编号：Session 接管触摸屏时用它把键盘输入切过去（见 DexFocus）。 */
    private static volatile int[] externals = new int[0];

    private final Set<Integer> known = new HashSet<>();
    /**
     * 系统框架重启过（代理死了 / 显示服务不在）：等 AMS 能投递清单广播之后（{@link #systemReady}），把接着的外接屏
     * 都当新接上的，重发一次通知。框架重启会杀掉 App、清掉通知，外接屏却多半还是原来的编号，不重发的话要拔掉再插上
     * 才有通知（按编号发，重发只会覆盖同一条）。只看框架重启的迹象，不看一般的偶发错误：否则用户划掉的通知会
     * 无缘无故又弹回来。重启后显示服务比 AMS 早好几秒能用，这时发的广播清单里注册的 DisplayReceiver 收不到，所以要等。
     */
    private boolean resync;
    /** 问屏幕列表的对象和方法：IDisplayManager（首选）或 DisplayManagerGlobal（退路）；null = 下一轮重新取。 */
    private Object source;
    private Method getDisplayIds;
    private Object[] idsArgs;
    private Method getDisplayInfo;
    /** 正在用退路：每一轮都先再试直连（退路里的数据可能是缓存的旧数据，框架重启后它也许根本不会报错）。 */
    private boolean usingFallback;
    /** 直连在这个系统上根本没有（找不到类或方法），不用再试。 */
    private boolean directMissing;
    private final LogThrottle pollLog = new LogThrottle(LOG_INTERVAL_MS);
    private final LogThrottle directLog = new LogThrottle(LOG_INTERVAL_MS);

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
        while (true) {
            long now = SystemClock.uptimeMillis();
            try {
                if (source == null || (usingFallback && !directMissing)) resolve(now);
                poll();
                String line = pollLog.ok(now);
                if (line != null) Server.log("查屏幕列表" + line + "，多半是系统框架重启过");
            } catch (ReflectiveOperationException | RuntimeException e) {
                source = null;   // 代理多半死了：下一轮重新取
                if (frameworkGone(e)) resync = true;
                String line = pollLog.error(LogThrottle.describe(e), now);
                if (line != null) Server.log("查屏幕列表出错（每 1.5 秒重试）：" + line);
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 先试直连显示服务，不行再退回 DisplayManagerGlobal；两条都不行就抛异常，下一轮再试。 */
    private void resolve(long now) throws ReflectiveOperationException {
        if (!directMissing) {
            try {
                useDisplayService();
                String line = directLog.ok(now);
                if (line != null && usingFallback) Server.log("直接问显示服务又可以了（" + line + "）");
                usingFallback = false;
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (frameworkGone(e)) resync = true;
                directMissing = e instanceof ClassNotFoundException || e instanceof NoSuchMethodException;
                String line = directLog.error(LogThrottle.describe(e), now);
                if (line != null) {
                    Server.log("直接问显示服务不行（" + line + "），改用 DisplayManagerGlobal（外接屏重新接上可能发现不了）");
                }
            }
        }
        source = null;
        Class<?> cls = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = cls.getMethod("getInstance").invoke(null);
        if (global == null) throw new IllegalStateException("DisplayManagerGlobal 取不到");
        getDisplayIds = cls.getMethod("getDisplayIds");
        idsArgs = new Object[0];
        getDisplayInfo = cls.getMethod("getDisplayInfo", int.class);
        source = global;
        usingFallback = true;
    }

    /** IDisplayManager：Android 13 起 getDisplayIds 带一个"含停用的屏"参数，更早的没有。 */
    private void useDisplayService() throws ReflectiveOperationException {
        String iface = "android.hardware.display.IDisplayManager";
        Object service = SystemServices.get("display", iface);
        Class<?> api = Class.forName(iface);
        try {
            getDisplayIds = api.getMethod("getDisplayIds", boolean.class);
            idsArgs = new Object[]{false};
        } catch (NoSuchMethodException e) {
            getDisplayIds = api.getMethod("getDisplayIds");
            idsArgs = new Object[0];
        }
        getDisplayInfo = api.getMethod("getDisplayInfo", int.class);
        getDisplayIds.invoke(service, idsArgs);   // 先试一次，不行就走退路
        source = service;
    }

    private void poll() throws ReflectiveOperationException {
        int[] ids = (int[]) getDisplayIds.invoke(source, idsArgs);
        if (resync && systemReady()) {
            known.clear();   // 重启期间拔掉的屏少发一次 REMOVED 没关系：它的通知已经随重启清掉了
            resync = false;
        }
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

    /** 异常像是系统框架重启造成的：原因链里有 DeadObjectException（含 DeadSystemException），或者显示服务不在。 */
    private static boolean frameworkGone(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).startsWith("系统服务 ")) return true;
            for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
                if (c.getName().equals("android.os.DeadObjectException")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * 用户 0 已解锁 = AMS 早已 systemReady（mProcessesReady 在启动用户之前就置真），清单广播能投递、App 也起得来。
     * 问不到（服务还不在、反射失败）就当还没好，下一轮再看。
     */
    private static boolean systemReady() {
        try {
            String iface = "android.app.IActivityManager";
            Object am = SystemServices.get("activity", iface);
            Method m = Class.forName(iface).getMethod("isUserRunning", int.class, int.class);
            return Boolean.TRUE.equals(m.invoke(am, 0, 4 /* ActivityManager.FLAG_AND_UNLOCKED */));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static int field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getField(name);
        return f.getInt(o);
    }

    /** -f 0x20：豆花从没打开过（"已停止"状态）也叫得醒。 */
    private static void broadcast(String action, int id, String name) {
        try {
            Shell.run(BROADCAST_TIMEOUT_MS, "am", "broadcast", "-f", "0x20", "-n", RECEIVER, "-a", action,
                    "--ei", "id", String.valueOf(id), "--es", "name", name);
        } catch (IOException e) {
            Server.log("叫豆花发通知失败：" + e.getMessage());
        }
    }
}
