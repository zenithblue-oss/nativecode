---
name: install-chromium
description: Install and verify an ARM64-compatible Chromium-family browser for previews, browser testing, and rendering in the NativeCode Linux guest.
metadata:
  short-description: Install Chromium-compatible browser tooling
---

# Install Chromium

Use this skill when a task needs a local Chromium-family browser for headless rendering, previews, browser tests, or interactive display use. Install one compatible browser route; do not install every browser package or add an incompatible x86_64 archive to PATH.

## Environment and prerequisites

- NativeCode guest is expected to be ARM64/aarch64 and glibc-based. Confirm with `uname -m` and `ldd --version`.
- Use the configured native Alpaquita repositories for system libraries. Do not add foreign Alpine repositories.
- Install the font families required by the content. At minimum, verify a working sans-serif font with `fc-match`; use **Install Fonts** when language coverage or reproducible rendering requires more than the base fonts.
- For interactive windows, use NativeCode display integration and `gpu-launch --auto`. Headless rendering does not prove display or GPU acceleration.
- Do not use `systemctl`; this guest does not run systemd.

## Detect an existing installation

Check all likely command names and SDK locations before changing anything:

```bash
command -v chromium chromium-browser google-chrome google-chrome-stable 2>/dev/null || true
for p in /opt/google/chrome/chrome /opt/chromium/chrome /opt/android-sdk/chromium/chrome; do
  test -x "$p" && { file "$p"; "$p" --version; ldd "$p" | grep 'not found' || true; }
done
fc-match sans-serif
```

Accept an existing browser only if its executable is `aarch64`, its dynamic loader matches the guest libc, its shared libraries resolve, and `--version` succeeds. If **Install Browser Tests** already has a proven browser, reuse that exact browser instead of creating a second route.

## Obtain the compatible browser

Prefer a current package from a configured native repository when one exists. On this Alpaquita guest there was no Chromium package in the configured repositories, and the official Chromium Linux snapshot route did not expose an ARM64 build. The current official Google ARM64 Linux package is therefore the compatible Chromium-family route:

```bash
curl -fL --retry 3 -o /tmp/google-chrome-stable_current_arm64.deb \
  https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
sha256sum /tmp/google-chrome-stable_current_arm64.deb
```

The package is a Debian archive, but a package manager is not required for this PRoot guest. Inspect and extract it without executing maintainer scripts:

```bash
mkdir -p /tmp/chrome-deb/{deb,root}
ar x /tmp/google-chrome-stable_current_arm64.deb --output /tmp/chrome-deb/deb
tar -xf /tmp/chrome-deb/deb/data.tar.xz -C /tmp/chrome-deb/root
file /tmp/chrome-deb/root/opt/google/chrome/chrome
```

Read the package control metadata and verify the architecture before installation:

```bash
tar -xOf /tmp/chrome-deb/deb/control.tar.xz ./control
```

Install runtime libraries using the native package manager. Package names can differ between repositories; resolve them with `apk search` and install only the dependencies required by the selected browser:

```bash
apk add nspr nss ca-certificates font-liberation alsa-lib \
  at-spi2-core at-spi2-core-libs libatk-1.0 libatk-bridge-2.0 \
  cairo cups-libs curl dbus expat mesa-gbm glib gtk+3.0 pango \
  eudev libva vulkan-loader libx11 libxcb libxcomposite libxdamage \
  libxext libxfixes libxkbcommon libxrandr wget
```

If the package or its browser binary is not `aarch64`, stop and select a native build or a compatible repository package. Do not install the official x86_64 Linux archive on this host.

## Install and expose commands

Preserve an existing installation before replacing it, then install the extracted browser and stable user-facing aliases:

```bash
test ! -e /opt/google/chrome || mv /opt/google/chrome /tmp/google-chrome-existing-$(date +%Y%m%d)
mkdir -p /opt/google
cp -a /tmp/chrome-deb/root/opt/google/chrome /opt/google/chrome
mkdir -p /home/flux/.local/bin
chmod 755 /opt/google/chrome/chrome /opt/google/chrome/google-chrome
```

Make the normal command use NativeCode’s hardware path and reject GPU software rasterization:

```sh
#!/bin/sh
set -- --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan \
  --enable-features=VaapiVideoDecoder,VaapiIgnoreDriverChecks "$@"
if [ -n "${WAYLAND_DISPLAY:-}" ] && [ -n "${XDG_RUNTIME_DIR:-}" ] && \
   [ -S "${XDG_RUNTIME_DIR}/${WAYLAND_DISPLAY}" ]; then
  set -- --ozone-platform=wayland --enable-features=UseOzonePlatform "$@"
fi
exec gpu-launch --auto /opt/google/chrome/google-chrome "$@"
```

Save that wrapper as `/home/flux/.local/bin/chromium`, make it executable, and point `chromium-browser` and `google-chrome` at the wrapper. The wrapper must not silently add `--disable-gpu` or select SwiftShader.

```bash
chmod 755 /home/flux/.local/bin/chromium
ln -sfn /home/flux/.local/bin/chromium /home/flux/.local/bin/chromium-browser
ln -sfn /home/flux/.local/bin/chromium /home/flux/.local/bin/google-chrome
```

In this rootless PRoot guest, Chrome’s setuid sandbox cannot start because `chrome-sandbox` is not root-owned/setuid. The launcher therefore uses `--no-sandbox` for every NativeCode launch; this is a guest constraint, not a production security recommendation outside this environment. It also selects Wayland/Ozone only when Phosh supplies a live `WAYLAND_DISPLAY` socket; forcing Wayland in a shell with a stale socket can make Chrome exit during platform initialization.

Add the user bin directory once to both `/home/flux/.bashrc` and `/home/flux/.zshrc`; preserve existing PATH entries and make the block idempotent:

```sh
# NativeCode browser tools
case ":\${PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:\${PATH}" ;;
esac
```

Ensure `/home/flux/.bash_profile` sources `.bashrc` for login Bash shells. Start a new shell or source the relevant file, then confirm `command -v chromium` points to `/home/flux/.local/bin/chromium`.

## Compatibility and smoke tests

Run all of these after installation:

```bash
file /opt/google/chrome/chrome
ldd /opt/google/chrome/chrome | grep 'not found' && exit 1 || true
/opt/google/chrome/chrome --version
fc-match sans-serif
test -r /opt/google/chrome/resources.pak
test -r /opt/google/chrome/icudtl.dat
```

For a non-interactive renderer smoke test, use a throwaway profile and the NativeCode-safe sandbox setting:

```bash
rm -rf /tmp/chromium-smoke-profile
mkdir -p /tmp/chromium-smoke-profile
/opt/google/chrome/chrome \
  --headless=new --no-sandbox --disable-dev-shm-usage --disable-gpu \
  --user-data-dir=/tmp/chromium-smoke-profile \
  --dump-dom 'data:text/html,<h1>Chromium smoke</h1>' | grep -F 'Chromium smoke'
```

The wrapper uses `--no-sandbox` because this rootless PRoot guest cannot run Chrome’s setuid sandbox. Do not treat that flag as a security recommendation for an ordinary desktop installation. DBus warnings are expected when no DBus session is available; a successful DOM result is the acceptance signal.

For interactive validation, start the browser through NativeCode’s display/graphics bridge and test the actual preview window. Use the GPU-only flags so a missing hardware path is visible instead of silently falling back to a software rasterizer:

```bash
gpu-launch --diagnose
gpu-launch --auto chromium \
  --enable-gpu --disable-software-rasterizer --ignore-gpu-blocklist \
  --use-gl=angle --use-angle=vulkan \
  --no-first-run --user-data-dir=/tmp/chromium-interactive-profile
```

For an automated WebGL acceptance test, load a page that reports `WEBGL_debug_renderer_info` and reject `SwiftShader`, `llvmpipe`, `Software`, or an absent renderer. The expected NativeCode result is an ANGLE Vulkan renderer containing `Turnip` and the Qualcomm Adreno device. `gpu-launch --diagnose` must report `turnip=PASS` and `auto=turnip`.

Test video separately. A browser playback test must prove that the `<video>` element reaches `playing`, but `navigator.mediaCapabilities.decodingInfo()` should also report `powerEfficient:true` when browser hardware decoding is required. `powerEfficient:false` means playback succeeded through a software or otherwise non-power-efficient browser decoder; do not report that as hardware video decoding. In this NativeCode guest, use the host bridge for a hardware video acceptance test:

```bash
nativecode-media capabilities
nativecode-media benchmark_file --path /path/to/test.mp4 --mode surface
```

Accept the bridge result only when the selected codec reports `is_hardware:true`, `software_only:false`, decoded frames are nonzero, and the measured rate is sensible. NativeCode MediaCodec hardware decoding does not automatically make Chrome’s Linux HTML5 decoder hardware-backed; report those two results separately.

Do not remove `--disable-software-rasterizer` to hide a failed GPU test. If the Turnip/Zink/OpenGL path is unavailable, stop and report the graphics limitation rather than accepting CPU fallback.

## Phosh application menu

Install `/home/flux/.local/share/applications/chromium-turnip.desktop` so Phosh launches the GPU-enforcing wrapper:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=Chromium (Turnip GPU)
GenericName=Web Browser
Comment=Chromium browser with NativeCode Turnip/Vulkan acceleration
Exec=/home/flux/.local/bin/chromium %U
TryExec=/home/flux/.local/bin/chromium
Icon=/opt/google/chrome/product_logo_128.png
Terminal=false
Categories=Network;WebBrowser;
MimeType=text/html;text/xml;application/xhtml+xml;x-scheme-handler/http;x-scheme-handler/https;
StartupNotify=true
StartupWMClass=Google-chrome
X-GNOME-UsesNotifications=true
```

Refresh the desktop database when `update-desktop-database` is available. Close existing Chromium windows before retesting from Phosh because Chrome reuses an already-running browser process.

## Installed verification record

The NativeCode ARM64 installation completed with:

- Official package: `google-chrome-stable_current_arm64.deb`
- Browser version: `Google Chrome 153.0.8010.52`
- Download SHA-256: `e5a1d646af594568941e3a61e7b8eddc693f769b7f166874c71f72c9cb806165`
- Binary: ARM aarch64, glibc loader `/lib/ld-linux-aarch64.so.1`
- Shared libraries: resolved
- Fonts: DejaVu Sans and Liberation fonts available
- Smoke test: headless DOM render passed
- GPU probe: NativeCode Turnip backend available (`turnip=PASS`, `auto=turnip`)
- WebGL: hardware-backed ANGLE Vulkan renderer reported `Turnip Adreno 750`; no SwiftShader or llvmpipe fallback
- Browser video: H.264 playback reached `playing`, but `powerEfficient:false`; browser hardware decode is not proven in the Linux guest
- NativeCode video bridge: H.264 MediaCodec reported `is_hardware:true`, `software_only:false`, and 150 decoded frames
- VA-API runtime: `libva` installed; Chrome browser hardware decode remains unproven because the PRoot guest exposes no `/dev/dri` VA-API device
- Phosh menu: `/home/flux/.local/share/applications/chromium-turnip.desktop`

## Recovery

If the installed browser is incompatible, remove only the aliases and move `/opt/google/chrome` to a dated quarantine directory. Restore the previous directory from `/tmp` if one was saved. Do not delete user browser profiles unless the user explicitly requests that data removal.

## References

- [Chromium download guidance](https://www.chromium.org/getting-involved/download-chromium/)
- [Google’s ARM64 Linux Chrome announcement](https://blog.google/chromium/bringing-chrome-to-arm64-linux-devices/)
