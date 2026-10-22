package dev.douhua;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 空中指针（只在蓝牙模式）：转动手机移动光标（换算见 {@link AirPointer}），回正中发一帧绝对坐标（{@link #center}）。
 * 两种发法：空中鼠标页开着时"激光笔式"——豆花自己记光标在屏幕哪儿，直接发绝对坐标，对方的鼠标加速、蓝牙报文挤在一起到
 * 都不起作用，转同样的角度总走同样远（进页面后第一次移动先跳到正中，豆花才知道起点）；页面外（按钮条、音量键上的"按住移动"）
 * 照普通鼠标发相对位移，光标接着原来的位置走。两种都攒着、至少隔 SEND_MIN_GAP_MS 才发一次。
 * 由谁触发看动作放在哪：按住移动 / 按住拖动 / 回正中可以放在按钮条、角上按钮、空中鼠标页的大按钮、音量键上
 * （MainActivity.actionDown / actionUp）。按住类的按下 / 松开按来源分开记，一个来源松开不影响别的还按着的。
 * 陀螺仪按需开：开始跟随时注册，都松开 {@link #SENSOR_LINGER_MS} 后注销。所有方法都在主线程。
 */
final class AirMouse implements SensorEventListener {
    interface Host {
        /** 发一帧鼠标报文：宿主把触摸那一路按着的键并进来再发蓝牙。 */
        void airMouse(int airButtons, int dx, int dy);

        void airHaptic(int kind);

        /** 跟随 / 拖动的状态变了：刷新界面。 */
        void airChanged();
    }

    /** 按住类动作的来源：两个音量键（= HardKeys.VOL_*）、空中鼠标页的圆钮、大按钮、按钮条、角上按钮（后三个 + 序号）。 */
    static final int SRC_VOL_UP = 0, SRC_VOL_DOWN = 1, SRC_ROUND = 5, SRC_AIR_PAGE = 10, SRC_STRIP = 30, SRC_CORNER = 60;
    /** 圆钮点一下锁定的移动（不是手指按着，服务断开时不跟着松，所以编号在 SRC_ROUND 下面）。 */
    static final int SRC_ROUND_LOCK = 4;
    /** toggleDrag 用的来源（只能"点一下"的地方：手势、音量键的点一下）。 */
    private static final int SRC_TOGGLE = -1;

    /** 跟随停了以后传感器多留一会：紧接着再按就不用等它重新起来。 */
    private static final long SENSOR_LINGER_MS = 3000;
    private static final int GYRO_PERIOD_US = 5_000;   // 200Hz：每一步更细（Android 12 起 200Hz 以内不用权限）
    /**
     * 移动时的"颗粒"：手机每转 1.5° 一颗（同触控板的设置开关）。慢转时光标走约 11 个计数，
     * 和触控板上手指慢划 10dp（一颗）光标走的差不多；快了光标走得远、颗粒不变密，两边一样。
     */
    private static final float TEXTURE_STEP_DEG = 1.5f;
    private static final long TEXTURE_MIN_GAP_MS = 25;
    /** 发送最短间隔（相对位移）：蓝牙大约 11ms 送一次，发得比这密报文就挤在一起到，对方按"很快"加速，忽快忽慢。 */
    private static final long SEND_MIN_GAP_MS = 12;
    /**
     * 发送最短间隔（激光笔式）。双日志实测（2026-10-15，主力机 → KDE 电脑）：电脑按 11.25ms 一格收报文，持续最多一秒约 257 帧。
     * 原来至少隔 12ms（实际约 13ms）发，和格子对不上：有的格子空着、隔 22ms 才到，有的挤两帧——光标"卡卡的"。
     * 每帧都发（约 4.3ms）超过运力，排队，延迟到 260ms。8ms（陀螺仪每两帧发一次，约 8.6ms）：每格都有新位置，
     * 只用一半运力；同一格到两帧也没关系，绝对坐标后一帧直接盖掉前一帧，不像相对位移会被对方当成加速。
     */
    private static final long ABS_SEND_GAP_MS = 8;
    /** 激光笔式：灵敏度第 6 档时手机转多少度光标走一屏宽（每档 ×1.25，同相对的档位）。 */
    private static final float ABS_DEG_PER_WIDTH = 30f;

    /**
     * 调试日志（只在可调试包里，由 DebugMoves 收到 adb 广播打开；电脑上 tools/air-log.sh 收）：标签 douhua-air，
     * 每帧陀螺仪一行 g（传感器时间 ns、侧倾补偿后的角速度、全部平滑后的角速度，°/s）、每次发送一行 s（elapsedRealtime ns、
     * A 绝对坐标 / R 相对位移）、开始 / 停止跟随 f、回正中 c、按键 b。
     */
    static volatile boolean debugLog;
    /** 调试：激光笔式的发送最短间隔（毫秒），−1 = 默认的 ABS_SEND_GAP_MS。 */
    static volatile int debugAbsGapMs = -1;
    /**
     * 调试：临时换一组消抖参数 {截止, β, 判断在动的截止, 死区, 轨迹平滑 ms, 防抖半径}（描红测试的 A/B/C 轮）；
     * null = 用设置里的。下次开始跟随时生效。
     */
    static volatile float[] debugFilter;
    /** 调试：临时换方向换算（AirPointer.SPACE_*，描红测试按轮换）；−1 = 默认。下次开始跟随时生效。 */
    static volatile int debugSpace = -1;
    /** 现在用着的调试参数（和 debugFilter 不是同一个就要重新套）。 */
    private float[] appliedDebugFilter;
    private int appliedDebugSpace = -1;
    private static final String LOG_TAG = "douhua-air";

    private final BtHid bt;
    private final Prefs prefs;
    private final Host host;
    private final SensorManager sensors;
    private final Sensor gyro;
    private final Sensor gravity;
    private final Sensor accel;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AirPointer.Config config = new AirPointer.Config();
    private final AirPointer pointer = new AirPointer(config);
    private final Runnable sensorsOff = this::stopSensors;
    private final int[] out = new int[2];
    private final Set<Integer> followSources = new HashSet<>();
    private final Set<Integer> dragSources = new HashSet<>();
    private final Set<Integer> leftSources = new HashSet<>();
    private boolean toggledFollow;
    private boolean wasFollowing;
    private boolean sensorsOn;
    private int airButtons;
    private float textureDeg;
    private long lastTextureMs;
    private int level = 6;
    /** 激光笔式（绝对坐标）开着；对方屏幕宽 / 高（竖向每度走的绝对坐标按它放大，横竖才一样快）。 */
    private boolean absMode;
    private float aspect = 16f / 9;
    /**
     * 激光笔式下光标在哪（0..32767）；absKnown = 假时不知道（进页面后还没回过正中）。
     * 撞到屏幕边就截在边上、多出去的丢掉，往回一动马上动（和普通鼠标一样，斜着撞边会贴着边滑）。
     * 2026-10-16 试过另记"手指向哪"、出界时光标停在出界点等手转回来：用户说像光标扎进去了，哪有鼠标是这样的，撤了。
     */
    private int absX, absY;
    private boolean absKnown;
    /** 攒着还没发的：相对位移、激光笔式的新位置。 */
    private int pendDx, pendDy;
    private boolean pendAbs;
    private long lastSendMs;

    AirMouse(Context c, BtHid bt, Prefs prefs, Host host) {
        this.bt = bt;
        this.prefs = prefs;
        this.host = host;
        sensors = c.getSystemService(SensorManager.class);
        gyro = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        gravity = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
        accel = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
    }

    static boolean supported(Context c) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    void setSensitivity(int level) {
        this.level = level;
        applyGain();
    }

    /** 调试广播换过参数（或撤销了）就套上；没换不动。 */
    private void applyDebugFilter() {
        int sp = debugSpace;
        if (sp >= 0 && sp != config.space) {   // 描红按轮换换算：角度校正也换成那种换算的默认
            config.space = sp;
            float[] fix = AirPointer.defaultFix(sp);
            config.fixVDeg = fix[0];
            config.fixHDeg = fix[1];
        }
        else if (appliedDebugSpace >= 0) loadFilter();   // 调试换算撤了：回到设置里的
        appliedDebugSpace = sp;
        float[] d = debugFilter;
        if (d == appliedDebugFilter) return;
        appliedDebugFilter = d;
        if (d == null) {
            loadFilter();
            return;
        }
        config.minCutoffHz = d[0];
        config.beta = d[1];
        config.speedCutoffHz = d[2];
        config.tightenDps = d[3];
        config.pathSmoothMs = d[4];
        config.stringDeg = d[5];
    }

    /** 设置里的消抖参数（设置页改完回来时调）。 */
    void loadFilter() {
        AirPointer.Config def = new AirPointer.Config();
        config.minCutoffHz = def.minCutoffHz;
        config.beta = def.beta;
        config.speedCutoffHz = def.speedCutoffHz;
        config.tightenDps = def.tightenDps;
        config.pathSmoothMs = def.pathSmoothMs;
        config.stringDeg = def.stringDeg;
        config.space = def.space;
        prefs.airFilter(config);   // 角度校正跟着换算一起读
    }

    /** 每度多少：相对发法是鼠标计数，激光笔式是绝对坐标（一屏宽 32767）。 */
    private void applyGain() {
        float rel = AirPointer.countsPerDegree(level);
        config.countsPerDegree = absMode ? rel / 30f * (Hid.ABS_MAX / ABS_DEG_PER_WIDTH) : rel;
    }

    /** 空中鼠标页开着时用激光笔式（绝对坐标）；aspect = 对方屏幕宽 / 高。 */
    void setAbsoluteMode(boolean on, float aspect) {
        this.aspect = aspect;
        if (on == absMode) return;
        flush();
        absMode = on;
        absKnown = false;
        applyGain();
        pointer.resetMotion();
    }

    void setRotation(int rotation) {
        pointer.setRotation(rotation);
    }

    /**
     * 光标被触控板那一路挪过（空中鼠标页上调出的键盘带小触控板）：激光笔式记的位置不准了，
     * 下次开始跟随先回正中（updateFollow 里不知道起点就回正中）。相对发法用不到这个位置，无条件调也没影响。
     */
    void forgetPosition() {
        absKnown = false;
    }

    // ═══════════ 按住类 ═══════════

    /** 按住移动：按着时光标跟着手机走。 */
    void holdFollow(int source, boolean on) {
        if (on) followSources.add(source);
        else followSources.remove(source);
        updateFollow();
    }

    /** 按住拖动：按着时左键按下、光标跟着手机走。 */
    void holdDrag(int source, boolean on) {
        boolean was = !dragSources.isEmpty();
        if (on) dragSources.add(source);
        else dragSources.remove(source);
        boolean now = !dragSources.isEmpty();
        if (now != was) {
            updateLeft();
            host.airHaptic(now ? GestureEngine.HAPTIC_DRAG_START : GestureEngine.HAPTIC_DRAG_END);
        }
        updateFollow();
    }

    /** 按住左键（音量键按住）：只按着键、不跟随（震动由宿主按"左键"按钮那一套给）。 */
    void holdLeft(int source, boolean on) {
        if (on) leftSources.add(source);
        else leftSources.remove(source);
        updateLeft();
    }

    /** 只能"点一下"的地方（手势、音量键的点一下）：点一下开始跟随，再点一下停。 */
    void toggleFollow() {
        toggledFollow = !toggledFollow;
        updateFollow();
    }

    void toggleDrag() {
        holdDrag(SRC_TOGGLE, !dragSources.contains(SRC_TOGGLE));
    }

    /** 点出来的跟随（手势、音量键的点一下）关掉；本来就没开返回 false。 */
    boolean clearToggledFollow() {
        if (!toggledFollow) return false;
        toggledFollow = false;
        updateFollow();
        return true;
    }

    /** 只放掉编号 ≥ minSource 的按住（屏幕上的按钮）：服务断了，经服务转来的这些按钮等不到松开；音量键、点出来的切换不动。 */
    void releaseFrom(int minSource) {
        boolean dragging = !dragSources.isEmpty();
        followSources.removeIf(s -> s >= minSource);
        dragSources.removeIf(s -> s >= minSource);
        leftSources.removeIf(s -> s >= minSource);
        updateLeft();
        if (dragging && dragSources.isEmpty()) host.airHaptic(GestureEngine.HAPTIC_DRAG_END);
        updateFollow();
    }

    /** 全部放开：停跟随、松开这一路按着的键（暂停、断线、离开空中鼠标页时调）。 */
    void releaseAll() {
        boolean dragging = !dragSources.isEmpty();
        followSources.clear();
        dragSources.clear();
        leftSources.clear();
        toggledFollow = false;
        updateLeft();
        if (dragging) host.airHaptic(GestureEngine.HAPTIC_DRAG_END);
        updateFollow();
    }

    int buttons() {
        return airButtons;
    }

    boolean following() {
        return toggledFollow || !followSources.isEmpty() || !dragSources.isEmpty();
    }

    boolean dragging() {
        return !dragSources.isEmpty();
    }

    private void updateLeft() {
        boolean down = !leftSources.isEmpty() || !dragSources.isEmpty();
        int b = down ? airButtons | Proto.BUTTON_LEFT : airButtons & ~Proto.BUTTON_LEFT;
        if (b == airButtons) return;
        flush();   // 先走到，再按 / 松
        airButtons = b;
        if (debugLog) Log.d(LOG_TAG, "b " + SystemClock.elapsedRealtimeNanos() + " " + b);
        host.airMouse(airButtons, 0, 0);
    }

    private void updateFollow() {
        boolean f = following();
        if (f && !wasFollowing) applyDebugFilter();
        if (debugLog && f != wasFollowing) logFollow(f);
        if (f && !wasFollowing) {
            pointer.resetMotion();
            textureDeg = 0;
            if (absMode && !absKnown) center();   // 激光笔式不知道起点：先到正中，从那儿开始跟手
            main.removeCallbacks(sensorsOff);
            startSensors();
        } else if (!f && wasFollowing) {
            flush();
            main.postDelayed(sensorsOff, SENSOR_LINGER_MS);
        }
        wasFollowing = f;
        hostChanged();
    }

    private void hostChanged() {
        host.airChanged();
    }

    /** 调试日志：开始 / 停止跟随，带上对时（elapsedRealtime ↔ 墙上时间）、拿法、参数。 */
    private void logFollow(boolean on) {
        if (!on) {
            Log.d(LOG_TAG, "f 0 " + SystemClock.elapsedRealtimeNanos());
            return;
        }
        float[] g = pointer.gravityNow();
        Log.d(LOG_TAG, String.format(Locale.ROOT,
                "f 1 %d wall=%d g=%.2f,%.2f,%.2f space=%d abs=%b aspect=%.3f cpd=%.1f gap=%d cut=%.2f beta=%.2f scut=%.2f dz=%.1f path=%.0f str=%.2f",
                SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(), g[0], g[1], g[2], config.space, absMode, aspect,
                config.countsPerDegree, !absMode ? SEND_MIN_GAP_MS : debugAbsGapMs >= 0 ? debugAbsGapMs : ABS_SEND_GAP_MS,
                config.minCutoffHz, config.beta, config.speedCutoffHz, config.tightenDps, config.pathSmoothMs, config.stringDeg));
    }

    // ═══════════ 回正中 ═══════════

    /**
     * 回正中：一帧绝对坐标到屏幕正中（描述符里一直带绝对坐标指针）。不认绝对坐标的主机上光标不动。
     * 以前试过"每台主机校准 + 推进角再按步子走一半"：对方有鼠标加速，快慢不同走的距离不同，怎么都不准，撤了。
     */
    void center() {
        if (bt.connectedDevice() == null) return;
        pendDx = pendDy = 0;
        pendAbs = false;
        absX = absY = Hid.ABS_CENTER;
        absKnown = true;
        bt.sendAbsolute(absX, absY);
        lastSendMs = SystemClock.uptimeMillis();
        if (debugLog) Log.d(LOG_TAG, "c " + SystemClock.elapsedRealtimeNanos() + " " + absX + " " + absY);
        pointer.resetMotion();   // 跟随中：滤波里攒着的别接着走
        host.airHaptic(GestureEngine.HAPTIC_TICK);
    }

    /** 攒着的发出去（至少隔 SEND_MIN_GAP_MS，见 maybeSend；按键变化前、停止跟随时立刻发）。 */
    private void flush() {
        if (pendAbs) {
            bt.sendAbsolute(absX, absY);
            if (debugLog) Log.d(LOG_TAG, "s " + SystemClock.elapsedRealtimeNanos() + " A " + absX + " " + absY);
            pendAbs = false;
        } else if (pendDx != 0 || pendDy != 0) {
            host.airMouse(airButtons, pendDx, pendDy);
            if (debugLog) Log.d(LOG_TAG, "s " + SystemClock.elapsedRealtimeNanos() + " R " + pendDx + " " + pendDy);
            pendDx = pendDy = 0;
        } else {
            return;
        }
        lastSendMs = SystemClock.uptimeMillis();
    }

    private void maybeSend() {
        long gap = !absMode ? SEND_MIN_GAP_MS : debugAbsGapMs >= 0 ? debugAbsGapMs : ABS_SEND_GAP_MS;
        if (SystemClock.uptimeMillis() - lastSendMs >= gap) flush();
    }

    private static int clampAbs(int v) {
        return Math.max(0, Math.min(Hid.ABS_MAX, v));
    }

    // ═══════════ 传感器 ═══════════


    private void startSensors() {
        if (sensorsOn || gyro == null) return;
        sensorsOn = true;
        sensors.registerListener(this, gyro, GYRO_PERIOD_US, 0);
        Sensor down = gravity != null ? gravity : accel;
        if (down != null) sensors.registerListener(this, down, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopSensors() {
        if (!sensorsOn) return;
        sensorsOn = false;
        sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_GRAVITY) {
            pointer.gravity(e.values[0], e.values[1], e.values[2]);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            pointer.accel(e.values[0], e.values[1], e.values[2], e.timestamp);
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            if (!following()) return;
            boolean moved = pointer.gyro(e.values[0], e.values[1], e.values[2], e.timestamp, out);
            if (debugLog) {
                // 后六个：手机坐标系的陀螺仪读数（rad/s）和当前重力，方向换算有疑问时能离线重算
                float[] g = pointer.gravityNow();
                Log.d(LOG_TAG, String.format(Locale.ROOT, "g %d %.3f %.3f %.3f %.3f %.4f %.4f %.4f %.2f %.2f %.2f", e.timestamp,
                        pointer.dbgRawH, pointer.dbgRawV, pointer.dbgOutH, pointer.dbgOutV,
                        e.values[0], e.values[1], e.values[2], g[0], g[1], g[2]));
            }
            if (moved) {
                if (absMode) {
                    absX = clampAbs(absX + out[0]);
                    absY = clampAbs(absY + Math.round(out[1] * aspect));
                    pendAbs = true;
                } else {
                    pendDx += out[0];
                    pendDy += out[1];
                }
                maybeSend();
            }
            if (airButtons == 0) texture(pointer.travelDeg(), e.timestamp / 1_000_000);   // 拖动时是持续轻震
        }
    }

    /** 同 GestureEngine.texture：每走一步发一颗，离上一颗太近就跳过（距离照扣，不攒着补发）。 */
    private void texture(float deg, long tMs) {
        textureDeg += deg;
        if (textureDeg < TEXTURE_STEP_DEG) return;
        textureDeg %= TEXTURE_STEP_DEG;
        if (tMs - lastTextureMs < TEXTURE_MIN_GAP_MS) return;
        lastTextureMs = tMs;
        host.airHaptic(GestureEngine.HAPTIC_TEXTURE);   // 宿主按设置开关和触摸那一路的状态决定震不震
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不用
    }
}
