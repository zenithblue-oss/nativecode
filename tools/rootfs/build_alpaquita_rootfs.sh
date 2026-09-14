#!/bin/bash
# build_alpaquita_rootfs.sh — reproducible prebuilt Alpaquita glibc rootfs for PRoot + chroot.
# Host: any Linux with tar, sha256sum, curl/wget, zstd.
# Guest assembly runs inside an Alpaquita aarch64 glibc container/chroot.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/versions.env"

ALPAQUITA_MINIROOTFS_SHA256="${ALPAQUITA_MINIROOTFS_SHA256_OVERRIDE:-$ALPAQUITA_MINIROOTFS_SHA256}"

WORK="${WORK:-/tmp/nc-rootfs-alpaquita}"
OUT="${OUT:-$SCRIPT_DIR/out}"
ROOTFS=""
ASSEMBLE_ONLY=0
SKIP_ANDROID=0

while [ $# -gt 0 ]; do
    case "$1" in
        --work) WORK="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --rootfs) ROOTFS="$2"; shift 2 ;;
        --assemble-only) ASSEMBLE_ONLY=1; shift ;;
        --skip-android) SKIP_ANDROID=1; shift ;;
        --build-id) BUILD_ID="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

[ -n "$ROOTFS" ] || ROOTFS="$WORK/rootfs"
mkdir -p "$WORK" "$OUT"

log() { echo "build-alpaquita-rootfs: $*"; }

# BLOCKER gate: official minirootfs checksum must be pinned before any download/extract/build.
if [ -z "${ALPAQUITA_MINIROOTFS_SHA256:-}" ] || [ "$ALPAQUITA_MINIROOTFS_SHA256" = "REPLACE_ME" ]; then
    echo "build-alpaquita-rootfs: ERROR: ALPAQUITA_MINIROOTFS_SHA256 is not pinned in tools/rootfs/versions.env" >&2
    echo "build-alpaquita-rootfs: download ${ALPAQUITA_MINIROOTFS_URL}" >&2
    exit 1
fi
case "$ALPAQUITA_MINIROOTFS_SHA256" in
    *[^0-9a-f]*|"") echo "build-alpaquita-rootfs: ERROR: ALPAQUITA_MINIROOTFS_SHA256 malformed (want 64 lowercase hex)" >&2; exit 1 ;;
esac
if [ "${#ALPAQUITA_MINIROOTFS_SHA256}" -ne 64 ]; then
    echo "build-alpaquita-rootfs: ERROR: ALPAQUITA_MINIROOTFS_SHA256 malformed (want 64 lowercase hex, got ${#ALPAQUITA_MINIROOTFS_SHA256} chars)" >&2
    exit 1
fi

if [ "$ASSEMBLE_ONLY" = "0" ]; then
    MINI="$WORK/alpaquita-minirootfs-$ALPAQUITA_VERSION-glibc-aarch64.tar.gz"
    # Check if local download exists
    if [ -f "/home/user/Downloads/alpaquita-minirootfs-stream-latest-glibc-aarch64.tar.gz" ]; then
        log "copying from local Downloads cache"
        cp "/home/user/Downloads/alpaquita-minirootfs-stream-latest-glibc-aarch64.tar.gz" "$MINI"
    elif [ ! -s "$MINI" ]; then
        log "downloading $ALPAQUITA_MINIROOTFS_URL"
        if command -v curl >/dev/null 2>&1; then
            curl -fL -o "$MINI" "$ALPAQUITA_MINIROOTFS_URL"
        else
            wget -O "$MINI" "$ALPAQUITA_MINIROOTFS_URL"
        fi
    fi
    echo "$ALPAQUITA_MINIROOTFS_SHA256  $MINI" | sha256sum -c -
    log "minirootfs SHA256 OK"
    rm -rf "$ROOTFS"
    mkdir -p "$ROOTFS"
    log "extracting minirootfs -> $ROOTFS"
    tar xzpf "$MINI" -C "$ROOTFS" --numeric-owner
    mkdir -p "$ROOTFS/etc/apk"
    printf '%s\n%s\n' "$ALPAQUITA_REPOS_CORE" "$ALPAQUITA_REPOS_UNIVERSE" > "$ROOTFS/etc/apk/repositories"
fi

if [ ! -x "$ROOTFS/sbin/apk" ] && [ ! -x "$ROOTFS/bin/busybox" ] && [ ! -e "$ROOTFS/bin/sh" ]; then
    echo "build-alpaquita-rootfs: ERROR: $ROOTFS does not look like an Alpaquita rootfs" >&2
    exit 1
fi

# Copy guest assembly: runs INSIDE the new rootfs
ASSEMBLE="$WORK/assemble-inside.sh"
cp "$SCRIPT_DIR/rootfs-common.sh" "$WORK/rootfs-common.sh"
cp "$SCRIPT_DIR/gpu-backend.sh" "$WORK/gpu-backend.sh"
cp "$SCRIPT_DIR/clean_rootfs.sh" "$WORK/clean_rootfs.sh"
cp "$SCRIPT_DIR/verify_rootfs.sh" "$WORK/verify_rootfs.sh"
[ ! -f "$SCRIPT_DIR/test_xfce_overlay.py" ] || cp "$SCRIPT_DIR/test_xfce_overlay.py" "$WORK/test_xfce_overlay.py"
chmod +x "$WORK/clean_rootfs.sh" "$WORK/verify_rootfs.sh"
grep -v '^#' "$SCRIPT_DIR/packages-alpaquita-base.txt" | grep -v '^$' | tr '\n' ' ' > "$WORK/base.pkgs"
grep -v '^#' "$SCRIPT_DIR/packages-alpaquita-dev.txt" | grep -v '^$' | tr '\n' ' ' > "$WORK/dev.pkgs"

# Stage G desktop customization assets from app repository into $WORK/xfce-staging
mkdir -p "$WORK/xfce-staging"
if [ -d "$SCRIPT_DIR/../../app/src/main/assets/xfce" ]; then
    cp -a "$SCRIPT_DIR/../../app/src/main/assets/xfce/." "$WORK/xfce-staging/"
fi

# Stage GUI bundle if prebuilt artifact exists
mkdir -p "$WORK/gui-staging"
if [ -f "$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst" ]; then
    cp "$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst" "$WORK/gui-staging/"
    if [ -f "$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst.sha256" ]; then
        cp "$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst.sha256" "$WORK/gui-staging/"
    fi
fi

# Stage overlay
mkdir -p "$WORK/rootfs-overlay"
if [ -d "$SCRIPT_DIR/overlay/alpaquita" ]; then
    cp -a "$SCRIPT_DIR/overlay/alpaquita/." "$WORK/rootfs-overlay/"
fi

cat > "$ASSEMBLE" <<ASSEMBLE_EOF
#!/bin/sh
set -eu
guest_log() { echo "assemble-inside: \$*"; }

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
mkdir -p /tmp /var/tmp && chmod 1777 /tmp /var/tmp
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf
printf '%s\n%s\n' "$ALPAQUITA_REPOS_CORE" "$ALPAQUITA_REPOS_UNIVERSE" > /etc/apk/repositories
apk update
# shellcheck disable=SC2046
apk add --no-cache \$(cat /pkg-lists/base.pkgs) \$(cat /pkg-lists/dev.pkgs)
apk add --no-cache mesa-vulkan-virtio vulkan-tools mesa-utils 2>/dev/null || true

# flux user
if ! id flux >/dev/null 2>&1; then
    if command -v useradd >/dev/null 2>&1; then
        useradd -m -u 1000 -s /bin/bash flux 2>/dev/null || useradd -m -s /bin/bash flux
    else
        adduser -D -s /bin/sh flux
    fi
    echo "flux:flux" | chpasswd 2>/dev/null || true
fi
for g in wheel audio video netdev input; do addgroup flux "\$g" 2>/dev/null || true; done
mkdir -p /etc/sudoers.d
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux
chmod 0440 /etc/sudoers.d/flux
if [ -f /etc/sudoers ] && ! grep -qE '@includedir[[:space:]]+/etc/sudoers\.d' /etc/sudoers 2>/dev/null; then
    echo '@includedir /etc/sudoers.d' >> /etc/sudoers
fi

# bwrap shim (proot-safe glycin)
cat > /usr/bin/bwrap <<'BWRAP_EOF'
#!/bin/sh
# NativeCode proot: skip sandbox; run glycin loader (regular file only).
while [ \$# -gt 0 ]; do
  case "\$1" in
    /*) if [ -f "\$1" ] && [ -x "\$1" ]; then exec "\$@"; fi ;;
  esac
  shift
done
exit 127
BWRAP_EOF
chmod 755 /usr/bin/bwrap

# Install GUI bundle from canonical .tar.zst
if [ -f /gui-staging/nativecode-gui-alpaquita-glibc-aarch64.tar.zst ]; then
    guest_log "verifying and extracting GUI bundle .tar.zst"
    if [ ! -f /gui-staging/nativecode-gui-alpaquita-glibc-aarch64.tar.zst.sha256 ]; then
        echo "assemble-inside: FATAL: Missing GUI bundle SHA256 checksum file!" >&2
        exit 1
    fi
    cd /gui-staging && sha256sum -c nativecode-gui-alpaquita-glibc-aarch64.tar.zst.sha256
    zstd -dc /gui-staging/nativecode-gui-alpaquita-glibc-aarch64.tar.zst | tar -xpf - -C /
else
    guest_log "WARN: GUI bundle not found in /gui-staging"
fi

# VNC xstartup
mkdir -p /home/flux/.vnc
cat > /home/flux/.vnc/xstartup <<'XEOF'
#!/bin/sh
export PULSE_SERVER=127.0.0.1
[ -f "\$HOME/.Xresources" ] && xrdb "\$HOME/.Xresources"
exec startxfce4
XEOF
chmod +x /home/flux/.vnc/xstartup

# Bake G desktop customization assets (themes, icons, cursors, wallpapers, OMZ)
THEME_DIR="/usr/share/themes"
ICON_DIR="/usr/share/icons"
WALLPAPER_DIR="/home/flux/Pictures/Wallpapers"
mkdir -p "\$THEME_DIR" "\$ICON_DIR" "\$WALLPAPER_DIR"

if [ -d /xfce-staging ]; then
    [ ! -f /xfce-staging/Space-transparency.tar.xz ] || tar -xf /xfce-staging/Space-transparency.tar.xz -C "\$THEME_DIR" 2>/dev/null || true
    [ ! -f /xfce-staging/Space-light.tar.xz ] || tar -xf /xfce-staging/Space-light.tar.xz -C "\$THEME_DIR" 2>/dev/null || true
    [ ! -f /xfce-staging/01-Vimix-cursors.tar.xz ] || tar -xf /xfce-staging/01-Vimix-cursors.tar.xz -C "\$ICON_DIR" 2>/dev/null || true
    [ ! -f /xfce-staging/02-Vimix-white-cursors.tar.xz ] || tar -xf /xfce-staging/02-Vimix-white-cursors.tar.xz -C "\$ICON_DIR" 2>/dev/null || true
    if [ -f /xfce-staging/papirus-dark-only.tar.gz ]; then
        mkdir -p "\$ICON_DIR/Papirus-Dark"
        tar -xzf /xfce-staging/papirus-dark-only.tar.gz -C "\$ICON_DIR/Papirus-Dark" --strip-components=1 2>/dev/null || \
            tar -xzf /xfce-staging/papirus-dark-only.tar.gz -C "\$ICON_DIR" 2>/dev/null || true
    fi
    [ ! -f /xfce-staging/fluxlinux-dark.png ] || cp -f /xfce-staging/fluxlinux-dark.png "\$WALLPAPER_DIR/fluxlinux-dark.png"
    [ ! -f /xfce-staging/fluxlinux-light.png ] || cp -f /xfce-staging/fluxlinux-light.png "\$WALLPAPER_DIR/fluxlinux-light.png"
fi

# Bake XFCE overlay
if [ -d /rootfs-overlay/home/flux/.config/xfce4 ]; then
    mkdir -p /home/flux/.config
    cp -a /rootfs-overlay/home/flux/.config/xfce4 /home/flux/.config/
    chown -R 1000:1000 /home/flux/.config /home/flux/Pictures 2>/dev/null || true
    chmod -R u+rwX,go+rX /home/flux/.config
else
    echo "assemble-inside: FATAL: /rootfs-overlay/home/flux/.config/xfce4 missing" >&2
    exit 1
fi

# Bake iceauth compatibility symlink to remove runtime /cache/gui-work dependency
if [ -x /usr/bin/iceauth ]; then
    mkdir -p /cache/gui-work/destdir/usr/bin
    ln -sfn /usr/bin/iceauth /cache/gui-work/destdir/usr/bin/iceauth
fi

# Bake Oh My Zsh + plugins + theme as flux
su -s /bin/sh flux -c '
    set -e
    git clone --depth 1 --single-branch https://github.com/ohmyzsh/ohmyzsh.git "\$HOME/.oh-my-zsh" 2>/dev/null || true
    if [ -d "\$HOME/.oh-my-zsh" ]; then
        ZSH_CUSTOM="\$HOME/.oh-my-zsh/custom"
        mkdir -p "\$ZSH_CUSTOM/plugins" "\$ZSH_CUSTOM/themes"
        git clone --depth 1 --single-branch https://github.com/zsh-users/zsh-autosuggestions.git "\$ZSH_CUSTOM/plugins/zsh-autosuggestions" 2>/dev/null || true
        git clone --depth 1 --single-branch https://github.com/zsh-users/zsh-syntax-highlighting.git "\$ZSH_CUSTOM/plugins/zsh-syntax-highlighting" 2>/dev/null || true
        curl -fsSL --connect-timeout 10 --max-time 25 "https://raw.githubusercontent.com/zakaziko99/agnosterzak-ohmyzsh-theme/master/agnosterzak.zsh-theme" -o "\$ZSH_CUSTOM/themes/agnosterzak.zsh-theme" 2>/dev/null || true
    fi
' || true

# Install official GitHub CLI (gh) Linux ARM64 glibc binary
guest_log "installing GitHub CLI release binary"
GH_TAR="/tmp/gh_${GH_VERSION}_linux_arm64.tar.gz"
curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_arm64.tar.gz" -o "\$GH_TAR"
echo "${GH_SHA256}  \$GH_TAR" | sha256sum -c -
tar -xzf "\$GH_TAR" -C /tmp
cp "/tmp/gh_${GH_VERSION}_linux_arm64/bin/gh" /usr/local/bin/gh
chmod 755 /usr/local/bin/gh
rm -rf /tmp/gh*

# helper + release marker placeholder
mkdir -p /usr/local/lib/nativecode /etc/nativecode
cp /pkg-lists/rootfs-common.sh /usr/local/lib/nativecode/rootfs-common.sh
chmod 644 /usr/local/lib/nativecode/rootfs-common.sh
if [ -f /pkg-lists/gpu-backend.sh ]; then
    cp /pkg-lists/gpu-backend.sh /usr/local/lib/nativecode/gpu-backend.sh
    chmod 644 /usr/local/lib/nativecode/gpu-backend.sh
elif [ -f /rootfs-overlay/usr/local/lib/nativecode/gpu-backend.sh ]; then
    cp /rootfs-overlay/usr/local/lib/nativecode/gpu-backend.sh /usr/local/lib/nativecode/gpu-backend.sh
    chmod 644 /usr/local/lib/nativecode/gpu-backend.sh
fi
if [ -x /rootfs-overlay/usr/local/bin/gpu-launch ]; then
    mkdir -p /usr/local/bin
    cp /rootfs-overlay/usr/local/bin/gpu-launch /usr/local/bin/gpu-launch
    chmod 755 /usr/local/bin/gpu-launch
fi

# AI CLIs as flux
su -s /bin/sh flux -c '
  set -e
  export PATH="\$HOME/.local/bin:\$PATH"
  npm config set prefix "\$HOME/.local"
  npm install -g opencode-ai@'"${OPENCODE_VERSION:-1.18.27}"' @openai/codex@'"${CODEX_VERSION:-0.153.2}"' @qwen-code/qwen-code@'"${QWEN_VERSION:-0.23.0}"'
  (cd "\$HOME/.local/lib/node_modules/opencode-ai" && node postinstall.mjs)
  command -v opencode
  opencode --version
  rm -f "\$HOME/.npmrc"
  curl -fsSL "'"${AGY_INSTALLER_URL:-https://antigravity.google/cli/install.sh}"'" | bash || true
  command -v agy >/dev/null 2>&1 || (sleep 5; curl -fsSL "'"${AGY_INSTALLER_URL:-https://antigravity.google/cli/install.sh}"'" | bash || true)
  curl -fsSL "'"${CLAUDE_INSTALLER_URL:-https://claude.ai/install.sh}"'" | bash || true
  curl -fsSL "'"${GROK_INSTALLER_URL:-https://x.ai/cli/install.sh}"'" | bash || true
  curl -fsSL "'"${KIRO_INSTALLER_URL:-https://cli.kiro.dev/install}"'" | bash || true
'

# Normalize Grok / Kiro locations into ~/.local/bin before verification
if [ -x /home/flux/.grok/bin/grok ]; then
    mkdir -p /home/flux/.local/bin
    ln -sfn /home/flux/.grok/bin/grok /home/flux/.local/bin/grok
fi
if [ ! -x /home/flux/.local/bin/kiro-cli ] && [ -x /home/flux/.local/bin/kiro ]; then
    ln -sfn /home/flux/.local/bin/kiro /home/flux/.local/bin/kiro-cli
fi

mkdir -p /home/flux/.config/fluxlinux
cat > /home/flux/.config/fluxlinux/cli-tools.env <<'CLIEOF'
# Managed by NativeCode prebuilt rootfs
export PATH="\$HOME/.local/bin:\$HOME/.grok/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH"
CLIEOF

# Android SDK minimal
if [ -d /opt-staging/android-sdk ]; then
    mkdir -p /opt
    cp -a /opt-staging/android-sdk /opt/android-sdk
fi
if [ -d /opt-staging/kotlin ]; then mkdir -p /opt && cp -a /opt-staging/kotlin /opt/kotlin; fi
if [ -d /opt-staging/gradle ]; then mkdir -p /opt && cp -a /opt-staging/gradle /opt/gradle; fi

# Create symlinks for adb and fastboot into standard PATH if present
if [ -x /opt/android-sdk/platform-tools/adb ]; then
    mkdir -p /usr/local/bin
    ln -sfn /opt/android-sdk/platform-tools/adb /usr/local/bin/adb
fi
if [ -x /opt/android-sdk/platform-tools/fastboot ]; then
    mkdir -p /usr/local/bin
    ln -sfn /opt/android-sdk/platform-tools/fastboot /usr/local/bin/fastboot
fi

# Bake Android environment profile into DEV rootfs
if [ -d /opt/android-sdk ] || [ -d /opt/kotlin ] || [ -d /opt/gradle ]; then
    mkdir -p /etc/profile.d
    cat > /etc/profile.d/nativecode-android.sh <<'ANDROID_EOF'
export ANDROID_HOME="\${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="\${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_NDK_HOME="\${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/29.0.14206865}"
case ":\$PATH:" in
    *:/opt/android-sdk/platform-tools:*) ;;
    *) export PATH="/opt/android-sdk/platform-tools:\$PATH" ;;
esac
case ":\$PATH:" in
    *:/opt/android-sdk/build-tools/36.1.0:*) ;;
    *) export PATH="/opt/android-sdk/build-tools/36.1.0:\$PATH" ;;
esac
case ":\$PATH:" in
    *:/opt/gradle/bin:*) ;;
    *) export PATH="/opt/gradle/bin:\$PATH" ;;
esac
case ":\$PATH:" in
    *:/opt/kotlin/bin:*) ;;
    *) export PATH="/opt/kotlin/bin:\$PATH" ;;
esac
ANDROID_EOF
    chmod 644 /etc/profile.d/nativecode-android.sh
fi

guest_log "verifying all baked AI CLIs execute"
su -s /bin/sh flux -c '
  export PATH="\$HOME/.local/bin:\$HOME/.grok/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH"
  opencode --version
  codex --version
  qwen --version
  claude --version
  agy --version
  grok --version
  kiro-cli --version
'

guest_log "guest assembly finished"
ASSEMBLE_EOF
chmod +x "$ASSEMBLE"

log "guest assemble script: $ASSEMBLE"
log "ROOTFS=$ROOTFS"
log "next: run guest assembly inside rootfs (preserving chroot exit status and cleaning up):"
log "  docker run --rm --privileged --platform linux/arm64 \\"
log "    -v $ROOTFS:/rootfs \\"
log "    -v $WORK:/host-work \\"
log "    bellsoft/alpaquita-linux-base:stream-glibc \\"
log "    sh -c 'set -eu; \\"
log "           cleanup() { \\"
log "             umount /rootfs/dev/pts 2>/dev/null || true; \\"
log "             umount /rootfs/run 2>/dev/null || true; \\"
log "             umount /rootfs/sys 2>/dev/null || true; \\"
log "             umount /rootfs/proc 2>/dev/null || true; \\"
log "             umount /rootfs/dev 2>/dev/null || true; \\"
log "             rm -rf /rootfs/assemble.sh /rootfs/pkg-lists /rootfs/opt-staging /rootfs/xfce-staging /rootfs/gui-staging /rootfs/rootfs-overlay; \\"
log "           }; \\"
log "           trap cleanup EXIT INT TERM; \\"
log "           mkdir -p /rootfs/pkg-lists /rootfs/opt-staging /rootfs/xfce-staging /rootfs/gui-staging /rootfs/rootfs-overlay /rootfs/dev /rootfs/proc /rootfs/sys /rootfs/run /rootfs/dev/pts; \\"
log "           mount --bind /dev /rootfs/dev; \\"
log "           mount -t proc proc /rootfs/proc; \\"
log "           mount -t sysfs sysfs /rootfs/sys; \\"
log "           mount --bind /run /rootfs/run; \\"
log "           mount --bind /dev/pts /rootfs/dev/pts; \\"
log "           cp /host-work/base.pkgs /host-work/dev.pkgs /host-work/rootfs-common.sh /host-work/gpu-backend.sh /rootfs/pkg-lists/; \\"
log "           ([ ! -d /host-work/opt-staging ] || cp -a /host-work/opt-staging/. /rootfs/opt-staging/); \\"
log "           ([ ! -d /host-work/xfce-staging ] || cp -a /host-work/xfce-staging/. /rootfs/xfce-staging/); \\"
log "           ([ ! -d /host-work/gui-staging ] || cp -a /host-work/gui-staging/. /rootfs/gui-staging/); \\"
log "           ([ ! -d /host-work/rootfs-overlay ] || cp -a /host-work/rootfs-overlay/. /rootfs/rootfs-overlay/); \\"
log "           cp /host-work/assemble-inside.sh /rootfs/assemble.sh && chmod +x /rootfs/assemble.sh; \\"
log "           chroot /rootfs /bin/sh /assemble.sh'"

# Determine actual COMPONENT_DEV status:
HAS_DEV_STAGED=0
if [ "$SKIP_ANDROID" = "0" ] && \
   [ -f "$WORK/opt-staging/android-sdk/platforms/android-$ANDROID_PLATFORM/android.jar" ] && \
   [ -x "$WORK/opt-staging/android-sdk/build-tools/$ANDROID_BUILD_TOOLS/aapt2" ] && \
   [ -d "$WORK/opt-staging/android-sdk/ndk/$ANDROID_NDK" ] && \
   [ -x "$WORK/opt-staging/kotlin/bin/kotlinc" ] && \
   [ -x "$WORK/opt-staging/gradle/bin/gradle" ]; then
    HAS_DEV_STAGED=1
fi

if [ "$HAS_DEV_STAGED" = "1" ]; then
    COMPONENT_DEV=1
    log "Android dev stack fully staged -> COMPONENT_DEV=1"
else
    COMPONENT_DEV=0
    log "Android dev stack incomplete or skipped -> COMPONENT_DEV=0"
fi

# Write release marker
GIT_SHA="$(git -C "$SCRIPT_DIR/../.." rev-parse HEAD 2>/dev/null || echo unknown)"
cat > "$WORK/rootfs-release" <<RELEASE_EOF
FORMAT_VERSION=$FORMAT_VERSION
DISTRO=alpaquita
DISTRO_VERSION=$ALPAQUITA_VERSION
LIBC=glibc
ARCH=aarch64
BUILD_ID=$BUILD_ID
BUILD_GIT_SHA=$GIT_SHA
NODE_MAJOR=$NODE_MAJOR
ANDROID_PLATFORM=$ANDROID_PLATFORM
ANDROID_BUILD_TOOLS=$ANDROID_BUILD_TOOLS
ANDROID_NDK=$ANDROID_NDK
KOTLIN=$KOTLIN_VERSION
GRADLE=$GRADLE_VERSION
COMPONENT_E=1
COMPONENT_F=1
COMPONENT_GUI=1
COMPONENT_G=1
COMPONENT_H=1
COMPONENT_GH=1
COMPONENT_DEV=$COMPONENT_DEV
RELEASE_EOF
log "release marker staged at $WORK/rootfs-release"

cat > "$WORK/finalize.sh" <<'FINALIZE_EOF'
#!/bin/bash
# finalize.sh ROOTFS OUT
set -euo pipefail
ROOTFS="$1"; OUT="${2:-}"
[ -n "$ROOTFS" ] || { echo "usage: finalize.sh ROOTFS [OUT]" >&2; exit 2; }
mkdir -p "$ROOTFS/etc/nativecode"
[ -z "$OUT" ] || mkdir -p "$OUT"
cp "$(dirname "$0")/rootfs-release" "$ROOTFS/etc/nativecode/rootfs-release"
chmod 644 "$ROOTFS/etc/nativecode/rootfs-release"

SCRIPT_DIR_LOC="$(cd "$(dirname "$0")" && pwd)"

if [ -f "$SCRIPT_DIR_LOC/clean_rootfs.sh" ]; then
    bash "$SCRIPT_DIR_LOC/clean_rootfs.sh" "$ROOTFS"
else
    echo "finalize.sh: clean_rootfs.sh not found in $SCRIPT_DIR_LOC" >&2
    exit 1
fi

echo "finalize.sh: running mandatory verification..."
if [ -f "$SCRIPT_DIR_LOC/verify_rootfs.sh" ]; then
    bash "$SCRIPT_DIR_LOC/verify_rootfs.sh" "$ROOTFS"
else
    echo "finalize.sh: verify_rootfs.sh not found in $SCRIPT_DIR_LOC" >&2
    exit 1
fi
echo "finalize.sh: verification passed"
FINALIZE_EOF
chmod +x "$WORK/finalize.sh"
log "wrote $WORK/finalize.sh + $WORK/assemble-inside.sh"
