package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 实体键盘 F1–F12 的第二功能、F 行翻转（2026-11-02，协议 24）的离线测试。全部经真正的 TouchRouter / KeyHolds 跑（借 KeyHoldsTest 的 H：
 * 区域那一路、界面发来的 key() 那一路、两个定时器按截止时刻到点），看主机收到的键盘报文、多媒体 / 系统键两个槽报的码、报给界面的状态、震动。
 * 外加 FKeyFunctions（功能表、预设、拼区域 id）和 Boards 里的存储。
 * 锁住的：按下那一刻按"Fn 起作用 ^ F 行翻转"定码、松开松记下的那个（按着 F 键时 Fn+Esc 翻了照样松按下时的码）；多媒体 / 系统键一次报一个码、
 * 每个按下都配松开、所有收尾归零；第二功能是一个普通键（解开锁住的修饰键、让按着的修饰键算用过、用掉 Fn 单次锁）；Fn+Esc 翻转（不发 HID、震一下、报 FROW_FLIP），
 * 翻转是设置（releaseAll、unlatch 不清，setConfig 设、不报界面）。只证明规则照设计跑，主机认不认、按着手感对不对要真机看。
 */
public final class FKeysTest {
    static final String WIN = FixedLayout.WIN, MAC = FixedLayout.MAC;
    static final int FN = Proto.FN_KEY, FNG = Proto.FN_GLOBE_KEY, ESC = Proto.FN_LOCK_KEY, SHIFT = 0xE1, A = 0x04;
    /** F1 静音（多媒体 0xE2）、F2 音量−（0xEA）、F3 睡眠（系统 0x82）、F4 Super+P（组合键）、F5 没配（普通的 F5）、F6 音量+（0xE9）。 */
    static final int F1 = FKeyFunctions.regionId(1, FKeyFunctions.get("mute"), WIN),
            F2 = FKeyFunctions.regionId(2, FKeyFunctions.get("vol_down"), WIN),
            F3 = FKeyFunctions.regionId(3, FKeyFunctions.get("sleep"), WIN),
            F4 = FKeyFunctions.regionId(4, FKeyFunctions.get("display"), WIN),
            F5 = FKeyFunctions.regionId(5, FKeyFunctions.get("none"), WIN),
            F6 = FKeyFunctions.regionId(6, FKeyFunctions.get("vol_up"), WIN);
    static final int UP = Proto.fnLayerKey(0x52, 0x4B);
    static final int CLICK = GestureEngine.HAPTIC_CLICK;
    static final int FN_HELD = Proto.FN_HELD, FN_LATCHED = Proto.FN_LATCHED, FN_LOCKED = Proto.FN_LOCKED, FLIP = Proto.FROW_FLIP;

    /** 屏幕：下面一排 Fn、fn🌐、Esc（F 行翻转开关）、F1–F6、Shift、a、↑（Fn 层 PgUp）；右上"左键"按钮、触控板。 */
    static final TouchRouter.Region[] REGIONS = {
            KeyHoldsTest.key(FN, 0), KeyHoldsTest.key(FNG, 1), KeyHoldsTest.key(ESC, 2), KeyHoldsTest.key(F1, 3), KeyHoldsTest.key(F2, 4),
            KeyHoldsTest.key(F3, 5), KeyHoldsTest.key(F4, 6), KeyHoldsTest.key(F5, 7), KeyHoldsTest.key(F6, 8), KeyHoldsTest.key(SHIFT, 9),
            KeyHoldsTest.key(A, 10), KeyHoldsTest.key(UP, 11),
            new TouchRouter.Region(Proto.REGION_HOLD_LEFT, 3, 1100, 0, 1200, 100),
            new TouchRouter.Region(Proto.REGION_PAD, 0, 0, 0, 1000, 1000),
    };

    static int col(int key) {
        return KeyHoldsTest.col(REGIONS, key);
    }

    static KeyHoldsTest.H h() {
        return new KeyHoldsTest.H(REGIONS);
    }

    /** 换 F 行翻转（经 setConfig，同 CMD_CONFIG）。 */
    static void flip(KeyHoldsTest.H h, boolean v) {
        GestureEngine.Config c = new GestureEngine.Config();
        c.frowFlip = v;
        h.t.setConfig(c, 1);
    }

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        ids();
        catalog();
        presets();
        storage();
        plainByDefault();
        fnHeldSecond();
        flipped();
        fnOnceAndLock();
        fnLockToggle();
        escPlain();
        escWithOnceAndLock();
        globeToggle();
        heldThroughToggle();
        heldThroughConfig();
        systemKeys();
        combo();
        oneAtATime();
        actsLikePlainKey();
        releaseAllZeros();
        flipIsSetting();
        canceledReleases();
        twoFingersSameKey();
        pathsAgree();
        randomSequences();
        System.err.println("F 行第二功能 / F 行翻转：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    // ═══════════ 编号、功能表、预设、存储 ═══════════

    /** 区域 id 拼得对：没配的是普通用法码；配了的是 frowKey(发法, F 键, 码)；Mac 模式换码的那几个；Esc；内部码是负数、各认各的。 */
    static void ids() {
        eq(F5, 0x3E, "没配第二功能的 F5：普通的用法码（两面都是它）");
        eq(F1, Proto.frowKey(Proto.FROW_CONSUMER, 0x3A, 0xE2), "F1 静音：类型 2、底层 0x3A、码 0xE2");
        eq(F3, Proto.frowKey(Proto.FROW_SYSTEM, 0x3C, 0x82), "F3 睡眠：类型 3（系统键）");
        eq(F4, Proto.frowKey(Proto.FROW_KEY, 0x3D, 0x13 | Hid.MOD_META << 8), "F4 Super+P：类型 1、码 = 用法码 | 自带修饰键 << 8");
        eq(FKeyFunctions.regionId(11, FKeyFunctions.get("snip"), WIN), Proto.frowKey(Proto.FROW_KEY, 0x44, 0x16 | 0x0A00), "截屏工具 Windows：Super+Shift+S");
        eq(FKeyFunctions.regionId(11, FKeyFunctions.get("snip"), MAC), Proto.frowKey(Proto.FROW_KEY, 0x44, 0x21 | 0x0A00), "截屏工具 Mac：⇧⌘4");
        eq(FKeyFunctions.regionId(12, FKeyFunctions.get("vol_up"), MAC), FKeyFunctions.regionId(12, FKeyFunctions.get("vol_up"), WIN), "多媒体键两种模式一样");
        eq(FKeyFunctions.regionId(3, null, WIN), 0x3C, "认不出的功能（null）当没配");
        check(FKeyFunctions.fUsage(1) == 0x3A && FKeyFunctions.fUsage(12) == 0x45, "F1–F12 是 0x3A–0x45");
        eq(FKeyFunctions.escId(true), Proto.FN_LOCK_KEY, "有 F 行第二功能的尺寸：Esc 是翻转开关");
        eq(FKeyFunctions.escId(false), 0x29, "别的尺寸：Esc 照旧");
        int[] codes = {KeyHolds.CODE_CONSUMER | 0xE2, KeyHolds.CODE_SYSTEM | 0x82, KeyHolds.CODE_FN_LOCK_TOGGLE, KeyHolds.CODE_CONSUMER | 0x3FF};
        boolean ok = true;
        for (int c : codes) ok &= c < 0 && !Proto.isFrowKey(c) && !Proto.isFnLayerKey(c) && !Proto.isFnKey(c) && !Proto.isDualKey(c)
                && !KeyHolds.isModifier(c) && !KeyHolds.isTimed(c);
        check(ok, "内部码都是负数（撞不上任何区域 id），不是修饰键、不计时");
        check(KeyHolds.isConsumerCode(codes[0]) && !KeyHolds.isSystemCode(codes[0]) && KeyHolds.isSystemCode(codes[1]) && !KeyHolds.isConsumerCode(codes[1])
                && !KeyHolds.isConsumerCode(codes[2]) && !KeyHolds.isSystemCode(codes[2]) && KeyHolds.usageOf(codes[3]) == 0x3FF,
                "多媒体 / 系统 / 翻转三种内部码各认各的，用法码取得回来");
    }

    /** 功能表：id 不重复；码在描述符的范围里（多媒体 1..0x3FF、系统键 1..0xB7、键盘键的用法码不是 0）；分组；"key:XXXX" 读写；认不出的当 null。 */
    static void catalog() {
        Set<String> seen = new HashSet<>();
        boolean ok = true, sawSystem = false, sawConsumer = false, sawKey = false;
        String bad = null;
        for (FKeyFunctions.Function f : FKeyFunctions.all()) {
            boolean good = seen.add(f.id);
            switch (f.kind) {
                case FKeyFunctions.NONE: good &= f.code == 0 && f.group == FKeyFunctions.GROUP_NONE; break;
                case FKeyFunctions.CONSUMER: good &= f.code >= 1 && f.code <= 0x3FF; sawConsumer = true; break;
                case FKeyFunctions.SYSTEM: good &= f.code >= 1 && f.code <= 0xB7; sawSystem = true; break;
                case FKeyFunctions.KEY: good &= (f.code & 0xFF) != 0 && f.code <= 0xFFFF && (f.macCode == 0 || (f.macCode & 0xFF) != 0); sawKey = true; break;
                default: good = false;
            }
            good &= FKeyFunctions.get(f.id) == f;
            if (!good && bad == null) bad = f.id;
            ok &= good;
        }
        check(ok && sawSystem && sawConsumer && sawKey, "功能表：id 不重复、码在描述符的范围里、get 取得回来" + (bad == null ? "" : "（" + bad + "）"));
        check(FKeyFunctions.all().get(0).id.equals("none"), "\"无\"排第一");
        // 方案 §1 的表：抽查几个码（HUT 1.7 核过）
        Object[][] want = {{"mute", 0xE2}, {"vol_down", 0xEA}, {"vol_up", 0xE9}, {"mic_mute", 0xA9}, {"play_pause", 0xCD}, {"prev_track", 0xB6},
                {"next_track", 0xB5}, {"bright_down", 0x70}, {"bright_up", 0x6F}, {"show_windows", 0x29F}, {"show_apps", 0x2A2}, {"lock", 0x19E},
                {"search", 0x221}, {"next_layout", 0x29D}, {"dictation", 0xD8}, {"emoji", 0xD9}, {"sleep", 0x82}, {"dnd", 0x9B},
                {"f13", 0x68}, {"f24", 0x73}, {"prtsc", 0x46}, {"copilot", 0x72 | 0x0A00}};
        StringBuilder got = new StringBuilder(), exp = new StringBuilder();
        for (Object[] w : want) {
            FKeyFunctions.Function f = FKeyFunctions.get((String) w[0]);
            got.append(w[0]).append('=').append(f == null ? "null" : Integer.toHexString(f.code)).append(' ');
            exp.append(w[0]).append('=').append(Integer.toHexString((Integer) w[1])).append(' ');
        }
        eq(got.toString(), exp.toString(), "抽查的码");
        check(FKeyFunctions.get("mic_mute").kind == FKeyFunctions.SYSTEM && FKeyFunctions.get("dnd").kind == FKeyFunctions.SYSTEM
                && FKeyFunctions.get("sleep").kind == FKeyFunctions.SYSTEM && FKeyFunctions.get("mute").kind == FKeyFunctions.CONSUMER,
                "睡眠、勿扰、麦克风静音是系统键那一组，别的媒体键是多媒体页");
        eq(FKeyFunctions.keyFunctionId(0x06 | Hid.MOD_CTRL << 8), "key:0106", "组合键的 id：key: + 4 位十六进制");
        FKeyFunctions.Function k = FKeyFunctions.get("key:0106");
        check(k != null && k.kind == FKeyFunctions.KEY && k.code == 0x0106 && k.group == FKeyFunctions.GROUP_COMBO, "\"key:0106\" 读回 Ctrl+C");
        check(FKeyFunctions.get("key:0100") == null && FKeyFunctions.get("key:zz00") == null && FKeyFunctions.get("key:12345") == null
                && FKeyFunctions.get("nope") == null && FKeyFunctions.get(null) == null, "认不出的 id（用法码 0、不是十六进制、长度不对、没有这个名字）当 null");
    }

    /** 预设：每个 12 项、都认得出；ThinkPad / 苹果的顺序照方案的表；presetOf 认得出、改一个就是自定义；默认值按模式分。 */
    static void presets() {
        for (String p : new String[]{FKeyFunctions.PRESET_THINKPAD, FKeyFunctions.PRESET_APPLE, FKeyFunctions.PRESET_NONE}) {
            String[] ids = FKeyFunctions.preset(p);
            boolean ok = ids.length == 12;
            for (String id : ids) ok &= FKeyFunctions.get(id) != null;
            check(ok, "预设 " + p + "：12 项都认得出");
            eq(FKeyFunctions.presetOf(ids), p, "预设 " + p + " 认得回来");
        }
        eq(Arrays.asList(FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD)),
                Arrays.asList("mute", "vol_down", "vol_up", "mic_mute", "bright_down", "bright_up", "display", "none", "notifications", "prtsc", "snip", "none"),
                "ThinkPad 风格（方案 §2.1 的表）");
        eq(Arrays.asList(FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE)),
                Arrays.asList("bright_down", "bright_up", "show_windows", "search", "dictation", "dnd", "prev_track", "play_pause", "next_track",
                        "mute", "vol_down", "vol_up"), "苹果风格（方案 §2.1 的表）");
        String[] c = FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD);
        c[7] = "sleep";
        eq(FKeyFunctions.presetOf(c), FKeyFunctions.PRESET_CUSTOM, "改了一个：自定义");
        String[] n = FKeyFunctions.preset(FKeyFunctions.PRESET_NONE);
        n[0] = "gone";
        eq(FKeyFunctions.presetOf(n), FKeyFunctions.PRESET_NONE, "认不出的 id 当\"无\"");
        eq(FKeyFunctions.presetOf(null), FKeyFunctions.PRESET_CUSTOM, "null：自定义");
        eq(FKeyFunctions.preset("???")[0], "none", "认不出的预设当\"不用\"");
        eq(FKeyFunctions.defaultPreset(WIN) + "/" + FKeyFunctions.defaultPreset(MAC), "thinkpad/apple", "默认预设：Windows ThinkPad、Mac 苹果");
        check(!FKeyFunctions.defaultFrowFlip(WIN) && FKeyFunctions.defaultFrowFlip(MAC), "默认平时按出：Windows F1–F12、Mac 第二功能");
    }

    /** Boards 里的存储：键名都以 kbx_ 开头（不碰豆花布局的 kb_）；默认值按模式；两种模式各一套；只写 kbx_。 */
    static void storage() {
        BoardsTest.MapStore s = new BoardsTest.MapStore();
        Boards b = new Boards(s, null);
        check(!b.frowFlip(WIN) && b.frowFlip(MAC), "没存过：Windows 平时 F1–F12、Mac 平时第二功能");
        check(!b.frowFlip(), "现在的模式（默认 Windows）：不翻转");
        b.setMode(MAC);
        check(b.frowFlip(), "换成 Mac 模式：翻转（Mac 那一份）");
        b.setFrowFlip(MAC, false);
        b.setFrowFlip(WIN, true);
        check(!b.frowFlip(MAC) && b.frowFlip(WIN) && !b.frowFlip(), "两种模式各存各的");
        eq(Arrays.asList(b.fkeys(WIN)), Arrays.asList(FKeyFunctions.preset(FKeyFunctions.PRESET_THINKPAD)), "没存过的 F 键：Windows 照 ThinkPad 那套");
        eq(Arrays.asList(b.fkeys(MAC)), Arrays.asList(FKeyFunctions.preset(FKeyFunctions.PRESET_APPLE)), "Mac 照苹果那套");
        b.setFkey(WIN, 8, "sleep");
        eq(b.fkey(WIN, 8) + "/" + b.fkey(MAC, 8), "sleep/play_pause", "改 Windows 的 F8 不动 Mac 的");
        eq(FKeyFunctions.presetOf(b.fkeys(WIN)), FKeyFunctions.PRESET_CUSTOM, "改过：自定义");
        b.applyFkeyPreset(WIN, FKeyFunctions.PRESET_NONE);
        eq(FKeyFunctions.presetOf(b.fkeys(WIN)), FKeyFunctions.PRESET_NONE, "整套换成\"不用\"");
        boolean ok = true;
        for (String k : s.written) ok &= k.startsWith("kbx_") || k.equals(Boards.KEY_MODE);
        check(ok && !s.written.isEmpty(), "只写了 kbx_ 开头的键（和换模式写的 kbx_mode），一个 kb_ 都没碰");
        check(Boards.frowFlipKey(MAC).equals("kbx_frow_flip_mac") && Boards.fkeyKey(WIN, 12).equals("kbx_fkey_win_12"), "键名照方案");
    }

    // ═══════════ 定码 ═══════════

    /** 没翻转、没按 Fn：F 键出 F1–F12，两个槽什么都不发。 */
    static void plainByDefault() {
        KeyHoldsTest.H h = h();
        h.kd(F1, 1000);
        h.ku(F1, 1050);
        h.kd(F3, 1100);
        h.ku(F3, 1150);
        h.kd(F5, 1200);
        h.ku(F5, 1250);
        eq(h.r.keys, KeyHoldsTest.list("[00|3a]", "[00|]", "[00|3c]", "[00|]", "[00|3e]", "[00|]"), "平时：F1 / F3 / F5 出 F 键本身");
        check(h.r.controls.isEmpty(), "多媒体 / 系统键一帧都没发");
    }

    /** 按住 Fn：F 行出第二功能（多媒体 / 系统键 / 组合键），没配的照旧；松开 Fn 回来。 */
    static void fnHeldSecond() {
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.kd(F1, 1050);
        check(h.r.lastConsumer == 0xE2 && h.r.keys.isEmpty(), "Fn + F1：多媒体报静音、键盘报文不动");
        h.ku(F1, 1100);
        h.kd(F5, 1150);
        h.ku(F5, 1200);
        h.ku(FN, 1250);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "c0"), "按下报静音、松开报 0");
        eq(h.r.keys, KeyHoldsTest.list("[00|3e]", "[00|]"), "没配的 F5 照旧出 F5");
        check(h.r.lastF == 0 && h.r.globes == 0, "用过的 Fn 松开：不单次锁");
        h.kd(F1, 1300);
        h.ku(F1, 1350);
        eq(h.r.keys.subList(2, 4), KeyHoldsTest.list("[00|3a]", "[00|]"), "松开 Fn 后 F1 回到 F1");
    }

    /** 翻转着（平时第二功能）：F1 出静音；按住 Fn 出 F1；Fn 层别的键（↑）照旧只看 Fn。 */
    static void flipped() {
        KeyHoldsTest.H h = h();
        flip(h, true);
        h.kd(F1, 1000);
        h.ku(F1, 1050);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "c0"), "翻转着：F1 出静音");
        h.kd(UP, 1100);
        h.ku(UP, 1150);
        h.kd(FN, 1200);
        h.kd(F1, 1250);
        h.ku(F1, 1300);
        h.kd(UP, 1350);
        h.ku(UP, 1400);
        h.ku(FN, 1450);
        eq(h.r.keys, KeyHoldsTest.list("[00|52]", "[00|]", "[00|3a]", "[00|]", "[00|4b]", "[00|]"),
                "翻转只管 F 行：↑ 平时还是 ↑；按住 Fn 时 F1 出 F1、↑ 出 PgUp");
        eq(h.r.controls.size(), 2, "按住 Fn 的那下 F1 没发多媒体");
    }

    /** Fn 单次锁 / 永久锁也算"Fn 起作用"：单次锁只管下一个键（第二功能用掉它），永久锁一直；永久锁 + 翻转 = F 键。 */
    static void fnOnceAndLock() {
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.ku(FN, 1100);   // 单点：单次锁
        check(h.r.lastF == FN_LATCHED, "单点 Fn：单次锁");
        h.kd(F2, 1200);
        check(h.r.lastConsumer == 0xEA, "单次锁着按 F2：音量−");
        h.ku(F2, 1250);
        check(h.r.lastF == 0 && h.r.lastConsumer == 0, "F2 松开：单次锁用掉、音量− 松开");
        h.kd(F2, 1300);
        h.ku(F2, 1350);
        eq(h.r.keys, KeyHoldsTest.list("[00|3b]", "[00|]"), "再按 F2：F2 本身");
        KeyHoldsTest.H g = h();
        g.kd(FN, 1000);
        g.to(1500);
        g.ku(FN, 1500);   // 按住 0.5 秒：永久锁
        check(g.r.lastF == FN_LOCKED, "按住 Fn 0.5 秒：永久锁");
        for (int i = 0; i < 3; i++) {
            g.kd(F6, 2000 + i * 100);
            g.ku(F6, 2050 + i * 100);
        }
        eq(g.r.controls, KeyHoldsTest.list("ce9", "c0", "ce9", "c0", "ce9", "c0"), "永久锁着：F6 一直出音量+");
        flip(g, true);
        g.kd(F6, 3000);
        g.ku(F6, 3050);
        eq(g.r.keys, KeyHoldsTest.list("[00|3f]", "[00|]"), "永久锁 + 翻转：F6 出 F6（正好一个成立才是第二功能）");
    }

    /**
     * Fn+Esc：翻转 F 行——不发 HID、报 FROW_FLIP、{@link KeyHolds#FLIP_BUZZ_MS} 后震一下（当场震会被 Esc 按下的"嗒"盖掉）；
     * Fn 算用过（松开不单次锁）；再按一次翻回来；翻完马上收尾（releaseAll）就不震了。
     */
    static void fnLockToggle() {
        long buzz = KeyHolds.FLIP_BUZZ_MS;
        check(buzz >= 60 && buzz <= 150, "翻转的确认震动晚 " + buzz + "ms：和 Esc 的\"嗒\"分得开，又不至于像另一次操作");
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.kd(ESC, 1050);
        check(h.r.keys.isEmpty() && h.r.controls.isEmpty(), "Fn+Esc：不发任何 HID");
        check(h.r.clicks == 0 && h.r.keyPending && h.r.keyDue == 1050 + buzz, "翻转的那一刻不震（和 Esc 的\"嗒\"同一刻会被盖掉），按键定时器排在 " + buzz + "ms 后");
        check((h.r.lastF & FLIP) != 0 && h.t.frowFlip(), "报界面：F 行翻转着（马上报，不等震动）");
        h.to(1050 + buzz - 1);
        check(h.r.clicks == 0, "还没到点：不震");
        h.to(1050 + buzz);
        check(h.r.clicks == 1 && !h.r.keyPending, "到点震一下（同锁修饰键那一下），定时器不再挂着");
        h.ku(ESC, 1160);
        h.ku(FN, 1170);
        check(h.r.lastF == FLIP && h.r.keys.isEmpty() && h.r.clicks == 1, "Esc、Fn 松开：Fn 不单次锁（当组合用了），只剩翻转那一位，不再震");
        h.kd(F1, 1200);
        h.ku(F1, 1250);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "c0"), "翻转以后：F1 出静音");
        h.kd(FN, 1300);
        h.kd(ESC, 1350);
        h.ku(ESC, 1400);
        h.ku(FN, 1450);
        check(h.r.lastF == 0 && !h.t.frowFlip() && h.r.clicks == 2, "再 Fn+Esc：翻回来、又震一下");
        h.kd(F1, 1500);
        h.ku(F1, 1550);
        eq(h.r.keys, KeyHoldsTest.list("[00|3a]", "[00|]"), "翻回来以后 F1 出 F1");
        // 翻完不到 FLIP_BUZZ_MS 就收尾（退后台、释放独占……）：翻转照旧（是设置），确认的那一下不震了、定时器撤了
        KeyHoldsTest.H g = h();
        g.kd(FN, 1000);
        g.kd(ESC, 1050);
        g.t.releaseAll();
        g.to(3000);
        check(g.r.clicks == 0 && !g.r.keyPending && g.t.frowFlip(), "翻完马上收尾：翻转留着，不再震、定时器撤了");
        // 很快地连翻两次（第二次在第一次的确认震动之前）：只震一下，从第二次算起
        KeyHoldsTest.H d = h();
        d.kd(FN, 1000);
        d.kd(ESC, 1010);
        d.ku(ESC, 1040);
        d.kd(ESC, 1070);
        d.to(1010 + buzz);
        check(d.r.clicks == 0 && !d.t.frowFlip(), "连翻两次：翻回来了，第一次的确认震动不单独震");
        d.to(1070 + buzz);
        check(d.r.clicks == 1, "连翻两次：从第二次算起震一下");
        // 迟到 / 乱序的定时器回调不提前震
        KeyHoldsTest.H e = h();
        e.kd(FN, 1000);
        e.kd(ESC, 1050);
        e.t.onKeyTimer(1050 + buzz - 10);
        check(e.r.clicks == 0, "早到的回调（截止时刻之前）不震");
        e.t.onKeyTimer(1050 + buzz);
        e.t.onKeyTimer(1050 + buzz + 500);
        check(e.r.clicks == 1, "到点的回调震一下，之后过期的回调不再多震");
    }

    /** 没按 Fn 时 Esc 就是 Esc（翻转着也是：翻转只管 F1–F12）。 */
    static void escPlain() {
        KeyHoldsTest.H h = h();
        h.kd(ESC, 1000);
        h.ku(ESC, 1050);
        flip(h, true);
        h.kd(ESC, 1100);
        h.ku(ESC, 1150);
        eq(h.r.keys, KeyHoldsTest.list("[00|29]", "[00|]", "[00|29]", "[00|]"), "Esc 照旧出 Esc（翻不翻转都是）");
        check(h.r.clicks == 0 && h.t.frowFlip(), "不震、不翻");
    }

    /** 单次锁 + Esc：翻转、Esc 松开时用掉单次锁；永久锁 + Esc：翻转、永久锁照旧；锁住的 Shift 不被翻转解开（没有键发给主机）。 */
    static void escWithOnceAndLock() {
        KeyHoldsTest.H h = h();
        h.kd(SHIFT, 1000);
        h.to(1500);
        h.ku(SHIFT, 1500);   // 锁住 Shift
        check(h.r.lastL == Hid.MOD_SHIFT, "锁住 Shift");
        h.kd(FN, 1600);
        h.ku(FN, 1700);   // 单次锁
        h.kd(ESC, 1800);
        check(h.t.frowFlip() && h.r.lastF == (FN_LATCHED | FLIP), "单次锁着按 Esc：翻转，按着时单次锁还在");
        h.ku(ESC, 1850);
        check(h.r.lastF == FLIP, "Esc 松开：单次锁用掉");
        check(h.r.lastL == Hid.MOD_SHIFT, "锁住的 Shift 还锁着（翻转没有键发给主机）");
        eq(h.r.keys, KeyHoldsTest.list("[02|]"), "主机只看到 Shift 按着，没有 Esc");
        KeyHoldsTest.H g = h();
        g.kd(FN, 1000);
        g.to(1500);
        g.ku(FN, 1500);   // 永久锁
        g.kd(ESC, 1600);
        g.ku(ESC, 1650);
        check(g.t.frowFlip() && g.r.lastF == (FN_LOCKED | FLIP), "永久锁着按 Esc：翻转，永久锁照旧");
        g.kd(ESC, 1700);
        g.ku(ESC, 1750);
        check(!g.t.frowFlip() && g.r.lastF == FN_LOCKED && g.r.keys.isEmpty(), "再按 Esc：翻回来（永久锁着 Esc 一直是开关）");
    }

    /** Mac 模式的 fn🌐 + Esc 一样翻转；fn🌐 当组合用了，松开不出 🌐。 */
    static void globeToggle() {
        KeyHoldsTest.H h = h();
        h.kd(FNG, 1000);
        h.kd(ESC, 1050);
        h.ku(ESC, 1100);
        h.ku(FNG, 1150);
        check(h.t.frowFlip() && h.r.globes == 0 && h.r.keys.isEmpty(), "fn🌐 + Esc：翻转、不出 🌐、不发 HID");
    }

    /** 方案风险那条：按着 F 键时 Fn+Esc 翻了，松开照样松掉按下时的码（区域那一路、key() 那一路都看）。 */
    static void heldThroughToggle() {
        for (boolean region : new boolean[]{false, true}) {
            KeyHoldsTest.H h = h();
            String p = region ? "区域那一路：" : "key() 那一路：";
            press(h, region, 0, F1, 1000);   // 平时：F1
            h.kd(FN, 1050);
            h.kd(ESC, 1100);   // 翻转
            h.ku(ESC, 1150);
            h.ku(FN, 1200);
            check(h.t.frowFlip(), p + "按着 F1 时翻转了");
            release(h, region, 0, F1, 1250);
            eq(h.r.keys, KeyHoldsTest.list("[00|3a]", "[00|]"), p + "F1 松开：松的是按下时的 F1，主机上没留着");
            check(h.r.controls.isEmpty(), p + "没发多媒体");
            // 反过来：翻转着按 F1（静音），按着时翻回来，松开松静音
            press(h, region, 1, F1, 1300);
            h.kd(FN, 1350);
            h.kd(ESC, 1400);
            h.ku(ESC, 1450);
            h.ku(FN, 1500);
            release(h, region, 1, F1, 1550);
            eq(h.r.controls, KeyHoldsTest.list("ce2", "c0"), p + "翻转着按下的静音，翻回来以后松开照样松静音");
            eq(h.r.keys.size(), 2, p + "键盘报文没多出 F1");
        }
    }

    /** 按着 F 键时换设置（setConfig 改翻转、同 CMD_CONFIG）：松开照样松按下时的码；先松 Fn 再松 F 键也一样。 */
    static void heldThroughConfig() {
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.kd(F2, 1050);
        h.ku(FN, 1100);   // 先松 Fn
        flip(h, true);
        h.ku(F2, 1150);
        eq(h.r.controls, KeyHoldsTest.list("cea", "c0"), "先松 Fn、换了翻转，F2 松开照样松音量−");
        check(h.r.keys.isEmpty(), "没出 F2");
    }

    /** 系统键：报告 6 那一槽，按下报码、松开报 0。 */
    static void systemKeys() {
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.kd(F3, 1050);
        check(h.r.lastSystem == 0x82 && h.r.lastConsumer == 0, "Fn + F3：系统键报睡眠，多媒体那一槽不动");
        h.ku(F3, 1100);
        h.ku(FN, 1150);
        eq(h.r.controls, KeyHoldsTest.list("s82", "s0"), "睡眠按下、松开");
    }

    /** 第二功能是组合键（Super+P）：走自带修饰键那条路，先按 Super 再按 P、松开反过来。 */
    static void combo() {
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.kd(F4, 1050);
        h.ku(F4, 1100);
        h.ku(FN, 1150);
        eq(h.r.keys, KeyHoldsTest.list("[08|]", "[08|13]", "[08|]", "[00|]"), "Fn + F4 = Super+P：先修饰键、再键，松开反过来");
        check(h.r.lastL == 0 && h.r.lastH == 0, "Super 不是按着、也不锁（自带修饰键不算修饰键手指）");
    }

    /** 同时按两个多媒体键：报后按的；后按的松开报回先按的；先按的先松不发；全松开报 0。每个按下都配松开。 */
    static void oneAtATime() {
        KeyHoldsTest.H h = h();
        flip(h, true);
        h.kd(F1, 1000);
        h.kd(F2, 1050);
        h.ku(F2, 1100);
        h.ku(F1, 1150);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "cea", "ce2", "c0"), "静音 → 音量−（报后按的）→ 松音量− 报回静音 → 全松 0");
        h.r.controls.clear();
        h.kd(F1, 1200);
        h.kd(F2, 1250);
        h.ku(F1, 1300);
        h.ku(F2, 1350);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "cea", "c0"), "先按的先松：不用发，后按的松开报 0");
        h.r.controls.clear();
        h.kd(F1, 1400);
        h.kd(F3, 1500);
        h.ku(F3, 1550);
        h.ku(F1, 1650);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "s82", "s0", "c0"), "多媒体和系统键是两个槽，各报各的");
    }

    /** 第二功能也是一个普通键：解开锁住的修饰键；按着修饰键时按了它，修饰键算用过、不锁；用掉 Fn 单次锁；它按着时按下的修饰键不锁。 */
    static void actsLikePlainKey() {
        KeyHoldsTest.H h = h();
        flip(h, true);
        h.kd(SHIFT, 1000);
        h.to(1500);
        h.ku(SHIFT, 1500);
        check(h.r.lastL == Hid.MOD_SHIFT, "锁住 Shift");
        h.kd(F1, 1600);
        check(h.r.lastKeys().equals("[02|]") && h.r.lastConsumer == 0xE2, "锁住的 Shift 照旧按着（主机看到 Shift + 静音）");
        h.ku(F1, 1650);
        check(h.r.lastL == 0 && h.r.lastKeys().equals("[00|]"), "静音松开：Shift 解开");
        KeyHoldsTest.H g = h();
        flip(g, true);
        g.kd(SHIFT, 1000);
        g.kd(F2, 1100);
        g.ku(F2, 1150);
        g.to(1600);
        g.ku(SHIFT, 1600);
        check(g.r.lastL == 0 && g.r.clicks == 0, "按着 Shift 时按了音量−：Shift 用过，按 0.6 秒也不锁、不震");
        KeyHoldsTest.H k = h();
        flip(k, true);
        k.kd(F6, 1000);
        k.kd(SHIFT, 1050);
        k.to(1600);
        k.ku(SHIFT, 1600);
        k.ku(F6, 1650);
        check(k.r.lastL == 0 && k.r.clicks == 0, "音量+ 按着时按下的 Shift：组合的一部分，不锁");
    }

    /** 收尾：按着多媒体 / 系统键时 releaseAll 各发一帧 0；之后迟到的松开不再多发；什么都没按着时不发。 */
    static void releaseAllZeros() {
        KeyHoldsTest.H h = h();
        flip(h, true);
        h.kd(F1, 1000);
        h.kd(FN, 1050);
        h.kd(F4, 1080);   // 按住 Fn：F4 出 F4（翻转着）
        h.ku(FN, 1090);
        flip(h, false);
        h.kd(FN, 1095);
        h.kd(F3, 1100);   // 按住 Fn、没翻转：F3 出睡眠
        h.t.releaseAll();
        check(h.r.lastConsumer == 0 && h.r.lastSystem == 0 && h.r.lastKeys().equals("[00|]"), "releaseAll：多媒体、系统键、键盘都归零");
        int n = h.r.controls.size();
        h.ku(F1, 1200);
        h.ku(F3, 1250);
        h.ku(F4, 1300);
        h.ku(FN, 1350);
        eq(h.r.controls.size(), n, "releaseAll 之后迟到的松开不再多发");
        h.t.releaseAll();
        eq(h.r.controls.size(), n, "什么都没按着时 releaseAll 不发 0");
    }

    /** 翻转是设置：releaseAll、unlatch 都不清；setConfig 设它但不报界面（App 自己知道）；下一次报状态时带着；TouchRouter.setFrowFlip 一样。 */
    static void flipIsSetting() {
        KeyHoldsTest.H h = h();
        flip(h, true);
        check(h.t.frowFlip() && h.r.mods.isEmpty(), "setConfig 设翻转：不报界面");
        h.kd(SHIFT, 1000);
        check(h.r.lastF == FLIP, "下一次报状态时第四个字节带着 FROW_FLIP");
        h.ku(SHIFT, 1050);
        h.t.releaseAll();
        h.t.unlatch(1100);
        check(h.t.frowFlip() && (h.r.lastF & FLIP) != 0, "releaseAll、unlatch 之后照旧翻转着");
        h.t.setFrowFlip(false);
        int reports = h.r.mods.size();
        check(!h.t.frowFlip() && h.r.mods.size() == reports, "TouchRouter.setFrowFlip：只设、不报");
        h.kd(F1, 1200);
        h.ku(F1, 1250);
        check(h.r.lastKeys().equals("[00|]") && h.r.keys.contains("[00|3a]"), "设回来以后 F1 出 F1");
    }

    /** 被取消的 F 键（手掌、收尾补报的、面板的 KEY_CANCEL）照样松开它按下时的码（同普通键）。 */
    static void canceledReleases() {
        for (boolean region : new boolean[]{false, true}) {
            KeyHoldsTest.H h = h();
            flip(h, true);
            press(h, region, 0, F1, 1000);
            if (region) h.tu(0, 1050, true);
            else h.kc(F1, 1050);
            eq(h.r.controls, KeyHoldsTest.list("ce2", "c0"), (region ? "区域" : "key()") + "：被取消的静音照样松开");
        }
    }

    /** 同一个 F 键两根手指：后来的跟着第一根的码（按着时翻了也一样）；先抬起的那根就松开（同普通键的两个副本）。 */
    static void twoFingersSameKey() {
        KeyHoldsTest.H h = h();
        h.kd(FN, 1000);
        h.td(0, col(F1), 1050);   // 静音
        h.ku(FN, 1100);
        h.td(1, col(F1), 1150);   // Fn 已经松了，但跟着第一根：还是静音
        h.tu(0, 1200, false);
        h.tu(1, 1250, false);
        eq(h.r.controls, KeyHoldsTest.list("ce2", "c0"), "两根手指都是静音，先抬起的那根就松开，第二根不再多发");
        check(h.r.keys.isEmpty(), "没出 F1");
        h.td(2, col(F1), 1300);
        h.tu(2, 1350, false);
        eq(h.r.keys, KeyHoldsTest.list("[00|3a]", "[00|]"), "全抬起以后忘掉：再按是 F1");
    }

    /** 同样的序列走区域那一路和 key() 那一路，主机收到的、报界面的、震动一模一样。 */
    static void pathsAgree() {
        // Fn + F1（静音）→ Fn+Esc 翻转 → F2（音量−）夹着 Shift → F4（Super+P）→ F3（睡眠）→ F5（没配）→ 单点 Fn（单次锁）→ F1（单次锁 ^ 翻转 = F1 本身）
        int[][] seq = {{FN, 1}, {F1, 1}, {F1, 0}, {ESC, 1}, {ESC, 0}, {FN, 0}, {F2, 1}, {SHIFT, 1}, {F2, 0}, {SHIFT, 0}, {F4, 1}, {F4, 0},
                {F3, 1}, {F3, 0}, {F5, 1}, {F5, 0}, {FN, 1}, {FN, 0}, {F1, 1}, {F1, 0}};
        List<String> a = run(seq, false), b = run(seq, true);
        eq(b, a, "区域那一路和 key() 那一路的输出一样");
        check(a.contains("ce2") && a.contains("cea") && a.contains("s82") && a.contains("h" + CLICK) && a.contains("[08|13]") && a.contains("[00|3a]"),
                "序列里真出了多媒体、系统键、翻转、组合键、F 键本身");
    }

    static List<String> run(int[][] seq, boolean region) {
        KeyHoldsTest.H h = h();
        long t = 1000;
        Map<Integer, Integer> slots = new HashMap<>();
        int next = 0;
        for (int[] s : seq) {
            t += 40;
            if (s[1] == 1) {
                int slot = next++;
                slots.put(s[0], slot);
                press(h, region, slot, s[0], t);
            } else {
                release(h, region, slots.remove(s[0]), s[0], t);
            }
        }
        h.t.releaseAll();
        return h.r.all;
    }

    static void press(KeyHoldsTest.H h, boolean region, int slot, int key, long t) {
        if (region) h.td(slot, col(key), t);
        else h.kd(key, t);
    }

    static void release(KeyHoldsTest.H h, boolean region, int slot, int key, long t) {
        if (region) h.tu(slot, t, false);
        else h.ku(key, t);
    }

    // ═══════════ 随机序列 ═══════════

    /**
     * 随机按 Fn / fn🌐 / Esc / F1–F6 / Shift / a，夹着换翻转（setConfig）、releaseAll、unlatch、乱序的定时器回调。每一步查：
     * 每个 F 键按下时出哪一面 = (Fn 起作用 ^ 翻转)（Fn 起作用从报给界面的状态读：按着 / 单次锁 / 永久锁；翻转按模型：setConfig 设、Esc 在 Fn 起作用时翻），
     * 同一个 F 键已经按着时跟着第一根；Esc 在 Fn 起作用时翻转、不发 HID；两个槽报的码 = 还按着的、最后按下的那个（模型）；
     * 全部抬起后（releaseAll 之前）两个槽是 0、键盘只剩锁住的修饰键；releaseAll 后两个槽是 0、翻转不清；
     * 震动次数 = 修饰键快锁了 + Fn 快锁了 + 翻转的确认震动（翻转那一刻不震、{@link KeyHolds#FLIP_BUZZ_MS} 后震；到点前又翻了只震后一次，到点前 releaseAll 就不震）。
     */
    static void randomSequences() {
        int[] keys = {FN, FNG, ESC, F1, F2, F3, F4, F5, F6, SHIFT, A};
        int steps = 0, seconds = 0, plainF = 0, toggles = 0, bad = 0, multi = 0;
        String firstBad = null;
        for (int seed = 1; seed <= 400; seed++) {
            Random rnd = new Random(seed);
            KeyHoldsTest.H h = h();
            boolean flip = rnd.nextBoolean();
            flip(h, flip);
            long t = 1000;
            Map<Integer, Integer> count = new HashMap<>();
            Map<Integer, Integer> code = new HashMap<>();   // F 键 → 按下时定的内部码（-1 = F 键本身、-2 = 翻转）
            List<Integer> consHeld = new ArrayList<>(), sysHeld = new ArrayList<>();
            int toggled = 0, buzzed = 0;
            long buzzAt = -1;   // 模型：翻转的确认震动排在哪一刻（-1 = 没排着）
            for (int step = 0; step < 150; step++) {
                steps++;
                t += rnd.nextInt(5) == 0 ? 300 + rnd.nextInt(900) : rnd.nextInt(120);
                if (buzzAt >= 0 && t >= buzzAt) {   // 这一步的入口（h.to / 带时间的入口）会先把它震掉
                    buzzed++;
                    buzzAt = -1;
                }
                int op = rnd.nextInt(24);
                int key = keys[rnd.nextInt(keys.length)];
                int n = count.getOrDefault(key, 0);
                String why = null;
                KeyHoldsTest.R r = h.r;
                if (op < 10) {   // 按下
                    if (n >= 2) continue;
                    h.to(t);
                    boolean active = (r.lastF & (FN_HELD | FN_LATCHED | FN_LOCKED)) != 0;
                    int keysBefore = r.keys.size(), clicksBefore = r.clicks, controlsBefore = r.controls.size();
                    if (op < 6) h.kd(key, t);
                    else h.td(n, col(key), t);
                    if (Proto.isFrowKey(key)) {
                        int type = Proto.frowType(key);
                        int want;
                        if (n > 0) {
                            want = code.get(key);
                        } else if (type == Proto.FROW_FN_LOCK) {
                            want = active ? -2 : -1;
                        } else {
                            want = active ^ flip ? Proto.frowSecond(key) : -1;
                        }
                        code.put(key, want);
                        if (n == 0 && want == -2) {
                            flip = !flip;
                            toggled++;
                            toggles++;
                            buzzAt = t + KeyHolds.FLIP_BUZZ_MS;
                            if (r.keys.size() != keysBefore) why = "Fn+Esc 发了键盘报文 " + r.lastKeys();
                            if (r.clicks != clicksBefore) why = "Fn+Esc 当场就震了（应该晚 " + KeyHolds.FLIP_BUZZ_MS + "ms，不然被 Esc 的\"嗒\"盖掉）";
                            if (!r.keyPending || r.keyDue > buzzAt) why = "Fn+Esc 之后按键定时器没排到确认震动那一刻";
                        } else if (want == -1) {
                            plainF++;
                            int base = Proto.frowBase(key);
                            // 键盘满了（6 个键）第 7 个按不下去，同真键盘：那时不查
                            if (r.controls.size() != controlsBefore || (!full(r.lastKeys()) && !has(r.lastKeys(), base))) {
                                why = "按下 " + Integer.toHexString(key) + "（Fn " + (active ? "起作用" : "不起作用") + "、翻转 " + flip + "）：想要 F 键本身，报文 " + r.lastKeys();
                            }
                        } else if (want >= 0) {
                            if (n == 0) seconds++;
                            // 一个槽报最后按下的：同一个码再按一次（第二根手指）挪到最后
                            if (type == Proto.FROW_CONSUMER) {
                                consHeld.remove(Integer.valueOf(want));
                                consHeld.add(want);
                            } else if (type == Proto.FROW_SYSTEM) {
                                sysHeld.remove(Integer.valueOf(want));
                                sysHeld.add(want);
                            } else if (n == 0 && !full(r.lastKeys()) && !(has(r.lastKeys(), want & 0xFF) && r.lastKeys().startsWith(String.format("[%02x", 0x08 | r.lastL | r.lastH)))) {
                                why = "按下 " + Integer.toHexString(key) + "：想要组合键 " + Integer.toHexString(want) + "，报文 " + r.lastKeys();
                            }
                            if (type != Proto.FROW_KEY && r.keys.size() != keysBefore && n == 0 && r.lastL == 0) {
                                why = "第二功能是多媒体 / 系统键，却发了键盘报文 " + r.lastKeys();
                            }
                        }
                    }
                    count.put(key, n + 1);
                } else if (op < 18) {   // 抬起（有时被取消），都经 key()
                    if (n == 0) continue;
                    h.to(t);
                    if (rnd.nextInt(8) == 0) h.kc(key, t);
                    else h.ku(key, t);
                    count.put(key, n - 1);
                    if (Proto.isFrowKey(key)) {
                        int c = code.get(key);
                        if (c >= 0) {   // 先抬起的那根就松开（同普通键）
                            if (Proto.frowType(key) == Proto.FROW_CONSUMER) consHeld.remove(Integer.valueOf(c));
                            if (Proto.frowType(key) == Proto.FROW_SYSTEM) sysHeld.remove(Integer.valueOf(c));
                        }
                        if (n == 1) code.remove(key);
                    }
                } else if (op == 18) {   // 换翻转（设置页改了 / App 同步）
                    h.to(t);
                    flip = rnd.nextBoolean();
                    flip(h, flip);
                } else if (op == 19) {
                    h.t.onKeyTimer(t - rnd.nextInt(2000));   // 乱序的定时器回调
                } else if (op == 20) {
                    h.to(t);
                    h.t.unlatch(t);
                } else if (op == 21) {
                    h.to(t);
                    h.t.releaseAll();
                    buzzAt = -1;   // 还没到点的确认震动跟着撤了
                    count.clear();
                    code.clear();
                    consHeld.clear();
                    sysHeld.clear();
                } else {   // 鼠标：点一下 / 滚一格
                    h.to(t);
                    if (rnd.nextBoolean()) h.t.click(Proto.BUTTON_LEFT);
                    else h.t.wheel(1);
                }
                if (consHeld.size() > 1) multi++;
                int wantC = consHeld.isEmpty() ? 0 : consHeld.get(consHeld.size() - 1), wantS = sysHeld.isEmpty() ? 0 : sysHeld.get(sysHeld.size() - 1);
                if (why == null && (r.lastConsumer != wantC || r.lastSystem != wantS)) {
                    why = "两个槽：多媒体 " + Integer.toHexString(r.lastConsumer) + "（想要 " + Integer.toHexString(wantC) + "）、系统键 "
                            + Integer.toHexString(r.lastSystem) + "（想要 " + Integer.toHexString(wantS) + "）";
                }
                if (why == null && h.t.frowFlip() != flip) why = "翻转：模型 " + flip + "，实际 " + h.t.frowFlip();
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
            if (buzzAt >= 0) buzzed++;
            KeyHoldsTest.R r = h.r;
            String settled = r.lastKeys(), want = String.format("[%02x|]", r.lastL);
            if ((!r.keys.isEmpty() && !settled.equals(want)) || r.lastConsumer != 0 || r.lastSystem != 0) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：全部抬起后（releaseAll 之前）还按着 " + settled + " 多媒体 " + r.lastConsumer + " 系统键 " + r.lastSystem;
            }
            h.t.releaseAll();
            boolean ok = (r.lastKeys().isEmpty() || r.lastKeys().equals("[00|]")) && r.lastConsumer == 0 && r.lastSystem == 0 && r.lastL == 0
                    && (r.lastF & ~FLIP) == 0 && h.t.frowFlip() == flip && !r.keyPending;
            if (!ok) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：收尾后没归零 / 翻转被清了 " + r.lastKeys() + " Fn " + r.lastF;
            }
            if (r.clicks != r.armedRises + r.fnArmedRises + buzzed) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：震了 " + r.clicks + " 次，快锁了 " + r.armedRises + " + " + r.fnArmedRises
                        + "、翻转的确认震动 " + buzzed + "（翻转 " + toggled + " 次）";
            }
            // 每个非 0 的码都配了 0（收尾后两个槽最后一帧都是 0；槽里一次一个码，所以按下 / 松开成对）
            int nonzero = 0;
            for (String s : r.controls) if (!s.equals("c0") && !s.equals("s0")) nonzero++;
            if (nonzero > 0 && !(r.controls.contains("c0") || r.controls.contains("s0"))) {
                bad++;
                if (firstBad == null) firstBad = "种子 " + seed + "：发了 " + nonzero + " 个码，一个 0 都没发";
            }
        }
        check(bad == 0, "F 行随机序列：" + (firstBad == null ? "" : firstBad));
        check(seconds > 500 && plainF > 500 && toggles > 100 && multi > 50,
                "随机序列里真出过第二功能、F 键本身、翻转、同时按着两个多媒体键（" + seconds + " / " + plainF + " / " + toggles + " / " + multi + "）");
        System.err.println("  F 行随机序列：" + steps + " 步，第二功能 " + seconds + " 次、F 键本身 " + plainF + " 次、翻转 " + toggles + " 次、两个多媒体键同时按着 " + multi + " 步");
    }

    /** 键盘报文（"[修饰键|键 键…]"）里有没有这个用法码。 */
    static boolean has(String report, int usage) {
        return report.matches(".*[| ]" + String.format("%02x", usage) + "[] ].*");
    }

    /** 键盘报文里 6 个键都满了。 */
    static boolean full(String report) {
        int bar = report.indexOf('|');
        return bar >= 0 && report.substring(bar + 1, report.length() - 1).trim().split(" ").length >= 6;
    }

    // ═══════════ 工具 ═══════════

    static void eq(Object actual, Object expected, String what) {
        check(expected.equals(actual), what + "（期望 " + expected + "，实际 " + actual + "）");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
