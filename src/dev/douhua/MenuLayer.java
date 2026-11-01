package dev.douhua;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * ⋯ 菜单，主界面和键盘页共用这一份（2026-11-01：每块触控板顶上正中都有 ⋯）：盖住整页的"点外面关菜单"（{@link #outside}）
 * + 菜单本身（{@link #panel}，一格格的项）。豆花自己画，不用 PopupMenu / Dialog：服务独占触摸屏时 App 收不到手指，只能靠报区域
 * （MainActivity.collectRegions：菜单开着时只报菜单这一组）。
 * 摆在根视图最上面一层、铺满整页；菜单贴着当前那一屏的 ⋯ 弹出（{@link Host#menuAnchor}，KeyboardPlan.menuPosition），不出屏。
 * 项多、窗口矮时怎么排见 KeyboardPlan.menuGrid：先压矮每项，还放不下再分列（先排满第一列），最矮的窗口再贴边、缩。
 * 项是哪些、点了做什么由 MainActivity 管：项是 panel 的子 view，只排显示着的（这一屏没有的项 GONE）。
 *
 * ⋯ 挪了（换布局、开关三键条、拖分隔条）菜单要跟着走：只有 ⋯ 那一支重新布局时，这一层的 onLayout 不一定跑（大小没变就跳过），
 * 所以 MainActivity 在根视图的布局监听里（整棵树摆完以后）再调一次 {@link #place}。
 */
final class MenuLayer extends ViewGroup {
    interface Host {
        /**
         * ⋯ 现在在哪：窗口坐标 {左, 上, 右, 下} 写进 out。返回 ⋯ 是不是在触控板顶上正中（菜单和它居中对齐），
         * 假 = 键盘功能行最右边的键（菜单按它在页面左半 / 右半对齐）。
         */
        boolean menuAnchor(int[] out);
    }

    private final View outside;
    private final Panel panel;
    private final Host host;
    /** 菜单离页面边、离 ⋯ 多远（同以前键盘页：20dp、8dp）。 */
    private final int edge, gap;
    /** 菜单四周、项与项之间（6dp），每项最宽（208dp：菜单连四周最宽 220dp）、最高 / 压到最矮（44 / 36dp），分列时每列最窄（120dp）。 */
    private final int pad, itemGap, maxItemW, itemH, minItemH, minColW;
    private final int[] me = new int[2];
    private final int[] anchor = new int[4];

    /** outside 由 MainActivity 建好（触摸、按钮编号归它），这里只管摆。 */
    MenuLayer(Context c, View outside, Host host) {
        super(c);
        this.outside = outside;
        this.host = host;
        edge = Style.dp(c, 20);
        gap = Style.dp(c, 8);
        pad = Style.dp(c, 6);
        itemGap = Style.dp(c, 6);
        maxItemW = Style.dp(c, 208);
        itemH = Style.dp(c, 44);
        minItemH = Style.dp(c, 36);
        minColW = Style.dp(c, 120);
        panel = new Panel(c, pad, itemGap);
        // 底板可点：服务没接管时（View 自己收触摸）挡住底下的"点外面"，按在项与项之间的缝里不当点外面
        panel.setClickable(true);
        addView(outside);
        addView(panel);
        setVisibility(GONE);
    }

    /** 菜单底板：项加在它里面（{@link ViewGroup#addView}），底色由 MainActivity 按主题上。 */
    ViewGroup panel() {
        return panel;
    }

    void setOpen(boolean on) {
        setVisibility(on ? VISIBLE : GONE);
        requestLayout();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        outside.measure(exactly(w), exactly(h));
        int[] g = KeyboardPlan.menuGrid(panel.visibleCount(), w, h, edge, pad, itemGap, maxItemW, itemH, minItemH, minColW);
        panel.setGrid(g[0], g[1], g[2], g[3]);
        panel.measure(exactly(g[4]), exactly(g[5]));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        outside.layout(0, 0, r - l, b - t);
        place();
    }

    /** 按 ⋯ 现在的位置摆菜单（开着才摆）：贴着 ⋯，不出页面。 */
    void place() {
        if (!isOpen() || getWidth() == 0) return;
        boolean center = host.menuAnchor(anchor);
        getLocationInWindow(me);
        int[] a = {anchor[0] - me[0], anchor[1] - me[1], anchor[2] - me[0], anchor[3] - me[1]};
        int mw = panel.getMeasuredWidth(), mh = panel.getMeasuredHeight();
        int[] at = KeyboardPlan.menuPosition(a, mw, mh, getWidth(), getHeight(), edge, gap, center);
        panel.layout(at[0], at[1], at[0] + mw, at[1] + mh);
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
    }

    /** 菜单底板：显示着的项按 cols × rows 一格格排（先排满第一列再排下一列；从右往左的语言第一列在最右边，同按钮条）。 */
    private static final class Panel extends ViewGroup {
        private final int pad, gap;
        private int cols = 1, rows = 1, itemW, itemH;

        Panel(Context c, int pad, int gap) {
            super(c);
            this.pad = pad;
            this.gap = gap;
        }

        void setGrid(int cols, int rows, int itemW, int itemH) {
            this.cols = cols;
            this.rows = Math.max(1, rows);
            this.itemW = itemW;
            this.itemH = itemH;
        }

        int visibleCount() {
            int n = 0;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) n++;
            return n;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() != GONE) v.measure(exactly(itemW), exactly(itemH));
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int n = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int col = n / rows, row = n % rows;
                n++;
                int x = pad + col * (itemW + gap);
                if (rtl) x = (r - l) - x - itemW;
                int y = pad + row * (itemH + gap);
                v.layout(x, y, x + itemW, y + itemH);
            }
        }
    }
}
