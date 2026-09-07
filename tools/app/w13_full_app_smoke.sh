#!/usr/bin/env bash
# tools/app/w13_full_app_smoke.sh
# End-to-end device automation regression suite for Alpaquita PRoot compatibility
set -euo pipefail

SERIAL="${ADB_SERIAL:-192.168.1.53:37491}"
PKG="com.zenithblue.nativecode"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

    echo "Executing 7 AI CLI versions..."
    local ai_versions
    ai_versions="$(run_proot_guest flux '
        export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        for t in opencode codex qwen claude agy grok kiro-cli; do
            printf "%s: " "$t"
            $t --version 2>&1 || exit 1
        done
    ')"
    echo "$ai_versions" | sed 's/^/  /'
    echo "$ai_versions" | grep -q "opencode: 1\." || { echo "FAIL: opencode version missing/failed"; exit 1; }
    echo "$ai_versions" | grep -q "codex:" || { echo "FAIL: codex version missing/failed"; exit 1; }
    echo "$ai_versions" | grep -q "qwen:" || { echo "FAIL: qwen version missing/failed"; exit 1; }
    echo "$ai_versions" | grep -q "claude:" || { echo "FAIL: claude version missing/failed"; exit 1; }
    echo "$ai_versions" | grep -q "agy:" || { echo "FAIL: agy version missing/failed"; exit 1; }
    echo "$ai_versions" | grep -q "grok:" || { echo "FAIL: grok version missing/failed"; exit 1; }
    echo "$ai_versions" | grep -q "kiro-cli:" || { echo "FAIL: kiro-cli version missing/failed"; exit 1; }

    echo "Testing OpenCode terminal launcher (launch_tool.sh opencode -> opencode --auto)..."
    local opencode_cmdline
    opencode_cmdline="$(run_proot_guest flux '
        export PATH="$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin:/opt/nodejs/bin:/usr/local/bin:/usr/bin:/bin:/sbin"
        /tmp/launch_tool.sh opencode &
        _pid=$!
        sleep 0.8
        cat /proc/$_pid/cmdline 2>/dev/null | tr "\0" " " || true
        kill -9 $_pid 2>/dev/null || true
    ')"
    echo "OpenCode spawned cmdline: $opencode_cmdline"
    echo "$opencode_cmdline" | grep -q -- "opencode --auto" || { echo "FAIL: launch_tool.sh did not invoke opencode --auto"; exit 1; }

    echo "Checking dynamic link diagnostics for libICE and libXmuu..."
    local lib_diag
    lib_diag="$(run_proot_guest flux '
        /lib/ld-linux-aarch64.so.1 --list /usr/bin/xfce4-session | grep libICE || true
        /lib/ld-linux-aarch64.so.1 --list /usr/bin/xrdb | grep libXmuu || true
    ')"
    echo "$lib_diag" | sed 's/^/  /'
    echo "$lib_diag" | grep -q "libICE.so" || { echo "FAIL: libICE.so resolution failed"; exit 1; }
    echo "$lib_diag" | grep -q "libXmuu.so" || { echo "FAIL: libXmuu.so resolution failed"; exit 1; }
    if echo "$lib_diag" | grep -qi "not found\|permission denied"; then
        echo "FAIL: dynamic link error in libICE or libXmuu"
        exit 1
    fi

    echo "PASS: Guest Matrix"
}

mode_project_matrix() {
    echo "=== [3/6] PROJECT & WORKSPACE MATRIX ==="
    run_proot_guest flux 'rm -rf /home/flux/repos/w13-smoke-test "/home/flux/repos/W13 project (test)"'

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

mode_gui_matrix() {
    echo "=== [5/6] GUI FULL LIFECYCLE & IDEMPOTENCY MATRIX ==="
    local native_lib_dir
    native_lib_dir="$(get_native_lib_dir)"

    # Deploy latest start_gui.sh and stop_gui.sh to device
    adb_cmd push app/src/main/assets/scripts/start_gui.sh /data/local/tmp/start_gui.sh >/dev/null
    adb_cmd push app/src/main/assets/scripts/stop_gui.sh /data/local/tmp/stop_gui.sh >/dev/null
    adb_run_as "cp /data/local/tmp/start_gui.sh /data/data/$PKG/files/home/start_gui.sh && chmod 755 /data/data/$PKG/files/home/start_gui.sh"
    adb_run_as "cp /data/local/tmp/stop_gui.sh /data/data/$PKG/files/home/stop_gui.sh && chmod 755 /data/data/$PKG/files/home/stop_gui.sh"

    # Step 1: Execute STOP
    echo "[Step 1] Execute STOP..."
    adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/stop_gui.sh alpaquita" >/dev/null 2>&1 || true

    # Step 2: Verify teardown
    echo "[Step 2] Verify no residual X11/XFCE processes..."
    local residual
    residual="$(adb_cmd shell "/system/bin/toybox ps -A -o PID,NAME,ARGS | /system/bin/toybox grep -E 'termux-x11|xfce4-session|xfwm4|dbus-daemon' | /system/bin/toybox grep -v grep || true" | tr -d '\r')"
    if [ -n "$residual" ]; then
        echo "FAIL: residual processes after STOP:"
        echo "$residual"
        exit 1
    fi
    echo "Confirmed: clean state (0 residual processes)"

    # Step 3: Execute START through actual app host path
    echo "[Step 3] Execute START through app host path..."
    adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/start_gui.sh alpaquita > /data/data/$PKG/files/home/smoke_gui_start.log 2>&1 &"

    adb_cmd shell "/system/bin/toybox sleep 4"

    # Step 4: Assert log contains NO "Permission denied"
    echo "[Step 4] Assert host log contains NO 'Permission denied'..."
    local start_log
    start_log="$(adb_run_as "cat /data/data/$PKG/files/home/smoke_gui_start.log" | tr -d '\r')"
    echo "$start_log" | sed 's/^/  /'
    if echo "$start_log" | grep -qi "permission denied"; then
        echo "FAIL: Permission denied detected in GUI start log!"
        exit 1
    fi

    # Step 5: Assert X11 socket exists
    echo "[Step 5] Assert X11 socket exists..."
    local x_sock
    x_sock="$(adb_run_as "test -S /data/data/$PKG/files/usr/tmp/.X11-unix/X0 && echo exists || echo missing" | tr -d '\r')"
    [ "$x_sock" = "exists" ] || { echo "FAIL: X0 socket missing"; exit 1; }
    echo "Confirmed: X0 socket ready"

    # Step 6: Assert termux-x11 process alive
    echo "[Step 6] Assert termux-x11 process alive..."
    local x11_pid
    x11_pid="$(adb_cmd shell "/system/bin/toybox pidof termux-x11" | tr -d '\r' || true)"
    [ -n "$x11_pid" ] || { echo "FAIL: termux-x11 process not running"; exit 1; }
    echo "Confirmed: termux-x11 alive (PID=$x11_pid)"

    # Step 7: Assert xfce4-session alive in guest
    echo "[Step 7] Assert xfce4-session alive..."
    local xfce_pid
    xfce_pid="$(adb_cmd shell "/system/bin/toybox pidof xfce4-session" | tr -d '\r' || true)"
    [ -n "$xfce_pid" ] || { echo "FAIL: xfce4-session not running"; exit 1; }
    echo "Confirmed: xfce4-session alive (PID=$xfce_pid)"

    # Step 8: Execute START again
    echo "[Step 8] Execute START again (testing idempotency)..."
    local second_start
    second_start="$(adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/start_gui.sh alpaquita" | tr -d '\r')"
    echo "$second_start" | sed 's/^/  /'
    echo "$second_start" | grep -q "Reusing existing termux-x11 server" || { echo "FAIL: second start did not reuse termux-x11"; exit 1; }
    echo "$second_start" | grep -q "Active xfce4-session already running" || { echo "FAIL: second start did not recognize existing xfce session"; exit 1; }

    # Step 9: Prove no duplicate X server or session
    echo "[Step 9] Prove no duplicate X server/session..."
    local x11_count xfce_count
    x11_count="$(adb_cmd shell "/system/bin/toybox pidof termux-x11 | /system/bin/toybox wc -w" | tr -d '\r')"
    xfce_count="$(adb_cmd shell "/system/bin/toybox pidof xfce4-session | /system/bin/toybox wc -w" | tr -d '\r')"
    [ "$x11_count" -eq 1 ] || { echo "FAIL: duplicate termux-x11 processes ($x11_count)"; exit 1; }
    [ "$xfce_count" -eq 1 ] || { echo "FAIL: duplicate xfce4-session processes ($xfce_count)"; exit 1; }
    echo "Confirmed: exactly 1 termux-x11 and 1 xfce4-session"

    # Step 10: Execute STOP
    echo "[Step 10] Execute STOP..."
    adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/stop_gui.sh alpaquita" >/dev/null

    # Step 11: Prove teardown
    echo "[Step 11] Prove teardown..."
    local teardown_check
    teardown_check="$(adb_cmd shell "/system/bin/toybox ps -A -o PID,NAME,ARGS | /system/bin/toybox grep -E 'termux-x11|xfce4-session|xfwm4|dbus-daemon' | /system/bin/toybox grep -v grep || true" | tr -d '\r')"
    [ -z "$teardown_check" ] || { echo "FAIL: processes still alive after stop: $teardown_check"; exit 1; }
    echo "Confirmed: complete teardown verified"

    # Step 12: START again and prove restart
    echo "[Step 12] START again and prove restart..."
    adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/start_gui.sh alpaquita > /data/data/$PKG/files/home/smoke_gui_restart.log 2>&1 &"

    adb_cmd shell "/system/bin/toybox sleep 4"
    local restart_x11 restart_xfce
    restart_x11="$(adb_cmd shell "/system/bin/toybox pidof termux-x11" | tr -d '\r' || true)"
    restart_xfce="$(adb_cmd shell "/system/bin/toybox pidof xfce4-session" | tr -d '\r' || true)"
    [ -n "$restart_x11" ] || { echo "FAIL: restart termux-x11 not alive"; exit 1; }
    [ -n "$restart_xfce" ] || { echo "FAIL: restart xfce4-session not alive"; exit 1; }
    echo "Confirmed: restart successful (termux-x11 PID=$restart_x11, xfce4-session PID=$restart_xfce)"

    # Clean up final state
    adb_cmd shell "run-as $PKG env \
        TERMUX_APP__PACKAGE_NAME=$PKG \
        TERMUX__PREFIX=/data/data/$PKG/files/usr \
        TERMUX__HOME=/data/data/$PKG/files/home \
        PREFIX=/data/data/$PKG/files/usr \
        TMPDIR=/data/data/$PKG/files/usr/tmp \
        PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
        LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
        PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
        /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/stop_gui.sh alpaquita" >/dev/null
    echo "PASS: GUI Full Lifecycle & Idempotency"
}

mode_logs() {
    echo "=== [6/6] LOGCAT RUNTIME DUMP ==="
    adb_cmd logcat -d | grep -i "NativeCode\|TerminalSession\|proot-distro" | tail -n 25 | sed 's/^/  /' || true
    echo "PASS: Logs"
}

mode_xfce_config() {
    echo "=== FOCUSED MODE: XFCE CONFIGURATION ==="
    local guest_out
    guest_out="$(run_proot_guest flux '
        echo "=== HELPERS ==="
        cat ~/.config/xfce4/helpers.rc 2>/dev/null || true
        echo "=== THEME ==="
        grep -o "ThemeName.*" ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml 2>/dev/null || true
        echo "=== ICONS ==="
        grep -o "IconThemeName.*" ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml 2>/dev/null || true
        echo "=== CURSOR ==="
        grep -o "CursorThemeName.*" ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml 2>/dev/null || true
        echo "=== XFWM ==="
        grep -o "theme.*" ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml 2>/dev/null || true
        echo "=== WALLPAPER ==="
        grep -o "/home/flux/Pictures/Wallpapers/.*" ~/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml 2>/dev/null || true
        echo "=== PERMS ==="
        stat -c "%u:%g %a %n" ~/.config/xfce4/helpers.rc ~/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml 2>/dev/null || true
    ')"
    echo "$guest_out" | sed 's/^/  /'

    echo "$guest_out" | grep -q "Space-transparency" || { echo "FAIL: Space-transparency theme missing"; exit 1; }
    echo "$guest_out" | grep -q "Papirus-Dark" || { echo "FAIL: Papirus-Dark icons missing"; exit 1; }
    echo "$guest_out" | grep -q "Vimix-white-cursors" || { echo "FAIL: Vimix cursor missing"; exit 1; }
    echo "$guest_out" | grep -q "fluxlinux-dark.png" || { echo "FAIL: fluxlinux-dark.png wallpaper missing"; exit 1; }
    echo "$guest_out" | grep -q "TerminalEmulator=xfce4-terminal" || { echo "FAIL: helpers.rc TerminalEmulator missing"; exit 1; }

    echo "PASS: XFCE Configuration"
}

mode_xfce_terminal() {
    echo "=== FOCUSED MODE: XFCE TERMINAL ==="
    local term_ver
    term_ver="$(run_proot_guest flux 'xfce4-terminal --version 2>&1 || true')"
    echo "Terminal version output: $term_ver"
    echo "$term_ver" | grep -qi "xfce4-terminal\|cannot open display" || { echo "FAIL: xfce4-terminal executable error"; exit 1; }

    local native_lib_dir
    native_lib_dir="$(get_native_lib_dir)"
    local x11_alive
    x11_alive="$(adb_cmd shell "/system/bin/toybox pidof termux-x11" | tr -d '\r' || true)"
    local stop_after=0
    if [ -z "$x11_alive" ]; then
        stop_after=1
        adb_cmd shell "run-as $PKG env \
            TERMUX_APP__PACKAGE_NAME=$PKG \
            TERMUX__PREFIX=/data/data/$PKG/files/usr \
            TERMUX__HOME=/data/data/$PKG/files/home \
            PREFIX=/data/data/$PKG/files/usr \
            TMPDIR=/data/data/$PKG/files/usr/tmp \
            PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
            LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
            PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
            /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/start_gui.sh alpaquita >/dev/null 2>&1 &"
        adb_cmd shell "/system/bin/toybox sleep 4"
    fi

    local exo_test
    exo_test="$(run_proot_guest flux '
        mkdir -p /tmp/runtime-flux
        chmod 700 /tmp/runtime-flux
        export XDG_RUNTIME_DIR=/tmp/runtime-flux
        export DISPLAY=:0
        eval $(dbus-launch --sh-syntax 2>/dev/null || true)
        exo-open --launch TerminalEmulator &
        sleep 2
        ps -A -o pid,comm,args | grep -E "xfce4-terminal|xfce4-mime-helper" | grep -v grep || true
    ')"
    echo "exo-open test process output:"
    echo "$exo_test" | sed 's/^/  /'
    echo "$exo_test" | grep -q "xfce4-terminal" || { echo "FAIL: exo-open TerminalEmulator did not launch xfce4-terminal"; exit 1; }

    if [ "$stop_after" = "1" ]; then
        adb_cmd shell "run-as $PKG env \
            TERMUX_APP__PACKAGE_NAME=$PKG \
            TERMUX__PREFIX=/data/data/$PKG/files/usr \
            TERMUX__HOME=/data/data/$PKG/files/home \
            PREFIX=/data/data/$PKG/files/usr \
            TMPDIR=/data/data/$PKG/files/usr/tmp \
            PROOT_TMP_DIR=/data/data/$PKG/files/usr/tmp \
            LD_LIBRARY_PATH=/data/data/$PKG/files/usr/lib \
            PATH=$native_lib_dir:/data/data/$PKG/files/usr/bin:/system/bin \
            /system/bin/linker64 $native_lib_dir/libbash.so /data/data/$PKG/files/home/stop_gui.sh alpaquita" >/dev/null 2>&1 || true
    fi

    echo "PASS: XFCE Terminal"
}

mode_immutable_runtime() {
    echo "=== FOCUSED MODE: IMMUTABLE RUNTIME ==="
    bash "$DIR/test_prebuilt_runtime_immutable_contract.sh"
    echo "PASS: Immutable Runtime"
}

mode="${1:---all}"

case "$mode" in
    --preflight) mode_preflight ;;
    --guest-matrix) mode_guest_matrix ;;
    --project-matrix) mode_project_matrix ;;
    --git-matrix) mode_git_matrix ;;
    --gui-matrix) mode_gui_matrix ;;
    --logs) mode_logs ;;
    --xfce-config) mode_xfce_config ;;
    --xfce-terminal) mode_xfce_terminal ;;
    --immutable-runtime) mode_immutable_runtime ;;
    --all)
        mode_preflight
        mode_guest_matrix
        mode_project_matrix
        mode_git_matrix
        mode_gui_matrix
        mode_xfce_config
        mode_xfce_terminal
        mode_immutable_runtime
        mode_logs
        echo "========================================"
        echo "ALL W13 APP SMOKE TESTS COMPLETED: PASS"
        echo "========================================"
        ;;
    *)
        echo "Usage: $0 [--preflight|--guest-matrix|--project-matrix|--git-matrix|--gui-matrix|--logs|--xfce-config|--xfce-terminal|--immutable-runtime|--all]"
        exit 1
        ;;
esac
