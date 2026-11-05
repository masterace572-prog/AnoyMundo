import crypto from 'node:crypto';
import { config } from './config.mjs';
import {
  db, unwrap, getUserByUsername, listUsers, getKeyById, getKeyByUserKey,
  getRecentHistory, getReferralCodes, getSettings, deviceList,
} from './db.mjs';
import {
  currentUser, authenticate, hashPassword, codeHash, verifyPassword,
  setSession, clearSession, getClientIp, displayName,
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

function fail(req, res, url, message, errors = {}, old = {}) {
  return redirect(res, url, { msgDanger: `<strong>Failed</strong> ${message}`, errors, old });
}

async function logAttempt(username, ip, ok) {
  try {
    await db().from('auth_attempt').insert({ username: String(username || '').slice(0, 66), ip: String(ip).slice(0, 100), ok });
  } catch { /* audit table is best-effort */ }
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

function serverData(settings) {
  return {
    onoff: settings.onoff,
    ftext: settings.ftext,
    feature: settings.feature,
    server: settings.modname,
    credit: settings.credit,
    lib: settings.lib ? { ...settings.lib, currentTime: sqlDateTime() } : {},
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
  if (password !== password2) errors.password2 = 'Passwords do not match.';
  if (!alnum(referral, 4, 25)) errors.referral = 'Enter a valid referral code.';

  let code = null;
  if (!errors.referral) {
    const { data, error } = await db().from('referral_code').select('*').eq('code', codeHash(referral)).maybeSingle();
    if (error || !data) errors.referral = 'Wrong referral, please try again.';
    else if (data.used_by) errors.referral = `Wrong referral, code has been used &middot; ${data.used_by}.`;
    else if (data.acc_expiration && new Date(data.acc_expiration).getTime() < Date.now()) errors.referral = 'This referral code has expired.';
    else code = data;
  }
  if (Object.keys(errors).length) return fail(req, res, '/register', 'Please check the form.', errors, old);

  const { error: insertError } = await db().from('users').insert({
    email, username, fullname,
    level: num(code.level, 3),
    password: hashPassword(password),
    saldo: num(code.set_saldo, 0),
    uplink: code.created_by,
    user_ip: getClientIp(req),
    expiration_date: code.acc_expiration || null,
    status: 1,
    created_at: nowIso(),
    updated_at: nowIso(),
  });
  if (insertError) return fail(req, res, '/register', 'Could not create the account.', { username: insertError.message }, old);
  await db().from('referral_code').update({ used_by: username, updated_at: nowIso() }).eq('id_reff', code.id_reff);
  return redirect(res, '/login', { msgSuccess: 'Register Successfuly! Please login.' });
}

async function doGenerate(req, res, body, user) {
  const game = str(body.game) || 'PUBG';
  const maxDevices = num(body.max_devices, 0);
  const duration = num(body.duration, 0);
  const custom = str(body.custominput) === 'custom';
  const cuslicense = str(body.cuslicense);
  const loopMap = { 1: 5, 2: 10, 3: 50, 4: 100, 5: 1 };
  const loops = loopMap[num(body.loopcount, 5)] || 1;
  const old = { game, max_devices: body.max_devices, duration: body.duration };
  const errors = {};
  if (!config.games[game]) errors.game = 'Invalid game.';
  if (!Number.isInteger(maxDevices) || maxDevices < 1) errors.max_devices = 'Minimum devices is 1.';
  if (!config.prices[duration]) errors.duration = 'Invalid duration.';
  const price = (config.prices[duration] || 0) * maxDevices;
  if (price <= 0) errors.duration = 'Invalid duration or devices.';
  if (Number(user.saldo) < price) errors.duration = 'Insufficient balance';
  if (Object.keys(errors).length) {
    return redirect(res, '/keys/generate', {
      msgWarning: 'Please top up to your beloved admin.', errors, old,
    });
  }

  const licenses = [];
  if (custom) {
    if (cuslicense.length < 4 || cuslicense.length > 19 || !/^[A-Za-z0-9_-]+$/.test(cuslicense)) {
      return fail(req, res, '/keys/generate', 'Custom Key is too Short/Long or has invalid characters.', { user_key: 'Invalid custom key' }, old);
    }
    const exists = await getKeyByUserKey(cuslicense);
    if (exists) return fail(req, res, '/keys/generate', 'Key already exists!!', { user_key: 'Key already exists' }, old);
    licenses.push(cuslicense);
  } else {
    for (let i = 0; i < loops; i += 1) licenses.push(`${user.username}-${duration}-${randomAlnum(5)}`);
  }

  let lastId = null;
  for (const license of licenses) {
    const { data, error } = await db().from('keys_code').insert({
      game, user_key: license, duration, max_devices: maxDevices,
      registrator: user.username, admin_id: user.id_users, status: 1,
      expired_date: null, created_at: nowIso(), updated_at: nowIso(),
    }).select().single();
    if (error) return fail(req, res, '/keys/generate', 'Could not generate key.', { user_key: error.message }, old);
    lastId = data?.id_keys ?? null;
  }

  await db().from('users').update({ saldo: Number(user.saldo) - price, updated_at: nowIso() }).eq('id_users', user.id_users);
  await db().from('history').insert({
    keys_id: String(lastId ?? ''), user_do: user.username,
    info: `${game}|${licenses[0].slice(0, 5)}|${duration}|${maxDevices}`,
    created_at: nowIso(), updated_at: nowIso(),
  });
  return redirect(res, '/keys/generate', {
    msgSuccess: `Successfuly Generated ${licenses.length} key(s). Fee: ₹${price}`,
    user_key: licenses.join('\n'), game, duration: String(duration), max_devices: String(maxDevices),
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
    if (!Number.isInteger(duration) || duration < 1) errors.duration = 'Invalid duration.';
    if (!Number.isInteger(maxDevices) || maxDevices < 1) errors.max_devices = 'Invalid max devices.';
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
    if (password !== password2) errors.password2 = 'Passwords do not match.';
    if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Please check the form.', errors);
    await db().from('users').update({ password: hashPassword(password), updated_at: nowIso() }).eq('id_users', user.id_users);
    return redirect(res, targetUrl, { msgSuccess: 'Password Successfuly Changed.' });
  }
  if (body.fullname_form) {
    const fullname = str(body.fullname);
    const errors = {};
    if (fullname === user.fullname) errors.fullname = 'Nothing to change.';
    if (!/^[A-Za-z ]{4,155}$/.test(fullname)) errors.fullname = 'Only alphabetical characters and spaces (4-155).';
    if (Object.keys(errors).length) return fail(req, res, targetUrl, 'Please check the form.', errors);
    await db().from('users').update({ fullname, updated_at: nowIso() }).eq('id_users', user.id_users);
    return redirect(res, targetUrl, { msgSuccess: 'Account Detail Successfuly Changed.' });
  }
  return redirect(res, targetUrl, { msgWarning: 'Unknown action.' });
}

async function doServer(req, res, body, user) {
  const targetUrl = '/Server';
  if (body.modname_form && user.isAdmin) {
    const modname = str(body.modname).slice(0, 100);
    if (!modname) return fail(req, res, targetUrl, 'Mod name cannot be empty.');
    await db().from('modname').update({ modname }).eq('id', 1);
    return redirect(res, targetUrl, { msgSuccess: 'Mod Name Successfuly Changed.' });
  }
  if (body.status_form && user.isAdmin) {
    const status = body.radios === 'on' ? 'on' : 'off';
    const myinput = str(body.myInput).slice(0, 500);
    await db().from('onoff').update({ status, myinput }).eq('id', 1);
    return redirect(res, targetUrl, { msgSuccess: 'Mod Status Successfuly Changed.' });
  }
  if (body.feature_form && user.isOwner) {
    const flags = ['ESP', 'Item', 'AIM', 'SilentAim', 'BulletTrack', 'Memory', 'Floating', 'Setting'];
    const patch = {};
    for (const flag of flags) patch[flag.toLowerCase()] = body[flag] === 'on' ? 'on' : 'off';
    await db().from('feature').update(patch).eq('id', 1);
    return redirect(res, targetUrl, { msgSuccess: 'Mod Feature Stats Changed.' });
  }
  if ('_ftextr' in body && user.isAdmin) {
    const myinput = str(body._ftext).slice(0, 100);
    const status = body._ftextr === 'Safe' ? 'Safe' : 'Anti-Cheat is High..!!';
    await db().from('_ftext').update({ _ftext: myinput, _status: status }).eq('id', 1);
    return redirect(res, targetUrl, { msgSuccess: 'Successfuly Changed Mod Floating And Status.' });
  }
  if (body.password_form || body.fullname_form) return doSettings(req, res, body, user, targetUrl);
  return redirect(res, targetUrl, { msgWarning: 'Access Denied!' });
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
  if (!config.accLevel[level]) errors.accLevel = 'Invalid level.';
  if (Object.keys(errors).length) return fail(req, res, targetUrl, 'check the form', errors);
  const code = randomAlnum(6);
  const { error } = await db().from('referral_code').insert({
    code: codeHash(code), referral: code.slice(0, 7), level,
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
  if (!config.accLevel[level]) errors.level = 'Invalid role.';
  if (![1, 2, 3].includes(status)) errors.status = 'Invalid status.';
  if (saldo < 0) errors.saldo = 'Invalid currency.';
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

async function doLibUpload(req, res, body, user) {
  if (!user.isOwner) return sendJson(res, { ok: false, error: 'Access Denied!' }, 403);
  const name = str(body.file_name).replace(/["\r\n/\\]/g, '_').slice(0, 255) || 'release.zip';
  const base64 = str(body.content_base64);
  const version = str(body.version).slice(0, 32) || '1.0';
  const externalUrl = str(body.external_url);
  if (!base64 && !externalUrl) return sendJson(res, { ok: false, error: 'No file or URL provided.' }, 400);
  const bytes = base64 ? Buffer.from(base64.replace(/^data:[^;]+;base64,/, ''), 'base64') : Buffer.alloc(0);
  if (bytes.length > config.maxUploadMb * 1024 * 1024) {
    return sendJson(res, { ok: false, error: `File is larger than ${config.maxUploadMb} MB (Vercel function body limit).` }, 400);
  }
  if (base64 && bytes.length < 4) return sendJson(res, { ok: false, error: 'Uploaded data looks invalid.' }, 400);
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  // Only one current release at a time (partial unique index).
  await db().from('client_release').update({ is_current: false }).eq('is_current', true);
  const { error } = await db().from('client_release').insert({
    version, file_name: name, file_size: bytes.length, sha256,
    content_base64: base64 ? bytes.toString('base64') : null,
    external_url: externalUrl || null,
    notes: str(body.notes).slice(0, 500) || null,
    is_current: true, uploaded_by: user.username, created_at: nowIso(),
  });
  if (error) return sendJson(res, { ok: false, error: error.message }, 500);
  await db().from('lib').insert({
    file: name, file_type: `Onlinelib/${name}`,
    file_size: `${(bytes.length / 1024).toFixed(1)} KB`, time: nowIso(),
  });
  return sendJson(res, { ok: true, version, file_name: name, file_size: bytes.length, sha256 });
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

    // --------------------------- public endpoints ---------------------------
    if (lp === '/api/health' || lp === '/health') return handleHealth(res);
    if (lp === '/api/lib/version' || lp === '/version.txt' || lp === '/api/version' || lp === '/lib/version.txt') {
      return handleLibVersion(res);
    }
    if (lp === '/api/lib/download' || lp === '/xlox.zip' || lp === '/lib.zip' ||
        lp === '/onlinelib/lib.so' || lp === '/lib.so' || lp === '/download/lib.so') {
      return handleLibDownload(req, res);
    }
    if (lp === '/api/lib/info') return handleLibInfo(res);
    if (lp === '/connect' || lp === '/api/connect') {
      if (method === 'POST') return handleConnect(req, res, body);
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

    // --------------------------------- keys ---------------------------------
    if (lp === '/keys') {
      const { data } = await db().from('keys_code').select('user_key');
      const keyList = (data || []).map((k) => k.user_key);
      return page(req, res, 'keys_list', { user, title: 'Keys', keyList });
    }
    if (lp === '/keys/api') return keysDataTable(res, user, q);
    if (lp === '/keys/generate') {
      if (method === 'POST') return doGenerate(req, res, body, user);
      const message = Number(user.saldo) > 0
        ? { text: `<i class="bi bi-wallet"></i> Total Saldo ₹${user.saldo}`, type: 'success' }
        : { text: 'Please top up to your beloved admin.', type: 'warning' };
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
      await db().from('keys_code').delete().eq('user_key', userKey);
      return sendJson(res, { registered: true, keys: userKey, deleted: true, devices_total: 0, devices_max: key.max_devices });
    }
    if (lp === '/keys/deleteexp' || lp === '/keys/deleteunused') {
      let query = db().from('keys_code').delete();
      if (lp === '/keys/deleteexp') query = query.lt('expired_date', nowIso());
      else query = query.is('expired_date', null);
      if (!user.isOwner) query = query.eq('registrator', user.username);
      await query;
      return redirect(res, '/keys', { msgSuccess: 'success' });
    }
    if (lp === '/keys/download') {
      const { data } = await db().from('keys_code').select('user_key');
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
      return page(req, res, 'settings', { user, title: 'Settings' });
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
      if (Number(userDeleteMatch[1]) === user.id_users) return redirect(res, '/admin/manage-users', { msgDanger: 'You cannot delete yourself.' });
      await db().from('users').delete().eq('id_users', Number(userDeleteMatch[1]));
      return redirect(res, '/admin/manage-users', { msgSuccess: 'success' });
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
    return sendHtml(res, `<!doctype html><html><head><meta charset="utf-8"><title>404 - ${escapeHtml(config.brand)}</title><link rel="stylesheet" href="/assets/css/tailwind.css"></head><body class="bg-slate-100"><div class="max-w-lg mx-auto mt-24 p-8 bg-white rounded-2xl shadow text-center"><h1 class="text-3xl font-bold text-purple-700 mb-2">404</h1><p class="text-gray-600 mb-6">Page not found: ${escapeHtml(p)}</p><a href="/dashboard" class="px-4 py-2 bg-purple-600 text-white rounded-lg">Back to dashboard</a></div></body></html>`, 404);
  } catch (error) {
    console.error('[anoymundo] request failed:', error);
    if (!res.headersSent) {
      res.statusCode = 500;
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.setHeader('Cache-Control', 'no-store');
    }
    const detail = process.env.NODE_ENV === 'production' ? 'Please check the server logs.' : escapeHtml(error.stack || error.message);
    return res.end(`<!doctype html><html><head><meta charset="utf-8"><title>Server error</title></head><body style="font-family:sans-serif;padding:2rem"><h1>Something went wrong</h1><pre style="white-space:pre-wrap">${detail}</pre><p><a href="/">Home</a></p></body></html>`);
  }
}
