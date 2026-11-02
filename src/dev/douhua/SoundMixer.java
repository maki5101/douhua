package dev.douhua;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 混音：把要放的几个声音叠在一起，过一道软限幅，交给 Sounds 写进那条常驻的低延迟音频管道。纯 Java，离线测（SoundTest）。
 *
 * 线程：{@link #post} 任何线程都能调（主线程按键时就调它：往无锁队列 {@link SoundQueue} 写一个 long，不等、不分配）；
 * 别的方法只在混音线程里调（Sounds 的 douhua-sound 线程，测试里就是调用的那个线程）。设置类的（音量、候选、随机幅度）是 volatile，哪个线程设都行。
 *
 * 规矩（方案 2.1）：
 * <ul>
 * <li>每一块（{@link #render}）开头把队列里的请求全取出来，一律从这一块的第 0 帧开始放（时间不随机、不往后挪）。
 *     例外是请求里写明了"晚多少毫秒"的（{@link #request(int, int, int, double, int)} 的 delayMs，事件层一次点击的那一对"咔哒"的"哒"）：
 *     从这一块第 0 帧往后数正好这么多帧开始，也是确定的。</li>
 * <li>同一组（同一个键的按下 / 松开、同一个鼠标键……，SoundVary 定）的新声音开始时，旧的 {@link #FADE_MS} 毫秒淡掉（真键不会同时响两次）。</li>
 * <li>同时最多 {@link #MAX_VOICES} 个声音（不算正在淡掉的），超了淡掉最老的那个；淡掉中的也占位置（池子 {@link #POOL} 个），池子满了硬切淡得只剩最少的那个。</li>
 * <li>总输出过软限幅（{@link #softClip}）：{@link #KNEE} 以下原样，以上平滑地压到不超过 1，叠多了不破音。</li>
 * </ul>
 * 响度：每个声音乘 SoundBank 算好的增益（Bank.gain：按它自己的响度补到电平表那么响，普通键按下是 SoundBank.LOUDNESS_REF_DB），再加减 SoundVary 的 dB、
 * 乘音量滑块。单个声音的峰值都在 {@link #KNEE} 以下（SoundTest 锁住），几个叠起来才进软限幅。最后再乘手机"系统"那档音量（系统做的）。
 */
public final class SoundMixer {
    /** 同时最多几个声音（不算正在淡掉的）。 */
    public static final int MAX_VOICES = 8;
    /** 池子（含正在淡掉的）：8 个活着的 + 8 个在淡的，一毫秒来一个也用不着硬切。 */
    static final int POOL = 16;
    /** 互斥、抢占时淡掉用多久（方案：5–10 ms）。 */
    public static final double FADE_MS = 6;
    /** 软限幅从这里开始压。 */
    public static final double KNEE = 0.5;
    /** 打得快时的短尾巴：只放到原长的这么多（最短 30 ms），最后 8 ms 淡出。 */
    static final double SHORT_TAIL = 0.6, SHORT_TAIL_MIN_MS = 30, SHORT_FADE_MS = 8;

    private final SoundQueue queue = new SoundQueue(64);
    private final SoundVary vary;
    private final Voice[] pool = new Voice[POOL];
    private SoundBank.Bank bank;
    private float[] mix = new float[0];
    private long frame;
    private long lastBusyFrame;

    private volatile int[] choice = new int[SoundBank.SLOTS];
    private volatile float volume = 1;
    private volatile float offset = 1;
    private volatile double randomScale = 1;

    // 诊断
    private final AtomicInteger dropped = new AtomicInteger();
    private volatile int started, stolen, hardCut;

    public SoundMixer(long seed) {
        vary = new SoundVary(seed);
        for (int i = 0; i < POOL; i++) pool[i] = new Voice();
    }

    // ═══════════ 请求（任何线程） ═══════════

    private static final long VALID = 1L << 62;

    /** 打包一个请求：声音 id（SoundBank.KEY_DOWN…）+ key。用设置里选的候选。 */
    public static long request(int sound, int key) {
        return request(sound, key, -1, 0);
    }

    /**
     * 打包一个请求。cand ≥ 0 = 这一次用指定的候选（调试版候选区试听），-1 = 用设置里选的；trimDb ≤ 0 = 这一次再轻多少（0.25 dB 一格，最多 −63.75）。
     */
    public static long request(int sound, int key, int cand, double trimDb) {
        return request(sound, key, cand, trimDb, 0);
    }

    /**
     * 同上，delayMs = 晚多少毫秒再开始（0–{@link #MAX_DELAY_MS}，超出的截掉）：一次点击的一对"咔哒"里的"哒"（SoundEvents.CLICK_GAP_MS）。
     * 晚放的那个交的时候不淡掉同一组还在响的（不然一对里的"哒"会把刚开始的"咔"掐掉）；它等着的时候被同一组新来的淡掉 / 被抢占，就整个不放了。
     */
    public static long request(int sound, int key, int cand, double trimDb, int delayMs) {
        long trim = Math.max(0, Math.min(255, Math.round(-trimDb * 4)));
        long c = cand < 0 ? 0 : Math.min(15, cand + 1);
        long delay = Math.max(0, Math.min(MAX_DELAY_MS, delayMs));
        return VALID | ((long) (sound & 0xF) << 56) | (c << 52) | (trim << 40) | (delay << 32) | (key & 0xFFFFFFFFL);
    }

    /** 请求里能写的最长延后（毫秒，8 位）。 */
    public static final int MAX_DELAY_MS = 255;

    static int reqSound(long r) {
        return (int) ((r >>> 56) & 0xF);
    }

    static int reqCand(long r) {
        return (int) ((r >>> 52) & 0xF) - 1;
    }

    static double reqTrimDb(long r) {
        return -((r >>> 40) & 0xFF) / 4.0;
    }

    static int reqKey(long r) {
        return (int) r;
    }

    static int reqDelayMs(long r) {
        return (int) ((r >>> 32) & 0xFF);
    }

    /** 交一个请求（不等、不分配）。队列满了丢掉这一个、记一次，返回 false。 */
    public boolean post(long req) {
        if (queue.offer(req)) return true;
        dropped.incrementAndGet();
        return false;
    }

    // ═══════════ 设置（任何线程） ═══════════

    /** 音量（线性，0–1；设置页滑块经 {@link #volumeGain} 换算）。 */
    public void setVolume(float gain) {
        volume = Math.max(0, Math.min(1, gain));
    }

    /** 调试版"音量偏移"（dB，−20..+6）。 */
    public void setOffsetDb(double db) {
        offset = (float) Math.pow(10, Math.max(-20, Math.min(6, db)) / 20);
    }

    /** 调试版"随机幅度"（0 不随机、1 方案的数）。 */
    public void setRandomScale(double s) {
        randomScale = s;
    }

    /** 每个槽用哪个候选（长度不够 / null 的槽用第 0 个）。 */
    public void setChoices(int[] c) {
        int[] n = new int[SoundBank.SLOTS];
        if (c != null) System.arraycopy(c, 0, n, 0, Math.min(c.length, n.length));
        choice = n;
    }

    /**
     * 设置页音量滑块 0–100 → 线性增益：100 = 0 dB，每少 1 格少 0.4 dB（50 = −20 dB，1 = −39.6 dB），0 = 静音。
     */
    public static float volumeGain(int percent) {
        if (percent <= 0) return 0;
        return (float) Math.pow(10, (Math.min(100, percent) - 100) * 0.4 / 20);
    }

    // ═══════════ 混音线程 ═══════════

    /** 换一份声音（换套 / 换采样率之后）：正在放的全停、队列清空（建的时候按下的那几下不晚放）。null = 不出声。 */
    public void setBank(SoundBank.Bank b) {
        bank = b;
        for (Voice v : pool) v.data = null;
        queue.clear();
    }

    public SoundBank.Bank bank() {
        return bank;
    }

    /** 全停、队列清空（换路由、重开管道时）。 */
    public void reset() {
        for (Voice v : pool) v.data = null;
        queue.clear();
    }

    /** 混一块（frames 帧，单声道），输出在 −1..1 之间。 */
    public void render(float[] out, int frames) {
        if (mix.length < frames) mix = new float[frames];
        java.util.Arrays.fill(mix, 0, frames, 0f);
        long r;
        boolean busy = false;
        while ((r = queue.poll()) != 0) {
            start(r);
            busy = true;
        }
        for (Voice v : pool) {
            if (v.data == null) continue;
            v.mix(mix, frames);
            busy = true;
        }
        float g = volume * offset;
        for (int i = 0; i < frames; i++) out[i] = (float) softClip(mix[i] * g);
        frame += frames;
        if (busy) lastBusyFrame = frame;
    }

    private float[] tmp = new float[0];

    /** 混一块，写成 16 位。 */
    public void render(short[] out, int frames) {
        if (tmp.length < frames) tmp = new float[frames];
        render(tmp, frames);
        for (int i = 0; i < frames; i++) out[i] = (short) Math.round(tmp[i] * 32767);
    }

    /** 软限幅：|x| ≤ KNEE 原样；以上 KNEE + (1 − KNEE)·tanh((|x| − KNEE) / (1 − KNEE))，连续、斜率连续、不超过 1（很大的输入算出来正好是 1）。 */
    public static double softClip(double x) {
        double a = Math.abs(x);
        if (a <= KNEE) return x;
        double y = KNEE + (1 - KNEE) * Math.tanh((a - KNEE) / (1 - KNEE));
        return x < 0 ? -y : y;
    }

    private void start(long req) {
        if (bank == null) return;
        vary.setRandomScale(randomScale);
        SoundVary.Shot s = vary.resolve(reqSound(req), reqKey(req), frame, bank.sampleRate);
        if (s == null) return;
        int cand = reqCand(req);
        if (cand < 0) {
            int[] ch = choice;
            cand = s.slot < ch.length ? ch[s.slot] : 0;
        }
        if (!bank.has(s.slot, cand)) cand = firstPresent(s.slot);
        if (cand < 0) return;
        int variant = vary.pickVariant(s.slot, s.sub, SoundBank.VARIANTS);
        float[] data = bank.sample(s.slot, cand, s.sub, variant);
        if (data == null) return;   // 这个候选不出声（比如木与枝的松开）：也不打断同一个键还在响的按下
        double gain = bank.gain(s.slot, cand, s.sub) * Math.pow(10, (s.db + reqTrimDb(req)) / 20);
        double rate = Math.pow(2, s.cents / 1200);
        int fade = Math.max(1, (int) Math.round(FADE_MS * bank.sampleRate / 1000));
        int wait = (int) Math.round(reqDelayMs(req) * (double) bank.sampleRate / 1000);
        // 同一组的旧声音淡掉（晚放的不淡：一对"咔哒"的"哒"交的时候，同一组的"咔"才刚开始）
        if (wait == 0) for (Voice v : pool) if (v.data != null && v.group == s.group) v.fadeOut(fade);
        // 超了：淡掉最老的
        while (true) {
            int live = 0;
            Voice oldest = null;
            for (Voice v : pool) {
                if (v.data == null || v.fading) continue;
                live++;
                if (oldest == null || v.startFrame < oldest.startFrame) oldest = v;
            }
            if (live < MAX_VOICES) break;
            oldest.fadeOut(fade);
            stolen++;
        }
        Voice free = null;
        for (Voice v : pool) {
            if (v.data == null) {
                free = v;
                break;
            }
        }
        if (free == null) {   // 池子满了（活着的最多 8 个，剩下的都在淡）：硬切淡得只剩最少的那个
            for (Voice v : pool) if (v.fading && (free == null || v.fadeLeft < free.fadeLeft)) free = v;
            hardCut++;
        }
        int shortLen = s.shortTail ? (int) Math.max(data.length * SHORT_TAIL, SHORT_TAIL_MIN_MS * bank.sampleRate / 1000) : data.length;
        free.begin(data, rate, (float) gain, s.group, frame + wait, Math.min(data.length, shortLen),
                (int) Math.round(SHORT_FADE_MS * bank.sampleRate / 1000), wait);
        started++;
    }

    private int firstPresent(int slot) {
        for (int c = 0; c < bank.candidates(slot); c++) if (bank.has(slot, c)) return c;
        return -1;
    }

    // ═══════════ 诊断 / 测试 ═══════════

    /** 正在响的声音（含正在淡掉的）。 */
    public int voices() {
        int n = 0;
        for (Voice v : pool) if (v.data != null) n++;
        return n;
    }

    /** 不算正在淡掉的。 */
    public int liveVoices() {
        int n = 0;
        for (Voice v : pool) if (v.data != null && !v.fading) n++;
        return n;
    }

    /** 混过多少帧。 */
    public long frame() {
        return frame;
    }

    /** 离上一次有声音 / 有请求过了多少帧（Sounds 判断要不要暂停管道）。 */
    public long idleFrames() {
        return frame - lastBusyFrame;
    }

    /** 队列空、没有声音在响。 */
    public boolean quiet() {
        return queue.isEmpty() && voices() == 0;
    }

    public int dropped() {
        return dropped.get();
    }

    public int started() {
        return started;
    }

    public int stolen() {
        return stolen;
    }

    public int hardCut() {
        return hardCut;
    }

    public int merged() {
        return vary.merged();
    }

    /** 一个声音：放 data（按 rate 变调，线性插值）、乘 gain、可能在淡掉 / 短尾巴、可能还在等（晚放的）。 */
    private static final class Voice {
        float[] data;
        double pos, rate;
        float gain;
        long group, startFrame;   // startFrame：真正开始的那一帧（晚放的往后算），抢占时比谁老
        int end;          // 放到哪（采样）
        int tailStart;    // 从哪开始额外淡出（短尾巴；= end 表示没有）
        boolean fading;
        int fadeLeft, fadeTotal;
        int wait;         // 还要等多少帧才开始（晚放的；0 = 已经在放）

        void begin(float[] d, double rate, float gain, long group, long startFrame, int end, int tailFade, int wait) {
            this.data = d;
            this.wait = wait;
            this.pos = 0;
            this.rate = rate;
            this.gain = gain;
            this.group = group;
            this.startFrame = startFrame;
            this.end = end;
            this.tailStart = end < d.length ? Math.max(0, end - tailFade) : end;
            this.fading = false;
            this.fadeLeft = 0;
            this.fadeTotal = 0;
        }

        void fadeOut(int frames) {
            if (fading) {
                if (fadeLeft > frames) {   // 已经在淡：剩下的照比例接着淡，不会变响
                    fadeTotal = (int) Math.max(1, (long) fadeTotal * frames / fadeLeft);
                    fadeLeft = frames;
                }
                return;
            }
            fading = true;
            fadeLeft = frames;
            fadeTotal = frames;
        }

        void mix(float[] out, int frames) {
            float[] d = data;
            int len = d.length;
            for (int i = 0; i < frames; i++) {
                if (wait > 0) {
                    if (fading) {   // 还没开始就被淡掉（同一组新来的、被抢占）：整个不放
                        data = null;
                        return;
                    }
                    wait--;
                    continue;
                }
                if (pos >= end) {
                    data = null;
                    return;
                }
                int idx = (int) pos;
                double frac = pos - idx;
                float s0 = d[idx];
                float s1 = idx + 1 < len ? d[idx + 1] : 0f;
                double s = s0 + (s1 - s0) * frac;
                double env = 1;
                if (pos > tailStart) env = (end - pos) / (end - tailStart);
                if (fading) {
                    env *= (double) fadeLeft / fadeTotal;
                    if (--fadeLeft <= 0) {
                        out[i] += (float) (s * gain * env);
                        data = null;
                        return;
                    }
                }
                out[i] += (float) (s * gain * env);
                pos += rate;
            }
        }
    }
}
