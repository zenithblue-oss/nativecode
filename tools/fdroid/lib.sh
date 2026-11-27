#!/usr/bin/env bash
# Shared helpers for tools/fdroid/build_*.sh. Source, do not run.
set -euo pipefail

FDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$FDROID_DIR/../.." && pwd)"
# shellcheck source=pins.env
. "$FDROID_DIR/pins.env"

WORK="${FDROID_WORK:-$FDROID_DIR/work}"      # gitignored: termux-packages clone, HOME, fake /data
OUT="${FDROID_OUT:-$FDROID_DIR/out}"         # gitignored: artifacts
TP="$WORK/termux-packages"
umask 022
mkdir -p "$WORK/home" "$OUT"

# --- NDK (single version) ---------------------------------------------------
resolve_ndk() {
  local c
  for c in "${NDK:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
           "${ANDROID_HOME:-}/ndk/$NDK_VERSION" "${ANDROID_SDK_ROOT:-}/ndk/$NDK_VERSION" \
           /opt/android-sdk/ndk/$NDK_VERSION "$HOME/Android/Sdk/ndk/$NDK_VERSION"; do
    [ -n "$c" ] && grep -q "Pkg.Revision *= *$NDK_VERSION" "$c/source.properties" 2>/dev/null && { NDK="$c"; export NDK; return; }
  done
  echo "NDK $NDK_VERSION not found (set NDK=)" >&2; exit 1
}

# --- sandbox: termux-packages hardcodes /data/data/<appid>/files/usr -----------
# On the F-Droid buildserver the recipe `sudo:` creates that dir; elsewhere
# (no root) run inside bubblewrap with $WORK/data bound to /data.
DATA_PREFIX="/data/data/$APP_ID/files"
in_sandbox() {
  # hermetic env: the caller's TERMINFO, JAVA_HOME, etc. must not leak into the build
  local -a E=(PATH=/usr/local/bin:/usr/bin:/usr/bin/core_perl:/usr/bin/vendor_perl TERM=dumb LANG=C.UTF-8 HOME="$WORK/home" NDK="$NDK"
              ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
              FDROID_WORK="$WORK" FDROID_OUT="$OUT" CONTINUE_ON_FAIL="${CONTINUE_ON_FAIL:-0}")
  # autoconf >= 2.73 (non-Debian hosts) picks -std=gnu23 and breaks K&R sources (dash);
  # Debian trixie's 2.72 does not. Pin the old behaviour off-Debian.
  [ -f /etc/debian_version ] || E+=(ac_cv_prog_cc_c23=no)
  if [ -w "$DATA_PREFIX" ] || [ -w "$DATA_PREFIX/usr" ]; then env -i "${E[@]}" "$@"; return; fi
  command -v bwrap >/dev/null || { echo "need /data/data/$APP_ID writable, or bubblewrap" >&2; exit 1; }
  mkdir -p "$WORK/data/data/$APP_ID/files/usr"
  local -a S=(); local kv
  for kv in "${E[@]}"; do S+=(--setenv "${kv%%=*}" "${kv#*=}"); done
  # termux-am hardcodes Debian's JDK 17 path in GRADLE_OPTS; alias it when the host names it differently
  local -a J=()
  if [ ! -e /usr/lib/jvm/java-1.17.0-openjdk-amd64 ] && [ -d /usr/lib/jvm/java-17-openjdk ]; then
    J=(--tmpfs /usr/lib/jvm --ro-bind /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk
       --symlink java-17-openjdk /usr/lib/jvm/java-1.17.0-openjdk-amd64)
  fi
  # sqlite's tcl extension sources the HOST /usr/lib/tclConfig.sh when the host has tcl dev files
  # (Arch does, the Debian buildserver does not): show it the target one instead (see build_list).
  [ -e /usr/lib/tclConfig.sh ] && { touch "$WORK/host-tclConfig.sh"; J+=(--ro-bind "$WORK/host-tclConfig.sh" /usr/lib/tclConfig.sh); }
  bwrap --die-with-parent --unshare-user --unshare-pid --share-net --clearenv "${S[@]}" \
    --ro-bind /usr /usr "${J[@]}" --ro-bind /etc /etc --ro-bind /opt /opt \
    --symlink usr/bin /bin --symlink usr/bin /sbin --symlink usr/lib /lib --symlink usr/lib /lib64 \
    --proc /proc --dev /dev --tmpfs /tmp --tmpfs /var \
    --ro-bind-try /run/systemd/resolve /run/systemd/resolve \
    --ro-bind "$REPO_ROOT" "$REPO_ROOT" --bind "$WORK" "$WORK" --bind "$WORK/data" /data \
    --chdir "$PWD" "$@"
}

host_env() {   # env for termux build-package.sh on a non-Ubuntu host
  export HOME="$WORK/home" TAR_OPTIONS="--no-same-owner" NDK
  SOURCE_DATE_EPOCH="$(git -C "$TP" log -1 --format=%ct)"; export SOURCE_DATE_EPOCH
  local j; for j in /usr/lib/jvm/java-1.17.0-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    [ -d "$j" ] && { export TERMUX_JAVA_HOME="${TERMUX_JAVA_HOME:-$j}"; break; }; done
  command -v "clang-${TERMUX_HOST_LLVM_MAJOR_VERSION:-21}" >/dev/null 2>&1 || \
    export TERMUX_HOST_LLVM_MAJOR_VERSION="$(clang -dumpversion | cut -d. -f1)"
  if [ -d /usr/include/openssl-3 ] && [ -d /usr/lib/openssl-3 ]; then
    mkdir -p "$WORK/openssl3"; ln -sfn /usr/include/openssl-3 "$WORK/openssl3/include"; ln -sfn /usr/lib/openssl-3 "$WORK/openssl3/lib"
    export FDROID_HOST_OPENSSL="$WORK/openssl3"
  fi
  if [ ! -e /usr/share/aclocal/ax_check_compile_flag.m4 ]; then
    local aa="$WORK/autoconf-archive"
    if [ ! -d "$aa/m4" ]; then
      mkdir -p "$aa"; curl -fsSL -o "$aa.tar.xz" "$AUTOCONF_ARCHIVE_URL"
      echo "$AUTOCONF_ARCHIVE_SHA256  $aa.tar.xz" | sha256sum -c - >/dev/null
      tar -xf "$aa.tar.xz" --strip-components=1 -C "$aa"
    fi
    export ACLOCAL_PATH="$aa/m4${ACLOCAL_PATH:+:$ACLOCAL_PATH}"
  fi
  # no real fuse-overlayfs (needs root/fuse): copy-based stand-in, see host-shims/
  command -v fuse-overlayfs >/dev/null 2>&1 && command -v pandoc >/dev/null 2>&1 || export PATH="$FDROID_DIR/host-shims:$PATH"
}

# --- termux-packages checkout + overlays -------------------------------------
checkout_termux_packages() {
  if [ ! -d "$TP/.git" ]; then git init -q "$TP"; fi
  if ! git -C "$TP" cat-file -e "$TERMUX_PACKAGES_REF^{commit}" 2>/dev/null; then
    # TP_REF_REPO: optional local clone holding the pinned commit (offline/fast)
    git -C "$TP" fetch -q --depth 1 "${TP_REF_REPO:-$TERMUX_PACKAGES_URL}" "$TERMUX_PACKAGES_REF"
  fi
  git -C "$TP" checkout -q -f "$TERMUX_PACKAGES_REF"
  [ "$(git -C "$TP" rev-parse HEAD)" = "$TERMUX_PACKAGES_REF" ] || { echo "termux-packages pin mismatch" >&2; exit 1; }
}

apply_overlays() {
  local props="$TP/scripts/properties.sh" p="$TP/packages/proot"
  [[ "$APP_ID" =~ ^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$ ]] || { echo "bad APP_ID" >&2; exit 1; }
  (( ${#DATA_PREFIX} < 86 )) || { echo "TERMUX__ROOTFS too long" >&2; exit 1; }
  sed -i -E "s|^TERMUX_APP__PACKAGE_NAME=.*|TERMUX_APP__PACKAGE_NAME=\"$APP_ID\"|" "$props"
  grep -q "^TERMUX_APP__PACKAGE_NAME=\"$APP_ID\"" "$props"
  # proot -> pinned commit + NativeCode patch series
  sed -i -E \
    -e "s|^TERMUX_PKG_SRCURL=.*|TERMUX_PKG_SRCURL=https://github.com/termux/proot/archive/$PROOT_COMMIT.zip|" \
    -e "s|^TERMUX_PKG_SHA256=.*|TERMUX_PKG_SHA256=$PROOT_ZIP_SHA256|" \
    -e "s|^TERMUX_PKG_AUTO_UPDATE=.*|TERMUX_PKG_AUTO_UPDATE=false|" "$p/build.sh"
  cp -f "$FDROID_DIR"/patches/proot/*.patch "$p/"
  # 0014-nativecode-pair-affinity needs this define (opt-out at run time: PROOT_NO_AFFINITY=1)
  sed -i 's|CPPFLAGS+=" -DARG_MAX=131072"|CPPFLAGS+=" -DARG_MAX=131072 -DNATIVECODE_PROOT_AFFINITY"|' "$p/build.sh"
  grep -q DNATIVECODE_PROOT_AFFINITY "$p/build.sh"
  # termux_setup_build_python cd's into its cache dir on a cold cache: always return
  # to the source dir so cold and warm builds are identical (from FluxLinux 7f6e61d).
  sed -i 's|^\([[:space:]]*termux_setup_build_python\)$|\1; cd "$TERMUX_PKG_SRCDIR"|' "$TP/packages/python/build.sh"
  # termux builds a host python (build tool only) against the host OpenSSL; OpenSSL 4 (Arch) breaks
  # its _ssl, so point it at the host's openssl-3 compat tree when there is one. Also cap its -j.
  sed -i -e 's|--enable-shared \\$|--enable-shared ${FDROID_HOST_OPENSSL:+--with-openssl=$FDROID_HOST_OPENSSL --with-openssl-rpath=auto} \\|' \
         -e 's|make -j "$(nproc)" install|make -j "${TERMUX_PKG_MAKE_PROCESSES:-${JOBS:-2}}" install|' \
         "$TP/scripts/build/setup/termux_setup_build_python.sh"
  # No tkinter: its tk build-dep drags in X11, fontconfig, freetype, cairo-class libs (and gperf) for a
  # module the app never imports. Skip tk and the _tkinter check; python-tkinter becomes an empty subpackage (deps: zlib).
  sed -i -e '/^TERMUX_PKG_BUILD_DEPENDS="tk"/d' -e 's| _tkinter zlib| zlib|' "$TP/packages/python/build.sh"
  # (other packages still name python-tkinter in Depends, so keep the subpackage but without tcl/tk deps)
  sed -i 's|^TERMUX_SUBPKG_DEPENDS="tcl, tk"|TERMUX_SUBPKG_DEPENDS="zlib"|' "$TP/packages/python/python-tkinter.subpackage.sh"
  ! grep -q '_tkinter\|="tk"' "$TP/packages/python/build.sh"
  # PulseAudio as a headless TCP/OpenSL server: no D-Bus (its libx11/xorg chain), no GLib mainloop (glib pulls the
  # gobject-introspection cross toolchain) no `check` (-> pkg-config -> glib) for unit tests the build never runs, and no man pages (xmltoman).
  git -C "$TP" checkout -- packages/pulseaudio   # re-runs start from the pristine recipe
  sed -i -e 's|^TERMUX_PKG_DEPENDS="dbus, |TERMUX_PKG_DEPENDS="|' \
         -e 's|^TERMUX_PKG_BUILD_DEPENDS="libtool, glib, check"|TERMUX_PKG_BUILD_DEPENDS="libtool"|' \
         -e 's|^-D gtk=disabled$|-D gtk=disabled\n-D glib=disabled\n-D dbus=disabled\n-D tests=false\n-D man=false|' "$TP/packages/pulseaudio/build.sh"
  # pulseaudio-glib stays (other packages name it in Depends) but is empty and depends on libc++ instead of glib.
  sed -i 's|^TERMUX_SUBPKG_DEPENDS="glib"|TERMUX_SUBPKG_DEPENDS="libc++"|' "$TP/packages/pulseaudio/pulseaudio-glib.subpackage.sh"
  grep -q '^-D dbus=disabled' "$TP/packages/pulseaudio/build.sh" && ! grep -q 'dbus, ' "$TP/packages/pulseaudio/build.sh"
}

# --- source mirrors (some upstreams answer curl with HTTP 403/418) -----------
# mirrors.txt: <termux-pkg> <filename> <sha256> <url>...   (tried in order)
seed_mirrors() {
  local f="$FDROID_DIR/mirrors.txt" p fn sha d u ok
  [ -f "$f" ] || return 0
  while read -r p fn sha urls; do
    case "$p" in ''|'#'*) continue;; esac
    d="$WORK/home/.termux-build/$p/cache"; mkdir -p "$d"
    if [ -f "$d/$fn" ] && echo "$sha  $d/$fn" | sha256sum -c - >/dev/null 2>&1; then continue; fi
    ok=0
    for u in $urls; do
      if curl -fL --retry 3 -o "$d/$fn.tmp" "$u" && echo "$sha  $d/$fn.tmp" | sha256sum -c - >/dev/null 2>&1; then
        mv "$d/$fn.tmp" "$d/$fn"; ok=1; break
      fi
    done
    rm -f "$d/$fn.tmp"
    [ "$ok" = 1 ] || { echo "seed failed: $p $fn" >&2; exit 1; }
  done < "$f"
}

# --- build the packages in a list (dependencies are built automatically) -------
build_list() {  # $1 = package list file
  local list="$1" pkg src sub failed=()
  local -a pkgs=()
  while IFS= read -r pkg; do pkg="${pkg%%#*}"; pkg="$(echo $pkg)"; [ -n "$pkg" ] && pkgs+=("$pkg"); done < "$list"
  cd "$TP"
  for pkg in "${pkgs[@]}"; do
    src="$pkg"
    if ! ls -d packages/"$pkg" >/dev/null 2>&1; then   # subpackage -> build its parent
      sub="$(ls packages/*/"$pkg".subpackage.sh 2>/dev/null | head -1)"
      [ -n "$sub" ] && src="$(basename "$(dirname "$sub")")"
    fi
    echo "=== $(date +%T) building $pkg (from $src)"
    [ -f "$DATA_PREFIX/usr/lib/tclConfig.sh" ] && [ -w "$WORK/host-tclConfig.sh" ] && cp -f "$DATA_PREFIX/usr/lib/tclConfig.sh" "$WORK/host-tclConfig.sh" || true
    ./build-package.sh -j "${JOBS:-2}" -a "$ARCH" "$src" || { echo "FAIL $pkg"; failed+=("$pkg"); [ "${CONTINUE_ON_FAIL:-0}" = 1 ] || break; }
  done
  [ ${#failed[@]} -eq 0 ] || { echo "failed: ${failed[*]}" >&2; return 1; }
}
