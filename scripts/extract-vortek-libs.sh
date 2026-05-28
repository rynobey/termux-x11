#!/usr/bin/env bash
# Extract the closed Vortek libs from a Winlator APK into the right slots in this fork.
#
# Usage:
#   scripts/extract-vortek-libs.sh [/path/to/Winlator_11.0.apk]
#
# If no path is given, downloads Winlator 11.0 to /tmp.
#
# Outputs (gitignored — *.so + third_party/ are excluded):
#   app/src/main/jniLibs/arm64-v8a/libvortekrenderer.so   — the closed Vulkan server lib
#   app/src/main/jniLibs/arm64-v8a/libwinlator.so         — its NEEDED dep
#   third_party/vortek-2.1.tzst                            — the open client (for the proot)
#
# These are bruno's bundled binaries (closed-source). Do NOT redistribute. Each developer
# extracts them locally from a Winlator APK they obtained themselves.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-/tmp/Winlator_11.0.apk}"
WINLATOR_URL="https://github.com/brunodev85/winlator/releases/download/v11.0.0/Winlator_11.0.apk"

# Download if the APK isn't already at the given path. This is important in CI:
# the actions/cache step provides the path even on a cache miss, but doesn't put
# the file there — so we must always check existence and download if absent.
if [ ! -f "$APK" ]; then
    echo "==> APK not present at $APK; downloading Winlator 11.0…"
    mkdir -p "$(dirname "$APK")"
    curl -L --fail -o "$APK" "$WINLATOR_URL"
else
    echo "==> Reusing existing $APK"
fi

if [ ! -f "$APK" ]; then
    echo "ERROR: download failed, $APK still not present" >&2
    exit 1
fi

JNILIB_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
THIRD_PARTY_DIR="$REPO_ROOT/third_party"
mkdir -p "$JNILIB_DIR" "$THIRD_PARTY_DIR"

echo "==> Extracting closed server libs to $JNILIB_DIR"
( cd "$JNILIB_DIR" && unzip -o -j "$APK" "lib/arm64-v8a/libvortekrenderer.so" "lib/arm64-v8a/libwinlator.so" )

echo "==> Extracting open Vortek client archive to $THIRD_PARTY_DIR"
( cd "$THIRD_PARTY_DIR" && unzip -o -j "$APK" "assets/graphics_driver/vortek-2.1.tzst" )

echo ""
echo "Result:"
ls -lh "$JNILIB_DIR"/libvortekrenderer.so "$JNILIB_DIR"/libwinlator.so "$THIRD_PARTY_DIR"/vortek-2.1.tzst
echo ""
echo "Notes:"
echo " - libvortekrenderer.so + libwinlator.so will be packaged into the app's"
echo "   arm64-v8a APK split and loaded via System.loadLibrary(\"vortekrenderer\")."
echo " - vortek-2.1.tzst is the OPEN client (libvulkan_vortek.so + ICD json) that"
echo "   needs to be installed inside the proot Ubuntu, with VORTEK_SERVER_PATH"
echo "   recompiled to match the server's socket path (see VortekServer)."
