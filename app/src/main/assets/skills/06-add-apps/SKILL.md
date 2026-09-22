---
name: add-apps
description: Install and integrate a Linux application in the NativeCode ARM64 guest, including latest compatible release selection, PATH setup, Phosh menu registration, GPU backend testing, and bounded recovery from launch failures.
metadata:
  short-description: Add NativeCode ARM64 apps to PATH and Phosh
---

# Add Apps

Use this skill when adding an application to the NativeCode Alpine/Alpaquita ARM64 guest and making it usable from Bash, Zsh, and the Phosh application menu. The app is not complete until its actual requested operation has been smoke-tested. A group such as “PDF tools” is not permission to install every package in that group; install the components required by the selected workflow, plus explicitly requested optional components.

## NativeCode constraints

- Confirm the host and guest before changing anything:

  ```bash
  uname -m
  ldd --version | head -2
  id
  nativecode-agent ping
  nativecode-agent telemetry
  ```

- This guest is Alpine/Alpaquita `aarch64` with glibc, rootless PRoot, no systemd, and no usable direct access to Android `/dev/dri`, `/dev/video*`, or binder nodes. Do not use `systemctl`, `modprobe`, or raw device mounts.
- Use the configured native `apk` repositories only. Do not add foreign Alpine repositories just to satisfy an application dependency.
- Use NativeCode bridges for hardware:
  - `gpu-launch --auto` selects the detected route.
  - `gpu-launch --turnip` is the Qualcomm Adreno Vulkan route.
  - `gpu-launch --panvk` is the ARM Mali/Panfrost Vulkan route when the diagnostic says PanVK is available.
  - `gpu-launch --venus` is the NativeCode virtio/Venus route when its vtest socket and ICD are available.
  - `gpu-launch --virgl-angle` and `gpu-launch --software` are diagnostics or compatibility fallbacks, not GPU acceptance results.
- Do not claim GPU acceleration because an app starts. Verify the app's own renderer, Vulkan device, WebGL renderer, video decoder, or equivalent observable path.

## Detect before installing

Record the existing state and reuse a compatible installation when possible:

```bash
command -v app-name 2>/dev/null || true
type -a app-name 2>/dev/null || true
apk info -e package-name 2>/dev/null || true
gpu-launch --diagnose
find /opt /home/flux/.local/bin -maxdepth 3 -type f -name 'app-name*' -print 2>/dev/null
```

For every candidate, check:

```bash
file /path/to/app
ldd /path/to/app 2>&1 | grep 'not found' || true
/path/to/app --version 2>&1 | head -5
```

Verify CPU architecture, glibc ABI, required system libraries, graphics API, model/runtime versions, and licensing before changing anything. A version string alone is not a smoke test. Keep existing user settings and project files.

## Find and verify the latest compatible release

Search the upstream project's official release page or API at the time of installation. Prefer, in order:

1. An official native Linux ARM64/aarch64 package.
2. An official package-manager build from the configured guest repositories.
3. A reproducible, well-maintained ARM64 build whose source, release commit, archive checksum, and build environment are visible.
4. A source build using **Install Dev Utilities** when no usable binary exists.

Do not download an x86_64 Linux archive onto this aarch64 guest. “Latest” means the newest release that is both current and compatible, not merely the newest filename. Obtain checksums/signatures from the same upstream release and verify before extraction:

```bash
mkdir -p /tmp/app-download
curl -fL --retry 3 -o /tmp/app-download/app.tar.xz 'OFFICIAL_RELEASE_URL'
printf '%s  %s\n' 'UPSTREAM_SHA256' /tmp/app-download/app.tar.xz | sha256sum -c -
file /tmp/app-download/app.tar.xz
```

For package-manager software, inspect the candidate first and install only the selected package set:

```bash
apk policy package-name
apk search -v package-name
apk add package-name
```

If the latest compatible release cannot be verified or has no usable ARM64 route, report that as blocked instead of silently installing an older or foreign binary.

## Install with a reversible layout

For portable applications, use a versioned directory and a stable symlink. Preserve an existing target by moving it to a dated quarantine path, not deleting it:

```bash
install_root=/opt/app-name
versioned="$install_root/VERSION-aarch64"
mkdir -p "$install_root"
mkdir -p /tmp/app-name-stage
tar -xf /tmp/app-download/app.tar.xz -C /tmp/app-name-stage
install -d "$versioned"
# Copy or move only the verified application tree into "$versioned".
ln -sfn "$versioned" "$install_root/current"
```

Use the application's bundled launcher when it sets `LD_LIBRARY_PATH` or other required variables. Keep a small wrapper in `/home/flux/.local/bin` when the app needs a stable GPU/display/audio configuration; do not bake machine-specific paths into project scripts.

## PATH for Bash and Zsh

Add the user executable directory once to both `/home/flux/.bashrc` and `/home/flux/.zshrc`, preserving existing PATH entries:

```sh
# NativeCode user-installed executables
case ":${PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:${PATH}" ;;
esac
```

After editing, test fresh shells rather than only the current shell:

```bash
bash -lc 'command -v app-name; app-name --version | head -3'
zsh -lc 'command -v app-name; app-name --version | head -3'
```

Use an absolute `Exec=` path in the desktop entry even when PATH is configured; Phosh may start applications with a smaller environment than an interactive shell.

## GPU backend selection and verification

Start with the diagnostic and select only hardware routes that are actually available:

```bash
gpu-launch --diagnose
```

Use this decision order:

```text
Adreno/Qualcomm + turnip=PASS  -> gpu-launch --turnip or --auto
Mali/Panfrost + panvk=PASS     -> gpu-launch --panvk or --auto
Virtio vtest + venus=PASS      -> gpu-launch --venus or --auto
No validated hardware route    -> stop as GPU-blocked, or obtain approval for software fallback
```

For each candidate route, run a bounded test with a small representative workload. Record exit status and the app-specific renderer output:

```bash
gpu-launch --turnip app-name --version
gpu-launch --panvk app-name --version
gpu-launch --auto app-name --version
```

For a GUI app, do not leave multiple test instances running. Use a short, bounded launch test, then inspect its process state and log. Stop only processes started by the test; never kill a user's unsaved interactive session. If a route reports software rendering, mark that route as failed for a GPU-required task rather than relabeling it as accelerated.

## Phosh menu integration

Create a wrapper when the app needs environment variables, then install a desktop entry under `/home/flux/.local/share/applications`. Use the desktop-entry specification's supported fields and field codes:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=App Name (NativeCode GPU)
GenericName=Application type
Comment=App Name using the validated NativeCode graphics route
Exec=/home/flux/.local/bin/app-name %U
TryExec=/home/flux/.local/bin/app-name
Icon=/opt/app-name/current/app.svg
Terminal=false
Categories=Utility;
StartupNotify=true
StartupWMClass=AppName
```

Use `%U`, `%F`, or no field code only when the app accepts that form. Validate and refresh when the utilities exist:

```bash
command -v desktop-file-validate >/dev/null && \
  desktop-file-validate /home/flux/.local/share/applications/app-name.desktop || true
command -v update-desktop-database >/dev/null && \
  update-desktop-database /home/flux/.local/share/applications || true
```

For apps whose native Wayland launch fails, test an Xwayland route by setting `WAYLAND_DISPLAY=` in that app's wrapper only. Do not impose this globally. For Blender, this is an observed compatibility setting; Blender's own manual documents the empty variable as its X11 selector. Keep app-specific audio workarounds, such as Blender's `-noaudio`, in the wrapper and explain why.

## Phosh/GPU test loop

Run this finite loop after installation. “Loop until it runs” means iterate through evidence-based fixes and compatible backends, not an unbounded battery-draining restart loop:

1. Send `nativecode-agent toast` and query `nativecode-agent telemetry`.
2. Run the CLI/version and representative headless smoke test.
3. Test `--auto`, then the validated vendor backend (`--turnip` or `--panvk`) with the app wrapper.
4. Launch the exact desktop wrapper in the live Phosh environment. Wait a bounded interval, then check whether the process remains initialized, maps a window, and reports the expected renderer.
5. If it fails, save stdout/stderr, the app crash report, `WAYLAND_DEBUG=1` or app logging output, `gpu-launch --diagnose`, and relevant Phosh log lines. Classify the failure as missing library, display protocol, audio/session initialization, GPU driver, incompatible binary, or application bug.
6. Apply one narrow change, retest the same smoke test, and avoid stacking unverified environment variables. Try the next compatible backend only when the diagnostic supports it.
7. Stop after the tested route works or after all validated routes are exhausted. Do not leave orphaned GUI processes; notify the user if an old process must be closed manually because it may contain unsaved work.
8. Send `nativecode-agent notify` with the final route, version, smoke result, and any known limitation.

Useful diagnostics:

```bash
gpu-launch --diagnose
nativecode-agent telemetry
ps -o pid,ppid,stat,etime,args | grep app-name
find /tmp /home/flux -maxdepth 4 -type f -iname '*app-name*' -print 2>/dev/null
```

For a suspected startup hang, use `strace` only for a bounded diagnostic and clean up the test process afterward. A Phosh startup timeout with a live process is not the same as a crash; distinguish it from a non-zero exit and a crash backtrace.

## Completion record

Record these facts in the app-specific skill or handoff:

- exact release, architecture, source URL, checksum/signature result, and install path;
- system packages and linked dependencies installed;
- Bash and Zsh command resolution;
- desktop-entry path and wrapper environment;
- selected GPU backend and app-reported renderer/device;
- CLI/headless smoke result and Phosh GUI result;
- any disabled feature, such as audio, and the command needed to test it separately;
- unresolved limitations and the safe recovery path.

## References

- [Blender Linux windowing environment](https://docs.blender.org/manual/en/latest/getting_started/installing/linux_windowing_environment.html) — Wayland diagnostics and the app-specific `WAYLAND_DISPLAY=` X11 selector.
- [Freedesktop Desktop Entry Specification](https://specifications.freedesktop.org/desktop-entry/latest-single/) — `Exec`, `TryExec`, field codes, and Phosh menu entries.
- [Mesa Freedreno and Turnip documentation](https://docs.mesa3d.org/drivers/freedreno.html) — Adreno/Turnip scope and capabilities.
- [Mesa Panfrost/PanVK project](https://panfrost.freedesktop.org/) — PanVK scope and current compatibility limitations.
- [Mesa Venus documentation](https://docs.mesa3d.org/drivers/venus.html) — virtio/Venus requirements.

