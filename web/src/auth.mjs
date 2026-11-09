import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';
import { config } from './config.mjs';
import { getUserById, getUserByUsername } from './db.mjs';

export const SESSION_COOKIE = 'am_session';
export const FLASH_COOKIE = 'am_flash';

// ---------------------------------------------------------------------------
// Password compatibility with the PHP helper nata_helper.php::create_password
//   hash = md5('XquxmymXDtWRA66D' . password)
//   password_hash(hash, PASSWORD_DEFAULT, ['cost' => 8])  -> bcrypt $2y$
// ---------------------------------------------------------------------------
export function preHash(password) {
  return crypto.createHash('md5').update(config.passwordSalt + String(password ?? '')).digest('hex');
}

export function codeHash(code) {
  return preHash(code);
}

export function verifyPassword(password, hash) {
  if (!hash) return false;
  try {
    // bcryptjs accepts $2y$ as well, but normalising keeps PHP/Node hashes
    // interchangeable (PHP writes $2y$, bcryptjs writes $2a$).
    const normalised = String(hash).replace(/^\$2y\$/, '$2a$').replace(/^\$2b\$/, '$2a$');
    return bcrypt.compareSync(preHash(password), normalised);
  } catch {
    return false;
  }
}

export function hashPassword(password) {
  return bcrypt.hashSync(preHash(password), 8);
}

/** Returns '' for an acceptable new password, or a short reason to show. */
const WEAK_PASSWORDS = new Set([
  'admin123', 'admin1234', 'password', 'password1', '123456', '12345678',
  '123456789', 'qwerty123', 'letmein', 'iloveyou', 'abc12345',
]);
export function passwordIssue(password, username = '') {
  const p = String(password ?? '');
  if (p.length < 8) return 'Use at least 8 characters.';
  if (p.length > 45) return 'Use at most 45 characters.';
  if (WEAK_PASSWORDS.has(p.toLowerCase())) return 'Choose a less common password.';
  if (!/[A-Za-z]/.test(p) || !/[0-9]/.test(p)) return 'Include a letter and a number.';
  if (username && p.toLowerCase().includes(String(username).toLowerCase())) {
    return 'Do not include your username in the password.';
  }
  return '';
}

// ---------------------------------------------------------------------------
// Stateless signed session cookie (replaces PHP file-based sessions, which
// cannot work on Vercel's read-only / ephemeral filesystem).
// ---------------------------------------------------------------------------
function b64url(input) {
  return Buffer.from(input).toString('base64url');
}

function sign(data) {
  return crypto.createHmac('sha256', config.authSecret).update(data).digest('base64url');
}

export function createSessionToken(payload) {
  const body = b64url(JSON.stringify(payload));
  return `${body}.${sign(body)}`;
}

export function readSessionToken(token) {
  if (!token || typeof token !== 'string') return null;
  const [body, signature] = token.split('.');
  if (!body || !signature) return null;
  const expected = sign(body);
  const a = Buffer.from(signature);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (!payload.exp || payload.exp < Date.now()) return null;
    return payload;
  } catch {
    return null;
  }
}

export function parseCookies(header) {
  const out = {};
  for (const part of String(header || '').split(';')) {
    const idx = part.indexOf('=');
    if (idx < 0) continue;
    const key = part.slice(0, idx).trim();
    const value = part.slice(idx + 1).trim();
    if (!key) continue;
    try { out[key] = decodeURIComponent(value); } catch { out[key] = value; }
  }
  return out;
}

function serializeCookie(name, value, { maxAge = 0, clear = false } = {}) {
  const parts = [`${name}=${clear ? '' : encodeURIComponent(value)}`, 'Path=/', 'HttpOnly', 'SameSite=Lax'];
  if (process.env.VERCEL || process.env.NODE_ENV === 'production') parts.push('Secure');
  parts.push(clear ? 'Max-Age=0' : `Max-Age=${maxAge}`);
  return parts.join('; ');
}

/** Append a Set-Cookie header without clobbering cookies set earlier. */
function appendCookie(res, cookie) {
  const existing = res.getHeader?.('Set-Cookie');
  const list = Array.isArray(existing) ? [...existing] : existing ? [existing] : [];
  list.push(cookie);
  res.setHeader?.('Set-Cookie', list);
}

export function setSession(res, user, remember) {
  const ttlMs = (remember ? config.rememberHours * 60 : config.sessionMinutes) * 60 * 1000;
  const now = Date.now();
  const payload = {
    uid: user.id_users,
    un: user.username,
    iat: now,
    exp: now + ttlMs,
    remember: !!remember,
    // Bind the cookie to the current password hash. Changing a password (in
    // Settings or by the Owner) immediately invalidates every older session.
    pv: String(user.password || '').slice(0, 12),
  };
  appendCookie(res, serializeCookie(SESSION_COOKIE, createSessionToken(payload), { maxAge: Math.floor(ttlMs / 1000) }));
  return payload;
}

export function clearSession(res) {
  appendCookie(res, serializeCookie(SESSION_COOKIE, '', { clear: true }));
  appendCookie(res, serializeCookie(FLASH_COOKIE, '', { clear: true }));
}

export function setFlash(res, flash) {
  const value = b64url(JSON.stringify(flash || {}));
  appendCookie(res, serializeCookie(FLASH_COOKIE, value, { maxAge: 120 }));
}

export function readFlash(req, res) {
  const cookies = parseCookies(req.headers?.cookie);
  const raw = cookies[FLASH_COOKIE];
  if (!raw) return {};
  // Flash is one-shot: clear it on the response that consumed it.
  appendCookie(res, serializeCookie(FLASH_COOKIE, '', { clear: true }));
  try {
    return JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'));
  } catch {
    return {};
  }
}

export function getClientIp(req) {
  const fwd = req.headers?.['x-forwarded-for'];
  if (fwd) return String(fwd).split(',')[0].trim();
  return req.headers?.['x-real-ip'] || req.socket?.remoteAddress || '0.0.0.0';
}

export function levelName(level) {
  return ({ 1: 'Owner', 2: 'Admin', 3: 'Reseller' })[Number(level)] || 'Unknown';
}

export function displayName(user) {
  if (!user) return 'Guest';
  if (user.fullname) return String(user.fullname).trim().split(/\s+/)[0];
  return user.username;
}

// ---------------------------------------------------------------------------
// 2.3.3: per-instance cache of the signed-in user's row, keyed by the session
// cookie (its hash), for at most 15 s. Only the database read is cached:
// every request still verifies the cookie signature and expiry and re-runs
// every check below (renamed account, password version, status, level,
// account expiry) against the row. Entries are dropped on sign-out and by any
// request that can change data (app.mjs), so a password change, ban or
// balance change on this instance applies at once; another warm instance can
// lag by at most 15 s. The money path never uses it (key-service.mjs re-reads
// the row before charging).
// ---------------------------------------------------------------------------
const SESSION_CACHE_MS = 15_000;
const SESSION_CACHE_MAX = 500;
const sessionCache = new Map();

function sessionKey(token) {
  return crypto.createHash('sha256').update(String(token)).digest('base64url');
}

async function cachedUserRow(token, uid) {
  const key = sessionKey(token);
  const hit = sessionCache.get(key);
  const now = Date.now();
  if (hit && now - hit.at < SESSION_CACHE_MS && hit.row && Number(hit.row.id_users) === Number(uid)) {
    return hit.row;
  }
  const row = await getUserById(uid);
  if (row) {
    if (sessionCache.size >= SESSION_CACHE_MAX) sessionCache.delete(sessionCache.keys().next().value);
    sessionCache.set(key, { row, at: now });
  } else {
    sessionCache.delete(key);
  }
  return row;
}

/** Drops the cached row of the session in this request's cookie (sign-out). */
export function forgetSession(req) {
  const token = parseCookies(req?.headers?.cookie)[SESSION_COOKIE];
  if (token) sessionCache.delete(sessionKey(token));
}

/** Drops every cached row on this instance (any data-changing request). */
export function forgetAllSessions() {
  sessionCache.clear();
}

/**
 * Validates the session cookie, re-checks the user row (status / level /
 * account expiry) and returns a view-model, or null when not authenticated.
 */
export async function currentUser(req) {
  const cookies = parseCookies(req.headers?.cookie);
  const session = readSessionToken(cookies[SESSION_COOKIE]);
  if (!session) return null;
  const user = await cachedUserRow(cookies[SESSION_COOKIE], session.uid);
  if (!user) return null;
  // If an account is renamed, an old cookie must not keep its privileges.
  if (String(user.username) !== String(session.un)) return null;
  // Sessions issued before this guard had no pv; they expire naturally. Any
  // newer session is void as soon as the password hash changes.
  if (session.pv && String(user.password || '').slice(0, 12) !== String(session.pv)) return null;
  if (Number(user.status) !== 1 || Number(user.level) > 3) return null;
  if (user.expiration_date && new Date(user.expiration_date).getTime() < Date.now()) return null;
  const isOwner = Number(user.level) === 1 && user.username === config.ownerUsername;
  return {
    ...user,
    isAdmin: isOwner || Number(user.level) === 2,
    isOwner,
    notLevel2: Number(user.level) !== 2,
    displayName: displayName(user),
    levelName: levelName(user.level),
    session,
  };
}

export async function authenticate(username, password) {
  const user = await getUserByUsername(String(username || '').trim());
  if (!user) return { ok: false, reason: 'The username is not registered.' };
  if (!verifyPassword(password, user.password)) return { ok: false, reason: 'Wrong password, please try again.' };
  if (Number(user.status) !== 1) return { ok: false, reason: 'Account is not active.' };
  if (user.expiration_date && new Date(user.expiration_date).getTime() < Date.now()) {
    return { ok: false, reason: 'Expired - Please Renew Your Account to Login.' };
  }
  return { ok: true, user };
}
