#!/bin/sh
# setup_alpine_family.sh
# Post-install base desktop for Alpine (proot + chroot). apk + musl.
# Creates user flux, installs XFCE4. Must run as root inside the guest.

DISTRO_NAME="${1:-alpine}"

echo "FluxLinux: Configuring ${DISTRO_NAME} (Alpine Family)..."

if [ "$(id -u)" -ne 0 ]; then
    echo "FluxLinux: ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
# NOTE: never append inherited $PATH here — under proot-distro login it carries
# host $PREFIX/bin, and host tools leak into the guest and fail to link.

# Prebuilt-rootfs helpers (baked at /usr/local/lib/nativecode/rootfs-common.sh).
NC_COMMON="/usr/local/lib/nativecode/rootfs-common.sh"
if [ -f "$NC_COMMON" ]; then
    # shellcheck source=/dev/null
    . "$NC_COMMON"
else
    NC_RELEASE_FILE="${NC_RELEASE_FILE:-/etc/nativecode/rootfs-release}"
    nc_is_prebuilt_rootfs() { [ -f "$NC_RELEASE_FILE" ] && grep -q '^FORMAT_VERSION=1' "$NC_RELEASE_FILE" 2>/dev/null; }
    nc_component_baked() { [ -n "${1:-}" ] && nc_is_prebuilt_rootfs && grep -q "^COMPONENT_$1=1" "$NC_RELEASE_FILE" 2>/dev/null; }
    nc_is_proot() { [ "${NATIVECODE_ISOLATION:-}" = "proot" ] && return 0; [ -n "${PROOT_TMP_DIR:-}" ] && return 0; return 1; }
    nc_runtime_home_repair() {
        _nc_user="${1:-flux}"; _nc_home="/home/$_nc_user"; [ -d "$_nc_home" ] || return 0
        mkdir -p "$_nc_home/.config" "$_nc_home/.cache" "$_nc_home/.local/share" 2>/dev/null || true
        if nc_is_proot; then
            chmod 755 "$_nc_home" 2>/dev/null || true
            chmod -R u+rwX "$_nc_home/.config" "$_nc_home/.cache" "$_nc_home/.local" 2>/dev/null || true
        else
            chown -R "$_nc_user:$_nc_user" "$_nc_home" 2>/dev/null || chown -R "$_nc_user" "$_nc_home" 2>/dev/null || true
            chmod 755 "$_nc_home" 2>/dev/null || true
        fi
    }
    nc_runtime_tmp_repair() { mkdir -p /tmp /var/tmp 2>/dev/null || true; chmod 1777 /tmp /var/tmp 2>/dev/null || true; unset PROOT_TMP_DIR; export TMPDIR=/tmp; }
    nc_runtime_dns_repair() {
        if [ ! -s /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
            printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > /etc/resolv.conf 2>/dev/null || true
        fi
    }
    nc_runtime_machine_id() {
        command -v dbus-uuidgen >/dev/null 2>&1 && dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
        mkdir -p /var/lib/dbus 2>/dev/null || true
        if [ ! -e /var/lib/dbus/machine-id ] && [ -f /etc/machine-id ]; then
            ln -sf /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || cp -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true
        fi
    }
fi

if nc_component_baked E; then
    echo "NativeCode: E already baked — runtime repair only"
    PREBUILT_E=1
else
    PREBUILT_E=0
fi

# Shared /tmp (--shared-tmp) must be sticky-writable for guest uid 1000.
nc_runtime_tmp_repair

# 1. DNS (minirootfs ships without resolv.conf; keep multi-NS for proot flakiness)
nc_runtime_dns_repair
if [ ! -s /etc/resolv.conf ]; then
    echo "FluxLinux: Writing /etc/resolv.conf"
    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > /etc/resolv.conf
fi

# 2. Repositories sanity (pin exact v3.24 main + community)
if [ -f /etc/apk/repositories ]; then
    grep -q 'alpine/v3.24/main' /etc/apk/repositories 2>/dev/null || \
        echo "https://dl-cdn.alpinelinux.org/alpine/v3.24/main" >> /etc/apk/repositories
    if ! grep -q 'community' /etc/apk/repositories 2>/dev/null; then
        echo "https://dl-cdn.alpinelinux.org/alpine/v3.24/community" >> /etc/apk/repositories
    fi
fi

if [ "$PREBUILT_E" = "1" ]; then
    echo "NativeCode: E packages baked — skipping apk update/install"
else
echo "FluxLinux: apk update..."
apk update || {
    echo "FluxLinux: apk update failed (network / repos)"
    exit 1
}

# 3. Bootstrap tools (bash for later scripts; shadow for useradd; sudo)
echo "FluxLinux: Installing base packages..."
apk add --no-cache \
    bash \
    sudo \
    shadow \
    ca-certificates \
    curl \
    wget \
    unzip \
    tar \
    xz \
    tzdata \
    musl-locales \
    dbus \
    dbus-x11 \
    || {
        echo "FluxLinux: base package install failed"
        exit 1
    }

# Session bus soft-depends on machine-id (empty /var/lib/dbus breaks some stacks)
nc_runtime_machine_id

# 4. XFCE desktop (always include xfce4-session for failsafe session name)
echo "FluxLinux: Installing XFCE4..."
apk add --no-cache \
    xfce4 \
    xfce4-session \
    xfce4-settings \
    xfce4-panel \
    xfce4-terminal \
    xfce4-screensaver \
    xfdesktop \
    xfwm4 \
    thunar \
    adwaita-icon-theme \
    font-dejavu \
    mesa-dri-gallium \
    mesa-gl \
    || {
        echo "FluxLinux: XFCE package install failed"
        exit 1
    }

if [ ! -e /usr/bin/startxfce4 ]; then
    echo "FluxLinux: ERROR: startxfce4 missing after apk install"
    exit 1
fi

# Glycin image loaders invoke bubblewrap; real bwrap fails under proot and aborts
# GTK (exit 134). Install a shim that drops sandbox flags but keeps --dbus-fd.
echo "FluxLinux: Installing proot-safe bwrap shim for glycin loaders..."
apk add --no-cache glycin-image-rs glycin-svg gdk-pixbuf 2>/dev/null || true
if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ] && \
   ! grep -qE 'FluxLinux proot|NativeCode proot' /usr/bin/bwrap 2>/dev/null; then
    cp -a /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
fi
cat > /usr/bin/bwrap <<'BWRAP_EOF'
#!/bin/sh
# NativeCode proot: skip sandbox; run glycin loader (regular file only — not /).
while [ $# -gt 0 ]; do
  case "$1" in
    /*)
      if [ -f "$1" ] && [ -x "$1" ]; then
        exec "$@"
      fi
      ;;
  esac
  shift
done
exit 127
BWRAP_EOF
chmod 755 /usr/bin/bwrap
fi # end legacy install (PREBUILT_E=0)

if [ "$PREBUILT_E" = "1" ]; then
    # Prebuilt fast path: validate baked binaries, no package manager.
    command -v startxfce4 >/dev/null 2>&1 || { echo "NativeCode: ERROR: startxfce4 missing in prebuilt rootfs"; exit 1; }
    command -v bash >/dev/null 2>&1 || { echo "NativeCode: ERROR: bash missing in prebuilt rootfs"; exit 1; }
    command -v gh >/dev/null 2>&1 || echo "NativeCode: WARN: gh missing in prebuilt rootfs (COMPONENT_GH=1 expected)"
    # bwrap: an executable alone is NOT enough — the real bubblewrap binary is
    # exactly what crashes glycin/GTK under PRoot (exit 134). Require the
    # NativeCode shim signature; repair offline from the baked template
    # (no apk, no network). Fail explicitly if repair is impossible.
    if grep -qE 'NativeCode proot|FluxLinux proot' /usr/bin/bwrap 2>/dev/null; then
        echo "NativeCode: bwrap shim signature OK"
    else
        echo "NativeCode: bwrap shim missing/wrong — repairing offline from baked template"
        if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ] && \
           ! grep -qE 'NativeCode proot|FluxLinux proot' /usr/bin/bwrap 2>/dev/null; then
            cp -a /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
        fi
        cat > /usr/bin/bwrap <<'BWRAP_EOF'
#!/bin/sh
# NativeCode proot: skip sandbox; run glycin loader (regular file only — not /).
while [ $# -gt 0 ]; do
  case "$1" in
    /*)
      if [ -f "$1" ] && [ -x "$1" ]; then
        exec "$@"
      fi
      ;;
  esac
  shift
done
exit 127
BWRAP_EOF
        chmod 755 /usr/bin/bwrap
        grep -qE 'NativeCode proot' /usr/bin/bwrap 2>/dev/null || \
            { echo "NativeCode: ERROR: bwrap shim repair failed — image incompatible"; exit 1; }
        echo "NativeCode: bwrap shim repaired offline"
    fi
    nc_runtime_machine_id
fi

# 5. User flux (prefer uid 1000 when free — matches common XFCE defaults)
if ! id flux >/dev/null 2>&1; then
    echo "FluxLinux: Creating user flux..."
    if command -v useradd >/dev/null 2>&1; then
        if ! id -u 1000 >/dev/null 2>&1; then
            useradd -m -u 1000 -s /bin/bash flux 2>/dev/null \
                || useradd -m -s /bin/bash flux 2>/dev/null \
                || useradd -m -s /bin/sh flux
        else
            useradd -m -s /bin/bash flux 2>/dev/null || useradd -m -s /bin/sh flux
        fi
    else
        adduser -D -s /bin/sh flux
    fi
    echo "flux:flux" | chpasswd 2>/dev/null || true
fi

# Groups (best-effort — names differ slightly across Alpine releases)
for g in wheel audio video netdev input; do
    addgroup flux "$g" 2>/dev/null || true
done

# 6. Sudo NOPASSWD
mkdir -p /etc/sudoers.d
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux
chmod 0440 /etc/sudoers.d/flux
# Alpine ships @includedir; re-assert if a minimal sudoers was replaced
if [ -f /etc/sudoers ] && ! grep -qE '@includedir[[:space:]]+/etc/sudoers\.d' /etc/sudoers 2>/dev/null; then
    echo '@includedir /etc/sudoers.d' >> /etc/sudoers
fi
chmod 4755 /usr/bin/sudo 2>/dev/null || true

# 7. Optional VNC xstartup parity with Debian family
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

# Ensure home ownership / writability — PRoot vs chroot diverge explicitly.
# PRoot: physical tree owned by Android app UID; must NOT chown to guest flux.
# Chroot: real ownership; chown to flux is correct.
nc_runtime_home_repair flux

unset LC_ALL
export LANG="${LANG:-C}"

_flux_fix_apk_writable() {
    _ref_u=$(stat -c %u /etc 2>/dev/null || true)
    _ref_g=$(stat -c %g /etc 2>/dev/null || true)
    [ -n "$_ref_u" ] || return 0
    for p in /lib/apk /var/cache/apk /var/log /etc/apk; do
        [ -e "$p" ] || continue
        chown -R "$_ref_u:$_ref_g" "$p" 2>/dev/null || true
    done
    mkdir -p /lib/apk/db /var/cache/apk /var/log 2>/dev/null || true
    rm -f /lib/apk/db/lock 2>/dev/null || true
    touch /lib/apk/db/lock 2>/dev/null || true
    chmod 666 /lib/apk/db/lock 2>/dev/null || true
    touch /var/log/apk.log 2>/dev/null || true
    chmod 666 /var/log/apk.log 2>/dev/null || true
    return 0
}
echo "FluxLinux: Ensuring apk database is writable (proot-safe)…"
_flux_fix_apk_writable

# GitHub CLI: mandatory in prebuilt rootfs (apk github-cli); legacy fallback installs.
if ! command -v gh >/dev/null 2>&1; then
    if [ "$PREBUILT_E" = "1" ]; then
        echo "NativeCode: WARN: gh missing despite COMPONENT_GH=1"
    else
        echo "FluxLinux: Installing github-cli (legacy rootfs)..."
        apk add --no-cache github-cli 2>/dev/null || echo "FluxLinux: WARN: github-cli install failed"
    fi
fi

mkdir -p /etc/fluxlinux
if [ ! -s /etc/fluxlinux/gpu_mode ]; then
    printf '%s\n' virgl > /etc/fluxlinux/gpu_mode
    echo "FluxLinux: gpu_mode=virgl (first-paint; hw-accel may upgrade)"
else
    echo "NativeCode: gpu_mode=$(tr -d '[:space:]' </etc/fluxlinux/gpu_mode) kept (F owns upgrades)"
fi

echo "FluxLinux: ${DISTRO_NAME} Alpine setup complete!"
exit 0
