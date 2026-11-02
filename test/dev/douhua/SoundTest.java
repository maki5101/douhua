package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 声音第一批里不依赖 Android 的部分（tools/test.sh 直接跑）：
 * 合成（SoundSynth：每套 × 每槽 × 每个候选 × 子声音 × 变体，在 44.1 / 48 / 96 kHz 下查时长、峰值、开头没有静音、2.5–5.5 kHz 不出尖峰、300 Hz 以下切掉了、
 * 同一个种子结果一样）、清单（SoundBank：每类 2–4 个候选、电平的先后、只算默认 / 全算、建两次一样）、
 * 变化规则（SoundVary：类别、行、随机的范围、变体不连着重复、打得快变轻变短但一定出声、滚轮合并、互斥的组）、
 * 混音（SoundMixer：请求打包、下一块第 0 帧就出声、同键互斥淡掉、最多 8 个、抢最老的、软限幅、确定性、音量、候选、不出声的候选不打断按下）、
 * 无锁队列（SoundQueue：几个线程同时写、一个线程读，不丢不重）。
 * 这些只证明代码照设计跑，证明不了好不好听、三星上延迟多少、从哪个喇叭出。
 */
public final class SoundTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        synthAll(48000);
        synthAll(44100);
        synthAll(96000);
        synthReproducible();
        finishFilters();
        catalog();
        levels();
        loudness(44100);
        loudness(48000);
        loudness(96000);
        bankBuild();
        keyClasses();
        rows();
        randomRanges();
        variants();
        fastTyping();
        scrollMerge();
        groups();
        requestPacking();
        mixerStartsNow();
        mixerSameKeyFades();
        mixerMaxVoices();
        softClipShape();
        mixerNeverClips();
        mixerDeterministic();
        mixerVolume();
        mixerCandidates();
        mixerSilentCandidate();
        mixerShortTail();
        mixerIdle();
        mixerBankSwap();
        mixerDelayed();
        queueSingle();
        queueConcurrent();
        speed();
        System.err.println("声音（合成 / 混音 / 变化规则 / 队列）：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 合成 ═══════════

    /** 每个槽最长多少毫秒（−60 dB 剪尾以后的长度）、主体（最后一个高于峰值 −20 dB 的采样）最长多少。照方案 2.6 的表。 */
    private static double maxLenMs(int slot) {
        switch (slot) {
            case SoundBank.SLOT_SPACE: case SoundBank.SLOT_ENTER: return 200;
            case SoundBank.SLOT_KEY_UP: return 60;
            case SoundBank.SLOT_MOUSE_DOWN: case SoundBank.SLOT_MOUSE_UP: return 40;
            case SoundBank.SLOT_PAD: return 40;
            case SoundBank.SLOT_SCROLL: return 15;
            case SoundBank.SLOT_CATCH: return 40;
            default: return 150;
        }
    }

    private static double maxBodyMs(int slot) {
        switch (slot) {
            case SoundBank.SLOT_SPACE: case SoundBank.SLOT_ENTER: return 80;
            case SoundBank.SLOT_KEY_UP: return 20;
            case SoundBank.SLOT_MOUSE_DOWN: case SoundBank.SLOT_MOUSE_UP: return 15;
            case SoundBank.SLOT_PAD: return 20;
            case SoundBank.SLOT_SCROLL: return 5;
            case SoundBank.SLOT_CATCH: return 15;
            default: return 60;
        }
    }

    /**
     * 每套 × 每槽 × 每个候选 × 子声音 × 变体：峰值 1、没有 NaN、开头 1 ms 内就到峰值的 5%（开头没有静音）、长度和主体在表里的范围内、
     * 2.5–5.5 kHz 最高的地方比 300–2500 Hz 最高的地方低至少 6 dB（不出尖峰）、150 Hz 以下比主峰低至少 20 dB、200 Hz 以下的能量不到 2%（切掉了；
     * 200–300 Hz 是 4 阶高通的过渡带）；500 Hz 以下的能量不超过 {@link #MAX_BELOW_500}（手机喇叭 500–1000 Hz 以下衰减很快，大半能量落在那里的声音
     * 在手机上几乎听不见——审查时"像 MacBook 触控板"的三个候选、竹筒有两个是 87–94%）；树枝"第一下最响"：头 2 ms 里就到峰值的 {@link #TWIG_HEAD}
     * （后面的小裂叠在纤维共振上会比第一下还响、起音变软，审查时 5–7 ms 才到峰值）。
     */
    private static void synthAll(int sr) {
        int sounds = 0;
        double worstBand = -999, worstLow = -999, worstLowEnergy = 0, worstBelow500 = 0, worstTwigHead = 1;
        String worstBandAt = "", worstBelow500At = "";
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
                for (int c = 0; c < cs.length; c++) {
                    if (cs[c].recipe == null) continue;
                    for (int sub = 0; sub < SoundBank.subs(slot); sub++) {
                        SoundSynth.Recipe rec = SoundBank.subRecipe(slot, sub, cs[c].recipe);
                        for (int v = 0; v < SoundBank.VARIANTS; v++) {
                            float[] x = rec.render(sr, SoundBank.seed(pack, slot, c, sub, v));
                            String what = SoundBank.PACK_IDS[pack] + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code + " 子 " + sub + " 变体 " + v + " @" + sr;
                            sounds++;
                            double peak = 0;
                            boolean finite = true;
                            for (float f : x) {
                                if (Float.isNaN(f) || Float.isInfinite(f)) finite = false;
                                peak = Math.max(peak, Math.abs(f));
                            }
                            check(finite, what + "：没有 NaN / 无穷");
                            check(Math.abs(peak - 1) < 1e-4, what + "：峰值 1（" + peak + "）");
                            int lead = 0;
                            while (lead < x.length && Math.abs(x[lead]) < 0.05) lead++;
                            check(lead <= sr / 1000, what + "：开头 1 ms 内就有声音（" + lead + " 个采样）");
                            check(Math.abs(x[0]) > 0, what + "：第一个采样不是 0");
                            double lenMs = x.length * 1000.0 / sr;
                            int body = x.length;
                            while (body > 0 && Math.abs(x[body - 1]) < 0.1) body--;
                            double bodyMs = body * 1000.0 / sr;
                            check(lenMs >= 2 && lenMs <= maxLenMs(slot), what + "：长度 " + fmt(lenMs) + " ms 在 2–" + maxLenMs(slot) + " 里");
                            check(bodyMs <= maxBodyMs(slot), what + "：主体 " + fmt(bodyMs) + " ms ≤ " + maxBodyMs(slot));
                            check(Math.abs(x[x.length - 1]) < 0.01, what + "：结尾淡出到 0 附近");
                            double[] m = magnitude(x, sr);
                            int n = (m.length - 1) * 2;
                            double main = maxIn(m, sr, n, 300, 2500), band = maxIn(m, sr, n, 2500, 5500), low = maxIn(m, sr, n, 1, 150);
                            double bandDb = 20 * Math.log10(band / main), lowDb = 20 * Math.log10(low / main);
                            check(bandDb <= -6, what + "：2.5–5.5 kHz 比主峰低 " + fmt(-bandDb) + " dB（要 ≥ 6）");
                            check(lowDb <= -20, what + "：150 Hz 以下比主峰低 " + fmt(-lowDb) + " dB（要 ≥ 20）");
                            double eLow = 0, eAll = 0;
                            for (int k = 1; k < m.length; k++) {
                                double p = m[k] * m[k];
                                eAll += p;
                                if (k * (double) sr / n < 200) eLow += p;
                            }
                            check(eLow / eAll < 0.02, what + "：200 Hz 以下的能量 " + fmt(100 * eLow / eAll) + "%（要 < 2%）");
                            double e500 = 0;
                            for (int k = 1; k < m.length; k++) if (k * (double) sr / n < 500) e500 += m[k] * m[k];
                            check(e500 / eAll <= MAX_BELOW_500, what + "：500 Hz 以下的能量 " + fmt(100 * e500 / eAll) + "%（要 ≤ " + fmt(100 * MAX_BELOW_500) + "%）");
                            if (e500 / eAll > worstBelow500) {
                                worstBelow500 = e500 / eAll;
                                worstBelow500At = what;
                            }
                            if (rec.type == SoundSynth.TWIG) {
                                double head = 0;
                                for (int i = 0; i < Math.min(x.length, sr / 500); i++) head = Math.max(head, Math.abs(x[i]));
                                check(head >= TWIG_HEAD, what + "：树枝第一下最响（头 2 ms 里到峰值的 " + fmt(100 * head) + "%）");
                                worstTwigHead = Math.min(worstTwigHead, head);
                            }
                            if (bandDb > worstBand) {
                                worstBand = bandDb;
                                worstBandAt = what;
                            }
                            worstLow = Math.max(worstLow, lowDb);
                            worstLowEnergy = Math.max(worstLowEnergy, eLow / eAll);
                        }
                    }
                }
            }
        }
        System.err.println("  合成 @" + sr + "：" + sounds + " 个声音；2.5–5.5k 最多 " + fmt(worstBand) + " dB（" + worstBandAt + "）、150 Hz 以下最多 "
                + fmt(worstLow) + " dB、200 Hz 以下能量最多 " + fmt(100 * worstLowEnergy) + "%、500 Hz 以下能量最多 " + fmt(100 * worstBelow500)
                + "%（" + worstBelow500At + "）、树枝头 2 ms 最少到峰值的 " + fmt(100 * worstTwigHead) + "%");
    }

    /** 每个声音 500 Hz 以下的能量最多占多少。 */
    static final double MAX_BELOW_500 = 0.4;
    /** 树枝：头 2 ms 里最少到峰值的多少。 */
    static final double TWIG_HEAD = 0.8;

    /** 同一个配方、同一个种子：逐个采样一样；种子不同：不一样；随机数自己算，和平台无关（钉几个值）。 */
    private static void synthReproducible() {
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                for (SoundBank.Candidate c : SoundBank.candidates(pack, slot)) {
                    if (c.recipe == null) continue;
                    float[] a = c.recipe.render(48000, 7), b = c.recipe.render(48000, 7), d = c.recipe.render(48000, 8);
                    check(Arrays.equals(a, b), SoundBank.PACK_IDS[pack] + "/" + SoundBank.SLOT_IDS[slot] + "/" + c.code + "：同一个种子逐个一样");
                    check(!Arrays.equals(a, d), SoundBank.PACK_IDS[pack] + "/" + SoundBank.SLOT_IDS[slot] + "/" + c.code + "：种子不同就不一样");
                }
            }
        }
        // SplitMix64 的头几个值（种子 0）：照算法写死的，换了实现就对不上
        SoundSynth.Rng r = new SoundSynth.Rng(0);
        check(r.next() == 0xE220A8397B1DCDAFL, "随机数：SplitMix64 第 1 个值");
        check(r.next() == 0x6E789E6AA1B965F4L, "随机数：SplitMix64 第 2 个值");
        SoundSynth.Rng u = new SoundSynth.Rng(42);
        for (int i = 0; i < 10000; i++) {
            double x = u.uniform(), s = u.signed();
            int k = u.below(7);
            if (x < 0 || x >= 1 || s < -1 || s >= 1 || k < 0 || k >= 7) {
                check(false, "随机数：范围不对 " + x + " " + s + " " + k);
                break;
            }
        }
        check(true, "随机数：uniform / signed / below 的范围");
    }

    /** 收尾的滤波本身：100 Hz、150 Hz 的正弦压掉 ≥ 35 / 24 dB（4 阶巴特沃斯 300 Hz），1 kHz 只动一点（凹的裙边 ≤ 2 dB），3.7 kHz 被凹压下去。 */
    private static void finishFilters() {
        int sr = 48000;
        double[] f = {100, 150, 1000, 3700};
        double[] gain = new double[f.length];
        for (int i = 0; i < f.length; i++) {
            double[] x = new double[sr];
            for (int k = 0; k < x.length; k++) x[k] = Math.sin(2 * Math.PI * f[i] * k / sr);
            SoundSynth.highpass2(x, sr, SoundSynth.HIGHPASS_HZ, 0.5411961001461970);
            SoundSynth.highpass2(x, sr, SoundSynth.HIGHPASS_HZ, 1.3065629648763766);
            SoundSynth.peaking(x, sr, SoundSynth.DIP_HZ, SoundSynth.DIP_DB, SoundSynth.DIP_Q);
            double p = 0;
            for (int k = sr / 2; k < sr; k++) p = Math.max(p, Math.abs(x[k]));
            gain[i] = 20 * Math.log10(p);
        }
        check(gain[0] <= -35, "收尾：100 Hz 压掉 " + fmt(-gain[0]) + " dB（≥ 35）");
        check(gain[1] <= -23.5, "收尾：150 Hz 压掉 " + fmt(-gain[1]) + " dB（≥ 24）");
        check(Math.abs(gain[2]) <= 2, "收尾：1 kHz 几乎不动（" + fmt(gain[2]) + " dB，凹的裙边）");
        check(Math.abs(gain[3] - SoundSynth.DIP_DB) <= 0.5, "收尾：3.7 kHz 压下去 " + fmt(-gain[3]) + " dB");
    }

    // ═══════════ 清单 ═══════════

    /** 每套每个槽 2–4 个候选；代号是 A B C D 依次；第 0 个（默认）都算得出来（除了说好不出声的）；触控板是"像 MacBook"的那一种、两套共用；"关"没有候选。 */
    private static void catalog() {
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
                String what = SoundBank.PACK_IDS[pack] + "/" + SoundBank.SLOT_IDS[slot];
                check(cs.length >= 2 && cs.length <= 4, what + "：2–4 个候选（" + cs.length + "）");
                for (int i = 0; i < cs.length; i++) check(cs[i].code.equals(String.valueOf((char) ('A' + i))), what + "：第 " + i + " 个叫 " + (char) ('A' + i));
                if (slot != SoundBank.SLOT_KEY_UP) check(cs[0].recipe != null, what + "：默认的出声");
            }
            for (SoundBank.Candidate c : SoundBank.candidates(pack, SoundBank.SLOT_PAD)) {
                check(c.recipe.type == SoundSynth.TAPTIC || c.recipe.type == SoundSynth.TAPTIC_LOW, SoundBank.PACK_IDS[pack] + "/pad：" + c.code + " 是像 MacBook 触控板的那种");
            }
        }
        check(SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOT_PAD) == SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_PAD), "触控板：两套用同一组");
        check(SoundBank.candidates(SoundBank.PACK_OFF, SoundBank.SLOT_KEY).length == 0, "关：没有候选");
        check(SoundBank.candidates(SoundBank.PACK_LIGHT, -1).length == 0 && SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOTS).length == 0, "越界的槽：没有候选");
        // 木与枝：字母木块、空格树枝、回车竹筒、松开默认不出声
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_KEY)[0].recipe.type == SoundSynth.WOOD, "木与枝：字母是木块");
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_KEY)[1].recipe.b > 0.5, "木与枝：字母的 B 是木鱼");
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_SPACE)[0].recipe.type == SoundSynth.TWIG, "木与枝：空格是树枝");
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_ENTER)[0].recipe.type == SoundSynth.BAMBOO, "木与枝：回车是竹筒");
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_KEY_UP)[0].recipe == null, "木与枝：松开默认不出声");
        check(SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOT_KEY)[0].recipe.type == SoundSynth.TAP, "轻：普通键是闷嗒");
        // 存的字
        check(SoundBank.PACK_IDS.length == SoundBank.PACKS && SoundBank.SLOT_IDS.length == SoundBank.SLOTS, "存的字和常量一样多");
        check(new HashSet<>(Arrays.asList(SoundBank.PACK_IDS)).size() == SoundBank.PACKS, "套装的字不重复");
        check(new HashSet<>(Arrays.asList(SoundBank.SLOT_IDS)).size() == SoundBank.SLOTS, "槽的字不重复");
        for (int i = 0; i < SoundBank.PACKS; i++) check(SoundBank.packOf(SoundBank.PACK_IDS[i]) == i, "套装的字认得回来：" + SoundBank.PACK_IDS[i]);
        check(SoundBank.packOf("piano") == SoundBank.PACK_OFF && SoundBank.packOf(null) == SoundBank.PACK_OFF, "认不出的套装当关");
        // 候选区的小字：数字和单位，不出声的是空的
        check(SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOT_KEY)[0].desc().matches("\\d+ Hz · [\\d.]+ ms"), "候选的小字：" + SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOT_KEY)[0].desc());
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_SPACE)[0].desc().matches("\\d+ Hz · [\\d.]+ ms · ×\\d+"), "树枝的小字：" + SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_SPACE)[0].desc());
        check(SoundBank.candidates(SoundBank.PACK_WOOD, SoundBank.SLOT_KEY_UP)[0].desc().isEmpty(), "不出声的候选：小字是空的");
        // 子声音的配方：右键高一点、中键低一点闷一点、触控板松开高一点短一点
        SoundSynth.Recipe base = SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOT_MOUSE_DOWN)[1].recipe;
        check(SoundBank.subRecipe(SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_LEFT, base) == base, "鼠标左键就是候选本身");
        SoundSynth.Recipe right = SoundBank.subRecipe(SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_RIGHT, base);
        SoundSynth.Recipe mid = SoundBank.subRecipe(SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_MIDDLE, base);
        check(right.f > base.f && right.a > base.a, "鼠标右键：弹片和外壳都高一点");
        check(mid.f < base.f && mid.lp < base.lp && mid.t60 > base.t60, "鼠标中键：低一点、闷一点、长一点");
        SoundSynth.Recipe pad = SoundBank.candidates(SoundBank.PACK_LIGHT, SoundBank.SLOT_PAD)[0].recipe;
        SoundSynth.Recipe padUp = SoundBank.subRecipe(SoundBank.SLOT_PAD, SoundBank.SUB_PAD_UP, pad);
        check(padUp.f > pad.f && padUp.t60 < pad.t60 && padUp.b > pad.b, "触控板松开：高一点、短一点（那一下咔也跟着高）");
        check(SoundBank.subs(SoundBank.SLOT_MOUSE_DOWN) == 3 && SoundBank.subs(SoundBank.SLOT_PAD) == 2 && SoundBank.subs(SoundBank.SLOT_KEY) == 1, "子声音个数");
    }

    /** 电平的先后（方案 2.6）：松开比按下轻 6–12 dB、鼠标比普通键轻约 2 dB、触控板比鼠标再轻、滚轮轻 10–14 dB、轻那套整体更轻。 */
    private static void levels() {
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            String p = SoundBank.PACK_IDS[pack];
            double key = SoundBank.levelDb(pack, SoundBank.SLOT_KEY, 0);
            double up = SoundBank.levelDb(pack, SoundBank.SLOT_KEY_UP, 0) - key;
            double mouse = SoundBank.levelDb(pack, SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_LEFT) - key;
            double mouseUp = SoundBank.levelDb(pack, SoundBank.SLOT_MOUSE_UP, SoundBank.SUB_LEFT) - SoundBank.levelDb(pack, SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_LEFT);
            double padDown = SoundBank.levelDb(pack, SoundBank.SLOT_PAD, SoundBank.SUB_PAD_DOWN) - key;
            double padUp = SoundBank.levelDb(pack, SoundBank.SLOT_PAD, SoundBank.SUB_PAD_UP) - SoundBank.levelDb(pack, SoundBank.SLOT_PAD, SoundBank.SUB_PAD_DOWN);
            double scroll = SoundBank.levelDb(pack, SoundBank.SLOT_SCROLL, 0) - key;
            check(up <= -6 && up >= -12, p + "：松开比按下轻 " + fmt(-up) + " dB（6–12）");
            check(mouse <= -1.5 && mouse >= -3, p + "：鼠标比普通键轻 " + fmt(-mouse) + " dB（约 2）");
            check(mouseUp < 0, p + "：鼠标松开比按下轻");
            check(padDown < mouse - 3, p + "：触控板轻点比鼠标键再轻 " + fmt(mouse - padDown) + " dB");
            check(padUp < 0, p + "：触控板松开比按下轻");
            check(scroll <= -10 && scroll >= -14, p + "：滚轮比普通键轻 " + fmt(-scroll) + " dB（10–14）");
            check(SoundBank.levelDb(pack, SoundBank.SLOT_CATCH, 0) < SoundBank.levelDb(pack, SoundBank.SLOT_SCROLL, 0), p + "：刹住比滚轮还轻");
            check(SoundBank.levelDb(pack, SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_MIDDLE) <= SoundBank.levelDb(pack, SoundBank.SLOT_MOUSE_DOWN, SoundBank.SUB_LEFT), p + "：中键不比左键响");
        }
        check(SoundBank.levelDb(SoundBank.PACK_LIGHT, SoundBank.SLOT_KEY, 0) < SoundBank.levelDb(SoundBank.PACK_WOOD, SoundBank.SLOT_KEY, 0), "轻那套整体更轻");
    }

    /**
     * 电平表说的是响度、不是峰值（审查发现：以前按峰值归一，几十毫秒以内的声音听起来多响看总能量，木与枝的鼠标比字母轻 7 dB、触控板只比鼠标轻 0.4 dB、
     * 轻那套的滚轮轻 17.6 dB）。拿真算好的波形量（这里另写一份：Bank.gain × 采样，过同一个粗手机喇叭，整段的总能量、不是 Bank 用的 50 ms 窗，四个变体平均）：
     * 每套、每个候选、每个子声音都是 参考 + 电平表（±0.6 dB）；默认的候选之间：鼠标比普通键轻约 2 dB、触控板比鼠标再轻 3–5 dB、松开比按下轻 6–12 dB、
     * 滚轮轻 10–14 dB、刹住比滚轮还轻；单个声音（音量 100%、不随机）的峰值都在软限幅拐点以下（只有叠起来才进限幅）。
     */
    private static void loudness(int sr) {
        double worstOff = 0, worstPeak = 0;
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            String p = SoundBank.PACK_IDS[pack] + " @" + sr;
            SoundBank.Bank b = SoundBank.build(sr, pack, true, null);
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
                for (int c = 0; c < cs.length; c++) {
                    if (cs[c].recipe == null) continue;
                    for (int sub = 0; sub < SoundBank.subs(slot); sub++) {
                        String what = p + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code + " 子 " + sub;
                        double want = SoundBank.LOUDNESS_REF_DB + SoundBank.levelDb(pack, slot, sub), got = speakerDb(b, slot, c, sub, sr);
                        check(Math.abs(got - want) <= 0.6, what + "：响度 " + fmt(got) + " dB，电平表要 " + fmt(want));
                        worstOff = Math.max(worstOff, Math.abs(got - want));
                        double peak = 0;
                        for (int v = 0; v < SoundBank.VARIANTS; v++) for (float f : b.sample(slot, c, sub, v)) peak = Math.max(peak, Math.abs(f));
                        peak *= b.gain(slot, c, sub);
                        check(peak <= SoundMixer.KNEE, what + "：单个声音的峰值 " + fmt(peak) + " 在软限幅拐点 " + SoundMixer.KNEE + " 以下");
                        worstPeak = Math.max(worstPeak, peak);
                    }
                }
            }
            double key = speakerDb(b, SoundBank.SLOT_KEY, 0, 0, sr);
            double mouse = speakerDb(b, SoundBank.SLOT_MOUSE_DOWN, 0, SoundBank.SUB_LEFT, sr) - key;
            double pad = speakerDb(b, SoundBank.SLOT_PAD, 0, SoundBank.SUB_PAD_DOWN, sr) - key;
            int upCand = SoundBank.candidates(pack, SoundBank.SLOT_KEY_UP)[0].recipe != null ? 0 : 1;   // 木与枝的松开默认不出声：量出声的那个
            double up = speakerDb(b, SoundBank.SLOT_KEY_UP, upCand, 0, sr) - key;
            double mouseUp = speakerDb(b, SoundBank.SLOT_MOUSE_UP, 0, SoundBank.SUB_LEFT, sr) - key - mouse;
            double padUp = speakerDb(b, SoundBank.SLOT_PAD, 0, SoundBank.SUB_PAD_UP, sr) - key - pad;
            double scroll = speakerDb(b, SoundBank.SLOT_SCROLL, 0, 0, sr) - key;
            double caught = speakerDb(b, SoundBank.SLOT_CATCH, 0, 0, sr) - key;
            check(mouse <= -1 && mouse >= -3, p + "：鼠标按下比普通键轻 " + fmt(-mouse) + " dB（约 2）");
            check(pad - mouse <= -3 && pad - mouse >= -5, p + "：触控板轻点比鼠标键再轻 " + fmt(mouse - pad) + " dB（约 4）");
            check(up <= -6 && up >= -12, p + "：键松开比按下轻 " + fmt(-up) + " dB（6–12）");
            check(mouseUp <= -4 && padUp <= -4, p + "：鼠标、触控板松开比按下轻（" + fmt(-mouseUp) + " / " + fmt(-padUp) + " dB）");
            check(scroll <= -10 && scroll >= -14, p + "：滚轮一格比普通键轻 " + fmt(-scroll) + " dB（10–14）");
            check(caught < scroll, p + "：刹住比滚轮还轻");
        }
        System.err.println("  响度 @" + sr + "：和电平表最多差 " + fmt(worstOff) + " dB；单个声音的峰值最多 " + fmt(worstPeak));
    }

    /** 放出来的响度（dB）：Bank.gain × 四个变体，各自过粗手机喇叭（SoundBank.SPEAKER_HZ 二阶高通）、整段的总能量，平均。 */
    private static double speakerDb(SoundBank.Bank b, int slot, int cand, int sub, int sr) {
        double e = 0, g = b.gain(slot, cand, sub);
        for (int v = 0; v < SoundBank.VARIANTS; v++) {
            float[] x = b.sample(slot, cand, sub, v);
            double[] y = new double[x.length];
            for (int i = 0; i < x.length; i++) y[i] = x[i] * g;
            SoundSynth.highpass2(y, sr, SoundBank.SPEAKER_HZ, Math.sqrt(0.5));
            double sum = 0;
            for (double d : y) sum += d * d;
            e += sum / sr / SoundBank.VARIANTS;
        }
        return 10 * Math.log10(e);
    }

    /** 建一份：只算默认的 / 全算；"关"是 null；同样的参数建两次逐个一样；选了没算的候选 has() 是假。 */
    private static void bankBuild() {
        check(SoundBank.build(48000, SoundBank.PACK_OFF, true, null) == null, "关：不建");
        check(SoundBank.build(48000, 9, true, null) == null, "认不出的套装：不建");
        for (int pack = 1; pack < SoundBank.PACKS; pack++) {
            String p = SoundBank.PACK_IDS[pack];
            SoundBank.Bank one = SoundBank.build(48000, pack, false, null);
            SoundBank.Bank all = SoundBank.build(48000, pack, true, null);
            SoundBank.Bank again = SoundBank.build(48000, pack, true, null);
            check(one.sampleRate == 48000 && one.pack == pack, p + "：采样率、套装记着");
            check(one.bytes > 0 && all.bytes > one.bytes, p + "：全算的比只算默认的大（" + one.bytes / 1024 + " / " + all.bytes / 1024 + " KB）");
            check(all.bytes < 4L << 20, p + "：全算也不到 4 MB（" + all.bytes / 1024 + " KB）");
            boolean same = true;
            for (int slot = 0; slot < SoundBank.SLOTS; slot++) {
                SoundBank.Candidate[] cs = SoundBank.candidates(pack, slot);
                check(one.candidates(slot) == cs.length && all.candidates(slot) == cs.length, p + "/" + SoundBank.SLOT_IDS[slot] + "：候选个数");
                check(one.has(slot, 0), p + "/" + SoundBank.SLOT_IDS[slot] + "：默认的算了");
                for (int c = 1; c < cs.length; c++) {
                    check(one.has(slot, c) == (cs[c].recipe == null), p + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code + "：只算默认时没算（不出声的算已有）");
                    check(all.has(slot, c), p + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code + "：全算时算了");
                }
                for (int c = 0; c < cs.length; c++) {
                    for (int sub = 0; sub < SoundBank.subs(slot); sub++) {
                        for (int v = 0; v < SoundBank.VARIANTS; v++) {
                            float[] a = all.sample(slot, c, sub, v), b = again.sample(slot, c, sub, v);
                            if (!Arrays.equals(a, b)) same = false;
                            if (cs[c].recipe == null) check(a == null, p + "/" + SoundBank.SLOT_IDS[slot] + "/" + cs[c].code + "：不出声的没有采样");
                        }
                    }
                }
                check(!one.has(slot, cs.length) && !one.has(slot, -1), p + "/" + SoundBank.SLOT_IDS[slot] + "：越界的候选没有");
            }
            check(same, p + "：建两次逐个一样");
            // 只算选了的那个
            int[] choice = new int[SoundBank.SLOTS];
            choice[SoundBank.SLOT_KEY] = 2;
            SoundBank.Bank picked = SoundBank.build(48000, pack, false, choice);
            check(picked.has(SoundBank.SLOT_KEY, 2) && !picked.has(SoundBank.SLOT_KEY, 0), p + "：只算选了的候选");
            check(Arrays.equals(picked.sample(SoundBank.SLOT_KEY, 2, 0, 1), all.sample(SoundBank.SLOT_KEY, 2, 0, 1)), p + "：单算和全算的同一个候选一样");
        }
    }

    // ═══════════ 变化规则 ═══════════

    private static void keyClasses() {
        int[][] cases = {
                {0x2C, SoundVary.CLASS_SPACE}, {0x28, SoundVary.CLASS_ENTER}, {0x58, SoundVary.CLASS_ENTER},
                {0x2A, SoundVary.CLASS_BIG}, {0xE1, SoundVary.CLASS_BIG}, {0xE5, SoundVary.CLASS_BIG},
                {0xE0, SoundVary.CLASS_MOD}, {0xE2, SoundVary.CLASS_MOD}, {0xE3, SoundVary.CLASS_MOD}, {0xE6, SoundVary.CLASS_MOD}, {0xE7, SoundVary.CLASS_MOD},
                {0x3A, SoundVary.CLASS_FUNCTION}, {0x45, SoundVary.CLASS_FUNCTION}, {0x29, SoundVary.CLASS_FUNCTION}, {0x2B, SoundVary.CLASS_FUNCTION},
                {0x39, SoundVary.CLASS_FUNCTION}, {0x4F, SoundVary.CLASS_FUNCTION}, {0x4C, SoundVary.CLASS_FUNCTION}, {0x65, SoundVary.CLASS_FUNCTION},
                {0x04, SoundVary.CLASS_NORMAL}, {0x1D, SoundVary.CLASS_NORMAL}, {0x1E, SoundVary.CLASS_NORMAL}, {0x36, SoundVary.CLASS_NORMAL},
                {0x64, SoundVary.CLASS_NORMAL}, {0x87, SoundVary.CLASS_NORMAL}, {0x85, SoundVary.CLASS_NORMAL}, {0x59, SoundVary.CLASS_NORMAL},
                // 自带修饰键的（"@" = Shift+2、复制 = Ctrl+C）：看物理键
                {0x1F | 2 << 8, SoundVary.CLASS_NORMAL}, {0x06 | 1 << 8, SoundVary.CLASS_NORMAL},
                // 合并键（逗号句号）看点一下的那个、带 Fn 层的看底层、F 行键看底层、Fn 键自己算修饰键
                {Proto.dualKey(0x36, 0x37), SoundVary.CLASS_NORMAL},
                {Proto.fnLayerKey(0x2A, 0x4C), SoundVary.CLASS_BIG},
                {Proto.frowKey(Proto.FROW_CONSUMER, 0x3A, 0xE2), SoundVary.CLASS_FUNCTION},
                {Proto.FN_LOCK_KEY, SoundVary.CLASS_FUNCTION},
                {Proto.FN_KEY, SoundVary.CLASS_MOD}, {Proto.FN_GLOBE_KEY, SoundVary.CLASS_MOD},
                {0, SoundVary.CLASS_NORMAL},
        };
        for (int[] c : cases) check(SoundVary.keyClass(c[0]) == c[1], "类别：键 0x" + Integer.toHexString(c[0]) + " 是 " + c[1] + "（得 " + SoundVary.keyClass(c[0]) + "）");
        check(SoundVary.slotOf(SoundVary.CLASS_SPACE) == SoundBank.SLOT_SPACE && SoundVary.slotOf(SoundVary.CLASS_ENTER) == SoundBank.SLOT_ENTER
                && SoundVary.slotOf(SoundVary.CLASS_BIG) == SoundBank.SLOT_BIG && SoundVary.slotOf(SoundVary.CLASS_NORMAL) == SoundBank.SLOT_KEY
                && SoundVary.slotOf(SoundVary.CLASS_MOD) == SoundBank.SLOT_KEY && SoundVary.slotOf(SoundVary.CLASS_FUNCTION) == SoundBank.SLOT_KEY, "类别 → 槽");
        check(SoundVary.classDb(SoundVary.CLASS_MOD) == -3 && SoundVary.classDb(SoundVary.CLASS_FUNCTION) == -1.5 && SoundVary.classDb(SoundVary.CLASS_NORMAL) == 0, "修饰键轻 3 dB、功能键轻 1.5 dB");
        // 用法码：每种 id 都取回物理键
        check(SoundVary.usage(Proto.dualKey(0x36, 0x37)) == 0x36, "用法码：合并键取点一下的");
        check(SoundVary.usage(Proto.fnLayerKey(0x52, 0x4B)) == 0x52, "用法码：Fn 层取底层");
        check(SoundVary.usage(Proto.frowKey(Proto.FROW_KEY, 0x3B, 0x06 | 1 << 8)) == 0x3B, "用法码：F 行键取底层");
        check(SoundVary.usage(Proto.FN_KEY) == -1, "用法码：Fn 键自己没有");
        check(SoundVary.usage(0x06 | 1 << 8) == 0x06, "用法码：组合键去掉自带修饰键");
    }

    private static void rows() {
        int[][] cases = {
                {0x29, 0}, {0x3A, 0}, {0x45, 0}, {0x46, 0}, {0x68, 0},
                {0x35, 1}, {0x1E, 1}, {0x27, 1}, {0x2D, 1}, {0x2A, 1}, {0x49, 1}, {0x53, 1},
                {0x2B, 2}, {0x14, 2}, {0x13, 2}, {0x2F, 2}, {0x31, 2}, {0x4C, 2}, {0x5F, 2},
                {0x39, 3}, {0x04, 3}, {0x0F, 3}, {0x33, 3}, {0x28, 3}, {0x32, 3}, {0x5C, 3},
                {0xE1, 4}, {0x1D, 4}, {0x10, 4}, {0x38, 4}, {0xE5, 4}, {0x64, 4}, {0x87, 4}, {0x52, 4}, {0x59, 4},
                {0xE0, 5}, {0x2C, 5}, {0xE4, 5}, {0x50, 5}, {0x4F, 5}, {0x62, 5},
                {Proto.FN_KEY, 5},
        };
        for (int[] c : cases) check(SoundVary.row(c[0]) == c[1], "行：0x" + Integer.toHexString(c[0]) + " 在第 " + c[1] + " 排（得 " + SoundVary.row(c[0]) + "）");
        for (int r = 0; r < 5; r++) check(SoundVary.rowCents(r) > SoundVary.rowCents(r + 1), "行的音分：上排比下排高（" + r + "）");
        check(SoundVary.rowCents(3) == 0, "ASDF 那一排不变调");
        check(SoundVary.rowCents(0) <= 40 && SoundVary.rowCents(5) >= -40, "行的音分在 ±40 里");
        check(SoundVary.rowCents(-3) == SoundVary.rowCents(0) && SoundVary.rowCents(99) == SoundVary.rowCents(5), "越界的行截到两头");
    }

    /** 随机的范围：按键 ±20 音分、±1.2 dB（加行和类别）；鼠标 ±15；滚轮 ±10；随机幅度 0 = 一点不随机；2 = 两倍。 */
    private static void randomRanges() {
        SoundVary v = new SoundVary(1);
        double minC = 1e9, maxC = -1e9, minD = 1e9, maxD = -1e9;
        long frame = 0;
        for (int i = 0; i < 5000; i++) {
            frame += 48000;   // 隔一秒一下：不算打得快
            SoundVary.Shot s = v.resolve(SoundBank.KEY_DOWN, 0x04, frame, 48000);
            minC = Math.min(minC, s.cents);
            maxC = Math.max(maxC, s.cents);
            minD = Math.min(minD, s.db);
            maxD = Math.max(maxD, s.db);
            if (s.shortTail) check(false, "隔一秒按：不该有短尾巴");
        }
        check(minC >= -12 && maxC <= 12 && maxC - minC > 20, "按键：音高在 ±12 音分里、也真用到了（" + fmt(minC) + " … " + fmt(maxC) + "）");
        check(minD >= -1.2 && maxD <= 1.2 && maxD - minD > 2, "按键：音量在 ±1.2 dB 里（" + fmt(minD) + " … " + fmt(maxD) + "）");
        // 行 + 类别叠上去
        SoundVary.Shot f1 = v.resolve(SoundBank.KEY_DOWN, 0x3A, frame += 48000, 48000);
        check(f1.cents >= 18 && f1.cents <= 42, "F1：高 30 音分 ± 12（" + fmt(f1.cents) + "）");
        check(f1.db >= -2.7 && f1.db <= -0.3, "F1：功能键轻 1.5 dB ± 1.2（" + fmt(f1.db) + "）");
        SoundVary.Shot ctrl = v.resolve(SoundBank.KEY_DOWN, 0xE0, frame += 48000, 48000);
        check(ctrl.db >= -4.2 && ctrl.db <= -1.8, "Ctrl：修饰键轻 3 dB ± 1.2（" + fmt(ctrl.db) + "）");
        // 鼠标、滚轮的范围
        double mouseMax = 0, tickMax = 0;
        for (int i = 0; i < 3000; i++) {
            frame += 48000;
            mouseMax = Math.max(mouseMax, Math.abs(v.resolve(SoundBank.MOUSE_DOWN, Proto.BUTTON_LEFT, frame, 48000).cents));
            tickMax = Math.max(tickMax, Math.abs(v.resolve(SoundBank.SCROLL, 0, frame + 24000, 48000).cents));
        }
        check(mouseMax <= 10 && mouseMax > 8, "鼠标：音高 ±10 音分（最大 " + fmt(mouseMax) + "）");
        check(tickMax <= 8 && tickMax > 6.4, "滚轮：音高 ±8 音分（最大 " + fmt(tickMax) + "）");
        // 两层加起来（变体之间本来的差 + 每一下的随机）不超过方案的 ±30 音分（审查发现以前是 ±21 + ±20 = ±41）
        double variantCents = 1200 * Math.log(1 + SoundSynth.VARIANT_F) / Math.log(2);
        check(variantCents + SoundVary.KEY_CENTS <= 30 && variantCents + SoundVary.MOUSE_CENTS <= 30 && variantCents + SoundVary.TICK_CENTS <= 30,
                "变体（±" + fmt(variantCents) + "）+ 每一下的随机：按键 ±" + fmt(variantCents + SoundVary.KEY_CENTS) + "、鼠标 ±"
                        + fmt(variantCents + SoundVary.MOUSE_CENTS) + "、滚轮 ±" + fmt(variantCents + SoundVary.TICK_CENTS) + " 音分，都 ≤ 30"); 
        // 随机幅度 0：一点不随机（只剩行和类别）；2：两倍
        SoundVary z = new SoundVary(2);
        z.setRandomScale(0);
        boolean exact = true;
        for (int i = 0; i < 100; i++) {
            SoundVary.Shot s = z.resolve(SoundBank.KEY_DOWN, 0x14, 48000L * (i + 1), 48000);
            if (s.cents != SoundVary.rowCents(2) || s.db != 0) exact = false;
        }
        check(exact, "随机幅度 0：音高 = 行的、音量 = 0");
        // 随机幅度 0：变体也固定第 0 个（"完全一样的一下"；审查发现以前变体照样随机挑，变体之间差到 35 音分）
        boolean first = true;
        for (int i = 0; i < 100; i++) {
            first &= z.pickVariant(SoundBank.SLOT_KEY, 0, SoundBank.VARIANTS) == 0;
            first &= z.pickVariant(SoundBank.SLOT_PAD, SoundBank.SUB_PAD_DOWN, SoundBank.VARIANTS) == 0;
        }
        check(first, "随机幅度 0：变体一律第 0 个");
        SoundMixer still = new SoundMixer(4);
        still.setBank(SoundBank.build(48000, SoundBank.PACK_WOOD, false, null));
        still.setRandomScale(0);
        float[] once = null;
        boolean identical = true;
        for (int i = 0; i < 6; i++) {
            still.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
            float[] o = new float[480];
            still.render(o, 480);
            for (int k = 0; k < 200; k++) still.render(new float[480], 480);   // 等它放完、离开"打得快"
            if (once == null) once = o;
            else identical &= Arrays.equals(once, o);
        }
        check(identical, "随机幅度 0：同一个键连按几下，每一下逐个采样一样");
        SoundVary two = new SoundVary(3);
        two.setRandomScale(2);
        double two2 = 0;
        for (int i = 0; i < 3000; i++) two2 = Math.max(two2, Math.abs(two.resolve(SoundBank.KEY_DOWN, 0x04, 48000L * (i + 1), 48000).cents));
        check(two2 <= 24 && two2 > 21, "随机幅度 2：音高到 ±24（" + fmt(two2) + "）");
        two.setRandomScale(99);
        check(Math.abs(two.resolve(SoundBank.KEY_DOWN, 0x04, 48000L * 9999, 48000).cents) <= 36, "随机幅度最多 3");
    }

    /** 变体：随机挑、不连着用同一个、每个都用得到；只有一个时就是它。 */
    private static void variants() {
        SoundVary v = new SoundVary(5);
        int[] count = new int[SoundBank.VARIANTS];
        int last = -1;
        boolean repeat = false;
        for (int i = 0; i < 4000; i++) {
            int x = v.pickVariant(SoundBank.SLOT_KEY, 0, SoundBank.VARIANTS);
            if (x == last) repeat = true;
            if (x < 0 || x >= SoundBank.VARIANTS) {
                check(false, "变体越界 " + x);
                break;
            }
            count[x]++;
            last = x;
        }
        check(!repeat, "变体：不连着用同一个");
        for (int i = 0; i < count.length; i++) check(count[i] > 800, "变体 " + i + " 用得到（" + count[i] + " / 4000）");
        // 不同的子声音各记各的：左键用了 1，右键照样可以用 1
        SoundVary w = new SoundVary(6);
        int a = w.pickVariant(SoundBank.SLOT_MOUSE_DOWN, 0, 4);
        boolean sameAllowed = false;
        for (int i = 0; i < 50 && !sameAllowed; i++) {
            SoundVary t = new SoundVary(100 + i);
            int l = t.pickVariant(SoundBank.SLOT_MOUSE_DOWN, 0, 4);
            int r = t.pickVariant(SoundBank.SLOT_MOUSE_DOWN, 1, 4);
            if (l == r) sameAllowed = true;
        }
        check(a >= 0 && sameAllowed, "变体：子声音各记各的");
        check(w.pickVariant(SoundBank.SLOT_SCROLL, 0, 1) == 0 && w.pickVariant(SoundBank.SLOT_SCROLL, 0, 1) == 0, "只有一个变体：就是它");
    }

    /**
     * 打得快：平均间隔 ≥ 125 ms 不变；100 ms 轻 1.5–4 dB、短尾巴；≤ 60 ms 轻 4 dB；间隔 1 帧也一定出声（按下永远有结果）；
     * 停超过 1 秒重新数；松开不算按键间隔。
     */
    private static void fastTyping() {
        int sr = 48000;
        double[] intervalsMs = {300, 130, 100, 80, 60, 30, 0.02};
        for (double ms : intervalsMs) {
            SoundVary v = new SoundVary(7);
            v.setRandomScale(0);
            long frame = 0;
            double db = 1;
            boolean tail = false, always = true;
            for (int i = 0; i < 12; i++) {
                frame += Math.max(1, Math.round(ms * sr / 1000));
                SoundVary.Shot s = v.resolve(SoundBank.KEY_DOWN, 0x04 + (i % 3), frame, sr);
                if (s == null) {
                    always = false;
                    continue;
                }
                db = s.db;   // resolve 每次用同一个对象：先记下来
                tail = s.shortTail;
                // 中间夹着松开：不影响间隔
                v.resolve(SoundBank.KEY_UP, 0x04 + (i % 3), frame + 1, sr);
            }
            check(always, "间隔 " + ms + " ms：每一下按下都出声");
            if (ms >= 125) {
                check(db == 0 && !tail, "间隔 " + ms + " ms：不变（" + fmt(db) + " dB）");
            } else if (ms > 60) {
                check(db <= -1.5 && db >= -4 && tail, "间隔 " + ms + " ms：轻 1.5–4 dB、短尾巴（" + fmt(db) + " dB）");
            } else {
                check(Math.abs(db + 4) < 1e-9 && tail, "间隔 " + ms + " ms：轻 4 dB、短尾巴（" + fmt(db) + " dB）");
            }
        }
        // 越快越轻（单调）
        double prev = 1;
        for (int ms = 130; ms >= 40; ms -= 5) {
            SoundVary v = new SoundVary(8);
            v.setRandomScale(0);
            SoundVary.Shot s = null;
            for (int i = 1; i <= 6; i++) s = v.resolve(SoundBank.KEY_DOWN, 0x04, (long) i * ms * sr / 1000, sr);
            check(s.db <= prev + 1e-9, "越快越轻：" + ms + " ms → " + fmt(s.db) + " dB");
            prev = s.db;
        }
        // 刚开始打（只有一个间隔）不算快
        SoundVary v = new SoundVary(9);
        v.setRandomScale(0);
        v.resolve(SoundBank.KEY_DOWN, 0x04, 1000, sr);
        SoundVary.Shot second = v.resolve(SoundBank.KEY_DOWN, 0x05, 1000 + sr / 20, sr);
        check(second.db == 0 && !second.shortTail, "第二下（一个间隔）不算快");
        SoundVary.Shot third = v.resolve(SoundBank.KEY_DOWN, 0x06, 1000 + sr / 10, sr);
        check(third.db < 0 && third.shortTail, "第三下（两个 50 ms 的间隔）算快");
        // 停了 1 秒以上重新数
        SoundVary.Shot after = v.resolve(SoundBank.KEY_DOWN, 0x07, 1000 + sr / 10 + 2L * sr, sr);
        check(after.db == 0 && !after.shortTail, "停 2 秒再按：重新数");
        SoundVary.Shot after2 = v.resolve(SoundBank.KEY_DOWN, 0x07, 1000 + sr / 10 + 2L * sr + sr / 20, sr);
        check(after2.db == 0, "停过以后第二下：还是一个间隔，不算快");
        // 松开、鼠标不变轻
        SoundVary u = new SoundVary(10);
        u.setRandomScale(0);
        for (int i = 1; i <= 8; i++) u.resolve(SoundBank.KEY_DOWN, 0x04, (long) i * sr / 50, sr);
        SoundVary.Shot up = u.resolve(SoundBank.KEY_UP, 0x04, 9L * sr / 50, sr);
        check(up.db == 0 && !up.shortTail, "打得快时松开不另外变");
        // 回差：进了"打得快"以后，平均间隔在 125–140 ms 之间还是快（轻 1.5 dB），到 140 ms 以上才恢复；没进过的 130 ms 照旧不快
        SoundVary h = new SoundVary(12);
        h.setRandomScale(0);
        long t = 0;
        for (int i = 0; i < 6; i++) h.resolve(SoundBank.KEY_DOWN, 0x04, t += sr / 10, sr);   // 100 ms：快
        SoundVary.Shot hold = null;
        for (int i = 0; i < 6; i++) hold = h.resolve(SoundBank.KEY_DOWN, 0x04, t += 130L * sr / 1000, sr);
        check(Math.abs(hold.db + SoundVary.FAST_DB_MIN) < 1e-9 && hold.shortTail, "回差：快了以后 130 ms 还算快（轻 1.5 dB、短尾巴）");
        SoundVary.Shot out = null;
        for (int i = 0; i < 6; i++) out = h.resolve(SoundBank.KEY_DOWN, 0x04, t += 145L * sr / 1000, sr);
        check(out.db == 0 && !out.shortTail, "回差：平均到 145 ms 恢复");
        // 每秒 8 下、按块（192 帧 = 4 ms）对齐再抖几毫秒：平均间隔压在 125 ms 上下，也不会快 / 不快来回跳（审查发现以前三成的下忽轻忽重）
        java.util.Random rnd = new java.util.Random(13);
        int worstFlips = 0;
        for (int run = 0; run < 200; run++) {
            SoundVary j = new SoundVary(run);
            j.setRandomScale(0);
            long ideal = 0;
            int flips = 0;
            Boolean prevFast = null;
            for (int i = 0; i < 16; i++) {
                ideal += 125L * sr / 1000;
                long at = (ideal + rnd.nextInt(8 * sr / 1000)) / 192 * 192;   // Handler 抖 0–8 ms、再按混音块对齐
                boolean f = j.resolve(SoundBank.KEY_DOWN, 0x04 + (i % 3), at, sr).shortTail;
                if (prevFast != null && f != prevFast) flips++;
                prevFast = f;
            }
            worstFlips = Math.max(worstFlips, flips);
        }
        check(worstFlips <= 1, "每秒 8 下抖着打：16 下里快 / 不快最多变一次（" + worstFlips + "）");
    }

    /** 滚轮：每 10 ms 一格（一秒 100 格）大部分合并、放出来的间隔 ≥ 40 ms 且轻 3 dB；每 100 ms 一格一个不合并、不变轻；合并数记着。 */
    private static void scrollMerge() {
        int sr = 48000;
        SoundVary v = new SoundVary(11);
        v.setRandomScale(0);
        int played = 0;
        long lastPlayed = -1;
        boolean spaced = true, quieter = true;
        for (int i = 0; i < 100; i++) {
            long frame = (long) i * sr / 100;
            SoundVary.Shot s = v.resolve(SoundBank.SCROLL, 0, frame, sr);
            if (s == null) continue;
            if (lastPlayed >= 0 && (frame - lastPlayed) * 1000.0 / sr < 40) spaced = false;
            if (played > 0 && s.db > -3 + 1e-9) quieter = false;
            lastPlayed = frame;
            played++;
        }
        check(played >= 20 && played <= 26, "一秒 100 格：放出来约 25 格（" + played + "）");
        check(spaced, "放出来的间隔都 ≥ 40 ms");
        check(quieter, "合并过的：轻 3 dB");
        check(v.merged() == 100 - played, "合并数记着（" + v.merged() + "）");
        SoundVary s = new SoundVary(12);
        s.setRandomScale(0);
        int all = 0;
        boolean loud = true;
        for (int i = 0; i < 30; i++) {
            SoundVary.Shot x = s.resolve(SoundBank.SCROLL, 0, (long) i * sr / 10, sr);
            if (x != null) {
                all++;
                if (x.db != 0) loud = false;
            }
        }
        check(all == 30 && loud && s.merged() == 0, "一秒 10 格：一格不合并、不变轻");
        // 手柄的十字键 / 摇杆咔嗒跟滚轮一样合并、再轻 2 dB
        SoundVary g = new SoundVary(13);
        g.setRandomScale(0);
        SoundVary.Shot gt = g.resolve(SoundBank.GAMEPAD_TICK, 0, 0, sr);
        check(gt != null && gt.slot == SoundBank.SLOT_SCROLL && gt.db == -2 && gt.group == SoundVary.G_GAMEPAD, "手柄咔嗒：滚轮那个声音、轻 2 dB");
        // 认不出的声音 id 不放
        check(g.resolve(0, 0, 0, sr) == null && g.resolve(99, 0, 0, sr) == null, "认不出的声音 id：不放");
    }

    /** 互斥的组：同一个键的按下 / 松开同组；不同键不同组；鼠标左右中各一组；键盘键和鼠标键不同组；触控板一组。 */
    private static void groups() {
        SoundVary v = new SoundVary(14);
        long a = v.resolve(SoundBank.KEY_DOWN, 0x04, 0, 48000).group;
        long aUp = v.resolve(SoundBank.KEY_UP, 0x04, 10, 48000).group;
        long b = v.resolve(SoundBank.KEY_DOWN, 0x05, 20, 48000).group;
        long l = v.resolve(SoundBank.MOUSE_DOWN, Proto.BUTTON_LEFT, 30, 48000).group;
        long lUp = v.resolve(SoundBank.MOUSE_UP, Proto.BUTTON_LEFT, 40, 48000).group;
        long r = v.resolve(SoundBank.MOUSE_DOWN, Proto.BUTTON_RIGHT, 50, 48000).group;
        long one = v.resolve(SoundBank.KEY_DOWN, Proto.BUTTON_LEFT, 60, 48000).group;   // 键 id 1 和左键的编号 1 撞了
        long pd = v.resolve(SoundBank.PAD_DOWN, 0, 70, 48000).group;
        long pu = v.resolve(SoundBank.PAD_UP, 0, 80, 48000).group;
        check(a == aUp, "同一个键：按下和松开同组");
        check(a != b, "不同的键：不同组");
        check(l == lUp && l != r, "鼠标：左键按下松开同组、左右不同组");
        check(one != l, "键盘键 1 和鼠标左键 1：不同组");
        check(pd == pu && pd != l, "触控板：按下松开同组、和鼠标不同组");
        // 子声音
        check(v.resolve(SoundBank.MOUSE_DOWN, Proto.BUTTON_RIGHT, 90, 48000).sub == SoundBank.SUB_RIGHT, "右键 → 右键的声音");
        check(v.resolve(SoundBank.MOUSE_DOWN, Proto.BUTTON_MIDDLE, 100, 48000).sub == SoundBank.SUB_MIDDLE, "中键 → 中键的声音");
        check(v.resolve(SoundBank.MOUSE_UP, 0, 110, 48000).sub == SoundBank.SUB_LEFT, "认不出的按钮当左键");
        check(v.resolve(SoundBank.PAD_UP, 0, 120, 48000).sub == SoundBank.SUB_PAD_UP, "触控板松开 → 松开的声音");
        check(v.resolve(SoundBank.KEY_DOWN, 0x2C, 130, 48000).slot == SoundBank.SLOT_SPACE, "空格 → 空格的槽");
        check(v.resolve(SoundBank.KEY_UP, 0x2C, 140, 48000).slot == SoundBank.SLOT_KEY_UP, "空格松开 → 松开的槽");
        check(v.resolve(SoundBank.CATCH, 0, 150, 48000).slot == SoundBank.SLOT_CATCH, "刹住 → 刹住的槽");
        SoundVary z = new SoundVary(15);
        z.setRandomScale(0);
        check(z.resolve(SoundBank.KEY_UP, 0x2C, 0, 48000).cents < z.resolve(SoundBank.KEY_UP, 0x04, 1, 48000).cents, "空格松开比字母松开低");
        SoundVary.Shot gd = z.resolve(SoundBank.GAMEPAD_DOWN, 7, 2, 48000);
        check(gd.slot == SoundBank.SLOT_MOUSE_DOWN && gd.sub == SoundBank.SUB_LEFT && gd.db == -2 && gd.group == (SoundVary.G_GAMEPAD | 7), "手柄键：鼠标左键的声音、轻 2 dB、自己一组");
    }

    // ═══════════ 混音 ═══════════

    private static void requestPacking() {
        int[] sounds = {SoundBank.KEY_DOWN, SoundBank.KEY_UP, SoundBank.MOUSE_DOWN, SoundBank.SCROLL, SoundBank.GAMEPAD_TICK};
        int[] keys = {0, 1, 0x2C, 0xFFFF, Proto.dualKey(0x36, 0x37), Proto.FN_KEY, Proto.frowKey(Proto.FROW_FN_LOCK, 0x29, 0), -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        int[] cands = {-1, 0, 1, 3, 14};
        double[] trims = {0, -0.25, -6, -63.75};
        boolean ok = true;
        for (int s : sounds) for (int k : keys) for (int c : cands) for (double t : trims) {
            long r = SoundMixer.request(s, k, c, t);
            if (r == 0 || SoundMixer.reqSound(r) != s || SoundMixer.reqKey(r) != k || SoundMixer.reqCand(r) != c || SoundMixer.reqTrimDb(r) != t) {
                ok = false;
                check(false, "请求打包：" + s + " " + k + " " + c + " " + t);
            }
        }
        check(ok, "请求打包：声音 id / key / 候选 / 再轻多少都取得回来、不会是 0");
        check(SoundMixer.request(0, 0) != 0, "请求打包：全零的参数也不是 0");
        check(SoundMixer.reqTrimDb(SoundMixer.request(1, 0, -1, 5)) == 0, "再轻多少：正数当 0");
        check(SoundMixer.reqTrimDb(SoundMixer.request(1, 0, -1, -100)) == -63.75, "再轻多少：最多 −63.75");
        check(SoundMixer.reqCand(SoundMixer.request(1, 0, 99, 0)) == 14, "候选：最多 14");
    }

    private static SoundMixer mixer(int pack, boolean all, long seed) {
        SoundMixer m = new SoundMixer(seed);
        m.setBank(SoundBank.build(48000, pack, all, null));
        m.setRandomScale(0);
        return m;
    }

    /** 交了请求，下一块第 0 帧就出声；不随机时输出 = 软限幅(这个声音的增益 Bank.gain × 某个变体)。 */
    private static void mixerStartsNow() {
        SoundMixer m = mixer(SoundBank.PACK_LIGHT, false, 1);
        float[] out = new float[256];
        m.render(out, 256);
        check(allZero(out, 256), "没请求时是静音");
        check(m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04)), "交请求");
        m.render(out, 256);
        check(out[0] != 0, "下一块第 0 帧就出声");
        SoundBank.Bank b = m.bank();
        boolean matches = false;
        for (int v = 0; v < SoundBank.VARIANTS && !matches; v++) {
            float[] d = b.sample(SoundBank.SLOT_KEY, 0, 0, v);
            boolean same = true;
            double g = b.gain(SoundBank.SLOT_KEY, 0, 0);
            for (int i = 0; i < 256 && i < d.length; i++) {
                if (Math.abs(out[i] - SoundMixer.softClip(d[i] * g)) > 1e-5) {
                    same = false;
                    break;
                }
            }
            if (same) matches = true;
        }
        check(matches, "输出 = 软限幅(增益 × 变体)，没变调、没往后挪");
        check(m.voices() == 1 && m.started() == 1, "一个声音在响");
        // 一块里交几个：都从第 0 帧开始
        SoundMixer n = mixer(SoundBank.PACK_WOOD, false, 2);
        n.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        n.post(SoundMixer.request(SoundBank.MOUSE_DOWN, Proto.BUTTON_LEFT));
        n.render(out, 256);
        check(out[0] != 0 && n.voices() == 2, "一块里交两个：都从第 0 帧开始");
    }

    /** 同一个键再按：旧的 6 ms 内淡完，新的照常；不同的键不互相打断。 */
    private static void mixerSameKeyFades() {
        SoundMixer m = mixer(SoundBank.PACK_WOOD, false, 3);
        float[] out = new float[48];
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x2C));   // 树枝，几十毫秒长
        m.render(out, 48);
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x2C));
        m.render(out, 48);
        check(m.voices() == 2 && m.liveVoices() == 1, "同一个键再按：旧的在淡、新的在响");
        int fade = (int) Math.round(SoundMixer.FADE_MS * 48);
        for (int i = 0; i < fade / 48 + 1; i++) m.render(out, 48);
        check(m.voices() == 1, "6 ms 后旧的淡完了");
        // 淡出是平滑的：拿只有旧声音的那一段比——按下 1 ms 后再按，旧的那一路每帧的变化不超过原来最大斜率 + 淡出斜率
        SoundMixer k = mixer(SoundBank.PACK_WOOD, false, 4);
        k.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        k.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x05));
        k.render(out, 48);
        check(k.liveVoices() == 2, "不同的键：两个都在响");
        // 松开和按下同组：松开打断按下的尾巴（木与枝的松开默认不出声，用轻那套）
        SoundMixer l = mixer(SoundBank.PACK_LIGHT, false, 5);
        l.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        l.render(out, 48);
        l.post(SoundMixer.request(SoundBank.KEY_UP, 0x04));
        l.render(out, 48);
        check(l.voices() == 2 && l.liveVoices() == 1, "松开打断同一个键按下的尾巴");
    }

    /** 最多 8 个（不算正在淡掉的）：一毫秒一个交 20 个不同的键，任何时候活着的 ≤ 8、池子 ≤ 16，抢了最老的、用不着硬切；一块里交 20 个时池子不够，硬切的只是在淡的；之后照常放完。 */
    private static void mixerMaxVoices() {
        SoundMixer m = mixer(SoundBank.PACK_WOOD, false, 6);
        float[] out = new float[48];
        int[] keys = {0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17};
        boolean ok = true;
        for (int i = 0; i < keys.length; i++) {
            m.post(SoundMixer.request(SoundBank.KEY_DOWN, keys[i]));
            m.render(out, 48);   // 一毫秒一个
            if (m.liveVoices() > SoundMixer.MAX_VOICES || m.voices() > SoundMixer.POOL) ok = false;
        }
        check(ok, "一毫秒一个、20 个：活着的 ≤ 8、池子 ≤ 16");
        check(m.liveVoices() == SoundMixer.MAX_VOICES, "满了：正好 8 个活着");
        check(m.stolen() == keys.length - SoundMixer.MAX_VOICES, "抢了 " + m.stolen() + " 次（20 − 8）");
        check(m.hardCut() == 0, "一毫秒一个：用不着硬切");
        // 一块里同时交 20 个：池子不够时硬切淡得最快的
        SoundMixer n = mixer(SoundBank.PACK_WOOD, false, 7);
        for (int k : keys) n.post(SoundMixer.request(SoundBank.KEY_DOWN, k));
        n.render(out, 48);
        check(n.liveVoices() <= SoundMixer.MAX_VOICES && n.voices() <= SoundMixer.POOL, "一块里 20 个：活着的 ≤ 8、池子 ≤ 16");
        check(n.liveVoices() == SoundMixer.MAX_VOICES, "一块里 20 个：硬切只切在淡的，活着的还是 8 个");
        check(n.started() == 20 && n.hardCut() > 0, "池子满了硬切（" + n.hardCut() + " 次）");
        for (int i = 0; i < 400; i++) n.render(out, 48);
        check(n.voices() == 0, "都放完了");
        // 被抢的是最老的：最早的那个键再按时，它已经不在了
        SoundMixer o = mixer(SoundBank.PACK_WOOD, false, 8);
        for (int i = 0; i < 9; i++) {
            o.post(SoundMixer.request(SoundBank.KEY_DOWN, keys[i]));
            o.render(out, 48);
        }
        check(o.liveVoices() == 8 && o.voices() == 9, "第 9 个：最老的在淡");
        for (int i = 0; i < 8; i++) o.render(out, 48);
        check(o.voices() == 8, "最老的淡完了");
    }

    /** 软限幅：KNEE 以下原样、奇函数、单调、不超过 1（输入 5 以内严格小于 1）、斜率连续。 */
    private static void softClipShape() {
        boolean identity = true, odd = true, mono = true, bounded = true;
        double prev = -1e9;
        for (double x = -20; x <= 20; x += 0.001) {
            double y = SoundMixer.softClip(x);
            if (Math.abs(x) <= SoundMixer.KNEE && y != x) identity = false;
            if (Math.abs(SoundMixer.softClip(-x) + y) > 1e-12) odd = false;
            if (y < prev - 1e-15) mono = false;
            if (Math.abs(y) > 1 || (Math.abs(x) < 5 && Math.abs(y) >= 1)) bounded = false;
            prev = y;
        }
        check(identity, "软限幅：KNEE 以下原样");
        check(odd, "软限幅：正负对称");
        check(mono, "软限幅：单调");
        check(bounded, "软限幅：不超过 1");
        double h = 1e-6, k = SoundMixer.KNEE;
        double left = (SoundMixer.softClip(k) - SoundMixer.softClip(k - h)) / h, right = (SoundMixer.softClip(k + h) - SoundMixer.softClip(k)) / h;
        check(Math.abs(left - right) < 1e-3, "软限幅：拐点处斜率连续（" + fmt(left) + " / " + fmt(right) + "）");
    }

    /** 叠满 8 个最响的、音量开满、偏移 +6 dB：16 位输出不溢出（不会因为绕回而炸）、浮点不超过 ±1。 */
    private static void mixerNeverClips() {
        SoundMixer m = mixer(SoundBank.PACK_WOOD, true, 9);
        m.setOffsetDb(6);
        short[] s = new short[96];
        float[] f = new float[96];
        boolean ok = true;
        int peak = 0;
        for (int round = 0; round < 200; round++) {
            for (int i = 0; i < 8; i++) m.post(SoundMixer.request(SoundBank.KEY_DOWN, i == 0 ? 0x28 : 0x04 + i));
            m.render(s, 96);
            for (short v : s) peak = Math.max(peak, Math.abs(v));
            m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x2C));
            m.render(f, 96);
            for (float v : f) if (Math.abs(v) > 1 || Float.isNaN(v)) ok = false;
        }
        check(ok, "叠满：浮点输出在 ±1 里");
        check(peak <= 32767 && peak > 16384, "叠满：16 位不溢出、软限幅压着（峰值 " + peak + "）");
    }

    /** 同一个种子、同样的请求序列：输出逐个一样；种子不同：不一样（变体 / 随机不同）。 */
    private static void mixerDeterministic() {
        float[] a = run(1), b = run(1), c = run(2);
        check(Arrays.equals(a, b), "同一个种子：输出逐个一样");
        check(!Arrays.equals(a, c), "种子不同：输出不一样");
    }

    private static float[] run(long seed) {
        SoundMixer m = new SoundMixer(seed);
        m.setBank(SoundBank.build(48000, SoundBank.PACK_WOOD, false, null));
        float[] all = new float[48 * 400];
        float[] out = new float[48];
        SoundSynth.Rng r = new SoundSynth.Rng(99);
        int[] sounds = {SoundBank.KEY_DOWN, SoundBank.KEY_UP, SoundBank.MOUSE_DOWN, SoundBank.MOUSE_UP, SoundBank.PAD_DOWN, SoundBank.SCROLL, SoundBank.CATCH};
        for (int i = 0; i < 400; i++) {
            if (r.below(4) == 0) m.post(SoundMixer.request(sounds[r.below(sounds.length)], 0x04 + r.below(30)));
            m.render(out, 48);
            System.arraycopy(out, 0, all, i * 48, 48);
        }
        return all;
    }

    /** 音量：0 = 静音；滑块 0–100 换算单调、100 = 0 dB、50 = −20 dB；音量减半输出减半（软限幅以下）。 */
    private static void mixerVolume() {
        check(SoundMixer.volumeGain(0) == 0 && SoundMixer.volumeGain(-5) == 0, "音量 0：静音");
        check(Math.abs(SoundMixer.volumeGain(100) - 1) < 1e-6 && SoundMixer.volumeGain(150) == SoundMixer.volumeGain(100), "音量 100：0 dB（超出截住）");
        check(Math.abs(20 * Math.log10(SoundMixer.volumeGain(50)) + 20) < 1e-3, "音量 50：−20 dB");
        boolean mono = true;
        for (int p = 1; p <= 100; p++) if (SoundMixer.volumeGain(p) <= SoundMixer.volumeGain(p - 1)) mono = false;
        check(mono, "音量滑块：越大越响");
        float[] full = one(1f), half = one(0.5f), zero = one(0f);
        boolean ratio = true;
        for (int i = 0; i < full.length; i++) if (Math.abs(half[i] - full[i] * 0.5f) > 1e-5) ratio = false;
        check(ratio, "音量减半：输出减半");
        check(allZero(zero, zero.length), "音量 0：全是 0");
    }

    private static float[] one(float volume) {
        SoundMixer m = mixer(SoundBank.PACK_LIGHT, false, 10);
        m.setVolume(volume);
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        float[] out = new float[512];
        m.render(out, 512);
        return out;
    }

    /** 候选：请求里指定的用指定的；设置里选的用选的；正式包（只算了默认）选了没算的退回算了的那个。 */
    private static void mixerCandidates() {
        SoundBank.Bank all = SoundBank.build(48000, SoundBank.PACK_LIGHT, true, null);
        for (int c = 0; c < 3; c++) {
            SoundMixer m = new SoundMixer(11);
            m.setBank(all);
            m.setRandomScale(0);
            m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04, c, 0));
            float[] out = new float[64];
            m.render(out, 64);
            check(matchesAnyVariant(out, all, SoundBank.SLOT_KEY, c, 0, 0), "试听指定候选 " + c + "：放的就是它");
            SoundMixer n = new SoundMixer(12);
            n.setBank(all);
            n.setRandomScale(0);
            int[] ch = new int[SoundBank.SLOTS];
            ch[SoundBank.SLOT_KEY] = c;
            n.setChoices(ch);
            n.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
            n.render(out, 64);
            check(matchesAnyVariant(out, all, SoundBank.SLOT_KEY, c, 0, 0), "设置里选候选 " + c + "：放的就是它");
        }
        SoundBank.Bank only = SoundBank.build(48000, SoundBank.PACK_LIGHT, false, null);
        SoundMixer m = new SoundMixer(13);
        m.setBank(only);
        m.setRandomScale(0);
        int[] ch = new int[SoundBank.SLOTS];
        ch[SoundBank.SLOT_KEY] = 2;
        m.setChoices(ch);
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        float[] out = new float[64];
        m.render(out, 64);
        check(matchesAnyVariant(out, only, SoundBank.SLOT_KEY, 0, 0, 0), "选了没算的候选：退回默认的");
        // 再轻多少（请求里带的）
        SoundMixer t = new SoundMixer(14);
        t.setBank(all);
        t.setRandomScale(0);
        t.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04, 0, -6));
        t.render(out, 64);
        check(matchesAnyVariant(out, all, SoundBank.SLOT_KEY, 0, 0, -6), "请求里带的再轻 6 dB");
        // 偏移
        SoundMixer o = new SoundMixer(15);
        o.setBank(all);
        o.setRandomScale(0);
        o.setOffsetDb(-6);
        o.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        o.render(out, 64);
        check(matchesAnyVariant(out, all, SoundBank.SLOT_KEY, 0, 0, -6), "音量偏移 −6 dB");
    }

    /** 不出声的候选（木与枝的松开）：不起声音，也不打断同一个键还在响的按下。 */
    private static void mixerSilentCandidate() {
        SoundMixer m = mixer(SoundBank.PACK_WOOD, false, 16);
        float[] out = new float[48];
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        m.render(out, 48);
        m.post(SoundMixer.request(SoundBank.KEY_UP, 0x04));
        m.render(out, 48);
        check(m.voices() == 1 && m.liveVoices() == 1 && m.started() == 1, "木与枝的松开：不出声、不打断按下");
        // 没有 bank 时什么都不放
        SoundMixer n = new SoundMixer(17);
        n.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        n.render(out, 48);
        check(allZero(out, 48) && n.voices() == 0, "没有声音（关）：什么都不放");
    }

    /** 打得快的短尾巴：同一个键放的总长短了（木块 76 ms → 约 45 ms），结尾平滑地淡到 0。 */
    private static void mixerShortTail() {
        int normal = lengthOfOneKey(false), fast = lengthOfOneKey(true);
        check(fast < normal * 0.75 && fast >= 30 * 48, "打得快：尾巴短了（" + fmt(normal / 48.0) + " → " + fmt(fast / 48.0) + " ms）");
    }

    /**
     * 放一个木块按下，数它响了多少帧。fast = 先每 70 ms 按一下别的键、按三下（第三下起就算打得快），隔 70 ms 再按这一下：
     * 那时前面三下都已经放完（第三下也是短尾巴），从按下数到 voices() 归零就是这一下的长度。
     */
    private static int lengthOfOneKey(boolean fast) {
        SoundMixer m = mixer(SoundBank.PACK_WOOD, false, 18);
        float[] out = new float[48];
        if (fast) {
            int[] warm = {0x05, 0x06, 0x07};
            for (int k : warm) {
                m.post(SoundMixer.request(SoundBank.KEY_DOWN, k));
                for (int i = 0; i < 70; i++) m.render(out, 48);
            }
            check(m.voices() == 0, "打得快的前三下在第四下之前放完了");
        }
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        int frames = 0;
        for (int i = 0; i < 48 * 400; i++) {
            m.render(out, 1);
            frames++;
            if (m.voices() == 0) break;
        }
        return frames;
    }

    /** 闲着：放完以后 quiet()、idleFrames 一直涨；交一个请求就不 quiet、idleFrames 归零。 */
    private static void mixerIdle() {
        SoundMixer m = mixer(SoundBank.PACK_LIGHT, false, 19);
        float[] out = new float[48];
        check(m.quiet(), "一开始：安静");
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        check(!m.quiet(), "交了请求：不安静");
        m.render(out, 48);
        check(m.idleFrames() == 0, "在响：idleFrames = 0");
        for (int i = 0; i < 100; i++) m.render(out, 48);
        check(m.quiet() && m.idleFrames() > 48 * 50, "放完了：安静、idleFrames 在涨（" + m.idleFrames() + "）");
        check(m.frame() == 48 * 101, "数过的帧");
    }

    /** 换一份声音：正在放的全停、队列清空（换之前交的请求不晚放）；reset 也一样。 */
    private static void mixerBankSwap() {
        SoundMixer m = mixer(SoundBank.PACK_LIGHT, false, 20);
        float[] out = new float[48];
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04));
        m.render(out, 48);
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x05));
        m.setBank(SoundBank.build(48000, SoundBank.PACK_WOOD, false, null));
        m.render(out, 48);
        check(m.voices() == 0 && allZero(out, 48), "换一份：全停、换之前交的不放");
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x05));
        m.render(out, 48);
        check(m.voices() == 1, "换了以后照常放");
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x06));
        m.reset();
        m.render(out, 48);
        check(m.voices() == 0 && allZero(out, 48), "reset：全停、队列清空");
        m.setBank(null);
        m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x05));
        m.render(out, 48);
        check(allZero(out, 48), "换成 null（关）：不出声");
    }

    /**
     * 晚放的请求（一次点击那一对"咔哒"的"哒"，SoundEvents.CLICK_GAP_MS）：延后写得进、取得回（0–255 ms，超出的截掉）；
     * 正好在第 delay × 采样率 / 1000 帧开始、之前一帧都不出声；交的时候不淡掉同一组正在响的"咔"（前面那段和只放"咔"逐个一样）；
     * 等着的时候算"有声音"（管道不暂停）；被同一组新来的淡掉就整个不放；同样的输入结果一样。
     */
    private static void mixerDelayed() {
        int[] delays = {0, 1, 45, 255, 300, -5};
        int[] want = {0, 1, 45, 255, 255, 0};
        boolean packed = true;
        for (int i = 0; i < delays.length; i++) {
            long r = SoundMixer.request(SoundBank.PAD_UP, 0x1234, 2, -6, delays[i]);
            packed &= SoundMixer.reqDelayMs(r) == want[i] && SoundMixer.reqSound(r) == SoundBank.PAD_UP && SoundMixer.reqKey(r) == 0x1234
                    && SoundMixer.reqCand(r) == 2 && SoundMixer.reqTrimDb(r) == -6;
        }
        packed &= SoundMixer.reqDelayMs(SoundMixer.request(SoundBank.KEY_DOWN, -1)) == 0 && SoundMixer.reqKey(SoundMixer.request(SoundBank.KEY_DOWN, -1, -1, 0, 255)) == -1;
        check(packed, "延后：写得进、取得回（截到 0–255 ms），不碰别的字段（key 是 -1 也一样）");
        // 正好在 45 ms（48 kHz 下第 2160 帧）开始
        SoundMixer m = mixer(SoundBank.PACK_LIGHT, false, 21);
        m.post(SoundMixer.request(SoundBank.PAD_UP, 0, -1, 0, 45));
        float[] out = new float[48];
        int first = -1;
        for (int b = 0; b < 100 && first < 0; b++) {
            m.render(out, 48);
            if (b == 0) check(!m.quiet() && m.voices() == 1, "等着的时候算有声音（管道不暂停）");
            for (int i = 0; i < 48; i++) {
                if (out[i] != 0) {
                    first = b * 48 + i;
                    break;
                }
            }
        }
        check(first == 45 * 48, "晚 45 ms：第一个不是 0 的采样正好在第 2160 帧（实际 " + first + "）");
        // 一对：按下马上、松开晚 45 ms；前 45 ms 和只放按下的逐个一样（松开没淡掉按下）
        SoundMixer a = mixer(SoundBank.PACK_LIGHT, false, 22), b = mixer(SoundBank.PACK_LIGHT, false, 22);
        a.post(SoundMixer.request(SoundBank.PAD_DOWN, 0));
        a.post(SoundMixer.request(SoundBank.PAD_UP, 0, -1, 0, 45));
        b.post(SoundMixer.request(SoundBank.PAD_DOWN, 0));
        float[] oa = new float[45 * 48], ob = new float[45 * 48];
        a.render(oa, oa.length);
        b.render(ob, ob.length);
        check(Arrays.equals(oa, ob) && a.voices() == 1 && a.started() == 2, "一对：前 45 ms 只有按下、和单放按下逐个一样（交松开时没淡掉按下）");
        float[] tail = new float[30 * 48];
        a.render(tail, tail.length);
        check(!allZero(tail, tail.length), "45 ms 时松开开始响");
        // 等着的时候同一组又来一个：等着的那个整个不放
        SoundMixer c = mixer(SoundBank.PACK_LIGHT, false, 23);
        c.post(SoundMixer.request(SoundBank.PAD_DOWN, 0));
        c.post(SoundMixer.request(SoundBank.PAD_UP, 0, -1, 0, 45));
        float[] o10 = new float[10 * 48];
        c.render(o10, o10.length);
        c.post(SoundMixer.request(SoundBank.PAD_DOWN, 0));   // 10 ms 时又点了一下（新的一对的"咔"）
        c.render(o10, o10.length);
        float[] rest = new float[200 * 48];
        c.render(rest, rest.length);
        int lastNonZero = -1;
        for (int i = 0; i < rest.length; i++) if (rest[i] != 0) lastNonZero = i;
        int pad = c.bank().sample(SoundBank.SLOT_PAD, 0, SoundBank.SUB_PAD_DOWN, 0).length;
        check(c.voices() == 0 && lastNonZero < 45 * 48 - 20 * 48, "等着的松开被同一组新来的淡掉：整个不放（之后只有新的按下，" + pad + " 帧长）");
        // 不同组的不受影响：鼠标的一对和触控板的一对叠着放
        SoundMixer d = mixer(SoundBank.PACK_WOOD, false, 24);
        d.post(SoundMixer.request(SoundBank.MOUSE_DOWN, Proto.BUTTON_LEFT));
        d.post(SoundMixer.request(SoundBank.MOUSE_UP, Proto.BUTTON_LEFT, -1, 0, 45));
        d.post(SoundMixer.request(SoundBank.PAD_DOWN, 0));
        d.render(out, 48);
        check(d.voices() == 3 && d.liveVoices() == 3, "鼠标的一对 + 触控板的按下：三个都在（等着的那个也算一个）");
        // 确定性
        SoundMixer e1 = mixer(SoundBank.PACK_WOOD, false, 25), e2 = mixer(SoundBank.PACK_WOOD, false, 25);
        float[] x1 = new float[100 * 48], x2 = new float[100 * 48];
        for (SoundMixer e : new SoundMixer[]{e1, e2}) {
            e.post(SoundMixer.request(SoundBank.MOUSE_DOWN, Proto.BUTTON_RIGHT));
            e.post(SoundMixer.request(SoundBank.MOUSE_UP, Proto.BUTTON_RIGHT, -1, 0, 45));
        }
        e1.render(x1, x1.length);
        e2.render(x2, x2.length);
        check(Arrays.equals(x1, x2) && !allZero(x1, x1.length), "一对：同样的输入结果逐个一样");
    }

    // ═══════════ 队列 ═══════════

    private static void queueSingle() {
        SoundQueue q = new SoundQueue(5);   // 往上取到 8
        check(q.isEmpty() && q.poll() == 0, "空队列");
        for (int i = 1; i <= 8; i++) check(q.offer(i), "放第 " + i + " 个");
        check(!q.offer(9), "满了（8 个）：放不进");
        for (int i = 1; i <= 8; i++) check(q.poll() == i, "先进先出：第 " + i + " 个");
        check(q.poll() == 0 && q.isEmpty(), "取完了");
        for (int round = 0; round < 1000; round++) {
            q.offer(round + 1);
            if (q.poll() != round + 1) {
                check(false, "绕圈：第 " + round + " 圈");
                break;
            }
        }
        check(true, "绕了 1000 圈");
        q.offer(5);
        q.offer(6);
        q.clear();
        check(q.isEmpty() && q.poll() == 0, "清空");
        boolean threw = false;
        try {
            q.offer(0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "0 不能放");
    }

    /** 4 个线程同时写（每个 20000 个）、一个线程读：放进去的（offer 返回 true 的）恰好读到一次，没有 0、没有重复、没有凭空多出来的。 */
    private static void queueConcurrent() throws Exception {
        final SoundQueue q = new SoundQueue(64);
        final int producers = 4, each = 20000;
        final boolean[][] accepted = new boolean[producers][each];
        final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(producers);
        final AtomicBoolean stop = new AtomicBoolean();
        List<Thread> ts = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int id = p;
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    long v = ((long) (id + 1) << 32) | i;
                    accepted[id][i] = q.offer(v);
                    if ((i & 63) == 0) Thread.yield();
                }
                done.countDown();
            });
            t.start();
            ts.add(t);
        }
        final List<Long> got = new ArrayList<>();
        Thread consumer = new Thread(() -> {
            while (!stop.get() || !q.isEmpty()) {
                long v = q.poll();
                if (v != 0) got.add(v);
                else Thread.yield();
            }
        });
        consumer.start();
        go.countDown();
        done.await();
        stop.set(true);
        consumer.join();
        for (Thread t : ts) t.join();
        Set<Long> seen = new HashSet<>();
        boolean dup = false, bogus = false;
        for (long v : got) {
            if (!seen.add(v)) dup = true;
            int id = (int) (v >>> 32) - 1, i = (int) v;
            if (id < 0 || id >= producers || i < 0 || i >= each || !accepted[id][i]) bogus = true;
        }
        int acc = 0;
        for (boolean[] a : accepted) for (boolean b : a) if (b) acc++;
        check(!dup, "并发：没有重复");
        check(!bogus, "并发：读到的都是放进去了的");
        check(got.size() == acc, "并发：放进去的 " + acc + " 个都读到了（读到 " + got.size() + "）");
        check(acc > 0, "并发：确实放进去了一些");
        // 每个写的线程自己的顺序不乱
        boolean order = true;
        int[] lastI = new int[producers];
        Arrays.fill(lastI, -1);
        for (long v : got) {
            int id = (int) (v >>> 32) - 1, i = (int) v;
            if (i <= lastI[id]) order = false;
            lastI[id] = i;
        }
        check(order, "并发：每个线程放的先后不乱");
    }

    /** 快不快：一秒的声音（8 个一直在响）混多久；全算一套多久。只打出来、给一个很松的上限（电脑上）。 */
    private static void speed() {
        SoundMixer m = mixer(SoundBank.PACK_WOOD, false, 21);
        short[] out = new short[192];
        long t0 = System.nanoTime();
        int blocks = 48000 / 192 * 5;
        for (int i = 0; i < blocks; i++) {
            if (i % 2 == 0) for (int k = 0; k < 8; k++) m.post(SoundMixer.request(SoundBank.KEY_DOWN, 0x04 + k));
            m.render(out, 192);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        long t1 = System.nanoTime();
        SoundBank.Bank b = SoundBank.build(48000, SoundBank.PACK_WOOD, true, null);
        double build = (System.nanoTime() - t1) / 1e6;
        System.err.println("  混 5 秒（8 个一直在响）：" + fmt(ms) + " ms；全算木与枝：" + fmt(build) + " ms、" + b.bytes / 1024 + " KB");
        check(ms < 2500, "混 5 秒的声音用不了 2.5 秒（" + fmt(ms) + " ms）");
    }

    // ═══════════ 工具 ═══════════

    /** out 是不是"软限幅(这个声音的增益 × 某个变体 × 再加减 extraDb)"。 */
    private static boolean matchesAnyVariant(float[] out, SoundBank.Bank b, int slot, int cand, int sub, double extraDb) {
        double g = b.gain(slot, cand, sub) * Math.pow(10, extraDb / 20);
        for (int v = 0; v < SoundBank.VARIANTS; v++) {
            float[] d = b.sample(slot, cand, sub, v);
            if (d == null) continue;
            boolean same = true;
            for (int i = 0; i < out.length && i < d.length; i++) {
                if (Math.abs(out[i] - SoundMixer.softClip(d[i] * g)) > 1e-5) {
                    same = false;
                    break;
                }
            }
            if (same) return true;
        }
        return false;
    }

    private static boolean allZero(float[] a, int n) {
        for (int i = 0; i < n; i++) if (a[i] != 0) return false;
        return true;
    }

    private static double maxIn(double[] m, int sr, int n, double lo, double hi) {
        double r = 0;
        for (int k = 0; k < m.length; k++) {
            double f = k * (double) sr / n;
            if (f >= lo && f <= hi) r = Math.max(r, m[k]);
        }
        return r;
    }

    /** 补零到 ≥ 16384 点的 FFT 幅度谱（声音很短、尾巴淡到 0，不加窗）。 */
    static double[] magnitude(float[] x, int sr) {
        int n = 1;
        while (n < Math.max(16384, x.length)) n <<= 1;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < x.length; i++) re[i] = x[i];
        fft(re, im);
        double[] m = new double[n / 2 + 1];
        for (int k = 0; k <= n / 2; k++) m[k] = Math.hypot(re[k], im[k]);
        return m;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;
                    double xr = re[v] * cr - im[v] * ci, xi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - xr;
                    im[v] = im[u] - xi;
                    re[u] += xr;
                    im[u] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("  失败：" + what);
        }
    }
}
