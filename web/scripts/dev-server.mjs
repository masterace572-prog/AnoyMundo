// Local development server: uses the exact same handler as the Vercel
// function, plus static file serving for /public.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import dotenv from 'dotenv';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
dotenv.config({ path: path.join(root, '.env.local') });
dotenv.config({ path: path.join(root, '.env') });

const forceMemory = process.argv.includes('--memory');
if (forceMemory || !process.env.SUPABASE_URL) {
  process.env.DB_DRIVER = 'memory';
  console.log('[dev] DB_DRIVER=memory (offline demo data)');
}

const { default: handler } = await import('../api/index.js');

const publicDir = path.join(root, 'public');
const mime = {
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.txt': 'text/plain; charset=utf-8',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.zip': 'application/zip',
};

const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  const candidate = path.resolve(publicDir, '.' + urlPath);
  if (candidate.startsWith(publicDir) && fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
    res.setHeader('Content-Type', mime[path.extname(candidate).toLowerCase()] || 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-cache');
    fs.createReadStream(candidate).pipe(res);
    return;
  }
  Promise.resolve(handler(req, res)).catch((err) => {
    console.error(err);
    if (!res.headersSent) { res.statusCode = 500; res.setHeader('Content-Type', 'text/plain'); }
    res.end('Internal Server Error');
  });
});

const port = Number(process.env.PORT || 3000);
server.listen(port, '0.0.0.0', () => {
  console.log(`[dev] http://0.0.0.0:${port}  (root: ${root})`);
});
