package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 每个键 / 鼠标键至少按住 MinHold.MIN_MS（2026-11-04）：按得短的松开晚到 40 毫秒、按得长的照旧；留着的松开不和后面的输入叠在一起
 * （新按下、滚轮先把它们发掉，光标移动先把鼠标键发掉）；每个按下都配松开；真 GestureEngine 的双指轻点出 m2、40 毫秒后 m0。
 * 随机序列旁边跑一份模型查这几条。
 */
public final class MinHoldTest {
    private static int checks, fails;

    public static void main(String[] args) {
        quickClickHeld();
        longClickUntouched();
        moveFlushesMouse();
        rePress();
        chordThenKey();
        modifierThenClick();
        modifierThenWheel();
        keyMoveKeepsKey();
        consumer();
        systemKeys();
        flushAndReset();
        twoFingerTap();
        engineClick();
        random();
        System.out.println("最短按住：" + checks + " 项检查，" + fails + " 项失败");
        if (fails > 0) System.exit(1);
    }

    // ═══════════ 假的输出 + 时钟 ═══════════

    static final class Out implements MinHold.Sink {
        final List<String> log = new ArrayList<>();
        long now;
        long due = -1;
        int mouse, mods, consumer, system;
        final int[] keys = new int[6];
        int keyCount;

        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            mouse = buttons;
            log.add(now + " m" + buttons + (dx != 0 || dy != 0 ? " d" : "") + (wheel != 0 || pan != 0 ? " w" : ""));
        }

        @Override
        public void keyboard(byte[] r) {
            mods = r[0] & 0xFF;
            keyCount = 0;
            StringBuilder b = new StringBuilder(now + " k" + Integer.toHexString(mods));
            for (int i = 2; i < 8; i++) {
                if (r[i] != 0) {
                    keys[keyCount++] = r[i] & 0xFF;
                    b.append(',').append(Integer.toHexString(r[i] & 0xFF));
                }
            }
            log.add(b.toString());
        }

        @Override
        public void consumer(int usage) {
            consumer = usage;
            log.add(now + " c" + Integer.toHexString(usage));
        }

        @Override
        public void system(int usage) {
            system = usage;
            log.add(now + " s" + Integer.toHexString(usage));
        }

        @Override
        public void schedule(long delayMs) {
            check(delayMs >= 1 && delayMs <= MinHold.MIN_MS, "排的定时器在 1–40 毫秒里（" + delayMs + "）");
            due = now + delayMs;
        }

        /** 时间走到 t，中间到点的定时器照到点的时刻调。 */
        void advance(MinHold h, long t) {
            while (due >= 0 && due <= t) {
                now = due;
                due = -1;
                h.onTimer(now);
            }
            now = t;
        }
    }

    static byte[] kb(int mods, int... keys) {
        byte[] r = new byte[8];
        r[0] = (byte) mods;
        for (int i = 0; i < keys.length; i++) r[2 + i] = (byte) keys[i];
        return r;
    }

    // ═══════════ 定向 ═══════════

    static void quickClickHeld() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        o.now = 1000;
        h.mouse(2, 0, 0, 0, 0, 1000);
        h.mouse(0, 0, 0, 0, 0, 1000);
        eq(o.log, list("1000 m2"), "右键按下马上发、同一刻的松开先留着");
        check(h.pending() && h.mouseButtons() == 2, "留着：主机那头还按着右键");
        o.advance(h, 1039);
        eq(o.log, list("1000 m2"), "39 毫秒还没松");
        o.advance(h, 1100);
        eq(o.log, list("1000 m2", "1040 m0"), "40 毫秒整松开");
        check(!h.pending(), "没留着的了");
    }

    static void longClickUntouched() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        o.now = 1000;
        h.mouse(1, 0, 0, 0, 0, 1000);
        o.advance(h, 1220);
        h.mouse(1, 3, 4, 0, 0, 1220);
        h.mouse(0, 0, 0, 0, 0, 1220);
        eq(o.log, list("1000 m1", "1220 m1 d", "1220 m0"), "按够了的松开马上发，移动照旧带着按着的键");
        // 正好 40 毫秒
        o = new Out();
        h = new MinHold(o);
        h.keyboard(kb(0, 4), 0);
        h.keyboard(kb(0), 40);
        eq(o.log, list("0 k0,4", "0 k0"), "按了正好 40 毫秒：松开马上发");
    }

    static void moveFlushesMouse() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.mouse(2, 0, 0, 0, 0, 0);
        o.now = 5;
        h.mouse(0, 0, 0, 0, 0, 5);
        o.now = 10;
        h.mouse(0, 7, 0, 0, 0, 10);
        eq(o.log, list("0 m2", "10 m0", "10 m0 d"), "右键留着时光标一动：先松右键再移动（不成右键拖动）");
        o.advance(h, 100);
        check(o.log.size() == 3, "之后定时器到点什么都不多发");
    }

    static void rePress() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.mouse(1, 0, 0, 0, 0, 0);
        h.mouse(0, 0, 0, 0, 0, 1);
        o.now = 20;
        h.mouse(1, 0, 0, 0, 0, 20);
        eq(o.log, list("0 m1", "20 m0", "20 m1"), "留着的左键又按下：先松再按（主机看得到两下）");
        h.mouse(0, 0, 0, 0, 0, 25);
        o.advance(h, 200);
        eq(o.log, list("0 m1", "20 m0", "20 m1", "60 m0"), "第二下从它按下起算 40 毫秒");
        // 键盘同一个键
        o = new Out();
        h = new MinHold(o);
        h.keyboard(kb(0, 0x0F), 0);
        h.keyboard(kb(0), 5);
        o.now = 15;
        h.keyboard(kb(0, 0x0F), 15);
        h.keyboard(kb(0), 20);
        o.advance(h, 200);
        eq(o.log, list("0 k0,f", "15 k0", "15 k0,f", "55 k0"), "快打 ll：两下分得开，第二下按住 40 毫秒");
    }

    static void chordThenKey() {
        // "@" = Shift + 2（TouchRouter：先按 Shift、再按 2；松开先 2 再 Shift），马上打 a
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.keyboard(kb(2), 0);
        h.keyboard(kb(2, 0x1F), 0);
        h.keyboard(kb(2), 10);
        h.keyboard(kb(0), 10);
        o.now = 25;
        h.keyboard(kb(0, 4), 25);
        eq(o.log, list("0 k2", "0 k2,1f", "25 k0", "25 k0,4"), "@ 按得短、25 毫秒后打 a：先松 Shift 和 2，a 不带 Shift");
        h.keyboard(kb(0), 30);
        o.advance(h, 200);
        eq(o.log, list("0 k2", "0 k2,1f", "25 k0", "25 k0,4", "65 k0"), "a 按住 40 毫秒");
        // 没有后来的键：@ 两样一起按满 40 毫秒
        o = new Out();
        h = new MinHold(o);
        h.keyboard(kb(2), 0);
        h.keyboard(kb(2, 0x1F), 0);
        h.keyboard(kb(2), 3);
        h.keyboard(kb(0), 3);
        o.advance(h, 200);
        eq(o.log, list("0 k2", "0 k2,1f", "40 k0"), "@ 自己按满 40 毫秒，Shift 和 2 一起松");
    }

    static void modifierThenClick() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.keyboard(kb(1), 0);
        h.keyboard(kb(0), 10);
        o.now = 20;
        h.mouse(1, 0, 0, 0, 0, 20);
        eq(o.log, list("0 k1", "20 k0", "20 m1"), "Ctrl 点一下马上点鼠标：先松 Ctrl（不成 Ctrl+点击）");
    }

    static void modifierThenWheel() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.keyboard(kb(1), 0);
        h.keyboard(kb(0), 10);
        o.now = 20;
        h.mouse(0, 0, 0, 120, 0, 20);
        eq(o.log, list("0 k1", "20 k0", "20 m0 w"), "Ctrl 点一下马上滚滚轮：先松 Ctrl（不成缩放）");
    }

    static void keyMoveKeepsKey() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.keyboard(kb(0, 4), 0);
        h.keyboard(kb(0), 5);
        o.now = 10;
        h.mouse(0, 3, 0, 0, 0, 10);
        o.advance(h, 100);
        eq(o.log, list("0 k0,4", "10 m0 d", "40 k0"), "另一只手推着光标：按得短的键照样按满 40 毫秒");
    }

    static void consumer() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.consumer(0xE9, 0);
        h.consumer(0, 0);
        o.advance(h, 100);
        eq(o.log, list("0 ce9", "40 c0"), "媒体键按一下：40 毫秒后松");
        // 留着时换了码：先松、再按新的
        o = new Out();
        h = new MinHold(o);
        h.consumer(0xE9, 0);
        h.consumer(0, 1);
        o.now = 10;
        h.consumer(0xEA, 10);
        h.consumer(0, 12);
        o.advance(h, 100);
        eq(o.log, list("0 ce9", "10 c0", "10 cea", "50 c0"), "音量 + 留着时按音量 −：先松、再按，各自按满");
        // 按着的码没变、又报一次（照旧发）
        o = new Out();
        h = new MinHold(o);
        h.consumer(0xE9, 0);
        o.advance(h, 100);
        h.consumer(0xE9, 100);
        o.advance(h, 200);
        h.consumer(0, 200);
        eq(o.log, list("0 ce9", "100 ce9", "200 c0"), "按够了照旧");
    }

    static void systemKeys() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.system(0x82, 0);
        h.system(0, 0);
        o.now = 10;
        h.keyboard(kb(0, 4), 10);
        eq(o.log, list("0 s82", "10 s0", "10 k0,4"), "系统键留着时按了别的键：先松");
    }

    static void flushAndReset() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        h.mouse(4, 0, 0, 0, 0, 0);
        h.mouse(0, 0, 0, 0, 0, 0);
        h.keyboard(kb(2, 4), 0);
        h.keyboard(kb(0), 0);
        h.consumer(0xCD, 0);
        h.consumer(0, 0);
        h.system(0xA9, 0);
        h.system(0, 0);
        o.now = 5;
        h.flushNow();
        check(o.mouse == 0 && o.mods == 0 && o.keyCount == 0 && o.consumer == 0 && o.system == 0 && !h.pending(), "flushNow：留着的全松");
        int n = o.log.size();
        o.advance(h, 200);
        check(o.log.size() == n, "flushNow 以后定时器到点什么都不多发");
        // reset：什么都不发，之后从头算
        o = new Out();
        h = new MinHold(o);
        h.mouse(1, 0, 0, 0, 0, 0);
        h.reset();
        check(!h.pending() && h.mouseButtons() == 0, "reset：忘掉");
        o.now = 10;
        h.mouse(1, 0, 0, 0, 0, 10);
        eq(o.log, list("0 m1", "10 m1"), "reset 以后的按下照发");
    }

    /** 真 GestureEngine 的双指轻点（原来 m2、m0 同一刻）经 MinHold：m2，40 毫秒后 m0。 */
    static void twoFingerTap() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        long[] t = {0};
        GestureEngine e = new GestureEngine(new EngineOut(h, t), new GestureEngine.Config());
        t[0] = 1000;
        o.now = 1000;
        e.down(0, 50, 50, 1000);
        t[0] = o.now = 1010;
        e.down(1, 80, 50, 1010);
        t[0] = o.now = 1080;
        e.up(1, 1080, false);
        t[0] = o.now = 1090;
        e.up(0, 1090, false);
        eq(o.log, list("1090 m2"), "双指轻点：抬起时按下右键、松开先留着");
        o.advance(h, 1200);
        eq(o.log, list("1090 m2", "1130 m0"), "40 毫秒后松开右键");
    }

    /** 点击类动作（engine.click，CMD_CLICK / 按钮条的"单击"）同样按满 40 毫秒。 */
    static void engineClick() {
        Out o = new Out();
        MinHold h = new MinHold(o);
        long[] t = {500};
        o.now = 500;
        GestureEngine e = new GestureEngine(new EngineOut(h, t), new GestureEngine.Config());
        e.click(Proto.BUTTON_LEFT);
        o.advance(h, 1000);
        eq(o.log, list("500 m1", "540 m0"), "动作里的单击：按住 40 毫秒");
    }

    static final class EngineOut implements GestureEngine.Output {
        final MinHold h;
        final long[] t;

        EngineOut(MinHold h, long[] t) {
            this.h = h;
            this.t = t;
        }

        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            h.mouse(buttons, dx, dy, wheel, pan, t[0]);
        }

        @Override
        public void zoom(int steps) {
        }

        @Override
        public void zoomEnd() {
        }

        @Override
        public void gesture(int fingers, int kind) {
        }

        @Override
        public void haptic(int kind) {
        }

        @Override
        public void schedule(long delayMs) {
        }

        @Override
        public void cancelSchedule() {
        }
    }

    // ═══════════ 随机序列 ═══════════

    /**
     * 随机按下 / 松开鼠标键、键、修饰键、媒体键，夹着移动、滚轮、时间流逝。查：主机那头按着的 ⊇ 来源按着的；
     * 每次主机那头松开一个东西，要么离它按下已满 40 毫秒、要么正好有新按下 / 滚轮（鼠标键还有移动）来了；
     * 新按下 / 滚轮发出去的那一刻主机那头没有留着的（鼠标键：移动那一刻也没有）；最后全松、定时器跑完主机那头什么都不按着。
     */
    static void random() {
        Random rnd = new Random(20261104L);
        int rounds = 400, events = 0, forced = 0, natural = 0;
        for (int round = 0; round < rounds; round++) {
            Out o = new Out();
            MinHold h = new MinHold(o);
            int mouseWant = 0, modWant = 0, consumerWant = 0;
            List<Integer> keyWant = new ArrayList<>();
            long now = 1000;
            for (int step = 0; step < 120; step++) {
                now += rnd.nextInt(4) == 0 ? rnd.nextInt(80) : rnd.nextInt(12);
                o.advance(h, now);
                int r = rnd.nextInt(10);
                if (r < 3) {
                    int b = 1 << rnd.nextInt(3);
                    mouseWant ^= b;
                    boolean move = rnd.nextInt(3) == 0, wheel = rnd.nextInt(6) == 0;
                    h.mouse(mouseWant, move ? 3 : 0, 0, wheel ? 120 : 0, 0, now);
                } else if (r < 4) {
                    h.mouse(mouseWant, rnd.nextInt(5) - 2, rnd.nextInt(5) - 2, 0, 0, now);
                } else if (r < 7) {
                    if (!keyWant.isEmpty() && rnd.nextBoolean()) keyWant.remove(rnd.nextInt(keyWant.size()));
                    else if (keyWant.size() < 6) {
                        int u = 4 + rnd.nextInt(8);
                        if (!keyWant.contains(u)) keyWant.add(u);
                    }
                    int[] k = new int[keyWant.size()];
                    for (int i = 0; i < k.length; i++) k[i] = keyWant.get(i);
                    h.keyboard(kb(modWant, k), now);
                } else if (r < 8) {
                    modWant ^= 1 << rnd.nextInt(4);
                    int[] k = new int[keyWant.size()];
                    for (int i = 0; i < k.length; i++) k[i] = keyWant.get(i);
                    h.keyboard(kb(modWant, k), now);
                } else if (r < 9) {
                    consumerWant = consumerWant == 0 ? 0xE9 + rnd.nextInt(2) : (rnd.nextBoolean() ? 0 : 0xE9 + rnd.nextInt(2));
                    h.consumer(consumerWant, now);
                } else {
                    h.mouse(mouseWant, 0, 0, 120, 0, now);
                }
                events++;
                // 主机那头 ⊇ 来源
                check((o.mouse & mouseWant) == mouseWant, "鼠标：来源按着的主机那头一定按着");
                check((o.mods & modWant) == modWant, "修饰键：来源按着的主机那头一定按着");
                for (int u : keyWant) check(contains(o.keys, o.keyCount, u), "键：来源按着的主机那头一定按着");
                if (consumerWant != 0) check(o.consumer == consumerWant, "媒体键：来源按着的就是主机那头的");
            }
            // 全松
            now += 5;
            mouseWant = modWant = consumerWant = 0;
            keyWant.clear();
            o.now = now;
            h.mouse(0, 0, 0, 0, 0, now);
            h.keyboard(kb(0), now);
            h.consumer(0, now);
            o.advance(h, now + 100);
            check(o.mouse == 0 && o.mods == 0 && o.keyCount == 0 && o.consumer == 0 && !h.pending(), "全松、定时器跑完：主机那头什么都不按着");
            // 回放日志查时长：每个东西从主机那头按下到松开，要么 ≥ 40，要么松开那一刻同时有新按下 / 滚轮（鼠标键还有移动）
            int[] res = durations(o.log);
            forced += res[0];
            natural += res[1];
        }
        check(events > 40000, "随机序列：" + rounds + " 组 × 120 步（" + events + "）");
        check(natural > 1000 && forced > 100, "短的按下两种都出现过：按满 40 的 " + natural + "，被后来的输入提前松的 " + forced);
    }

    /** 回放主机那头收到的：返回 {被后来的输入提前松的次数, 按满 40 毫秒的次数}；不合规矩的记失败。 */
    static int[] durations(List<String> log) {
        long[] mouseAt = new long[8], modAt = new long[8], keyAt = new long[256], consumerAt = {0};
        int mouse = 0, mods = 0, consumer = 0;
        int[] keys = new int[0];
        int forced = 0, natural = 0;
        for (int i = 0; i < log.size(); i++) {
            String[] f = log.get(i).split(" ");
            long t = Long.parseLong(f[0]);
            String what = f[1];
            // 这一刻紧接着有没有新的输入（同一时刻、下一条是按下 / 滚轮 / 移动）
            String next = i + 1 < log.size() ? log.get(i + 1) : "";
            boolean nextSameTime = next.startsWith(t + " ");
            if (what.startsWith("m")) {
                int b = Integer.parseInt(what.substring(1));
                for (int k = 0; k < 8; k++) {
                    int bit = 1 << k;
                    if ((b & bit) != 0 && (mouse & bit) == 0) mouseAt[k] = t;
                    if ((b & bit) == 0 && (mouse & bit) != 0) {
                        if (t - mouseAt[k] >= MinHold.MIN_MS) natural++;
                        else if (nextSameTime) forced++;
                        else check(false, "鼠标键按了 " + (t - mouseAt[k]) + " 毫秒就松、后面又没有新输入：" + log.subList(Math.max(0, i - 3), Math.min(log.size(), i + 2)));
                    }
                }
                mouse = b;
            } else if (what.startsWith("k")) {
                String[] parts = what.substring(1).split(",");
                int m = Integer.parseInt(parts[0], 16);
                int[] k2 = new int[parts.length - 1];
                for (int j = 1; j < parts.length; j++) k2[j - 1] = Integer.parseInt(parts[j], 16);
                for (int k = 0; k < 8; k++) {
                    int bit = 1 << k;
                    if ((m & bit) != 0 && (mods & bit) == 0) modAt[k] = t;
                    if ((m & bit) == 0 && (mods & bit) != 0) {
                        if (t - modAt[k] >= MinHold.MIN_MS) natural++;
                        else if (nextSameTime) forced++;
                        else check(false, "修饰键按了 " + (t - modAt[k]) + " 毫秒就松：" + log.subList(Math.max(0, i - 3), Math.min(log.size(), i + 2)));
                    }
                }
                for (int u : k2) if (!contains(keys, keys.length, u)) keyAt[u] = t;
                for (int u : keys) {
                    if (!contains(k2, k2.length, u)) {
                        if (t - keyAt[u] >= MinHold.MIN_MS) natural++;
                        else if (nextSameTime) forced++;
                        else check(false, "键 " + u + " 按了 " + (t - keyAt[u]) + " 毫秒就松：" + log.subList(Math.max(0, i - 3), Math.min(log.size(), i + 2)));
                    }
                }
                mods = m;
                keys = k2;
            } else if (what.startsWith("c")) {
                int c = Integer.parseInt(what.substring(1), 16);
                if (c != consumer) {
                    if (consumer != 0) {
                        if (t - consumerAt[0] >= MinHold.MIN_MS) natural++;
                        else if (nextSameTime || c != 0) forced++;
                        else check(false, "媒体键按了 " + (t - consumerAt[0]) + " 毫秒就松");
                    }
                    if (c != 0) consumerAt[0] = t;
                }
                consumer = c;
            }
        }
        return new int[]{forced, natural};
    }

    // ═══════════ 小工具 ═══════════

    static boolean contains(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) {
            if (a[i] == v) return true;
        }
        return false;
    }

    static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    static void eq(Object got, Object want, String what) {
        check(got.equals(want), what + "：得到 " + got + "，应为 " + want);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            if (fails <= 30) System.out.println("✗ " + what);
        }
    }
}
