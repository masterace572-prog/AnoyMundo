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

const { templates } = await import('../src/templates.generated.mjs');
Handlebars.registerPartial('header', templates.header);
Handlebars.registerPartial('msgStatus', templates.msgStatus);
for (const [name, source] of Object.entries(templates)) {
  try {
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

if (errors.length) {
  console.error('\n[check] FAILED:');
  for (const e of errors) console.error('  - ' + e);
  process.exit(1);
}
console.log('\n[check] all checks passed');
