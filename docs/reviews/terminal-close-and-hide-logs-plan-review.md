# Plan Review: terminal-close-and-hide-logs — PASS 2

## Verdict: REVISE
## Counts: CRITICAL 0 MAJOR 1 MINOR 0 SUGGESTIONS 1

Prior PASS 1 items 2, 3, 4 are fixed. Item 1 is only half-fixed: deletion scope and the "no pkill of proot-distro" criterion are correct, but the replacement pattern does not match the desktop supervisor and can still match an interactive terminal.

### Prior findings

1. **PARTIAL.** §A deletes only the `088c6a5` lines (`stop_gui.sh:37` `pkill -f "libproot.so"` and `:39-45` PPID-1 loop). `git blame` shows `:35` `proot-distro` and `:36` bare `proot` are `7156047b`. Acceptance no longer requires "no pkill of proot-distro"; it requires the opposite. Replacement is not a safe `/bin/proot` path match — see MAJOR below.
2. **FIXED.** §B says the guest is a ptrace tracee, a PPid walk will not find it, reap is `--kill-on-exit` (`ProotCommandBuilder.kt:182`) plus SIGKILL of `session.pid`, TracerPid snapshot is scoped to that pid, `reapOrphanShells` is deleted, no comm-name / PPID-1 sweep. Matches `termux.c:78` `setsid()` and `TerminalSession.java:233-242` (`Os.kill(mShellPid)` only).
3. **FIXED.** `closeTerminalSession` at `MainActivity.kt:2323`, click at `:2429`. `closeWorkspaceTab` at `:20161`, click at `:20385`. Both call `terminateSessionProcesses` then `finishIfRunning`.
4. **FIXED.** §C delete list includes `setupLogToggleLabel` (`OnboardingActivity.kt:90`, `:1925`), `toggleSetupLogPanel` (`:2054`), `setSetupLogVisible` (`:2058`). `appendSetupLog` (`:2920`) and `guiLogAppend` stay. `appendSetupLog` UI post at `:2930-2940` is called out for deletion.

### Findings

#### [MAJOR] `/bin/proot` pkill does not hit the supervisor and can still hit a terminal

- Location: `docs/plans/terminal-close-and-hide-logs-plan.md` §A (the three `pkill` lines) and Acceptance bullet 2
- Problem: The plan says the supervisor cmdline contains `…/usr/bin/proot` and that `/bin/proot` never appears inside `libproot.so`. Both claims are wrong for this tree.
  - Supervisor launch is `python …/bin/proot-distro login` (`start_gui.sh:270`). `PD_PROOT_BIN` is `nativeLibraryDir/libproot.so` (`TermuxHostPaths.kt:44-45`, written at `:96`). There is no argv element `/usr/bin/proot`. `pkill -f "proot-distro"` already kills that supervisor. The two extra `/bin/proot` lines kill nothing the supervisor needs, and `/bin/proot$` is not a guaranteed toybox regex (plan already hedges).
  - Interactive `buildInteractive` argv0 is `libproot.so` (`ProotCommandBuilder.kt:157-181`), but the same argv binds the host prefix: `--bind=${TermuxHostPaths.PREFIX}` (`:285`), i.e. `--bind=/data/data/com.zenithblue.nativecode/files/usr`. Toybox `pkill -f` is a cmdline substring match. That bind argument contains the substring `/bin` is irrelevant; it does **not** contain `/bin/proot`. The risk is the other way: `PREFIX` is `/data/data/.../files/usr`, and any later argv or env that embeds `$PREFIX/bin/proot` (bootstrap symlink target, `PROOT_BIN` default in `nativecode_proot_fast.sh:20`) would match. The concrete hit today is `--bind=/data/data/com.zenithblue.nativecode/files/usr` does **not** include `/bin/proot`. So `/bin/proot ` does not match the interactive cmdline as currently built. Do not treat that as the bug.
  - The actual bug: acceptance says stop must still kill "a `/bin/proot` path". The desktop tracer's cmdline is `libproot.so` via `PD_PROOT_BIN`, not `/bin/proot`. A worker who implements the snippet verbatim leaves step 2 as `proot-distro` only (fine for the supervisor) plus two patterns that do not match the supervisor and that the plan tells the worker to "confirm on device" with a fallback argv loop ("kill when an argv element ends with `/bin/proot` or `/proot`"). `libproot.so` **ends with `/proot` is false** (`libproot.so` ends with `t.so`), but the fallback text "ends with `/proot`" is safe. The unsafe clause is "exactly `proot`" combined with a cmdline scan that still uses `pkill -f`. Worker has two implementations and a device check as the decider. That is a guess.
- Evidence:
  - `start_gui.sh:270` — `$PYTHON_CMD "$TERMUX_PREFIX/bin/proot-distro" login`
  - `TermuxHostPaths.kt:44-45,96` — `PD_PROOT_BIN=<nativeLibraryDir>/libproot.so`
  - `ProotCommandBuilder.kt:157-158,181,285` — argv0 `libproot.so` **or** fallback `"${TermuxHostPaths.BIN}/proot"` when the `.so` is missing; plus `--bind=$PREFIX`
  - `assemble_bootstrap.py:193-194` — `usr/bin/proot` copied to `libproot.so` (same binary, different path)
  - `stop_gui.sh:35-36` — `proot-distro` pkill already present (`7156047b`)
- Impact: Worker either ships a no-op pair and a device-gated second design, or picks the argv fallback and can SIGKILL every process whose cmdline contains the token `proot` (the interactive `libproot.so` path's basename is `libproot.so`, not `proot` — safe if the test is argv-element equality). The dangerous reading is the literal `pkill -f "/bin/proot"` against the fallback argv0 `…/files/usr/bin/proot` used when `libproot.so` is absent (`ProotCommandBuilder.kt:158`). That fallback **is** an interactive terminal. `/bin/proot ` matches it. STOP would kill NativeCode terminals that launched via the fallback — the bug this plan exists to stop.
- Required planner change: Delete the bare `-f "proot"` line (`stop_gui.sh:36`, `7156047b`) and do **not** replace it. Keep only `"$TB" pkill -9 -f "proot-distro"`. State that the desktop supervisor is `bin/proot-distro` (`start_gui.sh:270`) and its tracer is `PD_PROOT_BIN`=`libproot.so`, which must survive. Drop both `/bin/proot ` and `/bin/proot$` lines and the argv-element fallback loop. Acceptance bullet 2 becomes: `stop_gui.sh` pkills `proot-distro` only; it does not pkill `proot`, `/bin/proot`, or `libproot.so`. If a supervisor child whose argv0 is exactly `$PREFIX/bin/proot` is later shown to survive `proot-distro` death, add a `/proc` argv-element check (element equals `proot` or ends with `/bin/proot`) that **skips** any cmdline containing `libproot.so` — not in this change unless test 2 shows the desktop still running.

#### [SUGGESTION] Package path in the file-level map

- Location: File-Level Change Map
- `app/src/main/java/.../MainActivity.kt` is `com/zenithblue/nativecode/MainActivity.kt`, not `com/nativecode/play`. Worker can find it. Non-blocking.

## Next Agent: Planner
## Next Action: Revise §A and acceptance bullet 2 so step 2 keeps only `pkill -f proot-distro`, deletes bare `-f proot`, and adds no `/bin/proot` pattern.
