package dev.douhua;

import java.util.ArrayList;
import java.util.List;

/**
 * "自定义组合键"用的东西：可选的键、修饰键位、拼成 HID 键 id、起名字。键名用键盘上通用的英文写法，不随语言变。
 * 键 id 同 Proto.REGION_KEY：HID 用法码 | (修饰键 << 8)。
 */
final class Combos {
    static final int CTRL = 1;
    static final int SHIFT = 2;
    static final int ALT = 4;
    static final int WIN = 8;

    static final String[] KEY_LABELS;
    static final int[] KEY_USAGES;

    static {
        List<String> labels = new ArrayList<>();
        List<Integer> usages = new ArrayList<>();
        for (char c = 'a'; c <= 'z'; c++) {
            labels.add(String.valueOf(Character.toUpperCase(c)));
            usages.add(0x04 + (c - 'a'));
        }
        for (int d = 1; d <= 10; d++) {
            labels.add(String.valueOf(d % 10));
            usages.add(0x1E + d - 1);
        }
        for (int f = 1; f <= 12; f++) {
            labels.add("F" + f);
            usages.add(0x3A + f - 1);
        }
        String[] names = {"Enter", "Esc", "Backspace", "Tab", "Space", "Del", "Ins", "Home", "End", "PgUp", "PgDn",
                "←", "↑", "↓", "→", "-", "=", "[", "]", "\\", ";", "'", "`", ",", ".", "/", "PrtSc"};
        int[] codes = {0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x4C, 0x49, 0x4A, 0x4D, 0x4B, 0x4E,
                0x50, 0x52, 0x51, 0x4F, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x46};
        for (int i = 0; i < names.length; i++) {
            labels.add(names[i]);
            usages.add(codes[i]);
        }
        KEY_LABELS = labels.toArray(new String[0]);
        KEY_USAGES = new int[usages.size()];
        for (int i = 0; i < KEY_USAGES.length; i++) KEY_USAGES[i] = usages.get(i);
    }

    private Combos() {}

    static int combine(int usage, boolean ctrl, boolean shift, boolean alt, boolean win) {
        int mods = (ctrl ? CTRL : 0) | (shift ? SHIFT : 0) | (alt ? ALT : 0) | (win ? WIN : 0);
        return usage | (mods << 8);
    }

    /** "Ctrl+Shift+T" 这样的名字。 */
    static String describe(int key) {
        int usage = key & 0xFF;
        int mods = (key >> 8) & 0xFF;
        StringBuilder s = new StringBuilder();
        if ((mods & CTRL) != 0) s.append("Ctrl+");
        if ((mods & SHIFT) != 0) s.append("Shift+");
        if ((mods & ALT) != 0) s.append("Alt+");
        if ((mods & WIN) != 0) s.append("Win+");
        String name = "?";
        for (int i = 0; i < KEY_USAGES.length; i++) {
            if (KEY_USAGES[i] == usage) {
                name = KEY_LABELS[i];
                break;
            }
        }
        return s.append(name).toString();
    }

    /**
     * 上一版键盘快捷行存的是 "键id:标签"，换成现在的动作 id：清单里有同样组合键的就用清单里的，否则当自定义组合键。
     * 不是旧格式的原样返回。
     */
    static String migrateLegacy(String stored) {
        if (stored == null || !stored.matches("\\d+:.*")) return stored;
        int i = stored.indexOf(':');
        int key = Integer.parseInt(stored.substring(0, i));
        if (key == 0) return "none";
        for (Actions.Action a : Actions.all()) {
            if (a.kind == Actions.Kind.HID && a.code == key) return a.id;
        }
        return Actions.combo(key, stored.substring(i + 1));
    }
}
