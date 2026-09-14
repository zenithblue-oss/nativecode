#!/usr/bin/env bash
# Build pulseaudio + virglrenderer-android (Venus) for com.zenithblue.nativecode.
# Uses a dedicated docker container so the shared termux-packages clone is restored.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TP="${TERMUX_PACKAGES_DIR:-$HOME/repos/termux-packages}"
OVERLAY="$ROOT/tools/host-packages/virglrenderer-android"
OUT="$ROOT/termux-packages/output"
PKG_NAME="com.zenithblue.nativecode"
ARCH="${ARCH:-aarch64}"
CONTAINER_NAME="${CONTAINER_NAME:-termux-package-builder-nativecode}"

if [[ ! -d "$TP/packages/pulseaudio" ]]; then
  echo "termux-packages not found at $TP" >&2
  exit 1
fi
if [[ ! -f "$OVERLAY/build.sh" ]]; then
  echo "missing overlay $OVERLAY/build.sh" >&2
  exit 1
fi

PROPS="$TP/scripts/properties.sh"
FOOT="$TP/x11-packages/foot/build.sh"
PROPS_BAK="$(mktemp)"
VIRGL_BAK="$(mktemp -d)"
FOOT_BAK=""
cp "$PROPS" "$PROPS_BAK"
cp -a "$TP/packages/virglrenderer-android/." "$VIRGL_BAK/"
if [[ -f "$FOOT" ]]; then
  FOOT_BAK="$(mktemp)"
  cp "$FOOT" "$FOOT_BAK"
fi

restore() {
  cp "$PROPS_BAK" "$PROPS"
  rm -rf "$TP/packages/virglrenderer-android"
  mkdir -p "$TP/packages/virglrenderer-android"
  cp -a "$VIRGL_BAK/." "$TP/packages/virglrenderer-android/"
  if [[ -n "$FOOT_BAK" && -f "$FOOT_BAK" ]]; then
    cp "$FOOT_BAK" "$FOOT"
    rm -f "$FOOT_BAK"
  fi
  rm -f "$PROPS_BAK"
  rm -rf "$VIRGL_BAK"
}
trap restore EXIT

sed -i "s/^TERMUX_APP__PACKAGE_NAME=.*/TERMUX_APP__PACKAGE_NAME=\"${PKG_NAME}\"/" "$PROPS"
# This clone used /tmp/nc-data/data which the current rootfs/prefix validator rejects.
sed -i 's|/tmp/nc-data/data|/data/data|g' "$PROPS"
# ncurses pulls foot terminfo; upstream tarball hash drifts.
if [[ -f "$TP/x11-packages/foot/build.sh" ]]; then
  sed -i 's/^TERMUX_PKG_SHA256=.*/TERMUX_PKG_SHA256=f5917cad2d7b723b99873e53d78fd10ea202923d189aed5086591fc53b70b7e3/' \
    "$TP/x11-packages/foot/build.sh"
fi
grep -q "TERMUX_APP__PACKAGE_NAME=\"${PKG_NAME}\"" "$PROPS"
grep -q 'TERMUX_APP__DATA_DIR="/data/data/$TERMUX_APP__PACKAGE_NAME"' "$PROPS"
echo "[*] properties: $(grep '^TERMUX_APP__PACKAGE_NAME=' "$PROPS")"
echo "[*] data dir:   $(grep '^TERMUX_APP__DATA_DIR=' "$PROPS")"
echo "[*] cgct:       $(grep '^CGCT_DEFAULT_PREFIX=' "$PROPS")"

rm -rf "$TP/packages/virglrenderer-android"
mkdir -p "$TP/packages/virglrenderer-android"
cp -a "$OVERLAY/." "$TP/packages/virglrenderer-android/"

mkdir -p "$OUT"
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

export TERMUX_DOCKER_RUN_EXTRA_ARGS="--network host"
export CONTAINER_NAME

cd "$TP"
if [[ "${FORCE_VIRGL:-0}" != 1 ]] && compgen -G "$TP/output/virglrenderer-android_*_aarch64.deb" > /dev/null; then
  echo "[*] virglrenderer-android deb already present, skip"
else
  echo "[*] building virglrenderer-android (Venus, prefix=/data/data/${PKG_NAME}/files/usr)"
  ./scripts/run-docker.sh ./build-package.sh -a "$ARCH" virglrenderer-android
fi
if [[ "${SKIP_PULSE:-0}" == 1 ]]; then
  echo "[*] SKIP_PULSE=1"
else
  echo "[*] building pulseaudio"
  ./scripts/run-docker.sh ./build-package.sh -a "$ARCH" pulseaudio
fi

shopt -s nullglob
copied=0
for deb in "$TP/output"/virglrenderer-android_*_aarch64.deb "$TP/output"/pulseaudio_*_aarch64.deb; do
  cp -a "$deb" "$OUT/"
  echo "  copied $(basename "$deb")"
  copied=$((copied + 1))
done
if [[ "$copied" -lt 2 ]]; then
  echo "expected both virglrenderer-android and pulseaudio debs in $TP/output" >&2
  ls "$TP/output" | grep -E 'virglrenderer-android|pulseaudio' || true
  exit 1
fi

echo "[*] done. debs in $OUT"
