import dev.douhua.AirPointer;
import java.io.*;
import java.util.*;

/** 用记下来的原始转动（侧倾补偿后、消抖前）按不同参数重放 AirPointer，算平滑 / 跟手 / 静止抖动。 */
public class Replay {
    static final class Seg { String label; long[] t; float[] h, v, oh, ov; double[] ix, iy; }

    public static void main(String[] a) throws Exception {
        List<Seg> segs = load(a[0]);
        String mode = a.length > 1 ? a[1] : "grid";
        if (mode.equals("check")) { check(segs); return; }
        if (mode.equals("detail")) {
            // 每组参数按图形分开算：detail c,b,s,d,p,st [c,b,s,d,p,st ...]
            for (int i = 2; i < a.length; i++) {
                String[] q = a[i].split(",");
                AirPointer.Config cfg = cfg(Float.parseFloat(q[0]), Float.parseFloat(q[1]), Float.parseFloat(q[2]), Float.parseFloat(q[3]), Float.parseFloat(q[4]), Float.parseFloat(q[5]));
                Map<String, List<Seg>> by = new TreeMap<>();
                for (Seg g : segs) by.computeIfAbsent(kind(g.label), k -> new ArrayList<>()).add(g);
                StringBuilder sb = new StringBuilder(a[i]);
                for (Map.Entry<String, List<Seg>> e : by.entrySet()) {
                    double[] m = score(e.getValue(), cfg);
                    sb.append(String.format(Locale.ROOT, " | %s r5 %.2f r2 %.2f lag %.0f len %.2f", e.getKey(), m[1] * 37.5, m[2] * 37.5, m[3], m[4]));
                }
                System.out.println(sb);
            }
            return;
        }
        float[] cuts = {0.45f, 0.6f, 0.8f, 1.0f, 1.3f, 1.8f};
        float[] betas = {1.0f, 1.5f, 2.5f, 4.0f};
        float[] scuts = {1.2f, 2.0f, 3.0f};
        float[] dzs = {0f, 1.5f, 3f};
        float[] paths = {0f, 120f, 180f, 250f, 350f};
        float[] strs = {0f, 0.15f, 0.25f, 0.35f, 0.5f, 0.65f};
        PrintStream out = System.out;
        out.println("cut,beta,scut,dz,path,str,static,r5,r2,lag,len");
        for (float c : cuts) for (float b : betas) for (float s : scuts) for (float d : dzs) for (float p : paths) for (float st : strs) {
            AirPointer.Config cfg = cfg(c, b, s, d, p, st);
            double[] m = score(segs, cfg);
            out.printf(Locale.ROOT, "%.2f,%.2f,%.2f,%.1f,%.0f,%.2f,%.5f,%.5f,%.5f,%.1f,%.3f%n", c, b, s, d, p, st, m[0], m[1], m[2], m[3], m[4]);
        }
    }

    static String kind(String l) {
        switch (l) {
            case "A1": case "B1": case "C1": return "横线";
            case "A2": case "B2": return "竖线";
            case "A3": case "B3": case "C2": return "圆";
            case "A4": case "C3": return "方";
            case "A5": return "三角";
            default: return "静止";
        }
    }

    static AirPointer.Config cfg(float c, float b, float s, float d, float p, float st) {
        AirPointer.Config cfg = new AirPointer.Config();
        cfg.countsPerDegree = 1000f;
        cfg.minCutoffHz = c; cfg.beta = b; cfg.speedCutoffHz = s; cfg.tightenDps = d; cfg.pathSmoothMs = p; cfg.stringDeg = st;
        return cfg;
    }

    /** 一段重放：位置（度），每个样本一个。 */
    static double[][] run(Seg g, AirPointer.Config cfg) {
        AirPointer p = new AirPointer(cfg);
        p.gravity(0, 0, 9.81f);
        int n = g.t.length;
        double[] x = new double[n], y = new double[n];
        int[] o = new int[2];
        long cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            // 平放时：左右 = −ωz，上下 = −ωx（见 AirPointer），倒推回陀螺仪读数
            if (p.gyro((float) Math.toRadians(-g.v[i]), 0, (float) Math.toRadians(-g.h[i]), g.t[i], o)) { cx += o[0]; cy += o[1]; }
            x[i] = cx / 1000.0; y[i] = cy / 1000.0;
        }
        return new double[][]{x, y};
    }

    /** {静止段抖动 std（度），5Hz 以上的抖（度），2Hz 以上的抖（度），速度落后（ms），走的距离保留了多少（比例）}。 */
    static double[] score(List<Seg> segs, AirPointer.Config cfg) {
        double sStd = 0; int sN = 0;
        double r5 = 0, r2 = 0; long rN = 0;
        double lagSum = 0, lenOut = 0, lenIn = 0; int lagN = 0;
        for (Seg g : segs) {
            double[][] r = run(g, cfg);
            double[] x = r[0], y = r[1];
            int n = x.length;
            if (g.label.equals("A6")) {
                double[] dx = detrend(x), dy = detrend(y);
                double s = 0; for (int i = 0; i < n; i++) s += dx[i] * dx[i] + dy[i] * dy[i];
                sStd += Math.sqrt(s / n); sN++;
                continue;
            }
            // 抖：和二次局部拟合（Savitzky-Golay，扣掉弯本身）的差。±12 样本（约 ±50ms）≈ 5Hz 以上，±35（约 ±150ms）≈ 2Hz 以上
            for (int i = 35; i < n - 35; i++) {
                double e5x = x[i] - sg(x, i, 12), e5y = y[i] - sg(y, i, 12);
                double e2x = x[i] - sg(x, i, 35), e2y = y[i] - sg(y, i, 35);
                r5 += e5x * e5x + e5y * e5y; r2 += e2x * e2x + e2y * e2y; rN++;
            }
            // 速度落后：输出速度和手的原始速度（侧倾补偿后）错开几个样本最相关
            double dtMs = (g.t[n - 1] - g.t[0]) / 1e6 / (n - 1);
            double best = -1e18; int bestK = 0;
            for (int k = 0; k <= 70; k++) {
                double c = 0;
                for (int i = k + 1; i < n; i++) {
                    double vx = (x[i] - x[i - 1]), vy = (y[i] - y[i - 1]);
                    c += vx * g.h[i - k] + vy * g.v[i - k];
                }
                if (c > best) { best = c; bestK = k; }
            }
            lagSum += bestK * dtMs; lagN++;
            // 距离：输出走的路程 vs 手（去掉手抖后）走的路程
            for (int i = 1; i < n; i++) {
                lenOut += Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
                lenIn += Math.hypot(g.ix[i] - g.ix[i - 1], g.iy[i] - g.iy[i - 1]);
            }
        }
        return new double[]{sStd / Math.max(1, sN), Math.sqrt(r5 / Math.max(1, rN)), Math.sqrt(r2 / Math.max(1, rN)), lagSum / Math.max(1, lagN), lenOut / lenIn};
    }

    /** 以 i 为中心、半宽 m 的二次 Savitzky-Golay 平滑值。 */
    static double sg(double[] a, int i, int m) {
        double norm = (2.0 * m - 1) * (2 * m + 1) * (2 * m + 3) / 3.0;
        double s = 0;
        for (int k = -m; k <= m; k++) s += ((3.0 * m * m + 3 * m - 1) - 5.0 * k * k) * a[i + k];
        return s / norm;
    }

    static double[] prefix(double[] a) { double[] p = new double[a.length + 1]; for (int i = 0; i < a.length; i++) p[i + 1] = p[i] + a[i]; return p; }

    static double[] detrend(double[] a) {
        int n = a.length; double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { sx += i; sy += a[i]; sxx += (double) i * i; sxy += i * a[i]; }
        double k = (n * sxy - sx * sy) / (n * sxx - sx * sx), b = (sy - k * sx) / n;
        double[] o = new double[n]; for (int i = 0; i < n; i++) o[i] = a[i] - (k * i + b); return o;
    }

    /** 用当时的参数重放，和手机上记下来的平滑后角速度对一对，确认重放和真机一样。 */
    static void check(List<Seg> segs) {
        AirPointer.Config cfg = cfg(0.6f, 1.0f, 1.2f, 3f, 180f, 0.65f);
        double maxErr = 0, sumAbs = 0; long n = 0;
        for (Seg g : segs) {
            double[][] r = run(g, cfg);
            for (int i = 2; i < g.t.length; i++) {
                double dt = (g.t[i] - g.t[i - 1]) / 1e9;
                if (dt <= 0 || dt > 0.05) continue;
                double vx = (r[0][i] - r[0][i - 1]) / dt;
                double e = Math.abs(vx - g.oh[i]);
                if (Math.abs(g.oh[i]) > 0.5) { maxErr = Math.max(maxErr, e); sumAbs += Math.abs(g.oh[i]); n++; }
            }
        }
        System.out.printf(Locale.ROOT, "重放 vs 真机（左右角速度）：最大差 %.3f °/s，真机平均 %.2f °/s，样本 %d%n", maxErr, sumAbs / n, n);
    }

    static List<Seg> load(String file) throws IOException {
        Map<Integer, List<String[]>> m = new TreeMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) { String[] p = line.split(","); m.computeIfAbsent(Integer.parseInt(p[0]), k -> new ArrayList<>()).add(p); }
        }
        List<Seg> segs = new ArrayList<>();
        for (List<String[]> rows : m.values()) {
            Seg g = new Seg(); int n = rows.size();
            g.label = rows.get(0)[1]; g.t = new long[n]; g.h = new float[n]; g.v = new float[n]; g.oh = new float[n]; g.ov = new float[n]; g.ix = new double[n]; g.iy = new double[n];
            for (int i = 0; i < n; i++) {
                String[] p = rows.get(i);
                g.t[i] = Long.parseLong(p[2]); g.h[i] = Float.parseFloat(p[3]); g.v[i] = Float.parseFloat(p[4]);
                g.oh[i] = Float.parseFloat(p[5]); g.ov[i] = Float.parseFloat(p[6]); g.ix[i] = Double.parseDouble(p[7]); g.iy[i] = Double.parseDouble(p[8]);
            }
            segs.add(g);
        }
        return segs;
    }
}
