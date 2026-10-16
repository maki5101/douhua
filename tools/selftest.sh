#!/usr/bin/env bash
# 不开界面，测 App 能不能连上豆花服务。
# 连上的瞬间服务会造一次虚拟鼠标，断开即移除，不移动、不点击。
#
# 用法：tools/selftest.sh [-s <序列号>]（选哪台手机同 start-server.sh）
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

adb shell am broadcast -f 0x20 -n dev.douhua/.SelfTestReceiver | tr -d '\r' | sed -n 's/.*data="\(.*\)"/\1/p'
