package dev.douhua.server;

import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 把按键直接注入到指定屏幕（DeX 那块）。只有 shell / root 身份有这个权限，所以放在服务里。
 * 用的都是隐藏 API，靠反射调用；app_process 起的进程不受隐藏 API 限制。
 *
 * 输入服务的代理用到时才取、失败就重取：系统框架重启后旧代理全死了。以前构造时取一次，
 * 之后整个服务都不能注入按键（InputManagerGlobal 是进程单例，里面的代理死了就一直是死的）。
 * 首选直接问 ServiceManager 要 IInputManager（见 {@link SystemServices}）；这个系统版本没有这个接口或方法时，
 * 退回原来的单例写法。
 */
final class Injector {
    private static final int INJECT_MODE_ASYNC = 0;
    private static final String IFACE = "android.hardware.input.IInputManager";

    /** {整体 meta 位, 左侧修饰键键码, 左侧 meta 位}，按这个顺序按下、反序松开。 */
    private static final int[][] MODIFIERS = {
            {KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON},
            {KeyEvent.META_SHIFT_ON, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON},
            {KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON},
            {KeyEvent.META_META_ON, KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON},
    };

    /** 取不到 KeyEvent.setDisplayId 就没法指定屏幕，永远不可用，原因记在 broken。 */
    private final Method setDisplayId;
    private final String broken;
    /** 输入服务的代理和注入方法；null = 用到时再取。 */
    private Object inputManager;
    private Method injectInputEvent;

    Injector() {
        Method m = null;
        String why = null;
        try {
            m = KeyEvent.class.getMethod("setDisplayId", int.class);
        } catch (NoSuchMethodException e) {
            why = e.toString();
        }
        setDisplayId = m;
        broken = why;
    }

    /** 现在能不能注入：能就返回 null，不能返回原因（每次连上时随 hello 告诉 App）。 */
    synchronized String status() {
        if (broken != null) return broken;
        try {
            ensure();
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return LogThrottle.describe(e);
        }
    }

    /** 按一下 keyCode，期间按住 metaState 里的修饰键。新旧会话可能同时调用，所以加锁。 */
    synchronized void key(int displayId, int keyCode, int metaState) throws ReflectiveOperationException {
        if (broken != null) throw new NoSuchMethodException("KeyEvent.setDisplayId（" + broken + "）");
        long downTime = SystemClock.uptimeMillis();
        int meta = 0;
        for (int[] m : MODIFIERS) {
            if ((metaState & m[0]) != 0) {
                meta |= m[0] | m[2];
                send(displayId, downTime, KeyEvent.ACTION_DOWN, m[1], meta);
            }
        }
        send(displayId, downTime, KeyEvent.ACTION_DOWN, keyCode, meta);
        send(displayId, downTime, KeyEvent.ACTION_UP, keyCode, meta);
        for (int i = MODIFIERS.length - 1; i >= 0; i--) {
            int[] m = MODIFIERS[i];
            if ((metaState & m[0]) != 0) {
                meta &= ~(m[0] | m[2]);
                send(displayId, downTime, KeyEvent.ACTION_UP, m[1], meta);
            }
        }
    }

    /**
     * 单独注入一次"按下"或"松开"（实体键补发给系统用），保留原始 downTime，不指定屏幕
     * （{@link Display#INVALID_DISPLAY}）——和真实物理键一样由系统按策略派发到当前有焦点的屏（理由见 CLAUDE.md 决策记录）。
     * 补发出去的每个"按下"在所有退出路径上都要配一个"松开"，不然系统会一直连发（音量被推到头 / 关机菜单弹出）。
     */
    synchronized void keyState(int keyCode, boolean down, long downTime, long eventTime)
            throws ReflectiveOperationException {
        if (broken != null) throw new NoSuchMethodException("KeyEvent.setDisplayId（" + broken + "）");
        send(Display.INVALID_DISPLAY, downTime, eventTime, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0);
    }

    private void send(int displayId, long downTime, int action, int keyCode, int meta)
            throws ReflectiveOperationException {
        send(displayId, downTime, SystemClock.uptimeMillis(), action, keyCode, meta);
    }

    private void send(int displayId, long downTime, long eventTime, int action, int keyCode, int meta)
            throws ReflectiveOperationException {
        KeyEvent ev = new KeyEvent(downTime, eventTime, action, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        setDisplayId.invoke(ev, displayId);
        try {
            ensure();
            injectInputEvent.invoke(inputManager, ev, INJECT_MODE_ASYNC);
        } catch (InvocationTargetException | RuntimeException e) {
            // 多半是系统框架重启过、代理死了：丢掉，重新取，再试一次；还不行才算失败
            inputManager = null;
            ensure();
            injectInputEvent.invoke(inputManager, ev, INJECT_MODE_ASYNC);
        }
    }

    private void ensure() throws ReflectiveOperationException {
        if (inputManager != null) return;
        try {
            Object im = SystemServices.get("input", IFACE);
            injectInputEvent = Class.forName(IFACE).getMethod("injectInputEvent", InputEvent.class, int.class);
            inputManager = im;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // 这个系统版本的 IInputManager 不是这个样子：退回 InputManagerGlobal（14 起）/ InputManager 单例。
            // 单例里的代理在框架重启后恢复不了，那时只能重启服务
            Class<?> cls;
            Object im;
            try {
                cls = Class.forName("android.hardware.input.InputManagerGlobal");
                im = cls.getMethod("getInstance").invoke(null);
            } catch (ClassNotFoundException e2) {
                cls = Class.forName("android.hardware.input.InputManager");
                im = cls.getDeclaredMethod("getInstance").invoke(null);
            }
            injectInputEvent = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
            inputManager = im;
        }
    }
}
