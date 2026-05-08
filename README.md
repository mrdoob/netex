# Netex (Android)

A minimal Android browser with a built-in devtools panel. Drag up the
bottom bar to see the page's source (formatted, syntax-highlighted) and
a live network log (fetch + XHR + images + HTML, with inline image and
glTF previews).

A three.js revision detector is currently bundled in — when a page uses
three.js, the detected revision shows as a small badge on the logo in
the URL bar. The detector is hardcoded for now; the planned direction is
to support Chrome extensions and let `three.js DevTools` (or any other
extension) drop in instead.

## Build & install

```sh
./gradlew :app:installDebug
```

You'll need the Android SDK with platform 35 installed and a
`local.properties` pointing to it (`sdk.dir=/path/to/Android/sdk`).

## Debug from desktop Chrome

With the device on USB and the app open:

1. Desktop Chrome → `chrome://inspect/#devices` → **Inspect** under the WebView.
2. Logcat: `adb logcat -s WebViewConsole:D`.

## Three.js detector internals

`bridge.js` and `constants.js` (vendored from the
[three.js DevTools](https://github.com/mrdoob/threejs-devtools) Chrome
extension, in `app/src/main/assets/threejs-devtools/`) are injected at
`document_start` via `WebViewCompat.addDocumentStartJavaScript`. When
three.js boots it sees `window.__THREE_DEVTOOLS__` and dispatches a
`register` event with its revision. A small relay
(`assets/threejs-devtools/android-relay.js`) replaces the Chrome
extension's `content-script.js` and forwards the event to native code via
`WebViewCompat.addWebMessageListener`.
