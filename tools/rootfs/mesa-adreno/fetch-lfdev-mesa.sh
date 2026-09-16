#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/sources.lock"

OUT="${OUT:-$SCRIPT_DIR/out/nativecode-turnip}"
CACHE="${CACHE:-$SCRIPT_DIR/cache}"
ARCHIVE="$CACHE/$LFDEV_ARTIFACT"
URL="$LFDEV_REPO/releases/download/$LFDEV_TAG/$LFDEV_ARTIFACT"

NATIVE_BUNDLE="$CACHE/nativecode-turnip-alpaquita.tar.gz"
if [ -s "$NATIVE_BUNDLE" ]; then
    echo "mesa-adreno: unpacking Alpaquita-native LFDev Mesa 26.3 bundle from $NATIVE_BUNDLE"
    rm -rf "$OUT"
    mkdir -p "$OUT"
    tar -xzf "$NATIVE_BUNDLE" -C "$OUT"
    cd "$OUT/lib"
    ln -sf libEGL.so.1 libEGL_mesa.so.0 2>/dev/null || true
    ln -sf libGL.so.1 libGLX_mesa.so.0 2>/dev/null || true
    cd - >/dev/null
else
    mkdir -p "$CACHE"
    if [ ! -s "$ARCHIVE" ]; then
        curl -fL --retry 3 -o "$ARCHIVE" "$URL"
    fi
    printf '%s  %s\n' "$LFDEV_SHA256" "$ARCHIVE" | sha256sum -c -

    rm -rf "$OUT"
    mkdir -p "$OUT/lib/dri" "$OUT/icd" "$OUT/share"
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    tar -xzf "$ARCHIVE" -C "$TMP"
    LIB="$TMP/usr/lib/aarch64-linux-gnu"

    cp -a "$LIB/." "$OUT/lib/"
    cp -a "$TMP/usr/share/drirc.d" "$OUT/share/"
    cp -a "$TMP/usr/share/glvnd" "$OUT/share/"
    cp "$TMP/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" "$OUT/icd/"
    sed -i 's#"library_path": ".*"#"library_path": "/opt/nativecode-turnip/lib/libvulkan_freedreno.so"#' \
        "$OUT/icd/freedreno_icd.aarch64.json"
fi

for required in \
    lib/libvulkan_freedreno.so lib/dri/kgsl_dri.so lib/dri/msm_dri.so \
    lib/libEGL_mesa.so.0 lib/libGLX_mesa.so.0 icd/freedreno_icd.aarch64.json
do
    [ -e "$OUT/$required" ] || { echo "mesa-adreno: missing $required" >&2; exit 1; }
done
cat > "$OUT/source.env" <<EOF
LFDEV_REPO=$LFDEV_REPO
LFDEV_TAG=$LFDEV_TAG
LFDEV_COMMIT=$LFDEV_COMMIT
LFDEV_ARTIFACT=$LFDEV_ARTIFACT
LFDEV_SHA256=$LFDEV_SHA256
EOF
echo "mesa-adreno: staged LFDev Mesa 26.3 at $OUT"
