#!/usr/bin/env bash
# 空中鼠标双日志：手机这边每帧陀螺仪 / 每次发送（AirMouse 的调试日志，只在调试包里有），
# 电脑这边光标实际走到哪（KWin 脚本记光标坐标），能读的话再加内核收到的原始报文（evtest，不独占）。
# 两种测法，结果都放进 .private/airlog/<时间>/：
#   默认：按终端提示一组一组随手画（横线、竖线、圆、方、三角……），每组之间按回车；
#   --trace：描红——浏览器里开全屏页面，对着固定图案描、实时看到自己的轨迹；分三轮，每轮临时换一组参数
#            （盲测，顺序打乱，记在 plan.json），最后选哪轮最顺手（tools/airlog/trace.html / trace_server.py）；
#   --board：白板——用手机现在的设置随便画竖线、横线、斜线（可来回折返），描圆和三角，画多少都行，存 board.json（tools/airlog/board.html）。
#
# 用法：tools/air-log.sh [--trace|--board] [-s 序列号]
# 前提：电脑是 KDE Wayland；手机装的是调试包、蓝牙模式连着这台电脑；手机能 adb（无线调试也行）。
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/adb-device.sh

serial=""
trace=0
while [ $# -gt 0 ]; do
    case "$1" in
        -s) serial="$2"; shift 2 ;;
        --trace) trace=1; page=trace; shift ;;
        --board) trace=1; page=board; shift ;;
        *) echo "不认识的参数：$1（用法：tools/air-log.sh [--trace|--board] [-s 序列号]）" >&2; exit 2 ;;
    esac
done
pick_device "$serial"

adb_lost() {
    echo "手机的 adb 连接断了（$ANDROID_SERIAL）。无线调试常会自己掉：在手机的开发者选项里确认\"无线调试\"开着，" >&2
    echo "看它显示的 IP:端口，电脑上 adb connect <IP:端口> 连上（adb devices 里是 device）再重跑。" >&2
    exit 1
}
[ "$(adb get-state 2>/dev/null)" = device ] || adb_lost

out=".private/airlog/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
echo "结果放在 $out"

KWIN_NAME="douhua-cursor-log"
pids=()

cleanup() {
    set +e
    adb shell am broadcast -a dev.douhua.DEBUG_AIRLOG --ez on false >/dev/null 2>&1
    qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript "$KWIN_NAME" >/dev/null 2>&1
    for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
    wait 2>/dev/null
    echo
    echo "已停止记录：手机调试日志关了，KWin 脚本卸了。文件在 $out"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

mark() {
    echo "$(date +%s%N) $*" >> "$out/marks.txt"
}

# ── 对时：电脑发出 / 手机读表 / 电脑收回，记 10 次，分析时取来回最快的那次 ──
echo "对时……"
for _ in $(seq 10); do
    t0=$(date +%s%N)
    p=$(adb shell 'date +%s%N; echo $EPOCHREALTIME' 2>/dev/null | tr '\r\n' '  ')
    [ -n "${p// /}" ] || adb_lost
    t1=$(date +%s%N)
    echo "$t0 $p $t1" >> "$out/clock.txt"
done

# ── 手机：打开调试日志，从现在起收 douhua-air / douhua 两个标签 ──
adb shell am broadcast -a dev.douhua.DEBUG_AIRLOG --ez on true --ei gap -1 >/dev/null
adb logcat -v epoch,usec -T 1 -s douhua-air:V douhua:V > "$out/phone.log" 2>&1 &
pids+=($!)
# 调试日志的开关在豆花的内存里，豆花中途重启（打开、被系统回收）就丢了——2026-10-16 白板那次整段没记上陀螺仪。
# 记录期间每 3 秒补发一次"开"（只带 on，不动间隔 / 换算 / 参数）
( while sleep 3; do adb shell am broadcast -a dev.douhua.DEBUG_AIRLOG --ez on true >/dev/null 2>&1; done ) &
pids+=($!)

# ── 电脑：KWin 脚本把光标坐标打到日志里（journalctl 带微秒时间戳） ──
cat > "$out/cursor.js" <<'JS'
console.info("douhua-screen " + workspace.virtualScreenSize.width + " " + workspace.virtualScreenSize.height);
workspace.cursorPosChanged.connect(function () {
    var p = workspace.cursorPos;
    console.info("douhua-cur " + p.x + " " + p.y);
});
JS
journalctl --user -f -n 0 -o short-unix | grep --line-buffered -E 'douhua-(cur|screen)' > "$out/cursor.log" &
pids+=($!)
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript "$KWIN_NAME" >/dev/null 2>&1 || true
id=$(qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.loadScript "$(realpath "$out/cursor.js")" "$KWIN_NAME")
qdbus6 org.kde.KWin "/Scripting/Script$id" org.kde.kwin.Script.run >/dev/null

# ── 电脑：内核收到的原始报文（可选）：只看蓝牙（Bus=0005）、带绝对坐标的输入设备，只读、不独占 ──
node=""
while IFS='|' read -r name ev; do
    [ -z "$ev" ] && continue
    echo "蓝牙输入设备：$name（/dev/input/$ev）"
    [ -z "$node" ] && node="/dev/input/$ev"   # 有好几个就用第一个
done < <(awk 'BEGIN{RS=""; FS="\n"} /Bus=0005/ && /B: ABS=/ {
    n = ""; e = ""
    for (i = 1; i <= NF; i++) {
        if ($i ~ /^N: Name=/) { n = $i; sub(/^N: Name="/, "", n); sub(/"$/, "", n) }
        if ($i ~ /^H: Handlers=/ && match($i, /event[0-9]+/)) e = substr($i, RSTART, RLENGTH)
    }
    print n "|" e
}' /proc/bus/input/devices)
if [ -n "$node" ]; then
    if [ ! -r "$node" ]; then
        echo "要记内核收到的原始报文，需要给你这个用户读 $node 的权限（只读，不独占；手机断开重连后要重给）："
        echo "  sudo setfacl -m u:$USER:r $node"
        read -r -p "现在执行吗？[y/N] " yn
        if [ "$yn" = "y" ] || [ "$yn" = "Y" ]; then sudo setfacl -m "u:$USER:r" "$node"; fi
    fi
    if [ -r "$node" ]; then
        evtest "$node" > "$out/evdev.log" 2>&1 &
        pids+=($!)
        echo "在记 $node 的原始报文"
    else
        echo "跳过原始报文，只记光标"
    fi
else
    echo "没找到蓝牙的绝对坐标设备（手机没连上这台电脑？），只记光标"
fi

if [ "$trace" = 1 ]; then
    python3 tools/airlog/trace_server.py "$out" "$page" &
    server=$!
    pids+=($server)
    for _ in $(seq 30); do [ -s "$out/url.txt" ] && break; sleep 0.1; done
    if [ ! -s "$out/url.txt" ]; then echo "描红服务没起来（看上面的报错）" >&2; exit 1; fi
    url=$(cat "$out/url.txt")
    echo
    echo "在电脑的浏览器里打开 $url （下面试着自动打开），点\"全屏\"再点\"开始\"。"
    echo "页面里点\"完成并保存\"（描红是描完、选完）会自动保存，这边跟着结束；中途不想测了按 Ctrl+C。"
    xdg-open "$url" >/dev/null 2>&1 &
    wait "$server" || true
    exit 0
fi

step() {
    echo
    echo "▶ $1"
    read -r -p "  画完按回车……" _
    mark "done $1"
}

echo
echo "准备：手机打开空中鼠标页，先点一下\"回正中\"。每画一个图形 = 按住圆钮画完、松开；一组画完按回车。"
read -r -p "准备好了按回车开始……" _
mark "start gap=default"
step "A1 遥控器拿法（顶边指屏幕）：横线 从左到右 ×3"
step "A2 遥控器拿法：竖线 从上到下 ×3"
step "A3 遥控器拿法：顺时针画圆 ×3"
step "A4 遥控器拿法：画方 ×3"
step "A5 遥控器拿法：画三角 ×3"
step "A6 遥控器拿法：按住圆钮尽量不动 5 秒 ×1"
step "B1 换个拿法（歪着 / 抬高）：横线 ×3"
step "B2 换个拿法：竖线 ×3"
step "B3 换个拿法：顺时针画圆 ×3"

adb shell am broadcast -a dev.douhua.DEBUG_AIRLOG --ez on true --ei gap 12 >/dev/null
mark "start gap=12"
echo
echo "第二轮：激光笔式改回旧的发送间隔（至少隔 12ms；现在默认 8ms），对比卡不卡。"
step "C1 遥控器拿法：横线 ×3"
step "C2 遥控器拿法：顺时针画圆 ×3"
step "C3 遥控器拿法：画方 ×3"
mark "end"
