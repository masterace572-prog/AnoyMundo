// Compiles Tailwind from the .hbs templates into a static stylesheet.
// Static CSS means no runtime CDN dependency on cdn.tailwindcss.com.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const input = path.join(root, 'src', 'tailwind.input.css');
const output = path.join(root, 'public', 'assets', 'css', 'tailwind.css');
const cli = path.join(root, 'node_modules', 'tailwindcss', 'lib', 'cli.js');

fs.mkdirSync(path.dirname(output), { recursive: true });
execFileSync(process.execPath, [cli, '-i', input, '-o', output, '--minify'], {
  cwd: root,
  stdio: 'inherit',
});
console.log(`[build-css] ${(fs.statSync(output).size / 1024).toFixed(0)} KB -> ${path.relative(root, output)}`);
