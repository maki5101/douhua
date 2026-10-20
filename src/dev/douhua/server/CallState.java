package dev.douhua.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 手机是不是正在来电响铃 / 通话中：这时实体键（电源键静音 / 挂断、音量键静音）要还给系统，不截。
 *
 * App 那边只看 AudioManager.getMode()，手机设成振动 / 静音 / 勿扰时来电不响铃，Telecom 不改音频模式（还是 MODE_NORMAL），
 * App 看不出来。这里由服务直接问 Telecom（ITelecomService，隐藏 API，反射）的 isRinging / isInCall：
 * shell 本来有读电话状态的权限，App 不用多申请权限。参数个数各系统版本不同（比如 isInCall 后来多了 callingFeatureId），
 * 所以按方法名找：第一个 String 参数填调用方包名 "com.android.shell"，别的参数填 null / 0 / false。
 *
 * 问一次走一趟 binder，所以结果缓存 {@link #REFRESH_MS}。查不到（反射失败、服务不在）就当没在通话，不因此拒绝截键，
 * 只记日志（限频），靠 App 那边的 getMode 兜底；代理丢掉，下次重新取（框架重启后旧代理就死了，见 {@link SystemServices}）。
 * 通话结束后再等 {@link HardKeyGate#COOLDOWN} 才算空闲：刚挂断时顺手按的电源键（灭屏）照样归系统。
 *
 * 不是线程安全的：只在实体键线程（douhua-keys）里用。
 */
final class CallState {
    private static final String IFACE = "com.android.internal.telecom.ITelecomService";
    private static final String CALLER = "com.android.shell";
    static final long REFRESH_MS = 400;

    private Object service;
    private Method isRinging;
    private Method isInCall;
    /** 这个系统上根本没有这个接口或方法：不用再试。 */
    private boolean missing;
    private long checkedAt = Long.MIN_VALUE / 4;
    /** 最近一次看到响铃 / 通话中的时间；没看到过时很久以前。 */
    private long busySeenAt = Long.MIN_VALUE / 4;
    private boolean busy;
    private final LogThrottle log = new LogThrottle(60_000);

    /** 正在来电响铃、通话中，或者刚结束不到冷却时间。now 是 SystemClock.uptimeMillis。 */
    boolean busy(long now) {
        if (now - checkedAt >= REFRESH_MS) {
            checkedAt = now;
            if (query(now)) busySeenAt = now;
            boolean b = now - busySeenAt < HardKeyGate.COOLDOWN;
            if (b != busy) {
                busy = b;
                Server.log(b ? "来电响铃或通话中：实体键还给系统" : "通话结束 " + HardKeyGate.COOLDOWN / 1000 + " 秒了：可以再截实体键");
            }
        }
        return busy;
    }

    /** 现在是不是在响铃 / 通话中；查不到返回 false。 */
    private boolean query(long now) {
        if (missing) return false;
        try {
            if (service == null) {
                Object s = SystemServices.get("telecom", IFACE);
                Class<?> api = Class.forName(IFACE);
                isRinging = find(api, "isRinging");
                isInCall = find(api, "isInCall");
                service = s;
            }
            boolean b = call(isRinging) || call(isInCall);
            String ok = log.ok(now);
            if (ok != null) Server.log("查来电状态" + ok);
            return b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            service = null;
            // 没有这个接口 / 方法、没有权限：一直都会这样，不用再试
            missing = e instanceof ClassNotFoundException || e instanceof NoSuchMethodException
                    || (e instanceof InvocationTargetException && e.getCause() instanceof SecurityException);
            String line = log.error(LogThrottle.describe(e), now);
            if (line != null) {
                Server.log((missing ? "这个系统上查不了来电状态（来电时实体键靠 App 那边判断放开）：" : "查来电状态出错（下次重试）：") + line);
            }
            return false;
        }
    }

    private boolean call(Method m) throws ReflectiveOperationException {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean callerSet = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == String.class && !callerSet) {
                args[i] = CALLER;
                callerSet = true;
            } else if (t == boolean.class) {
                args[i] = false;
            } else if (t == int.class) {
                args[i] = 0;
            } else if (t == long.class) {
                args[i] = 0L;
            }
        }
        return Boolean.TRUE.equals(m.invoke(service, args));
    }

    /** 按名字找（参数个数各版本不同）；同名的有好几个时取参数最少的。 */
    private static Method find(Class<?> api, String name) throws NoSuchMethodException {
        Method best = null;
        for (Method m : api.getMethods()) {
            if (!m.getName().equals(name) || m.getReturnType() != boolean.class) continue;
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) best = m;
        }
        if (best == null) throw new NoSuchMethodException(IFACE + "." + name);
        return best;
    }
}
