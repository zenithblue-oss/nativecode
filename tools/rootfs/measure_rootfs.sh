#!/bin/bash
# measure_rootfs.sh — size report + archive (zstd preferred, xz fallback).
# Usage: tools/rootfs/measure_rootfs.sh /path/to/rootfs out/name
# ZSTD_LEVEL=3 is recommended for fast engineering builds; release default is 19.
set -euo pipefail
ROOTFS="${1:?usage: measure_rootfs.sh <rootfs-dir> <out-prefix>}"
OUT="${2:?usage: measure_rootfs.sh <rootfs-dir> <out-prefix>}"
ZSTD_LEVEL="${ZSTD_LEVEL:-14}"

du -sh "$ROOTFS"
du -sx --block-size=1 "$ROOTFS"
du -sh "$ROOTFS/usr" "$ROOTFS/opt" "$ROOTFS/home" "$ROOTFS/usr/lib" \
    "$ROOTFS/opt/android-sdk" "$ROOTFS/opt/android-sdk/ndk" \
    "$ROOTFS/opt/gradle" "$ROOTFS/opt/kotlin" 2>/dev/null || true
if command -v zstd >/dev/null 2>&1; then
    echo "measure_rootfs: creating zstd archive at level $ZSTD_LEVEL"
    rm -f "$OUT.tar.zst" "$OUT.tar.zst.sha256"
    tar --numeric-owner -cpf - -C "$ROOTFS" . | zstd -f -"$ZSTD_LEVEL" -T0 -o "$OUT.tar.zst"
    stat -c '%s' "$OUT.tar.zst"
    sha256sum "$OUT.tar.zst" | tee "$OUT.tar.zst.sha256"
else
    rm -f "$OUT.tar.xz" "$OUT.tar.xz.sha256"
    tar --numeric-owner -cJpf "$OUT.tar.xz" -C "$ROOTFS" .
    stat -c '%s' "$OUT.tar.xz"
    sha256sum "$OUT.tar.xz" | tee "$OUT.tar.xz.sha256"
fi
MAX=$((145 * 1024 * 1024 * 1024 / 100))
SIZE=$(stat -c '%s' "$OUT".tar.* | head -1)
if [ "$SIZE" -gt "$MAX" ]; then
    if [ "$ZSTD_LEVEL" -lt 10 ]; then
        echo "measure_rootfs: fast build size $SIZE exceeded 1.45GiB, recompressing with release level 14..."
        rm -f "$OUT.tar.zst" "$OUT.tar.zst.sha256"
        tar --numeric-owner -cpf - -C "$ROOTFS" . | zstd -f -14 -T0 -o "$OUT.tar.zst"
        stat -c '%s' "$OUT.tar.zst"
        sha256sum "$OUT.tar.zst" | tee "$OUT.tar.zst.sha256"
        SIZE=$(stat -c '%s' "$OUT".tar.* | head -1)
    fi
fi
if [ "$SIZE" -gt "$MAX" ]; then
    echo "SIZE GATE FAIL: $SIZE > 1.45GiB — split runtime + dev-SDK layers" >&2
    exit 1
fi
echo "SIZE GATE PASS: $SIZE"
