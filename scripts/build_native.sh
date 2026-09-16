#!/usr/bin/env bash
#
# Build native JNI shared libraries for all supported desktop platforms.
# Usage: ./scripts/build_native.sh [--android]
#
# The JNI subprojects under jni/jvm/* build these on demand during Gradle
# builds. This script is a convenience for standalone CMake builds.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_DIR="$PROJECT_DIR/jni"
BUILD_DIR="$PROJECT_DIR/build/native"

OS="$(uname -s)"
ARCH="$(uname -m)"

echo "=== Building webrtc_resampler_jni for $OS/$ARCH ==="

git -C "$PROJECT_DIR" submodule update --init --recursive

# --- Desktop JVM build ---
JVM_BUILD_DIR="$BUILD_DIR/jvm-build"
JVM_OUTPUT_DIR="$BUILD_DIR/jvm"
mkdir -p "$JVM_BUILD_DIR" "$JVM_OUTPUT_DIR"

JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java) 2>/dev/null || echo /usr/lib/jvm/default/bin/java)))}"
JNI_INCLUDE="$JAVA_HOME/include"

case "$OS" in
    Linux)  JNI_PLATFORM="$JNI_INCLUDE/linux" ;;
    Darwin) JNI_PLATFORM="$JNI_INCLUDE/darwin" ;;
    *)      echo "Unsupported OS: $OS"; exit 1 ;;
esac

echo "JAVA_HOME=$JAVA_HOME"
echo "JNI headers: $JNI_INCLUDE"

cmake -S "$JNI_DIR" -B "$JVM_BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DJNI_INCLUDE_DIR="$JNI_INCLUDE" \
    -DJNI_INCLUDE_DIR_PLATFORM="$JNI_PLATFORM" \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$JVM_OUTPUT_DIR"

cmake --build "$JVM_BUILD_DIR" --config Release

echo ""
echo "=== Desktop JVM library built ==="
ls -la "$JVM_OUTPUT_DIR"/libwebrtc_resampler_jni.*

# --- Android build (optional) ---
if [[ "${1:-}" == "--android" ]]; then
    if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
        echo "ERROR: ANDROID_NDK_HOME not set"
        exit 1
    fi

    for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
        echo ""
        echo "=== Building for Android $ABI ==="
        ANDROID_BUILD_DIR="$BUILD_DIR/android-$ABI-build"
        ANDROID_OUTPUT_DIR="$BUILD_DIR/android/$ABI"
        mkdir -p "$ANDROID_BUILD_DIR" "$ANDROID_OUTPUT_DIR"

        cmake -S "$JNI_DIR" -B "$ANDROID_BUILD_DIR" \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM=android-21 \
            -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$ANDROID_OUTPUT_DIR"

        cmake --build "$ANDROID_BUILD_DIR" --config Release

        echo "Android $ABI library built:"
        ls -la "$ANDROID_OUTPUT_DIR"/libwebrtc_resampler_jni.so
    done
fi

echo ""
echo "=== Build complete ==="
