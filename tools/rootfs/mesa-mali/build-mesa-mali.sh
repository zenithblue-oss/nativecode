#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/sources.lock"

SYSROOT="${SYSROOT:?set SYSROOT to the extracted Alpaquita aarch64 rootfs}"
WORK="${WORK:-$SCRIPT_DIR/work}"
DESTDIR="${DESTDIR:-$WORK/stage}"
JOBS="${JOBS:-$(nproc)}"
PREPARE_ONLY="${PREPARE_ONLY:-0}"
SOURCE="$WORK/mesa"
CROSS="$WORK/aarch64-alpaquita.ini"

for tool in git python3; do
    command -v "$tool" >/dev/null || { echo "mesa-mali: missing $tool" >&2; exit 1; }
done

mkdir -p "$WORK"
if [ ! -d "$SOURCE/.git" ]; then
    git clone --filter=blob:none "$MESA_REPO" "$SOURCE"
fi
for patch in "$SCRIPT_DIR"/patches/*.patch; do
    if git -C "$SOURCE" apply --recount --reverse --check "$patch" 2>/dev/null; then
        git -C "$SOURCE" apply --recount --reverse "$patch"
    fi
done
git -C "$SOURCE" fetch origin "$MESA_KBASE_COMMIT" --depth=1
git -C "$SOURCE" fetch "$UPSTREAM_REPO" "$PANFROST_V11_COMMIT" "$PANVK_V11_COMMIT" --depth=2
git -C "$SOURCE" checkout --detach "$MESA_KBASE_COMMIT"
git -C "$SOURCE" -c user.name=NativeCode -c user.email=build@nativecode.invalid \
    cherry-pick "$PANFROST_V11_COMMIT" "$PANVK_V11_COMMIT"
for patch in "$SCRIPT_DIR"/patches/*.patch; do
    git -C "$SOURCE" apply --recount --check "$patch"
    git -C "$SOURCE" apply --recount "$patch"
done
grep -q "panfrost_versions = .*'11'" "$SOURCE/src/gallium/drivers/panfrost/meson.build"
grep -q 'valhall_archs = \[9, 10, 11\]' "$SOURCE/src/panfrost/vulkan/meson.build"
grep -q 'panfrost_kbase_screen_create' "$SOURCE/src/gallium/winsys/panfrost/drm/panfrost_drm_winsys.c"
grep -q 'panfrost_open_device_kbase(screen, kbase_path, dev)' "$SOURCE/src/gallium/drivers/panfrost/pan_screen.c"
PATCHSET_HASH="$({ sha256sum "$SCRIPT_DIR/sources.lock"; sha256sum "$SCRIPT_DIR"/patches/*.patch; } | sha256sum | cut -d' ' -f1)"
echo "mesa-mali: prepared Kbase + official v11 patchset $PATCHSET_HASH"
[ "$PREPARE_ONLY" = "0" ] || exit 0

for tool in meson ninja aarch64-linux-gnu-gcc pkg-config; do
    command -v "$tool" >/dev/null || { echo "mesa-mali: missing $tool" >&2; exit 1; }
done
[ -e "$SYSROOT/lib/ld-linux-aarch64.so.1" ] || [ -e "$SYSROOT/lib64/ld-linux-aarch64.so.1" ] || {
    echo "mesa-mali: $SYSROOT is not an aarch64 glibc sysroot" >&2
    exit 1
}

sed "s|@SYSROOT@|$SYSROOT|g" "$SCRIPT_DIR/cross/aarch64-alpaquita.ini" > "$CROSS"
rm -rf "$WORK/build-target" "$DESTDIR"
meson setup "$WORK/build-target" "$SOURCE" \
    --cross-file "$CROSS" \
    --prefix=/opt/nativecode/mesa-mali \
    -Dbuildtype=release \
    -Dvulkan-drivers=panfrost,virtio,swrast \
    -Dgallium-drivers=panfrost,zink,virgl,llvmpipe \
    -Dpanfrost-kmds=kbase,panthor \
    -Dplatforms=x11 \
    -Degl=enabled -Dglx=dri -Dgles1=disabled -Dgles2=enabled \
    -Dmesa-clc=system -Dprecomp-compiler=system
ninja -C "$WORK/build-target" -j "$JOBS"
DESTDIR="$DESTDIR" ninja -C "$WORK/build-target" install

BUNDLE="$DESTDIR/opt/nativecode/mesa-mali"
python3 "$SCRIPT_DIR/manifest.py" "$BUNDLE"
python3 "$SCRIPT_DIR/manifest.py" --verify "$BUNDLE"
find "$BUNDLE" -type f \( -name '*.so' -o -name '*.so.*' \) -exec file {} +
find "$BUNDLE" -type f \( -name '*.so' -o -name '*.so.*' \) -exec readelf -h {} \; | grep -E 'Class:|Machine:'
echo "mesa-mali: bundle=$BUNDLE"
