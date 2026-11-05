import Handlebars from 'handlebars';
import { templates } from './templates.generated.mjs';
import { config } from './config.mjs';
import { readFlash } from './auth.mjs';

// ---------------------------------------------------------------------------
// Handlebars helpers used by the converted CodeIgniter views.
// ---------------------------------------------------------------------------
Handlebars.registerHelper('eq', (a, b) => String(a) === String(b));
Handlebars.registerHelper('neq', (a, b) => String(a) !== String(b));
Handlebars.registerHelper('gt', (a, b) => Number(a) > Number(b));
Handlebars.registerHelper('lt', (a, b) => Number(a) < Number(b));
Handlebars.registerHelper('and', (...args) => args.slice(0, -1).every(Boolean));
Handlebars.registerHelper('or', (...args) => args.slice(0, -1).some(Boolean));
Handlebars.registerHelper('json', (value) => JSON.stringify(value ?? null));
Handlebars.registerHelper('alertClass', (type) => {
  const allowed = ['success', 'warning', 'danger', 'info'];
  return `ui-alert--${allowed.includes(String(type)) ? String(type) : 'info'}`;
});

Handlebars.registerPartial('header', templates.header);
Handlebars.registerPartial('msgStatus', templates.msgStatus);

const compiled = new Map();
function compile(name) {
  if (!compiled.has(name)) {
    if (!templates[name]) throw new Error(`Missing template: ${name}`);
    compiled.set(name, Handlebars.compile(templates[name], { noEscape: false }));
  }
  return compiled.get(name);
}

export function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** Build <select> markup with an optional selected value. */
export function buildSelect(name, id, options, selected, extraClass = '') {
  const cls = extraClass || 'w-full px-4 py-3 bg-white/50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-purple-500 transition-all';
  const opts = Object.entries(options).map(([value, label]) => {
    const sel = String(selected ?? '') === String(value) ? ' selected' : '';
    return `<option value="${escapeHtml(value)}"${sel}>${label}</option>`;
  }).join('');
  return `<select name="${escapeHtml(name)}" id="${escapeHtml(id)}" class="${cls}">${opts}</select>`;
}

export function sqlDateTime(date = new Date()) {
  const d = date instanceof Date ? date : new Date(date);
  if (Number.isNaN(d.getTime())) return '';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;
}

export function humanDate(value) {
  if (!value) return '';
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getUTCDate())} ${months[d.getUTCMonth()]} ${String(d.getUTCFullYear()).slice(2)} - ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}

export function longDate(value) {
  if (!value) return '';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return String(value);
  return d.toLocaleString('en-US', {
    timeZone: 'UTC', month: 'long', day: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).replace(',', '');
}

export function durationLabel(hours) {
  const h = Number(hours);
  if (h === 1) return '1 Hour';
  if (h > 1 && h < 24) return `${h} Hours`;
  if (h === 24) return '1 Day';
  if (h > 24) return `${h / 24} Days`;
  return String(hours ?? '');
}

function humanizeDiff(ms) {
  const past = ms < 0;
  const minutes = Math.max(1, Math.round(Math.abs(ms) / 60000));
  let text;
  if (minutes < 60) text = `${minutes} minute${minutes === 1 ? '' : 's'}`;
  else if (minutes < 1440) { const h = Math.round(minutes / 60); text = `${h} hour${h === 1 ? '' : 's'}`; }
  else { const d = Math.round(minutes / 1440); text = `${d} day${d === 1 ? '' : 's'}`; }
  return past ? `${text} ago` : `in ${text}`;
}

/**
 * Renders a converted view. Standalone views (login/register) are complete
 * HTML documents; the rest are wrapped in the original Layout/Starter shell.
 */
export function renderPage(req, res, name, data = {}) {
  const flashRaw = readFlash(req, res);
  const user = data.user || null;
  const session = user?.session;
  const flash = {
    danger: flashRaw.msgDanger || '',
    success: flashRaw.msgSuccess || '',
    warning: flashRaw.msgWarning || '',
    user_key: flashRaw.user_key || '',
    game: flashRaw.game || '',
    duration: flashRaw.duration || '',
    max_devices: flashRaw.max_devices || '',
    apiKeyPlain: flashRaw.apiKeyPlain || '',
  };
  const old = flashRaw.old || data.old || {};
  const errors = flashRaw.errors || data.errors || {};
  const form = { ...(data.form || {}), ...old };

  let currentPath = '/';
  try {
    currentPath = new URL(req.url || '/', 'http://localhost').pathname.toLowerCase();
  } catch { /* keep the default */ }
  currentPath = currentPath.replace(/\/+$/, '') || '/';
  const on = (prefix, exact = false) => (exact
    ? currentPath === prefix
    : currentPath === prefix || currentPath.startsWith(prefix + '/'));

  const base = {
    brand: config.brand,
    telegram: {
      handle: `@${config.telegramHandle}`,
      url: `https://t.me/${config.telegramHandle}`,
    },
    currentPath,
    navDashboard: on('/dashboard', true) || currentPath === '/' || currentPath === '/login',
    navKeys: on('/keys') && !on('/keys/generate'),
    navGenerate: on('/keys/generate'),
    navSettings: on('/settings'),
    navApiKeys: on('/api-keys'),
    navApiDocs: on('/api-docs'),
    navIntegration: on('/docs'),
    navServer: on('/server'),
    navUsers: on('/admin/manage-users') || on('/admin/user'),
    navReferral: on('/admin/create-referral'),
    navLib: on('/lib'),
    year: new Date().getUTCFullYear(),
    title: data.title || 'Panel',
    now: sqlDateTime(),
    userAgent: req.headers?.['user-agent'] || '',
    clientIp: data.clientIp || '',
    user,
    flash,
    old,
    errors,
    form,
    pageMessage: data.pageMessage || null,
    selects: data.selects || {},
    priceJson: JSON.stringify(config.prices),
    stats: data.stats || {},
    sessionSince: session ? humanizeDiff(Date.now() - session.iat) : '',
    sessionRemaining: session ? humanizeDiff(session.exp - Date.now()) : '',
    ...data,
  };
  // data overrides above, but keep the derived collections coherent
  base.user = user
    ? { ...user, initial: String(user.displayName || user.username || 'U').trim().charAt(0).toUpperCase() }
    : null;
  base.flash = { ...flash, ...(data.flash || {}) };
  base.old = { ...old, ...(data.old || {}) };
  base.errors = { ...errors, ...(data.errors || {}) };
  base.form = { ...form, ...(data.form || {}), ...old };

  if (name === 'login' || name === 'register') return compile(name)(base);
  const content = compile(name)(base);
  return compile('layout')({ ...base, content, pageCss: data.pageCss || '', pageJs: data.pageJs || '' });
}

function securityHeaders(res, html) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  if (html) {
    // No frame restriction: the panel is shown inside the Arena live preview.
    res.setHeader('Content-Security-Policy',
      "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline' https:; " +
      "script-src 'self' 'unsafe-inline' https:; font-src 'self' data: https:; " +
      "connect-src 'self'; base-uri 'self'; form-action 'self'");
  }
}

export function sendHtml(res, html, status = 200) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  securityHeaders(res, true);
  res.end(html);
}

export function sendJson(res, payload, status = 200) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  securityHeaders(res, false);
  res.end(JSON.stringify(payload));
}

export function sendText(res, text, status = 200, contentType = 'text/plain; charset=utf-8') {
  res.statusCode = status;
  res.setHeader('Content-Type', contentType);
  securityHeaders(res, false);
  res.end(text);
}
