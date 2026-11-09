import crypto from 'node:crypto';
import { config } from './config.mjs';
import {
  db, getUserByUsername, listUsers, getKeyById, getKeyByUserKey,
  getSettings, deviceList,
} from './db.mjs';
import {
  currentUser, authenticate, hashPassword, codeHash, verifyPassword,
  setSession, clearSession, getClientIp, displayName, passwordIssue,
} from './auth.mjs';
import {
  renderPage, sendHtml, sendJson, sendText, buildSelect, sqlDateTime, parseIstDateTime,
  humanDate, longDate, durationLabel, escapeHtml,
  iconSvg,
} from './render.mjs';
import { requestUrl, pathOf, parseBody, redirect, back } from './http.mjs';
import { handleAppUpdateCheck, doAppReleasePost, appReleasePageData, validHttpsUrl, handleLatestBuild } from './app-release.mjs';
import {
  handleConnect, handleConnectInfo, handleHealth, handleLibVersion,
  handleLibDownload, handleLibInfo, handleLibManifest,
} from './licensing.mjs';
import {
  handleApiV1, createApiKeyForUser, listApiKeysForUser, mutateApiKey,
} from './api-keys.mjs';
import {
  createKeysForUser, deleteKeysWithRefund, refundMessage, keyPrefix, isUnlimited, normalizeKey,
} from './key-service.mjs';

// ---------------------------------------------------------------------------
// small utilities
// ---------------------------------------------------------------------------
const str = (v) => String(v ?? '').trim();
const num = (v, fallback = 0) => (Number.isFinite(Number(v)) ? Number(v) : fallback);
const alnum = (v, min, max) => new RegExp(`^[A-Za-z0-9]{${min},${max}}$`).test(str(v));
const REFERRAL_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const randomCode = (n) => Array.from({ length: n }, () => REFERRAL_ALPHABET[crypto.randomInt(REFERRAL_ALPHABET.length)]).join('');
const inr = (v) => `₹${Number(v || 0).toLocaleString('en-IN')}`;
const nowIso = () => new Date().toISOString();

function userCtx(user) {
  return { user, title: '' };
}

async function guard(req, res, { admin = false, owner = false } = {}) {
  const user = await currentUser(req);
  if (!user) {
    redirect(res, '/login', { msgWarning: 'Please sign in to continue.' });
    return null;
  }
  if (admin && !user.isAdmin) {
    redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
    return null;
  }
  if (owner && !user.isOwner) {
    redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
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

// Date inputs show and accept IST (see render.mjs); stored as a UTC instant.
function parseDateInput(value) {
  if (!value) return null;
  return parseIstDateTime(str(value));
}

function buildDevices(text, max) {
  const list = str(text).split(/[\n,]+/).map((s) => s.replace(/[^A-Za-z0-9]/g, '')).filter(Boolean);
  return Array.from(new Set(list)).slice(0, Math.max(1, num(max, 1))).join(',');
}

// ---------------------------------------------------------------------------
// JSON list endpoint for the keys table (search + pagination)
// ---------------------------------------------------------------------------
function listParams(q) {
  const per = Math.min(100, Math.max(5, Math.trunc(num(q.get('per'), 25)) || 25));
  const pageNo = Math.max(1, Math.trunc(num(q.get('page'), 1)) || 1);
  // PostgREST `or()` syntax characters are stripped so a search can never
  // change the shape of the filter.
  const search = str(q.get('q') || '').replace(/[(),*\\%:."']/g, '').slice(0, 64);
  const status = ['used', 'unused', 'expired', 'blocked'].includes(q.get('status')) ? q.get('status') : '';
  return { per, page: pageNo, search, status };
}

async function keysList(res, user, q) {
  const { per, page: pageNo, search, status } = listParams(q);
  let query = db().from('keys_code').select('*', { count: 'exact' });
  if (!user.isOwner) query = query.eq('registrator', user.username);
  if (search) {
    query = query.or(`user_key.ilike.*${search}*,registrator.ilike.*${search}*,game.ilike.*${search}*`);
  }
  if (status === 'blocked') query = query.neq('status', 1);
  else if (status === 'unused') query = query.is('expired_date', null);
  else if (status === 'used') query = query.gt('expired_date', nowIso());
  else if (status === 'expired') query = query.lt('expired_date', nowIso());
  const start = (pageNo - 1) * per;
  query = query.order('id_keys', { ascending: false }).range(start, start + per - 1);
  const { data, count, error } = await query;
  if (error) {
    console.error('[anoymundo] keys list failed:', error.message);
    return sendJson(res, { ok: false, error: 'Could not load keys. Please try again.' }, 500);
  }
  const now = Date.now();
  const rows = (data || []).map((r) => {
    const expiry = r.expired_date ? new Date(r.expired_date).getTime() : null;
    let state = 'unused';
    if (Number(r.status) !== 1) state = 'blocked';
    else if (expiry && expiry <= now) state = 'expired';
    else if (expiry) state = 'active';
    return {
      id: r.id_keys,
      game: r.game,
      user_key: r.user_key,
      duration: durationLabel(r.duration),
      expires: r.expired_date ? humanDate(r.expired_date) : '',
      max_devices: Number(r.max_devices || 1),
      devices: deviceList(r.devices).length,
      state,
      registrator: r.registrator,
    };
  });
  const total = count ?? rows.length;
  return sendJson(res, { ok: true, rows, total, page: pageNo, per, pages: Math.max(1, Math.ceil(total / per)) });
}

// ---------------------------------------------------------------------------
// Page data builders
// ---------------------------------------------------------------------------
async function countKeys(user, apply = (q) => q) {
  let query = db().from('keys_code').select('id_keys', { count: 'exact', head: true });
  if (!user.isOwner) query = query.eq('registrator', user.username);
  const { count, error } = await apply(query);
  if (error) throw new Error(error.message);
  return count || 0;
}

async function dashboardData(user) {
  const now = nowIso();
  const usersQuery = user.isOwner
    ? db().from('users').select('id_users', { count: 'exact', head: true })
    : db().from('users').select('id_users', { count: 'exact', head: true }).eq('uplink', user.username);
  const [total, unused, active, expired, usersRes] = await Promise.all([
    countKeys(user),
    countKeys(user, (q) => q.is('expired_date', null)),
    countKeys(user, (q) => q.gt('expired_date', now)),
    countKeys(user, (q) => q.lt('expired_date', now)),
    usersQuery,
  ]);
  return {
    stats: {
      keys: total,
      active,
      inactive: unused,
      expired,
      users: usersRes.count || 0,
      expiryFormatted: user.expiration_date ? longDate(user.expiration_date) : '',
    },
  };
}

function keyFormData(key) {
  const devices = deviceList(key.devices);
  return {
    key: {
      ...key,
      deviceTotal: devices.length,
      deviceRows: Math.min(8, Math.max(2, devices.length)),
      durationLabel: durationLabel(key.duration),
      expiresLabel: key.expired_date ? humanDate(key.expired_date) : '',
    },
    form: {
      game: key.game, user_key: key.user_key, duration: key.duration,
      max_devices: key.max_devices, registrator: key.registrator,
      expired_date: key.expired_date ? sqlDateTime(key.expired_date) : '',
      devices: devices.join('\n'),
    },
    selects: {
      status: buildSelect('status', 'status', { 1: 'Active', 0: 'Blocked' }, Number(key.status) === 1 ? 1 : 0),
      game: buildSelect('game', 'game', config.games, key.game),
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
  if (!alnum(username, 3, 25)) errors.username = 'Enter a valid username (letters and numbers only).';
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
  return redirect(res, '/dashboard', { msgSuccess: `Welcome back, ${displayName(auth.user)}.` });
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
  if (email.length < 5 || email.length > 40 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = 'Enter a valid email address.';
  if (!alnum(username, 4, 25)) errors.username = 'Username must be 4-25 alphanumeric characters.';
  else {
    const exists = await getUserByUsername(username);
    if (exists) errors.username = 'That username is taken.';
  }
  if (!/^[A-Za-z][A-Za-z ]{1,39}$/.test(fullname)) errors.fullname = 'Enter your name (letters and spaces, 2-40).';
  if (password.length < 6 || password.length > 45) errors.password = 'Password must be 6-45 characters.';
  else {
    const issue = passwordIssue(password, username);
    if (issue) errors.password = issue;
  }
  if (password !== password2) errors.password2 = 'Passwords do not match.';
  if (!alnum(referral, 4, 25)) errors.referral = 'Enter a valid referral code.';

  let code = null;
  if (!errors.referral) {
    let { data, error } = await db().from('referral_code').select('*').eq('code', codeHash(referral.toUpperCase())).maybeSingle();
    if (!error && !data && referral.toUpperCase() !== referral) {
      ({ data, error } = await db().from('referral_code').select('*').eq('code', codeHash(referral)).maybeSingle());
    }
    if (error || !data) errors.referral = 'That referral code is not valid.';
    else if (data.used_by) errors.referral = 'This referral code has already been used.';
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
    console.error('[anoymundo] register insert failed:', insertError.message);
    return fail(req, res, '/register', 'Could not create the account. Please try again.', {}, old);
  }
  return redirect(res, '/login', { msgSuccess: 'Account created. Please sign in.' });
}

async function doGenerate(req, res, body, user) {
  // The custom toggle posts custom=1 (custominput=custom from older pages).
  const custom = str(body.custom) === '1' || str(body.custominput) === 'custom';
  const customKey = custom ? str(body.custom_key ?? body.cuslicense) : '';
  // A custom key is always exactly one key. The quantity control is hidden in
  // that mode, so never derive a count from a missing/disabled field.
  const count = custom ? 1 : Math.trunc(num(body.count, 1));
  const old = {
    game: str(body.game) || 'PUBG', max_devices: body.max_devices, duration: body.duration,
    count: String(count), custom: custom ? '1' : '', custom_key: customKey,
  };
  if (custom && !customKey) {
    return fail(req, res, '/keys/generate', 'Enter the custom part of the key.', { custom_key: 'Required.' }, old);
  }
  const result = await createKeysForUser(user, {
    game: body.game, duration: body.duration, max_devices: body.max_devices,
    count, custom_key: customKey, request_id: body.request_id,
  });
  if (!result.ok) {
    const errors = result.errors || {};
    if (result.code === 'insufficient_balance') {
      return fail(req, res, '/keys/generate',
        `Not enough balance. This order needs ${inr(result.required || result.price)}; you have ${inr(result.available ?? 0)}.`,
        errors, old);
    }
    const first = Object.values(errors)[0];
    const message = result.code === 'invalid_request' && first ? first : result.message;
    return fail(req, res, '/keys/generate', message, errors, old);
  }
  const n = result.licenses.length;
  const charged = result.unlimited ? 'No charge (unlimited balance).' : `Charged ${inr(result.price)}.`;
  return redirect(res, '/keys/generate', {
    msgSuccess: `Created ${n} key${n === 1 ? '' : 's'}. ${charged}`,
    user_key: result.licenses.join('\n'), game: result.game,
    duration: String(result.duration), max_devices: String(result.max_devices),
    // The clock starts at the first sign-in (unchanged); this is a preview in IST.
    created_label: humanDate(new Date()),
    expires_if_now: humanDate(new Date(Date.now() + Number(result.duration || 0) * 3600_000)),
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
    const userKey = normalizeKey(body.user_key);
    const duration = num(body.duration, 0);
    const maxDevices = num(body.max_devices, 0);
    const status = num(body.status, 1);
    const registrator = str(body.registrator) || key.registrator;
    if (!config.games[game]) errors.game = 'Invalid game.';
    if (!/^[A-Z0-9_-]{4,64}$/.test(userKey)) errors.user_key = 'Use 4-64 characters: A-Z, 0-9, - or _.';
    else if (userKey !== key.user_key && (await getKeyByUserKey(userKey))) errors.user_key = 'That key already exists.';
    if (!Number.isInteger(duration) || duration < 1 || duration > 100000) errors.duration = 'Invalid duration.';
    if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 100) errors.max_devices = 'Devices must be 1-100.';
    if (![0, 1].includes(status)) errors.status = 'Invalid status.';
    const expiredDate = str(body.expired_date) ? parseDateInput(body.expired_date) : null;
    if (str(body.expired_date) && !expiredDate) errors.expired_date = 'Invalid expired date.';
    if (Object.keys(errors).length) return fail(req, res, back(req, '/keys'), 'Please fix the highlighted fields.', errors);
    patch = {
      game, user_key: userKey, duration, max_devices: maxDevices, status, registrator,
      expired_date: expiredDate, devices: buildDevices(body.devices, maxDevices) || null,
      updated_at: nowIso(),
    };
  } else {
    const status = num(body.status, 1);
    if (![0, 1].includes(status)) errors.status = 'Invalid status.';
    if (Object.keys(errors).length) return fail(req, res, back(req, '/keys'), 'Please fix the highlighted fields.', errors);
    patch = { status, updated_at: nowIso() };
  }
  const { error } = await db().from('keys_code').update(patch).eq('id_keys', id);
  if (error) {
    console.error('[anoymundo] key update failed:', error.message);
    return fail(req, res, back(req, '/keys'), 'Could not save this key. Nothing was changed.');
  }
  return redirect(res, back(req, '/keys'), { msgSuccess: 'Key updated.' });
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
    // Refresh this session so the new password does not immediately sign the
    // user out; every *other* session for this account is now invalid.
    const { data: refreshed } = await db().from('users').select('*')
      .eq('id_users', user.id_users).maybeSingle();
    if (refreshed) setSession(res, refreshed, !!user.session?.remember);
    return redirect(res, targetUrl, { msgSuccess: 'Password updated. Other sessions are now signed out.' });
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
  const targetUrl = '/server';
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
  if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
  const setSaldo = Math.trunc(num(body.set_saldo, -1));
  const days = num(body.accExpire, 0);
  const level = num(body.accLevel, 3);
  const errors = {};
  if (!Number.isSafeInteger(setSaldo) || setSaldo < 0 || setSaldo > 99999) errors.set_saldo = 'Enter a balance between 0 and 99999.';
  if (!config.accExpire[days]) errors.accExpire = 'Choose how long the account lasts.';
  if (level === 1 || !config.accLevel[level]) errors.accLevel = 'Choose Admin or Reseller.';
  // Only the Owner can hand out Admin accounts.
  if (!errors.accLevel && level === 2 && !user.isOwner) errors.accLevel = 'Only the Owner can create Admin accounts.';
  if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Please fix the highlighted fields.', errors);

  // Balance given away with a code comes out of the creator's own balance
  // (the Owner's balance is unlimited). Debit first, conditionally, so two
  // tabs cannot spend the same balance twice.
  let debited = false;
  if (!isUnlimited(user) && setSaldo > 0) {
    const { data: fresh } = await db().from('users').select('saldo').eq('id_users', user.id_users).maybeSingle();
    const before = Number(fresh?.saldo || 0);
    if (before < setSaldo) {
      return fail(req, res, targetUrl, `Not enough balance. You have ${inr(before)}.`, { set_saldo: 'More than your balance.' });
    }
    const { data: rows, error } = await db().from('users')
      .update({ saldo: before - setSaldo, updated_at: nowIso() })
      .eq('id_users', user.id_users).eq('saldo', before).select('id_users');
    if (error || !rows?.length) return fail(req, res, targetUrl, 'Your balance just changed. Please try again.');
    debited = true;
  }

  const code = randomCode(7);
  const { error } = await db().from('referral_code').insert({
    code: codeHash(code), referral: code, level: Math.max(2, Math.min(3, level)),
    set_saldo: Math.max(0, setSaldo), used_by: '', created_by: user.username,
    acc_expiration: new Date(Date.now() + days * 86400_000).toISOString(),
    created_at: nowIso(), updated_at: nowIso(),
  });
  if (error) {
    console.error('[anoymundo] referral insert failed:', error.message);
    if (debited) {
      const { data: now } = await db().from('users').select('saldo').eq('id_users', user.id_users).maybeSingle();
      await db().from('users').update({ saldo: Number(now?.saldo || 0) + setSaldo, updated_at: nowIso() }).eq('id_users', user.id_users);
    }
    return fail(req, res, targetUrl, 'Could not create the referral code. Nothing was charged.');
  }
  return redirect(res, targetUrl, { msgSuccess: `Referral code created: ${code}` });
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
  if (!alnum(username, 4, 25)) errors.username = 'Use 4-25 letters or numbers.';
  else if (username.toLowerCase() !== String(target.data.username).toLowerCase() && (await getUserByUsername(username))) errors.username = 'That username is taken.';
  if (fullname && !/^[A-Za-z ]{4,155}$/.test(fullname)) errors.fullname = 'Only alphabetical characters and spaces.';
  if (level === 1 || !config.accLevel[level]) errors.level = 'Choose Admin or Reseller.';
  if (![1, 2, 3].includes(status)) errors.status = 'Invalid status.';
  if (!Number.isSafeInteger(saldo) || saldo < 0 || saldo > 999999999) errors.saldo = 'Invalid balance.';
  const uplinkUser = await getUserByUsername(uplink);
  if (!uplinkUser) errors.uplink = 'Uplink not registered anymore.';
  if (str(body.expiration) && !expiration) errors.expiration = 'Invalid expiration date.';
  if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Please fix the highlighted fields.', errors);
  const { error } = await db().from('users').update({
    username, fullname, level, status, saldo: Math.max(0, saldo), uplink,
    expiration_date: expiration, updated_at: nowIso(),
  }).eq('id_users', id);
  if (error) {
    console.error('[anoymundo] user update failed:', error.message);
    return fail(req, res, targetUrl, 'Could not save this user. Nothing was changed.');
  }
  // Keys belong to a username; keep them attached when the user is renamed.
  if (username !== target.data.username) {
    await db().from('keys_code').update({ registrator: username }).eq('registrator', target.data.username);
    await db().from('users').update({ uplink: username }).eq('uplink', target.data.username);
  }
  return redirect(res, targetUrl, { msgSuccess: `Saved ${username}.` });
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
 *   upload -> new file OR external https link becomes the current release.
 *             One rule when both arrive: the file wins and the link is
 *             ignored (never stored, never validated), so exactly one release
 *             is published per click.
 *   link   -> push a new version + external URL onto the current release
 *             (kept for older panel pages and scripts)
 *
 * Both are validated BEFORE anything is changed, and if the insert fails the
 * previous current release is restored. It can never leave zero current
 * releases (which was the "external link update doesn't work" failure).
 */
async function doLibUpload(req, res, body, user) {
  if (!user.isOwner) return sendJson(res, { ok: false, error: 'Only the Owner can publish releases.' }, 403);
  const action = str(body.action) === 'link' ? 'link' : 'upload';
  const version = str(body.version).slice(0, 32);
  const base64 = action === 'upload' ? str(body.content_base64) : '';
  // File wins: with a file the link is dropped before anything looks at it.
  const externalUrl = base64 ? '' : str(body.external_url).slice(0, 2000);
  const notes = str(body.notes).slice(0, 500) || null;

  if (!validVersion(version)) {
    return sendJson(res, { ok: false, error: 'Enter a version like 1.3 (letters, numbers, . _ + -).' }, 400);
  }
  if (externalUrl && !validHttpsUrl(externalUrl)) {
    return sendJson(res, { ok: false, error: 'The external link must be a full https:// address (http:// is not allowed).' }, 400);
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
  if (fetchSite === 'same-origin') return true; // set by the browser, cannot be forged by a page
  if (fetchSite && fetchSite !== 'none') return false;
  // Origin is "null" for some privacy modes; fall back to the Referer then.
  const origin = String(h.origin || '');
  const source = origin && origin !== 'null' ? origin : String(h.referer || '');
  if (!source) return true; // non-browser clients
  let sourceHost;
  try { sourceHost = new URL(source).host; } catch { return false; }
  const allowed = new Set();
  for (const raw of [h['x-forwarded-host'], h.host, config.baseUrl]) {
    if (!raw) continue;
    try { allowed.add(new URL(/^https?:/.test(String(raw)) ? String(raw) : `https://${raw}`).host); }
    catch { /* ignore malformed host */ }
  }
  return allowed.has(sourceHost);
}

/** Standalone page for errors / 404, same look as the rest of the panel. */
function plainPage(title, message, detail = '', status = 200) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#000000"><meta name="color-scheme" content="dark">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<title>${escapeHtml(title)} · ${escapeHtml(config.brand)}</title>
<link rel="stylesheet" href="/assets/css/ui.css"></head>
<body class="auth"><main class="auth-shell"><div class="auth-panel center fade-in" data-status="${status}">
<div class="auth-card">
<div class="tile tile-lg ${status >= 500 ? 'red' : status >= 400 ? 'orange' : ''}">${iconSvg(status >= 500 ? 'triangle-alert' : status === 404 ? 'search' : 'info')}</div>
<h1 class="auth-title">${escapeHtml(title)}</h1>
<p class="lead">${escapeHtml(message)}</p>
${detail ? `<p class="hint mono">${escapeHtml(detail)}</p>` : ''}
<a href="/dashboard" class="btn btn-primary btn-block" style="margin-top:18px">${iconSvg('layout-dashboard')}Back to dashboard</a>
</div>
</div></main></body></html>`;
}

function errorPage(title, message, ref) {
  return plainPage(title, message, `Reference ${ref}`, 500);
}

function isJsonRoute(p, method, req) {
  const lp = p.toLowerCase();
  const accept = String(req.headers?.accept || '');
  const contentType = String(req.headers?.['content-type'] || '');
  return lp.startsWith('/api/') || lp === '/connect' || lp === '/health' ||
    ((lp === '/lib' || lp === '/app-update') && method === 'POST') || lp === '/app-update/latest-build' ||
    lp.startsWith('/keys/reset') || lp === '/keys/api' ||
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
  // Declared outside the try so the failure handler can always use them.
  let p = '/';
  const method = String(req.method || 'GET').toUpperCase();
  try {
    const url = requestUrl(req);
    const q = url.searchParams;
    p = pathOf(req);
    const lp = p.toLowerCase();
    const body = method === 'POST' || method === 'PUT' || method === 'PATCH' ? await parseBody(req) : {};

    // State-changing requests from a browser must originate from the panel
    // itself (session cookies are SameSite=Lax; this is the second layer).
    const machineRoute = lp === '/connect' || lp === '/api/connect' || lp.startsWith('/api/');
    if (!machineRoute && (method === 'POST' || (method === 'GET' && isMutatingGet(lp))) && !sameOriginMutation(req)) {
      if (method === 'POST' && (lp === '/login' || lp === '/register')) {
        return redirect(res, lp, { msgDanger: 'Please submit the form from this site.' });
      }
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
    if (lp === '/api/lib/manifest') return handleLibManifest(res);
    // The Android app's own update (APK), checked on the splash screen.
    if (lp === '/api/app/update') return handleAppUpdateCheck(res, q);
    if (lp === '/favicon.ico') { res.statusCode = 301; res.setHeader('Location', '/favicon.svg'); return res.end(); }
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
      return redirect(res, '/login', { msgSuccess: 'You have been signed out.' });
    }

    // Everything below requires a valid session.
    const user = await guard(req, res);
    if (!user) return;

    // ------------------------------ dashboard ------------------------------
    if (lp === '/dashboard' || lp === '/dbg') {
      const { stats } = await dashboardData(user);
      return page(req, res, 'dashboard', { user, title: 'Dashboard', stats, unlimited: isUnlimited(user) });
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
            console.error('[anoymundo] api key create failed:', error);
            return redirect(res, '/api-keys', { msgDanger: 'Could not create the API key. Please try again.' });
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
            console.error('[anoymundo] api key update failed:', error);
            return redirect(res, '/api-keys', { msgDanger: 'Could not update the API key. Please try again.' });
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
      return page(req, res, 'keys_list', { user, title: 'Keys', keyTotal: await countKeys(user) });
    }
    if (lp === '/keys/api') return keysList(res, user, q);
    if (lp === '/keys/generate') {
      if (method === 'POST') return doGenerate(req, res, body, user);
      const unlimited = isUnlimited(user);
      return page(req, res, 'keys_generate', {
        user, title: 'Generate keys', unlimited,
        balanceLow: !unlimited && Number(user.saldo) <= 0,
        form: { max_devices: 1, duration: 24, game: 'PUBG', count: 1 },
        prefixJson: JSON.stringify(Object.fromEntries(Object.keys(config.prices)
          .map((h) => [h, keyPrefix(user.username, h)]))),
        customMin: config.customSuffixMin,
        customMax: config.customSuffixMax,
        // Duration cards: label, key token and price per device.
        durations: Object.entries(config.durations).map(([h, label]) => ({
          hours: String(h), label, token: config.durationTokens[h] || '', price: inr(config.prices[h]),
        })),
        selects: {
          game: buildSelect('game', 'game', config.games, 'PUBG'),
          duration: buildSelect('duration', 'duration', Object.fromEntries(Object.entries(config.durations)
            .map(([h, label]) => [h, `${label} · ${inr(config.prices[h])} per device`])), 24),
        },
      });
    }
    if (lp === '/keys/edit' && method === 'POST') return doKeyEdit(req, res, body, user);
    if (lp === '/keys/reset' || lp === '/keys/resetall') {
      const userKey = str(body.userkey ?? q.get('userkey'));
      const key = userKey ? await getKeyByUserKey(userKey) : null;
      // "Not found" and "not yours" look the same so keys cannot be probed.
      if (!key || (!user.isOwner && key.registrator !== user.username)) {
        return sendJson(res, { ok: false, registered: false, keys: userKey, error: 'That key no longer exists.' }, 404);
      }
      const max = Number(key.max_devices || 1);
      if (lp === '/keys/reset') {
        const { error } = await db().from('keys_code').update({ devices: null, updated_at: nowIso() }).eq('id_keys', key.id_keys);
        if (error) return sendJson(res, { ok: false, registered: true, error: 'Could not reset devices. Please try again.' }, 500);
        return sendJson(res, { ok: true, registered: true, keys: key.user_key, reset: true, devices_total: 0, devices_max: max });
      }
      const refund = await deleteKeysWithRefund([key.id_keys], user);
      if (!refund.ok) return sendJson(res, { ok: false, registered: true, keys: key.user_key, deleted: false, error: refund.message }, 500);
      return sendJson(res, {
        ok: true, registered: true, keys: key.user_key, deleted: refund.deleted > 0,
        refunded: refund.refunded, refund_total: refund.refund_total,
        devices_total: 0, devices_max: max,
      });
    }
    if (lp === '/keys/deleteexp' || lp === '/keys/deleteunused') {
      let query = db().from('keys_code').select('id_keys,expired_date,registrator').limit(500);
      if (!user.isOwner) query = query.eq('registrator', user.username);
      query = lp === '/keys/deleteexp' ? query.lt('expired_date', nowIso()) : query.is('expired_date', null);
      const { data, error } = await query;
      if (error) return redirect(res, '/keys', { msgDanger: 'Could not read keys. Nothing was deleted.' });
      const ids = (data || []).map((row) => row.id_keys);
      const result = await deleteKeysWithRefund(ids, user);
      return redirect(res, '/keys', result.ok ? { msgSuccess: refundMessage(result) } : { msgDanger: refundMessage(result) });
    }
    if (lp === '/keys/download') {
      let query = db().from('keys_code').select('user_key').order('id_keys', { ascending: false }).limit(10000);
      if (!user.isOwner) query = query.eq('registrator', user.username);
      const { data, error } = await query;
      if (error) return redirect(res, '/keys', { msgDanger: 'Could not export keys. Please try again.' });
      const text = (data || []).map((r) => r.user_key).join('\n');
      res.setHeader('Content-Disposition', 'attachment; filename="keys.txt"');
      return sendText(res, text);
    }
    const keyEditMatch = p.match(/^\/keys\/(\d+)$/i);
    if (keyEditMatch) {
      const key = await getKeyById(Number(keyEditMatch[1]));
      if (!key || (!user.isOwner && key.registrator !== user.username)) {
        return redirect(res, '/keys', { msgDanger: 'That key no longer exists.' });
      }
      return page(req, res, 'keys_edit', { user, title: 'Edit key', ...keyFormData(key) });
    }

    // ------------------------------- settings ------------------------------
    if (lp === '/settings') {
      if (method === 'POST') return doSettings(req, res, body, user, '/settings');
      return page(req, res, 'settings', { user, title: 'Settings', unlimited: isUnlimited(user), form: { fullname: user.fullname || '' } });
    }

    // ---------------------------- server settings --------------------------
    if (lp === '/server') {
      if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      if (method === 'POST') return doServer(req, res, body, user);
      const settings = await getSettings();
      return page(req, res, 'server', { user, title: 'Server', ...serverData(settings) });
    }

    // --------------------------------- admin --------------------------------
    if (lp === '/admin/create-referral') {
      if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      if (method === 'POST') return doReferral(req, res, body, user);
      let codesQuery = db().from('referral_code').select('*', { count: 'exact' }).order('id_reff', { ascending: false }).limit(25);
      if (!user.isOwner) codesQuery = codesQuery.eq('created_by', user.username);
      const { data: codes, count, error } = await codesQuery;
      if (error) throw new Error(error.message);
      const levels = user.isOwner ? { 3: 'Reseller', 2: 'Admin' } : { 3: 'Reseller' };
      return page(req, res, 'admin_referral', {
        user, title: 'Referral codes', unlimited: isUnlimited(user),
        codes: (codes || []).map((c) => ({
          ...c,
          levelName: ({ 2: 'Admin', 3: 'Reseller' })[Number(c.level)] || '—',
          expiresLabel: c.acc_expiration ? humanDate(c.acc_expiration) : '—',
          saldoLabel: inr(c.set_saldo),
        })),
        totalCodes: count ?? (codes || []).length,
        selects: {
          accExpire: buildSelect('accExpire', 'accExpire', config.accExpire, 30),
          accLevel: buildSelect('accLevel', 'accLevel', levels, 3),
        },
      });
    }
    if (lp === '/admin/manage-users') {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      const userList = (await listUsers()).map((u) => ({
        ...u,
        levelName: ({ 1: 'Owner', 2: 'Admin', 3: 'Reseller' })[Number(u.level)] || 'Unknown',
        statusName: ({ 1: 'Active', 2: 'Banned', 3: 'Expired' })[Number(u.status)] || 'Unknown',
        saldoLabel: Number(u.level) === 1 ? 'Unlimited' : inr(u.saldo),
        expiresLabel: u.expiration_date ? humanDate(u.expiration_date) : 'Never',
        isOwnerRow: Number(u.level) === 1,
      }));
      return page(req, res, 'admin_users', { user, title: 'Users', userList });
    }
    const userDeleteMatch = p.match(/^\/admin\/user\/delete\/(\d+)$/i);
    if (userDeleteMatch) {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      const targetId = Number(userDeleteMatch[1]);
      if (targetId === user.id_users) return redirect(res, '/admin/manage-users', { msgDanger: 'You cannot delete yourself.' });
      const { data: target } = await db().from('users').select('id_users,username,level').eq('id_users', targetId).maybeSingle();
      if (!target) return redirect(res, '/admin/manage-users', { msgDanger: 'User no longer exists.' });
      if (Number(target.level) === 1 || target.username === config.ownerUsername) {
        return redirect(res, '/admin/manage-users', { msgDanger: 'The Owner account cannot be deleted.' });
      }
      const { data: theirKeys } = await db().from('keys_code').select('id_keys').eq('registrator', target.username).limit(500);
      const removed = await deleteKeysWithRefund((theirKeys || []).map((k) => k.id_keys), user);
      if (!removed.ok) return redirect(res, '/admin/manage-users', { msgDanger: 'Could not remove this user\'s keys. Nothing was deleted.' });
      const { error } = await db().from('users').delete().eq('id_users', targetId);
      if (error) return redirect(res, '/admin/manage-users', { msgDanger: 'Could not delete this user. Please try again.' });
      return redirect(res, '/admin/manage-users', { msgSuccess: 'User and their keys removed.' });
    }
    const userEditMatch = p.match(/^\/admin\/user\/(\d+)$/i);
    if (userEditMatch) {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      const id = Number(userEditMatch[1]);
      if (method === 'POST') return doUserEdit(req, res, body, user);
      const { data: target } = await db().from('users').select('*').eq('id_users', id).maybeSingle();
      if (!target) return redirect(res, '/admin/manage-users', { msgDanger: 'User no longer exists.' });
      if (Number(target.level) === 1) return redirect(res, '/admin/manage-users', { msgWarning: 'The Owner account is managed from Settings.' });
      return page(req, res, 'admin_user_edit', {
        user, title: 'Edit user',
        target: { ...target, displayName: displayName(target) },
        form: {
          username: target.username, fullname: target.fullname, saldo: target.saldo,
          uplink: target.uplink, expiration: target.expiration_date ? sqlDateTime(target.expiration_date) : '',
        },
        selects: {
          level: buildSelect('level', 'level', { 2: 'Admin', 3: 'Reseller' }, target.level),
          status: buildSelect('status', 'status', { 1: 'Active', 2: 'Banned', 3: 'Expired' }, target.status),
        },
      });
    }

    // ---------------------------- library / release ------------------------
    if (lp === '/lib') {
      if (!user.isOwner) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      if (method === 'POST') return doLibUpload(req, res, body, user);
      const settings = await getSettings();
      return page(req, res, 'lib', { user, title: 'Library', maxUploadMb: config.maxUploadMb, ...serverData(settings) });
    }

    // ------------------------- app (APK) update ----------------------------
    if (lp === '/app-update/latest-build') {
      if (!user.isAdmin) return sendJson(res, { ok: false, error: 'Only the Owner or an Admin can do that.' }, 403);
      return handleLatestBuild(res);
    }
    if (lp === '/app-update') {
      if (!user.isAdmin) return redirect(res, '/dashboard', { msgWarning: 'You do not have access to that page.' });
      if (method === 'POST') return doAppReleasePost(req, res, body, user);
      return page(req, res, 'app_update', { user, title: 'App update', ...(await appReleasePageData()) });
    }

    // -------------------------------- 404 -----------------------------------
    return sendHtml(res, plainPage('Page not found', 'This page does not exist.', p, 404), 404);
  } catch (error) {
    return handleFailure(req, res, p, method, error);
  }
}

// Default export doubles as a Vercel Node entrypoint (req, res) => void.
export default handle;
