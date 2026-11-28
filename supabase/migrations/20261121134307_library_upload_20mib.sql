-- BEGIN LIBRARY 20 MIB UPLOADS
-- Additive limit change only. Keep the bucket private; do not rewrite any
-- release, object, owner, licence, key, billing data or live APK selection.
-- The panel accepts 20 MiB input AND 20 MiB total expanded ZIP contents.
-- Reserve 1 MiB for normalized ZIP framing/deflate overhead in private Storage.
begin;
insert into storage.buckets(id, name, public, file_size_limit)
values ('loader-releases', 'loader-releases', false, 22020096)
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit;
commit;
