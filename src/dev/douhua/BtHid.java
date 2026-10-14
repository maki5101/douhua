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

    private static final String TAG = "douhua";
    private static final long GRACE_MS = 60_000;
    private static BtHid instance;

    private final Context context;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final byte[] mouseReport = new byte[Hid.MOUSE_REPORT_SIZE];
    private final Runnable stopLater = this::stop;

    private int users;
    private boolean started;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
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

    /** 切回 DeX 模式时：立刻停，不等。 */
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
            if (!registered) register();
            return;
        }
        setState(STATE_REGISTERING);
        if (!adapter.getProfileProxy(context, proxyListener, BluetoothProfile.HID_DEVICE)) {
            setState(STATE_UNSUPPORTED);
        }
    }

    private void stop() {
        started = false;
        if (receiverOn) {
            context.unregisterReceiver(stateReceiver);
            receiverOn = false;
        }
        if (hid != null) {
            try {
                if (registered) hid.unregisterApp();   // 对方那边会看到键盘鼠标断开
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            } catch (SecurityException e) {
                Log.w(TAG, "停止蓝牙 HID 时没有权限：" + e.getMessage());
            }
        }
        hid = null;
        registered = false;
        connected = null;
        connecting = null;
        setState(STATE_OFF);
    }

    private final BluetoothProfile.ServiceListener proxyListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (!started) {
                adapter.closeProfileProxy(profile, proxy);
                return;
            }
            hid = (BluetoothHidDevice) proxy;
            register();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            hid = null;
            registered = false;
            connected = null;
            if (started) setState(STATE_FAILED);
        }
    };

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "Douhua", "Douhua touchpad & keyboard", "Douhua",
                BluetoothHidDevice.SUBCLASS1_COMBO, Hid.combinedDescriptor());
        try {
            if (!hid.registerApp(sdp, null, null, context.getMainExecutor(), callback)) setState(STATE_FAILED);
        } catch (SecurityException e) {
            setState(STATE_NO_PERMISSION);
        }
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            int s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (s == BluetoothAdapter.STATE_ON && started && state == STATE_BT_DISABLED) {
                start();
            } else if (s == BluetoothAdapter.STATE_OFF && started) {
                hid = null;
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
            registered = isRegistered;
            if (!isRegistered) {
                connected = null;
                if (started) setState(STATE_FAILED);
                return;
            }
            setState(STATE_WAITING);
            BluetoothDevice host = plugged != null ? plugged : preferredHost();
            if (host != null) connect(host);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int s) {
            if (s == BluetoothProfile.STATE_CONNECTED) {
                connected = device;
                connecting = null;
                prefs.setBtHost(device.getAddress());
                setState(STATE_CONNECTED);
            } else if (s == BluetoothProfile.STATE_CONNECTING) {
                connecting = device;
                setState(STATE_CONNECTING);
            } else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(connected)) connected = null;
                if (device.equals(connecting)) connecting = null;
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

        /** 对方来要当前报文（有的系统在连上时问一次）：回一帧全零的。 */
        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 要报文：type=" + type + " id=" + id + " size=" + bufferSize);
            int size = id == Hid.REPORT_KEYBOARD ? Hid.KEYBOARD_REPORT_SIZE
                    : id == Hid.REPORT_MOUSE ? Hid.MOUSE_REPORT_SIZE
                    : id == Hid.REPORT_CONSUMER ? Hid.CONSUMER_REPORT_SIZE : 0;
            try {
                if (size == 0) hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                else hid.replyReport(device, type, id, new byte[size]);
            } catch (SecurityException ignored) {
                // 权限被收回，下次 acquire 会重新检查
            }
        }

        /** 对方设置报文（键盘指示灯之类）：照单全收。 */
        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            Log.i(TAG, "蓝牙主机 " + deviceName(device) + " 设报文：type=" + type + " id=" + id + " 长度=" + (data == null ? 0 : data.length));
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
        try {
            if (connected != null && !connected.equals(device)) hid.disconnect(connected);
            if (!device.equals(connected)) {
                connecting = device;
                setState(STATE_CONNECTING);
                if (!hid.connect(device)) {
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

    void sendMouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (!isConnected()) return;
        Hid.mouseReport(mouseReport, buttons, dx, dy, wheel, pan);
        send(Hid.REPORT_MOUSE, mouseReport);
    }

    void sendKeyboard(byte[] report) {
        if (isConnected()) send(Hid.REPORT_KEYBOARD, report);
    }

    /** 按一下媒体键（Consumer 用法码）：按下、松开。 */
    void pressConsumer(int usage) {
        if (!isConnected()) return;
        send(Hid.REPORT_CONSUMER, new byte[]{(byte) usage, (byte) (usage >> 8)});
        send(Hid.REPORT_CONSUMER, new byte[]{0, 0});
    }

    private void send(int id, byte[] report) {
        try {
            hid.sendReport(connected, id, report);
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
            default: return 0;
        }
    }

    private void setState(int s) {
        state = s;
        String name = deviceName(connected != null ? connected : connecting);
        for (Listener l : new ArrayList<>(listeners)) l.onBtState(s, name);
    }
}
