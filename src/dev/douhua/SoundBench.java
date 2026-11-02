package dev.douhua;

/**
 * 设置 → 外观 → 声音那一节的纯 Java 部分（声音第一批第 ④ 步），离线测（SoundBenchTest）。SettingsActivity 只管画和收触摸，这里定：
 * <ul>
 * <li>试听台（正式版也有）：一排键 ⇧ a s d ⌫ Space ↵（{@link #KEYS}，键 id 就是用法码，同豆花布局的键 id，走 SoundEvents 同一套规则）、
 *     一小块触控板（{@link Pad}：轻点 = 点击、划 = 滚轮几格，容差和轻点多久照 GestureEngine.Config）、三个鼠标键。</li>
 * <li>点一项套装时放的那一小段（{@link #DEMO}）。</li>
 * <li>调试版候选区：每个槽点一下放什么、震什么（{@link #hit}、{@link #haptic}），"连打"（每秒 {@link #BURST_HZ} 下连放 {@link #BURST_MS} 毫秒，按键的轮着换几个键），
 *     "打乱顺序"（{@link #shuffled}），微调滑块的范围（音量偏移、随机幅度、震动延后）。</li>
 * </ul>
 */
public final class SoundBench {
    private SoundBench() {}

    // ═══════════ 试听台的一排键 ═══════════

    /** ⇧ a s d ⌫ Space ↵ 的键 id（= 用法码，没有自带修饰键）。 */
    public static final int[] KEYS = {0xE1, 0x04, 0x16, 0x07, 0x2A, 0x2C, 0x28};
    /** 键帽上的字（键名不翻译，同豆花布局：空格写 Space）。 */
    public static final String[] KEY_LABELS = {"⇧", "a", "s", "d", "⌫", "Space", "↵"};
    /** 宽度的份数（字母键 1 份）。 */
    public static final float[] KEY_WEIGHTS = {1.4f, 1, 1, 1, 1.4f, 2.6f, 1.4f};

    /** 第 i 个键是不是功能键（主题按它分主次上色）：同 KeyRows.functionKey——字母、空格是普通键，⇧ ⌫ ↵ 是功能键。 */
    public static boolean keyFn(int i) {
        int u = KEYS[i] & 0xFF;
        return !(u >= 0x04 && u <= 0x27 || u == 0x2C);
    }

    // ═══════════ 点一项套装时放的那一小段 ═══════════

    /**
     * {毫秒, 声音 id, key}：打一个词（h e y）、空格、回车，再点一下触控板、滚两格。只放声音、不震（"点一项就放一遍"，听这一套是什么样）；
     * 按时间排好，最后一个不到 1.5 秒。键的按下到松开 70 毫秒上下（真打字的按住时长）。
     */
    public static final int[][] DEMO = {
            {0, SoundBank.KEY_DOWN, 0x0B}, {70, SoundBank.KEY_UP, 0x0B},
            {150, SoundBank.KEY_DOWN, 0x08}, {215, SoundBank.KEY_UP, 0x08},
            {300, SoundBank.KEY_DOWN, 0x1C}, {370, SoundBank.KEY_UP, 0x1C},
            {470, SoundBank.KEY_DOWN, 0x2C}, {560, SoundBank.KEY_UP, 0x2C},
            {680, SoundBank.KEY_DOWN, 0x28}, {780, SoundBank.KEY_UP, 0x28},
            {980, SoundBank.PAD_DOWN, 0}, {980 + SoundEvents.CLICK_GAP_MS, SoundBank.PAD_UP, 0},
            {1200, SoundBank.SCROLL, 0}, {1300, SoundBank.SCROLL, 0},
    };

    // ═══════════ 调试版候选区 ═══════════

    /** 候选区每个槽点一下震什么：不震 / 按键那个能打断的"嗒"（Haptics.playKey）/ 别的是 GestureEngine.HAPTIC_*（Haptics.play）。 */
    public static final int HAPTIC_NONE = -1, HAPTIC_KEY = -2;

    /**
     * "连打"：每秒几下、连放多久（毫秒）、一共几下、两下隔多久。方案写的是每秒 8 下，可 8 下正好间隔 125 ms、压在"打得快"的门槛上
     * （SoundVary.FAST_MS），按块对齐、Handler 抖几毫秒就有三成的下忽轻忽重（审查发现）：用 7 下（隔 142 ms，离门槛和回差都有余量），听的是正常打字的那一档。
     */
    public static final int BURST_HZ = 7, BURST_MS = 2000;
    public static final int BURST_HITS = BURST_HZ * BURST_MS / 1000;
    public static final int BURST_GAP_MS = 1000 / BURST_HZ;
    /** 连打时一下按键里按下到松开隔多久（真打字的按住时长）。 */
    public static final int KEY_HOLD_MS = 70;

    /** 调试版微调滑块的范围：音量偏移（dB，同 SoundMixer.setOffsetDb）、随机幅度（百分数，100 = 方案的数，同 SoundVary 最多 3 倍）、震动延后（毫秒）。 */
    public static final int OFFSET_MIN_DB = -20, OFFSET_MAX_DB = 6;
    public static final int RANDOM_MAX_PCT = 300, RANDOM_STEP_PCT = 10;
    public static final int DELAY_MAX_MS = 40, DELAY_STEP_MS = 2;

    /** 震动延后存坏了截到范围里。 */
    public static int clampDelay(int ms) {
        return Math.max(0, Math.min(DELAY_MAX_MS, ms));
    }

    /** 点一下候选（和连打的每一下）震什么：照主界面同一种操作的震动（键按下"嗒"、鼠标 / 触控板点一下、滚一格咔嗒、惯性刹住），松开不震。 */
    public static int haptic(int slot) {
        switch (slot) {
            case SoundBank.SLOT_KEY:
            case SoundBank.SLOT_BIG:
            case SoundBank.SLOT_SPACE:
            case SoundBank.SLOT_ENTER:
                return HAPTIC_KEY;
            case SoundBank.SLOT_MOUSE_DOWN:
            case SoundBank.SLOT_PAD:
                return GestureEngine.HAPTIC_CLICK;
            case SoundBank.SLOT_SCROLL:
                return GestureEngine.HAPTIC_TICK;
            case SoundBank.SLOT_CATCH:
                return GestureEngine.HAPTIC_SPIN_CATCH;
            default:   // 键松开、鼠标松开：松开不震
                return HAPTIC_NONE;
        }
    }

    /** 候选区的一下里要交出去的一个声音。candidate = 用候选区正在试的那个候选（Sounds.audition），否则用现在选的（Sounds.play）。 */
    public static final class Shot {
        public final int sound, key, delayMs;
        public final boolean candidate;

        Shot(int sound, int key, int delayMs, boolean candidate) {
            this.sound = sound;
            this.key = key;
            this.delayMs = delayMs;
            this.candidate = candidate;
        }
    }

    // 连打时按键轮着换的几个字母：上中下三排都有（行的音高不一样，同一个键老响像机关枪）
    private static final int[] BURST_LETTERS = {0x14, 0x04, 0x1D, 0x08, 0x16, 0x1B, 0x15, 0x07};
    // 大键轮着换退格、左 Shift
    private static final int[] BURST_BIG = {0x2A, 0xE1};

    /**
     * 候选区第 n 下（点一下 n = 0）放什么。点一下只放这个槽的声音（松开的槽只放松开，听得清）；
     * 连打的每一下是完整的一下：按键 = 按下 + {@link #KEY_HOLD_MS} 后松开，鼠标键 = 按下 + {@link SoundEvents#CLICK_GAP_MS} 后松开，
     * 正在试的那一半用候选、另一半用现在选的。触控板一直是一对（按下、松开都是这个候选的子声音）。
     */
    public static Shot[] hit(int slot, int n, boolean burst) {
        int key = 0;
        switch (slot) {
            case SoundBank.SLOT_KEY:
            case SoundBank.SLOT_KEY_UP:
                key = burst ? BURST_LETTERS[Math.floorMod(n, BURST_LETTERS.length)] : 0x04;
                break;
            case SoundBank.SLOT_BIG:
                key = burst ? BURST_BIG[Math.floorMod(n, BURST_BIG.length)] : 0x2A;
                break;
            case SoundBank.SLOT_SPACE:
                key = 0x2C;
                break;
            case SoundBank.SLOT_ENTER:
                key = 0x28;
                break;
            case SoundBank.SLOT_MOUSE_DOWN:
            case SoundBank.SLOT_MOUSE_UP:
                key = Proto.BUTTON_LEFT;
                break;
            default:
                break;
        }
        switch (slot) {
            case SoundBank.SLOT_KEY:
            case SoundBank.SLOT_BIG:
            case SoundBank.SLOT_SPACE:
            case SoundBank.SLOT_ENTER:
                return burst ? new Shot[]{new Shot(SoundBank.KEY_DOWN, key, 0, true), new Shot(SoundBank.KEY_UP, key, KEY_HOLD_MS, false)}
                        : new Shot[]{new Shot(SoundBank.KEY_DOWN, key, 0, true)};
            case SoundBank.SLOT_KEY_UP:
                return burst ? new Shot[]{new Shot(SoundBank.KEY_DOWN, key, 0, false), new Shot(SoundBank.KEY_UP, key, KEY_HOLD_MS, true)}
                        : new Shot[]{new Shot(SoundBank.KEY_UP, key, 0, true)};
            case SoundBank.SLOT_MOUSE_DOWN:
                return burst ? new Shot[]{new Shot(SoundBank.MOUSE_DOWN, key, 0, true),
                        new Shot(SoundBank.MOUSE_UP, key, SoundEvents.CLICK_GAP_MS, false)}
                        : new Shot[]{new Shot(SoundBank.MOUSE_DOWN, key, 0, true)};
            case SoundBank.SLOT_MOUSE_UP:
                return burst ? new Shot[]{new Shot(SoundBank.MOUSE_DOWN, key, 0, false),
                        new Shot(SoundBank.MOUSE_UP, key, SoundEvents.CLICK_GAP_MS, true)}
                        : new Shot[]{new Shot(SoundBank.MOUSE_UP, key, 0, true)};
            case SoundBank.SLOT_PAD:
                return new Shot[]{new Shot(SoundBank.PAD_DOWN, 0, 0, true), new Shot(SoundBank.PAD_UP, 0, SoundEvents.CLICK_GAP_MS, true)};
            case SoundBank.SLOT_SCROLL:
                return new Shot[]{new Shot(SoundBank.SCROLL, 0, 0, true)};
            case SoundBank.SLOT_CATCH:
                return new Shot[]{new Shot(SoundBank.CATCH, 0, 0, true)};
            default:
                return new Shot[0];
        }
    }

    /**
     * "打乱顺序"：n 个候选的一种排法（第 i 个按钮放 perm[i] 那个候选），同一个种子永远一样。打乱时按钮上照旧写 A B C D、参数不显示，
     * 免得记住"A 是哪一种"；关掉就照原来的顺序（揭晓）。
     */
    public static int[] shuffled(int n, long seed) {
        int[] p = new int[Math.max(0, n)];
        for (int i = 0; i < p.length; i++) p[i] = i;
        SoundSynth.Rng rng = new SoundSynth.Rng(seed);
        for (int i = p.length - 1; i > 0; i--) {
            int j = rng.below(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        return p;
    }

    /** 候选区第 i 个按钮背后是哪个候选：打乱时照种子排（{@link #shuffled}），不然照原来的顺序（第 i 个就是第 i 个）。 */
    public static int[] order(int n, boolean shuffle, long seed) {
        if (shuffle) return shuffled(n, seed);
        int[] p = new int[Math.max(0, n)];
        for (int i = 0; i < p.length; i++) p[i] = i;
        return p;
    }

    /** 第 i 个按钮上写的代号（A、B、C、D……）。 */
    public static String code(int i) {
        return String.valueOf((char) ('A' + i));
    }

    // ═══════════ 试听台的一小块触控板 ═══════════

    /**
     * 认轻点和划：按下到抬起没挪出轻点容差、不超过轻点的时长 = 轻点（抬起那一刻报，同分流）；挪出容差以后 = 滚动，手指每划过 step（像素）滚一格
     * （按走过的路长算，来回划也算）。只认一根手指（第二根不理），坐标是 view 里的像素。
     */
    public static final class Pad {
        private final float slop, step;
        private final long tapMs;
        private boolean down, moved;
        private float x0, y0, lastX, lastY;
        private long t0;
        private double acc;

        /** slopPx = 轻点容差、stepPx = 滚一格划多远（像素，&gt; 0）、tapMs = 轻点最长多久。 */
        public Pad(float slopPx, float stepPx, long tapMs) {
            this.slop = Math.max(0, slopPx);
            this.step = Math.max(1, stepPx);
            this.tapMs = tapMs;
        }

        public void down(float x, float y, long t) {
            down = true;
            moved = false;
            x0 = lastX = x;
            y0 = lastY = y;
            t0 = t;
            acc = 0;
        }

        /** 挪了：返回这一下滚过了几格（0 = 没有）。 */
        public int move(float x, float y, long t) {
            if (!down) return 0;
            if (!moved) {
                if (Math.hypot(x - x0, y - y0) <= slop) return 0;
                moved = true;
                lastX = x0;
                lastY = y0;
            }
            acc += Math.hypot(x - lastX, y - lastY);
            lastX = x;
            lastY = y;
            int n = (int) (acc / step);
            acc -= n * step;
            return n;
        }

        /** 抬起：先当最后一下挪动算格（返回值里不含，调用方先 move），再看是不是轻点。 */
        public boolean up(float x, float y, long t) {
            if (!down) return false;
            down = false;
            return !moved && Math.hypot(x - x0, y - y0) <= slop && t - t0 <= tapMs;
        }

        /** 被取消（页面滚走、退后台）：什么都不算。 */
        public void cancel() {
            down = false;
        }

        public boolean scrolling() {
            return down && moved;
        }
    }
}
