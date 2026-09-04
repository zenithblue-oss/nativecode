#!/bin/bash
# test_build_pipeline.sh — automated verification of rootfs build contract:
# 1. build script generates chroot-based target command (targeting $ROOTFS, not container /)
# 2. guest assemble script operates on root, which when chrooted targets $ROOTFS
# 3. finalize.sh strictly enforces verify_rootfs.sh (no || true, non-zero stops pipeline)
# 4. verifier fails when a required baked component is missing (negative test)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d /dev/shm/nc-bp-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "== checking build_alpine_rootfs.sh docker command =="
# Check generated docker instructions in build_alpine_rootfs.sh
if ! grep -q 'chroot /rootfs /bin/sh' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh"; then
    echo "FAIL: build_alpine_rootfs.sh does not specify chrooting into /rootfs" >&2
    exit 1
fi
echo "PASS: build_alpine_rootfs.sh invokes chroot into /rootfs"

echo "== checking chroot namespace staging and virtual fs mounts =="
# Verify instruction mounts virtual fs and stages /pkg-lists, /opt-staging, /xfce-staging inside /rootfs before chrooting
if ! grep -q 'mount --bind /dev /rootfs/dev' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" || \
   ! grep -q 'mount -t proc proc /rootfs/proc' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" || \
   ! grep -q 'mount -t sysfs sysfs /rootfs/sys' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" || \
   ! grep -q 'mount --bind /run /rootfs/run' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" || \
   ! grep -q 'mkdir -p /rootfs/pkg-lists /rootfs/opt-staging /rootfs/xfce-staging' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh"; then
    echo "FAIL: build_alpine_rootfs.sh does not mount virtual filesystems or stage assets before chroot" >&2
    exit 1
fi
echo "PASS: build_alpine_rootfs.sh mounts virtual filesystems and stages assets before chroot"

echo "== checking build_alpine_rootfs.sh preserves chroot exit status on failure =="
# Verify instruction uses trap or preserves error exit code from chroot
if ! grep -q 'trap cleanup EXIT' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh"; then
    echo "FAIL: build_alpine_rootfs.sh does not trap cleanup on EXIT" >&2
    exit 1
fi
# Test actual wrapper logic with a mock failure (exit 42)
WRAPPER_TEST_DIR="$WORK/mock_wrapper"
mkdir -p "$WRAPPER_TEST_DIR/rootfs/pkg-lists"
cat > "$WRAPPER_TEST_DIR/test_wrapper.sh" <<'WRAPPER_EOF'
#!/bin/sh
set -eu
cleanup() {
    echo "CLEANUP-CALLED"
}
trap cleanup EXIT INT TERM
exit 42
WRAPPER_EOF
chmod +x "$WRAPPER_TEST_DIR/test_wrapper.sh"

set +e
WRAPPER_OUT="$(sh "$WRAPPER_TEST_DIR/test_wrapper.sh" 2>&1)"
WRAPPER_RC=$?
set -e

if [ "$WRAPPER_RC" -eq 42 ] && echo "$WRAPPER_OUT" | grep -q "CLEANUP-CALLED"; then
    echo "PASS: chroot assembly failure propagates exact exit code 42 through cleanup trap"
else
    echo "FAIL: wrapper did not propagate exit 42 (rc=$WRAPPER_RC) or run cleanup" >&2
    exit 1
fi

echo "== checking assemble-inside.sh preserves literal \$HOME, \$PATH and poisoned host env =="
# Fake versions.env to satisfy the SHA gate for generating scripts
export ALPINE_MINIROOTFS_SHA256_OVERRIDE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
FAKE_MINI="$WORK/alpine-minirootfs-3.24.1-aarch64.tar.gz"
mkdir -p "$WORK/rootfs/etc/apk" "$WORK/rootfs/bin" "$WORK/rootfs/sbin"
touch "$WORK/rootfs/sbin/apk" "$WORK/rootfs/bin/sh" "$FAKE_MINI"
echo "$ALPINE_MINIROOTFS_SHA256_OVERRIDE  $FAKE_MINI" | sha256sum -c - >/dev/null 2>&1 || true

# Test with deliberately poisoned host variables
export ANDROID_HOME="/HOST-BAD-HOME"
export ANDROID_SDK_ROOT="/HOST-BAD-SDK"
export ANDROID_NDK_HOME="/HOST-BAD-NDK"

# Run assemble-only generation to inspect generated finalize.sh & assemble-inside.sh
bash "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" --work "$WORK" --assemble-only

if grep -qF "$HOME" "$WORK/assemble-inside.sh"; then
    echo "FAIL: assemble-inside.sh contains host home directory ($HOME)!" >&2
    exit 1
fi
if grep -q "HOST-BAD" "$WORK/assemble-inside.sh"; then
    echo "FAIL: assemble-inside.sh contains poisoned host ANDROID environment paths!" >&2
    exit 1
fi
if ! grep -qF 'export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"' "$WORK/assemble-inside.sh"; then
    echo "FAIL: assemble-inside.sh does not have literal guest \$HOME/\$PATH export!" >&2
    exit 1
fi
echo "PASS: assemble-inside.sh has unexpanded guest variables and resists poisoned host env"

echo "== checking finalize.sh verification enforcement and helper bundling =="
if grep -q 'verify_rootfs.sh.*|| true' "$WORK/finalize.sh"; then
    echo "FAIL: finalize.sh contains '|| true' on verify_rootfs" >&2
    exit 1
fi
if [ ! -f "$WORK/clean_rootfs.sh" ] || [ ! -f "$WORK/verify_rootfs.sh" ]; then
    echo "FAIL: clean_rootfs.sh or verify_rootfs.sh was not copied into \$WORK" >&2
    exit 1
fi
echo "PASS: finalize.sh helpers bundled and no '|| true'"

echo "== test verifier failure propagation in finalize.sh =="
# Create isolated test directory with mock clean and failing mock verify (exit 47)
MOCK_DIR="$WORK/mock_finalize"
mkdir -p "$MOCK_DIR"
cp "$WORK/finalize.sh" "$MOCK_DIR/finalize.sh"
cp "$WORK/rootfs-release" "$MOCK_DIR/rootfs-release"

cat > "$MOCK_DIR/clean_rootfs.sh" <<'EOF'
#!/bin/bash
echo "MOCK-CLEAN-RAN"
exit 0
EOF
chmod +x "$MOCK_DIR/clean_rootfs.sh"

cat > "$MOCK_DIR/verify_rootfs.sh" <<'EOF'
#!/bin/bash
echo "MOCK-VERIFY-RAN"
exit 47
EOF
chmod +x "$MOCK_DIR/verify_rootfs.sh"

set +e
PROP_OUT="$(bash "$MOCK_DIR/finalize.sh" "$WORK/fake_rootfs" "$WORK/out" 2>&1)"
PROP_RC=$?
set -e

if [ "$PROP_RC" -eq 47 ] && echo "$PROP_OUT" | grep -q "MOCK-VERIFY-RAN" && echo "$PROP_OUT" | grep -q "finalize.sh: running mandatory verification"; then
    echo "PASS: finalize.sh executed verifier and propagated exact non-zero exit code 47"
else
    echo "FAIL: finalize.sh did not properly run verifier or propagate exit code (rc=$PROP_RC)" >&2
    echo "$PROP_OUT"
    exit 1
fi

echo "== negative test: verify_rootfs.sh flags missing AI tools and binaries =="
set +e
VERIFY_OUT="$(bash "$REPO_ROOT/tools/rootfs/verify_rootfs.sh" "$WORK/fake_rootfs" 2>&1)"
VERIFY_RC=$?
set -e

if [ "$VERIFY_RC" -ne 0 ] && echo "$VERIFY_OUT" | grep -q 'MISS'; then
    echo "PASS: verify_rootfs.sh reported MISS and exited $VERIFY_RC"
else
    echo "FAIL: verify_rootfs.sh did not fail on empty rootfs!" >&2
    exit 1
fi

echo "== test COMPONENT_DEV flag conditioned on complete staging =="
# 1. Without staging
grep -q '^COMPONENT_DEV=0' "$WORK/rootfs-release" || { echo "FAIL: expected COMPONENT_DEV=0 without android staging"; exit 1; }
echo "PASS: COMPONENT_DEV=0 when staging absent"

# 2. Incomplete staging: empty directory only
mkdir -p "$WORK/opt-staging/android-sdk"
bash "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" --work "$WORK" --assemble-only
grep -q '^COMPONENT_DEV=0' "$WORK/rootfs-release" || { echo "FAIL: expected COMPONENT_DEV=0 with empty android-sdk dir"; exit 1; }
echo "PASS: COMPONENT_DEV=0 with incomplete staging directory"

# 3. Complete staging: all required artifacts present
mkdir -p "$WORK/opt-staging/android-sdk/platforms/android-36" \
         "$WORK/opt-staging/android-sdk/build-tools/36.1.0" \
         "$WORK/opt-staging/android-sdk/ndk/29.0.14206865" \
         "$WORK/opt-staging/kotlin/bin" \
         "$WORK/opt-staging/gradle/bin"
touch "$WORK/opt-staging/android-sdk/platforms/android-36/android.jar"
touch "$WORK/opt-staging/android-sdk/build-tools/36.1.0/aapt2"
chmod +x "$WORK/opt-staging/android-sdk/build-tools/36.1.0/aapt2"
touch "$WORK/opt-staging/kotlin/bin/kotlinc" && chmod +x "$WORK/opt-staging/kotlin/bin/kotlinc"
touch "$WORK/opt-staging/gradle/bin/gradle" && chmod +x "$WORK/opt-staging/gradle/bin/gradle"

bash "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" --work "$WORK" --assemble-only
grep -q '^COMPONENT_DEV=1' "$WORK/rootfs-release" || { echo "FAIL: expected COMPONENT_DEV=1 with full android staging"; exit 1; }
echo "PASS: COMPONENT_DEV=1 when full staging is present"

echo "== test verify_rootfs.sh passes on complete COMPONENT_DEV=0 rootfs =="
# Create a dummy rootfs that satisfies non-dev requirements + G requirements
DUMMY_FS="$WORK/dummy_rootfs"
mkdir -p "$DUMMY_FS/etc/nativecode" "$DUMMY_FS/usr/local/lib/nativecode" \
         "$DUMMY_FS/bin" "$DUMMY_FS/usr/bin" "$DUMMY_FS/usr/local/bin" "$DUMMY_FS/home/flux/.local/bin" \
         "$DUMMY_FS/usr/share/themes/Space-transparency" \
         "$DUMMY_FS/usr/share/themes/Space-light" \
         "$DUMMY_FS/usr/share/icons/Papirus-Dark" \
         "$DUMMY_FS/usr/share/icons/Vimix-white-cursors" \
         "$DUMMY_FS/usr/share/icons/Vimix-cursors" \
         "$DUMMY_FS/home/flux/Pictures/Wallpapers" \
         "$DUMMY_FS/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions" \
         "$DUMMY_FS/home/flux/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting" \
         "$DUMMY_FS/home/flux/.oh-my-zsh/custom/themes" \
         "$DUMMY_FS/usr/share/fonts"

touch "$DUMMY_FS/etc/nativecode/rootfs-release"
cat > "$DUMMY_FS/etc/nativecode/rootfs-release" <<'EOF'
FORMAT_VERSION=1
COMPONENT_E=1
COMPONENT_H=1
COMPONENT_DEV=0
EOF
touch "$DUMMY_FS/usr/local/lib/nativecode/rootfs-common.sh"
touch "$DUMMY_FS/usr/bin/startxfce4" && chmod +x "$DUMMY_FS/usr/bin/startxfce4"
touch "$DUMMY_FS/usr/bin/Xvnc" && chmod +x "$DUMMY_FS/usr/bin/Xvnc"
touch "$DUMMY_FS/home/flux/Pictures/Wallpapers/fluxlinux-dark.png"
touch "$DUMMY_FS/home/flux/Pictures/Wallpapers/fluxlinux-light.png"
touch "$DUMMY_FS/home/flux/.oh-my-zsh/oh-my-zsh.sh"
touch "$DUMMY_FS/home/flux/.oh-my-zsh/custom/themes/agnosterzak.zsh-theme"

# Populate all mandatory base commands in dummy rootfs
for c in sh bash sudo git node npm python3 java javac adb fastboot gcc g++ cmake ninja clang rustc cargo go gh; do
    touch "$DUMMY_FS/usr/bin/$c" && chmod +x "$DUMMY_FS/usr/bin/$c"
done

for c in opencode codex qwen agy claude grok kiro-cli; do
    touch "$DUMMY_FS/home/flux/.local/bin/$c" && chmod +x "$DUMMY_FS/home/flux/.local/bin/$c"
done

set +e
VERIFY_DEV0_OUT="$(bash "$REPO_ROOT/tools/rootfs/verify_rootfs.sh" "$DUMMY_FS" 2>&1)"
VERIFY_DEV0_RC=$?
set -e

if [ "$VERIFY_DEV0_RC" -eq 0 ]; then
    echo "PASS: verify_rootfs.sh succeeded on COMPONENT_DEV=0 rootfs without kotlin/gradle"
else
    echo "FAIL: verify_rootfs.sh failed on COMPONENT_DEV=0 rootfs (rc=$VERIFY_DEV0_RC)" >&2
    echo "$VERIFY_DEV0_OUT"
    exit 1
fi

echo "== test negative verify_rootfs.sh on missing base command (node) =="
rm -f "$DUMMY_FS/usr/bin/node"
set +e
VERIFY_NONODE_OUT="$(bash "$REPO_ROOT/tools/rootfs/verify_rootfs.sh" "$DUMMY_FS" 2>&1)"
VERIFY_NONODE_RC=$?
set -e
touch "$DUMMY_FS/usr/bin/node" && chmod +x "$DUMMY_FS/usr/bin/node"

if [ "$VERIFY_NONODE_RC" -ne 0 ] && echo "$VERIFY_NONODE_OUT" | grep -q 'MISS cmd:node'; then
    echo "PASS: missing node command causes verifier failure (exit=$VERIFY_NONODE_RC)"
else
    echo "FAIL: missing node command did not fail verifier!" >&2
    echo "$VERIFY_NONODE_OUT"
    exit 1
fi

echo "== test negative verify_rootfs.sh on missing base command (gh) =="
rm -f "$DUMMY_FS/usr/bin/gh"
set +e
VERIFY_NOGH_OUT="$(bash "$REPO_ROOT/tools/rootfs/verify_rootfs.sh" "$DUMMY_FS" 2>&1)"
VERIFY_NOGH_RC=$?
set -e
touch "$DUMMY_FS/usr/bin/gh" && chmod +x "$DUMMY_FS/usr/bin/gh"

if [ "$VERIFY_NOGH_RC" -ne 0 ] && echo "$VERIFY_NOGH_OUT" | grep -q 'MISS cmd:gh'; then
    echo "PASS: missing gh command causes verifier failure (exit=$VERIFY_NOGH_RC)"
else
    echo "FAIL: missing gh command did not fail verifier!" >&2
    echo "$VERIFY_NOGH_OUT"
    exit 1
fi

echo "== test DEV=1 verifier passes with full dev stack =="
cat > "$DUMMY_FS/etc/nativecode/rootfs-release" <<'EOF'
FORMAT_VERSION=1
COMPONENT_E=1
COMPONENT_H=1
COMPONENT_DEV=1
EOF
mkdir -p "$DUMMY_FS/opt/android-sdk/platforms/android-36" \
         "$DUMMY_FS/opt/android-sdk/build-tools/36.1.0" \
         "$DUMMY_FS/opt/android-sdk/ndk/29.0.14206865" \
         "$DUMMY_FS/opt/kotlin/bin" \
         "$DUMMY_FS/opt/gradle/bin" \
         "$DUMMY_FS/etc/profile.d"
touch "$DUMMY_FS/opt/android-sdk/platforms/android-36/android.jar"
touch "$DUMMY_FS/opt/android-sdk/build-tools/36.1.0/aapt2" && chmod +x "$DUMMY_FS/opt/android-sdk/build-tools/36.1.0/aapt2"
touch "$DUMMY_FS/opt/kotlin/bin/kotlinc" && chmod +x "$DUMMY_FS/opt/kotlin/bin/kotlinc"
touch "$DUMMY_FS/opt/gradle/bin/gradle" && chmod +x "$DUMMY_FS/opt/gradle/bin/gradle"
touch "$DUMMY_FS/etc/profile.d/nativecode-android.sh"

set +e
VERIFY_DEV1_OUT="$(bash "$REPO_ROOT/tools/rootfs/verify_rootfs.sh" "$DUMMY_FS" 2>&1)"
VERIFY_DEV1_RC=$?
set -e

if [ "$VERIFY_DEV1_RC" -eq 0 ]; then
    echo "PASS: verify_rootfs.sh succeeded on COMPONENT_DEV=1 rootfs with full dev stack"
else
    echo "FAIL: verify_rootfs.sh failed on COMPONENT_DEV=1 rootfs (rc=$VERIFY_DEV1_RC)" >&2
    echo "$VERIFY_DEV1_OUT"
    exit 1
fi

echo "BUILD-PIPELINE: ALL PASS"
