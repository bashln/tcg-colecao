alter table public.link_plans
  add column request_device_id text,
  add column request_operations jsonb,
  add column terminal_result jsonb;

alter function public.prepare_collection_link(text, jsonb, bigint)
  rename to _tcg_prepare_collection_link;

create or replace function public.commit_collection_link(p_plan_id uuid, p_digest text, p_device_id text, p_operations jsonb)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_cid uuid;
  v_digest text;
  v_high bigint;
  v_plan_high bigint;
  v_expires timestamptz;
  v_consumed timestamptz;
  v_saved_device text;
  v_saved_operations jsonb;
  v_terminal jsonb;
  v_push jsonb;
  v_status text;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;

  select collection_id, local_digest, remote_high_water, expires_at, consumed_at,
         request_device_id, request_operations, terminal_result
  into v_cid, v_digest, v_plan_high, v_expires, v_consumed,
       v_saved_device, v_saved_operations, v_terminal
  from public.link_plans
  where id = p_plan_id and owner = v_owner
  for update;

  if not found then
    return jsonb_build_object('status', 'not-found');
  end if;
  if p_digest is distinct from v_digest then
    return jsonb_build_object('status', 'digest-mismatch');
  end if;
  if v_terminal is not null then
    if p_device_id is distinct from v_saved_device or p_operations is distinct from v_saved_operations then
      return jsonb_build_object('status', 'payload-mismatch');
    end if;
    return v_terminal;
  end if;
  if v_consumed is not null then
    return jsonb_build_object('status', 'consumed');
  end if;
  if v_expires < now() then
    return jsonb_build_object('status', 'expired');
  end if;
  if p_device_id is null or p_device_id !~ '^[a-zA-Z0-9-]{8,80}$'
     or p_operations is null or jsonb_typeof(p_operations) <> 'array'
     or jsonb_array_length(p_operations) > 100 then
    raise exception 'invalid-request';
  end if;

  select remote_sequence into v_high from public.collections where id = v_cid for update;
  if v_high <> v_plan_high then
    return jsonb_build_object('status', 'stale-preview');
  end if;

  update public.link_plans
  set request_device_id = p_device_id, request_operations = p_operations
  where id = p_plan_id;

  if jsonb_array_length(p_operations) > 0 then
    v_push := public.push_collection_operations(p_device_id, p_operations);
  else
    v_push := jsonb_build_object('status', 'ok', 'results', '[]'::jsonb, 'highWater', v_high);
  end if;

  if v_push->>'status' = 'ok' and not exists (
    select 1
    from jsonb_array_elements(coalesce(v_push->'results', '[]'::jsonb)) outcome
    where outcome->>'status' not in ('applied', 'already-applied')
  ) then
    v_status := 'linked';
  else
    v_status := 'partial';
  end if;

  v_terminal := jsonb_build_object('status', v_status, 'push', v_push);
  update public.link_plans
  set consumed_at = now(), terminal_result = v_terminal
  where id = p_plan_id;
  return v_terminal;
end;
$$;

create or replace function public.prepare_collection_link(p_local_digest text, p_local_items jsonb, p_remote_sequence bigint)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_result jsonb;
begin
  delete from public.link_plans
  where owner = auth.uid()
    and ((expires_at < now() - interval '1 day')
      or (consumed_at < now() - interval '7 days'));

  -- Preserve the existing preview contract by invoking its unchanged implementation
  -- through a renamed helper installed below.
  v_result := public._tcg_prepare_collection_link(p_local_digest, p_local_items, p_remote_sequence);
  return v_result;
end;
$$;

revoke execute on function public.commit_collection_link(uuid, text, text, jsonb) from public, anon;
grant execute on function public.commit_collection_link(uuid, text, text, jsonb) to authenticated;
revoke execute on function public.prepare_collection_link(text, jsonb, bigint) from public, anon;
grant execute on function public.prepare_collection_link(text, jsonb, bigint) to authenticated;
