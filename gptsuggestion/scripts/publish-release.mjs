#!/usr/bin/env node
/** Publish a .so or ZIP (up to 10 MiB) to the signed-in Owner's own channel.
 * Files go straight to private Storage; no large base64 Vercel request.
 *
 * PANEL_CHANNEL=anoy PANEL_ADMIN_USERNAME=Anoy PANEL_ADMIN_PASSWORD=... \
 *   node scripts/publish-release.mjs path/to/library.so 1.4
 *
 * For KennyPvt use PANEL_CHANNEL=kenny and that Owner's own credentials.
 * A channel mismatch is rejected by the panel, never silently redirected.
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const base = (process.env.PANEL_BASE_URL || 'https://anoymundo-panel.vercel.app').replace(/\/$/, '');
const input = process.argv[2];
const version = (process.argv[3] || '1.0').trim();
const channel = process.env.PANEL_CHANNEL || 'anoy';
const username = process.env.PANEL_ADMIN_USERNAME || (channel === 'anoy' ? 'Anoy' : '');
const password = process.env.PANEL_ADMIN_PASSWORD;
const MAX = 10 * 1024 * 1024;
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

const cookie = (res) => res.headers.getSetCookie().map(c => c.split(';')[0]).join('; ');

async function main() {
  if (!input) throw new Error('Usage: node scripts/publish-release.mjs <library.so|release.zip> [version]');
  if (!['anoy', 'kenny'].includes(channel)) throw new Error('PANEL_CHANNEL must be anoy or kenny.');
  if (!username || !password) throw new Error('Set PANEL_ADMIN_USERNAME and PANEL_ADMIN_PASSWORD for the channel Owner.');
  const bytes = fs.readFileSync(path.resolve(input));
  if (bytes.length < 64 || bytes.length > MAX) throw new Error('Choose a .so or ZIP between 64 bytes and 10 MiB.');

  const login = await fetch(`${base}/login`, {
    method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(30000),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password, stay_log: '1' }),
  });
  const session = cookie(login);
  if (!session.includes('am_session=')) throw new Error(`Sign-in failed (HTTP ${login.status}). Check the Owner credentials.`);

  const post = async (body) => {
    const r = await fetch(`${base}/lib`, {
      method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(120000),
      headers: { 'Content-Type': 'application/json', Cookie: session },
      body: JSON.stringify({ ...body, channel }),
    });
    const result = await r.json().catch(() => ({}));
    if (!r.ok || !result.ok) throw new Error(`Panel HTTP ${r.status}: ${result.error || 'publication not confirmed; reload the channel before retrying'}`);
    return result;
  };
  const ticket = await post({ action: 'prepare_upload', file_size: bytes.length, file_name: path.basename(input) });
  const upload = await fetch(new URL(ticket.upload_url, base), {
    method: 'PUT', signal: AbortSignal.timeout(180000),
    headers: { 'Content-Type': 'application/octet-stream' }, body: bytes,
  });
  if (!upload.ok) throw new Error(`Direct Storage upload failed (HTTP ${upload.status}).`);
  await upload.body?.cancel();
  const result = await post({ action: 'upload', version, upload_path: ticket.upload_path,
    notes: process.env.PANEL_RELEASE_NOTES || 'Library published with the release uploader' });

  // Normalization changes the uploaded ZIP, so compare its published SHA256,
  // not the caller's original filename/ZIP layout/bytes.
  const manifestResponse = await fetch(`${base}/api/lib/manifest?channel=${channel}`, { signal: AbortSignal.timeout(30000) });
  const manifest = await manifestResponse.json();
  const dl = await fetch(`${base}/api/lib/download?channel=${channel}`, { signal: AbortSignal.timeout(120000) });
  const output = Buffer.from(await dl.arrayBuffer());
  if (!dl.ok || manifest.channel !== channel || manifest.version !== version
    || manifest.file_name !== 'libbgmi.so' || output[0] !== 0x50 || output[1] !== 0x4b
    || sha(output) !== result.sha256 || manifest.sha256 !== result.sha256) {
    throw new Error('Post-publication verification failed. Reload your channel before trying again.');
  }
  console.log(`OK: ${channel} library ${version}, libbgmi.so in ZIP, ${output.length} bytes, sha256 ${result.sha256}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
