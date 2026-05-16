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
    document.getElementById('console-tab').classList.toggle('active', tab === 'console');
    document.getElementById('source-tab').classList.toggle('active', tab === 'source');
    document.getElementById('network-tab').classList.toggle('active', tab === 'network');
    // Entries that arrived while the tab was hidden don't trigger auto-scroll
    // (scrollHeight is 0 when the tab is display:none); pin to bottom on first
    // reveal so the user lands on the latest. requestAnimationFrame waits for
    // layout to flush after the display flip.
    if (tab === 'console' && !consoleScrolledUp) {
      requestAnimationFrame(function () {
        var entries = document.getElementById('consoleEntries');
        entries.scrollTop = entries.scrollHeight;
      });
    } else if (tab === 'network' && !networkScrolledUp) {
      requestAnimationFrame(function () {
        window.scrollTo(0, document.documentElement.scrollHeight);
      });
    }
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
  // Network rows live in the document's scroll context (unlike Console which has
  // its own scrollable container). Pin to bottom unless the user scrolled up.
  var networkScrolledUp = false;
  window.addEventListener('scroll', function () {
    networkScrolledUp = (window.scrollY + window.innerHeight) < (document.documentElement.scrollHeight - 4);
  }, { passive: true });

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
    networkScrolledUp = false;
    updateTotals();
  }

  function appendNetworkRow(r) {
    var container = document.getElementById('network-tab');
    var det = r.requestId
      ? container.querySelector('details.req[data-rid="' + r.requestId + '"]')
      : null;
    var isNew = !det;

    if (isNew) {
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

    // Scroll after renderRow so scrollHeight reflects the new row's content.
    if (isNew && !networkScrolledUp) window.scrollTo(0, document.documentElement.scrollHeight);
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

  var MAX_CONSOLE_ENTRIES = 1000;
  var MAX_HISTORY = 100;
  var pendingEvals = new Set();
  var evalSeq = 0;
  var history = [];
  var historyCursor = -1;
  var draft = '';
  // Track via scroll-event rather than reading scrollTop on every append; per-log
  // layout flush gets expensive when chatty pages spam console.log.
  var consoleScrolledUp = false;

  var GLYPH = { input: '❯', result: '❮', result_error: '❮', warn: '⚠', error: '✕' };

  // CSS allowlist for %c styled segments — anything not on this list is dropped so a
  // page can't escape the chip with e.g. position:fixed or background:url(...).
  var SAFE_CSS = new Set([
    'color', 'background', 'background-color',
    'font', 'font-size', 'font-weight', 'font-family', 'font-style',
    'padding', 'padding-left', 'padding-right', 'padding-top', 'padding-bottom',
    'margin', 'margin-left', 'margin-right', 'margin-top', 'margin-bottom',
    'border', 'border-radius', 'border-color', 'border-style', 'border-width',
    'text-decoration', 'text-shadow', 'text-transform',
    'line-height', 'letter-spacing', 'word-spacing'
  ]);

  function sanitizeStyle(s) {
    var parts = String(s).split(';');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i].trim();
      if (!p) continue;
      var colon = p.indexOf(':');
      if (colon === -1) continue;
      var key = p.slice(0, colon).trim().toLowerCase();
      var val = p.slice(colon + 1).trim();
      if (/url\(|expression\(|@import|javascript:/i.test(val)) continue;
      if (SAFE_CSS.has(key)) out.push(key + ':' + val);
    }
    return out.join(';');
  }

  function appendConsoleEntry(r) {
    var entries = document.getElementById('consoleEntries');
    var div = document.createElement('div');
    div.className = 'entry ' + (r.level || 'log');

    var glyph = document.createElement('span');
    glyph.className = 'glyph';
    glyph.textContent = GLYPH[r.level] || '';
    div.appendChild(glyph);

    var msg = document.createElement('span');
    msg.className = 'msg';
    if (Array.isArray(r.segments)) {
      r.segments.forEach(function (s) {
        var seg = document.createElement('span');
        seg.textContent = s.text || '';
        if (s.style) seg.style.cssText = sanitizeStyle(s.style);
        msg.appendChild(seg);
      });
    } else {
      msg.textContent = r.message || '';
    }
    div.appendChild(msg);

    if (r.sourceId) {
      var src = document.createElement('span');
      src.className = 'src';
      src.textContent = shortenSource(r.sourceId) + (r.line ? ':' + r.line : '');
      div.appendChild(src);
    }

    entries.appendChild(div);
    while (entries.childElementCount > MAX_CONSOLE_ENTRIES) entries.firstElementChild.remove();
    if (!consoleScrolledUp) entries.scrollTop = entries.scrollHeight;
  }

  function shortenSource(src) {
    var slash = src.lastIndexOf('/');
    return slash === -1 ? src : src.slice(slash + 1);
  }

  function clearConsole() {
    document.getElementById('consoleEntries').replaceChildren();
    consoleScrolledUp = false;
  }

  function submitEval(source) {
    if (!source) return;
    // Pin to bottom when the user runs a command — they want to see the result, even
    // if they had scrolled up to inspect earlier logs.
    consoleScrolledUp = false;
    history.push(source);
    if (history.length > MAX_HISTORY) history.shift();
    historyCursor = -1;
    draft = '';
    appendConsoleEntry({ level: 'input', message: source });
    var evalId = 'e-' + (++evalSeq);
    pendingEvals.add(evalId);
    try {
      AndroidPanel.postMessage(JSON.stringify({ type: 'eval', evalId: evalId, source: source }));
    } catch (e) {
      appendConsoleEntry({ level: 'result_error', message: String(e) });
      pendingEvals.delete(evalId);
    }
  }

  function deliverEvalResult(evalId, resultJson) {
    if (!pendingEvals.delete(evalId)) return;
    var parsed;
    try { parsed = JSON.parse(resultJson); } catch (e) { parsed = [false, 'unparseable result: ' + resultJson]; }
    if (!Array.isArray(parsed)) parsed = [false, 'unexpected result shape'];
    appendConsoleEntry({ level: parsed[0] ? 'result' : 'result_error', message: String(parsed[1]) });
  }

  function wireConsoleScroll() {
    var entries = document.getElementById('consoleEntries');
    if (!entries) return;
    entries.addEventListener('scroll', function () {
      consoleScrolledUp = entries.scrollTop + entries.clientHeight < entries.scrollHeight - 4;
    });
  }
  wireConsoleScroll();

  function wireConsolePrompt() {
    var form = document.getElementById('consolePrompt');
    var input = document.getElementById('consoleInput');
    if (!form || !input) return;
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var src = input.value;
      input.value = '';
      submitEval(src);
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowUp') {
        if (history.length === 0) return;
        if (historyCursor === -1) draft = input.value;
        historyCursor = historyCursor === -1 ? history.length - 1 : Math.max(0, historyCursor - 1);
        input.value = history[historyCursor];
        e.preventDefault();
      } else if (e.key === 'ArrowDown') {
        if (historyCursor === -1) return;
        historyCursor = historyCursor + 1;
        if (historyCursor >= history.length) {
          historyCursor = -1;
          input.value = draft;
        } else {
          input.value = history[historyCursor];
        }
        e.preventDefault();
      }
    });
  }
  wireConsolePrompt();

  window.__panel = {
    setTheme: setTheme,
    setActiveTab: setActiveTab,
    setSource: setSource,
    clearNetwork: clearNetwork,
    appendNetworkRow: appendNetworkRow,
    deliverBlob: deliverBlob,
    appendConsoleEntry: appendConsoleEntry,
    clearConsole: clearConsole,
    deliverEvalResult: deliverEvalResult
  };
})();
