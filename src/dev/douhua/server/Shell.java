package dev.douhua.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 起一个子进程（wm、dumpsys、am）、读完输出、等它结束，带超时。
 * 以前这些 waitFor 都没有上限：会话收尾时恢复方向的 wm 要是卡住，整个服务就跟着卡住。
 * 只用 JDK，能离线测；自己不写日志，出错抛 IOException，由调用方决定怎么记。
 */
final class Shell {
    private Shell() {}

    /**
     * 返回标准输出和标准错误合在一起的内容；退出码不是 0 也照样返回。
     * 超过 timeoutMs 没结束就结束掉它，抛 IOException（消息里带超时秒数）。
     */
    static String run(long timeoutMs, String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // 输出在另一个线程里读：读本身也可能一直阻塞（进程不退出），超时要靠 waitFor 判断
        Thread reader = new Thread(() -> {
            byte[] chunk = new byte[16384];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(chunk)) > 0) {
                    synchronized (buf) {
                        buf.write(chunk, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程被结束掉了
            }
        }, "douhua-shell");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
                throw new IOException(String.join(" ", cmd) + " 超过 " + seconds(timeoutMs) + " 秒没结束，已结束掉它");
            }
            // 进程退出了，输出一般马上读完；它的子进程还占着输出管道时不死等，有多少拿多少
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            reader.join(Math.max(100, left));
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(String.join(" ", cmd) + " 被打断");
        }
        synchronized (buf) {
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String seconds(long ms) {
        return ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
