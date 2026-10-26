-- Jarvis Galería: private per-user photo bucket. APPLY ON A PREVIEW BRANCH FIRST, not production.
insert into storage.buckets (id, name, public) values ('jarvis-gallery', 'jarvis-gallery', false)
on conflict (id) do nothing;

create policy "jarvis_gallery_read_own" on storage.objects for select to authenticated
  using (bucket_id = 'jarvis-gallery' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "jarvis_gallery_insert_own" on storage.objects for insert to authenticated
  with check (bucket_id = 'jarvis-gallery' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy "jarvis_gallery_delete_own" on storage.objects for delete to authenticated
  using (bucket_id = 'jarvis-gallery' and (storage.foldername(name))[1] = (select auth.uid())::text);
