// Converts Lucide SVG icons (ISC licence) into Android VectorDrawables.
//   node xlox/scripts/lucide_vectors.mjs
// Source: web/node_modules/lucide-static/icons (installed by `npm ci` in web/).
// Output: xlox/app/src/main/res/drawable/ic_<name>.xml — committed, so the
// Android build never needs Node. Arc flags are re-emitted with spaces because
// older Android path parsers mis-read Lucide's compact "0 0 01" form.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '../../web/node_modules/lucide-static/icons');
const OUT = path.resolve(here, '../app/src/main/res/drawable');

// [lucide name, drawable suffix, colour resource or null (= tinted by the view), size dp]
const ICONS = [
  ['eye', 'eye', null],
  ['eye-off', 'eye_off', null],
  ['clipboard-paste', 'clipboard_paste', null],
  ['key-round', 'key_round', null],
  ['log-out', 'log_out', null],
  ['shield-check', 'shield_check', null],
  ['shield-alert', 'shield_alert', null],
  ['calendar', 'calendar', null],
  ['gamepad-2', 'gamepad', null],
  ['activity', 'activity', null],
  ['package', 'package', null],
  ['server', 'server', null],
  ['smartphone', 'smartphone', null],
  ['cpu', 'cpu', null],
  ['send', 'send_blue', 'blue', 18],
  ['circle-help', 'help_blue', 'blue', 18],
  ['circle-alert', 'alert_red', 'red', 16],
  ['download', 'download_blue', 'blue'],
  ['wifi-off', 'wifi_off_orange', 'orange'],
  ['circle-check', 'circle_check_green', 'green'],
  ['key-round', 'key_round_blue', 'blue'],
];

const NUM = /-?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?/iy;
function tokenize(d) {
  const out = [];
  let i = 0; let cmd = null; let argIndex = 0;
  const arity = { m: 2, l: 2, h: 1, v: 1, c: 6, s: 4, q: 4, t: 2, a: 7, z: 0 };
  const skip = () => { while (i < d.length && /[\s,]/.test(d[i])) i += 1; };
  while (true) {
    skip();
    if (i >= d.length) break;
    if (/[a-z]/i.test(d[i])) { cmd = d[i]; out.push(cmd); i += 1; argIndex = 0; continue; }
    if (!cmd) throw new Error(`bad path: ${d}`);
    const lc = cmd.toLowerCase();
    const pos = argIndex % arity[lc];
    if (lc === 'a' && (pos === 3 || pos === 4)) {
      out.push(d[i]); i += 1; // single-character flag
    } else {
      NUM.lastIndex = i;
      const m = NUM.exec(d);
      if (!m) throw new Error(`bad number at ${i} in ${d}`);
      out.push(m[0].replace(/^(-?)\./, '$10.')); i = NUM.lastIndex;
    }
    argIndex += 1;
  }
  return out.join(' ').replace(/ ([a-zA-Z]) /g, ' $1').replace(/^([a-zA-Z]) /, '$1');
}

const f = (n) => String(Math.round(Number(n) * 1000) / 1000);
function attrs(tag) {
  const o = {};
  for (const m of tag.matchAll(/([a-z][a-z0-9-]*)="([^"]*)"/g)) o[m[1]] = m[2];
  return o;
}
function toPath(el, a) {
  switch (el) {
    case 'path': return tokenize(a.d);
    case 'line': return `M${f(a.x1)} ${f(a.y1)}L${f(a.x2)} ${f(a.y2)}`;
    case 'polyline':
    case 'polygon': {
      const p = a.points.trim().split(/[\s,]+/);
      let s = `M${p[0]} ${p[1]}`;
      for (let k = 2; k < p.length; k += 2) s += `L${p[k]} ${p[k + 1]}`;
      return el === 'polygon' ? `${s}Z` : s;
    }
    case 'circle': {
      const cx = +a.cx; const cy = +a.cy; const r = +a.r;
      return `M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 0 ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 0 ${f(cx - r)} ${f(cy)}Z`;
    }
    case 'ellipse': {
      const cx = +a.cx; const cy = +a.cy; const rx = +a.rx; const ry = +a.ry;
      return `M${f(cx - rx)} ${f(cy)}A${f(rx)} ${f(ry)} 0 1 0 ${f(cx + rx)} ${f(cy)}A${f(rx)} ${f(ry)} 0 1 0 ${f(cx - rx)} ${f(cy)}Z`;
    }
    case 'rect': {
      const x = +(a.x || 0); const y = +(a.y || 0); const w = +a.width; const h = +a.height;
      let rx = a.rx != null ? +a.rx : (a.ry != null ? +a.ry : 0);
      rx = Math.min(rx, w / 2, h / 2);
      if (!rx) return `M${f(x)} ${f(y)}H${f(x + w)}V${f(y + h)}H${f(x)}Z`;
      return `M${f(x + rx)} ${f(y)}H${f(x + w - rx)}A${f(rx)} ${f(rx)} 0 0 1 ${f(x + w)} ${f(y + rx)}` +
        `V${f(y + h - rx)}A${f(rx)} ${f(rx)} 0 0 1 ${f(x + w - rx)} ${f(y + h)}` +
        `H${f(x + rx)}A${f(rx)} ${f(rx)} 0 0 1 ${f(x)} ${f(y + h - rx)}` +
        `V${f(y + rx)}A${f(rx)} ${f(rx)} 0 0 1 ${f(x + rx)} ${f(y)}Z`;
    }
    default: throw new Error(`unsupported element <${el}>`);
  }
}

if (!existsSync(SRC)) {
  console.log(`[lucide-vectors] ${SRC} missing; run npm ci in web/ first. Keeping committed drawables.`);
  process.exit(0);
}

for (const [name, suffix, colour, size = 24] of ICONS) {
  const svg = readFileSync(path.join(SRC, `${name}.svg`), 'utf8');
  const paths = [];
  for (const m of svg.matchAll(/<(path|line|polyline|polygon|circle|ellipse|rect)\b([^>]*?)\/?>/g)) {
    const a = attrs(m[2]);
    const filled = a.fill === 'currentColor';
    const d = toPath(m[1], a);
    if (/NaN|undefined/.test(d)) throw new Error(`${name}: bad <${m[1]}> -> ${d}`);
    paths.push({ d, filled });
  }
  const colourRef = colour ? `@color/${colour}` : '@color/white';
  const body = paths.map((p) => p.filled
    ? `    <path\n        android:fillColor="${colourRef}"\n        android:pathData="${p.d}" />`
    : `    <path\n        android:fillColor="@android:color/transparent"\n        android:pathData="${p.d}"\n        android:strokeColor="${colourRef}"\n        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n        android:strokeWidth="1.75" />`).join('\n');
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Lucide "${name}" (ISC licence), generated by xlox/scripts/lucide_vectors.mjs. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp"
    android:height="${size}dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
${body}
</vector>
`;
  writeFileSync(path.join(OUT, `ic_${suffix}.xml`), xml);
}
console.log(`[lucide-vectors] ${ICONS.length} drawables -> ${path.relative(process.cwd(), OUT)}`);
