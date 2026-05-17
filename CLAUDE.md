# Netex Browser

A minimal Android browser with a built-in devtools panel. Drag up the
bottom bar to see Console / Source / Network / Three.js tabs:

- **Console** — live page logs plus a JS prompt that evaluates in
  the page's global scope.
- **Source** — formatted page HTML.
- **Network** — live fetch/XHR/Image log with inline previews.
- **Three.js** — vendored
  [Three.js DevTools](https://github.com/mrdoob/three.js/tree/dev/devtools)
  Chrome extension running in-process via a minimum `chrome.*` shim;
  its background script also drives the revision badge on the logo.

## Layout

```
app/src/main/
  java/com/mrdoob/browser/       # Kotlin sources
  assets/
    chrome-shim-page.js          # chrome.* shim for the page WebView
    chrome-shim-panel.js         # chrome.* shim for the Three.js panel WebView
    network-shim.js              # fetch/XHR/Image instrumentation
    orientation-shim.js          # screen.orientation.lock → native
    console-shim.js              # %c parsing + Chrome-style format + consoleEval
    panel-shell.html / panel.js  # Console + Source + Network tabs (custom)
    threejs-devtools/            # Vendored Three.js DevTools extension
      manifest.json, background.js, content-script.js, devtools.js,
      bridge.js, highlight.js, constants.js, index.html,
      panel/{panel.html, panel.css, panel.js}
  res/raw/tlds.txt               # IANA TLD list (URL vs search heuristic)
  res/layout/activity_main.xml   # Chrome bar + bottom panel
  res/values/styles.xml          # @style/PanelTab (shared tab styling)
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
scripts. The bundle is `constants.js` + `chrome-shim-page.js` +
`bridge.js` + `highlight.js` + `content-script.js` (wrapped in an IIFE
with a scoped `chrome`) + `background.js` (wrapped in an IIFE) +
`network-shim.js` + `orientation-shim.js` + `console-shim.js`.

**Three.js detection** runs the upstream extension's own pipeline:
`bridge.js` exposes `window.__THREE_DEVTOOLS__` and three.js dispatches
`register`/`observe` events. `content-script.js` forwards via
`chrome.runtime.sendMessage`; `background.js` calls
`chrome.action.setBadgeText` — both are real Chrome APIs faked by
`chrome-shim-page.js`. content-script gets its own scoped `chrome` (separate
`runtime.onMessage` pool) so its listeners don't observe its own
sendMessage calls — that self-loop is normally prevented by isolated worlds.

**Native bridges** all extend `JsonMessageBridge` and have a nested
`Fallback` `@JavascriptInterface` for WebViews too old for
`WEB_MESSAGE_LISTENER`. Envelope `type` strings are centralized in
`EnvelopeType.kt`:

- `ExtensionBridge` (page WebView, `AndroidExtension`) — handles
  `action.setBadgeText` / `setBadgeBackgroundColor` (→ `DetectionStore`),
  `orientation.lock` / `unlock` (→ `MainActivity.applyOrientationLock`),
  `console.entry` (styled `%c` logs → `ConsoleLog`), plus `page-ready`
  and page-side port traffic forwarded to the router.
- `PanelExtensionBridge` (Three.js panel WebView, `AndroidExtensionPanel`)
  — forwards panel `port-connect`/`port-message`/`port-disconnect` to the
  router.
- `NetworkBridge` — receives fetch/XHR records from `network-shim.js`,
  appends to `NetworkLog` (a `MutableSharedFlow`).
- `PanelBridge` — runs in the *panel* WebView; handles `fetch-blob` →
  asks `MainActivity` to invoke `__netex.findBlob(rid)` in the page
  WebView, which posts the data URL back through `NetworkBridge`. Also
  handles `eval` → runs `__netex.consoleEval(src)` on the page WebView,
  returns the `[ok, formatted]` JSON via `panel.deliverEvalResult`.

**`ExtensionRouter`** is the activity-scoped persistent piece — in Chrome
the MV3 service worker stays alive across navigations, but here
`background.js` dies with the page. The router caches each live panel
port's `MESSAGE_INIT` and replays connect+init against every fresh
`background.js` (top frame + each same-origin iframe via the page shim's
`dispatchAllFrames`). Sub-frame `page-ready` envelopes also dispatch a
synthetic `committed` to the panel — without it, switching between
threejs.org examples (which only swap the iframe) would pile up
renderers and scenes.

**The devtools panel** (`PanelRenderer.kt`) is a WebView at the
bottom, dragged up by the chrome bar. It's loaded with
`loadDataWithBaseURL("https://cdn.jsdelivr.net/", ...)` so highlight.js,
js-beautify, and `<model-viewer>` can be loaded from jsDelivr. Tabs:
**Console** (default — see below), **Source** (formatted page HTML),
**Network** (live request log), and **Three.js** (a second WebView
hosting the extension's vendored `panel/panel.html`, configured by
`setupThreeJsPanel`).

**Console tab**: `console-shim.js` wraps `console.{log,info,warn,error,debug}`.
For `%c` styled calls it parses the format string into `{text, style}`
segments and posts them via the `console.entry` envelope; plain logs
fall through to `orig.apply` so they reach `onConsoleMessage` with
source/line info preserved. The panel renders styled segments with
an allowlist-sanitized inline style (`url(...)`, `@import`,
`expression(...)`, `javascript:` blocked — a page can't escape the row
with position/size tricks). The prompt posts `eval` envelopes through
`PanelBridge`; native runs `window.__netex.consoleEval(src)` which uses
indirect `(0,eval)` for global scope and wraps a leading `{` in parens
so object literals don't parse as block statements.

**Network tab** upserts rows by `data-rid`: the shim emits a
`pending:true` start at fetch/XHR send, throttled progress for XHR
(~4/s), and a final record on `loadend`/`.then` — same row updates in
place so live byte counters tick instead of appearing only at the end.

**Blob handling is lazy**: `network-shim.js` keeps captured Blobs in
`window.__netex.blobs` (FIFO cap 64). Encoding to a data URL
(`FileReader.readAsDataURL`) only happens when the user expands a row in
the panel — eager encoding blocked the UI. The lookup walks iframes
recursively because `evaluateJavascript` only runs in the top frame.

**Three.js panel external links**: `target="_blank"` anchors (e.g.
the feedback `+`) go through `onCreateWindow`, which assigns a transient
WebView whose `shouldOverrideUrlLoading` fires an `ACTION_VIEW` and
destroys itself.

**`screen.orientation.lock()`**: shimmed in `orientation-shim.js`.
Stock WebView rejects the call; we forward to native, which only
applies the lock while fullscreen is active and unlocks on exit.

**Chrome bar**: edge-adjacent reload/three.js buttons opt out of
Android's back-gesture region via `View.systemGestureExclusionRects`
so edge taps reach them rather than triggering back-swipe.

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
- Every file under `app/src/main/assets/threejs-devtools/` is vendored
  from the Three.js DevTools Chrome extension — leave them byte-identical
  so they stay easy to refresh from upstream. Custom logic belongs in the
  `chrome-shim-*.js` files or `network-shim.js`.
- Don't commit unless explicitly asked.
- Periodic `/simplify` rounds are run after feature work — keep the diff
  small enough to review in one pass.
