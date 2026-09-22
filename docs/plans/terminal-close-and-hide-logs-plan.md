# Plan: terminal-close-and-hide-logs

## Task Summary

Three independent fixes. No new features.

1. **Stop desktop must not kill NativeCode / workspace terminals.** `088c6a5` added two lines to `stop_gui.sh` step 2: `pkill -9 -f "libproot.so"` and a PPID-1 `bash|zsh` sweep. Interactive terminals are `libproot.so` (`ProotCommandBuilder.buildInteractive`). Remove those two additions and the older bare `pkill -f "proot"` (`stop_gui.sh:36`, also `7156047b`) — that substring matches `libproot.so`. Keep only `pkill -f "proot-distro"` (`stop_gui.sh:35`) so STOP still ends the desktop supervisor.
2. **Tab close must kill that session only, not every orphan shell.** `b12841a` added `terminateSessionProcesses` + `reapOrphanShells`. `reapOrphanShells` SIGKILLs every PPID-1 `bash|zsh|sh|libproot.so` in `/proc` — including other open tabs and the desktop session. Delete it. Kill only the closed session: its process group, its PPid descendants, and — because proot guests are tracees, not children — rely on `--kill-on-exit` already on that session's argv, with one tracee-only fallback if the guest is still alive. Callers: `closeTerminalSession` (`MainActivity.kt:2323`, sidebar click at 2429) and `closeWorkspaceTab` (`MainActivity.kt:20161`, tab click at 20385).
3. **Hide log UI only. Keep capture.** Onboarding "View setup log" toggle + panel gone. Settings hub GRAPHICAL DESKTOP "VIEW LOGS" button gone. `appendSetupLog`, `guiLogAppend`, `guiLogFile`, `openGuiLogPage` stay so logs still land in the buffer / `cacheDir/gui_desktop.log`.

## Research Sources

- `git show 088c6a5` — insertions are only `pkill -9 -f "libproot.so"` and the PPID-1 `bash|zsh` loop (`stop_gui.sh` step 2 tail). Commit message: kill libproot and PPID-1 shells on desktop stop to stop 100% CPU PTY spin.
- `git blame` / parent of `088c6a5`: `pkill -9 -f "proot-distro"` and `pkill -9 -f "proot"` predate that commit (`7156047b`). Desktop supervisor is `python …/proot-distro login "$DISTRO" -- /bin/bash …` then `exec nativecode-desktop` (`start_gui.sh:270-359`). Step 1 name-pkill does not match `proot-distro`.
- `git show b12841a` — `MainActivity.kt` `terminateSessionProcesses` + `reapOrphanShells` (+48).
- `termux-app/terminal-emulator/src/main/jni/termux.c:65-78` — `fork()` then child `setsid()` before `execvp`. Session pid **is** the process-group id. `Os.kill(-pid, …)` targets that group only.
- `termux-app/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java:233-242` — `finishIfRunning()` sends SIGKILL to `mShellPid` only, not the group, not descendants that called `setsid()` again (proot guest).
- POSIX: negative pid = process group (`kill(2)`). No library API required. ctx7 skipped — Android `android.system.Os.kill` + `/proc` is platform, not a third-party SDK.
- Local: `ProotCommandBuilder.kt:146` direct `libproot.so` launch; `stop_gui.sh:31-44` the collateral block; onboarding log UI `OnboardingActivity.kt:1924-2031`; settings button `MainActivity.kt:8769-8781`.

## Current Architecture

- Desktop stop: `stopGui()` (`MainActivity.kt:14174`) runs host `stop_gui.sh`. Script steps: (1) named DE procs, (2) **proot/libproot pkill + PPID-1 shell sweep**, (3) Termux X11, (4) PulseAudio/VirGL.
- Interactive terminals: `TerminalSession` → JNI `createSubprocess` → `setsid`. Session pid is `libproot.so` (`ProotCommandBuilder.kt:180-182`) with `--kill-on-exit`, then guest `/bin/bash -l` (`:293-294`). Proot ptraces the guest; the guest is not a PPid child of `libproot.so`. App terminal list `sessionsList`; workspace list `workspaceSessions`. Close paths both call `terminateSessionProcesses` then `session.finishIfRunning()`.
- `terminateSessionProcesses` (`MainActivity.kt:2277-2289`): SIGTERM+SIGHUP+SIGKILL on `-pid`, SIGKILL on `pid`, then **global** `reapOrphanShells`.
- `reapOrphanShells` (`MainActivity.kt:2292-2321`): every numeric `/proc` dir, PPID==1 and comm in `bash|zsh|sh|libproot.so` → SIGKILL. Not scoped to the closed pid. This is the tab-close equivalent of the stop_gui bug. Only caller is `terminateSessionProcesses`. Only callers of that are `closeTerminalSession:2326` and `closeWorkspaceTab:20164`.
- GUI logs: `guiLogAppend` writes `cacheDir/gui_desktop.log`. `viewLogsBtn` ("VIEW LOGS") lives on the settings-hub card built by `buildGuiLaunchCard()` (`MainActivity.kt:8638`, attached at `4381`). Shown by `setGuiCardRunning(showLogs=true)` after first healthy line (`onGuiStreamHealthyLine`). Fail path also calls `openGuiLogPage()` (`MainActivity.kt:14158`).
- Onboarding: page 6 `buildDebianBasePage` builds `setupLogToggleBtn` ("View setup log") and `setupLogPanel`. `appendSetupLog` fills `setupLogBuffer` regardless of visibility. `failSetupProgress` sets detail text "Open setup log for details" (`OnboardingActivity.kt:2267`).

## Affected Components & Dependencies

| Component | Touch? |
|-----------|--------|
| `stop_gui.sh` | Yes — step 2 keeps only `pkill -f proot-distro`. Delete `libproot.so` pkill, bare `-f proot`, and the PPID-1 loop. Add nothing |
| `MainActivity.terminateSessionProcesses` / `reapOrphanShells` | Yes — scoped tree kill; delete global reap |
| `closeTerminalSession`, `closeWorkspaceTab` | No signature change; they already call terminate |
| `OnboardingActivity` log toggle + panel | Yes — remove views; keep `appendSetupLog` |
| `viewLogsBtn` + `setGuiCardRunning(showLogs)` | Yes — never show button |
| `guiLogAppend` / `openGuiLogPage` / `showLogFileInScriptPage` | Keep. Stop calling `openGuiLogPage` from the button and from the start-fail auto-open |
| `TerminalSession.java`, `termux.c` | Do not modify |
| Other `stop_gui` steps (phosh/phoc/xfce, X11, pulse, virgl) | Do not modify |

## Implementation Steps (ordered, smallest correct change)

### A. Stop killing terminals from desktop stop

In `app/src/main/assets/scripts/stop_gui.sh` step 2, delete **only** the `088c6a5` lines:

- `"$TB" pkill -9 -f "libproot.so"`
- the whole PPID-1 loop (`for _pid in $("$TB" pgrep -f "bash|zsh" …)` through `fi; done`)

Keep `"$TB" pkill -9 -f "proot-distro"`. That is the desktop supervisor (`start_gui.sh:270`: `python …/bin/proot-distro login …`). Step 1 does not name it. Deleting it leaves the desktop running.

Delete `"$TB" pkill -9 -f "proot"` as well. Do not replace it.

- toybox `pkill -f` is a substring match. `libproot.so` contains `proot`, so bare `-f proot` kills every interactive terminal.
- A `/bin/proot` pattern is also wrong. The desktop supervisor argv is `python …/bin/proot-distro` (`start_gui.sh:270`), already covered by `-f proot-distro`. There is no `/usr/bin/proot` in that cmdline. The only `/bin/proot` argv is the interactive-terminal fallback in `ProotCommandBuilder.kt:158` (`${BIN}/proot` when `libproot.so` is missing). Matching it kills that terminal.

Step 2 after the edit is one line:

```
"$TB" pkill -9 -f "proot-distro" 2>/dev/null || true
```

No `/bin/proot`, no `/bin/proot$`, no argv-element walk, no PPID-1 loop, no `libproot.so`.

Leave steps 1, 3, 4 unchanged.

`stop_gui_chroot.sh` has no this block (comment at `MainActivity.kt:14174`: "no proot pkill"). Do not touch it.

### B. Strict tab close (terminal page + workspace)

Process shape (do not assume a normal Unix tree):

- `session.pid` is the JNI child after `setsid` (`termux.c:78`). For an interactive terminal that child is `libproot.so` (`ProotCommandBuilder.kt:181`), which is its own process-group leader. `Os.kill(-pid)` hits that group only — `libproot.so` and host children that did not `setsid`. It does **not** hit the guest.
- The guest (`/bin/bash -l`, and `sleep` under it) is a **ptrace tracee**, not a PPid descendant of `libproot.so`. A PPid walk from `session.pid` will not find it. Do not claim the walk alone kills the guest.
- `buildInteractive` already passes `--kill-on-exit` (`ProotCommandBuilder.kt:182`). On tracer death, proot kills its tracees. `finishIfRunning` (`TerminalSession.java:233-242`) SIGKILLs `mShellPid` only. Together those are the guest kill. The plan must not remove `--kill-on-exit`.

Delete `reapOrphanShells` and its call. No comm-name filter. No PPID-1 sweep.

`terminateSessionProcesses(session)`:

1. PPid snapshot from `session.pid` only (cap depth ~32, skip pid<=1). Same `/proc/PID/stat` parse as today (comm inside parens; PPid is the field after state). This collects host-side children (linker, etc.). It is necessary but not sufficient for the guest.
2. Signal that set and the group, then the tracer:
   - `Os.kill(-pid, SIGTERM)`, `SIGHUP`, `SIGKILL`
   - SIGKILL each snapshotted descendant
   - `Os.kill(pid, SIGKILL)` on `session.pid` (`libproot.so`)
3. Swallow `ErrnoException` per pid.
4. Guest fallback, still scoped to this session: if a short wait (~200ms, off the UI thread) shows the session's tracer is dead but a guest it was tracing is alive, kill only pids listed as tracees of **this** tracer. Read `/proc/<session.pid>/task/<tid>/children` is the wrong source (not tracees). Use the tracee set proot already owns: after SIGKILL of `libproot.so`, `--kill-on-exit` should reap them. Fallback only if test 5 fails: before the signal, record `/proc/<pid>/task/*/stat` tracer fields or read `/proc/<tracee>/status` `TracerPid` for tasks whose `TracerPid` equals `session.pid`, snapshot those pids, then SIGKILL the tracer, then SIGKILL the snapshotted tracee pids. Never select by comm (`bash`, `libproot.so`) and never by PPID==1.
5. Other tabs, other workspace sessions, and the desktop `proot-distro` supervisor are not in this tracer's group and are not its tracees. They stay alive.

`closeTerminalSession` (`2323`) and `closeWorkspaceTab` (`20161`) stay: `terminateSessionProcesses(session)` then `session.finishIfRunning()`. No second global sweep.

Out of scope: script-runner sessions (`scriptInstallSession`, `mpInstallerSession`) — they never call `terminateSessionProcesses`. Do not route them through it. Do not edit `termux.c` or `TerminalSession.java`.

### C. Hide onboarding log UI (logic stays)

In `OnboardingActivity.buildDebianBasePage`:

- Do not build `setupLogToggleBtn`, its icon, or `setupLogPanel` / console header. Do not `addView` them.
- Leave `appendSetupLog`, `setupLogBuffer`, and the writers (`onLine`, tar/verify/agent/SDK messages) unchanged.
- Delete with the views: `setupLogToggleBtn`, `setupLogToggleLabel`, `setupLogPanel`, `baseLogText`, `baseLogScroll` (all `lateinit`, only used by this UI), `setupLogVisible`, `toggleSetupLogPanel` (`2054`), `setSetupLogVisible` (`2058`). Do not delete `appendSetupLog`.
- `appendSetupLog` posts into `baseLogText` when the panel is visible (`OnboardingActivity.kt:2938`). After the view is gone, keep only the `setupLogBuffer` append. Delete the `baseLogText` / `baseLogScroll` UI post (the `::baseLogText.isInitialized` branch). Grep those names and compile before finishing.
- `failSetupProgress` detail string `"Open setup log for details"` (`2267`) → a string that does not point at a removed control (e.g. `"Setup failed"`). Title text `"Setup failed — open log for details"` (`1898`) same treatment.
- Progress card, percent, Next button: unchanged.

### D. Hide settings GUI "VIEW LOGS" (logic stays)

- In `buildGuiLaunchCard`, do not create `viewLogsBtn` and do not `row.addView(viewLogsBtn)`.
- `setGuiCardRunning`: drop the `showLogs` parameter (or ignore it). Never set a log button visible. STOP button still shows full width (`LayoutParams(MATCH, WRAP)` or weight 1 with no sibling).
- `setGuiCardIdle`: delete the `viewLogsBtn` visibility block.
- `onGuiStreamHealthyLine`: still calls `setGuiCardRunning` and still opens X11. Remove only the log-button side effect.
- `startGui` `onDone` fail branch (`14150-14160`): keep the Toast, drop the `openGuiLogPage()` call so a missing button is not replaced by an auto-opened log page. Keep `guiLogAppend` on every line.
- Keep `openGuiLogPage` and `showLogFileInScriptPage` methods (logic stays, no UI entry). Toast at `14121` ("check VIEW LOGS") → drop the VIEW LOGS clause.
- Do not delete `guiLogFile` / `guiLogAppend` / `guiLogHeader`.

## File-Level Change Map

| File | Change | Rationale |
|------|--------|-----------|
| `app/src/main/assets/scripts/stop_gui.sh` | Step 2 becomes only `pkill -9 -f "proot-distro"`. Delete `-f libproot.so`, `-f proot`, PPID-1 loop | Supervisor argv is `proot-distro` (`start_gui.sh:270`). Bare `proot` and `/bin/proot` both match terminals |
| `app/src/main/java/.../MainActivity.kt` | Delete `reapOrphanShells`. `terminateSessionProcesses` kills that pid's group, PPid children, and (via existing `--kill-on-exit`) its tracees. Hide `viewLogsBtn`; stop auto `openGuiLogPage` | Global PPID-1 reap kills other tabs. Guest is a tracee, not a child |
| `app/src/main/java/.../OnboardingActivity.kt` | Remove setup-log toggle + panel from page 6; fix two "open log" strings | UI only; `appendSetupLog` stays |

No other files. No `termux.c` / `TerminalSession.java` edits. No new classes.

## Testing Strategy

Manual on device (proot). No unit harness exists for `/proc` kill or these views.

1. Open a NativeCode terminal tab and a workspace terminal. Run `sleep 300` or a long `yes` in each. Note pids (`echo $$`).
2. Settings → START PHOSH DESKTOP, wait until running, STOP. Both terminals still accept input. `sleep`/`yes` still alive. Desktop procs (`phosh`/`phoc`) are dead. X11 activity closed.
3. Close **one** terminal tab via the sidebar X. That session's pid and its children are gone (`/proc/<pid>` absent, including a child that is not bash — e.g. `sleep`). The other terminal and any workspace tab still run.
4. Close one workspace tab the same way. Same isolation.
5. Close a tab that is running `bash -c 'sleep 300'` (child of the shell). Child pid is gone too, not only the shell.
6. Onboarding page 6: no "View setup log" control, no log panel. Install still advances percent/title. On failure, detail text does not say "open log". (Buffer still appended — optional logcat/debug check, not a UI check.)
7. Settings hub GRAPHICAL DESKTOP card: START only while idle; STOP only while running; no VIEW LOGS at any state. After a forced start failure, card returns to START and no log page opens. `cacheDir/gui_desktop.log` still grows (pull file or `adb shell run-as` if debuggable).

## Acceptance Criteria (objective, verifiable)

- [ ] `stop_gui.sh` step 2 is only `pkill -f "proot-distro"`. No `pkill` of `proot`, `/bin/proot`, or `libproot.so`. No PPID-1 shell loop.
- [ ] `stop_gui.sh` still kills the named desktop list, Termux X11, PulseAudio, VirGL.
- [ ] Stopping the desktop does not kill an already-open NativeCode terminal or workspace terminal.
- [ ] `reapOrphanShells` is gone. No `/proc` scan kills by comm name or by PPID==1.
- [ ] Closing a terminal sidebar tab or a workspace tab kills that session's `libproot.so` pid and its guest (`bash` and a child `sleep`). Another open tab and the desktop supervisor stay alive.
- [ ] Guest death is via `--kill-on-exit` (already on `buildInteractive` argv) plus SIGKILL of `session.pid`, not via a PPid walk. PPid walk is only for host children of that pid.
- [ ] Onboarding page 6 has no log toggle and no log panel. `appendSetupLog` still exists and is still called from the setup writers.
- [ ] Settings GRAPHICAL DESKTOP card has no VIEW LOGS button in idle or running state. `guiLogAppend` still writes `gui_desktop.log`. Start-failure does not open the log page.

## Risks & Mitigations (NEW_RISKS)

- **Supervisor not actually named `proot-distro`.** `start_gui.sh:270` passes `…/bin/proot-distro` as a python script argv, so `-f proot-distro` matches. If a device test shows the desktop still up after STOP, the survivor's cmdline is the next fact to add — one exact token, never `proot` or `libproot.so`. Do not add `/bin/proot` (`ProotCommandBuilder.kt:158` is the terminal fallback).
- **`--kill-on-exit` does not reap the guest.** Then test 5 fails. Use the TracerPid snapshot in §B step 4 for **that** `session.pid` only. Do not bring back `reapOrphanShells`.
- **Descendants that fork after the snapshot.** A host child started in the gap can escape. Acceptable. Do not add a global reap to close the gap.
- **Step 1 `pkill -9 -f` can match a terminal cmdline** that happens to contain `dbus-daemon`, `phosh`, etc. Pre-existing (`stop_gui.sh` step 1), not this change. Only revisit if test 2 shows a terminal dying after §A is correct.
- **`/proc/PID/stat` permission.** Unreadable guests are skipped (same as today). Group kill via `-pid` still lands on children that stayed in the group even if `/proc` is unreadable.
- **`showLogs` call-site compile break.** Dropping the parameter requires updating `setGuiCardRunning(showLogs = true)` at `14061` and `14065`. Grep `setGuiCardRunning` before finishing.
- **Removed onboarding views still referenced.** `lateinit` fields used only by the log UI: `setupLogToggleBtn`, `setupLogToggleLabel`, `setupLogPanel`, `baseLogText`, `baseLogScroll`. Also delete `toggleSetupLogPanel`, `setSetupLogVisible`, `setupLogVisible`. `appendSetupLog` must not touch those views after the change (buffer append only). Grep before finishing.
- **PTY 100% CPU spin returns for desktop xfce4-terminal.** `088c6a5` message says PPID-1 kill fixed a spin when xfce4-terminal closes. Step 1 already `pkill -9 xfce4-terminal`. If a guest shell of that terminal spins after this change, fix later by killing children of **that** pid only — not all PPID-1 shells. Out of scope unless test 2 shows the spin.

## Handoff to Plan Reviewer

Validate:

- Step 2 keeps only `pkill -f "proot-distro"`. Gone: `088c6a5` lines (`libproot.so` pkill + PPID-1 loop) and bare `-f proot`. No `/bin/proot` replacement. Steps 1/3/4 untouched.
- Tab close does not use a comm-name or PPID-1 scan. Guest kill is `--kill-on-exit` on the existing `buildInteractive` argv plus SIGKILL of `session.pid`, with a TracerPid snapshot of **that** pid only if test 5 fails. PPid walk is host children only.
- `setsid` (`termux.c:78`): `-pid` is the `libproot.so` group, not the app group, not the guest.
- Log UI removal does not delete `appendSetupLog` or `guiLogAppend`. `setupLogToggleLabel` is in the delete list.
- Close functions cited at `MainActivity.kt:2323` and `:20161` (clicks at 2429 and 20385).
- No drive-by edits (chroot stop script, JNI, `ProotCommandBuilder` argv, script-runner sessions, file-search clear button at `21467`).
