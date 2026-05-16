// Shims screen.orientation.lock/unlock. Stock WebView returns a rejected
// promise for lock() and never actually rotates the activity — pages like
// YouTube rely on this to switch to landscape on fullscreen, so we forward
// the request to native and let MainActivity set requestedOrientation.
// Native ignores locks issued while not in fullscreen; exitFullscreen also
// resets orientation, so unlock() on fullscreenchange is handled implicitly.
(function () {
  var orientation = window.screen && window.screen.orientation;
  if (!orientation) return;

  function post(payload) {
    try {
      if (typeof AndroidExtension !== 'undefined' && AndroidExtension.postMessage) {
        AndroidExtension.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* bridge re-attaches on next load */ }
  }

  try {
    Object.defineProperty(orientation, 'lock', {
      configurable: true,
      writable: true,
      value: function (type) {
        post({ type: 'orientation.lock', orientation: String(type || '') });
        return Promise.resolve();
      }
    });
    Object.defineProperty(orientation, 'unlock', {
      configurable: true,
      writable: true,
      value: function () {
        post({ type: 'orientation.unlock' });
      }
    });
  } catch (e) { /* read-only on some WebViews — best-effort only */ }
})();
