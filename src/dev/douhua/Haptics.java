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

    Haptics(Context context) {
        vibrator = context.getSystemService(VibratorManager.class).getDefaultVibrator();
        primitives = vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK);
    }

    /** 0 关、1 小、2 中、3 大。 */
    void setLevel(int level) {
        this.level = level;
    }

    /** kind 是 GestureEngine.HAPTIC_*。 */
    void play(int kind) {
        if (level <= 0 || !vibrator.hasVibrator()) return;
        float scale = level == 1 ? 0.35f : level == 2 ? 0.65f : 1f;
        VibrationEffect effect;
        if (primitives) {
            VibrationEffect.Composition c = VibrationEffect.startComposition();
            switch (kind) {
                case GestureEngine.HAPTIC_DRAG_START:
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale);
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * 0.6f, 30);
                    break;
                case GestureEngine.HAPTIC_DRAG_END:
                case GestureEngine.HAPTIC_TICK:   // 滚轮的"咔嗒"：短促清脆
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale);
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
                case GestureEngine.HAPTIC_TICK:
                    id = VibrationEffect.EFFECT_TICK;
                    break;
                case GestureEngine.HAPTIC_GESTURE:
                case GestureEngine.HAPTIC_DRAG_START:
                    id = VibrationEffect.EFFECT_DOUBLE_CLICK;
                    break;
                default:
                    id = level >= 3 ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
                    break;
            }
            effect = VibrationEffect.createPredefined(id);
        }
        vibrator.vibrate(effect, ATTRS);
    }
}
