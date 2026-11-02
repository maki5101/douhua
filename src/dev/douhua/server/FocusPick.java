package dev.douhua.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面模式分屏里豆花窗口拿着键盘焦点时（虚拟键盘的键被豆花自己吞掉），挑同一块屏上哪个任务接过焦点（小窗时不交，见下面的规则）；
 * {@link TaskFocus} 照它调 IActivityTaskManager.setFocusedTask。纯 Java（tools/test.sh 离线测，FocusPickTest）：
 * 任务信息从 ActivityManager.RunningTaskInfo 按字段名反射读出来（{@link #read}），测试用同名字段的假对象。
 *
 * 字段照 AOSP 的 android.app.TaskInfo（android16-release 为主，对照 android15-release、android-14.0.0_r1、android-13.0.0_r1 的原文，2026-11-01 读的）：
 * taskId、displayId、topActivity / baseActivity（ComponentName）、isVisible（Task.hasVisibleChildren：有看得见的 Activity）、
 * isFocused（Task.isFocused：这块屏的 mFocusedApp 在这个任务里）、topActivityType、lastActiveTime 13–16 都有；
 * isVisibleRequested 14 起才有（13 没有就当不知道）；isTopActivityNoDisplay 16 才有；窗口模式走 TaskInfo.getWindowingMode()（13–16 都有，@hide）。
 * 常量照 android16-release 的 WindowConfiguration：ACTIVITY_TYPE_UNDEFINED 0 / STANDARD 1 / HOME 2 / RECENTS 3 / ASSISTANT 4 / DREAM 5，
 * WINDOWING_MODE_FULLSCREEN 1 / PINNED 2（画中画）/ FREEFORM 5（小窗）/ MULTI_WINDOW 6（分屏的两半）。任务报的窗口模式是算上父容器以后的
 * （Task.fillTaskInfo 里 setWindowingMode(getWindowingMode())），分屏里的应用任务报 6，不是它自己没设的 0。
 *
 * 规则（{@link #pick}）：
 * - 只看这块屏（displayId）上的任务；
 * - 豆花自己的任务（顶上或最底下的 Activity 是 dev.douhua）得正拿着焦点，不然什么都不做——请求晚到、用户已经点了别处时不抢；
 * - **只在分屏时交**：豆花的任务窗口模式得是 MULTI_WINDOW（6），候选也得是 6（分屏的另一半）。小窗（5）时不交（审查发现，2026-11-01 晚）：
 *   setFocusedTask 会把目标挪到最上面（ActivityRecord.moveFocusableActivityToTop → Task.moveToFront → positionChildAt(POSITION_TOP, …, includingParents)），
 *   AOSP 上全屏的根任务和小窗的根任务优先级一样、都不是 always-on-top（TaskDisplayArea.findPositionForRootTask），豆花的小窗浮在全屏应用上时
 *   交出去等于把后面那个应用挪到小窗上面、盖住豆花——一打开就被自己请来的应用盖掉。三星的弹出式视图是不是也这样离线查不到，真机确认之前先不交。
 *   分屏时只挑另一半，浮在分屏上的第三个应用的小窗（5）就不会抢走焦点。窗口模式读不出来（{@link #UNKNOWN}）也不交：认不出是不是分屏；
 * - 候选：不是豆花的、有 Activity 的、不是 SystemUI 的、类型是普通应用（不是桌面、最近任务、助手、屏保——WMS 在同一块显示区里点桌面也不挪焦点，WindowManagerService.handleTaskFocusChange）、
 *   不是画中画、看得见（isVisible，14 起还要 isVisibleRequested 没变假：正要藏起来的不算）、顶上那个 Activity 不是 noDisplay 的（{@link #candidate}），而且在分屏里（6）；
 * - 挑 lastActiveTime 最大的（最近用过的）；一样大取列表里靠前的。系统给的列表本来就是"有焦点的看得见的 → 看得见的从上到下 → 看不见的"
 *   （14–16 的 RunningTasks.getTasks，看得见的 lastActiveTime 还按这个顺序重写成递减；13 是按 lastActiveTime 排、看得见的现刷新），两种都对得上。
 */
final class FocusPick {
    private FocusPick() {}

    static final String OWN_PACKAGE = "dev.douhua";
    static final String SYSTEM_UI = "com.android.systemui";
    static final int ACTIVITY_TYPE_UNDEFINED = 0, ACTIVITY_TYPE_STANDARD = 1;
    static final int WINDOWING_MODE_PINNED = 2, WINDOWING_MODE_FREEFORM = 5, WINDOWING_MODE_MULTI_WINDOW = 6;
    /** 窗口模式读不出来。 */
    static final int UNKNOWN = -1;

    /** 一个任务里挑任务要用的几样（RunningTaskInfo 的子集）。 */
    static final class Task {
        final int taskId, displayId;
        /** 顶上 / 最底下那个 Activity 的包名；没有就是 null。 */
        final String topPackage, basePackage;
        final boolean visible;
        /** isVisibleRequested：14 起才有，13 上是 null（不知道）。 */
        final Boolean visibleRequested;
        final boolean focused;
        final int activityType;
        /** getWindowingMode()；读不出来是 {@link #UNKNOWN}。 */
        final int windowingMode;
        /** isTopActivityNoDisplay（16 起；没有这个字段 = 假）。 */
        final boolean noDisplay;
        final long lastActive;

        Task(int taskId, int displayId, String topPackage, String basePackage, boolean visible, Boolean visibleRequested,
             boolean focused, int activityType, int windowingMode, boolean noDisplay, long lastActive) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.topPackage = topPackage;
            this.basePackage = basePackage;
            this.visible = visible;
            this.visibleRequested = visibleRequested;
            this.focused = focused;
            this.activityType = activityType;
            this.windowingMode = windowingMode;
            this.noDisplay = noDisplay;
            this.lastActive = lastActive;
        }

        /** 豆花自己的（设置页、主界面都在豆花的任务里；最底下的是豆花也算）。 */
        boolean own() {
            return OWN_PACKAGE.equals(topPackage) || OWN_PACKAGE.equals(basePackage);
        }

        String name() {
            return topPackage != null ? topPackage : basePackage != null ? basePackage : "?";
        }

        /** 诊断日志用的一项：task 编号、包名、看得见 / 焦点、类型、窗口模式。 */
        String describe() {
            return "task " + taskId + " " + name() + (focused ? " 焦点" : "") + (visible ? " 看得见" : " 看不见")
                    + (Boolean.FALSE.equals(visibleRequested) ? "（正要藏起来）" : "") + (noDisplay ? " noDisplay" : "")
                    + " 类型 " + activityType + " 窗口模式 " + (windowingMode == UNKNOWN ? "?" : String.valueOf(windowingMode))
                    + " 屏 " + displayId;
        }
    }

    /** 挑的结果：target 为 null = 不交，why 是原因（记日志用）。 */
    static final class Pick {
        final Task target;
        final String why;
        /** 不交是因为豆花的任务没拿着焦点（Server tasks 只读时另外说"拿着焦点的话会怎样"）。 */
        final boolean ownNotFocused;

        Pick(Task target, String why) {
            this(target, why, false);
        }

        Pick(Task target, String why, boolean ownNotFocused) {
            this.target = target;
            this.why = why;
            this.ownNotFocused = ownNotFocused;
        }
    }

    /**
     * 从一个 RunningTaskInfo（或者字段同名的对象）读出 {@link Task}。必须有的字段（taskId、displayId、topActivity、isVisible、isFocused、
     * topActivityType）缺了就抛 NoSuchFieldException：这个系统版本的字段对不上，调用方当"不支持"、以后不再试。
     */
    static Task read(Object info) throws ReflectiveOperationException {
        Class<?> c = info.getClass();
        int taskId = c.getField("taskId").getInt(info);
        int displayId = c.getField("displayId").getInt(info);
        String top = packageOf(c.getField("topActivity").get(info));
        Field base = optField(c, "baseActivity");
        String basePkg = base != null ? packageOf(base.get(info)) : null;
        boolean visible = c.getField("isVisible").getBoolean(info);
        Field req = optField(c, "isVisibleRequested");
        Boolean requested = req != null ? req.getBoolean(info) : null;
        boolean focused = c.getField("isFocused").getBoolean(info);
        int type = c.getField("topActivityType").getInt(info);
        Field noDisp = optField(c, "isTopActivityNoDisplay");
        boolean noDisplay = noDisp != null && noDisp.getBoolean(info);
        Field last = optField(c, "lastActiveTime");
        long lastActive = last != null ? last.getLong(info) : 0;
        return new Task(taskId, displayId, top, basePkg, visible, requested, focused, type, windowingMode(info), noDisplay, lastActive);
    }

    static List<Task> readAll(List<?> infos) throws ReflectiveOperationException {
        List<Task> out = new ArrayList<>();
        if (infos == null) return out;
        for (Object o : infos) {
            if (o != null) out.add(read(o));
        }
        return out;
    }

    private static Field optField(Class<?> c, String name) {
        try {
            return c.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** ComponentName（或同名方法的对象）的包名；null → null。 */
    private static String packageOf(Object component) throws ReflectiveOperationException {
        if (component == null) return null;
        Object p = component.getClass().getMethod("getPackageName").invoke(component);
        return p instanceof String ? (String) p : null;
    }

    /** TaskInfo.getWindowingMode()（@hide，13–16 都有）；读不出来是 {@link #UNKNOWN}，不挡候选。 */
    private static int windowingMode(Object info) {
        try {
            Method m = info.getClass().getMethod("getWindowingMode");
            Object v = m.invoke(info);
            return v instanceof Integer ? (Integer) v : UNKNOWN;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** 能不能把焦点交给它（不看是不是在这块屏上、在不在分屏里，{@link #pick} 另外筛）。 */
    static boolean candidate(Task t) {
        if (t.own() || t.topPackage == null || SYSTEM_UI.equals(t.topPackage)) return false;
        if (t.activityType != ACTIVITY_TYPE_UNDEFINED && t.activityType != ACTIVITY_TYPE_STANDARD) return false;
        if (t.windowingMode == WINDOWING_MODE_PINNED) return false;
        if (!t.visible || Boolean.FALSE.equals(t.visibleRequested)) return false;
        return !t.noDisplay;
    }

    /** 挑接焦点的任务（规则见类注释）。tasks 照系统给的顺序。 */
    static Pick pick(List<Task> tasks, int displayId) {
        return pick(tasks, displayId, true);
    }

    /**
     * needFocus = false：不看豆花的任务拿没拿着焦点、当它拿着算（Server tasks 只读时说"豆花拿着焦点的话会交给谁"：交焦点生效以后焦点已经在另一半，
     * 照 needFocus = true 只会说"焦点已经不在豆花"，看不出分屏的窗口模式对不对得上）。别的规则一样。
     */
    static Pick pick(List<Task> tasks, int displayId, boolean needFocus) {
        List<Task> here = new ArrayList<>();
        for (Task t : tasks) {
            if (t.displayId == displayId) here.add(t);
        }
        Task own = null, ownFocused = null, focusedOther = null;
        for (Task t : here) {
            if (t.own()) {
                if (own == null) own = t;
                if (t.focused && ownFocused == null) ownFocused = t;
            } else if (t.focused && focusedOther == null) {
                focusedOther = t;
            }
        }
        if (own == null) return new Pick(null, "这块屏上没有豆花的任务");
        if (ownFocused == null && needFocus) {
            return new Pick(null, focusedOther != null ? "焦点已经不在豆花（在 " + focusedOther.name() + "）"
                    : "焦点不在豆花的任务上", true);
        }
        if (ownFocused != null) own = ownFocused;
        if (own.windowingMode == WINDOWING_MODE_FREEFORM) {
            return new Pick(null, "豆花是小窗（窗口模式 5），小窗时不交（交了等于点了后面的应用一下，会把它挪到小窗上面、盖住豆花）");
        }
        if (own.windowingMode != WINDOWING_MODE_MULTI_WINDOW) {
            return new Pick(null, "豆花的窗口模式是 " + (own.windowingMode == UNKNOWN ? "?（读不出来）" : String.valueOf(own.windowingMode))
                    + "，不是分屏（6），只在分屏时交");
        }
        Task best = null;
        for (Task t : here) {
            if (!candidate(t) || t.windowingMode != WINDOWING_MODE_MULTI_WINDOW) continue;
            if (best == null || t.lastActive > best.lastActive) best = t;
        }
        if (best == null) return new Pick(null, "分屏里没有别的看得见的应用");
        return new Pick(best, "交给 " + best.name() + "（task " + best.taskId + "）");
    }

    /**
     * 诊断日志里的任务列表（一行一个，前面空两格）。all = false（交焦点时记进服务日志）：只列看得见的，加上挑中的那个（target，可以是 null），
     * 别的只记个数——日志文件在 /data/local/tmp、谁都能读，全列等于把最近用过哪些应用（微信、WhatsApp……）落了盘（2026-11-01 主力机的日志里列了二十来个）。
     * all = true（只读的 Server tasks：人手动跑、输出到终端）：照旧全列。
     */
    static String describe(List<Task> tasks, Task target, boolean all) {
        if (tasks.isEmpty()) return "  （这块屏上一个任务都没列出来）";
        StringBuilder b = new StringBuilder();
        int hidden = 0;
        for (Task t : tasks) {
            if (!all && !t.visible && t != target) {
                hidden++;
                continue;
            }
            if (b.length() > 0) b.append('\n');
            b.append("  ").append(t.describe());
        }
        if (hidden > 0) {
            if (b.length() > 0) b.append('\n');
            b.append("  （另有 ").append(hidden).append(" 个看不见的，不列）");
        }
        return b.toString();
    }

    /** 这块屏上现在拿着焦点的任务（核对交没交过去用）；没有就是 null。 */
    static Task focusedOn(List<Task> tasks, int displayId) {
        for (Task t : tasks) {
            if (t.displayId == displayId && t.focused) return t;
        }
        return null;
    }
}
