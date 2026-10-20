package dev.douhua.server;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/**
 * 给反复出现的同一条错误限频：同一条只记第一次，之后每 {@code intervalMs} 记一行"又出现 N 次"；
 * 换成别的错误立刻记；恢复正常时记一行"恢复了（出错 N 次、持续 T 秒）"。
 * 起因：系统框架重启后，盯外接屏的线程每 1.5 秒刷一行同样的错，日志里全是它，真正有用的几行被淹掉。
 *
 * 自己不写日志：返回要记的那一行（不用记时返回 null），由调用方加上前缀交给 Server.log。
 * 时间由调用方传入（SystemClock.uptimeMillis），纯 Java，能离线测。不是线程安全的，一个调用方一份。
 */
final class LogThrottle {
    private final long intervalMs;

    /** 现在这一段出错是从什么时候开始的；没在出错时 < 0。 */
    private long firstAt = -1;
    /** 这一段一共出了几次错（换了错误也接着数）。 */
    private int total;
    private String last;
    private long lastLoggedAt;
    /** 上次记下以后又出现、但没记的次数。 */
    private int suppressed;

    LogThrottle(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    /** 出错了：返回要记的一行，不用记时返回 null。 */
    String error(String message, long now) {
        total++;
        if (firstAt < 0) firstAt = now;
        if (!message.equals(last)) {
            last = message;
            lastLoggedAt = now;
            suppressed = 0;
            return message;
        }
        if (now - lastLoggedAt < intervalMs) {
            suppressed++;
            return null;
        }
        String line = suppressed > 0 ? message + "（又出现 " + suppressed + " 次）" : message;
        lastLoggedAt = now;
        suppressed = 0;
        return line;
    }

    /** 成功了：之前在出错就返回"恢复了"那一行，否则 null。 */
    String ok(long now) {
        if (firstAt < 0) return null;
        String line = String.format(Locale.ROOT, "恢复了（出错 %d 次、持续 %d 秒）", total, (now - firstAt) / 1000);
        firstAt = -1;
        total = 0;
        last = null;
        suppressed = 0;
        return line;
    }

    /**
     * 把异常连同它的原因链写成一行，比如 "java.lang.RuntimeException ← android.os.DeadSystemException"。
     * 反射调用的异常外面包着 InvocationTargetException，它自己的 toString 只有类名、看不出真正的原因。
     */
    static String describe(Throwable t) {
        StringBuilder b = new StringBuilder();
        String innermost = null;
        int depth = 0;
        for (Throwable c = t; c != null && depth < 8; c = c.getCause(), depth++) {
            if (b.length() > 0) b.append(" ← ");
            b.append(c.getClass().getName());
            if (c.getMessage() != null && !(c instanceof InvocationTargetException)) innermost = c.getMessage();
            if (c.getCause() == c) break;
        }
        if (innermost != null) b.append("：").append(innermost);
        return b.toString();
    }
}
