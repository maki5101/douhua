package dev.douhua;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 触摸分流：每根手指按下时落在哪个区域，就交给谁——触控板 → 手势引擎；键盘键 → 虚拟键盘；
 * 按钮 → 报告给界面；"左键"按钮 → 按住鼠标左键。另外管键盘修饰键和双指开合的 Ctrl + 滚轮。
 *
 * 修饰键（左右八个）按下马上发；按住期间用过（按了别的键、鼠标键 / 滚轮 / 缩放 / 手势）= 组合键、松手就松；单独点一下 = 点了一下；
 * 单独按住 0.4–1 秒松手才锁，锁了以后打完下一个普通键自动解开、再点一下也能解开（2026-11-01 起，以前是点一下就锁）。
 * Caps 点一下发、按住只切显示；小红点状态的合并键点一下逗号、按住句号；实体键盘 75% / 65% / 苹果紧凑的 Fn（协议 21；协议 22 起点一下单次锁、
 * 按住 0.4 秒永久锁、锁着时单独按一下解开，Mac 模式 fn🌐 点一下照旧 🌐），带 Fn 层的键按下那一刻按 Fn 定码。按着要计时的这几种键、锁定、按着的计数、Fn 的状态都在 {@link KeyHolds}，
 * 到点用按键专用的定时器（{@link Output#scheduleKeys}，和引擎那个槽分开）。锁定期间点触控板就是 Ctrl + 点击之类。
 * "自带修饰键"的键（"@"、"复制"之类）按下时连同修饰键一起按。
 *
 * 键盘：按在键与键的缝、第二行两头的空位、外圈边距上（落在 Proto.REGION_KEYBOARD 里却没落在键上），
 * 分给范围里最近的键（{@link #nearestKey}）；按在键上的照旧按矩形判。
 *
 * 小红点（Proto.REGION_STICK，按得到的是报上来那个正方形的内切圆，{@link Region#contains}）：第一根按在红点上的手指交给 {@link TrackPoint}，按着期间请宿主按固定节拍调 {@link #onTick}
 * （{@link Output#startTicks}），抬起 / 取消 / {@link #releaseAll} / 换参数都停。再来的手指按在红点上不理。
 * 三个鼠标键（Proto.REGION_MOUSE_BUTTON）：左 / 右键按住就按住（左键和"左键"按钮、{@link #holdLeft} 共用计数）；
 * 中键按着时推红点 / 单指划触控板 = 滚动（GestureEngine.setScrollHold），最后一个松开时这次一格都没滚过 = 补一次中键单击，
 * 收尾（releaseAll）不补。按在中键上的第一根手指自己也是一根滚动杆（{@link #lever}，2026-11-01：单手用键盘 + 小红点时按住中键往上 / 下挪就滚，
 * 不用另一只手推红点）：另一个 TrackPoint（滚动杆模式），手指离按下点的位移当推力、同一条滚动曲线，和红点共用一套节拍
 * （两个任一按着就开着，都松了才停）；挪出轻点容差就算滚过了、松开不补单击。再来的手指按在中键上照旧是中键、不当滚动杆。
 * 滚动杆让位（{@link #leverYield}）：中键按着、滚动杆还没挪出轻点容差时，别的来源（红点、触控板、开合、滚轮动作）先出了滚轮，
 * 滚动杆这次就作废到抬起——按着中键当修饰键、另一根手指推红点 / 划触控板时，拇指漂一点不会另外滚（和以前一模一样）；
 * 滚动杆先挪出了轻点容差，之后再加红点 / 触控板就叠加。
 * 服务连着没接管时各 View 自己收触摸（MouseKeys 轻点 = 点一下），那条路没有滚动杆。
 *
 * 手柄页（Proto.REGION_GAMEPAD）：按下的手指交给 {@link GamepadTouch}（单键、面键簇、十字键、摇杆），报文经 {@link Output#gamepad} 出；
 * 归属照旧按下时定，唯一的例外是面键簇内部（归属定到簇，簇里按当前位置挑键，见 GamepadTouch）。releaseAll / 换参数 /
 * {@link #setGamepadConfig} 时手柄回中立（发全零）。
 *
 * 桌面模式下跑在服务里（触摸来自独占的触摸屏，输出到 /dev/uhid），蓝牙模式下跑在 App 里
 * （触摸来自 App 自己的窗口，输出到蓝牙），两边只是触摸来源和 {@link Output} 不同。
 * 纯 Java、不加锁：调用方保证同一时刻只有一个线程在调（包括定时器到点的 {@link #onTimer}）。
 */
public final class TouchRouter implements GestureEngine.Output {
    public interface Output {
        /** 虚拟鼠标一帧：buttons 是当前按下状态，其余是相对量；wheel / pan 的单位是 1/120 格（Hid.WHEEL_UNIT，换算成计数在 Hid.WheelOut）。 */
        void mouse(int buttons, int dx, int dy, int wheel, int pan);

        /** 虚拟键盘状态变了：8 字节键盘报文（只在回调期间有效）。 */
        void keyboard(byte[] report);

        void haptic(int kind);

        void gesture(int fingers, int kind);

        /** 按钮 / 键按下、松开（id、action 同 Proto.MSG_BUTTON），界面据此画按下状态、执行动作。 */
        void button(int id, int action);

        /**
         * 修饰键的状态变了（同 Proto.MSG_MODIFIERS，HID 修饰键位）：latched = 锁住的，held = 有手指按着的，armed = "现在松手就锁"的；
         * fn = Fn 的状态（Proto.FN_LATCHED 单次锁 / FN_HELD / FN_ARMED 快永久锁了 / FN_LOCKED 永久锁 位，协议 21 起、22 改了意思）。四样都比上次报的，变了才调。
         */
        void modifiers(int latched, int held, int armed, int fn);

        /** Caps 的显示该翻一下了（同 Proto.MSG_CAPS）：sent = 这次给主机发了 Caps Lock（点一下），假 = 只切显示（按住 0.4 秒）。显示状态归界面管。 */
        void caps(boolean sent);

        /** 过 delayMs 后调 {@link #onTimer}；再次调用时取代上一次。 */
        void schedule(long delayMs);

        void cancelSchedule();

        /**
         * 过 delayMs 后调 {@link #onKeyTimer}；再次调用时取代上一次。按键专用（修饰键 / Caps / 合并键按住到点，{@link KeyHolds}），
         * 和 schedule（引擎唯一的槽：轻点拖动、长按、惯性）是两条独立的路。不做成 default：忘了实现就编译不过（同 startTicks）。
         */
        void scheduleKeys(long delayMs);

        void cancelKeys();

        /**
         * 开始按固定节拍调 {@link #onTick}（桌面模式 8ms、蓝牙 12ms，由实现方定）；已经在跑就不动。小红点按着时用。
         * 和 schedule 是两条独立的路（引擎的轻点拖动 / 长按 / 惯性用 schedule）。不做成 default：忘了实现就静默失效，宁可编译报错。
         */
        void startTicks();

        void stopTicks();

        /**
         * 手柄状态变了：13 字节报文（Hid.GAMEPAD_DESC，只在回调期间有效）。全零 = 中立。和上一次一样不发，所以没用过手柄时收尾什么都不发。
         * 不做成 default：忘了实现就静默丢掉手柄的松开，宁可编译报错（同 startTicks）。
         */
        void gamepad(byte[] report);

        /** 记一行日志（小红点兜底暂停之类，给开发者看）。 */
        default void note(String s) {}

        /** 调试用，见 GestureEngine.Output#edgeRelease。 */
        default void edgeRelease(float speedDpPerMs, float spinPerSecond) {}
    }

    /**
     * 键盘触点记录（打字测试用，tools/type-log.sh 打开；App 和服务都写 logcat，标签 {@link #TRACE_TAG}）。
     * 只记落在键盘范围里的触摸，触控板、按钮条上的一律不记。每行一条，空格分隔，id 十六进制，坐标是屏幕像素：
     *   L l t r b 键数               键盘布局（打开时、键盘部分变了时）：范围的矩形，后面跟着这么多行 K；没有键盘时只有 "L 0"
     *   K type id l t r b            范围里的一个键（type 是 Proto.REGION_*）
     *   p t 槽 x y hit near l t r b  按下：hit = 只看矩形（按在缝里写 -），near = 最近的键，
     *                                l t r b = 实际用的键（都没有写 -）
     *   u t 槽 x y                   这根手指抬起（x y 是最后的位置）
     * t 是触摸事件的时间（毫秒，App 和服务的时钟不一样，分析时用 logcat 自己的时间）。
     * 格式只在这里生成，App 和服务写出来一模一样；分析见 tools/typelog/analyze.py。
     */
    public interface Trace {
        void line(String s);
    }

    public static final String TRACE_TAG = "douhua-keys";

    /**
     * 屏幕上的一块区域（像素），type 是 Proto.REGION_*。小红点（REGION_STICK）报的是正方形，按得到的是它的内切圆（{@link #contains}）：
     * 嵌在键里时正方形的四个角压着挨着的键，看着是键（键帽沿圆弧切口，2026-11-01），按上去也得是键。
     */
    public static final class Region {
        public final int type, id, left, top, right, bottom;

        public Region(int type, int id, int left, int top, int right, int bottom) {
            this.type = type;
            this.id = id;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean contains(float x, float y) {
            if (!(x >= left && x < right && y >= top && y < bottom)) return false;
            if (type != Proto.REGION_STICK) return true;
            // 小红点：正方形的内切圆（不是正方形时取短边的一半当半径）；KeyFingers.pick 的洞、TrackPointView 画的范围是同一个圆
            float r = Math.min(right - left, bottom - top) / 2f;
            float dx = x - (left + right) / 2f, dy = y - (top + bottom) / 2f;
            return dx * dx + dy * dy < r * r;
        }

        /** 同一个区域（App 每次重报布局都是新对象，按内容比）。 */
        boolean same(Region o) {
            return o != null && o.type == type && o.id == id && o.left == left && o.top == top
                    && o.right == right && o.bottom == bottom;
        }

        boolean inside(Region area) {
            return left >= area.left && right <= area.right && top >= area.top && bottom <= area.bottom;
        }

        @Override
        public String toString() {
            return "区域 type=" + type + " id=" + id;
        }
    }

    public static final int SLOTS = 16;
    private static final int KIND_NONE = 0;
    private static final int KIND_PAD = 1;
    private static final int KIND_BUTTON = 2;
    private static final int KIND_HOLD = 3;
    private static final int KIND_KEY = 4;
    private static final int KIND_STICK = 5;
    private static final int KIND_MOUSE = 6;
    private static final int KIND_GAMEPAD = 7;

    private final Output out;
    private final Hid.Keyboard keyboard = new Hid.Keyboard();
    private final byte[] keyboardReport = new byte[Hid.KEYBOARD_REPORT_SIZE];

    private GestureEngine engine;
    private float density = 1;
    private Region[] regions = new Region[0];
    private final int[] slotKind = new int[SLOTS];
    private final Region[] slotRegion = new Region[SLOTS];
    private final float[] lastX = new float[SLOTS];
    private final float[] lastY = new float[SLOTS];
    /** 这根手指按下时记了触点（抬起时要记一行 u）。 */
    private final boolean[] slotTraced = new boolean[SLOTS];
    private Trace trace;
    /** 上次记下的键盘布局（同样的布局重报时不重复记）。 */
    private String tracedLayout;
    /** 按着的左键：REGION_HOLD_LEFT、三键条 / 键盘最下面那排的左键、holdLeft（实体键）共用。 */
    private int leftHolds;
    /** 按着的右键、中键（三键条 / 键盘最下面那排）。 */
    private int rightHolds, middleHolds;
    /** 中键这次按着期间滚过（出过滚轮）：松开时不补中键单击。 */
    private boolean middleScrolled;
    /** 小红点：哪个 slot 按着它（-1 = 没有），宿主的节拍开着没有（红点、中键滚动杆共用一套节拍）。 */
    private final TrackPoint stick = new TrackPoint(new StickOutput());
    private int stickSlot = -1;
    /** 中键滚动杆：按在中键上的第一根手指（-1 = 没有），只出滚动（输出同红点，经引擎出）。 */
    private final TrackPoint lever = new TrackPoint(new StickOutput(), true);
    private int leverSlot = -1;
    /** 滚动杆这次让位了（别的来源先滚了，见 {@link #leverYield}）：作废到这根手指抬起，leverSlot 照旧占着（再来的中键手指还是不当滚动杆）。 */
    private boolean leverYielded;
    private boolean ticking;
    /** 手柄页的控件（REGION_GAMEPAD）。 */
    private final GamepadTouch gamepad = new GamepadTouch(new PadOutput());
    /** 按着要计时的键（修饰键、Caps、合并键）、锁定的修饰键、按着的键的计数。 */
    private final KeyHolds holds = new KeyHolds(new HoldsHost());
    /** 上次报给界面的修饰键状态、Fn 的状态（Output.modifiers，变了才报）。 */
    private int shownLatched, shownHeld, shownArmed, shownFn;
    /** 最近一次带时间的入口给的时间（引擎在 frame / onTimer 里回调 mouse / zoom 时用它；只会比真正的现在早，按它排的定时器只会晚到）。 */
    private long now = Long.MIN_VALUE;
    /** 开合缩放期间按住的 Ctrl。 */
    private boolean zoomCtrl;
    /** 正按着的"自带修饰键"的键，它们的修饰键要一起按着。 */
    private final List<Integer> chordKeys = new ArrayList<>();
    /** 引擎最近一次报给鼠标的按键状态（缩放时发滚轮要带上）。 */
    private int lastButtons;

    public TouchRouter(Output out) {
        this.out = out;
    }

    /**
     * 新的手势参数：换一个引擎，进行中的手势作废。density = 每 dp 多少像素。
     * 按着键的手指留着（键不依赖引擎，抬起照常松开、照常判点一下 / 锁定）：以前一起清掉、不松 HID，按着的键（现在包括按着的修饰键）
     * 会一直按在主机上，直到 releaseAll。
     */
    public void setConfig(GestureEngine.Config c, float density) {
        if (engine != null) engine.cancel();   // 转着的惯性也停
        engine = new GestureEngine(this, c);
        engine.setCtrlHeld(Hid.ctrl(modifierMask()));
        stick.setConfig(c);
        lever.setConfig(c);
        this.density = density > 0 ? density : 1;
        gamepad.setDensity(this.density);
        clearSlots(true);
    }

    /** 手柄参数（Proto.CMD_GAMEPAD_CONFIG）：手柄这一路先回中立（按着的全松、发全零），再换。不动手势引擎。 */
    public void setGamepadConfig(GamepadConfig c) {
        for (int s = 0; s < SLOTS; s++) {
            if (slotKind[s] != KIND_GAMEPAD) continue;
            slotKind[s] = KIND_NONE;
            slotRegion[s] = null;
        }
        gamepad.setConfig(c);
    }

    /** 参数到了才能工作。 */
    public boolean ready() {
        return engine != null;
    }

    /** 整体替换；按顺序找第一个命中的，叠在上面的区域要排在前面。键盘范围（REGION_KEYBOARD）排在它的键后面。 */
    public void setRegions(Region[] regions) {
        this.regions = regions;
        gamepad.setRegions(regions);
        if (trace != null) traceLayout(false);
    }

    /**
     * 按顺序找第一个包含这个点的区域；如果它是键盘范围，换成范围里最近的键（可能是 null）。
     * 所以按在键上的触点结果和只看矩形一样，排在键盘范围前面的区域照样优先。
     */
    public Region hit(float x, float y) {
        Region r = rectHit(x, y);
        if (r != null && r.type == Proto.REGION_KEYBOARD) return nearestKey(regions, r, x, y);
        return r;
    }

    /**
     * 这一点落在键、按钮、"左键"、键盘范围（缝里，分给最近的键）、小红点、三个鼠标键上——不是触控板、也不是空白处。只读，不改任何状态。
     * 服务分屏透传时用：豆花窗口里系统手势带上的点，落在这些上就归豆花，只有触控板和空白处交给系统认手势。
     */
    public boolean onTarget(float x, float y) {
        Region r = rectHit(x, y);
        return r != null && r.type != Proto.REGION_PAD;
    }

    /** 只看矩形：第一个包含这个点的区域（键盘范围也照样返回）。 */
    private Region rectHit(float x, float y) {
        for (Region r : regions) if (r.contains(x, y)) return r;
        return null;
    }

    /**
     * 键盘范围 area 里离 (x, y) 最近的键：候选是整个落在 area 里的键、按钮、"左键"（REGION_KEY / BUTTON / HOLD_LEFT）。
     * 距离按到键的矩形算（不是到中心），先比竖向、再比横向——先定行、再在行里找最近的键；一样近取列表里靠前的。
     * 效果等于软键盘常见的判定框铺法：每行纵向铺到相邻行的中线，行内横向铺到相邻键的中线，最边上的键一直延到键盘边，
     * 第二行两头的空位整块归 a / l（按纯欧氏距离的话，空位靠外的大半会分给上一行的 q / p）。
     * 没有候选返回 null。点不在 area 里也照样找最近的（hit 只在命中键盘范围时才调它）。
     */
    public static Region nearestKey(Region[] regions, Region area, float x, float y) {
        Region best = null;
        float bestDy = 0, bestDx = 0;
        for (Region r : regions) {
            if (!keyLike(r) || !r.inside(area)) continue;
            float dy = Math.max(0, Math.max(r.top - y, y - r.bottom));
            float dx = Math.max(0, Math.max(r.left - x, x - r.right));
            if (best == null || dy < bestDy || (dy == bestDy && dx < bestDx)) {
                best = r;
                bestDy = dy;
                bestDx = dx;
            }
        }
        return best;
    }

    /** 能当"最近的键"的区域：键、按钮、"左键"（键盘里的特殊键是按钮）。 */
    private static boolean keyLike(Region r) {
        return r.type == Proto.REGION_KEY || r.type == Proto.REGION_BUTTON || r.type == Proto.REGION_HOLD_LEFT;
    }

    // ═══════════ 触摸（屏幕像素） ═══════════

    /**
     * 返回按到的区域（调试日志用），没按到任何区域返回 null。
     * 惯性转着时碰屏幕任何地方都马上停：触控板上的由引擎自己"抓住"（这一下不算点击）；别处的这一下照常生效，
     * 停的时候震一下"刹住"，按在键上的不震（键自己会"嗒"）。
     */
    public Region down(int slot, float x, float y, long t) {
        if (slot < 0 || slot >= SLOTS) return null;
        tick(t);
        Region r = hit(x, y);
        // 按在键、手柄控件上的静默停（它们自己会"嗒"）
        if (engine != null && (r == null || r.type != Proto.REGION_PAD)) {
            engine.stopSpin(r == null || (r.type != Proto.REGION_KEY && r.type != Proto.REGION_GAMEPAD));
        }
        lastX[slot] = x;
        lastY[slot] = y;
        slotRegion[slot] = r;
        // 按在嵌在键里的小红点、键盘里的三个鼠标键上（都在键盘范围里面）不是按键，不记：触点记录只记键盘上的按键
        slotTraced[slot] = trace != null && engine != null
                && (r == null || (r.type != Proto.REGION_STICK && r.type != Proto.REGION_MOUSE_BUTTON)) && traceDown(slot, x, y, r, t);
        if (r == null || engine == null) {
            slotKind[slot] = KIND_NONE;
            return r;
        }
        switch (r.type) {
            case Proto.REGION_PAD: {
                slotKind[slot] = KIND_PAD;
                // 按在触控板边缘带里：左右两条单指上下滑 = 竖向滚动，上下两条单指左右滑 = 横向滚动
                float edge = engine.config().edgeWidthDp * density;
                int flags = 0;
                if (x - r.left < edge || r.right - x < edge) flags |= GestureEngine.EDGE_VERTICAL;
                if (y - r.top < edge || r.bottom - y < edge) flags |= GestureEngine.EDGE_HORIZONTAL;
                engine.down(slot, x / density, y / density, t, flags);
                break;
            }
            case Proto.REGION_HOLD_LEFT:
                slotKind[slot] = KIND_HOLD;
                if (leftHolds++ == 0) applyExternal();
                out.button(r.id, Proto.BUTTON_DOWN);
                break;
            case Proto.REGION_STICK:
                if (stickSlot >= 0) {   // 红点上已经有一根手指了：再来的不理
                    slotKind[slot] = KIND_NONE;
                    break;
                }
                slotKind[slot] = KIND_STICK;
                stickSlot = slot;
                stick.down(x / density, y / density, t);
                out.button(r.id, Proto.BUTTON_DOWN);
                startTicks();
                break;
            case Proto.REGION_MOUSE_BUTTON:
                slotKind[slot] = KIND_MOUSE;
                mouseButton(r.id & MOUSE_MASK, true, false);
                out.button(r.id, Proto.BUTTON_DOWN);
                if ((r.id & MOUSE_MASK) == Proto.BUTTON_MIDDLE && leverSlot < 0) {   // 中键上的第一根手指：滚动杆（再来的不当）
                    leverSlot = slot;
                    leverYielded = false;
                    lever.down(x / density, y / density, t);
                    startTicks();
                }
                break;
            case Proto.REGION_KEY:
                slotKind[slot] = KIND_KEY;
                holds.down(r.id, t);
                out.button(Proto.KEY_ID_BASE + r.id, Proto.BUTTON_DOWN);
                break;
            case Proto.REGION_BUTTON:
                slotKind[slot] = KIND_BUTTON;
                out.button(r.id, Proto.BUTTON_DOWN);
                break;
            case Proto.REGION_GAMEPAD:
                slotKind[slot] = KIND_GAMEPAD;
                gamepad.down(slot, r, x, y);
                break;
            default:   // 不认识的区域类型（以后的新类型、万一被直接命中的键盘范围）什么都不做，不再当成按钮 id 0
                slotKind[slot] = KIND_NONE;
                break;
        }
        return r;
    }

    public void move(int slot, float x, float y) {
        if (slot < 0 || slot >= SLOTS) return;
        lastX[slot] = x;
        lastY[slot] = y;
        if (slotKind[slot] == KIND_PAD && engine != null) engine.move(slot, x / density, y / density);
        else if (slotKind[slot] == KIND_STICK) stick.move(x / density, y / density);
        else if (slotKind[slot] == KIND_GAMEPAD) gamepad.move(slot, x, y);
        else if (slot == leverSlot) {   // 滑出中键照样算（归属按下时定）
            lever.move(x / density, y / density);
            if (lever.started()) middleScrolled = true;   // 挪出了轻点容差 = 要滚了：松开不补单击（按格模式还没攒满一格也一样）
        }
    }

    /** 一帧触摸报完（所有手指的位置都更新了）。 */
    public void frame(long t) {
        tick(t);
        if (engine != null) engine.endFrame(t);
        if (stickSlot >= 0 && stick.frame(t)) startTicks();   // 太久没动暂停了的红点又动了：接着发
        if (leverLive() && lever.frame(t)) startTicks();   // 中键滚动杆同样
        gamepad.frame();   // 摇杆轴一帧只发一次
    }

    public void up(int slot, long t) {
        up(slot, t, false);
    }

    /**
     * canceled：被取消的手指（手掌、丢数据、服务释放触摸屏时补报的抬起）照旧当抬起，只是这次手势松手不起惯性
     * （见 GestureEngine#up(int, long, boolean)）、红点轻点不算左键、中键不补单击。
     */
    public void up(int slot, long t, boolean canceled) {
        if (slot < 0 || slot >= SLOTS) return;
        tick(t);
        int kind = slotKind[slot];
        Region r = slotRegion[slot];
        slotKind[slot] = KIND_NONE;
        slotRegion[slot] = null;
        if (slotTraced[slot]) {
            slotTraced[slot] = false;
            if (trace != null) trace.line(String.format(Locale.ROOT, "u %d %d %.1f %.1f", t, slot, lastX[slot], lastY[slot]));
        }
        if (engine == null) return;
        switch (kind) {
            case KIND_PAD:
                engine.up(slot, t, canceled);
                break;
            case KIND_HOLD:
                if (--leftHolds <= 0) {
                    leftHolds = 0;
                    applyExternal();
                }
                out.button(r.id, Proto.BUTTON_UP);
                break;
            case KIND_STICK:
                stickSlot = -1;
                stopTicksIfIdle();
                stick.up(t, canceled);   // 轻点 = 左键（被取消的不算）
                out.button(r.id, Proto.BUTTON_UP);
                break;
            case KIND_MOUSE:
                if (slot == leverSlot) {   // 滚动杆先停，再按"滚过没有"松中键
                    leverSlot = -1;
                    leverYielded = false;
                    lever.up(t, canceled);   // 滚动杆不算轻点，什么都不出
                    stopTicksIfIdle();
                }
                mouseButton(r.id & MOUSE_MASK, false, canceled);
                out.button(r.id, Proto.BUTTON_UP);
                break;
            case KIND_BUTTON: {
                // 还在按钮上松开才算点击；被分过来的键盘特殊键（?123 / Fn / 🌐 / 快捷行动作），手指一直停在缝里也算
                float x = lastX[slot], y = lastY[slot];
                out.button(r.id, r.contains(x, y) || r.same(hit(x, y)) ? Proto.BUTTON_TAP : Proto.BUTTON_UP);
                break;
            }
            case KIND_KEY:
                holds.up(r.id, t, canceled);   // 被取消的：修饰键松开但不锁，Caps、合并键什么都不出
                out.button(Proto.KEY_ID_BASE + r.id, Proto.BUTTON_UP);
                break;
            case KIND_GAMEPAD:
                gamepad.up(slot);   // 被取消的也一样：全松
                break;
            default:
                break;
        }
    }

    public void onTimer(long t) {
        tick(t);
        if (engine != null) engine.onTimer(t);
    }

    /** 按键专用的定时器到点（{@link Output#scheduleKeys}）：按截止时刻 + 当前状态处理，过期的回调什么都不多做（{@link KeyHolds#onTimer}）。 */
    public void onKeyTimer(long t) {
        tick(t);
        holds.onTimer(t);
    }

    /** 清掉全部锁住的修饰键、锁住的 Fn（Proto.CMD_UNLATCH：进空中鼠标、手柄页时）；按着的不动。 */
    public void unlatch(long t) {
        tick(t);
        holds.unlatch(t);
    }

    /** 带时间的入口先记下时间（只往后走）。 */
    private void tick(long t) {
        if (t > now) now = t;
    }

    /**
     * 节拍到了（宿主在 {@link Output#startTicks} 之后按固定间隔调）：小红点、中键滚动杆按到 t 为止积分，出位移或滚轮。
     * 两个都按着时各出各的（滚轮叠加；红点先算，这一拍它先出了滚轮、滚动杆还没开始的话滚动杆让位，见 {@link #leverYield}）。
     * 两个都没按着（迟到的节拍、滚动杆让位了）什么都不出；按着的都太久没事件暂停了就停节拍。
     */
    public void onTick(long t) {
        tick(t);
        if (engine == null || (stickSlot < 0 && !leverLive())) {
            stopTicks();
            return;
        }
        boolean going = false;
        if (stickSlot >= 0) going |= stick.tick(t, middleHolds > 0, engine.heldButtons() != 0, engine.smoothNow());
        if (leverLive()) going |= lever.tick(t, true, false, engine.smoothNow());
        if (!going) {
            boolean was = ticking;   // 停了以后还在路上的节拍不再记
            stopTicks();
            if (was) out.note("小红点 / 中键滚动杆按着但 " + TrackPoint.IDLE_PAUSE_MS / 1000 + " 秒没收到这根手指的事件：先停节拍，再动就接着");
        }
    }

    /** 点一下鼠标键（动作"右键""中键"之类）。 */
    public void click(int button) {
        if (engine != null) engine.click(button);
    }

    /**
     * 按住 / 松开鼠标左键，和 {@link Proto#REGION_HOLD_LEFT} 共用 {@link #leftHolds} 计数（桌面模式下实体键按住配了"左键"时用）。
     * 第一个按下时按住左键，最后一个松开时清零；减到 0 以下截在 0。{@link #releaseAll} / {@link #clearSlots} 会把计数归零，
     * 这时若实体键还按着，之后的松开减到 0 以下也不会出错。
     */
    public void holdLeft(boolean down) {
        if (engine == null) return;
        if (down) {
            if (leftHolds++ == 0) applyExternal();
        } else if (--leftHolds <= 0) {
            leftHolds = 0;
            applyExternal();
        }
    }

    private static final int MOUSE_MASK = Proto.BUTTON_LEFT | Proto.BUTTON_RIGHT | Proto.BUTTON_MIDDLE;

    /**
     * 三键条 / 键盘最下面那排的鼠标键按下 / 松开（mask 是 Proto.BUTTON_*）。左 / 右：第一个按下时按住、最后一个松开时放开。
     * 中键：不直接按，按着期间是滚动（引擎的单指滚动、红点的滚动模式、中键上那根手指自己当的滚动杆）；最后一个松开时这次没滚过就补一次中键单击，
     * 被取消的（canceled：手掌、丢数据、服务收尾补报的抬起）不补——同 releaseAll，收尾不补中键单击（Linux 上中键单击 = 粘贴）。
     */
    private void mouseButton(int mask, boolean down, boolean canceled) {
        if (mask == Proto.BUTTON_LEFT) {
            if (down) {
                if (leftHolds++ == 0) applyExternal();
            } else if (--leftHolds <= 0) {
                leftHolds = 0;
                applyExternal();
            }
        } else if (mask == Proto.BUTTON_RIGHT) {
            if (down) {
                if (rightHolds++ == 0) applyExternal();
            } else if (--rightHolds <= 0) {
                rightHolds = 0;
                applyExternal();
            }
        } else if (mask == Proto.BUTTON_MIDDLE) {
            if (down) {
                holds.used(now);   // 中键按下不出鼠标帧（按着时是滚动），这里替它标"用过"：按着修饰键时按中键也是组合
                if (middleHolds++ == 0) {
                    middleScrolled = false;
                    engine.setScrollHold(true);
                }
            } else if (--middleHolds <= 0) {
                middleHolds = 0;
                engine.setScrollHold(false);
                if (!middleScrolled && !canceled) engine.click(Proto.BUTTON_MIDDLE);   // 按下时已经"嗒"过，补的单击不再震
            }
        }
    }

    /** 外部按住的鼠标键（左键计数、右键计数）告诉引擎。 */
    private void applyExternal() {
        engine.setExternalButtons((leftHolds > 0 ? Proto.BUTTON_LEFT : 0) | (rightHolds > 0 ? Proto.BUTTON_RIGHT : 0));
    }

    private void startTicks() {
        if (ticking) return;
        ticking = true;
        out.startTicks();
    }

    private void stopTicks() {
        if (!ticking) return;
        ticking = false;
        out.stopTicks();
    }

    /** 红点、中键滚动杆都没按着（滚动杆让位了也算没有）才停节拍（还有一个按着的话，它要是也暂停了，下一拍 onTick 停）。 */
    private void stopTicksIfIdle() {
        if (stickSlot < 0 && !leverLive()) stopTicks();
    }

    /** 中键滚动杆按着、没让位。 */
    private boolean leverLive() {
        return leverSlot >= 0 && !leverYielded;
    }

    /**
     * 中键按着时出了滚轮（mouse / zoom 里调，红点、触控板、开合、滚轮动作、滚动杆自己的都走这里）：滚动杆还没挪出轻点容差的话，
     * 这一下只能是别的来源出的（滚动杆没开始前一点不出），滚动杆就让位到这根手指抬起——
     * 拇指按着中键当修饰键、另一根手指推红点 / 划触控板时，拇指漂出 6dp 也不会按漂的方向另外滚（另一根手指松了以后也不会接着滚）。
     * 滚动杆已经开始了（先用滚动杆、后加红点 / 触控板）就不管，两个叠加。
     * 剩下的缺口：拇指在另一根手指开始滚之前就漂出了轻点容差，滚动杆已经开始了，照样叠加。
     */
    private void leverYield() {
        if (leverSlot < 0 || leverYielded || lever.started()) return;
        leverYielded = true;
        lever.cancel();
        stopTicksIfIdle();
    }

    /** 滚轮滚 n 格（正 = 向上）。转着的惯性先停。 */
    public void wheel(int n) {
        if (engine != null) engine.wheel(n);
    }

    /**
     * 惯性转着时停下（不在转就什么都不做）。feedback = 震一下"刹住"。服务看到按在 TouchRouter 以外的手指（分屏透传归系统的）、
     * App 收到不归本地分流的按下时调：碰屏幕任何地方都马上停。
     */
    public void stopSpin(boolean feedback) {
        if (engine != null) engine.stopSpin(feedback);
    }

    /**
     * 按下 / 松开一个键（key 同 Proto.REGION_KEY 的 id：HID 用法码 | 自带修饰键 << 8，合并键 Proto.dualKey，Fn 键、带 Fn 层的键 Proto.fnLayerKey），
     * action = Proto.KEY_DOWN / KEY_UP / KEY_CANCEL（被取消的：修饰键松开但不锁，Caps、合并键什么都不出）。
     * 界面自己收到的键（面板自己收触摸时）、动作里的组合键走这里，和按在区域上的键走同一份规则（KeyHolds），t = 按下 / 松开的时间。
     * 按下时转着的惯性静默停（打字说明不滚了，也免得 Ctrl / Super 在转的中途接上）。
     */
    public void key(int key, int action, long t) {
        tick(t);
        if (action == Proto.KEY_DOWN) {
            if (engine != null) engine.stopSpin(false);
            holds.down(key, t);
        } else {
            holds.up(key, t, action == Proto.KEY_CANCEL);
        }
    }

    /**
     * 放手：手势作废、按住的鼠标键和键盘键松开、按着的按钮报告松开、小红点和中键滚动杆停（节拍停）、中键不补单击、手柄回中立；
     * 修饰键和 Fn 的锁定 / 按着 / 快锁了全部归零，待定的 Caps / 合并键丢掉（不发），按键定时器撤掉。之后迟到的松开计数是 0、整个不理。
     * 触摸屏被释放、连接断开时调。
     */
    public void releaseAll() {
        if (engine != null) engine.cancel();
        for (int s = 0; s < SLOTS; s++) {
            if (slotRegion[s] == null) continue;
            if (slotKind[s] == KIND_BUTTON || slotKind[s] == KIND_HOLD || slotKind[s] == KIND_STICK || slotKind[s] == KIND_MOUSE) {
                out.button(slotRegion[s].id, Proto.BUTTON_UP);
            } else if (slotKind[s] == KIND_KEY) {
                out.button(Proto.KEY_ID_BASE + slotRegion[s].id, Proto.BUTTON_UP);
            }
        }
        clearSlots(false);
        holds.releaseAll();   // 连按键定时器一起撤掉
        zoomCtrl = false;
        chordKeys.clear();
        if (engine != null) engine.setCtrlHeld(false);
        if (keyboard.releaseAll()) sendKeyboard();
        showModifiers();   // 不是全零就报一次 (0, 0, 0, 0)
    }

    /** keepKeys = 按着键的手指留着（换参数时，见 {@link #setConfig}）；releaseAll 全清。 */
    private void clearSlots(boolean keepKeys) {
        for (int s = 0; s < SLOTS; s++) {
            if (keepKeys && slotKind[s] == KIND_KEY) continue;
            slotKind[s] = KIND_NONE;
            slotRegion[s] = null;
            slotTraced[s] = false;
        }
        leftHolds = 0;
        rightHolds = 0;
        middleHolds = 0;
        middleScrolled = false;
        stick.cancel();
        stickSlot = -1;
        lever.cancel();
        leverSlot = -1;
        leverYielded = false;
        stopTicks();
        gamepad.releaseAll();   // 按着的手柄键报松开、发全零（没用过就什么都不发）
    }

    // ═══════════ 键盘触点记录 ═══════════

    /** 打开 / 关掉触点记录（null = 关）。打开时先记一次键盘布局。 */
    public void setTrace(Trace t) {
        trace = t;
        tracedLayout = null;
        if (t == null) {
            for (int s = 0; s < SLOTS; s++) slotTraced[s] = false;
        } else {
            traceLayout(true);
        }
    }

    /** 键盘部分（范围 + 里面的键）变了才记；force = 不管变没变都记。 */
    private void traceLayout(boolean force) {
        StringBuilder b = new StringBuilder();
        int areas = 0;
        for (Region a : regions) {
            if (a.type != Proto.REGION_KEYBOARD) continue;
            areas++;
            List<Region> keys = new ArrayList<>();
            for (Region r : regions) {
                if (keyLike(r) && r.inside(a)) keys.add(r);
            }
            b.append("L ").append(a.left).append(' ').append(a.top).append(' ').append(a.right).append(' ').append(a.bottom)
                    .append(' ').append(keys.size()).append('\n');
            for (Region r : keys) {
                b.append("K ").append(r.type).append(' ').append(Integer.toHexString(r.id)).append(' ')
                        .append(r.left).append(' ').append(r.top).append(' ').append(r.right).append(' ').append(r.bottom).append('\n');
            }
        }
        if (areas == 0) b.append("L 0\n");
        String layout = b.toString();
        if (!force && layout.equals(tracedLayout)) return;
        tracedLayout = layout;
        for (String line : layout.split("\n")) trace.line(line);
    }

    /** 落在键盘范围里的按下记一行 p，返回记了没有。used = 实际用的键（hit 的结果）。 */
    private boolean traceDown(int slot, float x, float y, Region used, long t) {
        Region area = null;
        for (Region a : regions) {
            if (a.type == Proto.REGION_KEYBOARD && a.contains(x, y)) {
                area = a;
                break;
            }
        }
        if (area == null) return false;
        Region rect = rectHit(x, y);
        Region near = nearestKey(regions, area, x, y);
        String bounds = used == null ? "- - - -" : used.left + " " + used.top + " " + used.right + " " + used.bottom;
        trace.line(String.format(Locale.ROOT, "p %d %d %.1f %.1f %s %s %s", t, slot, x, y,
                rect == null || rect.type == Proto.REGION_KEYBOARD ? "-" : Integer.toHexString(rect.id),
                near == null ? "-" : Integer.toHexString(near.id), bounds));
        return true;
    }

    // ═══════════ 虚拟键盘 ═══════════

    /** 普通键按下（原语，KeyHolds 调）：自带修饰键的先按下修饰键、再按键。 */
    private void pressKey(int key) {
        int usage = key & 0xFF;
        int chord = (key >> 8) & 0xFF;
        if (chord != 0) {
            chordKeys.add(key);
            applyModifiers();   // 先按下修饰键，再按键
        }
        if (keyboard.press(usage)) sendKeyboard();
    }

    /** 普通键松开（原语，KeyHolds 调）：先松开键，再松开它自带的修饰键。"打完一个键锁定的修饰键自动解开"在 KeyHolds 里。 */
    private void releaseKey(int key) {
        int usage = key & 0xFF;
        if (keyboard.release(usage)) sendKeyboard();
        if (chordKeys.remove(Integer.valueOf(key))) applyModifiers();
    }

    /** 修饰键的状态告诉界面（latched / held / armed 三样和 Fn 的状态，比上次报的，变了才报）。 */
    private void showModifiers() {
        int l = holds.latched(), h = holds.held(), a = holds.armed(), f = holds.fn();
        if (l == shownLatched && h == shownHeld && a == shownArmed && f == shownFn) return;
        shownLatched = l;
        shownHeld = h;
        shownArmed = a;
        shownFn = f;
        out.modifiers(l, h, a, f);
    }

    /** 鼠标键按着（左 / 右 / 中，含外部按住的、中键按着还没发出去的）：这时按下的修饰键是组合的一部分，绝不锁。 */
    private boolean mouseBusy() {
        return lastButtons != 0 || middleHolds > 0 || leftHolds > 0 || rightHolds > 0;
    }

    private void applyModifiers() {
        int mask = modifierMask();
        if (engine != null) engine.setCtrlHeld(Hid.ctrl(mask));   // Ctrl（左右都算）按着：不起惯性、连续模式照旧按格（是缩放）
        if (keyboard.setModifiers(mask)) sendKeyboard();
    }

    /** 虚拟键盘现在该按着的修饰键：锁定的、有手指按着的、自带修饰键的键带的、开合缩放的 Ctrl。 */
    private int modifierMask() {
        int chords = 0;
        for (int k : chordKeys) chords |= (k >> 8) & 0xFF;
        return holds.latched() | holds.held() | chords | (zoomCtrl ? Hid.MOD_CTRL : 0);
    }

    private void sendKeyboard() {
        keyboard.report(keyboardReport);
        out.keyboard(keyboardReport);
    }

    // ═══════════ 手势引擎输出 ═══════════

    @Override
    public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
        // 按着的修饰键"用过了"：鼠标键按下 / 松开、带着按住的鼠标键的移动（拖动中）、滚轮、横向滚；没按鼠标键的纯移动不算
        // （另一只手指搭在触控板 / 红点上抖一下就锁不上）。在 lastButtons 赋值之前比
        if (buttons != lastButtons || buttons != 0 || wheel != 0 || pan != 0) holds.used(now);
        lastButtons = buttons;
        if (middleHolds > 0 && (wheel != 0 || pan != 0)) {   // 中键按着时滚过（红点、触控板、滚动杆都算）
            middleScrolled = true;
            leverYield();
        }
        out.mouse(buttons, dx, dy, wheel, pan);
    }

    /** 缩放 = 按住 Ctrl 滚滚轮（浏览器、安卓、电脑上通用的缩放方式）。连续滚动开着也按整格（应用的缩放本来就一级一级的）。 */
    @Override
    public void zoom(int steps) {
        if (middleHolds > 0) {
            middleScrolled = true;
            leverYield();
        }
        holds.used(now);
        if (!zoomCtrl) {
            holds.settle(now);   // 待定的键先发出去（不带缩放的 Ctrl：Ctrl+, 在不少应用里是"设置"）
            zoomCtrl = true;
            applyModifiers();
        }
        out.mouse(lastButtons, 0, 0, steps * Hid.WHEEL_UNIT, 0);
    }

    @Override
    public void zoomEnd() {
        if (!zoomCtrl) return;
        zoomCtrl = false;
        applyModifiers();
    }

    @Override
    public void gesture(int fingers, int kind) {
        holds.used(now);   // 三指 / 四指手势是明确的操作（蓝牙模式下手势映射成的点击不经分流，这里先兜住）
        out.gesture(fingers, kind);
    }

    @Override
    public void haptic(int kind) {
        out.haptic(kind);
    }

    @Override
    public void schedule(long delayMs) {
        out.schedule(delayMs);
    }

    @Override
    public void cancelSchedule() {
        out.cancelSchedule();
    }

    @Override
    public void edgeRelease(float speedDpPerMs, float spinPerSecond) {
        out.edgeRelease(speedDpPerMs, spinPerSecond);
    }

    /** 按键规则（KeyHolds）回调到这里：报文、修饰键字节、界面、震动、按键定时器。 */
    private final class HoldsHost implements KeyHolds.Host {
        @Override
        public void pressKey(int key) {
            TouchRouter.this.pressKey(key);
        }

        @Override
        public void releaseKey(int key) {
            TouchRouter.this.releaseKey(key);
        }

        @Override
        public void modsChanged() {
            applyModifiers();
            showModifiers();
        }

        @Override
        public boolean mouseBusy() {
            return TouchRouter.this.mouseBusy();
        }

        @Override
        public void holdReached() {
            out.haptic(GestureEngine.HAPTIC_CLICK);
        }

        @Override
        public void caps(boolean sent) {
            out.caps(sent);
        }

        /** fn🌐 单点：和 🌐 键一样报一次点击（App 照 🌐 键执行切输入法）。 */
        @Override
        public void globe() {
            out.button(Proto.GLOBE_BUTTON_ID, Proto.BUTTON_TAP);
        }

        @Override
        public void schedule(long delayMs) {
            out.scheduleKeys(delayMs);
        }

        @Override
        public void cancel() {
            out.cancelKeys();
        }
    }

    /** 手柄的输出：报文、按下 / 松开（App 震"嗒"）、"咔嗒"。 */
    private final class PadOutput implements GamepadTouch.Output {
        @Override
        public void report(byte[] report) {
            out.gamepad(report);
        }

        @Override
        public void button(int id, int action) {
            out.button(id, action);
        }

        @Override
        public void haptic(int kind) {
            out.haptic(kind);
        }
    }

    /** 小红点（和中键滚动杆）的输出：位移、滚轮经引擎出（按着的鼠标键照带），轻点 = 引擎点一下左键（滚动杆只出滚轮）。 */
    private final class StickOutput implements TrackPoint.Output {
        @Override
        public void move(int dx, int dy) {
            if (engine != null) engine.externalMove(dx, dy);
        }

        @Override
        public void scroll(int wheel, int pan) {
            if (engine != null) engine.externalScroll(wheel, pan);
        }

        @Override
        public void click() {
            if (engine != null) engine.click(Proto.BUTTON_LEFT);
        }

        @Override
        public void haptic(int kind) {
            out.haptic(kind);
        }
    }
}
