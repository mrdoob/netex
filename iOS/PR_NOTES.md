# Netex iOS PR Notes

## Motivation

This branch adds an iPhone-capable Netex target without changing the Android app. The iOS target keeps Netex's core idea intact: a compact browser with built-in Console, Source, Network, and Three.js inspection panels.

## Architecture

- The app is native iOS with `WKWebView` for page and panel surfaces.
- Panel, vendor, and Three.js DevTools files are bundled and served through `netex-assets://`, so first launch does not depend on CDN fetches.
- Page-to-native messages use a typed envelope (`type`, `id`, `source`, `payload`, `timestamp`) with bridge handlers for console, network, blobs, panel eval, perf marks, and Three.js DevTools routing.
- Console and Network messages are batched in JavaScript before crossing into Swift, then batched again before panel rendering.
- The Three.js tab uses vendored `threejs-devtools/` resources with page/panel chrome shims and native extension routing.

## Tests And Verification

- Unit coverage includes URL/search resolution, offline asset presence, shim mode parsing, script injection order, bridge envelope decoding, console/network batching, blob FIFO eviction, and extension port replay.
- UI coverage launches the local start page, switches Console/Source/Network/Three.js tabs, and verifies the Three.js panel surface exists.
- Simulator profiling can be reproduced with:

```sh
cd iOS/NetexIOS
NETEX_PROFILE_RUN_TESTS=1 Scripts/profile_netex_ios.sh
```

## Signing And Device Install

The committed project keeps upstream-neutral signing defaults. Local device installs should use command-line overrides:

```sh
xcodebuild -project NetexIOS.xcodeproj -scheme NetexIOS -configuration Debug -destination 'generic/platform=iOS' -allowProvisioningUpdates build DEVELOPMENT_TEAM=<TEAM_ID> PRODUCT_BUNDLE_IDENTIFIER=<YOUR_BUNDLE_ID>
xcrun devicectl device install app --device <DEVICE_ID> ~/Library/Developer/Xcode/DerivedData/NetexIOS-*/Build/Products/Debug-iphoneos/NetexIOS.app
```

Do not commit personal development teams, bundle IDs, or provisioning profiles.

## Third-Party Asset Licenses

- `highlight.js`: BSD-3-Clause, npm latest checked as `11.11.1`.
- `js-beautify`: MIT, npm latest checked as `1.15.4`.
- `@google/model-viewer`: Apache-2.0, npm latest checked as `4.2.0`.
- `threejs-devtools/` is kept as vendored DevTools code from the existing Netex/Three.js ecosystem and should remain isolated from app-specific edits where possible.

## Known Deltas

- Physical-device performance still depends on local Apple development provisioning.
- Android is intentionally untouched unless a shared JS bug is reproduced and tested on both platforms.
