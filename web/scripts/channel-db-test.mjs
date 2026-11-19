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
  ok((await fs.readFile(new URL('../../supabase/schema.sql', import.meta.url), 'utf8')).endsWith(migration), 'fresh schema and migration share exactly the same feature SQL');
  await db.exec(migration);
  await db.exec(migration); // deliberately re-run to verify idempotence
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
  const permissions = await rows("select proname, has_function_privilege('anon', p.oid, 'EXECUTE') a, has_function_privilege('authenticated', p.oid, 'EXECUTE') u, has_function_privilege('service_role', p.oid, 'EXECUTE') s from pg_proc p where proname in ('create_owner_invite','register_owner_from_referral','publish_library_release','publish_app_release')");
  ok(permissions.length === 4 && permissions.every(p => !p.a && !p.u && p.s), 'owner and publish RPCs are service-role only');
  console.log(`\n${checks} PostgreSQL checks passed`);
} finally { await db.close(); }
