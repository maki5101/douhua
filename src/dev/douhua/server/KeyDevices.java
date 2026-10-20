package dev.douhua.server;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.douhua.Proto;

/**
 * 从 {@code /proc/bus/input/devices} 里找出能"单独独占"的实体键设备（音量下 114、音量上 115、电源 116）。
 * EVIOCGRAB 是整个设备一起独占的，所以只认事件类型只有 SYN/KEY/MSC、键码只有 114/115/116、
 * 不是 USB/蓝牙/虚拟总线、厂商号不是豆花虚拟键盘（{@link Proto#HID_VENDOR}）的设备——合盖霍尔（带 EV_SW）、
 * 触摸板（带 EV_ABS/REL）、外接键盘、Bixby 这类带别的键的设备误截了，代价比这个功能本身大。
 *
 * 解析和设备筛选都是纯 Java，不依赖 Android，能离线测（{@code tools/test.sh}）。
 * {@code /proc/bus/input/devices} 是内核的稳定格式，比 dumpsys 稳；KEY= 按 64 位一字解析，假定 64 位内核
 * （和 TouchReader 的 EVENT_SIZE=24 是同一个假设）。
 */
final class KeyDevices {
    static final int KEY_VOLUMEDOWN = 114, KEY_VOLUMEUP = 115, KEY_POWER = 116;

    /** 允许的事件类型：SYN(0)、KEY(1)、MSC(4)。别的（REL/ABS/SW…）一律不截。 */
    private static final long EV_ALLOWED = (1L << 0) | (1L << 1) | (1L << 4);
    /** 指针类事件：REL(2)、ABS(3)。带这些的（触摸屏、触控板）连旁观都不旁观：事件太多，又不会是实体键。 */
    private static final long EV_POINTER = (1L << 2) | (1L << 3);
    private static final int BUS_USB = 0x03, BUS_BLUETOOTH = 0x05, BUS_VIRTUAL = 0x06;

    private KeyDevices() {}

    /** 一块输入设备（/proc/bus/input/devices 的一段），纯数据。 */
    static final class Device {
        final String name;
        /** /dev/input/eventN（没解析出来是 null）。 */
        final String path;
        final int bus, vendor;
        final long ev;
        final boolean volUp, volDown, power;
        /** 114/115/116 以外的键码（有就不能单独截），最多记几个。 */
        final List<Integer> extraKeys;

        Device(String name, String path, int bus, int vendor, long ev,
               boolean volUp, boolean volDown, boolean power, List<Integer> extraKeys) {
            this.name = name;
            this.path = path;
            this.bus = bus;
            this.vendor = vendor;
            this.ev = ev;
            this.volUp = volUp;
            this.volDown = volDown;
            this.power = power;
            this.extraKeys = extraKeys;
        }

        boolean hasAnyTarget() {
            return volUp || volDown || power;
        }

        /** 这个键在不在本设备上。 */
        boolean has(int bit) {
            return (bit == Proto.HARD_BIT_VOL_UP && volUp)
                    || (bit == Proto.HARD_BIT_VOL_DOWN && volDown)
                    || (bit == Proto.HARD_BIT_POWER && power);
        }

        /**
         * 能不能单独截（只看设备本身，不看配置）：能返回 null，不能返回原因。
         * 原因里带上为什么（多出来的事件类型 / 键码、总线、厂商），文字由 App 拼，这里给设备名和细节。
         */
        String whyNotGrabbable() {
            if (path == null) return name + "：没有 event 节点";
            if ((ev & ~EV_ALLOWED) != 0) return name + "：带别的输入（事件类型 0x" + Long.toHexString(ev) + "）";
            if (bus == BUS_USB || bus == BUS_BLUETOOTH || bus == BUS_VIRTUAL) {
                return name + "：外接设备（总线 0x" + Integer.toHexString(bus) + "）";
            }
            if (vendor == Proto.HID_VENDOR) return name + "：豆花自己的虚拟键盘";
            if (!extraKeys.isEmpty()) {
                StringBuilder b = new StringBuilder(name).append("：还有别的键（");
                for (int i = 0; i < extraKeys.size(); i++) {
                    if (i > 0) b.append(' ');
                    b.append(extraKeys.get(i));
                }
                return b.append("）").toString();
            }
            if (!hasAnyTarget()) return name + "：没有音量 / 电源键";
            return null;
        }

        /**
         * 能不能旁观（不独占地读，只用来认"电源键和音量键一起按"的系统组合键）：机身上带音量 / 电源键的按键设备就行，
         * 带别的键、开关也没关系（不独占就不影响任何人），外接的、豆花自己的、触摸屏这类指针设备不算。
         */
        boolean watchable() {
            return path != null && hasAnyTarget() && (ev & EV_POINTER) == 0 && vendor != Proto.HID_VENDOR
                    && bus != BUS_USB && bus != BUS_BLUETOOTH && bus != BUS_VIRTUAL;
        }

        @Override
        public String toString() {
            StringBuilder keys = new StringBuilder();
            if (volDown) keys.append(" 音量下");
            if (volUp) keys.append(" 音量上");
            if (power) keys.append(" 电源");
            String why = whyNotGrabbable();
            return name + "（" + path + "，总线 0x" + Integer.toHexString(bus) + "，键:" + keys + "）"
                    + (why == null ? " 能截" : " 不能截：" + why);
        }
    }

    /** 独占计划：要 EVIOCGRAB 的设备、只旁观的设备、哪些键归豆花了、哪些配了却截不了（带原因）。 */
    static final class Plan {
        final List<Device> grab = new ArrayList<>();
        /** 不独占、只旁观的设备（{@link Device#watchable}）：认出"电源键和音量键一起按"，交还系统。 */
        final List<Device> watch = new ArrayList<>();
        /** 实际截住、归豆花判断的键（HARD_BIT_*）。 */
        int grabbedBits;
        /** 配了动作、却截不了的键（HARD_BIT_*）。 */
        int unavailableBits;
        /** unavailableBits 的主要原因（Proto.HARD_*），以及细节（设备名等，文字由 App 拼）。 */
        int reason = Proto.HARD_OK;
        String detail = "";
    }

    /** 配置：哪个键配了动作（配了才截）。 */
    static final class Config {
        final boolean volUp, volDown, power;

        Config(boolean volUp, boolean volDown, boolean power) {
            this.volUp = volUp;
            this.volDown = volDown;
            this.power = power;
        }

        boolean wants(int bit) {
            return (bit == Proto.HARD_BIT_VOL_UP && volUp)
                    || (bit == Proto.HARD_BIT_VOL_DOWN && volDown)
                    || (bit == Proto.HARD_BIT_POWER && power);
        }

        boolean any() {
            return volUp || volDown || power;
        }
    }

    /**
     * 按配置挑设备：
     * <ul>
     * <li>带电源键、但电源键没配动作的设备绝不截（满足"没配动作绝不独占电源键"）；它上面配了动作的音量键报
     *     {@link Proto#HARD_SHARED}（和电源键在同一个设备上）。</li>
     * <li>其余带了某个配了动作的键的设备（能单独截的）就截；设备上没配的键由 {@link HardKeyGate} 原样透传。</li>
     * <li>配了动作、却只在不能单独截的设备上（或哪儿都没有）的键，报原因（HARD_SHARED / HARD_NO_DEVICE）。</li>
     * <li>要截点什么时，其余带音量 / 电源键的机身按键设备都旁观（{@link Plan#watch}）：配了的键和别的设备上的键一起按
     *     （截屏、强制重启）也认得出来。</li>
     * </ul>
     */
    static Plan plan(List<Device> devices, Config cfg) {
        Plan p = new Plan();
        int[] bits = {Proto.HARD_BIT_VOL_UP, Proto.HARD_BIT_VOL_DOWN, Proto.HARD_BIT_POWER};
        // 每个配了动作的键：找哪台设备能截它（记下原因）
        int[] perReason = new int[3];       // 下标同 bits
        String[] perDetail = new String[3];
        boolean[] seenCandidate = new boolean[3];   // 在能截的设备上见过（可能被电源规则挡住）
        boolean[] seenShared = new boolean[3];       // 在不能截的设备上见过
        String[] sharedDetail = new String[3];

        for (Device d : devices) {
            String why = d.whyNotGrabbable();
            if (why != null) {
                for (int i = 0; i < 3; i++) {
                    if (d.has(bits[i]) && cfg.wants(bits[i])) {
                        seenShared[i] = true;
                        if (sharedDetail[i] == null) sharedDetail[i] = why;
                    }
                }
                continue;
            }
            // 这台设备能单独截。看它带不带没配的电源键
            boolean blockedByPower = d.power && !cfg.power;
            boolean carriesWanted = (d.volUp && cfg.volUp) || (d.volDown && cfg.volDown) || (d.power && cfg.power);
            if (blockedByPower) {
                for (int i = 0; i < 3; i++) {
                    if (bits[i] != Proto.HARD_BIT_POWER && d.has(bits[i]) && cfg.wants(bits[i])) {
                        seenCandidate[i] = true;
                        perReason[i] = Proto.HARD_SHARED;
                        if (perDetail[i] == null) perDetail[i] = d.name + "：和电源键在同一个设备上";
                    }
                }
                continue;
            }
            if (!carriesWanted) continue;
            p.grab.add(d);
            if (d.volUp && cfg.volUp) p.grabbedBits |= Proto.HARD_BIT_VOL_UP;
            if (d.volDown && cfg.volDown) p.grabbedBits |= Proto.HARD_BIT_VOL_DOWN;
            if (d.power && cfg.power) p.grabbedBits |= Proto.HARD_BIT_POWER;
        }

        if (!p.grab.isEmpty()) {
            for (Device d : devices) if (!p.grab.contains(d) && d.watchable()) p.watch.add(d);
        }

        // 配了、却没截住的键：拼原因
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (!cfg.wants(bits[i]) || (p.grabbedBits & bits[i]) != 0) continue;
            p.unavailableBits |= bits[i];
            int reason;
            String d;
            if (seenCandidate[i]) {
                reason = perReason[i];
                d = perDetail[i];
            } else if (seenShared[i]) {
                reason = Proto.HARD_SHARED;
                d = sharedDetail[i];
            } else {
                reason = Proto.HARD_NO_DEVICE;
                d = "没找到带这个键的设备";
            }
            if (p.reason == Proto.HARD_OK) p.reason = reason;
            else if (p.reason != reason) p.reason = Proto.HARD_MIXED;   // 只报一种原因会把别的键说错
            if (d != null) {
                if (detail.length() > 0) detail.append("；");
                detail.append(d);
            }
        }
        p.detail = detail.toString();
        return p;
    }

    /** 读整份 /proc/bus/input/devices（谁都能读，不用权限）。 */
    static String readProc() throws IOException {
        try (InputStream in = new FileInputStream("/proc/bus/input/devices")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 解析整份 /proc/bus/input/devices。块之间空行分隔，解析部分纯 Java。 */
    static List<Device> parse(String procInput) {
        List<Device> out = new ArrayList<>();
        String[] blocks = procInput.split("\n\\s*\n");
        for (String block : blocks) {
            Device d = parseBlock(block);
            if (d != null) out.add(d);
        }
        return out;
    }

    private static Device parseBlock(String block) {
        String name = null, path = null;
        int bus = 0, vendor = 0;
        long ev = 0;
        long[] key = null;
        boolean sawAny = false;
        for (String line : block.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("I:")) {
                bus = (int) hexAfter(line, "Bus=");
                vendor = (int) hexAfter(line, "Vendor=");
                sawAny = true;
            } else if (line.startsWith("N:")) {
                int q = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                name = q >= 0 && q2 > q ? line.substring(q + 1, q2) : line.substring(2).trim();
                sawAny = true;
            } else if (line.startsWith("H:")) {
                path = eventPath(line);
                sawAny = true;
            } else if (line.startsWith("B: EV=")) {
                ev = parseHexWord(line.substring("B: EV=".length()).trim());
                sawAny = true;
            } else if (line.startsWith("B: KEY=")) {
                key = parseBitmap(line.substring("B: KEY=".length()).trim());
                sawAny = true;
            }
        }
        if (!sawAny) return null;
        List<Integer> extra = new ArrayList<>();
        boolean volUp = false, volDown = false, power = false;
        if (key != null) {
            int maxBit = key.length * 64;
            for (int b = 0; b < maxBit; b++) {
                if (!bit(key, b)) continue;
                if (b == KEY_VOLUMEUP) volUp = true;
                else if (b == KEY_VOLUMEDOWN) volDown = true;
                else if (b == KEY_POWER) power = true;
                else if (extra.size() < 8) extra.add(b);
            }
        }
        return new Device(name == null ? "?" : name, path, bus, vendor, ev, volUp, volDown, power, extra);
    }

    /** "H: Handlers=sysrq kbd event3 " → "/dev/input/event3"。 */
    private static String eventPath(String line) {
        for (String tok : line.split("\\s+")) {
            if (tok.startsWith("event") && tok.length() > 5) {
                boolean digits = true;
                for (int i = 5; i < tok.length(); i++) if (!Character.isDigit(tok.charAt(i))) digits = false;
                if (digits) return "/dev/input/" + tok;
            }
        }
        return null;
    }

    private static long hexAfter(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return 0;
        int j = i + key.length();
        int e = j;
        while (e < line.length() && isHex(line.charAt(e))) e++;
        return e > j ? parseHexWord(line.substring(j, e)) : 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static long parseHexWord(String s) {
        try {
            return Long.parseUnsignedLong(s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** KEY= 是空格分隔的十六进制字，最高位的字在前、最低位的字在后（64 位一字，前导零可能被省略）。 */
    private static long[] parseBitmap(String s) {
        String[] parts = s.trim().split("\\s+");
        long[] words = new long[parts.length];
        for (int i = 0; i < parts.length; i++) words[i] = parseHexWord(parts[i]);
        return words;
    }

    /** 第 b 位在不在（words 最后一个字是 0..63 位）。 */
    private static boolean bit(long[] words, int b) {
        int w = b / 64;
        if (w >= words.length) return false;
        long word = words[words.length - 1 - w];
        return ((word >>> (b % 64)) & 1L) != 0;
    }
}
