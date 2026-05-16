#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$PROJECT_DIR/../.." && pwd)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARTIFACT_DIR="${NETEX_PROFILE_DIR:-$REPO_DIR/../artifacts}"
SIM_ID="${NETEX_SIM_ID:-booted}"
DESTINATION="${NETEX_DESTINATION:-platform=iOS Simulator,name=iPhone 17 Pro}"
PROFILE_URL="${NETEX_PROFILE_URL:-netex-assets://bundle/NetexAssets/stress.html}"
RECEIPT="$ARTIFACT_DIR/netex-ios-profile-$STAMP.md"
SCREENSHOT="$ARTIFACT_DIR/netex-ios-profile-$STAMP.png"
BUILD_LOG="$ARTIFACT_DIR/netex-ios-build-$STAMP.log"
TEST_LOG="$ARTIFACT_DIR/netex-ios-test-$STAMP.log"
DEVICE_LOG="$ARTIFACT_DIR/netex-ios-device-log-$STAMP.log"

mkdir -p "$ARTIFACT_DIR"
ARTIFACT_DIR="$(cd "$ARTIFACT_DIR" && pwd)"
DERIVED_DATA_PATH="${NETEX_DERIVED_DATA_PATH:-$ARTIFACT_DIR/DerivedData-$STAMP}"
APP_BUNDLE="${NETEX_APP_BUNDLE:-$DERIVED_DATA_PATH/Build/Products/Debug-iphonesimulator/NetexIOS.app}"
cd "$PROJECT_DIR"

COMMIT="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
BRANCH="$(git -C "$REPO_DIR" branch --show-current)"
XCODE_VERSION="$(xcodebuild -version | tr '\n' ' ')"

xcodegen generate >/dev/null

BUILD_SECONDS=$(
  { /usr/bin/time -p xcodebuild build -project NetexIOS.xcodeproj -scheme NetexIOS -destination "$DESTINATION" -derivedDataPath "$DERIVED_DATA_PATH" CODE_SIGNING_ALLOWED=NO >"$BUILD_LOG"; } 2>&1 |
  awk '/^real / { print $2 }'
)

xcrun simctl bootstatus "$SIM_ID" -b >/dev/null
xcrun simctl install "$SIM_ID" "$APP_BUNDLE"
LAUNCH_OUTPUT="$(xcrun simctl launch --terminate-running-process "$SIM_ID" com.mrdoob.netex.ios --netex-reset --netex-url "$PROFILE_URL")"
sleep 2
xcrun simctl io "$SIM_ID" screenshot "$SCREENSHOT" >/dev/null
xcrun simctl spawn "$SIM_ID" log show --last 3m --style compact --predicate 'subsystem == "com.mrdoob.netex.ios"' >"$DEVICE_LOG" || true

TEST_SECONDS=""
if [[ "${NETEX_PROFILE_RUN_TESTS:-0}" == "1" ]]; then
  TEST_SECONDS=$(
    { /usr/bin/time -p xcodebuild test -project NetexIOS.xcodeproj -scheme NetexIOS -destination "$DESTINATION" -derivedDataPath "$DERIVED_DATA_PATH" CODE_SIGNING_ALLOWED=NO >"$TEST_LOG"; } 2>&1 |
    awk '/^real / { print $2 }'
  )
fi

cat >"$RECEIPT" <<EOF
# Netex iOS Simulator Profile Receipt

- Date UTC: $STAMP
- Branch: $BRANCH
- Commit: $COMMIT
- Xcode: $XCODE_VERSION
- Destination: $DESTINATION
- Simulator: $SIM_ID
- Derived data: $DERIVED_DATA_PATH
- Build seconds: ${BUILD_SECONDS:-unknown}
- Test seconds: ${TEST_SECONDS:-not run by this receipt}
- Launch output: \`$LAUNCH_OUTPUT\`
- Profile URL: $PROFILE_URL
- Screenshot: $SCREENSHOT
- Build log: $BUILD_LOG
- Test log: $TEST_LOG
- Device log: $DEVICE_LOG

## Acceptance Notes

- Local app shell launched from bundled \`netex-assets://\` content.
- Default profile URL emits 1,000 console rows and 3 local fetches for bridge stress coverage.
- Screenshot captured after launch reset.
- Native Netex log excerpt is in the device log when iOS exposes the subsystem entries.
- For full PR proof, run with \`NETEX_PROFILE_RUN_TESTS=1\` before attaching the receipt.
EOF

echo "$RECEIPT"
