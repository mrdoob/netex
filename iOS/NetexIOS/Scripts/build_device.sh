#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

: "${DEVELOPMENT_TEAM:?Set DEVELOPMENT_TEAM to your Apple development team id.}"
: "${PRODUCT_BUNDLE_IDENTIFIER:?Set PRODUCT_BUNDLE_IDENTIFIER to a bundle id owned by your team.}"
: "${DEVICE_UDID:?Set DEVICE_UDID to the Xcode destination device id.}"

COREDEVICE_ID="${COREDEVICE_ID:-}"
NETEX_DERIVED_DATA="${NETEX_DERIVED_DATA:-$REPO_ROOT/artifacts/DeviceDerivedData-$STAMP}"
NETEX_RECEIPT_DIR="${NETEX_RECEIPT_DIR:-$REPO_ROOT/artifacts}"
BUILD_LOG="$NETEX_RECEIPT_DIR/netex-ios-device-build-$STAMP.log"
INSTALL_LOG="$NETEX_RECEIPT_DIR/netex-ios-device-install-$STAMP.log"
LAUNCH_LOG="$NETEX_RECEIPT_DIR/netex-ios-device-launch-$STAMP.log"
APP_PATH="$NETEX_DERIVED_DATA/Build/Products/Debug-iphoneos/NetexIOS.app"

mkdir -p "$NETEX_RECEIPT_DIR"

cd "$PROJECT_DIR"
xcodegen generate

set +e
COPYFILE_DISABLE=1 xcodebuild \
  -project NetexIOS.xcodeproj \
  -scheme NetexIOS \
  -configuration Debug \
  -destination "id=$DEVICE_UDID" \
  -derivedDataPath "$NETEX_DERIVED_DATA" \
  -allowProvisioningUpdates \
  -allowProvisioningDeviceRegistration \
  build \
  DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
  PRODUCT_BUNDLE_IDENTIFIER="$PRODUCT_BUNDLE_IDENTIFIER" \
  2>&1 | tee "$BUILD_LOG"
BUILD_STATUS=${PIPESTATUS[0]}
set -e

if [[ $BUILD_STATUS -ne 0 ]]; then
  if [[ -d "$APP_PATH" ]] && grep -q "resource fork, Finder information, or similar detritus not allowed" "$BUILD_LOG"; then
    SIGN_IDENTITY="$(sed -n 's/.*--sign \([A-Fa-f0-9]\{40\}\).*/\1/p' "$BUILD_LOG" | tail -1)"
    ENTITLEMENTS="$NETEX_DERIVED_DATA/Build/Intermediates.noindex/NetexIOS.build/Debug-iphoneos/NetexIOS.build/NetexIOS.app.xcent"
    if [[ -z "$SIGN_IDENTITY" || ! -f "$ENTITLEMENTS" ]]; then
      echo "Could not locate signing identity or entitlements after codesign metadata failure." >&2
      exit "$BUILD_STATUS"
    fi
    xattr -cr "$APP_PATH"
    /usr/bin/codesign --force --sign "$SIGN_IDENTITY" --entitlements "$ENTITLEMENTS" --timestamp=none --generate-entitlement-der "$APP_PATH"
  else
    exit "$BUILD_STATUS"
  fi
fi

/usr/bin/codesign --verify --deep --strict --verbose=2 "$APP_PATH"

if [[ -n "$COREDEVICE_ID" ]]; then
  xcrun devicectl device install app --device "$COREDEVICE_ID" "$APP_PATH" 2>&1 | tee "$INSTALL_LOG"
  set +e
  xcrun devicectl device process launch --device "$COREDEVICE_ID" "$PRODUCT_BUNDLE_IDENTIFIER" 2>&1 | tee "$LAUNCH_LOG"
  LAUNCH_STATUS=${PIPESTATUS[0]}
  set -e
  if [[ $LAUNCH_STATUS -ne 0 ]]; then
    echo "Launch failed after install. The app is installed; see $LAUNCH_LOG." >&2
  fi
fi

cat <<EOF
Netex iOS device build complete.
App: $APP_PATH
Build log: $BUILD_LOG
Install log: $INSTALL_LOG
Launch log: $LAUNCH_LOG
EOF
