package dev.douhua;

import java.util.Arrays;
import java.util.Random;

/**
 * EdgeMargin（屏幕边缘留白）的离线测试：哪几条边留（全屏四条、分屏 / 小窗只留贴着屏幕边的、差一两个像素也算贴着、超出屏幕的也算）、
 * 留多少（左右 / 上下各一个值、窗口小时按比例缩到不超过一半）、页面往里收了以后"离屏幕边 20dp"在页面里还剩多少（edges）；
 * 再把收进去的页面交给真正的 KeyboardPlan（菜单弹在哪、⋯、调整栏、三键条、键盘页整个摆法）扫一遍各种窗口 × 留白，
 * 查换回窗口坐标后：东西都在页面里（不进留白那一条），地方够时离每条屏幕边至少 max(20, 留白)。尺寸按 1dp = 1px 算。
 */
public final class EdgeMarginTest {
    private static int failures;
    private static int checks;
    private static final int EDGE = 20;
    /** 扫描里真查到"离窗口边够远"的次数（免得条件一直不成立、测了个寂寞）：菜单、⋯、调整栏、三键条、键盘外面的红点。 */
    private static int farMenus, farDots, farBars, farKeys, farSticks;

    public static void main(String[] args) {
        whichSides();
        amounts();
        edgesInPage();
        randomWindows();
        pageSweep();
        System.err.println("  扫描里查了离窗口边够远的：菜单 " + farMenus + "、⋯ " + farDots + "、调整栏 " + farBars
                + "、三键条 " + farKeys + "、键盘外面的红点 " + farSticks);
        check(farMenus > 0 && farDots > 0 && farBars > 0 && farKeys > 0 && farSticks > 0, "扫描每种都真查到了");
        System.err.println("屏幕边缘留白：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    /** 哪几条边留：窗口那条边贴着（或超出）屏幕边才留。 */
    static void whichSides() {
        int[] port = {0, 0, 1080, 2400};
        eq(EdgeMargin.margins(port, port, 8, 12), new int[]{8, 12, 8, 12}, "全屏竖屏：四条边都留，左右 8、上下 12");
        int[] land = {0, 0, 2400, 1080};
        eq(EdgeMargin.margins(land, land, 8, 12), new int[]{8, 12, 8, 12}, "转成横屏：左右还是当前方向的左右（8），上下 12");
        eq(EdgeMargin.margins(new int[]{0, 1210, 1080, 2400}, port, 8, 12), new int[]{8, 0, 8, 12},
                "分屏下半边：上沿挨着分隔条不留，左、右、下留");
        eq(EdgeMargin.margins(new int[]{0, 0, 1080, 1190}, port, 8, 12), new int[]{8, 12, 8, 0}, "分屏上半边：下沿不留");
        eq(EdgeMargin.margins(new int[]{0, 0, 1190, 1080}, land, 8, 12), new int[]{8, 12, 0, 12}, "横屏左右分屏的左边那个：右沿不留");
        eq(EdgeMargin.margins(new int[]{1210, 0, 2400, 1080}, land, 8, 12), new int[]{0, 12, 8, 12}, "右边那个：左沿不留");
        eq(EdgeMargin.margins(new int[]{200, 300, 800, 1500}, port, 8, 12), new int[]{0, 0, 0, 0}, "小窗浮在中间：一条都不留");
        eq(EdgeMargin.margins(new int[]{500, 300, 1080, 1500}, port, 8, 12), new int[]{0, 0, 8, 0}, "小窗拖到贴着右边：只留右边");
        eq(EdgeMargin.margins(new int[]{2, 0, 1078, 2400}, port, 8, 12), new int[]{8, 12, 8, 12}, "差 2 像素也算贴着（取整）");
        eq(EdgeMargin.margins(new int[]{3, 0, 1077, 2400}, port, 8, 12), new int[]{0, 12, 0, 12}, "差 3 像素不算");
        eq(EdgeMargin.margins(new int[]{-50, 300, 600, 1500}, port, 8, 12), new int[]{8, 0, 0, 0}, "小窗一截拖出了屏幕左边：算贴着");
        // 屏幕原点不在 (0, 0) 时照它的范围比（getMaximumWindowMetrics 的 bounds 一般从 0 开始，以防万一）
        eq(EdgeMargin.margins(new int[]{100, 100, 500, 900}, new int[]{100, 100, 500, 900}, 8, 12), new int[]{8, 12, 8, 12},
                "屏幕原点不在 0：照范围比");
    }

    /** 留多少：0 就是不留；负数当 0；窗口没量好全 0；窗口小时一个方向两边加起来不超过一半。 */
    static void amounts() {
        int[] port = {0, 0, 1080, 2400};
        eq(EdgeMargin.margins(port, port, 0, 0), new int[4], "设成 0：不留");
        eq(EdgeMargin.margins(port, port, -5, 6), new int[]{0, 6, 0, 6}, "负数当 0");
        eq(EdgeMargin.margins(new int[]{0, 0, 0, 2400}, port, 8, 12), new int[4], "窗口宽 0（还没量好）：全 0");
        eq(EdgeMargin.margins(new int[]{0, 0, 1080, 0}, port, 8, 12), new int[4], "窗口高 0：全 0");
        // 200 × 200 的窗口四边都贴着、每边要 144：一个方向最多一半（100），两边按比例各 50
        eq(EdgeMargin.margins(new int[]{0, 0, 200, 200}, new int[]{0, 0, 200, 200}, 144, 144), new int[]{50, 50, 50, 50},
                "窗口小：两边按比例缩到加起来一半");
        // 只贴着一边的窄小窗：那一边最多窗口宽的一半
        int[] m = EdgeMargin.margins(new int[]{0, 300, 60, 900}, port, 96, 0);
        eq(m, new int[]{30, 0, 0, 0}, "窄小窗只贴左边：左边缩到 30（宽 60 的一半）");
        check(EdgeMargin.clampDp(-2) == 0 && EdgeMargin.clampDp(50) == EdgeMargin.MAX_DP && EdgeMargin.clampDp(8) == 8,
                "设置值收进 0–48");
        check(EdgeMargin.DEFAULT_DP == 8 && EdgeMargin.MAX_DP == 48 && EdgeMargin.STEP_DP == 2
                && EdgeMargin.DEFAULT_DP % EdgeMargin.STEP_DP == 0 && EdgeMargin.MAX_DP % EdgeMargin.STEP_DP == 0,
                "滑块 0–48、步长 2、默认 8（落在刻度上）");
    }

    /** 页面往里收了以后，"离屏幕边 20"在页面里还要多少：收得少的边补足 20，收了 20 以上的边不再另留；没收的边还是 20。 */
    static void edgesInPage() {
        eq(EdgeMargin.edges(new int[]{0, 8, 20, 48}, EDGE), new int[]{20, 12, 0, 0}, "没收 → 20，收 8 → 12，收 20 / 48 → 0");
        eq(EdgeMargin.edges(new int[4], EDGE), new int[]{20, 20, 20, 20}, "没开留白：和以前一样四边 20");
    }

    /** 随便的窗口：每条边要么 0、要么不超过设的值；不贴屏幕边的一定是 0；一个方向两边加起来不超过窗口的一半。 */
    static void randomWindows() {
        Random r = new Random(7);
        int[] screen = {0, 0, 1080, 2400};
        for (int i = 0; i < 3000; i++) {
            int l = r.nextInt(1200) - 100, t = r.nextInt(2600) - 100;
            int w = r.nextInt(1300), h = r.nextInt(2700);
            if (r.nextInt(4) == 0) l = 0;
            if (r.nextInt(4) == 0) t = 0;
            if (r.nextInt(4) == 0) w = 1080 - l;
            if (r.nextInt(4) == 0) h = 2400 - t;
            int[] win = {l, t, l + w, t + h};
            int lr = r.nextInt(150), tb = r.nextInt(150);
            int[] m = EdgeMargin.margins(win, screen, lr, tb);
            boolean ok = true;
            boolean[] touch = {l <= 2, t <= 2, l + w >= 1078, t + h >= 2398};
            for (int s = 0; s < 4; s++) {
                int want = s % 2 == 0 ? lr : tb;
                ok &= m[s] >= 0 && m[s] <= want && (touch[s] || m[s] == 0);
                if (touch[s] && w > 0 && h > 0) {
                    // 贴着的边：没被"不超过一半"缩的话就是设的值
                    int sum = s % 2 == 0 ? (touch[0] ? lr : 0) + (touch[2] ? lr : 0) : (touch[1] ? tb : 0) + (touch[3] ? tb : 0);
                    int room = s % 2 == 0 ? w / 2 : h / 2;
                    if (sum <= room) ok &= m[s] == want;
                }
            }
            if (w > 0 && h > 0) ok &= m[0] + m[2] <= w / 2 && m[1] + m[3] <= h / 2;
            else ok &= Arrays.equals(m, new int[4]);
            check(ok, "随便的窗口 " + Arrays.toString(win) + " 左右 " + lr + " 上下 " + tb + " → " + Arrays.toString(m));
        }
    }

    /**
     * 收进去的页面交给 KeyboardPlan（页面坐标，离边按 EdgeMargin.edges），换回窗口坐标查：在页面里（不进留白），
     * 地方够时离每条窗口边（全屏 = 屏幕边）至少 max(20, 这条边的留白)。窗口：全屏竖 / 横、分屏上 / 下半边、手机横屏左右分屏、小窗。
     */
    static void pageSweep() {
        // 最后两个是窄的（键盘里嵌不下小红点、红点放到键盘外面）：贴着左边的窄小窗、窄的小屏（翻盖手机的外屏那种）
        int[][] screens = {{0, 0, 1080, 2400}, {0, 0, 2400, 1080}, {0, 0, 1080, 2400}, {0, 0, 1080, 2400}, {0, 0, 2400, 1080},
                {0, 0, 1080, 2400}, {0, 0, 1812, 2176}, {0, 0, 1080, 2400}, {0, 0, 260, 520}};
        int[][] windows = {{0, 0, 1080, 2400}, {0, 0, 2400, 1080}, {0, 1210, 1080, 2400}, {0, 0, 1080, 1190}, {0, 0, 1190, 1080},
                {300, 600, 1080, 1500}, {0, 1098, 1812, 2176}, {0, 600, 270, 1500}, {0, 0, 260, 520}};
        KeyboardPlan.Sizes base = new KeyboardPlan.Sizes(8, 96, 32, 64, 32, 44, 48, 4, 56, 2, EDGE);
        int[] values = {0, 2, 8, 14, 20, 26, 48};
        for (int wi = 0; wi < windows.length; wi++) {
            int[] win = windows[wi];
            int W = win[2] - win[0], H = win[3] - win[1];
            for (int lr : values) {
                for (int tb : values) {
                    int[] m = EdgeMargin.margins(win, screens[wi], lr, tb);
                    int[] e = EdgeMargin.edges(m, EDGE);
                    int cw = W - m[0] - m[2], ch = H - m[1] - m[3];
                    // 窗口坐标里每条边至少要离多远
                    int[] need = new int[4];
                    for (int s = 0; s < 4; s++) need[s] = Math.max(EDGE, m[s]);
                    String what = "窗口 " + Arrays.toString(win) + " 左右 " + lr + " 上下 " + tb;
                    menus(m, e, cw, ch, W, H, need, what);
                    keyboardPage(m, e, cw, ch, W, H, need, base, what);
                }
            }
        }
    }

    /** 菜单弹在哪（各种 ⋯ 的位置、菜单大小）、触控板顶上的 ⋯、调整栏。 */
    private static void menus(int[] m, int[] e, int cw, int ch, int W, int H, int[] need, String what) {
        int[][] sizes = {{220, 200}, {220, 356}, {260, 300}, {120, 90}};
        for (int[] ms : sizes) {
            int mw = ms[0], mh = ms[1];
            for (int ax = 0; ax <= 4; ax++) {
                for (int ay = 0; ay <= 4; ay++) {
                    int x = (cw - 56) * ax / 4, y = (ch - 36) * ay / 4;
                    int[] anchor = {x, y, x + 56, y + 36};
                    for (boolean center : new boolean[]{true, false}) {
                        int[] at = KeyboardPlan.menuPosition(anchor, mw, mh, cw, ch, e, 8, center);
                        int l = m[0] + at[0], t = m[1] + at[1];
                        boolean ok = true;
                        // 页面放得下菜单就不出页面（不进留白）
                        if (mw <= cw) ok &= at[0] >= 0 && at[0] + mw <= cw;
                        if (mh <= ch) ok &= at[1] >= 0 && at[1] + mh <= ch;
                        // 地方够时离每条窗口边至少 max(20, 留白)
                        if (cw - mw >= e[0] + e[2]) ok &= l >= need[0] && l + mw <= W - need[2];
                        if (ch - mh >= e[1] + e[3]) ok &= t >= need[1] && t + mh <= H - need[3];
                        if (cw - mw >= e[0] + e[2] && ch - mh >= e[1] + e[3]) farMenus++;
                        check(ok, what + "：菜单 " + mw + "×" + mh + " ⋯ " + Arrays.toString(anchor) + " → " + Arrays.toString(at));
                    }
                }
            }
        }
        // 触控板（整页）顶上正中的 ⋯：放得下的话离窗口边够远、在页面里
        int[] pad = {0, 0, cw, ch};
        int[] at = KeyboardPlan.menuTop(pad, cw, ch, 56, 36, 0, e);
        if (at != null) {
            farDots++;
            int l = m[0] + at[0], t = m[1] + at[1];
            check(at[0] >= 0 && at[1] >= 0 && at[0] + 56 <= cw && at[1] + 36 <= ch && l >= need[0] && t >= need[1]
                    && l + 56 <= W - need[2] && t + 36 <= H - need[3], what + "：⋯ " + Arrays.toString(at));
        } else {
            check(cw - e[0] - e[2] < 56 || ch - e[1] - e[3] < 36, what + "：⋯ 放得下却没放");
        }
        // 调整栏（36 高）：页面够高时离上下窗口边够远
        int top = KeyboardPlan.adjustBarTop(ch, 36, e[1], e[3], ch / 2, ch);
        if (ch >= e[1] + e[3] + 36) {
            farBars++;
            check(m[1] + top >= need[1] && m[1] + top + 36 <= H - need[3], what + "：调整栏 " + top);
        } else {
            check(top == Math.max(0, (ch - 36) / 2), what + "：调整栏放不下竖着居中");
        }
        // 调整栏按钮宽度：按要得多的那边算、栏在正中，两边都够远
        int each = KeyboardPlan.barButtonWidth(cw, 2, Math.max(e[0], e[2]), 8, 104);
        int barW = 2 * (each + 16);
        if (each > 0) {
            int left = m[0] + (cw - barW) / 2;
            check(left >= need[0] && left + barW <= W - need[2], what + "：调整栏按钮 " + each);
        }
    }

    /** 键盘页整个摆法：三键条、放在键盘外面的红点离窗口边够远；所有东西在页面里。 */
    private static void keyboardPage(int[] m, int[] e, int cw, int ch, int W, int H, int[] need, KeyboardPlan.Sizes base,
                                     String what) {
        KeyboardPlan.Sizes s = base.withEdges(e);
        check(s.edgeL == e[0] && s.edgeT == e[1] && s.edgeR == e[2] && s.edgeB == e[3] && s.gap == base.gap
                && s.minPad == base.minPad && s.keyH == base.keyH, what + "：withEdges 只换离边距离");
        boolean land = cw > ch;
        for (int mode : new int[]{KeyboardPlan.MODE_AUTO, KeyboardPlan.MODE_SPLIT, KeyboardPlan.MODE_MERGE}) {
            for (int pointer : new int[]{KeyboardPlan.POINTER_PAD, KeyboardPlan.POINTER_STICK}) {
                for (int keys : new int[]{KeyboardPlan.KEYS_BOTTOM, KeyboardPlan.KEYS_TOP}) {
                    KeyboardPlan.Plan p = KeyboardPlan.plan(mode, pointer, keys, cw, ch, null, null, land, s);
                    String w = what + " 布局 " + mode + " 指点 " + pointer + " 三键条 " + keys;
                    check(inside(p.block, cw, ch) && inside(p.pad, cw, ch) && inside(p.keys, cw, ch) && inside(p.stick, cw, ch),
                            w + "：都在页面里");
                    if (pointer == KeyboardPlan.POINTER_PAD && p.keys != null) {
                        farKeys++;
                        check(far(p.keys, m, W, H, need), w + "：三键条离窗口边 " + Arrays.toString(p.keys));
                    }
                    if (p.stickAt == KeyboardPlan.STICK_OUTSIDE) {
                        farSticks++;
                        int[] k = p.stick;
                        check(m[0] + k[0] >= need[0] && m[0] + k[2] <= W - need[2], w + "：键盘外面的红点离左右窗口边 " + Arrays.toString(k));
                    }
                }
            }
        }
        // 合并、存过位置：键盘照存的比例摆在页面（键盘区）里，留白变了比例不动
        float[] saved = {0.1f, 0.4f, 0.9f, 1f};
        KeyboardPlan.Plan p = KeyboardPlan.plan(KeyboardPlan.MODE_MERGE, KeyboardPlan.POINTER_PAD, KeyboardPlan.KEYS_OFF, cw, ch,
                saved, null, land, s);
        check(Arrays.equals(p.rect, saved) && Math.abs(p.block[0] - 0.1f * cw) <= 1 && Math.abs(p.block[3] - ch) <= 1,
                what + "：存的位置照比例摆在页面里 " + Arrays.toString(p.block));
    }

    private static boolean inside(int[] r, int w, int h) {
        return r == null || (r[0] >= 0 && r[1] >= 0 && r[2] <= w && r[3] <= h);
    }

    /** 页面坐标的 r 换回窗口坐标后离四条窗口边都至少 need。 */
    private static boolean far(int[] r, int[] m, int W, int H, int[] need) {
        return m[0] + r[0] >= need[0] && m[1] + r[1] >= need[1] && m[0] + r[2] <= W - need[2] && m[1] + r[3] <= H - need[3];
    }

    private static void eq(int[] got, int[] want, String what) {
        check(Arrays.equals(got, want), what + "：想要 " + Arrays.toString(want) + "，实际 " + Arrays.toString(got));
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
