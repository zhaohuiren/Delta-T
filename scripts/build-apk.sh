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

./gradlew :app:testFallbackDebugUnitTest :app:lintFallbackDebug :app:assembleFallbackDebug

mkdir -p "$ROOT/dist"
cp "$ROOT/app/build/outputs/apk/fallback/debug/app-fallback-debug.apk" \
  "$ROOT/dist/Delta-T-0.4.0-fallback-debug.apk"

echo "Built: $ROOT/dist/Delta-T-0.4.0-fallback-debug.apk"
