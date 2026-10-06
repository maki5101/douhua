package dev.douhua;

import android.hardware.display.DisplayManager;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * 找 DeX 所在的那块屏幕，按键要注入到它上面。只认外接屏（有线 / 无线投屏）；
 * scrcpy 之类的虚拟屏、手机自己的屏、豆花所在的屏都跳过。
 * 只有拿不到屏幕类型（隐藏 API 被拦）时，才退而求其次选一块亮着的、非私有的其它屏。
 */
final class DexDisplay {
    private static final int TYPE_UNKNOWN = -1;
    private static final int TYPE_EXTERNAL = 2;
    private static final int TYPE_WIFI = 3;

    private DexDisplay() {}

    /** 找不到返回 null。ownDisplayId 是豆花自己所在的屏。 */
    static Display find(DisplayManager dm, int ownDisplayId) {
        Display fallback = null;
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || d.getDisplayId() == ownDisplayId) continue;
            if (d.getState() != Display.STATE_ON) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            int type = typeOf(d);
            if (type == TYPE_EXTERNAL || type == TYPE_WIFI) return d;
            if (type == TYPE_UNKNOWN && fallback == null) fallback = d;
        }
        return fallback;
    }

    /** Display.getType() 是隐藏 API；拿不到时返回 TYPE_UNKNOWN，交给上面的退路逻辑。 */
    private static int typeOf(Display d) {
        try {
            Method m = Display.class.getMethod("getType");
            return (Integer) m.invoke(d);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return TYPE_UNKNOWN;
        }
    }
}
