package dev.douhua.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/**
 * 分屏透传的纯 Java 部分：手指归谁（TouchSplit）、注入流怎么拼（InjectStream）、MtDecoder 的取消（手掌、丢数据）。
 * 注入流用一个照 AOSP input_verifier.rs（Android 16 起系统拿它拒绝不自洽的注入流）写的小校验器逐个事件过一遍。
 * 不依赖 Android、不需要 JUnit：tools/test.sh 用电脑上的 JDK 直接编译运行。
 */
public final class PassthroughTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        splitFullGoesToRouter();
        splitOutsideAndGestureEdges();
        splitStickyUntilUp();
        splitOwnTargetInBand();
        verifierCatchesBadStreams();
        streamSingleFinger();
        streamTwoFingersInterleaved();
        streamCancelVoidsUntilUp();
        streamCancelIdempotent();
        streamMoveOnlyWhenChanged();
        streamFuzzPassesVerifier();
        streamNoFocusOnlyOnDown();
        downAfterAndResidueRecord();
        decoderSeesPreGrabFinger();
        decoderPreGrabLiftClearAndSlot();
        liftWatchKernelSide();
        liftWatchBackoffAndStale();
        decoderCancelForPalmAndDrop();
        harnessPalmReplacedStop();
        harnessFuzzWithDecoder();

        System.err.println("分屏透传：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ TouchSplit ═══════════

    /** 上下分屏、豆花在下半：窗口 [0,1000)–[1000,2000)，左右返回带 40、下边回桌面带 60、上边没贴屏幕边（0）。 */
    static final TouchSplit.Window BOTTOM = new TouchSplit.Window(false, 0, 1000, 1000, 2000, 40, 0, 40, 60);

    static void splitFullGoesToRouter() {
        TouchSplit.Window full = TouchSplit.Window.FULL;
        check(!full.toSystem(5, 5), "占满：左上角归豆花");
        check(!full.toSystem(500, 1990), "占满：底边归豆花（系统手势照旧挡住）");
        check(!full.toSystem(-10, 99999), "占满：哪儿都归豆花");
    }

    static void splitOutsideAndGestureEdges() {
        check(BOTTOM.toSystem(500, 500), "窗口外（上半，另一边的应用）归系统");
        check(BOTTOM.toSystem(500, 999), "窗口上边外一点（分隔条）归系统");
        check(!BOTTOM.toSystem(500, 1000), "窗口上边（没贴屏幕边，手势带 0）归豆花");
        check(!BOTTOM.toSystem(500, 1500), "窗口中间归豆花");
        check(BOTTOM.toSystem(10, 1500), "左边返回带归系统");
        check(!BOTTOM.toSystem(40, 1500), "左边返回带外（正好 40）归豆花");
        check(BOTTOM.toSystem(970, 1500), "右边返回带归系统");
        check(!BOTTOM.toSystem(959, 1500), "右边返回带外归豆花");
        check(BOTTOM.toSystem(500, 1950), "下边回桌面带归系统");
        check(!BOTTOM.toSystem(500, 1939), "下边回桌面带外归豆花");
        check(BOTTOM.toSystem(500, 2000), "窗口下边外（右、下不含）归系统");
        TouchSplit.Window top = new TouchSplit.Window(false, 0, 0, 1000, 990, 40, 80, 40, 0);
        check(top.toSystem(500, 30), "豆花在上半：上边下拉带归系统");
        check(!top.toSystem(500, 500), "豆花在上半：中间归豆花");
        check(top.toSystem(500, 995), "豆花在上半：窗口下面的分隔条归系统");
        TouchSplit.Window neg = new TouchSplit.Window(false, 0, 0, 100, 100, -5, -5, -5, -5);
        check(!neg.toSystem(0, 0), "负的手势带宽度当 0");
        check(BOTTOM.sameAs(new TouchSplit.Window(false, 0, 1000, 1000, 2000, 40, 0, 40, 60)), "sameAs：一样");
        check(!BOTTOM.sameAs(TouchSplit.Window.FULL), "sameAs：不一样");
    }

    static void splitStickyUntilUp() {
        TouchSplit s = new TouchSplit();
        eq(s.down(0, 500, 1500, BOTTOM, false), TouchSplit.ROUTER, "按在豆花里");
        eq(s.down(1, 500, 500, BOTTOM, false), TouchSplit.SYSTEM, "按在另一边");
        // 中途窗口变了（比如回到全屏）：已经按着的手指归属不变
        check(!s.isSystem(0), "豆花这根一直归豆花");
        check(s.isSystem(1), "系统那根一直归系统（窗口变了也不变）");
        check(s.up(1), "抬起时报它归系统");
        check(!s.isSystem(1), "抬起后忘掉");
        check(!s.up(0), "豆花那根抬起报归豆花");
        eq(s.down(2, 500, 500, TouchSplit.Window.FULL, false), TouchSplit.ROUTER, "占满时同一位置归豆花");
        check(s.down(99, 500, 500, BOTTOM, false) == TouchSplit.ROUTER && !s.isSystem(99) && !s.up(-1), "slot 越界不出错");
        s.down(3, 500, 500, BOTTOM, false);
        s.clear();
        check(!s.isSystem(3), "clear 后全忘");
    }

    /** 手势带里的点：落在豆花的键 / 按钮上（ownTarget）归豆花，触控板 / 空白处归系统、注入时不挪焦点；窗口外照旧归系统。 */
    static void splitOwnTargetInBand() {
        check(BOTTOM.outside(500, 500) && !BOTTOM.inGestureBand(500, 500), "窗口外：outside，不算手势带");
        check(!BOTTOM.outside(10, 1500) && BOTTOM.inGestureBand(10, 1500), "左边返回带：窗口里、在带里");
        check(!BOTTOM.outside(500, 1500) && !BOTTOM.inGestureBand(500, 1500), "窗口中间：都不是");
        check(!TouchSplit.Window.FULL.outside(-5, -5) && !TouchSplit.Window.FULL.inGestureBand(1, 1), "占满：没有外、没有带");
        TouchSplit s = new TouchSplit();
        eq(s.down(0, 10, 1500, BOTTOM, false), TouchSplit.SYSTEM_IN_WINDOW, "带里的触控板 / 空白处：归系统、不挪焦点");
        check(s.isSystem(0), "记成系统");
        eq(s.down(1, 10, 1600, BOTTOM, true), TouchSplit.ROUTER, "带里的键 / 按钮：归豆花");
        check(!s.isSystem(1), "记成豆花");
        eq(s.down(2, 500, 1950, BOTTOM, true), TouchSplit.ROUTER, "下边回桌面带里的空格键：归豆花");
        eq(s.down(3, 500, 1950, BOTTOM, false), TouchSplit.SYSTEM_IN_WINDOW, "下边回桌面带里的空白处：归系统");
        eq(s.down(4, 500, 500, BOTTOM, true), TouchSplit.SYSTEM, "窗口外（ownTarget 不算数）：归系统、照常挪焦点");
        eq(s.down(5, 500, 1500, BOTTOM, false), TouchSplit.ROUTER, "窗口中间：归豆花");
        eq(s.down(6, 10, 1500, TouchSplit.Window.FULL, false), TouchSplit.ROUTER, "占满时边上也归豆花");
        TouchSplit.Window caption = new TouchSplit.Window(false, 100, 300, 900, 1300, 0, 48, 0, 0);
        eq(s.down(7, 500, 320, caption, false), TouchSplit.SYSTEM_IN_WINDOW, "小窗标题栏（报成上边的带）：归系统");
        eq(s.down(8, 500, 360, caption, false), TouchSplit.ROUTER, "标题栏下面：归豆花");
    }

    // ═══════════ InjectStream ═══════════

    /** 照 AOSP input_verifier.rs（verify_event / process_movement）的规则：不自洽就返回原因，自洽才更新状态。 */
    static final class Verifier {
        final TreeSet<Integer> down = new TreeSet<>();
        long downTime = -1;

        String check(InjectStream.Event e) {
            int m = e.masked();
            int idx = e.actionIndex();
            TreeSet<Integer> ids = new TreeSet<>();
            for (int id : e.ids) ids.add(id);
            if (ids.size() != e.ids.length) return "手指编号重复";
            if (e.ids.length == 0 || e.ids.length > 16) return "手指个数不对";
            for (int id : e.ids) if (id < 0 || id > 31) return "编号越界";
            if (e.xs.length != e.ids.length || e.ys.length != e.ids.length) return "坐标个数不对";
            if (m != InjectStream.ACTION_CANCEL && (e.flags & InjectStream.FLAG_CANCELED) != 0) return "非 CANCEL 带了 FLAG_CANCELED";
            if (e.eventTime < e.downTime) return "eventTime 早于 downTime";
            switch (m) {
                case InjectStream.ACTION_DOWN:
                    if (e.ids.length != 1) return "DOWN 不止一根";
                    if (!down.isEmpty()) return "DOWN 时已有手指按着";
                    down.add(e.ids[0]);
                    downTime = e.downTime;
                    return null;
                case InjectStream.ACTION_POINTER_DOWN: {
                    if (idx >= e.ids.length) return "POINTER_DOWN 下标越界";
                    int id = e.ids[idx];
                    if (down.contains(id)) return "POINTER_DOWN 的手指已经按着";
                    if (e.ids.length != down.size() + 1) return "POINTER_DOWN 个数不对";
                    TreeSet<Integer> rest = new TreeSet<>(ids);
                    rest.remove(id);
                    if (!rest.equals(down)) return "POINTER_DOWN 其它手指和按着的不一致";
                    if (e.downTime != downTime) return "downTime 变了";
                    down.add(id);
                    return null;
                }
                case InjectStream.ACTION_MOVE:
                    if (!ids.equals(down)) return "MOVE 手指集合不一致";
                    if (e.downTime != downTime) return "downTime 变了";
                    return null;
                case InjectStream.ACTION_POINTER_UP: {
                    if (idx >= e.ids.length) return "POINTER_UP 下标越界";
                    if (!ids.equals(down)) return "POINTER_UP 手指集合不一致";
                    if (down.size() < 2) return "POINTER_UP 只剩一根";
                    down.remove(e.ids[idx]);
                    return null;
                }
                case InjectStream.ACTION_UP:
                    if (e.ids.length != 1) return "UP 不止一根";
                    if (!ids.equals(down)) return "UP 手指和按着的不一致";
                    down.clear();
                    return null;
                case InjectStream.ACTION_CANCEL:
                    if ((e.flags & InjectStream.FLAG_CANCELED) == 0) return "CANCEL 没带 FLAG_CANCELED";
                    if (!ids.equals(down)) return "CANCEL 手指集合不一致";
                    down.clear();
                    return null;
                default:
                    return "不认识的动作 " + m;
            }
        }
    }

    /** 收事件：过校验器，记成字符串。 */
    static final class Sink implements InjectStream.Sink {
        final List<String> log = new ArrayList<>();
        final List<InjectStream.Event> events = new ArrayList<>();
        final Verifier verifier = new Verifier();
        final List<String> errors = new ArrayList<>();

        @Override
        public void emit(InjectStream.Event e) {
            events.add(e);
            log.add(e.toString());
            String bad = verifier.check(e);
            if (bad != null) errors.add(e + "：" + bad);
            int m = e.masked();
            if ((e.flags & InjectStream.FLAG_NO_FOCUS_CHANGE) != 0 && m != InjectStream.ACTION_DOWN
                    && m != InjectStream.ACTION_POINTER_DOWN) {
                errors.add(e + "：只有按下才该带 NO_FOCUS_CHANGE");
            }
        }
    }

    static InjectStream.Event ev(int action, int[] ids, int flags) {
        return new InjectStream.Event(action, ids, new float[ids.length], new float[ids.length], flags, 1, 1);
    }

    /** 校验器本身要能认出坏流，不然"全过校验"说明不了什么。 */
    static void verifierCatchesBadStreams() {
        Verifier v = new Verifier();
        check(v.check(ev(InjectStream.ACTION_MOVE, new int[]{0}, 0)) != null, "校验器：没按下就 MOVE 被拒");
        check(v.check(ev(InjectStream.ACTION_DOWN, new int[]{0}, 0)) == null, "校验器：DOWN 通过");
        check(v.check(ev(InjectStream.ACTION_DOWN, new int[]{1}, 0)) != null, "校验器：已有手指时再 DOWN 被拒");
        check(v.check(ev(InjectStream.ACTION_POINTER_DOWN, new int[]{0}, 0)) != null, "校验器：POINTER_DOWN 个数不对被拒");
        check(v.check(ev(InjectStream.ACTION_MOVE, new int[]{0, 1}, 0)) != null, "校验器：MOVE 多带一根被拒");
        check(v.check(ev(InjectStream.ACTION_CANCEL, new int[]{0}, 0)) != null, "校验器：CANCEL 没带 FLAG_CANCELED 被拒");
        check(v.check(ev(InjectStream.ACTION_UP, new int[]{1}, 0)) != null, "校验器：UP 的手指不对被拒");
        check(v.check(ev(InjectStream.ACTION_CANCEL, new int[]{0}, InjectStream.FLAG_CANCELED)) == null, "校验器：带标志的 CANCEL 通过");
        check(v.down.isEmpty(), "校验器：CANCEL 后没有按着的");
    }

    static void streamSingleFinger() {
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        s.down(2, 10, 20, 100);
        s.move(2, 15, 25);
        s.frame(110);
        s.up(2, 120);
        eq(k.log, list("DOWN[2]", "MOVE[2]", "UP[2]"), "单指");
        eq(k.errors, list(), "单指过校验");
        InjectStream.Event down = k.events.get(0);
        eq(down.action, InjectStream.ACTION_DOWN, "DOWN 的动作码");
        eq(down.downTime, 100L, "downTime = 第一个 DOWN 的时间");
        InjectStream.Event mv = k.events.get(1);
        check(mv.xs[0] == 15f && mv.ys[0] == 25f && mv.eventTime == 110 && mv.downTime == 100, "MOVE 的坐标和时间");
        eq(k.events.get(2).eventTime, 120L, "UP 的时间");
        eq(s.count(), 0, "抬起后没有按着的");
    }

    static void streamTwoFingersInterleaved() {
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        s.down(3, 0, 0, 1);
        s.down(1, 0, 0, 2);   // 编号比已按着的小：排在前面，下标 0
        s.down(7, 0, 0, 3);   // 下标 2
        s.up(3, 4);           // [1,3,7] 里的下标 1
        s.up(1, 5);
        s.up(7, 6);
        eq(k.log, list("DOWN[3]", "POINTER_DOWN#0[1,3]", "POINTER_DOWN#2[1,3,7]", "POINTER_UP#1[1,3,7]",
                "POINTER_UP#0[1,7]", "UP[7]"), "多指交错按下、抬起");
        eq(k.events.get(1).action, InjectStream.ACTION_POINTER_DOWN, "POINTER_DOWN 下标 0 的动作码");
        eq(k.events.get(2).action, InjectStream.ACTION_POINTER_DOWN | (2 << 8), "POINTER_DOWN 下标 2 的动作码 = 5 | 2 << 8");
        eq(k.events.get(3).action, InjectStream.ACTION_POINTER_UP | (1 << 8), "POINTER_UP 下标 1 的动作码 = 6 | 1 << 8");
        eq(k.errors, list(), "多指过校验");
        for (InjectStream.Event e : k.events) check(e.downTime == 1, "整条流 downTime 不变（" + e + "）");
    }

    static void streamCancelVoidsUntilUp() {
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        s.down(0, 1, 1, 10);
        s.down(4, 2, 2, 11);
        s.cancelAll(12);
        check(s.isVoided(0) && s.isVoided(4), "取消后两根都作废");
        s.move(0, 5, 5);
        s.frame(13);
        s.up(0, 14);
        check(!s.isVoided(0), "抬起后不再作废");
        s.down(2, 0, 0, 15);   // 4 还作废着、没抬起：新手指开一条新流
        s.up(4, 16);
        s.up(2, 17);
        eq(k.log, list("DOWN[0]", "POINTER_DOWN#1[0,4]", "CANCEL[0,4] canceled", "DOWN[2]", "UP[2]"),
                "取消后作废的手指不再注入，新手指开新流");
        eq(k.events.get(2).flags, InjectStream.FLAG_CANCELED, "CANCEL 带 FLAG_CANCELED");
        eq(k.events.get(3).downTime, 15L, "新流的 downTime 重新算");
        eq(k.errors, list(), "取消过校验");
    }

    static void streamCancelIdempotent() {
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        s.cancelAll(1);
        s.down(0, 0, 0, 2);
        s.cancelAll(3);
        s.cancelAll(4);
        s.up(0, 5);
        s.cancelAll(6);
        eq(k.log, list("DOWN[0]", "CANCEL[0] canceled"), "没按着时取消什么都不发，重复取消只发一次");
        eq(k.errors, list(), "过校验");
    }

    static void streamMoveOnlyWhenChanged() {
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        s.frame(1);          // 没手指
        s.move(5, 1, 1);     // 没按着的手指
        s.frame(2);
        s.down(0, 1, 1, 3);
        s.move(0, 1, 1);     // 位置没变
        s.frame(4);
        s.down(0, 9, 9, 5);  // 重复按下不理
        s.move(0, 2, 1);
        s.up(0, 6);          // 没 frame 就抬起：先把移动发掉
        eq(k.log, list("DOWN[0]", "MOVE[0]", "UP[0]"), "只在真动过时发 MOVE，抬起前补发没发的移动");
        eq(k.errors, list(), "过校验");
    }

    static void streamFuzzPassesVerifier() {
        Random r = new Random(20261029);
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        boolean[] held = new boolean[MtDecoder.SLOTS];
        long t = 0;
        for (int i = 0; i < 20000; i++) {
            int slot = r.nextInt(MtDecoder.SLOTS);
            t++;
            switch (r.nextInt(6)) {
                case 0:
                    if (!held[slot]) {
                        held[slot] = true;
                        s.down(slot, r.nextInt(1000), r.nextInt(2000), t);
                    }
                    break;
                case 1:
                case 2:
                    if (held[slot]) s.move(slot, r.nextInt(1000), r.nextInt(2000));
                    break;
                case 3:
                    s.frame(t);
                    break;
                case 4:
                    if (held[slot]) {
                        held[slot] = false;
                        s.up(slot, t);
                    }
                    break;
                default:
                    if (r.nextInt(20) == 0) s.cancelAll(t);
                    break;
            }
        }
        s.cancelAll(t + 1);
        eq(k.errors.size() > 3 ? k.errors.subList(0, 3) : k.errors, list(), "随机 2 万步全过校验");
        check(k.verifier.down.isEmpty(), "最后取消后系统那边没有按着的手指");
        check(k.events.size() > 1000, "随机序列确实发了不少事件（" + k.events.size() + "）");
    }

    // ═══════════ MtDecoder 的取消 ═══════════

    static final class Rec implements MtDecoder.Listener {
        final List<String> log = new ArrayList<>();

        @Override
        public void down(int slot, int x, int y) {
            log.add("down" + slot);
        }

        @Override
        public void move(int slot, int x, int y) {
        }

        @Override
        public void frame() {
        }

        @Override
        public void up(int slot) {
            log.add("up" + slot);
        }

        @Override
        public void cancel(int slot) {
            log.add("cancel" + slot);
        }
    }

    static void abs(MtDecoder d, int code, int value) {
        d.feed(MtDecoder.EV_ABS, code, value);
    }

    static void syn(MtDecoder d) {
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_REPORT, 0);
    }

    static void decoderCancelForPalmAndDrop() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 5);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);
        syn(d);
        eq(r.log, list("down1", "cancel1"), "手指中途变手掌：取消，不是抬起");
        r.log.clear();
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 6);
        syn(d);
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_DROPPED, 0);
        syn(d);
        eq(r.log, list("down0", "cancel0"), "丢数据：取消");
        r.log.clear();
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 7);
        syn(d);
        d.releaseAll();
        eq(r.log, list("down0", "up0"), "外部停止（releaseAll）：抬起");
    }

    /** NO_FOCUS_CHANGE 只加在手势带那根手指自己的 DOWN / POINTER_DOWN 上，别的事件都不带。 */
    static void streamNoFocusOnlyOnDown() {
        Sink k = new Sink();
        InjectStream s = new InjectStream(k);
        s.down(0, 10, 1500, 1, true);     // 豆花窗口里返回带：DOWN 带
        s.down(3, 500, 500, 2, false);    // 另一边：POINTER_DOWN 不带
        s.move(0, 20, 1500);
        s.frame(3);
        s.down(5, 10, 1600, 4, true);     // 又一根在带里：POINTER_DOWN 带
        s.up(3, 5);
        s.up(0, 6);
        s.cancelAll(7);
        eq(k.log, list("DOWN[0] no-focus", "POINTER_DOWN#1[0,3]", "MOVE[0,3]", "POINTER_DOWN#2[0,3,5] no-focus",
                "POINTER_UP#1[0,3,5]", "POINTER_UP#0[0,5]", "CANCEL[5] canceled"), "只有带里那根的按下带 no-focus");
        eq(k.errors, list(), "过校验");
        Sink k2 = new Sink();
        InjectStream s2 = new InjectStream(k2);
        s2.down(2, 500, 500, 1, false);
        s2.up(2, 2);
        eq(k2.log, list("DOWN[2]", "UP[2]"), "窗口外的手指：DOWN 不带（点 Moonlight 照常把焦点给它）");
    }

    /** 系统收下事件后按着的集合、残留记录的读写、清残留时猜的集合。 */
    static void downAfterAndResidueRecord() {
        eq(Arrays.toString(ev(InjectStream.ACTION_DOWN, new int[]{3}, 0).downAfter()), "[3]", "DOWN 后按着 [3]");
        eq(Arrays.toString(ev(InjectStream.ACTION_POINTER_DOWN | (1 << 8), new int[]{1, 3}, 0).downAfter()), "[1, 3]",
                "POINTER_DOWN 后按着全部");
        eq(Arrays.toString(ev(InjectStream.ACTION_MOVE, new int[]{1, 3}, 0).downAfter()), "[1, 3]", "MOVE 不变");
        eq(Arrays.toString(ev(InjectStream.ACTION_POINTER_UP, new int[]{1, 3, 7}, 0).downAfter()), "[3, 7]",
                "POINTER_UP#0 去掉下标 0 那根");
        eq(Arrays.toString(ev(InjectStream.ACTION_POINTER_UP | (2 << 8), new int[]{1, 3, 7}, 0).downAfter()), "[1, 3]",
                "POINTER_UP#2 去掉下标 2 那根");
        eq(Arrays.toString(ev(InjectStream.ACTION_UP, new int[]{3}, 0).downAfter()), "[]", "UP 后一根不剩");
        eq(Arrays.toString(ev(InjectStream.ACTION_CANCEL, new int[]{1, 3}, InjectStream.FLAG_CANCELED).downAfter()), "[]",
                "CANCEL 后一根不剩");

        String rec = InjectStream.formatDown(0, new int[]{0, 4});
        eq(rec, "0 0 4\n", "记录格式");
        eq(Arrays.toString(InjectStream.parseDown(rec, 0)), "[0, 4]", "读回记录");
        check(InjectStream.parseDown(rec, 1) == null, "屏幕不对：不用");
        check(InjectStream.parseDown("", 0) == null && InjectStream.parseDown(null, 0) == null, "空记录：null");
        check(InjectStream.parseDown("0", 0) == null, "只有屏幕号：null");
        check(InjectStream.parseDown("0 x", 0) == null, "读不懂：null");
        check(InjectStream.parseDown("0 16", 0) == null && InjectStream.parseDown("0 -1", 0) == null, "编号越界：null");
        eq(Arrays.toString(InjectStream.parseDown("0 5 1 5\n", 0)), "[1, 5]", "乱序、重复：排好去重");

        // 按记录的集合发 CANCEL：集合正好相等才被收下（两根残留时单根的都被拒，这就是要落盘的原因）
        Verifier v = new Verifier();
        v.check(ev(InjectStream.ACTION_DOWN, new int[]{0}, 0));
        v.check(ev(InjectStream.ACTION_POINTER_DOWN | (1 << 8), new int[]{0, 4}, 0));
        check(v.check(InjectStream.cancelOf(new int[]{0}, 1)) != null, "两根残留：单根 CANCEL 被拒");
        check(v.check(InjectStream.cancelOf(new int[]{4}, 1)) != null, "两根残留：另一根单根也被拒");
        check(v.check(InjectStream.cancelOf(new int[]{0, 4}, 1)) == null, "两根残留：按记录的集合 CANCEL 收下");
        check(v.down.isEmpty(), "清干净了");

        int[][] singles = InjectStream.residueGuesses(false);
        int[][] pairs = InjectStream.residueGuesses(true);
        eq(singles.length, 16, "单根 16 种");
        eq(pairs.length, 120, "两根 120 种");
        TreeSet<String> distinct = new TreeSet<>();
        boolean sorted = true;
        for (int[] p : pairs) {
            distinct.add(Arrays.toString(p));
            if (p.length != 2 || p[0] >= p[1] || p[0] < 0 || p[1] > 15) sorted = false;
        }
        check(sorted && distinct.size() == 120, "两根组合：编号从小到大、不重复");
        Verifier v2 = new Verifier();
        v2.check(ev(InjectStream.ACTION_DOWN, new int[]{6}, 0));
        v2.check(ev(InjectStream.ACTION_POINTER_DOWN, new int[]{2, 6}, 0));
        int accepted = 0;
        for (int[] g : singles) if (v2.check(InjectStream.cancelOf(g, 1)) == null) accepted++;
        for (int[] g : pairs) if (v2.check(InjectStream.cancelOf(g, 1)) == null) accepted++;
        eq(accepted, 1, "没有记录、两根残留：猜到那一对、只收下一次");
    }

    /** 独占前就按着的手指：开始读之后动了 / 抬起了就认出来；读之后才按下的（不管同一帧里坐标和编号谁先到）不算。 */
    static void decoderSeesPreGrabFinger() {
        MtDecoder d = new MtDecoder(new Harness());
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 5);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 100);
        syn(d);
        check(!d.sawPreGrab(), "新按下的（编号先到）：不算");
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 300);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 6);
        syn(d);
        check(!d.sawPreGrab(), "新按下的（同一帧里坐标先到）：不算");
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_DROPPED, 0);
        syn(d);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 120);
        syn(d);
        check(!d.sawPreGrab(), "丢过数据后的旧手指：不算（开头是开始读之后看到的）");
        abs(d, MtDecoder.ABS_MT_SLOT, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 900);
        syn(d);
        check(d.sawPreGrab(), "从没见过开头的 slot 上来了坐标：独占前就按着");
        syn(d);
        check(d.sawPreGrab(), "一旦认出就一直为真");

        MtDecoder lift = new MtDecoder(new Harness());
        abs(lift, MtDecoder.ABS_MT_SLOT, 3);
        abs(lift, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(lift);
        check(lift.sawPreGrab(), "从没见过开头的 slot 上抬起：独占前就按着");

        MtDecoder major = new MtDecoder(new Harness());
        abs(major, 0x30, 12);   // ABS_MT_TOUCH_MAJOR：slot 还是开头的 0
        syn(major);
        check(major.sawPreGrab(), "没切 slot 就来了接触面大小：独占前就按着（当前 slot 按 0 算）");

        MtDecoder quiet = new MtDecoder(new Harness());
        syn(quiet);
        check(!quiet.sawPreGrab(), "什么都没来：不算");
    }

    /** 认出的独占前手指第一个事件就是抬起（日志用）；全屏时清掉标记；独占前读过的那一路告诉解码器当前 slot。 */
    static void decoderPreGrabLiftClearAndSlot() {
        MtDecoder lift = new MtDecoder(new Harness());
        abs(lift, MtDecoder.ABS_MT_SLOT, 3);
        abs(lift, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(lift);
        check(lift.sawPreGrab() && lift.sawPreGrabLift(), "第一个事件就是抬起：认出、而且记下抬起已经被吞了");
        lift.clearSawPreGrab();
        check(!lift.sawPreGrab() && !lift.sawPreGrabLift(), "清掉后不再为真（全屏时认出的不留到分屏）");
        abs(lift, MtDecoder.ABS_MT_SLOT, 3);
        abs(lift, MtDecoder.ABS_MT_POSITION_X, 5);
        syn(lift);
        check(!lift.sawPreGrab(), "同一个 slot 已经认过，不再算");

        MtDecoder moved = new MtDecoder(new Harness());
        abs(moved, MtDecoder.ABS_MT_SLOT, 1);
        abs(moved, MtDecoder.ABS_MT_POSITION_X, 5);
        abs(moved, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(moved);
        check(moved.sawPreGrab() && !moved.sawPreGrabLift(), "先动了再抬起：认出，但第一个事件不是抬起");

        // 独占前那一路看到内核切到了 slot 2：之后不带 ABS_MT_SLOT 的新手指算在 2 上
        List<String> got = new ArrayList<>();
        MtDecoder d = new MtDecoder(new MtDecoder.Listener() {
            @Override public void down(int slot, int x, int y) { got.add("down" + slot); }
            @Override public void move(int slot, int x, int y) { }
            @Override public void frame() { }
            @Override public void up(int slot) { got.add("up" + slot); }
        });
        d.setSlot(2);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 9);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 10);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        eq(got.toString(), "[down2, up2]", "setSlot：之后的事件按那个 slot 算");
        check(!d.sawPreGrab(), "setSlot 之后按下的新手指不算独占前");
    }

    static void watchAbs(LiftWatch w, int code, int value, long now) {
        w.feed(MtDecoder.EV_ABS, code, value, now * 1000, now);
    }

    /** 不独占地读着的那一路：见过按下没抬起 = 按着；开头没看到但在动 = 按着；丢数据 = 不知道；切 slot 记着。 */
    static void liftWatchKernelSide() {
        LiftWatch w = new LiftWatch();
        check(!w.anyDown(), "什么都没来：没有按着的");
        eq(w.lastEventUs(), Long.MIN_VALUE, "没有事件时时间是最小值");
        watchAbs(w, MtDecoder.ABS_MT_SLOT, 1, 10);
        watchAbs(w, MtDecoder.ABS_MT_TRACKING_ID, 7, 10);
        check(w.anyDown(), "看到按下：按着");
        eq(w.slot(), 1, "记着当前 slot");
        eq(w.lastEventUs(), 10_000L, "记着最后一个事件的内核时间");
        watchAbs(w, MtDecoder.ABS_MT_TRACKING_ID, -1, 20);
        check(!w.anyDown(), "抬起了：没有按着的");

        watchAbs(w, MtDecoder.ABS_MT_SLOT, 4, 30);
        watchAbs(w, MtDecoder.ABS_MT_POSITION_Y, 300, 30);
        check(w.anyDown(), "开着之前就按下、还在动：按着");
        w.feed(MtDecoder.EV_SYN, MtDecoder.SYN_DROPPED, 0, 40_000, 40);
        check(!w.anyDown(), "丢过数据：全回到不知道");
        watchAbs(w, MtDecoder.ABS_MT_TRACKING_ID, -1, 50);
        check(!w.anyDown(), "不知道的抬起了：没有按着的");

        LiftWatch q = new LiftWatch();
        watchAbs(q, MtDecoder.ABS_MT_TRACKING_ID, 3, 100);
        check(!q.forgetIfQuiet(100 + LiftWatch.FORGET_AFTER_MS - 1), "按着、不到 3 秒没动静：不忘");
        check(q.forgetIfQuiet(100 + LiftWatch.FORGET_AFTER_MS), "按着、3 秒没动静：改回不知道（交给 dumpsys 那一道）");
        check(!q.anyDown(), "忘掉后没有按着的");
        check(!new LiftWatch().forgetIfQuiet(1_000_000), "没有按着的：不用忘");
    }

    /** dumpsys 报按着：150ms 起翻倍封顶 1 秒；等够 1.5 秒、内核那边 1 秒没动静、数没变才当旧状态。 */
    static void liftWatchBackoffAndStale() {
        LiftWatch w = new LiftWatch();
        eq(w.onPressed(0, 0), 0L, "没按着：接管");
        eq(w.onPressed(-1, 0), 0L, "读不出来：不拦");
        long t = 1000;
        eq(w.onPressed(1, t), 150L, "第一次等 150ms");
        eq(w.onPressed(1, t += 150), 300L, "翻倍");
        eq(w.onPressed(1, t += 300), 600L, "再翻倍");
        eq(w.onPressed(1, t += 600), 1000L, "封顶 1 秒");
        eq(t, 2050L, "这时已经等了 1050ms");
        eq(w.onPressed(1, t += 500), LiftWatch.STALE, "等够 1.5 秒、内核那边一直没动静：当旧状态");
        w.stopWaiting();
        eq(w.onPressed(1, t += 10), 150L, "停了再等：从头计时、从 150ms 起");

        LiftWatch busy = new LiftWatch();
        long u = 0;
        busy.onPressed(2, u);
        for (int i = 0; i < 20; i++) {
            u += 300;
            watchAbs(busy, MtDecoder.ABS_MT_POSITION_X, i, u);   // 内核那边一直有动静（另一边应用上在拖）
            check(busy.onPressed(2, u) > 0, "内核那边一直有动静：一直等（第 " + i + " 轮）");
        }
        LiftWatch changed = new LiftWatch();
        long v = 0;
        changed.onPressed(1, v);
        changed.onPressed(1, v += 1400);
        check(changed.onPressed(2, v += 200) > 0, "dumpsys 的数变了：算有动静，不当旧状态");
        check(changed.onPressed(2, v += 1000) == LiftWatch.STALE, "之后 1 秒没再变：当旧状态");

        LiftWatch real = new LiftWatch();
        watchAbs(real, MtDecoder.ABS_MT_TRACKING_ID, 5, 0);   // 内核那边看到按下没抬起
        real.onPressed(1, 0);
        check(real.onPressed(1, 5000) > 0, "内核那边见过按下没抬起：再安静也不当旧状态");
    }

    // ═══════════ 整条路：MtDecoder → TouchSplit → InjectStream（照 Session 的接法） ═══════════

    /** 照 Session：归系统的走注入流，其余记成 router；停的时候先 onStop（取消），再 releaseAll（抬起）。 */
    static final class Harness implements MtDecoder.Listener {
        final Sink sink = new Sink();
        final InjectStream stream = new InjectStream(sink);
        final TouchSplit split = new TouchSplit();
        final MtDecoder decoder = new MtDecoder(this);
        final List<String> router = new ArrayList<>();
        final int[] x = new int[MtDecoder.SLOTS];
        final int[] y = new int[MtDecoder.SLOTS];
        TouchSplit.Window window = BOTTOM;
        /** 假装豆花在 x 是 3 的倍数的地方有键 / 按钮（手势带里的点据此归豆花）；false = 哪儿都没有。 */
        boolean keysEverywhereThird;
        long t;

        @Override
        public void down(int slot, int px, int py) {
            int before = sink.events.size();
            int to = split.down(slot, px, py, window, keysEverywhereThird && px % 3 == 0);
            if (to != TouchSplit.ROUTER) {
                stream.down(slot, px, py, ++t, to == TouchSplit.SYSTEM_IN_WINDOW);
                // 只有手势带那根的按下带 NO_FOCUS_CHANGE
                if (sink.events.size() > before) {
                    InjectStream.Event e = sink.events.get(sink.events.size() - 1);
                    boolean flagged = (e.flags & InjectStream.FLAG_NO_FOCUS_CHANGE) != 0;
                    if (flagged != (to == TouchSplit.SYSTEM_IN_WINDOW)) sink.errors.add(e + "：NO_FOCUS_CHANGE 加错了");
                }
            } else {
                router.add("down" + slot);
            }
        }

        @Override
        public void move(int slot, int px, int py) {
            if (split.isSystem(slot)) stream.move(slot, px, py);
        }

        @Override
        public void frame() {
            stream.frame(++t);
        }

        @Override
        public void up(int slot) {
            if (split.up(slot)) stream.up(slot, ++t);
            else router.add("up" + slot);
        }

        @Override
        public void cancel(int slot) {
            if (split.up(slot)) stream.cancelAll(++t);
            else router.add("up" + slot);
        }

        void stop() {
            stream.cancelAll(++t);
            decoder.releaseAll();
        }

        void finger(int slot, int id, int px, int py) {
            abs(decoder, MtDecoder.ABS_MT_SLOT, slot);
            abs(decoder, MtDecoder.ABS_MT_TRACKING_ID, id);
            if (id >= 0) {
                abs(decoder, MtDecoder.ABS_MT_POSITION_X, px);
                abs(decoder, MtDecoder.ABS_MT_POSITION_Y, py);
            }
        }
    }

    static void harnessPalmReplacedStop() {
        Harness h = new Harness();
        h.finger(0, 1, 500, 500);    // 另一边
        h.finger(1, 2, 500, 1500);   // 豆花
        h.finger(2, 3, 10, 1500);    // 左边返回带（豆花窗口里：按下不挪焦点）
        syn(h.decoder);
        h.finger(0, 1, 520, 600);
        syn(h.decoder);
        h.finger(0, 9, 100, 100);    // 同一帧里 slot 0 换了一根手指（还在另一边）
        syn(h.decoder);
        abs(h.decoder, MtDecoder.ABS_MT_SLOT, 2);
        abs(h.decoder, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);   // 返回带那根变手掌
        syn(h.decoder);
        h.finger(0, 9, 120, 120);    // 作废的手指还在动：不再注入
        syn(h.decoder);
        h.finger(3, 4, 700, 300);    // 新手指按在另一边：新流
        syn(h.decoder);
        h.stop();                    // 释放独占：先取消再抬起，系统那边不会收到 UP（= 点击）
        eq(h.sink.log, list("DOWN[0]", "POINTER_DOWN#1[0,2] no-focus", "MOVE[0,2]", "POINTER_UP#0[0,2]", "POINTER_DOWN#0[0,2]",
                "CANCEL[0,2] canceled", "DOWN[3]", "CANCEL[3] canceled"), "替换 slot、手掌、释放独占");
        eq(h.router, list("down1", "up1"), "豆花那根照常给 router，停的时候抬起");
        eq(h.sink.errors, list(), "过校验");
        check(h.sink.verifier.down.isEmpty(), "停了以后系统那边没有按着的注入手指");
        check(!h.sink.log.contains("UP[3]") && !h.sink.log.contains("UP[0]"), "停的时候没发抬起（= 点击）");
    }

    static void harnessFuzzWithDecoder() {
        Random r = new Random(1029);
        Harness h = new Harness();
        h.keysEverywhereThird = true;
        int[] tracking = new int[MtDecoder.SLOTS];
        Arrays.fill(tracking, -1);
        int nextId = 1;
        for (int round = 0; round < 5000; round++) {
            int changes = 1 + r.nextInt(3);
            for (int c = 0; c < changes; c++) {
                int slot = r.nextInt(6);
                int k = r.nextInt(10);
                if (k < 2) {
                    tracking[slot] = nextId++;   // 按下（或同一帧换手指）
                    h.finger(slot, tracking[slot], r.nextInt(1000), r.nextInt(2000));
                } else if (k < 4) {
                    if (tracking[slot] >= 0) {
                        tracking[slot] = -1;
                        h.finger(slot, -1, 0, 0);
                    }
                } else if (k < 9) {
                    if (tracking[slot] >= 0) h.finger(slot, tracking[slot], r.nextInt(1000), r.nextInt(2000));
                } else if (r.nextInt(8) == 0) {
                    abs(h.decoder, MtDecoder.ABS_MT_SLOT, slot);
                    abs(h.decoder, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);
                } else if (r.nextInt(30) == 0) {
                    h.decoder.feed(MtDecoder.EV_SYN, MtDecoder.SYN_DROPPED, 0);
                }
            }
            if (r.nextInt(200) == 0) h.window = h.window == BOTTOM ? TouchSplit.Window.FULL : BOTTOM;
            syn(h.decoder);
        }
        h.stop();
        eq(h.sink.errors.size() > 3 ? h.sink.errors.subList(0, 3) : h.sink.errors, list(), "随机触摸 5000 帧全过校验");
        check(h.sink.verifier.down.isEmpty(), "停了以后系统那边没有按着的注入手指");
        check(h.sink.events.size() > 500, "随机触摸确实注入了不少事件（" + h.sink.events.size() + "）");
    }

    // ═══════════ 工具 ═══════════

    static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("失败：" + what);
        }
    }

    static void eq(Object actual, Object expected, String what) {
        check(expected.equals(actual), what + "（期望 " + expected + "，实际 " + actual + "）");
    }
}
