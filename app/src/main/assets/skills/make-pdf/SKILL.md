---
name: make-pdf
description: A new printable report, handout, invoice-like document, or typeset PDF brief. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make PDF
  category: PROJECT
  group: Documents
  engine: Typst for new typeset PDF; preserve existing document source when applicable
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make PDF

**Use for:** A new printable report, handout, invoice-like document, or typeset PDF brief.

**Deliver:** PDF, editable .typ source, assets and a rendered-page review.

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

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Typst and page inspection

Reuse `typst --version`. If absent, inspect the active native repository's Typst package or choose an architecture/libc-compatible release from [Typst](https://github.com/typst/typst/releases), verify its checksum and install it into a versioned user-local tool directory. Do not select an x86 executable or assume a GNU/musl build is interchangeable.

No Python environment is required to compile Typst. For page rendering/inspection, check `pdfinfo`, `pdftoppm` and `pdftotext`; install the active repository's `poppler-utils` package when available and needed. Keep fonts local and recorded. Package downloads embedded in Typst sources must be explicit/pinned, not an unnoticed external dependency.

## Do the task

1. Read the actual content and requested page dimensions/style. Preserve an existing editable authoring source when the task is conversion; use Typst for a new typeset document rather than rebuilding DOCX/PPTX without need.
2. Create a task-local `.typ` source with semantic structure, consistent margins, headings, tables, captions and page numbering. Keep supplied factual text unchanged unless editing is requested.
3. Compile only the intended source tree:

```sh
mkdir -p output
typst compile document.typ output/document.pdf
```

4. Inspect page count/size with `pdfinfo`, extract text for missing-content checks and render pages with `pdftoppm -png -r 120 output/document.pdf output/page`. Visually inspect every page, fix clipping/missing glyphs/layout issues and recompile.
5. Deliver the PDF plus editable source when requested. Use NativeCode's current file/preview facilities. Typesetting and Poppler rendering are CPU tasks, not accelerated merely by `gpu-launch`.

## Check the actual result

Require correct page dimensions/count, text content, readable fonts, actual page previews and no overlap/clipping. Preserve source material and disclose missing renderer verification rather than claiming a perfect PDF from compile success alone.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/typst/typst
