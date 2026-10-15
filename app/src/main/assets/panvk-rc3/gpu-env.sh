#!/bin/sh
# /etc/profile.d/gpu-env.sh — automatic GPU environment configuration
# Sourced for login and interactive shells. Sets optimal driver defaults
# for Adreno (Turnip/Freedreno) or Mali (PanVK/Zink).

if [ -e /dev/kgsl-3d0 ] || [ -e /dev/kgsl ]; then
    # Adreno 7xx / Qualcomm KGSL via Turnip + Zink
    export VK_DRIVER_FILES="/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
    export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
    export TU_DEBUG=noconform
    export MESA_VK_WSI_DEBUG=sw
    export GALLIUM_DRIVER=zink
    export MESA_LOADER_DRIVER_OVERRIDE=zink
elif [ -e /dev/mali0 ] || [ -e /dev/mali ]; then
    # Mali Valhall / ARM Kbase
    export VK_DRIVER_FILES="/opt/nativecode/panvk/icd.d/panvk_kbase.json"
    export VK_ICD_FILENAMES="$VK_DRIVER_FILES"
    # No kbase_shim.so preload: PanVK rc3 breaks with it (gpu_id decoded as v10 -> unknown).
    export WSI_X11_TERMUX=1
    export PANVK_KBASE_DRI3=raw
    export MESA_VK_WSI_DEBUG=sw
    export GALLIUM_DRIVER=zink
    export MESA_LOADER_DRIVER_OVERRIDE=zink
fi
