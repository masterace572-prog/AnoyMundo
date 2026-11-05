import crypto from 'node:crypto';
import { config } from './config.mjs';
import {
  db, unwrap, getUserByUsername, listUsers, getKeyById, getKeyByUserKey,
  getRecentHistory, getReferralCodes, getSettings, deviceList,
} from './db.mjs';
import {
  currentUser, authenticate, hashPassword, codeHash, verifyPassword,
  setSession, clearSession, getClientIp, displayName, passwordIssue,
} from './auth.mjs';
import {
  renderPage, sendHtml, sendJson, sendText, buildSelect, sqlDateTime,
  humanDate, longDate, durationLabel, escapeHtml,
} from './render.mjs';
import { requestUrl, pathOf, parseBody, redirect, back } from './http.mjs';
import {
  handleConnect, handleConnectInfo, handleHealth, handleLibVersion,
  handleLibDownload, handleLibInfo,
} from './licensing.mjs';
import {
  handleApiV1, createApiKeyForUser, listApiKeysForUser, mutateApiKey,
} from './api-keys.mjs';
import { createKeysForUser, deleteKeysWithRefund, refundMessage } from './key-service.mjs';

// ---------------------------------------------------------------------------
// small utilities
// ---------------------------------------------------------------------------
const str = (v) => String(v ?? '').trim();
const num = (v, fallback = 0) => (Number.isFinite(Number(v)) ? Number(v) : fallback);
const alnum = (v, min, max) => new RegExp(`^[A-Za-z0-9]{${min},${max}}$`).test(str(v));
const randomAlnum = (n) => crypto.randomBytes(Math.ceil(n * 2)).toString('base64url').replace(/[^A-Za-z0-9]/g, '').slice(0, n).padEnd(n, 'X');
const nowIso = () => new Date().toISOString();

function userCtx(user) {
  return { user, title: '' };
}

async function guard(req, res, { admin = false, owner = false } = {}) {
  const user = await currentUser(req);
  if (!user) {
    redirect(res, '/login', { msgDanger: 'Please login first' });
    return null;
  }
  if (admin && !user.isAdmin) {
    redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
    return null;
  }
  if (owner && !user.isOwner) {
    redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
    return null;
  }
  return user;
}

function page(req, res, name, data = {}) {
  return sendHtml(res, renderPage(req, res, name, data));
}

/** Canonical public origin used by the in-panel API docs examples. */
function publicBase(req) {
  if (config.baseUrl) return String(config.baseUrl).replace(/\/+$/, '');
  const h = req.headers || {};
  const host = String(h['x-forwarded-host'] || h.host || 'localhost:3000').split(',')[0].trim();
  const local = /^(localhost|127\.0\.0\.1)(:|$)/.test(host);
  const proto = String(h['x-forwarded-proto'] || (local ? 'http' : 'https')).split(',')[0].trim();
  return `${proto}://${host}`;
}

function fail(req, res, url, message, errors = {}, old = {}) {
  return redirect(res, url, { msgDanger: message, errors, old });
}

async function logAttempt(username, ip, ok) {
  try {
    await db().from('auth_attempt').insert({
      username: String(username || '').slice(0, 66), ip: String(ip).slice(0, 100),
      ok: !!ok, created_at: nowIso(),
    });
  } catch { /* audit table is best-effort */ }
}

/** Simple brute-force guard. Fails open if the audit table is unavailable. */
async function tooManyAttempts(username, ip) {
  const since = new Date(Date.now() - 10 * 60_000).toISOString();
  try {
    const [byUser, byIp] = await Promise.all([
      db().from('auth_attempt').select('id', { count: 'exact', head: true })
        .eq('ok', false).eq('username', String(username).slice(0, 66)).gt('created_at', since),
      db().from('auth_attempt').select('id', { count: 'exact', head: true })
        .eq('ok', false).eq('ip', String(ip).slice(0, 100)).gt('created_at', since),
    ]);
    return (byUser.count || 0) >= 8 || (byIp.count || 0) >= 15;
  } catch { return false; }
}

function parseDateInput(value) {
  if (!value) return null;
  const s = str(value).replace(' ', 'T');
  const d = new Date(s.endsWith('Z') || /[+-]\d\d:\d\d$/.test(s) ? s : `${s}Z`);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

function buildDevices(text, max) {
  const list = str(text).split(/[\n,]+/).map((s) => s.replace(/[^A-Za-z0-9]/g, '')).filter(Boolean);
  return Array.from(new Set(list)).slice(0, Math.max(1, num(max, 1))).join(',');
}

// ---------------------------------------------------------------------------
// DataTables helpers
// ---------------------------------------------------------------------------
function dataTableParams(q) {
  return {
    draw: num(q.get('draw'), 1),
    start: Math.max(0, num(q.get('start'), 0)),
    length: Math.min(500, Math.max(1, num(q.get('length'), 10))),
    search: str(q.get('search[value]') || '').replace(/[(),*\\]/g, '').slice(0, 80),
    orderColumn: num(q.get('order[0][column]'), 0),
    ascending: (q.get('order[0][dir]') || 'desc') !== 'desc',
  };
}

async function keysDataTable(res, user, q) {
  const { draw, start, length, search, orderColumn, ascending } = dataTableParams(q);
  const columns = ['id_keys', 'game', 'user_key', 'devices', 'duration', 'expired_date', 'id_keys'];
  let query = db().from('keys_code').select('*', { count: 'exact' });
  if (!user.isOwner) query = query.eq('registrator', user.username);
  if (search) {
    query = query.or(`game.ilike.*${search}*,user_key.ilike.*${search}*,registrator.ilike.*${search}*,devices.ilike.*${search}*`);
  }
  query = query.order(columns[orderColumn] || 'id_keys', { ascending }).range(start, start + length - 1);
  const { data, count, error } = await query;
  if (error) return sendJson(res, { draw, error: error.message }, 500);
  const rows = (data || []).map((r) => ({
    id: r.id_keys,
    game: r.game,
    user_key: r.user_key,
    duration: durationLabel(r.duration),
    expired: humanDate(r.expired_date),
    max_devices: r.max_devices,
    devices: deviceList(r.devices).length,
    status: Number(r.status) === 1 ? 'Active' : 'Inactive',
    registrator: r.registrator,
  }));
  return sendJson(res, { draw, recordsTotal: count ?? rows.length, recordsFiltered: count ?? rows.length, data: rows });
}

async function usersDataTable(res, user, q) {
  const { draw, start, length, search, orderColumn, ascending } = dataTableParams(q);
  const columns = ['id_users', 'username', 'fullname', 'level', 'saldo', 'status', 'uplink'];
  let query = db().from('users').select('*', { count: 'exact' });
  if (!user.isOwner) query = query.eq('uplink', user.username);
  if (search) query = query.or(`username.ilike.*${search}*,fullname.ilike.*${search}*,uplink.ilike.*${search}*`);
  query = query.order(columns[orderColumn] || 'id_users', { ascending }).range(start, start + length - 1);
  const { data, count, error } = await query;
  if (error) return sendJson(res, { draw, error: error.message }, 500);
  const rows = (data || []).map((r) => ({
    id: r.id_users, username: r.username,
    fullname: r.fullname ? str(r.fullname).split(/\s+/)[0] : '',
    saldo: r.saldo,
    level: ({ 1: 'Owner', 2: 'Admin', 3: 'Reseller' })[Number(r.level)] || 'Unknown',
    status: r.status, uplink: r.uplink,
  }));
  return sendJson(res, { draw, recordsTotal: count ?? rows.length, recordsFiltered: count ?? rows.length, data: rows });
}

// ---------------------------------------------------------------------------
// Page data builders
// ---------------------------------------------------------------------------
async function dashboardData(user) {
  const [{ data: keys }, history] = await Promise.all([
    db().from('keys_code').select('id_keys,devices'),
    getRecentHistory(10),
  ]);
  const list = keys || [];
  const stats = {
    keys: list.length,
    active: list.filter((k) => k.devices).length,
    inactive: list.filter((k) => !k.devices).length,
    users: unwrap(await db().from('users').select('id_users')).length,
    expiryFormatted: longDate(user.expiration_date),
  };
  const rows = (history || []).map((h) => {
    const parts = String(h.info || '').split('|');
    const diff = h.created_at ? Date.now() - new Date(h.created_at).getTime() : 0;
    return {
      ...h,
      parts,
      durationLabel: durationLabel(parts[2]),
      createdHuman: `${Math.max(1, Math.round(Math.abs(diff) / 60000))} minutes ago`,
    };
  });
  return { stats, history: rows };
}

function keyFormData(key) {
  const devices = deviceList(key.devices);
  return {
    key: {
      ...key,
      deviceTotal: devices.length,
      deviceRows: devices.length > Number(key.max_devices || 1) ? 3 : Math.max(1, devices.length),
      expiredHint: key.expired_date ? '' : '(Not started yet)',
    },
    form: {
      game: key.game, user_key: key.user_key, duration: key.duration,
      max_devices: key.max_devices, registrator: key.registrator,
      expired_date: key.expired_date ? sqlDateTime(key.expired_date) : '',
      devices: devices.join('\n'),
    },
    selects: {
      status: buildSelect('status', 'status', { 0: 'Banned/Block', 1: 'Active' }, key.status),
    },
  };
}

function bumpVersion(value) {
  const m = String(value || '').match(/^(.*?)(\d+)$/);
  return m ? `${m[1]}${Number(m[2]) + 1}` : '1.1';
}

function serverData(settings) {
  const release = settings.release || null;
  return {
    onoff: settings.onoff,
    ftext: settings.ftext,
    feature: settings.feature,
    server: settings.modname,
    credit: settings.credit,
    lib: settings.lib ? { ...settings.lib, currentTime: sqlDateTime() } : {},
    release,
    currentVersion: release?.version || '',
    suggestedVersion: bumpVersion(release?.version || '1.0'),
  };
}

// ---------------------------------------------------------------------------
// Action handlers
// ---------------------------------------------------------------------------
async function doLogin(req, res, body) {
  const username = str(body.username);
  const password = str(body.password);
  const remember = !!body.stay_log;
  const ip = getClientIp(req);
  const errors = {};
  if (await tooManyAttempts(username, ip)) {
    return fail(req, res, '/login', 'Too many sign-in attempts. Wait 10 minutes and try again.', { password: 'Temporarily locked.' }, { username });
  }
  if (!alnum(username, 4, 25)) errors.username = 'Enter a valid username (4-25 alphanumeric characters).';
  if (password.length < 6 || password.length > 45) errors.password = 'Password must be 6-45 characters.';
  if (Object.keys(errors).length) {
    await logAttempt(username, ip, false);
    return fail(req, res, '/login', 'Please check the form.', errors, { username });
  }
  const auth = await authenticate(username, password);
  if (!auth.ok) {
    await logAttempt(username, ip, false);
    return fail(req, res, '/login', auth.reason, { password: auth.reason }, { username });
  }
  await logAttempt(username, ip, true);
  setSession(res, auth.user, remember);
  const expires = auth.user.expiration_date ? sqlDateTime(auth.user.expiration_date) : 'never';
  return redirect(res, '/dashboard', { msgSuccess: `Account Expires on : ${expires}` });
}

async function doRegister(req, res, body) {
  const email = str(body.email);
  const username = str(body.username);
  const fullname = str(body.fullname);
  const password = str(body.password);
  const password2 = str(body.password2);
  const referral = str(body.referral);
  const old = { email, username, fullname, referral };
  const errors = {};
  if (email.length < 5 || email.length > 40 || !email.includes('@')) errors.email = 'Enter a valid email address.';
  if (!alnum(username, 4, 25)) errors.username = 'Username must be 4-25 alphanumeric characters.';
  else {
    const exists = await getUserByUsername(username);
    if (exists) errors.username = 'The username has been taken.';
  }
  if (!alnum(fullname, 4, 25)) errors.fullname = 'Fullname must be 4-25 alphanumeric characters.';
  else {
    const { data } = await db().from('users').select('id_users').eq('fullname', fullname).maybeSingle();
    if (data) errors.fullname = 'The fullname has been taken.';
  }
  if (password.length < 6 || password.length > 45) errors.password = 'Password must be 6-45 characters.';
  else {
    const issue = passwordIssue(password, username);
    if (issue) errors.password = issue;
  }
  if (password !== password2) errors.password2 = 'Passwords do not match.';
  if (!alnum(referral, 4, 25)) errors.referral = 'Enter a valid referral code.';

  let code = null;
  if (!errors.referral) {
    const { data, error } = await db().from('referral_code').select('*').eq('code', codeHash(referral)).maybeSingle();
    if (error || !data) errors.referral = 'Wrong referral, please try again.';
    else if (data.used_by) errors.referral = `Wrong referral, code has been used · ${data.used_by}.`;
    else if (data.acc_expiration && new Date(data.acc_expiration).getTime() < Date.now()) errors.referral = 'This referral code has expired.';
    else if (![2, 3].includes(Number(data.level)) || Number(data.set_saldo) < 0 || Number(data.set_saldo) > 99999) {
      errors.referral = 'This referral code is not valid. Ask your admin for a new one.';
    } else code = data;
  }
  if (Object.keys(errors).length) return fail(req, res, '/register', 'Please check the form.', errors, old);

  // Re-check the code immediately before creating the account, then claim it
  // conditionally so two simultaneous registrations cannot share one code.
  const { data: freshCode } = await db().from('referral_code').select('used_by,acc_expiration')
    .eq('id_reff', code.id_reff).maybeSingle();
  if (!freshCode || freshCode.used_by || (freshCode.acc_expiration && new Date(freshCode.acc_expiration).getTime() < Date.now())) {
    return fail(req, res, '/register', 'That referral code was just used. Ask for a new one.', { referral: 'Already used.' }, old);
  }
  const { error: claimError, data: claimed } = await db().from('referral_code')
    .update({ used_by: username, updated_at: nowIso() }).eq('id_reff', code.id_reff).eq('used_by', '').select('id_reff');
  if (claimError || !claimed?.length) {
    return fail(req, res, '/register', 'That referral code was just used. Ask for a new one.', { referral: 'Already used.' }, old);
  }

  const { error: insertError } = await db().from('users').insert({
    email, username, fullname,
    // A referral can never mint another Owner. Owner is unique and fixed.
    level: Math.max(2, Math.min(3, num(code.level, 3))),
    password: hashPassword(password),
    saldo: Math.max(0, Math.min(99999, num(code.set_saldo, 0))),
    uplink: code.created_by,
    user_ip: getClientIp(req),
    expiration_date: code.acc_expiration || null,
    status: 1,
    created_at: nowIso(),
    updated_at: nowIso(),
  });
  if (insertError) {
    // Roll back the claim so the code is not burned by a failed signup.
    await db().from('referral_code').update({ used_by: '', updated_at: nowIso() }).eq('id_reff', code.id_reff).eq('used_by', username);
    return fail(req, res, '/register', 'Could not create the account. Please try again.', { username: insertError.message }, old);
  }
  return redirect(res, '/login', { msgSuccess: 'Account created. Please sign in.' });
}

async function doGenerate(req, res, body, user) {
  const custom = str(body.custominput) === 'custom';
  // The form now posts the real number of keys. Legacy 1-5 selector values are
  // still accepted so old bookmarks cannot silently order 100 keys.
  const legacy = { 1: 5, 2: 10, 3: 50, 4: 100, 5: 1 };
  const raw = num(body.count ?? body.loopcount, 1);
  const count = body.count != null ? raw : (legacy[raw] ?? raw);
  const old = { game: str(body.game) || 'PUBG', max_devices: body.max_devices, duration: body.duration };
  const result = await createKeysForUser(user, {
    game: body.game, duration: body.duration, max_devices: body.max_devices,
    count, custom_key: custom ? str(body.cuslicense) : '',
    request_id: body.request_id,
  });
  if (!result.ok) {
    const errors = result.errors || { duration: result.message };
    if (result.code === 'insufficient_balance') {
      return fail(req, res, '/keys/generate',
        `Not enough balance. This order needs ₹${Number(result.required || result.price).toLocaleString('en-IN')}; you have ₹${Number(result.available ?? 0).toLocaleString('en-IN')}.`,
        errors, old);
    }
    if (result.code === 'account_expired' || result.code === 'account_inactive') {
      return fail(req, res, '/keys/generate', result.message, { duration: result.message }, old);
    }
    return fail(req, res, '/keys/generate', result.message, errors, old);
  }
  return redirect(res, '/keys/generate', {
    msgSuccess: `Generated ${result.licenses.length} key${result.licenses.length === 1 ? '' : 's'}. Charged ₹${Number(result.price).toLocaleString('en-IN')}.`,
    user_key: result.licenses.join('\n'), game: result.game,
    duration: String(result.duration), max_devices: String(result.max_devices),
  });
}

async function doKeyEdit(req, res, body, user) {
  const id = num(body.id_keys, 0);
  const key = id ? await getKeyById(id) : null;
  if (!key) return fail(req, res, '/keys', 'The user key no longer exists.');
  if (!user.isOwner && key.registrator !== user.username) return fail(req, res, '/keys', 'Restricted to this user key.');
  const errors = {};
  let patch = {};
  if (user.isOwner) {
    const game = str(body.game);
    const userKey = str(body.user_key);
    const duration = num(body.duration, 0);
    const maxDevices = num(body.max_devices, 0);
    const status = num(body.status, 1);
    const registrator = str(body.registrator) || key.registrator;
    if (!config.games[game]) errors.game = 'Invalid game.';
    if (!/^[A-Za-z0-9_-]{1,32}$/.test(userKey)) errors.user_key = 'Invalid user key.';
    else if (userKey !== key.user_key && (await getKeyByUserKey(userKey))) errors.user_key = 'The user key has been taken.';
    if (!Number.isInteger(duration) || duration < 1 || duration > 100000) errors.duration = 'Invalid duration.';
    if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 100) errors.max_devices = 'Devices must be 1-100.';
    if (![0, 1].includes(status)) errors.status = 'Invalid status.';
    const expiredDate = str(body.expired_date) ? parseDateInput(body.expired_date) : null;
    if (str(body.expired_date) && !expiredDate) errors.expired_date = 'Invalid expired date.';
    if (Object.keys(errors).length) return fail(req, res, back(req, '/keys'), 'Please check the error', errors);
    patch = {
      game, user_key: userKey, duration, max_devices: maxDevices, status, registrator,
      expired_date: expiredDate, devices: buildDevices(body.devices, maxDevices) || null,
      updated_at: nowIso(),
    };
  } else {
    const status = num(body.status, 1);
    if (![0, 1].includes(status)) errors.status = 'Invalid status.';
    if (Object.keys(errors).length) return fail(req, res, back(req, '/keys'), 'Please check the error', errors);
    patch = { status, updated_at: nowIso() };
  }
  const { error } = await db().from('keys_code').update(patch).eq('id_keys', id);
  if (error) return fail(req, res, back(req, '/keys'), error.message);
  return redirect(res, back(req, '/keys'), { msgSuccess: 'User key successfuly updated!' });
}

async function doSettings(req, res, body, user, targetUrl) {
  if (body.password_form) {
    const current = str(body.current);
    const password = str(body.password);
    const password2 = str(body.password2);
    const errors = {};
    if (!verifyPassword(current, user.password)) errors.current = 'Wrong current password.';
    if (current === password) errors.password = 'Nothing to change.';
    if (password.length < 6 || password.length > 45) errors.password = 'Password must be 6-45 characters.';
    else {
      const issue = passwordIssue(password, user.username);
      if (issue) errors.password = issue;
    }
    if (password !== password2) errors.password2 = 'Passwords do not match.';
    if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Please check the form.', errors);
    const { error } = await db().from('users')
      .update({ password: hashPassword(password), updated_at: nowIso() }).eq('id_users', user.id_users);
    if (error) return fail(req, res, targetUrl, 'Could not save the new password. Please try again.');
    return redirect(res, targetUrl, { msgSuccess: 'Password updated.' });
  }
  if (body.fullname_form) {
    const fullname = str(body.fullname);
    const errors = {};
    if (fullname === user.fullname) errors.fullname = 'Nothing to change.';
    if (!/^[A-Za-z ]{4,155}$/.test(fullname)) errors.fullname = 'Only alphabetical characters and spaces (4-155).';
    if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Please check the form.', errors);
    const { error } = await db().from('users').update({ fullname, updated_at: nowIso() }).eq('id_users', user.id_users);
    if (error) return fail(req, res, targetUrl, 'Could not save your details. Please try again.');
    return redirect(res, targetUrl, { msgSuccess: 'Profile updated.' });
  }
  return redirect(res, targetUrl, { msgWarning: 'Unknown action.' });
}

async function doServer(req, res, body, user) {
  const targetUrl = '/Server';
  const write = async (table, patch, label) => {
    const { error } = await db().from(table).update(patch).eq('id', 1);
    if (error) {
      console.error(`[anoymundo] ${table} update failed:`, error.message);
      return redirect(res, targetUrl, { msgDanger: 'Could not save this change. Please try again.' });
    }
    return redirect(res, targetUrl, { msgSuccess: label });
  };

  if (body.modname_form && user.isAdmin) {
    const modname = str(body.modname).slice(0, 100);
    if (!modname) return fail(req, res, targetUrl, 'Mod name cannot be empty.');
    if (!/^[A-Za-z0-9 ._-]{1,100}$/.test(modname)) return fail(req, res, targetUrl, 'Use letters, numbers, spaces, . _ or -.');
    return write('modname', { modname }, 'Mod name updated.');
  }
  if (body.status_form && user.isAdmin) {
    const status = body.radios === 'on' ? 'on' : 'off';
    const myinput = str(body.myInput).slice(0, 500);
    return write('onoff', { status, myinput }, 'Maintenance settings updated.');
  }
  if (body.feature_form && user.isOwner) {
    const flags = ['ESP', 'Item', 'AIM', 'SilentAim', 'BulletTrack', 'Memory', 'Floating', 'Setting'];
    const patch = {};
    for (const flag of flags) patch[flag.toLowerCase()] = body[flag] === 'on' ? 'on' : 'off';
    return write('feature', patch, 'Features updated.');
  }
  // The hidden marker is always posted. A checkbox that is OFF is simply
  // absent from the body, so testing for `_ftextr` alone used to make it
  // impossible to switch the mod status back. That is the bug fixed here.
  if (body.floating_form && user.isAdmin) {
    const myinput = str(body._ftext).slice(0, 100);
    if (!myinput) return fail(req, res, targetUrl, 'Floating text cannot be empty.');
    const status = body._ftextr === 'Safe' ? 'Safe' : 'Anti-Cheat is High..!!';
    return write('_ftext', { _ftext: myinput, _status: status }, `Mod status set to ${status === 'Safe' ? 'Safe' : 'High risk'}.`);
  }
  if (body.password_form || body.fullname_form) return doSettings(req, res, body, user, targetUrl);
  return redirect(res, targetUrl, { msgWarning: 'Unknown action. Nothing was changed.' });
}

async function doReferral(req, res, body, user) {
  const targetUrl = '/admin/create-referral';
  if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
  const setSaldo = num(body.set_saldo, -1);
  const days = num(body.accExpire, 0);
  const level = num(body.accLevel, 3);
  const errors = {};
  if (!Number.isFinite(setSaldo) || setSaldo < 0 || setSaldo > 99999) errors.set_saldo = 'Invalid saldo.';
  if (!config.accExpire[days]) errors.accExpire = 'Invalid days.';
  if (level === 1 || !config.accLevel[level]) errors.accLevel = 'Choose Admin or Reseller.';
  if (Object.keys(errors).length) return fail(req, res, targetUrl, 'check the form', errors);
  const code = randomAlnum(6);
  const { error } = await db().from('referral_code').insert({
    code: codeHash(code), referral: code.slice(0, 7), level: Math.max(2, Math.min(3, level)),
    set_saldo: Math.max(0, setSaldo), used_by: '', created_by: user.username,
    acc_expiration: new Date(Date.now() + days * 86400_000).toISOString(),
    created_at: nowIso(), updated_at: nowIso(),
  });
  if (error) return fail(req, res, targetUrl, error.message);
  return redirect(res, targetUrl, { msgSuccess: `Referral : ${code}` });
}

async function doUserEdit(req, res, body, user) {
  const id = num(body.user_id, 0);
  const targetUrl = `/admin/user/${id}`;
  const target = id ? await db().from('users').select('*').eq('id_users', id).maybeSingle() : null;
  if (!target?.data) return fail(req, res, '/admin/manage-users', 'User no longer exists.');
  // The Owner row is never editable from the user manager, and no account can
  // be promoted to Owner. There is exactly one Owner, enforced again in SQL.
  if (String(target.data.username) === config.ownerUsername || Number(target.data.level) === 1) {
    return fail(req, res, '/admin/manage-users', 'The Owner account cannot be changed here.');
  }
  const username = str(body.username);
  const fullname = str(body.fullname);
  const level = num(body.level, 0);
  const status = num(body.status, 0);
  const saldo = num(body.saldo, 0);
  const uplink = str(body.uplink);
  const expiration = parseDateInput(body.expiration);
  const errors = {};
  if (!alnum(username, 4, 25)) errors.username = 'Invalid username.';
  else if (username !== target.data.username && (await getUserByUsername(username))) errors.username = 'The username has taken by other.';
  if (fullname && !/^[A-Za-z ]{4,155}$/.test(fullname)) errors.fullname = 'Only alphabetical characters and spaces.';
  if (level === 1 || !config.accLevel[level]) errors.level = 'Choose Admin or Reseller.';
  if (![1, 2, 3].includes(status)) errors.status = 'Invalid status.';
  if (!Number.isSafeInteger(saldo) || saldo < 0 || saldo > 999999999) errors.saldo = 'Invalid balance.';
  const uplinkUser = await getUserByUsername(uplink);
  if (!uplinkUser) errors.uplink = 'Uplink not registered anymore.';
  if (str(body.expiration) && !expiration) errors.expiration = 'Invalid expiration date.';
  if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Something wrong! Please check the form', errors);
  const { error } = await db().from('users').update({
    username, fullname, level, status, saldo: Math.max(0, saldo), uplink,
    expiration_date: expiration, updated_at: nowIso(),
  }).eq('id_users', id);
  if (error) return fail(req, res, targetUrl, error.message);
  return redirect(res, targetUrl, { msgSuccess: `Successfuly update ${target.data.username}.` });
}

function validVersion(value) {
  return /^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$/.test(str(value));
}

function urlFileName(url, fallback) {
  try {
    const last = new URL(url).pathname.split('/').filter(Boolean).pop();
    return String(last || fallback).replace(/["\r\n/\\]/g, '_').slice(0, 255);
  } catch { return fallback; }
}

/**
 * Publishes a loader release. Two actions:
 *   upload -> new file (or external URL) becomes the current release
 *   link   -> push a new version + external URL onto the current release
 *
 * Both are validated BEFORE anything is changed, and if the insert fails the
 * previous current release is restored. It can never leave zero current
 * releases (which was the "external link update doesn't work" failure).
 */
async function doLibUpload(req, res, body, user) {
  if (!user.isOwner) return sendJson(res, { ok: false, error: 'Access Denied!' }, 403);
  const action = str(body.action) === 'link' ? 'link' : 'upload';
  const version = str(body.version).slice(0, 32);
  const externalUrl = str(body.external_url).slice(0, 2000);
  const notes = str(body.notes).slice(0, 500) || null;

  if (!validVersion(version)) {
    return sendJson(res, { ok: false, error: 'Enter a version like 1.3 (letters, numbers, . _ + -).' }, 400);
  }
  if (externalUrl && !/^https?:\/\//i.test(externalUrl)) {
    return sendJson(res, { ok: false, error: 'The external link must start with https:// or http://.' }, 400);
  }

  const { data: current, error: readError } = await db().from('client_release')
    .select('*').eq('is_current', true).maybeSingle();
  if (readError) return sendJson(res, { ok: false, error: 'Could not read the current release.' }, 500);

  if (action === 'link') {
    if (!externalUrl) return sendJson(res, { ok: false, error: 'Enter an external link.' }, 400);
    if (!current) return sendJson(res, { ok: false, error: 'Upload a release first, then update its link.' }, 404);
    const patch = {
      version, external_url: externalUrl, content_base64: null,
      file_name: urlFileName(externalUrl, current.file_name || 'release.zip'),
      file_size: 0, sha256: '', notes, uploaded_by: user.username,
    };
    const { error } = await db().from('client_release').update(patch).eq('id', current.id);
    if (error) return sendJson(res, { ok: false, error: `Could not update the link: ${error.message}` }, 500);
    return sendJson(res, { ok: true, version, external_url: externalUrl, file_name: patch.file_name, mode: 'link' });
  }

  const name = str(body.file_name).replace(/["\r\n/\\]/g, '_').slice(0, 255) ||
    urlFileName(externalUrl, 'release.zip');
  const base64 = str(body.content_base64);
  if (!base64 && !externalUrl) {
    return sendJson(res, { ok: false, error: 'Choose a file or enter an external link.' }, 400);
  }
  const bytes = base64
    ? Buffer.from(base64.replace(/^data:[^;]+;base64,/, ''), 'base64')
    : Buffer.alloc(0);
  if (bytes.length > config.maxUploadMb * 1024 * 1024) {
    return sendJson(res, { ok: false, error: `File is larger than ${config.maxUploadMb} MB.` }, 400);
  }
  if (base64 && bytes.length < 4) {
    return sendJson(res, { ok: false, error: 'The uploaded file looks invalid.' }, 400);
  }
  const sha256 = bytes.length ? crypto.createHash('sha256').update(bytes).digest('hex') : '';

  const { error: demoteError } = await db().from('client_release')
    .update({ is_current: false }).eq('is_current', true);
  if (demoteError) {
    return sendJson(res, { ok: false, error: 'Could not retire the previous release. Nothing was changed.' }, 500);
  }

  const { error: insertError } = await db().from('client_release').insert({
    version, file_name: name, file_size: bytes.length, sha256,
    content_base64: base64 ? bytes.toString('base64') : null,
    external_url: externalUrl || null, notes,
    is_current: true, uploaded_by: user.username, created_at: nowIso(),
  }).select('id').single();
  if (insertError) {
    if (current) await db().from('client_release').update({ is_current: true }).eq('id', current.id);
    console.error('[anoymundo] release insert failed:', insertError.message);
    return sendJson(res, { ok: false, error: 'Could not publish the release. The previous version is still active.' }, 500);
  }

  // Best-effort audit row; never let it fail a successful publish.
  try {
    await db().from('lib').insert({
      file: name, file_type: `Onlinelib/${name}`,
      file_size: `${(bytes.length / 1024).toFixed(1)} KB`, time: nowIso(),
    });
  } catch { /* ignore */ }
  return sendJson(res, { ok: true, version, file_name: name, file_size: bytes.length, sha256, mode: 'upload' });
}

// ---------------------------------------------------------------------------
// One consistent, in-panel error surface. A Vercel timeout, a database hiccup
// or any unexpected exception must never show a raw stack trace, never strand
// the user outside the panel, and never claim an action succeeded.
// ---------------------------------------------------------------------------
/**
 * Legacy GET endpoints that change data. Modern browsers tell us the request
 * context (Sec-Fetch-Site); Origin/Referer covers older ones. CLI/API callers
 * send neither header and keep working. A cross-site page can no longer delete
 * or reset keys just by getting a signed-in user to load an image or a link.
 */
const MUTATING_GET = new Set([
  '/keys/reset', '/keys/resetall', '/keys/deleteexp', '/keys/deleteunused',
]);

function isMutatingGet(lp) {
  return MUTATING_GET.has(lp) || lp.startsWith('/admin/user/delete/');
}

function sameOriginMutation(req) {
  const h = req.headers || {};
  const fetchSite = String(h['sec-fetch-site'] || '').toLowerCase();
  if (fetchSite && fetchSite !== 'same-origin' && fetchSite !== 'none') return false;
  const source = String(h.origin || h.referer || '');
  if (!source) return true; // non-browser clients
  let sourceHost;
  try { sourceHost = new URL(source).host; } catch { return false; }
  const allowed = new Set();
  for (const raw of [h['x-forwarded-host'], h.host, config.publicBaseUrl]) {
    if (!raw) continue;
    try { allowed.add(new URL(/^https?:/.test(String(raw)) ? String(raw) : `https://${raw}`).host); }
    catch { /* ignore malformed host */ }
  }
  return allowed.has(sourceHost);
}

function errorPage(title, message, ref) {
  const safeRef = escapeHtml(ref);
  return `<!doctype html><html lang="en" data-theme="light"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>${escapeHtml(title)} · ${escapeHtml(config.brand)}</title>
<link rel="stylesheet" href="/assets/css/tailwind.css">
<link rel="stylesheet" href="/assets/css/app.css">
<link rel="stylesheet" href="/assets/css/ios.css"></head>
<body class="app-body"><main class="auth-shell"><div class="auth-card" style="text-align:center">
<span class="brand-mark brand-mark-lg" aria-hidden="true">!</span>
<h1 class="auth-title">${escapeHtml(title)}</h1>
<p class="auth-subtitle">${escapeHtml(message)}</p>
<p class="field-hint">Reference ${safeRef}</p>
<div style="margin-top:24px"><a href="/dashboard" class="ui-btn auth-submit">Back to dashboard</a></div>
</div></main></body></html>`;
}

function isJsonRoute(p, method, req) {
  const lp = p.toLowerCase();
  const accept = String(req.headers?.accept || '');
  const contentType = String(req.headers?.['content-type'] || '');
  return lp.startsWith('/api/') || lp === '/connect' || lp === '/health' ||
    (lp === '/lib' && method === 'POST') ||
    lp.startsWith('/keys/reset') || lp === '/keys/api' || lp === '/admin/api/users' ||
    contentType.includes('application/json') || accept.includes('application/json');
}

function handleFailure(req, res, p, method, error) {
  const ref = crypto.randomBytes(3).toString('hex').toUpperCase();
  console.error(`[anoymundo] ${ref} request failed on ${p}:`, error?.stack || error);
  if (res.headersSent) return res.end();
  const generic = 'Something went wrong. Nothing was changed. Please try again.';
  if (isJsonRoute(p, method, req)) {
    return sendJson(res, { ok: false, status: false, error: generic, ref }, 500);
  }
  if (method === 'POST') {
    const target = (p === '/login' || p === '/register') ? p : back(req, '/dashboard');
    return redirect(res, target, { msgDanger: `${generic} (Ref ${ref})` });
  }
  return sendHtml(res, errorPage('Something went wrong', generic, ref), 500);
}


// ---------------------------------------------------------------------------
// Main dispatch
// ---------------------------------------------------------------------------
export async function handle(req, res) {
  try {
    const url = requestUrl(req);
    const q = url.searchParams;
    const p = pathOf(req);
    const lp = p.toLowerCase();
    const method = (req.method || 'GET').toUpperCase();
    const body = method === 'POST' || method === 'PUT' || method === 'PATCH' ? await parseBody(req) : {};

    // State-changing legacy GETs must originate from the panel itself.
    if (method === 'GET' && isMutatingGet(lp) && !sameOriginMutation(req)) {
      return sendJson(res, {
        ok: false, status: false, code: 'cross_site_blocked',
        error: 'This action must be started from the panel. Nothing was changed.',
      }, 403);
    }

    // --------------------------- public endpoints ---------------------------
    if (lp === '/api/health' || lp === '/health') return handleHealth(res, q.get('rpc') === '1');
    if (lp === '/api/lib/version' || lp === '/version.txt' || lp === '/api/version' || lp === '/lib/version.txt') {
      return handleLibVersion(res);
    }
    if (lp === '/api/lib/download' || lp === '/xlox.zip' || lp === '/lib.zip' ||
        lp === '/onlinelib/lib.so' || lp === '/lib.so' || lp === '/download/lib.so') {
      return handleLibDownload(req, res);
    }
    if (lp === '/api/lib/info') return handleLibInfo(res);
    // Personal API-key endpoints authenticate with X-API-Key, not a session.
    if (lp === '/api/v1' || lp.startsWith('/api/v1/')) {
      return handleApiV1(req, res, body, q, p, method);
    }
    if (lp === '/connect' || lp === '/api/connect') {
      if (method === 'POST') return handleConnect(req, res, body);
      // Some loaders (and deployment checks) use a query-string GET instead
      // of a form POST. Same validation, same JSON contract.
      if (q.has('game') && q.has('user_key') && q.has('serial')) {
        return handleConnect(req, res, {
          game: q.get('game'), user_key: q.get('user_key'), serial: q.get('serial'),
        });
      }
      return handleConnectInfo(res);
    }

    // ------------------------------ auth pages ------------------------------
    if (p === '/' || lp === '/login') {
      const existing = await currentUser(req);
      if (existing) return redirect(res, '/dashboard');
      if (method === 'POST') return doLogin(req, res, body);
      return page(req, res, 'login', { title: 'Login', clientIp: getClientIp(req) });
    }
    if (lp === '/register') {
      const existing = await currentUser(req);
      if (existing) return redirect(res, '/dashboard');
      if (method === 'POST') return doRegister(req, res, body);
      return page(req, res, 'register', { title: 'Register', clientIp: getClientIp(req) });
    }
    if (lp === '/logout') {
      clearSession(res);
      return redirect(res, '/login', { msgSuccess: 'Logout successfuly.' });
    }

    // Everything below requires a valid session.
    const user = await guard(req, res);
    if (!user) return;

    // ------------------------------ dashboard ------------------------------
    if (lp === '/dashboard' || lp === '/dbg') {
      const { stats, history } = await dashboardData(user);
      return page(req, res, 'dashboard', { user, title: 'Dashboard', stats, history });
    }

    // --------------------------- docs (two kinds) ---------------------------
    // /docs      = loader integration (mod name, expiry, status, floating text, C++)
    // /api-docs  = personal API-key REST reference (key management only)
    if (lp === '/docs') {
      return page(req, res, 'integration_docs', { user, title: 'Integration docs', apiBase: publicBase(req) });
    }
    if (lp === '/api-docs') {
      return page(req, res, 'api_docs', { user, title: 'API docs', apiBase: publicBase(req) });
    }

    // --------------------- personal API keys (panel UI) ---------------------
    if (lp === '/api-keys') {
      if (method === 'POST') {
        const action = str(body.action);
        if (action === 'create') {
          const name = str(body.name);
          if (!name || name.length > 80) {
            return redirect(res, '/api-keys', { msgDanger: 'Enter a name of 1–80 characters.' });
          }
          try {
            const { token } = await createApiKeyForUser(user, name);
            return redirect(res, '/api-keys', {
              msgSuccess: `API key "${name}" created. Copy it now — it will not be shown again.`,
              apiKeyPlain: token,
            });
          } catch (error) {
            return redirect(res, '/api-keys', { msgDanger: `Failed: ${error.message}` });
          }
        }
        if (action === 'revoke' || action === 'delete') {
          try {
            const done = await mutateApiKey(user, num(body.id, 0), action);
            if (!done) return redirect(res, '/api-keys', { msgDanger: 'That API key was not found.' });
            return redirect(res, '/api-keys', {
              msgSuccess: action === 'revoke' ? 'API key revoked.' : 'API key deleted.',
            });
          } catch (error) {
            return redirect(res, '/api-keys', { msgDanger: `Could not update the API key: ${error.message}` });
          }
        }
        return redirect(res, '/api-keys', { msgWarning: 'Unknown action.' });
      }
      const apiKeys = await listApiKeysForUser(user);
      return page(req, res, 'api_keys', {
        user, title: 'API keys', apiKeys: apiKeys.map((k) => ({
          ...k,
          statusLabel: Number(k.status) === 1 ? 'Active' : 'Revoked',
          createdLabel: humanDate(k.created_at),
          usedLabel: k.last_used_at ? humanDate(k.last_used_at) : 'Never',
        })),
      });
    }

    // --------------------------------- keys ---------------------------------
    if (lp === '/keys') {
      let query = db().from('keys_code').select('user_key');
      if (!user.isOwner) query = query.eq('registrator', user.username);
      const { data } = await query;
      const keyList = (data || []).map((k) => k.user_key);
      return page(req, res, 'keys_list', { user, title: 'Keys', keyList });
    }
    if (lp === '/keys/api') return keysDataTable(res, user, q);
    if (lp === '/keys/generate') {
      if (method === 'POST') return doGenerate(req, res, body, user);
      const message = Number(user.saldo) > 0
        ? { text: `Total Saldo ₹${Number(user.saldo).toLocaleString('en-IN')}`, type: 'success' }
        : { text: 'Your balance is empty. Ask your admin to top up.', type: 'warning' };
      return page(req, res, 'keys_generate', {
        user, title: 'Generate', pageMessage: message,
        form: { max_devices: 1, duration: 24, game: 'PUBG' },
        selects: {
          game: buildSelect('game', 'game', config.games, 'PUBG'),
          duration: buildSelect('duration', 'duration', config.durations, 24),
        },
      });
    }
    if (lp === '/keys/edit' && method === 'POST') return doKeyEdit(req, res, body, user);
    if (lp === '/keys/reset') {
      const userKey = str(q.get('userkey'));
      const key = await getKeyByUserKey(userKey);
      if (!key) return sendJson(res, { registered: false, keys: userKey });
      const total = deviceList(key.devices).length;
      const max = Number(key.max_devices || 0);
      if (q.get('reset') && (user.isOwner || key.registrator === user.username)) {
        await db().from('keys_code').update({ devices: null, updated_at: nowIso() }).eq('user_key', userKey);
        return sendJson(res, { registered: true, keys: userKey, reset: true, devices_total: 0, devices_max: max });
      }
      return sendJson(res, { registered: true, keys: userKey, devices_total: total, devices_max: max });
    }
    if (lp === '/keys/resetall') {
      const userKey = str(q.get('userkey'));
      const key = await getKeyByUserKey(userKey);
      if (!key) return sendJson(res, { registered: false, keys: userKey });
      if (!user.isOwner && key.registrator !== user.username) {
        return sendJson(res, { registered: true, keys: userKey, devices_total: deviceList(key.devices).length, devices_max: key.max_devices });
      }
      const refund = await deleteKeysWithRefund([key.id_keys], user);
      if (!refund.ok) return sendJson(res, { registered: true, keys: userKey, deleted: false, error: refund.message }, 500);
      return sendJson(res, {
        registered: true, keys: userKey, deleted: refund.deleted > 0,
        refunded: refund.refunded, refund_total: refund.refund_total,
        devices_total: 0, devices_max: key.max_devices,
      });
    }
    if (lp === '/keys/deleteexp' || lp === '/keys/deleteunused') {
      let query = db().from('keys_code').select('id_keys,expired_date,registrator').limit(500);
      if (!user.isOwner) query = query.eq('registrator', user.username);
      const { data, error } = await query;
      if (error) return redirect(res, '/keys', { msgDanger: 'Could not read keys. Nothing was deleted.' });
      const ids = (data || []).filter((row) => (lp === '/keys/deleteexp'
        ? !!row.expired_date && new Date(row.expired_date).getTime() < Date.now()
        : !row.expired_date)).map((row) => row.id_keys);
      const result = await deleteKeysWithRefund(ids, user);
      return redirect(res, '/keys', { msgSuccess: refundMessage(result) });
    }
    if (lp === '/keys/download') {
      let query = db().from('keys_code').select('user_key');
      if (!user.isOwner) query = query.eq('registrator', user.username);
      const { data } = await query;
      const text = (data || []).map((r) => r.user_key).join('\n');
      res.setHeader('Content-Disposition', 'attachment; filename="Allkeys.txt"');
      return sendText(res, text);
    }
    const keyEditMatch = p.match(/^\/keys\/(\d+)$/i);
    if (keyEditMatch) {
      const key = await getKeyById(Number(keyEditMatch[1]));
      if (!key) return redirect(res, '/keys', { msgDanger: 'The user key no longer exists.' });
      if (!user.isOwner && key.registrator !== user.username) {
        return redirect(res, '/keys', { msgDanger: 'Restricted to this user key.' });
      }
      return page(req, res, 'keys_edit', { user, title: 'Key', ...keyFormData(key) });
    }

    // ------------------------------- settings ------------------------------
    if (lp === '/settings') {
      if (method === 'POST') return doSettings(req, res, body, user, '/settings');
      return page(req, res, 'settings', { user, title: 'Settings', form: { fullname: user.fullname || '' } });
    }

    // ---------------------------- server settings --------------------------
    if (lp === '/server') {
      if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'Access Denied' });
      if (method === 'POST') return doServer(req, res, body, user);
      const settings = await getSettings();
      return page(req, res, 'server', { user, title: 'Server', ...serverData(settings) });
    }

    // --------------------------------- admin --------------------------------
    if (lp === '/admin/create-referral') {
      if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
      if (method === 'POST') return doReferral(req, res, body, user);
      const codes = await getReferralCodes(10);
      const { count } = await db().from('referral_code').select('*', { count: 'exact', head: true });
      return page(req, res, 'admin_referral', {
        user, title: 'Referral', codes: codes.map((c) => ({ ...c, codeShort: String(c.code).slice(1, 16) })),
        totalCodes: count ?? codes.length,
        selects: {
          accExpire: buildSelect('accExpire', 'accExpire', { '': '— Select Days —', ...config.accExpire }, ''),
          accLevel: buildSelect('accLevel', 'accLevel', { '': '— Select Roles —', ...config.accLevel }, ''),
        },
      });
    }
    if (lp === '/admin/manage-users') {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
      const userList = await listUsers();
      return page(req, res, 'admin_users', { user, title: 'Users', userList });
    }
    if (lp === '/admin/api/users') return usersDataTable(res, user, q);
    const userDeleteMatch = p.match(/^\/admin\/user\/delete\/(\d+)$/i);
    if (userDeleteMatch) {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
      const targetId = Number(userDeleteMatch[1]);
      if (targetId === user.id_users) return redirect(res, '/admin/manage-users', { msgDanger: 'You cannot delete yourself.' });
      const { data: target } = await db().from('users').select('id_users,username,level').eq('id_users', targetId).maybeSingle();
      if (!target) return redirect(res, '/admin/manage-users', { msgDanger: 'User no longer exists.' });
      if (Number(target.level) === 1 || target.username === config.ownerUsername) {
        return redirect(res, '/admin/manage-users', { msgDanger: 'The Owner account cannot be deleted.' });
      }
      const { data: theirKeys } = await db().from('keys_code').select('id_keys').eq('registrator', target.username).limit(500);
      await deleteKeysWithRefund((theirKeys || []).map((k) => k.id_keys), user);
      const { error } = await db().from('users').delete().eq('id_users', targetId);
      if (error) return redirect(res, '/admin/manage-users', { msgDanger: 'Could not delete this user. Please try again.' });
      return redirect(res, '/admin/manage-users', { msgSuccess: 'User and their keys removed.' });
    }
    const userEditMatch = p.match(/^\/admin\/user\/(\d+)$/i);
    if (userEditMatch) {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
      const id = Number(userEditMatch[1]);
      if (method === 'POST') return doUserEdit(req, res, body, user);
      const { data: target } = await db().from('users').select('*').eq('id_users', id).maybeSingle();
      if (!target) return redirect(res, '/admin/manage-users', { msgDanger: 'User no longer exists.' });
      return page(req, res, 'admin_user_edit', {
        user, title: 'Settings',
        target: { ...target, displayName: displayName(target) },
        form: {
          username: target.username, fullname: target.fullname, saldo: target.saldo,
          uplink: target.uplink, expiration: target.expiration_date ? sqlDateTime(target.expiration_date) : '',
        },
        selects: {
          level: buildSelect('level', 'level', { '': '— Select Roles —', ...config.accLevel }, target.level),
          status: buildSelect('status', 'status', { '': '— Select Status —', 2: 'Banned/Block', 1: 'Active', 3: 'Expired' }, target.status),
        },
      });
    }

    // ---------------------------- library / release ------------------------
    if (lp === '/lib') {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'Access Denied!' });
      if (method === 'POST') return doLibUpload(req, res, body, user);
      const settings = await getSettings();
      return page(req, res, 'lib', { user, title: 'Library', maxUploadMb: config.maxUploadMb, ...serverData(settings) });
    }

    // -------------------------------- 404 -----------------------------------
    return sendHtml(res, `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>404 - ${escapeHtml(config.brand)}</title><link rel="stylesheet" href="/assets/css/tailwind.css"><link rel="stylesheet" href="/assets/css/app.css"><link rel="stylesheet" href="/assets/css/ios.css"></head><body class="app-body"><main class="auth-shell"><div class="auth-card" style="text-align:center"><span class="brand-mark brand-mark-lg" aria-hidden="true">404</span><h1 class="auth-title">Page not found</h1><p class="auth-subtitle">${escapeHtml(p)}</p><div style="margin-top:24px"><a href="/dashboard" class="ui-btn auth-submit">Back to dashboard</a></div></div></main></body></html>`, 404);
  } catch (error) {
    return handleFailure(req, res, p, method, error);
  }
}

// Default export doubles as a Vercel Node entrypoint (req, res) => void.
export default handle;
