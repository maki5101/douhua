package dev.douhua;

/**
 * 主机实测扫描（DebugMoves 的 DEBUG_KEYSCAN，电脑上 tools/kbd-scan.sh）的纯逻辑：许发哪些键、广播里的键怎么解析、一段键拆成哪几拍。
 * 每个键三下：这个键、一个空格（死键后面看组合出什么）、一个 Tab（主机上的页面按 Tab 分步），每下按下 / 松开各一拍。
 * 纯 Java（离线测，KeyScanTest）。
 */
final class KeyScan {
    /** 一次广播最多几个键：电脑那边一段发 6 个；页面失去焦点、脚本被打断时最多再多出这么多个键。 */
    static final int MAX = 12;
    static final int USAGE_TAB = 0x2B;
    static final int USAGE_SPACE = 0x2C;
    /** 许用的修饰键位（HID 报文的修饰键字节）：左 / 右 Shift（0x02 / 0x20）、左 / 右 Alt（0x04 / 0x40）。 */
    static final int MODS = 0x02 | 0x20 | 0x04 | 0x40;
    /** 每个键几拍。 */
    static final int BEATS_PER_KEY = 6;

    private KeyScan() {
    }

    /**
     * 只发这些：主键区出字的位置（0x04–0x27、0x2D–0x38）、小键盘小数点 0x63、ISO / JIS / 巴西多出来的 0x64 / 0x85 / 0x87 / 0x89；
     * 修饰键只许 Shift、Alt（AltGr / Option 层要用）。Ctrl / GUI（⌘ / Super）、Enter、Esc、Backspace、Tab、空格本身都不许：
     * 主机上的页面没拿到焦点时，这些键落到别的应用里会关窗口、发消息（空格和 Tab 由扫描自己在每个键后面补）。
     */
    static boolean allowed(int key) {
        if (key < 0 || key > 0xFFFF) return false;
        int usage = key & 0xFF;
        int mods = key >> 8;
        boolean printable = (usage >= 0x04 && usage <= 0x27) || (usage >= 0x2D && usage <= 0x38)
                || usage == 0x63 || usage == 0x64 || usage == 0x85 || usage == 0x87 || usage == 0x89;
        return printable && (mods & ~MODS) == 0;
    }

    /** "414,416,…"（十六进制，用法码 | 修饰键 << 8）→ 键。格式不对、超过 MAX 个、有不许发的键都抛 IllegalArgumentException（带原因）。 */
    static int[] parse(String s) {
        String[] p = s.split(",");
        if (p.length > MAX) throw new IllegalArgumentException("一次最多 " + MAX + " 个键，这次 " + p.length + " 个");
        int[] keys = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                keys[i] = Integer.parseInt(p[i].trim(), 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("键的格式不对：" + p[i].trim());
            }
            if (!allowed(keys[i])) {
                throw new IllegalArgumentException("键 " + p[i].trim() + " 不在允许的范围里（只发出字的位置和 Shift / Alt）");
            }
        }
        return keys;
    }

    /** 第 beat 拍（0 起）按的是哪个键。 */
    static int keyAt(int[] keys, int beat) {
        int which = beat % BEATS_PER_KEY / 2;
        return which == 0 ? keys[beat / BEATS_PER_KEY] : which == 1 ? USAGE_SPACE : USAGE_TAB;
    }

    /** 第 beat 拍是按下（不然是松开）。 */
    static boolean downAt(int beat) {
        return beat % 2 == 0;
    }

    /** 这一段一共几拍。 */
    static int beats(int[] keys) {
        return keys.length * BEATS_PER_KEY;
    }
}
