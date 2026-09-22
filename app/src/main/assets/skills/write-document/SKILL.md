---
name: write-document
description: A report, letter, proposal, instruction document or existing DOCX to edit. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Write Document
  category: PROJECT
  group: Documents
  engine: python-docx for editable Word documents
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Write Document

**Use for:** A report, letter, proposal, instruction document or existing DOCX to edit.

**Deliver:** Native editable DOCX, generation/edit script and optional rendered preview.

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

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Editable Word package

```sh
.venv/bin/python -m pip install python-docx
.venv/bin/python -c 'from docx import Document; print("python-docx ready")'
```

Use the existing dependency lock when present. Install only the compatible native library dependencies needed by its selected wheels. Do not use page screenshots as the document body.

### Office renderer, only when the input/output needs it

Reuse `libreoffice` or `soffice` if it opens the selected format. Otherwise inspect `apk search 'libreoffice*'` in the active Alpaquita repositories and install the matching package/components and required fonts through the authorized root terminal. A filename in an archive is not proof of an ARM64-compatible office build. Do not install a generic x86 desktop tarball or modify NativeCode's display stack to make it run.

Use a unique profile for each conversion, preserve the original and write into a task output directory:

```sh
mkdir -p output
lo_profile=$(mktemp -d /tmp/nativecode-office.XXXXXX)
# Substitute the actual source file and requested output format.
libreoffice "-env:UserInstallation=file://$lo_profile" \
  --headless --convert-to pdf --outdir output input.docx
```

For page-image inspection, check `pdfinfo` and `pdftoppm`. If missing, install the native repository's `poppler-utils` package only for that preview step; run `pdftoppm -png -r 120 output/<actual-name>.pdf output/page` and inspect every produced page.

Use a bounded timeout appropriate to the document, inspect exit status and verify the new file. `--headless` conversion is not a GPU workload. For interactive inspection, use the existing NativeCode desktop and `gpu-launch --auto libreoffice <actual-file>` only when the app supports the selected route; do not start a new desktop session. If no compatible renderer is available, preserve the editable source and report that visual conversion is blocked instead of faking a rendered result.

## Do the task

1. Read the actual supplied content/template and identify page size, margins, headings, text styles, tables, headers/footers and required edits. Preserve content and organization unless the user asks for changes.
2. Use python-docx native paragraphs, runs, styles, tables, sections and images. Keep text editable and preserve existing document features; do not recreate a complex source as flattened images.
3. Set consistent spacing/typography and table widths. Use real page/section breaks and heading styles rather than blank paragraphs to force layout. Handle page numbers/fields through valid OOXML only when needed.
4. Save a new DOCX and verify its content/structure. With an available office renderer, produce a PDF/page preview and inspect every page for clipped text, spacing, broken tables, font substitutions and unwanted blank pages; fix and re-render.
5. Deliver the editable document and the requested supporting source only. Use NativeCode file/preview/notification features; python-docx generation is CPU-based, and a preview window does not prove GPU document generation.

## Check the actual result

Require actual editable text/tables, intended content/order/page setup, preserved originals and clean rendered pages when verified. Identify unsupported source features or unavailable visual rendering honestly; do not claim editability from a DOCX containing one image per page.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/python-openxml/python-docx
