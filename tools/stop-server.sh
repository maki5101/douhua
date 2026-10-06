#!/usr/bin/env bash
# 停掉手机上的豆花服务。虚拟鼠标随之移除，按住的键也会松开。
set -euo pipefail

PID="$(adb shell pidof douhua_server | tr -d '\r' || true)"
if [ -z "$PID" ]; then
    echo "豆花服务没在运行。" >&2
    exit 0
fi
adb shell kill $PID
echo "豆花服务已停止（pid $PID）。" >&2
