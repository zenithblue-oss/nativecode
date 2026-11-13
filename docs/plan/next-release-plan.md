# NativeCode 1.8.0 — Implementation Plan

Date: 2026-11-11. Branch: `feature/monetisation-pr1` (app 1.7.1, versionCode 16). All work stays on this branch.
Target: **one release, 1.8.0 (versionCode 17)**, containing everything below.
Status: plan only. No code, rootfs, commit or device was touched while writing it.

Conventions:
- Paths are relative to `app/src/main/java/com/zenithblue/nativecode/` unless they start with `tools/`, `docs/`, `app/` or `nativecode_`. `MA` means `MainActivity.kt` (22,903 lines).
- Test devices: **OP13R** = OnePlus 13R (Adreno 750), **X6P** = Poco X6 Pro (Mali-G615 MC6, Dimensity 8300).
- **UNVERIFIED** = not confirmed in a primary source or on a device. Section 26 collects them. **ESTIMATE** = a size or effort guess that must be measured.
- Effort: S = up to 2 days, M = 3–6 days, L = more than a week, for one developer including device testing.
- Tiers: Free = opencode only, 1 AI session, 30 min/day desktop. Pro = everything.

## 0. Summary

### 0.1 Owner decisions and how the plan answers them

| # | Item | Plan | Effort |
|---|------|------|--------|
| 1 | REA | One `INSTALL` skill entry the user copies. Nothing baked, no installer. | S |
| 2 | CLI login | One native page per CLI (nine tools). Host-side detection, login UI only when signed out, status and logout when signed in. | M |
| 3 | CLI update + Hermes | opencode 2.2.7 from `@opencode/cli`; all other CLIs to latest and pinned; Hermes 0.23.1 as a Pro tool; rootfs rebuilt; delta for existing users through small Play asset packs. **Deviation to confirm:** Hermes and the Pro CLIs cannot fit inside the 1.5 GB runtime pack, so they ship as separate Play packs that are unpacked into the rootfs (section 3.4). | L |
| 4 | Desktop everywhere | Shared controller, desktop pill, notification Open/Stop, free minutes left. As previously planned. | S–M |
| 5 | Top bar + tool rail | Two slim rows (40 dp + 32 dp), no dropdown, title on its own row with middle ellipsis. | M |
| 6 | Extra keys | Two compact rows of eight keys (38 dp), paged, built on the vendored Termux `ExtraKeysView`. | M |
| 7 | PRoot speed | Ship the two flag changes measured in `termux-lib` plus git settings now; the U11 "rootfd" engine (+14.9% measured) as an opt-in experimental switch. | M (+M stretch) |
| 8 | GPU | PanVK v26.3.1 with `PANVK_BC_MODE=lowmem` set explicitly; Turnip `mesa-27.0.0-devel-20261025-r2`. X6P Phosh + Vulkan + zink test is a hard gate. | M |
| 9 | Agent chat (beta) | Chat entry next to the terminal launcher for three CLIs: opencode via ACP, Codex via `codex app-server`, Claude via stream-json. Terminal fallback always present. | L |
| 10 | Git history | One streamed `git log` across all refs, read lazily as the user scrolls, streaming lane assignment, `RecyclerView`. Checkout and create-branch included (one command each). | M–L |

### 0.2 Additional features

| # | Feature | Plan | Effort | Tier |
|---|---------|------|--------|------|
| 11 | Pro free trial | Play Console offer + prefer the zero-price offer token in `BillingRepository.subOffer`. | S | — |
| 12 | Dev-server preview | In-app WebView page; ports found from terminal output and a connect probe. | M | Free |
| 13 | Agent notifications | Existing agent bridge. No new work (one open question). | — | Free |
| 14 | Firebase | Crashlytics (crash, ANR, native) + Analytics events, both off until consent. Data safety form and privacy policy updated. | M | Free |
| 15 | Backup / restore | Guest `tar --zstd` streamed to a SAF document with sha256; staged restore. | M | Free |
| 16 | Code editor | Sora Editor (LGPL-2.1, about 2.4 MB) opened from the file viewer. | M | Free |
| 17 | SSH | `openssh` in the rootfs; server page (port 8022, key auth) and a saved-hosts client list. | M | Client Free, server Pro |
| 18 | Usage dashboard | Pinned `ccusage` binary in the guest, `--offline --json`, rendered natively. | M | opencode Free, other CLIs Pro |
| 19 | Widget + QS tile | Quick Settings tile for the desktop; one 2×1 widget (Agent, Desktop). | S–M | Free (limits apply) |
| 20 | Localisation | Extract about 700–1,900 hard-coded strings to resources, then 12 languages (no Hindi). | L | Free |

### 0.3 Honest size of this release
Summed effort is roughly 16–20 developer-weeks. One release is the owner's decision; the plan therefore orders the work so that the release can be cut at any point after chunk 8 with every finished chunk shippable, and marks the three items most likely to slip (Arabic/RTL in item 20, Claude inline approvals in item 9, the U11 engine in item 7).

---

## 1. REA skill

**Current state.** A skill is `Skill(id, title, summary, source, body)` in categories `INBUILT`, `PROJECT`, `INSTALL` (`skills/SkillsCatalog.kt:9-31`); entries live in `skills/AdditionalSkillTexts.kt` (6,238 lines). `SkillsPage.kt:252-395` renders the body and offers COPY; `SkillsPickerSheet.kt:229-292` copies the raw body. Skills are ungated.

**Facts.** https://github.com/morluto/rea — MIT, npm `rea-agents` 6.8.0 (2026-10-25), an MCP server plus CLI plus skill. Node `^22.23.4 || ^24.22.1 || >=26.12.1`. Needs a procps-compatible `ps`. `npx rea-agents setup --all-detected --yes` writes an MCP entry per detected client and backs each config up as `.rea.backup`. No Hopper on aarch64 Linux; Ghidra optional and manual; JS/Electron analysis needs no engine; APK analysis needs JADX and Apktool. REA is not a sandbox.

**Design.** One `INSTALL` skill, id `install-rea`, title "REA — Reverse Engineer Anything". The body is markdown the user copies into an agent. It contains, in order:
1. What REA is and what works on a phone (JS/Electron, .NET static, APK after the Android tools skill; no Hopper).
2. Pre-checks: `node -v` satisfies the range; `command -v ps` (else `apk add procps`).
3. Install, from `$HOME`: `npm i -g rea-agents@6.8.0 && rea-agents setup --all-detected --yes && rea doctor --json`.
4. How to use it from each agent, and how to undo (`.rea.backup` files, `npm rm -g rea-agents`).
5. A warning that analysed binaries are untrusted input.

No second skill, no install button, no baking. `procps` is added to the base package list because the rebuild is happening anyway.

**Files.** `skills/AdditionalSkillTexts.kt`, `docs/skills/NATIVECODE_SKILL_CATALOG.md`, `tools/rootfs/packages-alpaquita-base.txt`.

**Gating.** Free.

**Risks.** REA releases weekly, so the pinned version ages; the skill says how to bump it. Whether its PTY prebuild covers linux-arm64 glibc is UNVERIFIED.

**Device test.** Both devices: copy the skill into opencode, let it run, confirm `rea doctor --json` passes and the MCP server is listed in opencode. Record install time and disk use.

**Effort.** S.

---

## 2. CLI login pages

**Current state.**
- Catalog `cliauth/CliToolCatalog.kt:17-111` (claude, codex, qwen, opencode, agy, grok, kiro-cli). No Hermes.
- Detection `cliauth/CliGuestCommands.kt:71-167`: file checks mixed with status commands run in the guest (a proot spawn each).
- Login `CliGuestCommands.kt:172-201`, `CliAuthService.kt:318-418` (867 lines): Codex device auth, Claude `setup-token`, Kiro login, OpenCode `auth login`, agy and grok by launching the tool, API-key forms for Qwen and Grok. Guest commands run with `BROWSER=echo` (`CliAuthService.kt:269-277`).
- UI in `MA`: `buildCliAuthPage` `:17339`, `paintCliAuthList` `:18883`, `buildCliToolCard` `:18914`, `startCliToolLogin` `:19001`, `performCliToolLogout` `:19017`, `showCliApiKeyDialog` `:19026`, `showCliAuthOverlay` `:19071`. The copy at `MA:17395-17417` still says the page does not authenticate tools.
- GitHub is separate and already native: `github/GitHubCliService.kt:278-760`, UI at `MA:16651` and `:17150`, gated by `GITHUB_LOGIN`.

**Per-CLI facts.**

| CLI (1.8.0 pin) | Signed-in check (host file, no proot) | Verify command | Login | Logout |
|---|---|---|---|---|
| opencode 2.2.7 | auth file; path on 2.x UNVERIFIED — resolve once with `opencode debug paths data` and cache | `opencode auth list` (UNVERIFIED on 2.x) | `opencode auth login` (wizard) → terminal sheet | `opencode auth logout` (UNVERIFIED) |
| codex 0.167.1 | `~/.codex/auth.json` | `codex login status` (exit 0) | `codex login --device-auth`; API key on stdin | `codex logout` |
| claude 2.1.322 | `~/.claude/.credentials.json` | `claude auth status` (JSON) | `claude auth login` (URL, paste code) or `setup-token`; API key | `claude auth logout` |
| agy 1.5.5 | under `~/.gemini/antigravity-cli/` (may use a keyring; UNVERIFIED) | none documented | prints URL, paste code | UNVERIFIED |
| qwen 0.27.2 | `~/.qwen/settings.json` has a key | none | API key only (OAuth free tier ended 2026-04-15) | remove key |
| grok 1.0.77 | path UNVERIFIED | parse `grok models` | `grok login --device-auth`; `XAI_API_KEY` | `grok logout` |
| kiro-cli 3.2.0 | path UNVERIFIED | `kiro-cli whoami --format json` | `kiro-cli login --use-device-flow`; `KIRO_API_KEY` | `kiro-cli logout` |
| hermes 0.23.1 | `~/.hermes/auth.json`, `~/.hermes/.env` | `hermes auth list` (exit codes UNVERIFIED) | `hermes auth add <provider> --type oauth`; API keys | `hermes auth logout <provider>` |
| gh 2.103.3 | `~/.config/gh/hosts.yml` | `gh auth status` | existing native device flow | `gh auth logout` |

**Design.**
The page becomes a list of nine rows; tapping a row opens that tool's own page. Each tool page has exactly two states:

```
 SIGNED OUT                          SIGNED IN
┌────────────────────────────┐      ┌────────────────────────────┐
│ ‹  Codex                   │      │ ‹  Codex                   │
│ Not signed in              │      │ ● Signed in                │
│                            │      │   name@example.com         │
│ [ Sign in with browser ]   │      │   Plan: Plus               │
│ [ Use an API key ]         │      │   Checked 2 min ago  ↻     │
│ Open login in terminal     │      │ [ Sign out ]               │
└────────────────────────────┘      └────────────────────────────┘
```
The login controls are not built at all when the tool is signed in; the account block is not built when signed out. `NOT INSTALLED` (Pro pack missing) shows a single "Download" action instead (section 3.4).

1. **Detection in two tiers.**
   - Tier 1, on page open, all nine tools, no proot: Kotlin reads the credential file on the host path (`<rootfs>/home/flux/…`, via `terminal/ProjectPathResolver.kt:54`). Result `LIKELY_SIGNED_IN` or `SIGNED_OUT`. Runs off the main thread, finishes in milliseconds.
   - Tier 2, when a tool page opens, on ↻, and after login or logout: the verify command in the guest. Result `SIGNED_IN(account)`, `SIGNED_OUT`, `EXPIRED`, `UNKNOWN`. Cached per tool; invalidated on login, logout and CLI update.
2. **Login flow type is data on the catalog entry**, not code per tool: `DEVICE_CODE` (codex, grok, kiro, hermes), `URL_PASTE_CODE` (claude, agy), `API_KEY`, `NATIVE` (gh — calls the existing `startGithubConnect`), `TERMINAL` (opencode wizard, and the fallback for any parse failure).
   - `DEVICE_CODE`: run the command, parse URL and code from stdout, show the code large with COPY and OPEN BROWSER, end when the command exits.
   - `URL_PASTE_CODE`: show the URL, open the browser, a text field writes the pasted code to stdin.
   - `API_KEY`: the existing dialog; keys go to the tool's own config with mode 0600, never to SharedPreferences, logs or analytics.
3. Logout asks for confirmation. Every parser returns `UNKNOWN` on unexpected output, and `UNKNOWN` shows "Open in terminal".
4. All new UI goes in new files; `MA` keeps only the navigation call.

**Files.** New: `cliauth/ui/CliAuthListPage.kt`, `cliauth/ui/CliToolAuthPage.kt`, `cliauth/CliCredentialProbe.kt` (tier 1). Changed: `cliauth/CliToolCatalog.kt`, `CliGuestCommands.kt`, `CliAuthService.kt`; `MA:17339-17429` and `MA:18878-19100` shrink to calls into the new files.

**Gating.** The list and opencode's page are Free. Opening a Pro tool's page as a free user shows the existing upsell (`FeatureGate.canLaunchAiTool`). GitHub keeps `GITHUB_LOGIN`.

**Risks.** Status output changes between CLI versions (mitigated by `UNKNOWN`). A credential file can exist with an expired token, which is why tier 1 says "likely". agy may want a keyring that does not exist under PRoot.

**Device test.** OP13R: for each of nine tools sign in, kill the app, reopen, check state; sign out; check state. Codex and Claude again on X6P. Rotate mid-flow. Deny the browser hand-off and use COPY URL. Free user: Pro tools show the upsell.

**Effort.** M. Depends on item 3 for Hermes and opencode 2.x paths.

---

## 3. Update CLIs, add Hermes, rebuild the rootfs, update existing users

### 3.1 Current state
- Pins in `tools/rootfs/versions.env`: `OPENCODE_VERSION=1.18.27`, `CODEX_VERSION=0.153.2`, `QWEN_VERSION=0.23.0`, `GH_VERSION=2.67.0`, `NODE_MAJOR=24`. agy, claude, grok and kiro come from unpinned `curl | bash` installers (`tools/rootfs/build_alpaquita_rootfs.sh:325-329`).
- Install locations: npm prefix is `$HOME/.local` (`build_alpaquita_rootfs.sh:318-320`), so every CLI except `gh` lives under `/home/flux` (`~/.local/lib/node_modules`, `~/.local/bin`, `~/.local/share/claude/versions`, `~/.grok/bin`). `gh` is in `/usr/local/bin` (`:271`). PATH puts `/home/flux/.local/bin` first (`terminal/ProotCommandBuilder.kt:117`).
- Runtime installer `app/src/main/assets/scripts/setup_cli_tools.sh:357-499` installs the same tools unpinned; `:428-447` is the prebuilt mode.
- Delivery: two on-demand asset packs, `nativecode_runtime` (`alpaquita_rootfs.tar.zst`, 1,356,467,533 bytes) and `nativecode_android_dev` (69,451,708 bytes) (`settings.gradle.kts:11-16`, `app/build.gradle.kts:120`). `onboarding/PlayAssetDeliveryManager.kt:15-101` downloads; `onboarding/RootfsSource.kt:49` pins sha256 `edeec26a…0698`, which **matches** the shipped archive. `versions.env` `NATIVECODE_ROOTFS_SHA256=832c4cc0…` is stale.
- Existing users: `AppUpgrade.kt:41-143` re-stages host scripts on a versionCode change and never touches the rootfs. `PanvkDriverUpdate.kt` (106 lines) is the only in-place guest update: sha256 compare, backup, stage, verify, rename.
- Rootfs glibc is **2.43** (read from `libc.so.6` in the shipped archive). This satisfies kiro-cli gnu (2.39), Turnip and PanVK (2.38).
- There is no host-side tar or zstd in Kotlin or `jniLibs`; archives are unpacked by scripts.
- Launcher list: `cliauth/ToolLauncherCatalog.kt:21-30` (opencode free; codex, agy, claude-code, qwen-code, grok, kiro paid).

### 3.2 Versions for 1.8.0

| Component | Bundled | 1.8.0 pin | Artifact (linux-arm64 glibc) | Integrity |
|---|---|---|---|---|
| opencode | 1.18.27 (`opencode-ai`) | **2.2.7** | `opencode-linux-arm64.tar.gz`, 120,326,654 B, from `anomalyco/opencode` release, or npm `@opencode/cli@2.2.7` (platform package unpacks to 253,873,383 B) | sha256 `c1682057f0815cb2abdd6e00305f1146ebaf1c167f0335cdb74ddc80008120e7` |
| codex | 0.153.2 | 0.167.1 | npm `@openai/codex@0.167.1` (platform package via optionalDependencies) | npm integrity |
| claude code | unpinned | 2.1.322 | `downloads.claude.ai/claude-code-releases/2.1.322/…`, 341,501,368 B | sha256 `f220ca50…c1fd` |
| qwen-code | 0.23.0 | 0.27.2 | npm, 159 MB unpacked | npm integrity |
| agy | unpinned | 1.5.5 | `cli_linux_arm64.tar.gz`, 70,187,550 B | sha512 in manifest |
| grok | unpinned | 1.0.77 | `https://x.ai/cli/grok-1.0.77-linux-aarch64`, 171,935,112 B | **none published** — record our own sha256 at first fetch and pin it |
| kiro-cli | unpinned | 3.2.0 | `kirocli-aarch64-linux.tar.zst`, 124,890,181 B | sha256 `bff067a4…d65c` |
| hermes-agent | — | 0.23.1 | installer + `uv`, 355 locked Python packages, Python 3.14 | `uv.lock` hashes |
| gh | 2.67.0 | 2.103.3 | `gh_2.103.3_linux_arm64.tar.gz`, 13,661,485 B | release checksums file |
| ccusage (item 18) | — | 20.0.49 | `@ccusage/ccusage-linux-arm64` | npm integrity |
| Node | 24.x | stay on 24 LTS | apk | — |
| Kotlin / Gradle / build-tools / cmdline-tools | 2.1.0 / 9.2.1 / 36.1.0 / 15859902 | 2.4.21 / 9.9.1 / 37.0.0 / latest | — | Platform stays 36, NDK stays r29, JDK stays 21 |

**opencode 2.x specifics.**
- The current install path cannot produce 2.x: `opencode.ai/install` and npm `opencode-ai` are 1.x only. 2.x installs from `https://opencode.ai/v2/install` (to `~/.opencode/bin`, `--version X` to pin) or npm `@opencode/cli`. The plan uses the **release tarball with the sha256 above**, unpacked to a fixed system path; no installer script runs at build time.
- 2.x starts one shared per-user background service by default; `opencode service set disabled true` or `--standalone` turns it off. Under PRoot with `--kill-on-exit` a lingering daemon is a liability, so the guest profile disables the service unless the spike shows it is needed.
- Config is `opencode.jsonc`; `opencode debug paths` prints real paths. The 2.2.x notes list no user-facing CLI or config break (permission rules are auto-converted in 2.2.3), but the full bodies were not read.
- UNVERIFIED on 2.x: auth file path, `auth list|logout`, self-update switch.

**Hermes specifics.** https://github.com/NousResearch/hermes-agent, MIT, v0.23.1 (2026-11-10). Binary `hermes` (also `hermes-acp`). Config in `~/.hermes`. The installer takes `--dir`, `--hermes-home`, `--skip-browser`, `--skip-setup`; `HERMES_RUNTIME_DIR` relocates its tool downloads. It refuses to run when `TERMUX_VERSION` is set or `PREFIX` contains `com.termux/files/usr`, so both are unset in the build chroot. Installed size is an ESTIMATE of 0.6–1.2 GB (250–450 MB compressed) because the lock set includes numpy, onnxruntime, faster-whisper and av.

### 3.3 Why the layout has to change
Play limits (support.google.com/googleplay/android-developer/answer/9859372, compressed download size): 1.5 GB per asset pack; 4 GB for all modules plus install-time packs; 30 GB cumulative for on-demand and fast-follow packs; 500 MB base module. The runtime pack is at 1,356 MB, which leaves about 143 MB if the cap is decimal (254 MB if binary; which one is UNVERIFIED). Hermes alone exceeds that, and the newer CLI binaries are larger than the old ones. So "Hermes inside the rootfs archive" is not possible without moving something else out.

The delta for existing users has the same problem from the other side: opencode + six Pro CLIs + Hermes + drivers is about 1.1–1.4 GB of already-compressed binaries. That is not a small update.

### 3.4 Design: component packs (recommended)

One mechanism serves new installs and existing users. Every CLI and driver becomes a **component**: a `tar.zst` built at rootfs-build time, unpacked into a versioned system directory in the guest, with a launcher in `/usr/local/bin`.

| Asset pack (all on-demand) | Contents | Size | Who downloads it |
|---|---|---|---|
| `nativecode_runtime` (rebuilt) | Alpaquita base, desktop, dev tools, **opencode 2.2.7**, gh, Turnip, PanVK, openssh, procps, ccusage, gitconfig, overlay scripts. Pro CLIs removed. | target ≤ 1,300 MB (ESTIMATE; removing six CLIs frees more than opencode and the drivers add) | every new install |
| `nativecode_update_core` | components: `opencode`, `gh`, `panvk`, `turnip`, `ccusage`, `overlay` (scripts, gitconfig, profile.d) | about 180 MB (120 + 14 + 34 + 12 + small) | existing users only, once |
| `nativecode_clis` | components: `codex`, `claude`, `qwen`, `agy`, `grok`, `kiro` | ESTIMATE 500–800 MB | Pro users, on first use of a Pro CLI (or "Download all" on the Components page) |
| `nativecode_hermes` | component: `hermes` (`/opt/hermes` with its Python, venv and tools) | ESTIMATE 250–450 MB | Pro users, on first Hermes launch |
| `nativecode_android_dev` | unchanged | 69 MB | as today |

Consequences worth stating plainly:
- Hermes is **pre-built and delivered by Play**, never fetched from a third-party server on the device, but it is a pack unpacked into the rootfs, not a file inside the rootfs archive. This is the one place the plan departs from the letter of decision 3; see open question 1.
- Free users never download Pro binaries. That saves them roughly 1 GB and closes the existing hole where the free opencode agent can exec `codex` or `claude` from its shell tool.
- Existing users get a 180 MB update, not a 1.3 GB one. Their old Pro CLIs keep working (most self-update already) until they choose to switch to the pinned pack.
- If the owner rejects the split, the fallback is: keep the Pro CLIs baked in `nativecode_runtime` (only if the rebuilt archive stays under the cap), with Hermes still in its own pack. The updater below is identical in both layouts.

**Guest layout.**
```
/opt/nativecode/components/<id>/<version>/…     unpacked payload
/opt/nativecode/components/<id>/current -> <version>
/opt/nativecode/components/<id>/previous -> <old version>   (kept for rollback)
/usr/local/bin/<tool>  -> ../../opt/nativecode/components/<id>/current/bin/<tool>
/opt/hermes            (Hermes only: built and run at the same absolute path, so its venv paths hold)
```
User data stays where each tool keeps it (`~/.codex`, `~/.claude`, `~/.hermes`, `~/.local/share/opencode`). The updater never writes there.

**Manifest.** Each pack carries `components.json`: `[{id, version, archive, sha256, size, unpackedSize, targetDir, bins[], smoke: ["opencode","--version"], expect: "2.2.7"}]`. The same file is compiled into the APK as the pinned source of truth, so a tampered pack cannot supply its own hashes (the rule `RootfsSource.kt:165-174` already applies to the rootfs).

**Apply sequence** (generalised from `PanvkDriverUpdate.kt:52-97`), per component:
1. Preconditions: no AI session open, no desktop running (driver components), free space ≥ 2 × `unpackedSize` + 200 MB. Otherwise refuse with a clear message.
2. Verify the archive: streaming sha256 in Kotlin against the compiled manifest. Mismatch → stop, delete nothing.
3. Unpack with the guest's own `tar --zstd` in a short proot session into `/opt/nativecode/components/<id>/.staging-<version>/`. Hard links are stored as copies at build time so the unpack does not depend on `--link2symlink`.
4. Smoke test from staging: run `smoke`, compare with `expect`.
5. Commit: rename staging → `<version>`; repoint `previous` to the old `current`; repoint `current` (rename of a temp symlink, atomic); write `/usr/local/bin` links.
6. Shadowing: the old copy in `~/.local/bin/<tool>` would win on PATH. Rename it to `<tool>.nc-1.7.bak` (not delete).
7. Post-check through the normal PATH. On failure: repoint `current` to `previous`, restore the `.bak`, report.
8. Record `{id, version, sha256, appliedAt}` in a host-side JSON state file used by the Components page.

**Rollback.** "Use previous version" on each component card repoints `current` to `previous` and restores any `.bak`. Old home-directory trees are deleted only when the user taps "Free up space" after the new version has run at least once.

**Pinning.** Every component runs with self-update off where a switch exists: qwen `general.enableAutoUpdate=false`; grok `--no-auto-update`; claude `autoUpdatesChannel`/`DISABLE_AUTOUPDATER=1` (the latter from a third-party source, UNVERIFIED); opencode, codex, agy, kiro and Hermes switches UNVERIFIED. A component in a root-owned system directory cannot overwrite itself in any case, and a CLI that nags about a newer version is acceptable.

**UI.** The Components page (today the PanVK card, `MA:5189-5260`) lists each component: installed version, available version, size, UPDATE / DOWNLOAD / USE PREVIOUS. On first launch after upgrading to 1.8.0, one prompt: "Update opencode, GitHub CLI and GPU drivers (about 180 MB)". Wi-Fi-only by default, using the waiting-for-Wi-Fi state already in `PlayAssetDeliveryManager.kt:34`.

**Things an existing install also needs that are not components.** `openssh`, `procps` and test tools are apk packages; existing users get them through `apk add` when they first open the SSH page (network needed, same as any package install today). New installs have them baked.

**Hermes in the app.** Add `hermes` to `cliauth/ToolLauncherCatalog.kt` (paid list and type map), `cliauth/CliToolCatalog.kt`, the terminal page launcher, the workspace tool sections, `onboarding/AgentConfigInstaller.kt` (shared instructions path under `~/.hermes`) and the sentinel checks. `FeatureGate.canLaunchAiTool` already denies every id except opencode; add a unit test that `hermes` is denied for free users. A Pro user who taps Hermes without the pack sees "Download Hermes (N MB)".

**Rootfs build changes.** Split `build_alpaquita_rootfs.sh` so each CLI is built into its component directory and packed separately; new `tools/rootfs/build_components.sh` writes the archives and `components.json`; `verify_rootfs.sh` checks every pinned version, the glibc floor, absence of musl binaries and pack sizes; `versions.env` gains `CLAUDE_VERSION`, `AGY_VERSION`, `GROK_VERSION`, `KIRO_VERSION`, `HERMES_VERSION`, `UV_VERSION`, `CCUSAGE_VERSION` with hashes, and its stale rootfs sha256 is replaced by the new one in the same commit as `RootfsSource.kt:49`. Python bytecode for `/opt/hermes` is precompiled with `--invalidation-mode unchecked-hash` (item 7).

### 3.5 Files
`tools/rootfs/versions.env`, `tools/rootfs/build_alpaquita_rootfs.sh`, new `tools/rootfs/build_components.sh`, `tools/rootfs/packages-alpaquita-*.txt`, `tools/rootfs/verify_rootfs.sh`, `tools/rootfs/measure_rootfs.sh`, `app/src/main/assets/scripts/setup_cli_tools.sh`, new modules `nativecode_update_core/`, `nativecode_clis/`, `nativecode_hermes/`, `settings.gradle.kts`, `app/build.gradle.kts:120`, `onboarding/PlayAssetDeliveryManager.kt`, `onboarding/RootfsSource.kt`, `onboarding/AgentConfigInstaller.kt`, new `components/ComponentManifest.kt`, `components/ComponentUpdater.kt`, `components/ui/ComponentsPage.kt` (replacing `PanvkDriverUpdate.kt`), `AppUpgrade.kt`, `cliauth/CliToolCatalog.kt`, `cliauth/ToolLauncherCatalog.kt`, `MA:5189-5260` (reduced to a call).

### 3.6 Gating
Free: opencode update, drivers, the updater. Pro: `nativecode_clis`, `nativecode_hermes`.

### 3.7 Risks
- opencode 2.x is the only free tool. A half-day spike in a scratch rootfs comes first: launch, `auth login`, `acp`, the `AgentConfigInstaller` JSONC edit, the background service under PRoot. If it fails, the release is blocked, not downgraded, because 2.x is an owner decision.
- Hermes size and start-up time under PRoot are unmeasured; the installer's relocation into `/opt/hermes` is undocumented.
- Play policy: confirm in Play Console that additional on-demand packs may carry native executables. The runtime pack already does, so refusal is unlikely, but it is unchecked.
- An interrupted apply must leave a working install: nothing outside staging changes before step 5, and step 5 is renames only.
- `--link2symlink` interaction: components are unpacked in a session that keeps the flag (item 7 "compat" profile).

### 3.8 Device test
- Fresh install, both devices: onboarding completes; `opencode --version` = 2.2.7; opencode launches and signs in; free user sees no Pro binaries on disk.
- Pro, both devices: download `nativecode_clis`, each of six tools prints its pinned version and launches; download Hermes, `hermes --version`, first prompt works; time cold start.
- Upgrade from 1.7.1 with data, both devices: core update applies; `/home` intact; old `~/.local/bin/opencode` renamed; opencode 2.x sees existing config and sessions (or the migration prompt is recorded).
- Failure cases: corrupted archive rejected; airplane mode mid-download; kill the app during step 3 and during step 5; low storage; update while an AI session is open is refused; USE PREVIOUS works.

### 3.9 Effort
L. Blocks items 2, 8, 9, 17, 18.

---

## 4. Desktop start / view / stop from anywhere

**Current state.** Start and stop only in the Settings card `buildGuiLaunchCard` (`MA:9506`), gate at `MA:9608` (`FeatureGate.canStartXfce`). View: top-bar `displayBtn` (`MA:1861`), visible only once the desktop has output (`MA:14917`, `:15072`). Budget: `entitlements/XfceBudget.kt:17-38`, `FeatureLimits.kt:10` (30 minutes), lifecycle calls at `MA:6003-6013`, `:14865`, `:14950`, `:15027`, `:15063`. A foreground service with an ongoing notification already runs while the desktop is up (`BackgroundService.kt:16-84`, acquired `MA:14942`, released `MA:15026`). `openGuiLogPage()` has no caller.

**Design.**
1. **`desktop/DesktopController.kt`** (new): `start()`, `stop()`, `view()`, `state` (OFF, STARTING, RUNNING, BUDGET_OUT), `remainingMs`. The gate and the budget calls move inside it, so no entry point can bypass the 30-minute limit. This is a move out of `MA`, not a rewrite. Every entry point — Settings card, pill, notification, QS tile, widget — calls only the controller.
2. **Desktop pill** in the workspace tool rail (item 5) and in the app top bar where `displayBtn` is:
```
 off          [ ▢ Desktop ]     tap → start (gate), then view
 starting     [ ◌ Starting ]    tap → logs
 running Pro  [ ● Desktop ]     tap → view
 running Free [ ● 24:10 ]       tap → view; text = minutes left today
 budget out   [ ▢ 0:00 · Pro ]  tap → upsell
```
   Long-press: View, Stop, Logs (wires up `openGuiLogPage`), Desktop settings.
3. **Notification** actions Open and Stop on the existing ongoing notification. For free users the text shows time left, updated once a minute.
4. The pill reads `XfceBudget.remainingMs` directly; there is no second counter.

**Files.** New `desktop/DesktopController.kt`, `desktop/DesktopPillView.kt`. Changed: `MA:9506-9700`, `MA:1861`, `MA:14860-15080` (bodies move to the controller), `BackgroundService.kt`.

**Gating.** Free 30 min/day, Pro unlimited, enforced in the controller.

**Risks.** A stop from the notification while the X11 activity is in front must close it cleanly. Notification actions arrive when `MA` may be dead; the controller must work from the service.

**Device test.** Both devices, free and Pro: start from workspace, view, return, stop from pill, stop from notification. Free: let the budget run out while viewing and while backgrounded; check stop, pill state and upsell. Kill the app with the desktop running; confirm the budget is folded in on restart.

**Effort.** S–M.

---

## 5. Top bar stats and workspace buttons

**Current state.** `buildProjectWorkspaceLayout` (`MA:21322`): title in a weighted `TextView` (`MA:21356`) with no single-line or ellipsize setting, then `createSystemTelemetryPlate` (`MA:3516`) with battery, CPU (`CpuUtilizationProvider.kt:27`) and RAM (`MA:16096`). Extra buttons hide in `showWorkspaceToolsMenu` (`MA:4756`), a `PopupWindow` with EXTRA KEYS (`:4819`), KEEP AWAKE (`:4820`), COPY SKILL (`:4825`).

**Design.** Two slim rows, 72 dp in total. The popup is deleted.

```
┌──────────────────────────────────────────────┐
│ ☰  my-very-long-project-na…e-backend         │ row 1, 40dp: title only
├──────────────────────────────────────────────┤
│ 82% C34 R61 38°        │ ⌨  📎  ✦  ☀  ●24:10 │ row 2, 32dp
└──────────────────────────────────────────────┘
   stats: fit or drop        rail: always visible
```
Landscape collapses to one 40 dp row: title (minimum 40% width), stats, rail. With stats hidden, portrait is also one row: title plus rail.

Rules:
- Title: `maxLines = 1`, `ellipsize = MIDDLE` so the distinguishing suffix survives. In portrait it owns row 1, so nothing can squeeze it.
- Stats: the user's ordered list is the priority list. Chips are added in order while they fit; the first that does not fit and all after it are dropped. No scroll, no wrap, no text below 11 sp. Each chip is a one-letter or glyph label plus a value in tabular figures, so the strip does not jitter. Tapping a chip shows its full name and value in a tooltip.
- Refresh every 2 s while visible; stop on pause.
- Available: battery, CPU, RAM, battery temperature (`ACTION_BATTERY_CHANGED` `EXTRA_TEMPERATURE`), thermal status (`PowerManager`), network rate (`TrafficStats`), storage free (`StatFs`), GPU load only if the sysfs node is readable (probe once, hide on failure). Default: battery, CPU, RAM, temperature.
- Customise: long-press the strip → a bottom sheet with a checklist and up/down reorder, plus "Hide stats". Stored as one comma-separated preference. Same sheet from Settings.
- Rail: five icon buttons, 32 dp visual, 48 dp touch target, content description and long-press tooltip each: extra keys, attach (image / file sheet), copy skill, keep awake (filled when on), desktop pill (item 4). State is shown by fill, not colour alone.

**Files.** New `workspace/WorkspaceTopBar.kt`, `workspace/StatStrip.kt`, `workspace/ToolRail.kt`. Changed: `MA:21322-21420` (replaced by a call), `MA:3516-3600`, `MA:4756-4850` (deleted), `CpuUtilizationProvider.kt`, Settings.

**Gating.** Identical for Free and Pro.

**Risks.** On a 360 dp screen the rail takes about 5 × 36 = 180 dp, leaving room for three or four chips; check at 1.3× font scale. `/proc/stat` is unreadable for apps since Android 8 — confirm what `CpuUtilizationProvider` falls back to before adding detail.

**Device test.** Both devices: portrait, landscape, split-screen at minimum width, font scale 0.85 / 1.0 / 1.3, a 60-character project name, every stat on, all stats off. The title is never clipped by stats; the rail never scrolls. TalkBack reads each rail button.

**Effort.** M.

---

## 6. Extra keys overhaul

**Current state.** `buildSpecialKeysToolbar` (`MA:4338`), used at `MA:4680` and `MA:21400`. Row 1 (`MA:4380-4460`): nine equal buttons at 44 dp — CTRL ALT SHFT ESC TAB ENT BKSP image document. Row 2 (`MA:4480-4600`) scrolls: arrows, DEL, INS, symbols, Fn, COPY, Transcript, JumpToLive. No haptics, no repeat, no customisation; total height 88 dp plus padding. The vendored Termux `ExtraKeysView` (`termux-app/termux-shared/…/extrakeys/ExtraKeysView.java:147-514`) already supports key matrices, swipe-up popups, macros, repeat and haptics; `com.github.termux:termux-app:v0.118.0` is a dependency (`app/build.gradle.kts:147`) but whether the `termux-shared` classes are on the classpath is UNVERIFIED.

**Design.** Replace the hand-rolled toolbar with `ExtraKeysView` driven by the app's own preferences. Two rows × eight keys, **38 dp rows (76 dp total)**, 2 dp gaps, three swipeable pages with small page dots drawn inside the bottom gap (no extra height). Page 1 never scrolls within itself.

```
Page 1 — keys
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ ESC │  /  │  -  │  |  │ HOME│  ↑  │ END │ PGUP│   small corner glyph =
│  ⎋⎋ │  \  │  _  │  ~  │     │     │     │     │   swipe-up alternate
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│ TAB │ CTRL│ ALT │ SHFT│  ←  │  ↓  │  →  │ PGDN│
│  ⇤  │     │     │     │     │     │     │     │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
Page 2 — agent shortcuts and symbols
│ ^C │ ^D │ ^R │ ^L │ ⇧⇥ │ ↵+ │ DEL │ INS │
│ `@ │ $# │ &= │ *+ │ ;: │ "' │ <>  │ {}  │
Page 3 — actions (four wide keys per row)
│ PASTE │ COPY ALL │ IMAGE │ FILE │
│ TRANSCRIPT │ TO LIVE │ F1–F12 │ KEYBOARD │
```
- Swipe up: ESC → Esc Esc; TAB → Shift+Tab; symbol keys → their pair.
- Arrows, PGUP, PGDN auto-repeat (400 ms, then every 80 ms).
- CTRL, ALT, SHFT: tap = one-shot (filled); second tap or long-press = locked (filled + underline); tap again = off.
- `↵+` (newline without submit) is a macro, because the sequence differs per CLI (UNVERIFIED per CLI).
- Haptic `KEYBOARD_TAP` on press, following the system setting, with an off switch.
- Presets: **Standard** (above), **Single row** (38 dp total: ESC TAB CTRL ALT ← ↓ ↑ →, everything else by swipe-up or page), **Agent** (page 2 first), **Classic** (today's layout, kept for one release). An advanced field accepts Termux `extra-keys` JSON, validated on save. No drag-and-drop editor.
- Size setting: 34 / 38 / 44 dp.
- Hidden automatically when a hardware keyboard is attached.
- Attach also lives on the tool rail (item 5); a "jump to live" chip appears over the terminal only while scrolled back.

**Files.** New `terminal/ui/ExtraKeysBar.kt` (adapter implementing `ExtraKeysView.IExtraKeysView`), `terminal/ui/ExtraKeysPresets.kt`. Changed: `MA:4309-4700` (deleted, replaced by a call), `MA:21400`, `MA:661`, `MA:22533`, `terminal/ui/TerminalContextMenuHelper.kt`.

**Gating.** Identical for Free and Pro.

**Risks.** If `termux-shared` is not on the classpath, copy the five `extrakeys` classes (GPLv3 — the same licence as the Termux terminal code already vendored; confirm the app's licence position covers it). Existing users lose ENT and BKSP from the default; Classic covers it. Swipe-up is not discoverable without the corner glyphs.

**Device test.** Both devices, portrait and landscape, Gboard plus one other keyboard: every key on every page sends the right sequence (`cat -v`, `showkey -a`); Ctrl+C, Ctrl+D, Shift+Tab and newline in opencode and one Pro CLI; arrow repeat in `less`; lock survives a page swipe; haptics on/off; each preset; malformed JSON rejected; bar hides with a Bluetooth keyboard.

**Effort.** M.

---

## 7. PRoot metadata speed

### 7.1 What the owner already measured (`~/repos/termux-lib`)
Branch `perf/parity-sprint-v2-engine-e1-attribution`, 273 commits, all device runs on the X6P-class `duchamp` (MT6897, Android 16) with scratch rootfs containers. Harness in `bench/`: `nc-meta-bench.c`, `nc-io-bench.c`, launch benches, `run-*.sh` runners, a thermal guard, JSON results under `docs/perf/results/`.

State of the product: `libproot.so` (236,072 B, sha256 `2cfa8177…`) and `libloader.so` (18,136 B, `44ef39c1…`) are **byte-identical** in `termux-lib` and NC-PLAY. None of the measured work has been ported.

Measured numbers (all from that repo):

| Measurement | Result | Source |
|---|---|---|
| Developer workloads, proot vs chroot, p50 ratio | `git_status` 27.4×, `git_diff` 27.6×, `tar_read_git` 49.8×, `py_compile_noop` 6.8×, `node_traversal` 1.9×, `cargo_check_noop` 1.9×, `ninja_noop` 1.7×; geomean 6.02× | `docs/perf/dev-ph-r3-2026-08-31.md` |
| `stat` on a path | 123,384 ns proot vs 2,462 ns chroot; 45,923 ns on the fast CLI profile | `docs/perf/changeid-triad-15x.md` |
| `fstat` on an fd | 30,770 ns with `--change-id` vs 616 ns without (chroot 923 ns) | same |
| create/stat/unlink | 6,004.969 → 6,512.719 ops/s (+8.5%) after dropping `--link2symlink -L`; chroot 111,935 | `docs/perf/proot-metadata-performance-report.md:75-93` |
| `getdents64` | about 1.0× of chroot | same |
| Launch of `/bin/true` | 816 ms through the legacy proot-distro wrapper, 104 ms plain proot, 30 ms chroot | `docs/environment/proot-fast-launcher-design.md:91-110` |
| **U11 "rootfd" patch vs upstream U2**, 12 paired rounds, 192/192 valid | geomean **+14.90%**; `tar_read_git` 3,521.5 → 2,866.2 ms; `cargo_check_noop` 120.9 → 89.2 ms; `py_compile_noop` 984.3 → 894.8 ms; `git_diff` 740.1 → 649.9 ms (CI crosses 1); `git_status` 963.4 → 1,042.6 ms (CI crosses 1) | `docs/perf/results/u11-ab-r3-summary.json` |
| U11R2 follow-up | +6.36%, CI [0.816, 1.081], not significant; regression signal on `openat_enoent@shallow-1000` | `docs/perf/u11r2-final-decision.md` |
| Upstream U2 binary (`termux/proot` `7266fb3`) | neutral on launch (186.3 / 189.8 / 191.1 ms) and on metadata (18×); brings upstream hard-link, proc-fd, statx fixes; all compat gates passed | `docs/perf/upstream-u2.md`, `u2-meta-18x.md` |
| Profile of U2 | `fstatat` is 40.43% of self time, issued per path component by `substitute_binding_stat` | `docs/perf/promotion-decision-round3.md` |
| Rejected by measurement | `-O3` (13.45% slower), ThinLTO (−8.49%), short rootfs path (−33.38%), batched fake-id uid/gid (no gain), host lstat prefix walk, persistent broker | various |

Owner conclusions recorded there: seccomp acceleration is already active; the remaining gap is ptrace interception, not CPU or I/O; compiler-flag work is exhausted. Why it stopped: `docs/perf/current-state.md` reads `FAST_DEFAULT=OFF`, `RELEASE_READY=NO`, `U11R2_FINAL_DECISION=DEFERRED_METADATA_PILOT_SIGNAL`, large suite and AC13 gate `NOT_RUN`. The work ran out of time in validation, not in engineering.

### 7.2 Current state in NC-PLAY
`terminal/ProotCommandBuilder.kt:200-213` always passes `--kill-on-exit --link2symlink --sysvipc --kernel-release=… -L --change-id=…`; env at `:315-326`. `terminal/ProotFastCommandBuilder.kt` (172 lines) is an older, divergent copy that still passes `--link2symlink --sysvipc -L` (`:75-79`) and is not wired in; `termux-lib` has a 526-line version with profiles (`:274-283`). The app's own git refresh runs `status --porcelain -uall` plus `diff --numstat HEAD` on every refresh (`git/GitGuestCommands.kt:57-67`).

### 7.3 What ships in 1.8.0, cheapest first

| Step | Change | Measured gain | Risk | Effort |
|---|---|---|---|---|
| A | **Session profiles.** `CLI` profile (AI tools, plain shells, the app's own git and status commands) drops `--link2symlink -L` and `--sysvipc`. `COMPAT` profile (desktop, package installs, component unpack, anything the user starts from "Compatibility shell") keeps today's flags. | +8.5% create/stat/unlink; `--sysvipc` unmeasured; no launch change | Hard links fail with EPERM on the CLI profile. `git` and `uv` fall back to copying; `apk`/`dpkg` are untested — the owner's own rule was "not without a full dpkg/git/symlink regression". Hence `COMPAT` for package work. | S |
| B | **Git settings and app-side calls.** `/etc/gitconfig`: `core.untrackedCache=true`, `core.checkStat=minimal`, `core.trustctime=false`, `index.version=4`, `core.commitGraph=true`, `fetch.writeCommitGraph=true`, `gc.writeCommitGraph=true`. App: `GIT_OPTIONAL_LOCKS=0`, one proot spawn per refresh, debounce, refresh only when the git page is visible. | Not measured in `termux-lib`; `git_status` is the worst workload (27.4×), and it is the one U11 does not help, so this is where to look | Low; `checkStat=minimal` makes git slightly less sensitive on odd filesystems | S |
| C | **Keep the app's own reads out of PRoot.** Credential detection (item 2), file tree, file sizes and the editor (item 16) read host paths from Kotlin. | Removes whole proot spawns (about 100–190 ms each on the measured device) | Absolute symlinks inside the rootfs resolve against Android's root; handle by resolving relative to the rootfs | S |
| D | **Environment.** `NODE_COMPILE_CACHE`, npmrc `audit=false fund=false prefer-offline=true`, `UV_LINK_MODE=copy`, precompiled Python bytecode with `unchecked-hash` for system site-packages and `/opt/hermes`. | Not measured; `py_compile_noop` is 6.8× | Stale bytecode if a system `.py` is edited in place | S |
| E | **Port the harness.** Copy `bench/` and the runners from `termux-lib` into `tools/bench/`, and commit results for 1.7.1 flags vs steps A–D under `docs/results/`. | — | — | S |
| F (stretch) | **Experimental engine.** Build U2 + U11 (`scripts/build_proot_android.sh`, `third_party/proot/patches-common/0001-…`, `profile/0012-u11-rootfd-candidate.patch`, `0013-u11-rootfd-promotion-fixes.patch`) and ship it as a second library, `libproot_u11.so`, next to the legacy one. Settings switch "Experimental PRoot engine", default **off**, with an automatic fall back to legacy if a session fails to start. | +14.9% geomean (tar +25%, cargo +35%); `git_status` unchanged | Medium: U11R2's regression signal and the unrun large suite. Mitigated by default-off and by keeping the legacy binary. Adds about 244 KB. | M |

Not shipping: dropping `--change-id` (the 50× `fstat` cost is real, but the identity-free run was a diagnostic and breaks tools that need a passwd entry and uid 0/1000), U11R2, the persistent broker, any compiler-flag variant, the short rootfs path.

The launch-time gain (816 → 104 ms) applies only if NC-PLAY still goes through a proot-distro style wrapper; whether it does is UNVERIFIED and step E measures it. If it does, port the typed launcher from `termux-lib` in place of NC-PLAY's stale copy.

**Files.** `terminal/ProotCommandBuilder.kt`, `terminal/ProotFastCommandBuilder.kt` (replace with the `termux-lib` version or delete), `terminal/LinuxCommandBuilder.kt`, `app/src/main/assets/scripts/nativecode_proot_fast.sh`, `git/GitGuestCommands.kt`, `tools/rootfs/overlay/alpaquita/etc/gitconfig`, `…/etc/profile.d/*`, `…/etc/npmrc`, `tools/bench/*`, `app/src/main/jniLibs/arm64-v8a/libproot_u11.so` (step F), Settings.

**Gating.** Identical for Free and Pro.

**Device test.** Run the ported harness on both devices for 1.7.1 flags, A, A+B, and F. Functional regression on the CLI profile: `git clone`, `git gc`, `npm ci`, `pip install`, `uv sync`, a Gradle build, opencode and one Pro CLI session. On COMPAT: `apk add`, desktop start, component unpack. With F on: the same list, plus the `openat` ENOENT shape that flagged in U11R2.

**Effort.** M for A–E; M more for F.

---

## 8. GPU drivers

**Current state.**
- Turnip: `tools/rootfs/mesa-adreno/sources.lock:1-5` pins `lfdevs/mesa-for-android-container` tag `mesa-26.3.0-devel-20260824`, sha256 `c014cf66…2ccf`; staged to `/opt/nativecode-turnip` (`build_alpaquita_rootfs.sh:156-157`).
- PanVK: `app/src/main/assets/panvk-rc3/libvulkan_panfrost_kbase.so` (21,908,872 B, = upstream `panvk-kbase-v0.1.0-rc3-lowmem` glibc) plus copies of `gpu-env.sh`, `gpu-backend.sh` and `panvk_kbase.json`, installed by `PanvkDriverUpdate.kt:19-23`. `needsUpdate` (`:52-59`) compares only the `.so` and `gpu-env.sh` hashes, so a change to `gpu-backend.sh` alone never reaches existing users.
- Environment: `tools/rootfs/overlay/alpaquita/etc/profile.d/gpu-env.sh` — KGSL branch lines 6-13 (Turnip ICD, `TU_DEBUG=noconform`, `MESA_VK_WSI_DEBUG=sw`, zink), Mali branch lines 14-23 (PanVK ICD, `WSI_X11_TERMUX=1`, `PANVK_KBASE_DRI3=raw`, zink). `…/usr/local/lib/nativecode/gpu-backend.sh` (415 lines): `gpu_unset_env` 19-31, `turnip` 161-171, `panvk` 180-186, `panvk-zink` 187-198. The desktop sources it from `start_gui.sh:296`, with a fallback `case` at `:300-340`. Duplicate copies exist at `tools/rootfs/overlay/alpaquita/etc/bash/gpu-env.sh` and `tools/rootfs/gpu-backend.sh`.
- Known status (`docs/gpu-drivers-status-and-handoff.md`): OP13R Turnip Vulkan 1.4 works, GL capped at GLES 3.2, zink failed with "failed to create dri2 screen" under PRoot. X6P PanVK Vulkan 1.4 works, zink over PanVK gives GLES 3.1.

**Facts.**
- **PanVK `panvk-kbase-v26.3.1`** (2026-10-31): `libvulkan_panfrost-glibc-aarch64.so`, 33,917,432 B, sha256 `063bcf0cacd6605458f01530e7fc4f4de43cda7d7a995d9518e1a6e38f5d0db6`; needs glibc 2.38; covers Mali v6–v14; ICD `api_version` 1.4.363. Upstream states the glibc build was not run on a device.
- **Low-memory mode is `PANVK_BC_MODE`.** Value `lowmem` routes BC1–BC5 textures to ETC2/EAC shadows; `exact` (or any other value) turns it off. The v26.3.1 notes say low-mem is on by default on Mali v10+ (the G615 is v11), and the string `unknown PANVK_BC_MODE=%s, using exact` is in the binary. The plan sets it explicitly so behaviour does not depend on a default. It covers BC texture emulation only; it is not a general memory limit. Known caveat from the rc4-lowmem notes: `vkCmdCopyImage` on BC formats and raw readback can be wrong in low-mem mode; `PANVK_BC_RAW=lazy` keeps raw blocks readable. Whether 26.3.1 fixed that is UNVERIFIED. Related: `PANVK_DEBUG=mem`, `PANVK_BC_AFRC`, `PANVK_GPU_STATS`, `PANVK_WSI_SW_CACHED`; a family of `PANVK_MEM_*` names exists in the binary with unconfirmed meaning.
- **Turnip `mesa-27.0.0-devel-20261025-r2`** (2026-11-08): `…_debian_trixie_arm64.tar.gz`, 12,234,186 B, sha256 `79e1aaf5a49e204fae68a5c87c76248701ba723f4349bda87d310f456c5705cf`; highest symbol version GLIBC_2.38 across `libvulkan_freedreno.so`, `libgallium`, `libEGL_mesa`, `libGLX_mesa`; includes `zink_dri.so` and the GL stack. New: a present wait fence is on by default for X11/XWayland (`MESA_DRI3_DISABLE_PRESENT_WAIT_FENCE=1` disables it), a KGSL present wait-fence fix, OpenGL capabilities preserved for KGSL clients.
- Rootfs glibc 2.43 satisfies both.

**Design.**
1. PanVK → 26.3.1 glibc build as the `panvk` component (item 3.4), installed under the existing `/opt/nativecode/panvk` path with our own ICD JSON bumped to 1.4.363.
2. Add to both Mali branches of `gpu-backend.sh` (after the `PANVK_KBASE_DRI3=raw` lines at 185-186 and 194-195) and to `gpu-env.sh` (after line 20):
   `export PANVK_BC_MODE="${NATIVECODE_PANVK_BC_MODE:-lowmem}"`
   and add `PANVK_BC_MODE` to `gpu_unset_env`. Mirror the edit in the `start_gui.sh` fallback and in the two duplicate copies (or delete the duplicates and source one file). A Settings → GPU switch "Exact BC textures (more memory)" sets `NATIVECODE_PANVK_BC_MODE=exact`.
3. Turnip → `mesa-27.0.0-devel-20261025-r2` as the `turnip` component; update `sources.lock`. Test with and without `MESA_DRI3_DISABLE_PRESENT_WAIT_FENCE=1` on OP13R and set whichever paces Phosh better.
4. Both drivers and the `overlay` scripts are components, so existing users receive them through `nativecode_update_core`, and script-only changes are detected by the manifest hash rather than by the `.so`. `app/src/main/assets/panvk-rc3/` is removed from the APK (saves 22 MB of base module).
5. The previous driver stays selectable for one release (USE PREVIOUS on the card). The updater refuses while the desktop is running.

**Files.** `tools/rootfs/mesa-adreno/sources.lock`, `fetch-lfdev-mesa.sh`, `tools/rootfs/mesa-mali/sources.lock`, `build_alpaquita_rootfs.sh:156-157,311-312,394-397`, `gpu-backend.sh`, `gpu-env.sh`, `start_gui.sh`, `tools/rootfs/test_mesa_mali.py:206-208`, `app/src/main/assets/panvk-rc3/` (remove), `PanvkDriverUpdate.kt` (replaced by the component updater), `docs/gpu-drivers-status-and-handoff.md`.

**Gating.** Identical; desktop time stays limited for free users.

**Risks.** The PanVK glibc build has never run on hardware upstream — X6P acceptance is a hard gate; on failure stay on rc3-lowmem and report upstream. Low-mem BC readback artefacts in specific apps. Turnip frame pacing change on Termux-X11.

**Device test plan — X6P (Mali-G615), the gate.** Test tools are in no package list (`vulkan-tools`, `mesa-demos`, `glmark2`, `vkmark` are absent from `tools/rootfs/packages-alpaquita-*.txt`); add `vulkan-tools` and `mesa-demos` to the GUI list and install the rest with `apk add` for the test.
1. Driver identity: `vulkaninfo --summary` shows Mesa PanVK 26.3.0-devel build 122i, Vulkan 1.4; `gpu_probe_panvk` (`gpu-backend.sh:253`) passes.
2. Low-mem confirmation: start with `PANVK_DEBUG=mem`; capture the memory report with `PANVK_BC_MODE=lowmem` and again with `exact`; record both. Check logcat/stderr for the BC emulation line.
3. Phosh: start the desktop, idle 10 minutes, then open and close the app drawer, switch apps, rotate; no compositor crash, no visual corruption; record RSS of the compositor and the driver memory report at start and after 10 minutes.
4. Vulkan apps: `vkcube` 60 s; `vkmark` full run (score recorded); one real app — a GTK4 app with `GSK_RENDERER=vulkan`.
5. OpenGL through zink (`panvk-zink` mode): `glxinfo -B` reports renderer "zink Vulkan 1.4 … Mali", `glxgears -frames 300`, `glmark2` and `glmark2-es2` (scores), `panfrost-kbase-test` (`tools/rootfs/overlay/alpaquita/usr/local/bin/panfrost-kbase-test:36-43`), Chromium or Firefox WebGL sample, one GTK3 app.
6. Texture correctness: one BC-compressed sample in both `lowmem` and `exact`; compare screenshots.
7. Pressure: open the browser plus two GL apps under Phosh until the low-memory killer acts; the desktop must survive or fail with a clear message.
8. Compare every score with rc3-lowmem on the same device, same session.

**OP13R (Adreno 750).** `vulkaninfo --summary`, `vkcube`, `vkmark`, `glmark2-es2`, zink retest (the dri2 screen failure), Phosh 10 minutes with and without the present wait fence, Chromium WebGL. Scores against the 26.3.0 build.

**Both.** Update through the Components page on an upgraded install; corrupt payload rejected; USE PREVIOUS; update refused while the desktop runs.

**Effort.** M.

---

## 9. Native agent chat (beta): opencode, Claude Code, Codex

**Current state.** Agents run only in terminal tabs (`createWorkspaceTerminalTab`, `MA:20914`), gated at `MA:5934-5991`. The app is programmatic Views (`MA:690`); no `RecyclerView` dependency is declared (`app/build.gradle.kts`). Markdown rendering exists (`skills/SkillMarkdown.kt`) and so does a diff renderer (`renderDiffLines`, `MA:9112`). `terminal/ShellCommandRunner.kt:88,102` has `runStreamed` and `runStreamedCancelable`.

**Adapter per CLI.** The rule: use the machine interface the CLI itself ships, so no Node adapter packages enter the rootfs and the wire format is versioned with the binary.

| CLI | Primary adapter | Why | Fallback |
|---|---|---|---|
| opencode 2.2.7 | **ACP**: `opencode acp` (JSON-RPC 2.0 over stdio, one object per line; `initialize` → `session/new` → `session/prompt`; `session/update` notifications; `session/request_permission`; `session/cancel`; `loadSession`) | Documented, versioned protocol. The 2.x HTTP API (`opencode serve`, `/api/…`) labels itself "Experimental … 0.0.1" and broke between 1.x and 2.x. | terminal |
| Codex 0.167.1 | **`codex app-server`** (JSON-RPC, JSONL on stdio; `initialize`, `thread/start`, `thread/resume`, `turn/start`; approvals arrive as server requests such as `item/commandExecution/requestApproval`) | Native, no extra package; `codex app-server generate-json-schema --out DIR` produces the schema from the installed binary, which is what makes it version-resilient. | terminal |
| Claude Code 2.1.322 | **stream-json**: `claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages` | Uses the `claude` binary already installed. The ACP adapter would add Node plus the Agent SDK (or a ~209 MB standalone tarball). | terminal |

**Design.**
```
 Chat page (RecyclerView)                      Terminal tab (unchanged)
        │ AgentEvent                                   ▲ "Open in terminal"
        ▼                                              │
   ChatSession ── AgentAdapter (interface) ────────────┘
                    ├─ AcpAdapter            → proot … opencode acp
                    ├─ CodexAppServerAdapter → proot … codex app-server
                    └─ ClaudeStreamAdapter   → proot … claude -p …stream-json
                  JsonLineProcess: stdin/stdout pipes, no PTY, stderr → ring buffer
```
1. `JsonLineProcess` — starts a guest process through `LinuxCommandBuilder` with plain pipes on the `CLI` proot profile, reads stdout line by line off the main thread, skips lines that are not JSON until the first valid one, writes lines to stdin. `org.json`, no new dependency.
2. `AgentAdapter` — three small implementations (three real products justify the interface) mapping their wire format to one sealed `AgentEvent`: `TextDelta`, `ThinkingDelta`, `ToolCall(started|updated|finished)`, `Diff`, `PermissionRequest(options)`, `Usage`, `TurnEnded(reason)`, `Error`, `SessionId`, and `Unknown(rawJson)`.
3. Chat page — `RecyclerView` (new dependency `androidx.recyclerview`, shared with item 10), stable ids, one row type per event kind:
   - assistant text streams into a `TextView`, coalesced to one update per 50 ms, plain while streaming and converted to spans when the block completes;
   - tool calls are collapsed cards (title, status, duration) that expand;
   - diffs reuse `renderDiffLines`;
   - permission requests are inline cards offering exactly the options the agent sent, answered by id, never auto-approved;
   - `Unknown` is a collapsed row showing raw JSON — displayed, never dropped, never fatal.
   - Composer: multi-line input, send, stop, attach, skills picker.
4. **Placement.** The terminal page's tool launcher and the workspace tool sections list each CLI once. For the three supported tools the row gains a second action:
```
│ opencode                 [ Chat β ] [ Terminal ] │
│ Claude Code              [ Chat β ] [ Terminal ] │
│ OpenAI Codex             [ Chat β ] [ Terminal ] │
│ Qwen Code                           [ Terminal ] │
```
   Chat opens as a workspace tab next to terminal tabs. Every chat tab has "Open in terminal". A Settings switch "Chat view (beta)" hides the buttons entirely.

**Version resilience.**
- Capabilities come from the handshake (`agentCapabilities` in ACP `initialize`; Codex `initialize`; Claude's init message), never from the CLI's name or version string.
- Unknown fields are ignored; unknown event kinds become `Unknown`.
- Handshake timeout 20 s. On timeout, non-zero exit or a parse failure on the first protocol line, the tab shows one banner — "Chat view is not available for this version of <tool>" — with Open in terminal and Details.
- Contract tests: record real handshake → prompt → tool call → permission → end transcripts from each pinned CLI into `app/src/test/resources/agent/<tool>-<version>.jsonl` and replay them through the adapter in JVM unit tests. A CLI bump that changes the wire shape fails a test before it reaches a device. For Codex, the generated JSON schema is committed next to the fixture and diffed on every bump.
- Health checks only run the handshake; they never create a session.

**Claude approvals.** Headless permission prompts need either `--permission-prompt-tool` (an MCP tool) or the SDK control channel (`--permission-prompts host`, v2.1.259+); the wire names of the latter are UNVERIFIED. One-day spike first. If the control channel is workable, Claude gets inline approvals like the others. If not, the Claude beta ships with a permission-mode selector in the chat header (plan / accept edits / ask in terminal) and no inline approval card, and says so on screen.

**Lifetime.** Sessions run under the existing foreground `BackgroundService`. Turn end and permission requests raise a notification through the same channel the agent bridge uses.

**Files.** New: `agent/chat/JsonLineProcess.kt`, `AgentAdapter.kt`, `AgentEvent.kt`, `AcpAdapter.kt`, `CodexAppServerAdapter.kt`, `ClaudeStreamAdapter.kt`, `ChatSession.kt`, `agent/chat/ui/AgentChatPage.kt`, `ChatRows.kt`, fixtures. Changed: `MA:20914` (tab type), `MA:5934-5991` (gate count), `cliauth/ToolLauncherCatalog.kt` (a `chat` flag), `terminal/LinuxCommandBuilder.kt` (non-PTY launch), `app/build.gradle.kts`.

**Gating.** A chat tab is an AI session: `FeatureGate.canLaunchAiTool` and `canOpenAiSession`, with chat and terminal sessions counted together (`aiLaunchAllowed`, `MA:5987-5991`). Free: opencode chat, one session. Pro: Claude and Codex chat, multiple sessions. Unit test for the combined count.

**Risks.** Guest stdout must carry only protocol lines (proot warnings, profile output, update banners): minimal environment, no login shell, skip-until-JSON. Codex method and notification names beyond those listed (`turn/interrupt`, delta names) are UNVERIFIED — read them from the generated schema, not from memory. Sign-in is required beforehand (item 2); on an auth error the tab links to that tool's login page. Long conversations: cap rendered rows and page older ones.

**Device test.** Both devices, per tool: a prompt that reads a file, one that edits (diff row), one that runs a command (allow, decline), stop mid-turn, background two minutes mid-turn, rotate, kill and reopen. Fallback: point the launcher at a broken binary; banner and terminal button appear. Free: opencode only, second session shows the upsell. Performance on X6P: a 5,000-token streamed answer stays smooth; memory after 200 turns.

**Effort.** L (opencode ACP alone is M).

### 9.1 Guest entitlement gate (owner item 9, built; chat reuses it)

**Problem.** The app gates tabs (`aiLaunchAllowed`, `MA:5459`): Free = opencode, one AI session, no shell tab. But opencode's bash tool (or any Pro shell) can run `codex`, `claude`, a second `opencode`. The app cannot see that.

**Design (all Kotlin file I/O; the app never runs a guest command).**
1. `entitlements/GuestGate.kt` writes `/etc/nativecode/entitlement` in the rootfs: `v, enforce, pro, exp, free_tool, max_free, max_pro, mac`. Values come from `EntitlementStore` and `FeatureLimits` (no second source of truth). `exp` = last Play check + 7-day offline TTL (`EntitlementStore.proValidUntilMs`), so offline Pro works exactly as in the app. `mac = sha256(body + key)`, key random per install (pref `gate_key`).
2. `app/src/main/assets/gate/nc-gate.sh` is copied to `/opt/nativecode/gate/bin/<tool>` for opencode, codex, claude, qwen, agy, grok, kiro, kiro-cli, hermes. That dir is first on PATH: `launch_tool.sh` (v5, `ChrootCommandBuilder.kt`) and `/etc/profile.d/zz-nativecode-cli.sh` (`LinuxGuestDefaults.cliPathProfile`), so it is inherited by every child shell, including a CLI's shell tool. Automation (`LinuxCommandBuilder.build`, `bash --noprofile`) does not get the dir, so onboarding and version probes are unaffected.
3. The shim: finds the real binary later on PATH, lets `--version`/`--help` through, verifies the MAC, then: Pro and unexpired = allow up to `max_pro`; otherwise only `free_tool` and up to `max_free`. Session count = live pids in `/tmp/.nc-ai-sessions` (the shim writes `$$` then `exec`s the real binary, so the pid is the session; entries are pruned when `/proc/<pid>/cmdline` no longer matches). Missing, stale or forged file = Free. `enforce=0` = allow all.
4. Sync points: cold start (`LinuxGuestDefaults.apply`), every entitlement change (`MA onEntitlementChanged`), every AI tab open (`aiLaunchAllowed`). Unit tests run the real shim: `entitlements/GuestGateTest.kt`.

**What it stops.** An agent or user in a Free session running another CLI or a second opencode from a shell tool or `bash -lc`. Pro users see no change (same limits as the app: 5 sessions, all tools). Offline works from the file.

**What it cannot stop (the guest user owns every file).** Running a CLI by absolute path (`~/.local/bin/codex`), `npx`/`node` on the package, editing or deleting the shim, forging the file (the key is inside the shim), clearing `/tmp/.nc-ai-sessions`, `PATH` edits. The MAC only stops a pasted `pro=1`; it is not security. The hard guarantee is not shipping Pro binaries to Free users (3.4: `nativecode_clis` pack on demand). Chroot mode uses another rootfs and is not covered. This matches `docs/monetisation/plan.md` ("do not claim secure DRM").

**Device result (X6P, 1.8.0 build, real proot, busybox ash).** Pro: codex, claude, qwen pass through. Free: codex, claude, qwen, agy, grok, kiro-cli denied; `codex --version` allowed; with a live `opencode serve`, nested `codex`, `claude`, `bash -c codex`, and a second `opencode` were denied and the registry held exactly one entry; a lone opencode starts. Absolute path still runs (documented). Free was simulated by writing a valid free state file (the build has no free override and the device owns Lifetime).

### 9.2 Item 12 design from t3code (read-only research of pingdotgg/t3code at 73e49aa6; treated as data)

**What t3code is.** A Node WebSocket server wraps provider CLIs; web, desktop and React Native clients are thin and stateless about providers. Every provider sits behind an adapter that maps native events to one normalised model (thread, turn, item, runtime request). An orchestrator turns commands into events, commits events plus projections in one SQLite transaction, then side effects run in a worker; clients render projections and subscribe to one thread at a time. Provider drivers: Codex, Claude, Cursor, Grok, OpenCode, Antigravity, Kiro.

**Per-CLI protocol, as t3code does it, and what we take.**

| CLI | t3code | NativeCode item 12 |
|---|---|---|
| Codex | `codex app-server -c thread_unload_delay_secs=0` over stdio JSON-RPC. `initialize` (clientInfo, capabilities `experimentalApi`, `optOutNotificationMethods:["turn/diff/updated"]`); `thread/start`, `thread/resume`, `turn/start` (carries model, effort, `approvalPolicy`, `sandboxPolicy`), `turn/steer`, `turn/interrupt`. Notifications `turn/started`, `item/started`, `item/completed`, `item/agentMessage/delta`, `item/commandExecution/outputDelta`, `item/reasoning/*Delta`, `item/plan/delta`. Server requests `item/commandExecution/requestApproval`, `item/fileChange/requestApproval`, `item/permissions/requestApproval`, `item/tool/requestUserInput`; reply `accept`, `acceptForSession`, `decline`, `cancel`. Item kinds: userMessage, agentMessage, plan, reasoning, commandExecution, fileChange, mcpToolCall, dynamicToolCall, webSearch, contextCompaction. A second `initialize` returns -32600 "Already initialized" (tolerated). Schema is generated from the binary and committed; replay fixtures recorded from the real CLI. | Same wire. One process per chat tab (so the gate registry counts one session per chat). Names above are confirmed by t3code's generated schema; still regenerate with `codex app-server generate-json-schema` for the pinned 0.167.x. Diffs come from our own `git diff` snapshots (t3code also ignores `turn/diff/updated` and uses hidden git-ref checkpoints). |
| Claude Code | Claude Agent SDK `query()`: `permissionMode`, `canUseTool` callback for approvals, `includePartialMessages`, `resume`, `interrupt()`, `setPermissionMode()`, MCP over stdin. The SDK drives the `claude` binary over stdio. | No Node SDK on device. Speak the CLI wire directly: `claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages` plus the control channel the SDK uses for `canUseTool` (`control_request` / `control_response`; exact subtype names UNVERIFIED, take them from the pinned binary in the day-one spike). If the channel fails, ship the permission-mode selector without inline approval (section 9, Claude approvals). |
| OpenCode | 2.x: `opencode serve --service` HTTP plus SSE `/api/event` (events `session.text.delta`, `session.tool.called/progress/success/failed`, `session.execution.*`), password in `OPENCODE_PASSWORD`; 1.x one server per thread. | Keep ACP (`opencode acp`, stdio, no port, no password, one process per chat). t3code uses ACP for Grok (`grok agent stdio`) and Kiro (`kiro-cli acp`), which confirms ACP as the pattern. Fall back to the 2.x HTTP API only if ACP lacks tool detail; the HTTP API is labelled experimental. |

**Architecture for Android.**
- `JsonLineProcess` + `AgentAdapter` + sealed `AgentEvent` as in section 9, plus t3code's two ideas that matter: (1) a durable per-thread state (turn items keyed by native id, runtime requests keyed by native request id) that the UI renders from, so a rotation, a process death or a reopened tab replays state instead of depending on the live stream; (2) approvals are persisted requests with an explicit decision, answered by the id and options the agent sent, never auto-approved. Store state as JSON files under app storage (no Room dependency needed for a beta); cap rows.
- Coalesce text deltas (t3code `ProviderTextDeltaCoalescer`; ours is one update per 50 ms).
- Health probes run `initialize` only and never create a session (t3code: "setup must not happen as a health-check side effect").
- Capabilities and version gating come from the handshake and the `userAgent`; a known-broken version shows "update the CLI" (t3code advisory) instead of failing mid-turn.
- Runtime modes map the way t3code maps Codex: ask (`untrusted`, read-only), auto-accept edits (`on-request`, workspace-write), full access (`never`). Default is ask; full access needs a confirmation.
- Updates stay manual or via component packs; never self-update under a live session (t3code holds leases on running versions).

**Android UI mapping (t3code web to our Views).**

| t3code | NativeCode |
|---|---|
| ChatView timeline | `RecyclerView`, one row type per item kind, stable ids |
| ChatComposer + controls bar (model, runtime mode) | bottom composer: input, send/stop, mode chip, model chip, attach, skills |
| ComposerBanner stack (approvals, errors) | cards pinned above the composer; approval card lists the agent's options |
| Changed files tree / diff panel | bottom sheet with file list; `renderDiffLines` |
| Command output panel, terminal drawers | tool card expands to output; "Open in terminal" |
| Background work / subagent rows | collapsed rows, count badge |

**Pro gating hooks (reuse item 9, add nothing parallel).**
1. Launch: a chat tab goes through `aiLaunchAllowed(type)` like a terminal tab, so `FeatureGate.canLaunchAiTool` and `canOpenAiSession` apply and the chat tab counts in `terminalSessionTypes`/`workspaceTabNames` with terminals.
2. Spawn through `LinuxCommandBuilder.buildToolDirect` (launch_tool.sh, gate dir on PATH). The shim then enforces the same entitlement file and registers the chat process as a session in `/tmp/.nc-ai-sessions`, so terminal sessions, chat sessions and nested shell-tool calls share one count.
3. A denied start prints `NativeCode: ...` on stderr and exits 1 before any JSON. The adapter treats exit before `initialize` with that prefix as `GateDenied` and shows the Upgrade sheet (`gateAllows`), not "chat unavailable".
4. Two launcher details to fix in item 12: `launch_tool.sh` appends `--auto` to opencode (not valid before `acp`), and it may print banners; skip-until-JSON stays mandatory.
5. Chat sync: `GuestGate.sync` already runs on every AI tab open; no new entitlement code.

**Risks.** t3code is a Node server with a database; we copy its model, not its stack. Claude control-channel names unverified. Codex and ACP wire names must be taken from the pinned binaries, not from t3code (it targets its own versions). The shim costs one `sh` start plus a few small tools per CLI start (not measured, ESTIMATE tens of ms under proot). Long conversations on a phone: cap rows and page older ones. A model that is told to avoid the gate message can still use an absolute path; do not promise more than 9.1.

---

## 10. Git page: full history and commit graph

**Current state.** `git/GitGuestCommands.kt` (113 lines), `GitPorcelainParse.kt` (197), `GitModels.kt` (56), `GitRepoService.kt` (74); everything runs in the guest. UI: `buildProjectGitDiffLayout` (`MA:22424`), `refreshGitDiffTree` (`MA:20831`), `showDiffViewer` (`MA:9008`), `renderDiffLines` (`MA:9112`), branches card `buildProjectGitBranchesCard` (`MA:21667`) reading "View only — checkout in terminal" (`MA:21916`). No `git log` anywhere. Dead controls: Clone and Push (`MA:4869-4877`), fetch (`MA:10229-10230`).

**Why this is cheap under PRoot.** `git log` reads packfiles and the commit-graph through `read`/`mmap`, which proot does not trap. The cost is one process start plus git's own time. `git status` is the 27× workload; history is not.

**Design — data.**
- **One long-lived stream instead of pages.** Start once per view:
  `git -c log.showSignature=false log --topo-order --branches --tags --remotes HEAD --format=%H%x00%P%x00%an%x00%at%x00%s%x1e`
  through `runStreamedCancelable`, reading raw bytes. The reader thread parses records and stops reading when it is 200 rows ahead of what the list has asked for; the pipe fills and git blocks. Scrolling near the end releases the reader for another 200. There is no `--skip` (which re-walks) and no frontier bookkeeping. The process is killed when the page is left or the filter changes. If it has died when more rows are wanted (app was backgrounded), restart with `--skip=<rows already shown>`; that is the only place `--skip` is used.
- With a commit-graph file, `--topo-order` emits the first commit almost immediately instead of walking the whole history; `git commit-graph write --reachable --split` runs after clone and fetch (set by the gitconfig in item 7) and once, in the background, the first time the history page opens on a repository without one.
- Refs: one `git for-each-ref --format=%(objectname)%00%(refname)%00%(HEAD)` per refresh, mapped hash → labels. Not `%D` per commit.
- Detail on tap, one spawn: `git show --no-patch --format=…`, `git diff-tree --root --no-commit-id -r -m --first-parent --name-status <hash>` and the same with `--numstat`. File diff: `git show --first-parent --format= <hash> -- <file>` into the existing diff viewer (works for root and merge commits).

**Design — lane assignment.** Streaming, O(commits × lanes), state carried across batches, pure function:
```
lanes: MutableList<Hash?>          // hash each lane waits for; null = hole
for commit c in stream order:      // children arrive before parents
    hits = indices i where lanes[i] == c.hash
    col  = hits.firstOrNull() ?: firstHole() ?: append()
    for i in hits.drop(1): lanes[i] = null
    row = Row(c, col, lanesBefore = snapshot, joins = hits)
    if c.parents.isEmpty(): lanes[col] = null
    else:
        lanes[col] = c.parents[0]
        for p in c.parents.drop(1):
            j = lanes.indexOf(p).takeIf { it >= 0 } ?: firstHole() ?: append()
            lanes[j] = p; row.forks += j
    row.lanesAfter = snapshot; emit row
```
Each row stores what passes through it, so it draws without its neighbours. Rows are kept as compact objects (hash, parents, column, lane snapshot as a small int array); at 50,000 commits this is a few tens of MB at most, and rows beyond a cap of 50,000 are not loaded without an explicit "load more".

**Design — UI.** The DIFF tab becomes GIT with two segments: CHANGES (today's page, unchanged) and HISTORY.
```
┌──────────────────────────────────────────────┐
│ [ CHANGES ]  [ HISTORY ]     ⎇ all ▾  ⟳  🔍  │
├──────────────────────────────────────────────┤
│ ●      Fix billing retry          2h  a1b2c3 │
│ │      [main] [origin/main]                  │
│ ●─╮    Merge feature/login        5h  d4e5f6 │
│ │ ●    Add OAuth callback         6h  0a9b8c │
│ │ │    [feature/login]                       │
│ ● │    Bump gradle to 9.9         1d  f1e2d3 │
│ ├─╯                                          │
│ ●      Initial workspace layout   3d  9a8b7c │
└──────────────────────────────────────────────┘
```
- `RecyclerView`, stable ids, fixed 52 dp rows, `setHasFixedSize`. The graph is a custom `View` per row drawing precomputed segments with `Canvas`; no bitmaps, no allocation in `onDraw`.
- Lane width 12 dp; the graph column is capped at six lanes, with "+n" beyond; the subject always gets at least 55% of the width.
- Row: subject (one line), ref chips (branch / remote / tag distinguished by shape and prefix), relative time, short hash.
- Filter: all refs or one ref. Search by subject or author uses `--grep` / `--author` and shows a flat list without a graph (filtered output omits the parents the lanes wait for); tapping a result jumps to it in the full graph.
- Detail: bottom sheet with message, author, date, hash (copy), parents (tap to jump), changed files with +/− counts.
- **Actions** — included because each is one command plus a confirmation: checkout a branch (`git switch`, refused with a message when the tree is dirty), create a branch at a commit (`git switch -c`), copy hash, FETCH (⟳, makes the dead fetch control real). Checkout of an arbitrary commit, cherry-pick, revert, reset and tag stay in the terminal. Clone and Push dead buttons are removed.

**Files.** `git/GitGuestCommands.kt`, new `git/GitLogParse.kt`, `git/GitGraphLayout.kt` (+ tests), `git/GitLogStream.kt`, `git/ui/GitHistoryPage.kt`, `git/ui/GraphCellView.kt`; `MA:22424-22460`, `MA:3283-3286`, `MA:4869-4877`, `MA:10229`, `MA:21916`.

**Gating.** Follows the existing `WORKSPACE` gate; no new gate.

**Risks.** A blocked `git log` holds a proot process open; kill it on page leave, on app background after 30 s, and on repository change. Lane tests must cover linear, branch, merge, octopus and criss-cross histories and assert that no two live lanes share a column. Checkout while an agent is editing files: confirmation names any open AI session in that project.

**Device test.** Both devices: a small repo; a 20k-commit repo with many merges; a shallow clone; an empty repo; detached HEAD. Time to first row with and without a commit-graph. Scroll 5,000 commits on X6P watching for dropped frames. Leave the page mid-stream and confirm the process is gone. Filter, search, detail, file diff, checkout clean and dirty, create branch, fetch. TalkBack reads subject, refs and date.

**Effort.** M–L.

---

## 11. Pro free trial

**Current state.** Two subscriptions and one in-app product: `nativecode_pro_monthly`, `nativecode_pro_yearly` (`ProductType.SUBS`) and `nativecode_pro_lifetime` (`INAPP`) (`billing/BillingRepository.kt:31-36,75-76`). Billing library `billing-ktx:9.1.0` (`app/build.gradle.kts:163`). Offer choice (`BillingRepository.kt:154`):
`offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()` — always the base plan. Price text comes from the last pricing phase (`:143-150`), shown at `MA:6020` and `MA:6091`; purchase at `MA:6093`; sheet `showUpgradeSheet` `MA:6066`. `docs/monetisation/plan.md` records no trial decision.

**Design.**
1. Play Console: add a free-trial offer (owner chooses length; 7 days assumed) to the monthly and yearly base plans, eligibility "new customer acquisition". Play enforces one trial per account and omits the offer from `subscriptionOfferDetails` for ineligible users, so no local trial flag is needed.
2. `subOffer`: prefer an offer whose `pricingPhases.pricingPhaseList` has a phase with `priceAmountMicros == 0L`; else the base plan as today.
3. `trialPeriod(sku)`: parse that phase's `billingPeriod` (ISO 8601, e.g. `P7D`).
4. Copy: button "Start 7-day free trial", sub-line "then $3.99/month · cancel anytime" from the last phase; without an eligible trial the text is unchanged. Lifetime never shows a trial.
5. Entitlement needs no change: a trial purchase is an active subscription.

**Files.** `billing/BillingRepository.kt`, `MA:6020`, `MA:6066-6095` (strings via resources), a unit test for offer choice with fake `ProductDetails`.

**Risks.** Trial terms must be shown before purchase (Play subscription policy). Base plan ids and offer tags exist only in Play Console.

**Device test.** Licence-tester account never subscribed: trial text shown, purchase completes, Pro unlocks. Account that already used a trial: base price shown. Cancel during trial; entitlement ends when Play says so.

**Effort.** S.

---

## 12. Dev-server preview

**Current state.** WebViews exist for local HTML only (`skills/SkillsPage.kt:383`, `MA:8684`). No `networkSecurityConfig` or `usesCleartextTraffic` in the manifest; targetSdk 37 blocks cleartext by default. PRoot shares the app's network namespace, so a guest server on `127.0.0.1:3000` is reachable from the app (the guest PulseAudio link already relies on this). No URL detection exists in `terminal/`.

**Design.**
- `preview/PreviewPage.kt`: a WebView with address bar, reload, back/forward, "Open in browser", and a desktop/mobile user-agent toggle. JavaScript and DOM storage on; file access off; no JavaScript interface is exposed.
- Network security config allowing cleartext **only** for `localhost` and `127.0.0.1`.
- Port discovery, two cheap sources, no `/proc/net/tcp` (unreadable for apps on current Android):
  1. Terminal output hook: a regex for `https?://(localhost|127\.0\.0\.1|0\.0\.0\.0):(\d+)` on session output raises a small "Preview :5173" chip above the terminal.
  2. On opening the page: a 150 ms connect probe of a fixed list (3000, 3001, 4200, 4321, 5000, 5173, 5174, 8000, 8080, 8081, 8787, 8888, 1313) in parallel, plus a manual port field.
- Entry points: the chip, and a Preview item in the workspace tabs.

**Files.** New `preview/PreviewPage.kt`, `preview/PortProbe.kt`, `app/src/main/res/xml/network_security_config.xml`; `AndroidManifest.xml`; a hook in the terminal session output path; `MA` tab registration.

**Gating.** Free.

**Risks.** Servers bound to a LAN address rather than loopback will not be found by the probe; the manual field covers it. WebView is not Chrome DevTools; "Open in browser" is the escape hatch.

**Device test.** Both devices: `npx vite`, `python3 -m http.server`, a Next.js dev server; chip appears; hot reload works over the WebSocket; rotate; background and return; a server that exits shows an error page with retry.

**Effort.** M.

---

## 13. Agent notifications

Already handled by the existing agent bridge; **no new work in 1.8.0.** For the record: `agent/AgentBridgeServer.kt` (abstract Unix socket, peer-uid checked) implements `ping` `:186`, `notify` `:192`, `status` `:213`, `toast` `:225`, `telemetry` `:235`, `open_link` `:239`, `install_apk` `:267`, `dismiss_alert` `:322`; the guest client is `app/src/main/assets/agents/nativecode-agent`, installed to `/usr/local/bin/nativecode-agent` by `onboarding/AgentConfigInstaller.kt:94-98`, and described to agents in the shared instructions (`:222`).

One observation for the owner: the bridge carries messages from agent to user. There is no command that returns the user's answer to the agent, so "ask" today means "notify and wait in the terminal". If a reply path is wanted it is new work (open question 6). Hermes gets the same instructions file as the other agents (item 3).

---

## 14. Firebase: Analytics and Crashlytics

**Current state.** No Firebase, google-services plugin, Crashlytics or analytics anywhere; no uncaught-exception handler. `app/build.gradle.kts:30-37`: compileSdk 37, minSdk 26, targetSdk 37, `com.zenithblue.nativecode`. AGP 9.3.2. Onboarding page 0 is a privacy screen (`OnboardingActivity.kt:63`, accepted flag `:174-178`). `docs/privacy-policy.md:14` says: "We do not operate an advertising, analytics, or telemetry backend."

**Design.**
1. Add the google-services and Crashlytics Gradle plugins and the Firebase BoM (35.1.0 at the time of writing) with `firebase-analytics`, `firebase-crashlytics` and `firebase-crashlytics-ndk` (native crashes in the JNI libraries; proot itself is a separate process and is not covered).
2. **Off by default.** Manifest: `firebase_analytics_collection_enabled=false`, `firebase_crashlytics_collection_enabled=false`, `google_analytics_adid_collection_enabled=false`. Nothing is sent before a choice is made.
3. **Consent.** The existing privacy page gains two switches, both off: "Send crash reports" and "Share anonymous usage statistics". Existing users see the same sheet once after upgrading. Settings → Privacy shows both switches permanently. On opt-in call `setCrashlyticsCollectionEnabled(true)` / `setAnalyticsCollectionEnabled(true)` and `setConsent` with `ANALYTICS_STORAGE` granted and `AD_STORAGE`, `AD_USER_DATA`, `AD_PERSONALIZATION` denied — the app shows no ads; conversions are measured, users are not profiled for advertising. (Whether Google Ads conversion import needs `AD_USER_DATA` granted for EEA users is UNVERIFIED and decides whether a third switch is needed.)
4. **Events**, through one wrapper `telemetry/Telemetry.kt` (the only file that imports Firebase):

| Event | Fired at | Parameters |
|---|---|---|
| `setup_complete` | `OnboardingActivity.kt:2905` / `MA:14690` | duration bucket, backend (proot/chroot) |
| `first_ai_session` | first allowed pass through `aiLaunchAllowed` (`MA:5987-5991`), once per install | tool id |
| `ai_session_start` | same point, every time | tool id, surface (terminal/chat) |
| `free_limit_hit` | `MA:5977-5981`, `:6000`, `:1043` | which limit |
| `paywall_view` | `showUpgradeSheet` (`MA:6066`) | source |
| `trial_start` / `pro_purchase` | `BillingRepository.kt:132` | sku, is_trial |
| `desktop_start` | `DesktopController.start` | — |
| `component_update` | updater result | id, result |

   No project names, paths, prompts, file contents, account names or tokens, ever. The wrapper accepts only a fixed enum of parameter keys.
5. Funnel in GA4: `first_open` → `setup_complete` → `first_ai_session` → `paywall_view` → `trial_start` → `pro_purchase`. Link the Firebase project to Google Ads and mark `setup_complete`, `first_ai_session` and `pro_purchase` as key events to import as conversions.
6. Crashlytics custom keys: app version, backend, GPU class, component versions. No logs from the terminal.

**Play policy work.** Update the Data safety form (crash logs, diagnostics, app interactions, device or other IDs; collected, not shared for advertising; optional; purposes analytics and app functionality), rewrite `docs/privacy-policy.md:14` and the hosted policy, and add the Firebase SDK disclosure. The exact Data safety categories must be taken from Google's SDK disclosure pages at the time of submission (not fetched for this plan).

**Files.** `build.gradle.kts`, `app/build.gradle.kts`, `app/google-services.json` (owner supplies), `AndroidManifest.xml`, new `telemetry/Telemetry.kt`, `telemetry/ConsentSheet.kt`, `OnboardingActivity.kt:63,174-178`, `billing/BillingRepository.kt:132`, the call sites above, `docs/privacy-policy.md`, `docs/playstore/*`, `legal/OpenSourceLicenses.kt`.

**Gating.** Free and Pro identical.

**Risks.** Opt-in analytics under-reports the funnel; Ads conversion volumes will be lower than installs. A secret or path leaking into a crash key — prevented by the enum wrapper and one unit test that fails on free-form strings.

**Device test.** Fresh install declining both: no network traffic to Firebase hosts (check with a proxy). Accept both: events in DebugView in order; a forced Kotlin crash, a forced native crash and a forced ANR appear in the console. Toggle off in Settings and confirm collection stops.

**Effort.** M.

---

## 15. Backup and restore

**Current state.** `allowBackup="false"` (`AndroidManifest.xml:12`), no data extraction rules, no SAF usage anywhere, no host-side tar or zstd. Guest home is `<filesDir>/usr/var/lib/proot-distro/containers/<distro>/rootfs/home/flux` (`terminal/ProjectPathResolver.kt:19,54`); projects live under it. An uninstall loses everything.

**Design.**
- **Backup.** `ACTION_CREATE_DOCUMENT` → the user picks a location. A guest process on the `COMPAT` profile runs `tar --zstd -cf - -C /home/flux <includes> --exclude-from=<list>` with stdout piped as raw bytes into the document's `OutputStream`; Kotlin computes sha256 while copying. Running tar inside the guest means it sees the file names, modes and links the guest sees, so link2symlink stand-ins need no special handling (UNVERIFIED; covered by the round-trip test).
- A second small document, `<name>.json`, written next to it: format version, app version, date, sha256, byte size, file count, exclusion list, component versions.
- **Scope presets:** "Projects only", "Home (settings, shell history, projects)". Default exclusions, each a checkbox: `node_modules`, `.venv`, `target`, `build`, `.gradle`, `.cache`, `.npm`, `.cargo/registry`, CLI binaries (`.local/lib/node_modules`, `.local/share/claude/versions`, `.opencode/bin`, `.grok/bin`), and — off by default with a warning when enabled — credentials (`.codex/auth.json`, `.claude/.credentials.json`, `.config/gh/hosts.yml`, `.hermes/auth.json`, `.hermes/.env`, `.ssh`).
- A size estimate (`du` with the same exclusions) is shown before starting; progress is bytes written.
- **Restore.** `ACTION_OPEN_DOCUMENT` → pass 1 streams the file to verify sha256 against the sidecar (or shows "no checksum, continue?"); pass 2 pipes into guest `tar --zstd -xf -` into `/home/flux/.restore-<ts>/`. Archive members with absolute paths or `..` are rejected. Then per top-level entry: "Merge (keep existing on conflict)" or "Replace (existing moved to `.replaced-<ts>`)". Refused while AI sessions are open; free space checked first.
- No scheduled backups and no cloud integration; SAF already reaches Drive and other providers.

**Files.** New `backup/BackupService.kt`, `backup/ui/BackupPage.kt`; a raw-stdout variant in `terminal/ShellCommandRunner.kt`; Settings entry.

**Gating.** Free. Losing a user's work on uninstall is not a paywall-worthy risk.

**Risks.** Large homes on slow SAF providers; a provider that fails mid-stream leaves a partial document — detect by the missing sidecar and delete. Restoring credentials from an archive created on another device.

**Device test.** Both devices: back up a home with symlinks, hard links, executables and a 2 GB project to local storage and to a cloud provider; uninstall, reinstall, restore; compare `find -type f | sha256sum` listings and modes. Corrupt one byte: restore refuses. Cancel mid-backup and mid-restore. Low storage.

**Effort.** M.

---

## 16. Built-in code editor

**Current state.** A read-only viewer: `showFileViewer` (`MA:7210`), `buildCodeViewerCard` (`MA:7705`); markdown in a WebView (`MA:8684`).

**Choice.** **Sora Editor** (`io.github.rosemoe`, 0.24.7, 2026-10-17): View-based, so it fits the programmatic-Views app; LGPL-2.1; `editor` 798 KB + `language-textmate` 271 KB + `oniguruma-native` 1.30 MB ≈ 2.4 MB before grammars. LGPL through unmodified AARs is compatible with a closed-source app provided the licence text and attribution ship and the library remains replaceable; add it to `legal/OpenSourceLicenses.kt` (not legal advice — owner to confirm). Rejected: CodeView (MIT, unmaintained since 2023), CodeMirror/Monaco in a WebView (JS bridge, IME problems, Monaco is heavy). Sora's minSdk against the app's 26 is UNVERIFIED.

**Design.** `editor/EditorPage.kt`, opened from the file tree and from the viewer's new EDIT button. Reads and writes the host path directly (no proot). Features for 1.8.0: syntax highlighting for a bundled grammar set (Kotlin, Java, JS/TS/JSX, Python, Go, Rust, C/C++, JSON, YAML, TOML, Markdown, HTML, CSS, shell, Dockerfile — measure the asset size), line numbers, find/replace, undo/redo, save with an unsaved-changes prompt, go to line, soft wrap, font size, the extra-keys bar (item 6) above the keyboard. Files over 2 MB or with binary content open read-only in the old viewer. On save, write to a temp file and rename; if the file changed on disk since it was opened (an agent edited it), offer Reload / Overwrite / Diff. No LSP, no multi-tab, no git gutter.

**Files.** `app/build.gradle.kts`, new `editor/EditorPage.kt`, `editor/Grammars.kt`, `app/src/main/assets/textmate/*`, `MA:7210`, `MA:7705` (button), `legal/OpenSourceLicenses.kt`.

**Gating.** Free.

**Risks.** Agent and human editing the same file — handled by the changed-on-disk check. Grammar assets growing the base module.

**Device test.** Both devices: open, edit and save files of each bundled language; a 1.9 MB file scrolls smoothly on X6P; rotate with unsaved changes; hardware keyboard shortcuts (Ctrl+S, Ctrl+F); an agent modifies the open file; CJK input through the IME.

**Effort.** M.

---

## 17. SSH server and client

**Current state.** No `openssh` or `dropbear` in any `tools/rootfs/packages-*.txt`; no SSH text in skills; no LAN-address code. `BackgroundService` is a foreground service (`AndroidManifest.xml:59-60`).

**Design.**
- Add `openssh` (client, server, `ssh-keygen`) to `packages-alpaquita-base.txt`. Existing users: the SSH page offers `apk add openssh` on first open.
- **Client (Free).** `ssh` works in any terminal tab. A small "Remote hosts" list (name, user@host, port, key file) on the terminal page opens a tab running `ssh`. Keys are generated with `ssh-keygen -t ed25519` into `~/.ssh`; a COPY PUBLIC KEY button. No password storage.
- **Server (Pro).** Settings → SSH server: a switch that starts `sshd -D -e -p 8022 -f ~/.config/nativecode/sshd_config` in the guest as the normal user, kept alive by `BackgroundService` with its own notification line and a Stop action. Config: host keys under `~/.config/nativecode/ssh/`, `PasswordAuthentication no`, `PubkeyAuthentication yes`, `AllowUsers flux`, `ListenAddress` = the Wi-Fi interface address. The page shows the phone's address (`NetworkInterface`), the command to copy (`ssh -p 8022 flux@192.168.x.y`), a field to paste or import a public key into `authorized_keys`, and the host-key fingerprint. Off by default, never auto-started at boot, stops when Wi-Fi is lost unless "Keep running" is on.
- Whether stock `sshd` starts as a non-root user under PRoot without privilege-separation errors is UNVERIFIED; `dropbear` is the fallback with the same UI.

**Files.** `tools/rootfs/packages-alpaquita-base.txt`, new `ssh/SshServerController.kt`, `ssh/ui/SshPage.kt`, `ssh/RemoteHosts.kt`, `BackgroundService.kt`, a new `SSH_SERVER` entry in `entitlements/FeatureGate.kt`.

**Gating.** Client Free; server Pro.

**Risks.** An open port on shared Wi-Fi: key-only auth, bound to one interface, visible notification, a warning on first enable. Android may kill the guest process in the background — same exposure as agents.

**Device test.** Both devices: enable, connect from a PC, run `git status` and `opencode` over SSH; wrong key refused; password refused; toggle Wi-Fi; stop from the notification; client tab to a remote server with a generated key; free user sees the upsell on the server switch.

**Effort.** M.

---

## 18. AI usage dashboard

**Current state.** No session tracking. `FeatureGate.canOpenAiSession` takes a live count computed in `aiLaunchAllowed` (`MA:5987-5991`); nothing is persisted.

**Where the CLIs keep usage** (as read by `ccusage` 20.0.49):

| CLI | Location | Format |
|---|---|---|
| Claude Code | `~/.claude/projects/` (and `~/.config/claude/projects/`) | JSONL, per-message `usage` |
| Codex | `~/.codex` sessions | JSONL / `.jsonl.zst`, `token_count` events |
| opencode 2.x | `~/.local/share/opencode/opencode.db` | SQLite |
| qwen-code | `~/.qwen/**/*.jsonl` | JSONL |
| grok | `~/.grok` `updates.jsonl` | JSONL |
| Hermes | `~/.hermes/state.db` | SQLite, includes recorded cost |
| agy | `~/.gemini/antigravity-cli/conversations/` | SQLite |
| kiro-cli | no known parseable log | — |

**Design.** Do not write eight parsers. Ship the pinned `ccusage` Rust binary (`@ccusage/ccusage-linux-arm64`) as a component and run, on the `CLI` profile, `ccusage <tool> daily --json --offline` and the session report for each installed tool; cache the JSON on the host; render natively. `--offline` uses the embedded LiteLLM price snapshot, so the page makes no network calls; costs are labelled "estimated". Page layout: range switch (today / 7 days / 30 days), total tokens and estimated cost, a per-CLI bar list, then a session list (date, project directory, model, input/output/cache tokens, cost). Refresh on open and on pull; no background polling. Kiro shows "no usage data available". The exact sub-command names and JSON shape of the unified binary must be read from the pinned version (UNVERIFIED).

**Files.** New `usage/UsageRepository.kt`, `usage/ui/UsagePage.kt`; `components.json` entry; Settings/drawer entry.

**Gating.** Free users see opencode; rows for Pro CLIs appear only for Pro (they have no such CLIs otherwise).

**Risks.** `ccusage` output changes between versions — it is pinned, and the parser tolerates missing fields. Reading SQLite files while a CLI writes them (ccusage's concern, but test it). Estimated cost for subscription-plan users is notional; say so on the page.

**Device test.** Both devices: run sessions in opencode, Claude and Codex, open the page, compare totals with each CLI's own usage display; airplane mode; a tool with no history; 30 days of history loads within two seconds after cache.

**Effort.** M.

---

## 19. Quick Settings tile and home-screen widget

**Current state.** Manifest has no `<receiver>`, `TileService`, `AppWidgetProvider` or `shortcuts.xml`. `MA` already handles external extras: `PROJECT_PATH` / `PROJECT_NAME` (`MA:873-881`), `EXTRA_TARGET_PAGE` (`:886-888`), `CREATE_TERM` (`:893`). `SplashActivity` is the only exported activity (`AndroidManifest.xml:31`).

**Design.**
- **Tile "Linux desktop".** A `TileService` whose state mirrors `DesktopController.state`; label shows minutes left for free users. Tap when off: `startActivityAndCollapse(PendingIntent)` into the app with a `start_desktop` extra — starting through the activity avoids background foreground-service-start restrictions and shows the upsell when the budget is out. Tap when on: stop through the controller.
- **Widget 2×1** with two buttons: **Agent** — opens the last project and launches the default agent (opencode for Free; the last-used tool for Pro) through the existing extras plus a new `LAUNCH_TOOL` extra; **Desktop** — same intent as the tile. `RemoteViews`, no periodic updates (`updatePeriodMillis=0`); the desktop button text is refreshed by the controller on state changes.
- Both go through `SplashActivity` → `MA`, so every existing gate applies.
- Static app shortcuts (`shortcuts.xml`): "New terminal", "Last project".

**Files.** New `quick/DesktopTileService.kt`, `quick/NativeCodeWidget.kt`, `res/xml/widget_info.xml`, `res/layout/widget.xml` (widgets require XML layouts), `res/xml/shortcuts.xml`, `AndroidManifest.xml`, `MA:873-908` (two new extras).

**Gating.** Free; the usual limits apply inside the app.

**Risks.** Tile and widget run when the app process is dead; state must come from persisted controller state, not from `MA` fields. Exported intents must not allow another app to pass an arbitrary path or command: only fixed extras, validated.

**Device test.** Both devices (HyperOS and OxygenOS launchers differ): add the tile and widget; start and stop the desktop from each with the app killed; free budget exhausted shows the upsell; Agent button on a fresh install with no project opens the project picker.

**Effort.** S–M. Depends on item 4.

---

## 20. Localisation

**Current state.** `app/src/main/res/values/strings.xml` does not exist; zero string resources. A heuristic count found **694** literals in obvious UI sinks (161 are templates with `$` interpolation) — `MainActivity.kt` 551, `OnboardingActivity.kt` 66, `X11SettingsPage.kt` 35 — and **1,910** multi-word literals overall (831 in `MA`, 224 in onboarding, 93 in `DeviceRequirements.kt`, 80 in `X11SettingsPage.kt`, 70 in `GitHubCliService.kt`, 53 in `CliAuthService.kt`). The real number of user-visible strings is between the two. `supportsRtl="true"` (`AndroidManifest.xml:16`). Labels are often ALL-CAPS with fixed widths. Custom fonts are loaded from assets (`MA:1876`, `:3521`); their CJK and Arabic coverage is UNVERIFIED and probably absent. Store listing: `en-US` only.

**Languages.** Spanish, Portuguese (Brazil), French, German, Italian, Russian, Indonesian, Turkish, Japanese, Korean, Chinese (Simplified), Arabic. No Hindi.

**Plan.**
1. **Rule from chunk 1:** every new user-visible string in 1.8.0 goes into `res/values/strings.xml`. All the new feature files in this plan therefore never add to the backlog.
2. **Not translated, by design:** skill bodies (`AdditionalSkillTexts.kt`, `HardwareSkillTexts.kt` — they are prompts for English-trained agents), agent instruction files, terminal output, log lines, command names, legal licence texts.
3. **Extraction** (the bulk of the effort), file by file in this order: onboarding → Settings and X11 settings → billing and upsell → `cliauth` and `github` services → `DeviceRequirements` and installers → `MA` last. A throwaway script lists candidate literals per file; each is moved by hand because the 161+ templates need positional format arguments (`%1$s`) and plurals need `<plurals>`. Strings built by concatenation are rewritten as one formatted string so translators see whole sentences. Estimate: about 900–1,200 resource entries.
4. **Layout hardening:** replace fixed widths on labelled buttons with `wrap_content` plus min width; allow two lines where German and Russian run 30–40% longer; uppercase through `textAllCaps` instead of literal capitals; use `start`/`end` instead of `left`/`right`; mirror directional icons. Terminal, code editor and diff views stay left-to-right in every locale.
5. **Fonts:** UI text falls back to the system font for scripts the bundled font lacks (verify glyph coverage; for ja, ko, zh and ar simply use the system typeface).
6. **Translation:** machine translation with a glossary of terms that stay in English (terminal, shell, PRoot, Pro, agent names), then one native-speaker review pass per language for the 150 highest-traffic strings (onboarding, paywall, errors). A pseudo-locale build (`en-XA`, `ar-XB`) is checked before translating.
7. **Per-app language** support through `locales_config.xml`, so users can choose the language in system settings.
8. **Store listing** in the same 12 languages (title, short and full description) through fastlane.

**Files.** `res/values/strings.xml`, `res/values-<lang>/strings.xml` × 12, `res/xml/locales_config.xml`, `AndroidManifest.xml`, every UI file (mechanical), `fastlane/metadata/android/<locale>/`.

**Gating.** Free.

**Risks.** This is the largest mechanical change in the release and touches `MA` everywhere, so it runs last, alone, with no other chunk open (section 23). Arabic is the riskiest language (RTL in a UI built from hand-positioned Views); it is in scope, and it is the first thing to cut if the release date is threatened. Unreviewed machine translation of the paywall can read as misleading pricing — those strings get human review first.

**Device test.** Both devices: each language through onboarding, home, workspace, Settings, paywall, a login page, the git page; pseudo-locales for clipping; Arabic for mirroring and for the terminal staying LTR; font scale 1.3 in German and Russian; screenshots of 12 key screens per language reviewed side by side.

**Effort.** L (extraction about 2 weeks, layout and RTL about 1 week, translation and review about 1 week).

---

## 21. Free and Pro placement

| Feature | Free | Pro |
|---|---|---|
| opencode 2.2.7 terminal and chat (beta) | Yes, 1 AI session | Multiple sessions |
| Codex, Claude Code, Qwen, agy, Grok, Kiro, Hermes (terminal) | Upsell | Yes |
| Chat (beta) for Claude and Codex | Upsell | Yes |
| CLI login pages | opencode page | All tools, GitHub |
| Desktop: pill, notification, tile, widget | 30 min/day, minutes shown | Unlimited |
| Top bar stats, tool rail, extra keys | Yes | Yes |
| Git history, checkout, create branch | With the existing workspace gate | Yes |
| Dev-server preview | Yes | Yes |
| Code editor | Yes | Yes |
| Backup and restore | Yes | Yes |
| SSH client and saved hosts | Yes | Yes |
| SSH server | Upsell | Yes |
| Usage dashboard | opencode | All CLIs |
| REA skill, all skills | Yes | Yes |
| Component updates (opencode, gh, drivers) | Yes | Yes |
| Pro CLI and Hermes packs | Not downloaded | On demand |
| GPU drivers, PRoot profiles, experimental engine | Yes | Yes |
| Languages, crash reporting | Yes | Yes |
| Free trial | Offered once per Play account | — |

New gates to add to `entitlements/FeatureGate.kt`: `SSH_SERVER`. Everything else reuses `canLaunchAiTool`, `canOpenAiSession`, `canStartXfce`, `WORKSPACE`, `GITHUB_LOGIN`. A unit-test matrix covers Free and Pro for every new entry point (pill, notification action, tile, widget, chat tab, login page, pack download, SSH switch).

---

## 22. Implementation order

All chunks land on `feature/monetisation-pr1` in this order, each reviewable on its own and each leaving the app shippable. "MA regions" lists what a chunk touches in `MainActivity.kt` so reviewers can see overlaps.

| # | Chunk | Items | MA regions touched | Depends on |
|---|-------|-------|--------------------|------------|
| 0 | Console and spikes, no app code: Play pack policy and size cap, trial offers, Firebase project, opencode 2.x scratch-rootfs spike, Claude control-channel spike, Hermes build-and-measure, `sshd` under PRoot | 3, 9, 11, 14, 17 | — | — |
| 1 | Foundations: `strings.xml` and the resources rule, `RecyclerView` dependency, raw-stdout and non-PTY launch in `ShellCommandRunner`/`LinuxCommandBuilder`, bench harness ported, baseline results committed | 7E, 20.1 | none | — |
| 2 | Telemetry: Firebase, consent, event wrapper, privacy policy text | 14 | call sites only | 0 |
| 3 | Free trial | 11 | 6020, 6066–6095 | 0, 2 |
| 4 | Workspace top bar and tool rail | 5 | 21322–21420, 3516–3600, 4756–4850 | 1 |
| 5 | Desktop controller, pill, notification actions | 4 | 9506–9700, 1861, 14860–15080 | 4 |
| 6 | Extra keys | 6 | 4309–4700, 21400 | 4 |
| 7 | PRoot profiles, git settings, host-side reads | 7A–D | none (builders) | 1 |
| 8 | Rootfs rebuild and component build: versions, opencode 2.2.7, Hermes, drivers and GPU env, openssh, procps, ccusage, gitconfig; new packs; `RootfsSource` pin | 3, 8, parts of 1, 17, 18 | none | 0, 7 |
| 9 | Component updater, Components page, existing-user update flow; remove `panvk-rc3` assets | 3, 8 | 5189–5260 | 8 |
| — | **Cut line.** Everything above is a coherent release on its own if needed. | | | |
| 10 | CLI login pages; Hermes in launchers | 2, 3 | 17339–17429, 18878–19100 | 9 |
| 11 | Git history | 10 | 22424–22460, 21667–21916, 4869–4877 | 1, 7 |
| 12 | Chat beta: opencode (ACP) | 9 | 20914, 5934–5991 | 8, 10 |
| 13 | Chat beta: Codex and Claude | 9 | none new | 12 |
| 14 | Dev-server preview | 12 | tab registration | 1 |
| 15 | Code editor | 16 | 7210, 7705 | 6 |
| 16 | Backup and restore | 15 | Settings entry | 1 |
| 17 | SSH | 17 | Settings entry | 8 |
| 18 | Usage dashboard | 18 | drawer entry | 9 |
| 19 | Tile, widget, shortcuts | 19 | 873–908 | 5 |
| 20 | REA skill and skill text updates (Hermes, SSH, opencode 2.x notes) | 1 | none | 8 |
| 21 | Experimental PRoot engine (stretch) | 7F | Settings entry | 7 |
| 22 | Localisation: extraction, layout hardening, translations, store listing | 20 | everything, mechanically | all others merged |
| 23 | Release hardening: full device matrix, benchmarks, Data safety form, changelog, version 1.8.0 (17) | — | version lines | all |

---

## 23. `MainActivity.kt` conflict strategy

The file is 22,903 lines and ten of these items want to edit it.

1. **New code never goes into `MA`.** Every item above creates its own package and files; `MA` receives a call site of a few lines (construct the page, hand it a small interface for navigation and theme helpers). The target is that `MA` is *smaller* at the end of 1.8.0: items 4, 5, 6, 2 and 9 each delete more from it than they add.
2. **Move, do not rewrite.** When logic leaves `MA` (desktop controller, top bar, extra keys, CLI auth UI) the first commit of the chunk is a pure move with no behaviour change, so the diff reviews as a move; the second commit changes behaviour.
3. **Serial, not parallel, on overlapping regions.** The table in section 22 lists the regions. Chunks 4, 5 and 6 overlap around `MA:4300-4850` and `MA:21322-21420` and must merge in that order. Chunks 10, 11 and 12 touch disjoint regions and may be developed concurrently.
4. **No drive-by reformatting or reordering** in `MA` in any chunk. Line anchors in this document are valid for commit `8324007`; each chunk re-locates its anchors by function name, not by number.
5. **One shared interface** (`AppHost`: navigate, theme colours, dp helper, executor, main handler, entitlement state) is introduced in chunk 1 and passed to extracted pages, so extracted code does not reach back into `MA` fields.
6. **Localisation last and alone.** The string extraction touches hundreds of lines across `MA`; it starts only when every other chunk has merged, and nothing else is open while it runs.
7. Work is linear on one branch (no merges between long-lived branches), so conflicts can only come from concurrent chunks, which rule 3 limits.

---

## 24. Asset-pack size budget

Limits (Play, compressed download): 1.5 GB per asset pack; 4 GB for all modules plus install-time packs; 30 GB cumulative on-demand and fast-follow; 500 MB base module.

| Pack | Today | 1.8.0 target | Hard ceiling | Notes |
|---|---|---|---|---|
| `nativecode_runtime` | 1,356 MB | ≤ 1,300 MB | 1,430 MB | Pro CLIs move out; opencode 2.2.7 (+120 MB vs its 1.x tree, unmeasured), PanVK (+12 MB), openssh, vulkan-tools, ccusage come in |
| `nativecode_update_core` | — | ≈ 180 MB | 250 MB | existing users only |
| `nativecode_clis` | — | 500–800 MB (ESTIMATE) | 1,200 MB | split in two packs if over |
| `nativecode_hermes` | — | 250–450 MB (ESTIMATE) | 600 MB | trim optional extras (browser, voice) if over |
| `nativecode_android_dev` | 69 MB | 69 MB | — | unchanged |
| Base APK/AAB | — | −22 MB (PanVK asset out) + 2.4 MB Sora + grammars + Firebase (≈ 2–3 MB) | 500 MB | far below the cap |

Rules:
1. `tools/rootfs/measure_rootfs.sh` runs before and after each addition; the delta goes in the chunk description.
2. `verify_rootfs.sh` fails the build if any pack exceeds its hard ceiling.
3. If `nativecode_runtime` cannot get under 1,430 MB even with the Pro CLIs removed, the next things to move out are the Android dev toolchain pieces still in the base and the GUI build set's static libraries and headers.
4. Free users download `nativecode_runtime` only. Pro users download up to about 2.5 GB in total across packs; the Components page shows sizes before each download.

---

## 25. Testing strategy

- **JVM unit tests** for every pure function: lane assignment (linear, branch, merge, octopus, criss-cross), log record parsing (short and malformed records), the three chat adapters by fixture replay, auth status parsers (one sample per CLI per pinned version), component manifest verification and the apply state machine (with a fake filesystem, including a crash at every step), offer-token choice, extra-keys JSON validation, port regex, backup path validation, the feature-gate matrix.
- **Rootfs checks** in `verify_rootfs.sh`: every pinned version equals `versions.env`; glibc ≥ 2.39; no musl binaries; each component's smoke command passes inside a proot run on the build host; pack sizes under ceiling; sha256 values in `versions.env`, `components.json` and `RootfsSource.kt` agree.
- **Benchmarks**: the ported `termux-lib` harness on both devices at chunk 1 (baseline), chunk 7 (profiles and git settings) and chunk 21 (engine), results committed under `docs/results/`.
- **Device matrix** at chunks 9, 13 and 23: OP13R and X6P × fresh install and upgrade from 1.7.1 with data × Free and Pro. The per-item test lists are the script. The GPU gate in item 8 is run on X6P before chunk 8 is accepted.
- **Gating regression**: a checklist run confirming a free user cannot reach a Pro tool, a second AI session, more than 30 desktop minutes, the SSH server, or a Pro pack download through any new entry point.
- **Privacy check**: with both consent switches off, a proxy capture shows no Firebase traffic; with them on, event parameters contain no paths, names or tokens.
- **Play pre-launch report** and an internal-track rollout before production; staged rollout at 5% → 20% → 100% with Crashlytics watched between steps.

---

## 26. Risks, open questions, unverified claims

### Top risks
1. **Scope.** Twenty items in one release, roughly 16–20 developer-weeks. The cut line after chunk 9 and the stretch markers exist so the date can hold.
2. **opencode 2.x is the only free tool** and several of its behaviours under PRoot are unconfirmed (background service, auth path, self-update). The spike in chunk 0 is a release gate.
3. **PanVK 26.3.1 glibc build has never run on hardware upstream.** X6P acceptance decides whether it ships; rc3-lowmem is the fallback.
4. **Pack layout departs from "inside the rootfs"** for Hermes and the Pro CLIs because of the 1.5 GB cap. If the owner rejects it, Hermes still needs its own pack and the runtime pack may not fit.
5. **Updating a live rootfs.** A failed or interrupted update must leave a working install; the staged, rename-only commit and the fake-filesystem tests are the mitigation.
6. **`--link2symlink` removal on the CLI profile** has only a microbenchmark behind it (+8.5%) and no package-manager regression run. It ships with a COMPAT profile and can be turned off by one constant.
7. **Claude inline approvals** depend on an undocumented control channel; the fallback is a reduced Claude chat.
8. **Localisation** is a mechanical edit across the whole app at the end of the release, with Arabic the riskiest part.
9. **Analytics consent** reduces measured conversions; Ads optimisation will work on partial data.
10. **`MainActivity.kt`** remains the conflict hotspot until the extractions land.

### Open questions for the owner
1. Hermes and the Pro CLIs as separate Play packs unpacked into the rootfs (section 3.4): accepted? If not, which contents should leave the runtime pack to make room?
2. Existing Pro users: keep their current self-updating CLIs until they choose the pinned pack (planned), or prompt them to switch at upgrade?
3. Free-trial length, and on which plans (monthly, yearly or both)?
4. Should analytics consent include an advertising-measurement switch (`AD_USER_DATA`) for EEA users, or is analytics-only consent enough for your Ads setup?
5. `PANVK_BC_MODE=lowmem` covers BC texture emulation only. Is that the low-mem mode you meant, or is there a separate memory-budget variable (`PANVK_MEM_*` names exist in the binary) that should also be set?
6. The agent bridge can notify the user but cannot return an answer to the agent. Is that sufficient, or is a reply command wanted in 1.8.0?
7. Extra keys: is dropping ENT and BKSP from the default acceptable with a Classic preset kept for one release?
8. SSH server as Pro, and backup/restore, editor and preview as Free: agreed?
9. LGPL-2.1 for Sora Editor and GPLv3 for Termux `ExtraKeysView` code: both acceptable for the Play build's licence position?
10. Experimental PRoot engine (U11): ship it default-off in 1.8.0, or hold it for the unrun large suite?
11. Who reviews translations, and is Arabic required for the 1.8.0 date?
12. Play Console access is needed for the pack policy check, trial offers and the Data safety form — who does chunk 0's console work?

### Claims not verified
- Play: whether the 1.5 GB cap is decimal or binary; that extra on-demand packs may carry native executables (the runtime pack already does); the limit on the number of asset packs.
- opencode 2.x: auth file path, `auth list|logout`, self-update switch, default `serve` port, behaviour of the shared service under PRoot, the size of the 1.x tree it replaces.
- Self-update switches for codex, agy, kiro, grok and Hermes; `DISABLE_AUTOUPDATER` for Claude.
- Hermes: installed and compressed size, relocation into `/opt/hermes`, start-up time and behaviour under PRoot, `hermes auth list` exit codes.
- Sizes of `nativecode_clis` and of the rebuilt runtime pack (estimates until built).
- agy: credential storage under PRoot, logout command. grok and kiro credential file paths.
- PRoot: whether NC-PLAY's launch path still pays the 816 ms wrapper cost; hard-link, `apk` and `dpkg` behaviour without `--link2symlink`; gains from the git settings, Node and Python environment changes (none measured); whether the U11 binary exists in `termux-lib/out/` or must be rebuilt.
- PanVK: that low-mem is on by default in 26.3.1 (release notes and strings only); the BC readback caveat in 26.3.1; meaning of the `PANVK_MEM_*` variables; required `mali_kbase` versions.
- Turnip: effect of the present wait fence on Termux-X11 pacing; whether zink over Turnip now works under PRoot.
- Chat: Codex `turn/interrupt` and delta notification names; Claude stream-json message shapes, interrupt and control-channel wire names; that `opencode acp` in 2.2.7 behaves as documented under PRoot.
- `ccusage` 20.0.49: sub-command names, JSON shape, per-session report, and its handling of the opencode 2.x database.
- Firebase: exact Data safety categories, ANR minimum Android version, consent requirements for Ads conversion import (taken from memory, not from fetched pages); current plugin versions beyond BoM 35.1.0.
- Android: `/proc/net/tcp` unreadable on both test devices (general knowledge, not tested); tile `startActivityAndCollapse(PendingIntent)` requirement on Android 14+; GPU load sysfs readability; what `CpuUtilizationProvider` falls back to.
- Sora Editor minSdk; grammar asset sizes; LGPL compliance details.
- `sshd` as a non-root user under PRoot.
- Whether `termux-shared` (`ExtraKeysView`) is on the app's classpath; which newline-without-submit sequence each CLI accepts.
- Backup: that guest `tar` round-trips link2symlink hard links without special handling.
- Localisation: the true count of user-visible strings (between 694 and 1,910); glyph coverage of the bundled fonts.
- REA: PTY prebuild coverage for linux-arm64 glibc.
- All `MA` line anchors are from commit `8324007` and were carried over from the previous plan without being re-checked line by line.
