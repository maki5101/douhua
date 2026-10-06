package dev.douhua;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * App 这一侧和特权服务的连接。后台线程负责连接、断了每秒重连；要发的报文进队列，由同一个线程写出，不卡界面。
 * 服务发来的消息由另一个线程读，回调都在主线程。
 */
final class Link {
    interface Listener {
        void onLinkStatus(boolean connected, String message);

        void onHaptic(int kind);

        void onGesture(int fingers, int kind);

        void onButton(int id, int action);

        /** reason 是 Proto.GRAB_*，detail 是屏幕编号或错误原文。 */
        void onGrab(boolean grabbed, int reason, String detail);

        void onModifiers(int mask);
    }

    /** 队列里的"连接已断"信号。 */
    private static final byte[] POISON = new byte[0];

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Listener listener;
    private final Context context;
    private volatile boolean running;
    private volatile boolean connected;
    private Thread thread;

    Link(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "douhua-link");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    boolean isConnected() {
        return connected;
    }

    // ═══════════ 发给服务 ═══════════

    void key(int displayId, int keyCode, int metaState) {
        offer(ByteBuffer.allocate(13).put((byte) Proto.CMD_KEY).putInt(displayId).putInt(keyCode).putInt(metaState));
    }

    void click(int button) {
        offer(ByteBuffer.allocate(2).put((byte) Proto.CMD_CLICK).put((byte) button));
    }

    void config(GestureEngine.Config c, float density) {
        offer(ByteBuffer.allocate(36).put((byte) Proto.CMD_CONFIG)
                .putFloat(c.pointerSpeed).putFloat(c.acceleration).putFloat(c.scrollStepDp)
                .put((byte) (c.naturalScroll ? 1 : 0)).put((byte) (c.pinchZoom ? 1 : 0)).put((byte) (c.edgeScroll ? 1 : 0))
                .put((byte) (c.edgeScrollHorizontal ? 1 : 0)).putFloat(c.edgeWidthDp).put((byte) c.scrollTicks)
                .put((byte) (c.longPressDrag ? 1 : 0)).putInt(c.longPressMs)
                .put((byte) (c.tapToDrag ? 1 : 0))
                .putInt(c.dragWindowMs).putFloat(density));
    }

    /** regions 每项是 {type, id, left, top, right, bottom}，屏幕像素。 */
    void layout(boolean active, int displayId, int rotation, int width, int height, List<int[]> regions) {
        ByteBuffer b = ByteBuffer.allocate(17 + regions.size() * 21);
        b.put((byte) Proto.CMD_LAYOUT).put((byte) (active ? 1 : 0)).putInt(displayId).put((byte) rotation)
                .putInt(width).putInt(height).putShort((short) regions.size());
        for (int[] r : regions) {
            b.put((byte) r[0]).putInt(r[1]).putInt(r[2]).putInt(r[3]).putInt(r[4]).putInt(r[5]);
        }
        offer(b);
    }

    /** 服务没接管触摸屏时，键盘区的触摸由 App 收到后转发。 */
    void hidKey(int usage, boolean down) {
        offer(ByteBuffer.allocate(4).put((byte) Proto.CMD_HID_KEY).putShort((short) usage).put((byte) (down ? 1 : 0)));
    }

    void rotate(int displayId, int rotation) {
        offer(ByteBuffer.allocate(6).put((byte) Proto.CMD_ROTATE).putInt(displayId).put((byte) rotation));
    }

    void heartbeat() {
        offer(ByteBuffer.allocate(1).put((byte) Proto.CMD_HEARTBEAT));
    }

    private void offer(ByteBuffer b) {
        if (connected) queue.offer(b.array());
    }

    // ═══════════ 连接循环 ═══════════

    private void run() {
        while (running) {
            Socket socket = new Socket();
            try {
                // App 没法反查端口另一端是谁（读不了 /proc/net），所以这里不校验服务身份；
                // 经过这条连接的只有布局、参数和按键码，打的字走剪贴板，不经过它。
                socket.connect(new InetSocketAddress(Proto.HOST, Proto.PORT), 1000);
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                if (in.readUnsignedByte() != Proto.MSG_HELLO) throw new IOException("bad handshake");
                int version = in.readUnsignedByte();
                boolean ok = in.readUnsignedByte() == 1;
                String message = in.readUTF();
                if (version != Proto.VERSION) {
                    throw new Shown(context.getString(R.string.status_version, version, Proto.VERSION));
                }
                if (!ok) throw new Shown(context.getString(R.string.status_service_error, message));

                queue.clear();
                connected = true;
                main.post(() -> listener.onLinkStatus(true, message));
                startReader(in);
                OutputStream out = socket.getOutputStream();
                while (running) {
                    byte[] packet = queue.take();
                    if (packet == POISON) throw new IOException("service disconnected");
                    out.write(packet);
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (running) {
                    String m = describe(e);
                    main.post(() -> listener.onLinkStatus(false, m));
                }
            } finally {
                connected = false;
                closeQuietly(socket);
            }
            if (!running) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        connected = false;
    }

    /** 读服务发来的消息；读到流结束就往队列塞个信号，让发送循环醒来重连。 */
    private void startReader(DataInputStream in) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    int type = in.readUnsignedByte();
                    switch (type) {
                        case Proto.MSG_HAPTIC: {
                            int kind = in.readUnsignedByte();
                            main.post(() -> listener.onHaptic(kind));
                            break;
                        }
                        case Proto.MSG_GESTURE: {
                            int fingers = in.readUnsignedByte();
                            int kind = in.readUnsignedByte();
                            main.post(() -> listener.onGesture(fingers, kind));
                            break;
                        }
                        case Proto.MSG_BUTTON: {
                            int id = in.readInt();
                            int action = in.readUnsignedByte();
                            main.post(() -> listener.onButton(id, action));
                            break;
                        }
                        case Proto.MSG_MODIFIERS: {
                            int mask = in.readUnsignedByte();
                            main.post(() -> listener.onModifiers(mask));
                            break;
                        }
                        case Proto.MSG_GRAB: {
                            boolean grabbed = in.readUnsignedByte() == 1;
                            int reason = in.readUnsignedByte();
                            String detail = in.readUTF();
                            main.post(() -> listener.onGrab(grabbed, reason, detail));
                            break;
                        }
                        default:
                            throw new IOException("unknown message " + type);
                    }
                }
            } catch (IOException ignored) {
                // 连接关闭
            }
            queue.offer(POISON);
        }, "douhua-link-read");
        t.setDaemon(true);
        t.start();
    }

    /** 已经是给用户看的话（按系统语言拼好的）。 */
    private static final class Shown extends IOException {
        Shown(String message) {
            super(message);
        }
    }

    private String describe(IOException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof Shown) return m;
        if (m.contains("ECONNREFUSED") || m.contains("refused")) return context.getString(R.string.status_no_service);
        if (m.contains("EACCES") || m.contains("Permission denied")) return context.getString(R.string.status_denied, m);
        return context.getString(R.string.status_service_error, m);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 已经关了
        }
    }
}
