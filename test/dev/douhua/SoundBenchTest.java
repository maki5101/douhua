package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置 → 外观 → 声音那一节的纯 Java 部分（SoundBench，声音第一批第 ④ 步）：不依赖 Android 的部分（tools/test.sh 直接跑）。
 * <ul>
 * <li>试听台的一排键：键 id、类别（Shift / 退格是大键、空格、回车）、主次键帽和 KeyRows.functionKey 一致，经 SoundEvents 出的就是这个键。</li>
 * <li>候选区每个槽点一下 / 连打交出去的声音：正在试的那一半经 SoundVary 落在这个槽、另一半落在配对的槽；
 *     真 SoundMixer 上"指定候选试听"和"设置里选了这个候选再正常放"逐个采样一样（试听的就是选了以后用的）；不出声的候选不出声。</li>
 * <li>连打：每秒 7 下、2 秒、14 下（离"打得快"的门槛有余量：按块对齐、抖几毫秒照样一下都不算快）；按键轮着换的几个字母在三排上、都落在普通键那个槽。</li>
 * <li>"打乱顺序"：是排列、同一个种子一样、各种排法都出现；不打乱时就是原来的顺序。</li>
 * <li>点一项套装放的那一小段：按时间排好、不到 1.5 秒、按下都配了松开；两套在真混音器上每个按下都出声。</li>
 * <li>那一小块触控板：轻点（容差、时长）、划（每 step 一格、按路长算、来回也算）、被取消、第二次按下从头来。</li>
 * <li>微调滑块的范围和混音器 / 变化规则一致。</li>
 * </ul>
 * 只证明"按下去放什么、震什么"照规则走；界面（SettingsActivity）、好不好听、三星上多晚出声都要真机。
 */
public final class SoundBenchTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        benchKeys();
        hitSlots();
        hitSameAsChosen();
        silentCandidates();
        burst();
        haptics();
        shuffle();
        demo();
        demoAudible();
        pad();
        ranges();
        System.err.println("声音的试听台 / 候选区：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 试听台的一排键 ═══════════

    static void benchKeys() {
        check(SoundBench.KEYS.length == 7 && SoundBench.KEY_LABELS.length == 7 && SoundBench.KEY_WEIGHTS.length == 7, "试听台 7 个键，字和宽度对得上");
        int[] cls = {SoundVary.CLASS_BIG, SoundVary.CLASS_NORMAL, SoundVary.CLASS_NORMAL, SoundVary.CLASS_NORMAL, SoundVary.CLASS_BIG,
                SoundVary.CLASS_SPACE, SoundVary.CLASS_ENTER};
        for (int i = 0; i < SoundBench.KEYS.length; i++) {
            int id = SoundBench.KEYS[i];
            check(SoundVary.keyClass(id) == cls[i], "试听台第 " + i + " 个键（" + SoundBench.KEY_LABELS[i] + "）的类别 " + SoundVary.keyClass(id));
            boolean fn = KeyRows.functionKey(new KeyRows.Key(id, SoundBench.KEY_LABELS[i], null, 1, false), false);
            check(SoundBench.keyFn(i) == fn, "试听台第 " + i + " 个键的主次键帽同 KeyRows.functionKey");
            check(SoundBench.KEY_WEIGHTS[i] > 0, "宽度为正");
            // 经事件层：按下 / 松开出的就是这个键（同面板自己收触摸那条路）
            List<String> plays = new ArrayList<>();
            SoundEvents ev = new SoundEvents((s, k, d) -> plays.add(s + ":" + k + ":" + d));
            ev.configure(true, true, true, true, false, true);
            ev.keyDown(id);
            ev.keyUp(id, false);
            ev.keyUp(id, true);
            check(plays.equals(Arrays.asList(SoundBank.KEY_DOWN + ":" + id + ":0", SoundBank.KEY_UP + ":" + id + ":0")),
                    "试听台的键经事件层：按下、松开各一声，被取消的不出声 " + plays);
        }
        check(SoundBench.keyFn(0) && !SoundBench.keyFn(1) && SoundBench.keyFn(4) && !SoundBench.keyFn(5) && SoundBench.keyFn(6),
                "⇧ ⌫ ↵ 是功能键、字母和空格是普通键");
    }

    // ═══════════ 候选区 ═══════════

    /** 每个槽点一下 / 连打的每一下：正在试的那一半落在这个槽，配对的那一半落在对应的槽。 */
    static void hitSlots() {
        for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
            for (int burst = 0; burst < 2; burst++) {
                for (int n = 0; n < (burst == 1 ? SoundBench.BURST_HITS : 1); n++) {
                    SoundBench.Shot[] shots = SoundBench.hit(slot, n, burst == 1);
                    check(shots.length > 0, "槽 " + SoundBank.SLOT_IDS[slot] + " 有东西放");
                    int tried = 0;
                    for (SoundBench.Shot s : shots) {
                        SoundVary v = new SoundVary(1);
                        SoundVary.Shot r = v.resolve(s.sound, s.key, 0, 48000);
                        check(r != null, "试听的声音解得出来 " + SoundBank.SLOT_IDS[slot]);
                        if (r == null) continue;
                        check(s.delayMs >= 0 && s.delayMs <= SoundMixer.MAX_DELAY_MS, "延后在请求写得下的范围里");
                        if (s.candidate) {
                            tried++;
                            check(r.slot == slot, "正在试的那一半落在这个槽：" + SoundBank.SLOT_IDS[slot] + " → " + SoundBank.SLOT_IDS[r.slot]);
                        } else {
                            check(r.slot != slot, "配对的那一半用现在选的、落在别的槽 " + SoundBank.SLOT_IDS[slot]);
                            check(burst == 1, "点一下只放这个槽（不带配对的） " + SoundBank.SLOT_IDS[slot]);
                        }
                    }
                    check(tried >= 1, "每一下至少有一个是正在试的 " + SoundBank.SLOT_IDS[slot]);
                    // 第一个声音从这一块开头放（时间不随机、不往后挪）
                    check(shots[0].delayMs == 0, "第一个不延后 " + SoundBank.SLOT_IDS[slot]);
                }
            }
        }
        // 连打的每一下是完整的一下：按键 = 按下 + 70 ms 后松开，鼠标 = 按下 + 45 ms 后松开，触控板一直是一对
        SoundBench.Shot[] k = SoundBench.hit(SoundBank.SLOT_KEY, 3, true);
        check(k.length == 2 && k[0].sound == SoundBank.KEY_DOWN && k[1].sound == SoundBank.KEY_UP && k[1].delayMs == SoundBench.KEY_HOLD_MS
                && k[0].key == k[1].key && k[0].candidate && !k[1].candidate, "连打按键：按下（试的）+ 70 ms 后松开（选的）");
        SoundBench.Shot[] u = SoundBench.hit(SoundBank.SLOT_KEY_UP, 3, true);
        check(u.length == 2 && !u[0].candidate && u[1].candidate && u[1].sound == SoundBank.KEY_UP, "连打键松开：按下用选的、松开用试的");
        SoundBench.Shot[] one = SoundBench.hit(SoundBank.SLOT_KEY_UP, 0, false);
        check(one.length == 1 && one[0].sound == SoundBank.KEY_UP && one[0].candidate, "点一下键松开：只放松开（听得清）");
        SoundBench.Shot[] m = SoundBench.hit(SoundBank.SLOT_MOUSE_DOWN, 0, true);
        check(m.length == 2 && m[1].delayMs == SoundEvents.CLICK_GAP_MS && m[0].key == Proto.BUTTON_LEFT, "连打鼠标：按下 + 45 ms 后松开、左键");
        for (boolean b : new boolean[]{false, true}) {
            SoundBench.Shot[] p = SoundBench.hit(SoundBank.SLOT_PAD, 0, b);
            check(p.length == 2 && p[0].sound == SoundBank.PAD_DOWN && p[1].sound == SoundBank.PAD_UP && p[1].delayMs == SoundEvents.CLICK_GAP_MS
                    && p[0].candidate && p[1].candidate, "触控板一直是一对（同事件层的轻点）");
        }
        check(SoundBench.hit(-1, 0, false).length == 0 && SoundBench.hit(SoundBank.SLOTS, 0, true).length == 0, "认不出的槽什么都不放");
    }

    /**
     * 真混音器上：指定候选试听（Sounds.audition 交的请求）和"设置里选了这个候选、正常放"逐个采样一样——试听的就是选了以后主界面用的。
     * 两个混音器同一个种子、同一份声音。
     */
    static void hitSameAsChosen() {
        int sr = 48000;
        int compared = 0, audible = 0;
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            SoundBank.Bank bank = SoundBank.build(sr, pack, true, null);
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
                for (int c = 0; c < cs.length; c++) {
                    for (int burst = 0; burst < 2; burst++) {
                        SoundMixer a = new SoundMixer(77);
                        SoundMixer b = new SoundMixer(77);
                        a.setBank(bank);
                        b.setBank(bank);
                        int[] choice = new int[SoundBank.SLOTS];
                        choice[slot] = c;
                        b.setChoices(choice);
                        for (SoundBench.Shot s : SoundBench.hit(slot, 1, burst == 1)) {
                            a.post(s.candidate ? SoundMixer.request(s.sound, s.key, c, 0, s.delayMs) : SoundMixer.request(s.sound, s.key, -1, 0, s.delayMs));
                            b.post(SoundMixer.request(s.sound, s.key, -1, 0, s.delayMs));
                        }
                        float[] oa = new float[sr / 4], ob = new float[sr / 4];
                        a.render(oa, oa.length);
                        b.render(ob, ob.length);
                        check(Arrays.equals(oa, ob), "试听 = 选了以后放的：" + SoundBank.PACK_IDS[pack] + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code
                                + (burst == 1 ? "（连打）" : ""));
                        compared++;
                        if (peak(oa) > 0) audible++;
                    }
                }
            }
        }
        check(compared > 50 && audible > compared / 2, "比了 " + compared + " 组，出声的 " + audible);
    }

    /** 不出声的候选（轻的松开 B、木与枝的松开 A）：点一下什么都听不到（也不打断别的）。别的候选点一下都出声。 */
    static void silentCandidates() {
        int sr = 44100;
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            SoundBank.Bank bank = SoundBank.build(sr, pack, true, null);
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
                for (int c = 0; c < cs.length; c++) {
                    SoundMixer m = new SoundMixer(5);
                    m.setBank(bank);
                    for (SoundBench.Shot s : SoundBench.hit(slot, 0, false)) {
                        if (s.candidate) m.post(SoundMixer.request(s.sound, s.key, c, 0, s.delayMs));
                    }
                    float[] o = new float[sr / 4];
                    m.render(o, o.length);
                    boolean silent = cs[c].recipe == null;
                    check(silent == (peak(o) == 0), SoundBank.PACK_IDS[pack] + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code
                            + (silent ? " 不出声" : " 出声") + "（峰值 " + peak(o) + "）");
                }
            }
        }
    }

    static void burst() {
        check(SoundBench.BURST_HITS == 14 && SoundBench.BURST_GAP_MS == 142, "连打每秒 7 下、2 秒、14 下");
        check(Math.abs(SoundBench.BURST_GAP_MS * SoundBench.BURST_HITS - SoundBench.BURST_MS) < SoundBench.BURST_GAP_MS, "14 × 142 ms ≈ 2 秒");
        // 离"打得快"的门槛有余量：间隔抖 8 ms 也在 SoundVary.FAST_MS 以上（审查发现：每秒 8 下正好 125 ms，三成的下忽轻忽重）
        check(SoundBench.BURST_GAP_MS - 8 >= SoundVary.FAST_MS, "连打的间隔减去 8 ms 抖动还 ≥ 打得快的门槛");
        java.util.Random rnd = new java.util.Random(1);
        int fast = 0;
        for (int run = 0; run < 200; run++) {
            SoundVary v = new SoundVary(run);
            for (int n = 0; n < SoundBench.BURST_HITS; n++) {
                long at = ((long) n * SoundBench.BURST_GAP_MS + rnd.nextInt(9)) * 48 / 192 * 192;   // Handler 抖 0–8 ms、再按 4 ms 的混音块对齐
                SoundBench.Shot s = SoundBench.hit(SoundBank.SLOT_KEY, n, true)[0];
                if (v.resolve(s.sound, s.key, at, 48000).shortTail) fast++;
            }
        }
        check(fast == 0, "连打 200 串、按块对齐抖着放：一下都不算打得快（" + fast + "）");
        Set<Integer> rows = new HashSet<>();
        Set<Integer> keys = new HashSet<>();
        for (int n = 0; n < SoundBench.BURST_HITS; n++) {
            SoundBench.Shot s = SoundBench.hit(SoundBank.SLOT_KEY, n, true)[0];
            check(SoundVary.keyClass(s.key) == SoundVary.CLASS_NORMAL, "连打的字母是普通键");
            rows.add(SoundVary.row(s.key));
            keys.add(s.key);
            if (n > 0) check(s.key != SoundBench.hit(SoundBank.SLOT_KEY, n - 1, true)[0].key, "连打不连着按同一个键");
            SoundBench.Shot big = SoundBench.hit(SoundBank.SLOT_BIG, n, true)[0];
            check(SoundVary.slotOf(SoundVary.keyClass(big.key)) == SoundBank.SLOT_BIG, "连打大键都落在大键那个槽");
        }
        check(rows.size() >= 3 && keys.size() >= 6, "连打的字母在三排上（行的音高不一样）：排 " + rows + "，键 " + keys.size());
        check(SoundBench.hit(SoundBank.SLOT_KEY, -5, true).length == 2 && SoundBench.hit(SoundBank.SLOT_KEY, 1 << 30, true).length == 2, "第几下越界也不越界");
    }

    static void haptics() {
        int[] want = new int[SoundBank.SLOTS];
        want[SoundBank.SLOT_KEY] = SoundBench.HAPTIC_KEY;
        want[SoundBank.SLOT_BIG] = SoundBench.HAPTIC_KEY;
        want[SoundBank.SLOT_SPACE] = SoundBench.HAPTIC_KEY;
        want[SoundBank.SLOT_ENTER] = SoundBench.HAPTIC_KEY;
        want[SoundBank.SLOT_KEY_UP] = SoundBench.HAPTIC_NONE;
        want[SoundBank.SLOT_MOUSE_DOWN] = GestureEngine.HAPTIC_CLICK;
        want[SoundBank.SLOT_MOUSE_UP] = SoundBench.HAPTIC_NONE;
        want[SoundBank.SLOT_PAD] = GestureEngine.HAPTIC_CLICK;
        want[SoundBank.SLOT_SCROLL] = GestureEngine.HAPTIC_TICK;
        want[SoundBank.SLOT_CATCH] = GestureEngine.HAPTIC_SPIN_CATCH;
        for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
            check(SoundBench.haptic(slot) == want[slot], "槽 " + SoundBank.SLOT_IDS[slot] + " 震 " + SoundBench.haptic(slot) + "（同主界面同一种操作）");
        }
        check(SoundBench.haptic(-1) == SoundBench.HAPTIC_NONE && SoundBench.haptic(99) == SoundBench.HAPTIC_NONE, "认不出的槽不震");
        check(SoundBench.HAPTIC_NONE < 0 && SoundBench.HAPTIC_KEY < 0 && SoundBench.HAPTIC_NONE != SoundBench.HAPTIC_KEY, "两个特殊值不和 HAPTIC_* 撞");
    }

    static void shuffle() {
        for (int n = 0; n <= 5; n++) {
            int[] id = SoundBench.order(n, false, 123);
            for (int i = 0; i < n; i++) check(id[i] == i, "不打乱就是原来的顺序");
            for (long seed = 0; seed < 200; seed++) {
                int[] p = SoundBench.order(n, true, seed);
                check(isPerm(p, n), "打乱后还是一个排列 n=" + n);
                check(Arrays.equals(p, SoundBench.shuffled(n, seed)), "同一个种子一样");
            }
        }
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 2000; seed++) seen.add(Arrays.toString(SoundBench.shuffled(4, seed * 31 + 7)));
        check(seen.size() == 24, "4 个候选的 24 种排法都出现：" + seen.size());
        int same = 0;
        for (long seed = 0; seed < 2000; seed++) if (SoundBench.shuffled(3, seed)[0] == 0) same++;
        check(same > 500 && same < 850, "第一个按钮背后是 A 的大约三分之一：" + same + " / 2000");
        check(SoundBench.code(0).equals("A") && SoundBench.code(3).equals("D"), "按钮写 A–D");
        check(SoundBench.shuffled(-1, 1).length == 0, "负数不闪退");
    }

    static void demo() {
        int last = -1;
        List<Integer> down = new ArrayList<>();
        for (int[] d : SoundBench.DEMO) {
            check(d.length == 3, "一项三个数");
            check(d[0] >= last, "按时间排好");
            last = d[0];
            check(d[1] >= 1 && d[1] < SoundBank.SOUNDS && d[1] != SoundBank.GAMEPAD_DOWN && d[1] != SoundBank.GAMEPAD_UP
                    && d[1] != SoundBank.GAMEPAD_TICK, "声音 id 认得、不放手柄的");
            if (d[1] == SoundBank.KEY_DOWN) down.add(d[2]);
            if (d[1] == SoundBank.KEY_UP) check(down.remove((Integer) d[2]), "松开的键之前按下过 " + Integer.toHexString(d[2]));
        }
        check(down.isEmpty(), "按下的都配了松开");
        check(SoundBench.DEMO[0][0] == 0 && last < 1500, "马上开始、不到 1.5 秒：" + last);
        boolean pad = false, scroll = false, space = false, enter = false;
        for (int[] d : SoundBench.DEMO) {
            pad |= d[1] == SoundBank.PAD_DOWN;
            scroll |= d[1] == SoundBank.SCROLL;
            space |= d[1] == SoundBank.KEY_DOWN && d[2] == 0x2C;
            enter |= d[1] == SoundBank.KEY_DOWN && d[2] == 0x28;
        }
        check(pad && scroll && space && enter, "这一小段有字母、空格、回车、触控板轻点、滚轮");
    }

    /** 两套声音照默认候选在真混音器上放这一小段：每个按下（键、触控板、滚轮）开始的那几毫秒都有声音。 */
    static void demoAudible() {
        int sr = 48000;
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            SoundMixer m = new SoundMixer(9);
            m.setBank(SoundBank.build(sr, pack, false, null));
            int block = 96;   // 2 ms 一块
            int total = (SoundBench.DEMO[SoundBench.DEMO.length - 1][0] + 300) * sr / 1000;
            float[] out = new float[block];
            int next = 0;
            for (int f = 0; f < total; f += block) {
                int ms = f * 1000 / sr;
                List<Integer> started = new ArrayList<>();
                while (next < SoundBench.DEMO.length && SoundBench.DEMO[next][0] <= ms) {
                    int[] d = SoundBench.DEMO[next++];
                    m.post(SoundMixer.request(d[1], d[2]));
                    if (d[1] != SoundBank.KEY_UP && d[1] != SoundBank.PAD_UP) started.add(d[1]);
                }
                m.render(out, block);
                if (!started.isEmpty()) check(peak(out) > 0.01, SoundBank.PACK_IDS[pack] + " 在 " + ms + " ms 开始的 " + started + " 马上有声音（峰值 " + peak(out) + "）");
            }
            check(next == SoundBench.DEMO.length, "整段都交出去了");
        }
    }

    // ═══════════ 那一小块触控板 ═══════════

    static void pad() {
        float slop = 12, step = 40;
        SoundBench.Pad p = new SoundBench.Pad(slop, step, 250);
        // 轻点：没挪、没超时
        p.down(100, 100, 1000);
        check(p.up(100, 100, 1100), "轻点");
        p.down(100, 100, 1000);
        check(p.move(105, 108, 1050) == 0 && !p.scrolling(), "容差里挪不算划");
        check(p.up(106, 109, 1250), "容差里、正好 250 ms 也算轻点");
        p.down(100, 100, 1000);
        check(!p.up(100, 100, 1251), "超过 250 ms 不算轻点");
        p.down(100, 100, 1000);
        p.move(100, 113, 1020);
        check(p.scrolling(), "挪出容差就是划");
        check(!p.up(100, 100, 1040), "划过了回到原处抬起也不算轻点");
        p.down(100, 100, 1000);
        check(!p.up(100, 115, 1040), "没报挪动、抬起时已经在容差外：不算轻点");
        // 划：按路长每 40 一格，挪出容差那一刻从按下点算起
        p.down(0, 0, 0);
        int ticks = 0;
        for (int y = 1; y <= 100; y++) ticks += p.move(0, y, y);
        check(ticks == 2, "划 100 = 2 格（40 一格）：" + ticks);
        for (int y = 99; y >= 60; y--) ticks += p.move(0, y, 200 - y);
        check(ticks == 3, "往回划 40 再一格（按路长算）：" + ticks);
        check(!p.up(0, 60, 400), "划完抬起不是轻点");
        // 一下挪很远：一次给出几格
        p.down(0, 0, 0);
        check(p.move(0, 400, 10) == 10, "一下挪 400 = 10 格");
        // 斜着划按路长
        p.down(0, 0, 0);
        check(p.move(30, 40, 10) == 1 && p.move(60, 80, 20) == 1, "斜着 50 + 50 = 100 → 先 1 格再 1 格");
        // 被取消：什么都不算；没按下时什么都不算
        p.down(0, 0, 0);
        p.cancel();
        check(p.move(0, 200, 10) == 0 && !p.up(0, 0, 20), "被取消以后挪、抬起都不算");
        SoundBench.Pad q = new SoundBench.Pad(12, 40, 250);
        check(q.move(1, 1, 1) == 0 && !q.up(1, 1, 2), "没按下就挪、抬起：什么都没有");
        // 第二次按下从头来（上一次的余数、"划过"都清掉）
        p.down(0, 0, 0);
        p.move(0, 70, 10);   // 1 格，余 30
        p.down(0, 0, 100);
        check(!p.scrolling() && p.move(0, 13, 110) == 0 && p.move(0, 39, 120) == 0 && p.move(0, 41, 130) == 1, "第二次按下余数清零");
        // step 很小也不会除零；slop 负数当 0
        SoundBench.Pad z = new SoundBench.Pad(-5, 0, 250);
        z.down(0, 0, 0);
        check(z.move(0, 3, 1) == 3, "step 至少 1 像素、slop 负数当 0");
        // 随机走 400 步：格数一直正好是 floor(路长 / step)（容差 0：从第一下挪动就算）
        SoundBench.Pad e = new SoundBench.Pad(0, 25, 250);
        e.down(0, 0, 0);
        int sum = 0;
        double len = 0;
        float px = 0, py = 0;
        SoundSynth.Rng r2 = new SoundSynth.Rng(3);
        for (int i = 0; i < 400; i++) {
            float nx = px + (float) (r2.signed() * 9), ny = py + (float) (r2.signed() * 9);
            sum += e.move(nx, ny, i);
            len += Math.hypot(nx - px, ny - py);
            px = nx;
            py = ny;
            check(sum == (int) Math.floor(len / 25 + 1e-6) || sum == (int) Math.floor(len / 25 - 1e-6), "第 " + i + " 下：格数 = 路长 / 25 取整（" + sum + " vs " + len + "）");
        }
    }

    static void ranges() {
        check(SoundBench.OFFSET_MIN_DB == -20 && SoundBench.OFFSET_MAX_DB == 6, "音量偏移 −20..+6 dB（同 SoundMixer.setOffsetDb 截的范围）");
        check(SoundBench.RANDOM_MAX_PCT == 300 && SoundBench.RANDOM_MAX_PCT % SoundBench.RANDOM_STEP_PCT == 0, "随机幅度最多 300%（同 SoundVary 最多 3 倍），步长整除");
        check(SoundBench.DELAY_MAX_MS == 40 && SoundBench.DELAY_MAX_MS % SoundBench.DELAY_STEP_MS == 0, "震动延后 0–40 ms，步长整除");
        check(SoundBench.clampDelay(-3) == 0 && SoundBench.clampDelay(17) == 17 && SoundBench.clampDelay(99) == 40, "震动延后存坏了截到范围里");
        // 随机幅度 0 时同一个请求放两次一模一样的音高和响度（变化规则真的跟着滑块）
        SoundVary v = new SoundVary(1);
        v.setRandomScale(0);
        SoundVary.Shot a = v.resolve(SoundBank.KEY_DOWN, 0x04, 0, 48000);
        double c1 = a.cents, d1 = a.db;
        SoundVary.Shot b = v.resolve(SoundBank.KEY_DOWN, 0x04, 48000 * 5, 48000);
        check(b.cents == c1 && b.db == d1, "随机幅度 0：音高、响度不随机");
        // 音量滑块 0 是静音、100 是 0 dB
        check(SoundMixer.volumeGain(0) == 0 && SoundMixer.volumeGain(100) == 1, "音量滑块 0 静音、100 不衰减");
    }

    // ═══════════ 小工具 ═══════════

    static boolean isPerm(int[] p, int n) {
        if (p.length != n) return false;
        boolean[] seen = new boolean[n];
        for (int x : p) {
            if (x < 0 || x >= n || seen[x]) return false;
            seen[x] = true;
        }
        return true;
    }

    static float peak(float[] x) {
        float m = 0;
        for (float v : x) m = Math.max(m, Math.abs(v));
        return m;
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
