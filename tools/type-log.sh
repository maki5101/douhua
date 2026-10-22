#!/usr/bin/env bash
# 打字测试记录：手机这边每次按外接键盘界面的触点（TouchRouter 的触点日志，只在调试包里有，logcat 标签 douhua-keys），
# 电脑这边网页收到的每个按键（tools/typelog/typing.html，句子在 tools/typelog/sentences.txt）。
# 结果放进 .private/typelog/<时间>/，之后用 tools/type-analyze.sh 分析（误按率、每个键的落点偏移、缝分给最近的键前后对比）。
#
# 用法：tools/type-log.sh [--dex] [-s 序列号]
#   默认：蓝牙模式，手机连着这台电脑，页面开在这台电脑的浏览器里。
#   --dex：DeX 模式，用 adb reverse 把页面的端口转到手机上，在 DeX 的浏览器里打开同一个地址（页面的时间用手机的时钟，分析时自动换算）。
# 前提：
#   - 手机装的是调试包（tools/build.sh --install）；正式包里根本没有触点日志的开关。
#   - 服务开着的话要和 App 同一版（Proto.VERSION）：装完要重启服务 tools/stop-server.sh && tools/start-server.sh，
#     不然 App 连不上服务（DeX 模式下用不了；蓝牙模式会退回 App 自己收触摸，照样能记）。
#   - 电脑的输入法切成英文（拼音句子也是一个个字母原样打，页面会提示）。
# 触点日志等于按键记录：开着是租期（10 秒），这里每 3 秒续一次；退出（含 Ctrl+C）时立刻关，脚本被强杀也最多 10 秒自己关。
# 日志只进 logcat（App 和服务都不落盘），电脑这边收进 .private/（不进 git）。
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/adb-device.sh

serial=""
dex=0
while [ $# -gt 0 ]; do
    case "$1" in
        -s) serial="$2"; shift 2 ;;
        --dex) dex=1; shift ;;
        *) echo "不认识的参数：$1（用法：tools/type-log.sh [--dex] [-s 序列号]）" >&2; exit 2 ;;
    esac
done
pick_device "$serial"

adb_lost() {
    echo "手机的 adb 连接断了（$ANDROID_SERIAL）。无线调试常会自己掉：在手机的开发者选项里确认\"无线调试\"开着，" >&2
    echo "看它显示的 IP:端口，电脑上 adb connect <IP:端口> 连上（adb devices 里是 device）再重跑。" >&2
    exit 1
}
[ "$(adb get-state 2>/dev/null)" = device ] || adb_lost

out=".private/typelog/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
[ "$dex" = 1 ] && : > "$out/dex"   # 告诉分析：页面开在手机上，它记的时间是手机的时钟
echo "结果放在 $out"

pids=()
port=""
renew=""

cleanup() {
    set +e
    # 先停续期并等它真退出（在路上的那条"开"跑完才退），最后再发"关"，免得"开"比"关"晚到、又开 10 秒
    [ -n "$renew" ] && { kill "$renew" 2>/dev/null; wait "$renew" 2>/dev/null; }
    adb shell am broadcast -a dev.douhua.DEBUG_KEYLOG --ez on false >/dev/null 2>&1
    [ -n "$port" ] && adb reverse --remove "tcp:$port" >/dev/null 2>&1
    for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
    wait 2>/dev/null
    echo
    echo "已停止记录：手机的触点日志关了。文件在 $out，分析：tools/type-analyze.sh $out"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ── 对时：电脑发出 / 手机读表 / 电脑收回，记 10 次，分析时取来回最快的那次 ──
echo "对时……"
for _ in $(seq 10); do
    t0=$(date +%s%N)
    p=$(adb shell 'date +%s%N; echo $EPOCHREALTIME' 2>/dev/null | tr '\r\n' '  ')
    [ -n "${p// /}" ] || adb_lost
    t1=$(date +%s%N)
    echo "$t0 $p $t1" >> "$out/clock.txt"
done

# ── 手机：先开始收日志，再打开触点日志（打开时 App 和服务各记一次键盘布局，要收得到） ──
adb logcat -v epoch,usec -T 1 -s douhua-keys:V douhua:V > "$out/phone.log" 2>&1 &
pids+=($!)
sleep 0.5
adb shell am broadcast -a dev.douhua.DEBUG_KEYLOG --ez on true >/dev/null
# 租期 10 秒：每 3 秒续一次（豆花中途重启丢了开关，下一次续期也会重新打开）
# 收到 TERM 时：在 sleep 里就马上退；adb 正在跑就等它跑完再退（cleanup 等这个子 shell 退出后才发"关"）
( trap 'exit 0' TERM; while :; do sleep 3 & wait $!; adb shell am broadcast -a dev.douhua.DEBUG_KEYLOG --ez on true >/dev/null 2>&1; done ) &
renew=$!
pids+=($renew)

# ── 电脑：网页 ──
python3 tools/typelog/server.py "$out" &
server=$!
pids+=($server)
for _ in $(seq 30); do [ -s "$out/url.txt" ] && break; sleep 0.1; done
if [ ! -s "$out/url.txt" ]; then echo "打字测试的本机服务没起来（看上面的报错）" >&2; exit 1; fi
url=$(cat "$out/url.txt")
echo
echo "手机：打开豆花儿的外接键盘界面（蓝牙模式连着这台电脑；服务开着的话要是新装的同一版）。电脑的输入法切成英文。"
if [ "$dex" = 1 ]; then
    port=$(echo "$url" | sed -E 's#.*:([0-9]+)/.*#\1#')
    adb reverse "tcp:$port" "tcp:$port" >/dev/null
    echo "在 DeX 的浏览器里打开 $url （已用 adb reverse 把端口转到手机上），点\"开始\"。"
else
    echo "在电脑的浏览器里打开 $url （下面试着自动打开），点\"全屏\"再点\"开始\"。"
    xdg-open "$url" >/dev/null 2>&1 &
fi
echo "页面里点\"完成并保存\"（或 30 句全打完）会自动保存，这边跟着结束；每打完一句也先存一份，中途不想测了按 Ctrl+C。"
wait "$server" || true
