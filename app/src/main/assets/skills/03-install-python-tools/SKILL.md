---
name: "install-python-tools"
description: "Install and verify the latest compatible Python runtime, pip, virtualenv, venv support, and uv for NativeCode ARM64. Use when Python tooling is missing, outdated, or explicitly requested."
compatibility: "NativeCode ARM64 PRoot; BellSoft Alpaquita Linux glibc; apk and official uv ARM64 binaries"
metadata:
  title: "Install Python Tools"
  category: "INSTALL"
  group: "Dependency setup"
  engine: "Python, venv, virtualenv, pip, and uv"
  status: "verified on 2026-09-22"
  dependencies: "Compatible Python package source, curl, and a matching ARM64/glibc runtime"
  reviewed: "2026-09-22"
---

# Install Python Tools

Install the complete Python baseline:

- the latest compatible Python runtime;
- standard-library \`venv\`;
- \`pip\` and \`pip3\` package installation;
- \`virtualenv\`;
- the latest compatible \`uv\` package and Python environment manager.

The optional \`uv\` route is required for this skill's complete setup. Use project-local virtual environments and never replace a project's pinned requirements with the newest packages without authorization.

## Detect before changing anything

Check architecture, libc, package sources, shells, and all Python entry points:

\`\`\`sh
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version | head -1
command -v apk
for tool in python3 python pip pip3 virtualenv uv; do
  command -v "\$tool" || printf '%s: missing\n' "\$tool"
done
python3 --version 2>/dev/null || true
python3 -m pip --version 2>/dev/null || true
virtualenv --version 2>/dev/null || true
uv --version 2>/dev/null || true
\`\`\`

Check whether the current interpreter can create an environment before assuming \`venv\` is complete:

\`\`\`sh
precheck_dir=$(mktemp -d /tmp/install-python-tools-precheck.XXXXXX)
python3 -m venv "\$precheck_dir/venv"
"\$precheck_dir/venv/bin/python" -m pip --version
\`\`\`

Source code mentioning Python or a package does not prove the runtime, pip, or a compatible wheel is installed.

## Select the latest compatible Python

Always check the current official Python release page and the active package repository at setup time. The latest stable Python release verified on 2026-09-22 was Python \`3.14.7\`. Python.org identifies it as the seventh maintenance release of the 3.14 series.

For this Alpaquita glibc ARM64 guest:

\`\`\`sh
apk update
apk policy python3
apk search -x python3
apk search -x py3-pip
apk search -x py3-virtualenv
\`\`\`

Use the newest repository package that matches the active Alpaquita release, architecture, and libc. If the repository lags behind the official stable release, do not silently accept the old runtime: check whether an approved compatible binary or uv-managed interpreter is available, or report the mismatch before a source build. Do not install x86_64 binaries or a musl-only artifact into this glibc guest.

Python.org's generic Linux ARM64 distribution is normally a source release rather than a universal desktop binary. A source build requires the development utilities from \`install-dev-utils\`, the exact build dependencies, and a separate compatibility check. Prefer the verified Alpaquita package when it is current.

## Install the complete package baseline

Install all components, including the virtualenv helper:

\`\`\`sh
apk add python3 py3-pip py3-virtualenv
\`\`\`

This is idempotent. It reuses already-installed components and adds missing ones. The package mapping is:

| Package | Capability |
| --- | --- |
| \`python3\` | Python interpreter and standard-library \`venv\` support |
| \`py3-pip\` | \`pip\` and \`pip3\` package installation |
| \`py3-virtualenv\` | \`virtualenv\` environment creation |
| official uv binary | fast package/environment management and Python selection |

Confirm the installed versions and interpreter ABI:

\`\`\`sh
python3 --version
python3 -c 'import platform, sys, sysconfig; print(sys.version); print(platform.machine()); print(sysconfig.get_platform())'
python3 -m pip --version
virtualenv --version
\`\`\`

## Install the latest uv

The Alpaquita package index has no \`uv\` package in this environment. Query Astral's official release list at setup time and select the newest release with an \`aarch64-unknown-linux-gnu\` artifact. The version verified on 2026-09-22 was \`0.12.17\`.

Download the official installer to a temporary file, inspect it, and execute the local copy. Do not pipe an unreviewed remote script directly to a shell:

\`\`\`sh
uv_version=0.12.17
uv_installer=/tmp/uv-installer-\${uv_version}.sh
curl -fLsS "https://astral.sh/uv/\${uv_version}/install.sh" -o "\$uv_installer"
sed -n '1,40p' "\$uv_installer"
UV_INSTALL_DIR=/home/flux/.local/bin UV_NO_MODIFY_PATH=1 sh "\$uv_installer"
/home/flux/.local/bin/uv --version
\`\`\`

Use the official ARM64 GNU build. Do not use the x86_64 or musl binary. Record the release URL, version, architecture, and checksum or release-attestation result when available.

## Configure PATH for Bash and Zsh

The uv installer was deliberately prevented from rewriting shell profiles. Add the user executable directory idempotently to both \`/home/flux/.bashrc\` and \`/home/flux/.zshrc\`:

\`\`\`sh
# NativeCode user-installed executables
case ":\${PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:\${PATH}" ;;
esac
\`\`\`

Keep the Node.js block from \`install-node-tools\` intact. Do not overwrite unrelated shell configuration. Ensure Bash login shells source \`/home/flux/.bashrc\` through \`/home/flux/.bash_profile\`:

\`\`\`sh
if [ -f /home/flux/.bashrc ]; then
  . /home/flux/.bashrc
fi
\`\`\`

Start a new shell or source the relevant file:

\`\`\`sh
. /home/flux/.bashrc
command -v uv
uv --version
\`\`\`

For Zsh, use \`. /home/flux/.zshrc\` or start a new interactive shell. Keep \`/home/flux/.local/bin\` on PATH without duplicate entries.

## Use environments safely

Create one environment per project and use its interpreter to install packages:

\`\`\`sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
\`\`\`

The equivalent uv route is:

\`\`\`sh
uv venv
uv pip install -r requirements.txt
\`\`\`

Prefer \`python -m pip\` over a bare \`pip\` so the installer is attached to the intended interpreter. Inspect ARM64/glibc wheel availability before installing large packages. If a package has no usable wheel, install only the approved native build dependencies and build it in the project environment; never emulate x86 silently or modify the system Python.

## Compatibility and smoke test

Run a representative test for both environment creation paths and package installation:

\`\`\`sh
python_smoke=$(mktemp -d /tmp/install-python-tools-smoke.XXXXXX)

python3 -m venv "\$python_smoke/venv"
"\$python_smoke/venv/bin/python" -m pip install --disable-pip-version-check --no-input --no-cache-dir --quiet packaging
"\$python_smoke/venv/bin/python" -c 'import packaging, sys; print(sys.version.split()[0], packaging.__version__)'

virtualenv --clear "\$python_smoke/virtualenv" >/dev/null
"\$python_smoke/virtualenv/bin/python" -c 'import sys; print(sys.version.split()[0])'

uv venv "\$python_smoke/uv-venv" --quiet
uv pip install --python "\$python_smoke/uv-venv/bin/python" --quiet packaging
"\$python_smoke/uv-venv/bin/python" -c 'import packaging; print(packaging.__version__)'

bash --noprofile --norc -c 'source /home/flux/.bashrc; command -v uv; uv --version'
zsh -f -c 'source /home/flux/.zshrc; command -v uv; uv --version'
\`\`\`

A version command alone is not enough. The smoke test must create environments and import a package from the selected environment.

## Record and rollback

Record:

- official Python release URL and selected version;
- architecture, libc, and active package repositories;
- apk package versions;
- uv release URL, version, architecture, and integrity evidence;
- virtual-environment locations and package-install results;
- shell files changed and PATH order;
- project-specific wheel or native-build compatibility findings.

Report **already-working**, **installed-and-smoke-tested**, **blocked**, or **planned-only** accurately. For shared apk packages, record the package-manager rollback plan instead of removing dependencies blindly. Remove only user-local uv files or project environments created by this operation after confirming they are not shared.

## Verification record for this workspace

On 2026-09-22, the guest was aarch64 BellSoft Alpaquita Linux glibc. Python \`3.14.7\` was already the current package version. \`py3-pip\` \`26.2\` and \`py3-virtualenv\` \`21.3.3\` were installed from the matching Alpaquita repositories. Astral uv \`0.12.17\` was installed to \`/home/flux/.local/bin\`. Standard \`venv\`, pip package installation, \`virtualenv\`, uv environment/package installation, and Bash/Zsh PATH smoke tests passed.

## Primary sources

- [Python 3.14.7 release](https://www.python.org/downloads/release/python-3147/)
- [Python documentation](https://docs.python.org/3/)
- [uv installation](https://docs.astral.sh/uv/getting-started/installation/)
- [uv releases](https://github.com/astral-sh/uv/releases)
