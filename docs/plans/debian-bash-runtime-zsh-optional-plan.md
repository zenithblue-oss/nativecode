# Plan: debian-bash-runtime-zsh-optional

**PLAN_STATUS:** APPROVED FOR IMPLEMENTATION
**PASS:** 5 (revised after upstream PRoot verification)
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
- <source: upstream proot-distro `commands/login/__init__.py` — normal login resolves `/etc/passwd` shell, validates it, then runs supplied commands as `<login_shell> -c ...`; `--` does not bypass the login-shell wrapper>

### PASS 5 architectural correction

Upstream `proot-distro login` does not execute the command after `--` directly for a normal container. It reads the selected user's shell from guest `/etc/passwd`, verifies that shell exists, and invokes it with `-c` when a command is supplied. Therefore customization must not change `flux`'s runtime login shell to zsh: doing so would make every programmatic login depend on zsh and would fail before a Bash payload starts if zsh is removed.

The runtime and UX contracts are now separate:

```text
guest /etc/passwd flux shell = /bin/bash
    ↓
proot-distro automation, Repairs, probes, AI launchers, setup scripts

/home/flux/.config/fluxlinux/interactive-shell = /bin/zsh (optional marker)
    ↓
NativeCode interactive shell selection only
```

Customization writes the marker and leaves `/etc/passwd` unchanged. Missing, invalid, or non-executable marker values fall back to `/bin/bash`. This marker is shared by PRoot, Chroot, and PRoot-fast.
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
| Interactive Debian shell | NativeCode preference | flux reads `/home/flux/.config/fluxlinux/interactive-shell`, validates `/bin/zsh` or `/bin/bash`, then falls back to Bash; root is always Bash |
| Customization | zsh | optional enhancement only; installs zsh and writes the interactive-shell marker; never changes `/etc/passwd` |

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
| `flux` | `/home/flux/.config/fluxlinux/interactive-shell`, validated executable, `/bin/bash` fallback | optional workspace cwd |
| `root` | always `/bin/bash` | current app contract passes `null` (MainActivity.kt:18337-18338 — root gets no project workdir to avoid root-owned project files); builder must still tolerate non-null by cd'ing as root |

**PRoot backend interactive emission (explicit spec).** Because `/etc/passwd` permanently keeps Bash as the runtime shell, NativeCode uses a Bash bootstrap for both home and workspace sessions. The bootstrap reads the NativeCode preference marker and then `exec`s the selected human shell:

```text
buildInteractive(ctx, user=root, workDir=null, method=proot):
  exec python proot-distro login debian [--shared-tmp] --user root -- \
    /bin/bash --noprofile --norc -c 'exec /bin/bash -l'

buildInteractive(ctx, user=flux, workDir=<path>, method=proot):
  exec python proot-distro login debian --shared-tmp --user flux -- \
    /bin/bash --noprofile --norc -c \
    'mkdir -p -- "$1" && cd -- "$1" &&
     sh=$(cat /home/flux/.config/fluxlinux/interactive-shell 2>/dev/null || true)
     case "$sh" in /bin/bash|/bin/zsh|/usr/bin/bash|/usr/bin/zsh) [ -x "$sh" ] || sh=/bin/bash ;; *) sh=/bin/bash ;; esac
     exec "$sh" -l' \
    nativecode-interactive '<workDir>'

buildInteractive(ctx, user=flux, workDir=null, method=proot):
  same Bash bootstrap without the workdir/`$1` prefix
```

Rules:
- Host Kotlin must call `shellSingleQuote(workDir)` before appending the workdir after `nativecode-interactive`; this preserves one positional argument for spaces, quotes, and literal `$`.
- **workDir is a positional argv (`$1`), never interpolated into the script body.** Exactly one quoting layer exists — the host-side single-quote escaping of the outer command string (same `'` → `'\''` mechanism as today, ProotCommandBuilder.kt:26). Guest body references `$1` only, so spaces/quotes/`$`/backticks in project paths are safe. Do **not** implement `cd $workDir && ...`. The payload shown above is the **assembled host command**, not the Kotlin string literal — in Kotlin, write the awk program with raw single quotes and let the existing `replace("'","'\\''")` layer do the escaping; do not hand-embed `'"'"'`.
- **workDir == null still uses the Bash bootstrap** so the marker can select zsh for the normal interactive terminal; root's bootstrap always execs Bash.
- The zero-zsh-token static check applies to **non-interactive execution paths only**; interactive auto-resolution referencing zsh is explicitly allowed per the "Allowed zsh references" clause.

**Chroot backend interactive emission:** `login --user <u> --shell auto [--workdir <q>]`; helper resolves per §Interactive-Chroot below.

**MainActivity branching (required change):** both call sites (MainActivity.kt:2051 startNewTerminalSession, :18339 startWorkspaceTerminalTab) currently route **all** types through `buildToolShellCommand(...)` → `LinuxCommandBuilder.build(...)`. Split explicitly:

```kotlin
val method = projectGitMethod() // startNewTerminalSession uses currentMethod
val user = LinuxCommandBuilder.sessionUserForType(type)
val (args, envMap) = if (type == "shell" || type == "shell-root") {
    LinuxCommandBuilder.buildInteractive(
        this,
        user = user,
        workDir = workDir,   // MainActivity.kt:18338: null for shell-root
        method = method
    )
} else {
    val command = ChrootCommandBuilder.buildToolShellCommand(this, type, workDir)
    LinuxCommandBuilder.build(this, command, user = user, method = method)
}
```

Both branches must carry the resolved `method`; Workspace AI launches must use `projectGitMethod()` and never fall back to `LinuxCommandBuilder.currentMethod`.

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

Resolution inside rootfs — **`auto` means the NativeCode interactive preference marker, not `/etc/passwd` and not "zsh binary exists"**. Base setup creates flux with Bash (`useradd -m -s /bin/bash flux`, setup_debian_family.sh:16). Customization installs zsh and writes `/home/flux/.config/fluxlinux/interactive-shell` without changing `/etc/passwd`. Helper runs as host root, so resolve the marker from `$NC_CHROOT/home/flux/.config/fluxlinux/interactive-shell`:

```text
requested=auto:
  user=root:
    always /bin/bash
  user=flux:
    configured=$(cat "$NC_CHROOT/home/flux/.config/fluxlinux/interactive-shell" 2>/dev/null || true)
    case configured in
      /bin/zsh|/usr/bin/zsh|/bin/bash|/usr/bin/bash) [ -x "$NC_CHROOT$configured" ] && use configured || WARN+fallback /bin/bash ;;
      *)                                                WARN if non-empty+unrecognized; use /bin/bash ;;
    esac

requested=zsh missing → WARN → /bin/bash (never kill terminal)
```

Rationale: executable-existence auto ("apt install zsh but customization not run") would wrongly switch flux to zsh while the preference marker is absent. Marker-based auto makes zsh an explicit UX choice and leaves the runtime shell permanently Bash. Rewrite the current resolution branches (nativecode_chroot.sh:262-298), which today default flux to `su -s /bin/zsh` via the `zsh|*)` catch-all (:289).

Change callers from `login --user flux --shell zsh` (ChrootCommandBuilder.kt:58, setup_debian13_chroot.sh:417) to `--shell auto`. Root interactive shells are pinned to Bash on both sides (ChrootCommandBuilder.kt:54 already emits `--shell bash`; keep).

**Helper version bump (upgrade-path requirement):** helper deployment is version-gated — `ensureHelperScript()` restages from assets only when the installed stamp differs (ChrootCommandBuilder.kt:112-118; const at :19-20, `VERSION_STR` in nativecode_chroot.sh:20). Bump **all three** in lockstep so the gate converges (const must match asset header line 2):

```text
nativecode-chroot v2.2 → v2.3
```

1. `CHROOT_HELPER_VERSION` const — ChrootCommandBuilder.kt:20
2. `VERSION_STR` variable — nativecode_chroot.sh:20
3. Asset header stamp comment — nativecode_chroot.sh:2 (`# nativecode-chroot v2.2`)

Without this, upgraded devices silently keep the old zsh-default v2.2 helper while fresh installs pass tests; mismatched stamps would re-stage via su on every build() call.

Result: before customization Terminal→Bash; after customization Terminal→zsh; `/etc/passwd` remains Bash and AI cards stay Bash-backed in both cases.

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

**Customization integration:** `setup_customization_debian.sh` writes complete `.zshrc` (lines 772+), writes the marker, and must not run `chsh` or patch `/etc/passwd`. Add:

```bash
mkdir -p "$USER_HOME/.config/fluxlinux"
printf '%s\n' /bin/zsh > "$USER_HOME/.config/fluxlinux/interactive-shell"
chown "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config/fluxlinux/interactive-shell"
```

Inside generated `.zshrc` add:

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

- `nativecode_proot_fast.sh:22` `LOGIN_SHELL="${NC_SHELL:-zsh}"` → same **marker-based auto policy** as the chroot helper (resolve `${ROOTFS}/home/flux/.config/fluxlinux/interactive-shell`, validate executable, Bash fallback; root always Bash). Direct exec mode unchanged.
- `nativecode_proot_fast.sh:356` non-interactive string mode `/bin/sh -c "$CMD_STR"` → `/bin/bash --noprofile --norc -c` (script-side parity with builder change below).
- `ProotFastCommandBuilder.kt:135` normal interactive sessions select `/bin/zsh` → apply the same marker-based auto resolution (builder reads `${ROOTFS}/home/flux/.config/fluxlinux/interactive-shell` host-side); `ProotFastCommandBuilder.kt:143` `/bin/sh -c` → `/bin/bash --noprofile --norc -c`; interactive detection via `exec zsh` regex (:12-14) removed in favor of `buildInteractive()`.
- Non-interactive shell-string mode uses `/bin/bash --noprofile --norc -c` (not `/bin/sh -c`) because NativeCode's Debian automation contract is Bash and existing snippets use Bash semantics.

**Maintained mirror:** `docs/environment/plans/suite/nativecode_proot_fast.sh` is a second copy currently carrying the same zsh-default behavior (mirror :22). Keep it synchronized — apply the identical patch **byte-for-byte** with the production asset and note the sync in the PR. Add to change map + regression check ("both copies identical after patch": `cmp` must pass).

**Itemized script edits (both copies):**
- usage strings :8 and :40 `[--shell zsh|bash]` → `[--shell auto|bash|zsh]`
- login-mode shell case :359-363 — currently `zsh)/bash)/sh|dash)/*) die`; add `auto)` branch → passwd-based resolution; keep explicit `zsh`/`bash` branches; unknown value still dies

Patch now so enabling PRoot-fast later does not reintroduce the bug.

### Do NOT solve by installing zsh in base setup

Prohibited fix: `apt install zsh` inside `setup_debian_family.sh` or base Chroot installer. It would hide the architectural problem. `setup_debian_family.sh:16` is correct as-is. `setup_customization_debian.sh` owns zsh install, Oh My Zsh, theme, plugins, `.zshrc`, and the interactive-shell marker; it must not change `flux`'s `/etc/passwd` shell.

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
| app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt | `zsh -c` → `/bin/bash --noprofile --norc -c`; add `buildInteractive()` with Bash bootstrap, marker resolution, explicit positional workdir argv, and root→Bash; drop `exec zsh` marker checks | P0 execution contract |
| app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt | add `buildInteractive(ctx, user, workDir, method)`, `buildGuestBash(...)` | explicit interactive/system-Bash APIs |
| app/src/main/java/com/zenithblue/nativecode/MainActivity.kt | :2051 + :18339 — shell/shell-root → `buildInteractive(sessionUserForType(type), workDir, method)`; AI/tool types → `buildToolShellCommand` + `build` | shell-vs-tool split at call sites |
| app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt | launcher `#!/bin/bash` + version bump v3; `CHROOT_HELPER_VERSION` → v2.3; add `buildInteractive` emitting `--shell auto`; `buildToolShellCommand` becomes tool-only (rejects shell types); delete/rewrite `toolEnvInit`; `toolBinaryName` strict; stop emitting `exec zsh`/`parseInteractiveWorkdir` | launcher + interactive decoupling |
| app/src/main/java/com/zenithblue/nativecode/cliauth/CliGuestCommands.kt | remove `setopt`; Bash-safe bootstrap | auth probes run anywhere (auth/status/login/logout route via LinuxCommandBuilder.build — CliAuthService.kt:861, GitHubCliService.kt:1029) |
| app/src/main/java/com/zenithblue/nativecode/git/GitGuestCommands.kt + GitRepoService.kt | path-scoped `safe.directory` env/inline Git commands; valid one-line status bundle | Android-owned PRoot bind trees remain usable by guest Git Diff/branches/status |
| app/src/main/assets/scripts/start_gui.sh | remove START-side app/service kills; clear only stale X11 lock/socket when no X server process exists | safe XFCE startup and repeatable X11 display validation |
| app/src/main/java/com/zenithblue/nativecode/terminal/ProotFastCommandBuilder.kt | marker-based auto shell resolution; `/bin/sh -c` (:143) → Bash; drop `exec zsh` regex (:12-14) via `buildInteractive` | future-proof fast path |
| app/src/main/assets/scripts/chroot/nativecode_chroot.sh | header stamp :2 + `VERSION_STR` → v2.3 (lockstep with const); **BOTH** `NC_SHELL:-zsh` (:25 AND :409) → `:-auto`; usage strings :11+:42 → auto\|bash\|zsh; marker-based resolution replacing :262-298 branches | helper contract + upgrade-path restage |
| app/src/main/assets/scripts/chroot/setup_debian13_chroot.sh | emit `--shell auto` | caller alignment |
| app/src/main/assets/scripts/setup_cli_tools.sh | mandatory bash wiring; conditional zsh wiring (skip zsh ensure_source_line at both :187-191 and :329-331) | zsh optional |
| app/src/main/assets/scripts/setup_customization_debian.sh | source `cli-tools.env` in generated `.zshrc` | both install orders |
| app/src/main/assets/scripts/nativecode_proot_fast.sh | `NC_SHELL:-auto` w/ marker resolution; usage :8+:40 → auto\|bash\|zsh; login case :359-363 gains `auto)` branch; :356 `/bin/sh -c` → `/bin/bash --noprofile --norc -c` | fast-path parity |
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
| broken installed OpenCode shim | `setup_cli_tools.sh` rerun | executes `postinstall.mjs`, then `opencode --version` passes |
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

1. guest shell resolution (marker-based auto, fallback, root pin)
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
- [ ] PRoot interactive always enters through the `/etc/passwd` Bash runtime shell, then applies the marker for flux UX; workDir≠null uses positional argv (`"$1"`, never interpolated); user=root → `/bin/bash`
- [ ] Interactive shell selection is **marker-based**: flux `/etc/passwd` remains `/bin/bash`; missing/invalid/non-executable marker falls back to Bash; installing zsh WITHOUT customization does NOT switch flux to zsh (device-verifiable)
- [ ] Root interactive is always `/bin/bash` on all three paths (chroot helper, PRoot, PRoot-fast), including `shell-root` sessions with null workdir
- [ ] `MainActivity.kt:2051/:18339` branch shell/shell-root → `buildInteractive`; `buildToolShellCommand` rejects shell types
- [ ] No `"exec zsh"` emitter or parser remains anywhere in Kotlin (legacy shim removed, not deferred)
- [ ] Generated launcher has `#!/bin/bash` and `LAUNCH_TOOL_VERSION` bumped to v3; existing installs get rewritten via content-diff (verify by relaunching a card without reinstall)
- [ ] Fresh extracted Debian rootfs with NO `/bin/zsh`: Repairs → Debian Family repair succeeds end-to-end
- [ ] Same rootfs: `setup_cli_tools.sh` succeeds; every AI card launches; CLI login/status probes succeed
- [ ] Interactive Debian shell pre-customization = bash; post-customization = zsh
- [ ] Explicitly removing zsh post-customization: cards, repairs, probes still work; interactive falls back to bash
- [ ] No `setopt` in CliGuestCommands or toolEnvInit; `toolBinaryName` never returns zsh for unknown type
- [ ] Chroot helper default shell = auto (BOTH :25 and :409 assignments); usage strings :11+:42 updated; marker resolution validates guest executable; explicit zsh request falls back gracefully when absent
- [ ] `CHROOT_HELPER_VERSION` and helper `VERSION_STR` both = v2.3; **upgraded install** (old v2.2 helper present) gets restaged helper automatically
- [ ] PRoot-fast script/builder follow marker-based auto policy; usage :8+:40 + case `auto)` branch (:359-363) updated; non-interactive mode uses Bash not `/bin/sh` (script :356 + builder :143); suite mirror byte-identical to production asset (`cmp` clean)
- [ ] JUnit scaffold added (`testImplementation junit:4.13.2`, `app/src/test/...`); listed pure-helper tests pass (`./gradlew :app:testDebugUnitTest`)
- [ ] Mandatory static checks pass: `:app:compileDebugKotlin`, `bash -n` ×2, `sh -n` ×3
- [ ] Static grep checks listed in Testing Strategy all pass (non-interactive scope)

## Risks & Mitigations (NEW_RISKS)

- **Behavioral drift:** some payloads may rely on zsh globbing/quoting quirks → audit call-site payloads during migration; unit-test escaped payloads. (Reviewer-verified: GhGuestCommands/GitGuestCommands/CliGuestCommands payloads contain no zsh-only syntax — bash-safe.)
- **Legacy `exec zsh` markers:** removed entirely in the same PR, no compat shim. Reviewer-verified nil persisted-state risk (sessions in-memory; single emitter + both consumers updated here).
- **Preference marker edge:** malformed/missing marker or unknown value must resolve to `/bin/bash` fallback (never empty shell string) — covered by unit test 1 and helper WARN path.
- **Upgrade-path silent staleness:** version-gated asset deployment means unchanged stamps never restage → mitigated by lockstep v2.3 bumps for helper (+ v3 launcher stamp) and new acceptance criterion.
- **Mirror drift:** suite copy of `nativecode_proot_fast.sh` patched in lockstep + `cmp` check prevents divergence.
- **Oh My Zsh rewrite of `.zshrc`:** customization rewrites `.zshrc` and could clobber CLI env → mitigated by sourcing `cli-tools.env` inside generated `.zshrc` (§PR2 integration) and `setup_cli_tools.sh` re-apply block (existing lines 326-331).
- **Research:** upstream `proot-distro` login source was checked; normal-container commands are wrapped by the `/etc/passwd` shell before the supplied command. The marker split is required by that behavior.
- **PRoot-fast not yet production-default:** lower priority but included to prevent reintroduction when enabled.

## Done Definition

Done only when a newly extracted Debian rootfs reaches this sequence with **no zsh installed**:

```text
install Debian → run Debian Family repair → create flux user → run CLI Tools setup
→ launch AI CLI tools → perform CLI login/status probes → run Repairs → open workspace CLI
```

Then, after Debian Customization installs zsh, only the human interactive shell experience changes from Bash to zsh. Nothing else notices. **Bash = runtime ABI; zsh = optional UX.**

## Device validation

MediaTek target `Y5WWBMJVOZSK4HU8` (`MT6897Z_A/ZA`, KernelSU root) passed release install, upgrade restaging, Bash runtime probes, marker-selected zsh UX, quoted workspace workdir, all nine CLI version probes, host/guest repair scripts, and Software Manager search/expand/refresh. Root was available and was tested through the app and the staged chroot helper; no service-kill or SELinux-weakening command was used.

Additional device acceptance passed:

- Create/import cloned `https://github.com/octocat/Hello-World.git` into PRoot; Project Directory showed `README` and Project Git Diff reported a clean repository (`DIFF SUMMARY`, zero changes).
- Workspace shell and Workspace `opencode` launched with the project path; global Debian Shell and global `opencode` launched through the PRoot Bash wrapper. Project AI launch retained the project backend/cwd.
- Existing project Directory exposed `app-release.apk`; Save/Open reached Android's `FluxLinux` update installer, then the prompt was cancelled without replacing the installed app. Release APK installation itself passed through `pm install`.
- Root terminal opened in-app with `uid=0`; the in-app Chroot AI suite install completed with all expected probes (`node`, `npm`, `opencode`, `codex`, `qwen`, `agy`, `claude`, `grok`, `kiro-cli`), and an in-app root-mode OpenCode launch reached its TUI. Root-backed clone, project workdir with spaces, Git status/diff, and workspace/global backend/cwd isolation passed.
- Root Software Manager showed `CHROOT` and searched/expanded Debian packages successfully. Root Debian Family, customization, hardware-acceleration, and CLI-tool repairs completed; the VNC ownership repair now uses the actual `flux` primary group (`users`).
- Root XFCE4 display passed from the in-app Graphical Desktop card: `Chroot mode` was shown, `termux-x11`, `xfce4-session`, `xfwm4`, `xfce4-panel`, and `xfdesktop` rendered the desktop and remained alive for a 60-second stability check.

## Handoff to Plan Reviewer (PASS 5)

PASS 4 had 2 major and 1 minor finding after upstream PRoot verification. PASS 5 resolves them:
1. Normal `proot-distro login -- COMMAND` still starts the shell from `/etc/passwd`; flux therefore remains permanently `/bin/bash` for runtime automation.
2. Customization writes `/home/flux/.config/fluxlinux/interactive-shell` instead of changing `/etc/passwd`; all three interactive backends validate this marker and Bash-fallback.
3. Workspace AI launches pass `method = projectGitMethod()` explicitly, matching Workspace shells.
4. Workspace workdirs use explicit host `shellSingleQuote(workDir)` and guest positional `$1`.

**Expected verdict:** APPROVE.

Validate: Bash runtime shell remains in `/etc/passwd`, marker-based UX resolution is consistent across PRoot/Chroot/PRoot-fast, MainActivity preserves project backend for shell and AI branches, both NC_SHELL assignments and usage strings update, JUnit/static checks pass, and the suite mirror remains byte-identical.

**PASS 2 verdict:** APPROVE (0 critical, 0 major; 1 minor resolved above — asset header stamp nativecode_chroot.sh:2 added to lockstep bump).
