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
 * 设置 → 外观里的主题色块网格（一组：深色或浅色）：每格一小块这套主题的样子——页面底色上一个普通键帽 "A"、一个功能键帽 "⇧"
 * （功能键的颜色）、一小块触控板（底色 + 边框），名字写在格子下面；点一下选中。每行几格按宽度定（每格至少 {@link #MIN_CELL_DP}，
 * 手机竖屏约 3 格）。选中框用设置页自己的配色（设置页不跟主题变），色块里的样子用带 Theme 参数的 Style 方法，不碰主界面的当前主题。
 */
final class ThemeGrid extends ViewGroup {
    interface Listener {
        void onPick(Theme t);
    }

    /** 每格至少多宽（dp）。 */
    private static final int MIN_CELL_DP = 96;
    private static final int GAP_DP = 8;
    /** 设置页自己的强调色（"添加按钮"之类的链接色），选中框用它。 */
    private static final int SELECTED = 0xFF8AB4F8;

    private final Theme[] themes;
    private final LinearLayout[] cells;
    private final LinearLayout[] swatches;
    private final float density;

    ThemeGrid(Context c, Theme[] themes, String[] names, Listener listener) {
        super(c);
        this.themes = themes;
        density = c.getResources().getDisplayMetrics().density;
        cells = new LinearLayout[themes.length];
        swatches = new LinearLayout[themes.length];
        for (int i = 0; i < themes.length; i++) {
            Theme t = themes[i];
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            int p = dp(5);
            cell.setPadding(p, p, p, dp(4));
            cell.setContentDescription(names[i]);
            cell.setOnClickListener(v -> listener.onPick(t));
            LinearLayout swatch = new LinearLayout(c);
            swatch.setOrientation(LinearLayout.VERTICAL);
            swatch.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // 键按实体键盘排，从右往左的语言下也不镜像（同主界面）
            int sp = dp(6);
            swatch.setPadding(sp, sp, sp, sp);
            cell.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
            TextView name = new TextView(c);
            name.setText(names[i]);
            name.setTextColor(0xFFE8E8E8);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            // 放不下先缩字号（"Catppuccin Latte"、"Solarized Light"），缩到 9sp 还放不下就省略
            name.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
            np.topMargin = dp(4);
            cell.addView(name, np);
            addView(cell);
            cells[i] = cell;
            swatches[i] = swatch;
        }
    }

    /** 按选中的主题、"按键边框"开关重画所有色块和选中框。 */
    void show(String selectedId, boolean keyBorders) {
        for (int i = 0; i < themes.length; i++) {
            boolean on = themes[i].id.equals(selectedId);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(14));
            // 没选中的也留一圈透明的边，选中时不跳位置
            frame.setStroke(dp(2.5f), on ? SELECTED : 0);
            frame.setColor(on ? 0x268AB4F8 : 0);
            cells[i].setBackground(frame);
            cells[i].setSelected(on);
            drawSwatch(swatches[i], themes[i], keyBorders);
        }
    }

    /** 一格的样子：页面底色上一行 "A"（普通键）+ "⇧"（功能键），下面一小块触控板。 */
    private void drawSwatch(LinearLayout box, Theme t, boolean keyBorders) {
        box.removeAllViews();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(t.bg);
        bg.setStroke(Math.max(1, dp(1)), 0xFF5F6368);   // 纯黑的页面贴着设置页的深色底也看得出边
        box.setBackground(bg);
        LinearLayout keys = new LinearLayout(getContext());
        String[] labels = {"A", "⇧"};
        for (int i = 0; i < labels.length; i++) {
            TextView k = Style.button(getContext(), 14, t, keyBorders);
            k.setText(labels[i]);
            Style.look(k, t, keyBorders, i == 1, false, false);
            k.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) kp.leftMargin = dp(4);
            keys.addView(k, kp);
        }
        box.addView(keys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        GradientDrawable pad = new GradientDrawable();
        pad.setCornerRadius(dp(8));
        pad.setColor(t.padFill);
        pad.setStroke(Math.max(1, Math.round(t.borderDp * density)), t.padBorder);
        View padView = new View(getContext());
        padView.setBackground(pad);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f);
        pp.topMargin = dp(5);
        box.addView(padView, pp);
    }

    // ═══════════ 摆放：每行几格按宽度定 ═══════════

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
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;   // 阿拉伯文：从右往左排格子（格子里的键不镜像）
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
