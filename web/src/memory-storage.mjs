// OFFLINE ONLY. Supabase Storage's narrow API, plus local signed URLs for
// integration tests. Never mounted by the Vercel function.
import crypto from 'node:crypto';

export function createMemoryStorage() {
  const objects = new Map();
  const created = new Map();
  const failures = new Map();
  const fail = operation => {
    if (!failures.has(operation)) return null;
    const message = failures.get(operation); failures.delete(operation);
    return { data: null, error: { message } };
  };
  const grants = new Map();
  const grant = (key, mode) => {
    const token = crypto.randomBytes(24).toString('hex');
    grants.set(token, { key, mode, expires: Date.now() + 3600_000 });
    return `/_offline/storage/${token}`;
  };
  return {
    _failNext(operation, message = 'Offline simulated storage failure') { failures.set(operation, message); },
    _inventory() { return [...objects].map(([key, bytes]) => ({ bucket_id: key.split('/')[0],
      name: key.slice(key.indexOf('/') + 1), created_at: created.get(key), bytes: bytes.length })); },
    _setCreatedAt(bucket, path, value) { created.set(`${bucket}/${path}`, value); },
    from(bucket) {
      return {
        async upload(path, bytes) {
          const failure = fail('upload'); if (failure) return failure;
          const key = `${bucket}/${path}`;
          if (objects.has(key)) return { error: { message: 'Object already exists' }, data: null };
          objects.set(key, Buffer.from(bytes));
          created.set(key, new Date().toISOString());
          return { data: { path }, error: null };
        },
        async download(path) {
          const failure = fail('download'); if (failure) return failure;
          const bytes = objects.get(`${bucket}/${path}`);
          return bytes ? { data: new Blob([bytes]), error: null } : { data: null, error: { message: 'Object missing' } };
        },
        async createSignedUploadUrl(path) { return { data: { signedUrl: grant(`${bucket}/${path}`, 'PUT'), path }, error: null }; },
        async createSignedUrl(path) { return { data: { signedUrl: grant(`${bucket}/${path}`, 'GET') }, error: null }; },
        async remove(paths) {
          const failure = fail('remove'); if (failure) return failure;
          for (const path of paths) { objects.delete(`${bucket}/${path}`); created.delete(`${bucket}/${path}`); }
          return { data: [], error: null };
        },
      };
    },
    async handle(req, res) {
      const token = String(req.url).split('?')[0].split('/').pop();
      const entry = grants.get(token);
      res.setHeader('Cache-Control', 'no-store');
      if (!entry || entry.expires < Date.now() || req.method !== entry.mode) { res.statusCode = 403; return res.end(); }
      if (entry.mode === 'GET') {
        const bytes = objects.get(entry.key);
        if (!bytes) { res.statusCode = 404; return res.end(); }
        res.setHeader('Content-Type', 'application/zip');
        return res.end(bytes);
      }
      if (objects.has(entry.key)) { res.statusCode = 409; return res.end(); }
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 11 * 1024 * 1024) { res.statusCode = 413; return res.end(); }
        chunks.push(chunk);
      }
      objects.set(entry.key, Buffer.concat(chunks));
      created.set(entry.key, new Date().toISOString());
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ Key: entry.key }));
    },
  };
}
