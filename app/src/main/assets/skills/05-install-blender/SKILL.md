---
name: install-blender
description: Install and verify a native ARM64 Blender build with bundled scripting support, Vulkan graphics, rendering smoke tests, PATH setup, and a Phosh launcher.
metadata:
  short-description: Install Blender for NativeCode ARM64
---

# Install Blender

Use this skill when a task needs Blender for 3D editing, Python automation, EEVEE rendering, asset conversion, or background rendering. Install one architecture-compatible Blender build; do not place the official Linux x86_64 archive on an ARM64 system.

## Environment and prerequisites

- Confirm `uname -m` is `aarch64` and the guest uses glibc with `ldd --version`.
- Use the configured native Alpaquita repositories only. Do not add foreign Alpine repositories.
- Blender needs a compatible graphics path for editor and EEVEE viewport use. NativeCode provides Turnip Vulkan through `gpu-launch --auto`; `gpu-launch --diagnose` must report `turnip=PASS` for the installed GPU route.
- Keep fonts available through **Install Fonts** when text, UI, or reproducible scene output needs language coverage beyond the base fonts.
- Blender's bundled Python is the supported scripting environment. Do not replace it with the system Python for Blender's internal modules.
- Cycles GPU compute is separate from viewport/EEVEE Vulkan support. Adreno/Turnip is not CUDA, OptiX, HIP, OneAPI, or Metal; do not claim Cycles GPU rendering unless Blender exposes and verifies a supported Cycles device.
- Do not use `systemctl`; this guest has no systemd.

## Detect an existing installation

Check commands, architecture, dynamic libraries, version, and graphics support before changing anything:

```bash
command -v blender blender-launcher 2>/dev/null || true
for p in /opt/blender/current/blender /opt/blender/*/blender /home/flux/.local/bin/blender; do
  test -x "$p" && {
    file "$p"
    ldd "$p" | grep 'not found' || true
    "$p" --version | head -3
  }
done
gpu-launch --diagnose
```

Reuse an existing build only when it is ARM64/aarch64, its glibc loader and shared libraries resolve, its version is acceptable for the project, and the selected graphics backend starts. Test the actual operation needed; a version command alone does not prove that the editor or renderer works.

## Obtain a compatible build

The official Blender download page currently publishes Linux x86_64, not Linux ARM64. The current native ARM64 binary used here is the latest available release from the `lfdevs/blender-linux-arm64` project:

- Release: Blender 5.1.0
- Build date: 2026-03-25
- Commit: `ae6d847d66fa`
- Archive: `blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz`
- SHA-256: `a4927219950566af13572e72f31b5bcb8baf87190ee86a26e2572ce7fd059793`

Download and verify it before extraction:

```bash
mkdir -p /tmp/blender-arm64
curl -fL --retry 3 -o /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz \
  https://github.com/lfdevs/blender-linux-arm64/releases/download/v5.1.0/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz
printf '%s  %s\n' \
  a4927219950566af13572e72f31b5bcb8baf87190ee86a26e2572ce7fd059793 \
  /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz | sha256sum -c -
tar -xzf /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz \
  -C /tmp/blender-arm64
file /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64/blender
```

Do not substitute the official `linux-x64` archive: it cannot execute on this aarch64 guest. Recheck the ARM64 release page for a newer native build before future upgrades.

## Install runtime libraries and Blender

The portable archive bundles Blender's large application libraries, but the guest still needs the system display, font, Vulkan, and X11 compatibility libraries. Resolve package names with `apk search` if the repository changes:

```bash
apk add fontconfig freetype libx11 libxrender libxfixes libxi \
  libxkbcommon libxcb mesa-gbm vulkan-loader gtk+3.0 \
  libdecor libdecor-gtk
```

Install the verified archive under a versioned directory and maintain a stable symlink:

```bash
mkdir -p /opt/blender
test ! -e /opt/blender/current || mv /opt/blender/current /tmp/blender-existing-$(date +%Y%m%d)
mv /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64 /opt/blender/5.1.0-arm64
ln -sfn /opt/blender/5.1.0-arm64 /opt/blender/current
chmod 755 /opt/blender/current/blender /opt/blender/current/blender-launcher
```

Use the bundled launcher, not the binary directly; it sets the archive's library path. Verify:

```bash
/opt/blender/current/blender-launcher --version
ldd /opt/blender/current/blender | grep 'not found' && exit 1 || true
```

## PATH, Phosh display selection, and GPU launcher

Add `/home/flux/.local/bin` once to both `/home/flux/.bashrc` and `/home/flux/.zshrc`, preserving existing entries:

```sh
# NativeCode user-installed executables
case ":\${PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:\${PATH}" ;;
esac
```

Create `/home/flux/.local/bin/blender` with the Vulkan backend forced through NativeCode Turnip:

```sh
#!/bin/sh

# Blender's Vulkan Wayland path is not usable in this NativeCode Phosh session:
# the compositor is scaled 3x and the ARM64 build can hang before mapping its
# window. Use the session's Xwayland display while retaining Vulkan/Turnip GPU
# rendering. An empty value is Blender's documented X11 selector.
# PulseAudio initialization stalls this PRoot/Phosh launch before the window
# appears, so the menu launcher disables Blender audio by default.
export WAYLAND_DISPLAY=
exec gpu-launch --auto /opt/blender/current/blender-launcher \
  --gpu-backend vulkan -noaudio "$@"
```

Make it executable and confirm Bash and Zsh both resolve it:

```bash
chmod 755 /home/flux/.local/bin/blender
bash -lc 'command -v blender; blender --version | head -3'
zsh -lc 'command -v blender; blender --version | head -3'
```

The Vulkan flag is required for this ARM64 build on the current Mesa stack: its default OpenGL backend crashed in the tested background render, while `--gpu-backend vulkan` rendered successfully through Turnip. Keep the failure visible; do not silently switch to software rendering.

For Phosh, keep `WAYLAND_DISPLAY` empty in the wrapper. Native Wayland startup was tested against the live Phoc session and did not map a Blender window; the Xwayland-selected launch avoided that path. This changes only the window protocol: Vulkan rendering still uses the NativeCode Turnip ICD. Do not replace it with `--software` or llvmpipe.

Also keep `-noaudio` in the Phosh wrapper. A traced GUI launch reached Xwayland successfully but stalled during PulseAudio device initialization; the `-noaudio` launch continued processing X11 events. This is a GUI-startup compatibility setting, not a renderer fallback. If a workflow specifically needs Blender audio, test `-setaudio PulseAudio` from a terminal separately rather than removing the working menu default.

## Smoke tests

First verify Blender's bundled Python and engine list:

```bash
blender -b --factory-startup --python-expr \
  'import bpy; print("BLENDER_PYTHON_SMOKE", bpy.app.version_string, bpy.context.scene.render.engine)'
blender -b -E help
```

Run a small EEVEE render through the wrapper and require a non-empty PNG:

```bash
blender -b --factory-startup \
  --python-expr 'import bpy, math; s=bpy.context.scene; s.render.engine="BLENDER_EEVEE"; s.render.resolution_x=64; s.render.resolution_y=64; s.render.resolution_percentage=100; bpy.ops.mesh.primitive_cube_add(); bpy.ops.object.camera_add(location=(4,-4,3)); c=bpy.context.object; s.camera=c; c.rotation_euler=(math.radians(67),0,math.radians(43)); bpy.ops.object.light_add(type="POINT", location=(2,-2,4));' \
  -o /tmp/blender-smoke-render -F PNG -f 1
test -s /tmp/blender-smoke-render0001.png
file /tmp/blender-smoke-render0001.png
```

For GUI/editor validation, start the wrapper from the Phosh menu or a live Wayland session. Confirm that `gpu-launch --diagnose` still selects Turnip, that the Blender editor opens through Xwayland without a PulseAudio startup stall, and that the process environment has an empty `WAYLAND_DISPLAY` plus the expected Turnip/Vulkan variables. A successful background render proves the Vulkan render path, not every interactive display feature.

## Phosh application menu

Install `/home/flux/.local/share/applications/blender-turnip.desktop`:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=Blender (Turnip Vulkan/Xwayland)
GenericName=3D Creation Suite
Comment=Blender using NativeCode Turnip Vulkan through Phosh Xwayland
Exec=/home/flux/.local/bin/blender %U
TryExec=/home/flux/.local/bin/blender
Icon=/opt/blender/current/blender.svg
Terminal=false
Categories=Graphics;3DGraphics;
MimeType=application/x-blender;
StartupNotify=true
StartupWMClass=Blender
X-GNOME-UsesNotifications=true
```

Refresh the desktop database when `update-desktop-database` is available. Close old Blender processes before retesting after launcher changes.

## Installed verification record

This NativeCode guest has:

- Native ARM64 build installed at `/opt/blender/current`
- Blender 5.1.0, build hash `ae6d847d66fa`
- Archive checksum verified
- Shared-library dependencies resolved
- `libdecor` and `libdecor-gtk` installed for window initialization
- Bundled Python smoke test passed
- EEVEE engine listing passed
- 64×64 EEVEE render passed through `gpu-launch --auto` with `--gpu-backend vulkan`
- NativeCode GPU diagnostics: Turnip PASS and auto=turnip
- Default OpenGL background render: failed with a Mesa/libgallium segmentation fault; use the Vulkan launcher
- Native Wayland GUI launch: failed to map a window in the live Phoc session; use `WAYLAND_DISPLAY=` to select Xwayland
- PulseAudio GUI initialization: stalled before window startup; the Phosh launcher uses `-noaudio`; the final GUI smoke reached a 40-thread initialized Blender process through Xwayland
- Phosh entry: `/home/flux/.local/share/applications/blender-turnip.desktop` (Xwayland window path, no-audio startup, Vulkan/Turnip renderer)

## Recovery

If the build is incompatible, remove only the Blender aliases and move `/opt/blender/current` to a dated quarantine path. Restore the previous version from `/tmp` if one was saved. Do not delete Blender user settings or project files unless explicitly requested.

## References

- [Official Blender downloads](https://www.blender.org/download/)
- [Official Blender 5.2 release archive](https://download.blender.org/release/Blender5.2/)
- [ARM64 Blender release](https://github.com/lfdevs/blender-linux-arm64/releases)
