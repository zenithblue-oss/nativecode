#!/bin/sh
# setup_alpaquita_family.sh — Alpaquita glibc guest onboarding phase E.
# Idempotent. Run as root inside guest. POSIX sh. No bashisms.
set -eu

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

DISTRO_NAME="${1:-alpaquita}"

echo "FluxLinux: Configuring ${DISTRO_NAME} (Alpaquita glibc Family)..."

# Source shared rootfs helpers when available
NC_COMMON="/usr/local/lib/nativecode/rootfs-common.sh"
if [ -f "$NC_COMMON" ]; then
    # shellcheck source=/dev/null
    . "$NC_COMMON" 2>/dev/null || true
else
    nc_is_prebuilt_rootfs() { [ -f /etc/nativecode/rootfs-release ] && grep -q '^FORMAT_VERSION=1' /etc/nativecode/rootfs-release 2>/dev/null; }
    nc_component_baked() { [ -n "${1:-}" ] && [ -f /etc/nativecode/rootfs-release ] && grep -q "^COMPONENT_$1=1" /etc/nativecode/rootfs-release 2>/dev/null; }
    nc_runtime_tmp_repair() { mkdir -p /tmp /var/tmp 2>/dev/null || true; chmod 1777 /tmp /var/tmp 2>/dev/null || true; export TMPDIR=/tmp; }
    nc_runtime_dns_repair() { [ -s /etc/resolv.conf ] || printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf 2>/dev/null || true; }
    nc_runtime_machine_id() { :; }
    nc_runtime_home_repair() { :; }
fi

if nc_component_baked E 2>/dev/null; then
    echo "NativeCode: E already baked — runtime repair only"
    PREBUILT_E=1
else
    PREBUILT_E=0
fi

# 1. DNS & tmp repair
nc_runtime_dns_repair 2>/dev/null || true
nc_runtime_tmp_repair 2>/dev/null || true
nc_runtime_machine_id 2>/dev/null || true

# 2. Package installation (legacy path only)
if [ "$PREBUILT_E" = "0" ]; then
    echo "FluxLinux: Updating APK repositories..."
    apk update
    echo "FluxLinux: Installing Alpaquita glibc base packages..."
    apk add --no-cache \
        bash sudo shadow coreutils glibc-locales tzdata ca-certificates \
        curl wget unzip tar xz dbus dbus-x11 libstdc++ fontconfig font-dejavu \
        mesa mesa-dri-gallium mesa-gl mesa-vulkan-freedreno vulkan-loader \
        glycin-image-rs glycin-svg gdk-pixbuf git python3 nodejs npm
fi

# 3. Validation / verification of baked binaries
if [ "$PREBUILT_E" = "1" ]; then
    command -v bash >/dev/null 2>&1 || { echo "NativeCode: ERROR: bash missing in prebuilt rootfs" >&2; exit 1; }
    command -v gh >/dev/null 2>&1 || { echo "NativeCode: ERROR: gh missing in prebuilt rootfs (COMPONENT_GH=1 expected)" >&2; exit 1; }

    # bwrap shim signature verification & repair
    if grep -qE 'NativeCode proot|FluxLinux proot' /usr/bin/bwrap 2>/dev/null; then
        echo "NativeCode: bwrap shim signature OK"
    else
        echo "NativeCode: bwrap shim missing/wrong — repairing offline from baked template"
        cat > /usr/bin/bwrap <<'BWRAP_EOF'
#!/bin/sh
# NativeCode proot: skip sandbox; run glycin loader (regular file only).
while [ $# -gt 0 ]; do
  case "$1" in
    /*) if [ -f "$1" ] && [ -x "$1" ]; then exec "$@"; fi ;;
  esac
  shift
done
exit 127
BWRAP_EOF
        chmod 755 /usr/bin/bwrap
    fi
fi

# 4. Sudo NOPASSWD for flux
mkdir -p /etc/sudoers.d
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux
chmod 0440 /etc/sudoers.d/flux
if [ -f /etc/sudoers ] && ! grep -qE '@includedir[[:space:]]+/etc/sudoers\.d' /etc/sudoers 2>/dev/null; then
    echo '@includedir /etc/sudoers.d' >> /etc/sudoers
fi
chmod 4755 /usr/bin/sudo 2>/dev/null || true

# 5. VNC xstartup
if id flux >/dev/null 2>&1; then
    mkdir -p /home/flux/.vnc
    cat > /home/flux/.vnc/xstartup <<'EOF'
#!/bin/sh
export PULSE_SERVER=127.0.0.1
[ -f "$HOME/.Xresources" ] && xrdb "$HOME/.Xresources"
exec startxfce4
EOF
    chmod +x /home/flux/.vnc/xstartup
fi

# 6. PRoot vs chroot home repair
nc_runtime_home_repair flux 2>/dev/null || true

_flux_fix_apk_writable() {
    _ref_u=$(stat -c %u /etc 2>/dev/null || true)
    _ref_g=$(stat -c %g /etc 2>/dev/null || true)
    [ -n "$_ref_u" ] || return 0
    for p in /lib/apk /var/lib/apk /var/cache/apk /var/log /etc/apk; do
        [ -e "$p" ] || continue
        chown -R "$_ref_u:$_ref_g" "$p" 2>/dev/null || true
    done
    mkdir -p /lib/apk/db /var/lib/apk/db /var/cache/apk /var/log 2>/dev/null || true
    rm -f /lib/apk/db/lock /var/lib/apk/db/lock 2>/dev/null || true
    touch /var/lib/apk/db/lock 2>/dev/null || true
    chmod 666 /var/lib/apk/db/lock 2>/dev/null || true
    touch /var/log/apk.log 2>/dev/null || true
    chmod 666 /var/log/apk.log 2>/dev/null || true
    return 0
}
_flux_fix_apk_writable

mkdir -p /etc/fluxlinux
if [ ! -s /etc/fluxlinux/gpu_mode ]; then
    printf '%s\n' virgl > /etc/fluxlinux/gpu_mode
    echo "FluxLinux: gpu_mode=virgl (first-paint; hw-accel may upgrade)"
fi

echo "FluxLinux: ${DISTRO_NAME} Alpaquita glibc setup complete!"
exit 0
