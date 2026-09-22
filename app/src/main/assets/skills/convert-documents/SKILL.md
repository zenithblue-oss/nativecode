---
name: convert-documents
description: A precise source/target format pair such as Markdown→DOCX or DOCX/PPTX→PDF. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Convert Documents
  category: PROJECT
  group: Documents
  engine: Pandoc for structured markup; LibreOffice for office rendering
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Convert Documents

**Use for:** A precise source/target format pair such as Markdown→DOCX or DOCX/PPTX→PDF.

**Deliver:** Converted file, source preserved, conversion log and fidelity report.

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

### Select one conversion route

For Markdown/HTML/structured document conversion reuse `pandoc --version`; if absent, install the configured native package or a checksum-verified compatible ARM64 release from [Pandoc](https://github.com/jgm/pandoc/releases). Do not install every office/PDF utility for a plain Markdown-to-HTML conversion.

For DOCX/PPTX-to-PDF use the inline office renderer below. For page previews check `pdfinfo`/`pdftoppm` and add native `poppler-utils` only if required. For a text-extraction-only request use a format reader/MarkItDown with its specific extras instead of promising layout-preserving conversion. Spreadsheet conversions must preserve formulas/types and require an explicitly selected compatible route; do not silently round-trip an XLSX through office conversion.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

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

1. Identify the actual source format, target format and what must survive: editability, visual layout, links, tables, images, formulas, comments or tracked changes. Preserve originals.
2. Choose the smallest supported route: Pandoc for structured markup; office rendering for office-to-PDF; extraction tools only for content extraction. State irreversible losses before conversion.
3. Run a task-local conversion with explicit source/destination paths, for example `pandoc input.md -o output.docx` for that format pair. Use a unique office profile for office rendering and capture logs/exit codes.
4. Reopen the output with a suitable parser and inspect rendered pages where layout matters. Compare headings, text, tables, page/slide count, images and any required links or fields.
5. Deliver only the requested final formats plus a short fidelity report. Use NativeCode previews/notifications; conversion does not gain GPU acceleration by wrapping a CPU converter.

## Check the actual result

Require a file of the real target format, usable content, preserved essentials and explicit editability/fidelity limits. Do not change the extension to fake conversion or claim that extracted Markdown preserves page layout.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/jgm/pandoc
- https://github.com/LibreOffice/core
