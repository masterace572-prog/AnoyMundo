/* Progressive enhancement for the flat responsive layout.
   No framework dependency. Class hooks only: no form fields, names, actions or
   server routes are touched here. */
(function () {
  'use strict';

  function content() {
    return document.getElementById('main-content');
  }

  /* Give every table a horizontal scroll wrapper on wide screens. */
  function wrapTables() {
    var main = content();
    if (!main) return;
    var tables = main.querySelectorAll('table');
    Array.prototype.forEach.call(tables, function (table) {
      var parent = table.parentElement;
      if (!parent) return;
      // DataTables and several views already provide their own scroll wrapper.
      if (parent.closest('.table-scroll, .dataTables_wrapper')) return;
      if (parent.classList.contains('overflow-x-auto')) return;
      var style = window.getComputedStyle(parent);
      if (style.overflowX === 'auto' || style.overflowX === 'scroll') return;
      var wrapper = document.createElement('div');
      wrapper.className = 'table-scroll';
      parent.insertBefore(wrapper, table);
      wrapper.appendChild(table);
    });
  }

  /* Copy the column heading into every cell so CSS can turn rows into
     labelled cards below 800px. DataTables injects its rows after load, so the
     same pass runs from a MutationObserver. */
  function labelTableCells() {
    var main = content();
    if (!main) return;
    Array.prototype.forEach.call(main.querySelectorAll('table'), function (table) {
      var heads = table.querySelectorAll('thead th');
      if (!heads.length) return;
      var labels = Array.prototype.map.call(heads, function (th) {
        return (th.textContent || '').replace(/\s+/g, ' ').trim();
      });
      Array.prototype.forEach.call(table.querySelectorAll('tbody tr'), function (row) {
        Array.prototype.forEach.call(row.cells, function (cell, i) {
          if (!cell.hasAttribute('data-label')) {
            cell.setAttribute('data-label', labels[i] || '');
          }
        });
      });
    });
  }

  /* Indian digit grouping for the saldo figure, in both the sidebar and the
     top bar. The raw number stays in the markup as a no-JS fallback. */
  function formatSaldo() {
    var nodes = document.querySelectorAll('[data-saldo]');
    Array.prototype.forEach.call(nodes, function (el) {
      var n = Number(el.getAttribute('data-saldo'));
      if (!isFinite(n)) return;
      var formatted;
      try {
        formatted = new Intl.NumberFormat('en-IN').format(n);
      } catch (e) {
        formatted = String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
      }
      el.textContent = '₹' + formatted;
    });
  }

  /* Render the real account expiry as YYYY-MM-DD. */
  function formatExpiry() {
    var el = document.getElementById('expiry-date');
    if (!el) return;
    var raw = el.getAttribute('datetime') || '';
    if (/^\d{4}-\d{2}-\d{2}/.test(raw)) el.textContent = raw.slice(0, 10);
  }

  /* An empty flash slot should not eat the first screen on a phone. */
  function hideEmptySlots() {
    var main = content();
    if (!main) return;
    Array.prototype.forEach.call(main.querySelectorAll('.mb-6, .mb-4'), function (el) {
      if (el.querySelector('img, svg, input, button, a, .ui-alert, .ui-card')) return;
      if ((el.textContent || '').trim()) return;
      el.style.display = 'none';
    });
  }

  /* ------------------------------------------------------------------ *
   * Feedback: button busy state + page progress bar.
   *
   * Account login does a bcrypt verification on the server, so a click used
   * to look like a dead button for a second or two. Every normal form submit
   * now shows a spinner and a "please wait" label, disables the button and
   * ignores repeat submits until the new page arrives.
   * ------------------------------------------------------------------ */

  var BUSY_LABELS = {
    'Sign In': 'Signing in…',
    'Create Account': 'Creating account…',
    'Create API key': 'Creating key…',
    'Revoke': 'Revoking…',
    'Delete': 'Deleting…',
    'Update Account': 'Saving…',
    'Update Password': 'Saving…',
    'Update Account Information': 'Saving…',
    'Update User Key': 'Saving…',
    'Update Settings': 'Saving…',
    'Update Features': 'Saving…',
    'Update Mod Name': 'Saving…',
    'Update Floating Text': 'Saving…',
    'Generate License Key': 'Generating…',
    'Create Referral Code': 'Generating…',
    'Upload': 'Uploading…',
    'Copy': 'Copied'
  };
  var BUSY_FALLBACK = 'Please wait…';
  /* Failsafe in case a request stalls: never leave a permanently dead form. */
  var RELEASE_AFTER_MS = 30000;
  var timers = [];

  function addTimer(fn, ms) {
    var id = window.setTimeout(fn, ms);
    timers.push(id);
    return id;
  }

  function makeSpinner() {
    var el = document.createElement('span');
    el.className = 'ui-spinner';
    el.setAttribute('aria-hidden', 'true');
    return el;
  }

  /* Wrap (never replace) the button's original children, so inline icons and
     listeners survive and can be restored byte-for-byte. */
  function busyButton(btn, label) {
    if (!btn || btn.getAttribute('data-anoy-busy') === '1') return false;
    var original = (btn.textContent || '').replace(/\s+/g, ' ').trim();
    var text = btn.getAttribute('data-busy-label') || label ||
               BUSY_LABELS[original] || BUSY_FALLBACK;

    var originalContent = document.createElement('span');
    originalContent.className = 'ui-btn__content';
    while (btn.firstChild) originalContent.appendChild(btn.firstChild);

    var busyLabel = document.createElement('span');
    busyLabel.className = 'ui-btn__busy-label';
    busyLabel.textContent = text;

    var spinner = makeSpinner();
    btn.appendChild(spinner);
    btn.appendChild(busyLabel);
    btn.appendChild(originalContent);

    btn.__anoyBusy = {
      wasDisabled: !!btn.disabled,
      spinner: spinner,
      label: busyLabel,
      content: originalContent
    };
    btn.classList.add('is-busy');
    btn.setAttribute('data-anoy-busy', '1');
    btn.setAttribute('aria-busy', 'true');
    btn.setAttribute('aria-disabled', 'true');
    btn.disabled = true;
    return true;
  }

  function releaseButton(btn) {
    if (!btn || btn.getAttribute('data-anoy-busy') !== '1') return false;
    var state = btn.__anoyBusy;
    if (state) {
      var wrapper = state.content;
      while (wrapper.firstChild) btn.insertBefore(wrapper.firstChild, wrapper);
      if (wrapper.parentNode) wrapper.parentNode.removeChild(wrapper);
      if (state.spinner.parentNode) state.spinner.parentNode.removeChild(state.spinner);
      if (state.label.parentNode) state.label.parentNode.removeChild(state.label);
      btn.disabled = state.wasDisabled;
      delete btn.__anoyBusy;
    }
    btn.classList.remove('is-busy');
    btn.removeAttribute('data-anoy-busy');
    btn.removeAttribute('aria-busy');
    btn.removeAttribute('aria-disabled');
    return true;
  }

  function releaseAll() {
    Array.prototype.forEach.call(
      document.querySelectorAll('[data-anoy-busy="1"]'), releaseButton);
    Array.prototype.forEach.call(document.forms, function (form) {
      if (form.getAttribute('data-anoy-busy') === '1') {
        form.removeAttribute('data-anoy-busy');
        form.classList.remove('is-submitting');
      }
    });
    hidePageProgress();
  }

  function pageProgress() {
    var el = document.getElementById('anoy-page-progress');
    if (el) return el;
    el = document.createElement('div');
    el.id = 'anoy-page-progress';
    el.className = 'anoy-page-progress';
    el.setAttribute('role', 'progressbar');
    el.setAttribute('aria-label', 'Loading next page');
    el.hidden = true;
    var bar = document.createElement('span');
    bar.className = 'anoy-page-progress__bar';
    el.appendChild(bar);
    document.body.appendChild(el);
    return el;
  }

  function showPageProgress() {
    var el = pageProgress();
    el.hidden = false;
    el.classList.add('is-active');
    /* Restart the indeterminate animation even on a repeat submit. */
    var bar = el.firstChild;
    if (bar) { bar.style.animation = 'none'; void bar.offsetWidth; bar.style.animation = ''; }
  }

  function hidePageProgress() {
    var el = document.getElementById('anoy-page-progress');
    if (!el) return;
    el.classList.remove('is-active');
    el.hidden = true;
  }

  function submitterOf(form, event) {
    if (event && event.submitter && event.submitter.form === form) return event.submitter;
    return form.querySelector('button[type="submit"]:not([disabled]), input[type="submit"]:not([disabled])') ||
           form.querySelector('button[type="submit"], input[type="submit"]');
  }

  /* Bubble phase on purpose: forms with their own JS (the library uploader)
     call preventDefault() first, so they own their feedback UI. */
  function onFormSubmit(e) {
    var form = e.target;
    if (!form || form.nodeName !== 'FORM') return;
    if (e.defaultPrevented) return;
    if (form.getAttribute('data-anoy-nobusy') === '1') return;
    /* Spam guard: a second Enter/click while the first request is in flight. */
    if (form.getAttribute('data-anoy-busy') === '1') {
      e.preventDefault();
      return;
    }
    var btn = submitterOf(form, e);
    if (!btn) return;
    form.setAttribute('data-anoy-busy', '1');
    form.classList.add('is-submitting');
    busyButton(btn);
    showPageProgress();
    addTimer(function () {
      if (form.getAttribute('data-anoy-busy') !== '1') return;
      form.removeAttribute('data-anoy-busy');
      form.classList.remove('is-submitting');
      releaseButton(btn);
      hidePageProgress();
    }, RELEASE_AFTER_MS);
  }

  /* Plain navigation links styled as buttons get the same feedback. Downloads,
     new tabs, external hosts and same-page anchors are deliberately excluded. */
  function onButtonLinkClick(e) {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    var node = e.target;
    var link = node && node.closest ? node.closest('a.ui-btn, a.btn') : null;
    if (!link || link.getAttribute('data-anoy-busy') === '1') return;
    if (link.hasAttribute('download') || link.target === '_blank') return;
    var href = link.getAttribute('href') || '';
    if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) return;
    if (/^https?:/i.test(href) && link.host !== window.location.host) return;
    if (/\/api\/lib\/download/i.test(link.getAttribute('href'))) return;
    if (link.pathname === window.location.pathname && link.search === window.location.search) return;
    busyButton(link, 'Opening…');
    showPageProgress();
    addTimer(function () { releaseButton(link); hidePageProgress(); }, RELEASE_AFTER_MS);
  }

  /* File/URL upload progress. Used by the /lib uploader and reusable anywhere.
     `state` is one of '' | 'working' | 'done' | 'error'. */
  function setProgress(root, percent, message, detail, state) {
    if (!root) return;
    var pct = Math.max(0, Math.min(100, Math.round(Number(percent) || 0)));
    var fill = root.querySelector('.anoy-progress__fill');
    var track = root.querySelector('.anoy-progress__track');
    var pctEl = root.querySelector('.anoy-progress__percent');
    var msgEl = root.querySelector('.anoy-progress__message');
    var hintEl = root.querySelector('.anoy-progress__hint');
    root.hidden = false;
    root.classList.remove('is-working', 'is-done', 'is-error');
    if (state) root.classList.add('is-' + state);
    if (fill) fill.style.width = pct + '%';
    if (track) track.setAttribute('aria-valuenow', String(pct));
    if (pctEl) pctEl.textContent = pct + '%';
    if (msgEl && message != null) msgEl.textContent = message;
    if (hintEl && detail != null) hintEl.textContent = detail;
  }

  function humanBytes(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    return (n / (1024 * 1024)).toFixed(2) + ' MB';
  }

  /* ------------------------------------------------------------------ *
   * Compact toast notifications. Used for quick actions (create / delete
   * key) instead of a full-screen SweetAlert modal or a wide flash banner.
   * No CDN dependency, so it also works when SweetAlert/jQuery are blocked.
   * ------------------------------------------------------------------ */
  function toastHost() {
    var host = document.getElementById('anoy-toasts');
    if (host) return host;
    host = document.createElement('div');
    host.id = 'anoy-toasts';
    host.className = 'anoy-toasts';
    host.setAttribute('aria-live', 'polite');
    document.body.appendChild(host);
    return host;
  }

  /* duration 0 keeps the toast on screen until dismiss() is called. */
  function toast(message, kind, duration) {
    var type = kind || 'info';
    var host = toastHost();
    var el = document.createElement('div');
    el.className = 'anoy-toast anoy-toast--' + type;
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');

    var dot = document.createElement('span');
    dot.className = 'anoy-toast__dot';
    dot.setAttribute('aria-hidden', 'true');
    var text = document.createElement('span');
    text.className = 'anoy-toast__text';
    text.textContent = String(message == null ? '' : message);
    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'anoy-toast__close';
    close.setAttribute('aria-label', 'Dismiss notification');
    close.textContent = '×';

    el.appendChild(dot);
    el.appendChild(text);
    el.appendChild(close);
    host.appendChild(el);

    var timer = null;
    function dismiss() {
      if (timer) { window.clearTimeout(timer); timer = null; }
      if (!el.parentNode) return;
      el.classList.add('is-leaving');
      window.setTimeout(function () {
        if (el.parentNode) el.parentNode.removeChild(el);
      }, 180);
    }
    function update(msg, newKind) {
      text.textContent = String(msg == null ? '' : msg);
      if (newKind) {
        el.className = 'anoy-toast anoy-toast--' + newKind;
        el.setAttribute('role', newKind === 'error' ? 'alert' : 'status');
      }
    }
    close.addEventListener('click', dismiss);
    var ms = duration === 0 ? 0 : (duration || 3200);
    if (ms > 0) timer = window.setTimeout(dismiss, ms);
    return { element: el, dismiss: dismiss, update: update };
  }

  /* Success flashes (e.g. "key generated") become small toasts. Errors and
     warnings stay on the page as compact banners so they cannot be missed. */
  function promoteFlashToasts() {
    Array.prototype.forEach.call(
      document.querySelectorAll('.ui-alert[data-flash="success"]'), function (el) {
        var body = el.querySelector('.ui-alert-body');
        var message = body ? body.textContent : el.textContent;
        message = (message || '').replace(/\s+/g, ' ').trim();
        if (!message) return;
        toast(message, 'success');
        el.hidden = true;
        var wrap = el.parentElement;
        if (wrap && (wrap.classList.contains('mb-6') || wrap.classList.contains('mb-4')) &&
            !wrap.querySelector('.ui-alert:not([hidden]), input, button:not(.ui-alert-close)')) {
          wrap.style.display = 'none';
        }
      });
  }

  /* Small public API so views with custom async forms stay consistent. */
  window.AnoyUI = {
    busyButton: busyButton,
    releaseButton: releaseButton,
    setProgress: setProgress,
    showPageProgress: showPageProgress,
    hidePageProgress: hidePageProgress,
    humanBytes: humanBytes,
    toast: toast
  };

  function init() {
    wrapTables();
    labelTableCells();
    formatSaldo();
    formatExpiry();
    hideEmptySlots();
    promoteFlashToasts();

    document.addEventListener('submit', onFormSubmit, false);
    document.addEventListener('click', onButtonLinkClick, false);
    /* A back/forward navigation can restore a page from bfcache with its
       spinner still frozen on screen. */
    window.addEventListener('pageshow', function (e) { if (e.persisted) releaseAll(); });
    window.addEventListener('pagehide', function () {
      timers.forEach(window.clearTimeout);
      timers = [];
    });

    var main = content();
    if (!main || !window.MutationObserver) return;
    var pending = false;
    var observer = new MutationObserver(function () {
      if (pending) return;
      pending = true;
      window.requestAnimationFrame(function () {
        pending = false;
        labelTableCells();
        formatSaldo();
        hideEmptySlots();
      });
    });
    observer.observe(main, { childList: true, subtree: true });
  }

  if (document.readyState !== 'loading') init();
  else document.addEventListener('DOMContentLoaded', init);
})();
