#!/usr/bin/env bash
# F-Droid flavor native build, step 3: libXlorie.so (Xlorie X server + Android renderer used by the
# :termux-x11 module) built from termux/termux-x11 @ TERMUX_X11_COMMIT and its 16 pinned submodules,
# with CMake + the NDK toolchain (no Gradle). Needs: python3, bison, patch, cmake, ninja, git, network.
# Output: tools/fdroid/out/jniLibs/arm64-v8a/libXlorie.so  (--install also copies it to
#         app/src/fdroid/jniLibs/arm64-v8a/)
# Env: TX11_REF_DIR = optional local checkout of termux-x11 cpp submodules to fetch from first
#      (e.g. ~/repos/fluxlinux/termux-x11/src/main/cpp: same pins), JOBS (default 2).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
INSTALL=0; [ "${1:-}" = "--install" ] && INSTALL=1
resolve_ndk
SRC="$WORK/termux-x11-src"; BLD="$WORK/xlorie-build"; JOBS="${JOBS:-2}"

# --- sources at the pin (shallow, by sha) ---
[ -d "$SRC/.git" ] || git init -q "$SRC"
git -C "$SRC" cat-file -e "$TERMUX_X11_COMMIT^{commit}" 2>/dev/null || \
  git -C "$SRC" fetch -q --depth 1 "$TERMUX_X11_URL" "$TERMUX_X11_COMMIT"
git -C "$SRC" checkout -q -f "$TERMUX_X11_COMMIT"
git -C "$SRC" ls-tree -r "$TERMUX_X11_COMMIT" | awk '$1=="160000"{print $3, $4}' | while read -r sha path; do
  name="$(git -C "$SRC" config -f .gitmodules --get-regexp '^submodule\..*\.path$' | awk -v p="$path" '$2==p{print $1}' | sed 's/\.path$//')"
  url="$(git -C "$SRC" config -f .gitmodules --get "$name.url")"
  d="$SRC/$path"; [ -d "$d/.git" ] || { rm -rf "$d"; git init -q "$d"; }
  if ! git -C "$d" cat-file -e "$sha^{commit}" 2>/dev/null; then
    ref="${TX11_REF_DIR:-}/$(basename "$path")"
    { [ -n "${TX11_REF_DIR:-}" ] && [ -e "$ref/.git" ] && git -C "$d" fetch -q --depth 1 "$ref" "$sha"; } || \
      git -C "$d" fetch -q --depth 1 "$url" "$sha"
  fi
  git -C "$d" checkout -q -f "$sha"; git -C "$d" clean -qfdx   # patches are re-applied by CMake
done

# NativeCode's termux-x11 Java must call exactly the natives this source exports
python3 -I "$FDROID_DIR/check_jni.py" "$REPO_ROOT/termux-x11/src/main/java" "$SRC/app/src/main/java"
[ "${FETCH_ONLY:-0}" = 1 ] && exit 0
# --- build ---
CM="$SRC/app/src/main/cpp"
t0=$(date +%s)
cmake -S "$CM" -B "$BLD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_C_FLAGS="-ffile-prefix-map=$WORK=." \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none -Wl,-z,max-page-size=16384" 2>&1 | tee "$OUT/build_xlorie.log"
cmake --build "$BLD" -j "$JOBS" 2>&1 | tee -a "$OUT/build_xlorie.log"
echo "[*] build time $(( $(date +%s) - t0 )) s"
SO="$(find "$BLD" -name libXlorie.so | head -1)"; [ -f "$SO" ] || { echo "libXlorie.so not produced" >&2; exit 1; }
mkdir -p "$OUT/jniLibs/arm64-v8a"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$OUT/jniLibs/arm64-v8a/libXlorie.so" "$SO"
python3 "$REPO_ROOT/scripts/check_elf_16k.py" "$OUT/jniLibs/arm64-v8a/libXlorie.so"
( cd "$OUT" && sha256sum jniLibs/arm64-v8a/libXlorie.so | tee libXlorie.sha256 )
if [ "$INSTALL" = 1 ]; then
  mkdir -p "$REPO_ROOT/app/src/fdroid/jniLibs/arm64-v8a"; cp -f "$OUT/jniLibs/arm64-v8a/libXlorie.so" "$REPO_ROOT/app/src/fdroid/jniLibs/arm64-v8a/"
fi
