#!/usr/bin/env bash
# 在手机上以 shell 身份拉起豆花服务（后台常驻，手机重启后要重新运行）。服务日志在手机的 /data/local/tmp/douhua-server.log，
# 上一次的日志改名成 douhua-server.log.prev。
#
# 已经在运行时先做健康检查：进程在、但端口没在监听，或者有连接一直排着没被接走（服务主循环卡住了），
# 就自动重启（先尽力取一份线程堆栈到 /data/local/tmp/douhua-server-stuck.txt）；正常就什么都不做。
#
# 用法：tools/start-server.sh [--debug] [--restart] [-s <序列号>]
#   --debug    服务每秒记一行鼠标包统计，查卡顿用
#   --restart  不管健康检查结果，强制重启
#   -s         指定手机；不给就看 ANDROID_SERIAL，只连着一台就用它，连着多台会列出来让你选（见 tools/adb-device.sh）
set -euo pipefail

EXTRA=""
SERIAL=""
RESTART=0
while [ $# -gt 0 ]; do
    case "$1" in
        --debug) EXTRA="debug" ;;
        --restart) RESTART=1 ;;
        -s) SERIAL="${2:-}"; [ -n "$SERIAL" ] || { echo "-s 后面要跟手机的序列号（adb devices 里第一列）。" >&2; exit 2; }; shift ;;
        *) echo "不认识的参数：$1（可用 --debug、--restart、-s <序列号>）" >&2; exit 2 ;;
    esac
    shift
done
TOOLS="$(dirname "$0")"
. "$TOOLS/adb-device.sh"
pick_device "$SERIAL" || exit 1
echo "手机：$ANDROID_SERIAL" >&2

PKG=dev.douhua
LOG=/data/local/tmp/douhua-server.log
STACK=/data/local/tmp/douhua-server-stuck.txt
PORT=37601
PORT_HEX=92E1   # 37601 的十六进制，/proc/net/tcp 里就是这么写的

server_pid() {
    adb shell pidof douhua_server | tr -d '\r' || true
}

# 端口 37601 的 accept 队列里排着几个连接（还没被服务接走的）；没在监听就什么都不输出、返回 1。
# /proc/net/tcp6（Java 的 socket 是双栈的，多半在这里）和 /proc/net/tcp 里，st=0A（LISTEN）那一行
# 第 5 列是 tx_queue:rx_queue，监听的 socket 的 rx_queue 就是 accept 队列的长度（十六进制）。
listen_queue() {
    local rx
    rx="$(adb shell cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null | tr -d '\r' \
        | awk -v port=":$PORT_HEX" '$4 == "0A" && substr($2, length($2) - 4) == port { split($5, q, ":"); print q[2]; exit }' \
        || true)"
    [ -n "$rx" ] || return 1
    echo $((16#$rx))
}

PID="$(server_pid)"
if [ -n "$PID" ]; then
    STUCK=""
    if [ "$RESTART" = 1 ]; then
        STUCK="按 --restart 强制重启"
    elif ! listen_queue >/dev/null; then
        STUCK="进程在，但端口 $PORT 没有在监听"
    else
        # 每秒看一次、共 3 次，只要有一次队列是空的就算正常（新服务的主线程只管 accept，平时排不起来）
        QUEUED=0
        for i in 1 2 3; do
            if ! QUEUED="$(listen_queue)"; then
                STUCK="进程在，但端口 $PORT 没有在监听"
                break
            fi
            [ "$QUEUED" -gt 0 ] || break
            [ "$i" = 3 ] || sleep 1
        done
        if [ -z "$STUCK" ] && [ "$QUEUED" -gt 0 ]; then
            STUCK="有 $QUEUED 个连接排队、连着 3 次（隔 1 秒）看都没被接走，服务主循环卡住了"
        fi
    fi
    if [ -z "$STUCK" ]; then
        echo "豆花服务已经在运行（pid $PID），端口正常接连接；要强制重启加 --restart。" >&2
        exit 0
    fi
    echo "豆花服务（pid $PID）要重启：$STUCK。" >&2
    if [ "$RESTART" = 0 ]; then
        # 尽力留一份线程堆栈，查是卡在哪（shell 能不能对它用 debuggerd 没验证过，取不到也照样重启）
        if adb shell "timeout 15 debuggerd -j ${PID%% *} > $STACK 2>&1"; then
            echo "线程堆栈存在手机的 $STACK（adb pull 下来看）。" >&2
        else
            echo "没取到线程堆栈（debuggerd 失败），照样重启。" >&2
        fi
    fi
    "$TOOLS/stop-server.sh" -s "$ANDROID_SERIAL"
fi

APK="$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
if [ -z "$APK" ]; then
    echo "手机上没装豆花。先运行：tools/build.sh --install" >&2
    exit 1
fi
APP_UID="$(adb shell pm list packages -U "$PKG" | tr -d '\r' \
    | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | head -1)"

# 上一次的日志留一份（卡住现场的日志不被覆盖）
adb shell "if [ -f $LOG ]; then mv -f $LOG $LOG.prev; fi"
# setsid：服务自成一个会话，adb 连接断开时 adbd 拆掉的那组进程里没有它（手机自己拉起时实测：不加的话断开就被带走）
adb shell "CLASSPATH=$APK setsid nohup app_process / --nice-name=douhua_server dev.douhua.server.Server uid=$APP_UID $EXTRA >$LOG 2>&1 </dev/null &"
# 最多等 3 秒，确认端口在监听
LISTENING=0
for _ in 1 2 3 4 5 6; do
    sleep 0.5
    if listen_queue >/dev/null; then
        LISTENING=1
        break
    fi
done
adb shell cat "$LOG" >&2
if [ "$LISTENING" = 0 ] || [ -z "$(server_pid)" ]; then
    echo "豆花服务没起来（3 秒内端口 $PORT 没有在监听），看上面的日志。" >&2
    exit 1
fi
