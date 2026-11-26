package com.zenithblue.nativecode.skills

/** Generated from nativecode-skills-page. Do not edit by hand. */
internal object AdditionalSkillTexts {
    val install: List<Skill> = listOf(
        installDevUtils(),
        installNodeTools(),
        installPythonTools(),
        installChromium(),
        installBlender(),
        addApps(),
        installAdb(),
        installHyperframes(),
        installMoneyprinter(),
        installAndroidTools(),
        installRea(),
        installHermes(),
        installSsh(),
    )

    val project: List<Skill> = listOf(
        buildAndroidApp(),
        buildWebApp(),
        buildWebsite(),
        buildFullStackApp(),
        buildApi(),
        turnWebsiteIntoApp(),
        testWebsite(),
        testAndroidApp(),
        getLibraryDocs(),
        buildPythonTool(),
        make2dGame(),
        make3dWebGame(),
        makeGodotGame(),
        makeGameAssets(),
        make3dAssets(),
        editImages(),
        makeThumbnail(),
        removeBackground(),
        makeVectorArt(),
        generateImages(),
        makeMotionVideo(),
        makeShortVideo(),
        makeReactVideo(),
        editVideo(),
        addSubtitles(),
        makeVoiceover(),
        cleanAudio(),
        makeVideoFromSlides(),
        makeSlides(),
        makeMarkdownSlides(),
        writeDocument(),
        makePdf(),
        editPdf(),
        readDocuments(),
        convertDocuments(),
        makeSpreadsheet(),
        frontendDesign(),
        themeFactory(),
        tasteDesign(),
        designMobileComposeUi(),
        designSystemTokens(),
        makeVanillaAnimation(),
    )

    private fun installDevUtils() = Skill(
        id = "install-dev-utils",
        title = "Install Dev Utilities",
        summary = "Set up Git, archive tools, checksums and build utilities.",
        source = "",
        body = """---
name: "install-dev-utils"
description: "Set up and verify Git, curl, archive tools, checksums, and native build utilities in a NativeCode ARM64 Alpaquita environment. Use when this development-tool baseline is requested; do not install unrelated packages."
compatibility: "NativeCode ARM64 PRoot; BellSoft Alpaquita Linux glibc; apk"
metadata:
  title: "Install Dev Utilities"
  category: "INSTALL"
  group: "Dependency setup"
  engine: "Git, curl, ZIP/tar, checksums, and build utilities"
  status: "verified on 2026-09-22"
  dependencies: "Existing NativeCode runtime and compatible Alpaquita package repositories"
  reviewed: "2026-09-22"
---

# Install Dev Utilities

Set up the complete development-utility baseline for the current NativeCode guest:

- Git and curl
- ZIP and tar creation/extraction tools
- SHA-256/SHA-512 and other checksum commands
- C/C++ and native-build utilities: Make, CMake, Ninja/Samurai, GCC, G++, and Clang

Installation is an explicit environment change. Detect first, use only the package source matching the running rootfs, and report what was already installed versus what was added.

## Detect the environment and existing tools

Run as the normal project user for detection and builds. Confirm the runtime before selecting packages:

\`\`\`sh
id
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version | head -1
command -v apk
\`\`\`

Check every utility rather than treating a source file or package name as proof that it works:

\`\`\`sh
for tool in git curl unzip zip tar sha256sum sha512sum cksum cmake ninja make gcc g++ clang; do
  command -v "\${'$'}tool" || printf '%s: missing\n' "\${'$'}tool"
done
\`\`\`

Record versions before changing anything:

\`\`\`sh
git --version
curl --version | head -1
unzip -v | head -1
zip -v | head -1
tar --version | head -1
sha256sum --version | head -1
cmake --version | head -1
ninja --version
make --version | head -1
gcc --version | head -1
g++ --version | head -1
clang --version | head -1
\`\`\`

## Obtain the complete package set

For this Alpaquita glibc ARM64 environment, use the configured Alpaquita repositories. Do not substitute Ubuntu/Debian commands, add foreign Alpine repositories, use x86_64 archives, or run an unreviewed remote installer.

First refresh package indexes and confirm exact package names:

\`\`\`sh
apk update
for package in git curl unzip zip tar coreutils cmake ninja make gcc g++ clang; do
  apk search -x "\${'$'}package"
done
\`\`\`

When the full baseline is requested, ensure all of these packages are present:

\`\`\`sh
apk add git curl unzip zip tar coreutils cmake ninja make gcc g++ clang
\`\`\`

The package-to-command mapping is:

| Package | Commands or capability |
| --- | --- |
| \`git\` | \`git\` |
| \`curl\` | \`curl\` |
| \`unzip\` | \`unzip\` |
| \`zip\` | \`zip\` |
| \`tar\` | \`tar\` |
| \`coreutils\` | \`sha256sum\`, \`sha512sum\`, \`cksum\`, and related utilities |
| \`cmake\` | \`cmake\` |
| \`ninja\` or the repository's provider | \`ninja\` |
| \`make\` | \`make\` |
| \`gcc\` | \`gcc\` |
| \`g++\` | \`g++\` |
| \`clang\` | \`clang\` |

\`apk add\` is idempotent: packages already present should remain installed, while missing packages such as \`zip\` are added. Build packages are part of this complete entry, but a future project may choose only the subset required by its build system.

## Verify compatibility

Before using a downloaded binary or native dependency, verify all of the following:

- \`uname -m\` is the architecture expected by the artifact, normally \`aarch64\` here.
- The artifact targets the detected libc (\`glibc\` here), not an incompatible musl-only or foreign desktop build.
- The project's required compiler, CMake, Ninja, and language standards are supported by the installed versions.
- Downloads use an authoritative source and a published checksum or signature where available.
- The package database and repository URLs belong to the active Alpaquita release and architecture.

Use project-local build directories and preserve existing lockfiles or build configuration. Do not change global Git identity, remotes, credentials, or unrelated user configuration.

## Smoke test

Run a real disposable test after setup. It must exercise Git, archive creation/listing, checksums, and a native compiler:

\`\`\`sh
smoke_dir=${'$'}(mktemp -d /tmp/install-dev-utils-smoke.XXXXXX)
touch "\${'$'}smoke_dir/payload.txt"
zip -q "\${'$'}smoke_dir/archive.zip" "\${'$'}smoke_dir/payload.txt"
unzip -tq "\${'$'}smoke_dir/archive.zip"
tar -cf "\${'$'}smoke_dir/archive.tar" -C "\${'$'}smoke_dir" payload.txt
tar -tf "\${'$'}smoke_dir/archive.tar" | grep -qx 'payload.txt'
sha256sum "\${'$'}smoke_dir/archive.zip" > "\${'$'}smoke_dir/archive.zip.sha256"
sha256sum -c "\${'$'}smoke_dir/archive.zip.sha256"
git -C "\${'$'}smoke_dir" init -q
git -C "\${'$'}smoke_dir" status --short
printf 'int main(void){return 0;}' | gcc -x c -o "\${'$'}smoke_dir/hello" -
"\${'$'}smoke_dir/hello"
\`\`\`

Also run the selected project's own configure/build smoke test when CMake, Ninja, Make, or Clang is required. A version command alone is not evidence that a compiler or archive tool works.

## Result and rollback record

Record the architecture, libc, repository, package names and versions, source/checksum for any separately downloaded artifact, changed files, and smoke-test result. Report one of:

- **already-working** — the complete requested set was present and passed tests;
- **installed-and-smoke-tested** — missing packages were added and tests passed;
- **blocked** — a compatible source, package, permission, or test is unavailable;
- **planned-only** — no environment change was authorized or attempted.

For shared \`apk\` packages, record the package-manager rollback plan instead of removing packages blindly. Remove only user-local files created by the setup after confirming no other project uses them.

## Verification record for this workspace

On 2026-09-22, the NativeCode guest reported \`aarch64\`, BellSoft Alpaquita Linux Stream glibc, and \`apk\`. Git, curl, unzip, tar, checksums, CMake, Ninja, Make, GCC, G++, and Clang were already available; \`zip\` was missing. The compatible Alpaquita package index was refreshed and the complete package command succeeded. Archive, checksum, Git repository, and C compiler smoke tests passed.

## Primary sources

- [Git documentation](https://git-scm.com/docs)
- [curl documentation](https://curl.se/docs/)
- [CMake documentation](https://cmake.org/documentation/)
- [GNU tar manual](https://www.gnu.org/software/tar/manual/)
""",
    )

    private fun installNodeTools() = Skill(
        id = "install-node-tools",
        title = "Install Node Tools",
        summary = "Set up Node.js and npm for NativeCode.",
        source = "",
        body = """---
name: "install-node-tools"
description: "Install and verify the latest compatible Node.js and npm toolchain for NativeCode ARM64, including persistent Bash and Zsh PATH configuration. Use when Node tooling is missing, outdated, or explicitly requested."
compatibility: "NativeCode ARM64 PRoot; BellSoft Alpaquita Linux glibc; official Node.js linux-arm64 binaries"
metadata:
  title: "Install Node Tools"
  category: "INSTALL"
  group: "Dependency setup"
  engine: "Node.js and npm"
  status: "verified on 2026-09-22"
  dependencies: "curl, tar/xz, checksums, compatible CPU architecture and libc"
  reviewed: "2026-09-22"
---

# Install Node Tools

Install the latest compatible Node.js release and a package manager. This skill uses the official Node.js ARM64 binary and npm. It keeps the toolchain user-local, exposes it through a stable \`current\` path, and configures both Bash and Zsh so new shells do not silently select an older system Node.

Use project-local package installs and preserve lockfiles. Do not install unrelated global CLIs merely because Node is available.

## Detect before changing anything

Check the current architecture, libc, shell files, and all existing Node package managers:

\`\`\`sh
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version | head -1
for tool in node npm npx corepack pnpm yarn bun; do
  command -v "\${'$'}tool" || printf '%s: missing\n' "\${'$'}tool"
done
node --version 2>/dev/null || true
npm --version 2>/dev/null || true
npm config get prefix 2>/dev/null || true
\`\`\`

A system Node version is not automatically compatible with the selected project. Inspect its \`engines\` field, lockfile, native dependencies, and required Node ABI before replacing or bypassing it.

## Select the latest compatible release

The default policy is latest available, not latest remembered. Query the official Node release index at setup time:

\`\`\`sh
curl -fsSL https://nodejs.org/dist/index.json
\`\`\`

Choose the newest release entry that:

- has a \`linux-arm64\` binary;
- is not marked EOL or security-inactive;
- is compatible with the project's \`engines\` and native dependencies;
- matches the guest's architecture and libc.

For a current NativeCode ARM64 glibc guest, use the official \`linux-arm64.tar.xz\` artifact. Do not select the \`linux-x64\` artifact or the \`linux-x64-musl\` artifact. If a project explicitly requires LTS, choose the newest supported LTS entry and record that exception; otherwise use the newest current release.

The latest release selected during the 2026-09-22 verification was Node.js \`v26.10.0\`. Its bundled npm was \`11.19.1\`; npm's registry \`latest\` was \`12.0.2\`, and npm 12 declared support for Node \`>=26.0.0\`.

## Download, verify, and install Node.js

Install into a versioned user-owned directory. Do not overwrite the system package and do not run an arbitrary remote shell installer:

\`\`\`sh
node_version=v26.10.0
node_archive="node-\${'$'}{node_version}-linux-arm64.tar.xz"
node_parent=/home/flux/.local/share/nativecode/tools/node
node_stage_dir=/tmp/install-node-tools
node_stage="\${'$'}node_stage_dir/\${'$'}node_archive"

mkdir -p "\${'$'}node_parent" "\${'$'}node_stage_dir"
curl -fL "https://nodejs.org/dist/\${'$'}{node_version}/\${'$'}{node_archive}" -o "\${'$'}node_stage"
curl -fLsS "https://nodejs.org/dist/\${'$'}{node_version}/SHASUMS256.txt" |
  grep "  \${'$'}{node_archive}\${'$'}" > "\${'$'}node_stage_dir/\${'$'}{node_archive}.sha256"
(
  cd "\${'$'}node_stage_dir"
  sha256sum -c "\${'$'}{node_archive}.sha256"
)
tar -xJf "\${'$'}node_stage" -C "\${'$'}node_parent"
node_home="\${'$'}node_parent/node-\${'$'}{node_version}-linux-arm64"
test -x "\${'$'}node_home/bin/node"
test -x "\${'$'}node_home/bin/npm"
\`\`\`

For a future latest release, replace \`node_version\` only after checking the current official index and checksum. Record the exact version and SHA-256 value. For the 2026-09-22 artifact, the verified SHA-256 was:

\`\`\`text
7a6353f63eb3d04765004b4adf172616243e4522434635cb1d26288658b04ab5
\`\`\`

## Update npm to its latest compatible version

Node bundles npm, but npm releases independently. Query npm's registry tag and engine requirement using the extracted binary:

\`\`\`sh
"\${'$'}node_home/bin/npm" view npm version
"\${'$'}node_home/bin/npm" view npm engines --json
\`\`\`

Install the current \`latest\` tag only when its engine range includes the selected Node version:

\`\`\`sh
"\${'$'}node_home/bin/npm" install --global --prefix "\${'$'}node_home" npm@latest
"\${'$'}node_home/bin/node" --version
"\${'$'}node_home/bin/npm" --version
"\${'$'}node_home/bin/npx" --version
\`\`\`

Use the project lockfile for project dependencies. Avoid global package installation except for the Node/npm toolchain or a CLI explicitly required by the user's selected workflow.

## Configure PATH for Bash and Zsh

Keep a stable symlink so future upgrades do not require changing shell configuration:

\`\`\`sh
ln -sfn "\${'$'}node_home" "\${'$'}node_parent/current"
\`\`\`

Add this idempotent block to \`/home/flux/.bashrc\` and \`/home/flux/.zshrc\` without deleting unrelated user configuration:

\`\`\`sh
# NativeCode Node.js toolchain
export NATIVE_NODE_HOME="/home/flux/.local/share/nativecode/tools/node/current"
case ":\${'$'}{PATH}:" in
  *":\${'$'}{NATIVE_NODE_HOME}/bin:"*) ;;
  *) export PATH="\${'$'}{NATIVE_NODE_HOME}/bin:\${'$'}{PATH}" ;;
esac
\`\`\`

For Bash login shells, ensure \`/home/flux/.bash_profile\` sources \`/home/flux/.bashrc\`:

\`\`\`sh
if [ -f /home/flux/.bashrc ]; then
  . /home/flux/.bashrc
fi
\`\`\`

Apply the configuration to the current shell or start a new shell:

\`\`\`sh
. /home/flux/.bashrc
node --version
npm --version
\`\`\`

For Zsh, use \`. /home/flux/.zshrc\` or start a new interactive Zsh. Keep the Node path ahead of \`/usr/bin\`; otherwise the system package can continue to win command lookup. Do not add duplicate PATH entries on repeated shell startup.

## Verify compatibility and smoke-test

Confirm the selected binary is really ARM64 Linux and that npm reaches its registry:

\`\`\`sh
command -v node
command -v npm
node -p 'process.version + " " + process.arch + " " + process.platform'
npm ping --silent
npm config get prefix
\`\`\`

Run a disposable project-local package test:

\`\`\`sh
smoke_dir=${'$'}(mktemp -d /tmp/install-node-tools-smoke.XXXXXX)
npm init -y --prefix "\${'$'}smoke_dir" >/dev/null
npm install --prefix "\${'$'}smoke_dir" --ignore-scripts --no-audit --no-fund is-number@7.0.0 >/dev/null
node -e "const isNumber=require('\${'$'}smoke_dir/node_modules/is-number'); if (!isNumber(7)) process.exit(1); console.log('npm package smoke passed')"
\`\`\`

Also verify both shell startup paths:

\`\`\`sh
bash --noprofile --norc -c 'source /home/flux/.bashrc; command -v node; node --version; npm --version'
zsh -f -c 'source /home/flux/.zshrc; command -v node; node --version; npm --version'
\`\`\`

A version string alone is not sufficient. A native project dependency must be rebuilt or tested when its ABI, libc, or Node engine requirement differs.

## Record and rollback

Record:

- the release-index URL and selected Node version;
- architecture and libc;
- artifact URL and SHA-256;
- npm version and engine range;
- install path and \`current\` symlink target;
- shell files changed;
- smoke-test results and any project-specific compatibility result.

Report **already-working**, **installed-and-smoke-tested**, **blocked**, or **planned-only** accurately. To roll back, point the \`current\` symlink to a previously verified version and remove only the versioned user-local directory created by this operation after checking that no project uses it. Do not remove the system Node package or erase project lockfiles.

## Verification record for this workspace

On 2026-09-22, the guest was aarch64 Alpaquita glibc 2.43 with system Node \`v24.18.1\` and npm \`12.0.2\`. Official Node \`v26.10.0\` ARM64 was downloaded and SHA-256 verified, installed under \`/home/flux/.local/share/nativecode/tools/node/\`, and linked as \`current\`. npm \`12.0.2\` was installed into that toolchain. Bash and Zsh resolved Node \`v26.10.0\` and npm \`12.0.2\`; registry, package-install, architecture, and shell-path smoke tests passed.

## Primary sources

- [Node.js release index](https://nodejs.org/dist/index.json)
- [Node.js downloads](https://nodejs.org/en/download/)
- [npm CLI version guidance](https://docs.npmjs.com/about-npm-versions/)
- [npm package](https://www.npmjs.com/package/npm)
""",
    )

    private fun installPythonTools() = Skill(
        id = "install-python-tools",
        title = "Install Python Tools",
        summary = "Set up Python, environments, pip and uv.",
        source = "",
        body = """---
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
  command -v "\${'$'}tool" || printf '%s: missing\n' "\${'$'}tool"
done
python3 --version 2>/dev/null || true
python3 -m pip --version 2>/dev/null || true
virtualenv --version 2>/dev/null || true
uv --version 2>/dev/null || true
\`\`\`

Check whether the current interpreter can create an environment before assuming \`venv\` is complete:

\`\`\`sh
precheck_dir=${'$'}(mktemp -d /tmp/install-python-tools-precheck.XXXXXX)
python3 -m venv "\${'$'}precheck_dir/venv"
"\${'$'}precheck_dir/venv/bin/python" -m pip --version
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
uv_installer=/tmp/uv-installer-\${'$'}{uv_version}.sh
curl -fLsS "https://astral.sh/uv/\${'$'}{uv_version}/install.sh" -o "\${'$'}uv_installer"
sed -n '1,40p' "\${'$'}uv_installer"
UV_INSTALL_DIR=/home/flux/.local/bin UV_NO_MODIFY_PATH=1 sh "\${'$'}uv_installer"
/home/flux/.local/bin/uv --version
\`\`\`

Use the official ARM64 GNU build. Do not use the x86_64 or musl binary. Record the release URL, version, architecture, and checksum or release-attestation result when available.

## Configure PATH for Bash and Zsh

The uv installer was deliberately prevented from rewriting shell profiles. Add the user executable directory idempotently to both \`/home/flux/.bashrc\` and \`/home/flux/.zshrc\`:

\`\`\`sh
# NativeCode user-installed executables
case ":\${'$'}{PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:\${'$'}{PATH}" ;;
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
python_smoke=${'$'}(mktemp -d /tmp/install-python-tools-smoke.XXXXXX)

python3 -m venv "\${'$'}python_smoke/venv"
"\${'$'}python_smoke/venv/bin/python" -m pip install --disable-pip-version-check --no-input --no-cache-dir --quiet packaging
"\${'$'}python_smoke/venv/bin/python" -c 'import packaging, sys; print(sys.version.split()[0], packaging.__version__)'

virtualenv --clear "\${'$'}python_smoke/virtualenv" >/dev/null
"\${'$'}python_smoke/virtualenv/bin/python" -c 'import sys; print(sys.version.split()[0])'

uv venv "\${'$'}python_smoke/uv-venv" --quiet
uv pip install --python "\${'$'}python_smoke/uv-venv/bin/python" --quiet packaging
"\${'$'}python_smoke/uv-venv/bin/python" -c 'import packaging; print(packaging.__version__)'

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
""",
    )

    private fun installChromium() = Skill(
        id = "install-chromium",
        title = "Install Chromium",
        summary = "Set up the supplied NativeCode Chromium GPU route.",
        source = "",
        body = """---
name: install-chromium
description: Install and verify an ARM64-compatible Chromium-family browser for previews, browser testing, and rendering in the NativeCode Linux guest.
metadata:
  short-description: Install Chromium-compatible browser tooling
---

# Install Chromium

Use this skill when a task needs a local Chromium-family browser for headless rendering, previews, browser tests, or interactive display use. Install one compatible browser route; do not install every browser package or add an incompatible x86_64 archive to PATH.

## Environment and prerequisites

- NativeCode guest is expected to be ARM64/aarch64 and glibc-based. Confirm with `uname -m` and `ldd --version`.
- Use the configured native Alpaquita repositories for system libraries. Do not add foreign Alpine repositories.
- Install the font families required by the content. At minimum, verify a working sans-serif font with `fc-match`; use **Install Fonts** when language coverage or reproducible rendering requires more than the base fonts.
- For interactive windows, use NativeCode display integration and `gpu-launch --auto`. Headless rendering does not prove display or GPU acceleration.
- Do not use `systemctl`; this guest does not run systemd.

## Detect an existing installation

Check all likely command names and SDK locations before changing anything:

```bash
command -v chromium chromium-browser google-chrome google-chrome-stable 2>/dev/null || true
for p in /opt/google/chrome/chrome /opt/chromium/chrome /opt/android-sdk/chromium/chrome; do
  test -x "${'$'}p" && { file "${'$'}p"; "${'$'}p" --version; ldd "${'$'}p" | grep 'not found' || true; }
done
fc-match sans-serif
```

Accept an existing browser only if its executable is `aarch64`, its dynamic loader matches the guest libc, its shared libraries resolve, and `--version` succeeds. If **Install Browser Tests** already has a proven browser, reuse that exact browser instead of creating a second route.

## Obtain the compatible browser

Prefer a current package from a configured native repository when one exists. On this Alpaquita guest there was no Chromium package in the configured repositories, and the official Chromium Linux snapshot route did not expose an ARM64 build. The current official Google ARM64 Linux package is therefore the compatible Chromium-family route:

```bash
curl -fL --retry 3 -o /tmp/google-chrome-stable_current_arm64.deb \
  https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
sha256sum /tmp/google-chrome-stable_current_arm64.deb
```

The package is a Debian archive, but a package manager is not required for this PRoot guest. Inspect and extract it without executing maintainer scripts:

```bash
mkdir -p /tmp/chrome-deb/{deb,root}
ar x /tmp/google-chrome-stable_current_arm64.deb --output /tmp/chrome-deb/deb
tar -xf /tmp/chrome-deb/deb/data.tar.xz -C /tmp/chrome-deb/root
file /tmp/chrome-deb/root/opt/google/chrome/chrome
```

Read the package control metadata and verify the architecture before installation:

```bash
tar -xOf /tmp/chrome-deb/deb/control.tar.xz ./control
```

Install runtime libraries using the native package manager. Package names can differ between repositories; resolve them with `apk search` and install only the dependencies required by the selected browser:

```bash
apk add nspr nss ca-certificates font-liberation alsa-lib \
  at-spi2-core at-spi2-core-libs libatk-1.0 libatk-bridge-2.0 \
  cairo cups-libs curl dbus expat mesa-gbm glib gtk+3.0 pango \
  eudev libva vulkan-loader libx11 libxcb libxcomposite libxdamage \
  libxext libxfixes libxkbcommon libxrandr wget
```

If the package or its browser binary is not `aarch64`, stop and select a native build or a compatible repository package. Do not install the official x86_64 Linux archive on this host.

## Install and expose commands

Preserve an existing installation before replacing it, then install the extracted browser and stable user-facing aliases:

```bash
test ! -e /opt/google/chrome || mv /opt/google/chrome /tmp/google-chrome-existing-${'$'}(date +%Y%m%d)
mkdir -p /opt/google
cp -a /tmp/chrome-deb/root/opt/google/chrome /opt/google/chrome
mkdir -p /home/flux/.local/bin
chmod 755 /opt/google/chrome/chrome /opt/google/chrome/google-chrome
```

Make the normal command use NativeCode’s hardware path and reject GPU software rasterization:

```sh
#!/bin/sh
set -- --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan \
  --enable-features=VaapiVideoDecoder,VaapiIgnoreDriverChecks "${'$'}@"
if [ -n "${'$'}{WAYLAND_DISPLAY:-}" ] && [ -n "${'$'}{XDG_RUNTIME_DIR:-}" ] && \
   [ -S "${'$'}{XDG_RUNTIME_DIR}/${'$'}{WAYLAND_DISPLAY}" ]; then
  set -- --ozone-platform=wayland --enable-features=UseOzonePlatform "${'$'}@"
fi
exec gpu-launch --auto /opt/google/chrome/google-chrome "${'$'}@"
```

Save that wrapper as `/home/flux/.local/bin/chromium`, make it executable, and point `chromium-browser` and `google-chrome` at the wrapper. The wrapper must not silently add `--disable-gpu` or select SwiftShader.

```bash
chmod 755 /home/flux/.local/bin/chromium
ln -sfn /home/flux/.local/bin/chromium /home/flux/.local/bin/chromium-browser
ln -sfn /home/flux/.local/bin/chromium /home/flux/.local/bin/google-chrome
```

In this rootless PRoot guest, Chrome’s setuid sandbox cannot start because `chrome-sandbox` is not root-owned/setuid. The launcher therefore uses `--no-sandbox` for every NativeCode launch; this is a guest constraint, not a production security recommendation outside this environment. It also selects Wayland/Ozone only when Phosh supplies a live `WAYLAND_DISPLAY` socket; forcing Wayland in a shell with a stale socket can make Chrome exit during platform initialization.

Add the user bin directory once to both `/home/flux/.bashrc` and `/home/flux/.zshrc`; preserve existing PATH entries and make the block idempotent:

```sh
# NativeCode browser tools
case ":\${'$'}{PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:\${'$'}{PATH}" ;;
esac
```

Ensure `/home/flux/.bash_profile` sources `.bashrc` for login Bash shells. Start a new shell or source the relevant file, then confirm `command -v chromium` points to `/home/flux/.local/bin/chromium`.

## Compatibility and smoke tests

Run all of these after installation:

```bash
file /opt/google/chrome/chrome
ldd /opt/google/chrome/chrome | grep 'not found' && exit 1 || true
/opt/google/chrome/chrome --version
fc-match sans-serif
test -r /opt/google/chrome/resources.pak
test -r /opt/google/chrome/icudtl.dat
```

For a non-interactive renderer smoke test, use a throwaway profile and the NativeCode-safe sandbox setting:

```bash
rm -rf /tmp/chromium-smoke-profile
mkdir -p /tmp/chromium-smoke-profile
/opt/google/chrome/chrome \
  --headless=new --no-sandbox --disable-dev-shm-usage --disable-gpu \
  --user-data-dir=/tmp/chromium-smoke-profile \
  --dump-dom 'data:text/html,<h1>Chromium smoke</h1>' | grep -F 'Chromium smoke'
```

The wrapper uses `--no-sandbox` because this rootless PRoot guest cannot run Chrome’s setuid sandbox. Do not treat that flag as a security recommendation for an ordinary desktop installation. DBus warnings are expected when no DBus session is available; a successful DOM result is the acceptance signal.

For interactive validation, start the browser through NativeCode’s display/graphics bridge and test the actual preview window. Use the GPU-only flags so a missing hardware path is visible instead of silently falling back to a software rasterizer:

```bash
gpu-launch --diagnose
gpu-launch --auto chromium \
  --enable-gpu --disable-software-rasterizer --ignore-gpu-blocklist \
  --use-gl=angle --use-angle=vulkan \
  --no-first-run --user-data-dir=/tmp/chromium-interactive-profile
```

For an automated WebGL acceptance test, load a page that reports `WEBGL_debug_renderer_info` and reject `SwiftShader`, `llvmpipe`, `Software`, or an absent renderer. The expected NativeCode result is an ANGLE Vulkan renderer containing `Turnip` and the Qualcomm Adreno device. `gpu-launch --diagnose` must report `turnip=PASS` and `auto=turnip`.

Test video separately. A browser playback test must prove that the `<video>` element reaches `playing`, but `navigator.mediaCapabilities.decodingInfo()` should also report `powerEfficient:true` when browser hardware decoding is required. `powerEfficient:false` means playback succeeded through a software or otherwise non-power-efficient browser decoder; do not report that as hardware video decoding. In this NativeCode guest, use the host bridge for a hardware video acceptance test:

```bash
nativecode-media capabilities
nativecode-media benchmark_file --path /path/to/test.mp4 --mode surface
```

Accept the bridge result only when the selected codec reports `is_hardware:true`, `software_only:false`, decoded frames are nonzero, and the measured rate is sensible. NativeCode MediaCodec hardware decoding does not automatically make Chrome’s Linux HTML5 decoder hardware-backed; report those two results separately.

Do not remove `--disable-software-rasterizer` to hide a failed GPU test. If the Turnip/Zink/OpenGL path is unavailable, stop and report the graphics limitation rather than accepting CPU fallback.

## Phosh application menu

Install `/home/flux/.local/share/applications/chromium-turnip.desktop` so Phosh launches the GPU-enforcing wrapper:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=Chromium (Turnip GPU)
GenericName=Web Browser
Comment=Chromium browser with NativeCode Turnip/Vulkan acceleration
Exec=/home/flux/.local/bin/chromium %U
TryExec=/home/flux/.local/bin/chromium
Icon=/opt/google/chrome/product_logo_128.png
Terminal=false
Categories=Network;WebBrowser;
MimeType=text/html;text/xml;application/xhtml+xml;x-scheme-handler/http;x-scheme-handler/https;
StartupNotify=true
StartupWMClass=Google-chrome
X-GNOME-UsesNotifications=true
```

Refresh the desktop database when `update-desktop-database` is available. Close existing Chromium windows before retesting from Phosh because Chrome reuses an already-running browser process.

## Installed verification record

The NativeCode ARM64 installation completed with:

- Official package: `google-chrome-stable_current_arm64.deb`
- Browser version: `Google Chrome 153.0.8010.52`
- Download SHA-256: `e5a1d646af594568941e3a61e7b8eddc693f769b7f166874c71f72c9cb806165`
- Binary: ARM aarch64, glibc loader `/lib/ld-linux-aarch64.so.1`
- Shared libraries: resolved
- Fonts: DejaVu Sans and Liberation fonts available
- Smoke test: headless DOM render passed
- GPU probe: NativeCode Turnip backend available (`turnip=PASS`, `auto=turnip`)
- WebGL: hardware-backed ANGLE Vulkan renderer reported `Turnip Adreno 750`; no SwiftShader or llvmpipe fallback
- Browser video: H.264 playback reached `playing`, but `powerEfficient:false`; browser hardware decode is not proven in the Linux guest
- NativeCode video bridge: H.264 MediaCodec reported `is_hardware:true`, `software_only:false`, and 150 decoded frames
- VA-API runtime: `libva` installed; Chrome browser hardware decode remains unproven because the PRoot guest exposes no `/dev/dri` VA-API device
- Phosh menu: `/home/flux/.local/share/applications/chromium-turnip.desktop`

## Recovery

If the installed browser is incompatible, remove only the aliases and move `/opt/google/chrome` to a dated quarantine directory. Restore the previous directory from `/tmp` if one was saved. Do not delete user browser profiles unless the user explicitly requests that data removal.

## References

- [Chromium download guidance](https://www.chromium.org/getting-involved/download-chromium/)
- [Google’s ARM64 Linux Chrome announcement](https://blog.google/chromium/bringing-chrome-to-arm64-linux-devices/)
""",
    )

    private fun installBlender() = Skill(
        id = "install-blender",
        title = "Install Blender",
        summary = "Set up the supplied Blender Vulkan launcher.",
        source = "",
        body = """---
name: install-blender
description: Install and verify a native ARM64 Blender build with bundled scripting support, Vulkan graphics, rendering smoke tests, PATH setup, and a Phosh launcher.
metadata:
  short-description: Install Blender for NativeCode ARM64
---

# Install Blender

Use this skill when a task needs Blender for 3D editing, Python automation, EEVEE rendering, asset conversion, or background rendering. Install one architecture-compatible Blender build; do not place the official Linux x86_64 archive on an ARM64 system.

## Environment and prerequisites

- Confirm `uname -m` is `aarch64` and the guest uses glibc with `ldd --version`.
- Use the configured native Alpaquita repositories only. Do not add foreign Alpine repositories.
- Blender needs a compatible graphics path for editor and EEVEE viewport use. NativeCode provides Turnip Vulkan through `gpu-launch --auto`; `gpu-launch --diagnose` must report `turnip=PASS` for the installed GPU route.
- Keep fonts available through **Install Fonts** when text, UI, or reproducible scene output needs language coverage beyond the base fonts.
- Blender's bundled Python is the supported scripting environment. Do not replace it with the system Python for Blender's internal modules.
- Cycles GPU compute is separate from viewport/EEVEE Vulkan support. Adreno/Turnip is not CUDA, OptiX, HIP, OneAPI, or Metal; do not claim Cycles GPU rendering unless Blender exposes and verifies a supported Cycles device.
- Do not use `systemctl`; this guest has no systemd.

## Detect an existing installation

Check commands, architecture, dynamic libraries, version, and graphics support before changing anything:

```bash
command -v blender blender-launcher 2>/dev/null || true
for p in /opt/blender/current/blender /opt/blender/*/blender /home/flux/.local/bin/blender; do
  test -x "${'$'}p" && {
    file "${'$'}p"
    ldd "${'$'}p" | grep 'not found' || true
    "${'$'}p" --version | head -3
  }
done
gpu-launch --diagnose
```

Reuse an existing build only when it is ARM64/aarch64, its glibc loader and shared libraries resolve, its version is acceptable for the project, and the selected graphics backend starts. Test the actual operation needed; a version command alone does not prove that the editor or renderer works.

## Obtain a compatible build

The official Blender download page currently publishes Linux x86_64, not Linux ARM64. The current native ARM64 binary used here is the latest available release from the `lfdevs/blender-linux-arm64` project:

- Release: Blender 5.1.0
- Build date: 2026-03-25
- Commit: `ae6d847d66fa`
- Archive: `blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz`
- SHA-256: `a4927219950566af13572e72f31b5bcb8baf87190ee86a26e2572ce7fd059793`

Download and verify it before extraction:

```bash
mkdir -p /tmp/blender-arm64
curl -fL --retry 3 -o /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz \
  https://github.com/lfdevs/blender-linux-arm64/releases/download/v5.1.0/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz
printf '%s  %s\n' \
  a4927219950566af13572e72f31b5bcb8baf87190ee86a26e2572ce7fd059793 \
  /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz | sha256sum -c -
tar -xzf /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz \
  -C /tmp/blender-arm64
file /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64/blender
```

Do not substitute the official `linux-x64` archive: it cannot execute on this aarch64 guest. Recheck the ARM64 release page for a newer native build before future upgrades.

## Install runtime libraries and Blender

The portable archive bundles Blender's large application libraries, but the guest still needs the system display, font, Vulkan, and X11 compatibility libraries. Resolve package names with `apk search` if the repository changes:

```bash
apk add fontconfig freetype libx11 libxrender libxfixes libxi \
  libxkbcommon libxcb mesa-gbm vulkan-loader gtk+3.0 \
  libdecor libdecor-gtk
```

Install the verified archive under a versioned directory and maintain a stable symlink:

```bash
mkdir -p /opt/blender
test ! -e /opt/blender/current || mv /opt/blender/current /tmp/blender-existing-${'$'}(date +%Y%m%d)
mv /tmp/blender-arm64/blender-5.1.0-git20260325.ae6d847d66fa-aarch64 /opt/blender/5.1.0-arm64
ln -sfn /opt/blender/5.1.0-arm64 /opt/blender/current
chmod 755 /opt/blender/current/blender /opt/blender/current/blender-launcher
```

Use the bundled launcher, not the binary directly; it sets the archive's library path. Verify:

```bash
/opt/blender/current/blender-launcher --version
ldd /opt/blender/current/blender | grep 'not found' && exit 1 || true
```

## PATH, Phosh display selection, and GPU launcher

Add `/home/flux/.local/bin` once to both `/home/flux/.bashrc` and `/home/flux/.zshrc`, preserving existing entries:

```sh
# NativeCode user-installed executables
case ":\${'$'}{PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:\${'$'}{PATH}" ;;
esac
```

Create `/home/flux/.local/bin/blender` with the Vulkan backend forced through NativeCode Turnip:

```sh
#!/bin/sh

# Blender's Vulkan Wayland path is not usable in this NativeCode Phosh session:
# the compositor is scaled 3x and the ARM64 build can hang before mapping its
# window. Use the session's Xwayland display while retaining Vulkan/Turnip GPU
# rendering. An empty value is Blender's documented X11 selector.
# PulseAudio initialization stalls this PRoot/Phosh launch before the window
# appears, so the menu launcher disables Blender audio by default.
export WAYLAND_DISPLAY=
exec gpu-launch --auto /opt/blender/current/blender-launcher \
  --gpu-backend vulkan -noaudio "${'$'}@"
```

Make it executable and confirm Bash and Zsh both resolve it:

```bash
chmod 755 /home/flux/.local/bin/blender
bash -lc 'command -v blender; blender --version | head -3'
zsh -lc 'command -v blender; blender --version | head -3'
```

The Vulkan flag is required for this ARM64 build on the current Mesa stack: its default OpenGL backend crashed in the tested background render, while `--gpu-backend vulkan` rendered successfully through Turnip. Keep the failure visible; do not silently switch to software rendering.

For Phosh, keep `WAYLAND_DISPLAY` empty in the wrapper. Native Wayland startup was tested against the live Phoc session and did not map a Blender window; the Xwayland-selected launch avoided that path. This changes only the window protocol: Vulkan rendering still uses the NativeCode Turnip ICD. Do not replace it with `--software` or llvmpipe.

Also keep `-noaudio` in the Phosh wrapper. A traced GUI launch reached Xwayland successfully but stalled during PulseAudio device initialization; the `-noaudio` launch continued processing X11 events. This is a GUI-startup compatibility setting, not a renderer fallback. If a workflow specifically needs Blender audio, test `-setaudio PulseAudio` from a terminal separately rather than removing the working menu default.

## Smoke tests

First verify Blender's bundled Python and engine list:

```bash
blender -b --factory-startup --python-expr \
  'import bpy; print("BLENDER_PYTHON_SMOKE", bpy.app.version_string, bpy.context.scene.render.engine)'
blender -b -E help
```

Run a small EEVEE render through the wrapper and require a non-empty PNG:

```bash
blender -b --factory-startup \
  --python-expr 'import bpy, math; s=bpy.context.scene; s.render.engine="BLENDER_EEVEE"; s.render.resolution_x=64; s.render.resolution_y=64; s.render.resolution_percentage=100; bpy.ops.mesh.primitive_cube_add(); bpy.ops.object.camera_add(location=(4,-4,3)); c=bpy.context.object; s.camera=c; c.rotation_euler=(math.radians(67),0,math.radians(43)); bpy.ops.object.light_add(type="POINT", location=(2,-2,4));' \
  -o /tmp/blender-smoke-render -F PNG -f 1
test -s /tmp/blender-smoke-render0001.png
file /tmp/blender-smoke-render0001.png
```

For GUI/editor validation, start the wrapper from the Phosh menu or a live Wayland session. Confirm that `gpu-launch --diagnose` still selects Turnip, that the Blender editor opens through Xwayland without a PulseAudio startup stall, and that the process environment has an empty `WAYLAND_DISPLAY` plus the expected Turnip/Vulkan variables. A successful background render proves the Vulkan render path, not every interactive display feature.

## Phosh application menu

Install `/home/flux/.local/share/applications/blender-turnip.desktop`:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=Blender (Turnip Vulkan/Xwayland)
GenericName=3D Creation Suite
Comment=Blender using NativeCode Turnip Vulkan through Phosh Xwayland
Exec=/home/flux/.local/bin/blender %U
TryExec=/home/flux/.local/bin/blender
Icon=/opt/blender/current/blender.svg
Terminal=false
Categories=Graphics;3DGraphics;
MimeType=application/x-blender;
StartupNotify=true
StartupWMClass=Blender
X-GNOME-UsesNotifications=true
```

Refresh the desktop database when `update-desktop-database` is available. Close old Blender processes before retesting after launcher changes.

## Installed verification record

This NativeCode guest has:

- Native ARM64 build installed at `/opt/blender/current`
- Blender 5.1.0, build hash `ae6d847d66fa`
- Archive checksum verified
- Shared-library dependencies resolved
- `libdecor` and `libdecor-gtk` installed for window initialization
- Bundled Python smoke test passed
- EEVEE engine listing passed
- 64×64 EEVEE render passed through `gpu-launch --auto` with `--gpu-backend vulkan`
- NativeCode GPU diagnostics: Turnip PASS and auto=turnip
- Default OpenGL background render: failed with a Mesa/libgallium segmentation fault; use the Vulkan launcher
- Native Wayland GUI launch: failed to map a window in the live Phoc session; use `WAYLAND_DISPLAY=` to select Xwayland
- PulseAudio GUI initialization: stalled before window startup; the Phosh launcher uses `-noaudio`; the final GUI smoke reached a 40-thread initialized Blender process through Xwayland
- Phosh entry: `/home/flux/.local/share/applications/blender-turnip.desktop` (Xwayland window path, no-audio startup, Vulkan/Turnip renderer)

## Recovery

If the build is incompatible, remove only the Blender aliases and move `/opt/blender/current` to a dated quarantine path. Restore the previous version from `/tmp` if one was saved. Do not delete Blender user settings or project files unless explicitly requested.

## References

- [Official Blender downloads](https://www.blender.org/download/)
- [Official Blender 5.2 release archive](https://download.blender.org/release/Blender5.2/)
- [ARM64 Blender release](https://github.com/lfdevs/blender-linux-arm64/releases)
""",
    )

    private fun addApps() = Skill(
        id = "add-apps",
        title = "Add Apps",
        summary = "Install and integrate an application in NativeCode.",
        source = "",
        body = """---
name: add-apps
description: Install and integrate a Linux application in the NativeCode ARM64 guest, including latest compatible release selection, PATH setup, Phosh menu registration, GPU backend testing, and bounded recovery from launch failures.
metadata:
  short-description: Add NativeCode ARM64 apps to PATH and Phosh
---

# Add Apps

Use this skill when adding an application to the NativeCode Alpine/Alpaquita ARM64 guest and making it usable from Bash, Zsh, and the Phosh application menu. The app is not complete until its actual requested operation has been smoke-tested. A group such as “PDF tools” is not permission to install every package in that group; install the components required by the selected workflow, plus explicitly requested optional components.

## NativeCode constraints

- Confirm the host and guest before changing anything:

  ```bash
  uname -m
  ldd --version | head -2
  id
  nativecode-agent ping
  nativecode-agent telemetry
  ```

- This guest is Alpine/Alpaquita `aarch64` with glibc, rootless PRoot, no systemd, and no usable direct access to Android `/dev/dri`, `/dev/video*`, or binder nodes. Do not use `systemctl`, `modprobe`, or raw device mounts.
- Use the configured native `apk` repositories only. Do not add foreign Alpine repositories just to satisfy an application dependency.
- Use NativeCode bridges for hardware:
  - `gpu-launch --auto` selects the detected route.
  - `gpu-launch --turnip` is the Qualcomm Adreno Vulkan route.
  - `gpu-launch --panvk` is the ARM Mali/Panfrost Vulkan route when the diagnostic says PanVK is available.
  - `gpu-launch --venus` is the NativeCode virtio/Venus route when its vtest socket and ICD are available.
  - `gpu-launch --virgl-angle` and `gpu-launch --software` are diagnostics or compatibility fallbacks, not GPU acceptance results.
- Do not claim GPU acceleration because an app starts. Verify the app's own renderer, Vulkan device, WebGL renderer, video decoder, or equivalent observable path.

## Detect before installing

Record the existing state and reuse a compatible installation when possible:

```bash
command -v app-name 2>/dev/null || true
type -a app-name 2>/dev/null || true
apk info -e package-name 2>/dev/null || true
gpu-launch --diagnose
find /opt /home/flux/.local/bin -maxdepth 3 -type f -name 'app-name*' -print 2>/dev/null
```

For every candidate, check:

```bash
file /path/to/app
ldd /path/to/app 2>&1 | grep 'not found' || true
/path/to/app --version 2>&1 | head -5
```

Verify CPU architecture, glibc ABI, required system libraries, graphics API, model/runtime versions, and licensing before changing anything. A version string alone is not a smoke test. Keep existing user settings and project files.

## Find and verify the latest compatible release

Search the upstream project's official release page or API at the time of installation. Prefer, in order:

1. An official native Linux ARM64/aarch64 package.
2. An official package-manager build from the configured guest repositories.
3. A reproducible, well-maintained ARM64 build whose source, release commit, archive checksum, and build environment are visible.
4. A source build using **Install Dev Utilities** when no usable binary exists.

Do not download an x86_64 Linux archive onto this aarch64 guest. “Latest” means the newest release that is both current and compatible, not merely the newest filename. Obtain checksums/signatures from the same upstream release and verify before extraction:

```bash
mkdir -p /tmp/app-download
curl -fL --retry 3 -o /tmp/app-download/app.tar.xz 'OFFICIAL_RELEASE_URL'
printf '%s  %s\n' 'UPSTREAM_SHA256' /tmp/app-download/app.tar.xz | sha256sum -c -
file /tmp/app-download/app.tar.xz
```

For package-manager software, inspect the candidate first and install only the selected package set:

```bash
apk policy package-name
apk search -v package-name
apk add package-name
```

If the latest compatible release cannot be verified or has no usable ARM64 route, report that as blocked instead of silently installing an older or foreign binary.

## Install with a reversible layout

For portable applications, use a versioned directory and a stable symlink. Preserve an existing target by moving it to a dated quarantine path, not deleting it:

```bash
install_root=/opt/app-name
versioned="${'$'}install_root/VERSION-aarch64"
mkdir -p "${'$'}install_root"
mkdir -p /tmp/app-name-stage
tar -xf /tmp/app-download/app.tar.xz -C /tmp/app-name-stage
install -d "${'$'}versioned"
# Copy or move only the verified application tree into "${'$'}versioned".
ln -sfn "${'$'}versioned" "${'$'}install_root/current"
```

Use the application's bundled launcher when it sets `LD_LIBRARY_PATH` or other required variables. Keep a small wrapper in `/home/flux/.local/bin` when the app needs a stable GPU/display/audio configuration; do not bake machine-specific paths into project scripts.

## PATH for Bash and Zsh

Add the user executable directory once to both `/home/flux/.bashrc` and `/home/flux/.zshrc`, preserving existing PATH entries:

```sh
# NativeCode user-installed executables
case ":${'$'}{PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:${'$'}{PATH}" ;;
esac
```

After editing, test fresh shells rather than only the current shell:

```bash
bash -lc 'command -v app-name; app-name --version | head -3'
zsh -lc 'command -v app-name; app-name --version | head -3'
```

Use an absolute `Exec=` path in the desktop entry even when PATH is configured; Phosh may start applications with a smaller environment than an interactive shell.

## GPU backend selection and verification

Start with the diagnostic and select only hardware routes that are actually available:

```bash
gpu-launch --diagnose
```

Use this decision order:

```text
Adreno/Qualcomm + turnip=PASS  -> gpu-launch --turnip or --auto
Mali/Panfrost + panvk=PASS     -> gpu-launch --panvk or --auto
Virtio vtest + venus=PASS      -> gpu-launch --venus or --auto
No validated hardware route    -> stop as GPU-blocked, or obtain approval for software fallback
```

For each candidate route, run a bounded test with a small representative workload. Record exit status and the app-specific renderer output:

```bash
gpu-launch --turnip app-name --version
gpu-launch --panvk app-name --version
gpu-launch --auto app-name --version
```

For a GUI app, do not leave multiple test instances running. Use a short, bounded launch test, then inspect its process state and log. Stop only processes started by the test; never kill a user's unsaved interactive session. If a route reports software rendering, mark that route as failed for a GPU-required task rather than relabeling it as accelerated.

## Phosh menu integration

Create a wrapper when the app needs environment variables, then install a desktop entry under `/home/flux/.local/share/applications`. Use the desktop-entry specification's supported fields and field codes:

```ini
[Desktop Entry]
Version=1.0
Type=Application
Name=App Name (NativeCode GPU)
GenericName=Application type
Comment=App Name using the validated NativeCode graphics route
Exec=/home/flux/.local/bin/app-name %U
TryExec=/home/flux/.local/bin/app-name
Icon=/opt/app-name/current/app.svg
Terminal=false
Categories=Utility;
StartupNotify=true
StartupWMClass=AppName
```

Use `%U`, `%F`, or no field code only when the app accepts that form. Validate and refresh when the utilities exist:

```bash
command -v desktop-file-validate >/dev/null && \
  desktop-file-validate /home/flux/.local/share/applications/app-name.desktop || true
command -v update-desktop-database >/dev/null && \
  update-desktop-database /home/flux/.local/share/applications || true
```

For apps whose native Wayland launch fails, test an Xwayland route by setting `WAYLAND_DISPLAY=` in that app's wrapper only. Do not impose this globally. For Blender, this is an observed compatibility setting; Blender's own manual documents the empty variable as its X11 selector. Keep app-specific audio workarounds, such as Blender's `-noaudio`, in the wrapper and explain why.

## Phosh/GPU test loop

Run this finite loop after installation. “Loop until it runs” means iterate through evidence-based fixes and compatible backends, not an unbounded battery-draining restart loop:

1. Send `nativecode-agent toast` and query `nativecode-agent telemetry`.
2. Run the CLI/version and representative headless smoke test.
3. Test `--auto`, then the validated vendor backend (`--turnip` or `--panvk`) with the app wrapper.
4. Launch the exact desktop wrapper in the live Phosh environment. Wait a bounded interval, then check whether the process remains initialized, maps a window, and reports the expected renderer.
5. If it fails, save stdout/stderr, the app crash report, `WAYLAND_DEBUG=1` or app logging output, `gpu-launch --diagnose`, and relevant Phosh log lines. Classify the failure as missing library, display protocol, audio/session initialization, GPU driver, incompatible binary, or application bug.
6. Apply one narrow change, retest the same smoke test, and avoid stacking unverified environment variables. Try the next compatible backend only when the diagnostic supports it.
7. Stop after the tested route works or after all validated routes are exhausted. Do not leave orphaned GUI processes; notify the user if an old process must be closed manually because it may contain unsaved work.
8. Send `nativecode-agent notify` with the final route, version, smoke result, and any known limitation.

Useful diagnostics:

```bash
gpu-launch --diagnose
nativecode-agent telemetry
ps -o pid,ppid,stat,etime,args | grep app-name
find /tmp /home/flux -maxdepth 4 -type f -iname '*app-name*' -print 2>/dev/null
```

For a suspected startup hang, use `strace` only for a bounded diagnostic and clean up the test process afterward. A Phosh startup timeout with a live process is not the same as a crash; distinguish it from a non-zero exit and a crash backtrace.

## Completion record

Record these facts in the app-specific skill or handoff:

- exact release, architecture, source URL, checksum/signature result, and install path;
- system packages and linked dependencies installed;
- Bash and Zsh command resolution;
- desktop-entry path and wrapper environment;
- selected GPU backend and app-reported renderer/device;
- CLI/headless smoke result and Phosh GUI result;
- any disabled feature, such as audio, and the command needed to test it separately;
- unresolved limitations and the safe recovery path.

## References

- [Blender Linux windowing environment](https://docs.blender.org/manual/en/latest/getting_started/installing/linux_windowing_environment.html) — Wayland diagnostics and the app-specific `WAYLAND_DISPLAY=` X11 selector.
- [Freedesktop Desktop Entry Specification](https://specifications.freedesktop.org/desktop-entry/latest-single/) — `Exec`, `TryExec`, field codes, and Phosh menu entries.
- [Mesa Freedreno and Turnip documentation](https://docs.mesa3d.org/drivers/freedreno.html) — Adreno/Turnip scope and capabilities.
- [Mesa Panfrost/PanVK project](https://panfrost.freedesktop.org/) — PanVK scope and current compatibility limitations.
- [Mesa Venus documentation](https://docs.mesa3d.org/drivers/venus.html) — virtio/Venus requirements.

""",
    )

    private fun installAdb() = Skill(
        id = "install-adb",
        title = "Install ADB",
        summary = "Set up ARM64 ADB and authorized device connections.",
        source = "",
        body = """---
name: install-adb
description: Install and verify a prebuilt ARM64 Android Debug Bridge in the NativeCode guest without compiling, including architecture checks, Bash/Zsh PATH setup, daemon diagnostics, and authorized device connections.
metadata:
  short-description: Install ADB for NativeCode ARM64
---

# Install ADB

Install only Android Debug Bridge when ADB is requested. Check **Install Android Tools** first: reuse its compatible `adb` if it already works. Do not interpret this as permission to install every Android SDK component.

## NativeCode constraints

The target is an ARM64 (`aarch64`) Alpaquita/Alpine-style PRoot guest running on Android. There is no systemd, and the guest may not have direct access to the host USB device. Do not mount or chmod Android device nodes. ADB does not use the GPU, so `gpu-launch`, Turnip, PanVK, Vulkan, and Phosh menu registration are not applicable to this CLI tool.

Before changing anything, collect:

```sh
uname -m
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version | head -1
command -v adb || true
adb version 2>/dev/null || true
file "${'$'}(command -v adb 2>/dev/null)" 2>/dev/null || true
apk search '*adb*' 2>/dev/null || true
```

Reject an existing x86_64 or dynamically linked binary that cannot run in the guest. Record its path before replacing it; preserve it as a versioned backup rather than deleting it.

## Select a prebuilt ARM64 release

Google's current Platform-Tools release may be newer, but its Linux download is x86_64-only. Never install that archive on this ARM64 host. Do not compile ADB for this workflow.

Use the newest verified prebuilt ARM64 archive that is actually available. The currently verified source is the community `android-sdk-linux-arm64` release:

```text
https://github.com/zhuwanhong/android-sdk-linux-arm64/releases/download/v1.0.0/android-sdk-linux-arm64-36.0.0-ours.tar.gz
SHA-256: f82700229b117aca7720c227bec1e62259071b5e1f0d60e0569244149aee357a
```

This archive is labeled SDK/build-tools `36.0.0`, but its provenance reports that the host `adb` and `fastboot` were built from AOSP `platform-tools-35.0.2`; verify the embedded provenance and report that distinction. A package version label is not proof that its `adb` is that version. If a newer ARM64 prebuilt becomes available, verify its release asset, checksum/signature, and ELF architecture before selecting it. Do not silently fall back to a source build.

Download to a temporary directory, verify before extraction, and inspect the archive:

```sh
mkdir -p /tmp/adb-arm64
curl -fL --retry 3 -o /tmp/adb-arm64/platform-tools-arm64.tar.gz \
  'https://github.com/zhuwanhong/android-sdk-linux-arm64/releases/download/v1.0.0/android-sdk-linux-arm64-36.0.0-ours.tar.gz'
printf '%s  %s\n' \
  f82700229b117aca7720c227bec1e62259071b5e1f0d60e0569244149aee357a \
  /tmp/adb-arm64/platform-tools-arm64.tar.gz | sha256sum -c -
tar -tzf /tmp/adb-arm64/platform-tools-arm64.tar.gz | head -80
```

## Install without breaking the SDK

Keep installations versioned and make the selected one explicit. If `/opt/android-sdk/platform-tools` already exists, identify its architecture and move it to a recoverable versioned backup before creating the active symlink. Do not overwrite an unknown installation in place.

The extracted `platform-tools/adb` must pass all of these checks before activation:

```sh
file platform-tools/adb                 # must say ARM aarch64
ldd platform-tools/adb                  # static is acceptable; no missing libs
platform-tools/adb version
```

Install the verified platform-tools directory under a versioned path such as `/opt/android-sdk/platform-tools-arm64-35.0.2`, then point `/opt/android-sdk/platform-tools` at it. Preserve the archive's companion files if the selected binary expects them. Expose only the requested command through the user-local PATH:

```sh
mkdir -p /home/flux/.local/bin
cat > /home/flux/.local/bin/adb <<'EOF'
#!/bin/sh
exec /opt/android-sdk/platform-tools/adb "${'$'}@"
EOF
chmod 755 /home/flux/.local/bin/adb
```

For an existing system-wide installation, a symlink is also acceptable when its target is verified ARM64. Keep `fastboot` and other bundled tools out of PATH unless they were separately requested.

## Bash and Zsh PATH

Ensure both interactive shells contain the same idempotent block. Do not add duplicate exports or replace the user's unrelated PATH entries:

```sh
# NativeCode user-installed executables
case ":${'$'}{PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:${'$'}{PATH}" ;;
esac
```

Place it in `/home/flux/.bashrc` and `/home/flux/.zshrc` as applicable, then verify new shells rather than trusting the current shell:

```sh
bash -lc 'command -v adb; adb version'
zsh -lc 'command -v adb; adb version'   # when zsh is installed
```

The resolved command must be `/home/flux/.local/bin/adb` (or the deliberately selected verified path), and `file "${'$'}(command -v adb)"` must identify ARM64.

## ADB server and connection smoke test

ADB starts its own user daemon; do not use `systemctl`. Run a bounded local smoke test:

```sh
adb start-server
adb server-status
adb devices -l
adb kill-server
```

`server-status` must report the selected ARM64 executable and a successful server. An empty device list is an installation success when the guest has no authorized Android device; it is not evidence that ADB itself failed. Do not leave a daemon running unnecessarily after testing.

For a real device, require Android Developer Options and USB debugging, then accept the device's RSA authorization prompt. For Wireless debugging, use the exact values shown by Android:

```sh
adb pair HOST:PAIR_PORT
adb connect HOST:CONNECT_PORT
adb devices -l
```

Never bypass authentication or claim a connection without an authorized serial in `adb devices`. If NativeCode does not expose USB to the PRoot guest, use an authorized network/Wireless Debugging connection or the host's supported bridge; do not attempt raw `/dev/binder`, `/dev/bus/usb`, or `/dev/dri` workarounds.

## Completion record

Record the source URL, archive checksum, embedded ADB version, ELF architecture, selected install path, PATH shell files, `server-status` result, and whether an authorized device was visible. State separately that no Phosh `.desktop` entry and no GPU test were required because ADB is a command-line diagnostic tool.

References:

- [Android Platform-Tools release notes](https://developer.android.com/tools/releases/platform-tools)
- [Android ADB documentation](https://developer.android.com/tools/adb)
- [Verified ARM64 SDK release and provenance](https://github.com/zhuwanhong/android-sdk-linux-arm64)
""",
    )

    private fun installHyperframes() = Skill(
        id = "install-hyperframes",
        title = "Install HyperFrames",
        summary = "Set up HyperFrames and hardware media workflows.",
        source = "",
        body = """---
name: install-hyperframes
description: Install and operate HyperFrames on NativeCode ARM64 for deterministic HTML video rendering, strict Turnip/Zink browser-GPU validation, and NativeCode MediaCodec hardware encoding when supported.
metadata:
  short-description: Install HyperFrames with GPU and hardware video paths
---

# Install HyperFrames

HyperFrames renders HTML compositions frame by frame in Chromium and assembles them with FFmpeg. Treat browser rendering and video encoding as separate acceleration stages:

~~~text
HTML/GSAP/CSS/Three.js → Chromium + WebGL GPU capture → frames → encoder → MP4/WebM
~~~

On NativeCode, browser graphics use Turnip Vulkan through Zink. The final encoder must be verified independently; an application starting is not proof of acceleration.

## Prerequisites and detection

This is a rootless ARM64 (aarch64) Alpaquita/Alpine-style PRoot guest with glibc and no systemd. Use NativeCode bridges instead of raw Android devices:

- gpu-launch --auto for browser graphics
- nativecode-media for Android MediaCodec hardware decode/encode
- nativecode-agent for telemetry, previews, and status
- no direct /dev/dri, /dev/video*, KGSL, binder, or USB access

HyperFrames needs Node.js 22+, Chromium, FFmpeg/ffprobe, fontconfig, and fonts required by the composition. Docker is not required for local rendering. It is a CLI tool, so do not create a Phosh desktop entry; use nativecode-agent open-link for previews.

~~~sh
uname -m
node --version 2>/dev/null || true
npm --version 2>/dev/null || true
command -v hyperframes || true
hyperframes --version 2>/dev/null || true
command -v chromium || command -v google-chrome || true
command -v ffmpeg || true
command -v ffprobe || true
fc-match sans 2>/dev/null || true
gpu-launch --diagnose
nativecode-media capabilities
~~~

Reject x86_64 binaries. Verify an existing tool's real path and ELF architecture before reinstalling it.

## Install the latest CLI

The tested npm release is HyperFrames 0.8.60 and it requires Node >=22:

~~~sh
npm view hyperframes version engines --json
npm install --global hyperframes@latest
hyperframes --version
~~~

For a reproducible project, use a pinned CLI script or npx hyperframes@approved-version. HyperFrames' upstream authoring skills can be refreshed from GitHub when requested:

~~~sh
npx skills add heygen-com/hyperframes
npx hyperframes skills update
~~~

Do not copy an old skill snapshot over the current installation. The installed CLI is authoritative; use hyperframes command --help.

## Install FFmpeg without building

Prefer the configured Alpaquita package:

~~~sh
apk search | grep -E '^ffmpeg(${'$'}|-)'
apk policy ffmpeg
~~~

If no compatible ARM64 package exists, use a verified prebuilt binary rather than compiling. The tested fallback is the BtbN Linux ARM64 GPL build for the FFmpeg 9.0 line:

~~~text
URL: https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-linuxarm64-gpl-9.0.tar.xz
SHA-256: 796f86435604248a58253adc3f92ae3865a15a7d99777b6e901e92f55b3a3d30
~~~

Download and verify before activation. Keep a versioned tree under /opt/ffmpeg/<version>-arm64 and expose only verified commands in /home/flux/.local/bin:

~~~sh
mkdir -p /tmp/hyperframes-ffmpeg
curl -fL --retry 3 -o /tmp/hyperframes-ffmpeg/ffmpeg-arm64.tar.xz \
  'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n9.0-latest-linuxarm64-gpl-9.0.tar.xz'
printf '%s  %s\n' \
  796f86435604248a58253adc3f92ae3865a15a7d99777b6e901e92f55b3a3d30 \
  /tmp/hyperframes-ffmpeg/ffmpeg-arm64.tar.xz | sha256sum -c -
mkdir -p /tmp/hyperframes-ffmpeg/extracted
tar -xJf /tmp/hyperframes-ffmpeg/ffmpeg-arm64.tar.xz -C /tmp/hyperframes-ffmpeg/extracted
~~~

Keep ffmpeg and ffprobe from the same build. Check both:

~~~sh
file /opt/ffmpeg/current/bin/ffmpeg /opt/ffmpeg/current/bin/ffprobe
ldd /opt/ffmpeg/current/bin/ffmpeg 2>/dev/null || true
ffmpeg -version | head -3
ffprobe -version | head -2
~~~

An encoder named *_vulkan, *_vaapi, *_nvenc, or *_amf is not automatically usable with Android Adreno; test it before selecting it.

## Bash and Zsh PATH

Put this idempotent block in /home/flux/.bashrc and /home/flux/.zshrc as applicable. Preserve unrelated PATH entries:

~~~sh
# NativeCode user-installed executables
case ":${'$'}PATH:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:${'$'}PATH" ;;
esac
~~~

Verify fresh shells:

~~~sh
bash -lc 'command -v hyperframes; command -v ffmpeg; command -v ffprobe; hyperframes --version'
zsh -lc 'command -v hyperframes; command -v ffmpeg; command -v ffprobe; hyperframes --version'
file "${'$'}(command -v ffmpeg)" "${'$'}(command -v ffprobe)"
~~~

## GPU-backed Chromium capture

Use the NativeCode Chromium wrapper. It must call gpu-launch --auto, select ANGLE Vulkan/Zink, enable GPU, and disable software rasterization:

~~~sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
~~~

If discovery selects another browser, set:

~~~sh
export HYPERFRAMES_BROWSER_PATH=/home/flux/.local/bin/google-chrome
~~~

Run a GPU-only check. Accept only browserGpuMode probe → hardware and a renderer containing ANGLE, Zink, Vulkan, and Turnip. Reject SwiftShader, llvmpipe, Software, or a missing renderer:

~~~sh
gpu-launch --diagnose
HYPERFRAMES_BROWSER_PATH=/home/flux/.local/bin/google-chrome \
  hyperframes render ./project --browser-gpu --no-gpu --output /tmp/hyperframes-gpu-check.mp4
~~~

Here --browser-gpu enables Chromium/WebGL GPU capture. --no-gpu intentionally disables HyperFrames' separate FFmpeg encoder probe while retaining browser GPU. HyperFrames --gpu targets NVENC, VideoToolbox, AMF, VA-API, or QSV, not NativeCode MediaCodec; do not use it as proof of Adreno video encoding.

A regular Chrome build may lack HeadlessExperimental.beginFrame and fall back to screenshot capture. That is acceptable when the GPU probe is hardware-backed. An ARM64-compatible chrome-headless-shell may improve capture performance, but verify its architecture and Turnip/Zink renderer before using it.

## NativeCode hardware encode/decode

Inspect codecs first:

~~~sh
nativecode-media capabilities
~~~

Accept an encoder only when it reports is_hardware:true and software_only:false. The tested Snapdragon/Adreno device exposes hardware AVC and HEVC through c2.qti.avc.encoder and c2.qti.hevc.encoder; AV1 and VP9 encoders are software-only. Capabilities vary by device, Android build, resolution, and profile.

HyperFrames does not directly hand frames to MediaCodec. For a hardware-encoded deliverable, render a PNG sequence with browser GPU enabled, convert frames to NV12, and stream them to the bridge:

~~~sh
hyperframes render ./project \
  --browser-gpu --no-gpu --format png-sequence --output /tmp/hyperframes-frames

ffmpeg -hide_banner -loglevel error \
  -framerate 30 -pattern_type glob -i '/tmp/hyperframes-frames/*.png' \
  -pix_fmt nv12 -f rawvideo - |
  nativecode-media encode_stream \
  --width 1920 --height 1080 --fps 30 --bitrate 8000000 \
  --mime video/avc --output /tmp/hyperframes-h264.mp4
~~~

Use --mime video/hevc only after capabilities proves a hardware HEVC encoder. Retain the MediaBridge JSON and probe the result:

~~~sh
ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name,width,height,avg_frame_rate,nb_frames \
  -of default=nw=1 /tmp/hyperframes-h264.mp4
~~~

The bridge output is normally video-only. For audio, mix separately and remux with -c:v copy; do not re-encode the hardware video in software. If the required codec, profile, dimensions, or color format is unsupported, report it instead of silently using libx264/libx265.

Test source-video hardware decode separately:

~~~sh
nativecode-media benchmark_file --path /path/to/source.mp4 --mode surface
~~~

Require hardware decoding and nonzero decoded frames. Browser playback, MediaCodec decoding, browser WebGL, and final encoding are separate claims.

## Fonts, preview, and smoke test

Check language coverage before rendering; fallback can change line wrapping:

~~~sh
fc-match sans
fc-match 'Noto Sans'
fc-list :lang=ja family | head
fc-list :lang=hi family | head
~~~

Install only required Noto or other language families through Install Fonts, preserve licenses, and prefer local fonts for reproducibility. HyperFrames may cache Google Fonts; record the cache and families used.

~~~sh
hyperframes doctor --json
hyperframes check . --json
hyperframes preview
~~~

If preview does not open automatically:

~~~sh
nativecode-agent open-link \
  --url 'http://localhost:3000' \
  --title 'HyperFrames preview' \
  --message 'Open the local HyperFrames preview'
~~~

Run a short render with --browser-gpu --no-gpu, inspect the hardware WebGL renderer, probe the output with ffprobe, and separately run one AVC or HEVC MediaBridge encode. Use --workers 1 or --low-memory-mode when telemetry reports low memory or elevated temperature; each worker is a separate Chrome process.

~~~sh
nativecode-agent telemetry
nativecode-agent status --key hyperframes --text 'GPU capture and hardware-encode validation running' --progress 10
~~~

Do not leave orphaned Chrome, FFmpeg, or MediaBridge processes. Do not run an unbounded retry loop.

## Completion record

Record:

- HyperFrames and Node versions
- Chromium path and GPU renderer
- gpu-launch --diagnose result
- FFmpeg/ffprobe version, architecture, and checksum
- font families and language coverage
- render format, workers, and output probe
- NativeCode MIME, codec name, is_hardware, software_only, dimensions, and measured rate
- whether audio was remuxed without re-encoding video
- unsupported codecs or headless-capture limitations

References:

- https://github.com/heygen-com/hyperframes
- https://github.com/heygen-com/hyperframes/blob/main/docs/guides/rendering.mdx
- https://github.com/heygen-com/hyperframes/blob/main/docs/packages/cli.mdx
- https://github.com/heygen-com/hyperframes/blob/main/packages/engine/README.md
- https://ffmpeg.org/download.html
- https://github.com/BtbN/FFmpeg-Builds/releases
- https://github.com/notofonts

""",
    )

    private fun installMoneyprinter() = Skill(
        id = "install-moneyprinter",
        title = "Install MoneyPrinterTurbo",
        summary = "Set up MoneyPrinterTurbo and its local WebUI.",
        source = "",
        body = """---
name: install-moneyprinter
description: Install and run MoneyPrinterTurbo on NativeCode ARM64 with its locked Python environment, FFmpeg path, WebUI launcher, provider boundaries, and separately verified GPU and MediaCodec workflows.
metadata:
  short-description: Install MoneyPrinterTurbo for ARM64
---

# Install MoneyPrinterTurbo

Install the latest upstream MoneyPrinterTurbo short-video application and its pinned Python dependencies. Keep three acceleration claims separate:

~~~text
Streamlit WebUI → Chromium/Turnip GPU for display
Python/MoviePy/faster-whisper → CPU unless a supported backend is proven
Final video encode → FFmpeg software or NativeCode MediaCodec hardware post-process
~~~

MoneyPrinterTurbo's built-in FFmpeg codec list targets libx264, NVENC, AMF, QSV, Media Foundation, and VideoToolbox. Android Adreno does not provide NVENC/AMF/QSV in this guest. Do not configure an unavailable codec and call the fallback accelerated.

## NativeCode constraints and detection

The target is rootless ARM64 (aarch64) Alpaquita/Alpine-style PRoot with glibc and no systemd. Use apk only for compatible guest packages and NativeCode bridges for Android hardware. Never access raw /dev/dri, /dev/video*, KGSL, binder, or USB nodes.

Detect existing components:

~~~sh
uname -m
python3 --version
uv --version
command -v moneyprinterturbo || true
command -v ffmpeg
ffmpeg -version | head -1
fc-match sans
gpu-launch --diagnose
nativecode-media capabilities
~~~

Reject x86_64 binaries and Python environments built for another architecture. Do not install Docker just for this app; local deployment is the supported route in this guest.

## Select and obtain the upstream release

Use the official repository:

~~~text
https://github.com/harry0703/MoneyPrinterTurbo
~~~

At the verified setup date, the latest release is v1.3.7 and pyproject.toml declares version 1.3.7 with Python >=3.11. Prefer the release tag over an unreviewed moving branch. Verify the tag, archive checksum, and source manifest. If a shallow Git clone reports missing objects, preserve the failed checkout and use the GitHub tag source archive; do not activate a partial tree.

A verified source archive at setup time was:

~~~text
https://github.com/harry0703/MoneyPrinterTurbo/archive/refs/tags/v1.3.7.tar.gz
SHA-256: 8253255062d5100f030f344575e543c8739091ca5f449c4727c2f715f0571081
~~~

Keep the application versioned and provide a stable path:

~~~sh
mkdir -p /opt/moneyprinterturbo /tmp/moneyprinterturbo
curl -fL --retry 3 -o /tmp/moneyprinterturbo/v1.3.7.tar.gz \
  'https://github.com/harry0703/MoneyPrinterTurbo/archive/refs/tags/v1.3.7.tar.gz'
printf '%s  %s\n' \
  8253255062d5100f030f344575e543c8739091ca5f449c4727c2f715f0571081 \
  /tmp/moneyprinterturbo/v1.3.7.tar.gz | sha256sum -c -
mkdir -p /tmp/moneyprinterturbo/extracted
tar -xzf /tmp/moneyprinterturbo/v1.3.7.tar.gz -C /tmp/moneyprinterturbo/extracted
mv /tmp/moneyprinterturbo/extracted/MoneyPrinterTurbo-1.3.7 /opt/moneyprinterturbo/v1.3.7
ln -s /opt/moneyprinterturbo/v1.3.7 /opt/moneyprinterturbo/current
~~~

If current already exists, inspect its version before changing it and preserve it under a versioned backup.

## Python environment and locked dependencies

MoneyPrinterTurbo's primary dependency manifest is pyproject.toml and uv.lock. Use Python 3.11 on ARM64 even when a newer system Python is present; this is the upstream recommended compatibility target:

~~~sh
uv python install 3.11
cd /opt/moneyprinterturbo/current
~~~

Install the production lockfile and the declared TwelveLabs extra. Do not install the development group merely to run the app:

~~~sh
UV_CACHE_DIR=/tmp/mpt-uv-cache-fresh \
UV_LINK_MODE=copy \
uv sync --frozen --no-dev --extra twelvelabs --link-mode=copy
~~~

The fresh cache and copy mode matter in PRoot: cross-filesystem hardlinks can leave package files unreadable. If a previous sync is broken, move its explicit .venv directory to a versioned recovery name and recreate it; do not delete it blindly.

The current core pins include MoviePy 2.2.1, Streamlit 1.59.1, faster-whisper 1.1.0, Edge TTS 7.2.7, FastAPI 0.136.3, LiteLLM 1.86.2, and the other versions in the upstream lockfile. Verify imports and the project configuration:

~~~sh
/opt/moneyprinterturbo/current/.venv/bin/python - <<'PY'
import sys
from app.config import config
from app.utils import utils
print("python", sys.version.split()[0])
print("project_version", config.project_version)
print("ffmpeg", utils.get_ffmpeg_binary())
print("ffmpeg_ready", utils.check_ffmpeg_ready())
PY
~~~

Do not download Whisper, TTS, music, or image models during installation unless that feature is selected. Package extras are not model weights. Keep API keys empty until the user supplies and authorizes a provider.

## FFmpeg and fonts

MoneyPrinterTurbo resolves FFmpeg in this order: app configuration, PATH, imageio-ffmpeg, then the literal ffmpeg fallback. Make the verified ARM64 FFmpeg the first PATH result and optionally set app.ffmpeg_path to its absolute path in config.toml:

~~~sh
command -v ffmpeg
ffmpeg -version | head -3
command -v ffprobe
fc-match sans
fc-match 'Noto Sans'
~~~

Install required language families through Install Fonts and retain licensing information. Font fallback changes subtitle wrapping and can change the output.

Do not confuse an FFmpeg encoder list with working hardware. An FFmpeg build can contain h264_nvenc or h264_amf while the Android guest has no corresponding device. Test the actual output and record the selected encoder.

## Bash, Zsh, and launcher PATH

Both /home/flux/.bashrc and /home/flux/.zshrc should contain one idempotent block:

~~~sh
# NativeCode user-installed executables
case ":${'$'}PATH:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:${'$'}PATH" ;;
esac
~~~

Provide /home/flux/.local/bin/moneyprinterturbo as the application launcher. It should run /opt/moneyprinterturbo/current/webui.sh with the verified project venv, bind to 127.0.0.1 by default, wait for /_stcore/health, and call nativecode-agent open-link. Launch it in an isolated process group so stopping the menu entry does not leave Streamlit orphaned.

Verify fresh shells:

~~~sh
bash -lc 'command -v moneyprinterturbo; command -v ffmpeg'
zsh -lc 'command -v moneyprinterturbo; command -v ffmpeg'
sh -n /home/flux/.local/bin/moneyprinterturbo
~~~

## Browser preview

MoneyPrinterTurbo's interface is a local Streamlit WebUI, not a native desktop window. Do not add it to the Phosh menu. Start the server, then open the URL in the Android browser:

~~~sh
MPT_WEBUI_HOST=127.0.0.1 MPT_WEBUI_PORT=8501 moneyprinterturbo
~~~

The browser opened by the NativeCode link uses the existing Chromium Turnip wrapper. Verify the browser renderer separately with the Chromium skill; the Streamlit process itself does not render video frames through the GPU.

If NativeCode does not open the browser automatically, use:

~~~sh
nativecode-agent open-link \
  --url 'http://127.0.0.1:8501' \
  --title 'MoneyPrinterTurbo' \
  --message 'MoneyPrinterTurbo WebUI is ready'
~~~

## GPU, transcription, and hardware video

Run the diagnostic before a heavy job:

~~~sh
gpu-launch --diagnose
nativecode-media capabilities
nativecode-agent telemetry
~~~

The WebUI browser can use Chromium's Turnip/Zink GPU path when opened through the verified Chromium wrapper. That accelerates browser display and any explicitly GPU-backed browser preview; it does not make MoviePy, Python composition, or FFmpeg libx264 hardware-encoded.

faster-whisper uses CTranslate2. On this ARM64 NativeCode guest, do not force CUDA or another unavailable accelerator. Start with CPU settings that match the installed wheel and explicitly selected model. A model download is a separate, potentially large operation; record its source and checksum when reproducibility matters.

MoneyPrinterTurbo's built-in video_codec setting accepts its upstream whitelist. Do not set h264_nvenc, h264_amf, h264_qsv, or h264_videotoolbox unless the exact encoder and hardware device are proven. The application may fall back to libx264 after an encoder failure; log and report that fallback.

NativeCode hardware encoding is a separate post-processing route because MoneyPrinterTurbo does not directly call nativecode-media. For a final MP4, first obtain the generated video and inspect width, height, frame rate, and audio. Then stream decoded NV12 frames to MediaCodec:

~~~sh
nativecode-media capabilities
ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height,avg_frame_rate \
  -of default=nw=1 /path/to/moneyprinter-output.mp4

ffmpeg -hide_banner -loglevel error -i /path/to/moneyprinter-output.mp4 \
  -pix_fmt nv12 -f rawvideo - |
  nativecode-media encode_stream \
  --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
  --mime video/avc --output /tmp/moneyprinter-video-hw.mp4
~~~

Replace dimensions and FPS with the probed values. Use HEVC only when capabilities reports a hardware HEVC encoder. Retain the bridge response and require is_hardware:true and software_only:false. Remux the original audio with -c:v copy if needed; never re-encode the hardware video in a software pass. This route performs guest decoding with FFmpeg and Android hardware encoding; it is not proof of hardware decoding.

For source-video decoding, separately test:

~~~sh
nativecode-media benchmark_file --path /path/to/source.mp4 --mode surface
~~~

Do not accept software output as a hardware result. On the tested device, AVC and HEVC encoders are hardware-backed, while AV1 and VP9 encoders reported by the bridge are software-only.

## Smoke tests and completion

Use bounded tests without API keys or model downloads:

~~~sh
/opt/moneyprinterturbo/current/.venv/bin/python -c \
  'from app.config import config; print(config.project_version)'
MPT_WEBUI_HOST=127.0.0.1 MPT_WEBUI_PORT=18501 \
  /opt/moneyprinterturbo/current/webui.sh
~~~

From another shell, require:

~~~sh
curl -fsS http://127.0.0.1:18501/_stcore/health
~~~

Stop the server after the smoke test. Before a real generation, check telemetry and disk space, then run one short generation with a selected provider and asset source. Keep worker/thread counts conservative on this device; MoneyPrinterTurbo's FFmpeg thread setting is not a hardware-encode switch.

Record:

- upstream tag and source checksum
- Python and uv versions
- lockfile sync command and enabled extras
- FFmpeg/ffprobe path, version, and architecture
- font families and language coverage
- WebUI URL and health result
- Chromium GPU renderer, if tested
- selected LLM/TTS/material provider and whether credentials were supplied
- Whisper model/device, if selected
- final video encoder, MediaBridge JSON, and whether audio was remuxed
- any explicit CPU fallback or unsupported hardware route

References:

- https://github.com/harry0703/MoneyPrinterTurbo
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/README-en.md
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/pyproject.toml
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/requirements.txt
- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/app/services/video.py
""",
    )

    private fun installAndroidTools() = Skill(
        id = "install-android-tools",
        title = "Install Android Tools",
        summary = "Check or set up NativeCode Android build tools.",
        source = "",
        body = """---
name: "install-android-tools"
description: "Plan/check NativeCode Android development pack, JDK, SDK/build tools and Gradle integration for NativeCode ARM64. Use when this dependency is missing or setup is explicitly requested; installation is deferred in the current authoring task."
compatibility: "NativeCode ARM64 PRoot; OpenCode skill discovery"
metadata:
  title: "Install Android Tools"
  category: "INSTALL"
  group: "Dependency setup (later)"
  engine: "NativeCode Android development pack, JDK, SDK/build tools and Gradle integration"
  status: "deferred; not-installed-by-this-pack"
  dependencies: ""
  reviewed: "2026-09-22"
---

# Install Android Tools

**Category:** INSTALL PROGRAMS  |  **Mechanism:** NativeCode Android development pack, JDK, SDK/build tools and Gradle integration

**Current phase: documentation only. Do not install, download, pair a device, start a service, or change the runtime while creating this skill pack.** These instructions describe a later explicitly authorized setup/repair task.

## Detect before installing
Reuse the current task environment inventory and inspect existing tools. Source code mentioning a tool does not prove it is installed on the device.

```sh
command -v java; command -v javac; command -v adb; test -d /opt/android-sdk && printf "SDK directory exists\n"; command -v gradle
```

### NativeCode fast path
Work in the current project; use `/home/flux/repos/<project>` only for a new project. Run as `flux`, not root. Reuse this task's recorded environment check; otherwise check `uname -m`, `/etc/os-release` and `getconf GNU_LIBC_VERSION` (or `ldd --version`) once. The uploaded source favors Alpaquita/glibc but retains an Alpine fallback. Never assume Ubuntu/apt, Docker, systemd, CUDA, x86_64 tools, or host Bionic binaries.

Use the existing inbuilt Host Agent/GPU/Video skills when those capabilities are actually needed; do not replace them. Before heavy work, use `nativecode-agent telemetry` when available and start with conservative concurrency. GUI work uses the existing NativeCode desktop and `gpu-launch --auto`; a driver name or extension list is not evidence of a working workload.

## Prerequisite skills
No prerequisite skill is required. Check whether the named tool already works before any future setup.

## Future setup procedure
1. First inspect the NativeCode development-pack status and existing /opt/android-sdk. A source setup script lists expected tools but does not prove a user's rootfs contains them.
2. Prefer NativeCode's existing verified dev-pack acquisition/repair UI and runtime policy. Do not change rootfs delivery or fetch a generic desktop SDK as part of a skill.
3. Check JDK, SDK platform, ARM64 aapt2, zipalign/apksigner and required NDK against the project. Preserve android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.1.0/aapt2 when that installed path is valid.
4. The bundled setup_android_dev_alpine.sh is a manual repair/verification script, not a promise to download missing tools. Some comments mention musl; detect the real Alpaquita glibc environment before selecting artifacts.
5. Confirm Gradle wrapper or Gradle installation is compatible with the project's build configuration.

## Real completion checks
- Build a minimal debug APK with a compatible project wrapper and inspect it; record every missing component.

## Record and rollback
Record the actual architecture/libc, source URL, pinned version, checksum when a binary/model is acquired, install path, added packages/files, and test result in a user-owned receipt. Do not write secrets into it. Keep project packages local and shared tools in an approved user-owned location such as `/home/flux/.local/share/nativecode/tools/<tool>/<version>`. Use a matching distro package manager only when needed and explicitly authorized. Never mix distro repositories or install foreign ABI artifacts.

Report **already-working**, **installed-and-smoke-tested**, **blocked**, or **planned-only** accurately. For user-local installs, rollback removes only files created by this operation after checking no other project uses them. For shared distro packages, report the package-manager rollback plan rather than blindly removing shared dependencies. Never erase models/projects or overwrite user configuration to make a test pass.

## Constraints
No claim that Android Studio, an emulator, iOS/Xcode or every NDK is usable on this phone.

## Primary sources
- [android](https://developer.android.com/build/building-cmdline)
""",
    )

    private fun buildAndroidApp() = Skill(
        id = "build-android-app",
        title = "Build Android App",
        summary = "Existing Android project or a brief for a new Android-only application.",
        source = "",
        body = """---
name: build-android-app
description: Existing Android project or a brief for a new Android-only application. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build Android App
  category: PROJECT
  group: Development
  engine: Kotlin / Jetpack Compose + project Gradle wrapper
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build Android App

**Use for:** Existing Android project or a brief for a new Android-only application.

**Deliver:** Editable project source, tests, debug APK when buildable, and build log.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### Android build tools, installed inline through the existing NativeCode route

```sh
command -v java; command -v javac
java -version
printf 'SDK: '; test -d /opt/android-sdk && printf '/opt/android-sdk\n'
find /opt/android-sdk/build-tools -maxdepth 2 -name aapt2 -type f 2>/dev/null
```

Reuse NativeCode's development pack and `/opt/android-sdk`, not a desktop x86 SDK bundle. If the pack is absent/incomplete, use NativeCode's existing development-pack install/repair control; this is a native application facility, **not a prerequisite skill**. Inspect the checkout's existing setup script only for a concrete missing component. There is no documented `nativecode-agent install-sdk` command; do not invent one or reimplement pack delivery.

Set `ANDROID_HOME=/opt/android-sdk` and `ANDROID_SDK_ROOT=/opt/android-sdk` in the task shell when that path exists. Select a JDK compatible with the project's Gradle/Android Gradle Plugin, preserve the checked-in wrapper, and install only SDK platforms/build-tools actually referenced by the project through the existing compatible pack mechanism. Add an NDK only for native code and only with ARM64-compatible host executables.

The supplied path `/opt/android-sdk/build-tools/36.1.0/aapt2` may be the working ARM64 override. Verify it with `file` and its version first; preserve an existing `android.aapt2FromMavenOverride` pointing to the working file rather than downloading an x86 Maven aapt2. Do not rewrite build scripts just because the version differs. If the native pack mechanism cannot supply a required component, report that exact component; do not replace the user's runtime.

## Do the task

1. Inspect the existing app/module, Gradle wrapper, manifests, Kotlin/Compose versions and tests. For a new app, create a minimal Kotlin/Compose project compatible with the installed Android pack; no Android Studio/emulator requirement.
2. Implement the requested screens and behavior, including small-screen layout, accessibility, loading/error states and storage/permission handling. Preserve the application's architecture and package identity.
3. Build with the existing wrapper and conservative workers; choose the real variant/module rather than assuming every project is named `app`:

```sh
./gradlew --version
./gradlew :app:assembleDebug --max-workers=2
```

4. Run the relevant existing unit/lint tasks that the checkout provides. Inspect the produced APK and logs; a Gradle success message without an APK is not delivery.
5. Offer the actual APK with `nativecode-agent install-apk --path <actual-apk>`. The app renders on the Android host; do not wrap Gradle/JDK with `gpu-launch`. Use an authorized connected device for on-device behavior tests when available; distinguish those from local unit tests.

## Check the actual result

Require nonempty APK, matching package/variant, real build/test exit codes and editable source. Verify installation/launch only if the user completed the installer or an authorized device test succeeded. Record missing SDK components without claiming a successful build.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://developer.android.com/build/building-cmdline
""",
    )

    private fun buildWebApp() = Skill(
        id = "build-web-app",
        title = "Build Web App",
        summary = "Existing client-side app or a new interactive web-app brief.",
        source = "",
        body = """---
name: build-web-app
description: Existing client-side app or a new interactive web-app brief. Complete this task inside NativeCode using the inline
  setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build Web App
  category: PROJECT
  group: Development
  engine: Vite + React + TypeScript
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build Web App

**Use for:** Existing client-side app or a new interactive web-app brief.

**Deliver:** Source, lockfile, tests and production dist/ output when dependencies exist.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

## Do the task

1. Keep the existing framework and package manager. For a new interactive client app, use React + TypeScript + Vite. In a new destination, scaffold once:

```sh
npm create vite@latest web-app -- --template react-ts
cd web-app
npm install
```

2. Build the requested working routes/components rather than a static mockup. Implement responsive layout, keyboard/touch input, loading/error/empty states and accessible controls. Use shadcn/ui only when the requested design benefits from it; do not make it an additional prerequisite.
3. Keep runtime code, assets and tests in the project; record dependencies in the lockfile. Do not add API keys or a paid service just to build the UI.
4. Run the project's type-check/tests and `npm run build`. Preview with `npm run dev -- --host 127.0.0.1 --port 5173`, verify HTTP readiness, then open that URL through NativeCode.
5. For a GPU-heavy canvas/WebGL feature, test in the existing GPU-enabled Chromium wrapper; ordinary React compilation is a CPU task. Use the actual browser renderer when reporting graphics acceleration, not the npm process.

## Check the actual result

Require a successful production build and real interactions at phone/tablet widths. Inspect console/network failures and accessibility basics. Deliver source, lockfile, output build, preview URL and actual checks. Do not label an untested screenshot or placeholder data flow as a completed application.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/vitejs/vite
- https://github.com/shadcn-ui/ui
""",
    )

    private fun buildWebsite() = Skill(
        id = "build-website",
        title = "Build Website",
        summary = "A landing page, portfolio, blog, documentation site, or existing content website.",
        source = "",
        body = """---
name: build-website
description: A landing page, portfolio, blog, documentation site, or existing content website. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build Website
  category: PROJECT
  group: Development
  engine: Astro for content sites; existing stack otherwise
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build Website

**Use for:** A landing page, portfolio, blog, documentation site, or existing content website.

**Deliver:** Editable website, assets, accessibility checks and a static build.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

## Do the task

1. Preserve an existing site's stack. For a new landing page, portfolio, blog or documentation site, use Astro and select the smallest appropriate starter:

```sh
npm create astro@latest website
cd website
npm install
```

2. Implement actual pages, navigation, content structure, responsive typography and assets. Keep static generation unless the brief genuinely requires a server.
3. Add semantic HTML, keyboard focus, alternative text, metadata and a real contact/navigation behavior. Do not install a UI library for a simple page merely because it exists in the catalog.
4. Run the project's checks and `npm run build`. Preview with `npm run dev -- --host 127.0.0.1 --port 4321`; verify the actual port before calling NativeCode's `open-link`.
5. Reuse the NativeCode browser for preview. For animated/WebGL content use its working GPU wrapper and verify the renderer; do not claim that Astro builds use the GPU.

## Check the actual result

Check all requested routes, mobile overflow, asset loading, link targets, keyboard navigation and build output. Deliver editable source, built site and preview instructions. Do not deploy or publish without a separate request.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/withastro/astro
""",
    )

    private fun buildFullStackApp() = Skill(
        id = "build-full-stack-app",
        title = "Build Full-Stack App",
        summary = "An app requiring server routes, server rendering or persistent server-side behavior.",
        source = "",
        body = """---
name: build-full-stack-app
description: An app requiring server routes, server rendering or persistent server-side behavior. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build Full-Stack App
  category: PROJECT
  group: Development
  engine: Next.js + existing database; SQLite for appropriate local prototypes
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build Full-Stack App

**Use for:** An app requiring server routes, server rendering or persistent server-side behavior.

**Deliver:** Client/server source, schema or migrations, tests, and a local preview.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

## Do the task

1. Keep the current project/database. For a new server-dependent app, scaffold Next.js + TypeScript with the installed Node version supported by that release:

```sh
npx create-next-app@latest full-stack-app --ts --app --use-npm
cd full-stack-app
```

2. Implement one complete vertical flow from UI to server validation to persistence. Use the existing database; for a small local prototype use SQLite through a compatible driver. Install only the chosen driver's project package and test an actual read/write before building the rest of the app.
3. Keep server secrets out of client bundles, validate inputs at the API boundary and add migrations/seed data only for the task database. Do not create hosted accounts, change production data or install a database service unnecessarily.
4. Run unit/integration checks and `npm run build`. Start with `npm run dev -- --hostname 127.0.0.1 --port 3000`; open a NativeCode preview only after the health/page request works.
5. Test the end-to-end flow and persistence across a task-owned restart. Browser graphics may use the existing NativeCode GPU path; Node/server/database execution is not GPU-accelerated by `gpu-launch`.

## Check the actual result

Require real frontend/server/data behavior, error cases, persistent writes in the task database, passing available checks and a production build. Document migrations and exact environment variables without secrets. Preserve an existing stack rather than replacing it after one native-module installation error.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/vercel/next.js
""",
    )

    private fun buildApi() = Skill(
        id = "build-api",
        title = "Build API",
        summary = "An HTTP API, a local service, or API changes in an existing Python project.",
        source = "",
        body = """---
name: build-api
description: An HTTP API, a local service, or API changes in an existing Python project. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build API
  category: PROJECT
  group: Development
  engine: FastAPI + Python; standard sqlite3 where suitable
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build API

**Use for:** An HTTP API, a local service, or API changes in an existing Python project.

**Deliver:** Typed API code, OpenAPI schema, tests and a managed localhost service.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### API packages

For a new pip-managed project, install the actual API and test packages:

```sh
.venv/bin/python -m pip install fastapi uvicorn pytest httpx
```

Keep existing pinned requirements instead when supplied. Add a database package only for the selected storage; Python's built-in `sqlite3` is enough for a suitable local prototype.

## Do the task

1. Inspect or create the API package and a typed request/response contract. Implement the requested endpoints, validation, structured errors and an inexpensive `/health` route.
2. Put configuration in environment variables with safe local defaults; preserve credentials. Add authentication only appropriate to the brief and never expose the service publicly by default.
3. Write pytest tests for success, invalid input, not-found cases and persistence. Use isolated temporary test data.
4. For an `app/main.py` module containing `app = FastAPI()`, run:

```sh
.venv/bin/python -m pytest
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

5. Check `/health` and `/openapi.json`, then use NativeCode's `open-link` for `http://127.0.0.1:8000/docs`. Keep a task-owned PID/log. FastAPI does not need a display or GPU.

## Check the actual result

Require tested endpoint behavior, actual schema output, healthy local service and reproducible startup. Deliver source, pinned dependencies and examples without secrets. Do not run a system daemon or bind to all interfaces unless the user requests it.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/fastapi/fastapi
""",
    )

    private fun turnWebsiteIntoApp() = Skill(
        id = "turn-website-into-app",
        title = "Turn Website Into App",
        summary = "A local web project whose built assets should run in an Android wrapper.",
        source = "",
        body = """---
name: turn-website-into-app
description: A local web project whose built assets should run in an Android wrapper. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Turn Website Into App
  category: PROJECT
  group: Development
  engine: Capacitor Android + existing web build
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Turn Website Into App

**Use for:** A local web project whose built assets should run in an Android wrapper.

**Deliver:** Web source, Capacitor Android project, and debug APK when buildable.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### Android build tools, installed inline through the existing NativeCode route

```sh
command -v java; command -v javac
java -version
printf 'SDK: '; test -d /opt/android-sdk && printf '/opt/android-sdk\n'
find /opt/android-sdk/build-tools -maxdepth 2 -name aapt2 -type f 2>/dev/null
```

Reuse NativeCode's development pack and `/opt/android-sdk`, not a desktop x86 SDK bundle. If the pack is absent/incomplete, use NativeCode's existing development-pack install/repair control; this is a native application facility, **not a prerequisite skill**. Inspect the checkout's existing setup script only for a concrete missing component. There is no documented `nativecode-agent install-sdk` command; do not invent one or reimplement pack delivery.

Set `ANDROID_HOME=/opt/android-sdk` and `ANDROID_SDK_ROOT=/opt/android-sdk` in the task shell when that path exists. Select a JDK compatible with the project's Gradle/Android Gradle Plugin, preserve the checked-in wrapper, and install only SDK platforms/build-tools actually referenced by the project through the existing compatible pack mechanism. Add an NDK only for native code and only with ARM64-compatible host executables.

The supplied path `/opt/android-sdk/build-tools/36.1.0/aapt2` may be the working ARM64 override. Verify it with `file` and its version first; preserve an existing `android.aapt2FromMavenOverride` pointing to the working file rather than downloading an x86 Maven aapt2. Do not rewrite build scripts just because the version differs. If the native pack mechanism cannot supply a required component, report that exact component; do not replace the user's runtime.

### Capacitor packages

Reuse an existing Capacitor project. For a new wrapper, install into the web project:

```sh
npm install @capacitor/core @capacitor/android
npm install --save-dev @capacitor/cli
```

Keep all Capacitor packages on a compatible release line. Set the real built asset directory in `capacitor.config.*`; do not point it at the source directory or a development-only URL.

## Do the task

1. Build the existing web project and confirm its routes/assets work without a development server. Server-side-only features need an explicit reachable backend; do not pretend they become local merely by wrapping them.
2. Initialize Capacitor only if absent, using the user's app name/package ID and the actual `webDir`. Add Android only when its directory is absent:

```sh
npx cap add android
npm run build
npx cap sync android
```

3. Handle back navigation, safe areas, permissions, local files and responsive WebView layout. Keep existing Android/Capacitor changes and signing settings.
4. Build through the Android project's wrapper:

```sh
cd android
./gradlew assembleDebug --max-workers=2
```

5. Offer the resulting APK through `nativecode-agent install-apk`. Test on the host/authorized device; Android WebView owns graphics acceleration. Do not call a guest Chromium render a device WebView test.

## Check the actual result

Require an actual debug APK, correct app identity, assets loading in WebView and requested native/web behavior. Offline expectations must match the implemented backend design. Deliver both web source and the Android wrapper.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/ionic-team/capacitor
- https://developer.android.com/build/building-cmdline
""",
    )

    private fun testWebsite() = Skill(
        id = "test-website",
        title = "Test Website",
        summary = "A local web project or a user-authorized test URL and a small acceptance checklist.",
        source = "",
        body = """---
name: test-website
description: A local web project or a user-authorized test URL and a small acceptance checklist. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Test Website
  category: PROJECT
  group: Development
  engine: Playwright with a proven compatible browser
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Test Website

**Use for:** A local web project or a user-authorized test URL and a small acceptance checklist.

**Deliver:** Reproducible browser tests, screenshots, console/network errors and a test report.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

### Browser-test package

Use the existing project runner when present. For a new test setup use one project-local package without downloading desktop browsers:

```sh
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --save-dev @playwright/test
```

Configure Chromium with `executablePath: '/home/flux/.local/bin/chromium'`, a 30-second launch timeout and one worker. The supplied wrapper carries NativeCode GPU/sandbox settings. Do not run Playwright's distribution-specific `install-deps` on Alpaquita or assume a custom browser is guaranteed compatible. Run one launch smoke before the suite; if it fails, diagnose that exact runner/browser combination once instead of installing several engines.

## Do the task

1. Read the app's existing test configuration and the requested flows. Use localhost or an explicitly authorized test target with non-production data.
2. Start/reuse the task server and confirm HTTP readiness. Use a fresh browser context/profile and deterministic viewport; preserve the user's real browser profile.
3. Add tests for essential interactions, forms, routing and responsive widths. Capture page errors, console errors, failed requests and screenshots on failure. Prefer accessible locators over fragile timing sleeps.
4. Run `npx playwright test --workers=1`; collect the real report/trace. For GPU-required pages, draw/read back WebGL pixels and record the renderer rather than only taking a screenshot.
5. For a local screenshot preview, expose only the report directory on localhost and use NativeCode's `open-link`. Close the test browser and only the server that this task owns.

## Small WebGL probe for the test page

Run inside a launched Playwright `page`; this executes a tiny draw/readback instead of trusting only a renderer string:

```javascript
const gpu = await page.evaluate(() => {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 1;
  const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
  if (!gl) return {ok: false, reason: 'No WebGL context'};
  const ext = gl.getExtension('WEBGL_debug_renderer_info');
  const renderer = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null;
  gl.clearColor(1, 0, 0, 1);
  gl.clear(gl.COLOR_BUFFER_BIT);
  const pixel = new Uint8Array(4);
  gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
  const ok = typeof renderer === 'string' &&
    !/swiftshader|llvmpipe|software/i.test(renderer) &&
    pixel[0] > 240 && pixel[1] < 16 && pixel[2] < 16;
  return {ok, renderer, pixel: Array.from(pixel)};
});
console.log(JSON.stringify(gpu));
if (!gpu.ok) throw new Error('Hardware WebGL was not verified');
```

Also match the renderer to the live device/backend and test the application's real workload. This probe does not measure browser video decoding or overall application performance.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Report actual passed/failed/skipped counts, reproducible failures and artifact paths. A WebGL context without a device-backed renderer is not GPU verification. If browser/runner compatibility is blocked, return source-level findings separately rather than inventing a browser-test pass.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/microsoft/playwright
- https://playwright.dev/docs/intro
- https://github.com/puppeteer/puppeteer
- https://playwright.dev/docs/api/class-browsertype
""",
    )

    private fun testAndroidApp() = Skill(
        id = "test-android-app",
        title = "Test Android App",
        summary = "A debug APK/package and an explicitly authorized Android test target.",
        source = "",
        body = """---
name: test-android-app
description: A debug APK/package and an explicitly authorized Android test target. Complete this task inside NativeCode using
  the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Test Android App
  category: PROJECT
  group: Development
  engine: NativeCode APK bridge + authorized ADB when needed
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Test Android App

**Use for:** A debug APK/package and an explicitly authorized Android test target.

**Deliver:** Installation/launch evidence, filtered logs, screenshots when permitted, and a reproducible bug report.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### ADB, only for an authorized device test

Reuse `/home/flux/.local/bin/adb` or `/opt/android-sdk/platform-tools/adb`; check `adb version` and the real binary's architecture. No ADB is needed just to offer an APK to Android's Package Installer through NativeCode.

If ADB is absent, the supplied verified ARM64 prebuilt route is:

```text
https://github.com/zhuwanhong/android-sdk-linux-arm64/releases/download/v1.0.0/android-sdk-linux-arm64-36.0.0-ours.tar.gz
SHA-256: f82700229b117aca7720c227bec1e62259071b5e1f0d60e0569244149aee357a
```

Download to a temporary directory, verify this exact artifact, inspect/extract it safely, and require an aarch64 `platform-tools/adb` that passes `adb version`. Preserve its companion files under a versioned `/opt/android-sdk/platform-tools-arm64-35.0.2` path, or an authorized user-local tool path, and expose a small `/home/flux/.local/bin/adb` wrapper. The archive label is not the embedded ADB version: record what the binary reports. Never overwrite an unknown SDK installation, install a desktop x86 binary, or compile a new ADB as a hidden fallback.

Start/reuse the user's ADB server without killing an existing session. For wireless debugging use the exact Android-provided `HOST:PAIR_PORT` with `adb pair`, then the separate `HOST:CONNECT_PORT` with `adb connect`. Require the user's authorization. No network scan, authentication bypass or raw USB/device-node workaround is allowed.

## Do the task

1. Identify the exact APK/package and requested behavior; read the project's existing tests and NativeCode bridge availability. Do not install a toolchain when only APK testing is requested.
2. Offer the APK using `nativecode-agent install-apk --path <actual-apk>`. This opens a user-confirmed Android installer; it neither silently installs nor proves launch success.
3. For automation, use `adb devices -l`, select the user's authorized serial, and use `adb -s "${'$'}SERIAL" ...` consistently. Do not choose a device merely because it is first in the list.
4. Reproduce the exact flow using the existing test harness or authorized ADB commands. For a known package, capture PID-scoped logcat where supported, test-owned screenshots with `exec-out screencap -p`, and the exact crash/system evidence needed. Do not clear all device logs or collect unrelated app data.
5. Verify launch, back/rotation/resume behavior and the requested feature. GPU rendering belongs to the Android app/host; guest GPU diagnostics are not proof of app rendering correctness. Stop only test-owned activity/processes and keep original app data unless reset was explicitly authorized.

## Check the actual result

Return device/Android/app version, reproduction steps, actual pass/fail observations, scoped logs and screenshots. Distinguish offered APK, installed package, launched activity and verified behavior. No authorized device means device testing remains unverified; it is not a reason to bypass pairing.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://developer.android.com/tools/adb
- https://developer.android.com/build/building-cmdline
""",
    )

    private fun getLibraryDocs() = Skill(
        id = "get-library-docs",
        title = "Get Library Docs",
        summary = "A library/API question tied to the project’s actual installed version.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
""",
    )

    private fun buildPythonTool() = Skill(
        id = "build-python-tool",
        title = "Build Python Tool",
        summary = "A repeatable CLI utility, automation script or changes to an existing Python package.",
        source = "",
        body = """---
name: build-python-tool
description: A repeatable CLI utility, automation script or changes to an existing Python package. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Build Python Tool
  category: PROJECT
  group: Development
  engine: Python standard library first; uv/venv for dependencies
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Build Python Tool

**Use for:** A repeatable CLI utility, automation script or changes to an existing Python package.

**Deliver:** A runnable CLI, tests, usage examples and explicit input/output contracts.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

## Do the task

1. Define the command's inputs, outputs, error behavior and dry-run mode where it can modify files. Reuse an existing package layout or create a small CLI using `argparse` and the standard library first.
2. Implement the requested operation with validation, useful exit codes, bounded resources and original-preserving output paths. Add third-party packages only for an actual missing capability and lock them in the task environment.
3. Add unit tests and temporary-file fixtures. Keep secrets and user filesystem state out of tests.
4. Run the CLI's `--help`, an actual successful example, an invalid-input example and the test suite. A tool that imports but does not perform its requested operation is not complete.
5. Report progress through NativeCode for long jobs and expose only task artifacts. Use GPU/MediaCodec bridges only when the tool actually performs a supported graphics/video operation; a generic Python utility remains CPU-based.

## Check the actual result

Require a runnable CLI, passing relevant tests, documented usage and actual output. Report modified files and rollback/dry-run results when applicable; do not package the project environment or overwrite unrelated user files.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/astral-sh/uv
- https://www.python.org/downloads/
""",
    )

    private fun make2dGame() = Skill(
        id = "make-2d-game",
        title = "Make 2D Game",
        summary = "A small playable 2D browser game or changes to an existing Phaser game.",
        source = "",
        body = """---
name: make-2d-game
description: A small playable 2D browser game or changes to an existing Phaser game. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make 2D Game
  category: PROJECT
  group: Games
  engine: Phaser + Vite + TypeScript
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make 2D Game

**Use for:** A small playable 2D browser game or changes to an existing Phaser game.

**Deliver:** Playable source, asset manifest and a static web build.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

## Do the task

1. Keep an existing game project. For a new browser game, scaffold Vite's `vanilla-ts` template and install Phaser locally:

```sh
npm create vite@latest game -- --template vanilla-ts
cd game
npm install
npm install phaser
```

2. Implement a complete playable loop: start, gameplay, win/loss and restart. Add keyboard and phone touch controls, aspect-ratio handling and pause/resume behavior.
3. Use simple local assets or approved user assets. Keep scenes/input/state separate and include asset provenance. Do not require a neural model for placeholder sprites.
4. Build with `npm run build`; preview on `127.0.0.1:5173`. Launch the existing NativeCode Chromium wrapper for local GPU validation and offer the URL through `nativecode-agent open-link` for phone testing.
5. For hardware-required rendering choose Phaser's WebGL route and inspect the actual browser renderer plus visible game frames. Canvas fallback is a different, explicitly reported route. Do not use the presence of a Vite server as a GPU test.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require playable start-to-finish/restart behavior, keyboard/touch input, resize handling, no missing assets and successful build. Test the real rendering path on the available device and report frame/render observations without inventing FPS benchmarks.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/phaserjs/phaser
- https://github.com/vitejs/vite
""",
    )

    private fun make3dWebGame() = Skill(
        id = "make-3d-web-game",
        title = "Make 3D Web Game",
        summary = "A small browser 3D game, interactive scene or existing Three.js project.",
        source = "",
        body = """---
name: make-3d-web-game
description: A small browser 3D game, interactive scene or existing Three.js project. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make 3D Web Game
  category: PROJECT
  group: Games
  engine: Three.js + TypeScript
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make 3D Web Game

**Use for:** A small browser 3D game, interactive scene or existing Three.js project.

**Deliver:** Source, glTF assets, controls and a runnable web build.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

## Do the task

1. Reuse the project or create a Vite `vanilla-ts` app and install `three` plus `@types/three` as a development dependency. Preserve a current Three.js version and renderer rather than migrating it unnecessarily.
2. Build one complete interactive scene/game: camera, controls, objective, collision rules appropriate to the brief, reset and mobile touch input. Add a physics package only when the selected gameplay requires one.
3. Use the supported Three.js WebGL renderer by default. WebGPU is a separate opt-in capability that requires a working browser adapter/device; an API flag alone is not proof. Keep assets in the project and dispose textures/geometries when replaced.
4. Cap pixel ratio and scene complexity for the phone, test context-loss/resize behavior, then run `npm run build` and a localhost preview.
5. Use the NativeCode Chromium wrapper for GPU validation. Inspect actual scene rendering, input and the reported device-backed renderer, then offer the browser URL through the host bridge. Do not change NativeCode drivers or silently switch to a software rasterizer.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require a working scene/game loop, phone controls, correct assets, bounded resource use and production output. Record the tested renderer/API and unresolved visual issues. Distinguish host-browser testing from guest-browser testing.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/mrdoob/three.js
- https://github.com/vitejs/vite
""",
    )

    private fun makeGodotGame() = Skill(
        id = "make-godot-game",
        title = "Make Godot Game",
        summary = "A Godot project or a new small 2D/3D game requiring an engine/editor workflow.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
""",
    )

    private fun makeGameAssets() = Skill(
        id = "make-game-assets",
        title = "Make Game Assets",
        summary = "Sprites, tiles, icons, UI assets or a sprite sheet brief with target dimensions.",
        source = "",
        body = """---
name: make-game-assets
description: Sprites, tiles, icons, UI assets or a sprite sheet brief with target dimensions. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Game Assets
  category: PROJECT
  group: Games
  engine: Pillow + SVG; optional ImageMagick
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Game Assets

**Use for:** Sprites, tiles, icons, UI assets or a sprite sheet brief with target dimensions.

**Deliver:** Editable source assets, PNG atlas, metadata and a preview sheet.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

## Do the task

1. Establish the asset specification: dimensions, palette, tile size, animation frames, transparent background and target engine atlas format. Reuse an existing game's scale/style.
2. Generate deterministic sprites/tiles/icons with Pillow or editable SVG. Preserve originals and source scripts; no AI image model is required for geometric/pixel assets.
3. Export PNG assets and, when requested, a sprite sheet with padding/extrusion and a JSON atlas containing correct frame rectangles, pivots and animation order.
4. Make an enlarged nearest-neighbor preview for pixel art and a native-size contact sheet. Use the existing browser for the preview or NativeCode's file UI; writing Pillow pixels is CPU work, not a GPU rendering claim.
5. Load a sample asset/animation in the real target project when available and verify scale, filtering, transparency and loop timing.

## Check the actual result

Require correct dimensions/alpha, atlas rectangles within bounds, consistent frame order and editable source. Check assets at native game scale, not just enlarged. Do not replace a supplied real image with invented content when only editing was requested.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/python-pillow/Pillow
- https://github.com/ImageMagick/ImageMagick
""",
    )

    private fun make3dAssets() = Skill(
        id = "make-3d-assets",
        title = "Make 3D Assets",
        summary = "A procedural model, low-poly prop, basic material or scene-asset brief.",
        source = "",
        body = """---
name: make-3d-assets
description: A procedural model, low-poly prop, basic material or scene-asset brief. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make 3D Assets
  category: PROJECT
  group: Games
  engine: Blender Python; lightweight glTF as targeted fallback
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make 3D Assets

**Use for:** A procedural model, low-poly prop, basic material or scene-asset brief.

**Deliver:** Editable .blend when Blender runs, glTF/GLB export, dimensions and preview.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### Blender and its existing NativeCode launcher

Reuse `/home/flux/.local/bin/blender` and `/opt/blender/current/blender-launcher`. The supplied working launcher deliberately keeps `WAYLAND_DISPLAY=` (Xwayland), `--gpu-backend vulkan`, and `-noaudio`; **do not remove those settings** or replace Blender's bundled Python with the system Python.

If missing, the supplied verified build route is:

```text
https://github.com/lfdevs/blender-linux-arm64/releases/download/v5.1.0/blender-5.1.0-git20260325.ae6d847d66fa-aarch64.tar.gz
SHA-256: a4927219950566af13572e72f31b5bcb8baf87190ee86a26e2572ce7fd059793
```

Download/verify this exact artifact, require aarch64/glibc, inspect extraction paths, preserve any current install, and place the versioned tree under `/opt/blender/5.1.0-arm64` using the authorized root terminal. Resolve only missing native display/font/Vulkan libraries (`fontconfig`, `freetype`, X11/XCB/XKB, `mesa-gbm`, `vulkan-loader`, GTK/libdecor as actually required) from Alpaquita. Use the bundled launcher and expose this wrapper only when a working one does not already exist:

```sh
#!/bin/sh
export WAYLAND_DISPLAY=
exec gpu-launch --auto /opt/blender/current/blender-launcher \
  --gpu-backend vulkan -noaudio "${'$'}@"
```

This is a reference setup from the supplied records, not proof on a different device. Run `blender -b -E help` and a tiny actual EEVEE render. Do not silently choose CPU Cycles or llvmpipe to satisfy a hardware requirement. Viewport/EEVEE Vulkan does not imply that Adreno supports Cycles CUDA/OptiX/HIP devices. Keep the working launcher; no new Phosh entry is needed for this project skill.

## Do the task

1. Read the requested model dimensions, units, polygon budget, materials, origin/pivot and export format. Reuse an existing `.blend` rather than rebuilding unrelated content.
2. Write a project-local Blender Python script using `bpy`, or edit the scene, to create the requested mesh/materials. Keep a meaningful hierarchy, applied transforms where appropriate and portable texture paths.
3. Execute with Blender's own interpreter through the supplied wrapper, for example `blender -b --factory-startup --python create_asset.py` only for a new scene. Do not factory-reset an existing user scene.
4. Save editable `.blend`, export glTF/GLB using the installed Blender export API, and render a small EEVEE preview through the existing Vulkan/Xwayland/noaudio wrapper. Query the real available engine IDs instead of assuming one across releases.
5. Inspect the actual rendered image and exported mesh/materials; validate bounds, units, normals, UVs, textures and polygon count. Record GPU viewport/EEVEE evidence separately from any Cycles capability.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require nonempty editable and requested export files, correct geometry/scale, portable assets and an actual preview. The supplied setup reports a successful small EEVEE render, not a guarantee that every Cycles or GUI feature works. No silent CPU-Cycles substitution for a hardware-required render.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/blender/blender
- https://github.com/mrdoob/three.js
""",
    )

    private fun editImages() = Skill(
        id = "edit-images",
        title = "Edit Images",
        summary = "User images plus crop, resize, rotate, format, overlay or color-adjustment instructions.",
        source = "",
        body = """---
name: edit-images
description: User images plus crop, resize, rotate, format, overlay or color-adjustment instructions. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Edit Images
  category: PROJECT
  group: Images
  engine: Pillow for scripted edits; ImageMagick for batch operations
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Edit Images

**Use for:** User images plus crop, resize, rotate, format, overlay or color-adjustment instructions.

**Deliver:** Edited images, original-preserving filenames and a small before/after preview.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

## Do the task

1. Identify the actual supplied images and requested edits. Inspect format, dimensions, alpha, orientation and profile; do not invent an edit target.
2. Apply only the requested operations with Pillow by default: crop, resize, rotate, composite, color adjustment or format conversion. Preserve the source and record parameters in a script.
3. Honor EXIF orientation, resampling appropriate to photos/pixel art, alpha handling and output quality. Strip location metadata only when requested or required for a specific privacy-oriented export, not silently.
4. Save new output files, reopen them to verify they decode, and create a small before/after view when useful. Use ImageMagick only for capabilities needed by this task.
5. Use NativeCode's existing file/preview facilities. CPU image transforms do not become GPU operations by being wrapped in `gpu-launch`; use a real supported accelerated backend only if the selected operation has one.

## Check the actual result

Check exact output dimensions/format, transparency, visual edit scope and original preservation. Deliver edited images plus reproducible script/settings; do not claim visual inspection without actually viewing the rendered output.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/python-pillow/Pillow
- https://github.com/ImageMagick/ImageMagick
""",
    )

    private fun makeThumbnail() = Skill(
        id = "make-thumbnail",
        title = "Make Thumbnail",
        summary = "A video/article/game topic, supplied artwork, title and target platform/aspect ratio.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
""",
    )

    private fun removeBackground() = Skill(
        id = "remove-background",
        title = "Remove Background",
        summary = "User images requiring transparent backgrounds or foreground cutouts.",
        source = "",
        body = """---
name: remove-background
description: User images requiring transparent backgrounds or foreground cutouts. Complete this task inside NativeCode using
  the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Remove Background
  category: PROJECT
  group: Images
  engine: rembg CPU + verified ONNX model
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Remove Background

**Use for:** User images requiring transparent backgrounds or foreground cutouts.

**Deliver:** Transparent PNGs, masks if requested, and edge-quality previews.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### Background-removal runtime and selected model

Reuse a working rembg environment and cached model. For a CPU-supported task environment the upstream package route is:

```sh
.venv/bin/python -m pip install 'rembg[cpu,cli]'
```

Use a Python version with a compatible ARM64 ONNX Runtime wheel. Select one appropriate model and obtain approval before its first potentially large download; rembg may fetch it on first use. Keep its provenance and cache path. Do not assume `onnxruntime-gpu` means Adreno/Mali support. For a user-required GPU route, install only a documented compatible execution provider and prove the model actually uses it; otherwise report the GPU limitation before inference, or use an explicitly accepted CPU route.

## Do the task

1. Confirm the actual input image, subject and desired output transparency/edge treatment. Preserve the source.
2. Inspect the installed `rembg --help` and reuse one model session for a batch rather than repeatedly loading weights. Use the selected model only; do not download all available models.
3. Run the input/output route, for example `.venv/bin/rembg i input.png output.png`, after the selected model/download has been approved. Use a bounded input size for the first test.
4. Inspect hair/fine edges, holes, shadows and color fringing against both light and dark backgrounds. Refine mask settings without hallucinating missing subject details.
5. Save a transparent PNG and requested mask plus the actual model/provider report. NativeCode telemetry is useful before batch inference; GPU-enabled browser display is not proof of accelerated background-removal inference.

## Check the actual result

Require RGBA output, useful alpha mask, acceptable edges on different backgrounds and preserved source. Record the real execution provider, model and any CPU fallback explicitly. No fabricated GPU claim from a browser or graphics-driver test.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/danielgatis/rembg
""",
    )

    private fun makeVectorArt() = Skill(
        id = "make-vector-art",
        title = "Make Vector Art",
        summary = "An icon, diagram-like illustration, logo concept or scalable graphic brief.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
""",
    )

    private fun generateImages() = Skill(
        id = "generate-images",
        title = "Generate Images",
        summary = "An explicit neural image-generation/editing request and an available authorized image model.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
""",
    )

    private fun makeMotionVideo() = Skill(
        id = "make-motion-video",
        title = "Make Motion Video",
        summary = "A product intro, animated title, explainer, data animation or motion-graphics brief.",
        source = "",
        body = """---
name: make-motion-video
description: A product intro, animated title, explainer, data animation or motion-graphics brief. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Motion Video
  category: PROJECT
  group: Video and audio
  engine: HyperFrames + compatible Chromium + FFmpeg
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Motion Video

**Use for:** A product intro, animated title, explainer, data animation or motion-graphics brief.

**Deliver:** Editable HTML/CSS/animation project, preview frames and a rendered MP4 when supported.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### HyperFrames CLI

Reuse the supplied working `hyperframes` command first. Check `hyperframes --version` and `npm view hyperframes engines --json` only when selecting a new version. If missing, install a compatible pinned release into this project's npm environment with `npm install --save-dev --save-exact hyperframes@<selected-version>` and use `./node_modules/.bin/hyperframes` in place of the bare command below. The supplied route needs Node 22+; honor the actual selected package's engines too.

Set `HYPERFRAMES_BROWSER_PATH=/home/flux/.local/bin/google-chrome` when that verified wrapper exists, otherwise set it to the existing `/home/flux/.local/bin/chromium`. Do not install/refresh upstream agent skills, a separate cloud service or every optional HyperFrames package. Use the installed CLI's operation-specific help only if its interface differs.

## Do the task

1. Reuse the composition or run `hyperframes init <new-project>` once. Plan the requested duration, aspect ratio, scenes, fonts and approved local assets. Author within the initialized HTML/timing contract rather than inventing undocumented composition attributes.
2. Write deterministic seekable animation using the project's supported CSS/GSAP/Three.js route. Load local media/fonts, avoid wall-clock/random animation, and keep editable sources and a frame/timing plan.
3. Run `hyperframes check . --json`, preview with `hyperframes preview`, and open the **actual reported localhost port** through NativeCode. Inspect representative start/middle/end frames before a long render.
4. Use the supplied browser-GPU route. `--browser-gpu` is the browser capture switch; `--no-gpu` disables HyperFrames' separate desktop-encoder probe, not browser graphics:

```sh
hyperframes render . --browser-gpu --no-gpu \
  --format png-sequence --output ./output/frames
```

5. Require the real browser hardware renderer. Use one worker/low-memory mode when supported and needed; check the installed render help for those resource flags. Inspect emitted frame names/dimensions, normalize an ordered sequence in the task directory, then use the NativeCode MediaCodec section for the requested hardware MP4. Mix/remux audio separately; PNG sequences do not carry audio.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "${'$'}PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require validated composition, deterministic rendered frames, correct duration/aspect ratio, no missing fonts/media, actual GPU renderer and a playable output. Record browser capture and MediaCodec encoder separately. HyperFrames' `--gpu` desktop encoder detection is not a NativeCode MediaCodec integration. Do not deliver a software encode as a hardware success.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/heygen-com/hyperframes
- https://github.com/FFmpeg/FFmpeg
- https://github.com/puppeteer/puppeteer
""",
    )

    private fun makeShortVideo() = Skill(
        id = "make-short-video",
        title = "Make Short Video",
        summary = "A topic or supplied script, desired duration, language and owned/licensed media; narration optional.",
        source = "",
        body = """---
name: make-short-video
description: A topic or supplied script, desired duration, language and owned/licensed media; narration optional. Complete
  this task inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Short Video
  category: PROJECT
  group: Video and audio
  engine: MoneyPrinterTurbo; local-assets/no-extra-LLM-key route first
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Short Video

**Use for:** A topic or supplied script, desired duration, language and owned/licensed media; narration optional.

**Deliver:** Portrait MP4, script, captions, asset manifest and editable task configuration.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### MoneyPrinterTurbo: reuse the supplied working app

Check `/home/flux/.local/bin/moneyprinterturbo`, `/opt/moneyprinterturbo/current`, its `.venv/bin/python`, `pyproject.toml`, `uv.lock` and current config. Do not assume there is a `moneyprinterturbo generate` CLI; the supplied launcher starts the WebUI.

If the application is absent, the supplied source artifact is:

```text
https://github.com/harry0703/MoneyPrinterTurbo/archive/refs/tags/v1.3.7.tar.gz
SHA-256: 8253255062d5100f030f344575e543c8739091ca5f449c4727c2f715f0571081
```

Verify/download/extract this exact archive into a versioned `/opt/moneyprinterturbo/v1.3.7` directory through the authorized root terminal; preserve an existing version and current symlink. Use the installed uv and the matching upstream lockfile, not a collection of independently upgraded packages:

```sh
uv python install 3.11
cd /opt/moneyprinterturbo/current
UV_CACHE_DIR=/tmp/mpt-uv-cache-fresh UV_LINK_MODE=copy \
  uv sync --frozen --no-dev --extra twelvelabs --link-mode=copy
.venv/bin/python -c 'from app.config import config; print(config.project_version)'
```

That command is the supplied tested production dependency route, including its declared extra; it does not authorize model downloads or API calls. Keep the app's own environment separate from the user's project and preserve existing settings/secrets. Set `app.ffmpeg_path` only to the already verified ARM64 FFmpeg if discovery needs it.

Reuse `moneyprinterturbo`; if the wrapper is absent, run the existing checkout's `webui.sh` with its own environment. Bind to localhost and retain a task-owned process/PID. Check Streamlit's `/_stcore/health` before opening the page. No Phosh menu or NativeCode app changes are needed.

## Do the task

1. Read the topic/brief, target duration/aspect ratio and supplied media. Use the active coding model to write an editable script and shot list; do not require a second LLM API by default.
2. Prefer approved local footage/images. Select only a supported narration/TTS route: user-supplied audio, an already configured service/model, or an explicit approved provider. Inspect the installed task configuration/schema before assuming an input feature. Do not promise fully offline generation unless the selected pipeline actually supports it.
3. Reuse the ready WebUI or launch `MPT_WEBUI_HOST=127.0.0.1 MPT_WEBUI_PORT=8501 moneyprinterturbo`. Check health, then use NativeCode's `open-link` for the WebUI. For automation use the installed app's real API/schema, not invented command flags or a made-up endpoint.
4. Generate a small trial before the full short. Keep task config, script, captions, local media paths and output locations. Review framing, caption wrapping, narration timing and asset rights.
5. Inspect the actual composition/encoder logs. Browser GPU acceleration does not accelerate MoviePy or CTranslate2. Use the NativeCode media route when hardware final encoding is required and the pipeline can provide frames; do not rewrite MoneyPrinterTurbo or NativeCode as part of this task. If the upstream output was already software-encoded, report that stage and avoid an unnecessary second lossy encode merely to claim GPU use.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "${'$'}PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require a real playable portrait video, intended script/footage/captions, appropriate narration or disclosed no-narration result, audio sync and asset/config provenance. Report local/online providers, actual inference device and final encoder separately. Any bridge post-processing is hardware final encoding, not proof that the entire creation pipeline was GPU-accelerated.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/harry0703/MoneyPrinterTurbo/blob/main/README-en.md
- https://github.com/FFmpeg/FFmpeg
""",
    )

    private fun makeReactVideo() = Skill(
        id = "make-react-video",
        title = "Make React Video",
        summary = "An existing Remotion composition or an explicit request for React-based video authoring.",
        source = "",
        body = """---
name: make-react-video
description: An existing Remotion composition or an explicit request for React-based video authoring. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make React Video
  category: PROJECT
  group: Video and audio
  engine: Remotion for existing React video projects
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make React Video

**Use for:** An existing Remotion composition or an explicit request for React-based video authoring.

**Deliver:** React composition, render settings, preview frames and final video when supported.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Remotion packages

Keep an existing Remotion project's lockfile and aligned package versions. For a specifically requested new Remotion project, use the upstream starter (`npm init video`) in a new directory, or install matching `remotion` and `@remotion/cli` packages into the chosen project. Confirm the package license fits the intended use; do not silently switch engines for an existing composition.

Reuse the NativeCode Chromium wrapper. Check the installed CLI's documented browser-executable, browser-graphics and image-sequence options with `npx remotion render --help`; browser/renderer native helpers must be ARM64/glibc compatible. Do not download x86 browser/compositor helpers or assume generic Linux support proves this guest works. Test one frame before a full render; a precise ABI blocker is preferable to an invented compatibility patch.

## Do the task

1. Read the existing composition, assets, props schema, frame rate, duration and dimensions. Keep frame-driven React animation deterministic and assets local or explicitly approved.
2. Implement the requested scene/timing changes using the installed Remotion APIs. Do not re-scaffold an existing project or install a separate agent-skill collection.
3. Start the existing Studio preview on localhost, wait for readiness and use NativeCode's `open-link`. Render a test still with the actual composition ID and check layout/media resolution.
4. Render with the verified browser wrapper and hardware graphics route. For MediaCodec final encoding, use the installed version's supported image-sequence output and the media section below; do not assume a guessed `--mediacodec` flag exists.
5. Export/mix audio through the supported composition route and remux without re-encoding hardware video. Keep native-renderer compatibility, browser graphics and final codec claims separate.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "${'$'}PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require actual frame/video output, correct timing/dimensions, deterministic seeking, audio sync and editable React source. If the native helper or image-sequence route is unavailable, report that exact limitation without claiming a rendered deliverable or rewriting the app/backend.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/remotion-dev/remotion
- https://www.remotion.dev/docs/miscellaneous/linux-dependencies
""",
    )

    private fun editVideo() = Skill(
        id = "edit-video",
        title = "Edit Video",
        summary = "User-owned/authorized clips and explicit trim, crop, resize, join, overlay or audio changes.",
        source = "",
        body = """---
name: edit-video
description: User-owned/authorized clips and explicit trim, crop, resize, join, overlay or audio changes. Complete this task
  inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Edit Video
  category: PROJECT
  group: Video and audio
  engine: FFmpeg; NativeCode MediaCodec bridge only when separately proven
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Edit Video

**Use for:** User-owned/authorized clips and explicit trim, crop, resize, join, overlay or audio changes.

**Deliver:** Edited video, reproducible command/script and stream/validation report.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

## Do the task

1. Probe the actual source with `ffprobe -v error -show_streams -show_format -of json input.mp4`; inspect dimensions, rotation, timestamps, frame rate, color information and audio. Preserve the original.
2. Select the smallest operation: use stream copy for compatible no-filter edits where keyframe accuracy is acceptable; use frame processing only when cropping, scaling, overlays or exact-frame changes require it.
3. Build a reproducible FFmpeg filter/command for the requested trim/crop/resize/overlay. Preserve aspect ratio and explicitly handle variable frame rate, color conversion and audio mapping.
4. For supported required hardware encoding, produce normalized NV12 frames through FFmpeg and use NativeCode `encode_stream` as described below. Keep decoding/filtering claims honest; those FFmpeg stages may be CPU even when final encoding is hardware.
5. Remux audio, inspect the final probe and sample start/middle/end frames plus edit boundaries. Preserve a real existing GPU/VPU route; do not choose CUDA/VA-API from an encoder name alone.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "${'$'}PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require the requested edit, original preserved, valid streams, correct duration/dimensions and audio sync. Decode the output for errors and report the actual selected encoder/bridge response when hardware was used. Do not label a transcode as lossless.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/FFmpeg/FFmpeg
""",
    )

    private fun addSubtitles() = Skill(
        id = "add-subtitles",
        title = "Add Subtitles",
        summary = "A video/audio file plus an existing transcript or permission to run a local speech model.",
        source = "",
        body = """---
name: add-subtitles
description: A video/audio file plus an existing transcript or permission to run a local speech model. Complete this task
  inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Add Subtitles
  category: PROJECT
  group: Video and audio
  engine: whisper.cpp for ASR + FFmpeg/libass for caption rendering
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Add Subtitles

**Use for:** A video/audio file plus an existing transcript or permission to run a local speech model.

**Deliver:** SRT/VTT/ASS captions and optional captioned video.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Transcription only when captions are not already supplied

Skip speech recognition entirely for an existing SRT/VTT/ASS file. Otherwise reuse an installed `whisper-cli` and approved local model. If absent, obtain a pinned [whisper.cpp](https://github.com/ggml-org/whisper.cpp) checkout or compatible ARM64 binary. For an authorized source build install only missing native CMake/compiler tools from the configured repository, configure `cmake -B build`, then `cmake --build build --config Release -j2`.

Use a supported Vulkan build only when the selected whisper.cpp revision actually exposes that backend and the installed NativeCode GPU route passes a real inference smoke. Inspect its CMake options once instead of guessing a flag from another ggml project. A compatible CPU build is a separately reported route and requires acceptance when GPU inference is mandatory. Do not assume CUDA exists.

Select one language-appropriate model; obtain approval for its download and record source/checksum. Reuse the installed model cache. Speech-recognition installation does not require every Whisper model or an online transcription service.

## Do the task

1. Identify the actual audio/video, caption language and output type: sidecar SRT/VTT, styled ASS or burned-in captions. Preserve supplied words/timing unless editing is requested.
2. For transcription prepare the model's supported PCM input, for example `ffmpeg -n -i input.mp4 -vn -ar 16000 -ac 1 audio.wav`; use the installed CLI's actual options for SRT/VTT output and selected model. Do not invent timestamps from unaligned text.
3. Review spelling, names, punctuation, line lengths, reading speed and timing against the source. Keep uncertainty in low-confidence words rather than confidently fabricating them.
4. Export sidecars directly when that is the requested result. For burn-in use FFmpeg's subtitle/ASS filter with verified fonts; check filter availability and path escaping. Output video frames through the NativeCode hardware encoder when supported, then remux audio.
5. Inspect captions at phone size and sample difficult transitions, long lines and source silences. GPU/Vulkan inference, CPU text layout and MediaCodec video encoding are distinct stages.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "${'$'}PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require parsable sidecars, monotonic sensible timestamps, language/font coverage and no clipped captions. A sidecar-only task does not need a video transcode. For burned-in output verify video dimensions, duration, caption appearance and A/V sync.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/ggml-org/whisper.cpp
- https://github.com/FFmpeg/FFmpeg
""",
    )

    private fun makeVoiceover() = Skill(
        id = "make-voiceover",
        title = "Make Voiceover",
        summary = "A script, language and permitted voice model, or supplied narration needing preparation.",
        source = "",
        body = """---
name: make-voiceover
description: A script, language and permitted voice model, or supplied narration needing preparation. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Voiceover
  category: PROJECT
  group: Video and audio
  engine: Piper offline with approved voice; supplied audio supported
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Voiceover

**Use for:** A script, language and permitted voice model, or supplied narration needing preparation.

**Deliver:** Narration WAV/audio, script chunks and voice/model metadata.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Piper and one approved voice

Reuse an existing Piper environment and installed voice. Otherwise install `piper-tts` into a compatible ARM64 task environment:

```sh
.venv/bin/python -m pip install piper-tts
.venv/bin/python -m piper --help
```

Use the upstream voice model plus its matching configuration, with approval for the download and the voice's usage terms. Do not clone an identifiable person's voice as a hidden default. If the selected Python/ONNX wheel does not support the guest, choose a supported interpreter/runtime before proceeding; do not install CUDA packages on Adreno/Mali. CPU inference is common here; claim accelerated inference only when an actual compatible execution provider is selected and tested.

## Do the task

1. Read the narration script, target language/voice, pacing and pronunciation notes. Use an approved installed voice; keep original text and any spoken-text substitutions.
2. Split long text at natural boundaries. Generate a short sample with the selected model first and inspect pronunciation/quality before synthesizing the whole script.
3. Use the installed Piper CLI contract, for example `python -m piper --model <actual-model.onnx> --output_file <output.wav>` with text on stdin when that version supports it. Use the task environment's interpreter and matching model configuration.
4. Concatenate segments and adjust loudness with FFmpeg only as needed; retain source segments and timing. Do not silently speed up speech to fit an arbitrary duration.
5. Check the complete output, duration, sample rate, clipping and intelligibility. Use NativeCode telemetry for long batches and notify completion. Browser/MediaCodec graphics do not prove TTS model acceleration.

## Check the actual result

Require playable narration, correct language/text, acceptable pacing and accurate voice/model/provider metadata. Record actual inference backend and any external service use. Supply original script, audio and reproducible settings.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/OHF-Voice/piper1-gpl
- https://github.com/FFmpeg/FFmpeg
""",
    )

    private fun cleanAudio() = Skill(
        id = "clean-audio",
        title = "Clean Audio",
        summary = "Audio/video needing trim, channel conversion, mixing, noise reduction or loudness normalization.",
        source = "",
        body = """---
name: clean-audio
description: Audio/video needing trim, channel conversion, mixing, noise reduction or loudness normalization. Complete this
  task inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Clean Audio
  category: PROJECT
  group: Video and audio
  engine: FFmpeg audio filters
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Clean Audio

**Use for:** Audio/video needing trim, channel conversion, mixing, noise reduction or loudness normalization.

**Deliver:** Processed audio, optional remuxed video and an audio-processing report.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

## Do the task

1. Probe the actual recording and inspect a sample. Identify the requested issue: noise, loudness, clipping, channel imbalance, hum or an audio mix. Preserve the original.
2. Use a minimal FFmpeg filter chain appropriate to the evidence; do not apply every filter. Examples include high/low-pass, gentle denoising and loudness normalization, with measured settings.
3. For loudness targets measure first and apply a second pass when precision is needed. Avoid aggressive denoising that damages speech; do not promise to reconstruct unrecoverable clipped/missing audio.
4. Produce a short before/after segment, inspect it, then process the full file and check channel layout, sample rate and peak/loudness behavior.
5. Remux into the original video with video stream copy when no video edit is requested. These audio filters are CPU operations unless a separately supported backend is proved; do not involve a GPU/MediaCodec video transcode unnecessarily.

## Check the actual result

Require a decodable output, preserved timing/channels, no newly introduced clipping and a useful before/after comparison. Record actual filter parameters and measurements, not invented subjective improvements or GPU acceleration.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/FFmpeg/FFmpeg
""",
    )

    private fun makeVideoFromSlides() = Skill(
        id = "make-video-from-slides",
        title = "Make Video From Slides",
        summary = "A presentation/PDF or ordered images, per-slide timing and optional narration/music.",
        source = "",
        body = """---
name: make-video-from-slides
description: A presentation/PDF or ordered images, per-slide timing and optional narration/music. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Video From Slides
  category: PROJECT
  group: Video and audio
  engine: Rendered slides/images + FFmpeg
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Video From Slides

**Use for:** A presentation/PDF or ordered images, per-slide timing and optional narration/music.

**Deliver:** Video slideshow, rendered slide assets and timing manifest.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
uname -m
cat /etc/os-release
getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1
```

Reuse this check when it was already done in the current task. The supplied installation records target ARM64 Alpaquita/glibc. Do not mix foreign Alpine/Debian repositories, use x86 binaries, assume CUDA, use systemd/Docker, or change NativeCode drivers. Use existing NativeCode bridges instead of opening/chmodding Android device nodes.

For a required download/extraction, reuse existing utilities. If one is missing, install only the corresponding native `curl`, `ca-certificates`, `tar`, `xz`, `unzip`, `coreutils` or `binutils` package (`ar`) in the authorized root terminal. Do not install a full compiler suite for a binary-only task.

**When executing this skill later:** install only missing task packages in the project's environment. Run `apk` changes only in NativeCode's authorized root terminal using the active distribution's repositories; return to `flux` for builds and content. Do not assume `sudo` is available. Keep existing versions that satisfy the project. Ask before large model downloads, paid/external services or destructive actions. Merely adding this Markdown to the Skills page must execute nothing.

## Install what this task needs

### FFmpeg / ffprobe, only if missing

```sh
command -v ffmpeg; command -v ffprobe
ffmpeg -version | head -3
ffprobe -version | head -2
```

Reuse the supplied working ARM64 FFmpeg and matching ffprobe (including `/opt/ffmpeg/current/bin` when present). If missing, inspect `apk search -x ffmpeg` / `apk policy ffmpeg`, then install the compatible native package from the authorized root terminal. If unavailable, the supplied verified recipe uses [BtbN's FFmpeg ARM64 builds](https://github.com/BtbN/FFmpeg-Builds/releases): select `linuxarm64`, verify that release's published checksum, extract into a versioned `/opt/ffmpeg` directory and expose its two binaries in `/home/flux/.local/bin`. Do not reuse an old checksum for a changed `latest` asset or silently source-build/emulate x86.

Test the exact filter/codec required. The presence of `h264_nvenc`, VA-API or another encoder in an FFmpeg listing is not proof that Android exposes that hardware. NativeCode MediaCodec is the separate bridge described below, not an FFmpeg `--gpu` switch.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Office renderer, only when the input/output needs it

Reuse `libreoffice` or `soffice` if it opens the selected format. Otherwise inspect `apk search 'libreoffice*'` in the active Alpaquita repositories and install the matching package/components and required fonts through the authorized root terminal. A filename in an archive is not proof of an ARM64-compatible office build. Do not install a generic x86 desktop tarball or modify NativeCode's display stack to make it run.

Use a unique profile for each conversion, preserve the original and write into a task output directory:

```sh
mkdir -p output
lo_profile=${'$'}(mktemp -d /tmp/nativecode-office.XXXXXX)
# Substitute the actual source file and requested output format.
libreoffice "-env:UserInstallation=file://${'$'}lo_profile" \
  --headless --convert-to pdf --outdir output input.docx
```

For page-image inspection, check `pdfinfo` and `pdftoppm`. If missing, install the native repository's `poppler-utils` package only for that preview step; run `pdftoppm -png -r 120 output/<actual-name>.pdf output/page` and inspect every produced page.

Use a bounded timeout appropriate to the document, inspect exit status and verify the new file. `--headless` conversion is not a GPU workload. For interactive inspection, use the existing NativeCode desktop and `gpu-launch --auto libreoffice <actual-file>` only when the app supports the selected route; do not start a new desktop session. If no compatible renderer is available, preserve the editable source and report that visual conversion is blocked instead of faking a rendered result.

### Slide/page image preparation

If input is already a numbered image sequence, skip office/PDF tools. For a PPTX, use the inline office renderer to export a PDF; for a PDF, reuse it directly. Install the active repository's `poppler-utils` package only when `pdftoppm`/`pdfinfo` is absent, then render pages at the required size into a task directory. Preserve original slide order and editable deck source.

## Do the task

1. Read the actual slide deck/images, desired aspect ratio, duration per slide, transitions and supplied narration/music. Do not claim slide animations survived conversion to static images.
2. Render only when needed and inspect all slide/page images for missing fonts, charts and crops. Normalize dimensions and frame order without overwriting source slides.
3. Build a timing manifest. Use narration duration or the user's timings; pad/hold slides intentionally and do not stretch audio silently.
4. Compose the frame sequence with the selected transitions using FFmpeg, then use the NativeCode hardware encoder for a supported final video. Mix and remux audio separately.
5. Inspect the finished first/last slide, transitions, readable text, total duration and synchronization. Return the source deck, timing manifest, rendered assets and actual video.

## NativeCode hardware video path

Treat graphics, frame composition, video encoding and decoding as separate stages. Before a heavy operation:

```sh
nativecode-agent telemetry
nativecode-media capabilities
```

For hardware encoding require the actual selected codec to report `is_hardware:true` and `software_only:false`. NativeCode's `encode_stream` accepts raw NV12 frames; it does not automatically integrate into MoviePy/Remotion/HyperFrames. Use an existing compatible frame export and this bridge; do not modify NativeCode or invent an FFmpeg encoder.

For a normalized, zero-padded PNG sequence, use the actual dimensions/rate (the numbers here are an example), and preserve pipe failures:

```bash
set -o pipefail
mkdir -p output
ffmpeg -hide_banner -loglevel error \
  -framerate 30 -i 'frames/%06d.png' -an -pix_fmt nv12 -f rawvideo - | \
  nativecode-media encode_stream \
    --width 1080 --height 1920 --fps 30 --bitrate 8000000 \
    --mime video/avc --output "${'$'}PWD/output/video-only.mp4"
```

Normalize frame names in the task directory if the renderer uses another naming scheme; verify frame count/order and that every frame matches the dimensions. Do not store an enormous raw-video intermediate. For an existing video, replace the frame input with `-i input.mp4` and use the probed frame rate; identify FFmpeg decoding as CPU unless separately proven otherwise.

If audio is required, mix it separately and remux without re-encoding the hardware video:

```sh
ffmpeg -n -i output/video-only.mp4 -i narration.wav \
  -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -shortest output/final.mp4
ffprobe -v error -show_streams -show_format -of json output/final.mp4
```

Set the audio duration intentionally before using `-shortest`. Probe and decode the final file, inspect sampled frames and A/V sync, and retain the encoder response. For a separate source decode test use `nativecode-media benchmark_file --path <actual-video> --mode surface`. That benchmark does not make browser playback or FFmpeg decoding hardware-backed. If the requested codec/size is unsupported, report it; do not silently output a CPU encode as a hardware result.

## Check the actual result

Require correct page order/count, intended timing, readable slide content, expected streams and an actual hardware encode response when claimed. A rendered slideshow is not an editable PPTX; keep both artifacts distinct.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/FFmpeg/FFmpeg
- https://github.com/LibreOffice/core
""",
    )

    private fun makeSlides() = Skill(
        id = "make-slides",
        title = "Make Slides",
        summary = "A presentation brief, outline, existing theme or editable PowerPoint requirement.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
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
lo_profile=${'$'}(mktemp -d /tmp/nativecode-office.XXXXXX)
# Substitute the actual source file and requested output format.
libreoffice "-env:UserInstallation=file://${'$'}lo_profile" \
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
""",
    )

    private fun makeMarkdownSlides() = Skill(
        id = "make-markdown-slides",
        title = "Make Markdown Slides",
        summary = "A quick Markdown-based deck or HTML/PDF slide presentation.",
        source = "",
        body = """---
name: make-markdown-slides
description: A quick Markdown-based deck or HTML/PDF slide presentation. Complete this task inside NativeCode using the inline
  setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Markdown Slides
  category: PROJECT
  group: Documents
  engine: Marp CLI + compatible browser
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Markdown Slides

**Use for:** A quick Markdown-based deck or HTML/PDF slide presentation.

**Deliver:** Markdown source and requested HTML/PDF; PPTX only with clear editability status.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
: "${'$'}{NODE_VERSION:?Set the selected official version, including v}"
node_archive="node-${'$'}{NODE_VERSION}-linux-arm64.tar.xz"
node_stage=${'$'}(mktemp -d /tmp/nativecode-node.XXXXXX)
node_base=/home/flux/.local/share/nativecode/tools/node
curl -fL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/${'$'}{node_archive}" -o "${'$'}node_stage/${'$'}node_archive"
curl -fsSL "https://nodejs.org/dist/${'$'}{NODE_VERSION}/SHASUMS256.txt" -o "${'$'}node_stage/SHASUMS256.txt"
(cd "${'$'}node_stage"; grep "  ${'$'}{node_archive}${'$'}" SHASUMS256.txt > selected.sha256; test -s selected.sha256; sha256sum -c selected.sha256) || exit 1
mkdir -p "${'$'}node_base"
tar -xJf "${'$'}node_stage/${'$'}node_archive" -C "${'$'}node_base"
"${'$'}node_base/node-${'$'}{NODE_VERSION}-linux-arm64/bin/node" --version
```

Activate the verified version with a recoverable `current` symlink; never replace an unknown real directory. Add the chosen `bin` once to both Bash/Zsh PATH only when toolchain setup changed it, preserving other entries. Use project-local packages. Respect an existing npm/pnpm/yarn lockfile; `npm ci` is the npm locked-install route, not a command to overwrite another manager's lockfile.

### NativeCode Chromium, only for browser rendering/testing

Reuse `/home/flux/.local/bin/chromium` or `/home/flux/.local/bin/google-chrome`. The supplied working browser tree is `/opt/google/chrome/`; its wrapper already selects NativeCode graphics. Do not replace that wrapper or append `--disable-gpu`. Check the real executable's architecture, shared libraries and `--version` once.

If no usable browser exists, the supplied installation uses this ARM64 package source:

```text
https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
```

Download to a disposable directory; inspect the package with `ar`, extract its `control.tar.*` and `data.tar.*` without executing maintainer scripts, and require `Architecture: arm64` plus an aarch64 ELF. Record the actual downloaded version and checksum: this is a moving URL, not a pinned archive. Install only missing display/font/NSS libraries from the **native** package repositories based on control metadata and `ldd`. Preserve any existing browser, then place the verified `opt/google/chrome` tree at `/opt/google/chrome` using the authorized root terminal. No Debian repository or dpkg setup is needed.

When creating a missing wrapper, use the supplied NativeCode route, not generic desktop flags:

```sh
#!/bin/sh
exec gpu-launch --auto /opt/google/chrome/google-chrome \
  --no-sandbox --enable-gpu --disable-software-rasterizer \
  --ignore-gpu-blocklist --use-gl=angle --use-angle=vulkan "${'$'}@"
```

Save it as `/home/flux/.local/bin/chromium` only when that path is absent, make it executable and expose it in Bash/Zsh PATH. Reuse an existing known-good wrapper unchanged. Select Wayland/Ozone only with the live NativeCode Phosh socket; do not force a stale display. The supplied PRoot route needs `--no-sandbox`; confine automation to approved/local content with a fresh profile, not credentials or the user's normal browser profile. Do not weaken other security settings.

Use `gpu-launch --diagnose`, then a real WebGL draw/readback or the application's own renderer report. Accept only the actual device-backed renderer; reject SwiftShader/llvmpipe as a GPU result. Headless HTML success alone is not GPU evidence. Do not create Phosh launchers as a side effect of a content task.

### Fonts, only for the requested languages

Check `fc-match sans` and `fc-match 'Noto Sans'`; for a specific language use `fc-list :lang=hi family` (replace `hi` as appropriate). Install `fontconfig` and only the required font families from the active native repositories (`apk search '*noto*'`, then the exact available package). Alternatively use licensed font files supplied by the user in the project. Refresh with `fc-cache -f` and record the resolved family. Do not silently substitute fonts that change layout, bundle unrelated fonts, or download every language family.

### Marp CLI

Install only the project-local Marp package when missing:

```sh
PUPPETEER_SKIP_DOWNLOAD=1 npm install --save-dev @marp-team/marp-cli
```

For PDF/image export point Marp's documented `--browser-path` option to the existing NativeCode Chromium wrapper. Check `npx marp --help` once for the installed version. Markdown-to-HTML output does not need a browser render. Do not install Docker or use an x86 standalone binary.

## Do the task

1. Read the source material and desired style/slide count. Write editable Markdown with Marp frontmatter, slide separators and a local theme/assets where needed.
2. Keep each slide focused, readable and within the safe content area. Use semantic headings, meaningful image alternatives and source notes for factual material.
3. Generate HTML with `npx marp slides.md -o slides.html`. For PDF use the same source with `--pdf` and the existing browser path. Add local-file access only when the approved task assets require it; do not allow arbitrary filesystem exposure.
4. Inspect every rendered slide for wrapping, font fallback and overflow. Use NativeCode's localhost preview and actual browser wrapper for graphics-heavy slides.
5. Deliver Markdown/theme/assets plus the requested format. Marp's ordinary PPTX export contains pre-rendered slide images; do not label it native editable PowerPoint. For native editability use a PptxGenJS authoring implementation in this task instead, installing that local package inline, or report the requested output distinction.

## NativeCode graphics and hardware use

Run `gpu-launch --diagnose` once for this rendering job, reuse the existing wrappers and select `--auto`. Use explicit Turnip/PanVK/Venus only when the live diagnostic and installed launcher support that route. Do not hard-code an Adreno renderer on a Mali device, alter global ICDs, or disable GPU to hide a failure.

Validate the actual application workload and record its renderer/device. SwiftShader, llvmpipe, a software-only route, a version string or a bare PASS in the launcher are not enough. If required hardware rendering fails, save the log, try one evidence-based correction in the task's configuration, then report the specific blocker; CPU substitution needs an explicit user choice. CPU-only parsing/build steps remain CPU steps.

## Check the actual result

Require source/slide count consistency, rendered output where requested and clear editability status. GPU evidence applies only to the actual browser graphics path; static document conversion is not inherently GPU-accelerated.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/marp-team/marp-cli
""",
    )

    private fun writeDocument() = Skill(
        id = "write-document",
        title = "Write Document",
        summary = "A report, letter, proposal, instruction document or existing DOCX to edit.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
lo_profile=${'$'}(mktemp -d /tmp/nativecode-office.XXXXXX)
# Substitute the actual source file and requested output format.
libreoffice "-env:UserInstallation=file://${'$'}lo_profile" \
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
""",
    )

    private fun makePdf() = Skill(
        id = "make-pdf",
        title = "Make PDF",
        summary = "A new printable report, handout, invoice-like document, or typeset PDF brief.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
""",
    )

    private fun editPdf() = Skill(
        id = "edit-pdf",
        title = "Edit PDF",
        summary = "An existing PDF and explicit merge, split, reorder, rotate, crop or metadata changes.",
        source = "",
        body = """---
name: edit-pdf
description: An existing PDF and explicit merge, split, reorder, rotate, crop or metadata changes. Complete this task inside
  NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Edit PDF
  category: PROJECT
  group: Documents
  engine: pypdf for structural operations; pdfplumber for inspection
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Edit PDF

**Use for:** An existing PDF and explicit merge, split, reorder, rotate, crop or metadata changes.

**Deliver:** A new PDF, operation manifest and page-count/content checks.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### PDF inspection/rendering tools, only when used

For page-text/structure work install the needed package into the task environment: `.venv/bin/python -m pip install pypdf pdfplumber`. Skip either package when the operation does not need it. For page previews check `pdfinfo`, `pdftoppm` and `pdftotext`; install the configured native repository's `poppler-utils` package when available. These are CPU utilities, not GPU applications. Preserve source files and save outputs under the task folder.

## Do the task

1. Inspect the supplied PDF, page count/boxes/rotation, encryption/forms/bookmarks and exact requested operation. Preserve the original; use only authorized access to protected documents.
2. Use pypdf for structural operations such as merge, split, reorder, rotate, crop and metadata changes. Use pdfplumber for inspection/table extraction when required. Do not pretend structural manipulation is a general word-processor edit of PDF text.
3. Apply only the requested operation and write a new PDF. Maintain an operation manifest mapping input pages to output pages.
4. Reopen output to verify page count/order, rotation/boxes, required forms/bookmarks and text. Render affected pages with an available Poppler renderer and inspect them against the input.
5. For redaction or arbitrary text replacement, use a tool that actually removes/replaces underlying content and verify it; a white rectangle is not secure redaction. Do not claim pypdf alone supplies every requested editing feature.

## Check the actual result

Require valid output, exact requested page transformation, original preservation and visual/content checks appropriate to the operation. Record any changed metadata or unsupported feature. PDF parsing/structural edits are CPU work; NativeCode handles file preview and notifications.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/py-pdf/pypdf
- https://github.com/jsvine/pdfplumber
""",
    )

    private fun readDocuments() = Skill(
        id = "read-documents",
        title = "Read Documents",
        summary = "Local PDF, DOCX, PPTX, XLSX or other supported documents to extract, summarize or compare.",
        source = "",
        body = """---
name: read-documents
description: Local PDF, DOCX, PPTX, XLSX or other supported documents to extract, summarize or compare. Complete this task
  inside NativeCode using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Read Documents
  category: PROJECT
  group: Documents
  engine: MarkItDown + format-native readers; pdfplumber for PDF tables
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Read Documents

**Use for:** Local PDF, DOCX, PPTX, XLSX or other supported documents to extract, summarize or compare.

**Deliver:** Structured Markdown/JSON extraction and an answer tied to exact source pages/sheets/sections.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### Install only readers for the input formats

Use existing text/file tools first. For DOCX install `python-docx`; for XLSX install `openpyxl`; for PDF text/tables install `pypdf`/`pdfplumber`. For a broader conversion-to-text route, install [MarkItDown](https://github.com/microsoft/markitdown) with only the format extras declared by the selected release. Do not install `all` extras or neural models just to read one document.

Commands use the task environment, for example `.venv/bin/python -m pip install python-docx` for DOCX only. For scanned pages use an existing visual viewer first. OCR is a last resort when no text/usable visual reading is available; install only a compatible OCR tool and the required language data, and mark OCR uncertainty. Do not claim scanned pages contain no relevant content merely because text extraction is empty.

## Do the task

1. Identify every requested input and its actual version. Read the relevant pages/sheets/sections, not an unrelated file or a truncated preview.
2. Extract content with its structure and provenance: PDF page numbers, DOCX section/paragraph labels, spreadsheet sheet names/cell ranges or slide numbers. Preserve ordering, headers, units and table relationships.
3. For tables/figures or scanned content, inspect the actual page images or available viewer. Do not replace source evidence with general knowledge or silently infer missing numeric values.
4. Answer/summarize using only what the requested source supports. Clearly mark any allowed outside research or inference separately; quote minimally and retain source locations.
5. Save requested structured extraction as Markdown/JSON/CSV with a source map. Use NativeCode file/preview features as needed; text extraction does not need a GPU.

## Check the actual result

Require coverage of all requested files/versions, accurate source locations and preserved table semantics. Distinguish missing content, unavailable rendering and OCR uncertainty. Avoid uploading private files to a service without authorization.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://github.com/microsoft/markitdown
- https://github.com/jsvine/pdfplumber
- https://github.com/python-openxml/python-docx
- https://openpyxl.readthedocs.io/en/stable/
""",
    )

    private fun convertDocuments() = Skill(
        id = "convert-documents",
        title = "Convert Documents",
        summary = "A precise source/target format pair such as Markdown→DOCX or DOCX/PPTX→PDF.",
        source = "",
        body = """---
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
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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
lo_profile=${'$'}(mktemp -d /tmp/nativecode-office.XXXXXX)
# Substitute the actual source file and requested output format.
libreoffice "-env:UserInstallation=file://${'$'}lo_profile" \
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
""",
    )

    private fun makeSpreadsheet() = Skill(
        id = "make-spreadsheet",
        title = "Make Spreadsheet",
        summary = "A spreadsheet, tracker, budget-like model, data table or existing XLSX to update.",
        source = "",
        body = """---
name: make-spreadsheet
description: A spreadsheet, tracker, budget-like model, data table or existing XLSX to update. Complete this task inside NativeCode
  using the inline setup and workflow.
compatibility: NativeCode ARM64 PRoot; use the installed Alpaquita/glibc runtime and existing host/GPU/media bridges
metadata:
  title: Make Spreadsheet
  category: PROJECT
  group: Documents
  engine: openpyxl; Python calculations explicitly separated
  setup: inline; no prerequisite skills
  status: authored; not independently device-tested
---

# Make Spreadsheet

**Use for:** A spreadsheet, tracker, budget-like model, data table or existing XLSX to update.

**Deliver:** Editable XLSX, formulas where appropriate, source data and a validation report.

## NativeCode fast path

Work in the current project as `flux`; use `/home/flux/repos/<project>` only for a new project. Keep the existing stack, source files, lockfiles and user configuration. This skill is self-contained: **do not load another skill or wait for a dependency card**. Reuse working tools; the setup section below covers what to install when this task actually needs it.

```sh
export PATH="/home/flux/.local/bin:/home/flux/.local/share/nativecode/tools/node/current/bin:${'$'}PATH"
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

### Workbook package

Install only the task's spreadsheet package when missing:

```sh
.venv/bin/python -m pip install openpyxl
.venv/bin/python -c 'import openpyxl; print(openpyxl.__version__)'
```

Use an existing compatible environment/lockfile when present. Add other analysis packages only for a real task requirement. This NativeCode workflow uses openpyxl; it does not depend on a hosted spreadsheet tool or another skill. Do not install an office suite merely to write XLSX.

## Do the task

1. Read the actual input workbook/data and requested calculations/layout. Preserve existing sheet names, formulas, formats and macros/features when supported; identify unsupported features before rewriting an existing complex file.
2. Use openpyxl to create/edit cells, formulas, formats, tables, validation and appropriate charts. Keep data types and number/date formats correct; avoid coercing IDs into numbers or untrusted text into formulas.
3. Keep calculations formula-driven where the workbook requires formulas, with clear inputs and references. openpyxl does not evaluate formulas: do not report empty/stale cached values as recalculated results. Independently validate expected numbers in Python and clearly separate them from Excel formula caches.
4. Reopen the saved workbook with formulas preserved and check key ranges, references, duplicate table names, row/column bounds and missing values. Use a compatible existing viewer for visual inspection when available; do not require installing one to perform basic data validation.
5. Deliver the editable XLSX, source data/script and actual validation results. Use NativeCode file/progress features. Spreadsheet generation/validation is CPU-based; a GPU display route is not a formula calculation engine.

## Check the actual result

Require correct data/types/formulas, sensible widths/heights/wrapping, intact source workbook and accurate recalculation status. Do not claim formulas have been evaluated unless a real calculation engine did so. Keep private data local unless transfer was authorized.

## NativeCode progress, preview and delivery

Use available host features for their actual purpose: `nativecode-agent toast --text "Task started"` for a useful start message, `nativecode-agent telemetry` before large builds/renders, and `nativecode-agent status --key <task-id> --text <real-status> --progress <real-percent>` only when progress is measurable. Do not spam alerts.

For a local preview, run only the task server on `127.0.0.1`, keep its PID/log, wait for HTTP readiness, then call `nativecode-agent open-link --url http://127.0.0.1:<actual-port> --title "Preview"`. This opens the host/system browser; it does **not** prove that the guest Chromium GPU wrapper was used. For a built APK, offer `nativecode-agent install-apk --path <actual-apk>`; it asks the user through Android's installer and is not silent installation.

Only one NativeCode alert can be active. On `alert_already_active`, wait or ask; do not dismiss an unrelated user alert. Stop only task-owned processes. Send `nativecode-agent notify --title "Task complete" --message <actual-result>` after a long job.

Deliver existing output paths and editable source through the current file UI. There is no documented generic `nativecode-agent open-file` or `share-file` command. Serve an isolated output folder only when a browser preview is useful, never the whole home directory. Report **created**, **built/rendered**, **checked**, and **device/hardware-verified** separately. Preserve originals and credentials; do not publish/upload automatically. This project workflow was authored from the supplied setup records and has not itself been device-tested by the pack author.

## Sources and setup provenance

The NativeCode paths and bridge/launcher patterns above are adapted from the user-supplied `create-skill.zip` installation records and the supplied NativeCode source. These project instructions are newly authored; the original installation files remain unmodified in the pack. Do not load those other files to perform this task: the necessary setup is included above.

- https://openpyxl.readthedocs.io/en/stable/
""",
    )

    private fun installRea() = Skill(
        id = "install-rea",
        title = "REA — Reverse Engineer Anything",
        summary = "Install REA to reverse engineer apps and binaries.",
        source = "",
        body = """---
name: "install-rea"
description: "Install and check REA (Reverse Engineer Anything), an MCP server and CLI for reverse engineering JavaScript/Electron apps, .NET assemblies and Android APKs, on NativeCode ARM64 PRoot. Use when the user asks to reverse engineer an app or binary and REA is missing."
compatibility: "NativeCode ARM64 PRoot (Alpaquita glibc); Node.js and npm; opencode 2.x, Claude Code, Codex, Grok, Qwen, Antigravity"
metadata:
  title: "REA — Reverse Engineer Anything"
  category: "INSTALL"
  group: "Reverse engineering"
  engine: "rea-agents (npm) 6.8.0, MIT"
  status: "user-copied recipe; nothing is bundled with NativeCode"
  dependencies: "install-node-tools"
  reviewed: "2026-11-15"
---

# REA — Reverse Engineer Anything

**Category:** INSTALL PROGRAMS  |  **Mechanism:** npm package `rea-agents` pinned to 6.8.0 (MIT, https://github.com/morluto/rea)

REA is one MCP server plus a `rea` CLI plus an investigation skill. Your agent calls it to understand how an app works. The user copied this text into you on purpose; nothing is installed until you run the steps below. Work as the `flux` user in `${'$'}HOME`, never as root, except for the one `apk add` in step 2 if it is needed.

## What works on this phone
- **JavaScript and Electron apps:** works. Module and import recovery, Electron boundaries. No native engine needed.
- **.NET assemblies:** static analysis works.
- **Android APKs:** works after the Android tools skill is done (JADX and Apktool are needed). Run that skill first.
- **Native ARM64/x86 binaries (deep pseudocode):** needs Hopper, Ghidra, IDA or Kuna. Hopper does not exist for aarch64 Linux. Ghidra is optional and manual; do not install it unless the user asks.
- REA is **not a sandbox**. It analyses files, it does not isolate them.

## 1. Check what is already there
```sh
node -v          # must satisfy ^22.23.4 || ^24.22.1 || >=26.12.1
npm -v
command -v rea && rea --version
command -v ps && ps --version
```
If `node` is missing or too old, stop and run the Install Node Tools skill first. If `rea` already works, skip to step 4.

## 2. procps `ps` (Linux requirement)
REA's process supervisor needs a procps-compatible `ps` for Ghidra, JADX and JavaScript/Electron analysis. If `ps --version` does not print `procps`, ask the user to open a **root** Linux shell tab in NativeCode and run:
```sh
apk add procps
```
Do not use `sudo` or `su` yourself.

## 3. Install, from `${'$'}HOME`
```sh
cd "${'$'}HOME"
npm install --global rea-agents@6.8.0
rea --version
```
If `npm install --global` fails with `EACCES`, point npm at the user prefix (`~/.local/bin` is already on PATH in NativeCode) and retry:
```sh
npm config set prefix "${'$'}HOME/.local"
```
Run package installs from `${'$'}HOME`, not from inside the project being analysed.

## 4. Register REA with the agent(s) the user has
Preview first; this writes nothing:
```sh
rea setup --client opencode --dry-run
```
Client ids: `opencode`, `claude_code`, `codex`, `grok_build`, `qwen_code`, `antigravity`. Add one `--client` flag per agent. Apply with:
```sh
rea setup --client opencode --yes
rea doctor --client opencode --json
```
`rea setup --all-detected --yes` configures every supported agent it finds; use it only if the user wants that. Setup backs up any config file it changes (look for the `.rea.backup` files named in its output). Restart the agent afterwards.

### opencode 2.x
opencode 2.x keeps MCP servers under `mcp.servers.<name>` in `~/.config/opencode/opencode.jsonc` (not directly under `mcp`, which was the 1.x layout). After setup, confirm REA is listed:
```sh
opencode mcp list
```
If it is missing, do not guess: run `rea setup --client opencode --dry-run`, take the command it would register and add it in the 2.x shape:
```jsonc
{ "mcp": { "servers": { "rea": { "type": "local", "command": ["<command from dry-run>", "<args>"] } } } }
```
Skills for opencode live in `~/.config/opencode/skills/<name>/SKILL.md`; setup installs the REA workflow skill there.

### Other agents
Each agent reads its own config; `rea setup` writes it. After a restart, ask the agent to list its MCP tools and confirm the REA tools appear.

## 5. Check
```sh
rea doctor --json
```
`ok` for the JavaScript/Electron path is enough for the first use. Whether REA's PTY prebuilt covers linux-arm64 glibc is unverified: if `doctor` reports a missing or failing native module, report the exact error to the user and stop. Do not compile or patch REA to work around it.

First real use, no engine needed (replace the path):
```sh
rea --help
```
then ask the agent to analyse an unpacked Electron app folder.

## Update, pin and undo
- REA releases weekly. This skill pins 6.8.0 so a copy behaves the same every time. To move on, change the version in step 3 or run `rea update`, then `rea doctor --json` again.
- Undo registrations: `rea uninstall` (removes what setup added for every client, keeps unrelated entries), or restore a `.rea.backup` file over its config.
- Remove the program: `npm remove --global rea-agents`.

## Safety
Everything you analyse is **untrusted input**. Never run an analysed binary, installer or script, never `chmod +x` it, and never paste its strings back into a shell. Keep analysed files in a project folder, not in `${'$'}HOME` dotfiles. Do not upload the target anywhere. REA analyses locally; your model provider still sees the tool results.

## Report
State which parts are **already-working**, **installed-and-checked** (with the `rea doctor` result), **blocked** (with the exact error) or **not attempted** (native engines, APK tools). Record version, install path and which agents were registered.
""",
    )


    private fun installHermes() = Skill(
        id = "install-hermes",
        title = "Hermes Agent",
        summary = "Install the Hermes agent by hand.",
        source = "",
        body = """---
name: "install-hermes"
description: "Install and check Hermes Agent (Nous Research) in the NativeCode PRoot guest. Use when the user wants Hermes and it is not installed; NativeCode does not bundle it yet."
compatibility: "NativeCode ARM64 PRoot (Alpaquita glibc); needs several hundred MB of free space"
metadata:
  title: "Hermes Agent"
  category: "INSTALL"
  group: "AI agents"
  engine: "hermes-agent 0.23.1 (MIT) official installer"
  status: "user-copied recipe; not bundled, not in the NativeCode launchers yet"
  dependencies: "install-dev-utils"
  reviewed: "2026-11-15"
---

# Hermes Agent

**Category:** INSTALL PROGRAMS  |  **Mechanism:** official installer from https://hermes-agent.nousresearch.com, source https://github.com/NousResearch/hermes-agent (MIT, 0.23.1)

State of NativeCode today: **Hermes is not installed, not bundled and not in the NativeCode tool launchers or CLI login pages.** This skill is the manual route, run in a normal Linux shell tab as the `flux` user. A built-in Hermes pack is planned; do not claim it exists.

## 1. Check first
```sh
command -v hermes && hermes --version
df -h "${'$'}HOME" | tail -1
uname -m; getconf GNU_LIBC_VERSION
```
If `hermes` already works, skip to step 4. Hermes needs roughly 0.6-1.2 GB installed (estimate: it ships Python 3.14 and about 350 locked packages, some large). If free space is below 2 GB, stop and tell the user.

## 2. Download the installer and read it
Do not pipe the web into a shell. Download, show the user the size and head of the script, then run it:
```sh
cd "${'$'}HOME"
curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o hermes-install.sh
wc -c hermes-install.sh; head -40 hermes-install.sh
```
The installer refuses to run when `TERMUX_VERSION` is set or `PREFIX` points into `com.termux`. NativeCode's guest does neither; if it complains anyway, report the exact message and stop.

## 3. Install without the interactive wizard and the browser tools
```sh
bash hermes-install.sh --skip-setup --skip-browser
exec "${'$'}SHELL" -l
hermes --version
```
`--dir` and `--hermes-home` relocate the install and the config; keep the defaults unless the user asks (config lives in `~/.hermes`). Time the install and note how long the first `hermes` start takes under PRoot; both are unmeasured.

## 4. Configure and check
Credentials are the user's job: never ask the user to paste a key into the chat and never print one.
```sh
hermes model        # choose provider and model (device-code login for OAuth providers)
hermes doctor       # diagnose problems
hermes              # interactive CLI
```
Other useful commands: `hermes setup` (full wizard), `hermes tools`, `hermes config get|set`, `hermes update`.

## 5. Notes for this phone
- Use the **Linux shell** tab; the Hermes launcher card does not exist yet.
- Free NativeCode plan: Hermes runs as an ordinary program in the guest, but the NativeCode agent tools (chat, login pages) do not manage it.
- Keep `~/.hermes/auth.json` and `~/.hermes/.env` out of backups, project folders and git.
- A Hermes auto-update can pull large downloads; ask before running `hermes update`.

## Undo
Remove the install directory the installer printed (default under `~/.hermes` and its program directory), the `hermes` link it created and any PATH line it added to `~/.profile`, `~/.zshrc` or `~/.bashrc`. List what you will delete and get the user's OK first. `rm -rf ~/.hermes` also deletes their sessions and keys.

## Report
Say **already-working**, **installed-and-checked** (version and `hermes doctor` result), **blocked** (exact error) or **planned-only**. Record version, install size (`du -sh`), install time and path.
""",
    )


    private fun installSsh() = Skill(
        id = "install-ssh",
        title = "SSH Server and Client",
        summary = "Use the SSH client and the key-only SSH server.",
        source = "",
        body = """---
name: "install-ssh"
description: "Use SSH on NativeCode: the built-in SSH client and saved hosts, and the optional key-only SSH server (port 8022, user flux). Use when the user wants to log in to the phone from another computer or to a remote machine from the phone."
compatibility: "NativeCode 1.8 ARM64 PRoot (Alpaquita glibc); OpenSSH from the Alpaquita repository"
metadata:
  title: "SSH Server and Client"
  category: "INSTALL"
  group: "Remote access"
  engine: "OpenSSH (bundled in the NativeCode Linux runtime update), managed by the NativeCode SSH page"
  status: "NativeCode manages the server; the agent only checks and helps with keys"
  dependencies: ""
  reviewed: "2026-11-15"
---

# SSH Server and Client

**Category:** INSTALL PROGRAMS  |  **Mechanism:** OpenSSH inside the guest, controlled from NativeCode **Settings → SSH**

## What NativeCode already does
- **Client (Free):** the `ssh` command in the guest, plus saved remote hosts on the SSH page; a saved host opens an `ssh` terminal tab. Keys live in `~/.ssh` of the guest.
- **Server (Pro):** off by default. The user switches it on in Settings → SSH; NativeCode starts `sshd` as `flux`, **key login only**, port **8022**, bound to the Wi-Fi (or hotspot) address, with a notification that has a Stop action. It stops when Wi-Fi is lost or Pro ends.
- OpenSSH ships with the NativeCode Linux runtime update (Settings > Components); you do not need to install it yourself.

Do **not** start `sshd` yourself, edit `~/.config/nativecode/sshd_config` (it is rewritten on every start) or open a different port. Passwords, root login and agent/X11 forwarding are off by design and must stay off.

## Check
```sh
command -v ssh ssh-keygen sshd
ls -l ~/.ssh 2>/dev/null
ssh -V
```
If a command is missing, tell the user to open Settings → SSH and tap install; then re-check.

## Let another computer in (server)
1. On the other computer, create a key if it has none: `ssh-keygen -t ed25519`. Copy the **public** key (`~/.ssh/id_ed25519.pub`, one line).
2. In NativeCode, Settings → SSH, paste the public key (or import a file). The page shows its fingerprint; it must match `ssh-keygen -lf id_ed25519.pub` on the computer. Lines starting with options such as `command=` or `from=` are rejected.
3. Start the server there; the page shows the address. From the computer:
```sh
ssh -p 8022 flux@<phone-wifi-ip>
scp -P 8022 file flux@<phone-wifi-ip>:/home/flux/
```
Only the same Wi-Fi network can reach it. The first connection shows the phone's host-key fingerprint, which the SSH page also shows: compare them before answering yes.

## Log in to a remote machine (client)
```sh
ssh-keygen -t ed25519 -C "phone"         # once; keep the passphrase
cat ~/.ssh/id_ed25519.pub                # give this to the remote's authorized_keys
ssh user@host
```
Better: ask the user to add the host on the SSH page so it opens as a tab with the right command. Never print or copy `~/.ssh/id_*` private keys; only `.pub` files may leave the phone.

## Agents and background work
- Long jobs over SSH survive a dropped connection only inside `tmux` or `screen` (install them with `apk add tmux` as root if missing).
- The phone sleeps: the NativeCode notification keeps the server alive, but battery saver can still cut Wi-Fi.

## Troubleshooting
- `Permission denied (publickey)`: the key on the page is not the one the computer offers. Compare fingerprints.
- `Connection refused`: server is off, wrong port (8022, not 22) or different network.
- `chown /dev/pts failed`: fixed by NativeCode when it starts the server; do not work around it.

## Report
State whether the client works, whether the server is on, the address and port the page shows, and the key fingerprints you compared. Never include private keys.
""",
    )

    private fun frontendDesign() = Skill(
        id = "frontend-design",
        title = "Frontend Design",
        summary = "Distinctive, intentional visual design for new or existing UI.",
        source = "anthropics/skills e4bb5d9 - Apache-2.0",
        body = """---
name: frontend-design
description: Guidance for distinctive, intentional visual design when building new UI or reshaping an existing one. Helps with aesthetic direction, typography, and making choices that don't read as templated defaults.
license: Complete terms in LICENSE.txt
---

# Frontend Design

Approach this as the design lead at a design studio known for giving every client a distinct visual identity that is not mistaken for anyone else's. This client has already rejected proposals that felt cliché or templated, and is paying for a distinctive point of view: make deliberate, opinionated choices about palette, typography, and layout that are specific to this brief, and take aesthetic risk if justified.

## Ground your designs in the subject matter

If the brief does not identify what the product or subject matter is, identify it yourself before designing, and confirm with the client. You can come up with one concrete subject, the design's audience, and the design's primary job, as a proposal. If there's any information in your memory about the client's preferences or context about what they're building, use that as a hint. The subject's industry, subject matter, materials, and vernacular are where distinctive visual choices come from — a design for a toy for girls aged 8–11 will be very aesthetically different from a dashboard for financial analysts. Build with the brief's real content and subject matter throughout.

## Design principles

For web designs, the hero is the first thing viewers will see. Open with the most characteristic thing in the subject's world, in the form that is most appropriate: a headline, an image, an animation, a live demo, an interactive moment, or other treatments. Be deliberate with your choice: a big number with a small label, supporting stats, and a gradient accent is the default treatment, so only use it if that's truly the best option.

Typography carries the personality of the page. You don't need a different typeface for display or headline text and body content: use one family or two, and if two, make them clearly distinct.

Choose your typefaces deliberately, not the default families you would reach for on any other project, and set a clear type scale following the default guidance of The Elements of Typographic Style with intentional weights, widths, and spacing. When type is used as a headline or visual element, use the type treatment itself as an active part of the design, not a neutral delivery vehicle for the content.

Default to line lengths of less than 80 characters. Serif typefaces can have slightly longer line lengths; give serif body text slightly more line-height than a sans-serif.

Avoid these default typographic treatments; they are the commonest tells of a generated page:
- Accenting just a single word or phrase in a headline, like putting one word in italic/bold or a different color.
- Using all caps for labels.
- Adding unnecessary typographic labels above content.

Visual structure is information. Structural devices like outlines, borders, numbering, eyebrows, dividers, labels, etc., encode useful information about the content rather than decorate it. Many generic designs use numbered markers (01 / 02 / 03), but that's only appropriate if the content actually is a sequence — like a stepped process or a timeline. Before adding numbered markers, check the content really is a sequence.

Use non-user-triggered motion sparingly and deliberately, only to draw attention. A single orchestrated moment — one page-load sequence or one reveal — lands better than scattered effects; fade-and-slide-up entrances on each section and hover transitions on every card are the generic default and read as AI-generated. Motion that answers a person's action (opening, expanding, confirming) is welcome when it shows what changed.

Consider written content carefully. Often a design brief may not contain real content, and it's up to you to come up with copy and placeholder content. Copy can make a design feel as templated as the design itself. See the below section on writing for more guidance.

## Process: plan, review against the brief, build, critique

For calibration, AI-generated design right now clusters around some traits:
1. a warm cream background (near #F4F1EA) with a high-contrast serif display and a terracotta or warm-clay accent (often near #D97757 — Anthropic's own Claude-interaction accent, so on a user's brief it reads as a tell);
2. a near-black background with a single bright acid-green or vermilion accent;
3. a broadsheet-style layout with hairline rules, zero border-radius, and dense newspaper-like columns;
4. the SaaS-card kit: content chopped into identical rounded cards, one border-radius on everything regardless of hierarchy, the same soft grey shadow (rgba(0,0,0,.1)) under each, and gradient washes as decoration;
5. template chrome that appears whatever the subject: a tracked-out ALL-CAPS eyebrow label above every heading; meta strings joined with middle dots ('A · B · C'); labels built as 'WORD — fragment' with a spaced em dash; tinted near-black (#0B0B0B, #111) standing in for black; a monospace face for small data labels; a '→' appended to link and button text.

All traits are legitimate for some briefs, but they are defaults rather than choices, and they appear regardless of subject. Where the brief pins down a visual direction, follow it exactly — the brief's own words always win, including when it asks for one of these looks. Where it leaves an axis free, don't spend that freedom on one of these defaults. As with a hired human designer, there's often a careful balance between doing what you're good at and taking each project as a chance to experiment and learn.

Work in two passes. First, brainstorm a short design plan based on the client's design brief: create a compact token system with color, type, layout, and principles.
- Color: describe the core base palette as 4–6 named hex values.
- Type: the typefaces and their roles.
- Layout: a layout concept, using one-sentence prose descriptions and ASCII wireframes to ideate and compare. Include alignment guidance; should the content be left aligned, center aligned, justified?
- Principles: the high-level guidance for what makes this page unique.

Then review that plan against the brief before building: if any part of it reads like the generic default you would produce for any similar page (work through a similar prompt to see if you arrive somewhere similar) rather than a choice made for this specific brief — revise that part, say what you changed and why. Only after you've confirmed the relative uniqueness of your design plan should you start to write the code, following the revised plan.

When writing the code, be careful of structuring your CSS selector specificities. It's easy to generate CSS classes that cancel each other out (especially with a type-based selector like .section and an element-based selector like .cta). This can happen often with padding/margin between sections.

## Restraint and self-critique

Spend your boldness in one place. Let one element be the memorable thing, keep everything around it quiet and disciplined, and cut any decoration that does not serve the brief. Build to a quality floor without announcing it: responsive down to mobile, visible keyboard focus, reduced motion respected, visually accessible, harmonious color palettes. Critique your own work as you build, taking screenshots to review if your environment supports it — a picture is worth 1000 tokens. Consider Chanel's advice: before leaving the house, take a look in the mirror and remove one accessory. Human creatives have memory and always try to do something new, so if you have a space to quickly jot down notes about what you've tried, it can help you in future passes.

## More on writing in design

Words appear in a design for one reason: to make it easier to understand and use. They are design content, not decoration. Bring the same intentionality and minimalism to copywriting that you would bring to spacing and color. Before writing anything, ask what the design needs to say, and how it can best be said to help the person navigate the experience.

Write from the end user's perspective. Name things by what users will understand in simple language, not by how the system is built. A user manages notifications, not webhook config. Describe what something is or does in plain terms rather than selling it. Being specific and legible to new users is always better than being clever.

Use active voice as default. A CTA says exactly what happens when it is used: "Save changes," not "Submit." An action keeps the same name through the whole flow, so the button that says "Publish" produces a toast that says "Published." The vocabulary of an interface is the signposting for someone navigating the product. Cohesion and consistency are how people learn their way around.

Treat failure and emptiness as moments for direction, not mood. Explain what went wrong and how to fix it, in the interface's voice rather than a person's. Errors don't apologize, and they are never vague about what happened. An empty screen is an invitation to act.

Keep the tone conversational: plain verbs, sentence case, no filler, with tone matched to the brand and the audience. Let each written element do exactly one job.
""",
    )

    private fun themeFactory() = Skill(
        id = "theme-factory",
        title = "Theme Factory",
        summary = "Apply one of 10 curated color and font themes to slides, docs or HTML pages.",
        source = "anthropics/skills e4bb5d9 - Apache-2.0",
        body = """---
name: theme-factory
description: Toolkit for styling artifacts with a theme. These artifacts can be slides, docs, reportings, HTML landing pages, etc. There are 10 pre-set themes with colors/fonts that you can apply to any artifact that has been creating, or can generate a new theme on-the-fly.
license: Complete terms in LICENSE.txt
---


# Theme Factory Skill

This skill provides a curated collection of professional font and color themes themes, each with carefully selected color palettes and font pairings. Once a theme is chosen, it can be applied to any artifact.

## Purpose

To apply consistent, professional styling to presentation slide decks, use this skill. Each theme includes:
- A cohesive color palette with hex codes
- Complementary font pairings for headers and body text
- A distinct visual identity suitable for different contexts and audiences

## Usage Instructions

To apply styling to a slide deck or other artifact:

1. **Show the theme showcase**: Display the `theme-showcase.pdf` file to allow users to see all available themes visually. Do not make any modifications to it; simply show the file for viewing.
2. **Ask for their choice**: Ask which theme to apply to the deck
3. **Wait for selection**: Get explicit confirmation about the chosen theme
4. **Apply the theme**: Once a theme has been chosen, apply the selected theme's colors and fonts to the deck/artifact

## Themes Available

The following 10 themes are available, each showcased in `theme-showcase.pdf`:

1. **Ocean Depths** - Professional and calming maritime theme
2. **Sunset Boulevard** - Warm and vibrant sunset colors
3. **Forest Canopy** - Natural and grounded earth tones
4. **Modern Minimalist** - Clean and contemporary grayscale
5. **Golden Hour** - Rich and warm autumnal palette
6. **Arctic Frost** - Cool and crisp winter-inspired theme
7. **Desert Rose** - Soft and sophisticated dusty tones
8. **Tech Innovation** - Bold and modern tech aesthetic
9. **Botanical Garden** - Fresh and organic garden colors
10. **Midnight Galaxy** - Dramatic and cosmic deep tones

## Theme Details

Each theme is defined in the `themes/` directory with complete specifications including:
- Cohesive color palette with hex codes
- Complementary font pairings for headers and body text
- Distinct visual identity suitable for different contexts and audiences

## Application Process

After a preferred theme is selected:
1. Read the corresponding theme file from the `themes/` directory
2. Apply the specified colors and fonts consistently throughout the deck
3. Ensure proper contrast and readability
4. Maintain the theme's visual identity across all slides

## Create your Own Theme
To handle cases where none of the existing themes work for an artifact, create a custom theme. Based on provided inputs, generate a new theme similar to the ones above. Give the theme a similar name describing what the font/color combinations represent. Use any basic description provided to choose appropriate colors/fonts. After generating the theme, show it for review and verification. Following that, apply the theme as described above.

---

# Bundled theme files

The theme files this skill refers to, included here verbatim so the skill is a single document in NativeCode. (Upstream also ships theme-showcase.pdf, not included.)

---

## themes/arctic-frost.md

# Arctic Frost

A cool and crisp winter-inspired theme that conveys clarity, precision, and professionalism.

## Color Palette

- **Ice Blue**: `#d4e4f7` - Light backgrounds and highlights
- **Steel Blue**: `#4a6fa5` - Primary accent color
- **Silver**: `#c0c0c0` - Metallic accent elements
- **Crisp White**: `#fafafa` - Clean backgrounds and text

## Typography

- **Headers**: DejaVu Sans Bold
- **Body Text**: DejaVu Sans

## Best Used For

Healthcare presentations, technology solutions, winter sports, clean tech, pharmaceutical content.

---

## themes/botanical-garden.md

# Botanical Garden

A fresh and organic theme featuring vibrant garden-inspired colors for lively presentations.

## Color Palette

- **Fern Green**: `#4a7c59` - Rich natural green
- **Marigold**: `#f9a620` - Bright floral accent
- **Terracotta**: `#b7472a` - Earthy warm tone
- **Cream**: `#f5f3ed` - Soft neutral backgrounds

## Typography

- **Headers**: DejaVu Serif Bold
- **Body Text**: DejaVu Sans

## Best Used For

Garden centers, food presentations, farm-to-table content, botanical brands, natural products.

---

## themes/desert-rose.md

# Desert Rose

A soft and sophisticated theme with dusty, muted tones perfect for elegant presentations.

## Color Palette

- **Dusty Rose**: `#d4a5a5` - Soft primary color
- **Clay**: `#b87d6d` - Earthy accent
- **Sand**: `#e8d5c4` - Warm neutral backgrounds
- **Deep Burgundy**: `#5d2e46` - Rich dark contrast

## Typography

- **Headers**: FreeSans Bold
- **Body Text**: FreeSans

## Best Used For

Fashion presentations, beauty brands, wedding planning, interior design, boutique businesses.

---

## themes/forest-canopy.md

# Forest Canopy

A natural and grounded theme featuring earth tones inspired by dense forest environments.

## Color Palette

- **Forest Green**: `#2d4a2b` - Primary dark green
- **Sage**: `#7d8471` - Muted green accent
- **Olive**: `#a4ac86` - Light accent color
- **Ivory**: `#faf9f6` - Backgrounds and text

## Typography

- **Headers**: FreeSerif Bold
- **Body Text**: FreeSans

## Best Used For

Environmental presentations, sustainability reports, outdoor brands, wellness content, organic products.

---

## themes/golden-hour.md

# Golden Hour

A rich and warm autumnal palette that creates an inviting and sophisticated atmosphere.

## Color Palette

- **Mustard Yellow**: `#f4a900` - Bold primary accent
- **Terracotta**: `#c1666b` - Warm secondary color
- **Warm Beige**: `#d4b896` - Neutral backgrounds
- **Chocolate Brown**: `#4a403a` - Dark text and anchors

## Typography

- **Headers**: FreeSans Bold
- **Body Text**: FreeSans

## Best Used For

Restaurant presentations, hospitality brands, fall campaigns, cozy lifestyle content, artisan products.

---

## themes/midnight-galaxy.md

# Midnight Galaxy

A dramatic and cosmic theme with deep purples and mystical tones for impactful presentations.

## Color Palette

- **Deep Purple**: `#2b1e3e` - Rich dark base
- **Cosmic Blue**: `#4a4e8f` - Mystical mid-tone
- **Lavender**: `#a490c2` - Soft accent color
- **Silver**: `#e6e6fa` - Light highlights and text

## Typography

- **Headers**: FreeSans Bold
- **Body Text**: FreeSans

## Best Used For

Entertainment industry, gaming presentations, nightlife venues, luxury brands, creative agencies.

---

## themes/modern-minimalist.md

# Modern Minimalist

A clean and contemporary theme with a sophisticated grayscale palette for maximum versatility.

## Color Palette

- **Charcoal**: `#36454f` - Primary dark color
- **Slate Gray**: `#708090` - Medium gray for accents
- **Light Gray**: `#d3d3d3` - Backgrounds and dividers
- **White**: `#ffffff` - Text and clean backgrounds

## Typography

- **Headers**: DejaVu Sans Bold
- **Body Text**: DejaVu Sans

## Best Used For

Tech presentations, architecture portfolios, design showcases, modern business proposals, data visualization.

---

## themes/ocean-depths.md

# Ocean Depths

A professional and calming maritime theme that evokes the serenity of deep ocean waters.

## Color Palette

- **Deep Navy**: `#1a2332` - Primary background color
- **Teal**: `#2d8b8b` - Accent color for highlights and emphasis
- **Seafoam**: `#a8dadc` - Secondary accent for lighter elements
- **Cream**: `#f1faee` - Text and light backgrounds

## Typography

- **Headers**: DejaVu Sans Bold
- **Body Text**: DejaVu Sans

## Best Used For

Corporate presentations, financial reports, professional consulting decks, trust-building content.

---

## themes/sunset-boulevard.md

# Sunset Boulevard

A warm and vibrant theme inspired by golden hour sunsets, perfect for energetic and creative presentations.

## Color Palette

- **Burnt Orange**: `#e76f51` - Primary accent color
- **Coral**: `#f4a261` - Secondary warm accent
- **Warm Sand**: `#e9c46a` - Highlighting and backgrounds
- **Deep Purple**: `#264653` - Dark contrast and text

## Typography

- **Headers**: DejaVu Serif Bold
- **Body Text**: DejaVu Sans

## Best Used For

Creative pitches, marketing presentations, lifestyle brands, event promotions, inspirational content.

---

## themes/tech-innovation.md

# Tech Innovation

A bold and modern theme with high-contrast colors perfect for cutting-edge technology presentations.

## Color Palette

- **Electric Blue**: `#0066ff` - Vibrant primary accent
- **Neon Cyan**: `#00ffff` - Bright highlight color
- **Dark Gray**: `#1e1e1e` - Deep backgrounds
- **White**: `#ffffff` - Clean text and contrast

## Typography

- **Headers**: DejaVu Sans Bold
- **Body Text**: DejaVu Sans

## Best Used For

Tech startups, software launches, innovation showcases, AI/ML presentations, digital transformation content.
""",
    )

    private fun tasteDesign() = Skill(
        id = "taste-design",
        title = "Taste Design",
        summary = "Write a DESIGN.md that enforces premium, anti-generic UI standards.",
        source = "google-labs-code/stitch-skills 75f4696 - Apache-2.0",
        body = """---
name: taste-design
description: Semantic Design System Skill for Google Stitch. Generates agent-friendly DESIGN.md files that enforce premium, anti-generic UI standards — strict typography, calibrated color, asymmetric layouts, perpetual micro-motion, and hardware-accelerated performance.
allowed-tools:
  - "StitchMCP"
  - "Read"
  - "Write"
---

# Stitch Design Taste — Semantic Design System Skill

## Overview
This skill generates `DESIGN.md` files optimized for Google Stitch screen generation. It translates the battle-tested anti-slop frontend engineering directives into Stitch's native semantic design language — descriptive, natural-language rules paired with precise values that Stitch's AI agent can interpret to produce premium, non-generic interfaces.

The generated `DESIGN.md` serves as the **single source of truth** for prompting Stitch to generate new screens that align with a curated, high-agency design language. Stitch interprets design through **"Visual Descriptions"** supported by specific color values, typography specs, and component behaviors.

## Prerequisites
- Access to Google Stitch via [stitch.withgoogle.com](https://stitch.withgoogle.com)
- Optionally: Stitch MCP Server for programmatic integration with Cursor, Antigravity, or Gemini CLI

## The Goal
Generate a `DESIGN.md` file that encodes:
1. **Visual atmosphere** — the mood, density, and design philosophy
2. **Color calibration** — neutrals, accents, and banned patterns with hex codes
3. **Typographic architecture** — font stacks, scale hierarchy, and anti-patterns
4. **Component behaviors** — buttons, cards, inputs with interaction states
5. **Layout principles** — grid systems, spacing philosophy, responsive strategy
6. **Motion philosophy** — animation engine specs, spring physics, perpetual micro-interactions
7. **Anti-patterns** — explicit list of banned AI design clichés

## Analysis & Synthesis Instructions

### 1. Define the Atmosphere
Evaluate the target project's intent. Use evocative adjectives from the taste spectrum:
- **Density:** "Art Gallery Airy" (1–3) → "Daily App Balanced" (4–7) → "Cockpit Dense" (8–10)
- **Variance:** "Predictable Symmetric" (1–3) → "Offset Asymmetric" (4–7) → "Artsy Chaotic" (8–10)
- **Motion:** "Static Restrained" (1–3) → "Fluid CSS" (4–7) → "Cinematic Choreography" (8–10)

Default baseline: Creativity 9, Variance 8, Motion 6, Density 5. Adapt dynamically based on user's vibe description.

### 2. Map the Color Palette
For each color provide: **Descriptive Name** + **Hex Code** + **Functional Role**.

**Mandatory constraints:**
- Maximum 1 accent color. Saturation below 80%
- The "AI Purple/Blue Neon" aesthetic is strictly BANNED — no purple button glows, no neon gradients
- Use absolute neutral bases (Zinc/Slate) with high-contrast singular accents
- Stick to one palette for the entire output — no warm/cool gray fluctuation
- Never use pure black (`#000000`) — use Off-Black, Zinc-950, or Charcoal

### 3. Establish Typography Rules
- **Display/Headlines:** Track-tight, controlled scale. Not screaming. Hierarchy through weight and color, not just massive size
- **Body:** Relaxed leading, max 65 characters per line
- **Font Selection:** `Inter` is BANNED for premium/creative contexts. Force unique character: `Geist`, `Outfit`, `Cabinet Grotesk`, or `Satoshi`
- **Serif Ban:** Generic serif fonts (`Times New Roman`, `Georgia`, `Garamond`, `Palatino`) are BANNED. If serif is needed for editorial/creative contexts, use only distinctive modern serifs: `Fraunces`, `Gambarino`, `Editorial New`, or `Instrument Serif`. Serif is always BANNED in dashboards or software UIs
- **Dashboard Constraint:** Use Sans-Serif pairings exclusively (`Geist` + `Geist Mono` or `Satoshi` + `JetBrains Mono`)
- **High-Density Override:** When density exceeds 7, all numbers must use Monospace

### 4. Define the Hero Section
The Hero is the first impression and must be creative, striking, and never generic:
- **Inline Image Typography:** Embed small, contextual photos or visuals directly between words or letters in the headline. Images sit inline at type-height, rounded, acting as visual punctuation. This is the signature creative technique
- **No Overlapping:** Text must never overlap images or other text. Every element occupies its own clean spatial zone
- **No Filler Text:** "Scroll to explore", "Swipe down", scroll arrow icons, bouncing chevrons are BANNED. The content should pull users in naturally
- **Asymmetric Structure:** Centered Hero layouts BANNED when variance exceeds 4
- **CTA Restraint:** Maximum one primary CTA. No secondary "Learn more" links

### 5. Describe Component Stylings
For each component type, describe shape, color, shadow depth, and interaction behavior:
- **Buttons:** Tactile push feedback on active state. No neon outer glows. No custom mouse cursors
- **Cards:** Use ONLY when elevation communicates hierarchy. Tint shadows to background hue. For high-density layouts, replace cards with border-top dividers or negative space
- **Inputs/Forms:** Label above input, helper text optional, error text below. Standard gap spacing
- **Loading States:** Skeletal loaders matching layout dimensions — no generic circular spinners
- **Empty States:** Composed compositions indicating how to populate data
- **Error States:** Clear, inline error reporting

### 6. Define Layout Principles
- No overlapping elements — every element occupies its own clear spatial zone. No absolute-positioned content stacking
- Centered Hero sections are BANNED when variance exceeds 4 — force Split Screen, Left-Aligned, or Asymmetric Whitespace
- The generic "3 equal cards horizontally" feature row is BANNED — use 2-column Zig-Zag, asymmetric grid, or horizontal scroll
- CSS Grid over Flexbox math — never use `calc()` percentage hacks
- Contain layouts using max-width constraints (e.g., 1400px centered)
- Full-height sections must use `min-h-[100dvh]` — never `h-screen` (iOS Safari catastrophic jump)

### 7. Define Responsive Rules
Every design must work across all viewports:
- **Mobile-First Collapse (< 768px):** All multi-column layouts collapse to single column. No exceptions
- **No Horizontal Scroll:** Horizontal overflow on mobile is a critical failure
- **Typography Scaling:** Headlines scale via `clamp()`. Body text minimum `1rem`/`14px`
- **Touch Targets:** All interactive elements minimum `44px` tap target
- **Image Behavior:** Inline typography images (photos between words) stack below headline on mobile
- **Navigation:** Desktop horizontal nav collapses to clean mobile menu
- **Spacing:** Vertical section gaps reduce proportionally (`clamp(3rem, 8vw, 6rem)`)

### 8. Encode Motion Philosophy
- **Spring Physics default:** `stiffness: 100, damping: 20` — premium, weighty feel. No linear easing
- **Perpetual Micro-Interactions:** Every active component should have an infinite loop state (Pulse, Typewriter, Float, Shimmer)
- **Staggered Orchestration:** Never mount lists instantly — use cascade delays for waterfall reveals
- **Performance:** Animate exclusively via `transform` and `opacity`. Never animate `top`, `left`, `width`, `height`. Grain/noise filters on fixed pseudo-elements only

### 9. List Anti-Patterns (AI Tells)
Encode these as explicit "NEVER DO" rules in the DESIGN.md:
- No emojis anywhere
- No `Inter` font
- No generic serif fonts (`Times New Roman`, `Georgia`, `Garamond`) — distinctive modern serifs only if needed
- No pure black (`#000000`)
- No neon/outer glow shadows
- No oversaturated accents
- No excessive gradient text on large headers
- No custom mouse cursors
- No overlapping elements — clean spatial separation always
- No 3-column equal card layouts
- No generic names ("John Doe", "Acme", "Nexus")
- No fake round numbers (`99.99%`, `50%`)
- No fabricated data or statistics — never generate metrics, performance numbers, uptime percentages, response times, or any data that the user did not explicitly provide. "99.98% UPTIME SLA", "124ms AVG. RESPONSE", "18.5k DEPLOY CYCLES" are invented AI filler. If real data is not available, use clear placeholder labels like `[metric]` instead of making up numbers
- No fake system/metric sections — "SYSTEM PERFORMANCE METRICS", "KEY STATISTICS", "BY THE NUMBERS" dashboard cards filled with invented data are BANNED
- No `LABEL // YEAR` formatting — "SYSTEM // 2024", "METRICS // 2025" is a lazy AI convention, not real design typography
- No AI copywriting clichés ("Elevate", "Seamless", "Unleash", "Next-Gen")
- No filler UI text: "Scroll to explore", "Swipe down", scroll arrows, bouncing chevrons
- No broken Unsplash links — use `picsum.photos` or SVG avatars
- No centered Hero sections (for high-variance projects)

## Output Format (DESIGN.md Structure)

```markdown
# Design System: [Project Title]

## 1. Visual Theme & Atmosphere
(Evocative description of the mood, density, variance, and motion intensity.
Example: "A restrained, gallery-airy interface with confident asymmetric layouts
and fluid spring-physics motion. The atmosphere is clinical yet warm — like a
well-lit architecture studio.")

## 2. Color Palette & Roles
- **Canvas White** (#F9FAFB) — Primary background surface
- **Pure Surface** (#FFFFFF) — Card and container fill
- **Charcoal Ink** (#18181B) — Primary text, Zinc-950 depth
- **Muted Steel** (#71717A) — Secondary text, descriptions, metadata
- **Whisper Border** (rgba(226,232,240,0.5)) — Card borders, 1px structural lines
- **[Accent Name]** (#XXXXXX) — Single accent for CTAs, active states, focus rings
(Max 1 accent. Saturation < 80%. No purple/neon.)

## 3. Typography Rules
- **Display:** [Font Name] — Track-tight, controlled scale, weight-driven hierarchy
- **Body:** [Font Name] — Relaxed leading, 65ch max-width, neutral secondary color
- **Mono:** [Font Name] — For code, metadata, timestamps, high-density numbers
- **Banned:** Inter, generic system fonts for premium contexts. Serif fonts banned in dashboards.

## 4. Component Stylings
* **Buttons:** Flat, no outer glow. Tactile -1px translate on active. Accent fill for primary, ghost/outline for secondary.
* **Cards:** Generously rounded corners (2.5rem). Diffused whisper shadow. Used only when elevation serves hierarchy. High-density: replace with border-top dividers.
* **Inputs:** Label above, error below. Focus ring in accent color. No floating labels.
* **Loaders:** Skeletal shimmer matching exact layout dimensions. No circular spinners.
* **Empty States:** Composed, illustrated compositions — not just "No data" text.

## 5. Layout Principles
(Grid-first responsive architecture. Asymmetric splits for Hero sections.
Strict single-column collapse below 768px. Max-width containment.
No flexbox percentage math. Generous internal padding.)

## 6. Motion & Interaction
(Spring physics for all interactive elements. Staggered cascade reveals.
Perpetual micro-loops on active dashboard components. Hardware-accelerated
transforms only. Isolated Client Components for CPU-heavy animations.)

## 7. Anti-Patterns (Banned)
(Explicit list of forbidden patterns: no emojis, no Inter, no pure black,
no neon glows, no 3-column equal grids, no AI copywriting clichés,
no generic placeholder names, no broken image links.)
```

## Best Practices
- **Be Descriptive:** "Deep Charcoal Ink (#18181B)" — not just "dark text"
- **Be Functional:** Explain what each element is used for
- **Be Consistent:** Same terminology throughout the document
- **Be Precise:** Include exact hex codes, rem values, pixel values in parentheses
- **Be Opinionated:** This is not a neutral template — it enforces a specific, premium aesthetic

## Tips for Success
1. Start with the atmosphere — understand the vibe before detailing tokens
2. Look for patterns — identify consistent spacing, sizing, and styling
3. Think semantically — name colors by purpose, not just appearance
4. Consider hierarchy — document how visual weight communicates importance
5. Encode the bans — anti-patterns are as important as the rules themselves

## Common Pitfalls to Avoid
- Using technical jargon without translation ("rounded-xl" instead of "generously rounded corners")
- Omitting hex codes or using only descriptive names
- Forgetting functional roles of design elements
- Being too vague in atmosphere descriptions
- Ignoring the anti-pattern list — these are what make the output premium
- Defaulting to generic "safe" designs instead of enforcing the curated aesthetic

---

# Bundled resource

The resource file this skill refers to, included here verbatim so the skill is a single document in NativeCode.

## resources/DESIGN.md

# Design System: Taste Standard
**Skill:** stitch-design-taste

---

## Configuration — Set Your Style
Adjust these dials before using this design system. They control how creative, dense, and animated the output should be. Pick the level that fits your project.

| Dial | Level | Description |
|------|-------|-------------|
| **Creativity** | `9` | `1` = Ultra-minimal, Swiss, silent, monochrome. `5` = Balanced, clean but with personality. `10` = Expressive, editorial, bold typography experiments, inline images in headlines, strong asymmetry. Default: `9` |
| **Density** | `5` | `1` = Gallery-airy, massive whitespace. `5` = Balanced sections. `10` = Cockpit-dense, data-heavy. Default: `5` |
| **Variance** | `8` | `1` = Predictable, symmetric grids. `5` = Subtle offsets. `10` = Artsy chaotic, no two sections alike. Default: `8` |
| **Motion Intent** | `6` | `1` = Static, no animation noted. `5` = Subtle hover/entrance cues. `10` = Cinematic orchestration noted in every component. Default: `6` |

> **How to use:** Change the numbers above to match your project's vibe. At **Creativity 1–3**, the system produces clean, quiet, Notion-like interfaces. At **Creativity 7–10**, expect inline image typography, dramatic scale contrast, and strong editorial layouts. The rest of the rules below adapt to your chosen levels.

---

## 1. Visual Theme & Atmosphere
A restrained, gallery-airy interface with confident asymmetric layouts and fluid spring-physics motion. The atmosphere is clinical yet warm — like a well-lit architecture studio where every element earns its place through function. Density is balanced (Level 4), variance runs high (Level 8) to prevent symmetrical boredom, and motion is fluid but never theatrical (Level 6). The overall impression: expensive, intentional, alive.

## 2. Color Palette & Roles
- **Canvas White** (#F9FAFB) — Primary background surface. Warm-neutral, never clinical blue-white
- **Pure Surface** (#FFFFFF) — Card and container fill. Used with whisper shadow for elevation
- **Charcoal Ink** (#18181B) — Primary text. Zinc-950 depth — never pure black
- **Steel Secondary** (#71717A) — Body text, descriptions, metadata. Zinc-500 warmth
- **Muted Slate** (#94A3B8) — Tertiary text, timestamps, disabled states
- **Whisper Border** (rgba(226,232,240,0.5)) — Card borders, structural 1px lines. Semi-transparent for depth
- **Diffused Shadow** (rgba(0,0,0,0.05)) — Card elevation. Wide-spreading, 40px blur, -15px offset. Never harsh

### Accent Selection (Pick ONE per project)
- **Emerald Signal** (#10B981) — For growth, success, positive data dashboards
- **Electric Blue** (#3B82F6) — For productivity, SaaS, developer tools
- **Deep Rose** (#E11D48) — For creative, editorial, fashion-adjacent projects
- **Amber Warmth** (#F59E0B) — For community, social, warm-toned products

### Banned Colors
- Purple/Violet neon gradients — the "AI Purple" aesthetic
- Pure Black (#000000) — always Off-Black or Zinc-950
- Oversaturated accents above 80% saturation
- Mixed warm/cool gray systems within one project

## 3. Typography Rules
- **Display:** `Geist`, `Satoshi`, `Cabinet Grotesk`, or `Outfit` — Track-tight (`-0.025em`), controlled fluid scale, weight-driven hierarchy (700–900). Not screaming. Leading compressed (`1.1`). Alternatives forced — `Inter` is BANNED for premium contexts
- **Body:** Same family at weight 400 — Relaxed leading (`1.65`), 65ch max-width, Steel Secondary color (#71717A)
- **Mono:** `Geist Mono` or `JetBrains Mono` — For code blocks, metadata, timestamps. When density exceeds Level 7, all numbers switch to monospace
- **Scale:** Display at `clamp(2.25rem, 5vw, 3.75rem)`. Body at `1rem/1.125rem`. Mono metadata at `0.8125rem`

### Banned Fonts
- `Inter` — banned everywhere in premium/creative contexts
- Generic serif fonts (`Times New Roman`, `Georgia`, `Garamond`, `Palatino`) — BANNED. If serif is needed for editorial/creative, use only distinctive modern serifs like `Fraunces`, `Gambarino`, `Editorial New`, or `Instrument Serif`. Never use default browser serif stacks. Serif is always BANNED in dashboards or software UIs regardless

## 4. Component Stylings
* **Buttons:** Flat surface, no outer glow. Primary: accent fill with white text. Secondary: ghost/outline. Active state: `-1px translateY` or `scale(0.98)` for tactile push. Hover: subtle background shift, never glow
* **Cards/Containers:** Generously rounded corners (`2.5rem`). Pure white fill. Whisper border (`1px`, semi-transparent). Diffused shadow (`0 20px 40px -15px rgba(0,0,0,0.05)`). Internal padding `2rem–2.5rem`. Used ONLY when elevation communicates hierarchy — high-density layouts replace cards with `border-top` dividers or negative space
* **Inputs/Forms:** Label positioned above input. Helper text optional. Error text below in Deep Rose. Focus ring in accent color, `2px` offset. No floating labels. Standard `0.5rem` gap between label-input-error stack
* **Navigation:** Sleek, sticky. Icons scale on hover (Dock Magnification optional). No hamburger on desktop. Clean horizontal with generous spacing
* **Loaders:** Skeletal shimmer matching exact layout dimensions and rounded corners. Shifting light reflection across placeholder shapes. Never circular spinners
* **Empty States:** Composed illustration or icon composition with guidance text. Never just "No data found"
* **Error States:** Inline, contextual. Red accent underline or border. Clear recovery action

## 5. Hero Section
The Hero is the first impression — it must be striking, creative, and never generic.
- **Inline Image Typography:** Embed small, contextual photos or visuals directly between words or letters in the headline. Example: "We build [photo of hands typing] digital [photo of screen] products" — images sit inline at type-height, rounded, acting as visual punctuation between words. This is the signature creative technique
- **No Overlapping Elements:** Text must never overlap images or other text. Every element has its own clear spatial zone. No z-index stacking of content layers, no absolute-positioned headlines over images. Clean separation always
- **No Filler Text:** "Scroll to explore", "Swipe down", scroll arrow icons, bouncing chevrons, and any instructional UI chrome are BANNED. The user knows how to scroll. Let the content pull them in naturally
- **Asymmetric Structure:** Centered Hero layouts are BANNED at this variance level. Use Split Screen (50/50), Left-Aligned text / Right visual, or Asymmetric Whitespace with large empty zones
- **CTA Restraint:** Maximum one primary CTA button. No secondary "Learn more" links. No redundant micro-copy below the headline

## 6. Layout Principles
- **Grid-First:** CSS Grid for all structural layouts. Never flexbox percentage math (`calc(33% - 1rem)` is BANNED)
- **No Overlapping:** Elements must never overlap each other. No absolute-positioned layers stacking content on content. Every element occupies its own grid cell or flow position. Clean, separated spatial zones
- **Feature Sections:** The "3 equal cards in a row" pattern is BANNED. Use 2-column Zig-Zag, asymmetric Bento grids (2fr 1fr 1fr), or horizontal scroll galleries
- **Containment:** All content within `max-width: 1400px`, centered. Generous horizontal padding (`1rem` mobile, `2rem` tablet, `4rem` desktop)
- **Full-Height:** Use `min-height: 100dvh` — never `height: 100vh` (iOS Safari address bar jump)
- **Bento Architecture:** For feature grids, use Row 1: 3 columns | Row 2: 2 columns (70/30 split). Each tile contains a perpetual micro-animation

## 7. Responsive Rules
Every screen must work flawlessly across all viewports. **Responsive is not optional — it is a hard requirement. Every single element must be tested at 375px, 768px, and 1440px.**
- **Mobile-First Collapse (< 768px):** All multi-column layouts collapse to a strict single column. `width: 100%`, `padding: 1rem`, `gap: 1.5rem`. No exceptions
- **No Horizontal Scroll:** Horizontal overflow on mobile is a critical failure. All elements must fit within viewport width. If any element causes horizontal scroll, the design is broken
- **Typography Scaling:** Headlines scale down gracefully via `clamp()`. Body text stays `1rem` minimum. Never shrink body below `14px`. Headlines must remain readable on 375px screens
- **Touch Targets:** All interactive elements minimum `44px` tap target. Generous spacing between clickable items. Buttons must be full-width on mobile
- **Image Behavior:** Hero and inline images scale proportionally. Inline typography images (photos between words) stack below the headline on mobile instead of inline
- **Navigation:** Desktop horizontal nav collapses to a clean mobile menu (slide-in or full-screen overlay). No tiny hamburger icons without labels
- **Cards & Grids:** Bento grids and asymmetric layouts revert to stacked single-column cards with full-width. Maintain internal padding (`1rem`)
- **Spacing Consistency:** Vertical section gaps reduce proportionally on mobile (`clamp(3rem, 8vw, 6rem)`). Never cramped, never excessively airy
- **Testing Viewports:** Designs must be verified at: `375px` (iPhone SE), `390px` (iPhone 14), `768px` (iPad), `1024px` (small laptop), `1440px` (desktop)

## 8. Motion & Interaction (Code-Phase Intent)
> **Note:** Stitch generates static screens — it does not animate. This section documents the **intended motion behavior** so that the coding agent (Antigravity, Cursor, etc.) knows exactly how to implement animations when building the exported design into a live product.

- **Physics Engine:** Spring-based exclusively. `stiffness: 100, damping: 20`. No linear easing anywhere. Premium, weighty feel on all interactive elements
- **Perpetual Micro-Loops:** Every active dashboard component has an infinite-loop state — Pulse on status dots, Typewriter on search bars, Float on feature icons, Shimmer on loading states
- **Staggered Orchestration:** Lists and grids mount with cascaded delays (`animation-delay: calc(var(--index) * 100ms)`). Waterfall reveals, never instant mount
- **Layout Transitions:** Smooth re-ordering via shared element IDs. Items swap positions with physics, simulating real-time intelligence
- **Hardware Rules:** Animate ONLY `transform` and `opacity`. Never `top`, `left`, `width`, `height`. Grain/noise filters on fixed, pointer-events-none pseudo-elements only
- **Performance:** CPU-heavy perpetual animations isolated in microscopic leaf components. Never trigger parent re-renders. Target 60fps minimum

## 9. Anti-Patterns (Banned)
- No emojis — anywhere in UI, code, or alt text
- No `Inter` font — use `Geist`, `Outfit`, `Cabinet Grotesk`, `Satoshi`
- No generic serif fonts (`Times New Roman`, `Georgia`, `Garamond`) — if serif is needed, use distinctive modern serifs only (`Fraunces`, `Instrument Serif`)
- No pure black (`#000000`) — Off-Black or Zinc-950 only
- No neon outer glows or default box-shadow glows
- No oversaturated accent colors above 80%
- No excessive gradient text on large headers
- No custom mouse cursors
- No overlapping elements — text never overlaps images or other content. Clean spatial separation always
- No 3-column equal card layouts for features
- No centered Hero sections (at this variance level)
- No filler UI text: "Scroll to explore", "Swipe down", "Discover more below", scroll arrows, bouncing chevrons — all BANNED
- No generic names: "John Doe", "Sarah Chan", "Acme", "Nexus", "SmartFlow"
- No fake round numbers: `99.99%`, `50%`, `1234567` — use organic data: `47.2%`, `+1 (312) 847-1928`
- No fabricated data or statistics — never generate metrics, performance numbers, uptime percentages, response times, or any data not explicitly provided by the user. "99.98% UPTIME SLA", "124ms AVG. RESPONSE", "18.5k DEPLOY CYCLES" are invented AI filler. Use `[metric]` placeholders if real data is unavailable
- No fake system/metric sections — "SYSTEM PERFORMANCE METRICS", "KEY STATISTICS", "BY THE NUMBERS" dashboard cards filled with invented data are BANNED
- No `LABEL // YEAR` formatting — "SYSTEM // 2024", "METRICS // 2025" is a lazy AI convention, not real design typography
- No AI copywriting clichés: "Elevate", "Seamless", "Unleash", "Next-Gen", "Revolutionize"
- No broken Unsplash links — use `picsum.photos/seed/{id}/800/600` or SVG UI Avatars
- No generic `shadcn/ui` defaults — customize radii, colors, shadows to match this system
- No `z-index` spam — use only for Navbar, Modal, Overlay layer contexts
- No `h-screen` — always `min-h-[100dvh]`
- No circular loading spinners — skeletal shimmer only
""",
    )

    private fun designMobileComposeUi() = Skill(
        id = "design-mobile-compose-ui",
        title = "Design Mobile UI",
        summary = "Phone-first Android and Jetpack Compose screens: layout, type, color, motion, accessibility.",
        source = "",
        body = """---
name: design-mobile-compose-ui
description: Design and review phone-first Android and Jetpack Compose screens: layout, touch targets, type, color, motion, insets and accessibility. Use when building or judging a mobile UI so it does not look like a default template.
compatibility: NativeCode ARM64 PRoot; Kotlin/Compose project sources and Gradle builds as supplied by the project
metadata:
  title: Design Mobile UI
  category: PROJECT
  group: Design
  status: authored; not independently device-tested
---

# Design Mobile UI (Jetpack Compose)

**Use for:** a new Android screen, a redesign, or a UI review of a Compose (or View) app.

**Deliver:** working Compose code that follows the project's existing theme, plus a short list of the design decisions you made.

## Before you design

1. Read the project's theme first (`Theme.kt`, `Color.kt`, `Type.kt`, any `DesignTokens`). Reuse its colors, shapes and spacing. Never add a second palette next to it.
2. Name the screen's one job in a sentence. Everything on the screen either serves that job or is removed.
3. Pick one primary action. Give it the strongest visual weight; every other action is secondary or tertiary.

## Layout and spacing

- Use a 4 dp base grid; common steps 4, 8, 12, 16, 24, 32. Screen edge padding 16 dp on phones. Put the steps in named constants, not inline numbers.
- Touch targets are at least 48 x 48 dp, with 8 dp between neighbours. A 24 dp icon sits in a 48 dp hit area (`IconButton`, or `Modifier.minimumInteractiveComponentSize()`).
- Group related items with whitespace first, dividers last. Do not wrap every group in a card.
- Thumb zone: primary actions and navigation live in the lower half. Destructive actions are never next to the primary action without a gap or confirmation.
- Edge to edge: call `enableEdgeToEdge()`, then apply `WindowInsets.systemBars`, `ime` and `displayCutout` through `Scaffold` padding or `Modifier.windowInsetsPadding`. A list scrolls under the bars with content padding, it is not clipped by them.
- Scroll with `LazyColumn` and stable `key`s. Never nest a vertical scroller in another vertical scroller.

## Adaptive

- Design for 360 dp wide first, then verify 320 dp (small), 600 dp (foldable and tablet) and landscape.
- Use `currentWindowAdaptiveInfo().windowSizeClass`: Compact = one pane and bottom bar, Medium = navigation rail, Expanded = list-detail with two panes.
- Text must survive 200 percent font scale: use `sp`, `Modifier.heightIn(min = ...)` instead of fixed heights, `maxLines` plus `overflow = Ellipsis` only where truncation is acceptable.

## Typography

- Take sizes from `MaterialTheme.typography` roles (display, headline, title, body, label); do not invent font sizes per screen.
- Build hierarchy with size, weight and color together, not size alone. Body is 14 to 16 sp with line height about 1.4 to 1.5. Lines longer than about 70 characters are hard to read: cap content width on wide screens.
- One typeface family per product, two at most. Use tabular numbers (`fontFeatureSettings = "tnum"`) for values that change in place.

## Color

- Use color roles (`primary`, `onPrimary`, `surfaceContainer`, `outline`, `error`), never raw hex in composables.
- Neutral surfaces carry most of the screen; one accent marks the primary action and the selected state. State is never conveyed by color alone: add an icon, a label or a shape change.
- Contrast: text 4.5:1, large text and icons 3:1. Check dark and light themes both. In dark theme use lighter surfaces for elevation, not shadows.
- Avoid the default-AI look: purple to blue gradients, glowing buttons, glass cards on every item, emoji as icons, identical rounded cards in a grid.

## Components and states

- Every interactive element has all states: enabled, pressed, focused, disabled, error and loading. Show a skeleton or progress while loading, an explanation plus action when empty, and a retry when failed.
- Icons come from one vector set (Material Symbols or the project's drawables), one size and one stroke weight. Never use emoji or font glyphs as icons.
- Dialogs for decisions only. Prefer a snackbar with Undo over a confirmation dialog for reversible actions.
- Bottom navigation holds three to five top-level destinations. Back always works and leaves state intact.

## Motion

- Motion explains change: enter, exit, reorder, state. 150 to 300 ms, standard easing (`FastOutSlowInEasing`), no bounce on utility screens.
- Use `AnimatedVisibility`, `animateContentSize`, `animate*AsState` and shared-element transitions where the item stays recognisable. Respect the system "remove animations" setting.

## Accessibility

- `contentDescription` on every informative icon; `null` on decorative ones. Use `Modifier.semantics { ... }` for custom controls, `clearAndSetSemantics` to merge a row into one announcement.
- Logical focus order, visible focus indicator for keyboard and switch access, `Role` for custom clickables (`Modifier.clickable(onClickLabel = ...)`).
- Test with TalkBack once, with 200 percent font scale once, and in grayscale once.

## Verify

- Add `@Preview` for light, dark, small width and large font scale.
- Build with `./gradlew :app:assembleDebug`, install, and look at the real screen at its shipped size before calling it done.
- Finish with a checklist reply: primary action, touch targets, insets, contrast, states, font scale, dark theme, TalkBack.
""",
    )

    private fun designSystemTokens() = Skill(
        id = "design-system-tokens",
        title = "Design System Tokens",
        summary = "Color, type and spacing tokens for web and Android, with a DESIGN.md.",
        source = "",
        body = """---
name: design-system-tokens
description: Create or tidy a design system built on tokens: color, type, spacing, radius, elevation and motion defined once and used everywhere, for web (CSS variables) and Android (Compose theme), with a DESIGN.md that agents and people follow.
compatibility: NativeCode ARM64 PRoot; plain files, no extra tools required
metadata:
  title: Design System Tokens
  category: PROJECT
  group: Design
  status: authored; not independently device-tested
---

# Design System Tokens

**Use for:** starting a design system, replacing scattered hex values and magic numbers, adding dark mode or a second theme, or documenting the look so every later change stays consistent.

**Deliver:** a token file, the platform theme that consumes it, and a short `DESIGN.md`.

## 1. Inspect before inventing

- Search the project for existing colors, font sizes, spacing and radii (`grep -rE "#[0-9a-fA-F]{3,8}|[0-9]+px|[0-9]+\.dp"`). The values used most often are the real system; promote them to tokens instead of picking new ones.
- Keep the brand the project already has. Change values only when asked.

## 2. Three layers

1. **Primitive**: raw values with no meaning. `gray-900: #14161a`, `space-4: 16px`.
2. **Semantic**: purpose. `color.surface`, `color.text`, `color.text-muted`, `color.accent`, `color.danger`, `space.page`, `radius.control`. Components use only this layer.
3. **Component** (only when needed): `button.padding-x`, `card.radius`, pointing at semantic tokens.

Themes (light, dark, high contrast) swap the primitive mapping behind the same semantic names. Components never know which theme is active.

## 3. What to define

- **Color**: surface (3 steps), border, text (default, muted, disabled, on-accent), accent (default, hover, pressed), and status (success, warning, danger, info). One accent. Check contrast: text 4.5:1, large text and UI parts 3:1, in every theme.
- **Type**: a scale of 6 to 8 steps (for example 12, 14, 16, 20, 24, 32, 48) with size, line height and weight per role (caption, body, label, title, headline, display). One or two families.
- **Space**: a 4 px base scale (4, 8, 12, 16, 24, 32, 48, 64). No other values in layout code.
- **Radius** (2 to 4 steps), **border width**, **elevation** (shadow or surface tint, 3 levels), **motion** (durations 120, 200, 320 ms; one or two easings), **z-index** layers, **breakpoints**.

## 4. Web output (CSS variables)

```css
:root {
  --gray-900: #14161a; --gray-50: #f6f7f9; --blue-600: #2458d6;
  --color-surface: var(--gray-50);
  --color-text: var(--gray-900);
  --color-accent: var(--blue-600);
  --space-2: 8px; --space-4: 16px; --radius-control: 8px;
  --font-body: system-ui, sans-serif; --text-body: 1rem/1.5 var(--font-body);
}
@media (prefers-color-scheme: dark) {
  :root { --color-surface: #14161a; --color-text: #f6f7f9; }
}
:root[data-theme="dark"] { --color-surface: #14161a; --color-text: #f6f7f9; }
```

Rules: components reference `var(--color-*)` and `var(--space-*)` only. A raw color or pixel value in a component is a bug. Use `rem` for type, `clamp()` for fluid headings, and `color-scheme: light dark`.

## 5. Android output (Compose)

- Put colors in `Color.kt` as primitives, map them into `lightColorScheme(...)` and `darkColorScheme(...)` roles in `Theme.kt`.
- Spacing and radius as an immutable object (`object Space { val s = 8.dp ... }`) or a `CompositionLocal` when themes change them.
- Type in `Typography(...)`. Screens read `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*`, never hex or ad hoc `sp`.

## 6. Write DESIGN.md

Keep it under two pages: purpose and tone in two sentences; the token table (name, value, use); do and do not rules (one accent, no gradients on surfaces, spacing only from the scale); component states; accessibility floor; where the tokens live in code. Agents read this file first on every UI task.

## 7. Migrate safely

- Replace values in small steps, one screen or component at a time, and build after each step.
- Add a lint-style check: a script or test that fails on raw hex colors and non-scale spacing in component files.
- Screenshot before and after at phone width, in light and dark. The look should not change unless the user asked for a change.
""",
    )

    private fun makeVanillaAnimation() = Skill(
        id = "make-vanilla-animation",
        title = "Make Vanilla Animation",
        summary = "Animated shorts in plain HTML, CSS and JS, exported to MP4 with headless Chrome and FFmpeg.",
        source = "",
        body = """---
name: make-vanilla-animation
description: Build animated shorts, title cards and explainers with plain HTML, CSS and JavaScript (canvas 2D, WebGL, CSS or Web Animations) and no animation library, then export a deterministic MP4 by capturing frames with headless Chrome and encoding with FFmpeg.
compatibility: NativeCode ARM64 PRoot; Node, the supplied Chrome wrapper and FFmpeg as installed in the guest
metadata:
  title: Make Vanilla Animation
  category: PROJECT
  group: Video and audio
  engine: plain HTML/CSS/JS + headless Chrome + FFmpeg
  status: authored; not independently device-tested
---

# Make Vanilla Animation

**Use for:** a short animated clip, loop, title sequence, chart or explainer when the user wants no framework or animation library (no GSAP, no Remotion). For the HyperFrames workflow use the make-motion-video skill instead.

**Deliver:** one editable `index.html` that plays live in a browser, a captured PNG sequence, and `out.mp4`.

## Rule 1: everything is a function of time

Write one `render(t)` (seconds) that draws the whole frame from `t` alone. No `Date.now()`, no `setInterval`, no unseeded `Math.random()`, no state that depends on earlier frames. The live preview calls `render(performance.now()/1000)`; the exporter calls `render(frame / fps)`. This makes the video identical on every run and lets you scrub.

## Template

```html
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  html,body{margin:0;background:#101114}
  canvas{display:block;width:100vw;height:100vh;object-fit:contain}
</style>
<canvas id="c" width="1280" height="720"></canvas>
<script>
const W = 1280, H = 720, DURATION = 5, FPS = 30;
const ctx = document.getElementById('c').getContext('2d');
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const ease = x => 1 - Math.pow(1 - clamp(x), 3);          // easeOutCubic
const seg = (t, from, to) => clamp((t - from) / (to - from)); // 0..1 inside [from,to]
function rng(seed){ return () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296; }

function render(t) {
  ctx.fillStyle = '#101114'; ctx.fillRect(0, 0, W, H);
  const a = ease(seg(t, 0.2, 1.2));
  ctx.globalAlpha = a;
  ctx.fillStyle = '#f2f2f0';
  ctx.font = '700 96px system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('Hello motion', W / 2, H / 2 + 32 * (1 - a));
  ctx.globalAlpha = 1;
}
window.render = render; window.DURATION = DURATION; window.FPS = FPS;
if (!new URLSearchParams(location.search).has('export')) {
  const t0 = performance.now();
  (function loop(){ render(((performance.now() - t0) / 1000) % DURATION); requestAnimationFrame(loop); })();
} else render(0);
</script>
```

## Building blocks

- **Timeline**: define scenes as `[start, end]` ranges and map time with `seg()`; stagger items with `seg(t, i * 0.08, i * 0.08 + 0.5)`.
- **Easing**: write the few curves you need (`ease`, easeInOutCubic, a spring `1 - Math.exp(-6 * x) * Math.cos(12 * x)`), keep them in one place.
- **Canvas 2D**: shapes, text, gradients, `ctx.save/restore`, `ctx.filter` for blur. Prefer vector drawing over images. Wait for fonts with `document.fonts.ready` before the first export frame.
- **CSS and WAAPI** alternative: create animations paused and seek them: `document.getAnimations().forEach(a => { a.pause(); a.currentTime = t * 1000; })`. Works for HTML layouts, text and SVG.
- **WebGL**: one fragment shader with a `u_time` uniform set from `t`; render into a canvas created with `preserveDrawingBuffer: true` so screenshots capture it.
- **Audio**: synthesize or supply a file; mux it in the FFmpeg step. Never rely on live `AudioContext` timing for export.
- **Design**: apply the frontend-design skill rules: one idea per scene, strong hierarchy, 5 to 7 second scenes at most, readable text (at least 48 px at 1280 x 720), safe margins of 5 percent.

## Export (headless Chrome + FFmpeg)

Work as the `flux` user. Reuse the supplied browser wrapper `/home/flux/.local/bin/google-chrome` and the installed `ffmpeg`; do not reinstall them.

```sh
cd <project> && npm init -y >/dev/null && npm i puppeteer-core
```

`export.mjs`:

```js
import puppeteer from 'puppeteer-core';
import { mkdirSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const browser = await puppeteer.launch({
  executablePath: '/home/flux/.local/bin/google-chrome',
  args: ['--no-sandbox', '--force-device-scale-factor=1'],
});
const page = await browser.newPage();
await page.setViewport({ width: 1280, height: 720 });
await page.goto(pathToFileURL(process.cwd() + '/index.html').href + '?export');
await page.evaluate(() => document.fonts.ready);
const { d, fps } = await page.evaluate(() => ({ d: DURATION, fps: FPS }));
mkdirSync('frames', { recursive: true });
for (let i = 0; i < d * fps; i++) {
  await page.evaluate(t => render(t), i / fps);
  await page.screenshot({ path: `frames/${'$'}{String(i).padStart(5, '0')}.png`, clip: { x: 0, y: 0, width: 1280, height: 720 } });
}
await browser.close();
```

```sh
node export.mjs
ffmpeg -y -framerate 30 -i frames/%05d.png -c:v libx264 -pix_fmt yuv420p -crf 18 -movflags +faststart out.mp4
ffprobe -v error -show_entries format=duration -of csv=p=0 out.mp4
```

Add `-i audio.wav -shortest -c:a aac` to include sound. For a GIF use `-vf "fps=15,scale=640:-1:flags=lanczos"`.

## Verify

1. Open `index.html` in the in-app preview or browser and scrub a few times: nothing flickers, nothing depends on wall-clock time.
2. Look at frames 0, the middle and the last one (`frames/00000.png` and so on). Text is readable, nothing is cut off, the end state is intentional.
3. Check the MP4 duration equals `DURATION` and that it plays.
4. Cost: a 5 s, 30 fps, 1280 x 720 clip is 150 screenshots. Lower `FPS` to 24 or the size to 960 x 540 on slow devices; do not skip frames in the middle.
""",
    )

}
