#!/usr/bin/env bash
# Builds a single-file installer: install.sh with the app files appended.
#   tools/prepair/bundle.sh [output]   (default: build/swis-prepair-install.sh)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-build/swis-prepair-install.sh}"
mkdir -p "$(dirname "$OUT")"
{
  cat "$HERE/install.sh"
  printf 'exit 0\n__SWIS_PAYLOAD__\n'
  tar -C "$HERE" -cz procon.py swis_prepair.py swis_prepair_gui.py swis-prepair.svg app.swis.prepair.policy | base64
} > "$OUT"
chmod +x "$OUT"
echo "$OUT"
