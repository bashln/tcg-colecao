alter table public.sync_conflicts
  drop constraint sync_conflicts_resolution_check;

alter table public.sync_conflicts
  add constraint sync_conflicts_resolution_check
  check (resolution is null or resolution in ('keep-local', 'keep-cloud', 'keep-remote', 'dismiss'));

create or replace function public.read_collection_conflicts()
  returns jsonb
  language plpgsql stable security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_result jsonb;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'id', conflict.id,
    'itemId', conflict.item_id,
    'reason', conflict.reason,
    'payload', conflict.payload,
    'createdAt', conflict.created_at
  ) order by conflict.created_at, conflict.id), '[]'::jsonb)
  into v_result
  from public.sync_conflicts conflict
  where conflict.owner = v_owner
    and conflict.resolved_at is null;

  return v_result;
end;
$$;

create or replace function public.resolve_collection_conflict(p_conflict_id uuid, p_resolution text, p_device_id text)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;
  if p_resolution is null or p_resolution not in ('keep-local', 'keep-cloud', 'keep-remote') then
    raise exception 'invalid-resolution';
  end if;
  if p_device_id is null or p_device_id !~ '^[a-zA-Z0-9-]{8,80}$' then
    raise exception 'invalid-device';
  end if;

  update public.sync_conflicts
  set resolved_at = now(), resolution = p_resolution
  where id = p_conflict_id and owner = v_owner and resolved_at is null;
  if found then
    return jsonb_build_object('status', 'resolved');
  end if;

  if exists (select 1 from public.sync_conflicts where id = p_conflict_id and owner = v_owner) then
    return jsonb_build_object('status', 'already-resolved');
  end if;
  return jsonb_build_object('status', 'not-found');
end;
$$;

revoke execute on function public.read_collection_conflicts() from public, anon;
grant execute on function public.read_collection_conflicts() to authenticated;
revoke execute on function public.resolve_collection_conflict(uuid, text, text) from public, anon;
grant execute on function public.resolve_collection_conflict(uuid, text, text) to authenticated;
