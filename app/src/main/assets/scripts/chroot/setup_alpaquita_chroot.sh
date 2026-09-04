#!/bin/sh
# setup_alpaquita_chroot.sh — fast host-side bootstrap for the prebuilt Alpaquita rootfs.
# Run as UID 0 on rooted Android. Guest E/F/GUI/G/H are owned by OnboardingActivity.
# Mandatory onboarding stage contract after this bootstrap:
#   E   setup_alpaquita_family.sh
#   F   setup_hw_accel_debian.sh
#   GUI setup_xfce4.sh
#   G   setup_customization_alpine.sh
#   H   setup_cli_tools.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR=""
FLUX_ROOTFS_PATH="${FLUX_CHROOT:-/data/local/tmp/chrootAlpine}"
FLUX_ARCHIVE="${1:-}"

PKG="${TERMUX_APP__PACKAGE_NAME:-com.zenithblue.nativecode}"
APP_HOME="${TERMUX__HOME:-/data/data/${PKG}/files/home}"
APP_PREFIX="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}"
ROOTFS_NAME="alpaquita_rootfs.tar.zst"
ROOTFS_SHA256="${FLUX_ROOTFS_SHA256:-${NATIVECODE_ROOTFS_SHA256:-}}"

progress() { printf '\033[1;36m[+] %s\033[0m\n' "$1"; }
success()  { printf '\033[1;32m[✓] %s\033[0m\n' "$1"; }
error()    { printf '\033[1;31m[!] %s\033[0m\n' "$1" >&2; }

fatal() {
    error "$*"
    exit 1
}

[ "$(id -u)" = "0" ] || fatal "This script must be run as root (UID 0)."

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

load_adjacent_sha() {
    [ -n "$ROOTFS_SHA256" ] && return 0
    [ -n "$FLUX_ARCHIVE" ] || return 1
    _sha_file="$FLUX_ARCHIVE.sha256"
    if [ -f "$_sha_file" ] && [ -s "$_sha_file" ]; then
        ROOTFS_SHA256="$(awk 'NR==1 {print $1}' "$_sha_file")"
    fi
    [ -n "$ROOTFS_SHA256" ]
}

verify_archive() {
    load_adjacent_sha || fatal "Final NativeCode rootfs SHA256 is missing (set FLUX_ROOTFS_SHA256 or provide $FLUX_ARCHIVE.sha256)."
    command -v sha256sum >/dev/null 2>&1 || fatal "sha256sum is required for rootfs verification."
    _got="$(sha256sum "$FLUX_ARCHIVE" | awk '{print $1}')"
    [ "$_got" = "$ROOTFS_SHA256" ] || {
        error "SHA256 mismatch for $FLUX_ARCHIVE"
        error "expected $ROOTFS_SHA256"
        error "got      $_got"
        exit 1
    }
    success "Rootfs SHA256 OK"
}

rootfs_populated() {
    [ -e "$FLUX_ROOTFS_PATH/bin/sh" ] || [ -e "$FLUX_ROOTFS_PATH/sbin/apk" ]
}

progress "Preparing Alpaquita glibc chroot at $FLUX_ROOTFS_PATH"

if ! rootfs_populated; then
    resolve_rootfs_archive || fatal "No $ROOTFS_NAME found in app home, Downloads, or /data/local/tmp."
    verify_archive
    command -v zstd >/dev/null 2>&1 || fatal "zstd is required to extract canonical .tar.zst rootfs."

    rm -rf "$FLUX_ROOTFS_PATH"
    mkdir -p "$FLUX_ROOTFS_PATH"
    progress "Extracting prebuilt rootfs..."
    zstd -dc "$FLUX_ARCHIVE" | tar -xpf - -C "$FLUX_ROOTFS_PATH" --numeric-owner
fi

rootfs_populated || fatal "Extracted rootfs is invalid: /bin/sh or /sbin/apk missing."

# Runtime directories used by the shared chroot helper and onboarding stages.
mkdir -p \
    "$FLUX_ROOTFS_PATH/tmp" \
    "$FLUX_ROOTFS_PATH/var/tmp" \
    "$FLUX_ROOTFS_PATH/dev" \
    "$FLUX_ROOTFS_PATH/dev/pts" \
    "$FLUX_ROOTFS_PATH/dev/shm" \
    "$FLUX_ROOTFS_PATH/proc" \
    "$FLUX_ROOTFS_PATH/sys" \
    "$FLUX_ROOTFS_PATH/run" \
    "$FLUX_ROOTFS_PATH/mnt/host-tmp" \
    "$FLUX_ROOTFS_PATH/sdcard"
chmod 1777 "$FLUX_ROOTFS_PATH/tmp" "$FLUX_ROOTFS_PATH/var/tmp" 2>/dev/null || true

# Mark only the base rootfs bootstrap complete. Onboarding owns E/F/GUI/G/H.
touch "$FLUX_ROOTFS_PATH/.flux_configured"

# Best-effort install of the shared chroot helper for immediate subsequent stages.
HELPER_DST="/data/local/tmp/nativecode_chroot.sh"
for helper_src in \
    "$APP_HOME/nativecode_chroot.sh" \
    "/data/data/${PKG}/files/staged_scripts/nativecode_chroot.sh" \
    "$SCRIPT_DIR/nativecode_chroot.sh"
do
    if [ -f "$helper_src" ]; then
        cp -f "$helper_src" "$HELPER_DST"
        chmod 755 "$HELPER_DST"
        break
    fi
done

success "Alpaquita chroot base ready; onboarding will run E -> F -> GUI -> G -> H."
exit 0
