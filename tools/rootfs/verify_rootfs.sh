#!/bin/bash
# verify_rootfs.sh — offline verifier for built rootfs tree (no network).
# Usage: tools/rootfs/verify_rootfs.sh /path/to/rootfs
set -u
ROOTFS="${1:?usage: verify_rootfs.sh <rootfs-dir>}"
FAIL=0
need() { if [ -e "$ROOTFS$1" ]; then echo "OK   $1"; else echo "MISS $1"; FAIL=1; fi; }
have() {
    if chroot "$ROOTFS" /bin/sh -c "command -v $1 >/dev/null 2>&1" 2>/dev/null || \
       [ -x "$ROOTFS/usr/bin/$1" ] || [ -x "$ROOTFS/usr/local/bin/$1" ] || \
       [ -x "$ROOTFS/bin/$1" ] || [ -x "$ROOTFS/sbin/$1" ] || [ -x "$ROOTFS/usr/sbin/$1" ]; then
        echo "OK   cmd:$1"
        return 0
    else
        echo "MISS cmd:$1"
        return 1
    fi
}

echo "== manifest =="
need /etc/nativecode/rootfs-release
grep -q '^COMPONENT_E=1' "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null || { echo "MISS COMPONENT_E"; FAIL=1; }
grep -q '^COMPONENT_H=1' "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null || { echo "MISS COMPONENT_H"; FAIL=1; }
need /usr/local/lib/nativecode/rootfs-common.sh

IS_DEV=0
if grep -q '^COMPONENT_DEV=1' "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null; then
    IS_DEV=1
fi

echo "== base cmds =="
for c in sh bash sudo git node npm python3 java javac adb fastboot gcc g++ cmake ninja clang rustc cargo go gh; do
    have "$c" || FAIL=1
done
for c in opencode codex qwen agy claude grok kiro-cli; do
    if [ -x "$ROOTFS/home/flux/.local/bin/$c" ] || [ -x "$ROOTFS/usr/local/bin/$c" ]; then echo "OK   ai:$c"; else echo "MISS ai:$c"; FAIL=1; fi
done

echo "== desktop/gpu =="
need /usr/bin/startxfce4
[ -x "$ROOTFS/usr/bin/Xtigervnc" ] || [ -x "$ROOTFS/usr/bin/Xvnc" ] || { echo "MISS tigervnc server"; FAIL=1; }

echo "== customization assets (COMPONENT_G=1) =="
[ -d "$ROOTFS/usr/share/themes/Space-transparency" ] || [ -d "$ROOTFS/usr/share/themes/Space-light" ] || { echo "MISS G theme Space"; FAIL=1; }
[ -d "$ROOTFS/usr/share/icons/Papirus-Dark" ] || { echo "MISS G icon Papirus-Dark"; FAIL=1; }
[ -d "$ROOTFS/usr/share/icons/Vimix-white-cursors" ] || [ -d "$ROOTFS/usr/share/icons/Vimix-cursors" ] || { echo "MISS G cursor Vimix"; FAIL=1; }
[ -f "$ROOTFS/home/flux/Pictures/Wallpapers/fluxlinux-dark.png" ] || [ -f "$ROOTFS/home/flux/Pictures/Wallpapers/fluxlinux-light.png" ] || { echo "MISS G wallpaper"; FAIL=1; }
[ -f "$ROOTFS/home/flux/.oh-my-zsh/ohmyzsh.sh" ] || [ -f "$ROOTFS/home/flux/.oh-my-zsh/oh-my-zsh.sh" ] || { echo "MISS G oh-my-zsh"; FAIL=1; }

if [ "$IS_DEV" = "1" ]; then
    echo "== android/dev stack (COMPONENT_DEV=1) =="
    need /opt/android-sdk/platforms/android-36/android.jar
    [ -x "$ROOTFS/opt/android-sdk/build-tools/36.1.0/aapt2" ] || { echo "MISS aapt2 36.1.0"; FAIL=1; }
    [ -d "$ROOTFS/opt/android-sdk/ndk/29.0.14206865" ] || { echo "MISS ndk r29"; FAIL=1; }
    [ -x "$ROOTFS/opt/kotlin/bin/kotlinc" ] || [ -x "$ROOTFS/usr/bin/kotlinc" ] || { echo "MISS kotlinc"; FAIL=1; }
    [ -x "$ROOTFS/opt/gradle/bin/gradle" ] || [ -x "$ROOTFS/usr/bin/gradle" ] || { echo "MISS gradle"; FAIL=1; }
    need /etc/profile.d/nativecode-android.sh
fi

echo "== credential scrub =="
for p in /root/.ssh /home/flux/.ssh /root/.git-credentials /home/flux/.git-credentials /root/.npmrc /home/flux/.npmrc /home/flux/.config/gh/hosts.yml /home/flux/.config/gh/hosts.yml.bak /home/flux/.config/fluxlinux/cli-auth.env /etc/machine-id /var/lib/dbus/machine-id; do
    if [ -e "$ROOTFS$p" ]; then echo "LEAK $p"; FAIL=1; fi
done

if [ "$FAIL" = "0" ]; then echo "VERIFY PASS"; else echo "VERIFY FAIL"; fi
exit "$FAIL"
