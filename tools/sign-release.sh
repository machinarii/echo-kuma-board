#!/bin/bash
# Sign the Kuma Board release APK. Run this yourself, in your own terminal.
#
# - First run: creates the signing key at $KS (RSA 4096, valid ~27 years). keytool asks for the
#   passwords at hidden prompts; nothing is passed on the command line or written to a file here.
# - Every run: aligns and signs app/build/outputs/apk/release/app-release-unsigned.apk
#   (apksigner asks for the keystore password at a hidden prompt) and verifies the result.
#
# Keep the key: every future update must be signed with the same key, or the app has to be
# uninstalled first. Back up $KS and its password in your password manager.
#
# Usage: tools/sign-release.sh [path/to/keystore.jks]
set -euo pipefail

cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "No Android build-tools under $SDK; set ANDROID_HOME."; exit 1; }
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
KS="${1:-$HOME/.kuma-board/release.jks}"
ALIAS="kumaboard"
IN="app/build/outputs/apk/release/app-release-unsigned.apk"
OUT_DIR="dist"
OUT="$OUT_DIR/kuma-board-release.apk"

[ -f "$IN" ] || { echo "Missing $IN. Build it first: ./gradlew :app:assembleRelease"; exit 1; }

if [ ! -f "$KS" ]; then
  echo "Creating signing key $KS (you will be asked for a new password, twice)."
  mkdir -p "$(dirname "$KS")"
  chmod 700 "$(dirname "$KS")"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Kuma Board"
  chmod 600 "$KS"
fi

mkdir -p "$OUT_DIR"
ALIGNED="$(mktemp -t kuma-aligned).apk"
trap 'rm -f "$ALIGNED"' EXIT
"$BT/zipalign" -p -f 4 "$IN" "$ALIGNED"

echo "Signing (enter the keystore password)."
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" --out "$OUT" "$ALIGNED"
"$BT/apksigner" verify --verbose --print-certs "$OUT" | grep -E "Verified using|Signer #1 certificate (DN|SHA-256)"
echo
echo "Signed: $(pwd)/$OUT"
