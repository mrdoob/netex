# Netex

A minimal Android browser with a built-in devtools panel. Drag up the
bottom bar to see the page's source and a live network log (fetch, XHR,
images, HTML, with inline image and glTF previews).

A three.js revision detector is currently bundled in — when a page uses
three.js, the detected revision shows as a small badge on the logo in
the URL bar. The detector is hardcoded for now; the planned direction is
to support Chrome extensions and let Three.js DevTools (or any other
extension) drop in instead.

## Install

Download [netex-0.1.0.apk](https://github.com/mrdoob/netex/releases/download/0.1.0/netex-0.1.0.apk)
and sideload it. You may need to allow "install unknown apps" for the
browser or file manager you opened the file from.

## Build from source

```sh
./gradlew :app:installDebug
```

You'll need the Android SDK with platform 35 installed and a
`local.properties` pointing to it (`sdk.dir=/path/to/Android/sdk`).
