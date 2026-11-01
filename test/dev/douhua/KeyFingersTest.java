package dev.douhua;

import java.util.Arrays;

/** KeyFingers 的离线测试：键盘面板自己收触摸时，按下定键和多根手指各自按下 / 抬起。 */
public final class KeyFingersTest {
    private static int failures;
    private static int checks;

    // 面板 0–230 × 0–100；一行两个键 A [10,100)×[10,50)、B [120,220)×[10,50)，中间 20 像素的缝；
    // 第二行一个特殊键 S [10,220)×[60,90)（编号 = 下标，同 KeyboardPanel）
    private static final TouchRouter.Region AREA = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, 230, 100);
    private static final TouchRouter.Region[] KEYS = {
            new TouchRouter.Region(Proto.REGION_KEY, 0, 10, 10, 100, 50),
            new TouchRouter.Region(Proto.REGION_KEY, 1, 120, 10, 220, 50),
            new TouchRouter.Region(Proto.REGION_BUTTON, 2, 10, 60, 220, 90),
    };
    private static final int A = 0, B = 1, S = 2;

    public static void main(String[] args) {
        // ── 按下定键 ──
        check(pick(50, 30) == A, "按在 A 上是 A");
        check(pick(150, 30) == B, "按在 B 上是 B");
        check(pick(105, 30) == A, "缝里靠 A 的一侧分给 A");
        check(pick(115, 30) == B, "缝里靠 B 的一侧分给 B");
        check(pick(2, 30) == A, "左边距分给 A");
        check(pick(150, 53) == B, "两行之间的缝先比竖向：靠上一行分给 B");
        check(pick(150, 58) == S, "两行之间的缝先比竖向：靠下一行分给 S");
        check(pick(150, 98) == S, "下边距分给 S");
        check(pick(240, 30) == -1, "面板范围外（多指时派过来的别处手指）不算任何键");
        check(pick(-1, 30) == -1, "左边外面也不算");
        // 和分流同一条规则：缝里的点 TouchRouter.nearestKey 怎么分，这里就怎么分
        for (float x = 0; x < 230; x += 7) {
            for (float y = 0; y < 100; y += 3) {
                boolean onKey = false;
                for (TouchRouter.Region r : KEYS) onKey |= r.contains(x, y);
                if (onKey) continue;
                TouchRouter.Region near = TouchRouter.nearestKey(KEYS, AREA, x, y);
                check(pick(x, y) == near.id, "缝里 (" + x + ", " + y + ") 和 nearestKey 一致");
            }
        }

        // ── 分裂键盘：两块范围，中间是触控板那一截 ──
        // 左半 0–100：键 L [10,90)×[10,90)；右半 200–300：键 R [210,290)×[10,90)；100–200 不在任何范围里
        TouchRouter.Region[] halves = {
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 0, 100, 100),
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 200, 0, 300, 100),
        };
        TouchRouter.Region[] lr = {
                new TouchRouter.Region(Proto.REGION_KEY, 0, 10, 10, 90, 90),
                new TouchRouter.Region(Proto.REGION_KEY, 1, 210, 10, 290, 90),
        };
        check(KeyFingers.pick(lr, halves, 50, 50).id == 0, "分裂：按在左键上");
        check(KeyFingers.pick(lr, halves, 98, 50).id == 0, "分裂：左半靠中间的边距归左半的键");
        check(KeyFingers.pick(lr, halves, 202, 50).id == 1, "分裂：右半靠中间的边距归右半的键");
        check(KeyFingers.pick(lr, halves, 150, 50) == null, "分裂：中间（触控板那一截）不算任何键");
        check(KeyFingers.pick(lr, halves, 105, 50) == null, "分裂：离左键更近但不在任何范围里，也不算");

        // ── 嵌在键里的小红点（洞）：按得到的是报上来那个正方形的内切圆（2026-11-01 起）。圆里的点不算任何键（连底下的键角、
        //    缝里离得近的键也不分），圆外——包括正方形的四个角——和没洞时一模一样 ──
        // 洞 [90,130)×[35,75)：圆心 (110, 55)、半径 20，压着 A 的右下角、B 的左下角、S 的上沿那一截，还有 A、B 之间的缝
        TouchRouter.Region[] whole = {AREA};
        TouchRouter.Region[] holes = {new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, 90, 35, 130, 75)};
        int inHole = 0, corners = 0;
        boolean holeOk = true, circleOk = true;
        for (int x = 85; x < 135; x++) {
            for (int y = 30; y < 80; y++) {
                TouchRouter.Region got = KeyFingers.pick(KEYS, whole, holes, x, y);
                float dx = x - 110, dy = y - 55;
                boolean inCircle = dx * dx + dy * dy < 400;
                circleOk &= holes[0].contains(x, y) == inCircle;
                if (inCircle) {
                    inHole++;
                    holeOk &= got == null;
                } else {
                    if (x >= 90 && x < 130 && y >= 35 && y < 75) corners++;
                    holeOk &= got == KeyFingers.pick(KEYS, whole, x, y);
                }
            }
        }
        check(circleOk, "洞（REGION_STICK）按正方形的内切圆认");
        check(holeOk && inHole > 1200 && inHole < 1290, "圆里 " + inHole + " 个点都不算键（π × 20² ≈ 1257），圆外和没洞时一样");
        check(corners > 300, "正方形的四个角（圆外）照常是键或缝：" + corners + " 个点");
        check(KeyFingers.pick(KEYS, whole, holes, 98, 45) == null, "按在 A 被圆盖住的那一块上：不算 A");
        check(KeyFingers.pick(KEYS, whole, holes, 92, 37).id == A, "正方形左上角（圆外）还在 A 上：是 A（以前方形的洞会吞掉）");
        check(KeyFingers.pick(KEYS, whole, holes, 85, 45).id == A, "洞外一点的 A 照样是 A");
        check(KeyFingers.pick(KEYS, whole, new TouchRouter.Region[0], 98, 45).id == A, "没有洞时那一块是 A");
        // 让给三个鼠标键的空位（矩形的洞，KeyboardPanel 报成 REGION_MOUSE_BUTTON）：整块不算任何键，旁边的照旧
        TouchRouter.Region[] gapHole = {new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, 0, 100, 55, 200, 100)};
        check(KeyFingers.pick(KEYS, whole, gapHole, 150, 70) == null && KeyFingers.pick(KEYS, whole, gapHole, 101, 99) == null,
                "空位里（连底下 S 那一截）不算任何键");
        check(KeyFingers.pick(KEYS, whole, gapHole, 50, 70).id == S && KeyFingers.pick(KEYS, whole, gapHole, 150, 53).id == B,
                "空位外照旧：S 的左半截、空位上面缝里靠上的 B");

        // ── 第一根按在缝里，第二根按在键上：各自按下、各自松开（以前第二根被吞、缝里的键等全部抬起才松） ──
        KeyFingers f = new KeyFingers();
        check(f.down(0, pick(105, 30)) == KeyFingers.NONE, "手指 0 按在缝里（分给 A）");
        check(f.down(1, pick(150, 30)) == KeyFingers.NONE, "手指 1 按在 B 上");
        check(f.up(1) == B, "手指 1 抬起松开 B");
        check(f.up(0) == A, "手指 0 抬起松开 A（不用等别的手指）");
        check(f.cancel().length == 0, "都抬起了，没有按着的");

        // ── 第一根按在 A，第二根按在缝里：A 的手指先抬起，A 马上松开（以前 A 一直按着） ──
        f.down(3, pick(50, 30));
        f.down(4, pick(115, 30));
        check(f.up(3) == A, "手指 3 抬起松开 A");
        check(f.up(4) == B, "缝里的手指 4 抬起松开 B");

        // ── 快速轮打：前一个还没抬起，后一个已经按下，抬起顺序和按下顺序不同 ──
        f.down(0, A);
        f.down(1, B);
        f.down(2, S);
        check(f.up(1) == B, "中间那根先抬起，只松它的");
        check(f.up(2) == S, "再抬起后按的");
        check(f.up(0) == A, "最后抬起最早按的");

        // ── 取消：全部松开 ──
        f.down(5, A);
        f.down(6, S);
        check(Arrays.equals(f.cancel(), new int[]{A, S}), "ACTION_CANCEL 松开所有按着的键");
        check(f.up(5) == KeyFingers.NONE, "取消后再收到抬起什么都不松");

        // ── 没按着键的手指抬起 / 编号越界 ──
        check(f.up(7) == KeyFingers.NONE, "没按着键的手指抬起什么都不松");
        check(f.down(32, A) == KeyFingers.NONE && f.up(32) == KeyFingers.NONE, "编号越界不记");
        check(f.down(-1, A) == KeyFingers.NONE && f.cancel().length == 0, "负的编号不记");

        // ── 同一根手指没等到抬起又按下：返回旧的键，让调用方先松开 ──
        f.down(2, A);
        check(f.down(2, B) == A, "顶掉的旧键交回调用方松开");
        check(f.up(2) == B, "抬起松开新的键");

        // ── 键换了下标 / 有的不显示了（换层、分裂 / 合拢、⋯ 键、让位、重建底行，2026-11-01）：还显示着的照旧按着、换成新下标，消失的交回去当取消松开 ──
        KeyFingers g = new KeyFingers();
        g.down(1, 0);   // 旧下标 0（比如功能行的 Shift）→ 新下标 3
        g.down(4, 2);   // 旧下标 2（比如字母层的 q）→ 消失
        g.down(6, 5);   // 旧下标 5（比如底行的合并键）→ 新下标 5
        g.down(9, 1);   // 旧下标 1 → 消失
        int[] gone = g.remap(new int[]{3, -1, -1, 0, 1, 5});
        check(Arrays.equals(gone, new int[]{2, 1}), "消失了的键按手指编号交回旧下标（调用方当取消松开）：" + Arrays.toString(gone));
        check(g.up(1) == 3, "还显示着的键：抬起时返回新下标（照旧按着）");
        check(g.up(6) == 5, "下标没变的照旧");
        check(g.up(4) == KeyFingers.NONE && g.up(9) == KeyFingers.NONE, "消失了的手指之后抬起什么都不松");
        g.down(2, 7);
        check(g.remap(new int[]{0, 1}).length == 1 && g.up(2) == KeyFingers.NONE, "旧下标超出映射：当消失");
        g.down(3, 0);
        check(g.remap(new int[0]).length == 1, "空映射：全部消失");
        check(g.cancel().length == 0, "都清掉了");
        check(g.remap(new int[]{1, 0}).length == 0, "没按着的手指：remap 什么都不做");
        g.down(0, 0);
        g.down(1, 1);
        check(g.remap(new int[]{1, 0}).length == 0 && g.up(0) == 1 && g.up(1) == 0, "两个键对调位置：各自跟着自己的键");

        System.err.println("键盘多指：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    private static int pick(float x, float y) {
        TouchRouter.Region r = KeyFingers.pick(KEYS, AREA, x, y);
        return r == null ? -1 : r.id;
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
