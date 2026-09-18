#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

WORK="${WORK:-/home/user/nc-phase-g-work}"
ROOTFS="$WORK/rootfs"
ORIG_ARCHIVE="$REPO_ROOT/tools/rootfs/out/alpaquita_rootfs.tar.zst"
if [ ! -f "$REPO_ROOT/tools/rootfs/out/alpaquita_rootfs.tar.zst.orig_xfce" ]; then
    echo "Creating safety backup of original XFCE rootfs archive..."
    cp "$ORIG_ARCHIVE" "$REPO_ROOT/tools/rootfs/out/alpaquita_rootfs.tar.zst.orig_xfce"
fi
ORIG_ARCHIVE="$REPO_ROOT/tools/rootfs/out/alpaquita_rootfs.tar.zst.orig_xfce"
OUTPUT_DIR="$REPO_ROOT/tools/rootfs/out"
ASSET_DIR="$REPO_ROOT/nativecode_runtime/src/main/assets"
BUNDLE_DIR="$REPO_ROOT/packages/phosh-lite"

echo "=== Phase G: Purge XFCE & Build Clean Phosh-Lite Rootfs ==="
echo "Work directory: $WORK"
mkdir -p "$WORK" "$OUTPUT_DIR"

if [ -d "$ROOTFS" ]; then
    echo "Found existing $ROOTFS, cleaning..."
    docker run --rm -v "$WORK":/work alpine rm -rf /work/rootfs || rm -rf "$ROOTFS"
fi
mkdir -p "$ROOTFS"

echo "==> [1/8] Unpacking existing canonical rootfs..."
tar --numeric-owner -xpf "$ORIG_ARCHIVE" -C "$ROOTFS"

echo "==> [2/8] Purging XFCE4 DE (session, window manager, desktop, panel, settings)..."
# Purge DE binaries
rm -f "$ROOTFS"/usr/bin/xfce4-session* \
      "$ROOTFS"/usr/bin/xfwm4* \
      "$ROOTFS"/usr/bin/xfdesktop* \
      "$ROOTFS"/usr/bin/xfce4-panel* \
      "$ROOTFS"/usr/bin/xfce4-settings* \
      "$ROOTFS"/usr/bin/xfce4-display-settings* \
      "$ROOTFS"/usr/bin/xfce4-mime-settings* \
      "$ROOTFS"/usr/bin/xfce4-mime-helper* \
      "$ROOTFS"/usr/bin/xfce4-mouse-settings* \
      "$ROOTFS"/usr/bin/xfce4-keyboard-settings* \
      "$ROOTFS"/usr/bin/xfce4-accessibility-settings* \
      "$ROOTFS"/usr/bin/xfce4-appfinder* \
      "$ROOTFS"/usr/bin/xfce4-about* \
      "$ROOTFS"/usr/bin/xfce-build* \
      "$ROOTFS"/usr/bin/xfce-do-release* \
      "$ROOTFS"/usr/bin/xfce-get-* \
      "$ROOTFS"/usr/bin/xfce-update-*

# Purge DE plugins, sessions, configs
rm -rf "$ROOTFS"/usr/lib/xfce4/panel \
       "$ROOTFS"/usr/lib/systemd/user/xfce* \
       "$ROOTFS"/usr/lib/systemd/user/xfconf* \
       "$ROOTFS"/usr/share/xfce4/panel \
       "$ROOTFS"/etc/xdg/xfce4 \
       "$ROOTFS"/home/flux/.config/xfce4 \
       "$ROOTFS"/usr/share/xsessions/xfce* \
       "$ROOTFS"/usr/share/wayland-sessions/xfce* \
       "$ROOTFS"/usr/share/backgrounds/xfce \
       "$ROOTFS"/usr/share/applications/xfce4-session* \
       "$ROOTFS"/usr/share/applications/xfce4-about* \
       "$ROOTFS"/usr/share/applications/xfce4-appfinder* \
       "$ROOTFS"/usr/share/applications/xfce-wm* \
       "$ROOTFS"/usr/share/applications/xfce-backdrop* \
       "$ROOTFS"/usr/share/applications/xfce-workspaces* \
       "$ROOTFS"/usr/share/applications/xfce-settings* \
       "$ROOTFS"/usr/share/applications/xfce4-settings* \
       "$ROOTFS"/usr/share/applications/xfce4-accessibility* \
       "$ROOTFS"/usr/share/applications/xfce-keyboard* \
       "$ROOTFS"/usr/share/applications/xfce-mouse* \
       "$ROOTFS"/usr/share/applications/xfce-display* \
       "$ROOTFS"/usr/share/applications/xfce4-mime* \
       "$ROOTFS"/usr/share/applications/panel-*.desktop

# Purge legacy themes, cursors, wallpapers
rm -rf "$ROOTFS"/usr/share/themes/Space-* \
       "$ROOTFS"/usr/share/themes/Default/xfwm4 \
       "$ROOTFS"/usr/share/themes/Default-hdpi/xfwm4 \
       "$ROOTFS"/usr/share/themes/Default-xhdpi/xfwm4 \
       "$ROOTFS"/usr/share/themes/Daloa/xfwm4 \
       "$ROOTFS"/usr/share/themes/Kokodi/xfwm4 \
       "$ROOTFS"/usr/share/themes/Moheli/xfwm4 \
       "$ROOTFS"/usr/share/icons/01-Vimix-cursors \
       "$ROOTFS"/usr/share/icons/02-Vimix-white-cursors \
       "$ROOTFS"/usr/share/icons/Papirus-Dark \
       "$ROOTFS"/home/flux/Pictures/Wallpapers/fluxlinux-*

# Stale build/tmp artifacts
rm -rf "$ROOTFS"/tmp/* "$ROOTFS"/var/tmp/*

echo "==> [3/8] Installing Phosh-Lite runtime dependencies via APK..."
# Mount rootfs into an Alpaquita container and invoke apk
docker run --rm --platform linux/arm64 \
    -v "$ROOTFS":/target -w /target \
    bellsoft/alpaquita-linux-base:stream-glibc sh -c "
        apk --root /target update && \
        apk --root /target --no-scripts add --no-cache \
            xcb-util-renderutil \
            xcb-util-keysyms \
            libpulse \
            polkit-elogind-libs \
            libelogind \
            libsecret \
            libpulse-mainloop-glib \
            mesa-egl \
            mesa-gles || true
        rm -f /target/var/lib/apk/db/triggers
    "

echo "==> [4/8] Extracting Phosh-Lite bundle & Adwaita icons..."
tar -xzf "$BUNDLE_DIR/phosh-lite-bundle.tar.gz" -C "$ROOTFS"
mkdir -p "$ROOTFS/usr/share/icons"
tar -xzf "$BUNDLE_DIR/adwaita-icons.tar.gz" -C "$ROOTFS/usr/share/icons"

echo "==> [5/8] Setting permissions, ld.so config, and desktop symlinks..."
chmod 755 "$ROOTFS/usr/local/bin/phoc" \
          "$ROOTFS/usr/local/bin/phosh-session" \
          "$ROOTFS/usr/local/bin/wvkbd-mobintl" \
          "$ROOTFS/usr/local/bin/nativecode-desktop" \
          "$ROOTFS/usr/local/libexec/phosh"
chmod -h 755 "$ROOTFS/usr/local/bin/phosh" 2>/dev/null || true
chmod 755 "$ROOTFS"/usr/local/lib64/lib*.so* 2>/dev/null || true

mkdir -p "$ROOTFS/etc/ld.so.conf.d"
echo "/usr/local/lib64" > "$ROOTFS/etc/ld.so.conf.d/local64.conf"

# Wrapper for startxfce4 to call nativecode-desktop for any legacy callers
cat << 'EOF' > "$ROOTFS/usr/bin/startxfce4"
#!/bin/sh
exec /usr/local/bin/nativecode-desktop "$@"
EOF
chmod 755 "$ROOTFS/usr/bin/startxfce4"
rm -f "$ROOTFS/usr/local/bin/startxfce4"
cp -f "$ROOTFS/usr/bin/startxfce4" "$ROOTFS/usr/local/bin/startxfce4"
ln -sfn ../libexec/phosh "$ROOTFS/usr/local/bin/phosh"

# Configure desktop entries visibility
if ls "$ROOTFS"/usr/share/applications/*.desktop >/dev/null 2>&1; then
    sed -i 's/OnlyShowIn=XFCE;/OnlyShowIn=XFCE;Phosh;GNOME;/g' "$ROOTFS"/usr/share/applications/*.desktop 2>/dev/null || true
    sed -i 's/OnlyShowIn=XFCE/OnlyShowIn=XFCE;Phosh;GNOME;/g' "$ROOTFS"/usr/share/applications/*.desktop 2>/dev/null || true
fi

# Ensure xfce4-terminal and thunar are visible in Phosh
if [ -f "$ROOTFS/usr/share/applications/xfce4-terminal.desktop" ]; then
    sed -i '/NoDisplay=true/d' "$ROOTFS/usr/share/applications/xfce4-terminal.desktop"
    if ! grep -q "OnlyShowIn" "$ROOTFS/usr/share/applications/xfce4-terminal.desktop"; then
        echo "OnlyShowIn=XFCE;Phosh;GNOME;" >> "$ROOTFS/usr/share/applications/xfce4-terminal.desktop"
    fi
fi
if [ -f "$ROOTFS/usr/share/applications/thunar.desktop" ]; then
    sed -i '/NoDisplay=true/d' "$ROOTFS/usr/share/applications/thunar.desktop"
    if ! grep -q "OnlyShowIn" "$ROOTFS/usr/share/applications/thunar.desktop"; then
        echo "OnlyShowIn=XFCE;Phosh;GNOME;" >> "$ROOTFS/usr/share/applications/thunar.desktop"
    fi
fi

# Update Phosh gschema override with empty favorites (so all apps show in grid) and disabled app-filter-mode
if [ -f "$ROOTFS/usr/local/share/glib-2.0/schemas/00_mobi.Phosh.gschema.override" ]; then
    if ! grep -q 'favorites=@as \[\]' "$ROOTFS/usr/local/share/glib-2.0/schemas/00_mobi.Phosh.gschema.override"; then
        cat << 'SCHEMA_EOF' >> "$ROOTFS/usr/local/share/glib-2.0/schemas/00_mobi.Phosh.gschema.override"

[sm.puri.phosh]
favorites=@as []
app-filter-mode=@as []
SCHEMA_EOF
    fi
fi

# Ensure gpu-backend.sh and gpu-env.sh are updated with verified Turnip/PanVK Zink settings
mkdir -p "$ROOTFS/usr/local/lib/nativecode" "$ROOTFS/etc/profile.d"
cp -f "$REPO_ROOT/tools/rootfs/gpu-backend.sh" "$ROOTFS/usr/local/lib/nativecode/gpu-backend.sh"
cp -f "$REPO_ROOT/tools/rootfs/overlay/alpaquita/etc/profile.d/gpu-env.sh" "$ROOTFS/etc/profile.d/gpu-env.sh"
chmod 755 "$ROOTFS/usr/local/lib/nativecode/gpu-backend.sh" "$ROOTFS/etc/profile.d/gpu-env.sh"

# Update /etc/nativecode/rootfs-release
if [ -f "$ROOTFS/etc/nativecode/rootfs-release" ]; then
    sed -i '/^DESKTOP=/d' "$ROOTFS/etc/nativecode/rootfs-release"
    sed -i '/^PHOSH_VERSION=/d' "$ROOTFS/etc/nativecode/rootfs-release"
    echo "DESKTOP=phosh" >> "$ROOTFS/etc/nativecode/rootfs-release"
    echo "PHOSH_VERSION=lite" >> "$ROOTFS/etc/nativecode/rootfs-release"
fi

echo "==> [6/8] Compiling GSettings schemas & updating icon caches..."
docker run --rm --privileged --platform linux/arm64 \
    -v "$ROOTFS":/target \
    bellsoft/alpaquita-linux-base:stream-glibc chroot /target /bin/sh -c "
        glib-compile-schemas /usr/local/share/glib-2.0/schemas
        glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
        gtk4-update-icon-cache -q -t -f /usr/share/icons/Adwaita 2>/dev/null || true
        gtk-update-icon-cache -q -t -f /usr/share/icons/Adwaita 2>/dev/null || true
        ldconfig 2>/dev/null || true
        rm -rf /var/cache/ldconfig/* /var/cache/apk/* /tmp/* /var/tmp/* 2>/dev/null || true
        chmod -R a+rX / 2>/dev/null || true
    "

echo "==> [7/8] Running credential and temporary state scrub..."
bash "$SCRIPT_DIR/clean_rootfs.sh" "$ROOTFS"

echo "==> [8/8] Measuring rootfs and repacking canonical archive..."
UNCOMPRESSED_BYTES=$(du -sb "$ROOTFS" | awk '{print $1}')
echo "Final uncompressed rootfs bytes: $UNCOMPRESSED_BYTES ($(( UNCOMPRESSED_BYTES / 1024 / 1024 )) MB)"

FINAL_TAR="$OUTPUT_DIR/alpaquita_rootfs.tar.zst"
FINAL_SHA="$OUTPUT_DIR/alpaquita_rootfs.tar.zst.sha256"

# Create archive at compression level 19 with multicore
echo "Compressing with zstd -19 -T0..."
rm -f "$FINAL_TAR" "$FINAL_SHA"
tar --numeric-owner -cpf - -C "$ROOTFS" . | zstd -19 -T0 -o "$FINAL_TAR"

(
    cd "$OUTPUT_DIR"
    sha256sum alpaquita_rootfs.tar.zst > alpaquita_rootfs.tar.zst.sha256
)

# Copy to app runtime assets
echo "Updating runtime asset copy..."
cp -f "$FINAL_TAR" "$ASSET_DIR/alpaquita_rootfs.tar.zst"
cp -f "$FINAL_SHA" "$ASSET_DIR/alpaquita_rootfs.tar.zst.sha256"

COMPRESSED_BYTES=$(stat -c '%s' "$FINAL_TAR")
echo "=========================================================="
echo "Phase G Rebuild COMPLETE!"
echo "New archive:    $FINAL_TAR"
echo "New size:       $COMPRESSED_BYTES bytes ($(( COMPRESSED_BYTES / 1024 / 1024 )) MB)"
echo "New SHA256:     $(cat "$FINAL_SHA")"
echo "=========================================================="
