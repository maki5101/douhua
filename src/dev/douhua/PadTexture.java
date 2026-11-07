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
    /**
     * 照涌现时钟的表面处理补的几种（2026-11-07，用户：把时钟里的主题加上来）：喷砂（1 像素细颗粒、对比高、零星亮点）、和纸（稀疏的长弯纤维）、
     * 皮革（细荔枝纹：抖动网格的细胞，鼓包亮、缝暗）、碳纤维（2/2 斜纹，每束横截面中间亮）、大理石（扭曲过的细纹）、青花（稀疏的、边缘深的晕染色块）。
     * 时钟里大理石主纹、青花按整块板子生成、不平铺；这里触控板小，做成能平铺的简化版。
     */
    static final int SAND = 5, WASHI = 6, LEATHER = 7, CARBON = 8, MARBLE = 9, QINGHUA = 10;
    /** 碳纤维一束多宽（像素按 dp 算）。 */
    static final float TOW_DP = 3;
    /** 一道褶多宽（dp）。 */
    static final float PLEAT_DP = 9;
    /** 一块多大（像素，褶的那块取褶宽的整数倍、最接近它的）。 */
    static final int TILE = 256;

    private PadTexture() {}

    /** 这种纹理的一块多大（像素）：褶要正好装下整数道褶，不然接缝处断开。 */
    static int tileSize(int kind, float density) {
        int p;
        if (kind == PLEAT) p = pleatPx(density);
        else if (kind == CARBON) p = 4 * towPx(density);   // 斜纹一个循环是 4 束
        else return TILE;
        return p * Math.max(1, Math.round((float) TILE / p));
    }

    static int towPx(float density) {
        return Math.max(4, Math.round(TOW_DP * density));
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
            case SAND:
                sand(f, rng);
                break;
            case WASHI:
                washi(f, n, rng);
                break;
            case LEATHER:
                leather(f, n, rng);
                break;
            case CARBON:
                carbon(f, n, towPx(density));
                break;
            case MARBLE:
                marble(f, n, rng);
                break;
            case QINGHUA:
                qinghua(f, n, rng);
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
        return pixels(kind, density, strength, 0xFF000000, false);
    }

    /**
     * 同上，dark = 负的那头用什么颜色（大理石的纹、青花的钴蓝；平常是黑），invert = 正负对调（白底上的深色纹：纹是负的）。
     * 结果是 blend(底色, 白, |值| × strength) / blend(底色, dark, |值| × strength)（Theme.Material.padLight / padDark）。
     */
    static int[] pixels(int kind, float density, float strength, int dark, boolean invert) {
        float[] f = field(kind, density);
        int[] p = new int[f.length];
        for (int i = 0; i < f.length; i++) {
            float v = invert ? -f[i] : f[i];
            int a = Math.round(Math.abs(v) * strength * 255);
            p[i] = a << 24 | (v >= 0 ? 0xFFFFFF : dark & 0xFFFFFF);
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

    /** 喷砂：1 像素的细颗粒（不模糊，时钟里模糊过用户说"不清晰"），零星的亮点。 */
    private static void sand(float[] f, Rng rng) {
        for (int i = 0; i < f.length; i++) {
            double u = rng.uniform();
            f[i] = u < 0.004 ? 4 : (float) (rng.uniform() * 2 - 1);
        }
    }

    /** 和纸：稀疏的长弯纤维（亮），慢慢拐弯，首尾相接地画。 */
    private static void washi(float[] f, int n, Rng rng) {
        int count = n * n / 1600;
        for (int i = 0; i < count; i++) {
            double x = rng.uniform() * n, y = rng.uniform() * n, ang = rng.uniform() * Math.PI * 2, turn = (rng.uniform() - 0.5) * 0.08;
            int len = 30 + (int) (rng.uniform() * 70);
            float v = (float) (0.5 + 0.5 * rng.uniform());
            for (int s = 0; s < len; s++) {
                x += Math.cos(ang);
                y += Math.sin(ang);
                ang += turn;
                f[Math.floorMod((int) y, n) * n + Math.floorMod((int) x, n)] += v;
            }
        }
        blur(f, n, 1, 1);
        float[] g = new float[f.length];
        noise(g, rng, 1);
        blur(g, n, 6, 6);
        standardize(f);
        standardize(g);
        for (int i = 0; i < f.length; i++) f[i] += 0.3f * g[i];
    }

    /** 皮革（细荔枝纹）：抖动网格的细胞噪声，离最近的点越近越亮（鼓包），两个点之间的缝暗。 */
    private static void leather(float[] f, int n, Rng rng) {
        int cell = 8, g = Math.max(1, n / cell);
        float[] px = new float[g * g], py = new float[g * g];
        for (int i = 0; i < g * g; i++) {
            px[i] = (float) ((i % g + 0.15 + 0.7 * rng.uniform()) * n / g);
            py[i] = (float) ((i / g + 0.15 + 0.7 * rng.uniform()) * n / g);
        }
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int cx = x * g / n, cy = y * g / n;
                double d1 = 1e9, d2 = 1e9;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int j = Math.floorMod(cy + dy, g) * g + Math.floorMod(cx + dx, g);
                        double ox = px[j] + (cx + dx < 0 ? -n : cx + dx >= g ? n : 0) - x;
                        double oy = py[j] + (cy + dy < 0 ? -n : cy + dy >= g ? n : 0) - y;
                        double d = ox * ox + oy * oy;
                        if (d < d1) {
                            d2 = d1;
                            d1 = d;
                        } else if (d < d2) {
                            d2 = d;
                        }
                    }
                }
                f[y * n + x] = (float) (Math.sqrt(d2) - Math.sqrt(d1));   // 缝上（两个点一样近）是 0：暗
            }
        }
    }

    /** 碳纤维：2/2 斜纹，横束、竖束轮流压在上面；每束横截面中间亮、两边暗，横束（朝光）略亮一点。 */
    private static void carbon(float[] f, int n, int tow) {
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                // 整体挪半束：一块的边落在束的正中（亮的地方），平铺时接缝两边一样，不落在束和束交界的明暗跳变上
                int xx = x + tow / 2, yy = y + tow / 2;
                int i = xx / tow, j = yy / tow;
                boolean horizontal = Math.floorMod(i + j, 4) < 2;
                double u = (horizontal ? yy % tow : xx % tow) + 0.5;
                double v = Math.sin(Math.PI * u / tow);
                f[y * n + x] = (float) (v * v + (horizontal ? 0.25 : 0));
            }
        }
    }

    /**
     * 大理石：两组斜着走的细纹（一组主纹、一组淡的次纹），用分形噪声轻轻扭弯（扭得太狠会绕成一圈圈的等高线）；每组横竖各整数个周期，首尾相接。
     * 纹是正的（白底深纹由 Material.invert 对调）。
     */
    private static void marble(float[] f, int n, Rng rng) {
        float[] warpA = fbm(n, rng, 4), warpB = fbm(n, rng, 4);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int k = y * n + x;
                double a = 2 * Math.PI * (1.0 * x / n + 2.0 * y / n) + 1.3 * warpA[k];
                double b = 2 * Math.PI * (-2.0 * x / n + 1.0 * y / n) + 1.6 * warpB[k];
                f[k] = (float) (Math.exp(-Math.abs(Math.sin(a)) * 22) + 0.4 * Math.exp(-Math.abs(Math.sin(b)) * 30));
            }
        }
        float[] cloud = fbm(n, rng, 2);
        standardize(f);
        for (int i = 0; i < f.length; i++) f[i] += 0.12f * cloud[i];
    }

    /** 青花：稀疏的、不规整的小块钴料晕染，边上深、里面淡，零星几个积料的小点（负的：Material 的深色那头是钴蓝），首尾相接。 */
    private static void qinghua(float[] f, int n, Rng rng) {
        float[] edge = fbm(n, rng, 4);
        int blobs = 6;
        for (int b = 0; b < blobs; b++) {
            double cx = rng.uniform() * n, cy = rng.uniform() * n, r = n * (0.05 + 0.05 * rng.uniform());
            double sx = 0.6 + 0.8 * rng.uniform();   // 拉长一点，不是圆的
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double dx = Math.abs(x - cx), dy = Math.abs(y - cy);
                    dx = Math.min(dx, n - dx) / sx;
                    dy = Math.min(dy, n - dy) * sx;
                    double d = Math.hypot(dx, dy) / (r * Math.max(0.5, 1 + 0.45 * edge[y * n + x]));   // 边缘不规整，但半径不会变负
                    if (d < 1.1) {
                        double body = d < 1 ? 0.3 + 0.7 * Math.pow(d, 6) : Math.max(0, 1 - (d - 1) / 0.1);
                        f[y * n + x] -= (float) body;
                    }
                }
            }
        }
        for (int i = 0; i < n * n / 2500; i++) {   // 积料的小点
            int x = (int) (rng.uniform() * n), y = (int) (rng.uniform() * n);
            f[y * n + x] -= 1.2f;
        }
    }

    /** 首尾相接的分形值噪声（octaves 层，每层格子翻倍），标准化过。 */
    private static float[] fbm(int n, Rng rng, int octaves) {
        float[] out = new float[n * n];
        float amp = 1;
        for (int o = 0; o < octaves; o++) {
            int g = 4 << o;
            float[] cell = new float[g * g];
            for (int i = 0; i < cell.length; i++) cell[i] = (float) (rng.uniform() * 2 - 1);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float gx = (float) x * g / n, gy = (float) y * g / n;
                    int x0 = (int) gx, y0 = (int) gy;
                    float tx = smooth(gx - x0), ty = smooth(gy - y0);
                    float a = cell[y0 % g * g + x0 % g], b = cell[y0 % g * g + (x0 + 1) % g];
                    float c = cell[(y0 + 1) % g * g + x0 % g], d = cell[(y0 + 1) % g * g + (x0 + 1) % g];
                    out[y * n + x] += amp * (a + (b - a) * tx + (c - a) * ty + (a - b - c + d) * tx * ty);
                }
            }
            amp *= 0.5f;
        }
        standardize(out);
        return out;
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
