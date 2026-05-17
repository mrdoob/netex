# Netex iOS Third-Party Notices

The iOS target bundles local copies of panel dependencies so the app shell and inspector panels can load without a first-run CDN dependency.

## Bundled Vendor Assets

| Asset | Source | Version Checked | License |
| --- | --- | --- | --- |
| `highlight.min.js`, `github.min.css`, `github-dark.min.css` | https://github.com/highlightjs/highlight.js | 11.11.1 | BSD-3-Clause |
| `beautify.min.js`, `beautify-css.min.js`, `beautify-html.min.js` | https://github.com/beautifier/js-beautify | 1.15.4 | MIT |
| `model-viewer.min.js` | https://github.com/google/model-viewer | 4.2.0 | Apache-2.0 |

`model-viewer.min.js` retains its embedded `@license` comments for transitive bundled code, including Apache-2.0, BSD-3-Clause, and MIT notices.

## Three.js DevTools

`Resources/NetexAssets/threejs-devtools/` is vendored from the Three.js DevTools extension surface used by Netex. Keep this directory isolated from app-specific iOS edits where possible. iOS-specific routing belongs in the surrounding chrome shims and Swift extension router.

## Project License

Netex itself is MIT licensed. Keep the root `LICENSE` file with forks, builds, and pull requests.
