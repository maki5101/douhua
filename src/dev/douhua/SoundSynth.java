package dev.douhua;

/**
 * 合成的声音（声音第一批全是合成的：轻、鼠标微动开关、像 MacBook 触控板的"咔"、滚轮刻度、木块 / 木鱼、细树枝折断、竹筒）。纯 Java，离线测（SoundTest）。
 *
 * 做法都是"模态合成"：一下激励（锤头的半个正弦脉冲、一小段衰减的噪声、一个冲击）喂进几个二阶共振器（每个共振器 = 一个按 T60 衰减的正弦），
 * 再过一道统一的收尾（{@link #finish}）：切掉 300 Hz 以下（手机喇叭放不出、还会让声音破）、在 2.5–5.5 kHz 挖一个宽的凹（这一段加上每秒几下的重复最难听，
 * 方案 2.6 引 Kumar 2008）、剪掉开头的静音、按 −60 dB 剪尾巴再补 3 ms 淡出、峰值归一到 1。
 * 锤头越软（脉冲越宽），高频越少：半个正弦宽 w 秒时频谱第一个零点在 1.5 / w，木头、竹筒靠它把 2.5 kHz 以上的分音自然压下去。
 * 思路参考了 Farnell《Designing Sound》和 STK（ModalBar 的木头、Shakers 的随机碰撞），代码是照公式自己写的，没有抄。
 *
 * 同一个配方、同一个种子，结果逐个采样一样（{@link Rng} 是自己写的 SplitMix64，不依赖平台的随机数）；种子不同时共振频率差 ±{@link #VARIANT_F}（约 ±16 音分，
 * 和混音时每一下的随机音高 SoundVary 加起来不超过方案的 ±15–30 音分）、T60 差 ±5%、噪声不同，这就是"每一下都略有不同"的变体（SoundBank 每个声音预先算 {@link SoundBank#VARIANTS} 个）。
 * 手机喇叭 500–1000 Hz 以下衰减很快（方案 1.5）：要"偏低、很实"的声音（像 MacBook 触控板、竹筒）主共振放在 550 Hz 以上，靠阻尼、压高频显得实，
 * 或者只放低音的 2、3、4 倍泛音、让耳朵自己听出那个低音（{@link #TAPTIC_LOW}）；能量大半在 500 Hz 以下的声音在手机上几乎听不见（SoundTest 锁住）。
 * 声音好不好听这里证明不了：测试只锁住时长、峰值、开头没有静音、频谱、低频切掉了、可复现。
 */
public final class SoundSynth {
    private SoundSynth() {}

    /** 低于它的切掉（4 阶巴特沃斯高通的 −3 dB 点）。 */
    public static final double HIGHPASS_HZ = 300;
    /** 凹的中心、深度、Q：从 2.5k 到 5.5k 都压下去几 dB，中心压得最深。 */
    static final double DIP_HZ = 3700, DIP_DB = -9, DIP_Q = 0.75;
    /** 剪尾巴：最后一个高于峰值 −60 dB 的采样之后不要；再补这么长的淡出，免得最后一下"咔"。 */
    static final double TAIL_DB = -60, END_FADE_MS = 3;
    /** 开头：第一个高于峰值 1% 的采样之前的剪掉（实际上激励从第 0 个采样就开始，基本剪不到什么）。 */
    static final double LEAD_GATE = 0.01;
    /** 算的时候先留这么长，收尾时按 −60 dB 剪短。 */
    static final double MAX_SECONDS = 0.3;
    /** 轻那一套（{@link #TAP}）另外再压的低频（二阶高通）。 */
    static final double TAP_HIGHPASS_HZ = 450;
    /** 变体之间共振频率差多少（±0.9% ≈ ±16 音分）。 */
    static final double VARIANT_F = 0.009;
    /** 树枝：第一下之后的小裂合起来最响不超过第一下的这么多（叠在第一下还在响的纤维共振上也不会比"啪"那一下响）。 */
    static final double TWIG_REST_MAX = 0.55;

    // ═══════════ 配方 ═══════════

    /** 轻：很闷很短的"嗒"（软锤 + 一个主共振 + 两个弱的，再压掉高频）。f = 主共振，t60，lp = 低通，a = 噪声多少。 */
    public static final int TAP = 1;
    /** 鼠标微动开关：冲击 + 一下"金属弹片"（f = 弹片共振）、一个外壳（a = 外壳频率）、一点 6.8 kHz 的"空气"，lp 越低越闷。 */
    public static final int SWITCH = 2;
    /**
     * 像 MacBook 触控板（Force Touch，"咔"是 Taptic Engine 震出来的）：短、干、偏低、很实。一个偏低的主共振（f）+ 一个弱的上分音（f × 2.45）
     * + 一小下机械的"咔"（a = 有多少，b = 它的频率）；c > 0 时 c 毫秒后再来一下弱的（执行器回弹，"双层"）。整体低通 3 kHz，不要塑料的"叮"。
     */
    public static final int TAPTIC = 3;
    /**
     * 像 MacBook 触控板、更低的那种：f 是"听起来的低音"（340 Hz 上下），它本身不放（手机喇叭放不出），只放它的 2、3、4 倍泛音
     * （耳朵从一串整数倍的泛音里听出那个低音，残留音高），再加同 {@link #TAPTIC} 的那一下咔（a、b）；c &gt; 0 同样是回弹。
     */
    public static final int TAPTIC_LOW = 8;
    /** 滚轮刻度：极短的一下（f、t60 几毫秒）；a > 0 时 a 毫秒后再来一下弱的（棘轮两下）。 */
    public static final int TICK = 4;
    /** 木块 / 木鱼：软锤（a = 锤宽毫秒）敲几个模态；b = 0 木块（自由梁的分音 1 : 2.756 : 5.404），b = 1 木鱼（空腔，1 : 1.52 : 2.88，第二个响得久）。 */
    public static final int WOOD = 5;
    /**
     * 细树枝折断（PhISEM 式的随机碰撞，思路同 STK Shakers，代码自己写）：第一下最响的"啪"，之后 a 下越来越稀、越来越弱的小裂，
     * 每一下是极短的噪声，喂进三个木纤维的共振（f、f × 1.31、f × 1.68，最高的也压在 2.5 kHz 以下）；b = 整段多长（毫秒）。另有一点 6 kHz 以上的干裂声给脆度（避开 2.5–5.5k）。
     */
    public static final int TWIG = 6;
    /** 竹筒"咚"：一端闭口的管，奇数分音（f × 1 : 2.95 : 4.9，稍微拉开），软锤（a = 锤宽毫秒）+ b 份拍击的噪声。 */
    public static final int BAMBOO = 7;

    /**
     * 一个配方：种类 + 参数。不可变；{@link #scaled} 派生出鼠标的左 / 右 / 中、松开、大键这些"同一个声音的兄弟"。
     * f（Hz）、t60（毫秒，主共振衰减 60 dB 的时间）、lp（Hz，0 = 不另外低通）、a / b / c 每种配方自己的意思（见各种类的说明）。
     * desc 只给调试版的候选区看（数字和单位，不用翻译）。
     */
    public static final class Recipe {
        public final int type;
        public final double f, t60, lp, a, b, c;
        public final String desc;

        public Recipe(int type, double f, double t60, double lp, double a, double b, double c) {
            this.type = type;
            this.f = f;
            this.t60 = t60;
            this.lp = lp;
            this.a = a;
            this.b = b;
            this.c = c;
            // 树枝没有 t60：写整段多长、几下小裂
            // 更低的触控板：f 是听起来的低音、本身不放，写成 ≈
            this.desc = type == TWIG ? Math.round(f) + " Hz · " + fmt(b) + " ms · ×" + Math.round(a)
                    : (type == TAPTIC_LOW ? "≈" : "") + Math.round(f) + " Hz · " + fmt(t60) + " ms";
        }

        /** 频率 × fMul（连同配方里别的频率：微动开关的外壳、触控板那一下"咔"）、衰减 × tMul、低通 × lpMul。 */
        public Recipe scaled(double fMul, double tMul, double lpMul) {
            double na = type == SWITCH ? a * fMul : a;
            double nb = type == TAPTIC || type == TAPTIC_LOW ? b * fMul : b;
            return new Recipe(type, f * fMul, t60 * tMul, lp * lpMul, na, nb, c);
        }

        public float[] render(int sampleRate, long seed) {
            return SoundSynth.render(this, sampleRate, seed);
        }
    }

    private static String fmt(double ms) {
        return ms == Math.rint(ms) ? Long.toString((long) ms) : String.valueOf(Math.round(ms * 10) / 10.0);
    }

    /** 按配方合成、收尾。返回的数组峰值是 1（全零的除外），开头就有声音。 */
    public static float[] render(Recipe r, int sr, long seed) {
        Rng rng = new Rng(seed * 0x9E3779B97F4A7C15L + r.type * 0x632BE59BD9B4E019L);
        // 每个变体的小差别：共振 ±0.9%、衰减 ±5%（同一个种子永远一样）
        double fj = 1 + VARIANT_F * rng.signed();
        double tj = 1 + 0.05 * rng.signed();
        int n = (int) Math.ceil(MAX_SECONDS * sr);
        double[] x = new double[n];
        switch (r.type) {
            case TAP: tap(x, sr, rng, r.f * fj, r.t60 * tj, r.a); break;
            case SWITCH: sw(x, sr, rng, r.f * fj, r.t60 * tj, r.a * fj); break;
            case TAPTIC: taptic(x, sr, rng, r.f * fj, r.t60 * tj, r.a, r.b * fj, r.c, false); break;
            case TAPTIC_LOW: taptic(x, sr, rng, r.f * fj, r.t60 * tj, r.a, r.b * fj, r.c, true); break;
            case TICK: tick(x, sr, rng, r.f * fj, r.t60 * tj, r.a); break;
            case WOOD: wood(x, sr, rng, r.f * fj, r.t60 * tj, r.a, r.b); break;
            case TWIG: twig(x, sr, rng, r.f * fj, r.a, r.b * tj); break;
            case BAMBOO: bamboo(x, sr, rng, r.f * fj, r.t60 * tj, r.a, r.b); break;
            default: throw new IllegalArgumentException("不认识的配方 " + r.type);
        }
        if (r.lp > 0) lowpass(x, sr, r.lp);
        return finish(x, sr);
    }

    // ── 各种类 ──

    private static void tap(double[] x, int sr, Rng rng, double f, double t60, double noise) {
        double[] e = new double[x.length];
        mallet(e, sr, 0, 0.6, 1);
        noiseBurst(e, sr, rng, 0, 1.2, noise);
        resonate(x, e, sr, f, t60, 1);
        resonate(x, e, sr, f * 0.66, t60 * 1.2, 0.4);
        resonate(x, e, sr, f * 2.31, t60 * 0.45, 0.3);
        // 激励里的噪声喂进宽的共振，裙边会拖进 500 Hz 以下（手机喇叭放不出，白占响度）：再压一道
        highpass2(x, sr, TAP_HIGHPASS_HZ, 0.7071067811865476);
    }

    private static void sw(double[] x, int sr, Rng rng, double snap, double t60, double body) {
        double[] e = new double[x.length];
        e[0] += 1;   // 弹片翻过去的那一下冲击
        noiseBurst(e, sr, rng, 0, 0.4, 0.5);
        resonate(x, e, sr, snap, t60, 1);
        resonate(x, e, sr, body, t60 * 1.6, 0.7);
        resonate(x, e, sr, 6800, 3, 0.15);
    }

    private static void taptic(double[] x, int sr, Rng rng, double f, double t60, double click, double clickHz, double reboundMs, boolean low) {
        double[] e = new double[x.length];
        e[0] += 1;
        if (reboundMs > 0) {
            int at = (int) Math.round(reboundMs * sr / 1000);
            if (at < e.length) e[at] += 0.32;
        }
        if (low) {   // 只放低音的 2、3、4 倍泛音（耳朵听出的还是 f）：高的死得快一点
            resonate(x, e, sr, f * 2, t60, 1);
            resonate(x, e, sr, f * 3, t60 * 0.8, 0.7);
            resonate(x, e, sr, f * 4, t60 * 0.6, 0.35);
        } else {
            resonate(x, e, sr, f, t60, 1);
            resonate(x, e, sr, f * 2.45, t60 * 0.5, 0.35);
        }
        double[] c = new double[x.length];
        noiseBurst(c, sr, rng, 0, 0.5, 1);
        if (reboundMs > 0) noiseBurst(c, sr, rng, reboundMs, 0.5, 0.3);
        resonate(x, c, sr, clickHz, 3, click);
        lowpass(x, sr, 3000);
    }

    private static void tick(double[] x, int sr, Rng rng, double f, double t60, double secondMs) {
        double[] e = new double[x.length];
        e[0] += 1;
        noiseBurst(e, sr, rng, 0, 0.25, 0.3);
        if (secondMs > 0) {
            int at = (int) Math.round(secondMs * sr / 1000);
            if (at < e.length) e[at] += 0.5;
            noiseBurst(e, sr, rng, secondMs, 0.25, 0.15);
        }
        resonate(x, e, sr, f, t60, 1);
        resonate(x, e, sr, f * 1.35, t60 * 0.7, 0.35);
    }

    private static final double[] BEAM = {1, 2.756, 5.404}, BEAM_AMP = {1, 0.45, 0.12};
    private static final double[] HOLLOW = {1, 1.52, 2.88}, HOLLOW_AMP = {1, 0.5, 0.18}, HOLLOW_T = {1, 0.85, 0.3};

    private static void wood(double[] x, int sr, Rng rng, double f, double t60, double malletMs, double hollow) {
        double[] e = new double[x.length];
        mallet(e, sr, 0, malletMs, 1);
        noiseBurst(e, sr, rng, 0, 0.4, 0.06);
        boolean h = hollow > 0.5;
        double[] ratio = h ? HOLLOW : BEAM, amp = h ? HOLLOW_AMP : BEAM_AMP;
        for (int k = 0; k < ratio.length; k++) {
            // 高的分音死得快：木块 ∝ 频率^1.2；木鱼的空腔第二个分音响得久
            double t = h ? t60 * HOLLOW_T[k] : t60 / Math.pow(ratio[k], 1.2);
            resonate(x, e, sr, f * ratio[k], t, amp[k]);
        }
    }

    private static void twig(double[] x, int sr, Rng rng, double f, double cracks, double lengthMs) {
        int n = x.length;
        double[] first = new double[n], rest = new double[n];
        // 第一下最响；之后的小裂间隔越来越大（指数分布、均值随时间变长）、越来越弱
        noiseBurst(first, sr, rng, 0, 0.25, 1);
        first[0] += 0.8;
        double t = 0;
        int count = (int) Math.round(cracks);
        for (int i = 0; i < count; i++) {
            double mean = lengthMs / (count + 1.0) * (0.4 + 1.2 * i / Math.max(1, count));
            t += -Math.log(1 - 0.95 * rng.uniform()) * mean;
            if (t >= lengthMs) break;
            double amp = Math.exp(-t / (lengthMs * 0.45)) * (0.25 + 0.6 * rng.uniform());
            noiseBurst(rest, sr, rng, t, 0.12 + 0.25 * rng.uniform(), amp);
        }
        // 第一下和后面的小裂分开过同一套（线性，分开算再加 = 一起算）：后面的小裂打在还在响的纤维共振上会叠得比第一下还响，
        // 合起来最响压到第一下的 TWIG_REST_MAX（"啪"那一下永远最响、起音不软）
        double[] a = twigBody(first, sr, f), b = twigBody(rest, sr, f);
        double pa = 0, pb = 0;
        for (int i = 0; i < n; i++) {
            pa = Math.max(pa, Math.abs(a[i]));
            pb = Math.max(pb, Math.abs(b[i]));
        }
        double k = pb > TWIG_REST_MAX * pa ? TWIG_REST_MAX * pa / pb : 1;
        for (int i = 0; i < n; i++) x[i] += a[i] + k * b[i];
    }

    /** 树枝的一段激励 → 声音：木纤维的共振 + 干裂声。 */
    private static double[] twigBody(double[] e, int sr, double f) {
        int n = e.length;
        // 纤维的共振：噪声激励的裙边会伸进 2.5 kHz 以上，再压一道低通
        double[] out = new double[n];
        resonate(out, e, sr, f, 9, 1);
        resonate(out, e, sr, f * 1.31, 7, 0.75);
        resonate(out, e, sr, f * 1.68, 6, 0.5);
        lowpass(out, sr, 2600);
        // 干裂声：低的那半（压在 2.3 kHz 以下）+ 一点 6 kHz 以上的"脆"，中间 2.5–5.5k 不要
        double[] dryLow = e.clone();
        lowpass(dryLow, sr, 2000);
        lowpass(dryLow, sr, 2000);
        double[] dryHigh = e.clone();
        highpass2(dryHigh, sr, 6500, 0.707);
        highpass2(dryHigh, sr, 6500, 0.707);
        for (int i = 0; i < n; i++) out[i] += 0.12 * dryLow[i] + 0.12 * dryHigh[i];
        return out;
    }

    private static void bamboo(double[] x, int sr, Rng rng, double f, double t60, double malletMs, double slap) {
        double[] e = new double[x.length];
        mallet(e, sr, 0, malletMs, 1);
        if (slap > 0) noiseBurst(e, sr, rng, 0, 0.6, 0.25 * slap);
        resonate(x, e, sr, f, t60, 1);
        resonate(x, e, sr, f * 2.95, t60 * 0.45, 0.35);
        resonate(x, e, sr, f * 4.9, t60 * 0.25, 0.1);
    }

    // ═══════════ 积木 ═══════════

    /** 锤头：从 atMs 起一个宽 widthMs 的半个正弦脉冲（越宽越软、高频越少）。 */
    static void mallet(double[] e, int sr, double atMs, double widthMs, double amp) {
        int start = (int) Math.round(atMs * sr / 1000);
        int w = Math.max(1, (int) Math.round(widthMs * sr / 1000));
        // 按面积归一：不同锤宽打进去的"冲量"一样
        double k = amp * Math.PI / (2.0 * w);
        for (int i = 0; i < w && start + i < e.length; i++) e[start + i] += k * Math.sin(Math.PI * (i + 0.5) / w);
    }

    /**
     * 从 atMs 起一段按 tauMs 指数衰减的白噪声（拉 5 个 τ 长）。幅度按 √(48000 / 采样率) 缩：采样率高了每赫兹的能量不变，
     * 和冲击、锤头（它们的频谱跟采样率无关）的比例不变，44.1 / 48 / 96 kHz 下听起来一样。
     */
    static void noiseBurst(double[] e, int sr, Rng rng, double atMs, double tauMs, double amp) {
        int start = (int) Math.round(atMs * sr / 1000);
        double tau = tauMs * sr / 1000;
        amp *= Math.sqrt(48000.0 / sr);
        int len = (int) Math.ceil(5 * tau) + 1;
        for (int i = 0; i < len && start + i < e.length; i++) e[start + i] += amp * rng.signed() * Math.exp(-i / tau);
    }

    /**
     * 二阶共振器：x 加上"激励 e 过一个 f Hz、T60 = t60 毫秒的共振"。冲激响应是 amp · r^n · sin((n+1)θ)，峰值约 amp
     * （r 由 T60 定：60 dB 衰减 = 1000 倍）。f 到了奈奎斯特附近就不要（会变成别的频率）。
     */
    static void resonate(double[] x, double[] e, int sr, double f, double t60, double amp) {
        if (f <= 0 || f >= sr * 0.45 || t60 <= 0 || amp == 0) return;
        double theta = 2 * Math.PI * f / sr;
        double r = Math.pow(10, -3.0 / (t60 / 1000.0 * sr));
        double a1 = 2 * r * Math.cos(theta), a2 = r * r;
        double b0 = amp * Math.sin(theta);
        double y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double y = b0 * e[i] + a1 * y1 - a2 * y2;
            x[i] += y;
            y2 = y1;
            y1 = y;
        }
    }

    /** 二阶巴特沃斯低通（RBJ 的公式）。 */
    static void lowpass(double[] x, int sr, double fc) {
        if (fc <= 0 || fc >= sr * 0.45) return;
        double w = 2 * Math.PI * fc / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * 0.7071067811865476);
        double a0 = 1 + al;
        biquad(x, (1 - cs) / 2 / a0, (1 - cs) / a0, (1 - cs) / 2 / a0, -2 * cs / a0, (1 - al) / a0);
    }

    /** 二阶高通（RBJ 的公式），q 给 4 阶巴特沃斯拆成两节用（0.5412 和 1.3066）。 */
    static void highpass2(double[] x, int sr, double fc, double q) {
        double w = 2 * Math.PI * fc / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
        double a0 = 1 + al;
        biquad(x, (1 + cs) / 2 / a0, -(1 + cs) / a0, (1 + cs) / 2 / a0, -2 * cs / a0, (1 - al) / a0);
    }

    /** 峰值均衡（RBJ 的公式）：gainDb 为负就是一个凹。 */
    static void peaking(double[] x, int sr, double fc, double gainDb, double q) {
        double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * fc / sr, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
        double a0 = 1 + al / A;
        biquad(x, (1 + al * A) / a0, -2 * cs / a0, (1 - al * A) / a0, -2 * cs / a0, (1 - al / A) / a0);
    }

    /** 直接 I 型双二阶（a0 已经归一）。 */
    private static void biquad(double[] x, double b0, double b1, double b2, double a1, double a2) {
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i];
            double y = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = in;
            y2 = y1;
            y1 = y;
            x[i] = y;
        }
    }

    /**
     * 统一收尾：4 阶高通 300 Hz → 2.5–5.5 kHz 的凹 → 剪开头 → 按 −60 dB 剪尾巴、补 3 ms 淡出 → 峰值归一到 1。
     * 返回 float（混音用）。全零的输入返回长度 1 的零。
     */
    static float[] finish(double[] x, int sr) {
        highpass2(x, sr, HIGHPASS_HZ, 0.5411961001461970);
        highpass2(x, sr, HIGHPASS_HZ, 1.3065629648763766);
        peaking(x, sr, DIP_HZ, DIP_DB, DIP_Q);
        double peak = 0;
        for (double v : x) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return new float[1];
        int start = 0;
        while (start < x.length && Math.abs(x[start]) < LEAD_GATE * peak) start++;
        double gate = peak * Math.pow(10, TAIL_DB / 20);
        int end = x.length;
        while (end > start + 1 && Math.abs(x[end - 1]) < gate) end--;
        int len = end - start;
        float[] out = new float[len];
        double k = 1 / peak;
        int fade = Math.min(len / 2, (int) Math.round(END_FADE_MS * sr / 1000));
        for (int i = 0; i < len; i++) {
            double g = k;
            int fromEnd = len - 1 - i;
            if (fromEnd < fade) g *= 0.5 - 0.5 * Math.cos(Math.PI * (fromEnd + 0.5) / fade);
            out[i] = (float) (x[start + i] * g);
        }
        return out;
    }

    /** 自己的随机数（SplitMix64）：同一个种子在电脑、手机上逐个一样。 */
    public static final class Rng {
        private long s;

        public Rng(long seed) {
            s = seed;
        }

        public long next() {
            long z = (s += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** [0, 1)。 */
        public double uniform() {
            return (next() >>> 11) * 0x1.0p-53;
        }

        /** [−1, 1)。 */
        public double signed() {
            return uniform() * 2 - 1;
        }

        /** [0, n)。 */
        public int below(int n) {
            return (int) ((next() >>> 33) % n);
        }
    }
}
