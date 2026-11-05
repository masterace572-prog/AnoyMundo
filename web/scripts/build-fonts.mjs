// Copies the self-hosted Inter variable font out of node_modules into public/,
// so Vercel serves it as a static asset. No runtime font CDN dependency.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = path.join(root, 'node_modules', '@fontsource-variable', 'inter');
const outDir = path.join(root, 'public', 'assets', 'fonts');

const files = [
  ['files/inter-latin-wght-normal.woff2', 'inter-latin-wght-normal.woff2'],
  ['LICENSE', 'INTER-LICENSE.txt'],
];

fs.mkdirSync(outDir, { recursive: true });
for (const [from, to] of files) {
  const src = path.join(sourceDir, from);
  if (!fs.existsSync(src)) {
    console.error(`[build-fonts] missing ${src} — run npm install`);
    process.exit(1);
  }
  fs.copyFileSync(src, path.join(outDir, to));
  console.log(`[build-fonts] ${to} (${(fs.statSync(src).size / 1024).toFixed(0)} KB)`);
}
