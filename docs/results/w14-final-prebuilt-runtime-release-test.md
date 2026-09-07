# NativeCode — Final Prebuilt Runtime Release Test Report

## Release Baseline & Artifacts
- **COMMIT:** Working branch `feature/prebuilt-alpine-rootfs-w1-w6`
- **ROOTFS_SHA256:** `02be3de800ab006b318f7225490a0fe8239a33c84b1a3b1445b9273368b5dd8a`
- **ROOTFS_SIZE:** 1,477,464,213 bytes (1.38 GiB)
- **DEVICE:** Xiaomi Poco X6 Pro (`2311DRK48I`), MediaTek Dimensity 8300 Ultra (`MT6897`)
- **ANDROID_VERSION:** Android 16 (API 36, targetSdk 37 W^X)

---

## Onboarding & Execution Verification

### 1. FRESH_INSTALL: PASS
- `alpaquita_rootfs.tar.zst` extracted directly from source without double-buffering or heap OOM.
- Container registered and installed via `proot-distro install`.
- Android 16 W^X compliant host execution (Toybox + `/system/bin/linker64` wrapping Termux binaries).

### 2. ZERO_POSTINSTALL_GUEST_SETUP: PASS
- Post-install phase in `OnboardingActivity.kt` no longer invokes any guest configuration/setup/repair scripts:
  - `setup_alpaquita_family.sh` (E): NOT EXECUTED
  - `setup_hw_accel_debian.sh` (F): NOT EXECUTED
  - `setup_xfce4.sh` (GUI): NOT EXECUTED
  - `setup_customization_alpine.sh` (G): NOT EXECUTED
  - `setup_cli_tools.sh` (H): NOT EXECUTED
- Sentinel runtime checks verified all guest components are already baked into the delivered image.

### 3. REDO_ONBOARDING_EXISTING & BUSY_SESSION: PASS
- Tested onboarding rerun with an active PRoot guest session (`sleep 600`) running in the background.
- `inspectExistingAlpaquita()` accurately detected the intact environment before PAD download or install.
- Did not call `proot-distro install` -> 0 "container busy" errors.
- Completed immediately in 4 seconds with 0 guest script execution.
- Existing live sessions, project directories, and auth tokens remained untouched.

---

## Desktop & Graphical Environment

### 4. XFCE_CONFIG_SOURCE: PASS
- Captured known-good configuration from historical desktop baseline (`chrootAlpine`).
- Staged into `tools/rootfs/overlay/alpaquita/home/flux/.config/xfce4/` and baked deterministically into the rootfs image:
  - `helpers.rc`
  - `terminal/terminalrc`
  - `xfconf/xfce-perchannel-xml/xsettings.xml`
  - `xfconf/xfce-perchannel-xml/xfwm4.xml`
  - `xfconf/xfce-perchannel-xml/xfce4-desktop.xml`
  - `xfconf/xfce-perchannel-xml/xfce4-panel.xml`
  - `xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml`

### 5. XFCE_THEME: PASS
- Active GTK theme: `Space-transparency`
- Active XFWM theme: `Space-transparency`
- Active icon theme: `Papirus-Dark`
- Active cursor theme: `Vimix-white-cursors`
- Compositing: disabled (`use_compositing = false`)
- Window scaling: `2`

### 6. XFCE_PANEL: PASS
- Panel layout: Single bottom panel (length 100%, height 32px)
- Plugins: `applicationsmenu`, `tasklist`, `separator` (expanding), `systray`, `clock`
- Verified: XFCE 4.20 retains panel without overriding to default panel pairs.

### 7. XFCE_WALLPAPER: PASS
- Configured path: `/home/flux/Pictures/Wallpapers/fluxlinux-dark.png`
- Verified active on `screen0/monitor0/workspace0` and `screen0/monitorVirtual-1/workspace0`.

### 8. XFCE_TERMINAL: PASS
- Root cause diagnosed: `vte3` dependency missing in runtime + `helpers.rc` missing preferred helper key.
- Fixed at build time: `vte3` package included in Alpaquita base packages; `helpers.rc` baked with:
  ```ini
  WebBrowser=firefox
  MailReader=thunderbird
  TerminalEmulator=xfce4-terminal
  TerminalEmulatorDismissed=true
  FileManager=thunar
  ```
- Verified on device: `exo-open --launch TerminalEmulator` spawned `/usr/bin/xfce4-terminal` with NO error dialog.

---

## AI CLI Suite & App Features

### 9. AI_CLI_PAGE_NON_MUTATING: PASS
- Mutating actions completely removed from AI CLI Tools page in `MainActivity.kt`:
  - Removed "INSTALL AI TOOLS"
  - Removed "RE-INSTALL / REPAIR AI CLI TOOLS"
  - Removed "CLEAR ALL AI CREDENTIALS"
  - Removed "CLEAR SUITE FLAG"
  - Removed "RE-AUTH" / "LOGOUT"
- Page now displays non-mutating bundled overview:
  - Explains tools are pre-baked in runtime image and managed directly by the user via terminal.
  - "OPEN TERMINAL" launcher
  - Bundled status for all 7 tools: OpenCode (`opencode --auto`), Codex (`codex`), Qwen Code (`qwen`), Claude Code (`claude`), Antigravity (`agy`), Grok (`grok`), Kiro (`kiro-cli`).
  - Direct individual "OPEN" buttons to spawn corresponding terminal sessions.
  - Non-mutating link to "AI SAFETY & REPORT".

### 10. OPENCODE_AUTO: PASS
- Tested `/tmp/launch_tool.sh opencode`: dispatches `opencode --auto "$@"` correctly.
- Real version output on Poco X6 Pro: `1.18.27`.
- All other 6 AI CLIs execute with version numbers:
  - `codex-cli 0.153.2`
  - `qwen 0.23.0`
  - `claude 2.1.263`
  - `agy 1.1.27`
  - `grok 1.0.13`
  - `kiro-cli 2.21.1`

### 11. PROJECTS, GIT, GITHUB: PASS
- Project creation, workspace root, file exploration: PASS.
- Git porcelain status & Git diff: PASS.
- GitHub CLI (`/usr/local/bin/gh`): PASS.

### 12. PROOT_OWNERSHIP: PASS
- Host rootfs files remain Android app UID owned (`u0_a322:u0_a322`).
- No recursive `chown` executed on host.
- Inside PRoot: `/home/flux/.config/xfce4` and subfiles are owned by `1000:1000` with `644` / `755` permissions.

### 13. CHROOT_SMOKE: PASS
- Verified existing rooted chroot installation:
  - Shell launches as root (`uid=0(root)`).
  - `/usr/bin/xfce4-terminal` present and executable.
  - `/home/flux/.local/bin/opencode` present and executable.
  - `/home/flux` ownership is `1000:1000 755`.

### 14. OFFLINE_RESTART: PASS
- GUI stop, start, and idempotency work without network access.
- D-Bus session and X11 sockets clean up cleanly upon stop.

---

## Test Suite Summary

| Test Suite | Command | Result |
| :--- | :--- | :--- |
| Android Gradle Assemble | `./gradlew assembleDebug` | **PASS** |
| Android Unit Tests | `./gradlew test` | **PASS** |
| Rootfs Build Pipeline | `bash tools/rootfs/test_build_pipeline.sh` | **PASS** |
| Rootfs Prebuilt Paths | `bash tools/rootfs/test_prebuilt_paths.sh` | **PASS (14/14)** |
| Runtime Compatibility Contract | `bash tools/app/test_alpaquita_app_runtime_contract.sh` | **PASS** |
| Immutable Runtime Contract | `bash tools/app/test_prebuilt_runtime_immutable_contract.sh` | **PASS** |
| Full On-Device Regression | `bash tools/app/w13_full_app_smoke.sh --all` | **PASS** |

---

## RELEASE_RECOMMENDATION
**APPROVE FOR RELEASE**.
All 5 release blockers have been resolved, verified against contract specifications, and proven on physical Android 16 hardware (Xiaomi Poco X6 Pro). The prebuilt immutable runtime architecture is fully operational with zero post-install guest script dependencies.
