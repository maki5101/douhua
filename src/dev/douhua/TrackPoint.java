package dev.douhua;

/**
 * 小红点（模拟 ThinkPad 的 TrackPoint），纯 Java（离线测，TrackPointTest）。键盘页 ⋯ 菜单里"指点"选小红点时有。
 *
 * 真的 TrackPoint 是测力的摇杆：推的力 → 光标速度。触摸屏量不了力（外屏触摸屏没有压力轴，按重按轻接触面也差不多），
 * 只能拿手指离按下点的位移当"力"：原点 = 按下的那一点（浮动摇杆，按偏了不会一按就窜），往哪边推光标就往哪边持续走，
 * 推得越远越快，松手立刻停。手指滑出红点也照算（归属按下时定），推到底速度封顶。
 *
 * 速度曲线（{@link #curve}，照 IBM 专利里的 S 形：死区、爬行、上升、平台、陡升，数字是方案按资料和小模型定的初值，没在真机上验证）：
 * 位移 m ≤ {@link #DEAD_DP} 不动（按住不动时拇指重心的抖动）；u = (m − 死区) / (推到底 − 死区)，
 * u < 0.15 爬行（像素级微调），0.15–0.45 平滑升到平台，0.45–0.70 平台（速度基本不变、好对准），0.70–1 陡升到最快，≥ 1 封顶。
 * 再乘设置里的灵敏度（{@link GestureEngine.Config#stickSpeed}）。不加平滑滤波（论文说更拖，空中鼠标也试过"如履薄冰"）。
 *
 * 负惯性（{@link GestureEngine.Config#stickInertia}，Barrett 等 CHI '95 / 专利 US5570111）：对位移加微分项
 * z = m + I·(m − m 的一阶滞后)，起步、停下更利落，巡航速度不变；夹在不反向（不往回弹）。I = 3（专利是 6，触摸屏 120Hz 一阶一阶地更新，6 时平台速度抖得多）。
 *
 * 报文只在节拍上发（{@link #tick}，宿主按固定间隔调：桌面模式 8ms、蓝牙 12ms）：手指按住不动时触摸屏多半不再报事件，速度却要一直发。
 * 每拍按实际过去的时间积分，小数部分留到下一拍；一拍隔太久（卡了一下）按 {@link #MAX_DT_MS} 算，免得一拍窜一大截。
 *
 * 滚动模式（中键按着）：同一条曲线、单位换成格 / 秒，出滚轮（1/120 格）不出位移；方向"推哪边视野往哪边走"（往下推 = 往下翻），
 * 不跟自然滚动开关；一个方向占 2 倍以上时只滚这个方向。按格模式每格咔嗒一下，连续模式每滚过一格的距离咔嗒一下（跟"滚动咔嗒"开关）。
 *
 * 轻点红点 = 左键（{@link GestureEngine.Config#stickTap}）：按下到抬起不超过 tapMaxMs、离按下点没超过 tapSlopDp（同触控板轻点）。
 * 反馈照"同一种操作反馈一样"：推着走 = 颗粒（每走 {@link #TEXTURE_COUNTS} 个计数一颗，跟"颗粒"开关）；按着鼠标键时是拖动，不发颗粒
 * （有持续轻震）；滚动 = 咔嗒；轻点 = 点一下。
 *
 * 兜底：按着但 {@link #IDLE_PAUSE_MS} 没收到这根手指的任何事件就暂停（不出报文、节拍可以停），这根手指再动就接着。
 *
 * 中键滚动杆（{@link #MODE_SCROLL_LEVER}，2026-11-01）：TouchRouter 给按在中键（三个鼠标键的中键）上的第一根手指另开一个，
 * 让这根手指自己也能当"按住中键推红点"用——手指离按下点的位移当推力，同一套节拍积分。和红点的不同有四处：
 * 一直是滚动模式（tick 的 scroll 不看）；抬起不算轻点（中键单击由 TouchRouter 按"滚过没有"补）；这次按下离按下点先挪出轻点容差
 * （{@link GestureEngine.Config#tapSlopDp}）才算开始（{@link #started}）；速度慢一档（LEVER_*，位移从轻点容差那一圈起算，
 * 离按下点容差 + 死区以内都不滚、挪回来就停，用户 2026-11-01 嫌照红点的曲线太快）——
 * 中键那根手指还要能点中键单击，按下 / 抬起时指肚在触摸屏上滚一下常有两三 dp（估的，没量过），只看 2.5dp 的死区的话，连续滚动开着时点一下中键会先滚出一点、
 * 单击也就没了（同触控板上的中键滚动：挪出轻点容差才开始）。
 *
 * 左键杆（{@link #MODE_DRAG_LEVER}，2026-11-01 晚）：TouchRouter 给按在左键（三个鼠标键的左键）上的第一根手指另开一个，
 * 让这根手指自己当小红点用——左键一直按着（TouchRouter 按下时就按住了），所以推出来的就是拖动。和中键滚动杆一样：抬起不算轻点
 * （单击由左键自己的按下 / 松开出）；先挪出轻点容差才开始；位移从轻点容差那一圈起算（{@link #leverLevel}，一出容差不跳一下）。
 * 和中键滚动杆不一样：一直是移动模式（tick 的 scroll 不看，中键按着也不滚）、走红点推光标的那条曲线（MOVE_*，× 灵敏度、负惯性照设置），
 * 一直当按着鼠标键（不发颗粒：拖动有持续轻震，同"同一种操作，反馈一样"）。
 *
 * 不加锁：宿主保证同一时刻只有一个线程在调（同 TouchRouter）。
 */
final class TrackPoint {
    interface Output {
        /** 光标位移（鼠标计数）。 */
        void move(int dx, int dy);

        /** 滚轮：1/120 格（Hid.WHEEL_UNIT），正 = 往上 / 往右。 */
        void scroll(int wheel, int pan);

        /** 轻点成立：点一下左键。 */
        void click();

        void haptic(int kind);
    }

    /** 死区（dp，约 0.4 毫米）：按住不动时拇指重心的抖动。 */
    static final float DEAD_DP = 2.5f;
    /** 推到底（dp，约 5 毫米）：再远速度封顶。 */
    static final float FULL_DP = 32f;
    static final float CRAWL_END = 0.15f, RISE_END = 0.45f, PLATEAU_END = 0.70f;
    /**
     * 移动的速度（鼠标计数 / 秒）：爬行、平台、最快（一屏 1920 像素约 1.6 秒）。
     * 最快原来 3000（约 0.65 秒一屏），用户 2026-11-01："移动远一点的话非常不好控制，要降低一些"——平台到最快只翻 2.7 倍（原来 6.7 倍）。
     */
    static final float MOVE_CRAWL = 40, MOVE_PLATEAU = 450, MOVE_MAX = 1200;
    /** 中键滚动的速度（格 / 秒）：原来 2 / 10 / 40，用户 2026-11-01 嫌慢，整体翻倍。 */
    static final float SCROLL_CRAWL = 4, SCROLL_PLATEAU = 20, SCROLL_MAX = 80;
    /**
     * 中键滚动杆的滚动速度（格 / 秒，设置里的倍数 {@link GestureEngine.Config#leverSpeed} 是 100% 时）。用户 2026-11-01 试了滚动杆说"这个滚动速度太快了"——
     * 手指在键上一划就是二三十 dp，照红点那条曲线（还是从按下点量、挪出轻点容差那一下已经在曲线中段）一下就到平台 / 最快：先降成 2 / 10 / 40（红点翻倍之前那一组），
     * 位移从挪出轻点容差那一圈起算（{@link #leverLevel}）；当天晚上又说"还是太快"，再慢一半成 1 / 5 / 20，另加设置里的滑块（25%–200%）。
     */
    static final float LEVER_CRAWL = 1, LEVER_PLATEAU = 5, LEVER_MAX = 20;
    /** 负惯性：增益、滞后的时间常数（毫秒）。 */
    static final float INERTIA_GAIN = 3, INERTIA_TAU_MS = 10;
    /** 一拍最多按这么久积分（主线程 / 定时器卡了一下时不窜）。 */
    static final int MAX_DT_MS = 50;
    /** 推着走时光标每走这么多计数一颗颗粒（照触控板慢划 10dp ≈ 12–26 计数、空中指针 1.5° = 45 计数取的中间值）。 */
    static final int TEXTURE_COUNTS = 30;
    /** 按着但这么久没收到这根手指的事件：暂停（兜底，防节拍漏停光标一直漂）。 */
    static final long IDLE_PAUSE_MS = 20000;
    /** 中键滚动：一个方向占另一个这么多倍以上就只滚它。 */
    static final float AXIS_DOMINANCE = 2;

    /** 红点（{@link #TrackPoint(Output)}）：死区外就走，轻点 = 左键（开着时），中键按着时滚动。 */
    static final int MODE_STICK = 0;
    /** 中键滚动杆（见类注释）：只滚动、抬起不点左键、先挪出轻点容差才开始，速度走 LEVER_*。 */
    static final int MODE_SCROLL_LEVER = 1;
    /** 左键杆（见类注释）：只移动、当按着鼠标键（不发颗粒）、抬起不点左键、先挪出轻点容差才开始，速度走红点的 MOVE_*。 */
    static final int MODE_DRAG_LEVER = 2;

    private final Output out;
    private final int mode;
    /** 两种杆（中键滚动杆、左键杆）共有的：抬起不点左键、先挪出轻点容差才开始、位移从容差那一圈起算。红点是 false。 */
    private final boolean lever;
    private GestureEngine.Config cfg = new GestureEngine.Config();

    private boolean active;
    /** 这次按下已经开始出东西了（红点一按下就是；滚动杆挪出过轻点容差才是）。 */
    private boolean started;
    /** 暂停了（太久没事件）：不出报文，再动就接着。 */
    private boolean paused;
    /** 按下点、当前位置（dp）。 */
    private float ox, oy, x, y;
    private long downT, lastTick, lastEvent;
    /** 这次按下离按下点最远到过多远（dp，判断轻点）。 */
    private float maxTravel;
    /** 上一帧以来这根手指动过（frame 时记下时间）。 */
    private boolean touched;
    /** 位移的一阶滞后（负惯性用）。 */
    private float lag;
    /** 上一拍是不是滚动模式：换了模式两边的余数清掉。 */
    private boolean lastScroll;
    /** 位移的余数（计数）、滚动的余数（1/120 格）。 */
    private float remX, remY, wheelRem, panRem;
    /** 连续模式下每滚过一格咔嗒一下：上一下之后出了多少单位（带方向）。 */
    private int tickAccWheel, tickAccPan;
    /** 颗粒：上一颗之后光标走了多少计数、上一颗的时间。 */
    private float textureCounts;
    private long lastTexture = Long.MIN_VALUE / 2;

    TrackPoint(Output out) {
        this(out, MODE_STICK);
    }

    /** mode = MODE_STICK / MODE_SCROLL_LEVER / MODE_DRAG_LEVER（见类注释）。 */
    TrackPoint(Output out, int mode) {
        this.out = out;
        this.mode = mode;
        this.lever = mode != MODE_STICK;
    }

    /** 新参数（灵敏度、轻点、负惯性、颗粒、咔嗒、轻点的时长和容差）。正按着的照旧，从下一拍起按新的算。 */
    void setConfig(GestureEngine.Config c) {
        cfg = c;
    }

    boolean active() {
        return active;
    }

    /**
     * 这次按下开始出东西了没有：红点按着就是；两种杆要先挪出轻点容差（TouchRouter 据此认中键"滚过了"、松开不补中键单击，
     * 也据此判杆让不让位：还没开始时别的来源先出了东西，杆就让位）。
     */
    boolean started() {
        return active && started;
    }

    /** 手指按在红点上（dp、毫秒）。 */
    void down(float px, float py, long t) {
        active = true;
        paused = false;
        started = !lever;
        ox = x = px;
        oy = y = py;
        downT = lastTick = lastEvent = t;
        maxTravel = 0;
        touched = false;
        lag = 0;
        lastScroll = false;
        remX = remY = wheelRem = panRem = 0;
        tickAccWheel = tickAccPan = 0;
        textureCounts = 0;
    }

    void move(float px, float py) {
        if (!active) return;
        x = px;
        y = py;
        maxTravel = Math.max(maxTravel, (float) Math.hypot(x - ox, y - oy));
        if (maxTravel > cfg.tapSlopDp) started = true;   // 两种杆：挪出轻点容差，开始（红点本来就是 true）
        touched = true;
    }

    /**
     * 一帧触摸报完：这根手指这一帧动过就记下时间。返回 true = 刚才因为太久没事件暂停了、现在接着（宿主要重新开节拍）。
     */
    boolean frame(long t) {
        if (!active || !touched) return false;
        touched = false;
        lastEvent = t;
        if (!paused) return false;
        paused = false;
        lastTick = t;   // 暂停的那一段不补
        return true;
    }

    /** 手指抬起。没被取消、开着"轻点 = 左键"、够快、没挪远：点一下左键（"嗒"一下）。两种杆都不算轻点（中键单击、左键单击由 TouchRouter 出）。 */
    void up(long t, boolean canceled) {
        if (!active) return;
        active = false;
        paused = false;
        if (canceled || lever || !cfg.stickTap) return;
        if (t - downT <= cfg.tapMaxMs && maxTravel <= cfg.tapSlopDp) {
            out.haptic(GestureEngine.HAPTIC_CLICK);
            out.click();
        }
    }

    /** 作废（触摸屏释放、断开、换参数）：不点击，之后的节拍什么都不出。 */
    void cancel() {
        active = false;
        paused = false;
    }

    /**
     * 一拍：按到 t 为止实际过去的时间积分，出位移（或者滚轮）。scroll = 中键按着（滚动模式；中键滚动杆一直是，左键杆一直不是）；
     * buttons = 按着鼠标键（拖动，不发颗粒；左键杆一直是）；smooth = 滚动按连续模式出（GestureEngine.smoothNow：开着连续滚动、没锁着 Ctrl）。
     * 返回 false = 没按着或者暂停了，宿主可以停节拍。
     */
    boolean tick(long t, boolean scroll, boolean buttons, boolean smooth) {
        if (!active || paused) return false;
        if (mode == MODE_SCROLL_LEVER) {
            scroll = true;
        } else if (mode == MODE_DRAG_LEVER) {   // 左键按着：一直是拖动（移动、不发颗粒），中键也按着时照样移动
            scroll = false;
            buttons = true;
        }
        if (t - lastEvent > IDLE_PAUSE_MS) {
            paused = true;
            return false;
        }
        long dt = t - lastTick;
        if (dt <= 0) return true;
        lastTick = t;
        dt = Math.min(dt, MAX_DT_MS);
        float dx = x - ox, dy = y - oy;
        float dist = (float) Math.hypot(dx, dy);
        float m = lever ? leverLevel(dist, cfg.tapSlopDp) : dist;   // 两种杆：从挪出轻点容差那一圈起算，开始那一下是 0
        lag += dt / (INERTIA_TAU_MS + dt) * (m - lag);
        if (scroll != lastScroll) {   // 中键刚按下 / 松开：另一种模式攒的零头不带过来
            lastScroll = scroll;
            remX = remY = wheelRem = panRem = 0;
            tickAccWheel = tickAccPan = 0;
        }
        if (!started) return true;   // 杆还没挪出轻点容差：不出东西（滞后照样跟着，开始那一下不突变）
        if (m <= DEAD_DP) return true;   // 死区：不动（负惯性也不把抖动放大出去）
        float z = cfg.stickInertia ? Math.max(0, m + INERTIA_GAIN * (m - lag)) : m;
        float u = level(z);
        float ux = dx / dist, uy = dy / dist;   // 方向照按下点量（dist ≥ m > 死区，不会除零）
        if (scroll) {
            // 滚动杆另乘设置里的"中键滑动的滚动速度"（只管它；按住中键推红点照旧只乘灵敏度）
            float rate = mode == MODE_SCROLL_LEVER ? curve(u, LEVER_CRAWL, LEVER_PLATEAU, LEVER_MAX) * cfg.leverSpeed : curve(u, SCROLL_CRAWL, SCROLL_PLATEAU, SCROLL_MAX);
            float notches = rate * cfg.stickSpeed * dt / 1000f;
            float ax = Math.abs(ux), ay = Math.abs(uy);
            boolean onlyV = ay >= AXIS_DOMINANCE * ax, onlyH = ax >= AXIS_DOMINANCE * ay;
            float vy = onlyH ? 0 : uy, vx = onlyV ? 0 : ux;
            // 往下推 = 往下翻（滚轮负），往右推 = 往右翻（横向正）
            wheelRem += -vy * notches * Hid.WHEEL_UNIT;
            panRem += vx * notches * Hid.WHEEL_UNIT;
            emitScroll(smooth);
            return true;
        }
        float v = curve(u, MOVE_CRAWL, MOVE_PLATEAU, MOVE_MAX) * cfg.stickSpeed * dt / 1000f;
        float fx = ux * v + remX, fy = uy * v + remY;
        int ix = (int) fx, iy = (int) fy;   // 向零取整，余数留到下一拍
        remX = fx - ix;
        remY = fy - iy;
        if (ix == 0 && iy == 0) return true;
        out.move(ix, iy);
        if (cfg.texture && !buttons) {
            textureCounts += (float) Math.hypot(ix, iy);
            if (textureCounts >= TEXTURE_COUNTS) {
                textureCounts %= TEXTURE_COUNTS;
                if (t - lastTexture >= cfg.textureMinGapMs) {   // 太密就跳过（距离照扣，不攒着补发）
                    lastTexture = t;
                    out.haptic(GestureEngine.HAPTIC_TEXTURE);
                }
            }
        }
        return true;
    }

    /** 攒的滚轮量出出去：按格模式攒满一格（WHEEL_UNIT）出一格、咔嗒一下；连续模式按单位出、每滚过一格的距离咔嗒一下。 */
    private void emitScroll(boolean smooth) {
        int w, p;
        if (smooth) {
            w = (int) wheelRem;
            p = (int) panRem;
        } else {
            w = (int) (wheelRem / Hid.WHEEL_UNIT) * Hid.WHEEL_UNIT;
            p = (int) (panRem / Hid.WHEEL_UNIT) * Hid.WHEEL_UNIT;
        }
        wheelRem -= w;
        panRem -= p;
        if (w == 0 && p == 0) return;
        out.scroll(w, p);
        if (cfg.scrollTicks == GestureEngine.TICKS_OFF) return;
        boolean tick;
        if (!smooth) {
            tick = true;
        } else {
            tickAccWheel += w;
            tickAccPan += p;
            tick = false;
            if (Math.abs(tickAccWheel) >= Hid.WHEEL_UNIT) {
                tickAccWheel -= tickAccWheel > 0 ? Hid.WHEEL_UNIT : -Hid.WHEEL_UNIT;
                tick = true;
            }
            if (Math.abs(tickAccPan) >= Hid.WHEEL_UNIT) {
                tickAccPan -= tickAccPan > 0 ? Hid.WHEEL_UNIT : -Hid.WHEEL_UNIT;
                tick = true;
            }
        }
        if (tick) out.haptic(GestureEngine.HAPTIC_TICK);
    }

    /** 两种杆当"推力"的位移（dp）：离按下点的距离减去轻点容差（挪出容差才开始，那一下从 0 起，不一下跳到曲线中段），不小于 0。 */
    static float leverLevel(float dist, float slop) {
        return Math.max(0, dist - slop);
    }

    /** 中键滚动杆离按下点 dist（dp）时每秒滚几格（灵敏度 1、中键滑动速度 100%、没负惯性），测试和文档用。 */
    static float leverRate(float dist, float slop) {
        return curve(level(leverLevel(dist, slop)), LEVER_CRAWL, LEVER_PLATEAU, LEVER_MAX);
    }

    /**
     * 左键杆离按下点 dist（dp）时光标多快（计数 / 秒，灵敏度 1、没负惯性），测试和文档用：红点推光标的那条曲线，位移从轻点容差那一圈起算——
     * 离按下点 dist 的左键杆 = 推 dist − 容差的红点。
     */
    static float dragLeverSpeed(float dist, float slop) {
        return moveSpeed(leverLevel(dist, slop));
    }

    /** 位移（dp）→ 曲线的横轴 u：死区里 ≤ 0，推到底 = 1。 */
    static float level(float m) {
        return (m - DEAD_DP) / (FULL_DP - DEAD_DP);
    }

    /** 速度曲线：u ≤ 0 是 0；爬行 crawl、平滑升到平台 plateau、平台、平滑升到 max、封顶。 */
    static float curve(float u, float crawl, float plateau, float max) {
        if (u <= 0) return 0;
        if (u < CRAWL_END) return crawl;
        if (u < RISE_END) return crawl + (plateau - crawl) * smoothstep((u - CRAWL_END) / (RISE_END - CRAWL_END));
        if (u < PLATEAU_END) return plateau;
        if (u < 1) return plateau + (max - plateau) * smoothstep((u - PLATEAU_END) / (1 - PLATEAU_END));
        return max;
    }

    /** 光标速度（计数 / 秒，不含灵敏度）：位移 m（dp）时多快。设置页、测试用。 */
    static float moveSpeed(float m) {
        return curve(level(m), MOVE_CRAWL, MOVE_PLATEAU, MOVE_MAX);
    }

    private static float smoothstep(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
