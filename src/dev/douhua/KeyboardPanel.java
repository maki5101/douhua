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
 * 修饰键点一下锁定（高亮），打完下一个键自动解开。键位和分裂时怎么分见 {@link KeyRows}。
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
 * 键帽分主次（主题的功能键颜色）：字母、数字、空格、标点是普通键，其余（修饰键、⌫ ↵、F 键、?123、快捷行……）是功能键，见 KeyRows.functionKey。
 */
final class KeyboardPanel extends ViewGroup {
    interface Listener {
        /** 服务没接管时，键被按下 / 松开。key 同 Proto.REGION_KEY 的 id。 */
        void onLocalKey(int key, boolean down);

        void onKeyHaptic(int id, boolean special);

        /** 服务没接管时，非 HID 的特殊键（符 / Fn / 🌐 / ⋯ / 快捷行里的非键盘动作）被点了。 */
        void onSpecialKey(int id);
    }

    /** 按下字符键时的放大提示，由外面的容器画在键盘上方（键盘自己画不出自己的边界）。 */
    interface PreviewHost {
        void showKeyPreview(View key, String label);

        /** 只在 key 正是当前放大的那个键时收起（同时按着几个键，显示最后按下的）。 */
        void hideKeyPreview(View key);
    }

    static final int SYMBOLS_BUTTON_ID = KeyRows.SYMBOLS_BUTTON_ID;
    static final int FN_BUTTON_ID = KeyRows.FN_BUTTON_ID;
    static final int GLOBE_BUTTON_ID = KeyRows.GLOBE_BUTTON_ID;
    static final int MENU_BUTTON_ID = KeyRows.MENU_BUTTON_ID;
    /** 快捷行里不是键盘组合键的动作（返回、键盘界面……）：按钮编号 = 这个 + 位置。 */
    static final int ROW_ACTION_BASE_ID = 600;

    /** 把彩色 emoji 画成灰的：去掉饱和度，再按当前主题映射到键帽的色调（纯黑主题就是乘 0.75 的灰，见 Theme.emojiMap）。 */
    private static ColorMatrixColorFilter grayEmoji() {
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
        /** 上次 Style.keyLook 画的 (高亮 << 1) | 按下；-1 = 还没画过。 */
        int look = -1;
        boolean pressed;
        /** onMeasure 算好的位置（面板坐标），onLayout 照着摆。 */
        int left, top, right, bottom;

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
    private int modifiers;
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
    /** 小红点嵌在键里时按得到的范围（面板坐标），见 {@link #setStickHole}。 */
    private int[] stickHole;

    KeyboardPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.gap = Style.dp(context, 2);
        addCaps(KeyRows.TOP_ROWS_MENU, null);   // 带 ⋯ 的那份是不带的加一个键，两种功能行的键帽都在这里建
        for (KeyRows.Row[] rows : KeyRows.LAYERS) addCaps(rows, null);
        rebuildBottom();
    }

    void setPreviewHost(PreviewHost host) {
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

    /** 服务报来的锁定修饰键：高亮对应键，锁定 Shift 时显示大写和上档符号。 */
    void setModifiers(int mask) {
        modifiers = mask;
        refreshLabels();
    }

    /** "符" / "Fn" 被点了。 */
    void onLayerButton(int id) {
        int target = id == FN_BUTTON_ID ? KeyRows.LAYER_FN : KeyRows.LAYER_SYMBOLS;
        dropTouches();
        layer = layer == target ? KeyRows.LAYER_LETTERS : target;
        showLayer();
    }

    /**
     * 分裂成左右两半 / 合成一整块，分裂时两半中间留多宽（KeyboardScreen 量尺寸时按布局设置、键盘区的宽高定，见 KeyboardPlan）。
     * 键的位置全变了：面板自己收的手指按着的键先松开、按下的样子收掉，再换显示的键（分裂时多一个空格）。
     * 只是中间宽度变了（触控板显示 / 隐藏、调整模式里拖两半）也一样：两半的键都挪了位置。
     * seam = 两半只隔一条缝（KeyboardPlan.Plan.seam，决定键盘范围伸不伸到缝的中线）。
     * 在 KeyboardScreen.onMeasure 里、量这个面板之前调：这里改子 view 的显示只会让接着的这次测量把它们算进去。
     */
    void setSplit(boolean on, int middle, boolean seam) {
        int m = on ? middle : KeyRows.MIDDLE_PAD;
        boolean sm = on && seam;
        if (on == split && m == this.middle && sm == this.seam) return;
        dropTouches();
        split = on;
        this.middle = m;
        this.seam = sm;
        showLayer();
    }

    boolean isSplit() {
        return split;
    }

    /**
     * 功能行最右边带不带 ⋯ 键（KeyboardScreen 量尺寸时定：小触控板摆出来、角上放得下 ⋯ 小方块就不带，不然带）。
     * 功能行的键都挪了位置：同 {@link #setSplit}，先松开按着的键、收掉按下的样子，在量这个面板之前调。
     */
    void setMenuKey(boolean on) {
        if (on == menuKey) return;
        dropTouches();
        menuKey = on;
        showLayer();
    }

    /** ⋯ 键摆在哪（面板坐标 {左, 上, 右, 下}，量过尺寸之后才对）；功能行没带它时返回 null。 */
    int[] menuKeyRect() {
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
    void collectRegions(List<int[]> regions) {
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
    void showPressed(int id, boolean pressed) {
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
    void releaseTouches() {
        dropTouches();
    }

    /**
     * 换了主题 / 按键边框开关：所有键帽（含藏着的层）作废上次画的样子，当前显示的马上按新主题重画，藏着的等显示时再画；🌐 重新去色。
     */
    void applyTheme() {
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
    void clearPressed() {
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

    /** 重建底行和快捷行（换主机、换快捷行）；功能行和中间三层不动。 */
    private void rebuildBottom() {
        dropTouches();
        for (Cap c : bottomCaps) {
            removeView(c.view);
            capOf.remove(c.key);
        }
        bottomCaps.clear();
        bottomRows = KeyRows.bottomRows(apple, shortcuts);
        addCaps(bottomRows, bottomCaps);
        showLayer();
    }

    /** 给 rows 里（两种布局）还没有键帽的键建键帽（先藏着），记进 list（可以是 null）。 */
    private void addCaps(KeyRows.Row[] rows, List<Cap> list) {
        for (KeyRows.Row row : rows) {
            for (Key[] part : new Key[][]{row.whole, row.left, row.right}) {
                for (Key key : part) {
                    if (key.spacer() || capOf.containsKey(key)) continue;
                    // 一个字（含 🌐 这种占两个 char 的）用大字，多个字的键名用小字
                    boolean oneChar = key.label.codePointCount(0, key.label.length()) <= 1;
                    TextView v = Style.keyButton(getContext(), oneChar ? 18 : 13);
                    if (key.id == GLOBE_BUTTON_ID) v.getPaint().setColorFilter(grayEmoji());   // 🌐 是彩色 emoji，去色调暗，和别的键一个色调
                    v.setVisibility(GONE);
                    addView(v);
                    Cap c = new Cap(key, v, KeyRows.functionKey(key, isShortcut(key)));
                    capOf.put(key, c);
                    if (list != null) list.add(c);
                }
            }
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
     * 小红点嵌在键里时按得到的范围（面板坐标 {左, 上, 右, 下}；没嵌在键里是 null，KeyboardScreen 量尺寸时设）：
     * 面板自己收触摸时，按在这块里的手指不算任何键（{@link KeyFingers#pick} 的 holes）。平时红点的 view 叠在面板上面、先收到这些手指，
     * 这里是兜底，规则和分流一样（分流时红点的区域排在键前面）。
     */
    void setStickHole(int[] hole) {
        stickHole = hole == null ? null : hole.clone();
    }

    /**
     * 显示当前层、当前布局：这时该显示的键 VISIBLE，别的 GONE（不占地方、不量），当前显示的键换成这些，键帽和高亮跟上。
     * 显示的键没变的子 view 不动（setVisibility 同值什么都不做）。
     */
    private void showLayer() {
        caps.clear();
        for (Key key : KeyRows.keys(rows(), split)) caps.add(capOf.get(key));
        IdentityHashMap<Cap, Boolean> shown = new IdentityHashMap<>();
        for (Cap c : caps) shown.put(c, Boolean.TRUE);
        for (Cap c : capOf.values()) c.view.setVisibility(shown.containsKey(c) ? VISIBLE : GONE);
        refreshLabels();
        requestLayout();
    }

    /**
     * 键要换掉了（换层、重建底行、分裂 / 合拢）：面板自己收的手指按着的键先松开；所有键画回松开的样子、收起放大提示
     * （服务转来的按下等不到这些键的松开了，藏起来的层下次显示时也不该还按着）。
     */
    private void dropTouches() {
        releaseFingers();
        for (Cap c : caps) {
            look(c, false);
            preview(c, false);
        }
    }

    // ═══════════ 摆放 ═══════════

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (KeyRows.Placed p : KeyRows.place(rows(), w, h, split, middle, gap)) {
            Cap c = capOf.get(p.key);
            c.left = p.left;
            c.top = p.top;
            c.right = Math.max(p.left, p.right);
            c.bottom = Math.max(p.top, p.bottom);
            c.view.measure(MeasureSpec.makeMeasureSpec(c.right - c.left, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(c.bottom - c.top, MeasureSpec.EXACTLY));
        }
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
        String label = key.label;
        if ((modifiers & KeyRows.SHIFT) != 0 && key.shifted != null) label = key.shifted;
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

    /** 字符键按下时放大显示（看清按到了哪个），松开收起。 */
    private void preview(Cap c, boolean pressed) {
        if (previewHost == null) return;
        if (!pressed) previewHost.hideKeyPreview(c.view);
        else if (isCharKey(c)) previewHost.showKeyPreview(c.view, c.view.getText().toString());
    }

    /** 字符键：键帽只有一个字的字母、数字、符号。修饰键、⇧ ⌫ ↵、空格、F 键、快捷行不放大。 */
    private boolean isCharKey(Cap c) {
        Key key = c.key;
        if (key.special || isShortcut(key)) return false;
        String t = c.view.getText().toString();
        return t.length() == 1 && "⇧⌫↵".indexOf(t) < 0;
    }

    /** 高亮（换层键、锁定的修饰键）和按下的样子，功能键用主题的功能键颜色；和上次画的一样就不动（Style.keyLook 每次都新建背景）。 */
    private void look(Cap c, boolean pressed) {
        int id = c.key.id;
        boolean active = (id == SYMBOLS_BUTTON_ID && layer == KeyRows.LAYER_SYMBOLS)
                || (id == FN_BUTTON_ID && layer == KeyRows.LAYER_FN)
                || (id >= 0xE0 && id <= 0xE3 && (modifiers & (1 << (id - 0xE0))) != 0);
        c.pressed = pressed;
        int state = (active ? 2 : 0) | (pressed ? 1 : 0);
        if (state == c.look) return;
        c.look = state;
        Style.keyLook(c.view, c.fn, active, pressed);
    }

    // ═══════════ 键自己收触摸（服务没接管、没走本地分流时） ═══════════

    /** 面板整块收触摸，不让各键自己收：按手指编号分给键（{@link KeyFingers}），多指打字、缝里的手指都按同一条规则。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return true;
    }

    /**
     * 每根手指按下时定键（包含的键优先，缝、空位、边距分给这一半里最近的键，规则同分流 TouchRouter.nearestKey），
     * 抬起时松开它自己那个键；ACTION_CANCEL 全部松开。分裂时按在中间（触控板那一截）的手指、按在嵌着的小红点上的手指不算任何键。
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
                if (old != KeyFingers.NONE) release(old, false);
                press(index);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = fingers.up(e.getPointerId(e.getActionIndex()));
                if (index != KeyFingers.NONE) release(index, true);
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

    /** 面板自己收的手指按着的键全部松开（不算点击）。 */
    private void releaseFingers() {
        for (int index : fingers.cancel()) release(index, false);
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
        int[] h = stickHole;
        TouchRouter.Region[] holes = h == null ? new TouchRouter.Region[0]
                : new TouchRouter.Region[]{new TouchRouter.Region(Proto.REGION_STICK, Proto.STICK_ID, h[0], h[1], h[2], h[3])};
        TouchRouter.Region r = KeyFingers.pick(rs, areas, holes, x, y);
        return r == null ? -1 : r.id;
    }

    /** 先发键，再震动、画按下的样子（震动是一次 binder 调用，画背景也要时间，别让报文等它们）。 */
    private void press(int index) {
        Cap c = caps.get(index);
        if (!c.key.special) listener.onLocalKey(c.key.id, true);
        listener.onKeyHaptic(c.key.id, c.key.special);
        look(c, true);
        preview(c, true);
    }

    /** tap = 手指正常抬起（特殊键这时才算点了）；被取消、被顶掉的只松开。 */
    private void release(int index, boolean tap) {
        Cap c = caps.get(index);
        if (!c.key.special) {
            listener.onLocalKey(c.key.id, false);
        } else if (tap) {
            post(() -> listener.onSpecialKey(c.key.id));
        }
        look(c, false);
        preview(c, false);
    }
}
