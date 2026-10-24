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

OLD_CODE=$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' app/build.gradle | head -1)
NEW_CODE=$((OLD_CODE + 1))
sed -i "s/versionCode $OLD_CODE/versionCode $NEW_CODE/" app/build.gradle
sed -i "s/versionName '[^']*'/versionName '$VERSION'/" app/build.gradle
echo "版本: $VERSION (versionCode=$NEW_CODE)"

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}
ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export JAVA_HOME ANDROID_HOME

/opt/gradle/gradle-8.13/bin/gradle :app:assembleRelease --no-daemon --max-workers=2 \
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
