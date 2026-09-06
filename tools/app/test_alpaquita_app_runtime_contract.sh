#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

LCB="$ROOT/app/src/main/java/com/zenithblue/nativecode/terminal/LinuxCommandBuilder.kt"
PCB="$ROOT/app/src/main/java/com/zenithblue/nativecode/terminal/ProotCommandBuilder.kt"
GUI="$ROOT/app/src/main/assets/scripts/start_gui.sh"
ONB="$ROOT/app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt"

grep -q 'else -> ProotCommandBuilder.build(' "$LCB"
grep -q 'else -> ProotCommandBuilder.buildInteractive(' "$LCB"

if grep -q 'else -> ProotFastCommandBuilder.build(' "$LCB"; then
    echo "FAIL: product runtime still globally uses ProotFastCommandBuilder"
    exit 1
fi

grep -q 'remove("LD_PRELOAD")' "$PCB"

grep -q 'proot-distro' "$GUI"
if grep -q -- '--rootfs=' "$GUI"; then
    echo "FAIL: start_gui.sh contains handwritten product direct-PROot rootfs launch"
    exit 1
fi

grep -q 'setup_alpaquita_family.sh' "$ONB"
grep -q 'setup_hw_accel_debian.sh' "$ONB"
grep -q 'setup_xfce4.sh' "$ONB"
grep -q 'setup_customization_alpine.sh' "$ONB"
grep -q 'setup_cli_tools.sh' "$ONB"

STOP_GUI="$ROOT/app/src/main/assets/scripts/stop_gui.sh"
CCB="$ROOT/app/src/main/java/com/zenithblue/nativecode/terminal/ChrootCommandBuilder.kt"
CLI_SETUP="$ROOT/app/src/main/assets/scripts/setup_cli_tools.sh"
ALPAQUITA_BUILDER="$ROOT/tools/rootfs/build_alpaquita_rootfs.sh"

grep -q 'unset LD_PRELOAD' "$GUI"
grep -q 'unset LD_PRELOAD' "$STOP_GUI"
grep -q 'TB=/system/bin/toybox' "$GUI"
grep -q 'TB=/system/bin/toybox' "$STOP_GUI"
grep -q 'DISTRO=${1:-alpaquita}' "$STOP_GUI"
grep -q 'opencode) exec opencode --auto' "$CCB" || grep -q 'opencode --auto' "$CCB"

if grep -q 'postinstall.mjs 2>/dev/null || true' "$ALPAQUITA_BUILDER"; then
    echo "FAIL: Alpaquita builder still ignores opencode postinstall failure"
    exit 1
fi

echo "PASS: Alpaquita app runtime compatibility contract"
