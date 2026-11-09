import Handlebars from 'handlebars';
import { templates } from './templates.generated.mjs';
import { icons, uiSprite } from './icons.generated.mjs';
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
// First letter of a name, for avatar circles.
Handlebars.registerHelper('initial', (...args) => {
  const name = args.slice(0, -1).find((v) => typeof v === 'string' && v.trim()) || '?';
  return name.trim().charAt(0).toUpperCase();
});
Handlebars.registerHelper('json', (value) => JSON.stringify(value ?? null));
Handlebars.registerHelper('alertClass', (type) => {
  const allowed = ['success', 'warning', 'danger', 'info'];
  return `alert-${allowed.includes(String(type)) ? String(type) : 'info'}`;
});

// {{icon "key-round"}} or {{icon "copy" "i-sm"}}: inline line icon, decorative
// (aria-hidden); the surrounding control carries the accessible label.
export function iconSvg(name, cls = '') {
  const inner = icons[name];
  if (!inner) return '';
  const extra = cls ? ` ${String(cls).replace(/[^a-z0-9 _-]/gi, '')}` : '';
  return `<svg class="i${extra}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" ` +
    `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${inner}</svg>`;
}
Handlebars.registerHelper('icon', (name, cls) => new Handlebars.SafeString(
  iconSvg(String(name), typeof cls === 'string' ? cls : '')));

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
  const cls = extraClass || 'input';
  const opts = Object.entries(options).map(([value, label]) => {
    const sel = String(selected ?? '') === String(value) ? ' selected' : '';
    return `<option value="${escapeHtml(value)}"${sel}>${escapeHtml(label)}</option>`;
  }).join('');
  return `<select name="${escapeHtml(name)}" id="${escapeHtml(id)}" class="${cls}">${opts}</select>`;
}

// ---------------------------------------------------------------- time
// One rule for every timestamp: the database stores real instants
// (timestamptz, sent as ISO-8601 UTC), and everything a person reads or types
// is Indian Standard Time (Asia/Kolkata). IST is a fixed UTC+5:30 with no
// daylight saving, so the conversion is a plain offset - applied once, at the
// edge, and never to the stored value or to a key's length.
export const IST_OFFSET_MS = 330 * 60_000;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December'];

/** Wall-clock fields of an instant in IST (read with getUTC*), or null. */
function istClock(value) {
  if (value === null || value === undefined || value === '') return null;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  return new Date(d.getTime() + IST_OFFSET_MS);
}

function clock12(t) {
  const h = t.getUTCHours();
  return `${h % 12 === 0 ? 12 : h % 12}:${String(t.getUTCMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
}

/** "2026-11-08 22:30:00" in IST - the value of the panel's date inputs. */
export function sqlDateTime(date = new Date()) {
  const t = istClock(date);
  if (!t) return '';
  const p = (n) => String(n).padStart(2, '0');
  return `${t.getUTCFullYear()}-${p(t.getUTCMonth() + 1)}-${p(t.getUTCDate())} ${p(t.getUTCHours())}:${p(t.getUTCMinutes())}:${p(t.getUTCSeconds())}`;
}

/**
 * A typed date/time back to an ISO instant. Without an explicit zone it is
 * IST (what the inputs show); "Z" or "+hh:mm" are honoured. Null if invalid.
 */
export function parseIstDateTime(value) {
  const s = String(value ?? '').trim().replace(' ', 'T');
  if (!s) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec(s);
  if (m) {
    const [, y, mo, d, h = '0', mi = '0', se = '0'] = m;
    const utc = Date.UTC(+y, +mo - 1, +d, +h, +mi, +se);
    const back = new Date(utc);
    if (back.getUTCMonth() !== +mo - 1 || back.getUTCDate() !== +d || +h > 23 || +mi > 59 || +se > 59) return null;
    return new Date(utc - IST_OFFSET_MS).toISOString();
  }
  if (!/(Z|[+-]\d\d:\d\d)$/.test(s)) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

/** "8 Nov 2026, 10:30 PM IST" - the same text the loader shows for that instant. */
export function humanDate(value) {
  if (!value) return '';
  const t = istClock(value);
  if (!t) return String(value);
  return `${t.getUTCDate()} ${MONTHS[t.getUTCMonth()]} ${t.getUTCFullYear()}, ${clock12(t)} IST`;
}

/** "8 November 2026, 10:30 PM IST". */
export function longDate(value) {
  if (!value) return '';
  const t = istClock(value);
  if (!t) return String(value);
  return `${t.getUTCDate()} ${MONTHS_LONG[t.getUTCMonth()]} ${t.getUTCFullYear()}, ${clock12(t)} IST`;
}

export function durationLabel(hours) {
  const h = Number(hours);
  if (h === 1) return '1 Hour';
  if (h > 1 && h < 24) return `${h} Hours`;
  if (h === 24) return '1 Day';
  if (h > 24) return `${h / 24} Days`;
  return String(hours ?? '');
}

function reselect(html, value) {
  const wanted = `value="${escapeHtml(value)}"`;
  if (!String(html).includes(wanted)) return html;
  return String(html).replace(/ selected(?=>)/g, '').replace(wanted, `${wanted} selected`);
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
    created_label: flashRaw.created_label || '',
    expires_if_now: flashRaw.expires_if_now || '',
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
    navAppUpdate: on('/app-update'),
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
    ? {
      ...user,
      initial: String(user.displayName || user.username || 'U').trim().charAt(0).toUpperCase(),
      // The single Owner account has an unlimited balance.
      unlimited: !!user.isOwner,
      saldoLabel: Number(user.saldo || 0).toLocaleString('en-IN'),
    }
    : null;
  base.flash = { ...flash, ...(data.flash || {}) };
  base.old = { ...old, ...(data.old || {}) };
  base.errors = { ...errors, ...(data.errors || {}) };
  base.form = { ...form, ...(data.form || {}), ...old };
  base.appVersion = config.version;
  base.iconSprite = uiSprite;
  // A failed POST re-renders with the previous choices kept selected.
  base.selects = Object.fromEntries(Object.entries(base.selects || {})
    .map(([key, html]) => [key, old[key] !== undefined ? reselect(html, old[key]) : html]));

  if (name === 'login' || name === 'register') return compile(name)(base);
  const content = compile(name)(base);
  return compile('layout')({ ...base, content, pageCss: data.pageCss || '', pageJs: data.pageJs || '' });
}

// Everything is no-store unless a caller passes an explicit Cache-Control
// (2.3.3: only the public, identical-for-everyone /api/app/update does).
function securityHeaders(res, html, cacheControl = 'no-store') {
  res.setHeader('Cache-Control', cacheControl);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'same-origin');
  if (html) {
    // No frame restriction: the panel is shown inside the Arena live preview.
    res.setHeader('Content-Security-Policy',
      "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
      "script-src 'self' 'unsafe-inline'; font-src 'self' data:; " +
      "connect-src 'self'; base-uri 'self'; form-action 'self'; object-src 'none'");
  }
}

export function sendHtml(res, html, status = 200) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  securityHeaders(res, true);
  res.end(html);
}

export function sendJson(res, payload, status = 200, { cacheControl } = {}) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  securityHeaders(res, false, status === 200 && cacheControl ? cacheControl : 'no-store');
  res.end(JSON.stringify(payload));
}

export function sendText(res, text, status = 200, contentType = 'text/plain; charset=utf-8') {
  res.statusCode = status;
  res.setHeader('Content-Type', contentType);
  securityHeaders(res, false);
  res.end(text);
}
