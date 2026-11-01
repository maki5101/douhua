package dev.douhua;

/**
 * 桌面模式分屏 / 小窗里豆花窗口拿着键盘焦点时，请服务把焦点交给同一块屏上另一个看得见的应用（{@link Proto#CMD_FOCUS_OTHER}，协议 22）：
 * 什么时候发、多久最多发一次。纯 Java（tools/test.sh 离线测，FocusHandoffTest）。
 *
 * 为什么：从设置页回来，onResume 清掉 yieldFocus，豆花窗口重新拿到焦点；豆花自己的虚拟键盘、手柄的键送到豆花窗口、被吞掉，
 * 整块键盘打不出字，要用户点一下另一半才好（2026-11-01 主力机）。
 *
 * 什么时候能发（{@link #eligible}，全都要满足）：桌面模式（蓝牙模式下键不经这台手机的窗口）；窗口没占满（分屏 / 小窗——全屏时豆花是这块屏唯一的应用，
 * 交出去就是"没有焦点窗口、5 秒后没有响应"）；没接外接屏（接着时键本来就去外接屏，DexFocus 管）；连着服务；贴字面板没开（它要焦点打字）；在前台。
 * 再加上窗口这时真的拿着焦点（Activity.hasWindowFocus）。App 分不出分屏和小窗（isInMultiWindowMode 两种都是真，窗口模式是隐藏 API），
 * 所以两种都发；小窗时由服务按任务的窗口模式不交（server.FocusPick：交了会把后面的全屏应用挪到小窗上面、盖住豆花）。
 *
 * 节流（{@link #decide}）：同一次拿到焦点只发一次（交出去了豆花就收到"失去焦点"，照旧 yieldFocus、不再拿；没交出去——服务没找到别的应用——也不反复问）；
 * 重新连上服务算新的一次（旧连接上发的可能没做完）。两次之间至少隔 {@link #MIN_GAP_MS}：短时间里反复拿到 / 丢掉焦点时不狂发，
 * 隔不够的不丢，等够了再问一次（MainActivity 照返回的毫秒数 postDelayed）。
 * MainActivity 在拿到 / 丢掉焦点、连上 / 断开服务、窗口占满 ↔ 没占满、贴字面板收起、外接屏变了时问。
 */
final class FocusHandoff {
    /** 两次请求之间至少隔这么久。 */
    static final long MIN_GAP_MS = 1000;
    /** {@link #decide} 的返回值：什么都不做 / 现在发；正数 = 过这么多毫秒再问一次。 */
    static final long NONE = -1, NOW = 0;

    /** 这一次拿到焦点（同一条连接上）已经发过了。 */
    private boolean sentThisFocus;
    private boolean everSent;
    private long lastSent;

    /** 能不能发（窗口拿没拿着焦点另看）。参数见类注释。 */
    static boolean eligible(boolean desktopMode, boolean windowFull, boolean externalDisplay, boolean connected,
                            boolean textPanelOpen, boolean resumed) {
        return desktopMode && !windowFull && !externalDisplay && connected && !textPanelOpen && resumed;
    }

    /** 现在该不该发：{@link #NONE}、{@link #NOW}，或者过多少毫秒再问一次。不改状态，发了要调 {@link #sent}。 */
    long decide(boolean hasFocus, boolean eligible, long now) {
        if (!hasFocus || !eligible || sentThisFocus) return NONE;
        if (everSent) {
            long wait = lastSent + MIN_GAP_MS - now;
            if (wait > 0) return wait;
        }
        return NOW;
    }

    void sent(long now) {
        sentThisFocus = true;
        everSent = true;
        lastSent = now;
    }

    /** 窗口丢了焦点：下次拿到算新的一次。 */
    void focusLost() {
        sentThisFocus = false;
    }

    /** 重新连上服务：旧连接上发的可能没做完（服务断了），算新的一次。 */
    void reconnected() {
        sentThisFocus = false;
    }
}
