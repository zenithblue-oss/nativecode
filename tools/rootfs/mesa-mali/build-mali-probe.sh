#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOTFS_DIR="$(dirname "$SCRIPT_DIR")"
SYSROOT="${SYSROOT:?set SYSROOT to the extracted Alpaquita aarch64 rootfs}"
CC="${CC:-aarch64-linux-gnu-gcc}"
SOURCE="$ROOTFS_DIR/overlay/alpaquita/opt/nativecode/panvk/mali_probe.c"
OUTPUT="$ROOTFS_DIR/overlay/alpaquita/opt/nativecode/panvk/bin/nativecode-mali-probe"

command -v "$CC" >/dev/null || { echo "mali-probe: missing $CC" >&2; exit 1; }
[ -e "$SYSROOT/lib/ld-linux-aarch64.so.1" ] || [ -e "$SYSROOT/lib64/ld-linux-aarch64.so.1" ] || {
    echo "mali-probe: $SYSROOT is not an aarch64 glibc sysroot" >&2
    exit 1
}

"$CC" --sysroot="$SYSROOT" -O2 -Wall -Wextra -Werror "$SOURCE" -o "$OUTPUT"
file "$OUTPUT" | grep -q 'ARM aarch64'
echo "mali-probe: built $OUTPUT"
