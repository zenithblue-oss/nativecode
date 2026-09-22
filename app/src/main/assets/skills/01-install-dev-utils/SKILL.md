---
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
  command -v "\$tool" || printf '%s: missing\n' "\$tool"
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
  apk search -x "\$package"
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
smoke_dir=$(mktemp -d /tmp/install-dev-utils-smoke.XXXXXX)
touch "\$smoke_dir/payload.txt"
zip -q "\$smoke_dir/archive.zip" "\$smoke_dir/payload.txt"
unzip -tq "\$smoke_dir/archive.zip"
tar -cf "\$smoke_dir/archive.tar" -C "\$smoke_dir" payload.txt
tar -tf "\$smoke_dir/archive.tar" | grep -qx 'payload.txt'
sha256sum "\$smoke_dir/archive.zip" > "\$smoke_dir/archive.zip.sha256"
sha256sum -c "\$smoke_dir/archive.zip.sha256"
git -C "\$smoke_dir" init -q
git -C "\$smoke_dir" status --short
printf 'int main(void){return 0;}' | gcc -x c -o "\$smoke_dir/hello" -
"\$smoke_dir/hello"
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
