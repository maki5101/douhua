package dev.douhua.server;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 某块屏对应的触摸屏设备，以及系统自己用的"原始坐标 → 屏幕坐标"换算矩阵（含旋转）。
 * 信息从 `dumpsys input` 里读：用系统现成的矩阵，横竖屏换算就和系统完全一致，不靠自己推。
 * 纯 Java、不依赖 Android（解析部分能离线测试）。
 */
final class TouchDevice {
    final String name;
    final String path;
    /** 屏幕 x = a·rawX + b·rawY + c；屏幕 y = d·rawX + e·rawY + f */
    final float a, b, c, d, e, f;

    TouchDevice(String name, String path, float[] m) {
        this.name = name;
        this.path = path;
        this.a = m[0];
        this.b = m[1];
        this.c = m[2];
        this.d = m[3];
        this.e = m[4];
        this.f = m[5];
    }

    /** 矩阵只是缩放（没含旋转）：屏幕旋转要自己再做一步（见 ScreenRotation）。 */
    boolean unrotated() {
        return Math.abs(b) < 1e-3f && Math.abs(d) < 1e-3f && a > 0 && e > 0;
    }

    float mapX(int rawX, int rawY) {
        return a * rawX + b * rawY + c;
    }

    float mapY(int rawX, int rawY) {
        return d * rawX + e * rawY + f;
    }

    /** dumpsys 卡住（比如系统框架正在重启）时最多等这么久。 */
    private static final long DUMPSYS_TIMEOUT_MS = 10_000;

    static TouchDevice find(int displayId) throws IOException {
        return parse(Shell.run(DUMPSYS_TIMEOUT_MS, "dumpsys", "input"), displayId);
    }

    /** 这块屏没有对应的触摸屏（比如虚拟屏）。 */
    static final class NotFound extends IOException {
        NotFound(int displayId) {
            super("没找到屏幕 " + displayId + " 对应的触摸屏");
        }
    }

    private static final Pattern READER_DEVICE = Pattern.compile("(?m)^  Device \\d+: (.+?)\\s*$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** 在 dumpsys input 的输出里找绑定到 displayId 的触摸屏。找不到抛异常，消息说明原因。 */
    static TouchDevice parse(String dump, int displayId) throws IOException {
        int hub = dump.indexOf("Event Hub State");
        int reader = dump.indexOf("Input Reader State");
        int dispatcher = dump.indexOf("Input Dispatcher State");
        if (hub < 0 || reader < 0) throw new IOException("读不懂 dumpsys input 的输出（找不到 Event Hub / Input Reader 段）");
        String hubPart = dump.substring(hub, reader);
        String readerPart = dump.substring(reader, dispatcher > reader ? dispatcher : dump.length());

        Pattern viewport = Pattern.compile("Viewport \\w+: displayId=" + displayId + ",");
        Matcher m = READER_DEVICE.matcher(readerPart);
        int start = -1;
        String name = null;
        while (true) {
            boolean more = m.find();
            if (name != null) {
                String block = readerPart.substring(start, more ? m.start() : readerPart.length());
                if (isTouchscreen(block) && viewport.matcher(block).find()) {
                    float[] matrix = rawToDisplay(block);
                    if (matrix != null) return new TouchDevice(name, pathOf(hubPart, name), matrix);
                }
            }
            if (!more) break;
            name = m.group(1);
            start = m.end();
        }
        throw new NotFound(displayId);
    }

    private static boolean isTouchscreen(String block) {
        Matcher s = Pattern.compile("(?m)^\\s+Sources: (.*)$").matcher(block);
        return s.find() && s.group(1).contains("TOUCHSCREEN");
    }

    /** "RawToDisplay Transform:" 后面两行就是矩阵的前两行。 */
    private static float[] rawToDisplay(String block) {
        int i = block.indexOf("RawToDisplay Transform:");
        if (i < 0) return null;
        String[] lines = block.substring(i).split("\n");
        if (lines.length < 3) return null;
        float[] out = new float[6];
        for (int row = 0; row < 2; row++) {
            Matcher n = NUMBER.matcher(lines[row + 1]);
            for (int col = 0; col < 3; col++) {
                if (!n.find()) return null;
                out[row * 3 + col] = Float.parseFloat(n.group());
            }
        }
        return out;
    }

    private static String pathOf(String hubPart, String name) throws IOException {
        Matcher m = Pattern.compile("(?m)^\\s+\\d+: " + Pattern.quote(name) + "\\s*$").matcher(hubPart);
        if (!m.find()) throw new IOException("Event Hub 里没找到设备 " + name);
        Matcher p = Pattern.compile("(?m)^\\s+Path: (\\S+)").matcher(hubPart);
        if (!p.find(m.end())) throw new IOException("Event Hub 里没找到 " + name + " 的设备路径");
        return p.group(1);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%s（%s）矩阵[%.4f %.4f %.1f / %.4f %.4f %.1f]",
                name, path, a, b, c, d, e, f);
    }
}
