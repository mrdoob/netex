(function () {
  var pendingBlobs = new Map();
  var HLJS_BASE = 'https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles';

  function setTheme(opts) {
    document.documentElement.style.setProperty('--fg', opts.fg);
    document.documentElement.style.setProperty('--panel-bg', opts.panelBg);
    document.documentElement.style.setProperty('--viewer-bg', opts.viewerBg);
    var theme = opts.dark ? 'github-dark' : 'github';
    document.getElementById('hljs-theme').href = HLJS_BASE + '/' + theme + '.min.css';
  }

  function setActiveTab(tab) {
    document.getElementById('source-tab').classList.toggle('active', tab === 'source');
    document.getElementById('network-tab').classList.toggle('active', tab === 'network');
  }

  function setSource(html) {
    var oldCode = document.querySelector('#source-tab code');
    var newCode = document.createElement('code');
    newCode.className = 'language-html';
    var formatted = html;
    if (typeof html_beautify === 'function') {
      try {
        formatted = html_beautify(html, {
          indent_size: 2,
          indent_with_tabs: false,
          preserve_newlines: true,
          max_preserve_newlines: 1,
          wrap_line_length: 0
        });
      } catch (e) { /* fall back to raw */ }
    }
    newCode.textContent = formatted;
    oldCode.parentNode.replaceChild(newCode, oldCode);
    try { hljs.highlightElement(newCode); } catch (e) {}
  }

  var totalBytes = 0;
  var totalCount = 0;

  function updateTotals() {
    var el = document.getElementById('netTotals');
    if (!el) return;
    if (totalCount === 0) { el.classList.remove('show'); return; }
    el.classList.add('show');
    document.getElementById('netTotalsCount').textContent =
      totalCount + ' request' + (totalCount === 1 ? '' : 's');
    document.getElementById('netTotalsSize').textContent = formatSize(totalBytes);
  }

  function clearNetwork() {
    var tab = document.getElementById('network-tab');
    Array.from(tab.children).forEach(function (child) {
      if (child.id !== 'netTotals') child.remove();
    });
    var p = document.createElement('p');
    p.className = 'empty';
    p.textContent = 'No requests captured yet.';
    tab.appendChild(p);
    totalBytes = 0;
    totalCount = 0;
    updateTotals();
  }

  function appendNetworkRow(payload) {
    var r = typeof payload === 'string' ? JSON.parse(payload) : payload;
    var container = document.getElementById('network-tab');
    var det = r.requestId
      ? container.querySelector('details.req[data-rid="' + r.requestId + '"]')
      : null;

    if (!det) {
      var empty = container.querySelector('.empty');
      if (empty) empty.remove();
      det = document.createElement('details');
      det.className = 'req';
      if (r.requestId) det.dataset.rid = r.requestId;
      det.dataset.lastSize = '0';
      container.appendChild(det);
      totalCount++;
      bindToggle(det, r.requestId);
    }

    // Track size delta per row so progress ticks update totals without double-counting.
    var lastSize = parseInt(det.dataset.lastSize, 10) || 0;
    var newSize = r.size || 0;
    totalBytes += newSize - lastSize;
    det.dataset.lastSize = String(newSize);
    updateTotals();

    renderRow(det, r);
  }

  function renderRow(det, r) {
    var statusClass = (r.pending || r.status === 0) ? 's0'
      : r.status < 300 ? 's2xx'
      : r.status < 400 ? 's3xx'
      : r.status < 500 ? 's4xx'
      : 's5xx';
    var statusText = r.pending ? '…' : r.status === 0 ? '—' : r.status;

    var summary = document.createElement('summary');
    summary.appendChild(span('method ' + r.method, r.method));
    summary.appendChild(span('url', r.displayUrl || r.url));
    if (r.size) summary.appendChild(span('size', formatSize(r.size)));
    summary.appendChild(span('status ' + statusClass, statusText));

    var body = document.createElement('div');
    body.className = 'body';
    if (!r.pending) body.appendChild(buildMedia(r) || buildText(r));

    det.replaceChildren(summary, body);
  }

  function bindToggle(det, rid) {
    if (!rid) return;
    det.addEventListener('toggle', function () {
      if (!det.open || det.dataset.loaded) return;
      var media = det.querySelector(':scope > .body > img, :scope > .body > model-viewer');
      if (!media) return;
      det.dataset.loaded = '1';
      requestBlob(rid, function (dataUrl) {
        if (dataUrl) media.src = dataUrl;
      });
    });
  }

  function buildMedia(r) {
    if (isImage(r)) {
      var img = document.createElement('img');
      img.loading = 'lazy';
      img.referrerPolicy = 'no-referrer';
      img.alt = '';
      if (!r.requestId) img.src = r.url;
      return img;
    }
    if (isModel(r)) {
      var mv = document.createElement('model-viewer');
      mv.setAttribute('camera-controls', '');
      mv.setAttribute('auto-rotate', '');
      mv.setAttribute('touch-action', 'pan-y');
      if (!r.requestId) mv.setAttribute('src', r.url);
      return mv;
    }
    return null;
  }

  function buildText(r) {
    var pre = document.createElement('pre');
    var code = document.createElement('code');
    var ct = (r.contentType || '').toLowerCase();
    var raw = r.body || '';
    if (ct.indexOf('json') !== -1) {
      code.className = 'language-json';
      code.textContent = prettyJson(raw);
    } else {
      code.className = 'language-plaintext';
      code.textContent = raw;
    }
    pre.appendChild(code);
    try { hljs.highlightElement(code); } catch (e) {}
    return pre;
  }

  function prettyJson(raw) {
    var trimmed = raw.replace(/^\s+/, '');
    if (trimmed[0] !== '{' && trimmed[0] !== '[') return raw;
    try { return JSON.stringify(JSON.parse(raw), null, 2); } catch (e) { return raw; }
  }

  function isImage(r) {
    if (/^image\//i.test(r.contentType || '')) return true;
    return /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)(\?|#|$)/i.test(r.url);
  }
  function isModel(r) {
    if (/^model\/gltf/i.test(r.contentType || '')) return true;
    return /\.(glb|gltf)(\?|#|$)/i.test(r.url);
  }

  function formatSize(b) {
    if (b < 1024) return b + ' B';
    if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB';
    return (b / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function span(cls, text) {
    var e = document.createElement('span');
    e.className = cls;
    e.textContent = text;
    return e;
  }

  function deliverBlob(rid, dataUrl) {
    var pending = pendingBlobs.get(rid);
    if (!pending) return;
    pending.forEach(function (cb) { cb(dataUrl); });
    pendingBlobs.delete(rid);
  }

  function requestBlob(rid, cb) {
    var pending = pendingBlobs.get(rid);
    if (!pending) {
      pending = [];
      pendingBlobs.set(rid, pending);
      try {
        AndroidPanel.postMessage(JSON.stringify({ type: 'fetch-blob', requestId: rid }));
      } catch (e) {
        cb('');
        return;
      }
    }
    pending.push(cb);
  }

  window.__panel = {
    setTheme: setTheme,
    setActiveTab: setActiveTab,
    setSource: setSource,
    clearNetwork: clearNetwork,
    appendNetworkRow: appendNetworkRow,
    deliverBlob: deliverBlob
  };
})();
