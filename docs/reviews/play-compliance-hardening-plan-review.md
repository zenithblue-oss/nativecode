# Plan Review: play-compliance-hardening — PASS 1 ITER 1

## Verdict: REVISE
## Counts: CRITICAL 4 MAJOR 4 MINOR 4 SUGGESTIONS 2

### Findings

#### [CRITICAL] AAB base size exceeds Play limits despite Correction #1
- Location: `docs/plans/play-compliance-hardening-plan.md:66-68` + `app/build.gradle.kts:76-77` + `app/src/main/assets/`
- Problem: Plan treats PAD as optional because rootfs is bundled, but base APK already ships `bootstrap.tar` 122 MB + `rootfs/debian_13_rootfs.tar.xz` 82 MB = 204 MB raw with `noCompress += ["xz","tar"]`. Base AAB will be >150 MB (Play warning) and approaches 200 MB hard cap plus code/assets. Plan has no size audit or mitigation.
- Evidence: `app/build.gradle.kts:76-77` noCompress; `ls -lh app/src/main/assets/bootstrap.tar` 122M `app/src/main/assets/rootfs/debian_13_rootfs.tar.xz` 82M; `du` confirms. Play docs: 150 MB base warning, 200 MB limit (base). Plan §13 defers `nativecode-rootfs` PAD module as OPTIONAL.
- Impact: Play Console rejects or review fails for oversized AAB even if remote-download-free; airplane-mode provision passes but store deliverable fails.
- Required planner change: Add Phase 0/8 size audit: `bundletool get-size total` + `aab size gate (<150 MB)`; decide: strip bootstrap duplication, compress vs PAD, or move rootfs to `install-time` asset-pack now (not deferred). Add `scripts/check_play_compliance.sh` check for AAB total size and document mitigation trigger.

#### [CRITICAL] Compile-time chroot exclusion underspecified — Play build breaks
- Location: `docs/plans/play-compliance-hardening-plan.md:62` (`Move RootShellService.kt + chroot builder into src/full/`)
- Problem: `RootShell` is `object RootShell` in `RootShellService.kt:16` but plan names `RootShellService.kt`. More importantly, moving only `RootShellService.kt` + `ChrootCommandBuilder.kt` leaves 6+ `src/main` call sites still importing them: `ChrootProcessManager.kt:5`, `ChrootSizeManager.kt:5`, `PackageInstallRunner.kt:324` (`stageIntoGuest` chroot branch + `runSu`), `CliToolsInstaller.kt:78` (`runChrootAsync`), `LinuxCommandBuilder.kt:18`. Flavor compile will fail or force keeping class in both flavors.
- Evidence: `grep -rn "RootShell\|ChrootCommandBuilder" app/src/main/java --include="*.kt"` shows 8 files; `app/src/play`/`app/src/full` dirs do not exist yet (`ls app/src` → `main test` only). No flavor source set strategy enumerated.
- Impact: `assemblePlayDebug` compilation failure; worker must rediscover full dependency closure.
- Required planner change: Enumerate full closure to move or gate. Either (a) move `RootShell.kt` + `ChrootCommandBuilder.kt` + `ChrootProcessManager.kt` + `ChrootSizeManager.kt` + chroot assets `scripts/chroot/` into `src/full/` and stub them in `src/play/` with `throw UnsupportedOperationException`, or (b) keep single source set and guard every entry with `BuildConfig.PLAY_STORE_BUILD`. Choose one model and list exact files in File-Level Change Map.

#### [CRITICAL] PLAY_STORE_BUILD runtime guard scope incomplete
- Location: `docs/plans/play-compliance-hardening-plan.md:60-61` (guard MainActivity + RootShellService)
- Problem: `LinuxCommandBuilder.currentMethod` is mutable global (`LinuxCommandBuilder.kt:8 var currentMethod = "proot"`), persisted via `prefs.getString("linux_method")` at `MainActivity.kt:598`, `OnboardingActivity.kt` etc. Plan only guards "sessions dispatch (MainActivity terminal creation + RootShellService entry)" for `chroot|shell-root|root-shell|su`. Missed paths: `CliToolsInstaller.runChrootAsync:32`, `ProjectManager.kt:19`, `PackageInstallRunner.stageIntoGuest:method=="chroot"` + `buildRootExec`, `ChrootCommandBuilder.buildInteractive/build`, `AppUpgrade.kt` chroot migrations. Prefs can still restore `chroot` on Play build.
- Evidence: `grep -n "currentMethod" MainActivity.kt` 40+ hits; `grep -rn currentMethod app/src/main/java` shows ProjectManager, LinuxCommandBuilder, etc.
- Impact: Play variant remains able to invoke su/chroot via Settings restore or direct API calls; Play policy violation (root privilege escalation) not fully severed.
- Required planner change: Add exhaustive guard list + prefs migration: on Play, `LinuxCommandBuilder.build()` throws if `method=="chroot"`, `ChrootCommandBuilder.build()` immediate throw, `CliToolsInstaller` early return, and startup prefs coercion: if `BuildConfig.PLAY_STORE_BUILD && method=="chroot"` force `proot` and clear `ai_cli_tools_provisioned_chroot` key. Enumerate in plan §Phase2.

#### [CRITICAL] Marketplace arbitrary-code severance leaves residual exec/ download surface
- Location: `docs/plans/play-compliance-hardening-plan.md:73-75`, `MarketplaceClient.kt:49-72`, `PackageInstallRunner.kt:324-447`, `MarketplaceIcons.kt:48`
- Problem: Plan deletes `MarketplaceClient.downloadTo(install.sh/uninstall.sh)` but `PackageInstallRunner.buildGuestBatch:324` still does `/bin/bash $GUEST_STAGE/$id/install.sh` over staged files, and `stageIntoGuest` + `buildRootExec` remain in main source set. Without flavor split, Play build can still stage+exec any bash script dropped into `$GUEST_STAGE`. Also `MarketplaceIcons.kt:48 downloadTo(iconUrl)` still fetches arbitrary remote URLs (catalog `icon` field `o.optString("icon")` at `MarketplaceClient.kt:158` unsanitized) and writes to cache.
- Evidence: `PackageInstallRunner.kt:339-343` `lines += "/bin/bash $GUEST_STAGE/$id/$script"`; `MarketplaceClient.kt:68 downloadTo` + `dest.setExecutable(true)`; `MarketplaceIcons.kt:71 downloadTo` HTTP GET without allowlist.
- Impact: Play review flags remote-code execution; plan's acceptance `grep for downloadTo(*.sh)+setExecutable+bash` in Play source set would still pass if file remains in main, but runtime path exists.
- Required planner change: Specify flavor split: `src/play` provides `MarketplaceClient` (metadata only, HTTPS + size cap + schema allowlist) and `PackageInstallRunner` with `InstallerRecipe` executor that runs ONLY bundled recipes from `src/play/assets/marketplace/<id>/` via fixed `apt/npm/pip` allowlist; `src/full` retains legacy runner. Add `MarketplaceIcons` hardening: HTTPS-only, size cap (<200KB), MIME check, no `setExecutable`. Update acceptance grep to scan merged Play sources (`src/main` + `src/play` with full excluded).

#### [MAJOR] Signing config guarded incorrectly vs proot-parity SSOT
- Location: `docs/plans/play-compliance-hardening-plan.md:47`, `app/build.gradle.kts:32-39`
- Problem: Plan proposes `providers.gradleProperty("NATIVECODE_KEYSTORE|STORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD")` guarded so missing props fail release only. Current literals `storePassword = "REDACTED"` at `app/build.gradle.kts:35,37`, `storeFile = file("/home/user/repos/keys/nativecode-release.jks")`. Simple `providers.get()` without `hasReleaseSigning` check breaks `assembleDebug` configuration phase. Naming mismatches proot-parity plan's `nativecode.keystore.path` / `NATIVECODE_KEYSTORE` env fallback and `GradleException` only on Release tasks. Plan also omits `storeFile` handling.
- Evidence: `app/build.gradle.kts:32-39` vs `docs/plans/proot-parity-v2-plan.md:61-85` hasReleaseSigning pattern with `orElse(environmentVariable)`.
- Impact: After edit, `assemblePlayDebug`/`assembleFullDebug` fail at configuration; CI blocked. Also named properties not documented for CI.
- Required planner change: Adopt `hasReleaseSigning = releaseKeystore.isPresent && releaseKeystorePwd.isPresent` pattern, `signingConfigs.create("release")` conditionally, `buildTypes.release` throws `GradleException("Release signing credentials required...")` only when `hasReleaseSigning==false` and `gradle.startParameter.taskNames.any{it.contains("Release")}` (or inside `buildTypes.release` guarded). Document property names `nativecode.keystore.path/password/alias/key.password` + env `NATIVECODE_KEYSTORE*` aligned with proot-parity plan. Add `keys/` + `*.jks` to `.gitignore`.

#### [MAJOR] .gitignore and rotation checklist incomplete
- Location: `docs/plans/play-compliance-hardening-plan.md:48`, `.gitignore:1-30`
- Problem: Plan adds `*.jks, *.keystore, keystore.properties, signing.properties` but not `keys/` (where current `nativecode-release.jks` path hints) nor `**/keys/`, and doesn't address git history taint (`git log --diff-filter=D` + `git grep storePassword`). Also step 3 says "generate new upload key, enable Play App Signing" without distinguishing upload key vs app-signing key (Play App Signing manages app-signing key; upload key is what leaked). Dangerous to rotate app-signing key casually.
- Evidence: `.gitignore` lacks `*.jks`/`keys/`; `app/build.gradle.kts:34` path `/home/user/repos/keys/nativecode-release.jks` (outside repo but pattern still needed); `docs/plans/proot-parity-v2-plan.md:90` correctly distinguishes upload-key reset vs app-signing key.
- Impact: Future accidental commit; rotation does wrong key, causing Play signing mismatch.
- Required planner change: Add `keys/`, `**/*.jks`, `**/*.keystore` to `.gitignore`; add checklist: `git log --all --diff-filter=D -- app/build.gradle.kts` audit, confirm `git grep -i REDACTED` empty, use Play Console "Request upload key reset" (not app-signing key replacement), store new `NATIVECODE_KEYSTORE_BASE64` in GitHub Actions, document in `docs/setup/signing.md`.

#### [MAJOR] Manifest overlay missing xmlns:tools and termux-x11 reintroduction risk
- Location: `docs/plans/play-compliance-hardening-plan.md:59`, `app/src/main/AndroidManifest.xml:1-9`, `termux-x11/src/main/AndroidManifest.xml:1-10`
- Problem: Main manifest `AndroidManifest.xml:1` declares only `xmlns:android`; overlay `app/src/play/AndroidManifest.xml` snippet with `tools:node="remove"` requires `xmlns:tools="http://schemas.android.com/tools"` declaration, not mentioned. Also plan lists prohibited perms but `termux-x11` manifest currently clean (`FOREGROUND_SERVICE, INTERNET, POST_NOTIFICATIONS`) yet merged manifest could reintroduce perms from transitive `termux-app` dependency (which has `MANAGE_EXTERNAL_STORAGE` at `termux-app/app/src/main/AndroidManifest.xml:26` and `REQUEST_INSTALL_PACKAGES:35` though not currently implemented as project dep). R1 mitigation `tools:node="remove"` pattern not fully specified per-perm.
- Evidence: `app/src/main/AndroidManifest.xml:1-9` vs `termux-x11/src/main/AndroidManifest.xml:1-9`; `grep ACCESS_SUPERUSER app/build/intermediates/merged_manifest` shows present.
- Impact: `processPlayReleaseMainManifest` still contains `ACCESS_SUPERUSER`; Play pre-launch flags it.
- Required planner change: Provide full overlay header example with `xmlns:tools`, and per-perm `tools:node="remove"` entries for all 9 prohibited perms from §25. Add Phase7 step inspecting `bundletool dump manifest` + `aapt dump xmltree`.

#### [MAJOR] In-app AI report contract and queue storage undefined
- Location: `docs/plans/play-compliance-hardening-plan.md:82,112,115`, `ReportMailHelper.kt:73`, `cliauth/`
- Problem: Phase 5 proposes "POST JSON to backend endpoint; offline → queue locally with Retry/Delete; mailto demoted to secondary". No endpoint URL, auth, payload schema (tool/category/description/diagnostics opt-in, no secrets), storage choice (Room/DataStore/file), retry policy, size cap, privacy, or acceptance test harness. Existing code only has `ReportMailHelper.openMailto` via `ACTION_SENDTO`. `CredentialClearService.kt` exists but no consent keys `ai_consent_<vendor>` exist (only `AiCliProvisionState.KEY_PROVISIONED_*` + `enable_ai_cli_install`).
- Evidence: `ReportMailHelper.kt:73-95 openMailto` mailto only; `grep ai_consent app/src/main/java` → 0 hits; `AiCliProvisionState.kt:20` keys are `ai_cli_tools_provisioned*`.
- Impact: Worker must invent backend contract; `acceptance: report submitted fully in-app when backend reachable` untestable; queue could store PII without cap.
- Required planner change: Define contract: placeholder endpoint (e.g., `https://api.nativecode.app/v1/report`), HTTPS-only, payload `{toolId,category,description (<=3500), method, appVersion, diagnosticsOptIn}` no API keys, storage `filesDir/report-queue.json` with 10-item cap, WorkManager retry exponential, and instrumentation test mock via `MockWebServer`. Clarify `ai_consent_<vendor>` SharedPreferences `nativecode_prefs` boolean per `CliToolCatalog`.

#### [MINOR] File-Level Change Map omits critical files
- Location: `docs/plans/play-compliance-hardening-plan.md:100-117`
- Problem: Map lists 12 rows but omits `bootstrap.tar` size, `ChrootProcessManager.kt`, `ChrootSizeManager.kt`, `MarketplaceIcons.kt`, `MarketplacePaths.kt`, `LinuxCommandBuilder.kt`, `HostCommandBuilder.kt`, `MainActivity.kt` 20k-line edits fragility note incomplete, `.gitignore`, `termux-x11` audit.
- Evidence: `grep -rn ChrootProcessManager app/src/main/java` etc.
- Impact: Incomplete ownership; worker misses touch points.
- Required planner change: Expand map with those rows and rationale Phase2/4/7.

#### [MINOR] Citation line drift and missing proguard artifact
- Location: `docs/plans/play-compliance-hardening-plan.md:9-14`, `app/build.gradle.kts:44-47`
- Problem: `OnboardingActivity.kt:3290-3298` actual deploy at ~3285-3310 and `MainActivity.kt:12940-12949` at ~12955; `flux_install.sh:14` actual at 16; `chroot/setup_debian13_chroot.sh:15-16` correct. `app/proguard-rules.pro` referenced at `app/build.gradle.kts:47` but file missing (`cat` → not found) so R8 enable later fails.
- Evidence: `sed -n 3280,3315p OnboardingActivity.kt`, `ls app/proguard-rules.pro` missing.
- Impact: Review friction, future minify step broken.
- Required planner change: Fix citations to ranges (`OnboardingActivity.kt:~3294`, `MainActivity.kt:~12955`, `flux_install.sh:14-17`) and add task to create `app/proguard-rules.pro` with keep rules for `libproot`, `termux-shared` before enabling R8.

#### [MINOR] Flavor asset merge semantics not explicit
- Location: `docs/plans/play-compliance-hardening-plan.md:66`
- Problem: Says "Play source set ships its own `assets/scripts/flux_install.sh` with NO ROOTFS_URL", Full keeps via `src/full/assets/scripts/`. Android Gradle merges `src/main/assets` into both flavors unless shadowed by same relative path. If worker creates `src/play/assets/scripts/flux_install.sh` correctly it shadows, but `src/full/assets/scripts/flux_install.sh` must also shadow `src/main` version or Play will still include `src/main` fallback. Not clarified.
- Evidence: `ls app/src/main/assets/scripts/flux_install.sh` exists; `ls app/src` shows no flavor dirs.
- Impact: Play APK still contains fallback script with curl URL, compliance grep fails.
- Required planner change: State shadow requirement: `src/play/assets/scripts/flux_install.sh` and `src/full/assets/scripts/flux_install.sh` must each be at `assets/scripts/flux_install.sh` relative, overriding `src/main`. Add CI check: `unzip -l app-play-release.apk | grep flux_install` and `grep -R FLUX_ROOTFS_URL` on extracted flavor assets, not just source.

#### [MINOR] InstallerRecipe catalog allowlist and icon hardening gap
- Location: `docs/plans/play-compliance-hardening-plan.md:73-75`, `MarketplaceModels.kt:20-45`
- Problem: Sealed `InstallerRecipe` (`Apt/Npm/Pip/BuiltInScript`) proposed but catalog parsing at `MarketplaceClient.parseCatalog:100-160` currently accepts any `script_path` string without allowlist; icons from `catalog.json` `icon` field not validated; HTTPS/size cap not noted for Play.
- Evidence: `MarketplaceModels.kt` has no InstallerRecipe; `MarketplaceClient.kt:158 icon = o.optString("icon")`.
- Impact: Malformed catalog could inject unexpected `script_path` or large icon fetch; Play catalog fetch still network.
- Required planner change: Define `InstallerRecipe` enum + validation: `env` allowlist `["proot"]` for Play, `size_hint_mb` cap, catalog `schema_version` check, `min_app_version` gate, and icon: HTTPS-only, host allowlist `raw.githubusercontent.com`, size cap 200KB, no exec.

#### [SUGGESTION] Add CI size and secret gates precisely
- Location: `docs/plans/play-compliance-hardening-plan.md:96,127-137`
- Problem: `scripts/check_play_compliance.sh` bullet mentions 5 checks but not AAB size or manifest dump diff.
- Evidence: `ls scripts/` has no `check_play_compliance.sh` yet.
- Impact: Oversize passes CI.
- Required planner change: Gate also: `aab_size_bytes < 150MB`, `bundletool dump manifest` prohibited perms, `grep -R storePassword` zero, and `unzip -l` asset check.

#### [SUGGESTION] Document flavor divergence and testing matrix explicitly
- Location: `docs/plans/play-compliance-hardening-plan.md:96,122-124`
- Problem: Testing strategy lists device matrix but not flavor matrix (`assemblePlayDebug` vs `assembleFullDebug` manifest diff, airplane-mode provision).
- Evidence: Testing Strategy:122
- Impact: Manual test misses Play-only regression.
- Required planner change: Add build gate diff: `diff <(aapt dump xml) play vs full` and explicit airplane-mode provision test on Play APK.

## Next Agent: Planner
## Next Action: Revise `docs/plans/play-compliance-hardening-plan.md` to address CRITICAL 1–4 + MAJOR 5-8 (mandatory), fix MINOR citations/map, incorporate SUGGESTIONS, then re-request plan-reviewer PASS 2. Do not start worker implementation until REVISE cleared.
