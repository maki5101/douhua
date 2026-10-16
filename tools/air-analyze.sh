#!/usr/bin/env bash
# 分析 tools/air-log.sh 记下的一次双日志：
#   1. 送达节奏：手机每帧什么时候发、电脑内核什么时候收到（卡不卡、排不排队）；
#   2. 用手机记下的原始转动（侧倾补偿后、消抖前）按一批参数重放真正的 AirPointer，算静止抖动、2Hz / 5Hz 以上的晃、
#      速度落后、距离保留，挑各档最稳的组合（tools/airlog/Replay.java，参数网格写在里面）。
# 用法：tools/air-analyze.sh [.private/airlog/<时间>]（默认最新一次）。要 python3 和 JDK 17（同 tools/test.sh）。
set -euo pipefail
cd "$(dirname "$0")/.."
d="${1:-$(ls -d .private/airlog/*/ | tail -1)}"
d="${d%/}"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then JDK="$JAVA_HOME"; else JDK="$(ls -d "$HOME"/.gradle/jdks/*17*/ 2>/dev/null | head -1)"; JDK="${JDK%/}"; fi
[ -x "$JDK/bin/javac" ] || { echo "找不到 JDK 17（需要 javac），同 tools/test.sh" >&2; exit 1; }
echo "== $d"
python3 tools/airlog/parse.py "$d"
python3 tools/airlog/delivery.py "$d"
python3 tools/airlog/capacity.py "$d"
python3 tools/airlog/segments.py "$d"
cls="$(mktemp -d)"
trap 'rm -rf "$cls"' EXIT
"$JDK/bin/javac" -encoding UTF-8 -d "$cls" src/dev/douhua/AirPointer.java tools/airlog/Replay.java
"$JDK/bin/java" -cp "$cls" Replay "$d/segs.csv" check
echo "重放参数网格（约一分钟）……"
"$JDK/bin/java" -cp "$cls" Replay "$d/segs.csv" grid > "$d/grid.csv"
python3 tools/airlog/rank.py "$d/grid.csv" --base 1.80,2.50,1.20,3.0,250,0.25
