#!/usr/bin/env bash
set -euo pipefail

# Build Debug or Release APK using Cyberorg signingConfigs in app/build.gradle.kts
# Usage:
#   ./scripts/build-apk.sh debug
#   KEYSTORE_PATH=/path/to/upload.jks STORE_PASSWORD=*** KEY_PASSWORD=*** ./scripts/build-apk.sh release

MODE="${1:-debug}"

GRADLE_CMD="gradle"
if ! command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD="./gradlew"
fi

case "$MODE" in
  debug)
    echo "Building Debug APK (signed with debugConfig -> debug.keystore)..."
    "$GRADLE_CMD" :app:assembleDebug
    echo "APK output: app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    : "${STORE_PASSWORD:?Set STORE_PASSWORD before building release APK}"
    : "${KEY_PASSWORD:?Set KEY_PASSWORD before building release APK}"
    export KEYSTORE_PATH="${KEYSTORE_PATH:-$(pwd)/my-upload-key.jks}"
    echo "Building Release APK (signed with release -> $KEYSTORE_PATH)..."
    "$GRADLE_CMD" :app:assembleRelease
    echo "APK output: app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "Unknown mode: $MODE (expected 'debug' or 'release')"
    exit 1
    ;;
esac
