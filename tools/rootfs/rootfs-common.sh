#!/bin/sh
# rootfs-common.sh — NativeCode prebuilt-rootfs helpers.
# Installed (baked) at /usr/local/lib/nativecode/rootfs-common.sh.
# Guest setup scripts source it when present, else use inline fallback.
# POSIX sh. No bashisms. Never append host $PATH.
# shellcheck shell=sh

NC_RELEASE_FILE="${NC_RELEASE_FILE:-/etc/nativecode/rootfs-release}"

nc_is_prebuilt_rootfs() {
    [ -f "$NC_RELEASE_FILE" ] || return 1
    grep -q '^FORMAT_VERSION=1' "$NC_RELEASE_FILE" 2>/dev/null || return 1
    return 0
}

nc_component_baked() {
    _nc_name="$1"
    [ -n "$_nc_name" ] || return 1
    nc_is_prebuilt_rootfs || return 1
    grep -q "^COMPONENT_${_nc_name}=1" "$NC_RELEASE_FILE" 2>/dev/null
}

# Isolation: Kotlin passes NATIVECODE_ISOLATION=proot|chroot explicitly.
# Env probes are fallback only — never rely on UID 0 (both modes run as root).
nc_is_proot() {
    [ "${NATIVECODE_ISOLATION:-}" = "proot" ] && return 0
    [ -n "${PROOT_TMP_DIR:-}" ] && return 0
    [ -n "${PROOT:-}" ] && return 0
    grep -q ' /tmp ' /proc/mounts 2>/dev/null && return 0
    return 1
}

nc_is_chroot() {
    [ "${NATIVECODE_ISOLATION:-}" = "chroot" ] && return 0
    if [ "${NATIVECODE_ISOLATION:-}" = "proot" ]; then
        return 1
    fi
    # Explicit chroot marker written by setup_alpine_chroot.sh
    [ -f /.dockerenv ] && return 1
    [ -n "${PROOT_TMP_DIR:-}" ] && return 1
    # Fallback: chroot tree carries Android bind mounts
    grep -q ' /sdcard ' /proc/mounts 2>/dev/null && return 0
    return 1
}

# PRoot: physical rootfs owned by Android app UID — never chown the tree to
# guest flux or the host app loses write access. Repair writability only.
# Chroot: real Linux ownership — chown to flux is correct.
nc_runtime_home_repair() {
    _nc_user="${1:-flux}"
    _nc_home="$(eval echo "~$_nc_user" 2>/dev/null || true)"
    [ -n "$_nc_home" ] || _nc_home="/home/$_nc_user"
    [ -d "$_nc_home" ] || return 0
    mkdir -p "$_nc_home/.config" "$_nc_home/.cache" "$_nc_home/.local/share" 2>/dev/null || true
    if nc_is_proot; then
        chmod 755 "$_nc_home" 2>/dev/null || true
        chmod -R u+rwX "$_nc_home/.config" "$_nc_home/.cache" "$_nc_home/.local" 2>/dev/null || true
    else
        chown -R "$_nc_user:$_nc_user" "$_nc_home" 2>/dev/null || \
            chown -R "$_nc_user" "$_nc_home" 2>/dev/null || true
        chmod 755 "$_nc_home" 2>/dev/null || true
    fi
    return 0
}

nc_runtime_tmp_repair() {
    mkdir -p /tmp /var/tmp 2>/dev/null || true
    chmod 1777 /tmp /var/tmp 2>/dev/null || true
    if [ -z "${NATIVECODE_ISOLATION:-}" ]; then
        :
    fi
    # Guest must use guest /tmp, never host PROOT_TMP_DIR as non-root uid.
    unset PROOT_TMP_DIR
    export TMPDIR=/tmp
    return 0
}

nc_runtime_dns_repair() {
    if [ ! -s /etc/resolv.conf ] || ! grep -q nameserver /etc/resolv.conf 2>/dev/null; then
        printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n' > /etc/resolv.conf 2>/dev/null || true
    fi
    return 0
}

# PRoot: /data is nosuid and rootfs files are app-uid (guest sees 1000), so
# real sudo always fails ownership/setuid checks. PATH-first wrapper execs.
# Chroot: real /usr/bin/sudo is uid 0 + setuid; wrapper execs it.
nc_runtime_sudo_repair() {
    mkdir -p /etc/sudoers.d /usr/local/bin 2>/dev/null || true
    echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux
    chmod 0440 /etc/sudoers.d/flux 2>/dev/null || true
    if [ -f /etc/sudoers ] && ! grep -qE '@includedir[[:space:]]+/etc/sudoers\.d' /etc/sudoers 2>/dev/null; then
        echo '@includedir /etc/sudoers.d' >> /etc/sudoers
    fi
    chmod 4755 /usr/bin/sudo 2>/dev/null || true
    cat > /usr/local/bin/sudo <<'NC_SUDO_EOF'
#!/bin/sh
# NativeCode PRoot sudo.
# Android /data is nosuid; extracted rootfs is app-uid (guest sees 1000).
# Real sudo refuses:
#   sudo: /etc/sudo.conf is owned by uid 1000, should be 0
#   sudo: /usr/bin/sudo must be owned by uid 0 and have the setuid bit set
# PRoot --change-id already maps the app UID to flux, so "escalate" is exec.
# Chroot with real setuid /usr/bin/sudo is used unchanged.
REAL=/usr/bin/sudo
_self=$(readlink -f "$0" 2>/dev/null || echo "$0")
_real=$(readlink -f "$REAL" 2>/dev/null || echo "$REAL")
if [ "$_self" != "$_real" ] && [ -x "$REAL" ]; then
    _owner=$(stat -c %u "$REAL" 2>/dev/null || echo "")
    _mode=$(stat -c %a "$REAL" 2>/dev/null || echo "")
    case "$_mode" in
        4*|2*|6*)
            if [ "$_owner" = "0" ]; then
                exec "$REAL" "$@"
            fi
            ;;
    esac
fi

while [ $# -gt 0 ]; do
    case "$1" in
        --) shift; break ;;
        -i|--login)
            shift
            exec "${SHELL:-/bin/bash}" -l "$@"
            ;;
        -s|--shell)
            shift
            exec "${SHELL:-/bin/bash}" "$@"
            ;;
        -n|-E|-H|-A|-K|-k|-l|-v|-b|-e|-f)
            shift
            ;;
        --non-interactive|--preserve-env|--set-home|--askpass|--reset-timestamp|--list|--validate|--background|--edit|--stdin)
            shift
            ;;
        --preserve-env=*|--prompt=*|--host=*|--user=*|--group=*|--other-user=*)
            shift
            ;;
        -u|--user|-g|--group|-h|--host|-p|--prompt|-U|--other-user|-C|--close-from|-D|--chdir|-R|--chroot)
            shift
            [ $# -gt 0 ] && shift
            ;;
        -*)
            shift
            ;;
        *)
            break
            ;;
    esac
done

if [ $# -eq 0 ]; then
    exec "${SHELL:-/bin/bash}"
fi

exec "$@"
NC_SUDO_EOF
    chmod 755 /usr/local/bin/sudo 2>/dev/null || true
    return 0
}

nc_runtime_machine_id() {
    if command -v dbus-uuidgen >/dev/null 2>&1; then
        dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
    fi
    mkdir -p /var/lib/dbus 2>/dev/null || true
    if [ ! -e /var/lib/dbus/machine-id ]; then
        if [ -f /etc/machine-id ]; then
            ln -sf /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || \
                cp -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true
        elif command -v dbus-uuidgen >/dev/null 2>&1; then
            dbus-uuidgen --ensure=/var/lib/dbus/machine-id 2>/dev/null || true
        fi
    fi
    return 0
}
