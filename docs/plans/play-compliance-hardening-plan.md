# Plan: play-compliance-hardening

## Task Summary

Create a Google Play–safe distribution path for NativeCode (`zenithblue-oss/nativecode-ai`) without weakening the full `master` build. Introduce an explicit `store` flavor dimension (`full` / `play`), remove signing secrets, make Play PRoot-only, strip remote rootfs + remote marketplace-script execution from Play, add AI per-vendor consent + in-app reporting, harden manifest/packaging, and add automated compliance checks. Deliverable: clean `play` branch producing reviewable `./gradlew bundlePlayRelease`.

## Research Sources

- <source: file app/build.gradle.kts:24-28> — applicationId `com.zenithblue.nativecode`, minSdk 26, targetSdk 36, versionCode 6, **no productFlavors**; `isMinifyEnabled = false` (line 44), missing `app/proguard-rules.pro` (`ls` → not found)
- <source: file app/build.gradle.kts:32-39> — **LIVE SIGNING SECRETS**: `storeFile = file("/home/user/repos/keys/nativecode-release.jks")`, `storePassword = "REDACTED"` (line 35), `keyPassword = "REDACTED"` (line 37)
- <source: file docs/plans/proot-parity-v2-plan.md:61-85> — canonical signing SSOT: `providers.gradleProperty("nativecode.keystore.path").orElse(environmentVariable("NATIVECODE_KEYSTORE"))` + `hasReleaseSigning = isPresent && isPresent` + conditional `signingConfigs` + `GradleException` only on Release tasks
- <source: file app/src/main/AndroidManifest.xml:1-12> — main manifest declares only `xmlns:android` (line 1); perms: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+SPECIAL_USE), POST_NOTIFICATIONS, **ACCESS_SUPERUSER (line 9)**; `allowBackup="false"` (line ~12); FileProvider exported=false; launcher-only exported=true
- <source: file termux-x11/src/main/AndroidManifest.xml:1-10> — already declares `xmlns:tools` but main does not; currently clean (FOREGROUND_SERVICE/INTERNET/POST_NOTIFICATIONS only) — transitive dep risk via termux-app not via local module
- <source: file app/src/main/assets/scripts/flux_install.sh:14-17,126-135> — remote rootfs fallback: `ROOTFS_URL=${FLUX_ROOTFS_URL:-https://github.com/zenithblue-oss/fluxlinux/releases/download/rootfs/debian_13_rootfs.tar.xz}` + curl/wget download
- <source: file app/src/main/assets/scripts/chroot/setup_debian13_chroot.sh:15-16> — same GitHub rootfs URL in chroot setup
- <source: file app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt:~3285-3310> and MainActivity.kt:~12955 (copy of `assets/rootfs/debian_13_rootfs.tar.xz` → $HOME via `assets.open("rootfs/debian_13_rootfs.tar.xz")`) — rootfs IS bundled in APK assets **before** any download fallback
- <source: fs app/src/main/assets/> — `bootstrap.tar` 122M + `rootfs/debian_13_rootfs.tar.xz` 82M = 204M raw under `noCompress += ["xz","tar"]` (`app/build.gradle.kts:76-77`); `.gitignore:18-21` lists `bootstrap.tar` + `app/src/main/assets/rootfs/*.tar.xz` as ignored (locally present, not tracked via `git ls-files`); base AAB will exceed 150M warning / approach 200M cap
- <source: file app/src/main/java/com/zenithblue/nativecode/marketplace/MarketplaceClient.kt:49-72> — downloads `install.sh`/`uninstall.sh` + `package.json` + `lib/nc_mp_common.sh` via `rawUrl(".../install.sh")`, `dest.setExecutable(true)` (line ~72)
- <source: file app/src/main/java/com/zenithblue/nativecode/marketplace/MarketplaceClient.kt:100-180> — `parseCatalog` accepts any `script_path = o.optString("script_path","packages/<id>")` + `icon = o.optString("icon")` without allowlist/HTTPS check; fields: `schema_version`, `min_app_version`, `env`, `size_hint_mb` exist but not validated
- <source: file app/src/main/java/com/zenithblue/nativecode/marketplace/MarketplaceIcons.kt:48-95> — `bind()` fetches arbitrary `pkg.icon` URL (http→https? not enforced) via `downloadTo` without size cap/MIME check; `iconCacheFile` hash only
- <source: file app/src/main/java/com/zenithblue/nativecode/marketplace/PackageInstallRunner.kt:324-447> — executes staged scripts: `lines += "/bin/bash $GUEST_STAGE/$id/$script"` (line ~339) + `stageIntoGuest(... method=="chroot"... RootShell.capture)` (line ~345) + `buildRootExec` chroot branch; `chmod +x` + `/bin/bash` remain in `src/main`
- <source: file app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt:8> — `var currentMethod = "proot"` mutable global; `build()`/`buildInteractive()` delegate to `ChrootCommandBuilder` when `method=="chroot"` (lines 24-37)
- <source: file app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt:5-35> — `object ChrootCommandBuilder` with `CHROOT_PATH="/data/local/tmp/chrootDebian13"`, `CHROOT_HELPER` scripts, `build()`/`buildInteractive()` → `RootShell`
- <source: file app/src/main/java/com/zenithblue/nativecode/RootShellService.kt:16> — `object RootShell` (file named RootShellService.kt, symbol is `RootShell`), su discovery
- <source: file app/src/main/java/com/zenithblue/nativecode/terminal/ChrootProcessManager.kt:5, ChrootSizeManager.kt:5, PackageInstallRunner.kt:324, CliToolsInstaller.kt:78, LinuxCommandBuilder.kt:18> — 6+ callers of `RootShell`/`ChrootCommandBuilder` still in `src/main`; `ls app/src` → `main test` only (no flavor dirs)
- <source: grep currentMethod> — `MainActivity.kt:598` restores `LinuxCommandBuilder.currentMethod = prefs.getString("linux_method","proot")`; 40+ hits; also `OnboardingActivity.kt`, `ProjectManager.kt:19`, `CliToolsInstaller.runChrootAsync:32`, `AppUpgrade.kt`
- <source: file app/src/main/java/com/zenithblue/nativecode/cliauth/AiCliProvisionState.kt:20-35> — prefs `nativecode_prefs` keys `ai_cli_tools_provisioned_proot/chroot`, legacy `ai_cli_tools_provisioned`; no `ai_consent_<vendor>` keys exist (grep → 0 hits); only `enable_ai_cli_install`
- <source: file app/src/main/java/com/zenithblue/nativecode/cliauth/ReportMailHelper.kt:73-77> — `mailto:` `ACTION_SENDTO` only
- <source: file app/src/main/assets/scripts/setup_cli_tools.sh:292-369> — AI CLIs via `npm_global_install "@openai/codex"` + `curl https://claude.ai/install.sh` + qwen/grok/kiro loops
- <source: repo state> — branches `master`, `target-sdk-bypass`; no `play` branch; `.gitignore:1-30` lacks `keys/`, `**/*.jks`

### Corrections vs. user draft (verified)

1. **Rootfs delivery (§6) — bundled but oversized**: Debian rootfs is already shipped inside APK assets, so deleting the curl/wget fallback makes Play policy-compliant without immediate PAD. HOWEVER `bootstrap.tar 122M + rootfs 82M = 204M` raw (noCompress) pushes base AAB past Play's 150M warning toward 200M cap. PAD (`install-time` asset-pack `nativecode-rootfs`) is therefore no longer "optional later enhancement" — plan now includes a **size gate** and a trigger: if `bundletool get-size --size base` ≥150M, move rootfs to asset-pack in the same phase. This addresses review CRITICAL-1.
2. **Minification (§30)**: `isMinifyEnabled=false` and `proguard-rules.pro` missing → enabling R8 deferred behind device-matrix validation; plan adds explicit creation of `app/proguard-rules.pro` with keep rules for `libproot`/`termux-shared` as prerequisite.
3. Chroot/rootfs scripts + Kotlin helpers are interwoven (`src/main` callers everywhere) — flavor split must move full closure or use `BuildConfig` guards exhaustively, not just hide UI.
4. Marketplace `icon` field + `script_path` need allowlist hardening, not just exec removal.

## Current Architecture

Single-module app (`app/`, namespace `com.zenithblue.nativecode`) + `termux-x11` project module + `stub` AAR. Compose+View, Termux v0.118.0. Monolithic `MainActivity.kt` (~20k lines), `OnboardingActivity.kt` (~3.6k). Assets carry `bootstrap.tar` (122M) + `rootfs/debian_13_rootfs.tar.xz` (82M) uncompressed + shell scripts (`flux_install.sh`, `chroot/setup_debian13_chroot.sh`, `setup_cli_tools.sh`). Marketplace = remote `catalog.json` (`MarketplacePaths.RAW_BASE=https://raw.githubusercontent.com/zenithblue-oss/nativecode-marketplace/main`) → downloaded `install.sh/uninstall.sh` executed inside proot guest via `PackageInstallRunner`. Root paths: `RootShell` su discovery, `ChrootCommandBuilder`, `ChrootProcessManager/SizeManager`. Existing plans pipeline active.

## Affected Components & Dependencies

- `app/build.gradle.kts` — flavors, signing (`hasReleaseSigning` pattern), `noCompress`, packaging
- `app/src/play/`, `app/src/full/` source sets — new (assets shadow semantics)
- `AndroidManifest.xml` (main + play overlay with `xmlns:tools`) — permission removal
- `terminal/LinuxCommandBuilder.kt`, `terminal/ChrootCommandBuilder.kt` — guard centralization
- `RootShellService.kt` (object `RootShell`), `terminal/ChrootProcessManager.kt`, `terminal/ChrootSizeManager.kt` — closure to move/stub
- `MarketplaceClient.kt`, `MarketplaceIcons.kt`, `MarketplacePaths.kt`, `MarketplaceModels.kt`, `PackageInstallRunner.kt`, `InstallRegistry.kt` — recipe redesign + flavor split
- `assets/scripts/flux_install.sh`, `assets/scripts/chroot/*` — asset shadowing
- `cliauth/*` (`CliToolsInstaller.kt`, `AiCliProvisionState.kt`, `ReportMailHelper.kt`, `CredentialClearService.kt`) — consent/report
- `app/proguard-rules.pro` — new (R8 prerequisite)
- `scripts/check_play_compliance.sh` — CI gate (size + manifest + grep)
- Depends on: `termux-app` v0.118.0 (transitive perm risk → audit), `termux-x11` module

## Implementation Steps (ordered, smallest correct change)

### Phase 0 — Security emergency (master, immediate — blocks `play` branch)
1. **Signing de-secret** (`app/build.gradle.kts:32-39`): Replace literals with proot-parity SSOT:
   ```kotlin
   val releaseKeystore = providers.gradleProperty("nativecode.keystore.path")
       .orElse(providers.environmentVariable("NATIVECODE_KEYSTORE"))
   val releaseKeystorePwd = providers.gradleProperty("nativecode.keystore.password")
       .orElse(providers.environmentVariable("NATIVECODE_KEYSTORE_PASSWORD"))
   val releaseKeyAlias = providers.gradleProperty("nativecode.keystore.alias")
       .orElse(providers.environmentVariable("NATIVECODE_KEY_ALIAS"))
   val releaseKeyPwd = providers.gradleProperty("nativecode.key.password")
       .orElse(providers.environmentVariable("NATIVECODE_KEY_PASSWORD"))
   val hasReleaseSigning = releaseKeystore.isPresent && releaseKeystorePwd.isPresent
   signingConfigs { if (hasReleaseSigning) create("release") { /* ... */ } }
   buildTypes { release { if (!hasReleaseSigning && gradle.startParameter.taskNames.any{ it.contains("Release", true) }) throw GradleException("Release signing credentials required ...") else if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release") } }
   ```
   Document property names + `NATIVECODE_KEYSTORE_BASE64` (base64 jks for CI) in `docs/setup/signing.md`.
2. **Git hygiene** (`.gitignore`): Append `keys/`, `**/*.jks`, `**/*.keystore`, `keystore.properties`, `signing.properties`; `git log --all --diff-filter=D -- app/build.gradle.kts` + `git grep -i "REDACTED"` audit; confirm `git ls-files` shows no `*.jks`.
3. **Rotation** (manual): Treat `REDACTED` as compromised; use Play Console **upload-key reset** (not app-signing key replacement — Play App Signing manages app-signing key); store new secret in GitHub Actions; verify `git grep storePassword` zero. (Do NOT casually rotate app-signing key.)
4. **Create `app/proguard-rules.pro`** with keeps for `libproot.so`, `libbash.so`, `termux-shared` reflection (prereq for Phase 8 R8 enable — file currently missing).
   Exit: `git grep storePassword` zero; `assembleDebug` passes without creds; `assembleRelease` without creds fails with `GradleException`; `app/proguard-rules.pro` exists.

### Phase 1 — Build split (branch `play` from post-Phase-0 master)
5. `git checkout -b play`.
6. Add `flavorDimensions "store"` + `productFlavors { create("full"){buildConfigField("boolean","PLAY_STORE_BUILD","false")} create("play"){buildConfigField("boolean","PLAY_STORE_BUILD","true")} }` (keep `applicationId` = `com.zenithblue.nativecode` — already production ID).
7. Create `app/src/full/` + `app/src/play/` dirs. No code moved yet — just skeletons + flavor-specific manifests placeholder.
   Exit: `assemblePlayDebug` + `assembleFullDebug` both succeed (single-asset set still).

### Phase 2 — PRoot-only Play (CRITICAL-2 + CRITICAL-3 fix)
8. **Manifest overlay** (`app/src/play/AndroidManifest.xml`) — MUST include `xmlns:tools`:
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
             xmlns:tools="http://schemas.android.com/tools">
     <uses-permission android:name="android.permission.ACCESS_SUPERUSER" tools:node="remove"/>
     <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:node="remove"/>
     <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" tools:node="remove"/>
     <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:node="remove"/>
     <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" tools:node="remove"/>
     <uses-permission android:name="android.permission.READ_LOGS" tools:node="remove"/>
     <uses-permission android:name="android.permission.DUMP" tools:node="remove"/>
     <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:node="remove"/>
     <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" tools:node="remove"/>
     <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" tools:node="remove"/>
   </manifest>
   ```
9. **Compile-time exclusion** — move FULL CLOSURE into `src/full/java/...` and stub in `src/play/java/...` (chosen model: compile-time exclusion, not runtime-only):
   - Move: `RootShellService.kt` (object `RootShell`), `terminal/ChrootCommandBuilder.kt`, `terminal/ChrootProcessManager.kt`, `terminal/ChrootSizeManager.kt`, `assets/scripts/chroot/` (all `*.sh` under `scripts/chroot/`) into `src/full/`.
   - Provide `src/play/java` stubs with same package/symbol + same public `const val` constants (`CHROOT_PATH`, `CHROOT_HELPER`, `CHROOT_HELPER_VERSION`, `SESSION_EXEC` etc. from `ChrootCommandBuilder.kt:12-20`) so `ProjectPathResolver.kt:30,45,60` compiles, but every public **method** throws `UnsupportedOperationException("Chroot unavailable in Play build")` (so `src/main` callers compile against Play).
   - Alternative rejected: keep single set + pure `BuildConfig` guards — leaves root code in AAB; stubs are cleaner for review.
10. **Runtime defense-in-depth guards** (even with stubs) — exhaustive list:
    - `terminal/LinuxCommandBuilder.build()` + `buildInteractive()`: first line `if (BuildConfig.PLAY_STORE_BUILD && method=="chroot") throw UnsupportedOperationException`.
    - `terminal/ChrootCommandBuilder.build()` / `buildInteractive()` / `ensureHelperScript()` — immediate throw in Play stubs (above) or guard in main if stub not used.
    - `terminal/ChrootProcessManager` + `ChrootSizeManager` — stubs throw.
    - `RootShell.isRootAvailable()` / `executeSync()` — stub returns false / throws in Play.
    - `PackageInstallRunner.stageIntoGuest(..., method)`: `if (BuildConfig.PLAY_STORE_BUILD && method=="chroot") throw`.
    - `CliToolsInstaller.runChrootAsync` — early return in Play (see Phase 5).
    - `terminal/ProjectManager.kt:19,50` + `ProjectPathResolver.kt:28,43,58,79` — methods already take `method` param defaulting to `currentMethod`; Play forces `proot` via coercion below.
    - `AppUpgrade.kt` chroot migrations — guard with `if (PLAY_STORE_BUILD) skip chroot path`.
    - `github/GitHubCliService.kt:122,291,605` — chroot branch `RootShell`/`ChrootCommandBuilder` usage: Play guard `if (BuildConfig.PLAY_STORE_BUILD) return false / early return` (avoid throwing inside coroutine; stub throw remains as second line of defense).
11. **Prefs coercion at startup** (`MainActivity.kt:598` + `OnboardingActivity` init):
    ```kotlin
    var m = prefs.getString("linux_method","proot") ?: "proot"
    if (BuildConfig.PLAY_STORE_BUILD && m=="chroot") { m="proot"; prefs.edit().putString("linux_method","proot").apply() }
    LinuxCommandBuilder.currentMethod = m
    ```
    Also clear `AiCliProvisionState` chroot key (`ai_cli_tools_provisioned_chroot`) in Play on startup.
12. **UI**: Flavor-gated composable branches in `MainActivity` settings + `OnboardingActivity` hide chroot/root selection; copy: "NativeCode uses an isolated PRoot Linux environment. No root access is required." Remove: chroot selection, root/KernelSU/Magisk status, Root Shell / Debian Shell Rooted, chroot setup/repair/storage/process tools.
    Exit: `assemblePlayDebug` compiles (stub closure); merged Play manifest lacks `ACCESS_SUPERUSER` (verified via `processPlayReleaseMainManifest` intermediates + `bundletool dump manifest`); Play debug on unrooted device never requests root regardless of stored prefs.

### Phase 3 — Rootfs delivery hardening (CRITICAL-1 + MINOR asset-merge fix)
13. **Asset shadowing semantics** — Gradle merges `src/main/assets` into BOTH flavors unless shadowed. Worker MUST create SAME relative path `assets/scripts/flux_install.sh` in BOTH `src/play/` and `src/full/` to shadow `src/main/assets/scripts/flux_install.sh`. Likewise for any other flavored asset. Do NOT rely on `src/main` fallback.
    - `src/play/assets/scripts/flux_install.sh`: **NO** `ROOTFS_URL`, **NO** `curl`/`wget` block. Logic: if `[ ! -f "$ROOTFS_ARCHIVE" ]` (archive path under `$HOME`/asset-pack), then `echo "Required Debian environment is missing. Please reinstall NativeCode from Google Play."; exit 1`. Include SHA-256 check against bundled `rootfs-manifest.json` before extraction (both flavors).
    - `src/full/assets/scripts/flux_install.sh`: keep current GitHub fallback (copy of original) via `src/full` shadow.
14. **SHA-256 manifest** (`rootfs-manifest.json` `{name:"debian",version:"13",arch:"arm64",sha256,format:"tar.xz"}`) — validate before extraction (both flavors improvement).
15. **Size gate + PAD trigger** (new — fixes CRITICAL-1):
    - After creating flavor shadowing, run `./gradlew bundlePlayRelease` + `bundletool get-size --bundle app-play-release.aab --size base` (compressed base module download size) — previously written as `get-size total`; total includes asset-packs so use **base**. Alternative: `bundletool build-apks --bundle ... && unzip -l base-master.apk`.
    - Gate: `aab_base_bytes < 150 * 1024 * 1024` (warning) and `< 200M` (hard). Add to `scripts/check_play_compliance.sh`.
    - Trigger: if measured base ≥150M, execute PAD mitigation in SAME phase (not deferred): create `nativecode-rootfs` install-time asset-pack module (`src/main/assets/rootfs/` moved to `nativecode-rootfs/src/main/assets/rootfs/`, `build.gradle` asset-pack), update Play shadow script to read from `AssetManager` asset-pack path, re-run size gate. Document decision in `docs/play/architecture.md`.
    - CI also does `unzip -l app-play-release.apk | grep flux_install` + `grep -R FLUX_ROOTFS_URL` on extracted flavor assets (not just source) to ensure fallback not reintroduced via `src/main` merge.
    Exit: `grep -R "fluxlinux/releases/download\|FLUX_ROOTFS_URL" app/src/play/` zero; `unzip -l` check zero for Play APK; `get-size --size base` <150M base (or PAD implemented and passing); airplane-mode provision from Play asset succeeds.

### Phase 4 — Marketplace redesign (CRITICAL-4 + MINOR allowlist fix)
16. **New sealed model** (`marketplace/MarketplaceModels.kt`): `sealed interface InstallerRecipe { data class Apt(val packages:List<String>):InstallerRecipe; data class Npm(...); data class Pip(...); data class BuiltInScript(val id:String):InstallerRecipe }` + allowlist validator. Catalog validation (new `MarketplaceCatalogValidator.kt` in both flavors, stricter in Play):
    - `schema_version` known, `min_app_version` ≤ current `versionCode`, `env` allowlist = `["proot"]` for Play (reject any `chroot`), `size_hint_mb` cap (e.g., 500M for Play), field allowlist (drop unknown keys), `script_path` sanitized (reject `..`/`//`), `size` caps.
    - HTTPS-only for all HTTP paths; max payload (catalog ≤500KB, individual icon ≤200KB).
17. **Flavor split** (compile-time, not just runtime `if`):
    - `src/play/java/.../marketplace/MarketplaceClient.kt` = metadata+icons ONLY: fetch catalog via HTTPS+size cap+schema allowlist; `ensurePackageScripts()` deleted; `downloadTo(install.sh/uninstall.sh)` removed; only `catalog.json` + icons.
    - `src/full/java/.../marketplace/MarketplaceClient.kt` = retains legacy `ensurePackageScripts` (for master).
    - `src/play/java/.../marketplace/PackageInstallRunner.kt` = **`InstallerRecipe` executor ONLY**: runs bundled recipes from `src/play/assets/marketplace/<id>/` via fixed allowlist commands (`apt-get`, `npm`, `pip`) or known `BuiltInScript` ids → bundled shell snippet from APK assets (`assets/marketplace/<recipe_id>/install.sh` shipped inside AAB, not downloaded). **Never** does `/bin/bash $GUEST_STAGE/$id/install.sh` over downloaded content. Unknown `recipe_id` → `IllegalArgumentException("Unsupported package. Update from Play.")`.
    - `src/full/java/.../marketplace/PackageInstallRunner.kt` = legacy runner (keeps `buildGuestBatch` `/bin/bash $GUEST_STAGE/$id/install.sh` over staged downloaded scripts — acceptable for full).
18. **Icon hardening** (`MarketplaceIcons.kt` flavor split or guarded): Play variant: HTTPS-only, host allowlist = `raw.githubusercontent.com` (or `MarketplacePaths.RAW_BASE` host), `Content-Type` must be `image/*`, size cap 200KB, decode via `BitmapFactory` only, **never** `setExecutable`, no shell.
19. **Consent copy**: Update `MarketplaceConsent` to §7.5 wording (metadata online, install only after user taps Install).
    Exit: `grep -R "FLUX_ROOTFS_URL\|downloadTo.*\.sh" app/src/play/` zero; Play merged sources (`src/main` + `src/play` with full excluded) contain no `"/bin/bash \$GUEST_STAGE"` pattern (verify via `check_play_compliance.sh` merged-source grep); changing remote `install.sh` on GitHub cannot alter Play behavior (unit test: mutate catalog `script_path`; Play runner ignores); icons → HTTPS+capped only.

### Phase 5 — AI compliance
20. **Defaults**: Verify onboarding `enable_ai_cli_install` default OFF; Debian install succeeds without AI (existing `CliToolsInstaller` gated by `AiCliProvisionState` per-method keys — keep behavior, ensure Play coerces chroot key cleared).
21. **Per-tool install cards** (prefer over monolithic "AI Suite"): Settings/AI Hub shows individual `Codex / Claude / Qwen / Grok / Kiro` cards with [Install] each. `CliToolsInstaller.kt` updated: Play per-tool flow constructs `guestCmd` per tool (`npm i -g @openai/codex` etc.), not suite loop, with per-vendor consent dialog BEFORE download/exec.
22. **Per-vendor consent** — dialog text per §9.3 + privacy/terms link; persist `ai_consent_<vendor>` booleans in `nativecode_prefs` (NEW keys distinct from `ai_cli_tools_provisioned_*`); `CliToolCatalog` maps tool→vendor (openai→`ai_consent_openai`, anthropic→`ai_consent_anthropic`, qwen→`ai_consent_qwen`, etc.); `CliToolsInstaller` checks consent before any `setup_cli_tools.sh` invocation; in Play stubs, `runChrootAsync` unreachable anyway.
23. **Credential removal** (`CredentialClearService` surfaced): Settings → AI Credentials list: per-vendor [Sign out] + [Clear all AI credentials] with confirmation dialog; verify removes `~/.config / .codex / .claude` token files via guest `rm`.
24. **In-app report** — **Contract defined** (fixes MAJOR-8):
    - Endpoint: placeholder `https://api.nativecode.app/v1/report` (configurable via `gradle.properties` `nativecode.report.url`; HTTPS-only; no auth yet, later API key if needed). Until backend is live, endpoint may 404 — queue-and-WorkManager-retry + mailto-secondary ensures Play disclosure satisfied via queue-only; document placeholder status in `docs/play/release-checklist.md`. Payload (JSON POST): `{appVersion, tool:String (codex|claude|...), category:String (harmful|unsafe_output|...), description:String (≤3500), isolation:"proot", diagnosticsOptIn:{includeVersion:boolean, includeToolVersion:boolean}}` — **never** includes API keys/tokens/filesystem contents/terminal history/source code unless user pastes.
    - UI: `Settings → AI Safety & Report` form (tool spinner, category spinner, description EditText, diagnostics checkboxes, [Submit]).
    - Storage: `filesDir/nativecode_reports/report-queue.json` (append-only, 10-item cap, each `{"id","payload","attempts","createdAt"}`). On submit failure or offline → enqueue. `WorkManager` exponential retry (15s→1h) with `NetworkType.CONNECTED` constraint; user actions [Retry]/[Delete] in queue screen. `ReportMailHelper` demoted to secondary link ("Also report to vendor via email") — not primary path.
    - Tests: unit test verifies payload excludes secrets + size cap; instrumentation test with `MockWebServer` verifies POST + queue-retry; `check_play_compliance.sh` not needed for network but manual QA covers.
25. No bundled API keys/tokens — audit `cliauth/*` + `assets/` for secrets.
    Exit: `ai_consent_<vendor>` persisted per vendor; report submits via MockWebServer in instrumentation test without leaving app; credential clear removes token files.

### Phase 6 — Privacy & store metadata
26. Fix privacy-policy contradictions: remove "open source" claim unless repo made public; state `allowBackup=false` wording; describe Play as `PRoot only` (no chroot mention); update remote content wording (`Marketplace metadata may be retrieved online. Installation logic in Play is bundled; package managers inside Debian may download at user request.`).
27. Public HTTPS privacy URL (not private GitHub); Data Safety matrix doc; reviewer notes (`docs/play/reviewer-notes.md`); store listing text (Play only, no root claims); trademark disclaimer per §18.
    Exit: docs in `docs/play/` complete; reviewer notes match merged-manifest reality.

### Phase 7 — Manifest & artifact audit
28. Run `./gradlew :app:processPlayReleaseMainManifest` (and `processFullReleaseMainManifest` for comparison). Inspect `app/build/intermediates/merged_manifests/playRelease/` + `bundletool dump manifest --bundle app-play-release.aab` + `aapt dump xmltree` for prohibited perms.
29. Exported-component review: re-verify post-merge `android:exported` (currently only launcher true) — `termux-x11` merge already clean.
30. FGS descriptions finalized per §16; APK-installation path audit (`grep -R PackageInstaller\|ACTION_INSTALL_PACKAGE\|application/vnd.android.package-archive` → empty for Play).
    Exit: merged Play manifest contains NONE of 9 prohibited perms; `bundletool dump` clean; `grep` APK paths clean.

### Phase 8 — Tests, CI gate, release
31. `scripts/check_play_compliance.sh` — gates (ALL must be checked on **merged Play sources** / AAB, not just `src/play/`):
    - `grep -R "storePassword\|keyPassword" --include="*.kts" app/` zero (no literals)
    - `grep -R "ACCESS_SUPERUSER\|REQUEST_INSTALL_PACKAGES\|MANAGE_EXTERNAL_STORAGE\|QUERY_ALL_PACKAGES\|WRITE_SECURE_SETTINGS\|READ_LOGS\|DUMP\|PACKAGE_USAGE_STATS\|SYSTEM_ALERT_WINDOW\|REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" app/src/play/` zero AND `bundletool dump manifest` prohibited-list zero
    - `grep -R "fluxlinux/releases/download\|FLUX_ROOTFS_URL" app/src/play/` zero AND `unzip -l app-play-release.apk | grep FLUX_ROOTFS_URL` zero
    - `grep -R "downloadTo.*\.sh\|/bin/bash \$GUEST_STAGE" app/src/play/` zero (Play sources only; full flavor exempt)
    - `targetSdk` check: `grep targetSdk app/build.gradle.kts` == 36
    - **AAB size**: `bundletool get-size --size base` <150M warning threshold, <200M hard (failure if exceeded without PAD; do not use `total` when PAD installed — checks base only)
    - `grep -R "PackageInstaller\|ACTION_INSTALL_PACKAGE" app/src/play/` zero
32. Device matrix smoke (API 26/30/33/34/35/36 arm64): install/provision/terminal (`bash zsh git python3 node gcc apt`), PRoot lifecycle, XFCE FGS, Marketplace (malformed catalog, offline, unknown recipe reject, uninstall), AI (off-by-default, consent cancel/accept, credential clear, report submit+queue retry), airplane-mode reinstall on Play APK, manifest diff `diff <(aapt dump xml full) <(aapt dump xml play)`.
33. `bundlePlayRelease`; `bundletool dump manifest` re-verify; **R8 decision**: keep `isMinifyEnabled=false` for first Play closed-test; enable `isMinifyEnabled=true` + `isShrinkResources=true` only after matrix PASS with `app/proguard-rules.pro` validated.
    Exit: `check_play_compliance.sh` exits 0; matrix PASS; Play Pre-launch report no unresolved criticals.

## File-Level Change Map

| File | Change | Rationale | Phase |
|---|---|---|---|
| app/build.gradle.kts | `hasReleaseSigning` providers+env SSOT, `signingConfigs` conditional, `GradleException` gated on Release tasks, `store` flavors + `PLAY_STORE_BUILD`, `proguard-rules.pro` reference | P0-1 | 0,1,8 |
| app/proguard-rules.pro | **NEW** — keep rules for `libproot`, `termux-shared`, `libbash` | R8 prereq | 0 |
| .gitignore | Append `keys/`, `**/*.jks`, `**/*.keystore`, `keystore.properties`, `signing.properties` | Secrets | 0 |
| docs/setup/signing.md | **NEW** — property names, `NATIVECODE_KEYSTORE_BASE64`, Play upload-key reset procedure, `git log` audit steps | Secrets | 0 |
| app/src/play/AndroidManifest.xml | **NEW** — `xmlns:tools` header + 10× `tools:node="remove"` perms | PRoot-only | 2 |
| app/src/full/java/**/RootShellService.kt | **MOVE** from `src/main` — object `RootShell` full impl | Closure | 2 |
| app/src/play/java/**/RootShellService.kt | **NEW stub** — throws `UnsupportedOperationException` | Closure | 2 |
| app/src/full/java/**/terminal/ChrootCommandBuilder.kt | MOVE full | Closure | 2 |
| app/src/play/java/**/terminal/ChrootCommandBuilder.kt | NEW stub | Closure | 2 |
| app/src/full/java/**/terminal/ChrootProcessManager.kt | MOVE | Closure | 2 |
| app/src/play/java/**/terminal/ChrootProcessManager.kt | NEW stub | Closure | 2 |
| app/src/full/java/**/terminal/ChrootSizeManager.kt | MOVE | Closure | 2 |
| app/src/play/java/**/terminal/ChrootSizeManager.kt | NEW stub | Closure | 2 |
| app/src/full/assets/scripts/chroot/** | MOVE all `scripts/chroot/*.sh` | Closure | 2 |
| terminal/LinuxCommandBuilder.kt | Add `PLAY_STORE_BUILD && method=="chroot"` throw in `build()`/`buildInteractive()` | Guard | 2 |
| MainActivity.kt (~20k lines, targeted anchors only: :598, :744) | Prefs coercion `linux_method`, hide root/chroot UI, `AppUpgrade` chroot guard | Guard+UI | 2 |
| OnboardingActivity.kt (~3285-3310 anchor) | PRoot-only copy, hide chroot selection | UI | 2 |
| terminal/ProjectManager.kt:19,50 | Guarded via `LinuxCommandBuilder` coercion (no direct edit needed beyond method param) | Guard | 2 |
| terminal/ProjectPathResolver.kt:28,43,58,79 | Same — method param already | Guard | 2 |
| cli auth AiCliProvisionState.kt | Clear `ai_cli_tools_provisioned_chroot` on Play startup | Guard | 2 |
| app/src/play/assets/scripts/flux_install.sh | **NEW shadow** — no `ROOTFS_URL`/curl/wget, SHA-256 check, exit 1 | Rootfs | 3 |
| app/src/full/assets/scripts/flux_install.sh | NEW shadow (copy of original) — preserves fallback for full | Rootfs | 3 |
| app/src/main/assets/scripts/flux_install.sh | Remains as common base but SHADOWED by both flavors (never merged directly) | Rootfs | 3 |
| nativecode-rootfs/ (optional, triggered by size gate) | **NEW asset-pack module** `install-time` with `rootfs.tar.xz` + manifest | Size | 3 |
| marketplace/MarketplaceModels.kt | `sealed InstallerRecipe` (Apt/Npm/Pip/BuiltInScript) + validator constants | Marketplace | 4 |
| marketplace/MarketplaceCatalogValidator.kt | **NEW** — schema allowlist, env/size/method checks | Marketplace | 4 |
| app/src/play/java/**/marketplace/MarketplaceClient.kt | Flavor impl — metadata only, HTTPS+size cap, no script download | Marketplace | 4 |
| app/src/full/java/**/marketplace/MarketplaceClient.kt | Flavor impl — retains legacy `ensurePackageScripts` | Marketplace | 4 |
| app/src/play/java/**/marketplace/PackageInstallRunner.kt | Flavor impl — `InstallerRecipe` bundled-recipes only | Marketplace | 4 |
| app/src/full/java/**/marketplace/PackageInstallRunner.kt | Flavor impl — legacy `/bin/bash $GUEST_STAGE` | Marketplace | 4 |
| marketplace/MarketplaceIcons.kt | Flavor split or guard — Play: HTTPS host allowlist + 200KB + MIME | Marketplace | 4 |
| marketplace/MarketplacePaths.kt | No move, but add constants for allowlist hosts | Marketplace | 4 |
| cliauth/CliToolsInstaller.kt | Per-tool flow + `ai_consent_<vendor>` check, Play `runChrootAsync` early return | AI | 5 |
| cliauth/AiVendorSafetyCatalog.kt, AiCliProvisionState.kt | `ai_consent_<vendor>` keys, per-tool catalog | AI | 5 |
| cliauth/ReportMailHelper.kt → cliauth/ReportSubmitter.kt + ReportQueue.kt | **NEW** HTTP report + queue (`filesDir/report-queue.json`, WorkManager) | AI | 5 |
| scripts/check_play_compliance.sh | **NEW** — 7 gates including AAB size + merged-manifest + asset unzip | CI | 8 |
| docs/play/** (`README.md`, `architecture.md`, `policy-audit.md`, `release-checklist.md`, `reviewer-notes.md`, `data-safety.md`, `fgs-declaration.md`, `ai-disclosure.md`, `marketplace-policy.md`) | **NEW docs set** | Docs | 6,7 |

## Testing Strategy

- **Unit (JUnit, existing scaffold)**: `MarketplaceCatalogValidator` rejects `env=chroot` in Play, unknown `InstallerRecipe`, oversized catalog; `MarketplaceIcons` rejects non-HTTPS / oversize; report payload excludes secrets + truncates description ≤3500; `rootfs-manifest.json` SHA-256 check.
- **Build gates**: `assemblePlayDebug` / `assembleFullDebug` / `bundlePlayRelease` / `processPlayReleaseMainManifest` succeed; `check_play_compliance.sh` exits 0 on merged Play tree + AAB; `aab_base_bytes <150M` (or PAD done); `diff` play vs full `aapt dump xmltree` shows only expected perm deltas.
- **Static**: `check_play_compliance.sh` in CI — includes `unzip -l app-play-release.apk | grep` fallback check + `bundletool dump manifest` prohibited-list grep.
- **Manual / device matrix** (API 26/30/33/34/35/36 arm64 core; 8→16 sweep if lab available): clean install, first launch, rootfs delivery (including **airplane-mode reinstall on Play APK**), interrupted delivery, low storage, PRoot start/stop/multiple terminals/shared-tmp, XFCE FGS notification stop flow, Marketplace (malformed catalog → graceful, offline, allowlisted install, unknown recipe rejected, uninstall), AI (off-by-default, disclosure cancel/accept per vendor, credential clear, report submit online + offline queue retry), no root prompt / no chroot option visible.

## Acceptance Criteria (objective, verifiable)

- [ ] `git grep -i "REDACTED"` empty; `git grep storePassword` zero; no `*.jks` tracked; `.gitignore` contains `keys/`
- [ ] `assemblePlayDebug` + `assembleFullDebug` succeed; `assembleRelease` without creds **fails** (`GradleException`), `assembleDebug` without creds **passes**
- [ ] `app/proguard-rules.pro` exists with keep rules
- [ ] Merged Play release manifest (`processPlayReleaseMainManifest` intermediates + `bundletool dump manifest`) contains **none** of: `ACCESS_SUPERUSER`, `REQUEST_INSTALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `DUMP`, `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- [ ] `grep -R "fluxlinux/releases/download\|FLUX_ROOTFS_URL" app/src/play/` zero **AND** `unzip -l app-play-release.apk` shows no fallback URL in extracted `assets/scripts/flux_install.sh`
- [ ] `grep -R "downloadTo.*\.sh\|/bin/bash \$GUEST_STAGE" app/src/play/` zero (merged Play sources); Play runner has no downloaded-script exec path (unit test: mutate remote catalog `script_path` — Play still executes only bundled recipes)
- [ ] `MarketplaceIcons` Play path: unit test verifies non-HTTPS + oversize icon rejected, never `setExecutable`
- [ ] Unknown marketplace recipe / `env: chroot` catalog entry rejected by unit test
- [ ] AI tools default OFF (instrumented onboarding test); `ai_consent_<vendor>` persisted per vendor; cancel blocks install
- [ ] In-app AI report: MockWebServer test — `POST https://api.nativecode.app/v1/report` JSON payload `{tool,category,description<=3500,isolation:"proot",appVersion}` with no secrets, succeeds in-app; offline enqueue `filesDir/report-queue.json` (cap 10) + WorkManager retry + manual Delete/Retry UI; mailto link is secondary only
- [ ] `scripts/check_play_compliance.sh` exits 0 on final tree (includes AAB size gate)
- [ ] `bundletool get-size --size base --bundle app-play-release.aab` reports **<150M** base (or PAD asset-pack installed and base <150M, do not use `total`); `bundletool dump manifest` clean; universal APK from bundle installs on unrooted arm64 device and provisions Debian from bundled/asset-pack with networking disabled

## Risks & Mitigations (NEW_RISKS)

- **R1** `termux-app` transitive perms not visible in main manifest → mitigated by per-perm `tools:node="remove"` + merged-manifest audit + `bundletool dump` gate.
- **R2** `MainActivity.kt` ~20k lines: targeted edits fragile → use `grep -n linux_method` anchors (`:598`, `:744`) and minimal `BuildConfig` coercion; no wholesale refactor.
- **R3** Flavor asset shadowing duplicates maintenance (`flux_install.sh` ×2) → acceptable; document divergence in `docs/play/architecture.md`; CI `unzip -l` catches drift.
- **R4** In-app report backend placeholder `api.nativecode.app` may not exist at first → queue-and-WorkManager-retry makes absence non-blocking; email remains secondary; MockWebServer makes test hermetic.
- **R5** Minification deferred → shipping without R8 is compliant; larger AAB addressed by size gate / PAD.
- **R6** Bootstrap + rootfs together near cap → size gate triggers PAD automatically; no silent oversize.
- **R7** `RootShell` closure move touches 4 files + asset dir — risk of missed import → mitigated by exhaustive grep list in Phase 2 + compilation gate `assemblePlayDebug`.
- **R8** `ai_consent_*` new prefs keys vs legacy `ai_cli_tools_provisioned_*` — risk of pref pollution → Play startup clears chroot key; consent keys namespaced separately.
- **R9** Icon host allowlist (`raw.githubusercontent.com`) must match `MarketplacePaths.RAW_BASE` — if marketplace forks, update allowlist constant.

## Handoff to Plan Reviewer

Re-validate: (1) Size gate + PAD trigger (CRITICAL-1) — does `get-size --size base <150M` (not total) + asset shadowing + `unzip -l` fallback check actually prevent both policy and store-size rejects? (2) Full closure move (CRITICAL-2) — does `src/play` stub set for 4 Kotlin files + `scripts/chroot/` with preserved `CHROOT_PATH` const vals actually make `assemblePlayDebug` compile with `src/main` callers? (3) Exhaustive `currentMethod` guards + prefs coercion (CRITICAL-3) — any remaining chroot path (ProjectManager, CliToolsInstaller, AppUpgrade, GitHubCliService) uncovered? (4) Marketplace flavor split (CRITICAL-4) — does `src/play` runner truly sever `fluxlinux/releases/download` + `downloadTo(.sh)` + `/bin/bash $GUEST_STAGE` while `src/full` remains functional? (5) Signing SSOT alignment with `proot-parity-v2-plan` (MAJOR-5) — debug vs release task gating correct? (6) `xmlns:tools` overlay + 10 perms (MAJOR-7) — any transitive perm still leaks? (7) Report contract (MAJOR-8) — payload, storage `report-queue.json` cap 10, WorkManager retry, MockWebServer test all sufficient for Play disclosure even with placeholder 404?

PLAN_STATUS: READY
PASS: 2
ITERATION: 2  // APPROVE at PASS2 ITER1; this ITER2 applies 3 MINOR patches (stub const vals, GitHubCliService guard, base size metric)
