package dev.douhua;

/**
 * 主机实测扫描（KeyScan）：许发哪些键（出字的位置 + Shift / Alt，绝不发 Ctrl / ⌘ / Enter 这类会在别的应用里闯祸的）、
 * 广播里的键怎么解析（超过一段的上限、格式不对、有不许的键都整段拒绝）、一段拆成的拍（每个键后面跟空格和 Tab，每个按下都有松开）。
 */
public final class KeyScanTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        // 许发的：字母、数字、标点、小键盘小数点、ISO / JIS 多出来的键，带不带 Shift / Alt（左右）
        for (int u = 0x04; u <= 0x27; u++) check(KeyScan.allowed(u), "字母 / 数字 0x" + Integer.toHexString(u));
        for (int u = 0x2D; u <= 0x38; u++) check(KeyScan.allowed(u), "标点 0x" + Integer.toHexString(u));
        check(KeyScan.allowed(0x63) && KeyScan.allowed(0x64) && KeyScan.allowed(0x87) && KeyScan.allowed(0x89), "小键盘小数点、ISO / JIS 的键");
        check(KeyScan.allowed(0x0214) && KeyScan.allowed(0x2014), "Shift（左右）+ 键");
        check(KeyScan.allowed(0x0414) && KeyScan.allowed(0x4014) && KeyScan.allowed(0x4214), "Option / AltGr（+ Shift）+ 键");
        // 不许的：会在别的应用里闯祸的键、修饰键本身、Ctrl / GUI 组合、越界
        for (int u : new int[] {0x00, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x39, 0x3A, 0x4C, 0x52, 0x65, 0xE0, 0xE3, 0xE7}) {
            check(!KeyScan.allowed(u), "不许发 0x" + Integer.toHexString(u));
        }
        for (int mod : new int[] {0x01, 0x08, 0x10, 0x80, 0x05, 0x48}) {
            check(!KeyScan.allowed(0x14 | mod << 8), "不许带修饰键 0x" + Integer.toHexString(mod) + "（Ctrl / GUI）");
        }
        check(!KeyScan.allowed(-1) && !KeyScan.allowed(0x10000), "越界");

        // 解析
        int[] k = KeyScan.parse("4,214, 4014 ,4214");
        check(k.length == 4 && k[0] == 0x04 && k[1] == 0x214 && k[2] == 0x4014 && k[3] == 0x4214, "十六进制、带空格");
        check(throwsOn("4,5,6,7,8,9,a,b,c,d,e,f,10"), "超过 " + KeyScan.MAX + " 个整段拒绝");
        check(KeyScan.parse("4,5,6,7,8,9,a,b,c,d,e,f").length == KeyScan.MAX, "正好 " + KeyScan.MAX + " 个可以");
        check(throwsOn("4,28,5"), "一段里有 Enter：整段拒绝");
        check(throwsOn("4,114"), "一段里有 Ctrl+Q：整段拒绝");
        check(throwsOn("4,zz"), "格式不对：整段拒绝");
        check(throwsOn(""), "空的：拒绝");

        // 拍：键 / 空格 / Tab 依次按下、松开；每个按下后面紧跟同一个键的松开（停在哪一拍都只可能有一个键按着）
        int[] two = {0x4014, 0x64};
        check(KeyScan.beats(two) == 12, "两个键 12 拍");
        int[] want = {0x4014, 0x4014, 0x2C, 0x2C, 0x2B, 0x2B, 0x64, 0x64, 0x2C, 0x2C, 0x2B, 0x2B};
        for (int b = 0; b < KeyScan.beats(two); b++) {
            check(KeyScan.keyAt(two, b) == want[b], "第 " + b + " 拍是 0x" + Integer.toHexString(want[b]));
            check(KeyScan.downAt(b) == (b % 2 == 0), "第 " + b + " 拍" + (b % 2 == 0 ? "按下" : "松开"));
        }
        for (int b = 1; b < KeyScan.beats(two); b += 2) {
            check(KeyScan.keyAt(two, b) == KeyScan.keyAt(two, b - 1), "第 " + b + " 拍松开的就是刚按下的");
        }

        System.out.println("按键扫描（KeyScan）：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    private static boolean throwsOn(String s) {
        try {
            KeyScan.parse(s);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("不对：" + what);
        }
    }
}
