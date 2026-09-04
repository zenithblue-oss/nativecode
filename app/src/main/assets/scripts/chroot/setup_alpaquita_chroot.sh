#!/bin/sh
# setup_alpaquita_chroot.sh — host-side chroot setup for Alpaquita glibc/AArch64 rootfs.
# Run on rooted Android / Linux host with UID 0.
# Contract: strictly verify SHA256, stage guest scripts, and execute phases E -> F -> GUI -> G -> H.
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR=""
FLUX_ROOTFS_PATH="${FLUX_CHROOT:-${FLUX_ROOTFS_PATH:-/data/local/tmp/nativecode-rootfs}}"
FLUX_ARCHIVE="${1:-}"

PKG="${TERMUX_APP__PACKAGE_NAME:-com.zenithblue.nativecode}"
APP_HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
APP_PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
ROOTFS_NAME="alpaquita_rootfs.tar.zst"
ROOTFS_SHA256="${FLUX_ROOTFS_SHA256:-ec5c81cb32bd6454a778aaec449ef269806d7f17b52432abe00280684136af50}"

progress() { printf "\033[1;36m[+] %s\033[0m\n" "$1"; }
success()  { printf "\033[1;32m[✓] %s\033[0m\n" "$1"; }
error()    { printf "\033[1;31m[!] %s\033[0m\n" "$1"; }

cleanup_mounts() {
    progress "Safety Check: Unmounting virtual filesystems..."
    umount "$FLUX_ROOTFS_PATH/sdcard" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/mnt/host-tmp" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/dev/shm" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/dev/pts" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/proc" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/sys" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/run" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/dev" 2>/dev/null || true
    umount "$FLUX_ROOTFS_PATH/tmp" 2>/dev/null || true
    return 0
}

goodbye() {
    error "NativeCode: Setup failed!"
    cleanup_mounts
    exit 1
}

trap goodbye INT TERM

if [ "$(id -u)" != "0" ]; then
    error "This script must be run as root (UID 0). Exiting."
    exit 1
fi

progress "Setting up Alpaquita glibc rootfs under chroot..."

resolve_rootfs_archive() {
    if [ -n "$FLUX_ARCHIVE" ] && [ -f "$FLUX_ARCHIVE" ] && [ -s "$FLUX_ARCHIVE" ]; then
        return 0
    fi
    for candidate in \
        "$APP_HOME/$ROOTFS_NAME" \
        "$APP_HOME/rootfs/$ROOTFS_NAME" \
        "$APP_PREFIX/var/lib/proot-distro/cache/rootfs/$ROOTFS_NAME" \
        "/sdcard/Download/$ROOTFS_NAME" \
        "/storage/emulated/0/Download/$ROOTFS_NAME" \
        "/data/local/tmp/$ROOTFS_NAME"
    do
        if [ -f "$candidate" ] && [ -s "$candidate" ]; then
            FLUX_ARCHIVE="$candidate"
            progress "rootfs archive found: $FLUX_ARCHIVE"
            return 0
        fi
    done
    return 1
}

# Extraction step if rootfs not populated
if [ ! -f "$FLUX_ROOTFS_PATH/bin/sh" ] && [ ! -f "$FLUX_ROOTFS_PATH/sbin/apk" ]; then
    if ! resolve_rootfs_archive; then
        error "No rootfs archive found for extraction (checked app home, downloads, /data/local/tmp)"
        goodbye
    fi

    if [ -n "$ROOTFS_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
        progress "Verifying rootfs checksum..."
        _got="$(sha256sum "$FLUX_ARCHIVE" | awk '{print $1}')"
        if [ "$_got" != "$ROOTFS_SHA256" ]; then
            error "SHA256 mismatch for $FLUX_ARCHIVE"
            error "  expected $ROOTFS_SHA256"
            error "  got      $_got"
            goodbye
        fi
        success "SHA256 OK"
    fi

    progress "Extracting $FLUX_ARCHIVE -> $FLUX_ROOTFS_PATH..."
    mkdir -p "$FLUX_ROOTFS_PATH"
    if command -v zstd >/dev/null 2>&1; then
        zstd -dc "$FLUX_ARCHIVE" | tar -xpf - -C "$FLUX_ROOTFS_PATH" --numeric-owner || goodbye
    else
        tar -xzf "$FLUX_ARCHIVE" -C "$FLUX_ROOTFS_PATH" --numeric-owner || goodbye
    fi
fi

if [ ! -d "$FLUX_ROOTFS_PATH" ] || [ ! -e "$FLUX_ROOTFS_PATH/bin/sh" ]; then
    error "Rootfs directory $FLUX_ROOTFS_PATH invalid or missing /bin/sh"
    goodbye
fi

# Prepare and mount virtual filesystems
progress "Mounting virtual filesystems..."
mkdir -p "$FLUX_ROOTFS_PATH/dev" "$FLUX_ROOTFS_PATH/proc" "$FLUX_ROOTFS_PATH/sys" "$FLUX_ROOTFS_PATH/run" "$FLUX_ROOTFS_PATH/dev/pts" "$FLUX_ROOTFS_PATH/dev/shm" "$FLUX_ROOTFS_PATH/tmp"

mountpoint -q "$FLUX_ROOTFS_PATH/dev" || mount --bind /dev "$FLUX_ROOTFS_PATH/dev" || goodbye
mountpoint -q "$FLUX_ROOTFS_PATH/proc" || mount -t proc proc "$FLUX_ROOTFS_PATH/proc" || goodbye
mountpoint -q "$FLUX_ROOTFS_PATH/sys" || mount -t sysfs sysfs "$FLUX_ROOTFS_PATH/sys" || goodbye
mountpoint -q "$FLUX_ROOTFS_PATH/run" || mount --bind /run "$FLUX_ROOTFS_PATH/run" 2>/dev/null || true
mountpoint -q "$FLUX_ROOTFS_PATH/dev/pts" || mount --bind /dev/pts "$FLUX_ROOTFS_PATH/dev/pts" || goodbye
mountpoint -q "$FLUX_ROOTFS_PATH/dev/shm" || mount -t tmpfs -o size=512M,mode=1777 tmpfs "$FLUX_ROOTFS_PATH/dev/shm" 2>/dev/null || true

# Stage guest setup scripts into /usr/local/lib/nativecode inside rootfs
mkdir -p "$FLUX_ROOTFS_PATH/usr/local/lib/nativecode"
SCRIPTS_FOUND=0
for sdir in "$SCRIPT_DIR/.." "$APP_HOME/scripts" "$APP_HOME"; do
    if [ -f "$sdir/setup_alpaquita_family.sh" ]; then
        cp -f "$sdir"/setup_*.sh "$FLUX_ROOTFS_PATH/usr/local/lib/nativecode/" 2>/dev/null || cp -f "$sdir/setup_alpaquita_family.sh" "$FLUX_ROOTFS_PATH/usr/local/lib/nativecode/"
        SCRIPTS_FOUND=1
        break
    fi
done

if [ ! -f "$FLUX_ROOTFS_PATH/usr/local/lib/nativecode/setup_alpaquita_family.sh" ]; then
    error "CRITICAL: setup_alpaquita_family.sh could not be found or staged into guest!"
    goodbye
fi

# Execute guest setup stages in chroot: E -> F -> GUI -> G -> H
progress "Executing guest stages in chroot (E -> F -> GUI -> G -> H)..."
export NATIVECODE_ISOLATION=chroot
chroot "$FLUX_ROOTFS_PATH" /bin/sh -c '
    set -eu
    export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    SETUP_DIR="/usr/local/lib/nativecode"
    
    # 1. Phase E: Distro family setup
    if [ -f "$SETUP_DIR/setup_alpaquita_family.sh" ]; then
        /bin/sh "$SETUP_DIR/setup_alpaquita_family.sh" alpaquita
    else
        echo "Missing setup_alpaquita_family.sh" >&2; exit 1
    fi

    # 2. Phase F: Desktop XFCE core setup
    if [ -f "$SETUP_DIR/setup_desktop_xfce.sh" ]; then
        /bin/sh "$SETUP_DIR/setup_desktop_xfce.sh" alpaquita
    fi

    # 3. Phase G: Customization setup
    if [ -f "$SETUP_DIR/setup_customization_alpine.sh" ]; then
        /bin/sh "$SETUP_DIR/setup_customization_alpine.sh" alpaquita
    fi

    # 4. Phase H: AI stack setup
    if [ -f "$SETUP_DIR/setup_ai_stack.sh" ]; then
        /bin/sh "$SETUP_DIR/setup_ai_stack.sh" alpaquita
    fi
' || goodbye

cleanup_mounts
trap - INT TERM
success "NativeCode: Alpaquita chroot setup complete."
exit 0
