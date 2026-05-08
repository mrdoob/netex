// Patches window.fetch and XMLHttpRequest to forward each request/response
// to the AndroidNetwork bridge. Image/model responses are stashed as Blobs
// in window.__netex.blobs and only encoded to data: URLs lazily when
// the panel asks via window.__netex.findBlob(requestId).
(function () {
  var ns = window.__netex || (window.__netex = {});
  if (ns.installed) return;
  ns.installed = true;

  var MAX_BODY = 256 * 1024;        // 256 KB
  var MAX_BINARY = 4 * 1024 * 1024; // 4 MB
  var MAX_BLOBS = 64;               // FIFO cap so long-lived pages don't pin memory

  if (!ns.blobs) ns.blobs = new Map();

  function clip(s) {
    if (typeof s !== 'string') return '';
    return s.length > MAX_BODY ? s.slice(0, MAX_BODY) + '\n…[truncated]' : s;
  }

  function isBinaryDisplayable(contentType) {
    if (!contentType) return false;
    return /^(image|model)\//i.test(contentType);
  }

  function parseLen(headerValue) {
    return parseInt(headerValue || '0', 10) || 0;
  }

  function post(record) {
    try {
      if (typeof AndroidNetwork !== 'undefined' && AndroidNetwork.postMessage) {
        AndroidNetwork.postMessage(JSON.stringify(record));
      }
    } catch (e) { /* ignore */ }
  }

  function newRequestId() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    // Fallback for older WebViews.
    return 'r-' + Math.random().toString(36).slice(2) + '-' + Date.now().toString(36);
  }

  function captureBlob(blob) {
    if (!blob || blob.size > MAX_BINARY) return '';
    if (ns.blobs.size >= MAX_BLOBS) {
      var oldest = ns.blobs.keys().next().value;
      if (oldest) ns.blobs.delete(oldest);
    }
    var rid = newRequestId();
    ns.blobs.set(rid, blob);
    return rid;
  }

  ns.readBlob = function (rid) {
    var blob = ns.blobs.get(rid);
    if (!blob) {
      post({ type: 'blob-response', requestId: rid, body: '' });
      return;
    }
    var reader = new FileReader();
    reader.onloadend = function () {
      post({ type: 'blob-response', requestId: rid, body: reader.result || '' });
    };
    reader.onerror = function () {
      post({ type: 'blob-response', requestId: rid, body: '' });
    };
    reader.readAsDataURL(blob);
  };

  // Native calls this on a panel expand. Each frame has its own __netex
  // namespace; recurse through same-origin frames to find the one with the blob.
  ns.findBlob = function (rid) {
    function visit(win) {
      try {
        var nsi = win.__netex;
        if (nsi && nsi.blobs && nsi.blobs.has(rid)) {
          nsi.readBlob(rid);
          return true;
        }
        for (var i = 0; i < win.frames.length; i++) {
          if (visit(win.frames[i])) return true;
        }
      } catch (e) { /* cross-origin frame */ }
      return false;
    }
    visit(window);
  };

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var method = (init && init.method) || (input && input.method) || 'GET';
      var start = Date.now();
      var promise = origFetch.apply(this, arguments);
      promise.then(function (response) {
        var ct = response.headers.get('content-type') || '';
        var lenHeader = parseLen(response.headers.get('content-length'));
        var emit = function (body, requestId, size) {
          post({
            method: method.toUpperCase(),
            url: url,
            status: response.status,
            body: body,
            contentType: ct,
            duration: Date.now() - start,
            size: size || 0,
            requestId: requestId || ''
          });
        };
        if (isBinaryDisplayable(ct)) {
          response.clone().blob()
            .then(function (blob) { emit('', captureBlob(blob), lenHeader || blob.size); })
            .catch(function () { emit('', '', lenHeader); });
        } else {
          response.clone().text()
            .then(function (b) { emit(clip(b), '', lenHeader || b.length); })
            .catch(function () {});
        }
      }).catch(function (err) {
        post({
          method: method.toUpperCase(),
          url: url,
          status: 0,
          body: '',
          contentType: '',
          duration: Date.now() - start,
          requestId: '',
          error: String(err)
        });
      });
      return promise;
    };
  }

  // PerformanceObserver picks up resources the browser loads directly
  // (<img>, <iframe>, the document itself) — fetch/XHR are already covered above.
  function classify(entry) {
    if (entry.entryType === 'navigation') return 'text/html';
    if (entry.entryType !== 'resource') return null;
    if (entry.initiatorType === 'iframe') return 'text/html';
    if (entry.initiatorType === 'img') return 'image/*';
    return null;
  }

  function isUninteresting(entry) {
    var url = entry.name || '';
    if (url.indexOf('data:') === 0) return true;
    if (url.indexOf('about:') === 0) return true;
    if (url.indexOf('javascript:') === 0) return true;
    return false;
  }

  var seenEntries = new Set();
  try {
    var po = new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (entry) {
        if (isUninteresting(entry)) return;
        // Sub-frames' navigation is already logged by the parent as an iframe resource.
        if (entry.entryType === 'navigation' && window !== window.top) return;
        var ct = classify(entry);
        if (!ct) return;
        var key = entry.entryType + '|' + entry.startTime + '|' + entry.name;
        if (seenEntries.has(key)) return;
        seenEntries.add(key);
        if (seenEntries.size > 512) seenEntries.clear();
        var body = '';
        if (entry.entryType === 'navigation' && window === window.top) {
          try { body = clip(document.documentElement.outerHTML); } catch (e) {}
        }
        post({
          method: 'GET',
          url: entry.name,
          status: entry.responseStatus || 0,
          body: body,
          contentType: ct,
          duration: Math.round(entry.duration || 0),
          size: entry.decodedBodySize || entry.encodedBodySize || 0,
          requestId: ''
        });
      });
    });
    // Two observes (each with buffered:true) — entries finalized before the async callback
    // runs would be lost otherwise. Dedupe handles any double-delivery.
    po.observe({ type: 'resource', buffered: true });
    po.observe({ type: 'navigation', buffered: true });
  } catch (e) { /* PerformanceObserver unavailable */ }

  var XHR = window.XMLHttpRequest;
  if (XHR) {
    var origOpen = XHR.prototype.open;
    var origSend = XHR.prototype.send;
    XHR.prototype.open = function (method, url) {
      this._netMethod = method;
      this._netUrl = url;
      return origOpen.apply(this, arguments);
    };
    XHR.prototype.send = function () {
      var xhr = this;
      var start = Date.now();
      xhr.addEventListener('loadend', function () {
        var ct = xhr.getResponseHeader('content-type') || '';
        var lenHeader = parseLen(xhr.getResponseHeader('content-length'));
        var body = '';
        if (!isBinaryDisplayable(ct)) {
          try { body = xhr.responseText || ''; } catch (e) {}
        }
        post({
          method: (xhr._netMethod || 'GET').toUpperCase(),
          url: xhr._netUrl || '',
          status: xhr.status,
          body: clip(body),
          contentType: ct,
          duration: Date.now() - start,
          size: lenHeader || body.length || 0,
          requestId: ''
        });
      });
      return origSend.apply(this, arguments);
    };
  }
})();
