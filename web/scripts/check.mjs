// Build-time validation: templates compile, every module imports, legacy
// password hash verifies, and the compiled stylesheet exists.
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
Handlebars.registerHelper('alertClass', () => 'ui-alert--info');

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
  '../src/licensing.mjs', '../src/app.mjs', '../api/index.js']) {
  try { await import(mod); process.stdout.write(`  ok  module ${mod}\n`); }
  catch (e) { errors.push(`module ${mod}: ${e.stack || e.message}`); }
}

try {
  const { verifyPassword, hashPassword } = await import('../src/auth.mjs');
  const legacy = '$2y$08$/CsSVgrGgCqVcievCuR2COPnlMIpRz6kA.hzItBD/xd1Cx0hj0kMK';
  if (!verifyPassword('admin123', legacy)) errors.push('legacy admin bcrypt hash did not verify');
  else process.stdout.write('  ok  legacy password compatibility (admin/admin123)\n');
  const fresh = hashPassword('test1234');
  if (!verifyPassword('test1234', fresh) || verifyPassword('wrong', fresh)) errors.push('password hashing round-trip failed');
} catch (e) { errors.push(`password check: ${e.message}`); }

const css = path.join(root, 'public', 'assets', 'css', 'tailwind.css');
if (!fs.existsSync(css) || fs.statSync(css).size < 1000) errors.push('compiled Tailwind CSS is missing or too small');
else process.stdout.write(`  ok  tailwind.css (${(fs.statSync(css).size / 1024).toFixed(0)} KB)\n`);

// ---- UI regression guards -------------------------------------------------
const viewsDir = path.join(root, 'views');
const leaked = [];
for (const file of fs.readdirSync(viewsDir).filter((f) => f.endsWith('.hbs'))) {
  const source = fs.readFileSync(path.join(viewsDir, file), 'utf8');
  if (/<\?|&lt;\?|linear-gradient|bg-gradient|glass-card|gradient-bg/.test(source)) {
    leaked.push(`${file}: untranslated PHP or gradient markup`);
  }
}
if (leaked.length) errors.push(...leaked);
else process.stdout.write('  ok  views contain no PHP tags and no gradients\n');

const appSource = fs.readFileSync(path.join(root, 'src', 'app.mjs'), 'utf8');
if (/<i class|<strong>|&middot;/.test(appSource)) errors.push('server messages still contain HTML markup');
else process.stdout.write('  ok  flash/error messages are plain text\n');

const uiCss = path.join(root, 'public', 'assets', 'css', 'app.css');
if (!fs.existsSync(uiCss) || fs.statSync(uiCss).size < 5000) errors.push('app.css design system is missing');
else if (/linear-gradient|radial-gradient/.test(fs.readFileSync(uiCss, 'utf8'))) errors.push('app.css contains a gradient');
else process.stdout.write(`  ok  app.css flat design system (${(fs.statSync(uiCss).size / 1024).toFixed(0)} KB)\n`);

// The global form padding shorthand overrides Tailwind's pl-* utilities, so
// icon-prefixed inputs (saldo) need an explicit, stronger padding rule.
const uiCssSource = fs.existsSync(uiCss) ? fs.readFileSync(uiCss, 'utf8') : '';
if (!/\.field-prefix > input[\s\S]{0,160}padding-left:\s*2\.875rem\s*!important/.test(uiCssSource)) {
  errors.push('app.css is missing the icon-prefix input padding fix (saldo digits overlap the icon)');
} else process.stdout.write('  ok  saldo icon-prefix padding fix present\n');
for (const view of ['admin_user_edit.hbs', 'admin_referral.hbs']) {
  const src = fs.readFileSync(path.join(viewsDir, view), 'utf8');
  if (!/class="relative field-prefix"/.test(src) || !/class="field-prefix__icon"/.test(src)) {
    errors.push(`${view} is missing the field-prefix wrapper/icon classes`);
  }
}

for (const asset of ['public/assets/js/app-ui.js', 'public/assets/fonts/inter-latin-wght-normal.woff2',
  'public/assets/fonts/INTER-LICENSE.txt']) {
  if (!fs.existsSync(path.join(root, asset))) errors.push(`missing static asset: ${asset}`);
}

if (errors.length) {
  console.error('\n[check] FAILED:');
  for (const e of errors) console.error('  - ' + e);
  process.exit(1);
}
console.log('\n[check] all checks passed');
