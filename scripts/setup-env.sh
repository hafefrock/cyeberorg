#!/usr/bin/env bash
set -euo pipefail

# HackerStudio Android Environment Setup Script
# Verifies Java, Android SDK, Gradle availability, and prepares signing env vars.

echo "=== HackerStudio Environment Check ==="

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java (JDK 11+) not found in PATH."
  exit 1
fi
java -version

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  echo "WARNING: Neither ANDROID_SDK_ROOT nor ANDROID_HOME is set."
  echo "Export ANDROID_SDK_ROOT=/path/to/android-sdk before running gradle."
else
  echo "Android SDK: ${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
fi

if command -v gradle >/dev/null 2>&1; then
  echo "Gradle binary: $(command -v gradle)"
elif [ -x "./gradlew" ]; then
  echo "Gradle wrapper: ./gradlew"
else
  echo "ERROR: Neither 'gradle' nor './gradlew' found."
  exit 1
fi

if [ ! -f "debug.keystore" ]; then
  echo "Generating local debug.keystore..."
  keytool -genkeypair \
    -v \
    -keystore debug.keystore \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "Environment ready."
