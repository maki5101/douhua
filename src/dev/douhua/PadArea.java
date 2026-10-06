package dev.douhua;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 触控板 + 数字键盘。按可用空间的形状排：竖长时数字键盘在下面，横宽时在右边；触控板占满剩下的地方。
 * 数字键盘最多占一半，并且不超过一个正方形（4×4 的键保持方正好按）。
 */
final class PadArea extends LinearLayout {
    private final View pad;
    private final View numpad;
    private final int gap;

    PadArea(Context context, View pad, View numpad, int gap) {
        super(context);
        this.pad = pad;
        this.numpad = numpad;
        this.gap = gap;
        addView(pad, new LayoutParams(0, 0));
        addView(numpad, new LayoutParams(0, 0));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        boolean wide = w > h;
        setOrientation(wide ? HORIZONTAL : VERTICAL);

        LayoutParams pp = (LayoutParams) pad.getLayoutParams();
        LayoutParams np = (LayoutParams) numpad.getLayoutParams();
        pp.weight = 1;
        if (wide) {
            pp.width = 0;
            pp.height = LayoutParams.MATCH_PARENT;
            np.width = Math.min(w / 2, h);
            np.height = LayoutParams.MATCH_PARENT;
            np.leftMargin = gap;
            np.topMargin = 0;
        } else {
            pp.width = LayoutParams.MATCH_PARENT;
            pp.height = 0;
            np.width = LayoutParams.MATCH_PARENT;
            np.height = Math.min(h / 2, w);
            np.leftMargin = 0;
            np.topMargin = gap;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
