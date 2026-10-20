package dev.douhua.server;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Linux 输入设备（/dev/input/eventN）的底层操作。shell 在 input 组里，能直接读触摸屏的原始数据。
 * 独占（EVIOCGRAB）期间系统收不到这块触摸屏的任何触摸；关掉文件描述符即自动释放。实体键设备（KeyReader）也用这几个。
 */
final class Evdev {
    /** _IOW('E', 0x90, int) */
    private static final int EVIOCGRAB = 0x40044590;

    private Evdev() {}

    static FileDescriptor open(String path) throws IOException {
        try {
            return Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * 独占设备。内核只看参数指针是否非空，libcore 的 ioctlInt 传的是指向 int 的指针，所以总是"独占"；
     * 释放靠关闭文件描述符。
     */
    static void grab(FileDescriptor fd) throws IOException {
        try {
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            Method m = findIoctlInt(os.getClass());
            if (m.getParameterCount() == 2) {
                m.invoke(os, fd, EVIOCGRAB);
            } else {
                Class<?> ref = Class.forName("android.system.Int32Ref");
                m.invoke(os, fd, EVIOCGRAB, ref.getConstructor(int.class).newInstance(1));
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException("独占设备失败：" + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IOException("找不到 ioctl 接口：" + e, e);
        }
    }

    static void close(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
            // 已经关了
        }
    }

    private static Method findIoctlInt(Class<?> cls) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals("ioctlInt")) return m;
        }
        throw new NoSuchMethodException("ioctlInt");
    }
}
