// Explicit operational command. Dry-run unless --apply is supplied.
// Run inside the site's configured environment; credentials are never printed.
import { db } from '../src/db.mjs';
import { libraryStoragePlan, cleanLibraryStorage } from '../src/library-storage.mjs';
import { verifyStoredLibraries } from '../src/library-storage-worker.mjs';

const args = process.argv.slice(2);
if (args.some(arg => !['--apply', '--channel=anoy', '--channel=kenny'].includes(arg))
    || args.filter(arg => arg.startsWith('--channel=')).length > 1) {
  console.error('Usage: npm run storage:cleanup -- [--apply] [--channel=anoy|kenny]');
  process.exit(1);
}
const apply = args.includes('--apply');
const channel = args.find(arg => arg.startsWith('--channel='))?.split('=')[1] || null;
try {
  const plan = await libraryStoragePlan({ channel });
  console.log(JSON.stringify({ mode: apply ? 'apply' : 'dry-run', channel: channel || 'both', plan }));
  if (apply) {
    const before = await verifyStoredLibraries(db());
    const cleanup = await cleanLibraryStorage({ channel, maxBatches: 50 });
    if (!cleanup.ok) throw new Error('Cleanup needs a retry.');
    const after = await verifyStoredLibraries(db());
    console.log(JSON.stringify({ ok: true, cleanup, status: cleanup.pending ? 'queued-for-download-grace-or-retry' : 'complete',
      verified_current_before: before, verified_current_after: after }));
  }
} catch {
  // Do not print Supabase exception messages, keys, signed URLs or payloads.
  console.error('Library storage maintenance did not finish. Current releases were not deliberately removed; inspect the cleanup queue before retrying.');
  process.exitCode = 1;
}
