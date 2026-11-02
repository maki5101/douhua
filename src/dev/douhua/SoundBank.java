package dev.douhua;

import dev.douhua.SoundSynth.Recipe;

/**
 * 声音的清单：有哪几套（{@link #PACK_IDS}）、每套里每个槽（{@link #SLOT_IDS}，候选按它分）有哪几个候选、每个子声音多响，
 * 和"预先算好放内存"的那一份（{@link #build} → {@link Bank}）。纯 Java，离线测（SoundTest）。
 *
 * 三层编号，给后面接事件、做设置页用：
 * <ul>
 * <li>声音 id（"发生了什么"）：{@link #KEY_DOWN} 到 {@link #GAMEPAD_TICK}，事件层只认这一层，Sounds.play(声音 id, key)。
 *     key：键盘键是区域的键 id（REGION_KEY 那种，含合并键 / Fn 层 / F 行键，SoundVary 按它查类别和行；同一个 id 的按下和松开互斥），
 *     鼠标键是 Proto.BUTTON_LEFT / RIGHT / MIDDLE，别的填 0。</li>
 * <li>槽（{@link #SLOT_KEY} 到 {@link #SLOT_CATCH}）：一套声音里的一类，候选按槽列。声音 id → 槽 + 子声音由 SoundVary.resolve 定
 *     （按下的普通键 / 大键 / 空格 / 回车各是一个槽，鼠标按下一个槽、左右中是它的三个子声音，触控板按下 / 松开是一个槽的两个子声音）。</li>
 * <li>候选：{@link #candidates}(套, 槽)，第 0 个是默认。调试版设置里挑的存 Prefs，正式包一律用默认（同惯性波形那一节的规矩）。</li>
 * </ul>
 * 套装第一批：关、轻、木与枝（以后加机械 / 打字机 / 钢琴，存的是 {@link #PACK_IDS} 里的字，别改已有的）。
 * 触控板轻点（{@link #SLOT_PAD}）两套用同一组"像 MacBook 触控板"的候选（用户 2026-11-02：要最接近 MacBook 触控板的声音、比三个鼠标键再轻一点）。
 */
public final class SoundBank {
    private SoundBank() {}

    // ═══════════ 套装 ═══════════

    public static final int PACK_OFF = 0, PACK_LIGHT = 1, PACK_WOOD = 2;
    /** 存进 Prefs 的字（按下标对应 PACK_*）。 */
    public static final String[] PACK_IDS = {"off", "light", "wood"};
    public static final int PACKS = PACK_IDS.length;

    /** 存的字 → PACK_*，认不出的当"关"。 */
    public static int packOf(String id) {
        for (int i = 0; i < PACK_IDS.length; i++) if (PACK_IDS[i].equals(id)) return i;
        return PACK_OFF;
    }

    // ═══════════ 声音 id（"发生了什么"，事件层用） ═══════════

    /** 键按下 / 正常松开（被取消的不出声，事件层不发）。key = 区域的键 id。 */
    public static final int KEY_DOWN = 1, KEY_UP = 2;
    /** 鼠标键按下 / 松开。key = Proto.BUTTON_*（别的值当左键）。 */
    public static final int MOUSE_DOWN = 3, MOUSE_UP = 4;
    /** 触控板轻点的按下 / 松开（像 MacBook 触控板，比鼠标键轻）。 */
    public static final int PAD_DOWN = 5, PAD_UP = 6;
    /** 滚一格。打得快了会合并、变轻（SoundVary）。 */
    public static final int SCROLL = 7;
    /** 惯性刹住（很轻一声）。 */
    public static final int CATCH = 8;
    /** 手柄键按下 / 松开、十字键换方向 / 摇杆推到边（设置里默认关）。 */
    public static final int GAMEPAD_DOWN = 9, GAMEPAD_UP = 10, GAMEPAD_TICK = 11;
    public static final int SOUNDS = 12;

    // ═══════════ 槽 ═══════════

    public static final int SLOT_KEY = 0, SLOT_BIG = 1, SLOT_SPACE = 2, SLOT_ENTER = 3, SLOT_KEY_UP = 4,
            SLOT_MOUSE_DOWN = 5, SLOT_MOUSE_UP = 6, SLOT_PAD = 7, SLOT_SCROLL = 8, SLOT_CATCH = 9;
    /** 存调试版选的候选时用的字（按下标对应 SLOT_*）。 */
    public static final String[] SLOT_IDS = {
            "key", "big", "space", "enter", "key_up", "mouse_down", "mouse_up", "pad", "scroll", "catch",
    };
    public static final int SLOTS = SLOT_IDS.length;

    /** 鼠标的子声音：左 / 右 / 中。 */
    public static final int SUB_LEFT = 0, SUB_RIGHT = 1, SUB_MIDDLE = 2;
    /** 触控板的子声音：按下 / 松开。 */
    public static final int SUB_PAD_DOWN = 0, SUB_PAD_UP = 1;

    /** 一个槽有几个子声音。 */
    public static int subs(int slot) {
        if (slot == SLOT_MOUSE_DOWN || slot == SLOT_MOUSE_UP) return 3;
        if (slot == SLOT_PAD) return 2;
        return 1;
    }

    /** 每个子声音预先算几个变体（不同种子），放的时候随机挑、不连着用同一个。 */
    public static final int VARIANTS = 4;

    /**
     * 子声音的电平（dB，响度相对普通键按下：{@link #loudnessDb} 量的，普通键按下在 0 dB 时就是 {@link #LOUDNESS_REF_DB}）。数字照方案 2.6 的表：
     * 松开比按下轻 9 dB（6–12）、鼠标比普通键轻 2 dB、触控板再轻 4 dB（松开再轻 7）、滚轮轻 12 dB（10–14）、刹住比滚轮再轻一点。
     * 修饰键 / 功能键的"轻 3 / 1.5 dB"按键的类别算，不在这里（SoundVary.classDb）。
     * 是响度不是峰值：几十毫秒以内的声音听起来多响主要看总能量（也就看它多长），峰值一样的 5 ms 滚轮刻度和 70 ms 木块差十几 dB。
     * {@link #build} 照每个声音算出来的响度补增益（{@link Bank#gain}），这张表说的就是在手机喇叭上听到的轻重。
     */
    public static double levelDb(int pack, int slot, int sub) {
        double db;
        switch (slot) {
            case SLOT_KEY_UP: db = -9; break;
            case SLOT_MOUSE_DOWN: db = sub == SUB_MIDDLE ? -2.5 : -2; break;
            case SLOT_MOUSE_UP: db = sub == SUB_MIDDLE ? -8.5 : -8; break;
            case SLOT_PAD: db = sub == SUB_PAD_UP ? -13 : -6; break;
            case SLOT_SCROLL: db = -12; break;
            case SLOT_CATCH: db = -14; break;
            default: db = 0; break;
        }
        // "轻"那一套整体再轻 3 dB（不想被人听见）；木与枝的树枝本来就扎耳，空格压 1 dB
        if (pack == PACK_LIGHT) db -= 3;
        if (pack == PACK_WOOD && slot == SLOT_SPACE) db -= 1;
        return db;
    }

    // ═══════════ 响度 ═══════════

    /**
     * 粗略的手机喇叭（二阶高通的 −3 dB 点）：方案 1.5"手机喇叭 500–1000 Hz 以下衰减很快"取中间，不是 Fold 8 的实测。
     * 量响度时先过它：能量落在喇叭放不出来的地方的，不算响。
     */
    public static final double SPEAKER_HZ = 800;
    /** 量响度的窗：最响的这么长一段里的能量（几十毫秒以内的声音就是总能量）。 */
    public static final double LOUDNESS_WINDOW_MS = 50;
    /**
     * 普通键按下（电平 0 dB、音量 100%、没有随机）的响度（dB，{@link #loudnessDb} 的单位：满幅² · 秒）。
     * 定在这里，单个声音的峰值都在软限幅的拐点（SoundMixer.KNEE）以下（SoundTest 锁住），几个叠起来才会碰到。
     */
    public static final double LOUDNESS_REF_DB = -41;

    /**
     * 一个声音的响度（dB，满幅² · 秒）：过粗略的手机喇叭（{@link #SPEAKER_HZ} 二阶高通），取最响的 {@link #LOUDNESS_WINDOW_MS} 毫秒里的能量。
     * 全零的给很小的数（不会除零）。
     */
    public static double loudnessDb(float[] x, int sr) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i];
        SoundSynth.highpass2(y, sr, SPEAKER_HZ, 0.7071067811865476);
        int w = Math.max(1, (int) Math.round(LOUDNESS_WINDOW_MS * sr / 1000));
        double sum = 0, best = 0;
        for (int i = 0; i < y.length; i++) {
            sum += y[i] * y[i];
            if (i >= w) sum -= y[i - w] * y[i - w];
            best = Math.max(best, sum);
        }
        return 10 * Math.log10(Math.max(best / sr, 1e-30));
    }

    // ═══════════ 候选 ═══════════

    /** 一个候选。recipe 是 null = 这个槽不出声（比如木与枝的松开）。code 是 A / B / C / D（调试版候选区显示，"打乱顺序"时换的是它背后的候选）。 */
    public static final class Candidate {
        public final String code;
        public final Recipe recipe;

        Candidate(String code, Recipe recipe) {
            this.code = code;
            this.recipe = recipe;
        }

        /** 候选区的小字（数字和单位，不用翻译；不出声的是空字）。 */
        public String desc() {
            return recipe == null ? "" : recipe.desc;
        }
    }

    private static Candidate c(String code, Recipe r) {
        return new Candidate(code, r);
    }

    private static Recipe r(int type, double f, double t60, double lp, double a, double b, double c) {
        return new Recipe(type, f, t60, lp, a, b, c);
    }

    // 像 MacBook 触控板（两套共用）：A 实、B 更低（只放 340 Hz 的泛音，耳朵听出低音）、C 更脆、D 双层（执行器回弹一下）。
    // 真触控板那一下的"实"在 200–400 Hz，手机喇叭放不出：主共振都放在 600 Hz 以上，靠阻尼短、压掉高频显得实
    private static final Candidate[] PAD = {
            c("A", r(SoundSynth.TAPTIC, 680, 18, 0, 0.25, 1500, 0)),
            c("B", r(SoundSynth.TAPTIC_LOW, 340, 22, 0, 0.2, 1250, 0)),
            c("C", r(SoundSynth.TAPTIC, 820, 13, 0, 0.35, 1900, 0)),
            c("D", r(SoundSynth.TAPTIC, 680, 17, 0, 0.25, 1500, 7)),
    };

    // 微动开关：清脆 / 收着 / 闷
    private static final Recipe SWITCH_CLEAR = r(SoundSynth.SWITCH, 2100, 10, 9000, 1200, 0, 0);
    private static final Recipe SWITCH_HELD = r(SoundSynth.SWITCH, 1750, 9, 4500, 950, 0, 0);
    private static final Recipe SWITCH_DULL = r(SoundSynth.SWITCH, 1350, 8, 2800, 800, 0, 0);

    private static final Candidate[][] LIGHT = {
            // 普通键：闷嗒 / 更闷 / 笔记本（亮一点、更短）
            {c("A", r(SoundSynth.TAP, 850, 30, 2200, 0.3, 0, 0)),
             c("B", r(SoundSynth.TAP, 650, 35, 1500, 0.25, 0, 0)),
             c("C", r(SoundSynth.TAP, 1150, 20, 2600, 0.35, 0, 0))},
            // 大键（退格、Shift）：低一点、长一点
            {c("A", r(SoundSynth.TAP, 790, 36, 2000, 0.35, 0, 0)),
             c("B", r(SoundSynth.TAP, 560, 42, 1400, 0.3, 0, 0))},
            // 空格：最厚、略长
            {c("A", r(SoundSynth.TAP, 600, 48, 1900, 0.5, 0, 0)),
             c("B", r(SoundSynth.TAP, 560, 55, 1400, 0.45, 0, 0))},
            // 回车
            {c("A", r(SoundSynth.TAP, 640, 42, 2000, 0.4, 0, 0)),
             c("B", r(SoundSynth.TAP, 540, 48, 1500, 0.35, 0, 0))},
            // 松开：几乎听不见的一下 / 不出声
            {c("A", r(SoundSynth.TAP, 980, 16, 2000, 0.3, 0, 0)),
             c("B", null)},
            // 鼠标按下：闷嗒 / 收着的微动 / 闷的微动
            {c("A", r(SoundSynth.TAP, 1050, 16, 2600, 0.35, 0, 0)),
             c("B", SWITCH_HELD),
             c("C", SWITCH_DULL)},
            // 鼠标松开
            {c("A", r(SoundSynth.TAP, 1150, 11, 2600, 0.35, 0, 0)),
             c("B", up(SWITCH_HELD)),
             c("C", up(SWITCH_DULL))},
            PAD,
            // 滚轮一格：细 / 闷 / 棘轮两下
            {c("A", r(SoundSynth.TICK, 1700, 5, 6000, 0, 0, 0)),
             c("B", r(SoundSynth.TICK, 1150, 7, 3500, 0, 0, 0)),
             c("C", r(SoundSynth.TICK, 1600, 4, 5000, 1.8, 0, 0))},
            // 惯性刹住
            {c("A", r(SoundSynth.TAP, 1000, 18, 2200, 0.3, 0, 0)),
             c("B", r(SoundSynth.TICK, 1150, 7, 3500, 0, 0, 0))},
    };

    private static final Candidate[][] WOOD = {
            // 字母：木块 / 木鱼 / 硬木
            {c("A", r(SoundSynth.WOOD, 950, 75, 5000, 0.55, 0, 0)),
             c("B", r(SoundSynth.WOOD, 720, 120, 5000, 0.7, 1, 0)),
             c("C", r(SoundSynth.WOOD, 1250, 45, 5000, 0.4, 0, 0))},
            // 大键：低一点的木块 / 木鱼
            {c("A", r(SoundSynth.WOOD, 780, 85, 5000, 0.65, 0, 0)),
             c("B", r(SoundSynth.WOOD, 600, 120, 5000, 0.8, 1, 0))},
            // 空格：细树枝折断 / 粗一点 / 干枝
            {c("A", r(SoundSynth.TWIG, 1250, 0, 0, 7, 35, 0)),
             c("B", r(SoundSynth.TWIG, 900, 0, 0, 12, 55, 0)),
             c("C", r(SoundSynth.TWIG, 1300, 0, 0, 4, 22, 0))},
            // 回车：竹筒（"咚"本来在 500 Hz 以下，手机喇叭放不出：放在 580 Hz 以上）
            {c("A", r(SoundSynth.BAMBOO, 580, 170, 0, 0.6, 0, 0)),
             c("B", r(SoundSynth.BAMBOO, 660, 130, 0, 0.5, 0, 0)),
             c("C", r(SoundSynth.BAMBOO, 560, 150, 0, 0.6, 0.5, 0))},
            // 松开：不出声 / 很轻一下
            {c("A", null),
             c("B", r(SoundSynth.WOOD, 1400, 20, 4000, 0.35, 0, 0))},
            // 鼠标按下：小木"嗒" / 收着的微动 / 小木鱼
            {c("A", r(SoundSynth.WOOD, 1500, 22, 5000, 0.35, 0, 0)),
             c("B", SWITCH_HELD),
             c("C", r(SoundSynth.WOOD, 1250, 30, 5000, 0.45, 1, 0))},
            // 鼠标松开
            {c("A", up(r(SoundSynth.WOOD, 1500, 22, 5000, 0.35, 0, 0))),
             c("B", up(SWITCH_HELD)),
             c("C", up(r(SoundSynth.WOOD, 1250, 30, 5000, 0.45, 1, 0)))},
            PAD,
            // 滚轮一格：小木刻度 / 细的
            {c("A", r(SoundSynth.WOOD, 1400, 6, 4500, 0.3, 0, 0)),
             c("B", r(SoundSynth.TICK, 1700, 5, 6000, 0, 0, 0))},
            // 惯性刹住
            {c("A", r(SoundSynth.WOOD, 1300, 25, 4500, 0.4, 0, 0)),
             c("B", r(SoundSynth.TICK, 1150, 7, 3500, 0, 0, 0))},
    };

    /** 鼠标松开：比按下高一点、短一点（真微动开关回弹那一下更轻更短）。 */
    private static Recipe up(Recipe down) {
        return down.scaled(1.08, 0.7, 1);
    }

    /** 某套某槽的候选（第 0 个是默认）。"关"没有候选（空数组）。 */
    public static Candidate[] candidates(int pack, int slot) {
        if (slot < 0 || slot >= SLOTS) return new Candidate[0];
        switch (pack) {
            case PACK_LIGHT: return LIGHT[slot];
            case PACK_WOOD: return WOOD[slot];
            default: return new Candidate[0];
        }
    }

    /**
     * 子声音的配方：鼠标的右键比左键高一点（×1.04），中键低一点、闷一点（×0.86、衰减 ×1.15、低通 ×0.7，"中键略闷"）；
     * 触控板松开高一点、短一点（×1.1、×0.7）。别的槽只有一个子声音，就是候选本身。
     */
    public static Recipe subRecipe(int slot, int sub, Recipe base) {
        if (base == null) return null;
        if (slot == SLOT_MOUSE_DOWN || slot == SLOT_MOUSE_UP) {
            if (sub == SUB_RIGHT) return base.scaled(1.04, 1, 1);
            if (sub == SUB_MIDDLE) return base.scaled(0.86, 1.15, 0.7);
            return base;
        }
        if (slot == SLOT_PAD && sub == SUB_PAD_UP) return base.scaled(1.1, 0.7, 1);
        return base;
    }

    // ═══════════ 预先算好的那一份 ═══════════

    /**
     * 某套声音按某个采样率算好的样子：data[槽][候选][子声音][变体] 是峰值 1 的采样（float，−1..1）。
     * 没算的候选（正式包只算默认的）、不出声的候选是 null。{@link #gain}(槽, 候选, 子声音) 是放的时候乘的增益（线性）：
     * 把这个声音（几个变体的平均）的响度补到 {@link #LOUDNESS_REF_DB} + {@link #levelDb}。
     * 一旦建好就不改（混音线程只读），换套 / 换采样率就整份重建。
     */
    public static final class Bank {
        public final int sampleRate;
        public final int pack;
        final float[][][][][] data;
        /** gain[槽][候选][子声音]，没算的是 null。 */
        final float[][][] gain;
        /** 每个槽实际有几个候选（有些没算）。 */
        final int[] candidateCount;
        /** 合计多少字节（诊断用）。 */
        public final long bytes;

        Bank(int sampleRate, int pack, float[][][][][] data, float[][][] gain, int[] candidateCount, long bytes) {
            this.sampleRate = sampleRate;
            this.pack = pack;
            this.data = data;
            this.gain = gain;
            this.candidateCount = candidateCount;
            this.bytes = bytes;
        }

        /** 这个候选算了没有（不出声的候选也算"算了"）。 */
        public boolean has(int slot, int cand) {
            if (slot < 0 || slot >= SLOTS || cand < 0 || cand >= candidateCount[slot]) return false;
            Candidate[] cs = SoundBank.candidates(pack, slot);
            return cs[cand].recipe == null || data[slot][cand] != null;
        }

        /** 变体的采样；null = 不出声。 */
        public float[] sample(int slot, int cand, int sub, int variant) {
            float[][][] c = data[slot][cand];
            if (c == null || sub >= c.length || c[sub] == null) return null;
            return c[sub][variant];
        }

        public int candidates(int slot) {
            return candidateCount[slot];
        }

        /** 放这个声音时乘的增益（响度补到电平表那么响）；没算的 / 不出声的是 0。 */
        public float gain(int slot, int cand, int sub) {
            if (slot < 0 || slot >= SLOTS || cand < 0 || cand >= gain[slot].length) return 0;
            float[] g = gain[slot][cand];
            return g == null || sub < 0 || sub >= g.length ? 0 : g[sub];
        }
    }

    /**
     * 按采样率算一套。all = 每个槽所有候选都算（调试版：候选区随便切不用重算）；否则只算 choice[槽] 那一个（null 或越界 = 第 0 个）。
     * 同样的参数结果逐个采样一样（种子是固定的）。"关"返回 null。
     */
    public static Bank build(int sampleRate, int pack, boolean all, int[] choice) {
        if (pack <= PACK_OFF || pack >= PACKS) return null;
        float[][][][][] data = new float[SLOTS][][][][];
        float[][][] gain = new float[SLOTS][][];
        int[] count = new int[SLOTS];
        long bytes = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            Candidate[] cs = candidates(pack, slot);
            count[slot] = cs.length;
            data[slot] = new float[cs.length][][][];
            gain[slot] = new float[cs.length][];
            int subs = subs(slot);
            int pick = choice != null && slot < choice.length && choice[slot] >= 0 && choice[slot] < cs.length ? choice[slot] : 0;
            for (int ci = 0; ci < cs.length; ci++) {
                if (!all && ci != pick) continue;
                if (cs[ci].recipe == null) continue;
                float[][][] subData = new float[subs][VARIANTS][];
                float[] subGain = new float[subs];
                for (int sub = 0; sub < subs; sub++) {
                    Recipe rec = subRecipe(slot, sub, cs[ci].recipe);
                    double energy = 0;
                    for (int v = 0; v < VARIANTS; v++) {
                        subData[sub][v] = rec.render(sampleRate, seed(pack, slot, ci, sub, v));
                        bytes += 4L * subData[sub][v].length;
                        energy += Math.pow(10, loudnessDb(subData[sub][v], sampleRate) / 10) / VARIANTS;
                    }
                    // 几个变体平均的响度补到 参考 + 电平（变体之间那一点差别留着，是"每一下略有不同"的一部分）
                    subGain[sub] = (float) Math.pow(10, (LOUDNESS_REF_DB + levelDb(pack, slot, sub) - 10 * Math.log10(energy)) / 20);
                }
                data[slot][ci] = subData;
                gain[slot][ci] = subGain;
            }
        }
        return new Bank(sampleRate, pack, data, gain, count, bytes);
    }

    /** 每个变体的种子：只跟 套 / 槽 / 候选 / 子声音 / 第几个 有关。 */
    static long seed(int pack, int slot, int cand, int sub, int variant) {
        return (((pack * 31L + slot) * 31 + cand) * 31 + sub) * 31 + variant + 1;
    }
}
