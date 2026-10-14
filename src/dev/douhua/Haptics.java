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
    private final Handler main = new Handler(Looper.getMainLooper());
    private int humLevel = 1;
    private boolean humming;
    private final Runnable humStart = this::vibrateHum;
    private final Runnable humWatchdog = this::stopHum;

    Haptics(Context context) {
        vibrator = context.getSystemService(VibratorManager.class).getDefaultVibrator();
        primitives = vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK);
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
     */
    void play(int kind) {
        if (kind == GestureEngine.HAPTIC_DRAG_END) stopHum();   // 先停持续轻震，再"嗒"一下
        if (level <= 0 || !vibrator.hasVibrator()) return;
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
