/* Progressive enhancement for the flat responsive layout.
   No framework dependency: runs on DOMContentLoaded. */
(function () {
  'use strict';

  function wrapTables() {
    var content = document.getElementById('main-content');
    if (!content) return;
    var tables = content.querySelectorAll('table');
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

  if (document.readyState !== 'loading') wrapTables();
  else document.addEventListener('DOMContentLoaded', wrapTables);
})();
