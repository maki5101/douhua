package dev.douhua;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 手机自己拉起服务时执行的 shell 命令（ServerCommands）：和 tools/start-server.sh、stop-server.sh 对得上，
 * 单引号转义对，交给本机 sh -n 检查语法（只检查、不执行）；清单里申请了 pm grant 的权限、迁移规则不带 adb 密钥。
 * 在仓库根目录跑（tools/test.sh）。
 */
public final class ServerCommandsTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        String start = ServerCommands.startServer("/data/app/~~x==/dev.douhua-1/base.apk", 10234, false);
        check(start.contains("CLASSPATH='/data/app/~~x==/dev.douhua-1/base.apk' setsid nohup sh -c ")
                && start.contains("app_process / --nice-name=douhua_server dev.douhua.server.Server uid=10234; echo ")
                && start.contains(">>/data/local/tmp/douhua-server.log 2>&1 </dev/null &")
                && start.contains("退出码 $?"), "起服务命令（start-server.sh 那条 + 诊断）：" + start);
        check(start.startsWith("if [ -f /data/local/tmp/douhua-server.log ]; then mv -f /data/local/tmp/douhua-server.log "
                + "/data/local/tmp/douhua-server.log.prev; fi;"), "旧日志改名 .prev");
        check(ServerCommands.startServer("/a.apk", 1, true).contains("uid=1 debug; echo"), "debug 参数");
        check(!start.contains(" debug"), "默认不带 debug");
        check(ServerCommands.quote("a'b").equals("'a'\\''b'"), "单引号转义");
        check(ServerCommands.grantSecureSettings("dev.douhua")
                .equals("pm grant 'dev.douhua' android.permission.WRITE_SECURE_SETTINGS"), "pm grant");
        String stop = ServerCommands.stopServer();
        check(stop.contains("pidof douhua_server") && stop.contains("kill -9"), "停服务：pidof + 兜底 kill -9");
        check(stop.contains("-lt 15") && stop.contains("sleep 0.2"), "停服务：每 0.2 秒看、最多 15 次（同 stop-server.sh）");
        check(syntaxOk(stop), "停服务命令 sh -n 语法检查");
        check(syntaxOk(start), "起服务命令 sh -n 语法检查");
        check(syntaxOk(ServerCommands.startServer("/data/app/it's here/base.apk", 1, false)), "路径带单引号也不破坏语法");
        // pm grant 只给清单里申请过的权限（不然报 "has not requested permission"），豆花就永远开关不了无线调试
        String grant = ServerCommands.grantSecureSettings("dev.douhua");
        String perm = grant.substring(grant.lastIndexOf(' ') + 1);
        String manifest = new String(Files.readAllBytes(Paths.get("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(Pattern.compile("<uses-permission\\s+android:name=\"" + Pattern.quote(perm) + "\"").matcher(manifest).find(),
                "清单里申请了 pm grant 的权限 " + perm);
        // 换机迁移不带 adb 密钥和配对状态（Keystore 里的包装密钥不跟着走，搬过去只会显示"已配对"却起不来）
        String rules = new String(Files.readAllBytes(Paths.get("res/xml/data_extraction_rules.xml")), StandardCharsets.UTF_8);
        check(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""), "清单用了迁移规则");
        check(count(rules, "<exclude domain=\"file\" path=\"adb/\" />") == 2
                        && count(rules, "<exclude domain=\"sharedpref\" path=\"selfstart.xml\" />") == 2,
                "云备份、换机迁移都不带 files/adb/ 和 selfstart.xml");
        System.out.println("服务命令测试：" + checks + " 项，失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) n++;
        return n;
    }

    private static boolean syntaxOk(String script) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start();
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
