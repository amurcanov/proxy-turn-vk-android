#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for abi in arm64-v8a armeabi-v7a x86_64; do
  "$ROOT_DIR/scripts/build-go-lib.sh" "$abi"
done
