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

echo "== checking chroot namespace staging =="
# Verify instruction stages /pkg-lists and /opt-staging inside /rootfs before chrooting
if ! grep -q 'mkdir -p /rootfs/pkg-lists /rootfs/opt-staging' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" || \
   ! grep -q 'cp /host-work/base.pkgs /host-work/dev.pkgs /host-work/rootfs-common.sh /rootfs/pkg-lists/' "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh"; then
    echo "FAIL: build_alpine_rootfs.sh does not stage pkg-lists inside /rootfs before chroot" >&2
    exit 1
fi
echo "PASS: build_alpine_rootfs.sh stages pkg-lists inside /rootfs before chroot"

echo "== checking finalize.sh verification enforcement and helper bundling =="
# Fake versions.env to satisfy the SHA gate for generating scripts
export ALPINE_MINIROOTFS_SHA256_OVERRIDE="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
FAKE_MINI="$WORK/alpine-minirootfs-3.24.1-aarch64.tar.gz"
mkdir -p "$WORK/rootfs/etc/apk" "$WORK/rootfs/bin" "$WORK/rootfs/sbin"
touch "$WORK/rootfs/sbin/apk" "$WORK/rootfs/bin/sh" "$FAKE_MINI"
echo "$ALPINE_MINIROOTFS_SHA256_OVERRIDE  $FAKE_MINI" | sha256sum -c - >/dev/null 2>&1 || true

# Run assemble-only generation to inspect generated finalize.sh & assemble-inside.sh
bash "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" --work "$WORK" --assemble-only

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

echo "== test COMPONENT_DEV flag conditioned on staging =="
grep -q '^COMPONENT_DEV=0' "$WORK/rootfs-release" || { echo "FAIL: expected COMPONENT_DEV=0 without android staging"; exit 1; }
echo "PASS: COMPONENT_DEV=0 when staging absent"

# With opt-staging/android-sdk present
mkdir -p "$WORK/opt-staging/android-sdk"
bash "$REPO_ROOT/tools/rootfs/build_alpine_rootfs.sh" --work "$WORK" --assemble-only
grep -q '^COMPONENT_DEV=1' "$WORK/rootfs-release" || { echo "FAIL: expected COMPONENT_DEV=1 with android staging"; exit 1; }
echo "PASS: COMPONENT_DEV=1 when staging present"

echo "BUILD-PIPELINE: ALL PASS"
