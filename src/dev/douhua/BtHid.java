package dev.douhua;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 蓝牙模式：手机当蓝牙键盘 + 鼠标 + 媒体键（系统的 BluetoothHidDevice，不需要电脑、不需要服务）。
 * 对方（电脑、平板、电视）先在它的蓝牙设置里配对这台手机；之后豆花儿打开时自动连上次那台。
 *
 * 整个进程一份：主界面和设置页都用它，最后一个用的人走了 {@link #GRACE_MS} 后才真的停，
 * 去设置页改个参数、切出去看一眼再回来，连接不断。所有方法和回调都在主线程。
 *
 * 连续滚动：描述符跟着设置里的"连续滚动"开关（开着才带滚轮的分辨率倍率，Hid.MOUSE_DESC_HIRES），开关一变就注销、重新注册
 * （{@link #descriptorChanged}），对方要删掉这台手机重新配对才认新描述符。主机来设倍率就照办（它可能缓存着以前配对时的描述符），
 * 每次连上先当 ×1、等主机重新设（规范里复位后默认 ×1；Linux 每次连接都重设）。滚轮量的单位是 1/120 格，按倍率换算（Hid.WheelOut）。
 *
 * 系统键（协议 24，实体键盘 F 行第二功能里的睡眠、勿扰、麦克风静音）：描述符最末尾一直带一组系统键（报告 6，Hid.SYSTEM_DESC），不跟着设置变；
 * 前面报告 1–4 一个字节不动。对方要删掉这台手机重新配对一次才认报告 6（多媒体键在报告 3 里本来就有，不用重配）。
 * 多媒体键、系统键照真键盘按下发码、松开发 0（{@link #sendConsumer} / {@link #sendSystem}，按着的码由 TouchRouter 的两个槽记）。
 */
final class BtHid {
    interface Listener {
        void onBtState(int state, String deviceName);
    }

    static final int STATE_OFF = 0;
    static final int STATE_NO_PERMISSION = 1;
    static final int STATE_BT_DISABLED = 2;
    static final int STATE_UNSUPPORTED = 3;
    static final int STATE_REGISTERING = 4;
    /** 已注册，等对方连（或正在连上次那台）。 */
    static final int STATE_WAITING = 5;
    static final int STATE_CONNECTING = 6;
    static final int STATE_CONNECTED = 7;
    /** 注册失败：多半是别的 App 正在当蓝牙键盘（系统同一时刻只允许一个）。 */
    static final int STATE_FAILED = 8;
    /** 注册了系统一直不回话：系统蓝牙卡住了，App 解不开，要重启手机（见 onRegisterTimeout）。 */
    static final int STATE_STUCK = 9;

    private static final String TAG = "douhua";
    private static final long GRACE_MS = 60_000;
    /** 有人在用、又没连上时，每隔这么久重新连一次上次那台（对方刚才没准备好、刚打开蓝牙，都能自己连上）。 */
    private static final long RETRY_MS = 5_000;
    /** 注册正常几毫秒就有回话，等这么久还没有就当系统卡住了。 */
    private static final long REGISTER_TIMEOUT_MS = 5_000;
    /** 连接正常 1–2 秒有结果，对方不在时约 5 秒报断开；过了这么久还没结果就当系统卡在"连接中"了。 */
    private static final long CONNECT_TIMEOUT_MS = 12_000;
    private static BtHid instance;

    private final Context context;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final byte[] mouseReport = new byte[Hid.MOUSE_REPORT_SIZE];
    private final byte[] absReport = new byte[Hid.ABS_REPORT_SIZE];
    /** 注册好了（描述符里一直带绝对坐标指针，回正中用）。 */
    private boolean absRegistered;
    /** 上一次发的绝对坐标（同样的值内核会吞掉，见 sendAbsolute）。 */
    private int lastAbsX = -1, lastAbsY = -1;
    /** 最近一帧鼠标报文里的按键（绝对报文的第一个字节照抄，见 Hid.ABS_POINTER_DESC）。 */
    private int mouseButtons;
    private final Runnable stopLater = this::stop;
    private final Runnable retryLater = this::retryConnect;
    private final Runnable registerTimeout = this::onRegisterTimeout;
    private final Runnable connectTimeout = this::onConnectTimeout;
    private final Runnable reregisterFallback = this::onReregisterTimeout;
    /** 滚轮单位 → 计数，记着连着的主机设的倍率。 */
    private final Hid.WheelOut wheelOut = new Hid.WheelOut();
    /**
     * 发出去之前的最后一道：每个键 / 鼠标键至少按住 {@link MinHold#MIN_MS}（2026-11-04；服务转发回来的、本地分流的、App 直接发的——
     * 点击类动作、空中鼠标、媒体键动作——都经它）。主线程用，定时器是主线程 Handler；换了主机 / 断开就忘掉。
     */
    private final MinHold hold = new MinHold(new HoldSink());
    private final Runnable holdTimer = () -> hold.onTimer(SystemClock.uptimeMillis());
    /** 上次 registerApp 用的描述符带不带滚轮倍率（和设置对不上就重新注册）。 */
    private boolean regHires;
    /** 正在为换描述符注销：注销完（onAppStatusChanged(false)）马上重新注册。 */
    private boolean reregistering;

    private int users;
    private boolean started;
    private BluetoothAdapter adapter;
    /**
     * 系统给的代理，整个启动期间只要一个：蓝牙关开时它不失效，系统蓝牙起来后会再回调 onServiceConnected。
     * 以前蓝牙一关就丢掉它、打开时再要一个，两个代理各注册一次，第二次被系统拒掉，状态就成了"注册失败"。
     */
    private BluetoothHidDevice proxy;
    private boolean proxyRequested;
    /** 代理当前连着系统服务时 = proxy，否则 null。 */
    private BluetoothHidDevice hid;
    /** 已经调了 registerApp、还在等系统回话。 */
    private boolean registering;
    private boolean registered;
    private BluetoothDevice connected;
    private BluetoothDevice connecting;
    private int state = STATE_OFF;
    private boolean receiverOn;

    private BtHid(Context context) {
        this.context = context;
        this.prefs = new Prefs(context);
    }

    static BtHid get(Context c) {
        if (instance == null) instance = new BtHid(c.getApplicationContext());
        return instance;
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    // ═══════════ 谁在用 ═══════════

    void acquire(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        users++;
        main.removeCallbacks(stopLater);
        if (!started || state == STATE_NO_PERMISSION || state == STATE_FAILED) start();
        // 宽限期里回来：排着的重连在没人用时已经作罢（retryConnect 看到 users == 0 就不再排），这里接上
        if (state == STATE_WAITING) {
            main.removeCallbacks(retryLater);
            main.postDelayed(retryLater, RETRY_MS);
        }
        l.onBtState(state, deviceName(connected != null ? connected : connecting));
    }

    void release(Listener l) {
        listeners.remove(l);
        if (users > 0 && --users == 0) main.postDelayed(stopLater, GRACE_MS);
    }

    /** 刚拿到权限、或用户打开了蓝牙：有人在用就重新走一遍启动。 */
    void retry() {
        if (users > 0) start();
    }

    /** 切回桌面模式时：立刻停，不等。 */
    void stopNow() {
        main.removeCallbacks(stopLater);
        stop();
    }

    int state() {
        return state;
    }

    BluetoothDevice connectedDevice() {
        return connected;
    }

    // ═══════════ 启停 ═══════════

    private void start() {
        started = true;
        if (!hasPermission(context)) {
            setState(STATE_NO_PERMISSION);
            return;
        }
        BluetoothManager bm = context.getSystemService(BluetoothManager.class);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            setState(STATE_UNSUPPORTED);
            return;
        }
        if (!receiverOn) {
            context.registerReceiver(stateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverOn = true;
        }
        if (!adapter.isEnabled()) {
            setState(STATE_BT_DISABLED);   // 用户打开蓝牙后 stateReceiver 会接着往下走
            return;
        }
        if (hid != null) {
            if (!registered && !registering) register();
            return;
        }
        setState(STATE_REGISTERING);
        if (proxyRequested) return;   // 代理已经要过了：系统服务连上后 onServiceConnected 接着注册
        proxyRequested = true;
        if (!adapter.getProfileProxy(context, proxyListener, BluetoothProfile.HID_DEVICE)) {
            proxyRequested = false;
            setState(STATE_UNSUPPORTED);
        }
    }

    private void stop() {
        started = false;
        if (receiverOn) {
            context.unregisterReceiver(stateReceiver);
            receiverOn = false;
        }
        main.removeCallbacks(registerTimeout);
        main.removeCallbacks(connectTimeout);
        if (proxy != null) {
            try {
                // 对方那边会看到键盘鼠标断开；卡在注册里的也撤掉，免得系统一直当它占着
                if (hid != null && (registered || registering)) hid.unregisterApp();
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
            } catch (SecurityException e) {
                Log.w(TAG, "停止蓝牙 HID 时没有权限：" + e.getMessage());
            }
        }
        proxy = null;
        proxyRequested = false;
        hid = null;
        registering = false;
        registered = false;
        connected = null;
        connecting = null;
        absRegistered = false;
        reregistering = false;
        main.removeCallbacks(reregisterFallback);
        setState(STATE_OFF);
    }

    private final BluetoothProfile.ServiceListener proxyListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile p) {
            // 停了以后才回来的、或者多出来的代理：关掉，只留一个
            if (!started || (proxy != null && proxy != p)) {
                adapter.closeProfileProxy(profile, p);
                return;
            }
            proxy = (BluetoothHidDevice) p;
            hid = proxy;
            if (!registered && !registering) register();
        }

        /** 蓝牙关掉时也会走这里（系统服务停了）；代理留着，蓝牙打开后会再回调 onServiceConnected。 */
        @Override
        public void onServiceDisconnected(int profile) {
            hid = null;
            registering = false;
            registered = false;
            connected = null;
            connecting = null;
            main.removeCallbacks(registerTimeout);
            main.removeCallbacks(connectTimeout);
            if (started) setState(adapter != null && adapter.isEnabled() ? STATE_FAILED : STATE_BT_DISABLED);
        }
    };

    private void register() {
        boolean hires = prefs.smoothScroll();
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Douhua", "Douhua touchpad & keyboard", "Douhua",
                BluetoothHidDevice.SUBCLASS1_COMBO, Hid.combinedDescriptor(true, hires, true));   // 末尾一直带系统键（报告 6，协议 24）
        absRegistered = true;
        regHires = hires;
        registering = true;
        setState(STATE_REGISTERING);
        try {
            if (hid.registerApp(sdp, null, null, context.getMainExecutor(), callback)) {
                main.removeCallbacks(registerTimeout);
                main.postDelayed(registerTimeout, REGISTER_TIMEOUT_MS);
            } else {
                registering = false;
                setState(STATE_FAILED);
            }
        } catch (SecurityException e) {
            registering = false;
            setState(STATE_NO_PERMISSION);
        }
    }

    /**
     * 注册了 5 秒系统还没回话（正常几毫秒）。三星主力机实测（2026-10-15）：豆花注册着的时候关开一次蓝牙，
     * 系统蓝牙再起来后"手机连外接键鼠"那部分没启动，而三星注册蓝牙键盘前要先把它停掉、等它停好，于是永远等不到，
     * 之后谁来注册都一样，重启手机才好（日志：BTA_HH_REG_HD_STATE NOT_READY 之后再没有 BTA_HD_REGISTER_APP_EVT）。
     * App 解不开，也没法在系统关蓝牙之前抢先注销（三星一开始关就先停了键鼠服务），只能说清楚。
     */
    private void onRegisterTimeout() {
        if (!started || !registering || registered) return;
        Log.w(TAG, "注册蓝牙 HID " + REGISTER_TIMEOUT_MS / 1000 + " 秒没有回话：系统蓝牙卡住了，要重启手机");
        setState(STATE_STUCK);
    }

    /**
     * 设置里的"连续滚动"开关变了（设置页切开关时、回到主界面时都调，没变什么都不做）：注册着的就注销、用新描述符重新注册。
     * 正在注册的等它回话（onAppStatusChanged）再看。对方要删掉这台手机重新配对才认新描述符。
     */
    void descriptorChanged() {
        if (hid == null || reregistering || registering || !registered) return;
        if (regHires == prefs.smoothScroll()) return;
        reregister();
    }

    private void reregister() {
        Log.i(TAG, "连续滚动开关变了：用" + (prefs.smoothScroll() ? "带" : "不带") + "滚轮倍率的描述符重新注册蓝牙 HID");
        reregistering = true;
        connected = null;
        connecting = null;
        main.removeCallbacks(connectTimeout);
        setState(STATE_REGISTERING);
        main.removeCallbacks(reregisterFallback);
        main.postDelayed(reregisterFallback, REGISTER_TIMEOUT_MS);
        try {
            if (!hid.unregisterApp()) onReregisterTimeout();   // 系统没接：不等回话，直接重新注册
        } catch (SecurityException e) {
            reregistering = false;
            main.removeCallbacks(reregisterFallback);
            setState(STATE_NO_PERMISSION);
        }
    }

    /** 注销了却一直没回话（或系统不接注销）：照样重新注册。 */
    private void onReregisterTimeout() {
        main.removeCallbacks(reregisterFallback);
        if (!reregistering) return;
        reregistering = false;
        registered = false;
        if (started && hid != null) register();
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            int s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (s == BluetoothAdapter.STATE_ON && started && state == STATE_BT_DISABLED) {
                start();
            } else if (s == BluetoothAdapter.STATE_OFF && started) {
                main.removeCallbacks(registerTimeout);
                main.removeCallbacks(connectTimeout);
                hid = null;
                registering = false;
                registered = false;
                connected = null;
                connecting = null;
                setState(STATE_BT_DISABLED);
            }
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean isRegistered) {
            main.removeCallbacks(registerTimeout);
            registering = false;
            registered = isRegistered;
            if (!isRegistered) {
                connected = null;
                if (reregistering) {   // 为换描述符注销的：马上用新的重新注册
                    reregistering = false;
                    main.removeCallbacks(reregisterFallback);
                    if (started && hid != null) register();
                    return;
                }
                if (started) setState(STATE_FAILED);
                return;
            }
            if (regHires != prefs.smoothScroll()) {   // 注册的这一会儿开关又变了
                reregister();
                return;
            }
            setState(STATE_WAITING);
            BluetoothDevice host = plugged != null ? plugged : preferredHost();
            if (host != null) connect(host);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int s) {
            Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 连接状态：" + s + "（0 断开 1 连接中 2 已连接 3 断开中）");
            if (s == BluetoothProfile.STATE_CONNECTED) {
                main.removeCallbacks(connectTimeout);
                mouseButtons = 0;   // 新连上的主机那边什么键都没按着
                hold.reset();
                main.removeCallbacks(holdTimer);
                wheelOut.reset();   // 滚轮倍率先当 ×1，等主机重新来设
                connected = device;
                connecting = null;
                prefs.setBtHost(device.getAddress());
                setState(STATE_CONNECTED);
            } else if (s == BluetoothProfile.STATE_CONNECTING) {
                connecting = device;
                main.removeCallbacks(connectTimeout);   // 对方主动来连的也一样看着
                main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                setState(STATE_CONNECTING);
            } else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(connected)) connected = null;
                if (device.equals(connecting)) {
                    connecting = null;
                    main.removeCallbacks(connectTimeout);
                }
                if (connected == null && registered) setState(STATE_WAITING);
            }
        }

        /**
         * 对方切协议：0 = 简化的 boot 协议，1 = 正常的报文协议。现在只记日志：iPad 上只认光标移动、点击无效，
         * 怀疑和它有关（boot 协议下鼠标报文只有 3 字节，对方会按另一种格式读），先看清对方到底发没发。
         */
        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
            Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 切协议：" + (protocol == 0 ? "boot" : "report") + "（" + protocol + "）");
        }

        /**
         * 对方来要当前报文（有的系统在连上时问一次）：输入报文回一帧全零的；鼠标的 Feature 报告（滚轮倍率）回当前倍率，
         * 别的 Feature 没有。
         */
        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 要报文：type=" + type + " id=" + id + " size=" + bufferSize);
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE) {
                try {
                    if (id == Hid.REPORT_MOUSE) {
                        hid.replyReport(device, type, id, new byte[]{(byte) wheelOut.featureByte()});
                    } else {
                        hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                    }
                } catch (SecurityException ignored) {
                    // 权限被收回，下次 acquire 会重新检查
                }
                return;
            }
            int size = id == Hid.REPORT_KEYBOARD ? Hid.KEYBOARD_REPORT_SIZE
                    : id == Hid.REPORT_MOUSE ? Hid.MOUSE_REPORT_SIZE
                    : id == Hid.REPORT_CONSUMER ? Hid.CONSUMER_REPORT_SIZE
                    : id == Hid.REPORT_SYSTEM && absRegistered ? Hid.SYSTEM_REPORT_SIZE   // 注册了才有（描述符一直带，同绝对坐标）
                    : id == Hid.REPORT_ABS && absRegistered ? Hid.ABS_REPORT_SIZE : 0;
            try {
                if (size == 0) {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                } else if (id == Hid.REPORT_ABS) {
                    hid.replyReport(device, type, id, absReport);   // 上一次发的坐标
                } else {
                    hid.replyReport(device, type, id, new byte[size]);
                }
            } catch (SecurityException ignored) {
                // 权限被收回，下次 acquire 会重新检查
            }
        }

        /**
         * 对方设置报文（键盘指示灯之类）：照单全收。鼠标的 Feature 报告是滚轮倍率：不管注册的是哪份描述符都照办
         * （主机可能缓存着以前配对时带倍率的描述符），记下、告诉看着的界面（设置页显示这台主机开没开平滑滚动）。
         */
        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 设报文：type=" + type + " id=" + id + " 长度=" + (data == null ? 0 : data.length));
            if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE && id == Hid.REPORT_MOUSE) {
                int b = Hid.featureByte(data, Hid.REPORT_MOUSE);
                if (b >= 0) {
                    wheelOut.setMultipliers(Hid.multiplierV(b), Hid.multiplierH(b));
                    Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 设了滚轮倍率：竖 ×" + wheelOut.multV() + " 横 ×" + wheelOut.multH());
                }
                try {
                    hid.reportError(device, b >= 0 ? BluetoothHidDevice.ERROR_RSP_SUCCESS : BluetoothHidDevice.ERROR_RSP_INVALID_PARAM);
                } catch (SecurityException ignored) {
                    // 同上
                }
                if (b >= 0) setState(state);   // 状态没变，只是让设置页刷新"这台设备开了平滑滚动"
                return;
            }
            try {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            } catch (SecurityException ignored) {
                // 同上
            }
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            if (device.equals(connected)) connected = null;
            if (registered) setState(STATE_WAITING);
        }
    };

    // ═══════════ 连哪台 ═══════════

    /**
     * 设置页列的"连哪台"：配对过、可能当主机的设备（电脑、手机 / 平板、电视这类），按名字排；上次连的那台总在里面。
     * 耳机音箱、手表、手柄、只支持低功耗蓝牙的设备当不了蓝牙键盘的主机，不列，免得一长串挑不出来。
     */
    List<BluetoothDevice> hostCandidates() {
        List<BluetoothDevice> list = new ArrayList<>();
        String last = prefs.btHost();
        for (BluetoothDevice d : bondedDevices()) {
            if (d.getAddress().equals(last) || couldBeHost(d)) list.add(d);
        }
        return list;
    }

    private static boolean couldBeHost(BluetoothDevice d) {
        try {
            if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) return false;   // 蓝牙键盘鼠标走经典蓝牙
            BluetoothClass c = d.getBluetoothClass();
            if (c == null) return true;
            switch (c.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.COMPUTER:
                case BluetoothClass.Device.Major.PHONE:
                case BluetoothClass.Device.Major.MISC:
                case BluetoothClass.Device.Major.UNCATEGORIZED:
                    return true;
                case BluetoothClass.Device.Major.AUDIO_VIDEO:   // 电视、显示器、机顶盒算；耳机音箱不算
                    int dev = c.getDeviceClass();
                    return dev == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER
                            || dev == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR
                            || dev == BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX
                            || dev == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING;
                default:
                    return false;   // 可穿戴、外设（手柄、鼠标）、健康、玩具、成像
            }
        } catch (SecurityException e) {
            return true;
        }
    }

    /** 配对过的所有设备，按名字排。没权限时是空的。 */
    List<BluetoothDevice> bondedDevices() {
        List<BluetoothDevice> list = new ArrayList<>();
        if (adapter == null || !hasPermission(context)) return list;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) list.addAll(bonded);
            Collections.sort(list, (a, b) -> deviceName(a).compareToIgnoreCase(deviceName(b)));
        } catch (SecurityException ignored) {
            // 没权限：空列表
        }
        return list;
    }

    /** 换一台：断开当前的，连新的（记住它，下次自动连）。 */
    void connect(BluetoothDevice device) {
        prefs.setBtHost(device.getAddress());
        if (hid == null || !registered) return;   // 注册好了会自动连
        if (device.equals(connecting)) return;   // 正在连它：结果会回来，卡住了 onConnectTimeout 会处理
        try {
            if (connected != null && !connected.equals(device)) hid.disconnect(connected);
            // 正在连另一台：先放弃它，不然系统只回"已经在连了"
            if (connecting != null && !connecting.equals(device)) hid.disconnect(connecting);
            if (!device.equals(connected)) {
                connecting = device;
                setState(STATE_CONNECTING);
                Log.i(TAG, "向 " + deviceName(device) + " 发起连接");
                if (hid.connect(device)) {
                    main.removeCallbacks(connectTimeout);
                    main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                } else {
                    Log.i(TAG, "向 " + deviceName(device) + " 发起连接被系统拒绝");
                    connecting = null;
                    setState(STATE_WAITING);   // 对方不在附近、或还没配对成键盘：等对方来连
                }
            }
        } catch (SecurityException e) {
            setState(STATE_NO_PERMISSION);
        }
    }

    /** 上次连的那台（配对过才算）；没有就是 null。 */
    BluetoothDevice preferredHost() {
        String address = prefs.btHost();
        if (address.isEmpty()) return null;
        for (BluetoothDevice d : bondedDevices()) if (d.getAddress().equals(address)) return d;
        return null;
    }

    /** 苹果设备的服务列表里带这个苹果专用标识（iPad 实测有；AirPods 也有，但它们当不了主机）。 */
    private static final ParcelUuid APPLE_SERVICE = ParcelUuid.fromString("00000000-deca-fade-deca-deafdecacafe");

    /** 是不是苹果设备（键盘上的 GUI 键在苹果上是 ⌘）。认不出来（没权限、没查过服务）就当不是。 */
    static boolean isApple(BluetoothDevice d) {
        if (d == null) return false;
        try {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids == null) return false;
            for (ParcelUuid u : uuids) if (APPLE_SERVICE.equals(u)) return true;
        } catch (SecurityException ignored) {
            // 没权限：当不是
        }
        return false;
    }

    static String deviceName(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String n = d.getName();
            return n != null && !n.isEmpty() ? n : d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    // ═══════════ 发报文 ═══════════

    boolean isConnected() {
        return connected != null && hid != null;
    }

    /** 鼠标一帧：wheel / pan 是 1/120 格（Hid.WHEEL_UNIT），按主机设的倍率换算成计数、超了拆帧。 */
    void sendMouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (!isConnected()) return;
        hold.mouse(buttons, dx, dy, wheel, pan, SystemClock.uptimeMillis());
    }

    /** 连着的主机设的竖滚轮倍率（×1 = 照旧一格一格，RES_MULT = 平滑滚动开了）；没连着时没意义。 */
    int hostWheelMultiplier() {
        return wheelOut.multV();
    }

    /** 描述符里有绝对坐标指针（实验开关打开后注册的）。对方要重新配对过才认得。 */
    boolean absoluteAvailable() {
        return absRegistered;
    }

    /**
     * 绝对坐标（0..32767；点击照样走普通鼠标那一路，按键字节只是照抄，见 Hid.ABS_POINTER_DESC）。回正中、空中鼠标页里的激光笔式移动用。
     * 和上一次发的一样时差 1 再发（32767 分之一，看不出来）：Linux 内核只转发变了的绝对坐标，中间光标被相对移动挪走过的话，
     * 同样的值第二次就被吞掉（KDE 电脑实测：第一次跳到正中，之后都不动）。
     */
    void sendAbsolute(int x, int y) {
        if (!isConnected() || !absRegistered) return;
        if (x == lastAbsX && y == lastAbsY) {
            x = x > 0 ? x - 1 : x + 1;
            y = y > 0 ? y - 1 : y + 1;
        }
        lastAbsX = x;
        lastAbsY = y;
        Hid.absReport(absReport, mouseButtons, x, y);
        send(Hid.REPORT_ABS, absReport);
    }

    void sendKeyboard(byte[] report) {
        if (isConnected()) hold.keyboard(report, SystemClock.uptimeMillis());
    }

    /** 按一下媒体键（Consumer 用法码）：按下、松开（按钮条 / 角上按钮的媒体动作，协议 24 起和 F 行的第二功能共用报告 3）。 */
    void pressConsumer(int usage) {
        sendConsumer(usage);
        sendConsumer(0);
    }

    /**
     * 多媒体页现在按着的码（报告 3；0 = 松开）。F 行的第二功能按下发码、松开发 0（TouchRouter 的槽一次报一个码）。
     * 和按钮动作的"按一下"共用这一个报告：按着 F 行的音量时点一下角上的"播放"，音量会被提前松开（不会卡键，只是提前松）。
     */
    void sendConsumer(int usage) {
        if (isConnected()) hold.consumer(usage, SystemClock.uptimeMillis());
    }

    /** 系统键那一组现在按着的码（报告 6，协议 24；0 = 松开）。对方没重新配对过就不认报告 6，丢掉不管。 */
    void sendSystem(int usage) {
        if (isConnected()) hold.system(usage, SystemClock.uptimeMillis());
    }

    /** {@link #hold} 放行的报文真正发出去（主线程）。 */
    private final class HoldSink implements MinHold.Sink {
        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            if (!isConnected()) return;
            mouseButtons = buttons;
            wheelOut.mouse(mouseReport, buttons, dx, dy, wheel, pan, SystemClock.uptimeMillis(), r -> send(Hid.REPORT_MOUSE, r));
        }

        @Override
        public void keyboard(byte[] report) {
            if (isConnected()) send(Hid.REPORT_KEYBOARD, report);
        }

        @Override
        public void consumer(int usage) {
            if (isConnected()) send(Hid.REPORT_CONSUMER, Hid.usageReport(usage));
        }

        @Override
        public void system(int usage) {
            if (isConnected()) send(Hid.REPORT_SYSTEM, Hid.usageReport(usage));
        }

        @Override
        public void schedule(long delayMs) {
            main.removeCallbacks(holdTimer);
            main.postDelayed(holdTimer, delayMs);
        }
    }

    private void send(int id, byte[] report) {
        try {
            if (!hid.sendReport(connected, id, report)) Log.w(TAG, "报文 " + id + " 没发出去（系统蓝牙拒收，可能是链路忙）");
        } catch (SecurityException e) {
            setState(STATE_NO_PERMISSION);
        }
    }

    /**
     * 键盘上没有、要注入的系统键（Actions 里的 KEY 类动作）在蓝牙上对应哪个媒体键；0 = 蓝牙里没有通用的键。
     * 最近任务、所有应用、通知栏没有各系统通用的用法码，不做。
     */
    static int consumerUsage(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK: return 0x0224;              // AC Back
            case KeyEvent.KEYCODE_HOME: return 0x0223;              // AC Home
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return 0x00CD;  // Play/Pause
            case KeyEvent.KEYCODE_VOLUME_UP: return 0x00E9;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return 0x00EA;
            case KeyEvent.KEYCODE_VOLUME_MUTE: return 0x00E2;
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH: return 0x029D;   // AC Next Keyboard Layout Select（🌐）
            default: return 0;
        }
    }

    /** 还在等、还有人在用：再连一次上次那台。结果由 onConnectionStateChanged 回来，没连上会再排下一次。 */
    private void retryConnect() {
        if (state != STATE_WAITING || users == 0 || hid == null || !registered) return;
        BluetoothDevice host = preferredHost();
        if (host != null) connect(host);
    }

    /**
     * 连接 12 秒还没结果：系统卡在"连接中"了。主力机实测（2026-10-15）：从 iPad 换到电脑时撞上电脑同时来连，
     * 之后系统一直不报结果，再连只回"已经在连了"，要关开蓝牙才解开（而关开蓝牙会触发 onRegisterTimeout 那个毛病）。
     * 断开它（系统对还在连的会直接放弃、清掉"连接中"），回到等待，5 秒后照常重连。
     */
    private void onConnectTimeout() {
        BluetoothDevice d = connecting;
        if (hid == null || connected != null || d == null) return;
        Log.w(TAG, "向 " + deviceName(d) + " 连接 " + CONNECT_TIMEOUT_MS / 1000 + " 秒没有结果，先断开再重连");
        try {
            hid.disconnect(d);
        } catch (SecurityException e) {
            setState(STATE_NO_PERMISSION);
            return;
        }
        connecting = null;
        if (registered) setState(STATE_WAITING);
    }

    private void setState(int s) {
        state = s;
        main.removeCallbacks(retryLater);
        if (s == STATE_WAITING && users > 0) main.postDelayed(retryLater, RETRY_MS);
        String name = deviceName(connected != null ? connected : connecting);
        for (Listener l : new ArrayList<>(listeners)) l.onBtState(s, name);
    }
}
