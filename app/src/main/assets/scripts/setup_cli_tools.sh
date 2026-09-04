#!/usr/bin/env bash
# NativeCode AI CLI provisioning
# Adapted for Alpine Linux (musl + apk)

set -euo pipefail

DISTRO_ID="alpine-musl-apk"
FLUX_USER="${FLUX_USER:-flux}"
NODE_MAJOR="${NODE_MAJOR:-24}"
NVM_VERSION="${NVM_VERSION:-v0.40.5}"
MARKER="flux-cli-tools"
BENCHMARK_FILE="${BENCHMARK_FILE:-/tmp/nativecode-ai-cli-benchmark.tsv}"

SCRIPT_START="$(date +%s)"
PKG_SECONDS=0
NODE_SECONDS=0
AI_SECONDS=0
FINALIZED=0

fmt_seconds() {
    local s="${1:-0}"
    printf '%02d:%02d:%02d' $((s/3600)) $(((s%3600)/60)) $((s%60))
}

finish_benchmark() {
    local rc=$?
    [ "$FINALIZED" -eq 1 ] && return "$rc"
    FINALIZED=1
    local end total status now
    end="$(date +%s)"
    total=$((end - SCRIPT_START))
    if [ "$rc" -eq 0 ]; then status="PASS"; else status="FAIL($rc)"; fi
    now="$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date)"
    echo ""
    echo "================================================================"
    echo " NativeCode AI CLI benchmark"
    echo " distro        : $DISTRO_ID"
    echo " package phase : ${PKG_SECONDS}s ($(fmt_seconds "$PKG_SECONDS"))"
    echo " node phase    : ${NODE_SECONDS}s ($(fmt_seconds "$NODE_SECONDS"))"
    echo " AI tools phase: ${AI_SECONDS}s ($(fmt_seconds "$AI_SECONDS"))"
    echo " TOTAL         : ${total}s ($(fmt_seconds "$total"))"
    echo " result        : $status"
    echo "================================================================"
    if [ ! -f "$BENCHMARK_FILE" ]; then
        printf 'timestamp_utc\tdistro\tpackage_seconds\tnode_seconds\tai_seconds\ttotal_seconds\tstatus\n' \
            >> "$BENCHMARK_FILE" 2>/dev/null || true
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$now" "$DISTRO_ID" "$PKG_SECONDS" "$NODE_SECONDS" "$AI_SECONDS" "$total" "$status" \
        >> "$BENCHMARK_FILE" 2>/dev/null || true
    echo " benchmark TSV : $BENCHMARK_FILE"
    return "$rc"
}
trap finish_benchmark EXIT

if [ "${NC_SKIP_AI_CLI:-0}" = "1" ]; then
    echo ">>> NC_SKIP_AI_CLI=1 — skipping AI CLI tools install"
    exit 0
fi

# Flux terminal convenience: normally interactive user is flux
if [ "$(id -u)" != "0" ]; then
    if command -v sudo >/dev/null 2>&1; then
        echo ">>> Re-launching through sudo from Flux terminal..."
        exec sudo -E \
            FLUX_USER="${FLUX_USER:-$(id -un)}" \
            NODE_MAJOR="${NODE_MAJOR:-24}" \
            NVM_VERSION="${NVM_VERSION:-v0.40.5}" \
            BENCHMARK_FILE="${BENCHMARK_FILE:-/tmp/nativecode-ai-cli-benchmark.tsv}" \
            NC_SKIP_AI_CLI="${NC_SKIP_AI_CLI:-0}" \
            ALPINE_NODE_MODE="${ALPINE_NODE_MODE:-system}" \
            bash "$0" "$@"
    fi
    echo "ERROR: this script needs root for system packages and sudo is unavailable."
    exit 1
fi

if ! id "$FLUX_USER" >/dev/null 2>&1; then
    echo "ERROR: user '$FLUX_USER' missing — run the Alpine distro family setup first."
    exit 1
fi

FLUX_HOME="$(getent passwd "$FLUX_USER" 2>/dev/null | cut -d: -f6)"
if [ -z "$FLUX_HOME" ]; then
    FLUX_HOME="$(eval echo "~$FLUX_USER" 2>/dev/null || true)"
fi
if [ -z "$FLUX_HOME" ] || [ ! -d "$FLUX_HOME" ]; then
    echo "ERROR: home for $FLUX_USER not found."
    exit 1
fi

echo ">>> NativeCode AI CLI provisioning"
echo ">>> distro=$DISTRO_ID user=$FLUX_USER home=$FLUX_HOME"

# Distro package manager
pkg_ok() {
    apk info -e "$1" >/dev/null 2>&1
}

ensure_pkgs() {
    local need=() p
    for p in "$@"; do
        pkg_ok "$p" || need+=("$p")
    done
    if [ "${#need[@]}" -eq 0 ]; then
        echo ">>> APK packages already present"
        return 0
    fi
    echo ">>> apk add: ${need[*]}"
    apk add --no-cache "${need[@]}"
}

as_flux() {
    su -s /bin/bash - "$FLUX_USER" -c "$1"
}

# Prebuilt fast path: binaries baked — verify + PATH repair only, seconds.
NC_COMMON="/usr/local/lib/nativecode/rootfs-common.sh"
if [ -f "$NC_COMMON" ]; then
    # shellcheck source=/dev/null
    . "$NC_COMMON" 2>/dev/null || true
else
    NC_RELEASE_FILE="${NC_RELEASE_FILE:-/etc/nativecode/rootfs-release}"
    nc_component_baked() { [ -n "${1:-}" ] && [ -f "$NC_RELEASE_FILE" ] && grep -q "^COMPONENT_$1=1" "$NC_RELEASE_FILE" 2>/dev/null; }
fi
if nc_component_baked H 2>/dev/null; then
    echo "NativeCode: H already baked — verification/PATH repair only (no installs)"
    PREBUILT_H=1
else
    PREBUILT_H=0
fi

flux_has() {
    local cmd="$1"
    as_flux "
        export PATH=\"\$HOME/.local/bin:\$HOME/.grok/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"
        export NVM_DIR=\"\$HOME/.nvm\"
        if [ -s \"\$NVM_DIR/nvm.sh\" ]; then
            . \"\$NVM_DIR/nvm.sh\" >/dev/null 2>&1 || true
        fi
        if [ -d \"\$NVM_DIR/versions/node\" ]; then
            _n=\$(ls -1d \"\$NVM_DIR/versions/node\"/v* 2>/dev/null | sort -V | tail -1)
            [ -n \"\$_n\" ] && PATH=\"\$_n/bin:\$PATH\"
        fi
        command -v '$cmd' >/dev/null 2>&1 || exit 1
        if [ '$cmd' = 'opencode' ]; then
            opencode --version >/dev/null 2>&1
        fi
    "
}

OK_LIST=()
SKIP_LIST=()
FAIL_LIST=()
record_ok()   { OK_LIST+=("$1"); }
record_skip() { SKIP_LIST+=("$1"); }
record_fail() { FAIL_LIST+=("$1"); }

# System dependencies.
# PREBUILT_H placement note: detection runs after the benchmark EXIT trap,
# root/sudo normalization, and flux/home discovery above, and managed PATH
# repair below always runs. Only the apk/package phase is skipped when baked.
_phase="$(date +%s)"
if ! command -v apk >/dev/null 2>&1; then
    echo "ERROR: apk not found; this is not a configured Alpine guest."
    exit 1
fi

if [ "${PREBUILT_H:-0}" = "1" ]; then
    echo ">>> NativeCode: system packages baked — skipping apk package phase"
    echo ">>> Alpine node mode: system (baked)"
    PKG_SECONDS=0
else
ensure_pkgs bash curl wget git ca-certificates python3 build-base unzip xz coreutils gcompat libstdc++

ALPINE_NODE_MODE="${ALPINE_NODE_MODE:-system}"
case "$ALPINE_NODE_MODE" in
    system)
        ensure_pkgs nodejs npm
        ;;
    nvm-source)
        ensure_pkgs linux-headers perl
        ;;
    *)
        echo "ERROR: ALPINE_NODE_MODE must be 'system' or 'nvm-source'"
        exit 2
        ;;
esac
echo ">>> Alpine node mode: $ALPINE_NODE_MODE"
PKG_SECONDS=$(( $(date +%s) - _phase ))
fi

# PATH wiring
ENV_DIR="$FLUX_HOME/.config/fluxlinux"
ENV_FILE="$ENV_DIR/cli-tools.env"
mkdir -p "$ENV_DIR"

cat > "$ENV_FILE" <<'ENVEOF'
# Managed by NativeCode setup_cli_tools (prebuilt rootfs: Node 24 system)
export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"
export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"

if [ -d "$NVM_DIR/versions/node" ]; then
    _flux_node=$(ls -1d "$NVM_DIR/versions/node"/v* 2>/dev/null | sort -V | tail -1)
    if [ -n "$_flux_node" ] && [ -d "$_flux_node/bin" ]; then
        export PATH="$_flux_node/bin:$PATH"
    fi
    unset _flux_node
fi

if [ -s "$NVM_DIR/nvm.sh" ]; then
    . "$NVM_DIR/nvm.sh"
fi
if [ -s "$NVM_DIR/bash_completion" ] && [ -n "${BASH_VERSION:-}" ]; then
    . "$NVM_DIR/bash_completion"
fi

if [ -f "$HOME/.config/fluxlinux/cli-auth.env" ]; then
    . "$HOME/.config/fluxlinux/cli-auth.env"
fi
ENVEOF

chown -R "$FLUX_USER:$FLUX_USER" "$ENV_DIR" 2>/dev/null || chown -R "$FLUX_USER" "$ENV_DIR" 2>/dev/null || true
chmod 644 "$ENV_FILE"

ensure_source_line() {
    local rc="$1"
    local label="$2"
    touch "$rc"
    chown "$FLUX_USER:$FLUX_USER" "$rc" 2>/dev/null || chown "$FLUX_USER" "$rc" 2>/dev/null || true

    if grep -qF "# $MARKER" "$rc" 2>/dev/null; then
        local tmp
        tmp="$(mktemp)"
        awk -v m="# $MARKER" '
            $0 == m {skip=1; next}
            $0 == m"-end" {skip=0; next}
            !skip {print}
        ' "$rc" > "$tmp" || cp "$rc" "$tmp"
        mv "$tmp" "$rc"
    fi

    cat >> "$rc" <<RCEOF

# $MARKER
# $label — AI CLI tools PATH + Node/NVM
if [ -f "\$HOME/.config/fluxlinux/cli-tools.env" ]; then
    . "\$HOME/.config/fluxlinux/cli-tools.env"
fi
# $MARKER-end
RCEOF
    chown "$FLUX_USER:$FLUX_USER" "$rc" 2>/dev/null || chown "$FLUX_USER" "$rc" 2>/dev/null || true
}

echo ">>> wiring PATH into shell startup files..."
if [ -x /bin/zsh ] || [ -x /usr/bin/zsh ]; then
    ensure_source_line "$FLUX_HOME/.zshenv" "zshenv"
    ensure_source_line "$FLUX_HOME/.zprofile" "zprofile"
    ensure_source_line "$FLUX_HOME/.zshrc" "zshrc"
fi
ensure_source_line "$FLUX_HOME/.bashrc" "bashrc"
ensure_source_line "$FLUX_HOME/.profile" "profile"

mkdir -p /etc/profile.d
cat > /etc/profile.d/flux-cli-tools.sh <<'PROFILE'
if [ -n "${HOME:-}" ] && [ -f "$HOME/.config/fluxlinux/cli-tools.env" ]; then
    . "$HOME/.config/fluxlinux/cli-tools.env"
fi
PROFILE
chmod 644 /etc/profile.d/flux-cli-tools.sh

# Node setup (Alpine production policy: system Node 24 via apk; nvm-source legacy opt-in only)
_phase="$(date +%s)"
if [ "${PREBUILT_H:-0}" = "1" ]; then
    echo ">>> NativeCode: Node baked — verifying system node"
    if as_flux 'command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1'; then
        ACTIVE_NODE="$(as_flux 'node -v 2>/dev/null' || true)"
        record_ok "node@${ACTIVE_NODE:-system}"
    else
        echo "WARN: baked Node missing"
        record_fail "node@system"
    fi
elif [ "$ALPINE_NODE_MODE" = "system" ]; then
    if as_flux '
        set -e
        export PATH="$HOME/.local/bin:$PATH"
        npm config set prefix "$HOME/.local"
        echo ">>> Alpine system Node $(node -v) / npm $(npm -v)"
    '; then
        ACTIVE_NODE="$(as_flux 'node -v 2>/dev/null' || true)"
        record_ok "node@${ACTIVE_NODE:-system}"
        if ! printf '%s' "$ACTIVE_NODE" | grep -qE "^v${NODE_MAJOR}\."; then
            echo "WARN: Alpine system Node is $ACTIVE_NODE, not requested Node major $NODE_MAJOR."
        fi
    else
        record_fail "node@system"
    fi
else
    echo "WARN: ALPINE_NODE_MODE=nvm-source is legacy (slow source build, not used in prebuilt rootfs)"
    if as_flux "
        set -e
        export NVM_DIR=\"\$HOME/.nvm\"
        if [ ! -s \"\$NVM_DIR/nvm.sh\" ]; then
            echo '>>> Installing NVM ${NVM_VERSION}...'
            curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/${NVM_VERSION}/install.sh | bash
        fi
        . \"\$NVM_DIR/nvm.sh\"
        if command -v node >/dev/null 2>&1 && node -v | grep -qE '^v${NODE_MAJOR}\.'; then
            echo \">>> Node \$(node -v) already active\"
        else
            echo '>>> Alpine/musl: building Node ${NODE_MAJOR} from source (strict parity)...'
            nvm install -s ${NODE_MAJOR}
        fi
        nvm alias default ${NODE_MAJOR}
        nvm use ${NODE_MAJOR}
        echo \">>> Node \$(node -v) / npm \$(npm -v)\"
    "; then
        record_ok "node@${NODE_MAJOR}-source"
    else
        record_fail "node@${NODE_MAJOR}-source"
        echo "ERROR: Alpine Node source build failed"
    fi
fi
NODE_SECONDS=$(( $(date +%s) - _phase ))

# npm global tools
npm_global_install() {
    local pkg="$1"
    local bin="$2"

    if flux_has "$bin"; then
        echo ">>> skip $pkg ($bin already on PATH)"
        record_skip "$bin"
        return 0
    fi

    if [ "$bin" = "opencode" ]; then
        echo ">>> repairing existing opencode postinstall if present..."
        if as_flux '
            export PATH="$HOME/.local/bin:$PATH"
            export NVM_DIR="$HOME/.nvm"
            [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" >/dev/null 2>&1 || true
            _root=$(npm root -g 2>/dev/null)/opencode-ai
            if [ -f "$_root/postinstall.mjs" ]; then
                cd "$_root" && node postinstall.mjs
            else
                exit 1
            fi
        '; then
            if flux_has "$bin"; then
                record_ok "$bin"
                return 0
            fi
        fi
    fi

    echo ">>> npm install -g $pkg..."
    if as_flux "
        set -e
        export PATH=\"\$HOME/.local/bin:\$PATH\"
        export NVM_DIR=\"\$HOME/.nvm\"
        if [ -s \"\$NVM_DIR/nvm.sh\" ]; then
            . \"\$NVM_DIR/nvm.sh\"
        else
            npm config set prefix \"\$HOME/.local\"
        fi
        npm install -g '$pkg' 2>&1 || \
            npm install -g '$pkg' --ignore-scripts 2>&1
    "; then
        if flux_has "$bin"; then
            record_ok "$bin"
        else
            echo "WARN: $pkg installed but '$bin' not found/working"
            record_fail "$bin"
        fi
    else
        echo "WARN: npm install failed: $pkg"
        record_fail "$bin"
    fi
}

_phase="$(date +%s)"

# Grok repair: installer may place binary at $HOME/.grok/bin/grok without PATH.
as_flux 'if [ -x "$HOME/.grok/bin/grok" ] && ! command -v grok >/dev/null 2>&1; then mkdir -p "$HOME/.local/bin"; ln -sfn "$HOME/.grok/bin/grok" "$HOME/.local/bin/grok" 2>/dev/null || true; fi' || true

if [ "${PREBUILT_H:-0}" = "1" ]; then
    echo ">>> NativeCode: baked AI CLIs — verifying only (no npm/curl installs, no self-update)"
    for _b in opencode codex qwen agy claude grok kiro-cli; do
        if flux_has "$_b"; then record_ok "$_b"; else echo "WARN: baked $_b missing"; record_fail "$_b"; fi
    done
elif flux_has node && flux_has npm; then
    npm_global_install "opencode-ai" "opencode"
    npm_global_install "@openai/codex" "codex"
    npm_global_install "@qwen-code/qwen-code" "qwen"
else
    echo "ERROR: Node/npm unavailable; skipping npm-based AI tools"
    record_fail "opencode"
    record_fail "codex"
    record_fail "qwen"
fi

if [ "${PREBUILT_H:-0}" = "1" ]; then
    echo ">>> NativeCode: baked image — skipping curl-install fallback (verify-only)"
else
if ! flux_has opencode; then
    echo ">>> fallback: opencode.ai native installer..."
    if as_flux 'curl -fsSL https://opencode.ai/install | bash 2>&1'; then
        flux_has opencode && record_ok "opencode" || record_fail "opencode"
    else
        record_fail "opencode"
    fi
fi
fi

curl_install() {
    local name="$1"
    local bin="$2"
    local url="$3"

    if flux_has "$bin"; then
        echo ">>> skip $name ($bin already on PATH)"
        record_skip "$bin"
        return 0
    fi

    echo ">>> Installing $name..."
    if as_flux "curl -fsSL '$url' | bash 2>&1"; then
        if flux_has "$bin"; then
            record_ok "$bin"
        else
            echo "WARN: $name install finished but '$bin' not on PATH"
            record_fail "$bin"
        fi
    else
        echo "WARN: $name install failed"
        record_fail "$bin"
    fi
}

if [ "${PREBUILT_H:-0}" = "1" ]; then
    echo ">>> NativeCode: baked image — skipping curl installs (verify-only)"
else
curl_install "agy (Antigravity)" "agy" "https://antigravity.google/cli/install.sh"
curl_install "claude-code" "claude" "https://claude.ai/install.sh"
curl_install "grok CLI" "grok" "https://x.ai/cli/install.sh"
curl_install "kiro CLI" "kiro-cli" "https://cli.kiro.dev/install"
fi

if ! flux_has kiro-cli && flux_has kiro; then
    as_flux 'mkdir -p "$HOME/.local/bin"; ln -sfn "$(command -v kiro)" "$HOME/.local/bin/kiro-cli" 2>/dev/null || true'
    flux_has kiro-cli && record_ok "kiro-cli" || true
fi

AI_SECONDS=$(( $(date +%s) - _phase ))

if [ -x /bin/zsh ] || [ -x /usr/bin/zsh ]; then
    ensure_source_line "$FLUX_HOME/.zshenv" "zshenv"
    ensure_source_line "$FLUX_HOME/.zprofile" "zprofile"
    ensure_source_line "$FLUX_HOME/.zshrc" "zshrc"
fi
ensure_source_line "$FLUX_HOME/.bashrc" "bashrc"
ensure_source_line "$FLUX_HOME/.profile" "profile"

echo ""
echo ">>> Verification (as $FLUX_USER):"
as_flux '
    export PATH="$HOME/.local/bin:$HOME/.grok/bin:$HOME/bin:$HOME/.cargo/bin:$PATH"
    export NVM_DIR="$HOME/.nvm"
    [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" >/dev/null 2>&1 || true
    if [ -d "$NVM_DIR/versions/node" ]; then
        _n=$(ls -1d "$NVM_DIR/versions/node"/v* 2>/dev/null | sort -V | tail -1)
        [ -n "$_n" ] && PATH="$_n/bin:$PATH"
    fi
    for c in node npm opencode codex qwen agy claude grok kiro-cli kiro; do
        if command -v "$c" >/dev/null 2>&1; then
            ver=$("$c" --version 2>/dev/null | head -1 || "$c" -v 2>/dev/null | head -1 || echo ok)
            printf "  OK  %-12s %s\n" "$c" "$ver"
        else
            printf "  --  %-12s not found\n" "$c"
        fi
    done
'

echo ""
echo ">>> Summary: ok=[${OK_LIST[*]-}] skip=[${SKIP_LIST[*]-}] fail=[${FAIL_LIST[*]-}]"
echo ">>> Shell env: $ENV_FILE"
echo ">>> AI CLI Tools Provisioning Complete"

# Corrupt-bake gate: a baked image missing tools is damaged — never silently
# fall through to runtime installation. Ship a new rootfs revision instead.
if [ "${PREBUILT_H:-0}" = "1" ] && [ "${#FAIL_LIST[@]}" -gt 0 ]; then
    echo "ERROR: NativeCode prebuilt image damaged/incompatible — missing: ${FAIL_LIST[*]}"
    echo "ERROR: refusing runtime install; ship a new rootfs revision."
    exit 1
fi

if ! flux_has node; then
    exit 1
fi
exit 0
