---
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
file "$(command -v adb 2>/dev/null)" 2>/dev/null || true
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
exec /opt/android-sdk/platform-tools/adb "$@"
EOF
chmod 755 /home/flux/.local/bin/adb
```

For an existing system-wide installation, a symlink is also acceptable when its target is verified ARM64. Keep `fastboot` and other bundled tools out of PATH unless they were separately requested.

## Bash and Zsh PATH

Ensure both interactive shells contain the same idempotent block. Do not add duplicate exports or replace the user's unrelated PATH entries:

```sh
# NativeCode user-installed executables
case ":${PATH}:" in
  *":/home/flux/.local/bin:"*) ;;
  *) export PATH="/home/flux/.local/bin:${PATH}" ;;
esac
```

Place it in `/home/flux/.bashrc` and `/home/flux/.zshrc` as applicable, then verify new shells rather than trusting the current shell:

```sh
bash -lc 'command -v adb; adb version'
zsh -lc 'command -v adb; adb version'   # when zsh is installed
```

The resolved command must be `/home/flux/.local/bin/adb` (or the deliberately selected verified path), and `file "$(command -v adb)"` must identify ARM64.

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
