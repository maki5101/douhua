package dev.douhua.server;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * 把按键直接注入到指定屏幕（DeX 那块）。只有 shell / root 身份有这个权限，所以放在服务里。
 * 用的都是隐藏 API，靠反射调用；app_process 起的进程不受隐藏 API 限制。
 */
final class Injector {
    private static final int INJECT_MODE_ASYNC = 0;

    /** {整体 meta 位, 左侧修饰键键码, 左侧 meta 位}，按这个顺序按下、反序松开。 */
    private static final int[][] MODIFIERS = {
            {KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON},
            {KeyEvent.META_SHIFT_ON, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_LEFT_ON},
            {KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON},
            {KeyEvent.META_META_ON, KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_LEFT_ON},
    };

    private final Object inputManager;
    private final Method injectInputEvent;
    private final Method setDisplayId;

    Injector() throws ReflectiveOperationException {
        Class<?> cls;
        Object im;
        try {
            cls = Class.forName("android.hardware.input.InputManagerGlobal");   // Android 14+
            im = cls.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            cls = Class.forName("android.hardware.input.InputManager");
            im = cls.getDeclaredMethod("getInstance").invoke(null);
        }
        inputManager = im;
        injectInputEvent = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayId = KeyEvent.class.getMethod("setDisplayId", int.class);
    }

    /** 按一下 keyCode，期间按住 metaState 里的修饰键。 */
    void key(int displayId, int keyCode, int metaState) throws ReflectiveOperationException {
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

    private void send(int displayId, long downTime, int action, int keyCode, int meta)
            throws ReflectiveOperationException {
        KeyEvent ev = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        setDisplayId.invoke(ev, displayId);
        injectInputEvent.invoke(inputManager, ev, INJECT_MODE_ASYNC);
    }
}
