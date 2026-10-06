package dev.douhua;

/**
 * 调整键盘大小 / 位置时的轻微吸附。纯 Java、不依赖 Android（离线测试）。
 *
 * 输入 raw（手指拖到的位置，{左, 上, 右, 下}，0–1），输出吸附后显示的位置。raw 本身不改，所以继续拖就能拖过吸附点。
 * 挪位置 / 改宽度时居中优先于贴边：缩一点宽度不会被拽回两边贴边。
 */
final class KeyboardSnap {
    static final int MOVE = 1;
    static final int LEFT = 2;
    static final int TOP = 3;
    static final int RIGHT = 4;
    static final int BOTTOM = 5;

    static final int CENTER = 1;
    static final int AT_LEFT = 2;
    static final int AT_RIGHT = 4;
    static final int AT_TOP = 8;
    static final int AT_BOTTOM = 16;

    private KeyboardSnap() {}

    /**
     * @param sx 横向吸附范围（占宽度的比例）
     * @param sy 竖向吸附范围（占高度的比例）
     * @return 吸上了哪些（CENTER / AT_* 的组合）
     */
    static int apply(float[] raw, float[] out, int handle, float sx, float sy) {
        System.arraycopy(raw, 0, out, 0, 4);
        int hit = 0;
        float w = out[2] - out[0];
        float h = out[3] - out[1];
        boolean nearCenter = Math.abs((out[0] + out[2]) / 2 - 0.5f) < sx;
        switch (handle) {
            case MOVE:
                if (nearCenter) {
                    out[0] = 0.5f - w / 2;
                    hit |= CENTER;
                } else if (out[0] < sx) {
                    out[0] = 0;
                    hit |= AT_LEFT;
                } else if (1 - out[2] < sx) {
                    out[0] = 1 - w;
                    hit |= AT_RIGHT;
                }
                out[2] = out[0] + w;
                if (out[1] < sy) {
                    out[1] = 0;
                    hit |= AT_TOP;
                } else if (1 - out[3] < sy) {
                    out[1] = 1 - h;
                    hit |= AT_BOTTOM;
                }
                out[3] = out[1] + h;
                break;
            case LEFT:
                if (out[0] < sx) {
                    out[0] = 0;
                    hit |= AT_LEFT;
                } else if (Math.abs((out[0] + out[2]) / 2 - 0.5f) < sx / 2) {
                    out[0] = 1 - out[2];   // 改宽度时正好接近居中：对称
                    hit |= CENTER;
                }
                break;
            case RIGHT:
                if (1 - out[2] < sx) {
                    out[2] = 1;
                    hit |= AT_RIGHT;
                } else if (Math.abs((out[0] + out[2]) / 2 - 0.5f) < sx / 2) {
                    out[2] = 1 - out[0];
                    hit |= CENTER;
                }
                break;
            case TOP:
                if (out[1] < sy) {
                    out[1] = 0;
                    hit |= AT_TOP;
                }
                break;
            case BOTTOM:
                if (1 - out[3] < sy) {
                    out[3] = 1;
                    hit |= AT_BOTTOM;
                }
                break;
            default:
                break;
        }
        return hit;
    }
}
