#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_DIR="${1:-/opt/android-sdk/platforms/android-36}"
BUILD_TOOLS_ARCHIVE="${2:-/tmp/nativecode-android-sdk-aarch64.tar.xz}"
ASSETS_DIR="$ROOT_DIR/nativecode_android_dev/src/main/assets"
OUTPUT="$ASSETS_DIR/nativecode_android_dev.tar.xz"
EXPECTED_BUILD_TOOLS_SHA256="3a79b9cb06351ccb31d7e29100b3f11a3ba4bd491c839c9bbbaa7e174d60146a"

test -f "$PLATFORM_DIR/android.jar" || {
    echo "Missing Android Platform 36: $PLATFORM_DIR/android.jar" >&2
    exit 1
}
test -f "$BUILD_TOOLS_ARCHIVE" || {
    echo "Missing pinned ARM64 Build Tools archive: $BUILD_TOOLS_ARCHIVE" >&2
    exit 1
}
echo "$EXPECTED_BUILD_TOOLS_SHA256  $BUILD_TOOLS_ARCHIVE" | sha256sum -c -

WORK_DIR="$(mktemp -d /tmp/nativecode-android-dev.XXXXXX)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT INT TERM

mkdir -p \
    "$WORK_DIR/extracted" \
    "$WORK_DIR/root/opt/android-sdk/platforms" \
    "$WORK_DIR/root/opt/android-sdk/build-tools" \
    "$WORK_DIR/root/etc/profile.d" \
    "$WORK_DIR/root/usr/local/bin"

tar -xf "$BUILD_TOOLS_ARCHIVE" -C "$WORK_DIR/extracted"
AAPT2_SRC="$WORK_DIR/extracted/android-sdk/build-tools/36.1.0/aapt2"
test -x "$AAPT2_SRC" || { echo "ARM64 aapt2 missing" >&2; exit 1; }
file "$AAPT2_SRC" | grep -q 'ARM aarch64' || {
    echo "aapt2 is not ARM64" >&2
    exit 1
}

cp -a "$PLATFORM_DIR" "$WORK_DIR/root/opt/android-sdk/platforms/android-36"
cp -a "$WORK_DIR/extracted/android-sdk/build-tools/36.1.0" \
    "$WORK_DIR/root/opt/android-sdk/build-tools/36.1.0"
ln -s /opt/android-sdk/build-tools/36.1.0/aapt2 "$WORK_DIR/root/usr/local/bin/aapt2"

cat > "$WORK_DIR/root/etc/profile.d/nativecode-android.sh" <<'EOF'
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
case ":$PATH:" in
  *:/opt/android-sdk/build-tools/36.1.0:*) ;;
  *) export PATH="/opt/android-sdk/build-tools/36.1.0:$PATH" ;;
esac
EOF
chmod 644 "$WORK_DIR/root/etc/profile.d/nativecode-android.sh"

mkdir -p "$ASSETS_DIR"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner \
    -C "$WORK_DIR/root" -cJf "$OUTPUT" .
sha256sum "$OUTPUT" | sed 's#  .*/#  #' > "$OUTPUT.sha256"

tar -Jtf "$OUTPUT" > "$WORK_DIR/archive.list"
grep -Fxq './opt/android-sdk/platforms/android-36/android.jar' "$WORK_DIR/archive.list"
grep -Fxq './opt/android-sdk/build-tools/36.1.0/aapt2' "$WORK_DIR/archive.list"
test "$(stat -c %s "$OUTPUT")" -lt 1500000000

echo "Built $OUTPUT ($(stat -c %s "$OUTPUT") bytes)"
cat "$OUTPUT.sha256"
