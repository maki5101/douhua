package dev.douhua;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手柄页摆哪些控件：一组参数（左手 / 面键排法 / 字样 / 右摇杆 / 肩键 / 中间 / 摇杆按下 / 偏好方向），预设就是这组参数的一种取值，
 * "自定义"是用户自己的一组（从任一预设复制出来再改，{@link #asCustom}；存成一行文字，{@link #encode} / {@link #decode}）。纯 Java（离线测）。
 *
 * 内部只认位置（南 / 东 / 西 / 北……，Proto.GP_*），键帽上的字是字样的属性：**按位置发**——任天堂系右边那颗"A"发东（= Xbox B），
 * 下边那颗"B"发南（= Xbox A），和 Linux / SDL / 安卓 / RetroArch 的约定一致，电脑上用模拟器时字和功能对得上；
 * 电脑原生游戏里屏幕提示是 Xbox 的字，位置对、字不对（用户 2026-11-01 照推荐定的）。
 * 用户说的"Game Boy 有 ABXY、L R"其实是 DS（按键等于超任手柄），预设按真机分：Game Boy（B A）、GBA（再加 L R）、"SNES / DS"（ABXY + L R）。
 * 第二批的约定（没有公认答案，照模拟器常见的默认，凭记忆）：N64 的 A 南、B 西、C 键 = 右摇杆推满、Z = 左扳机；
 * GameCube 按位置 A 南、B 西（在 A 左下）、X 东、Y 北、Z = R1、L / R = 扳机；Neo Geo 照 Neo Geo CD 手柄 A 南 B 东 C 西 D 北；
 * 街机 6 键 = 8 键的前三列（上排 西 北 R1、下排 南 东 RT）；Wii 经典手柄（初代）L / R 是扳机（模拟的、在外角）、ZL / ZR 是肩键（在里边）。
 */
final class GamepadSpec {
    // ── 左手 ──
    static final int LEFT_DPAD = 0;
    static final int LEFT_STICK = 1;
    /** 两个都有，摇杆在上靠外、十字键在下靠里（Xbox、Switch Pro）。 */
    static final int LEFT_STICK_TOP = 2;
    /** 两个都有，十字键在上靠外、摇杆在下靠里（PlayStation）。 */
    static final int LEFT_DPAD_TOP = 3;
    /** 左手什么都不放（自定义）。 */
    static final int LEFT_NONE = 4;

    // ── 面键怎么排（GamepadPlan.faceCenters 按它摆，{@link #faceOutputs} 是每颗发什么） ──
    /** 两颗横排（NES、GBA：B 左 A 右）。 */
    static final int FACE_ROW2 = 0;
    /** 两颗斜排（Game Boy：B 左下、A 右上，约 25°）。 */
    static final int FACE_DIAG2 = 1;
    /** 四颗菱形。 */
    static final int FACE_DIAMOND = 2;
    /** 六颗两排、往右上斜（Mega Drive 6 键：下排 A B C、上排 X Y Z）。 */
    static final int FACE_SIX = 3;
    /** 八颗两排、上排右移半格（街机 Vewlix 式）。 */
    static final int FACE_EIGHT = 4;
    /** 三颗一排、往右上斜（Mega Drive 3 键：A B C）。 */
    static final int FACE_THREE = 5;
    /** 四颗一道弧（Neo Geo：A B C D）。 */
    static final int FACE_ARC4 = 6;
    /** 六颗两排、上排右移半格（街机 6 键：街霸的轻中重拳 / 脚）。 */
    static final int FACE_ARCADE6 = 7;
    /** N64：A、B 和右上方四颗小一点的 C 键（菱形）。 */
    static final int FACE_N64 = 8;
    /** GameCube：大的 A 在中间，小的 B 在左下，X 在右、Y 在上。 */
    static final int FACE_GC = 9;
    /** 不放面键（自定义）。 */
    static final int FACE_NONE = 10;

    // ── 字样（同一组位置印什么字；中间键、肩键、摇杆按下的名字也跟着它） ──
    static final int LABELS_XBOX = 0, LABELS_PLAYSTATION = 1, LABELS_NINTENDO = 2, LABELS_SEGA = 3, LABELS_ARCADE = 4, LABELS_NUMBER = 5;

    /** PlayStation 的四个面键、N64 的 C 键画形状、不用字符（键帽上的字是这几个记号时 GamepadPainter 画形状）。 */
    static final String TRIANGLE = "△", CIRCLE = "○", CROSS = "×", SQUARE = "□";
    static final String C_UP = "▲", C_RIGHT = "▶", C_DOWN = "▼", C_LEFT = "◀";

    /** 自定义的 id（Prefs 里选的是它时用存着的那一套）。 */
    static final String CUSTOM_ID = "custom";

    /** 一个键：键帽上的字、发什么（Proto.GP_*）。 */
    static final class Key {
        final String label;
        final int output;

        Key(String label, int output) {
            this.label = label;
            this.output = output;
        }
    }

    final String id;
    /** 显示的名字（主机 / 手柄名，各语言都不翻译）；null = 要翻译，App 按 id 取 strings.xml（"Mega Drive 6 键"、"街机 8 键"、"自定义"……）。 */
    final String name;
    final int left, face, labels;
    final boolean rightStick;
    /** 肩键：L1 / R1（肩上那颗）、LT / RT（扳机，按下就是按满）各自有没有（N64、GameCube 两边不一样）。 */
    final boolean l1, r1, lt, rt;
    final boolean select, home, start, thumbs;
    /** 偏好竖屏（Game Boy）。不自动转屏，⋯ 菜单里有"旋转"。 */
    final boolean portrait;
    /** 十字键画成街机摇杆底座的样子（只是画法，判定一样是 8 方向）。 */
    final boolean arcadeStick;
    /** 预设自己的肩键名 {LT, L1, R1, RT}（null = 按字样；N64 的 Z、GameCube 的 L ZL Z R）；只在字样还是 {@link #nameLabels} 时用。 */
    final String[] shoulderNames;
    final int nameLabels;
    /** 从哪个预设来的（预设就是自己；自定义记着，键名、画法跟它）。 */
    final String base;

    private GamepadSpec(Builder b) {
        id = b.id;
        name = b.name;
        left = b.left;
        face = b.face;
        labels = b.labels;
        rightStick = b.rightStick;
        l1 = b.l1;
        r1 = b.r1;
        lt = b.lt;
        rt = b.rt;
        select = b.select;
        home = b.home;
        start = b.start;
        thumbs = b.thumbs;
        portrait = b.portrait;
        arcadeStick = b.arcadeStick;
        shoulderNames = b.shoulderNames;
        nameLabels = b.nameLabels;
        base = b.base != null ? b.base : b.id;
    }

    /** 拼一套参数：预设用链式写法，自定义从一份已有的复制出来再改（设置页）。 */
    static final class Builder {
        String id, name, base;
        int left = LEFT_DPAD, face = FACE_DIAMOND, labels = LABELS_XBOX;
        boolean rightStick, l1, r1, lt, rt, select, home, start, thumbs, portrait, arcadeStick;
        String[] shoulderNames;
        int nameLabels = -1;

        Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        Builder(GamepadSpec s) {
            id = s.id;
            name = s.name;
            base = s.base;
            left = s.left;
            face = s.face;
            labels = s.labels;
            rightStick = s.rightStick;
            l1 = s.l1;
            r1 = s.r1;
            lt = s.lt;
            rt = s.rt;
            select = s.select;
            home = s.home;
            start = s.start;
            thumbs = s.thumbs;
            portrait = s.portrait;
            arcadeStick = s.arcadeStick;
            shoulderNames = s.shoulderNames;
            nameLabels = s.nameLabels;
        }

        Builder left(int v) {
            left = v;
            return this;
        }

        Builder face(int v) {
            face = v;
            return this;
        }

        Builder labels(int v) {
            labels = v;
            return this;
        }

        Builder rightStick() {
            rightStick = true;
            return this;
        }

        /** L1 R1 一对。 */
        Builder bumpers() {
            l1 = r1 = true;
            return this;
        }

        /** LT RT 一对。 */
        Builder triggers() {
            lt = rt = true;
            return this;
        }

        Builder shoulders(boolean l1, boolean r1, boolean lt, boolean rt) {
            this.l1 = l1;
            this.r1 = r1;
            this.lt = lt;
            this.rt = rt;
            return this;
        }

        Builder middle(boolean select, boolean home, boolean start) {
            this.select = select;
            this.home = home;
            this.start = start;
            return this;
        }

        Builder thumbs() {
            thumbs = true;
            return this;
        }

        Builder portrait() {
            portrait = true;
            return this;
        }

        Builder arcade() {
            arcadeStick = true;
            return this;
        }

        /** 预设自己的肩键名 {LT, L1, R1, RT}（null 的按字样）：只在字样是现在这个时用。 */
        Builder names(String lt, String l1, String r1, String rt) {
            shoulderNames = new String[]{lt, l1, r1, rt};
            nameLabels = labels;
            return this;
        }

        GamepadSpec build() {
            if (left < 0 || left > LEFT_NONE) left = LEFT_DPAD;
            if (face < 0 || face > FACE_NONE) face = FACE_DIAMOND;
            if (labels < 0 || labels > LABELS_NUMBER) labels = LABELS_XBOX;
            return new GamepadSpec(this);
        }
    }

    /**
     * ⋯ 菜单里"预设：…"点一下按这个顺序轮换（存过自定义的话最后是自定义）；第一个是默认（电脑上 Sunshine 造的就是 Xbox 手柄，游戏提示也是 Xbox 的字）。
     * 设置页的预设网格也照这个顺序：现代手柄、任天堂老手柄、世嘉、街机。
     */
    static final GamepadSpec[] PRESETS = {
            new Builder("xbox", "Xbox").left(LEFT_STICK_TOP).face(FACE_DIAMOND).labels(LABELS_XBOX).rightStick()
                    .bumpers().triggers().middle(true, true, true).thumbs().build(),
            new Builder("playstation", "PlayStation").left(LEFT_DPAD_TOP).face(FACE_DIAMOND).labels(LABELS_PLAYSTATION).rightStick()
                    .bumpers().triggers().middle(true, true, true).thumbs().build(),
            new Builder("switch", "Switch Pro").left(LEFT_STICK_TOP).face(FACE_DIAMOND).labels(LABELS_NINTENDO).rightStick()
                    .bumpers().triggers().middle(true, true, true).thumbs().build(),
            // 初代经典手柄：L R 是模拟的、在外角（当扳机），ZL ZR 是数字的、在里边（当肩键）；摇杆不能按
            new Builder("wii", "Wii Classic").left(LEFT_DPAD_TOP).face(FACE_DIAMOND).labels(LABELS_NINTENDO).rightStick()
                    .bumpers().triggers().middle(true, true, true).names("L", "ZL", "ZR", "R").build(),
            // 左手摇杆在上、小十字键在下；右手 C 摇杆；L R 是扳机，Z 在 R 的里边（R1）；摇杆不能按。
            // 真机左边没有 Z 的镜像，自定义里打开肩键时左边那颗叫 ZL（不写的话按字样退回 L，和左扳机 L 重名；
            // 任天堂给 Switch 2 出的 GameCube 手柄在这个位置加的就叫 ZL，凭记忆、没核对）
            new Builder("gamecube", "GameCube").left(LEFT_STICK_TOP).face(FACE_GC).labels(LABELS_NINTENDO).rightStick()
                    .shoulders(false, true, true, true).middle(false, false, true).names("L", "ZL", "Z", "R").build(),
            // 十字键在左握把（靠外）、摇杆在中间握把（靠里）；Z 在背面，当左扳机放在左边外角
            new Builder("n64", "N64").left(LEFT_DPAD_TOP).face(FACE_N64).labels(LABELS_NINTENDO)
                    .shoulders(true, true, true, false).middle(false, false, true).names("Z", "L", "R", null).build(),
            new Builder("snes", "SNES / DS").left(LEFT_DPAD).face(FACE_DIAMOND).labels(LABELS_NINTENDO)
                    .bumpers().middle(true, false, true).build(),
            new Builder("nes", "FC / NES").left(LEFT_DPAD).face(FACE_ROW2).labels(LABELS_NINTENDO)
                    .middle(true, false, true).build(),
            new Builder("gameboy", "Game Boy").left(LEFT_DPAD).face(FACE_DIAG2).labels(LABELS_NINTENDO)
                    .middle(true, false, true).portrait().build(),
            new Builder("gba", "GBA").left(LEFT_DPAD).face(FACE_ROW2).labels(LABELS_NINTENDO)
                    .bumpers().middle(true, false, true).build(),
            new Builder("md3", null).left(LEFT_DPAD).face(FACE_THREE).labels(LABELS_SEGA)
                    .middle(false, false, true).build(),
            new Builder("md6", null).left(LEFT_DPAD).face(FACE_SIX).labels(LABELS_SEGA)
                    .middle(true, false, true).build(),
            new Builder("neogeo", "Neo Geo").left(LEFT_DPAD).face(FACE_ARC4).labels(LABELS_ARCADE)
                    .middle(true, false, true).arcade().build(),
            new Builder("arcade6", null).left(LEFT_DPAD).face(FACE_ARCADE6).labels(LABELS_ARCADE)
                    .middle(true, true, true).arcade().build(),
            new Builder("arcade8", null).left(LEFT_DPAD).face(FACE_EIGHT).labels(LABELS_ARCADE)
                    .middle(true, true, true).arcade().build(),
    };

    static final String DEFAULT_ID = "xbox";

    /** 预设里不认识的 id（以后删掉的预设、坏数据、自定义）按默认。 */
    static GamepadSpec byId(String id) {
        for (GamepadSpec s : PRESETS) if (s.id.equals(id)) return s;
        return PRESETS[0];
    }

    /** 是不是一个预设的 id。 */
    static boolean isPreset(String id) {
        for (GamepadSpec s : PRESETS) if (s.id.equals(id)) return true;
        return false;
    }

    /** 轮换的下一个（id 可以是 {@link #CUSTOM_ID}）：最后一个预设之后是自定义（存过才有），自定义之后回到第一个。 */
    static String next(String id, boolean hasCustom) {
        if (CUSTOM_ID.equals(id)) return PRESETS[0].id;
        for (int i = 0; i < PRESETS.length; i++) {
            if (!PRESETS[i].id.equals(id)) continue;
            if (i == PRESETS.length - 1) return hasCustom ? CUSTOM_ID : PRESETS[0].id;
            return PRESETS[i + 1].id;
        }
        return PRESETS[0].id;
    }

    boolean hasDpad() {
        return left == LEFT_DPAD || left == LEFT_STICK_TOP || left == LEFT_DPAD_TOP;
    }

    boolean hasLeftStick() {
        return left == LEFT_STICK || left == LEFT_STICK_TOP || left == LEFT_DPAD_TOP;
    }

    boolean isCustom() {
        return CUSTOM_ID.equals(id);
    }

    /** 比较用：预设是 id，自定义是存的那一行（两份自定义内容一样就算同一个）。 */
    String key() {
        return isCustom() ? CUSTOM_ID + ":" + encode() : id;
    }

    // ═══════════ 面键 ═══════════

    /**
     * 面键：按这个顺序报区域、画（位置固定，GamepadPlan.faceCenters 按 face 摆），字和发什么见 {@link #faceOutputs} / {@link #faceLabels}：
     * ROW2 / DIAG2 = {左 / 左下, 右 / 右上}；THREE = 从左到右；DIAMOND = {北, 西, 东, 南}；ARC4 = 从左到右；
     * SIX = {下排 3 颗, 上排 3 颗}；ARCADE6 = {上排 3 颗, 下排 3 颗}；EIGHT = {上排 4 颗, 下排 4 颗}（各从左到右）；
     * N64 = {A, B, C↑, C←, C→, C↓}；GC = {A, B, X, Y}；NONE = 没有。
     */
    Key[] faceKeys() {
        int[] out = faceOutputs(face);
        String[] lab = faceLabels(face, labels);
        Key[] k = new Key[out.length];
        for (int i = 0; i < k.length; i++) k[i] = new Key(lab[i], out[i]);
        return k;
    }

    /** 每种排法每颗发什么（按位置；Linux 两键规则：左 / 左下的是南、右 / 右上的是东；三键规则：西 南 东）。 */
    static int[] faceOutputs(int face) {
        final int S = Proto.GP_SOUTH, E = Proto.GP_EAST, W = Proto.GP_WEST, N = Proto.GP_NORTH;
        switch (face) {
            case FACE_ROW2:
            case FACE_DIAG2: return new int[]{S, E};
            case FACE_THREE: return new int[]{W, S, E};
            case FACE_ARC4: return new int[]{S, E, W, N};   // Neo Geo CD 手柄：A 南 B 东 C 西 D 北
            // 内核 NSO 表 / Batocera：A 西、B 南、C 东、X → L1、Y 北、Z → R1
            case FACE_SIX: return new int[]{W, S, E, Proto.GP_L1, N, Proto.GP_R1};
            // 现代 8 键摇杆：上排 西 北 R1 L1，下排 南 东 RT LT；6 键是它的前三列
            case FACE_ARCADE6: return new int[]{W, N, Proto.GP_R1, S, E, Proto.GP_RT};
            case FACE_EIGHT: return new int[]{W, N, Proto.GP_R1, Proto.GP_L1, S, E, Proto.GP_RT, Proto.GP_LT};
            case FACE_N64: return new int[]{S, W, Proto.GP_C_UP, Proto.GP_C_LEFT, Proto.GP_C_RIGHT, Proto.GP_C_DOWN};
            case FACE_GC: return new int[]{S, W, E, N};
            case FACE_NONE: return new int[0];
            case FACE_DIAMOND:
            default: return new int[]{N, W, E, S};
        }
    }

    /**
     * 每颗键帽上的字。N64、GameCube 的键帽只有自己那一套（不跟字样）；Neo Geo 的弧形四颗在世嘉 / 街机字样下是 A B C D；
     * 世嘉字样下两颗、三颗、六颗的照 Mega Drive（西 A、南 B、东 C、L1 X、北 Y、R1 Z），别的排法退回 Xbox 的字（Dreamcast 也是这样）；
     * 数字：菱形照 PC 手柄的习惯（1 左、2 下、3 右、4 上），别的从上到下、从左到右数。其余按输出码查字样（{@link #outputLabel}）。
     */
    static String[] faceLabels(int face, int style) {
        switch (face) {
            case FACE_N64: return new String[]{"A", "B", C_UP, C_LEFT, C_RIGHT, C_DOWN};
            case FACE_GC: return new String[]{"A", "B", "X", "Y"};
            default: break;
        }
        int[] out = faceOutputs(face);
        String[] lab = new String[out.length];
        if (style == LABELS_NUMBER) {
            int[] order;
            switch (face) {
                case FACE_DIAMOND: order = new int[]{4, 1, 3, 2}; break;
                case FACE_SIX: order = new int[]{4, 5, 6, 1, 2, 3}; break;   // 下排先报，但上排在上面：上排是 1 2 3
                default:
                    order = new int[out.length];
                    for (int i = 0; i < order.length; i++) order[i] = i + 1;
                    break;
            }
            for (int i = 0; i < lab.length; i++) lab[i] = String.valueOf(order[i]);
            return lab;
        }
        if (face == FACE_ARC4 && (style == LABELS_SEGA || style == LABELS_ARCADE)) return new String[]{"A", "B", "C", "D"};
        boolean sega = style == LABELS_SEGA
                && (face == FACE_ROW2 || face == FACE_DIAG2 || face == FACE_THREE || face == FACE_SIX);
        for (int i = 0; i < lab.length; i++) lab[i] = sega ? segaLabel(out[i]) : outputLabel(out[i], style);
        return lab;
    }

    /** 一个输出码在这种字样下印什么（世嘉、街机、数字退回 Xbox 的字）。 */
    static String outputLabel(int output, int style) {
        switch (style) {
            case LABELS_NINTENDO:
                switch (output) {
                    case Proto.GP_SOUTH: return "B";
                    case Proto.GP_EAST: return "A";
                    case Proto.GP_WEST: return "Y";
                    case Proto.GP_NORTH: return "X";
                    case Proto.GP_L1: return "L";
                    case Proto.GP_R1: return "R";
                    case Proto.GP_LT: return "ZL";
                    case Proto.GP_RT: return "ZR";
                    default: break;
                }
                break;
            case LABELS_PLAYSTATION:
                switch (output) {
                    case Proto.GP_SOUTH: return CROSS;
                    case Proto.GP_EAST: return CIRCLE;
                    case Proto.GP_WEST: return SQUARE;
                    case Proto.GP_NORTH: return TRIANGLE;
                    case Proto.GP_L1: return "L1";
                    case Proto.GP_R1: return "R1";
                    case Proto.GP_LT: return "L2";
                    case Proto.GP_RT: return "R2";
                    default: break;
                }
                break;
            default:
                break;
        }
        switch (output) {
            case Proto.GP_SOUTH: return "A";
            case Proto.GP_EAST: return "B";
            case Proto.GP_WEST: return "X";
            case Proto.GP_NORTH: return "Y";
            case Proto.GP_L1: return "LB";
            case Proto.GP_R1: return "RB";
            case Proto.GP_LT: return "LT";
            case Proto.GP_RT: return "RT";
            default: return "?";
        }
    }

    private static String segaLabel(int output) {
        switch (output) {
            case Proto.GP_WEST: return "A";
            case Proto.GP_SOUTH: return "B";
            case Proto.GP_EAST: return "C";
            case Proto.GP_L1: return "X";
            case Proto.GP_NORTH: return "Y";
            case Proto.GP_R1: return "Z";
            default: return outputLabel(output, LABELS_XBOX);
        }
    }

    // ═══════════ 肩键、中间、摇杆按下 ═══════════

    /** 左肩，从外往里（外角是扳机）；没有返回空。右肩是它的镜像（{@link #rightShoulders}，也是从外往里）。 */
    Key[] leftShoulders() {
        return shoulderKeys(lt, Proto.GP_LT, 0, l1, Proto.GP_L1, 1);
    }

    Key[] rightShoulders() {
        return shoulderKeys(rt, Proto.GP_RT, 3, r1, Proto.GP_R1, 2);
    }

    private Key[] shoulderKeys(boolean trigger, int triggerOut, int triggerName, boolean bumper, int bumperOut, int bumperName) {
        Key[] k = new Key[(trigger ? 1 : 0) + (bumper ? 1 : 0)];
        int i = 0;
        if (trigger) k[i++] = new Key(shoulderName(triggerName, triggerOut), triggerOut);
        if (bumper) k[i] = new Key(shoulderName(bumperName, bumperOut), bumperOut);
        return k;
    }

    /**
     * 这一套里某颗肩键 / 扳机叫什么（Proto.GP_L1 / GP_R1 / GP_LT / GP_RT；这一套没有这颗也照样给，设置页的开关写"这一对在这一套里叫什么"用：
     * Wii 经典手柄靠里那对叫 ZL ZR、外角那对叫 L R，和别家正好反着）。
     */
    String shoulderLabel(int output) {
        switch (output) {
            case Proto.GP_LT: return shoulderName(0, output);
            case Proto.GP_L1: return shoulderName(1, output);
            case Proto.GP_R1: return shoulderName(2, output);
            case Proto.GP_RT: return shoulderName(3, output);
            default: return outputLabel(output, labels);
        }
    }

    /** 肩键名：预设自己写了、字样也没换就照它（N64 的 Z），不然按字样。which = {LT, L1, R1, RT} 的下标。 */
    private String shoulderName(int which, int output) {
        if (shoulderNames != null && labels == nameLabels && shoulderNames[which] != null) return shoulderNames[which];
        return outputLabel(output, labels);
    }

    /** 中间那排，从左到右：Select、Home、Start（有的才有）。任天堂字样有 Home 的是 − ⌂ +（Switch、Wii），没有的是 SELECT START。 */
    Key[] middle() {
        String sel, home, start;
        switch (labels) {
            case LABELS_XBOX: sel = "⧉"; home = "⌂"; start = "≡"; break;
            case LABELS_PLAYSTATION: sel = "Create"; home = "PS"; start = "Options"; break;
            case LABELS_NINTENDO:
                sel = this.home ? "−" : "SELECT";
                home = "⌂";
                start = this.home ? "+" : "START";
                break;
            case LABELS_SEGA: sel = "MODE"; home = "⌂"; start = "START"; break;
            default: sel = "SELECT"; home = "⌂"; start = "START"; break;
        }
        int n = (select ? 1 : 0) + (this.home ? 1 : 0) + (this.start ? 1 : 0);
        Key[] k = new Key[n];
        int i = 0;
        if (select) k[i++] = new Key(sel, Proto.GP_SELECT);
        if (this.home) k[i++] = new Key(home, Proto.GP_HOME);
        if (this.start) k[i] = new Key(start, Proto.GP_START);
        return k;
    }

    /** 左摇杆按下（有左摇杆、开着 thumbs 才有）。 */
    Key leftThumb() {
        if (!thumbs || !hasLeftStick()) return null;
        return new Key(numberedThumbs() ? "L3" : "LS", Proto.GP_L3);
    }

    Key rightThumb() {
        if (!thumbs || !rightStick) return null;
        return new Key(numberedThumbs() ? "R3" : "RS", Proto.GP_R3);
    }

    private boolean numberedThumbs() {
        return labels == LABELS_PLAYSTATION || labels == LABELS_NUMBER;
    }

    // ═══════════ 自定义 ═══════════

    /** 复制成自定义（设置页"自定义"从这一份开始改；键名、画法跟着原来的预设）。 */
    GamepadSpec asCustom() {
        Builder b = new Builder(this);
        b.id = CUSTOM_ID;
        b.name = null;
        b.base = base;
        return b.build();
    }

    static final String[] LEFT_NAMES = {"dpad", "stick", "stick_top", "dpad_top", "none"};
    static final String[] FACE_NAMES = {"row2", "diag2", "diamond", "six", "eight", "three", "arc4", "arcade6", "n64", "gc", "none"};
    static final String[] LABEL_NAMES = {"xbox", "playstation", "nintendo", "sega", "arcade", "number"};

    /**
     * 存成一行文字：`base=xbox;left=stick_top;face=diamond;labels=xbox;rs=1;l1=1;…`。取值写名字不写编号（以后加了新的取值不会把旧的认错）；
     * 偏好方向、街机画法、预设自己的肩键名不存，跟着 base 那个预设走。
     */
    String encode() {
        StringBuilder s = new StringBuilder();
        s.append("base=").append(base);
        s.append(";left=").append(LEFT_NAMES[left]);
        s.append(";face=").append(FACE_NAMES[face]);
        s.append(";labels=").append(LABEL_NAMES[labels]);
        flag(s, "rs", rightStick);
        flag(s, "l1", l1);
        flag(s, "r1", r1);
        flag(s, "lt", lt);
        flag(s, "rt", rt);
        flag(s, "sel", select);
        flag(s, "home", home);
        flag(s, "start", start);
        flag(s, "thumbs", thumbs);
        return s.toString();
    }

    private static void flag(StringBuilder s, String key, boolean on) {
        s.append(';').append(key).append('=').append(on ? '1' : '0');
    }

    /**
     * 读回 {@link #encode} 存的那一行；null / 空 = 没存过（返回 null）。先照 base 那个预设（认不出按默认），再一项项盖上存着的：
     * 认不出的项、认不出的取值都跳过（照预设的），坏数据也不会闪退。
     */
    static GamepadSpec decode(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        GamepadSpec base = byId(m.get("base"));
        Builder b = new Builder(base.asCustom());
        b.left = pick(LEFT_NAMES, m.get("left"), b.left);
        b.face = pick(FACE_NAMES, m.get("face"), b.face);
        b.labels = pick(LABEL_NAMES, m.get("labels"), b.labels);
        b.rightStick = bool(m.get("rs"), b.rightStick);
        b.l1 = bool(m.get("l1"), b.l1);
        b.r1 = bool(m.get("r1"), b.r1);
        b.lt = bool(m.get("lt"), b.lt);
        b.rt = bool(m.get("rt"), b.rt);
        b.select = bool(m.get("sel"), b.select);
        b.home = bool(m.get("home"), b.home);
        b.start = bool(m.get("start"), b.start);
        b.thumbs = bool(m.get("thumbs"), b.thumbs);
        return b.build();
    }

    private static int pick(String[] names, String v, int fallback) {
        if (v == null) return fallback;
        for (int i = 0; i < names.length; i++) if (names[i].equals(v)) return i;
        return fallback;
    }

    private static boolean bool(String v, boolean fallback) {
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        return fallback;
    }
}
