#!/usr/bin/env bash
# Install the debug APK on a phone over wireless adb (local network).
#
#   cd android
#   ./gradlew assembleDebug
#   ./scripts/install-phone.sh
#
# Set DEVICE_ID (and for first pairing PAIR_DEVICE_ID / PAIRING_CODE) via the
# environment or a git-ignored android/.env (see scripts/.env.example).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/phone-adb-common.sh"

APK_PATH="${APK_PATH:-$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: APK not found at $APK_PATH" >&2
  echo "Build it first:  (cd \"$PROJECT_ROOT\" && ./gradlew assembleDebug)" >&2
  exit 1
fi

ensure_connected_device

echo "Installing $APK_PATH to $DEVICE_ID for Android user $ANDROID_USER"
"$ADB_BIN" -s "$DEVICE_ID" install --user "$ANDROID_USER" -r "$APK_PATH"
