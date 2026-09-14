#!/usr/bin/env bash
# alpaquita-package-audit.sh — audit Alpaquita glibc/aarch64 package availability
# for NativeCode rootfs against BellSoft repositories.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/alpaquita-audit-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

REPORT_OUT="${1:-$SCRIPT_DIR/alpaquita-package-report.md}"

CORE_URL="https://packages.bell-sw.com/alpaquita/glibc/stream/core/aarch64/APKINDEX.tar.gz"
UNIVERSE_URL="https://packages.bell-sw.com/alpaquita/glibc/stream/universe/aarch64/APKINDEX.tar.gz"

echo "Downloading Alpaquita glibc stream package indexes..."
curl -sL "$CORE_URL" | tar -xz -C "$WORK" APKINDEX && mv "$WORK/APKINDEX" "$WORK/APKINDEX.core"
curl -sL "$UNIVERSE_URL" | tar -xz -C "$WORK" APKINDEX && mv "$WORK/APKINDEX" "$WORK/APKINDEX.universe"

cat "$WORK/APKINDEX.core" "$WORK/APKINDEX.universe" > "$WORK/APKINDEX.all"
grep -E '^P:' "$WORK/APKINDEX.all" | cut -d: -f2 | sort -u > "$WORK/pkgs.all"

TOTAL_PKGS=$(wc -l < "$WORK/pkgs.all")
echo "Loaded $TOTAL_PKGS unique packages."

classify_pkg() {
    local target="$1"
    local candidates=("${@:2}")
    if grep -qx "$target" "$WORK/pkgs.all"; then
        echo "SAME NAME|$target|Present in Alpaquita repositories"
        return 0
    fi
    for c in "${candidates[@]}"; do
        if grep -qx "$c" "$WORK/pkgs.all"; then
            echo "RENAMED|$c|Replaces $target"
            return 0
        fi
    done
    # Search for substring matches
    local sub
    sub=$(grep -i "$target" "$WORK/pkgs.all" | head -n 3 | tr '\n' ' ' || true)
    if [ -n "$sub" ]; then
        echo "MISSING|none|Not found ($target). Potential related: $sub"
    else
        echo "MISSING|none|Not found ($target) in core or universe"
    fi
}

{
    echo "# Alpaquita glibc/AArch64 Package Availability Report"
    echo ""
    echo "**Repository:** BellSoft Alpaquita glibc \`stream\` (core + universe)"
    echo "**Total Unique Packages:** $TOTAL_PKGS"
    echo "**Audit Date:** $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo ""
    echo "| Target Component | Status | Resolved Package | Notes |"
    echo "| :--- | :--- | :--- | :--- |"
} > "$REPORT_OUT"

# Packages required by Worker Plan §6
AUDIT_LIST=(
    "bash"
    "sudo"
    "shadow"
    "ca-certificates"
    "curl"
    "wget"
    "unzip"
    "tar"
    "xz"
    "tzdata"
    "locales:glibc-locales"
    "dbus"
    "dbus-x11"
    "coreutils"
    "libstdc++"
    "mesa"
    "mesa-dri-gallium"
    "mesa-gl"
    "mesa-vulkan-freedreno"
    "mesa-vulkan-virtio"
    "vulkan-loader"
    "glycin-image-rs"
    "glycin-svg"
    "gdk-pixbuf"
    "git"
    "zsh"
    "fastfetch"
    "python3"
    "nodejs"
    "npm"
    "github-cli"
    "build-base"
    "gcc"
    "g++"
    "cmake"
    "ninja"
    "clang:clang22,clang21,clang20"
    "rust"
    "cargo"
    "go"
    "adb:android-tools"
    "fastboot:android-tools"
    "openjdk21-jdk:liberica21-jdk,liberica21"
    "font-dejavu:dejavu-fonts,ttf-dejavu"
    "font-jetbrains-mono-nerd"
    "xfce4"
    "xfce4-session"
    "xfce4-settings"
    "xfce4-panel"
    "xfce4-terminal"
    "xfce4-screensaver"
    "xfce4-screenshooter"
    "thunar"
    "xfwm4"
    "tigervnc"
    "adwaita-icon-theme"
    "fontconfig"
)

for item in "${AUDIT_LIST[@]}"; do
    pkg="${item%%:*}"
    cands="${item#*:}"
    if [ "$pkg" = "$cands" ]; then
        IFS=',' read -r -a cand_arr <<< ""
    else
        IFS=',' read -r -a cand_arr <<< "$cands"
    fi
    res=$(classify_pkg "$pkg" "${cand_arr[@]}")
    status=$(echo "$res" | cut -d'|' -f1)
    resolved=$(echo "$res" | cut -d'|' -f2)
    notes=$(echo "$res" | cut -d'|' -f3)
    echo "| \`$pkg\` | **$status** | \`$resolved\` | $notes |" >> "$REPORT_OUT"
done

echo "Report generated at $REPORT_OUT"
