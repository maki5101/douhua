#!/usr/bin/env bash
# 在手机上以 shell 身份拉起豆花服务（后台常驻，手机重启后要重新运行）。服务日志在手机的 /data/local/tmp/douhua-server.log。
#
# 用法：tools/start-server.sh [--debug] [-s <序列号>]
#   --debug  服务每秒记一行鼠标包统计，查卡顿用
#   -s       指定手机；不给就看 ANDROID_SERIAL，只连着一台就用它，连着多台会列出来让你选（见 tools/adb-device.sh）
set -euo pipefail

EXTRA=""
SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --debug) EXTRA="debug" ;;
        -s) SERIAL="${2:-}"; [ -n "$SERIAL" ] || { echo "-s 后面要跟手机的序列号（adb devices 里第一列）。" >&2; exit 2; }; shift ;;
        *) echo "不认识的参数：$1（可用 --debug、-s <序列号>）" >&2; exit 2 ;;
    esac
    shift
done
. "$(dirname "$0")/adb-device.sh"
pick_device "$SERIAL" || exit 1
echo "手机：$ANDROID_SERIAL" >&2

PKG=dev.douhua
LOG=/data/local/tmp/douhua-server.log

if [ -n "$(adb shell pidof douhua_server | tr -d '\r' || true)" ]; then
    echo "豆花服务已经在运行。要重启先运行 tools/stop-server.sh。" >&2
    exit 0
fi

APK="$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
if [ -z "$APK" ]; then
    echo "手机上没装豆花。先运行：tools/build.sh --install" >&2
    exit 1
fi
APP_UID="$(adb shell pm list packages -U "$PKG" | tr -d '\r' \
    | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | head -1)"

adb shell "CLASSPATH=$APK nohup app_process / --nice-name=douhua_server dev.douhua.server.Server uid=$APP_UID $EXTRA >$LOG 2>&1 &"
sleep 1
adb shell cat "$LOG" >&2
if [ -z "$(adb shell pidof douhua_server | tr -d '\r' || true)" ]; then
    echo "豆花服务没起来，看上面的日志。" >&2
    exit 1
fi
