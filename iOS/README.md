# Netex iOS

Experimental iOS port of Netex 0.3.0, built as an upstream-safe sibling to the Android app.

The Android app remains the upstream implementation. This target keeps the same core idea on iPhone: a compact browser backed by `WKWebView`, with a lower developer panel for Console, Source, Network, and Three.js.

## Status

- Local Netex start page instead of a remote default launch URL.
- Focused top chrome with Home, page title, Reload, and an Examples menu led by the classic animated city-block scene.
- Curated Three.js entry points for the examples gallery, animated model, and glTF loader, with custom URL entry kept in the advanced menu.
- Reload control and back/forward swipe gestures through `WKWebView`.
- Console panel with batched injected `console.*` forwarding.
- Source panel using `document.documentElement.outerHTML`, with local lazy-loaded beautifier/highlighter assets and an experimental live-session edit mode. Source edits apply by rewriting the current page document only; they are not persisted to bundled examples or upstream files, and the panel keeps a one-tap in-memory Revert checkpoint from the moment editing starts.
- Network panel using injected `fetch` / `XMLHttpRequest` capture, blob preview storage, and a single full-screen lazy model preview that uses `model-viewer`'s native one-finger orbit and two-finger pan.
- Three.js panel backed by vendored `threejs-devtools/` assets and iOS chrome-shim routing.
- Bundle-backed `netex-assets://` scheme for panel, vendor, and Three.js DevTools resources.
- Debug `WKWebView.isInspectable` support on iOS 16.4+.
- Native panel drag snap points and safe-area layout.
- Native signpost/log hooks for launch, navigation, panel readiness, perf marks, first console row, first network row, and Three.js readiness.

The Android release APK cannot install on iOS. This target exists so the project has a native iPhone path that can be built, signed, and installed through Xcode.

## Build

```sh
cd iOS/NetexIOS
xcodegen generate
xcodebuild test -project NetexIOS.xcodeproj -scheme NetexIOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

The test suite covers URL/search resolution, bundle asset loading, shim mode parsing, bridge envelope decoding, console/network batching, blob FIFO eviction, extension port replay, and UI smoke tests for the local start page, examples menu, hidden custom URL flow, inspector hide/show, and all panel tabs.

To install and launch the simulator build manually:

```sh
cd iOS/NetexIOS
xcodegen generate
xcodebuild build -project NetexIOS.xcodeproj -scheme NetexIOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro' CODE_SIGNING_ALLOWED=NO
xcrun simctl install booted ~/Library/Developer/Xcode/DerivedData/NetexIOS-*/Build/Products/Debug-iphonesimulator/NetexIOS.app
xcrun simctl launch booted com.mrdoob.netex.ios
```

## Debug Modes

Set `NETEX_SHIMS` in the scheme environment to narrow startup and bridge timing:

```sh
NETEX_SHIMS=off       # no injected shims
NETEX_SHIMS=console   # console bridge only
NETEX_SHIMS=network   # network bridge only
NETEX_SHIMS=full      # default full bridge and Three.js shims
```

Use this for A/B profiling when a page feels slow. In debug builds, iOS Web Inspector can also attach to the page and panel web views.

## Profiling

- Use Instruments or `os_signpost` views around the `NetexIOS` subsystem.
- Key signpost names include `app-view-did-load`, `navigation-start`, `wk-did-start`, `wk-did-finish`, `panel-ready`, `three-panel-ready`, `three-page-ready`, `first-console-row`, and `first-network-row`.
- The JS performance shim forwards browser `performance.mark` events into the same native log lane.
- The local start page should appear quickly because no CDN or remote Three.js example is loaded at app launch.

For a repeatable simulator receipt:

```sh
cd iOS/NetexIOS
NETEX_PROFILE_RUN_TESTS=1 Scripts/profile_netex_ios.sh
```

The script records branch, commit, Xcode version, destination, build time, optional test time, launch output, screenshot, and native log paths under the local Codex artifact folder by default. Use `NETEX_SIM_ID`, `NETEX_DESTINATION`, and `NETEX_PROFILE_DIR` to override the simulator and output location.

To launch the local stress harness manually:

```sh
xcrun simctl launch booted com.mrdoob.netex.ios --netex-reset --netex-url netex-assets://bundle/NetexAssets/stress.html
```

## Upstream PR Notes

Use `PR_NOTES.md` as the draft PR body and contribution checklist. It documents architecture, verification, signing posture, known deltas, and third-party asset license checks.

## Install On A Paired iPhone

For a local development install, pass your own Apple development team and a bundle id that belongs to that team:

```sh
cd iOS/NetexIOS
DEVELOPMENT_TEAM=<TEAM_ID> \
PRODUCT_BUNDLE_IDENTIFIER=<YOUR_BUNDLE_ID> \
DEVICE_UDID=<DEVICE_UDID> \
COREDEVICE_ID=<COREDEVICE_ID> \
Scripts/build_device.sh
```

Use `xcodebuild -showdestinations -project NetexIOS.xcodeproj -scheme NetexIOS` for the Xcode device UDID and `xcrun devicectl list devices` for the CoreDevice install/launch id. The helper keeps upstream signing defaults neutral, builds with command-line signing overrides, strips macOS bundle metadata if codesign rejects it, verifies the signed app, installs when `COREDEVICE_ID` is set, and writes receipts under `artifacts/`. Do not commit personal team IDs, provisioning profiles, or local bundle IDs.

For the underlying command-line shape without the helper, see `PR_NOTES.md`.

## Third-Party Notices

The iOS target bundles local inspector assets to avoid first-run CDN fetches. See `THIRD_PARTY_NOTICES.md` for bundled dependency sources, versions checked, and license notes.

## Known Deltas

- Physical-device performance must be validated with a working Apple development team and provisioning profile.
- The Three.js tab uses the vendored extension panel and shim routing. Treat changes under `Resources/NetexAssets/threejs-devtools/` as vendored unless an upstream Three.js DevTools fix is intentionally being made.
- Keep Android untouched unless a shared JS bug fix is proven and tested on both platforms.

## License

Netex is MIT licensed. Keep the root `LICENSE` with forks, builds, and pull requests.
