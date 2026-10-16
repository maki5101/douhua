package dev.douhua;

/**
 * 空中鼠标：陀螺仪角速度 + 重力方向 → 光标位移，像电视的体感遥控器。纯 Java（离线测试）。
 * <ul>
 * <li>方向：把角速度按一组斜的基拆开（2026-10-16 改）：w = a·竖直向上 + b·手机自己的右轴 + c·水平朝前，
 *     左右 = a（绕竖直轴转，胳膊水平扫），上下 = b（绕手机自己的右轴转，手腕点头），c（绕水平朝前的轴拧，转小臂）不算。
 *     这样手机怎么歪着、仰着拿，水平扫都只走左右，点头都只走上下。"水平朝前"取手机 y–z 平面里和竖直垂直的那个方向，
 *     平拿时是顶边、竖拿时是背面，中间连续过渡。
 *     来历：第一版"玩家空间"（GyroWiki）上下按手机俯仰、左右按重力混合，手机一歪水平扫就斜着走；
 *     第二版"侧倾补偿"（Hillcrest Freespace 那种）把上下改成绕水平轴转、按仰角在顶边 / 背面两种指向之间切换（44° / 53° 回差）：
 *     用户描红时手机仰 40–60°，往下描竖线正好穿过切换区，换算中途突变；而且手腕点头绕的是手机自己的右轴，
 *     歪 8°、仰 50° 时每往下 1° 就往旁边串 0.22°（侧倾补偿还把左右除以 cos 仰角，仰得越高串得越多）——所有竖线都鼓包。</li>
 * <li>灵敏度固定，不自己加速：对方系统本来就按速度放大鼠标，两层加速叠在一起转同样的角度走多远忽大忽小，不好控制。</li>
 * <li>消抖：1€ 滤波——慢的时候截止频率低、狠狠平滑掉手抖，快的时候截止频率高、几乎不延迟；两级串联。
 *     截止频率看的速度是"先把带方向的角速度低通、再取大小"（1€ 原文就是先滤导数）：来回抖的正负抵消，抬不高截止频率。
 *     第一版先取大小再平均，手抖本身把截止频率抬到 5–8Hz，等于没滤（离线算：持续的 10Hz 手抖剩 32%、6Hz 剩 45%，
 *     改了以后剩 1%、4%，起步延迟一样：20°/s 约 60ms、5°/s 约 130ms 跟上）。
 *     再加软死区：小于 tightenDps 的按比例缩小，不一刀切（一刀切会把慢慢微调也吃掉）。</li>
 * <li>轨迹平滑（用户的原话："在这个基础上再加入平滑，把轨迹平滑了"）：把移动拆成"沿着走的方向"和"横着偏出去"两部分，
 *     沿走向的照常立刻跟手，横向的单独再低通（pathSmoothMs）。路线上左右 / 上下晃出来的弯被抹平，往前走不拖；
 *     原路掉头也不拖（掉头还在同一条线上）；拐直角时拐角变圆。横向的位移只是晚到、不丢，停下后补齐。
 *     走向按 3 倍时间常数估（不然走向自己跟着晃，横向晃动被当成"沿走向"放过去）；
 *     慢于 2°/s 的部分当横向一起平滑，等于静止时又多一道消抖。离线算（往右 20°/s 扫、上下 3Hz 晃）：
 *     100ms 晃剩 65%、150ms（默认）剩 41%、300ms 剩 29%。</li>
 * <li>防抖半径（拉绳，画图软件的防抖笔刷：Lazy Nezumi、Krita 的拉绳模式）：光标像被一根长 stringDeg 的绳子拖着，
 *     手在绳长范围里晃光标不动，超出了才沿绳子跟过去。只能拉平比绳长还短的小弯（快动时手晃出的弯又长又缓，拉不平，
 *     离线算往右扫时上下晃只少 1/16），所以主要管停住时的小抖；代价是掉头时手要多转两倍绳长光标才往回走。
 *     我一开始以为它没用、默认关；用户手调到 0.65°，重放算下来 0.15–0.25° 就把静止的抖全吃掉了，再大只是多落后。</li>
 * </ul>
 * 取整余数留到下一帧（同 GestureEngine.pointer）。
 */
public final class AirPointer {
    /** 斜基：左右 = 绕竖直轴，上下 = 绕手机自己的右轴，绕水平朝前拧不算（现在的默认）。 */
    public static final int SPACE_OBLIQUE = 0;
    /**
     * 玩家空间（GyroWiki / JoyShockMapper 的 Player Space，GamepadMotionHelpers.CalculatePlayerSpaceGyro）：
     * 上下 = 手机自己的俯仰；左右 = 绕"上"的转速（只用手机 y、z 两轴），放宽 1.41 倍、不超过这两轴合起来的转速。
     */
    public static final int SPACE_PLAYER = 1;
    /**
     * 世界空间（GamepadMotionHelpers.CalculateWorldSpaceGyro）：左右 = 绕竖直轴，上下 = 绕手机右轴在水平面上的投影。
     */
    public static final int SPACE_WORLD = 2;

    /**
     * 各换算下的默认角度校正 {竖, 横}（度）：2026-10-16 白板（.private/airlog/20261016-012214）里不碰边的竖 / 横线，
     * 按手转的角度算的平均歪斜取反。只是这一位用户、这一种拿法下的数，设置里能改。
     */
    public static float[] defaultFix(int space) {
        switch (space) {
            case SPACE_PLAYER: return new float[]{-10.1f, -5.4f};
            case SPACE_OBLIQUE: return new float[]{-7.7f, 1.9f};
            default: return new float[]{5.8f, 2.0f};
        }
    }

    public static final class Config {
        /** 每转一度光标走多少鼠标计数（由灵敏度档位给，见 {@link #countsPerDegree}）。 */
        public float countsPerDegree = 30f;
        // 默认值 = 刚加侧倾补偿那一版（2026-10-15），用户说那一版"最得劲儿"：没有轨迹平滑、没有防抖半径。
        // 之后按开环重放的指标挑过两组（加了轨迹平滑和防抖半径），描红实测（闭环：人看着光标不停横向修正）反而全是波浪、
        // "不跟手、如履薄冰"：轨迹平滑把横向修正延后、防抖半径让每次反向修正有空程，人一修就修过头。
        // 开环重放（tools/airlog/Replay.java）只看得出平滑和延迟，看不出闭环里修正的手感，别再拿它定默认值。
        /** 1€ 滤波：静止时的截止频率（Hz），越低抖动去得越干净。 */
        public float minCutoffHz = 1.0f;
        /** 1€ 滤波：每 °/s 的速度把截止频率抬高多少（Hz），越大快动作越跟手。 */
        public float beta = 1.5f;
        /** 1€ 滤波里估速度用的截止频率（Hz），对带方向的角速度低通。 */
        public float speedCutoffHz = 2.0f;
        /** 软死区：速度低于这个（°/s）按 速度/阈值 缩小。 */
        public float tightenDps = 3f;
        /** 轨迹平滑：横向偏移的低通时间常数（毫秒）；0 = 不用。 */
        public float pathSmoothMs = 0f;
        /** 防抖半径（拉绳的绳长，度，手转的角度）；0 = 不用。 */
        public float stringDeg = 0f;
        /** 两帧间隔上限：传感器停过一阵再来，别一步跳太远。 */
        public float maxDtMs = 50f;
        /**
         * 方向换算，见 {@link #SPACE_OBLIQUE} 等。默认世界空间（2026-10-16 白板，453 段不碰边的竖 / 横线，同一批原始陀螺仪重算）：
         * 竖线每笔歪的角度散布 玩家空间 14°、世界空间 7°，平均歪斜也小（−5.8° / −2.0° vs 玩家 +10.1° / +5.4°，按手转的角度算）。
         * 之前描红（每种只 5 笔、有越描越熟的影响）玩家空间成绩最好，样本太少，以白板为准。设置里可切。
         */
        public int space = SPACE_WORLD;
        /**
         * 角度校正（度，正 = 逆时针）：画竖线时手的转动整体往回扳 fixVDeg、横线扳 fixHDeg（一个固定的 2×2 线性变换，
         * 竖、横两个方向分别对正）。默认值见 {@link #defaultFix}：用户的白板数据里系统性的歪斜，用一半笔学、另一半笔检验能把平均歪斜消到 0。
         */
        public float fixVDeg = defaultFix(SPACE_WORLD)[0];
        public float fixHDeg = defaultFix(SPACE_WORLD)[1];
    }

    private static final float ACCEL_LOWPASS_MS = 150f;
    /** 低通后的速度到这么多（°/s）才算看得出往哪走；不到的部分全当横向一起平滑。 */
    private static final float PATH_DIR_DPS = 2f;
    private static final float PATH_DIR_FACTOR = 3f;

    private final Config cfg;
    private int rotation;
    private final float[] g = new float[3];
    private boolean haveGravity;
    private long lastAccelT;
    private long lastT;
    private float remX, remY;
    private float travel;
    // 1€ 滤波的状态：低通后的带方向角速度（估速度用）、两级低通
    private float dh, dv, h1, v1, h2, v2;
    /** 轨迹平滑：走向轴的低通（倍角表示：(h²−v²)/s、2hv/s，正反方向算同一条轴）、低通后的横向部分。 */
    private float pqa, pqb, pch, pcv;
    /** 拉绳：手比光标多走了多少（度）。 */
    private float ox, oy;
    /** 调试日志用：这一帧侧倾补偿后、消抖前的角速度，和全部平滑后的（°/s）。 */
    float dbgRawH, dbgRawV, dbgOutH, dbgOutV;

    public AirPointer(Config c) {
        cfg = c;
    }

    /** 灵敏度档位 1–10 → 每度多少计数：第 6 档 30，每档 ×1.25（第 1 档约 9.8，第 10 档约 73）。超出范围截断。 */
    public static float countsPerDegree(int level) {
        int l = Math.max(1, Math.min(10, level));
        return (float) (30.0 * Math.pow(1.25, l - 6));
    }

    /** 当前重力"上"（设备坐标，没归一化）；调试日志用。 */
    float[] gravityNow() {
        return g.clone();
    }

    /** Surface.ROTATION_* 的数值 0–3。 */
    public void setRotation(int r) {
        rotation = r & 3;
    }

    /** TYPE_GRAVITY，设备坐标，m/s²（平放屏幕朝上时约 (0,0,+9.81)，指向"上"）。 */
    public void gravity(float x, float y, float z) {
        g[0] = x;
        g[1] = y;
        g[2] = z;
        haveGravity = true;
    }

    /** 没有 TYPE_GRAVITY 的手机：加速度计低通后当重力用。 */
    public void accel(float x, float y, float z, long tNs) {
        if (!haveGravity || lastAccelT == 0) {
            gravity(x, y, z);
        } else {
            float dt = Math.max(0, (tNs - lastAccelT) / 1e6f);
            float a = dt / (ACCEL_LOWPASS_MS + dt);
            g[0] += a * (x - g[0]);
            g[1] += a * (y - g[1]);
            g[2] += a * (z - g[2]);
        }
        lastAccelT = tNs;
    }

    /** 开始跟随时调：清掉余数、滤波状态、上一帧时间（重力保留）。 */
    public void resetMotion() {
        remX = remY = 0;
        lastT = 0;
        dh = dv = h1 = v1 = h2 = v2 = 0;
        pqa = pqb = pch = pcv = 0;
        ox = oy = 0;
    }

    /**
     * 一帧陀螺仪（rad/s，tNs = SensorEvent.timestamp）。有整数位移时返回 true，out = {dx, dy}（HID 方向：右正、下正）。
     */
    public boolean gyro(float wx, float wy, float wz, long tNs, int[] out) {
        travel = 0;
        if (lastT == 0) {
            lastT = tNs;
            return false;
        }
        float dtMs = Math.min((tNs - lastT) / 1e6f, cfg.maxDtMs);
        lastT = tNs;
        if (dtMs <= 0 || !haveGravity) return false;

        // 按显示方向把 x/y 换到界面坐标（z 不变）：x = 界面右，y = 界面上，z = 屏幕朝外
        float[] w = remap(wx, wy, wz);
        float[] gr = remap(g[0], g[1], g[2]);
        float gn = (float) Math.sqrt(gr[0] * gr[0] + gr[1] * gr[1] + gr[2] * gr[2]);
        if (gn < 1e-3f) return false;
        float ux = gr[0] / gn, uy = gr[1] / gn, uz = gr[2] / gn;

        float yaw, pitch;
        if (cfg.space == SPACE_PLAYER) {
            float worldYaw = w[1] * uy + w[2] * uz;
            float local = (float) Math.sqrt(w[1] * w[1] + w[2] * w[2]);
            yaw = Math.signum(worldYaw) * Math.min(Math.abs(worldYaw) * 1.41f, local);
            pitch = w[0];
        } else if (cfg.space == SPACE_WORLD) {
            yaw = w[0] * ux + w[1] * uy + w[2] * uz;
            // 手机右轴 x 去掉竖直分量 → 水平的俯仰轴
            float qx = 1 - ux * ux, qy = -ux * uy, qz = -ux * uz;
            float qn = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
            if (qn < 0.3f) return false;   // 手机侧立
            pitch = (w[0] * qx + w[1] * qy + w[2] * qz) / qn;
        } else {
            // 水平朝前 p：手机 y–z 平面里和竖直垂直的方向（平拿 = 顶边 (0,1,0)，竖拿 = 背面 (0,0,−1)），总和"上"垂直
            float pn = (float) Math.sqrt(uy * uy + uz * uz);   // = cos(手机歪的角度)
            if (pn < 0.3f) return false;   // 手机侧立（左右边朝下），分不出上下左右
            float py = uz / pn, pz = -uy / pn;
            // 斜基 {上 u, 手机右轴 x = (1,0,0), 朝前 p} 解 w = a·u + b·x + c·p（克莱姆法则）：det = u · (x × p) = pn，x × p = (0, −pz, py)
            float det = uy * -pz + uz * py;
            // 左右 a = w · (x × p) / det：绕竖直轴转了多少，怎么拿都一样
            yaw = (w[1] * -pz + w[2] * py) / det;
            // 上下 b = u · (w × p) / det：绕手机自己的右轴转了多少；绕朝前拧（c）两个都是 0
            float cx = w[1] * pz - w[2] * py, cy = -w[0] * pz, cz = w[0] * py;
            pitch = (ux * cx + uy * cy + uz * cz) / det;
        }

        // 左转（绕"上"逆时针）→ 光标往左；抬头 → 光标往上
        float h0 = (float) -Math.toDegrees(yaw);
        float v0 = (float) -Math.toDegrees(pitch);
        // 角度校正：屏幕坐标（右正、下正）里 D 把"正竖 / 正横"变成实测歪了的方向，乘 D⁻¹ 扳回来
        float h = h0, v = v0;
        if (cfg.fixVDeg != 0 || cfg.fixHDeg != 0) {
            double dh = Math.toRadians(-cfg.fixHDeg), dv = Math.toRadians(-cfg.fixVDeg);
            double a = Math.cos(dh), b = Math.sin(dv), c = -Math.sin(dh), d = Math.cos(dv);   // D = [[a, b], [c, d]]
            double det = a * d - b * c;
            h = (float) ((d * h0 - b * v0) / det);
            v = (float) ((-c * h0 + a * v0) / det);
        }
        dbgRawH = h;
        dbgRawV = v;

        // 1€ 滤波：截止频率按"有意的"速度定（带方向的角速度先低通再取大小，手抖正负抵消），两轴用同一个（方向不歪），两级串联
        float as = alpha(cfg.speedCutoffHz, dtMs);
        dh += as * (h - dh);
        dv += as * (v - dv);
        float speed = (float) Math.hypot(dh, dv);
        float a = alpha(cfg.minCutoffHz + cfg.beta * speed, dtMs);
        h1 += a * (h - h1);
        v1 += a * (v - v1);
        h2 += a * (h1 - h2);
        v2 += a * (v1 - v2);
        h = h2;
        v = v2;

        // 软死区：慢于 tightenDps 的按比例缩小
        float s = (float) Math.hypot(h, v);
        if (s < cfg.tightenDps) {
            float k = s / cfg.tightenDps;
            h *= k;
            v *= k;
        }

        // 轨迹平滑：沿走向的照常过，横向的再低通
        if (cfg.pathSmoothMs > 0) {
            float ap = dtMs / (cfg.pathSmoothMs + dtMs);
            // 走向轴看得更久（3 倍）：不然轴自己跟着晃，横向的晃动被算成"沿走向"放过去了。
            // 只认轴、不认正反：原路掉头时轴不变（第一版按带方向的速度估，掉头那一下估计穿过零点乱转，
            // 主要的运动被当成横向延后放出，线上反而多出小折——用户的双日志重放出来的）
            float ad = dtMs / (PATH_DIR_FACTOR * cfg.pathSmoothMs + dtMs);
            float sp = (float) Math.hypot(h, v);
            if (sp > 1e-6f) {
                pqa += ad * ((h * h - v * v) / sp - pqa);
                pqb += ad * (2 * h * v / sp - pqb);
            } else {
                pqa -= ad * pqa;
                pqb -= ad * pqb;
            }
            float qn = (float) Math.hypot(pqa, pqb);
            float ah = 0, av = 0;
            if (qn > 0.05f) {
                // 倍角 → 轴：cos θ = √((1 + cos2θ)/2)，sin θ 的符号跟 sin2θ
                float c2 = pqa / qn;
                float dirH = (float) Math.sqrt(Math.max(0, (1 + c2) / 2));
                float dirV = (float) Math.copySign(Math.sqrt(Math.max(0, (1 - c2) / 2)), pqb);
                // "算不算在走"看这一帧的速度（起步快），慢于 PATH_DIR_DPS 的按比例当横向
                float along = (h * dirH + v * dirV) * Math.min(1f, sp / PATH_DIR_DPS);
                ah = along * dirH;
                av = along * dirV;
            }
            pch += ap * (h - ah - pch);
            pcv += ap * (v - av - pcv);
            h = ah + pch;
            v = av + pcv;
        }

        // 这一帧手转了多少度 → 拉绳：手离光标超过绳长，光标才沿绳子走过去，走到正好绳长
        float mx = h * dtMs / 1000f, my = v * dtMs / 1000f;
        if (cfg.stringDeg > 0) {
            ox += mx;
            oy += my;
            float n = (float) Math.hypot(ox, oy);
            if (n > cfg.stringDeg) {
                float k = (n - cfg.stringDeg) / n;
                mx = ox * k;
                my = oy * k;
                ox -= mx;
                oy -= my;
            } else {
                mx = my = 0;
            }
        }
        travel = (float) Math.hypot(mx, my);
        dbgOutH = mx * 1000f / dtMs;
        dbgOutV = my * 1000f / dtMs;

        float fx = mx * cfg.countsPerDegree + remX;
        float fy = my * cfg.countsPerDegree + remY;
        int ix = (int) fx;
        int iy = (int) fy;
        remX = fx - ix;
        remY = fy - iy;
        if (ix == 0 && iy == 0) return false;
        out[0] = ix;
        out[1] = iy;
        return true;
    }

    /** 一阶低通的系数：截止频率 fc（Hz），这一帧 dtMs。 */
    private static float alpha(float fc, float dtMs) {
        float dt = dtMs / 1000f;
        return dt / (dt + (float) (1 / (2 * Math.PI * fc)));
    }

    /** 上一次 gyro() 这一帧光标按多少度走了（消抖、拉绳以后）。移动"颗粒"按它算，和灵敏度无关。 */
    public float travelDeg() {
        return travel;
    }

    private float[] remap(float x, float y, float z) {
        switch (rotation) {
            case 1: return new float[]{-y, x, z};
            case 2: return new float[]{-x, -y, z};
            case 3: return new float[]{y, -x, z};
            default: return new float[]{x, y, z};
        }
    }
}
