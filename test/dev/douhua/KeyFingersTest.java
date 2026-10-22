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
