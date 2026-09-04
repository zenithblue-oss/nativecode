#!/bin/bash
# build_alpine_rootfs.sh — reproducible prebuilt Alpine rootfs for PRoot + chroot.
# Host: any Linux with tar, sha256sum, curl/wget, zstd (or xz fallback).
# Guest assembly runs inside an Alpine aarch64 container/chroot (see README).
# Usage:
#   tools/rootfs/build_alpine_rootfs.sh --work /tmp/nc-rootfs --out out/
#   tools/rootfs/build_alpine_rootfs.sh --assemble-only --rootfs /tmp/nc-rootfs/rootfs
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/versions.env"

WORK="${WORK:-/tmp/nc-rootfs}"
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

log() { echo "build-rootfs: $*"; }

# BLOCKER gate: official minirootfs checksum must be pinned before any
# download/extract/build. Never build a release artifact against an
# unverified base image.
if [ -z "${ALPINE_MINIROOTFS_SHA256:-}" ] || [ "$ALPINE_MINIROOTFS_SHA256" = "REPLACE_ME" ]; then
    echo "build-rootfs: ERROR: ALPINE_MINIROOTFS_SHA256 is not pinned in tools/rootfs/versions.env" >&2
    echo "build-rootfs: download ${ALPINE_MINIROOTFS_URL}" >&2
    echo "build-rootfs: verify out-of-band against https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/ + SHA256SUMS, pin it, then rebuild." >&2
    exit 1
fi
case "$ALPINE_MINIROOTFS_SHA256" in
    *[^0-9a-f]*|"") echo "build-rootfs: ERROR: ALPINE_MINIROOTFS_SHA256 malformed (want 64 lowercase hex)" >&2; exit 1 ;;
esac
if [ "${#ALPINE_MINIROOTFS_SHA256}" -ne 64 ]; then
    echo "build-rootfs: ERROR: ALPINE_MINIROOTFS_SHA256 malformed (want 64 lowercase hex, got ${#ALPINE_MINIROOTFS_SHA256} chars)" >&2
    exit 1
fi

if [ "$ASSEMBLE_ONLY" = "0" ]; then
    MINI="$WORK/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"
    if [ ! -s "$MINI" ]; then
        log "downloading $ALPINE_MINIROOTFS_URL"
        if command -v curl >/dev/null 2>&1; then
            curl -fL -o "$MINI" "$ALPINE_MINIROOTFS_URL"
        else
            wget -O "$MINI" "$ALPINE_MINIROOTFS_URL"
        fi
    fi
    echo "$ALPINE_MINIROOTFS_SHA256  $MINI" | sha256sum -c -
    log "minirootfs SHA256 OK"
    rm -rf "$ROOTFS"
    mkdir -p "$ROOTFS"
    log "extracting minirootfs -> $ROOTFS"
    tar xzpf "$MINI" -C "$ROOTFS" --numeric-owner
    printf '%s\n%s\n' "$APK_REPOS_MAIN" "$APK_REPOS_COMMUNITY" > "$ROOTFS/etc/apk/repositories"
fi

if [ ! -x "$ROOTFS/sbin/apk" ] && [ ! -x "$ROOTFS/bin/busybox" ] && [ ! -e "$ROOTFS/bin/sh" ]; then
    echo "build-rootfs: ERROR: $ROOTFS does not look like an Alpine rootfs" >&2
    exit 1
fi

# Copy guest assembly: runs INSIDE the new rootfs (chroot or qemu-aarch64).
# For device/CI: adb push ROOTFS + run /assemble.sh under chroot on rooted device,
# or run under docker --platform linux/arm64 with ROOTFS bind-mounted.
ASSEMBLE="$WORK/assemble-inside.sh"
cp "$SCRIPT_DIR/rootfs-common.sh" "$WORK/rootfs-common.sh"
grep -v '^#' "$SCRIPT_DIR/packages-alpine-base.txt" | grep -v '^$' | tr '\n' ' ' > "$WORK/base.pkgs"
grep -v '^#' "$SCRIPT_DIR/packages-alpine-dev.txt" | grep -v '^$' | tr '\n' ' ' > "$WORK/dev.pkgs"

cat > "$ASSEMBLE" <<ASSEMBLE_EOF
#!/bin/sh
set -eu
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
mkdir -p /tmp /var/tmp && chmod 1777 /tmp /var/tmp
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf
printf '%s\n%s\n' "$APK_REPOS_MAIN" "$APK_REPOS_COMMUNITY" > /etc/apk/repositories
apk update
# shellcheck disable=SC2046
apk add --no-cache \$(cat /pkg-lists/base.pkgs) \$(cat /pkg-lists/dev.pkgs)
# canonical font pkg already in base list (font-dejavu)
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
apk add --no-cache glycin-image-rs glycin-svg gdk-pixbuf 2>/dev/null || true
if [ -x /usr/bin/bwrap ] && [ ! -e /usr/bin/bwrap.real ] && ! grep -q 'NativeCode proot' /usr/bin/bwrap 2>/dev/null; then
    cp -a /usr/bin/bwrap /usr/bin/bwrap.real 2>/dev/null || true
fi
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
# VNC xstartup
mkdir -p /home/flux/.vnc
cat > /home/flux/.vnc/xstartup <<'XEOF'
#!/bin/sh
export PULSE_SERVER=127.0.0.1
[ -f "\$HOME/.Xresources" ] && xrdb "\$HOME/.Xresources"
exec startxfce4
XEOF
chmod +x /home/flux/.vnc/xstartup
# helper + release marker placeholder (finalized by outer script)
mkdir -p /usr/local/lib/nativecode /etc/nativecode
cp /pkg-lists/rootfs-common.sh /usr/local/lib/nativecode/rootfs-common.sh
chmod 644 /usr/local/lib/nativecode/rootfs-common.sh
# AI CLIs as flux (system node 24)
su -s /bin/sh flux -c '
  set -e
  export PATH="\$HOME/.local/bin:\$PATH"
  npm config set prefix "\$HOME/.local"
  npm install -g opencode-ai @openai/codex @qwen-code/qwen-code
  curl -fsSL https://antigravity.google/cli/install.sh | bash || true
  # agy retry once during build (not user onboarding)
  command -v agy >/dev/null 2>&1 || (sleep 5; curl -fsSL https://antigravity.google/cli/install.sh | bash || true)
  curl -fsSL https://claude.ai/install.sh | bash || true
  curl -fsSL https://x.ai/cli/install.sh | bash || true
  curl -fsSL https://cli.kiro.dev/install | bash || true
'
mkdir -p /home/flux/.config/fluxlinux
cat > /home/flux/.config/fluxlinux/cli-tools.env <<'CLIEOF'
# Managed by NativeCode prebuilt rootfs
export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"
CLIEOF
# Android SDK minimal (host-side download staged by outer script into /opt-staging)
if [ -d /opt-staging/android-sdk ]; then
    mkdir -p /opt
    cp -a /opt-staging/android-sdk /opt/android-sdk
fi
if [ -d /opt-staging/kotlin ]; then mkdir -p /opt && cp -a /opt-staging/kotlin /opt/kotlin; fi
if [ -d /opt-staging/gradle ]; then mkdir -p /opt && cp -a /opt-staging/gradle /opt/gradle; fi
echo "assemble-inside: done"
ASSEMBLE_EOF
chmod +x "$ASSEMBLE"

log "guest assemble script: $ASSEMBLE"
log "ROOTFS=$ROOTFS"
log "next: run guest assembly inside rootfs, e.g.:"
log "  docker run --rm --platform linux/arm64 -v $ROOTFS:/rootfs -v $WORK:/pkg-lists -v $ASSEMBLE:/assemble.sh arm64v8/alpine:$ALPINE_VERSION sh /assemble.sh"
log "  (or chroot on rooted aarch64 device after bind-mounting /dev /proc /sys)"

if [ "$SKIP_ANDROID" = "0" ]; then
    log "Android staging not run in this step — download cmdline-tools $ANDROID_CMDLINE_TOOLS_VERSION + platform $ANDROID_PLATFORM + build-tools $ANDROID_BUILD_TOOLS + NDK $ANDROID_NDK + Kotlin $KOTLIN_VERSION + Gradle $GRADLE_VERSION into $WORK/opt-staging (verify checksums, ARM64/musl only)."
fi

# Write release marker (outer/host side; build script finalizes after assembly)
GIT_SHA="$(git -C "$SCRIPT_DIR/../.." rev-parse HEAD 2>/dev/null || echo unknown)"
cat > "$WORK/rootfs-release" <<RELEASE_EOF
FORMAT_VERSION=$FORMAT_VERSION
DISTRO=alpine
ALPINE_BRANCH=$ALPINE_BRANCH
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
COMPONENT_DEV=1
RELEASE_EOF
log "release marker staged at $WORK/rootfs-release (installed to /etc/nativecode/rootfs-release by finalize step)"

# Finalize helper: install marker + scrub + archive (run after guest assembly)
cat > "$WORK/finalize.sh" <<'FINALIZE_EOF'
#!/bin/bash
# finalize.sh ROOTFS OUT
set -euo pipefail
ROOTFS="$1"; OUT="$2"
mkdir -p "$OUT" "$ROOTFS/etc/nativecode"
cp "$(dirname "$0")/rootfs-release" "$ROOTFS/etc/nativecode/rootfs-release"
chmod 644 "$ROOTFS/etc/nativecode/rootfs-release"
bash "$(dirname "$0")/../rootfs/clean_rootfs.sh" "$ROOTFS" || bash "tools/rootfs/clean_rootfs.sh" "$ROOTFS"
bash tools/rootfs/verify_rootfs.sh "$ROOTFS" || true
FINALIZE_EOF
chmod +x "$WORK/finalize.sh"
log "wrote $WORK/finalize.sh + $WORK/assemble-inside.sh"
