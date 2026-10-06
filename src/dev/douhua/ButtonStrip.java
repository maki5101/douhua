package dev.douhua;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 左侧按钮条：按钮从上到下排，铺满整个高度。屏幕矮（比如横屏）放不下时自动分成多列，
 * 保证每个按钮不低于 minButtonHeight。先排满第一列再排第二列，最常用的留在最靠左的那列。
 */
final class ButtonStrip extends ViewGroup {
    private final int columnWidth;
    private final int minButtonHeight;
    private final int gap;
    private int rows = 1;

    ButtonStrip(Context context, int columnWidth, int minButtonHeight, int gap) {
        super(context);
        this.columnWidth = columnWidth;
        this.minButtonHeight = minButtonHeight;
        this.gap = gap;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = MeasureSpec.getSize(heightSpec);
        int n = Math.max(1, getChildCount());
        int columns = Math.max(1, (int) Math.ceil(n * (minButtonHeight + gap) / (double) Math.max(1, height + gap)));
        rows = (int) Math.ceil(n / (double) columns);
        int childHeight = Math.max(0, (height - gap * (rows - 1)) / rows);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(columns * columnWidth + (columns - 1) * gap, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;   // 从右往左的语言：第一列放最右边
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int col = i / rows;
            int row = i % rows;
            int x = col * (columnWidth + gap);
            if (rtl) x = (r - l) - x - columnWidth;
            int y = row * (c.getMeasuredHeight() + gap);
            c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
        }
    }
}
