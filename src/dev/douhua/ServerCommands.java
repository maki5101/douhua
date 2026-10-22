package dev.douhua;

/**
 * 手机自己拉起服务时经 adb shell 执行的命令（SelfStart 用），和电脑那条路 tools/start-server.sh、stop-server.sh 做同样的事。纯 Java，离线测。
 * 改其中一边时两边对照着看：进程名 douhua_server、日志 /data/local/tmp/douhua-server.log（旧的改名 .prev）、
 * 先 SIGTERM 约 3 秒不退再 kill -9。
 */
final class ServerCommands {
    static final String PROCESS = "douhua_server";
    static final String LOG = "/data/local/tmp/douhua-server.log";

    private ServerCommands() {}

    /** 授权 App 写安全设置（之后 App 能自己开关无线调试）。 */
    static String grantSecureSettings(String pkg) {
        return "pm grant " + quote(pkg) + " android.permission.WRITE_SECURE_SETTINGS";
    }

    /**
     * 停旧服务：没在跑什么都不做；SIGTERM 后每 0.2 秒看一次、最多 15 次，还在就 kill -9。最后一行输出 stopped / none / alive。
     * 只用 toybox / mksh 都有的东西（pidof、kill、sleep、while）。
     */
    static String stopServer() {
        return "p=$(pidof " + PROCESS + "); "
                + "if [ -z \"$p\" ]; then echo none; exit 0; fi; "
                + "kill $p; i=0; "
                + "while [ $i -lt 15 ] && [ -n \"$(pidof " + PROCESS + ")\" ]; do sleep 0.2; i=$((i+1)); done; "
                + "p=$(pidof " + PROCESS + "); "
                + "if [ -n \"$p\" ]; then kill -9 $p; sleep 0.5; fi; "
                + "if [ -n \"$(pidof " + PROCESS + ")\" ]; then echo alive; else echo stopped; fi";
    }

    /**
     * 起服务：和 start-server.sh 一样的 setsid nohup app_process（后台，日志落到 LOG，上一份改名 .prev）。
     * 手机自己拉起时多两行诊断（2026-10-22 主力机上服务起不来、日志是空的，logcat 又几秒就被刷掉）：
     * 先写一行"启动命令执行了"，app_process 一退出再写退出码（137 = 被 kill -9、129 = SIGHUP、143 = SIGTERM），
     * 所以 app_process 外面套了一层 sh -c，服务跑着时多一个空等的 sh 进程。
     */
    static String startServer(String apkPath, int appUid, boolean debug) {
        String server = "app_process / --nice-name=" + PROCESS + " dev.douhua.server.Server uid=" + appUid + (debug ? " debug" : "");
        // 诊断（同一天第二轮：服务连同包装的 sh 一起被杀，退出码都没来得及写）：记下进程组 / 会话 / cgroup，
        // 收到 TERM 记一笔，1 秒后和 4 秒后各看一次还在不在
        String wrapper = "echo \"[豆花] 包装进程 $$：进程组 / 会话 $(cut -d' ' -f5,6 /proc/$$/stat)，cgroup $(tr '\\n' ' ' </proc/$$/cgroup)\"; "
                + "trap 'echo \"[豆花] 包装进程收到 TERM\"' TERM; "
                + server + "; echo \"[豆花] app_process 退出，退出码 $? $(date '+%H:%M:%S')\"";
        return "if [ -f " + LOG + " ]; then mv -f " + LOG + " " + LOG + ".prev; fi; "
                + "echo \"[豆花] 启动命令执行了 $(date '+%H:%M:%S')；外层 sh $$：进程组 / 会话 $(cut -d' ' -f5,6 /proc/$$/stat)\" >" + LOG + "; "
                + "CLASSPATH=" + quote(apkPath) + " setsid nohup sh -c " + quote(wrapper)
                + " >>" + LOG + " 2>&1 </dev/null & "
                + "w=$!; sleep 1; "
                + "if [ -d /proc/$w ]; then echo \"[豆花] 1 秒后包装进程 $w 还在，服务 $(pidof " + PROCESS + ")\" >>" + LOG + "; "
                + "else echo \"[豆花] 1 秒后包装进程 $w 已经没了\" >>" + LOG + "; fi; "
                + "setsid sh -c " + quote("sleep 3; echo \"[豆花] 4 秒后服务：$(pidof " + PROCESS + ")\"")
                + " >>" + LOG + " 2>&1 </dev/null &";
    }

    /** sh 单引号转义：'…'，里面的 ' 写成 '\''。 */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
