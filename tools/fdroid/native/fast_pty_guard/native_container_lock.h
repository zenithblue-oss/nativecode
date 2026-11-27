#ifndef NATIVECODE_NATIVE_CONTAINER_LOCK_H
#define NATIVECODE_NATIVE_CONTAINER_LOCK_H

#include <cstddef>
#include <sys/types.h>
#include <unistd.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Open $prefix/var/lib/proot-distro/locks/<name>.lock with dirfd + O_NOFOLLOW.
 * Returns fd >= 0, or -1 and writes a short error into err. */
int nativecode_open_container_lock(const char* prefix, const char* name,
                                   char* err, size_t err_len);

/* flock(LOCK_SH|LOCK_NB). Returns 0, -EAGAIN/-EWOULDBLOCK when busy, or -errno. */
int nativecode_flock_shared_nb(int fd);

/* Alias of nativecode_flock_shared_nb for existing callers. */
int nativecode_flock_shared(int fd);

/* flock(LOCK_EX|LOCK_NB). Returns 0, -EAGAIN/-EWOULDBLOCK when busy, or -errno. */
int nativecode_flock_exclusive_nb(int fd);

/* killpg(pid, sig) with pid>1. Returns 0 or -errno. Never kill(-1). */
int nativecode_killpg(pid_t pid, int sig);

/* setpgid with pid>1. Returns 0 or -errno. */
int nativecode_setpgid(pid_t pid, pid_t pgid);

/* Component-wise O_NOFOLLOW directory open of an absolute path. */
int nativecode_open_dir_nofollow(const char* path, char* err, size_t err_len);

/* Open one NOFOLLOW component under dirfd. directory!=0 => O_DIRECTORY, else regular file. */
int nativecode_openat_nofollow(int dirfd, const char* name, int directory,
                               char* err, size_t err_len);

/* Walk a relative path of NOFOLLOW components under dirfd. */
int nativecode_openat_path(int dirfd, const char* rel, int directory,
                           char* err, size_t err_len);

/* Validate fd is a regular file (or directory if expect_dir) owned by us.
 * require_nlink_one applies only to regular files. Returns 0 or -errno. */
int nativecode_fstat_check(int fd, int expect_dir, int require_nlink_one,
                           char* err, size_t err_len);

/* Read the whole regular file from fd. Returns byte count, or -1 on error. */
ssize_t nativecode_read_fd(int fd, char* buf, size_t cap, char* err, size_t err_len);

/* NUL-separated directory names into out. Returns 0 or -1. */
int nativecode_listdir_at(int dirfd, char* out, size_t cap, char* err, size_t err_len);

/* Nonzero if fd is a regular file with any execute bit. */
int nativecode_fd_executable(int fd);

/* fork + PDEATHSIG + setsid + close fds>2 before exec.
 * expected_parent must be >1 (caller's pid). Returns 0 and sets child_pid, or -1. */
int nativecode_spawn_setsid(const char* cwd, char* const argv[], char* const envp[],
                            pid_t expected_parent, pid_t* child_pid, char* err, size_t err_len);

/* fork + setsid + close fds>2 before exec, WITHOUT PDEATHSIG.
 * Used for the fast-PTY app-death watchdog, which must outlive the killed
 * app process briefly to kill the escaped outer-PRoot process group.
 * Returns 0 and sets child_pid, or -1. */
int nativecode_spawn_watchdog(const char* cwd, char* const argv[], char* const envp[],
                              pid_t* child_pid, char* err, size_t err_len);

/* waitpid. options is passed through. Returns waitpid result or -errno. */
int nativecode_waitpid(pid_t pid, int* status, int options);

#ifdef __cplusplus
}
#endif

#endif
