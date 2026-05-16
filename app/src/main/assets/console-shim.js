// Exposes window.__netex.consoleEval so the panel's prompt can run code in the
// page's global scope (indirect eval), and wraps console.{log,info,warn,error,debug}
// so objects render Chrome-like (with class name + method preview) instead of
// "[object Object]" or JSON.stringify dropping the methods.
(function () {
  var ns = window.__netex || (window.__netex = {});
  if (ns.consoleEval) return;

  function format(v, depth, seen) {
    depth = depth || 0;
    if (v === undefined) return 'undefined';
    if (v === null) return 'null';
    var t = typeof v;
    if (t === 'string') return depth === 0 ? v : quoteString(v);
    if (t === 'function') return functionLabel(v);
    if (t === 'symbol') { try { return v.toString(); } catch (e) { return 'Symbol()'; } }
    if (t === 'bigint') return v.toString() + 'n';
    if (t !== 'object') return String(v);
    seen = seen || new WeakSet();
    if (seen.has(v)) return '[Circular]';
    seen.add(v);
    try {
      if (Array.isArray(v)) return formatArray(v, depth, seen);
      if (v instanceof Error) return v.stack || (v.name + ': ' + v.message);
      if (v.nodeType === 1) return formatElement(v);
      return formatObject(v, depth, seen);
    } catch (e) {
      return String(v);
    }
  }

  function quoteString(s) {
    return "'" + s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n') + "'";
  }

  function functionLabel(fn) {
    var name = fn.name || '';
    return name ? 'ƒ ' + name + '()' : 'ƒ ()';
  }

  function formatElement(el) {
    var tag = (el.tagName || 'node').toLowerCase();
    var id = el.id ? ' id="' + el.id + '"' : '';
    var cls = el.className ? ' class="' + String(el.className).slice(0, 40) + '"' : '';
    return '<' + tag + id + cls + '>';
  }

  function ctorName(o) {
    try { return (o.constructor && o.constructor.name) || ''; } catch (e) { return ''; }
  }

  function isPlain(o) {
    try {
      var p = Object.getPrototypeOf(o);
      return p === Object.prototype || p === null;
    } catch (e) { return false; }
  }

  function collectKeys(o) {
    // Class instances (Window, console, etc.) hide their methods on the prototype
    // chain — walk up a few hops so they show up in the preview.
    var found = new Set();
    var out = [];
    var p = o;
    for (var i = 0; i < 3 && p && p !== Object.prototype; i++) {
      try {
        Object.getOwnPropertyNames(p).forEach(function (k) {
          if (!found.has(k) && k !== 'constructor') { found.add(k); out.push(k); }
        });
      } catch (e) {}
      try { p = Object.getPrototypeOf(p); } catch (e) { p = null; }
    }
    // Window has hundreds of numeric frame-index keys; sink them so named ones win the preview.
    var named = [], numeric = [];
    out.forEach(function (k) { (/^\d+$/.test(k) ? numeric : named).push(k); });
    return named.concat(numeric);
  }

  function formatArray(a, depth, seen) {
    var n = a.length;
    var max = depth > 1 ? 3 : 10;
    var parts = [];
    for (var i = 0; i < Math.min(n, max); i++) parts.push(format(a[i], depth + 1, seen));
    if (n > max) parts.push('…');
    return 'Array(' + n + ') [' + parts.join(', ') + ']';
  }

  function formatObject(o, depth, seen) {
    var plain = isPlain(o);
    var name = ctorName(o);
    var keys = plain ? Object.keys(o) : collectKeys(o);
    var max = depth > 1 ? 3 : 8;
    var parts = [];
    for (var i = 0; i < Math.min(keys.length, max); i++) {
      var k = keys[i];
      try { parts.push(k + ': ' + format(o[k], depth + 1, seen)); }
      catch (e) { parts.push(k + ': [throw]'); }
    }
    if (keys.length > max) parts.push('…');
    var prefix = (plain || !name || name === 'Object') ? '' : name + ' ';
    return prefix + '{' + parts.join(', ') + '}';
  }

  ns.consoleEval = function (src) {
    // Chrome's trick: a leading `{` parses as a block statement; wrap so it
    // evaluates as an object literal, then fall back if the wrap is a syntax error.
    var wrapped = /^\s*\{/.test(src) ? '(' + src + '\n)' : src;
    try {
      return [true, format((0, eval)(wrapped), 1)];
    } catch (e) {
      if (wrapped !== src) {
        try { return [true, format((0, eval)(src), 1)]; } catch (e2) { return [false, String(e2)]; }
      }
      return [false, String(e)];
    }
  };

  var FMT_RE = /%[csdifoO]/g;

  // Returns null if there's no %c style directive — caller should fall through to the
  // plain onConsoleMessage path (which preserves source-line info). Otherwise returns
  // a list of styled segments parsed Chrome-style.
  function parseStyled(args) {
    var first = args[0];
    if (typeof first !== 'string' || first.indexOf('%c') === -1) return null;
    var segments = [];
    var currentStyle = '';
    var buf = '';
    var argIdx = 1;
    var pos = 0;
    FMT_RE.lastIndex = 0;
    var m;
    while ((m = FMT_RE.exec(first)) !== null) {
      buf += first.slice(pos, m.index);
      pos = m.index + 2;
      var spec = m[0][1];
      if (spec === 'c') {
        segments.push({ text: buf, style: currentStyle });
        buf = '';
        currentStyle = argIdx < args.length ? String(args[argIdx++]) : '';
      } else if (spec === 's') {
        buf += argIdx < args.length ? String(args[argIdx++]) : m[0];
      } else if (spec === 'd' || spec === 'i') {
        buf += argIdx < args.length ? String(parseInt(args[argIdx++], 10)) : m[0];
      } else if (spec === 'f') {
        buf += argIdx < args.length ? String(parseFloat(args[argIdx++])) : m[0];
      } else {
        buf += argIdx < args.length ? format(args[argIdx++], 1) : m[0];
      }
    }
    buf += first.slice(pos);
    segments.push({ text: buf, style: currentStyle });
    if (argIdx < args.length) {
      var rest = args.slice(argIdx).map(function (a) { return format(a, 0); }).join(' ');
      if (rest) segments.push({ text: ' ' + rest, style: '' });
    }
    return segments;
  }

  function postEntry(level, segments) {
    try {
      AndroidExtension.postMessage(JSON.stringify({ type: 'console.entry', level: level, segments: segments }));
    } catch (e) { /* bridge re-attaches on next load */ }
  }

  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (method) {
    var orig = console[method];
    if (!orig || orig.__netex) return;
    var wrapped = function () {
      var args = Array.prototype.slice.call(arguments);
      var segments = parseStyled(args);
      if (segments) {
        postEntry(method, segments);
        return;
      }
      var formatted = args.map(function (a) { return format(a, 0); });
      return orig.apply(console, formatted);
    };
    wrapped.__netex = true;
    // Preserve the original name so previews show `ƒ log()` rather than `ƒ wrapped()`.
    try { Object.defineProperty(wrapped, 'name', { value: method }); } catch (e) {}
    console[method] = wrapped;
  });
})();
