#!/system/bin/sh
CHROOT_DEST="/data/local/tmp/chrootAlpine"
while grep -q "$CHROOT_DEST" /proc/mounts 2>/dev/null; do
    awk -v t="$CHROOT_DEST" '$2 ~ t {print $2}' /proc/mounts | sort -r | while read -r m; do
        umount -l "$m" 2>/dev/null || true
    done
    sleep 1
done
rm -rf "$CHROOT_DEST"
