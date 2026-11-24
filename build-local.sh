#!/usr/bin/env bash
# Сборка на ARM64-машине, где aapt2 из Maven собран под x86_64.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew assembleRelease \
  -Pandroid.aapt2FromMavenOverride=/root/workspace/_tools/bin/aapt2-override/aapt2 \
  "$@"
