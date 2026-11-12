#!/usr/bin/env bash
# Installs SWIS Pre-pair on a Linux computer and opens it.
#
#   Single file (from the Build workflow):  bash swis-prepair-install.sh
#   From a clone of the repository:         ./tools/prepair/install.sh
#   Public mirror / raw URL:                curl -fsSL <raw URL of this file> | bash
#   Remove it again:                        add --uninstall to any of the above
#
# Needs sudo once. Installs BlueZ, Python's D-Bus and Tk modules and polkit with the
# system package manager, the helper into /opt/swis-prepair, and a menu entry.
set -euo pipefail

PREFIX=/opt/swis-prepair
REF="${SWIS_REF:-main}"
RAW="https://raw.githubusercontent.com/hakeperty/switch2-swis-app/${REF}/tools/prepair"
FILES=(procon.py swis_prepair.py swis_prepair_gui.py swis-prepair.svg app.swis.prepair.policy)
APPS=/usr/share/applications/swis-prepair.desktop
ICON=/usr/share/icons/hicolor/scalable/apps/swis-prepair.svg
POLICY=/usr/share/polkit-1/actions/app.swis.prepair.policy

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "This installer is for Linux."
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else
  command -v sudo >/dev/null || die "Needs sudo (or run as root)."
  SUDO="sudo"
fi

if [ "${1:-}" = "--uninstall" ]; then
  say "Removing SWIS Pre-pair"
  [ -x "$PREFIX/swis_prepair.py" ] && $SUDO "$PREFIX/swis_prepair.py" restore || true
  $SUDO rm -rf "$PREFIX" "$APPS" "$ICON" "$POLICY" /usr/local/bin/swis-prepair /usr/local/bin/swis-prepair-gui
  $SUDO rm -rf /var/lib/swis-prepair
  say "Removed."
  exit 0
fi

say "Installing packages"
if command -v apt-get >/dev/null; then
  $SUDO apt-get update -qq
  $SUDO apt-get install -y bluez python3 python3-dbus python3-tk
  $SUDO apt-get install -y pkexec 2>/dev/null || $SUDO apt-get install -y policykit-1
elif command -v dnf >/dev/null; then
  $SUDO dnf install -y bluez python3 python3-dbus python3-tkinter polkit
elif command -v pacman >/dev/null; then
  $SUDO pacman -S --needed --noconfirm bluez bluez-utils python python-dbus tk polkit
elif command -v zypper >/dev/null; then
  $SUDO zypper --non-interactive install bluez python3 python3-dbus-python python3-tk polkit
else
  die "Unknown package manager. Install BlueZ (with btmgmt), python3-dbus, python3-tk and polkit, then run this again."
fi
command -v btmgmt >/dev/null || die "btmgmt was not installed with BlueZ on this system. Install your distribution's BlueZ utilities package."

say "Installing the app into $PREFIX"
SELF="${BASH_SOURCE[0]:-}"
if [ -n "$SELF" ] && grep -q '^__SWIS_PAYLOAD__$' "$SELF"; then
  # Single-file installer built by bundle.sh: the app files are appended below.
  SRC="$(mktemp -d)"
  trap 'rm -rf "$SRC"' EXIT
  sed '1,/^__SWIS_PAYLOAD__$/d' "$SELF" | base64 -d | tar -xz -C "$SRC"
elif [ -n "$SELF" ] && [ -f "$(dirname "$SELF")/swis_prepair.py" ]; then
  SRC="$(cd "$(dirname "$SELF")" && pwd)"
else
  SRC="$(mktemp -d)"
  trap 'rm -rf "$SRC"' EXIT
  for f in "${FILES[@]}"; do
    curl -fsSL "$RAW/$f" -o "$SRC/$f" || die "Could not download $f from $RAW (set SWIS_REF to the branch that has it)."
  done
fi
$SUDO install -d "$PREFIX"
$SUDO install -m 644 "$SRC/procon.py" "$PREFIX/"
$SUDO install -m 755 "$SRC/swis_prepair.py" "$SRC/swis_prepair_gui.py" "$PREFIX/"
$SUDO install -D -m 644 "$SRC/swis-prepair.svg" "$ICON"
$SUDO install -D -m 644 "$SRC/app.swis.prepair.policy" "$POLICY"
$SUDO ln -sf "$PREFIX/swis_prepair.py" /usr/local/bin/swis-prepair
$SUDO ln -sf "$PREFIX/swis_prepair_gui.py" /usr/local/bin/swis-prepair-gui

$SUDO tee "$APPS" >/dev/null <<EOF
[Desktop Entry]
Type=Application
Name=SWIS Pre-pair
Comment=Make a Switch 2 remember your phone for SWIS Controller
Exec=$PREFIX/swis_prepair_gui.py
Icon=swis-prepair
Terminal=false
Categories=Game;Utility;
EOF
command -v update-desktop-database >/dev/null && $SUDO update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && $SUDO gtk-update-icon-cache -q /usr/share/icons/hicolor || true

say "Enabling Bluetooth"
$SUDO systemctl enable --now bluetooth >/dev/null 2>&1 || true
command -v rfkill >/dev/null && $SUDO rfkill unblock bluetooth || true

say "Checking the setup"
$SUDO "$PREFIX/swis_prepair.py" check || true

say "Installed. Find \"SWIS Pre-pair\" in your app menu."
if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  nohup "$PREFIX/swis_prepair_gui.py" >/dev/null 2>&1 &
  say "Opening it now."
fi
