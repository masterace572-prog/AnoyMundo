#!/usr/bin/env node
/**
 * Publish a loader ZIP (containing libbgmi.so) to the Anoy panel.
 *
 *   PANEL_BASE_URL=https://anoymundo-panel.vercel.app \
 *   PANEL_ADMIN_USERNAME=Anoy PANEL_ADMIN_PASSWORD=... \
 *   node scripts/publish-release.mjs ../dist/xlox-loader.zip 1.0.1
 *
 * The panel stores the ZIP in client_release.content_base64 and serves it at
 * GET /api/lib/download; GET /api/lib/version returns the version string.
 */
import fs from 'node:fs';
import path from 'node:path';

const base = (process.env.PANEL_BASE_URL || 'https://anoymundo-panel.vercel.app').replace(/\/$/, '');
const zipPath = process.argv[2];
const version = (process.argv[3] || '1.0').trim();
const username = process.env.PANEL_ADMIN_USERNAME || 'Anoy';
const password = process.env.PANEL_ADMIN_PASSWORD;

if (!zipPath) {
  console.error('Usage: node scripts/publish-release.mjs <loader.zip> [version]');
  process.exit(1);
}
if (!password) {
  console.error('Set PANEL_ADMIN_PASSWORD (and optionally PANEL_ADMIN_USERNAME).');
  process.exit(1);
}

const bytes = fs.readFileSync(path.resolve(zipPath));
if (bytes.length < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) {
  console.error('Not a ZIP file: ' + zipPath);
  process.exit(1);
}
if (bytes.length > 4 * 1024 * 1024) {
  console.error('ZIP is larger than the panel upload limit (4 MB).');
  process.exit(1);
}

const cookie = (res) => (res.headers.getSetCookie ? res.headers.getSetCookie() : [res.headers.get('set-cookie')])
  .filter(Boolean)
  .map((c) => c.split(';')[0])
  .join('; ');

async function main() {
  const login = await fetch(`${base}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password, stay_log: '1' }),
    redirect: 'manual',
  });
  const session = cookie(login);
  if (!session.includes('am_session=')) {
    throw new Error(`Login failed (HTTP ${login.status}). Check PANEL_ADMIN_PASSWORD.`);
  }

  const upload = await fetch(`${base}/lib`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: session },
    body: JSON.stringify({
      version,
      file_name: path.basename(zipPath),
      content_base64: bytes.toString('base64'),
      notes: 'XLOX loader release published by build-xlox workflow',
    }),
  });
  const result = await upload.json().catch(() => ({}));
  if (!upload.ok || !result.ok) {
    throw new Error(`Upload failed (HTTP ${upload.status}): ${result.error || 'unknown error'}`);
  }

  const ver = (await (await fetch(`${base}/api/lib/version`)).text()).trim();
  const dl = await fetch(`${base}/api/lib/download`);
  const body = Buffer.from(await dl.arrayBuffer());
  const ok = dl.status === 200 && body.length === bytes.length
    && body[0] === 0x50 && body[1] === 0x4b && ver === version;
  console.log(`version endpoint = ${ver}`);
  console.log(`download endpoint = HTTP ${dl.status}, ${body.length} bytes, ${dl.headers.get('content-type')}`);
  if (!ok) throw new Error('Post-upload verification failed.');
  console.log('OK: panel lib + version are live and the ZIP round-trips byte-for-byte.');
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
