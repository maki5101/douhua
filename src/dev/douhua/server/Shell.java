package dev.douhua.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 起一个子进程（wm、dumpsys、am、getevent）、读完输出、等它结束，带超时。
 * 以前这些 waitFor 都没有上限：会话收尾时恢复方向的 wm 要是卡住，整个服务就跟着卡住。
 * 只用 JDK，能离线测；自己不写日志，出错抛 IOException，由调用方决定怎么记。
 */
final class Shell {
    private Shell() {}

    /** 分开读的结果：退出码、标准输出、标准错误。 */
    static final class Result {
        final int exit;
        final String out;
        final String err;

        Result(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    /**
     * 返回标准输出和标准错误合在一起的内容；退出码不是 0 也照样返回。
     * 超过 timeoutMs 没结束就结束掉它，抛 IOException（消息里带超时秒数）。
     */
    static String run(long timeoutMs, String... cmd) throws IOException {
        return exec(timeoutMs, true, cmd).out;
    }

    /**
     * 同 {@link #run}，但标准输出、标准错误分开读，带上退出码。要按行解析、又要分清哪些是错误的命令用这个
     * （getevent -i 的 "could not open …" 只从标准错误里认，不用管它和设备块的行怎么排在一起）。
     */
    static Result runSplit(long timeoutMs, String... cmd) throws IOException {
        return exec(timeoutMs, false, cmd);
    }

    private static Result exec(long timeoutMs, boolean merge, String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(merge).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // 输出在另一个线程里读：读本身也可能一直阻塞（进程不退出），超时要靠 waitFor 判断
        Thread outReader = reader(p.getInputStream(), out);
        Thread errReader = merge ? null : reader(p.getErrorStream(), err);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
                throw new IOException(String.join(" ", cmd) + " 超过 " + seconds(timeoutMs) + " 秒没结束，已结束掉它");
            }
            // 进程退出了，输出一般马上读完；它的子进程还占着输出管道时不死等，有多少拿多少
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            outReader.join(Math.max(100, left));
            if (errReader != null) {
                left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                errReader.join(Math.max(100, left));
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(String.join(" ", cmd) + " 被打断");
        }
        return new Result(p.exitValue(), text(out), text(err));
    }

    private static Thread reader(InputStream in, ByteArrayOutputStream buf) {
        Thread t = new Thread(() -> {
            byte[] chunk = new byte[16384];
            try (InputStream s = in) {
                int n;
                while ((n = s.read(chunk)) > 0) {
                    synchronized (buf) {
                        buf.write(chunk, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程被结束掉了
            }
        }, "douhua-shell");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String text(ByteArrayOutputStream buf) {
        synchronized (buf) {
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String seconds(long ms) {
        return ms % 1000 == 0 ? String.valueOf(ms / 1000) : String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
