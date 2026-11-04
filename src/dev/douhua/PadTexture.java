package dev.douhua;

/**
 * 有材质的主题里触控板的纹理（2026-11-04，Theme.Material）：程序生成的一小块，能上下左右无缝平铺（TrackpadView 用 BitmapShader REPEAT 铺满），
 * 不带图片资源、不占安装包。纯 Java（离线测，ThemeTest.textures）：同样的参数生成的一模一样。
 *
 * 每个点一个 -1..1 的值（正 = 往白、负 = 往黑），整块平均是 0（不改触控板的底色）、绝对值最大 1；叠到底色上时乘纹理的浓淡
 * （Theme.Material.textureStrength，最亮 / 最暗处就是 Material.padLight / padDark，状态字对它们也够对比度）。
 * <ul>
 * <li>{@link #BRUSHED} 拉丝铝：横向的细长纹（每行噪声沿横向抹开）+ 一点逐行的明暗。</li>
 * <li>{@link #PAPER} 纸：大块的、淡的明暗 + 短的纤维 + 细颗粒。</li>
 * <li>{@link #PLEAT} 褶：一道道竖褶（迎光面亮、背光面暗、褶线处最暗），褶宽按 dp 定（{@link #PLEAT_DP}）。</li>
 * <li>{@link #GRAIN} 颗粒：哑光的粉末涂层、镀银表盘那种很细的颗粒。</li>
 * </ul>
 */
final class PadTexture {
    static final int NONE = 0, BRUSHED = 1, PAPER = 2, PLEAT = 3, GRAIN = 4;
    /** 一道褶多宽（dp）。 */
    static final float PLEAT_DP = 9;
    /** 一块多大（像素，褶的那块取褶宽的整数倍、最接近它的）。 */
    static final int TILE = 256;

    private PadTexture() {}

    /** 这种纹理的一块多大（像素）：褶要正好装下整数道褶，不然接缝处断开。 */
    static int tileSize(int kind, float density) {
        if (kind != PLEAT) return TILE;
        int p = pleatPx(density);
        return p * Math.max(1, Math.round((float) TILE / p));
    }

    static int pleatPx(float density) {
        return Math.max(6, Math.round(PLEAT_DP * density));
    }

    /** size × size 的一块（行优先），每个点 -1..1，平均 0。NONE 是全 0。 */
    static float[] field(int kind, float density) {
        int n = tileSize(kind, density);
        float[] f = new float[n * n];
        Rng rng = new Rng(0x5EEDL * 31 + kind);
        switch (kind) {
            case BRUSHED:
                brushed(f, n, rng);
                break;
            case PAPER:
                paper(f, n, rng);
                break;
            case PLEAT:
                pleat(f, n, pleatPx(density), rng);
                break;
            case GRAIN:
                noise(f, rng, 1);
                blur(f, n, 1, 1);
                break;
            default:
                return f;
        }
        normalize(f);
        return f;
    }

    /**
     * 叠在触控板底色上的像素（ARGB）：正的是白、负的是黑，透明度 = |值| × strength × 255。
     * 白 / 黑 α 盖在底色上，结果正好是 blend(底色, 白 / 黑, |值| × strength)（Theme.Material.padLight / padDark 的两头）。
     */
    static int[] pixels(int kind, float density, float strength) {
        float[] f = field(kind, density);
        int[] p = new int[f.length];
        for (int i = 0; i < f.length; i++) {
            int a = Math.round(Math.abs(f[i]) * strength * 255);
            p[i] = a << 24 | (f[i] >= 0 ? 0xFFFFFF : 0);
        }
        return p;
    }

    // ═══════════ 各种纹理 ═══════════

    private static void brushed(float[] f, int n, Rng rng) {
        noise(f, rng, 1);
        blur(f, n, n / 10, 0);   // 沿横向抹开成细长的纹
        blur(f, n, n / 10, 0);
        standardize(f);
        float[] row = new float[n];
        for (int y = 0; y < n; y++) row[y] = (float) (rng.uniform() * 2 - 1);
        for (int y = 0; y < n; y++) {
            float r = 0.5f * row[y] + 0.25f * row[(y + 1) % n] + 0.25f * row[(y + n - 1) % n];
            for (int x = 0; x < n; x++) f[y * n + x] += 0.45f * r;
        }
    }

    private static void paper(float[] f, int n, Rng rng) {
        // 大块的明暗：每边 6 格的值噪声，平滑插值、首尾相接
        int g = 6;
        float[] cell = new float[g * g];
        for (int i = 0; i < cell.length; i++) cell[i] = (float) (rng.uniform() * 2 - 1);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float gx = (float) x * g / n, gy = (float) y * g / n;
                int x0 = (int) gx, y0 = (int) gy;
                float tx = smooth(gx - x0), ty = smooth(gy - y0);
                float a = cell[y0 % g * g + x0 % g], b = cell[y0 % g * g + (x0 + 1) % g];
                float c = cell[(y0 + 1) % g * g + x0 % g], d = cell[(y0 + 1) % g * g + (x0 + 1) % g];
                f[y * n + x] = a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty;
            }
        }
        standardize(f);
        // 纤维：短的、随便朝哪的线段，有亮有暗
        float[] fib = new float[n * n];
        int count = n * n / 500;
        for (int i = 0; i < count; i++) {
            double x = rng.uniform() * n, y = rng.uniform() * n, ang = rng.uniform() * Math.PI;
            double len = 4 + rng.uniform() * 12;
            float v = (float) ((rng.uniform() < 0.6 ? 1 : -1) * (0.4 + 0.6 * rng.uniform()));
            for (int s = 0; s <= len; s++) {
                int px = Math.floorMod((int) Math.round(x + Math.cos(ang) * s), n);
                int py = Math.floorMod((int) Math.round(y + Math.sin(ang) * s), n);
                fib[py * n + px] += v;
            }
        }
        blur(fib, n, 1, 1);
        standardize(fib);
        float[] grain = new float[n * n];
        noise(grain, rng, 1);
        standardize(grain);
        for (int i = 0; i < f.length; i++) f[i] = 0.7f * f[i] + 0.45f * fib[i] + 0.35f * grain[i];
    }

    private static void pleat(float[] f, int n, int period, Rng rng) {
        float[] grain = new float[n * n];
        noise(grain, rng, 1);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double u = (double) (x % period) / period;
                // 迎光的那面慢慢亮起来、背光那面暗下去；褶线（u = 0）处最暗、窄
                double v = Math.sin(2 * Math.PI * u);
                v = Math.signum(v) * Math.pow(Math.abs(v), 0.6);
                double crease = Math.exp(-Math.pow(Math.min(u, 1 - u) * period / 1.2, 2));
                f[y * n + x] = (float) (0.8 * v - 0.35 * crease) + 0.06f * grain[y * n + x];
            }
        }
    }

    // ═══════════ 小工具 ═══════════

    private static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    private static void noise(float[] f, Rng rng, float amp) {
        for (int i = 0; i < f.length; i++) f[i] = (float) ((rng.uniform() * 2 - 1) * amp);
    }

    /** 首尾相接的方框模糊：rx 横向半径、ry 竖向半径（0 = 这个方向不模糊）。 */
    private static void blur(float[] f, int n, int rx, int ry) {
        float[] t = new float[f.length];
        if (rx > 0) {
            for (int y = 0; y < n; y++) {
                float sum = 0;
                for (int k = -rx; k <= rx; k++) sum += f[y * n + Math.floorMod(k, n)];
                for (int x = 0; x < n; x++) {
                    t[y * n + x] = sum / (2 * rx + 1);
                    sum += f[y * n + Math.floorMod(x + rx + 1, n)] - f[y * n + Math.floorMod(x - rx, n)];
                }
            }
            System.arraycopy(t, 0, f, 0, f.length);
        }
        if (ry > 0) {
            for (int x = 0; x < n; x++) {
                float sum = 0;
                for (int k = -ry; k <= ry; k++) sum += f[Math.floorMod(k, n) * n + x];
                for (int y = 0; y < n; y++) {
                    t[y * n + x] = sum / (2 * ry + 1);
                    sum += f[Math.floorMod(y + ry + 1, n) * n + x] - f[Math.floorMod(y - ry, n) * n + x];
                }
            }
            System.arraycopy(t, 0, f, 0, f.length);
        }
    }

    /** 减去平均值、除以标准差（几种成分按比例合起来之前先各自量纲一致）。 */
    private static void standardize(float[] f) {
        double mean = 0;
        for (float v : f) mean += v;
        mean /= f.length;
        double var = 0;
        for (float v : f) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / f.length);
        for (int i = 0; i < f.length; i++) f[i] = (float) (sd == 0 ? 0 : (f[i] - mean) / sd);
    }

    /** 减去平均值，再按 2.5 倍标准差（或者最大值，取小的）缩到 -1..1（噪声里少数过头的点截住，不让它们把整体压淡）。 */
    private static void normalize(float[] f) {
        double mean = 0;
        for (float v : f) mean += v;
        mean /= f.length;
        double var = 0;
        for (int i = 0; i < f.length; i++) {
            f[i] -= mean;
            var += f[i] * f[i];
        }
        double sd = Math.sqrt(var / f.length), max = 0;
        for (float v : f) max = Math.max(max, Math.abs(v));
        if (sd == 0) return;
        double k = 1 / Math.min(2.5 * sd, max);   // 褶这种规则的起伏最大值不到 2.5 倍标准差：照最大值缩，浓淡用足
        for (int i = 0; i < f.length; i++) f[i] = (float) Math.max(-1, Math.min(1, f[i] * k));
        // 截完再把平均值拉回 0（截掉的两头不对称时会偏一点）
        mean = 0;
        for (float v : f) mean += v;
        mean /= f.length;
        for (int i = 0; i < f.length; i++) f[i] = (float) Math.max(-1, Math.min(1, f[i] - mean));
    }

    /** SplitMix64（同 SoundSynth.Rng 的写法）：同一个种子在电脑、手机上逐个一样。 */
    private static final class Rng {
        private long s;

        Rng(long seed) {
            s = seed;
        }

        double uniform() {
            long z = (s += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z ^= z >>> 31;
            return (z >>> 11) * 0x1.0p-53;
        }
    }
}
