package dev.douhua;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * 手柄页的组件清单（GamepadSpec）的离线测试：15 个预设（编号不重复、第一批 9 个的字和输出照旧）、⋯ 菜单的轮换（存过自定义才轮到它）、
 * 每种面键排法 × 每种字样的字和输出（个数对、输出不重复、都在 Proto.GP_OUTPUTS 里）、几处约定（N64 / GameCube / Neo Geo / 世嘉 / 数字）、
 * 预设自己的肩键名只在字样没换时用，自定义存成一行再读回来（往返一样、坏数据不闪退、缺的照 base 那个预设）。
 */
public final class GamepadSpecTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        presets();
        rotation();
        everyFaceAndStyle();
        firstBatchUnchanged();
        conventions();
        shoulderNames();
        customRoundTrip();
        customDecodeRobust();
        System.err.println("手柄组件清单：" + checks + " 项检查，" + failures + " 项失败");
        if (failures > 0) System.exit(1);
    }

    static String labels(GamepadSpec.Key[] keys) {
        StringBuilder s = new StringBuilder();
        for (GamepadSpec.Key k : keys) s.append(s.length() > 0 ? " " : "").append(k.label);
        return s.toString();
    }

    static String outputs(GamepadSpec.Key[] keys) {
        StringBuilder s = new StringBuilder();
        for (GamepadSpec.Key k : keys) s.append(s.length() > 0 ? " " : "").append(k.output);
        return s.toString();
    }

    static void presets() {
        Set<String> ids = new HashSet<>();
        for (GamepadSpec s : GamepadSpec.PRESETS) {
            check(ids.add(s.id), "预设编号不重复：" + s.id);
            check(!s.isCustom() && s.base.equals(s.id), s.id + "：预设的 base 是自己");
            check(GamepadSpec.byId(s.id) == s && GamepadSpec.isPreset(s.id), s.id + "：byId 找得到");
        }
        eq(GamepadSpec.PRESETS.length, 15, "预设一共 15 个");
        eq(GamepadSpec.PRESETS[0].id, GamepadSpec.DEFAULT_ID, "第一个是默认（Xbox）");
        eq(GamepadSpec.byId("没有这个").id, "xbox", "认不出的 id 按默认");
        eq(GamepadSpec.byId(GamepadSpec.CUSTOM_ID).id, "xbox", "byId 只认预设（自定义由 Prefs 解）");
        check(!GamepadSpec.isPreset(GamepadSpec.CUSTOM_ID), "自定义不是预设");
    }

    /** ⋯ 菜单里"预设：…"：按 PRESETS 的顺序；最后一个之后，存过自定义才轮到自定义，自定义之后回到第一个。 */
    static void rotation() {
        String id = GamepadSpec.PRESETS[0].id;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < GamepadSpec.PRESETS.length; i++) {
            seen.add(id);
            id = GamepadSpec.next(id, false);
        }
        eq(seen.size(), GamepadSpec.PRESETS.length, "没有自定义：轮一圈每个预设都到过");
        eq(id, GamepadSpec.PRESETS[0].id, "没有自定义：轮一圈回到第一个");
        String last = GamepadSpec.PRESETS[GamepadSpec.PRESETS.length - 1].id;
        eq(GamepadSpec.next(last, true), GamepadSpec.CUSTOM_ID, "存过自定义：最后一个预设之后是自定义");
        eq(GamepadSpec.next(GamepadSpec.CUSTOM_ID, true), GamepadSpec.PRESETS[0].id, "自定义之后回到第一个");
        eq(GamepadSpec.next("没有这个", true), GamepadSpec.PRESETS[0].id, "认不出的回到第一个");
    }

    /** 每种排法 × 每种字样：个数和输出表对得上，字不空，输出在范围里、一簇里不重复（不然两颗键发同一个、计数会乱）。 */
    static void everyFaceAndStyle() {
        boolean ok = true;
        String bad = "";
        for (int face = 0; face <= GamepadSpec.FACE_NONE; face++) {
            int[] out = GamepadSpec.faceOutputs(face);
            Set<Integer> seen = new HashSet<>();
            for (int o : out) {
                if (o < 0 || o >= Proto.GP_OUTPUTS || !seen.add(o)) {
                    ok = false;
                    bad = "排法 " + face + " 的输出 " + o;
                }
            }
            for (int style = 0; style <= GamepadSpec.LABELS_NUMBER; style++) {
                String[] lab = GamepadSpec.faceLabels(face, style);
                if (lab.length != out.length) {
                    ok = false;
                    bad = "排法 " + face + " 字样 " + style + "：个数不对";
                }
                for (String l : lab) {
                    if (l == null || l.isEmpty() || "?".equals(l)) {
                        ok = false;
                        bad = "排法 " + face + " 字样 " + style + "：有空的字";
                    }
                }
            }
        }
        check(ok, "每种排法 × 每种字样：字和输出对得上" + (ok ? "" : "：" + bad));
        eq(GamepadSpec.faceOutputs(GamepadSpec.FACE_NONE).length, 0, "不要面键：没有键");
    }

    /** 第一批 9 个预设的字和输出照旧（第二批把 Switch 的字样并进了任天堂：有 Home 时中间是 − ⌂ +）。 */
    static void firstBatchUnchanged() {
        GamepadSpec x = GamepadSpec.byId("xbox");
        eq(labels(x.faceKeys()), "Y X B A", "Xbox 面键");
        eq(outputs(x.faceKeys()), Proto.GP_NORTH + " " + Proto.GP_WEST + " " + Proto.GP_EAST + " " + Proto.GP_SOUTH, "Xbox 面键按北 西 东 南");
        eq(labels(x.leftShoulders()) + " | " + labels(x.rightShoulders()), "LT LB | RT RB", "Xbox 肩键（从外往里）");
        eq(labels(x.middle()), "⧉ ⌂ ≡", "Xbox 中间");
        eq(x.leftThumb().label + " " + x.rightThumb().label, "LS RS", "Xbox 摇杆按下");
        GamepadSpec ps = GamepadSpec.byId("playstation");
        eq(labels(ps.faceKeys()), "△ □ ○ ×", "PlayStation 面键");
        eq(labels(ps.leftShoulders()) + " | " + labels(ps.rightShoulders()), "L2 L1 | R2 R1", "PlayStation 肩键");
        eq(labels(ps.middle()), "Create PS Options", "PlayStation 中间");
        eq(ps.leftThumb().label, "L3", "PlayStation 摇杆按下");
        check(ps.left == GamepadSpec.LEFT_DPAD_TOP, "PlayStation：十字键在上");
        GamepadSpec sw = GamepadSpec.byId("switch");
        eq(labels(sw.faceKeys()), "X Y A B", "Switch 面键");
        eq(labels(sw.leftShoulders()) + " | " + labels(sw.rightShoulders()), "ZL L | ZR R", "Switch 肩键");
        eq(labels(sw.middle()), "− ⌂ +", "Switch 中间");
        GamepadSpec snes = GamepadSpec.byId("snes");
        eq(labels(snes.faceKeys()) + " / " + labels(snes.middle()) + " / " + labels(snes.leftShoulders()), "X Y A B / SELECT START / L", "SNES / DS");
        GamepadSpec nes = GamepadSpec.byId("nes");
        eq(labels(nes.faceKeys()) + " " + outputs(nes.faceKeys()), "B A " + Proto.GP_SOUTH + " " + Proto.GP_EAST, "NES：B 南、A 东");
        GamepadSpec gb = GamepadSpec.byId("gameboy");
        check(gb.portrait && gb.face == GamepadSpec.FACE_DIAG2 && "B A".equals(labels(gb.faceKeys())), "Game Boy：偏好竖屏、B A 斜排");
        eq(labels(GamepadSpec.byId("gba").leftShoulders()), "L", "GBA：有 L");
        GamepadSpec md6 = GamepadSpec.byId("md6");
        eq(labels(md6.faceKeys()), "A B C X Y Z", "MD 6 键面键");
        eq(outputs(md6.faceKeys()), Proto.GP_WEST + " " + Proto.GP_SOUTH + " " + Proto.GP_EAST + " " + Proto.GP_L1 + " " + Proto.GP_NORTH + " " + Proto.GP_R1,
                "MD 6 键：A 西 B 南 C 东 X L1 Y 北 Z R1");
        eq(labels(md6.middle()), "MODE START", "MD 6 键中间");
        GamepadSpec a8 = GamepadSpec.byId("arcade8");
        eq(labels(a8.faceKeys()), "X Y RB LB A B RT LT", "街机 8 键面键");
        check(a8.arcadeStick && "SELECT ⌂ START".equals(labels(a8.middle())), "街机 8 键：摇杆底座的画法、中间 SELECT ⌂ START");
    }

    /** 第二批的约定：N64 A 南 B 西 + C 键；GameCube 按位置；Neo Geo A B C D；世嘉字样在菱形上退回 Xbox 的字；数字的菱形照 PC 手柄。 */
    static void conventions() {
        GamepadSpec n64 = GamepadSpec.byId("n64");
        eq(outputs(n64.faceKeys()), Proto.GP_SOUTH + " " + Proto.GP_WEST + " " + Proto.GP_C_UP + " " + Proto.GP_C_LEFT + " " + Proto.GP_C_RIGHT + " " + Proto.GP_C_DOWN,
                "N64：A 南 B 西 C↑ C← C→ C↓");
        eq(labels(n64.leftShoulders()) + " | " + labels(n64.rightShoulders()), "Z L | R", "N64 肩键：Z（左扳机）L | R");
        eq(labels(n64.middle()), "START", "N64 中间只有 START");
        check(!n64.rightStick && n64.leftThumb() == null, "N64：没有右摇杆、摇杆不能按");
        GamepadSpec gc = GamepadSpec.byId("gamecube");
        eq(labels(gc.faceKeys()) + " " + outputs(gc.faceKeys()),
                "A B X Y " + Proto.GP_SOUTH + " " + Proto.GP_WEST + " " + Proto.GP_EAST + " " + Proto.GP_NORTH, "GameCube：A 南 B 西 X 东 Y 北");
        eq(labels(gc.leftShoulders()) + " | " + labels(gc.rightShoulders()), "L | R Z", "GameCube 肩键：L | R Z");
        GamepadSpec neo = GamepadSpec.byId("neogeo");
        eq(labels(neo.faceKeys()) + " " + outputs(neo.faceKeys()),
                "A B C D " + Proto.GP_SOUTH + " " + Proto.GP_EAST + " " + Proto.GP_WEST + " " + Proto.GP_NORTH, "Neo Geo：A 南 B 东 C 西 D 北");
        GamepadSpec md3 = GamepadSpec.byId("md3");
        eq(labels(md3.faceKeys()) + " / " + labels(md3.middle()), "A B C / START", "MD 3 键");
        eq(labels(GamepadSpec.byId("arcade6").faceKeys()), "X Y RB A B RT", "街机 6 键 = 8 键的前三列");
        check(GamepadSpec.byId("wii").thumbs == false && "− ⌂ +".equals(labels(GamepadSpec.byId("wii").middle())), "Wii 经典：摇杆不能按、中间 − ⌂ +");
        eq(String.join(" ", GamepadSpec.faceLabels(GamepadSpec.FACE_DIAMOND, GamepadSpec.LABELS_SEGA)), "Y X B A", "世嘉字样的菱形 = Xbox 的字（Dreamcast）");
        eq(String.join(" ", GamepadSpec.faceLabels(GamepadSpec.FACE_ROW2, GamepadSpec.LABELS_SEGA)), "B C", "世嘉字样的两颗 = MD 的 B C");
        eq(String.join(" ", GamepadSpec.faceLabels(GamepadSpec.FACE_DIAMOND, GamepadSpec.LABELS_NUMBER)), "4 1 3 2", "数字的菱形：1 左 2 下 3 右 4 上");
        eq(String.join(" ", GamepadSpec.faceLabels(GamepadSpec.FACE_SIX, GamepadSpec.LABELS_NUMBER)), "4 5 6 1 2 3", "数字的 6 颗：上排 1 2 3、下排 4 5 6");
        eq(String.join(" ", GamepadSpec.faceLabels(GamepadSpec.FACE_N64, GamepadSpec.LABELS_PLAYSTATION)),
                "A B " + GamepadSpec.C_UP + " " + GamepadSpec.C_LEFT + " " + GamepadSpec.C_RIGHT + " " + GamepadSpec.C_DOWN, "N64 的键帽不跟字样");
        eq(String.join(" ", GamepadSpec.faceLabels(GamepadSpec.FACE_ARC4, GamepadSpec.LABELS_XBOX)), "A B X Y", "弧形四颗在 Xbox 字样下按输出码");
        // 任天堂字样：有 Home 才是 − ⌂ +
        GamepadSpec.Builder b = new GamepadSpec.Builder(GamepadSpec.byId("snes").asCustom());
        b.home = true;
        eq(labels(b.build().middle()), "− ⌂ +", "任天堂字样加了 Home：中间变成 − ⌂ +");
        check(GamepadSpec.byId("xbox").hasDpad() && GamepadSpec.byId("xbox").hasLeftStick(), "Xbox：十字键和左摇杆都有");
        b.left = GamepadSpec.LEFT_NONE;
        GamepadSpec none = b.build();
        check(!none.hasDpad() && !none.hasLeftStick(), "左手不放：没有十字键也没有左摇杆");
    }

    /** 预设自己的肩键名（N64 的 Z、GameCube 的 L R Z、Wii 的 L ZL）只在字样还是它原来那种时用；换了字样就照字样。 */
    static void shoulderNames() {
        GamepadSpec.Builder b = new GamepadSpec.Builder(GamepadSpec.byId("n64").asCustom());
        eq(labels(b.build().leftShoulders()), "Z L", "N64 复制成自定义：字样没换，Z 还是 Z");
        b.labels = GamepadSpec.LABELS_XBOX;
        eq(labels(b.build().leftShoulders()) + " | " + labels(b.build().rightShoulders()), "LT LB | RB", "N64 换成 Xbox 字样：LT LB | RB");
        b.labels = GamepadSpec.LABELS_NINTENDO;
        b.rt = true;
        eq(labels(b.build().rightShoulders()), "ZR R", "N64 加了右扳机：预设没写的按字样（ZR）");
        GamepadSpec decoded = GamepadSpec.decode(b.build().encode());
        eq(labels(decoded.leftShoulders()) + " | " + labels(decoded.rightShoulders()), "Z L | ZR R", "存了再读回来：肩键名照样跟 base");

        // 设置页"肩键 / 扳机"两个开关写这一套里实际叫什么（Wii 经典手柄靠里那对叫 ZL ZR、外角那对叫 L R，和别家反着）
        GamepadSpec wii = GamepadSpec.byId("wii").asCustom();
        eq(wii.shoulderLabel(Proto.GP_L1) + " " + wii.shoulderLabel(Proto.GP_R1) + " | " + wii.shoulderLabel(Proto.GP_LT) + " "
                + wii.shoulderLabel(Proto.GP_RT), "ZL ZR | L R", "Wii 自定义：肩键开关叫 ZL ZR、扳机开关叫 L R");
        GamepadSpec xbox = GamepadSpec.byId("xbox");
        eq(xbox.shoulderLabel(Proto.GP_L1) + " " + xbox.shoulderLabel(Proto.GP_LT), "LB LT", "Xbox：LB、LT");
        b = new GamepadSpec.Builder(GamepadSpec.byId("gamecube").asCustom());
        eq(b.build().shoulderLabel(Proto.GP_L1), "ZL", "GameCube 没有左肩键也给名字（开关上写）：ZL");
        b.l1 = true;
        eq(labels(b.build().leftShoulders()) + " | " + labels(b.build().rightShoulders()), "L ZL | R Z",
                "GameCube 自定义打开肩键：左边新出来的叫 ZL，不和左扳机 L 重名");

        // 每个预设复制成自定义、每种字样、四颗全开：四颗的名字互不相同（同一边两颗重名就看不出哪颗是扳机）
        boolean distinct = true;
        String bad = "";
        for (GamepadSpec p : GamepadSpec.PRESETS) {
            for (int style = GamepadSpec.LABELS_XBOX; style <= GamepadSpec.LABELS_NUMBER; style++) {
                GamepadSpec.Builder c = new GamepadSpec.Builder(p.asCustom());
                c.labels = style;
                c.l1 = c.r1 = c.lt = c.rt = true;
                GamepadSpec g = c.build();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (GamepadSpec.Key k : g.leftShoulders()) seen.add(k.label);
                for (GamepadSpec.Key k : g.rightShoulders()) seen.add(k.label);
                eq(g.leftShoulders().length + g.rightShoulders().length, 4, p.id + " 字样 " + style + "：四颗全开有四颗");
                if (seen.size() != 4) {
                    distinct = false;
                    bad = p.id + " 字样 " + style + "：" + labels(g.leftShoulders()) + " | " + labels(g.rightShoulders());
                }
                check(g.shoulderLabel(Proto.GP_L1).equals(g.leftShoulders()[1].label)
                        && g.shoulderLabel(Proto.GP_LT).equals(g.leftShoulders()[0].label)
                        && g.shoulderLabel(Proto.GP_RT).equals(g.rightShoulders()[0].label)
                        && g.shoulderLabel(Proto.GP_R1).equals(g.rightShoulders()[1].label),
                        p.id + " 字样 " + style + "：开关上写的名字和键帽上的一样");
            }
        }
        check(distinct, "每个预设 × 每种字样四颗全开：肩键名互不相同" + (distinct ? "" : "（" + bad + "）"));
    }

    /** 每个预设复制成自定义、随机组合：存成一行再读回来一模一样（key 相等），偏好方向、街机画法跟着 base。 */
    static void customRoundTrip() {
        for (GamepadSpec p : GamepadSpec.PRESETS) {
            GamepadSpec c = p.asCustom();
            check(c.isCustom() && c.base.equals(p.id) && c.name == null, p.id + "：复制成自定义记着 base");
            GamepadSpec back = GamepadSpec.decode(c.encode());
            check(back != null && back.key().equals(c.key()) && same(back, c), p.id + "：自定义存了再读回来一样");
            check(back != null && back.portrait == p.portrait && back.arcadeStick == p.arcadeStick, p.id + "：偏好方向、街机画法跟着 base");
        }
        Random rnd = new Random(1101);
        boolean ok = true;
        for (int i = 0; i < 2000 && ok; i++) {
            GamepadSpec.Builder b = new GamepadSpec.Builder(GamepadSpec.PRESETS[rnd.nextInt(GamepadSpec.PRESETS.length)].asCustom());
            b.left = rnd.nextInt(5);
            b.face = rnd.nextInt(11);
            b.labels = rnd.nextInt(6);
            b.rightStick = rnd.nextBoolean();
            b.l1 = rnd.nextBoolean();
            b.r1 = rnd.nextBoolean();
            b.lt = rnd.nextBoolean();
            b.rt = rnd.nextBoolean();
            b.select = rnd.nextBoolean();
            b.home = rnd.nextBoolean();
            b.start = rnd.nextBoolean();
            b.thumbs = rnd.nextBoolean();
            GamepadSpec c = b.build();
            GamepadSpec back = GamepadSpec.decode(c.encode());
            if (back == null || !same(back, c) || !back.key().equals(c.key())) ok = false;
        }
        check(ok, "随机 2000 套自定义：存了再读回来一样");
        GamepadSpec a = GamepadSpec.byId("xbox").asCustom(), b2 = GamepadSpec.byId("xbox").asCustom();
        check(a.key().equals(b2.key()) && !a.key().equals(GamepadSpec.byId("xbox").key()), "两份内容一样的自定义 key 相等，和预设不相等");
    }

    /** 坏数据：没存过 = null；乱写的、认不出的项和取值都跳过（照 base 那个预设）；base 认不出按默认。 */
    static void customDecodeRobust() {
        check(GamepadSpec.decode(null) == null && GamepadSpec.decode("") == null && GamepadSpec.decode("  ") == null, "没存过：null");
        GamepadSpec g = GamepadSpec.decode("乱写的;;=;===;face");
        check(g != null && g.isCustom() && g.base.equals("xbox") && same(g, GamepadSpec.byId("xbox").asCustom()), "乱写的：照默认预设");
        g = GamepadSpec.decode("base=gamecube;face=星形;labels=;rs=2;l1=yes;新的项=1");
        GamepadSpec gc = GamepadSpec.byId("gamecube").asCustom();
        check(g != null && same(g, gc), "认不出的取值、认不出的项：照 base 那个预设");
        g = GamepadSpec.decode("base=没有这个;left=none;face=none");
        check(g != null && g.base.equals("xbox") && g.left == GamepadSpec.LEFT_NONE && g.face == GamepadSpec.FACE_NONE, "base 认不出按默认，别的照存的");
        g = GamepadSpec.decode("base=nes;rs=1;lt=1;rt=1");   // 缺的项（左手、面键……）照 nes
        check(g != null && g.face == GamepadSpec.FACE_ROW2 && g.rightStick && g.lt && g.rt && !g.l1, "缺的项照 base，存了的盖上去");
        GamepadSpec.Builder b = new GamepadSpec.Builder(GamepadSpec.byId("xbox").asCustom());
        b.left = 99;
        b.face = -3;
        b.labels = 42;
        GamepadSpec fixed = b.build();
        check(fixed.left == GamepadSpec.LEFT_DPAD && fixed.face == GamepadSpec.FACE_DIAMOND && fixed.labels == GamepadSpec.LABELS_XBOX,
                "越界的取值收回默认（encode 不会越界）");
        check(GamepadSpec.decode(fixed.encode()) != null, "收回后照样存得下");
    }

    static boolean same(GamepadSpec a, GamepadSpec b) {
        return a.left == b.left && a.face == b.face && a.labels == b.labels && a.rightStick == b.rightStick
                && a.l1 == b.l1 && a.r1 == b.r1 && a.lt == b.lt && a.rt == b.rt && a.select == b.select && a.home == b.home
                && a.start == b.start && a.thumbs == b.thumbs && a.base.equals(b.base) && a.portrait == b.portrait && a.arcadeStick == b.arcadeStick;
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!actual.equals(expected)) {
            failures++;
            System.err.println("✗ " + what + "：期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("✗ " + what);
        }
    }
}
