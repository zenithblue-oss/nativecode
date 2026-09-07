#!/bin/bash
# verify_rootfs.sh — offline verifier for built rootfs tree (no network).
# Usage: tools/rootfs/verify_rootfs.sh /path/to/rootfs
set -u
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOTFS="${1:?usage: verify_rootfs.sh <rootfs-dir>}"
FAIL=0
DISTRO="${ROOTFS_DISTRO:-}"
if [ -z "$DISTRO" ] && [ -f "$ROOTFS/etc/nativecode/rootfs-release" ]; then
    DISTRO="$(grep '^DISTRO=' "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null | cut -d= -f2 || true)"
fi
[ -n "$DISTRO" ] || DISTRO="alpaquita"
need() { if [ -e "$ROOTFS$1" ]; then echo "OK   $1"; else echo "MISS $1"; FAIL=1; fi; }
have() {
    local target
    if [ -L "$ROOTFS/usr/bin/$1" ]; then
        target="$(readlink "$ROOTFS/usr/bin/$1")"
        case "$target" in
            /*) [ -e "$ROOTFS$target" ] && { echo "OK   cmd:$1"; return 0; } ;;
            *) [ -e "$ROOTFS/usr/bin/$target" ] && { echo "OK   cmd:$1"; return 0; } ;;
        esac
    fi
    if [ -L "$ROOTFS/bin/$1" ]; then
        target="$(readlink "$ROOTFS/bin/$1")"
        case "$target" in
            /*) [ -e "$ROOTFS$target" ] && { echo "OK   cmd:$1"; return 0; } ;;
            *) [ -e "$ROOTFS/bin/$target" ] && { echo "OK   cmd:$1"; return 0; } ;;
        esac
    fi
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
for c in E F GUI G H GH; do
    grep -q "^COMPONENT_${c}=1$" "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null || \
        { echo "MISS COMPONENT_$c"; FAIL=1; }
done
need /usr/local/lib/nativecode/rootfs-common.sh

# Verify glibc loader if LIBC=glibc
if grep -q '^LIBC=glibc' "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null; then
    echo "== glibc loader =="
    if [ -e "$ROOTFS/lib/ld-linux-aarch64.so.1" ] || [ -e "$ROOTFS/usr/lib/ld-linux-aarch64.so.1" ]; then
        echo "OK   glibc:ld-linux-aarch64.so.1"
    else
        echo "MISS glibc:ld-linux-aarch64.so.1"
        FAIL=1
    fi
fi

IS_DEV=0
if grep -q '^COMPONENT_DEV=1' "$ROOTFS/etc/nativecode/rootfs-release" 2>/dev/null; then
    IS_DEV=1
fi

echo "== base cmds =="
for c in sh bash sudo git node npm python3 java javac gcc g++ cmake ninja clang rustc cargo go gh; do
    have "$c" || FAIL=1
done
for c in opencode codex qwen agy claude grok kiro-cli; do
    target=""
    if [ -L "$ROOTFS/home/flux/.local/bin/$c" ]; then
        target="$(readlink "$ROOTFS/home/flux/.local/bin/$c")"
        case "$target" in
            /*) [ -e "$ROOTFS$target" ] && { echo "OK   ai:$c"; continue; } ;;
            *) [ -e "$ROOTFS/home/flux/.local/bin/$target" ] && { echo "OK   ai:$c"; continue; } ;;
        esac
    fi
    if [ -x "$ROOTFS/home/flux/.local/bin/$c" ] || [ -x "$ROOTFS/usr/local/bin/$c" ]; then
        echo "OK   ai:$c"
    else
        echo "MISS ai:$c"
        FAIL=1
    fi
done

echo "== desktop/gpu =="
need /usr/bin/startxfce4
[ -x "$ROOTFS/usr/bin/Xtigervnc" ] || [ -x "$ROOTFS/usr/bin/Xvnc" ] || { echo "MISS tigervnc server"; FAIL=1; }
if [ "$DISTRO" = "alpaquita" ]; then
    need /usr/lib/libICE.so.6
    need /usr/lib/libSM.so.6
    need /usr/lib/libxcvt.so.0
    need /usr/lib/libvte-2.91.so.0
    need /usr/lib/libdisplay-info.so.3
fi

echo "== XFCE baked configuration overlay =="
need /home/flux/.config/xfce4/helpers.rc
need /home/flux/.config/xfce4/terminal/terminalrc
need /home/flux/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml
need /home/flux/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml
need /home/flux/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml
need /home/flux/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml

XCONF="$ROOTFS/home/flux/.config/xfce4/xfconf/xfce-perchannel-xml"
if [ -f "$XCONF/xsettings.xml" ]; then
    grep -q 'ThemeName.*Space-transparency' "$XCONF/xsettings.xml" || { echo "FAIL xsettings ThemeName Space-transparency"; FAIL=1; }
    grep -q 'IconThemeName.*Papirus-Dark' "$XCONF/xsettings.xml" || { echo "FAIL xsettings IconThemeName Papirus-Dark"; FAIL=1; }
    grep -q 'CursorThemeName.*Vimix-white-cursors' "$XCONF/xsettings.xml" || { echo "FAIL xsettings CursorThemeName Vimix-white-cursors"; FAIL=1; }
fi
if [ -f "$XCONF/xfwm4.xml" ]; then
    grep -q 'theme.*Space-transparency' "$XCONF/xfwm4.xml" || { echo "FAIL xfwm4 theme Space-transparency"; FAIL=1; }
fi
if [ -f "$XCONF/xfce4-desktop.xml" ]; then
    grep -q 'fluxlinux-dark.png' "$XCONF/xfce4-desktop.xml" || { echo "FAIL xfce4-desktop wallpaper fluxlinux-dark.png"; FAIL=1; }
fi
if [ -f "$ROOTFS/home/flux/.config/xfce4/helpers.rc" ]; then
    grep -q '^TerminalEmulator=xfce4-terminal$' "$ROOTFS/home/flux/.config/xfce4/helpers.rc" || { echo "FAIL helpers.rc TerminalEmulator=xfce4-terminal"; FAIL=1; }
fi
if [ -f "$XCONF/xfce4-panel.xml" ]; then
    PY_VALIDATOR=""
    for cand in "$DIR/test_xfce_overlay.py" "$DIR/tools/rootfs/test_xfce_overlay.py" "${REPO_ROOT:-}/tools/rootfs/test_xfce_overlay.py" "$(git -C "$DIR" rev-parse --show-toplevel 2>/dev/null)/tools/rootfs/test_xfce_overlay.py" "/home/user/repos/NC-PLAY/tools/rootfs/test_xfce_overlay.py"; do
        if [ -n "$cand" ] && [ -f "$cand" ]; then
            PY_VALIDATOR="$cand"
            break
        fi
    done
    if [ -n "$PY_VALIDATOR" ]; then
        python3 "$PY_VALIDATOR" "$XCONF/xfce4-panel.xml" || { echo "FAIL xfce4-panel overlay structural contract"; FAIL=1; }
        echo "panel CONFIG CONTRACT PASS"
    else
        echo "FAIL test_xfce_overlay.py not found"
        FAIL=1
    fi
fi

if [ "$(uname -m)" = "aarch64" ] && [ -x "$ROOTFS/bin/sh" ] && [ -x "$ROOTFS/home/flux/.local/bin/opencode" ]; then
    echo "== executing AI tools (ARM64) =="
    chroot "$ROOTFS" su -s /bin/sh flux -c '
      export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"
      opencode --version
      codex --version
      qwen --version
      claude --version
      agy --version
      grok --version
      kiro-cli --version
    ' || FAIL=1
fi

echo "== customization assets (COMPONENT_G=1) =="
[ -d "$ROOTFS/usr/share/themes/Space-transparency" ] || { echo "MISS G theme Space-transparency"; FAIL=1; }
[ -d "$ROOTFS/usr/share/themes/Space-light" ] || { echo "MISS G theme Space-light"; FAIL=1; }
[ -d "$ROOTFS/usr/share/icons/Papirus-Dark" ] || { echo "MISS G icon Papirus-Dark"; FAIL=1; }
[ -d "$ROOTFS/usr/share/icons/Vimix-white-cursors" ] || { echo "MISS G cursor Vimix-white"; FAIL=1; }
[ -d "$ROOTFS/usr/share/icons/Vimix-cursors" ] || { echo "MISS G cursor Vimix"; FAIL=1; }
[ -f "$ROOTFS/home/flux/Pictures/Wallpapers/fluxlinux-dark.png" ] || { echo "MISS G wallpaper fluxlinux-dark.png"; FAIL=1; }
[ -f "$ROOTFS/home/flux/Pictures/Wallpapers/fluxlinux-light.png" ] || { echo "MISS G wallpaper fluxlinux-light.png"; FAIL=1; }
[ -f "$ROOTFS/home/flux/.oh-my-zsh/oh-my-zsh.sh" ] || [ -f "$ROOTFS/home/flux/.oh-my-zsh/ohmyzsh.sh" ] || { echo "MISS G oh-my-zsh"; FAIL=1; }
[ -f "$ROOTFS/home/flux/.oh-my-zsh/custom/plugins/zsh-autosuggestions/zsh-autosuggestions.zsh" ] || { echo "MISS G plugin file zsh-autosuggestions.zsh"; FAIL=1; }
[ -f "$ROOTFS/home/flux/.oh-my-zsh/custom/plugins/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ] || { echo "MISS G plugin file zsh-syntax-highlighting.zsh"; FAIL=1; }
[ -f "$ROOTFS/home/flux/.oh-my-zsh/custom/themes/agnosterzak.zsh-theme" ] || { echo "MISS G theme agnosterzak"; FAIL=1; }
if [ -f "$ROOTFS/usr/share/fonts/TTF/JetBrainsMonoNerdFont-Regular.ttf" ] || \
   [ -f "$ROOTFS/usr/share/fonts/jetbrains-mono-nerd/JetBrainsMonoNerdFont-Regular.ttf" ] || \
   [ -f "$ROOTFS/usr/share/fonts/JetBrainsMonoNerdFont-Regular.ttf" ] || \
   [ -f "$ROOTFS/usr/share/fonts/dejavu/DejaVuSans.ttf" ] || \
   [ -f "$ROOTFS/usr/share/fonts/dejavu-core/DejaVuSans.ttf" ] || \
   [ -f "$ROOTFS/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf" ]; then
    echo "OK   G fonts"
else
    echo "MISS G font (JetBrains / DejaVu)"
    FAIL=1
fi

if [ "$IS_DEV" = "1" ]; then
    echo "== android/dev stack (COMPONENT_DEV=1) =="
    have adb || [ -x "$ROOTFS/opt/android-sdk/platform-tools/adb" ] || { echo "MISS adb"; FAIL=1; }
    have fastboot || [ -x "$ROOTFS/opt/android-sdk/platform-tools/fastboot" ] || { echo "MISS fastboot"; FAIL=1; }
    need /opt/android-sdk/platforms/android-36/android.jar
    [ -x "$ROOTFS/opt/android-sdk/build-tools/36.1.0/aapt2" ] || { echo "MISS aapt2 36.1.0"; FAIL=1; }
    [ -d "$ROOTFS/opt/android-sdk/ndk/29.0.14206865" ] || { echo "MISS ndk r29"; FAIL=1; }
    [ -x "$ROOTFS/opt/kotlin/bin/kotlinc" ] || [ -x "$ROOTFS/usr/bin/kotlinc" ] || { echo "MISS kotlinc"; FAIL=1; }
    [ -x "$ROOTFS/opt/gradle/bin/gradle" ] || [ -x "$ROOTFS/usr/bin/gradle" ] || { echo "MISS gradle"; FAIL=1; }
    need /etc/profile.d/nativecode-android.sh
fi

echo "== credential scrub =="
for p in /root/.ssh /home/flux/.ssh /root/.git-credentials /home/flux/.git-credentials /home/flux/.config/gh/hosts.yml /home/flux/.config/gh/hosts.yml.bak /home/flux/.config/fluxlinux/cli-auth.env /etc/machine-id /var/lib/dbus/machine-id; do
    [ ! -e "$ROOTFS$p" ] || { echo "LEAK: $p"; FAIL=1; }
done
if [ -f "$ROOTFS/root/.npmrc" ] && grep -iq "authToken\|password\|_auth" "$ROOTFS/root/.npmrc" 2>/dev/null; then
    echo "LEAK: /root/.npmrc"
    FAIL=1
fi
if [ -f "$ROOTFS/home/flux/.npmrc" ] && grep -iq "authToken\|password\|_auth" "$ROOTFS/home/flux/.npmrc" 2>/dev/null; then
    echo "LEAK: /home/flux/.npmrc"
    FAIL=1
fi

if [ "$FAIL" = "0" ]; then echo "VERIFY PASS"; else echo "VERIFY FAIL"; fi
exit "$FAIL"
