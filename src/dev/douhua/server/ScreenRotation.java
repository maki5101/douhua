package dev.douhua.server;

/**
 * 把"没转过的物理屏坐标"换成"当前方向的屏幕坐标"。纯 Java、不依赖 Android（离线测试）。
 *
 * 为什么要自己转：Android 17（三星 One UI 9）上，dumpsys input 里触摸屏的 RawToDisplay 矩阵只换算到没转过的物理屏，
 * 屏幕旋转由系统在后面另做；而 App 报来的区域位置是当前方向的坐标。
 *
 * 方向 1（ROTATION_90）的公式来自真机：横屏时系统记录的屏幕变换是 x = 原 y，y = 竖屏宽 − 原 x。
 */
final class ScreenRotation {
    private ScreenRotation() {}

    /**
     * @param nx       物理屏坐标（竖屏，自然方向）
     * @param rotation Surface.ROTATION_*（0–3）
     * @param naturalW 自然方向（竖屏）的宽
     * @param naturalH 自然方向（竖屏）的高
     * @return {x, y} 当前方向的屏幕坐标
     */
    static float[] apply(float nx, float ny, int rotation, float naturalW, float naturalH) {
        switch (rotation & 3) {
            case 1:
                return new float[]{ny, naturalW - nx};
            case 2:
                return new float[]{naturalW - nx, naturalH - ny};
            case 3:
                return new float[]{naturalH - ny, nx};
            default:
                return new float[]{nx, ny};
        }
    }
}
