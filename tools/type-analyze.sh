#!/usr/bin/env bash
# 分析 tools/type-log.sh 记下的一次打字测试：
#   1. tools/typelog/analyze.py：对齐、误按率（按空 / 按错没改 / 按错改过）、每个键的落点偏移、电脑有没有丢键，
#      写 presses.csv、offsets.csv、replay.txt；
#   2. tools/typelog/KeyReplay.java：用真正的 TouchRouter（和 src 里同一份源码一起编译）重放这些触点——
#      先核对"新规则"和手机日志里实际用的键一致，再比旧规则（缝里没反应）和新规则（缝分给最近的键）。
# 用法：tools/type-analyze.sh [.private/typelog/<时间>]（默认最新一次）。要 python3 和 JDK 17（同 tools/test.sh）。
set -euo pipefail
cd "$(dirname "$0")/.."
d="${1:-$(ls -d .private/typelog/*/ 2>/dev/null | tail -1)}"
[ -n "$d" ] || { echo "还没有记录：先跑 tools/type-log.sh" >&2; exit 1; }
d="${d%/}"
[ -s "$d/typing.json" ] || { echo "$d 里没有 typing.json（页面没保存过一句？）" >&2; exit 1; }
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then JDK="$JAVA_HOME"; else JDK="$(ls -d "$HOME"/.gradle/jdks/*17*/ 2>/dev/null | head -1)"; JDK="${JDK%/}"; fi
[ -x "$JDK/bin/javac" ] || { echo "找不到 JDK 17（需要 javac），同 tools/test.sh" >&2; exit 1; }
python3 tools/typelog/analyze.py "$d"
cls="$(mktemp -d)"
trap 'rm -rf "$cls"' EXIT
"$JDK/bin/javac" -encoding UTF-8 -d "$cls" src/dev/douhua/Proto.java src/dev/douhua/GestureEngine.java \
    src/dev/douhua/Hid.java src/dev/douhua/TouchRouter.java tools/typelog/KeyReplay.java
"$JDK/bin/java" -cp "$cls" KeyReplay "$d/replay.txt" check || echo "（上面不一致的触点：手机上跑的规则和这份源码不是同一版？）"
"$JDK/bin/java" -cp "$cls" KeyReplay "$d/replay.txt" compare
