// Temporary test account for live-smoke.yml (2.3.3), used when the
// PANEL_OWNER_PASSWORD secret is not available to push runs.
//
// The password is generated here, on the runner, and never leaves it: only
// its bcrypt hash is published (as the commit status "temp-account"). A
// maintainer creates a reseller row with that hash in Supabase, the smoke
// test signs in with it, and the row is deleted again afterwards.
//   GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA, GITHUB_ENV, GITHUB_RUN_ID
import crypto from 'node:crypto';
import fs from 'node:fs';
import { hashPassword } from '../src/auth.mjs';

const user = `SMOKE${Number(process.env.GITHUB_RUN_ID || Date.now()).toString(36).toUpperCase().slice(-6)}`;
const password = `${crypto.randomBytes(18).toString('base64url')}a1`;
const hash = hashPassword(password);

console.log(`::add-mask::${password}`);
fs.appendFileSync(process.env.GITHUB_ENV, `TEMP_USER=${user}\nTEMP_PASSWORD=${password}\n`);

const r = await fetch(`https://api.github.com/repos/${process.env.GITHUB_REPOSITORY}/statuses/${process.env.GITHUB_SHA}`, {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${process.env.GITHUB_TOKEN}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': 'anoy-live-smoke',
  },
  body: JSON.stringify({ state: 'pending', context: 'temp-account', description: `${user} ${hash}` }),
});
if (!r.ok) throw new Error(`could not publish the account request: HTTP ${r.status}`);
console.log(`Requested temporary account ${user} (bcrypt hash published as commit status "temp-account").`);
