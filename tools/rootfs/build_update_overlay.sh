#!/bin/bash
# build_update_overlay.sh - host driver for the 1.8.0 update pack (non-root, no docker).
#
# Takes the 1.7.1 runtime archive (the exact bytes shipped as nativecode_runtime, pinned by sha256),
# unpacks it, and inside an aarch64 chroot (bubblewrap user namespace + qemu-user binfmt) :
#   - apk-installs the pinned OpenSSH (server + client) from the Alpaquita repo
#   - rebuilds zsh 5.9 with the BROKEN_KILL_ESRCH / SIGSUSPEND answers forced (zsh_update_build.sh)
#   - smoke-tests ssh -V, sshd -t, ssh-keygen, zsh $(...)
# then writes OUT/update-overlay.tar (dereferenced regular files only) for build_update_pack.py and
# runs that script (opencode, gh and ccusage come from DL, every download is checksum-verified there).
#
#   build_update_overlay.sh --old OUT/alpaquita_rootfs.tar.zst --dl DL_DIR --work WORK_DIR --out OUT_DIR
# Needs: bwrap, qemu-aarch64-static registered in binfmt_misc with the F flag, zstd, GNU tar, python3, curl.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$HERE/versions.env"

OLD="" DL="" WORK="" OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --old) OLD="$2"; shift 2 ;;
        --dl) DL="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        *) echo "unknown arg $1" >&2; exit 2 ;;
    esac
done
[ -n "$OLD" ] && [ -n "$DL" ] && [ -n "$WORK" ] && [ -n "$OUT" ] || { sed -n '2,15p' "$0"; exit 2; }

log() { echo "build-update-overlay: $*"; }
chroot_run() { # chroot_run ROOT cmd...  (aarch64 via qemu binfmt, uid 0 inside a user namespace)
    local root="$1"; shift
    bwrap --unshare-user --uid 0 --gid 0 --bind "$root" / --proc /proc --dev /dev --tmpfs /run \
        --ro-bind /etc/resolv.conf /etc/resolv.conf --setenv HOME /root --setenv TMPDIR /tmp \
        --setenv PATH /usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "$@"
}

# 1. the base must be the 1.7.1 archive Play already serves
actual="$(sha256sum "$OLD" | awk '{print $1}')"
[ "$actual" = "$NATIVECODE_ROOTFS_SHA256" ] || { echo "base archive sha256 $actual != pinned $NATIVECODE_ROOTFS_SHA256" >&2; exit 1; }

mkdir -p "$WORK" "$OUT" "$DL"
ROOT="$WORK/rootfs"
if [ ! -f "$WORK/.extracted" ]; then
    rm -rf "$ROOT"; mkdir -p "$ROOT"
    log "extracting base"
    zstd -dc --long=31 "$OLD" | tar -x -C "$ROOT" --no-same-owner --no-same-permissions -p
    touch "$WORK/.extracted"
fi

# 2. OpenSSH
log "apk add openssh=$OPENSSH_VERSION"
chroot_run "$ROOT" /bin/sh -c "apk update >/dev/null && apk add --no-cache 'openssh=$OPENSSH_VERSION'"
SSH_PKGS="openssh openssh-keygen libedit openssh-client-common openssh-client-default openssh-sftp-server openssh-server-common openssh-server"
chroot_run "$ROOT" /bin/sh -c "for p in $SSH_PKGS; do apk info -L \$p; done" \
    | grep -v ' contains:$' | grep -v '^$' | sort -u > "$WORK/openssh.files"
[ "$(wc -l < "$WORK/openssh.files")" -ge 18 ] || { echo "openssh file list too short" >&2; exit 1; }

# 3. zsh
log "building zsh $ZSH_VERSION"
mkdir -p "$ROOT/tmp/zb"
[ -f "$DL/zsh-$ZSH_VERSION.tar.xz" ] || curl -fsSL -o "$DL/zsh-$ZSH_VERSION.tar.xz" \
    "https://sourceforge.net/projects/zsh/files/zsh/$ZSH_VERSION/zsh-$ZSH_VERSION.tar.xz/download"
echo "$ZSH_SHA256  $DL/zsh-$ZSH_VERSION.tar.xz" | sha256sum -c - >/dev/null
cp "$DL/zsh-$ZSH_VERSION.tar.xz" "$HERE/patches/zsh-5.9-modern-ncurses.patch" "$HERE/zsh_update_build.sh" "$ROOT/tmp/zb/"
chroot_run "$ROOT" /bin/sh /tmp/zb/zsh_update_build.sh | tail -3

# 4. smoke tests inside the chroot (as the guest user's uid, no privilege separation chroot)
log "smoke tests"
chroot_run "$ROOT" /bin/sh -c '
set -e
ssh -V 2>&1
ssh-keygen -q -t ed25519 -N "" -f /tmp/nc_hostkey && rm -f /tmp/nc_hostkey /tmp/nc_hostkey.pub
printf "Port 8022\nListenAddress 127.0.0.1\nPasswordAuthentication no\n" > /tmp/nc_sshd_config
ssh-keygen -q -t ed25519 -N "" -f /tmp/nc_hk && echo "HostKey /tmp/nc_hk" >> /tmp/nc_sshd_config
/usr/sbin/sshd -t -f /tmp/nc_sshd_config && echo "sshd -t ok"
rm -f /tmp/nc_hk /tmp/nc_hk.pub /tmp/nc_sshd_config
test "$(/tmp/zb/dest/usr/bin/zsh-5.9 -fc "echo \$(echo ok)")" = ok && echo "zsh subst ok"
'

# 5. overlay: dereferenced regular files only (the pack extractor refuses symlinks)
STAGE="$WORK/stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
while read -r f; do
    mkdir -p "$STAGE/$(dirname "$f")"
    cp -L "$ROOT/$f" "$STAGE/$f"
done < "$WORK/openssh.files"
mkdir -p "$STAGE/usr/bin"
cp "$ROOT/tmp/zb/dest/usr/bin/zsh-$ZSH_VERSION" "$STAGE/usr/bin/zsh-$ZSH_VERSION"
tar -C "$STAGE" --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 -cf "$OUT/update-overlay.tar" .
log "overlay sha256 $(sha256sum "$OUT/update-overlay.tar" | awk '{print $1}')"

# 6. the pack
python3 -I "$HERE/build_update_pack.py" --dl "$DL" --overlay "$OUT/update-overlay.tar" --out "$OUT"
