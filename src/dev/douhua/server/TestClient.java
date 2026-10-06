package dev.douhua.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import dev.douhua.Proto;

/**
 * 服务自测：冒充 App 连上服务，要求接管某块屏的触摸屏若干秒，然后停发心跳，看服务会不会自己释放。
 * 只验证"接管 / 释放"这条安全链路，不发鼠标位移、不点击。测试期间那块屏摸不动。
 *
 * 用法：app_process / dev.douhua.server.TestClient [displayId] [接管秒数]
 */
public final class TestClient {
    private TestClient() {}

    public static void main(String[] args) throws Exception {
        int displayId = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(Proto.HOST, Proto.PORT), 1000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            Thread reader = new Thread(() -> printMessages(in), "reader");
            reader.setDaemon(true);
            reader.start();

            out.writeByte(Proto.CMD_CONFIG);
            out.writeFloat(2f);
            out.writeFloat(1f);
            out.writeFloat(20f);
            out.writeByte(1);   // 自然滚动
            out.writeByte(1);   // 开合缩放
            out.writeByte(1);   // 边缘滚动
            out.writeByte(1);   // 横向边缘滚动
            out.writeFloat(28f);   // 边缘带宽度
            out.writeByte(2);   // 咔嗒：所有滚动
            out.writeByte(1);   // 长按拖动
            out.writeInt(450);
            out.writeByte(1);   // 轻点拖动
            out.writeInt(220);
            out.writeFloat(2.625f);
            out.writeByte(Proto.CMD_LAYOUT);
            out.writeByte(1);
            out.writeInt(displayId);
            out.writeByte(0);
            out.writeInt(1248);
            out.writeInt(1972);
            out.writeShort(1);
            out.writeByte(Proto.REGION_PAD);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(1248);
            out.writeInt(1972);
            out.flush();
            Server.log("自测：已请求接管屏幕 " + displayId + "，保持 " + seconds + " 秒心跳");
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
                    case Proto.MSG_HELLO:
                        Server.log("自测收到 HELLO version=" + in.readUnsignedByte() + " ok=" + in.readUnsignedByte()
                                + " " + in.readUTF());
                        break;
                    case Proto.MSG_GRAB:
                        Server.log("自测收到 接管=" + in.readUnsignedByte() + " 原因=" + in.readUnsignedByte() + " " + in.readUTF());
                        break;
                    case Proto.MSG_HAPTIC:
                        Server.log("自测收到 震动 " + in.readUnsignedByte());
                        break;
                    case Proto.MSG_GESTURE:
                        Server.log("自测收到 手势 " + in.readUnsignedByte() + "/" + in.readUnsignedByte());
                        break;
                    case Proto.MSG_MODIFIERS:
                        Server.log("自测收到 修饰键 " + in.readUnsignedByte());
                        break;
                    case Proto.MSG_BUTTON:
                        Server.log("自测收到 按钮 " + in.readInt() + " " + in.readUnsignedByte());
                        break;
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
