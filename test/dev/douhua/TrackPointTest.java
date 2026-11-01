package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * TrackPoint（小红点）的离线测试：速度曲线（死区、单调、平台、封顶、方案里算的几个点、灵敏度）、按节拍积分（8ms / 12ms / 抖动的节拍
 * 总量对、余数不丢、一拍最多算 50ms）、负惯性（起步更快、按住不动时一样快、往回收手指时不反向）、轻点 = 左键（快、近才算，关掉 / 取消不算，
 * 轻点过程中不出位移）、颗粒（每 30 计数一颗、最短间隔、按着键 / 滚动 / 关掉时不发）、中键滚动（只出滚轮、方向、主方向、按格 / 连续、咔嗒）、
 * 停（抬起 / 取消后迟到的节拍什么都不出）、兜底暂停（20 秒没事件停、再动接着）；
 * 中键滚动杆模式（一直是滚动、和红点的中键滚动出的量一样、先挪出轻点容差才开始、开始以后挪回死区停 / 再推接着、不算轻点、不发颗粒、按格 / 连续、停、暂停）。
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
        TrackPoint t = new TrackPoint(r, true);
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
