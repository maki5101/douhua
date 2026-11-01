package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 实体键盘面板摆到像素以后的样子，纯 Java（离线测，FixedLayoutTest）：每个键摆在哪（按行切成几段矩形）、键盘范围、
 * 报给服务的区域、面板自己收触摸时哪根手指按的是哪个键、键帽上画哪几个字、被小红点切掉的那块。
 * FixedKeyboardView（Android）只按这里的结果画和收触摸，规则都在这里（离线测得到；上一轮界面类没测到，漏了一个必闪退的空指针）。
 *
 * 摆放（方案 §5.2）：面板宽高各按 u 等分（不必等比），键的边界都取整、相邻的键共用同一条边界，四边各缩进 gap（同 KeyRows.placeRow）。
 * 跨两行的键（ISO 回车、小键盘 + 和 Enter）报区域、收触摸时按行切成同一个 id 的几段矩形，画的时候画成一个形状（{@link #shape}）。
 * 小红点状态（KeyboardScreen 量尺寸时设，同豆花布局）：空格那一排在中间让出空位给三个鼠标键（{@link #setHole}），空格被切成左右两段；
 * 红点嵌在 G、H、B 中间时碰到切口圆的键帽沿圆弧画、字竖着挪开（{@link #setStick}，规则同豆花布局：KeyboardPlan.cuts / labelShift）。
 */
final class FixedPanel {
    /** 键的一段（面板像素，已经扣掉四周的缝）：key = 当前显示的键（{@link #keys}）的下标。 */
    static final class Seg {
        final int key;
        final int left, top, right, bottom;

        Seg(int key, int left, int top, int right, int bottom) {
            this.key = key;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /** 键帽上的一个字：放在哪（正中 / 上半 / 下半 / 角上的小字）、是不是死键、要不要压暗（锁着修饰键时不会出的那几个）。 */
    static final class Glyph {
        static final int CENTER = 0, TOP = 1, BOTTOM = 2, CORNER = 3;
        final String text;
        final int slot;
        final boolean dead, dim;

        Glyph(String text, int slot, boolean dead, boolean dim) {
            this.text = text;
            this.slot = slot;
            this.dead = dead;
            this.dim = dim;
        }

        @Override
        public String toString() {
            return text + "@" + slot + (dead ? "（死键）" : "") + (dim ? "（暗）" : "");
        }
    }

    /** 角上小字放哪个角。 */
    static final int CORNER_BR = 0, CORNER_TR = 1, CORNER_BL = 2, CORNER_TL = 3;

    private FixedLayout layout;
    private FixedLegends.Table legends;
    private boolean menuKey;
    private int[] hole;
    private int[] stickHit;
    private float stickCut;
    private int width, height, gap;
    private float labelRoom;

    private List<FixedLayout.Key> keys = new ArrayList<>();
    private final List<Seg> segs = new ArrayList<>();
    private final List<int[]> ranges = new ArrayList<>();
    /** 每个键画出来的外框（面板像素，几段的外接矩形），切口 {圆心 x, 圆心 y, 半径}（面板坐标；没被切到是 null），字竖着挪多少，角上小字放哪个角。 */
    private int[][] boxes = new int[0][];
    private float[][] cuts = new float[0][];
    private float[] shifts = new float[0];
    private int[] corners = new int[0];

    /**
     * 换一块键盘（布局 + 字表）。字表可以是 null：出字的键照样有键帽，只是没有字（FixedLayoutTest 锁住现在的数据每个键都有字）；
     * App 里不会这样传：字表读不出来时 FixedLayout.forLegends 不拼布局，KeyboardScreen 照样显示豆花布局。
     */
    void setBoard(FixedLayout layout, FixedLegends.Table legends) {
        this.layout = layout;
        this.legends = legends;
        keys = layout == null ? new ArrayList<>() : layout.keys(menuKey);
        unplace();
    }

    /**
     * 键换了（换键盘、⋯ 槽放不放 ⋯）、还没重新摆：段、范围清掉（不报区域、不认手指，等下一次 {@link #place}），每个键的外框是空的，
     * 下标都对得上新的键（服务转来的按下、放大提示照样能查，不会越界）。
     */
    private void unplace() {
        segs.clear();
        ranges.clear();
        int n = keys.size();
        boxes = new int[n][];
        for (int i = 0; i < n; i++) boxes[i] = new int[4];
        cuts = new float[n][];
        shifts = new float[n];
        corners = new int[n];
    }

    FixedLayout layout() {
        return layout;
    }

    FixedLegends.Table legends() {
        return legends;
    }

    /** ⋯ 放在 Esc 和 F1 之间的槽里（小触控板上没摆出 ⋯ 时）。键会换（多一个）：调用方先松开按着的。 */
    void setMenuKey(boolean on) {
        if (on == menuKey) return;
        menuKey = on;
        if (layout != null) keys = layout.keys(on);
        unplace();
    }

    boolean menuKey() {
        return menuKey;
    }

    /** 小红点状态下空格那一排让出的空位 {左, 右}（面板 x，KeyboardPlan.Plan.hole；null = 不让）。 */
    void setHole(int[] hole) {
        this.hole = hole == null ? null : hole.clone();
    }

    int[] hole() {
        return hole == null ? null : hole.clone();
    }

    /** 嵌在键里的小红点：按得到的正方形 {左, 上, 右, 下}（面板坐标，按内切圆算）、键上切口的半径；没嵌在键里时 hit 是 null。 */
    void setStick(int[] hit, float cut) {
        stickHit = hit == null ? null : hit.clone();
        stickCut = stickHit == null ? 0 : cut;
    }

    int[] stickHit() {
        return stickHit == null ? null : stickHit.clone();
    }

    /** 当前显示的键（含 ⋯ 槽里的 ⋯）。 */
    List<FixedLayout.Key> keys() {
        return keys;
    }

    List<Seg> segs() {
        return segs;
    }

    /** 键盘范围（面板像素 {左, 上, 右, 下}）。 */
    List<int[]> ranges() {
        return ranges;
    }

    int[] box(int key) {
        return key >= 0 && key < boxes.length ? boxes[key] : new int[4];
    }

    float[] cut(int key) {
        return key >= 0 && key < cuts.length ? cuts[key] : null;
    }

    float shift(int key) {
        return key >= 0 && key < shifts.length ? shifts[key] : 0;
    }

    int corner(int key) {
        return key >= 0 && key < corners.length ? corners[key] : CORNER_BR;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    // ═══════════ 摆放 ═══════════

    /**
     * 摆进 w × h 的面板（gap = 每个键四边缩进多少，labelRoom = 键上的字离切口多远，同 KeyboardPlan.Sizes.labelRoom）。
     * 每次量尺寸、换键盘 / ⋯ / 空位 / 红点后调。
     */
    void place(int w, int h, int gap, float labelRoom) {
        this.width = w;
        this.height = h;
        this.gap = gap;
        this.labelRoom = labelRoom;
        segs.clear();
        ranges.clear();
        int n = keys.size();
        boxes = new int[n][];
        cuts = new float[n][];
        shifts = new float[n];
        corners = new int[n];
        if (layout == null || w <= 0 || h <= 0) {
            for (int i = 0; i < n; i++) boxes[i] = new int[4];
            return;
        }
        segs.addAll(segments(layout, keys, w, h, gap, hole));
        for (FixedLayout.Range r : layout.ranges(menuKey)) {
            ranges.add(new int[]{px(r.left, w, layout.width), px(r.top, h, FixedLayout.HEIGHT),
                    px(r.right, w, layout.width), px(r.bottom, h, FixedLayout.HEIGHT)});
        }
        for (int i = 0; i < n; i++) boxes[i] = null;
        for (Seg s : segs) {
            int[] b = boxes[s.key];
            if (b == null) boxes[s.key] = new int[]{s.left, s.top, s.right, s.bottom};
            else boxes[s.key] = new int[]{Math.min(b[0], s.left), Math.min(b[1], s.top), Math.max(b[2], s.right), Math.max(b[3], s.bottom)};
        }
        for (int i = 0; i < n; i++) if (boxes[i] == null) boxes[i] = new int[4];
        // 空格被空位切成两段时外框不连起来（画的是两个键帽）：外框只用来放字，取左边那段
        int[] sp = null;
        for (Seg s : segs) if (keys.get(s.key).id == 0x2C) { sp = new int[]{s.left, s.top, s.right, s.bottom}; break; }
        if (sp != null && hole != null) boxes[index(0x2C)] = sp;
        boolean cutOn = stickHit != null && stickCut > 0;
        float cx = cutOn ? (stickHit[0] + stickHit[2]) / 2f : 0, cy = cutOn ? (stickHit[1] + stickHit[3]) / 2f : 0;
        for (Seg s : segs) {
            if (!cutOn) break;
            KeyRows.Placed p = new KeyRows.Placed(keys.get(s.key).face, s.left, s.top, s.right, s.bottom);
            if (!KeyboardPlan.cuts(p, cx, cy, stickCut)) continue;
            cuts[s.key] = new float[]{cx, cy, stickCut};
        }
        for (int i = 0; i < n; i++) {
            int[] b = boxes[i];
            if (cuts[i] == null) {
                corners[i] = CORNER_BR;
                continue;
            }
            KeyRows.Placed p = new KeyRows.Placed(keys.get(i).face, b[0], b[1], b[2], b[3]);
            float d = KeyboardPlan.labelShift(p, cx, cy, stickCut, labelRoom);
            shifts[i] = Float.isNaN(d) ? 0 : d;
            corners[i] = farCorner(b, cx, cy);
        }
    }

    /** 面板坐标的 u → 像素（取整，同一个 u 值永远落在同一个像素上，相邻的键共用边界）。 */
    static int px(float u, int size, float total) {
        return Math.round(size * u / total);
    }

    /** 离 (cx, cy) 最远的那个角（切口在右下就放左上），一样远按 右下、右上、左下、左上。 */
    static int farCorner(int[] b, float cx, float cy) {
        float[][] pts = {{b[2], b[3]}, {b[2], b[1]}, {b[0], b[3]}, {b[0], b[1]}};
        int best = 0;
        float bestD = -1;
        for (int i = 0; i < 4; i++) {
            float dx = pts[i][0] - cx, dy = pts[i][1] - cy;
            float d = dx * dx + dy * dy;
            if (d > bestD + 1e-3f) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * 每个键的每一段（面板像素，四边缩进 gap），顺序同 keys（一个键的几段挨着）。hole = 空格那一排让出的空位 {左, 右}（null = 不让）：
     * 空格切成左右两段，各从空格原来的边到空位的边。
     */
    static List<Seg> segments(FixedLayout layout, List<FixedLayout.Key> keys, int w, int h, int gap, int[] hole) {
        List<Seg> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            FixedLayout.Key k = keys.get(i);
            for (int r = k.row; r < k.row + Math.max(k.rows, k.lShape() ? 2 : 1); r++) {
                float[] s = k.span(r);
                if (s == null) continue;
                int l = px(s[0], w, layout.width), rr = px(s[1], w, layout.width);
                int t = px(FixedLayout.rowTop(r), h, FixedLayout.HEIGHT), b = px(FixedLayout.rowBottom(r), h, FixedLayout.HEIGHT);
                if (hole != null && k.id == 0x2C && hole[0] > l && hole[1] < rr) {
                    out.add(new Seg(i, l + gap, t + gap, hole[0] - gap, b - gap));
                    out.add(new Seg(i, hole[1] + gap, t + gap, rr - gap, b - gap));
                } else {
                    out.add(new Seg(i, l + gap, t + gap, rr - gap, b - gap));
                }
            }
        }
        return out;
    }

    /** 同 {@link #segments}，换成 KeyboardPlan 算切口用的 KeyRows.Placed（每段一个，face 同键）。 */
    static List<KeyRows.Placed> placed(FixedLayout layout, boolean menuKey, int w, int h, int gap, int[] hole) {
        List<FixedLayout.Key> ks = layout.keys(menuKey);
        List<KeyRows.Placed> out = new ArrayList<>();
        for (Seg s : segments(layout, ks, w, h, gap, hole)) {
            out.add(new KeyRows.Placed(ks.get(s.key).face, s.left, s.top, s.right, s.bottom));
        }
        return out;
    }

    /**
     * 小红点嵌在哪（面板坐标 {x, y}）：真 ThinkPad 的位置（2026-11-01 用户），G、H 之间、B 上方——横着是 G|H 那条缝（= B 的中线，
     * 实体键盘最下一排比中间一排右错半个键），竖着在 G、H 键帽的下沿（真机上圆心大约在这条线上、比两排之间那条缝的中线略高，
     * thinkpad-real 调研第一节第 3 条），gap = 键帽四边缩进多少。
     */
    static int[] stickPoint(FixedLayout layout, int w, int h, int gap) {
        return new int[]{px(layout.stickU(), w, layout.width), px(FixedLayout.rowTop(4), h, FixedLayout.HEIGHT) - gap};
    }

    /** 空格那一排的上沿（面板像素）：三个鼠标键从这里铺到面板下沿，红点的切口不过这条线。 */
    static int spaceRowTop(int h) {
        return px(FixedLayout.rowTop(5), h, FixedLayout.HEIGHT);
    }

    /**
     * 小红点状态下空格那一排在中间让出的空位 {左, 右}（面板 x，面板宽 w），让不出返回 null。规则同豆花布局（KeyboardPlan.stickHole）：
     * ① 中键正对红点、左右键一样宽：空位以红点那条竖线为中线左右对称；② 空格尽量长：空位两边各剩的空格最长 KeyRows.STICK_SPACE 份（1.5 个字母键宽）；
     * ③ 三个鼠标键各至少 keyW（Sizes.holeWidth）。三个 keyW 宽的键放不下时空格缩短，最短 KeyRows.STICK_SPACE_MIN 份（1 个字母键宽）；
     * 还放不下就让到最宽（空格剩 1 份），三个键最窄 minKey；再窄就不让（三个鼠标键不显示，红点照样有）。
     * 实体键盘的空格在一排的中间（两边是修饰键），所以空位两边各剩一段空格（发同一个键），哪只手的拇指都按得到。
     * 红点那条竖线在空格里偏左（ANSI / ISO 都是空格 3.75–10u、红点 6.75u），右边那段多 0.25u。
     */
    static int[] stickHole(FixedLayout layout, int w, KeyboardPlan.Sizes s) {
        FixedLayout.Key space = layout.find(0x2C);
        if (space == null || w <= 0) return null;
        float unit = w / layout.width;
        int cx = px(layout.stickU(), w, layout.width);
        int left = px(space.x, w, layout.width), right = px(space.x + space.w, w, layout.width);
        int narrow = Math.min(cx - left, right - cx);
        int hwMax = narrow - Math.round(unit * KeyRows.STICK_SPACE_MIN);   // 空格最短那段剩 1 份
        int hwPref = narrow - Math.round(unit * KeyRows.STICK_SPACE);      // 空格那段剩 1.5 份
        int hw = Math.max(hwPref, (s.holeWidth(s.keyW) + 1) / 2);
        if (hw <= hwMax && hw > 0) return new int[]{cx - hw, cx + hw};
        if (hwMax > 0 && 2 * hwMax >= s.holeWidth(s.minKey)) return new int[]{cx - hwMax, cx + hwMax};
        return null;
    }

    // ═══════════ 区域和触摸 ═══════════

    /**
     * 报给服务的区域（加到 out 后面，每项 {type, id, 左, 上, 右, 下}，屏幕像素；dx / dy = 面板在屏幕上的左上角）：
     * 先是每个键的每一段（特殊键是 REGION_BUTTON，别的 REGION_KEY，id 是用法码 / 按钮编号），后面是键盘范围（同 KeyboardPanel：hit 先找包含的键）。
     */
    void regions(int dx, int dy, List<int[]> out) {
        for (Seg s : segs) {
            FixedLayout.Key k = keys.get(s.key);
            out.add(new int[]{k.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, k.id, dx + s.left, dy + s.top, dx + s.right, dy + s.bottom});
        }
        for (int[] r : ranges) out.add(new int[]{Proto.REGION_KEYBOARD, 0, dx + r[0], dy + r[1], dx + r[2], dy + r[3]});
    }

    /**
     * 面板坐标 (x, y) 该算哪个键（{@link #keys} 的下标），不算任何键返回 -1。规则同分流：包含的键段优先，缝里交给落进的那块范围里最近的键
     * （KeyFingers.pick → TouchRouter.nearestKey）；嵌着的红点圆（REGION_STICK 按内切圆）、让给三个鼠标键的空位是洞，不算任何键。
     */
    int keyAt(float x, float y) {
        TouchRouter.Region[] rs = new TouchRouter.Region[segs.size()];
        for (int i = 0; i < rs.length; i++) {
            Seg s = segs.get(i);
            // 编号直接用键的下标（只在这里用）
            rs[i] = new TouchRouter.Region(keys.get(s.key).special ? Proto.REGION_BUTTON : Proto.REGION_KEY, s.key,
                    s.left, s.top, s.right, s.bottom);
        }
        TouchRouter.Region[] areas = new TouchRouter.Region[ranges.size()];
        for (int i = 0; i < areas.length; i++) {
            int[] a = ranges.get(i);
            areas[i] = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, a[0], a[1], a[2], a[3]);
        }
        TouchRouter.Region r = KeyFingers.pick(rs, areas, holes(), x, y);
        return r == null ? -1 : r.id;
    }

    /** 面板自己收触摸时的洞（面板坐标）：嵌着的红点（按内切圆）、空格那一排让给三个鼠标键的空位。 */
    TouchRouter.Region[] holes() {
        List<TouchRouter.Region> out = new ArrayList<>();
        if (stickHit != null && stickCut > 0) {
            out.add(new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, stickHit[0], stickHit[1], stickHit[2], stickHit[3]));
        }
        if (hole != null && height > 0) {
            out.add(new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, 0, hole[0], spaceRowTop(height), hole[1], height));
        }
        return out.toArray(new TouchRouter.Region[0]);
    }

    /** ⋯ 键摆在哪（面板坐标 {左, 上, 右, 下}）；槽里没放 ⋯ 时返回 null。 */
    int[] menuKeyRect() {
        if (!menuKey) return null;
        int i = index(FixedLayout.MENU_ID);
        return i < 0 ? null : box(i).clone();
    }

    /** 这个 id（用法码 / 按钮编号）是第几个键，没有返回 -1。 */
    int index(int id) {
        for (int i = 0; i < keys.size(); i++) if (keys.get(i).id == id) return i;
        return -1;
    }

    // ═══════════ 键帽上的字 ═══════════

    /**
     * 锁着 / 按着的修饰键（mods = TouchRouter 的 latched | held 掩码：左 Ctrl 0x01、左 Shift 0x02、左 Alt 0x04、左 GUI 0x08，右边四个 << 4；
     * 2026-11-01 起按住 Shift / AltGr / Option 也换层，同锁着）会选哪一层：
     * Shift = 任一 Shift；第三层 = Mac 的任一 Option（左右 Alt），Windows 的 AltGr（右 Alt，0x40）——只在这个布局有第三层、键帽写着 AltGr 时
     * （FixedLayout.altGr）；美式的右 Alt 写 Alt，锁住它和锁左 Alt 一样不换层（不然整块键盘的字全压暗，像出了故障）。layout 是 null 时不换第三层。
     */
    static int layer(int mods, FixedLayout layout) {
        boolean shift = (mods & 0x22) != 0;
        boolean third = layout != null && (layout.mac() ? (mods & 0x44) != 0 : layout.altGr && (mods & 0x40) != 0);
        return third ? (shift ? FixedLegends.THIRD_SHIFT : FixedLegends.THIRD) : shift ? FixedLegends.SHIFT : FixedLegends.NORMAL;
    }

    /** 这一格的字（画得出来的），没有 / 画不出来返回 null。 */
    private FixedLegends.Cell cell(int usage, int layer) {
        if (legends == null) return null;
        FixedLegends.Cell c = legends.get(usage, layer);
        return c == null || FixedLegends.printable(c.text) == null ? null : c;
    }

    /** 字母那种键：普通层是一个字母，Shift 层是它的大写（a / A、ü / Ü），只画一个字、跟着 Shift / Caps 换大小写（同豆花布局）。 */
    private static boolean letterLike(FixedLegends.Cell n, FixedLegends.Cell s) {
        if (n == null || s == null || n.dead || s.dead) return false;
        if (n.text.codePointCount(0, n.text.length()) != 1 || !Character.isLetter(n.text.codePointAt(0))) return false;
        String up = n.text.toUpperCase(Locale.ROOT);
        return !up.equals(n.text) && up.equals(s.text);
    }

    /**
     * 字母类的键这时出大写吗（2026-11-01，Caps 的显示）：Windows 模式 Shift ⊕ Caps（Windows / Linux / 安卓的大写锁定 + Shift 出小写），
     * Mac 模式 Shift ∨ Caps（macOS 大写锁定开着时 Shift 不反转；iPadOS 凭记忆同 macOS，没核对）。数字、标点、第三层不看 Caps。
     */
    static boolean upper(boolean shift, boolean caps, FixedLayout layout) {
        return layout != null && layout.mac() ? shift || caps : shift ^ caps;
    }

    /** 同 {@link #glyphs(int, int, boolean, String)}，Caps 关着。 */
    List<Glyph> glyphs(int i, int mods, String spaceName) {
        return glyphs(i, mods, false, spaceName);
    }

    /**
     * 第 i 个键键帽上画哪几个字（照实体键帽，方案 §1.1 第 5 条，用户 2026-11-01 定）：
     * - 功能键、小键盘、空格：正中一个名字（spaceName = 界面语言的"空格"）；
     * - 字母：正中一个字母，跟着锁住 / 按着的 Shift 和 Caps（caps = 豆花这边显示的大写锁定）换大小写（{@link #upper}）；
     * - 数字、标点：上半 Shift 层、下半普通层；
     * - 有第三层（AltGr / Option）的字：角上一个小字（平时右下；被小红点切到的键放离切口最远的角，{@link #corner}）。
     * 锁着 Shift / 第三层时，会出的那个字照常画、别的压暗（dim）；锁着第三层 + Shift 时角上换成第四层的字（有的话）。
     * 死键（dead）用强调色。画不出来的字（苹果标这种私用区字）不画。
     */
    List<Glyph> glyphs(int i, int mods, boolean caps, String spaceName) {
        List<Glyph> out = new ArrayList<>();
        FixedLayout.Key k = keys.get(i);
        if (!k.charKey()) {
            String name = FixedLayout.SPACE_NAME.equals(k.name) && k.id == 0x2C && spaceName != null ? spaceName : k.name;
            out.add(new Glyph(name, Glyph.CENTER, false, false));
            return out;
        }
        int layer = layer(mods, layout);
        boolean plain = layer == FixedLegends.NORMAL;
        boolean shift = layer == FixedLegends.SHIFT || layer == FixedLegends.THIRD_SHIFT;
        boolean third = layer >= FixedLegends.THIRD;
        FixedLegends.Cell n = cell(k.id, FixedLegends.NORMAL), s = cell(k.id, FixedLegends.SHIFT);
        FixedLegends.Cell t = cell(k.id, FixedLegends.THIRD), t4 = cell(k.id, FixedLegends.THIRD_SHIFT);
        if (letterLike(n, s)) {
            // 字母：Shift / Caps 定大小写（会出的就是它，不暗）；锁着第三层时会出的是角上那个，字母压暗
            FixedLegends.Cell c = upper(shift, caps, layout) ? s : n;
            out.add(new Glyph(c.text, Glyph.CENTER, c.dead, third));
        } else if (s != null && n != null) {
            // 上半 Shift 层：只锁着 Shift 时会出它；下半普通层：没锁时会出它
            out.add(new Glyph(s.text, Glyph.TOP, s.dead, !(plain || layer == FixedLegends.SHIFT)));
            out.add(new Glyph(n.text, Glyph.BOTTOM, n.dead, !plain));
        } else if (n != null || s != null) {
            FixedLegends.Cell c = n != null ? n : s;
            boolean comes = c == n ? plain : layer == FixedLegends.SHIFT;
            out.add(new Glyph(c.text, Glyph.CENTER, c.dead, !comes));
        }
        // 角上的第三层：锁着第三层时亮（第三层 + Shift 时换成第四层的字，没有第四层就压暗：什么都不出），锁着别的时压暗
        if (third && shift) {
            if (t4 != null) out.add(new Glyph(t4.text, Glyph.CORNER, t4.dead, false));
            else if (t != null) out.add(new Glyph(t.text, Glyph.CORNER, t.dead, true));
        } else if (t != null) {
            out.add(new Glyph(t.text, Glyph.CORNER, t.dead, !plain && !third));
        }
        return out;
    }

    /**
     * 按下第 i 个键时放大提示写什么：照锁住的修饰键会出的那个字（死键画附加符号本身）；功能键、修饰键、F 键、方向键、小键盘、空格不放大
     * （同豆花布局），返回 null。
     */
    String preview(int i, int mods) {
        return preview(i, mods, false);
    }

    /** 同上，caps = 豆花这边显示的大写锁定：字母类的键照 {@link #upper} 换大小写（第三层不看 Caps）。 */
    String preview(int i, int mods, boolean caps) {
        FixedLayout.Key k = keys.get(i);
        if (!k.charKey()) return null;
        int layer = layer(mods, layout);
        if (layer == FixedLegends.NORMAL || layer == FixedLegends.SHIFT) {
            FixedLegends.Cell n = cell(k.id, FixedLegends.NORMAL), s = cell(k.id, FixedLegends.SHIFT);
            if (letterLike(n, s)) return (upper(layer == FixedLegends.SHIFT, caps, layout) ? s : n).text;
        }
        FixedLegends.Cell c = cell(k.id, layer);
        return c == null ? null : c.text;
    }

    /** 第 i 个键算不算功能键（主题的功能键颜色，规则同豆花布局 KeyRows.functionKey）。 */
    boolean functionKey(int i) {
        return KeyRows.functionKey(keys.get(i).face, false);
    }

    /** 这个键是修饰键，而且锁着（高亮）：左右八个都认（豆花布局只画左边四个）。 */
    static boolean active(FixedLayout.Key k, int mods) {
        return active(k, mods, false);
    }

    /**
     * 这个键画不画高亮：修饰键看 shown（= latched | armed：锁住的、"现在松手就锁"的，左右八个都认），Caps 看 caps（豆花这边显示的大写锁定）。
     * 按着的样子另外画（按下事件），所以按住 0.4 秒那一刻是"按下 + 高亮"，1 秒后退回"只按下"。
     */
    static boolean active(FixedLayout.Key k, int shown, boolean caps) {
        if (k.special) return false;
        if (k.id == KeyHolds.CAPS) return caps;
        return k.id >= 0xE0 && k.id <= 0xE7 && (shown & (1 << (k.id - 0xE0))) != 0;
    }

    /**
     * 第 i 个键画成几个键帽（面板坐标 {左, 上, 右, 下}）：一般一个（两行高的键、L 形回车是外框，L 形按 {@link #shape} 画）；
     * 空格被让给三个鼠标键的空位切开时是左右两段，各画一个键帽、各写一遍"空格"（同分裂键盘的两个空格）。
     */
    List<int[]> capBoxes(int i) {
        List<int[]> out = new ArrayList<>();
        FixedLayout.Key k = keys.get(i);
        if (k.id == 0x2C && hole != null) {
            for (Seg s : segs) if (s.key == i) out.add(new int[]{s.left, s.top, s.right, s.bottom});
        }
        if (out.isEmpty()) out.add(box(i).clone());
        return out;
    }

    /** 第 i 个键的形状（面板坐标的多边形顶点 {x0, y0, x1, y1, …}，顺时针）：普通键是外框四个角，ISO 回车是 L 形六个角。 */
    float[] shape(int i) {
        int[] b = box(i);
        FixedLayout.Key k = keys.get(i);
        if (!k.lShape()) return new float[]{b[0], b[1], b[2], b[1], b[2], b[3], b[0], b[3]};
        Seg top = null, low = null;
        for (Seg s : segs) {
            if (s.key != i) continue;
            if (top == null) top = s;
            else low = s;
        }
        if (top == null || low == null) return new float[]{b[0], b[1], b[2], b[1], b[2], b[3], b[0], b[3]};
        return new float[]{top.left, top.top, top.right, top.top, top.right, low.bottom, low.left, low.bottom, low.left, top.bottom,
                top.left, top.bottom};
    }

    @Override
    public String toString() {
        return "FixedPanel " + (layout == null ? "-" : layout.shape + "/" + layout.size + "/" + layout.mode) + " " + width + "×" + height
                + " 键 " + keys.size() + " 段 " + segs.size() + " 范围 " + ranges.size() + Arrays.toString(hole);
    }
}
