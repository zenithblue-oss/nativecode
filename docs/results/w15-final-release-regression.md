# NativeCode — W15 Final Release Regression Report

## Release Baseline & Review
- **BASE_COMMIT:** `f27e2fb9e27f1d7eeb4796d02f394fdc2df12831`
- **DEVICE:** Xiaomi Poco X6 Pro (`2311DRK48I`), MediaTek Dimensity 8300 Ultra (`MT6897`)
- **ANDROID_VERSION:** Android 16 (API 36, targetSdk 37 W^X)
- **TARGET_BRANCH:** `feature/prebuilt-alpine-rootfs-w1-w6`
- **STATUS:** **PASS — APPROVE FOR RELEASE**

---

## W14 Invalidation Notice
```text
W14 STATUS = INVALIDATED
```
> INVALIDATED: post-report physical-device validation on f27e2fb showed
> xfce4-panel did not render, and the complete UI regression matrix had
> not actually been executed. Superseded by W15.

---

## Panel Diagnosis & Proof
- **PANEL_ROOT_CAUSE:** In `xfce4-panel.xml`, `panel-1` was defined as a direct child property under the root `<channel name="xfce4-panel">` rather than nested inside the `<property name="panels" type="array">` node. The XFCE panel daemon (`xfce4-panel`) queries `/panels/panel-1/...` and ignored the top-level `/panel-1` declaration, causing the panel not to display.
- **PANEL_XML_BEFORE:**
```xml
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
  </property>
  <property name="panel-1" type="empty">
    <property name="position" type="string" value="p=6;x=0;y=0"/>
    ...
```
- **PANEL_XML_AFTER:**
```xml
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
    <property name="panel-1" type="empty">
      <property name="position" type="string" value="p=6;x=0;y=0"/>
      ...
    </property>
  </property>
  <property name="plugins" type="empty">
    ...
```
- **PANEL_DEVICE_PATCH_RESULT:** PASS. Patched live on Poco X6 Pro installed container without rebuilding rootfs first. Process launched cleanly.
- **PANEL_CANONICAL_CONFIG_SOURCE:** Canonical effective XFCE 4.20 configuration captured from live device, verified under `xfconf-query`, and staged in `tools/rootfs/overlay/alpaquita/home/flux/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml`.
- **LIVE_XFCONF_PATHS:** Verified via `xfconf-query -c xfce4-panel -lv`:
  - `/panels/panel-1/position` = `p=6;x=0;y=0`
  - `/panels/panel-1/length` = `100`
  - `/panels/panel-1/size` = `32`
  - `/panels/panel-1/plugin-ids` = `[1, 2, 3, 4, 5]`
  - `/plugins/plugin-1` = `applicationsmenu`
  - `/plugins/plugin-2` = `tasklist`
  - `/plugins/plugin-3` = `separator`
  - `/plugins/plugin-4` = `systray`
  - `/plugins/plugin-5` = `clock`
- **PANEL_VISIBLE:** PASS. Full top horizontal panel visible on device with Applications menu, active window tasklist item, and clock.
- **PANEL_SCREENSHOT:** Captured on device:
  - `docs/results/w15_xfce_panel_live.png`
  - `docs/results/w15_xfce_panel_fresh_install.png`
  - `docs/results/w15_xfce_terminal_desktop.png`

---

## Settings & UI Cleanups
- **REPAIRS_CARD_REMOVED:** PASS. Removed `settingsHubLayout.addView(buildScriptsSectionButton())` and its trailing spacer from `MainActivity.kt`. Settings Hub no longer displays the Repairs / Repair Scripts card.
- **ONBOARDING_COPY_UPDATED:** PASS. All onboarding pages aligned with prebuilt immutable runtime architecture.
  - Intro slideshow: lists bundled suite (OpenCode, Codex, Qwen Code, Claude Code, Antigravity, Grok, Kiro), removed Aider.
  - Linux Runtime page: describes all-in-one prebuilt runtime, no guest setup or repair scripts run after extraction.
  - Complete page: displays `ENVIRONMENT READY`, summarizes bundled Linux runtime, XFCE desktop, developer toolchain, Git/GitHub CLI, and 7 AI CLIs.
- **DEAD_INSTALL_STATE_REMOVED:** PASS. Removed unused fields `enableDebianCustomization`, `enableXfceGui`, `enableAiCliInstall`, `aiCliSetupOutcome`, `maybeRunCliToolsSetupProot`, and `maybeRunCliToolsSetupChroot`. Removed dead imports `CliToolsInstaller`, `InstallPlanCatalog`, and `CustomBrutalistToggle`.
- **SIMPLIFIED_SENTINEL:** PASS. `verifyRuntimeSentinel()` simplified to parameterless, always asserting the complete runtime release including `rootfs-release`, `glibc`, `/home/flux`, `bash`, `git`, `gh`, `startxfce4`, `xfce4-panel`, `xfce4-terminal`, and `opencode`.

---

## Onboarding Progress Race Fix
- **PROGRESS_ROOT_CAUSE:** `ValueAnimator` listeners executed `onAnimationEnd()` without cancellation checks and without run-generation isolation, allowing cancelled or stale callbacks from prior setup events to overwrite newer progress values (e.g. 100 -> 33 -> 100).
- **PROGRESS_SEQUENCE_OBSERVED:** `0%` -> `10%` -> `20%` -> `33%` -> `60%` -> `90%` -> `95%` -> `99%` (capped) -> `100%` (final success).
- **PROGRESS_MONOTONIC:** PASS. Enforced via `SetupProgressCoordinator`: within an active generation run, intermediate calculations can never decrease the overall percent.
- **PROGRESS_100_ONLY_FINAL:** PASS. Calculations before `finishSetupProgressSuccess()` are strictly clamped to a maximum of 99%. Only terminal completion sets 100%. Tested and verified with unit tests (`SetupProgressCoordinatorTest`).

---

## Release Artifacts
- **ROOTFS_SHA256:** `5f2ca58ad46733a8bbadfce90a67ac4d18f43a8f58aefd99215bb55c5b6d013f`
- **ROOTFS_SIZE:** 1,477,425,386 bytes (1.38 GiB)
- **SIZE_GATE:** PASS (1,477,425,386 < 1,556,925,644 byte threshold).
- **SSOT_PINNED:** Updated in `tools/rootfs/versions.env` and `app/src/main/java/com/zenithblue/nativecode/onboarding/RootfsSource.kt`.

---

## Physical Device Regression Matrix (Poco X6 Pro)

### 1. Fresh PRoot Installation: PASS
- App data cleared via `pm clear com.zenithblue.nativecode`.
- `RootfsSourceResolver` resolved `alpaquita_rootfs.tar.zst` from `/data/local/tmp`.
- Checksum verified: `5f2ca58ad46733a8bbadfce90a67ac4d18f43a8f58aefd99215bb55c5b6d013f`.
- Stream extraction and `proot-distro install` completed in 38 seconds.
- Sentinel verified all required components.
- Setup progress was strictly monotonic (0% -> 95% -> 100%).

### 2. Zero Guest Setup: PASS
- Post-install phase executed zero guest scripts (`setup_alpaquita_family.sh`, `setup_hw_accel_debian.sh`, `setup_xfce4.sh`, `setup_customization_alpine.sh`, `setup_cli_tools.sh` were NOT executed).
- Log confirmed clean extraction and marker verification.

### 3. Redo Onboarding with Active PRoot Shell: PASS
- Background `sleep 300` process kept active inside PRoot.
- Launched Onboarding walkthrough via Settings -> "RE-RUN ONBOARDING".
- `inspectExistingAlpaquita()` detected existing valid container in 2 seconds.
- Skipped PAD re-download, skipped `proot-distro install`, zero "container 'alpaquita' is busy" errors.
- Monotonic progress completed cleanly to 100%.

### 4. Project Creation & Persistence: PASS
- Created `W15_Smoke_Project` via app UI.
- Project opened directly in Workspace.
- App force-stopped and restarted: `W15_SMOKE_PROJECT` persisted under "RECENT WORKSPACES".
- Workspace reopened project with selected CWD intact.

### 5. Directory Browser: PASS
- Opened DIRECTORY tab in `W15_Smoke_Project`.
- Displayed `README.md` and `untracked.txt`.
- Tapped `README.md`: opened in built-in File Viewer showing preview and source.

### 6. Git Status & Diffs: PASS
- Modified tracked `README.md` and created untracked `untracked.txt`.
- Opened DIFF tab: showed `+1 -0 LOC`, `MOD README.md`, and `NEW untracked.txt`.
- Tapped `MOD README.md`: inline tracked diff rendered with `@@ -1 +1,2 @@` and `+ modified tracked line`.
- Tapped `NEW untracked.txt`: inline untracked diff rendered with `@@ -0,0 +1 @@` and `+ untracked file content`.

### 7. GitHub CLI: PASS
- `/usr/local/bin/gh` detected.
- `gh version 2.67.0 (2025-02-11)` executed with 0 errors.

### 8. AI CLIs Suite: PASS
- All 7 CLIs verified on physical device:
  - `opencode`: 1.18.27
  - `codex`: codex-cli 0.153.2
  - `qwen`: 0.23.0
  - `claude`: 2.1.263 (Claude Code)
  - `agy`: 1.1.27
  - `grok`: grok 1.0.13 (5e9a58528b76)
  - `kiro-cli`: kiro-cli 2.21.1
- OpenCode launcher in Workspace opened full interactive terminal running `opencode --auto` in `~/repos/w15-smoke-project:main`.

### 9. XFCE Theme, Panel & Terminal: PASS
- Theme: `Space-transparency`.
- Icons: `Papirus-Dark`.
- Cursor: `Vimix-white-cursors`.
- Wallpaper: `/home/flux/Pictures/Wallpapers/fluxlinux-dark.png`.
- Panel: Fully visible at top (`p=6;x=0;y=0`, size 32, length 100) with Applications menu, active window item in tasklist, separator, systray, and clock.
- Terminal: `xfce4-terminal` launched on desktop with window decorations and registered on panel tasklist. Running `bash` as `uid=1000(flux)`, `HOME=/home/flux`, `PWD=/home/flux`.

### 10. GUI Lifecycle & Idempotency: PASS
- `STOP` -> `0` residual processes.
- `START` -> X11 socket ready, termux-x11 PID running, xfce4-session running.
- `START` again -> Detected existing session, refreshed display activity without duplicate processes.
- `STOP` -> Complete teardown verified.
- `START` again -> Restart successful with single instance of termux-x11 and xfce4-session.

### 11. Settings Hub: PASS
- No Repairs / Repair Scripts card.
- Accessible sections: AI CLI TOOLS, AI SAFETY & REPORT, STORAGE SETTINGS, SOFTWARE MANAGER, X11 SETTINGS, RE-RUN ONBOARDING, PRIVACY & LICENSES.
- AI CLI page is read-only / non-mutating with OPEN TERMINAL and individual OPEN buttons.

### 12. Package Inventory: PASS
- Software Manager opened, loaded 217 packages from Alpaquita APK database.
- Expanded SYSTEM category showing `acl-libs`, `apk-tools`, `bash`, `binutils`, etc.

### 13. Offline / Cold Restart: PASS
- App force-stopped, restarted.
- Existing rootfs immediately recognized without network prompt.
- Workspace and project opened offline.

### 14. PRoot Ownership: PASS
- Host filesystem: `/data/data/com.zenithblue.nativecode/...` owned by app UID `u0_a322:u0_a322`.
- Guest filesystem: `/home/flux/...` owned by `1000:1000 644/755`.
- Panel configuration file owned by `1000:1000 644`.

### 15. Chroot Smoke: PASS
- Verified `/data/local/tmp/chrootAlpine`.
- Root access available via KernelSU (`uid=0`).
- `/home/flux` ownership verified as `1000:1000`.

---

## Test Suite Results
- `./gradlew assembleDebug`: **PASS**
- `./gradlew test`: **PASS** (including new `SetupProgressCoordinatorTest`)
- `bash tools/rootfs/test_build_pipeline.sh`: **PASS**
- `bash tools/rootfs/test_prebuilt_paths.sh`: **PASS** (14/14 checks)
- `bash tools/app/test_alpaquita_app_runtime_contract.sh`: **PASS**
- `bash tools/app/test_prebuilt_runtime_immutable_contract.sh`: **PASS**
- `python3 tools/rootfs/test_xfce_overlay.py`: **PASS**
- `bash tools/app/w13_full_app_smoke.sh --all`: **PASS**

---

## Remaining Issues
- None. All release gates met.

---

## Release Recommendation
**APPROVE FOR RELEASE.**
The immutable Alpaquita glibc runtime is completely verified with XFCE panel rendering, non-mutating UI, monotonic progress, and full project/git regression on physical hardware.
