#!/usr/bin/env bash
# termux-x11 shell loader (usr/libexec/termux-x11/loader.apk): javac + d8 -> zip with classes.dex.
# d8 is pinned to build-tools $D8_BUILD_TOOLS (30.0.3, installed by termux-am's gradle build) so
# classes.dex does not depend on whichever build-tools the host has. Output: out/loader.apk
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
D8="${D8:-$SDK/build-tools/${D8_BUILD_TOOLS:-30.0.3}/d8}"
[ -x "$D8" ] || { echo "d8 not found at $D8" >&2; exit 1; }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
javac --release 8 -nowarn -d "$T/classes" "$FDROID_DIR/native/loader/Loader.java"
"$D8" --release --min-api 26 --no-desugaring --output "$T/loader.zip" "$T/classes/com/termux/x11/Loader.class"
cp -f "$T/loader.zip" "$OUT/loader.apk"; sha256sum "$OUT/loader.apk"
