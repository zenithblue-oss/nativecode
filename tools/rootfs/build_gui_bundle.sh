#!/usr/bin/env bash
# build_gui_bundle.sh — compile and package XFCE 4.20 core + TigerVNC (with Xvnc) + zsh + fastfetch
# for Alpaquita glibc/AArch64 inside a container or native builder.
# Output: $OUT/nativecode-gui-alpaquita-glibc-aarch64.tar.zst
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/versions.env"

WORK="${WORK:-/tmp/nc-gui-build}"
OUT="${OUT:-$SCRIPT_DIR/out}"
PREFIX="/usr"
DESTDIR="$WORK/destdir"
JOBS="${JOBS:-$(nproc)}"
GUI_ZSTD_LEVEL="${GUI_ZSTD_LEVEL:-19}"
GUI_CACHE_REV="${GUI_CACHE_REV:-2}"
BUNDLE_NAME="nativecode-gui-alpaquita-glibc-aarch64.tar.zst"
BUNDLE="$OUT/$BUNDLE_NAME"
BUNDLE_SHA="$BUNDLE.sha256"

mkdir -p "$WORK" "$OUT" "$DESTDIR" "$WORK/.stamps"

log() { echo "build-gui-bundle: $*"; }

verify_sha256() {
    local file="$1"
    local expected="$2"
    local actual
    actual=$(sha256sum "$file" | awk '{print $1}')
    if [ "$actual" != "$expected" ]; then
        echo "build-gui-bundle: ERROR: Checksum mismatch for $file" >&2
        echo "  Expected: $expected" >&2
        echo "  Actual:   $actual" >&2
        exit 1
    fi
}

download_and_verify() {
    local url="$1"
    local dest="$2"
    local expected_sha="$3"

    if [ ! -f "$dest" ]; then
        log "Downloading $url -> $dest"
        curl -fsSL -o "$dest" "$url"
    fi
    verify_sha256 "$dest" "$expected_sha"
}

stamp_path() {
    printf '%s/.stamps/%s-%s.done\n' "$WORK" "$GUI_CACHE_REV" "$1"
}

stamp_hit() {
    [ "${GUI_DISABLE_CACHE:-0}" != "1" ] && [ -f "$(stamp_path "$1")" ]
}

stamp_done() {
    touch "$(stamp_path "$1")"
}

reuse_finished_bundle() {
    [ "${REUSE_GUI_BUNDLE:-1}" = "1" ] || return 1
    [ -s "$BUNDLE" ] && [ -s "$BUNDLE_SHA" ] || return 1
    if (cd "$OUT" && sha256sum -c "$(basename "$BUNDLE_SHA")" >/dev/null 2>&1); then
        log "CACHE HIT: verified GUI bundle already exists: $BUNDLE"
        return 0
    fi
    log "cached GUI bundle checksum failed; rebuilding"
    rm -f "$BUNDLE" "$BUNDLE_SHA"
    return 1
}

# 1. Zsh
build_zsh() {
    local patch_sha
    patch_sha="$(sha256sum "$SCRIPT_DIR/patches/zsh-5.9-modern-ncurses.patch" | awk '{print substr($1,1,16)}')"
    local key="zsh-$ZSH_VERSION-$ZSH_SHA256-$patch_sha"
    if stamp_hit "$key" && [ -x "$DESTDIR/usr/bin/zsh" ]; then
        log "CACHE HIT: zsh $ZSH_VERSION"
        return 0
    fi

    log "=== Building Zsh ($ZSH_VERSION) ==="
    local TARBALL="$WORK/zsh-$ZSH_VERSION.tar.xz"
    local SRC="$WORK/zsh-$ZSH_VERSION"
    download_and_verify \
        "https://sourceforge.net/projects/zsh/files/zsh/$ZSH_VERSION/zsh-$ZSH_VERSION.tar.xz/download" \
        "$TARBALL" \
        "$ZSH_SHA256"

    rm -rf "$SRC"
    tar -xf "$TARBALL" -C "$WORK"
    
    log "Applying Zsh modern ncurses compatibility patch..."
    patch -p1 --batch -d "$SRC" < "$SCRIPT_DIR/patches/zsh-5.9-modern-ncurses.patch"

    cd "$SRC"
    ./configure --prefix="$PREFIX" \
                --bindir="$PREFIX/bin" \
                --sysconfdir=/etc \
                --enable-etcdir=/etc \
                --enable-multibyte \
                --enable-pcre \
                --enable-cap \
                --with-tcsetpgrp
    make -j"$JOBS"
    make DESTDIR="$DESTDIR" install
    stamp_done "$key"
    log "Zsh installed to $DESTDIR"
}

# 2. Fastfetch
build_fastfetch() {
    local key="fastfetch-$FASTFETCH_VERSION-$FASTFETCH_SHA256"
    if stamp_hit "$key" && [ -x "$DESTDIR/usr/bin/fastfetch" ]; then
        log "CACHE HIT: fastfetch $FASTFETCH_VERSION"
        return 0
    fi

    log "=== Building Fastfetch ($FASTFETCH_VERSION) ==="
    local TARBALL="$WORK/fastfetch-$FASTFETCH_VERSION.tar.gz"
    local SRC="$WORK/fastfetch-$FASTFETCH_VERSION"
    download_and_verify \
        "https://github.com/fastfetch-cli/fastfetch/archive/refs/tags/$FASTFETCH_VERSION.tar.gz" \
        "$TARBALL" \
        "$FASTFETCH_SHA256"

    if [ ! -d "$SRC" ]; then
        tar -xf "$TARBALL" -C "$WORK"
    fi
    mkdir -p "$SRC/build"
    cd "$SRC/build"
    cmake .. -DCMAKE_INSTALL_PREFIX="$PREFIX" -DBUILD_TESTS=OFF
    cmake --build . -j"$JOBS"
    DESTDIR="$DESTDIR" cmake --install .
    stamp_done "$key"
    log "Fastfetch installed to $DESTDIR"
}

# 3. TigerVNC + Xvnc Server
build_tigervnc() {
    local key="tigervnc-$TIGERVNC_VERSION-$TIGERVNC_SHA256-xorg-$XORG_SERVER_VERSION-$XORG_SERVER_SHA256"
    if stamp_hit "$key" && [ -x "$DESTDIR/usr/bin/Xvnc" ]; then
        log "CACHE HIT: TigerVNC/Xvnc $TIGERVNC_VERSION"
        return 0
    fi

    log "=== Building TigerVNC ($TIGERVNC_VERSION) with Xvnc ==="
    local TV_TARBALL="$WORK/tigervnc-$TIGERVNC_VERSION.tar.gz"
    local TV_SRC="$WORK/tigervnc-$TIGERVNC_VERSION"
    download_and_verify \
        "https://github.com/TigerVNC/tigervnc/archive/refs/tags/v$TIGERVNC_VERSION.tar.gz" \
        "$TV_TARBALL" \
        "$TIGERVNC_SHA256"

    if [ ! -d "$TV_SRC" ]; then
        tar -xf "$TV_TARBALL" -C "$WORK"
    fi

    # Clean existing build directory to ensure CMake doesn't reuse failed/stale cache
    rm -rf "$TV_SRC/build"
    mkdir -p "$TV_SRC/build"
    cd "$TV_SRC/build"
    cmake .. -DCMAKE_INSTALL_PREFIX="$PREFIX" -DENABLE_NLS=OFF -DBUILD_VIEWER=OFF
    cmake --build . -j"$JOBS"
    DESTDIR="$DESTDIR" cmake --install .

    local XORG_TARBALL="$WORK/xorg-server-$XORG_SERVER_VERSION.tar.xz"
    local XORG_SRC="$WORK/xorg-server-$XORG_SERVER_VERSION"
    download_and_verify \
        "https://www.x.org/releases/individual/xserver/xorg-server-$XORG_SERVER_VERSION.tar.xz" \
        "$XORG_TARBALL" \
        "$XORG_SERVER_SHA256"

    if [ ! -d "$XORG_SRC" ]; then
        tar -xf "$XORG_TARBALL" -C "$WORK"
    fi

    log "Preparing Xorg server tree for Xvnc..."
    # Always re-extract pristine TigerVNC unix/xserver and Xorg source to avoid corrupted tree across cached runs
    tar -xf "$TV_TARBALL" -C "$WORK" "tigervnc-$TIGERVNC_VERSION/unix/xserver"
    rm -rf "$WORK/xorg-server-$XORG_SERVER_VERSION"
    tar -xf "$XORG_TARBALL" -C "$WORK"

    # Per TigerVNC BUILDING.txt: copy Xorg sources into unix/xserver/ overlaying without removing hw/vnc
    cp -R "$XORG_SRC"/* "$TV_SRC/unix/xserver/"

    cd "$TV_SRC/unix/xserver"
    if [ -f "$TV_SRC/unix/xserver21.patch" ]; then
        # Modern binutils with --no-copy-dt-needed-entries requires explicit direct dependency DSO linking for TigerVNC crypto/pam/jpeg/zlib
        sed -i 's/AC_SUBST(\[XVNC_SYS_LIBS\], \["\$GLX_SYS_LIBS"\])/AC_SUBST([XVNC_SYS_LIBS], ["$GLX_SYS_LIBS -lhogweed -lnettle -lgmp -lgnutls -lpam -ljpeg -lz"])/' "$TV_SRC/unix/xserver21.patch"
        patch -p1 -N < "$TV_SRC/unix/xserver21.patch" || true
    fi

    autoreconf -fiv
    ./configure --prefix="$PREFIX" \
        --with-pic --without-dtrace --disable-static --disable-dri \
        --disable-xinerama --disable-xvfb --disable-xnest --disable-xorg \
        --disable-dmx --disable-xwin --disable-xephyr --disable-kdrive \
        --disable-config-hal --disable-config-udev --disable-dri2 --enable-glx \
        --with-default-font-path="catalogue:/etc/X11/fontpath.d,built-ins" \
        --with-xkb-path=/usr/share/X11/xkb \
        --with-xkb-output=/var/lib/xkb \
        --with-xkb-bin-directory=/usr/bin

    make -j"$JOBS" TIGERVNC_SRCDIR="$TV_SRC" TIGERVNC_BUILDDIR="$TV_SRC/build"
    cd hw/vnc
    make DESTDIR="$DESTDIR" TIGERVNC_SRCDIR="$TV_SRC" TIGERVNC_BUILDDIR="$TV_SRC/build" install
    [ -x "$DESTDIR/usr/bin/Xvnc" ] || {
        echo "build-gui-bundle: ERROR: Xvnc missing after TigerVNC build" >&2
        exit 1
    }
    stamp_done "$key"
    log "TigerVNC & Xvnc installed to $DESTDIR"
}

# 4. XFCE 4.20 core components
build_xfce_core() {
    log "=== Building XFCE Core ==="
    local XFCE_COMPONENTS=(
        "xfce4-dev-tools:4.20.0:$XFCE_XFCE4_DEV_TOOLS_SHA256:xfce"
        "libxfce4windowing:4.20.0:$XFCE_LIBXFCE4WINDOWING_SHA256:xfce"
        "libxfce4util:4.20.0:$XFCE_LIBXFCE4UTIL_SHA256:xfce"
        "xfconf:4.20.0:$XFCE_XFCONF_SHA256:xfce"
        "libxfce4ui:4.20.0:$XFCE_LIBXFCE4UI_SHA256:xfce"
        "garcon:4.20.0:$XFCE_GARCON_SHA256:xfce"
        "exo:4.20.0:$XFCE_EXO_SHA256:xfce"
        "xfce4-panel:4.20.0:$XFCE_XFCE4_PANEL_SHA256:xfce"
        "xfce4-session:4.20.0:$XFCE_XFCE4_SESSION_SHA256:xfce"
        "xfwm4:4.20.0:$XFCE_XFWM4_SHA256:xfce"
        "xfdesktop:4.20.0:$XFCE_XFDESKTOP_SHA256:xfce"
        "thunar:4.20.0:$XFCE_THUNAR_SHA256:xfce"
        "xfce4-terminal:1.1.4:$XFCE_XFCE4_TERMINAL_SHA256:apps"
        "xfce4-settings:4.20.0:$XFCE_XFCE4_SETTINGS_SHA256:xfce"
    )

    for comp in "${XFCE_COMPONENTS[@]}"; do
        local name="${comp%%:*}"
        local rest="${comp#*:}"
        local ver="${rest%%:*}"
        local rest2="${rest#*:}"
        local sha="${rest2%%:*}"
        local cat="${rest2#*:}"
        local major_minor="${ver%.*}"
        local key="xfce-$name-$ver-$sha"

        if stamp_hit "$key"; then
            log "CACHE HIT: $name $ver"
            continue
        fi

        log "Building $name $ver..."
        local TARBALL="$WORK/$name-$ver.tar.bz2"
        local SRC="$WORK/$name-$ver"
        local url="https://archive.xfce.org/src/$cat/$name/$major_minor/$name-$ver.tar.bz2"

        download_and_verify "$url" "$TARBALL" "$sha"
        if [ ! -d "$SRC" ]; then
            tar -xf "$TARBALL" -C "$WORK"
        fi

        cd "$SRC"
        if [ -x "configure" ]; then
            ./configure --prefix="$PREFIX" --sysconfdir=/etc --disable-static
            make -j"$JOBS"
            DESTDIR="$DESTDIR" make install
            make install 2>/dev/null || true
        elif [ -f "meson.build" ]; then
            if [ ! -d build ]; then
                meson setup build --prefix="$PREFIX" --sysconfdir=/etc
            else
                meson setup build --reconfigure --prefix="$PREFIX" --sysconfdir=/etc
            fi
            ninja -C build -j"$JOBS"
            DESTDIR="$DESTDIR" ninja -C build install
            ninja -C build install 2>/dev/null || true
        else
            echo "build-gui-bundle: ERROR: No recognized build system in $SRC" >&2
            exit 1
        fi
        stamp_done "$key"
    done
}

verify_contract() {
    log "=== Verifying Built Artifact Contract ==="
    local missing=0
    for bin in \
        "$DESTDIR/usr/bin/zsh" \
        "$DESTDIR/usr/bin/fastfetch" \
        "$DESTDIR/usr/bin/startxfce4" \
        "$DESTDIR/usr/bin/xfwm4" \
        "$DESTDIR/usr/bin/thunar" \
        "$DESTDIR/usr/bin/Xvnc"
    do
        if [ ! -x "$bin" ]; then
            echo "build-gui-bundle: CONTRACT FAILURE: Missing executable binary: $bin" >&2
            missing=1
        else
            log "OK: $bin exists and is executable"
        fi
    done

    if [ "$missing" != "0" ]; then
        echo "build-gui-bundle: FATAL: One or more required GUI bundle binaries failed to build!" >&2
        exit 1
    fi
}

archive_bundle() {
    verify_contract
    log "=== Archiving NativeCode GUI Bundle (zstd level $GUI_ZSTD_LEVEL) ==="
    command -v zstd >/dev/null 2>&1 || {
        echo "build-gui-bundle: ERROR: 'zstd' command is required" >&2
        exit 1
    }
    rm -f "$BUNDLE" "$BUNDLE_SHA"
    tar -C "$DESTDIR" --numeric-owner -cpf - . | zstd -"$GUI_ZSTD_LEVEL" -T0 -o "$BUNDLE"
    (
        cd "$OUT"
        sha256sum "$BUNDLE_NAME" > "$BUNDLE_NAME.sha256"
    )
    log "Bundle created: $BUNDLE"
    cat "$BUNDLE_SHA"
}

# Main sequence
log "Note: run inside Alpaquita AArch64 container with build dependencies installed."
if [ "${1:-}" = "--archive-only" ]; then
    archive_bundle
    exit 0
fi

if reuse_finished_bundle; then
    exit 0
fi

# Execute sequence — completed components are cached under $WORK/.stamps.
build_zsh
build_fastfetch
build_tigervnc
build_xfce_core
archive_bundle
log "GUI bundle build completed successfully."
