package dev.douhua.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import dev.douhua.ConfigCodec;
import dev.douhua.GestureEngine;
import dev.douhua.Proto;

/**
 * 服务自测：冒充 App 连上服务，要求接管某块屏的触摸屏若干秒，然后停发心跳，看服务会不会自己释放。
 * 只验证"接管 / 释放"这条安全链路，自己不发鼠标位移、不点击。测试期间那块屏摸不动。
 * 第三个参数写 relay 就按蓝牙模式接管：服务把分流出的鼠标、键盘报文发回来，这里打印出来
 * （配合往触摸屏节点 sendevent 模拟触摸，不用真人去摸也能验证转发）。
 * App 正在用服务（还在说话）时，服务会回 hello ok=2（忙）并断开，这里打印出来就退出。
 * 再带一个 keys=<4 位 0/1>（音量+点一下、音量+按住、音量−点一下、音量−按住）就一起要求截音量键，
 * 打印服务发来的实体键事件和独占状态；停发心跳后应该收到"截住=0 原因=5"（已放开）。服务找不到触摸屏时实体键照样截（测试机能测）。
 *
 * 再带一个 focus（协议 22）就不接管（布局报 inactive），只发一次 CMD_FOCUS_OTHER，看服务交不交焦点：
 * 服务日志里有"分屏交焦点（屏幕 N）：…"一行（豆花的任务没拿着焦点时写"不交：焦点已经不在豆花"；服务用 --debug 起时还列出这块屏上看得见的任务和挑中的那个，看不见的只记个数）。
 * 只读地看会交给谁、不真交，用 Server tasks（豆花没拿着焦点时另外说"豆花拿着焦点的话会怎样"）。
 *
 * 用法：app_process / dev.douhua.server.TestClient [displayId] [接管秒数] [relay] [keys=1000] [focus]
 */
public final class TestClient {
    private TestClient() {}

    public static void main(String[] args) throws Exception {
        int displayId = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        boolean relay = false;
        boolean focus = false;
        String keys = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("relay")) relay = true;
            if (args[i].equals("focus")) focus = true;
            if (args[i].startsWith("keys=")) keys = args[i].substring(5);
        }
        if (keys != null && !keys.matches("[01]{4}")) {
            Server.log("自测：keys= 要 4 位 0/1，比如 keys=1000（只截音量+点一下）");
            System.exit(2);
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(Proto.HOST, Proto.PORT), 1000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Thread reader = new Thread(() -> printMessages(in), "reader");
            reader.setDaemon(true);
            reader.start();

            out.writeByte(Proto.CMD_CONFIG);
            ConfigCodec.write(out, new GestureEngine.Config(), 2.625f);   // 全用默认参数，密度 2.625
            out.writeByte(Proto.CMD_LAYOUT);
            out.writeByte(focus ? 0 : 1);   // focus：不接管触摸屏
            out.writeByte(relay ? 1 : 0);
            out.writeInt(displayId);
            out.writeByte(0);
            out.writeInt(1248);
            out.writeInt(1972);
            out.writeByte(1);   // 窗口占满整块屏（不分屏透传）
            for (int i = 0; i < 8; i++) out.writeInt(0);   // 窗口范围、系统手势带：占满时不用
            out.writeShort(1);
            out.writeByte(Proto.REGION_PAD);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(1248);
            out.writeInt(1972);
            if (keys != null) {
                out.writeByte(Proto.CMD_HARD_KEYS);
                for (int i = 0; i < 4; i++) out.writeByte(keys.charAt(i) - '0');
            }
            if (focus) {
                out.writeByte(Proto.CMD_FOCUS_OTHER);
                out.writeInt(displayId);
                out.flush();
                Server.log("自测：已请求把屏幕 " + displayId + " 上的键盘焦点交给另一个应用（不接管触摸屏），结果看服务日志");
                Thread.sleep(2000);
                Server.log("自测：断开");
                return;
            }
            out.flush();
            Server.log("自测：已请求接管屏幕 " + displayId + (keys != null ? "、截实体键 " + keys : "")
                    + "，保持 " + seconds + " 秒心跳");
            for (int i = 0; i < seconds * 2; i++) {
                out.writeByte(Proto.CMD_HEARTBEAT);
                out.flush();
                Thread.sleep(500);
            }
            Server.log("自测：停发心跳，服务应在 " + Proto.HEARTBEAT_TIMEOUT_MS + "ms 内释放");
            Thread.sleep(Proto.HEARTBEAT_TIMEOUT_MS + 1500);
        }
        Server.log("自测：断开");
    }

    private static void printMessages(DataInputStream in) {
        try {
            while (true) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case Proto.MSG_HELLO: {
                        int version = in.readUnsignedByte();
                        int ok = in.readUnsignedByte();
                        String message = in.readUTF();
                        if (version != Proto.VERSION) {   // 版本不对后面的格式就对不上（apk 字段是协议 16 加的），不往下读
                            Server.log("自测收到 HELLO version=" + version + "，和自测的 " + Proto.VERSION + " 不一致，停止");
                            System.exit(1);
                        }
                        Server.log("自测收到 HELLO version=" + version + " ok=" + ok + " " + message + " apk=" + in.readUTF());
                        if (ok == Proto.HELLO_BUSY) {
                            Server.log("自测：服务正被另一个连接（多半是豆花 App）占用，先把豆花退到后台再测");
                            System.exit(1);
                        }
                        break;
                    }
                    case Proto.MSG_GRAB:
                        Server.log("自测收到 接管=" + in.readUnsignedByte() + " 原因=" + in.readUnsignedByte() + " " + in.readUTF());
                        break;
                    case Proto.MSG_HAPTIC:
                        Server.log("自测收到 震动 " + in.readUnsignedByte());
                        break;
                    case Proto.MSG_GESTURE:
                        Server.log("自测收到 手势 " + in.readUnsignedByte() + "/" + in.readUnsignedByte());
                        break;
                    case Proto.MSG_MODIFIERS:   // 协议 21 起：锁住 / 按着 / 快锁了三个字节 + Fn（位：1 单次锁、2 按着、4 快永久锁了、8 永久锁，协议 22；16 F 行翻转着，协议 24）
                        Server.log("自测收到 修饰键 锁住=" + in.readUnsignedByte() + " 按着=" + in.readUnsignedByte()
                                + " 快锁了=" + in.readUnsignedByte() + " Fn=" + in.readUnsignedByte());
                        break;
                    case Proto.MSG_CAPS:
                        Server.log("自测收到 Caps 显示翻一下（" + (in.readUnsignedByte() == 1 ? "发了 Caps Lock" : "只切显示") + "）");
                        break;
                    case Proto.MSG_BUTTON:
                        Server.log("自测收到 按钮 " + in.readInt() + " " + in.readUnsignedByte());
                        break;
                    case Proto.MSG_MOUSE:
                        Server.log("自测收到 鼠标 键=" + in.readUnsignedByte() + " 位移=(" + in.readShort() + "," + in.readShort()
                                + ") 滚轮=" + in.readShort() + " 横滚=" + in.readShort() + "（滚轮单位 1/120 格）");
                        break;
                    case Proto.MSG_HARD_KEY:
                        Server.log("自测收到 实体键 " + in.readUnsignedByte() + " 事件 " + in.readUnsignedByte()
                                + "（键 0 音量+、1 音量−；事件 0 点一下、1 开始按住、2 结束按住）");
                        break;
                    case Proto.MSG_HARD_GRAB:
                        Server.log("自测收到 实体键 截住=" + in.readUnsignedByte() + " 截不了=" + in.readUnsignedByte()
                                + " 原因=" + in.readUnsignedByte() + " " + in.readUTF());
                        break;
                    case Proto.MSG_CONSUMER: {   // 协议 24：蓝牙模式转回来的多媒体 / 系统键（页 0x0C 多媒体、0x01 系统键；码 0 = 松开）
                        int page = in.readUnsignedByte();
                        int usage = in.readUnsignedShort();
                        Server.log("自测收到 " + (page == Proto.PAGE_CONSUMER ? "多媒体键" : "系统键") + " 0x" + Integer.toHexString(usage));
                        break;
                    }
                    case Proto.MSG_KEYBOARD: {
                        byte[] r = new byte[8];
                        in.readFully(r);
                        StringBuilder b = new StringBuilder();
                        for (byte x : r) b.append(String.format("%02x ", x));
                        Server.log("自测收到 键盘 " + b.toString().trim());
                        break;
                    }
                    case Proto.MSG_GAMEPAD: {
                        int flags = in.readUnsignedByte();
                        byte[] r = new byte[dev.douhua.Hid.GAMEPAD_REPORT_SIZE];
                        in.readFully(r);
                        StringBuilder b = new StringBuilder();
                        for (byte x : r) b.append(String.format("%02x ", x));
                        Server.log("自测收到 手柄 flags=" + flags + " " + b.toString().trim());
                        break;
                    }
                    default:
                        Server.log("自测收到未知消息 " + type);
                        return;
                }
            }
        } catch (IOException ignored) {
            // 连接关闭
        }
    }
}
