---
name: generate-images
description: An explicit neural image-generation/editing request and an available authorized image model. Complete this task
  inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Generate Images
  category: PROJECT
  group: Images
  engine: ComfyUI on a proven local backend or explicitly authorized endpoint
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Generate Images

**Use for:** An explicit neural image-generation/editing request and an available authorized image model.

**Deliver:** Generated images plus workflow, seed/settings and model provenance.

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

### ComfyUI, model and compatible compute backend

Reuse an existing ComfyUI service/project and approved model. A browser/GPU wrapper does not supply PyTorch acceleration. First identify a supported local inference backend with enough memory, or an explicitly authorized existing remote endpoint.

For a new compatible local setup, clone an approved pinned revision of [ComfyUI](https://github.com/Comfy-Org/ComfyUI) into a versioned task-owned tool directory. Create its separate environment, install the PyTorch/backend build documented for the **actual** hardware/ABI, then install that revision's `requirements.txt`. Do not use CUDA/ROCm instructions on Adreno/Mali or blindly install a multi-gigabyte backend. See [ComfyUI's manual installation contract](https://docs.comfy.org/installation/manual_install).

Obtain only the selected model/encoder/VAE files with user approval, their checksums and licensing information. Put them in the installed workflow's documented model directories. If no supported local GPU execution provider exists, stop with that specific GPU blocker or use the user's explicitly accepted remote/CPU route; do not invent a NativeCode-to-PyTorch bridge or change NativeCode itself.

## Do the task

1. Read the image brief and actual reference images. Choose an installed workflow/model appropriate to the requested generation/edit, size and memory budget.
2. Keep the prompt, seed, workflow, model hashes and parameters in the project. Avoid unreviewed custom-node installations and arbitrary workflow-executed code.
3. Launch the existing service on localhost or, for the standard local checkout, use its environment to run `python main.py --listen 127.0.0.1 --port 8188`. Use the actual environment interpreter rather than system Python.
4. Open the ready WebUI with NativeCode's `open-link`. Run one small inference first, inspect the inference backend's device logs and memory behavior, then produce the requested final image(s).
5. Save images, workflow JSON, seed/settings and model provenance. A GPU-rendered WebUI is only display acceleration; report the compute backend independently.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require actual generated images, the requested content/size, retained reproducibility data and real inference-device evidence. Do not silently download a different model, use a paid endpoint, or label CPU/remote computation as on-device GPU generation.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/Comfy-Org/ComfyUI
- https://docs.comfy.org/installation/manual_install
