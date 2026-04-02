#!/usr/bin/env bash
set -euo pipefail

echo "Building vestiga native image..."

# Build uberjar first
clj -T:build uber

# Build native image
clj -T:build native

echo "Native binary at: target/vestiga"
echo "Size: $(du -h target/vestiga | cut -f1)"
