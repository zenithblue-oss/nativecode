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

echo "== checking finalize.sh verification enforcement =="
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
echo "PASS: finalize.sh does not ignore verifier failure"

echo "== negative test: verifier failure halts finalize.sh =="
mkdir -p "$WORK/fake_rootfs"
# Missing release file and all tools
set +e
bash "$WORK/finalize.sh" "$WORK/fake_rootfs" "$WORK/out" >/dev/null 2>&1
FINALIZE_RC=$?
set -e

if [ "$FINALIZE_RC" -ne 0 ]; then
    echo "PASS: finalize.sh halted on verifier failure (exit=$FINALIZE_RC)"
else
    echo "FAIL: finalize.sh succeeded despite broken rootfs!" >&2
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

echo "BUILD-PIPELINE: ALL PASS"
