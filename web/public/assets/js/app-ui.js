/* Anoy panel — small progressive enhancement layer. No dependencies.
   Every feature is optional: without JavaScript the forms still post and the
   server still validates everything. All server text is inserted as text. */
(function () {
  'use strict';

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  function el(tag, attrs, text) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'className') node.className = attrs[k];
      else node.setAttribute(k, attrs[k]);
    });
    if (text != null) node.textContent = String(text);
    return node;
  }

  /* -------------------------------------------------------------- icons */
  // Icons come from a static, content-hashed SVG sprite (path in
  // <body data-icons>, cached for a year), drawn with DOM calls: the script
  // never builds SVG markup from strings.
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var sprite = document.body ? document.body.getAttribute('data-icons') : '';
  if (sprite) { var warm = new Image(); warm.src = sprite; }
  function icon(name, cls) {
    if (!sprite || !/^[a-z0-9-]+$/.test(name)) return document.createTextNode('');
    var svg = document.createElementNS(SVG_NS, 'svg');
    var attrs = {
      'class': 'i' + (cls ? ' ' + cls : ''), viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
      'stroke-width': '1.75', 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
      'aria-hidden': 'true', focusable: 'false',
    };
    Object.keys(attrs).forEach(function (k) { svg.setAttribute(k, attrs[k]); });
    var use = document.createElementNS(SVG_NS, 'use');
    use.setAttribute('href', sprite + '#' + name);
    use.setAttributeNS('http://www.w3.org/1999/xlink', 'xlink:href', sprite + '#' + name);
    svg.appendChild(use);
    return svg;
  }
  function iconButton(name, label, cls) {
    var b = el('button', { type: 'button', className: 'icon-btn' + (cls ? ' ' + cls : ''), 'aria-label': label, title: label });
    b.appendChild(icon(name));
    return b;
  }

  /* ------------------------------------------------------------- toasts */
  // opts.persistent: stays until removed by the caller (returns the node).
  function toast(message, isError, opts) {
    var host = $('.toasts');
    if (!host) { host = el('div', { className: 'toasts', 'aria-live': 'polite' }); document.body.appendChild(host); }
    var persistent = !!(opts && opts.persistent);
    var t = el('div', { className: 'toast' + (isError ? ' error' : '') + (persistent ? ' persistent' : '') });
    t.appendChild(icon(opts && opts.icon ? opts.icon : (isError ? 'circle-alert' : 'circle-check')));
    t.appendChild(el('span', {}, message));
    host.appendChild(t);
    if (persistent) return t;
    setTimeout(function () { t.style.opacity = '0'; t.style.transition = 'opacity .2s'; }, 2600);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2900);
    return t;
  }

  /* -------------------------------------------------------------- modal */
  function confirmDialog(title, detail, okLabel, danger) {
    return new Promise(function (resolve) {
      var back = el('div', { className: 'modal-backdrop', role: 'presentation' });
      var box = el('div', { className: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'modal-title' });
      box.appendChild(el('h3', { id: 'modal-title' }, title));
      if (detail) box.appendChild(el('p', {}, detail));
      var actions = el('div', { className: 'actions' });
      var cancel = el('button', { type: 'button', className: 'btn btn-secondary' }, 'Cancel');
      var ok = el('button', { type: 'button', className: 'btn ' + (danger ? 'btn-danger' : 'btn-primary') }, okLabel || 'Continue');
      actions.appendChild(cancel); actions.appendChild(ok); box.appendChild(actions); back.appendChild(box);
      var last = document.activeElement;
      function close(result) {
        document.removeEventListener('keydown', onKey);
        back.classList.remove('show');
        setTimeout(function () { if (back.parentNode) back.parentNode.removeChild(back); if (last && last.focus) last.focus(); }, 150);
        resolve(result);
      }
      function onKey(e) { if (e.key === 'Escape') close(false); }
      cancel.addEventListener('click', function () { close(false); });
      ok.addEventListener('click', function () { close(true); });
      back.addEventListener('click', function (e) { if (e.target === back) close(false); });
      document.addEventListener('keydown', onKey);
      document.body.appendChild(back);
      requestAnimationFrame(function () { back.classList.add('show'); ok.focus(); });
    });
  }

  /* ---------------------------------------------------------- busy state */
  // Spinner + label; after 6 s the label becomes "Still working…" so a slow
  // server never looks frozen.
  var SLOW_MS = 6000;
  function setBusy(btn, label) {
    if (!btn || btn.dataset.busy === '1') return;
    btn.dataset.busy = '1';
    btn.dataset.idleHtml = btn.innerHTML;
    btn.disabled = true;
    btn.textContent = '';
    btn.appendChild(el('span', { className: 'spinner', 'aria-hidden': 'true' }));
    if (!btn.classList.contains('icon-btn')) {
      var text = el('span', { className: 'busy-text' }, label || 'Working…');
      btn.appendChild(text);
      btn._slow = setTimeout(function () { if (btn.dataset.busy === '1') text.textContent = 'Still working…'; }, SLOW_MS);
    }
  }
  function clearBusy(btn) {
    if (!btn || btn.dataset.busy !== '1') return;
    clearTimeout(btn._slow);
    btn.innerHTML = btn.dataset.idleHtml || btn.innerHTML;
    btn.disabled = false;
    btn.dataset.busy = '';
  }
  // Back/forward cache restores a page with the spinner still showing.
  window.addEventListener('pageshow', function () { $$('[data-busy="1"]').forEach(clearBusy); });

  /* ---------------------------------------------------------- JSON fetch */
  function post(url, data) {
    var body = new URLSearchParams();
    Object.keys(data || {}).forEach(function (k) { body.append(k, data[k]); });
    var ctrl = window.AbortController ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 25000);
    return fetch(url, {
      method: 'POST', body: body, credentials: 'same-origin',
      headers: { Accept: 'application/json' }, signal: ctrl ? ctrl.signal : undefined
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (json) {
        if (!res.ok || json.ok === false) throw new Error(json.error || ('Request failed (' + res.status + ').'));
        return json;
      });
    }, function (err) {
      throw new Error(err && err.name === 'AbortError' ? 'The server took too long. Please try again.' : 'Network error. Check your connection.');
    }).then(function (v) { clearTimeout(timer); return v; }, function (e) { clearTimeout(timer); throw e; });
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (resolve, reject) {
      var area = el('textarea', { readonly: '', style: 'position:fixed;opacity:0' });
      area.value = text; document.body.appendChild(area); area.select();
      try { document.execCommand('copy') ? resolve() : reject(new Error('copy failed')); }
      catch (e) { reject(e); } finally { document.body.removeChild(area); }
    });
  }

  // Brief "copied" feedback on the button itself, plus a toast.
  // opts.label: an icon button briefly widens to show check + "Copied", so the
  // confirmation is obvious on a phone (not only the toast).
  function copied(btn, message, opts) {
    toast(message || 'Copied');
    if (!btn || btn.dataset.copied === '1') return;
    btn.dataset.copied = '1';
    var before = btn.innerHTML;
    var isIcon = btn.classList.contains('icon-btn');
    var label = !isIcon || (opts && opts.label);
    btn.textContent = '';
    btn.appendChild(icon('check'));
    if (label) btn.appendChild(isIcon ? el('span', { className: 'copied-label' }, 'Copied') : document.createTextNode('Copied'));
    btn.classList.add('ok');
    if (isIcon && label) btn.classList.add('labelled');
    setTimeout(function () {
      btn.innerHTML = before; btn.classList.remove('ok', 'labelled'); btn.dataset.copied = '';
    }, (opts && opts.ms) || 1400);
  }

  function randomId() {
    if (window.crypto && crypto.getRandomValues) {
      var a = new Uint8Array(16); crypto.getRandomValues(a);
      return Array.prototype.map.call(a, function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
    }
    return String(Date.now()) + Math.random().toString(16).slice(2);
  }

  /* ----------------------------------------------------------- generic */
  function initGeneric() {
    document.addEventListener('click', function (e) {
      var t = e.target.closest ? e.target : null;
      if (!t) return;
      if (t.closest('[data-nav-open]')) { document.body.classList.add('nav-open'); return; }
      if (t.closest('[data-nav-close]')) { document.body.classList.remove('nav-open'); return; }
      var dismiss = t.closest('[data-dismiss]');
      if (dismiss) { var a = dismiss.closest('.alert'); if (a) a.remove(); return; }
      var toggle = t.closest('[data-toggle-password]');
      if (toggle) {
        var input = document.getElementById(toggle.getAttribute('data-toggle-password'));
        if (input) {
          var show = input.type === 'password';
          input.type = show ? 'text' : 'password';
          toggle.classList.toggle('on', show);
          toggle.setAttribute('aria-pressed', show ? 'true' : 'false');
          toggle.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
          if (toggle.hasAttribute('title')) toggle.setAttribute('title', show ? 'Hide password' : 'Show password');
        }
        return;
      }
      var copy = t.closest('[data-copy-target]');
      if (copy) {
        var src = document.getElementById(copy.getAttribute('data-copy-target'));
        if (src) copyText(src.value || src.textContent).then(function () { copied(copy); }, function () {
          if (src.select) src.select();
          toast('Press Ctrl+C to copy', true);
        });
        return;
      }
      var copyT = t.closest('[data-copy-text]');
      if (copyT) {
        copyText(copyT.getAttribute('data-copy-text')).then(function () { copied(copyT); }, function () { toast('Copy failed', true); });
      }
    });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') document.body.classList.remove('nav-open'); });

    // Forms that need a confirmation first.
    $$('form[data-confirm]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        if (form.dataset.confirmed === '1') return;
        e.preventDefault();
        var btn = form.querySelector('[type=submit]');
        var danger = btn && btn.classList.contains('btn-danger');
        danger = danger || (btn && btn.classList.contains('danger'));
        var okLabel = btn ? (btn.getAttribute('title') || btn.textContent.trim() || 'Continue') : 'Continue';
        confirmDialog(form.getAttribute('data-confirm'), form.getAttribute('data-confirm-detail'), okLabel, danger)
          .then(function (ok) {
            if (!ok) return;
            form.dataset.confirmed = '1';
            setBusy(btn, 'Working…');
            form.submit();
          });
      });
    });

    // A switch that needs a confirmation when it is turned on (Maintenance).
    $$('input[data-confirm-on]').forEach(function (input) {
      var form = input.form;
      if (!form) return;
      var wasOn = input.checked;
      form.addEventListener('submit', function (e) {
        if (wasOn || !input.checked || form.dataset.confirmed === '1') return;
        e.preventDefault();
        e.stopImmediatePropagation();
        confirmDialog(input.getAttribute('data-confirm-on'), input.getAttribute('data-confirm-on-detail'), 'Turn on', false)
          .then(function (ok) {
            if (!ok) return;
            form.dataset.confirmed = '1';
            var btn = form.querySelector('[type=submit]');
            setBusy(btn, btn && btn.getAttribute('data-busy-label'));
            form.submit();
          });
      });
    });

    // Server forms: the "Save changes" bar only appears once something changed.
    $$('form[data-dirty-form]').forEach(function (form) {
      var bar = form.querySelector('[data-save-bar]');
      if (!bar) return;
      function snapshot() {
        return $$('input, textarea, select', form).map(function (f) {
          return f.name + '=' + (f.type === 'checkbox' || f.type === 'radio' ? f.checked : f.value);
        }).join('&');
      }
      var start = snapshot();
      function sync() { bar.hidden = snapshot() === start; }
      // A form that came back with an error keeps its button visible.
      if (!form.querySelector('.invalid')) sync();
      form.addEventListener('input', sync);
      form.addEventListener('change', sync);
    });

    // Empty-state actions that jump to a field on the same page.
    document.addEventListener('click', function (e) {
      var a = e.target.closest && e.target.closest('[data-focus]');
      if (!a) return;
      var f = document.getElementById(a.getAttribute('data-focus'));
      if (!f) return;
      e.preventDefault();
      f.scrollIntoView({ block: 'center' });
      f.focus({ preventScroll: true });
    });

    // A toast handed over from the previous page (App update publish/remove).
    try {
      var handed = sessionStorage.getItem('anoy.toast');
      if (handed) { sessionStorage.removeItem('anoy.toast'); toast(handed); }
    } catch (e) { /* storage off */ }

    // One submit per click: spinner + disabled button, no double charges.
    $$('form[data-busy-form]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        if (form.dataset.submitting === '1') { e.preventDefault(); return; }
        if (e.defaultPrevented) return;
        form.dataset.submitting = '1';
        var btn = e.submitter || form.querySelector('[type=submit]');
        setBusy(btn, btn && btn.getAttribute('data-busy-label'));
        setTimeout(function () { form.dataset.submitting = ''; clearBusy(btn); }, 30000);
      });
    });

    // Number steppers (- / +) next to numeric inputs.
    $$('[data-step]').forEach(function (b) {
      b.addEventListener('click', function () {
        var input = document.getElementById(b.getAttribute('data-target'));
        if (!input) return;
        var v = parseInt(input.value, 10); if (isNaN(v)) v = 0;
        var lo = Number(input.min || 1), hi = Number(input.max || 100);
        input.value = String(Math.min(hi, Math.max(lo, v + Number(b.getAttribute('data-step')))));
        input.dispatchEvent(new Event('input', { bubbles: true }));
      });
    });

    // Upper-case inputs keep the caret where it was.
    $$('input.upper').forEach(function (input) {
      input.addEventListener('input', function () {
        var pos = input.selectionStart;
        var up = input.value.toUpperCase();
        if (up !== input.value) { input.value = up; try { input.setSelectionRange(pos, pos); } catch (e) { /* type=email */ } }
      });
    });

    // Client-side table filter (users list).
    $$('[data-filter-table]').forEach(function (input) {
      var table = document.getElementById(input.getAttribute('data-filter-table'));
      if (!table) return;
      input.addEventListener('input', function () {
        var q = input.value.trim().toLowerCase();
        $$('tbody tr', table).forEach(function (row) {
          if (row.classList.contains('is-empty')) return;
          row.hidden = q && row.textContent.toLowerCase().indexOf(q) < 0;
        });
      });
    });

    // Success messages fade after a while; errors stay until dismissed.
    $$('.alert[data-flash="success"]').forEach(function (a) {
      setTimeout(function () { a.style.transition = 'opacity .3s'; a.style.opacity = '0'; setTimeout(function () { a.remove(); }, 320); }, 6000);
    });

    // Key detail page actions.
    $$('[data-key-action]').forEach(function (btn) {
      btn.addEventListener('click', function () { keyAction(btn.getAttribute('data-key-action'), btn.getAttribute('data-key'), btn); });
    });
  }

  function keyAction(action, key, btn, done) {
    var isDelete = action === 'delete';
    var title = isDelete ? 'Delete ' + key + '?' : 'Reset devices for ' + key + '?';
    var detail = isDelete
      ? 'The key stops working immediately. A never-used key is refunded. This cannot be undone.'
      : 'Every registered device is removed. The next device to sign in is registered again.';
    confirmDialog(title, detail, isDelete ? 'Delete' : 'Reset', isDelete).then(function (ok) {
      if (!ok) return;
      setBusy(btn, isDelete ? 'Deleting…' : 'Resetting…');
      post(isDelete ? '/keys/resetall' : '/keys/reset', { userkey: key }).then(function (res) {
        clearBusy(btn);
        if (isDelete) {
          toast(res.refunded ? 'Deleted and refunded ₹' + res.refund_total : 'Key deleted');
          var after = btn.getAttribute('data-after');
          if (after) setTimeout(function () { location.href = after; }, 600);
        } else toast('Devices reset');
        if (done) done();
      }, function (err) { clearBusy(btn); toast(err.message, true); });
    });
  }

  /* ---------------------------------------------------------- keys list */
  // Phone: 72 px rows (tap = edit, "⋯" = actions sheet). Desktop: the table
  // with the same actions. "Select" picks keys to copy or delete; each delete
  // is the normal per-key delete (POST /keys/resetall: ownership check and
  // refund on the server). The last answer for each query is kept for 60 s in
  // sessionStorage so the list paints instantly, then refreshes.
  var KEYS_CACHE = 'anoy.keys.';
  function clearKeysCache() {
    try {
      for (var i = sessionStorage.length - 1; i >= 0; i -= 1) {
        var k = sessionStorage.key(i);
        if (k && k.indexOf(KEYS_CACHE) === 0) sessionStorage.removeItem(k);
      }
    } catch (e) { /* storage off */ }
  }

  function sheet(title, items) {
    var back = el('div', { className: 'sheet-backdrop', role: 'presentation' });
    var box = el('div', { className: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': title });
    box.appendChild(el('div', { className: 'sheet-title' }, title));
    var last = document.activeElement;
    function close() {
      document.removeEventListener('keydown', onKey);
      back.classList.remove('show'); box.classList.remove('show');
      setTimeout(function () { [back, box].forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); }); if (last && last.focus) last.focus(); }, 260);
    }
    function onKey(e) { if (e.key === 'Escape') close(); }
    items.forEach(function (it) {
      var b = el('button', { type: 'button', className: 'sheet-item' + (it.danger ? ' danger' : '') });
      b.appendChild(icon(it.icon)); b.appendChild(el('span', {}, it.label));
      b.addEventListener('click', function () { close(); setTimeout(function () { it.run(b); }, 10); });
      box.appendChild(b);
    });
    var cancel = el('button', { type: 'button', className: 'btn btn-secondary' }, 'Cancel');
    cancel.addEventListener('click', close);
    box.appendChild(cancel);
    back.addEventListener('click', close);
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back); document.body.appendChild(box);
    requestAnimationFrame(function () { back.classList.add('show'); box.classList.add('show'); var f = box.querySelector('.sheet-item'); if (f) f.focus(); });
  }

  function initKeysList() {
    var body = document.getElementById('keys-body');
    var list = document.getElementById('keys-list');
    var card = document.getElementById('keys-card');
    if (!body || !list || !card || !window.ANOY_KEYS) return;
    var owner = !!window.ANOY_KEYS.owner;
    var who = String(window.ANOY_KEYS.user || '');
    var state = { page: 1, per: 25, q: '', status: '', pages: 1, seq: 0, rows: [], selecting: false };
    var picked = {};
    var search = document.getElementById('keys-search');
    var filter = document.getElementById('keys-status');
    var prev = document.getElementById('keys-prev');
    var next = document.getElementById('keys-next');
    var range = document.getElementById('keys-range');
    var stateBox = document.getElementById('keys-state');
    var selectBtn = document.getElementById('keys-select');
    var selCancel = document.getElementById('keys-select-cancel');
    var selCount = document.getElementById('keys-selected-count');
    var copySel = document.getElementById('keys-copy-selected');
    var delSel = document.getElementById('keys-delete-selected');
    var cleanExp = document.getElementById('keys-cleanup-expired');
    var cleanUnused = document.getElementById('keys-cleanup-unused');
    var labels = { active: 'Active', unused: 'Unused', expired: 'Expired', blocked: 'Blocked' };
    var table = document.getElementById('keys-table');
    var cols = table ? table.querySelectorAll('thead th').length : 8;

    function plural(n, one, many) { return n + ' ' + (n === 1 ? one : many); }

    // ---- states (empty / no match / error)
    function showState(kind) {
      stateBox.textContent = '';
      card.classList.toggle('has-state', !!kind);
      stateBox.hidden = !kind;
      if (!kind) return;
      body.textContent = ''; list.textContent = '';
      var box = el('div', { className: 'empty' });
      var spec = {
        empty: ['key-round', 'No keys yet', 'Generate keys to hand out licences.'],
        nomatch: ['search', 'No matching keys', 'Try a different search or filter.'],
        error: ['wifi-off', 'Couldn\u2019t load keys', 'Check your connection and try again.']
      }[kind];
      box.appendChild(icon(spec[0]));
      box.appendChild(el('b', {}, spec[1]));
      box.appendChild(document.createTextNode(spec[2]));
      var action;
      if (kind === 'empty') {
        action = el('a', { className: 'btn btn-primary', href: '/keys/generate' });
        action.appendChild(icon('plus')); action.appendChild(document.createTextNode('Generate keys'));
      } else if (kind === 'nomatch') {
        action = el('button', { type: 'button', className: 'btn btn-secondary' }, 'Clear filters');
        action.addEventListener('click', function () { search.value = ''; filter.value = ''; state.q = ''; state.status = ''; state.page = 1; load(); });
      } else {
        action = el('button', { type: 'button', className: 'btn btn-secondary' });
        action.appendChild(icon('rotate-ccw')); action.appendChild(document.createTextNode('Try again'));
        action.addEventListener('click', function () { load(); });
      }
      box.appendChild(action);
      stateBox.appendChild(box);
    }
    function skeleton() {
      if (state.rows.length) { body.style.opacity = '.55'; list.style.opacity = '.55'; return; }
      showState(null);
      body.textContent = ''; list.textContent = '';
      [62, 48, 56, 40, 52, 46].forEach(function (w) {
        var tr = el('tr', { className: 'skeleton' });
        var td = el('td', { colspan: String(cols) });
        td.appendChild(el('span', { className: 'sk', style: 'width:' + w + '%' }));
        tr.appendChild(td); body.appendChild(tr);
        var li = el('li', { className: 'key-row skeleton' });
        var m = el('div', { className: 'kr-main' });
        m.appendChild(el('span', { className: 'sk', style: 'width:' + (w + 4) + '%' }));
        m.appendChild(el('span', { className: 'sk', style: 'width:' + Math.min(90, w + 20) + '%' }));
        li.appendChild(m); list.appendChild(li);
      });
    }

    // Message the seller pastes to the customer: the key plus a short expiry
    // reminder built only from what the row already shows.
    function customerText(r) {
      var lines = ['Your Anoy key: ' + r.user_key];
      if (r.state === 'expired') {
        lines.push((r.duration ? 'Duration: ' + r.duration + '. ' : '') + 'This key expired' + (r.expires ? ' on ' + r.expires : '') + '. Ask me to renew it.');
      } else if (r.state === 'blocked') {
        lines.push('This key is blocked. Message me before using it.');
      } else if (r.expires) {
        lines.push((r.duration ? 'Duration: ' + r.duration + '. ' : '') + 'Expires ' + r.expires + '.');
        lines.push('Renew before it runs out to keep playing.');
      } else {
        lines.push((r.duration ? 'Duration: ' + r.duration + ', starting' : 'Time starts') + ' when you first sign in.');
        lines.push('Renew before it runs out to keep playing.');
      }
      return lines.join('\n');
    }
    function copyKey(r, btn) {
      copyText(r.user_key).then(function () { copied(btn, 'Key copied'); }, function () { toast('Copy failed', true); });
    }
    function editUrl(r) { return '/keys/' + encodeURIComponent(r.id); }
    function afterChange() { clearKeysCache(); load(); }
    function openSheet(r, origin) {
      sheet(r.user_key, [
        { icon: 'copy', label: 'Copy key', run: function () { copyKey(r, null); } },
        { icon: 'rotate-ccw', label: 'Reset devices', run: function () { keyAction('reset', r.user_key, origin, afterChange); } },
        { icon: 'pencil', label: 'Edit', run: function () { location.href = editUrl(r); } },
        { icon: 'trash-2', label: 'Delete key', danger: true, run: function () { keyAction('delete', r.user_key, origin, afterChange); } }
      ]);
    }

    // ---- selection
    function pickedKeys() { return Object.keys(picked); }
    function syncSelection() {
      var n = pickedKeys().length;
      selCount.textContent = n + ' selected';
      copySel.disabled = !n; delSel.disabled = !n;
      $$('[data-pick]', card).forEach(function (cb) {
        cb.checked = !!picked[cb.getAttribute('data-pick')];
        var row = cb.closest('.key-row, tr');
        if (row) row.classList.toggle('selected', cb.checked);
      });
    }
    function setSelecting(on) {
      state.selecting = on;
      card.classList.toggle('selecting', on);
      document.body.classList.toggle('has-bottom-bar', on);
      if (!on) picked = {};
      syncSelection();
    }
    function togglePick(key) {
      if (picked[key]) delete picked[key]; else picked[key] = true;
      syncSelection();
    }
    function pickBox(r) {
      var lab = el('label', { className: 'pick', title: 'Select ' + r.user_key });
      var cb = el('input', { type: 'checkbox', 'data-pick': r.user_key, 'aria-label': 'Select ' + r.user_key });
      cb.checked = !!picked[r.user_key];
      cb.addEventListener('click', function (e) { e.stopPropagation(); });
      cb.addEventListener('change', function () { if (cb.checked) picked[r.user_key] = true; else delete picked[r.user_key]; syncSelection(); });
      lab.addEventListener('click', function (e) { e.stopPropagation(); });
      lab.appendChild(cb);
      return lab;
    }

    // ---- rows
    function metaLine(r) {
      var parts = [];
      if (r.duration) parts.push(String(r.duration).toLowerCase());
      parts.push(r.devices + ' of ' + plural(Number(r.max_devices) || 0, 'device', 'devices'));
      return parts.join(' · ');
    }
    function subLine(r) {
      var s = r.expires_short ? (r.state === 'expired' ? 'Expired ' : 'Expires ') + r.expires_short : 'Starts on first use';
      if (owner && r.registrator && r.registrator !== who) s += ' · by ' + r.registrator;
      return s;
    }
    function phoneRow(r) {
      var li = el('li', { className: 'key-row' });
      li.appendChild(pickBox(r));
      var main = el('div', { className: 'kr-main' });
      main.appendChild(el('div', { className: 'kr-key' }, r.user_key));
      var meta = el('div', { className: 'kr-meta' });
      meta.appendChild(el('span', { className: 'status ' + r.state }, labels[r.state] || r.state));
      meta.appendChild(el('span', {}, '\u00b7 ' + metaLine(r)));
      main.appendChild(meta);
      main.appendChild(el('div', { className: 'kr-sub' }, subLine(r)));
      li.appendChild(main);
      var copy = iconButton('copy', 'Copy ' + r.user_key, 'row-only');
      copy.addEventListener('click', function (e) { e.stopPropagation(); copyKey(r, copy); });
      var more = iconButton('ellipsis', 'More actions for ' + r.user_key, 'row-only');
      more.addEventListener('click', function (e) { e.stopPropagation(); openSheet(r, more); });
      li.appendChild(copy); li.appendChild(more);
      li.addEventListener('click', function () {
        if (state.selecting) togglePick(r.user_key);
        else location.href = editUrl(r);
      });
      return li;
    }
    function cell(tr, label, text, cls) {
      var td = el('td', { 'data-label': label }, text);
      if (cls) td.className = cls;
      tr.appendChild(td); return td;
    }
    function tableRow(r) {
      var tr = el('tr');
      var pc = el('td', { className: 'pick-cell' }); pc.appendChild(pickBox(r)); tr.appendChild(pc);
      var kc = cell(tr, 'Key', '', 'primary-cell');
      var kt = el('span', { className: 'keytext' }, r.user_key);
      var copy = iconButton('copy', 'Copy ' + r.user_key);
      copy.addEventListener('click', function () { copyKey(r, copy); });
      kt.appendChild(copy);
      kc.appendChild(kt);
      cell(tr, 'Duration', r.duration);
      cell(tr, 'Devices', r.devices + ' / ' + r.max_devices);
      var st = cell(tr, 'Status', '');
      st.appendChild(el('span', { className: 'status ' + r.state }, labels[r.state] || r.state));
      cell(tr, 'Expires', r.expires || 'Starts on first use');
      if (owner) cell(tr, 'Created by', r.registrator);
      var act = el('td', { className: 'actions-cell' });
      var group = el('span', { className: 'row-actions' });
      var share = iconButton('send', 'Copy for customer');
      share.addEventListener('click', function () {
        copyText(customerText(r)).then(function () { copied(share, 'Copied for customer \u2014 paste it in your chat', { label: true, ms: 1800 }); },
          function () { toast('Copy failed', true); });
      });
      var edit = el('a', { className: 'icon-btn', href: editUrl(r), 'aria-label': 'Edit ' + r.user_key, title: 'Edit' });
      edit.appendChild(icon('pencil'));
      var reset = iconButton('rotate-ccw', 'Reset devices');
      reset.addEventListener('click', function () { keyAction('reset', r.user_key, reset, afterChange); });
      var del = iconButton('trash-2', 'Delete key', 'danger');
      del.addEventListener('click', function () { keyAction('delete', r.user_key, del, afterChange); });
      group.appendChild(share); group.appendChild(edit); group.appendChild(reset); group.appendChild(del);
      act.appendChild(group);
      tr.appendChild(act);
      tr.addEventListener('click', function (e) {
        if (state.selecting && !e.target.closest('button, a, label')) togglePick(r.user_key);
      });
      return tr;
    }
    function render(data) {
      var rows = data.rows || [];
      state.rows = rows;
      body.textContent = ''; list.textContent = '';
      body.style.opacity = ''; list.style.opacity = '';
      // Expired / Unused filter: one quiet "N expired keys · Delete all" row.
      var cleanup = !state.q && data.total > 0 ? state.status : '';
      cleanExp.hidden = cleanup !== 'expired';
      cleanUnused.hidden = cleanup !== 'unused';
      if (cleanup === 'expired') $('[data-cleanup-label]', cleanExp).textContent = plural(data.total, 'expired key', 'expired keys');
      if (cleanup === 'unused') $('[data-cleanup-label]', cleanUnused).textContent = plural(data.total, 'unused key', 'unused keys');
      if (!rows.length) { showState(state.q || state.status ? 'nomatch' : 'empty'); }
      else {
        showState(null);
        rows.forEach(function (r) { list.appendChild(phoneRow(r)); body.appendChild(tableRow(r)); });
      }
      syncSelection();
      var from = data.total ? (data.page - 1) * data.per + 1 : 0;
      var to = Math.min(data.total, data.page * data.per);
      range.textContent = data.total ? from + '\u2013' + to + ' of ' + data.total : '';
      prev.disabled = state.page <= 1;
      next.disabled = state.page >= state.pages;
    }
    function cacheKey(url) { return KEYS_CACHE + who + '|' + url; }
    function readCache(url) {
      try {
        var hit = JSON.parse(sessionStorage.getItem(cacheKey(url)) || 'null');
        if (hit && Date.now() - hit.t < 60000) return hit.data;
      } catch (e) { /* ignore */ }
      return null;
    }
    function writeCache(url, data) {
      try { sessionStorage.setItem(cacheKey(url), JSON.stringify({ t: Date.now(), data: data })); } catch (e) { /* full */ }
    }
    function load() {
      var seq = ++state.seq;
      var url = '/keys/api?page=' + state.page + '&per=' + state.per +
        '&q=' + encodeURIComponent(state.q) + '&status=' + encodeURIComponent(state.status);
      prev.disabled = true; next.disabled = true;
      var cached = readCache(url);
      if (cached) { state.pages = cached.pages || 1; render(cached); } else skeleton();
      fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
        .then(function (res) {
          if (res.status === 401 || res.redirected) { location.href = '/login'; throw new Error('Logged out'); }
          return res.json().then(function (json) {
            if (!res.ok || !json.ok) throw new Error(json.error || 'Could not load keys.');
            return json;
          });
        })
        .then(function (data) {
          if (seq !== state.seq) return;
          state.pages = data.pages || 1;
          if (state.page > state.pages && data.total) { state.page = state.pages; return load(); }
          writeCache(url, data);
          render(data);
        })
        .catch(function () {
          if (seq !== state.seq) return;
          body.style.opacity = ''; list.style.opacity = '';
          if (cached) { toast('Couldn\u2019t refresh keys. Showing the last list.', true); return; }
          state.rows = [];
          showState('error');
        });
    }

    // ---- select mode actions
    selectBtn.addEventListener('click', function () { setSelecting(true); });
    selCancel.addEventListener('click', function () { setSelecting(false); });
    copySel.addEventListener('click', function () {
      var keys = pickedKeys();
      if (!keys.length) return;
      copyText(keys.join('\n')).then(function () { toast(plural(keys.length, 'key', 'keys') + ' copied'); }, function () { toast('Copy failed', true); });
    });
    delSel.addEventListener('click', function () {
      var keys = pickedKeys();
      if (!keys.length) return;
      confirmDialog('Delete ' + plural(keys.length, 'key', 'keys') + '?',
        'They stop working immediately. Never-used keys are refunded. This cannot be undone.', 'Delete', true).then(function (ok) {
        if (!ok) return;
        setBusy(delSel, 'Deleting…');
        var done = 0, failed = 0, refund = 0, lastError = '';
        // One at a time, through the same per-key delete as the row action.
        (function nextKey(i) {
          if (i >= keys.length) {
            clearBusy(delSel);
            if (failed) toast(done + ' deleted, ' + failed + ' not deleted. ' + lastError, true);
            else toast((done === 1 ? 'Key deleted' : done + ' keys deleted') + (refund ? ' · refunded \u20b9' + refund : ''));
            setSelecting(false);
            afterChange();
            return;
          }
          post('/keys/resetall', { userkey: keys[i] }).then(function (res) {
            done += 1; refund += Number(res.refund_total) || 0;
          }, function (err) { failed += 1; lastError = err.message; }).then(function () { nextKey(i + 1); });
        })(0);
      });
    });

    var timer;
    search.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () { state.q = search.value.trim(); state.page = 1; state.rows = []; load(); }, 250);
    });
    filter.addEventListener('change', function () { state.status = filter.value; state.page = 1; state.rows = []; load(); });
    prev.addEventListener('click', function () { if (state.page > 1) { state.page -= 1; load(); } });
    next.addEventListener('click', function () { if (state.page < state.pages) { state.page += 1; load(); } });
    // Deleting from the clean-up row or elsewhere: drop the cached lists.
    $$('.keys-cleanup form').forEach(function (f) { f.addEventListener('submit', clearKeysCache); });
    if ($('.alert', document)) clearKeysCache();
    load();
  }

  /* ------------------------------------------------------ generate keys */
  function initGenerate() {
    var form = document.getElementById('generate-form');
    if (!form) return;
    var prices = {}, prefixes = {};
    try { prices = JSON.parse(form.getAttribute('data-prices') || '{}'); } catch (e) { /* keep empty */ }
    try { prefixes = JSON.parse(form.getAttribute('data-prefixes') || '{}'); } catch (e) { /* keep empty */ }
    var unlimited = form.getAttribute('data-unlimited') === '1';
    var saldo = Number(form.getAttribute('data-saldo') || 0);
    var min = Number(form.getAttribute('data-min') || 4), max = Number(form.getAttribute('data-max') || 10);
    // Duration is a group of radio cards; fall back to a <select> if present.
    var duration = {
      get value() {
        var r = form.querySelector('[name=duration]:checked') || form.querySelector('select[name=duration]');
        return r ? r.value : '';
      }
    };
    var devices = document.getElementById('max_devices');
    var count = document.getElementById('count');
    var customInput = document.getElementById('custom_key');
    var countField = document.getElementById('count-field');
    var customField = document.getElementById('custom-field');
    var prefixEl = document.getElementById('custom-prefix');
    var example = document.getElementById('key-example');
    var unitEl = document.getElementById('unit-price');
    var totalEl = document.getElementById('total-price');
    var balanceErr = document.getElementById('balance-error');
    var submit = document.getElementById('generate-submit');
    var barTotal = document.getElementById('gen-bar-total');
    var barSubmit = document.getElementById('gen-bar-submit');
    if (barSubmit) document.body.classList.add('has-bottom-bar');
    var reqId = document.getElementById('request_id');
    if (reqId && !reqId.value) reqId.value = randomId();

    function isCustom() { var r = form.querySelector('[name=custom]:checked'); return !!(r && r.value === '1'); }
    function inr(n) { return '₹' + Number(n || 0).toLocaleString('en-IN'); }
    function int(input, fallback) { var n = parseInt(input && input.value, 10); return isNaN(n) ? fallback : n; }
    function suffix() {
      var prefix = prefixes[duration.value] || '';
      var v = (customInput.value || '').toUpperCase().replace(/\s+/g, '');
      if (prefix && v.indexOf(prefix) === 0) v = v.slice(prefix.length);
      return v;
    }
    function update() {
      var custom = isCustom();
      countField.hidden = custom;
      customField.hidden = !custom;
      var prefix = prefixes[duration.value] || '';
      prefixEl.textContent = prefix;
      var n = custom ? 1 : Math.max(0, int(count, 1));
      var d = Math.max(0, int(devices, 1));
      var unit = Number(prices[duration.value] || 0) * d;
      var total = unit * n;
      var s = custom ? suffix() : '';
      example.textContent = prefix + (custom ? (s || '…') : 'XXXXX');
      unitEl.textContent = unlimited ? 'Free' : inr(unit);
      totalEl.textContent = unlimited ? 'Free · ' + n + ' key' + (n === 1 ? '' : 's') : inr(total);
      if (barTotal) barTotal.textContent = totalEl.textContent;
      var short = !unlimited && total > saldo;
      balanceErr.hidden = !short;
      var validCustom = !custom || new RegExp('^[A-Z0-9]{' + min + ',' + max + '}$').test(s);
      var validCount = custom || (n >= 1 && n <= 100);
      submit.disabled = short || !validCustom || !validCount || d < 1 || d > 100;
      if (barSubmit && barSubmit.dataset.busy !== '1') barSubmit.disabled = submit.disabled;
    }
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    update();
    var out = document.getElementById('generated-keys');
    if (out) out.scrollIntoView({ block: 'nearest' });
  }

  /* -------------------------------------------------------------- shell */
  // Fast navigation: the tab lights up on pointerdown, a thin top bar shows
  // after 120 ms, and internal pages are prefetched on pointerdown/touchstart
  // (Home/Keys/Settings also when idle). Never prefetched: /logout, downloads,
  // data-changing GETs and other sites.
  var NO_PREFETCH = /^\/(logout|keys\/(reset|resetall|deleteexp|deleteunused|download)|admin\/user\/delete|api\/|connect)(\/|$|\?)/i;
  var prefetched = {};
  function prefetchable(a) {
    if (!a || !a.href || a.hasAttribute('download') || a.hasAttribute('data-no-prefetch')) return null;
    if (a.target && a.target !== '_self') return null;
    var u;
    try { u = new URL(a.href, location.href); } catch (e) { return null; }
    if (u.origin !== location.origin || !/^https?:$/.test(u.protocol)) return null;
    if (NO_PREFETCH.test(u.pathname)) return null;
    if (u.pathname === location.pathname && u.search === location.search) return null;
    return u.pathname + u.search;
  }
  var specRules = typeof HTMLScriptElement !== 'undefined' && HTMLScriptElement.supports && HTMLScriptElement.supports('speculationrules');
  function prefetch(path) {
    if (!path || prefetched[path] || NO_PREFETCH.test(path)) return;
    prefetched[path] = 1;
    if (specRules) {
      var s = document.createElement('script');
      s.type = 'speculationrules';
      s.textContent = JSON.stringify({ prefetch: [{ source: 'list', urls: [path], eagerness: 'immediate' }] });
      document.head.appendChild(s);
    } else {
      var l = document.createElement('link');
      l.rel = 'prefetch'; l.href = path;
      document.head.appendChild(l);
    }
  }
  var progress = (function () {
    var bar, timer;
    return {
      start: function () {
        bar = bar || $('.nav-progress');
        if (!bar) return;
        clearTimeout(timer);
        timer = setTimeout(function () { bar.classList.add('run'); }, 120);
      },
      stop: function () {
        clearTimeout(timer);
        if (bar) bar.classList.remove('run');
      }
    };
  })();
  function initShell() {
    function onPress(e) {
      var a = e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      prefetch(prefetchable(a));
      var tab = a.closest('.tabbar, .sidebar');
      if (tab && !a.classList.contains('tab-cta')) {
        $$('a.active', tab).forEach(function (x) { x.classList.remove('active'); });
        a.classList.add('active');
      }
    }
    document.addEventListener('pointerdown', onPress, { passive: true });
    document.addEventListener('touchstart', onPress, { passive: true });
    document.addEventListener('click', function (e) {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      var a = e.target.closest && e.target.closest('a[href]');
      if (!a || a.hasAttribute('download') || (a.target && a.target !== '_self')) return;
      var u;
      try { u = new URL(a.href, location.href); } catch (err) { return; }
      if (u.origin !== location.origin || /\/keys\/download/.test(u.pathname)) return;
      if (u.pathname === location.pathname && u.search === location.search && u.hash) return;
      progress.start();
    });
    document.addEventListener('submit', function (e) {
      // Runs after the form handlers: a cancelled submit shows no bar.
      setTimeout(function () { if (!e.defaultPrevented && e.target.getAttribute('method') === 'post' && !e.target.hasAttribute('data-ajax')) progress.start(); }, 0);
    });
    window.addEventListener('pageshow', function () {
      progress.stop();
      // Restore the real active tab after back/forward.
      $$('.tabbar a.active, .sidebar a.active').forEach(function (x) { if (x.getAttribute('data-was-active') !== '1') x.classList.remove('active'); });
      $$('[data-was-active="1"]').forEach(function (x) { x.classList.add('active'); });
    });
    $$('.tabbar a.active, .sidebar a.active').forEach(function (x) { x.setAttribute('data-was-active', '1'); });

    // Idle prefetch of the three main pages.
    if ($('.tabbar')) {
      var idle = window.requestIdleCallback || function (fn) { return setTimeout(fn, 1200); };
      idle(function () { ['/dashboard', '/keys', '/settings'].forEach(function (p) { if (p !== location.pathname) prefetch(p); }); });
    }

    // Offline: one persistent toast until the connection is back.
    var offlineToast = null;
    function net() {
      if (!navigator.onLine && !offlineToast) {
        offlineToast = toast('You\u2019re offline. Changes can\u2019t be saved.', true, { persistent: true, icon: 'wifi-off' });
      } else if (navigator.onLine && offlineToast) {
        if (offlineToast.parentNode) offlineToast.parentNode.removeChild(offlineToast);
        offlineToast = null;
      }
    }
    window.addEventListener('online', net);
    window.addEventListener('offline', net);
    if (navigator.onLine === false) net();

    // Autofocus only with a mouse / trackpad (a phone keyboard would cover the form).
    var auto = $('[data-autofocus]');
    if (auto && window.matchMedia && window.matchMedia('(pointer: fine)').matches) auto.focus();

    // Keyboard open on a phone: hide the bottom bars so the field stays visible.
    var vv = window.visualViewport;
    if (vv) {
      var base = window.innerHeight;
      vv.addEventListener('resize', function () {
        if (vv.height > base) base = vv.height;
        document.body.classList.toggle('kb-open', base - vv.height > 140);
      });
    }
  }

  function ready(fn) {
    if (document.readyState !== 'loading') fn(); else document.addEventListener('DOMContentLoaded', fn);
  }
  ready(function () {
    initGeneric();
    initShell();
    initKeysList();
    initGenerate();
  });

  window.AnoyUI = { icon: icon, toast: toast, confirm: confirmDialog, setBusy: setBusy, clearBusy: clearBusy, post: post };
})();
