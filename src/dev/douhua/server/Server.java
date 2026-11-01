package dev.douhua.server;

import android.os.SystemClock;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dev.douhua.Proto;

/**
 * 豆花的特权服务：以 shell 身份运行（adb 或 Shizuku 用 app_process 拉起），替 App 做它自己没权限做的事——
 * 独占触摸屏读原始触摸、识别手势、造虚拟鼠标、往外接屏注入按键。具体见 {@link Session}。
 * 连接断开，触摸屏释放、虚拟鼠标移除。
 *
 * 主线程只管 accept，每条连接在自己的线程（douhua-session）里跑。以前 accept 和会话在同一个线程里串行：
 * 一条死连接（App 进程没了、断开的包被 Doze 掐掉，服务这边一直停在"已连接"）就把后来的连接全堵在内核的
 * accept 队列里，App 一直连不上，服务看着却在运行（2026-10-19 主力机）。
 * 现在同一时间仍只有一个会话在用：新连接来时，旧会话 5 秒以上没消息就被顶掉，还在说话就给新连接回"忙"
 * （见 {@link SessionPolicy}）；会话 10 秒收不到任何消息也自己结束。
 *
 * 用法：app_process / dev.douhua.server.Server uid=<豆花 App 的 uid> [debug]
 *      debug：每秒记一行发出的鼠标包统计（包数、最长间隔、位移），查卡顿用
 *      app_process / dev.douhua.server.Server probe [秒数]   只造鼠标画个方块，不点击，用来验证这条路通不通
 *      app_process / dev.douhua.server.Server focus <屏幕编号>   只把键盘输入切到那块屏（验证 DexFocus 用）
 *      app_process / dev.douhua.server.Server keys   只读：用 getevent -i 列出输入设备、音量键能不能单独截、为什么（不独占任何设备）
 */
public final class Server {
    private static final int UID_ROOT = 0;
    private static final int UID_SHELL = 2000;

    private static boolean debug;
    /** 最近接受的会话；只在主线程里读写。 */
    private static Session current;
    /** App 每秒重试一次，回"忙"的日志要限频。 */
    private static final LogThrottle busyLog = new LogThrottle(60_000);

    private Server() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("probe")) {
            probe(args.length > 1 ? Integer.parseInt(args[1]) : 5);
            return;
        }
        if (args.length > 1 && args[0].equals("grabtest")) {
            grabTest(args[1]);
            return;
        }
        if (args.length > 0 && args[0].equals("keys")) {
            listKeys();
            return;
        }
        if (args.length > 1 && args[0].equals("focus")) {
            log(DexFocus.moveTo(Integer.parseInt(args[1])) ? "已请求把键盘输入切到屏幕 " + args[1] : "切换失败，见上一行");
            return;
        }
        int appUid = -1;
        for (String a : args) {
            if (a.startsWith("uid=")) appUid = Integer.parseInt(a.substring(4));
            if (a.equals("debug")) debug = true;
        }
        if (appUid < 0) {
            log("缺少参数 uid=<豆花 App 的 uid>。请用 tools/start-server.sh 启动，它会自动查 uid。");
            System.exit(2);
        }

        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            // 队列留宽一点：主线程只管 accept，平时排不起来；排起来了 tools/start-server.sh 会认出服务卡住了
            server.bind(new InetSocketAddress(InetAddress.getByName(Proto.HOST), Proto.PORT), 8);
        } catch (IOException e) {
            log("端口 " + Proto.PORT + " 被占用（" + e.getMessage() + "）：多半是已有一个豆花服务在运行，"
                    + "要重启先运行 tools/stop-server.sh。");
            return;
        }

        Injector injector = new Injector();
        String injectStatus = injector.status();
        if (injectStatus != null) log("按键注入暂时不可用（每次连接时再试）：" + injectStatus);

        log("已启动，等待豆花 App 连接（允许的 uid=" + appUid + "）" + (debug ? "，诊断模式" : ""));
        DisplayWatcher.start();
        while (true) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                log("accept 失败：" + e);
                sleep(1000);
                continue;
            }
            admit(client, appUid, injector);
        }
    }

    /** 决定新连接接不接、旧会话顶不顶（见 {@link SessionPolicy}），接了就在新线程里跑会话。不阻塞。 */
    private static void admit(Socket client, int appUid, Injector injector) {
        int uid = PeerUid.lookup(client.getPort(), client.getLocalPort());
        if (uid != appUid && uid != UID_SHELL && uid != UID_ROOT) {
            log("拒绝了 uid=" + uid + " 的连接（不是豆花 App）");
            closeQuietly(client);
            return;
        }
        Session old = current;
        boolean oldOpen = old != null && !old.isEnded();
        // 已经在收尾的旧会话（App 刚断开）不算"在用"：不回"忙"，新会话等它收完尾再开始。
        // App 每次回到前台都是先断开旧连接、马上连新的，正好撞上旧会话收尾的那零点几秒
        boolean oldTalking = oldOpen && !old.isClosing();
        long silent = oldTalking ? old.silentMs() : 0;
        long now = SystemClock.uptimeMillis();
        int verdict = SessionPolicy.admit(oldTalking, silent);
        if (verdict == SessionPolicy.BUSY) {
            String line = busyLog.error("另一个连接正在用，回了\"忙\"", now);
            if (line != null) log("新连接（uid=" + uid + "）被拒：" + line);
            try {
                hello(new DataOutputStream(client.getOutputStream()), Proto.HELLO_BUSY, "");
            } catch (IOException ignored) {
                // 对面已经走了
            }
            closeQuietly(client);
            return;
        }
        busyLog.ok(now);
        if (verdict == SessionPolicy.KICK_OLD) {
            log("旧连接 " + silent / 1000 + " 秒没消息，被新连接顶掉");
            old.kick();
        }
        Session s;
        try {
            client.setTcpNoDelay(true);
            s = new Session(client, uid, injector, debug);
        } catch (IOException e) {
            log("连接出错：" + e);
            closeQuietly(client);
            return;
        }
        current = s;
        Session prev = oldOpen ? old : null;   // 顶掉的、正在收尾的，都等它收完尾
        new Thread(() -> s.run(prev), "douhua-session").start();
    }

    /** 连上后先发的一条（见 Proto.MSG_HELLO）。 */
    static void hello(DataOutputStream out, int code, String message) throws IOException {
        out.writeByte(Proto.MSG_HELLO);
        out.writeByte(Proto.VERSION);
        out.writeByte(code);
        out.writeUTF(message);
        out.writeUTF(apkPath());
        out.flush();
    }

    /** 这个服务是从哪个 APK 起的：start-server.sh 和 App 自己拉起时都用 CLASSPATH=<APK> 起 app_process。 */
    static String apkPath() {
        String p = System.getenv("CLASSPATH");
        if (p == null || p.isEmpty()) p = System.getProperty("java.class.path", "");
        return p.length() > 4096 ? "" : p;   // writeUTF 有长度上限；这么长的不会是 APK 路径
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 已经关了
        }
    }

    /** 只移动、不点击：造一个虚拟鼠标，画一个方块，停留几秒后移除。 */
    private static void probe(int holdSeconds) {
        UhidMouse m;
        try {
            m = new UhidMouse("Douhua Probe");
        } catch (IOException e) {
            log("探测失败：创建虚拟鼠标出错：" + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            log("探测：虚拟鼠标已创建，1 秒后画方块（只移动，不点击）");
            sleep(1000);
            int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            for (int[] d : dirs) {
                for (int i = 0; i < 60; i++) {
                    m.send(0, d[0] * 8, d[1] * 8, 0, 0);
                    sleep(8);
                }
            }
            log("探测：方块画完，" + holdSeconds + " 秒后移除虚拟鼠标");
            sleep(holdSeconds * 1000L);
        } catch (IOException e) {
            log("探测失败：写虚拟鼠标出错：" + e.getMessage());
        } finally {
            m.close();
        }
    }

    /**
     * 只读：用 getevent -i 列出所有输入设备、能不能单独截、为什么，再按"桌面模式下音量上 / 音量下都配了动作"算一遍会截哪块、旁观哪块。
     * 不独占、不读任何设备的事件，在主力机上收集真实设备分组时用。
     */
    private static void listKeys() {
        java.util.List<KeyDevices.Device> devs;
        try {
            devs = KeyDevices.read();
        } catch (IOException e) {
            log("列输入设备失败：" + e.getMessage());
            System.exit(1);
            return;
        }
        int vol = 0, unopenable = 0;
        for (KeyDevices.Device d : devs) {
            if (d.openError != null) unopenable++;
            if (d.hasVolume()) vol++;
            log("设备 " + d.describe());
        }
        log("共 " + devs.size() + " 个输入设备（getevent 打不开的 " + unopenable + " 个），带音量键的 " + vol + " 个");
        KeyDevices.Plan p = KeyDevices.plan(devs, new KeyDevices.Config(true, true));
        log("按桌面模式下音量上 / 音量下都配了动作：独占 " + names(p.grab) + "；旁观（认电源键 + 音量键的组合键）" + names(p.watch));
        log("截得住：" + keyNames(p.grabbedBits) + "；截不了：" + keyNames(p.unavailableBits)
                + (p.detail.isEmpty() ? "" : "（" + p.detail + "）"));
    }

    private static String names(java.util.List<KeyDevices.Device> ds) {
        if (ds.isEmpty()) return "无";
        StringBuilder b = new StringBuilder();
        for (KeyDevices.Device d : ds) {
            if (b.length() > 0) b.append("，");
            b.append(d.name).append("（").append(d.path).append("）");
        }
        return b.toString();
    }

    private static String keyNames(int bits) {
        if (bits == 0) return "无";
        String up = (bits & Proto.HARD_BIT_VOL_UP) != 0 ? "音量上" : "";
        String down = (bits & Proto.HARD_BIT_VOL_DOWN) != 0 ? "音量下" : "";
        return up.isEmpty() || down.isEmpty() ? up + down : up + "、" + down;
    }

    /** 独占某个输入设备 0.5 秒再释放，验证能不能独占。 */
    private static void grabTest(String path) {
        try {
            java.io.FileDescriptor fd = Evdev.open(path);
            try {
                Evdev.grab(fd);
                log("独占测试：已独占 " + path + "，0.5 秒后释放");
                sleep(500);
            } finally {
                Evdev.close(fd);
            }
            log("独占测试：已释放");
        } catch (IOException e) {
            log("独占测试失败：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        System.err.println(time + " [豆花服务] " + msg);
    }
}
