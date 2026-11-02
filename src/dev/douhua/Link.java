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
 * 服务发来的消息由另一个线程读，回调都在主线程（异步消息，不排在界面重画的同步屏障后面，见 {@link #main}）。
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

        /**
         * 修饰键的状态（Proto.MSG_MODIFIERS，协议 20 起三样，HID 修饰键位）：latched = 锁住的，held = 有手指按着的，
         * armed = 单独按住到了 0.4 秒、"现在松手就锁"的。界面照 latched | armed 画高亮、照 latched | held 换键帽上的字。
         * fn = Fn 的状态（协议 21 起，Proto.FN_LATCHED 单次锁 / FN_HELD / FN_ARMED 快永久锁了 / FN_LOCKED 永久锁 位，协议 22 改了意思；
         * 协议 24 起还有 Proto.FROW_FLIP：F 行翻转着，App 存进设置）。
         */
        void onModifiers(int latched, int held, int armed, int fn);

        /** Caps 的显示该翻一下了（Proto.MSG_CAPS）：sent = 这次给主机发了 Caps Lock（点一下），假 = 只切显示（按住 0.4 秒）。 */
        void onCaps(boolean sent);

        /** 蓝牙模式下服务接管触摸屏时，分流出的鼠标报文（见 Proto.MSG_MOUSE）。 */
        void onRelayMouse(int buttons, int dx, int dy, int wheel, int pan);

        /** 同上，键盘报文（8 字节）。 */
        void onRelayKeyboard(byte[] report);

        /**
         * 同上，多媒体 / 系统键（Proto.MSG_CONSUMER，协议 24）：page = Proto.PAGE_CONSUMER（蓝牙报告 3）/ PAGE_GENERIC_DESKTOP（系统键，报告 6），
         * usage = 现在按着的码（0 = 松开）。走同一个异步 Handler，和键盘报文的先后照服务发的（组合键 + 多媒体键这类不乱）。
         */
        void onRelayConsumer(int page, int usage);

        /** 服务截住的实体键（已判断好）：key = Proto.HARD_VOL_UP / VOL_DOWN，event = Proto.HARD_TAP / HOLD_START / HOLD_END。 */
        void onHardKey(int key, int event);

        /** 实体键的独占状态：grabbed / unavailable 是 Proto.HARD_BIT_* 位，reason 是 Proto.HARD_*，detail 是设备名或错误原文。 */
        void onHardGrab(int grabbed, int unavailable, int reason, String detail);

        /** 服务分流出的手柄状态（Proto.MSG_GAMEPAD）：flags 是 Proto.GAMEPAD_FLAG_*，report 13 字节。 */
        void onGamepad(int flags, byte[] report);
    }

    private static final byte[] PING = {(byte) Proto.CMD_PING};

    /**
     * 回调全部经这一个异步 Handler 到主线程：界面一重画（invalidate）主线程队列里就插一道同步屏障，普通 post 的消息
     * 要等下一帧画完才轮到，转发回来的键盘 / 鼠标报文（MSG_KEYBOARD / MSG_MOUSE，蓝牙模式下由 App 发出去）就晚一帧；
     * 异步消息不受屏障挡。所有回调走同一个 Handler，相互之间的先后和服务发的一样：Ctrl + 滚轮缩放靠键盘、鼠标报文的先后；
     * onGrab 接管时先收尾本地分流（可能发全零报文），要在服务转来的第一个报文之前；按钮、修饰键和报文的先后也不乱。
     */
    private final Handler main = Handler.createAsync(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final Listener listener;
    private final Context context;
    private volatile boolean running;
    private volatile boolean connected;
    /** 连着的服务是从哪个 APK 起的（hello 里报的；空 = 不知道）。连上的回调之前写好。 */
    private volatile String serverApk = "";
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

    /** 连着的服务是从哪个 APK 起的（协议 16 起 hello 里带；空 = 不知道）。onLinkStatus(true, …) 时已经是这次连接的。 */
    String serverApk() {
        return serverApk;
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

    /** 手势参数（编解码在 ConfigCodec，服务那边读的是同一份）。 */
    void config(GestureEngine.Config c, float density) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(bytes);
        try {
            d.writeByte(Proto.CMD_CONFIG);
            ConfigCodec.write(d, c, density);
        } catch (IOException impossible) {
            return;   // 写内存不会出错
        }
        if (connected) queue.offer(bytes.toByteArray());
    }

    /**
     * regions 每项是 {type, id, left, top, right, bottom}，屏幕像素。relay = 蓝牙模式，报文发回来由 App 发出去。
     * width / height 是整块屏的尺寸；full = 窗口占满整块屏；window = 窗口在屏上的 {left, top, right, bottom}；
     * gestures = 贴着窗口四条边的系统手势带宽度 {left, top, right, bottom}（分屏透传用，见 Proto.CMD_LAYOUT）。
     */
    void layout(boolean active, boolean relay, int displayId, int rotation, int width, int height,
                boolean full, int[] window, int[] gestures, List<int[]> regions) {
        ByteBuffer b = ByteBuffer.allocate(51 + regions.size() * 21);
        b.put((byte) Proto.CMD_LAYOUT).put((byte) (active ? 1 : 0)).put((byte) (relay ? 1 : 0))
                .putInt(displayId).put((byte) rotation)
                .putInt(width).putInt(height).put((byte) (full ? 1 : 0));
        for (int v : window) b.putInt(v);
        for (int v : gestures) b.putInt(v);
        b.putShort((short) regions.size());
        for (int[] r : regions) {
            b.put((byte) r[0]).putInt(r[1]).putInt(r[2]).putInt(r[3]).putInt(r[4]).putInt(r[5]);
        }
        offer(b);
    }

    /**
     * 服务没接管触摸屏时，键盘区的触摸、动作里的组合键由 App 收到后转发（协议 20：key:i32，含合并键；action = Proto.KEY_DOWN / KEY_UP / KEY_CANCEL）。
     * 服务的分流照同一份规则判点一下 / 按住 / 锁（KeyHolds），时间用它收到的时刻。
     */
    void hidKey(int key, int action) {
        offer(ByteBuffer.allocate(6).put((byte) Proto.CMD_HID_KEY).putInt(key).put((byte) action));
    }

    /** 清掉服务那边锁住的修饰键（Proto.CMD_UNLATCH，不带参数）：进空中鼠标、手柄页时，不然 Ctrl 一直按着、每次点击都成了 Ctrl+点击。 */
    void unlatch() {
        offer(ByteBuffer.allocate(1).put((byte) Proto.CMD_UNLATCH));
    }

    /**
     * 请服务把键盘焦点交给这块屏上另一个看得见的应用（Proto.CMD_FOCUS_OTHER，协议 22）：桌面模式分屏 / 小窗里豆花窗口拿着焦点时，
     * 不然虚拟键盘的键被豆花自己吞掉。什么时候发由 MainActivity 照 FocusHandoff 定。
     */
    void focusOther(int displayId) {
        offer(ByteBuffer.allocate(5).put((byte) Proto.CMD_FOCUS_OTHER).putInt(displayId));
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

    /** 经服务的虚拟鼠标按住 / 松开左键（桌面模式下实体键按住配了"左键"时）。 */
    void holdLeft(boolean down) {
        offer(ByteBuffer.allocate(2).put((byte) Proto.CMD_HOLD_LEFT).put((byte) (down ? 1 : 0)));
    }

    /** 桌面模式下服务没接管时，App 本地分流出的手柄状态（13 字节，Proto.CMD_GAMEPAD）。 */
    void gamepad(byte[] report) {
        offer(ByteBuffer.allocate(1 + Hid.GAMEPAD_REPORT_SIZE).put((byte) Proto.CMD_GAMEPAD).put(report, 0, Hid.GAMEPAD_REPORT_SIZE));
    }

    /** 手柄参数（编解码在 GamepadConfig，服务那边读的是同一份）：服务的分流收到后手柄那一路回中立。 */
    void gamepadConfig(GamepadConfig c) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(bytes);
        try {
            d.writeByte(Proto.CMD_GAMEPAD_CONFIG);
            c.write(d);
        } catch (IOException impossible) {
            return;   // 写内存不会出错
        }
        if (connected) queue.offer(bytes.toByteArray());
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
                if (version != Proto.VERSION) {
                    throw new Shown(context.getString(R.string.status_version, version, Proto.VERSION), false);
                }
                try {
                    serverApk = in.readUTF();   // 版本对上了才有这一段（协议 16 起）
                } catch (SocketTimeoutException e) {
                    throw new Shown(context.getString(R.string.status_service_stuck), true);
                }
                socket.setSoTimeout(0);
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
                            int latched = in.readUnsignedByte();
                            int held = in.readUnsignedByte();
                            int armed = in.readUnsignedByte();
                            int fn = in.readUnsignedByte();   // 协议 21
                            main.post(() -> listener.onModifiers(latched, held, armed, fn));
                            break;
                        }
                        case Proto.MSG_CAPS: {
                            boolean sent = in.readUnsignedByte() == 1;
                            main.post(() -> listener.onCaps(sent));
                            break;
                        }
                        case Proto.MSG_MOUSE: {
                            int buttons = in.readUnsignedByte();
                            int dx = in.readShort();
                            int dy = in.readShort();
                            int wheel = in.readShort();   // 1/120 格（协议 18 起）
                            int pan = in.readShort();
                            main.post(() -> listener.onRelayMouse(buttons, dx, dy, wheel, pan));
                            break;
                        }
                        case Proto.MSG_KEYBOARD: {
                            byte[] report = new byte[Hid.KEYBOARD_REPORT_SIZE];
                            in.readFully(report);
                            main.post(() -> listener.onRelayKeyboard(report));
                            break;
                        }
                        case Proto.MSG_CONSUMER: {   // 协议 24
                            int page = in.readUnsignedByte();
                            int usage = in.readUnsignedShort();
                            main.post(() -> listener.onRelayConsumer(page, usage));
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
                        case Proto.MSG_GAMEPAD: {
                            int flags = in.readUnsignedByte();
                            byte[] report = new byte[Hid.GAMEPAD_REPORT_SIZE];
                            in.readFully(report);
                            main.post(() -> listener.onGamepad(flags, report));
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
