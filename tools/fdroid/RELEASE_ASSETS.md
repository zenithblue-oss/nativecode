# F-Droid runtime release assets

The fdroid flavor downloads these files from one GitHub release (`ReleasePins.BASE_URL`,
currently `https://github.com/ZenithBlue-Dev/nativecode-runtime/releases/download/runtime-1.8.0`; repo name still TBD).
Each file is accepted only if its size and SHA-256 match `app/src/fdroid/java/com/zenithblue/nativecode/onboarding/ReleasePins.kt`.
Never overwrite a published asset: new bytes = new file name + new pin.

Upload every file below, byte for byte, under exactly this name. (Nothing has been uploaded yet.)

| asset name | bytes | sha256 | source |
|---|---:|---|---|
| `alpaquita_rootfs.tar.zst` | 1,356,467,533 | `edeec26af1c7d7dedd490c99473733ad90b8fff16aab27c63c6f43d467ff0698` | existing Play asset-pack rootfs |
| `nativecode_update.tgz` | 316,974,152 | `fa9f25582d523fa36c3fc93e260aabafa1762842a6dd65bfa71ee093de778a01` | existing runtime update pack |
| `nativecode_android_dev.tar.xz` | 69,451,708 | `f4d4aad5b8f78cd68b0254718626576c547ca187cb5c682faaa8b2e0fbc10a2d` | existing Android SDK pack |
| `panvk-26.3.1-libvulkan_panfrost_kbase.so` | 33,917,432 | `063bcf0cacd6605458f01530e7fc4f4de43cda7d7a995d9518e1a6e38f5d0db6` | see PanVK below |
| `turnip-27.0.0-r2-libvulkan_freedreno.so` | 16,912,208 | `33b4acfbc04205d34e57178301b32f06df1539568fcb1e9b37688a3e777163ab` | see Turnip below |

## PanVK (Mali), Mesa PanVK 26.3.1

- Source release: https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v26.3.1
  (Mesa `5a07217f`, patched tree `795ca7ff3e8`, patch level p0175, build 122i, glibc aarch64, stripped, Mali v6-v14).
- Upload the file `libvulkan_panfrost-glibc-aarch64.so` of that release (published sha256 matches the table) renamed to
  `panvk-26.3.1-libvulkan_panfrost_kbase.so`. The same bytes ship in the Play flavor as
  `app/src/play/assets/panvk-26.3.1/libvulkan_panfrost_kbase.so`.
- The upstream release notes call the glibc build "not device tested". Verified here on a Poco X6 Pro (Mali-G615 MC6, kbase CSF):
  vulkan_probe lists the device (Vulkan 1.4.363, driver 0x6802063), compute acceptance 10/10, Phosh desktop starts.
- License: Mesa MIT (+ patches under Mesa's license); vendored kbase UAPI headers GPL-2.0 WITH Linux-syscall-note. Free software, OK for F-Droid.
  Include the NOTICE of the source repo when redistributing.

## Turnip (Adreno), LFDev Mesa 27.0.0-devel-20261025-r2

- Source release: https://github.com/lfdevs/mesa-for-android-container/releases/tag/mesa-27.0.0-devel-20261025-r2
  ("Latest" on 2026-11-27, commit `b84f02a45149`, patched Turnip; newer than the previous bundle 26.3.0-devel-20260824).
  The `turnip-27.0.0-devel-20261025` release is the unpatched Turnip-only variant and is not used.
- Asset taken from it: `mesa-for-android-container_27.0.0-devel-20261025-r2_debian_trixie_arm64.tar.gz`
  (12,234,186 bytes, sha256 `79e1aaf5a49e204fae68a5c87c76248701ba723f4349bda87d310f456c5705cf`).
  Extract `usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so` and upload it renamed to `turnip-27.0.0-r2-libvulkan_freedreno.so`.
  The Debian trixie (glibc) build is used because the Alpine builds are musl and the guest is Alpaquita glibc; the .so needs
  GLIBC up to 2.38 and the same libraries the previous native build needed plus `libwayland-client.so.0` (in the base rootfs).
- Only the Vulkan driver and its ICD json (`/opt/nativecode-turnip/icd/freedreno_icd.aarch64.json`, library path unchanged) are replaced in the guest;
  the Mesa GL libraries from the 26.3 bundle stay. The in-app card shows on Adreno (KGSL) devices only.
- NOT device-tested: no Adreno device was available. The install script is covered by a JVM test; the driver itself needs a run on an Adreno phone
  (`vulkaninfo`/Zink) before release.
- License: Mesa MIT. Free software, OK for F-Droid.

## Debug override

Debug builds read `files/debug_pack_base_url` (first line, e.g. `http://127.0.0.1:8099`) instead of `BASE_URL`; the same file names and pins apply.
