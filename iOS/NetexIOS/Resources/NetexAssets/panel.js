(function () {
  var pendingBlobs = new Map();
  var ASSET_BASE = 'netex-assets://bundle/NetexAssets/vendor/';
  var CSS_BASE = ASSET_BASE.replace(/\/$/, '');
  var HLJS_BASE = CSS_BASE;
  var loadedScripts = new Map();

  function loadScript(url, attrs) {
    if (loadedScripts.has(url)) return loadedScripts.get(url);
    var promise = new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = url;
      if (attrs) Object.keys(attrs).forEach(function (k) { s.setAttribute(k, attrs[k]); });
      s.onload = resolve;
      s.onerror = reject;
      document.head.appendChild(s);
    });
    loadedScripts.set(url, promise);
    return promise;
  }

  function ensureHighlight() {
    if (window.hljs) return Promise.resolve();
    return loadScript(ASSET_BASE + 'highlight.min.js');
  }

  function ensureBeautifier() {
    if (typeof html_beautify === 'function') return Promise.resolve();
    return loadScript(ASSET_BASE + 'beautify.min.js')
      .then(function () { return loadScript(ASSET_BASE + 'beautify-css.min.js'); })
      .then(function () { return loadScript(ASSET_BASE + 'beautify-html.min.js'); });
  }

  function ensureModelViewer() {
    if (customElements.get('model-viewer')) return Promise.resolve();
    return loadScript(ASSET_BASE + 'model-viewer.min.js', { type: 'module' }).catch(function () {});
  }

  var overlayModelViewer = null;

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
  }

  var sourceRaw = '';
  var sourceDisplayText = '';
  var sourceCheckpoint = '';
  var sourceEditMode = false;
  var sourceDirty = false;
  var sourceApplying = false;
  var sourceVersion = 0;
  var sourceFindMatches = [];
  var sourceFindIndex = -1;
  var sourceFindNeedle = '';

  function requestInspectorResize(mode) {
    try {
      AndroidPanel.postMessage(JSON.stringify({
        type: 'inspector.resize',
        payload: { mode: mode || 'normal' }
      }));
    } catch (e) {}
  }

  function markSourceRanges(root, matches, currentIndex, needleLength) {
    if (!root || !matches.length || !needleLength) return;
    var ranges = matches.map(function (start, index) {
      return { start: start, end: start + needleLength, current: index === currentIndex };
    });
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    var nodes = [];
    var offset = 0;
    var node;
    while ((node = walker.nextNode())) {
      var text = node.nodeValue || '';
      nodes.push({ node: node, start: offset, end: offset + text.length });
      offset += text.length;
    }

    nodes.forEach(function (entry) {
      var overlaps = ranges.filter(function (range) {
        return range.start < entry.end && range.end > entry.start;
      });
      if (!overlaps.length) return;
      overlaps.sort(function (a, b) { return a.start - b.start; });

      var text = entry.node.nodeValue || '';
      var frag = document.createDocumentFragment();
      var cursor = 0;
      overlaps.forEach(function (range) {
        var start = Math.max(0, range.start - entry.start);
        var end = Math.min(text.length, range.end - entry.start);
        if (start > cursor) frag.appendChild(document.createTextNode(text.slice(cursor, start)));
        var mark = document.createElement('mark');
        mark.className = 'source-find-match' + (range.current ? ' source-find-current' : '');
        mark.textContent = text.slice(start, end);
        frag.appendChild(mark);
        cursor = end;
      });
      if (cursor < text.length) frag.appendChild(document.createTextNode(text.slice(cursor)));
      entry.node.parentNode.replaceChild(frag, entry.node);
    });
  }

  function renderSourceCode(code, text, decorate) {
    if (!code) return;
    var renderSeq = (code.__netexRenderSeq || 0) + 1;
    code.__netexRenderSeq = renderSeq;
    code.textContent = text || '';
    code.removeAttribute('data-highlighted');
    ensureHighlight().then(function () {
      if (code.__netexRenderSeq !== renderSeq) return;
      try { hljs.highlightElement(code); } catch (e) {}
      if (decorate) markSourceRanges(code, sourceFindMatches, sourceFindIndex, sourceFindNeedle.length);
      if (decorate) scrollActiveSourceMatch();
    }).catch(function () {
      if (decorate) markSourceRanges(code, sourceFindMatches, sourceFindIndex, sourceFindNeedle.length);
      if (decorate) scrollActiveSourceMatch();
    });
  }

  function renderSourceEditorHighlight() {
    var editor = document.getElementById('sourceEditor');
    var code = document.getElementById('sourceEditorHighlight');
    if (!editor || !code) return;
    renderSourceCode(code, editor.value, sourceEditMode);
  }

  function renderSourceFindHighlights() {
    if (sourceEditMode) {
      return;
    } else {
      renderSourceCode(document.querySelector('#source-tab .source-view code'), sourceDisplayText, true);
    }
  }

  function syncSourceEditorScroll() {
    var editor = document.getElementById('sourceEditor');
    var highlight = document.querySelector('.source-editor-highlight');
    if (!editor || !highlight) return;
    highlight.scrollTop = editor.scrollTop;
    highlight.scrollLeft = editor.scrollLeft;
  }

  function scrollEditorToOffset(offset) {
    var editor = document.getElementById('sourceEditor');
    if (!editor) return;
    var before = editor.value.slice(0, offset).split('\n');
    var line = before.length - 1;
    var column = before[before.length - 1].length;
    var lineHeight = 12.8;
    var charWidth = 6.4;
    editor.scrollTop = Math.max(0, (line * lineHeight) - (editor.clientHeight * 0.38));
    editor.scrollLeft = Math.max(0, (column * charWidth) - (editor.clientWidth * 0.25));
    syncSourceEditorScroll();
  }

  function scrollActiveSourceMatch() {
    var active = document.querySelector('#source-tab .source-find-current');
    if (sourceEditMode) {
      return;
    }
    if (active) active.scrollIntoView({ block: 'center', inline: 'center' });
  }

  function setSource(html) {
    sourceRaw = html || '';
    var version = ++sourceVersion;
    var oldCode = document.querySelector('#source-tab code');
    var newCode = document.createElement('code');
    newCode.className = 'language-html';
    var formatted = sourceRaw;
    if (typeof html_beautify === 'function') {
      try {
        formatted = html_beautify(sourceRaw, {
          indent_size: 2,
          indent_with_tabs: false,
          preserve_newlines: true,
          max_preserve_newlines: 1,
          wrap_line_length: 0
        });
      } catch (e) { /* fall back to raw */ }
    }
    sourceDisplayText = formatted;
    renderSourceCode(newCode, sourceDisplayText, false);
    oldCode.parentNode.replaceChild(newCode, oldCode);
    if (!sourceEditMode) {
      var editor = document.getElementById('sourceEditor');
      if (editor) editor.value = sourceRaw;
      sourceDirty = false;
      setSourceStatus('Read-only snapshot');
      updateSourceButtons();
    }
    ensureBeautifier().then(function () {
      if (version !== sourceVersion) return;
      if (typeof html_beautify === 'function') {
        try { sourceDisplayText = html_beautify(sourceRaw, { indent_size: 2, indent_with_tabs: false, preserve_newlines: true, max_preserve_newlines: 1, wrap_line_length: 0 }); } catch (e) {}
      }
      renderSourceCode(newCode, sourceDisplayText, !sourceEditMode);
    }).catch(function () {});
  }

  function setSourceStatus(text) {
    var status = document.getElementById('sourceStatus');
    if (status) status.textContent = text;
  }

  function updateSourceButtons() {
    var edit = document.getElementById('sourceEdit');
    var apply = document.getElementById('sourceApply');
    var revert = document.getElementById('sourceRevert');
    if (edit) {
      edit.disabled = sourceApplying;
      edit.textContent = sourceEditMode ? 'Done' : 'Edit';
    }
    if (apply) apply.disabled = sourceApplying || !sourceEditMode || !sourceDirty;
    if (revert) revert.disabled = sourceApplying || !sourceEditMode;
  }

  function setSourceEditing(enabled) {
    var tab = document.getElementById('source-tab');
    var editor = document.getElementById('sourceEditor');
    if (!tab || !editor) return;
    sourceEditMode = !!enabled;
    tab.classList.toggle('editing', sourceEditMode);
    if (sourceEditMode) {
      sourceCheckpoint = sourceRaw;
      editor.value = sourceRaw;
      sourceDirty = false;
      sourceApplying = false;
      setSourceStatus('Editing live-session source. Apply rewrites this page only.');
      requestInspectorResize('source-edit');
      setTimeout(function () { editor.focus(); }, 0);
    } else {
      sourceDirty = false;
      setSourceStatus('Read-only snapshot');
      requestInspectorResize('normal');
    }
    updateSourceButtons();
    refreshSourceFind(true);
  }

  function sourceWriteScript(html) {
    return "(function(){var html=" + JSON.stringify(html) + ";document.open();document.write(html);document.close();return 'Source applied';})()";
  }

  function applySourceEdit() {
    var editor = document.getElementById('sourceEditor');
    if (!editor || !sourceEditMode) return;
    sourceApplying = true;
    setSourceStatus('Applying source to live page...');
    updateSourceButtons();
    try {
      postPanelEval(sourceWriteScript(editor.value), 'source-apply');
    } catch (e) {
      sourceApplying = false;
      updateSourceButtons();
      setSourceStatus('Apply failed: ' + String(e));
    }
  }

  function revertSourceEdit() {
    var editor = document.getElementById('sourceEditor');
    if (!editor || !sourceEditMode) return;
    editor.value = sourceCheckpoint;
    sourceDirty = false;
    sourceApplying = true;
    setSourceStatus('Reverting to edit checkpoint...');
    updateSourceButtons();
    try {
      postPanelEval(sourceWriteScript(sourceCheckpoint), 'source-revert');
    } catch (e) {
      sourceApplying = false;
      updateSourceButtons();
      setSourceStatus('Revert failed: ' + String(e));
    }
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
    det.classList.toggle('model-row', isModel(r));

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
      var image = det.querySelector(':scope > .body > img');
      if (!image) return;
      det.dataset.loaded = '1';
      requestBlob(rid, function (dataUrl) {
        if (dataUrl) image.src = dataUrl;
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
      return buildModelPreviewTrigger(r);
    }
    return null;
  }

  function buildModelPreviewTrigger(r) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'model-preview-trigger';
    button.textContent = '3D Preview';
    button.addEventListener('click', function () {
      openModelPreview(r);
    });
    return button;
  }

  function hiddenModelViewerSlot(slotName) {
    var el = document.createElement('span');
    el.slot = slotName;
    el.className = 'hidden-model-viewer-slot';
    el.setAttribute('aria-hidden', 'true');
    return el;
  }

  function ensureOverlayModelViewer() {
    if (overlayModelViewer) return overlayModelViewer;
    var stage = document.getElementById('modelStage');
    if (!stage) return null;
    var mv = document.createElement('model-viewer');
    mv.className = 'model-preview-host';
    mv.setAttribute('camera-controls', '');
    mv.setAttribute('camera-orbit', '0deg 75deg auto');
    mv.setAttribute('camera-target', '0m 0m 0m');
    mv.setAttribute('interaction-prompt', 'none');
    mv.setAttribute('touch-action', 'none');
    mv.style.setProperty('--interaction-prompt-display', 'none');
    var panTarget = hiddenModelViewerSlot('pan-target');
    mv.appendChild(panTarget);
    var prompt = hiddenModelViewerSlot('interaction-prompt');
    mv.appendChild(prompt);
    stage.replaceChildren(mv);
    overlayModelViewer = mv;
    ensureModelViewer();
    return mv;
  }

  function openModelPreview(r) {
    var overlay = document.getElementById('modelOverlay');
    var title = document.getElementById('modelOverlayTitle');
    var mv = ensureOverlayModelViewer();
    if (!overlay || !mv) return;
    if (title) title.textContent = r.displayUrl || r.url || '3D Preview';
    overlay.hidden = false;
    overlay.setAttribute('aria-hidden', 'false');
    mv.removeAttribute('src');
    mv.setAttribute('camera-target', '0m 0m 0m');
    mv.setAttribute('camera-orbit', '0deg 75deg auto');
    if (r.requestId) {
      requestBlob(r.requestId, function (dataUrl) {
        if (dataUrl && !overlay.hidden) mv.setAttribute('src', dataUrl);
      });
    } else {
      mv.setAttribute('src', r.url);
    }
  }

  function closeModelPreview() {
    var overlay = document.getElementById('modelOverlay');
    if (!overlay) return;
    overlay.hidden = true;
    overlay.setAttribute('aria-hidden', 'true');
    if (overlayModelViewer) overlayModelViewer.removeAttribute('src');
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
    ensureHighlight().then(function () {
      try { hljs.highlightElement(code); } catch (e) {}
    }).catch(function () {});
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
  var pendingEvalTargets = new Map();
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
  }

  function postPanelEval(source, target) {
    var evalId = (target || 'e') + '-' + (++evalSeq);
    pendingEvals.add(evalId);
    pendingEvalTargets.set(evalId, target || 'console');
    try {
      AndroidPanel.postMessage(JSON.stringify({ type: 'eval', evalId: evalId, source: source }));
      return evalId;
    } catch (e) {
      pendingEvals.delete(evalId);
      pendingEvalTargets.delete(evalId);
      throw e;
    }
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
    try {
      postPanelEval(source, 'console');
    } catch (e) {
      appendConsoleEntry({ level: 'result_error', message: String(e) });
    }
  }

  function deliverEvalResult(evalId, resultJson) {
    if (!pendingEvals.delete(evalId)) return;
    var target = pendingEvalTargets.get(evalId);
    pendingEvalTargets.delete(evalId);
    var parsed;
    try { parsed = JSON.parse(resultJson); } catch (e) { parsed = [false, 'unparseable result: ' + resultJson]; }
    if (!Array.isArray(parsed)) parsed = [false, 'unexpected result shape'];
    if (target === 'source-apply' || target === 'source-revert') {
      sourceApplying = false;
      if (parsed[0]) {
        sourceRaw = document.getElementById('sourceEditor') ? document.getElementById('sourceEditor').value : sourceRaw;
        sourceDirty = false;
        setSourceStatus(target === 'source-apply' ? 'Applied to live page.' : 'Reverted to edit checkpoint.');
      } else {
        setSourceStatus((target === 'source-apply' ? 'Apply' : 'Revert') + ' failed: ' + String(parsed[1]));
      }
      updateSourceButtons();
      return;
    }
    appendConsoleEntry({ level: parsed[0] ? 'result' : 'result_error', message: String(parsed[1]) });
  }

  function refreshSourceFind(reset) {
    var input = document.getElementById('sourceFind');
    var count = document.getElementById('sourceFindCount');
    var editor = document.getElementById('sourceEditor');
    var query = input ? input.value : '';
    var text = sourceEditMode && editor ? editor.value : sourceDisplayText;
    sourceFindMatches = [];
    sourceFindIndex = reset ? -1 : sourceFindIndex;
    sourceFindNeedle = query;
    if (!query) {
      if (count) count.textContent = '0/0';
      renderSourceFindHighlights();
      return;
    }
    var haystack = text.toLowerCase();
    var needle = query.toLowerCase();
    var from = 0;
    while (needle && from <= haystack.length) {
      var found = haystack.indexOf(needle, from);
      if (found === -1) break;
      sourceFindMatches.push(found);
      from = found + Math.max(needle.length, 1);
    }
    if (sourceFindIndex >= sourceFindMatches.length) sourceFindIndex = sourceFindMatches.length - 1;
    if (count) count.textContent = sourceFindMatches.length ? Math.max(sourceFindIndex + 1, 0) + '/' + sourceFindMatches.length : '0/0';
    renderSourceFindHighlights();
  }

  function selectSourceMatch(direction) {
    var input = document.getElementById('sourceFind');
    var editor = document.getElementById('sourceEditor');
    if (!input || !input.value) return;
    refreshSourceFind(false);
    if (!sourceFindMatches.length) return;
    sourceFindIndex = sourceFindIndex === -1
      ? (direction < 0 ? sourceFindMatches.length - 1 : 0)
      : (sourceFindIndex + direction + sourceFindMatches.length) % sourceFindMatches.length;
    var start = sourceFindMatches[sourceFindIndex];
    if (sourceEditMode && editor) {
      var end = start + input.value.length;
      editor.focus();
      editor.setSelectionRange(start, end);
      scrollEditorToOffset(start);
    }
    var count = document.getElementById('sourceFindCount');
    if (count) count.textContent = (sourceFindIndex + 1) + '/' + sourceFindMatches.length;
    renderSourceFindHighlights();
  }

  function findInSource(direction) {
    selectSourceMatch(direction || 1);
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

  function wireSourceEditor() {
    var edit = document.getElementById('sourceEdit');
    var apply = document.getElementById('sourceApply');
    var revert = document.getElementById('sourceRevert');
    var editor = document.getElementById('sourceEditor');
    var find = document.getElementById('sourceFind');
    var prev = document.getElementById('sourceFindPrev');
    var next = document.getElementById('sourceFindNext');
    if (!edit || !apply || !revert || !editor) return;
    edit.addEventListener('click', function () { setSourceEditing(!sourceEditMode); });
    apply.addEventListener('click', applySourceEdit);
    revert.addEventListener('click', revertSourceEdit);
    editor.addEventListener('input', function () {
      sourceDirty = true;
      setSourceStatus('Editing live-session source.');
      updateSourceButtons();
      if (!sourceEditMode) renderSourceEditorHighlight();
      refreshSourceFind(false);
    });
    editor.addEventListener('scroll', syncSourceEditorScroll);
    if (find) {
      find.addEventListener('input', function () { refreshSourceFind(true); });
      find.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          findInSource(e.shiftKey ? -1 : 1);
        }
      });
    }
    if (prev) prev.addEventListener('click', function () { findInSource(-1); });
    if (next) next.addEventListener('click', function () { findInSource(1); });
  }
  wireSourceEditor();

  function wireModelPreviewOverlay() {
    var close = document.getElementById('modelOverlayClose');
    var overlay = document.getElementById('modelOverlay');
    if (close) close.addEventListener('click', closeModelPreview);
    if (overlay) {
      overlay.addEventListener('click', function (e) {
        if (e.target === overlay) closeModelPreview();
      });
    }
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') closeModelPreview();
    });
  }
  wireModelPreviewOverlay();

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
