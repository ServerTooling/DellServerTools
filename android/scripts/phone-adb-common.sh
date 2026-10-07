#!/usr/bin/env bash
# Shared helpers for installing the app on a phone over wireless adb (local network).
# Sourced by install-phone.sh. Not part of the Gradle build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The Gradle root (where ./gradlew and app/ live).
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

# adb: use $ADB_BIN or $ANDROID_HOME/platform-tools/adb, else fall back to adb on PATH.
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/android-sdk}}"
ADB_BIN="${ADB_BIN:-$ANDROID_HOME/platform-tools/adb}"
if [[ ! -x "$ADB_BIN" ]]; then
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
  fi
fi

ENV_DEVICE_ID="${DEVICE_ID:-}"
ENV_PAIR_DEVICE_ID="${PAIR_DEVICE_ID:-}"
ENV_PAIRING_CODE="${PAIRING_CODE:-}"
ENV_ANDROID_USER="${ANDROID_USER:-0}"

DEVICE_ID=""
PAIR_DEVICE_ID=""
PAIRING_CODE=""
ANDROID_USER="$ENV_ANDROID_USER"

if [[ -f "$ENV_FILE" ]]; then
  # Local, git-ignored defaults such as DEVICE_ID, so you don't retype them.
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

DEVICE_ID="${ENV_DEVICE_ID:-${DEVICE_ID:-}}"
PAIR_DEVICE_ID="${ENV_PAIR_DEVICE_ID:-${PAIR_DEVICE_ID:-}}"
PAIRING_CODE="${ENV_PAIRING_CODE:-${PAIRING_CODE:-}}"
ANDROID_USER="${ENV_ANDROID_USER:-${ANDROID_USER:-0}}"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "ERROR: adb not found. Set ANDROID_HOME (platform-tools/adb) or put adb on PATH." >&2
  exit 1
fi

adb_has_device() {
  "$ADB_BIN" devices | awk '$2 == "device" {print $1}' | grep -Fxq "$1"
}

adb_first_connected_device() {
  "$ADB_BIN" devices | awk '$2 == "device" {print $1; exit}'
}

adb_first_connected_secure_transport() {
  "$ADB_BIN" devices | awk '$2 == "device" && $1 ~ /_adb-tls-connect\._tcp$/ {print $1; exit}'
}

adb_shell() {
  "$ADB_BIN" -s "$DEVICE_ID" shell "$@"
}

prompt_for_pairing() {
  echo "Device $DEVICE_ID is not connected and may need wireless pairing."

  if [[ -z "$PAIR_DEVICE_ID" ]]; then
    read -r -p "Enter pairing DEVICE_ID (example 192.0.2.45:43619): " PAIR_DEVICE_ID
  fi

  if [[ -z "$PAIR_DEVICE_ID" ]]; then
    echo "ERROR: Pairing DEVICE_ID is required." >&2
    exit 1
  fi

  if [[ -z "$PAIRING_CODE" ]]; then
    read -r -p "Enter wifi pairing code: " PAIRING_CODE
  fi

  if [[ -z "$PAIRING_CODE" ]]; then
    echo "ERROR: Pairing code is required." >&2
    exit 1
  fi

  echo "Pairing with $PAIR_DEVICE_ID..."
  if ! printf '%s\n' "$PAIRING_CODE" | "$ADB_BIN" pair "$PAIR_DEVICE_ID"; then
    echo "ERROR: adb pair failed for $PAIR_DEVICE_ID" >&2
    exit 1
  fi

  PAIR_DEVICE_ID=""
  PAIRING_CODE=""
}

ensure_connected_device() {
  if [[ -z "$DEVICE_ID" ]]; then
    read -r -p "Enter DEVICE_ID (example 192.0.2.45:41565): " DEVICE_ID
  fi

  if [[ -z "$DEVICE_ID" ]]; then
    echo "ERROR: DEVICE_ID is required." >&2
    exit 1
  fi

  while true; do
    if adb_has_device "$DEVICE_ID"; then
      break
    fi

    CONNECTED_SECURE_TRANSPORT="$(adb_first_connected_secure_transport || true)"
    if [[ -n "$CONNECTED_SECURE_TRANSPORT" ]]; then
      echo "Using already-connected secure transport $CONNECTED_SECURE_TRANSPORT."
      DEVICE_ID="$CONNECTED_SECURE_TRANSPORT"
      break
    fi

    CONNECTED_DEVICE="$(adb_first_connected_device || true)"
    if [[ -n "$CONNECTED_DEVICE" ]]; then
      echo "Using already-connected adb device $CONNECTED_DEVICE."
      DEVICE_ID="$CONNECTED_DEVICE"
      break
    fi

    echo "Device $DEVICE_ID is not connected. Attempting adb connect..."
    if "$ADB_BIN" connect "$DEVICE_ID"; then
      sleep 1
    fi

    if adb_has_device "$DEVICE_ID"; then
      break
    fi

    prompt_for_pairing

    echo "Retrying adb connect to $DEVICE_ID..."
    if "$ADB_BIN" connect "$DEVICE_ID"; then
      sleep 1
    fi

    if adb_has_device "$DEVICE_ID"; then
      break
    fi

    echo "Device $DEVICE_ID is still not available in adb after pairing."
    echo "Open Developer options > Wireless debugging on the phone and confirm the current connect IP:port."
    read -r -p "Enter a new DEVICE_ID or press Enter to cancel: " NEXT_DEVICE_ID
    if [[ -z "$NEXT_DEVICE_ID" ]]; then
      echo "Cancelled." >&2
      exit 1
    fi
    DEVICE_ID="$NEXT_DEVICE_ID"
  done
}
