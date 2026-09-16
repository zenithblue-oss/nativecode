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
GPU_MESA_PREFIX="/opt/nativecode/mesa-mali"
GPU_ADRENO_PREFIX="${GPU_ADRENO_PREFIX:-/opt/nativecode-turnip}"
GPU_SYSTEM_ICD_DIR="${GPU_SYSTEM_ICD_DIR:-/usr/share/vulkan/icd.d}"
GPU_MALI_PROFILE="${GPU_MALI_PROFILE:-/usr/local/bin/mali-profile}"

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
    unset LIBGL_DRIVERS_PATH
    unset __EGL_VENDOR_LIBRARY_DIRS
    if [ -n "${LD_LIBRARY_PATH:-}" ]; then
        _lp=""
        _oldifs=$IFS
        IFS=:
        for _p in $LD_LIBRARY_PATH; do
            [ -n "$_p" ] || continue
            [ "$_p" = "$GPU_ADRENO_PREFIX/lib" ] && continue
            _lp="${_lp:+$_lp:}$_p"
        done
        IFS=$_oldifs
        if [ -n "$_lp" ]; then export LD_LIBRARY_PATH="$_lp"; else unset LD_LIBRARY_PATH; fi
        unset _lp _oldifs _p
    fi
    if [ -n "${LD_PRELOAD:-}" ]; then
        _np=""
        _oldifs=$IFS
        IFS=:
        for _p in $LD_PRELOAD; do
            [ -n "$_p" ] || continue
            case "$_p" in
                */kbase_shim.so) continue ;;
            esac
            _np="${_np:+$_np:}$_p"
        done
        IFS=$_oldifs
        if [ -n "$_np" ]; then
            export LD_PRELOAD="$_np"
        else
            unset LD_PRELOAD
        fi
        unset _np _oldifs _p
    fi
}

gpu_find_icd() {
    # arg: virtio|freedreno|panvk  → print first matching json
    _kind="$1"
    _dir="$GPU_SYSTEM_ICD_DIR"
    if [ "$_kind" = "panvk" ] || [ "$_kind" = "panfrost" ]; then
        for _c in \
            "$GPU_MESA_PREFIX/share/vulkan/icd.d/panvk_kbase.json" \
            "$GPU_MESA_PREFIX/share/vulkan/icd.d/panfrost_icd.aarch64.json" \
            /opt/nativecode/panvk/icd.d/panvk_kbase.json \
            /usr/share/vulkan/icd.d/panfrost_kbase_icd.aarch64.json \
            /usr/share/vulkan/icd.d/panfrost_icd.aarch64.json
        do
            [ -f "$_c" ] && { printf '%s\n' "$_c"; return 0; }
        done
        return 1
    fi
    # The pinned LFDev stack must remain coherent with its EGL/GLX/DRI libraries.
    if [ "$_kind" = "freedreno" ]; then
        for _c in \
            "$GPU_ADRENO_PREFIX/icd/freedreno_icd.aarch64.json" \
            /usr/lib/aarch64-linux-gnu/GL/vulkan/icd.d/freedreno_icd.aarch64.json
        do
            [ -f "$_c" ] && { printf '%s\n' "$_c"; return 0; }
        done
    fi
    [ -d "$_dir" ] || return 1
    _hit=$(find "$_dir" -maxdepth 1 -type f -name "*${_kind}*.json" 2>/dev/null | head -n 1)
    [ -n "$_hit" ] && [ -f "$_hit" ] && { printf '%s\n' "$_hit"; return 0; }
    return 1
}

gpu_kgsl_present() {
    [ -e /dev/kgsl-3d0 ] || [ -e /dev/kgsl ]
}

gpu_mali_present() {
    [ -e /dev/mali0 ] || [ -e /dev/mali ]
}

gpu_kbase_shim() {
    _shim=/opt/nativecode/panvk/lib/kbase_shim.so
    [ -f "$_shim" ] || return 0
    case ":${LD_PRELOAD:-}:" in
        *:"$_shim":*) ;;
        *) export LD_PRELOAD="${_shim}${LD_PRELOAD:+:$LD_PRELOAD}" ;;
    esac
}

gpu_method_label() {
    case "$1" in
        turnip) printf '%s\n' "Turnip Vulkan + Freedreno GL (KGSL)" ;;
        panvk) printf '%s\n' "PanVK (Kbase)" ;;
        panvk-zink|zink-panvk) printf '%s\n' "Zink over PanVK" ;;
        panfrost) printf '%s\n' "Panfrost (Kbase Gallium)" ;;
        venus) printf '%s\n' "Venus (VirtIO Vulkan)" ;;
        zink-venus) printf '%s\n' "Zink over Venus" ;;
        virgl-angle|virgl) printf '%s\n' "VirGL (virpipe)" ;;
        llvmpipe|software) printf '%s\n' "llvmpipe (software)" ;;
        *) printf '%s\n' "$1" ;;
    esac
}

gpu_apply() {
    # arg: turnip|panvk|panvk-zink|venus|zink-venus|virgl-angle|llvmpipe
    gpu_unset_env
    _be="$1"
    if gpu_kgsl_present; then
        case "$_be" in
            panvk|panvk-zink|zink-panvk|panfrost)
                if gpu_find_icd freedreno >/dev/null 2>&1; then
                    _be=turnip
                elif gpu_find_icd virtio >/dev/null 2>&1; then
                    _be=zink-venus
                elif [ -S "$VTEST_SOCK" ]; then
                    _be=virgl-angle
                else
                    _be=llvmpipe
                fi
                ;;
        esac
    fi
    _fd=$(gpu_find_icd freedreno 2>/dev/null || true)
    _pv=$(gpu_find_icd panvk 2>/dev/null || true)
    _vn=$(gpu_find_icd virtio 2>/dev/null || true)
    export VTEST_SOCKET_NAME="$VTEST_SOCK"
    case "$_be" in
        turnip)
            export MESA_LOADER_DRIVER_OVERRIDE=kgsl
            export GALLIUM_DRIVER=freedreno
            if [ -d "$GPU_ADRENO_PREFIX/lib" ]; then
                export LD_LIBRARY_PATH="$GPU_ADRENO_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
                export LIBGL_DRIVERS_PATH="$GPU_ADRENO_PREFIX/lib/dri"
            fi
            [ -n "$_fd" ] && export VK_DRIVER_FILES="$_fd" && export VK_ICD_FILENAMES="$_fd"
            export TU_DEBUG=noconform
            export MESA_VK_WSI_DEBUG=sw
            export MESA_GL_VERSION_OVERRIDE=4.6
            export MESA_GLES_VERSION_OVERRIDE=3.2
            export MESA_NO_ERROR=1
            ;;
        panfrost)
            export LD_LIBRARY_PATH="$GPU_MESA_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
            export LIBGL_DRIVERS_PATH="$GPU_MESA_PREFIX/lib/dri"
            export GALLIUM_DRIVER=panfrost
            export MESA_LOADER_DRIVER_OVERRIDE=panfrost
            [ -n "$_pv" ] && export VK_DRIVER_FILES="$_pv" && export VK_ICD_FILENAMES="$_pv"
            gpu_kbase_shim
            ;;
        panvk)
            [ -n "$_pv" ] && export VK_DRIVER_FILES="$_pv" && export VK_ICD_FILENAMES="$_pv"
            gpu_kbase_shim
            export WSI_X11_TERMUX=1
            export PANVK_KBASE_DRI3=raw
            ;;
        panvk-zink|zink-panvk)
            export MESA_LOADER_DRIVER_OVERRIDE=zink
            export GALLIUM_DRIVER=zink
            [ -n "$_pv" ] && export VK_DRIVER_FILES="$_pv" && export VK_ICD_FILENAMES="$_pv"
            gpu_kbase_shim
            export WSI_X11_TERMUX=1
            export PANVK_KBASE_DRI3=raw
            export MESA_VK_WSI_DEBUG=sw
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
    gpu_unset_env
    export VK_DRIVER_FILES="$_fd"
    export VK_ICD_FILENAMES="$_fd"
    export TU_DEBUG=noconform
    export MESA_VK_WSI_DEBUG=sw
    if command -v vulkaninfo >/dev/null 2>&1; then
        _out=$(vulkaninfo --summary 2>/dev/null || true)
        gpu_renderer_is_soft "$_out" && return 1
        printf '%s' "$_out" | grep -qiE 'adreno|turnip|freedreno|qualcomm'
        return $?
    fi
    if [ -x /opt/nativecode/panvk/bin/vulkan_probe ]; then
        /opt/nativecode/panvk/bin/vulkan_probe >/dev/null 2>&1
        return $?
    fi
    return 0
}

gpu_probe_panvk() {
    gpu_kgsl_present && return 1
    gpu_mali_present || return 1
    _pv=$(gpu_find_icd panvk) || return 1
    if [ -x /opt/nativecode/panvk/bin/nativecode-mali-probe ]; then
        /opt/nativecode/panvk/bin/nativecode-mali-probe >/dev/null 2>&1
        return $?
    fi
    return 0
}

gpu_probe_panfrost() {
    gpu_mali_present || return 1
    [ -x "$GPU_MALI_PROFILE" ] || return 1
    [ -f "$GPU_MESA_PREFIX/lib/dri/panfrost_dri.so" ] || return 1
    _profile=$($GPU_MALI_PROFILE 2>/dev/null) || return 1
    printf '%s\n' "$_profile" | grep -q '^PROFILE_STATUS=\(WORKING\|DEFAULT_CANDIDATE\)$'
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
    if gpu_kgsl_present; then
        case "$1" in
            panvk|panvk-zink|zink-panvk|panfrost)
                if gpu_find_icd freedreno >/dev/null 2>&1; then printf '%s\n' turnip
                elif gpu_find_icd virtio >/dev/null 2>&1; then printf '%s\n' zink-venus
                elif [ -S "$VTEST_SOCK" ]; then printf '%s\n' virgl-angle
                else printf '%s\n' llvmpipe
                fi
                return 0
                ;;
        esac
    fi
    case "$1" in
        turnip) printf '%s\n' turnip ;;
        panfrost) printf '%s\n' panfrost ;;
        panvk) printf '%s\n' panvk-zink ;;
        panvk-zink|zink-panvk) printf '%s\n' panvk-zink ;;
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
        [ -n "$_b" ] && { gpu_map_mode_to_backend "$_b"; return 0; }
    fi
    if [ -r "$GPU_MODE_FILE" ]; then
        _m=$(tr -d '[:space:]' <"$GPU_MODE_FILE")
        gpu_map_mode_to_backend "$_m"
        return 0
    fi
    printf '%s\n' zink-venus
}

# Login default: live nodes + baked ICDs.
# KGSL -> Turnip
# Mali -> PanVK direct (no Venus fallback when /dev/mali0 is available)
# VirtIO -> Venus
gpu_login_select() {
    if gpu_kgsl_present && gpu_find_icd freedreno >/dev/null 2>&1; then
        printf '%s\n' turnip
        return 0
    fi
    if gpu_mali_present && gpu_find_icd panvk >/dev/null 2>&1; then
        printf '%s\n' panvk-zink
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
    _be=$(gpu_map_mode_to_backend "$1")
    mkdir -p "$GPU_STATE_DIR"
    printf '%s\n' "$_be" > "$GPU_PROBE_FILE"
    chmod 644 "$GPU_PROBE_FILE" 2>/dev/null || true
    # Do not clobber a configured Turnip/PanVK/Venus hint with software fallback.
    if [ "$_be" = "llvmpipe" ] && [ -r "$GPU_MODE_FILE" ]; then
        _m=$(tr -d '[:space:]' <"$GPU_MODE_FILE")
        case "$_m" in
            turnip|panvk|venus|virgl) return 0 ;;
        esac
    fi
    printf '%s\n' "$_be" > "$GPU_BACKEND_FILE"
    gpu_method_label "$_be" > "$GPU_METHOD_FILE"
    chmod 644 "$GPU_BACKEND_FILE" "$GPU_METHOD_FILE" 2>/dev/null || true
}

gpu_diagnose() {
    echo "kgsl=$(gpu_kgsl_present && echo yes || echo no)"
    echo "mali=$(gpu_mali_present && echo yes || echo no)"
    echo "vtest_socket=$VTEST_SOCK exists=$([ -S "$VTEST_SOCK" ] && echo yes || echo no)"
    echo "freedreno_icd=$(gpu_find_icd freedreno 2>/dev/null || echo none)"
    echo "adreno_mesa=$([ -r "$GPU_ADRENO_PREFIX/source.env" ] && grep '^LFDEV_TAG=' "$GPU_ADRENO_PREFIX/source.env" | cut -d= -f2 || echo none)"
    echo "panvk_icd=$(gpu_find_icd panvk 2>/dev/null || echo none)"
    echo "panfrost_validated=$(gpu_probe_panfrost && echo yes || echo no)"
    echo "virtio_icd=$(gpu_find_icd virtio 2>/dev/null || echo none)"
    echo "gpu_mode=$([ -r "$GPU_MODE_FILE" ] && tr -d '[:space:]' <"$GPU_MODE_FILE" || echo unset)"
    echo "gpu_vendor=$([ -r "$GPU_VENDOR_FILE" ] && tr -d '[:space:]' <"$GPU_VENDOR_FILE" || echo unset)"
    echo "gpu_backend=$([ -r "$GPU_BACKEND_FILE" ] && tr -d '[:space:]' <"$GPU_BACKEND_FILE" || echo unset)"
    echo "gpu_method=$([ -r "$GPU_METHOD_FILE" ] && tr -d '\n' <"$GPU_METHOD_FILE" || echo unset)"
    echo "vulkaninfo=$(command -v vulkaninfo >/dev/null 2>&1 && echo yes || echo no)"
    echo "note=Venus uses VN_DEBUG=vtest + VTEST_SOCKET_NAME (AF_UNIX). /dev/dri EACCES is expected under PRoot and is not the Venus path."
}
