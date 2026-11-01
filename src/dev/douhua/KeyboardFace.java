package dev.douhua;

import java.util.List;

/**
 * 键盘页上的一块键盘面板对外的共同方法（第 1B 批，2026-11-01）：豆花布局（KeyboardPanel）和实体键盘（FixedKeyboardView）两块并存，
 * KeyboardScreen 同一时刻只显示、只报一块（{@link KeyboardScreen#face}）。
 * 状态类（修饰键、主题、松开、收掉按下的样子）MainActivity 两块都调；区域、按下的样子只调显示着的那块。
 * setMenuKey / setHole / setStick 由 KeyboardScreen 量尺寸时、在量这块面板之前调（同 KeyboardPanel 原来的规矩）。
 */
interface KeyboardFace {
    /**
     * 修饰键和 Caps 的显示（2026-11-01 起；以前只有锁定掩码）：latched / held / armed 同 Proto.MSG_MODIFIERS（HID 修饰键位），
     * caps = 豆花这边显示的大写锁定（只有 App 一份，MainActivity.capsShown）。高亮画 latched | armed（锁住的、"现在松手就锁"的）和 Caps，
     * 键帽上的字照 latched | held 换（按住 Shift 键帽就大写）、字母再看 caps。按着的样子另外画（showPressed / 自己收的手指）。
     * fn = Fn 的状态（协议 21，Proto.FN_LATCHED / FN_HELD / FN_ARMED 位）：只有实体键盘 75% / 65% / 苹果紧凑画（Fn 键的高亮看锁住 | 快锁了，
     * 带 Fn 层的键帽在锁住 | 按着时整片换成 Fn 层的字），豆花布局不看。
     */
    void setKeyState(int latched, int held, int armed, int fn, boolean caps);

    /** 画按下的样子（id 同 Proto.REGION_KEY / REGION_BUTTON 的 id）。 */
    void showPressed(int id, boolean pressed);

    /** 面板自己收的手指按着的键松开（不算点击），按下的样子、放大提示收掉。 */
    void releaseTouches();

    /** 所有键画回松开的样子、收起放大提示（服务断了，它转来的按下等不到松开）。 */
    void clearPressed();

    /** 换了主题 / 按键边框开关：重新上色。 */
    void applyTheme();

    /** 把键和键盘范围在屏幕上的位置加到 regions 后面（键在前、范围在后）。 */
    void collectRegions(List<int[]> regions);

    /** ⋯ 键摆在哪（面板坐标 {左, 上, 右, 下}）；没带 ⋯ 键时返回 null。 */
    int[] menuKeyRect();

    /** 带不带 ⋯ 键（小触控板上没摆出 ⋯ 时带）。 */
    void setMenuKey(boolean on);

    /** 小红点状态下让给三个鼠标键的空位 {左, 右}（面板 x；null = 不让）、快捷行也让不让（实体键盘没有快捷行，不看）。 */
    void setHole(int[] hole, boolean spread);

    /** 嵌在键里的小红点：按得到的正方形（面板坐标，按内切圆算）、键上切口的半径；hit 是 null = 没嵌在键里。 */
    void setStick(int[] hit, float cut);

    void setPreviewHost(KeyboardPanel.PreviewHost host);
}
