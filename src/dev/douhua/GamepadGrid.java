package dev.douhua;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置 → 手柄里的预设网格：每格一小块手柄页的样子（{@link GamepadPreview}，不写字）+ 名字，最后一格是"自定义"；点一下选中。
 * 每行几格按宽度定（每格至少 {@link #MIN_CELL_DP}，手机竖屏约 3 格），选中框用设置页自己的蓝（同主题色块网格 ThemeGrid）。
 */
final class GamepadGrid extends ViewGroup {
    interface Listener {
        void onPick(String id);
    }

    private static final int MIN_CELL_DP = 96;
    private static final int GAP_DP = 8;
    private static final int SELECTED = 0xFF8AB4F8;

    private final String[] ids;
    private final LinearLayout[] cells;
    private final GamepadPreview[] previews;
    private final float density;

    GamepadGrid(Context c, String[] ids, String[] names, Listener listener) {
        super(c);
        this.ids = ids;
        density = Style.density(c);
        cells = new LinearLayout[ids.length];
        previews = new GamepadPreview[ids.length];
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            int p = dp(5);
            cell.setPadding(p, p, p, dp(4));
            cell.setContentDescription(names[i]);
            cell.setOnClickListener(v -> listener.onPick(id));
            GamepadPreview preview = new GamepadPreview(c, false);
            cell.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView name = new TextView(c);
            name.setText(names[i]);
            name.setTextColor(0xFFE8E8E8);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            // 放不下先缩字号（"Mega Drive 6-button"、德语的长名字），缩到 9sp 还放不下就省略
            name.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
            np.topMargin = dp(4);
            cell.addView(name, np);
            addView(cell);
            cells[i] = cell;
            previews[i] = preview;
        }
    }

    /** 每格的样子（specs 和 ids 一一对应；"自定义"那格是存着的那一套，没存过是现在选的预设复制出来的样子）、选中哪格。 */
    void show(GamepadSpec[] specs, String selectedId, Theme t, boolean keyBorders) {
        for (int i = 0; i < ids.length; i++) {
            boolean on = ids[i].equals(selectedId);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(14));
            frame.setStroke(dp(2.5f), on ? SELECTED : 0);   // 没选中的也留一圈透明的边，选中时不跳位置
            frame.setColor(on ? 0x268AB4F8 : 0);
            cells[i].setBackground(frame);
            cells[i].setSelected(on);
            previews[i].show(specs[i], t, keyBorders);
        }
    }

    // ═══════════ 摆放：每行几格按宽度定（同 ThemeGrid） ═══════════

    private int columns(int width) {
        int gap = dp(GAP_DP);
        return Math.max(2, (width + gap) / (dp(MIN_CELL_DP) + gap));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int gap = dp(GAP_DP);
        int cols = columns(w);
        int cellW = Math.max(0, (w - gap * (cols - 1)) / cols);
        int h = 0, rowH = 0;
        for (int i = 0; i < cells.length; i++) {
            cells[i].measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowH = Math.max(rowH, cells[i].getMeasuredHeight());
            if (i % cols == cols - 1 || i == cells.length - 1) {
                h += rowH + (h > 0 ? gap : 0);
                rowH = 0;
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int gap = dp(GAP_DP);
        int cols = columns(w);
        int cellW = Math.max(0, (w - gap * (cols - 1)) / cols);
        int y = 0, rowH = 0;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;   // 阿拉伯文：从右往左排格子（格子里的手柄不镜像）
        for (int i = 0; i < cells.length; i++) {
            int col = i % cols;
            int x = col * (cellW + gap);
            if (rtl) x = w - x - cellW;
            View c = cells[i];
            c.layout(x, y, x + cellW, y + c.getMeasuredHeight());
            rowH = Math.max(rowH, c.getMeasuredHeight());
            if (col == cols - 1) {
                y += rowH + gap;
                rowH = 0;
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
