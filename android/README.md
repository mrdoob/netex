# three.js browser (Android)

Minimal Android browser that detects which three.js revision a page is using and shows it as a chip in the URL bar.

## How it works

`bridge.js` and `constants.js` from `../devtools/` are bundled as assets and injected at `document_start` via `WebViewCompat.addDocumentStartJavaScript`. When three.js boots, it sees `window.__THREE_DEVTOOLS__` and dispatches a `register` event with its revision. A small relay (`assets/threejs-devtools/android-relay.js`) replaces the Chrome extension's `content-script.js` and forwards the event to native code via `WebViewCompat.addWebMessageListener`.

The two files copied from `../devtools/` are regenerated on every build by the `copyThreeDevtoolsAssets` Gradle task — keep editing `../devtools/{bridge,constants}.js` as the source of truth.

## Bootstrapping

This project ships without the Gradle wrapper jar (it's a binary). Generate it once:

- **Easiest**: open this folder in Android Studio. It will sync the project, install SDK components, and add the wrapper jar automatically.
- **CLI**: if you have a system Gradle (`brew install gradle`), run `gradle wrapper --gradle-version 8.10.2` from this directory once. After that `./gradlew` works.

You'll also need:

- Android SDK with platform 35 installed (Android Studio handles this).
- A `local.properties` with `sdk.dir=/path/to/Android/sdk` (Android Studio writes this on first sync).

## Build & install

```sh
./gradlew assembleDebug
./gradlew :app:installDebug
```

## Smoke test

Launch the app on a phone connected via USB:

1. `https://threejs.org/examples/` → chip shows current revision (e.g. `r167`) within ~1s.
2. Tap into an example (SPA nav) → chip persists.
3. `https://google.com` → chip disappears.
4. `https://playground.babylonjs.com/` → chip stays hidden (no false positive).
5. `https://aframe.io/` → chip shows the bundled three.js revision.

## Debug from desktop Chrome

With the device on USB and the app open:

1. Desktop Chrome → `chrome://inspect/#devices` → click **Inspect** under your WebView.
2. Console:
   - `typeof window.__THREE_DEVTOOLS__` should print `"object"` (confirms pre-page-script injection).
   - `window.__THREE_DEVTOOLS__.dispatchEvent(new CustomEvent('register', { detail: { revision: '999' } }))` → chip should update to `r999`.
3. Logcat: `adb logcat -s ThreeDevtoolsBridge:V WebViewConsole:D`.

## File map

```
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  app/
    build.gradle.kts                                    # incl. copyThreeDevtoolsAssets
    src/main/
      AndroidManifest.xml
      java/com/threejs/browser/
        MainActivity.kt                                 # WebView + URL bar + chip + bridge wiring
        ThreeDevtoolsBridge.kt                          # WebMessageListener (and Fallback for old WebView)
        DetectorScripts.kt                              # loads + concatenates the 3 JS assets
        DetectionState.kt                               # StateFlow holder
      res/layout/activity_main.xml
      res/values/{strings,themes}.xml
      assets/threejs-devtools/
        constants.js                                    # build-copied from ../devtools/
        bridge.js                                       # build-copied from ../devtools/
        android-relay.js                                # the only fresh JS — replaces content-script.js
```
