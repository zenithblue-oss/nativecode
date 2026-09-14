#!/bin/sh
# setup_android_dev_alpine.sh — MANUAL / repair only (NOT part of onboarding).
# Verifies the minimal Android/JVM suite baked in the prebuilt rootfs:
#   JDK 21, cmdline-tools 15859902, Platform 36, Build-Tools 36.1.0 (ARM64 musl),
#   NDK r29 (29.0.14206865 ARM64 musl), Kotlin 2.1.0, Gradle 9.2.1.
# No Flutter. No gtk+3.0-dev. No SDK 34/35. No NDK 27.
# Idempotent: repairs PATH/env, never downloads in prebuilt path.
# Must run as root inside the guest.

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: must run as root inside the guest (got uid=$(id -u))."
    exit 1
fi

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

NC_COMMON="/usr/local/lib/nativecode/rootfs-common.sh"
if [ -f "$NC_COMMON" ]; then
    # shellcheck source=/dev/null
    . "$NC_COMMON" 2>/dev/null || true
else
    NC_RELEASE_FILE="${NC_RELEASE_FILE:-/etc/nativecode/rootfs-release}"
    nc_component_baked() { [ -n "${1:-}" ] && [ -f "$NC_RELEASE_FILE" ] && grep -q "^COMPONENT_$1=1" "$NC_RELEASE_FILE" 2>/dev/null; }
fi

FAIL=0
check_cmd() {
    if command -v "$1" >/dev/null 2>&1; then
        echo "OK   $1: $("$1" --version 2>&1 | head -1 || echo ok)"
    else
        echo "MISS $1"
        FAIL=1
    fi
}

echo "NativeCode: verifying Android/JVM developer suite (manual)..."
check_cmd java
check_cmd javac
check_cmd kotlinc
check_cmd gradle
check_cmd adb
check_cmd fastboot
check_cmd cmake
check_cmd ninja

[ -f /opt/android-sdk/platforms/android-36/android.jar ] \
    && echo "OK   android-36/android.jar" \
    || { echo "MISS android-36/android.jar"; FAIL=1; }
[ -x /opt/android-sdk/build-tools/36.1.0/aapt2 ] \
    && echo "OK   build-tools/36.1.0/aapt2" \
    || { echo "MISS build-tools/36.1.0/aapt2"; FAIL=1; }
[ -d /opt/android-sdk/ndk/29.0.14206865 ] \
    && echo "OK   ndk/29.0.14206865" \
    || { echo "MISS ndk/29.0.14206865"; FAIL=1; }

# Env repair (per-user + system profile, no downloads)
mkdir -p /etc/profile.d
cat > /etc/profile.d/nativecode-android.sh <<'PROFILE'
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/29.0.14206865}"
case ":$PATH:" in
    *:/opt/android-sdk/platform-tools:*) ;;
    *) export PATH="/opt/android-sdk/platform-tools:$PATH" ;;
esac
case ":$PATH:" in
    *:/opt/android-sdk/build-tools/36.1.0:*) ;;
    *) export PATH="/opt/android-sdk/build-tools/36.1.0:$PATH" ;;
esac
case ":$PATH:" in
    *:/opt/gradle/bin:*) ;;
    *) export PATH="/opt/gradle/bin:$PATH" ;;
esac
case ":$PATH:" in
    *:/opt/kotlin/bin:*) ;;
    *) export PATH="/opt/kotlin/bin:$PATH" ;;
esac
PROFILE
chmod 644 /etc/profile.d/nativecode-android.sh

if nc_component_baked DEV 2>/dev/null; then
    echo "NativeCode: DEV baked — verification only (no downloads)"
else
    echo "NOTE: legacy minimal rootfs — install full suite via prebuilt rootfs rebuild (no ad-hoc SDK downloads here)"
fi

if [ "$FAIL" = "0" ]; then
    echo "NativeCode: Android dev suite OK"
else
    echo "NativeCode: Android dev suite INCOMPLETE (see MISS lines)"
fi
exit "$FAIL"
