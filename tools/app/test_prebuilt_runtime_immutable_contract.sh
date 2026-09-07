#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

ONB="$ROOT/app/src/main/java/com/zenithblue/nativecode/OnboardingActivity.kt"
MAIN="$ROOT/app/src/main/java/com/zenithblue/nativecode/MainActivity.kt"
BUILDER="$ROOT/tools/rootfs/build_alpaquita_rootfs.sh"
VERIFY="$ROOT/tools/rootfs/verify_rootfs.sh"
OVERLAY_DIR="$ROOT/tools/rootfs/overlay/alpaquita/home/flux/.config/xfce4"

echo "== [1/3] ONBOARDING IMMUTABLE RUNTIME CONTRACT =="

# 1. No runPrebuiltGuestRepair calls
if grep -q 'runPrebuiltGuestRepair' "$ONB"; then
    echo "FAIL: OnboardingActivity still references runPrebuiltGuestRepair" >&2
    exit 1
fi

# 2. No automatic guest E/F/GUI/G/H setup calls in PRoot onboarding execution flow
if grep -q 'runShellCommand.*setup_alpaquita_family' "$ONB" || \
   grep -q 'runShellCommand.*setup_hw_accel' "$ONB" || \
   grep -q 'runShellCommand.*setup_xfce4' "$ONB" || \
   grep -q 'runShellCommand.*setup_customization_alpine' "$ONB" || \
   grep -q 'runShellCommand.*setup_cli_tools' "$ONB"; then
    echo "FAIL: OnboardingActivity still executes guest setup scripts after install" >&2
    exit 1
fi

# 3. inspectExistingAlpaquita exists and is invoked before PAD / install
grep -q 'fun inspectExistingAlpaquita(): ExistingProotState' "$ONB"
grep -q 'val existing = inspectExistingAlpaquita()' "$ONB"

# 4. Valid existing rootfs has a skip path without proot-distro install
grep -q 'Existing valid Alpaquita environment detected — setup already complete' "$ONB" || \
grep -q 'Existing Alpaquita environment detected — setup already complete' "$ONB"

# 5. No maybeRunCliToolsSetup or obsolete AI outcome copy
if grep -q 'maybeRunCliToolsSetup' "$ONB"; then
    echo "FAIL: OnboardingActivity still has dead maybeRunCliToolsSetup" >&2
    exit 1
fi

if grep -q 'Retry in Settings' "$ONB" || \
   grep -q 'AI tools skipped' "$ONB" || \
   grep -q 'AI tools incomplete' "$ONB" || \
   grep -q 'Off — shells only' "$ONB"; then
    echo "FAIL: OnboardingActivity still has obsolete AI retry / skipped copy" >&2
    exit 1
fi

echo "PASS: Onboarding immutable runtime contract"

echo "== [2/3] AI CLI TOOLS PAGE NON-MUTATING CONTRACT =="

# 1. No mutating buttons/functions on the AI CLI page
if grep -q 'fun confirmClearAllAiCredentials' "$MAIN"; then
    echo "FAIL: MainActivity still has confirmClearAllAiCredentials" >&2
    exit 1
fi

if grep -q 'fun confirmClearAiSuiteFlag' "$MAIN"; then
    echo "FAIL: MainActivity still has confirmClearAiSuiteFlag" >&2
    exit 1
fi

if grep -q 'fun confirmAndInstallAiCliTools' "$MAIN"; then
    echo "FAIL: MainActivity still has confirmAndInstallAiCliTools" >&2
    exit 1
fi

# 2. Settings Hub does not add Repairs button
if grep -q 'settingsHubLayout.addView(buildScriptsSectionButton())' "$MAIN"; then
    echo "FAIL: MainActivity still adds buildScriptsSectionButton to settingsHubLayout" >&2
    exit 1
fi

if grep -q 'Settings → Repairs' "$MAIN" || grep -q 'Settings -> Repairs' "$MAIN"; then
    echo "FAIL: MainActivity still contains stale Settings -> Repairs copy" >&2
    exit 1
fi

# 2. Page subtitle & description declaring non-mutating policy
grep -q '// INCLUDED IN THE PRE-BUILT LINUX RUNTIME' "$MAIN"
grep -q 'NativeCode does not install, repair, authenticate, or remove' "$MAIN"

# 3. All 7 bundled tools listed
for tool in "OpenCode" "Codex" "Qwen Code" "Claude Code" "Antigravity" "Grok" "Kiro"; do
    grep -q "$tool" "$MAIN"
done

echo "PASS: AI CLI page non-mutating contract"

echo "== [3/3] ROOTFS BUILD & VERIFICATION CONTRACT =="

# 1. Overlay directory exists and contains required files
[ -d "$OVERLAY_DIR" ] || { echo "FAIL: missing overlay dir $OVERLAY_DIR" >&2; exit 1; }
[ -f "$OVERLAY_DIR/helpers.rc" ] || { echo "FAIL: missing helpers.rc in overlay" >&2; exit 1; }
[ -f "$OVERLAY_DIR/terminal/terminalrc" ] || { echo "FAIL: missing terminalrc in overlay" >&2; exit 1; }
[ -f "$OVERLAY_DIR/xfconf/xfce-perchannel-xml/xsettings.xml" ] || { echo "FAIL: missing xsettings.xml in overlay" >&2; exit 1; }
[ -f "$OVERLAY_DIR/xfconf/xfce-perchannel-xml/xfwm4.xml" ] || { echo "FAIL: missing xfwm4.xml in overlay" >&2; exit 1; }
[ -f "$OVERLAY_DIR/xfconf/xfce-perchannel-xml/xfce4-desktop.xml" ] || { echo "FAIL: missing xfce4-desktop.xml in overlay" >&2; exit 1; }
[ -f "$OVERLAY_DIR/xfconf/xfce-perchannel-xml/xfce4-panel.xml" ] || { echo "FAIL: missing xfce4-panel.xml in overlay" >&2; exit 1; }
[ -f "$OVERLAY_DIR/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml" ] || { echo "FAIL: missing keyboard shortcuts in overlay" >&2; exit 1; }

# 2. Builder stages and bakes overlay
grep -q 'rootfs-overlay' "$BUILDER"
grep -q 'cp -a /rootfs-overlay/home/flux/.config/xfce4 /home/flux/.config/' "$BUILDER"

# 3. Builder bakes iceauth fallback symlink
grep -q '/cache/gui-work/destdir/usr/bin/iceauth' "$BUILDER"

# 4. Mandatory OpenCode postinstall without ignoring failure
grep -F -q 'cd "\$HOME/.local/lib/node_modules/opencode-ai" && node postinstall.mjs' "$BUILDER"
if grep -q 'postinstall.mjs 2>/dev/null || true' "$BUILDER"; then
    echo "FAIL: builder ignores postinstall failure" >&2
    exit 1
fi

# 5. Verifier checks desktop defaults and helpers.rc
grep -q 'ThemeName.*Space-transparency' "$VERIFY"
grep -q 'IconThemeName.*Papirus-Dark' "$VERIFY"
grep -q 'CursorThemeName.*Vimix-white-cursors' "$VERIFY"
grep -q 'theme.*Space-transparency' "$VERIFY"
grep -q 'fluxlinux-dark.png' "$VERIFY"
grep -q 'TerminalEmulator=xfce4-terminal' "$VERIFY"

# 6. Structural XFCE overlay panel contract
python3 "$ROOT/tools/rootfs/test_xfce_overlay.py"

echo "PASS: Rootfs build and verification contract"
echo "IMMUTABLE-RUNTIME-CONTRACT: ALL PASS"
