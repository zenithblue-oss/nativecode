#!/bin/bash
# setup_xfce4.sh
# Complete standalone setup for XFCE4 Desktop Environment & FluxLinux GUI theme.
# Run inside Debian guest as root (PRoot or Chroot).

CUSTOM_USER="flux"
CUSTOM_GROUP="$(id -gn "$CUSTOM_USER" 2>/dev/null || echo users)"
USER_HOME="/home/$CUSTOM_USER"
THEME_DIR="/usr/share/themes"

handle_error() {
    echo ""
    echo "❌ FluxLinux Error: XFCE4 setup failed at: $1"
    exit 1
}

echo "FluxLinux: Starting XFCE4 Desktop Setup..."

# Prebuilt-rootfs helper. GUI phase is configuration-only when baked.
NC_COMMON="/usr/local/lib/nativecode/rootfs-common.sh"
if [ -f "$NC_COMMON" ]; then
    # shellcheck source=/dev/null
    . "$NC_COMMON"
else
    NC_RELEASE_FILE="${NC_RELEASE_FILE:-/etc/nativecode/rootfs-release}"
    nc_component_baked() { [ -n "${1:-}" ] && [ -f "$NC_RELEASE_FILE" ] && grep -q "^COMPONENT_$1=1" "$NC_RELEASE_FILE" 2>/dev/null; }
    nc_is_proot() { [ "${NATIVECODE_ISOLATION:-}" = "proot" ] && return 0; [ -n "${PROOT_TMP_DIR:-}" ] && return 0; return 1; }
    nc_runtime_home_repair() {
        _u="${1:-flux}"; _h="/home/$_u"; [ -d "$_h" ] || return 0
        if nc_is_proot; then chmod 755 "$_h" 2>/dev/null || true; chmod -R u+rwX "$_h/.config" 2>/dev/null || true
        else chown -R "$_u:$_u" "$_h" 2>/dev/null || chown -R "$_u" "$_h" 2>/dev/null || true; fi
    }
fi
if nc_component_baked GUI 2>/dev/null; then
    echo "NativeCode: GUI already baked — configuration only (no apk, no download)"
    PREBUILT_GUI=1
else
    PREBUILT_GUI=0
fi

if [ "${PREBUILT_GUI:-0}" = "1" ]; then
    command -v startxfce4 >/dev/null 2>&1 || handle_error "startxfce4 missing in prebuilt rootfs"
elif command -v apk >/dev/null 2>&1; then
    echo "FluxLinux: Installing XFCE4 packages via apk..."
    apk update || handle_error "APK Update"
    apk add --no-cache xfce4 xfce4-terminal dbus tigervnc font-dejavu wget unzip curl || handle_error "Package Installation"
else
    export DEBIAN_FRONTEND=noninteractive
    apt update -y || handle_error "APT Update"

    # 1. Install XFCE4 and Desktop Packages
    echo "FluxLinux: Installing XFCE4 packages..."
    apt install -y xfce4 xfce4-goodies xfce4-terminal dbus-x11 tigervnc-standalone-server wget unzip curl || handle_error "Package Installation"
fi

# 2. Configure VNC xstartup
mkdir -p "$USER_HOME/.vnc"
cat << 'EOF' > "$USER_HOME/.vnc/xstartup"
#!/bin/bash
export PULSE_SERVER=127.0.0.1
xrdb $HOME/.Xresources 2>/dev/null
startxfce4
EOF
chmod +x "$USER_HOME/.vnc/xstartup"
if [ "${PREBUILT_GUI:-0}" = "1" ]; then
    nc_runtime_home_repair "$CUSTOM_USER" 2>/dev/null || chmod 755 "$USER_HOME" 2>/dev/null || true
else
    chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.vnc"
fi

# 3. Deploy FluxLinux Themes
ASSET_REPO="zenithblue-oss/fluxlinux"
ASSET_TAG="debian-v1"
BASE_URL="https://github.com/$ASSET_REPO/releases/download/$ASSET_TAG"

extract_all_assets() {
    local URL="$1"
    local TARGET_DIR="$2"
    local TEMP_ZIP="/tmp/$(basename "$URL")"

    echo " - Downloading $(basename "$URL")..."
    wget -q "$URL" -O "$TEMP_ZIP" || return 0

    echo " - Extracting to $TARGET_DIR..."
    unzip -q -o "$TEMP_ZIP" -d "$TARGET_DIR" 2>/dev/null || true
    rm -f "$TEMP_ZIP"

    find "$TARGET_DIR" -maxdepth 1 -name "*.tar.xz" -exec tar -xf {} -C "$TARGET_DIR" 2>/dev/null \;
    find "$TARGET_DIR" -maxdepth 1 -name "*.tar.gz" -exec tar -xzf {} -C "$TARGET_DIR" 2>/dev/null \;
    rm -f "$TARGET_DIR"/*.tar.xz "$TARGET_DIR"/*.tar.gz 2>/dev/null || true
}

echo "FluxLinux: Downloading XFCE themes..."
mkdir -p "$THEME_DIR"
if [ "${PREBUILT_GUI:-0}" = "1" ]; then
    echo "NativeCode: themes baked — skipping network download (validating)"
    [ -d "$THEME_DIR/Space-transparency" ] || [ -d "$THEME_DIR/Space-light" ] || \
        echo "NativeCode: WARN: baked theme missing, keeping XML-only config"
else
extract_all_assets "$BASE_URL/theme.zip" "$THEME_DIR"
fi

SEL_THEME="Space-transparency"
if [ "$FLUX_THEME" = "light" ]; then
    SEL_THEME="Space-light"
fi

# 4. Apply Settings for User 'flux'
echo "FluxLinux: Applying XFCE4 Settings..."
XFCONF_DIR="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
mkdir -p "$XFCONF_DIR"

cat <<EOF > "$XFCONF_DIR/xsettings.xml"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xsettings" version="1.0">
  <property name="Net" type="empty">
    <property name="ThemeName" type="string" value="$SEL_THEME"/>
    <property name="EnableEventSounds" type="bool" value="false"/>
    <property name="EnableInputFeedbackSounds" type="bool" value="false"/>
  </property>
  <property name="Gtk" type="empty">
    <property name="FontName" type="string" value="JetBrainsMono Nerd Font 10"/>
    <property name="MonospaceFontName" type="string" value="JetBrainsMono Nerd Font Mono 10"/>
    <property name="DecorationLayout" type="string" value="menu:minimize,maximize,close"/>
  </property>
  <property name="Gdk" type="empty">
    <property name="WindowScalingFactor" type="int" value="2"/>
  </property>
  <property name="Xft" type="empty">
    <property name="Antialias" type="int" value="1"/>
    <property name="HintStyle" type="string" value="hintslight"/>
    <property name="RGBA" type="string" value="rgb"/>
  </property>
</channel>
EOF

cat <<'EOF' > "$XFCONF_DIR/xfwm4.xml"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="box_resize" type="bool" value="true"/>
    <property name="box_move" type="bool" value="true"/>
    <property name="button_layout" type="string" value="O|HMC"/>
    <property name="title_font" type="string" value="JetBrainsMono Nerd Font Bold 10"/>
    <property name="title_alignment" type="string" value="center"/>
    <property name="use_compositing" type="bool" value="false"/>
    <property name="workspace_count" type="int" value="1"/>
  </property>
</channel>
EOF

cat <<EOF > "$XFCONF_DIR/xfce4-desktop.xml"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="backdrop" type="empty">
    <property name="screen0" type="empty">
      <property name="monitor0" type="empty">
        <property name="workspace0" type="empty">
          <property name="color-style" type="int" value="0"/>
          <property name="rgba1" type="array">
            <value type="double" value="0.08"/>
            <value type="double" value="0.08"/>
            <value type="double" value="0.08"/>
            <value type="double" value="1.0"/>
          </property>
        </property>
      </property>
    </property>
  </property>
  <property name="desktop-icons" type="empty">
    <property name="file-icons" type="empty">
      <property name="show-home" type="bool" value="false"/>
      <property name="show-filesystem" type="bool" value="false"/>
      <property name="show-trash" type="bool" value="false"/>
      <property name="show-removable" type="bool" value="false"/>
    </property>
  </property>
</channel>
EOF

# 5. Keyboard Shortcuts
cat <<'SHORTCUTEOF' > "$XFCONF_DIR/xfce4-keyboard-shortcuts.xml"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-keyboard-shortcuts" version="1.0">
  <property name="commands" type="empty">
    <property name="default" type="empty">
      <property name="&lt;Alt&gt;F1" type="empty"/>
      <property name="&lt;Alt&gt;F2" type="empty"/>
      <property name="&lt;Alt&gt;F3" type="empty"/>
    </property>
    <property name="custom" type="empty">
      <property name="&lt;Primary&gt;&lt;Alt&gt;t" type="string" value="xfce4-terminal"/>
      <property name="&lt;Primary&gt;w" type="string" value="xfce4-appfinder"/>
      <property name="&lt;Primary&gt;q" type="string" value="xfce4-session-logout"/>
      <property name="&lt;Alt&gt;F2" type="string" value="xfce4-appfinder --collapsed"/>
      <property name="&lt;Super&gt;r" type="string" value="xfce4-appfinder -c"/>
      <property name="&lt;Alt&gt;F3" type="string" value="xfce4-appfinder"/>
      <property name="&lt;Alt&gt;F1" type="string" value="xfce4-popup-applicationsmenu"/>
      <property name="&lt;Primary&gt;&lt;Shift&gt;Escape" type="string" value="xfce4-taskmanager"/>
    </property>
  </property>
</channel>
SHORTCUTEOF

# 6. Panel Configuration
PANEL_CONFIG_DIR="$USER_HOME/.config/xfce4/xfconf/xfce-perchannel-xml"
cat <<'EOF' > "$PANEL_CONFIG_DIR/xfce4-panel.xml"
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-panel" version="1.0">
  <property name="configver" type="int" value="2"/>
  <property name="panels" type="array">
    <value type="int" value="1"/>
  </property>
  <property name="panel-1" type="empty">
    <property name="position" type="string" value="p=6;x=0;y=0"/>
    <property name="length" type="uint" value="100"/>
    <property name="position-locked" type="bool" value="true"/>
    <property name="size" type="uint" value="32"/>
    <property name="plugin-ids" type="array">
      <value type="int" value="1"/>
      <value type="int" value="2"/>
      <value type="int" value="3"/>
      <value type="int" value="4"/>
      <value type="int" value="5"/>
    </property>
  </property>
  <property name="plugins" type="empty">
    <property name="plugin-1" type="string" value="applicationsmenu"/>
    <property name="plugin-2" type="string" value="tasklist"/>
    <property name="plugin-3" type="string" value="separator">
      <property name="expand" type="bool" value="true"/>
      <property name="style" type="uint" value="0"/>
    </property>
    <property name="plugin-4" type="string" value="systray"/>
    <property name="plugin-5" type="string" value="clock"/>
  </property>
</channel>
EOF

# 7. Terminal Config
TERM_CONFIG_DIR="$USER_HOME/.config/xfce4/terminal"
mkdir -p "$TERM_CONFIG_DIR"
cat <<EOF > "$TERM_CONFIG_DIR/terminalrc"
[Configuration]
FontUseSystem=TRUE
FontName=JetBrainsMono Nerd Font 12
MiscAlwaysShowTabs=FALSE
MiscBell=FALSE
MiscBordersDefault=TRUE
MiscCursorBlinks=FALSE
MiscCursorShape=TERMINAL_CURSOR_SHAPE_IBEAM
MiscDefaultGeometry=80x24
MiscInheritGeometry=FALSE
MiscMenubarDefault=FALSE
MiscMouseAutohide=FALSE
MiscToolbarDefault=FALSE
MiscConfirmClose=TRUE
MiscCycleTabs=TRUE
MiscTabCloseButtons=TRUE
MiscTabCloseMiddleClick=TRUE
MiscTabPosition=TERMINAL_TAB_POSITION_TOP
MiscHighlightUrls=TRUE
MiscScrollAlternateScreen=TRUE
ScrollingLines=1000
BackgroundMode=TERMINAL_BACKGROUND_TRANSPARENT
BackgroundDarkness=0.7
EOF

if [ "${PREBUILT_GUI:-0}" = "1" ]; then
    nc_runtime_home_repair "$CUSTOM_USER" 2>/dev/null || true
else
    chown -R "$CUSTOM_USER:$CUSTOM_GROUP" "$USER_HOME/.config"
fi

# 8. Reload XFCE Daemons if active
if pgrep -x xfce4-session >/dev/null 2>&1; then
    echo "FluxLinux: Reloading XFCE daemons..."
    su -s /bin/bash - "$CUSTOM_USER" -c "killall -9 xfdesktop xfwm4 xfsettingsd" 2>/dev/null || true
    sleep 1
    su -s /bin/bash - "$CUSTOM_USER" -c "DISPLAY=:0 nohup xfdesktop > /dev/null 2>&1 &" 2>/dev/null || true
    su -s /bin/bash - "$CUSTOM_USER" -c "DISPLAY=:0 nohup xfwm4 --replace > /dev/null 2>&1 &" 2>/dev/null || true
    su -s /bin/bash - "$CUSTOM_USER" -c "DISPLAY=:0 nohup xfsettingsd > /dev/null 2>&1 &" 2>/dev/null || true
fi

echo "FluxLinux: XFCE4 Desktop Setup Complete!"
exit 0
