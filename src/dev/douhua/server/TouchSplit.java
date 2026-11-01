package dev.douhua.server;

/**
 * 分屏 / 小窗时，独占着的触摸屏上每根手指归谁：豆花（交给 TouchRouter）还是系统（注入回去，见 {@link Passthrough}）。
 * 纯 Java、不依赖 Android（离线测试）。
 *
 * 规则（按下那一刻定，这根手指一直归那边，直到抬起）：
 * - 豆花窗口占满整块屏：一律归豆花（系统手势照旧挡住，和以前一样）。
 * - 没占满：按在豆花窗口外（另一边的应用、分隔条、状态栏）→ 系统（{@link #SYSTEM}，注入时允许挪键盘焦点：点 Moonlight 就该把焦点给它）；
 *   按在豆花窗口里、但落在贴着窗口边的系统手势带里（返回：左右；回桌面：下；下拉：上；小窗的标题栏也算上边的带；宽度由 App 报）
 *   → 系统（{@link #SYSTEM_IN_WINDOW}，注入时带 NO_FOCUS_CHANGE：系统没认成手势、把它发回豆花窗口时，不许把键盘焦点挪给豆花，
 *   不然虚拟键盘打的字全被豆花自己吞掉、进不了另一边）——除非这一点落在豆花的键、按钮、"左键"、键盘范围上（ownTarget），
 *   那就归豆花：轻点被系统发回豆花窗口又被吞，按键就丢了。只有落在触控板上或空白处的才交给系统认手势；
 *   代价是从键、按钮上划不出返回 / 回桌面；
 *   其余 → 豆花。
 * 手势带只在窗口那条边贴着屏幕边时才有宽度（App 报的是自己窗口的 systemGestures 插图）；没贴着的那一侧，
 * 系统手势区本来就在豆花窗口外，"窗口外归系统"已经管到了。
 */
final class TouchSplit {
    /** App 报来的豆花窗口：当前方向的屏幕坐标（像素），右、下不含。 */
    static final class Window {
        static final Window FULL = new Window(true, 0, 0, 0, 0, 0, 0, 0, 0);

        final boolean full;
        final int left, top, right, bottom;
        /** 贴着窗口四条边的系统手势带宽度（像素）。 */
        final int gestLeft, gestTop, gestRight, gestBottom;

        Window(boolean full, int left, int top, int right, int bottom,
               int gestLeft, int gestTop, int gestRight, int gestBottom) {
            this.full = full;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.gestLeft = Math.max(0, gestLeft);
            this.gestTop = Math.max(0, gestTop);
            this.gestRight = Math.max(0, gestRight);
            this.gestBottom = Math.max(0, gestBottom);
        }

        /** (x, y) 在豆花窗口外（占满时没有"外"）。 */
        boolean outside(float x, float y) {
            return !full && (x < left || x >= right || y < top || y >= bottom);
        }

        /** (x, y) 在豆花窗口里、落在贴着窗口边的系统手势带里（占满时恒假：系统手势照旧挡住）。 */
        boolean inGestureBand(float x, float y) {
            if (full || outside(x, y)) return false;
            return x < left + gestLeft || x >= right - gestRight || y < top + gestTop || y >= bottom - gestBottom;
        }

        /** 按在 (x, y) 的手指（不看底下是不是豆花的键 / 按钮）该归系统吗：窗口外或手势带里。 */
        boolean toSystem(float x, float y) {
            return outside(x, y) || inGestureBand(x, y);
        }

        boolean sameAs(Window o) {
            return full == o.full && left == o.left && top == o.top && right == o.right && bottom == o.bottom
                    && gestLeft == o.gestLeft && gestTop == o.gestTop && gestRight == o.gestRight && gestBottom == o.gestBottom;
        }

        @Override
        public String toString() {
            return full ? "占满" : "[" + left + "," + top + "–" + right + "," + bottom + "] 手势带 "
                    + gestLeft + "/" + gestTop + "/" + gestRight + "/" + gestBottom;
        }
    }

    /** {@link #down} 的结果：归豆花（交给 TouchRouter）。 */
    static final int ROUTER = 0;
    /** 归系统，按在豆花窗口外：注入时照常（DOWN 点到别的应用会把键盘焦点给它）。 */
    static final int SYSTEM = 1;
    /** 归系统，按在豆花窗口里的手势带：注入时带 NO_FOCUS_CHANGE，系统发回豆花窗口时不挪焦点。 */
    static final int SYSTEM_IN_WINDOW = 2;

    /** 每个 slot 现在按着的手指归不归系统；没按着的是 false。 */
    private final boolean[] system = new boolean[MtDecoder.SLOTS];

    /**
     * 手指按下：按当时的窗口定归属，记下来。ownTarget = 这一点落在豆花的键、按钮、"左键"或键盘范围上
     * （调用方问 TouchRouter；只在 {@link Window#inGestureBand} 为真时才要紧）。返回 {@link #ROUTER} / {@link #SYSTEM} / {@link #SYSTEM_IN_WINDOW}。
     */
    int down(int slot, float x, float y, Window w, boolean ownTarget) {
        if (slot < 0 || slot >= system.length) return ROUTER;
        int to = w.outside(x, y) ? SYSTEM : w.inGestureBand(x, y) && !ownTarget ? SYSTEM_IN_WINDOW : ROUTER;
        system[slot] = to != ROUTER;
        return to;
    }

    boolean isSystem(int slot) {
        return slot >= 0 && slot < system.length && system[slot];
    }

    /** 手指抬起（或被取消）：返回它归不归系统，然后忘掉。 */
    boolean up(int slot) {
        if (slot < 0 || slot >= system.length) return false;
        boolean s = system[slot];
        system[slot] = false;
        return s;
    }

    /** 重新接管时清空（上一轮的手指都已经收过尾）。 */
    void clear() {
        for (int i = 0; i < system.length; i++) system[i] = false;
    }
}
