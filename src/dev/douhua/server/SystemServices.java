package dev.douhua.server;

/**
 * 现取系统服务的 binder 代理（隐藏 API，反射）。
 *
 * 为什么每次现取：系统框架（system_server）重启后，以前取到的代理全死了，而且不会自己恢复；
 * DisplayManagerGlobal、InputManagerGlobal、ActivityTaskManager 这些进程单例里缓存的也一样。
 * app_process 起的进程里 ServiceManager 没有缓存（只有 App 进程启动时会填），getService 每次都现问
 * servicemanager，而 servicemanager 不跟着框架重启，所以框架起来后再取就是新的。
 * 调用方的做法：调用失败就丢掉代理，下次（或当场）重新取。
 */
final class SystemServices {
    private SystemServices() {}

    /** name 是 ServiceManager 里的服务名（"display"、"input"、"activity_task"），iface 是 AIDL 接口的类名。 */
    static Object get(String name, String iface) throws ReflectiveOperationException {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("系统服务 " + name + " 不在（框架可能正在重启）");
        Class<?> stub = Class.forName(iface + "$Stub");
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
    }
}
