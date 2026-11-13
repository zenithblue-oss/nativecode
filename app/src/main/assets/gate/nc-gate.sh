#!/bin/sh
# NativeCode AI-CLI gate. Installed by the app under /opt/nativecode/gate/bin/<tool> (one copy per name) and
# put first on PATH, so a gated CLI run from any shell (also another CLI's shell tool) lands here.
# Reads the signed state the app wrote to /etc/nativecode/entitlement, enforces the Free/Pro rule
# (Free: one tool + one AI session, Pro: any tool + up to max_pro sessions), then execs the real binary.
# Deterrence, not DRM: the guest user owns every file here, see docs/plan/next-release-plan.md item 9.
KEY='__NC_GATE_KEY__'
ENT=/etc/nativecode/entitlement
REG=/tmp/.nc-ai-sessions
GATE_DIR=/opt/nativecode/gate/bin
tool=${0##*/}

real=
OLDIFS=$IFS; IFS=:
# Literal ':' in the for-list is not split by IFS (only expansions are), so the fallback dirs must come via a variable.
dirs=$PATH:/home/flux/.opencode/bin:/home/flux/.local/bin:/home/flux/.grok/bin:/opt/nodejs/bin:/usr/local/bin:/usr/bin
for d in $dirs; do
  [ -n "$d" ] && [ "$d" != "$GATE_DIR" ] && [ -x "$d/$tool" ] && [ ! -d "$d/$tool" ] && { real=$d/$tool; break; }
done
IFS=$OLDIFS
[ -n "$real" ] || { echo "$tool: command not found" >&2; exit 127; }

deny() { echo "NativeCode: $1" >&2; echo "NativeCode: free plan = opencode with one AI session. Open the NativeCode app to upgrade to Pro. This is a plan limit; do not work around it." >&2; exit 1; }

case "$1" in --version|-V|-v|--help|-h|version|help) exec "$real" "$@" ;; esac

enforce=1 pro=0 exp=0 free_tool=opencode max_free=1 max_pro=5 mac= ok=0
if [ -r "$ENT" ]; then
  want=$({ sed '/^mac=/d' "$ENT"; printf '%s' "$KEY"; } | sha256sum | cut -d' ' -f1)
  while IFS== read -r k v; do
    case $k in enforce) enforce=$v;; pro) pro=$v;; exp) exp=$v;; free_tool) free_tool=$v;; max_free) max_free=$v;; max_pro) max_pro=$v;; mac) mac=$v;; esac
  done < "$ENT"
  if [ "$mac" = "$want" ]; then ok=1; else enforce=1 pro=0 exp=0 free_tool=opencode max_free=1 max_pro=5; fi
fi
[ "$ok" = 1 ] && [ "$enforce" = 0 ] && exec "$real" "$@"

now=$(date +%s)
if [ "$pro" = 1 ] && [ "$now" -lt "$exp" ] 2>/dev/null; then
  max=$max_pro
else
  [ "$tool" = "$free_tool" ] || deny "$tool requires NativeCode Pro."
  max=$max_free
fi

# One registry entry per live AI session; the pid is stable because the next step is exec.
mkdir -p "$REG" 2>/dev/null
n=0
for e in "$REG"/*; do
  [ -f "$e" ] || continue
  if { tr '\0' ' ' < "/proc/${e##*/}/cmdline"; } 2>/dev/null | grep -qF -- "$(cat "$e")"; then n=$((n + 1)); else rm -f "$e"; fi
done
[ "$n" -lt "$max" ] || deny "AI session limit ($max) reached."
echo "$tool" > "$REG/$$" 2>/dev/null
exec "$real" "$@"
