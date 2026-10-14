package dev.douhua.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 把"键盘打的字送到哪块屏"切到外接屏（DeX）。
 *
 * 为什么：在手机屏上打开豆花，系统就把键盘输入的目标切到了手机屏；豆花的窗口故意不收按键（不抢 DeX 窗口的焦点），
 * 于是外接键盘（或豆花自己的虚拟键盘）打的字送到手机屏没人收。系统的输入队列就卡在这个键上干等，
 * 后面的光标、点击全在排队——用户看到的是"豆花的输入全没反应"，十来秒后系统报"豆花没有响应"
 * （2026-10-13 主力机：am_anr … Input dispatching timed out (Application does not have a focused window)，
 * 用户刚接上自己的外接键盘鼠标）。
 * 豆花接管触摸屏时把目标切回 DeX 屏，打的字就直接进 DeX 里最前面的窗口。只在接管那一刻切一次，
 * 之后用户点哪个窗口，输入照常跟过去。
 *
 * 用 IActivityTaskManager.focusTopTask(屏幕编号)（隐藏 API，反射；要 MANAGE_ACTIVITY_TASKS 权限，shell 有）。
 * 失败只记一次日志、以后不再试，不影响别的功能。
 */
final class DexFocus {
    private static Object service;
    private static Method focusTopTask;
    private static boolean failed;

    private DexFocus() {}

    /** 返回是否成功（调用没出错；系统有没有真的切过去要看 dumpsys window 的 mTopFocusedDisplayId）。 */
    static synchronized boolean moveTo(int displayId) {
        if (failed) return false;
        try {
            if (focusTopTask == null) {
                service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                focusTopTask = service.getClass().getMethod("focusTopTask", int.class);
            }
            focusTopTask.invoke(service, displayId);
            return true;
        } catch (InvocationTargetException e) {
            failed = true;
            Server.log("把键盘输入切到屏幕 " + displayId + " 失败（以后不再试）：" + e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Server.log("把键盘输入切到屏幕 " + displayId + " 不可用（以后不再试）：" + e);
        }
        return false;
    }
}
