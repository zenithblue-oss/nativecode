#!/bin/bash
set -u
echo "=== GUEST GPU TESTS ==="
echo "whoami=$(whoami) mali=$(ls -l /dev/mali0 2>&1)"
. /opt/nativecode/panvk/env.sh
. /usr/local/lib/nativecode/gpu-backend.sh 2>/dev/null || true

echo "=== FASTFETCH ==="
if [ -x /usr/local/bin/fastfetch ]; then
    /usr/local/bin/fastfetch --gpu-driver-specific 2>&1 | grep -iE 'GPU|Mali|Vulkan|Device|Renderer' | head -40
    echo "--- raw gpu lines ---"
    /usr/local/bin/fastfetch --gpu-driver-specific 2>&1 | head -80
elif [ -x /usr/bin/fastfetch ]; then
    gpu_apply panvk 2>/dev/null || true
    /usr/bin/fastfetch --gpu-driver-specific 2>&1 | grep -iE 'GPU|Mali|Vulkan|Device|Renderer' | head -40
else
    echo FASTFETCH_ABSENT
fi

echo "=== KBASE GPUPROPS KEYS ==="
if [ -x /opt/nativecode/panvk/bin/nativecode-mali-probe ]; then
    /opt/nativecode/panvk/bin/nativecode-mali-probe 2>&1 | awk '$2==1 || $2==55 || $2=="1" || /Key[[:space:]]+1 / || /Key[[:space:]]+55 / || /Key[[:space:]]+1\(/ {print}'
    echo "--- keys 1 and 55 ---"
    /opt/nativecode/panvk/bin/nativecode-mali-probe 2>&1 | grep -E 'Key[[:space:]]+(1|55) '
else
    echo NO_MALI_PROBE
fi

echo "=== VULKAN FEATURES (PanVK ICD) ==="
gpu_unset_env 2>/dev/null || true
gpu_apply panvk 2>/dev/null || . /opt/nativecode/panvk/env.sh
export LD_LIBRARY_PATH="/opt/nativecode/panvk/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export VK_DRIVER_FILES="${VK_DRIVER_FILES:-/opt/nativecode/panvk/icd.d/panvk_kbase.json}"
export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
[ -f /opt/nativecode/panvk/lib/kbase_shim.so ] && export LD_PRELOAD="/opt/nativecode/panvk/lib/kbase_shim.so${LD_PRELOAD:+:$LD_PRELOAD}"
echo "using ICD=$VK_DRIVER_FILES"
if [ -x /tmp/vulkan_features ]; then
    /tmp/vulkan_features 2>&1 | tee /tmp/vk_features.out
    echo "EXT_LIST_PATH=/tmp/vk_features.out"
else
    cc -O2 /tmp/vulkan_features.c -o /tmp/vulkan_features -lvulkan && /tmp/vulkan_features 2>&1 | tee /tmp/vk_features.out
fi

echo "=== PANVK COMPUTE ==="
/opt/nativecode/panvk/bin/vulkan_compute_test 2>&1 | tail -20

echo "=== PANVK GRAPHICS ==="
/opt/nativecode/panvk/bin/vulkan_graphics_test 2>&1 | tail -20

echo "=== HEADLESS GL panvk-zink ==="
gpu_unset_env 2>/dev/null || true
if type gpu_apply >/dev/null 2>&1; then
    gpu_apply panvk-zink
else
    export GALLIUM_DRIVER=zink
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export VK_DRIVER_FILES=/opt/nativecode/panvk/icd.d/panvk_kbase.json
    export VK_ICD_FILENAMES=$VK_DRIVER_FILES
    export LD_LIBRARY_PATH="/opt/nativecode/panvk/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    export LD_PRELOAD=/opt/nativecode/panvk/lib/kbase_shim.so
    export MESA_VK_WSI_DEBUG=sw
    export MESA_NO_ERROR=1
fi
export LIBGL_DRIVERS_PATH="${LIBGL_DRIVERS_PATH:-/usr/lib/dri}"
ls -l /usr/lib/dri/zink_dri.so /usr/lib/libEGL.so.1 2>&1
python3 /tmp/egl_offscreen.py 2>&1
echo "zink_exit=$?"

echo "=== DIRECT PANFROST (system dri, not mesa-mali) ==="
if [ -e /opt/nativecode/mesa-mali/lib/dri/panfrost_dri.so ]; then
    echo "mesa-mali panfrost_dri=PRESENT"
elif [ -e /usr/lib/dri/panfrost_dri.so ]; then
    echo "system panfrost_dri=PRESENT target=$(readlink -f /usr/lib/dri/panfrost_dri.so)"
    echo "NOTE=system Mesa 26.1.6 DRI loader; Kbase /dev/mali0 not DRM. Expect fail unless kbase winsys present."
    gpu_unset_env 2>/dev/null || true
    if type gpu_apply >/dev/null 2>&1; then
        gpu_apply panfrost
    else
        export GALLIUM_DRIVER=panfrost
        export MESA_LOADER_DRIVER_OVERRIDE=panfrost
        export LD_PRELOAD=/opt/nativecode/panvk/lib/kbase_shim.so
    fi
    export LIBGL_DRIVERS_PATH="${LIBGL_DRIVERS_PATH:-/usr/lib/dri}"
    python3 /tmp/egl_offscreen.py 2>&1
    echo "panfrost_exit=$?"
else
    echo "DIRECT_PANFROST=ABSENT no panfrost_dri.so"
fi

echo "=== DRI FILES ==="
ls -l /usr/lib/dri/panfrost_dri.so /usr/lib/dri/zink_dri.so /opt/nativecode/mesa-mali/lib/dri 2>&1 | head
echo "=== DONE ==="
