#!/usr/bin/env bash
# w10_fast_build.sh — one-command fast engineering build for NativeCode Alpaquita rootfs.
# Reuses a persistent GUI build cache and Docker builder image, runs contract tests first,
# assembles the ARM64 rootfs, verifies it, and emits alpaquita_rootfs.tar.zst.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/versions.env"

CACHE="${NATIVECODE_ROOTFS_CACHE:-$HOME/.cache/nativecode-rootfs}"
OUT="${OUT:-$SCRIPT_DIR/out}"
ROOT_WORK="${ROOT_WORK:-$CACHE/rootfs-work}"
GUI_WORK="${GUI_WORK:-$CACHE/gui-work}"
FAST=1
CLEAN_ROOT=0
SKIP_ANDROID=0
SKIP_TESTS=0
SKIP_GUI_BUILD=0

usage() {
    cat <<'EOF'
Usage: tools/rootfs/w10_fast_build.sh [options]

Options:
  --release          use zstd level 19 instead of fast level 3
  --clean-root       discard the assembled rootfs work tree before build
  --skip-android     build COMPONENT_DEV=0 unless staging is already supplied
  --skip-tests       skip preflight shell contract tests
  --skip-gui-build   require an already verified GUI bundle in tools/rootfs/out
  --cache DIR        persistent build cache directory
  --out DIR          artifact output directory

Environment:
  JOBS=N                     parallel compile jobs (default: host CPU count)
  NATIVECODE_ROOTFS_CACHE=   persistent download/build cache
  ALPAQUITA_MINIROOTFS_SHA256_OVERRIDE=... (tests only)
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --release) FAST=0; shift ;;
        --clean-root) CLEAN_ROOT=1; shift ;;
        --skip-android) SKIP_ANDROID=1; shift ;;
        --skip-tests) SKIP_TESTS=1; shift ;;
        --skip-gui-build) SKIP_GUI_BUILD=1; shift ;;
        --cache) CACHE="$2"; ROOT_WORK="$2/rootfs-work"; GUI_WORK="$2/gui-work"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "w10-fast: unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

mkdir -p "$CACHE" "$OUT" "$GUI_WORK"
[ "$CLEAN_ROOT" = "0" ] || rm -rf "$ROOT_WORK"
mkdir -p "$ROOT_WORK"

JOBS="${JOBS:-$(nproc)}"
if [ "$FAST" = "1" ]; then
    GUI_ZSTD_LEVEL=3
    ROOT_ZSTD_LEVEL=3
    BUILD_KIND=engineering-fast
else
    GUI_ZSTD_LEVEL=19
    ROOT_ZSTD_LEVEL=19
    BUILD_KIND=release-compression
fi

log() { printf 'w10-fast: %s\n' "$*"; }
fatal() { printf 'w10-fast: ERROR: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fatal "docker is required"
command -v sha256sum >/dev/null 2>&1 || fatal "sha256sum is required"
command -v zstd >/dev/null 2>&1 || fatal "host zstd is required for final archive"
docker info >/dev/null 2>&1 || fatal "Docker daemon is not available"

if [ "$SKIP_TESTS" = "0" ]; then
    log "running fast preflight tests before expensive ARM64 work"
    bash "$SCRIPT_DIR/test_build_pipeline.sh"
    if command -v unshare >/dev/null 2>&1 && unshare -rm true >/dev/null 2>&1; then
        bash "$SCRIPT_DIR/test_prebuilt_paths.sh"
    else
        log "SKIP test_prebuilt_paths.sh: unshare -rm unavailable"
    fi
    find "$REPO_ROOT/app/src/main/assets/scripts" "$SCRIPT_DIR" -type f -name '*.sh' -print0 |
        while IFS= read -r -d '' f; do
            first="$(head -n 1 "$f" || true)"
            case "$first" in
                *bash*) bash -n "$f" ;;
                *) sh -n "$f" ;;
            esac
        done
    log "preflight shell tests PASS"
fi

log "checking ARM64 execution through Docker/binfmt"
ARM64_PROBE="$(docker run --rm --platform linux/arm64 bellsoft/alpaquita-linux-base:stream-glibc uname -m 2>/dev/null || true)"
case "$ARM64_PROBE" in
    aarch64|arm64) ;;
    *) fatal "ARM64 Docker execution unavailable (got '$ARM64_PROBE'). Install/enable QEMU binfmt first." ;;
esac

GUI_BUNDLE="$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst"
GUI_SHA="$GUI_BUNDLE.sha256"

gui_bundle_valid() {
    [ -s "$GUI_BUNDLE" ] && [ -s "$GUI_SHA" ] &&
        (cd "$OUT" && sha256sum -c "$(basename "$GUI_SHA")" >/dev/null 2>&1)
}

build_gui_builder_image() {
    local manifest="$SCRIPT_DIR/packages-alpaquita-gui-build.txt"
    local deps_hash image
    deps_hash="$(sha256sum "$manifest" | awk '{print substr($1,1,16)}')"
    image="nativecode/alpaquita-gui-builder:$deps_hash"
    if docker image inspect "$image" >/dev/null 2>&1; then
        printf '%s\n' "$image"
        return 0
    fi

    log "building cached ARM64 GUI builder image $image"
    docker build --platform linux/arm64 -t "$image" -f - "$REPO_ROOT" <<'DOCKERFILE'
FROM bellsoft/alpaquita-linux-base:stream-glibc
COPY tools/rootfs/packages-alpaquita-gui-build.txt /tmp/gui-build.pkgs
RUN apk update && apk add \
    bash ca-certificates curl git tar xz zstd coreutils \
    $(grep -v '^#' /tmp/gui-build.pkgs | grep -v '^$' | tr '\n' ' ') \
    && rm -rf /var/cache/apk/*
WORKDIR /repo
DOCKERFILE
    printf '%s\n' "$image"
}

if ! gui_bundle_valid; then
    [ "$SKIP_GUI_BUILD" = "0" ] || fatal "GUI bundle missing/invalid and --skip-gui-build was requested"
    GUI_IMAGE="$(build_gui_builder_image | tail -n1)"
    log "building GUI bundle with persistent cache: $GUI_WORK"
    docker run --rm --platform linux/arm64 \
        -v "$REPO_ROOT:/repo" \
        -v "$GUI_WORK:/cache/gui-work" \
        -w /repo \
        -e WORK=/cache/gui-work \
        -e OUT=/repo/tools/rootfs/out \
        -e JOBS="$JOBS" \
        -e GUI_ZSTD_LEVEL="$GUI_ZSTD_LEVEL" \
        -e REUSE_GUI_BUNDLE=1 \
        "$GUI_IMAGE" \
        bash tools/rootfs/build_gui_bundle.sh
else
    log "CACHE HIT: verified GUI bundle; skipping XFCE/TigerVNC rebuild"
fi

gui_bundle_valid || fatal "GUI bundle did not pass checksum verification"

log "generating/extracting Alpaquita rootfs work tree"
ROOT_ARGS=(--work "$ROOT_WORK" --out "$OUT")
[ "$SKIP_ANDROID" = "0" ] || ROOT_ARGS+=(--skip-android)
bash "$SCRIPT_DIR/build_alpaquita_rootfs.sh" "${ROOT_ARGS[@]}"

ROOTFS="$ROOT_WORK/rootfs"
[ -e "$ROOTFS/bin/sh" ] || fatal "builder did not create $ROOTFS/bin/sh"

log "assembling ARM64 guest inside target chroot"
docker run --rm --privileged --platform linux/arm64 \
    -v "$ROOTFS:/rootfs" \
    -v "$ROOT_WORK:/host-work" \
    bellsoft/alpaquita-linux-base:stream-glibc \
    sh -c '
        set -eu
        cleanup() {
            umount /rootfs/dev/pts 2>/dev/null || true
            umount /rootfs/run 2>/dev/null || true
            umount /rootfs/sys 2>/dev/null || true
            umount /rootfs/proc 2>/dev/null || true
            umount /rootfs/dev 2>/dev/null || true
            rm -rf /rootfs/assemble.sh /rootfs/pkg-lists /rootfs/opt-staging /rootfs/xfce-staging /rootfs/gui-staging
        }
        trap cleanup EXIT INT TERM
        mkdir -p /rootfs/pkg-lists /rootfs/opt-staging /rootfs/xfce-staging /rootfs/gui-staging \
                 /rootfs/dev /rootfs/proc /rootfs/sys /rootfs/run /rootfs/dev/pts
        mount --bind /dev /rootfs/dev
        mount -t proc proc /rootfs/proc
        mount -t sysfs sysfs /rootfs/sys
        mount --bind /run /rootfs/run
        mount --bind /dev/pts /rootfs/dev/pts
        cp /host-work/base.pkgs /host-work/dev.pkgs /host-work/rootfs-common.sh /rootfs/pkg-lists/
        if [ -d /host-work/opt-staging ]; then cp -a /host-work/opt-staging/. /rootfs/opt-staging/; fi
        if [ -d /host-work/xfce-staging ]; then cp -a /host-work/xfce-staging/. /rootfs/xfce-staging/; fi
        if [ -d /host-work/gui-staging ]; then cp -a /host-work/gui-staging/. /rootfs/gui-staging/; fi
        cp /host-work/assemble-inside.sh /rootfs/assemble.sh
        chmod +x /rootfs/assemble.sh
        chroot /rootfs /bin/sh /assemble.sh
    '

log "running mandatory clean + verifier"
bash "$ROOT_WORK/finalize.sh" "$ROOTFS" "$OUT"

PREFIX="$OUT/nativecode-alpaquita-glibc-aarch64-rootfs"
log "archiving $BUILD_KIND rootfs with zstd level $ROOT_ZSTD_LEVEL"
ZSTD_LEVEL="$ROOT_ZSTD_LEVEL" bash "$SCRIPT_DIR/measure_rootfs.sh" "$ROOTFS" "$PREFIX"

FINAL_SRC="$PREFIX.tar.zst"
FINAL="$OUT/alpaquita_rootfs.tar.zst"
[ -s "$FINAL_SRC" ] || fatal "expected final archive missing: $FINAL_SRC"
rm -f "$FINAL" "$FINAL.sha256"
ln "$FINAL_SRC" "$FINAL" 2>/dev/null || cp -f "$FINAL_SRC" "$FINAL"
(
    cd "$OUT"
    sha256sum "alpaquita_rootfs.tar.zst" > "alpaquita_rootfs.tar.zst.sha256"
)

FINAL_SHA="$(awk 'NR==1 {print $1}' "$FINAL.sha256")"
FINAL_SIZE="$(stat -c '%s' "$FINAL")"

cat <<EOF

W10 FAST BUILD PASS
artifact: $FINAL
sha256:   $FINAL_SHA
bytes:    $FINAL_SIZE
mode:     $BUILD_KIND
cache:    $CACHE

Next device-test inputs:
  FLUX_ROOTFS_PATH=$FINAL
  FLUX_ROOTFS_SHA256=$FINAL_SHA

For release compression after functional testing:
  tools/rootfs/w10_fast_build.sh --release
EOF
