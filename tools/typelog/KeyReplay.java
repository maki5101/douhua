import dev.douhua.Proto;
import dev.douhua.TouchRouter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 打字测试的重放：把 tools/typelog/analyze.py 导出的布局和触点（replay.txt）交给真正的 TouchRouter（和 src 里的同一份源码一起编译），
 * 旧 = 只看矩形（去掉键盘范围，缝里没反应），新 = 现在的规则（缝分给最近的键）。规则只有一份源码，以后学落点偏移也在这里加。
 * <pre>
 * java KeyReplay replay.txt check     "新"必须等于手机日志里实际用的键；按在键上的触点，"旧"必须等于"新"
 * java KeyReplay replay.txt compare   缝里的触点占多少、其中分对 / 分错几个（以前没反应，现在出对 / 出错的字）
 * </pre>
 * replay.txt：每份布局一段 "L l t r b"（键盘范围）+ "K type id l t r b"（键），后面是用这份布局的触点
 * "T x y 实际用的键(id 或 -) 实际键的 l t r b(或 - - - -) 想按的键(id 或 -)"，id 十六进制。
 */
public class KeyReplay {
    static final class Touch {
        float x, y;
        String used;
        String usedRect;
        String intended;
    }

    static final class Block {
        TouchRouter.Region area;
        final List<TouchRouter.Region> keys = new ArrayList<>();
        final List<Touch> touches = new ArrayList<>();
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.err.println("用法：java KeyReplay replay.txt [check|compare]");
            System.exit(2);
        }
        List<Block> blocks = load(a[0]);
        String mode = a.length > 1 ? a[1] : "compare";
        if (mode.equals("check")) check(blocks);
        else compare(blocks);
    }

    static List<Block> load(String path) throws Exception {
        List<Block> blocks = new ArrayList<>();
        Block cur = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 0 || f[0].isEmpty() || f[0].startsWith("#")) continue;
                switch (f[0]) {
                    case "L":
                        cur = new Block();
                        cur.area = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, i(f[1]), i(f[2]), i(f[3]), i(f[4]));
                        blocks.add(cur);
                        break;
                    case "K":
                        cur.keys.add(new TouchRouter.Region(i(f[1]), Integer.parseInt(f[2], 16), i(f[3]), i(f[4]), i(f[5]), i(f[6])));
                        break;
                    case "T": {
                        Touch t = new Touch();
                        t.x = Float.parseFloat(f[1]);
                        t.y = Float.parseFloat(f[2]);
                        t.used = f[3];
                        t.usedRect = f[4] + " " + f[5] + " " + f[6] + " " + f[7];
                        t.intended = f[8];
                        cur.touches.add(t);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("不认识的行：" + line);
                }
            }
        }
        return blocks;
    }

    static int i(String s) {
        return Integer.parseInt(s);
    }

    /** 新规则：键 + 键盘范围（排在键后面，和 KeyboardPanel.collectRegions 报的一样）。 */
    static TouchRouter router(Block b, boolean withArea) {
        TouchRouter t = new TouchRouter(new Quiet());
        List<TouchRouter.Region> rs = new ArrayList<>(b.keys);
        if (withArea) rs.add(b.area);
        t.setRegions(rs.toArray(new TouchRouter.Region[0]));
        return t;
    }

    static String id(TouchRouter.Region r) {
        return r == null ? "-" : Integer.toHexString(r.id);
    }

    static String rect(TouchRouter.Region r) {
        return r == null ? "- - - -" : r.left + " " + r.top + " " + r.right + " " + r.bottom;
    }

    static void check(List<Block> blocks) {
        int n = 0, bad = 0;
        for (Block b : blocks) {
            TouchRouter now = router(b, true), old = router(b, false);
            for (Touch t : b.touches) {
                n++;
                TouchRouter.Region nr = now.hit(t.x, t.y), or = old.hit(t.x, t.y);
                boolean ok = id(nr).equals(t.used) && rect(nr).equals(t.usedRect);
                if (or != null && !(id(or).equals(id(nr)) && rect(or).equals(rect(nr)))) ok = false;
                if (!ok) {
                    bad++;
                    if (bad <= 10) {
                        System.err.printf(Locale.ROOT, "✗ (%.1f, %.1f)：日志里用的 %s [%s]，重放新 %s [%s]，旧 %s%n",
                                t.x, t.y, t.used, t.usedRect, id(nr), rect(nr), id(or));
                    }
                }
            }
        }
        System.err.println("打字重放核对：" + n + " 个触点，" + bad + " 处和手机上不一致");
        if (bad > 0) System.exit(1);
    }

    static void compare(List<Block> blocks) {
        int n = 0, onKey = 0, changed = 0, gap = 0, right = 0, wrong = 0, unknown = 0, still = 0;
        Map<String, Integer> misses = new TreeMap<>();
        for (Block b : blocks) {
            TouchRouter now = router(b, true), old = router(b, false);
            for (Touch t : b.touches) {
                n++;
                TouchRouter.Region nr = now.hit(t.x, t.y), or = old.hit(t.x, t.y);
                if (or != null) {
                    onKey++;
                    if (!id(or).equals(id(nr))) changed++;
                    continue;
                }
                gap++;
                if (nr == null) still++;
                else if (t.intended.equals("-")) unknown++;
                else if (nr.type == Proto.REGION_KEY && id(nr).equals(t.intended)) right++;
                else {
                    wrong++;
                    misses.merge(label(t.intended) + " → " + label(nr), 1, Integer::sum);
                }
            }
        }
        System.out.printf(Locale.ROOT, "新旧规则重放：%d 个触点，按在键上 %d 个（新旧不同 %d 个，应该是 0），按在缝里 %d 个（%.1f%%）%n",
                n, onKey, changed, gap, n == 0 ? 0 : gap * 100.0 / n);
        System.out.printf(Locale.ROOT, "  缝里的：以前没反应；现在分对 %d、分错 %d、不知道想按哪个 %d、还是没出键 %d%n", right, wrong, unknown, still);
        for (Map.Entry<String, Integer> e : misses.entrySet()) System.out.println("    分错：想按 " + e.getKey() + " ×" + e.getValue());
    }

    static String label(TouchRouter.Region r) {
        if (r == null) return "（没有）";
        return r.type == Proto.REGION_KEY ? label(Integer.toHexString(r.id)) : "按钮 " + r.id;
    }

    static String label(String hexId) {
        int u = Integer.parseInt(hexId, 16);
        if (u >= 0x04 && u <= 0x1D) return String.valueOf((char) ('a' + u - 0x04));
        switch (u) {
            case 0x2C: return "空格";
            case 0x36: return ",";
            case 0x37: return ".";
            case 0x2A: return "⌫";
            case 0x28: return "↵";
            case 0xE1: return "⇧";
            default: return "键 " + hexId;
        }
    }

    /** 重放只看判定结果，分流的输出都不要。 */
    static final class Quiet implements TouchRouter.Output {
        @Override public void mouse(int buttons, int dx, int dy, int wheel, int pan) { }
        @Override public void keyboard(byte[] report) { }
        @Override public void haptic(int kind) { }
        @Override public void gesture(int fingers, int kind) { }
        @Override public void button(int id, int action) { }
        @Override public void modifiers(int latched, int held, int armed, int fn) { }
        @Override public void caps(boolean sent) { }
        @Override public void schedule(long delayMs) { }
        @Override public void cancelSchedule() { }
        @Override public void scheduleKeys(long delayMs) { }
        @Override public void cancelKeys() { }
        @Override public void startTicks() { }
        @Override public void stopTicks() { }
        @Override public void gamepad(byte[] report) { }
    }
}
