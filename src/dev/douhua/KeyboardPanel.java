package dev.douhua;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.douhua.KeyRows.Key;

/**
 * 手机上的外接键盘。每个键对应一个 HID 用法码（可以自带修饰键，比如"@" = Shift+2、"复制" = Ctrl+C），
 * 按下后由豆花服务通过虚拟 USB 键盘发给系统——系统看到的就是一块真键盘。
 *
 * 字母区照 Gboard 排（手机打字的习惯最重要）；修饰键、数字、快捷键各占一排，不往字母区里硬塞：
 *   功能行   Esc Tab Shift Ctrl Alt Super Caps Fn Del（小触控板没摆出来时最右边多一个 ⋯：键盘界面的菜单，见 {@link #setMenuKey}）
 *   数字行   1–0（锁定 Shift 时显示 !@#…）
 *   字母三行 q–p / a–l / ⇧ z–m ⌫
 *   底行     符 ，空格 。↵
 *   快捷行   默认 复制 粘贴 剪切 撤销 全选 ← ↑ ↓ →，9 个位置都能在设置里换（选项是共用的动作清单 Actions）
 * "符"层把数字行和字母三行换成符号（一按就出，不用配 Shift），"Fn"层换成 F1–F12 和 Home/End/翻页。
 * 修饰键点一下就是按一下，单独按住 0.4–1 秒松手才锁（高亮），打完下一个键自动解开；Caps 点一下发、按住只切显示（2026-11-01，规则在
 * TouchRouter / KeyHolds，这里只画：{@link #setKeyState}）。键位和分裂时怎么分见 {@link KeyRows}。
 *
 * 分裂（{@link #setSplit}，由 KeyboardScreen 按布局设置和键盘区的宽高定）时面板从左半的左边到右半的右边，默认两半各占三分之一宽，
 * 中间这一截面板不画东西，触控板由 KeyboardScreen 摆在那里；触控板藏起来 / 放在别处时两半往中间伸、只隔一条缝。
 * 用户调过分裂的大小时两半照调的宽度、中间隔多宽就多宽（KeyboardPlan）。两半各报一块键盘范围，缝只在各自那一半里分。
 *
 * 平时触摸由服务处理（按键的位置判断按到了哪个键）；服务没接管、也没走本地分流时，面板整块自己收触摸
 * （{@link #onTouchEvent}，按手指编号分给键）并转发给服务。
 * 键与键之间的缝、第二行两头的空位、外圈边距不是死区：分给最近的键（规则只有一份，TouchRouter.nearestKey；
 * 分流时由键盘范围 REGION_KEYBOARD 触发，面板自己收触摸时由 {@link KeyFingers#pick} 调它）。
 * 所有键帽 view 都是面板的直接子 view，建一次缓存着（三层、两种布局都是），换层、分裂 / 合拢只切显示、重新摆放。
 * 键帽分主次（主题的功能键颜色）：字母、数字、空格、标点是普通键，其余（修饰键、⌫ ↵、F 键、?123、快捷行……）是功能键，见 KeyRows.functionKey；
 * 功能行最右的 ⋯ 另画成强调色的底 + 反差字、不带边框（Style.menuKeyLook，2026-11-01）。
 *
 * 键盘页上的两块面板之一（豆花布局；另一块是实体键盘 FixedKeyboardView，第 1B 批），对外的共同方法在 KeyboardFace，
 * 切到实体键盘再切回来这块面板不销毁（层、键帽缓存都留着），设置一个都不写。
 *
 * 小红点状态（KeyboardScreen 量尺寸时设，2026-11-01）：底行、快捷行在中间让出空位给三个鼠标键（{@link #setHole}，键位见 KeyRows.place），
 * 红点嵌在 g、h、v、b 交界时挨着的键帽被和红点同心的圆切掉一块（{@link #setStick}）：底色、按下、高亮、边框都沿着圆弧画（Style.keyLook 的 cut），
 * 字碰到切口的键帽把字竖着挪开（上下内边距，KeyboardPlan.labelShift）。
 */
final class KeyboardPanel extends ViewGroup implements KeyboardFace {
    interface Listener {
        /**
         * 服务没接管时，键被按下 / 松开。key 同 Proto.REGION_KEY 的 id（含合并键），action = Proto.KEY_DOWN / KEY_UP / KEY_CANCEL
         * （被取消的手指：ACTION_CANCEL、服务接管时 releaseTouches、换键时消失了的键——修饰键不锁，Caps、合并键什么都不出）。
         */
        void onLocalKey(int key, int action);

        void onKeyHaptic(int id, boolean special);

        /** 服务没接管时，非 HID 的特殊键（符 / Fn / 🌐 / ⋯ / 快捷行里的非键盘动作）被点了。 */
        void onSpecialKey(int id);
    }

    /** 按下字符键时的放大提示，由外面的容器画在键盘上方（键盘自己画不出自己的边界）。 */
    interface PreviewHost {
        void showKeyPreview(View key, String label);

        /** 只在 key 正是当前放大的那个键时收起（同时按着几个键，显示最后按下的）。 */
        void hideKeyPreview(View key);

        /**
         * 实体键盘（FixedKeyboardView 整块自己画，键帽不是 view）：按面板 panel 里的矩形定位，token 认是哪个键（收起时比它）。
         */
        void showKeyPreviewAt(View panel, int left, int top, int right, int bottom, String label, Object token);

        /** 只在 token 正是当前放大的那个键时收起。 */
        void hideKeyPreviewToken(Object token);
    }

    static final int SYMBOLS_BUTTON_ID = KeyRows.SYMBOLS_BUTTON_ID;
    static final int FN_BUTTON_ID = KeyRows.FN_BUTTON_ID;
    static final int GLOBE_BUTTON_ID = KeyRows.GLOBE_BUTTON_ID;
    static final int MENU_BUTTON_ID = KeyRows.MENU_BUTTON_ID;
    /** 快捷行里不是键盘组合键的动作（返回、键盘界面……）：按钮编号 = 这个 + 位置。 */
    static final int ROW_ACTION_BASE_ID = 600;

    /** 把彩色 emoji 画成灰的：去掉饱和度，再按当前主题映射到键帽的色调（纯黑主题就是乘 0.75 的灰，见 Theme.emojiMap）。 */
    static ColorMatrixColorFilter grayEmoji() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0);
        float[] k = Style.theme().emojiMap();
        m.postConcat(new ColorMatrix(new float[]{
                k[0], 0, 0, 0, k[3],
                0, k[1], 0, 0, k[4],
                0, 0, k[2], 0, k[5],
                0, 0, 0, 1, 0}));
        return new ColorMatrixColorFilter(m);
    }

    /** 一个键和它的 view，外加上次画上去的样子（没变就不重画，见 {@link #look}）和摆放的位置。 */
    private static final class Cap {
        final Key key;
        final TextView view;
        /** 功能键（KeyRows.functionKey）：用主题的功能键颜色。 */
        final boolean fn;
        /** 上次 Style.keyLook 画的 (高亮 << 1) | 按下；-1 = 还没画过（或者切口变了，要重画）。 */
        int look = -1;
        boolean pressed;
        /** onMeasure 算好的位置（面板坐标），onLayout 照着摆。 */
        int left, top, right, bottom;
        /** 被小红点切掉的那块：{圆心 x, 圆心 y, 半径}（键帽自己的坐标）；没被切到是 null。 */
        float[] cut;
        /** 带角上小字的键（合并键）按着 0.4 秒把放大提示换成角上那个字（{@link #preview}）。 */
        Runnable cornerLater;

        Cap(Key key, TextView view, boolean fn) {
            this.key = key;
            this.view = view;
            this.fn = fn;
        }
    }

    private final Listener listener;
    private PreviewHost previewHost;
    private final int gap;
    /** 快捷行（设置里可改）。 */
    private Key[] shortcuts = new Key[0];
    private int layer = KeyRows.LAYER_LETTERS;
    /** 修饰键的状态（MSG_MODIFIERS：锁住 / 按着 / 快锁了）、豆花这边显示的大写锁定，见 {@link #setKeyState}。 */
    private int latched, held, armed;
    private boolean capsOn;
    private boolean split;
    /** 功能行最右边带 ⋯ 键（小触控板没摆出来时，见 {@link #setMenuKey}）。 */
    private boolean menuKey;
    /** 分裂时两半中间那截（KeyRows.leftEnd 的 middle）：MIDDLE_PAD = 三分之一布局（中间放触控板），否则两半中间隔这么宽。 */
    private int middle = KeyRows.MIDDLE_PAD;
    /** 分裂时两半只隔一条缝：两块键盘范围各伸到缝的中线；不然（中间有触控板、或者用户把两半拉开了）只到各自那一半的边。 */
    private boolean seam;
    /** 底行 + 快捷行（换快捷行、换主机时重建）。 */
    private KeyRows.Row[] bottomRows = new KeyRows.Row[0];
    /**
     * 每个键（Key 对象）的键帽：功能行和三层的中间四行建一次缓存着（重建几十个自动缩字号的 TextView 会让主线程停一下，
     * 蓝牙模式下报文也从主线程发）；底行和快捷行的在 {@link #bottomCaps} 里，重建时换掉。没显示的是 GONE。
     */
    private final Map<Key, Cap> capOf = new IdentityHashMap<>();
    private final List<Cap> bottomCaps = new ArrayList<>();
    /** 当前显示的键（不含空位），顺序同报给服务的区域：功能行、中间四行、底行、快捷行，分裂时每行先左半后右半。 */
    private final List<Cap> caps = new ArrayList<>();
    /** 键自己收触摸时每根手指按的键（caps 的下标）。 */
    private final KeyFingers fingers = new KeyFingers();
    /** 小红点按得到的范围（面板坐标的正方形，按它的内切圆算）、键上切口的半径（0 = 没嵌在键里），见 {@link #setStick}。 */
    private int[] stickHit;
    private float stickCut;
    /** 小红点状态下底行、快捷行让出的空位 {左, 右}（面板 x）、快捷行也让不让（spread），见 {@link #setHole}。 */
    private int[] hole;
    private boolean spread;

    KeyboardPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.gap = Style.dp(context, 2);
        addCaps(KeyRows.TOP_ROWS_MENU, null);   // 带 ⋯ 的那份是不带的加一个键，两种功能行的键帽都在这里建
        for (KeyRows.Row[] rows : KeyRows.LAYERS) addCaps(rows, null);
        rebuildBottom();
    }

    @Override
    public void setPreviewHost(PreviewHost host) {
        previewHost = host;
    }

    /**
     * 换快捷行（设置页改完回来时调用）。键盘组合键直接当键（按住会连发）；其它动作当特殊键，
     * 按钮编号 ROW_ACTION_BASE_ID + 位置，由宿主执行。
     */
    void setShortcuts(List<Actions.Action> list) {
        shortcutActions = new ArrayList<>(list);
        List<Key> row = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Actions.Action a = list.get(i);
            if (a.kind == Actions.Kind.NONE) continue;
            String label = a.shortLabel(getContext());
            if (a.kind == Actions.Kind.HID) row.add(new Key(a.hidCode(apple), label, null, 1, false));
            else row.add(new Key(ROW_ACTION_BASE_ID + i, label, null, 1, true));
        }
        shortcuts = row.toArray(new Key[0]);
        rebuildBottom();
    }

    /**
     * 修饰键和 Caps 的显示（KeyboardFace.setKeyState）：锁住的、"现在松手就锁"的修饰键和开着的 Caps 高亮；
     * 锁住或按着 Shift 时显示大写和上档符号，Caps 开着时字母按 KeyRows.capText 换大小写。
     */
    @Override
    public void setKeyState(int latched, int held, int armed, int fn, boolean caps) {   // fn：豆花布局没有实体键盘的 Fn 键，不看
        if (latched == this.latched && held == this.held && armed == this.armed && caps == capsOn) return;
        // 按着的修饰键松开了：分流照 KeyHolds 的规则先把待定的合并键当点一下结算掉，提示不再换成角上那个字
        if ((this.held & ~held) != 0) settleCorners(-1);
        this.latched = latched;
        this.held = held;
        this.armed = armed;
        capsOn = caps;
        refreshLabels();
    }

    /** "符" / "Fn" 被点了。按着的功能行修饰键 / Caps、底行的合并键 / 空格在各层是同一个键，照旧按着（{@link #changeKeys}）。 */
    void onLayerButton(int id) {
        int target = id == FN_BUTTON_ID ? KeyRows.LAYER_FN : KeyRows.LAYER_SYMBOLS;
        changeKeys(() -> {
            layer = layer == target ? KeyRows.LAYER_LETTERS : target;
            showLayer();
        });
    }

    /**
     * 分裂成左右两半 / 合成一整块，分裂时两半中间留多宽（KeyboardScreen 量尺寸时按布局设置、键盘区的宽高定，见 KeyboardPlan）。
     * 键的位置全变了：换显示的键（分裂时多一个空格），面板自己收的手指按着的键还显示着就照旧按着、消失了的当取消松开（{@link #changeKeys}）。
     * 只是中间宽度变了（触控板显示 / 隐藏、调整模式里拖两半）也一样：两半的键都挪了位置。
     * seam = 两半只隔一条缝（KeyboardPlan.Plan.seam，决定键盘范围伸不伸到缝的中线）。
     * 在 KeyboardScreen.onMeasure 里、量这个面板之前调：这里改子 view 的显示只会让接着的这次测量把它们算进去。
     */
    void setSplit(boolean on, int middle, boolean seam) {
        int m = on ? middle : KeyRows.MIDDLE_PAD;
        boolean sm = on && seam;
        if (on == split && m == this.middle && sm == this.seam) return;
        changeKeys(() -> {
            split = on;
            this.middle = m;
            this.seam = sm;
            showLayer();
        });
    }

    boolean isSplit() {
        return split;
    }

    /**
     * 功能行最右边带不带 ⋯ 键（KeyboardScreen 量尺寸时定：小触控板摆出来、角上放得下 ⋯ 小方块就不带，不然带）。
     * 功能行的键都挪了位置：同 {@link #setSplit}（按着的键还显示着就照旧按着），在量这个面板之前调。
     */
    @Override
    public void setMenuKey(boolean on) {
        if (on == menuKey) return;
        changeKeys(() -> {
            menuKey = on;
            showLayer();
        });
    }

    /**
     * 小红点状态下底行、快捷行在中间让出的空位 {左, 右}（面板 x，KeyboardPlan.Plan.hole；null = 不让）：底行的空格缩短、
     * spread（KeyboardPlan.Plan.spread）时快捷行的键也分到两边，不然快捷行照旧铺满（KeyRows.place），空位里放三个鼠标键（KeyboardScreen 摆，叠在面板上面）。
     * 键的位置变了：同 {@link #setSplit}（按着的键还显示着就照旧按着），在量这个面板之前调。
     */
    @Override
    public void setHole(int[] hole, boolean spread) {
        boolean sp = hole != null && spread;
        if (java.util.Arrays.equals(hole, this.hole) && sp == this.spread) return;
        int[] h = hole == null ? null : hole.clone();
        changeKeys(() -> {
            this.hole = h;
            this.spread = sp;
            showLayer();
        });
    }

    /** ⋯ 键摆在哪（面板坐标 {左, 上, 右, 下}，量过尺寸之后才对）；功能行没带它时返回 null。 */
    @Override
    public int[] menuKeyRect() {
        if (!menuKey) return null;
        for (Cap c : caps) {
            if (c.key.id == MENU_BUTTON_ID) return new int[]{c.left, c.top, c.right, c.bottom};
        }
        return null;
    }

    /**
     * 把每个键在屏幕上的位置报给服务，最后是键盘范围（REGION_KEYBOARD）：按在缝、空位、边距上时分给这块范围里最近的键。
     * 整块时范围就是面板自己；分裂时左右两半各一块（中间的触控板不在任何一块里，缝只在各自那一半里分）。
     * 键盘外面的空白、小触控板和它角上的 ⋯ 都不在范围里。只报当前这一层（藏起来的层不在 caps 里）。
     */
    @Override
    public void collectRegions(List<int[]> regions) {
        int[] loc = new int[2];
        for (Cap c : caps) {
            TextView v = c.view;
            v.getLocationOnScreen(loc);
            regions.add(new int[]{c.key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, c.key.id,
                    loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()});
        }
        getLocationOnScreen(loc);
        for (int[] a : areas()) {
            regions.add(new int[]{Proto.REGION_KEYBOARD, 0, loc[0] + a[0], loc[1] + a[1], loc[0] + a[2], loc[1] + a[3]});
        }
    }

    /**
     * 键盘范围（面板坐标 {左, 上, 右, 下}）：整块时一块，分裂时左右两半各一块。分裂时每半只到这一半最后一行有键的
     * 那一行下沿（快捷行只有 1 个键时右半那一行没有键，那块不算键盘，免得被分给上一行的空格 / ↵）。
     * 两半只隔一条缝时各伸到缝的中线；中间放触控板、或者两半被拉开了时两块范围只到各自那一半的边，中间那截什么都不是。
     * 分流（collectRegions）和面板自己收触摸（keyAt）用的是这同一份。
     */
    private int[][] areas() {
        int w = getWidth(), h = getHeight();
        if (!split) return new int[][]{{0, 0, w, h}};
        KeyRows.Row[] rows = rows();
        // 两半只隔一条缝：两块范围各伸到缝的中线（KeyRows.areaEnd / areaStart）；拉开了的只到各自那一半的边
        int end = seam ? KeyRows.areaEnd(w, middle) : KeyRows.leftEnd(w, middle);
        int start = seam ? KeyRows.areaStart(w, middle) : KeyRows.rightStart(w, middle);
        return new int[][]{{0, 0, end, KeyRows.halfBottom(rows, h, false)}, {start, 0, w, KeyRows.halfBottom(rows, h, true)}};
    }

    /** 画按下的样子。id 同 Key.id（分裂时的两个空格同一个 id，服务转来的按下两个一起画）。 */
    @Override
    public void showPressed(int id, boolean pressed) {
        for (Cap c : caps) {
            if (c.key.id == id) {
                look(c, pressed);
                preview(c, pressed);
            }
        }
    }

    /**
     * 服务刚接管触摸屏：系统再也收不到面板上正按着的手指的抬起了，面板自己按下的键先松开（不算点击），
     * 画回松开的样子、收起放大提示。不松的话服务的虚拟键盘一直按着，系统里不停连发。
     */
    @Override
    public void releaseTouches() {
        dropTouches();
    }

    /**
     * 换了主题 / 按键边框开关：所有键帽（含藏着的层）作废上次画的样子，当前显示的马上按新主题重画，藏着的等显示时再画；🌐 重新去色。
     */
    @Override
    public void applyTheme() {
        for (Cap c : capOf.values()) {
            c.look = -1;
            if (c.key.id == GLOBE_BUTTON_ID) {
                c.view.getPaint().setColorFilter(grayEmoji());
                c.view.invalidate();
            }
        }
        refreshLabels();
    }

    /** 所有键松开的样子、收起放大提示（服务断了，它转来的按下等不到松开）。 */
    @Override
    public void clearPressed() {
        for (Cap c : caps) {
            look(c, false);
            preview(c, false);
        }
    }

    // ═══════════ 内部 ═══════════

    /**
     * 连着苹果设备：修饰键按苹果的写法标（Ctrl ⌃、Alt ⌥、GUI ⌘，GUI 键平时叫 Super），
     * 快捷行里的内置快捷键按苹果的习惯发（复制 = ⌘C……，见 Actions.Action.hidCode）。
     */
    private boolean apple;
    private List<Actions.Action> shortcutActions = new ArrayList<>();

    void setAppleHost(boolean apple) {
        if (apple == this.apple) return;
        this.apple = apple;
        setShortcuts(shortcutActions);   // 重建底行和快捷行（键的编号跟着变），区域会随重新布局报上去
    }

    /** 重建底行和快捷行（换主机、换快捷行）；功能行和中间三层不动（按着的照旧按着），底行、快捷行的键帽换了新的，按着的当取消松开。 */
    private void rebuildBottom() {
        changeKeys(() -> {
            for (Cap c : bottomCaps) {
                removeView(c.view);
                capOf.remove(c.key);
            }
            bottomCaps.clear();
            bottomRows = KeyRows.bottomRows(apple, shortcuts);
            addCaps(bottomRows, bottomCaps);
            showLayer();
        });
    }

    /**
     * 给 rows 里（整块、分裂、小红点让位三种布局，KeyRows.capKeys）还没有键帽的键建键帽（先藏着），记进 list（可以是 null）。
     * 小红点状态的底行（before / after，含缩短的空格）以前漏了，showLayer 拿到 null 就闪退。
     */
    private void addCaps(KeyRows.Row[] rows, List<Cap> list) {
        for (Key key : KeyRows.capKeys(rows)) {
            if (capOf.containsKey(key)) continue;
            // 一个字（含 🌐 这种占两个 char 的）用大字，多个字的键名用小字
            boolean oneChar = key.label.codePointCount(0, key.label.length()) <= 1;
            TextView v = Style.keyButton(getContext(), oneChar ? 18 : 13, key.corner);   // 合并键右上角另画一个小字
            if (key.id == GLOBE_BUTTON_ID) v.getPaint().setColorFilter(grayEmoji());   // 🌐 是彩色 emoji，去色调暗，和别的键一个色调
            v.setVisibility(GONE);
            addView(v);
            Cap c = new Cap(key, v, KeyRows.functionKey(key, isShortcut(key)));
            capOf.put(key, c);
            if (list != null) list.add(c);
        }
    }

    /** 是不是快捷行里的键（快捷行的键一律当功能键上色）。 */
    private boolean isShortcut(Key key) {
        for (Key s : shortcuts) if (s == key) return true;
        return false;
    }

    /** 当前层、当前布局下的所有行：功能行、中间四行、底行（+ 快捷行）。 */
    private KeyRows.Row[] rows() {
        return KeyRows.rows(menuKey, layer, bottomRows);
    }

    /**
     * 三层各自的全部行（功能行按 menu 带不带 ⋯、底行和快捷行用现在的）：KeyboardScreen 量尺寸时交给 KeyboardPlan，
     * 算小红点嵌在键里能多大（每一层的键都不能被占掉太多）。
     */
    KeyRows.Row[][] layers(boolean menu) {
        return KeyRows.layers(menu, bottomRows);
    }

    /**
     * 小红点嵌在键里（KeyboardScreen 量尺寸时设，在量这个面板之前）：hit = 按得到的范围（面板坐标的正方形 {左, 上, 右, 下}，按它的内切圆算），
     * cut = 键上切口的半径（KeyboardPlan.Plan.cut，圆心同 hit 的中心）；没嵌在键里时 hit 是 null。
     * 碰到切口的键帽沿着圆弧画、字挪开（{@link #onMeasure}）；面板自己收触摸时，按在红点圆里的手指不算任何键（{@link KeyFingers#pick} 的 holes）——
     * 红点的 view 虽然叠在面板上面，嵌在键里时一根手指都不收（MainActivity），哪根手指归红点圆、哪根归被切了角的键全在这里定，
     * 规则和分流一样（分流时红点的区域排在键前面、按内切圆算）。
     */
    @Override
    public void setStick(int[] hit, float cut) {
        int[] h = hit == null ? null : hit.clone();
        float c = h == null ? 0 : cut;
        if (java.util.Arrays.equals(h, stickHit) && c == stickCut) return;
        stickHit = h;
        stickCut = c;
        requestLayout();
    }

    /**
     * 显示当前层、当前布局：这时该显示的键 VISIBLE，别的 GONE（不占地方、不量），当前显示的键换成这些，键帽和高亮跟上。
     * 显示的键没变的子 view 不动（setVisibility 同值什么都不做）。
     */
    private void showLayer() {
        caps.clear();
        for (Key key : KeyRows.keys(rows(), split, holeOn())) caps.add(capOf.get(key));
        IdentityHashMap<Cap, Boolean> shown = new IdentityHashMap<>();
        for (Cap c : caps) shown.put(c, Boolean.TRUE);
        for (Cap c : capOf.values()) c.view.setVisibility(shown.containsKey(c) ? VISIBLE : GONE);
        refreshLabels();
        requestLayout();
    }

    /**
     * 服务刚接管（releaseTouches）：面板自己收的手指按着的键全部当取消松开；所有键画回松开的样子、收起放大提示。
     */
    private void dropTouches() {
        releaseFingers();
        for (Cap c : caps) {
            look(c, false);
            preview(c, false);
        }
    }

    /**
     * 换键（换层、分裂 / 合拢、⋯ 键、让位、重建底行，2026-11-01 起）：change 里改状态、换显示的键（showLayer），之后按 Key 对象（Cap）
     * 把面板自己收的手指重新对上（KeyFingers.remap）：还显示着的键（功能行的修饰键 / Caps、底行的合并键 / 空格在各层是同一个 Key 对象）
     * 照旧按着——按住功能行的 Shift 点 ?123 再按符号、按住合并键时滚着点了 ?123，Shift 不松、逗号不丢；只有真消失的键当取消松开（KEY_CANCEL）。
     * 以前一律当取消松开，新规则下修饰键松手就算数，换层会把按着的 Shift / Ctrl 松掉。
     * 样子：消失了的键帽画回松开；还显示着的保持按着的样子（含服务转来的按下，它们的松开照常会到）；放大提示都收掉（位置可能变了）。
     */
    private void changeKeys(Runnable change) {
        List<Cap> old = new ArrayList<>(caps);
        for (Cap c : old) preview(c, false);
        change.run();
        IdentityHashMap<Cap, Integer> now = new IdentityHashMap<>();
        for (int i = 0; i < caps.size(); i++) now.put(caps.get(i), i);
        int[] map = new int[old.size()];
        for (int i = 0; i < map.length; i++) {
            Integer j = now.get(old.get(i));
            map[i] = j == null ? -1 : j;
        }
        for (int o : fingers.remap(map)) release(old.get(o), false);
        for (Cap c : old) if (!now.containsKey(c)) look(c, false);
    }

    // ═══════════ 摆放 ═══════════

    /** 底行、快捷行让出了空位（整块才有）。 */
    private boolean holeOn() {
        return hole != null && !split;
    }

    /**
     * 每个键照 KeyRows.place 摆；红点嵌在键里时，碰到切口的键帽记下切口（键帽坐标，Style.keyLook 沿着圆弧画），
     * 字照 KeyboardPlan.labelShift 竖着挪开：往上挪 d 就在下面加 2d 的内边距（往下挪反过来），居中的字跟着挪 d。
     * 切口变了的键帽量完重画一次（setBackground、setPadding 量的时候改：同样的最小尺寸、只是这个子 view 要重新量，不会再往上请求布局）。
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int[] s = stickHit;
        boolean cutOn = s != null && stickCut > 0 && !split;
        float cx = cutOn ? (s[0] + s[2]) / 2f : 0, cy = cutOn ? (s[1] + s[3]) / 2f : 0;
        float room = Style.sp(getContext(), KeyboardScreen.LABEL_ROOM_SP);
        for (KeyRows.Placed p : KeyRows.place(rows(), w, h, split, middle, gap, holeOn() ? hole : null, spread)) {
            Cap c = capOf.get(p.key);
            c.left = p.left;
            c.top = p.top;
            c.right = Math.max(p.left, p.right);
            c.bottom = Math.max(p.top, p.bottom);
            float[] cut = null;
            int shift = 0;
            if (cutOn && KeyboardPlan.cuts(p, cx, cy, stickCut)) {
                cut = new float[]{cx - c.left, cy - c.top, stickCut};
                float d = KeyboardPlan.labelShift(p, cx, cy, stickCut, room);
                if (!Float.isNaN(d)) shift = d < 0 ? -(int) Math.ceil(-d) : (int) Math.ceil(d);   // 往远处取整：宁可多挪一点
            }
            if (!java.util.Arrays.equals(cut, c.cut)) {
                c.cut = cut;
                c.look = -1;
            }
            int pt = shift > 0 ? 2 * shift : 0, pb = shift < 0 ? -2 * shift : 0;
            TextView v = c.view;
            if (v.getPaddingTop() != pt || v.getPaddingBottom() != pb) v.setPadding(v.getPaddingLeft(), pt, v.getPaddingRight(), pb);
            v.measure(MeasureSpec.makeMeasureSpec(c.right - c.left, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(c.bottom - c.top, MeasureSpec.EXACTLY));
        }
        for (Cap c : caps) if (c.look == -1) look(c, c.pressed);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (Cap c : caps) c.view.layout(c.left, c.top, c.right, c.bottom);
    }

    /** 键帽文字和高亮：只改文字真变了的键（开着自动缩字号，setText 要重新排版、试字号），样子没变的键不重画。 */
    private void refreshLabels() {
        for (Cap c : caps) {
            String label = label(c.key);
            if (!label.contentEquals(c.view.getText())) c.view.setText(label);
            look(c, c.pressed);
        }
    }

    private String label(Key key) {
        // 锁住或按着 Shift 换上档字、字母再看 Caps（连苹果设备时 Shift ∨ Caps，别的主机 Shift ⊕ Caps）
        String label = KeyRows.capText(key, latched | held, capsOn, apple);
        // 跟着系统语言的键名
        if (key.id == 0x2C) label = getContext().getString(R.string.key_space);
        if (key.id == 0xE0) label = apple ? "⌃" : "Ctrl";
        if (key.id == 0xE2) label = apple ? "⌥" : "Alt";
        if (key.id == 0xE3) label = apple ? "⌘" : "Super";
        if (key.id == SYMBOLS_BUTTON_ID) {
            label = layer == KeyRows.LAYER_SYMBOLS ? "ABC" : getContext().getString(R.string.key_symbols);
        }
        return label;
    }

    /**
     * 字符键按下时放大显示（看清按到了哪个），松开收起。合并键（角上有小字）按着 0.4 秒（KeyHolds.HOLD_MS）把提示换成角上那个字：
     * 只是样子，真正出什么字由分流定（差几毫秒无所谓）。
     */
    private void preview(Cap c, boolean pressed) {
        if (previewHost == null) return;
        if (c.cornerLater != null) removeCallbacks(c.cornerLater);
        if (!pressed) {
            previewHost.hideKeyPreview(c.view);
        } else if (isCharKey(c)) {
            previewHost.showKeyPreview(c.view, c.view.getText().toString());
            if (c.key.corner != null) {
                if (c.cornerLater == null) {
                    c.cornerLater = () -> {
                        if (previewHost != null && c.pressed) previewHost.showKeyPreview(c.view, c.key.corner);
                    };
                }
                postDelayed(c.cornerLater, KeyHolds.HOLD_MS);
            }
        }
    }

    /**
     * 合并键按着、还没到 0.4 秒时分流把它结算了（KeyHolds：别的键按下、按着的修饰键松开之前先当点一下发出逗号，之后按多久都不再出句号）：
     * 放大提示照旧是逗号，不再到点换成角上那个字。except = 这次按下的键（合并键自己按下不算）；-1 = 都撤。
     * 只照面板看得到的两件事撤（别的键按下、按着的修饰键少了一位）：开合开始时分流也结算，App 看不到，那时提示照样会换（只是样子）；
     * 被取消的修饰键松开分流不结算，这里分不出来、照样撤（手掌压在 Shift 上这类，少见）。
     */
    private void settleCorners(int except) {
        for (Cap c : capOf.values()) {
            if (c.cornerLater != null && c.key.id != except) removeCallbacks(c.cornerLater);
        }
    }

    /** 服务 / 本地分流报来别的键按下了（MainActivity.onButton 键那一支，按键本身分流已经发了）：见 {@link #settleCorners}。 */
    void otherKeyDown(int id) {
        settleCorners(id);
    }

    /** 字符键：键帽只有一个字的字母、数字、符号。修饰键、⇧ ⌫ ↵、空格、F 键、快捷行不放大。 */
    private boolean isCharKey(Cap c) {
        Key key = c.key;
        if (key.special || isShortcut(key)) return false;
        String t = c.view.getText().toString();
        return t.length() == 1 && "⇧⌫↵".indexOf(t) < 0;
    }

    /**
     * 高亮（换层键、锁住的和"现在松手就锁"的修饰键、开着的 Caps，KeyRows.lit）和按下的样子，功能键用主题的功能键颜色；
     * 和上次画的一样就不动（Style.keyLook 每次都新建背景）。
     */
    private void look(Cap c, boolean pressed) {
        int id = c.key.id;
        boolean active = (id == SYMBOLS_BUTTON_ID && layer == KeyRows.LAYER_SYMBOLS)
                || (id == FN_BUTTON_ID && layer == KeyRows.LAYER_FN)
                || KeyRows.lit(c.key, latched | armed, capsOn);
        c.pressed = pressed;
        int state = (active ? 2 : 0) | (pressed ? 1 : 0);
        if (state == c.look) return;
        c.look = state;
        // ⋯ 键：强调色的底 + 反差字、不画边框（KeyRows.menuKey；它是特殊键，从不高亮）
        if (KeyRows.menuKey(id)) Style.menuKeyLook(c.view, pressed, c.cut);
        else Style.keyLook(c.view, c.fn, active, pressed, c.cut);
    }

    // ═══════════ 键自己收触摸（服务没接管、没走本地分流时） ═══════════

    /** 面板整块收触摸，不让各键自己收：按手指编号分给键（{@link KeyFingers}），多指打字、缝里的手指都按同一条规则。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return true;
    }

    /**
     * 每根手指按下时定键（包含的键优先，缝、空位、边距分给这一半里最近的键，规则同分流 TouchRouter.nearestKey），
     * 抬起时松开它自己那个键；ACTION_CANCEL 全部松开。分裂时按在中间（触控板那一截）的手指、按在嵌着的小红点上的手指、
     * 按在让给三个鼠标键的空位上的手指不算任何键。
     * 以前各键自己收触摸：第一根手指按在缝里时，同一次手势里后来的手指全被吞掉、缝里那个键要等所有手指抬起才松开；
     * 第一根按在键 A、第二根按在缝里时，A 收到的是"多指中的一根抬起"，被忽略，A 一直按着。
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int index = keyAt(e.getX(i), e.getY(i));
                if (index < 0) break;   // 不在键盘范围里（分裂时的中间、多指时派到这里的别处手指）
                int old = fingers.down(e.getPointerId(i), index);
                if (old != KeyFingers.NONE) release(caps.get(old), false);
                press(caps.get(index));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = fingers.up(e.getPointerId(e.getActionIndex()));
                if (index != KeyFingers.NONE) release(caps.get(index), true);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseFingers();
                break;
            default:
                break;
        }
        return true;
    }

    /** 面板自己收的手指按着的键全部松开（不算点击，KEY_CANCEL）。 */
    private void releaseFingers() {
        for (int index : fingers.cancel()) release(caps.get(index), false);
    }

    /** 面板坐标 (x, y) 该算哪个键（caps 的下标），范围外 / 没有键返回 -1。 */
    private int keyAt(float x, float y) {
        TouchRouter.Region[] rs = new TouchRouter.Region[caps.size()];
        for (int i = 0; i < rs.length; i++) {
            View v = caps.get(i).view;   // 键帽是面板的直接子 view，位置就是面板坐标
            // 编号直接用下标（只在这里用，不是 Key.id）
            rs[i] = new TouchRouter.Region(caps.get(i).key.special ? Proto.REGION_BUTTON : Proto.REGION_KEY, i,
                    v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
        }
        int[][] as = areas();
        TouchRouter.Region[] areas = new TouchRouter.Region[as.length];
        for (int i = 0; i < as.length; i++) {
            areas[i] = new TouchRouter.Region(Proto.REGION_KEYBOARD, 0, as[i][0], as[i][1], as[i][2], as[i][3]);
        }
        // 洞：嵌着的红点（REGION_STICK 按内切圆算；红点的 view 不收手指，就靠这里）、让给三个鼠标键的空位（平时鼠标键的 view 铺满空位、先收到这些手指，这里是兜底）
        List<TouchRouter.Region> holes = new ArrayList<>();
        int[] h = stickHit;
        if (h != null && stickCut > 0) holes.add(new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, h[0], h[1], h[2], h[3]));
        if (holeOn()) {
            KeyRows.Row[] rows = rows();
            int top = KeyRows.holeTop(rows, getHeight()), bottom = KeyRows.holeBottom(rows, getHeight(), spread);
            if (top >= 0) holes.add(new TouchRouter.Region(Proto.REGION_MOUSE_BUTTON, 0, hole[0], top, hole[1], bottom));
        }
        TouchRouter.Region r = KeyFingers.pick(rs, areas, holes.toArray(new TouchRouter.Region[0]), x, y);
        return r == null ? -1 : r.id;
    }

    /** 先发键，再震动、画按下的样子（震动是一次 binder 调用，画背景也要时间，别让报文等它们）。 */
    private void press(Cap c) {
        if (!c.key.special) {
            listener.onLocalKey(c.key.id, Proto.KEY_DOWN);
            settleCorners(c.key.id);   // 分流先把待定的合并键结算掉了（出逗号）
        }
        listener.onKeyHaptic(c.key.id, c.key.special);
        look(c, true);
        preview(c, true);
    }

    /**
     * tap = 手指正常抬起（特殊键这时才算点了；键发 KEY_UP）；被取消、被顶掉、换键时消失了的发 KEY_CANCEL（修饰键松开但不锁，
     * Caps、合并键什么都不出，不然取消一下就发 Caps / 逗号）。按 Cap 不按下标：换键以后下标就变了。
     */
    private void release(Cap c, boolean tap) {
        if (!c.key.special) {
            listener.onLocalKey(c.key.id, tap ? Proto.KEY_UP : Proto.KEY_CANCEL);
        } else if (tap) {
            post(() -> listener.onSpecialKey(c.key.id));
        }
        look(c, false);
        preview(c, false);
    }
}
