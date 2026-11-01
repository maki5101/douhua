package dev.douhua.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面模式分屏里豆花窗口拿着键盘焦点时，把焦点交给分屏的另一半（App 发 Proto.CMD_FOCUS_OTHER，协议 22；App 在分屏 / 小窗时都发，
 * 小窗时这里不交——交了会把后面的全屏应用挪到小窗上面，见 {@link FocusPick} 的规则）。
 *
 * 为什么（2026-11-01 主力机，诊断模式的服务日志）：分屏时从设置页回来，豆花 onResume 清掉 yieldFocus，窗口重新拿到键盘焦点；
 * 豆花自己的虚拟键盘的键送到豆花窗口，豆花按"没接外接屏时收下自己的键并吞掉"吞了——整块键盘打不出字，手柄键同样（只发给焦点窗口）。
 * 用户点一下另一半（Moonlight）焦点就回去了；这里替他"点一下"。
 *
 * 用 IActivityTaskManager（隐藏 API，反射；签名照 AOSP 的 IActivityTaskManager.aidl，android16-release / android15-release / android-14.0.0_r1 /
 * android-13.0.0_r1 的原文，2026-11-01 读的）：
 * - List&lt;ActivityManager.RunningTaskInfo&gt; getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId)：14–16；
 *   13 是没有 displayId 的三参数版（这时按任务的 displayId 自己筛）。要 REAL_GET_TASKS 才列得出别的应用的任务（不然只有自己的），shell 有
 *   （frameworks/base/packages/Shell/AndroidManifest.xml；ActivityTaskManagerService.isGetTasksAllowed）。
 * - void setFocusedTask(int taskId)：13–16 都有，要 MANAGE_ACTIVITY_TASKS（ActivityTaskManagerService.enforceTaskPermission），shell 有。
 *   用户点别的窗口时 WMS 走的就是它（WindowManagerService.handleTaskFocusChange → mAtmService.setFocusedTask(taskId, touchedActivity)），
 *   所以效果等于用户点了那个应用一下：任务挪到前面、成为这块屏的焦点应用。目标已经是焦点时什么都不变。
 * 挑哪个任务的规则在 {@link FocusPick}（纯 Java，离线测）。调完再列一次，核对焦点是不是真过去了（日志里写成没成）。
 *
 * 代理从 ServiceManager 现取（{@link SystemServices}），调用失败就丢掉、重取、当场再试一次（同 {@link DexFocus}）。
 * 接口、方法、字段对不上（这个系统版本不认）就永久停用，以后什么都不做（等于没有这个功能时的行为）。
 * 只在会话的 douhua-focus 线程（和 Server 的只读子命令 tasks）里调；绝不在读触摸的线程、命令线程里调（binder 可能卡住）。
 */
final class TaskFocus {
    private static final String IFACE = "android.app.IActivityTaskManager";
    /** 列多少个任务：看得见的排在最前面，20 个足够。 */
    static final int MAX_TASKS = 20;

    private static Object service;
    private static Method getTasks;
    /** getTasks 的参数个数：4（14 起，带 displayId）或 3（13）。 */
    private static int getTasksArgs;
    private static Method setFocusedTask;
    /** 不是 null = 这个系统版本做不了（原因），以后不再试。 */
    private static String missing;

    private TaskFocus() {}

    /** 一次交焦点的结果。 */
    static final class Result {
        /** 调了 setFocusedTask 而且核对过焦点确实过去了。 */
        final boolean moved;
        /** 记日志用的一行（交给了谁、成没成 / 为什么没交）。 */
        final String line;
        /** 诊断模式多记的：这块屏上的任务列表（没列出来是 null）。 */
        final String detail;

        Result(boolean moved, String line, String detail) {
            this.moved = moved;
            this.line = line;
            this.detail = detail;
        }
    }

    /**
     * 挑一个任务把焦点交过去。apply = false 只列出来、说会交给谁，不调 setFocusedTask（Server 的只读子命令 tasks 用）；
     * 这时豆花的任务没拿着焦点（多半是交焦点已经生效、焦点在另一半）就另外说一句"豆花拿着焦点的话会怎样"（{@link FocusPick#pick(List, int, boolean)}）。
     * 不抛异常：出错、不支持都写在结果里。
     */
    static synchronized Result handOff(int displayId, boolean apply) {
        if (missing != null) return new Result(false, "不可用（这个系统版本不认，以后不再试）：" + missing, null);
        for (int attempt = 0; ; attempt++) {
            try {
                ensure();
                List<FocusPick.Task> before = list(displayId);
                String detail = describe(before);
                FocusPick.Pick p = FocusPick.pick(before, displayId);
                if (!apply) {
                    String line = p.target == null ? "不交：" + p.why : "会" + p.why + "（只读，没交）";
                    if (p.ownNotFocused) {
                        FocusPick.Pick h = FocusPick.pick(before, displayId, false);
                        line += "；豆花拿着焦点的话" + (h.target == null ? "也不交：" + h.why : "会" + h.why);
                    }
                    return new Result(false, line, detail);
                }
                if (p.target == null) return new Result(false, "不交：" + p.why, detail);
                setFocusedTask.invoke(service, p.target.taskId);
                FocusPick.Task now = FocusPick.focusedOn(list(displayId), displayId);
                if (now != null && now.taskId == p.target.taskId) return new Result(true, p.why + "：成了", detail);
                return new Result(false, p.why + "：调用没出错，但焦点" + (now == null ? "不在这块屏的任何任务上" : "在 " + now.name()), detail);
            } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
                missing = e.toString() + signatures();
                return new Result(false, "不可用（这个系统版本不认，以后不再试）：" + missing, null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                service = null;   // 代理多半死了（框架重启过）：重取
                if (attempt == 0) continue;
                return new Result(false, "失败（下次再试）：" + LogThrottle.describe(e), null);
            }
        }
    }

    private static void ensure() throws ReflectiveOperationException {
        if (service != null) return;
        Class<?> c = Class.forName(IFACE);
        if (getTasks == null) {
            try {
                getTasks = c.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
                getTasksArgs = 4;
            } catch (NoSuchMethodException e) {
                getTasks = c.getMethod("getTasks", int.class, boolean.class, boolean.class);   // 13；再没有就抛出去 = 不支持
                getTasksArgs = 3;
            }
            setFocusedTask = c.getMethod("setFocusedTask", int.class);
        }
        service = SystemServices.get("activity_task", IFACE);
    }

    /** 这块屏上的任务（照系统给的顺序）；三参数版列的是所有屏，自己筛。 */
    private static List<FocusPick.Task> list(int displayId) throws ReflectiveOperationException {
        Object r = getTasksArgs == 4
                ? getTasks.invoke(service, MAX_TASKS, false, false, displayId)
                : getTasks.invoke(service, MAX_TASKS, false, false);
        List<FocusPick.Task> all = FocusPick.readAll(r instanceof List ? (List<?>) r : null);
        List<FocusPick.Task> here = new ArrayList<>();
        for (FocusPick.Task t : all) {
            if (t.displayId == displayId) here.add(t);
        }
        return here;
    }

    private static String describe(List<FocusPick.Task> tasks) {
        if (tasks.isEmpty()) return "  （这块屏上一个任务都没列出来）";
        StringBuilder b = new StringBuilder();
        for (FocusPick.Task t : tasks) {
            if (b.length() > 0) b.append('\n');
            b.append("  ").append(t.describe());
        }
        return b.toString();
    }

    /** 不支持时顺手列出接口里实际有的 getTasks / setFocusedTask 签名（各家改过的系统上好对照）。 */
    private static String signatures() {
        try {
            StringBuilder b = new StringBuilder();
            for (Method m : Class.forName(IFACE).getMethods()) {
                if (!m.getName().equals("getTasks") && !m.getName().equals("setFocusedTask")) continue;
                b.append(b.length() == 0 ? "；接口里有：" : "，").append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) b.append(i > 0 ? ", " : "").append(ps[i].getSimpleName());
                b.append(')');
            }
            return b.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }
}
