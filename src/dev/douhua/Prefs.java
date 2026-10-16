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

    /** 左侧按钮条里可增减的按钮（最上面固定"退出""设置"、最下面固定"收起"，不在这里），最多这么多个。 */
    static final int MAX_SLOTS = 12;
    private static final String[] SLOT_DEFAULTS = {
            "rotate", "keyboard", "numpad", "back", "copy", "paste", "undo", "hold_left",
    };
    /** 0.2.0 以前：固定"退出""旋转""设置"，后面 7 个可配置位置。 */
    private static final String[] OLD_SLOT_DEFAULTS = {
            "keyboard", "numpad", "back", "copy", "paste", "undo", "hold_left",
    };

    static final int[] HAPTIC_LABELS = {R.string.haptic_off, R.string.haptic_low, R.string.haptic_medium, R.string.haptic_high};
    /** 精细移动各档（关 / 小 / 中 / 大）慢速时的倍率，见 GestureEngine.Config.slowFactor。 */
    static final float[] SLOW_FACTORS = {1f, 0.75f, 0.55f, 0.4f};
    static final int[] TICK_STRENGTH_LABELS = {R.string.haptic_low, R.string.haptic_medium, R.string.haptic_high};

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("douhua", Context.MODE_PRIVATE);
        migrateSlots();
    }

    /**
     * 以前按钮条是固定"旋转" + 7 个位置；现在"旋转"也成了可删的普通按钮、个数可增减。
     * 老设置原样保留、前面补上"旋转"，原来设成"无"的空位去掉。新装 / 恢复默认的直接用新默认。
     */
    private void migrateSlots() {
        if (sp.contains("slot_count")) return;
        boolean old = false;
        for (int i = 0; i < OLD_SLOT_DEFAULTS.length; i++) old |= sp.contains("slot" + i);
        if (!old) return;
        java.util.List<String> slots = new java.util.ArrayList<>();
        slots.add("rotate");
        for (int i = 0; i < OLD_SLOT_DEFAULTS.length; i++) {
            String id = sp.getString("slot" + i, OLD_SLOT_DEFAULTS[i]);
            if (!"none".equals(id)) slots.add(id);
        }
        SharedPreferences.Editor e = sp.edit();
        for (int i = 0; i < OLD_SLOT_DEFAULTS.length; i++) e.remove("slot" + i);
        for (int i = 0; i < slots.size(); i++) e.putString("slot" + i, slots.get(i));
        e.putInt("slot_count", slots.size()).apply();
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

    int slotCount() {
        return Math.max(0, Math.min(MAX_SLOTS, sp.getInt("slot_count", SLOT_DEFAULTS.length)));
    }

    String slot(int index) {
        return sp.getString("slot" + index, index < SLOT_DEFAULTS.length ? SLOT_DEFAULTS[index] : "none");
    }

    void setSlot(int index, String actionId) {
        sp.edit().putString("slot" + index, actionId).putInt("slot_count", slotCount()).apply();
    }

    /** 在最后加一个按钮（满了就不加）。 */
    void addSlot(String actionId) {
        int n = slotCount();
        if (n >= MAX_SLOTS) return;
        SharedPreferences.Editor e = sp.edit();
        for (int i = 0; i < n; i++) e.putString("slot" + i, slot(i));   // 把默认值也写实，免得后面错位
        e.putString("slot" + n, actionId).putInt("slot_count", n + 1).apply();
    }

    /** 删掉第 index 个，后面的往前挪。 */
    void removeSlot(int index) {
        int n = slotCount();
        if (index < 0 || index >= n) return;
        SharedPreferences.Editor e = sp.edit();
        for (int i = 0; i < n - 1; i++) e.putString("slot" + i, slot(i < index ? i : i + 1));
        e.remove("slot" + (n - 1)).putInt("slot_count", n - 1).apply();
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
        c.scrollTicks = scrollTicks() ? GestureEngine.TICKS_ALL : GestureEngine.TICKS_OFF;
        c.longPressDrag = getBool("long_press", c.longPressDrag);
        c.longPressMs = getInt("long_press_ms", c.longPressMs);
        c.tapToDrag = getBool("tap_drag", c.tapToDrag);
        c.dragWindowMs = getInt("drag_ms", c.dragWindowMs);
        c.texture = getBool("texture", c.texture);
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

    /**
     * 这台主机的屏幕宽高比（激光笔式里竖向每度走多少按它算，横竖才一样灵敏）；0 = 自动（苹果 4:3，其它 16:9）。
     * 蓝牙键鼠拿不到对方屏幕多大，只能让人选（描红测试那台电脑是 1126×776，按 16:9 猜上下就比左右灵敏 23%）。
     */
    float hostAspect(String address) {
        return address == null || address.isEmpty() ? 0f : getFloat("aspect_" + address, 0f);
    }

    void setHostAspect(String address, float aspect) {
        sp.edit().putFloat("aspect_" + address, aspect).putString("aspect_wh_" + address, "").apply();
    }

    /** 按分辨率填的屏幕比例："宽x高"；没填过是空的。 */
    String hostAspectWh(String address) {
        return address == null ? "" : sp.getString("aspect_wh_" + address, "");
    }

    void setHostAspectWh(String address, String wh) {
        sp.edit().putString("aspect_wh_" + address, wh).apply();
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

    /** 滚动"咔嗒"开没开。以前是三选一（"ticks"：关 / 只在边缘 / 所有），没改过的照旧值：不是"关"就是开。 */
    boolean scrollTicks() {
        return getBool("scroll_ticks", getInt("ticks", GestureEngine.TICKS_ALL) != GestureEngine.TICKS_OFF);
    }

    // ═══════════ 空中鼠标 ═══════════

    /**
     * 空中鼠标页：2 列 × airRows() 行可设定的大按钮（"无"就空着、位置留着），再加一个固定的圆钮
     * （点一下回正中、按住移动），放在第 airRoundAt()、airRoundAt()+1 行之间的交叉点上。
     * 回正中、按住移动归圆钮了，默认的格子里不再放它们。
     */
    static final int AIR_ROWS_MIN = 2, AIR_ROWS_MAX = 6;
    private static final String[] AIR_SLOT_DEFAULTS = {"hold_left", "right_click", "air_drag", "back", "copy", "paste"};

    String airSlot(int index) {
        return sp.getString("air_slot" + index, index < AIR_SLOT_DEFAULTS.length ? AIR_SLOT_DEFAULTS[index] : "none");
    }

    void setAirSlot(int index, String actionId) {
        sp.edit().putString("air_slot" + index, actionId).apply();
    }

    int airRows() {
        return Math.max(AIR_ROWS_MIN, Math.min(AIR_ROWS_MAX, getInt("air_rows", 3)));
    }

    /** 加一行（两个"无"，再点它们选动作）。 */
    void addAirRow() {
        int n = airRows();
        if (n >= AIR_ROWS_MAX) return;
        sp.edit().putString("air_slot" + (n * 2), "none").putString("air_slot" + (n * 2 + 1), "none")
                .putInt("air_rows", n + 1).apply();
    }

    /** 删掉第 row 行（0 起），后面的往上挪。 */
    void removeAirRow(int row) {
        int n = airRows();
        if (n <= AIR_ROWS_MIN || row < 0 || row >= n) return;
        SharedPreferences.Editor e = sp.edit();
        for (int i = row * 2; i < (n - 1) * 2; i++) e.putString("air_slot" + i, airSlot(i + 2));
        e.putInt("air_rows", n - 1).apply();
    }

    /** 圆钮在第 k 行和第 k+1 行之间（1 起）。 */
    int airRoundAt() {
        return Math.max(1, Math.min(airRows() - 1, getInt("air_round", 1)));
    }

    void setAirRoundAt(int k) {
        putInt("air_round", k);
    }

    /** 实体按键（HardKeys.VOL_UP / VOL_DOWN）"点一下" / "按住"做什么；默认"无" = 照常调音量。 */
    String hardKey(int key, boolean hold) {
        return sp.getString(hardKeyName(key, hold), "none");
    }

    void setHardKey(int key, boolean hold, String actionId) {
        sp.edit().putString(hardKeyName(key, hold), actionId).apply();
    }

    private static String hardKeyName(int key, boolean hold) {
        return "hk_" + (key == HardKeys.VOL_UP ? "up" : "down") + (hold ? "_hold" : "_tap");
    }

    /** 空中鼠标灵敏度档位 1–10（AirPointer.countsPerDegree）。 */
    int airSensitivity() {
        return getInt("air_sens", 6);
    }

    /** 空中鼠标消抖参数的键（设置页四个滑块，默认值见 AirPointer.Config）。 */
    static final String AIR_MIN_CUTOFF = "air_min_cutoff", AIR_BETA = "air_beta",
            AIR_SPEED_CUTOFF = "air_speed_cutoff", AIR_DEADZONE = "air_deadzone",
            AIR_PATH = "air_path_ms", AIR_STRING = "air_string", AIR_SPACE = "air_space";

    /** 把设置里的消抖参数填进 c（没设过的保持 c 里的默认值）。 */
    void airFilter(AirPointer.Config c) {
        c.minCutoffHz = getFloat(AIR_MIN_CUTOFF, c.minCutoffHz);
        c.beta = getFloat(AIR_BETA, c.beta);
        c.speedCutoffHz = getFloat(AIR_SPEED_CUTOFF, c.speedCutoffHz);
        c.tightenDps = getFloat(AIR_DEADZONE, c.tightenDps);
        c.pathSmoothMs = getFloat(AIR_PATH, c.pathSmoothMs);
        c.stringDeg = getFloat(AIR_STRING, c.stringDeg);
        c.space = getInt(AIR_SPACE, c.space);
        // 角度校正按换算分开存（各换算的歪法不一样），没设过用 AirPointer.defaultFix
        float[] fix = AirPointer.defaultFix(c.space);
        c.fixVDeg = getFloat(airFixKey(c.space, true), fix[0]);
        c.fixHDeg = getFloat(airFixKey(c.space, false), fix[1]);
    }

    /** 角度校正的键：每种换算各一套。 */
    static String airFixKey(int space, boolean vertical) {
        return "air_fix_" + (vertical ? "v_" : "h_") + space;
    }

    /** 消抖参数恢复默认。 */
    void resetAirFilter() {
        SharedPreferences.Editor e = sp.edit().remove(AIR_MIN_CUTOFF).remove(AIR_BETA).remove(AIR_SPEED_CUTOFF).remove(AIR_DEADZONE)
                .remove(AIR_PATH).remove(AIR_STRING).remove(AIR_SPACE);
        for (int s = 0; s < 3; s++) e.remove(airFixKey(s, true)).remove(airFixKey(s, false));
        e.apply();
    }

    void resetAll() {
        sp.edit().clear().apply();
    }
}
