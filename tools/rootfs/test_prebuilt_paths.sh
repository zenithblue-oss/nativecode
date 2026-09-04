#!/bin/bash
# test_prebuilt_paths.sh — prove baked E/F/GUI/G/H never reach network/package tools.
#
# Runs each guest setup script inside `unshare -rm` with:
#   - /etc, /home, /usr/local, /var bound to prepared fakes (host untouched)
#   - /tmp as private tmpfs, /usr/bin/bwrap + /usr/bin/sudo shadowed by fakes
#   - fail-fast stubs (exit 99) for apk/curl/wget/git/npm early on sanitized PATH
#   - /etc/nativecode/rootfs-release with every COMPONENT_*=1
#
# Asserts per script: exit 0 AND zero STUB-CALL. A legacy negative control
# (marker removed) must DO call the apk stub, proving interception works.
# Requires: unshare (util-linux), bash, coreutils. No root needed.
# Usage: tools/rootfs/test_prebuilt_paths.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GUEST_DIR="$REPO_ROOT/app/src/main/assets/scripts"

command -v unshare >/dev/null 2>&1 || { echo "SKIP: unshare not found"; exit 2; }
unshare -rm true 2>/dev/null || { echo "SKIP: unshare -rm not permitted in this environment"; exit 2; }

T="$(mktemp -d /dev/shm/nc-pt-XXXXXX)"
trap 'rm -rf "$T"' EXIT

mkdir -p "$T/guest" "$T/etc/nativecode" "$T/etc/apk" "$T/etc/sudoers.d" \
    "$T/etc/profile.d" "$T/etc/fluxlinux" \
    "$T/home/flux/.local/bin" "$T/home/flux/.grok/bin" \
    "$T/home/flux/.config/fluxlinux" \
    "$T/usrlocal/sbin" "$T/usrlocal/bin" "$T/usrlocal/lib/nativecode" \
    "$T/var/tmp" "$T/var/log" "$T/var/lib/dbus" "$T/var/cache/apk" "$T/var/lib/apk/db"

cp "$GUEST_DIR/setup_alpine_family.sh" \
   "$GUEST_DIR/setup_alpaquita_family.sh" \
   "$GUEST_DIR/setup_hw_accel_debian.sh" \
   "$GUEST_DIR/setup_xfce4.sh" \
   "$GUEST_DIR/setup_customization_alpine.sh" \
   "$GUEST_DIR/setup_cli_tools.sh" "$T/guest/"
cp "$REPO_ROOT/tools/rootfs/rootfs-common.sh" "$T/usrlocal/lib/nativecode/rootfs-common.sh"

cat > "$T/etc/nativecode/rootfs-release" <<'EOF'
FORMAT_VERSION=1
DISTRO=alpine
ALPINE_BRANCH=3.24
ARCH=aarch64
BUILD_ID=test
BUILD_GIT_SHA=test
NODE_MAJOR=24
ANDROID_PLATFORM=36
ANDROID_BUILD_TOOLS=36.1.0
ANDROID_NDK=29.0.14206865
KOTLIN=2.1.0
GRADLE=9.2.1
COMPONENT_E=1
COMPONENT_F=1
COMPONENT_GUI=1
COMPONENT_G=1
COMPONENT_H=1
COMPONENT_GH=1
COMPONENT_DEV=1
EOF

cat > "$T/etc/passwd" <<'EOF'
root:x:0:0:root:/root:/bin/bash
flux:x:1000:1000:flux:/home/flux:/bin/bash
EOF
cat > "$T/etc/group" <<'EOF'
root:x:0:
flux:x:1000:
EOF
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$T/etc/resolv.conf"
printf '/bin/sh\n/bin/bash\n' > "$T/etc/shells"
: > "$T/etc/machine-id"
: > "$T/etc/apk/repositories"
[ -f /etc/nsswitch.conf ] && cp /etc/nsswitch.conf "$T/etc/nsswitch.conf" || true
[ -f /etc/ld.so.cache ] && cp /etc/ld.so.cache "$T/etc/ld.so.cache" || true

# Fake baked userland binaries (as flux).
fake_bin() { printf '#!/bin/sh\necho "%s"\n' "$2" > "$1"; chmod +x "$1"; }
fake_bin "$T/home/flux/.local/bin/node" "v24.11.0"
fake_bin "$T/home/flux/.local/bin/npm" "11.0.0"
for b in opencode codex qwen agy claude grok kiro-cli kiro; do
    fake_bin "$T/home/flux/.local/bin/$b" "$b test-1.0"
done
fake_bin "$T/home/flux/.grok/bin/grok" "grok test-1.0"
fake_bin "$T/usrlocal/sbin/startxfce4" "startxfce4 test"
fake_bin "$T/usrlocal/sbin/gh" "gh version 2.67.0"

# Fail-fast network/package stubs (early on guest-sanitized PATH).
for s in apk curl wget git npm; do
    cat > "$T/usrlocal/sbin/$s" <<'EOF'
#!/bin/sh
echo "STUB-CALL: $(basename "$0") $*" >> "$NC_TEST_CALL_LOG"
exit 99
EOF
    chmod +x "$T/usrlocal/sbin/$s"
done

# su stub: emulate root `su -s SH - USER -c CMD` for CliToolsInstaller path.
cat > "$T/usrlocal/sbin/su" <<'EOF'
#!/bin/sh
user=""; cmd=""; want_c=0; skip_next=0
for a in "$@"; do
    if [ "$skip_next" = 1 ]; then skip_next=0; continue; fi
    case "$a" in
        -s) skip_next=1 ;;
        -c) want_c=1 ;;
        -|-l|--*) ;;
        -*) ;;
        *) if [ "$want_c" = 1 ]; then cmd="$a"; want_c=0; else user="$a"; fi ;;
    esac
done
echo "NEUTRAL-CALL: su $user" >> "$NC_TEST_CALL_LOG"
HOME=/home/flux PATH=/home/flux/.local/bin:/home/flux/.grok/bin:/home/flux/bin:/home/flux/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/sh -c "$cmd"
EOF
chmod +x "$T/usrlocal/sbin/su"

# bwrap fake WITH NativeCode shim signature (repair must be a no-op).
cat > "$T/bwrap-fake" <<'EOF'
#!/bin/sh
# NativeCode proot: skip sandbox; run glycin loader (regular file only — not /).
while [ $# -gt 0 ]; do
  case "$1" in
    /*) if [ -f "$1" ] && [ -x "$1" ]; then exec "$@"; fi ;;
  esac
  shift
done
exit 127
EOF
: > "$T/sudo-fake"

cat > "$T/inner.sh" <<'INNER_EOF'
#!/bin/bash
# Runs INSIDE unshare -rm: private mounts, mapped root. No host mutation.
set -u
T="${NC_TEST_T:?}"
export NC_TEST_CALL_LOG="$T/calls.log"
: > "$NC_TEST_CALL_LOG"

# Spy on real chown calls targeting /home/flux
mkdir -p "$T/bin"
cp /bin/chown "$T/bin/real-chown" 2>/dev/null || true
cat > "$T/chown-spy" <<SPY_EOF
#!/bin/sh
for a in "\$@"; do
    if [ "\$a" = "/home/flux" ] || [ "\$a" = "/home/flux/" ]; then
        echo "FORBIDDEN-HOME-CHOWN: chown \$*" >> "$NC_TEST_CALL_LOG"
    fi
done
exec "$T/bin/real-chown" "\$@"
SPY_EOF
chmod +x "$T/chown-spy"
mount --bind "$T/chown-spy" /bin/chown 2>/dev/null || true
if [ -e /usr/bin/chown ]; then
    mount --bind "$T/chown-spy" /usr/bin/chown 2>/dev/null || true
fi

mount --bind "$T/etc" /etc
mount --bind "$T/home" /home
mount --bind "$T/usrlocal" /usr/local
mount --bind "$T/var" /var
mount -t tmpfs tmpfs /tmp
chmod 1777 /tmp
mount --bind "$T/bwrap-fake" /usr/bin/bwrap
mount --bind "$T/sudo-fake" /usr/bin/sudo

# shadow host gh if present so test controls its presence via /usr/local/sbin/gh
if [ -e /usr/bin/gh ]; then
    mount --bind "$T/sudo-fake" /usr/bin/gh
fi

export NATIVECODE_ISOLATION=proot FLUX_GPU=virgl FLUX_THEME=dark
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

PASS=0; FAIL=0
log_calls_from() { sed -n "$1,\$p" "$NC_TEST_CALL_LOG"; }
mark() { wc -l < "$NC_TEST_CALL_LOG"; }

check() { # name expected_exit script shell...
    _name="$1"; _want="$2"; _script="$3"; _sh="$4"
    _m="$(mark)"
    _out="$("$_sh" "$_script" alpine </dev/null 2>&1)"; _rc=$?
    _calls="$(log_calls_from "$((_m + 1))" | grep -c STUB-CALL || true)"
    if [ "$_rc" = "$_want" ] && { [ "$_want" != "0" ] || [ "$_calls" = "0" ]; }; then
        echo "PASS $_name (exit=$_rc stub_calls=$_calls)"
        PASS=$((PASS + 1))
    else
        echo "FAIL $_name (want exit=$_want, got exit=$_rc stub_calls=$_calls)"
        echo "----- output -----"; printf '%s\n' "$_out" | tail -n 20; echo "--------------------"
        FAIL=$((FAIL + 1))
    fi
}

echo "== negative control: E without marker must reach apk stub =="
mv /etc/nativecode/rootfs-release /etc/nativecode/release.bak
_m="$(mark)"
sh "$T/guest/setup_alpine_family.sh" alpine </dev/null >/tmp/neg.log 2>&1; _rc=$?
if [ "$_rc" != "0" ] && log_calls_from "$((_m + 1))" | grep -q 'STUB-CALL: apk'; then
    echo "PASS neg-E-legacy-reaches-apk (exit=$_rc)"
    PASS=$((PASS + 1))
else
    echo "FAIL neg-E-legacy-reaches-apk (exit=$_rc) — stubs may not intercept!"
    tail -n 10 /tmp/neg.log
    FAIL=$((FAIL + 1))
fi
mv /etc/nativecode/release.bak /etc/nativecode/rootfs-release

echo "== negative control: baked E missing gh must fail with exit 1 =="
# Temporarily shadow or remove gh stub
mv /usr/local/sbin/gh /usr/local/sbin/gh.bak
_m="$(mark)"
sh "$T/guest/setup_alpine_family.sh" alpine </dev/null >/tmp/neg_gh.log 2>&1; _rc=$?
if [ "$_rc" = "1" ] && grep -qE 'gh missing in prebuilt rootfs' /tmp/neg_gh.log; then
    echo "PASS neg-alpine-E-missing-gh-fails (exit=1)"
    PASS=$((PASS + 1))
else
    echo "FAIL neg-alpine-E-missing-gh-fails (want exit=1, got exit=$_rc)"
    tail -n 10 /tmp/neg_gh.log
    FAIL=$((FAIL + 1))
fi

_m="$(mark)"
sh "$T/guest/setup_alpaquita_family.sh" alpaquita </dev/null >/tmp/neg_alpa_gh.log 2>&1; _rc=$?
mv /usr/local/sbin/gh.bak /usr/local/sbin/gh
if [ "$_rc" = "1" ] && grep -qE 'gh missing in prebuilt rootfs' /tmp/neg_alpa_gh.log; then
    echo "PASS neg-alpaquita-E-missing-gh-fails (exit=1)"
    PASS=$((PASS + 1))
else
    echo "FAIL neg-alpaquita-E-missing-gh-fails (want exit=1, got exit=$_rc)"
    tail -n 10 /tmp/neg_alpa_gh.log
    FAIL=$((FAIL + 1))
fi

# Mock OMZ tree with plugins and theme
mkdir -p "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions" \
         "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting" \
         "$T/home/flux/.oh-my-zsh/custom/themes"
touch "$T/home/flux/.oh-my-zsh/oh-my-zsh.sh"
touch "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh"
touch "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"
echo "# agnosterzak theme" > "$T/home/flux/.oh-my-zsh/custom/themes/agnosterzak.zsh-theme"

echo "== prebuilt positives: exit 0 + zero STUB-CALL =="
check "E-baked-alpine"    0 "$T/guest/setup_alpine_family.sh" sh
check "E-baked-alpaquita" 0 "$T/guest/setup_alpaquita_family.sh" sh
check "F-baked"           0 "$T/guest/setup_hw_accel_debian.sh" bash
check "GUI-baked"         0 "$T/guest/setup_xfce4.sh" bash
check "G-baked"           0 "$T/guest/setup_customization_alpine.sh" sh
check "H-baked"           0 "$T/guest/setup_cli_tools.sh" bash

echo "== negative control: baked G missing OMZ plugin must fail with exit 1 and zero git calls =="
rm -f "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh"
rm -rf "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions"
_m="$(mark)"
sh "$T/guest/setup_customization_alpine.sh" alpine </dev/null >/tmp/neg_g.log 2>&1; _rc=$?
_calls="$(log_calls_from "$((_m + 1))" | grep -c 'STUB-CALL: git' || true)"
mkdir -p "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions"
touch "$T/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh"

if [ "$_rc" = "1" ] && [ "$_calls" = "0" ] && grep -q 'baked plugin zsh-autosuggestions missing' /tmp/neg_g.log; then
    echo "PASS neg-G-missing-plugin-fails (exit=1 git_calls=0)"
    PASS=$((PASS + 1))
else
    echo "FAIL neg-G-missing-plugin-fails (want exit=1 git_calls=0, got exit=$_rc git_calls=$_calls)"
    tail -n 10 /tmp/neg_g.log
    FAIL=$((FAIL + 1))
fi

echo "== test negative control: G with FLUX_SKIP_POKEMON=0 in baked mode must NOT clone git =="
_m="$(mark)"
FLUX_SKIP_POKEMON=0 sh "$T/guest/setup_customization_alpine.sh" alpine </dev/null >/tmp/poke.log 2>&1; _rc=$?
_calls="$(log_calls_from "$((_m + 1))" | grep -c 'STUB-CALL: git' || true)"
if [ "$_rc" = "0" ] && [ "$_calls" = "0" ]; then
    echo "PASS neg-G-pokemon-baked-zero-network (exit=0 git_calls=0)"
    PASS=$((PASS + 1))
else
    echo "FAIL neg-G-pokemon-baked-zero-network (exit=$_rc git_calls=$_calls)"
    tail -n 10 /tmp/poke.log
    FAIL=$((FAIL + 1))
fi

echo "== spot checks =="
[ "$(tr -d '[:space:]' </etc/fluxlinux/gpu_mode 2>/dev/null)" = "virgl" ] \
    && { echo "PASS gpu_mode=virgl"; PASS=$((PASS + 1)); } \
    || { echo "FAIL gpu_mode"; FAIL=$((FAIL + 1)); }
[ -x /home/flux/.vnc/xstartup ] \
    && { echo "PASS vnc-xstartup"; PASS=$((PASS + 1)); } \
    || { echo "FAIL vnc-xstartup"; FAIL=$((FAIL + 1)); }
grep -q 'grok/bin' /home/flux/.config/fluxlinux/cli-tools.env 2>/dev/null \
    && { echo "PASS grok-path"; PASS=$((PASS + 1)); } \
    || { echo "FAIL grok-path"; FAIL=$((FAIL + 1)); }

echo "RESULT pass=$PASS fail=$FAIL"
[ "$FAIL" = "0" ]
INNER_EOF

echo "running prebuilt-path sandbox (unshare -rm)…"
if NC_TEST_T="$T" unshare -rm --propagation private bash "$T/inner.sh"; then
    echo "PREBUILT-PATHS: ALL PASS"
else
    echo "PREBUILT-PATHS: FAIL" >&2
    exit 1
fi
