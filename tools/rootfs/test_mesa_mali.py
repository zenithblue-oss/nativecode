#!/usr/bin/env python3
import hashlib
import json
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
for required in ("panfrost,zink,virgl,llvmpipe", "panfrost,virtio,swrast", "kbase,panthor", "mesa-clc=system", "precomp-compiler=system"):
    assert required in build
assert "PREPARE_ONLY" in build

patches = sorted((mesa / "patches").glob("*.patch"))
assert [patch.name for patch in patches] == [
    "0001-panfrost-open-kbase-explicitly.patch",
    "0002-kbase-csf-pf2-contracts.patch",
    "0003-gallium-kbase-csf-queue.patch",
]
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
    assert "cs_queue_count > kbase_dev->csif_info.max_cs" in group_create
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
backend = (root / "gpu-backend.sh").read_text()
assert 'panfrost) printf \'%s\\n\' "Panfrost (Kbase Gallium)"' in backend or "Panfrost (Kbase Gallium)" in backend
assert "export GALLIUM_DRIVER=panfrost" in backend
rootfs_build = (root / "build_alpaquita_rootfs.sh").read_text()
assert "--mesa-bundle" in rootfs_build
assert "/opt/nativecode/mesa-mali" in rootfs_build
print("mesa-mali tests: PASS")
