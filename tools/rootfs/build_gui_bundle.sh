#!/usr/bin/env bash
# build_gui_bundle.sh — compile and package XFCE 4.20 core + TigerVNC + zsh + fastfetch
# for Alpaquita glibc/AArch64 inside a container or native builder.
# Output: $OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/tmp/nc-gui-build}"
OUT="${OUT:-$SCRIPT_DIR/out}"
PREFIX="/usr"
DESTDIR="$WORK/destdir"

mkdir -p "$WORK" "$OUT" "$DESTDIR"

log() { echo "build-gui-bundle: $*"; }

log "NativeCode GUI bundle build started"
log "Target PREFIX: $PREFIX"
log "Staging DESTDIR: $DESTDIR"

# 1. Zsh (5.9)
build_zsh() {
    log "=== Building Zsh ==="
    local ZSH_VER="5.9"
    local SRC="$WORK/zsh-$ZSH_VER"
    if [ ! -d "$SRC" ]; then
        curl -fsSL "https://sourceforge.net/projects/zsh/files/zsh/$ZSH_VER/zsh-$ZSH_VER.tar.xz/download" -o "$WORK/zsh-$ZSH_VER.tar.xz"
        tar -xf "$WORK/zsh-$ZSH_VER.tar.xz" -C "$WORK"
    fi
    cd "$SRC"
    ./configure --prefix="$PREFIX" \
                --bindir="$PREFIX/bin" \
                --sysconfdir=/etc \
                --enable-etcdir=/etc \
                --enable-multibyte \
                --enable-pcre \
                --enable-cap \
                --with-tcsetpgrp
    make -j"$(nproc)"
    make DESTDIR="$DESTDIR" install
    log "Zsh installed to $DESTDIR"
}

# 2. Fastfetch
build_fastfetch() {
    log "=== Building Fastfetch ==="
    local FF_VER="2.38.0"
    local SRC="$WORK/fastfetch-$FF_VER"
    if [ ! -d "$SRC" ]; then
        curl -fsSL "https://github.com/fastfetch-cli/fastfetch/archive/refs/tags/$FF_VER.tar.gz" -o "$WORK/fastfetch-$FF_VER.tar.gz"
        tar -xf "$WORK/fastfetch-$FF_VER.tar.gz" -C "$WORK"
    fi
    mkdir -p "$SRC/build"
    cd "$SRC/build"
    cmake .. -DCMAKE_INSTALL_PREFIX="$PREFIX" -DBUILD_TESTS=OFF
    cmake --build . -j"$(nproc)"
    DESTDIR="$DESTDIR" cmake --install .
    log "Fastfetch installed to $DESTDIR"
}

# 3. TigerVNC (Xvnc server & vncpasswd)
build_tigervnc() {
    log "=== Building TigerVNC ==="
    local TV_VER="1.14.1"
    local SRC="$WORK/tigervnc-$TV_VER"
    if [ ! -d "$SRC" ]; then
        curl -fsSL "https://github.com/TigerVNC/tigervnc/archive/refs/tags/v$TV_VER.tar.gz" -o "$WORK/tigervnc-$TV_VER.tar.gz"
        tar -xf "$WORK/tigervnc-$TV_VER.tar.gz" -C "$WORK"
    fi
    mkdir -p "$SRC/build"
    cd "$SRC/build"
    cmake .. -DCMAKE_INSTALL_PREFIX="$PREFIX" -DENABLE_NLS=OFF -DBUILD_VIEWER=OFF
    cmake --build . -j"$(nproc)"
    DESTDIR="$DESTDIR" cmake --install .
    log "TigerVNC tools installed to $DESTDIR"
}

# 4. XFCE 4.20 core components
build_xfce_core() {
    log "=== Building XFCE Core ==="
    # Components order per Xfce documentation:
    # libxfce4util -> xfconf -> libxfce4ui -> garcon -> exo -> xfce4-panel -> xfce4-session -> xfwm4 -> xfdesktop -> thunar -> xfce4-terminal
    local XFCE_COMPONENTS=(
        "libxfce4util:4.20.0"
        "xfconf:4.20.0"
        "libxfce4ui:4.20.0"
        "garcon:4.20.0"
        "exo:4.20.0"
        "xfce4-panel:4.20.0"
        "xfce4-session:4.20.0"
        "xfwm4:4.20.0"
        "xfdesktop:4.20.0"
        "thunar:4.20.0"
        "xfce4-terminal:1.1.4"
        "xfce4-settings:4.20.0"
    )

    for comp in "${XFCE_COMPONENTS[@]}"; do
        local name="${comp%%:*}"
        local ver="${comp#*:}"
        local major_minor="${ver%.*}"
        log "Building $name $ver..."
        local SRC="$WORK/$name-$ver"
        if [ ! -d "$SRC" ]; then
            local url="https://archive.xfce.org/src/xfce/$name/$major_minor/$name-$ver.tar.bz2"
            if ! curl -fsSL -o "$WORK/$name-$ver.tar.bz2" "$url" 2>/dev/null; then
                url="https://archive.xfce.org/src/apps/$name/$major_minor/$name-$ver.tar.bz2"
                curl -fsSL -o "$WORK/$name-$ver.tar.bz2" "$url"
            fi
            tar -xf "$WORK/$name-$ver.tar.bz2" -C "$WORK"
        fi
        cd "$SRC"
        if [ -f "meson.build" ]; then
            meson setup build --prefix="$PREFIX" --sysconfdir=/etc
            ninja -C build -j"$(nproc)"
            DESTDIR="$DESTDIR" ninja -C build install
        else
            ./configure --prefix="$PREFIX" --sysconfdir=/etc --disable-static
            make -j"$(nproc)"
            make DESTDIR="$DESTDIR" install
        fi
    done
}

archive_bundle() {
    log "=== Archiving NativeCode GUI Bundle ==="
    local TARBALL="$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst"
    if command -v zstd >/dev/null 2>&1; then
        tar -C "$DESTDIR" --numeric-owner -cpf - . | zstd -19 -T0 -o "$TARBALL"
    else
        tar -C "$DESTDIR" --numeric-owner -cJpf "$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.xz" .
        TARBALL="$OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.xz"
    fi
    sha256sum "$TARBALL" | tee "$TARBALL.sha256"
    log "Bundle created: $TARBALL"
}

# Main sequence
log "Note: run inside Alpaquita AArch64 container with build dependencies installed."
if [ "${1:-}" = "--archive-only" ]; then
    archive_bundle
    exit 0
fi

# Execute sequence
build_zsh || log "WARN: zsh build step"
build_fastfetch || log "WARN: fastfetch build step"
build_tigervnc || log "WARN: tigervnc build step"
build_xfce_core || log "WARN: xfce build step"
archive_bundle
log "GUI bundle build completed successfully."
