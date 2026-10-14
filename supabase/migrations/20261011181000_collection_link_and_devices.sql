-- First-link preview/commit, conflict resolution and device revocation. The preview is
-- read-only over the live remote state and binds a plan to the server's own high-water
-- sequence plus the caller's local digest; commit re-checks both before seeding, so a
-- changing cloud or a changed local collection invalidates the preview instead of
-- silently merging. Seeding reuses push_collection_operations, keeping replay, CAS and
-- conflict semantics identical to ordinary sync.

alter table public.sync_conflicts add column resolution text
  check (resolution is null or resolution in ('keep-local', 'keep-remote', 'dismiss'));

-- Normalizes the optional pricePaid payload for equality checks.
create function public._tcg_price_key(p_item jsonb) returns jsonb
  language sql immutable set search_path = public, pg_temp
as $$
  select case
    when p_item->'pricePaid' is null or jsonb_typeof(p_item->'pricePaid') = 'null' then 'null'::jsonb
    else jsonb_build_object(
      'amount', (p_item#>>'{pricePaid,amount}')::numeric,
      'currency', p_item#>>'{pricePaid,currency}'
    )
  end;
$$;

-- Compares a client SyncItem jsonb with a server-built remote item jsonb.
create function public._tcg_same_item(p_left jsonb, p_right jsonb) returns boolean
  language sql immutable set search_path = public, pg_temp
as $$
  select (p_left->>'cardId') = (p_right->>'cardId')
     and (p_left->>'variant') = (p_right->>'variant')
     and (p_left->>'condition') = (p_right->>'condition')
     and (p_left->>'quantity')::numeric = (p_right->>'quantity')::numeric
     and (p_left->>'addedAt')::numeric = (p_right->>'addedAt')::numeric
     and public._tcg_price_key(p_left) = public._tcg_price_key(p_right);
$$;

create function public.prepare_collection_link(p_local_digest text, p_local_items jsonb, p_remote_sequence bigint)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_cid uuid;
  v_count int;
  v_item jsonb;
  v_id text;
  v_reason text;
  v_remote jsonb;
  v_high bigint;
  v_plan_id uuid;
  v_expires timestamptz;
  v_local_only jsonb;
  v_cloud_only jsonb;
  v_identical jsonb;
  v_conflicting jsonb;
  v_dup jsonb;
  i int;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;
  if p_local_digest is null or length(p_local_digest) < 8 or length(p_local_digest) > 128 then
    raise exception 'invalid-preview';
  end if;
  if p_local_items is null or jsonb_typeof(p_local_items) <> 'array' then
    raise exception 'invalid-preview';
  end if;
  v_count := jsonb_array_length(p_local_items);
  if v_count > 5000 then
    raise exception 'invalid-preview';
  end if;

  v_cid := public._tcg_ensure_collection(v_owner);

  for i in 0 .. v_count - 1 loop
    v_item := p_local_items -> i;
    v_id := v_item->>'id';
    if v_id is null or v_id !~ '^[a-zA-Z0-9-]{8,80}$' then
      raise exception 'invalid-preview';
    end if;
    v_reason := public._tcg_validate_item(v_item, v_id);
    if v_reason is not null then
      raise exception 'invalid-preview';
    end if;
  end loop;

  select remote_sequence into v_high from public.collections where id = v_cid;

  select coalesce(jsonb_agg(
    jsonb_build_object(
      'id', item_id,
      'cardId', card_id,
      'quantity', quantity,
      'variant', variant,
      'condition', condition,
      'pricePaid', case when price_paid_amount is null then null
                        else jsonb_build_object('amount', price_paid_amount, 'currency', price_paid_currency) end,
      'addedAt', added_at
    ) order by item_id), '[]'::jsonb)
  into v_remote
  from public.collection_items
  where collection_id = v_cid and not deleted;

  select coalesce(jsonb_agg(loc->>'id' order by loc->>'id'), '[]'::jsonb)
  into v_local_only
  from jsonb_array_elements(p_local_items) as loc
  where not exists (
    select 1 from jsonb_array_elements(v_remote) as remote
    where remote->>'id' = loc->>'id'
  );

  select coalesce(jsonb_agg(r->>'id' order by r->>'id'), '[]'::jsonb)
  into v_cloud_only
  from jsonb_array_elements(v_remote) as r
  where not exists (
    select 1 from jsonb_array_elements(p_local_items) as l
    where l->>'id' = r->>'id'
  );

  select coalesce(jsonb_agg(l->>'id' order by l->>'id'), '[]'::jsonb)
  into v_identical
  from jsonb_array_elements(p_local_items) as l
  join jsonb_array_elements(v_remote) as r on r->>'id' = l->>'id'
  where public._tcg_same_item(l, r);

  select coalesce(jsonb_agg(jsonb_build_object('itemId', l->>'id', 'reason', 'different-content') order by l->>'id'), '[]'::jsonb)
  into v_conflicting
  from jsonb_array_elements(p_local_items) as l
  join jsonb_array_elements(v_remote) as r on r->>'id' = l->>'id'
  where not public._tcg_same_item(l, r);

  select coalesce(jsonb_agg(
    jsonb_build_object('localItemId', l->>'id', 'remoteItemId', existing.item_id) order by l->>'id'), '[]'::jsonb)
  into v_dup
  from jsonb_array_elements(p_local_items) as l
  join public.collection_items existing
    on existing.collection_id = v_cid
   and not existing.deleted
   and existing.card_id = l->>'cardId'
   and existing.variant = l->>'variant'
   and existing.condition = l->>'condition'
  where not exists (
    select 1 from public.collection_items mine
    where mine.collection_id = v_cid and mine.item_id = l->>'id'
  );

  insert into public.link_plans (collection_id, local_digest, remote_high_water, owner, expires_at)
  values (v_cid, p_local_digest, v_high, v_owner, now() + interval '15 minutes')
  returning id, expires_at into v_plan_id, v_expires;

  return jsonb_build_object(
    'planId', v_plan_id,
    'expiresAt', v_expires,
    'remoteHighWater', v_high,
    'preview', jsonb_build_object(
      'localOnly', v_local_only,
      'cloudOnly', v_cloud_only,
      'identical', v_identical,
      'conflicting', v_conflicting,
      'duplicateConfigurations', v_dup
    )
  );
end;
$$;

create function public.commit_collection_link(p_plan_id uuid, p_digest text, p_device_id text, p_operations jsonb)
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
  v_push jsonb;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;

  select collection_id, local_digest, remote_high_water, expires_at, consumed_at
  into v_cid, v_digest, v_plan_high, v_expires, v_consumed
  from public.link_plans
  where id = p_plan_id and owner = v_owner;

  if not found then
    return jsonb_build_object('status', 'not-found');
  end if;
  if p_digest is distinct from v_digest then
    return jsonb_build_object('status', 'digest-mismatch');
  end if;
  if v_consumed is not null then
    return jsonb_build_object('status', 'consumed');
  end if;
  if v_expires < now() then
    return jsonb_build_object('status', 'expired');
  end if;

  select remote_sequence into v_high from public.collections where id = v_cid for update;
  if v_high <> v_plan_high then
    return jsonb_build_object('status', 'stale-preview');
  end if;

  update public.link_plans set consumed_at = now()
  where id = p_plan_id and consumed_at is null;
  if not found then
    return jsonb_build_object('status', 'consumed');
  end if;

  if p_operations is not null and jsonb_typeof(p_operations) = 'array' and jsonb_array_length(p_operations) > 0 then
    v_push := public.push_collection_operations(p_device_id, p_operations);
  else
    v_push := jsonb_build_object('status', 'ok', 'results', '[]'::jsonb, 'highWater', v_high);
  end if;

  return jsonb_build_object('status', 'linked', 'push', v_push);
end;
$$;

create function public.resolve_collection_conflict(p_conflict_id uuid, p_resolution text, p_device_id text)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
begin
  if p_resolution is null or p_resolution not in ('keep-local', 'keep-remote', 'dismiss') then
    raise exception 'invalid-resolution';
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

create function public.revoke_sync_device(p_device_id text)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_cid uuid;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;
  if p_device_id is null or p_device_id !~ '^[a-zA-Z0-9-]{8,80}$' then
    raise exception 'invalid-device';
  end if;
  v_cid := public._tcg_ensure_collection(v_owner);

  update public.sync_devices set revoked_at = now()
  where collection_id = v_cid and device_id = p_device_id and revoked_at is null;
  if found then
    return jsonb_build_object('status', 'revoked');
  end if;
  if exists (select 1 from public.sync_devices where collection_id = v_cid and device_id = p_device_id) then
    return jsonb_build_object('status', 'already-revoked');
  end if;
  return jsonb_build_object('status', 'not-found');
end;
$$;

revoke execute on function public._tcg_same_item(jsonb, jsonb) from public, anon, authenticated;
revoke execute on function public._tcg_price_key(jsonb) from public, anon, authenticated;
revoke execute on function public.prepare_collection_link(text, jsonb, bigint) from public, anon;
revoke execute on function public.commit_collection_link(uuid, text, text, jsonb) from public, anon;
revoke execute on function public.resolve_collection_conflict(uuid, text, text) from public, anon;
revoke execute on function public.revoke_sync_device(text) from public, anon;
grant execute on function public.prepare_collection_link(text, jsonb, bigint) to authenticated;
grant execute on function public.commit_collection_link(uuid, text, text, jsonb) to authenticated;
grant execute on function public.resolve_collection_conflict(uuid, text, text) to authenticated;
grant execute on function public.revoke_sync_device(text) to authenticated;
