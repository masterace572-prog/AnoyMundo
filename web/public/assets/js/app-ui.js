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

  /* ------------------------------------------------------------- toasts */
  function toast(message, isError) {
    var host = $('.toasts');
    if (!host) { host = el('div', { className: 'toasts', 'aria-live': 'polite' }); document.body.appendChild(host); }
    var t = el('div', { className: 'toast' + (isError ? ' error' : '') }, message);
    host.appendChild(t);
    setTimeout(function () { t.style.opacity = '0'; t.style.transition = 'opacity .2s'; }, 2600);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2900);
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
  function setBusy(btn, label) {
    if (!btn || btn.dataset.busy === '1') return;
    btn.dataset.busy = '1';
    btn.dataset.idleHtml = btn.innerHTML;
    btn.disabled = true;
    btn.textContent = '';
    btn.appendChild(el('span', { className: 'spinner', 'aria-hidden': 'true' }));
    btn.appendChild(document.createTextNode(label || 'Working…'));
  }
  function clearBusy(btn) {
    if (!btn || btn.dataset.busy !== '1') return;
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
          toggle.textContent = show ? 'Hide' : 'Show';
        }
        return;
      }
      var copy = t.closest('[data-copy-target]');
      if (copy) {
        var src = document.getElementById(copy.getAttribute('data-copy-target'));
        if (src) copyText(src.value || src.textContent).then(function () { toast('Copied'); }, function () {
          if (src.select) src.select();
          toast('Press Ctrl+C to copy', true);
        });
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
        confirmDialog(form.getAttribute('data-confirm'), form.getAttribute('data-confirm-detail'), btn ? btn.textContent.trim() : 'Continue', danger)
          .then(function (ok) {
            if (!ok) return;
            form.dataset.confirmed = '1';
            setBusy(btn, 'Working…');
            form.submit();
          });
      });
    });

    // One submit per click: spinner + disabled button, no double charges.
    $$('form[data-busy-form]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        if (form.dataset.submitting === '1') { e.preventDefault(); return; }
        if (e.defaultPrevented) return;
        form.dataset.submitting = '1';
        var btn = form.querySelector('[type=submit]');
        setBusy(btn, btn && btn.getAttribute('data-busy-label'));
        setTimeout(function () { form.dataset.submitting = ''; clearBusy(btn); }, 30000);
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
  function initKeysList() {
    var body = document.getElementById('keys-body');
    if (!body || !window.ANOY_KEYS) return;
    var owner = !!window.ANOY_KEYS.owner;
    var state = { page: 1, per: 25, q: '', status: '', pages: 1, seq: 0 };
    var search = document.getElementById('keys-search');
    var filter = document.getElementById('keys-status');
    var prev = document.getElementById('keys-prev');
    var next = document.getElementById('keys-next');
    var range = document.getElementById('keys-range');
    var cols = owner ? 7 : 6;
    var labels = { active: 'In use', unused: 'Unused', expired: 'Expired', blocked: 'Blocked' };

    function message(text) {
      body.textContent = '';
      var tr = el('tr', { className: 'is-empty' });
      tr.appendChild(el('td', { colspan: String(cols) }, text));
      body.appendChild(tr);
    }
    function cell(tr, label, text, cls) {
      var td = el('td', { 'data-label': label }, text);
      if (cls) td.className = cls;
      tr.appendChild(td); return td;
    }
    function render(rows) {
      body.textContent = '';
      if (!rows.length) return message(state.q || state.status ? 'No keys match.' : 'No keys yet. Generate your first key.');
      rows.forEach(function (r) {
        var tr = el('tr');
        cell(tr, 'Key', r.user_key, 'key');
        cell(tr, 'Duration', r.duration);
        cell(tr, 'Devices', r.devices + ' / ' + r.max_devices);
        var st = cell(tr, 'Status', '');
        st.appendChild(el('span', { className: 'status ' + r.state }, labels[r.state] || r.state));
        cell(tr, 'Expires', r.expires || 'Not started');
        if (owner) cell(tr, 'Created by', r.registrator);
        var act = el('td', { className: 'actions-cell' });
        var copy = el('button', { type: 'button', className: 'btn btn-plain btn-sm' }, 'Copy');
        copy.addEventListener('click', function () { copyText(r.user_key).then(function () { toast('Copied'); }, function () { toast('Copy failed', true); }); });
        var edit = el('a', { className: 'btn btn-secondary btn-sm', href: '/keys/' + encodeURIComponent(r.id) }, 'Edit');
        var reset = el('button', { type: 'button', className: 'btn btn-secondary btn-sm' }, 'Reset');
        reset.addEventListener('click', function () { keyAction('reset', r.user_key, reset, load); });
        var del = el('button', { type: 'button', className: 'btn btn-danger btn-sm' }, 'Delete');
        del.addEventListener('click', function () { keyAction('delete', r.user_key, del, load); });
        [copy, edit, reset, del].forEach(function (b, i) { if (i) act.appendChild(document.createTextNode(' ')); act.appendChild(b); });
        tr.appendChild(act);
        body.appendChild(tr);
      });
    }
    function load() {
      var seq = ++state.seq;
      var url = '/keys/api?page=' + state.page + '&per=' + state.per +
        '&q=' + encodeURIComponent(state.q) + '&status=' + encodeURIComponent(state.status);
      prev.disabled = true; next.disabled = true;
      fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
        .then(function (res) {
          if (res.status === 401 || res.redirected) { location.href = '/login'; throw new Error('Signed out'); }
          return res.json().then(function (json) {
            if (!res.ok || !json.ok) throw new Error(json.error || 'Could not load keys.');
            return json;
          });
        })
        .then(function (data) {
          if (seq !== state.seq) return;
          state.pages = data.pages || 1;
          if (state.page > state.pages && data.total) { state.page = state.pages; return load(); }
          render(data.rows || []);
          var from = data.total ? (data.page - 1) * data.per + 1 : 0;
          var to = Math.min(data.total, data.page * data.per);
          range.textContent = data.total ? from + '–' + to + ' of ' + data.total : '';
          prev.disabled = state.page <= 1;
          next.disabled = state.page >= state.pages;
        })
        .catch(function (err) {
          if (seq !== state.seq) return;
          message(err && err.message === 'Failed to fetch' ? 'Network error. Check your connection and try again.' : (err.message || 'Could not load keys.'));
        });
    }
    var timer;
    search.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () { state.q = search.value.trim(); state.page = 1; load(); }, 250);
    });
    filter.addEventListener('change', function () { state.status = filter.value; state.page = 1; load(); });
    prev.addEventListener('click', function () { if (state.page > 1) { state.page -= 1; load(); } });
    next.addEventListener('click', function () { if (state.page < state.pages) { state.page += 1; load(); } });
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
    var duration = form.querySelector('[name=duration]');
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
      var short = !unlimited && total > saldo;
      balanceErr.hidden = !short;
      var validCustom = !custom || new RegExp('^[A-Z0-9]{' + min + ',' + max + '}$').test(s);
      var validCount = custom || (n >= 1 && n <= 100);
      submit.disabled = short || !validCustom || !validCount || d < 1 || d > 100;
    }
    form.addEventListener('input', update);
    form.addEventListener('change', update);
    update();
    var out = document.getElementById('generated-keys');
    if (out) out.scrollIntoView({ block: 'nearest' });
  }

  function ready(fn) {
    if (document.readyState !== 'loading') fn(); else document.addEventListener('DOMContentLoaded', fn);
  }
  ready(function () {
    initGeneric();
    initKeysList();
    initGenerate();
  });

  window.AnoyUI = { toast: toast, confirm: confirmDialog, setBusy: setBusy, clearBusy: clearBusy, post: post };
})();
