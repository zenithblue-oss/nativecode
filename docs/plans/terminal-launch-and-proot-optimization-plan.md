# Plan: Terminal Launch & PRoot Performance Optimization

## Objective
Eliminate startup latency, main-thread blocking I/O, and redundant process wrappers when launching interactive terminal sessions and logging into the PRoot guest as the `flux` user, without breaking non-interactive command consumers across the application.

---

## 1. System Topology & Shared Consumers of PRoot Logic

The user inquiry raised a critical architectural constraint:
> *"see that if the terminal logic proot logic is used somewhere else if yes we cant change soley here"*

An exhaustive audit of the codebase confirms that **PRoot logic and LinuxCommandBuilder are heavily shared across the application**, far beyond the interactive terminal. Changing PRoot mechanics globally without isolation risks breaking core features.

### Complete Dependency Matrix

| Consumer / Caller | Invocation Method | Purpose / Execution Mode | Impact if Changed Globally |
|---|---|---|---|
| **Interactive Terminal Tabs** (`MainActivity.kt:2244`) | `LinuxCommandBuilder.buildInteractive()` | User terminal tab (`/bin/bash -l`, PTY master/slave) | Target of this optimization |
| **Workspace Terminal Tabs** (`MainActivity.kt:20035`) | `LinuxCommandBuilder.buildInteractive()` | Workspace terminal tab scoped to project CWD | Target of this optimization |
| **Tool Launchers** (`MainActivity.kt:2247`, `20038`) | `LinuxCommandBuilder.build()` | AI CLI tools (`opencode`, `codex`, `claude`, etc.) | Runs in PTY terminal window |
| **Git Operations** (`GitRepoService.kt:19, 35, 58`) | `LinuxCommandBuilder.build()` | Background git branch, log, status, diff queries | Non-interactive background `ProcessBuilder` via `ShellCommandRunner` |
| **GitHub CLI Service** (`GitHubCliService.kt:1035`) | `LinuxCommandBuilder.build()` | GitHub CLI queries (`gh auth status`, `gh pr list`) | Non-interactive background `ProcessBuilder` via `ShellCommandRunner` |
| **Package Manager** (`AptInventoryService.kt:66, 71`) | `LinuxCommandBuilder.build()` | Checking installed APK/APT packages | Non-interactive background queries |
| **Marketplace Runner** (`PackageInstallRunner.kt:262`) | `LinuxCommandBuilder.build()` | Running package install scripts in guest | Terminal / ProcessBuilder |
| **Project Manager** (`ProjectManager.kt:37, 53`) | `LinuxCommandBuilder.build()` | `git clone`, `mkdir -p` project directories | Non-interactive background execution |
| **CLI Auth Service** (`CliAuthService.kt:862`) | `LinuxCommandBuilder.build()` | Validating CLI tokens and auth status | Non-interactive background execution |
| **AI Provision State** (`AiCliProvisionState.kt:59`) | `LinuxCommandBuilder.build()` | Probing binary availability in guest | Non-interactive background execution |
| **Desktop GUI Lifecycle** (`start_gui.sh`, `stop_gui.sh`) | Shell scripts invoking `proot-distro` | Starting XFCE desktop & X11 server | Independent shell scripts relying on `proot-distro` container metadata |

### Key Takeaway
`LinuxCommandBuilder` and `ProotCommandBuilder` have two distinct entry points:
1. `buildInteractive(...)` — exclusively used by interactive terminal sessions (PTY).
2. `build(...)` — used by non-interactive background jobs, git queries, package scans, and tool scripts.

**We cannot replace PRoot globally with a naive stripped launcher**, because non-interactive background services rely on specific environment inheritance, safe directory configs, and Android 16 W^X linker wrapping. Past attempts (W12 regression) failed when a global replacement stripped environment variables (`GIT_PAGER`, `safe.directory`, OAuth tokens) and broke background services.

---

## 2. Inefficiencies Identified in Current Terminal Launch

### Inefficiency A: Massive Blocking UI-Thread I/O on Every Terminal Launch
- **Location:** `MainActivity.kt:2169` (`ensureBootstrapExtracted()`) called by `MainActivity.kt:2234` (`startNewTerminalSession`).
- **Mechanism:** When `usr/lib/libtermux-exec.so` exists, it calls `TermuxHostPaths.applyPackageToExtractedPrefix(filesDir)` every single time a terminal tab is opened.
- **Cost:** Recursively walks `usr/bin`, `usr/etc`, `usr/share`, `usr/lib/python3.14/...`, `usr/include/...` (thousands of files), reads the first 512 bytes of every file up to 2MB to search for `"com.termux"`, and writes `usr/etc/fluxlinux-host.env` to disk.
- **Root Cause:** Missing single-run sentinel. Package path rewriting only needs to run once after extraction.

### Inefficiency B: Synchronous Subprocess Spawning, `/proc` Scanning & Sleep on UI Thread
- **Location:** `HostServerController.kt` via `ProotCommandBuilder.kt:78, 97` (`HostServerController.ensureGuestGpu(ctx)`).
- **Mechanism:**
  - `GuestGpuConfig.applyToRootfs(ctx)` writes 4 configuration files (`gpu_mode`, `gpu_backend`, `gpu_vendor`, `gpu_method`) to the rootfs disk synchronously on the UI thread.
  - `status(Kind.VENUS)` and `status(Kind.PULSE)` call `findPids()`: traverses all of `/proc`, reading `/proc/<pid>/cmdline` for every running process on Android twice on the UI thread.
  - If Venus is not running: executes `virgl_test_server_android --help` subprocess (waits up to 2s), starts process, calls `Thread.sleep(800)` on the UI thread, executes `chmod 777` subprocess (waits 1s).
  - If PulseAudio is not running: starts process, calls `Thread.sleep(800)` on the UI thread, executes `pacmd` subprocess (waits 4s).
- **Cost:** Causes 1.6s–7s UI thread freeze if servers are starting, and 50–200ms freeze even when servers are running.
- **Root Cause:** Tying host hardware daemon lifecycle checks to terminal command string generation. Non-interactive background commands (like `git status`) also trigger this check.

### Inefficiency C: 5-Process Layer Chain & Python Interpreter Overhead
- **Location:** `ProotCommandBuilder.kt:80-84, 103-108`.
- **Process Chain:**
  1. `JNI.createSubprocess` forks PTY.
  2. Executes `/system/bin/linker64 libbash.so -c "exec /system/bin/linker64 python .../proot-distro login ..."`
  3. `libbash.so` parses `-c` string and execs Python 3.14.
  4. Python 3.14 starts, initializes GIL, imports 20+ modules (`argparse`, `json`, `sysdata`, `locking`, `paths`, etc.).
  5. Python acquires flock, checks `/etc/passwd`, reads JSON `manifest.json`, probes 9 `/proc` paths, rewrites `/etc/profile.d/termux-profile.sh`, and calls `os.execvpe(libproot.so, ...)`.
  6. `libproot.so` initializes ptrace sandbox.
  7. Spawns Guest Process 1: `/bin/bash --noprofile --norc -c "export ...; exec /bin/bash -l"`.
  8. Spawns Guest Process 2: `/bin/bash -l` (Login shell).
- **Cost:** 150–350ms wasted on Python startup and redundant bash wrapping before PRoot even starts.

### Inefficiency D: Double Bash Invocation Inside Guest
- **Location:** `ProotCommandBuilder.kt:20, 24, 82, 107`.
- **Mechanism:** Starts `/bin/bash --noprofile --norc -c 'export GIT_CONFIG_COUNT=...; [ -r ... ] && ...; exec /bin/bash -l'` instead of launching `/bin/bash -l` directly with environment variables pre-populated.
- **Cost:** PRoot must trace an extra child process, dynamic linking of bash, argument parsing, and `execve` transition.

### Inefficiency E: 30+ Bind Mounts Linear Table Traversal in PRoot
- **Location:** `proot_cmd.py` in `proot-distro`.
- **Mechanism:** Attaches 30+ bind mounts (Dalvik caches, ART caches, `/apex`, `/system`, `/vendor`, Termux app cache, apps folder, multiple proc stubs).
- **Cost:** Every traced syscall (`stat`, `open`, `read`, `execve`) linearly scans the bind table in PRoot's user-space path resolution engine.

---

## 3. Phased Optimization Strategy

To satisfy the constraint that PRoot logic is shared across the codebase, we apply optimizations in 3 decoupled phases:

```
┌─────────────────────────────────────────────────────────────┐
│ Phase 1: Zero-Risk UI-Thread Unblocking (Immediate Win)     │
│ - One-time sentinel for ensureBootstrapExtracted()          │
│ - Async / cached HostServerController status (no UI sleep)  │
│ - Skip GPU/Pulse server check for non-interactive jobs      │
└─────────────────────────────────────────────────────────────┘
                               ↓
┌─────────────────────────────────────────────────────────────┐
│ Phase 2: Direct Fast Terminal Spawning (Interactive Only)    │
│ - Bypass Host Bash & Python for interactive PTY sessions    │
│ - Construct direct PRoot argv in Kotlin/C                   │
│ - Pre-inject GIT_CONFIG & GPU env via PRoot env vector      │
│ - Launch /bin/bash -l directly (eliminate guest double-bash)│
│ - Non-interactive build() remains 100% untouched            │
└─────────────────────────────────────────────────────────────┘
                               ↓
┌─────────────────────────────────────────────────────────────┐
│ Phase 3: Bind Mount & Profile Trimming                      │
│ - Profile binds: minimal CLI profile for terminal & git     │
│ - Cache sysdata / proc availability checks                  │
│ - Prevent repeated disk writes to /etc/profile.d            │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. Detailed Component Implementation Plan

### Phase 1: Main-Thread Unblocking (Non-Destructive)

#### 1.1 One-Time Sentinel for Bootstrap Package Rewriting
- **File:** `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt` & `TermuxHostPaths.kt`
- **Change:**
  Add a persistent marker file `File(filesDir, "usr/etc/.paths_rewritten")`:
  ```kotlin
  private fun ensureBootstrapExtracted() {
      val termuxExecFile = File(filesDir, "usr/lib/libtermux-exec.so")
      if (termuxExecFile.exists()) {
          val marker = File(filesDir, "usr/etc/.paths_rewritten")
          if (!marker.exists()) {
              TermuxHostPaths.applyPackageToExtractedPrefix(filesDir)
              marker.createNewFile()
          }
          return
      }
      // ... extract bootstrap ...
  }
  ```
- **Gain:** Saves 100–500ms of disk reads and recursive walks on every terminal launch.

#### 1.2 Decouple GPU Server Checks from Command Builders
- **File:** `app/src/main/java/com/zenithblue/nativecode/terminal/HostServerController.kt` & `ProotCommandBuilder.kt`
- **Change:**
  1. Remove `HostServerController.ensureGuestGpu(ctx)` from `ProotCommandBuilder.build(...)` (non-interactive git, gh, apk commands do not need GPU/Pulse servers).
  2. In `HostServerController.ensureServers(ctx)`:
     - Execute the daemon checks and process spawning on a background thread (`executor.execute`), never on the caller's thread.
     - Cache running status with an in-memory TTL (e.g. 5 seconds) instead of scanning `/proc` for every check.
     - Eliminate `Thread.sleep(800)` on the main thread.
  3. Call `ensureServers` once during Activity startup or when the user opens the Desktop/GUI page, not on every terminal tab open.
- **Gain:** Eliminates up to 1.6s–7s UI thread freeze.

---

### Phase 2: Direct Interactive PRoot Launch (Zero Python, Single Bash)

#### 2.1 Scope: Interactive Sessions Only (`buildInteractive`)
Leave `LinuxCommandBuilder.build(...)` untouched so that `GitRepoService`, `GitHubCliService`, `AptInventoryService`, and other background jobs continue executing reliably through the established `proot-distro` contract.

#### 2.2 Construct Direct PRoot Argv in `ProotCommandBuilder.buildInteractive`
Instead of generating:
```shell
exec /system/bin/linker64 python .../proot-distro login alpaquita -- /bin/bash --noprofile --norc -c '...'
```
Build direct PRoot arguments:
```kotlin
val prootBin = TermuxHostPaths.libProot(ctx).absolutePath
val rootfs = ProjectPathResolver.prootRootfsDir(ctx).absolutePath
val cwd = workDir?.takeIf { it.isNotBlank() } ?: (if (user == "root") "/root" else "/home/flux")

val args = arrayOf(
    "/system/bin/linker64",
    prootBin,
    "--kill-on-exit",
    "--link2symlink",
    "--sysvipc",
    "-L",
    "--change-id=${if (user == "root") "0:0" else "1000:1000"}",
    "--rootfs=$rootfs",
    "--cwd=$cwd",
    "--bind=/dev",
    "--bind=/proc",
    "--bind=/sys",
    "--bind=/dev/urandom:/dev/random",
    "--bind=/proc/self/fd:/dev/fd",
    "--bind=${TermuxHostPaths.PREFIX}/tmp:/tmp",
    "--bind=$rootfs/tmp:/dev/shm",
    "--bind=${TermuxHostPaths.PREFIX}/var/lib/proot-distro/sysdata/sys_empty:/sys/fs/selinux",
    "--bind=/storage/emulated/0:/sdcard",
    "--bind=/system",
    "--bind=/vendor",
    "--bind=/apex",
    "--bind=${TermuxHostPaths.PREFIX}",
    "/bin/bash",
    "-l"
)
```

#### 2.3 Direct Environment Forwarding via PTY Session
Pass required environment variables directly into the PTY session `env` map:
- `HOME`: `/home/flux` (or `/root`)
- `USER`: `flux` (or `root`)
- `LOGNAME`: `flux` (or `root`)
- `TERM`: `xterm-256color`
- `GIT_CONFIG_COUNT`: `1`
- `GIT_CONFIG_KEY_0`: `safe.directory`
- `GIT_CONFIG_VALUE_0`: `*`
- `PROOT_LOADER`: `TermuxHostPaths.libLoader(ctx).absolutePath`
- `PROOT_TMP_DIR`: `${TermuxHostPaths.PREFIX}/tmp`
- `PATH`: `/home/flux/.local/bin:/home/flux/bin:/home/flux/.cargo/bin:/opt/nodejs/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`

#### 2.4 Eliminate Double-Bash Inside Container
PRoot directly starts `/bin/bash -l`. No intermediate `/bin/bash --noprofile --norc -c` process.

- **Gain:**
  - Bypasses Host Bash (Process 1).
  - Bypasses Python 3.14 startup and module loading (Process 2).
  - Bypasses intermediate Guest Bash (Process 4).
  - Terminal drops from 5 process lifecycles to 2: `libproot.so` $\rightarrow$ `/bin/bash -l`.
  - Cold launch latency drops from ~800ms to <150ms.

---

### Phase 3: Sysdata & Bind Mount Optimization

1. **Verify Sysdata Parity**: Ensure `loadavg`, `stat`, `uptime`, `version`, `vmstat` under `containers/alpaquita/sysdata/` are bind-mounted if the host `/proc` entry is unreadable, preventing utility crashes (`htop`, `neofetch`, `ps`).
2. **Prevent Guest Profile Overwrites**: Avoid rewriting `/etc/profile.d/termux-profile.sh` on every session start.

---

## 5. Risk Analysis & Regressions Guard (Lessons from W12/W13)

| Risk Identified in Past Iterations | Root Cause in W12 | Safeguard in This Plan |
|---|---|---|
| **Environment Variable Stripping** | W12 used `env -i` which discarded `GIT_PAGER`, `safe.directory`, and auth tokens | Environment is explicitly preserved; `env -i` is NOT used; `build()` for background commands is unchanged |
| **Android 16 TargetSdk 37 W^X Denial** | Executing binaries in `app_data_file` directly fails with `EACCES` | All host ELF binaries (`libproot.so`, `libbash.so`) remain wrapped with `/system/bin/linker64` |
| **`libloader.so` Missing in PRoot** | PRoot failed to execute guest ELF binaries under Android 16 W^X | `PROOT_LOADER` is explicitly exported pointing to `nativeLibraryDir/libloader.so` |
| **Background Git / CLI Breakage** | Global replacement broke non-interactive tools | Phase 2 only alters `buildInteractive()`; `build()` continues using tested path |
| **Missing Sysdata Crash** | Guest tools crashed reading Android's blocked `/proc/stat` | Verified sysdata bind mappings match `proot-distro` behavior |

---

## 6. Verification & Acceptance Criteria

### Verification Checks
1. **Interactive Shell Latency**:
   - Time from tapping "+" tab to shell prompt (`localhost:~$`) ready measured on physical device. Target: <200ms.
2. **UI Thread Health**:
   - Zero `Thread.sleep` or file-tree walks on UI thread. Monitored via Logcat / StrictMode.
3. **Background Services Regression Suite**:
   - `GitRepoService`: Branch detection, commit history, diff viewer render without error.
   - `GitHubCliService`: `gh auth status` responds normally.
   - `CliAuthService`: AI CLI tools (`opencode`, `codex`, `claude`) detect and execute properly in project workspace.
4. **Physical Device Smoke Test**:
   - Run `tools/app/w13_full_app_smoke.sh --all` on both connected devices (`Y5WWBMJVOZSK4HU8` and `d30a1726`).
   - Run `./gradlew test` and `./gradlew assembleDebug`.
