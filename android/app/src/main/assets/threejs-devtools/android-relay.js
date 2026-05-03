// Forwards three-devtools window.postMessage events to the native bridge.
(function () {
  function send(payload) {
    try {
      // WebMessageListener path (preferred): payload must be a string.
      if (typeof AndroidThreeDevtools !== 'undefined' && AndroidThreeDevtools.postMessage) {
        AndroidThreeDevtools.postMessage(payload);
      }
    } catch (e) {
      // Swallow — native side will be re-registered on next page load.
    }
  }

  window.addEventListener('message', function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || data.id !== 'three-devtools') return;
    send(JSON.stringify({ name: data.name, detail: data.detail }));
  });
})();
