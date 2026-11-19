import { zipSync } from 'fflate';
import { inflateRawSync } from 'node:zlib';

export const MAX_LIBRARY_BYTES = 10 * 1024 * 1024;
export const LIBRARY_NAME = 'libbgmi.so';
export const LIBRARY_BUCKET = 'loader-releases';

export function isArm64Elf(bytes) {
  return bytes.length >= 64 && bytes[0] === 0x7f && bytes[1] === 0x45
    && bytes[2] === 0x4c && bytes[3] === 0x46 && bytes[4] === 2
    && bytes[5] === 1 && bytes[18] === 183 && bytes[19] === 0;
}

const CRC_TABLE = Uint32Array.from({ length: 256 }, (_, n) => {
  for (let i = 0; i < 8; i++) n = (n >>> 1) ^ ((n & 1) ? 0xedb88320 : 0);
  return n >>> 0;
});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const b of bytes) crc = CRC_TABLE[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
const damaged = () => { throw new Error('The ZIP is invalid, encrypted or damaged.'); };

/** Strict ZIP32 reader: bounded ENTRY COUNT, compressed bytes and ACTUAL
 * inflated output. Never trust just the advertised uncompressed size: a ZIP
 * bomb may lie about it. zlib stops before allocating beyond the entry cap.
 * No archive paths are written to the filesystem, and CRCs are checked.
 */
function readZip(bytes) {
  let end = bytes.length - 22;
  const min = Math.max(0, end - 65535);
  while (end >= min && (bytes.readUInt32LE(end) !== 0x06054b50
    || end + 22 + bytes.readUInt16LE(end + 20) !== bytes.length)) end--;
  if (end < min) damaged();
  const count = bytes.readUInt16LE(end + 10);
  const start = bytes.readUInt32LE(end + 16);
  const size = bytes.readUInt32LE(end + 12);
  if (bytes.readUInt16LE(end + 4) || bytes.readUInt16LE(end + 6)
    || bytes.readUInt16LE(end + 8) !== count || count === 0xffff
    || start + size !== end) damaged();
  if (count > 128) throw new Error('ZIP exceeds the 128 entry limit.');
  const result = new Map();
  const ranges = [];
  let expanded = 0;
  let at = start;
  for (let i = 0; i < count; i++) {
    if (at + 46 > end || bytes.readUInt32LE(at) !== 0x02014b50) damaged();
    const flags = bytes.readUInt16LE(at + 8);
    const method = bytes.readUInt16LE(at + 10);
    const crc = bytes.readUInt32LE(at + 16);
    const compressed = bytes.readUInt32LE(at + 20);
    const original = bytes.readUInt32LE(at + 24);
    const nameLength = bytes.readUInt16LE(at + 28);
    const next = at + 46 + nameLength + bytes.readUInt16LE(at + 30) + bytes.readUInt16LE(at + 32);
    const local = bytes.readUInt32LE(at + 42);
    if (next > end || (flags & 0x41) || ![0, 8].includes(method) || local + 30 > start) damaged();
    const nameBytes = bytes.subarray(at + 46, at + 46 + nameLength);
    const name = nameBytes.toString('utf8');
    if (!name || name.includes('\0') || name.includes('\\') || name.startsWith('/')
      || /^[A-Za-z]:/.test(name) || name.split('/').includes('..')) throw new Error('ZIP contains an unsafe path.');
    if (result.has(name)) damaged();
    expanded += original;
    if (expanded > MAX_LIBRARY_BYTES) throw new Error('ZIP contents exceed the 10 MB expanded limit.');
    if (bytes.readUInt32LE(local) !== 0x04034b50
      || bytes.readUInt16LE(local + 6) !== flags || bytes.readUInt16LE(local + 8) !== method) damaged();
    const localNameLength = bytes.readUInt16LE(local + 26);
    const dataAt = local + 30 + localNameLength + bytes.readUInt16LE(local + 28);
    const dataEnd = dataAt + compressed;
    if (dataEnd > start || !bytes.subarray(local + 30, local + 30 + localNameLength).equals(nameBytes)
      || ranges.some(([a, b]) => local < b && dataEnd > a)) damaged();
    ranges.push([local, dataEnd]);
    if (!(flags & 8) && (bytes.readUInt32LE(local + 14) !== crc
      || bytes.readUInt32LE(local + 18) !== compressed || bytes.readUInt32LE(local + 22) !== original)) damaged();
    let out;
    try {
      out = method === 0 ? bytes.subarray(dataAt, dataEnd)
        : inflateRawSync(bytes.subarray(dataAt, dataEnd), { maxOutputLength: Math.max(1, original) });
    } catch { damaged(); }
    if (out.length !== original || crc32(out) !== crc) damaged();
    result.set(name, out);
    at = next;
  }
  if (at !== end) damaged();
  return result;
}

/** Inputs and expanded ZIP contents are capped at 10 MiB. The result is a ZIP
 * with a canonical libbgmi.so, preserving existing Android download contracts.
 * Only verified arm64 sibling .so dependencies are retained. Input filenames
 * are deliberately irrelevant, including names inside wrapping ZIP folders.
 */
export function normalizeLibrary(input) {
  const bytes = Buffer.from(input);
  if (bytes.length > MAX_LIBRARY_BYTES) throw new Error('Library uploads must be 10 MB or smaller.');
  const files = Object.create(null);
  if (isArm64Elf(bytes)) {
    files[LIBRARY_NAME] = bytes;
  } else {
    if (bytes.length < 22 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) throw new Error('Choose an arm64 .so library or a ZIP containing one.');
    const unpacked = readZip(bytes);
    const candidates = [...unpacked.keys()].filter((name) => isArm64Elf(unpacked.get(name)));
    const canonical = candidates.filter((name) => name.split('/').pop().toLowerCase() === LIBRARY_NAME);
    const legacy = candidates.filter((name) => name.split('/').pop().toLowerCase() === 'lib.so');
    const main = canonical.length === 1 ? canonical[0] : legacy.length === 1 ? legacy[0] : candidates.length === 1 ? candidates[0] : null;
    if (!main) throw new Error('ZIP must contain one identifiable arm64 library (use libbgmi.so if there are several).');
    const parent = main.slice(0, main.lastIndexOf('/') + 1);
    for (const name of candidates) {
      const base = name.split('/').pop();
      if (name !== main && name.slice(0, name.lastIndexOf('/') + 1) === parent && /^[A-Za-z0-9_.+-]+\.so$/.test(base)) {
        if (base.toLowerCase() === LIBRARY_NAME) throw new Error('ZIP contains conflicting main libraries.');
        files[base] = unpacked.get(name);
      }
    }
    files[LIBRARY_NAME] = unpacked.get(main);
  }
  // Stable timestamps make hashes deterministic across machines and retries.
  return Buffer.from(zipSync(files, { level: 6, mtime: new Date('2000-01-01T00:00:00Z') }));
}
