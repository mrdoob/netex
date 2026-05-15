# Netex Browser

A minimal Android browser with a built-in devtools panel. Drag up the
bottom bar to see the page's source and a live network log (fetch, XHR,
images, HTML, with inline image and glTF previews).

The vendored [Three.js DevTools](https://github.com/mrdoob/three.js/tree/dev/devtools)
Chrome extension runs in-process via a minimum `chrome.*` shim — its
background script drives the revision badge on the logo, and its panel
UI is mounted as a third tab (Source / Network / Three.js) in the
bottom panel.

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
