package dev.douhua;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设置的读写和默认值。所有键名集中在这里，设置页和主界面都从这里读。
 */
final class Prefs {
    /** 三指 / 四指手势的设置键，顺序也是设置页里的显示顺序。 */
    static final String[] GESTURE_KEYS = {
            "g3_tap", "g3_up", "g3_down", "g3_left", "g3_right",
            "g4_tap", "g4_up", "g4_down", "g4_left", "g4_right",
    };
    static final int[] GESTURE_LABELS = {
            R.string.g3_tap, R.string.g3_up, R.string.g3_down, R.string.g3_left, R.string.g3_right,
            R.string.g4_tap, R.string.g4_up, R.string.g4_down, R.string.g4_left, R.string.g4_right,
    };
    private static final String[] GESTURE_DEFAULTS = {
            "back", "recents", "home", "switch_next", "switch_prev",
            "all_apps", "none", "none", "none", "none",
    };

    /** 左侧按钮条从上到下的可配置位置（最上面固定是"退出""旋转""设置"，不在这里）。 */
    static final int SLOT_COUNT = 7;
    private static final String[] SLOT_DEFAULTS = {
            "keyboard", "numpad", "back", "copy", "paste", "undo", "hold_left",
    };

    static final int[] HAPTIC_LABELS = {R.string.haptic_off, R.string.haptic_low, R.string.haptic_medium, R.string.haptic_high};
    /** 精细移动各档（关 / 小 / 中 / 大）慢速时的倍率，见 GestureEngine.Config.slowFactor。 */
    static final float[] SLOW_FACTORS = {1f, 0.75f, 0.55f, 0.4f};
    static final int[] TICK_STRENGTH_LABELS = {R.string.haptic_low, R.string.haptic_medium, R.string.haptic_high};

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("douhua", Context.MODE_PRIVATE);
    }

    // ═══════════ 手势和按钮 ═══════════

    String gesture(int index) {
        return sp.getString(GESTURE_KEYS[index], GESTURE_DEFAULTS[index]);
    }

    void setGesture(int index, String actionId) {
        sp.edit().putString(GESTURE_KEYS[index], actionId).apply();
    }

    /** fingers 是 3 或 4，kind 是 GestureEngine.GESTURE_*。 */
    String gestureFor(int fingers, int kind) {
        return gesture((fingers == 3 ? 0 : 5) + kind);
    }

    String slot(int index) {
        return sp.getString("slot" + index, SLOT_DEFAULTS[index]);
    }

    void setSlot(int index, String actionId) {
        sp.edit().putString("slot" + index, actionId).apply();
    }

    // ═══════════ 手感 ═══════════

    /** 震动强度：0 关、1 小、2 中、3 大。 */
    int haptic() {
        return sp.getInt("haptic", 2);
    }

    void setHaptic(int level) {
        sp.edit().putInt("haptic", level).apply();
    }

    /** 滚动"咔嗒"的强度：0 小、1 中、2 大，见 Haptics.setTickStrength。 */
    int tickStrength() {
        return sp.getInt("tick_strength", 1);
    }

    void setTickStrength(int strength) {
        sp.edit().putInt("tick_strength", strength).apply();
    }

    /** 拖动时的持续轻震：0 关、1 小、2 中、3 大，见 Haptics.setDragHum。 */
    int dragHum() {
        return sp.getInt("drag_hum", 1);
    }

    void setDragHum(int level) {
        sp.edit().putInt("drag_hum", level).apply();
    }

    float getFloat(String key, float def) {
        return sp.getFloat(key, def);
    }

    void putFloat(String key, float value) {
        sp.edit().putFloat(key, value).apply();
    }

    boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    void putBool(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    void putInt(String key, int value) {
        sp.edit().putInt(key, value).apply();
    }

    /** 按当前设置生成手势引擎参数；没设过的用 GestureEngine.Config 里的默认值。 */
    GestureEngine.Config engineConfig() {
        GestureEngine.Config c = new GestureEngine.Config();
        c.pointerSpeed = getFloat("speed", c.pointerSpeed);
        c.acceleration = getFloat("accel", c.acceleration);
        c.slowFactor = SLOW_FACTORS[Math.max(0, Math.min(SLOW_FACTORS.length - 1, getInt("precision", 2)))];
        c.scrollStepDp = getFloat("scroll_step", c.scrollStepDp);
        c.naturalScroll = getBool("natural", c.naturalScroll);
        c.pinchZoom = getBool("pinch", c.pinchZoom);
        c.edgeScroll = getBool("edge", c.edgeScroll);
        c.edgeScrollHorizontal = getBool("edge_h", c.edgeScrollHorizontal);
        c.edgeWidthDp = getInt("edge_width_i", Math.round(c.edgeWidthDp));
        c.scrollTicks = getInt("ticks", c.scrollTicks);
        c.longPressDrag = getBool("long_press", c.longPressDrag);
        c.longPressMs = getInt("long_press_ms", c.longPressMs);
        c.tapToDrag = getBool("tap_drag", c.tapToDrag);
        c.dragWindowMs = getInt("drag_ms", c.dragWindowMs);
        return c;
    }

    // ═══════════ 连接方式 ═══════════

    /** 连接方式：false = DeX（这台手机的外接屏，靠服务），true = 蓝牙（手机当蓝牙键盘鼠标，连电脑、平板、电视）。 */
    boolean btMode() {
        return sp.getBoolean("bt_mode", false);
    }

    void setBtMode(boolean on) {
        sp.edit().putBoolean("bt_mode", on).apply();
    }

    /** 蓝牙模式下上次连的那台（地址），打开时自动连它；空 = 还没连过。 */
    String btHost() {
        return sp.getString("bt_host", "");
    }

    void setBtHost(String address) {
        sp.edit().putString("bt_host", address).apply();
    }

    /** 旋转按钮选的屏幕方向（Surface.ROTATION_*）；-1 = 没按过，不动系统方向。 */
    int rotation() {
        return sp.getInt("rotation", -1);
    }

    void setRotation(int value) {
        sp.edit().putInt("rotation", value).apply();
    }

    /** 键盘最下面一排（快捷行）的位置数。 */
    static final int KEYBOARD_ROW_COUNT = 9;
    private static final String[] KEYBOARD_ROW_DEFAULTS = {
            "copy", "paste", "cut", "undo", "select_all", "left", "up", "down", "right",
    };

    /** 键盘最下面一排第 index 个键的动作 id（兼容上一版存的"键id:标签"格式）。 */
    String keyboardRow(int index) {
        return Combos.migrateLegacy(sp.getString("kb_sc_" + index, KEYBOARD_ROW_DEFAULTS[index]));
    }

    void setKeyboardRow(int index, String actionId) {
        sp.edit().putString("kb_sc_" + index, actionId).apply();
    }

    /** 按钮条收起后触控板角上可自定义的小按钮（左上、右上、右下；左下的 ☰ 固定）。 */
    static final int CORNER_COUNT = 3;
    static final int[] CORNER_LABELS = {R.string.corner_tl, R.string.corner_tr, R.string.corner_br};
    private static final String[] CORNER_DEFAULTS = {"exit", "rotate", "keyboard"};

    String corner(int index) {
        return sp.getString("corner" + index, CORNER_DEFAULTS[index]);
    }

    void setCorner(int index, String actionId) {
        sp.edit().putString("corner" + index, actionId).apply();
    }

    /** 键盘工具栏（收起 / 调整）放在下面。 */
    boolean keyboardToolbarBottom() {
        return getBool("kb_toolbar_bottom", false);
    }

    void setKeyboardToolbarBottom(boolean bottom) {
        putBool("kb_toolbar_bottom", bottom);
    }

    /** 左侧按钮条收起了（触控板四角放小按钮）。 */
    boolean stripHidden() {
        return getBool("strip_hidden", false);
    }

    void setStripHidden(boolean hidden) {
        putBool("strip_hidden", hidden);
    }

    // ═══════════ 键盘大小和位置（横竖屏各一份） ═══════════

    /** 键盘在可用区域里的位置 {左, 上, 右, 下}，0–1。默认竖屏占下面七成，横屏占满。 */
    float[] keyboardRect(boolean landscape) {
        String p = landscape ? "kb_land_" : "kb_port_";
        float[] def = landscape ? new float[]{0, 0, 1, 1} : new float[]{0, 0.3f, 1, 1};
        return new float[]{getFloat(p + "l", def[0]), getFloat(p + "t", def[1]),
                getFloat(p + "r", def[2]), getFloat(p + "b", def[3])};
    }

    void setKeyboardRect(boolean landscape, float[] r) {
        String p = landscape ? "kb_land_" : "kb_port_";
        sp.edit().putFloat(p + "l", r[0]).putFloat(p + "t", r[1]).putFloat(p + "r", r[2]).putFloat(p + "b", r[3]).apply();
    }

    void resetKeyboardRect(boolean landscape) {
        String p = landscape ? "kb_land_" : "kb_port_";
        sp.edit().remove(p + "l").remove(p + "t").remove(p + "r").remove(p + "b").apply();
    }

    void resetAll() {
        sp.edit().clear().apply();
    }
}
