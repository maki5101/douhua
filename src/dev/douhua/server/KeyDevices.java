package dev.douhua.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.douhua.Proto;

/**
 * 用系统自带的 {@code getevent -i} 列出输入设备，找出能"单独独占"的音量键设备（音量下 114、音量上 115）。
 * EVIOCGRAB 是整个设备一起独占的，所以只认事件类型只有 KEY（加 MSC；SYN 隐含）、键码只有 114 / 115、
 * 不是 USB / 蓝牙 / 虚拟总线、厂商号不是豆花虚拟键盘（{@link Proto#HID_VENDOR}）的设备——合盖霍尔（带 SW）、
 * 触摸屏（带 ABS）、外接键盘、Bixby 这类带别的键的设备误截了，代价比这个功能本身大。
 * getevent 看不见 REP / PWR / FF_STATUS：它按类型调 EVIOCGBIT，内核只认 KEY、REL、ABS、MSC、LED、SND、FF、SW，
 * 别的类型返回 EINVAL、getevent 什么都不打。所以带内核自动连发（EV_REP）的设备照样可能判成能截；独占后会多收 value=2 的
 * 连发事件，{@link HardKeyGate} 直接忽略，透传的键由系统自己连发，无害。
 * <b>带电源键（116）的设备任何情况下都不截</b>；它只能被旁观（不独占地读），用来认出"电源键 + 截住的音量键"一起按（截屏）。
 *
 * 不读 /proc/bus/input/devices：三星 One UI 上 shell 读它被 SELinux 拒绝（EACCES）。getevent 是 shell 能跑的系统命令，
 * 直接打开 /dev/input/eventN 查（服务读触摸屏也是直接开这些节点）。
 * 解析和设备筛选都是纯 Java，不依赖 Android，能离线测（{@code tools/test.sh}）。
 *
 * {@code getevent -i} 的输出格式（照 AOSP system/core/toolbox/getevent.c 的 printf 写的，没在真机上对过；-i 只列设备就退出，不读事件）：
 * <pre>
 * add device 1: /dev/input/event2
 *   bus:      0019
 *   vendor    0001
 *   product   0001
 *   version   0100
 *   name:     "gpio_keys"
 *   location: "gpio_keys/input0"
 *   id:       ""
 *   version:  1.0.1
 *   events:
 *     KEY (0001): 0072  0073*
 *     ABS (0003): 0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
 *                 0036  : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0
 *     SW  (0005): 0000
 *   input props:
 *     &lt;none&gt;
 * </pre>
 * 类型名补到三个字符（"SW "、"FF "），所以 SW / FF 和括号之间是两个空格；类型以括号里的十六进制为准。
 * 代码后面的 '*' 是当前按着；一行放不下时续行只有缩进和代码；ABS 每个代码单独一行。打不开的设备在标准错误里打
 * "could not open /dev/input/eventX, Permission denied"。解析很宽容：设备块外、input props 之后认不出的行跳过；
 * 但 events 段里有认不出的行、或者缺了 bus / vendor / events，这块设备就当不能截（宁可不截）。
 */
final class KeyDevices {
    static final int KEY_VOLUMEDOWN = 114, KEY_VOLUMEUP = 115, KEY_POWER = 116;

    static final int EV_KEY = 0x01, EV_REL = 0x02, EV_ABS = 0x03, EV_MSC = 0x04;
    private static final int BUS_USB = 0x03, BUS_BLUETOOTH = 0x05, BUS_VIRTUAL = 0x06;
    /** getevent 列完设备就退出，正常几十毫秒；卡住了也别拖住实体键线程。 */
    static final long GETEVENT_TIMEOUT_MS = 5000;

    private static final Pattern TYPE_LABELED = Pattern.compile("(\\S+)\\s+\\(([0-9a-fA-F]{4})\\):(.*)");
    private static final Pattern TYPE_BARE = Pattern.compile("([0-9a-fA-F]{4}):(.*)");
    private static final Pattern CODE = Pattern.compile("([0-9a-fA-F]{1,8})\\*?");
    private static final Pattern COULD_NOT_OPEN = Pattern.compile("could not open (\\S+?),? (.*)");

    private KeyDevices() {}

    /** 一块输入设备（getevent 的一块），纯数据。 */
    static final class Device {
        /** /dev/input/eventN。 */
        final String path;
        final String name;
        final int bus, vendor, product;
        /** 事件类型 → 代码（SYN 不列：getevent 查不到它的代码，所有设备都有）。 */
        final Map<Integer, Set<Integer>> events;
        /** 解析不完整 / events 段里认不出的地方；null = 完整。 */
        final String problem;
        /** getevent 打不开这个设备（标准错误里的原因）；null = 打开了。 */
        final String openError;
        final boolean volUp, volDown, power;
        /** 114 / 115 / 116 以外的键码（有就不能单独截），最多记几个；{@link #extraKeyCount} 是总数。 */
        final List<Integer> extraKeys = new ArrayList<>();
        final int extraKeyCount;

        Device(String path, String name, int bus, int vendor, int product, Map<Integer, Set<Integer>> events,
               String problem, String openError) {
            this.path = path;
            this.name = name;
            this.bus = bus;
            this.vendor = vendor;
            this.product = product;
            this.events = events;
            this.problem = problem;
            this.openError = openError;
            Set<Integer> keys = events.get(EV_KEY);
            boolean up = false, down = false, pwr = false;
            int extra = 0;
            if (keys != null) {
                for (int k : keys) {
                    if (k == KEY_VOLUMEUP) up = true;
                    else if (k == KEY_VOLUMEDOWN) down = true;
                    else if (k == KEY_POWER) pwr = true;
                    else {
                        extra++;
                        if (extraKeys.size() < 8) extraKeys.add(k);
                    }
                }
            }
            volUp = up;
            volDown = down;
            power = pwr;
            extraKeyCount = extra;
        }

        static Device unopenable(String path, String why) {
            return new Device(path, path, 0, 0, 0, new TreeMap<>(), null, why);
        }

        boolean hasVolume() {
            return volUp || volDown;
        }

        /**
         * 能不能单独截（只看设备本身，不看配置）：能返回 null，不能返回原因。
         * 原因里带上设备名和为什么（多出来的事件类型 / 键码、总线、厂商、电源键），文字由 App 拼，这里给细节。
         */
        String whyNotGrabbable() {
            if (openError != null) return name + "：getevent 打不开（" + openError + "）";
            if (problem != null) return name + "：getevent 的输出没认全（" + problem + "）";
            if (!path.matches("/dev/input/event\\d+")) return name + "：不是 event 节点（" + path + "）";
            StringBuilder other = new StringBuilder();
            for (int type : events.keySet()) {
                if (type == EV_KEY || type == EV_MSC) continue;
                other.append(' ').append(typeName(type));
            }
            if (other.length() > 0) return name + "：带别的输入（事件类型" + other + "）";
            if (bus == BUS_USB || bus == BUS_BLUETOOTH || bus == BUS_VIRTUAL) {
                return name + "：外接设备（总线 0x" + Integer.toHexString(bus) + "）";
            }
            if (vendor == Proto.HID_VENDOR) return name + "：豆花自己的虚拟键盘";
            if (power) return name + "：和电源键在同一个设备上（服务不截带电源键的设备）";
            if (extraKeyCount > 0) return name + "：还有别的键（" + extraKeysText() + "）";
            if (!hasVolume()) return name + "：没有音量键";
            return null;
        }

        /**
         * 能不能旁观（不独占地读，只用来认"电源键和截住的音量键一起按"——电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等））：机身上带电源键的设备就行，
         * 带别的键、开关也没关系（不独占就不影响任何人）；外接的、豆花自己的、触摸屏这类指针设备不算。
         */
        boolean watchable() {
            return openError == null && power && path.matches("/dev/input/event\\d+")
                    && !events.containsKey(EV_REL) && !events.containsKey(EV_ABS)
                    && vendor != Proto.HID_VENDOR && bus != BUS_USB && bus != BUS_BLUETOOTH && bus != BUS_VIRTUAL;
        }

        private String extraKeysText() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < extraKeys.size(); i++) {
                if (i > 0) b.append(' ');
                b.append(extraKeys.get(i));
            }
            if (extraKeyCount > extraKeys.size()) b.append(" 等 ").append(extraKeyCount).append(" 个");
            return b.toString();
        }

        /** 给 {@code Server keys} 看的一行：路径、名字、总线 / 厂商 / 产品、事件类型、键、能不能截 / 为什么。 */
        String describe() {
            if (openError != null) return path + "：getevent 打不开（" + openError + "）";
            StringBuilder b = new StringBuilder(path).append(" \"").append(name).append("\" 总线 0x")
                    .append(Integer.toHexString(bus)).append(" 厂商 0x").append(Integer.toHexString(vendor))
                    .append(" 产品 0x").append(Integer.toHexString(product)).append(" · 事件类型");
            if (events.isEmpty()) b.append(" 无");
            for (int type : events.keySet()) b.append(' ').append(typeName(type));
            if (events.containsKey(EV_KEY)) {
                b.append(" · 键");
                if (volDown) b.append(" 音量下");
                if (volUp) b.append(" 音量上");
                if (power) b.append(" 电源");
                if (extraKeyCount > 0) b.append(" 另有 ").append(extraKeyCount).append(" 个别的键（").append(extraKeysText()).append("）");
            }
            String why = whyNotGrabbable();
            return b.append(" · ").append(why == null ? "能截" : "不能截：" + why.substring(why.indexOf('：') + 1))
                    .append(watchable() ? "（可旁观）" : "").toString();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** 事件类型的名字（getevent 用的那套）；不认识的写十六进制。 */
    static String typeName(int type) {
        switch (type) {
            case 0x00: return "SYN";
            case 0x01: return "KEY";
            case 0x02: return "REL";
            case 0x03: return "ABS";
            case 0x04: return "MSC";
            case 0x05: return "SW";
            case 0x11: return "LED";
            case 0x12: return "SND";
            case 0x15: return "FF";
            default: return "0x" + Integer.toHexString(type);
        }
    }

    /** 独占计划：要 EVIOCGRAB 的设备、只旁观的设备、哪些键归豆花了、哪些配了却截不了（带原因）。 */
    static final class Plan {
        final List<Device> grab = new ArrayList<>();
        /** 不独占、只旁观的设备（{@link Device#watchable}）：认出"电源键和截住的音量键一起按"，交还系统。 */
        final List<Device> watch = new ArrayList<>();
        /** 实际截住、归豆花判断的键（HARD_BIT_*）。 */
        int grabbedBits;
        /** 配了动作、却截不了的键（HARD_BIT_*）。 */
        int unavailableBits;
        /** unavailableBits 的主要原因（Proto.HARD_*），以及细节（设备名等，文字由 App 拼）。 */
        int reason = Proto.HARD_OK;
        String detail = "";
    }

    /** 配置：哪个音量键配了动作（配了才截）。 */
    static final class Config {
        final boolean volUp, volDown;

        Config(boolean volUp, boolean volDown) {
            this.volUp = volUp;
            this.volDown = volDown;
        }

        boolean any() {
            return volUp || volDown;
        }
    }

    /**
     * 按配置挑设备：
     * <ul>
     * <li>带了某个配了动作的音量键、又能单独截的设备就截；设备上没配的音量键由 {@link HardKeyGate} 原样透传。</li>
     * <li>配了动作、却只在不能单独截的设备上（比如和电源键在同一个设备上）的键报 {@link Proto#HARD_SHARED}，
     *     哪儿都没有的报 {@link Proto#HARD_NO_DEVICE}，原因不同报 {@link Proto#HARD_MIXED}。</li>
     * <li>要截点什么时，机身上带电源键的设备都旁观（{@link Plan#watch}）：电源键和截住的音量键一起按（电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等））认得出来。
     *     一块能旁观的电源键设备都没有（getevent 打不开、带触摸之类）就什么都不截、报 {@link Proto#HARD_SHARED}：
     *     截了电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）会失灵，单按电源直接灭屏。</li>
     * </ul>
     */
    static Plan plan(List<Device> devices, Config cfg) {
        Plan p = new Plan();
        String sharedUp = null, sharedDown = null;
        int unopenable = 0;
        for (Device d : devices) {
            if (d.openError != null) unopenable++;
            boolean up = d.volUp && cfg.volUp, down = d.volDown && cfg.volDown;
            if (!up && !down) continue;
            String why = d.whyNotGrabbable();
            if (why == null) {
                p.grab.add(d);
                if (up) p.grabbedBits |= Proto.HARD_BIT_VOL_UP;
                if (down) p.grabbedBits |= Proto.HARD_BIT_VOL_DOWN;
                continue;
            }
            if (up && sharedUp == null) sharedUp = why;
            if (down && sharedDown == null) sharedDown = why;
        }

        if (!p.grab.isEmpty()) {
            for (Device d : devices) if (!p.grab.contains(d) && d.watchable()) p.watch.add(d);
            if (p.watch.isEmpty()) {   // watchable() 要求带电源键：watch 空 = 看不到电源键在哪
                String why = "找不到能旁观的电源键设备" + (unopenable > 0 ? "（" + unopenable + " 个设备 getevent 打不开）" : "")
                        + "，截了会让电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）失灵";
                // 本来截得住的键改报这个原因；本来就截不了的键原因不变
                if ((p.grabbedBits & Proto.HARD_BIT_VOL_UP) != 0) sharedUp = why;
                if ((p.grabbedBits & Proto.HARD_BIT_VOL_DOWN) != 0) sharedDown = why;
                p.grab.clear();
                p.grabbedBits = 0;
            }
        }

        // 配了、却没截住的键：拼原因
        String missing = "没找到带这个键的设备" + (unopenable > 0 ? "（另有 " + unopenable + " 个设备 getevent 打不开）" : "");
        StringBuilder detail = new StringBuilder();
        int[] bits = {Proto.HARD_BIT_VOL_UP, Proto.HARD_BIT_VOL_DOWN};
        boolean[] wants = {cfg.volUp, cfg.volDown};
        String[] shared = {sharedUp, sharedDown};
        String[] names = {"音量上", "音量下"};
        for (int i = 0; i < 2; i++) {
            if (!wants[i] || (p.grabbedBits & bits[i]) != 0) continue;
            p.unavailableBits |= bits[i];
            int reason = shared[i] != null ? Proto.HARD_SHARED : Proto.HARD_NO_DEVICE;
            if (p.reason == Proto.HARD_OK) p.reason = reason;
            else if (p.reason != reason) p.reason = Proto.HARD_MIXED;   // 只报一种原因会把别的键说错
            if (detail.length() > 0) detail.append("；");
            detail.append(names[i]).append("：").append(shared[i] != null ? shared[i] : missing);
        }
        p.detail = detail.toString();
        return p;
    }

    /**
     * 跑 {@code getevent -i} 列设备（不独占、不读事件，列完就退出）。getevent 不存在、超时、一块打开了的设备都没列出来
     * 都抛 IOException，消息写清楚原因。
     */
    static List<Device> read() throws IOException {
        Shell.Result r;
        try {
            r = Shell.runSplit(GETEVENT_TIMEOUT_MS, "getevent", "-i");
        } catch (IOException e) {
            throw new IOException("运行 getevent 失败：" + e.getMessage(), e);
        }
        List<Device> ds = parse(r.out, r.err);
        int opened = 0;
        for (Device d : ds) if (d.openError == null) opened++;
        if (opened == 0) {
            String why = firstLine(r.err);
            if (why.isEmpty()) why = firstLine(r.out);
            throw new IOException("getevent -i 没列出任何能打开的设备（退出码 " + r.exit + "，打不开 " + ds.size() + " 个）"
                    + (why.isEmpty() ? "" : "：" + why));
        }
        return ds;
    }

    private static String firstLine(String s) {
        for (String line : s.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) return line.length() > 200 ? line.substring(0, 200) + "…" : line;
        }
        return "";
    }

    /** 解析 getevent -i 的标准输出和标准错误（错误里只认 "could not open"，其余跳过）。 */
    static List<Device> parse(String out, String err) {
        List<Device> devs = new ArrayList<>();
        Builder cur = null;
        for (String raw : out.split("\n")) {
            String line = stripCr(raw);
            if (line.startsWith("add device ")) {
                if (cur != null) devs.add(cur.build());
                int colon = line.indexOf(": ");
                cur = new Builder(colon >= 0 ? line.substring(colon + 2).trim() : "?");
                if (colon < 0) cur.fail("认不出设备行：" + clip(line));
                continue;
            }
            if (cur == null) continue;   // 第一块设备之前的杂行
            cur.line(line);
        }
        if (cur != null) devs.add(cur.build());
        if (err != null) {
            for (String raw : err.split("\n")) {
                Matcher m = COULD_NOT_OPEN.matcher(stripCr(raw).trim());
                if (m.matches()) devs.add(Device.unopenable(m.group(1), m.group(2).trim()));
            }
        }
        return devs;
    }

    /** 一块设备的解析状态。 */
    private static final class Builder {
        private static final int SEC_HEADER = 0, SEC_EVENTS = 1, SEC_OTHER = 2;

        final String path;
        String name;
        int bus = -1, vendor = -1, product;
        boolean sawEvents;
        int section = SEC_HEADER;
        /** events 段里当前的事件类型（续行归它）；-1 = 还没有。 */
        int type = -1;
        final Map<Integer, Set<Integer>> events = new TreeMap<>();
        String problem;

        Builder(String path) {
            this.path = path;
        }

        void fail(String why) {
            if (problem == null) problem = why;
        }

        void line(String line) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String body = line.substring(indent).trim();
            if (body.isEmpty()) return;
            if (indent == 2) {
                header(body);
            } else if (section == SEC_EVENTS) {
                if (indent == 4) typeLine(body);
                else if (indent > 4 && type >= 0) codes(type, body);
                else fail("events 段里认不出的行：" + clip(body));
            }
            // 别的（input props、HID 描述符之类）跳过
        }

        private void header(String body) {
            int sp = 0;
            while (sp < body.length() && body.charAt(sp) != ' ' && body.charAt(sp) != ':') sp++;
            String key = body.substring(0, sp);
            String value = body.substring(sp);
            if (value.startsWith(":")) value = value.substring(1);
            value = value.trim();
            section = SEC_HEADER;
            switch (key) {
                case "bus":
                    bus = hex(value);
                    break;
                case "vendor":
                    vendor = hex(value);
                    break;
                case "product":
                    product = Math.max(0, hex(value));
                    break;
                case "name": {
                    int q = value.indexOf('"'), q2 = value.lastIndexOf('"');
                    name = q >= 0 && q2 > q ? value.substring(q + 1, q2) : value;
                    break;
                }
                case "events":
                    section = SEC_EVENTS;
                    sawEvents = true;
                    type = -1;
                    break;
                default:
                    // input props 之后的都不看；version / location / id 不用
                    if (key.equals("input")) section = SEC_OTHER;
                    break;
            }
        }

        private void typeLine(String body) {
            Matcher m = TYPE_LABELED.matcher(body);
            String rest;
            if (m.matches()) {
                type = Integer.parseInt(m.group(2), 16);
                rest = m.group(3);
            } else {
                Matcher b = TYPE_BARE.matcher(body);
                if (!b.matches()) {
                    type = -1;
                    fail("认不出的事件类型行：" + clip(body));
                    return;
                }
                type = Integer.parseInt(b.group(1), 16);
                rest = b.group(2);
            }
            if (!events.containsKey(type)) events.put(type, new TreeSet<>());
            codes(type, rest);
        }

        /** 一行里的代码（"0072  0073* "；ABS 后面的 ": value …" 不要）。 */
        private void codes(int t, String s) {
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(0, colon);
            Set<Integer> set = events.get(t);
            for (String tok : s.trim().split("\\s+")) {
                if (tok.isEmpty()) continue;
                Matcher m = CODE.matcher(tok);
                if (!m.matches()) {
                    fail("认不出的代码：" + clip(tok));
                    continue;
                }
                set.add(Integer.parseInt(m.group(1), 16));
            }
        }

        Device build() {
            if (bus < 0 || vendor < 0) fail("缺 bus / vendor 行");
            if (!sawEvents) fail("没有 events 段");
            String n = name == null || name.isEmpty() ? path : name;
            return new Device(path, n, Math.max(0, bus), Math.max(0, vendor), product, events, problem, null);
        }

        private int hex(String s) {
            try {
                return Integer.parseInt(s.trim(), 16);
            } catch (NumberFormatException e) {
                fail("认不出的十六进制：" + clip(s));
                return -1;
            }
        }
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String clip(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
