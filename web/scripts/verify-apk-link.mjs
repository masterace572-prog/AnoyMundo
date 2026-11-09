// Runs the panel's real APK link check (src/app-release.mjs) from the command
// line, against the live network. Used by .github/workflows/app-update-e2e.yml.
//
//   node scripts/verify-apk-link.mjs --latest          newest anoy-v<name>-<code> release must pass
//   node scripts/verify-apk-link.mjs --good URL ...     each URL must pass
//   node scripts/verify-apk-link.mjs --bad URL ...      each URL must be refused
//
// Prints GitHub annotations (::notice:: / ::error::); exits 1 on any surprise.
// With --latest it also writes LATEST_NAME / LATEST_CODE / LATEST_URL to
// $GITHUB_OUTPUT when that is set.
import fs from 'node:fs';
import { verifyApkLink, pickLatestBuild } from '../src/app-release.mjs';

const repo = process.env.GITHUB_REPOSITORY || 'masterace572-prog/AnoyMundo';
const selfHosts = ['anoymundo-panel.vercel.app'];
let mode = 'good';
let failures = 0;
const one = (s) => String(s).replace(/[\r\n]+/g, ' ');

async function expect(url, shouldPass) {
  const started = Date.now();
  const r = await verifyApkLink(url, { selfHosts });
  const ms = Date.now() - started;
  if (r.ok === shouldPass) {
    console.log(`::notice title=APK link check ${shouldPass ? 'passed' : 'refused as expected'}::${one(url)} -> ${r.ok ? `HTTP ${r.status} from ${r.host}, starts with PK` : one(r.error)} (${ms} ms)`);
  } else {
    failures++;
    console.log(`::error title=APK link check wrong::${one(url)} should ${shouldPass ? 'pass' : 'be refused'} but got: ${r.ok ? `ok (HTTP ${r.status})` : one(r.error)}`);
  }
}

for (const arg of process.argv.slice(2)) {
  if (arg === '--good') { mode = 'good'; continue; }
  if (arg === '--bad') { mode = 'bad'; continue; }
  if (arg === '--latest') {
    let status = 'unreachable';
    let build = null;
    try {
      const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=20`, {
        headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'AnoyPanel/2' },
      });
      status = `HTTP ${res.status}`;
      if (res.ok) build = pickLatestBuild(await res.json());
    } catch (e) {
      status = one(e?.cause?.code || e?.message || e);
    }
    if (!build) {
      failures++;
      console.log(`::error title=Use latest build::no anoy-v<name>-<code> release with the APK (GitHub ${status})`);
      continue;
    }
    console.log(`::notice title=Use latest build::${build.tag} -> ${build.version_name} (${build.version_code}) ${build.apk_url}`);
    if (process.env.GITHUB_OUTPUT) {
      fs.appendFileSync(process.env.GITHUB_OUTPUT,
        `LATEST_NAME=${build.version_name}\nLATEST_CODE=${build.version_code}\nLATEST_URL=${build.apk_url}\n`);
    }
    await expect(build.apk_url, true);
    continue;
  }
  await expect(arg, mode === 'good');
}

process.exit(failures ? 1 : 0);
