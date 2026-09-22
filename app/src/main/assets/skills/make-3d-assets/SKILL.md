---
name: make-3d-assets
description: A procedural model, low-poly prop, basic material or scene-asset brief. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make 3D Assets
  category: PROJECT
  group: Games
  engine: Blender Python; lightweight glTF as targeted fallback
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make 3D Assets

**Use for:** A procedural model, low-poly prop, basic material or scene-asset brief.

**Deliver:** Editable .blend when Blender runs, glTF/GLB export, dimensions and preview.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:$PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### Blender and its existing NativeCode launcher

Reuse `/home/flux/.local/bin/blender` and `/opt/blender/current/blender-launcher`. The supplied working launcher deliberately keeps `WAYLAND_DISPLAY=` (Xwayland), `--gpu-backend vulkan`, and `-noaudio`; **do not remove those settings** or replace Blender's bundled Python with the system Python.

If missing, the supplied verified build route is:

```text
https://github.com/lfdevs/blender-linux-arm64/releases/download/v5.1.0/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz
SHA-256: a4927219950566af13572e72f31b5bcb8baf87190ee86a26e2572ce7fd059793
```

Download/verify this exact artifact, require aarch64/glibc, inspect extraction paths, preserve any current install, and place the versioned tree under `/opt/blender/5.1.0-arm64` using the authorized root terminal. Resolve only missing native display/font/Vulkan libraries (`fontconfig`, `freetype`, X11/XCB/XKB, `mesa-gbm`, `vulkan-loader`, GTK/libdecor as actually required) from Alpaquita. Use the bundled launcher and expose this wrapper only when a working one does not already exist:

```sh
#!/bin/sh
export WAYLAND_DISPLAY=
exec gpu-launch --auto /opt/blender/current/blender-launcher \
  --gpu-backend vulkan -noaudio "$@"
```

This is a reference setup from the supplied records, not proof on a different device. Run `blender -b -E help` and a tiny actual EEVEE render. Do not silently choose CPU Cycles or llvmpipe to satisfy a hardware requirement. Viewport/EEVEE Vulkan does not imply that Adreno supports Cycles CUDA/OptiX/HIP devices. Keep the working launcher; no new Phosh entry is needed for this project skill.

## Do the task

1. Read the requested model dimensions, units, polygon budget, materials, origin/pivot and export format. Reuse an existing `.blend` rather than rebuilding unrelated content.
2. Write a project-local Blender Python script using `bpy`, or edit the scene, to create the requested mesh/materials. Keep a meaningful hierarchy, applied transforms where appropriate and portable texture paths.
3. Execute with Blender's own interpreter through the supplied wrapper, for example `blender -b --factory-startup --python create_asset.py` only for a new scene. Do not factory-reset an existing user scene.
4. Save editable `.blend`, export glTF/GLB using the installed Blender export API, and render a small EEVEE preview through the existing Vulkan/Xwayland/noaudio wrapper. Query the real available engine IDs instead of assuming one across releases.
5. Inspect the actual rendered image and exported mesh/materials; validate bounds, units, normals, UVs, textures and polygon count. Record GPU viewport/EEVEE evidence separately from any Cycles capability.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require nonempty editable and requested export files, correct geometry/scale, portable assets and an actual preview. The supplied setup reports a successful small EEVEE render, not a guarantee that every Cycles or GUI feature works. No silent CPU-Cycles substitution for a hardware-required render.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/blender/blender
- https://github.com/mrdoob/three.js
