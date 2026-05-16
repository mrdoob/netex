// Minimum chrome.* shim for running the three.js DevTools content + background
// scripts inside the page WebView.
//
// In real Chrome these two scripts run in isolated worlds with independent
// chrome.runtime instances; here they share one global, so we expose TWO
// runtime "scopes" (background = window.chrome; content-script = scoped via
// __netexExt.createContentScriptChrome) with separate onMessage pools to
// prevent a content-script ↔ chrome.runtime self-loop.
//
//   content-script.runtime.sendMessage(msg) → fires background's onMessage
//   background.runtime.sendMessage(msg)     → fires content-script's onMessage
//   background.tabs.sendMessage(tabId, msg) → fires content-script's onMessage
//
// Port traffic (panel ↔ background) and chrome.action.* cross to native.
(function () {
  if (window.chrome && window.chrome.runtime && window.chrome.runtime.__netex) return;

  var TAB_ID = 1;
  var EXT_ID = 'netex-threejs-devtools';

  function postNative(payload) {
    try {
      if (typeof AndroidExtension !== 'undefined' && AndroidExtension.postMessage) {
        AndroidExtension.postMessage(JSON.stringify(payload));
      }
    } catch (e) { /* native bridge re-attaches on next load */ }
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

  var remotePorts = new Map();
  var nextPortId = 1;

  function createRemotePort(portId, name) {
    // Re-issued port-connect (nav, new iframe) tears down the previous port so
    // bg.js's old closure cleanly releases its connections entry + listeners.
    var prev = remotePorts.get(portId);
    if (prev) prev._remoteClosed();
    var onMessage = createEvent();
    var onDisconnect = createEvent();
    var disconnected = false;
    var port = {
      name: name,
      sender: { tab: { id: TAB_ID } },
      onMessage: onMessage,
      onDisconnect: onDisconnect,
      postMessage: function (msg) {
        if (disconnected) return;
        postNative({ type: 'port-message', portId: portId, message: msg });
      },
      disconnect: function () {
        if (disconnected) return;
        disconnected = true;
        remotePorts.delete(portId);
        postNative({ type: 'port-disconnect', portId: portId });
        onDisconnect._fire(port);
      },
      _deliver: function (msg) { onMessage._fire(msg, port); },
      _remoteClosed: function () {
        if (disconnected) return;
        disconnected = true;
        remotePorts.delete(portId);
        onDisconnect._fire(port);
      }
    };
    remotePorts.set(portId, port);
    return port;
  }

  var bgOnMessage = createEvent();
  var csOnMessage = createEvent();
  var bgOnConnect = createEvent();
  var bgWebNavCommitted = createEvent();

  function fireBg(msg, sender) {
    bgOnMessage._fire(msg, sender, function () {});
  }

  function fireCs(msg, sender) {
    csOnMessage._fire(msg, sender, function () {});
  }

  var runtime = {
    __netex: true,
    id: EXT_ID,
    onMessage: bgOnMessage,
    onConnect: bgOnConnect,
    sendMessage: function (msg) {
      fireCs(msg, { id: EXT_ID });
      return Promise.resolve();
    },
    connect: function () {
      var portId = 'page-' + (nextPortId++);
      var port = createRemotePort(portId, '');
      postNative({ type: 'port-connect', portId: portId, name: '' });
      return port;
    },
    getURL: function (path) { return 'asset:///threejs-devtools/' + (path || ''); },
    getManifest: function () {
      return { name: 'Three.js DevTools', version: '1.15', manifest_version: 3 };
    }
  };

  var tabs = {
    sendMessage: function (_tabId, msg) {
      fireCs(msg, { id: EXT_ID });
      return Promise.resolve();
    },
    onRemoved: createEvent()
  };

  var webNavigation = { onCommitted: bgWebNavCommitted };

  var action = {
    onClicked: createEvent(),
    setIcon: function (opts) { postNative({ type: 'action.setIcon', path: opts && opts.path }); return Promise.resolve(); },
    setBadgeText: function (opts) {
      postNative({ type: 'action.setBadgeText', tabId: opts && opts.tabId, text: (opts && opts.text) || '' });
      return Promise.resolve();
    },
    setBadgeBackgroundColor: function (opts) {
      postNative({ type: 'action.setBadgeBackgroundColor', tabId: opts && opts.tabId, color: opts && opts.color });
      return Promise.resolve();
    },
    setBadgeTextColor: function () { return Promise.resolve(); }
  };

  // Injected via IIFE wrapper around content-script.js so its onMessage
  // listeners don't observe chrome.runtime.sendMessage calls from itself.
  function createContentScriptChrome() {
    return {
      runtime: {
        __netex: true,
        id: EXT_ID,
        onMessage: csOnMessage,
        sendMessage: function (msg) {
          fireBg(msg, { tab: { id: TAB_ID }, id: EXT_ID });
          return Promise.resolve();
        },
        getURL: runtime.getURL,
        getManifest: runtime.getManifest
      }
    };
  }

  function nativeDispatch(envelope) {
    if (!envelope || typeof envelope !== 'object') return;
    switch (envelope.type) {
      case 'webNavigation.committed':
        bgWebNavCommitted._fire({
          tabId: TAB_ID,
          frameId: envelope.frameId || 0,
          url: envelope.url || ''
        });
        break;
      case 'tab.removed':
        tabs.onRemoved._fire(envelope.tabId || TAB_ID, { isWindowClosing: false });
        break;
      case 'port-connect': {
        var port = createRemotePort(envelope.portId, envelope.name || '');
        bgOnConnect._fire(port);
        break;
      }
      case 'port-message': {
        var p = remotePorts.get(envelope.portId);
        if (p) p._deliver(envelope.message);
        break;
      }
      case 'port-disconnect': {
        var pp = remotePorts.get(envelope.portId);
        if (pp) pp._remoteClosed();
        break;
      }
    }
  }

  // Recurses into same-origin iframes so port traffic reaches their shims too
  // (three.js demos commonly live in an iframe).
  function dispatchAllFrames(envelope) {
    function visit(w) {
      try { if (w.__netexExt) w.__netexExt.dispatch(envelope); } catch (e) {}
      for (var i = 0; i < w.frames.length; i++) {
        try { visit(w.frames[i]); } catch (e) {}
      }
    }
    visit(window);
  }

  // background.js calls importScripts('constants.js') — constants are already in scope.
  if (typeof importScripts === 'undefined') {
    window.importScripts = function () { /* no-op */ };
  }

  window.chrome = window.chrome || {};
  window.chrome.runtime = runtime;
  window.chrome.tabs = tabs;
  window.chrome.webNavigation = webNavigation;
  window.chrome.action = action;

  window.__netexExt = {
    dispatch: nativeDispatch,
    dispatchAllFrames: dispatchAllFrames,
    createContentScriptChrome: createContentScriptChrome
  };

  // The bundle runs synchronously; this microtask fires after every listener
  // is registered so bg.js can clear the badge (onCommitted) and the native
  // router can replay port-connects for live panel ports (page-ready).
  Promise.resolve().then(function () {
    bgWebNavCommitted._fire({ tabId: TAB_ID, frameId: 0, url: location.href });
    postNative({ type: 'page-ready', frameId: window === window.top ? 0 : 1 });
  });
})();
