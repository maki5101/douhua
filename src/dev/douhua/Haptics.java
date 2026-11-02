package dev.douhua;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * 震动反馈。马达支持"基本震动单元"（短促清脆的 click / tick）就用它，否则退回系统预设效果。
 * 用 HARDWARE_FEEDBACK 用途：它模拟的是实体按键的手感，不受"触摸振动"开关影响。
 */
final class Haptics {
    private static final VibrationAttributes ATTRS =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK);

    private final Vibrator vibrator;
    private final boolean primitives;
    private int level;
    private int tickStrength = 1;

    // ── 拖动时的持续轻震 ──
    /** 各档（关 / 小 / 中 / 大）的幅度（1–255）。用户试过原来的"小"（45）嫌有点强，整体往下挪：现在的"中"≈ 原来的"小"。 */
    private static final int[] HUM_AMPLITUDES = {0, 18, 40, 80};
    /** 万一漏了"拖动结束"（比如和服务的连接断了），最多震这么久就停。 */
    private static final long HUM_MAX_MS = 60_000;
    /** 让前面那下"咔"先震完再接着嗡。 */
    private static final long HUM_AFTER_MS = 80;
    /** 支持最弱的轻触原语（"颗粒"用）。 */
    private final boolean lowTick;
    private static final float TEXTURE_SCALE = 0.3f;
    /** 没有震动原语、但能调幅度的马达（比如测试机）："颗粒"用一下 5 毫秒、幅度约一成的短震。 */
    private static final long TEXTURE_ONE_SHOT_MS = 5;
    private static final int TEXTURE_AMPLITUDE = 30;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ── 惯性滚动的"放开"（拨出去）和"刹住"（转着时碰屏幕） ──
    // 和每格的"咔嗒"（等强的一下）、点击（单下 CLICK）、开始拖动（CLICK→TICK）分得开。具体用哪种要手摸：
    // 调试版设置里列着候选（点一下震一下、选中的存下来），正式包用默认（-1）。默认按马达支持的挑：
    // 放开 = 旋转原语 → 三下渐弱的轻嗒 → 系统的 TICK；刹住 = 闷响原语 → 轻嗒 + 实的一下 → 系统的重击。
    /** 放开：旋转原语（模拟旋转的动量）。 */
    static final int SPIN_START_SPIN = 0;
    /** 放开：三下渐弱的轻嗒（"嗒嗒嗒"往远处走）。 */
    static final int SPIN_START_FALL = 1;
    /** 放开：快速下落原语。 */
    static final int SPIN_START_QUICK_FALL = 2;
    /** 刹住：闷响原语（带重量往下落的一下）。 */
    static final int SPIN_CATCH_THUD = 0;
    /** 刹住：轻嗒 + 20ms 后实的一下（往上收）。 */
    static final int SPIN_CATCH_RISE = 1;
    /** 刹住：系统的重击。 */
    static final int SPIN_CATCH_HEAVY = 2;
    static final int SPIN_STYLES = 3;
    /** 跟"咔嗒强度"三档（小 / 中 / 大）缩放。 */
    private static final float[] SPIN_SCALES = {0.6f, 0.8f, 1f};
    private int spinStartStyle = -1, spinCatchStyle = -1;
    private int humLevel = 1;
    private boolean humming;
    private final Runnable humStart = this::vibrateHum;
    private final Runnable humWatchdog = this::stopHum;

    // ── 调试版"震动延后"（声音第一批第 ④ 步，设置 → 外观 → 声音的候选区）：震动比声音晚多少，用户凭手感调到"合成一体" ──
    /** 延后多少毫秒（0 = 不延后，正式包、声音关着时一律 0：和以前逐字一样）。 */
    private int delayMs;
    /** 延后的那几种排在这里（Message.what = 震动种类，按键的"嗒"是 {@link #WHAT_KEY}；sendEmptyMessageDelayed 用消息池，不分配）。 */
    private static final int WHAT_KEY = 100;
    private final Handler delayed = new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what == WHAT_KEY) playKeyNow();
        else playNow(msg.what);
        return true;
    });

    Haptics(Context context) {
        vibrator = context.getSystemService(VibratorManager.class).getDefaultVibrator();
        primitives = vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK);
        lowTick = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK);
    }

    /** 0 关、1 小、2 中、3 大。 */
    void setLevel(int level) {
        this.level = level;
    }

    /**
     * 滚动"咔嗒"的强度：0 小、1 中、2 大。不跟总强度走（总强度为"关"时照样不响）。
     * 小 = 轻触开满、中 = 点击 × 0.8、大 = 系统的"重击"。
     */
    void setTickStrength(int strength) {
        this.tickStrength = strength;
    }

    /** 惯性"放开" / "刹住"用哪种波形（SPIN_*；-1 = 默认）。马达不支持选的那种就退回默认。 */
    void setSpinStyles(int start, int stop) {
        spinStartStyle = start;
        spinCatchStyle = stop;
    }

    /**
     * 调试版"震动延后"（0–40 ms，SoundBench.DELAY_MAX_MS）：有声音的那几种震动（按键的"嗒"、点一下、滚轮咔嗒、惯性刹住）晚这么多再震，
     * 用户凭手感调到声音和震动像一个东西为止。带着持续轻震的开始 / 结束拖动、颗粒、手势、惯性放开、按住到点不延后（开始 / 结束拖动连着嗡的开关，
     * 晚了会和紧接着的下一次拖动打架；别的没有声音，延后没意义）。0 = 不延后（正式包、声音关着时一律 0，和以前逐字一样）。
     */
    void setDelayMs(int ms) {
        int d = Math.max(0, ms);
        if (d == delayMs) return;
        delayMs = d;
        if (d == 0) delayed.removeCallbacksAndMessages(null);   // 不延后了：还排着的就不震了（最多少震一下）
    }

    /** 调试版（可调试包）：设置里列"放开 / 刹住"的候选波形；正式包一律用默认。 */
    static boolean tuning(Context c) {
        return (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** 这台马达放不放得出这种波形（设置页的候选标"不支持"用）。 */
    boolean spinSupported(boolean start, int style) {
        if (start) {
            switch (style) {
                case SPIN_START_SPIN: return vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_SPIN);
                case SPIN_START_FALL: return primitives;
                case SPIN_START_QUICK_FALL: return vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL);
                default: return false;
            }
        }
        switch (style) {
            case SPIN_CATCH_THUD: return vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD);
            case SPIN_CATCH_RISE: return primitives;
            case SPIN_CATCH_HEAVY: return true;   // 系统预设，哪台都有
            default: return false;
        }
    }

    /** 没挑过时用哪种（按马达支持的挑）；连原语都没有返回 -1（放开退成系统的 TICK）。 */
    int defaultSpinStyle(boolean start) {
        if (start) {
            if (spinSupported(true, SPIN_START_SPIN)) return SPIN_START_SPIN;
            return primitives ? SPIN_START_FALL : -1;
        }
        if (spinSupported(false, SPIN_CATCH_THUD)) return SPIN_CATCH_THUD;
        return primitives ? SPIN_CATCH_RISE : SPIN_CATCH_HEAVY;
    }

    /** 设置页的"试手感"：放一下指定的候选（不管存的是哪种）。 */
    void trySpin(boolean start, int style) {
        if (level <= 0 || !vibrator.hasVibrator()) return;
        vibrator.vibrate(spinEffect(start, style), ATTRS);
    }

    private VibrationEffect spinEffect(boolean start, int style) {
        if (!spinSupported(start, style)) style = defaultSpinStyle(start);
        float k = SPIN_SCALES[Math.max(0, Math.min(SPIN_SCALES.length - 1, tickStrength))];
        if (start) {
            switch (style) {
                case SPIN_START_SPIN:
                    return VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, k).compose();
                case SPIN_START_QUICK_FALL:
                    return VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, k).compose();
                case SPIN_START_FALL:
                    return VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f * k)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f * k, 25)
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f * k, 35).compose();
                default:
                    return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
        }
        switch (style) {
            case SPIN_CATCH_THUD:
                return VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, k).compose();
            case SPIN_CATCH_RISE:
                return VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f * k)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, k, 20).compose();
            default:
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
        }
    }

    /** 拖动时持续轻震的强度：0 关、1 小、2 中、3 大。总强度为"关"时也不震。 */
    void setDragHum(int level) {
        humLevel = level;
        if (level <= 0) stopHum();
    }

    /** 进入拖动：开始持续轻震（按左侧"左键"按钮时也调）。 */
    void startHum() {
        if (humLevel <= 0 || level <= 0 || !vibrator.hasVibrator()) return;
        humming = true;
        main.removeCallbacks(humStart);
        main.postDelayed(humStart, HUM_AFTER_MS);
        main.removeCallbacks(humWatchdog);
        main.postDelayed(humWatchdog, HUM_MAX_MS);
    }

    /** 拖动结束、豆花退到后台、连接断开、触摸被释放：停。 */
    void stopHum() {
        main.removeCallbacks(humStart);
        main.removeCallbacks(humWatchdog);
        if (!humming) return;
        humming = false;
        vibrator.cancel();
    }

    /**
     * 按键的"嗒"：先把还在震的上一下停掉，再震这一下。打得快时上一下可能还没震完（"大"档是系统的重击，余震长），
     * 不停的话要么等它放完、要么叠在一起，手感就拖在手指后面（用户 2026-10-22："不是轻重的问题，是不能打断"）。
     * 安卓原版对同一个 App 的新震动本来就会顶掉旧的，但三星没核对过，这里自己停，不靠系统。
     * 拖动时的持续轻震也会被停掉，play 里照常 80ms 后接着嗡。
     */
    void playKey() {
        if (level <= 0 || !vibrator.hasVibrator()) return;
        if (delayMs > 0) {
            delayed.sendEmptyMessageDelayed(WHAT_KEY, delayMs);
            return;
        }
        playKeyNow();
    }

    private void playKeyNow() {
        if (level <= 0 || !vibrator.hasVibrator()) return;
        vibrator.cancel();
        playNow(GestureEngine.HAPTIC_CLICK);
    }

    /** 低幅度一直嗡；马达不能调幅度时退成一下一下的轻点。 */
    private void vibrateHum() {
        if (!humming) return;
        VibrationEffect e = vibrator.hasAmplitudeControl()
                ? VibrationEffect.createOneShot(HUM_MAX_MS, HUM_AMPLITUDES[Math.min(humLevel, HUM_AMPLITUDES.length - 1)])
                : VibrationEffect.createWaveform(new long[]{0, 12, 88}, 0);
        vibrator.vibrate(e, ATTRS);
    }

    /**
     * kind 是 GestureEngine.HAPTIC_*。
     * 各档比 0.1.0 整体强一档（用户反馈太弱）：小 = 点击 × 0.65、中 = 点击开满、大 = 系统的"重击"
     * （"试手感"实测：拿在手里时它最强、也清脆；平放在桌上点，反而是点击开满最好）。
     * 两下一组的（开始拖动、三指四指手势）没法和"重击"拼在一起，大档仍用点击开满。
     * "按住到点"（HAPTIC_HOLD，协议 25 起单独一种，给声音分"点了一下"和"快锁了"）震的和点一下一模一样；
     * "拖动被取消着结束"（HAPTIC_DRAG_CANCEL，同样协议 25 起、给声音分"松了手"和"收尾"）震的和拖动结束一模一样。
     * 调试版"震动延后"开着时（{@link #setDelayMs}）点一下、滚轮咔嗒、惯性刹住晚那么多再震。
     */
    void play(int kind) {
        if (delayMs > 0 && (kind == GestureEngine.HAPTIC_CLICK || kind == GestureEngine.HAPTIC_TICK || kind == GestureEngine.HAPTIC_SPIN_CATCH)) {
            delayed.sendEmptyMessageDelayed(kind, delayMs);
            return;
        }
        playNow(kind);
    }

    private void playNow(int kind) {
        if (kind == GestureEngine.HAPTIC_HOLD) kind = GestureEngine.HAPTIC_CLICK;
        if (kind == GestureEngine.HAPTIC_DRAG_CANCEL) kind = GestureEngine.HAPTIC_DRAG_END;
        if (kind == GestureEngine.HAPTIC_DRAG_END) stopHum();   // 先停持续轻震，再"嗒"一下
        if (level <= 0 || !vibrator.hasVibrator()) return;
        if (kind == GestureEngine.HAPTIC_TEXTURE) {
            // "颗粒"：越轻越好。按手机支持的挑：最弱的轻触原语 → 普通轻触原语压低 → 能调幅度就来一下极短极弱的；
            // 都不行就不震，不拿更重的效果凑数（会变成一路"嗒嗒嗒"）
            VibrationEffect grain = null;
            if (lowTick) {
                grain = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, TEXTURE_SCALE).compose();
            } else if (primitives) {
                grain = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, TEXTURE_SCALE / 2).compose();
            } else if (vibrator.hasAmplitudeControl()) {
                grain = VibrationEffect.createOneShot(TEXTURE_ONE_SHOT_MS, TEXTURE_AMPLITUDE);
            }
            if (grain != null) vibrator.vibrate(grain, ATTRS);
            return;
        }
        if (kind == GestureEngine.HAPTIC_SPIN_START || kind == GestureEngine.HAPTIC_SPIN_CATCH) {
            boolean start = kind == GestureEngine.HAPTIC_SPIN_START;
            vibrator.vibrate(spinEffect(start, start ? spinStartStyle : spinCatchStyle), ATTRS);
            if (humming) {   // 按着左键时拨 / 刹：震完接着嗡
                main.removeCallbacks(humStart);
                main.postDelayed(humStart, HUM_AFTER_MS);
            }
            return;
        }
        boolean heavy = kind == GestureEngine.HAPTIC_TICK ? tickStrength >= 2
                : kind == GestureEngine.HAPTIC_CLICK && level >= 3;
        if (heavy) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK), ATTRS);
            return;
        }
        float scale = level == 1 ? 0.65f : 1f;
        VibrationEffect effect;
        if (primitives) {
            VibrationEffect.Composition c = VibrationEffect.startComposition();
            switch (kind) {
                case GestureEngine.HAPTIC_DRAG_START:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale);
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * 0.6f, 30);
                    break;
                case GestureEngine.HAPTIC_DRAG_END:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale);
                    break;
                case GestureEngine.HAPTIC_TICK:   // 滚轮的"咔嗒"：短促清脆，强度单独调
                    if (tickStrength == 0) {
                        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f);
                    } else {
                        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f);
                    }
                    break;
                case GestureEngine.HAPTIC_GESTURE:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale);
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.7f, 50);
                    break;
                default:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale);
                    break;
            }
            effect = c.compose();
        } else {
            int id;
            switch (kind) {
                case GestureEngine.HAPTIC_DRAG_END:
                    id = VibrationEffect.EFFECT_TICK;
                    break;
                case GestureEngine.HAPTIC_TICK:
                    id = tickStrength == 0 ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK;
                    break;
                case GestureEngine.HAPTIC_GESTURE:
                case GestureEngine.HAPTIC_DRAG_START:
                    id = VibrationEffect.EFFECT_DOUBLE_CLICK;
                    break;
                default:
                    id = VibrationEffect.EFFECT_CLICK;
                    break;
            }
            effect = VibrationEffect.createPredefined(id);
        }
        vibrator.vibrate(effect, ATTRS);
        if (kind == GestureEngine.HAPTIC_DRAG_START) {
            startHum();
        } else if (humming) {   // 拖动中来了别的震动（比如按键），它会打断持续轻震：震完接着嗡
            main.removeCallbacks(humStart);
            main.postDelayed(humStart, HUM_AFTER_MS);
        }
    }
}
