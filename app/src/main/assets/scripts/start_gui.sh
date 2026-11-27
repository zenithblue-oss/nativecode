#!/data/data/com.zenithblue.nativecode/files/usr/bin/bash
# start_gui.sh - Launch XFCE4 Desktop Environment in PRoot Distro
# Paths: TermuxHostPaths via fluxlinux-host.env (SSOT)

DISTRO=${1:-alpaquita}
PKG="${TERMUX_APP__PACKAGE_NAME:-com.zenithblue.nativecode}"
_HOST_ENV="${TERMUX__PREFIX:-/data/data/${PKG}/files/usr}/etc/fluxlinux-host.env"
[ -r "$_HOST_ENV" ] && . "$_HOST_ENV"

unset LD_PRELOAD
TB=/system/bin/toybox

# Detect how we're running
IS_ROOT=false
if [ "$("$TB" id -u 2>/dev/null)" = "0" ]; then IS_ROOT=true; fi

# Termux paths (from SSOT env, with safe fallbacks)
PKG="${TERMUX_APP__PACKAGE_NAME:-$PKG}"
TERMUX_PREFIX="${TERMUX__PREFIX:-/data/data/$PKG/files/usr}"
TERMUX_HOME="${TERMUX__HOME:-/data/data/$PKG/files/home}"
export HOME="$TERMUX_HOME"
export TMPDIR="${TMPDIR:-$TERMUX_PREFIX/tmp}"
export PROOT_TMP_DIR="${PROOT_TMP_DIR:-$TMPDIR}"
export PATH="/system/bin:/system/xbin:$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/opt/virglrenderer-android/lib:$TERMUX_PREFIX/lib"
export TERMUX_APP__PACKAGE_NAME="$PKG"
export TERMUX_X11_OVERRIDE_PACKAGE="$PKG"
export TERMUX__PREFIX="$TERMUX_PREFIX"
export TERMUX__HOME="$TERMUX_HOME"
if [ -d "$TERMUX_PREFIX/share/xkeyboard-config-2" ]; then
  export XKB_CONFIG_ROOT="$TERMUX_PREFIX/share/xkeyboard-config-2"
else
  export XKB_CONFIG_ROOT="$TERMUX_PREFIX/share/X11/xkb"
fi
# The F-Droid bootstrap ships no xkeyboard-config: use the guest's (the Phosh desktop needs it anyway).
[ -d "$XKB_CONFIG_ROOT" ] || XKB_CONFIG_ROOT="$TERMUX_PREFIX/var/lib/proot-distro/containers/${DISTRO}/rootfs/usr/share/X11/xkb"
export XKB_CONFIG_ROOT

# Configure PulseAudio (use home to avoid root-owned stale tmp dirs)
export PULSE_RUNTIME_PATH="${HOME}/.pulse"
"$TB" mkdir -p "$PULSE_RUNTIME_PATH" 2>/dev/null || true

LINKER=""
[ -x /system/bin/linker64 ] && LINKER="/system/bin/linker64"

# Start PulseAudio over TCP via linker64 on targetSdk 37
PA_BIN="$TERMUX_PREFIX/bin/pulseaudio"
PA_CMD="$PA_BIN"
[ -n "$LINKER" ] && [ -f "$PA_BIN" ] && PA_CMD="$LINKER $PA_BIN"

if ! "$TB" pidof pulseaudio >/dev/null 2>&1; then
  echo "FluxLinux: Starting PulseAudio..."
  if $IS_ROOT; then
    $PA_CMD --system --start --dl-search-path="$TERMUX_PREFIX/lib/pulseaudio/modules" \
      --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
      --exit-idle-time=-1 2>/dev/null || \
    $PA_CMD --start --dl-search-path="$TERMUX_PREFIX/lib/pulseaudio/modules" \
      --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
      --exit-idle-time=-1 2>/dev/null || true
  else
    $PA_CMD --start --dl-search-path="$TERMUX_PREFIX/lib/pulseaudio/modules" \
      --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1" \
      --exit-idle-time=-1 2>/dev/null || true
  fi
else
  echo "FluxLinux: PulseAudio already running"
fi

# VirGL/Venus is optional. Turnip (Snapdragon) does not need the host vtest server.
ROOTFS="$TERMUX_PREFIX/var/lib/proot-distro/containers/${DISTRO}/rootfs"
GUEST_GPU_MODE=venus
GUEST_GPU_BACKEND=""
if [ -r "$ROOTFS/etc/fluxlinux/gpu_backend" ]; then
  GUEST_GPU_BACKEND=$("$TB" tr -d '[:space:]' <"$ROOTFS/etc/fluxlinux/gpu_backend")
fi
if [ -r "$ROOTFS/etc/fluxlinux/gpu_mode" ]; then
  GUEST_GPU_MODE=$("$TB" tr -d '[:space:]' <"$ROOTFS/etc/fluxlinux/gpu_mode")
fi
NEED_VTEST=1
case "${GUEST_GPU_BACKEND:-$GUEST_GPU_MODE}" in
  turnip|panvk|panvk-zink) NEED_VTEST=0 ;;
  software|llvmpipe) NEED_VTEST=0 ;;
esac

VIRGL_BIN="$TERMUX_PREFIX/bin/virgl_test_server_android"
VIRGL_CMD="$VIRGL_BIN"
[ -n "$LINKER" ] && [ -f "$VIRGL_BIN" ] && VIRGL_CMD="$LINKER $VIRGL_BIN"

if [ "$NEED_VTEST" = "1" ] && [ -f "$VIRGL_BIN" ]; then
  if ! "$TB" pidof virgl_test_server_android >/dev/null 2>&1; then
    VTEST_ARGS=(--socket-path "$TERMUX_PREFIX/tmp/.virgl_test")
    HELP=$($VIRGL_CMD --help 2>&1 || true)
    case "${GUEST_GPU_BACKEND:-$GUEST_GPU_MODE}" in
      venus|zink-venus)
        if printf '%s' "$HELP" | grep -q -- '--venus'; then
          if printf '%s' "$HELP" | grep -q -- '--no-virgl'; then
            VTEST_ARGS+=(--no-virgl --venus)
          else
            VTEST_ARGS+=(--venus)
          fi
        fi
        echo "FluxLinux: Starting Venus host server..."
        ;;
      *)
        echo "FluxLinux: Starting VirGL server..."
        ;;
    esac
    RENDER_SERVER_EXEC_PATH="${TERMUX_PREFIX}/opt/virglrenderer-android/libexec/virgl_render_server"
    export RENDER_SERVER_EXEC_PATH
    LD_LIBRARY_PATH="${TERMUX_PREFIX}/opt/virglrenderer-android/lib" \
      RENDER_SERVER_EXEC_PATH="$RENDER_SERVER_EXEC_PATH" \
      $VIRGL_CMD "${VTEST_ARGS[@]}" >/dev/null 2>&1 &
    "$TB" sleep 1
  fi
  test -S "${TMPDIR}/.virgl_test" && echo "FluxLinux: GPU vtest socket ready" || \
    echo "FluxLinux: [WARN] GPU vtest socket not found"
elif [ "$NEED_VTEST" = "0" ]; then
  echo "FluxLinux: Host vtest not required (guest GPU=${GUEST_GPU_BACKEND:-$GUEST_GPU_MODE})"
else
  echo "FluxLinux: virgl_test_server_android unavailable; guest may use Turnip or software"
fi

# Start X server via app_process (needs system context)
echo "FluxLinux: Checking termux-x11 server..."
export XDG_RUNTIME_DIR="$TMPDIR"
export DISPLAY=:0

# Configure Termux:X11 preferences for Phosh (native resolution, fullscreen, direct touch)
/system/bin/am broadcast -a com.termux.x11.CHANGE_PREFERENCE -p "$PKG" \
  --es displayResolutionMode native \
  --ez fullscreen true \
  --ez hideCutout true \
  --es touchMode 3 >/dev/null 2>&1 || true

# Idempotency: reuse existing server if alive with valid socket, or clean stale process
X11_RUNNING=false
if "$TB" pidof termux-x11 >/dev/null 2>&1; then
  if [ -S "$TMPDIR/.X11-unix/X0" ] || [ -S "/tmp/.X11-unix/X0" ]; then
    X11_RUNNING=true
    echo "FluxLinux: Reusing existing termux-x11 server (PID=$("$TB" pidof termux-x11))"
  else
    echo "FluxLinux: Terminating stale termux-x11 server without active socket..."
    for _p in $("$TB" pidof termux-x11); do
      "$TB" kill -9 "$_p" 2>/dev/null || true
    done
    "$TB" sleep 0.5
  fi
fi

if ! $X11_RUNNING; then
  "$TB" rm -f "$TMPDIR/.X0-lock" "$TMPDIR/.X11-unix/X0" "/tmp/.X0-lock" "/tmp/.X11-unix/X0" 2>/dev/null || true
fi

# Fix Android 14 SecurityException for writable dex files (loader.apk must be read-only)
"$TB" chmod 0400 "$TERMUX_PREFIX/libexec/termux-x11/loader.apk" 2>/dev/null || true
"$TB" chmod 0500 "$TERMUX_PREFIX/libexec/termux-x11" 2>/dev/null || true

# Fix broken xkb symlink if pointing to old com.termux prefix
if [ -L "$TERMUX_PREFIX/share/X11/xkb" ] && [ ! -e "$TERMUX_PREFIX/share/X11/xkb" ]; then
  "$TB" rm -f "$TERMUX_PREFIX/share/X11/xkb"
  "$TB" ln -s "$TERMUX_PREFIX/share/xkeyboard-config-2" "$TERMUX_PREFIX/share/X11/xkb" 2>/dev/null || true
fi

# Resolve our installed APK path so Loader can dlopen CmdEntryPoint classes
if [ -z "$TERMUX_X11_APK_PATH" ]; then
  TERMUX_X11_APK_PATH=$(/system/bin/pm path "$PKG" 2>/dev/null | "$TB" tr -d '\r' | "$TB" sed 's/^package://')
fi
if [ -z "$TERMUX_X11_APK_PATH" ] || [ ! -f "$TERMUX_X11_APK_PATH" ]; then
  TERMUX_X11_APK_PATH=$("$TB" find /data/app -name "base.apk" -path "*$PKG*" 2>/dev/null | "$TB" head -1)
fi
export TERMUX_X11_APK_PATH
echo "FluxLinux: APK path = $TERMUX_X11_APK_PATH"

# Ensure libXlorie.so is available in /data/data app lib dir
APP_LIB_DIR="/data/data/$PKG/lib"
"$TB" mkdir -p "$APP_LIB_DIR" 2>/dev/null || true
if [ ! -f "$APP_LIB_DIR/libXlorie.so" ]; then
  for _cand in "$APP_LIB_DIR"/../lib/*/libXlorie.so "$NATIVE_LIB_DIR/libXlorie.so" /data/app/*/"$PKG"*/lib/*/libXlorie.so; do
    if [ -f "$_cand" ]; then
      "$TB" cp -f "$_cand" "$APP_LIB_DIR/libXlorie.so" && "$TB" chmod 755 "$APP_LIB_DIR/libXlorie.so" 2>/dev/null && break
    fi
  done
fi
if [ ! -f "$APP_LIB_DIR/libXlorie.so" ] && [ -n "$TERMUX_X11_APK_PATH" ]; then
  echo "FluxLinux: Extracting libXlorie.so from APK..."
  ( cd "$APP_LIB_DIR" && \
    "$TB" unzip -o "$TERMUX_X11_APK_PATH" 'lib/arm64-v8a/libXlorie.so' 2>/dev/null && \
    "$TB" mv -f lib/arm64-v8a/libXlorie.so . && "$TB" rm -rf lib ) || \
  ( cd "$APP_LIB_DIR" && \
    "$TB" unzip -o "$TERMUX_X11_APK_PATH" 'lib/armeabi-v7a/libXlorie.so' 2>/dev/null && \
    "$TB" mv -f lib/armeabi-v7a/libXlorie.so . && "$TB" rm -rf lib )
fi

if ! $X11_RUNNING; then
  echo "FluxLinux: Starting termux-x11 server via app_process..."
  TMPDIR="$TMPDIR" \
  XKB_CONFIG_ROOT="$XKB_CONFIG_ROOT" \
  LD_LIBRARY_PATH="" LD_PRELOAD="" \
  CLASSPATH="$TERMUX_PREFIX/libexec/termux-x11/loader.apk" \
  TERMUX_X11_APK_PATH="$TERMUX_X11_APK_PATH" \
  TERMUX_X11_OVERRIDE_PACKAGE="$PKG" \
  LANG=en_US.UTF-8 \
  /system/bin/app_process / \
    --nice-name="termux-x11" com.termux.x11.Loader :0 -legacy-drawing &
  XSERVER_PID=$!
  echo "FluxLinux: X server PID=$XSERVER_PID"

  X_READY=false
  for _i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    if [ -S "$TMPDIR/.X11-unix/X0" ] && "$TB" pidof termux-x11 >/dev/null 2>&1; then
      X_READY=true
      break
    fi
    "$TB" sleep 0.2
  done
  if ! $X_READY; then
    echo "FluxLinux: [ERROR] termux-x11 failed to start or create X0 socket" >&2
    exit 1
  fi
  echo "FluxLinux: X11 socket ready"
fi

# Open X11 display activity in our app
echo "FluxLinux: Launching X11 display activity..."
/system/bin/am start -n "$PKG/com.termux.x11.MainActivity" \
  --activity-single-top \
  --activity-clear-top >/dev/null 2>&1 || \
/system/bin/am start -n "$PKG/com.termux.x11.MainActivity" >/dev/null 2>&1 || true
"$TB" sleep 1

# Check if desktop session is already running
if "$TB" pidof phosh >/dev/null 2>&1 || "$TB" pidof phoc >/dev/null 2>&1 || "$TB" pidof xfce4-session >/dev/null 2>&1; then
  echo "FluxLinux: Active desktop session already running; display activity refreshed"
  exit 0
fi

# Verify guest setup
ROOTFS="$TERMUX_PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"
if [ ! -f "$ROOTFS/usr/local/bin/nativecode-desktop" ] && [ ! -f "$ROOTFS/usr/bin/startxfce4" ]; then
  echo "FluxLinux: [ERROR] Desktop setup incomplete (nativecode-desktop missing in $DISTRO rootfs)." >&2
  exit 1
fi

echo "FluxLinux: Desktop launcher=READY"

# Guest GPU mode from launch hint or /etc/fluxlinux/gpu_mode
GPU_HINT="${2:-}"
case "$GPU_HINT" in
  turnip|panvk|venus|virgl|software) GPU_MODE="$GPU_HINT" ;;
  *)
    GPU_MODE="${GUEST_GPU_MODE:-virgl}"
    if [ -r "$ROOTFS/etc/fluxlinux/gpu_mode" ]; then
      GPU_MODE=$("$TB" tr -d '[:space:]' <"$ROOTFS/etc/fluxlinux/gpu_mode")
    fi
    ;;
esac
case "$GPU_MODE" in
  turnip|panvk|venus|virgl|software) ;;
  *) GPU_MODE=venus ;;
esac
echo "FluxLinux: Guest GPU mode=$GPU_MODE backend=${GUEST_GPU_BACKEND:-}"

# Launch XFCE in proot
if [ "$DISTRO" = "termux" ]; then
  export PULSE_SERVER=127.0.0.1
  env DISPLAY=:0 startxfce4
else
  PYTHON_BIN="${TERMUX_PREFIX}/bin/python3.14"
  [ -f "$PYTHON_BIN" ] || PYTHON_BIN="${TERMUX_PREFIX}/bin/python"
  PYTHON_CMD="$PYTHON_BIN"
  [ -n "$LINKER" ] && [ -f "$PYTHON_BIN" ] && PYTHON_CMD="$LINKER $PYTHON_BIN"

  $PYTHON_CMD "$TERMUX_PREFIX/bin/proot-distro" \
    login "$DISTRO" \
    --shared-tmp \
    --user flux \
    -- /bin/bash --noprofile --norc -c '
      export HOME=/home/flux
      export USER=flux
      export LOGNAME=flux
      export DISPLAY=:0
      export PULSE_SERVER=tcp:127.0.0.1
      export XDG_RUNTIME_DIR=/tmp
      export VTEST_SOCKET_NAME=/tmp/.virgl_test
      export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

      if pidof phosh >/dev/null 2>&1 || pidof phoc >/dev/null 2>&1 || pidof xfce4-session >/dev/null 2>&1; then
        echo "FluxLinux: Desktop session already active in guest"
        exit 0
      fi

      if [ ! -s /var/lib/dbus/machine-id ] && [ ! -s /etc/machine-id ]; then
        dbus-uuidgen --ensure=/etc/machine-id 2>/dev/null || true
        mkdir -p /var/lib/dbus 2>/dev/null || true
        ln -sf /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || cp -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true
      fi

      unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE VK_ICD_FILENAMES VK_DRIVER_FILES
      unset LIBGL_ALWAYS_SOFTWARE TU_DEBUG MESA_VK_WSI_DEBUG VN_DEBUG

      GPU_MODE="'"$GPU_MODE"'"
      GPU_BACKEND="'"${GUEST_GPU_BACKEND:-}"'"
      if [ -f /usr/local/lib/nativecode/gpu-backend.sh ]; then
        . /usr/local/lib/nativecode/gpu-backend.sh
        if [ -n "$GPU_BACKEND" ]; then
          gpu_apply "$GPU_BACKEND"
        else
          gpu_apply "$(gpu_map_mode_to_backend "$GPU_MODE")"
        fi
      else
        case "$GPU_MODE" in
          turnip)
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export GALLIUM_DRIVER=zink
            export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
            export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
            export TU_DEBUG=noconform
            export MESA_VK_WSI_DEBUG=sw
            ;;
          panvk|panvk-zink)
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export GALLIUM_DRIVER=zink
            export VK_DRIVER_FILES=/opt/nativecode/panvk/icd.d/panvk_kbase.json
            export VK_ICD_FILENAMES=/opt/nativecode/panvk/icd.d/panvk_kbase.json
            export WSI_X11_TERMUX=1
            export PANVK_KBASE_DRI3=raw
            export MESA_VK_WSI_DEBUG=sw
            ;;
          venus)
            export VN_DEBUG=vtest
            export GALLIUM_DRIVER=zink
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            _vn=$(ls /usr/share/vulkan/icd.d/*virtio*.json 2>/dev/null | head -n 1)
            [ -n "$_vn" ] && export VK_DRIVER_FILES="$_vn" && export VK_ICD_FILENAMES="$_vn"
            export VTEST_SOCKET_NAME=/tmp/.virgl_test
            ;;
          virgl)
            if [ -S /tmp/.virgl_test ]; then
              export GALLIUM_DRIVER=virpipe
            else
              export LIBGL_ALWAYS_SOFTWARE=1
              export GALLIUM_DRIVER=llvmpipe
            fi
            ;;
          *)
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            ;;
        esac
      fi

      mkdir -p /tmp/runtime-flux
      chmod 700 /tmp/runtime-flux 2>/dev/null || true
      export XDG_RUNTIME_DIR=/tmp/runtime-flux

      if [ -z "$DBUS_SESSION_BUS_ADDRESS" ] || [ ! -S "$(echo "$DBUS_SESSION_BUS_ADDRESS" | sed "s/unix:path=//" | cut -d, -f1)" ]; then
        eval $(dbus-launch --sh-syntax)
      fi

      if [ -x /usr/local/bin/nativecode-desktop ]; then
        exec /usr/local/bin/nativecode-desktop
      else
        exec startxfce4
      fi
    '
  GUEST_EXIT=$?
  if [ $GUEST_EXIT -ne 0 ]; then
    echo "FluxLinux: [ERROR] Guest session exited with code $GUEST_EXIT" >&2
    exit $GUEST_EXIT
  fi
fi

exit 0
