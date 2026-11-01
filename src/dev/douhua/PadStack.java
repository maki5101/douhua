package dev.douhua;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 主界面的触控板（连四个角上的小按钮）+ 顶上正中的 ⋯（2026-11-01：和键盘页一样，点开是 键盘 / 设置 / 旋转 / 退出）
 * + 可选的三键条（设置 → 触控板里的开关，放触控板上沿 / 下沿；高 48dp，中键窄、左右键宽，照 ThinkPad 约 2.2 : 1 : 2.2、中键在正中）。
 * 三键条从触控板里切出来（不是盖在上面）：角上的小按钮、底部状态字、边缘滚动带都跟着留在剩下的触控板里。
 * 怎么切和键盘页一样（KeyboardPlan.cutBar）：离窗口四条边至少 20dp（拿手机的手指常压在边上）、切完触控板短边不够 96dp 就不切。
 * ⋯ 也和键盘页一样（KeyboardPlan.menuTop）：剩下的触控板顶上正中、离窗口边至少 20dp，三键条在上沿时在三键条正下方隔一条缝；
 * 主界面没有功能行可退，放不下时照样放（KeyboardPlan.padMenuTop：不出触控板，离边少一点）。叠在角上的小按钮上面（窄触控板时会挨着）。
 * 离窗口边多远要知道自己在窗口里的位置，所以在 onLayout 里算、再量子 view。
 */
final class PadStack extends ViewGroup {
    private final View padFrame;
    /** padFrame 里的触控板（在 padFrame 左上角、和它一样大）：有背景图时在 ⋯ 下面垫一块底色，要告诉它 ⋯ 在哪。 */
    private final TrackpadView pad;
    private final MouseKeys keys;
    private final View menuButton;
    /** ⋯ 按得到的范围（同键盘页：56 × 36dp，不画框）。 */
    private final int menuW, menuH;
    private final KeyboardPlan.Sizes sizes;
    /**
     * 三键条、⋯ 离窗口边至少多远（20dp）。按窗口算（不是按主界面那一页）：屏幕边缘留白时页面往里收了，触控板跟着在里面，
     * 这里照旧离屏幕边 20dp，留白比 20dp 宽时三键条、⋯ 本来就在触控板里、自然更靠里（不用另外知道留白多少）。
     */
    private final int edge;
    private int keysMode = KeyboardPlan.KEYS_OFF;
    private boolean keysShown;
    private final int[] loc = new int[2];

    PadStack(Context c, View padFrame, TrackpadView pad, MouseKeys keys, View menuButton) {
        super(c);
        this.padFrame = padFrame;
        this.pad = pad;
        this.keys = keys;
        this.menuButton = menuButton;
        menuW = Style.dp(c, 56);
        menuH = Style.dp(c, 36);
        // 同键盘页（KeyboardScreen）：缝 8、触控板短边 96、三个键各至少 44 × 48（2026-11-01 从 36 加高）、隔 4、中键至少 56、离边 20（dp）
        edge = Style.dp(c, 20);
        sizes = new KeyboardPlan.Sizes(Style.dp(c, 8), Style.dp(c, 96), Style.dp(c, 32), Style.dp(c, 64), Style.dp(c, 32),
                Style.dp(c, KeyboardScreen.STICK_GAP_DP), Style.sp(c, KeyboardScreen.LABEL_ROOM_SP),
                Style.dp(c, 44), Style.dp(c, 48), Style.dp(c, 4), Style.dp(c, 56), Style.dp(c, 2), edge);
        addView(padFrame);
        addView(keys);
        addView(menuButton);
    }

    /** 三键条：KeyboardPlan.KEYS_*（不要 / 下沿 / 上沿）。 */
    void setKeys(int mode) {
        if (mode == keysMode) return;
        keysMode = mode;
        requestLayout();
    }

    /** 三键条摆出来了吗（开着、而且切得下）：摆出来才报它的区域。 */
    boolean keysShown() {
        return keysShown;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        padFrame.measure(exactly(w), exactly(h));   // 切不切 onLayout 里才知道（要看离窗口边多远），先按整块量
        keys.measure(exactly(0), exactly(0));
        menuButton.measure(exactly(menuW), exactly(menuH));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        getLocationInWindow(loc);
        View root = getRootView();
        int rw = root.getWidth(), rh = root.getHeight();
        int[] whole = {loc[0], loc[1], loc[0] + w, loc[1] + h};
        int[][] cut = keysMode == KeyboardPlan.KEYS_OFF ? null
                : KeyboardPlan.cutBar(whole, rw, rh, keysMode == KeyboardPlan.KEYS_TOP, sizes);
        keysShown = cut != null;
        int[] p = cut != null ? cut[0] : whole;
        place(padFrame, p[0] - loc[0], p[1] - loc[1], p[2] - loc[0], p[3] - loc[1]);
        if (cut != null) {
            int[] k = cut[1];
            // 中键窄、左右键宽，对着三键条的中线（同键盘页，KeyboardPlan.middleKey）；setMiddle 改了的话 place 照样重新量
            int[] m = KeyboardPlan.middleKey(k, (k[0] + k[2]) / 2, sizes);
            keys.setMiddle(m[0] - k[0], m[1] - k[0]);
            place(keys, k[0] - loc[0], k[1] - loc[1], k[2] - loc[0], k[3] - loc[1]);
        } else {
            place(keys, 0, 0, 0, 0);
        }
        // ⋯：剩下的触控板顶上正中；三键条在上沿时在它正下方、隔一条缝（同键盘页 KeyboardPlan.finish）
        int[] area = p;
        int margin = 0;
        if (cut != null && keysMode == KeyboardPlan.KEYS_TOP) {
            area = new int[]{p[0], cut[1][3], p[2], p[3]};
            margin = sizes.gap;
        }
        int[] at = KeyboardPlan.padMenuTop(area, rw, rh, menuW, menuH, margin, edge);
        place(menuButton, at[0] - loc[0], at[1] - loc[1], at[0] - loc[0] + menuW, at[1] - loc[1] + menuH);
        pad.setMenuChip(at[0] + menuW / 2f - p[0], at[1] + menuH / 2f - p[1]);
    }

    /** 量好再摆（尺寸在 onLayout 里才定）；尺寸没变但 view 自己标了要重新量（forceLayout，比如三键条换了中键宽度）也量。 */
    private static void place(View v, int l, int t, int r, int b) {
        if (v.getMeasuredWidth() != r - l || v.getMeasuredHeight() != b - t || v.isLayoutRequested()) {
            v.measure(exactly(r - l), exactly(b - t));
        }
        v.layout(l, t, r, b);
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(Math.max(0, size), MeasureSpec.EXACTLY);
    }
}
