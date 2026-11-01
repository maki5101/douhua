package dev.douhua;

/**
 * 屏幕边缘留白（设置 → 外观，2026-11-01 用户："很多安卓手机在边缘上都有额外操作……不管是键盘还是触控板，都留一个安全距离，
 * 靠近屏幕边缘的部分都留一小条"），纯 Java（离线测，EdgeMarginTest）。
 *
 * 豆花的几页（主界面、键盘屏、空中鼠标页、⋯ 菜单那一层）整体往里收，留出来的那一条是页面底色、不报区域、什么都不按得到
 * （服务接管时落在那儿的手指找不到区域；分屏透传时照"窗口里没区域的地方"的规则）。只在窗口贴着屏幕边的那几条边上留：
 * 分屏 / 小窗时窗口的某条边不在屏幕边上（比如分屏下半边的上沿挨着分隔条），那条不留。坐标都是当前方向的屏幕像素
 * （窗口在屏上的范围和整块屏，和报给服务的区域同一套），所以转屏后"左右"永远是当前方向的左右两条边。
 *
 * ⋯、菜单、调整栏、三键条、放在键盘外面的小红点原来的规矩"离屏幕边至少 20dp"照旧按屏幕边算：页面收进去多少，它们在页面里就少留多少
 * （{@link #edges}）；留白比 20dp 还宽时它们自然更靠里。
 */
final class EdgeMargin {
    private EdgeMargin() {
    }

    /** 设置里两个滑块（左右、上下）的范围、步长、默认值（dp）。 */
    static final int MAX_DP = 48, STEP_DP = 2, DEFAULT_DP = 8;
    /** 窗口的边离屏幕边不到这么多像素就算贴着（取整差一两个像素的也算）。 */
    static final int TOUCH_PX = 2;

    /** 设置里存的值（dp）收进 0–MAX_DP。 */
    static int clampDp(int dp) {
        return Math.max(0, Math.min(MAX_DP, dp));
    }

    /**
     * 各边留多宽（像素）{左, 上, 右, 下}：window、screen 是 {左, 上, 右, 下}（当前方向的屏幕像素），lr、tb 是"左右""上下"两个设置（像素）。
     * 窗口的那条边贴着（或超出）屏幕边才留，不然 0。窗口小时（小窗贴着屏幕边）别把内容挤没：一个方向上两边加起来不超过窗口这个方向的一半，
     * 超了两边按比例一起缩。窗口还没量好（宽或高 ≤ 0）全是 0。
     */
    static int[] margins(int[] window, int[] screen, int lr, int tb) {
        int w = window[2] - window[0], h = window[3] - window[1];
        if (w <= 0 || h <= 0) return new int[4];
        lr = Math.max(0, lr);
        tb = Math.max(0, tb);
        int[] m = {
                window[0] <= screen[0] + TOUCH_PX ? lr : 0,
                window[1] <= screen[1] + TOUCH_PX ? tb : 0,
                window[2] >= screen[2] - TOUCH_PX ? lr : 0,
                window[3] >= screen[3] - TOUCH_PX ? tb : 0,
        };
        fit(m, 0, 2, w / 2);
        fit(m, 1, 3, h / 2);
        return m;
    }

    /** m[a] + m[b] 超过 room 时两个一起按比例缩到放得下（取整往下，加起来不超过 room）。 */
    private static void fit(int[] m, int a, int b, int room) {
        int sum = m[a] + m[b];
        if (sum <= room) return;
        m[a] = (int) ((long) m[a] * room / sum);
        m[b] = (int) ((long) m[b] * room / sum);
    }

    /**
     * 页面收进去 margins 之后，"离屏幕边至少 edge"在页面里是离页面各边至少多远 {左, 上, 右, 下}：edge − 那条边的留白，不小于 0。
     * 没留白的边（分屏时不贴屏幕边的那条）照旧离页面边 edge，和以前一样。
     */
    static int[] edges(int[] margins, int edge) {
        int[] e = new int[4];
        for (int i = 0; i < 4; i++) e[i] = Math.max(0, edge - margins[i]);
        return e;
    }
}
