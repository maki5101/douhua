package dev.douhua;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 手柄页的触摸模型（Proto.REGION_GAMEPAD，TouchRouter 按下时把手指交给这里，同小红点的 TrackPoint）。纯 Java（离线测，GamepadTouchTest）。
 * 坐标是 TouchRouter 给的屏幕像素，阈值按 dp 配（{@link GamepadConfig}）、乘 density 换成像素。
 *
 * 每根手指归谁仍在按下那一刻定：单键、十字键、摇杆滑出自己的范围照样有效（同"左键"按钮、同"从触控板出发划出去照样动光标"）。
 * 唯一的例外是面键簇内部：归属定到"这一簇"（不会被改判给十字键、⋯ 或别的簇），只在簇自己的几颗键之间按当前位置挑键——
 * 用户要的"搓键"（从 B 滚到 A、拇指压在两颗中间一起按），{@link GamepadConfig#clusterSlide} 关掉就是按下时挑一颗、滑动不换。
 *
 * - 单键（肩键、Start…、数字扳机）：按下这个输出，滑出照样按着，松开才松；同一个输出按引用计数，最后一根松开才松。
 * - 面键簇：按最近的键（缝里也算，没有死区）；离两颗的边都在 chordDp 以内 = 两颗一起按（最多两颗）；按着的键到 chordDp + 2dp 才松；
 *   滑到别的键 = 旧的松、新的按；离簇的范围 clusterExitDp 以外全松，回到范围里再按。
 * - 十字键：中心固定 = 区域中心；离中心不到 dpadDeadzone × 半径 = 回中，否则按角度分扇区（8 方向时斜向窄），扇区边界 5° 回差；
 *   滑出范围只看角度。第一根手指占着，再来的不理。
 * - 摇杆：按下点当中心（浮动）；位移 / stickRadiusDp → 单位圆里的向量；推过半径底座跟着手指走（反向推马上生效）；滑出区域照样有效；
 *   死区 + 抗死区。第一根占着，再来的不理。
 * - N64 的 C 键（输出码 Proto.GP_C_*，在面键簇里，搓键照样）：按下 = 右摇杆往那边推满（{@link #cKeys}），"嗒"同别的键。
 *
 * 报文：按键 / 十字键 / 扳机变了当场发；摇杆轴变了攒到 {@link #frame} 发（一帧触摸只发一次）；{@link #up} 里自己冲一次
 * （App 本地分流抬起后没有 frame）。和上一次发的一样就不发；所以没用过手柄时收尾（{@link #releaseAll}）什么都不发。
 * 所有收尾路径（抬起 / 取消、releaseAll、换参数）之后一定是全零报文，每个按下（button DOWN）都配了松开（UP）。
 *
 * 反馈：键（含簇里新按下的那颗、十字键从中间推出一个方向）按下 = button DOWN（App 震能打断的"嗒"）；十字键按着换方向、
 * 摇杆推到边（≥ 95%，回到 85% 以下再推才再响）= HAPTIC_TICK（"咔嗒"）；摇杆推动不震、不发颗粒；松开不震。
 * 不加锁：调用方（TouchRouter）保证同一时刻只有一个线程在调。
 */
final class GamepadTouch {
    interface Output {
        /** 手柄状态变了：13 字节报文（Hid.GAMEPAD_DESC，只在回调期间有效）。 */
        void report(byte[] report);

        /** 控件按下 / 松开（id 是手柄控件编号，action 同 Proto.BUTTON_*）：App 按下时震"嗒"。 */
        void button(int id, int action);

        void haptic(int kind);
    }

    /** 回差（dp）：按着的簇键到 chordDp + 它才松；主键换成别的要比按着的近这么多以上。 */
    static final float HYST_DP = 2;
    /** 十字键扇区边界的回差（度）。 */
    static final float DIR_HYST_DEG = 5;
    /** 十字键回中的回差：离中心到死区的这么多倍才回中（推出去要到死区）。 */
    static final float DPAD_BACK = 0.8f;
    /** 摇杆推到边的"咔嗒"：推量到它响，回到 RIM_OFF 以下才再上弦。 */
    static final float RIM_ON = 0.95f, RIM_OFF = 0.85f;

    private static final int SLOTS = TouchRouter.SLOTS;
    private static final int NONE = 0, BUTTON = 1, CLUSTER = 2, DPAD = 3, STICK = 4;

    private final Output out;
    private GamepadConfig cfg = new GamepadConfig();
    private float density = 1;
    /** 当前布局里的面键簇（范围区域 → 成员）；按着的手指记着按下时那一份，布局换了也照用旧的几何到抬起。 */
    private final Map<TouchRouter.Region, Cluster> clusters = new IdentityHashMap<>();

    /** 一个面键簇：范围 + 成员（排在范围后面、整个落在范围里的手柄单键）的圆心、半径、输出。 */
    static final class Cluster {
        final TouchRouter.Region area;
        final int[] id, output;
        final float[] cx, cy, radius;

        Cluster(TouchRouter.Region area, TouchRouter.Region[] members) {
            this.area = area;
            int n = members.length;
            id = new int[n];
            output = new int[n];
            cx = new float[n];
            cy = new float[n];
            radius = new float[n];
            for (int i = 0; i < n; i++) {
                TouchRouter.Region r = members[i];
                id[i] = r.id;
                output[i] = Proto.gamepadParam(r.id);
                cx[i] = (r.left + r.right) / 2f;
                cy[i] = (r.top + r.bottom) / 2f;
                radius[i] = Math.min(r.right - r.left, r.bottom - r.top) / 2f;
            }
        }
    }

    // ── 每根手指 ──
    private final int[] kind = new int[SLOTS];
    /** 单键 / 十字键 / 摇杆的控件编号。 */
    private final int[] slotId = new int[SLOTS];
    private final int[] slotOutput = new int[SLOTS];
    private final Cluster[] slotCluster = new Cluster[SLOTS];
    /** 簇里这根手指按着哪几颗（成员下标，-1 = 空），最多两颗。 */
    private final int[][] slotKeys = new int[SLOTS][2];
    /** 簇：手指离开范围太远、全松了（回到范围里再按）。 */
    private final boolean[] slotAway = new boolean[SLOTS];
    /** 十字键的中心、摇杆的底座（像素）。 */
    private final float[] baseX = new float[SLOTS], baseY = new float[SLOTS];
    /** 十字键半径（像素）、方向数、现在的方向（Hid.Gamepad.HAT_*，0 回中）。 */
    private final float[] slotRadius = new float[SLOTS];
    private final int[] slotDirs = new int[SLOTS];
    private final int[] slotHat = new int[SLOTS];
    /** 摇杆：哪一根（0 左、1 右）、现在的轴值、推到边响过没有。 */
    private final int[] slotStick = new int[SLOTS];
    private final int[] slotAxisX = new int[SLOTS], slotAxisY = new int[SLOTS];
    private final boolean[] slotRim = new boolean[SLOTS];

    /** 每个输出码按着几根手指（引用计数）。 */
    private final int[] held = new int[Proto.GP_OUTPUTS];
    /** 十字键、摇杆被哪根手指占着（控件编号 → slot）。 */
    private final Map<Integer, Integer> owner = new HashMap<>();

    private final Hid.Gamepad state = new Hid.Gamepad();
    private final byte[] report = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private final byte[] sent = new byte[Hid.GAMEPAD_REPORT_SIZE];
    private boolean axesPending;

    GamepadTouch(Output out) {
        this.out = out;
        for (int[] k : slotKeys) Arrays.fill(k, -1);
    }

    void setDensity(float density) {
        this.density = density > 0 ? density : 1;
    }

    /** 换参数：先回中立（按着的全松，发全零），再换。 */
    void setConfig(GamepadConfig c) {
        releaseAll();
        cfg = c != null ? c.copy().sane() : new GamepadConfig();
    }

    GamepadConfig config() {
        return cfg;
    }

    /**
     * 新布局：认出面键簇和它的成员（范围后面接着的、整个落在范围里的手柄单键）。按着的手指照用按下时那一份几何，抬起才换。
     */
    void setRegions(TouchRouter.Region[] regions) {
        clusters.clear();
        for (int i = 0; i < regions.length; i++) {
            TouchRouter.Region a = regions[i];
            if (a.type != Proto.REGION_GAMEPAD || Proto.gamepadKind(a.id) != Proto.GP_KIND_CLUSTER) continue;
            int j = i + 1;
            while (j < regions.length && regions[j].type == Proto.REGION_GAMEPAD
                    && Proto.gamepadKind(regions[j].id) == Proto.GP_KIND_BUTTON && regions[j].inside(a)) {
                j++;
            }
            clusters.put(a, new Cluster(a, Arrays.copyOfRange(regions, i + 1, j)));
        }
    }

    /** 现在的报文是不是全零（测试用）。 */
    boolean neutral() {
        return Hid.Gamepad.neutral(sent);
    }

    // ═══════════ 触摸（屏幕像素） ═══════════

    void down(int slot, TouchRouter.Region r, float x, float y) {
        if (slot < 0 || slot >= SLOTS) return;
        if (kind[slot] != NONE) up(slot);   // 万一同一个 slot 没抬起又按下：先收尾
        int id = r.id;
        switch (Proto.gamepadKind(id)) {
            case Proto.GP_KIND_CLUSTER: {
                Cluster c = clusters.get(r);
                if (c == null || c.id.length == 0) return;
                kind[slot] = CLUSTER;
                slotCluster[slot] = c;
                slotKeys[slot][0] = slotKeys[slot][1] = -1;
                slotAway[slot] = false;
                setKeys(slot, pick(c, x, y, slot, true));
                send();
                break;
            }
            case Proto.GP_KIND_DPAD: {
                if (owner.containsKey(id)) return;   // 十字键上已经有一根手指了：再来的不理
                owner.put(id, slot);
                kind[slot] = DPAD;
                slotId[slot] = id;
                baseX[slot] = (r.left + r.right) / 2f;
                baseY[slot] = (r.top + r.bottom) / 2f;
                slotRadius[slot] = Math.max(1, Math.min(r.right - r.left, r.bottom - r.top) / 2f);
                slotDirs[slot] = Proto.gamepadParam(id) == 4 || !cfg.dpad8 ? 4 : 8;
                slotHat[slot] = 0;
                dpad(slot, x, y);
                break;
            }
            case Proto.GP_KIND_STICK: {
                if (owner.containsKey(id)) return;   // 摇杆上已经有一根手指了：再来的不理
                owner.put(id, slot);
                kind[slot] = STICK;
                slotId[slot] = id;
                slotStick[slot] = Proto.gamepadParam(id) & 1;
                if (cfg.floatingCenter) {
                    baseX[slot] = x;
                    baseY[slot] = y;
                } else {
                    baseX[slot] = (r.left + r.right) / 2f;
                    baseY[slot] = (r.top + r.bottom) / 2f;
                }
                slotAxisX[slot] = slotAxisY[slot] = 0;
                slotRim[slot] = false;
                stick(slot, x, y);
                break;
            }
            case Proto.GP_KIND_BUTTON:
            default: {
                int o = Proto.gamepadParam(id);
                if (o >= Proto.GP_OUTPUTS) return;
                kind[slot] = BUTTON;
                slotId[slot] = id;
                slotOutput[slot] = o;
                held[o]++;
                out.button(id, Proto.BUTTON_DOWN);
                send();
                break;
            }
        }
    }

    void move(int slot, float x, float y) {
        if (slot < 0 || slot >= SLOTS) return;
        switch (kind[slot]) {
            case CLUSTER:
                clusterMove(slot, x, y);
                break;
            case DPAD:
                dpad(slot, x, y);
                break;
            case STICK:
                stick(slot, x, y);
                break;
            default:
                break;   // 单键：滑出照样按着
        }
    }

    /** 一帧触摸报完：攒着的摇杆轴发出去。 */
    void frame() {
        if (axesPending) send();
    }

    /** 抬起 / 被取消（手柄这一路一样处理：全松）。自己冲一次报文（含攒着的轴）。 */
    void up(int slot) {
        if (slot < 0 || slot >= SLOTS) return;
        release(slot);
        send();
    }

    /** 放手：所有手指全松，发全零（没用过就什么都不发）。 */
    void releaseAll() {
        for (int s = 0; s < SLOTS; s++) release(s);
        owner.clear();
        Arrays.fill(held, 0);
        send();
    }

    private void release(int slot) {
        switch (kind[slot]) {
            case BUTTON: {
                int o = slotOutput[slot];
                held[o] = Math.max(0, held[o] - 1);
                out.button(slotId[slot], Proto.BUTTON_UP);
                break;
            }
            case CLUSTER:
                setKeys(slot, null);
                slotCluster[slot] = null;
                break;
            case DPAD:
                if (slotHat[slot] != 0) out.button(slotId[slot], Proto.BUTTON_UP);
                slotHat[slot] = 0;
                owner.remove(slotId[slot]);
                break;
            case STICK:
                slotAxisX[slot] = slotAxisY[slot] = 0;
                owner.remove(slotId[slot]);
                break;
            default:
                break;
        }
        kind[slot] = NONE;
    }

    // ═══════════ 面键簇 ═══════════

    private void clusterMove(int slot, float x, float y) {
        if (!cfg.clusterSlide) return;   // 关了：按下时挑的那颗一直按着，滑动不换
        Cluster c = slotCluster[slot];
        TouchRouter.Region a = c.area;
        if (!slotAway[slot]) {
            float exit = cfg.clusterExitDp * density;
            if (x < a.left - exit || x >= a.right + exit || y < a.top - exit || y >= a.bottom + exit) {
                slotAway[slot] = true;   // 离簇的范围太远：全松
                if (setKeys(slot, null)) send();
                return;
            }
            if (setKeys(slot, pick(c, x, y, slot, false))) send();
        } else if (a.contains(x, y)) {
            slotAway[slot] = false;   // 回到范围里：再按
            if (setKeys(slot, pick(c, x, y, slot, true))) send();
        }
    }

    /**
     * 这一点该按哪颗（最多两颗，第一颗是最近的）。e = 到键边的距离（在键里为负）。
     * fresh = 刚按下 / 刚回到范围里（不看现在按着的，没有回差）。滑动换键关着时只挑最近的一颗。
     */
    private int[] pick(Cluster c, float x, float y, int slot, boolean fresh) {
        int n = c.id.length;
        float[] e = new float[n];
        for (int i = 0; i < n; i++) e[i] = (float) Math.hypot(x - c.cx[i], y - c.cy[i]) - c.radius[i];
        float hyst = HYST_DP * density;
        int[] cur = slotKeys[slot];
        int best = 0;
        for (int i = 1; i < n; i++) if (e[i] < e[best]) best = i;
        // 主键回差：按着的那颗只比最近的远一点点时不换（停在两颗正中间时不来回跳）
        if (!fresh) {
            for (int k : cur) if (k >= 0 && k != best && e[k] <= e[best] + hyst) best = k;
        }
        if (!cfg.clusterSlide || n < 2) return new int[]{best, -1};
        int second = -1;
        for (int i = 0; i < n; i++) {
            if (i == best) continue;
            if (second < 0 || e[i] < e[second]) second = i;
        }
        float chord = cfg.chordDp * density;
        boolean bestHeld = !fresh && (cur[0] == best || cur[1] == best);
        boolean secondHeld = !fresh && (cur[0] == second || cur[1] == second);
        if (e[best] <= chord + (bestHeld ? hyst : 0) && e[second] <= chord + (secondHeld ? hyst : 0)) {
            return new int[]{best, second};
        }
        return new int[]{best, -1};
    }

    /** 换成这几颗（null = 全松）：先松不要了的、再按新加的（"嗒"）。返回变了没有。 */
    private boolean setKeys(int slot, int[] next) {
        Cluster c = slotCluster[slot];
        int[] cur = slotKeys[slot];
        boolean changed = false;
        for (int i = 0; i < 2; i++) {
            int k = cur[i];
            if (k < 0 || has(next, k)) continue;
            int o = c.output[k];
            if (o < Proto.GP_OUTPUTS) held[o] = Math.max(0, held[o] - 1);
            out.button(c.id[k], Proto.BUTTON_UP);
            changed = true;
        }
        int[] was = cur.clone();
        cur[0] = cur[1] = -1;
        if (next != null) {
            int at = 0;
            for (int k : next) {
                if (k < 0) continue;
                cur[at++] = k;
                if (has(was, k)) continue;
                int o = c.output[k];
                if (o < Proto.GP_OUTPUTS) held[o]++;
                out.button(c.id[k], Proto.BUTTON_DOWN);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean has(int[] keys, int k) {
        if (keys == null) return false;
        for (int x : keys) if (x == k) return true;
        return false;
    }

    // ═══════════ 十字键 ═══════════

    /** 方向（Hid.Gamepad.HAT_*）的角度（度，0 = 右，90 = 上）。下标 = HAT 值，0 不用。 */
    private static final float[] HAT_ANGLE = {0, 90, 45, 0, -45, -90, -135, 180, 135};

    private void dpad(int slot, float x, float y) {
        float dx = x - baseX[slot], dy = y - baseY[slot];
        float m = (float) Math.hypot(dx, dy);
        int cur = slotHat[slot];
        float dz = cfg.dpadDeadzone * slotRadius[slot];
        int next;
        if (m < (cur == 0 ? dz : dz * DPAD_BACK)) {
            next = 0;
        } else {
            next = sector((float) Math.toDegrees(Math.atan2(-dy, dx)), slotDirs[slot], cur, cfg.diagonalDeg);
        }
        if (next == cur) return;
        slotHat[slot] = next;
        if (cur == 0) out.button(slotId[slot], Proto.BUTTON_DOWN);   // 从中间推出一个方向："嗒"
        else if (next == 0) out.button(slotId[slot], Proto.BUTTON_UP);   // 回中：不震
        else if (cfg.dpadTicks) out.haptic(GestureEngine.HAPTIC_TICK);   // 按着换方向（含斜向）："咔嗒"
        send();
    }

    /**
     * 角度 a（度，0 = 右、90 = 上）落在哪个方向的扇区：8 方向时斜向扇区 diag 度宽、正向 90 − diag 度宽；4 方向各 90°。
     * 现在有方向（cur）时，角度还在它的扇区外扩 {@link #DIR_HYST_DEG} 以内就不换（停在边界上不来回跳）。
     */
    static int sector(float a, int dirs, int cur, float diag) {
        float diagHalf = dirs == 8 ? diag / 2 : 0;
        float cardHalf = dirs == 8 ? (90 - diag) / 2 : 45;
        if (cur > 0 && (dirs == 8 || cur % 2 == 1) && diff(a, HAT_ANGLE[cur]) <= half(cur, cardHalf, diagHalf) + DIR_HYST_DEG) return cur;
        int best = 0;
        float bestOver = Float.MAX_VALUE;
        for (int h = 1; h <= 8; h++) {
            if (dirs == 4 && h % 2 == 0) continue;   // 4 方向只有上 1、右 3、下 5、左 7
            float over = diff(a, HAT_ANGLE[h]) - half(h, cardHalf, diagHalf);
            if (over < bestOver) {
                bestOver = over;
                best = h;
            }
        }
        return best;
    }

    /** 正向（HAT 奇数）、斜向（偶数）扇区的半宽。 */
    private static float half(int hat, float cardHalf, float diagHalf) {
        return hat % 2 == 1 ? cardHalf : diagHalf;
    }

    /** 两个角度差多少（0–180）。 */
    private static float diff(float a, float b) {
        float d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    // ═══════════ 摇杆 ═══════════

    private void stick(int slot, float x, float y) {
        float r = cfg.stickRadiusDp * density;
        float dx = x - baseX[slot], dy = y - baseY[slot];
        float m = (float) Math.hypot(dx, dy) / r;
        if (m > 1) {
            if (cfg.followBase) {
                // 推过半径：底座跟着手指走（离手指正好一个半径），反向推马上生效
                baseX[slot] = x - dx / m;
                baseY[slot] = y - dy / m;
            }
            dx /= m;
            dy /= m;
            m = 1;
        }
        float dz = cfg.deadzone, anti = cfg.antiDeadzone;
        float o = m <= dz ? 0 : Math.min(1, anti + (1 - anti) * (m - dz) / (1 - dz));
        int ax = 0, ay = 0;
        if (o > 0 && m > 0) {
            float k = o / (m * r);
            ax = Math.round(dx * k * Hid.Gamepad.AXIS_MAX);
            ay = Math.round(dy * k * Hid.Gamepad.AXIS_MAX);   // 往下为正（屏幕坐标、HID 都是）
        }
        if (!slotRim[slot] && o >= RIM_ON) {
            slotRim[slot] = true;
            if (cfg.rimTicks) out.haptic(GestureEngine.HAPTIC_TICK);   // 推到边："咔嗒"
        } else if (slotRim[slot] && o < RIM_OFF) {
            slotRim[slot] = false;   // 收回来一点：重新上弦
        }
        if (ax == slotAxisX[slot] && ay == slotAxisY[slot]) return;
        slotAxisX[slot] = ax;
        slotAxisY[slot] = ay;
        axesPending = true;   // 攒到这一帧报完再发
    }

    // ═══════════ 报文 ═══════════

    /** 按现在的状态拼报文，和上次发的不一样才发。 */
    private void send() {
        axesPending = false;
        state.clear();
        for (int o = 0; o < 15; o++) if (held[o] > 0) state.buttons |= 1 << o;
        state.lt = held[Proto.GP_LT] > 0 ? 255 : 0;
        state.rt = held[Proto.GP_RT] > 0 ? 255 : 0;
        for (int s = 0; s < SLOTS; s++) {
            if (kind[s] == DPAD && state.hat == 0) state.hat = slotHat[s];
            if (kind[s] == STICK) {
                if (slotStick[s] == 0) {
                    state.lx = slotAxisX[s];
                    state.ly = slotAxisY[s];
                } else {
                    state.rx = slotAxisX[s];
                    state.ry = slotAxisY[s];
                }
            }
        }
        cKeys();
        state.report(report);
        if (Arrays.equals(report, sent)) return;
        System.arraycopy(report, 0, sent, 0, report.length);
        out.report(report);
    }

    /**
     * N64 的 C 键 = 右摇杆推满（模拟器一般这么认）：上下左右各一颗，相邻两颗一起按 = 斜着推满（各 0.707，和真摇杆一样推不出方框的角），
     * 相反两颗一起按那根轴回中。右摇杆上同时有手指的话取离中心远的那个（同两路合并的规则）。
     */
    private void cKeys() {
        int cx = (held[Proto.GP_C_RIGHT] > 0 ? 1 : 0) - (held[Proto.GP_C_LEFT] > 0 ? 1 : 0);
        int cy = (held[Proto.GP_C_DOWN] > 0 ? 1 : 0) - (held[Proto.GP_C_UP] > 0 ? 1 : 0);
        if (cx == 0 && cy == 0) return;
        float k = cx != 0 && cy != 0 ? (float) Math.sqrt(0.5) : 1;
        int vx = Math.round(cx * k * Hid.Gamepad.AXIS_MAX), vy = Math.round(cy * k * Hid.Gamepad.AXIS_MAX);
        if ((long) vx * vx + (long) vy * vy > (long) state.rx * state.rx + (long) state.ry * state.ry) {
            state.rx = vx;
            state.ry = vy;
        }
    }
}
