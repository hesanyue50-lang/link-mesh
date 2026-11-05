#!/bin/sh
# 正式打包：自动递增 versionCode、写入 versionName、签名并输出到 dist/
# 用法：./scripts/release.sh 1.2.1
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$1"
if [ -z "$VERSION" ]; then
  echo "用法: ./scripts/release.sh <versionName>  例如 ./scripts/release.sh 1.2.1"
  exit 1
fi

cd "$ROOT"

# 图标 PNG 曾被外部清理误删，构建前先确保存在
python3 "$ROOT/scripts/gen_icons.py" >/dev/null 2>&1 || true
if [ ! -f keystore.properties ]; then
  echo "缺少 keystore.properties（storeFile/storePassword/keyAlias/keyPassword）"
  exit 1
fi

# versionCode 用单调递增的发布号，存在 build/version.txt（debug 构建用时间戳，不参与）
CODE_FILE="build/version.txt"
# 基线取「历史发布号」与「当前时间戳号（与 debug 同源）」的较大者，
# 否则 release 的号会低于手机上已装的 debug 包，安装时被系统判成降级
NOW_CODE=$(( $(date +%s) / 60 ))
LAST_CODE=0
if [ -f "$CODE_FILE" ]; then
  LAST_CODE=$(cat "$CODE_FILE" | tr -cd '0-9')
fi
[ -z "$LAST_CODE" ] && LAST_CODE=0
[ "$LAST_CODE" -lt "$NOW_CODE" ] && LAST_CODE=$NOW_CODE
NEW_CODE=$((LAST_CODE + 1))
mkdir -p build
echo "$NEW_CODE" > "$CODE_FILE"
sed -i "s/versionName '[^']*'/versionName '$VERSION'/" app/build.gradle
echo "版本: $VERSION (versionCode=$NEW_CODE，下次从 $NEW_CODE 继续)"

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export JAVA_HOME ANDROID_HOME

/opt/gradle/gradle-8.13/bin/gradle :app:assembleRelease --no-daemon --max-workers=1 \
  -Pvc="$NEW_CODE" \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2-qemu/aapt2

mkdir -p dist
OUT="dist/LinkMesh-v$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
echo "产物: $OUT ($(du -h "$OUT" | cut -f1))"

# 签名校验
APKSIGNER=$(ls /opt/android-sdk/build-tools/*/apksigner 2>/dev/null | tail -1)
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null | head -4 || true
fi

echo "SHA-256:"
sha256sum "$OUT"

echo ""
echo "下一步："
echo "  git add -A && git commit -m 'release v$VERSION' && git tag v$VERSION && git push --tags"
echo "  然后在 GitHub 新建 release 并上传 $OUT"
