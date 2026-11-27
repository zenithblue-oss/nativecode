// Fast PRoot PTY lifetime guard.
//
// Exec mode (production, spawned by Termux TerminalSession in place of proot):
//   libfast_pty_guard.so <expected_ppid> <prefix> <lock_name> -- <cmd> [args...]
//
//   1. Normalize SIGQUIT disposition/mask before PDEATHSIG: Termux PTY
//      child code unblocks inherited signals but does not reset dispositions;
//      ignored dispositions survive exec. Force SIG_DFL + unblock SIGQUIT,
//      fail closed on error, then prctl(PR_SET_PDEATHSIG, SIGQUIT) relative
//      to the app parent thread. SIGQUIT lets the packaged PRoot (5.1.0) run
//      its kill_all_tracees() handler and kill SETSID/setpgrp/double-fork
//      tracees while they are still ptrace tracees; SIGKILL would prevent
//      that handler from running. Before exec, SIGQUIT's default action
//      terminates the guard; after exec, PRoot handles SIGQUIT and cleans
//      its tracee list.
//   2. Verify getppid() == expected_ppid (closes the fork/exec race).
//   3. Verify we are the PTY session/process-group leader before relying on killpg.
//   4. Acquire the hardened shared container lock (LOCK_SH|LOCK_NB), fail
//      closed: zero PRoot start when the lock is unavailable.
//   5. Fork internal watchdog BEFORE exec; handshake via pipe ensures ZERO
//      PRoot start on watchdog failure (fail-closed).
//   6. Keep the lock fd alive across execve so the outer PRoot holds the
//      shared lease for its whole lifetime; every other inherited descriptor
//      is dropped.
//   7. execve() the exact typed PRoot argv. PR_SET_PDEATHSIG survives exec.
//
// Exit codes (exec mode): 126 parent mismatch / setup failure,
// 125 lock open failure, 124 lock busy, 127 exec failure.
//
// Watchdog mode (legacy external, still used for host tests):
//   libfast_pty_guard.so --watchdog <outer_pid>
//
//   Internal watchdog (production):
//   - PDEATHSIG disabled/cleared
//   - closes container lock fd, closes PTY stdin/stdout/stderr -> /dev/null
//   - closes all unrelated inherited fds
//   - enters own session/process group
//   - monitors exact outer guard/PRoot parent relationship (getppid + starttime)
//   - never uses process-name killing
//   - when parent dies/zombies: kill only the stored outer PRoot process group
//     after verification, then exit.

#include "native_container_lock.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <initializer_list>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <poll.h>

namespace {

const int EXIT_PARENT_MISMATCH = 126;
const int EXIT_LOCK_OPEN_FAILED = 125;
const int EXIT_LOCK_BUSY = 124;
const int EXIT_EXEC_FAILED = 127;

#ifdef __ANDROID__
#include <android/log.h>
#define GUARD_LOG(...) __android_log_print(__VA_ARGS__)
#else
#define GUARD_LOG(...) do {} while (0)
#endif

void fail(const char* reason) {
    dprintf(2, "fast_pty_guard: %s (errno=%d: %s)\n", reason, errno,
            strerror(errno));
    GUARD_LOG(ANDROID_LOG_ERROR, "fast_pty_guard",
                  "FAIL %s (errno=%d: %s)", reason, errno,
                  strerror(errno));
}

bool parse_pid_arg(const char* text, long* out) {
    if (text == nullptr || *text == '\0') return false;
    for (const char* p = text; *p != '\0'; ++p) {
        if (!isdigit(static_cast<unsigned char>(*p))) return false;
    }
    errno = 0;
    char* end = nullptr;
    const long value = strtol(text, &end, 10);
    if (errno != 0 || end == nullptr || *end != '\0') return false;
    if (value <= 1 || value > 0x7fffffffL) return false;
    *out = value;
    return true;
}

void close_unneeded_fds_except(int keep_fd) {
    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir != nullptr) {
        const int dir_fd = dirfd(self_dir);
        struct dirent* entry;
        while ((entry = readdir(self_dir)) != nullptr) {
            const int fd = atoi(entry->d_name);
            if (fd > 2 && fd != dir_fd && fd != keep_fd) close(fd);
        }
        closedir(self_dir);
        return;
    }
    for (int fd = 3; fd < 65536; ++fd) {
        if (fd != keep_fd) close(fd);
    }
}

void close_all_fds_except(std::initializer_list<int> keep) {
    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir != nullptr) {
        const int dir_fd = dirfd(self_dir);
        struct dirent* entry;
        while ((entry = readdir(self_dir)) != nullptr) {
            const int fd = atoi(entry->d_name);
            if (fd <= 2) continue;
            if (fd == dir_fd) continue;
            bool keep_it = false;
            for (int k : keep) if (fd == k) { keep_it = true; break; }
            if (!keep_it) close(fd);
        }
        closedir(self_dir);
        return;
    }
    for (int fd = 3; fd < 65536; ++fd) {
        bool keep_it = false;
        for (int k : keep) if (fd == k) { keep_it = true; break; }
        if (!keep_it) close(fd);
    }
}

bool proc_alive_nonzombie(long pid) {
    errno = 0;
    if (kill(static_cast<pid_t>(pid), 0) != 0) {
        return errno == EPERM;
    }
    char path[64];
    snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
    FILE* f = fopen(path, "r");
    if (f == nullptr) {
        return kill(static_cast<pid_t>(pid), 0) == 0 || errno == EPERM;
    }
    char line[512];
    const char* state = nullptr;
    if (fgets(line, sizeof(line), f) != nullptr) {
        const char* paren = strrchr(line, ')');
        if (paren != nullptr && paren[1] == ' ' && paren[2] != '\0') {
            state = paren + 2;
        }
    }
    fclose(f);
    if (state == nullptr) return true;
    return *state != 'Z';
}

bool get_starttime(long pid, unsigned long long* out) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[1024];
    if (!fgets(line, sizeof(line), f)) { fclose(f); return false; }
    fclose(f);
    const char* paren = strrchr(line, ')');
    if (!paren) return false;
    // field 22 is starttime, after ') <state> <ppid> ...' — parse by counting spaces
    // stat format: pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime priority nice num_threads itrealvalue starttime
    // starttime is field 22, which is the 20th field after the comm's ')'
    const char* p = paren + 2; // skip ") "
    // we need to skip 19 fields to reach starttime (field 22 - 3 already accounted? Let's just count)
    // Easier: sscanf after paren
    // Use a simple approach: tokenise by spaces
    char tmp[1024];
    strncpy(tmp, p, sizeof(tmp)-1); tmp[sizeof(tmp)-1]=0;
    char* saveptr = nullptr;
    char* tok = strtok_r(tmp, " ", &saveptr);
    int field = 3; // state is field 3, we are at state
    while (tok) {
        if (field == 22) {
            *out = strtoull(tok, nullptr, 10);
            return true;
        }
        tok = strtok_r(nullptr, " ", &saveptr);
        field++;
    }
    return false;
}

bool get_ppid(long pid, pid_t* out) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%ld/status", pid);
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[256];
    bool ok = false;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "PPid:", 5) == 0) {
            int ppid = 0;
            if (sscanf(line + 5, "%d", &ppid) == 1) {
                *out = static_cast<pid_t>(ppid);
                ok = true;
            }
            break;
        }
    }
    fclose(f);
    return ok;
}

bool verify_still_expected(long pid, long expected_pgid, long expected_sid, unsigned long long expected_start, uid_t expected_uid) {
    // Check pid still exists and starttime matches (PID reuse protection)
    unsigned long long cur_start = 0;
    if (!get_starttime(pid, &cur_start)) return false;
    if (cur_start != expected_start) return false;
    // Check pgid and sid still match
    pid_t cur_pgid = getpgid(static_cast<pid_t>(pid));
    pid_t cur_sid = getsid(static_cast<pid_t>(pid));
    if (cur_pgid != static_cast<pid_t>(expected_pgid)) return false;
    if (cur_sid != static_cast<pid_t>(expected_sid)) return false;
    // Check uid still matches (via /proc/pid/status Uid)
    char path[64];
    snprintf(path, sizeof(path), "/proc/%ld/status", pid);
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[256];
    bool ok = false;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "Uid:", 4) == 0) {
            unsigned int ruid = 0;
            if (sscanf(line + 4, "%u", &ruid) == 1 && ruid == expected_uid) ok = true;
            break;
        }
    }
    fclose(f);
    return ok;
}

// Scan /proc read-only to verify at least one remaining process with
// real uid == recorded outer uid, pgrp == recorded outer pgid, session == recorded outer sid.
// If zero matching members: nothing left, do not killpg.
// If matching members exist and no mismatched uid/session holders: safe to killpg.
// If any pgid holder has unexpected uid/sid: do NOT kill (pgid reuse, safety).
// Never uses process-name matching.
bool has_valid_remaining_pgrp_members(pid_t target_pgid, pid_t target_sid, uid_t target_uid) {
    DIR* proc = opendir("/proc");
    if (!proc) return false;
    bool found_match = false;
    bool found_mismatch = false;
    struct dirent* entry;
    while ((entry = readdir(proc)) != nullptr) {
        const char* name = entry->d_name;
        if (name[0] < '0' || name[0] > '9') continue;
        bool is_num = true;
        for (const char* p = name; *p; ++p) {
            if (!isdigit(static_cast<unsigned char>(*p))) { is_num = false; break; }
        }
        if (!is_num) continue;
        pid_t pid = static_cast<pid_t>(atoi(name));
        if (pid <= 1) continue;
        pid_t cur_pgid = getpgid(pid);
        if (cur_pgid < 0) continue;
        if (cur_pgid != target_pgid) continue;
        pid_t cur_sid = getsid(pid);
        if (cur_sid < 0) { found_mismatch = true; continue; }
        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/status", pid);
        FILE* f = fopen(path, "r");
        if (!f) { found_mismatch = true; continue; }
        char line[256];
        uid_t ruid = (uid_t)-1;
        bool got_uid = false;
        while (fgets(line, sizeof(line), f)) {
            if (strncmp(line, "Uid:", 4) == 0) {
                unsigned int u = 0;
                if (sscanf(line + 4, "%u", &u) == 1) { ruid = static_cast<uid_t>(u); got_uid = true; }
                break;
            }
        }
        fclose(f);
        if (!got_uid) { found_mismatch = true; continue; }
        if (ruid == target_uid && cur_sid == target_sid) {
            found_match = true;
        } else {
            found_mismatch = true;
        }
    }
    closedir(proc);
    if (found_mismatch) {
        GUARD_LOG(ANDROID_LOG_WARN, "fast_pty_guard",
                  "watchdog: pgid %d has unexpected uid/sid members, not killing", target_pgid);
        return false;
    }
    return found_match;
}

int run_watchdog_internal(long watch_pid, long watch_pgid, long watch_sid, unsigned long long watch_start, uid_t watch_uid, int hs_read_fd) {
    // Child watchdog already in its own session, stdio redirected, lock closed
    // Signal parent that we're ready
    char c = 'R';
    if (write(hs_read_fd, &c, 1) != 1) _exit(1);
    close(hs_read_fd);
    // Monitor loop
    for (;;) {
        bool parent_dead = !proc_alive_nonzombie(watch_pid);
        if (!parent_dead && getppid() == 1) parent_dead = true;
        if (!parent_dead) {
            pid_t outer_ppid = -1;
            if (get_ppid(watch_pid, &outer_ppid) && outer_ppid == 1) parent_dead = true;
        }
        // Also check if the watched pid's starttime diverged (PID reuse)
        if (!parent_dead) {
            unsigned long long cur_start = 0;
            if (!get_starttime(watch_pid, &cur_start) || cur_start != watch_start) {
                parent_dead = true;
            }
        }
        if (parent_dead) {
            // Before killpg, ensure this still represents the intended PRoot group.
            // If pid still exists, verify starttime/pgid/sid/uid to avoid PID-reuse kill.
            // If pid gone (original guard/PRoot died), kill the group to reap guests;
            // the stored pgid still names the old group (guests retain it) and reuse
            // of that numeric pgid for a new unrelated leader is unlikely within the
            // short race window. We check PID reuse: if pid exists but starttime
            // mismatch, it's reuse -> do NOT kill.
            unsigned long long cur_start = 0;
            bool pid_exists = get_starttime(watch_pid, &cur_start);
            if (pid_exists) {
                if (cur_start != watch_start) {
                    GUARD_LOG(ANDROID_LOG_WARN, "fast_pty_guard",
                              "watchdog: pid %ld reused (start %llu != %llu), not killing pgid %ld",
                              watch_pid, cur_start, watch_start, watch_pgid);
                    _exit(0);
                }
                if (verify_still_expected(watch_pid, watch_pgid, watch_sid, watch_start, watch_uid)) {
                    kill(static_cast<pid_t>(-watch_pgid), SIGKILL);
                } else {
                    GUARD_LOG(ANDROID_LOG_WARN, "fast_pty_guard",
                              "watchdog: verification failed, not killing pgid %ld", watch_pgid);
                }
            } else {
                // pid gone - verify remaining group identity before killpg.
                // Scan /proc read-only: need at least one remaining process with
                // real uid == recorded outer uid, pgrp == recorded outer pgid,
                // session == recorded outer sid. If zero: nothing left, exit.
                // If matching exists: old group still occupied, killpg may proceed.
                // If mismatched uid/sid holders: do NOT kill (pgid reuse, safety).
                if (!has_valid_remaining_pgrp_members(static_cast<pid_t>(watch_pgid),
                                                      static_cast<pid_t>(watch_sid),
                                                      watch_uid)) {
                    GUARD_LOG(ANDROID_LOG_WARN, "fast_pty_guard",
                              "watchdog: pid %ld gone but no valid pgrp %ld members, not killing",
                              watch_pid, watch_pgid);
                    _exit(0);
                }
                kill(static_cast<pid_t>(-watch_pgid), SIGKILL);
            }
            _exit(0);
        }
        const struct timespec pause = {0, 20 * 1000 * 1000};
        nanosleep(&pause, nullptr);
    }
}

// Legacy external watchdog mode for host tests
int run_watchdog(long watch_pid) {
    for (;;) {
        if (!proc_alive_nonzombie(watch_pid)) {
            kill(static_cast<pid_t>(-watch_pid), SIGKILL);
            _exit(0);
        }
        if (getppid() == 1) {
            kill(static_cast<pid_t>(-watch_pid), SIGKILL);
            _exit(0);
        }
        const struct timespec pause = {0, 20 * 1000 * 1000};
        nanosleep(&pause, nullptr);
    }
}

int run_exec_guard(int argc, char** argv) {
    if (argc < 6) { fail("usage/argc"); return EXIT_PARENT_MISMATCH; }

    long expected_parent = 0;
    if (!parse_pid_arg(argv[1], &expected_parent)) { fail("bad expected ppid"); return EXIT_PARENT_MISMATCH; }

    const char* prefix = argv[2];
    const char* lock_name = argv[3];
    if (prefix == nullptr || prefix[0] != '/') { fail("bad prefix"); return EXIT_PARENT_MISMATCH; }
    if (lock_name == nullptr || lock_name[0] == '\0' ||
        strchr(lock_name, '/') != nullptr) {
        fail("bad lock name");
        return EXIT_PARENT_MISMATCH;
    }
    if (strcmp(argv[4], "--") != 0) { fail("missing -- separator"); return EXIT_PARENT_MISMATCH; }
    char* const* child_argv = argv + 5;
    if (child_argv[0] == nullptr || child_argv[0][0] == '\0') {
        fail("empty child argv");
        return EXIT_PARENT_MISMATCH;
    }

    // Normalize SIGQUIT before PDEATHSIG: ignored disposition + blocked mask survive fork/exec.
    {
        struct sigaction sa{};
        sa.sa_handler = SIG_DFL;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        if (sigaction(SIGQUIT, &sa, nullptr) != 0) { fail("sigaction SIGQUIT DFL"); return EXIT_PARENT_MISMATCH; }
        sigset_t set;
        sigemptyset(&set);
        sigaddset(&set, SIGQUIT);
        if (sigprocmask(SIG_UNBLOCK, &set, nullptr) != 0) { fail("sigprocmask unblock SIGQUIT"); return EXIT_PARENT_MISMATCH; }
    }
    if (prctl(PR_SET_PDEATHSIG, SIGQUIT) != 0) { fail("prctl PDEATHSIG"); return EXIT_PARENT_MISMATCH; }
    if (getppid() != static_cast<pid_t>(expected_parent)) {
        fail("expected-parent mismatch");
        return EXIT_PARENT_MISMATCH;
    }

    // Verify we are the expected PTY session/process-group leader before relying on killpg
    pid_t self = getpid();
    if (self != getsid(0) || self != getpgrp()) {
        fail("not session/process-group leader");
        return EXIT_PARENT_MISMATCH;
    }

    char err[256];
    const int lock_fd =
        nativecode_open_container_lock(prefix, lock_name, err, sizeof(err));
    if (lock_fd < 0) { fail("open container lock"); return EXIT_LOCK_OPEN_FAILED; }
    if (nativecode_flock_shared_nb(lock_fd) != 0) {
        fail("flock SH|NB busy");
        close(lock_fd);
        return EXIT_LOCK_BUSY;
    }
    if (fcntl(lock_fd, F_SETFD, 0) != 0) {
        fail("clear FD_CLOEXEC");
        close(lock_fd);
        return EXIT_LOCK_OPEN_FAILED;
    }

    unsetenv("LD_PRELOAD");
    // Do not close fds yet - need pipe for handshake

    // Create handshake pipe
    int hs[2];
    if (pipe(hs) != 0) { fail("pipe"); close(lock_fd); return EXIT_PARENT_MISMATCH; }
    (void)fcntl(hs[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(hs[1], F_SETFD, FD_CLOEXEC);

    pid_t outer_pid = getpid();
    pid_t outer_pgid = getpgrp();
    pid_t outer_sid = getsid(0);
    uid_t outer_uid = getuid();
    unsigned long long outer_start = 0;
    if (!get_starttime(outer_pid, &outer_start)) {
        fail("get starttime");
        close(lock_fd); close(hs[0]); close(hs[1]);
        return EXIT_PARENT_MISMATCH;
    }

    pid_t wdog = fork();
    if (wdog < 0) {
        fail("fork watchdog");
        close(lock_fd); close(hs[0]); close(hs[1]);
        return EXIT_PARENT_MISMATCH;
    }
    if (wdog == 0) {
        // Watchdog child - must fail closed BEFORE sending READY.
        close(hs[0]);
        // Disable pdeathsig (watchdog must survive app death)
        prctl(PR_SET_PDEATHSIG, 0);
        // Close container lock fd (only outer guard/PRoot holds it)
        close(lock_fd);
#ifndef __ANDROID__
        // Host-only injection for fail-closed tests: force watchdog arm failure
        // before READY. This is never compiled into the Android release ELF.
        if (getenv("FAST_PTY_GUARD_FORCE_WATCHDOG_FAIL") != nullptr) _exit(42);
#endif
        // open /dev/null must succeed, each dup2 must succeed, else _exit nonzero
        // BEFORE sending watchdog READY. Do not retain PTY fd 0/1/2.
        int devnull = open("/dev/null", O_RDWR);
        if (devnull < 0) _exit(43);
        if (dup2(devnull, 0) < 0) _exit(44);
        if (dup2(devnull, 1) < 0) _exit(45);
        if (dup2(devnull, 2) < 0) _exit(46);
        if (devnull > 2) close(devnull);
        // Close all unrelated fds
        close_all_fds_except({hs[1]});
        // Enter own session/process group
        if (setsid() < 0) _exit(47);
        // Signal ready (hs[1] still open, 0/1/2 are now /dev/null)
        int ret = run_watchdog_internal(outer_pid, outer_pgid, outer_sid, outer_start, outer_uid, hs[1]);
        _exit(ret);
    }
    // Parent guard
    close(hs[1]);
    // Wait for watchdog ready with timeout (2s)
    struct pollfd pfd;
    pfd.fd = hs[0];
    pfd.events = POLLIN;
    int pr = poll(&pfd, 1, 2000);
    if (pr <= 0 || !(pfd.revents & POLLIN)) {
        fail("watchdog handshake timeout");
        close(hs[0]);
        close(lock_fd);
        kill(wdog, SIGKILL);
        int st; waitpid(wdog, &st, 0);
        return EXIT_PARENT_MISMATCH;
    }
    char c;
    ssize_t n = read(hs[0], &c, 1);
    close(hs[0]);
    if (n != 1 || c != 'R') {
        fail("watchdog handshake failed");
        close(lock_fd);
        kill(wdog, SIGKILL);
        int st; waitpid(wdog, &st, 0);
        return EXIT_PARENT_MISMATCH;
    }
    // Watchdog is armed - check it didn't already exit (should be still running)
    int wstatus;
    pid_t wret = waitpid(wdog, &wstatus, WNOHANG);
    if (wret == wdog) {
        fail("watchdog died after handshake");
        close(lock_fd);
        return EXIT_PARENT_MISMATCH;
    }
    // Now safe to clean up fds and exec
    // P4 hygiene: drop every other inherited descriptor, keep lock_fd
    close_unneeded_fds_except(lock_fd);
    execv(child_argv[0], const_cast<char* const*>(child_argv));
    GUARD_LOG(ANDROID_LOG_ERROR, "fast_pty_guard",
                  "execv failed target=%s", child_argv[0]);
    fail("execv failed");
    // If exec fails, the watchdog will see parent gone and kill the group (which is just this process's group, already dying)
    // Also need to kill watchdog since we failed to exec
    kill(wdog, SIGKILL);
    close(lock_fd);
    return EXIT_EXEC_FAILED;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 3 && strcmp(argv[1], "--watchdog") == 0) {
        long watch_pid = 0;
        if (!parse_pid_arg(argv[2], &watch_pid)) return 2;
        run_watchdog(watch_pid);
        return 0;
    }
    return run_exec_guard(argc, argv);
}
