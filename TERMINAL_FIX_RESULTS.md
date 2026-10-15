# Terminal optimization results

Source work order: `terminal_optimization.md` (SHA-256 `2d2555d68c6bba9144b8f1dcd3b5600a82075ce00c1b0743d45ae45c05aa38c2`).
Not device-tested. No millisecond or percentage claims.

## Done

| Item | Change |
|---|---|
| A1 | `ProotCommandBuilder.build()` is structured argv: python, proot-distro, guest bash. Host bash stage removed. Payload is one argv element, not re-escaped. Linker still added once by `ShellCommandRunner.adjustHostCmd` / `sessionExec`. |
| A2 | One in-flight `ensureServers` per process. Warm status uses the live handle inside the 5s window; `/proc` only when dead or forced. GPU files written atomically and only when changed. Hardware detection cached; props read once. |
| A3 | Launcher scans Node only when none is usable after the env load. Directory args quoted. Launcher rewritten by generation stamp, not full-text compare. `ensureDir` quotes its path. |
| B1 | Proot terminal tools use `buildToolDirect`: guest launcher under PRoot, no Python. Both terminal pages build the spec off the main thread and drop it if method/project changed. Chroot and automated commands stay on `build()`. |
| B2 | GitHub probe: detect + whoami in one guest script. Diff: one script, tracked files use `diff HEAD`, untracked use `--no-index`. Stale project rejected before render. |
| B3 | Streamed jobs: bounded queue, one pending drain, `onDone` after the queue empties. Cancel before process start destroys the process on attach. |
| C1 | Normal-history scroll is one clamp per event. Fling uses `postOnAnimation` and aborts on detach, mouse-mode or buffer change. Alternate-screen fling no longer uses history bounds. Button renamed Transcript. Paste on main thread jumps to live. Session exit no longer logs the transcript. Resize coalesced to one post; extra `SIGWINCH` to the tracer pid removed. |
| D1 | `gpu_apply` looks up only the ICD the selected backend uses. Software/VirGL look up none. Both script copies kept identical. |
| D2 | Setup-generated `cli-tools.env` keeps PATH, selected Node and auth. NVM functions and completion load only for interactive shells. |

## Checks

- `:app:compileDebugKotlin` — pass (pre-existing deprecation warnings only).
- `:terminal-view:compileDebugJavaWithJavac` — pass.
- `tools/app/test_alpaquita_app_runtime_contract.sh` — pass.
- `tools/app/test_prebuilt_runtime_immutable_contract.sh` — pass. The work order expected a missing-`helpers.rc` failure; this checkout passes all three sections. Assertion not weakened.

## Review fixes (TERMINAL_REVIEW_2 R1–R8)

Code landed. Not compiled and not device-tested. Compile was started but did not finish: the machine was saturated by other Gradle builds, so no result is claimed.

| Item | Change |
|---|---|
| R1 | Commit and Discard go through `ShellCommandRunner.runCaptureExit`, so the linker step applies. Discard classifies first: tracked restores with `checkout HEAD`, untracked removes with `git clean -f`. No `rm -rf`. Commit is two prefixed commands, `git add` then `git commit`, message passed as a literal argument. Exit code checked before success UI. |
| R2 | Stream completion uses its own `completed` flag, set once. The drain flag is released after the pass, then a follow-up is posted only if lines remain or the reader has finished. `onDone` fires once. |
| R3 | Diff checks HEAD and index separately with `:(literal)` pathspecs. Staged deletion stays a HEAD diff. Unborn repo with a staged file diffs against an empty tree. `--no-index` exit 1 counts as a difference; exit 2+ is an error, not "No changes". A request generation drops stale results. Newline in filenames still unsafe. |
| R4 | `rc=$?` is saved immediately after `gh api`, before `echo`. A missing or malformed frame is not treated as authenticated. Login must match GitHub username rules. Probe calls for the same key join one in-flight request, cache 5s, keyed by method + rootfs + generation, cleared on logout. |
| R5 | Ordinary server start uses the cached health. `/proc` only when forced, stale, missing or dead. In-flight work is keyed by rootfs + GPU config, so a new config is not dropped behind an old one. Hardware properties read once per cold detection; a second cold caller waits on the lock. |
| R6 | `-nt` mtime selection removed. A selected `FLUX_NODE_BIN` that runs is kept and its directory prepended to PATH. Version discovery runs only when the selection is missing or dead, then persisted. NVM and completion stay interactive-only. |
| R7 | `bootstrap.tar` extraction moved off the main thread with one in-flight flag. Tab slot reserved on the main thread before async work, released on success, error and cancel. Stale generation or destroyed activity does not attach. Launcher install failure aborts launch. Script written to a temp file and renamed; stamp published after success. |
| R8 | "Jump to live" added to the shared key toolbar (`setTopRow(0)` only). Transcript stays read-only. Resize coalesced onto `postOnAnimation`. |

## Not done

- No device trials (5 cold / 20 warm). No APK built, no hash.
- R1–R8 compile not confirmed. See note above.
- No commits. Work order asked for separate tested commits; nothing is committed.
- D2 reaches installed runtimes only through the versioned runtime update. Setup was not run.
- Chroot path untested.
- Rollback: `git checkout -- <file>` per file. No commit to revert.
