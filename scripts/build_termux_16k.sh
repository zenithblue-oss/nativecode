#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE="$ROOT/termux-app/terminal-emulator/src/main/jni/termux.c"
OUTPUT="$ROOT/app/libs/terminal-emulator-v0.118.0-16k.aar"

NDK_ROOT=${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}
if [ -z "$NDK_ROOT" ] && [ -f "$ROOT/local.properties" ]; then
    SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1)
    if [ -d "$SDK_ROOT/ndk" ]; then
        NDK_ROOT=$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
    fi
fi
[ -n "$NDK_ROOT" ] || { echo "Set ANDROID_NDK_ROOT." >&2; exit 1; }

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TOOLCHAIN/aarch64-linux-android26-clang"
READELF="$TOOLCHAIN/llvm-readelf"
[ -x "$CLANG" ] || { echo "Missing compiler: $CLANG" >&2; exit 1; }
[ -x "$READELF" ] || { echo "Missing readelf: $READELF" >&2; exit 1; }

UPSTREAM_AAR=${1:-}
if [ -z "$UPSTREAM_AAR" ]; then
    UPSTREAM_AAR=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.github.termux.termux-app/terminal-emulator/v0.118.0" \
        -type f -name 'terminal-emulator-v0.118.0.aar' -print -quit 2>/dev/null || true)
fi
[ -f "$UPSTREAM_AAR" ] || {
    echo "Resolve Gradle dependencies first, or pass the upstream terminal-emulator AAR path." >&2
    exit 1
}

WORK=$(mktemp -d /tmp/nativecode-termux-16k.XXXXXX)
cleanup() {
    find "$WORK" -mindepth 1 -delete 2>/dev/null || true
    rmdir "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

"$CLANG" -shared -fPIC -O2 \
    -Wl,-soname,libtermux.so \
    -Wl,-z,max-page-size=16384 \
    -o "$WORK/libtermux.so" "$SOURCE"

while read -r alignment; do
    [ "$((alignment))" -ge 16384 ] || {
        echo "LOAD alignment is below 0x4000: $alignment" >&2
        exit 1
    }
done < <("$READELF" -lW "$WORK/libtermux.so" | awk '$1 == "LOAD" {print $NF}')

mkdir -p "$WORK/aar"
unzip -q "$UPSTREAM_AAR" -d "$WORK/aar"
find "$WORK/aar/jni" -mindepth 1 -delete
mkdir -p "$WORK/aar/jni/arm64-v8a" "$(dirname "$OUTPUT")"
install -m 0644 "$WORK/libtermux.so" "$WORK/aar/jni/arm64-v8a/libtermux.so"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    rmdir "$WORK/aar/$abi" 2>/dev/null || true
done
[ ! -e "$OUTPUT" ] || unlink "$OUTPUT"
(cd "$WORK/aar" && zip -X -q -r "$OUTPUT" .)

echo "Built $OUTPUT"
