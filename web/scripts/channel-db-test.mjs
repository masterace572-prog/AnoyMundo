// Real PostgreSQL semantics offline (PGlite). No production credentials/network.
import { PGlite } from '@electric-sql/pglite';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

const db = new PGlite();
const rows = async (sql, args = []) => (await db.query(sql, args)).rows;
let checks = 0;
const ok = (value, message) => { assert.ok(value, message); checks++; console.log('  PASS  ' + message); };
const rejects = async (fn, message) => { await assert.rejects(fn); checks++; console.log('  PASS  ' + message); };
try {
  await db.exec(`create role anon; create role authenticated; create role service_role;
    create schema storage; create table storage.buckets(id text primary key, name text, public boolean, file_size_limit bigint);`);
  const base = (await fs.readFile(new URL('../../supabase/schema.sql', import.meta.url), 'utf8')).split('-- BEGIN TWO OWNER CHANNELS')[0].replace('create extension if not exists pgcrypto;', '');
  await db.exec(base);
  await db.exec('create unique index if not exists users_single_owner_idx on public.users(level) where level = 1;');
  await db.exec(`update public.client_release set content_base64 = 'preserve-existing-bytes', file_size = 12345, sha256 = repeat('a',64);
    insert into public.app_release(version_name, version_code, apk_url, changelog, mandatory, is_current) values('2.3.8',17,'https://example.com/Anoy.apk','Existing',true,true);`);
  const before = (await rows('select password from public.users where username = $1', ['Anoy']))[0].password;
  const migration = await fs.readFile(new URL('../../supabase/migrations/20261119_two_owner_channels.sql', import.meta.url), 'utf8');
  const lifecycle = await fs.readFile(new URL('../../supabase/migrations/20261119_referral_single_use.sql', import.meta.url), 'utf8');
  const schema = await fs.readFile(new URL('../../supabase/schema.sql', import.meta.url), 'utf8');
  ok(schema.split('-- BEGIN SINGLE USE REFERRALS')[0].trim().endsWith(migration.trim())
    && schema.endsWith(lifecycle), 'fresh schema includes both exact additive migrations');
  await db.exec(migration);
  await db.exec(lifecycle);
  await db.exec(migration); // deliberately re-run both to verify idempotence
  await db.exec(lifecycle);
  ok((await rows('select password, owner_channel from public.users where username=$1', ['Anoy']))[0].password === before, 'migration preserves the original owner password');
  ok((await rows('select content_base64, channel from public.client_release where is_current'))[0].content_base64 === 'preserve-existing-bytes', 'migration preserves the existing library bytes');
  ok((await rows('select channel, version_code from public.app_release where is_current'))[0].version_code === 17, 'migration preserves the existing APK update');
  ok((await rows('select public, file_size_limit from storage.buckets'))[0].public === false, 'new upload bucket is private');
  const [anoy] = await rows("select id_users from public.users where owner_channel='anoy'");
  const call = async (name, args) => (await rows(`select public.${name}(${args.map((_, i) => '$' + (i + 1)).join(',')}) as result`, args))[0].result;
  const invite = await call('create_owner_invite', [anoy.id_users, 'a'.repeat(32), 'KENNY24']);
  ok(invite.ok && invite.referral === 'KENNY24', 'Anoy can create the single-use second-owner invitation');
  ok((await call('create_owner_invite', [anoy.id_users, 'b'.repeat(32), 'OTHER24'])).referral === invite.referral, 'repeated invitation requests return the same pending code');
  ok(!(await call('create_owner_invite', [99999, 'c'.repeat(32), 'OTHER24'])).ok, 'unknown actors cannot invite an owner');
  const registration = await call('register_owner_from_referral', ['a'.repeat(32), 'KennyPvt', 'Kenny Owner', 'kenny@example.com', '$2a$08$' + 'x'.repeat(53), '127.0.0.1']);
  ok(registration.ok && registration.channel === 'kenny', 'owner registration atomically claims the Kenny channel');
  const [kenny] = await rows("select id_users, expiration_date from public.users where owner_channel='kenny'");
  ok(kenny.expiration_date === null, 'second owner has no account expiry');
  ok((await rows('select count(*)::int n from public.users where level=1 and expiration_date is not null'))[0].n === 0, 'both Owner accounts are permanent');
  await rejects(() => db.query("update public.users set expiration_date = now() + interval '7 days' where owner_channel='kenny'"), 'database cannot put Kenny on a seven-day account expiry');
  ok(!(await call('register_owner_from_referral', ['a'.repeat(32), 'ThirdOwner', 'Other Owner', 'other@example.com', '$2a$08$' + 'x'.repeat(53), '127.0.0.1'])).ok, 'owner referral cannot be replayed');
  ok(!(await call('create_owner_invite', [anoy.id_users, 'b'.repeat(32), 'OTHER24'])).ok, 'Anoy cannot invite a third owner');
  ok(!(await call('create_owner_invite', [kenny.id_users, 'b'.repeat(32), 'OTHER24'])).ok, 'Kenny cannot invite another owner');
  await rejects(() => db.query("insert into public.users(username,password,level,owner_channel) values('Third','hash',1,'third')"), 'database rejects a third owner channel');
  await rejects(() => db.query("insert into public.users(username,password,level,owner_channel) values('Duplicate','hash',1,'kenny')"), 'database rejects a duplicate owner channel');
  await rejects(() => db.query("update public.users set owner_channel='anoy' where owner_channel='kenny'"), 'owner channel cannot be reassigned');
  await rejects(() => db.query("update public.users set username='Takeover' where owner_channel='anoy'"), 'original owner cannot be renamed');
  await rejects(() => db.query("delete from public.users where owner_channel='kenny'"), 'second owner cannot be deleted');
  await rejects(() => db.query("update public.users set status=0 where owner_channel='anoy'"), 'original owner cannot be disabled');
  const pub = (actor, channel, version) => call('publish_library_release', [actor, JSON.stringify({
    channel, version, file_size: 100, sha256: 'a'.repeat(64), storage_path: `${channel}/releases/${crypto.randomUUID()}/libbgmi.zip`, game_version: '4.7.0',
  })]);
  ok((await pub(kenny.id_users, 'kenny', '0.1')).ok, 'Kenny can publish his own library');
  await rejects(() => pub(kenny.id_users, 'anoy', '99'), 'SQL rejects Kenny publishing to Anoy');
  await rejects(() => pub(anoy.id_users, 'kenny', '99'), 'SQL rejects Anoy publishing to Kenny');
  await rejects(() => call('publish_library_release', [kenny.id_users, JSON.stringify({ version: '99', file_size: 100, sha256: 'a'.repeat(64), storage_path: `anoy/releases/${crypto.randomUUID()}/libbgmi.zip` })]), 'SQL rejects cross-owner storage paths even without a channel parameter');
  ok((await rows("select content_base64 from public.client_release where channel='anoy' and is_current"))[0].content_base64 === 'preserve-existing-bytes', 'Kenny publication leaves the original library untouched');
  await rejects(() => pub(kenny.id_users, null, '99'), 'SQL rejects an explicitly null channel');
  await rejects(() => call('publish_library_release', [anoy.id_users, JSON.stringify({ channel: 'anoy', version: '99', external_url: 'https://example.com/a.so', game_version: 'x'.repeat(200) })]), 'a post-demotion insert failure rolls the entire library publication back');
  ok((await rows("select content_base64 from public.client_release where channel='anoy' and is_current"))[0].content_base64 === 'preserve-existing-bytes', 'failed publication keeps the previous current library and bytes');
  const app = (actor, channel, versionCode) => call('publish_app_release', [actor, JSON.stringify({ channel, version_name: '2.4.0', version_code: versionCode, apk_url: 'https://example.com/update.apk', changelog: 'Test', mandatory: false })]);
  ok((await app(kenny.id_users, 'kenny', 1)).ok, 'Kenny APK version codes are independent of Anoy');
  await rejects(() => app(kenny.id_users, 'anoy', 99), 'SQL rejects cross-owner APK publication');
  await rejects(() => app(anoy.id_users, 'kenny', 99), 'SQL rejects reciprocal cross-owner APK publication');
  await rejects(() => app(kenny.id_users, 'kenny', 1), 'APK version code must increase within its own channel');
  ok((await rows("select version_code from public.app_release where channel='anoy' and is_current"))[0].version_code === 17, 'Kenny APK publication leaves the existing mandatory Anoy update untouched');
  await db.exec((await fs.readFile(new URL('../../supabase/schema.sql', import.meta.url), 'utf8')).replace('create extension if not exists pgcrypto;', ''));
  ok((await rows("select count(*)::int n from public.users where level=1"))[0].n === 2, 'fresh-install schema can be re-run safely after both owners register');
  const order = await call('create_keys_atomic', ['kenny-order-test', kenny.id_users, 'PUBG', 24, 1, 80, 80, ['KENNYPVT-1D-TEST1']]);
  ok(order.ok && order.unlimited && order.price === 0, 'second owner uses the shared key system with unlimited balance');
  const permissions = await rows("select proname, has_function_privilege('anon', p.oid, 'EXECUTE') a, has_function_privilege('authenticated', p.oid, 'EXECUTE') u, has_function_privilege('service_role', p.oid, 'EXECUTE') s from pg_proc p where proname in ('create_owner_invite','register_owner_from_referral','register_account_from_referral','revoke_referral','publish_library_release','publish_app_release')");
  ok(permissions.length === 6 && permissions.every(p => !p.a && !p.u && p.s), 'owner and publish RPCs are service-role only');
  // Every normal role uses an atomic insert + one-time claim, not two HTTP writes.
  const add = async (text, level, creator = 'Anoy', expiry = null) => {
    const hash = crypto.createHash('md5').update(text).digest('hex');
    const [row] = await rows('insert into public.referral_code(code,referral,level,set_saldo,created_by,acc_expiration) values($1,$2,$3,100,$4,$5) returning *', [hash, text, level, creator, expiry]);
    return row;
  };
  const redeem = (code, name) => call('register_account_from_referral', [code.code, name, 'Fixture User', `${name}@example.com`, '$2a$08$' + 'x'.repeat(53), '127.0.0.1']);
  for (const [level, text, name] of [[2, 'ADMIN24', 'AdminSingle'], [3, 'RESELL4', 'ResellerSingle']]) {
    const code = await add(text, level);
    ok((await redeem(code, name)).ok, `${level === 2 ? 'Admin' : 'Reseller'} referral creates one user`);
    ok(!(await redeem(code, name + 'Other')).ok, 'the same referral cannot create a second user');
    const [user] = await rows('select level,saldo,owner_channel from public.users where username=$1', [name]);
    ok(user.level === level && user.saldo === 100 && user.owner_channel === null, 'referral role and balance are applied, without Owner authority');
    await rejects(() => db.query("update public.referral_code set used_by='' where id_reff=$1", [code.id_reff]), 'a used referral cannot be reset');
    await rejects(() => db.query('delete from public.referral_code where id_reff=$1', [code.id_reff]), 'a consumed code cannot be removed and reissued');
    ok((await call('revoke_referral', [anoy.id_users, code.id_reff])).ok, 'Owner can delete a used referral');
    ok((await rows('select count(*)::int n from public.users where username=$1', [name]))[0].n === 1, 'deleting a referral does not delete its registered account');
    ok(!(await redeem(code, name + 'Again')).ok, 'deleting the referral does not make it reusable');
  }
  const failed = await add('FAILINS', 3);
  ok(!(await redeem(failed, 'Anoy')).ok, 'failed account insert returns a safe error');
  ok((await rows('select used_by from public.referral_code where id_reff=$1', [failed.id_reff]))[0].used_by === '', 'failed insert rolls the claim back');
  ok((await redeem(failed, 'RetryUser')).ok, 'unused referral remains valid after a failed signup');
  const parallel = await add('RACE123', 3);
  const results = await Promise.all([redeem(parallel, 'RaceAlpha'), redeem(parallel, 'RaceBravo')]);
  ok(results.filter(r => r.ok).length === 1, 'parallel regular signups have exactly one winner');
  const revoked = await add('REVOKE4', 3);
  const [administrator] = await rows("select id_users from public.users where username='AdminSingle'");
  ok(!(await call('revoke_referral', [administrator.id_users, revoked.id_reff])).ok, "Admin cannot delete another creator's referral");
  ok(!(await call('revoke_referral', [999999, revoked.id_reff])).ok, 'unknown actor cannot delete a referral');
  ok((await call('revoke_referral', [anoy.id_users, revoked.id_reff])).ok, 'Owner can delete an unused referral');
  ok((await call('revoke_referral', [anoy.id_users, revoked.id_reff])).ok, 'repeated deletion is idempotent');
  ok(!(await redeem(revoked, 'RevokedUser')).ok, 'revoked referral can never register a user');
  await rejects(() => db.query('update public.referral_code set revoked_at=null where id_reff=$1', [revoked.id_reff]), 'deleted referral cannot be restored');
  await rejects(() => db.query('insert into public.referral_code(code,referral) values($1,$2)', [revoked.code, 'OTHER23']), 'deleted hash cannot be reissued');
  await rejects(() => db.query('insert into public.referral_code(code,referral) values($1,$2)', ['f'.repeat(32), revoked.referral.toLowerCase()]), 'deleted referral text cannot be reissued, including another case');
  const ownCode = await add('OWNREF4', 3, 'AdminSingle');
  ok((await call('revoke_referral', [administrator.id_users, ownCode.id_reff])).ok, 'Admin can delete their own Reseller referral');
  const expired = await add('EXPREF4', 3, 'Anoy', '2000-01-01T00:00:00Z');
  ok(!(await redeem(expired, 'ExpiredRegular')).ok, 'expired regular referral fails closed');
  ok(!(await redeem({ code: 'a'.repeat(32) }, 'ForgedOwner')).ok, 'regular registration RPC cannot redeem an Owner referral');
  ok(!(await call('revoke_referral', [kenny.id_users, 1])).ok, "Kenny cannot delete Anoy's Owner invitation");
  console.log(`\n${checks} PostgreSQL checks passed`);
} finally { await db.close(); }
