// Minimum chrome.* shim for running the three.js DevTools panel UI inside the
// Three.js tab's WebView. Surface area is small: the panel only uses
//   chrome.runtime.connect / .getManifest
//   chrome.devtools.inspectedWindow.tabId
// Port traffic crosses to native, which routes it to the page WebView's
// background.js (the device's "service worker").
(function () {
  if (window.chrome && window.chrome.runtime && window.chrome.runtime.__netex) return;

  var TAB_ID = 1;
  var nextPortLocal = 1;
  var localPorts = new Map();

  function postNative(payload) {
    try {
      if (typeof AndroidExtensionPanel !== 'undefined' && AndroidExtensionPanel.postMessage) {
        AndroidExtensionPanel.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* native side may be re-attached */ }
  }

  function createEvent() {
    var listeners = [];
    return {
      addListener: function (fn) { if (listeners.indexOf(fn) === -1) listeners.push(fn); },
      removeListener: function (fn) {
        var i = listeners.indexOf(fn); if (i !== -1) listeners.splice(i, 1);
      },
      hasListener: function (fn) { return listeners.indexOf(fn) !== -1; },
      _fire: function () {
        var args = arguments;
        listeners.slice().forEach(function (fn) {
          try { fn.apply(null, args); } catch (e) { console.warn('chrome shim listener error', e); }
        });
      }
    };
  }

  function createPort(portId, name) {
    var onMessage = createEvent();
    var onDisconnect = createEvent();
    var disconnected = false;
    var port = {
      name: name,
      onMessage: onMessage,
      onDisconnect: onDisconnect,
      postMessage: function (msg) {
        if (disconnected) return;
        postNative({ type: 'port-message', portId: portId, message: msg });
      },
      disconnect: function () {
        if (disconnected) return;
        disconnected = true;
        localPorts.delete(portId);
        postNative({ type: 'port-disconnect', portId: portId });
        onDisconnect._fire(port);
      },
      _deliver: function (msg) { onMessage._fire(msg, port); },
      _remoteClosed: function () {
        if (disconnected) return;
        disconnected = true;
        localPorts.delete(portId);
        onDisconnect._fire(port);
      }
    };
    localPorts.set(portId, port);
    return port;
  }

  var runtime = {
    __netex: true,
    connect: function (opts) {
      var portId = 'panel-' + (nextPortLocal++);
      var name = (opts && opts.name) || '';
      var port = createPort(portId, name);
      postNative({ type: 'port-connect', portId: portId, name: name });
      return port;
    },
    getManifest: function () {
      return { name: 'Three.js DevTools', version: '1.15', manifest_version: 3 };
    },
    getURL: function (path) { return 'asset:///threejs-devtools/' + (path || ''); }
  };

  var devtools = {
    inspectedWindow: { tabId: TAB_ID },
    panels: {
      // The panel.html is already the body we mount — devtools.js isn't run on
      // this side, but expose create() as a no-op for completeness.
      create: function (_title, _icon, _path, cb) {
        if (typeof cb === 'function') setTimeout(cb, 0);
      }
    }
  };

  function nativeDispatch(envelope) {
    if (!envelope || typeof envelope !== 'object') return;
    switch (envelope.type) {
      case 'port-message': {
        var p = localPorts.get(envelope.portId);
        if (p) p._deliver(envelope.message);
        break;
      }
      case 'port-disconnect': {
        var pp = localPorts.get(envelope.portId);
        if (pp) pp._remoteClosed();
        break;
      }
    }
  }

  window.chrome = window.chrome || {};
  window.chrome.runtime = runtime;
  window.chrome.devtools = devtools;
  window.__netexExt = { dispatch: nativeDispatch };
})();
