package dev.douhua;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 手柄页触摸模型（GamepadTouch）的离线测试：单键（引用计数、滑出照样按着、扳机）、面键簇（最近的键、缝里不落空、两颗中间一起按、
 * 最多两颗、回差、滑动换键的先松后按、滑出 24dp 全松再回来、关掉滑动换键）、十字键（扇区、斜向窄、死区、边界回差、滑出范围只看角度、
 * 第二根不理、4 方向）、摇杆（浮动中心、封顶、死区 + 抗死区、底座跟着走、Y 往下为正、推到边咔嗒和重新上弦、一帧一份报文）、
 * 收尾（releaseAll、换参数后全零、每个按下配了松开）、换布局时按着的手指照用旧的、N64 的 C 键 = 右摇杆推满，和一组乱序随机序列。密度 1（像素 = dp）。
 */
public final class GamepadTouchTest {
    private static int failures;
    private static int checks;

    /** 记下所有输出：报文、按下 / 松开、震动。 */
    static final class Rec implements GamepadTouch.Output {
        final List<String> log = new ArrayList<>();
        final List<byte[]> reports = new ArrayList<>();
        byte[] last = new byte[Hid.GAMEPAD_REPORT_SIZE];
        /** 每个控件编号按下了几次没松（按下 +1、松开 -1）。 */
        final Map<Integer, Integer> held = new HashMap<>();
        boolean negative;

        @Override
        public void report(byte[] report) {
            last = report.clone();
            reports.add(last);
            log.add("r");
        }

        @Override
        public void button(int id, int action) {
            log.add("b" + Integer.toHexString(id) + ":" + action);
            int n = held.getOrDefault(id, 0) + (action == Proto.BUTTON_DOWN ? 1 : -1);
            if (n < 0) negative = true;
            held.put(id, n);
        }

        @Override
        public void haptic(int kind) {
            log.add("h" + kind);
        }

        Hid.Gamepad state() {
            Hid.Gamepad g = new Hid.Gamepad();
            g.parse(last);
            return g;
        }

        int count(String prefix) {
            int n = 0;
            for (String s : log) if (s.startsWith(prefix)) n++;
            return n;
        }

        boolean allUp() {
            for (int n : held.values()) if (n != 0) return false;
            return !negative;
        }
    }

    // ── 布局（像素 = dp）：一个菱形面键簇（Xbox 字样：北 Y、西 X、东 B、南 A）、十字键、左右摇杆、Start、LT ──
    static final int CLUSTER = Proto.gamepadId(Proto.GP_KIND_CLUSTER, 0, 0);
    static final int DPAD = Proto.gamepadId(Proto.GP_KIND_DPAD, 0, 8);
    static final int LSTICK = Proto.gamepadId(Proto.GP_KIND_STICK, 0, 0), RSTICK = Proto.gamepadId(Proto.GP_KIND_STICK, 0, 1);
    static final int START = Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_START), LT = Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_LT);
    static final int[] FACE_OUT = {Proto.GP_NORTH, Proto.GP_WEST, Proto.GP_EAST, Proto.GP_SOUTH};
    static final float D = 58, G = 8;
    /** 面键圆心（北 西 东 南），簇范围 (0,0)–(size)。 */
    static float[][] centers;
    static TouchRouter.Region area, dpad, lstick, rstick, start, lt;
    static TouchRouter.Region[] regions;

    static {
        float[] size = new float[2];
        centers = GamepadPlan.faceCenters(GamepadSpec.FACE_DIAMOND, D, G, size);
        List<TouchRouter.Region> l = new ArrayList<>();
        area = new TouchRouter.Region(Proto.REGION_GAMEPAD, CLUSTER, 0, 0, Math.round(size[0]), Math.round(size[1]));
        l.add(area);
        for (int i = 0; i < 4; i++) {
            int x = Math.round(centers[i][0] - D / 2), y = Math.round(centers[i][1] - D / 2);
            l.add(new TouchRouter.Region(Proto.REGION_GAMEPAD, Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, FACE_OUT[i]), x, y, x + 58, y + 58));
        }
        dpad = new TouchRouter.Region(Proto.REGION_GAMEPAD, DPAD, 200, 0, 336, 136);
        lstick = new TouchRouter.Region(Proto.REGION_GAMEPAD, LSTICK, 400, 0, 550, 150);
        rstick = new TouchRouter.Region(Proto.REGION_GAMEPAD, RSTICK, 600, 0, 750, 150);
        start = new TouchRouter.Region(Proto.REGION_GAMEPAD, START, 800, 0, 864, 44);
        lt = new TouchRouter.Region(Proto.REGION_GAMEPAD, LT, 900, 0, 988, 44);
        l.add(dpad);
        l.add(lstick);
        l.add(rstick);
        l.add(start);
        l.add(lt);
        regions = l.toArray(new TouchRouter.Region[0]);
    }

    static GamepadTouch touch(Rec r) {
        GamepadTouch t = new GamepadTouch(r);
        t.setRegions(regions);
        return t;
    }

    static float cx(int i) {
        return Math.round(centers[i][0] - D / 2) + D / 2;
    }

    static float cy(int i) {
        return Math.round(centers[i][1] - D / 2) + D / 2;
    }

    static final int N = 0, W = 1, E = 2, S = 3;

    public static void main(String[] args) {
        buttonPressRelease();
        buttonRefcount();
        triggerIsAxis();
        clusterNearest();
        clusterNoDeadSpot();
        clusterChord();
        clusterSlide();
        clusterHysteresis();
        clusterExit();
        clusterSlideOff();
        dpadDirections();
        dpadBoundaryHysteresis();
        dpadOutsideStillWorks();
        dpadDeadzone();
        dpadFourWay();
        dpadSecondFingerIgnored();
        stickFloating();
        stickYDown();
        stickCapAndFollow();
        stickNoFollow();
        stickDeadzone();
        stickRim();
        stickOneReportPerFrame();
        stickSecondFingerIgnored();
        upFlushesPendingAxes();
        releaseAllNeutral();
        configResetsNeutral();
        unusedReleaseSendsNothing();
        regionsSwapKeepsFinger();
        sameSlotDownTwice();
        n64CKeys();
        randomSequences();
        System.err.println("手柄触摸：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 单键 ═══════════

    static void buttonPressRelease() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, start, 830, 20);
        check(r.state().pressed(Proto.GP_START), "单键：按下当场发（Start 位）");
        check(r.log.contains("b" + Integer.toHexString(START) + ":" + Proto.BUTTON_DOWN), "单键：报按下（App 震\"嗒\"）");
        t.move(0, 2000, 2000);
        t.frame();
        check(r.state().pressed(Proto.GP_START), "单键：滑出照样按着");
        t.up(0);
        check(Hid.Gamepad.neutral(r.last), "单键：抬起回中立");
        check(r.allUp(), "单键：按下配了松开");
        eq(r.count("h"), 0, "单键：松开不震");
    }

    static void buttonRefcount() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, start, 830, 20);
        t.down(1, start, 840, 20);
        t.up(0);
        check(r.state().pressed(Proto.GP_START), "同一个输出两根手指：先抬一根照样按着");
        t.up(1);
        check(!r.state().pressed(Proto.GP_START), "最后一根抬起才松");
        check(r.allUp(), "两根手指：各自的按下都配了松开");
    }

    static void triggerIsAxis() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lt, 930, 20);
        eq(r.state().lt, 255, "数字扳机：按下 = 轴 255");
        eq(r.state().buttons, 0, "扳机不发数字键（第 9、10 键留着）");
        t.up(0);
        eq(r.state().lt, 0, "扳机松开归零");
    }

    // ═══════════ 面键簇 ═══════════

    static int pressedFace(Rec r) {
        int m = 0;
        Hid.Gamepad g = r.state();
        for (int i = 0; i < 4; i++) if (g.pressed(FACE_OUT[i])) m |= 1 << i;
        return m;
    }

    static void clusterNearest() {
        for (int k = 0; k < 4; k++) {
            Rec r = new Rec();
            GamepadTouch t = touch(r);
            t.down(0, area, cx(k), cy(k));
            eq(pressedFace(r), 1 << k, "簇：按在第 " + k + " 颗正中只按它");
            t.up(0);
            check(Hid.Gamepad.neutral(r.last) && r.allUp(), "簇：抬起全松");
        }
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        float mx = (cx(N) + cx(S)) / 2, my = (cy(W) + cy(E)) / 2;
        t.down(0, area, mx, my);
        eq(Integer.bitCount(pressedFace(r)), 1, "簇：按在菱形正中（离四颗都远）只按最近的一颗");
    }

    /** 范围里哪儿按都有键（缝里也算，没有死区），最多两颗。 */
    static void clusterNoDeadSpot() {
        boolean none = false, more = false;
        for (int x = area.left; x < area.right; x += 3) {
            for (int y = area.top; y < area.bottom; y += 3) {
                Rec r = new Rec();
                GamepadTouch t = touch(r);
                t.down(0, area, x, y);
                int n = Integer.bitCount(pressedFace(r));
                if (n == 0) none = true;
                if (n > 2) more = true;
            }
        }
        check(!none, "簇：范围里哪儿按都有键");
        check(!more, "簇：最多两颗一起按");
    }

    static void clusterChord() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        // 南、东两颗中间（两颗边之间的缝正中，离两颗的边都约 4dp）
        float x = (cx(S) + cx(E)) / 2, y = (cy(S) + cy(E)) / 2;
        t.down(0, area, x, y);
        eq(pressedFace(r), 1 << S | 1 << E, "簇：拇指压在两颗中间 = 两颗一起按");
        eq(r.count("b"), 2, "簇：两颗各报一次按下（各\"嗒\"一下）");
        t.up(0);
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "簇：两颗一起松");
        // 在南里面离南的边 3dp、离东的边 11dp：只按南
        Rec r2 = new Rec();
        GamepadTouch t2 = touch(r2);
        float[] p = alongPair(S, E, -3);
        t2.down(0, area, p[0], p[1]);
        eq(pressedFace(r2), 1 << S, "簇：离另一颗的边 > 10dp 不一起按");
    }

    /**
     * 南、东两颗圆心连线上，离南的边 d（负 = 在南里面）的点。两颗边之间的缝 = 圆心距 − 直径 = G（8dp）。
     */
    static float[] alongPair(int a, int b, float d) {
        float ax = cx(a), ay = cy(a), bx = cx(b), by = cy(b);
        float len = (float) Math.hypot(bx - ax, by - ay);
        float k = (D / 2 + d) / len;
        return new float[]{ax + (bx - ax) * k, ay + (by - ay) * k};
    }

    static void clusterSlide() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, area, cx(S), cy(S));
        int at = r.log.size();
        // 从南滚到东：中间先两颗一起，再只剩东
        float[] mid = alongPair(S, E, G / 2);
        t.move(0, mid[0], mid[1]);
        eq(pressedFace(r), 1 << S | 1 << E, "簇滑动：滚到两颗中间 = 两颗一起");
        t.move(0, cx(E), cy(E));
        eq(pressedFace(r), 1 << E, "簇滑动：滚到东上只按东");
        List<String> ev = new ArrayList<>();
        for (String s : r.log.subList(at, r.log.size())) if (s.startsWith("b")) ev.add(s);
        String sId = Integer.toHexString(Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, FACE_OUT[S]));
        String eId = Integer.toHexString(Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, FACE_OUT[E]));
        eq(ev.toString(), "[b" + eId + ":0, b" + sId + ":1]", "簇滑动：新的按下（嗒）、旧的松开");
        // 直接从东跳到北（中间没经过两颗之间）：先松后按
        int at2 = r.log.size();
        t.move(0, cx(N), cy(N));
        List<String> ev2 = new ArrayList<>();
        for (String s : r.log.subList(at2, r.log.size())) if (s.startsWith("b")) ev2.add(s);
        String nId = Integer.toHexString(Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, FACE_OUT[N]));
        eq(ev2.toString(), "[b" + eId + ":1, b" + nId + ":0]", "簇滑动：换到别的键 = 旧的先松、新的再按");
        t.up(0);
        check(r.allUp() && Hid.Gamepad.neutral(r.last), "簇滑动：抬起全松、按下都配了松开");
    }

    /** 按着两颗时，离第二颗的边 10–12dp 之间不松（回差 2dp），过了 12dp 才松。 */
    static void clusterHysteresis() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        float[] chord = alongPair(S, E, G / 2);
        t.down(0, area, chord[0], chord[1]);
        eq(pressedFace(r), 1 << S | 1 << E, "回差：先两颗一起");
        // 往南里面挪：离东的边 = 缝 − d；d = -3 → 离东 11dp
        float[] p = alongPair(S, E, -3);
        t.move(0, p[0], p[1]);
        eq(pressedFace(r), 1 << S | 1 << E, "回差：离东的边 11dp（在 10–12 之间）照样两颗");
        p = alongPair(S, E, -5);
        t.move(0, p[0], p[1]);
        eq(pressedFace(r), 1 << S, "回差：离东的边 13dp：东松开");
        p = alongPair(S, E, -3);
        t.move(0, p[0], p[1]);
        eq(pressedFace(r), 1 << S, "回差：再回到 11dp 不重新按（要到 10dp 以内）");
        p = alongPair(S, E, -1);
        t.move(0, p[0], p[1]);
        eq(pressedFace(r), 1 << S | 1 << E, "回差：到 9dp 再按上");
        // 主键回差：停在南、东正中间来回 ±0.5dp 不来回换主键
        Rec r2 = new Rec();
        GamepadTouch t2 = touch(r2);
        float mx = (cx(N) + cx(S)) / 2, my = (cy(W) + cy(E)) / 2;
        t2.down(0, area, mx, my);
        int downs = r2.count("b");
        for (int i = 0; i < 10; i++) t2.move(0, mx + (i % 2 == 0 ? 0.5f : -0.5f), my + (i % 2 == 0 ? 0.5f : -0.5f));
        eq(r2.count("b"), downs, "主键回差：在几颗正中间抖不来回换键");
    }

    static void clusterExit() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, area, cx(E), cy(E));
        t.move(0, area.right + 20, cy(E));
        eq(pressedFace(r), 1 << E, "滑出簇：范围外 20dp 照样按着（最近的）");
        t.move(0, area.right + 25, cy(E));
        eq(pressedFace(r), 0, "滑出簇：范围外 24dp 以外全松");
        t.move(0, area.right + 10, cy(E));
        eq(pressedFace(r), 0, "滑出簇：回到 24dp 以内但还在范围外：不按");
        t.move(0, cx(E), cy(E));
        eq(pressedFace(r), 1 << E, "滑出簇：回到范围里再按");
        t.up(0);
        check(r.allUp() && Hid.Gamepad.neutral(r.last), "滑出簇：抬起全松");
    }

    static void clusterSlideOff() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        GamepadConfig c = new GamepadConfig();
        c.clusterSlide = false;
        t.setConfig(c);
        float[] chord = alongPair(S, E, G / 2);
        t.down(0, area, chord[0], chord[1]);
        eq(Integer.bitCount(pressedFace(r)), 1, "滑动换键关：两颗中间只按一颗");
        int was = pressedFace(r);
        t.move(0, cx(N), cy(N));
        t.move(0, area.right + 200, 0);
        eq(pressedFace(r), was, "滑动换键关：滑动不换、滑出也不松");
        t.up(0);
        check(r.allUp() && Hid.Gamepad.neutral(r.last), "滑动换键关：抬起全松");
    }

    // ═══════════ 十字键 ═══════════

    static final float DCX = 268, DCY = 68;

    /** 十字键中心往角度 deg（0 = 右、90 = 上）推 dist 的点。 */
    static float[] at(float deg, float dist) {
        double a = Math.toRadians(deg);
        return new float[]{DCX + (float) Math.cos(a) * dist, DCY - (float) Math.sin(a) * dist};
    }

    static void dpadDirections() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, dpad, DCX, DCY);
        eq(r.reports.size(), 0, "十字键：按在正中 = 回中，不发");
        eq(r.count("b"), 0, "十字键：按在正中不\"嗒\"");
        float[] p = at(0, 50);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "十字键：往右 = 右");
        eq(r.count("b"), 1, "十字键：从中间推出一个方向 = 嗒（按下）");
        p = at(45, 50);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_UP_RIGHT, "十字键：45° = 右上");
        eq(r.count("h" + GestureEngine.HAPTIC_TICK), 1, "十字键：按着换方向 = 咔嗒");
        int[] want = {3, 2, 1, 8, 7, 6, 5, 4};
        for (int i = 0; i < 8; i++) {
            Rec r2 = new Rec();
            GamepadTouch t2 = touch(r2);
            float[] q = at(i * 45, 50);
            t2.down(0, dpad, q[0], q[1]);
            eq(r2.state().hat, want[i], "十字键：直接按在 " + (i * 45) + "°");
        }
        // 斜向窄（30°）：离正向 16° 照样是正向，离 16°（离斜向 29°）
        Rec r3 = new Rec();
        GamepadTouch t3 = touch(r3);
        float[] q = at(28, 50);
        t3.down(0, dpad, q[0], q[1]);
        eq(r3.state().hat, Hid.Gamepad.HAT_RIGHT, "斜向窄：28° 还是右（右的扇区 ±30°）");
        Rec r4 = new Rec();
        GamepadTouch t4 = touch(r4);
        q = at(32, 50);
        t4.down(0, dpad, q[0], q[1]);
        eq(r4.state().hat, Hid.Gamepad.HAT_UP_RIGHT, "斜向窄：32° 是右上（右上的扇区 30–60°）");
        t.up(0);
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "十字键：抬起回中、按下配了松开");
    }

    static void dpadBoundaryHysteresis() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        float[] p = at(25, 50);
        t.down(0, dpad, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "边界回差：25° 是右");
        for (int i = 0; i < 10; i++) {
            p = at(i % 2 == 0 ? 31 : 29, 50);
            t.move(0, p[0], p[1]);
        }
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "边界回差：在 30° 边界上来回 1° 不换方向");
        eq(r.count("h"), 0, "边界回差：不出咔嗒");
        p = at(36, 50);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_UP_RIGHT, "边界回差：过了 35° 才换成右上");
        p = at(26, 50);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_UP_RIGHT, "边界回差：回到 26° 还是右上（右上外扩 5° 到 25°）");
        p = at(24, 50);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "边界回差：24° 才回到右");
        eq(r.count("h" + GestureEngine.HAPTIC_TICK), 2, "边界回差：换了两次方向、两下咔嗒");
    }

    static void dpadOutsideStillWorks() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, dpad, DCX, DCY);
        t.move(0, 1500, DCY);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "十字键：滑出范围只看角度（照样右）");
        t.move(0, DCX, -900);
        eq(r.state().hat, Hid.Gamepad.HAT_UP, "十字键：滑到很远的上面 = 上");
    }

    static void dpadDeadzone() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, dpad, DCX, DCY);
        float[] p = at(0, 16);   // 半径 68 × 25% = 17
        t.move(0, p[0], p[1]);
        eq(r.state().hat, 0, "十字键死区：离中心 16（< 17）= 回中");
        p = at(0, 18);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "十字键死区：18 = 右");
        p = at(0, 15);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "十字键死区回差：回到 15 还算右（要到死区的 0.8 倍以内才回中）");
        p = at(0, 12);
        t.move(0, p[0], p[1]);
        eq(r.state().hat, 0, "十字键死区回差：12 回中");
        check(r.allUp(), "十字键：回中 = 松开");
    }

    static void dpadFourWay() {
        GamepadConfig c = new GamepadConfig();
        c.dpad8 = false;
        boolean diag = false;
        for (int deg = -180; deg < 180; deg += 3) {
            Rec r = new Rec();
            GamepadTouch t = touch(r);
            t.setConfig(c);
            float[] p = at(deg, 50);
            t.down(0, dpad, p[0], p[1]);
            int h = r.state().hat;
            if (h % 2 == 0) diag = true;
        }
        check(!diag, "4 方向：没有斜向");
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.setConfig(c);
        float[] p = at(40, 50);
        t.down(0, dpad, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_RIGHT, "4 方向：40° 是右");
    }

    static void dpadSecondFingerIgnored() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        float[] p = at(90, 50);
        t.down(0, dpad, p[0], p[1]);
        p = at(-90, 50);
        t.down(1, dpad, p[0], p[1]);
        eq(r.state().hat, Hid.Gamepad.HAT_UP, "十字键：第一根占着，第二根不理");
        t.up(1);
        eq(r.state().hat, Hid.Gamepad.HAT_UP, "十字键：第二根抬起不影响");
        t.up(0);
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "十字键：第一根抬起回中");
    }

    // ═══════════ 摇杆 ═══════════

    static void stickFloating() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 420, 130);   // 不在区域中心：浮动中心 = 按下点
        t.frame();
        eq(r.reports.size(), 0, "摇杆：按下点当中心，按下不动 = 回中");
        t.move(0, 420 + 28, 130);
        eq(r.reports.size(), 0, "摇杆：轴变了先攒着");
        t.frame();
        Hid.Gamepad g = r.state();
        check(Math.abs(g.lx - 16384) <= 2 && g.ly == 0, "摇杆：推半个行程 = 一半（死区和抗死区一样大时不重新缩放）：" + g.lx);
        eq(r.count("b"), 0, "摇杆：不报按下（不\"嗒\"）");
        eq(r.count("h"), 0, "摇杆：推动不震（不发颗粒）");
    }

    static void stickYDown() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, rstick, 675, 75);
        t.move(0, 675, 75 + 56);
        t.frame();
        eq(r.state().ry, 32767, "右摇杆：往下推满 = Y 正满（往下为正）");
        eq(r.state().lx, 0, "右摇杆不动左摇杆");
    }

    static void stickCapAndFollow() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 475, 75);
        t.move(0, 475 + 112, 75);   // 两个行程
        t.frame();
        eq(r.state().lx, 32767, "摇杆：推过半径封顶");
        t.move(0, 475 + 112 - 28, 75);   // 往回半个行程：底座已经跟到 475 + 56
        t.frame();
        check(r.state().lx > 16000 && r.state().lx < 16800, "底座跟着走：推过头后往回 28 = 还往右一半（底座离手指一个行程）：" + r.state().lx);
        t.move(0, 475 + 56 - 28, 75);
        t.frame();
        check(r.state().lx < -16000, "底座跟着走：往回到底座左边 = 马上往左：" + r.state().lx);
        t.move(0, 3000, 75);
        t.frame();
        eq(r.state().lx, 32767, "摇杆：滑出区域照样有效");
    }

    static void stickNoFollow() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        GamepadConfig c = new GamepadConfig();
        c.followBase = false;
        t.setConfig(c);
        t.down(0, lstick, 475, 75);
        t.move(0, 475 + 112, 75);
        t.move(0, 475 + 28, 75);
        t.frame();
        check(Math.abs(r.state().lx - 16384) <= 2, "底座顶住不动：推过头再回到 28 = 往右一半");
    }

    static void stickDeadzone() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 475, 75);
        t.move(0, 475 + 4, 75);   // 4 / 56 = 7%
        t.frame();
        eq(r.state().lx, 0, "摇杆死区：推 7%（≤ 8%）= 0");
        t.move(0, 475 + 5, 75);   // 8.9%
        t.frame();
        check(r.state().lx >= Math.round(0.08f * 32767), "抗死区：刚推出死区就从 8% 起，不被主机的死区吃掉：" + r.state().lx);
        GamepadConfig c = new GamepadConfig();
        c.antiDeadzone = 0;
        Rec r2 = new Rec();
        GamepadTouch t2 = touch(r2);
        t2.setConfig(c);
        t2.down(0, lstick, 475, 75);
        t2.move(0, 475 + 5, 75);
        t2.frame();
        check(r2.state().lx > 0 && r2.state().lx < 0.02f * 32767, "没有抗死区：刚推出死区从 0 起：" + r2.state().lx);
        t2.move(0, 475 + 56, 75);
        t2.frame();
        eq(r2.state().lx, 32767, "没有抗死区：推满照样满");
    }

    static void stickRim() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 475, 75);
        String tick = "h" + GestureEngine.HAPTIC_TICK;
        t.move(0, 475 + 54, 75);   // 96%
        eq(r.count(tick), 1, "推到边（≥ 95%）：咔嗒");
        t.move(0, 475 + 56, 75);
        t.move(0, 475 + 300, 75);
        eq(r.count(tick), 1, "一直在边上：不再响");
        t.move(0, 475 + 300 - 56 * 0.1f, 75);   // 底座跟到了 475 + 244：退 10% = 90%
        t.move(0, 475 + 300, 75);
        eq(r.count(tick), 1, "退到 90%（还在 85% 以上）再推：不响");
        t.move(0, 475 + 300 - 56 * 0.2f, 75);   // 80%
        t.move(0, 475 + 300, 75);
        eq(r.count(tick), 2, "退到 85% 以下重新上弦，再推到边：响");
        GamepadConfig c = new GamepadConfig();
        c.rimTicks = false;
        Rec r2 = new Rec();
        GamepadTouch t2 = touch(r2);
        t2.setConfig(c);
        t2.down(0, lstick, 475, 75);
        t2.move(0, 475 + 56, 75);
        eq(r2.count("h"), 0, "推到边咔嗒关：不响");
    }

    static void stickOneReportPerFrame() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 475, 75);
        t.down(1, rstick, 675, 75);
        int before = r.reports.size();
        t.move(0, 475 + 30, 75);
        t.move(1, 675, 75 + 30);
        t.frame();
        eq(r.reports.size() - before, 1, "两根摇杆同一帧都动：只发一份报文");
        t.frame();
        eq(r.reports.size() - before, 1, "没变的帧不发");
    }

    static void stickSecondFingerIgnored() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 475, 75);
        t.down(1, lstick, 500, 75);
        t.move(1, 500 + 56, 75);
        t.frame();
        eq(r.state().lx, 0, "摇杆：第二根不理");
        t.up(1);
        t.move(0, 475 - 56, 75);
        t.frame();
        eq(r.state().lx, -32767, "摇杆：第一根照常");
    }

    static void upFlushesPendingAxes() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, lstick, 475, 75);
        t.down(1, start, 830, 20);
        t.move(0, 475 + 56, 75);   // 攒着，还没 frame
        t.up(1);   // App 本地分流抬起后没有 frame：up 自己冲一次
        Hid.Gamepad g = r.state();
        check(g.lx == 32767 && !g.pressed(Proto.GP_START), "抬起时攒着的轴一起发出去");
    }

    // ═══════════ 收尾 ═══════════

    static void pressEverything(GamepadTouch t) {
        t.down(0, area, cx(S), cy(S));
        float[] p = at(0, 50);
        t.down(1, dpad, p[0], p[1]);
        t.down(2, lstick, 475, 75);
        t.move(2, 475 + 40, 75 + 20);
        t.down(3, rstick, 675, 75);
        t.move(3, 675 - 30, 75);
        t.down(4, start, 830, 20);
        t.down(5, lt, 930, 20);
        t.frame();
    }

    static void releaseAllNeutral() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        pressEverything(t);
        Hid.Gamepad g = r.state();
        check(g.buttons != 0 && g.hat != 0 && g.lx != 0 && g.rx != 0 && g.lt == 255, "全按上了");
        t.releaseAll();
        check(Hid.Gamepad.neutral(r.last), "releaseAll：全零");
        check(r.allUp(), "releaseAll：每个按下都配了松开");
        int n = r.reports.size();
        t.move(0, cx(E), cy(E));
        t.move(2, 475, 75);
        t.up(0);
        t.up(1);
        t.frame();
        eq(r.reports.size(), n, "releaseAll 之后迟到的移动 / 抬起：什么都不发");
        check(r.allUp(), "releaseAll 之后迟到的抬起：不多报松开");
    }

    static void configResetsNeutral() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        pressEverything(t);
        t.setConfig(new GamepadConfig());
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "换参数：回中立、按下都配了松开");
    }

    static void unusedReleaseSendsNothing() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.releaseAll();
        t.setConfig(new GamepadConfig());
        eq(r.reports.size(), 0, "没用过手柄：收尾什么都不发（服务那边不会因此建手柄）");
    }

    static void regionsSwapKeepsFinger() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, area, cx(S), cy(S));
        // App 重报布局（新对象，几何一样）：按着的手指照用按下时那一份
        TouchRouter.Region[] copy = new TouchRouter.Region[regions.length];
        for (int i = 0; i < regions.length; i++) {
            TouchRouter.Region a = regions[i];
            copy[i] = new TouchRouter.Region(a.type, a.id, a.left, a.top, a.right, a.bottom);
        }
        t.setRegions(copy);
        t.move(0, cx(E), cy(E));
        eq(pressedFace(r), 1 << E, "换布局：按着的手指照样能滑动换键");
        t.setRegions(new TouchRouter.Region[0]);   // ⋯ 菜单开了，只报菜单
        t.move(0, cx(W), cy(W));
        eq(pressedFace(r), 1 << W, "菜单开着：已经按在簇上的手指照旧归簇");
        t.up(0);
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "换布局后抬起：全松");
    }

    /** 同一个 slot 没抬起又按下（不该发生，兜底）：先把上一次收尾，不留按着的键。 */
    static void sameSlotDownTwice() {
        Rec r = new Rec();
        GamepadTouch t = touch(r);
        t.down(0, start, 830, 20);
        t.down(0, lt, 930, 20);
        check(!r.state().pressed(Proto.GP_START) && r.state().pressed(Proto.GP_LT), "同一个 slot 又按下：上一个键先松");
        t.up(0);
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "同一个 slot 又按下：抬起后全零、按下都配了松开");
    }

    /**
     * N64 的 C 键（在面键簇里、发右摇杆推满）：一颗 = 那个方向推满、按下"嗒"；相邻两颗（压在中间）= 斜着推满（各 0.707）；相反两颗 = 那根轴回中；
     * 右摇杆上同时有手指时取推得远的；全松回中立、按下都配了松开；画按下的样子（Hid.Gamepad.pressed）看右摇杆过没过一半。
     */
    static void n64CKeys() {
        float[] size = new float[2];
        float[][] c = GamepadPlan.faceCenters(GamepadSpec.FACE_N64, D, G, size);
        int[] out = GamepadSpec.faceOutputs(GamepadSpec.FACE_N64);
        List<TouchRouter.Region> l = new ArrayList<>();
        TouchRouter.Region a = new TouchRouter.Region(Proto.REGION_GAMEPAD, CLUSTER, 0, 0, Math.round(size[0]), Math.round(size[1]));
        l.add(a);
        for (int i = 0; i < c.length; i++) {
            int r = Math.round(c[i][2] / 2);
            int x = Math.round(c[i][0]), y = Math.round(c[i][1]);
            l.add(new TouchRouter.Region(Proto.REGION_GAMEPAD, Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, out[i]), x - r, y - r, x + r, y + r));
        }
        l.add(rstick);
        Rec r = new Rec();
        GamepadTouch t = new GamepadTouch(r);
        t.setRegions(l.toArray(new TouchRouter.Region[0]));
        int up = 2, left = 3, right = 4, down = 5;   // c 的下标：A B C↑ C← C→ C↓
        int max = Hid.Gamepad.AXIS_MAX, diag = Math.round(max * (float) Math.sqrt(0.5));
        t.down(0, a, c[up][0], c[up][1]);
        Hid.Gamepad g = r.state();
        check(g.rx == 0 && g.ry == -max && g.buttons == 0, "C↑：右摇杆往上推满、不发键");
        check(g.pressed(Proto.GP_C_UP) && !g.pressed(Proto.GP_C_DOWN), "C↑：画成按下（右摇杆过了一半）");
        check(r.log.contains("b" + Integer.toHexString(Proto.gamepadId(Proto.GP_KIND_BUTTON, 0, Proto.GP_C_UP)) + ":" + Proto.BUTTON_DOWN), "C↑：报按下（\"嗒\"）");
        // 压在 C↑ 和 C→ 中间：两颗一起 = 斜着推满
        float mx = (c[up][0] + c[right][0]) / 2, my = (c[up][1] + c[right][1]) / 2;
        t.move(0, mx, my);
        g = r.state();
        check(g.rx == diag && g.ry == -diag, "C↑ + C→：斜着推满（各 0.707），实际 " + g.rx + ", " + g.ry);
        t.down(1, a, c[left][0], c[left][1]);   // 再按 C←：左右相反、横轴回中
        g = r.state();
        check(g.rx == 0 && g.ry == -max, "C← 和 C→ 一起：横轴回中、只剩 C↑ 往上推满，实际 " + g.rx + ", " + g.ry);
        t.up(0);
        t.up(1);
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "C 键全松：回中立、按下都配了松开");
        // 右摇杆上有手指、推得比 C 键近：C 键赢；推得更远（同样满）时右摇杆那根照旧
        t.down(2, rstick, 675, 75);
        t.move(2, 675 + 20, 75);
        t.frame();
        t.down(3, a, c[down][0], c[down][1]);
        g = r.state();
        check(g.rx == 0 && g.ry == max, "右摇杆推一点 + C↓：取推得远的（C↓），实际 " + g.rx + ", " + g.ry);
        t.up(3);
        g = r.state();
        check(g.ry == 0 && g.rx > 0, "C↓ 松开：右摇杆那根手指照旧");
        t.releaseAll();
        check(Hid.Gamepad.neutral(r.last) && r.allUp(), "releaseAll：回中立");
    }

    /**
     * 乱序随机序列（照 PassthroughTest）：随机的按下 / 移动 / 抬起 / 一帧 / releaseAll / 换参数，每一步之后
     * 报文里按着的键 = 报过按下还没松开的那些输出，方向键不回中 = 十字键报过按下没松；最后全部抬起后一定是全零、按下都配了松开。
     */
    static void randomSequences() {
        Random rnd = new Random(20261101);
        TouchRouter.Region[] targets = {area, dpad, lstick, rstick, start, lt};
        boolean ok = true;
        String bad = "";
        for (int run = 0; run < 400 && ok; run++) {
            Rec r = new Rec();
            GamepadTouch t = touch(r);
            boolean[] down = new boolean[8];
            for (int step = 0; step < 120 && ok; step++) {
                int slot = rnd.nextInt(8);
                int op = rnd.nextInt(100);
                if (op < 25) {
                    if (down[slot]) t.up(slot);
                    TouchRouter.Region g = targets[rnd.nextInt(targets.length)];
                    float x = g.left + rnd.nextFloat() * (g.right - g.left), y = g.top + rnd.nextFloat() * (g.bottom - g.top);
                    t.down(slot, g, x, y);
                    down[slot] = true;
                } else if (op < 70) {
                    if (down[slot]) t.move(slot, -200 + rnd.nextFloat() * 1400, -200 + rnd.nextFloat() * 500);
                } else if (op < 85) {
                    t.frame();
                } else if (op < 95) {
                    if (down[slot]) t.up(slot);
                    down[slot] = false;
                } else if (op < 98) {
                    t.releaseAll();
                    java.util.Arrays.fill(down, false);
                } else {
                    GamepadConfig c = new GamepadConfig();
                    c.clusterSlide = rnd.nextBoolean();
                    c.dpad8 = rnd.nextBoolean();
                    t.setConfig(c);
                    java.util.Arrays.fill(down, false);
                }
                if (r.negative) {
                    ok = false;
                    bad = "松开比按下多";
                }
                // 键位 = 报过按下没松的输出（摇杆不报按下；十字键另看）
                int[] perOut = new int[Proto.GP_OUTPUTS];
                int dpadHeld = 0;
                for (Map.Entry<Integer, Integer> e : r.held.entrySet()) {
                    int id = e.getKey();
                    if (Proto.gamepadKind(id) == Proto.GP_KIND_DPAD) dpadHeld += e.getValue();
                    else if (Proto.gamepadKind(id) == Proto.GP_KIND_BUTTON) perOut[Proto.gamepadParam(id)] += e.getValue();
                }
                Hid.Gamepad g = r.state();
                for (int o = 0; o < Proto.GP_OUTPUTS && ok; o++) {
                    if (o == 15 || o > Proto.GP_RT || (o > 14 && o < Proto.GP_LT)) continue;
                    if (g.pressed(o) != (perOut[o] > 0)) {
                        ok = false;
                        bad = "第 " + run + " 组第 " + step + " 步：输出 " + o + " 报文 " + g.pressed(o) + "、按下计数 " + perOut[o];
                    }
                }
                if (ok && (g.hat != 0) != (dpadHeld > 0)) {
                    ok = false;
                    bad = "第 " + run + " 组第 " + step + " 步：方向键 " + g.hat + "、十字键按下计数 " + dpadHeld;
                }
            }
            for (int s = 0; s < 8; s++) if (down[s]) t.up(s);
            t.frame();
            if (ok && !(Hid.Gamepad.neutral(r.last) && r.allUp())) {
                ok = false;
                bad = "第 " + run + " 组：全部抬起后不是全零或按下没配松开";
            }
        }
        check(ok, "乱序随机序列（400 组 × 120 步）：报文和按下 / 松开对得上，全部抬起后全零" + (ok ? "" : "：" + bad));
    }

    // ═══════════ 工具 ═══════════

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!actual.equals(expected)) {
            failures++;
            System.err.println("✗ " + what + "：期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
