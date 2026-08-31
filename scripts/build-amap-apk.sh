#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -d "$ROOT/.toolchain/jdk17/Contents/Home" ]]; then
  export JAVA_HOME="$ROOT/.toolchain/jdk17/Contents/Home"
fi
if [[ -d "$ROOT/.toolchain/android-sdk" ]]; then
  export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}"

if ! grep -Eq '^AMAP_API_KEY=.+$' "$ROOT/local.properties" 2>/dev/null && [[ -z "${AMAP_API_KEY:-}" ]]; then
  echo "错误：请在 local.properties 或环境变量中配置 AMAP_API_KEY" >&2
  exit 2
fi

./gradlew :app:testAmapDebugUnitTest :app:lintAmapDebug :app:assembleAmapDebug

SOURCE="$ROOT/app/build/outputs/apk/amap/debug/app-amap-debug.apk"
TARGET="$ROOT/dist/Delta-T-0.4.0-amap-debug.apk"
test -s "$SOURCE"
mkdir -p "$ROOT/dist"
cp "$SOURCE" "$TARGET"

AAPT="${ANDROID_HOME:-}/build-tools/36.0.0/aapt2"
APKSIGNER="${ANDROID_HOME:-}/build-tools/36.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --verbose "$TARGET"
fi
if [[ -x "$AAPT" ]]; then
  "$AAPT" dump badging "$TARGET" | grep -E "package: name='app.deltat.commute'.*versionCode='8'.*versionName='0.4.0-test'"
fi

shasum -a 256 "$TARGET"
echo "Built: $TARGET"
