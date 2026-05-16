(function () {
  var ns = window.__netex || (window.__netex = {});
  if (ns.perfInstalled) return;
  ns.perfInstalled = true;

  function post(name, extra) {
    try {
      window.webkit.messageHandlers.netex.postMessage({
        type: 'perf.mark',
        source: 'page',
        timestamp: performance.now(),
        payload: Object.assign({ name: name, url: location.href }, extra || {})
      });
    } catch (e) {}
  }

  post('document-start');
  window.addEventListener('DOMContentLoaded', function () { post('dom-content-loaded'); }, { once: true });
  window.addEventListener('load', function () { post('window-load'); }, { once: true });

  try {
    new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (entry) {
        if (entry.name === 'first-contentful-paint' || entry.name === 'first-paint') {
          post(entry.name, { startTime: entry.startTime });
        }
      });
    }).observe({ type: 'paint', buffered: true });
  } catch (e) {}
})();
