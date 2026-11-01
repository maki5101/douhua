package dev.douhua;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手柄页自动排（GamepadPlan）的离线测试：主要目标窗口（Fold 内屏分屏下半边约 930 × 350dp）的摆法，
 * 再扫一遍尺寸（手机竖 / 横、Fold 内外屏、分屏上 / 下半、小窗、平板）× 15 个预设 × 几种边距：
 * 不重叠、不出界、不小于最小值（窗口够大时；面键逐颗查，N64 的 C 键、GameCube 的 B / X Y 按真机比例有自己的最小值 GamepadPlan.faceMin）、⋯ 离边够远、左右手区对称、手区贴下沿、报区域的顺序（簇的范围在成员前面）；
 * 第二批的几个预设（N64、GameCube、Wii 经典、MD 3 键、Neo Geo、街机 6 键）摆得对，自定义（随机挑的组合、什么都不要）照样不重叠、不出界。
 */
public final class GamepadPlanTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        mainTarget();
        playstationDpadOnTop();
        gameboyPortrait();
        orderAndIds();
        faceShapes();
        n64();
        gamecube();
        otherPresets();
        emptyCustom();
        sweep();
        customSweep();
        System.err.println("手柄页摆法：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static final float[] E20 = {20, 20, 20, 20};

    static GamepadPlan.Control find(GamepadPlan.Plan p, int role, int output) {
        for (GamepadPlan.Control c : p.controls) if (c.role == role && (output < 0 || c.output == output)) return c;
        return null;
    }

    /** 930 × 350（Fold 内屏分屏下半边）：Xbox 全尺寸；左手摇杆在上靠外、十字键在下靠里，右手面键在上靠外、右摇杆在下靠里；贴下沿。 */
    static void mainTarget() {
        GamepadPlan.Plan p = GamepadPlan.plan(GamepadSpec.byId("xbox"), 930, 350, E20);
        check(p.landscape && p.fitted, "930 × 350：横排、放得下");
        eq(p.faceD, GamepadPlan.FACE_D, "930 × 350：面键全尺寸");
        eq(p.dpad, GamepadPlan.DPAD, "930 × 350：十字键全尺寸");
        eq(p.stick, GamepadPlan.STICK, "930 × 350：摇杆区全尺寸");
        check(p.thumbs, "930 × 350：放得下 LS / RS");
        GamepadPlan.Control ls = find(p, GamepadPlan.ROLE_STICK, 0), rs = find(p, GamepadPlan.ROLE_STICK, 1);
        GamepadPlan.Control dpad = find(p, GamepadPlan.ROLE_DPAD, -1), face = find(p, GamepadPlan.ROLE_CLUSTER, -1);
        check(ls.l < dpad.l && ls.t < dpad.t, "Xbox 左手：摇杆在上靠外、十字键在下靠里");
        check(face.r > rs.r && face.t < rs.t, "Xbox 右手：面键在上靠外、右摇杆在下靠里");
        eq(dpad.b, 350 - GamepadPlan.SIDE, "手区贴下沿");
        eq(rs.b, 350 - GamepadPlan.SIDE, "右摇杆贴下沿");
        GamepadPlan.Control lt = find(p, GamepadPlan.ROLE_TRIGGER, Proto.GP_LT), lb = find(p, GamepadPlan.ROLE_BUMPER, Proto.GP_L1);
        check(lt != null && lb != null && lt.l < lb.l && lt.t == lb.t && lt.t == GamepadPlan.SIDE, "肩键贴上沿、同一排，外角是扳机");
        check(p.menu[1] == 20 && Math.abs((p.menu[0] + p.menu[2]) / 2 - 465) < 0.01f, "⋯ 在顶上正中、离上边 20");

        // 主要目标窗口上每个预设都是全尺寸，每颗面键 = 面键直径 × 它的倍数（N64 的 C 键、GameCube 的 B 是 43.5dp，按真机比例比 44 小一点）
        String off = null;
        for (GamepadSpec s : GamepadSpec.PRESETS) {
            GamepadPlan.Plan q = GamepadPlan.plan(s, 930, 350, E20);
            if (!q.fitted || q.faceD != GamepadPlan.FACE_D) off = off != null ? off : s.id + " 没到全尺寸";
            for (GamepadPlan.Control c : q.controls) {
                float want = GamepadPlan.faceRatio(s.face, c.output) * GamepadPlan.FACE_D;
                if (c.role == GamepadPlan.ROLE_FACE && Math.abs(c.dr - c.dl - want) > 0.01f && off == null) {
                    off = s.id + " 的 " + c.label + " 是 " + (c.dr - c.dl) + "dp，该是 " + want;
                }
            }
        }
        check(off == null, "930 × 350：每个预设全尺寸、每颗面键按自己的倍数" + (off == null ? "" : "（" + off + "）"));
        eq(GamepadPlan.faceMin(GamepadSpec.FACE_N64, Proto.GP_C_UP), GamepadPlan.N64_C * GamepadPlan.FACE_D_MIN, "N64 的 C 键最小值按比例（不是 44）");
        eq(GamepadPlan.faceMin(GamepadSpec.FACE_GC, Proto.GP_SOUTH), GamepadPlan.FACE_D_MIN, "GameCube 的 A 比面键大：最小值照 44");
        eq(GamepadPlan.faceMin(GamepadSpec.FACE_DIAMOND, Proto.GP_SOUTH), GamepadPlan.FACE_D_MIN, "别的面键最小 44");
    }

    static void playstationDpadOnTop() {
        GamepadPlan.Plan p = GamepadPlan.plan(GamepadSpec.byId("playstation"), 930, 350, E20);
        GamepadPlan.Control ls = find(p, GamepadPlan.ROLE_STICK, 0), dpad = find(p, GamepadPlan.ROLE_DPAD, -1);
        check(dpad.l < ls.l && dpad.t < ls.t, "PlayStation 左手：十字键在上靠外、摇杆在下靠里");
        boolean shapes = false;
        for (GamepadPlan.Control c : p.controls) if (GamepadSpec.TRIANGLE.equals(c.label)) shapes = true;
        check(shapes, "PlayStation：面键是 △○×□");
    }

    /** Game Boy 在手机竖屏：竖排，十字键和 B A 在下半截，SELECT START 在最下面中间。 */
    static void gameboyPortrait() {
        GamepadPlan.Plan p = GamepadPlan.plan(GamepadSpec.byId("gameboy"), 360, 780, E20);
        check(!p.landscape && p.fitted, "Game Boy 360 × 780：竖排、放得下");
        GamepadPlan.Control dpad = find(p, GamepadPlan.ROLE_DPAD, -1), face = find(p, GamepadPlan.ROLE_CLUSTER, -1);
        GamepadPlan.Control sel = find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_SELECT), start = find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_START);
        check(dpad.t > 390 && face.t > 390, "Game Boy：控件在下半截");
        check(sel.t > dpad.b && start.t > face.b && sel.l < 180 && start.r > 180, "Game Boy：SELECT START 在最下面中间");
        GamepadPlan.Control b = find(p, GamepadPlan.ROLE_FACE, Proto.GP_SOUTH), a = find(p, GamepadPlan.ROLE_FACE, Proto.GP_EAST);
        check("B".equals(b.label) && "A".equals(a.label) && b.l < a.l && b.t > a.t, "Game Boy：B 左下（发南）、A 右上（发东）");
    }

    /** 报区域的顺序：簇的范围紧跟着它的成员（整个落在范围里）；编号不重复；单键的参数是输出码。 */
    static void orderAndIds() {
        for (GamepadSpec s : GamepadSpec.PRESETS) {
            GamepadPlan.Plan p = GamepadPlan.plan(s, 930, 350, E20);
            Set<Integer> ids = new HashSet<>();
            boolean unique = true, members = true, outputs = true;
            for (int i = 0; i < p.controls.size(); i++) {
                GamepadPlan.Control c = p.controls.get(i);
                if (!ids.add(c.id)) unique = false;
                if (c.role == GamepadPlan.ROLE_CLUSTER) {
                    int n = s.faceKeys().length;
                    for (int k = 1; k <= n; k++) {
                        GamepadPlan.Control m = p.controls.get(i + k);
                        if (m.role != GamepadPlan.ROLE_FACE || m.l < c.l || m.t < c.t || m.r > c.r || m.b > c.b
                                || Proto.gamepadKind(m.id) != Proto.GP_KIND_BUTTON) members = false;
                    }
                }
                if (Proto.gamepadKind(c.id) == Proto.GP_KIND_BUTTON && Proto.gamepadParam(c.id) != c.output) outputs = false;
                if (!Proto.isGamepadId(c.id)) outputs = false;
            }
            check(unique, s.id + "：控件编号不重复");
            check(members, s.id + "：簇的范围紧跟着它的成员、成员整个在范围里");
            check(outputs, s.id + "：单键的编号里是它的输出码");
        }
    }

    /** 每种面键排法：成员的圆互不重叠（大小不一样的照各自的直径算，间隔至少 g）、都在范围里（范围 = 外接框 + CLUSTER_PAD）。 */
    static void faceShapes() {
        int[] faces = {GamepadSpec.FACE_ROW2, GamepadSpec.FACE_DIAG2, GamepadSpec.FACE_DIAMOND, GamepadSpec.FACE_SIX, GamepadSpec.FACE_EIGHT,
                GamepadSpec.FACE_THREE, GamepadSpec.FACE_ARC4, GamepadSpec.FACE_ARCADE6, GamepadSpec.FACE_N64, GamepadSpec.FACE_GC};
        for (int f : faces) {
            for (float g = GamepadPlan.FACE_GAP_MIN; g <= GamepadPlan.FACE_GAP; g += 2) {
                for (float d = 30; d <= 60; d += 2) {
                    float[] size = new float[2];
                    float[][] c = GamepadPlan.faceCenters(f, d, g, size);
                    boolean ok = c.length == GamepadSpec.faceOutputs(f).length;
                    for (int i = 0; i < c.length; i++) {
                        float ri = c[i][2] / 2;
                        if (c[i][0] - ri < GamepadPlan.CLUSTER_PAD - 0.01f || c[i][1] - ri < GamepadPlan.CLUSTER_PAD - 0.01f
                                || c[i][0] + ri > size[0] - GamepadPlan.CLUSTER_PAD + 0.01f || c[i][1] + ri > size[1] - GamepadPlan.CLUSTER_PAD + 0.01f) ok = false;
                        for (int j = i + 1; j < c.length; j++) {
                            if (Math.hypot(c[i][0] - c[j][0], c[i][1] - c[j][1]) < (c[i][2] + c[j][2]) / 2 + g - 0.01f) ok = false;
                        }
                    }
                    if (!ok) {
                        check(false, "面键排法 " + f + "（直径 " + d + "、缝 " + g + "）：圆重叠或出了范围");
                        return;
                    }
                }
            }
            checks++;
        }
        float[] size = new float[2];
        check(GamepadPlan.faceCenters(GamepadSpec.FACE_NONE, 58, 8, size).length == 0 && size[0] == 0, "不要面键：没有成员、范围是空的");
    }

    /** N64：A 南、B 西（在 A 左上）、四颗 C 键（小一点、在 A 右上）发右摇杆推满；Z 在左边外角（左扳机）、L 在里边，右边只有 R；只有 START；没有右摇杆。 */
    static void n64() {
        GamepadSpec s = GamepadSpec.byId("n64");
        GamepadPlan.Plan p = GamepadPlan.plan(s, 930, 350, E20);
        check(p.fitted, "N64 930 × 350：放得下");
        GamepadPlan.Control a = find(p, GamepadPlan.ROLE_FACE, Proto.GP_SOUTH), b = find(p, GamepadPlan.ROLE_FACE, Proto.GP_WEST);
        GamepadPlan.Control cu = find(p, GamepadPlan.ROLE_FACE, Proto.GP_C_UP), cd = find(p, GamepadPlan.ROLE_FACE, Proto.GP_C_DOWN);
        GamepadPlan.Control cl = find(p, GamepadPlan.ROLE_FACE, Proto.GP_C_LEFT), cr = find(p, GamepadPlan.ROLE_FACE, Proto.GP_C_RIGHT);
        check(a != null && "A".equals(a.label) && b != null && "B".equals(b.label), "N64：A 发南、B 发西");
        check(cu != null && cd != null && cl != null && cr != null, "N64：四颗 C 键都在");
        if (a == null || b == null || cu == null || cd == null || cl == null || cr == null) return;
        check(b.r <= a.r && b.t < a.t, "N64：B 在 A 左上");
        check((cl.l + cl.r) / 2 > (a.l + a.r) / 2 - 1 && cd.b < a.b && cu.t < cd.t && cl.l < cr.l, "N64：C 键在 A 右上、排成菱形");
        check(cu.r - cu.l < a.r - a.l, "N64：C 键比 A 小");
        GamepadPlan.Control cluster = find(p, GamepadPlan.ROLE_CLUSTER, -1);
        check(cluster != null && cu.l >= cluster.l && cr.r <= cluster.r && cu.t >= cluster.t, "N64：C 键和 A B 在同一簇里（能搓）");
        GamepadPlan.Control z = find(p, GamepadPlan.ROLE_TRIGGER, Proto.GP_LT), l = find(p, GamepadPlan.ROLE_BUMPER, Proto.GP_L1);
        GamepadPlan.Control r = find(p, GamepadPlan.ROLE_BUMPER, Proto.GP_R1);
        check(z != null && "Z".equals(z.label) && l != null && "L".equals(l.label) && z.l < l.l, "N64：Z 在左边外角、L 在里边");
        check(r != null && "R".equals(r.label) && find(p, GamepadPlan.ROLE_TRIGGER, Proto.GP_RT) == null, "N64：右边只有 R");
        check(find(p, GamepadPlan.ROLE_STICK, 1) == null && find(p, GamepadPlan.ROLE_STICK, 0) != null, "N64：只有左摇杆");
        check(find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_START) != null && find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_SELECT) == null
                && find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_HOME) == null, "N64：中间只有 START");
        check(GamepadPlan.plan(s, 360, 780, E20).fitted, "N64：手机竖屏放得下（不缩到最小值以下）");
    }

    /** GameCube：A 最大居中、B 最小在左下、X 在右、Y 在上；A 南 B 西 X 东 Y 北；左肩只有 L（左扳机），右肩 R（右扳机，外角）+ Z（R1，里边）。 */
    static void gamecube() {
        GamepadPlan.Plan p = GamepadPlan.plan(GamepadSpec.byId("gamecube"), 930, 350, E20);
        check(p.fitted, "GameCube 930 × 350：放得下");
        GamepadPlan.Control a = find(p, GamepadPlan.ROLE_FACE, Proto.GP_SOUTH), b = find(p, GamepadPlan.ROLE_FACE, Proto.GP_WEST);
        GamepadPlan.Control x = find(p, GamepadPlan.ROLE_FACE, Proto.GP_EAST), y = find(p, GamepadPlan.ROLE_FACE, Proto.GP_NORTH);
        if (a == null || b == null || x == null || y == null) {
            check(false, "GameCube：四颗面键都在");
            return;
        }
        check("A".equals(a.label) && "B".equals(b.label) && "X".equals(x.label) && "Y".equals(y.label), "GameCube：A 南、B 西、X 东、Y 北");
        float aw = a.r - a.l, bw = b.r - b.l, xw = x.r - x.l;
        check(aw > xw && xw > bw, "GameCube：A 最大、B 最小");
        float acx = (a.l + a.r) / 2, acy = (a.t + a.b) / 2;
        check((b.l + b.r) / 2 < acx && (b.t + b.b) / 2 > acy, "GameCube：B 在 A 左下");
        check(x.l >= a.r - 0.01f && y.b <= a.t + 0.01f, "GameCube：X 在 A 右边、Y 在 A 上边");
        GamepadPlan.Control l = find(p, GamepadPlan.ROLE_TRIGGER, Proto.GP_LT), r = find(p, GamepadPlan.ROLE_TRIGGER, Proto.GP_RT);
        GamepadPlan.Control z = find(p, GamepadPlan.ROLE_BUMPER, Proto.GP_R1);
        check(l != null && "L".equals(l.label) && find(p, GamepadPlan.ROLE_BUMPER, Proto.GP_L1) == null, "GameCube：左肩只有 L（扳机）");
        check(r != null && "R".equals(r.label) && z != null && "Z".equals(z.label) && r.r > z.r, "GameCube：右肩 R 在外角、Z 在里边");
        check(find(p, GamepadPlan.ROLE_STICK, 1) != null && find(p, GamepadPlan.ROLE_THUMB, -1) == null, "GameCube：有 C 摇杆、摇杆不能按");
    }

    /** Wii 经典（L 外角扳机、ZL 里边、− ⌂ +）、MD 3 键（A B C = 西 南 东、只有 START）、Neo Geo（A B C D = 南 东 西 北）、街机 6 键（8 键的前三列）。 */
    static void otherPresets() {
        GamepadPlan.Plan p = GamepadPlan.plan(GamepadSpec.byId("wii"), 930, 350, E20);
        GamepadPlan.Control l = find(p, GamepadPlan.ROLE_TRIGGER, Proto.GP_LT), zl = find(p, GamepadPlan.ROLE_BUMPER, Proto.GP_L1);
        check(l != null && "L".equals(l.label) && zl != null && "ZL".equals(zl.label) && l.l < zl.l, "Wii 经典：L 在外角（扳机）、ZL 在里边");
        GamepadPlan.Control minus = find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_SELECT), plus = find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_START);
        check(minus != null && "−".equals(minus.label) && plus != null && "+".equals(plus.label), "Wii 经典：中间是 − ⌂ +");
        check(find(p, GamepadPlan.ROLE_THUMB, -1) == null, "Wii 经典：摇杆不能按");

        p = GamepadPlan.plan(GamepadSpec.byId("md3"), 930, 350, E20);
        String[] abc = new String[3];
        int[] wse = {Proto.GP_WEST, Proto.GP_SOUTH, Proto.GP_EAST};
        boolean order = true;
        float lastX = -1;
        for (int i = 0; i < 3; i++) {
            GamepadPlan.Control c = find(p, GamepadPlan.ROLE_FACE, wse[i]);
            abc[i] = c == null ? null : c.label;
            if (c == null || c.l <= lastX) order = false;
            else lastX = c.l;
        }
        check("A".equals(abc[0]) && "B".equals(abc[1]) && "C".equals(abc[2]) && order, "MD 3 键：A B C 从左到右 = 西 南 东");
        check(find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_SELECT) == null && find(p, GamepadPlan.ROLE_MIDDLE, Proto.GP_START) != null, "MD 3 键：只有 START");

        p = GamepadPlan.plan(GamepadSpec.byId("neogeo"), 930, 350, E20);
        int[] sewn = {Proto.GP_SOUTH, Proto.GP_EAST, Proto.GP_WEST, Proto.GP_NORTH};
        String[] want = {"A", "B", "C", "D"};
        boolean ok = true;
        lastX = -1;
        for (int i = 0; i < 4; i++) {
            GamepadPlan.Control c = find(p, GamepadPlan.ROLE_FACE, sewn[i]);
            if (c == null || !want[i].equals(c.label) || c.l <= lastX) ok = false;
            else lastX = c.l;
        }
        check(ok && p.fitted, "Neo Geo：A B C D 从左到右 = 南 东 西 北");

        p = GamepadPlan.plan(GamepadSpec.byId("arcade6"), 930, 350, E20);
        GamepadPlan.Control x = find(p, GamepadPlan.ROLE_FACE, Proto.GP_WEST), a = find(p, GamepadPlan.ROLE_FACE, Proto.GP_SOUTH);
        GamepadPlan.Control rb = find(p, GamepadPlan.ROLE_FACE, Proto.GP_R1), rt = find(p, GamepadPlan.ROLE_FACE, Proto.GP_RT);
        check(x != null && a != null && rb != null && rt != null && x.b <= a.t + 0.01f && x.l > a.l && rb.b <= rt.t + 0.01f
                && "X".equals(x.label) && "RT".equals(rt.label), "街机 6 键：上排 X Y RB、下排 A B RT，上排右移");
        check(find(p, GamepadPlan.ROLE_FACE, Proto.GP_L1) == null && find(p, GamepadPlan.ROLE_FACE, Proto.GP_LT) == null, "街机 6 键：没有 LB LT");
    }

    /** 自定义什么都不要：只剩 ⋯ 和状态字，照样放得下、不闪退。 */
    static void emptyCustom() {
        GamepadSpec.Builder b = new GamepadSpec.Builder(GamepadSpec.byId("xbox").asCustom());
        b.left = GamepadSpec.LEFT_NONE;
        b.face = GamepadSpec.FACE_NONE;
        b.rightStick = b.l1 = b.r1 = b.lt = b.rt = b.select = b.home = b.start = b.thumbs = false;
        for (int[] wh : new int[][]{{930, 350}, {360, 780}, {280, 200}}) {
            GamepadPlan.Plan p = GamepadPlan.plan(b.build(), wh[0], wh[1], E20);
            check(p.controls.isEmpty() && p.fitted && !p.forced, "自定义什么都不要 " + wh[0] + " × " + wh[1] + "：没有控件、放得下");
        }
        b.face = GamepadSpec.FACE_DIAMOND;   // 只要面键：只有右手
        GamepadPlan.Plan p = GamepadPlan.plan(b.build(), 930, 350, E20);
        check(find(p, GamepadPlan.ROLE_CLUSTER, -1) != null && find(p, GamepadPlan.ROLE_DPAD, -1) == null && p.fitted, "自定义只要面键：左手空着、右手有面键");
    }

    /**
     * 扫一遍尺寸：都得在页面里、互不重叠、⋯ 离边够远、左右手区对称；窗口够大时（横排宽 ≥ 640、高 ≥ 340，竖排宽 ≥ 360、高 ≥ 620）
     * 一定放得下、不小于最小值（竖屏时面键簇最小也比半边宽的除外：街机 8 键、街机 6 键、Neo Geo 一排四颗，按比例缩小，要横屏用；
     * Mega Drive 6 键竖屏要 352dp 宽）。
     */
    static void sweep() {
        int plans = 0, fitted = 0, forced = 0;
        List<String> bad = new ArrayList<>();
        float[][] edgeSets = {E20, {12, 12, 12, 12}, {20, 0, 20, 12}};
        for (GamepadSpec s : GamepadSpec.PRESETS) {
            for (float[] e : edgeSets) {
                for (int w = 280; w <= 1400; w += 20) {
                    for (int h = 200; h <= 1300; h += 20) {
                        GamepadPlan.Plan p = GamepadPlan.plan(s, w, h, e);
                        plans++;
                        if (p.fitted) fitted++;
                        if (p.forced) {
                            forced++;
                            if (bad.size() < 5) bad.add(s.id + " " + w + "×" + h + "：放不下（照摆）");
                            continue;
                        }
                        String why = problem(s, p, w, h, e, true);
                        checks++;   // 每一种摆法算一项
                        if (why != null) failures++;
                        if (why != null && bad.size() < 10) bad.add(s.id + " " + w + "×" + h + "：" + why);
                    }
                }
            }
        }
        check(bad.isEmpty(), "扫尺寸：" + (bad.isEmpty() ? "" : String.join("；", bad)));
        eq(forced, 0, "扫尺寸（宽 ≥ 280、高 ≥ 200）：没有放不下只能照摆的");
        System.err.println("  手柄页扫描：" + plans + " 种（预设 × 尺寸 × 边距），放得下不缩到最小值以下的 " + fitted);
    }

    /** 一份摆法有什么问题（null = 没有）。 */
    static String problem(GamepadSpec s, GamepadPlan.Plan p, int w, int h, float[] e, boolean preset) {
        List<float[]> blocks = new ArrayList<>();
        for (int i = 0; i < p.controls.size(); i++) {
            GamepadPlan.Control c = p.controls.get(i);
            if (c.l < -0.01f || c.t < -0.01f || c.r > w + 0.01f || c.b > h + 0.01f) return "出界 " + c.label + " " + c.role;
            if (c.dl < c.l - 0.01f || c.dr > c.r + 0.01f || c.dt < c.t - 0.01f || c.db > c.b + 0.01f) return "画的比判定大";
            if (c.role != GamepadPlan.ROLE_FACE) blocks.add(c.rect());
        }
        blocks.add(p.menu);
        blocks.add(p.status);
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                if (GamepadPlan.overlap(blocks.get(i), blocks.get(j))) return "重叠";
            }
        }
        if (p.menu[0] < e[0] - 0.01f || p.menu[1] < e[1] - 0.01f || p.menu[2] > w - e[2] + 0.01f) return "⋯ 离边不够";
        if (p.status[0] < e[0] - 0.01f || p.status[2] > w - e[2] + 0.01f) return "状态字离边不够";
        float[] lz = p.leftZone, rz = p.rightZone;
        if (Math.abs(lz[0] - (w - rz[2])) > 0.01f || Math.abs(lz[2] - (w - rz[0])) > 0.01f || lz[1] != rz[1] || lz[3] != rz[3]) return "手区不对称";
        if (p.landscape && Math.abs(lz[3] - (h - GamepadPlan.SIDE)) > 0.01f) return "横排手区没贴下沿";
        boolean big = p.landscape ? w >= 640 && h >= 340 : w >= 360 && h >= 620;
        boolean exempt = !p.landscape && faceTooWide(s, w);
        if (preset && big && !exempt && !p.fitted) return "够大却缩到了最小值以下";
        if (p.fitted && (p.faceD < GamepadPlan.FACE_D_MIN - 0.01f || p.dpad < GamepadPlan.DPAD_MIN - 0.01f || p.stick < GamepadPlan.STICK_MIN - 0.01f)) {
            return "放得下却小于最小值";
        }
        if (p.fitted) {
            for (GamepadPlan.Control c : p.controls) {
                if ((c.role == GamepadPlan.ROLE_BUMPER || c.role == GamepadPlan.ROLE_TRIGGER)
                        && (c.r - c.l < GamepadPlan.SH_W_MIN - 0.01f || c.b - c.t < GamepadPlan.SH_H_MIN - 0.01f)) return "肩键小于最小值";
                // 逐颗查面键（不只查 faceD）：N64 的 C 键、GameCube 的 B / X Y 按真机比例比别的小，各自有自己的最小值
                if (c.role == GamepadPlan.ROLE_FACE && c.dr - c.dl < GamepadPlan.faceMin(s.face, c.output) - 0.01f) {
                    return "面键 " + c.label + " 小于它的最小值（" + (c.dr - c.dl) + "dp）";
                }
            }
        }
        return null;
    }

    /** 竖排时面键簇缩到最小值、缝也最小，还是比半边（手区）宽。 */
    static boolean faceTooWide(GamepadSpec s, float w) {
        if (s.face == GamepadSpec.FACE_NONE) return false;
        float[] size = new float[2];
        GamepadPlan.faceCenters(s.face, GamepadPlan.FACE_D_MIN, GamepadPlan.FACE_GAP_MIN, size);
        return size[0] > (w - 2 * GamepadPlan.SIDE - GamepadPlan.GAP) / 2 + 0.01f;
    }

    /**
     * 自定义：随机挑一批组合（左手 × 面键 × 字样 × 右摇杆 × 肩键 × 扳机 × 中间 × 摇杆按下），扫一遍尺寸（粗一点）：
     * 照样不重叠、不出界、⋯ 离边够远、左右手区对称；不要求"够大就不缩"（随便组合出来的可能本来就挤）。报区域的编号不重复。
     */
    static void customSweep() {
        java.util.Random rnd = new java.util.Random(20261101);
        int plans = 0, forced = 0;
        List<String> bad = new ArrayList<>();
        for (int n = 0; n < 240; n++) {
            GamepadSpec.Builder b = new GamepadSpec.Builder(GamepadSpec.PRESETS[rnd.nextInt(GamepadSpec.PRESETS.length)].asCustom());
            b.left = rnd.nextInt(5);
            b.face = rnd.nextInt(11);
            b.labels = rnd.nextInt(6);
            b.rightStick = rnd.nextBoolean();
            b.l1 = b.r1 = rnd.nextBoolean();
            b.lt = b.rt = rnd.nextBoolean();
            b.select = rnd.nextBoolean();
            b.home = rnd.nextBoolean();
            b.start = rnd.nextBoolean();
            b.thumbs = rnd.nextBoolean();
            GamepadSpec s = b.build();
            for (int w = 280; w <= 1400; w += 70) {
                for (int h = 200; h <= 1300; h += 70) {
                    GamepadPlan.Plan p = GamepadPlan.plan(s, w, h, E20);
                    plans++;
                    if (p.forced) {
                        forced++;
                        if (bad.size() < 5) bad.add(s.encode() + " " + w + "×" + h + "：放不下（照摆）");
                        continue;
                    }
                    String why = problem(s, p, w, h, E20, false);
                    Set<Integer> ids = new HashSet<>();
                    for (GamepadPlan.Control c : p.controls) if (!ids.add(c.id)) why = "编号重复";
                    checks++;
                    if (why != null) failures++;
                    if (why != null && bad.size() < 10) bad.add(s.encode() + " " + w + "×" + h + "：" + why);
                }
            }
        }
        check(bad.isEmpty(), "自定义扫尺寸：" + (bad.isEmpty() ? "" : String.join("；", bad)));
        eq(forced, 0, "自定义扫尺寸：没有放不下只能照摆的");
        System.err.println("  手柄页自定义扫描：" + plans + " 种（240 套随机组合 × 尺寸）");
    }

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
