package dev.douhua;

/**
 * 声音的事件层（声音第一批第 ③ 步）："发生了什么" → 放哪个声音（SoundBank 的声音 id）、震不震。纯 Java，离线测（SoundEventsTest）。
 * MainActivity 在现在调震动的那几处调它（三条触摸路径一样：服务接管 / 蓝牙本地分流报来的按钮和震动、面板 / 数字键盘 / 鼠标键自己收触摸），
 * 声音先交出去（{@link Out#play} 只往队列里写一个数，不等）、再震；震动不为声音往后拖。只在主线程里用，不加锁、不分配。
 *
 * 照方案 design-sound 2.3 的表（"只给物理动作配声音，不给软件状态配"）：
 * <ul>
 * <li>键按下（豆花布局、实体键盘、数字键盘、快捷行，?123 / Fn / 🌐 / ⋯ / 快捷行的动作也算）= KEY_DOWN；正常松开 = KEY_UP；被取消的松开（手掌、收尾，
 *     协议 25 的 Proto.BUTTON_CANCELED、面板的 KEY_CANCEL）不出声。快捷行里的"滚轮"按下不"嗒"（那一格自己咔嗒），也不出按键声（MainActivity 不调）。</li>
 * <li>"按住到点"（GestureEngine.HAPTIC_HOLD：修饰键快锁了、Caps 只切显示、合并键出第二个字、Fn 快永久锁了、Fn+Esc 翻转 F 行）：软件状态，不出声。</li>
 * <li>分流报来的"点了一下"（HAPTIC_CLICK：触控板单指 / 双指轻点、双击的每一下、小红点轻点）= 像 MacBook 触控板的一对：PAD_DOWN + {@link #CLICK_GAP_MS} 后 PAD_UP
 *     （用户 2026-11-02：比三个鼠标键轻、最接近 MacBook 触控板）。分流在手指抬起、认出是轻点那一刻才报，按下、松开早就过去了，所以压成一对。</li>
 * <li>触控板上开始拖动 / 结束拖动（HAPTIC_DRAG_START / DRAG_END：长按拖动、轻点后拖）= PAD_DOWN / PAD_UP；拖动被取消着结束
 *     （协议 25 的 HAPTIC_DRAG_CANCEL：手掌、丢数据、释放触摸屏 / 退后台 / 换参数收尾）不出松开声，震动照拖动结束。空中鼠标的按住拖动同样。</li>
 * <li>鼠标键（三个鼠标键、"左键"按钮、音量键按住左键、空中鼠标的按住拖动）按下 = MOUSE_DOWN、松开 = MOUSE_UP（右键高一点、中键闷一点）；被取消的松开不出声。
 *     点击类动作（动作清单里的"单击 / 右键"之类，按钮条 / 角上 / 空中鼠标页 / 音量键点一下）= MOUSE_DOWN + {@link #CLICK_GAP_MS} 后 MOUSE_UP。</li>
 * <li>滚一格（HAPTIC_TICK：双指、边缘、中键滚动、滚动杆；滚轮动作那一格）= SCROLL（快了 SoundVary 合并、变轻）；惯性刹住 = CATCH；惯性放开、转着不出声。</li>
 * <li>手柄键按下 / 松开、十字键换方向 / 摇杆推到边 = GAMEPAD_DOWN / UP / TICK（设置里默认关）。</li>
 * <li>移动光标的颗粒、拖动时的持续轻震、三指四指手势、⋯ / 菜单 / 调整栏 / 空中鼠标页的界面按钮、吸附、回正中：不出声（MainActivity 不调，或者这里不放）。</li>
 * </ul>
 * 四个开关（设置 → 外观 → 声音，第 ④ 步）：键盘、鼠标键（含触控板轻点）、滚轮（含惯性刹住）、手柄。设置页的试听台也用一份它（SettingsActivity.benchEvents），规则只有这一处。
 *
 * 滚轮的咔嗒在引擎 / 服务里才发（GestureEngine.Config.scrollTicks 关着就根本不发）。"滚轮出声、滚轮不震"要引擎照样发、App 自己按震动开关滤掉：
 * MainActivity 把 {@link #wantTicks} 写进发给引擎 / 服务的参数，{@link #haptic} 的返回值告诉它这一下震不震。声音关着时 wantTicks = 用户的震动开关、
 * haptic 一律返回 true（和以前逐字一样：发来的就是该震的）。
 */
public final class SoundEvents {
    /** 把声音交出去（Sounds.play）。 */
    public interface Out {
        /** sound = SoundBank 的声音 id，key 见 SoundBank 的说明，delayMs &gt; 0 = 晚这么多毫秒再放（一对"咔哒"的"哒"）。 */
        void play(int sound, int key, int delayMs);
    }

    /** 一次点击压成的那一对"咔哒"：按下 + 这么多毫秒后松开（方案 2.3：压在约 40–60 毫秒内）。 */
    public static final int CLICK_GAP_MS = 45;
    /** {@link #haptic} 的震动从哪来：分流（触控板、小红点、滚动杆、按键的"按住到点"）、手柄页的分流（十字键、摇杆）、空中鼠标（AirMouse）。 */
    public static final int FROM_ROUTER = 0, FROM_GAMEPAD = 1, FROM_AIR = 2;
    /**
     * 键盘上的特殊键（?123 / Fn / 🌐 / ⋯ / 快捷行的动作，界面上的按钮编号）发声时当成哪个用法码：0x65（Application / Menu 键）——SoundVary 当它是功能键、
     * 在最下一排。按钮编号放进"按住的键"那一半（Proto.dualKey），每个特殊键自己一组（按下和松开互斥、和别的键不互斥）。只给声音用，不发给服务。
     */
    public static final int SPECIAL_USAGE = 0x65;
    /** 同时按着的特殊键最多记几个（手指数够用了；满了照样出声，只是松开时认不出"按过"）。 */
    static final int MAX_SPECIAL = 16;

    private final Out out;
    private boolean on;
    private boolean keys = true, mouse = true, scroll = true, gamepad;
    /** 用户的"滚动咔嗒"震动开关（Prefs.scrollTicks）。 */
    private boolean scrollHaptic = true;
    // 按着的特殊键（编号 → 次数）：只给按过的松开出声。分流对 fn🌐 单点只报 🌐 的 TAP、没有按下（Proto.GLOBE_BUTTON_ID），那一下 fn🌐 键自己已经出过声
    private final int[] specialIds = new int[MAX_SPECIAL];
    private final int[] specialCounts = new int[MAX_SPECIAL];

    public SoundEvents(Out out) {
        this.out = out;
    }

    /**
     * 设置（主线程，读完设置调）：on = 套装不是"关"；四个开关（键盘、鼠标键、滚轮、手柄）；scrollHaptic = 用户的"滚动咔嗒"震动开关。
     * 换了设置不清按着的特殊键（之后的松开照样配得上）。
     */
    public void configure(boolean on, boolean keys, boolean mouse, boolean scroll, boolean gamepad, boolean scrollHaptic) {
        this.on = on;
        this.keys = keys;
        this.mouse = mouse;
        this.scroll = scroll;
        this.gamepad = gamepad;
        this.scrollHaptic = scrollHaptic;
    }

    /** 声音开着（套装不是"关"）。 */
    public boolean on() {
        return on;
    }

    /**
     * 引擎 / 服务要不要发滚轮的咔嗒（GestureEngine.Config.scrollTicks；连惯性的放开 / 刹住、小红点 / 滚动杆滚动的咔嗒）：
     * 震动开着、或者声音开着且滚轮出声。声音关着时就是震动开关（发给服务的参数和以前一样）。
     */
    public boolean wantTicks() {
        return scrollHaptic || ticksForSoundOnly();
    }

    /** 引擎发咔嗒只是为了出声（震动开关关着）：发来的滚轮咔嗒、惯性放开 / 刹住只出声、不震。 */
    private boolean ticksForSoundOnly() {
        return on && scroll && !scrollHaptic;
    }

    // ═══════════ 键盘 ═══════════

    /** 键按下：key = 区域的键 id（Proto.REGION_KEY 那种，含合并键 / Fn 键 / Fn 层 / F 行键），数字键盘是用法码。 */
    public void keyDown(int key) {
        if (on && keys) out.play(SoundBank.KEY_DOWN, key, 0);
    }

    /** 键松开：被取消的（手掌、收尾、面板的 KEY_CANCEL）不出声。 */
    public void keyUp(int key, boolean canceled) {
        if (on && keys && !canceled) out.play(SoundBank.KEY_UP, key, 0);
    }

    /** 键盘上的特殊键（?123 / Fn / 🌐 / ⋯ / 快捷行的动作）按下：buttonId = 界面上的按钮编号。 */
    public void specialDown(int buttonId) {
        int i = buttonId == 0 ? -1 : specialIndex(buttonId);
        if (i < 0 && buttonId != 0) i = specialIndex(0);   // 新的一个（0 = 空位；真的按钮编号都不是 0）
        if (i >= 0) {
            specialIds[i] = buttonId;
            specialCounts[i]++;
        }
        if (on && keys) out.play(SoundBank.KEY_DOWN, specialKey(buttonId), 0);
    }

    /** 特殊键松开：只给按过的出声（见 {@link #specialIds}）；被取消的不出声。 */
    public void specialUp(int buttonId, boolean canceled) {
        int i = specialIndex(buttonId);
        if (i < 0 || buttonId == 0) return;
        if (--specialCounts[i] <= 0) {
            specialCounts[i] = 0;
            specialIds[i] = 0;
        }
        if (on && keys && !canceled) out.play(SoundBank.KEY_UP, specialKey(buttonId), 0);
    }

    /**
     * 面板自己收触摸（豆花布局 KeyboardPanel、实体键盘 FixedKeyboardView 经 MainActivity.onKeyHaptic）的键按下：special = 特殊键
     * （?123 / Fn / 🌐 / ⋯ / 快捷行的动作，id 是界面上的按钮编号，同分流报来的特殊键走 {@link #specialDown}），不然 id 是区域的键 id（同 {@link #routerButton} 的键盘键）。
     */
    public void panelDown(int id, boolean special) {
        if (special) specialDown(id);
        else keyDown(id);
    }

    /** 面板的键松开（MainActivity.onKeyRelease）：canceled = 不是正常抬起（面板的 KEY_CANCEL：ACTION_CANCEL、被顶掉、换键时消失）——不出声。 */
    public void panelUp(int id, boolean special, boolean canceled) {
        if (special) specialUp(id, canceled);
        else keyUp(id, canceled);
    }

    /** 特殊键发声时的键 id（见 {@link #SPECIAL_USAGE}）。 */
    public static int specialKey(int buttonId) {
        return Proto.dualKey(SPECIAL_USAGE, buttonId);
    }

    private int specialIndex(int id) {
        for (int i = 0; i < MAX_SPECIAL; i++) {
            if (specialIds[i] == id && (id != 0 || specialCounts[i] == 0)) return i;
        }
        return -1;
    }

    /** 收尾（服务接管、断开）：按着的特殊键都忘掉（它们等不到松开了；之后迟到的松开不出声）。 */
    public void reset() {
        java.util.Arrays.fill(specialIds, 0);
        java.util.Arrays.fill(specialCounts, 0);
    }

    // ═══════════ 鼠标键 ═══════════

    /** 鼠标键按下（三个鼠标键、"左键"按钮、音量键按住左键、空中鼠标按住拖动）：button = Proto.BUTTON_*。 */
    public void mouseDown(int button) {
        if (on && mouse) out.play(SoundBank.MOUSE_DOWN, button, 0);
    }

    /** 鼠标键松开；被取消的不出声。 */
    public void mouseUp(int button, boolean canceled) {
        if (on && mouse && !canceled) out.play(SoundBank.MOUSE_UP, button, 0);
    }

    /** 点击类动作（一次"点一下这个鼠标键"）：按下 + {@link #CLICK_GAP_MS} 后松开。 */
    public void click(int button) {
        if (!on || !mouse) return;
        out.play(SoundBank.MOUSE_DOWN, button, 0);
        out.play(SoundBank.MOUSE_UP, button, CLICK_GAP_MS);
    }

    // ═══════════ 滚轮 ═══════════

    /** 滚轮动作（"滚轮向上 / 向下"）滚了一格：App 自己震的那一格（MainActivity.wheel），震不震照旧看震动开关。 */
    public void wheel() {
        if (on && scroll) out.play(SoundBank.SCROLL, 0, 0);
    }

    // ═══════════ 手柄 ═══════════

    public void gamepadDown(int id) {
        if (on && gamepad) out.play(SoundBank.GAMEPAD_DOWN, id, 0);
    }

    public void gamepadUp(int id, boolean canceled) {
        if (on && gamepad && !canceled) out.play(SoundBank.GAMEPAD_UP, id, 0);
    }

    // ═══════════ 分流 / 服务报来的 ═══════════

    /**
     * 分流 / 服务报来的按钮（Proto.MSG_BUTTON / TouchRouter.Output.button，协议 25 的松开可能带 Proto.BUTTON_CANCELED）里，键盘键和手柄控件出声。
     * 认得（键盘键、手柄控件）返回 true；别的编号（界面按钮、三个鼠标键、键盘上的特殊键）返回 false，由界面按各自的规矩调上面那几个。
     */
    public boolean routerButton(int id, int action) {
        boolean down = Proto.buttonAction(action) == Proto.BUTTON_DOWN;
        boolean canceled = Proto.buttonCanceled(action);
        if (id >= Proto.KEY_ID_BASE) {
            int key = id - Proto.KEY_ID_BASE;
            if (down) keyDown(key);
            else keyUp(key, canceled);
            return true;
        }
        if (Proto.isGamepadId(id)) {
            if (down) gamepadDown(id);
            else gamepadUp(id, canceled);
            return true;
        }
        return false;
    }

    /**
     * 分流 / 服务 / 空中鼠标报来的震动（GestureEngine.HAPTIC_*）：先出声，返回这一下震不震（false 只有一种情况：发来的滚轮咔嗒、惯性放开 / 刹住
     * 只是为了出声才让引擎发的，见 {@link #wantTicks}）。from = {@link #FROM_ROUTER} / {@link #FROM_GAMEPAD} / {@link #FROM_AIR}。
     */
    public boolean haptic(int kind, int from) {
        switch (kind) {
            case GestureEngine.HAPTIC_CLICK:   // 协议 25 起分流的"点一下"只剩轻点（按住到点另是 HAPTIC_HOLD）
                if (from == FROM_ROUTER && on && mouse) {
                    out.play(SoundBank.PAD_DOWN, 0, 0);
                    out.play(SoundBank.PAD_UP, 0, CLICK_GAP_MS);
                }
                return true;
            case GestureEngine.HAPTIC_DRAG_START:
                if (from == FROM_ROUTER) {
                    if (on && mouse) out.play(SoundBank.PAD_DOWN, 0, 0);
                } else if (from == FROM_AIR) {
                    mouseDown(Proto.BUTTON_LEFT);
                }
                return true;
            case GestureEngine.HAPTIC_DRAG_END:
                if (from == FROM_ROUTER) {
                    if (on && mouse) out.play(SoundBank.PAD_UP, 0, 0);
                } else if (from == FROM_AIR) {
                    mouseUp(Proto.BUTTON_LEFT, false);
                }
                return true;
            case GestureEngine.HAPTIC_DRAG_CANCEL:   // 拖动被取消着结束（协议 25）：不是用户松了手，不出松开声；震动照拖动结束
                return true;
            case GestureEngine.HAPTIC_TICK:
                if (from == FROM_GAMEPAD) {   // 十字键换方向、摇杆推到边（手柄自己的开关定发不发，照旧震）
                    if (on && gamepad) out.play(SoundBank.GAMEPAD_TICK, 0, 0);
                    return true;
                }
                if (from == FROM_AIR) return true;   // 空中鼠标的回正中：界面，不出声
                if (on && scroll) out.play(SoundBank.SCROLL, 0, 0);
                return !ticksForSoundOnly();
            case GestureEngine.HAPTIC_SPIN_START:   // 惯性放开：不出声（罗技的无限滚轮转起来几乎没声）
                return !ticksForSoundOnly();
            case GestureEngine.HAPTIC_SPIN_CATCH:
                if (on && scroll) out.play(SoundBank.CATCH, 0, 0);
                return !ticksForSoundOnly();
            default:   // 按住到点（软件状态）、颗粒、三指四指手势：不出声
                return true;
        }
    }
}
