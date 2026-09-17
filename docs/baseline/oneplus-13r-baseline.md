# OnePlus 13R Baseline (Phase A)

**Date**: 2026-09-16  
**Device**: OnePlus 13R (`d30a1726`), Snapdragon 8 Gen 3 (`SM8650`), Adreno 750, Kernel 6.1  
**OS**: BellSoft Alpaquita Linux Stream (glibc)  
**Container**: PRoot Distro (`alpaquita`)  
**Rootfs Archive Reference**: `tools/rootfs/out/alpaquita_rootfs.tar.zst`  
**Rootfs SHA-256**: `54f5bf6a26765d9f212751b7c55a86eb20d3fe4dc0f1f4dd5f97ee0a843cb680`  

---

## 1. System Inventory & Metrics

| Metric | Measured Baseline Value |
|:---|:---|
| **Package Count** | 243 installed APK packages |
| **Rootfs File Count** | 105,459 files (`find / -xdev`) |
| **Installed Userland Size** | `/usr`: 2.2 GB, `/opt`: 89 MB, `/etc`: 1.2 MB |
| **Idle Process PSS** | 244 MB (`dumpsys meminfo com.zenithblue.nativecode`) |
| **Active GPU Mode** | `turnip` (Freedreno GL 4.6 + Turnip Vulkan 1.4 over KGSL `/dev/kgsl-3d0`) |
| **XFCE Staging State** | Clean (core X11/GTK3 runtime ready, no stale XFCE daemon running) |

---

## 2. GPU Baseline Reports

### Vulkan 1.4 (Turnip)
- **API Version**: 1.4.359
- **Device**: `Turnip Adreno (TM) 750`
- **Driver**: Mesa 26.3.0-devel (git-`98f3d6229d`)
- **ICD**: `/opt/nativecode-turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json`
- **Result**: PASS (compute + draw + extensions verified)

### OpenGL 4.6 Core / GLES 3.2 (Freedreno)
- **Vendor**: `freedreno`
- **Renderer**: `FD750`
- **Version**: `4.6 (Core Profile) Mesa 26.3.0-devel (git-98f3d6229d)`
- **Extensions**: 226 extensions verified
- **Result**: PASS

---

## 3. Installed Package Closure (243 Packages)

```text
acl-libs-2.3.2-r1
ada-libs-3.3.0-r1
apk-tools-3.0.7-r1
at-spi2-core-2.60.6-r0
at-spi2-core-libs-2.60.6-r0
avahi-libs-0.9_rc5-r0
bash-5.3.9-r1
binutils-2.45.1-r3
brotli-libs-1.2.0-r1
bubblewrap-0.12.0-r1
build-base-0.5-r5
busybox-1.38.0-r2
busybox-binsh-1.38.0-r2
c-ares-1.34.8-r0
ca-certificates-20260611-r0
ca-certificates-bundle-20260611-r0
cairo-1.18.4-r1
cairo-gobject-1.18.4-r1
cargo-1.97.0-r0
clang22-22.1.8-r0
clang22-headers-22.1.8-r0
clang22-libs-22.1.8-r0
cmake-4.3.4-r0
coreutils-9.11-r0
coreutils-env-9.11-r0
coreutils-fmt-9.11-r0
coreutils-sha512sum-9.11-r0
cups-libs-2.4.19-r0
curl-8.22.0-r0
dbus-1.16.2-r2
dbus-libs-1.16.2-r2
dbus-x11-1.16.2-r2
dconf-0.40.0-r4
fontconfig-2.16.0-r0
freetype-2.13.3-r1
fribidi-1.0.16-r0
gdk-pixbuf-2.44.4-r0
glib-2.84.1-r0
glib-networking-2.80.1-r0
gtk+3.0-3.24.52-r0
gtk-update-icon-cache-3.24.52-r0
harfbuzz-10.4.0-r0
libepoxy-1.5.10-r2
libhandy-1.8.3-r0
libx11-1.8.13-r0
libx11-dev-1.8.13-r0
libxcb-1.17.0-r0
libxcursor-1.2.3-r0
libxext-1.3.6-r0
libxi-1.8.2-r0
libxinerama-1.1.5-r0
libxkbcommon-1.8.0-r0
libxrandr-1.5.4-r0
libxrender-1.9.11-r0
llvm20-libs-20.1.8-r0
mesa-egl-25.0.1-r0
mesa-gles-25.0.1-r0
mesa-vulkan-virtio-25.0.1-r0
ninja-1.12.1-r0
pango-1.56.2-r0
pixman-0.44.2-r1
python3-3.13.2-r0
samurai-1.2-r4
wayland-1.26.0-r0
wayland-libs-client-1.26.0-r0
wayland-libs-cursor-1.26.0-r0
wayland-libs-egl-1.26.0-r0
wayland-libs-server-1.26.0-r0
xcb-util-keysyms-0.4.1-r1
```
