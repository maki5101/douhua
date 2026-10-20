package dev.douhua.server;

import dev.douhua.Proto;

/**
 * 独占条件里的心跳那一条，和释放时的原因码。纯 Java，离线测（{@code tools/test.sh}）。
 *
 * 只有心跳（{@link Proto#CMD_HEARTBEAT}，App 主线程每秒一次）和布局（{@link Proto#CMD_LAYOUT}）算心跳；
 * 保活（{@link Proto#CMD_PING}，App 连接线程发）和别的命令都不算——App 主线程卡住、被挂起时连接线程照样发保活，
 * 要是保活也刷新心跳，触摸屏就不释放了（外屏锁死）。
 *
 * 时间用调用方的单一时钟（Session 传 SystemClock.uptimeMillis）。命令线程写、监管线程读。
 */
final class GrabCondition {
    /** 最近一次心跳；一开始算没收到过（和原来 Session 的字段初值一样是 0）。 */
    private volatile long lastHeartbeat;

    /** 处理完一条命令后调：只有心跳和布局刷新心跳。 */
    void onMessage(int cmd, long now) {
        if (cmd == Proto.CMD_HEARTBEAT || cmd == Proto.CMD_LAYOUT) lastHeartbeat = now;
    }

    /** 心跳没超过 {@link Proto#HEARTBEAT_TIMEOUT_MS}。 */
    boolean fresh(long now) {
        return now - lastHeartbeat < Proto.HEARTBEAT_TIMEOUT_MS;
    }

    /** 释放触摸屏时报给 App 的原因（Proto.GRAB_*），按先后判断：断开 → 没响应 → 暂停 → 转屏 → 其它。 */
    int releaseReason(boolean alive, boolean active, boolean sameScreen, long now) {
        if (!alive) return Proto.GRAB_DISCONNECTED;
        if (!fresh(now)) return Proto.GRAB_UNRESPONSIVE;
        if (!active) return Proto.GRAB_PAUSED;
        if (!sameScreen) return Proto.GRAB_ROTATING;
        return Proto.GRAB_RELEASED;
    }
}
