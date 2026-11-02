package dev.douhua;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置 → 键盘 → 实体键盘"F1–F12 的第二功能"那一节的规则（协议 24，2026-11-02，方案 design-fkeys §2.1 / §2.2）：每个 F 键的选择列表里有哪些、
 * 按什么顺序分组，存着的功能 id 对应列表里哪一项、那一行写什么，"自定义组合键"拼出来的码存成什么 id，"预设"那一行有哪几项、
 * "平时按出"和 12 行什么时候显示。纯 Java（离线测，FKeyChoicesTest），SettingsActivity 只照它建界面、取名字（名字要 Context，留在界面类）。
 *
 * 列表只放真键盘能发的（用户定的原则）：
 * - "无"排第一；之后照 FKeyFunctions 的分组（声音 / 媒体 / 屏幕 / 窗口和桌面 / 打开 / 输入 / 系统 / 多出来的 F 键 / 组合键），每组前面一个标题；
 * - 组合键那一组后面接"快捷键"一组：动作清单里的键盘快捷键（Actions 里发 HID 的那些——复制、Alt+Tab、浏览器后退……，设置页传进来；
 *   Mac 模式传苹果习惯的那个组合，⌘C 而不是 Ctrl+C，同动作清单连苹果设备时的发法）。码和前面有名字的功能、前面的快捷键重了的不列（截屏 0x46 = PrtSc）；
 * - 最后一项"自定义组合键…"（自己勾修饰键、挑一个键）。
 * 豆花自己的动作（打开键盘、设置……）、鼠标的动作、靠注入安卓按键的那几个（返回、最近任务、媒体键……）不是键盘发的，不列（Actions 里只取 HID 的，由调用方过滤）。
 * Mac 模式不列只在 Windows 上有意思的三个组合键（{@link #WINDOWS_ONLY}：Super+P 切换显示器、Super+N 通知中心、Copilot 键）：
 * 在 Mac 上那是 ⌘P 打印、⌘N 新建，列成"切换显示器"是骗人；存着的（Mac 模式选了 ThinkPad 那套）照旧显示、照旧发，行上写的是它实际发的组合。
 *
 * 存的 id：有名字的功能存名字；快捷键、自定义组合键存 "key:XXXX"（FKeyFunctions.keyFunctionId）。自定义组合键拼出来的码正好是某个列着的有名字功能的码
 * （Windows 模式的 Super+P、Mac 模式的 ⇧⌘4、F13、PrtSc……）就存那个名字（{@link #canonical}），预设认得回来。
 */
final class FKeyChoices {
    private FKeyChoices() {}

    /** 列表的一项：分组标题、有名字的功能、动作清单里的快捷键、"自定义组合键…"。 */
    static final int HEADER = 0, FUNCTION = 1, SHORTCUT = 2, CUSTOM = 3;

    /** "快捷键"那一组（接在 FKeyFunctions 的分组后面）。 */
    static final int GROUP_SHORTCUTS = FKeyFunctions.GROUP_COMBO + 1;

    /** Mac 模式不列的有名字功能（只在 Windows 上有那个意思的组合键）。 */
    static final Set<String> WINDOWS_ONLY = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("display", "notifications", "copilot")));

    /** 动作清单里的一个键盘快捷键：动作 id（名字照它取）、这种模式下按的组合键（用法码 | 自带修饰键 << 8）。 */
    static final class Shortcut {
        final String actionId;
        final int code;

        Shortcut(String actionId, int code) {
            this.actionId = actionId;
            this.code = code;
        }
    }

    /** 列表的一项（或者一行上描述的那一项，不一定在列表里）。 */
    static final class Item {
        final int type, group;
        /** 存进设置的 id：FUNCTION 是功能名字，SHORTCUT 和行上描述的自定义组合键是 "key:XXXX"；HEADER、列表里的 CUSTOM 是 null。 */
        final String id;
        /** SHORTCUT：动作清单的 id（名字照它取）；别的 null。 */
        final String actionId;
        /** 这种模式下发的组合键（键盘键那一类的 FUNCTION、SHORTCUT、行上描述的自定义组合键）；多媒体 / 系统键、"无"、HEADER、列表里的 CUSTOM 是 0。 */
        final int code;
        /** FUNCTION：功能本身（名字、发法）；别的 null。 */
        final FKeyFunctions.Function function;

        Item(int type, int group, String id, String actionId, int code, FKeyFunctions.Function function) {
            this.type = type;
            this.group = group;
            this.id = id;
            this.actionId = actionId;
            this.code = code;
            this.function = function;
        }

        /** 点得了（不是分组标题）。 */
        boolean selectable() {
            return type != HEADER;
        }

        @Override
        public String toString() {
            switch (type) {
                case HEADER: return "#" + group;
                case SHORTCUT: return actionId + "=" + id;
                case CUSTOM: return id == null ? "custom…" : "custom=" + id;
                default: return id;
            }
        }
    }

    /** 这种模式（FixedLayout.WIN / MAC）的列表里有没有这个有名字的功能。 */
    static boolean offered(FKeyFunctions.Function f, String mode) {
        return !(FixedLayout.MAC.equals(mode) && WINDOWS_ONLY.contains(f.id));
    }

    /** 有名字功能在这种模式下的组合键：键盘键那一类才有（多媒体 / 系统键、"无"是 0）。 */
    private static int keyCode(FKeyFunctions.Function f, String mode) {
        return f.kind == FKeyFunctions.KEY ? f.code(mode) & 0xFFFF : 0;
    }

    /**
     * 这种模式下一个 F 键的选择列表（见类注释）。shortcuts 是动作清单里的键盘快捷键（按动作清单的顺序；码已经是这种模式下按的那个）；
     * 用法码是 0 的、和前面重了的不列。最后一项一定是"自定义组合键…"。
     */
    static List<Item> items(String mode, List<Shortcut> shortcuts) {
        List<Item> out = new ArrayList<>();
        Set<Integer> codes = new HashSet<>();
        int group = -1;
        for (FKeyFunctions.Function f : FKeyFunctions.all()) {
            if (!offered(f, mode)) continue;
            if (f.group != FKeyFunctions.GROUP_NONE && f.group != group) {
                group = f.group;
                out.add(new Item(HEADER, group, null, null, 0, null));
            }
            int code = keyCode(f, mode);
            if (code != 0) codes.add(code);
            out.add(new Item(FUNCTION, f.group, f.id, null, code, f));
        }
        boolean header = false;
        if (shortcuts != null) {
            for (Shortcut s : shortcuts) {
                int code = s.code & 0xFFFF;
                if ((code & 0xFF) == 0 || !codes.add(code)) continue;
                if (!header) {
                    out.add(new Item(HEADER, GROUP_SHORTCUTS, null, null, 0, null));
                    header = true;
                }
                out.add(new Item(SHORTCUT, GROUP_SHORTCUTS, FKeyFunctions.keyFunctionId(code), s.actionId, code, null));
            }
        }
        out.add(new Item(CUSTOM, GROUP_SHORTCUTS, null, null, 0, null));
        return out;
    }

    /**
     * 存着的功能 id 是列表里的哪一项（行上写它、列表里把它标成选中的）：
     * - 认不出的、"无" → 列表里的"无"；
     * - 有名字的 → 列表里那一项；这种模式不列的（Mac 模式存着 Super+P）→ 一个不在列表里的 FUNCTION（行上照样写名字和组合）；
     * - "key:XXXX" → 码和列着的有名字功能（键盘键那一类）一样的那一项、码一样的快捷键；都不是 → 一个不在列表里的 CUSTOM（带着 id 和码，行上写组合本身）。
     */
    static Item describe(List<Item> items, String storedId, String mode) {
        FKeyFunctions.Function f = FKeyFunctions.get(storedId);
        if (f == null || f.kind == FKeyFunctions.NONE) {
            Item none = find(items, "none");   // items() 里一定有；保险：不在时现造一个，调用方不会拿到 null
            return none != null ? none : new Item(FUNCTION, FKeyFunctions.GROUP_NONE, "none", null, 0, FKeyFunctions.get("none"));
        }
        if (!storedId.startsWith(FKeyFunctions.KEY_PREFIX)) {
            Item named = find(items, f.id);
            return named != null ? named : new Item(FUNCTION, f.group, f.id, null, keyCode(f, mode), f);
        }
        int code = f.code & 0xFFFF;
        for (Item i : items) {
            if ((i.type == FUNCTION || i.type == SHORTCUT) && i.code == code) return i;
        }
        return new Item(CUSTOM, GROUP_SHORTCUTS, FKeyFunctions.keyFunctionId(code), null, code, null);
    }

    /** 列表里名字是 id 的那一项有名字的功能；没列就是 null。 */
    private static Item find(List<Item> items, String id) {
        for (Item i : items) if (i.type == FUNCTION && i.id.equals(id)) return i;
        return null;
    }

    /** 列表里该标成选中的那一项的下标：describe 出来的在列表里就是它，自定义组合键是"自定义组合键…"那一项，这种模式不列的有名字功能是 -1（哪个都不标）。 */
    static int selected(List<Item> items, String storedId, String mode) {
        Item d = describe(items, storedId, mode);
        int i = items.indexOf(d);
        if (i >= 0) return i;
        if (d.type == CUSTOM) {
            for (int k = 0; k < items.size(); k++) if (items.get(k).type == CUSTOM) return k;
        }
        return -1;
    }

    /**
     * "自定义组合键…"拼出来的码（用法码 | 自带修饰键 << 8）存成什么 id：码正好是列着的有名字功能（键盘键那一类）在这种模式下的码就存那个名字
     * （预设认得回来、行上写名字），不然 "key:XXXX"。
     */
    static String canonical(List<Item> items, int code) {
        int c = code & 0xFFFF;
        for (Item i : items) {
            if (i.type == FUNCTION && i.code == c) return i.id;
        }
        return FKeyFunctions.keyFunctionId(c);
    }

    // ═══════════ "预设""平时按出"、12 行 ═══════════

    /** 预设那一行能选的几项：ThinkPad 风格、苹果风格、不用（只出 F1–F12）。现在是自定义时多一项"自定义"排在最后（灰着，只是写现在的样子，点不了）。 */
    static String[] presetChoices(String current) {
        return FKeyFunctions.PRESET_CUSTOM.equals(current)
                ? new String[]{FKeyFunctions.PRESET_THINKPAD, FKeyFunctions.PRESET_APPLE, FKeyFunctions.PRESET_NONE, FKeyFunctions.PRESET_CUSTOM}
                : new String[]{FKeyFunctions.PRESET_THINKPAD, FKeyFunctions.PRESET_APPLE, FKeyFunctions.PRESET_NONE};
    }

    /**
     * "平时按出"和 12 行显示不显示：选"不用"（12 个都是"无"）时都藏起来——F 行只出 F1–F12，翻转没有东西可翻（键盘上 Esc 这时也照旧是 Esc，不是翻转开关）。
     * 要再配就先挑一套预设。
     */
    static boolean keysShown(String preset) {
        return !FKeyFunctions.PRESET_NONE.equals(preset);
    }
}
