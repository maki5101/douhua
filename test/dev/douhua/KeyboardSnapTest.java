package dev.douhua;

/** KeyboardSnap 的离线测试。 */
public final class KeyboardSnapTest {
    private static int failures;
    private static int checks;
    private static final float S = 0.02f;   // 吸附范围：约 10dp / 470dp

    public static void main(String[] args) {
        float[] out = new float[4];

        // 挪位置：中心离正中 0.01 → 吸到正中
        int hit = KeyboardSnap.apply(new float[]{0.06f, 0.3f, 0.96f, 0.9f}, out, KeyboardSnap.MOVE, S, S);
        near(out[0] + out[2], 1f, "挪位置：靠近正中吸到居中");
        check((hit & KeyboardSnap.CENTER) != 0, "报告吸到居中");

        // 宽度 0.98、左边离边 0.01：居中优先（不会因为离左边近就贴左边）
        KeyboardSnap.apply(new float[]{0.01f, 0.3f, 0.99f, 0.9f}, out, KeyboardSnap.MOVE, S, S);
        near(out[0], 0.01f, "宽度接近满屏时居中优先，左右各留一样多");

        // 离正中远、离左边近：贴左边
        hit = KeyboardSnap.apply(new float[]{0.015f, 0.3f, 0.715f, 0.9f}, out, KeyboardSnap.MOVE, S, S);
        near(out[0], 0f, "离左边近：贴左边");
        near(out[2] - out[0], 0.7f, "宽度不变");
        check((hit & KeyboardSnap.AT_LEFT) != 0, "报告贴左边");

        // 拖过吸附范围：不吸
        hit = KeyboardSnap.apply(new float[]{0.1f, 0.3f, 0.8f, 0.9f}, out, KeyboardSnap.MOVE, S, S);
        near(out[0], 0.1f, "离开吸附范围就照手指位置");
        check(hit == 0, "没吸");

        // 竖向贴底
        KeyboardSnap.apply(new float[]{0f, 0.29f, 1f, 0.99f}, out, KeyboardSnap.MOVE, S, S);
        near(out[3], 1f, "靠近底边吸到底");
        near(out[3] - out[1], 0.7f, "高度不变");

        // 改宽度：拖右边缩一点，正好接近对称 → 对称
        hit = KeyboardSnap.apply(new float[]{0.05f, 0.3f, 0.945f, 0.9f}, out, KeyboardSnap.RIGHT, S, S);
        near(out[2], 0.95f, "改宽度接近对称时吸到对称");
        check((hit & KeyboardSnap.CENTER) != 0, "报告吸到居中");

        // 改宽度：缩了一截，离边和对称都远 → 不吸（不会被拽回贴边）
        hit = KeyboardSnap.apply(new float[]{0f, 0.3f, 0.9f, 0.9f}, out, KeyboardSnap.RIGHT, S, S);
        near(out[2], 0.9f, "缩了一截不会被拽回贴边");
        check(hit == 0, "没吸");

        // 原数组不被改
        float[] raw = {0.06f, 0.3f, 0.96f, 0.9f};
        KeyboardSnap.apply(raw, out, KeyboardSnap.MOVE, S, S);
        near(raw[0], 0.06f, "手指位置 raw 不被改（继续拖能拖过吸附点）");

        System.err.println("键盘吸附：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static void near(float a, float b, String what) {
        check(Math.abs(a - b) < 1e-4f, what + "（期望 " + b + "，实际 " + a + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
