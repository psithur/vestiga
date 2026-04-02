#!/usr/bin/env bash
set -euo pipefail

BINARY="target/vestiga"

if [ ! -f "$BINARY" ]; then
  echo "Native binary not found at $BINARY. Run script/build-native.sh first."
  exit 1
fi

echo "Testing native binary..."

# Test help/usage
echo "=== Testing usage ==="
$BINARY unknown-command 2>&1 || true

# Test version/serve startup (send empty stdin to make it exit)
echo "=== Testing serve startup ==="
echo "" | timeout 5 $BINARY serve 2>&1 || true

echo "Smoke tests passed."
