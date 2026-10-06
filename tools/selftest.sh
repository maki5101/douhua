#!/usr/bin/env bash
# 不开界面，测 App 能不能连上豆花服务。手机用 ANDROID_SERIAL 指定。
# 连上的瞬间服务会造一次虚拟鼠标，断开即移除，不移动、不点击。
set -euo pipefail

adb shell am broadcast -f 0x20 -n dev.douhua/.SelfTestReceiver | tr -d '\r' | sed -n 's/.*data="\(.*\)"/\1/p'
