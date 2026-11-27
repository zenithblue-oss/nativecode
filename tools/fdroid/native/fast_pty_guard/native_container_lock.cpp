#include "native_container_lock.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

#ifndef O_CLOEXEC
#define O_CLOEXEC 02000000
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0400000
#endif
#ifndef O_DIRECTORY
#define O_DIRECTORY 0200000
#endif
#ifndef O_PATH
#define O_PATH 010000000
#endif

void set_error(char* err, size_t err_len, const char* message) {
    if (err == nullptr || err_len == 0) return;
    const size_t n = strnlen(message, err_len - 1);
    memcpy(err, message, n);
    err[n] = '\0';
}

void set_error_errno(char* err, size_t err_len, const char* operation) {
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "%s: %s", operation, strerror(errno));
    set_error(err, err_len, buffer);
}

bool valid_name(const char* name) {
    if (name == nullptr || name[0] == '\0') return false;
    if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) return false;
    for (const char* p = name; *p; ++p) {
        if (*p == '/' || *p == '\0') return false;
    }
    return true;
}

int open_dir_at(int parent, const char* name, bool create, char* err, size_t err_len) {
    if (!valid_name(name)) {
        set_error(err, err_len, "invalid path component");
        errno = EINVAL;
        return -1;
    }
    if (create) {
        if (mkdirat(parent, name, 0700) != 0 && errno != EEXIST) {
            set_error_errno(err, err_len, "mkdirat");
            return -1;
        }
    }
    const int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        set_error_errno(err, err_len, "openat(dir)");
        return -1;
    }
    return fd;
}

int open_absolute_dir(const char* path, char* err, size_t err_len) {
    if (path == nullptr || path[0] != '/') {
        set_error(err, err_len, "prefix must be an absolute path");
        errno = EINVAL;
        return -1;
    }
    int fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) {
        set_error_errno(err, err_len, "open(/)");
        return -1;
    }
    const char* cursor = path + 1;
    while (*cursor) {
        while (*cursor == '/') ++cursor;
        if (*cursor == '\0') break;
        char component[256];
        size_t n = 0;
        while (cursor[n] != '\0' && cursor[n] != '/') {
            if (n + 1 >= sizeof(component)) {
                close(fd);
                set_error(err, err_len, "path component too long");
                errno = ENAMETOOLONG;
                return -1;
            }
            component[n] = cursor[n];
            ++n;
        }
        component[n] = '\0';
        cursor += n;
        const int next = openat(fd, component, O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        const int saved = errno;
        close(fd);
        if (next < 0) {
            errno = saved;
            set_error_errno(err, err_len, "openat(prefix)");
            return -1;
        }
        fd = next;
    }
    return fd;
}

}  // namespace

extern "C" int nativecode_open_container_lock(const char* prefix, const char* name,
                                              char* err, size_t err_len) {
    if (!valid_name(name)) {
        set_error(err, err_len, "invalid container name");
        errno = EINVAL;
        return -1;
    }
    int prefix_fd = open_absolute_dir(prefix, err, err_len);
    if (prefix_fd < 0) return -1;

    int var_fd = open_dir_at(prefix_fd, "var", true, err, err_len);
    close(prefix_fd);
    if (var_fd < 0) return -1;

    int lib_fd = open_dir_at(var_fd, "lib", true, err, err_len);
    close(var_fd);
    if (lib_fd < 0) return -1;

    int runtime_fd = open_dir_at(lib_fd, "proot-distro", true, err, err_len);
    close(lib_fd);
    if (runtime_fd < 0) return -1;

    int locks_fd = open_dir_at(runtime_fd, "locks", true, err, err_len);
    close(runtime_fd);
    if (locks_fd < 0) return -1;

    char lock_name[256];
    if (snprintf(lock_name, sizeof(lock_name), "%s.lock", name) >=
        static_cast<int>(sizeof(lock_name))) {
        close(locks_fd);
        set_error(err, err_len, "lock name too long");
        errno = ENAMETOOLONG;
        return -1;
    }

    const int lock_fd = openat(
        locks_fd, lock_name,
        O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW,
        0600);
    const int saved = errno;
    close(locks_fd);
    if (lock_fd < 0) {
        errno = saved;
        set_error_errno(err, err_len, "openat(lock)");
        return -1;
    }

    struct stat st {};
    if (fstat(lock_fd, &st) != 0) {
        const int st_err = errno;
        close(lock_fd);
        errno = st_err;
        set_error_errno(err, err_len, "fstat(lock)");
        return -1;
    }
    if (!S_ISREG(st.st_mode)) {
        close(lock_fd);
        set_error(err, err_len, "lock is not a regular file");
        errno = EINVAL;
        return -1;
    }
    if (st.st_uid != getuid()) {
        close(lock_fd);
        set_error(err, err_len, "lock is not owned by this uid");
        errno = EPERM;
        return -1;
    }
    if (st.st_nlink != 1) {
        close(lock_fd);
        set_error(err, err_len, "lock is multiply linked");
        errno = EINVAL;
        return -1;
    }
    if (fchmod(lock_fd, 0600) != 0 && errno != EPERM && errno != EACCES) {
        const int chmod_err = errno;
        close(lock_fd);
        errno = chmod_err;
        set_error_errno(err, err_len, "fchmod(lock)");
        return -1;
    }
    return lock_fd;
}

extern "C" int nativecode_flock_shared_nb(int fd) {
    if (fd < 0) return -EBADF;
    if (flock(fd, LOCK_SH | LOCK_NB) != 0) {
        if (errno == EINTR) return nativecode_flock_shared_nb(fd);
        return -errno;
    }
    return 0;
}

extern "C" int nativecode_flock_shared(int fd) {
    return nativecode_flock_shared_nb(fd);
}

extern "C" int nativecode_flock_exclusive_nb(int fd) {
    if (fd < 0) return -EBADF;
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        if (errno == EINTR) return nativecode_flock_exclusive_nb(fd);
        return -errno;
    }
    return 0;
}

extern "C" int nativecode_killpg(pid_t pid, int sig) {
    if (pid <= 1) return -EINVAL;
    if (sig != SIGTERM && sig != SIGKILL && sig != SIGINT && sig != SIGQUIT) {
        return -EINVAL;
    }
    if (::killpg(pid, sig) != 0) return -errno;
    return 0;
}

extern "C" int nativecode_setpgid(pid_t pid, pid_t pgid) {
    if (pid <= 1 || pgid < 0) return -EINVAL;
    if (setpgid(pid, pgid) != 0) return -errno;
    return 0;
}

namespace {

int open_real_dir_from_path_fd(int path_fd, char* err, size_t err_len) {
    const int fd = openat(path_fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) {
        set_error_errno(err, err_len, "openat(.)");
        return -1;
    }
    return fd;
}

bool valid_rel_component(const char* name) {
    return valid_name(name);
}

}  // namespace

extern "C" int nativecode_open_dir_nofollow(const char* path, char* err, size_t err_len) {
    const int path_fd = open_absolute_dir(path, err, err_len);
    if (path_fd < 0) return -1;
    const int fd = open_real_dir_from_path_fd(path_fd, err, err_len);
    const int saved = errno;
    close(path_fd);
    if (fd < 0) {
        errno = saved;
        return -1;
    }
    return fd;
}

extern "C" int nativecode_openat_nofollow(int dirfd, const char* name, int directory,
                                          char* err, size_t err_len) {
    if (dirfd < 0) {
        set_error(err, err_len, "invalid dirfd");
        errno = EBADF;
        return -1;
    }
    if (!valid_rel_component(name)) {
        set_error(err, err_len, "invalid path component");
        errno = EINVAL;
        return -1;
    }
    const int flags = directory
                          ? (O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)
                          : (O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    const int fd = openat(dirfd, name, flags);
    if (fd < 0) {
        set_error_errno(err, err_len, "openat");
        return -1;
    }
    const int check = nativecode_fstat_check(fd, directory, directory ? 0 : 1, err, err_len);
    if (check != 0) {
        close(fd);
        errno = -check;
        return -1;
    }
    return fd;
}

extern "C" int nativecode_openat_path(int dirfd, const char* rel, int directory,
                                      char* err, size_t err_len) {
    if (dirfd < 0 || rel == nullptr || rel[0] == '\0' || rel[0] == '/') {
        set_error(err, err_len, "relative path required");
        errno = EINVAL;
        return -1;
    }
    int current = dirfd;
    bool owns = false;
    const char* cursor = rel;
    while (*cursor) {
        while (*cursor == '/') ++cursor;
        if (*cursor == '\0') break;
        char component[256];
        size_t n = 0;
        while (cursor[n] != '\0' && cursor[n] != '/') {
            if (n + 1 >= sizeof(component)) {
                if (owns) close(current);
                set_error(err, err_len, "path component too long");
                errno = ENAMETOOLONG;
                return -1;
            }
            component[n] = cursor[n];
            ++n;
        }
        component[n] = '\0';
        cursor += n;
        while (*cursor == '/') ++cursor;
        const int last = (*cursor == '\0');
        const int next = nativecode_openat_nofollow(
            current, component, last ? directory : 1, err, err_len);
        if (owns) close(current);
        if (next < 0) return -1;
        current = next;
        owns = true;
    }
    if (!owns) {
        set_error(err, err_len, "relative path required");
        errno = EINVAL;
        return -1;
    }
    return current;
}

extern "C" int nativecode_fstat_check(int fd, int expect_dir, int require_nlink_one,
                                      char* err, size_t err_len) {
    if (fd < 0) return -EBADF;
    struct stat st {};
    if (fstat(fd, &st) != 0) {
        set_error_errno(err, err_len, "fstat");
        return -errno;
    }
    if (expect_dir) {
        if (!S_ISDIR(st.st_mode)) {
            set_error(err, err_len, "not a directory");
            errno = ENOTDIR;
            return -ENOTDIR;
        }
    } else {
        if (!S_ISREG(st.st_mode)) {
            set_error(err, err_len, "not a regular file");
            errno = EINVAL;
            return -EINVAL;
        }
        if (require_nlink_one && st.st_nlink != 1) {
            set_error(err, err_len, "file is multiply linked");
            errno = EINVAL;
            return -EINVAL;
        }
    }
    return 0;
}

extern "C" ssize_t nativecode_read_fd(int fd, char* buf, size_t cap, char* err, size_t err_len) {
    if (fd < 0 || buf == nullptr || cap == 0) {
        set_error(err, err_len, "invalid read");
        errno = EINVAL;
        return -1;
    }
    size_t filled = 0;
    while (filled < cap) {
        const ssize_t n = read(fd, buf + filled, cap - filled);
        if (n < 0) {
            if (errno == EINTR) continue;
            set_error_errno(err, err_len, "read");
            return -1;
        }
        if (n == 0) break;
        filled += static_cast<size_t>(n);
    }
    return static_cast<ssize_t>(filled);
}

extern "C" int nativecode_listdir_at(int dirfd, char* out, size_t cap, char* err, size_t err_len) {
    if (dirfd < 0 || out == nullptr || cap < 2) {
        set_error(err, err_len, "invalid listdir");
        errno = EINVAL;
        return -1;
    }
    const int dupfd = dup(dirfd);
    if (dupfd < 0) {
        set_error_errno(err, err_len, "dup");
        return -1;
    }
    DIR* dir = fdopendir(dupfd);
    if (dir == nullptr) {
        const int saved = errno;
        close(dupfd);
        errno = saved;
        set_error_errno(err, err_len, "fdopendir");
        return -1;
    }
    size_t used = 0;
    out[0] = '\0';
    while (true) {
        errno = 0;
        struct dirent* entry = readdir(dir);
        if (entry == nullptr) {
            if (errno != 0) {
                const int saved = errno;
                closedir(dir);
                errno = saved;
                set_error_errno(err, err_len, "readdir");
                return -1;
            }
            break;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        const size_t n = strlen(entry->d_name);
        if (used + n + 2 > cap) {
            closedir(dir);
            set_error(err, err_len, "directory listing too large");
            errno = ENOSPC;
            return -1;
        }
        memcpy(out + used, entry->d_name, n);
        used += n;
        out[used++] = '\0';
    }
    out[used] = '\0';
    closedir(dir);
    return 0;
}

extern "C" int nativecode_fd_executable(int fd) {
    if (fd < 0) return 0;
    struct stat st {};
    if (fstat(fd, &st) != 0) return 0;
    if (!S_ISREG(st.st_mode)) return 0;
    return (st.st_mode & (S_IXUSR | S_IXGRP | S_IXOTH)) != 0 ? 1 : 0;
}

namespace {

void close_unneeded_fds() {
    // Keep stdin/stdout/stderr. Drop every other inherited app descriptor.
    // close_range(2) is Linux syscall 436 even when libc is older than the kernel.
    constexpr unsigned long kCloseRangeSyscall = 436;
    const long result = syscall(kCloseRangeSyscall, 3U, ~0U, 0U);
    if (result == 0) return;
    for (int fd = 3; fd < 65536; ++fd) close(fd);
}

char* const kEmptyEnv[] = {nullptr};

}  // namespace

extern "C" int nativecode_spawn_setsid(const char* cwd, char* const argv[], char* const envp[],
                                       pid_t expected_parent, pid_t* child_pid,
                                       char* err, size_t err_len) {
    if (argv == nullptr || argv[0] == nullptr || child_pid == nullptr) {
        set_error(err, err_len, "invalid spawn arguments");
        errno = EINVAL;
        return -1;
    }
    if (expected_parent <= 1) {
        set_error(err, err_len, "invalid parent pid");
        errno = EINVAL;
        return -1;
    }
    int handshake[2];
    if (pipe(handshake) != 0) {
        set_error_errno(err, err_len, "pipe");
        return -1;
    }
    (void)fcntl(handshake[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(handshake[1], F_SETFD, FD_CLOEXEC);
    const pid_t child = fork();
    if (child < 0) {
        const int saved = errno;
        close(handshake[0]);
        close(handshake[1]);
        errno = saved;
        set_error_errno(err, err_len, "fork");
        return -1;
    }
    if (child == 0) {
        close(handshake[0]);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) _exit(127);
        if (getppid() != expected_parent) _exit(127);
        if (setsid() < 0) _exit(127);
        const char ok = 1;
        if (write(handshake[1], &ok, 1) != 1) _exit(127);
        close(handshake[1]);
        close_unneeded_fds();
        if (cwd != nullptr && cwd[0] != '\0' && chdir(cwd) != 0) _exit(127);
        char* const* env = envp != nullptr ? envp : kEmptyEnv;
        execve(argv[0], argv, env);
        _exit(127);
    }
    close(handshake[1]);
    char ok = 0;
    ssize_t n;
    do {
        n = read(handshake[0], &ok, 1);
    } while (n < 0 && errno == EINTR);
    close(handshake[0]);
    if (n != 1 || ok != 1) {
        int status = 0;
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
        }
        set_error(err, err_len, "pre-exec process group failed");
        errno = ECHILD;
        return -1;
    }
    *child_pid = child;
    return 0;
}

extern "C" int nativecode_spawn_watchdog(const char* cwd, char* const argv[],
                                         char* const envp[], pid_t* child_pid,
                                         char* err, size_t err_len) {
    if (argv == nullptr || argv[0] == nullptr || child_pid == nullptr) {
        set_error(err, err_len, "invalid watchdog spawn arguments");
        errno = EINVAL;
        return -1;
    }
    int handshake[2];
    if (pipe(handshake) != 0) {
        set_error_errno(err, err_len, "pipe");
        return -1;
    }
    (void)fcntl(handshake[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(handshake[1], F_SETFD, FD_CLOEXEC);
    const pid_t child = fork();
    if (child < 0) {
        const int saved = errno;
        close(handshake[0]);
        close(handshake[1]);
        errno = saved;
        set_error_errno(err, err_len, "fork");
        return -1;
    }
    if (child == 0) {
        close(handshake[0]);
        // No PDEATHSIG by design: see fast_pty_guard.cpp watchdog mode.
        if (setsid() < 0) _exit(127);
        const char ok = 1;
        if (write(handshake[1], &ok, 1) != 1) _exit(127);
        close(handshake[1]);
        close_unneeded_fds();
        if (cwd != nullptr && cwd[0] != '\0' && chdir(cwd) != 0) _exit(127);
        char* const* env = envp != nullptr ? envp : kEmptyEnv;
        execve(argv[0], argv, env);
        _exit(127);
    }
    close(handshake[1]);
    char ok = 0;
    ssize_t n;
    do {
        n = read(handshake[0], &ok, 1);
    } while (n < 0 && errno == EINTR);
    close(handshake[0]);
    if (n != 1 || ok != 1) {
        int status = 0;
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
        }
        set_error(err, err_len, "watchdog pre-exec failed");
        errno = ECHILD;
        return -1;
    }
    *child_pid = child;
    return 0;
}

extern "C" int nativecode_waitpid(pid_t pid, int* status, int options) {
    if (pid <= 1) return -EINVAL;
    int local = 0;
    const pid_t rc = waitpid(pid, status != nullptr ? status : &local, options);
    if (rc < 0) return -errno;
    return static_cast<int>(rc);
}
