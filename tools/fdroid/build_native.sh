#!/usr/bin/env bash
# F-Droid flavor native build, step 1 ("fast"): everything that becomes
# app/src/fdroid/jniLibs/arm64-v8a/lib*.so, built from source:
#   libfast_pty_guard.so  tools/fdroid/native/fast_pty_guard (NDK r29 clang, API 26, 16 KB aligned)
#   libproot.so libloader.so libbash.so libtermux-exec.so
#                         termux-packages @ pin (bash, termux-exec, proot @ 7266fb3e + patches/proot)
# Output: tools/fdroid/out/jniLibs/arm64-v8a/*.so   (--install also copies to app/src/fdroid/jniLibs)
# Env: FDROID_WORK, FDROID_OUT, NDK, TP_REF_REPO (local termux-packages clone with the pin).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
INSTALL=0; [ "${1:-}" = "--install" ] && INSTALL=1
resolve_ndk
JNI="$OUT/jniLibs/arm64-v8a"; mkdir -p "$JNI"

echo "[*] fast_pty_guard"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
SRC="$FDROID_DIR/native/fast_pty_guard"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -fPIE -pie -nostdlib++ \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=none \
  -ffile-prefix-map="$REPO_ROOT"=. \
  "$SRC/fast_pty_guard.cpp" "$SRC/native_container_lock.cpp" -llog -o "$JNI/libfast_pty_guard.so"

echo "[*] termux-packages chain (bash termux-exec proot)"
checkout_termux_packages
apply_overlays
host_env
seed_mirrors

( in_sandbox bash -c ". '$FDROID_DIR/lib.sh'; host_env; resolve_ndk; build_list '$FDROID_DIR/package-lists/native.txt'" ) 2>&1 | tee "$OUT/build_native.log"
[ "${PIPESTATUS[0]}" = 0 ]

echo "[*] jniLibs from debs"
python3 -I "$FDROID_DIR/assemble_bootstrap.py" --debs "$TP/output" --app-id "$APP_ID" \
  --list "$FDROID_DIR/package-lists/native.txt" --stage "$WORK/stage-native" --jnilibs "$JNI"
python3 "$REPO_ROOT/scripts/check_elf_16k.py" "$OUT/jniLibs" || true
( cd "$OUT" && sha256sum jniLibs/arm64-v8a/*.so | tee jniLibs.sha256 )
if [ "$INSTALL" = 1 ]; then
  mkdir -p "$REPO_ROOT/app/src/fdroid/jniLibs/arm64-v8a"; cp -f "$JNI"/*.so "$REPO_ROOT/app/src/fdroid/jniLibs/arm64-v8a/"
fi
