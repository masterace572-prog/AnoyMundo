// Deploy the single-file output of scripts/build-library-worker.mjs.
// JWT gateway validation is disabled only because this worker uses its own
// randomly generated, Vault-backed task token. It rejects unauthenticated calls.
import { createClient } from 'npm:@supabase/supabase-js@2.119.0';
import { createLibraryStorageWorker } from '../src/library-storage-worker.mjs';

Deno.serve(createLibraryStorageWorker({ createClient, env: Deno.env.toObject() }));
