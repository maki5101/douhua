package dev.douhua;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * F1–F12 的第二功能在键帽上画什么（协议 24，2026-11-02，方案 design-fkeys §9）：矢量图标或者短字，纯 Java（离线测，FixedFKeysTest），
 * 实体键盘的键帽（FixedPanel.glyphsAt → FixedKeyboardView 画）和以后设置页的列表都按它。
 * - 多媒体页 / 系统键那一组的功能（静音、音量、媒体、亮度、调度中心、启动台、锁屏、搜索、计算器、邮件、主页、我的电脑、设置、切输入法、听写、表情、睡眠、勿扰、
 *   麦克风静音）和 PrtSc：自己画的矢量图标（{@link #icon}，颜色跟主题，不用彩色 emoji）。
 * - 组合键：写组合本身（方案原话"组合键写组合本身"）。GUI 键 Windows 模式写 Super、Mac 模式写 ⌘（同项目规矩）；
 *   Windows 模式照微软快捷键页的写法 "Super+Shift+S"（顺序 Super、Ctrl、Alt、Shift），Mac 模式照苹果的写法 "⇧⌘4"（顺序 ⌃ ⌥ ⇧ ⌘，不加 +）。
 * - F13–F24 写 "F13"……；别的单个键写键名（同实体键盘的键名：Esc、⌫、PgUp、←……）。
 * - "无" 不画第二功能（键帽只有 F1）。每个能选的功能都有图标或字（FixedFKeysTest 锁住），守"不出没字的键"。
 *
 * 图标照 24 × 24 的方格写（y 朝下，同 Android），由几种笔画拼成（{@link #icon} 的每一项 = {笔画, 参数…}）：描边的笔粗 {@link #STROKE}、圆头圆角，
 * 填充的不描边。FixedKeyboardView 按键帽大小缩放画。只用 Path 现成有的几样（线、折线、多边形、圆角矩形、圆、椭圆、圆弧、圆减圆），不用解析 SVG。
 */
final class FKeyFaces {
    private FKeyFaces() {}

    /** 一个第二功能在键帽上画什么：icon ≠ 0 画图标（text 是它的功能 id，只当说明），不然写 text。 */
    static final class Face {
        final int icon;
        final String text;

        Face(int icon, String text) {
            this.icon = icon;
            this.text = text;
        }

        @Override
        public String toString() {
            return icon != 0 ? "图标" + icon + "(" + text + ")" : text;
        }
    }

    // ═══════════ 笔画 ═══════════

    /** 图标方格的边长、描边的笔粗（都是方格单位）。 */
    static final float BOX = 24, STROKE = 2;

    /** 线 {x1, y1, x2, y2}（描边）。 */
    static final int LINE = 1;
    /** 折线 {x0, y0, x1, y1, …}（描边，不封口）。 */
    static final int POLYLINE = 2;
    /** 多边形 {x0, y0, x1, y1, …}（填充）。 */
    static final int POLYGON = 3;
    /** 圆角矩形 {左, 上, 右, 下, 圆角半径, 填充(1) / 描边(0)}。 */
    static final int RECT = 4;
    /** 圆 {cx, cy, r, 填充(1) / 描边(0)}。 */
    static final int CIRCLE = 5;
    /** 椭圆 {左, 上, 右, 下}（描边）。 */
    static final int OVAL = 6;
    /** 圆弧 {cx, cy, r, 起始角, 扫过的角}（描边；度，0 = 正右、顺时针为正，同 Android 的 arcTo）。 */
    static final int ARC = 7;
    /** 月牙 {cx, cy, r, cx2, cy2, r2}（填充：第一个圆减去第二个圆）。 */
    static final int CRESCENT = 8;

    // ═══════════ 图标编号 ═══════════

    static final int MUTE = 1, VOL_DOWN = 2, VOL_UP = 3, MIC_MUTE = 4, PLAY_PAUSE = 5, PREV_TRACK = 6, NEXT_TRACK = 7, STOP = 8,
            REWIND = 9, FAST_FORWARD = 10, BRIGHT_DOWN = 11, BRIGHT_UP = 12, SHOW_WINDOWS = 13, SHOW_APPS = 14, LOCK = 15, SEARCH = 16,
            CALCULATOR = 17, MAIL = 18, HOME = 19, COMPUTER = 20, SETTINGS = 21, GLOBE = 22, DICTATION = 23, EMOJI = 24, SLEEP = 25,
            DND = 26, SCREENSHOT = 27;
    /** 图标一共几个（编号 1 … ICON_COUNT）。 */
    static final int ICON_COUNT = 27;

    /** 功能 id → 图标编号（多媒体 / 系统键那一组的都在这里，外加 PrtSc）。 */
    private static final Map<String, Integer> ICON_OF = new HashMap<>();

    static {
        String[] ids = {"mute", "vol_down", "vol_up", "mic_mute", "play_pause", "prev_track", "next_track", "stop", "rewind", "fast_forward",
                "bright_down", "bright_up", "show_windows", "show_apps", "lock", "search", "calculator", "mail", "browser_home", "my_computer",
                "settings", "next_layout", "dictation", "emoji", "sleep", "dnd", "prtsc"};
        int[] icons = {MUTE, VOL_DOWN, VOL_UP, MIC_MUTE, PLAY_PAUSE, PREV_TRACK, NEXT_TRACK, STOP, REWIND, FAST_FORWARD,
                BRIGHT_DOWN, BRIGHT_UP, SHOW_WINDOWS, SHOW_APPS, LOCK, SEARCH, CALCULATOR, MAIL, HOME, COMPUTER,
                SETTINGS, GLOBE, DICTATION, EMOJI, SLEEP, DND, SCREENSHOT};
        for (int i = 0; i < ids.length; i++) ICON_OF.put(ids[i], icons[i]);
    }

    /**
     * 功能 f 在这种模式（FixedLayout.WIN / MAC）下键帽上画什么；null / "无" 返回 null（键帽只有 F1）。
     * 有图标的画图标；组合键写组合本身；F13–F24 和别的单个键写键名。
     */
    static Face face(FKeyFunctions.Function f, String mode) {
        if (f == null || f.kind == FKeyFunctions.NONE) return null;
        Integer icon = ICON_OF.get(f.id);
        if (icon != null) return new Face(icon, f.id);
        int code = f.code(mode);
        if (f.kind != FKeyFunctions.KEY) return new Face(0, f.id);   // 照理不会：多媒体 / 系统键的都有图标（FixedFKeysTest 锁住）
        return new Face(0, mods(code) == 0 ? keyName(code & 0xFF) : comboLabel(code, mode));
    }

    /** 这个功能有没有图标。 */
    static boolean hasIcon(String functionId) {
        return ICON_OF.containsKey(functionId);
    }

    /** 自带修饰键的 4 位（Ctrl 1、Shift 2、Alt 4、GUI 8；右边四个折到左边，同 HID 修饰键字节的低 4 位）。 */
    private static int mods(int code) {
        int m = (code >> 8) & 0xFF;
        return (m | m >> 4) & 0x0F;
    }

    /**
     * 组合键的写法（code = 用法码 | 自带修饰键 << 8）：Windows 模式 "Super+Shift+S"（顺序 Super、Ctrl、Alt、Shift，照微软快捷键页），
     * Mac 模式 "⇧⌘4"（顺序 ⌃ ⌥ ⇧ ⌘、不加 +，照苹果的写法；GUI 写 ⌘、Alt 写 ⌥）。没有修饰键时就是键名。
     */
    static String comboLabel(int code, String mode) {
        int m = mods(code);
        String key = keyName(code & 0xFF);
        if (FixedLayout.MAC.equals(mode)) {
            StringBuilder sb = new StringBuilder();
            if ((m & 1) != 0) sb.append('⌃');
            if ((m & 4) != 0) sb.append('⌥');
            if ((m & 2) != 0) sb.append('⇧');
            if ((m & 8) != 0) sb.append('⌘');
            return sb.append(key).toString();
        }
        StringBuilder sb = new StringBuilder();
        if ((m & 8) != 0) sb.append("Super+");
        if ((m & 1) != 0) sb.append("Ctrl+");
        if ((m & 4) != 0) sb.append("Alt+");
        if ((m & 2) != 0) sb.append("Shift+");
        return sb.append(key).toString();
    }

    /** 单个键的名字（键盘页的用法码），写法同实体键盘的键帽（FixedLayout 的键名、方向键用箭头）；认不出的写 "0xNN"。 */
    static String keyName(int usage) {
        if (usage >= 0x04 && usage <= 0x1D) return String.valueOf((char) ('A' + usage - 0x04));
        if (usage >= 0x1E && usage <= 0x27) return String.valueOf((usage - 0x1E + 1) % 10);
        if (usage >= 0x3A && usage <= 0x45) return "F" + (usage - 0x3A + 1);
        if (usage >= 0x68 && usage <= 0x73) return "F" + (usage - 0x68 + 13);
        switch (usage) {
            case 0x28: return "↵";
            case 0x29: return "Esc";
            case 0x2A: return "⌫";
            case 0x2B: return "Tab";
            case 0x2C: return "Space";
            case 0x2D: return "-";
            case 0x2E: return "=";
            case 0x2F: return "[";
            case 0x30: return "]";
            case 0x31: return "\\";
            case 0x33: return ";";
            case 0x34: return "'";
            case 0x35: return "`";
            case 0x36: return ",";
            case 0x37: return ".";
            case 0x38: return "/";
            case 0x39: return "Caps";
            case 0x46: return "PrtSc";
            case 0x47: return "ScrLk";
            case 0x48: return "Pause";
            case 0x49: return "Ins";
            case 0x4A: return "Home";
            case 0x4B: return "PgUp";
            case 0x4C: return "Del";
            case 0x4D: return "End";
            case 0x4E: return "PgDn";
            case 0x4F: return "→";
            case 0x50: return "←";
            case 0x51: return "↓";
            case 0x52: return "↑";
            case 0x65: return "☰";
            default: return String.format(Locale.ROOT, "0x%02X", usage);
        }
    }

    // ═══════════ 图标的笔画 ═══════════

    /** 喇叭（静音、音量三个共用）。 */
    private static final float[] SPEAKER = {POLYGON, 3, 9, 7, 9, 12, 4.5f, 12, 19.5f, 7, 15, 3, 15};

    /** 话筒（听写、麦克风静音共用）：话筒头、托、柄、底座。 */
    private static final float[][] MIC = {
            {RECT, 9, 2.5f, 15, 14.5f, 3, 1},
            {ARC, 12, 11, 6.5f, 0, 180},
            {LINE, 12, 17.5f, 12, 21.5f},
            {LINE, 8.5f, 21.5f, 15.5f, 21.5f},
    };

    /** 太阳的光芒：8 条，从半径 r0 到 r1。 */
    private static float[][] rays(float r0, float r1) {
        float[][] out = new float[8][];
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            out[i] = new float[]{LINE, 12 + r0 * c, 12 + r0 * s, 12 + r1 * c, 12 + r1 * s};
        }
        return out;
    }

    private static float[][] join(float[][]... parts) {
        int n = 0;
        for (float[][] p : parts) n += p.length;
        float[][] out = new float[n][];
        int i = 0;
        for (float[][] p : parts) for (float[] q : p) out[i++] = q;
        return out;
    }

    private static float[][] of(float[]... ops) {
        return ops;
    }

    /**
     * 第 id 个图标的笔画（每一项 {笔画, 参数…}，见 {@link #LINE} 那几个），坐标在 0–{@link #BOX} 的方格里；认不出的编号返回空数组。
     * 样子照常见的键帽图标（苹果 / ThinkPad / Material 的意思，自己画的，没有照抄哪一家的图）：
     * 音量是喇叭 + 声波（减一道、加两道，同苹果），静音是喇叭 + ×；亮度小太阳 / 大太阳；调度中心三个窗口；启动台九宫格；勿扰是月牙（同苹果 F6），
     * 睡眠是月牙 + Z；截屏是取景框的四个角 + 中间一点。
     */
    static float[][] icon(int id) {
        switch (id) {
            case MUTE:
                return of(SPEAKER, new float[]{LINE, 15, 9.5f, 20.5f, 15}, new float[]{LINE, 20.5f, 9.5f, 15, 15});
            case VOL_DOWN:
                return of(SPEAKER, new float[]{ARC, 12, 12, 4.5f, -45, 90});
            case VOL_UP:
                return of(SPEAKER, new float[]{ARC, 12, 12, 4.5f, -45, 90}, new float[]{ARC, 12, 12, 8.5f, -45, 90});
            case MIC_MUTE:
                return join(MIC, of(new float[]{LINE, 4, 3, 20, 21}));
            case DICTATION:
                return MIC.clone();
            case PLAY_PAUSE:
                return of(new float[]{POLYGON, 2.5f, 5.5f, 2.5f, 18.5f, 11.5f, 12},
                        new float[]{RECT, 14, 5.5f, 16.5f, 18.5f, 0.5f, 1}, new float[]{RECT, 19, 5.5f, 21.5f, 18.5f, 0.5f, 1});
            case PREV_TRACK:
                return of(new float[]{RECT, 3, 6, 5.5f, 18, 0.5f, 1},
                        new float[]{POLYGON, 13, 6, 13, 18, 5.5f, 12}, new float[]{POLYGON, 21, 6, 21, 18, 13, 12});
            case NEXT_TRACK:
                return of(new float[]{POLYGON, 3, 6, 3, 18, 10.5f, 12}, new float[]{POLYGON, 11, 6, 11, 18, 18.5f, 12},
                        new float[]{RECT, 18.5f, 6, 21, 18, 0.5f, 1});
            case STOP:
                return of(new float[]{RECT, 6, 6, 18, 18, 1.5f, 1});
            case REWIND:
                return of(new float[]{POLYGON, 12, 6, 12, 18, 3, 12}, new float[]{POLYGON, 21, 6, 21, 18, 12, 12});
            case FAST_FORWARD:
                return of(new float[]{POLYGON, 3, 6, 3, 18, 12, 12}, new float[]{POLYGON, 12, 6, 12, 18, 21, 12});
            case BRIGHT_DOWN:
                return join(of(new float[]{CIRCLE, 12, 12, 3.5f, 0}), rays(6.5f, 8));
            case BRIGHT_UP:
                return join(of(new float[]{CIRCLE, 12, 12, 4, 1}), rays(6.5f, 10.5f));
            case SHOW_WINDOWS:
                return of(new float[]{RECT, 1.5f, 8, 7, 16, 1, 0}, new float[]{RECT, 9, 5.5f, 15, 18.5f, 1, 0}, new float[]{RECT, 17, 8, 22.5f, 16, 1, 0});
            case SHOW_APPS: {
                float[][] out = new float[9][];
                float[] at = {4, 10.25f, 16.5f};
                for (int i = 0; i < 9; i++) out[i] = new float[]{RECT, at[i % 3], at[i / 3], at[i % 3] + 3.5f, at[i / 3] + 3.5f, 0.8f, 1};
                return out;
            }
            case LOCK:
                return of(new float[]{RECT, 5, 10.5f, 19, 21, 2, 1}, new float[]{ARC, 12, 9, 4.5f, 180, 180},
                        new float[]{LINE, 7.5f, 9, 7.5f, 10.5f}, new float[]{LINE, 16.5f, 9, 16.5f, 10.5f});
            case SEARCH:
                return of(new float[]{CIRCLE, 10, 10, 6, 0}, new float[]{LINE, 14.5f, 14.5f, 20.5f, 20.5f});
            case CALCULATOR: {
                float[][] out = new float[8][];
                out[0] = new float[]{RECT, 5, 2, 19, 22, 2, 0};
                out[1] = new float[]{RECT, 8, 5, 16, 9, 0.5f, 1};
                for (int i = 0; i < 6; i++) out[2 + i] = new float[]{CIRCLE, 9 + 3 * (i % 3), i < 3 ? 13 : 17.5f, 1.2f, 1};
                return out;
            }
            case MAIL:
                return of(new float[]{RECT, 2.5f, 5.5f, 21.5f, 18.5f, 1.5f, 0}, new float[]{POLYLINE, 2.5f, 6, 12, 13, 21.5f, 6});
            case HOME:
                return of(new float[]{POLYLINE, 2.5f, 12, 12, 3.5f, 21.5f, 12}, new float[]{POLYLINE, 5.5f, 10, 5.5f, 20.5f, 18.5f, 20.5f, 18.5f, 10},
                        new float[]{RECT, 10, 14.5f, 14, 20.5f, 0.5f, 1});
            case COMPUTER:
                return of(new float[]{RECT, 2.5f, 3.5f, 21.5f, 16, 1.5f, 0}, new float[]{LINE, 12, 16, 12, 20}, new float[]{LINE, 7.5f, 20.5f, 16.5f, 20.5f});
            case SETTINGS:
                return join(of(new float[]{CIRCLE, 12, 12, 3, 0}, new float[]{CIRCLE, 12, 12, 7, 0}), rays(7, 9.8f));
            case GLOBE:
                return of(new float[]{CIRCLE, 12, 12, 9.5f, 0}, new float[]{OVAL, 8, 2.5f, 16, 21.5f}, new float[]{LINE, 2.5f, 12, 21.5f, 12});
            case EMOJI:
                return of(new float[]{CIRCLE, 12, 12, 9.5f, 0}, new float[]{CIRCLE, 8.8f, 9.8f, 1.3f, 1}, new float[]{CIRCLE, 15.2f, 9.8f, 1.3f, 1},
                        new float[]{ARC, 12, 12, 5.5f, 25, 130});
            case SLEEP:
                return of(new float[]{CRESCENT, 10.5f, 13.5f, 8, 15, 9, 7}, new float[]{POLYLINE, 15.5f, 2.5f, 21, 2.5f, 15.5f, 8, 21, 8});
            case DND:
                return of(new float[]{CRESCENT, 11, 12.5f, 9, 16.5f, 7.5f, 8});
            case SCREENSHOT:
                return of(new float[]{POLYLINE, 2.5f, 8, 2.5f, 2.5f, 8, 2.5f}, new float[]{POLYLINE, 16, 2.5f, 21.5f, 2.5f, 21.5f, 8},
                        new float[]{POLYLINE, 21.5f, 16, 21.5f, 21.5f, 16, 21.5f}, new float[]{POLYLINE, 8, 21.5f, 2.5f, 21.5f, 2.5f, 16},
                        new float[]{CIRCLE, 12, 12, 3.5f, 1});
            default:
                return new float[0][];
        }
    }
}
