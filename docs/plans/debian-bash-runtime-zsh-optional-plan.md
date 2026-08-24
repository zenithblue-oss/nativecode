# Plan: debian-bash-runtime-zsh-optional

**PLAN_STATUS:** READY
**PASS:** 4 (revised after plan-reviewer REVISE ×2 + external REVISE)
**SCOPE:** PRoot, Chroot, CLI tool launchers, CLI auth/probes, Repairs, interactive Debian shells, future PRoot-fast path
**PRIMARY CONTRACT:** `/bin/bash` is required runtime infrastructure; `/bin/zsh` is optional customization.

## Task Summary

NativeCode currently couples internal guest execution to zsh even though zsh is only installed by the optional Debian Customization stage. The fix is **not to install zsh earlier**; it is to make **Bash the guaranteed Debian runtime shell** and keep **zsh as an optional interactive customization**. Bash = runtime ABI; zsh = optional UX.

## Research Sources

- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt:18,29 — non-interactive wrap `-- zsh -c '$escaped'`>
- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt:30 — `LAUNCH_TOOL_VERSION = "nativecode-launch-tool v2"`; :249 — `#!/bin/zsh` launcher shebang; :58 — `login --user flux --shell zsh`; :300 — `toolEnvInit()`; :316 — `else -> "zsh"` in `toolBinaryName()`; :176-179 — `parseInteractiveWorkdir` regex on `exec zsh`>
- <source: app/src/main/java/com/zenithblue/nativecode/cliauth/CliGuestCommands.kt:25-47 — `pathBootstrap()` contains `setopt NULL_GLOB 2>/dev/null || setopt nonomatch` and zsh-quoting comments>
- <source: app/src/main/assets/scripts/setup_debian_family.sh:16 — `useradd -m -s /bin/bash flux`; no zsh install>
- <source: app/src/main/assets/scripts/setup_customization_debian.sh:725-834 — installs zsh, Oh My Zsh, `.zshrc`, `chsh -s /bin/zsh flux`>
- <source: app/src/main/assets/scripts/setup_cli_tools.sh:111-112,185-191,326-331 — wires `.zshenv/.zprofile/.zshrc/.bashrc`, calls `.zshenv` critical for non-interactive `zsh -c`>
- <source: app/src/main/assets/scripts/chroot/nativecode_chroot.sh:25,409 — `LOGIN_SHELL="${NC_SHELL:-zsh}"`; :264-293 explicit zsh branches>
- <source: app/src/main/assets/scripts/chroot/setup_debian13_chroot.sh:271 — chroot user created with `-s /bin/bash`; :417 — `exec sh "$HELPER" login --user flux --shell zsh`>
- <source: app/src/main/assets/scripts/nativecode_proot_fast.sh:22 — `LOGIN_SHELL="${NC_SHELL:-zsh}"`; :235 — PATH resolution avoids loading zsh; :347-356 — non-interactive string mode `/bin/sh -c` at :356; :360 — `zsh) SH_BIN=/bin/zsh`>
- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt:12-14,70-73 — interactive detection via `exec zsh` regex; :135 — `else "/bin/zsh"` default shell bin; :143 — `/bin/sh -c` non-interactive>
- <source: app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt:19-20 — `CHROOT_HELPER_VERSION = "nativecode-chroot v2.2"` must match helper stamp; :112-118 — `ensureHelperScript()` version-gated restage; :286 — `ensureLauncherScript()` content-diff rewrite>
- <source: app/src/main/assets/scripts/chroot/nativecode_chroot.sh:20 — `VERSION_STR="nativecode-chroot v2.2"`; :184 — bridge copies launch_tool via `cp -f` unconditionally; :303-317 — chroot_guest repairs already use `/bin/bash --noprofile --norc -c`>
- <source: MainActivity.kt:5600-5688 — repair runner `runScriptInTerminal` 4-mode dispatch, proot wraps `bash /tmp/<script>` via LinuxCommandBuilder.build; :2051,:18339 — only consumers of `exec zsh` strings>
- <source: CliAuthService.kt:861; GitHubCliService.kt:1029 — auth/status/login/logout route through `LinuxCommandBuilder.build`>
- <source: app/src/main/java/com/zenithblue/nativecode/cliauth/ToolLauncherCatalog.kt:16-30 — all catalog tool types covered by known `toolBinaryName` cases; else→zsh has zero live callers>
- <source: repo scan — no `app/src/test` source set exists and no `testImplementation`/`junit` in app gradle files>
- <source: ProotCommandBuilder.kt:18-22 — interactive markers already emit bare `proot-distro login debian --user <user>` (passwd-shell semantics); :26 — host-side `'`→`'\''` escaping layer>
- <source: LinuxCommandBuilder.kt:13-14 — `sessionUserForType`: `shell-root` → `root`, else `flux`; MainActivity.kt:18337-18340 — workspace gives root null workdir, passes user into builder>
- <source: setup_debian_family.sh:16 + setup_customization_debian.sh:826,830-831 — guest `/etc/passwd` is SSOT of configured login shell (base `-s /bin/bash`; customization `chsh -s /bin/zsh` + direct-passwd fallback)>
- <source: nativecode_chroot.sh:11,:42 — both usage strings `[--shell zsh|bash]`; :22 — `NC_CHROOT` rootfs var for `$NC_CHROOT/etc/passwd` resolution; :289 — `zsh|*)` catch-all defaults flux to zsh today>
- <source: docs/environment/plans/suite/nativecode_proot_fast.sh:22 — maintained mirror with same zsh-default behavior>
- <source: docs/plan/terminal-auto-resize.md:275,438 — shipped `launch_tool.sh` = nativecode-launch-tool v2>

## Current Architecture

- `LinuxCommandBuilder.build()` (LinuxCommandBuilder.kt:16) dispatches to backend builders (`ProotCommandBuilder`, `ChrootCommandBuilder`).
- Interactive vs non-interactive sessions are distinguished by **command-string markers** (`exec zsh`, `mkdir -p DIR && cd DIR && exec zsh`) parsed by regex in each builder.
- The shared AI CLI launcher `launch_tool.sh` is generated by `ChrootCommandBuilder.ensureLauncherScript()` with a `#!/bin/zsh` shebang (v2).
- Debian base setup creates user `flux` with `-s /bin/bash` and does NOT install zsh (setup_debian_family.sh:16). zsh + Oh My Zsh + `chsh -s /bin/zsh` happen later in setup_customization_debian.sh:725-834 → zsh is a customization-stage dependency, not guaranteed in fresh rootfs.
- Repairs construct inner `bash ...` commands but PRoot wraps them via `ProotCommandBuilder` outer `zsh -c` (ProotCommandBuilder.kt:29), so repairs fail on un-customized rootfs.

### Confirmed current problem matrix

| Area | Current problem | Evidence |
| --- | --- | --- |
| PRoot arbitrary guest command | `ProotCommandBuilder` wraps every non-interactive command with `zsh -c` | ProotCommandBuilder.kt:29 |
| CLI tool launcher | generated `/tmp/launch_tool.sh` has `#!/bin/zsh` | ChrootCommandBuilder.kt:249 |
| Interactive Chroot | requests `--shell zsh` | ChrootCommandBuilder.kt:58; setup_debian13_chroot.sh:417 |
| Chroot helper | defaults `LOGIN_SHELL` to `zsh` | nativecode_chroot.sh:25,409 |
| CLI auth/status probes | bootstrap contains zsh-only `setopt` | CliGuestCommands.kt:33 |
| PRoot-fast script | defaults interactive shell to zsh | nativecode_proot_fast.sh:22 |
| `ProotFastCommandBuilder` | maps normal interactive sessions to `/bin/zsh` | ProotFastCommandBuilder.kt:135 |
| Repairs in PRoot | command says `bash script`, but wrapped by `zsh -c` | ProotCommandBuilder.kt:29 |

Most direct bug:

```text
proot-distro ... --user USER -- zsh -c 'payload'
```

Even `bash setup_debian_family.sh` cannot start if `/bin/zsh` does not exist.

Second independent problem: `ensureLauncherScript()` emits `#!/bin/zsh` (ChrootCommandBuilder.kt:249), so `/tmp/launch_tool.sh opencode|claude|...` require zsh despite needing nothing zsh-specific. Same builder emits `exec zsh` for interactive sessions (ChrootCommandBuilder.kt:330).

This is an **execution-contract bug**, not a couple of string replacements.

## New Shell Contract

| Execution class | Required shell | Behavior |
| --- | --- | --- |
| Non-interactive internal command | `/bin/bash` | deterministic, no user shell dependency |
| AI CLI launcher | `/bin/bash` | explicit PATH/env bootstrap then `exec TOOL` |
| Repair/setup scripts | `/bin/bash` | matches actual script shebangs/features |
| Interactive Debian shell | configured login shell | read user's shell from guest `/etc/passwd`; validate executable; Bash fallback. **Root interactive is always `/bin/bash`.** zsh is used because the user is *configured* for zsh (customization `chsh`), not merely because the package is installed |
| Customization | zsh | optional enhancement only; sets `/etc/passwd` via `chsh -s /bin/zsh` (setup_customization_debian.sh:826,830) |

Lifecycle after fix:

```text
Fresh rootfs → Bash works → Debian setup works → CLI setup works → CLI tools work
→ Repairs work → customization optionally installs zsh → interactive terminal switches to zsh
```

Installing/removing zsh must have **zero effect** on CLI launch, CLI authentication, package management, Repairs, or background guest commands.

## Affected Components & Dependencies

- Kotlin terminal layer: ProotCommandBuilder, ChrootCommandBuilder, LinuxCommandBuilder, ProotFastCommandBuilder
- CLI auth layer: CliGuestCommands
- Assets scripts: setup_cli_tools.sh, setup_customization_debian.sh, nativecode_chroot.sh, setup_debian13_chroot.sh, nativecode_proot_fast.sh
- Call sites that generate `exec zsh` / workspace-shell commands (terminal session launching)

## Implementation Steps (ordered, smallest correct change)

### PR1 — Remove zsh from mandatory execution paths

#### 3.1 `ProotCommandBuilder.kt` (P0)

For non-interactive `shellCmd`, change:

```text
... -- zsh -c '<payload>'
```

to approximately:

```text
... -- /bin/bash --noprofile --norc -c '<payload>'
```

Keep existing single-quote escaping so host does not expand guest `$HOME`/`$PATH`.

Do **not** use `bash -l`: internal commands must not depend on `.profile`, `.bashrc`, `.zshrc`, Oh My Zsh, fastfetch, or user config.

This alone fixes the PRoot Repair failure:

```text
proot-distro ... -- /bin/bash --noprofile --norc -c 'bash setup_debian_family.sh'
```

#### 3.2 Replace `"exec zsh"` protocol with `buildInteractive()`

Replace command-string markers with an explicit interactive entry point:

```kotlin
LinuxCommandBuilder.buildInteractive(
    ctx,
    user = user,          // "flux" | "root" — from LinuxCommandBuilder.sessionUserForType(type)
    workDir = workDir,    // null = user home login
    method = method
)
```

Retain `LinuxCommandBuilder.build(...)` for non-interactive strings. Backends implement interactive startup without inspecting `"exec zsh"`. This allows deleting/refactoring `parseInteractiveWorkdir()` (ChrootCommandBuilder.kt:176, ProotFastCommandBuilder.kt:12).

**User contract (explicit):**

| user | shell policy | workDir |
| --- | --- | --- |
| `flux` | configured login shell from guest `/etc/passwd`, validated executable, `/bin/bash` fallback | optional workspace cwd |
| `root` | always `/bin/bash` | current app contract passes `null` (MainActivity.kt:18337-18338 — root gets no project workdir to avoid root-owned project files); builder must still tolerate non-null by cd'ing as root |

**PRoot backend interactive emission (explicit spec).** Today's interactive path already emits a **bare** login (ProotCommandBuilder.kt:18-22) — preserve it; bare `proot-distro login` starts the user's configured `/etc/passwd` login shell natively:

```text
buildInteractive(ctx, user, workDir=null, method=proot):
  exec python proot-distro login debian [--shared-tmp] --user <user>
  # passwd-shell semantics for free; no zsh logic anywhere

buildInteractive(ctx, user=flux, workDir=<path>, method=proot):
  exec python proot-distro login debian --shared-tmp --user flux -- \
    /bin/bash --noprofile --norc -c \
    'mkdir -p -- "$1" && cd -- "$1" &&
     sh=$(awk -F: -v u="$(id -un)" '"'"'$1==u {print $7; exit}'"'"' /etc/passwd 2>/dev/null)
     case "$sh" in /bin/bash|/bin/zsh) [ -x "$sh" ] || sh=/bin/bash ;; *) sh=/bin/bash ;; esac
     exec "$sh" -l' \
    nativecode-interactive '<workDir>'

buildInteractive(ctx, user=root, ...):
  same shape with resolved shell forced to /bin/bash
```

Rules:
- **workDir is a positional argv (`$1`), never interpolated into the script body.** Exactly one quoting layer exists — the host-side single-quote escaping of the outer command string (same `'` → `'\''` mechanism as today, ProotCommandBuilder.kt:26). Guest body references `$1` only, so spaces/quotes/`$`/backticks in project paths are safe. Do **not** implement `cd $workDir && ...`. The payload shown above is the **assembled host command**, not the Kotlin string literal — in Kotlin, write the awk program with raw single quotes and let the existing `replace("'","'\\''")` layer do the escaping; do not hand-embed `'"'"'`.
- **Shell lookup uses `$(id -un)`, not `$USER`** — proot-distro may not export USER for the target user; unset would silently fall back to Bash on the workspace path. Guest-side awk is fine here (Debian ships mawk; this payload runs inside the guest, unlike the chroot helper which runs on the Android host).
- **workDir == null → bare login** (no guest wrapper). Do not introduce `proot-distro login -- bash -c 'exec ...'` for this case; that would take over login-shell startup `proot-distro` already handles.
- The zero-zsh-token static check applies to **non-interactive execution paths only**; interactive auto-resolution referencing zsh is explicitly allowed per the "Allowed zsh references" clause.

**Chroot backend interactive emission:** `login --user <u> --shell auto [--workdir <q>]`; helper resolves per §Interactive-Chroot below.

**MainActivity branching (required change):** both call sites (MainActivity.kt:2051 startNewTerminalSession, :18339 startWorkspaceTerminalTab) currently route **all** types through `buildToolShellCommand(...)` → `LinuxCommandBuilder.build(...)`. Split explicitly:

```kotlin
if (type == "shell" || type == "shell-root") {
    val argsEnv = LinuxCommandBuilder.buildInteractive(
        this,
        user = LinuxCommandBuilder.sessionUserForType(type),
        workDir = workDir,   // MainActivity.kt:18338: null for shell-root
        method = method
    )
} else {
    val command = ChrootCommandBuilder.buildToolShellCommand(this, type, workDir)
    LinuxCommandBuilder.build(this, command)
}
```

Then make `buildToolShellCommand()` **tool-only**: reject `shell`/`shell-root` (throw/require) instead of retaining the compatibility branch at ChrootCommandBuilder.kt:325-326.

**No legacy shim:** legacy `"exec zsh"` compat is removed in the same PR. Reviewer-verified nil persisted-state risk — sessions are in-memory (`sessionsList` in MainActivity), sole emitter is `buildToolShellCommand` (ChrootCommandBuilder.kt:330,333) and both consumers (MainActivity.kt:2051,:18339) are updated here; no other code generates or parses the marker after this PR set.

### PR1 — Make the AI CLI launcher Bash-only

`ChrootCommandBuilder.ensureLauncherScript()`: change shebang at line 249 from `#!/bin/zsh` to `#!/bin/bash`. Body already Bourne/Bash-compatible. Keep: explicit `HOME=/home/flux`, Debian PATH, `.local/bin`, Cargo bin, `/opt/nodejs/bin`, `NVM_DIR`, source `cli-tools.env`, latest installed Node fallback, `command -v`, final `exec "$@"`.

The launcher deliberately must **not** source `.zshrc`/`.bashrc`.

Bump `LAUNCH_TOOL_VERSION` (ChrootCommandBuilder.kt:30):

```text
nativecode-launch-tool v2 → v3
```

Propagation mechanics (correct rationale): installed copies are rewritten by **content diff** — `ensureLauncherScript()` rewrites whenever generated text differs (ChrootCommandBuilder.kt:286), and the helper bridge copies via `cp -f` on every mount (nativecode_chroot.sh:184). The version bump is hygiene so the stamp stays truthful, not the rewrite mechanism; do not add a spurious version-check path.

### PR1 — Interactive Chroot becomes `auto`, not `zsh`

Change helper contract (`nativecode_chroot.sh`) to:

```text
--shell auto|bash|zsh   (NC_SHELL default = auto)
```

**Change BOTH default assignments** — the helper initializes the default twice and only changing one would be silently overridden at dispatch:

```text
line 25:  LOGIN_SHELL="${NC_SHELL:-zsh}"  →  LOGIN_SHELL="${NC_SHELL:-auto}"
line 409: LOGIN_SHELL="${NC_SHELL:-zsh}"  →  LOGIN_SHELL="${NC_SHELL:-auto}"
```

Update **both usage strings** (`[--shell zsh|bash]` → `[--shell auto|bash|zsh]`) at nativecode_chroot.sh:11 (header comment) and :42 (usage heredoc), plus env doc line :17.

Resolution inside rootfs — **`auto` means the user's configured login shell, not "zsh binary exists"**. The SSOT is guest `/etc/passwd`: base setup creates flux with Bash (`useradd -m -s /bin/bash flux`, setup_debian_family.sh:16) and customization later flips it via `chsh -s /bin/zsh` (setup_customization_debian.sh:826; direct-passwd fallback :830). Helper runs as host root, so resolve from `$NC_CHROOT/etc/passwd` (nativecode_chroot.sh:22):

```text
requested=auto:
  user=root:
    always /bin/bash
  user=flux:
    # helper runs on Android HOST under /system/bin/sh — bare awk NOT guaranteed
    # (toybox omits it); busybox IS guaranteed by resolve_bb() (nativecode_chroot.sh:54-83)
    configured=$("$BB" awk -F: -v u="$USER_NAME" '$1==u{print $7; exit}' "$NC_CHROOT/etc/passwd" 2>/dev/null)
    [ -n "$configured" ] || configured=$(grep "^${USER_NAME}:" "$NC_CHROOT/etc/passwd" 2>/dev/null | cut -d: -f7)
    case configured in
      /bin/zsh|/usr/bin/zsh) [ -x "$NC_CHROOT$configured" ] && use zsh || WARN+fallback /bin/bash ;;
      *)                     WARN if non-empty+unrecognized; use /bin/bash ;;
    esac

requested=zsh missing → WARN → /bin/bash (never kill terminal)
```

Rationale: executable-existence auto ("apt install zsh but customization not run") would wrongly switch flux to zsh while `/etc/passwd` still says Bash. With passwd-based auto, `apt install zsh` alone changes nothing — exactly the product contract. Rewrite the current resolution branches (nativecode_chroot.sh:262-298), which today default flux to `su -s /bin/zsh` via the `zsh|*)` catch-all (:289).

Change callers from `login --user flux --shell zsh` (ChrootCommandBuilder.kt:58, setup_debian13_chroot.sh:417) to `--shell auto`. Root interactive shells are pinned to Bash on both sides (ChrootCommandBuilder.kt:54 already emits `--shell bash`; keep).

**Helper version bump (upgrade-path requirement):** helper deployment is version-gated — `ensureHelperScript()` restages from assets only when the installed stamp differs (ChrootCommandBuilder.kt:112-118; const at :19-20, `VERSION_STR` in nativecode_chroot.sh:20). Bump **all three** in lockstep so the gate converges (const must match asset header line 2):

```text
nativecode-chroot v2.2 → v2.3
```

1. `CHROOT_HELPER_VERSION` const — ChrootCommandBuilder.kt:20
2. `VERSION_STR` variable — nativecode_chroot.sh:20
3. Asset header stamp comment — nativecode_chroot.sh:2 (`# nativecode-chroot v2.2`)

Without this, upgraded devices silently keep the old zsh-default v2.2 helper while fresh installs pass tests; mismatched stamps would re-stage via su on every build() call.

Result: before customization Terminal→Bash; after customization Terminal→zsh; AI cards stay Bash-backed in both cases.

### PR2 — Remove zsh assumptions from CLI auth/status

Clean `CliGuestCommands.pathBootstrap()` (CliGuestCommands.kt:31-47): remove `setopt NULL_GLOB` / `nonomatch` and zsh comments; NVM discovery already uses `find`. Make it shell-neutral/Bash-safe:

```text
export HOME=/home/flux
export USER=flux
export PATH=...
export NVM_DIR=...
if [ -d "$NVM_DIR/versions/node" ]; then find/sort latest version; prepend its bin; fi
source cli-tools.env if present
source cli-auth.env if present
```

Update comments to state snippets execute under the **NativeCode non-interactive Bash contract**. Login/status/logout flows all route through `LinuxCommandBuilder.build()`; leaving auth probes zsh-oriented would create a second failure mode.

### PR2 — `setup_cli_tools.sh` treats zsh as optional

Policy change for startup-file section (setup_cli_tools.sh:111-191,326-331):

Mandatory wiring:

```text
~/.bashrc
~/.profile
/etc/profile.d/flux-cli-tools.sh
~/.config/fluxlinux/cli-tools.env
```

Optional zsh wiring — only touch `~/.zshenv`, `~/.zprofile`, `~/.zshrc` when `/bin/zsh` exists: **skip** the `ensure_source_line` calls for zsh files entirely (both blocks — setup_cli_tools.sh:187-191 and the re-apply block :329-331) rather than touching files harmlessly. Same guard at both call sites.

`cli-tools.env` remains SSOT; startup files merely source it.

**Customization integration:** `setup_customization_debian.sh` writes complete `.zshrc` (lines 772+); inside generated `.zshrc` add:

```bash
if [ -f "$HOME/.config/fluxlinux/cli-tools.env" ]; then
    . "$HOME/.config/fluxlinux/cli-tools.env"
fi
```

Both orders (`CLI setup → customization` and `customization → CLI setup`) behave correctly.

### PR2 — Remove remaining zsh-specific fallback code

- `ChrootCommandBuilder.toolEnvInit()` (line 300) starts with `setopt NULL_GLOB`. Shared `launch_tool.sh` is SSOT → delete `toolEnvInit()` if no call sites need it; otherwise rewrite with `find`/`sort`.
- `toolBinaryName()` unknown/default resolves to `"zsh"` (line 316). Change API: known tool → binary; unknown → nullable or throw. Shell sessions go through dedicated interactive path, never fake-tool-zsh.

### PR2 — Repairs explicitly inherit Bash contract

Preserve four modes:

| Mode | Required behavior |
| --- | --- |
| `host` | host Bash |
| `proot` | guest `/bin/bash` |
| `chroot_guest` | guest `/bin/bash` (already correct — helper `guest_sh` uses `/bin/bash --noprofile --norc -c`, nativecode_chroot.sh:303-317) |
| `chroot_host` | Android `/system/bin/sh` running root host script |

After `ProotCommandBuilder` changes, PRoot repairs work automatically. Add defense-in-depth helper:

```kotlin
LinuxCommandBuilder.buildGuestBash(...)
```

Repair runner calls this so intent is visible: repairs are system automation, always Bash regardless of login shell.

### PR2 — Patch PRoot-fast paths

- `nativecode_proot_fast.sh:22` `LOGIN_SHELL="${NC_SHELL:-zsh}"` → same **passwd-based auto policy** as the chroot helper (resolve user's shell from `${ROOTFS}/etc/passwd`, validate executable, Bash fallback; root always Bash) — use the script's established `grep "^$user:" | cut -d: -f7` idiom (:87-89), NOT awk (Termux host does not guarantee awk). Direct exec mode unchanged.
- `nativecode_proot_fast.sh:356` non-interactive string mode `/bin/sh -c "$CMD_STR"` → `/bin/bash --noprofile --norc -c` (script-side parity with builder change below).
- `ProotFastCommandBuilder.kt:135` normal interactive sessions select `/bin/zsh` → apply same passwd-based auto resolution (builder may read `${ROOTFS}/etc/passwd` host-side or resolve guest-side per §3.2 pattern); `ProotFastCommandBuilder.kt:143` `/bin/sh -c` → `/bin/bash --noprofile --norc -c`; interactive detection via `exec zsh` regex (:12-14) removed in favor of `buildInteractive()`.
- Non-interactive shell-string mode uses `/bin/bash --noprofile --norc -c` (not `/bin/sh -c`) because NativeCode's Debian automation contract is Bash and existing snippets use Bash semantics.

**Maintained mirror:** `docs/environment/plans/suite/nativecode_proot_fast.sh` is a second copy currently carrying the same zsh-default behavior (mirror :22). Keep it synchronized — apply the identical patch **byte-for-byte** with the production asset and note the sync in the PR. Add to change map + regression check ("both copies identical after patch": `cmp` must pass).

**Itemized script edits (both copies):**
- usage strings :8 and :40 `[--shell zsh|bash]` → `[--shell auto|bash|zsh]`
- login-mode shell case :359-363 — currently `zsh)/bash)/sh|dash)/*) die`; add `auto)` branch → passwd-based resolution; keep explicit `zsh`/`bash` branches; unknown value still dies

Patch now so enabling PRoot-fast later does not reintroduce the bug.

### Do NOT solve by installing zsh in base setup

Prohibited fix: `apt install zsh` inside `setup_debian_family.sh` or base Chroot installer. It would hide the architectural problem. `setup_debian_family.sh:16` is correct as-is. `setup_customization_debian.sh` keeps owning zsh install, Oh My Zsh, theme, plugins, `.zshrc`, `chsh`.

### Suggested implementation order

1. Fix `ProotCommandBuilder`: all non-interactive payloads → clean Bash.
2. Change `launch_tool.sh` to Bash + bump version.
3. Introduce interactive `auto` shell resolution for Chroot; eliminate generated `exec zsh`.
4. Refactor `LinuxCommandBuilder` interactive API (`buildInteractive`) so shell selection isn't encoded in command strings.
5. Clean `CliGuestCommands` and `toolEnvInit` of `setopt`/zsh assumptions.
6. Update `setup_cli_tools.sh` + customization integration for order-independent `cli-tools.env`.
7. Lock Repairs to guest Bash; test rootfs without `/bin/zsh`.
8. Patch PRoot-fast script/builder before fast path becomes production-default.
9. Add command-builder/unit tests + fresh-rootfs device test matrix.
10. Update old docs (e.g., previous “Proot CLI zsh fix” plan) noting `zsh -c` superseded by Bash runtime contract.

## File-Level Change Map

| File | Change | Rationale |
| --- | --- | --- |
| app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt | `zsh -c` → `/bin/bash --noprofile --norc -c`; add `buildInteractive()` per §3.2 spec (bare login for workDir=null; positional-argv wrapper for workDir; root→Bash); drop `exec zsh` marker checks | P0 execution contract |
| app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt | add `buildInteractive(ctx, user, workDir, method)`, `buildGuestBash(...)` | explicit interactive/system-Bash APIs |
| app/src/main/java/com/zenithblue/nativecode/MainActivity.kt | :2051 + :18339 — shell/shell-root → `buildInteractive(sessionUserForType(type), workDir, method)`; AI/tool types → `buildToolShellCommand` + `build` | shell-vs-tool split at call sites |
| app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt | launcher `#!/bin/bash` + version bump v3; `CHROOT_HELPER_VERSION` → v2.3; emit `--shell auto`; `buildToolShellCommand` becomes tool-only (rejects shell types); delete/rewrite `toolEnvInit`; `toolBinaryName` strict; stop emitting `exec zsh`/`parseInteractiveWorkdir` | launcher + interactive decoupling |
| app/src/main/java/com/zenithblue/nativecode/cliauth/CliGuestCommands.kt | remove `setopt`; Bash-safe bootstrap | auth probes run anywhere (auth/status/login/logout route via LinuxCommandBuilder.build — CliAuthService.kt:861, GitHubCliService.kt:1029) |
| app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt | passwd-based auto shell resolution (:135); `/bin/sh -c` (:143) → Bash; drop `exec zsh` regex (:12-14) via `buildInteractive` | future-proof fast path |
| app/src/main/assets/scripts/chroot/nativecode_chroot.sh | header stamp :2 + `VERSION_STR` → v2.3 (lockstep with const); **BOTH** `NC_SHELL:-zsh` (:25 AND :409) → `:-auto`; usage strings :11+:42 → auto\|bash\|zsh; passwd-based resolution replacing :262-298 branches via `$BB awk` (busybox guaranteed, resolve_bb :54-83) + grep/cut fallback | helper contract + upgrade-path restage |
| app/src/main/assets/scripts/chroot/setup_debian13_chroot.sh | emit `--shell auto` | caller alignment |
| app/src/main/assets/scripts/setup_cli_tools.sh | mandatory bash wiring; conditional zsh wiring (skip zsh ensure_source_line at both :187-191 and :329-331) | zsh optional |
| app/src/main/assets/scripts/setup_customization_debian.sh | source `cli-tools.env` in generated `.zshrc` | both install orders |
| app/src/main/assets/scripts/nativecode_proot_fast.sh | `NC_SHELL:-auto` w/ passwd resolution; usage :8+:40 → auto\|bash\|zsh; login case :359-363 gains `auto)` branch; :356 `/bin/sh -c` → `/bin/bash --noprofile --norc -c` | fast-path parity |
| docs/environment/plans/suite/nativecode_proot_fast.sh | byte-for-byte sync with production asset after patch (`cmp` clean) | maintained mirror must not drift |
| Repair runner call site(s) | use `buildGuestBash` | regression defense |

## Testing Strategy

Test primary state: deliberately **un-customized Debian** (`test ! -x /bin/zsh`).

| Environment | Operation | Expected |
| --- | --- | --- |
| fresh PRoot rootfs | Repairs → `setup_debian_family.sh` | runs without zsh |
| fresh Chroot rootfs | same repair | runs under Bash |
| Debian setup only | Debian Shell | Bash prompt |
| Debian setup only | `setup_cli_tools.sh` | installs successfully |
| Debian setup only | OpenCode/Claude/Qwen/Agy/Grok/Kiro/Codex cards | launch |
| Debian setup only | CLI auth/status page | probes work |
| Debian setup only | workspace AI CLI | launches in requested cwd |
| after customization | Debian Shell | zsh prompt |
| after customization | all AI cards / Repairs | still Bash-backed / Bash |
| remove `/bin/zsh` afterward | AI cards + Repairs | still work |
| remove `/bin/zsh` afterward | interactive shell | falls back to Bash |
| PRoot-fast | interactive pre-customization | Bash |
| PRoot-fast | interactive post-customization | zsh |

Static regression checks (no zsh creeping back into runtime code):

```text
No `-- zsh -c` in ProotCommandBuilder
No `#!/bin/zsh` in launch_tool
No `setopt` in CliGuestCommands/toolEnvInit
No default-to-zsh in Chroot helper
No default-to-zsh in PRoot-fast
```

Allowed zsh references limited to: `setup_customization_debian.sh`, optional interactive-shell detection, documentation/tests describing fallback.

Plus unit tests for command builders (interactive detection, escaping preserved, version marker) and manual device matrix above.

**Test infrastructure decision (definite):** repo has no `app/src/test` source set and no test dependency (verified: no `testImplementation`/`junit` in `app/build.gradle.kts`). This PR set **adds the minimal JUnit scaffold**:

```kotlin
// app/build.gradle.kts
testImplementation("junit:junit:4.13.2")
```

New source set `app/src/test/java/com/zenithblue/nativecode/terminal/...` covering **pure helpers only** (no Robolectric; builders' `Context` use stays on host paths):

1. guest shell resolution (passwd-based auto, fallback, root pin)
2. single-quote/argv escaping behavior (incl. paths with spaces/quotes/`$`)
3. interactive workdir construction (positional argv, never interpolated)
4. root-vs-flux shell policy in `buildInteractive`
5. unknown tool type rejection (`toolBinaryName` strict)
6. version constants (`CHROOT_HELPER_VERSION` v2.3 ↔ asset stamp, `LAUNCH_TOOL_VERSION` v3)

**Mandatory static checks** (in CI/local verification regardless of device availability):

```text
./gradlew :app:compileDebugKotlin

bash -n app/src/main/assets/scripts/setup_cli_tools.sh
bash -n app/src/main/assets/scripts/setup_customization_debian.sh

sh -n app/src/main/assets/scripts/chroot/nativecode_chroot.sh
sh -n app/src/main/assets/scripts/chroot/setup_debian13_chroot.sh
sh -n app/src/main/assets/scripts/nativecode_proot_fast.sh

cmp docs/environment/plans/suite/nativecode_proot_fast.sh \
    app/src/main/assets/scripts/nativecode_proot_fast.sh
```

Static regression check scope: the no-`zsh` grep applies to **non-interactive execution paths**; interactive auto-resolution (passwd lookup + zsh validation, `NC_SHELL=auto` resolution) may reference zsh per "Allowed zsh references".

## Acceptance Criteria (objective, verifiable)

- [ ] `ProotCommandBuilder` emits no `zsh` token on **non-interactive paths**; non-interactive payload wrapped in `/bin/bash --noprofile --norc -c` (interactive auto-resolution per §3.2 spec allowed)
- [ ] PRoot interactive matches §3.2 spec exactly: workDir=null → bare `proot-distro login --user <user>`; workDir≠null → positional-argv Bash wrapper (`"$1"`, never interpolated); user=root → `/bin/bash`
- [ ] Interactive shell selection is **passwd-based**: flux gets configured `/etc/passwd` shell (validated, Bash fallback) — installing zsh WITHOUT customization does NOT switch flux to zsh (device-verifiable)
- [ ] Root interactive is always `/bin/bash` on all three paths (chroot helper, PRoot, PRoot-fast), including `shell-root` sessions with null workdir
- [ ] `MainActivity.kt:2051/:18339` branch shell/shell-root → `buildInteractive`; `buildToolShellCommand` rejects shell types
- [ ] No `"exec zsh"` emitter or parser remains anywhere in Kotlin (legacy shim removed, not deferred)
- [ ] Generated launcher has `#!/bin/bash` and `LAUNCH_TOOL_VERSION` bumped to v3; existing installs get rewritten via content-diff (verify by relaunching a card without reinstall)
- [ ] Fresh extracted Debian rootfs with NO `/bin/zsh`: Repairs → Debian Family repair succeeds end-to-end
- [ ] Same rootfs: `setup_cli_tools.sh` succeeds; every AI card launches; CLI login/status probes succeed
- [ ] Interactive Debian shell pre-customization = bash; post-customization = zsh
- [ ] Explicitly removing zsh post-customization: cards, repairs, probes still work; interactive falls back to bash
- [ ] No `setopt` in CliGuestCommands or toolEnvInit; `toolBinaryName` never returns zsh for unknown type
- [ ] Chroot helper default shell = auto (BOTH :25 and :409 assignments); usage strings :11+:42 updated; passwd extraction uses `$BB awk` w/ grep/cut fallback (no bare host `awk`); explicit zsh request falls back gracefully when absent
- [ ] `CHROOT_HELPER_VERSION` and helper `VERSION_STR` both = v2.3; **upgraded install** (old v2.2 helper present) gets restaged helper automatically
- [ ] PRoot-fast script/builder follow passwd-based auto policy; usage :8+:40 + case `auto)` branch (:359-363) updated; non-interactive mode uses Bash not `/bin/sh` (script :356 + builder :143); suite mirror byte-identical to production asset (`cmp` clean)
- [ ] JUnit scaffold added (`testImplementation junit:4.13.2`, `app/src/test/...`); listed pure-helper tests pass (`./gradlew :app:testDebugUnitTest`)
- [ ] Mandatory static checks pass: `:app:compileDebugKotlin`, `bash -n` ×2, `sh -n` ×3
- [ ] Static grep checks listed in Testing Strategy all pass (non-interactive scope)

## Risks & Mitigations (NEW_RISKS)

- **Behavioral drift:** some payloads may rely on zsh globbing/quoting quirks → audit call-site payloads during migration; unit-test escaped payloads. (Reviewer-verified: GhGuestCommands/GitGuestCommands/CliGuestCommands payloads contain no zsh-only syntax — bash-safe.)
- **Legacy `exec zsh` markers:** removed entirely in the same PR, no compat shim. Reviewer-verified nil persisted-state risk (sessions in-memory; single emitter + both consumers updated here).
- **Passwd parsing edge:** malformed/missing `$NC_CHROOT/etc/passwd` or unknown user must resolve to `/bin/bash` fallback (never empty shell string) — covered by unit test 1 and helper WARN path.
- **Upgrade-path silent staleness:** version-gated asset deployment means unchanged stamps never restage → mitigated by lockstep v2.3 bumps for helper (+ v3 launcher stamp) and new acceptance criterion.
- **Mirror drift:** suite copy of `nativecode_proot_fast.sh` patched in lockstep + `cmp` check prevents divergence.
- **Oh My Zsh rewrite of `.zshrc`:** customization rewrites `.zshrc` and could clobber CLI env → mitigated by sourcing `cli-tools.env` inside generated `.zshrc` (§PR2 integration) and `setup_cli_tools.sh` re-apply block (existing lines 326-331).
- **Research limitation:** no web/GitHub/ctx7 sources were needed; all claims verified against local repo files (citations above). No inconclusive areas remain.
- **PRoot-fast not yet production-default:** lower priority but included to prevent reintroduction when enabled.

## Done Definition

Done only when a newly extracted Debian rootfs reaches this sequence with **no zsh installed**:

```text
install Debian → run Debian Family repair → create flux user → run CLI Tools setup
→ launch AI CLI tools → perform CLI login/status probes → run Repairs → open workspace CLI
```

Then, after Debian Customization installs zsh, only the human interactive shell experience changes from Bash to zsh. Nothing else notices. **Bash = runtime ABI; zsh = optional UX.**

## Handoff to Plan Reviewer (PASS 4)

PASS 3 reviewer verdict was REVISE (1 major, 1 minor, 3 suggestions) — all 6 prior external findings verified as correctly resolved. PASS 4 deltas:
1. ~~Host-side bare `awk` unreliable~~ → chroot-helper passwd extraction now mandates `$BB awk` (busybox guaranteed by resolve_bb(), nativecode_chroot.sh:54-83 dies without it) + `grep "^user:" | cut -d: -f7` fallback; guest-side awk in PRoot §3.2 wrapper unchanged (Debian ships mawk).
2. ~~PRoot-fast auto not itemized~~ → script usage strings :8+:40 and login case :359-363 (`auto)` branch added, unknown still dies) explicitly itemized for both copies.
3. Suggestion folded: `$USER` → `$(id -un)` in PRoot wrapper lookup (env-independent); spec-rendering note added (assembled host command ≠ Kotlin literal — no hand-embedded `'"'"'`); citation fixed to :830-831.
4. Accepted cosmetic deferral: PR1/PR2 heading renumbering (changelog clarity only, no semantic content).

**Expected verdict:** APPROVE.

Validate PASS 3 external-review resolutions still intact: passwd-based auto everywhere (root pinned Bash), PRoot 3-case buildInteractive spec (bare login / positional-argv / root), MainActivity branching + tool-only `buildToolShellCommand` + same-PR shim removal, both NC_SHELL assignments (:25,:409) + usage :11+:42, JUnit scaffold + mandatory compile/syntax checks, suite-mirror sync + `cmp`.

**PASS 2 verdict:** APPROVE (0 critical, 0 major; 1 minor resolved above — asset header stamp nativecode_chroot.sh:2 added to lockstep bump).
