#!/bin/bash
# setup_hw_accel_debian.sh
# Hardware Acceleration Setup for Debian (PRoot / chroot)
#
# Modes:
#   turnip — Snapdragon / Adreno (KGSL) via Mesa Turnip + Zink
#   venus  — Mali / PowerVR / Xclipse / unknown (Mesa Venus → host vtest)
#   virgl  — VirGL/ANGLE fallback
#
# FLUX_GPU values:
#   turnip | adreno | snapdragon | qcom  → turnip
#   venus  | mali | powervr | xclipse    → venus
#   virgl  | virpipe                     → virgl
#   unset  → auto-detect inside guest (getprop / /dev/kgsl-3d0 / cpuinfo)

set -euo pipefail

# Prebuilt-rootfs helper (baked). Fallback: legacy = not baked.
NC_COMMON="/usr/local/lib/nativecode/rootfs-common.sh"
if [ -f "$NC_COMMON" ]; then
    # shellcheck source=/dev/null
    . "$NC_COMMON"
else
    NC_RELEASE_FILE="${NC_RELEASE_FILE:-/etc/nativecode/rootfs-release}"
    nc_component_baked() { [ -n "${1:-}" ] && [ -f "$NC_RELEASE_FILE" ] && grep -q "^COMPONENT_$1=1" "$NC_RELEASE_FILE" 2>/dev/null; }
fi
if nc_component_baked F 2>/dev/null; then
    echo "NativeCode: F already baked — runtime configuration only (no apk)"
    PREBUILT_F=1
else
    PREBUILT_F=0
fi

if [ "$(id -u)" != "0" ]; then
    echo "This script must be run as root."
    exit 1
fi

echo "FluxLinux: Setting up Hardware Acceleration (Debian)..."

MODE_STATE_DIR="/etc/fluxlinux"
MODE_STATE_FILE="$MODE_STATE_DIR/gpu_mode"
VENDOR_STATE_FILE="$MODE_STATE_DIR/gpu_vendor"

# ── helpers ──────────────────────────────────────────────────────────────────

normalize_mode() {
    # stdin/arg → turnip|venus|virgl|software|ask
    local raw
    raw=$(echo "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    case "$raw" in
        turnip|adreno|snapdragon|qcom|qualcomm|kgsl|zink)
            echo "turnip"
            ;;
        venus|virtio|mali|powervr|xclipse)
            echo "venus"
            ;;
        virgl|virpipe|virgl-angle)
            echo "virgl"
            ;;
        llvmpipe|soft|software|sw)
            echo "software"
            ;;
        ask|manual|"")
            echo "ask"
            ;;
        *)
            echo "venus"
            ;;
    esac
}

collect_gpu_hints() {
    local hints=""
    if command -v getprop >/dev/null 2>&1; then
        for k in \
            ro.hardware ro.hardware.chipname ro.chipname ro.board.platform \
            ro.soc.model ro.soc.manufacturer ro.product.board \
            ro.hardware.egl ro.hardware.vulkan ro.gfx.driver.0; do
            hints+=" $(getprop "$k" 2>/dev/null || true)"
        done
    fi
    # Android props often visible via /system even under proot
    if [ -r /proc/cpuinfo ]; then
        hints+=" $(grep -E 'Hardware|model name|Processor' /proc/cpuinfo 2>/dev/null | head -20 || true)"
    fi
    if [ -e /dev/kgsl-3d0 ] || [ -e /dev/kgsl ]; then
        hints+=" kgsl"
    fi
    # Some proot binds expose kgsl under host path
    if [ -e /dev/kgsl-3d0 ]; then
        hints+=" kgsl-3d0"
    fi
    echo "$hints" | tr '[:upper:]' '[:lower:]'
}

auto_detect_mode() {
    local hints vendor
    hints=$(collect_gpu_hints)
    vendor="unknown"

    # Strong signal: KGSL device node (Adreno)
    if [ -e /dev/kgsl-3d0 ] || echo "$hints" | grep -qE '\bkgsl\b'; then
        vendor="adreno/snapdragon"
        echo "turnip|$vendor|$hints"
        return
    fi

    if echo "$hints" | grep -qE 'qcom|qualcomm|adreno|snapdragon|msm[0-9]|sdm[0-9]|sm[0-9]{3,4}|lahaina|taro|kalama|pineapple|kona|lito|bengal|holi|crow|parrot|blair|waipio|yupik|shima|atoll|trinket'; then
        vendor="adreno/snapdragon"
        echo "turnip|$vendor|$hints"
        return
    fi

    if echo "$hints" | grep -qE 'mali|exynos|kirin|hisi|mediatek|mt6[0-9]|dimensity|helio|tensor|gs10|gs20|gs30'; then
        vendor="mali"
        echo "venus|$vendor|$hints"
        return
    fi

    if echo "$hints" | grep -qE 'powervr|imgtec|imagination|rogue'; then
        vendor="powervr"
        echo "venus|$vendor|$hints"
        return
    fi

    if echo "$hints" | grep -qE 'xclipse|amdgpu'; then
        vendor="xclipse"
        echo "venus|$vendor|$hints"
        return
    fi

    echo "venus|$vendor|$hints"
}

prepare_turnip_guest() {
    echo "FluxLinux: Disabling XFCE4 compositor for Turnip..."
    for userdir in /home/* /root; do
        [ -d "$userdir" ] || continue
        XFCE_CONF_DIR="$userdir/.config/xfce4/xfconf/xfce-perchannel-xml"
        mkdir -p "$XFCE_CONF_DIR" 2>/dev/null || true
        cat > "$XFCE_CONF_DIR/xfwm4.xml" << 'XFCEXML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
XFCEXML
    done
    echo "FluxLinux: Creating /dev/dri compatibility layer..."
    mkdir -p /dev/dri 2>/dev/null || true
    [ -e /dev/dri/card0 ] || ln -sf /dev/null /dev/dri/card0 2>/dev/null || true
    [ -e /dev/dri/renderD128 ] || ln -sf /dev/null /dev/dri/renderD128 2>/dev/null || true
    chmod 755 /dev/dri 2>/dev/null || true
}

# ── 1. deps ──────────────────────────────────────────────────────────────────

echo "FluxLinux: Detecting Package Manager..."

# apk first: this rootfs family is Alpine-native; host proot leakage
# (pacman/apt visible inside the guest) must never shadow the guest PM.
if command -v apk >/dev/null 2>&1; then
    echo "Alpine Linux detected (apk)."
    if [ "${PREBUILT_F:-0}" = "1" ]; then
        echo "NativeCode: F packages baked — skipping apk install"
        command -v vulkaninfo >/dev/null 2>&1 || true
    else
        echo "FluxLinux: Installing Vulkan/Mesa dependencies..."
        apk add --no-cache \
            mesa mesa-dri-gallium mesa-vulkan-freedreno vulkan-loader \
            curl unzip || true
        apk add --no-cache mesa-vulkan-virtio vulkan-tools mesa-utils 2>/dev/null || true
        echo "FluxLinux: Alpine/Alpaquita Mesa deps installed (Turnip ICD + optional Venus ICD)."
    fi
elif command -v pacman >/dev/null 2>&1; then
    echo "Arch Linux detected (pacman). Running System Update..."
    pacman -Syu --noconfirm
    echo "FluxLinux: Installing Arch Dependencies..."
    pacman -S --noconfirm \
        mesa vulkan-radeon vulkan-swrast vulkan-tools mesa-utils \
        curl unzip xdg-desktop-portal
    echo "FluxLinux: Arch Setup Complete."
elif command -v apt-get >/dev/null 2>&1; then
    echo "Debian/Ubuntu detected (apt)."
    echo "FluxLinux: Installing Vulkan/Mesa dependencies..."
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        mesa-utils libgl1-mesa-dri mesa-vulkan-drivers vulkan-tools \
        curl unzip libvulkan1 libgl1 libglx0 xdg-desktop-portal
else
    echo "Error: Neither apk, pacman nor apt found. Cannot install dependencies."
    exit 1
fi

# ── 2. arch ──────────────────────────────────────────────────────────────────

ARCH=""
if command -v dpkg >/dev/null 2>&1; then
    ARCH=$(dpkg --print-architecture 2>/dev/null || true)
fi
if [ -z "$ARCH" ]; then
    case "$(uname -m)" in
        aarch64|arm64) ARCH=arm64 ;;
        armv7*|armhf) ARCH=armhf ;;
        x86_64|amd64) ARCH=amd64 ;;
        *) ARCH=$(uname -m) ;;
    esac
fi
if [ "$ARCH" != "arm64" ] && [ "$ARCH" != "aarch64" ]; then
    echo "Warning: Turnip packages are arm64-only. Your arch is $ARCH."
fi

# ── 3. mode select ───────────────────────────────────────────────────────────

# Empty FLUX_GPU → auto. ask/manual → menu only on TTY. else normalize.
RAW_FLUX_GPU="${FLUX_GPU:-}"
NORMALIZED=$(normalize_mode "$RAW_FLUX_GPU")
VENDOR_HINT="unknown"
DETECT_HINTS=""
MODE=""

want_menu=0
if [ "$RAW_FLUX_GPU" = "ask" ] || [ "$RAW_FLUX_GPU" = "manual" ]; then
    want_menu=1
fi

if [ "$want_menu" = "1" ] && [ -t 0 ]; then
    echo "============================================"
    echo "      Select your GPU / Acceleration Mode"
    echo "============================================"
    echo "1) Adreno (Turnip + Zink) — Snapdragon"
    echo "2) Venus (VirtIO Vulkan) — Mali / PowerVR / other"
    echo "3) VirGL (legacy OpenGL fallback)"
    echo "============================================"
    read -r -p "Enter choice [1-3]: " GPU_CHOICE
    case "${GPU_CHOICE:-}" in
        1) MODE="turnip"; VENDOR_HINT="manual-adreno" ;;
        2) MODE="venus"; VENDOR_HINT="manual-venus" ;;
        3) MODE="virgl"; VENDOR_HINT="manual-virgl" ;;
        *)
            echo "Invalid choice. Defaulting to Venus."
            MODE="venus"
            VENDOR_HINT="invalid-default-venus"
            ;;
    esac
elif [ "$NORMALIZED" = "ask" ]; then
    # unset / empty → auto-detect (never block onboarding)
    DET=$(auto_detect_mode)
    MODE=$(echo "$DET" | cut -d'|' -f1)
    VENDOR_HINT=$(echo "$DET" | cut -d'|' -f2)
    DETECT_HINTS=$(echo "$DET" | cut -d'|' -f3-)
    echo "FluxLinux: Auto-detected GPU mode=$MODE vendor=$VENDOR_HINT"
    echo "FluxLinux: hints: $DETECT_HINTS"
else
    MODE="$NORMALIZED"
    case "$MODE" in
        turnip) VENDOR_HINT="env-adreno" ;;
        venus) VENDOR_HINT="env-venus" ;;
        virgl) VENDOR_HINT="env-virgl" ;;
        software) VENDOR_HINT="env-software" ;;
        *) VENDOR_HINT="env-other" ;;
    esac
    echo "FluxLinux: FLUX_GPU=${RAW_FLUX_GPU} → mode=$MODE"
fi

# Arm64-only turnip packages: fall back to Venus on other arches
if [ "$MODE" = "turnip" ] && [ "$ARCH" != "arm64" ] && [ "$ARCH" != "aarch64" ]; then
    echo "FluxLinux: [WARN] Turnip not available for arch=$ARCH — falling back to Venus."
    MODE="venus"
    VENDOR_HINT="${VENDOR_HINT}+arch-fallback"
fi

echo "FluxLinux: Configuring for mode=$MODE vendor=$VENDOR_HINT..."

# ── 4. turnip (baked ICD only — no remote driver download, Play policy) ──────

if [ "$MODE" = "turnip" ]; then
    echo "NativeCode: Snapdragon Turnip uses baked mesa-vulkan-freedreno + /dev/kgsl-3d0."
    echo "NativeCode: No remote Turnip/Mesa download (Play: no downloaded executable code)."
    prepare_turnip_guest
    VENDOR_HINT="${VENDOR_HINT}+baked-turnip"
fi

# ── 5. virgl note (deps already installed) ───────────────────────────────────

if [ "$MODE" = "virgl" ]; then
    echo "FluxLinux: VirGL mode — guest uses GALLIUM_DRIVER=virpipe."
    echo "FluxLinux: Host must run virgl_test_server_android (start_gui.sh)."
fi
if [ "$MODE" = "venus" ]; then
    echo "FluxLinux: Venus mode — guest uses Mesa virtio ICD (VN_DEBUG=vtest)."
    echo "FluxLinux: Host start_gui.sh starts virgl_test_server_android --venus when present."
fi

# ── 6. gpu-launch wrapper ────────────────────────────────────────────────────

echo "FluxLinux: Installing gpu-launch (mode=$MODE)..."
mkdir -p /usr/local/lib/nativecode /usr/local/bin
if [ ! -f /usr/local/lib/nativecode/gpu-backend.sh ]; then
    echo "FluxLinux: [WARN] gpu-backend.sh not baked — gpu-launch needs overlay/rootfs rebuild"
fi
if [ ! -x /usr/local/bin/gpu-launch ]; then
    cat > /usr/local/bin/gpu-launch <<'GL'
#!/bin/bash
set -euo pipefail
GPU_LIB="/usr/local/lib/nativecode/gpu-backend.sh"
if [ -f "$GPU_LIB" ]; then
    . "$GPU_LIB"
else
    exec "$@"
fi
MODE="${1:-}"
case "$MODE" in
    --auto|--turnip|--venus|--zink-venus|--virgl-angle|--software|--diagnose)
        MODE="${MODE#--}"; shift ;;
    *) MODE="" ;;
esac
[ -n "${FLUX_GPU_RUNTIME:-}" ] && MODE="$FLUX_GPU_RUNTIME"
if [ "$MODE" = "diagnose" ]; then gpu_diagnose; exit 0; fi
[ -z "$MODE" ] && MODE=$(gpu_read_backend)
[ "$MODE" = "auto" ] && MODE=$(gpu_auto_select)
gpu_persist "$MODE"
gpu_apply "$MODE"
exec "$@"
GL
fi
chmod +x /usr/local/bin/gpu-launch

# Login + interactive bash must apply Mesa/Venus env (not just FLUX_GPU_MODE).
# Without this, vulkaninfo/fastfetch hit /dev/dri and report llvmpipe + 0 devices.
mkdir -p /etc/profile.d
cat > /etc/profile.d/flux-gpu.sh << 'PROFILE'
# NativeCode GPU: Venus is vtest AF_UNIX, not /dev/dri.
# open(/dev/dri/card0)=EACCES under PRoot is expected. Do not wait for renderD128.
if [ -r /usr/local/lib/nativecode/gpu-backend.sh ]; then
    # shellcheck disable=SC1091
    . /usr/local/lib/nativecode/gpu-backend.sh
    _nc_gpu_be=$(gpu_read_backend 2>/dev/null || echo zink-venus)
    gpu_apply "$_nc_gpu_be"
    unset _nc_gpu_be
fi
PROFILE
chmod 644 /etc/profile.d/flux-gpu.sh
# Non-login interactive bash (XFCE terminal) skips profile.d.
if [ -d /etc/bash ]; then
    # Alpaquita interactive bash: `for f in /etc/bash/*.sh`
    cp -f /etc/profile.d/flux-gpu.sh /etc/bash/flux-gpu.sh
    mkdir -p /etc/bash/bashrc.d
    ln -sf /etc/profile.d/flux-gpu.sh /etc/bash/bashrc.d/flux-gpu.sh 2>/dev/null || \
        cp -f /etc/profile.d/flux-gpu.sh /etc/bash/bashrc.d/flux-gpu.sh
fi
if [ -d /home/flux ]; then
    grep -q flux-gpu.sh /home/flux/.bashrc 2>/dev/null || \
        echo '[ -r /etc/profile.d/flux-gpu.sh ] && . /etc/profile.d/flux-gpu.sh' >> /home/flux/.bashrc
    chown flux:flux /home/flux/.bashrc 2>/dev/null || true
fi

# Persist mode for start_gui.sh / gpu-launch
mkdir -p "$MODE_STATE_DIR"
echo "$MODE" > "$MODE_STATE_FILE"
echo "$VENDOR_HINT" > "$VENDOR_STATE_FILE"
BACKEND_FILE="$MODE_STATE_DIR/gpu_backend"
METHOD_FILE="$MODE_STATE_DIR/gpu_method"
case "$MODE" in
    turnip) BACKEND=turnip; METHOD="Turnip KGSL (Zink)" ;;
    venus) BACKEND=zink-venus; METHOD="Zink over Venus" ;;
    virgl) BACKEND=virgl-angle; METHOD="VirGL (virpipe)" ;;
    software) BACKEND=llvmpipe; METHOD="llvmpipe (software)" ;;
    *) BACKEND=zink-venus; METHOD="Zink over Venus" ;;
esac
echo "$BACKEND" > "$BACKEND_FILE"
echo "$METHOD" > "$METHOD_FILE"
chmod 644 "$MODE_STATE_FILE" "$VENDOR_STATE_FILE" "$BACKEND_FILE" "$METHOD_FILE"

echo ""
echo "============================================"
echo "  Hardware Acceleration Setup Complete!"
echo "============================================"
echo "Mode:   $MODE"
echo "Vendor: $VENDOR_HINT"
echo "State:  $MODE_STATE_FILE"
echo ""
echo "Usage: gpu-launch <application>"
echo "Example: gpu-launch glmark2"
echo ""

if [ "$MODE" = "virgl" ]; then
    echo "IMPORTANT: VirGL needs virgl_test_server on the host (start_gui.sh)."
    echo ""
fi
if [ "$MODE" = "venus" ]; then
    echo "Venus: start_gui.sh starts host vtest with --venus when the binary supports it."
    echo ""
fi
if [ "$MODE" = "turnip" ]; then
    echo "Turnip configured for Adreno/Snapdragon (KGSL)."
    echo "If GL fails: gpu-launch --auto <app>  (Venus fallback)"
    echo ""
fi

echo "Test:"
echo "  gpu-launch --diagnose"
echo "  gpu-launch --auto glxinfo | grep 'OpenGL renderer'"
echo "============================================"
