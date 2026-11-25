import crypto from 'node:crypto';

// Safe live checks: no Owner registration and NO publication/reset of a live
// release. Only a private temporary Storage upload is created, then removed.
export async function publicChannelChecks(request, ok) {
  for (const channel of ['anoy', 'kenny']) {
    const manifest = await request(`/api/lib/manifest?channel=${channel}`);
    ok(manifest.status === 200 && manifest.body?.ok === true && manifest.body.channel === channel,
      `${channel}: manifest explicitly identifies its own channel`);
    const version = await request(`/api/lib/version?channel=${channel}`);
    ok(version.status === 200 && String(version.body).trim() === manifest.body?.version,
      `${channel}: plain version matches its own manifest`);
    const download = await request(`/api/lib/download?channel=${channel}`, { raw: true });
    if (!manifest.body?.available) {
      ok(download.status === 404, `${channel}: unpublished channels serve no other owner's file`);
    } else if (manifest.body?.source === 'file') {
      let buffer = download.buffer;
      let status = download.status;
      if ([301, 302, 303, 307, 308].includes(status)) {
        // New file releases are signed Storage redirects, NOT unverifiable
        // external links. Never forward a panel session cookie to Storage.
        const url = new URL(download.response.headers.get('location'));
        if (url.protocol !== 'https:') throw new Error('A stored library redirected outside HTTPS.');
        const response = await fetch(url, { signal: AbortSignal.timeout(60000), redirect: 'error' });
        status = response.status;
        buffer = Buffer.from(await response.arrayBuffer());
      }
      ok(status === 200 && buffer?.subarray(0, 2).toString() === 'PK', `${channel}: final file download is a ZIP`);
      ok(buffer?.length === Number(manifest.body.size), `${channel}: downloaded size matches the manifest`);
      ok(crypto.createHash('sha256').update(buffer || '').digest('hex') === manifest.body.sha256,
        `${channel}: final downloaded SHA-256 matches (also after signed redirect)`);
      ok(buffer?.includes(Buffer.from('libbgmi.so')), `${channel}: ZIP contains the canonical libbgmi.so`);
    } else {
      ok(download.status === 302 && /^https:\/\//.test(download.response.headers.get('location') || ''),
        `${channel}: an external-link publication redirects over HTTPS`);
    }
    const update = await request(`/api/app/update?channel=${channel}&code=18`);
    ok(update.status === 200 && update.body?.ok === true && update.body.channel === channel,
      `${channel}: APK metadata explicitly identifies its own channel`);
  }
  const bad = await request('/api/lib/manifest?channel=third');
  ok(bad.status === 400, 'unknown release channels are refused (never mapped to an owner)');
}

export async function ownerStorageChecks(request, ok, base, { fetchImpl = globalThis.fetch } = {}) {
  const page = await request('/lib');
  const channel = /data-release-channel="(anoy|kenny)"/.exec(String(page.body))?.[1];
  ok(!!channel, 'signed-in release page derives a fixed Owner channel');
  if (!channel) return;
  // Anoy is the Real Owner and CAN manage both real channels. Never probe
  // authority by resetting a real release: that would change a live selection.
  const selections = () => Promise.all(['anoy', 'kenny'].flatMap(name => [
    `/api/lib/manifest?channel=${name}`, `/api/app/update?channel=${name}&code=0`,
  ]).map(async route => (await request(route)).body));
  const before = await selections();
  const forbidden = await request('/lib', { method: 'POST', body: {
    action: 'prepare_upload', channel: 'not-a-channel', file_size: 128,
  } });
  ok(forbidden.status === 403, 'live panel refuses an unknown-channel upload without changing a real release');
  ok(/up to 20 MB uploaded and 20 MB total unpacked/.test(String(page.body)),
    'live upload UI shows the 20 MB input and combined expanded allowance');
  const oversize = await request('/lib', { method: 'POST', body: {
    action: 'prepare_upload', file_size: 20 * 1024 * 1024 + 1,
  } });
  ok(oversize.status === 400 && /20 MB/.test(oversize.body?.error || ''),
    'live panel rejects a byte above 20 MiB before creating a Storage object');

  let pending;
  let uploaded = false, cleaned = false;
  try {
    const prepared = await request('/lib', { method: 'POST', body: { action: 'prepare_upload', file_size: 20 * 1024 * 1024, file_name: 'live-upload-test.so' } });
    pending = prepared.body?.upload_path;
    ok(prepared.status === 200 && prepared.body?.ok === true && prepared.body.max_bytes === 20 * 1024 * 1024 && pending?.startsWith(`${channel}/pending/`),
      'live panel issues a channel-owned 20 MiB direct-upload ticket');
    if (!prepared.body?.upload_url || !pending) return;
    const uploadUrl = new URL(prepared.body.upload_url, base);
    ok(String(page.response.headers.get('content-security-policy')).includes(uploadUrl.origin),
      'browser CSP allows the exact configured Storage origin');
    const upload = await fetchImpl(uploadUrl, { method: 'PUT', signal: AbortSignal.timeout(90000),
      headers: { 'Content-Type': 'application/octet-stream' }, body: Buffer.alloc(20 * 1024 * 1024) });
    uploaded = upload.ok;
    ok(upload.ok, '20 MiB binary PUT to real private Storage succeeds', `HTTP ${upload.status}`);
    await upload.body?.cancel();
  } finally {
    if (pending) {
      const cancelled = await request('/lib', { method: 'POST', body: { action: 'cancel_upload', upload_path: pending } });
      cleaned = cancelled.status === 200 && cancelled.body?.cancelled === true;
      ok(cleaned, 'cleanup: private test upload removed (never published)');
    }
  }
  const after = await selections();
  const unchanged = JSON.stringify(before) === JSON.stringify(after);
  ok(unchanged, 'both live libraries and APK selections stay byte-for-byte unchanged during upload check');
  return uploaded && cleaned && unchanged && forbidden.status === 403 && oversize.status === 400;
}
