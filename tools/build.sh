#!/usr/bin/env bash
# 编译豆花 APK。不用 Gradle：javac → d8 → aapt2 → zipalign → apksigner，几秒就完。
#
# 用法：tools/build.sh             调试版，产物 build/douhua.apk（可调试，调试签名）
#      tools/build.sh --install   调试版，编译后装到手机，并重启豆花服务
#      tools/build.sh --release   正式版（不可调试，正式签名）；一般由 tools/package.sh 调用
# 手机用环境变量 ANDROID_SERIAL 指定（adb 自己认这个变量）。
# 正式签名：密钥库 DOUHUA_KEYSTORE（默认 .private/release.keystore），口令 DOUHUA_KEYSTORE_PASS（放 .env，见 .env.example）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

INSTALL=0
RELEASE=0
for arg in "$@"; do
    case "$arg" in
        --install) INSTALL=1 ;;
        --release) RELEASE=1 ;;
        *) echo "不认识的参数：$arg（可用 --install / --release）" >&2; exit 2 ;;
    esac
done
# 本机密钥只放 .env（不进 git）
if [ -f "$ROOT/.env" ]; then
    set -a
    . "$ROOT/.env"
    set +a
fi

# 版本号：VERSION 文件里的 x.y.z；versionCode = x*10000 + y*100 + z
VERSION_NAME="$(tr -d ' \n' < "$ROOT/VERSION")"
IFS=. read -r V_MAJOR V_MINOR V_PATCH <<< "$VERSION_NAME"
VERSION_CODE=$((V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH))

# ═══════════ 找工具 ═══════════

SDK="${ANDROID_HOME:-$ROOT/.sdk}"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
BT="$SDK/build-tools/37.0.0"
if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$BT/d8" ]; then
    cat >&2 <<EOF
找不到 Android SDK（需要 platforms/android-36 和 build-tools/37.0.0）。
默认位置是 $ROOT/.sdk，或者用 ANDROID_HOME 指向已有的 SDK。
下载（需同意 Android SDK 许可协议）：
  https://dl.google.com/android/repository/platform-36_r02.zip  → 解压成 .sdk/platforms/android-36
  https://dl.google.com/android/repository/build-tools_r37_linux.zip → 解压成 .sdk/build-tools/37.0.0
EOF
    exit 1
fi

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
export PATH="$JDK/bin:$PATH"   # d8、apksigner 是脚本，会用 PATH 里的 java

if [ "$RELEASE" = 1 ]; then
    KEYSTORE="${DOUHUA_KEYSTORE:-$ROOT/.private/release.keystore}"
    KS_PASS="${DOUHUA_KEYSTORE_PASS:-}"
    if [ ! -f "$KEYSTORE" ] || [ -z "$KS_PASS" ]; then
        cat >&2 <<EOF2
正式版签名缺东西：
  密钥库 $KEYSTORE $([ -f "$KEYSTORE" ] && echo "（有）" || echo "（没有）")
  口令 DOUHUA_KEYSTORE_PASS $([ -n "$KS_PASS" ] && echo "（有）" || echo "（没设）")
密钥库放 .private/release.keystore（或用 DOUHUA_KEYSTORE 指过去），口令写进 .env（模板见 .env.example）。
还没有密钥库就生成一个（口令自己定，记牢、备份好；丢了以后就没法给已安装的用户升级）：
  keytool -genkeypair -keystore .private/release.keystore -alias douhua -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Douhua"
EOF2
        exit 1
    fi
else
    KEYSTORE="$ROOT/.private/debug.keystore"
    KS_PASS="android"   # 调试签名，沿用 Android 调试密钥的惯例口令
fi

# ═══════════ 编译 ═══════════

rm -rf build
mkdir -p build/classes build/dex build/gen

# 先编资源：生成 R.java（界面文字的编号）和不含代码的 apk
echo "aapt2…" >&2
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$ANDROID_JAR" --java build/gen build/res.zip \
    --min-sdk-version 33 --target-sdk-version 36 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    $([ "$RELEASE" = 1 ] || echo --debug-mode) -o build/unsigned.apk

echo "javac…" >&2
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" -Xlint:-options \
    -d build/classes @build/sources.txt
(cd build/classes && jar cf ../classes.jar .)

echo "d8…" >&2
"$BT/d8" --release --min-api 33 --lib "$ANDROID_JAR" --output build/dex build/classes.jar

(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -p -f 4 build/unsigned.apk build/aligned.apk

if [ "$RELEASE" = 0 ] && [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    echo "第一次编译，生成调试签名 $KEYSTORE（不进 git；丢了的话手机上要先卸载旧版才能装新版）" >&2
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias douhua \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Douhua Debug" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out build/douhua.apk build/aligned.apk
echo "编译完成：build/douhua.apk（$VERSION_NAME，$([ "$RELEASE" = 1 ] && echo 正式版 || echo 调试版)）" >&2

# ═══════════ 安装 ═══════════

if [ "$INSTALL" = 1 ]; then
    echo "安装到手机 ${ANDROID_SERIAL:-（adb 默认设备）}…" >&2
    adb install -r build/douhua.apk >&2
    "$ROOT/tools/stop-server.sh"
    "$ROOT/tools/start-server.sh"
fi
