#!/usr/bin/env bash
# 停掉手机上的豆花服务。虚拟鼠标随之移除，按住的键也会松开。
# 先发 SIGTERM，约 3 秒还没退出就 kill -9（卡住的服务可能不响应）；确认进程没了才返回。
#
# 用法：tools/stop-server.sh [-s <序列号>]（选哪台手机同 start-server.sh）
set -euo pipefail

SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="${2:-}"; [ -n "$SERIAL" ] || { echo "-s 后面要跟手机的序列号（adb devices 里第一列）。" >&2; exit 2; }; shift ;;
        *) echo "不认识的参数：$1（可用 -s <序列号>）" >&2; exit 2 ;;
    esac
    shift
done
. "$(dirname "$0")/adb-device.sh"
pick_device "$SERIAL" || exit 1

server_pid() {
    adb shell pidof douhua_server | tr -d '\r' || true
}

PID="$(server_pid)"
if [ -z "$PID" ]; then
    echo "豆花服务没在运行。" >&2
    exit 0
fi
adb shell kill $PID || true
# 每 0.2 秒看一次，最多 15 次（加上 adb 本身的耗时，约 3–5 秒）
for _ in $(seq 15); do
    sleep 0.2
    if [ -z "$(server_pid)" ]; then
        echo "豆花服务已停止（pid $PID）。" >&2
        exit 0
    fi
done
LEFT="$(server_pid)"
if [ -n "$LEFT" ]; then
    adb shell kill -9 $LEFT || true
    for _ in 1 2 3 4 5; do
        sleep 0.2
        [ -n "$(server_pid)" ] || break
    done
fi
if [ -n "$(server_pid)" ]; then
    echo "豆花服务（pid $LEFT）连 kill -9 都没结束掉，看一下 adb shell ps -A | grep douhua。" >&2
    exit 1
fi
echo "豆花服务没响应 SIGTERM，已强制结束（pid $LEFT）。" >&2
