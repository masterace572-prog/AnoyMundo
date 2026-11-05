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

  function init() {
    wrapTables();
    labelTableCells();
    formatSaldo();
    formatExpiry();
    hideEmptySlots();

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
