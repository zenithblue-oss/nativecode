// NativeCode agent-browser driver: injected with evaluateJavascript (no JS interface), one copy per document.
// Calls return JSON strings; {error} on failure. Refs (e1, e2, ...) live in this document only.
(function () {
  if (window.__ncb) return;
  var refs = new Map(), ids = new WeakMap(), seq = 0, results = {}, count = 0, MAX = 4000;
  var weak = typeof WeakRef === 'function';

  function refOf(el) {
    var r = ids.get(el);
    if (!r) { r = 'e' + (++seq); ids.set(el, r); refs.set(r, weak ? new WeakRef(el) : { deref: function () { return el; } }); }
    return r;
  }
  function get(ref) {
    var w = refs.get(ref), el = w && w.deref();
    if (!el || !el.isConnected) throw new Error('Unknown or stale ref ' + ref + '. Take a new browser_snapshot.');
    return el;
  }
  function clean(s, max) { return (s || '').replace(/\s+/g, ' ').trim().slice(0, max || 100); }

  var TAGS = { a: 'link', button: 'button', select: 'combobox', textarea: 'textbox', summary: 'button', option: 'option',
    h1: 'heading', h2: 'heading', h3: 'heading', h4: 'heading', h5: 'heading', h6: 'heading', img: 'img', ul: 'list', ol: 'list',
    li: 'listitem', nav: 'navigation', main: 'main', header: 'banner', footer: 'contentinfo', form: 'form', table: 'table',
    tr: 'row', th: 'columnheader', td: 'cell', dialog: 'dialog', iframe: 'iframe', p: 'paragraph' };
  var BY_CONTENT = { link: 1, button: 1, heading: 1, option: 1, tab: 1, menuitem: 1, checkbox: 1, radio: 1, 'switch': 1 };
  var ACT = { link: 1, button: 1, textbox: 1, searchbox: 1, checkbox: 1, radio: 1, combobox: 1, slider: 1, tab: 1, menuitem: 1, option: 1, 'switch': 1, clickable: 1 };

  function role(el) {
    var r = el.getAttribute('role');
    if (r) return r.split(' ')[0];
    var t = el.tagName.toLowerCase();
    if (t === 'input') {
      var ty = (el.type || 'text').toLowerCase();
      if (ty === 'hidden') return null;
      if (ty === 'checkbox' || ty === 'radio') return ty;
      if (ty === 'button' || ty === 'submit' || ty === 'reset' || ty === 'image') return 'button';
      if (ty === 'range') return 'slider';
      return ty === 'search' ? 'searchbox' : 'textbox';
    }
    if (t === 'a' && !el.hasAttribute('href')) return null;
    if (el.isContentEditable && el.parentElement && !el.parentElement.isContentEditable) return 'textbox';
    return TAGS[t] || null;
  }
  function name(el, r) {
    var s = el.getAttribute('aria-label');
    if (!s) {
      var lb = el.getAttribute('aria-labelledby');
      if (lb) s = lb.split(/\s+/).map(function (i) { var e = document.getElementById(i); return e ? e.textContent : ''; }).join(' ');
    }
    if (!s && el.labels && el.labels.length) s = el.labels[0].textContent;
    if (!s && BY_CONTENT[r]) s = el.innerText || el.textContent;
    if (!s) s = el.getAttribute('alt') || el.getAttribute('title') || el.getAttribute('placeholder') || (el.tagName === 'INPUT' && /^(button|submit|reset)$/.test(el.type) ? el.value : '');
    return clean(s);
  }
  function shown(el) {
    if (el.hidden || el.getAttribute('aria-hidden') === 'true') return false;
    var cs = getComputedStyle(el);
    return cs.display !== 'none' && cs.visibility !== 'hidden';
  }
  function states(el, r) {
    var s = [];
    if (el.disabled) s.push('disabled');
    if (el.checked) s.push('checked');
    if (el.required) s.push('required');
    if (el.selected) s.push('selected');
    var ex = el.getAttribute('aria-expanded');
    if (ex) s.push(ex === 'true' ? 'expanded' : 'collapsed');
    if (el.getAttribute('aria-pressed') === 'true') s.push('pressed');
    if (el.getAttribute('aria-current') && el.getAttribute('aria-current') !== 'false') s.push('current');
    if (r === 'heading') s.push('level=' + (el.tagName.length === 2 ? el.tagName[1] : el.getAttribute('aria-level') || '2'));
    return s;
  }

  function walk(parent) {
    var out = [];
    for (var c = parent.firstChild; c; c = c.nextSibling) {
      if (count > MAX) { out.push('[more content omitted]'); break; }
      if (c.nodeType === 3) { var t = clean(c.nodeValue, 200); if (t) { out.push(t); count++; } continue; }
      if (c.nodeType !== 1) continue;
      var tag = c.tagName.toLowerCase();
      if (/^(script|style|noscript|template|head|svg|canvas)$/.test(tag) || !shown(c)) continue;
      var r = role(c), n = null;
      var pointer = !r && /^(div|span|li|td|img)$/.test(tag) && (c.onclick || c.hasAttribute('onclick') || c.hasAttribute('tabindex') || getComputedStyle(c).cursor === 'pointer')
        && !(c.parentElement && getComputedStyle(c.parentElement).cursor === 'pointer' && !c.onclick);
      if (pointer) r = 'clickable';
      var kids = null;
      if (c.shadowRoot) kids = walk(c.shadowRoot);
      else if (tag === 'iframe') { try { kids = c.contentDocument && c.contentDocument.body ? walk(c.contentDocument.body) : null; } catch (e) { kids = null; } }
      else if (!(r && BY_CONTENT[r])) kids = walk(c);
      if (!r) { if (kids) for (var i = 0; i < kids.length; i++) out.push(kids[i]); continue; }
      count++;
      n = { r: r };
      var nm = name(c, r);
      if (nm) n.n = nm;
      if (ACT[r]) n.ref = refOf(c);
      var st = states(c, r);
      if (st.length) n.s = st;
      if (/^(textbox|searchbox|combobox|slider)$/.test(r) && 'value' in c) n.v = c.type === 'password' ? '[hidden]' : clean(String(c.value), 200);
      else if (c.isContentEditable && r === 'textbox') n.v = clean(c.textContent, 200);
      if (r === 'link' && c.href) n.h = c.href.slice(0, 150);
      if (kids && kids.length) n.c = kids;
      out.push(n);
    }
    return out;
  }

  var CODES = { Enter: 'Enter', Tab: 'Tab', Escape: 'Escape', Space: 'Space', ' ': 'Space' };
  var SCROLL = { PageDown: [0, 0.9], PageUp: [0, -0.9], ArrowDown: [0, 0.2], ArrowUp: [0, -0.2], Space: [0, 0.9] };
  function key(el, k) {
    var o = { key: k === 'Space' ? ' ' : k, code: CODES[k] || k, bubbles: true, cancelable: true, view: window };
    var go = el.dispatchEvent(new KeyboardEvent('keydown', o));
    el.dispatchEvent(new KeyboardEvent('keypress', o));
    var edit = el.isContentEditable || /^(input|textarea|select)$/i.test(el.tagName);
    if (go && k === 'Enter') {
      if (el.form && el.tagName !== 'TEXTAREA') { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }
      else if (/^(button|a)$/i.test(el.tagName)) el.click();
    } else if (go && k === 'Tab') {
      var f = Array.prototype.filter.call(document.querySelectorAll('a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'), function (e) { return !e.disabled && shown(e); });
      var i = f.indexOf(el);
      if (f.length) f[(i + 1) % f.length].focus();
    } else if (go && !edit && SCROLL[k]) window.scrollBy(0, innerHeight * SCROLL[k][1]);
    else if (go && !edit && (k === 'Home' || k === 'End')) window.scrollTo(0, k === 'Home' ? 0 : document.documentElement.scrollHeight);
    el.dispatchEvent(new KeyboardEvent('keyup', o));
  }
  function ser(v) {
    if (v === undefined) return 'undefined';
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v); } catch (e) { return String(v); }
  }
  function safe(f) {
    return function () { try { return JSON.stringify(f.apply(null, arguments)); } catch (e) { return JSON.stringify({ error: String(e && e.message || e) }); } };
  }

  var api = {
    snapshot: safe(function () { count = 0; return { tree: document.body ? walk(document.body) : [] }; }),
    click: safe(function (ref) {
      var el = get(ref);
      if (el.disabled) throw new Error('Element is disabled.');
      el.scrollIntoView({ block: 'center', inline: 'center' });
      var r = el.getBoundingClientRect(), o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0 };
      if (typeof PointerEvent === 'function') el.dispatchEvent(new PointerEvent('pointerdown', o));
      el.dispatchEvent(new MouseEvent('mousedown', o));
      if (el.focus) el.focus();
      if (typeof PointerEvent === 'function') el.dispatchEvent(new PointerEvent('pointerup', o));
      el.dispatchEvent(new MouseEvent('mouseup', o));
      el.click();
      return { ok: true };
    }),
    type: safe(function (ref, text, submit) {
      var el = get(ref);
      el.scrollIntoView({ block: 'center', inline: 'center' });
      el.focus();
      if (el.isContentEditable) {
        document.execCommand('selectAll', false, null);
        if (!document.execCommand('insertText', false, text)) el.textContent = text;
      } else if ('value' in el && !/^(select)$/i.test(el.tagName)) {
        var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
        if (d && d.set) d.set.call(el, text); else el.value = text;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } else throw new Error('Element is not editable.');
      if (submit) key(el, 'Enter');
      return { ok: true };
    }),
    press: safe(function (k) { key(document.activeElement || document.body, k); return { ok: true }; }),
    run: safe(function (id, f) {
      results[id] = { p: 1 };
      Promise.resolve().then(function () { return typeof f === 'function' ? f() : f; })
        .then(function (v) { results[id] = { v: ser(v) }; }, function (e) { results[id] = { e: String(e && e.message || e) }; });
      return { started: true };
    }),
    result: function (id) { var r = results[id]; if (!r || r.p) return null; delete results[id]; return JSON.stringify(r); },
    hasText: function (t) { return !!document.body && (document.body.innerText || '').indexOf(t) >= 0; }
  };
  Object.defineProperty(window, '__ncb', { value: api });
})();
