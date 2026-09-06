#!/bin/sh
export PATH="/home/flux/.local/bin:/home/flux/.grok/bin:/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin:$PATH"

test_bin() {
    name="$1"
    cmd="$2"
    out=$(eval "$cmd" 2>&1 | grep -v '^[[:space:]]*$' | head -n 1)
    rc=$?
    if [ $rc -eq 0 ] && [ -n "$out" ] && ! echo "$out" | grep -qiE 'error while loading shared libraries|cannot execute'; then
        echo "PASS | $name | $out"
    else
        echo "FAIL | $name | rc=$rc out=$out"
    fi
}

echo "=== MATRIX TEST OUTPUT ==="
test_bin "Arch" "uname -m"
test_bin "Glibc" "/lib/ld-linux-aarch64.so.1 --version"
test_bin "Zsh" "zsh --version"
test_bin "Fastfetch" "fastfetch --version"
test_bin "Xvnc" "Xvnc -version 2>&1 | grep TigerVNC"
test_bin "Node" "node --version"
test_bin "Npm" "npm --version"
test_bin "GitHub CLI" "gh --version"
test_bin "GCC" "gcc --version"
test_bin "G++" "g++ --version"
test_bin "CMake" "cmake --version"
test_bin "Ninja" "ninja --version"
test_bin "Clang" "clang --version"
test_bin "Rustc" "rustc --version"
test_bin "Cargo" "cargo --version"
test_bin "Go" "go version"
test_bin "Java" "java -version"
test_bin "Javac" "javac -version"
test_bin "OpenCode" "opencode --version"
test_bin "Codex" "codex --version"
test_bin "Qwen" "qwen --version"
test_bin "AGY" "agy --version"
test_bin "Claude" "claude --version"
test_bin "Grok" "grok --version"
test_bin "Kiro-CLI (Stripped)" "kiro-cli --version"

# GUI binaries that need DISPLAY
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
Xvnc :1 -SecurityTypes None -geometry 1024x768 >/dev/null 2>&1 &
VNC_PID=$!
sleep 2
test_bin "Thunar" "DISPLAY=:1 thunar --version 2>&1 | grep -i thunar"
test_bin "Xfwm4" "DISPLAY=:1 xfwm4 --version"
test_bin "Xfce4-session" "DISPLAY=:1 xfce4-session --version"
test_bin "Xfce4-terminal" "DISPLAY=:1 xfce4-terminal --version"
test_bin "Xfce4-panel" "DISPLAY=:1 xfce4-panel --version"
kill $VNC_PID 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
