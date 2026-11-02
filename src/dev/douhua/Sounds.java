package dev.douhua;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.locks.LockSupport;

/**
 * 声音（和 Haptics 并列）：一条常驻的低延迟音频管道，豆花自己混音（SoundMixer）、自己合成声音（SoundSynth / SoundBank）。
 * 整个进程一份（{@link #get}），主界面和设置页的试听台共用：试听的就是实际用的。"发生了什么 → 放哪个声音"在 SoundEvents（事件层，纯 Java），
 * MainActivity 在调震动的那几处调它、它再调这里的 {@link #play}。
 *
 * 怎么用（后面接事件层、设置页的入口）：
 * <ul>
 * <li>{@link #configure}（主线程，读完设置调）：套装、音量、调试版的候选 / 偏移 / 随机幅度。套装是"关"时什么都不建（没有线程、没有管道）。</li>
 * <li>{@link #acquire} / {@link #release}（主线程，页面 onStart / onStop 各调一次，计数）：有人用、套装不是"关"才开管道；计数到 0 就关掉（退后台）。
 *     主界面 → 设置页时设置页先 acquire、主界面后 release，管道不断。</li>
 * <li>{@link #play}(声音 id, key)（主线程，按键时）：只往无锁队列写一个数（暂停着时再叫醒混音线程），不等、不分配；声音 id 见 SoundBank.KEY_DOWN…。
 *     {@link #audition} 指定候选（调试版候选区）。</li>
 * <li>{@link #diag}：诊断（拿没拿到低延迟、采样率、缓冲、实际从哪个设备出、断流几次），调试版候选区下面那一行用。</li>
 * </ul>
 *
 * 管道（方案 2.1，推断为主，三星上没验证）：AudioTrack，PERFORMANCE_MODE_LOW_LATENCY + MODE_STREAM，手机原生采样率、单声道 16 位；
 * 拿到了低延迟（快速通道）才把缓冲压到两个 burst（不够时断流一次加一个 burst，每写一块查一次）；拿不到就不动系统给的大小——普通混音一个周期就 20 ms 以上，
 * 压到两个 HAL burst 会一直凑不够一个周期，声音一截一截地断、甚至混不上（审查时照 AOSP 的规则算过，几秒到十几秒才自己调回来）。
 * 混音线程 douhua-sound 每次混一个 burst、阻塞写。起步、换套、换路由那几块的断流（不是缓冲不够，是我们自己停过）单独记、不加缓冲（{@link #GRACE_WRITES}）；
 * 换套重算声音时先把管道停住（pause + flush）、算完再放，算的那几十到几百毫秒不会自己造出断流。
 * 用途 USAGE_ASSISTANCE_SONIFICATION（= 系统那档音量，STREAM_SYSTEM）：静音 / 振动 / 勿扰模式下系统自己把它静音（AOSP AudioService、ZenModeHelper），
 * 跟着手机"系统"那档音量走，和 Moonlight 的媒体音量分开；不请求音频焦点（不压低、不暂停别人的声音）。
 * 只从手机喇叭出：setPreferredDevice(手机喇叭)，再查实际路由（getRoutedDevice），不是手机喇叭就写静音（接着外接屏、蓝牙耳机、电脑连着手机媒体音频时）。
 * 主线程绝不碰管道：建 / 关 / 查路由都在混音线程里做；路由变了的回调只在主线程上置一个标记。查路由要走 binder（列一遍音频设备）：
 * 开管道时在 play 之前查一次；还不知道时只在没声音的时候再查（缓冲只有两个 burst，那时断一下也听不见、不算）；路由回调来了马上查一次、重读缓冲和低延迟（系统重建轨道后会变）；这个回调每次轨道重新 play 都会来（AOSP 的 AUDIO_CLIENT_STARTED），不只是路由真变了，日志只在查出来的东西变了时写。
 * 管道坏了（开不起来、一直写出错、线程里出了错）：线程退出、告诉主线程，过一会儿（{@link #RETRY_MS} 起翻倍，最多 {@link #RETRY_MAX_MS}）再开一个新的；
 * 退后台（计数到 0）、换套装时从头算。
 * 省电：10 秒没声音就 pause + flush、线程停住（再按时叫醒，第一下可能慢一点——硬件要预热）；release 到 0 就整个关掉。
 * 算声音（第一次几十到几百毫秒，调试版全算）也在混音线程里，算好之前按的键丢掉（不晚放）。算好的那份进程里留着，下次开管道不重算（换套 / 关掉才扔）。
 */
final class Sounds {
    private static final String TAG = "douhua";
    /** 多久没声音就暂停管道。 */
    static final long IDLE_MS = 10_000;
    /** 一个管道写出错这么多次就不再重开这个管道（线程退出，过 {@link #RETRY_MS} 再开新的）。 */
    static final int MAX_WRITE_ERRORS = 5;
    /** 管道坏了以后多久再试（连着坏一次翻一倍，最多 RETRY_MAX_MS；退后台、换套装从头算）。 */
    static final long RETRY_MS = 5_000, RETRY_MAX_MS = 60_000;
    /** play 之后（开管道、闲着醒来、换套重算完、换路由）这么多块里的断流不算缓冲不够（我们自己停过、系统重建过轨道），单独记。 */
    static final int GRACE_WRITES = 4;
    /** 设置页音量滑块的默认（0–100，SoundMixer.volumeGain 换算；要用户在真机上用耳朵定）。 */
    static final int DEFAULT_VOLUME = 80;

    /** 管道的状态（{@link Diag#state}）。 */
    static final int STATE_OFF = 0, STATE_BUILDING = 1, STATE_RUNNING = 2, STATE_PAUSED = 3, STATE_FAILED = 4;

    private static Sounds instance;

    /** 进程里一份（只在主线程调）。 */
    static Sounds get(Context c) {
        if (instance == null) instance = new Sounds(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    /** 调试版（可调试包）：每个槽的候选都算（候选区切换不用重算），设置里挑的候选生效；正式包只算默认的。 */
    private final boolean tuning;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ── 主线程的状态 ──
    private int pack = SoundBank.PACK_OFF;
    private int users;
    private boolean suppressed;
    private Engine engine;
    /** 连着坏了几次、下次最早什么时候再开（SystemClock.uptimeMillis）。 */
    private int failures;
    private long retryAt;
    private final Runnable retry = this::startEngineIfUsed;
    private float volume = SoundMixer.volumeGain(DEFAULT_VOLUME);
    private int[] choices;
    private double offsetDb, randomScale = 1;

    /** 算好的那份（换套 / 关掉才扔）。混音线程写、读。 */
    private static volatile SoundBank.Bank cached;
    private static volatile long cachedMs;
    /** 上一个管道最后的诊断（管道关了以后 diag 照样给得出东西）。 */
    private volatile Diag last;

    private Sounds(Context app) {
        this.app = app;
        this.tuning = Haptics.tuning(app);
    }

    /** 调试版：候选区、"连打"、微调滑块都只在调试包里有。 */
    boolean tuning() {
        return tuning;
    }

    /**
     * 读完设置调（主线程）：pack = SoundBank.PACK_*，volumePercent 0–100；choices = 每个槽选的候选（正式包忽略，一律默认）；
     * offsetDb / randomScale 是调试版的"音量偏移""随机幅度"（正式包忽略）。套装换了就重算（在混音线程里），换成"关"就把管道、算好的那份都扔掉。
     */
    void configure(int pack, int volumePercent, int[] choices, double offsetDb, double randomScale) {
        if (pack < 0 || pack >= SoundBank.PACKS) pack = SoundBank.PACK_OFF;
        this.volume = SoundMixer.volumeGain(volumePercent);
        this.choices = tuning ? choices : null;
        this.offsetDb = tuning ? offsetDb : 0;
        this.randomScale = tuning ? randomScale : 1;
        boolean changed = pack != this.pack;
        this.pack = pack;
        if (changed) resetRetry();   // 换了套装：坏过的也马上再试
        if (pack == SoundBank.PACK_OFF) {
            stopEngine();
            cached = null;
            return;
        }
        Engine e = liveEngine();
        if (e != null) {
            e.apply(this.volume, this.choices, this.offsetDb, this.randomScale);
            if (changed) e.rebuild(pack);
        } else {
            startEngineIfUsed();
        }
    }

    /** 页面开始用（onStart）。 */
    void acquire() {
        users++;
        startEngineIfUsed();
    }

    /** 页面不用了（onStop）。计数到 0 就关掉管道（坏过的下次回前台马上再试）。 */
    void release() {
        if (users > 0) users--;
        if (users == 0) {
            stopEngine();
            resetRetry();
        }
    }

    /** 通话中 / 来电响铃时（MainActivity 按 AudioManager 的模式设，同截不截音量键的那个判断 inCall）：一律不放。 */
    void setSuppressed(boolean s) {
        suppressed = s;
    }

    /** 放一个声音（主线程，按键时）：只写一个数进队列，不等。声音 id 见 SoundBank.KEY_DOWN…，key 见那里的说明。没开 / 满了返回 false。 */
    boolean play(int sound, int key) {
        return play(sound, key, 0);
    }

    /**
     * 同上，delayMs &gt; 0 = 晚这么多毫秒再放（混音线程按帧数，确定的；一次点击那一对"咔哒"的"哒"，SoundEvents.CLICK_GAP_MS）。
     * 事件层（SoundEvents.Out）就调这一个。
     */
    boolean play(int sound, int key, int delayMs) {
        Engine e = engine;
        if (e == null || e.finished || suppressed) return false;
        return e.post(SoundMixer.request(sound, key, -1, 0, delayMs));
    }

    /** 试听指定的候选（调试版候选区）：cand = SoundBank.candidates 的下标。 */
    boolean audition(int sound, int key, int cand) {
        return audition(sound, key, cand, 0);
    }

    /** 同上，晚 delayMs 毫秒（候选区连打时一下里的松开、触控板那一对的"哒"）。 */
    boolean audition(int sound, int key, int cand, int delayMs) {
        Engine e = engine;
        if (e == null || e.finished || suppressed) return false;
        return e.post(SoundMixer.request(sound, key, cand, 0, delayMs));
    }

    /**
     * 现在的套装算好了、管道开着（在放或暂停着都算）：这时交的声音不会被丢（算好之前交的会被丢掉，见类注释）。
     * 设置页点一项套装后等它再放那一小段（SoundBench.DEMO）。
     */
    boolean ready() {
        Engine e = engine;
        return e != null && !e.finished && e.readyVersion == e.version;
    }

    /** 诊断（任何时候都能调；管道关着时给上一次的——坏了的照样写 failed 和原因，从没开过给 STATE_OFF）。 */
    Diag diag() {
        Engine e = engine;
        if (e != null) return e.snapshot();
        Diag d = last;
        return d != null ? d.copy() : new Diag();
    }

    /** 还活着的管道（线程已经结束的——开不起来、一直写出错、出了错——当没有）。 */
    private Engine liveEngine() {
        Engine e = engine;
        return e != null && !e.finished ? e : null;
    }

    private void startEngineIfUsed() {
        main.removeCallbacks(retry);
        if (liveEngine() != null || users <= 0 || pack == SoundBank.PACK_OFF) return;
        long wait = retryAt - SystemClock.uptimeMillis();
        if (wait > 0) {   // 刚坏过：到点再试（页面一直开着也会再试，不用等退后台）
            main.postDelayed(retry, wait);
            return;
        }
        Engine e = new Engine(pack);
        e.apply(volume, choices, offsetDb, randomScale);
        engine = e;
        e.start();
    }

    private void stopEngine() {
        main.removeCallbacks(retry);
        Engine e = engine;
        if (e == null) return;
        engine = null;
        e.quit();
    }

    private void resetRetry() {
        failures = 0;
        retryAt = 0;
    }

    /** 混音线程结束了（主线程上调）：坏了的（failed）过一会儿再开一个新的，越坏越久（{@link #RETRY_MS} 翻倍到 {@link #RETRY_MAX_MS}）。 */
    private void engineEnded(Engine e, boolean failed) {
        if (engine != e) return;   // 已经关掉 / 换过了
        engine = null;
        if (!failed) return;
        failures++;
        long backoff = Math.min(RETRY_MAX_MS, RETRY_MS << Math.min(failures - 1, 4));
        retryAt = SystemClock.uptimeMillis() + backoff;
        Log.w(TAG, "声音管道坏了（第 " + failures + " 次），" + backoff / 1000 + " 秒后再开");
        startEngineIfUsed();   // 还有人用就排上到点再开
    }

    // ═══════════ 诊断 ═══════════

    /** 诊断的一份快照（字段都是只读的）。line() 给调试版候选区那一行。 */
    static final class Diag {
        int state = STATE_OFF;
        /** 出错时的原因（给开发者看，中文，不进界面翻译）。 */
        String error = "";
        /** 管道拿到了低延迟（getPerformanceMode() == LOW_LATENCY）；系统声明了"低延迟音频"（FEATURE_AUDIO_LOW_LATENCY）。路由变了以后重读。 */
        boolean lowLatency, lowLatencyFeature;
        int sampleRate, framesPerBurst, bufferFrames, capacityFrames;
        /** 实际出声的设备（AudioDeviceInfo.TYPE_*，0 = 还不知道）、名字；是不是手机喇叭；有没有设上"只从手机喇叭出"。 */
        int deviceType;
        String deviceName = "";
        boolean onSpeaker, speakerPreferred;
        /**
         * 断流（AudioTrack.getUnderrunCount）：underruns 是放着放着断的（缓冲不够、线程没抢到 CPU——这个数才是要看的）；
         * startUnderruns 是起步、换套重算、换路由、闲着时查路由那几块的（我们自己停过、系统重建过轨道，不加缓冲）。
         * 还有写出错、队列满了丢掉的、放了的、抢占的、合并的滚轮格。
         */
        int underruns, startUnderruns, writeErrors, dropped, played, stolen, merged;
        /** 算声音花了多久、占多少字节。 */
        long bankMs, bankBytes;
        /** 混音线程拿到了"紧急音频"优先级（拿不到退成"音频"，再拿不到就普通）。 */
        int priority;

        Diag copy() {
            Diag d = new Diag();
            d.state = state;
            d.error = error;
            d.lowLatency = lowLatency;
            d.lowLatencyFeature = lowLatencyFeature;
            d.sampleRate = sampleRate;
            d.framesPerBurst = framesPerBurst;
            d.bufferFrames = bufferFrames;
            d.capacityFrames = capacityFrames;
            d.deviceType = deviceType;
            d.deviceName = deviceName;
            d.onSpeaker = onSpeaker;
            d.speakerPreferred = speakerPreferred;
            d.underruns = underruns;
            d.startUnderruns = startUnderruns;
            d.writeErrors = writeErrors;
            d.dropped = dropped;
            d.played = played;
            d.stolen = stolen;
            d.merged = merged;
            d.bankMs = bankMs;
            d.bankBytes = bankBytes;
            d.priority = priority;
            return d;
        }

        /**
         * 一行技术信息（只有缩写、数字和单位，调试版用，不翻译）：
         * "running · LL ✓ (feature ✓) · 48000 Hz · burst 192 · buf 384/1536 · dev 2 speaker ✓ · xrun 0 (+2 start) · err 0 · drop 0 · 12 ms 1.6 MB · prio -19"。
         * xrun 只算放着放着断的（要看的就是它涨不涨），括号里是起步 / 换套 / 换路由那几下。
         */
        String line() {
            String[] st = {"off", "building", "running", "paused", "failed"};
            StringBuilder b = new StringBuilder();
            b.append(st[Math.max(0, Math.min(st.length - 1, state))]);
            if (!error.isEmpty()) b.append(" (").append(error).append(')');
            b.append(" · LL ").append(lowLatency ? "✓" : "✗").append(" (feature ").append(lowLatencyFeature ? "✓" : "✗").append(')');
            b.append(" · ").append(sampleRate).append(" Hz · burst ").append(framesPerBurst)
                    .append(" · buf ").append(bufferFrames).append('/').append(capacityFrames);
            b.append(" · dev ").append(deviceType).append(onSpeaker ? " speaker ✓" : " speaker ✗");
            if (!speakerPreferred) b.append(" (no pref)");
            b.append(" · xrun ").append(underruns).append(" (+").append(startUnderruns).append(" start)");
            b.append(" · err ").append(writeErrors).append(" · drop ").append(dropped);
            b.append(" · ").append(bankMs).append(" ms ").append(String.format(java.util.Locale.ROOT, "%.1f MB", bankBytes / 1048576.0));
            b.append(" · prio ").append(priority);
            return b.toString();
        }
    }

    // ═══════════ 混音线程 + 管道 ═══════════

    /**
     * 一次"开着的管道"：一个线程、一个 AudioTrack、一个混音器。关掉 / 坏了就整个扔，下次开新的（算好的那份留着）。
     * 线程结束时（finished）告诉主线程（Sounds.engineEnded），坏了的过一会儿再开新的。
     */
    private final class Engine implements Runnable {
        private final SoundMixer mixer = new SoundMixer(SystemClock.uptimeMillis() ^ System.nanoTime());
        private final Thread thread = new Thread(this, "douhua-sound");
        private volatile boolean quit;
        private volatile boolean parked;
        /** 线程已经结束（不会再放任何东西）。 */
        private volatile boolean finished;
        private volatile int wantPack;
        private volatile int[] wantChoices;
        /** 主线程改了套装就加一，混音线程看到不一样就重算。 */
        private volatile int version;
        /** 算好、管道开着的是第几版（0 = 还没有 / 管道关了重开中；{@link Sounds#ready}）。混音线程写。 */
        private volatile int readyVersion;
        private volatile boolean routeDirty;
        private final Diag d = new Diag();   // 混音线程写，snapshot 复制（字段级的小不一致无所谓：只是诊断）

        // 只在混音线程里用
        private AudioTrack track;
        /** 管道开着、但停着（闲着、换套重算时：pause + flush 过）。 */
        private boolean trackPaused;
        private int builtVersion = -1;
        private int burst;
        /** 拿到低延迟时想要的缓冲（两个 burst 起，断流一次加一个 burst）。 */
        private int targetFrames;
        private int lastUnderruns;
        /** 还有几块的断流不算缓冲不够（{@link #GRACE_WRITES}）。 */
        private int graceWrites;
        private int route;          // 0 不知道、1 手机喇叭、2 别的
        private long routeCheckedAt;
        /** 还不知道路由时最多再查几次（有的机器一直给 null：别在混音循环里一直走 binder）。 */
        private int routeTries;
        private final AudioRouting.OnRoutingChangedListener routing = router -> routeDirty = true;

        Engine(int pack) {
            wantPack = pack;
            version = 1;
        }

        void start() {
            thread.start();
        }

        void quit() {
            quit = true;
            LockSupport.unpark(thread);
        }

        void apply(float volume, int[] choices, double offsetDb, double randomScale) {
            mixer.setVolume(volume);
            mixer.setOffsetDb(offsetDb);
            mixer.setRandomScale(randomScale);
            mixer.setChoices(choices);
            // 正式包只算默认的候选：选的变了也不用重算（反正忽略）；调试版全都算了，切换不用重算
            wantChoices = choices;
        }

        void rebuild(int pack) {
            wantPack = pack;
            version++;
            LockSupport.unpark(thread);
        }

        boolean post(long req) {
            boolean ok = mixer.post(req);
            if (parked) LockSupport.unpark(thread);
            return ok;
        }

        Diag snapshot() {
            Diag s = d.copy();
            s.dropped = mixer.dropped();
            s.played = mixer.started();
            s.stolen = mixer.stolen();
            s.merged = mixer.merged();
            return s;
        }

        @Override
        public void run() {
            boolean failed = false;
            try {
                failed = !loop();
            } catch (Throwable ex) {
                // 声音只是锦上添花：这个线程里出了什么错（连 OutOfMemoryError）都只让声音停掉，不让整个 App 闪退
                failed = true;
                d.error = ex.getClass().getSimpleName();
                Log.w(TAG, "声音管道出错", ex);
            } finally {
                try {
                    closeTrack();
                } catch (RuntimeException ex) {
                    Log.w(TAG, "关声音管道出错", ex);   // 照样往下走：一定要告诉主线程"结束了"
                }
                d.state = failed ? STATE_FAILED : STATE_OFF;
                last = snapshot();
                finished = true;
                boolean f = failed;
                main.post(() -> engineEnded(this, f));
            }
        }

        /** 混音循环。正常关掉（quit）返回 true，坏了（开不起来、一直写出错）返回 false。 */
        private boolean loop() {
            d.priority = raisePriority();
            AudioManager am = app.getSystemService(AudioManager.class);
            int sr = parse(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), 0);
            if (sr <= 0) sr = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_SYSTEM);
            if (sr <= 0) sr = 48000;
            int b = parse(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 0);
            if (b <= 0) b = 256;
            burst = Math.min(b, 4096);
            d.sampleRate = sr;
            d.framesPerBurst = burst;
            d.lowLatencyFeature = app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
            short[] buf = new short[burst];
            long idleFrames = IDLE_MS * sr / 1000;
            while (!quit) {
                if (builtVersion != version) {
                    int v = version;
                    // 算的这段时间（几十到几百毫秒）不写：管道开着就先停住，不然一定断流——还会被当成缓冲不够、把缓冲越撑越大
                    if (track != null && !trackPaused) pauseTrack();
                    d.state = STATE_BUILDING;
                    mixer.setBank(bank(sr, wantPack, wantChoices));
                    builtVersion = v;
                    if (quit) break;   // 算的时候被关掉了：不开管道
                }
                if (track == null) {
                    track = open(am, sr);
                    if (track == null) return false;
                    mixer.reset();   // 开管道那几十毫秒里按的不晚放
                    played();
                } else if (trackPaused) {
                    track.play();
                    trackPaused = false;
                    played();
                }
                if (readyVersion != builtVersion) readyVersion = builtVersion;   // 从这里起交的声音不会被丢
                if (routeDirty) {
                    routeChanged();
                } else if (route == 0 && routeTries < 10 && mixer.quiet() && SystemClock.uptimeMillis() - routeCheckedAt > 200) {
                    checkRoute();   // 还不知道路由：只在没声音时查（走 binder，断一下也听不见），这几块的断流不算
                    graceWrites = GRACE_WRITES;
                }
                mixer.render(buf, burst);
                if (!routeOk()) java.util.Arrays.fill(buf, (short) 0);
                int w = track.write(buf, 0, burst, AudioTrack.WRITE_BLOCKING);
                if (w < 0) {   // 音频服务重启了（ERROR_DEAD_OBJECT）之类：关掉，下一圈重开；一直出错就不再试（线程退出，主线程过一会儿开新的）
                    d.writeErrors++;
                    Log.w(TAG, "声音管道写出错 " + w + (d.writeErrors >= MAX_WRITE_ERRORS ? "，这个管道不再试" : "，重开"));
                    if (d.writeErrors >= MAX_WRITE_ERRORS) {
                        d.error = "write " + w;
                        return false;
                    }
                    closeTrack();
                    mixer.reset();
                    SystemClock.sleep(100);
                    continue;
                }
                tune();
                if (mixer.quiet() && mixer.idleFrames() >= idleFrames) idle();
            }
            return true;
        }

        /** 管道刚放起来（开管道、闲着醒来、换套重算完）：状态写回"在放"，接下来几块的断流不算缓冲不够。 */
        private void played() {
            d.state = STATE_RUNNING;
            graceWrites = GRACE_WRITES;
        }

        /**
         * 每写一块查一次断流（读共享内存，不走 binder）：放着放着断了 = 缓冲不够，拿到低延迟时加一个 burst（凭记忆是 Oboe 的做法），到容量为止；
         * 起步 / 换套 / 换路由那几块的另记、不加。
         */
        private void tune() {
            int u = track.getUnderrunCount();
            if (u <= lastUnderruns) {
                lastUnderruns = u;
                if (graceWrites > 0) graceWrites--;
                return;
            }
            int n = u - lastUnderruns;
            lastUnderruns = u;
            if (graceWrites > 0) {
                graceWrites--;
                d.startUnderruns += n;
                return;
            }
            d.underruns += n;
            if (d.lowLatency && d.bufferFrames + burst <= d.capacityFrames) {
                targetFrames = d.bufferFrames + burst;
                applyBuffer(track);
            }
        }

        /** 闲着：暂停、清掉缓冲里的静音（再开时第一下不排在一堆静音后面），停住等下一个请求 / 关掉 / 换套。醒来以后回到循环里再放（换了套先算）。 */
        private void idle() {
            pauseTrack();
            int v = version;
            parked = true;
            while (!quit && mixer.quiet() && v == version) LockSupport.park(this);
            parked = false;
        }

        private void pauseTrack() {
            track.pause();
            track.flush();
            trackPaused = true;
            d.state = STATE_PAUSED;
        }

        private SoundBank.Bank bank(int sr, int pack, int[] choices) {
            SoundBank.Bank b = cached;
            if (b != null && b.sampleRate == sr && b.pack == pack) {
                d.bankMs = cachedMs;
                d.bankBytes = b.bytes;
                return b;
            }
            long t0 = SystemClock.uptimeMillis();
            b = SoundBank.build(sr, pack, tuning, choices);
            long ms = SystemClock.uptimeMillis() - t0;
            if (!quit) {   // 算的时候被关掉（换成"关"）了就不留着
                cachedMs = ms;
                cached = b;
            }
            d.bankMs = ms;
            d.bankBytes = b == null ? 0 : b.bytes;
            Log.i(TAG, "声音算好了：" + SoundBank.PACK_IDS[pack] + "，" + sr + " Hz，" + d.bankMs + " ms，" + (d.bankBytes / 1024) + " KB");
            return b;
        }

        private AudioTrack open(AudioManager am, int sr) {
            AudioTrack t;
            try {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                AudioFormat fmt = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                // 不设缓冲大小（凭记忆：流模式下 Builder 默认一帧、由系统定最小值，低延迟时就是快速通道要的大小）；开起来以后拿到低延迟才再压
                t = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } catch (RuntimeException ex) {
                d.error = "build " + ex.getClass().getSimpleName();
                Log.w(TAG, "建不了声音管道", ex);
                return null;
            }
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                d.error = "state " + t.getState();
                Log.w(TAG, "声音管道没初始化好：" + t.getState());
                t.release();
                return null;
            }
            try {
                d.speakerPreferred = false;
                for (AudioDeviceInfo dev : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        d.speakerPreferred = t.setPreferredDevice(dev);
                        break;
                    }
                }
                targetFrames = 2 * burst;
                applyBuffer(t);
                t.addOnRoutingChangedListener(routing, main);
                track = t;
                trackPaused = false;
                route = 0;
                routeTries = 0;
                checkRoute();   // play 之前查（走 binder，这时还没在放，断不了）；查不到的以后在没声音时再查
                lastUnderruns = t.getUnderrunCount();
                t.play();
            } catch (RuntimeException ex) {
                d.error = "start " + ex.getClass().getSimpleName();
                Log.w(TAG, "声音管道开不起来", ex);
                track = null;
                t.removeOnRoutingChangedListener(routing);
                t.release();
                return null;
            }
            Log.i(TAG, "声音管道：" + d.line());
            return t;
        }

        /**
         * 拿到低延迟（快速通道）才把缓冲压到 targetFrames（容量以内）；拿不到就不动系统给的大小（普通混音一个周期 20 ms 以上，
         * 压到两个 HAL burst 会一直凑不够、一截一截地断）。再读一遍实际的（路由变了以后系统会重建轨道，低延迟、缓冲、容量都可能变）。
         */
        private void applyBuffer(AudioTrack t) {
            d.lowLatency = t.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
            d.capacityFrames = t.getBufferCapacityInFrames();
            if (d.lowLatency && targetFrames > 0) {
                int want = Math.min(d.capacityFrames, targetFrames);
                if (want > 0 && want != t.getBufferSizeInFrames()) t.setBufferSizeInFrames(want);
            }
            d.bufferFrames = t.getBufferSizeInFrames();
        }

        /**
         * 路由回调来了（主线程上置的标记）：查一次实际的出口（走 binder）、重读缓冲和低延迟；这几块的断流不算缓冲不够。
         * 这个回调不只在路由真变了时来：AOSP 每次轨道重新 play（闲置醒来、换套重算后、开管道后）都发 AUDIO_CLIENT_STARTED、无条件回调，
         * 所以只在查出来的东西真变了时才写日志。
         */
        private void routeChanged() {
            int type = d.deviceType;
            String name = d.deviceName;
            boolean ll = d.lowLatency;
            int buffer = d.bufferFrames, capacity = d.capacityFrames;
            checkRoute();
            applyBuffer(track);
            graceWrites = GRACE_WRITES;
            if (type != d.deviceType || !name.equals(d.deviceName) || ll != d.lowLatency
                    || buffer != d.bufferFrames || capacity != d.capacityFrames) {
                Log.i(TAG, "声音管道（路由变了）：" + d.line());
            }
        }

        /** 查实际路由（会走一次 binder，只在开管道 play 之前、路由变了、还不知道时没声音的空当里查——最多十次）。 */
        private void checkRoute() {
            routeDirty = false;
            routeCheckedAt = SystemClock.uptimeMillis();
            routeTries++;
            AudioTrack t = track;
            if (t == null) return;
            AudioDeviceInfo dev;
            try {
                dev = t.getRoutedDevice();
            } catch (RuntimeException ex) {
                dev = null;
            }
            int old = route;
            if (dev == null) {
                route = 0;
                d.deviceType = 0;
                d.deviceName = "";
                d.onSpeaker = false;
            } else {
                int type = dev.getType();
                boolean speaker = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE;
                route = speaker ? 1 : 2;
                d.deviceType = type;
                CharSequence name = dev.getProductName();
                d.deviceName = name == null ? "" : name.toString();
                d.onSpeaker = speaker;
            }
            if (route != old) Log.i(TAG, "声音的出口：设备类型 " + d.deviceType + (route == 1 ? "（手机喇叭）" : route == 2 ? "（不是手机喇叭，不出声）" : "（还不知道）"));
        }

        /** 实际路由是手机喇叭才出声；还不知道时，设上了"只从手机喇叭出"就先出。 */
        private boolean routeOk() {
            return route == 1 || (route == 0 && d.speakerPreferred);
        }

        private void closeTrack() {
            readyVersion = 0;
            trackPaused = false;
            AudioTrack t = track;
            if (t == null) return;
            track = null;
            try {
                t.removeOnRoutingChangedListener(routing);
                t.pause();
                t.flush();
            } catch (RuntimeException ignored) {
                // 已经坏了的管道，关掉就是
            }
            t.release();
        }

        private int raisePriority() {
            int[] tries = {Process.THREAD_PRIORITY_URGENT_AUDIO, Process.THREAD_PRIORITY_AUDIO};
            for (int p : tries) {
                try {
                    Process.setThreadPriority(p);
                    return p;
                } catch (RuntimeException ignored) {
                    // 拿不到就退一档
                }
            }
            return Process.getThreadPriority(Process.myTid());
        }
    }

    private static int parse(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
