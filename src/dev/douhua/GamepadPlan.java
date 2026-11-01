package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 手柄页怎么摆（自动排）：输入页面大小（dp）、组件清单（{@link GamepadSpec}）、⋯ 和状态字离页面各边至少多远（dp，EdgeMargin.edges 换算好的）；
 * 输出每个控件的判定范围和画的范围、⋯ 和状态字在哪。纯 Java（离线测，GamepadPlanTest 扫一遍尺寸锁住"不重叠、不出界、不小于最小值（面键逐颗查，N64 的 C 键、GameCube 的 B / X Y 有自己的最小值 {@link #faceMin}）、
 * ⋯ 离边够远、左右手区对称、手区贴下沿"）。坐标都是 dp、页面左上角为原点；GamepadScreen 乘 density 画、报区域。
 *
 * 主要目标是 Fold 内屏分屏的下半边（约 930 × 350dp，Moonlight 在上、豆花在下）。宽 ≥ 1.3 × 高按横排，不然竖排（Game Boy 式：控件在下半截）。
 * - 横排：两只手各一块手区（左右对称，各约 36% 宽，至少放得下自己的控件），中间放 Select / Home / Start（下面一排 LS / RS），顶上正中 ⋯ 和状态字；
 *   肩键贴上沿两角（两对时同一排，外角是扳机、靠里是肩键）；手区高度封顶 {@link #BAND_MAX}、贴着下沿（拇指够得着的是下半截），多出来的高留在上面。
 * - 竖排：手区是左右两半、贴着中间那排（Select / Start…，最下面）上方；肩键在手区上面；⋯ 和状态字在上半截。
 * - 一只手放两个控件时斜着错开：上面那个靠外、下面那个靠里（Xbox 摇杆在上，PlayStation 十字键在上）；放得下就并排错开，放不下上下叠。
 * - 放不下：先缩面键之间的缝，再整体按比例缩（每样到各自的最小值为止），再放不下就不放 LS / RS；还不行就全部按比例缩到最小值以下
 *   （{@link Plan#fitted} 为假，只保证不重叠、不出界）。
 * - 第二批：面键多了几种排法（MD 3 键、Neo Geo 弧形、街机 6 键、N64 带 C 键、GameCube 大小不一的四颗，{@link #faceCenters} 给每颗的直径）；
 *   自定义可以一只手什么都不放（手区照样左右对称留着）；两边肩键颗数可以不一样（N64、GameCube），按多的那边留地方。
 * 不自动转屏（方向锁定锁整块屏、分屏里转不了）：Game Boy 偏好竖屏，横着时照样横排。
 */
final class GamepadPlan {
    /** 控件离页面左、右、下边（横排时也离上边）。 */
    static final float SIDE = 16;
    /** 控件之间至少隔多远。 */
    static final float GAP = 8;
    /** 面键直径 / 最小、面键之间的缝 / 最小。 */
    static final float FACE_D = 58, FACE_D_MIN = 44, FACE_GAP = 8, FACE_GAP_MIN = 4;
    /** 面键簇的范围 = 成员外接框四周再留这么多（按在缝外一点也算这一簇）。 */
    static final float CLUSTER_PAD = 8;
    /** 十字键见方 / 最小。 */
    static final float DPAD = 136, DPAD_MIN = 112;
    /** 摇杆区见方 / 最小（推满的行程在 GamepadConfig.stickRadiusDp）。 */
    static final float STICK = 150, STICK_MIN = 120;
    /** 肩键 / 最小。 */
    static final float SH_W = 88, SH_H = 44, SH_W_MIN = 64, SH_H_MIN = 40;
    /** Select / Home / Start：判定范围、画出来的大小。 */
    static final float MID_W = 64, MID_H = 44, MID_DRAW_W = 56, MID_DRAW_H = 32;
    /** 竖排中间那几颗（只在横排的中间那块放不下一排时）之间的缝。 */
    static final float MID_COL_GAP = 4;
    /** LS / RS。 */
    static final float THUMB_W = 44, THUMB_H = 36;
    /** ⋯（同触控板顶上的：56 × 36 的触摸范围，不带框）。 */
    static final float MENU_W = 56, MENU_H = 36;
    /** 状态字那一行的高。 */
    static final float STATUS_H = 20;
    /** 手区最高（贴下沿，多出来的高留在上面）。 */
    static final float BAND_MAX = 360;
    /** 宽 ≥ 这么多倍的高按横排。 */
    static final float LANDSCAPE_RATIO = 1.3f;
    /** 横排时每块手区至少这么宽（页面宽的比例），放得下就照这个给，中间留得多。 */
    static final float ZONE_SHARE = 0.36f;
    /** Game Boy 两颗面键斜着排的角度（度，B 左下、A 右上）。 */
    static final float DIAG_DEG = 25;
    /** Mega Drive 6 键每往右一颗抬高多少（面键直径的比例）。 */
    static final float SIX_SLANT = 0.2f;

    // 控件的用途（GamepadScreen 按它画）
    static final int ROLE_CLUSTER = 0, ROLE_FACE = 1, ROLE_DPAD = 2, ROLE_STICK = 3, ROLE_BUMPER = 4, ROLE_TRIGGER = 5,
            ROLE_MIDDLE = 6, ROLE_THUMB = 7;

    /** 一个控件：要报的区域（Proto.REGION_GAMEPAD）。 */
    static final class Control {
        final int id, role;
        /** 键帽上的字（簇、十字键、摇杆没有）。 */
        final String label;
        /** 单键 / 簇成员：输出码；十字键：方向数；摇杆：0 左、1 右；簇：第几簇。 */
        final int output;
        /** 判定范围（dp，页面坐标），画的范围（单键可以比判定小）。 */
        final float l, t, r, b, dl, dt, dr, db;

        Control(int id, int role, String label, int output, float l, float t, float r, float b, float dl, float dt, float dr, float db) {
            this.id = id;
            this.role = role;
            this.label = label;
            this.output = output;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.dl = dl;
            this.dt = dt;
            this.dr = dr;
            this.db = db;
        }

        float[] rect() {
            return new float[]{l, t, r, b};
        }
    }

    static final class Plan {
        /** 报区域的顺序：簇的范围在它的成员前面，然后十字键、摇杆、肩键、中间那排、LS / RS。⋯ 不在里面（MainActivity 报在最前面）。 */
        final List<Control> controls = new ArrayList<>();
        /** ⋯ 的触摸范围、状态字那一行（dp，页面坐标）。 */
        float[] menu, status;
        /** 左右手区（测试查对称、贴下沿用）。 */
        float[] leftZone, rightZone;
        boolean landscape;
        /**
         * 都不小于最小值（假 = 窗口太小，按比例缩到了最小值以下）。面键是 {@link #faceD} ≥ FACE_D_MIN；N64 的 C 键、GameCube 的 B / X Y
         * 按真机比例比它小，各自的最小值见 {@link GamepadPlan#faceMin}（不是 44）。
         */
        boolean fitted;
        /** 小到缩到最小值的两成都放不下：照摆、不再检查（可能重叠）。 */
        boolean forced;
        /** 面键直径、十字键、摇杆区多大（dp），放没放 LS / RS。 */
        float faceD, dpad, stick;
        boolean thumbs;
    }

    private GamepadPlan() {}

    /** 一次尝试用的尺寸。 */
    private static final class Sizes {
        float d, g, dpad, stick, shW, shH, midW, midH, midDW, midDH, thumbW, thumbH, menuW, menuH;
        boolean thumbs;

        static Sizes scaled(float k, float faceGap, boolean thumbs) {
            Sizes z = new Sizes();
            z.d = Math.max(FACE_D_MIN, FACE_D * k);
            z.g = faceGap;
            z.dpad = Math.max(DPAD_MIN, DPAD * k);
            z.stick = Math.max(STICK_MIN, STICK * k);
            z.shW = Math.max(SH_W_MIN, SH_W * k);
            z.shH = Math.max(SH_H_MIN, SH_H * k);
            z.midW = MID_W;
            z.midH = MID_H;
            z.midDW = MID_DRAW_W;
            z.midDH = MID_DRAW_H;
            z.thumbW = THUMB_W;
            z.thumbH = THUMB_H;
            z.menuW = MENU_W;
            z.menuH = MENU_H;
            z.thumbs = thumbs;
            return z;
        }

        /** 窗口太小：全部按最小值再乘 s（s < 1，都小于最小值了）。 */
        static Sizes squeezed(float s) {
            Sizes z = scaled(0, FACE_GAP_MIN, false);
            z.d *= s;
            z.g *= s;
            z.dpad *= s;
            z.stick *= s;
            z.shW *= s;
            z.shH *= s;
            z.midW *= s;
            z.midH *= s;
            z.midDW *= s;
            z.midDH *= s;
            z.menuW = Math.min(MENU_W, MENU_W * Math.max(s, 0.6f));
            z.menuH = Math.min(MENU_H, MENU_H * Math.max(s, 0.6f));
            return z;
        }
    }

    /** edges = ⋯ 和状态字离页面左、上、右、下边至少多远（dp）。 */
    static Plan plan(GamepadSpec spec, float w, float h, float[] edges) {
        float[] e = edges != null ? edges : new float[]{20, 20, 20, 20};
        boolean[] thumbChoices = {true, false};
        for (boolean thumbs : thumbChoices) {
            if (!thumbs && spec.leftThumb() == null && spec.rightThumb() == null) break;   // 本来就没有：不用再试一遍
            for (float g = FACE_GAP; g >= FACE_GAP_MIN; g -= 2) {   // 先缩缝
                Plan p = tryLayout(spec, w, h, e, Sizes.scaled(1, g, thumbs));
                if (p != null) return done(p, true);
            }
            for (float k = 0.975f; k >= 0.7f; k -= 0.025f) {   // 再整体按比例缩（各自到最小值为止）
                Plan p = tryLayout(spec, w, h, e, Sizes.scaled(k, FACE_GAP_MIN, thumbs));
                if (p != null) return done(p, true);
            }
        }
        for (float s = 0.95f; s >= 0.2f; s -= 0.05f) {   // 窗口太小：缩到最小值以下
            Plan p = tryLayout(spec, w, h, e, Sizes.squeezed(s));
            if (p != null) return done(p, false);
        }
        Plan p = tryLayout(spec, w, h, e, Sizes.squeezed(0.2f), true);   // 实在太小：照摆，不再检查
        p.forced = true;
        return done(p, false);
    }

    private static Plan done(Plan p, boolean fitted) {
        p.fitted = fitted;
        return p;
    }

    private static Plan tryLayout(GamepadSpec spec, float w, float h, float[] e, Sizes z) {
        return tryLayout(spec, w, h, e, z, false);
    }

    // ═══════════ 一只手里的控件 ═══════════

    private static final int ITEM_FACE = 0, ITEM_DPAD = 1, ITEM_STICK_L = 2, ITEM_STICK_R = 3;

    /** 手区里的一个控件：多大，摆好后在哪。面键簇的大小是范围的大小（外接框四周 CLUSTER_PAD）。 */
    private static final class Item {
        final int type;
        final float w, h;
        float x, y;

        Item(int type, float w, float h) {
            this.type = type;
            this.w = w;
            this.h = h;
        }
    }

    /** 左手：一个或两个（上面的在前）。 */
    private static List<Item> leftItems(GamepadSpec spec, Sizes z) {
        List<Item> l = new ArrayList<>();
        Item dpad = new Item(ITEM_DPAD, z.dpad, z.dpad);
        Item stick = new Item(ITEM_STICK_L, z.stick, z.stick);
        switch (spec.left) {
            case GamepadSpec.LEFT_STICK: l.add(stick); break;
            case GamepadSpec.LEFT_STICK_TOP: l.add(stick); l.add(dpad); break;
            case GamepadSpec.LEFT_DPAD_TOP: l.add(dpad); l.add(stick); break;
            case GamepadSpec.LEFT_NONE: break;
            default: l.add(dpad); break;
        }
        return l;
    }

    /** 右手：面键（上靠外）、右摇杆（下靠里）。 */
    private static List<Item> rightItems(GamepadSpec spec, Sizes z) {
        List<Item> l = new ArrayList<>();
        if (spec.face != GamepadSpec.FACE_NONE) {
            float[] size = new float[2];
            faceCenters(spec.face, z.d, z.g, size);
            l.add(new Item(ITEM_FACE, size[0], size[1]));
        }
        if (spec.rightStick) l.add(new Item(ITEM_STICK_R, z.stick, z.stick));
        return l;
    }

    /**
     * N64 的 C 键、GameCube 的 A / B / X Y 比别的面键大 / 小多少（直径的倍数，按真机比例）。小的那几颗跟着面键直径按比例缩，
     * **不受 {@link #FACE_D_MIN} 的约束**，各自的最小值是 倍数 × FACE_D_MIN（C 键、B 33dp，X Y 约 37dp，{@link #faceMin}）：
     * 也给它们定 44 的下限的话，C 键的菱形会压到 A（要重新排）、N64 在手机竖屏的半边放不下，整页缩到最小值以下、A 也跟着变小。
     * 全尺寸（主要目标窗口 930 × 350）时 C 键、B 是 43.5dp，手机竖屏约 35 / 37dp，拇指按不按得准要真机看。
     */
    static final float N64_C = 0.75f, GC_A = 1.25f, GC_B = 0.75f, GC_XY = 0.85f;

    /** 这一颗面键是面键直径的几倍（N64 的 C 键、GameCube 的四颗；别的都是 1）。 */
    static float faceRatio(int face, int output) {
        if (face == GamepadSpec.FACE_N64) {
            switch (output) {
                case Proto.GP_C_UP: case Proto.GP_C_DOWN: case Proto.GP_C_LEFT: case Proto.GP_C_RIGHT: return N64_C;
                default: return 1;
            }
        }
        if (face == GamepadSpec.FACE_GC) {
            switch (output) {
                case Proto.GP_SOUTH: return GC_A;
                case Proto.GP_WEST: return GC_B;
                default: return GC_XY;
            }
        }
        return 1;
    }

    /** 这一颗面键放得下（{@link Plan#fitted}）时至少多大：比面键大的照 FACE_D_MIN，小的按比例（见 {@link #N64_C}）。 */
    static float faceMin(int face, int output) {
        return Math.min(1, faceRatio(face, output)) * FACE_D_MIN;
    }

    /**
     * 面键各颗的 {圆心 x, 圆心 y, 直径}（相对簇范围的左上角，按 GamepadSpec.faceKeys 的顺序），范围的 {宽, 高} 写进 size。
     * d = 面键直径，g = 相邻两颗之间至少隔多远（N64 的 C 键、GameCube 的几颗大小不一样，间隔照样至少 g）。
     */
    static float[][] faceCenters(int face, float d, float g, float[] size) {
        float p = CLUSTER_PAD, r = d / 2;
        float[][] c;
        float bw, bh;
        switch (face) {
            case GamepadSpec.FACE_ROW2: {
                float span = d + 2 * g;   // NES 的两颗隔得开一点
                c = new float[][]{{p + r, p + r, d}, {p + r + span, p + r, d}};
                bw = d + span;
                bh = d;
                break;
            }
            case GamepadSpec.FACE_DIAG2: {
                float span = d + 1.5f * g;
                float dx = (float) (span * Math.cos(Math.toRadians(DIAG_DEG))), dy = (float) (span * Math.sin(Math.toRadians(DIAG_DEG)));
                c = new float[][]{{p + r, p + r + dy, d}, {p + r + dx, p + r, d}};   // B 左下、A 右上
                bw = d + dx;
                bh = d + dy;
                break;
            }
            case GamepadSpec.FACE_SIX: {
                float span = d + g, s = SIX_SLANT * d;
                c = new float[6][];
                for (int i = 0; i < 3; i++) {
                    c[i] = new float[]{p + r + i * span, p + r + span + (2 - i) * s, d};       // 下排 A B C
                    c[3 + i] = new float[]{p + r + i * span, p + r + (2 - i) * s, d};          // 上排 X Y Z
                }
                bw = d + 2 * span;
                bh = d + span + 2 * s;
                break;
            }
            case GamepadSpec.FACE_EIGHT: {
                float span = d + g;
                c = new float[8][];
                for (int i = 0; i < 4; i++) {
                    c[i] = new float[]{p + r + span / 2 + i * span, p + r, d};                // 上排（右移半格）
                    c[4 + i] = new float[]{p + r + i * span, p + r + span, d};                // 下排
                }
                bw = d + 3.5f * span;
                bh = d + span;
                break;
            }
            case GamepadSpec.FACE_THREE: {   // Mega Drive 3 键：A B C 一排、往右上斜（同 6 键的下排）
                float span = d + g, s = SIX_SLANT * d;
                c = new float[3][];
                for (int i = 0; i < 3; i++) c[i] = new float[]{i * span, (2 - i) * s, d};
                return normalized(c, size);
            }
            case GamepadSpec.FACE_ARC4: {   // Neo Geo：四颗一道弧，中间两颗高一点（拇指扫过去的弧）[估]
                float span = d + g, arch = 0.3f * d;
                c = new float[4][];
                for (int i = 0; i < 4; i++) {
                    float k = (i - 1.5f) / 1.5f;
                    c[i] = new float[]{i * span, arch * k * k, d};
                }
                return normalized(c, size);
            }
            case GamepadSpec.FACE_ARCADE6: {   // 两排各 3 颗、上排右移半格（同 8 键的前三列）
                float span = d + g;
                c = new float[6][];
                for (int i = 0; i < 3; i++) {
                    c[i] = new float[]{span / 2 + i * span, 0, d};          // 上排
                    c[3 + i] = new float[]{i * span, span, d};              // 下排
                }
                return normalized(c, size);
            }
            case GamepadSpec.FACE_N64: {
                // A 在下；B 在 A 左上；C 键（小一点的菱形）在 A 右上。几个比例按"最小尺寸下手机竖屏的半边放得下"挑的 [估]
                float u = d + g, dc = N64_C * d, rr = (float) ((dc + g) / Math.sqrt(2));
                float cx = 0.75f * u, cy = -1.2f * u;
                c = new float[][]{{0, 0, d}, {-0.75f * u, -0.7f * u, d},
                        {cx, cy - rr, dc}, {cx - rr, cy, dc}, {cx + rr, cy, dc}, {cx, cy + rr, dc}};   // A B C↑ C← C→ C↓
                return normalized(c, size);
            }
            case GamepadSpec.FACE_GC: {
                // 大的 A 在中间；B 小、在左下；X 在右（略高）、Y 在上（略左）
                float da = GC_A * d, db = GC_B * d, dx = GC_XY * d;
                float ra = (da + dx) / 2 + g, rb = (da + db) / 2 + g;
                c = new float[][]{{0, 0, da}, polar(rb, 215, db), polar(ra, 5, dx), polar(ra, 95, dx)};   // A B X Y
                return normalized(c, size);
            }
            case GamepadSpec.FACE_NONE:
                size[0] = size[1] = 0;
                return new float[0][];
            case GamepadSpec.FACE_DIAMOND:
            default: {
                float rr = (float) ((d + g) / Math.sqrt(2));   // 相邻两颗圆心距 = d + g
                float m = p + rr + r;
                c = new float[][]{{m, m - rr, d}, {m - rr, m, d}, {m + rr, m, d}, {m, m + rr, d}};   // 北 西 东 南
                bw = bh = 2 * rr + d;
                break;
            }
        }
        size[0] = bw + 2 * p;
        size[1] = bh + 2 * p;
        return c;
    }

    /** 离原点 dist、角度 deg（0 = 右、90 = 上）的一颗，直径 dia。 */
    private static float[] polar(float dist, float deg, float dia) {
        double a = Math.toRadians(deg);
        return new float[]{(float) (dist * Math.cos(a)), (float) (-dist * Math.sin(a)), dia};
    }

    /** 随手摆的几颗挪到范围里：外接框贴着 CLUSTER_PAD，范围大小写进 size。 */
    private static float[][] normalized(float[][] c, float[] size) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] k : c) {
            float r = k[2] / 2;
            minX = Math.min(minX, k[0] - r);
            minY = Math.min(minY, k[1] - r);
            maxX = Math.max(maxX, k[0] + r);
            maxY = Math.max(maxY, k[1] + r);
        }
        for (float[] k : c) {
            k[0] += CLUSTER_PAD - minX;
            k[1] += CLUSTER_PAD - minY;
        }
        size[0] = maxX - minX + 2 * CLUSTER_PAD;
        size[1] = maxY - minY + 2 * CLUSTER_PAD;
        return c;
    }

    /** 横排：手区高 H 里要多宽（放不下返回 -1）。两个控件：上下叠得下就按叠着算（最窄），不然并排。 */
    private static float zoneWidth(List<Item> items, float H) {
        if (items.isEmpty()) return 0;
        if (items.size() == 1) return items.get(0).h <= H ? items.get(0).w : -1;
        Item u = items.get(0), lo = items.get(1);
        if (u.h + GAP + lo.h <= H) return Math.max(u.w, lo.w);
        if (Math.max(u.h, lo.h) <= H) return u.w + GAP + lo.w;
        return -1;
    }

    /** 竖排：手区宽 W 里要多高（放不下返回 -1）。两个控件：并排放得下就并排（最矮），不然上下叠。 */
    private static float zoneHeight(List<Item> items, float W) {
        if (items.isEmpty()) return 0;
        if (items.size() == 1) return items.get(0).w <= W ? items.get(0).h : -1;
        Item u = items.get(0), lo = items.get(1);
        if (u.w + GAP + lo.w <= W) return Math.max(u.h, lo.h);
        if (Math.max(u.w, lo.w) <= W) return u.h + GAP + lo.h;
        return -1;
    }

    /**
     * 在手区 [zl, zt, zr, zb] 里摆一只手的控件。outerLeft = 外侧在左边（左手）。
     * 没有：什么都不做。一个：居中。两个：并排放得下就上面的靠外、下面的靠里，下面的贴底、上面的抬高下面那个的一半（斜着错开）；不然上下叠（下面的贴底）。
     */
    private static boolean place(List<Item> items, float zl, float zt, float zr, float zb, boolean outerLeft) {
        float W = zr - zl, H = zb - zt;
        if (items.isEmpty()) return true;   // 这只手什么都不放（自定义）
        if (items.size() == 1) {
            Item it = items.get(0);
            if (it.w > W + 0.01f || it.h > H + 0.01f) return false;
            it.x = zl + (W - it.w) / 2;
            it.y = zt + (H - it.h) / 2;
            return true;
        }
        Item u = items.get(0), lo = items.get(1);
        u.x = outerLeft ? zl : zr - u.w;
        lo.x = outerLeft ? zr - lo.w : zl;
        lo.y = zb - lo.h;
        if (u.w + GAP + lo.w <= W + 0.01f) {
            if (Math.max(u.h, lo.h) > H + 0.01f) return false;
            u.y = Math.max(zt, zb - lo.h / 2 - u.h);
            return true;
        }
        if (Math.max(u.w, lo.w) > W + 0.01f || u.h + GAP + lo.h > H + 0.01f) return false;
        u.y = lo.y - GAP - u.h;
        return true;
    }

    // ═══════════ 摆 ═══════════

    private static Plan tryLayout(GamepadSpec spec, float w, float h, float[] e, Sizes z, boolean force) {
        boolean landscape = w >= LANDSCAPE_RATIO * h;
        List<Item> left = leftItems(spec, z), right = rightItems(spec, z);
        GamepadSpec.Key[] ls = spec.leftShoulders(), rs = spec.rightShoulders(), mid = spec.middle();
        GamepadSpec.Key lt = z.thumbs ? spec.leftThumb() : null, rt = z.thumbs ? spec.rightThumb() : null;
        // 肩键一侧要多宽（两对时外角是扳机、靠里是肩键，同一排）；N64、GameCube 两边颗数不一样，按多的那边算
        int shMost = Math.max(ls.length, rs.length);
        float shBlock = shMost == 0 ? 0 : shMost * z.shW + (shMost - 1) * GAP;

        float[] menu = new float[4];
        menu[0] = Math.max(e[0], Math.min((w - z.menuW) / 2, w - e[2] - z.menuW));
        menu[1] = e[1];
        menu[2] = menu[0] + z.menuW;
        menu[3] = menu[1] + z.menuH;

        Plan p = new Plan();
        p.landscape = landscape;
        p.menu = menu;
        p.faceD = z.d;
        p.dpad = z.dpad;
        p.stick = z.stick;
        p.thumbs = lt != null || rt != null;
        List<float[]> others = new ArrayList<>();   // 除了手区控件以外要互不重叠的：⋯、状态字、肩键、中间那排、LS / RS
        others.add(menu);

        float[][] leftSh = new float[ls.length][], rightSh = new float[rs.length][];
        float[][] midRects = new float[mid.length][];
        float[] ltRect = null, rtRect = null;
        float[] status;
        float zoneW, bandTop, bandBottom;
        if (landscape) {
            float shBottom = shMost == 0 ? 0 : SIDE + z.shH;
            bandBottom = h - SIDE;
            bandTop = Math.max(shMost == 0 ? SIDE : shBottom + GAP, bandBottom - BAND_MAX);
            float bandH = bandBottom - bandTop;
            float needL = zoneWidth(left, bandH), needR = zoneWidth(right, bandH);
            if (!force && (needL < 0 || needR < 0)) return null;
            // 中间那块至少多宽：Select / Home / Start 竖着一列（一颗宽）、LS / RS 并排
            int thumbCount = (lt != null ? 1 : 0) + (rt != null ? 1 : 0);
            float midNeed = Math.max(mid.length > 0 ? z.midW : 0, thumbCount * z.thumbW + Math.max(0, thumbCount - 1) * GAP);
            float avail = (w - 2 * SIDE - 2 * GAP - midNeed) / 2;
            float need = Math.max(Math.max(needL, needR), shBlock);
            if (!force && need > avail + 0.01f) return null;
            zoneW = Math.max(need, Math.min(avail, ZONE_SHARE * w - SIDE));
            float midL = SIDE + zoneW + GAP, midR = w - SIDE - zoneW - GAP;
            // 状态字：⋯ 下面一行，横着只在中间那块里，碰到肩键就让开
            float st = menu[3] + 2;
            float sl = Math.max(midL, e[0]), sr = Math.min(midR, w - e[2]);
            status = new float[]{sl, st, sr, st + STATUS_H};
            // 中间那排：Select / Home / Start 一排放得下就一排，不然竖着一列；下面 LS 靠左、RS 靠右
            float rowW = mid.length * z.midW + Math.max(0, mid.length - 1) * GAP;
            boolean row = rowW <= midR - midL + 0.01f;
            float blockH = mid.length == 0 ? 0 : row ? z.midH : mid.length * z.midH + (mid.length - 1) * MID_COL_GAP;
            if (thumbCount > 0) blockH += (blockH > 0 ? GAP : 0) + z.thumbH;
            float top = Math.max(status[3] + GAP, bandTop), room = bandBottom - top;
            if (!force && blockH > room + 0.01f) return null;
            float y = top + Math.max(0, room - blockH) / 2;
            for (int i = 0; i < mid.length; i++) {
                float x, yy;
                if (row) {
                    x = (w - rowW) / 2 + i * (z.midW + GAP);
                    yy = y;
                } else {
                    x = (w - z.midW) / 2;
                    yy = y + i * (z.midH + MID_COL_GAP);
                }
                midRects[i] = new float[]{x, yy, x + z.midW, yy + z.midH};
            }
            float ty = y + blockH - z.thumbH;
            if (lt != null) ltRect = new float[]{midL, ty, midL + z.thumbW, ty + z.thumbH};
            if (rt != null) rtRect = new float[]{midR - z.thumbW, ty, midR, ty + z.thumbH};
            for (int i = 0; i < ls.length; i++) {
                float x = SIDE + i * (z.shW + GAP);
                leftSh[i] = new float[]{x, SIDE, x + z.shW, SIDE + z.shH};
            }
            for (int i = 0; i < rs.length; i++) {
                float x = w - SIDE - z.shW - i * (z.shW + GAP);
                rightSh[i] = new float[]{x, SIDE, x + z.shW, SIDE + z.shH};
            }
        } else {
            zoneW = (w - 2 * SIDE - GAP) / 2;
            // 最下面一排：[LS] Select Home Start [RS]
            int n = mid.length + (lt != null ? 1 : 0) + (rt != null ? 1 : 0);
            float rowW = mid.length * z.midW + (lt != null ? z.thumbW : 0) + (rt != null ? z.thumbW : 0) + Math.max(0, n - 1) * GAP;
            if (!force && rowW > w - 2 * SIDE + 0.01f) return null;
            float rowTop = h - SIDE - z.midH;
            float x = (w - rowW) / 2;
            float ty = rowTop + (z.midH - z.thumbH) / 2;
            if (lt != null) {
                ltRect = new float[]{x, ty, x + z.thumbW, ty + z.thumbH};
                x += z.thumbW + GAP;
            }
            for (int i = 0; i < mid.length; i++) {
                midRects[i] = new float[]{x, rowTop, x + z.midW, rowTop + z.midH};
                x += z.midW + GAP;
            }
            if (rt != null) rtRect = new float[]{x, ty, x + z.thumbW, ty + z.thumbH};
            bandBottom = n > 0 ? rowTop - GAP : h - SIDE;
            float needL = zoneHeight(left, zoneW), needR = zoneHeight(right, zoneW);
            if (!force && (needL < 0 || needR < 0)) return null;
            bandTop = bandBottom - Math.min(BAND_MAX, Math.max(Math.max(needL, needR), 0));
            float controlsTop = bandTop;
            if (shMost > 0) {
                if (!force && shBlock > zoneW + 0.01f) return null;
                float sy = bandTop - GAP - z.shH;
                for (int i = 0; i < ls.length; i++) {
                    float sx = SIDE + i * (z.shW + GAP);
                    leftSh[i] = new float[]{sx, sy, sx + z.shW, sy + z.shH};
                }
                for (int i = 0; i < rs.length; i++) {
                    float sx = w - SIDE - z.shW - i * (z.shW + GAP);
                    rightSh[i] = new float[]{sx, sy, sx + z.shW, sy + z.shH};
                }
                controlsTop = sy;
            }
            float st = menu[3] + 2;
            status = new float[]{Math.max(SIDE, e[0]), st, Math.min(w - SIDE, w - e[2]), st + STATUS_H};
            if (!force && status[3] + GAP > controlsTop + 0.01f) return null;
        }
        float[] lz = {SIDE, bandTop, SIDE + zoneW, bandBottom};
        float[] rz = {w - SIDE - zoneW, bandTop, w - SIDE, bandBottom};
        if (!place(left, lz[0], lz[1], lz[2], lz[3], true) && !force) return null;
        if (!place(right, rz[0], rz[1], rz[2], rz[3], false) && !force) return null;
        p.leftZone = lz;
        p.rightZone = rz;
        p.status = status;
        others.add(status);

        // ── 生成控件（报区域的顺序）──
        int[] copies = new int[Proto.GP_OUTPUTS];
        List<float[]> blocks = new ArrayList<>();   // 手区控件（簇按范围算）
        int clusterIndex = 0;
        List<List<Item>> hands = Arrays.asList(left, right);
        for (List<Item> hand : hands) {
            for (Item it : hand) {
                if (it.type != ITEM_FACE) continue;
                float[] size = new float[2];
                float[][] centers = faceCenters(spec.face, z.d, z.g, size);
                p.controls.add(new Control(Proto.gamepadId(Proto.GP_KIND_CLUSTER, 0, clusterIndex), ROLE_CLUSTER, null, clusterIndex,
                        it.x, it.y, it.x + it.w, it.y + it.h, it.x, it.y, it.x + it.w, it.y + it.h));
                clusterIndex++;
                GamepadSpec.Key[] keys = spec.faceKeys();
                for (int i = 0; i < keys.length && i < centers.length; i++) {
                    float cx = it.x + centers[i][0], cy = it.y + centers[i][1], r = centers[i][2] / 2;
                    int o = keys[i].output;
                    int id = Proto.gamepadId(Proto.GP_KIND_BUTTON, copies[o]++, o);
                    p.controls.add(new Control(id, ROLE_FACE, keys[i].label, o, cx - r, cy - r, cx + r, cy + r, cx - r, cy - r, cx + r, cy + r));
                }
                blocks.add(new float[]{it.x, it.y, it.x + it.w, it.y + it.h});
            }
        }
        for (List<Item> hand : hands) {
            for (Item it : hand) {
                if (it.type == ITEM_FACE) continue;
                int id = it.type == ITEM_DPAD ? Proto.gamepadId(Proto.GP_KIND_DPAD, 0, 8)
                        : Proto.gamepadId(Proto.GP_KIND_STICK, 0, it.type == ITEM_STICK_L ? 0 : 1);
                int role = it.type == ITEM_DPAD ? ROLE_DPAD : ROLE_STICK;
                int param = it.type == ITEM_DPAD ? 8 : it.type == ITEM_STICK_L ? 0 : 1;
                p.controls.add(new Control(id, role, null, param, it.x, it.y, it.x + it.w, it.y + it.h, it.x, it.y, it.x + it.w, it.y + it.h));
                blocks.add(new float[]{it.x, it.y, it.x + it.w, it.y + it.h});
            }
        }
        addKeys(p, ls, leftSh, copies, z, others);
        addKeys(p, rs, rightSh, copies, z, others);
        addKeys(p, mid, midRects, copies, z, others);
        if (lt != null) addKeys(p, new GamepadSpec.Key[]{lt}, new float[][]{ltRect}, copies, z, others);
        if (rt != null) addKeys(p, new GamepadSpec.Key[]{rt}, new float[][]{rtRect}, copies, z, others);
        if (force) return p;

        // ── 检查：都在页面里（⋯、状态字离边够远），互不重叠 ──
        List<float[]> all = new ArrayList<>(blocks);
        all.addAll(others);
        for (float[] r : all) {
            if (r[0] < -0.01f || r[1] < -0.01f || r[2] > w + 0.01f || r[3] > h + 0.01f || r[2] <= r[0] || r[3] <= r[1]) return null;
        }
        if (menu[0] < e[0] - 0.01f || menu[2] > w - e[2] + 0.01f || menu[3] > h - e[3] + 0.01f) return null;
        if (status[2] - status[0] < 40) return null;   // 状态字至少放得下几个字
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (overlap(all.get(i), all.get(j))) return null;
            }
        }
        return p;
    }

    /** 单键（肩键、中间那排、LS / RS）。肩键里输出是扳机的画成扳机；中间那排画得比判定小。 */
    private static void addKeys(Plan p, GamepadSpec.Key[] keys, float[][] rects, int[] copies, Sizes z, List<float[]> others) {
        for (int i = 0; i < keys.length; i++) {
            float[] r = rects[i];
            if (r == null) continue;
            int o = keys[i].output;
            int role;
            if (o == Proto.GP_LT || o == Proto.GP_RT) role = ROLE_TRIGGER;
            else if (o == Proto.GP_L1 || o == Proto.GP_R1) role = ROLE_BUMPER;
            else if (o == Proto.GP_L3 || o == Proto.GP_R3) role = ROLE_THUMB;
            else role = ROLE_MIDDLE;
            float dl = r[0], dt = r[1], dr = r[2], db = r[3];
            if (role == ROLE_MIDDLE) {
                float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
                float hw = Math.min(z.midDW, r[2] - r[0]) / 2, hh = Math.min(z.midDH, r[3] - r[1]) / 2;
                dl = cx - hw;
                dr = cx + hw;
                dt = cy - hh;
                db = cy + hh;
            }
            int id = Proto.gamepadId(Proto.GP_KIND_BUTTON, copies[o]++, o);
            p.controls.add(new Control(id, role, keys[i].label, o, r[0], r[1], r[2], r[3], dl, dt, dr, db));
            others.add(r);
        }
    }

    static boolean overlap(float[] a, float[] b) {
        return a[0] < b[2] - 0.01f && b[0] < a[2] - 0.01f && a[1] < b[3] - 0.01f && b[1] < a[3] - 0.01f;
    }
}
