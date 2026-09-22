---
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
