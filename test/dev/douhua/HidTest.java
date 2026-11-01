package dev.douhua;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 连续滚动、惯性滚动里不依赖 Android 的部分（tools/test.sh 直接跑）：
 * 带分辨率倍率的鼠标描述符（用一个小 HID 项解析器逐个字段比：输入报文和旧描述符一模一样、Feature 报告的样子对）、
 * 倍率字节的编解码、滚轮单位 → 计数的换算和拆帧（Hid.WheelOut）、CMD_CONFIG 编解码往返（ConfigCodec）、惯性的物理（ScrollInertia）。
 * 这些只证明代码照设计跑，证明不了主机认不认、手感对不对。
 */
public final class HidTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        hiresInputUnchanged();
        hiresFeatureShape();
        noFourByteItems();
        combinedIds();
        multiplierBytes();
        wheelOutGridSameAsBefore();
        wheelOutHires();
        wheelOutLowResSmooth();
        wheelOutSplitsFrames();
        wheelOutSkipsRemainderOnly();
        configRoundTrip();
        inertiaLevels();
        inertiaClosedForm();
        inertiaTable();
        System.err.println("滚轮倍率 / 惯性物理：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 小 HID 解析器 ═══════════

    /** 一个报告字段：kind 是 I（Input）/ O / F（Feature），usage = 用法页 << 16 | 用法（常量补齐是 -1）。 */
    static final class Field {
        char kind;
        int reportId, usage, offset, bits, logMin, logMax, phyMin, phyMax;
        /** 最里层的集合：类型（0 Physical、1 Application、2 Logical）和编号（每开一个集合加一）。 */
        int collType, collIndex;

        String input() {
            return kind + " id" + reportId + " u" + Integer.toHexString(usage) + " @" + offset + "+" + bits
                    + " [" + logMin + "," + logMax + "] p[" + phyMin + "," + phyMax + "]";
        }
    }

    static final class Parsed {
        final List<Field> fields = new ArrayList<>();
        boolean sizeCode3;
        /** 每种报告（kind + id）的总位数。 */
        final Map<String, Integer> bits = new HashMap<>();
        int openCollections;
    }

    static Parsed parse(byte[] d) {
        Parsed out = new Parsed();
        int page = 0, logMin = 0, logMax = 0, phyMin = 0, phyMax = 0, size = 0, count = 0, id = 0;
        List<Integer> usages = new ArrayList<>();
        int umin = -1, umax = -1;
        Deque<int[]> coll = new ArrayDeque<>();
        int collCounter = 0;
        int i = 0;
        while (i < d.length) {
            int prefix = d[i] & 0xFF;
            int bSize = prefix & 3;
            int len = bSize == 3 ? 4 : bSize;
            if (bSize == 3) out.sizeCode3 = true;
            int type = (prefix >> 2) & 3;
            int tag = prefix >> 4;
            int udata = 0;
            for (int k = 0; k < len; k++) udata |= (d[i + 1 + k] & 0xFF) << (8 * k);
            int sdata = len == 1 ? (byte) udata : len == 2 ? (short) udata : udata;
            i += 1 + len;
            if (type == 0) {   // Main
                if (tag == 0xA) {
                    coll.push(new int[]{udata, collCounter++});
                    out.openCollections++;
                } else if (tag == 0xC) {
                    coll.pop();
                    out.openCollections--;
                } else if (tag == 8 || tag == 9 || tag == 0xB) {
                    char kind = tag == 8 ? 'I' : tag == 9 ? 'O' : 'F';
                    boolean constant = (udata & 1) != 0;
                    String key = kind + "" + id;
                    int offset = out.bits.containsKey(key) ? out.bits.get(key) : 0;
                    for (int c = 0; c < count; c++) {
                        Field f = new Field();
                        f.kind = kind;
                        f.reportId = id;
                        if (constant) {
                            f.usage = -1;
                        } else if (!usages.isEmpty()) {
                            f.usage = usages.get(Math.min(c, usages.size() - 1));
                        } else if (umin >= 0) {
                            f.usage = (page << 16) | Math.min(umin + c, umax);
                        } else {
                            f.usage = -1;
                        }
                        f.offset = offset;
                        f.bits = size;
                        f.logMin = logMin;
                        f.logMax = logMax;
                        f.phyMin = phyMin;
                        f.phyMax = phyMax;
                        int[] top = coll.peek();
                        f.collType = top == null ? -1 : top[0];
                        f.collIndex = top == null ? -1 : top[1];
                        out.fields.add(f);
                        offset += size;
                    }
                    out.bits.put(key, offset);
                }
                usages.clear();
                umin = umax = -1;
            } else if (type == 1) {   // Global
                switch (tag) {
                    case 0: page = udata; break;
                    case 1: logMin = sdata; break;
                    case 2: logMax = sdata; break;
                    case 3: phyMin = sdata; break;
                    case 4: phyMax = sdata; break;
                    case 7: size = udata; break;
                    case 8: id = udata; break;
                    case 9: count = udata; break;
                    default: break;
                }
            } else if (type == 2) {   // Local
                if (tag == 0) usages.add(len == 4 ? udata : (page << 16) | udata);
                else if (tag == 1) umin = udata;
                else if (tag == 2) umax = udata;
            }
        }
        return out;
    }

    static List<String> inputs(Parsed p) {
        List<String> l = new ArrayList<>();
        for (Field f : p.fields) if (f.kind == 'I') l.add(f.input());
        return l;
    }

    static List<Field> fieldsOf(Parsed p, char kind) {
        List<Field> l = new ArrayList<>();
        for (Field f : p.fields) if (f.kind == kind) l.add(f);
        return l;
    }

    static final int GD = 0x01 << 16, WHEEL = GD | 0x38, RES_MULT = GD | 0x48, AC_PAN = (0x0C << 16) | 0x238;

    // ═══════════ 描述符 ═══════════

    static void hiresInputUnchanged() {
        Parsed old = parse(Hid.MOUSE_DESC), hi = parse(Hid.MOUSE_DESC_HIRES);
        eq(inputs(hi), inputs(old), "带倍率的鼠标描述符：每个输入字段（用法、位置、位数、逻辑 / 物理范围）和旧的一样");
        eq(hi.bits.get("I0"), Hid.MOUSE_REPORT_SIZE * 8, "输入报文还是 7 字节");
        eq(hi.openCollections, 0, "集合都闭合了");
        Parsed oldC = parse(Hid.combinedDescriptor(true, false)), hiC = parse(Hid.combinedDescriptor(true, true));
        eq(inputs(hiC), inputs(oldC), "蓝牙合并描述符：带不带倍率，所有输入报告（键盘、鼠标、媒体键、绝对坐标）一样");
        eq(inputs(parse(Hid.combinedDescriptor())), inputs(parse(Hid.combinedDescriptor(false, false))), "不带参数的合并描述符照旧");
        eq(fieldsOf(oldC, 'F').size(), 0, "旧的合并描述符没有 Feature 报告");
    }

    static void hiresFeatureShape() {
        Parsed hi = parse(Hid.MOUSE_DESC_HIRES);
        List<Field> feat = fieldsOf(hi, 'F');
        eq(hi.bits.get("F0"), Hid.FEATURE_REPORT_SIZE * 8, "Feature 报告 1 字节");
        List<Field> mult = new ArrayList<>();
        for (Field f : feat) if (f.usage == RES_MULT) mult.add(f);
        eq(mult.size(), 2, "两个倍率字段（竖、横）");
        for (Field f : mult) {
            check(f.bits == 2 && f.logMin == 0 && f.logMax == 1 && f.phyMin == 1 && f.phyMax == Hid.RES_MULT,
                    "倍率字段 2 位、逻辑 0..1、物理 1..8：" + f.input());
            check(f.collType == 2, "倍率在 Logical 集合里");
        }
        Field wheel = null, pan = null;
        for (Field f : fieldsOf(hi, 'I')) {
            if (f.usage == WHEEL) wheel = f;
            if (f.usage == AC_PAN) pan = f;
        }
        check(wheel != null && pan != null, "有竖滚轮和 AC Pan");
        eq(mult.get(0).collIndex, wheel.collIndex, "竖滚轮和第一个倍率在同一个 Logical 集合里");
        eq(mult.get(1).collIndex, pan.collIndex, "AC Pan 和第二个倍率在同一个 Logical 集合里");
        check(wheel.collIndex != pan.collIndex, "两个倍率各管各的");
        eq(mult.get(0).offset, 0, "竖倍率在位 0–1");
        eq(mult.get(1).offset, 2, "横倍率在位 2–3");
        check(wheel.phyMin == 0 && wheel.phyMax == 0 && pan.phyMin == 0 && pan.phyMax == 0, "倍率后面的输入项物理范围复位成 0");
    }

    /** 蓝牙栈注册时 check_descriptor 把尺寸码 3（4 字节数据）当 3 字节走：描述符里一个都不能有。 */
    static void noFourByteItems() {
        check(!parse(Hid.MOUSE_DESC_HIRES).sizeCode3, "带倍率的鼠标描述符没有 4 字节数据的项");
        check(!parse(Hid.combinedDescriptor(true, true)).sizeCode3, "蓝牙合并描述符（带倍率）没有 4 字节数据的项");
        check(!parse(Hid.combinedDescriptor(true, false)).sizeCode3, "蓝牙合并描述符（不带）没有 4 字节数据的项");
    }

    static void combinedIds() {
        Parsed p = parse(Hid.combinedDescriptor(true, true));
        boolean featureOnMouse = false;
        for (Field f : fieldsOf(p, 'F')) {
            check(f.reportId == Hid.REPORT_MOUSE, "Feature 报告带鼠标的报告编号（和输入报告同号不同类型）");
            featureOnMouse = true;
        }
        check(featureOnMouse, "合并描述符里有鼠标的 Feature 报告");
        eq(Hid.combinedDescriptor(true, true).length - Hid.combinedDescriptor(true, false).length,
                Hid.MOUSE_DESC_HIRES.length - Hid.MOUSE_DESC.length, "合并描述符只多了倍率那几项");
    }

    static void multiplierBytes() {
        eq(Hid.multiplierByte(1, 1), 0, "都 ×1 = 0");
        eq(Hid.multiplierByte(8, 8), 0x05, "都 ×8 = 位 0、位 2");
        eq(Hid.multiplierByte(8, 1), 0x01, "只竖 ×8");
        eq(Hid.multiplierV(0x05) * 10 + Hid.multiplierH(0x05), 88, "0x05 → 竖横都 ×8");
        eq(Hid.multiplierV(0x04) * 10 + Hid.multiplierH(0x04), 18, "0x04 → 竖 ×1、横 ×8");
        eq(Hid.multiplierV(0x02), 1, "超出逻辑范围的值（2）当 ×1");
        eq(Hid.featureByte(new byte[]{5}, Hid.REPORT_MOUSE), 5, "蓝牙设报告：不带编号的一个字节");
        eq(Hid.featureByte(new byte[]{(byte) Hid.REPORT_MOUSE, 5}, Hid.REPORT_MOUSE), 5, "蓝牙设报告：还带着编号的两个字节");
        eq(Hid.featureByte(new byte[]{1}, -1), 1, "uhid 设报告：没有编号");
        eq(Hid.featureByte(new byte[]{0, 5}, -1), 5, "uhid 设报告：内核在前面垫了编号字节 0（__hid_request）");
        eq(Hid.featureByte(new byte[]{0}, -1), 0, "uhid 设报告：一个字节就是 0");
        eq(Hid.featureByte(new byte[0], Hid.REPORT_MOUSE), -1, "空数据认不出");
        eq(Hid.featureByte(null, -1), -1, "null 认不出");
    }

    // ═══════════ 滚轮单位 → 计数 ═══════════

    /** 记下 WheelOut 发出的每一帧（7 字节报文转成字符串）。 */
    static final class Frames implements Hid.ReportSink<RuntimeException> {
        final List<String> frames = new ArrayList<>();

        @Override
        public void send(byte[] report) {
            frames.add(Arrays.toString(report));
        }

        int wheel() {
            int sum = 0;
            for (String f : frames) sum += Integer.parseInt(f.replaceAll("[\\[\\] ]", "").split(",")[5]);
            return sum;
        }

        int pan() {
            int sum = 0;
            for (String f : frames) sum += Integer.parseInt(f.replaceAll("[\\[\\] ]", "").split(",")[6]);
            return sum;
        }
    }

    static String oldReport(int buttons, int dx, int dy, int wheel, int pan) {
        byte[] r = new byte[Hid.MOUSE_REPORT_SIZE];
        Hid.mouseReport(r, buttons, dx, dy, wheel, pan);
        return Arrays.toString(r);
    }

    static void wheelOutGridSameAsBefore() {
        Hid.WheelOut w = new Hid.WheelOut();
        Frames f = new Frames();
        byte[] r = new byte[Hid.MOUSE_REPORT_SIZE];
        int[][] cases = {{1, 0, 0, 1, 0}, {0, 0, 0, -3, 0}, {0, 0, 0, 0, 2}, {1, 5, -7, 0, 0}, {0, 0, 0, 0, 0}, {3, 0, 0, 2, -1}};
        List<String> expect = new ArrayList<>();
        long t = 1000;
        for (int[] c : cases) {
            w.mouse(r, c[0], c[1], c[2], c[3] * Hid.WHEEL_UNIT, c[4] * Hid.WHEEL_UNIT, t += 16, f);
            expect.add(oldReport(c[0], c[1], c[2], c[3], c[4]));
        }
        eq(f.frames, expect, "主机没设倍率（×1）、按格滚：每帧逐字节等于以前的 mouseReport");
    }

    static void wheelOutHires() {
        Hid.WheelOut w = new Hid.WheelOut();
        w.setMultipliers(Hid.RES_MULT, Hid.RES_MULT);
        Frames f = new Frames();
        byte[] r = new byte[Hid.MOUSE_REPORT_SIZE];
        w.mouse(r, 0, 0, 0, 2 * Hid.WHEEL_UNIT, 0, 1000, f);
        eq(f.wheel(), 16, "×8：两格 = 16 个计数");
        f.frames.clear();
        for (int i = 0; i < 10; i++) w.mouse(r, 0, 0, 0, 20, 0, 1016 + i * 16, f);   // 每帧 1/6 格
        eq(f.wheel(), 13, "×8：10 帧各 20 单位 = 200 单位 = 13 个计数（余 5）");
        f.frames.clear();
        w.mouse(r, 0, 0, 0, 10, 0, 1200, f);
        eq(f.wheel(), 1, "余数接着用：5 + 10 = 15 单位 = 1 个计数");
        f.frames.clear();
        w.mouse(r, 0, 0, 0, 14, 0, 1216, f);
        w.mouse(r, 0, 0, 0, -14, 0, 1232, f);
        eq(f.wheel(), 0, "换向时余数清掉：+14 再 -14 一个计数都不出");
        f.frames.clear();
        w.mouse(r, 0, 0, 0, 14, 0, 1248, f);
        w.mouse(r, 0, 0, 0, 14, 0, 1248 + Hid.WheelOut.REMAINDER_RESET_MS, f);
        eq(f.wheel(), 0, "停了 300ms 余数清掉");
        eq(w.featureByte(), 0x05, "主机来问：回 0x05");
        w.reset();
        eq(w.featureByte(), 0, "复位后 ×1");
        f.frames.clear();
        w.mouse(r, 0, 0, 0, Hid.WHEEL_UNIT, -Hid.WHEEL_UNIT, 2000, f);
        eq(f.frames, Arrays.asList(oldReport(0, 0, 0, 1, -1)), "复位后又是一格一个计数");
    }

    static void wheelOutLowResSmooth() {
        Hid.WheelOut w = new Hid.WheelOut();
        Frames f = new Frames();
        byte[] r = new byte[Hid.MOUSE_REPORT_SIZE];
        for (int i = 0; i < 7; i++) w.mouse(r, 0, 0, 0, 20, 0, 1000 + i * 16, f);
        eq(f.wheel(), 1, "主机没设倍率、连续模式：攒满 120 单位才出一格（140 单位 → 1 格）");
        eq(f.frames.size(), 1, "不满一格的帧不发");
    }

    static void wheelOutSplitsFrames() {
        Hid.WheelOut w = new Hid.WheelOut();
        w.setMultipliers(Hid.RES_MULT, Hid.RES_MULT);
        Frames f = new Frames();
        byte[] r = new byte[Hid.MOUSE_REPORT_SIZE];
        w.mouse(r, 1, 4, 0, 20 * Hid.WHEEL_UNIT, 0, 1000, f);   // 20 格 × 8 = 160 计数
        eq(f.frames.size(), 2, "×8 时 20 格拆成两帧");
        eq(f.wheel(), 160, "两帧合计 160 个计数");
        eq(f.frames.get(1), oldReport(1, 0, 0, 33, 0), "只有第一帧带位移，按键每帧都带");
        check(f.frames.get(0).startsWith("[1, 4, 0, 0, 0, 127"), "第一帧带位移、滚轮 127：" + f.frames.get(0));
    }

    static void wheelOutSkipsRemainderOnly() {
        Hid.WheelOut w = new Hid.WheelOut();
        Frames f = new Frames();
        byte[] r = new byte[Hid.MOUSE_REPORT_SIZE];
        w.mouse(r, 1, 0, 0, 0, 0, 1000, f);   // 按下左键
        w.mouse(r, 1, 0, 0, 30, 0, 1016, f);  // 只攒余数、按键没变：不发
        w.mouse(r, 0, 0, 0, 30, 0, 1032, f);  // 余数照攒，但按键变了：要发
        eq(f.frames, Arrays.asList(oldReport(1, 0, 0, 0, 0), oldReport(0, 0, 0, 0, 0)), "只攒余数的帧不发，按键变了的照发");
    }

    // ═══════════ CMD_CONFIG ═══════════

    static void configRoundTrip() throws Exception {
        GestureEngine.Config c = new GestureEngine.Config();
        c.pointerSpeed = 3.5f;
        c.acceleration = 0.25f;
        c.scrollStepDp = 33.3f;
        c.naturalScroll = false;
        c.pinchZoom = false;
        c.edgeScroll = true;
        c.edgeScrollHorizontal = false;
        c.edgeWidthDp = 40;
        c.scrollTicks = GestureEngine.TICKS_OFF;
        c.longPressDrag = false;
        c.longPressMs = 700;
        c.tapToDrag = false;
        c.dragWindowMs = 300;
        c.slowFactor = 0.4f;
        c.texture = true;
        c.inertia = false;
        c.inertiaTauMs = 987;
        c.inertiaMinSpeed = 1.3f;
        c.smoothScroll = true;
        c.stickSpeed = 2.3f;
        c.stickTap = false;
        c.stickInertia = false;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConfigCodec.write(new DataOutputStream(bytes), c, 2.75f);
        byte[] data = bytes.toByteArray();
        eq(data.length, 56, "CMD_CONFIG 负载 56 字节（协议 17 是 40，加了惯性 / 连续滚动 10 字节、小红点 6 字节）");
        GestureEngine.Config d = new GestureEngine.Config();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        float density = ConfigCodec.read(in, d);
        eq(density, 2.75f, "密度");
        eq(in.available(), 0, "正好读完");
        eq(Arrays.asList(d.pointerSpeed, d.acceleration, d.scrollStepDp, d.naturalScroll, d.pinchZoom, d.edgeScroll,
                        d.edgeScrollHorizontal, d.edgeWidthDp, d.scrollTicks, d.longPressDrag, d.longPressMs, d.tapToDrag,
                        d.dragWindowMs, d.slowFactor, d.texture, d.inertia, d.inertiaTauMs, d.inertiaMinSpeed, d.smoothScroll,
                        d.stickSpeed, d.stickTap, d.stickInertia),
                Arrays.asList(c.pointerSpeed, c.acceleration, c.scrollStepDp, c.naturalScroll, c.pinchZoom, c.edgeScroll,
                        c.edgeScrollHorizontal, c.edgeWidthDp, c.scrollTicks, c.longPressDrag, c.longPressMs, c.tapToDrag,
                        c.dragWindowMs, c.slowFactor, c.texture, c.inertia, c.inertiaTauMs, c.inertiaMinSpeed, c.smoothScroll,
                        c.stickSpeed, c.stickTap, c.stickInertia),
                "每一项往返不变");
        GestureEngine.Config def = new GestureEngine.Config();
        check(def.inertia && !def.smoothScroll && def.inertiaMinSpeed == 0.6f
                        && def.inertiaTauMs == ScrollInertia.tauMs(ScrollInertia.DEFAULT_LEVEL),
                "默认：惯性开、连续滚动关、0.6dp/ms 算拨、阻尼 4 档");
        check(def.stickSpeed == 1.0f && def.stickTap && def.stickInertia, "默认：小红点灵敏度 1.0、轻点 = 左键、负惯性开");
    }

    // ═══════════ 惯性的物理 ═══════════

    static void inertiaLevels() {
        eq(ScrollInertia.tauMs(1), 3000, "1 档 τ = 3 秒");
        eq(ScrollInertia.tauMs(ScrollInertia.LEVELS), 250, "10 档 τ = 0.25 秒");
        boolean down = true;
        for (int l = 1; l < ScrollInertia.LEVELS; l++) down &= ScrollInertia.tauMs(l + 1) < ScrollInertia.tauMs(l);
        check(down, "档位越大 τ 越小");
        eq(ScrollInertia.tauMs(0), 3000, "超出范围截到两头（0 → 1 档）");
        eq(ScrollInertia.tauMs(99), 250, "超出范围截到两头（99 → 10 档）");
    }

    /** 闭式解和 1ms 一步的数值积分对得上；分成 16ms 一步推进，总距离和一步到底一样。 */
    static void inertiaClosedForm() {
        for (int level : new int[]{1, 4, 10}) {
            double tau = ScrollInertia.tauMs(level) / 1000.0;
            for (double v0 : new double[]{25, 50, 150}) {
                double v = v0, dist = 0, t = 0;
                while (v > 0) {
                    double dt = 0.0001;
                    double acc = -(v + ScrollInertia.FRICTION) / tau;
                    double nv = v + acc * dt;
                    if (nv <= 0) {
                        double part = v / -acc;
                        dist += v * part / 2;
                        t += part;
                        break;
                    }
                    dist += (v + nv) / 2 * dt;
                    v = nv;
                    t += dt;
                }
                double stop = ScrollInertia.stopSeconds(v0, tau);
                double total = ScrollInertia.distance(v0, tau);
                check(Math.abs(stop - t) < 0.02, String.format("%d 档 %.0f 格/秒：停止时间闭式 %.3f ≈ 积分 %.3f", level, v0, stop, t));
                check(Math.abs(total - dist) < 0.05, String.format("%d 档 %.0f 格/秒：距离闭式 %.2f ≈ 积分 %.2f", level, v0, total, dist));
                double sv = v0, sd = 0;
                int frames = 0;
                while (sv > 0 && frames < 100000) {
                    double[] r = ScrollInertia.advance(sv, 0.016, tau);
                    sv = r[0];
                    sd += r[1];
                    frames++;
                }
                check(Math.abs(sd - total) < 1e-6, String.format("%d 档 %.0f 格/秒：16ms 一步推进的总距离 %.4f = 一步到底 %.4f", level, v0, sd, total));
                check(Math.abs(frames * 0.016 - stop) <= 0.016, "16ms 一步，停在闭式算的那一帧");
            }
        }
        double[] none = ScrollInertia.advance(0, 0.016, 1);
        check(none[0] == 0 && none[1] == 0, "速度 0：不走");
    }

    /** 方案里那张表（默认 4 档）：中拨 50 格/秒约 3.8 秒 / 54 格，最快 150 格/秒约 5.2 秒 / 181 格。 */
    static void inertiaTable() {
        double tau = ScrollInertia.tauMs(4) / 1000.0;
        check(Math.abs(ScrollInertia.stopSeconds(50, tau) - 3.8) < 0.05, "4 档中拨约 3.8 秒：" + ScrollInertia.stopSeconds(50, tau));
        check(Math.abs(ScrollInertia.distance(50, tau) - 54) < 1, "4 档中拨约 54 格：" + ScrollInertia.distance(50, tau));
        check(Math.abs(ScrollInertia.stopSeconds(150, tau) - 5.2) < 0.05, "4 档最快约 5.2 秒");
        check(Math.abs(ScrollInertia.stopSeconds(150, 3.0) - 11.8) < 0.05, "1 档最快约 11.8 秒（15 秒兜底到不了）");
        check(ScrollInertia.stopSeconds(150, 3.0) * 1000 < ScrollInertia.MAX_MS, "最轻档最快一拨也在 15 秒以内");
    }

    // ═══════════ 工具 ═══════════

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }

    static void eq(Object actual, Object expected, String what) {
        check(expected == null ? actual == null : expected.equals(actual), what + "：期望 " + expected + "，实际 " + actual);
    }
}
