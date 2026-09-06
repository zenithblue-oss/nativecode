#!/bin/bash
# clean_rootfs.sh — credential + runtime-state scrub before archiving.
# Usage: tools/rootfs/clean_rootfs.sh /path/to/rootfs
# Fails (exit 1) if credentials found — remove them, do not just warn.
set -u
ROOTFS="${1:?usage: clean_rootfs.sh <rootfs-dir>}"
LEAK=0
for p in \
    /root/.ssh /home/flux/.ssh \
    /root/.git-credentials /home/flux/.git-credentials \
    /home/flux/.config/gh/hosts.yml /home/flux/.config/gh/hosts.yml.bak \
    /home/flux/.config/fluxlinux/cli-auth.env ; do
    if [ -e "$ROOTFS$p" ]; then echo "LEAK: $p" >&2; LEAK=1; fi
done
if [ -f "$ROOTFS/root/.npmrc" ]; then
    if grep -iq "authToken\|password\|_auth" "$ROOTFS/root/.npmrc" 2>/dev/null; then
        echo "LEAK: /root/.npmrc (auth token found)" >&2
        LEAK=1
    else
        rm -f "$ROOTFS/root/.npmrc"
    fi
fi
if [ -f "$ROOTFS/home/flux/.npmrc" ]; then
    if grep -iq "authToken\|password\|_auth" "$ROOTFS/home/flux/.npmrc" 2>/dev/null; then
        echo "LEAK: /home/flux/.npmrc (auth token found)" >&2
        LEAK=1
    else
        rm -f "$ROOTFS/home/flux/.npmrc"
    fi
fi
if [ "$LEAK" = "1" ]; then echo "clean_rootfs: credential leak — refusing to archive" >&2; exit 1; fi
rm -rf \
    "$ROOTFS/etc/machine-id" "$ROOTFS/var/lib/dbus/machine-id" \
    "$ROOTFS/tmp/"* "$ROOTFS/var/tmp/"* "$ROOTFS/var/cache/apk/"* \
    "$ROOTFS/root/.cache" "$ROOTFS/home/flux/.cache" \
    "$ROOTFS/root/.npm" "$ROOTFS/home/flux/.npm" \
    "$ROOTFS/root/.bash_history" "$ROOTFS/home/flux/.bash_history" 2>/dev/null || true
mkdir -p "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/run" "$ROOTFS/tmp" "$ROOTFS/var/tmp"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp" 2>/dev/null || true
if [ -x "$ROOTFS/usr/bin/strip" ]; then
    chroot "$ROOTFS" /usr/bin/strip /home/flux/.local/bin/kiro* 2>/dev/null || true
fi
echo "clean_rootfs: scrub OK"
