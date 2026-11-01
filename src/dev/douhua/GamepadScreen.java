package dev.douhua;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

/**
 * 手柄页（桌面模式，独立一页：没有触控板、没有键盘）：按 {@link GamepadPlan} 摆的控件全由这里画，顶上正中一个 ⋯（MainActivity 建的
 * TextView，是这里唯一的子 view，按住 0.6 秒开菜单），⋯ 下面一行状态字。整页不透明、可点（服务没接管、又没走本地分流时挡住底下的主界面）。
 *
 * 触摸不在这里处理：服务接管时按报上去的区域分流（{@link #collectRegions}），没接管时 MainActivity 的本地分流按同一份区域分流
 * （TouchRouter → GamepadTouch），两条路都一样。画按下的样子全看手柄报文（键看位、十字键看方向、摇杆看轴、扳机看轴）：
 * 本地分流的报文（{@link #setReport} local）和服务发来的（MSG_GAMEPAD）合在一起画。浮动摇杆服务那一路不知道按下点，底座画在区域中心。
 * 画法在 {@link GamepadPainter}（设置页的预览也用它），颜色从当前主题现取。
 * 一直按物理左右摆（阿拉伯文下左手区也在左边）：坐标全是绝对的，不跟布局方向镜像。
 */
final class GamepadScreen extends ViewGroup {
    private final TextView menuButton;
    private final float density;
    private GamepadSpec spec = GamepadSpec.byId(GamepadSpec.DEFAULT_ID);
    private GamepadPlan.Plan plan;
    /** ⋯ 和状态字离本页各边至少多远（像素，EdgeMargin.edges）。 */
    private final int[] edges = new int[4];

    private final byte[] localReport = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private final byte[] serverReport = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private final byte[] merged = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private final Hid.Gamepad shown = new Hid.Gamepad();

    private String status = "";
    private boolean statusOk = true;
    private boolean statusTappable;

    private final GamepadPainter painter = new GamepadPainter();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];

    GamepadScreen(Context c, TextView menuButton) {
        super(c);
        this.menuButton = menuButton;
        density = Style.density(c);
        setWillNotDraw(false);
        setClickable(true);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        text.setTextAlign(Paint.Align.CENTER);
        Arrays.fill(edges, Math.round(20 * density));
        addView(menuButton);
    }

    /** 换预设 / 自定义改过（内容一样的不算换：applyPrefs 每次都给一份新解出来的自定义）。 */
    void setSpec(GamepadSpec s) {
        if (s == null || s.key().equals(spec.key())) return;
        spec = s;
        clearReports();
        requestLayout();
        invalidate();
    }

    GamepadSpec spec() {
        return spec;
    }

    /** 本页离各边至少多远 {左, 上, 右, 下}（像素）：屏幕边缘留白把本页往里收了以后，⋯ 和状态字照旧离屏幕边 20dp。 */
    void setEdgeInsets(int[] e) {
        if (Arrays.equals(e, edges)) return;
        System.arraycopy(e, 0, edges, 0, 4);
        requestLayout();
    }

    /** 本地分流（local = true）或服务（MSG_GAMEPAD）的手柄报文：合在一起画。 */
    void setReport(boolean local, byte[] r) {
        byte[] to = local ? localReport : serverReport;
        if (Arrays.equals(to, r)) return;
        System.arraycopy(r, 0, to, 0, to.length);
        invalidate();
    }

    /** 两路都当中立（进出手柄页、换预设、断开）。 */
    void clearReports() {
        Arrays.fill(localReport, (byte) 0);
        Arrays.fill(serverReport, (byte) 0);
        invalidate();
    }

    void setStatus(String s, boolean ok) {
        if (s == null) s = "";
        if (s.equals(status) && ok == statusOk) return;
        status = s;
        statusOk = ok;
        invalidate();
    }

    void setStatusTappable(boolean tappable) {
        statusTappable = tappable;
    }

    /** 这一点（屏幕坐标）落在状态字那一行上吗（可点时打开设置页）。 */
    boolean statusHit(float rawX, float rawY) {
        if (!statusTappable || plan == null || status.isEmpty() || getVisibility() != VISIBLE) return false;
        getLocationOnScreen(loc);
        float x = (rawX - loc[0]) / density, y = (rawY - loc[1]) / density;
        float[] s = plan.status;
        return x >= s[0] && x < s[2] && y >= s[1] - 6 && y < s[3] + 6;   // 上下各宽 6dp，好点一点
    }

    void applyTheme() {
        Style.bare(menuButton, false);
        invalidate();
    }

    /**
     * 手柄控件的区域（屏幕像素，顺序同 GamepadPlan：簇的范围在成员前面），接在 ⋯ 后面。Proto.REGION_GAMEPAD，id 是手柄控件编号。
     */
    void collectRegions(List<int[]> regions) {
        if (plan == null) return;
        getLocationOnScreen(loc);
        for (GamepadPlan.Control c : plan.controls) {
            regions.add(new int[]{Proto.REGION_GAMEPAD, c.id, loc[0] + px(c.l), loc[1] + px(c.t), loc[0] + px(c.r), loc[1] + px(c.b)});
        }
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        plan = w > 0 && h > 0 ? GamepadPlan.plan(spec, w / density, h / density,
                new float[]{edges[0] / density, edges[1] / density, edges[2] / density, edges[3] / density}) : null;
        int mw = plan != null ? px(plan.menu[2] - plan.menu[0]) : 0, mh = plan != null ? px(plan.menu[3] - plan.menu[1]) : 0;
        menuButton.measure(MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(mh, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (plan == null) {
            menuButton.layout(0, 0, 0, 0);
            return;
        }
        int x = px(plan.menu[0]), y = px(plan.menu[1]);
        menuButton.layout(x, y, x + menuButton.getMeasuredWidth(), y + menuButton.getMeasuredHeight());
    }

    // ═══════════ 画 ═══════════

    @Override
    protected void onDraw(Canvas canvas) {
        Theme t = Style.theme();
        canvas.drawColor(t.bg);
        if (plan == null) return;
        Hid.Gamepad.merge(serverReport, localReport, merged);
        shown.parse(merged);
        painter.draw(canvas, spec, plan, shown, t, Style.keyBorders(), density, 8 * density);
        drawStatus(canvas, t);
    }

    private void drawStatus(Canvas canvas, Theme t) {
        if (status.isEmpty()) return;
        float[] s = plan.status;
        text.setColor(statusOk ? t.status : t.statusBad);
        text.setTextSize(13 * density);
        float room = (s[2] - s[0]) * density;
        CharSequence line = text.measureText(status) > room ? TextUtils.ellipsize(status, text, room, TextUtils.TruncateAt.END) : status;
        Paint.FontMetrics fm = text.getFontMetrics();
        float cy = (s[1] + s[3]) / 2 * density;
        canvas.drawText(line, 0, line.length(), (s[0] + s[2]) / 2 * density, cy - (fm.ascent + fm.descent) / 2, text);
    }

    /**
     * 预设 / 自定义显示的名字：主机 / 手柄名不翻译（GamepadSpec.name），"Mega Drive 6 键""街机 8 键""自定义"这几个按语言。
     * 手柄页 ⋯ 菜单、设置页"手柄"标签共用。
     */
    static String name(Context c, GamepadSpec spec) {
        if (spec.name != null) return spec.name;
        switch (spec.id) {
            case "md3": return c.getString(R.string.gp_md3);
            case "md6": return c.getString(R.string.gp_md6);
            case "arcade6": return c.getString(R.string.gp_arcade6);
            case "arcade8": return c.getString(R.string.gp_arcade8);
            default: return c.getString(R.string.gp_custom);
        }
    }
}
