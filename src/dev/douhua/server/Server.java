package dev.douhua.server;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
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
 * 独占触摸屏读原始触摸、识别手势、造虚拟鼠标、往 DeX 屏幕注入按键。具体见 {@link Session}。
 * 同一时间只服务一个 App 连接；连接断开，触摸屏释放、虚拟鼠标移除。
 *
 * 用法：app_process / dev.douhua.server.Server uid=<豆花 App 的 uid> [debug]
 *      debug：每秒记一行发出的鼠标包统计（包数、最长间隔、位移），查卡顿用
 *      app_process / dev.douhua.server.Server probe [秒数]   只造鼠标画个方块，不点击，用来验证这条路通不通
 *      app_process / dev.douhua.server.Server focus <屏幕编号>   只把键盘输入切到那块屏（验证 DexFocus 用）
 */
public final class Server {
    private static final int UID_ROOT = 0;
    private static final int UID_SHELL = 2000;

    private static boolean debug;

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
            server.bind(new InetSocketAddress(InetAddress.getByName(Proto.HOST), Proto.PORT), 1);
        } catch (IOException e) {
            log("端口 " + Proto.PORT + " 被占用（" + e.getMessage() + "）：多半是已有一个豆花服务在运行，"
                    + "要重启先运行 tools/stop-server.sh。");
            return;
        }

        Injector injector = null;
        String injectorError = null;
        try {
            injector = new Injector();
        } catch (ReflectiveOperationException e) {
            injectorError = e.toString();
            log("按键注入不可用，只能当鼠标用：" + injectorError);
        }

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
            serve(client, appUid, injector, injectorError);
        }
    }

    private static void serve(Socket client, int appUid, Injector injector, String injectorError) {
        try (Socket c = client) {
            int uid = PeerUid.lookup(c.getPort(), c.getLocalPort());
            if (uid != appUid && uid != UID_SHELL && uid != UID_ROOT) {
                log("拒绝了 uid=" + uid + " 的连接（不是豆花 App）");
                return;
            }
            c.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream()));
            DataOutputStream out = new DataOutputStream(c.getOutputStream());

            UhidMouse mouse;
            UhidKeyboard keyboard;
            try {
                mouse = new UhidMouse("Douhua Touchpad");
            } catch (IOException e) {
                log("创建虚拟鼠标失败：" + e.getMessage());
                hello(out, false, "创建虚拟鼠标失败：" + e.getMessage());
                return;
            }
            try {
                keyboard = new UhidKeyboard("Douhua Keyboard");
            } catch (IOException e) {
                mouse.close();
                log("创建虚拟键盘失败：" + e.getMessage());
                hello(out, false, "创建虚拟键盘失败：" + e.getMessage());
                return;
            }
            try {
                hello(out, true, injectorError == null ? "" : "按键注入不可用：" + injectorError);
                log("App 已连接（uid=" + uid + "），虚拟鼠标和键盘已创建");
                new Session(in, out, mouse, keyboard, injector, debug).run();
            } finally {
                keyboard.close();
                mouse.close();
                log("App 已断开，虚拟鼠标和键盘已移除");
            }
        } catch (IOException e) {
            log("连接出错：" + e);
        }
    }

    private static void hello(DataOutputStream out, boolean ok, String message) throws IOException {
        out.writeByte(Proto.MSG_HELLO);
        out.writeByte(Proto.VERSION);
        out.writeByte(ok ? 1 : 0);
        out.writeUTF(message);
        out.flush();
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
