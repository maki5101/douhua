package dev.douhua;

/**
 * 变化规则（方案 2.6）：一次请求（声音 id + key）→ 放哪个槽的哪个子声音、哪个变体、变调多少音分、加减多少 dB、和谁互斥、要不要短尾巴。纯 Java，离线测（SoundTest）。
 * 只在混音线程里用（不加锁）；时间用混音器数过的帧（不看墙钟，测得准）。
 *
 * 两层变化：
 * <ul>
 * <li>确定的（同一个键永远像它自己，三条触摸路径、两块键盘一样，只看键 id 里的用法码）：
 *     类别（{@link #keyClass}：普通 / 功能 / 修饰 / 大键 / 空格 / 回车）定槽和轻重（修饰键轻 3 dB、功能键轻 1.5 dB），
 *     行（{@link #row}：F 行最高、空格那一排最低，+30 … −20 音分，{@link #rowCents}）。</li>
 * <li>每次的小随机：变体随机挑、不连着用同一个；音高 ±12 音分（鼠标 ±10、滚轮 ±8）、音量 ±1.2 dB。乘 {@link #setRandomScale}（调试版"随机幅度"滑块；
 *     0 = 一点不随机：变体也固定用第 0 个）。变体之间本来就差 ±16 音分（SoundSynth.VARIANT_F），两层加起来按键最多 ±28，在方案的 ±15–30 里。
 *     时间不随机：一律下一块开始就放。</li>
 * </ul>
 * 打得快（最近几下按键平均间隔 &lt; 125 ms，约 100 字 / 分钟）：轻 1.5–4 dB、换短尾巴；永远不会因为打得快就不出声（按下一定有声，只有音色和响度变）。
 * 进出有回差：平均间隔 &lt; {@link #FAST_MS} 才进、≥ {@link #FAST_EXIT_MS} 才出——每秒 8 下上下（间隔正好压在 125 ms 上、按块对齐抖几毫秒）
 * 不会一下轻 1.5 dB 短尾巴、一下又不轻地来回跳。
 * 滚轮一秒超过 25 格（离上一个放出来的不到 40 ms）就合并（这一格不放）、放出来的那一格轻 3 dB。
 */
public final class SoundVary {
    // ── 键的类别 ──
    public static final int CLASS_NORMAL = 0, CLASS_FUNCTION = 1, CLASS_MOD = 2, CLASS_BIG = 3, CLASS_SPACE = 4, CLASS_ENTER = 5;

    /** 每次随机的范围（音分 / dB），乘随机幅度。 */
    static final double KEY_CENTS = 12, MOUSE_CENTS = 10, TICK_CENTS = 8, RANDOM_DB = 1.2;
    /** 打得快：平均间隔低于它开始变（轻 FAST_DB_MIN）；到 FAST_FULL_MS 变到最多；变了以后平均间隔回到 FAST_EXIT_MS 才恢复（回差）。 */
    static final double FAST_MS = 125, FAST_EXIT_MS = 140, FAST_FULL_MS = 60, FAST_DB_MIN = 1.5, FAST_DB_MAX = 4;
    /** 间隔超过它不算"连着打"（重新数）。 */
    static final double TYPING_GAP_MS = 1000;
    /** 平均最近几个间隔。 */
    static final int TYPING_WINDOW = 4;
    /** 滚轮：离上一个放出来的不到它就合并（= 一秒 25 格）；合并过、或者间隔不到 SCROLL_QUIET_MS 时放出来的那格轻 SCROLL_FAST_DB。 */
    static final double SCROLL_MERGE_MS = 40, SCROLL_QUIET_MS = 80, SCROLL_FAST_DB = 3;

    /** 互斥的组：同一组的新声音开始时旧的淡掉（真键不会同时响两次）。高 32 位是"哪一类东西"，低 32 位是 key。 */
    static final long G_KEY = 1L << 32, G_MOUSE = 2L << 32, G_PAD = 3L << 32, G_SCROLL = 4L << 32, G_CATCH = 5L << 32, G_GAMEPAD = 6L << 32;

    private final SoundSynth.Rng rng;
    private double randomScale = 1;
    private final int[] lastVariant = new int[SoundBank.SLOTS * 4];
    // 打字速度
    private final long[] intervals = new long[TYPING_WINDOW];
    private int intervalCount, intervalNext;
    private long lastKeyFrame = Long.MIN_VALUE;
    /** 现在算不算"打得快"（回差：进出的门槛不一样）。 */
    private boolean fast;
    // 滚轮合并
    private long lastScrollFrame = Long.MIN_VALUE;
    private boolean scrollMerged;
    private int merged;
    private final Shot shot = new Shot();

    public SoundVary(long seed) {
        rng = new SoundSynth.Rng(seed);
        java.util.Arrays.fill(lastVariant, -1);
    }

    /** 随机幅度（0 = 不随机，1 = 方案的数，最多 3）。 */
    public void setRandomScale(double s) {
        randomScale = Math.max(0, Math.min(3, s));
    }

    /** 合并掉的滚轮格数（诊断 / 测试）。 */
    public int merged() {
        return merged;
    }

    /** 解出来的一次"放什么"。resolve 每次都用同一个对象（混音线程里不分配），拿到就用、别留着。 */
    public static final class Shot {
        public int slot, sub, variant;
        public double cents, db;
        public long group;
        public boolean shortTail;
    }

    /**
     * 一次请求 → 放什么；null = 不放（滚轮合并了、认不出的声音 id）。frame = 混音器现在数到第几帧，sr = 采样率。
     * 按下（KEY_DOWN）一定有结果。返回的是同一个对象，下一次 resolve 会改掉它。
     */
    public Shot resolve(int sound, int key, long frame, int sr) {
        Shot s = shot;
        s.sub = 0;
        s.cents = 0;
        s.db = 0;
        s.shortTail = false;
        switch (sound) {
            case SoundBank.KEY_DOWN:
            case SoundBank.KEY_UP: {
                boolean down = sound == SoundBank.KEY_DOWN;
                int cls = keyClass(key);
                s.slot = down ? slotOf(cls) : SoundBank.SLOT_KEY_UP;
                s.cents = rowCents(row(key)) + (down ? 0 : upCents(cls)) + rand(KEY_CENTS);
                s.db = classDb(cls) + rand(RANDOM_DB);
                s.group = G_KEY | (key & 0xFFFFFFFFL);
                if (down) {
                    double fast = typing(frame, sr);
                    if (fast >= 0) {
                        s.db -= FAST_DB_MIN + (FAST_DB_MAX - FAST_DB_MIN) * fast;
                        s.shortTail = true;
                    }
                }
                break;
            }
            case SoundBank.MOUSE_DOWN:
            case SoundBank.MOUSE_UP:
            case SoundBank.GAMEPAD_DOWN:
            case SoundBank.GAMEPAD_UP: {
                boolean gp = sound == SoundBank.GAMEPAD_DOWN || sound == SoundBank.GAMEPAD_UP;
                boolean down = sound == SoundBank.MOUSE_DOWN || sound == SoundBank.GAMEPAD_DOWN;
                s.slot = down ? SoundBank.SLOT_MOUSE_DOWN : SoundBank.SLOT_MOUSE_UP;
                s.sub = gp ? SoundBank.SUB_LEFT : buttonSub(key);
                s.cents = rand(MOUSE_CENTS);
                s.db = rand(RANDOM_DB) - (gp ? 2 : 0);
                s.group = (gp ? G_GAMEPAD : G_MOUSE) | (key & 0xFFFFFFFFL);
                break;
            }
            case SoundBank.PAD_DOWN:
            case SoundBank.PAD_UP:
                s.slot = SoundBank.SLOT_PAD;
                s.sub = sound == SoundBank.PAD_DOWN ? SoundBank.SUB_PAD_DOWN : SoundBank.SUB_PAD_UP;
                s.cents = rand(MOUSE_CENTS);
                s.db = rand(RANDOM_DB);
                s.group = G_PAD;
                break;
            case SoundBank.SCROLL:
            case SoundBank.GAMEPAD_TICK: {
                boolean gp = sound == SoundBank.GAMEPAD_TICK;
                double since = lastScrollFrame == Long.MIN_VALUE ? Double.MAX_VALUE : (frame - lastScrollFrame) * 1000.0 / sr;
                if (since < SCROLL_MERGE_MS) {
                    scrollMerged = true;
                    merged++;
                    return null;
                }
                s.slot = SoundBank.SLOT_SCROLL;
                s.cents = rand(TICK_CENTS);
                s.db = rand(RANDOM_DB) - (scrollMerged || since < SCROLL_QUIET_MS ? SCROLL_FAST_DB : 0) - (gp ? 2 : 0);
                s.group = gp ? G_GAMEPAD : G_SCROLL;
                lastScrollFrame = frame;
                scrollMerged = false;
                break;
            }
            case SoundBank.CATCH:
                s.slot = SoundBank.SLOT_CATCH;
                s.cents = rand(TICK_CENTS);
                s.db = rand(RANDOM_DB);
                s.group = G_CATCH;
                break;
            default:
                return null;
        }
        return s;
    }

    /** 挑一个变体：随机、不和这个子声音上一次的一样。随机幅度是 0 时一律第 0 个（调试版拖到 0 = 每一下完全一样）。 */
    public int pickVariant(int slot, int sub, int n) {
        int at = slot * 4 + Math.min(sub, 3);
        int last = lastVariant[at];
        int v;
        if (n <= 1 || randomScale <= 0) {
            v = 0;
        } else if (last < 0 || last >= n) {
            v = rng.below(n);
        } else {
            v = rng.below(n - 1);
            if (v >= last) v++;
        }
        lastVariant[at] = v;
        return v;
    }

    private double rand(double range) {
        return range * randomScale * rng.signed();
    }

    /**
     * 打得快到什么程度：−1 = 不快（或刚开始打），0..1 = 快（0 = 刚算快、轻 FAST_DB_MIN，1 = 最快那一档）。按下时记一次间隔，平均最近 {@link #TYPING_WINDOW} 个；
     * 间隔超过 1 秒重新数。至少两个间隔才算。进出有回差（{@link #FAST_MS} 进、{@link #FAST_EXIT_MS} 出）。
     */
    private double typing(long frame, int sr) {
        if (lastKeyFrame != Long.MIN_VALUE) {
            long gap = frame - lastKeyFrame;
            if (gap * 1000.0 / sr > TYPING_GAP_MS) {
                intervalCount = 0;
                intervalNext = 0;
            } else {
                intervals[intervalNext] = gap;
                intervalNext = (intervalNext + 1) % TYPING_WINDOW;
                intervalCount = Math.min(TYPING_WINDOW, intervalCount + 1);
            }
        }
        lastKeyFrame = frame;
        if (intervalCount < 2) {
            fast = false;
            return -1;
        }
        long sum = 0;
        for (int i = 0; i < intervalCount; i++) sum += intervals[i];
        double avgMs = sum * 1000.0 / sr / intervalCount;
        fast = avgMs < (fast ? FAST_EXIT_MS : FAST_MS);
        if (!fast) return -1;
        return Math.max(0, Math.min(1, (FAST_MS - avgMs) / (FAST_MS - FAST_FULL_MS)));
    }

    // ═══════════ 确定的那一层（只看键 id） ═══════════

    /**
     * 键 id 里"这个物理键"的用法码：普通键 / 组合键取低 8 位（自带修饰键不算），合并键取点一下的那个，带 Fn 层的键取底层，
     * F 行键取底层（F1–F12、Esc）；Fn 键自己没有用法码，返回 -1；别的（界面上的特殊键传进来的 0 之类）原样取低 8 位。
     */
    public static int usage(int keyId) {
        if (Proto.isFnKey(keyId)) return -1;
        if (Proto.isFrowKey(keyId)) return Proto.frowBase(keyId);
        if (Proto.isFnLayerKey(keyId)) return Proto.fnBase(keyId) & 0xFF;
        if (Proto.isDualKey(keyId)) return Proto.dualTap(keyId) & 0xFF;
        return keyId & 0xFF;
    }

    /** 键的类别（定槽和轻重）。Shift 是带卫星轴的大键（略沉），别的修饰键轻一点；Fn 键算修饰键。 */
    public static int keyClass(int keyId) {
        int u = usage(keyId);
        if (u < 0) return CLASS_MOD;
        switch (u) {
            case 0x2C: return CLASS_SPACE;
            case 0x28: case 0x58: return CLASS_ENTER;   // 回车、小键盘回车
            case 0x2A: case 0xE1: case 0xE5: case 0x57: case 0x62: return CLASS_BIG;   // 退格、两个 Shift、小键盘 + 和 0
            default: break;
        }
        if (u >= 0xE0 && u <= 0xE7) return CLASS_MOD;
        if (u == 0x29 || u == 0x2B || u == 0x39 || (u >= 0x3A && u <= 0x52) || (u >= 0x65 && u <= 0x86 && u != 0x85)) return CLASS_FUNCTION;
        return CLASS_NORMAL;
    }

    /** 类别 → 按下放哪个槽。 */
    public static int slotOf(int cls) {
        switch (cls) {
            case CLASS_SPACE: return SoundBank.SLOT_SPACE;
            case CLASS_ENTER: return SoundBank.SLOT_ENTER;
            case CLASS_BIG: return SoundBank.SLOT_BIG;
            default: return SoundBank.SLOT_KEY;
        }
    }

    /** 类别的轻重（dB）：修饰键轻 3、功能键轻 1.5；Shift 归大键（带卫星轴、略沉，用大键那个槽），不另外轻（见 {@link #keyClass}）。 */
    public static double classDb(int cls) {
        switch (cls) {
            case CLASS_MOD: return -3;
            case CLASS_FUNCTION: return -1.5;
            default: return 0;
        }
    }

    /** 大键、空格、回车松开时低一点（键大、回弹慢）。 */
    static double upCents(int cls) {
        switch (cls) {
            case CLASS_SPACE: return -150;
            case CLASS_BIG: case CLASS_ENTER: return -100;
            default: return 0;
        }
    }

    /**
     * 用法码在美式键盘上是第几排：0 = F 行（Esc、F1–F24、PrtSc ScrLk Pause），1 = 数字行（含 Ins Home PgUp、小键盘最上一排），
     * 2 = QWERTY 那一排，3 = ASDF 那一排（认不出的也当这一排），4 = ZXCV 那一排，5 = 空格那一排（含 ← ↓ →、小键盘 0 .）。
     * 只看位置，不看键帽上印的字（德语的 Z 在 Y 的位置，照样是 ZXCV 那一排的音高——是物理位置在响）。
     */
    public static int row(int keyId) {
        int u = usage(keyId);
        if (u < 0) return 5;   // Fn 键在最下一排
        if (u == 0x29 || (u >= 0x3A && u <= 0x45) || (u >= 0x46 && u <= 0x48) || (u >= 0x68 && u <= 0x73)) return 0;
        if (u == 0x35 || (u >= 0x1E && u <= 0x27) || u == 0x2D || u == 0x2E || u == 0x2A
                || (u >= 0x49 && u <= 0x4B) || (u >= 0x53 && u <= 0x56)) return 1;
        switch (u) {
            case 0x2B: case 0x14: case 0x1A: case 0x08: case 0x15: case 0x17: case 0x1C: case 0x18: case 0x0C: case 0x12: case 0x13:
            case 0x2F: case 0x30: case 0x31: case 0x4C: case 0x4D: case 0x4E: case 0x5F: case 0x60: case 0x61: case 0x57:
                return 2;
            case 0x1D: case 0x1B: case 0x06: case 0x19: case 0x05: case 0x11: case 0x10: case 0x36: case 0x37: case 0x38:
            case 0xE1: case 0xE5: case 0x64: case 0x87: case 0x52: case 0x59: case 0x5A: case 0x5B: case 0x58:
                return 4;
            case 0x2C: case 0xE0: case 0xE2: case 0xE3: case 0xE4: case 0xE6: case 0xE7: case 0x65: case 0x50: case 0x51: case 0x4F:
            case 0x62: case 0x63: case 0x85:
                return 5;
            default:
                return 3;
        }
    }

    /** 行 → 音分：上排略亮略高，下排略低，ASDF 那一排不变（F 行 +30、数字行 +20、QWERTY +10、ASDF 0、ZXCV −10、空格那一排 −20）。 */
    public static double rowCents(int row) {
        return 10 * (3 - Math.max(0, Math.min(5, row)));
    }

    /** Proto.BUTTON_* → 鼠标的子声音（别的当左键）。 */
    public static int buttonSub(int button) {
        if (button == Proto.BUTTON_RIGHT) return SoundBank.SUB_RIGHT;
        if (button == Proto.BUTTON_MIDDLE) return SoundBank.SUB_MIDDLE;
        return SoundBank.SUB_LEFT;
    }
}
