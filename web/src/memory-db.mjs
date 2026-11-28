// In-memory implementation of the tiny slice of the Supabase JS query builder
// used by this app. Enabled with DB_DRIVER=memory (used by the offline test
// suite and `npm run dev -- --memory`). Nothing here is used in production.

import crypto from 'node:crypto';
import bcrypt from 'bcryptjs';
import { config } from './config.mjs';
import { createMemoryStorage } from './memory-storage.mjs';

const fixtureHash = value => crypto.createHash('md5').update(config.passwordSalt + value).digest('hex');

const seed = () => {
  const password = process.env.MEMORY_OWNER_PASSWORD;
  const ownerReferral = process.env.MEMORY_KENNY_REFERRAL;
  return ({
  users: [{
    // Offline-only account; no production password or usable default.
    id_users: 1, fullname: 'Anoy', username: 'Anoy', email: 'owner@example.invalid',
    reset_link_token: '', exp_date: '', level: 1, saldo: 999999999999, status: 1,
    owner_channel: 'anoy', uplink: 'Owner', password: password ? bcrypt.hashSync(fixtureHash(password), 8) : '!setup-required!',
    user_ip: '', created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z', expiration_date: null,
  }],
  keys_code: [
    { id_keys: 1, game: 'PUBG', user_key: 'ANOY-2H-LYK0Y', duration: 2, expired_date: null, max_devices: 1, devices: null, status: 1, registrator: 'Anoy', price_paid: 0, created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z' },
    { id_keys: 2, game: 'PUBG', user_key: 'ANOY-2H-F7VWE', duration: 2, expired_date: null, max_devices: 1, devices: null, status: 1, registrator: 'Anoy', price_paid: 0, created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z' },
  ],
  history: [],
  referral_code: [{
    id_reff: 1, code: 'f36d16b166f38a1a148a2069a74c866c', referral: 'WELCOME', level: 3,
    set_saldo: 1000, used_by: '', created_by: 'Anoy', acc_expiration: '2030-01-01T00:00:00Z', revoked_at: null,
  }, ...(ownerReferral ? [{
    id_reff: 2, code: fixtureHash(ownerReferral), referral: ownerReferral, level: 1,
    owner_channel: 'kenny', set_saldo: 0, used_by: '', created_by: 'Anoy',
    acc_expiration: null, invite_expires_at: new Date(Date.now() + 7 * 86400_000).toISOString(), revoked_at: null,
  }] : [])],
  feature: [{ id: 1, esp: 'on', item: 'on', silentaim: 'on', aim: 'on', bullettrack: 'on', memory: 'on', floating: 'on', setting: 'on' }],
  modname: [{ id: 1, modname: 'VIP MOD' }],
  onoff: [{ id: 1, status: 'off', myinput: '' }],
  credit: [{ id: 1, name: '' }],
  lib: [{ id: 1, channel: 'anoy', file: 'lib.so', file_type: 'Onlinelib/lib.so', file_size: '555 KB', time: '2022-06-04T23:43:38Z' }],
  _ftext: [{ id: 1, _status: 'Safe', _ftext: 'MOD STATUS :- 100% SAFE' }],
  game_status: [
    { game: 'bgmi', status: 'available', message: '', updated_at: '2026-01-01T00:00:00Z' },
    { game: 'bgmi_air', status: 'available', message: '', updated_at: '2026-01-01T00:00:00Z' },
  ],
  client_release: [{ id: 1, channel: 'anoy', version: '1.0', file_name: 'no-release.zip', file_size: 0, sha256: '', content_base64: null, external_url: null, notes: 'Placeholder', game_version: null, is_current: true, uploaded_by: 'admin', created_at: new Date().toISOString() }],
  app_release: [],
  library_storage_cleanup: [],
  auth_attempt: [],
  api_keys: [],
  key_orders: [],
  });
};

const serials = {
  users: 'id_users', keys_code: 'id_keys', history: 'id_history',
  referral_code: 'id_reff', feature: 'id', modname: 'id', onoff: 'id',
  credit: 'id', lib: 'id', _ftext: 'id', client_release: 'id', auth_attempt: 'id',
  api_keys: 'id', key_orders: 'request_id', app_release: 'id',
};

function compare(a, b) {
  if (typeof a === 'number' || (typeof b === 'number' && !Number.isNaN(Number(a)))) return Number(a) - Number(b);
  return new Date(a).getTime() - new Date(b).getTime();
}

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value));
}

class Query {
  constructor(store, table) {
    this.store = store;
    this.table = table;
    this.filters = [];
    this.orders = [];
    this._limit = null;
    this._range = null;
    this._single = null;
    this._operation = 'select';
    this._payload = null;
    this._columns = '*';
    this._count = false;
    this._head = false;
  }

  select(columns = '*', opts = {}) {
    if (this._operation !== 'select') return this;
    this._columns = columns;
    this._count = !!opts.count;
    this._head = !!opts.head;
    return this;
  }
  eq(col, value) { this.filters.push((r) => String(r[col]) === String(value)); return this; }
  neq(col, value) { this.filters.push((r) => String(r[col]) !== String(value)); return this; }
  // SQL semantics: comparisons against NULL never match.
  lt(col, value) { this.filters.push((r) => r[col] != null && compare(r[col], value) < 0); return this; }
  gt(col, value) { this.filters.push((r) => r[col] != null && compare(r[col], value) > 0); return this; }
  ilike(col, pattern) {
    const needle = String(pattern).replace(/%/g, '').toLowerCase();
    this.filters.push((r) => String(r[col] ?? '').toLowerCase().includes(needle));
    return this;
  }
  in(col, values) {
    const set = new Set((Array.isArray(values) ? values : []).map(String));
    this.filters.push((r) => set.has(String(r[col])));
    return this;
  }
  is(col, value) {
    this.filters.push((r) => (value === null ? r[col] == null : r[col] === value));
    return this;
  }
  or(expression) {
    // Only the simple `col.ilike.*term*,col.ilike.*term*` shape is used.
    const parts = String(expression).split(',').filter(Boolean);
    const fns = parts.map((part) => {
      const m = part.match(/^(\w+)\.ilike\.\*(.*)\*$/);
      if (!m) return () => true;
      const [, col, needle] = m;
      return (r) => String(r[col] ?? '').toLowerCase().includes(needle.toLowerCase());
    });
    this.filters.push((r) => fns.some((fn) => fn(r)));
    return this;
  }
  order(col, opts = {}) { this.orders.push([col, opts.ascending !== false]); return this; }
  limit(n) { this._limit = n; return this; }
  range(start, end) { this._range = [start, end]; return this; }
  maybeSingle() { this._single = 'maybe'; return this; }
  single() { this._single = 'single'; return this; }
  insert(rows) { this._operation = 'insert'; this._payload = rows; return this; }
  update(patch) { this._operation = 'update'; this._payload = patch; return this; }
  delete() { this._operation = 'delete'; return this; }

  matching() {
    return (this.store[this.table] || []).filter((row) => this.filters.every((fn) => fn(row)));
  }

  execute() {
    const table = this.table;
    this.store[table] ||= [];
    let rows = this.matching();
    let result = { data: null, error: null, count: null };

    if (this._operation === 'insert') {
      const list = Array.isArray(this._payload) ? this._payload : [this._payload];
      const inserted = [];
      for (const row of list) {
        const pk = serials[table];
        const next = Math.max(0, ...this.store[table].map((r) => Number(r[pk]) || 0)) + 1;
        const record = { ...clone(row), [pk]: row[pk] ?? next };
        if (table === 'users' && this.store.users.some((u) => u.username.toLowerCase() === String(record.username).toLowerCase())) {
          return { data: null, error: { message: 'duplicate key value violates unique constraint "users_username_key"', code: '23505' }, count: null };
        }
        if (table === 'users' && ((Number(record.level) === 1 && (record.expiration_date != null || !['anoy', 'kenny'].includes(record.owner_channel) || this.store.users.some(u => u.owner_channel === record.owner_channel))) || (Number(record.level) !== 1 && record.owner_channel))) {
          return { data: null, error: { message: 'Owner channel is invalid or already occupied', code: '23505' }, count: null };
        }
        if (table === 'referral_code' && this.store.referral_code.some(r => r.code === record.code || r.referral.toLowerCase() === String(record.referral).toLowerCase())) {
          return { data: null, error: { message: 'Referral already exists', code: '23505' }, count: null };
        }
        if (table === 'keys_code' && this.store.keys_code.some((k) => k.user_key === record.user_key)) {
          return { data: null, error: { message: 'duplicate key value violates unique constraint "keys_code_user_key_key"', code: '23505' }, count: null };
        }
        this.store[table].push(record);
        inserted.push(clone(record));
      }
      result.data = this._single ? inserted[0] : inserted;
      if (this._single === 'single' && !inserted.length) result.error = { message: 'no rows inserted' };
      return result;
    }

    if (this._operation === 'update') {
      if (table === 'client_release' && rows.some(row => {
        const next = { ...row, ...this._payload };
        return next.storage_path !== row.storage_path || (!row.is_current && next.is_current);
      })) return { data: null, error: { message: 'Retired library storage is immutable' }, count: null };
      if (table === 'users' && rows.some(row => {
        const next = { ...row, ...this._payload };
        return Number(row.level) === 1
          ? next.expiration_date != null || ['level', 'username', 'owner_channel', 'status'].some(key => next[key] !== row[key])
          : Number(next.level) === 1;
      })) return { data: null, error: { message: 'Owner identities cannot be changed' }, count: null };
      if (table === 'referral_code' && rows.some(row => {
        const next = { ...row, ...this._payload };
        return ['code', 'referral', 'level', 'owner_channel', 'created_by', 'set_saldo', 'acc_expiration', 'invite_expires_at'].some(key => next[key] !== row[key])
          || (row.used_by && next.used_by !== row.used_by)
          || (row.revoked_at && (next.revoked_at !== row.revoked_at || next.used_by !== row.used_by));
      })) return { data: null, error: { message: 'Referrals cannot be reused or restored' }, count: null };
      const updated = [];
      for (const row of rows) {
        Object.assign(row, clone(this._payload));
        row.updated_at = new Date().toISOString();
        updated.push(clone(row));
      }
      result.data = this._single ? (updated[0] || null) : updated;
      if (this._single === 'single' && !updated.length) result.error = { message: 'no rows updated' };
      return result;
    }

    if (this._operation === 'delete') {
      if (table === 'users' && rows.some(row => Number(row.level) === 1)) return { data: null, error: { message: 'Owner accounts cannot be deleted' }, count: null };
      if (table === 'referral_code' && rows.some(row => row.used_by || row.revoked_at)) return { data: null, error: { message: 'Used or deleted referrals cannot be reissued' }, count: null };
      const doomed = new Set(rows);
      this.store[table] = this.store[table].filter((r) => !doomed.has(r));
      result.data = rows.map(clone);
      return result;
    }

    // SELECT
    const total = rows.length;
    for (const [col, asc] of this.orders) {
      rows.sort((a, b) => {
        const av = a[col], bv = b[col];
        if (av === bv) return 0;
        if (av == null) return asc ? 1 : -1;
        if (bv == null) return asc ? -1 : 1;
        if (typeof av === 'number' && typeof bv === 'number') return asc ? av - bv : bv - av;
        return asc ? String(av).localeCompare(String(bv)) : String(bv).localeCompare(String(av));
      });
    }
    if (this._range) rows = rows.slice(this._range[0], this._range[1] + 1);
    else if (this._limit != null) rows = rows.slice(0, this._limit);

    if (this._columns && this._columns !== '*') {
      const cols = String(this._columns).split(',').map((c) => c.trim());
      rows = rows.map((r) => Object.fromEntries(cols.map((c) => [c, r[c]])));
    }
    if (this._count) result.count = total;
    if (this._head) result.data = null;
    else if (this._single) {
      result.data = rows[0] ? clone(rows[0]) : null;
      if (this._single === 'single' && !rows[0]) result.error = { message: 'no rows found' };
    } else result.data = clone(rows);
    return result;
  }

  then(resolve, reject) {
    return Promise.resolve().then(() => this.execute()).then(resolve, reject);
  }
}

export function createMemoryDb() {
  const store = seed();
  return {
    from(table) { return new Query(store, table); },
    storage: createMemoryStorage(),
    _store: store,
  };
}

export function resetMemoryDb() {
  // Kept for tests that want a clean slate.
  return seed();
}
