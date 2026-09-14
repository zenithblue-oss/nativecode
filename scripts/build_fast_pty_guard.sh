#!/usr/bin/env bash
# Build libfast_pty_guard.so for arm64-v8a with 16 KB ELF LOAD alignment.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-/opt/android-sdk/ndk/27.2.12479018}}"
[ -d "$NDK" ] || { echo "NDK not found at $NDK" >&2; exit 1; }
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++"
[ -x "$CXX" ] || { echo "missing $CXX" >&2; exit 1; }

SRC_ROOT="${PTY_GUARD_SRC:-$ROOT/../termux-lib/app/src/main/cpp}"
[ -f "$SRC_ROOT/fast_pty_guard.cpp" ] || {
    echo "fast_pty_guard.cpp not found in $SRC_ROOT (set PTY_GUARD_SRC)" >&2
    exit 1
}

OUT="$ROOT/app/src/full/jniLibs/arm64-v8a/libfast_pty_guard.so"
mkdir -p "$(dirname "$OUT")"

"$CXX" -target aarch64-linux-android26 \
    -std=c++17 -O2 -Wall -Wextra -Werror \
    -fPIE -pie -nostdlib++ \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384 \
    "$SRC_ROOT/fast_pty_guard.cpp" \
    "$SRC_ROOT/native_container_lock.cpp" \
    -llog \
    -o "$OUT"

python3 "$ROOT/scripts/check_elf_16k.py" "$OUT"
sha256sum "$OUT"
echo "Built $OUT"
