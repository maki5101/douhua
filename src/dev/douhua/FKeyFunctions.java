package dev.douhua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实体键盘 F1–F12 的第二功能（2026-11-02，协议 24；用户："F1–F12 每个键能配一个第二功能，像笔记本那样"）：能选哪些功能、
 * 每个功能真键盘发什么、两套预设、"F 键 + 第二功能"拼成区域 id。纯 Java（离线测，FKeysTest），设置页、FixedLayout 都调它，规则只有这一份。
 *
 * 只放"真键盘能发的"（用户定的原则：豆花模拟一块真键盘，真键盘能发的照样发，主机怎么接是主机的事）。三种发法：
 * - 多媒体页（Consumer）的标准码：音量、媒体、亮度、调度中心、启动台、搜索、听写、表情……（HUT 1.7 第 15 节，码都核过）；
 * - 系统键那一组（Generic Desktop 的 System Control）：睡眠、勿扰、麦克风静音（HUT 1.7 第 4.5 节）；
 * - 键盘页的键或组合键（固件替你按下几个普通键，同"自带修饰键"的键）：F13–F24、PrtSc、Super+P、截屏工具、Copilot 键……
 * 厂商私有的码（苹果的亮度键和 fn、联想的麦克风键）、飞行模式（要再单独一组、"松开时报一次"）、关机、键盘背光都不放（方案 §2.2）。
 * 豆花自己的动作（打开键盘、设置……）、鼠标的动作、靠注入安卓按键的那几个也不放：不是键盘发的。
 *
 * 存储（Boards）：每个键存功能的 id（{@link Function#id}），组合键另有 "key:" + 4 位十六进制的键 id（用法码 | 自带修饰键 << 8，
 * 同 REGION_KEY 的 id；设置页从动作清单 / 自定义组合键拼，{@link #keyFunctionId}）。认不出的 id 当"无"（{@link #get} 返回 null）。
 * 名字、图标归设置页和键帽（按 id 找：strings.xml 的条目名、矢量图标的编号都照 id 起），这里不管。
 */
final class FKeyFunctions {
    private FKeyFunctions() {}

    /** 发法：无（两面都是 F 键本身）、键盘键 / 组合键、多媒体页、系统键那一组（同 Proto.FROW_* 的类型号）。 */
    static final int NONE = 0, KEY = Proto.FROW_KEY, CONSUMER = Proto.FROW_CONSUMER, SYSTEM = Proto.FROW_SYSTEM;

    /** 设置页的分组（方案 §2.2 的顺序）。 */
    static final int GROUP_NONE = 0, GROUP_SOUND = 1, GROUP_MEDIA = 2, GROUP_SCREEN = 3, GROUP_DESKTOP = 4, GROUP_OPEN = 5,
            GROUP_INPUT = 6, GROUP_SYSTEM = 7, GROUP_FKEYS = 8, GROUP_COMBO = 9;

    /** 预设（设置页"预设"那一行）：ThinkPad 风格 / 苹果风格 / 不用（只出 F1–F12）；逐个改过、哪个都对不上就是自定义。 */
    static final String PRESET_THINKPAD = "thinkpad", PRESET_APPLE = "apple", PRESET_NONE = "none", PRESET_CUSTOM = "custom";

    /** 组合键的 id 前缀：后面是 4 位十六进制的键 id。 */
    static final String KEY_PREFIX = "key:";

    /** F1 的用法码（F1–F12 是 0x3A–0x45）。 */
    static final int F1 = 0x3A;
    static final int ESC = 0x29;

    private static final int GUI = Hid.MOD_META, SHIFT = Hid.MOD_SHIFT;

    /** 一个功能：发法、码（组合键 = 用法码 | 自带修饰键 << 8）、Mac 模式换的码（0 = 一样）、分组。 */
    static final class Function {
        final String id;
        final int kind, code, macCode, group;

        Function(String id, int kind, int code, int macCode, int group) {
            this.id = id;
            this.kind = kind;
            this.code = code;
            this.macCode = macCode;
            this.group = group;
        }

        /** 这种模式（FixedLayout.WIN / MAC）下发的码。 */
        int code(String mode) {
            return FixedLayout.MAC.equals(mode) && macCode != 0 ? macCode : code;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final Map<String, Function> BY_ID = new LinkedHashMap<>();

    private static void add(String id, int kind, int code, int group) {
        add(id, kind, code, 0, group);
    }

    private static void add(String id, int kind, int code, int macCode, int group) {
        BY_ID.put(id, new Function(id, kind, code, macCode, group));
    }

    static {
        add("none", NONE, 0, GROUP_NONE);
        // 声音
        add("mute", CONSUMER, 0xE2, GROUP_SOUND);
        add("vol_down", CONSUMER, 0xEA, GROUP_SOUND);
        add("vol_up", CONSUMER, 0xE9, GROUP_SOUND);
        add("mic_mute", SYSTEM, 0xA9, GROUP_SOUND);             // System Microphone Mute（Google 2022 年提的）
        // 媒体
        add("play_pause", CONSUMER, 0xCD, GROUP_MEDIA);
        add("prev_track", CONSUMER, 0xB6, GROUP_MEDIA);
        add("next_track", CONSUMER, 0xB5, GROUP_MEDIA);
        add("stop", CONSUMER, 0xB7, GROUP_MEDIA);
        add("rewind", CONSUMER, 0xB4, GROUP_MEDIA);
        add("fast_forward", CONSUMER, 0xB3, GROUP_MEDIA);
        // 屏幕
        add("bright_down", CONSUMER, 0x70, GROUP_SCREEN);
        add("bright_up", CONSUMER, 0x6F, GROUP_SCREEN);
        // 窗口和桌面
        add("show_windows", CONSUMER, 0x29F, GROUP_DESKTOP);    // AC Desktop Show All Windows：调度中心 / 任务视图
        add("show_apps", CONSUMER, 0x2A2, GROUP_DESKTOP);       // AC Desktop Show All Applications：启动台（现行标准，不发 Keychron 的旧号 0x2A0）
        add("lock", CONSUMER, 0x19E, GROUP_DESKTOP);            // AL Terminal Lock/Screensaver
        // 打开
        add("search", CONSUMER, 0x221, GROUP_OPEN);
        add("calculator", CONSUMER, 0x192, GROUP_OPEN);
        add("mail", CONSUMER, 0x18A, GROUP_OPEN);
        add("browser_home", CONSUMER, 0x223, GROUP_OPEN);
        add("my_computer", CONSUMER, 0x194, GROUP_OPEN);
        add("settings", CONSUMER, 0x19F, GROUP_OPEN);           // AL Control Panel
        // 输入
        add("next_layout", CONSUMER, 0x29D, GROUP_INPUT);       // AC Next Keyboard Layout Select（🌐 切输入法）
        add("dictation", CONSUMER, 0xD8, GROUP_INPUT);          // Start or Stop Voice Dictation Session
        add("emoji", CONSUMER, 0xD9, GROUP_INPUT);              // Invoke/Dismiss Emoji Picker
        // 系统
        add("sleep", SYSTEM, 0x82, GROUP_SYSTEM);
        add("dnd", SYSTEM, 0x9B, GROUP_SYSTEM);                 // System Do Not Disturb（苹果 2020 年提的）
        // 多出来的 F 键
        for (int i = 13; i <= 24; i++) add("f" + i, KEY, 0x68 + i - 13, GROUP_FKEYS);
        // 组合键（预设里用到的、方案点名的；别的从动作清单 / 自定义组合键拼成 "key:XXXX"）
        add("prtsc", KEY, 0x46, GROUP_COMBO);
        add("display", KEY, 0x13 | GUI << 8, GROUP_COMBO);                                  // Super+P：切换显示器 / 投影（Dell 手册 F8 写的就是它）
        add("notifications", KEY, 0x11 | GUI << 8, GROUP_COMBO);                            // Super+N：通知中心（凭记忆，没核）
        add("snip", KEY, 0x16 | (GUI | SHIFT) << 8, 0x21 | (GUI | SHIFT) << 8, GROUP_COMBO); // 截屏工具：Windows Super+Shift+S，Mac ⇧⌘4（Keychron 就这么发）
        add("copilot", KEY, 0x72 | (GUI | SHIFT) << 8, GROUP_COMBO);                        // Copilot 键：左 Win + 左 Shift + F23（联想、惠普、戴尔都这么发）
    }

    /** 全部有名字的功能（"无"排第一，之后按分组），设置页列表照它分组。 */
    static List<Function> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    /**
     * id → 功能：有名字的照表，"key:XXXX" 是组合键（键 id 的用法码不是 0 才认）；null / 认不出的返回 null（当"无"）。
     */
    static Function get(String id) {
        if (id == null) return null;
        Function f = BY_ID.get(id);
        if (f != null) return f;
        if (!id.startsWith(KEY_PREFIX) || id.length() != KEY_PREFIX.length() + 4) return null;
        int code;
        try {
            code = Integer.parseInt(id.substring(KEY_PREFIX.length()), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        if ((code & 0xFF) == 0) return null;
        return new Function(id, KEY, code, 0, GROUP_COMBO);
    }

    /** 组合键 → 存的 id："key:" + 4 位十六进制（小写）。键 id = 用法码 | 自带修饰键 << 8。 */
    static String keyFunctionId(int keyId) {
        return KEY_PREFIX + String.format(Locale.ROOT, "%04x", keyId & 0xFFFF);
    }

    /** 第 n 个 F 键（1–12）的用法码。 */
    static int fUsage(int n) {
        return F1 + n - 1;
    }

    /**
     * 第 n 个 F 键（1–12）配了功能 f 时报给分流的区域 id：没配（null / 无）就是 F 键本身的用法码（普通键，两面都是它），
     * 不然是 Proto.frowKey(发法, F 键的用法码, 这种模式下的码)。哪一面在按下那一刻由 KeyHolds 定。
     */
    static int regionId(int n, Function f, String mode) {
        int base = fUsage(n);
        if (f == null || f.kind == NONE) return base;
        return Proto.frowKey(f.kind, base, f.code(mode));
    }

    /** Esc 的区域 id：有 F 行第二功能的尺寸上是 F 行翻转开关（Fn+Esc 翻转，Fn 不起作用时就是 Esc），别的尺寸照旧 0x29。 */
    static int escId(boolean fnLock) {
        return fnLock ? Proto.FN_LOCK_KEY : ESC;
    }

    /**
     * 预设的 12 个功能 id。ThinkPad 风格照 T14 Gen 4 / X1 Carbon 近几代（F8 真机是飞行模式、F12 是"自定义键"，这批都空着）；
     * 苹果风格照 2021 年以后的 MacBook Pro / 带触控 ID 的妙控键盘的顺序，发法照第三方 Mac 键盘（Keychron）直接发标准码
     * （F7 / F9 发上一首 / 下一首，苹果实物印的是倒回 / 快进）。不用 = 12 个都是"无"。认不出的预设当"不用"。
     */
    static String[] preset(String name) {
        if (PRESET_THINKPAD.equals(name)) {
            return new String[]{"mute", "vol_down", "vol_up", "mic_mute", "bright_down", "bright_up",
                    "display", "none", "notifications", "prtsc", "snip", "none"};
        }
        if (PRESET_APPLE.equals(name)) {
            return new String[]{"bright_down", "bright_up", "show_windows", "search", "dictation", "dnd",
                    "prev_track", "play_pause", "next_track", "mute", "vol_down", "vol_up"};
        }
        String[] none = new String[12];
        java.util.Arrays.fill(none, "none");
        return none;
    }

    /** 这种模式的默认预设（用户 2026-11-02 定的，照 Keychron 的两种模式）：Windows 模式 ThinkPad 那套，Mac 模式苹果那套。 */
    static String defaultPreset(String mode) {
        return FixedLayout.MAC.equals(mode) ? PRESET_APPLE : PRESET_THINKPAD;
    }

    /** 这种模式默认"平时按出"哪一面：Windows 模式平时 F1–F12（假），Mac 模式平时第二功能（真）。 */
    static boolean defaultFrowFlip(String mode) {
        return FixedLayout.MAC.equals(mode);
    }

    /** 12 个功能 id 对得上哪个预设（"无"和认不出的 id 一样算"无"）；哪个都对不上是 {@link #PRESET_CUSTOM}。 */
    static String presetOf(String[] ids) {
        for (String p : new String[]{PRESET_THINKPAD, PRESET_APPLE, PRESET_NONE}) {
            String[] want = preset(p);
            boolean same = ids != null && ids.length == 12;
            for (int i = 0; same && i < 12; i++) same = normalize(ids[i]).equals(want[i]);
            if (same) return p;
        }
        return PRESET_CUSTOM;
    }

    /** 认不出的 id 当"无"。 */
    private static String normalize(String id) {
        Function f = get(id);
        return f == null ? "none" : f.id;
    }
}
