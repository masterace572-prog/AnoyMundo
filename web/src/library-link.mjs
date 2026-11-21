// Replacing a stored library with a broken external link must not garbage-
// collect the last working binary. Verify the link before any DB publication.
import { normalizeLibrary, MAX_LIBRARY_BYTES } from './library-package.mjs';

export async function verifyLibraryLink(rawUrl, { selfHosts = [], fetchImpl = globalThis.fetch, timeoutMs = 10_000 } = {}) {
  const bad = reason => ({ ok: false, error: `That link is not a working library (${reason}). The current release is unchanged.` });
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let url;
  try {
    url = new URL(rawUrl);
    for (let hop = 0; hop <= 6; hop++) {
      const host = url.hostname.toLowerCase();
      if (url.protocol !== 'https:' || url.username || url.password) return bad('use a public HTTPS download');
      if (selfHosts.includes(host) || host === 'vercel.app' || host.endsWith('.vercel.app')
          || host === 'localhost' || host.endsWith('.localhost') || host === '[::1]'
          || /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host)) return bad('use a direct public file, not this panel or a local address');
      const res = await fetchImpl(url, { redirect: 'manual', signal: controller.signal, cache: 'no-store',
        headers: { Accept: 'application/zip, application/octet-stream, */*', 'User-Agent': 'AnoyPanel/2 (library link check)' } });
      if ([301, 302, 303, 307, 308].includes(res.status)) {
        const location = res.headers.get('location');
        await res.body?.cancel();
        if (!location) return bad('redirect has no target');
        url = new URL(location, url); continue;
      }
      if (!res.ok) { await res.body?.cancel(); return bad(`HTTP ${res.status}`); }
      if (Number(res.headers.get('content-length')) > MAX_LIBRARY_BYTES) { await res.body?.cancel(); return bad('over 10 MB'); }
      const reader = res.body?.getReader();
      if (!reader) return bad('empty download');
      const chunks = [];
      let size = 0;
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > MAX_LIBRARY_BYTES) return bad('over 10 MB');
          chunks.push(Buffer.from(value));
        }
      } finally { await reader.cancel().catch(() => {}); }
      try { normalizeLibrary(Buffer.concat(chunks, size)); }
      catch { return bad('expected an arm64 .so or a valid ZIP up to 10 MB'); }
      return { ok: true };
    }
    return bad('too many redirects');
  } catch { return bad(controller.signal.aborted ? 'no answer within 10 seconds' : 'download could not be verified'); }
  finally { clearTimeout(timer); }
}
