#!/usr/bin/env bash
# 停掉手机上的豆花服务。虚拟鼠标随之移除，按住的键也会松开。
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

PID="$(adb shell pidof douhua_server | tr -d '\r' || true)"
if [ -z "$PID" ]; then
    echo "豆花服务没在运行。" >&2
    exit 0
fi
adb shell kill $PID
echo "豆花服务已停止（pid $PID）。" >&2
