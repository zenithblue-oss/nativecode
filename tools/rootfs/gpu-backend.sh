#!/bin/sh
# gpu-backend.sh — guest GPU backend env + probes.
# Sourced by gpu-launch and startxfce wrappers.
# POSIX sh. No host PATH.
# shellcheck shell=sh

GPU_STATE_DIR="${GPU_STATE_DIR:-/etc/fluxlinux}"
GPU_MODE_FILE="$GPU_STATE_DIR/gpu_mode"
GPU_VENDOR_FILE="$GPU_STATE_DIR/gpu_vendor"
GPU_BACKEND_FILE="$GPU_STATE_DIR/gpu_backend"
GPU_METHOD_FILE="$GPU_STATE_DIR/gpu_method"
GPU_PROBE_FILE="$GPU_STATE_DIR/gpu_last_probe"
VTEST_SOCK="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"

gpu_unset_env() {
    unset GALLIUM_DRIVER
    unset MESA_LOADER_DRIVER_OVERRIDE
    unset VK_DRIVER_FILES
    unset VK_ICD_FILENAMES
    unset VN_DEBUG
    unset VTEST_SOCKET_NAME
    unset LIBGL_ALWAYS_SOFTWARE
    unset TU_DEBUG
    unset MESA_VK_WSI_DEBUG
    unset MESA_GL_VERSION_OVERRIDE
    unset MESA_GLES_VERSION_OVERRIDE
    unset MESA_NO_ERROR
}

gpu_find_icd() {
    # arg: virtio|freedreno  → print first matching json
    _kind="$1"
    _dir="/usr/share/vulkan/icd.d"
    [ -d "$_dir" ] || return 1
    _hit=$(find "$_dir" -maxdepth 1 -type f -name "*${_kind}*.json" 2>/dev/null | head -n 1)
    [ -n "$_hit" ] && [ -f "$_hit" ] && { printf '%s\n' "$_hit"; return 0; }
    # opt overlay from lfdevs / NativeCode Turnip
    if [ "$_kind" = "freedreno" ]; then
        for _c in \
            /opt/nativecode-turnip/icd/freedreno_icd.aarch64.json \
            /usr/lib/aarch64-linux-gnu/GL/vulkan/icd.d/freedreno_icd.aarch64.json
        do
            [ -f "$_c" ] && { printf '%s\n' "$_c"; return 0; }
        done
    fi
    return 1
}

gpu_kgsl_present() {
    [ -e /dev/kgsl-3d0 ] || [ -e /dev/kgsl ]
}

gpu_method_label() {
    case "$1" in
        turnip) printf '%s\n' "Turnip KGSL (Zink)" ;;
        venus) printf '%s\n' "Venus (VirtIO Vulkan)" ;;
        zink-venus) printf '%s\n' "Zink over Venus" ;;
        virgl-angle|virgl) printf '%s\n' "VirGL (virpipe)" ;;
        llvmpipe|software) printf '%s\n' "llvmpipe (software)" ;;
        *) printf '%s\n' "$1" ;;
    esac
}

gpu_apply() {
    # arg: turnip|venus|zink-venus|virgl-angle|llvmpipe
    gpu_unset_env
    _be="$1"
    _fd=$(gpu_find_icd freedreno 2>/dev/null || true)
    _vn=$(gpu_find_icd virtio 2>/dev/null || true)
    export VTEST_SOCKET_NAME="$VTEST_SOCK"
    case "$_be" in
        turnip)
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export GALLIUM_DRIVER=zink
            [ -n "$_fd" ] && export VK_DRIVER_FILES="$_fd" && export VK_ICD_FILENAMES="$_fd"
            export TU_DEBUG=noconform
            export MESA_VK_WSI_DEBUG=sw
            export MESA_GL_VERSION_OVERRIDE=4.6
            export MESA_GLES_VERSION_OVERRIDE=3.2
            export MESA_NO_ERROR=1
            ;;
        venus|zink-venus)
            # Same as zink-venus: GL via Zink, Vulkan via virtio ICD + vtest.
            export VN_DEBUG=vtest
            export GALLIUM_DRIVER=zink
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            [ -n "$_vn" ] && export VK_DRIVER_FILES="$_vn" && export VK_ICD_FILENAMES="$_vn"
            export MESA_VK_WSI_DEBUG=sw
            export MESA_GL_VERSION_OVERRIDE=4.6
            export MESA_GLES_VERSION_OVERRIDE=3.2
            export MESA_NO_ERROR=1
            ;;
        virgl-angle|virgl)
            export GALLIUM_DRIVER=virpipe
            export MESA_GL_VERSION_OVERRIDE=4.0
            export MESA_GLES_VERSION_OVERRIDE=3.1
            export MESA_NO_ERROR=1
            ;;
        llvmpipe|software)
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            ;;
        *)
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            ;;
    esac
}

gpu_renderer_is_soft() {
    printf '%s' "$1" | grep -qiE 'lavapipe|llvmpipe|swiftshader|softpipe|swrast'
}

gpu_probe_turnip() {
    gpu_kgsl_present || return 1
    _fd=$(gpu_find_icd freedreno) || return 1
    command -v vulkaninfo >/dev/null 2>&1 || return 1
    gpu_unset_env
    export VK_DRIVER_FILES="$_fd"
    export VK_ICD_FILENAMES="$_fd"
    export TU_DEBUG=noconform
    export MESA_VK_WSI_DEBUG=sw
    _out=$(vulkaninfo --summary 2>/dev/null || true)
    gpu_renderer_is_soft "$_out" && return 1
    printf '%s' "$_out" | grep -qiE 'adreno|turnip|freedreno|qualcomm'
}

gpu_probe_venus() {
    _vn=$(gpu_find_icd virtio) || return 1
    [ -S "$VTEST_SOCK" ] || return 1
    gpu_unset_env
    export VN_DEBUG=vtest
    export VK_DRIVER_FILES="$_vn"
    export VK_ICD_FILENAMES="$_vn"
    export VTEST_SOCKET_NAME="$VTEST_SOCK"
    if command -v vulkaninfo >/dev/null 2>&1; then
        _out=$(vulkaninfo --summary 2>/dev/null || true)
        gpu_renderer_is_soft "$_out" && return 1
        printf '%s' "$_out" | grep -qiE 'venus|virtio|mali|adreno'
        return $?
    fi
    if command -v python3 >/dev/null 2>&1; then
        python3 - <<'PY'
import ctypes, os, sys
vk = ctypes.CDLL("libvulkan.so.1", mode=os.RTLD_GLOBAL)
class App(ctypes.Structure):
    _fields_ = [("sType", ctypes.c_int32), ("pNext", ctypes.c_void_p),
                ("pApp", ctypes.c_void_p), ("appVer", ctypes.c_uint32),
                ("pEngine", ctypes.c_void_p), ("engVer", ctypes.c_uint32),
                ("apiVer", ctypes.c_uint32)]
class CI(ctypes.Structure):
    _fields_ = [("sType", ctypes.c_int32), ("pNext", ctypes.c_void_p),
                ("flags", ctypes.c_uint32), ("pApp", ctypes.c_void_p),
                ("nLayer", ctypes.c_uint32), ("layers", ctypes.c_void_p),
                ("nExt", ctypes.c_uint32), ("exts", ctypes.c_void_p)]
vk.vkCreateInstance.restype = ctypes.c_int32
vk.vkEnumeratePhysicalDevices.restype = ctypes.c_int32
app = App(0, None, None, 0, None, 0, (1 << 22) | (1 << 12))
ci = CI(1, None, 0, ctypes.addressof(app), 0, None, 0, None)
inst = ctypes.c_void_p()
if vk.vkCreateInstance(ctypes.byref(ci), None, ctypes.byref(inst)) != 0:
    sys.exit(1)
n = ctypes.c_uint32(0)
r = vk.vkEnumeratePhysicalDevices(inst, ctypes.byref(n), None)
sys.exit(0 if r == 0 and n.value > 0 else 1)
PY
        return $?
    fi
    return 1
}

gpu_probe_virgl() {
    [ -S "$VTEST_SOCK" ]
}

gpu_map_mode_to_backend() {
    case "$1" in
        turnip) printf '%s\n' turnip ;;
        venus) printf '%s\n' zink-venus ;;
        zink-venus) printf '%s\n' zink-venus ;;
        virgl|virgl-angle) printf '%s\n' virgl-angle ;;
        software|llvmpipe) printf '%s\n' llvmpipe ;;
        *) printf '%s\n' zink-venus ;;
    esac
}

gpu_read_backend() {
    if [ -r "$GPU_BACKEND_FILE" ]; then
        _b=$(tr -d '[:space:]' <"$GPU_BACKEND_FILE")
        [ -n "$_b" ] && { printf '%s\n' "$_b"; return 0; }
    fi
    if [ -r "$GPU_MODE_FILE" ]; then
        _m=$(tr -d '[:space:]' <"$GPU_MODE_FILE")
        gpu_map_mode_to_backend "$_m"
        return 0
    fi
    printf '%s\n' zink-venus
}

# Login default: live nodes + baked ICDs. Do not require vulkaninfo or a live
# vtest socket (Mali Venus env must be set before the host server is up).
gpu_login_select() {
    if gpu_kgsl_present && gpu_find_icd freedreno >/dev/null 2>&1; then
        printf '%s\n' turnip
        return 0
    fi
    if gpu_find_icd virtio >/dev/null 2>&1; then
        printf '%s\n' zink-venus
        return 0
    fi
    gpu_read_backend
}

gpu_auto_select() {
    gpu_login_select
}

gpu_persist() {
    _be="$1"
    mkdir -p "$GPU_STATE_DIR"
    printf '%s\n' "$_be" > "$GPU_PROBE_FILE"
    chmod 644 "$GPU_PROBE_FILE" 2>/dev/null || true
    # Do not clobber a configured Turnip/Venus hint with software fallback.
    if [ "$_be" = "llvmpipe" ] && [ -r "$GPU_MODE_FILE" ]; then
        _m=$(tr -d '[:space:]' <"$GPU_MODE_FILE")
        case "$_m" in
            turnip|venus|virgl) return 0 ;;
        esac
    fi
    printf '%s\n' "$_be" > "$GPU_BACKEND_FILE"
    gpu_method_label "$_be" > "$GPU_METHOD_FILE"
    chmod 644 "$GPU_BACKEND_FILE" "$GPU_METHOD_FILE" 2>/dev/null || true
}

gpu_diagnose() {
    echo "kgsl=$(gpu_kgsl_present && echo yes || echo no)"
    echo "vtest_socket=$VTEST_SOCK exists=$([ -S "$VTEST_SOCK" ] && echo yes || echo no)"
    echo "freedreno_icd=$(gpu_find_icd freedreno 2>/dev/null || echo none)"
    echo "virtio_icd=$(gpu_find_icd virtio 2>/dev/null || echo none)"
    echo "gpu_mode=$([ -r "$GPU_MODE_FILE" ] && tr -d '[:space:]' <"$GPU_MODE_FILE" || echo unset)"
    echo "gpu_vendor=$([ -r "$GPU_VENDOR_FILE" ] && tr -d '[:space:]' <"$GPU_VENDOR_FILE" || echo unset)"
    echo "gpu_backend=$([ -r "$GPU_BACKEND_FILE" ] && tr -d '[:space:]' <"$GPU_BACKEND_FILE" || echo unset)"
    echo "gpu_method=$([ -r "$GPU_METHOD_FILE" ] && tr -d '\n' <"$GPU_METHOD_FILE" || echo unset)"
    echo "vulkaninfo=$(command -v vulkaninfo >/dev/null 2>&1 && echo yes || echo no)"
    echo "note=Venus uses VN_DEBUG=vtest + VTEST_SOCKET_NAME (AF_UNIX). /dev/dri EACCES is expected under PRoot and is not the Venus path."
}
