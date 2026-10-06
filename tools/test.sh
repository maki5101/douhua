#!/usr/bin/env bash
# 离线测试：手势识别、多点触控解码、触摸屏查找都不依赖 Android，直接用电脑上的 JDK 编译运行。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JDK="$JAVA_HOME"
else
    JDK="$(ls -d "$HOME"/.gradle/jdks/*17*/ 2>/dev/null | head -1)"
    JDK="${JDK%/}"
fi
if [ -z "$JDK" ] || [ ! -x "$JDK/bin/javac" ]; then
    echo "找不到 JDK 17（需要 javac）。装一个：sudo apt install openjdk-17-jdk-headless，或者用 JAVA_HOME 指过去。" >&2
    exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# 只编译不依赖 Android 的部分：手势识别、多点触控解码、触摸屏查找
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT" \
    src/dev/douhua/Proto.java src/dev/douhua/GestureEngine.java \
    src/dev/douhua/server/MtDecoder.java src/dev/douhua/server/TouchDevice.java \
    src/dev/douhua/server/ScreenRotation.java src/dev/douhua/KeyboardSnap.java \
    test/dev/douhua/GestureEngineTest.java test/dev/douhua/server/ServerPartsTest.java \
    test/dev/douhua/KeyboardSnapTest.java
"$JDK/bin/java" -cp "$OUT" dev.douhua.GestureEngineTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.server.ServerPartsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyboardSnapTest
