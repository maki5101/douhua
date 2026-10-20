package dev.douhua.server;

/**
 * 新连接来时怎么对待还在的旧会话。纯 Java，能离线测。
 *
 * 旧会话 {@link #KICK_SILENT_MS} 以上没收到任何消息（App 空闲时也每 2 秒发一个保活）→ 多半是死连接
 * （App 进程没了、断开的包被 Doze 掐掉，服务这边一直停在"已连接"），顶掉它；
 * 还在说话 → 给新连接回"忙"，旧会话不动。这样死连接照样被立刻顶掉，
 * 而两个豆花窗口、自测工具和正在用的 App 之间不会来回互顶（每次互顶都要释放、重新接管一次触摸屏，重建一次虚拟设备）。
 */
final class SessionPolicy {
    static final int ACCEPT = 0;
    static final int KICK_OLD = 1;
    static final int BUSY = 2;

    /** 旧会话沉默这么久，新连接就能顶掉它。要大于 App 的保活间隔、小于服务的读超时。 */
    static final long KICK_SILENT_MS = 5000;
    /**
     * 顶掉以后最多等旧会话收尾这么久：等不到、它又还占着触摸屏，服务就退出。
     * 要小于 App 等 hello 的时间（{@link dev.douhua.Proto#HELLO_TIMEOUT_MS}），不然 App 会误报"服务没响应"。
     */
    static final long OLD_SESSION_WAIT_MS = 2000;

    private SessionPolicy() {}

    /** oldRunning：有旧会话而且还没收完尾；oldSilentMs：它多久没收到消息了。 */
    static int admit(boolean oldRunning, long oldSilentMs) {
        if (!oldRunning) return ACCEPT;
        return oldSilentMs >= KICK_SILENT_MS ? KICK_OLD : BUSY;
    }
}
