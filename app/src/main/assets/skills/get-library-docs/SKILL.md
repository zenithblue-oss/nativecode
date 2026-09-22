---
name: get-library-docs
description: A library/API question tied to the project’s actual installed version. Complete this task inside NativeCode using
  the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Get Library Docs
  category: PROJECT
  group: Development
  engine: Installed package docs / official docs; Context7 optional
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Get Library Docs

**Use for:** A library/API question tied to the project’s actual installed version.

**Deliver:** A concise answer or implementation note with exact version and primary-source references.

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

### Minimal setup

No extra program is needed when the agent already has file-reading and HTTP tools. Reuse installed package documentation, source/type definitions and official versioned documentation. If terminal fetching is needed and `curl` is missing, install just `curl` and CA certificates from the configured native repositories in the authorized root terminal. Python is optional for parsing; no browser or GPU is required for ordinary documentation retrieval.

Context7 is optional, not a gate. Use it only if already configured or specifically requested. For an explicit local setup, use the project's supported Node runtime and [Context7's official configuration](https://github.com/upstash/context7); install only that integration and obtain any required credential through the user's existing secret mechanism. Do not install another model or modify global OpenCode configuration just to read public docs.

## Do the task

1. Read the installed package/version and lockfile first; choose its matching docs rather than assuming the latest API.
2. Search local README/type definitions/source for the exact symbol or behavior. Fetch the relevant official page only when local evidence is insufficient.
3. Extract the contract, arguments, return values, lifecycle, version caveats and one working example. Keep the distinction between documented behavior and your inference.
4. Apply the answer to the current project and run the smallest existing validation that exercises it when code changes are requested. Do not undertake a broad internet survey.
5. Return a concise implementation note with version and source URL/file locations; do not claim a unavailable page was read. Use NativeCode `open-link` only when the user needs to view a specific docs page.

## Check the actual result

Every API claim must match the selected version and source. Identify missing evidence. Do not send secrets or private source to a third-party documentation service without authorization.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/upstash/context7
