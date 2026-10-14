package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * 触摸分流：每根手指按下时落在哪个区域，就交给谁——触控板 → 手势引擎；键盘键 → 虚拟键盘；
 * 按钮 → 报告给界面；"左键"按钮 → 按住鼠标左键。另外管键盘修饰键和双指开合的 Ctrl + 滚轮。
 *
 * 修饰键（Ctrl / Shift / Alt / Win）点一下锁定、立即按下，打完下一个普通键自动解开，再点一下也能解开；
 * 锁定期间点触控板就是 Ctrl + 点击之类。"自带修饰键"的键（"@"、"复制"之类）按下时连同修饰键一起按。
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

        boolean contains(float x, float y) {
            return x >= left && x < right && y >= top && y < bottom;
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

    /** 整体替换；按顺序找第一个命中的，叠在上面的区域要排在前面。 */
    public void setRegions(Region[] regions) {
        this.regions = regions;
    }

    public Region hit(float x, float y) {
        for (Region r : regions) if (r.contains(x, y)) return r;
        return null;
    }

    // ═══════════ 触摸（屏幕像素） ═══════════

    /** 返回按到的区域（调试日志用），没按到任何区域返回 null。 */
    public Region down(int slot, float x, float y, long t) {
        if (slot < 0 || slot >= SLOTS) return null;
        Region r = hit(x, y);
        lastX[slot] = x;
        lastY[slot] = y;
        slotRegion[slot] = r;
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
            default:
                slotKind[slot] = KIND_BUTTON;
                out.button(r.id, Proto.BUTTON_DOWN);
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
            case KIND_BUTTON:
                out.button(r.id, r.contains(lastX[slot], lastY[slot]) ? Proto.BUTTON_TAP : Proto.BUTTON_UP);
                break;
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
        }
        holds = 0;
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
