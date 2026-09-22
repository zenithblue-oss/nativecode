---
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
  command -v "\$tool" || printf '%s: missing\n' "\$tool"
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
node_archive="node-\${node_version}-linux-arm64.tar.xz"
node_parent=/home/flux/.local/share/nativecode/tools/node
node_stage_dir=/tmp/install-node-tools
node_stage="\$node_stage_dir/\$node_archive"

mkdir -p "\$node_parent" "\$node_stage_dir"
curl -fL "https://nodejs.org/dist/\${node_version}/\${node_archive}" -o "\$node_stage"
curl -fLsS "https://nodejs.org/dist/\${node_version}/SHASUMS256.txt" |
  grep "  \${node_archive}\$" > "\$node_stage_dir/\${node_archive}.sha256"
(
  cd "\$node_stage_dir"
  sha256sum -c "\${node_archive}.sha256"
)
tar -xJf "\$node_stage" -C "\$node_parent"
node_home="\$node_parent/node-\${node_version}-linux-arm64"
test -x "\$node_home/bin/node"
test -x "\$node_home/bin/npm"
\`\`\`

For a future latest release, replace \`node_version\` only after checking the current official index and checksum. Record the exact version and SHA-256 value. For the 2026-09-22 artifact, the verified SHA-256 was:

\`\`\`text
7a6353f63eb3d04765004b4adf172616243e4522434635cb1d26288658b04ab5
\`\`\`

## Update npm to its latest compatible version

Node bundles npm, but npm releases independently. Query npm's registry tag and engine requirement using the extracted binary:

\`\`\`sh
"\$node_home/bin/npm" view npm version
"\$node_home/bin/npm" view npm engines --json
\`\`\`

Install the current \`latest\` tag only when its engine range includes the selected Node version:

\`\`\`sh
"\$node_home/bin/npm" install --global --prefix "\$node_home" npm@latest
"\$node_home/bin/node" --version
"\$node_home/bin/npm" --version
"\$node_home/bin/npx" --version
\`\`\`

Use the project lockfile for project dependencies. Avoid global package installation except for the Node/npm toolchain or a CLI explicitly required by the user's selected workflow.

## Configure PATH for Bash and Zsh

Keep a stable symlink so future upgrades do not require changing shell configuration:

\`\`\`sh
ln -sfn "\$node_home" "\$node_parent/current"
\`\`\`

Add this idempotent block to \`/home/flux/.bashrc\` and \`/home/flux/.zshrc\` without deleting unrelated user configuration:

\`\`\`sh
# NativeCode Node.js toolchain
export NATIVE_NODE_HOME="/home/flux/.local/share/nativecode/tools/node/current"
case ":\${PATH}:" in
  *":\${NATIVE_NODE_HOME}/bin:"*) ;;
  *) export PATH="\${NATIVE_NODE_HOME}/bin:\${PATH}" ;;
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
smoke_dir=$(mktemp -d /tmp/install-node-tools-smoke.XXXXXX)
npm init -y --prefix "\$smoke_dir" >/dev/null
npm install --prefix "\$smoke_dir" --ignore-scripts --no-audit --no-fund is-number@7.0.0 >/dev/null
node -e "const isNumber=require('\$smoke_dir/node_modules/is-number'); if (!isNumber(7)) process.exit(1); console.log('npm package smoke passed')"
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
