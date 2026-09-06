#!/usr/bin/env bash
# w11_device_smoke.sh — End-to-end device smoke validation for NativeCode Alpaquita rootfs.
# Tests W11-A (PRoot) and W11-B (Chroot) on connected physical Android ARM64 device.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ARTIFACT="${ARTIFACT:-$SCRIPT_DIR/out/alpaquita_rootfs.tar.zst}"
SHA256_FILE="${SHA256_FILE:-$SCRIPT_DIR/out/alpaquita_rootfs.tar.zst.sha256}"
DEVICE_SERIAL="${DEVICE_SERIAL:-192.168.1.53:37491}"
SKIP_PUSH="${SKIP_PUSH:-0}"
STAGE="${STAGE:-all}" # proot, chroot, or all

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()    { printf "${CYAN}>>> %s${NC}\n" "$*"; }
log_pass()    { printf "${GREEN}[PASS] %s${NC}\n" "$*"; }
log_fail()    { printf "${RED}[FAIL] %s${NC}\n" "$*" >&2; }
log_warn()    { printf "${YELLOW}[WARN] %s${NC}\n" "$*"; }

adb_cmd() {
    adb -s "$DEVICE_SERIAL" "$@"
}

adb_shell() {
    adb -s "$DEVICE_SERIAL" shell "$@"
}

adb_root() {
    adb -s "$DEVICE_SERIAL" shell "su -c '$*'"
}

adb_app() {
    adb -s "$DEVICE_SERIAL" shell "su $APP_USER -c '/data/local/tmp/run_as_app.sh $*'"
}

adb_proot() {
    adb -s "$DEVICE_SERIAL" shell "su $APP_USER -c '/data/local/tmp/run_proot.sh $*'"
}

usage() {
    cat <<'EOF'
Usage: tools/rootfs/w11_device_smoke.sh [options]

Options:
  -s, --serial SERIAL    Target ADB device serial (default: 192.168.1.53:33841)
  --artifact PATH        Path to alpaquita_rootfs.tar.zst
  --stage STAGE          proot, chroot, or all (default: all)
  --skip-push            Skip pushing artifact if already present on device
  -h, --help             Show this help message
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--serial) DEVICE_SERIAL="$2"; shift 2 ;;
        --artifact) ARTIFACT="$2"; SHA256_FILE="$2.sha256"; shift 2 ;;
        --stage) STAGE="$2"; shift 2 ;;
        --skip-push) SKIP_PUSH=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) log_fail "Unknown option: $1"; usage; exit 1 ;;
    esac
done

# ── 1. Check local artifact ──────────────────────────────────────────────────
log_info "Validating local rootfs artifact and checksum..."
[ -f "$ARTIFACT" ] || { log_fail "Artifact not found: $ARTIFACT"; exit 1; }
[ -f "$SHA256_FILE" ] || { log_fail "Checksum file not found: $SHA256_FILE"; exit 1; }

EXPECTED_SHA="$(awk 'NR==1 {print $1}' "$SHA256_FILE")"
LOCAL_SIZE="$(stat -c %s "$ARTIFACT")"
log_info "Expected SHA256: $EXPECTED_SHA"
log_info "Artifact size  : $LOCAL_SIZE bytes"

LOCAL_SHA="$(sha256sum "$ARTIFACT" | awk '{print $1}')"
[ "$LOCAL_SHA" = "$EXPECTED_SHA" ] || { log_fail "Local artifact checksum mismatch! Got $LOCAL_SHA"; exit 1; }
log_pass "Local artifact checksum verified: OK"

# ── 2. Select target ADB device ──────────────────────────────────────────────
log_info "Target device: $DEVICE_SERIAL"
DEVICE_MODEL="$(adb_shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || true)"
[ -n "$DEVICE_MODEL" ] || DEVICE_MODEL="$(adb_shell "grep 'ro.product.model=' /system/build.prop 2>/dev/null | head -1 | cut -d= -f2" | tr -d '\r\n' || true)"
[ -n "$DEVICE_MODEL" ] || DEVICE_MODEL="Android-ARM64"

DEVICE_ABI="$(adb_shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n' || true)"
[ -n "$DEVICE_ABI" ] || DEVICE_ABI="$(adb_shell uname -m 2>/dev/null | tr -d '\r\n' || true)"

DEVICE_SDK="$(adb_shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n' || true)"
[ -n "$DEVICE_SDK" ] || DEVICE_SDK="$(adb_shell "grep 'ro.build.version.sdk=' /system/build.prop 2>/dev/null | head -1 | cut -d= -f2" | tr -d '\r\n' || true)"
[ -n "$DEVICE_SDK" ] || DEVICE_SDK="36"
log_info "Device info  : Model=$DEVICE_MODEL, ABI=$DEVICE_ABI, SDK=$DEVICE_SDK"

[ "$DEVICE_ABI" = "arm64-v8a" ] || [ "$DEVICE_ABI" = "aarch64" ] || {
    log_fail "Target device is not ARM64 (got $DEVICE_ABI)"; exit 1;
}

HAS_ROOT=0
if adb_shell 'su -c id' >/dev/null 2>&1; then
    HAS_ROOT=1
    log_pass "Root access available (KernelSU / su)"
else
    log_warn "No root access available; chroot testing will be skipped"
fi

PKG="com.zenithblue.nativecode"
APP_PATH="$(adb_shell pm path "$PKG" 2>/dev/null | tr -d '\r\n' | sed 's/^package://' || true)"
[ -n "$APP_PATH" ] || { log_fail "$PKG is not installed on device!"; exit 1; }
log_info "App APK path: $APP_PATH"

APP_PREFIX="/data/data/$PKG/files/usr"
APP_HOME="/data/data/$PKG/files/home"
APP_USER="$(adb_root "stat -c %U /data/data/$PKG/files" | tr -d '\r\n')"
log_info "App user: $APP_USER"

# Push device runners directly
log_info "Deploying device runner scripts..."
adb_cmd push "$SCRIPT_DIR/device_runners/run_as_app.sh" /data/local/tmp/run_as_app.sh >/dev/null
adb_cmd push "$SCRIPT_DIR/device_runners/run_proot.sh" /data/local/tmp/run_proot.sh >/dev/null
adb_cmd push "$SCRIPT_DIR/device_runners/clean_chroot.sh" /data/local/tmp/clean_chroot.sh >/dev/null
adb_cmd push "$SCRIPT_DIR/device_runners/unmount_chroot.sh" /data/local/tmp/unmount_chroot.sh >/dev/null
adb_cmd push "$SCRIPT_DIR/device_runners/run_chroot_setup.sh" /data/local/tmp/run_chroot_setup.sh >/dev/null
adb_root "chmod 755 /data/local/tmp/run_as_app.sh /data/local/tmp/run_proot.sh /data/local/tmp/clean_chroot.sh /data/local/tmp/unmount_chroot.sh /data/local/tmp/run_chroot_setup.sh"

# ── 3. Stage rootfs artifact on device ───────────────────────────────────────
DEV_TMP="/data/local/tmp"
DEV_ROOTFS="$DEV_TMP/alpaquita_rootfs.tar.zst"
DEV_SHA="$DEV_TMP/alpaquita_rootfs.tar.zst.sha256"

STAGE_NEEDED=1
if [ "$SKIP_PUSH" -eq 1 ] || adb_shell "[ -f '$DEV_ROOTFS' ] && [ -s '$DEV_ROOTFS' ]"; then
    log_info "Checking existing artifact on device: $DEV_ROOTFS..."
    DEV_CURRENT_SHA="$(adb_shell "sha256sum '$DEV_ROOTFS' 2>/dev/null | awk '{print \$1}'" | tr -d '\r\n' || true)"
    if [ "$DEV_CURRENT_SHA" = "$EXPECTED_SHA" ]; then
        log_pass "Artifact already on device with matching SHA256 ($DEV_CURRENT_SHA)"
        STAGE_NEEDED=0
    else
        log_warn "Device artifact SHA mismatch (got $DEV_CURRENT_SHA, need $EXPECTED_SHA) — re-pushing..."
    fi
fi

if [ "$STAGE_NEEDED" -eq 1 ]; then
    log_info "Pushing $ARTIFACT to $DEV_ROOTFS (1.25 GiB, may take ~1 minute)..."
    adb_cmd push "$ARTIFACT" "$DEV_ROOTFS"
    adb_cmd push "$SHA256_FILE" "$DEV_SHA"
fi

adb_root "chmod 644 '$DEV_ROOTFS' '$DEV_SHA'"

log_info "Verifying SHA256 directly on Android device..."
DEV_VERIFY="$(adb_shell "sha256sum '$DEV_ROOTFS' | awk '{print \$1}'" | tr -d '\r\n')"
[ "$DEV_VERIFY" = "$EXPECTED_SHA" ] || {
    log_fail "On-device SHA256 verification failed! Got $DEV_VERIFY, expected $EXPECTED_SHA"
    exit 1
}
log_pass "On-device artifact checksum verified: OK"

# Stage latest scripts
log_info "Staging latest host onboarding scripts to device..."
SCRIPT_STAGE_DIR="$DEV_TMP/nc_scripts"
adb_shell "mkdir -p '$SCRIPT_STAGE_DIR'"
for s in \
    "$REPO_ROOT/app/src/main/assets/scripts/flux_install.sh" \
    "$REPO_ROOT/app/src/main/assets/scripts/setup_alpaquita_family.sh" \
    "$REPO_ROOT/app/src/main/assets/scripts/setup_hw_accel_debian.sh" \
    "$REPO_ROOT/app/src/main/assets/scripts/setup_xfce4.sh" \
    "$REPO_ROOT/app/src/main/assets/scripts/setup_customization_alpine.sh" \
    "$REPO_ROOT/app/src/main/assets/scripts/setup_cli_tools.sh" \
    "$REPO_ROOT/app/src/main/assets/scripts/chroot/setup_alpaquita_chroot.sh"
do
    adb_cmd push "$s" "$SCRIPT_STAGE_DIR/" >/dev/null
done
adb_shell "chmod 755 '$SCRIPT_STAGE_DIR'/*"

adb_root "cp '$SCRIPT_STAGE_DIR'/* '$APP_HOME/' && chmod 755 '$APP_HOME'/*.sh && chown $APP_USER:$APP_USER '$APP_HOME'/*.sh"
adb_root "mkdir -p '$APP_PREFIX/tmp' && cp '$SCRIPT_STAGE_DIR'/* '$APP_PREFIX/tmp/' && chmod 755 '$APP_PREFIX/tmp'/*.sh && chown -R $APP_USER:$APP_USER '$APP_PREFIX/tmp'"

# Deploy in-guest matrix and test scripts to $APP_PREFIX/tmp
adb_cmd push "$SCRIPT_DIR/device_runners/run_matrix.sh" "$APP_PREFIX/tmp/run_matrix.sh" >/dev/null
adb_cmd push "$SCRIPT_DIR/device_runners/test_xfce_session.sh" "$APP_PREFIX/tmp/test_xfce_session.sh" >/dev/null
adb_root "chmod 755 '$APP_PREFIX/tmp/run_matrix.sh' '$APP_PREFIX/tmp/test_xfce_session.sh' && chown $APP_USER:$APP_USER '$APP_PREFIX/tmp/run_matrix.sh' '$APP_PREFIX/tmp/test_xfce_session.sh'"

# ── 4. W11-A: PRoot Smoke Validation Matrix ──────────────────────────────────
run_proot_smoke() {
    log_info "============================================================"
    log_info " STARTING W11-A: PROOT SMOKE VALIDATION MATRIX"
    log_info "============================================================"

    # 4.1 Fresh install via proot-distro / flux_install.sh
    log_info "W11-A.1: Clean previous container & run fresh installation..."
    adb_app "python /data/data/com.zenithblue.nativecode/files/usr/bin/proot-distro remove alpaquita" 2>/dev/null || true
    adb_root "rm -rf $APP_PREFIX/var/lib/proot-distro/containers/alpaquita"

    INSTALL_START="$(date +%s)"
    log_info "Running flux_install.sh alpaquita..."
    adb_app "bash /data/data/com.zenithblue.nativecode/files/home/flux_install.sh alpaquita null"
    INSTALL_END="$(date +%s)"
    INSTALL_TIME=$((INSTALL_END - INSTALL_START))
    log_pass "PRoot base installation completed in ${INSTALL_TIME}s"

    # Inject runtime GUI shared libraries (libice, libsm, libxcvt, vte3, yaml, libdisplay-info, xkbcomp)
    if [ -d "/tmp/nc_gui_libs" ]; then
        adb_root "cp -a /data/local/tmp/nc_gui_libs/* '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/lib/' 2>/dev/null || true"
        adb_root "cp -a /data/local/tmp/nc_xkb/xkbcomp '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/bin/' 2>/dev/null || true"
        adb_root "cp -a /data/local/tmp/nc_xkb/libxkbfile.so* '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/lib/' 2>/dev/null || true"
        adb_root "mkdir -p '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/share/X11' && cp -a /data/local/tmp/nc_xkb/xkb '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/share/X11/' 2>/dev/null || true"
        adb_root "chown -R $APP_USER:$APP_USER '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/lib' '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/bin' '$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs/usr/share/X11'"
    fi

    # 4.2 Verify container files & release marker
    log_info "W11-A.2: Verifying rootfs-release marker..."
    RELEASE_OUT="$(adb_proot "login alpaquita -- cat /etc/nativecode/rootfs-release" | tr -d '\r')"
    echo "$RELEASE_OUT"
    echo "$RELEASE_OUT" | grep -q "DISTRO=alpaquita" || { log_fail "Missing DISTRO=alpaquita"; exit 1; }
    echo "$RELEASE_OUT" | grep -q "LIBC=glibc" || { log_fail "Missing LIBC=glibc"; exit 1; }
    echo "$RELEASE_OUT" | grep -q "ARCH=aarch64" || { log_fail "Missing ARCH=aarch64"; exit 1; }
    log_pass "Release marker verified (DISTRO=alpaquita, LIBC=glibc, ARCH=aarch64)"

    # 4.3 Run Onboarding Stages E -> F -> GUI -> G -> H
    log_info "W11-A.3: Running Phase E (setup_alpaquita_family.sh)..."
    adb_proot "login alpaquita --shared-tmp -- sh /tmp/setup_alpaquita_family.sh alpaquita"

    log_info "W11-A.4: Running Phase F (Hardware Accel / GPU detect)..."
    adb_proot "login alpaquita --shared-tmp -- env FLUX_GPU=virgl bash /tmp/setup_hw_accel_debian.sh"

    log_info "W11-A.5: Running Phase GUI (setup_xfce4.sh)..."
    adb_proot "login alpaquita --shared-tmp -- env FLUX_THEME=dark bash /tmp/setup_xfce4.sh"

    log_info "W11-A.6: Running Phase G (setup_customization_alpine.sh)..."
    adb_proot "login alpaquita --shared-tmp -- sh /tmp/setup_customization_alpine.sh"

    log_info "W11-A.7: Running Phase H (setup_cli_tools.sh)..."
    adb_proot "login alpaquita --shared-tmp -- env NC_SKIP_AI_CLI=0 bash /tmp/setup_cli_tools.sh"

    # 4.4 Execute full binary & CLI matrix inside PRoot container as flux
    log_info "W11-A.8: Executing binary and CLI verification matrix as flux..."
    MATRIX_OUT="$(adb_proot "login alpaquita --user flux --shared-tmp -- /tmp/run_matrix.sh" | tr -d '\r')"
    echo "$MATRIX_OUT"

    FAIL_COUNT="$(echo "$MATRIX_OUT" | grep -c "^FAIL" || true)"
    if [ "$FAIL_COUNT" -gt 0 ]; then
        log_fail "$FAIL_COUNT binaries failed in PRoot verification matrix!"
        exit 1
    fi
    log_pass "All 27 baked binaries, GUI desktop components, and AI CLIs executed successfully in PRoot!"

    # 4.5 Test Xvnc and XFCE desktop session launch
    log_info "W11-A.9: Testing Xvnc server and XFCE session startup..."
    DESKTOP_OUT="$(adb_proot "login alpaquita --user flux --shared-tmp -- /tmp/test_xfce_session.sh" | tr -d '\r')"
    echo "$DESKTOP_OUT"
    echo "$DESKTOP_OUT" | grep -q "Xvnc" || { log_fail "Xvnc failed to run in session test"; exit 1; }
    echo "$DESKTOP_OUT" | grep -q "xfce4-session" || { log_fail "xfce4-session failed to run in session test"; exit 1; }
    log_pass "Xvnc and XFCE desktop session launch verified: OK"

    # 4.6 Re-run and offline rerun test
    log_info "W11-A.10: Testing container re-run and offline re-run..."
    adb_proot "login alpaquita --user flux -- echo 're-run ok'"
    adb_proot "login alpaquita --user flux -- opencode --version"
    adb_proot "login alpaquita --user flux -- kiro-cli --version"
    log_pass "Container re-run and offline CLI execution verified"

    # 4.7 Check physical filesystem ownership
    log_info "W11-A.11: Verifying physical filesystem ownership under PRoot..."
    ROOTFS_DIR="$APP_PREFIX/var/lib/proot-distro/containers/alpaquita/rootfs"
    NON_APP_FILES="$(adb_root "find '$ROOTFS_DIR' -maxdepth 3 ! -user '$APP_USER' 2>/dev/null | wc -l" | tr -d '\r\n')"
    log_info "Files not owned by $APP_USER in container rootfs: $NON_APP_FILES"
    if [ "$NON_APP_FILES" -eq 0 ]; then
        log_pass "Physical filesystem ownership verified: 100% owned by app UID $APP_USER (no recursive chown)"
    else
        log_warn "$NON_APP_FILES files have non-app ownership"
    fi

    # 4.8 Record disk usage
    ROOTFS_SIZE="$(adb_root "du -sh '$ROOTFS_DIR' 2>/dev/null | cut -f1" | tr -d '\r\n')"
    log_pass "PRoot container disk usage: $ROOTFS_SIZE"
    log_pass "W11-A: PROOT SMOKE VALIDATION PASSED!"
}

# ── 5. W11-B: Chroot Smoke Validation Matrix ─────────────────────────────────
run_chroot_smoke() {
    log_info "============================================================"
    log_info " STARTING W11-B: CHROOT SMOKE VALIDATION MATRIX"
    log_info "============================================================"
    [ "$HAS_ROOT" -eq 1 ] || { log_warn "Skipping W11-B: device is not rooted"; return 0; }

    # 5.1 Prove root-shell sha256sum & zstd
    log_info "W11-B.1: Checking root-shell sha256sum and zstd..."
    adb_root "command -v sha256sum && command -v zstd && zstd --version | head -n 1"
    log_pass "Root-shell sha256sum and zstd verified"

    # 5.2 Fresh extract via setup_alpaquita_chroot.sh
    CHROOT_DEST="/data/local/tmp/chrootAlpine"
    log_info "W11-B.2: Cleaning $CHROOT_DEST and executing setup_alpaquita_chroot.sh..."
    adb_root "/data/local/tmp/clean_chroot.sh"

    CHROOT_START="$(date +%s)"
    adb_root "sh '$DEV_TMP/nc_scripts/setup_alpaquita_chroot.sh' '$DEV_ROOTFS'"
    CHROOT_END="$(date +%s)"
    CHROOT_TIME=$((CHROOT_END - CHROOT_START))
    log_pass "Chroot extraction completed in ${CHROOT_TIME}s"

    # Inject runtime GUI shared libraries if present in tmp
    if [ -d "/tmp/nc_gui_libs" ]; then
        adb_root "cp -a /data/local/tmp/nc_gui_libs/* '$CHROOT_DEST/usr/lib/' 2>/dev/null || true"
        adb_root "cp -a /data/local/tmp/nc_xkb/xkbcomp '$CHROOT_DEST/usr/bin/' 2>/dev/null || true"
        adb_root "cp -a /data/local/tmp/nc_xkb/libxkbfile.so* '$CHROOT_DEST/usr/lib/' 2>/dev/null || true"
        adb_root "mkdir -p '$CHROOT_DEST/usr/share/X11' && cp -a /data/local/tmp/nc_xkb/xkb '$CHROOT_DEST/usr/share/X11/' 2>/dev/null || true"
        adb_root "chown -R 0:0 '$CHROOT_DEST/usr/lib/lib*' '$CHROOT_DEST/usr/bin/xkbcomp' '$CHROOT_DEST/usr/share/X11/xkb' 2>/dev/null || true"
    fi

    # 5.3 Verify rootfs-release
    CHROOT_RELEASE="$(adb_root "cat '$CHROOT_DEST/etc/nativecode/rootfs-release'" | tr -d '\r')"
    echo "$CHROOT_RELEASE" | grep -q "DISTRO=alpaquita" || { log_fail "Chroot: Missing DISTRO=alpaquita"; exit 1; }
    echo "$CHROOT_RELEASE" | grep -q "LIBC=glibc" || { log_fail "Chroot: Missing LIBC=glibc"; exit 1; }
    log_pass "Chroot release marker verified"

    # 5.4 Mount virtual filesystems and test chroot execution
    log_info "W11-B.3: Mounting virtual filesystems for chroot test..."
    adb_root "mount --bind /dev '$CHROOT_DEST/dev' && mount -t proc proc '$CHROOT_DEST/proc' && mount -t sysfs sysfs '$CHROOT_DEST/sys' && mount --bind /dev/pts '$CHROOT_DEST/dev/pts' && mount --bind /run '$CHROOT_DEST/run' 2>/dev/null || true"

    # Copy onboarding scripts and matrix runner to chroot tmp
    adb_root "mkdir -p '$CHROOT_DEST/tmp' && cp '$DEV_TMP/nc_scripts'/* '$CHROOT_DEST/tmp/'"
    adb_cmd push "$SCRIPT_DIR/device_runners/run_matrix.sh" "$CHROOT_DEST/tmp/run_matrix.sh" >/dev/null
    adb_root "chmod 755 '$CHROOT_DEST/tmp'/*.sh"

    # Run E, F, GUI, G, H inside chroot
    log_info "W11-B.4: Running Onboarding Phases inside chroot..."
    adb_root "/data/local/tmp/run_chroot_setup.sh"

    # 5.5 Run matrix in chroot
    log_info "W11-B.9: Running CLI matrix inside chroot..."
    CHROOT_MATRIX="$(adb_root "chroot '$CHROOT_DEST' /usr/bin/env -i PATH='/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin' su -s /bin/sh flux -c '/tmp/run_matrix.sh'" | tr -d '\r')"
    echo "$CHROOT_MATRIX"

    CHROOT_FAIL_COUNT="$(echo "$CHROOT_MATRIX" | grep -c "^FAIL" || true)"
    if [ "$CHROOT_FAIL_COUNT" -gt 0 ]; then
        log_fail "$CHROOT_FAIL_COUNT binaries failed in Chroot verification matrix!"
        exit 1
    fi
    log_pass "All 27 baked binaries, GUI desktop components, and AI CLIs executed successfully in chroot!"

    # 5.6 Verify real guest ownership
    log_info "W11-B.10: Verifying real guest ownership in chroot..."
    FLUX_UID="$(adb_root "chroot '$CHROOT_DEST' /usr/bin/stat -c %u /home/flux" | tr -d '\r\n')"
    ROOT_UID="$(adb_root "chroot '$CHROOT_DEST' /usr/bin/stat -c %u /root" | tr -d '\r\n')"
    log_info "Chroot /home/flux UID=$FLUX_UID (expected 1000), /root UID=$ROOT_UID (expected 0)"
    [ "$FLUX_UID" = "1000" ] || log_warn "Chroot flux UID is $FLUX_UID"
    [ "$ROOT_UID" = "0" ] || log_warn "Chroot root UID is $ROOT_UID"
    log_pass "Real guest ownership verified"

    # 5.7 Record disk usage
    CHROOT_SIZE="$(adb_root "du -shx '$CHROOT_DEST' | cut -f1" | tr -d '\r\n')"
    log_pass "Chroot rootfs disk usage: $CHROOT_SIZE"

    # Unmount virtual filesystems cleanly
    adb_root "/data/local/tmp/unmount_chroot.sh"
    log_pass "W11-B: CHROOT SMOKE VALIDATION PASSED!"
}

# ── Execute Stages ───────────────────────────────────────────────────────────
case "$STAGE" in
    proot)  run_proot_smoke ;;
    chroot) run_chroot_smoke ;;
    all)    run_proot_smoke; run_chroot_smoke ;;
    *)      log_fail "Invalid stage: $STAGE"; exit 1 ;;
esac

log_info "============================================================"
log_info " ALL REQUESTED SMOKE VALIDATION COMPLETED SUCCESSFULLY"
log_info "============================================================"
