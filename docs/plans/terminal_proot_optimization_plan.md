# PRoot Launch & Terminal Service Architecture Optimization Plan

## 1. Context & Problem Statement
* **PRoot Launch Latency**: The current pipeline (`TerminalSession` $\rightarrow$ `libbash.so` $\rightarrow$ `python3` $\rightarrow$ `proot-distro` $\rightarrow$ `proot` $\rightarrow$ `zsh`/`bash`) incurs a 100–400ms Python startup penalty on every terminal session/tab launch and adds unnecessary process wrapping.
* **Service Fragmentation**: 3+ separate foreground services (`AppTerminalService`, `ProjectTerminalService`, `BackgroundService`) lead to redundant notification channels, race conditions on Android 14+ FGS limits, and scattered session state inside `MainActivity`.
* **Goal**: Follow [termux-app](file:///home/user/repos/termux-lib/termux-app) architecture by executing PRoot directly with native binary parameters and consolidating terminal session lifecycle into a single unified service.

---

## 2. Termux vs. NativeCode Architecture Comparison

| Feature | Termux Architecture (`termux-app`) | Current NativeCode | Target NativeCode Architecture |
|---|---|---|---|
| **Terminal Launch** | Direct PTY spawn via JNI `TerminalSession` $\rightarrow$ binary + static argv/env | `TerminalSession` $\rightarrow$ `libbash.so` $\rightarrow$ `python3` $\rightarrow$ `proot-distro` $\rightarrow$ `proot` | `TerminalSession` $\rightarrow$ Direct `proot` binary with static argv & env vector |
| **Foreground Service** | Single `TermuxService` holding all sessions & background tasks | Split `AppTerminalService` + `ProjectTerminalService` + `BackgroundService` | Single unified `TerminalService` managing all tabs & project sessions |
| **Session Ownership** | Owned by `TermuxService` (survives Activity teardown / rotation) | Owned by `MainActivity` fields (`sessionsList`, `workspaceSessions`) | Owned by `TerminalService`, UI binds via `LocalBinder` |
| **Notification** | Single status notification (`N sessions active`) | Multiple independent notification channels & services | Single consolidated notification channel |

---

## 3. Detailed Technical Plan

### Component 1: Direct Native PRoot Launcher (Zero-Python Overhead)
* **File**: `app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt`
* **Changes**:
  1. Eliminate Python `proot-distro` invocation completely for proot logins and AI tool commands.
  2. Directly construct the PRoot command arguments vector:
     ```kotlin
     val prootBin = TermuxHostPaths.libProot(ctx).absolutePath // or PREFIX/bin/proot
     val rootfs = "${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/debian/rootfs"
     val binds = arrayOf(
         "--bind=/dev",
         "--bind=/proc",
         "--bind=/sys",
         "--bind=/dev/urandom:/dev/random",
         "--bind=${TermuxHostPaths.PREFIX}/tmp:/tmp",
         "--bind=$rootfs/tmp:/dev/shm",
         // Storage bindings if accessible
         "--bind=/storage/emulated/0:/sdcard"
     )
     val flags = arrayOf(
         "--kill-on-exit",
         "--link2symlink",
         "-0", // fake root capabilities
         "--change-id=${if (user == "root") "0:0" else "1000:1000"}",
         "--rootfs=$rootfs",
         "--cwd=${workDir ?: (if (user == "root") "/root" else "/home/flux")}"
     )
     ```
  3. Formulate direct guest shell / tool execution payload:
     - Interactive shell: `/bin/zsh -l` (or `/bin/bash -l`)
     - AI CLI / custom commands: `/usr/bin/env -i <GUEST_ENV> /bin/zsh -lc '<escaped_command>'` or direct tool binary execution.
  4. Pass `prootBin` and built `args` + `env` directly to `TerminalSession`.

### Component 2: Unified Terminal Service (`TerminalService`)
* **Files**:
  - `app/src/main/java/com/zenithblue/nativecode/terminal/TerminalService.kt` (new/refactored)
  - `app/src/main/AndroidManifest.xml`
  - Remove / Deprecate `AppTerminalService.kt` and `ProjectTerminalService.kt`
* **Changes**:
  1. Implement `TerminalService : Service()` with `LocalBinder`.
  2. Service maintains:
     - `appSessions: MutableList<TerminalSession>`
     - `projectSessions: MutableMap<String, MutableList<TerminalSession>>`
  3. Maintain a single unified Foreground Service notification (`"NativeCode Terminal"`, e.g. `"X active sessions"`).
  4. Automatically call `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()` when total active session count reaches 0.
  5. Retain `BackgroundService` exclusively for long-running XFCE desktop host services / onboarding extraction, or manage all FGS through unified `NativeCodeFgs` refcounting.

### Component 3: `MainActivity` & `TerminalView` Integration
* **File**: `app/src/main/java/com/zenithblue/nativecode/MainActivity.kt`
* **Changes**:
  1. Bind to `TerminalService` on `onCreate`/`onStart`.
  2. Query active session from `TerminalService` when attaching `TerminalView`.
  3. `createNewTerminalSession(type)` and `closeTerminalSession(index)` delegate directly to `TerminalService`.
  4. Preserve full compatibility with:
     - AI CLI tool launchers (`opencode`, `claude-code`, `codex`, `grok`, `agy`, `kiro`, `qwen-code`)
     - Project workspace directory mappings (`workDir` passing)
     - Chroot execution mode (`LinuxCommandBuilder.currentMethod == "chroot"`)

---

## 4. Compatibility & Safety Verification Matrix

| Area | Potential Risk | Mitigation |
|---|---|---|
| **AI CLI Tools** | Missing environment variables (`PATH`, `NVM_DIR`, `NODE_PATH`) | Direct proot launcher injects full guest environment (`/home/flux/.nvm/versions/node/...`, `~/.local/bin`, `~/.cargo/bin`) |
| **Chroot Mode** | Chroot relies on root helper `/data/local/tmp/nativecode_chroot.sh` | Keep `ChrootCommandBuilder` intact; `LinuxCommandBuilder` routes cleanly based on `currentMethod` |
| **Desktop / XFCE** | Desktop session notification collision | Desktop remains managed by `BackgroundService` with `OWNER_DESKTOP`, completely independent of terminal sessions |
| **File Permissions** | PRoot permission simulation inside Debian | Preserve `--link2symlink`, `--sysvipc`, and `-0` / `--change-id` flags in direct proot argv |

---

## 5. Execution Steps
1. Create `TerminalService.kt` and update manifest declarations.
2. Implement direct native binary launch in `ProotCommandBuilder.kt`.
3. Update `MainActivity.kt` session creation & binding logic.
4. Clean up deprecated `AppTerminalService` and `ProjectTerminalService`.
5. Build and verify end-to-end on target Android devices.
