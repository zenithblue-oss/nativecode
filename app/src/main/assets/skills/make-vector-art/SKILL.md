---
name: make-vector-art
description: An icon, diagram-like illustration, logo concept or scalable graphic brief. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Vector Art
  category: PROJECT
  group: Images
  engine: SVG + optional CairoSVG
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Vector Art

**Use for:** An icon, diagram-like illustration, logo concept or scalable graphic brief.

**Deliver:** Editable SVG and optional PNG/PDF renders.

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

### Inline setup

Plain SVG can be written directly with the agent's file tools: there is no mandatory program or model. For PNG/PDF export use a working `cairosvg` command. If missing, install Python/venv from the configured native packages, create a task-local environment, then run:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install CairoSVG
```

Preserve an existing environment instead of recreating it. Install the required native Cairo library from the current Alpaquita repository only if import/rendering reports it missing. Use an installed compatible browser only when preview is needed; no additional skill is a prerequisite.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

## Do the task

1. Establish dimensions/viewBox, target use and requested style. Create editable SVG shapes/paths/text with a consistent coordinate system.
2. Avoid unnecessary embedded raster images. Use actual requested text and assets, validate XML and keep external resource references local or explicitly approved.
3. Preview in the existing NativeCode browser/file viewer. For browser GPU evidence test an actual supported graphics operation; simple SVG authoring and Cairo rasterization are not necessarily GPU workloads.
4. When requested export with the installed CairoSVG CLI, for example `.venv/bin/cairosvg artwork.svg -o artwork.png`, specifying the requested dimensions through its actual supported options.
5. Reopen exports, check clipping/strokes/font substitution and deliver SVG plus requested raster/PDF files. Keep text editable unless outlines were requested.

## Check the actual result

Require valid self-contained SVG, correct viewBox/dimensions, no unintended missing resources and matching exports. Do not claim a vector image remains editable when only a PNG was delivered.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/Kozea/CairoSVG
