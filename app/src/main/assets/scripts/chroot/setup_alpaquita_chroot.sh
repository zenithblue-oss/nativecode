#!/bin/sh
# setup_alpaquita_chroot.sh — host-side chroot setup for Alpaquita glibc/AArch64 rootfs.
# Run on rooted Android / Linux host with UID 0.
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FLUX_ROOTFS_PATH="${FLUX_ROOTFS_PATH:-/data/local/tmp/nativecode-rootfs}"
FLUX_ARCHIVE="${1:-}"

echo "NativeCode: Setting up Alpaquita glibc rootfs under chroot..."

if [ -n "$FLUX_ARCHIVE" ] && [ -f "$FLUX_ARCHIVE" ]; then
    echo "NativeCode: Extracting $FLUX_ARCHIVE -> $FLUX_ROOTFS_PATH..."
    mkdir -p "$FLUX_ROOTFS_PATH"
    if command -v zstd >/dev/null 2>&1; then
        tar -xf "$FLUX_ARCHIVE" -C "$FLUX_ROOTFS_PATH" --numeric-owner
    else
        tar -xzf "$FLUX_ARCHIVE" -C "$FLUX_ROOTFS_PATH" --numeric-owner
    fi
fi

if [ ! -d "$FLUX_ROOTFS_PATH" ]; then
    echo "NativeCode: ERROR: Rootfs directory $FLUX_ROOTFS_PATH does not exist" >&2
    exit 1
fi

# Prepare required virtual filesystems
mkdir -p "$FLUX_ROOTFS_PATH/dev" "$FLUX_ROOTFS_PATH/proc" "$FLUX_ROOTFS_PATH/sys" "$FLUX_ROOTFS_PATH/run" "$FLUX_ROOTFS_PATH/dev/pts"

mountpoint -q "$FLUX_ROOTFS_PATH/dev" || mount --bind /dev "$FLUX_ROOTFS_PATH/dev"
mountpoint -q "$FLUX_ROOTFS_PATH/proc" || mount -t proc proc "$FLUX_ROOTFS_PATH/proc"
mountpoint -q "$FLUX_ROOTFS_PATH/sys" || mount -t sysfs sysfs "$FLUX_ROOTFS_PATH/sys"
mountpoint -q "$FLUX_ROOTFS_PATH/run" || mount --bind /run "$FLUX_ROOTFS_PATH/run"
mountpoint -q "$FLUX_ROOTFS_PATH/dev/pts" || mount --bind /dev/pts "$FLUX_ROOTFS_PATH/dev/pts"

# Execute guest setup stages in chroot
export NATIVECODE_ISOLATION=chroot
chroot "$FLUX_ROOTFS_PATH" /bin/sh -c '
    set -e
    if [ -f /usr/local/lib/nativecode/setup_alpaquita_family.sh ]; then
        /bin/sh /usr/local/lib/nativecode/setup_alpaquita_family.sh alpaquita
    elif [ -f /usr/local/lib/nativecode/setup_alpine_family.sh ]; then
        /bin/sh /usr/local/lib/nativecode/setup_alpine_family.sh alpaquita
    fi
'

echo "NativeCode: Alpaquita chroot setup complete."
