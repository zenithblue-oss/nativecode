#!/bin/bash
# setup_customization_debian.sh
# Applies "FluxLinux" font, locales, and terminal/shell enhancements to Debian
# Works for both Chroot and Proot environments (run as root, switches to user 'flux')

CUSTOM_USER="flux"
CUSTOM_GROUP="$(id -gn "$CUSTOM_USER" 2>/dev/null || echo users)"
USER_HOME="/home/$CUSTOM_USER"

# Error Handler
handle_error() {
    echo ""
    echo "❌ FluxLinux Error: Script failed at step: $1"
    echo "---------------------------------------------------"
    echo "Please check the error message above for details."
    echo "---------------------------------------------------"
    [ -t 0 ] && read -t 5 -p "Press Enter to acknowledge error and exit..." || true
    exit 1
}

echo "FluxLinux: Starting Debian Shell & Font Customization..."

# 1. Install Dependencies
echo "FluxLinux: Installing customization tools..."
export DEBIAN_FRONTEND=noninteractive
apt update -y
apt install -y curl fastfetch wget unzip fontconfig locales || handle_error "Dependency Installation"

# Setup Locales for proper font rendering in ZSH/Terminal
echo "FluxLinux: Setting up locales..."
echo "en_US.UTF-8 UTF-8" > /etc/locale.gen
locale-gen
update-locale LANG=en_US.UTF-8

# 2. Install JetBrains Mono Nerd Font
# Using proper Debian font location: /usr/share/fonts/truetype/
FONT_DIR="/usr/share/fonts/truetype/jetbrains-mono-nerd"
FONT_INSTALLED=false

# Check if font already installed
if fc-list | grep -qi "JetBrainsMono Nerd"; then
    echo "FluxLinux: JetBrains Mono Nerd Font already installed."
    FONT_INSTALLED=true
fi

if [ "$FONT_INSTALLED" = false ]; then
    echo "FluxLinux: Installing JetBrains Mono Nerd Font..."
    
    # Create font directory
    mkdir -p "$FONT_DIR"
    
    # Download from official Nerd Fonts GitHub releases
    NERD_FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip"
    TEMP_ZIP="/tmp/JetBrainsMono.zip"
    BASE_URL="https://github.com/zenithblue-oss/fluxlinux/releases/download/debian-v1"
    
    echo " - Downloading JetBrains Mono Nerd Font..."
    wget -q --show-progress "$NERD_FONT_URL" -O "$TEMP_ZIP" || {
        echo "FluxLinux: Direct download failed, trying from release..."
        wget -q --show-progress "$BASE_URL/font.zip" -O "$TEMP_ZIP" || handle_error "Font Download"
    }
    
    # Extract only .ttf files (ignore nested folders, Windows-only formats)
    echo " - Extracting font files..."
    unzip -o -j "$TEMP_ZIP" "*.ttf" -d "$FONT_DIR" 2>/dev/null || \
    unzip -o "$TEMP_ZIP" -d "$FONT_DIR" 2>/dev/null
    
    # Clean up any non-font files that might have been extracted
    find "$FONT_DIR" -type f ! -name "*.ttf" ! -name "*.otf" -delete 2>/dev/null
    
    # Remove temp file
    rm -f "$TEMP_ZIP"
    
    # Set correct permissions
    chmod 644 "$FONT_DIR"/*.ttf 2>/dev/null
    chmod 644 "$FONT_DIR"/*.otf 2>/dev/null
    
    # Rebuild font cache (system-wide, verbose)
    echo " - Rebuilding font cache..."
    fc-cache -fv "$FONT_DIR"
    
    # Also rebuild user cache
    su -s /bin/bash - "$CUSTOM_USER" -c "fc-cache -f" 2>/dev/null
    
    # Verify installation
    if fc-list | grep -qi "JetBrainsMono Nerd"; then
        echo "FluxLinux: ✓ JetBrains Mono Nerd Font installed successfully!"
    else
        echo "FluxLinux: ⚠ Font may not be properly registered. Checking installed files..."
        ls -la "$FONT_DIR"
    fi
fi
# 4. Configure Terminal and Shell Enhancements
echo "FluxLinux: Configuring Zsh and Terminal..."

# Install zsh if not already installed
echo "FluxLinux: Installing zsh..."
apt-get install -y zsh 2>/dev/null

# Install Oh My Zsh for flux user
# Install Oh My Zsh for flux user
echo "FluxLinux: Installing Oh My Zsh..."

# 1. Check for corrupt installation (folder exists but missing core script)
if [ -d "$USER_HOME/.oh-my-zsh" ] && [ ! -f "$USER_HOME/.oh-my-zsh/oh-my-zsh.sh" ]; then
    echo "FluxLinux: Detected corrupt Oh My Zsh installation. Removing..."
    rm -rf "$USER_HOME/.oh-my-zsh"
fi

# 2. Install if missing
if [ ! -d "$USER_HOME/.oh-my-zsh" ]; then
    su -s /bin/bash - "$CUSTOM_USER" -c 'RUNZSH=no CHSH=no sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"' 2>/dev/null
fi

# Set ZSH_CUSTOM path
ZSH_CUSTOM="$USER_HOME/.oh-my-zsh/custom"

# Install zsh plugins
echo "FluxLinux: Installing Zsh plugins..."
su -s /bin/bash - "$CUSTOM_USER" -c "git clone https://github.com/zsh-users/zsh-autosuggestions '$ZSH_CUSTOM/plugins/zsh-autosuggestions'" 2>/dev/null
su -s /bin/bash - "$CUSTOM_USER" -c "git clone https://github.com/zsh-users/zsh-syntax-highlighting '$ZSH_CUSTOM/plugins/zsh-syntax-highlighting'" 2>/dev/null
su -s /bin/bash - "$CUSTOM_USER" -c "git clone --depth 1 https://github.com/marlonrichert/zsh-autocomplete.git '$ZSH_CUSTOM/plugins/zsh-autocomplete'" 2>/dev/null

# Install agnosterzak theme
echo "FluxLinux: Installing agnosterzak theme..."
su -s /bin/bash - "$CUSTOM_USER" -c "mkdir -p '$ZSH_CUSTOM/themes'"
su -s /bin/bash - "$CUSTOM_USER" -c "curl -fsSL https://raw.githubusercontent.com/zakaziko99/agnosterzak-ohmyzsh-theme/master/agnosterzak.zsh-theme -o '$ZSH_CUSTOM/themes/agnosterzak.zsh-theme'" 2>/dev/null

# Install pokemon-colorscripts
echo "FluxLinux: Installing pokemon-colorscripts..."
POKEMON_TEMP="/tmp/pokemon-colorscripts"
rm -rf "$POKEMON_TEMP"
if command -v git >/dev/null 2>&1; then
    if git clone --depth 1 https://gitlab.com/phoneybadger/pokemon-colorscripts.git "$POKEMON_TEMP" 2>/dev/null; then
        if cd "$POKEMON_TEMP" 2>/dev/null; then
            ./install.sh 2>/dev/null
            cd - > /dev/null
        fi
    fi
fi
rm -rf "$POKEMON_TEMP"

# Configure .zshrc
echo "FluxLinux: Configuring .zshrc..."
ZSHRC="$USER_HOME/.zshrc"

# Check if .zshrc is valid (loading oh-my-zsh)
# Write complete optimized .zshrc (performance fixes from screenshot)
# - Removed zsh-autocomplete (extremely slow on PRoot, 35s+ startup)
# - Backgrounded visuals with &! (async, don't block shell startup)
# - DISABLE_AUTO_UPDATE / DISABLE_UPDATE_PROMPT (no prompts on launch)
# - ZSH_DISABLE_COMPFIX (no compaudit, faster init)
echo "FluxLinux: Writing optimized .zshrc..."
cat > "$ZSHRC" << 'ZSHEOF'
# Defensive TERM fallback for chroot / bare PTY environments
export TERM="${TERM:-xterm-256color}"

# PATH setup - local bin, npm global modules
export PATH="$HOME/.local/bin:/opt/nodejs/bin:$PATH"

if [ -f "$HOME/.config/fluxlinux/cli-tools.env" ]; then
    . "$HOME/.config/fluxlinux/cli-tools.env"
fi

# Setup Locales
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# Fix XDG_RUNTIME_DIR (not set in PRoot/chroot — no systemd-logind)
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# Background visuals - don't block shell startup (guard against missing TERM)
# Skip when FLUX_QUIET_SHELL is set (AI tool launches need clean terminal)
if [ -z "$FLUX_QUIET_SHELL" ] && [ -n "$TERM" ] && [ -t 1 ]; then
    { fastfetch --config termux 2>/dev/null; pokemon-colorscripts --no-title -r 1,2,3 2>/dev/null } &!
fi

# oh-my-zsh optimizations
export ZSH="$HOME/.oh-my-zsh"
ZSH_THEME="agnosterzak"
DISABLE_UPDATE_PROMPT=true
DISABLE_AUTO_UPDATE=true
ZSH_DISABLE_COMPFIX=true

# Removed zsh-autocomplete (very slow), kept essential plugins
plugins=(git zsh-autosuggestions zsh-syntax-highlighting)

source $ZSH/oh-my-zsh.sh
ZSHEOF
chown "$CUSTOM_USER:$CUSTOM_GROUP" "$ZSHRC"

# Download fastfetch config
mkdir -p "$USER_HOME/.local/share/fastfetch/presets"
curl -fsSL https://raw.githubusercontent.com/zenithblue-oss/Linux_Setup/dev/config/termux.jsonc \
    -o "$USER_HOME/.local/share/fastfetch/presets/termux.jsonc" 2>/dev/null

# Fastfetch and pokemon are already included in the optimized .zshrc above (backgrounded)

# Keep /etc/passwd flux shell as Bash: proot-distro uses it for every command.
# NativeCode owns the optional human interactive preference separately.
mkdir -p "$USER_HOME/.config/fluxlinux"
printf '%s\n' /bin/bash > "$USER_HOME/.config/fluxlinux/interactive-shell"
chown "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config/fluxlinux/interactive-shell"

# Fix ownership
chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.oh-my-zsh" "$USER_HOME/.zshrc" "$USER_HOME/.local" 2>/dev/null

echo "FluxLinux: Terminal configuration complete!"
echo "FluxLinux: Customization Complete!"
echo "------------------------------------------------"
exit 0
