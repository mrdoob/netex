# Netex iOS

Experimental iOS port of Netex 0.3.0.

The Android app remains the upstream implementation. This sibling target keeps the same core idea on iPhone: a compact browser backed by `WKWebView`, with a lower developer panel for Console, Source, and Network.

## Status

- Browser URL/search bar.
- Reload control.
- Console panel with injected `console.*` forwarding.
- Source panel using `document.documentElement.outerHTML`.
- Network panel using injected `fetch` / `XMLHttpRequest` capture.
- Uses the upstream panel shell and scripts as bundled assets.

The Android release APK cannot install on iOS. This target exists so the project has a native iPhone path that can be built, signed, and installed through Xcode.

## Build

```sh
cd iOS/NetexIOS
xcodegen generate
xcodebuild test -project NetexIOS.xcodeproj -scheme NetexIOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

## Install On A Paired iPhone

For a local development install, pass your own Apple development team and a bundle id that belongs to that team:

```sh
cd iOS/NetexIOS
xcodebuild -project NetexIOS.xcodeproj -scheme NetexIOS -configuration Debug -destination 'generic/platform=iOS' -allowProvisioningUpdates build DEVELOPMENT_TEAM=<TEAM_ID> PRODUCT_BUNDLE_IDENTIFIER=<YOUR_BUNDLE_ID>
xcrun devicectl device install app --device <DEVICE_ID> ~/Library/Developer/Xcode/DerivedData/NetexIOS-*/Build/Products/Debug-iphoneos/NetexIOS.app
```

## License

Netex is MIT licensed. Keep the root `LICENSE` with forks, builds, and pull requests.
