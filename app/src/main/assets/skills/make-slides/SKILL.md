---
name: make-slides
description: A presentation brief, outline, existing theme or editable PowerPoint requirement. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Slides
  category: PROJECT
  group: Documents
  engine: PptxGenJS for native editable PowerPoint
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Slides

**Use for:** A presentation brief, outline, existing theme or editable PowerPoint requirement.

**Deliver:** Editable .pptx, generation source, asset provenance and preview when a renderer is available.

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

### Node.js / npm, only if missing or incompatible

```sh
command -v node; command -v npm
node -p 'process.version + " " + process.arch + " " + process.platform'
npm --version
```

Prefer the supplied working toolchain at `/home/flux/.local/share/nativecode/tools/node/current/bin`. Inspect the project's `engines` and package-manager lockfile once. Do not upgrade a working Node/npm installation simply to use this skill.

If no compatible Node works, use the configured Alpaquita `nodejs`/`npm` packages when their candidate versions satisfy the project (`apk search -x nodejs`, `apk search -x npm`). Otherwise select a compatible `linux-arm64` release from [Node's official index](https://nodejs.org/dist/index.json), not an x64/musl archive. Set the exact approved version, then download and verify it:

```sh
: "${NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${NODE_VERSION}-linux-arm64.tar.xz"
node_stage=$(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${NODE_VERSION}/${node_archive}" -o "$node_stage/$node_archive"
curl -fsSL "https://nodejs.org/dist/${NODE_VERSION}/SHASUMS256.txt" -o "$node_stage/SHASUMS256.txt"
(cd "$node_stage"; grep "  ${node_archive}$" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "$node_base"
tar -xJf "$node_stage/$node_archive" -C "$node_base"
"$node_base/node-${NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### Editable PowerPoint package

For a new task project with Node ready, install PptxGenJS locally:

```sh
npm install pptxgenjs
node -e "console.log(typeof require('pptxgenjs'))"
```

Keep existing project versions/lockfiles. Use PptxGenJS for native editable text, shapes, tables and charts. Do not rasterize an entire slide and call the result editable. It is a presentation authoring library, not a PowerPoint rendering engine.

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

1. Read the user's actual source material, audience, aspect ratio and requested slide count. Preserve supplied terminology, facts and template; do not fill missing data with invented claims.
2. Plan a clear story and layout per slide, then write a local PptxGenJS generation script. Keep text, shapes, tables and suitable charts as native slide elements, with local assets and source notes.
3. Use consistent margins, typography and alignment. Measure text and content bounds, avoid overlap/overflow, and maintain readable sizes instead of shrinking dense text to fit.
4. Generate the actual `.pptx`. For a new deck a minimal authoring pattern is:

```javascript
const pptxgen = require('pptxgenjs');
async function main() {
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_WIDE';
  const slide = pptx.addSlide();
  slide.addText('Presentation title', {x: 0.6, y: 0.5, w: 12, h: 0.8, fontSize: 30});
  await pptx.writeFile({fileName: 'presentation.pptx'});
}
main().catch(error => { console.error(error); process.exitCode = 1; });
```

5. Render the deck with a compatible office renderer and inspect every slide image when rendering is available; correct clipping, missing glyphs and chart issues, then re-render. Use NativeCode's file UI/preview for delivery. This authoring/conversion pipeline is CPU work; a GPU-enabled office/browser preview is a separate display capability.

## Check the actual result

Require a valid editable PPTX, correct slide count/content, local asset availability and visual QA evidence when rendered. Reopen package/XML to check that text/shapes remain editable. If no compatible renderer exists, clearly report visual QA as unverified rather than calling the layout verified.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/gitbrent/PptxGenJS
