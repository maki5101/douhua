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
# 只编译不依赖 Android 的部分：手势识别、触摸分流、HID 报文、多点触控解码、触摸屏查找、空中鼠标、
# 服务的会话交接部件（独占锁、顶替策略、方向锁定、心跳和释放原因）、日志限频、带超时的子进程、实体键（设备挑选、判断状态机）、
# 打字测试的重放（KeyReplay）
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT" \
    src/dev/douhua/Proto.java src/dev/douhua/GestureEngine.java \
    src/dev/douhua/Hid.java src/dev/douhua/TouchRouter.java \
    src/dev/douhua/server/MtDecoder.java src/dev/douhua/server/TouchDevice.java \
    src/dev/douhua/server/ScreenRotation.java src/dev/douhua/KeyboardSnap.java \
    src/dev/douhua/server/TouchGrabLock.java src/dev/douhua/server/SessionPolicy.java \
    src/dev/douhua/server/RotationLock.java src/dev/douhua/server/LogThrottle.java \
    src/dev/douhua/server/Shell.java src/dev/douhua/server/GrabCondition.java \
    src/dev/douhua/server/KeyDevices.java src/dev/douhua/server/HardKeyGate.java \
    test/dev/douhua/GestureEngineTest.java test/dev/douhua/server/ServerPartsTest.java \
    test/dev/douhua/KeyboardSnapTest.java test/dev/douhua/TouchRouterTest.java \
    src/dev/douhua/HardKeys.java src/dev/douhua/AirPointer.java \
    test/dev/douhua/AirMouseTest.java \
    tools/typelog/KeyReplay.java
"$JDK/bin/java" -cp "$OUT" dev.douhua.GestureEngineTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.server.ServerPartsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyboardSnapTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.TouchRouterTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.AirMouseTest
# 打字测试的分析（tools/typelog/analyze.py）：造一份小日志测对齐和分类，再用真正的 TouchRouter 重放它导出的触点
python3 tools/typelog/analyze.py --selftest "$OUT/typelog"
"$JDK/bin/java" -cp "$OUT" KeyReplay "$OUT/typelog/replay.txt" check
