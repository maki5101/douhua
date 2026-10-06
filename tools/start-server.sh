#!/usr/bin/env bash
# 在手机上以 shell 身份拉起豆花服务（后台常驻，手机重启后要重新运行）。
# 手机用环境变量 ANDROID_SERIAL 指定。服务日志在手机的 /data/local/tmp/douhua-server.log。
# 加 --debug：服务每秒记一行鼠标包统计，查卡顿用。
set -euo pipefail

EXTRA=""
[ "${1:-}" = "--debug" ] && EXTRA="debug"

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
