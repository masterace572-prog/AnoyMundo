// Build-time validation: templates compile, every module imports, the Owner
// password hash verifies, the stylesheet exists and key rules hold.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Handlebars from 'handlebars';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const errors = [];

Handlebars.registerHelper('eq', () => true);
Handlebars.registerHelper('neq', () => true);
Handlebars.registerHelper('gt', () => true);
Handlebars.registerHelper('lt', () => true);
Handlebars.registerHelper('and', () => true);
Handlebars.registerHelper('or', () => true);
Handlebars.registerHelper('json', () => '');
Handlebars.registerHelper('alertClass', () => 'alert-info');

const { templates } = await import('../src/templates.generated.mjs');
Handlebars.registerPartial('header', templates.header);
Handlebars.registerPartial('msgStatus', templates.msgStatus);
for (const [name, source] of Object.entries(templates)) {
  try {
    // Handlebars.compile is lazy, so parse explicitly to catch syntax errors.
    Handlebars.parse(source);
    Handlebars.compile(source);
    process.stdout.write(`  ok  template ${name}\n`);
  } catch (e) {
    errors.push(`template ${name}: ${e.message}`);
  }
}

for (const mod of ['../src/config.mjs', '../src/memory-db.mjs', '../src/db.mjs',
  '../src/auth.mjs', '../src/render.mjs', '../src/http.mjs',
  '../src/licensing.mjs', '../src/key-service.mjs', '../src/api-keys.mjs',
  '../src/app.mjs', '../api/index.js']) {
  try { await import(mod); process.stdout.write(`  ok  module ${mod}\n`); }
  catch (e) { errors.push(`module ${mod}: ${e.stack || e.message}`); }
}

try {
  const { verifyPassword, hashPassword } = await import('../src/auth.mjs');
  const owner = '$2a$08$sQefH.l6cnpeUwuTifoZvuF1SSh7U6Mw4UwddAO9cawj5pip8blOa';
  if (!verifyPassword('Anoy1764', owner)) errors.push('Owner bcrypt hash did not verify');
  else process.stdout.write('  ok  Owner password hash (Anoy)\n');
  const legacy = '$2y$08$/CsSVgrGgCqVcievCuR2COPnlMIpRz6kA.hzItBD/xd1Cx0hj0kMK';
  if (!verifyPassword('admin123', legacy)) errors.push('legacy $2y$ bcrypt hash did not verify');
  const fresh = hashPassword('test1234');
  if (!verifyPassword('test1234', fresh) || verifyPassword('wrong', fresh)) errors.push('password hashing round-trip failed');
} catch (e) { errors.push(`password check: ${e.message}`); }

// ---- static assets ----------------------------------------------------------
for (const asset of ['public/assets/css/ui.css', 'public/assets/js/app-ui.js', 'public/favicon.svg',
  'public/assets/fonts/inter-latin-wght-normal.woff2', 'public/assets/fonts/INTER-LICENSE.txt']) {
  if (!fs.existsSync(path.join(root, asset))) errors.push(`missing static asset: ${asset}`);
}
const src = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const uiCss = src('public/assets/css/ui.css');
if (/gradient\(/.test(uiCss)) errors.push('ui.css contains a gradient');
if (!/--bg:\s*#000/.test(uiCss) || !/--blue:\s*#0a84ff/i.test(uiCss)) errors.push('ui.css must keep the black / blue palette');

// ---- views: no CDN, no logo, no PHP, no legacy frameworks -------------------
const viewsDir = path.join(root, 'views');
for (const file of fs.readdirSync(viewsDir).filter((f) => f.endsWith('.hbs'))) {
  const source = fs.readFileSync(path.join(viewsDir, file), 'utf8');
  if (/<\?|&lt;\?/.test(source)) errors.push(`${file}: untranslated PHP`);
  if (/https?:\/\/(cdn|code\.jquery|cdnjs|unpkg)/.test(source)) errors.push(`${file}: loads a CDN asset`);
  if (/ic_anoy|logo\.(png|svg)|brand-mark/.test(source)) errors.push(`${file}: uses a logo`);
  if (/\$\(|jQuery|DataTable|Swal\./.test(source)) errors.push(`${file}: legacy jQuery/DataTables/SweetAlert code`);
  if (/tailwind|bg-white|text-gray-|rounded-xl/.test(source)) errors.push(`${file}: leftover Tailwind classes`);
}
const appSource = src('src/app.mjs');
if (/<i class|<strong>|&middot;/.test(appSource)) errors.push('server messages still contain HTML markup');

// ---- billing / security / key format guards ---------------------------------
const keyService = src('src/key-service.mjs');
if (!/create_keys_atomic/.test(keyService) || !/p_total_price/.test(keyService)) errors.push('key-service.mjs must use the atomic SQL transaction');
if (!/FAIL CLOSED/.test(keyService)) errors.push('key-service.mjs must fail closed when the transaction is unavailable');
if (!/delete_keys_refund/.test(keyService) || !/price_paid/.test(keyService)) errors.push('key-service.mjs must refund never-used keys');
if (!/handleFailure/.test(appSource) || !/sameOriginMutation/.test(appSource) || !/cross_site_blocked/.test(appSource)) {
  errors.push('app.mjs must keep the friendly error handler and the same-origin guard');
}
if (!/let p = '\/';/.test(appSource)) errors.push('handle() must declare the path outside try so the error handler can use it');
if (!/if \(!user\.isOwner\) query = query\.eq\('registrator', user\.username\);/.test(appSource)) {
  errors.push('non-owner key lists and downloads must be scoped to their own keys');
}
if (!/pv:/.test(src('src/auth.mjs')) || !/session\.pv/.test(src('src/auth.mjs'))) errors.push('sessions must be bound to the password hash');
const generateView = src('views/keys_generate.hbs');
if (!/name="count"/.test(generateView) || !/name="request_id"/.test(generateView) || !/name="custom"/.test(generateView)) {
  errors.push('generate view must post count, custom and an idempotency id');
}
if (!/floating_form/.test(src('views/server.hbs'))) errors.push('server view must post the hidden floating_form marker');
if (!/action: 'link'/.test(src('views/lib.hbs'))) errors.push('library view must support pushing an external link');

try {
  const ks = await import('../src/key-service.mjs');
  const samples = [];
  for (const h of [2, 5, 24, 72, 168, 336, 720]) samples.push(ks.keyPrefix('Anoy', h) + ks.randomKeyPart());
  const bad = samples.filter((k) => !/^ANOY-(2H|5H|1D|3D|7D|14D|30D)-[A-Z0-9]{5}$/.test(k));
  if (bad.length) errors.push(`key format wrong: ${bad.join(', ')}`);
  if (ks.parseCustomSuffix('anoy-7d-vip1', 'ANOY-7D-').suffix !== 'VIP1') errors.push('custom key prefix stripping failed');
  if (!ks.parseCustomSuffix('ab', 'ANOY-7D-').error) errors.push('too-short custom key accepted');
  if (!errors.length) process.stdout.write(`  ok  key format (${samples[4]})\n`);
} catch (e) { errors.push(`key format check: ${e.message}`); }

if (!errors.length) process.stdout.write('  ok  billing, security and UI guards\n');

if (errors.length) {
  console.error('\n[check] FAILED:');
  for (const e of errors) console.error('  - ' + e);
  process.exit(1);
}
console.log('\n[check] all checks passed');
