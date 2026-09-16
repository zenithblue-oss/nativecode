#!/usr/bin/env python3
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parent
mesa = root / "mesa-mali"
lock = dict(
    line.split("=", 1)
    for line in (mesa / "sources.lock").read_text().splitlines()
    if line and not line.startswith("#")
)
assert lock["MESA_KBASE_COMMIT"] == "034a7a94be54d71ff659d6564422eeef8e62c926"
assert lock["PANFROST_V11_COMMIT"] == "958205d63ec535eb0ab48aaaea98a193f44389d3"
assert lock["PANVK_V11_COMMIT"] == "c66177bdc90e06aa336357da2e4de0aec6d127d6"

with tempfile.TemporaryDirectory() as tmp:
    bundle = pathlib.Path(tmp)
    (bundle / "lib").mkdir()
    payload = bundle / "lib" / "test.so"
    payload.write_bytes(b"nativecode-mesa")
    subprocess.run(["python3", str(mesa / "manifest.py"), str(bundle)], check=True)
    subprocess.run(["python3", str(mesa / "manifest.py"), "--verify", str(bundle)], check=True)
    manifest = json.loads((bundle / "manifest.json").read_text())
    assert manifest["files"]["lib/test.so"] == hashlib.sha256(payload.read_bytes()).hexdigest()

build = (mesa / "build-mesa-mali.sh").read_text()
for required in ("panfrost,zink,virgl,llvmpipe", "panfrost,virtio,swrast", "kbase,panthor", "mesa-clc=enabled", "precomp-compiler=enabled"):
    assert required in build
assert "PREPARE_ONLY" in build
probe_build = (mesa / "build-mali-probe.sh").read_text()
assert '--sysroot="$SYSROOT"' in probe_build
assert "-Werror" in probe_build

profiles = json.loads(
    (root / "overlay/alpaquita/opt/nativecode/mesa-mali/mali-gpu-profiles.json").read_text()
)
assert set(profiles) == {
    "v4-jm", "v5-jm", "v6-jm", "v7-jm", "v9-jm",
    "v10-csf", "v11-csf", "v12-csf", "v13-csf", "v14-csf",
}
assert all(profile["panfrost_kbase"] == "UNTESTED" for profile in profiles.values())
assert all(profile["gl_default"] == "panvk-zink" for profile in profiles.values())

patches = sorted((mesa / "patches").glob("*.patch"))
assert [patch.name for patch in patches] == [
    "0001-panfrost-open-kbase-explicitly.patch",
    "0002-kbase-csf-pf2-contracts.patch",
    "0003-gallium-kbase-csf-queue.patch",
    "0005-panfrost-kbase-modern-core-query.patch",
]
assert "PANFROST_V11_XML_COMMIT=" in (mesa / "sources.lock").read_text()
assert "pan_query_core_id_range" in patches[-1].read_text()
assert 'for patch in "$SCRIPT_DIR"/patches/*.patch' in build
assert "apply --recount --check" in build
assert "sha256sum \"$SCRIPT_DIR\"/patches/*.patch" in build


def function_body(source, name):
    match = re.search(
        r"^" + re.escape(name) + r"\s*\([^;]*?\)\s*\{",
        source,
        re.M | re.S,
    )
    assert match, name
    start = source.find("{", match.start())
    depth = 0
    for pos in range(start, len(source)):
        if source[pos] == "{":
            depth += 1
        elif source[pos] == "}":
            depth -= 1
            if depth == 0:
                return source[start : pos + 1]
    raise AssertionError(f"unterminated function {name}")


prepared = pathlib.Path("/tmp/nc-pf12/mesa")
if prepared.is_dir():
    winsys = (prepared / "src/gallium/winsys/panfrost/drm/panfrost_drm_winsys.c").read_text()
    screen = (prepared / "src/gallium/drivers/panfrost/pan_screen.c").read_text()
    kbase = (prepared / "src/panfrost/lib/kmod/kbase_kmod.c").read_text()

    create = function_body(winsys, "panfrost_kbase_screen_create")
    assert 'strcmp(path, "/dev/mali0")' in create
    assert "panfrost_create_kbase_screen(path, config)" in create
    assert "panfrost_create_screen(fd" not in create
    assert "drmGetVersion" not in create

    common = function_body(screen, "panfrost_create_screen_common")
    assert "panfrost_open_device_kbase(screen, kbase_path, dev)" in common
    assert "panfrost_open_device(screen, fd, dev)" in common

    device_create = function_body(kbase, "kbase_kmod_dev_create")
    assert device_create.index("KBASE_IOCTL_VERSION_CHECK_CSF") < device_create.index("KBASE_IOCTL_SET_FLAGS")
    assert "ver.major < 11" in device_create

    group_create = function_body(kbase, "kbase_kmod_csf_group_create")
    assert "cs_queue_count > kbase_dev->csif_info.cs_slot_count" in group_create
    assert "KBASE_IOCTL_CS_QUEUE_GROUP_CREATE" in group_create

    queue_bind = function_body(kbase, "kbase_kmod_csf_queue_bind")
    for operation in ("KBASE_IOCTL_CS_QUEUE_REGISTER", "KBASE_IOCTL_CS_QUEUE_BIND", "mmap("):
        assert operation in queue_bind
    assert queue_bind.index("KBASE_IOCTL_CS_QUEUE_REGISTER") < queue_bind.index("KBASE_IOCTL_CS_QUEUE_BIND") < queue_bind.index("mmap(")

    queue_kick = function_body(kbase, "kbase_kmod_csf_queue_kick")
    assert "KBASE_IOCTL_CS_QUEUE_KICK" in queue_kick

    cqs_wait = function_body(kbase, "kbase_kmod_csf_wait_cqs64")
    assert cqs_wait.count("KBASE_IOCTL_KCPU_QUEUE_ENQUEUE") >= 2
    assert "kbase_kcpu_poll_fence" in cqs_wait

    vm_bind = function_body(kbase, "kbase_kmod_vm_bind")
    assert "PAN_KMOD_VM_MAP_AUTO_VA" in vm_bind
    assert "op->va.start = va" in vm_bind

    bo_alloc = function_body(kbase, "kbase_kmod_bo_alloc")
    assert "kbase_kmod_bo_alloc_dmabuf" in bo_alloc
    assert "KBASE_IOCTL_MEM_ALLOC_EX" in bo_alloc
    assert "KBASE_IOCTL_MEM_ALLOC" in bo_alloc
    assert "BASE_MEM_SAME_VA" in bo_alloc

    csf = (prepared / "src/gallium/drivers/panfrost/pan_csf.c").read_text()
    device_h = (prepared / "src/gallium/drivers/panfrost/pan_device.h").read_text()
    assert "panfrost_device_is_kbase" in device_h
    assert "panfrost_kbase_create_queue" in csf
    assert "kbase_kmod_csf_group_create" in csf
    assert "kbase_kmod_csf_queue_bind" in csf
    assert "kbase_kmod_csf_queue_kick" in csf
    assert "kbase_kmod_csf_wait_cqs64" in csf
    assert "cs_call(" in csf
    assert "panfrost_kbase_submit_cs" in csf
    submit = function_body(csf, "GENX(csf_submit_batch)")
    assert "panfrost_device_is_kbase" in submit
    assert submit.index("panfrost_device_is_kbase") < submit.index("panthor_kmod_vm_sync_handle")

wrapper = root / "overlay/alpaquita/usr/local/bin/fastfetch"
wrapper_text = wrapper.read_text()
assert "gpu-launch --panvk /usr/bin/fastfetch --gpu-driver-specific" in wrapper_text
assert "Device Name:" in wrapper_text
assert "vulkan_probe" in wrapper_text
assert "Mali-G615" not in wrapper_text
launch = (root / "overlay/alpaquita/usr/local/bin/gpu-launch").read_text()
assert "--panfrost" in launch
assert "--venus-zink" in launch
assert "--virgl" in launch
backend = (root / "gpu-backend.sh").read_text()
assert 'panfrost) printf \'%s\\n\' "Panfrost (Kbase Gallium)"' in backend or "Panfrost (Kbase Gallium)" in backend
assert "export GALLIUM_DRIVER=panfrost" in backend
assert "gpu_probe_panfrost" in backend
assert "DEFAULT_CANDIDATE" in backend
assert "MESA_GL_VERSION_OVERRIDE" not in function_body(backend, "gpu_probe_panfrost")
probe_source = (root / "overlay/alpaquita/opt/nativecode/panvk/mali_probe.c").read_text()
assert "KBASE_IOCTL_VERSION_CHECK_JM" in probe_source
assert 'printf("GPU_ARCH=v%u' in probe_source
assert 'printf("FRONTEND=%s' in probe_source
profile_tool = (root / "overlay/alpaquita/usr/local/bin/mali-profile").read_text()
assert "timeout=5" in profile_tool
assert 'status = profile["panfrost_kbase"] if profile else "UNSUPPORTED"' in profile_tool
with tempfile.TemporaryDirectory() as tmp:
    fake_probe = pathlib.Path(tmp) / "probe"
    fake_probe.write_text(
        "#!/bin/sh\nprintf '%s\\n' GPU_ID=0xb0000000 GPU_ARCH=v11 "
        "PRODUCT_ID=0xb003 KBASE_UAPI=11.42 FRONTEND=CSF\n"
    )
    fake_probe.chmod(0o755)
    env = os.environ | {
        "NATIVECODE_MALI_PROBE": str(fake_probe),
        "NATIVECODE_MALI_PROFILES": str(
            root / "overlay/alpaquita/opt/nativecode/mesa-mali/mali-gpu-profiles.json"
        ),
    }
    result = subprocess.run(
        [str(root / "overlay/alpaquita/usr/local/bin/mali-profile")],
        env=env, text=True, stdout=subprocess.PIPE, check=True,
    ).stdout
    assert "PROFILE=v11-csf" in result
    assert "PROFILE_STATUS=UNTESTED" in result
runtime_test = (root / "overlay/alpaquita/usr/local/bin/panfrost-kbase-test").read_text()
for test in ("PF_DEVICE", "PF_OFFSCREEN_TRIANGLE", "PF_EGL", "PF_X11_300F", "PF_SECOND_RUN", "PF_GLMARK2"):
    assert test in runtime_test
assert 'timeout "$TIMEOUT"' in runtime_test
assert "panfrost_validated=yes" not in runtime_test
assert "egl_offscreen.py" in build
rootfs_build = (root / "build_alpaquita_rootfs.sh").read_text()
assert "--mesa-bundle" in rootfs_build
assert "/opt/nativecode/mesa-mali" in rootfs_build
assert "--adreno-bundle" in rootfs_build
assert "/opt/nativecode-turnip" in rootfs_build
for required in (
    "lib/libvulkan_freedreno.so", "lib/dri/kgsl_dri.so", "lib/dri/msm_dri.so",
    "lib/libEGL_mesa.so.0", "lib/libGLX_mesa.so.0",
    "icd/freedreno_icd.aarch64.json", "source.env",
):
    assert required in rootfs_build

adreno = root / "mesa-adreno"
adreno_lock = dict(
    line.split("=", 1) for line in (adreno / "sources.lock").read_text().splitlines()
)
assert adreno_lock["LFDEV_TAG"] == "mesa-26.3.0-devel-20260824"
assert adreno_lock["LFDEV_COMMIT"] == "98f3d6229d61452cef80f8563af7c56ae599dc14"
assert adreno_lock["LFDEV_SHA256"] == "c014cf66bdbff96417ee30d34f006cf51df64ae04893d599711b0b6b73b52ccf"
adreno_fetch = (adreno / "fetch-lfdev-mesa.sh").read_text()
for required in ("sha256sum -c", "libvulkan_freedreno.so", "kgsl_dri.so", "msm_dri.so"):
    assert required in adreno_fetch
for backend_path in (
    root / "gpu-backend.sh",
    root / "overlay/alpaquita/usr/local/lib/nativecode/gpu-backend.sh",
):
    text = backend_path.read_text()
    assert "gpu_kgsl_present && return 1" in function_body(text, "gpu_probe_panvk")
    apply = function_body(text, "gpu_apply")
    assert "GALLIUM_DRIVER=freedreno" in apply
    assert "MESA_LOADER_DRIVER_OVERRIDE=kgsl" in apply
    assert "_be=turnip" in apply
    assert '_be=$(gpu_map_mode_to_backend "$1")' in function_body(text, "gpu_persist")
    unset = function_body(text, "gpu_unset_env")
    assert "unset __EGL_VENDOR_LIBRARY_DIRS" in unset
    assert '"$GPU_ADRENO_PREFIX/lib"' in unset

    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        pinned = tmp / "pinned/icd/freedreno_icd.aarch64.json"
        system = tmp / "system/freedreno_icd.aarch64.json"
        pinned.parent.mkdir(parents=True)
        system.parent.mkdir(parents=True)
        pinned.touch()
        system.touch()
        result = subprocess.run(
            ["sh", "-c", '. "$1"; gpu_find_icd freedreno', "sh", str(backend_path)],
            env=os.environ | {
                "GPU_ADRENO_PREFIX": str(tmp / "pinned"),
                "GPU_SYSTEM_ICD_DIR": str(tmp / "system"),
            },
            text=True, stdout=subprocess.PIPE, check=True,
        ).stdout.strip()
        assert result == str(pinned)

with tempfile.TemporaryDirectory() as tmp:
    tmp = pathlib.Path(tmp)
    mali = tmp / "mali0"
    mali.touch()
    launch = tmp / "gpu-launch"
    launch.write_text(
        "#!/bin/sh\ncase \"$*\" in *glxgears*|*glmark2*) exit 1;; *) exit 0;; esac\n"
    )
    launch.chmod(0o755)
    profile = tmp / "mali-profile"
    profile.write_text("#!/bin/sh\necho PROFILE_STATUS=UNTESTED\n")
    profile.chmod(0o755)
    result = subprocess.run(
        [str(root / "overlay/alpaquita/usr/local/bin/panfrost-kbase-test")],
        env=os.environ | {
            "PATH": f"{tmp}:{os.environ['PATH']}",
            "GPU_LAUNCH": str(launch),
            "MALI_DEVICE": str(mali),
            "KGSL_DEVICE": str(tmp / "no-kgsl"),
            "KGSL_LEGACY_DEVICE": str(tmp / "no-legacy-kgsl"),
        },
        text=True, stdout=subprocess.PIPE,
    )
    assert result.returncode == 2
    assert "PF_X11_300F=FAIL(1)" in result.stdout
    assert "PF_GLMARK2=FAIL(1)" in result.stdout
    assert "PF_SECOND_RUN=PASS" in result.stdout

    kgsl = tmp / "kgsl"
    kgsl.touch()
    result = subprocess.run(
        [str(root / "overlay/alpaquita/usr/local/bin/panfrost-kbase-test")],
        env=os.environ | {
            "PATH": f"{tmp}:{os.environ['PATH']}",
            "GPU_LAUNCH": str(launch),
            "MALI_DEVICE": str(mali),
            "KGSL_DEVICE": str(kgsl),
            "KGSL_LEGACY_DEVICE": str(tmp / "no-legacy-kgsl"),
        },
        text=True, stdout=subprocess.PIPE,
    )
    assert result.returncode == 1
    assert "PF_DEVICE=FAIL(KGSL device present)" in result.stdout

    result = subprocess.run(
        [str(root / "overlay/alpaquita/usr/local/bin/panfrost-kbase-test")],
        env=os.environ | {
            "PATH": f"{tmp}:{os.environ['PATH']}",
            "GPU_LAUNCH": str(launch),
            "MALI_DEVICE": str(tmp / "no-mali"),
            "KGSL_DEVICE": str(tmp / "no-kgsl"),
            "KGSL_LEGACY_DEVICE": str(tmp / "no-legacy-kgsl"),
        },
        text=True, stdout=subprocess.PIPE,
    )
    assert result.returncode == 1
    assert "PF_DEVICE=FAIL(no Mali Kbase device)" in result.stdout
print("mesa-mali tests: PASS")
