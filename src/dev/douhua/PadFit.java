package dev.douhua;

/**
 * 触控板背景图怎么摆（设置 → 外观；纯 Java，不 import android.*，离线测 PadFitTest）。
 * 触控板的形状一直在变（主界面竖长 / 横宽、开数字键盘变小；键盘页合并时宽扁、分裂时窄高、分屏下半边更扁），
 * 所以设置按"显示方式 + 焦点"给，不按某一种布局存一套：
 * - 显示方式五选一（FIT_*）：填满（多出来的裁掉）、完整显示（空出来的露触控板底色）、拉伸、平铺、原大小；
 * - 焦点 (fx, fy) ∈ [0,1]²：图上的一点，要裁时尽量留在正中、有空余时照 CSS object-position 的百分比靠边 / 居中（{@link #offset}）；
 * - 可选"宽的和高的触控板分开设"（Settings.split）：宽 ≥ 高用一套、高 > 宽用另一套（按形状不按横竖屏：横屏分裂时中间那块是高的）。
 * 坐标一律是像素，x 向右、y 向下；{@link #place} 的结果 = 位图像素 → 触控板（内框）坐标的缩放 + 平移，PadImage 拿它设 BitmapShader 的变换。
 */
final class PadFit {
    /** 显示方式（存进 Prefs 的值，顺序就是设置页里的顺序，不能改）。 */
    static final int FIT_COVER = 0, FIT_CONTAIN = 1, FIT_STRETCH = 2, FIT_TILE = 3, FIT_ORIGINAL = 4;
    static final int FIT_COUNT = 5;
    /** 焦点离 0 / 0.5 / 1 这么近就吸过去（贴边、居中点得准，等于 3×3 对齐）。 */
    static final float SNAP = 0.06f;
    /** 不透明度（百分比）：没有 0%（0% 等于没图，那该用"移除图片"）。 */
    static final int ALPHA_MIN = 10, ALPHA_MAX = 100, ALPHA_STEP = 5, ALPHA_DEFAULT = 100;
    /** "图片大小"（百分比，只管平铺 / 原大小）：100% = 一个图片像素一个屏幕像素。 */
    static final int SCALE_MIN = 25, SCALE_MAX = 400, SCALE_STEP = 5, SCALE_DEFAULT = 100;
    /** 解码上限：总像素 2²³（按 4 字节 / 像素约 32MB）、单边 4096（纹理上限，凭记忆说几乎所有机器 ≥ 4096）。 */
    static final long MAX_PIXELS = 1L << 23;
    static final int MAX_SIDE = 4096;
    /** 导入时拷原文件，最多这么大（MB）。 */
    static final int MAX_FILE_MB = 50;
    /** 需要的比解出来的大这么多才重新解（Fold 展开、挪到外接屏；小一点的差别靠线性过滤放大，看不出来）。 */
    static final double REDECODE_RATIO = 1.05;

    private PadFit() {}

    /** 一套"怎么摆"：显示方式、焦点、图片大小。构造时把越界的值夹回去（存坏了 / 以后改了范围也不出怪事）。 */
    static final class Look {
        final int fit;
        final float fx, fy;
        final int scalePct;

        Look(int fit, float fx, float fy, int scalePct) {
            this.fit = fit >= 0 && fit < FIT_COUNT ? fit : FIT_COVER;
            this.fx = unit(fx);
            this.fy = unit(fy);
            this.scalePct = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scalePct));
        }

        static Look defaults() {
            return new Look(FIT_COVER, 0.5f, 0.5f, SCALE_DEFAULT);
        }

        Look withFit(int f) {
            return new Look(f, fx, fy, scalePct);
        }

        Look withFocus(float x, float y) {
            return new Look(fit, x, y, scalePct);
        }

        Look withScale(int pct) {
            return new Look(fit, fx, fy, pct);
        }

        /** "图片大小"有用吗（平铺 / 原大小）：设置页只在这时显示那个滑块。 */
        boolean usesScale() {
            return fit == FIT_TILE || fit == FIT_ORIGINAL;
        }

        /** 焦点有用吗：拉伸时没有（铺满、不裁不留空），设置页藏起来。 */
        boolean usesFocus() {
            return fit != FIT_STRETCH;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Look)) return false;
            Look l = (Look) o;
            return fit == l.fit && fx == l.fx && fy == l.fy && scalePct == l.scalePct;
        }

        @Override
        public int hashCode() {
            return ((fit * 31 + Float.floatToIntBits(fx)) * 31 + Float.floatToIntBits(fy)) * 31 + scalePct;
        }
    }

    /** 背景图的全部设置（Prefs.padBg 读出来的一份快照，不可变）。file 空 = 没设图。 */
    static final class Settings {
        final String file;
        final boolean animated;
        final int alphaPct;
        final boolean split;
        final Look wide, tall;

        Settings(String file, boolean animated, int alphaPct, boolean split, Look wide, Look tall) {
            this.file = file == null ? "" : file;
            this.animated = animated;
            this.alphaPct = Math.max(ALPHA_MIN, Math.min(ALPHA_MAX, alphaPct));
            this.split = split;
            this.wide = wide == null ? Look.defaults() : wide;
            this.tall = tall == null ? this.wide : tall;
        }

        boolean hasImage() {
            return !file.isEmpty();
        }

        /** 这块触控板（宽 w、高 h）用哪套：分开设时高 > 宽用高的那套，其余（含正方形、没分开设）用宽的那套。 */
        Look pick(double w, double h) {
            return split && h > w ? tall : wide;
        }

        /** 眼下用得到的几套（解码多大要照顾到每一套）。 */
        Look[] used() {
            return split ? new Look[]{wide, tall} : new Look[]{wide};
        }

        Settings withLook(boolean forTall, Look l) {
            return forTall ? new Settings(file, animated, alphaPct, split, wide, l) : new Settings(file, animated, alphaPct, split, l, tall);
        }

        Settings withAlpha(int pct) {
            return new Settings(file, animated, pct, split, wide, tall);
        }

        /** 解码用的那几项（文件 + 用得到的几套）拼成一个键：一样就不用重新排一次解码。 */
        String decodeKey(int maxSide) {
            StringBuilder b = new StringBuilder(file).append('|').append(maxSide);
            for (Look l : used()) b.append('|').append(l.fit).append(',').append(l.scalePct);
            return b.toString();
        }
    }

    /**
     * 摆法：触控板坐标 = (dx + 位图 x × sx, dy + 位图 y × sy)（相对内框左上角）。
     * tile = 平铺：这时 (dx, dy) 是砖缝的相位，都在 (−砖宽, 0] / (−砖高, 0] 里（BitmapShader 的 REPEAT 自己往两边铺）。
     */
    static final class Placement {
        final double sx, sy, dx, dy;
        final boolean tile;

        Placement(double sx, double sy, double dx, double dy, boolean tile) {
            this.sx = sx;
            this.sy = sy;
            this.dx = dx;
            this.dy = dy;
            this.tile = tile;
        }
    }

    /** 焦点吸附：离 0 / 0.5 / 1 在 SNAP 以内吸过去，其它不动；结果在 [0,1]（NaN 当正中）。 */
    static float snap(float f) {
        f = unit(f);
        float[] marks = {0f, 0.5f, 1f};
        for (float m : marks) if (Math.abs(f - m) <= SNAP + 1e-6f) return m;   // 1e-6：0.56f − 0.5f 在 float 里比 0.06f 大一点点
        return f;
    }

    /**
     * 一个轴上图的偏移：盒子长 box、缩放后的图长 size、焦点 focus。
     * 要裁（size ≥ box）：焦点尽量放在盒子正中，但不露空（夹在 [box − size, 0]）；
     * 有空余（size < box）：focus × 空余，和 CSS 的 object-position: focus% 一样（0 靠左 / 上、0.5 居中、1 靠右 / 下）。
     * 两条在 size = box 时都是 0，所以拖分隔条时图不跳；focus 取 0 / 0.5 / 1 时两条都是贴边或居中，吸附点等于 3×3 对齐。
     */
    static double offset(double box, double size, double focus) {
        if (size >= box) return Math.max(box - size, Math.min(0, box / 2 - focus * size));
        return focus * (box - size);
    }

    /**
     * 算摆法。boxW / boxH = 内框大小；srcW / srcH = 原图大小（转正后的）；bmpW / bmpH = 解出来的位图大小（可能缩过）；
     * unit = 一个"屏幕像素"在这里是多少像素（触控板上是 1；设置页的小预览按比例缩小）——只有原大小、平铺用得着。
     * 盒子或图是空的返回 null（不画）。
     */
    static Placement place(double boxW, double boxH, int srcW, int srcH, int bmpW, int bmpH, Look look, double unit) {
        if (!(boxW > 0) || !(boxH > 0) || srcW <= 0 || srcH <= 0 || bmpW <= 0 || bmpH <= 0 || !(unit > 0)) return null;
        switch (look.fit) {
            case FIT_STRETCH:
                return new Placement(boxW / bmpW, boxH / bmpH, 0, 0, false);
            case FIT_CONTAIN:
            case FIT_COVER: {
                double sw = boxW / bmpW, sh = boxH / bmpH;
                double s = look.fit == FIT_COVER ? Math.max(sw, sh) : Math.min(sw, sh);
                return new Placement(s, s, offset(boxW, bmpW * s, look.fx), offset(boxH, bmpH * s, look.fy), false);
            }
            default: {
                // 原大小 / 平铺：显示出来正好是 原图 × 图片大小 × unit（解码时缩过的那部分在这里补回去，两个轴各补各的，免得取整差一点）
                double k = look.scalePct / 100.0 * unit;
                double w = srcW * k, h = srcH * k;
                double dx = offset(boxW, w, look.fx), dy = offset(boxH, h, look.fy);
                boolean tile = look.fit == FIT_TILE;
                if (tile) {
                    dx = phase(dx, w);
                    dy = phase(dy, h);
                }
                return new Placement(w / bmpW, h / bmpH, dx, dy, tile);
            }
        }
    }

    /** 平铺的相位：同一张砖往左 / 上挪整数块看不出区别，挪进 (−砖长, 0]。 */
    static double phase(double off, double tile) {
        double p = off % tile;
        if (p > 0) p -= tile;
        if (p <= -tile) p += tile;   // 浮点误差兜一下
        return p;
    }

    /**
     * 解码多大（{宽, 高}，转正后的像素）：原图 srcW × srcH，窗口最大可能的长边 maxSide（触控板的宽、高都不会超过它）。
     * 填满 / 完整显示 / 拉伸：短边缩到 maxSide（不放大）——这样画在任何不超过 maxSide × maxSide 的触控板上都不用再放大
     * （三种里要的缩放最大是 maxSide / 短边，在正方形的最大盒子上填满时）；平铺 / 原大小：缩到"图片大小"（不放大，放大交给画的时候）。
     * 几套取要得最大的那个；再压到总像素 ≤ MAX_PIXELS、单边 ≤ MAX_SIDE（这时极端形状上会略微放大、糊一点）。
     */
    static int[] decodeSize(int srcW, int srcH, int maxSide, Look... looks) {
        if (srcW <= 0 || srcH <= 0) return new int[]{1, 1};
        double s = 0;
        for (Look l : looks) {
            double need = l.usesScale() ? l.scalePct / 100.0 : (double) Math.max(1, maxSide) / Math.min(srcW, srcH);
            s = Math.max(s, need);
        }
        if (looks.length == 0) s = 1;
        s = Math.min(s, 1);
        s = Math.min(s, Math.sqrt((double) MAX_PIXELS / ((double) srcW * srcH)));
        s = Math.min(s, (double) MAX_SIDE / Math.max(srcW, srcH));
        int w = clampSide(Math.round(srcW * s)), h = clampSide(Math.round(srcH * s));
        if ((long) w * h > MAX_PIXELS) {   // 四舍五入多出来一点：往下取整就一定在上限以内
            w = clampSide((long) Math.floor(srcW * s));
            h = clampSide((long) Math.floor(srcH * s));
        }
        return new int[]{w, h};
    }

    private static int clampSide(long v) {
        return (int) Math.max(1, Math.min(MAX_SIDE, v));
    }

    /** 手上有 haveW × haveH 的位图，现在要 wantW × wantH：大出 REDECODE_RATIO 以上才重新解（小了不重解，线性过滤 + mipmap 缩小）。 */
    static boolean needsRedecode(int haveW, int haveH, int wantW, int wantH) {
        if (haveW <= 0 || haveH <= 0) return true;
        return wantW > haveW * REDECODE_RATIO || wantH > haveH * REDECODE_RATIO;
    }

    private static float unit(float f) {
        if (Float.isNaN(f)) return 0.5f;
        return Math.max(0f, Math.min(1f, f));
    }
}
