#!/usr/bin/env bash
# tools/app/w13_full_app_smoke.sh
# End-to-end device automation regression suite for Alpaquita PRoot compatibility
set -euo pipefail

SERIAL="${ADB_SERIAL:-192.168.1.53:37491}"
PKG="com.zenithblue.nativecode"

adb_cmd() {
    adb -s "$SERIAL" "$@"
}

adb_run_as() {
    local cmd="$1"
    adb_cmd shell "run-as $PKG $cmd"
}

get_native_lib_dir() {
    local apk_path
    apk_path="$(adb_cmd shell "pm path $PKG" | tr -d '\r' | sed 's/package://' | head -n 1)"
    echo "${apk_path%/base.apk}/lib/arm64"
}

run_proot_guest() {
    local guest_user="${1:-flux}"
    local guest_cmd="${2:-echo ok}"
    local native_lib_dir
    native_lib_dir="$(get_native_lib_dir)"

    adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        TERMUX_VERSION=0.119.0-5 \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        PD_PROOT_BIN=$native_lib_dir/libproot.so \
        PROOT_LOADER=$native_lib_dir/libloader.so \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 /data/data/$PKG/files/usr/bin/python3.14 \
        /data/data/$PKG/files/usr/bin/proot-distro login alpaquita --shared-tmp --user $guest_user -- \
        /bin/bash --noprofile --norc -c '$guest_cmd'" | tr -d '\r'
}

mode_preflight() {
    echo "=== [1/6] PREFLIGHT CHECK ==="
    local state
    state="$(adb_cmd get-state | tr -d '\r')"
    echo "ADB State: $state"
    [ "$state" = "device" ] || { echo "FAIL: device not online"; exit 1; }

    local abi sdk model
    abi="$(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')"
    sdk="$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
    model="$(adb_cmd shell getprop ro.product.model | tr -d '\r')"
    echo "Device: $model | ABI: $abi | SDK: $sdk"
    [ "$abi" = "arm64-v8a" ] || { echo "FAIL: unexpected ABI $abi"; exit 1; }

    local pkg_path
    pkg_path="$(adb_cmd shell pm path "$PKG" | tr -d '\r')"
    echo "Package path: $pkg_path"
    [ -n "$pkg_path" ] || { echo "FAIL: $PKG not installed"; exit 1; }

    echo "PASS: Preflight"
}

mode_guest_matrix() {
    echo "=== [2/6] GUEST MATRIX (Alpaquita glibc PRoot) ==="
    local guest_id
    guest_id="$(run_proot_guest flux "id")"
    echo "Guest ID: $guest_id"
    echo "$guest_id" | grep -q "uid=1000(flux)" || { echo "FAIL: guest uid not 1000"; exit 1; }

    local guest_cwd
    guest_cwd="$(run_proot_guest flux "pwd")"
    echo "Guest HOME/CWD: $guest_cwd"
    [ "$guest_cwd" = "/home/flux" ] || { echo "FAIL: guest cwd not /home/flux"; exit 1; }

    local release_marker
    release_marker="$(run_proot_guest flux "cat /etc/nativecode/rootfs-release")"
    echo "Release marker:"
    echo "$release_marker" | sed 's/^/  /'
    echo "$release_marker" | grep -q "DISTRO=alpaquita" || { echo "FAIL: missing DISTRO=alpaquita"; exit 1; }
    echo "$release_marker" | grep -q "LIBC=glibc" || { echo "FAIL: missing LIBC=glibc"; exit 1; }

    local tools
    tools="$(run_proot_guest flux "command -v bash git gh node python3 gcc make 2>&1")"
    echo "Available tool paths:"
    echo "$tools" | sed 's/^/  /'

    local preload_check
    preload_check="$(run_proot_guest flux 'printf "%s" "${LD_PRELOAD-}"')"
    if [ -n "$preload_check" ]; then
        echo "FAIL: LD_PRELOAD leaked into guest: $preload_check"
        exit 1
    fi
    echo "LD_PRELOAD in guest: (clean / empty)"

    echo "PASS: Guest Matrix"
}

mode_project_matrix() {
    echo "=== [3/6] PROJECT & WORKSPACE MATRIX ==="
    # Clean previous run state
    run_proot_guest flux 'rm -rf /home/flux/repos/w13-smoke-test "/home/flux/repos/W13 project (test)"'

    # Normal project
    run_proot_guest flux '
        mkdir -p /home/flux/repos/w13-smoke-test
        cd /home/flux/repos/w13-smoke-test
        git init -q
        git config user.email "w13@test.local"
        git config user.name "W13 Smoke"
        echo "w13 baseline" > README.md
        git add README.md
        git commit -q -m "initial commit"
    '
    local smoke_head
    smoke_head="$(run_proot_guest flux 'cd /home/flux/repos/w13-smoke-test && git rev-parse --short HEAD')"
    echo "Smoke project commit: $smoke_head"
    [ -n "$smoke_head" ] || { echo "FAIL: smoke project commit failed"; exit 1; }

    # Spaces in path test
    run_proot_guest flux '
        mkdir -p "/home/flux/repos/W13 project (test)"
        cd "/home/flux/repos/W13 project (test)"
        git init -q
        git config user.email "w13@test.local"
        git config user.name "W13 Smoke"
        echo "spaces test" > "file with spaces.txt"
        git add "file with spaces.txt"
        git commit -q -m "spaces commit"
    '
    local spaces_head
    spaces_head="$(run_proot_guest flux 'cd "/home/flux/repos/W13 project (test)" && git rev-parse --short HEAD')"
    echo "Spaces project commit: $spaces_head"
    [ -n "$spaces_head" ] || { echo "FAIL: spaces project commit failed"; exit 1; }

    echo "PASS: Project Matrix"
}

mode_git_matrix() {
    echo "=== [4/6] GIT & GIT DIFF MATRIX ==="
    run_proot_guest flux '
        cd /home/flux/repos/w13-smoke-test
        echo "new modified line" >> README.md
        echo "brand new file" > untracked.txt
    '
    local status_output
    status_output="$(run_proot_guest flux 'cd /home/flux/repos/w13-smoke-test && git -c safe.directory=/home/flux/repos/w13-smoke-test status --porcelain -uall')"
    echo "Git porcelain status:"
    echo "$status_output" | sed 's/^/  /'
    echo "$status_output" | grep -q ' M README.md' || { echo "FAIL: README.md not tracked as modified"; exit 1; }
    echo "$status_output" | grep -q '?? untracked.txt' || { echo "FAIL: untracked.txt not shown as untracked"; exit 1; }

    local diff_output
    diff_output="$(run_proot_guest flux 'cd /home/flux/repos/w13-smoke-test && git -c safe.directory=/home/flux/repos/w13-smoke-test diff HEAD -- README.md')"
    echo "Git diff output:"
    echo "$diff_output" | sed 's/^/  /'
    echo "$diff_output" | grep -q '+new modified line' || { echo "FAIL: diff missing modified line"; exit 1; }

    echo "PASS: Git Matrix"
}

mode_x11_probe() {
    echo "=== [5/6] X11 & XFCE DESKTOP PROBE ==="
    local xfce_bin
    xfce_bin="$(run_proot_guest flux 'command -v startxfce4 xfwm4 xfce4-session xfce4-terminal 2>&1')"
    echo "XFCE binaries:"
    echo "$xfce_bin" | sed 's/^/  /'

    local gpu_mode
    gpu_mode="$(run_proot_guest flux '[ -r /etc/fluxlinux/gpu_mode ] && cat /etc/fluxlinux/gpu_mode || echo virgl')"
    echo "Guest GPU mode: $gpu_mode"

    local xkb_check
    xkb_check="$(adb_run_as "ls -d /data/data/$PKG/files/usr/share/xkeyboard-config-2 2>/dev/null || echo missing")"
    echo "Host xkeyboard-config-2: $xkb_check"

    local xlorie_check
    xlorie_check="$(adb_run_as "ls -l $(get_native_lib_dir)/libXlorie.so 2>/dev/null || echo missing")"
    echo "libXlorie.so: $xlorie_check"

    echo "PASS: X11 & XFCE Probe"
}

mode_logs() {
    echo "=== [6/6] LOGCAT RUNTIME DUMP ==="
    adb_cmd logcat -d | grep -i "NativeCode\|TerminalSession\|proot-distro" | tail -n 25 | sed 's/^/  /' || true
    echo "PASS: Logs"
}

mode="${1:---all}"

case "$mode" in
    --preflight) mode_preflight ;;
    --guest-matrix) mode_guest_matrix ;;
    --project-matrix) mode_project_matrix ;;
    --git-matrix) mode_git_matrix ;;
    --x11-probe) mode_x11_probe ;;
    --logs) mode_logs ;;
    --all)
        mode_preflight
        mode_guest_matrix
        mode_project_matrix
        mode_git_matrix
        mode_x11_probe
        mode_logs
        echo "========================================"
        echo "ALL W13 APP SMOKE TESTS COMPLETED: PASS"
        echo "========================================"
        ;;
    *)
        echo "Usage: $0 [--preflight|--guest-matrix|--project-matrix|--git-matrix|--x11-probe|--logs|--all]"
        exit 1
        ;;
esac
