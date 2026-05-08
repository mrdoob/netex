# Netex (Android)

A minimal Android browser with a built-in devtools panel — drag up the
bottom bar to see the page's formatted source and a live network log
(fetch + XHR + images + HTML, with inline image / glTF previews).

A bundled three.js revision detector is *currently* shoehorned in — when
a page uses three.js, its revision shows as a badge on the logo in the
URL bar. This is a stopgap; the planned direction is to add Chrome
extension support so three.js DevTools (and others) can drop in instead
of being hardcoded.

## Layout

```
app/src/main/
  java/com/mrdoob/browser/       # Kotlin sources
  assets/                        # JS injected into the page WebView
    threejs-devtools/            # bridge.js + constants.js (vendored from
                                 # the three.js DevTools Chrome extension)
                                 # + android-relay.js (Android-specific)
  res/raw/tlds.txt               # IANA TLD list (URL vs search heuristic)
```

The repo IS the gradle project — `build.gradle.kts`, `settings.gradle.kts`,
`gradlew`, etc. live at the root.

## Build and install

We always install directly on a USB-connected phone via `adb`. There is no
emulator or CI.

```sh
./gradlew :app:installDebug
```

`JAVA_HOME` (openjdk@17) and `ANDROID_HOME` are exported in `~/.zshrc` but do
not propagate to non-interactive shells — when running gradle from a tool
shell, prefix with:

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Verify a device is attached with `adb devices` before installing.

## Architecture

**Page-world JS** is concatenated by `DocumentStartScripts.kt` and injected via
`WebViewCompat.addDocumentStartJavaScript` so it runs before any page
scripts. The bundle is `constants.js` + `bridge.js` + `android-relay.js` +
`network-shim.js`.

**Three.js detection** is a *cooperative protocol*, not a sniffer:
`bridge.js` exposes `window.__THREE_DEVTOOLS__` (an EventTarget) and three.js
itself dispatches `register`/`observe` events to it. The Android-specific
piece is just `android-relay.js` (replaces the Chrome extension's
`content-script.js`) — it forwards `window.postMessage` to a native
`WebMessageListener`. This whole path is a stopgap until extension
support lands; it should not grow new features.

**Native bridges** all extend `JsonMessageBridge` and have a nested
`Fallback` `@JavascriptInterface` for WebViews too old for
`WEB_MESSAGE_LISTENER`:

- `ThreeDevtoolsBridge` — receives `register` → `DetectionStore.setRevision`
  → updates the badge on the three.js logo in the URL bar.
- `NetworkBridge` — receives fetch/XHR records from `network-shim.js`,
  appends to `NetworkLog` (a `MutableSharedFlow`).
- `PanelBridge` — runs in the *panel* WebView; handles `fetch-blob` →
  asks `MainActivity` to invoke `__netex.findBlob(rid)` in the page
  WebView, which posts the data URL back through `NetworkBridge`.

**The devtools panel** (`PanelRenderer.kt`) is a separate WebView at the
bottom, dragged up by the chrome bar. It's loaded with
`loadDataWithBaseURL("https://cdn.jsdelivr.net/", ...)` so highlight.js,
js-beautify, and `<model-viewer>` can be loaded from jsDelivr. Tabs:
**Source** (formatted page HTML) and **Network** (live request log with
inline image / glTF previews).

**Blob handling is lazy**: `network-shim.js` keeps captured Blobs in
`window.__netex.blobs` (FIFO cap 64). Encoding to a data URL
(`FileReader.readAsDataURL`) only happens when the user expands a row in
the panel — eager encoding blocked the UI. The lookup walks iframes
recursively because `evaluateJavascript` only runs in the top frame.

**URL bar** uses the bundled IANA TLD list to decide URL vs search: input
with a known TLD is loaded as a URL (with `https://` prepended if needed),
anything else is sent to DuckDuckGo with the `!ducky` bang. `localhost` and
literal IPs are always treated as URLs.

**Intent filters**: registered as a handler for `VIEW` (http/https) and
`SEND` (text/plain) so links from other apps and the share sheet open here.
`launchMode="singleTop"` + `onNewIntent` reuses the running activity.

**Persistence**: `currentUrl` is written to activity-scoped
`SharedPreferences` in `onPause` and read back in `onCreate` when there's
no `savedInstanceState` and no incoming intent — so a cold relaunch
restores the last page. `onSaveInstanceState` still wins for config
changes (it restores full back/forward history via `WebView.saveState`).

## Conventions

- The user (mrdoob) prefers terse responses and small, focused diffs.
- `bridge.js` and `constants.js` under `app/src/main/assets/threejs-devtools/`
  are vendored from the three.js DevTools Chrome extension — leave them
  byte-identical so they stay easy to refresh from upstream. Custom logic
  belongs in `android-relay.js` or `network-shim.js` next to them.
- Don't commit unless explicitly asked.
- Periodic `/simplify` rounds are run after feature work — keep the diff
  small enough to review in one pass.
