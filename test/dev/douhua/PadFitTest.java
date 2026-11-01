package dev.douhua;

import java.util.Random;

/** PadFit（触控板背景图怎么摆）的离线测试。 */
public final class PadFitTest {
    private static int failures;
    private static int checks;
    private static final Random RND = new Random(20261031);

    public static void main(String[] args) {
        cover();
        focusRule();
        continuity();
        containAndStretch();
        original();
        tile();
        snap();
        pick();
        decodeSize();
        redecode();
        degenerate();
        clamps();
        themeFillOpaque();
        System.err.println("触控板背景图：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 填满 ═══════════

    private static void cover() {
        int[][] imgs = {{1, 1}, {10000, 10}, {10, 10000}, {1920, 1080}, {1080, 1920}, {512, 512}, {3, 7000}};
        for (int round = 0; round < 400; round++) {
            int[] img = round < imgs.length ? imgs[round] : new int[]{1 + RND.nextInt(6000), 1 + RND.nextInt(6000)};
            double bw = round % 7 == 0 ? 1 : 1 + RND.nextInt(3000), bh = round % 11 == 0 ? 3000 : 1 + RND.nextInt(3000);
            float fx = pickFocus(round), fy = pickFocus(round / 3);
            PadFit.Look l = new PadFit.Look(PadFit.FIT_COVER, fx, fy, 100);
            PadFit.Placement p = PadFit.place(bw, bh, img[0], img[1], img[0], img[1], l, 1);
            String at = "填满 " + img[0] + "×" + img[1] + " → " + bw + "×" + bh + " 焦点 " + fx + "," + fy;
            check(p != null, at + "：有摆法");
            if (p == null) continue;
            near(p.sx, p.sy, 1e-9 * p.sx, at + "：不变形");
            double sw = img[0] * p.sx, sh = img[1] * p.sy;
            check(p.dx <= 1e-6 && p.dx + sw >= bw - 1e-6 * bw, at + "：横向盖满");
            check(p.dy <= 1e-6 && p.dy + sh >= bh - 1e-6 * bh, at + "：竖向盖满");
            check(Math.abs(sw - bw) < 1e-6 * bw || Math.abs(sh - bh) < 1e-6 * bh, at + "：至少一个轴正好贴满（不多放大）");
            edgeAligned(p.dx, sw, bw, fx, at + "：横向");
            edgeAligned(p.dy, sh, bh, fy, at + "：竖向");
        }
    }

    /** 焦点取 0 / 0.5 / 1 时正好贴左（上）/ 居中 / 贴右（下）。 */
    private static void edgeAligned(double off, double size, double box, float f, String at) {
        double tol = 1e-6 * Math.max(box, size);
        if (f == 0f) near(off, 0, tol, at + "焦点 0 贴边");
        if (f == 0.5f) near(off, (box - size) / 2, tol, at + "焦点 0.5 居中");
        if (f == 1f) near(off + size, box, tol, at + "焦点 1 贴另一边");
    }

    private static float pickFocus(int i) {
        switch (i % 5) {
            case 0: return 0f;
            case 1: return 0.5f;
            case 2: return 1f;
            default: return RND.nextFloat();
        }
    }

    // ═══════════ 焦点规则 ═══════════

    private static void focusRule() {
        for (int i = 0; i < 2000; i++) {
            double box = 1 + RND.nextInt(2000), size = 1 + RND.nextInt(4000);
            double f = RND.nextDouble();
            double off = PadFit.offset(box, size, f);
            String at = "焦点规则 盒子 " + box + " 图 " + size + " 焦点 " + f;
            if (size >= box) {
                check(off <= 1e-9 && off >= box - size - 1e-9, at + "：要裁时不露空");
                double point = off + f * size;   // 焦点画到哪
                boolean clamped = Math.abs(off) < 1e-9 || Math.abs(off - (box - size)) < 1e-9;
                check(clamped || Math.abs(point - box / 2) < 1e-6, at + "：没被夹住时焦点正好在中线上");
                if (!clamped) continue;
                // 被夹住：焦点离中线的方向和贴的边一致（焦点在左半就贴左边）
                if (Math.abs(off) < 1e-9 && size > box) check(point <= box / 2 + 1e-6, at + "：贴左边时焦点在中线左边");
            } else {
                near(off, f * (box - size), 1e-9, at + "：有空余时照百分比");
            }
        }
        // S = B 处两条规则接得上
        for (int i = 0; i < 200; i++) {
            double box = 10 + RND.nextInt(2000), f = RND.nextDouble(), e = 1e-4;
            check(Math.abs(PadFit.offset(box, box + e, f)) <= e + 1e-9 && Math.abs(PadFit.offset(box, box - e, f)) <= e + 1e-9,
                    "S = B 两侧的偏移都趋于 0（盒子 " + box + "，焦点 " + f + "）");
        }
    }

    // ═══════════ 盒子连续变化时不跳 ═══════════

    private static void continuity() {
        int[][] imgs = {{1600, 900}, {900, 1600}, {800, 800}, {4000, 300}};
        int[] fits = {PadFit.FIT_COVER, PadFit.FIT_CONTAIN, PadFit.FIT_ORIGINAL, PadFit.FIT_STRETCH};
        for (int[] img : imgs) {
            for (int fit : fits) {
                for (float f : new float[]{0f, 0.3f, 0.5f, 0.85f, 1f}) {
                    PadFit.Look l = new PadFit.Look(fit, f, 1 - f, 100);
                    double bh = 500, step = 0.5;
                    PadFit.Placement prev = null;
                    double worst = 0;
                    for (double bw = 100; bw <= 2500; bw += step) {
                        PadFit.Placement p = PadFit.place(bw, bh, img[0], img[1], img[0], img[1], l, 1);
                        if (prev != null) {
                            // 一步 0.5 像素：偏移、缩放后的大小每步最多变"步长 × 图的长宽比"那么多（填满 / 完整显示时另一个轴跟着缩放）
                            double ratio = Math.max(1, Math.max((double) img[0] / img[1], (double) img[1] / img[0]));
                            worst = Math.max(worst, Math.max(Math.abs(p.dx - prev.dx), Math.abs(p.dy - prev.dy)) / (step * ratio));
                        }
                        prev = p;
                    }
                    check(worst <= 1.0001, "拖分隔条时图不跳（" + img[0] + "×" + img[1] + " 方式 " + fit + " 焦点 " + f + "，每步最多 " + worst + " 倍）");
                }
            }
        }
        // 原大小正好跨过 图 = 盒子（800 像素的图，盒子 400 → 1200）
        PadFit.Look l = new PadFit.Look(PadFit.FIT_ORIGINAL, 0.3f, 0.3f, 100);
        double prev = Double.NaN;
        boolean ok = true;
        for (double bw = 400; bw <= 1200; bw += 0.25) {
            double dx = PadFit.place(bw, 300, 800, 200, 800, 200, l, 1).dx;
            if (!Double.isNaN(prev) && Math.abs(dx - prev) > 0.25 + 1e-9) ok = false;
            prev = dx;
        }
        check(ok, "原大小：图和盒子一样大的前后偏移连续");
    }

    // ═══════════ 完整显示、拉伸 ═══════════

    private static void containAndStretch() {
        for (int i = 0; i < 500; i++) {
            int iw = 1 + RND.nextInt(5000), ih = 1 + RND.nextInt(5000);
            double bw = 1 + RND.nextInt(2500), bh = 1 + RND.nextInt(2500);
            float fx = pickFocus(i), fy = pickFocus(i + 2);
            String at = "完整显示 " + iw + "×" + ih + " → " + bw + "×" + bh;
            PadFit.Placement p = PadFit.place(bw, bh, iw, ih, iw, ih, new PadFit.Look(PadFit.FIT_CONTAIN, fx, fy, 100), 1);
            near(p.sx, p.sy, 1e-9 * p.sx, at + "：不变形");
            double sw = iw * p.sx, sh = ih * p.sy;
            check(p.dx >= -1e-6 && p.dx + sw <= bw + 1e-6 * bw, at + "：横向整张在里面");
            check(p.dy >= -1e-6 && p.dy + sh <= bh + 1e-6 * bh, at + "：竖向整张在里面");
            check(Math.abs(sw - bw) < 1e-6 * bw || Math.abs(sh - bh) < 1e-6 * bh, at + "：至少一个轴正好贴满");
            edgeAligned(p.dx, sw, bw, fx, at + "：横向");
            edgeAligned(p.dy, sh, bh, fy, at + "：竖向");

            PadFit.Placement s = PadFit.place(bw, bh, iw, ih, iw, ih, new PadFit.Look(PadFit.FIT_STRETCH, fx, fy, 100), 1);
            near(iw * s.sx, bw, 1e-6 * bw, "拉伸：横向正好铺满");
            near(ih * s.sy, bh, 1e-6 * bh, "拉伸：竖向正好铺满");
            check(s.dx == 0 && s.dy == 0 && !s.tile, "拉伸：不挪、不平铺（焦点没用）");
        }
        // 解出来的位图比原图小（缩过）：完整显示 / 填满照样按位图算，结果一样大
        PadFit.Placement a = PadFit.place(600, 400, 4000, 3000, 4000, 3000, new PadFit.Look(PadFit.FIT_COVER, 0.2f, 0.7f, 100), 1);
        PadFit.Placement b = PadFit.place(600, 400, 4000, 3000, 1000, 750, new PadFit.Look(PadFit.FIT_COVER, 0.2f, 0.7f, 100), 1);
        near(4000 * a.sx, 1000 * b.sx, 1e-6, "填满：解码缩过的位图显示得一样大");
        near(a.dx, b.dx, 1e-6, "填满：解码缩过的位图偏移一样");
        near(a.dy, b.dy, 1e-6, "填满：解码缩过的位图偏移一样（竖）");
    }

    // ═══════════ 原大小 ═══════════

    private static void original() {
        // 原图 3000×2000 解成一半：100% 显示成 3000×2000 个屏幕像素
        PadFit.Placement p = PadFit.place(500, 400, 3000, 2000, 1500, 1000, new PadFit.Look(PadFit.FIT_ORIGINAL, 0.5f, 0.5f, 100), 1);
        near(1500 * p.sx, 3000, 1e-9, "原大小 100%：补回解码时缩的那部分");
        near(1000 * p.sy, 2000, 1e-9, "原大小 100%：竖向一样");
        check(p.dx <= 0 && p.dx >= 500 - 3000 && !p.tile, "原大小比触控板大：照填满的规则裁、不露空");
        near(p.dx, (500 - 3000) / 2.0, 1e-9, "原大小：焦点正中时居中裁");
        // 取整不均（1501×999）：两个轴各补各的，显示大小还是正好
        p = PadFit.place(500, 400, 3000, 2000, 1501, 999, new PadFit.Look(PadFit.FIT_ORIGINAL, 0f, 1f, 200), 0.5);
        near(1501 * p.sx, 3000, 1e-9, "原大小：200% × unit 0.5 = 原图大小（横）");
        near(999 * p.sy, 2000, 1e-9, "原大小：200% × unit 0.5 = 原图大小（竖）");
        // 小贴纸：100 像素的图放在 500 的盒子里，焦点 0.25 → 照百分比
        p = PadFit.place(500, 300, 100, 100, 100, 100, new PadFit.Look(PadFit.FIT_ORIGINAL, 0.25f, 1f, 100), 1);
        near(p.dx, 0.25 * 400, 1e-9, "原大小比触控板小：横向照焦点百分比");
        near(p.dy, 200, 1e-9, "原大小比触控板小：焦点 1 贴底");
        p = PadFit.place(500, 300, 100, 100, 100, 100, new PadFit.Look(PadFit.FIT_ORIGINAL, 0.5f, 0.5f, 25), 1);
        near(100 * p.sx, 25, 1e-9, "原大小 25%：缩成四分之一");
    }

    // ═══════════ 平铺 ═══════════

    private static void tile() {
        for (int i = 0; i < 400; i++) {
            int iw = 1 + RND.nextInt(800), ih = 1 + RND.nextInt(800);
            double bw = 1 + RND.nextInt(2000), bh = 1 + RND.nextInt(2000);
            int scale = PadFit.SCALE_MIN + PadFit.SCALE_STEP * RND.nextInt((PadFit.SCALE_MAX - PadFit.SCALE_MIN) / PadFit.SCALE_STEP + 1);
            double unit = i % 2 == 0 ? 1 : 0.3 + RND.nextDouble();
            float fx = RND.nextFloat(), fy = RND.nextFloat();
            PadFit.Look l = new PadFit.Look(PadFit.FIT_TILE, fx, fy, scale);
            PadFit.Placement p = PadFit.place(bw, bh, iw, ih, iw, ih, l, unit);
            String at = "平铺 " + iw + "×" + ih + " " + scale + "% → " + bw + "×" + bh;
            double tw = iw * p.sx, th = ih * p.sy;
            near(tw, iw * scale / 100.0 * unit, 1e-9 * tw + 1e-12, at + "：砖的大小 = 图 × 图片大小");
            check(p.tile, at + "：是平铺");
            check(p.dx > -tw - 1e-9 && p.dx <= 1e-9, at + "：横向相位在 (−砖宽, 0]");
            check(p.dy > -th - 1e-9 && p.dy <= 1e-9, at + "：竖向相位在 (−砖高, 0]");
            // 和单张按焦点摆的位置差整数块砖
            double k = (PadFit.offset(bw, tw, fx) - p.dx) / tw;
            check(Math.abs(k - Math.round(k)) < 1e-6, at + "：相位和焦点摆的位置差整数块");
            PadFit.Placement q = PadFit.place(bw, bh, iw, ih, iw, ih, l.withFocus(RND.nextFloat(), RND.nextFloat()), unit);
            check(q.sx == p.sx && q.sy == p.sy, at + "：换焦点只改相位、不改砖的大小");
        }
        near(PadFit.phase(-250, 100), -50, 1e-12, "相位：−250 → −50");
        near(PadFit.phase(30, 100), -70, 1e-12, "相位：30 → −70");
        near(PadFit.phase(0, 100), 0, 1e-12, "相位：0 → 0");
        near(PadFit.phase(-100, 100), 0, 1e-12, "相位：正好一整块 → 0");
    }

    // ═══════════ 吸附 ═══════════

    private static void snap() {
        near(PadFit.snap(0.03f), 0, 0, "吸附：0.03 → 0");
        near(PadFit.snap(0.06f), 0, 0, "吸附：0.06 → 0（边界算吸）");
        near(PadFit.snap(0.07f), 0.07f, 1e-7, "吸附：0.07 不动");
        near(PadFit.snap(0.45f), 0.5, 0, "吸附：0.45 → 0.5");
        near(PadFit.snap(0.56f), 0.5, 0, "吸附：0.56 → 0.5（float 里差一点点也算）");
        near(PadFit.snap(0.43f), 0.43f, 1e-7, "吸附：0.43 不动");
        near(PadFit.snap(0.97f), 1, 0, "吸附：0.97 → 1");
        near(PadFit.snap(0.3f), 0.3f, 1e-7, "吸附：0.3 不动");
        near(PadFit.snap(-0.5f), 0, 0, "吸附：负数 → 0");
        near(PadFit.snap(1.7f), 1, 0, "吸附：大于 1 → 1");
        near(PadFit.snap(Float.NaN), 0.5, 0, "吸附：NaN → 正中");
        boolean ok = true;
        for (int i = 0; i < 2000; i++) {
            float f = RND.nextFloat() * 1.4f - 0.2f;
            float s = PadFit.snap(f);
            if (!(s >= 0 && s <= 1)) ok = false;
            float c = Math.max(0, Math.min(1, f));
            boolean nearMark = Math.abs(c) <= PadFit.SNAP || Math.abs(c - 0.5f) <= PadFit.SNAP || Math.abs(c - 1) <= PadFit.SNAP;
            if (!nearMark && s != c) ok = false;
            if (nearMark && s != 0 && s != 0.5f && s != 1) ok = false;
        }
        check(ok, "吸附：随机值都在 [0,1]，近的吸、远的不动");
    }

    // ═══════════ 按形状挑哪套 ═══════════

    private static void pick() {
        PadFit.Look wide = new PadFit.Look(PadFit.FIT_COVER, 0.2f, 0.5f, 100);
        PadFit.Look tall = new PadFit.Look(PadFit.FIT_CONTAIN, 0.8f, 0.5f, 100);
        PadFit.Settings off = new PadFit.Settings("a.img", false, 100, false, wide, tall);
        PadFit.Settings on = new PadFit.Settings("a.img", false, 100, true, wide, tall);
        check(off.pick(100, 200) == wide && off.pick(200, 100) == wide, "没分开设：两种形状都用同一套");
        check(on.pick(200, 100) == wide, "分开设：宽的用宽的那套");
        check(on.pick(100, 200) == tall, "分开设：高的用高的那套");
        check(on.pick(150, 150) == wide, "分开设：正方形算宽的（宽 ≥ 高）");
        check(on.pick(100, 100.5) == tall, "分开设：高比宽多一点就算高的（不做回差）");
        check(off.used().length == 1 && on.used().length == 2, "用得到几套：没分开设一套、分开设两套");
        check(on.withLook(true, wide).tall == wide && on.withLook(false, tall).wide == tall, "withLook 换对了那一套");
        check(!new PadFit.Settings("", false, 100, false, null, null).hasImage(), "文件名空 = 没设图");
        check(!new PadFit.Settings(null, false, 100, false, null, null).hasImage(), "文件名 null = 没设图");
        check(on.decodeKey(2400).equals(new PadFit.Settings("a.img", false, 40, true, wide.withFocus(0f, 0f), tall).decodeKey(2400)),
                "解码键不管透明度、焦点（它们不影响解多大）");
        check(!on.decodeKey(2400).equals(on.decodeKey(2600)), "解码键管窗口长边");
        check(!on.decodeKey(2400).equals(on.withLook(false, wide.withFit(PadFit.FIT_TILE)).decodeKey(2400)), "解码键管显示方式");
    }

    // ═══════════ 解码多大 ═══════════

    private static void decodeSize() {
        int[] sides = {800, 1600, 2400, 3200, 4000};
        int[][] fixed = {{1, 1}, {20000, 20000}, {1, 100000}, {100000, 1}, {16384, 12288}, {4000, 3000}, {1080, 2400}, {300, 300}, {5000, 100}};
        double worstCapped = 1, worstNormal = 1;   // 撞上限时多放大几倍：所有图 / 长宽比不超过 3 的图
        String worstAt = "", worstNormalAt = "";
        for (int i = 0; i < 3000; i++) {
            int[] img = i < fixed.length ? fixed[i] : new int[]{1 + RND.nextInt(i % 3 == 0 ? 20000 : 6000), 1 + RND.nextInt(i % 5 == 0 ? 20000 : 6000)};
            int L = sides[i % sides.length];
            int fit = i % 3 == 0 ? PadFit.FIT_CONTAIN : i % 3 == 1 ? PadFit.FIT_COVER : PadFit.FIT_STRETCH;
            int[] d = PadFit.decodeSize(img[0], img[1], L, new PadFit.Look(fit, 0.5f, 0.5f, 100));
            String at = "解码 " + img[0] + "×" + img[1] + " 长边 " + L;
            check((long) d[0] * d[1] <= PadFit.MAX_PIXELS, at + "：总像素不超上限");
            check(d[0] >= 1 && d[1] >= 1 && d[0] <= PadFit.MAX_SIDE && d[1] <= PadFit.MAX_SIDE, at + "：单边在 [1, 4096]");
            check(d[0] <= img[0] && d[1] <= img[1], at + "：不放大");
            // 最多还要放大多少：在不超过 L × L 的几种盒子上照三种方式算
            double need = 0;
            double[][] boxes = {{L, L}, {L, 1}, {1, L}, {L, L / 3.0}, {L / 3.0, L}, {L * 0.6, L * 0.9}};
            for (double[] b : boxes) {
                need = Math.max(need, Math.max(b[0] / d[0], b[1] / d[1]));   // 填满、拉伸都按这个（完整显示只会更小）
            }
            double s0 = Math.min(1, (double) L / Math.min(img[0], img[1]));
            boolean capped = (double) img[0] * s0 * img[1] * s0 > PadFit.MAX_PIXELS || Math.max(img[0], img[1]) * s0 > PadFit.MAX_SIDE;
            // 原图本身比要的小（s0 = 1）时本来就得放大，不算解码的问题：只看比"按原图画"多放大的那部分
            double needAtFull = 0;
            for (double[] b : boxes) needAtFull = Math.max(needAtFull, Math.max(b[0] / img[0], b[1] / img[1]));
            double extra = need / Math.max(1, needAtFull);
            if (!capped) {
                check(extra <= 1.0001, at + "：没撞上限时不用再放大（多要 " + extra + " 倍）");
            } else {
                if (extra > worstCapped) {
                    worstCapped = extra;
                    worstAt = img[0] + "×" + img[1] + "，长边 " + L;
                }
                if (Math.max(img[0], img[1]) <= 3.0 * Math.min(img[0], img[1]) && extra > worstNormal) {
                    worstNormal = extra;
                    worstNormalAt = img[0] + "×" + img[1] + "，长边 " + L;
                }
            }
        }
        System.err.println("  解码撞上限（总像素 2²³ / 单边 4096）时画的时候最多还要放大：长宽比 ≤ 3 的图 " + String.format("%.2f", worstNormal)
                + " 倍（" + worstNormalAt + "），所有图 " + String.format("%.2f", worstCapped) + " 倍（" + worstAt + "）");

        // 平铺 / 原大小：缩到图片大小、不放大
        int[] d = PadFit.decodeSize(2000, 1000, 2400, new PadFit.Look(PadFit.FIT_TILE, 0.5f, 0.5f, 25));
        check(d[0] == 500 && d[1] == 250, "平铺 25%：解成四分之一");
        d = PadFit.decodeSize(2000, 1000, 2400, new PadFit.Look(PadFit.FIT_ORIGINAL, 0.5f, 0.5f, 400));
        check(d[0] == 2000 && d[1] == 1000, "原大小 400%：解原图（放大交给画的时候）");
        // 两套取大的
        d = PadFit.decodeSize(4000, 3000, 2400, new PadFit.Look(PadFit.FIT_TILE, 0.5f, 0.5f, 25), new PadFit.Look(PadFit.FIT_COVER, 0.5f, 0.5f, 100));
        check(d[0] == 3200 && d[1] == 2400, "两套：平铺 25% 和填满一起时按填满要的解（短边 2400）");
        d = PadFit.decodeSize(100, 50, 2400, new PadFit.Look(PadFit.FIT_COVER, 0.5f, 0.5f, 100));
        check(d[0] == 100 && d[1] == 50, "小图不放大");
        d = PadFit.decodeSize(0, 50, 2400, new PadFit.Look(PadFit.FIT_COVER, 0.5f, 0.5f, 100));
        check(d[0] == 1 && d[1] == 1, "0 尺寸的图不抛异常");
        d = PadFit.decodeSize(1000, 1000, 2400);
        check(d[0] == 1000 && d[1] == 1000, "没给任何一套：按原图（不放大）");
    }

    private static void redecode() {
        check(!PadFit.needsRedecode(1000, 800, 1040, 832), "要的只大 4%：不重新解");
        check(PadFit.needsRedecode(1000, 800, 1060, 800), "要的大 6%：重新解");
        check(PadFit.needsRedecode(1000, 800, 1000, 900), "竖向大了：重新解");
        check(!PadFit.needsRedecode(1000, 800, 500, 400), "要的更小：不重新解");
        check(PadFit.needsRedecode(0, 0, 10, 10), "手上没有：要解");
    }

    // ═══════════ 空的、坏的输入 ═══════════

    private static void degenerate() {
        PadFit.Look l = PadFit.Look.defaults();
        check(PadFit.place(0, 100, 10, 10, 10, 10, l, 1) == null, "0 宽的盒子：不画");
        check(PadFit.place(100, 0, 10, 10, 10, 10, l, 1) == null, "0 高的盒子：不画");
        check(PadFit.place(Double.NaN, 100, 10, 10, 10, 10, l, 1) == null, "NaN 的盒子：不画");
        check(PadFit.place(100, 100, 0, 10, 10, 10, l, 1) == null, "0 尺寸的原图：不画");
        check(PadFit.place(100, 100, 10, 10, 0, 10, l, 1) == null, "0 尺寸的位图：不画");
        check(PadFit.place(100, 100, 10, 10, 10, 10, l, 0) == null, "unit 0：不画");
        boolean finite = true;
        for (int fit = 0; fit < PadFit.FIT_COUNT; fit++) {
            for (int[] img : new int[][]{{1, 1}, {1, 100000}, {100000, 1}}) {
                for (double[] box : new double[][]{{1, 1}, {0.5, 3000}, {3000, 0.5}}) {
                    PadFit.Placement p = PadFit.place(box[0], box[1], img[0], img[1], img[0], img[1], new PadFit.Look(fit, 0.3f, 0.7f, 400), 1);
                    if (p == null || !Double.isFinite(p.sx) || !Double.isFinite(p.sy) || !Double.isFinite(p.dx) || !Double.isFinite(p.dy)) {
                        finite = false;
                    }
                }
            }
        }
        check(finite, "极端尺寸：每种方式都算得出有限的数");
        check(Double.isFinite(PadFit.offset(0, 0, 0.5)), "offset(0, 0)：有限");
    }

    private static void clamps() {
        PadFit.Look l = new PadFit.Look(9, -1f, 2f, 1000);
        check(l.fit == PadFit.FIT_COVER && l.fx == 0f && l.fy == 1f && l.scalePct == PadFit.SCALE_MAX, "存坏的值夹回范围");
        l = new PadFit.Look(-1, Float.NaN, 0.3f, 1);
        check(l.fit == PadFit.FIT_COVER && l.fx == 0.5f && l.scalePct == PadFit.SCALE_MIN, "NaN 焦点当正中、太小的图片大小夹到最小");
        check(new PadFit.Settings("x", false, 0, false, null, null).alphaPct == PadFit.ALPHA_MIN, "不透明度不到 10% 夹到 10%");
        check(new PadFit.Settings("x", false, 300, false, null, null).alphaPct == PadFit.ALPHA_MAX, "不透明度超过 100% 夹到 100%");
        check(new PadFit.Look(PadFit.FIT_TILE, 0, 0, 100).usesScale() && new PadFit.Look(PadFit.FIT_ORIGINAL, 0, 0, 100).usesScale()
                && !new PadFit.Look(PadFit.FIT_COVER, 0, 0, 100).usesScale(), "图片大小只在平铺 / 原大小时有用");
        check(!new PadFit.Look(PadFit.FIT_STRETCH, 0, 0, 100).usesFocus() && new PadFit.Look(PadFit.FIT_CONTAIN, 0, 0, 100).usesFocus(),
                "焦点只在拉伸时没用");
    }

    /** 图上的状态字、角上小按钮、⋯ 下面垫的是"不透明的触控板底色"：每套主题的 padFill 都得是不透明的，块里的字才和没图时一样看得清。 */
    private static void themeFillOpaque() {
        for (Theme t : Theme.ALL) check((t.padFill >>> 24) == 0xFF, "主题 " + t.id + " 的触控板底色不透明");
    }

    static void near(double a, double b, double tol, String what) {
        check(Math.abs(a - b) <= tol, what + "（期望 " + b + "，实际 " + a + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
