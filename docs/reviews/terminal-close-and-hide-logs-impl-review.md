# Impl Review: terminal-close-and-hide-logs — PASS 1 ITER 1

## Verdict: APPROVE
## Counts: CRITICAL 0 MAJOR 0 MINOR 0

Device checks in the plan (terminals survive STOP, one-tab kill isolation, guest `sleep` gone, no VIEW LOGS on screen) are ENVIRONMENT_LIMITATION → MANUAL_TEST. Source matches the approved plan.

### Findings

None.

### Criterion map

| Criterion | Where | Evidence |
|-----------|--------|----------|
| `stop_gui.sh` step 2 is only `pkill -9 -f "proot-distro"` | `stop_gui.sh:33-36` | Diff deletes `-f proot`, `-f libproot.so`, PPID-1 loop. Steps 1/3/4 untouched (`:26-31`, `:38-64`) |
| `reapOrphanShells` gone; no comm / PPID==1 kill | `MainActivity.kt:2281-2345` | Repo grep: 0 hits. Scan filters `TracerPid == session.pid` or `PPid == root` only |
| Tab close scoped | `MainActivity.kt:2361-2365`, `:20161-20165` | Both: `terminateSessionProcesses` then `finishIfRunning`. Snapshot before signal (`:2286-2287` then `:2288-2293`) |
| `--kill-on-exit` kept | `ProotCommandBuilder.kt:182` | Not in diff |
| Onboarding log UI gone; buffer stays | `OnboardingActivity.kt:88`, `:1892`, `:2135`, `:2788-2797` | Deleted toggle/panel/`baseLog*`/`setupLogVisible`/toggle fns. Fail strings `"Setup failed"`. `appendSetupLog` still called (19 sites) |
| Settings VIEW LOGS hidden; capture stays | `MainActivity.kt:8807-8808`, `:14048`, `:14154-14160`, `:13966` | `viewLogsBtn` not created. `setGuiCardRunning()` no `showLogs`. No `openGuiLogPage()` on fail. `guiLogAppend` + `openGuiLogPage` (`:14223`) remain |

## Evidence

- `git diff --stat`: 3 files, +86/−240. Names match plan file map. No `termux.c`, `TerminalSession.java`, `ProotCommandBuilder`, `stop_gui_chroot.sh`.
- Branch `feature/prebuilt-alpine-rootfs-w1-w6`. Uncommitted.
- Worker `./gradlew :app:compileDebugKotlin --offline -q` exit 0 not re-run (review bash is git-only). Compile risk low: deleted symbols have 0 remaining references (`rg` exit 1).

## Next Action: @manual-tester — plan tests 1–7 on device
