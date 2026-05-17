# Netex Browser

A minimal Android browser with a built-in devtools panel. Drag up the
bottom bar to open it — four tabs:

- **Console** — live page logs plus a JS prompt that evaluates in
  the page's global scope.
- **Source** — formatted page HTML.
- **Network** — fetch/XHR log with inline image / glTF previews.
- **Three.js** — the vendored
  [Three.js DevTools](https://github.com/mrdoob/three.js/tree/dev/devtools)
  Chrome extension running in-process via a minimum `chrome.*` shim.
  Its background script drives the revision badge on the logo.

## Install

[Download the latest APK](https://github.com/mrdoob/netex/releases) and
sideload it. You may need to allow "install unknown apps" for the
browser or file manager you opened the file from.

## Dev Log

https://x.com/mrdoob/status/2051185470344953969

## Build from source

```sh
./gradlew :app:installDebug
```

You'll need the Android SDK with platform 35 installed and a
`local.properties` pointing to it (`sdk.dir=/path/to/Android/sdk`).
