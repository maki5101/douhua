package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 空中鼠标的离线测试：音量键"点一下 / 按住"、陀螺仪换算、回正中的步子序列、绝对坐标描述符。 */
public final class AirMouseTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        keysTapOnlyFiresOnDown();
        keysHoldOnly();
        keysTapAndHold();
        keysCancel();
        keysResetWhileHeld();
        keysForgetDropsStaleUp();
        pointerFlatYawAndPitch();
        pointerRollDoesNotLeak();
        pointerCameraPose();
        pointerSpaces();
        pointerFix();
        pointerDeadZoneAndClamp();
        pointerSensitivityLevels();
        pointerTravel();
        pointerString();
        hidAbsoluteDescriptor();

        System.err.println("空中鼠标：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 音量键 ═══════════

    static final class KeysRec implements HardKeys.Output {
        final List<String> log = new ArrayList<>();
        final HardKeys keys = new HardKeys(this);

        @Override public void tap(int k) { log.add("t" + k); }
        @Override public void holdStart(int k) { log.add("h" + k); }
        @Override public void holdEnd(int k) { log.add("e" + k); }
        @Override public void schedule(int k, long ms) { log.add("s" + k); }
        @Override public void cancelSchedule(int k) { log.add("x" + k); }
    }

    static KeysRec keys(boolean tap, boolean hold) {
        KeysRec r = new KeysRec();
        r.keys.setConfig(UP, tap, hold);
        return r;
    }

    static final int UP = HardKeys.VOL_UP, DOWN = HardKeys.VOL_DOWN;

    static void keysTapOnlyFiresOnDown() {
        KeysRec r = keys(true, false);
        r.keys.key(UP, true, 0);
        eq(r.log, list("t0"), "只设了点一下：按下当场就算");
        r.keys.key(UP, false, 900);
        eq(r.log, list("t0"), "只设了点一下：按多久都只算一下");
        check(!r.keys.mapped(DOWN), "没设的键不归豆花（照常调音量）");
    }

    static void keysHoldOnly() {
        KeysRec r = keys(false, true);
        r.keys.key(UP, true, 0);
        r.keys.key(UP, false, 100);
        eq(r.log, list("s0", "x0"), "只设了按住：没按够就松开，什么都不做");
        r.keys.key(UP, true, 200);
        r.keys.onTimer(UP, 500);
        r.keys.key(UP, false, 1500);
        eq(r.log.subList(2, r.log.size()), list("s0", "h0", "e0"), "只设了按住：按够了开始，松开结束");
    }

    static void keysTapAndHold() {
        KeysRec r = keys(true, true);
        r.keys.key(UP, true, 0);
        r.keys.key(UP, false, 150);
        eq(r.log, list("s0", "x0", "t0"), "都设了：没按够算点一下（松开时）");
        r.keys.key(UP, true, 300);
        r.keys.onTimer(UP, 600);
        r.keys.key(UP, false, 900);
        eq(r.log.subList(3, r.log.size()), list("s0", "h0", "e0"), "都设了：按够了算按住，不再算点一下");
    }

    static void keysCancel() {
        KeysRec r = keys(true, true);
        r.keys.key(UP, true, 0);
        r.keys.onTimer(UP, 300);
        r.keys.cancel(UP);
        r.keys.key(UP, false, 400);
        eq(r.log, list("s0", "h0", "e0"), "按住中被系统取消：结束按住，之后的松开不算");
        r.keys.key(UP, true, 500);
        r.keys.cancel(UP);
        eq(r.log.subList(3, r.log.size()), list("s0", "x0"), "还没按够就被取消：不算点一下");
    }

    static void keysResetWhileHeld() {
        KeysRec r = keys(true, true);
        r.keys.key(UP, true, 0);
        r.keys.onTimer(UP, 300);
        r.keys.reset();
        r.keys.key(UP, false, 900);
        eq(r.log, list("s0", "h0", "e0"), "reset：按住中的先结束，还按着的键松开时不算");
        r.keys.key(UP, true, 1000);
        r.keys.key(UP, false, 1050);
        eq(r.log.get(r.log.size() - 1), "t0", "reset 后再按：照常算");
    }

    static void keysForgetDropsStaleUp() {
        KeysRec r = keys(true, false);
        r.keys.key(UP, true, 0);
        r.keys.forget();
        r.keys.key(UP, false, 100);
        r.keys.key(UP, true, 200);
        eq(r.log, list("t0", "t0"), "forget：停着时漏掉的松开不影响下一次按下");
    }

    // ═══════════ 陀螺仪换算 ═══════════

    static final float G = 9.81f;

    /** 几何测试用：不带角度校正（默认校正是用户自己的歪法，单独测）。 */
    static AirPointer.Config cfg0() {
        AirPointer.Config c = new AirPointer.Config();
        c.fixVDeg = 0;
        c.fixHDeg = 0;
        return c;
    }
    static final float DEG = (float) Math.toRadians(1);
    /** 默认参数下 20°/s 转 1 秒应走的计数：停下时光标离手一根绳长。 */
    static final int FULL = Math.round((20 - cfg0().stringDeg) * 30);

    /** 100Hz 喂 seconds 秒恒定角速度，返回位移总和。 */
    static int[] feed(AirPointer p, float wx, float wy, float wz, float seconds) {
        int[] sum = new int[2], out = new int[2];
        long t = 1_000_000_000L;
        p.resetMotion();
        int frames = Math.round(seconds * 100);
        for (int i = 0; i <= frames + 50; i++) {   // 转完再停 0.5 秒：滤波里的余量走完
            boolean on = i <= frames;
            if (p.gyro(on ? wx : 0, on ? wy : 0, on ? wz : 0, t, out)) {
                sum[0] += out[0];
                sum[1] += out[1];
            }
            t += 10_000_000L;
        }
        return sum;
    }

    static AirPointer pointer(float gx, float gy, float gz) {
        AirPointer p = new AirPointer(cfg0());
        p.gravity(gx, gy, gz);
        return p;
    }

    /** 斜基换算（歪着、仰着拿时水平扫 / 点头都不串，是斜基专有的性质）。 */
    static AirPointer oblique(float gx, float gy, float gz) {
        AirPointer.Config c = cfg0();
        c.space = AirPointer.SPACE_OBLIQUE;
        AirPointer p = new AirPointer(c);
        p.gravity(gx, gy, gz);
        return p;
    }

    static void pointerFlatYawAndPitch() {
        // 平放屏幕朝上、顶边朝屏幕：右转 = 绕"上"顺时针（ωz < 0）→ 光标往右
        int[] s = feed(pointer(0, 0, G), 0, 0, -20 * DEG, 1);
        check(s[0] > 0 && Math.abs(s[1]) <= 1, "平放右转：光标往右，不上下跑（" + s[0] + "," + s[1] + "）");
        // 灵敏度固定：20°/s 转 1 秒 = (20° − 防抖半径) × 每度 30 计数（停下时离手一根绳长；尾巴被软死区吃掉一点）
        check(Math.abs(s[0] - FULL) <= 35, "平放右转：总量 ≈ (20° − 绳长) × 30 计数（" + s[0] + "，应 ≈ " + FULL + "）");
        // 抬头 = 绕右轴正转（ωx > 0）→ 光标往上
        int[] u = feed(pointer(0, 0, G), 20 * DEG, 0, 0, 1);
        check(u[1] < 0 && Math.abs(u[0]) <= 1, "平放抬头：光标往上，不左右跑（" + u[0] + "," + u[1] + "）");
        // 不加速：同样转 20°，快转慢转走得一样远
        int[] fast = feed(pointer(0, 0, G), 0, 0, -40 * DEG, 0.5f);
        check(Math.abs(fast[0] - s[0]) <= 30, "快转（40°/s 0.5 秒）和慢转（20°/s 1 秒）走得一样远（" + fast[0] + " vs " + s[0] + "）");
    }

    static void pointerRollDoesNotLeak() {
        // 以下是斜基的性质（玩家空间歪着拿水平扫会斜着走，见 AirPointer.SPACE_PLAYER）
        float c = (float) Math.cos(Math.toRadians(30)), sn = (float) Math.sin(Math.toRadians(30));
        float om = -20 * DEG;   // 右转 20°/s
        // 手机在手里歪 30°（重力"上"在设备坐标里是 (−sin, 0, cos)），胳膊水平扫 = 绕竖直轴（就是"上"）转：只左右、和平放一样远
        int[] sweep = feed(oblique(-sn * G, 0, c * G), -sn * om, 0, c * om, 1);
        check(sweep[0] > 0 && Math.abs(sweep[1]) <= 1, "歪 30° 水平扫：只往右（" + sweep[0] + "," + sweep[1] + "）");
        check(Math.abs(sweep[0] - FULL) <= 35, "歪 30° 水平扫：和平放一样远（" + sweep[0] + "）");
        // 歪 30° 手腕点头 = 绕手机自己的右轴转：只上下、和平放一样远
        int[] nod = feed(oblique(-sn * G, 0, c * G), 20 * DEG, 0, 0, 1);
        check(nod[1] < 0 && Math.abs(nod[0]) <= 1, "歪 30° 点头：只往上（" + nod[0] + "," + nod[1] + "）");
        check(Math.abs(-nod[1] - FULL) <= 35, "歪 30° 点头：和平放一样远（" + nod[1] + "）");
        // 平放时绕顶边拧（像拧门把手）：不动
        int[] roll = feed(oblique(0, 0, G), 0, 20 * DEG, 0, 1);
        check(Math.abs(roll[0]) <= 1 && Math.abs(roll[1]) <= 1, "平放拧手腕：不动（" + roll[0] + "," + roll[1] + "）");
        // 仰 50°、歪 8°（用户描红时的拿法，"上" = (cos50·sin(−8), sin50, cos50·cos8)）：点头只上下、水平扫只左右、绕水平朝前拧不动
        float e = (float) Math.toRadians(50), r = (float) Math.toRadians(-8);
        float ux = (float) (Math.cos(e) * Math.sin(r)), uy = (float) Math.sin(e), uz = (float) (Math.cos(e) * Math.cos(r));
        int[] nod2 = feed(oblique(ux * G, uy * G, uz * G), 20 * DEG, 0, 0, 1);
        check(nod2[1] < 0 && Math.abs(nod2[0]) <= 1, "仰 50° 歪 8° 点头：只往上（" + nod2[0] + "," + nod2[1] + "）");
        int[] sweep2 = feed(oblique(ux * G, uy * G, uz * G), ux * om, uy * om, uz * om, 1);
        check(sweep2[0] > 0 && Math.abs(sweep2[1]) <= 1, "仰 50° 歪 8° 水平扫：只往右（" + sweep2[0] + "," + sweep2[1] + "）");
        float pn = (float) Math.sqrt(uy * uy + uz * uz);
        int[] twist = feed(oblique(ux * G, uy * G, uz * G), 0, uz / pn * 20 * DEG, -uy / pn * 20 * DEG, 1);
        check(Math.abs(twist[0]) <= 1 && Math.abs(twist[1]) <= 1, "仰 50° 歪 8° 转小臂（绕水平朝前）：不动（" + twist[0] + "," + twist[1] + "）");
    }

    static void pointerSpaces() {
        // 三种换算平放时都一样：右转 → 右，抬头 → 上
        for (int sp : new int[]{AirPointer.SPACE_OBLIQUE, AirPointer.SPACE_PLAYER, AirPointer.SPACE_WORLD}) {
            AirPointer.Config c = cfg0();
            c.space = sp;
            AirPointer p = new AirPointer(c);
            p.gravity(0, 0, G);
            int[] yaw = feed(p, 0, 0, -20 * DEG, 1);
            p = new AirPointer(c);
            p.gravity(0, 0, G);
            int[] pitch = feed(p, 20 * DEG, 0, 0, 1);
            check(yaw[0] > 0 && Math.abs(yaw[1]) <= 1 && pitch[1] < 0 && Math.abs(pitch[0]) <= 1,
                    "换算 " + sp + " 平放：右转往右、抬头往上（" + yaw[0] + "," + yaw[1] + " / " + pitch[0] + "," + pitch[1] + "）");
        }
    }

    static void pointerFix() {
        // 竖线校正 +10°（逆时针）：纯抬头 → 光标往上、往左偏 10°；横线不动
        AirPointer.Config c = cfg0();
        c.fixVDeg = 10;
        AirPointer p = new AirPointer(c);
        p.gravity(0, 0, G);
        int[] up = feed(p, 20 * DEG, 0, 0, 1);
        double ang = Math.toDegrees(Math.atan2(-up[1], up[0]));   // 数学角：正上 = 90
        check(Math.abs(ang - 100) < 1.5, "竖线校正 +10°：抬头走 100°（" + String.format("%.1f", ang) + "°）");
        p = new AirPointer(c);
        p.gravity(0, 0, G);
        int[] right = feed(p, 0, 0, -20 * DEG, 1);
        check(Math.abs(right[1]) <= 1 && right[0] > 0, "竖线校正不动横线（" + right[0] + "," + right[1] + "）");
        // 横线校正 +5°：纯右转 → 往右上 5°
        c = cfg0();
        c.fixHDeg = 5;
        p = new AirPointer(c);
        p.gravity(0, 0, G);
        right = feed(p, 0, 0, -20 * DEG, 1);
        ang = Math.toDegrees(Math.atan2(-right[1], right[0]));
        check(Math.abs(ang - 5) < 1.5, "横线校正 +5°：右转走 5°（" + String.format("%.1f", ang) + "°）");
        eq(new AirPointer.Config().space, AirPointer.SPACE_WORLD, "默认换算是世界空间");
    }

    static void pointerCameraPose() {
        // 相机式：竖着拿、背面朝屏幕，"上"是设备 +Y
        int[] yaw = feed(pointer(0, G, 0), 0, -20 * DEG, 0, 1);
        int[] pitch = feed(pointer(0, G, 0), 20 * DEG, 0, 0, 1);
        check(yaw[0] > 0 && Math.abs(yaw[1]) <= 1, "相机式右转：光标往右（" + yaw[0] + "," + yaw[1] + "）");
        check(pitch[1] < 0 && Math.abs(pitch[0]) <= 1, "相机式抬头：光标往上（" + pitch[0] + "," + pitch[1] + "）");
    }

    /** 平放、绕"上"按 hz 赫兹、幅度 ±amp °/s 来回晃 2 秒（200Hz 采样），返回光标离起点最远多少计数。 */
    static int tremorPeak(AirPointer p, float hz, float amp) {
        p.gravity(0, 0, G);
        int[] out = new int[2];
        int x = 0, peak = 0;
        long t = 1_000_000_000L;
        for (int i = 0; i < 400; i++) {
            float w = (float) (amp * Math.sin(2 * Math.PI * hz * i / 200.0)) * DEG;
            if (p.gyro(0, 0, w, t, out)) {
                x += out[0];
                peak = Math.max(peak, Math.abs(x));
            }
            t += 5_000_000L;
        }
        return peak;
    }

    static void pointerDeadZoneAndClamp() {
        // 手抖：10Hz ±8°/s 和 4Hz ±4°/s 两种来回晃，和关掉消抖比光标晃多远
        for (float[] tremor : new float[][]{{10, 8}, {4, 4}}) {
            AirPointer.Config raw = cfg0();
            raw.minCutoffHz = 1e6f;
            raw.tightenDps = 0;
            raw.pathSmoothMs = 0;
            int with = tremorPeak(new AirPointer(cfg0()), tremor[0], tremor[1]);
            int without = tremorPeak(new AirPointer(raw), tremor[0], tremor[1]);
            check(with <= Math.max(1, without * 0.45f), "手抖 " + (int) tremor[0] + "Hz ±" + (int) tremor[1] + "°/s：消抖后晃 " + with
                    + " 计数（不消抖 " + without + "）");
        }

        // 慢慢微调（2°/s 转 2 秒 = 4°）：不能被吃掉（一刀切的死区会全吃掉），只是缩小
        int[] slow = feed(pointer(0, 0, G), 0, 0, -2 * DEG, 2);
        check(slow[0] > 20 && slow[0] < 4 * 30, "慢慢微调 4°：还能走（" + slow[0] + " 计数，不缩小是 120）");

        int[] out = new int[2];
        AirPointer q = pointer(0, 0, G);
        q.gyro(0, 0, -100 * DEG, 1_000_000_000L, out);
        boolean moved = q.gyro(0, 0, -100 * DEG, 3_000_000_000L, out);   // 隔了 2 秒
        check(!moved || Math.abs(out[0]) <= 30 * 100 * 0.05f + 1, "两帧隔很久：按 50ms 封顶，不一步跳远（" + out[0] + "）");
    }

    static void pointerSensitivityLevels() {
        check(Math.abs(AirPointer.countsPerDegree(6) - 30f) < 1e-3, "灵敏度第 6 档 = 30");
        check(AirPointer.countsPerDegree(1) < AirPointer.countsPerDegree(10), "灵敏度单调递增");
        eq(AirPointer.countsPerDegree(0), AirPointer.countsPerDegree(1), "灵敏度 0 截到 1");
        eq(AirPointer.countsPerDegree(11), AirPointer.countsPerDegree(10), "灵敏度 11 截到 10");
    }

    static void pointerTravel() {
        AirPointer p = pointer(0, 0, G);
        int[] out = new int[2];
        long t = 1_000_000_000L;
        float sum = 0;
        for (int i = 0; i <= 100; i++) {   // 20°/s 转 1 秒
            p.gyro(0, 0, 20 * DEG, t, out);
            sum += p.travelDeg();
            t += 10_000_000L;
        }
        check(sum > 17.5 && sum < 20.5, "转了多少度：20°/s 转 1 秒约 20°（滤波起步慢一点）（" + sum + "）");
        for (int i = 0; i < 40; i++) {   // 停下（平滑窗口里的余量走完）
            t += 10_000_000L;
            p.gyro(0, 0, 0, t, out);
        }
        check(p.travelDeg() < 0.01f, "停下来：不再算转动（" + p.travelDeg() + "）");
    }

    static void pointerString() {
        // 往右扫、同时上下晃（3Hz ±6°/s）：轨迹平滑把路线上的晃动抹平，往右走的量不少
        int[] off = wobble(0, 0), def = wobble(250, 0), strong = wobble(300, 0);
        check(def[1] <= off[1] * 0.6f, "轨迹平滑 250ms：往右扫时上下晃的幅度降到 6 成以下（" + def[1] + " vs 关掉 " + off[1] + "）");
        check(strong[1] <= off[1] * 0.35f, "轨迹平滑 300ms：降到 3.5 成以下（" + strong[1] + " vs 关掉 " + off[1] + "）");
        check(Math.abs(def[0] - off[0]) <= 5, "轨迹平滑不少走（" + def[0] + " vs " + off[0] + "）");
        // 原路掉头不拖：往右 1 秒再往左 1 秒，回到起点附近
        AirPointer.Config pc = cfg0();
        pc.pathSmoothMs = 250;
        AirPointer q = new AirPointer(pc);
        q.gravity(0, 0, G);
        int[] out = new int[2];
        long t = 1_000_000_000L;
        int x = 0, xMid = 0;
        for (int i = 0; i < 500; i++) {
            float w = i < 200 ? -20 : i < 400 ? 20 : 0;
            if (q.gyro(0, 0, w * DEG, t, out)) x += out[0];
            if (i == 199) xMid = x;
            t += 5_000_000L;
        }
        check(Math.abs(x) <= 10 && xMid > 500, "原路往返：回到起点（中途 " + xMid + "，最后 " + x + "）");

        // 防抖半径：掉头 0.6°（不到两倍绳长 1°），光标不往回走
        AirPointer.Config c = cfg0();
        c.stringDeg = 0.5f;
        c.tightenDps = 0;
        AirPointer p = new AirPointer(c);
        p.gravity(0, 0, G);
        t = 1_000_000_000L;
        x = 0;
        int maxX = 0;
        for (int i = 0; i < 400; i++) {   // 200Hz：先 10°/s 0.2 秒 = 2°，停 0.5 秒，再左 3°/s 0.2 秒 = 0.6°，停
            float w = i < 40 ? -10 : i < 140 ? 0 : i < 180 ? 3 : 0;
            if (p.gyro(0, 0, w * DEG, t, out)) x += out[0];
            if (i < 140) maxX = x;
            t += 5_000_000L;
        }
        check(x >= maxX - 1, "防抖半径：掉头不到两倍绳长，光标不往回走（停在 " + maxX + "，最后 " + x + "）");
    }

    /** 往右 20°/s 扫 2 秒，同时上下 3Hz ±6°/s 晃；返回 {横向总量, 后 1 秒上下晃的峰峰值}（200Hz）。 */
    static int[] wobble(float pathMs, float stringDeg) {
        AirPointer.Config c = cfg0();
        c.pathSmoothMs = pathMs;
        c.stringDeg = stringDeg;
        AirPointer p = new AirPointer(c);
        p.gravity(0, 0, G);
        int[] out = new int[2];
        long t = 1_000_000_000L;
        int x = 0, y = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            boolean on = i < 400;
            float wz = on ? -20 * DEG : 0;
            float wx = on ? (float) (6 * Math.sin(2 * Math.PI * 3 * i / 200.0)) * DEG : 0;
            if (p.gyro(wx, 0, wz, t, out)) {
                x += out[0];
                y += out[1];
            }
            if (i >= 200 && i < 400) {   // 后 1 秒（起步的过渡过去了）：上下晃的峰峰值
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
            t += 5_000_000L;
        }
        return new int[]{x, hi - lo};
    }

    // ═══════════ 绝对坐标描述符 ═══════════

    static void hidAbsoluteDescriptor() {
        byte[] old = Hid.combinedDescriptor();
        byte[] plain = Hid.combinedDescriptor(false);
        byte[] abs = Hid.combinedDescriptor(true);
        check(Arrays.equals(old, plain), "不开实验时描述符和原来一模一样");
        check(Arrays.equals(Arrays.copyOf(abs, old.length), old), "开实验时前面 1/2/3 不变");
        byte[] tail = Arrays.copyOfRange(abs, old.length, abs.length);
        check(Arrays.equals(tail, Hid.withReportId(Hid.ABS_POINTER_DESC, Hid.REPORT_ABS)), "绝对指针追加在最后、报告 ID 4");

        // 迷你解析：集合开闭配平，Input 位数 = 报文长度
        int depth = 0, bits = 0, size = 0, count = 0;
        boolean absInput = false;
        for (int i = 0; i < tail.length; ) {
            int b = tail[i] & 0xFF, len = b & 3;
            if (len == 3) len = 4;
            int val = 0;
            for (int k = 0; k < len; k++) val |= (tail[i + 1 + k] & 0xFF) << (8 * k);
            int tag = b & 0xFC;
            if (tag == 0xA0) depth++;
            if (tag == 0xC0) depth--;
            if (tag == 0x74) size = val;
            if (tag == 0x94) count = val;
            if (tag == 0x80) {
                bits += size * count;
                if (size == 16 && (val & 0x04) == 0) absInput = true;
            }
            i += 1 + len;
        }
        eq(depth, 0, "绝对指针的集合开闭配平");
        eq(bits, Hid.ABS_REPORT_SIZE * 8, "绝对指针 Input 位数 = 5 字节（和旧版带按键的排布一样）");
        check(absInput, "X/Y 是绝对值");

        byte[] r = new byte[Hid.ABS_REPORT_SIZE];
        Hid.absReport(r, 1, Hid.ABS_CENTER, Hid.ABS_CENTER);
        eq(Arrays.toString(r), "[1, 0, 64, 0, 64]", "绝对报文：按键字节 + 小端 16 位");
        Hid.absReport(r, 0, -5, 40000);
        eq(((r[1] & 0xFF) | (r[2] & 0xFF) << 8) + "/" + ((r[3] & 0xFF) | (r[4] & 0xFF) << 8), "0/32767", "绝对报文：越界截断");
    }

    // ═══════════ 工具 ═══════════

    static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static int count(List<String> log, String entry) {
        int n = 0;
        for (String s : log) if (s.equals(entry)) n++;
        return n;
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!actual.equals(expected)) {
            failures++;
            System.err.println("✗ " + what + "：期望 " + expected + "，实际 " + actual);
        }
    }
}
