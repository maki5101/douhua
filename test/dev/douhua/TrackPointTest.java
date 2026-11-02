package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * TrackPoint（小红点）的离线测试：速度曲线（死区、单调、平台、封顶、方案里算的几个点、灵敏度）、按节拍积分（8ms / 12ms / 抖动的节拍
 * 总量对、余数不丢、一拍最多算 50ms）、负惯性（起步更快、按住不动时一样快、往回收手指时不反向）、轻点 = 左键（快、近才算，关掉 / 取消不算，
 * 轻点过程中不出位移）、颗粒（每 30 计数一颗、最短间隔、按着键 / 滚动 / 关掉时不发）、中键滚动（只出滚轮、方向、主方向、按格 / 连续、咔嗒）、
 * 停（抬起 / 取消后迟到的节拍什么都不出）、兜底暂停（20 秒没事件停、再动接着）；
 * 中键滚动杆模式（一直是滚动、和红点的中键滚动出的量一样、先挪出轻点容差才开始、开始以后挪回死区停 / 再推接着、不算轻点、不发颗粒、按格 / 连续、停、暂停）；
 * 左键杆模式（一直是移动、离按下点容差 + m 出的量和红点推 m 一模一样（负惯性开 / 关、8 / 12ms 节拍、灵敏度）、先挪出轻点容差才开始、
 * 位移从容差那一圈起算、挪回来停 / 再推接着、不算轻点、不发颗粒、不滚、停、暂停）；
 * 推光标的加速、最快速度（协议 23：默认值时曲线逐位和改之前一样、一串随机序列出的东西和改之前的代码算的校验和一样；加速 0% 平台以后是平的、
 * 最快速度不起作用；滑块每一格速度随位移 / 两个滑块不减；积分、灵敏度照旧；左键杆用的也是新参数；中键滚动不跟；收到坏数截住；设置页说明里现算的倍数、秒数）。
 * 数字只证明代码照设计跑，证明不了手感。
 */
public final class TrackPointTest {
    private static int failures;
    private static int checks;

    static final class Rec implements TrackPoint.Output {
        int dx, dy, wheel, pan, moves, scrolls, clicks;
        final List<Integer> haptics = new ArrayList<>();
        final List<int[]> scrollLog = new ArrayList<>();
        boolean reversed;

        @Override
        public void move(int x, int y) {
            dx += x;
            dy += y;
            moves++;
            if (x < 0) reversed = true;   // 测试里一律往右推
        }

        @Override
        public void scroll(int w, int p) {
            wheel += w;
            pan += p;
            scrolls++;
            scrollLog.add(new int[]{w, p});
        }

        @Override
        public void click() {
            clicks++;
        }

        @Override
        public void haptic(int kind) {
            haptics.add(kind);
        }

        int count(int kind) {
            int n = 0;
            for (int k : haptics) if (k == kind) n++;
            return n;
        }
    }

    public static void main(String[] args) {
        curve();
        integrate();
        jitteredTicks();
        longGapClamped();
        sensitivity();
        inertiaFaster();
        inertiaNoReverse();
        tap();
        tapNoMove();
        texture();
        scrollMode();
        scrollSmooth();
        stops();
        idlePause();
        leverSlowerThanStick();
        leverGate();
        leverNoTapNoTexture();
        leverModes();
        leverStopsAndPauses();
        dragLeverSameAsStick();
        dragLeverGate();
        dragLeverRollTap();
        dragLeverNoTapNoTextureNoScroll();
        dragLeverStopsAndPauses();
        accelDefaultsUnchanged();
        accelGolden();
        accelZeroFlat();
        accelMonotone();
        accelIntegrate();
        accelSensitivity();
        accelDragLever();
        accelLeavesScroll();
        accelClamp();
        accelHints();
        accelHintTexts();
        System.err.println("小红点：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static GestureEngine.Config cfg(boolean inertia) {
        GestureEngine.Config c = new GestureEngine.Config();
        c.stickInertia = inertia;
        return c;
    }

    static TrackPoint stick(Rec r, GestureEngine.Config c) {
        TrackPoint t = new TrackPoint(r);
        t.setConfig(c);
        return t;
    }

    // ── 速度曲线 ──
    static void curve() {
        check(TrackPoint.moveSpeed(0) == 0 && TrackPoint.moveSpeed(TrackPoint.DEAD_DP) == 0, "死区里 0");
        float prev = 0;
        boolean mono = true;
        for (float m = 0; m <= 40; m += 0.1f) {
            float v = TrackPoint.moveSpeed(m);
            mono &= v >= prev - 1e-3f;
            prev = v;
        }
        check(mono, "单调不减");
        float plateauLo = TrackPoint.DEAD_DP + 0.46f * (TrackPoint.FULL_DP - TrackPoint.DEAD_DP);
        float plateauHi = TrackPoint.DEAD_DP + 0.69f * (TrackPoint.FULL_DP - TrackPoint.DEAD_DP);
        check(TrackPoint.moveSpeed(plateauLo) == TrackPoint.MOVE_PLATEAU && TrackPoint.moveSpeed(plateauHi) == TrackPoint.MOVE_PLATEAU,
                "平台那段速度不变（" + plateauLo + "–" + plateauHi + "dp）");
        check(TrackPoint.moveSpeed(32) == TrackPoint.MOVE_MAX && TrackPoint.moveSpeed(80) == TrackPoint.MOVE_MAX, "推到底封顶");
        check(TrackPoint.moveSpeed(3) == TrackPoint.MOVE_CRAWL, "刚出死区：爬行");
        // 方案里的模型算的点：10dp ≈ 154、15dp ≈ 441；25dp 原来 ≈ 738（最快 3000），2026-11-01 最快降到 1200 后 ≈ 535
        near(TrackPoint.moveSpeed(10), 154, 1, "10dp");
        near(TrackPoint.moveSpeed(15), 441, 1, "15dp");
        near(TrackPoint.moveSpeed(25), 535, 1, "25dp");
    }

    // ── 积分：恒定位移、不开负惯性，1 秒的总计数 ≈ 速度 ──
    static void integrate() {
        for (int period : new int[]{8, 12}) {
            Rec r = new Rec();
            TrackPoint t = stick(r, cfg(false));
            t.down(100, 100, 0);
            t.move(115, 100);
            t.frame(0);
            long now = 0;
            while (now < 1000) {
                now += period;
                t.tick(now, false, false, false);
            }
            float want = TrackPoint.moveSpeed(15) * now / 1000f;
            check(Math.abs(r.dx - want) < 1 && r.dy == 0, period + "ms 一拍：1 秒走 " + r.dx + "（应 " + want + "）");
            check(!r.reversed, period + "ms：只往右");
        }
        // 斜着推：两个方向按比例分，合起来的速度不变
        Rec r = new Rec();
        TrackPoint t = stick(r, cfg(false));
        t.down(0, 0, 0);
        t.move(9, 12);   // 15dp，方向 (0.6, 0.8)
        t.frame(0);
        for (long now = 8; now <= 1000; now += 8) t.tick(now, false, false, false);
        float v = TrackPoint.moveSpeed(15);
        check(Math.abs(r.dx - 0.6f * v) < 1.5f && Math.abs(r.dy - 0.8f * v) < 1.5f, "斜着推按方向分：" + r.dx + "," + r.dy);
    }

    static void jitteredTicks() {
        Rec r = new Rec();
        TrackPoint t = stick(r, cfg(false));
        t.down(0, 0, 0);
        t.move(15, 0);
        t.frame(0);
        long now = 0;
        int[] gaps = {7, 9, 13};
        for (int i = 0; now < 997; i++) {
            now += gaps[i % 3];
            t.tick(now, false, false, false);
        }
        float want = TrackPoint.moveSpeed(15) * now / 1000f;
        check(Math.abs(r.dx - want) < 1, "节拍 7 / 9 / 13ms 抖：总量不变 " + r.dx + "（应 " + want + "）");
        // 同一时刻（或者时间倒着）来的节拍不出东西
        int before = r.dx;
        t.tick(now, false, false, false);
        t.tick(now - 5, false, false, false);
        check(r.dx == before, "dt ≤ 0 的节拍不出位移");
    }

    static void longGapClamped() {
        Rec r = new Rec();
        TrackPoint t = stick(r, cfg(false));
        t.down(0, 0, 0);
        t.move(15, 0);
        t.frame(0);
        t.tick(200, false, false, false);   // 卡了 200ms
        float want = TrackPoint.moveSpeed(15) * TrackPoint.MAX_DT_MS / 1000f;
        check(Math.abs(r.dx - want) < 1, "一拍隔 200ms 按 50ms 算：" + r.dx + "（应 " + want + "）");
    }

    static void sensitivity() {
        int[] got = new int[2];
        float[] speeds = {1, 2};
        for (int i = 0; i < 2; i++) {
            Rec r = new Rec();
            GestureEngine.Config c = cfg(false);
            c.stickSpeed = speeds[i];
            TrackPoint t = stick(r, c);
            t.down(0, 0, 0);
            t.move(15, 0);
            t.frame(0);
            for (long now = 8; now <= 1000; now += 8) t.tick(now, false, false, false);
            got[i] = r.dx;
        }
        check(Math.abs(got[1] - 2 * got[0]) <= 2, "灵敏度 2 倍走 2 倍：" + got[0] + " → " + got[1]);
    }

    /** 手指 120ms 里从 0 推到 15dp（120Hz 的触摸帧），之后按住不动；按 8ms 节拍跑到 t，返回走了多少。 */
    static int ramp(boolean inertia, long until, Rec r) {
        TrackPoint t = stick(r, cfg(inertia));
        t.down(0, 0, 0);
        double touch = 0;
        for (long now = 8; now <= until; now += 8) {
            while (touch <= now) {
                float m = (float) (15 * Math.min(1, touch / 120));
                t.move(m, 0);
                t.frame((long) touch);
                touch += 1000 / 120.0;
            }
            t.tick(now, false, false, false);
        }
        return r.dx;
    }

    static void inertiaFaster() {
        int on = ramp(true, 160, new Rec()), off = ramp(false, 160, new Rec());
        check(on > off, "负惯性：起步更快（160ms 走了 " + on + " vs 不开 " + off + "）");
        // 按住不动之后速度一样：600ms 到 1600ms 这一秒走的差不多
        int on1 = ramp(true, 600, new Rec()), on2 = ramp(true, 1600, new Rec());
        int off1 = ramp(false, 600, new Rec()), off2 = ramp(false, 1600, new Rec());
        check(Math.abs((on2 - on1) - (off2 - off1)) <= 2, "按住不动时开 / 关一样快：" + (on2 - on1) + " vs " + (off2 - off1));
    }

    static void inertiaNoReverse() {
        Rec r = new Rec();
        TrackPoint t = stick(r, cfg(true));
        t.down(0, 0, 0);
        t.move(20, 0);
        t.frame(0);
        for (long now = 8; now <= 300; now += 8) t.tick(now, false, false, false);
        // 很快往回收：20dp → 3dp（还在死区外、同一个方向），负惯性把 z 压到 0 以下也夹在 0，不往回走
        int before = r.dx;
        t.move(3, 0);
        t.frame(300);
        int stalled = 0;
        for (long now = 308; now <= 400; now += 8) {
            int d = r.dx;
            t.tick(now, false, false, false);
            if (r.dx == d) stalled++;
        }
        check(!r.reversed, "往回收手指：不反向");
        check(stalled > 0, "往回收手指：负惯性让它先停下（" + stalled + " 拍没动）");
        check(r.dx >= before, "往回收手指后也只往前走");
    }

    /** 打开"轻点 = 左键"的配置（2026-11-01 起默认关）。 */
    static GestureEngine.Config tapOn() {
        GestureEngine.Config c = cfg(true);
        c.stickTap = true;
        return c;
    }

    // ── 轻点 = 左键 ──
    static void tap() {
        Rec r = new Rec();
        TrackPoint t = stick(r, tapOn());
        t.down(0, 0, 1000);
        t.move(1, 1);
        t.frame(1050);
        t.up(1150, false);
        check(r.clicks == 1 && r.count(GestureEngine.HAPTIC_CLICK) == 1, "快、近：点一下左键，嗒一下");

        r = new Rec();
        t = stick(r, tapOn());
        t.down(0, 0, 0);
        t.move(7, 0);   // 超过 tapSlopDp
        t.frame(50);
        t.up(100, false);
        check(r.clicks == 0, "挪远了：不算轻点");

        r = new Rec();
        t = stick(r, tapOn());
        t.down(0, 0, 0);
        t.up(300, false);
        check(r.clicks == 0, "按太久：不算轻点");

        r = new Rec();
        GestureEngine.Config c = cfg(true);
        c.stickTap = false;
        t = stick(r, c);
        t.down(0, 0, 0);
        t.up(100, false);
        check(r.clicks == 0 && r.haptics.isEmpty(), "关掉轻点 = 左键：不点");

        r = new Rec();
        t = stick(r, tapOn());
        t.down(0, 0, 0);
        t.up(100, true);
        check(r.clicks == 0, "被取消的（手掌）：不点");

        r = new Rec();
        t = stick(r, cfg(true));   // 默认：关
        t.down(0, 0, 0);
        t.up(100, false);
        check(r.clicks == 0 && r.haptics.isEmpty(), "默认（关）：轻点不点");

        r = new Rec();
        t = stick(r, cfg(true));
        t.down(0, 0, 0);
        check(r.haptics.isEmpty(), "按下不震");
    }

    static void tapNoMove() {
        Rec r = new Rec();
        TrackPoint t = stick(r, tapOn());
        t.down(0, 0, 0);
        for (long now = 8; now <= 200; now += 8) {
            t.move((now % 16 == 0) ? 2 : 1.2f, 0.5f);   // 死区里抖
            t.frame(now);
            t.tick(now, false, false, false);
        }
        t.up(210, false);
        check(r.moves == 0 && r.clicks == 1, "死区里抖着轻点：一点都不挪光标，照样点一下");
    }

    // ── 颗粒 ──
    static int textures(boolean texture, boolean buttons, boolean scroll) {
        Rec r = new Rec();
        GestureEngine.Config c = cfg(false);
        c.texture = texture;
        TrackPoint t = stick(r, c);
        t.down(0, 0, 0);
        t.move(15, 0);
        t.frame(0);
        for (long now = 8; now <= 1000; now += 8) t.tick(now, scroll, buttons, false);
        return r.count(GestureEngine.HAPTIC_TEXTURE);
    }

    static void texture() {
        int n = textures(true, false, false);
        int want = (int) (TrackPoint.moveSpeed(15) / TrackPoint.TEXTURE_COUNTS);
        check(Math.abs(n - want) <= 1, "每 30 计数一颗：1 秒 " + n + " 颗（约 " + want + "）");
        check(textures(true, true, false) == 0, "按着鼠标键（拖动）：不发颗粒");
        check(textures(true, false, true) == 0, "滚动模式：不发颗粒");
        check(textures(false, false, false) == 0, "颗粒关着：不发");
        // 推到底很快：颗粒最密也隔 textureMinGapMs
        Rec r = new Rec();
        GestureEngine.Config c = cfg(false);
        c.texture = true;
        TrackPoint t = stick(r, c);
        t.down(0, 0, 0);
        t.move(40, 0);
        t.frame(0);
        for (long now = 8; now <= 1000; now += 8) t.tick(now, false, false, false);
        int fast = r.count(GestureEngine.HAPTIC_TEXTURE);
        check(fast <= 1000 / c.textureMinGapMs + 1 && fast > 0, "推到底：颗粒最密 " + fast + " 颗 / 秒（间隔 ≥ " + c.textureMinGapMs + "ms）");
    }

    // ── 中键滚动 ──
    static Rec scroll(float dx, float dy, boolean smooth, boolean ticks) {
        Rec r = new Rec();
        GestureEngine.Config c = cfg(false);
        c.scrollTicks = ticks ? GestureEngine.TICKS_ALL : GestureEngine.TICKS_OFF;
        TrackPoint t = stick(r, c);
        t.down(0, 0, 0);
        t.move(dx, dy);
        t.frame(0);
        for (long now = 8; now <= 1000; now += 8) t.tick(now, true, false, smooth);
        return r;
    }

    static void scrollMode() {
        Rec r = scroll(0, 15, false, true);
        check(r.moves == 0, "滚动模式：不出位移");
        float notches = TrackPoint.curve(TrackPoint.level(15), TrackPoint.SCROLL_CRAWL, TrackPoint.SCROLL_PLATEAU, TrackPoint.SCROLL_MAX);
        check(r.wheel < 0 && r.pan == 0, "往下推 = 往下翻（滚轮负）：" + r.wheel);
        check(Math.abs(-r.wheel / (float) Hid.WHEEL_UNIT - notches) <= 1, "1 秒滚了 " + (-r.wheel / Hid.WHEEL_UNIT) + " 格（应约 " + notches + "）");
        boolean whole = true;
        for (int[] s : r.scrollLog) whole &= s[0] % Hid.WHEEL_UNIT == 0 && s[1] % Hid.WHEEL_UNIT == 0;
        check(whole, "按格模式：每次整格");
        check(r.count(GestureEngine.HAPTIC_TICK) == r.scrolls && r.scrolls > 0, "按格：每格咔嗒一下");
        check(scroll(0, 15, false, false).haptics.isEmpty(), "咔嗒关着：不震");
        Rec up = scroll(0, -15, false, true);
        check(up.wheel > 0, "往上推 = 往上翻");
        Rec right = scroll(15, 0, false, true);
        check(right.pan > 0 && right.wheel == 0, "往右推 = 往右翻（横向正）");
        Rec mostlyDown = scroll(5, 14, false, true);   // 竖向占 2 倍以上：只滚竖向
        check(mostlyDown.pan == 0 && mostlyDown.wheel < 0, "竖着为主：只滚竖向");
        Rec diag = scroll(10, 11, true, true);   // 斜着：两个方向都滚
        check(diag.pan > 0 && diag.wheel < 0, "斜着推：两个方向都滚");
    }

    static void scrollSmooth() {
        Rec r = scroll(0, 15, true, true);
        boolean partial = false;
        for (int[] s : r.scrollLog) partial |= s[0] % Hid.WHEEL_UNIT != 0;
        check(partial, "连续模式：出小数格");
        int units = -r.wheel;
        check(Math.abs(r.count(GestureEngine.HAPTIC_TICK) - units / Hid.WHEEL_UNIT) <= 1,
                "连续模式：每滚过一格的距离咔嗒一下（" + r.count(GestureEngine.HAPTIC_TICK) + " 下，" + units + " 单位）");
        // 模式切换：先移动再按中键，再松开，余数不串
        Rec m = new Rec();
        TrackPoint t = stick(m, cfg(false));
        t.down(0, 0, 0);
        t.move(15, 0);
        t.frame(0);
        for (long now = 8; now <= 200; now += 8) t.tick(now, false, false, false);
        int moved = m.dx;
        for (long now = 208; now <= 400; now += 8) t.tick(now, true, false, false);
        check(m.dx == moved && m.pan > 0, "按下中键：从下一拍起变成滚动");
        int panned = m.pan;
        for (long now = 408; now <= 600; now += 8) t.tick(now, false, false, false);
        check(m.pan == panned && m.dx > moved, "松开中键：回到移动");
    }

    // ── 停 ──
    static void stops() {
        Rec r = new Rec();
        TrackPoint t = stick(r, cfg(true));
        t.down(0, 0, 0);
        t.move(20, 0);
        t.frame(0);
        t.tick(8, false, false, false);
        t.up(300, false);
        int dx = r.dx;
        check(!t.tick(316, false, false, false) && r.dx == dx, "抬起后迟到的节拍：不出东西，返回 false");
        t.down(0, 0, 400);
        t.move(20, 0);
        t.frame(400);
        t.cancel();
        check(!t.tick(500, false, false, false) && r.dx == dx && !t.active(), "取消后：不出东西");
    }

    static void idlePause() {
        Rec r = new Rec();
        TrackPoint t = stick(r, cfg(false));
        t.down(0, 0, 0);
        t.move(15, 0);
        t.frame(0);
        long now = 0;
        boolean going = true;
        while (going && now < 30000) {
            now += 8;
            going = t.tick(now, false, false, false);
        }
        check(!going && now > TrackPoint.IDLE_PAUSE_MS && now < TrackPoint.IDLE_PAUSE_MS + 20, "20 秒没事件：暂停（" + now + "ms）");
        int dx = r.dx;
        check(!t.tick(now + 8, false, false, false) && r.dx == dx, "暂停中不出东西");
        check(!t.frame(now + 10), "没动过的帧不算");
        t.move(16, 0);
        check(t.frame(now + 20), "再动：接着（宿主重新开节拍）");
        check(t.tick(now + 28, false, false, false) && r.dx > dx, "接着走，暂停的那一段不补（" + (r.dx - dx) + "）");
        check(r.dx - dx <= 5, "暂停那一段不补：只走了 8ms 的量");
    }

    // ── 中键滚动杆 ──

    static TrackPoint lever(Rec r, GestureEngine.Config c) {
        TrackPoint t = new TrackPoint(r, TrackPoint.MODE_SCROLL_LEVER);
        t.setConfig(c);
        return t;
    }

    /** 滚动杆按在 (0, 0)、挪到 (dx, dy)，8ms 一拍跑 1 秒；scroll 参数故意给 false（滚动杆不看它）。 */
    static Rec leverRun(GestureEngine.Config c, float dx, float dy, boolean smooth) {
        Rec r = new Rec();
        TrackPoint t = lever(r, c);
        t.down(0, 0, 0);
        t.move(dx, dy);
        t.frame(0);
        for (long now = 8; now <= 1000; now += 8) t.tick(now, false, false, smooth);
        return r;
    }

    /**
     * 设置里"中键滑动的滚动速度"（Config.leverSpeed，协议 22，2026-11-01 晚）：乘到滚动杆的速率上（25% 慢四倍、200% 快一倍），
     * 按住中键推红点（红点那一个、scroll = true）不跟它变；默认 100% 是 1 / 5 / 20 格每秒（比以前的 2 / 10 / 40 慢一半）。
     */
    static void leverSpeedSetting() {
        check(TrackPoint.LEVER_CRAWL == 1 && TrackPoint.LEVER_PLATEAU == 5 && TrackPoint.LEVER_MAX == 20, "滚动杆默认 1 / 5 / 20 格每秒（再慢一半）");
        for (boolean smooth : new boolean[]{false, true}) for (boolean inertia : new boolean[]{false, true}) {
            String what = "（负惯性" + (inertia ? "开" : "关") + "、" + (smooth ? "连续" : "按格") + "）";
            GestureEngine.Config base = cfg(inertia);
            Rec one = leverRun(base, 0, 25, smooth);
            for (float k : new float[]{0.25f, 0.5f, 1.5f, 2f}) {
                GestureEngine.Config c = cfg(inertia);
                c.leverSpeed = k;
                Rec r = leverRun(c, 0, 25, smooth);
                check(Math.abs(r.wheel - k * one.wheel) <= Hid.WHEEL_UNIT, "滚动杆 × " + k + "：" + one.wheel + " → " + r.wheel + what);
                // 按住中键推红点：不跟它变
                Rec a = new Rec(), b = new Rec();
                TrackPoint ta = stick(a, base), tb = stick(b, c);
                for (TrackPoint t : new TrackPoint[]{ta, tb}) {
                    t.down(0, 0, 0);
                    t.move(0, 25);
                    t.frame(0);
                }
                for (long now = 8; now <= 1000; now += 8) {
                    ta.tick(now, true, false, smooth);
                    tb.tick(now, true, false, smooth);
                }
                check(a.wheel == b.wheel && a.wheel != 0, "按住中键推红点不跟中键滑动速度变（× " + k + "）：" + a.wheel + " / " + b.wheel + what);
            }
        }
        // 100% 的量照 leverRate（设置的倍数不进 leverRate）
        GestureEngine.Config c = cfg(false);
        Rec r = leverRun(c, 0, 25, false);
        check(Math.abs(-r.wheel / (float) Hid.WHEEL_UNIT - TrackPoint.leverRate(25, c.tapSlopDp)) <= 1, "100%：1 秒滚的格数照 leverRate（" + (-r.wheel / Hid.WHEEL_UNIT) + "）");
    }

    /** 滚动杆比"按着中键推红点"慢：自己一条曲线（LEVER_*），位移从挪出轻点容差那一圈起算（用户 2026-11-01 嫌滚动杆太快）。 */
    static void leverSlowerThanStick() {
        for (boolean inertia : new boolean[]{false, true}) {
            for (boolean smooth : new boolean[]{false, true}) {
                GestureEngine.Config c = cfg(inertia);
                Rec lv = leverRun(c, 0, 15, smooth);
                // 红点按着中键往下推同样远
                Rec st = new Rec();
                TrackPoint t = stick(st, c);
                t.down(0, 0, 0);
                t.move(0, 15);
                t.frame(0);
                for (long now = 8; now <= 1000; now += 8) t.tick(now, true, false, smooth);
                String what = "（负惯性" + (inertia ? "开" : "关") + "、" + (smooth ? "连续" : "按格") + "）";
                check(lv.wheel < 0 && lv.wheel > st.wheel && lv.pan == 0, "滚动杆往下推 15dp 比按着中键推红点慢：" + lv.wheel + " vs " + st.wheel + what);
                check(lv.moves == 0, "滚动杆不出位移" + what);
            }
        }
        GestureEngine.Config c = cfg(false);
        float notches = TrackPoint.leverRate(15, c.tapSlopDp);
        Rec down = leverRun(c, 0, 15, false);
        check(Math.abs(-down.wheel / (float) Hid.WHEEL_UNIT - notches) <= 1, "滚动杆 1 秒滚了 " + (-down.wheel / Hid.WHEEL_UNIT) + " 格（应约 " + notches + "）");
        check(TrackPoint.leverRate(c.tapSlopDp + TrackPoint.DEAD_DP, c.tapSlopDp) == 0, "刚挪出轻点容差：还在死区里，从 0 起");
        Rec start = leverRun(c, 0, c.tapSlopDp + 2, false);
        check(start.wheel == 0, "挪出容差 2dp：1 秒一格都不滚（以前一下就在曲线中段）");
        check(TrackPoint.leverRate(20, c.tapSlopDp) < TrackPoint.curve(TrackPoint.level(20), TrackPoint.SCROLL_CRAWL, TrackPoint.SCROLL_PLATEAU, TrackPoint.SCROLL_MAX) / 2,
                "挪 20dp：不到按着中键推红点的一半（" + TrackPoint.leverRate(20, c.tapSlopDp) + " 格 / 秒）");
        Rec up = leverRun(c, 0, -15, false);
        check(up.wheel > 0 && up.pan == 0, "滚动杆往上推 = 往上翻");
        Rec far = leverRun(c, 0, 60, false);
        int farN = -far.wheel / Hid.WHEEL_UNIT;
        check(farN >= TrackPoint.LEVER_MAX - 1 && farN <= TrackPoint.LEVER_MAX + 1, "推到底封顶在滚动杆的最快：" + farN + " 格 / 秒");
        Rec mostly = leverRun(cfg(false), 5, 14, false);
        check(mostly.pan == 0 && mostly.wheel < 0, "滚动杆竖着为主：只滚竖向");
        Rec right = leverRun(cfg(false), 15, 0, false);
        check(right.pan > 0 && right.wheel == 0, "滚动杆往右推 = 往右翻");
        GestureEngine.Config fast = cfg(false);
        fast.stickSpeed = 2;
        Rec twice = leverRun(fast, 0, 15, true);
        Rec once = leverRun(cfg(false), 0, 15, true);
        check(Math.abs(twice.wheel - 2 * once.wheel) <= 2, "滚动杆跟灵敏度：" + once.wheel + " → " + twice.wheel);
        leverSpeedSetting();
    }

    /** 先挪出轻点容差才开始；位移从容差那一圈起算：离按下点容差 + 死区以内不滚，推出去照曲线滚，挪回来就停。 */
    static void leverGate() {
        GestureEngine.Config c = cfg(false);
        Rec r = new Rec();
        TrackPoint t = lever(r, c);
        t.down(0, 0, 0);
        float inside = (TrackPoint.DEAD_DP + c.tapSlopDp) / 2;   // 出了死区、没出轻点容差
        t.move(0, inside);
        t.frame(0);
        long now = 0;
        for (; now < 1000; ) {
            now += 8;
            t.tick(now, true, false, true);
        }
        check(r.scrolls == 0 && !t.started(), "挪 " + inside + "dp（死区外、轻点容差里）按 1 秒：不滚、没开始");
        t.move(0, c.tapSlopDp + 1);
        t.frame(now);
        check(t.started(), "挪出轻点容差：开始");
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, true, false, true);
        }
        check(r.scrolls == 0, "刚挪出容差（位移从容差那一圈起算，还在死区里）：开始了但还不滚");
        float out = c.tapSlopDp + TrackPoint.DEAD_DP + 3;
        t.move(0, out);
        t.frame(now);
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, true, false, true);
        }
        check(r.wheel < 0, "推出容差 + 死区：往下滚：" + r.wheel);
        t.move(0, TrackPoint.DEAD_DP - 0.5f);   // 挪回死区
        t.frame(now);
        int w = r.wheel;
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, true, false, true);
        }
        check(r.wheel == w, "挪回死区：停");
        t.move(0, inside);   // 推到容差里：滚动杆的位移从容差那一圈起算，这里还是 0
        t.frame(now);
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, true, false, true);
        }
        check(r.wheel == w, "推回容差以内：不滚（滚动杆离按下点容差 + 死区以内都不滚）");
        t.move(0, out);
        t.frame(now);
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, true, false, true);
        }
        check(r.wheel < w && t.started(), "再推出容差 + 死区：接着滚");
        t.up(now, false);
        check(!t.started(), "抬起后不算开始了");
        // 红点没有这道门槛：死区外就走
        Rec s = new Rec();
        TrackPoint st = stick(s, c);
        st.down(0, 0, 0);
        st.move(inside, 0);
        st.frame(0);
        for (long x = 8; x <= 500; x += 8) st.tick(x, false, false, false);
        check(s.dx > 0 && st.started(), "红点没有起步门槛");
    }

    static void leverNoTapNoTexture() {
        GestureEngine.Config c = tapOn();
        c.texture = true;
        Rec r = new Rec();
        TrackPoint t = lever(r, c);
        t.down(0, 0, 0);
        t.move(1, 1);
        t.frame(50);
        t.tick(58, true, false, false);
        t.up(100, false);
        check(r.clicks == 0 && r.haptics.isEmpty() && r.moves == 0, "滚动杆轻点（开着轻点 = 左键）：不点左键、不震");
        Rec p = leverRun(c, 15, 0, false);
        check(p.count(GestureEngine.HAPTIC_TEXTURE) == 0 && p.moves == 0 && p.pan > 0, "滚动杆推着：不发颗粒、只滚");
    }

    static void leverModes() {
        Rec notch = leverRun(cfg(false), 0, 15, false);
        boolean whole = true;
        for (int[] x : notch.scrollLog) whole &= x[0] % Hid.WHEEL_UNIT == 0 && x[1] % Hid.WHEEL_UNIT == 0;
        check(whole && notch.scrolls > 0, "滚动杆按格模式：每次整格");
        check(notch.count(GestureEngine.HAPTIC_TICK) == notch.scrolls, "滚动杆按格：每格咔嗒一下");
        Rec smooth = leverRun(cfg(false), 0, 15, true);
        boolean partial = false;
        for (int[] x : smooth.scrollLog) partial |= x[0] % Hid.WHEEL_UNIT != 0;
        check(partial, "滚动杆连续模式：出小数格");
        check(Math.abs(smooth.count(GestureEngine.HAPTIC_TICK) - (-smooth.wheel) / Hid.WHEEL_UNIT) <= 1,
                "滚动杆连续模式：每滚过一格的距离咔嗒一下（" + smooth.count(GestureEngine.HAPTIC_TICK) + " 下，" + (-smooth.wheel) + " 单位）");
        GestureEngine.Config quiet = cfg(false);
        quiet.scrollTicks = GestureEngine.TICKS_OFF;
        check(leverRun(quiet, 0, 15, false).haptics.isEmpty(), "咔嗒关着：滚动杆不震");
    }

    static void leverStopsAndPauses() {
        Rec r = new Rec();
        TrackPoint t = lever(r, cfg(false));
        t.down(0, 0, 0);
        t.move(0, 20);
        t.frame(0);
        t.tick(8, true, false, false);
        t.up(300, false);
        int w = r.wheel;
        check(!t.tick(316, true, false, false) && r.wheel == w, "滚动杆抬起后迟到的节拍：不出东西，返回 false");
        t.down(0, 0, 400);
        t.move(0, 20);
        t.frame(400);
        t.cancel();
        check(!t.tick(500, true, false, false) && r.wheel == w && !t.active() && !t.started(), "滚动杆取消后：不出东西");
        Rec p = new Rec();
        TrackPoint q = lever(p, cfg(false));
        q.down(0, 0, 0);
        q.move(0, 15);
        q.frame(0);
        long now = 0;
        boolean going = true;
        while (going && now < 30000) {
            now += 8;
            going = q.tick(now, true, false, true);
        }
        check(!going && now > TrackPoint.IDLE_PAUSE_MS && now < TrackPoint.IDLE_PAUSE_MS + 20, "滚动杆 20 秒没事件：暂停（" + now + "ms）");
        int pw = p.wheel;
        check(!q.tick(now + 8, true, false, true) && p.wheel == pw, "滚动杆暂停中不出东西");
        q.move(0, 16);
        check(q.frame(now + 20), "滚动杆再动：接着");
        check(q.tick(now + 28, true, false, true) && p.wheel < pw, "滚动杆接着滚");
    }

    // ── 左键杆 ──

    static TrackPoint dragLever(Rec r, GestureEngine.Config c) {
        TrackPoint t = new TrackPoint(r, TrackPoint.MODE_DRAG_LEVER);
        t.setConfig(c);
        return t;
    }

    /**
     * 左键杆离按下点（容差 + m）、红点推 m，同样的节拍：出的位移一模一样（顺着轴推逐计数相等，斜着推差不过 1 个计数）——
     * 左键杆就是"挪出轻点容差以后的小红点"。scroll / buttons 参数故意给 true / false（左键杆不看：一直移动、一直当按着键）。
     */
    static void dragLeverSameAsStick() {
        float[][] dirs = {{1, 0}, {0, 1}, {0, -1}, {-0.6f, 0.8f}};
        float[] ms = {4, 10, 15, 25, 40};
        int cases = 0, bad = 0;
        for (boolean inertia : new boolean[]{false, true}) {
            for (int period : new int[]{8, 12}) {
                for (float speed : new float[]{1, 2.5f}) {
                    GestureEngine.Config c = cfg(inertia);
                    c.stickSpeed = speed;
                    for (float[] d : dirs) {
                        for (float m : ms) {
                            Rec a = new Rec(), b = new Rec();
                            TrackPoint st = stick(a, c), lv = dragLever(b, c);
                            float k = c.tapSlopDp + m;
                            st.down(0, 0, 0);
                            st.move(d[0] * m, d[1] * m);
                            st.frame(0);
                            lv.down(0, 0, 0);
                            lv.move(d[0] * k, d[1] * k);
                            lv.frame(0);
                            for (long now = period; now <= 1000; now += period) {
                                st.tick(now, false, false, false);
                                lv.tick(now, true, false, true);
                            }
                            cases++;
                            int tol = d[0] != 0 && d[1] != 0 ? 1 : 0;
                            if (Math.abs(a.dx - b.dx) > tol || Math.abs(a.dy - b.dy) > tol || b.scrolls != 0 || (m > TrackPoint.DEAD_DP && b.moves == 0)) {
                                bad++;
                                if (bad <= 3) System.err.println("  左键杆和红点不一样：负惯性 " + inertia + "、" + period + "ms、灵敏度 " + speed
                                        + "、方向 (" + d[0] + ", " + d[1] + ")、m " + m + "：红点 (" + a.dx + ", " + a.dy + ") 左键杆 (" + b.dx + ", " + b.dy + ")");
                            }
                        }
                    }
                }
            }
        }
        check(bad == 0, "左键杆离按下点（容差 + m）= 红点推 m：" + cases + " 种（负惯性 × 节拍 × 灵敏度 × 方向 × 位移）出的位移一样，不滚（错 " + bad + "）");
        GestureEngine.Config c = cfg(false);
        check(TrackPoint.dragLeverSpeed(c.tapSlopDp + TrackPoint.DEAD_DP, c.tapSlopDp) == 0, "刚挪出轻点容差：还在死区里，从 0 起");
        check(TrackPoint.dragLeverSpeed(c.tapSlopDp + 15, c.tapSlopDp) == TrackPoint.moveSpeed(15), "离按下点容差 + 15dp：照红点推 15dp 的速度");
        check(TrackPoint.dragLeverSpeed(80, c.tapSlopDp) == TrackPoint.MOVE_MAX, "推得很远：封顶在红点的最快");
        Rec far = new Rec();
        TrackPoint t = dragLever(far, c);
        t.down(0, 0, 0);
        t.move(200, 0);
        t.frame(0);
        for (long now = 8; now <= 1000; now += 8) t.tick(now, false, true, false);
        check(Math.abs(far.dx - TrackPoint.MOVE_MAX) <= 1 && far.dy == 0, "推到很远 1 秒：走 " + far.dx + "（封顶 " + TrackPoint.MOVE_MAX + "）");
    }

    /** 先挪出轻点容差才开始；位移从容差那一圈起算：离按下点容差 + 死区以内不动，推出去照曲线走，挪回来就停、再推接着。 */
    static void dragLeverGate() {
        GestureEngine.Config c = cfg(false);
        Rec r = new Rec();
        TrackPoint t = dragLever(r, c);
        t.down(0, 0, 0);
        float inside = (TrackPoint.DEAD_DP + c.tapSlopDp) / 2;   // 出了死区、没出轻点容差（红点在这里已经在走了）
        t.move(inside, 0);
        t.frame(0);
        long now = 0;
        for (; now < 1000; ) {
            now += 8;
            t.tick(now, false, true, false);
        }
        check(r.moves == 0 && !t.started() && t.active(), "挪 " + inside + "dp（死区外、轻点容差里）按 1 秒：不动、没开始");
        t.move(c.tapSlopDp + 1, 0);
        t.frame(now);
        check(t.started(), "挪出轻点容差：开始");
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, false, true, false);
        }
        check(r.moves == 0, "刚挪出容差（位移从容差那一圈起算，还在死区里）：开始了但还不动");
        float out = c.tapSlopDp + TrackPoint.DEAD_DP + 3;
        t.move(out, 0);
        t.frame(now);
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, false, true, false);
        }
        check(r.dx > 0 && r.dy == 0, "推出容差 + 死区：往右走：" + r.dx);
        int x = r.dx;
        t.move(inside, 0);   // 挪回容差以内：位移从容差那一圈起算，这里是 0
        t.frame(now);
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, false, true, false);
        }
        check(r.dx == x, "挪回容差以内：停（离按下点容差 + 死区以内都不动）");
        t.move(out, 0);
        t.frame(now);
        for (long end = now + 500; now < end; ) {
            now += 8;
            t.tick(now, false, true, false);
        }
        check(r.dx > x && t.started(), "再推出容差 + 死区：接着走（开始过了，不用再挪出容差一次）");
        t.move(0, -40);   // 往上推：方向照按下点量
        t.frame(now);
        int y0 = r.dy;
        for (long end = now + 300; now < end; ) {
            now += 8;
            t.tick(now, false, true, false);
        }
        check(r.dy < y0, "往上推：往上走");
        t.up(now, false);
        check(!t.started() && !t.active(), "抬起后不算开始了");
    }

    /**
     * 点左键时指肚一滚（8–40ms 里滚到离按下点容差 + 0.2 到容差 + 死区 − 0.1dp，按住 200ms 再松开），负惯性开（默认）/ 关、两种节拍、
     * 四个方向、灵敏度 1 / 3：一个计数都不出。挪出了容差、"开始"了，可位移从容差那一圈起算还在死区里——
     * 负惯性把起步放大（m + 3 × (m − 滞后)）也不越过死区出去（审查发现：死区那一行对左键杆不看的话最多出 2 个计数 = 一次微拖动，
     * 单击可能被主机当成拖动；dragLeverGate 用的是负惯性关，测不到）。
     */
    static void dragLeverRollTap() {
        int cases = 0, bad = 0, startedCases = 0;
        float[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0.6f, -0.8f}};
        for (boolean inertia : new boolean[]{true, false}) {
            for (int period : new int[]{8, 12}) {
                for (float speed : new float[]{1, 3}) {
                    GestureEngine.Config c = cfg(inertia);
                    c.stickSpeed = speed;
                    for (float[] d : dirs) {
                        for (float over = 0.2f; over < TrackPoint.DEAD_DP - 0.05f; over += 0.2f) {
                            for (int rampMs = 8; rampMs <= 40; rampMs += 8) {
                                Rec r = new Rec();
                                TrackPoint t = dragLever(r, c);
                                float roll = c.tapSlopDp + over;
                                t.down(0, 0, 0);
                                t.frame(0);
                                long now = 0;
                                boolean started = false;
                                while (now < 200) {
                                    now += period;
                                    float k = roll * Math.min(1f, now / (float) rampMs);
                                    t.move(d[0] * k, d[1] * k);
                                    t.frame(now);
                                    t.tick(now, false, true, false);
                                    started |= t.started();
                                }
                                t.up(now + 4, false);
                                cases++;
                                if (started) startedCases++;
                                if (r.moves != 0 || r.dx != 0 || r.dy != 0 || r.clicks != 0) {
                                    bad++;
                                    if (bad <= 3) System.err.println("  左键杆指肚一滚出了位移：负惯性 " + inertia + "、" + period + "ms、灵敏度 " + speed
                                            + "、方向 (" + d[0] + ", " + d[1] + ")、离按下点 " + roll + "dp、" + rampMs + "ms 滚到：(" + r.dx + ", " + r.dy + ")");
                                }
                            }
                        }
                    }
                }
            }
        }
        check(bad == 0 && startedCases == cases, "点左键时指肚一滚（挪出容差、还在容差 + 死区以内，" + cases
                + " 种：负惯性开 / 关 × 节拍 × 灵敏度 × 方向 × 滚多远 × 多快滚到）：都算开始了，一个计数都不出（错 " + bad + "）");
    }

    static void dragLeverNoTapNoTextureNoScroll() {
        GestureEngine.Config c = tapOn();
        c.texture = true;
        Rec r = new Rec();
        TrackPoint t = dragLever(r, c);
        t.down(0, 0, 0);
        t.move(1, 1);
        t.frame(50);
        t.tick(58, false, false, false);
        t.up(100, false);
        check(r.clicks == 0 && r.haptics.isEmpty() && r.moves == 0, "左键杆轻点（开着轻点 = 左键）：不另外点左键、不震（单击是左键自己的按下 / 松开）");
        Rec p = new Rec();
        TrackPoint q = dragLever(p, c);
        q.down(0, 0, 0);
        q.move(30, 0);
        q.frame(0);
        for (long now = 8; now <= 1000; now += 8) q.tick(now, true, false, true);   // scroll = true、buttons = false 都故意给错
        check(p.moves > 0 && p.dx > 0 && p.scrolls == 0, "左键杆推着：只移动，不滚（中键按着也一样）");
        check(p.count(GestureEngine.HAPTIC_TEXTURE) == 0 && p.haptics.isEmpty(), "左键杆推着：不发颗粒（拖动有持续轻震）");
        Rec s = new Rec();   // 对照：红点没按着鼠标键时同样推是发颗粒的
        TrackPoint st = stick(s, c);
        st.down(0, 0, 0);
        st.move(30 - c.tapSlopDp, 0);
        st.frame(0);
        for (long now = 8; now <= 1000; now += 8) st.tick(now, false, false, false);
        check(s.count(GestureEngine.HAPTIC_TEXTURE) > 0, "对照：红点不按鼠标键推着发颗粒");
    }

    static void dragLeverStopsAndPauses() {
        Rec r = new Rec();
        TrackPoint t = dragLever(r, cfg(false));
        t.down(0, 0, 0);
        t.move(30, 0);
        t.frame(0);
        t.tick(8, false, true, false);
        t.up(300, false);
        int x = r.dx;
        check(!t.tick(316, false, true, false) && r.dx == x, "左键杆抬起后迟到的节拍：不出东西，返回 false");
        t.down(0, 0, 400);
        t.move(30, 0);
        t.frame(400);
        t.cancel();
        check(!t.tick(500, false, true, false) && r.dx == x && !t.active() && !t.started(), "左键杆取消后：不出东西");
        Rec p = new Rec();
        TrackPoint q = dragLever(p, cfg(false));
        q.down(0, 0, 0);
        q.move(25, 0);
        q.frame(0);
        long now = 0;
        boolean going = true;
        while (going && now < 30000) {
            now += 8;
            going = q.tick(now, false, true, false);
        }
        check(!going && now > TrackPoint.IDLE_PAUSE_MS && now < TrackPoint.IDLE_PAUSE_MS + 20, "左键杆 20 秒没事件：暂停（" + now + "ms）");
        int px = p.dx;
        check(!q.tick(now + 8, false, true, false) && p.dx == px, "左键杆暂停中不出东西");
        q.move(26, 0);
        check(q.frame(now + 20), "左键杆再动：接着");
        check(q.tick(now + 28, false, true, false) && p.dx > px, "左键杆接着走");
    }

    // ── 加速、最快速度（协议 23，2026-11-01 晚）──

    /** 设置滑块的每一格：加速 0%–100%（步长 5）、最快速度 500–3000（步长 100），照 Prefs.engineConfig 的换算（百分数 / 100f、整数转 float）。 */
    static float[] accelGrid() {
        float[] a = new float[(TrackPoint.ACCEL_PCT_MAX - TrackPoint.ACCEL_PCT_MIN) / TrackPoint.ACCEL_PCT_STEP + 1];
        for (int i = 0; i < a.length; i++) a[i] = TrackPoint.accelFraction(TrackPoint.ACCEL_PCT_MIN + i * TrackPoint.ACCEL_PCT_STEP);
        return a;
    }

    static float[] topGrid() {
        float[] a = new float[(TrackPoint.TOP_SPEED_MAX - TrackPoint.TOP_SPEED_MIN) / TrackPoint.TOP_SPEED_STEP + 1];
        for (int i = 0; i < a.length; i++) a[i] = TrackPoint.TOP_SPEED_MIN + i * TrackPoint.TOP_SPEED_STEP;
        return a;
    }

    /** 默认值（加速 100%、最快 1200）时曲线和改之前逐点一样：改之前推光标用的是 curve(u, MOVE_CRAWL, MOVE_PLATEAU, 1200)。 */
    static void accelDefaultsUnchanged() {
        GestureEngine.Config c = new GestureEngine.Config();
        check(c.stickAccel == 1f && c.stickMaxSpeed == 1200f && TrackPoint.MOVE_MAX == 1200f, "默认：加速 1、最快 1200（= 以前的 MOVE_MAX）");
        check(Float.floatToIntBits(TrackPoint.moveTop(c.stickAccel, c.stickMaxSpeed)) == Float.floatToIntBits(1200f), "默认推满的速度正好是 1200（逐位）");
        // Prefs（离不开 Android，离线编不到）的默认值、范围、换算都引用这几个：STICK_ACCEL_DEFAULT = ACCEL_PCT_DEFAULT、STICK_MAX_DEFAULT = TOP_SPEED_DEFAULT，
        // engineConfig 里 accelFraction(百分数)、(float) 最快速度。这里锁住它们换算出来逐位等于以前的曲线、等于 Config 的默认值
        float prefsAccel = TrackPoint.accelFraction(TrackPoint.ACCEL_PCT_DEFAULT), prefsTop = TrackPoint.TOP_SPEED_DEFAULT;
        check(TrackPoint.ACCEL_PCT_MIN == 0 && TrackPoint.ACCEL_PCT_MAX == 100 && TrackPoint.ACCEL_PCT_DEFAULT == 100 && TrackPoint.TOP_SPEED_DEFAULT == 1200,
                "滑块的默认值：加速 100%、最快速度 1200（范围 0–100%）");
        check(Float.floatToIntBits(prefsAccel) == Float.floatToIntBits(1f) && Float.floatToIntBits(prefsTop) == Float.floatToIntBits(TrackPoint.MOVE_MAX),
                "照 Prefs 的换算（accelFraction(ACCEL_PCT_DEFAULT)、(float) TOP_SPEED_DEFAULT）逐位是 1、MOVE_MAX");
        check(Float.floatToIntBits(prefsAccel) == Float.floatToIntBits(c.stickAccel) && Float.floatToIntBits(prefsTop) == Float.floatToIntBits(c.stickMaxSpeed),
                "Prefs 的默认值换算出来和 Config 的默认值逐位一样");
        check(Float.floatToIntBits(TrackPoint.moveTop(prefsAccel, prefsTop)) == Float.floatToIntBits(1200f), "照 Prefs 的默认值推满也正好是 1200");
        boolean frac = true;
        for (int pct = -50; pct <= 150; pct++) {
            int in = Math.max(0, Math.min(100, pct));
            frac &= Float.floatToIntBits(TrackPoint.accelFraction(pct)) == Float.floatToIntBits(in / 100f);
        }
        check(frac, "accelFraction：百分数截到 0–100 再除以 100（-50–150 每个整数）");
        boolean same = true;
        int n = 0;
        for (int i = 0; i <= 8000; i++) {
            float m = i * 0.01f;
            float old = TrackPoint.curve(TrackPoint.level(m), TrackPoint.MOVE_CRAWL, TrackPoint.MOVE_PLATEAU, 1200f);
            same &= Float.floatToIntBits(TrackPoint.moveSpeed(m, c.stickAccel, c.stickMaxSpeed)) == Float.floatToIntBits(old)
                    && Float.floatToIntBits(TrackPoint.moveSpeed(m)) == Float.floatToIntBits(old)
                    && Float.floatToIntBits(TrackPoint.dragLeverSpeed(m + c.tapSlopDp, c.tapSlopDp, c.stickAccel, c.stickMaxSpeed))
                    == Float.floatToIntBits(TrackPoint.dragLeverSpeed(m + c.tapSlopDp, c.tapSlopDp));
            n++;
        }
        check(same, "默认值：0–80dp 每 0.01dp（" + n + " 个点）推光标的速度和以前的公式逐位一样，左键杆也一样");
    }

    /**
     * 默认值时 TrackPoint 出的每一样东西（位移、滚轮、点击、震动、tick 的返回值、started）和改之前一模一样：一串随机序列
     * （红点 / 左键杆 / 中键滚动杆 × 灵敏度 × 负惯性 × 轻点 × 颗粒 × 中键滑动速度 × 咔嗒 × 8 / 12ms 节拍 × 抖动、卡顿、20 秒暂停、中键按着 / 鼠标键按着 / 连续滚动、
     * 抬起 / 取消），把出的东西揉成一个 FNV 校验和。校验和是拿 77f6ec7（改之前）的 TrackPoint 跑同一段代码算的（一次性脚本，不进仓库）：
     * 以后有意改曲线时这里跟着改。
     */
    static void accelGolden() {
        long h = goldenRun(20261101L, 600);
        check(h == 0xf000b060d8299a8aL, "默认值：600 段随机序列出的东西和改之前一模一样（校验和 " + Long.toHexString(h) + "，应 f000b060d8299a8a；总位移 " + goldenMoved + "）");
        check(goldenMoved == 276726, "随机序列的总位移和改之前一样（" + goldenMoved + "）");
    }

    private static long golden;
    private static long goldenMoved;

    private static void mix(long v) {
        golden ^= v;
        golden *= 1099511628211L;
    }

    static final class GoldenOut implements TrackPoint.Output {
        long moved;

        @Override
        public void move(int dx, int dy) {
            mix(1);
            mix(dx);
            mix(dy);
            moved += Math.abs(dx) + Math.abs(dy);
        }

        @Override
        public void scroll(int w, int p) {
            mix(2);
            mix(w);
            mix(p);
        }

        @Override
        public void click() {
            mix(3);
        }

        @Override
        public void haptic(int k) {
            mix(4);
            mix(k);
        }
    }

    /** 随机序列（只用改之前就有的参数；加速、最快速度是默认值）。改这段就要拿改之前的代码重算校验和。 */
    static long goldenRun(long seed, int episodes) {
        golden = 1469598103934665603L;
        java.util.Random r = new java.util.Random(seed);
        float[] speeds = {0.3f, 0.5f, 1.0f, 1.0f, 1.0f, 1.7f, 3.0f};
        float[] levers = {0.25f, 1.0f, 2.0f};
        long moved = 0;
        for (int e = 0; e < episodes; e++) {
            int mode = r.nextInt(10) < 5 ? TrackPoint.MODE_STICK : r.nextInt(5) < 3 ? TrackPoint.MODE_DRAG_LEVER : TrackPoint.MODE_SCROLL_LEVER;
            GoldenOut o = new GoldenOut();
            TrackPoint t = new TrackPoint(o, mode);
            GestureEngine.Config c = new GestureEngine.Config();
            c.stickSpeed = speeds[r.nextInt(speeds.length)];
            c.stickInertia = r.nextInt(3) != 0;
            c.stickTap = r.nextBoolean();
            c.texture = r.nextBoolean();
            c.leverSpeed = levers[r.nextInt(levers.length)];
            c.scrollTicks = r.nextBoolean() ? GestureEngine.TICKS_ALL : GestureEngine.TICKS_OFF;
            t.setConfig(c);
            int period = r.nextBoolean() ? 8 : 12;
            long now = r.nextInt(1000);
            float x = r.nextFloat() * 300, y = r.nextFloat() * 300;
            float ox = x, oy = y;
            t.down(x, y, now);
            int steps = 20 + r.nextInt(200);
            boolean scroll = r.nextInt(4) == 0, buttons = r.nextInt(4) == 0, smooth = r.nextBoolean();
            for (int s = 0; s < steps; s++) {
                int what = r.nextInt(10);
                if (what < 4) {
                    // 换位置：离按下点 0–60dp 的随机方向，偶尔小抖
                    if (r.nextInt(4) == 0) {
                        x += (r.nextFloat() - 0.5f) * 2;
                        y += (r.nextFloat() - 0.5f) * 2;
                    } else {
                        double ang = r.nextDouble() * Math.PI * 2;
                        float d = r.nextFloat() * 60;
                        x = ox + (float) Math.cos(ang) * d;
                        y = oy + (float) Math.sin(ang) * d;
                    }
                    t.move(x, y);
                    mix(t.frame(now) ? 11 : 10);
                } else if (what < 9) {
                    int gap = r.nextInt(20) == 0 ? 30 + r.nextInt(200) : period + (r.nextInt(3) - 1);
                    now += gap;
                    if (r.nextInt(30) == 0) scroll = !scroll;
                    if (r.nextInt(30) == 0) buttons = !buttons;
                    mix(t.tick(now, scroll, buttons, smooth) ? 21 : 20);
                    mix(t.started() ? 31 : 30);
                } else if (r.nextInt(40) == 0) {
                    // 很久没事件（暂停）再接着
                    now += TrackPoint.IDLE_PAUSE_MS + 50;
                    mix(t.tick(now, scroll, buttons, smooth) ? 41 : 40);
                }
            }
            if (r.nextInt(8) == 0) {
                t.cancel();
            } else {
                t.up(now + r.nextInt(300), r.nextInt(6) == 0);
            }
            mix(t.tick(now + 100, scroll, buttons, smooth) ? 51 : 50);
            moved += o.moved;
        }
        mix(moved);
        goldenMoved = moved;
        return golden;
    }

    /** 加速 0%：推过平台以后不再变快（平台以前那一段照旧），最快速度不起作用。 */
    static void accelZeroFlat() {
        boolean flat = true, before = true, noTop = true;
        for (float top : topGrid()) {
            for (int i = 0; i <= 8000; i++) {
                float m = i * 0.01f;
                float u = TrackPoint.level(m);
                float v = TrackPoint.moveSpeed(m, 0f, top);
                if (u >= TrackPoint.RISE_END) flat &= v == TrackPoint.MOVE_PLATEAU;
                if (u < TrackPoint.PLATEAU_END) before &= v == TrackPoint.moveSpeed(m);   // 爬行 → 平台那一段和默认一样
                noTop &= v == TrackPoint.moveSpeed(m, 0f, 1200f);
            }
        }
        check(flat, "加速 0%：过了上升段（u ≥ 0.45）一律是平台 " + TrackPoint.MOVE_PLATEAU);
        check(before, "加速 0%：平台以前（爬行、上升、平台）和默认一样");
        check(noTop, "加速 0%：最快速度从 500 到 3000 曲线都一样（不起作用）");
        check(TrackPoint.moveTop(0f, 3000f) == TrackPoint.MOVE_PLATEAU && TrackPoint.pushRatio(0f, 3000f) == 1f, "加速 0%：推到底 = 推一半");
    }

    /** 曲线随位移不减；同一个位移，速度随加速、随最快速度不减；平台以前那一段不跟这两项。滑块的每一格都扫。 */
    static void accelMonotone() {
        float[] as = accelGrid(), tops = topGrid();
        boolean inM = true, inA = true, inTop = true, head = true, topOk = true;
        int cases = 0;
        for (float a : as) {
            for (float top : tops) {
                float prev = 0;
                for (int i = 0; i <= 4000; i++) {
                    float m = i * 0.02f;
                    float v = TrackPoint.moveSpeed(m, a, top);
                    inM &= v >= prev;
                    prev = v;
                    if (TrackPoint.level(m) < TrackPoint.PLATEAU_END) head &= v == TrackPoint.moveSpeed(m);
                    cases++;
                }
                float want = TrackPoint.MOVE_PLATEAU + a * (top - TrackPoint.MOVE_PLATEAU);
                topOk &= Math.abs(TrackPoint.moveSpeed(TrackPoint.FULL_DP, a, top) - want) <= 1e-3f
                        && TrackPoint.moveSpeed(200, a, top) == TrackPoint.moveSpeed(TrackPoint.FULL_DP, a, top);
            }
        }
        for (int i = 0; i <= 400; i++) {
            float m = i * 0.2f;
            for (float top : tops) {
                float prev = -1;
                for (float a : as) {
                    float v = TrackPoint.moveSpeed(m, a, top);
                    inA &= v >= prev;
                    prev = v;
                }
            }
            for (float a : as) {
                float prev = -1;
                for (float top : tops) {
                    float v = TrackPoint.moveSpeed(m, a, top);
                    inTop &= v >= prev;
                    prev = v;
                }
            }
        }
        check(inM, "滑块每一格（21 × 26）：速度随位移不减（" + cases + " 个点）");
        check(inA, "同一个位移：速度随加速不减");
        check(inTop, "同一个位移：速度随最快速度不减");
        check(head, "平台以前（u < 0.70）那一段不跟加速、最快速度");
        check(topOk, "推到底 = 平台 + 加速 × (最快 − 平台)，再远封顶");
        check(TrackPoint.TOP_SPEED_MIN > TrackPoint.MOVE_PLATEAU, "最快速度的下限（" + TrackPoint.TOP_SPEED_MIN + "）高于平台（" + TrackPoint.MOVE_PLATEAU + "）");
        check(TrackPoint.MOVE_MAX >= TrackPoint.TOP_SPEED_MIN && TrackPoint.MOVE_MAX <= TrackPoint.TOP_SPEED_MAX
                        && ((int) TrackPoint.MOVE_MAX - TrackPoint.TOP_SPEED_MIN) % TrackPoint.TOP_SPEED_STEP == 0 && 100 % TrackPoint.ACCEL_PCT_STEP == 0,
                "默认值 1200、100% 都正好在滑块的格子上");
    }

    /** 节拍积分照旧：8 / 12 / 抖动的节拍，1 秒走的 = 新曲线的速度（余数不丢）；推到底走 moveTop。 */
    static void accelIntegrate() {
        float[][] settings = {{0f, 1200f}, {0.5f, 2000f}, {1f, 3000f}, {0.35f, 500f}};
        boolean ok = true;
        int cases = 0;
        for (float[] st : settings) {
            for (float m : new float[]{3, 10, 18, 25, 29, 32, 50}) {
                for (int period : new int[]{8, 12, 0}) {
                    Rec r = new Rec();
                    GestureEngine.Config c = cfg(false);
                    c.stickAccel = st[0];
                    c.stickMaxSpeed = st[1];
                    TrackPoint t = stick(r, c);
                    t.down(0, 0, 0);
                    t.move(m, 0);
                    t.frame(0);
                    long now = 0;
                    int[] gaps = {7, 9, 13};
                    for (int i = 0; now < 997; i++) {
                        now += period == 0 ? gaps[i % 3] : period;
                        t.tick(now, false, false, false);
                    }
                    float want = TrackPoint.moveSpeed(m, st[0], st[1]) * now / 1000f;
                    // ≤ 1：余数在 float 里攒，正好整数的总量（平台 450 × 1 秒）可能差一点点、向零取整少 1 个计数（默认曲线也这样）
                    ok &= Math.abs(r.dx - want) <= 1 && r.dy == 0 && !r.reversed;
                    cases++;
                }
            }
        }
        check(ok, "加速 / 最快速度换了：" + cases + " 种（设置 × 位移 × 8 / 12 / 抖动节拍）1 秒走的 = 新曲线 × 时间（误差 ≤ 1 计数）");
        Rec r = new Rec();
        GestureEngine.Config c = cfg(false);
        c.stickAccel = 0.5f;
        c.stickMaxSpeed = 2000f;
        TrackPoint t = stick(r, c);
        t.down(0, 0, 0);
        t.move(32, 0);
        t.frame(0);
        t.tick(200, false, false, false);   // 卡了 200ms
        float want = TrackPoint.moveTop(0.5f, 2000f) * TrackPoint.MAX_DT_MS / 1000f;
        check(Math.abs(r.dx - want) < 1, "一拍隔 200ms 照旧按 50ms 算：" + r.dx + "（应 " + want + "）");
    }

    /** 灵敏度照旧乘在整条曲线上（加速、最快速度换了也一样）。 */
    static void accelSensitivity() {
        boolean ok = true;
        for (float[] st : new float[][]{{0f, 1200f}, {0.5f, 2500f}}) {
            for (float m : new float[]{10, 20, 32}) {
                int[] got = new int[2];
                float[] speeds = {1, 2.5f};
                for (int i = 0; i < 2; i++) {
                    Rec r = new Rec();
                    GestureEngine.Config c = cfg(false);
                    c.stickSpeed = speeds[i];
                    c.stickAccel = st[0];
                    c.stickMaxSpeed = st[1];
                    TrackPoint t = stick(r, c);
                    t.down(0, 0, 0);
                    t.move(m, 0);
                    t.frame(0);
                    for (long now = 8; now <= 1000; now += 8) t.tick(now, false, false, false);
                    got[i] = r.dx;
                }
                ok &= Math.abs(got[1] - 2.5f * got[0]) <= 3 && Math.abs(got[0] - TrackPoint.moveSpeed(m, st[0], st[1])) <= 1;
            }
        }
        check(ok, "加速 / 最快速度换了：灵敏度 2.5 倍走 2.5 倍");
    }

    /** 左键杆用的也是新参数：离按下点（容差 + m）= 红点推 m（加速、最快速度换了照样逐计数一样），推到底封顶在新的最快。 */
    static void accelDragLever() {
        int cases = 0, bad = 0;
        for (float[] st : new float[][]{{0f, 3000f}, {0.5f, 2000f}, {1f, 3000f}, {0.2f, 600f}}) {
            for (boolean inertia : new boolean[]{false, true}) {
                for (float m : new float[]{4, 15, 25, 31, 45}) {
                    GestureEngine.Config c = cfg(inertia);
                    c.stickAccel = st[0];
                    c.stickMaxSpeed = st[1];
                    Rec a = new Rec(), b = new Rec();
                    TrackPoint s = stick(a, c), lv = dragLever(b, c);
                    float k = c.tapSlopDp + m;
                    s.down(0, 0, 0);
                    s.move(0, m);
                    s.frame(0);
                    lv.down(0, 0, 0);
                    lv.move(0, k);
                    lv.frame(0);
                    for (long now = 8; now <= 1000; now += 8) {
                        s.tick(now, false, false, false);
                        lv.tick(now, true, false, true);
                    }
                    cases++;
                    if (a.dy != b.dy || a.dx != 0 || b.dx != 0 || b.scrolls != 0) bad++;
                    if (!inertia && Math.abs(b.dy - TrackPoint.moveSpeed(m, st[0], st[1])) > 1) bad++;   // 走的是新曲线（没负惯性时 1 秒 = 速度）
                }
            }
            GestureEngine.Config c = cfg(false);
            check(TrackPoint.dragLeverSpeed(80, c.tapSlopDp, st[0], st[1]) == TrackPoint.moveTop(st[0], st[1]),
                    "左键杆推得很远：封顶在新的最快（加速 " + st[0] + "、最快 " + st[1] + "）");
        }
        check(bad == 0, "左键杆 = 红点（加速 / 最快速度换了，" + cases + " 种）：出的位移逐计数一样、走的是新曲线、不滚（错 " + bad + "）");
    }

    /** 不管中键滚动：红点在中键按着时滚、中键滚动杆，加速 / 最快速度换了出的滚轮逐条一样。 */
    static void accelLeavesScroll() {
        boolean same = true;
        for (int mode : new int[]{TrackPoint.MODE_STICK, TrackPoint.MODE_SCROLL_LEVER}) {
            for (float m : new float[]{12, 20, 32, 50}) {   // 滚动杆从轻点容差那一圈起算：12dp 斜着推已经出了容差 + 死区
                for (boolean smooth : new boolean[]{false, true}) {
                    List<int[]> logs0 = null;
                    for (float[] st : new float[][]{{1f, 1200f}, {0f, 3000f}, {1f, 3000f}, {0.4f, 500f}}) {
                        Rec r = new Rec();
                        GestureEngine.Config c = cfg(true);
                        c.stickAccel = st[0];
                        c.stickMaxSpeed = st[1];
                        TrackPoint t = new TrackPoint(r, mode);
                        t.setConfig(c);
                        t.down(0, 0, 0);
                        t.move(m * 0.3f, m);
                        t.frame(0);
                        for (long now = 8; now <= 1500; now += 8) t.tick(now, true, false, smooth);
                        same &= r.moves == 0 && r.scrolls > 0;
                        if (logs0 == null) {
                            logs0 = r.scrollLog;
                        } else {
                            same &= logs0.size() == r.scrollLog.size();
                            for (int i = 0; same && i < logs0.size(); i++) same &= java.util.Arrays.equals(logs0.get(i), r.scrollLog.get(i));
                        }
                    }
                }
            }
        }
        check(same, "加速 / 最快速度换了：中键按着推红点、中键滚动杆出的滚轮逐条一样（只管推光标）");
    }

    /** 服务收到什么样的数（存坏了、版本混着跑）曲线都不往下走：加速截到 0–1，最快速度不低于平台，NaN 当 0 / 平台。 */
    static void accelClamp() {
        check(TrackPoint.moveTop(-1f, 2000f) == TrackPoint.MOVE_PLATEAU && TrackPoint.moveTop(Float.NaN, 2000f) == TrackPoint.MOVE_PLATEAU,
                "加速是负数 / NaN：当 0");
        check(TrackPoint.moveTop(5f, 2000f) == 2000f, "加速超过 1：当 1");
        check(TrackPoint.moveTop(1f, 100f) == TrackPoint.MOVE_PLATEAU && TrackPoint.moveTop(1f, Float.NaN) == TrackPoint.MOVE_PLATEAU,
                "最快速度低于平台 / NaN：当平台（推过一半不会变慢）");
        boolean mono = true;
        float prev = 0;
        for (int i = 0; i <= 4000; i++) {
            float v = TrackPoint.moveSpeed(i * 0.02f, 1f, 100f);
            mono &= v >= prev;
            prev = v;
        }
        check(mono, "最快速度给得比平台低：曲线照样不减");
        // ±无穷大（审查发现：以前 +无穷大 没截住，加速 0 时 0 × 无穷大 = NaN，余数变成 NaN 后这次按下一个计数都不出；加速 > 0 时每拍 move(Integer.MAX_VALUE)）
        float inf = Float.POSITIVE_INFINITY, ninf = Float.NEGATIVE_INFINITY;
        check(TrackPoint.moveTop(0f, inf) == TrackPoint.MOVE_PLATEAU && TrackPoint.moveTop(1f, inf) == TrackPoint.TOP_SPEED_MAX
                        && TrackPoint.moveTop(0.5f, inf) == TrackPoint.MOVE_PLATEAU + 0.5f * (TrackPoint.TOP_SPEED_MAX - TrackPoint.MOVE_PLATEAU),
                "最快速度 +无穷大：当 " + TrackPoint.TOP_SPEED_MAX + "（加速 0 时还是平台，不出 NaN）");
        check(TrackPoint.moveTop(1f, ninf) == TrackPoint.MOVE_PLATEAU && TrackPoint.moveTop(inf, 2000f) == 2000f && TrackPoint.moveTop(ninf, 2000f) == TrackPoint.MOVE_PLATEAU,
                "最快速度 -无穷大当平台；加速 +无穷大当 1、-无穷大当 0");
        check(TrackPoint.moveTop(1f, 5000f) == TrackPoint.TOP_SPEED_MAX && TrackPoint.moveTop(1f, Float.MAX_VALUE) == TrackPoint.TOP_SPEED_MAX,
                "最快速度超过滑块的上限：当上限 " + TrackPoint.TOP_SPEED_MAX);
        boolean finite = true;
        float[] odd = {Float.NaN, inf, ninf, -1f, 0f, 0.5f, 1f, 5f, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE};
        float[] oddTop = {Float.NaN, inf, ninf, -1f, 0f, 100f, 450f, 451f, 1200f, 3000f, 3001f, 1e30f, Float.MAX_VALUE, -Float.MAX_VALUE};
        for (float a : odd) {
            for (float top : oddTop) {
                float v = TrackPoint.moveTop(a, top);
                finite &= !Float.isNaN(v) && !Float.isInfinite(v) && v >= TrackPoint.MOVE_PLATEAU && v <= TrackPoint.TOP_SPEED_MAX;
            }
        }
        check(finite, "怪数（NaN、±无穷大、负数、极大极小）两两组合：推满的速度都在 平台–" + TrackPoint.TOP_SPEED_MAX + " 之间");
        // 真 TrackPoint 跑：推到 30dp 0.2 秒、退回 20dp（平台那一段，u ≈ 0.59）1 秒，每拍出的位移有界、退回平台以后照样按平台走
        boolean bounded = true, recovers = true;
        int cases = 0;
        for (float a : odd) {
            for (float top : oddTop) {
                final int[] most = {0};
                final int[] sum = {0};
                TrackPoint.Output o = new TrackPoint.Output() {
                    @Override
                    public void move(int dx, int dy) {
                        most[0] = Math.max(most[0], Math.abs(dx) + Math.abs(dy));
                        sum[0] += dx;
                    }

                    @Override
                    public void scroll(int w, int p) {
                    }

                    @Override
                    public void click() {
                    }

                    @Override
                    public void haptic(int k) {
                    }
                };
                GestureEngine.Config c = cfg(false);
                c.stickAccel = a;
                c.stickMaxSpeed = top;
                TrackPoint t = new TrackPoint(o);
                t.setConfig(c);
                t.down(0, 0, 0);
                t.move(30, 0);
                t.frame(0);
                long now = 0;
                for (; now < 200; now += 8) t.tick(now + 8, false, false, false);
                t.move(20, 0);
                t.frame(now);
                int before = sum[0];
                for (long end = now + 1000; now < end; now += 8) t.tick(now + 8, false, false, false);
                // 一拍最多 50ms × 3000 计数 / 秒（灵敏度 1）= 150 个计数
                bounded &= most[0] <= TrackPoint.TOP_SPEED_MAX * TrackPoint.MAX_DT_MS / 1000 + 1;
                recovers &= Math.abs(sum[0] - before - TrackPoint.MOVE_PLATEAU) <= 2;
                cases++;
            }
        }
        check(bounded, "怪数 " + cases + " 种：真 TrackPoint 每拍出的位移不超过 50ms × " + TrackPoint.TOP_SPEED_MAX + " 计数 / 秒");
        check(recovers, "怪数 " + cases + " 种：推满以后退回平台，1 秒照样走 " + TrackPoint.MOVE_PLATEAU + " 个计数（余数没变成 NaN）");
    }

    /**
     * 设置页说明里现算的数（SettingsActivity.refreshStickAccel 只调这几个）："推到底比推一半快约 N 倍"、"推到底约 N 秒横穿 1920 像素宽的屏"（含灵敏度）。
     */
    static void accelHints() {
        float halfU = TrackPoint.level(TrackPoint.HALF_DP);
        check(halfU >= TrackPoint.RISE_END && halfU < TrackPoint.PLATEAU_END, "\"推一半\"（" + TrackPoint.HALF_DP + "dp）落在平台那一段（u = " + halfU + "）");
        check(TrackPoint.tenths(TrackPoint.pushRatio(1f, 1200f)).equals("2.7"), "默认：推到底约是推一半的 2.7 倍（" + TrackPoint.pushRatio(1f, 1200f) + "）");
        check(TrackPoint.tenths(TrackPoint.crossSeconds(1f, 1200f, 1f)).equals("1.6"), "默认：推到底约 1.6 秒横穿 1920 像素");
        check(TrackPoint.pushRatio(0f, 3000f) == 1f, "加速 0%：倍数是 1（设置页这时换成\"推过一半以后不再变快\"）");
        check(TrackPoint.tenths(TrackPoint.crossSeconds(0f, 3000f, 1f)).equals("4.3"), "加速 0%：推到底就是平台，约 4.3 秒横穿");
        check(TrackPoint.tenths(TrackPoint.pushRatio(1f, 3000f)).equals("6.7") && TrackPoint.tenths(TrackPoint.crossSeconds(1f, 3000f, 1f)).equals("0.6"),
                "最快 3000（原来的值）：6.7 倍、0.6 秒");
        check(TrackPoint.tenths(TrackPoint.crossSeconds(1f, 1200f, 2f)).equals("0.8") && TrackPoint.tenths(TrackPoint.crossSeconds(1f, 1200f, 0.5f)).equals("3.2"),
                "灵敏度 2 / 0.5：0.8 / 3.2 秒（灵敏度乘在整条曲线上）");
        check(TrackPoint.tenths(TrackPoint.crossSeconds(0.5f, 2000f, 1.3f)).equals("1.2"), "加速 50%、最快 2000、灵敏度 1.3：推到底 1225 × 1.3 计数 / 秒，约 1.2 秒");
        // 倍数随两个滑块不减、秒数随两个滑块和灵敏度不增；每一格都是正常的数
        boolean ok = true;
        float[] as = accelGrid(), tops = topGrid();
        for (float a : as) {
            float prevR = 0;
            for (float top : tops) {
                float rr = TrackPoint.pushRatio(a, top);
                ok &= rr >= prevR && rr >= 1f && !Float.isNaN(rr);
                prevR = rr;
                float prevS = Float.MAX_VALUE;
                for (float sens = 0.3f; sens <= 3.0f; sens += 0.1f) {
                    float sec = TrackPoint.crossSeconds(a, top, sens);
                    ok &= sec <= prevS && sec > 0 && !Float.isInfinite(sec);
                    prevS = sec;
                }
            }
        }
        for (float top : tops) {
            float prev = 0;
            for (float a : as) {
                float rr = TrackPoint.pushRatio(a, top);
                ok &= rr >= prev;
                prev = rr;
            }
        }
        check(ok, "滑块每一格 × 灵敏度 0.3–3.0：倍数 ≥ 1、随两个滑块不减，秒数是正数、随灵敏度不增");
    }

    /**
     * 设置页说明里写出来的那几个字（审查后加的）：
     * ratioText——"推过一半以后还会再变快：约是推一半时的 N 倍"里的 N，一位小数写出来是 "1.0" 时（加速很小、最快速度很低）写两位，不然前后半句自相矛盾；
     * topSpeedText——"最快速度"滑块上的数是加速 100% 时推满的速度，加速在 0%–100% 之间时说明里写实际推满多快（不然标题 1200、秒数按 825 算，对不上）。
     */
    static void accelHintTexts() {
        check(TrackPoint.ratioText(1f, 1200f).equals("2.7") && TrackPoint.ratioText(1f, 3000f).equals("6.7"), "倍数：默认 2.7、最快 3000 时 6.7（一位小数）");
        check("1.01".equals(TrackPoint.ratioText(0.05f, 500f)) && "1.05".equals(TrackPoint.ratioText(0.45f, 500f)) && "1.1".equals(TrackPoint.ratioText(0.05f, 1000f)),
                "倍数：5% / 500 写 1.01、45% / 500 写 1.05（一位小数都是 1.0）、5% / 1000 写 1.1（" + TrackPoint.ratioText(0.05f, 500f) + "、"
                        + TrackPoint.ratioText(0.45f, 500f) + "、" + TrackPoint.ratioText(0.05f, 1000f) + "）");
        check(TrackPoint.ratioText(0.001f, 500f) == null && TrackPoint.ratioText(Float.NaN, 1200f) == null,
                "倍数：快不到千分之五（滑块拖不出来的 0.1%）、NaN 当不再变快（null）");
        boolean zeroNull = true, gridOk = true, monoA = true, monoTop = true;
        int twoDigits = 0, cells = 0;
        float[] as = accelGrid(), tops = topGrid();
        for (float top : tops) zeroNull &= TrackPoint.ratioText(0f, top) == null;
        for (float a : as) {
            if (!(a > 0)) continue;
            float prev = 0;
            for (float top : tops) {
                String t = TrackPoint.ratioText(a, top);
                cells++;
                if (t == null || t.equals("1.0") || t.equals("1.00")) {
                    gridOk = false;
                    continue;
                }
                float shown = Float.parseFloat(t), real = TrackPoint.pushRatio(a, top);
                boolean two = t.length() == 4;
                if (two) twoDigits++;
                gridOk &= shown > 1f && Math.abs(shown - real) <= (two ? 0.005f : 0.05f) + 1e-6f && (t.length() == 3 || two);
                monoTop &= shown >= prev;
                prev = shown;
            }
        }
        for (float top : tops) {
            float prev = 0;
            for (float a : as) {
                String t = TrackPoint.ratioText(a, top);
                float shown = t == null ? 1f : Float.parseFloat(t);
                monoA &= shown >= prev;
                prev = shown;
            }
        }
        check(zeroNull, "倍数：加速 0% 时一律 null（换成\"不再变快\"那句，和最快速度那一行灰掉同一个条件）");
        check(gridOk, "倍数：加速 > 0 的 " + cells + " 格都写得出比 1 大的数、没有 \"1.0\"，和真实的倍数差不过最后一位的一半");
        check(twoDigits == 15, "倍数：写两位小数的正好是一位小数会写成 1.0 的那 15 格（" + twoDigits + "）");
        check(monoA && monoTop, "倍数：写出来的数随加速、随最快速度不减");

        check("825".equals(TrackPoint.topSpeedText(0.5f, 1200f)) && "453".equals(TrackPoint.topSpeedText(0.05f, 500f))
                        && "2873".equals(TrackPoint.topSpeedText(0.95f, 3000f)),
                "实际推满：50% / 1200 是 825、5% / 500 是 453、95% / 3000 是 2873（" + TrackPoint.topSpeedText(0.5f, 1200f) + "、"
                        + TrackPoint.topSpeedText(0.05f, 500f) + "、" + TrackPoint.topSpeedText(0.95f, 3000f) + "）");
        check(TrackPoint.topSpeedText(Float.NaN, 1200f) == null, "实际推满：加速 NaN 不写");
        boolean ends = true, mid = true;
        int midCells = 0;
        for (float top : tops) {
            ends &= TrackPoint.topSpeedText(0f, top) == null && TrackPoint.topSpeedText(1f, top) == null;   // 0% 另有一句、100% 滑块上的数就是实际的
            for (float a : as) {
                if (!(a > 0 && a < 1)) continue;
                String t = TrackPoint.topSpeedText(a, top);
                midCells++;
                if (t == null) {
                    mid = false;
                    continue;
                }
                int v = Integer.parseInt(t);
                // 等于 平台 + 加速 × (最快 − 平台) 取整、比滑块上的数小、比平台大；下面那句的秒数就是按它算的
                mid &= v == Math.round(TrackPoint.MOVE_PLATEAU + a * (top - TrackPoint.MOVE_PLATEAU)) && v < top && v > TrackPoint.MOVE_PLATEAU
                        && Math.abs(TrackPoint.crossSeconds(a, top, 1f) - (float) TrackPoint.CROSS_PX / v) < 0.01f;
            }
        }
        check(ends, "实际推满：加速 0% / 100% 不写");
        check(mid, "实际推满：加速在中间的 " + midCells + " 格都写，等于 平台 + 加速 × (最快 − 平台) 取整、比滑块上的数小，和秒数对得上");
    }

    // ── 工具 ──

    private static void near(float got, float want, float tol, String what) {
        check(Math.abs(got - want) <= tol, what + "：想要 " + want + "，实际 " + got);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
