---
name: make-thumbnail
description: A video/article/game topic, supplied artwork, title and target platform/aspect ratio. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Thumbnail
  category: PROJECT
  group: Images
  engine: Pillow + editable SVG layout
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Thumbnail

**Use for:** A video/article/game topic, supplied artwork, title and target platform/aspect ratio.

**Deliver:** Final PNG/JPEG, editable layout/script, and small-size readability preview.

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

### Python environment, only if needed

Reuse a compatible project environment and its lockfile. Check `python3 --version`, `python3 -m venv --help` and `uv --version` when uv exists. If Python/pip/venv is missing, inspect the configured Alpaquita packages, then install only the missing `python3`, `py3-pip` and `py3-virtualenv` components in the authorized root terminal. Do not replace the system interpreter or use global `pip` installs.

```sh
# Run in the task project as flux; preserve an existing .venv.
if [ ! -x .venv/bin/python ]; then python3 -m venv .venv; fi
.venv/bin/python -m pip --version
```

Use `.venv/bin/python -m pip` for the package commands below. For an existing uv project, use its lockfile and `UV_LINK_MODE=copy uv sync --frozen` rather than changing package versions with pip. NativeCode PRoot may need copy mode instead of cross-filesystem hardlinks.

Select an interpreter supported by the chosen packages and ARM64 wheels; do not force the newest system Python on a package that does not support it. The supplied environment includes uv at `/home/flux/.local/bin/uv`. When needed and authorized, use `uv python install 3.11` and `uv venv --python 3.11 <new-task-venv>`; do not overwrite an unrelated environment. If uv itself is missing, its [official installation guide](https://docs.astral.sh/uv/getting-started/installation/) supplies the matching `aarch64-unknown-linux-gnu` binary; verify the release before activation. No required step calls another skill.

### Image packages

For the requested Pillow operations, use the task environment:

```sh
.venv/bin/python -m pip install Pillow
.venv/bin/python -c 'from PIL import Image; print(Image.__version__)'
```

Install ImageMagick only for an operation that needs its CLI (`magick`); check the configured native `imagemagick` package first. For SVG-to-PNG/PDF conversion, install `CairoSVG` into the task environment and its required native Cairo libraries through the configured repository. Do not install both optional tools simply for a crop/resize task. Preserve color profiles and transparency according to the requested output.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

## Do the task

1. Read the intended platform/aspect ratio, title, imagery and desired style. Preserve a supplied brand and use the user's actual images; do not invent identity/details in them.
2. Create a clean editable layout with strong hierarchy, limited text, readable type and a clear focal subject. Use Pillow plus SVG where useful; rasterize only for the requested final image.
3. Install CairoSVG only when SVG export requires it. Resolve real font files/families before positioning text; measure text bounds instead of trusting estimated character counts.
4. Export the requested PNG/JPEG and a small-size preview. Keep the layout/script and source assets separate from the final raster.
5. Inspect at actual feed size, check crop safety and text contrast, and adjust based on the real preview. Use existing NativeCode preview features; no neural model or paid provider is a prerequisite.

## Check the actual result

Require correct pixel dimensions, legible non-clipped text, intended crop, source asset provenance and editable layout. Do not promise audience/reach performance from a thumbnail design.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/python-pillow/Pillow
- https://github.com/Kozea/CairoSVG
