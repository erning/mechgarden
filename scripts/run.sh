#!/usr/bin/env sh
# Launch the Robocode UI.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
INSTALL_DIR="$ROOT/robocode"

if [ ! -x "$INSTALL_DIR/robocode.sh" ]; then
	echo "✗ Robocode is not installed. Run 'just robocode install' first." >&2
	exit 1
fi
cd "$INSTALL_DIR" && ./robocode.sh
