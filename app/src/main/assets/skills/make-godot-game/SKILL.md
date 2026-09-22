---
name: make-godot-game
description: A Godot project or a new small 2D/3D game requiring an engine/editor workflow. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Godot Game
  category: PROJECT
  group: Games
  engine: Godot with version-matched templates
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Godot Game

**Use for:** A Godot project or a new small 2D/3D game requiring an engine/editor workflow.

**Deliver:** Godot project, scenes/scripts/assets, and an export only for verified available targets.

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

### Install only the selected Godot route

Check `godot --version`, `command -v godot`, any `/opt/godot/current` installation and the project's engine version. Reuse a compatible installed editor. If absent, select the matching Linux ARM64/aarch64 release from [Godot's official releases](https://github.com/godotengine/godot/releases), verify its published checksum and required libc/display libraries, and install it versioned under an authorized tool directory. Expose a user-local wrapper; do not install a Linux x86 binary.

Use standard GDScript by default. Do not add the .NET SDK for a non-C# project. Add version-matched export templates only for a requested export. For Android export, reuse NativeCode's existing Android development pack/JDK and install only its required components via that native facility; for web export, use the engine's matching web templates. No separate skill needs to be loaded.

Run the editor through the live NativeCode desktop and `gpu-launch --auto`. Check `godot --help` for the installed version's rendering/export options once; do not assume an engine version or renderer flag from another release.

## Do the task

1. Inspect the existing `project.godot`, scenes, scripts and version. For a new game, create a small GDScript project with a working main scene and input actions.
2. Implement the requested complete game loop, menus/restart, keyboard/touch controls and mobile layout. Keep source assets editable and licensed.
3. Run a bounded import/script smoke (`godot --headless --path . --editor --quit`) for the installed engine. This checks import/scripts, not graphics.
4. Run the actual game through `gpu-launch --auto godot --path .` in NativeCode's existing display session. Use a supported Vulkan renderer when it passes; a tested hardware OpenGL compatibility renderer is a distinct reported route, not automatically CPU fallback.
5. Export only the requested, available target using its named preset and matching templates. Offer an actual Android APK through NativeCode when produced; do not claim export success from a source-only project.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require import/script validation, actual gameplay, selected renderer/device and an export artifact only when built. Preserve the project version. If a specific export tool or graphics route is unavailable, deliver editable project/source with that precise blocker.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/godotengine/godot
