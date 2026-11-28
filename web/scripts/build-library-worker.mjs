// The connector accepts one Edge source file. Bundle only our two small ESM
// modules, leaving the pinned Supabase npm import for Deno's package resolver.
import fs from 'node:fs/promises';
const core = await fs.readFile(new URL('../src/library-storage-core.mjs', import.meta.url), 'utf8');
const worker = await fs.readFile(new URL('../src/library-storage-worker.mjs', import.meta.url), 'utf8');
const entry = await fs.readFile(new URL('../edge/library-storage-cleanup.mjs', import.meta.url), 'utf8');
const coreImport = "import { LIBRARY_STORAGE_BUCKET, storageResult, storageCleanup } from './library-storage-core.mjs';";
const entryImport = "import { createLibraryStorageWorker } from '../src/library-storage-worker.mjs';";
if (worker.split(coreImport).length !== 2 || entry.split(entryImport).length !== 2) throw new Error('Worker source imports changed; update the bundle script.');
const output = new URL('../../.cache/site-owner-storage/library-storage-cleanup.mjs', import.meta.url);
await fs.mkdir(new URL('.', output), { recursive: true });
await fs.writeFile(output, [core, worker.replace(coreImport, ''), entry.replace(entryImport, '')].join('\n'));
console.log('Built single-file library cleanup worker (no credentials included).');
