package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 键盘界面怎么摆，纯 Java（离线测，KeyboardPlanTest）：分不分裂、键盘占哪块、小触控板放哪儿（或者不放）；
 * 还有 ⋯ 菜单的几何（主界面和键盘页共用）：⋯ 放在触控板顶上正中（{@link #menuTop}；主界面放不下也照样放，{@link #padMenuTop}）、
 * 菜单弹在哪（{@link #menuPosition}）、项多窗口矮时怎么排（{@link #menuGrid}）、
 * 调整模式那条"完成 / 恢复默认"栏放上面还是下面（{@link #adjustBarTop}）、栏里按钮多宽（{@link #barButtonWidth}）。
 * KeyboardScreen 每次量尺寸时调 {@link #plan}，画和收触摸都照它的结果。键盘区就是键盘界面整页（2026-10-30 起没有工具栏那一行）。
 *
 * 布局（⋯ 菜单里的"布局"，自动 → 分裂 → 合并 → 自动，记在 Prefs，不分横竖屏）：
 * - 自动：键盘区宽 ≥ 2 × 高（KeyRows.shouldSplit），而且分裂了键还够宽（{@link Sizes#minKey}）才分裂。
 * - 分裂：不管宽窄都分，只要两半往中间伸满（中间不放触控板）时键还够宽；再窄就按合并摆（手机竖屏扣掉缝一般还够）。
 * - 合并：不分裂。
 *
 * 触控板（⋯ 菜单里的"触控板"，显示 / 隐藏，记在 Prefs）：显示时放在键盘旁边空得最多的那块矩形里（上 / 右 / 左 / 下，
 * 分裂时还有两半中间），短边不到 {@link Sizes#minPad} 就不放（剩一条摁不了，留着没用）；没放成的话分裂的两半往中间伸。
 * 隐藏时空出来的地方空着（合并）或者给两半（分裂）。
 *
 * 分裂时键盘占满键盘区的宽；高：宽窗口（宽 ≥ 2 × 高）占满，窄窗口（只有强制分裂才会）照合并时键盘的上下沿（用户调过的高度），
 * 不然手机竖屏一分裂键就有一百多 dp 高。
 * 合并时键盘照存下的大小位置；没存过时，宽窗口里触控板设成显示、右边那三分之一又放得下它，默认靠左、占三分之二宽、满高
 * （触控板自然落到右边那三分之一），其它照旧（竖屏占下面七成，横屏占满）——所以没调过大小时开关触控板，键盘会在三分之二宽和满宽之间变。
 * 存下的大小位置横竖屏 × 指点三种状态各一份（Prefs.savedKeyboardRect），这里不改它。
 *
 * 分裂时也能调大小（2026-10-30，用户："分裂状态应该也可以调整大小啊，两边对称调啊"），两半永远左右对称，
 * 存的是左半的"格子" {左, 上, 右, 下}（键盘区的 0–1，横竖屏 × 指点三种状态各一份，Prefs.savedSplitRect；右半是它的镜像）：
 * 格子的右边量到两半中间那条线、连着半条缝（左半的键实际到 右 × 宽 − 缝 / 2），右 = 0.5 就是两半只隔一条缝（伸满），
 * 跟窗口多宽无关。没存过时按上面的默认规则摆，再折算成格子（{@link Plan#splitCell}），调整模式从它开始拖。
 * 存过的照存的摆（{@link #fitSplit} 先按当前窗口守住约束：一半够宽、不重叠、不出界、够高），不再"往中间伸"：
 * 触控板放得进中间（短边够 {@link Sizes#minPad}）就放中间，不然照样去上方 / 下方或者不放，两半留在用户放的位置。
 * 分不分裂（{@link #shouldSplit}）不看存的格子。拖法见 {@link #dragSplit}，吸附见 KeyboardSnap.applySplit。
 *
 * 指点（⋯ 菜单里"指点"，触控板 → 小红点 → 关，记在 Prefs；2026-10-31 用户：原来的"触控板：开 / 关"换成三选一）：
 * 小红点时照真的 ThinkPad 键盘摆（2026-11-01 用户："指点杆直接放键盘中间，然后在键盘下边有三个按键"），不放触控板，⋯ 回功能行上，
 * 而且固定合并（2026-11-01 用户："小红点状态把分裂布局取消掉，默认就是合并的布局"；"布局"选的值不管、也不改）：
 * - 三个鼠标键（{@link Plan#keys}）竖着跨底行（空格那一排）和快捷行（2026-11-01 用户："想用它占用空格行，还有包括空格下边那一行……
 *   高度跟这两个高度等高"；快捷行没有键、或者分到两边后快捷键太窄（{@link #shortcutsSpread}）时只占空格那一排）：这两行在中间让出同一段空位（{@link Plan#hole}，{@link #stickHole}），
 *   三个键铺满空位（{@link #holeKeys}：每个键帽四边缩进同键盘的键，中键窄、左右键宽，中键对着红点）。别的行照原来的高度，不再多一排。
 * - 红点在键盘里：字母层 g、h、v、b 交界那一点（KeyRows.stickPoint），挨着的键（字母层是 g、h、v、b，符号层、Fn 层是那一层压着的键）
 *   被一个和红点同心的圆切掉一块（{@link #STICK_KEYS}，切口和红帽之间留 {@link Sizes#stickGap}，照真 ThinkPad 的 G、H、B），
 *   按得到的范围是圆（报上去的正方形的内切圆，TouchRouter.Region.contains），多大见 {@link #stickRadius}；
 *   嵌不下（键太小）就放在键盘上面（或下面、旁边）、对着那一点（{@link #STICK_OUTSIDE}），都没地方就不放。
 * 三键条（设置里的开关，主界面和键盘页的触控板都管）：从触控板的上沿或下沿切出一条（{@link #cutBar}），切完触控板短边不够就不切；
 * 放上沿时 ⋯ 挪到三键条正下方。
 */
final class KeyboardPlan {
    private KeyboardPlan() {
    }

    static final int MODE_AUTO = 0;
    static final int MODE_SPLIT = 1;
    static final int MODE_MERGE = 2;

    static final int PAD_NONE = 0;
    /** 分裂时两半中间。 */
    static final int PAD_MIDDLE = 1;
    static final int PAD_TOP = 2;
    static final int PAD_RIGHT = 3;
    static final int PAD_LEFT = 4;
    static final int PAD_BOTTOM = 5;

    /** 键盘页的指点设备：小触控板 / 小红点 / 都不要。 */
    static final int POINTER_PAD = 0;
    static final int POINTER_STICK = 1;
    static final int POINTER_NONE = 2;

    /** 触控板旁边的三键条：不要 / 下沿 / 上沿。 */
    static final int KEYS_OFF = 0;
    static final int KEYS_BOTTOM = 1;
    static final int KEYS_TOP = 2;

    /** 小红点放在哪：没放 / 嵌在键里（挨着的键被圆弧切掉一块）/ 键盘外面（嵌不下时，键盘上面、下面或旁边）。 */
    static final int STICK_NONE = 0;
    static final int STICK_KEYS = 1;
    static final int STICK_OUTSIDE = 2;

    /**
     * 小红点嵌在键里时每个键最多被切掉多少面积（切口圆和键帽矩形相交的面积 / 键帽面积，{@link #cutShare}）：
     * 字母层（天天打字的那层，含功能行、底行）按 {@link #STICK_LETTER_SHARE} 定大小，
     * 所有层（符号层、Fn 层那一点上是别的键，不一定四个键交在一起）都不超过 {@link #STICK_MAX_SHARE}（硬上限）。
     * 以前按正方形算（2026-11-01 改成照真 ThinkPad 的圆切口）：同样的面积，圆能大约 13%。
     */
    static final float STICK_LETTER_SHARE = 0.16f;
    static final float STICK_MAX_SHARE = 0.25f;
    /**
     * 中键占三个键总宽的比例：真 ThinkPad 的左 : 中 : 右约 2.2 : 1 : 2.2（2026-11-01 照联想用户指南插图量的，各代 2.1–2.4 : 1），
     * 中键 = 总宽 / 5.4。以前凭印象按 3 : 2 : 3（四分之一）。
     */
    static final float MIDDLE_SHARE = 1 / 5.4f;

    /** 宽窗口里合并、没调过大小时，键盘占的宽度比例（靠左）。 */
    static final float WIDE_MERGED_WIDTH = 2f / 3;
    /** 调大小时键盘（分裂时两半）至少占键盘区多高（合并、分裂、实体键盘一样）。 */
    static final float MIN_HEIGHT = 0.25f;
    /** 调大小时合并的键盘（和实体键盘）至少占键盘区多宽；实体键盘另外还要一格不窄于 minKey（{@link #fixedMinWidth}）。 */
    static final float MIN_WIDTH = 0.4f;
    /** 分裂格子的右边：两半只隔一条缝。 */
    static final float SPLIT_MEET = 0.5f;

    /** 点一下菜单里的"布局"：自动 → 分裂 → 合并 → 自动。认不出的值（以后删了某种）当自动。 */
    static int nextMode(int mode) {
        switch (validMode(mode)) {
            case MODE_AUTO: return MODE_SPLIT;
            case MODE_SPLIT: return MODE_MERGE;
            default: return MODE_AUTO;
        }
    }

    static int validMode(int mode) {
        return mode == MODE_SPLIT || mode == MODE_MERGE ? mode : MODE_AUTO;
    }

    /** 点一下菜单里的"指点"：触控板 → 小红点 → 关 → 触控板。认不出的值当触控板。 */
    static int nextPointer(int pointer) {
        return nextPointer(pointer, true);
    }

    /**
     * 同上，stickOk = 这时能不能用小红点（实体键盘显示着 TKL / 全尺寸、Mac 模式时不能，FixedLayout.stickAllowed）：不能时跳过它（触控板 ↔ 关）。
     * pointer 是显示着的那个（存着小红点、这一级不给时照触控板算，{@link #fixedPointer}）。
     */
    static int nextPointer(int pointer, boolean stickOk) {
        switch (validPointer(pointer)) {
            case POINTER_PAD: return stickOk ? POINTER_STICK : POINTER_NONE;
            case POINTER_STICK: return POINTER_NONE;
            default: return POINTER_PAD;
        }
    }

    /**
     * 实体键盘这一块实际用的指点：存的是小红点、这一块不给小红点（FixedLayout.stickAllowed：TKL、全尺寸、Mac 模式）时照触控板（协调者 2026-11-01 定：
     * 关掉的话 ⋯ 没地方放，也不像原来想要的样子）；存的值不动，换回给小红点的那一级 / 模式时小红点回来。layout 是 null 时照存的。
     */
    static int fixedPointer(int pointer, FixedLayout layout) {
        pointer = validPointer(pointer);
        return pointer == POINTER_STICK && layout != null && !layout.stickAllowed() ? POINTER_PAD : pointer;
    }

    static int validPointer(int pointer) {
        return pointer == POINTER_STICK || pointer == POINTER_NONE ? pointer : POINTER_PAD;
    }

    /**
     * 没存过大小位置时合并键盘的默认位置 {左, 上, 右, 下}（键盘区的 0–1）。wide 时的三分之二宽只在触控板显示、
     * 右边那三分之一放得下它时才用（{@link #plan} 判断），不然 plan 按 wide = false 取。
     */
    static float[] defaultRect(boolean landscape, boolean wide) {
        if (wide) return new float[]{0, 0, WIDE_MERGED_WIDTH, 1};
        return landscape ? new float[]{0, 0, 1, 1} : new float[]{0, 0.3f, 1, 1};
    }

    /** 尺寸门槛（像素）。 */
    static final class Sizes {
        /** 键盘和触控板之间、分裂的两半（中间不放触控板时）之间的缝。 */
        final int gap;
        /** 触控板短边至少这么长才放。 */
        final int minPad;
        /** 分裂时一份（字母键连缝，KeyRows.splitUnit）至少这么宽才分裂。 */
        final int minKey;
        /** 小红点按得到的范围（圆）最大直径（64dp，放在键盘外面时就这么大）；0 = 没给（放不了小红点，也切不了三键条）。 */
        final int stick;
        /** 嵌在键里时红点的范围至少多大（直径 32dp，约 5 毫米），再小就不嵌（{@link #stickRadius}）。 */
        final int minStick;
        /**
         * 嵌在键里时红帽和键上切口之间的缝（3dp，照真 ThinkPad 约 0.5 毫米的缝放大一点、手机上看得出来）：按得到的范围（圆）的边在这条缝的中线，
         * 红帽半径 = 范围半径 − 缝 / 2，切口半径 = 范围半径 + 缝 / 2（{@link #cutRadius}）。
         */
        final int stickGap;
        /**
         * 键上的字要离切口多远（10sp，跟着系统字体大小）：把字看成以键帽中心为圆心、这么大半径的一块（18sp 的单个字连上下出头约 ±9sp），
         * 切口碰到它时字往远离切口的方向竖着挪（{@link #labelShift}），挪出键帽还躲不开就算嵌不下。
         */
        final int labelRoom;
        /** 三个鼠标键：每个至少多宽（44dp）、多高（48dp）、键与键之间（和三键条与触控板之间）的缝（4dp）。 */
        final int keyW, keyH, keyGap;
        /** 中键至少多宽（56dp；整排窄得三个一样宽都不到这么宽时三个一样宽）。 */
        final int minMiddle;
        /** 键盘面板里每个键四边各缩进多少（KeyboardPanel 的 gap，2dp）：算键的实际矩形、让三个鼠标键那排和键对齐。 */
        final int capGap;
        /**
         * 三键条、键盘外面的红点离页面左、上、右、下边至少多远（20dp：拿手机的手指常压在边上，同 ⋯、调整栏）。
         * 页面因屏幕边缘留白往里收了的那几条边少留这么多（{@link #withEdges}、EdgeMargin.edges：照旧按屏幕边算）。
         */
        final int edgeL, edgeT, edgeR, edgeB;

        Sizes(int gap, int minPad, int minKey) {
            this(gap, minPad, minKey, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Sizes(int gap, int minPad, int minKey, int stick, int minStick, int stickGap, int labelRoom, int keyW, int keyH, int keyGap,
              int minMiddle, int capGap, int edge) {
            this(gap, minPad, minKey, stick, minStick, stickGap, labelRoom, keyW, keyH, keyGap, minMiddle, capGap,
                    new int[]{edge, edge, edge, edge});
        }

        private Sizes(int gap, int minPad, int minKey, int stick, int minStick, int stickGap, int labelRoom, int keyW, int keyH,
                      int keyGap, int minMiddle, int capGap, int[] edges) {
            this.gap = gap;
            this.minPad = minPad;
            this.minKey = minKey;
            this.stick = stick;
            this.minStick = minStick;
            this.stickGap = stickGap;
            this.labelRoom = labelRoom;
            this.keyW = keyW;
            this.keyH = keyH;
            this.keyGap = keyGap;
            this.minMiddle = minMiddle;
            this.capGap = capGap;
            this.edgeL = edges[0];
            this.edgeT = edges[1];
            this.edgeR = edges[2];
            this.edgeB = edges[3];
        }

        /** 同一套尺寸，离页面四条边至少多远换成 edges {左, 上, 右, 下}（各边可以不一样）。 */
        Sizes withEdges(int[] edges) {
            return new Sizes(gap, minPad, minKey, stick, minStick, stickGap, labelRoom, keyW, keyH, keyGap, minMiddle, capGap, edges);
        }

        /** 给了红点和鼠标键的尺寸（不然小红点当"关"、三键条不切）。 */
        boolean stickReady() {
            return stick > 0 && minStick > 0 && minStick <= stick && keyW > 0 && keyH > 0;
        }

        /** 三个键排一行至少多宽（三键条）。 */
        int keysWidth() {
            return 3 * keyW + 2 * keyGap;
        }

        /** 底行、快捷行让出的空位至少多宽才放得下三个各宽 w 的鼠标键：每个键帽四边缩进 capGap，相邻两个之间隔 2 × capGap（同键盘的键）。 */
        int holeWidth(int w) {
            return 3 * w + 6 * capGap;
        }
    }

    /** 摆法。坐标都是键盘区里的像素 {左, 上, 右, 下}。 */
    static final class Plan {
        final boolean split;
        /**
         * 分裂时两半中间那截（KeyRows.leftEnd 的 middle，按键盘面板 {@link #keyboard} 的宽算）：KeyRows.MIDDLE_PAD = 默认的三分之一布局
         * （触控板在中间），否则两半中间隔这么宽（存过格子时触控板在中间也是实际宽度）。
         */
        final int middle;
        /** 分裂时两半只隔一条缝（middle == 缝）：两块键盘范围各伸到缝的中线（缝分给最近的键）；不然范围只到各自那一半的边。 */
        final boolean seam;
        /**
         * 键盘面板：合并时是整块键盘，分裂时从左半的左边到右半的右边（中间那截面板不画东西）。选小红点时三个鼠标键也在它里面
         * （底行、快捷行让出的空位，{@link #hole}）。调整模式画的框、拖的把手、调整栏让开的都是它；{@link #rect} / {@link #splitCell} 存的也是它。
         */
        final int[] keyboard;
        /** 分裂时左半、右半各自的范围；合并时是 null。 */
        final int[] leftHalf, rightHalf;
        final int padSide;
        /** 触控板；不放时是 null。 */
        final int[] pad;
        /** 合并时用的键盘位置（存下的，或者默认的），0–1；调整模式从它开始拖。 */
        final float[] rect;
        /** 分裂时用的左半格子（存下的守住约束后，或者默认的折算），0–1；调整模式从它开始拖。合并时是 null。 */
        final float[] splitCell;
        /** 小红点放在哪（STICK_*）、按得到的范围（正方形，按它的内切圆算）；没放时 STICK_NONE / null。 */
        final int stickAt;
        final int[] stick;
        /** 嵌在键里时键上切口的半径（像素，{@link #cutRadius}；圆心是 stick 的中心）；不嵌在键里是 0。 */
        final float cut;
        /**
         * 三个鼠标键：选小红点时是底行、快捷行让出的空位（从空位左边到右边、从底行上沿到键盘下沿——快捷行不让位（{@link #spread} 为假）时
         * 到底行的下沿，三个键铺满它，各自的键帽四边再缩进），触控板时是上沿 / 下沿切出来的三键条（三个键帽之间隔 keyGap）；没有时是 null。
         */
        final int[] keys;
        /**
         * 三个鼠标键里中键的 {左, 右}（页面 x）。三键条：中键的键帽，左键从 keys 左边到它左边隔一条缝，右键从它右边隔一条缝到 keys 右边；
         * 空位（hole 不是 null）：中键铺的那一格（键帽再缩进），左键那一格从 keys 左边到它，右键那一格从它到 keys 右边。没有 keys 时是 null。
         */
        final int[] middleKey;
        /** 选小红点时底行、快捷行让出的空位 {左, 右}（键盘面板里的 x，交给 KeyRows.place）；不让时是 null。 */
        final int[] hole;
        /**
         * 快捷行也让出空位（{@link #shortcutsSpread}）：三个鼠标键竖着跨底行和快捷行。快捷行没有键、或者分到空位两边后快捷键太窄时为假，
         * 快捷行照旧铺满整行，三个鼠标键只占空格那一排（{@link #keys} 的下沿是底行的下沿，KeyRows.holeBottom）。没让空位时是假。
         */
        final boolean spread;
        /** ⋯ 放在哪块的顶上正中（交给 {@link #menuTop}）、离那块顶边多远；null = 没地方，⋯ 是功能行上的键。 */
        final int[] menuArea;
        final int menuMargin;

        Plan(boolean split, int middle, boolean seam, int[] keyboard, int padSide, int[] pad, float[] rect,
             float[] splitCell, int stickAt, int[] stick, float cut, int[] keys, int[] middleKey, int[] hole, boolean spread,
             int[] menuArea, int menuMargin) {
            this.split = split;
            this.middle = middle;
            this.seam = seam;
            this.keyboard = keyboard;
            this.padSide = padSide;
            this.pad = pad;
            this.rect = rect;
            this.splitCell = splitCell;
            this.stickAt = stickAt;
            this.stick = stick;
            this.cut = cut;
            this.keys = keys;
            this.middleKey = middleKey;
            this.hole = hole;
            this.spread = spread;
            this.menuArea = menuArea;
            this.menuMargin = menuMargin;
            if (split) {
                int half = KeyRows.leftEnd(keyboard[2] - keyboard[0], middle);
                leftHalf = new int[]{keyboard[0], keyboard[1], keyboard[0] + half, keyboard[3]};
                rightHalf = new int[]{keyboard[2] - half, keyboard[1], keyboard[2], keyboard[3]};
            } else {
                leftHalf = rightHalf = null;
            }
        }
    }

    /** 同下，没存过分裂格子。 */
    static Plan plan(int mode, boolean padOn, int w, int h, float[] saved, boolean landscape, Sizes s) {
        return plan(mode, padOn, w, h, saved, null, landscape, s);
    }

    /** 指点只有触控板 / 关、没有三键条（小红点之前的接口，测试沿用）。 */
    static Plan plan(int mode, boolean padOn, int w, int h, float[] saved, float[] savedSplit, boolean landscape, Sizes s) {
        return plan(mode, padOn ? POINTER_PAD : POINTER_NONE, KEYS_OFF, w, h, saved, savedSplit, landscape, s);
    }

    /** 同下，用默认的键（功能行带 ⋯、底行不带 🌐、没有快捷行）算小红点嵌在键里能多大（测试沿用）。 */
    static Plan plan(int mode, int pointer, int keys, int w, int h, float[] saved, float[] savedSplit, boolean landscape, Sizes s) {
        return plan(mode, pointer, keys, w, h, saved, savedSplit, landscape, s, null);
    }

    /**
     * mode = MODE_*；pointer = POINTER_*（指点：触控板 / 小红点 / 关）；keys = KEYS_*（触控板的三键条，只在触控板时有用）；
     * w × h = 键盘区；saved = 存下的合并键盘位置（0–1，没存过是 null）；savedSplit = 存下的分裂左半格子（0–1，没存过是 null）；
     * landscape = 横屏（决定没存过时的默认位置）；layers = 键盘三层各自的全部行（KeyRows.layers，KeyboardPanel.layers 给），
     * 小红点时按它算底行、快捷行让多宽的空位、红点切掉键多少（null = 默认的键）。
     * 小红点固定合并（不管 mode；mode 本身不改，换回触控板 / 关时照它）。
     */
    static Plan plan(int mode, int pointer, int keys, int w, int h, float[] saved, float[] savedSplit, boolean landscape, Sizes s,
                     KeyRows.Row[][] layers) {
        pointer = validPointer(pointer);
        // 2026-11-01 用户："小红点状态把分裂布局取消掉，默认就是合并的布局"
        if (pointer == POINTER_STICK) mode = MODE_MERGE;
        if (pointer == POINTER_STICK && !s.stickReady()) pointer = POINTER_NONE;
        boolean padOn = pointer == POINTER_PAD;
        boolean stickOn = pointer == POINTER_STICK;
        if (layers == null) layers = KeyRows.layers(true, KeyRows.bottomRows(false, new KeyRows.Key[0]));
        boolean wide = KeyRows.shouldSplit(w, h);
        float[] r;
        if (saved != null) {
            r = saved.clone();
        } else {
            r = defaultRect(landscape, wide);
            // 三分之二宽只是给触控板腾地方：触控板关着（小红点在键盘里，也不用让地方）、或者右边那三分之一放不下它（短边不够），
            // 就照不宽的窗口占满，不留一块空着
            if (wide) {
                int[] right = {Math.round(WIDE_MERGED_WIDTH * w) + s.gap, 0, w, h};
                if (!padOn || !usable(right, s.minPad)) r = defaultRect(landscape, false);
            }
        }
        int[] merged = {Math.round(r[0] * w), Math.round(r[1] * h), Math.round(r[2] * w), Math.round(r[3] * h)};
        Draft d;
        if (!shouldSplit(validMode(mode), pointer, w, h, s)) {
            d = new Draft(false, KeyRows.MIDDLE_PAD, merged, null);
            if (padOn) {
                int[][] c = {
                        {0, 0, w, merged[1] - s.gap},              // 上
                        {merged[2] + s.gap, 0, w, h},              // 右
                        {0, 0, merged[0] - s.gap, h},              // 左
                        {0, merged[3] + s.gap, w, h},              // 下
                };
                int best = largest(c, s.minPad);
                if (best >= 0) d.pad(new int[]{PAD_TOP, PAD_RIGHT, PAD_LEFT, PAD_BOTTOM}[best], c[best]);
            }
        } else if (savedSplit == null) {
            d = defaultSplit(pointer, w, h, merged, s);
        } else {
            d = savedSplit(pointer, savedSplit, w, h, s);
        }
        return finish(d, padOn ? keys : KEYS_OFF, stickOn, r, w, h, s, layers);
    }

    /** 摆到一半的结果（键盘、触控板），{@link #finish} 再让空位放三个鼠标键 / 切三键条、放红点、定 ⋯ 放哪。 */
    private static final class Draft {
        final boolean split;
        int middle;
        boolean seam;
        final int[] keyboard;
        final float[] cell;
        int padSide = PAD_NONE;
        int[] pad;

        Draft(boolean split, int middle, int[] keyboard, float[] cell) {
            this.split = split;
            this.middle = middle;
            this.keyboard = keyboard;
            this.cell = cell;
        }

        void pad(int side, int[] r) {
            padSide = side;
            pad = r;
        }
    }

    /**
     * 没存过分裂格子时的分裂摆法（原来的规则）：宽窗口占满，窄窗口照合并时的上下沿（merged）；触控板放得进中间（三分之一布局）、
     * 上方、下方里面积最大的那块，没放进中间两半就往中间伸、只隔一条缝。顺带折算成格子（调整模式从它开始拖）。
     * 小红点不会走到这里（固定合并，2026-11-01 起；以前中间留一列给红点）。
     */
    private static Draft defaultSplit(int pointer, int w, int h, int[] merged, Sizes s) {
        int[] k = KeyRows.shouldSplit(w, h) ? new int[]{0, 0, w, h} : new int[]{0, merged[1], w, merged[3]};
        int[][] bands = {{0, 0, w, k[1] - s.gap}, {0, k[3] + s.gap, w, h}};
        int side = PAD_NONE;
        int[] pad = null;
        if (pointer == POINTER_PAD) {
            // 中间那块只在三分之一布局下键还够宽时才算候选
            int[] mid = KeyRows.splitUnit(w, KeyRows.MIDDLE_PAD) >= s.minKey
                    ? new int[]{KeyRows.leftEnd(w) + s.gap, k[1], KeyRows.rightStart(w) - s.gap, k[3]}
                    : new int[]{0, 0, 0, 0};
            int[][] c = {mid, bands[0], bands[1]};
            int best = largest(c, s.minPad);
            if (best >= 0) {
                side = new int[]{PAD_MIDDLE, PAD_TOP, PAD_BOTTOM}[best];
                pad = c[best];
            }
        }
        boolean third = side == PAD_MIDDLE;
        float right = third && w > 0 ? (KeyRows.leftEnd(w) + s.gap / 2f) / w : SPLIT_MEET;
        Draft d = new Draft(true, third ? KeyRows.MIDDLE_PAD : s.gap, k, cellOf(k, w, h, right));
        d.seam = !third;
        d.pad(side, pad);
        return d;
    }

    /** 默认摆法折算成的左半格子：上下照键盘，右边 right（0–1）。 */
    private static float[] cellOf(int[] k, int w, int h, float right) {
        return new float[]{0, h > 0 ? (float) k[1] / h : 0, right, h > 0 ? (float) k[3] / h : 1};
    }

    /** 存过格子：照存的摆（先守住约束），触控板放得进中间就放中间，不然上方 / 下方 / 不放，两半不动。 */
    private static Draft savedSplit(int pointer, float[] savedSplit, int w, int h, Sizes s) {
        float[] cell = fitSplit(savedSplit, w, s);
        int left = Math.round(cell[0] * w);
        int[] k = {left, Math.round(cell[1] * h), w - left, Math.round(cell[3] * h)};
        int pw = k[2] - k[0];
        int middle = s.gap;
        if (cell[2] < SPLIT_MEET) {
            int half = Math.round(cell[2] * w - s.gap / 2f) - left;
            middle = Math.max(s.gap, pw - 2 * half);
        }
        Draft d = new Draft(true, middle, k, cell);
        d.seam = middle == s.gap;
        if (pointer == POINTER_PAD) {
            int half = KeyRows.leftEnd(pw, middle);
            int[] mid = middle > s.gap ? new int[]{k[0] + half + s.gap, k[1], k[2] - half - s.gap, k[3]} : new int[]{0, 0, 0, 0};
            int[][] c = {mid, {0, 0, w, k[1] - s.gap}, {0, k[3] + s.gap, w, h}};
            int best = largest(c, s.minPad);
            if (best >= 0) d.pad(new int[]{PAD_MIDDLE, PAD_TOP, PAD_BOTTOM}[best], c[best]);
        }
        return d;
    }

    /**
     * 收尾：触控板开着三键条就从它上沿 / 下沿切一条（{@link #cutBar}，切不了就不切）；定 ⋯ 放哪——触控板顶上正中（三键条在上沿时
     * 在三键条正下方、隔一条缝），没有触控板（小红点、关）回到功能行上。
     * 小红点（一定是合并）：底行、快捷行让出空位（{@link #stickHole}，让不出就没有三个鼠标键；快捷行分开后太窄就只有底行让，
     * {@link #shortcutsSpread}），三个键铺满它、中键对着红点那一点（{@link #holeKeys}）；再把红点放进键盘（{@link #placeStick}）。
     */
    private static Plan finish(Draft d, int keys, boolean stickOn, float[] r, int w, int h, Sizes s, KeyRows.Row[][] layers) {
        int[] panel = d.keyboard;
        int[] bar = null, middleKey = null, hole = null;
        boolean spread = false;
        if (stickOn) {
            int pw = panel[2] - panel[0], ph = panel[3] - panel[1];
            KeyRows.Row[] letters = layers[KeyRows.LAYER_LETTERS];
            hole = stickHole(pw, letters, s);
            int top = KeyRows.holeTop(letters, ph);
            if (hole != null && top > 0) {
                spread = shortcutsSpread(hole, pw, letters, s);
                bar = new int[]{panel[0] + hole[0], panel[1] + top, panel[0] + hole[1], panel[1] + KeyRows.holeBottom(letters, ph, spread)};
                int[] m = holeKeys(hole, KeyRows.stickPoint(pw, ph, letters.length)[0], s);
                middleKey = new int[]{panel[0] + m[0], panel[0] + m[1]};
            } else {
                hole = null;
            }
        }
        int[] pad = d.pad;
        int[] menu = null;
        int margin = 0;
        if (pad != null) {
            int[][] cut = keys != KEYS_OFF && s.keyH > 0 ? cutBar(pad, w, h, keys == KEYS_TOP, s) : null;
            if (cut != null) {
                pad = cut[0];
                bar = cut[1];
            }
            menu = pad;
            if (bar != null && keys == KEYS_TOP) {
                menu = new int[]{pad[0], bar[3], pad[2], pad[3]};
                margin = s.gap;
            }
        }
        int stickAt = STICK_NONE;
        int[] stick = null;
        float cut = 0;
        if (stickOn) {
            int[] placed = placeStick(panel, w, h, s, layers, hole, spread);
            if (placed != null) {
                stickAt = placed[4];
                stick = Arrays.copyOf(placed, 4);
                if (stickAt == STICK_KEYS) cut = cutRadius((stick[2] - stick[0]) / 2, s);
            }
        } else if (bar != null) {
            middleKey = middleKey(bar, (bar[0] + bar[2]) / 2, s);   // 三键条：中键在正中
        }
        return new Plan(d.split, d.middle, d.seam, panel, d.padSide, pad, r, d.cell,
                stickAt, stick, cut, bar, middleKey, hole, spread, menu, margin);
    }

    // ═══════════ 实体键盘（第 1B 批，2026-11-01） ═══════════

    /** 实体键盘没调过大小时按"行高 ≈ 键宽 × 这么多"算自然比例（方案 §5.2；实体键盘的键差不多是方的，行矮一点按不准）。 */
    static final float FIXED_ROW_ASPECT = 1.1f;

    /**
     * 这个窗口（键盘区宽 w）放得下这一块实体键盘吗：一格（1u，字母键连缝）≥ minKey（32dp，同分裂键盘的门槛，再窄拇指按不准）。
     * 逐级缩的每一级都用它判断（{@link #fixedLevel}）。量的是 1u：笔记本的 F 键（0.8125u）、方向键（0.9375u 宽、0.625 行高）在门槛上比 32dp 小
     * （按"最小的键帽 ≥ 32dp"算的话笔记本要比 TKL 还宽才放得下，逐级缩永远轮不到它；协调者 2026-11-01 定照"一格"）。
     * 只看整页宽，不看调过的键盘大小（用户自己缩窄的不算放不下；拖窄另有 {@link #fixedMinWidth} 拦着）。
     */
    static boolean fixedFits(FixedLayout layout, int w, Sizes s) {
        return layout != null && w > 0 && w / layout.width >= s.minKey;
    }

    /**
     * 逐级缩（2026-11-01，用户："不要去帮用户判断他现在什么屏幕状态、用什么键盘了……你设定一个最小的键帽的大小……只要小于这个，你就缩那个键盘的阵列。
     * 缩完了还不放不进去，你就再缩；实在都放不进去，你就换成豆花"）：ladder = 设置里选的尺寸往下的几级（FixedLayout.ladder：全尺寸 → TKL → 75% → 笔记本……），
     * 返回第一个放得下（{@link #fixedFits}）的下标；都放不下（或者 ladder 是空的、w ≤ 0）返回 -1，这个窗口暂用豆花布局。
     * 纯按当前宽度算、不做回差（换尺寸不改变页宽，不会来回跳；页宽自己在门槛两边来回时本来就该换）。
     */
    static int fixedLevel(List<FixedLayout> ladder, int w, Sizes s) {
        if (ladder == null || w <= 0) return -1;
        for (int i = 0; i < ladder.size(); i++) if (fixedFits(ladder.get(i), w, s)) return i;
        return -1;
    }

    /**
     * 调大小时实体键盘至少占键盘区（宽 w）多宽：{@link #MIN_WIDTH}，而且一格不窄于 minKey（同显示它的门槛 {@link #fixedFits}，拖窄了键按不准）。
     * KeyboardScreen 拖边时照它夹；没调过大小的默认位置（{@link #fixedDefaultRect}）也不比它窄，不然调大小时第一下就跳一截。
     */
    static float fixedMinWidth(FixedLayout layout, int w, Sizes s) {
        if (layout == null || w <= 0) return MIN_WIDTH;
        return Math.min(1, Math.max(MIN_WIDTH, layout.width * s.minKey / w));
    }

    /**
     * 实体键盘没调过大小时的默认位置 {左, 上, 右, 下}（键盘区 w × h 的 0–1，方案 §5.2）：按自然比例（{@link #FIXED_ROW_ASPECT}）——
     * 窗口比它宽：键盘满高、按自然宽度；触控板开着、右边剩的那块放得下它（短边够 minPad）就靠左、右边给触控板，不然居中（不留一条空着的边）。
     * 窗口比它窄：键盘满宽、高按自然比例、贴底，上面那块给触控板（放不下就空着；小红点 / 关的时候也空着，用户可以调大小）。
     * 都守调大小的约束（不比 {@link #fixedMinWidth} 窄、不比 {@link #MIN_HEIGHT} 矮），调大小时第一下不跳：
     * 又宽又矮的窗口（高约 220dp 以下）按自然比例一格不到 minKey，就按一格 minKey 的宽摆、行比键宽矮（{@link #fixedFits} 只看整页宽，
     * 页面够宽时一格就得够宽；2026-11-01 审查发现 780 × 170dp 的小窗一格只有约 25dp）；又窄又高的窗口（平板竖屏）按自然比例不到 MIN_HEIGHT 高，就按 MIN_HEIGHT。
     */
    static float[] fixedDefaultRect(FixedLayout layout, int w, int h, boolean padOn, Sizes s) {
        if (layout == null || w <= 0 || h <= 0) return new float[]{0, 0, 1, 1};
        float natural = layout.width / (layout.height * FIXED_ROW_ASPECT);   // 自然的宽 / 高（笔记本 15 / (6.5 × 1.1) ≈ 2.10）
        if ((float) w / h > natural) {
            float kw = Math.min(1, Math.max(natural * h / w, fixedMinWidth(layout, w, s)));
            if (kw >= 1) return new float[]{0, 0, 1, 1};
            if (padOn && usable(new int[]{Math.round(kw * w) + s.gap, 0, w, h}, s.minPad)) return new float[]{0, 0, kw, 1};
            return new float[]{(1 - kw) / 2, 0, (1 + kw) / 2, 1};
        }
        float kh = Math.min(1, Math.max(MIN_HEIGHT, w / natural / h));
        return new float[]{0, 1 - kh, 1, 1};
    }

    /**
     * 存下的实体键盘大小位置 saved（{左, 上, 右, 下}，0–1）在这个窗口（键盘区宽 w）里显示成什么：宽度不够 {@link #fixedMinWidth}（一格 32dp）时
     * 按原来的中线撑到刚好够、再挪进 [0, 1]，高不动；够了就原样。返回新数组，存的那份不改（同分裂时的 {@link #fitSplit}）。
     * 逐级缩正好发生在窄窗口里：比如在宽窗口里把笔记本拖到六成宽，换到窄窗口缩成笔记本时一格只剩二十几 dp；撑开只是把"拖的时候本来就不许更窄"
     * 这条约束在显示时也守住，用户自己的比例在宽窗口里照旧。TKL / 全尺寸同样。
     */
    static float[] fitFixedRect(float[] saved, FixedLayout layout, int w, Sizes s) {
        float[] r = saved.clone();
        float min = fixedMinWidth(layout, w, s);
        if (r[2] - r[0] >= min) return r;
        float c = (r[0] + r[2]) / 2, l = c - min / 2, rr = c + min / 2;
        if (l < 0) {
            rr -= l;
            l = 0;
        }
        if (rr > 1) {
            l -= rr - 1;
            rr = 1;
        }
        r[0] = Math.max(0, l);
        r[2] = Math.min(1, rr);
        return r;
    }

    /**
     * 实体键盘怎么摆（KeyboardScreen 在逐级缩挑出一级时调，{@link #fixedLevel}；不分裂，用户 2026-11-01："实体键盘不分裂"）。
     * pointer / keys 同 {@link #plan}；saved = 存下的大小位置（这个尺寸的那份，Boards.savedRect，没存过是 null，用 {@link #fixedDefaultRect}；
     * 存过的经 {@link #fitFixedRect} 守住一格 ≥ minKey，Plan.rect 是撑过的那份）。
     * - 触控板：同合并时（键盘上 / 右 / 左 / 下空得最多的那块，短边够 minPad），可以切三键条。
     * - ⋯：触控板顶上正中放得下就放那儿（{@link Plan#menuArea}），不然是这个尺寸的 ⋯ 槽里的键（FixedLayout.menu：TKL / 全尺寸 / 75% 在 Esc 和 F1 之间，
     *   65% 在左下角 Ctrl 右边那格，笔记本在 F 行 Ins 那格（协议 24 起，左下第二格给了 Fn），苹果紧凑在 F 行最右）。
     * - 小红点（用户 2026-11-01："指点杆的位置是不一样的……也不是那几个键了，因为电脑键盘不是对称的；三个按键要占用的主要就是空格行了，因为没有自定义行"）：
     *   红点在真 ThinkPad 的位置（G、H 之间、B 上方，FixedPanel.stickPoint），碰到切口圆的键被切掉一块（一般是 G 的右下、H 的左下、B 的上沿正中），
     *   多大照豆花布局的规则（{@link #placeStickAt} → {@link #stickRadius}，实体键盘只有一层，每个键按字母层的门槛算），嵌不下放键盘外面；
     *   三个鼠标键占空格那一排的中间（FixedPanel.stickHole，空格切成左右两段），中键正对红点（{@link #holeKeys}）。
     *   只有笔记本、75%、65% 的 Windows 模式给小红点（FixedLayout.stickAllowed，2026-11-01 晚）：别的这里照触控板摆（{@link #fixedPointer}），
     *   saved 也该是触控板那份（Boards.pointer(尺寸) 照同一条规则取）。
     */
    static Plan planFixed(int pointer, int keys, int w, int h, float[] saved, FixedLayout layout, Sizes s) {
        pointer = fixedPointer(pointer, layout);
        if (pointer == POINTER_STICK && !s.stickReady()) pointer = POINTER_NONE;
        boolean padOn = pointer == POINTER_PAD, stickOn = pointer == POINTER_STICK;
        float[] r = saved != null ? fitFixedRect(saved, layout, w, s) : fixedDefaultRect(layout, w, h, padOn, s);
        int[] kb = {Math.round(r[0] * w), Math.round(r[1] * h), Math.round(r[2] * w), Math.round(r[3] * h)};
        int padSide = PAD_NONE;
        int[] pad = null;
        if (padOn) {
            int[][] c = {
                    {0, 0, w, kb[1] - s.gap},              // 上
                    {kb[2] + s.gap, 0, w, h},              // 右
                    {0, 0, kb[0] - s.gap, h},              // 左
                    {0, kb[3] + s.gap, w, h},              // 下
            };
            int best = largest(c, s.minPad);
            if (best >= 0) {
                padSide = new int[]{PAD_TOP, PAD_RIGHT, PAD_LEFT, PAD_BOTTOM}[best];
                pad = c[best];
            }
        }
        int pw = kb[2] - kb[0], ph = kb[3] - kb[1];
        int[] bar = null, middleKey = null, hole = null;
        int[] p = FixedPanel.stickPoint(layout, pw, ph, s.capGap);
        if (stickOn) {
            hole = FixedPanel.stickHole(layout, pw, s);
            if (hole != null) {
                bar = new int[]{kb[0] + hole[0], kb[1] + FixedPanel.spaceRowTop(layout, ph), kb[0] + hole[1], kb[1] + ph};
                int[] m = holeKeys(hole, p[0], s);
                middleKey = new int[]{kb[0] + m[0], kb[0] + m[1]};
            }
        }
        int[] menu = null;
        int margin = 0;
        if (pad != null) {
            int[][] cut = keys != KEYS_OFF && s.keyH > 0 ? cutBar(pad, w, h, keys == KEYS_TOP, s) : null;
            if (cut != null) {
                pad = cut[0];
                bar = cut[1];
            }
            menu = pad;
            if (bar != null && keys == KEYS_TOP) {
                menu = new int[]{pad[0], bar[3], pad[2], pad[3]};
                margin = s.gap;
            }
        }
        int stickAt = STICK_NONE;
        int[] stick = null;
        float cut = 0;
        if (stickOn) {
            // 小红点时没有触控板，⋯ 一定在槽里
            List<List<KeyRows.Placed>> placed = new ArrayList<>();
            placed.add(FixedPanel.placed(layout, true, pw, ph, s.capGap, hole));
            int floor = hole != null ? FixedPanel.spaceRowTop(layout, ph) : ph;
            int[] at = placeStickAt(kb, w, h, s, placed, floor, p);
            if (at != null) {
                stickAt = at[4];
                stick = Arrays.copyOf(at, 4);
                if (stickAt == STICK_KEYS) cut = cutRadius((stick[2] - stick[0]) / 2, s);
            }
        } else if (bar != null) {
            middleKey = middleKey(bar, (bar[0] + bar[2]) / 2, s);   // 三键条：中键在正中
        }
        return new Plan(false, KeyRows.MIDDLE_PAD, false, kb, padSide, pad, r, null,
                stickAt, stick, cut, bar, middleKey, hole, false, menu, margin);
    }

    /**
     * 小红点状态下底行、快捷行在中间让出的空位 {左, 右}（键盘面板里的 x；pw = 面板宽，rows = 一层的全部行），让不出返回 null。
     * 空位右边最远到右边那个空格原来的左边（右空格、↵ 不变窄；份数同以前 . 的位置），左边是缩短的空格的右边（空格 {@link KeyRows#STICK_SPACE} 份到 {@link KeyRows#STICK_SPACE_MIN} 份）。
     * 先后照顾：① 中键正对红点、左右键一样宽（空位以红点那一点为中线左右对称，照真 ThinkPad），做不到就尽量近；② 空格尽量长；
     * ③ 三个鼠标键每个至少 keyW（{@link Sizes#holeWidth}）。对称时空位右边比右空格原来的左边靠左，多出来的那截给右空格和 ↵（按 1 : 1.5 一起变宽）。
     * 1. 对称：空格能多长就多长（不超过 STICK_SPACE 份），空位至少够三个 keyW 宽的键——宽的窗口（手机横屏、大屏分屏）空格 1.5 份、
     *    空位 3 份宽；手机竖屏空格 1–1.5 份、三个键各 keyW；连苹果设备（多一个 🌐）的宽窗口空格 1–1.5 份、三个键各 keyW。
     * 2. 对称放不下（连苹果设备 + 窗口不到约 720dp 宽：空位左边被 🌐 挤到红点附近；或者很窄的窗口）：空格最短（STICK_SPACE_MIN 份）、
     *    右边到右空格原来的左边，这样中键离红点最近；三个键分这一段，每个最窄 minKey（32dp，同分裂时字母键的门槛）——
     *    比 keyW 窄，但三个键有两行高，比以前那排 48dp 高的好按。再窄就不让位（三个鼠标键不显示，红点照样有）。
     */
    static int[] stickHole(int pw, KeyRows.Row[] rows, Sizes s) {
        float[] u = KeyRows.holeUnits(rows);
        if (u == null || pw <= 0 || u[2] <= 0) return null;
        float unit = pw / u[2];
        int cx = KeyRows.stickPoint(pw, 1, rows.length)[0];
        int right = Math.round(unit * (u[2] - u[1]));   // 右空格原来的左边（= 整行时 . 的左边）
        int longest = Math.round(unit * u[0]);   // 空格 STICK_SPACE 份时空位的左边
        int shortest = Math.round(unit * (u[0] - (KeyRows.STICK_SPACE - KeyRows.STICK_SPACE_MIN)));   // 空格 STICK_SPACE_MIN 份
        int need = s.holeWidth(s.keyW);
        // 1. 对称：左边 ≤ 红点 − 半个空位（够宽）、≤ longest（空格不超过 1.5 份），尽量靠右；还得 ≥ shortest、右边不过右空格原来的左边
        int l = Math.min(longest, cx - (need + 1) / 2);
        if (l >= Math.max(shortest, 2 * cx - right)) return new int[]{l, 2 * cx - l};
        // 2. 空格最短、右边到右空格原来的左边（离对称最近），三个键分这一段
        if (right - shortest >= s.holeWidth(s.minKey)) return new int[]{shortest, right};
        return null;
    }

    /**
     * 快捷行也让出空位 hole 吗（三个鼠标键跨两行，2026-11-01 用户要的"高度跟这两个高度等高"）：快捷键按 KeyRows.spreadLeft 分到空位两边后，
     * 每边每个键（连缝）都不窄于 minKey（32dp，同分裂时字母键的门槛：再窄拇指按不准）才让；没有快捷行、或者分开后太窄，返回假——
     * 快捷行照旧铺满整行，三个鼠标键只占空格那一排（2026-11-01 审查发现：手机竖屏 9 个快捷键分到两边每个只剩 21–27dp，
     * 德 / 法 / 西 / 意 / 日 / 韩语的短名缩到最小字号也放不下）。pw = 面板宽，rows = 一层的全部行。
     * 手机竖屏（360–411dp）上 9 个默认快捷键分不开、只让底行；7 个以下（411dp）、宽窗口（手机横屏、大屏分屏下半边）跨两行。
     */
    static boolean shortcutsSpread(int[] hole, int pw, KeyRows.Row[] rows, Sizes s) {
        if (hole == null) return false;
        for (KeyRows.Row row : rows) {
            if (!row.spread) continue;
            int n = row.whole.length;
            if (n == 0) return false;
            int left = hole[0], right = pw - hole[1];
            int k = KeyRows.spreadLeft(n, left, right);
            if (k > 0 && left < (long) k * s.minKey) return false;
            if (k < n && right < (long) (n - k) * s.minKey) return false;
            return true;
        }
        return false;
    }

    /**
     * 三个鼠标键怎么铺空位 hole {左, 右}（返回中键那一格的 {左, 右}，同一套 x）：每个键帽四边缩进 capGap，键帽之间隔 2 × capGap；
     * 键帽宽度照 {@link #middleKey}（中键是三个键帽总宽的 {@link #MIDDLE_SHARE}、至少 minMiddle，左右键填满剩下的、不比中键窄），
     * 中键对着 center（红点那一点，挪不过去就挪到最近的地方）。左键那一格从 hole 左边到中键那一格，右键那一格从它到 hole 右边。
     */
    static int[] holeKeys(int[] hole, int center, Sizes s) {
        int g = s.capGap;
        int[] m = middleKey(new int[]{hole[0] + g, 0, hole[1] - g, 0}, center, 2 * g, s.minMiddle);
        return new int[]{m[0] - g, m[1] + g};
    }

    /**
     * 三键条从触控板 pad（{左, 上, 右, 下}，页面坐标；页面 w × h）的上沿（top）或下沿切出来：高 keyH，和剩下的触控板隔 keyGap，
     * 离页面四条边都至少 edge（Sizes.edgeL / T / R / B；贴着屏幕边那一截空着、不响应，同以前的工具栏）；中键窄、左右键宽（{@link #middleKey}，对着这一条的中线）。
     * 返回 {剩下的触控板, 三键条}；三个键排不下、或者切完触控板短边不够 minPad，返回 null（不切，触控板照旧）。
     * 主界面的触控板也用它（窗口坐标）。
     */
    static int[][] cutBar(int[] pad, int w, int h, boolean top, Sizes s) {
        if (pad == null || s.keyH <= 0) return null;
        int left = Math.max(pad[0], s.edgeL), right = Math.min(pad[2], w - s.edgeR);
        if (right - left < s.keysWidth()) return null;
        int barTop, barBottom;
        int[] rest;
        if (top) {
            barTop = Math.max(pad[1], s.edgeT);
            barBottom = barTop + s.keyH;
            rest = new int[]{pad[0], barBottom + s.keyGap, pad[2], pad[3]};
        } else {
            barBottom = Math.min(pad[3], h - s.edgeB);
            barTop = barBottom - s.keyH;
            rest = new int[]{pad[0], pad[1], pad[2], barTop - s.keyGap};
        }
        if (barTop < pad[1] || barBottom > pad[3] || !usable(rest, s.minPad)) return null;
        return new int[][]{rest, {left, barTop, right, barBottom}};
    }

    /**
     * 三个鼠标键（bar {左, 上, 右, 下}，键盘最下面那排或者三键条）里中键的 {左, 右}：左右键宽、中键窄——三个键总宽的
     * {@link #MIDDLE_SHARE}（照 ThinkPad 约 2.2 : 1 : 2.2），至少 minMiddle，左右键填满剩下的、都不窄于中键
     * （整排窄得三个一样宽都不到 minMiddle 时三个一样宽）；中键对着 center（红点的中线，真 ThinkPad 的中键在红点正下方；
     * 没红点时是这一排的中线），红点不在正中时左右键不一样宽，挪不过去就挪到最近的地方。键与键之间隔 keyGap。
     */
    static int[] middleKey(int[] bar, int center, Sizes s) {
        return middleKey(bar, center, s.keyGap, s.minMiddle);
    }

    /** 同上，键与键之间隔 gap、中键至少 minMiddle。 */
    private static int[] middleKey(int[] bar, int center, int gap, int minMiddle) {
        int inner = Math.max(0, bar[2] - bar[0] - 2 * gap);
        int m = Math.min(inner / 3, Math.max(minMiddle, Math.round(inner * MIDDLE_SHARE)));
        int lo = bar[0] + m + gap;
        int hi = bar[2] - 2 * m - gap;
        int left = Math.max(lo, Math.min(center - m / 2, hi));
        return new int[]{left, left + m};
    }

    /**
     * 小红点放哪，返回 {左, 上, 右, 下, STICK_*}（页面坐标，正方形，按得到的范围是它的内切圆），放不下返回 null。
     * 先嵌进键盘 panel 里 KeyRows.stickPoint 那一点（g、h、v、b 交界），半径见 {@link #stickRadius}（STICK_KEYS）；hole = 底行、
     * 快捷行让出的空位（{@link #stickHole}，null = 没让），切口不碰到它。
     * 嵌不下（键太小）就放到键盘上面、隔一条缝，对着那一点（STICK_OUTSIDE）：边长不超过 stick、不小于 minStick，
     * 离页面边至少 edge（Sizes.edgeL / T / R / B）；上面没地方放下面；上下都没有就放键盘右边或左边空着的那块（空得多的那边，挨着键盘、
     * 隔一条缝，竖着对齐那一点）。2026-11-01 用户在分屏下半边换成合并：存下的键盘只占左边六成、键很矮，
     * 嵌不下，上下也没地方，右边空着四成却不放——"完全找不见"。都没有才返回 null（三个鼠标键照样有）。
     * 三个鼠标键的中键对着那一点（g|h 的缝），不看红点放在哪。
     */
    static int[] placeStick(int[] panel, int w, int h, Sizes s, KeyRows.Row[][] layers, int[] hole, boolean spread) {
        int pw = panel[2] - panel[0], ph = panel[3] - panel[1];
        KeyRows.Row[] letters = layers[KeyRows.LAYER_LETTERS];
        int[] p = KeyRows.stickPoint(pw, ph, letters.length);
        int floor = hole != null ? KeyRows.holeTop(letters, ph) : ph;
        return placeStickAt(panel, w, h, s, placedLayers(layers, pw, ph, s, hole, spread), floor, p);
    }

    /**
     * 同 {@link #placeStick}，键已经摆好（placed：每一层的键在面板里的位置，第 0 层按字母层的门槛算）、红点那一点 p（面板坐标）、
     * 切口不过 floor（面板 y）。豆花布局和实体键盘（{@link #planFixed}，只有一层）共用：嵌在键里多大、嵌不下放哪，规则只有这一份。
     */
    static int[] placeStickAt(int[] panel, int w, int h, Sizes s, List<List<KeyRows.Placed>> placed, int floor, int[] p) {
        int pw = panel[2] - panel[0];
        int radius = stickRadius(placed, pw, floor, p, s);
        int cx = panel[0] + p[0];
        if (radius > 0) {
            int cy = panel[1] + p[1];
            return new int[]{cx - radius, cy - radius, cx + radius, cy + radius, STICK_KEYS};
        }
        int size;
        int across = w - s.edgeL - s.edgeR;
        int above = Math.min(across, panel[1] - s.gap - s.edgeT);
        int below = Math.min(across, h - s.edgeB - panel[3] - s.gap);
        int top;
        if (above >= s.minStick) {
            size = Math.min(s.stick, above);
            top = panel[1] - s.gap - size;
        } else if (below >= s.minStick) {
            size = Math.min(s.stick, below);
            top = panel[3] + s.gap;
        } else {
            // 上下都没地方：键盘旁边空着的那块（右边、左边取空得多的），竖着对齐那一点
            int tall = h - s.edgeT - s.edgeB;
            int rightRoom = Math.min(tall, w - s.edgeR - panel[2] - s.gap);
            int leftRoom = Math.min(tall, panel[0] - s.gap - s.edgeL);
            boolean right = rightRoom >= leftRoom;
            size = Math.min(s.stick, right ? rightRoom : leftRoom);
            if (size < s.minStick) return null;
            int cy = panel[1] + p[1];
            int t = Math.max(s.edgeT, Math.min(cy - size / 2, h - s.edgeB - size));
            int l = right ? panel[2] + s.gap : panel[0] - s.gap - size;
            return new int[]{l, t, l + size, t + size, STICK_OUTSIDE};
        }
        int left = Math.max(s.edgeL, Math.min(cx - size / 2, w - s.edgeR - size));
        return new int[]{left, top, left + size, top + size, STICK_OUTSIDE};
    }

    /**
     * 三层的键各自摆在面板（pw × ph，整块）里的位置（同 KeyboardPanel 摆的：缩进 capGap；hole = 底行、快捷行让出的空位，
     * spread = 快捷行也让，{@link Plan#spread}）。
     */
    static List<List<KeyRows.Placed>> placedLayers(KeyRows.Row[][] layers, int pw, int ph, Sizes s, int[] hole, boolean spread) {
        List<List<KeyRows.Placed>> out = new ArrayList<>();
        for (KeyRows.Row[] rows : layers) out.add(KeyRows.place(rows, pw, ph, false, KeyRows.MIDDLE_PAD, s.capGap, hole, spread));
        return out;
    }

    /** 按得到的范围半径 r 时键上切口的半径：范围的边在红帽和切口之间那条缝的中线上。 */
    static float cutRadius(int r, Sizes s) {
        return r + s.stickGap / 2f;
    }

    /**
     * 嵌在 p（面板里的点，面板宽 pw）的红点按得到的范围（圆）半径多大：越大越好按，挨着的键被切掉的也越多。取最大的一个：
     * 直径不超过 stick（64dp）；切口（{@link #cutRadius}）不出面板左右、上边，不过 floor（底行的上沿，三个鼠标键在那儿；没让空位时是面板下沿）；
     * 字母层（天天打字的那层）每个键被切掉的面积 ≤ {@link #STICK_LETTER_SHARE}、每一层（符号层、Fn 层在这一点上是别的键）每个键
     * ≤ {@link #STICK_MAX_SHARE}（{@link #cutShare}）；每个被切到的键上的字躲得开切口（{@link #labelShift}）。
     * 这样还不到 minStick / 2（键小）时，只要 minStick / 2 每一层每个键都 ≤ STICK_MAX_SHARE、字也躲得开就取它，不然返回 0（嵌不下）。
     * placed = {@link #placedLayers}。
     */
    static int stickRadius(List<List<KeyRows.Placed>> placed, int pw, int floor, int[] p, Sizes s) {
        int lo = 0, hi = Math.max(0, s.stick / 2);   // 越大切得越多、字越难躲（只会变难），二分找最大的
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fits(placed, pw, floor, p, mid, STICK_LETTER_SHARE, s)) lo = mid;
            else hi = mid - 1;
        }
        int min = (s.minStick + 1) / 2;
        if (lo >= min) return lo;
        if (min > 0 && fits(placed, pw, floor, p, min, STICK_MAX_SHARE, s)) return min;
        return 0;
    }

    /** 半径 r 的红点：切口在面板里、不过 floor，字母层每个键被切掉 ≤ letterShare、每一层每个键 ≤ STICK_MAX_SHARE，被切到的键上的字躲得开。 */
    private static boolean fits(List<List<KeyRows.Placed>> placed, int pw, int floor, int[] p, int r, float letterShare, Sizes s) {
        float rc = cutRadius(r, s);
        if (p[0] - rc < 0 || p[0] + rc > pw || p[1] - rc < 0 || p[1] + rc > floor) return false;
        for (int i = 0; i < placed.size(); i++) {
            float limit = i == KeyRows.LAYER_LETTERS ? Math.min(letterShare, STICK_MAX_SHARE) : STICK_MAX_SHARE;
            for (KeyRows.Placed k : placed.get(i)) {
                if (!cuts(k, p[0], p[1], rc)) continue;
                if (cutShare(k, p[0], p[1], rc) > limit) return false;
                if (Float.isNaN(labelShift(k, p[0], p[1], rc, s.labelRoom))) return false;
            }
        }
        return true;
    }

    /** 圆心 (cx, cy)、半径 r 的切口碰不碰到键帽 k（矩形里离圆心最近的点在圆里面）。 */
    static boolean cuts(KeyRows.Placed k, float cx, float cy, float r) {
        float dx = Math.max(0, Math.max(k.left - cx, cx - k.right));
        float dy = Math.max(0, Math.max(k.top - cy, cy - k.bottom));
        return dx * dx + dy * dy < r * r;
    }

    /**
     * 圆心 (cx, cy)、半径 r 的切口切掉键帽 k 多少面积（0–1），精确算：按 x 积分竖条的长度（圆和矩形上下边的交点处分段，
     * 每段里竖条的上端要么是矩形的上边、要么是圆弧，下端同样），圆弧那部分用 ∫√(r² − u²) du 的原函数。
     */
    static float cutShare(KeyRows.Placed k, float cx, float cy, float r) {
        double w = k.right - k.left, hgt = k.bottom - k.top;
        if (w <= 0 || hgt <= 0 || r <= 0) return 0;
        double x0 = Math.max(k.left, cx - r), x1 = Math.min(k.right, cx + r);
        if (x1 <= x0) return 0;
        double[] at = new double[6];
        int n = 0;
        at[n++] = x0;
        at[n++] = x1;
        for (double e : new double[]{k.bottom - cy, k.top - cy}) {   // 圆弧和这条边相交的地方：√(r² − u²) = |e|
            double v = Math.abs(e);
            if (v >= r) continue;
            double d = Math.sqrt((double) r * r - v * v);
            for (double x : new double[]{cx - d, cx + d}) if (x > x0 && x < x1) at[n++] = x;
        }
        Arrays.sort(at, 0, n);
        double area = 0;
        for (int i = 0; i + 1 < n; i++) {
            double a = at[i], b = at[i + 1];
            if (b <= a) continue;
            double m = (a + b) / 2, hm = arcHalf(m - cx, r);
            boolean topArc = cy - hm > k.top, bottomArc = cy + hm < k.bottom;   // 竖条的上端 / 下端是圆弧（不是矩形的边）
            if (Math.min(k.bottom, cy + hm) <= Math.max(k.top, cy - hm)) continue;   // 这一段圆和矩形竖着不重叠
            double arc = arcArea(b - cx, r) - arcArea(a - cx, r);   // ∫ √(r² − u²)
            double upper = topArc ? cy * (b - a) - arc : k.top * (b - a);
            double lower = bottomArc ? cy * (b - a) + arc : k.bottom * (b - a);
            area += lower - upper;
        }
        return (float) (area / (w * hgt));
    }

    /** 半个弦长 √(r² − u²)（u 出了 [−r, r] 是 0）。 */
    private static double arcHalf(double u, double r) {
        return Math.sqrt(Math.max(0, r * r - u * u));
    }

    /** ∫₀ᵘ √(r² − t²) dt = (u √(r² − u²) + r² asin(u / r)) / 2，u 夹到 [−r, r]。 */
    private static double arcArea(double u, double r) {
        u = Math.max(-r, Math.min(r, u));
        return (u * arcHalf(u, r) + r * r * Math.asin(u / r)) / 2;
    }

    /** 三层里被切口（圆心 (cx, cy)、半径 r）切掉最多的那个键被切掉了多少（0 = 一个键都没碰到）。 */
    static float maxCut(List<List<KeyRows.Placed>> placed, float cx, float cy, float r) {
        float max = 0;
        for (List<KeyRows.Placed> layer : placed) for (KeyRows.Placed k : layer) max = Math.max(max, cutShare(k, cx, cy, r));
        return max;
    }

    /**
     * 键帽 k 上的字要竖着挪多少（像素，负 = 往上）才躲得开切口（圆心 (cx, cy)、半径 rc）：把字看成以键帽中心为圆心、半径 room 的一块，
     * 和切口之间至少不重叠；往远离切口圆心的方向挪（切口在下面就往上），挪得最少的那个。挪完那块字还得在键帽里
     * （|挪的| ≤ 键帽高 / 2 − room），做不到返回 NaN。不用挪（离得够远、或者横着就隔得开）返回 0。
     * 只竖着挪：红点在两行的交界上，切口总是从键帽的上沿或下沿切进来。KeyboardPanel 照它给键帽加上下内边距（字跟着挪），KeyboardPlan 拿它判断嵌不嵌得下。
     */
    static float labelShift(KeyRows.Placed k, float cx, float cy, float rc, float room) {
        float kx = (k.left + k.right) / 2f, ky = (k.top + k.bottom) / 2f;
        float dx = kx - cx, vy = ky - cy;
        float reach = rc + room;
        float q2 = reach * reach - dx * dx;
        if (q2 <= 0) return 0;
        float q = (float) Math.sqrt(q2);
        if (Math.abs(vy) >= q) return 0;
        float shift = q - Math.abs(vy);
        float max = (k.bottom - k.top) / 2f - room;
        if (shift > max) return Float.NaN;
        return vy < 0 ? -shift : vy > 0 ? shift : -shift;   // 正对着圆心（竖着）时往上挪
    }

    /** 分裂时一半至少多宽（像素）：一份（字母键连缝）够 {@link Sizes#minKey}。 */
    static int minHalf(Sizes s) {
        return (int) Math.ceil(KeyRows.HALF_UNITS * s.minKey);
    }

    /** 格子至少多宽（占键盘区宽的比例）：一半够 {@link #minHalf}，加上格子连着的半条缝，再多一像素（取整）。 */
    static float minCell(int w, Sizes s) {
        return w > 0 ? (minHalf(s) + s.gap / 2f + 1) / w : SPLIT_MEET;
    }

    /**
     * 存下的格子按当前窗口守住约束（返回新数组，不改 cell）：上下在键盘区里、至少 {@link #MIN_HEIGHT} 高；
     * 左右在 [0, 0.5] 里、不重叠；一半不够 {@link #minHalf} 宽（窗口变窄了）就先往中间伸（直到只隔一条缝），还不够再往外伸（直到贴边）。
     * 贴边、伸满都不够的窗口 {@link #shouldSplit} 本来就不分裂。
     */
    static float[] fitSplit(float[] cell, int w, Sizes s) {
        float l = clamp(cell[0], 0, SPLIT_MEET);
        float r = clamp(cell[2], l, SPLIT_MEET);
        float min = minCell(w, s);
        if (r - l < min) r = Math.min(SPLIT_MEET, l + min);
        if (r - l < min) l = Math.max(0, r - min);
        float t = clamp(cell[1], 0, 1);
        float b = clamp(cell[3], 0, 1);
        if (b - t < MIN_HEIGHT) {
            b = Math.min(1, t + MIN_HEIGHT);
            t = Math.max(0, b - MIN_HEIGHT);
        }
        return new float[]{l, t, r, b};
    }

    /**
     * 调整模式里拖分裂键盘：cell 是左半格子的手指位置（不含吸附），edge 是拖的哪条边（按左半说，KeyboardSnap.LEFT = 外侧边、
     * RIGHT = 内侧边、TOP、BOTTOM、MOVE = 中间），dx / dy 是占键盘区的比例——拖的是右半时 dx 由调用方先取反（镜像）。
     * 两半一起变：拖内侧边两半一起变宽 / 变窄（中间跟着变窄 / 变宽），拖外侧边两半一起往里收 / 推回屏幕边，拖上下边一起变高变矮，
     * 拖中间只上下挪（两半对称，左右挪就不对称了，横向位移不要）。守住：一半不窄于 {@link #minHalf}、不重叠（右 ≤ 0.5）、
     * 不出界、不矮于 {@link #MIN_HEIGHT}。
     */
    static void dragSplit(float[] cell, int edge, float dx, float dy, int w, Sizes s) {
        float min = minCell(w, s);
        switch (edge) {
            case KeyboardSnap.MOVE: {
                float hgt = cell[3] - cell[1];
                cell[1] = clamp(cell[1] + dy, 0, 1 - hgt);
                cell[3] = cell[1] + hgt;
                break;
            }
            case KeyboardSnap.LEFT:
                cell[0] = clamp(cell[0] + dx, 0, cell[2] - min);
                break;
            case KeyboardSnap.RIGHT:
                cell[2] = Math.min(SPLIT_MEET, Math.max(cell[0] + min, cell[2] + dx));
                break;
            case KeyboardSnap.TOP:
                cell[1] = clamp(cell[1] + dy, 0, cell[3] - MIN_HEIGHT);
                break;
            case KeyboardSnap.BOTTOM:
                cell[3] = clamp(cell[3] + dy, cell[1] + MIN_HEIGHT, 1);
                break;
            default:
                break;
        }
    }

    /**
     * 调整模式里按下的点归哪一半：在两条内侧边的中线右边（含中线）= 右半（拖动时左右位移要镜像）。
     * 按离哪条内侧边近就算哪一半的——两半合拢或只隔不到两个把手宽时，两条内侧边的把手范围重叠，
     * 不能固定先判左半，不然按在右半内侧边上会被当成左半的、不镜像，拖的方向就反了。
     * 两半左右对称，这条中线也就是整个分裂键盘的中线。
     */
    static boolean onRightHalf(float x, float leftInner, float rightInner) {
        return x * 2 >= leftInner + rightInner;
    }

    /** 夹到 [min, max] 里；max < min 时取 min。 */
    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    static boolean shouldSplit(int mode, boolean padOn, int w, int h, Sizes s) {
        return shouldSplit(mode, padOn ? POINTER_PAD : POINTER_NONE, w, h, s);
    }

    /**
     * 分不分裂。自动：宽窗口，而且键够宽（触控板时按中间放触控板的三分之一布局算，关着时按两半伸满算）；
     * 强制分裂：两半伸满时键够宽就分（触控板放不进中间就去上方 / 下方或者不放）；合并：不分。小红点：一律不分（2026-11-01 起）。
     */
    static boolean shouldSplit(int mode, int pointer, int w, int h, Sizes s) {
        if (mode == MODE_MERGE || h <= 0 || pointer == POINTER_STICK) return false;
        boolean wideOk = KeyRows.splitUnit(w, s.gap) >= s.minKey;
        if (mode == MODE_SPLIT) return wideOk;
        if (!KeyRows.shouldSplit(w, h)) return false;
        if (pointer == POINTER_PAD) return KeyRows.splitUnit(w, KeyRows.MIDDLE_PAD) >= s.minKey;
        return wideOk;
    }

    /** 短边够 minPad 的候选里面积最大的（一样大取靠前的），都不够返回 -1。 */
    static int largest(int[][] candidates, int minPad) {
        int best = -1;
        long bestArea = 0;
        for (int i = 0; i < candidates.length; i++) {
            int[] c = candidates[i];
            if (!usable(c, minPad)) continue;
            long area = (long) (c[2] - c[0]) * (c[3] - c[1]);
            if (area > bestArea) {
                best = i;
                bestArea = area;
            }
        }
        return best;
    }

    /** 这块够不够当触控板：短边 ≥ minPad（只看高的话，左右窄成一条的也会放）。 */
    static boolean usable(int[] r, int minPad) {
        return Math.min(r[2] - r[0], r[3] - r[1]) >= Math.max(1, minPad);
    }

    /**
     * 调整模式那条栏里每个按钮多宽：count 个按钮在 width 里排一行，左右各离屏幕边 edge，按钮两边各 margin；
     * 放得下就是 max，放不下就一起缩（文字本来就会自动缩字号）。
     */
    static int barButtonWidth(int width, int count, int edge, int margin, int max) {
        if (count <= 0) return 0;
        int slot = (width - 2 * edge) / count;
        return Math.max(0, Math.min(max, slot - 2 * margin));
    }

    // ═══════════ ⋯ 菜单 ═══════════

    /**
     * ⋯（bw × bh 的触摸范围，不画框）放在触控板 pad（{左, 上, 右, 下}，页面坐标）顶上正中，返回它的 {左, 上}；放不下返回 null（⋯ 改到键盘功能行上）。
     * pad 也可以是 {@link Plan#menuArea}：三键条在上沿时是三键条下面那块（margin = 一条缝，⋯ 在三键条正下方）。
     * 2026-10-31 用户：放在角上丑、顶上正中那块平时不怎么按。原来挑离屏幕边最远的角（menuCorner）。
     * 离触控板顶边 margin；页面 w × h 就是整块屏（全屏时），拿手机的手指常压在屏幕边上，所以还要离页面四条边都至少 edge
     * （触控板贴着屏幕上边时往下推）；推完还得整个在触控板里。
     */
    static int[] menuTop(int[] pad, int w, int h, int bw, int bh, int margin, int edge) {
        return menuTop(pad, w, h, bw, bh, margin, same(edge));
    }

    /** 同上，离页面左、上、右、下边各至少 e[0..3]（页面因屏幕边缘留白往里收了的那几条边少留，EdgeMargin.edges）。 */
    static int[] menuTop(int[] pad, int w, int h, int bw, int bh, int margin, int[] e) {
        if (pad == null) return null;
        int x = (pad[0] + pad[2] - bw) / 2;
        int y = Math.max(pad[1] + margin, e[1]);
        x = Math.min(Math.max(x, e[0]), w - e[2] - bw);
        if (x < e[0] || y + bh > h - e[3] || x < pad[0] || y < pad[1] || x + bw > pad[2] || y + bh > pad[3]) return null;
        return new int[]{x, y};
    }

    /** 四条边一样的 {edge, edge, edge, edge}。 */
    private static int[] same(int edge) {
        return new int[]{edge, edge, edge, edge};
    }

    /**
     * 主界面触控板上的 ⋯（2026-11-01 起每块触控板顶上正中都有，主界面没有功能行可退）：照 {@link #menuTop} 放；放不下（触控板太矮、
     * 太窄、离屏幕边太近）也照样放——尽量离页面边 edge，但不出触控板（比 ⋯ 还小时贴着触控板左上角），离边少一点总比没有菜单强。
     */
    static int[] padMenuTop(int[] pad, int w, int h, int bw, int bh, int margin, int edge) {
        int[] at = menuTop(pad, w, h, bw, bh, margin, edge);
        if (at != null) return at;
        int x = (pad[0] + pad[2] - bw) / 2;
        if (w - 2 * edge >= bw) x = Math.min(Math.max(x, edge), w - edge - bw);   // 页面窄得左右都留不出 edge 就不管了
        x = Math.max(pad[0], Math.min(x, pad[2] - bw));
        int y = Math.max(pad[1] + margin, edge);
        y = Math.max(pad[1], Math.min(y, pad[3] - bh));
        return new int[]{x, y};
    }

    /**
     * 菜单怎么排（count 项，页面 w × h），返回 {列数, 行数, 每项宽, 每项高, 菜单宽, 菜单高}：菜单四周留 pad、项与项之间隔 gap，
     * 每项最宽 maxW、最高 itemH。2026-11-01 项变多了（键盘页七项，一列高约 356dp，手机横屏全屏只有三百多 dp 高），排法：
     * 1. 离页面边 edge 放得下的话：先一列，每项压矮到放得下（最矮 minItemH）；一列压到 minItemH 还放不下再分列（先排满第一列，
     *    同按钮条），列宽不窄于 minColW；
     * 2. 还不行就不留边（贴着页面边，同 menuPosition 的"不够就少留"）再按 1 试一遍；
     * 3. 最矮的窗口：列宽放宽到 minColW 的一半，挑列数最少、每项还有 minItemH 高的；再不行挑每项最高的那种（列数一样取少的）。
     * 每列的项从上到下排满再排下一列，列数按行数重算（不会多出一列空的）。
     */
    static int[] menuGrid(int count, int w, int h, int edge, int pad, int gap, int maxW, int itemH, int minItemH, int minColW) {
        return menuGrid(count, w, h, same(edge), pad, gap, maxW, itemH, minItemH, minColW);
    }

    /** 同上，"离页面边"按左、上、右、下各 e[0..3] 算（EdgeMargin.edges）。 */
    static int[] menuGrid(int count, int w, int h, int[] e, int pad, int gap, int maxW, int itemH, int minItemH, int minColW) {
        count = Math.max(1, count);
        for (int[] m : new int[][]{e, same(0)}) {
            for (int cols = 1; cols <= count; cols++) {
                int[] g = grid(count, cols, w - m[0] - m[2], h - m[1] - m[3], pad, gap, maxW, itemH);
                if (g == null) break;
                if (cols > 1 && g[2] < minColW) break;   // 列太窄：不再加列（一列时再窄也只能这样）
                if (g[3] >= minItemH) return g;
                if (g[0] < cols) break;   // 列数已经到顶（每列一项），加列不会更矮
            }
        }
        int[] best = null;
        for (int cols = 1; cols <= count; cols++) {
            int[] g = grid(count, cols, w, h, pad, gap, maxW, itemH);
            if (g == null || (cols > 1 && g[2] < minColW / 2)) break;
            if (g[3] >= minItemH) return g;
            if (best == null || g[3] > best[3]) best = g;
            if (g[0] < cols) break;
        }
        return best != null ? best : grid(count, 1, Math.max(w, 2 * pad + 1), Math.max(h, 2 * pad + count * (gap + 1)), pad, gap, maxW, itemH);
    }

    /** cols 列（按行数重算过的实际列数）在 w × h 里每项多宽多高；宽度一点都排不下时 null。 */
    private static int[] grid(int count, int cols, int w, int h, int pad, int gap, int maxW, int itemH) {
        int rows = (count + cols - 1) / cols;
        cols = (count + rows - 1) / rows;
        int colW = Math.min(maxW, (w - 2 * pad - (cols - 1) * gap) / cols);
        if (colW <= 0) return null;
        int ih = Math.max(1, Math.min(itemH, (h - 2 * pad - (rows - 1) * gap) / rows));
        return new int[]{cols, rows, colW, ih, 2 * pad + cols * colW + (cols - 1) * gap, 2 * pad + rows * ih + (rows - 1) * gap};
    }

    /** 不居中的版本（⋯ 是功能行上的键）。 */
    static int[] menuPosition(int[] anchor, int mw, int mh, int w, int h, int edge, int gap) {
        return menuPosition(anchor, mw, mh, w, h, edge, gap, false);
    }

    /**
     * 菜单（mw × mh）弹在哪，返回 {左, 上}：贴着 ⋯（anchor {左, 上, 右, 下}）——center 时和 ⋯ 居中对齐（⋯ 在触控板顶上正中），
     * 不然 ⋯ 在页面右半就和它右边对齐，在左半就和它左边对齐；
     * 先放 ⋯ 下面（隔 gap），下面放不下放上面，都放不下放空得多的那边再往里挪；挪完还压着 ⋯ 就横着放到 ⋯ 旁边（隔 gap，
     * 横着放得下的话）。整个菜单在页面 w × h 里，
     * 地方够的话离页面边至少 edge（同 ⋯ 小方块，免得拿手机的手指碰到），不够就少留一点，绝不出屏。
     */
    static int[] menuPosition(int[] anchor, int mw, int mh, int w, int h, int edge, int gap, boolean center) {
        return menuPosition(anchor, mw, mh, w, h, same(edge), gap, center);
    }

    /**
     * 同上，离页面左、上、右、下边各至少 e[0..3]（EdgeMargin.edges）；地方不够时两边按比例一起少留
     * （四边一样时就是以前的"各留一半空余"）。
     */
    static int[] menuPosition(int[] anchor, int mw, int mh, int w, int h, int[] e, int gap, boolean center) {
        int[] mx = room(e[0], e[2], w - mw);
        int[] my = room(e[1], e[3], h - mh);
        boolean rightSide = anchor[0] + anchor[2] >= w;   // ⋯ 的中线在页面右半
        int left = center ? (anchor[0] + anchor[2] - mw) / 2 : rightSide ? anchor[2] - mw : anchor[0];
        left = Math.max(mx[0], Math.min(left, w - mx[1] - mw));
        int below = anchor[3] + gap;
        int above = anchor[1] - gap - mh;
        int top;
        if (below + mh <= h - my[1]) {
            top = below;
        } else if (above >= my[0]) {
            top = above;
        } else {
            top = h - anchor[3] >= anchor[1] ? below : above;
            top = Math.max(my[0], Math.min(top, h - my[1] - mh));
            // 竖着躲不开 ⋯（挪进屏后还压着它）：放到它旁边——在右半放左边、在左半放右边，横着放得下才挪
            if (top < anchor[3] && anchor[1] < top + mh) {
                int side = rightSide ? anchor[0] - gap - mw : anchor[2] + gap;
                if (side >= mx[0] && side + mw <= w - mx[1]) left = side;
            }
        }
        return new int[]{Math.max(0, left), Math.max(0, top)};
    }

    /** 一个方向上两头各留多少：a、b 放得下（a + b ≤ spare）就照留，不然按比例一起缩到正好放下，spare ≤ 0 时都是 0。 */
    private static int[] room(int a, int b, int spare) {
        if (spare <= 0 || a + b <= 0) return new int[2];
        if (a + b <= spare) return new int[]{a, b};
        return new int[]{a * spare / (a + b), b * spare / (a + b)};
    }

    /**
     * 调整模式那条栏（高 barH）放在页面上面还是下面，返回它的上边：离那条屏幕边 edge（不贴边，同 ⋯）。
     * 哪边压着键盘（kbTop–kbBottom）少放哪边，一样（都不压，或者键盘占满高都压）放上面；拖键盘时跟着重算，键盘拖到哪边栏就让到另一边。
     * 页面矮得连 2 × edge + barH（上下各 edge）都不够就竖着居中。
     */
    static int adjustBarTop(int h, int barH, int edge, int kbTop, int kbBottom) {
        return adjustBarTop(h, barH, edge, edge, kbTop, kbBottom);
    }

    /** 同上，离页面上边至少 edgeTop、下边至少 edgeBottom（EdgeMargin.edges）。 */
    static int adjustBarTop(int h, int barH, int edgeTop, int edgeBottom, int kbTop, int kbBottom) {
        int top = edgeTop;
        int bottom = h - edgeBottom - barH;
        if (bottom < top) return Math.max(0, (h - barH) / 2);
        int overTop = overlap(top, top + barH, kbTop, kbBottom);
        int overBottom = overlap(bottom, bottom + barH, kbTop, kbBottom);
        return overBottom < overTop ? bottom : top;
    }

    private static int overlap(int a0, int a1, int b0, int b1) {
        return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
    }
}
