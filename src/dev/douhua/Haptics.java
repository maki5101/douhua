package dev.douhua;

import android.content.Context;
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

    /**
     * kind 是 GestureEngine.HAPTIC_*。
     * 各档比 0.1.0 整体强一档（用户反馈太弱）：小 = 点击 × 0.65、中 = 点击开满、大 = 系统的"重击"
     * （"试手感"实测：拿在手里时它最强、也清脆；平放在桌上点，反而是点击开满最好）。
     * 两下一组的（开始拖动、三指四指手势）没法和"重击"拼在一起，大档仍用点击开满。
     */
    void play(int kind) {
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
    }
}
