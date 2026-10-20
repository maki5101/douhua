package dev.douhua.server;

import java.io.IOException;

/**
 * 屏幕方向锁定（`wm user-rotation`），全服务一份。转之前的锁定记在这里，连接正常断开时恢复。
 *
 * 为什么不放在会话里：新连接能顶掉旧会话，两个会话会短暂并存。各记各的话，"旧会话恢复"和"新会话锁定"
 * 交错执行，屏幕会转回去（App 报的区域坐标就和真实触摸错位），新会话还可能把 App 选的方向错记成"原来的方向"。
 * 现在只有第一次锁某块屏时记原值，以后再锁不覆盖；被顶掉的会话不恢复，原值留给下一个会话。
 * 所有方法 synchronized：恢复和锁定不会交错。
 *
 * 执行命令、写日志都由外面传进来，纯 Java，能离线测。
 */
final class RotationLock {
    interface Cmd {
        String run(String... cmd) throws IOException;
    }

    interface Log {
        void log(String msg);
    }

    private final Cmd shell;
    private final Log log;

    /** 转过哪块屏、转之前的方向锁定（`wm user-rotation` 的输出，比如 "lock 0" / "free"）。 */
    private int display = -1;
    private String original;

    RotationLock(Cmd shell, Log log) {
        this.shell = shell;
        this.log = log;
    }

    /**
     * 把屏幕锁定到某个方向。App 自己请求方向不管用：这块屏设了"忽略 App 的方向请求"，
     * 系统只会把 App 画面转过来塞进竖屏，触摸坐标就对不上了。换了一块屏就先恢复上一块。
     */
    synchronized void lock(int displayId, int rotation) {
        if (rotation < 0 || rotation > 3) return;
        String id = String.valueOf(displayId);
        if (display != displayId) {
            restore();
            original = run("wm", "user-rotation", "-d", id).trim();
            display = displayId;
        }
        run("wm", "user-rotation", "-d", id, "lock", String.valueOf(rotation));
        log.log("屏幕 " + displayId + " 转到方向 " + rotation + "（原来是 " + original + "）");
    }

    /** 恢复原来的方向，然后清空记录；没转过就什么都不做。 */
    synchronized void restore() {
        if (display < 0 || original == null) return;
        String id = String.valueOf(display);
        String[] parts = original.split("\\s+");
        if (parts.length == 2 && parts[0].equals("lock") && parts[1].matches("[0-3]")) {
            run("wm", "user-rotation", "-d", id, "lock", parts[1]);
            log.log("屏幕 " + display + " 恢复成原来的方向：" + original);
        } else if (parts[0].equals("free")) {
            run("wm", "user-rotation", "-d", id, "free");
            log.log("屏幕 " + display + " 恢复成原来的方向：" + original);
        } else {
            log.log("看不懂屏幕 " + display + " 原来的方向（" + original + "），不恢复");
        }
        display = -1;
        original = null;
    }

    /** 出错只记日志、返回空串：读不到原值时恢复那一步会因为"看不懂"而跳过。 */
    private String run(String... cmd) {
        try {
            return shell.run(cmd);
        } catch (IOException e) {
            log.log("执行 " + String.join(" ", cmd) + " 失败：" + e.getMessage());
            return "";
        }
    }
}
