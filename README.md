# Netex (Android)

Minimal Android browser that detects which three.js revision a page is using
and shows it as a badge on a logo in the URL bar. Includes a bottom drag-up
devtools panel with view-source and live network capture.

## How it works

`bridge.js` and `constants.js` (vendored from the
[three.js DevTools](https://github.com/mrdoob/threejs-devtools) Chrome
extension, in `app/src/main/assets/threejs-devtools/`) are injected at
`document_start` via `WebViewCompat.addDocumentStartJavaScript`. When
three.js boots it sees `window.__THREE_DEVTOOLS__` and dispatches a
`register` event with its revision. A small relay
(`assets/threejs-devtools/android-relay.js`) replaces the Chrome extension's
`content-script.js` and forwards the event to native code via
`WebViewCompat.addWebMessageListener`.

The same document-start hook also installs `assets/network-shim.js`, which
patches `fetch`/`XMLHttpRequest` and a `PerformanceObserver` so the network
panel sees fetch/XHR calls plus image and HTML resources.

## Build & install

```sh
./gradlew :app:installDebug
```

You'll need the Android SDK with platform 35 installed and a
`local.properties` pointing to it (`sdk.dir=/path/to/Android/sdk`).

## Smoke test

1. `https://threejs.org/examples/` → revision badge shows on the logo.
2. Tap into an example (SPA nav) → badge persists.
3. `https://google.com` → badge disappears.
4. `https://playground.babylonjs.com/` → badge stays hidden (no false positive).
5. `https://aframe.io/` → badge shows the bundled three.js revision.

## Debug from desktop Chrome

With the device on USB and the app open:

1. Desktop Chrome → `chrome://inspect/#devices` → **Inspect** under the WebView.
2. Console:
   - `typeof window.__THREE_DEVTOOLS__` should print `"object"`.
   - `window.__THREE_DEVTOOLS__.dispatchEvent(new CustomEvent('register', { detail: { revision: '999' } }))` → badge updates to `r999`.
3. Logcat: `adb logcat -s ThreeDevtoolsBridge:V WebViewConsole:D`.
