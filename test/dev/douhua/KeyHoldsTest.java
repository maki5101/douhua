package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * KeyHolds 的离线测试（修饰键"点一下 = 按一下、按住才锁"、Caps 点一下发 / 按住只切显示、合并键点一下逗号 / 按住句号，2026-11-01）。
 * 全部经真正的 TouchRouter 跑（按在区域上的键、界面发来的 key() 两条路），看主机收到的键盘报文（写成 [修饰键|按着的键]，同方案的表）、
 * 报给界面的修饰键状态、震动、按键定时器。两个定时器（引擎的、按键的）由 {@link H#to} 按截止时刻依次到点。
 */
public final class KeyHoldsTest {
    static final int SHIFT = 0xE1, CTRL = 0xE0, ALT = 0xE2, RSHIFT = 0xE5, A = 0x04, B = 0x05, I = 0x0C, AT = 0x1F | (Hid.MOD_SHIFT << 8),
            CAPS = 0x39, SPACE = 0x2C, DUAL = Proto.dualKey(0x36, 0x37);
    /** "按住到点"的那一下震动：协议 25 起单独一种 HAPTIC_HOLD（以前和触控板轻点同是 HAPTIC_CLICK）。clicks 数的就是它。 */
    static final int CLICK = GestureEngine.HAPTIC_HOLD;

    /** 屏幕：触控板 (0,0)-(1000,1000)；下面一排键（每个 100 宽，第几个就是第几列），两个 Shift（⇧ 和功能行的）、两个空格副本、右 Shift；右上一个"左键"按钮。 */
    static final TouchRouter.Region[] REGIONS = {
            key(SHIFT, 0), key(CTRL, 1), key(ALT, 2), key(A, 3), key(B, 4), key(I, 5), key(AT, 6), key(CAPS, 7), key(DUAL, 8),
            key(SHIFT, 9), key(SPACE, 10), key(SPACE, 11), key(RSHIFT, 12),
            new TouchRouter.Region(Proto.REGION_HOLD_LEFT, 3, 1000, 0, 1100, 100),
            new TouchRouter.Region(Proto.REGION_PAD, 0, 0, 0, 1000, 1000),
    };

    static TouchRouter.Region key(int id, int col) {
        return new TouchRouter.Region(Proto.REGION_KEY, id, col * 100, 1000, col * 100 + 100, 1100);
    }

    /** 第 col 个键的中心 x（y 都是 1050）。 */
    static float x(int col) {
        return col * 100 + 50;
    }

    /** 分流的输出：键盘报文、修饰键状态、Caps、震动、两个定时器（截止时刻按 {@link #now} 算）。 */
    static final class R implements TouchRouter.Output {
        final List<String> keys = new ArrayList<>();
        final List<String> mods = new ArrayList<>();
        final List<String> all = new ArrayList<>();
        int capsSent, capsShown, clicks, lastMods, lastL, lastH, lastA;
        /** 快锁了（armed）新亮起的次数：每次报界面时数新多出来的位（同一步里亮了又退回也数得到）。 */
        int armedRises;
        long now;
        boolean keyPending, enginePending;
        long keyDue, engineDue;
        int mouseButtons;

        @Override
        public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
            mouseButtons = buttons;
            all.add("m" + buttons + (wheel != 0 ? " w" : ""));
        }

        @Override
        public void keyboard(byte[] r) {
            StringBuilder s = new StringBuilder(String.format("[%02x|", r[0] & 0xFF));
            boolean first = true;
            for (int i = 2; i < 8; i++) {
                if (r[i] == 0) continue;
                if (!first) s.append(' ');
                s.append(String.format("%02x", r[i] & 0xFF));
                first = false;
            }
            s.append(']');
            keys.add(s.toString());
            all.add(s.toString());
            lastMods = r[0] & 0xFF;
        }

        /** 多媒体 / 系统键（协议 24，F 行的第二功能）：每次报的码依次记下（"c" / "s" + 十六进制），最后报的另记着。 */
        final List<String> controls = new ArrayList<>();
        int lastConsumer, lastSystem;

        @Override
        public void consumer(int usage) {
            lastConsumer = usage;
            controls.add("c" + Integer.toHexString(usage));
            all.add("c" + Integer.toHexString(usage));
        }

        @Override
        public void system(int usage) {
            lastSystem = usage;
            controls.add("s" + Integer.toHexString(usage));
            all.add("s" + Integer.toHexString(usage));
        }

        @Override
        public void haptic(int kind) {
            if (kind == CLICK) clicks++;
            all.add("h" + kind);
        }

        @Override
        public void gesture(int fingers, int kind) {
            all.add("g" + fingers);
        }

        /** Fn 的状态（协议 21，Proto.FN_* 位）、Fn 快锁了新亮起的次数、fn🌐 单点报的 🌐 点击次数。 */
        int lastF, fnArmedRises, globes;

        @Override
        public void button(int id, int action) {
            if (id == Proto.GLOBE_BUTTON_ID) {
                if (action == Proto.BUTTON_TAP) globes++;
                all.add("globe" + action);
            }
        }

        @Override
        public void modifiers(int latched, int held, int armed, int fn) {
            armedRises += Integer.bitCount(armed & ~lastA);
            if ((fn & ~lastF & Proto.FN_ARMED) != 0) fnArmedRises++;
            lastL = latched;
            lastH = held;
            lastA = armed;
            lastF = fn;
            String s = latched + "/" + held + "/" + armed + (fn != 0 ? "/fn" + fn : "");
            mods.add(s);
            all.add("mod" + s);
        }

        @Override
        public void caps(boolean sent) {
            if (sent) capsSent++;
            else capsShown++;
            all.add("caps" + (sent ? 1 : 0));
        }

        @Override
        public void schedule(long delayMs) {
            enginePending = true;
            engineDue = now + delayMs;
        }

        @Override
        public void cancelSchedule() {
            enginePending = false;
        }

        @Override
        public void scheduleKeys(long delayMs) {
            check(delayMs >= 0, "按键定时器的延迟不是负的");
            keyPending = true;
            keyDue = now + delayMs;
        }

        @Override
        public void cancelKeys() {
            keyPending = false;
        }

        @Override
        public void startTicks() {
        }

        @Override
        public void stopTicks() {
        }

        @Override
        public void gamepad(byte[] report) {
        }

        String lastKeys() {
            return keys.isEmpty() ? "" : keys.get(keys.size() - 1);
        }
    }

    /** 一个分流 + 时钟：每个操作先把到点的定时器按截止时刻依次调掉。 */
    static final class H {
        final R r = new R();
        final TouchRouter t = new TouchRouter(r);
        long now;

        /** 同一个屏幕上的键所在的那一份区域（{@link #REGIONS} 或 Fn 的 {@link #FN_REGIONS}）。 */
        final TouchRouter.Region[] regions;

        H() {
            this(REGIONS);
        }

        H(TouchRouter.Region[] regions) {
            this.regions = regions;
            t.setConfig(new GestureEngine.Config(), 1);
            t.setRegions(regions);
        }

        /** 走到 t1：到点的定时器（引擎的、按键的）按先后到点。 */
        void to(long t1) {
            while (true) {
                boolean k = r.keyPending && r.keyDue <= t1, e = r.enginePending && r.engineDue <= t1;
                if (!k && !e) break;
                if (k && (!e || r.keyDue <= r.engineDue)) {
                    long d = r.keyDue;
                    r.keyPending = false;
                    now = r.now = d;
                    t.onKeyTimer(d);
                } else {
                    long d = r.engineDue;
                    r.enginePending = false;
                    now = r.now = d;
                    t.onTimer(d);
                }
            }
            if (t1 > now) now = t1;
            r.now = now;
        }

        void kd(int key, long t1) {
            to(t1);
            t.key(key, Proto.KEY_DOWN, t1);
        }

        void ku(int key, long t1) {
            to(t1);
            t.key(key, Proto.KEY_UP, t1);
        }

        void kc(int key, long t1) {
            to(t1);
            t.key(key, Proto.KEY_CANCEL, t1);
        }

        /** 手指按在第 col 个键（区域那一路）。 */
        void td(int slot, int col, long t1) {
            to(t1);
            t.down(slot, x(col), 1050, t1);
            t.frame(t1);
        }

        void tu(int slot, long t1, boolean canceled) {
            to(t1);
            t.up(slot, t1, canceled);
            t.frame(t1);
        }

        String lastKeys() {
            return r.lastKeys();
        }

        /** 触控板上轻点一下（左键按下 / 等轻点拖动的窗口过去松开）。 */
        void padTap(int slot, long t1) {
            to(t1);
            t.down(slot, 500, 500, t1);
            t.frame(t1);
            to(t1 + 40);
            t.up(slot, t1 + 40);
            t.frame(t1 + 40);
        }
    }

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        modifierTap();
        modifierLatch();
        modifierHoldTooLong();
        modifierUsedByKeys();
        modifierUsedByMouse();
        modifierPlainMoveStillLatches();
        latchedPressAgainUnlatches();
        sameUsageTwoFingers();
        canceledArmedNoLatch();
        plainKeyUnlatches();
        unlatchOnlyLatched();
        reviewMouseBusy();
        reviewKeyHeldFirst();
        reviewTwoModifiers();
        reviewShiftDualShiftFirst();
        reviewLatchedShiftRoll();
        reviewShiftCapsShiftFirst();
        reviewZoomSettles();
        reviewAtKeepsShift();
        capsTap();
        capsHold();
        capsRoll();
        capsCanceled();
        canceledModifierNoSettle();
        capsLatchedShift();
        dualTap();
        dualHold();
        dualRoll();
        dualCanceledAndLatched();
        dualInGap();
        countsAfterReleaseAll();
        spaceCopies();
        timerOnlyWhilePending();
        timerOutOfOrder();
        regionAndKeyPathsAgree();
        newConfigKeepsKeys();
        randomSequences();
        fnIds();
        fnCombo();
        fnTapWindows();
        fnGlobeTap();
        fnLock();
        fnLatchedPlainKey();
        fnLatchedPressAgain();
        fnHoldLong();
        fnTimerStuck();
        fnReleasedFirst();
        fnPressedBeforeFn();
        fnTwoFingersSameKey();
        fnModifierBase();
        fnWithShift();
        fnCanceled();
        fnReleaseAll();
        fnNewConfig();
        fnUnlatch();
        fnUsedByMouse();
        fnBusyAtPress();
        fnTimerOutOfOrder();
        fnPathsAgree();
        fnRandomSequences();
        System.err.println("修饰键 / Caps / 合并键：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 修饰键 ═══════════

    static void modifierTap() {
        H h = new H();
        h.kd(SHIFT, 1000);
        h.ku(SHIFT, 1120);
        eq(h.r.keys, list("[02|]", "[00|]"), "单点 Shift：按下马上发、松开松开");
        check(h.r.clicks == 0 && h.r.lastL == 0 && !h.r.keyPending, "不锁、不震、定时器撤了");
        eq(h.r.mods, list("0/2/0", "0/0/0"), "界面：按着的时候知道（键帽换字），松开归零");
    }

    static void modifierLatch() {
        H h = new H();
        h.kd(SHIFT, 1000);
        check(h.r.keyPending && h.r.keyDue == 1400, "按下排 0.4 秒");
        h.to(1400);
        check(h.r.clicks == 1 && h.r.lastA == 2, "到 0.4 秒：震一下、快锁了");
        check(h.r.keyPending && h.r.keyDue == 2000, "再排到 1 秒");
        h.ku(SHIFT, 1600);
        eq(h.r.keys, list("[02|]"), "0.4–1 秒松手：不发松开（主机眼里 Shift 一直按着）");
        check(h.r.lastL == 2 && h.r.lastH == 0 && h.r.lastA == 0 && !h.r.keyPending, "锁住，定时器撤了");
        h.kd(A, 1700);
        h.ku(A, 1750);
        eq(h.r.keys, list("[02|]", "[02|04]", "[02|]", "[00|]"), "锁着点 a：先松键、再松修饰键（同以前）");
        check(h.r.lastL == 0, "打完一个普通键解开");
    }

    static void modifierHoldTooLong() {
        H h = new H();
        h.kd(SHIFT, 1000);
        h.to(2000);
        check(h.r.lastA == 0 && h.r.lastH == 2 && h.r.clicks == 1, "1 秒：高亮退回、不再震");
        check(!h.r.keyPending, "不再等");
        h.ku(SHIFT, 2500);
        eq(h.r.keys, list("[02|]", "[00|]"), "按住超过 1 秒松手：松开、不锁");
        check(h.r.lastL == 0, "不锁");
        // 定时器卡住、一直没到点：松手那一刻照截止时刻算（0.4 秒那次没震过，1 秒以后直接当超过 1 秒）
        H g = new H();
        g.kd(SHIFT, 1000);
        g.r.keyPending = false;   // 宿主的定时器"丢了"
        g.t.key(SHIFT, Proto.KEY_UP, 2300);
        check(g.r.lastL == 0 && g.r.clicks == 0 && g.lastKeys().equals("[00|]"), "定时器没到点、松手时已过 1 秒：不锁、不震");
        H f = new H();
        f.kd(SHIFT, 1000);
        f.r.keyPending = false;
        f.t.key(SHIFT, Proto.KEY_UP, 1500);
        check(f.r.lastL == 2 && f.r.clicks == 1, "定时器没到点、松手时 0.5 秒：照截止时刻补上（震一下、锁住）");
    }

    /** 按着时按任何别的键：普通键、自带修饰键的键、别的修饰键、Caps、合并键——不锁，已经快锁了的退回。 */
    static void modifierUsedByKeys() {
        for (int other : new int[]{A, AT, CTRL, CAPS, DUAL}) {
            H h = new H();
            h.kd(SHIFT, 1000);
            h.to(1400);
            check(h.r.lastA == 2, Integer.toHexString(other) + "：先快锁了");
            h.kd(other, 1450);
            check(h.r.lastA == 0, Integer.toHexString(other) + " 按下：快锁了的退回");
            h.ku(other, 1500);
            h.to(1700);
            h.ku(SHIFT, 1700);
            check(h.r.lastL == 0 && h.r.lastMods == 0, Integer.toHexString(other) + "：Shift 不锁，松手就松");
            h.to(5000);
            check(h.lastKeys().equals("[00|]") && h.r.lastL == 0, Integer.toHexString(other) + "：最后全松");
        }
        // 还没到 0.4 秒就按了别的键：之后按多久都不锁、不震
        H h = new H();
        h.kd(CTRL, 1000);
        h.kd(A, 1100);
        h.ku(A, 1150);
        h.to(1600);
        h.ku(CTRL, 1600);
        check(h.r.clicks == 0 && h.r.lastL == 0, "Ctrl+a 后按着到 0.6 秒：不震、不锁");
        eq(h.r.keys, list("[01|]", "[01|04]", "[01|]", "[00|]"), "Ctrl+a");
    }

    /** 鼠标键按下 / 松开（触控板轻点、三键条 / "左键"、CMD_CLICK）、滚轮、缩放、三指手势：都算用过。 */
    static void modifierUsedByMouse() {
        String[] what = {"触控板轻点", "点一下鼠标键（CMD_CLICK）", "滚轮（CMD_WHEEL）", "三指手势", "按住\"左键\"按钮", "实体键按住左键（CMD_HOLD_LEFT）"};
        for (int k = 0; k < what.length; k++) {
            H h = new H();
            h.kd(CTRL, 1000);
            switch (k) {
                case 0:
                    h.padTap(1, 1100);
                    break;
                case 1:
                    h.to(1100);
                    h.t.click(Proto.BUTTON_LEFT);
                    break;
                case 2:
                    h.to(1100);
                    h.t.wheel(1);
                    break;
                case 3:
                    h.to(1100);
                    h.t.gesture(3, GestureEngine.GESTURE_TAP);
                    break;
                case 4:
                    h.to(1100);
                    h.t.down(1, 1050, 50, 1100);
                    h.to(1200);
                    h.t.up(1, 1200);
                    break;
                default:
                    h.to(1100);
                    h.t.holdLeft(true);
                    h.to(1200);
                    h.t.holdLeft(false);
                    break;
            }
            h.to(1500);
            h.ku(CTRL, 1500);
            check(h.r.lastL == 0 && h.r.clicks == 0, what[k] + "：按着 Ctrl 时用了鼠标，不锁、不到点震");
            check(h.r.all.contains("h" + GestureEngine.HAPTIC_CLICK) == (k == 0), what[k] + "：只有触控板轻点自己报\"点了一下\"（HAPTIC_CLICK）");
        }
        // 双指开合（缩放 = Ctrl + 滚轮）
        H h = new H();
        h.kd(SHIFT, 1000);
        pinch(h, 1050);
        h.to(1500);
        h.ku(SHIFT, 1500);
        check(h.r.lastL == 0 && h.r.clicks == 0, "按着 Shift 双指缩放：不锁");
    }

    static void pinch(H h, long t0) {
        h.to(t0);
        h.t.down(3, 400, 500, t0);
        h.t.down(4, 600, 500, t0);
        h.t.frame(t0);
        for (int i = 1; i <= 8; i++) {
            h.to(t0 + i * 16);
            h.t.move(3, 400 - i * 10, 500);
            h.t.move(4, 600 + i * 10, 500);
            h.t.frame(t0 + i * 16);
        }
        h.to(t0 + 200);
        h.t.up(3, t0 + 200);
        h.t.up(4, t0 + 200);
        h.t.frame(t0 + 200);
    }

    /** 没按鼠标键的光标移动不算用过（另一只手指搭在触控板上抖一下就锁不上）。 */
    static void modifierPlainMoveStillLatches() {
        H h = new H();
        h.kd(CTRL, 1000);
        h.to(1050);
        h.t.down(1, 300, 300, 1050);
        for (int i = 1; i <= 10; i++) {
            h.to(1050 + i * 16);
            h.t.move(1, 300 + i * 8, 300);
            h.t.frame(1050 + i * 16);
        }
        h.to(1300);
        h.t.up(1, 1300);
        h.t.frame(1300);
        h.ku(CTRL, 1600);
        check(h.r.all.contains("m0") || true, "（移动光标）");
        check(h.r.lastL == 1, "按着 Ctrl 时只挪光标（没按鼠标键）：照锁");
    }

    static void latchedPressAgainUnlatches() {
        H h = new H();
        lock(h, SHIFT, 1000);
        int clicks = h.r.clicks;
        h.kd(SHIFT, 2000);
        eq(h.lastKeys(), "[02|]", "锁着时按下：不发（锁定换成按着）");
        check(h.r.lastL == 0 && h.r.lastH == 2, "高亮灭、画按下");
        h.to(3000);
        check(h.r.clicks == clicks && h.r.lastA == 0, "这一下按多久都不再快锁、不震");
        h.ku(SHIFT, 3000);
        eq(h.lastKeys(), "[00|]", "松开就松");
        check(h.r.lastL == 0, "解开了");
    }

    /** ⇧ 和功能行的 Shift 同一个用法码：第一根松开不出东西，最后一根按第一根的计时。 */
    static void sameUsageTwoFingers() {
        H h = new H();
        h.td(0, 0, 1000);    // ⇧
        h.td(1, 9, 1100);    // 功能行的 Shift
        h.to(1400);
        check(h.r.lastA == 2, "第二根不重新计时：照第一根 0.4 秒到点");
        h.tu(0, 1500, false);
        eq(h.r.keys, list("[02|]"), "第一根松开：什么都不出");
        check(h.r.lastH == 2 && h.r.lastL == 0, "还按着");
        h.tu(1, 1700, false);
        check(h.r.lastL == 2, "最后一根 0.7 秒（按第一根算）松开：锁住");
    }

    static void canceledArmedNoLatch() {
        H h = new H();
        h.td(0, 0, 1000);
        h.to(1500);
        h.tu(0, 1600, true);   // 手掌 / 收尾补报的抬起
        check(h.r.lastL == 0 && h.lastKeys().equals("[00|]"), "快锁了的被取消：松开、不锁");
        H g = new H();
        g.kd(SHIFT, 1000);
        g.to(1500);
        g.kc(SHIFT, 1600);
        check(g.r.lastL == 0 && g.lastKeys().equals("[00|]"), "界面那一路的 KEY_CANCEL 同样");
    }

    static void plainKeyUnlatches() {
        H h = new H();
        lock(h, SHIFT, 1000);
        lock(h, CTRL, 2000);
        check(h.r.lastL == 3, "一个一个锁：Shift、Ctrl 都锁着");
        h.kd(ALT, 3000);
        check(h.r.lastL == 3, "按下别的修饰键不解开锁定");
        h.kd(A, 3100);
        h.ku(A, 3150);
        check(h.r.lastL == 0 && h.r.lastH == 4 && h.r.lastMods == 4, "打完 a：锁着的解开，按着的 Alt 不松");
        h.ku(ALT, 3200);
        eq(h.lastKeys(), "[00|]", "最后全松");
    }

    static void unlatchOnlyLatched() {
        H h = new H();
        lock(h, SHIFT, 1000);
        h.kd(CTRL, 2000);
        h.to(2100);
        h.t.unlatch(2100);
        check(h.r.lastL == 0 && h.r.lastH == 1 && h.r.lastMods == 1, "CMD_UNLATCH：锁着的 Shift 解开，按着的 Ctrl 照旧按着");
        h.to(2400);
        check(h.r.lastA == 1, "按着的 Ctrl 照常计时（快锁了）");
        h.ku(CTRL, 2500);
        check(h.r.lastL == 1, "照常锁住");
        H g = new H();
        g.to(100);
        g.t.unlatch(100);
        check(g.r.keys.isEmpty() && g.r.mods.isEmpty(), "没锁着时 unlatch：什么都不发");
    }

    /** 单独按住 0.5 秒锁住（区域那一路用 key() 也一样）。 */
    static void lock(H h, int key, long t0) {
        h.kd(key, t0);
        h.to(t0 + 500);
        h.ku(key, t0 + 500);
    }

    // ═══════════ 审查补的 ═══════════

    static void reviewMouseBusy() {
        // 先长按拖动（触控板按住不动 450ms → 左键按下），再按 Ctrl 0.5 秒、放下、再松 Ctrl（资源管理器 Ctrl 拖动复制）
        H h = new H();
        h.to(1000);
        h.t.down(1, 500, 500, 1000);
        h.t.frame(1000);
        h.to(1500);
        check(h.r.mouseButtons == 1, "长按：左键按下、开始拖");
        h.kd(CTRL, 1600);
        h.to(2100);
        h.to(2200);
        h.t.up(1, 2200);
        h.t.frame(2200);
        h.ku(CTRL, 2300);
        check(h.r.lastL == 0 && h.r.clicks == 0, "拖着时按的 Ctrl：不锁、不到点震");
        check(h.r.keys.equals(list("[01|]", "[00|]")), "Ctrl 照常按下、松开");
        // 按着修饰键 0.3 秒时开始拖（"左键"按钮）、0.5 秒放下、0.7 秒松修饰键
        H g = new H();
        g.kd(SHIFT, 1000);
        g.to(1300);
        g.t.down(1, 1050, 50, 1300);
        g.to(1500);
        g.t.up(1, 1500);
        g.ku(SHIFT, 1700);
        check(g.r.lastL == 0 && g.r.clicks == 0, "按着 Shift 时拖了一下：不锁");
    }

    static void reviewKeyHeldFirst() {
        H h = new H();
        h.kd(A, 1000);
        h.kd(SHIFT, 1100);
        h.to(1600);
        h.ku(SHIFT, 1600);
        h.ku(A, 1700);
        check(h.r.lastL == 0 && h.r.clicks == 0, "先按住 a 再按 Shift 0.5 秒：不锁、不震");
        eq(h.r.keys, list("[00|04]", "[02|04]", "[00|04]", "[00|]"), "报文");
    }

    static void reviewTwoModifiers() {
        for (boolean shiftFirstUp : new boolean[]{true, false}) {
            H h = new H();
            h.kd(SHIFT, 1000);
            h.kd(CTRL, 1050);
            h.to(1600);
            if (shiftFirstUp) {
                h.ku(SHIFT, 1600);
                h.ku(CTRL, 1650);
                eq(h.r.keys, list("[02|]", "[03|]", "[01|]", "[00|]"), "Shift 先松");
            } else {
                h.ku(CTRL, 1600);
                h.ku(SHIFT, 1650);
                eq(h.r.keys, list("[02|]", "[03|]", "[02|]", "[00|]"), "Ctrl 先松");
            }
            check(h.r.lastL == 0 && h.r.clicks == 0, "Shift、Ctrl 一起按 0.5 秒（" + (shiftFirstUp ? "Shift" : "Ctrl") + " 先松）：两个都不锁、都不震");
        }
        H h = new H();
        lock(h, SHIFT, 1000);
        lock(h, CTRL, 2000);
        check(h.r.lastL == 3 && h.r.clicks == 2, "一个一个锁：两个都锁住（锁着的 Shift 不算按着）");
    }

    static void reviewShiftDualShiftFirst() {
        H h = new H();
        h.kd(SHIFT, 1000);
        h.kd(DUAL, 1100);
        h.ku(SHIFT, 1200);
        int before = h.r.keys.size();
        h.ku(DUAL, 1250);
        eq(h.r.keys, list("[02|]", "[02|36]", "[02|]", "[00|]"), "按住 Shift 点合并键、Shift 先抬起：抬起前先结算，出 \"<\"");
        check(h.r.keys.size() == before, "合并键后抬起：什么都不发");
    }

    static void reviewLatchedShiftRoll() {
        H h = new H();
        lock(h, SHIFT, 0);
        h.kd(I, 1000);
        h.kd(DUAL, 1050);
        h.ku(I, 1100);
        h.to(1450);
        h.ku(DUAL, 1600);
        eq(h.r.keys, list("[02|]", "[02|0c]", "[02|]", "[00|]", "[00|37]", "[00|]"), "锁着 Shift 快打 \"I.\"：锁定不为待定的键结算，出 \"I.\"");
    }

    static void reviewShiftCapsShiftFirst() {
        H h = new H();
        h.kd(SHIFT, 1000);
        h.kd(CAPS, 1100);
        h.ku(SHIFT, 1200);
        h.ku(CAPS, 1250);
        h.to(2000);
        eq(h.r.keys, list("[02|]", "[02|39]", "[00|39]", "[00|]"), "按住 Shift 点 Caps、Shift 先抬起：Shift+Caps，60ms 后松");
        check(h.r.capsSent == 1 && h.r.capsShown == 0, "显示翻一次（发了键）");
    }

    static void reviewZoomSettles() {
        H h = new H();
        h.kd(DUAL, 1000);
        pinch(h, 1020);
        int comma = h.r.keys.indexOf("[00|36]"), ctrl = h.r.keys.indexOf("[01|]");
        check(comma >= 0 && ctrl > comma, "缩放开始时待定的合并键先发出去、不带 Ctrl：" + h.r.keys);
        h.ku(DUAL, 1300);
        check(h.r.keys.lastIndexOf("[00|36]") == comma && !h.r.keys.contains("[00|37]"), "松手什么都不再发");
    }

    static void reviewAtKeepsShift() {
        // 按着 "@" 时点合并键并先松开合并键：照旧带着 Shift（"<"，和以前逗号按下就发时一样）
        H h = new H();
        h.kd(AT, 1000);
        h.kd(DUAL, 1050);
        h.ku(DUAL, 1100);
        h.ku(AT, 1150);
        eq(h.r.keys, list("[02|]", "[02|1f]", "[02|1f 36]", "[02|1f]", "[02|]", "[00|]"), "\"@\" 按着时点合并键：带 Shift");
        // "@" 松开不结算：之后合并键按住 0.4 秒照解开后的出（不带 Shift）
        H g = new H();
        g.kd(AT, 1000);
        g.kd(DUAL, 1050);
        g.ku(AT, 1100);
        g.to(1450);
        g.ku(DUAL, 1500);
        eq(g.r.keys, list("[02|]", "[02|1f]", "[02|]", "[00|]", "[00|37]", "[00|]"), "\"@\" 先松开不结算：合并键照常按住出句号");
    }

    // ═══════════ Caps ═══════════

    static void capsTap() {
        H h = new H();
        h.kd(CAPS, 1000);
        check(h.r.keys.isEmpty(), "Caps 按下先不发");
        h.ku(CAPS, 1100);
        eq(h.r.keys, list("[00|39]"), "点一下：松手时发 Caps Lock");
        check(h.r.capsSent == 1 && h.r.keyPending && h.r.keyDue == 1100 + KeyHolds.CAPS_TAP_MS, "显示翻一下，60ms 后松");
        h.to(1160);
        eq(h.r.keys, list("[00|39]", "[00|]"), "60ms 后松开");
        check(!h.r.keyPending && h.r.clicks == 0, "不震、定时器撤了");
    }

    static void capsHold() {
        H h = new H();
        h.kd(CAPS, 1000);
        h.to(1400);
        check(h.r.capsShown == 1 && h.r.capsSent == 0 && h.r.clicks == 1, "按住 0.4 秒：只翻显示、震一下");
        h.ku(CAPS, 3000);
        check(h.r.keys.isEmpty(), "一帧报文都没有");
        check(h.r.capsShown == 1 && !h.r.keyPending, "松手什么都不做");
    }

    static void capsRoll() {
        H h = new H();
        h.kd(CAPS, 1000);
        h.kd(A, 1050);
        h.ku(A, 1200);
        h.ku(CAPS, 1250);
        eq(h.r.keys, list("[00|39]", "[00|39 04]", "[00|04]", "[00|]"), "滚着打（Caps 没松就按 a）：先 Caps 再 a，60ms 松 Caps");
        check(h.r.capsSent == 1, "显示翻一次");
    }

    static void capsCanceled() {
        H h = new H();
        h.td(0, 7, 1000);
        h.tu(0, 1100, true);
        h.to(2000);
        check(h.r.keys.isEmpty() && h.r.capsSent == 0 && h.r.capsShown == 0, "被取消的 Caps：什么都没有");
    }

    /**
     * 被取消的修饰键松开不结算待定的 Caps / 合并键（审查发现）：收尾补报的抬起（TouchReader 的 finally 按 slot 先后 up(…, true)、
     * 面板 releaseTouches 按手指编号逐个 KEY_CANCEL）里修饰键那根排在前面时，以前会把待定的键当点一下发出去——凭空一次 Caps Lock、一个 "<"。
     * 两种先后、两条路（区域 / key()）、收尾后再 releaseAll（同 TouchReader：先补报抬起、再 releaseAll）。
     */
    static void canceledModifierNoSettle() {
        for (int pending : new int[]{CAPS, DUAL}) {
            for (boolean modFirst : new boolean[]{true, false}) {
                for (boolean region : new boolean[]{true, false}) {
                    H h = new H();
                    // 修饰键在 slot 0、待定的键在 slot 1（modFirst）；反过来时待定的在 slot 0
                    int modSlot = modFirst ? 0 : 1, pendSlot = modFirst ? 1 : 0;
                    if (region) {
                        h.td(modSlot, col(SHIFT), 1000);
                        h.td(pendSlot, col(pending), 1100);
                        h.tu(0, 1200, true);
                        h.tu(1, 1200, true);
                    } else {
                        h.kd(SHIFT, 1000);
                        h.kd(pending, 1100);
                        h.kc(modFirst ? SHIFT : pending, 1200);
                        h.kc(modFirst ? pending : SHIFT, 1200);
                    }
                    h.t.releaseAll();
                    h.to(3000);
                    boolean clean = h.r.keys.get(0).equals("[02|]");
                    for (int i = 1; i < h.r.keys.size(); i++) clean &= h.r.keys.get(i).equals("[00|]");
                    String what = (pending == CAPS ? "Caps" : "合并键") + "（" + (region ? "区域" : "key()") + "，"
                            + (modFirst ? "Shift 先" : "待定的先") + "被取消）";
                    check(clean && h.r.capsSent == 0 && h.r.capsShown == 0 && h.r.clicks == 0 && !h.r.keyPending,
                            "Shift + " + what + "、再 releaseAll：只有 Shift 按下 / 松开，不出 Caps / 逗号：" + h.r.keys);
                }
            }
        }
        // 只有 Shift 被取消（手掌压在 Shift 那根上）：待定的合并键留着，自己正常松开时照点一下出，不带已经松掉的 Shift
        H h = new H();
        h.td(0, col(SHIFT), 1000);
        h.td(1, col(DUAL), 1100);
        h.tu(0, 1200, true);
        h.tu(1, 1250, false);
        eq(h.r.keys, list("[02|]", "[00|]", "[00|36]", "[00|]"), "只有 Shift 被取消：合并键自己松开时出逗号（不带 Shift）");
        // 被取消的普通键照样松开（不然主机上一直连发）
        H g = new H();
        g.td(0, col(A), 1000);
        g.tu(0, 1100, true);
        eq(g.r.keys, list("[00|04]", "[00|]"), "区域那一路：被取消的 a 照样松开");
        H f = new H();
        f.kd(A, 1000);
        f.kc(A, 1100);
        eq(f.r.keys, list("[00|04]", "[00|]"), "key() 那一路：KEY_CANCEL 的 a 照样松开");
    }

    static void capsLatchedShift() {
        H h = new H();
        lock(h, SHIFT, 0);
        h.kd(CAPS, 1000);
        h.ku(CAPS, 1100);
        h.to(1200);
        eq(h.r.keys, list("[02|]", "[02|39]", "[02|]", "[00|]"), "锁着 Shift 点 Caps：60ms 那帧之后解开 Shift（同 Caps 当普通键）");
    }

    // ═══════════ 合并键 ═══════════

    static void dualTap() {
        H h = new H();
        h.kd(DUAL, 1000);
        check(h.r.keys.isEmpty(), "按下先不发");
        h.ku(DUAL, 1100);
        eq(h.r.keys, list("[00|36]", "[00|]"), "点一下：逗号");
        check(h.r.clicks == 0 && !h.r.keyPending, "不震");
    }

    static void dualHold() {
        H h = new H();
        h.kd(DUAL, 1000);
        h.to(1400);
        eq(h.r.keys, list("[00|37]", "[00|]"), "按住 0.4 秒：句号（按下松开两帧）");
        check(h.r.clicks == 1, "震一下");
        h.to(4000);
        h.ku(DUAL, 4000);
        eq(h.r.keys, list("[00|37]", "[00|]"), "按住 3 秒也只有一次、松手什么都不发");
    }

    static void dualRoll() {
        H h = new H();
        h.kd(DUAL, 1000);
        h.kd(SPACE, 1050);
        h.ku(DUAL, 1100);
        h.ku(SPACE, 1150);
        eq(h.r.keys, list("[00|36]", "[00|]", "[00|2c]", "[00|]"), "滚着打（合并键没松就按空格）：先逗号再空格");
        H g = new H();
        g.kd(DUAL, 1000);
        g.kd(SHIFT, 1050);
        g.ku(SHIFT, 1100);
        g.ku(DUAL, 1150);
        eq(g.r.keys, list("[00|36]", "[00|]", "[02|]", "[00|]"), "合并键没松就按 Shift：逗号先出、不带 Shift");
    }

    static void dualCanceledAndLatched() {
        H h = new H();
        h.td(0, 8, 1000);
        h.tu(0, 1100, true);
        h.to(2000);
        check(h.r.keys.isEmpty(), "被取消的合并键：什么都没有");
        H g = new H();
        lock(g, SHIFT, 0);
        g.kd(DUAL, 1000);
        g.ku(DUAL, 1100);
        eq(g.r.keys, list("[02|]", "[02|36]", "[02|]", "[00|]"), "锁着 Shift 点合并键：\"<\"，打完解开");
        H f = new H();
        f.td(0, 8, 1000);
        f.td(1, 8, 1100);   // 第二根手指按同一个合并键：不管
        f.tu(0, 1150, false);
        f.tu(1, 1200, false);
        eq(f.r.keys, list("[00|36]", "[00|]"), "两根手指按同一个合并键：只出一个逗号");
    }

    /** 合并键是普通的 REGION_KEY：按在它和空格之间的缝里、靠它那边，照"缝分给最近的键"分给它（nearestKey 不认 id 的大小）。 */
    static void dualInGap() {
        H h = new H();
        h.t.setRegions(new TouchRouter.Region[]{
                new TouchRouter.Region(Proto.REGION_KEY, DUAL, 0, 1000, 100, 1100),
                new TouchRouter.Region(Proto.REGION_KEY, SPACE, 110, 1000, 300, 1100),
                new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, 0, 990, 300, 1110),
                new TouchRouter.Region(Proto.REGION_PAD, 0, 0, 0, 1000, 900),
        });
        h.to(1000);
        h.t.down(0, 104, 1050, 1000);
        h.t.frame(1000);
        check(h.r.keys.isEmpty() && h.r.keyPending, "缝里靠合并键那边：按下了合并键（先不发、排 0.4 秒）");
        h.tu(0, 1100, false);
        eq(h.r.keys, list("[00|36]", "[00|]"), "松手：逗号");
        h.to(2000);
        h.t.down(0, 50, 995, 2000);   // 上边距
        h.t.frame(2000);
        h.to(2500);
        h.tu(0, 2600, false);
        eq(h.r.keys, list("[00|36]", "[00|]", "[00|37]", "[00|]"), "上边距分给合并键，按住 0.4 秒：句号");
    }

    // ═══════════ 计数、定时器 ═══════════

    static void countsAfterReleaseAll() {
        H h = new H();
        h.kd(SHIFT, 1000);
        h.kd(A, 1100);
        h.to(1200);
        h.t.releaseAll();
        eq(h.lastKeys(), "[00|]", "releaseAll：一帧干净的报文");
        check(h.r.lastL == 0 && h.r.lastH == 0 && h.r.lastA == 0 && !h.r.keyPending, "三样归零、定时器撤了");
        lock(h, CTRL, 2000);
        int n = h.r.keys.size();
        h.ku(SHIFT, 3000);
        h.ku(A, 3050);
        check(h.r.keys.size() == n && h.r.lastL == 1, "releaseAll 之后迟到的松开（修饰键、普通键）：不出东西、不解开新的锁定");
        // 待定的 Caps / 合并键：releaseAll 丢掉，迟到的松开也不发（两个分开测：一起按的话后按的那个先把前一个结算掉）
        for (int k : new int[]{CAPS, DUAL}) {
            H g = new H();
            g.kd(k, 1000);
            g.to(1100);
            g.t.releaseAll();
            g.ku(k, 1200);
            g.to(3000);
            boolean clean = true;
            for (String r : g.r.keys) clean &= r.equals("[00|]");   // releaseAll 照旧发一帧干净的报文
            check(g.r.capsSent == 0 && g.r.capsShown == 0 && clean && g.r.clicks == 0 && !g.r.keyPending,
                    Integer.toHexString(k) + "：releaseAll 丢掉待定的，迟到的松开不发、不到点");
        }
        // Caps 的 60ms 还没到就 releaseAll：一帧干净的报文里松掉
        H f = new H();
        f.kd(CAPS, 1000);
        f.ku(CAPS, 1050);
        f.to(1070);
        f.t.releaseAll();
        f.to(2000);
        eq(f.r.keys, list("[00|39]", "[00|]"), "60ms 里 releaseAll：Caps 在干净的报文里松掉，之后不再发");
    }

    static void spaceCopies() {
        H h = new H();
        h.td(0, 10, 1000);
        h.td(1, 11, 1050);
        h.tu(0, 1100, false);
        h.tu(1, 1150, false);
        eq(h.r.keys, list("[00|2c]", "[00|]"), "两个空格副本：先抬起的那根就松开（同以前）");
    }

    static void timerOnlyWhilePending() {
        H h = new H();
        h.kd(A, 1000);
        check(!h.r.keyPending, "普通键：不排定时器");
        h.ku(A, 1050);
        h.kd(SHIFT, 1100);
        h.kd(CAPS, 1200);   // Shift 用过了：不再等它；Caps 待定，排 1600
        check(h.r.keyPending && h.r.keyDue <= 1600, "最早的截止时刻（排着的更早就不动）：" + h.r.keyDue);
        h.to(1500);
        check(h.r.keyPending && h.r.keyDue == 1600, "早到的那次什么都不做、按剩下最早的重排到 1600：" + h.r.keyDue);
        h.ku(CAPS, 1550);
        check(h.r.keyPending && h.r.keyDue == 1610, "Caps 点一下：排 60ms 的松开");
        h.to(1700);
        h.ku(SHIFT, 1700);
        check(!h.r.keyPending, "全松了：定时器撤了");
    }

    static void timerOutOfOrder() {
        H h = new H();
        h.kd(SHIFT, 1000);
        int n = h.r.all.size();
        h.t.onKeyTimer(1100);   // 早到
        h.t.onKeyTimer(1100);   // 重复
        check(h.r.all.size() == n, "早到、重复的回调：什么都不出");
        h.ku(SHIFT, 1200);
        n = h.r.all.size();
        h.t.onKeyTimer(1400);   // 撤晚了的那次
        h.t.onKeyTimer(2000);
        h.t.onKeyTimer(500);    // 时间倒着来的
        check(h.r.all.size() == n && h.r.lastL == 0, "撤掉之后到点的、乱序的：什么都不出、不锁");
        H g = new H();
        g.t.onKeyTimer(100);   // 从来没排过
        check(g.r.all.isEmpty(), "没排着时到点：什么都不出");
    }

    /** 区域那一路（触摸）和 key() 那一路（界面发来的）同样的序列出一模一样的报文、状态、震动。 */
    static void regionAndKeyPathsAgree() {
        int[][] seq = {   // {列, 按下时间, 抬起时间, 取消}
                {0, 1000, 1600, 0}, {3, 1700, 1750, 0}, {7, 1800, 1900, 0}, {8, 2000, 2500, 0}, {1, 2600, 2700, 0},
                {7, 2800, 3300, 0}, {8, 3400, 3450, 1}, {0, 3500, 3550, 0},
        };
        int[] ids = {SHIFT, CTRL, ALT, A, B, I, AT, CAPS, DUAL, SHIFT, SPACE, SPACE};
        H a = new H(), b = new H();
        for (int[] s : seq) {
            a.td(0, s[0], s[1]);
            b.kd(ids[s[0]], s[1]);
            a.tu(0, s[2], s[3] == 1);
            if (s[3] == 1) b.kc(ids[s[0]], s[2]);
            else b.ku(ids[s[0]], s[2]);
        }
        a.to(5000);
        b.to(5000);
        eq(a.r.keys, b.r.keys, "两条路的报文一样");
        eq(a.r.mods, b.r.mods, "两条路报给界面的修饰键状态一样");
        check(a.r.clicks == b.r.clicks && a.r.capsSent == b.r.capsSent && a.r.capsShown == b.r.capsShown, "震动、Caps 一样");
    }

    /** 换参数（setConfig）时按着的修饰键 / 键照旧按着，抬起照常判（以前清掉 slot、不松 HID）。 */
    static void newConfigKeepsKeys() {
        H h = new H();
        h.td(0, 0, 1000);      // ⇧
        h.td(1, 3, 1050);      // a
        h.to(1100);
        h.t.setConfig(new GestureEngine.Config(), 1);
        eq(h.lastKeys(), "[02|04]", "换参数：按着的照旧按着");
        h.tu(1, 1200, false);
        h.tu(0, 1300, false);
        eq(h.lastKeys(), "[00|]", "抬起照常松开");
        H g = new H();
        g.td(0, 0, 1000);
        g.to(1100);
        g.t.setConfig(new GestureEngine.Config(), 1);
        g.to(1400);
        g.tu(0, 1500, false);
        check(g.r.lastL == 2, "换参数前按下的 Shift：照常按住 0.5 秒锁住");
    }

    // ═══════════ 随机序列 ═══════════

    /**
     * 几百组随机序列（修饰键 / 字母 / "@" / Caps / 合并键的按下抬起、被取消、两根手指按同一个 Shift、点鼠标、滚轮、乱序的定时器、releaseAll、unlatch），每步查：
     * armed ⊆ held、latched ∩ held = ∅；HID 修饰键字节 = latched | held | 按着的 "@" 带的 Shift；
     * 有别的键 / 鼠标按着时按下、按着期间用过的修饰键松开时绝不进 latched，没用过、按了 0.4–1 秒的才进；
     * 震动次数 = 快锁了的次数 + Caps 按住的次数 + 合并键按住的次数；全部抬起后（releaseAll 之前）主机上没有按着的键、修饰键字节 = 锁住的；
     * 再 releaseAll：报文全零、三样全零、定时器撤了。
     */
    static void randomSequences() {
        int[] keys = {SHIFT, CTRL, RSHIFT, A, B, AT, CAPS, DUAL};
        int steps = 0, latchedSeen = 0, bad = 0;
        String firstBad = null;
        for (int seed = 1; seed <= 400; seed++) {
            Random rnd = new Random(seed);
            H h = new H();
            long t = 1000;
            Map<Integer, Integer> count = new HashMap<>();
            long[] pressAt = new long[8];
            boolean[] tainted = new boolean[8];
            int holdsShown, periods = 0;
            for (int step = 0; step < 120; step++) {
                steps++;
                t += rnd.nextInt(5) == 0 ? 300 + rnd.nextInt(900) : rnd.nextInt(120);
                int op = rnd.nextInt(23);
                int key = keys[rnd.nextInt(keys.length)];
                int n = count.getOrDefault(key, 0);
                String why = null;
                if (op >= 20) {   // 想锁一个修饰键：别的都没按着时单独按住一会儿（0.1–1.5 秒）再松，大约一半落在 0.4–1 秒
                    boolean empty = true;
                    for (int v : count.values()) empty &= v == 0;
                    int mod = keys[rnd.nextInt(3)];
                    if (!empty) continue;
                    int b = mod - 0xE0;
                    h.to(t);
                    int latchedBefore = h.r.lastL;
                    h.kd(mod, t);
                    long d = 100 + rnd.nextInt(1400);
                    t += d;
                    h.ku(mod, t);
                    boolean want = (latchedBefore & (1 << b)) == 0 && d >= KeyHolds.HOLD_MS && d < KeyHolds.LOCK_MAX_MS;
                    boolean got = (h.r.lastL & (1 << b)) != 0;
                    if (want != got) why = "单独按住修饰键 " + Integer.toHexString(mod) + " " + d + "ms 松开：想要 " + (want ? "锁" : "不锁") + "，实际" + (got ? "锁了" : "没锁");
                    if (got) latchedSeen++;
                } else if (op < 9) {   // 按下
                    if (n >= 2) continue;
                    boolean others = false;
                    for (Map.Entry<Integer, Integer> e : count.entrySet()) others |= e.getKey() != key && e.getValue() > 0;
                    h.to(t);
                    int latchedBefore = h.r.lastL;
                    if (op < 6) h.kd(key, t);
                    else h.td(n, col(key), t);   // 区域那一路：手指编号 = 第几根
                    // 别的修饰键按着的都标用过
                    for (int b = 0; b < 8; b++) {
                        if (0xE0 + b != key && count.getOrDefault(0xE0 + b, 0) > 0) tainted[b] = true;
                    }
                    if (KeyHolds.isModifier(key) && n == 0) {
                        int b = key - 0xE0;
                        pressAt[b] = t;
                        tainted[b] = others || (latchedBefore & (1 << b)) != 0;
                    }
                    count.put(key, n + 1);
                } else if (op < 17) {   // 抬起（有时被取消）
                    if (n == 0) continue;
                    boolean cancel = rnd.nextInt(8) == 0;
                    h.to(t);
                    // 区域那一路按下的手指要按区域抬起：两条路混着用时只用 key()，同一个键的计数在 KeyHolds 里是一份
                    if (cancel) h.kc(key, t);
                    else h.ku(key, t);
                    count.put(key, n - 1);
                    if (KeyHolds.isModifier(key) && n == 1) {
                        int b = key - 0xE0;
                        long d = t - pressAt[b];
                        boolean want = !cancel && !tainted[b] && d >= KeyHolds.HOLD_MS && d < KeyHolds.LOCK_MAX_MS;
                        boolean got = (h.r.lastL & (1 << b)) != 0;
                        if (want != got) why = "修饰键 " + Integer.toHexString(key) + " 按了 " + d + "ms" + (tainted[b] ? "（用过）" : "")
                                + (cancel ? "（被取消）" : "") + " 松开：想要 " + (want ? "锁" : "不锁") + "，实际" + (got ? "锁了" : "没锁");
                        if (got) latchedSeen++;
                    }
                } else if (op == 17) {   // 鼠标：点一下 / 滚一格
                    h.to(t);
                    if (rnd.nextBoolean()) h.t.click(Proto.BUTTON_LEFT);
                    else h.t.wheel(1);
                    for (int b = 0; b < 8; b++) if (count.getOrDefault(0xE0 + b, 0) > 0) tainted[b] = true;
                } else if (op == 18) {   // 乱序的定时器回调
                    h.t.onKeyTimer(t - rnd.nextInt(2000));
                } else {
                    h.to(t);
                    if (rnd.nextBoolean()) {
                        h.t.unlatch(t);
                    } else {
                        h.t.releaseAll();
                        count.clear();
                    }
                }
                // 不变量
                R r = h.r;
                if (why == null && (r.lastA & ~r.lastH) != 0) why = "armed 不在 held 里";
                if (why == null && (r.lastL & r.lastH) != 0) why = "latched 和 held 重叠";
                int chord = count.getOrDefault(AT, 0) > 0 ? Hid.MOD_SHIFT : 0;
                if (why == null && !r.keys.isEmpty() && r.lastMods != (r.lastL | r.lastH | chord)) {
                    why = "HID 修饰键字节 " + Integer.toHexString(r.lastMods) + " ≠ latched | held | chord " + Integer.toHexString(r.lastL | r.lastH | chord);
                }
                if (why != null) {
                    bad++;
                    if (firstBad == null) firstBad = "种子 " + seed + " 第 " + step + " 步：" + why;
                    break;
                }
            }
            // 收尾：全部抬起、走到很久以后、releaseAll
            for (Map.Entry<Integer, Integer> e : new HashMap<>(count).entrySet()) {
                for (int i = 0; i < e.getValue(); i++) h.ku(e.getKey(), t += 10);
            }
            h.to(t += 5000);
            // 全部抬起以后、releaseAll 之前：主机上没有按着的键，修饰键字节 = 锁住的（每个发出去的按下都配了松开，含被取消的普通键）。
            // 要在 releaseAll 前面查：它一定发一帧干净的报文，放后面什么都查不出来
            String settled = h.r.lastKeys(), want = String.format("[%02x|]", h.r.lastL);
            if (!h.r.keys.isEmpty() && !settled.equals(want)) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：全部抬起后（releaseAll 之前）主机上还按着 " + settled + "，想要 " + want;
            }
            h.t.releaseAll();
            R r = h.r;
            holdsShown = r.capsShown;
            for (String s : r.keys) if (s.matches(".*[| ]37[] ].*")) periods++;   // 按着的键里有 0x37（句号）的那一帧
            boolean ok = r.lastKeys().isEmpty() || r.lastKeys().equals("[00|]");
            ok &= r.lastL == 0 && r.lastH == 0 && r.lastA == 0 && !r.keyPending;
            if (!ok && firstBad == null) firstBad = "种子 " + seed + "：收尾后没归零 " + r.lastKeys() + " " + r.lastL + "/" + r.lastH + "/" + r.lastA;
            if (!ok) bad++;
            if (r.clicks != r.armedRises + holdsShown + periods) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：震了 " + r.clicks + " 次，快锁了 " + r.armedRises + " + Caps 按住 " + holdsShown + " + 句号 " + periods;
            }
        }
        check(bad == 0, "随机序列：" + (firstBad == null ? "" : firstBad));
        check(latchedSeen > 50, "随机序列里真锁住过（不是一直不锁）：" + latchedSeen);
        System.err.println("  修饰键随机序列：" + steps + " 步，锁住 " + latchedSeen + " 次");
    }

    // ═══════════ Fn（第 3 批，协议 21：实体键盘 75% / 65% / 苹果紧凑） ═══════════

    static final int FN = Proto.FN_KEY, FNG = Proto.FN_GLOBE_KEY, PGUP = 0x4B,
            UP = Proto.fnLayerKey(0x52, 0x4B), ONE = Proto.fnLayerKey(0x1E, 0x3A), RCTL = Proto.fnLayerKey(0xE4, 0x65);
    static final int FN_HELD = Proto.FN_HELD, FN_LATCHED = Proto.FN_LATCHED, FN_ARMED = Proto.FN_ARMED, FN_LOCKED = Proto.FN_LOCKED;

    /**
     * Fn 的屏幕：下面一排 Fn（Windows 模式）、fn🌐（Mac 模式）、↑（Fn 层 PgUp）、1（Fn 层 F1）、右 Ctrl（Fn 层 Menu，底层是修饰键）、Shift、a、
     * PgUp（底层就发 0x4B，和 Fn+↑ 同一个码）、Caps；右上一个"左键"按钮、触控板。
     */
    static final TouchRouter.Region[] FN_REGIONS = {
            key(FN, 0), key(FNG, 1), key(UP, 2), key(ONE, 3), key(RCTL, 4), key(SHIFT, 5), key(A, 6), key(PGUP, 7), key(CAPS, 8),
            new TouchRouter.Region(Proto.REGION_HOLD_LEFT, 3, 1000, 0, 1100, 100),
            new TouchRouter.Region(Proto.REGION_PAD, 0, 0, 0, 1000, 1000),
    };

    /** 键在这份区域里第几列。 */
    static int col(TouchRouter.Region[] rs, int key) {
        for (int i = 0; i < rs.length; i++) if (rs[i].type == Proto.REGION_KEY && rs[i].id == key) return i;
        throw new IllegalArgumentException("屏幕上没有这个键：" + Integer.toHexString(key));
    }

    /** Fn 的编号：🌐 的按钮编号同 KeyRows（App 的 🌐 键），Fn 层的码 / 底层取得回来。 */
    static void fnIds() {
        check(Proto.GLOBE_BUTTON_ID == KeyRows.GLOBE_BUTTON_ID && Proto.GLOBE_BUTTON_ID == FixedLayout.GLOBE_ID,
                "fn🌐 单点报的按钮编号就是 🌐 键的（App 走同一条路）");
        check(Proto.fnBase(UP) == 0x52 && Proto.fnCode(UP) == 0x4B && Proto.fnBase(RCTL) == 0xE4 && Proto.fnCode(RCTL) == 0x65,
                "带 Fn 层的键：底层、Fn 层的码取得回来");
        check(Proto.isFnKey(FN) && Proto.isFnKey(FNG) && !Proto.isFnLayerKey(FN) && !Proto.isFnLayerKey(FNG) && Proto.isFnLayerKey(UP)
                && !Proto.isFnKey(UP) && !Proto.isDualKey(FN) && !Proto.isDualKey(UP) && !KeyHolds.isTimed(UP) && !KeyHolds.isModifier(RCTL),
                "Fn 键、带 Fn 层的键各认各的，不是合并键、不是修饰键（底层是修饰键的要按下时才定）");
    }

    /** 按住 Fn 再按别的键：那个键出 Fn 层的码、松手就回底层；没有 Fn 层的照旧；用过的 Fn 松手不锁、不出 🌐。 */
    static void fnCombo() {
        H h = new H(FN_REGIONS);
        h.kd(FN, 1000);
        eq(h.r.mods, list("0/0/0/fn2"), "Fn 按下：报界面 Fn 按着（键帽换字）");
        check(h.r.keys.isEmpty(), "Fn 不发 HID");
        h.kd(UP, 1100);
        h.ku(UP, 1150);
        h.kd(ONE, 1200);
        h.ku(ONE, 1250);
        h.kd(A, 1300);
        h.ku(A, 1350);
        h.to(1600);
        check(h.r.clicks == 0 && h.r.lastF == FN_HELD, "按了别的键的 Fn：到 0.4 秒不震、不快锁了");
        h.ku(FN, 1600);
        eq(h.r.keys, list("[00|4b]", "[00|]", "[00|3a]", "[00|]", "[00|04]", "[00|]"), "按住 Fn：↑ 出 PgUp、1 出 F1、a 照旧出 a");
        check(h.r.lastF == 0 && h.r.globes == 0 && !h.r.keyPending, "用过的 Fn 松开：不锁、不出 🌐、定时器撤了");
        h.kd(UP, 1700);
        h.ku(UP, 1750);
        eq(h.r.keys.subList(6, 8), list("[00|52]", "[00|]"), "松开 Fn 以后 ↑ 回到 ↑");
        // fn🌐 一样（Mac 模式），组合时不出 🌐
        H g = new H(FN_REGIONS);
        g.kd(FNG, 1000);
        g.kd(UP, 1050);
        g.ku(UP, 1100);
        g.ku(FNG, 1150);
        eq(g.r.keys, list("[00|4b]", "[00|]"), "fn🌐 + ↑ = PgUp");
        check(g.r.globes == 0, "fn🌐 组合过：松开不出 🌐");
    }

    /**
     * Windows 模式单点 Fn（协议 22）：单次锁——不发 HID、不震；下一个普通键出 Fn 层、它松开时解开；再下一个回到底层。
     * 界面：按着时 Fn 层显示，松开后单次锁（FN_LATCHED）照样显示 Fn 层。
     */
    static void fnTapWindows() {
        H h = new H(FN_REGIONS);
        h.kd(FN, 1000);
        h.ku(FN, 1150);
        check(h.r.keys.isEmpty() && h.r.globes == 0 && h.r.lastF == FN_LATCHED && h.r.clicks == 0 && !h.r.keyPending,
                "单点 Fn：单次锁，不发 HID、不出 🌐、不震、定时器撤了");
        eq(h.r.mods, list("0/0/0/fn2", "0/0/0/fn1"), "界面：按着时 Fn 层显示，松开变单次锁");
        h.kd(UP, 1300);
        check(h.r.lastF == FN_LATCHED && h.lastKeys().equals("[00|4b]"), "单次锁着按 ↑：出 PgUp，按着时照旧锁着");
        h.ku(UP, 1350);
        check(h.r.lastF == 0, "↑ 松开：单次锁解开");
        h.kd(UP, 1400);
        h.ku(UP, 1450);
        eq(h.r.keys, list("[00|4b]", "[00|]", "[00|52]", "[00|]"), "单次锁只管下一个键：PgUp 之后 ↑ 回到 ↑");
        // 0.4 秒差一点（399ms）：照旧是单次锁
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.ku(FN, 1399);
        check(g.r.lastF == FN_LATCHED && g.r.clicks == 0, "按 399ms 松手：单次锁、不震");
    }

    /**
     * Mac 模式 fn🌐：单点 = 🌐（报 🌐 的点击，没有单次锁）；单独按住 0.4 秒起（多久都算）= 永久锁、不出 🌐；组合、被取消、按下时别的键按着都不出 🌐。
     * 区域那一路一样。
     */
    static void fnGlobeTap() {
        H h = new H(FN_REGIONS);
        h.kd(FNG, 1000);
        h.ku(FNG, 1200);
        check(h.r.globes == 1 && h.r.keys.isEmpty() && h.r.lastF == 0 && h.r.clicks == 0, "单点 fn🌐：出 🌐、不发 HID、不锁（没有单次锁）、不震");
        H g = new H(FN_REGIONS);
        g.kd(FNG, 1000);
        g.to(1400);
        check(g.r.clicks == 1 && g.r.lastF == (FN_HELD | FN_ARMED), "fn🌐 按住 0.4 秒：震一下、快永久锁了");
        check(!g.r.keyPending, "到点以后不再等（没有 1 秒的上限）");
        g.ku(FNG, 1600);
        check(g.r.globes == 0 && g.r.lastF == FN_LOCKED, "0.6 秒松手：永久锁、不出 🌐");
        H f = new H(FN_REGIONS);
        f.kd(FNG, 1000);
        f.to(3000);
        check(f.r.lastF == (FN_HELD | FN_ARMED) && f.r.clicks == 1, "按住 2 秒：一直是快永久锁了（不退回）、只震一次");
        f.ku(FNG, 3200);
        check(f.r.globes == 0 && f.r.lastF == FN_LOCKED, "按住超过 1 秒松手：照样永久锁、不出 🌐");
        H e = new H(FN_REGIONS);
        e.kd(FNG, 1000);
        e.kc(FNG, 1100);
        check(e.r.globes == 0 && e.r.lastF == 0, "被取消的单点：不出 🌐");
        H d = new H(FN_REGIONS);
        d.kd(A, 1000);
        d.kd(FNG, 1050);
        d.ku(FNG, 1100);
        d.ku(A, 1150);
        check(d.r.globes == 0, "按下时已经有别的键按着：组合的一部分，不出 🌐");
        H c = new H(FN_REGIONS);
        c.td(0, col(FN_REGIONS, FNG), 1000);
        c.tu(0, 1150, false);
        check(c.r.globes == 1 && c.r.all.contains("globe" + Proto.BUTTON_TAP), "区域那一路单点 fn🌐：同样出 🌐 的点击");
        H b = new H(FN_REGIONS);
        b.td(0, col(FN_REGIONS, FNG), 1000);
        b.tu(0, 1150, true);
        check(b.r.globes == 0, "区域那一路被取消的抬起（手掌 / 收尾补报的）：不出 🌐");
    }

    /** 单独按住 Fn 0.4 秒起松手：永久锁（Fn Sticky）——之后每个带 Fn 层的键都出 Fn 层、打多少个键都不解开，再单独点一下 Fn 才解开。 */
    static void fnLock() {
        H h = new H(FN_REGIONS);
        h.kd(FN, 1000);
        check(h.r.keyPending && h.r.keyDue == 1400, "Fn 按下排 0.4 秒");
        h.to(1400);
        check(h.r.clicks == 1 && h.r.lastF == (FN_HELD | FN_ARMED), "到 0.4 秒：震一下、快永久锁了");
        check(!h.r.keyPending, "Fn 快锁了以后不再等（修饰键还要排到 1 秒，Fn 没有上限）");
        h.ku(FN, 1600);
        check(h.r.lastF == FN_LOCKED && !h.r.keyPending && h.r.keys.isEmpty(), "松手：永久锁，不发 HID");
        h.kd(UP, 1700);
        h.ku(UP, 1750);
        h.kd(ONE, 1800);
        h.ku(ONE, 1850);
        h.kd(A, 1900);
        h.ku(A, 1950);
        h.kd(UP, 2000);
        h.ku(UP, 2050);
        check(h.r.lastF == FN_LOCKED, "打了好几个键（有没有 Fn 层的都算）：永久锁照旧");
        eq(h.r.keys, list("[00|4b]", "[00|]", "[00|3a]", "[00|]", "[00|04]", "[00|]", "[00|4b]", "[00|]"), "永久锁着：↑ 一直出 PgUp、1 出 F1，a 照旧");
        h.kd(FN, 2200);
        check(h.r.lastF == (FN_LOCKED | FN_HELD), "锁着时按下 Fn：先不解开（可能当组合用），按着");
        h.ku(FN, 2300);
        check(h.r.lastF == 0 && h.r.clicks == 1, "单独按了一下：松手解开，不震");
        h.kd(UP, 2400);
        h.ku(UP, 2450);
        eq(h.lastKeys(), "[00|]", "收尾");
        eq(h.r.keys.get(h.r.keys.size() - 2), "[00|52]", "解开以后 ↑ 回到 ↑");
    }

    /** 单次锁着时打了没有 Fn 层的键：照样用掉（同锁住的修饰键"打完下一个普通键解开"）；修饰键按下 / 松开不用掉它（单次锁 → Shift → 1 = Shift+F1）。 */
    static void fnLatchedPlainKey() {
        H h = new H(FN_REGIONS);
        tap(h, FN, 1000);
        check(h.r.lastF == FN_LATCHED, "单次锁");
        h.kd(A, 1600);
        h.ku(A, 1650);
        check(h.r.lastF == 0, "打完 a：解开");
        h.kd(UP, 1700);
        h.ku(UP, 1750);
        eq(h.r.keys, list("[00|04]", "[00|]", "[00|52]", "[00|]"), "a 照旧、↑ 回到 ↑");
        // 单次锁着时按修饰键不解开（同修饰键的锁定）：Shift 按着再按 1 = Shift+F1
        H g = new H(FN_REGIONS);
        tap(g, FN, 1000);
        g.kd(SHIFT, 1600);
        g.kd(ONE, 1650);
        g.ku(ONE, 1700);
        g.ku(SHIFT, 1750);
        eq(g.r.keys, list("[02|]", "[02|3a]", "[02|]", "[00|]"), "单次锁着 Fn、按着 Shift 打 1：Shift+F1");
        check(g.r.lastF == 0 && g.r.lastL == 0, "打完 F1 解开 Fn，Shift 用过不锁");
        // 单次锁 → 单点 Shift（松开）→ 1：Shift 点过一下、不用掉单次锁，1 照样出 F1
        H f = new H(FN_REGIONS);
        tap(f, FN, 1000);
        f.kd(SHIFT, 1600);
        f.ku(SHIFT, 1650);
        check(f.r.lastF == FN_LATCHED, "单点 Shift：单次锁还在");
        f.kd(ONE, 1700);
        f.ku(ONE, 1750);
        eq(f.r.keys, list("[02|]", "[00|]", "[00|3a]", "[00|]"), "之后 1 出 F1");
        // 单次锁着时按右 Ctrl（底层是修饰键、Fn 层是 Menu）：出 Menu，是普通键，用掉单次锁
        H e = new H(FN_REGIONS);
        tap(e, FN, 1000);
        e.kd(RCTL, 1600);
        e.ku(RCTL, 1650);
        eq(e.r.keys, list("[00|65]", "[00|]"), "单次锁着按右 Ctrl：出 Menu");
        check(e.r.lastF == 0 && e.r.lastL == 0, "Menu 是普通键：用掉单次锁，修饰键不动");
    }

    /**
     * 锁着（单次或永久）时再单独按一下 Fn：松手解开（这一下按着时照样是 Fn 层），按多久都不再锁、不震（fn🌐 也不出 🌐）；
     * 锁着时把 Fn 当组合用（按住它打了别的键 / 动了鼠标）：Fn 松手后锁照旧——永久锁一直在，单次锁被那个键用掉。
     */
    static void fnLatchedPressAgain() {
        // 单次锁 + 再点一下：解开
        H h = new H(FN_REGIONS);
        tap(h, FN, 1000);
        h.kd(FN, 1600);
        check(h.r.lastF == (FN_LATCHED | FN_HELD), "单次锁着按下 Fn：按着、单次锁先留着");
        h.ku(FN, 1700);
        check(h.r.lastF == 0, "单独点一下：解开（不会又锁一次）");
        h.kd(UP, 1800);
        h.ku(UP, 1850);
        eq(h.r.keys, list("[00|52]", "[00|]"), "之后 ↑ 是 ↑");
        // 单次锁 + 再按住 2 秒：照样只是解开，不震、不变永久锁
        H g = new H(FN_REGIONS);
        tap(g, FN, 1000);
        g.kd(FN, 1600);
        g.to(3600);
        check(g.r.clicks == 0 && g.r.lastF == (FN_LATCHED | FN_HELD) && !g.r.keyPending, "锁着时按住 2 秒：不震、不快锁了、不排定时器");
        g.ku(FN, 3600);
        check(g.r.lastF == 0, "松手解开");
        // 永久锁 + 再按住 0.6 秒：解开
        H f = new H(FN_REGIONS);
        lock(f, FN, 1000);
        f.kd(FN, 1600);
        f.to(2200);
        f.ku(FN, 2200);
        check(f.r.lastF == 0 && f.r.clicks == 1, "永久锁着再按住 0.6 秒：解开，不再震（只有锁那一下震过）");
        // fn🌐 永久锁 + 再点一下：解开，不出 🌐
        H e = new H(FN_REGIONS);
        lock(e, FNG, 1000);
        e.kd(FNG, 1600);
        e.ku(FNG, 1700);
        check(e.r.lastF == 0 && e.r.globes == 0, "锁着时再点一下 fn🌐：解开，不出 🌐");
        // 永久锁 + Fn 当组合用（按住 Fn 打 ↑）：PgUp，松开 Fn 后永久锁照旧
        H d = new H(FN_REGIONS);
        lock(d, FN, 1000);
        d.kd(FN, 1600);
        d.kd(UP, 1650);
        d.ku(UP, 1700);
        d.ku(FN, 1750);
        check(d.r.lastF == FN_LOCKED, "永久锁着按住 Fn 打了 ↑：松开 Fn 后锁照旧");
        d.kd(UP, 1800);
        d.ku(UP, 1850);
        eq(d.r.keys, list("[00|4b]", "[00|]", "[00|4b]", "[00|]"), "两次都出 PgUp");
        // 永久锁 + 按住 Fn 时点了鼠标：组合，锁照旧
        H c = new H(FN_REGIONS);
        lock(c, FN, 1000);
        c.kd(FN, 1600);
        c.to(1650);
        c.t.click(Proto.BUTTON_LEFT);
        c.ku(FN, 1700);
        check(c.r.lastF == FN_LOCKED, "永久锁着按住 Fn 点了鼠标：松开 Fn 后锁照旧");
        // 单次锁 + Fn 当组合用：那个键用掉单次锁（同"下一个普通键"），Fn 松开不另外改
        H b = new H(FN_REGIONS);
        tap(b, FN, 1000);
        b.kd(FN, 1600);
        b.kd(ONE, 1650);
        check(b.r.lastF == (FN_LATCHED | FN_HELD), "单次锁着按住 Fn 打 1：按着时单次锁还在");
        b.ku(ONE, 1700);
        b.ku(FN, 1750);
        check(b.r.lastF == 0, "1 松开用掉单次锁、Fn 松开不再锁");
        eq(b.r.keys, list("[00|3a]", "[00|]"), "出 F1");
        // 锁着时按下 Fn 那一刻别的键按着：组合的一部分，不解开
        H a = new H(FN_REGIONS);
        lock(a, FN, 1000);
        a.kd(A, 1600);
        a.kd(FN, 1650);
        a.ku(FN, 1700);
        a.ku(A, 1750);
        check(a.r.lastF == FN_LOCKED, "a 按着时按下 Fn、松开：锁照旧");
    }

    /** 按住超过 1 秒（修饰键那条"超过 1 秒不锁"对 Fn 不适用）：照样永久锁。 */
    static void fnHoldLong() {
        H h = new H(FN_REGIONS);
        h.kd(FN, 1000);
        h.to(2000);
        check(h.r.lastF == (FN_HELD | FN_ARMED) && h.r.clicks == 1 && !h.r.keyPending, "1 秒：照旧快永久锁了、只震过一次、不再等");
        h.ku(FN, 2500);
        check(h.r.lastF == FN_LOCKED, "按住 1.5 秒松手：永久锁");
        h.kd(UP, 2600);
        h.ku(UP, 2650);
        eq(h.r.keys, list("[00|4b]", "[00|]"), "之后 ↑ 出 PgUp");
    }

    /** 定时器卡住、一直没到点：松手那一刻照截止时刻算（同修饰键）：过了 0.4 秒就补上（震一下）、永久锁，不管多久。 */
    static void fnTimerStuck() {
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.r.keyPending = false;
        g.t.key(FN, Proto.KEY_UP, 2300);
        check(g.r.lastF == FN_LOCKED && g.r.clicks == 1, "没到点、松手时已过 1 秒：照截止时刻补上（震一下）、永久锁");
        H f = new H(FN_REGIONS);
        f.kd(FN, 1000);
        f.r.keyPending = false;
        f.t.key(FN, Proto.KEY_UP, 1500);
        check(f.r.lastF == FN_LOCKED && f.r.clicks == 1, "没到点、松手时 0.5 秒：照截止时刻补上（震一下、永久锁）");
        H e = new H(FN_REGIONS);
        e.kd(FN, 1000);
        e.r.keyPending = false;
        e.t.key(FN, Proto.KEY_UP, 1300);
        check(e.r.lastF == FN_LATCHED && e.r.clicks == 0, "没到点、松手时 0.3 秒：单次锁、不震");
    }

    /** 单点 Fn / fn🌐（按 150ms 松手）。 */
    static void tap(H h, int key, long t0) {
        h.kd(key, t0);
        h.ku(key, t0 + 150);
    }

    /** 先松 Fn 再松键：松的是按下时的 Fn 层的码（区域那一路、key() 那一路一样）。 */
    static void fnReleasedFirst() {
        H h = new H(FN_REGIONS);
        h.td(0, col(FN_REGIONS, FN), 1000);
        h.td(1, col(FN_REGIONS, UP), 1100);
        h.tu(0, 1200, false);
        check(h.r.lastF == 0, "Fn 松开了");
        eq(h.r.keys, list("[00|4b]"), "PgUp 还按着（Fn 松开不影响已经按着的键）");
        h.tu(1, 1300, false);
        eq(h.r.keys, list("[00|4b]", "[00|]"), "区域那一路：先松 Fn 再松 ↑，松的是 PgUp");
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.kd(ONE, 1100);
        g.ku(FN, 1200);
        g.ku(ONE, 1300);
        eq(g.r.keys, list("[00|3a]", "[00|]"), "key() 那一路：先松 Fn 再松 1，松的是 F1");
        // 被取消的抬起也松按下时的码
        H f = new H(FN_REGIONS);
        f.kd(FN, 1000);
        f.kd(UP, 1100);
        f.ku(FN, 1200);
        f.kc(UP, 1300);
        eq(f.r.keys, list("[00|4b]", "[00|]"), "被取消的：照样松 PgUp");
    }

    /** 先按键、再按 Fn、再松键：松的是按下时的底层码；之后按着 Fn 再按才是 Fn 层。 */
    static void fnPressedBeforeFn() {
        H h = new H(FN_REGIONS);
        h.td(1, col(FN_REGIONS, UP), 1000);
        h.td(0, col(FN_REGIONS, FN), 1100);
        h.tu(1, 1200, false);
        h.td(1, col(FN_REGIONS, UP), 1300);
        h.tu(1, 1350, false);
        h.tu(0, 1400, false);
        eq(h.r.keys, list("[00|52]", "[00|]", "[00|4b]", "[00|]"), "按着 ↑ 时按 Fn：松的是 ↑；再按才是 PgUp");
        check(h.r.lastF == 0 && h.r.globes == 0, "Fn 按下时 ↑ 按着：组合，不锁");
    }

    /**
     * 同一个带 Fn 层的键两根手指（区域那一路两个 slot、key() 那一路两次按下）：后来的跟着第一根的码（真键盘上按下的键不会再按下一次），
     * 先抬起的那根就松开（同两个空格副本）、最后一根抬起忘掉这个键记的码；两条路出一模一样的报文。
     */
    static void fnTwoFingersSameKey() {
        H h = new H(FN_REGIONS);
        int up = col(FN_REGIONS, UP), fn = col(FN_REGIONS, FN);
        h.td(0, up, 1000);    // ↑
        h.td(2, fn, 1050);    // Fn
        h.td(1, up, 1100);    // 同一个键第二根：跟着第一根（↑），不另外按 PgUp
        eq(h.r.keys, list("[00|52]"), "第二根跟着第一根的码");
        h.tu(0, 1150, false);
        eq(h.r.keys, list("[00|52]", "[00|]"), "先抬起的那根就松开");
        h.tu(1, 1200, false);
        eq(h.r.keys.size(), 2, "最后一根抬起：不多出东西");
        h.td(0, up, 1250);    // Fn 还按着：这个键已经忘了，重新定码 → PgUp
        h.td(1, up, 1300);    // 跟着 PgUp
        h.tu(2, 1350, false); // 松 Fn
        h.td(3, up, 1400);    // Fn 松了，但 ↑ 还有手指按着：第三根照样跟着 PgUp
        h.tu(0, 1450, false);
        h.tu(1, 1500, false);
        h.tu(3, 1550, false);
        h.td(0, up, 1600);
        h.tu(0, 1650, false);
        List<String> want = list("[00|52]", "[00|]", "[00|4b]", "[00|]", "[00|52]", "[00|]");
        eq(h.r.keys, want, "区域那一路：两根三根手指按同一个键，每个按下都配了松开");
        H g = new H(FN_REGIONS);
        g.kd(UP, 1000);
        g.kd(FN, 1050);
        g.kd(UP, 1100);
        g.ku(UP, 1150);
        g.ku(UP, 1200);
        g.kd(UP, 1250);
        g.kd(UP, 1300);
        g.ku(FN, 1350);
        g.kd(UP, 1400);
        g.ku(UP, 1450);
        g.ku(UP, 1500);
        g.ku(UP, 1550);
        g.kd(UP, 1600);
        g.ku(UP, 1650);
        eq(g.r.keys, want, "key() 那一路（面板自己收触摸）同样的序列一模一样");
    }

    /** 底层是修饰键（Windows 模式右 Ctrl，Fn 层 Menu）：没按 Fn 时照修饰键（点一下 / 锁），按着 Fn 时出 Menu、不碰修饰键字节。 */
    static void fnModifierBase() {
        H h = new H(FN_REGIONS);
        h.kd(RCTL, 1000);
        h.ku(RCTL, 1100);
        eq(h.r.keys, list("[10|]", "[00|]"), "没按 Fn：右 Ctrl 点一下");
        lock(h, RCTL, 1200);
        check(h.r.lastL == 0x10 && h.lastKeys().equals("[10|]"), "单独按住 0.5 秒：右 Ctrl 锁住（同修饰键）");
        h.kd(A, 1800);
        h.ku(A, 1850);
        check(h.r.lastL == 0, "打完 a 解开");
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.kd(RCTL, 1100);
        g.ku(RCTL, 1150);
        g.ku(FN, 1200);
        eq(g.r.keys, list("[00|65]", "[00|]"), "按着 Fn：右 Ctrl 出 Menu（不按下修饰键）");
        check(g.r.lastL == 0 && g.r.lastH == 0, "修饰键状态没动");
        H f = new H(FN_REGIONS);
        f.kd(RCTL, 1000);
        f.kd(FN, 1100);
        f.to(1600);
        f.ku(RCTL, 1600);
        f.ku(FN, 1700);
        eq(f.r.keys, list("[10|]", "[00|]"), "右 Ctrl 按着时按 Fn、松开：松的是右 Ctrl");
        check(f.r.lastL == 0 && f.r.lastF == 0 && f.r.clicks == 0, "两个一起按：谁都不锁、不震");
    }

    /** 按着 Shift 再按 Fn+1：主机收到 Shift+F1（同真键盘）；Shift、Fn 都用过、不锁。 */
    static void fnWithShift() {
        H h = new H(FN_REGIONS);
        h.kd(SHIFT, 1000);
        h.kd(FN, 1050);
        h.kd(ONE, 1100);
        h.ku(ONE, 1150);
        h.to(1600);
        h.ku(FN, 1600);
        h.ku(SHIFT, 1650);
        eq(h.r.keys, list("[02|]", "[02|3a]", "[02|]", "[00|]"), "Shift + Fn + 1 = Shift+F1");
        check(h.r.lastL == 0 && h.r.lastF == 0 && h.r.clicks == 0, "都不锁、不震");
        // 先按 Fn 再按 Shift：Fn 用过（不锁），Shift 按下时 Fn 按着（组合，不锁）
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.kd(SHIFT, 1050);
        g.to(1600);
        g.ku(SHIFT, 1600);
        g.ku(FN, 1650);
        check(g.r.lastL == 0 && g.r.lastF == 0 && g.r.clicks == 0, "Fn + Shift：都不锁");
    }

    /** 被取消的：快锁了的 Fn 不锁；按着的带 Fn 层的键照样松开。 */
    static void fnCanceled() {
        H h = new H(FN_REGIONS);
        h.td(0, col(FN_REGIONS, FN), 1000);
        h.to(1500);
        h.tu(0, 1600, true);
        check(h.r.lastF == 0, "快锁了的 Fn 被取消：不锁");
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.to(1500);
        g.kc(FN, 1600);
        check(g.r.lastF == 0, "界面那一路的 KEY_CANCEL 同样");
        H f = new H(FN_REGIONS);
        f.td(0, col(FN_REGIONS, FN), 1000);
        f.td(1, col(FN_REGIONS, UP), 1100);
        f.tu(0, 1200, true);
        f.tu(1, 1300, true);
        eq(f.r.keys, list("[00|4b]", "[00|]"), "Fn、↑ 都被取消（收尾补报）：PgUp 照样松开");
        check(f.r.lastF == 0, "Fn 归零");
    }

    /** releaseAll：Fn 按着 / 锁着 / 快锁了、按着的带 Fn 层的键记的码全部丢掉；之后迟到的松开不出东西，新按的重新定码。 */
    static void fnReleaseAll() {
        H h = new H(FN_REGIONS);
        h.kd(FN, 1000);
        h.kd(UP, 1100);
        h.to(1200);
        h.t.releaseAll();
        eq(h.lastKeys(), "[00|]", "releaseAll：一帧干净的报文");
        check(h.r.lastF == 0 && !h.r.keyPending, "Fn 归零、定时器撤了");
        int n = h.r.keys.size(), m = h.r.mods.size();
        h.ku(UP, 1300);
        h.ku(FN, 1350);
        check(h.r.keys.size() == n && h.r.mods.size() == m && h.r.globes == 0, "迟到的松开（Fn、↑）：什么都不出");
        h.kd(UP, 1400);
        h.ku(UP, 1450);
        eq(h.r.keys.subList(n, n + 2), list("[00|52]", "[00|]"), "之后按 ↑：Fn 和记的码都忘了，是 ↑");
        H e = new H(FN_REGIONS);   // 迟到的松开还没来、先按同一个键：不能跟着 releaseAll 之前记的 PgUp
        e.kd(FN, 1000);
        e.kd(UP, 1100);
        e.to(1200);
        e.t.releaseAll();
        e.kd(UP, 1300);
        e.ku(UP, 1350);
        e.ku(UP, 1400);
        e.ku(FN, 1450);
        eq(e.r.keys, list("[00|4b]", "[00|]", "[00|52]", "[00|]"), "releaseAll 后先按同一个键：重新定码（↑），不跟着之前记的 PgUp");
        H g = new H(FN_REGIONS);
        lock(g, FN, 1000);
        check(g.r.lastF == FN_LOCKED, "永久锁");
        g.t.releaseAll();
        check(g.r.lastF == 0, "永久锁着的 Fn：releaseAll 解开");
        g.kd(UP, 2000);
        g.ku(UP, 2050);
        eq(g.r.keys.subList(g.r.keys.size() - 2, g.r.keys.size()), list("[00|52]", "[00|]"), "之后 ↑ 是 ↑");
        H d = new H(FN_REGIONS);
        tap(d, FN, 1000);
        check(d.r.lastF == FN_LATCHED, "单次锁");
        d.t.releaseAll();
        check(d.r.lastF == 0, "单次锁着的 Fn：releaseAll 解开");
        H c = new H(FN_REGIONS);   // 锁着时按下 Fn（等着松手解开）、releaseAll：都归零，迟到的松开不出东西
        lock(c, FN, 1000);
        c.kd(FN, 1600);
        c.t.releaseAll();
        c.ku(FN, 1700);
        check(c.r.lastF == 0, "锁着时按着 Fn 被 releaseAll：归零，迟到的松开不会又锁上");
        H f = new H(FN_REGIONS);
        f.kd(FNG, 1000);
        f.to(1500);
        check(f.r.lastF == (FN_HELD | FN_ARMED), "快锁了");
        f.t.releaseAll();
        f.ku(FNG, 1600);
        check(f.r.lastF == 0 && f.r.globes == 0, "快锁了的 fn🌐：releaseAll 归零，迟到的松开不锁、不出 🌐");
    }

    /** 换参数（setConfig）同修饰键：按着的 Fn、带 Fn 层的键照旧按着，抬起照常判；锁着的 Fn 照旧锁着。 */
    static void fnNewConfig() {
        H h = new H(FN_REGIONS);
        h.td(0, col(FN_REGIONS, FN), 1000);
        h.td(1, col(FN_REGIONS, UP), 1100);
        h.to(1150);
        h.t.setConfig(new GestureEngine.Config(), 1);
        check(h.r.lastF == FN_HELD && h.lastKeys().equals("[00|4b]"), "换参数：Fn、PgUp 照旧按着");
        h.tu(1, 1200, false);
        h.td(1, col(FN_REGIONS, UP), 1250);
        h.tu(1, 1300, false);
        h.tu(0, 1350, false);
        eq(h.r.keys, list("[00|4b]", "[00|]", "[00|4b]", "[00|]"), "换参数之后 Fn 照样生效、抬起照常松开");
        H g = new H(FN_REGIONS);
        lock(g, FN, 1000);
        g.t.setConfig(new GestureEngine.Config(), 1);
        g.kd(UP, 1600);
        g.ku(UP, 1650);
        eq(g.r.keys, list("[00|4b]", "[00|]"), "永久锁着的 Fn 照旧锁着");
        H f = new H(FN_REGIONS);
        tap(f, FN, 1000);
        f.t.setConfig(new GestureEngine.Config(), 1);
        check(f.r.lastF == FN_LATCHED, "单次锁着的 Fn 照旧单次锁着");
        H e = new H(FN_REGIONS);   // 锁着时按着 Fn（等着松手解开）换参数：松手照样解开
        lock(e, FN, 1000);
        e.td(0, col(FN_REGIONS, FN), 1600);
        e.t.setConfig(new GestureEngine.Config(), 1);
        e.tu(0, 1700, false);
        check(e.r.lastF == 0, "锁着时按着 Fn、换参数、松手：照常解开");
    }

    /** CMD_UNLATCH：锁着的 Fn（单次、永久）解开，按着的 Fn 照旧计时。 */
    static void fnUnlatch() {
        H h = new H(FN_REGIONS);
        lock(h, FN, 1000);
        h.to(1600);
        h.t.unlatch(1600);
        check(h.r.lastF == 0, "永久锁着的 Fn 解开");
        h.kd(UP, 1700);
        h.ku(UP, 1750);
        eq(h.r.keys, list("[00|52]", "[00|]"), "之后 ↑ 是 ↑");
        H d = new H(FN_REGIONS);
        tap(d, FN, 1000);
        d.t.unlatch(1200);
        check(d.r.lastF == 0, "单次锁着的 Fn 解开");
        H g = new H(FN_REGIONS);
        g.kd(FN, 1000);
        g.to(1100);
        g.t.unlatch(1100);
        check(g.r.lastF == FN_HELD, "按着的 Fn 照旧按着");
        g.to(1400);
        g.ku(FN, 1500);
        check(g.r.lastF == FN_LOCKED, "照常计时、永久锁");
        H c = new H(FN_REGIONS);   // 锁着时按下 Fn（等着松手解开）、中途 unlatch：松手时已经没锁可解，什么都不多做（不会反过来锁上）
        lock(c, FN, 1000);
        c.kd(FN, 1600);
        c.t.unlatch(1650);
        check(c.r.lastF == FN_HELD, "unlatch：锁没了、Fn 还按着");
        c.ku(FN, 1700);
        check(c.r.lastF == 0, "松手：什么都不多做");
    }

    /** 按着 Fn 时用了鼠标（点一下 / 滚轮 / 按住左键）：不锁、fn🌐 不出 🌐（同修饰键"用过"）。 */
    static void fnUsedByMouse() {
        for (int k = 0; k < 3; k++) {
            H h = new H(FN_REGIONS);
            h.kd(FNG, 1000);
            h.to(1100);
            if (k == 0) h.t.click(Proto.BUTTON_LEFT);
            else if (k == 1) h.t.wheel(1);
            else {
                h.t.holdLeft(true);
                h.to(1150);
                h.t.holdLeft(false);
            }
            h.ku(FNG, 1200);
            check(h.r.globes == 0 && h.r.lastF == 0, "按着 fn🌐 时用了鼠标（" + k + "）：短按也不出 🌐");
            H g = new H(FN_REGIONS);
            g.kd(FN, 1000);
            g.to(1100);
            if (k == 0) g.t.click(Proto.BUTTON_LEFT);
            else if (k == 1) g.t.wheel(1);
            else g.t.holdLeft(true);
            g.to(1600);
            g.ku(FN, 1600);
            check(g.r.lastF == 0 && g.r.clicks == 0, "按着 Fn 时用了鼠标（" + k + "）：0.6 秒松手不锁、不震");
        }
    }

    /** 按下 Fn 那一刻已有别的键 / 鼠标键按着：组合的一部分，不锁。 */
    static void fnBusyAtPress() {
        H h = new H(FN_REGIONS);
        h.kd(A, 1000);
        h.kd(FN, 1050);
        h.to(1600);
        h.ku(FN, 1600);
        h.ku(A, 1650);
        check(h.r.lastF == 0 && h.r.clicks == 0, "a 按着时按 Fn：不锁");
        H g = new H(FN_REGIONS);
        g.to(1000);
        g.t.holdLeft(true);
        g.kd(FN, 1050);
        g.to(1600);
        g.ku(FN, 1600);
        g.t.holdLeft(false);
        check(g.r.lastF == 0 && g.r.clicks == 0, "左键按着时按 Fn：不锁");
    }

    /** 早到、重复、撤掉之后到的、时间倒着来的定时器回调：什么都不多做（Fn 同修饰键）。 */
    static void fnTimerOutOfOrder() {
        H h = new H(FN_REGIONS);
        h.kd(FN, 1000);
        int n = h.r.all.size();
        h.t.onKeyTimer(1100);
        h.t.onKeyTimer(1100);
        check(h.r.all.size() == n, "早到、重复的：什么都不出");
        h.ku(FN, 1200);
        check(h.r.lastF == FN_LATCHED, "0.2 秒松手：单次锁");
        n = h.r.all.size();
        h.t.onKeyTimer(1400);
        h.t.onKeyTimer(2000);
        h.t.onKeyTimer(500);
        check(h.r.all.size() == n && h.r.lastF == FN_LATCHED && h.r.clicks == 0, "撤掉之后到点的、乱序的：不震、不变永久锁");
    }

    /** 区域那一路和 key() 那一路同样的 Fn 序列：报文、修饰键 / Fn 状态、🌐、震动一模一样。 */
    static void fnPathsAgree() {
        int[][] seq = {   // {键, 按下时间, 抬起时间, 取消}：永久锁、锁着打键、点 fn🌐 解开、右 Ctrl 锁、单次锁、fn🌐 永久锁、点 Fn 解开、单点 fn🌐 出 🌐、单次锁打 ↑
                {FN, 1000, 1600, 0}, {UP, 1700, 1750, 0}, {FNG, 1800, 1900, 0}, {RCTL, 2000, 2500, 0}, {ONE, 2600, 2700, 0},
                {FNG, 2800, 3300, 0}, {UP, 3400, 3450, 1}, {FN, 3500, 3550, 0}, {A, 3600, 3650, 0}, {FNG, 3700, 3800, 0},
                {FN, 3900, 4000, 0}, {UP, 4050, 4100, 0}, {FN, 4200, 4300, 1},
        };
        H a = new H(FN_REGIONS), b = new H(FN_REGIONS);
        for (int[] s : seq) {
            a.td(0, col(FN_REGIONS, s[0]), s[1]);
            b.kd(s[0], s[1]);
            a.tu(0, s[2], s[3] == 1);
            if (s[3] == 1) b.kc(s[0], s[2]);
            else b.ku(s[0], s[2]);
        }
        // 两根手指交叠：按住 Fn 打 ↑、松 Fn、再松 ↑
        a.td(0, col(FN_REGIONS, FN), 5000);
        b.kd(FN, 5000);
        a.td(1, col(FN_REGIONS, UP), 5100);
        b.kd(UP, 5100);
        a.tu(0, 5200, false);
        b.ku(FN, 5200);
        a.tu(1, 5300, false);
        b.ku(UP, 5300);
        a.to(7000);
        b.to(7000);
        eq(a.r.keys, b.r.keys, "两条路的报文一样");
        eq(a.r.mods, b.r.mods, "两条路报给界面的修饰键 / Fn 状态一样");
        check(a.r.clicks == b.r.clicks && a.r.globes == b.r.globes && a.r.globes == 1, "震动、🌐 一样（🌐 只在单点 fn🌐 那一下出一次）");
    }

    /**
     * 几百组随机序列（Fn、fn🌐、带 Fn 层的键——↑ / 1 / 右 Ctrl、Shift、a、底层就发 PgUp 的键；按下抬起、被取消、两根手指、点鼠标、滚轮、
     * 乱序的定时器、releaseAll、unlatch），旁边跑一份照协议 22 的规矩写的模型（单次锁、永久锁两个开关），每步查：
     * 报给界面的单次锁 / 永久锁和模型一样、两种锁不同时出现、快永久锁了 ⊆ 按着；HID 修饰键字节 = latched | held；
     * 带 Fn 层的键按下时出的码 = 那一刻 Fn 按着 / 单次锁 / 永久锁（这个键已经按着就跟着它）定的、最后一根抬起时这个码从报文里消失；
     * Fn 最后一根抬起时照模型走：没当组合用、没被取消的——锁着就解开，没锁着按住 0.4 秒起就永久锁、不到 0.4 秒 fn🌐 出 🌐 / Fn 单次锁；
     * 普通键（出的码不是修饰键）松开用掉单次锁、永久锁不动；震动次数 = 修饰键和 Fn 快锁了的次数；
     * 全部抬起后（releaseAll 之前）主机上没有按着的键、修饰键字节 = 锁住的；再 releaseAll 全部归零。
     */
    static void fnRandomSequences() {
        int[] keys = {FN, FNG, UP, ONE, RCTL, SHIFT, A, PGUP};
        int steps = 0, lockSeen = 0, onceSeen = 0, unlockSeen = 0, globesSeen = 0, fnLayerSeen = 0, bad = 0;
        String firstBad = null;
        for (int seed = 1; seed <= 400; seed++) {
            Random rnd = new Random(seed);
            H h = new H(FN_REGIONS);
            long t = 1000;
            Map<Integer, Integer> count = new HashMap<>();
            Map<Integer, Integer> code = new HashMap<>();
            // 模型：单次锁、永久锁；这次按下的 Fn 从什么时候按着、是哪一种、按下时锁着没有、当没当组合用
            boolean once = false, lock = false;
            long fnAt = 0;
            int fnFirst = 0;
            boolean fnTainted = false, fnWasLocked = false;
            for (int step = 0; step < 150; step++) {
                steps++;
                t += rnd.nextInt(5) == 0 ? 300 + rnd.nextInt(900) : rnd.nextInt(120);
                int op = rnd.nextInt(22);
                int key = keys[rnd.nextInt(keys.length)];
                int n = count.getOrDefault(key, 0);
                int fnHeld = count.getOrDefault(FN, 0) + count.getOrDefault(FNG, 0);
                String why = null;
                if (op >= 19) {   // 别的都没按着时单独按住一会儿（0.05–1.5 秒）再松：单次锁 / 永久锁 / 🌐 / 解开
                    boolean empty = true;
                    for (int v : count.values()) empty &= v == 0;
                    if (!empty) continue;
                    int fk = rnd.nextBoolean() ? FN : FNG;
                    h.to(t);
                    int globes = h.r.globes;
                    boolean wasLocked = once || lock;
                    h.kd(fk, t);
                    long d = 50 + rnd.nextInt(1450);
                    t += d;
                    h.ku(fk, t);
                    boolean wantGlobe = false;
                    if (wasLocked) {
                        once = lock = false;
                        unlockSeen++;
                    } else if (d >= KeyHolds.HOLD_MS) {
                        lock = true;
                    } else if (fk == FNG) {
                        wantGlobe = true;
                    } else {
                        once = true;
                    }
                    boolean gotGlobe = h.r.globes > globes;
                    if (wantGlobe != gotGlobe) why = "单独按 " + (fk == FNG ? "fn🌐" : "Fn") + " " + d + "ms：🌐 想要 " + wantGlobe + "，实际 " + gotGlobe;
                    if (gotGlobe) globesSeen++;
                } else if (op < 9) {   // 按下
                    if (n >= 2) continue;
                    h.to(t);
                    boolean others = false;
                    for (Map.Entry<Integer, Integer> e : count.entrySet()) others |= e.getKey() != key && e.getValue() > 0;
                    boolean active = fnHeld > 0 || once || lock;
                    if (op < 6) h.kd(key, t);
                    else h.td(n, col(FN_REGIONS, key), t);
                    if (Proto.isFnKey(key)) {
                        if (fnHeld == 0) {
                            fnAt = t;
                            fnFirst = key;
                            fnTainted = others;
                            fnWasLocked = once || lock;
                        }
                    } else if (fnHeld > 0) {
                        fnTainted = true;   // 按着 Fn 时按了别的键：用过（当组合用）
                    }
                    int want = key;
                    if (Proto.isFnLayerKey(key)) {
                        want = n > 0 ? code.get(key) : active ? Proto.fnCode(key) : Proto.fnBase(key);
                        code.put(key, want);
                        if (want == Proto.fnCode(key)) fnLayerSeen++;
                        boolean ok = KeyHolds.isModifier(want) ? (h.r.lastMods & (1 << (want - 0xE0))) != 0
                                : h.lastKeys().matches(".*[| ]" + String.format("%02x", want) + "[] ].*");
                        if (!ok) why = "按下 " + Integer.toHexString(key) + "（Fn " + (active ? "生效" : "没生效") + (n > 0 ? "、已经按着" : "")
                                + "）：想要 " + Integer.toHexString(want) + "，报文 " + h.lastKeys();
                    }
                    count.put(key, n + 1);
                } else if (op < 16) {   // 抬起（有时被取消），都经 key()
                    if (n == 0) continue;
                    boolean cancel = rnd.nextInt(8) == 0;
                    h.to(t);
                    int globes = h.r.globes;
                    if (cancel) h.kc(key, t);
                    else h.ku(key, t);
                    count.put(key, n - 1);
                    int released = key;
                    if (Proto.isFnLayerKey(key)) {
                        released = code.get(key);
                        if (n == 1) {
                            code.remove(key);
                            if (!KeyHolds.isModifier(released) && h.lastKeys().matches(".*[| ]" + String.format("%02x", released) + "[] ].*")) {
                                why = "最后一根松开 " + Integer.toHexString(key) + "：报文里还按着 " + Integer.toHexString(released) + " " + h.lastKeys();
                            }
                        }
                    }
                    // 普通键（出的码不是修饰键、不是 Fn）松开，被取消的也算：用掉单次锁（同锁住的修饰键"打完下一个普通键解开"）
                    if (!Proto.isFnKey(key) && !KeyHolds.isModifier(released)) once = false;
                    if (Proto.isFnKey(key) && fnHeld == 1) {
                        long d = t - fnAt;
                        boolean wantGlobe = false;
                        if (!cancel && !fnTainted) {
                            if (fnWasLocked) {
                                once = lock = false;
                                unlockSeen++;
                            } else if (d >= KeyHolds.HOLD_MS) {
                                lock = true;
                            } else if (fnFirst == FNG) {
                                wantGlobe = true;
                            } else {
                                once = true;
                            }
                        }
                        boolean gotGlobe = h.r.globes > globes;
                        if (wantGlobe != gotGlobe) {
                            why = "Fn 按了 " + d + "ms" + (fnTainted ? "（用过）" : "") + (cancel ? "（被取消）" : "") + (fnWasLocked ? "（按下时锁着）" : "")
                                    + " 松开：🌐 想要 " + wantGlobe + "，实际 " + gotGlobe;
                        }
                        if (gotGlobe) globesSeen++;
                    }
                } else if (op == 16) {   // 鼠标：点一下 / 滚一格
                    h.to(t);
                    if (rnd.nextBoolean()) h.t.click(Proto.BUTTON_LEFT);
                    else h.t.wheel(1);
                    if (fnHeld > 0) fnTainted = true;
                } else if (op == 17) {   // 乱序的定时器回调
                    h.t.onKeyTimer(t - rnd.nextInt(2000));
                } else {
                    h.to(t);
                    if (rnd.nextBoolean()) {
                        h.t.unlatch(t);
                        once = lock = false;
                    } else {
                        h.t.releaseAll();
                        count.clear();
                        code.clear();
                        once = lock = false;
                    }
                }
                R r = h.r;
                boolean gotOnce = (r.lastF & FN_LATCHED) != 0, gotLock = (r.lastF & FN_LOCKED) != 0;
                if (gotOnce) onceSeen++;
                if (gotLock) lockSeen++;
                if (why == null && (gotOnce != once || gotLock != lock)) {
                    why = "Fn 的锁：模型 单次 " + once + " / 永久 " + lock + "，报的 " + r.lastF;
                }
                if (why == null && gotOnce && gotLock) why = "单次锁和永久锁同时出现";
                if (why == null && (r.lastF & FN_ARMED) != 0 && (r.lastF & FN_HELD) == 0) why = "Fn 快锁了却没按着";
                if (why == null && !r.keys.isEmpty() && r.lastMods != (r.lastL | r.lastH)) {
                    why = "HID 修饰键字节 " + Integer.toHexString(r.lastMods) + " ≠ latched | held " + Integer.toHexString(r.lastL | r.lastH);
                }
                if (why != null) {
                    bad++;
                    if (firstBad == null) firstBad = "种子 " + seed + " 第 " + step + " 步：" + why;
                    break;
                }
            }
            for (Map.Entry<Integer, Integer> e : new HashMap<>(count).entrySet()) {
                for (int i = 0; i < e.getValue(); i++) h.ku(e.getKey(), t += 10);
            }
            h.to(t += 5000);
            String settled = h.r.lastKeys(), want = String.format("[%02x|]", h.r.lastL);
            if (!h.r.keys.isEmpty() && !settled.equals(want)) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：全部抬起后（releaseAll 之前）主机上还按着 " + settled + "，想要 " + want;
            }
            h.t.releaseAll();
            R r = h.r;
            boolean ok = (r.lastKeys().isEmpty() || r.lastKeys().equals("[00|]")) && r.lastL == 0 && r.lastH == 0 && r.lastA == 0 && r.lastF == 0
                    && !r.keyPending;
            if (!ok) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：收尾后没归零 " + r.lastKeys() + " Fn " + r.lastF;
            }
            if (r.clicks != r.armedRises + r.fnArmedRises) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：震了 " + r.clicks + " 次，修饰键快锁了 " + r.armedRises + " + Fn 快锁了 " + r.fnArmedRises;
            }
        }
        check(bad == 0, "Fn 随机序列：" + (firstBad == null ? "" : firstBad));
        check(lockSeen > 200 && onceSeen > 200 && unlockSeen > 50 && globesSeen > 50 && fnLayerSeen > 200,
                "随机序列里真单次锁过、真永久锁过、真解开过、真出过 🌐、真出过 Fn 层（" + onceSeen + " / " + lockSeen + " / " + unlockSeen + " / " + globesSeen + " / " + fnLayerSeen + "）");
        System.err.println("  Fn 随机序列：" + steps + " 步，单次锁着 " + onceSeen + " 步、永久锁着 " + lockSeen + " 步、解开 " + unlockSeen + " 次、🌐 " + globesSeen
                + " 次、出 Fn 层 " + fnLayerSeen + " 次");
    }

    /** 键在 REGIONS 里第几列（Shift 用第一个）。 */
    static int col(int key) {
        for (int i = 0; i < REGIONS.length; i++) if (REGIONS[i].type == Proto.REGION_KEY && REGIONS[i].id == key) return i;
        throw new IllegalArgumentException("屏幕上没有这个键：" + Integer.toHexString(key));
    }

    // ═══════════ 工具 ═══════════

    static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    static void eq(Object actual, Object expected, String what) {
        check(expected.equals(actual), what + "（期望 " + expected + "，实际 " + actual + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }
}
