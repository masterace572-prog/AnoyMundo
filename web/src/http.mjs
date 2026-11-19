import { setFlash } from './auth.mjs';

/** Request URL helpers that behave identically on Vercel and the local server. */
export function requestUrl(req) {
  const host = req.headers?.host || 'localhost';
  return new URL(req.url || '/', `http://${host}`);
}

export function pathOf(req) {
  const raw = requestUrl(req).pathname;
  return raw.length > 1 ? raw.replace(/\/+$/, '') : '/';
}

/**
 * Reads the request body. Vercel already parses JSON / urlencoded bodies into
 * req.body; the local dev server gives us a raw stream instead.
 * Duplicate keys keep the LAST value, matching PHP's $_POST behaviour.
 */
export async function parseBody(req) {
  const existing = req.body;
  if (existing && typeof existing === 'object' && !Buffer.isBuffer(existing)) return existing;
  let raw = '';
  if (typeof existing === 'string') raw = existing;
  else if (Buffer.isBuffer(existing)) raw = existing.toString('utf8');
  else {
    raw = await new Promise((resolve) => {
      let buf = '';
      req.on('data', (chunk) => {
        buf += chunk;
        if (buf.length > 6_000_000) req.destroy();
      });
      req.on('end', () => resolve(buf));
      req.on('error', () => resolve(''));
    });
  }
  if (!raw) return {};
  const contentType = String(req.headers?.['content-type'] || '');
  if (contentType.includes('application/json')) {
    try { return JSON.parse(raw); } catch { return {}; }
  }
  const out = {};
  for (const [key, value] of new URLSearchParams(raw)) out[key] = value;
  return out;
}

export function redirect(res, url, flash) {
  if (flash) setFlash(res, flash);
  res.statusCode = 302;
  res.setHeader('Location', url);
  res.setHeader('Cache-Control', 'no-store');
  res.end();
}

export function back(req, fallback = '/dashboard') {
  const ref = req.headers?.referer;
  if (!ref) return fallback;
  try {
    const u = new URL(ref);
    const current = new URL(req.url || '/', `http://${req.headers?.host || 'localhost'}`);
    if (u.host !== current.host) return fallback;
    return u.pathname + u.search;
  } catch {
    return fallback;
  }
}

export function methodNotAllowed(res) {
  res.statusCode = 405;
  res.setHeader('Allow', 'GET, POST');
  res.end('Method Not Allowed');
}
