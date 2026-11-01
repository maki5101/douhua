package dev.douhua;

/**
 * 惯性滚动的物理（模拟罗技滚轮的自由转：拨一下转好久、自己慢慢停）。纯 Java，GestureEngine 和设置页共用，离线测。
 *
 * 减速是黏滞 + 一点恒定摩擦：dv/dt = −(v + S) / τ（v 是格/秒，S = {@link #FRICTION}）。
 * 高速时像指数衰减（越转越慢、尾巴自然），低速时那点恒定摩擦让它在有限时间里真停——纯指数衰减会在 1 格/秒以下拖很久，看着"粘"。
 * 每一步都用闭式解推进（{@link #advance}），和定时器准不准无关：晚到的一帧照真实经过的时间走，总距离不变。
 * τ 由阻尼档位定（{@link #tauMs}）：1 档最轻（τ = 3 秒），10 档最重（τ = 0.25 秒），中间按等比分。
 */
public final class ScrollInertia {
    private ScrollInertia() {}

    /** 恒定摩擦（格/秒）。 */
    public static final double FRICTION = 3;
    /** 起转速度上限（格/秒）：防一下甩飞（对方系统可能还会再加速）。 */
    public static final double MAX_SPEED = 150;
    /** 最长转多久（毫秒）：兜底，按公式最轻档最快一拨也只要约 12 秒。 */
    public static final long MAX_MS = 15000;
    public static final int LEVELS = 10;
    public static final int DEFAULT_LEVEL = 4;
    /** 设置页"中等的一拨大约转几秒"按这个起转速度算（格/秒）。 */
    public static final double TYPICAL_SPEED = 50;
    private static final double TAU_LIGHT = 3.0, TAU_HEAVY = 0.25;

    /** 阻尼档位（1–{@link #LEVELS}，越大停得越快）→ τ（毫秒）。超出范围的截到两头。 */
    public static int tauMs(int level) {
        level = Math.max(1, Math.min(LEVELS, level));
        double tau = TAU_LIGHT * Math.pow(TAU_HEAVY / TAU_LIGHT, (level - 1) / (double) (LEVELS - 1));
        return (int) Math.round(tau * 1000);
    }

    /**
     * 从速度 v（格/秒，取大小）推进 dt 秒：返回 {推进后的速度, 这一段走的格数}。
     * 中途停下的只走到停的那一刻，返回的速度是 0。
     */
    public static double[] advance(double v, double dt, double tau) {
        if (v <= 0 || dt <= 0 || tau <= 0) return new double[]{Math.max(0, v), 0};
        double e = Math.exp(-dt / tau);
        double next = (v + FRICTION) * e - FRICTION;
        if (next > 0) return new double[]{next, (v + FRICTION) * tau * (1 - e) - FRICTION * dt};
        return new double[]{0, distance(v, tau)};
    }

    /** 从速度 v0 自己转到停要多少秒：τ·ln(1 + v0 / S)。 */
    public static double stopSeconds(double v0, double tau) {
        return v0 <= 0 ? 0 : tau * Math.log(1 + v0 / FRICTION);
    }

    /** 从速度 v0 自己转到停一共走多少格：v0·τ − S·t停。 */
    public static double distance(double v0, double tau) {
        return v0 <= 0 ? 0 : v0 * tau - FRICTION * stopSeconds(v0, tau);
    }
}
