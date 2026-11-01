#!/usr/bin/env bash
# 主机实测扫描：豆花儿逐键发给主机（每个出字的位置 × 普通 / Shift / 第三层），主机上开一个网页记下实际出了什么字，
# 再和生成的键帽数据（res/raw/kbd_*.txt）逐格比，看实体键盘方案里最没把握的那几条前提成不成立。说明见 tools/kbd/README.md。
#
# 用法：tools/kbd-scan.sh --host ipad|mac|linux|win|android --layout us|de [--desktop] [--lan|--local] [-s 序列号]
#                         [--hold 毫秒] [--gap 毫秒]
#   --host：主机是什么系统（不自动认，自己选）。
#   --layout：主机上设的键盘布局（扫之前在主机上设好同一种）。
#   --desktop：桌面模式（主机就是手机自己）：等于 --host android；用 adb reverse 把页面的端口转到手机上，
#     在手机的浏览器（分屏 / 外接屏）里打开。
#   --lan / --local：页面听所有网卡（别的设备上打开）还是只听本机。默认：iPad / Mac / Windows / 蓝牙连的安卓 → --lan，
#     linux（这台电脑自己就是主机）和桌面模式 → --local。
#   --hold / --gap：每个键按住多久、松开后隔多久按下一个（默认 40 / 70 毫秒；主机来不及时调大）。
# 前提：手机装的是调试包（tools/build.sh --install；正式包里没有扫描）；豆花儿开着，蓝牙模式连着这台主机，
#   桌面模式连着服务。手机由 adb 操作（连着多台会让你选，见 tools/adb-device.sh）。
# 结果在 .private/kbdscan/<时间>-<主机>-<布局>/（scan.json；report.txt、compare.json 是分析），扫完自动跑 tools/kbd/compare.py。
# 安全：手机一次只发一小段（页面收完才要下一段），页面一失去焦点就叫手机停；只发出字的位置和 Shift / Alt，不发 Ctrl / ⌘ / Enter。
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/adb-device.sh

usage="用法：tools/kbd-scan.sh --host ipad|mac|linux|win|android --layout us|de [--desktop] [--lan|--local] [-s 序列号] [--hold 毫秒] [--gap 毫秒]"
serial=""
host=""
layout=""
desktop=0
lan=""
extra=()
while [ $# -gt 0 ]; do
    case "$1" in
        -s) serial="$2"; shift 2 ;;
        --host) host="$2"; shift 2 ;;
        --layout) layout="$2"; shift 2 ;;
        --desktop) desktop=1; shift ;;
        --lan) lan=1; shift ;;
        --local) lan=0; shift ;;
        --hold|--gap) extra+=("$1" "$2"); shift 2 ;;
        *) echo "不认识的参数：$1（$usage）" >&2; exit 2 ;;
    esac
done
if [ "$desktop" = 1 ]; then
    [ -z "$host" ] || [ "$host" = android ] || { echo "--desktop 时主机就是手机自己（安卓），不能再选 --host $host" >&2; exit 2; }
    host=android
fi
case "$host" in ipad|mac|linux|win|android) ;; *) echo "要选主机系统 --host（不自动认）。$usage" >&2; exit 2 ;; esac
[ -n "$layout" ] || { echo "要选布局 --layout（主机上设的那种）。$usage" >&2; exit 2; }
if [ -z "$lan" ]; then
    if [ "$host" = linux ] || [ "$desktop" = 1 ]; then lan=0; else lan=1; fi
fi

pick_device "$serial"
if [ "$(adb get-state 2>/dev/null)" != device ]; then
    echo "手机的 adb 连接断了（$ANDROID_SERIAL）：USB 插好，或者无线调试 adb connect <IP:端口> 后再跑。" >&2
    exit 1
fi
# 先整段读完再找（grep -q 提前退出会让 adb 收到 SIGPIPE，pipefail 下误报）
flags=$(adb shell dumpsys package dev.douhua 2>/dev/null | grep 'pkgFlags=' || true)
case "$flags" in
    *DEBUGGABLE*) ;;
    *) echo "提醒：手机上的豆花儿看起来不是调试包（或者没装），扫描的广播只有调试包收。先 tools/build.sh --install。" >&2 ;;
esac

out=".private/kbdscan/$(date +%Y%m%d-%H%M%S)-$host-$layout"
mkdir -p "$out"
echo "结果放在 $out"

server=""
port=""
cleanup() {
    set +e
    # 不管怎么退出都叫手机停（还在发的那一段不再发、按着的键松开）
    adb shell am broadcast -a dev.douhua.DEBUG_KEYSCAN --ez stop true >/dev/null 2>&1
    [ -n "$port" ] && adb reverse --remove "tcp:$port" >/dev/null 2>&1
    [ -n "$server" ] && kill "$server" 2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT
trap 'exit 130' INT TERM

args=("$out" --host "$host" --layout "$layout" ${extra[@]+"${extra[@]}"})
[ "$lan" = 1 ] && args+=(--lan)
python3 tools/kbd/scan_server.py "${args[@]}" &
server=$!
for _ in $(seq 30); do [ -s "$out/url.txt" ] && break; sleep 0.1; done
if [ ! -s "$out/url.txt" ]; then echo "扫描的本机小服务没起来（看上面的报错）" >&2; exit 1; fi
url=$(cat "$out/url.txt")

echo
echo "1. 主机上把键盘布局设成选的那种（页面上有提示），关掉输入法。"
echo "2. 手机上豆花儿开着；$([ "$desktop" = 1 ] && echo "桌面模式，服务连着" || echo "蓝牙连着这台主机")。扫描中别碰手机屏幕。"
if [ "$desktop" = 1 ]; then
    port=$(echo "$url" | sed -E 's#.*:([0-9]+)/.*#\1#')
    adb reverse "tcp:$port" "tcp:$port" >/dev/null
    echo "3. 在手机那边（分屏或外接屏）的浏览器里打开 $url （已用 adb reverse 把端口转到手机上），点\"开始\"。"
elif [ "$lan" = 1 ]; then
    echo "3. 在主机（$host）的浏览器里打开 $url （主机和这台电脑要在同一个局域网；打不开先看这台电脑的防火墙），点\"开始\"。"
else
    echo "3. 在这台电脑的浏览器里打开 $url （下面试着自动打开），点\"开始\"。"
    xdg-open "$url" >/dev/null 2>&1 &
fi
echo "扫完（或页面上点\"结束并保存\"）这边自动分析；中途不扫了按 Ctrl+C。"
wait "$server" || true
server=""
if [ -s "$out/scan.json" ]; then
    echo
    python3 tools/kbd/compare.py "$out"
else
    echo "没有扫描结果（页面没点开始？）。" >&2
fi
