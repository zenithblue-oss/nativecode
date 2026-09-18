#!/data/data/com.zenithblue.nativecode/files/usr/bin/bash
# stop_gui.sh - Stop XFCE4 Desktop Environment in PRoot Distro
# Paths: TermuxHostPaths via fluxlinux-host.env (SSOT)

unset LD_PRELOAD

DISTRO=${1:-alpaquita}
PKG="${TERMUX_APP__PACKAGE_NAME:-com.zenithblue.nativecode}"
_HOST_ENV="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}/etc/fluxlinux-host.env"
[ -r "$_HOST_ENV" ] && . "$_HOST_ENV"

export TERMUX_APP__PACKAGE_NAME="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
export TERMUX__PREFIX="${TERMUX__PREFIX:-/data/data/${TERMUX_APP__PACKAGE_NAME}/files/usr}"
export TERMUX__HOME="${TERMUX__HOME:-/data/data/${TERMUX_APP__PACKAGE_NAME}/files/home}"
export HOME="${HOME:-$TERMUX__HOME}"
export TMPDIR="${TMPDIR:-$TERMUX__PREFIX/tmp}"
export PROOT_TMP_DIR="${PROOT_TMP_DIR:-$TERMUX__PREFIX/tmp}"
PKG="$TERMUX_APP__PACKAGE_NAME"

TB=/system/bin/toybox

echo "========================================"
echo "FluxLinux: Stopping GUI for $DISTRO"
echo "========================================"

# Step 1: Kill Phosh, Phoc, XFCE & helper processes
echo "[1/4] Stopping Phosh, Phoc & XFCE4 processes..."
for proc in phosh phoc wvkbd wvkbd-mobintl glycin-svg glycin-image-rs glycin at-spi-bus-launcher at-spi2-registryd xfce4-terminal xfce4-session xfwm4 xfdesktop xfce4-panel dbus-launch dbus-daemon xfconfd; do
    "$TB" pkill -9 "$proc" 2>/dev/null || true
    "$TB" pkill -9 -f "$proc" 2>/dev/null || true
done

# Step 2: Stop proot-distro sessions
echo "[2/4] Killing proot sessions..."
"$TB" pkill -9 -f "proot-distro" 2>/dev/null || true
"$TB" pkill -9 -f "proot" 2>/dev/null || true

# Step 3: Stop Termux X11 server
echo "[3/4] Stopping Termux X11..."
# Restore write permissions to allow clean updates/deployments
"$TB" chmod 0700 "$TERMUX__PREFIX/libexec/termux-x11" 2>/dev/null || true
"$TB" chmod 0600 "$TERMUX__PREFIX/libexec/termux-x11/loader.apk" 2>/dev/null || true
# Send ACTION_STOP broadcast to close the X11 activity in our app
/system/bin/am broadcast -a com.termux.x11.ACTION_STOP -p "$PKG" >/dev/null 2>&1 || true
# Kill the app_process X server
"$TB" pkill -9 -f "termux-x11" 2>/dev/null || true
"$TB" pkill -9 -f "app_process.*termux-x11" 2>/dev/null || true
"$TB" pkill -9 -f "Xwayland" 2>/dev/null || true
for _p in $("$TB" pidof termux-x11 2>/dev/null); do
    "$TB" kill -9 "$_p" 2>/dev/null || true
done
"$TB" rm -f "$TMPDIR/.X0-lock" "$TMPDIR/.X11-unix/X0" "/tmp/.X0-lock" "/tmp/.X11-unix/X0" "$TMPDIR/.virgl_test" 2>/dev/null || true

# Step 4: Stop PulseAudio & VirGL
echo "[4/4] Stopping PulseAudio and VirGL..."
"$TB" pkill -9 -f "virgl_test_server_android" 2>/dev/null || true
"$TB" rm -f "$TMPDIR/.virgl_test" 2>/dev/null || true

LINKER=""
[ -x /system/bin/linker64 ] && LINKER="/system/bin/linker64"
if [ -n "$LINKER" ] && [ -f "$TERMUX__PREFIX/bin/pulseaudio" ]; then
  $LINKER "$TERMUX__PREFIX/bin/pulseaudio" --kill 2>/dev/null || true
fi
"$TB" pkill -9 -f "pulseaudio" 2>/dev/null || true

echo ""
echo "✅ GUI stopped successfully!"
echo "========================================"
exit 0
