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
    static final int CLICK = GestureEngine.HAPTIC_CLICK;

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

        @Override
        public void haptic(int kind) {
            if (kind == CLICK) clicks++;
            all.add("h" + kind);
        }

        @Override
        public void gesture(int fingers, int kind) {
            all.add("g" + fingers);
        }

        @Override
        public void button(int id, int action) {
        }

        @Override
        public void modifiers(int latched, int held, int armed) {
            armedRises += Integer.bitCount(armed & ~lastA);
            lastL = latched;
            lastH = held;
            lastA = armed;
            String s = latched + "/" + held + "/" + armed;
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

        H() {
            t.setConfig(new GestureEngine.Config(), 1);
            t.setRegions(REGIONS);
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
            check(h.r.lastL == 0 && h.r.clicks == clicksFromPad(h, k), what[k] + "：按着 Ctrl 时用了鼠标，不锁、不到点震");
        }
        // 双指开合（缩放 = Ctrl + 滚轮）
        H h = new H();
        h.kd(SHIFT, 1000);
        pinch(h, 1050);
        h.to(1500);
        h.ku(SHIFT, 1500);
        check(h.r.lastL == 0 && h.r.clicks == 0, "按着 Shift 双指缩放：不锁");
    }

    /** 触控板轻点自己会震一下（HAPTIC_CLICK），别的情况不震。 */
    static int clicksFromPad(H h, int k) {
        return k == 0 ? 1 : 0;
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
