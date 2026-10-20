package dev.douhua;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 触摸分流：每根手指按下时落在哪个区域，就交给谁——触控板 → 手势引擎；键盘键 → 虚拟键盘；
 * 按钮 → 报告给界面；"左键"按钮 → 按住鼠标左键。另外管键盘修饰键和双指开合的 Ctrl + 滚轮。
 *
 * 修饰键（Ctrl / Shift / Alt / Win）点一下锁定、立即按下，打完下一个普通键自动解开，再点一下也能解开；
 * 锁定期间点触控板就是 Ctrl + 点击之类。"自带修饰键"的键（"@"、"复制"之类）按下时连同修饰键一起按。
 *
 * 键盘：按在键与键的缝、第二行两头的空位、外圈边距上（落在 Proto.REGION_KEYBOARD 里却没落在键上），
 * 分给范围里最近的键（{@link #nearestKey}）；按在键上的照旧按矩形判。
 *
 * DeX 模式下跑在服务里（触摸来自独占的触摸屏，输出到 /dev/uhid），蓝牙模式下跑在 App 里
 * （触摸来自 App 自己的窗口，输出到蓝牙），两边只是触摸来源和 {@link Output} 不同。
 * 纯 Java、不加锁：调用方保证同一时刻只有一个线程在调（包括定时器到点的 {@link #onTimer}）。
 */
public final class TouchRouter implements GestureEngine.Output {
    public interface Output {
        /** 虚拟鼠标一帧：buttons 是当前按下状态，其余是相对量。 */
        void mouse(int buttons, int dx, int dy, int wheel, int pan);

        /** 虚拟键盘状态变了：8 字节键盘报文（只在回调期间有效）。 */
        void keyboard(byte[] report);

        void haptic(int kind);

        void gesture(int fingers, int kind);

        /** 按钮 / 键按下、松开（id、action 同 Proto.MSG_BUTTON），界面据此画按下状态、执行动作。 */
        void button(int id, int action);

        /** 锁定的修饰键变了（同 Proto.MSG_MODIFIERS）。 */
        void modifiers(int latched);

        /** 过 delayMs 后调 {@link #onTimer}；再次调用时取代上一次。 */
        void schedule(long delayMs);

        void cancelSchedule();
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

    /** 屏幕上的一块区域（像素），type 是 Proto.REGION_*。 */
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
            return x >= left && x < right && y >= top && y < bottom;
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
    private int holds;
    /** 点一下锁定的修饰键。 */
    private int latched;
    /** 开合缩放期间按住的 Ctrl。 */
    private boolean zoomCtrl;
    /** 正按着的"自带修饰键"的键，它们的修饰键要一起按着。 */
    private final List<Integer> chordKeys = new ArrayList<>();
    /** 引擎最近一次报给鼠标的按键状态（缩放时发滚轮要带上）。 */
    private int lastButtons;

    public TouchRouter(Output out) {
        this.out = out;
    }

    /** 新的手势参数：换一个引擎，进行中的手势作废。density = 每 dp 多少像素。 */
    public void setConfig(GestureEngine.Config c, float density) {
        if (engine != null) engine.cancel();
        engine = new GestureEngine(this, c);
        this.density = density > 0 ? density : 1;
        clearSlots();
    }

    /** 参数到了才能工作。 */
    public boolean ready() {
        return engine != null;
    }

    /** 整体替换；按顺序找第一个命中的，叠在上面的区域要排在前面。键盘范围（REGION_KEYBOARD）排在它的键后面。 */
    public void setRegions(Region[] regions) {
        this.regions = regions;
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

    /** 返回按到的区域（调试日志用），没按到任何区域返回 null。 */
    public Region down(int slot, float x, float y, long t) {
        if (slot < 0 || slot >= SLOTS) return null;
        Region r = hit(x, y);
        lastX[slot] = x;
        lastY[slot] = y;
        slotRegion[slot] = r;
        slotTraced[slot] = trace != null && engine != null && traceDown(slot, x, y, r, t);
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
                if (holds++ == 0) engine.setExternalButtons(Proto.BUTTON_LEFT);
                out.button(r.id, Proto.BUTTON_DOWN);
                break;
            case Proto.REGION_KEY:
                slotKind[slot] = KIND_KEY;
                keyDown(r.id);
                out.button(Proto.KEY_ID_BASE + r.id, Proto.BUTTON_DOWN);
                break;
            case Proto.REGION_BUTTON:
                slotKind[slot] = KIND_BUTTON;
                out.button(r.id, Proto.BUTTON_DOWN);
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
    }

    /** 一帧触摸报完（所有手指的位置都更新了）。 */
    public void frame(long t) {
        if (engine != null) engine.endFrame(t);
    }

    public void up(int slot, long t) {
        if (slot < 0 || slot >= SLOTS) return;
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
                engine.up(slot, t);
                break;
            case KIND_HOLD:
                if (--holds <= 0) {
                    holds = 0;
                    engine.setExternalButtons(0);
                }
                out.button(r.id, Proto.BUTTON_UP);
                break;
            case KIND_BUTTON: {
                // 还在按钮上松开才算点击；被分过来的键盘特殊键（?123 / Fn / 🌐 / 快捷行动作），手指一直停在缝里也算
                float x = lastX[slot], y = lastY[slot];
                out.button(r.id, r.contains(x, y) || r.same(hit(x, y)) ? Proto.BUTTON_TAP : Proto.BUTTON_UP);
                break;
            }
            case KIND_KEY:
                keyUp(r.id);
                out.button(Proto.KEY_ID_BASE + r.id, Proto.BUTTON_UP);
                break;
            default:
                break;
        }
    }

    public void onTimer(long t) {
        if (engine != null) engine.onTimer(t);
    }

    /** 点一下鼠标键（动作"右键""中键"之类）。 */
    public void click(int button) {
        if (engine != null) engine.click(button);
    }

    /**
     * 按住 / 松开鼠标左键，和 {@link Proto#REGION_HOLD_LEFT} 共用 {@link #holds} 计数（DeX 模式下实体键按住配了"左键"时用）。
     * 第一个按下时按住左键，最后一个松开时清零；减到 0 以下截在 0。{@link #releaseAll} / {@link #clearSlots} 会把计数归零，
     * 这时若实体键还按着，之后的松开减到 0 以下也不会出错。
     */
    public void holdLeft(boolean down) {
        if (engine == null) return;
        if (down) {
            if (holds++ == 0) engine.setExternalButtons(Proto.BUTTON_LEFT);
        } else if (--holds <= 0) {
            holds = 0;
            engine.setExternalButtons(0);
        }
    }

    /** 滚轮滚 n 格（正 = 向上）。 */
    public void wheel(int n) {
        if (engine != null) engine.wheel(n);
    }

    /** 按下 / 松开一个键（key = HID 用法码 | 自带修饰键 << 8，同 Proto.REGION_KEY）。界面自己收到的键、动作里的组合键走这里。 */
    public void key(int key, boolean down) {
        if (down) keyDown(key);
        else keyUp(key);
    }

    /** 放手：手势作废、按住的鼠标键和键盘键松开、按着的按钮报告松开。触摸屏被释放、连接断开时调。 */
    public void releaseAll() {
        if (engine != null) engine.cancel();
        for (int s = 0; s < SLOTS; s++) {
            if (slotRegion[s] == null) continue;
            if (slotKind[s] == KIND_BUTTON || slotKind[s] == KIND_HOLD) {
                out.button(slotRegion[s].id, Proto.BUTTON_UP);
            } else if (slotKind[s] == KIND_KEY) {
                out.button(Proto.KEY_ID_BASE + slotRegion[s].id, Proto.BUTTON_UP);
            }
        }
        clearSlots();
        boolean hadLatched = latched != 0;
        latched = 0;
        zoomCtrl = false;
        chordKeys.clear();
        if (keyboard.releaseAll()) sendKeyboard();
        if (hadLatched) out.modifiers(0);
    }

    private void clearSlots() {
        for (int s = 0; s < SLOTS; s++) {
            slotKind[s] = KIND_NONE;
            slotRegion[s] = null;
            slotTraced[s] = false;
        }
        holds = 0;
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

    private void keyDown(int key) {
        int usage = key & 0xFF;
        int chord = (key >> 8) & 0xFF;
        if (chord == 0 && Hid.isModifier(usage)) {
            latched ^= Hid.modifierBit(usage);
            applyModifiers();
            out.modifiers(latched);
            return;
        }
        if (chord != 0) {
            chordKeys.add(key);
            applyModifiers();   // 先按下修饰键，再按键
        }
        if (keyboard.press(usage)) sendKeyboard();
    }

    private void keyUp(int key) {
        int usage = key & 0xFF;
        int chord = (key >> 8) & 0xFF;
        if (chord == 0 && Hid.isModifier(usage)) return;   // 修饰键是点一下锁定，松手不算
        if (keyboard.release(usage)) sendKeyboard();   // 先松开键，再松开它自带的修饰键
        chordKeys.remove(Integer.valueOf(key));
        boolean hadLatched = latched != 0;
        latched = 0;               // 打完一个键，锁定的修饰键自动解开
        applyModifiers();
        if (hadLatched) out.modifiers(0);
    }

    private void applyModifiers() {
        int chords = 0;
        for (int k : chordKeys) chords |= (k >> 8) & 0xFF;
        if (keyboard.setModifiers(latched | chords | (zoomCtrl ? Hid.MOD_CTRL : 0))) sendKeyboard();
    }

    private void sendKeyboard() {
        keyboard.report(keyboardReport);
        out.keyboard(keyboardReport);
    }

    // ═══════════ 手势引擎输出 ═══════════

    @Override
    public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
        lastButtons = buttons;
        out.mouse(buttons, dx, dy, wheel, pan);
    }

    /** 缩放 = 按住 Ctrl 滚滚轮（浏览器、DeX、电脑上通用的缩放方式）。 */
    @Override
    public void zoom(int steps) {
        if (!zoomCtrl) {
            zoomCtrl = true;
            applyModifiers();
        }
        out.mouse(lastButtons, 0, 0, steps, 0);
    }

    @Override
    public void zoomEnd() {
        if (!zoomCtrl) return;
        zoomCtrl = false;
        applyModifiers();
    }

    @Override
    public void gesture(int fingers, int kind) {
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
}
