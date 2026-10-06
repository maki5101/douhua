package dev.douhua.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端纯 Java 部件的离线测试：多点触控解码（MtDecoder）、从 dumpsys input 里找触摸屏（TouchDevice）。
 * 不依赖 Android、不需要 JUnit：tools/test.sh 用电脑上的 JDK 直接编译运行。
 */
public final class ServerPartsTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        singleFinger();
        twoFingers();
        fingerAlreadyDownIsIgnored();
        palmIsNotAFinger();
        droppedEventsReleaseEverything();
        newFingerInSameSlotSameFrame();
        orderWithinFrame();
        findsCoverTouchscreen();
        findsInnerTouchscreen();
        missingDisplayFails();
        rotationMatchesRealDevice();
        rotationRoundTrip();
        realMatrixIsUnrotated();

        System.err.println("服务端部件：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ MtDecoder ═══════════

    static final class Rec implements MtDecoder.Listener {
        final List<String> log = new ArrayList<>();

        @Override
        public void down(int slot, int x, int y) {
            log.add("down" + slot + "@" + x + "," + y);
        }

        @Override
        public void move(int slot, int x, int y) {
            log.add("move" + slot + "@" + x + "," + y);
        }

        @Override
        public void frame() {
            log.add("frame");
        }

        @Override
        public void up(int slot) {
            log.add("up" + slot);
        }

        /** 去掉 frame，方便比对。 */
        List<String> events() {
            List<String> r = new ArrayList<>();
            for (String s : log) if (!s.equals("frame")) r.add(s);
            return r;
        }
    }

    static void abs(MtDecoder d, int code, int value) {
        d.feed(MtDecoder.EV_ABS, code, value);
    }

    static void syn(MtDecoder d) {
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_REPORT, 0);
    }

    static void singleFinger() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 10);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 100);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 200);
        syn(d);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 110);
        syn(d);
        syn(d);   // 没变化的帧
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        eq(r.events(), list("down0@100,200", "move0@110,200", "up0"), "单指：按下、移动、抬起");
        eq(count(r.log, "frame"), 4, "每个 SYN_REPORT 一帧");
    }

    static void twoFingers() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 10);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 10);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 50);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 10);
        syn(d);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 20);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 20);
        syn(d);
        eq(r.events(), list("down0@10,10", "down1@50,10", "move0@10,20", "move1@50,20"), "双指各自跟踪");
    }

    static void fingerAlreadyDownIsIgnored() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        // 开始读之前就按着：只看到位置更新，没看到 tracking id
        abs(d, MtDecoder.ABS_MT_POSITION_X, 300);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 7);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 5);
        abs(d, MtDecoder.ABS_MT_POSITION_Y, 6);
        syn(d);
        eq(r.events(), list("down0@5,6"), "之前就按着的手指不理，重新按下才算");
    }

    static void palmIsNotAFinger() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 9);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TOOL_TYPE, MtDecoder.MT_TOOL_PALM);
        syn(d);
        eq(r.events(), list("down1@9,0", "up1"), "手掌不算；手指中途变手掌按抬起处理");
    }

    static void droppedEventsReleaseEverything() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 1);
        syn(d);
        d.feed(MtDecoder.EV_SYN, MtDecoder.SYN_DROPPED, 0);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 99);
        syn(d);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 50);   // 那根手指还按着，但已作废
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 3);
        syn(d);
        eq(r.events(), list("down0@1,0", "up0", "down0@3,0"), "丢数据：全部抬起，作废的手指不再报告");
    }

    static void newFingerInSameSlotSameFrame() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        abs(d, MtDecoder.ABS_MT_POSITION_X, 1);
        syn(d);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);   // 同一帧里换了一根手指
        abs(d, MtDecoder.ABS_MT_POSITION_X, 8);
        syn(d);
        eq(r.events(), list("down0@1,0", "up0", "down0@8,0"), "同一 slot 换手指：先抬起旧的再按下新的");
    }

    static void orderWithinFrame() {
        Rec r = new Rec();
        MtDecoder d = new MtDecoder(r);
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 1);
        syn(d);
        r.log.clear();
        abs(d, MtDecoder.ABS_MT_SLOT, 0);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, -1);
        abs(d, MtDecoder.ABS_MT_SLOT, 1);
        abs(d, MtDecoder.ABS_MT_TRACKING_ID, 2);
        syn(d);
        eq(r.log, list("down1@0,0", "frame", "up0"), "一帧里：按下 → 帧结束 → 抬起");
    }

    // ═══════════ TouchDevice ═══════════

    /**
     * 按真机 dumpsys input 的格式缩写的样本（外屏 = sec_touchscreen2）。
     * 矩阵照抄真机转屏后的输出：仍然只是缩放（加一点点平移），不含旋转——旋转由 ScreenRotation 另做。
     */
    static final String DUMP = String.join("\n",
            "INPUT MANAGER (dumpsys input)",
            "Event Hub State:",
            "  BuiltInKeyboardId: -2",
            "  Devices:",
            "    5: sec_touchscreen",
            "      Classes: KEYBOARD | TOUCH | TOUCH_MT",
            "      Path: /dev/input/event11",
            "      Enabled: true",
            "    7: sec_touchscreen2",
            "      Classes: KEYBOARD | TOUCH | TOUCH_MT",
            "      Path: /dev/input/event9",
            "      Enabled: true",
            "    4: sec_touchpad",
            "      Classes: KEYBOARD | TOUCH | TOUCH_MT | TOUCHPAD",
            "      Path: /dev/input/event12",
            "      Enabled: false",
            "",
            "Input Reader State (Nums of device: 3):",
            "  Device 9: sec_touchpad",
            "    Sources: KEYBOARD | MOUSE | TOUCHPAD",
            "      Viewport INTERNAL: displayId=0, uniqueId=local:1, port=148, orientation=0",
            "        RawToDisplay Transform: (ROT_0) (SCALE )",
            "            9.0000  0.0000  0.0000",
            "            0.0000  9.0000  0.0000",
            "            0.0000  0.0000  1.0000",
            "  Device 8: sec_touchscreen",
            "    Sources: KEYBOARD | TOUCHSCREEN",
            "      Viewport INTERNAL: displayId=1, uniqueId=local:2, port=147, orientation=0",
            "        RawToDisplay Transform: (ROT_0) (SCALE )",
            "            0.5977  0.0000  0.0000",
            "            0.0000  0.4512  0.0000",
            "            0.0000  0.0000  1.0000",
            "  Device 7: sec_touchscreen2",
            "    Sources: KEYBOARD | TOUCHSCREEN",
            "      Viewport INTERNAL: displayId=0, uniqueId=local:1, port=148, orientation=0",
            "        RawToDisplay Transform: (ROT_0) (SCALE TRANSLATE)",
            "            0.3047  0.0000  0.3047",
            "            0.0000  0.4814  0.0000",
            "            0.0000  0.0000  1.0000",
            "",
            "Input Dispatcher State:",
            "  DispatchEnabled: true");

    static void findsCoverTouchscreen() throws IOException {
        TouchDevice t = TouchDevice.parse(DUMP, 0);
        eq(t.name, "sec_touchscreen2", "屏幕 0 对应外屏触摸屏（跳过同样绑在屏幕 0 的触控板设备）");
        eq(t.path, "/dev/input/event9", "设备路径");
        near(t.mapX(4095, 0), 1248f, "原始 x 0–4095 → 竖屏 x 0–1248");
        near(t.mapY(0, 4095), 1971.3f, "原始 y 0–4095 → 竖屏 y 0–1972");
        check(t.unrotated(), "真机矩阵不含旋转");
    }

    static void findsInnerTouchscreen() throws IOException {
        TouchDevice t = TouchDevice.parse(DUMP, 1);
        eq(t.path, "/dev/input/event11", "屏幕 1 对应内屏触摸屏");
        near(t.mapX(4095, 4095), 2447.6f, "竖屏缩放");
    }

    static void missingDisplayFails() {
        try {
            TouchDevice.parse(DUMP, 5);
            check(false, "没有触摸屏的屏幕应该报错");
        } catch (IOException e) {
            check(e.getMessage().contains("5"), "报错要说清是哪块屏：" + e.getMessage());
        }
    }

    // ═══════════ ScreenRotation ═══════════

    /** 真机横屏（方向 1）时系统记录的变换：x = 原 y，y = 1248 − 原 x。 */
    static void rotationMatchesRealDevice() {
        float[] p = ScreenRotation.apply(100, 300, 1, 1248, 1972);
        near(p[0], 300, "方向 1：x = 原 y");
        near(p[1], 1148, "方向 1：y = 竖屏宽 − 原 x");
        p = ScreenRotation.apply(0, 0, 1, 1248, 1972);
        near(p[1], 1248, "方向 1：竖屏左上角转到横屏左下角");
        p = ScreenRotation.apply(100, 300, 0, 1248, 1972);
        near(p[0], 100, "方向 0 不变");
        near(p[1], 300, "方向 0 不变");
    }

    /** 每个方向都要把整块竖屏正好铺满对应方向的屏幕（四个角落在四个角上）。 */
    static void rotationRoundTrip() {
        float w = 1248, h = 1972;
        for (int r = 0; r < 4; r++) {
            float lw = (r & 1) == 1 ? h : w;
            float lh = (r & 1) == 1 ? w : h;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] c : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
                float[] p = ScreenRotation.apply(c[0], c[1], r, w, h);
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
            check(minX == 0 && maxX == lw && minY == 0 && maxY == lh, "方向 " + r + " 铺满 " + lw + "x" + lh);
        }
        float[] a = ScreenRotation.apply(10, 20, 1, w, h);
        float[] b = ScreenRotation.apply(10, 20, 3, w, h);
        check(a[0] + b[0] == h && a[1] + b[1] == w, "方向 1 和 3 互为 180° 翻转");
    }

    /** 真机的矩阵（转屏后也是）只是缩放：要自己转。 */
    static void realMatrixIsUnrotated() {
        TouchDevice real = new TouchDevice("sec_touchscreen2", "/dev/input/event9",
                new float[]{0.3047f, 0, 0.3047f, 0, 0.4814f, 0});
        check(real.unrotated(), "真机矩阵判定为不含旋转");
        TouchDevice rotated = new TouchDevice("x", "y", new float[]{0, 0.4814f, 0, -0.3047f, 0, 1248});
        check(!rotated.unrotated(), "含旋转的矩阵不再自己转");
    }

    // ═══════════ 工具 ═══════════

    static int count(List<String> l, String s) {
        int n = 0;
        for (String x : l) if (x.equals(s)) n++;
        return n;
    }

    static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
    }

    static void near(float actual, float expected, String what) {
        check(Math.abs(actual - expected) < 1f, what + "（期望约 " + expected + "，实际 " + actual + "）");
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
