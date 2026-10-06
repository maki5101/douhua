#!/bin/bash
# 豆花发布打包（来自 release-isolation 的 package.sh.template）。产物：dist/douhua-<版本>.apk（正式签名）+ SHA256SUMS
#
#   tools/package.sh              正式包：从最新提交构建，工作区有没提交的改动就拒绝
#   tools/package.sh --snapshot   试打包：按"现在 git add -A 会提交的样子"构建，文件名带 -snapshot
#
# 隔离：源码一律从 git 取（git archive），在临时目录里构建，没进 git 的东西（.env、个人配置、.private/、
# 缓存）到不了包里。打完用 tools/privacy_scan.py 扫成品，有私人信息或密钥就中止、不留产物。
set -eu
cd "$(dirname "$0")/.."
SNAPSHOT=0; [ "${1:-}" = "--snapshot" ] && SNAPSHOT=1
if [ "$SNAPSHOT" = 0 ] && [ -n "$(git status --porcelain)" ]; then
    echo "工作区有没提交的改动，正式包只从提交构建。先提交，或者用 --snapshot 试打包："
    git status --short | head -20
    exit 1
fi
STAGE="$(mktemp -d)"; trap 'rm -rf "$STAGE"' EXIT
if [ "$SNAPSHOT" = 1 ]; then
    IDX="$STAGE/index"   # 临时索引：真正的暂存区不动
    if git rev-parse -q --verify HEAD >/dev/null; then
        GIT_INDEX_FILE="$IDX" git read-tree HEAD
    else
        GIT_INDEX_FILE="$IDX" git read-tree --empty   # 还没有任何提交
    fi
    GIT_INDEX_FILE="$IDX" git add -A
    REF="$(GIT_INDEX_FILE="$IDX" git write-tree)"
else
    REF=HEAD
fi
SRC="$STAGE/src"; mkdir -p "$SRC"
git archive "$REF" | tar -x -C "$SRC"
OUT="$STAGE/out"; mkdir -p "$OUT"

# 【改 1】版本号从源码里取
VER="$(tr -d ' \n' < "$SRC/VERSION")"
TAG="$VER"; [ "$SNAPSHOT" = 1 ] && TAG="$VER-snapshot"

# 【改 2】构建：只在 $SRC 里做，产物放进 $OUT，文件名带 $TAG。
#   SDK 是本机大文件（.sdk/，不进 git），直接指过去；正式签名密钥库临时拷进 $STAGE，口令从本机 .env 读，用完随 $STAGE 删掉。
ROOT="$(pwd)"
if [ -f "$ROOT/.env" ]; then set -a; . "$ROOT/.env"; set +a; fi
KS="${DOUHUA_KEYSTORE:-$ROOT/.private/release.keystore}"
[ -f "$KS" ] || { echo "没有正式签名密钥库 $KS（生成方法见 tools/build.sh 的报错提示）"; exit 1; }
cp "$KS" "$STAGE/release.keystore"
(cd "$SRC" && ANDROID_HOME="$ROOT/.sdk" DOUHUA_KEYSTORE="$STAGE/release.keystore" \
    DOUHUA_KEYSTORE_PASS="${DOUHUA_KEYSTORE_PASS:-}" tools/build.sh --release)
cp "$SRC/build/douhua.apk" "$OUT/douhua-$TAG.apk"

# 【改 3】一般不用改：扫成品、算校验和、放进 dist/
python3 tools/privacy_scan.py "$OUT" || { echo "成品里有私人信息或密钥（见上），没有输出到 dist/"; exit 1; }
(cd "$OUT" && sha256sum -- * > SHA256SUMS)
rm -rf dist; mkdir -p dist; cp -p "$OUT"/* dist/
echo "dist/（源码：$([ "$SNAPSHOT" = 1 ] && echo "工作区快照" || git rev-parse --short HEAD)）:"
ls -la dist | awk 'NR>3{printf "  %-48s %s\n", $NF, $5}'
