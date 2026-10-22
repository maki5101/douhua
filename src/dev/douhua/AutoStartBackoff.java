package dev.douhua;

/**
 * 自动启动的退避规则（纯 Java，离线测；状态本身由 {@link SelfStart} 存在私有 SharedPreferences 里，进程死了也记得）。
 *
 * 为什么要退避：豆花打开无线调试时，这个 Wi-Fi 要是没被信任，系统会弹"是否允许在此网络上使用无线调试"。
 * 在咖啡馆、公司这类网络上用户不会（也不该）选"始终允许"，自动的路径（打开豆花时的自动启动、开机任务、App 更新后的重启）
 * 要是每次都去开，就每次都弹。豆花读不到 Wi-Fi 名称（不要定位权限），分不清"这个网络"和"别的网络"，
 * 所以只能整体退避：被拒过一次（结果是 Wi-Fi 未信任）就不再由自动的路径去打开无线调试，
 * 直到用户手动启动（设置页"启动服务 / 重启服务"、配对成功后那次启动）成功一次。
 * "被拒"= 弹了框、一直没允许：宽限期（30 秒）过了先记上退避，之后盯守的 3 分钟里用户在系统弹框里点了"允许"、
 * 接着那次启动成功，也算用户确认过、解除退避（不然同一件事按点得快慢结果不同，设置页还会说"没被允许"）。
 *
 * 退避只管"豆花自己去打开无线调试"：无线调试本来就开着（用户自己开的，或者豆花开的、用户随后在弹窗里点了允许）时不会弹窗，照常走。
 * 手动启动被拒（弹了框、没允许）也进退避：下次自动启动照样会弹同一个框。
 */
final class AutoStartBackoff {
    private AutoStartBackoff() {
    }

    /**
     * 这次启动能不能由豆花自己打开无线调试。auto = 自动的路径；backoff = 现在在退避中。
     * 只在无线调试关着、豆花要去开的时候问；开着的不受限。
     */
    static boolean mayOpenWireless(boolean auto, boolean backoff) {
        return !(auto && backoff);
    }

    /**
     * 一次启动结束后退避标记的新值。
     * auto = 这次是自动的路径；allowedByUser = 这次是用户在系统弹框里点了"允许"、盯守接着的那次启动
     * （不管最初是自动还是手动，用户都亲手确认了这个网络，成功了按手动算）；
     * ok = 服务起来了；untrusted = 结果是 Wi-Fi 未信任（系统把豆花打开的无线调试关回去、宽限期里也没允许）。
     */
    static boolean after(boolean backoff, boolean auto, boolean allowedByUser, boolean ok, boolean untrusted) {
        if (untrusted) return true;
        if (ok && (!auto || allowedByUser)) return false;
        return backoff;
    }
}
