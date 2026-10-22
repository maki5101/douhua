package dev.douhua;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * App 这一侧和特权服务的连接。后台线程负责连接、断了每秒重连；要发的报文进队列，由同一个线程写出，不卡界面。
 * 服务发来的消息由另一个线程读，回调都在主线程。
 *
 * 队列空闲 {@link Proto#PING_INTERVAL_MS} 就发一个保活（{@link Proto#CMD_PING}）：服务据此判断连接死没死
 * （10 秒没消息就断开、新连接能顶掉 5 秒没消息的旧连接）。保活不是心跳，心跳由主线程发、决定能不能独占触摸屏。
 * 服务卡住时（连接 1 秒超时 = accept 队列满了，或者连上了 5 秒收不到 hello）报"服务没响应"（stuck）。
 */
final class Link {
    interface Listener {
        /** stuck：服务在运行却没响应（卡住了），message 是给用户看的提示。 */
        void onLinkStatus(boolean connected, boolean stuck, String message);

        void onHaptic(int kind);

        void onGesture(int fingers, int kind);

        void onButton(int id, int action);

        /** reason 是 Proto.GRAB_*，detail 是屏幕编号或错误原文。 */
        void onGrab(boolean grabbed, int reason, String detail);

        void onModifiers(int mask);

        /** 蓝牙模式下服务接管触摸屏时，分流出的鼠标报文（见 Proto.MSG_MOUSE）。 */
        void onRelayMouse(int buttons, int dx, int dy, int wheel, int pan);

        /** 同上，键盘报文（8 字节）。 */
        void onRelayKeyboard(byte[] report);

        /** 服务截住的实体键（已判断好）：key = Proto.HARD_VOL_UP / VOL_DOWN，event = Proto.HARD_TAP / HOLD_START / HOLD_END。 */
        void onHardKey(int key, int event);

        /** 实体键的独占状态：grabbed / unavailable 是 Proto.HARD_BIT_* 位，reason 是 Proto.HARD_*，detail 是设备名或错误原文。 */
        void onHardGrab(int grabbed, int unavailable, int reason, String detail);
    }

    private static final byte[] PING = {(byte) Proto.CMD_PING};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Listener listener;
    private final Context context;
    private volatile boolean running;
    private volatile boolean connected;
    /** 现在的连接线程：stop() 后又 start() 时，旧线程看到自己不是它就退出，不和新线程抢队列、不报状态。 */
    private volatile Thread thread;
    /** 现在的连接线程手里的 socket：stop() 关掉它，才叫得醒卡在 connect / 读 hello 上的线程。 */
    private volatile Socket current;

    Link(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::run, "douhua-link");
        thread = t;
        t.start();
    }

    /** interrupt 叫不醒卡在 connect 或读 hello 上的线程，要连 socket 一起关；不然每次 onStop / onStart 都漏一个线程。 */
    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        Socket s = current;
        if (s != null) closeQuietly(s);
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

    void wheel(int n) {
        offer(ByteBuffer.allocate(2).put((byte) Proto.CMD_WHEEL).put((byte) n));
    }

    void config(GestureEngine.Config c, float density) {
        offer(ByteBuffer.allocate(41).put((byte) Proto.CMD_CONFIG)
                .putFloat(c.pointerSpeed).putFloat(c.acceleration).putFloat(c.scrollStepDp)
                .put((byte) (c.naturalScroll ? 1 : 0)).put((byte) (c.pinchZoom ? 1 : 0)).put((byte) (c.edgeScroll ? 1 : 0))
                .put((byte) (c.edgeScrollHorizontal ? 1 : 0)).putFloat(c.edgeWidthDp).put((byte) c.scrollTicks)
                .put((byte) (c.longPressDrag ? 1 : 0)).putInt(c.longPressMs)
                .put((byte) (c.tapToDrag ? 1 : 0))
                .putInt(c.dragWindowMs).putFloat(density).putFloat(c.slowFactor).put((byte) (c.texture ? 1 : 0)));
    }

    /** regions 每项是 {type, id, left, top, right, bottom}，屏幕像素。relay = 蓝牙模式，报文发回来由 App 发出去。 */
    void layout(boolean active, boolean relay, int displayId, int rotation, int width, int height, List<int[]> regions) {
        ByteBuffer b = ByteBuffer.allocate(18 + regions.size() * 21);
        b.put((byte) Proto.CMD_LAYOUT).put((byte) (active ? 1 : 0)).put((byte) (relay ? 1 : 0))
                .putInt(displayId).put((byte) rotation)
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

    /** 音量键哪一半配了动作（服务据此决定截哪些，见 Proto.CMD_HARD_KEYS）；全 false = 一个都不截。 */
    void hardKeys(boolean volUpTap, boolean volUpHold, boolean volDownTap, boolean volDownHold) {
        offer(ByteBuffer.allocate(5).put((byte) Proto.CMD_HARD_KEYS)
                .put((byte) (volUpTap ? 1 : 0)).put((byte) (volUpHold ? 1 : 0))
                .put((byte) (volDownTap ? 1 : 0)).put((byte) (volDownHold ? 1 : 0)));
    }

    /** 经服务的虚拟鼠标按住 / 松开左键（DeX 模式下实体键按住配了"左键"时）。 */
    void holdLeft(boolean down) {
        offer(ByteBuffer.allocate(2).put((byte) Proto.CMD_HOLD_LEFT).put((byte) (down ? 1 : 0)));
    }

    /** 调试用：服务记不记键盘触点（只有可调试包会开，见 DebugMoves 的 DEBUG_KEYLOG）。 */
    void trace(boolean keys) {
        offer(ByteBuffer.allocate(2).put((byte) Proto.CMD_TRACE).put((byte) (keys ? 1 : 0)));
    }

    private void offer(ByteBuffer b) {
        if (connected) queue.offer(b.array());
    }

    // ═══════════ 连接循环 ═══════════

    private boolean mine(Thread me) {
        return running && thread == me;
    }

    private void run() {
        Thread me = Thread.currentThread();
        while (mine(me)) {
            Socket socket = new Socket();
            current = socket;
            if (!mine(me)) {   // stop() 可能刚好在上一行之前读过 current
                closeQuietly(socket);
                break;
            }
            try {
                // App 没法反查端口另一端是谁（读不了 /proc/net），所以这里不校验服务身份；
                // 经过这条连接的只有布局、参数和按键码，打的字走剪贴板，不经过它。
                try {
                    socket.connect(new InetSocketAddress(Proto.HOST, Proto.PORT), 1000);
                } catch (SocketTimeoutException e) {
                    // 本机回环上端口没人监听会直接被拒；连接超时只会是服务的 accept 队列满了——服务卡住了
                    throw new Shown(context.getString(R.string.status_service_stuck), true);
                }
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(Proto.HELLO_TIMEOUT_MS);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int version;
                int ok;
                String message;
                try {
                    if (in.readUnsignedByte() != Proto.MSG_HELLO) throw new IOException("bad handshake");
                    version = in.readUnsignedByte();
                    ok = in.readUnsignedByte();
                    message = in.readUTF();
                } catch (SocketTimeoutException e) {
                    throw new Shown(context.getString(R.string.status_service_stuck), true);
                }
                socket.setSoTimeout(0);
                if (version != Proto.VERSION) {
                    throw new Shown(context.getString(R.string.status_version, version, Proto.VERSION), false);
                }
                if (ok == Proto.HELLO_BUSY) throw new Shown(context.getString(R.string.status_service_busy), false);
                if (ok != Proto.HELLO_OK) throw new Shown(context.getString(R.string.status_service_error, message), false);
                if (!mine(me)) break;

                queue.clear();
                connected = true;
                main.post(() -> listener.onLinkStatus(true, false, message));
                byte[] poison = new byte[0];   // 这条连接自己的"已断"信号：上一条连接的读线程晚到的信号不算数
                startReader(in, poison);
                OutputStream out = socket.getOutputStream();
                while (mine(me)) {
                    byte[] packet = queue.poll(Proto.PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (packet == null) {
                        packet = PING;   // 空闲：发个保活，服务才知道这条连接还活着
                    } else if (packet.length == 0) {
                        if (packet == poison) throw new IOException("service disconnected");
                        continue;
                    }
                    out.write(packet);
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (mine(me)) {
                    String m = describe(e);
                    boolean stuck = e instanceof Shown && ((Shown) e).stuck;
                    main.post(() -> listener.onLinkStatus(false, stuck, m));
                }
            } finally {
                if (thread == me || thread == null) connected = false;
                if (current == socket) current = null;
                closeQuietly(socket);
            }
            if (!mine(me)) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (thread == me || thread == null) connected = false;
    }

    /** 读服务发来的消息；读到流结束就往队列塞这条连接的信号，让发送循环醒来重连。 */
    private void startReader(DataInputStream in, byte[] poison) {
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
                        case Proto.MSG_MOUSE: {
                            int buttons = in.readUnsignedByte();
                            int dx = in.readShort();
                            int dy = in.readShort();
                            int wheel = in.readByte();
                            int pan = in.readByte();
                            main.post(() -> listener.onRelayMouse(buttons, dx, dy, wheel, pan));
                            break;
                        }
                        case Proto.MSG_KEYBOARD: {
                            byte[] report = new byte[Hid.KEYBOARD_REPORT_SIZE];
                            in.readFully(report);
                            main.post(() -> listener.onRelayKeyboard(report));
                            break;
                        }
                        case Proto.MSG_GRAB: {
                            boolean grabbed = in.readUnsignedByte() == 1;
                            int reason = in.readUnsignedByte();
                            String detail = in.readUTF();
                            main.post(() -> listener.onGrab(grabbed, reason, detail));
                            break;
                        }
                        case Proto.MSG_HARD_KEY: {
                            int key = in.readUnsignedByte();
                            int event = in.readUnsignedByte();
                            main.post(() -> listener.onHardKey(key, event));
                            break;
                        }
                        case Proto.MSG_HARD_GRAB: {
                            int grabbed = in.readUnsignedByte();
                            int unavailable = in.readUnsignedByte();
                            int reason = in.readUnsignedByte();
                            String detail = in.readUTF();
                            main.post(() -> listener.onHardGrab(grabbed, unavailable, reason, detail));
                            break;
                        }
                        default:
                            throw new IOException("unknown message " + type);
                    }
                }
            } catch (IOException ignored) {
                // 连接关闭
            }
            queue.offer(poison);
        }, "douhua-link-read");
        t.setDaemon(true);
        t.start();
    }

    /** 已经是给用户看的话（按系统语言拼好的）。stuck：服务在运行却没响应。 */
    private static final class Shown extends IOException {
        final boolean stuck;

        Shown(String message, boolean stuck) {
            super(message);
            this.stuck = stuck;
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
