-- Durable push conflicts and the owner-scoped resolution protocol.
--
-- `push_collection_operations` records exactly one open conflict row per rejected
-- operation: retries of the same owner + operation id reuse that row and refresh its
-- authoritative cloud snapshot instead of duplicating it, and a rejected operation never
-- advances the collection sequence or enters the operation log. Every content-conflict
-- outcome now carries `conflictId`, `rejectedOperation`, `cloudItem`, `conflictingItem`
-- and a `highWater` snapshot label — the label documents the observed server state and is
-- never a pull cursor. Three new owner-scoped RPCs list, resolve, and acknowledge push
-- conflicts: a resolution records an immutable receipt under the conflict-row and item
-- locks that replays unchanged until the owner acknowledges its committed local
-- transaction. `invalid-payload`, `device-revoked` and `revision-limit` outcomes stay
-- distinct from resolvable content conflicts, and no client-supplied cloud value or base
-- revision is ever trusted — every snapshot is read from the database.
--
-- The `source` discriminator fences the legacy pull-conflict RPCs away from push-protocol
-- rows: rows that already exist are classified as `legacy`, rows recorded by this push
-- path are `push`. Historical duplicate open retries (created before the uniqueness
-- guards) are consolidated deterministically — the oldest open row wins, later duplicates
-- are closed as superseded with `superseded_by` pointing at the winner — so no payload is
-- ever deleted and operator-visible state stays reachable.

alter table public.sync_conflicts
  add column cloud_item jsonb,
  add column conflicting_item jsonb,
  add column observed_high_water bigint,
  add column refreshed_at timestamptz,
  add column resolution_id uuid,
  add column resolution_choice text,
  add column resolution_result jsonb,
  add column resolution_device_id text,
  add column source text not null default 'legacy',
  add column superseded_by uuid references public.sync_conflicts (id);

alter table public.sync_conflicts
  add constraint sync_conflicts_push_choice_check
  check (resolution_choice is null or resolution_choice in ('keep-local', 'keep-cloud'));

alter table public.sync_conflicts
  add constraint sync_conflicts_source_check
  check (source in ('legacy', 'push'));

-- Deterministic, non-destructive consolidation of duplicate open retries left behind by
-- the old push implementation. The oldest open row per key is canonical; later duplicates
-- keep their payload and are marked closed + superseded. Re-running is a no-op because
-- closed rows drop out of the ranking.
do $$
begin
  with ranked as (
    select id,
           first_value(id) over (partition by collection_id, op_id
                                 order by created_at, id) as canonical_id
      from public.sync_conflicts
     where op_id is not null and resolved_at is null
  ), losers as (
    select id, canonical_id from ranked where id <> canonical_id
  )
  update public.sync_conflicts sc
     set resolved_at = now(), superseded_by = l.canonical_id
    from losers l
   where sc.id = l.id;

  with ranked as (
    select id,
           first_value(id) over (partition by collection_id, payload
                                 order by created_at, id) as canonical_id
      from public.sync_conflicts
     where op_id is null and resolved_at is null
       and payload is not null
  ), losers as (
    select id, canonical_id from ranked where id <> canonical_id
  )
  update public.sync_conflicts sc
     set resolved_at = now(), superseded_by = l.canonical_id
    from losers l
   where sc.id = l.id;
end $$;

-- One open row per operation id, and one per malformed payload (operation id missing):
-- retries reuse it, acknowledgments and supersession close it.
create unique index if not exists sync_conflicts_open_operation
  on public.sync_conflicts (collection_id, op_id)
  where resolved_at is null and op_id is not null;

create unique index if not exists sync_conflicts_open_malformed
  on public.sync_conflicts (collection_id, payload)
  where resolved_at is null and op_id is null and payload is not null;

create function public._tcg_item_snapshot(p_collection_id uuid, p_item_id text)
  returns jsonb
  language sql stable set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'itemId', i.item_id,
    'cardId', i.card_id,
    'variant', i.variant,
    'condition', i.condition,
    'quantity', i.quantity,
    'pricePaid', case
      when i.price_paid_amount is null then null
      else jsonb_build_object('amount', i.price_paid_amount, 'currency', i.price_paid_currency)
    end,
    'addedAt', i.added_at,
    'revision', i.revision,
    'deleted', i.deleted
  )
  from public.collection_items i
  where i.collection_id = p_collection_id and i.item_id = p_item_id;
$$;

create function public._tcg_find_config_owner(p_collection_id uuid, p_item jsonb, p_exclude_item_id text)
  returns text
  language sql stable set search_path = public, pg_temp
as $$
  select i.item_id
  from public.collection_items i
  where i.collection_id = p_collection_id
    and not i.deleted
    and p_item is not null
    and i.card_id = p_item ->> 'cardId'
    and i.variant = p_item ->> 'variant'
    and i.condition = p_item ->> 'condition'
    and i.item_id is distinct from p_exclude_item_id
  limit 1;
$$;

-- Target item of a rejected operation, derived strictly by operation kind: a `delete`
-- addresses `itemId` and must never follow a crafted nested `item.id`, while
-- `create`/`replace` address `item.id`. Operations without a recognized kind (malformed
-- or legacy rows) keep the payload-first fallback. The recorded column is only a
-- fallback, never an override.
create function public._tcg_push_target_item(p_item_id text, p_operation jsonb)
  returns text
  language sql immutable set search_path = public, pg_temp
as $$
  select case
    when p_operation ->> 'kind' = 'delete' then coalesce(p_operation ->> 'itemId', p_item_id)
    when p_operation ->> 'kind' in ('create', 'replace') then coalesce(p_operation -> 'item' ->> 'id', p_item_id)
    else coalesce(p_operation -> 'item' ->> 'id', p_operation ->> 'itemId', p_item_id)
  end;
$$;

-- Authoritative cloud state for a rejected operation: the current row or tombstone for
-- its target item, plus the live item that owns the payload configuration, if another one
-- does. Read from the database only — never from client input.
create function public._tcg_conflict_cloud_state(p_collection_id uuid, p_item_id text, p_operation jsonb)
  returns jsonb
  language sql stable set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'itemId', target.item_id,
    'cloudItem', public._tcg_item_snapshot(p_collection_id, target.item_id),
    'conflictingItem', public._tcg_item_snapshot(
      p_collection_id,
      public._tcg_find_config_owner(
        p_collection_id,
        case when jsonb_typeof(p_operation -> 'item') = 'object' then p_operation -> 'item' end,
        target.item_id))
  )
  from (select public._tcg_push_target_item(p_item_id, p_operation) as item_id) target;
$$;

-- Records (or refreshes) the single open conflict row for an operation and returns its
-- durable id together with the snapshot the client must compare against. Rows recorded
-- here are `push`-source rows: the push protocol owns their visibility and their close.
-- Operations without a valid operation id reuse the open row with the same payload, so
-- malformed retries cannot grow the open set without bound.
create function public._tcg_record_push_conflict(
  p_collection_id uuid,
  p_op_id uuid,
  p_device_id text,
  p_item_id text,
  p_reason text,
  p_operation jsonb,
  p_owner uuid
) returns jsonb
  language plpgsql set search_path = public, pg_temp
as $$
declare
  v_state jsonb;
  v_high bigint;
  v_id uuid;
begin
  v_state := public._tcg_conflict_cloud_state(p_collection_id, p_item_id, p_operation);
  select remote_sequence into v_high from public.collections where id = p_collection_id;

  if p_op_id is null then
    insert into public.sync_conflicts
      (collection_id, op_id, device_id, item_id, reason, payload, owner, source,
       cloud_item, conflicting_item, observed_high_water, refreshed_at)
    values
      (p_collection_id, null, p_device_id, p_item_id, p_reason, p_operation, p_owner, 'push',
       v_state -> 'cloudItem', v_state -> 'conflictingItem', v_high, now())
    on conflict (collection_id, payload) where resolved_at is null and op_id is null and payload is not null
    do update set
      device_id = excluded.device_id,
      item_id = excluded.item_id,
      reason = excluded.reason,
      source = 'push',
      cloud_item = excluded.cloud_item,
      conflicting_item = excluded.conflicting_item,
      observed_high_water = excluded.observed_high_water,
      refreshed_at = now()
    returning id into v_id;
  else
    insert into public.sync_conflicts
      (collection_id, op_id, device_id, item_id, reason, payload, owner, source,
       cloud_item, conflicting_item, observed_high_water, refreshed_at)
    values
      (p_collection_id, p_op_id, p_device_id, p_item_id, p_reason, p_operation, p_owner, 'push',
       v_state -> 'cloudItem', v_state -> 'conflictingItem', v_high, now())
    on conflict (collection_id, op_id) where resolved_at is null and op_id is not null
    do update set
      device_id = excluded.device_id,
      item_id = excluded.item_id,
      reason = excluded.reason,
      payload = excluded.payload,
      source = 'push',
      cloud_item = excluded.cloud_item,
      conflicting_item = excluded.conflicting_item,
      observed_high_water = excluded.observed_high_water,
      refreshed_at = now()
    returning id into v_id;
  end if;

  return jsonb_build_object(
    'conflictId', v_id,
    'cloudItem', v_state -> 'cloudItem',
    'conflictingItem', v_state -> 'conflictingItem',
    'highWater', v_high
  );
end;
$$;

create function public._tcg_push_outcome(
  p_op_id text,
  p_status text,
  p_reason text,
  p_operation jsonb,
  p_conflict jsonb
) returns jsonb
  language sql immutable set search_path = public, pg_temp
as $$
  select jsonb_build_object(
    'opId', p_op_id,
    'status', p_status,
    'reason', p_reason,
    'revision', null,
    'conflictId', p_conflict -> 'conflictId',
    'rejectedOperation', p_operation,
    'cloudItem', p_conflict -> 'cloudItem',
    'conflictingItem', p_conflict -> 'conflictingItem',
    'highWater', p_conflict -> 'highWater'
  );
$$;

create or replace function public.push_collection_operations(p_device_id text, p_operations jsonb)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_cid uuid;
  v_count int;
  v_op jsonb;
  v_op_id text;
  v_op_uuid uuid;
  v_sig text;
  v_kind text;
  v_item jsonb;
  v_item_id text;
  v_base_text text;
  v_base bigint;
  v_reason text;
  v_deleted boolean;
  v_rev bigint;
  v_seq bigint;
  v_prev_sig text;
  v_rec jsonb;
  v_results jsonb := '[]'::jsonb;
  i int;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;
  if p_device_id is null or p_device_id !~ '^[a-zA-Z0-9-]{8,80}$' then
    raise exception 'invalid-device';
  end if;
  if p_operations is null or jsonb_typeof(p_operations) <> 'array' then
    raise exception 'invalid-request';
  end if;
  v_count := jsonb_array_length(p_operations);
  if v_count < 1 or v_count > 100 then
    raise exception 'invalid-request';
  end if;

  v_cid := public._tcg_ensure_collection(v_owner);

  if exists (
    select 1 from public.sync_devices
    where collection_id = v_cid and device_id = p_device_id and revoked_at is not null
  ) then
    return jsonb_build_object(
      'status', 'device-revoked',
      'results', '[]'::jsonb,
      'highWater', (select remote_sequence from public.collections where id = v_cid)
    );
  end if;

  insert into public.sync_devices (collection_id, device_id)
  values (v_cid, p_device_id)
  on conflict (collection_id, device_id) do update set last_seen_at = now();

  for i in 0 .. v_count - 1 loop
    v_op := p_operations -> i;
    v_op_id := v_op->>'opId';
    v_sig := v_op->>'signature';
    v_kind := v_op->>'kind';

    if v_sig is null or length(v_sig) > 512 then
      raise exception 'invalid-signature';
    end if;

    if v_op_id is null or v_op_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      v_rec := public._tcg_record_push_conflict(v_cid, null, p_device_id, null, 'invalid-payload', v_op, v_owner);
      v_results := v_results || public._tcg_push_outcome(v_op_id, 'rejected', 'invalid-payload', v_op, v_rec);
      continue;
    end if;
    v_op_uuid := v_op_id::uuid;

    select signature into v_prev_sig
    from public.collection_operations
    where collection_id = v_cid and op_id = v_op_uuid;
    if found then
      if v_prev_sig = v_sig then
        select revision into v_rev from public.collection_items
        where collection_id = v_cid and item_id = v_op->'item'->>'id';
        if v_rev is null then
          select revision into v_rev from public.collection_items
          where collection_id = v_cid and item_id = v_op->>'itemId';
        end if;
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'already-applied', 'reason', null, 'revision', v_rev);
      else
        v_rec := public._tcg_record_push_conflict(
          v_cid, v_op_uuid, p_device_id,
          public._tcg_push_target_item(null, v_op),
          'id-collision', v_op, v_owner);
        v_results := v_results || public._tcg_push_outcome(v_op_id, 'conflict', 'id-collision', v_op, v_rec);
      end if;
      continue;
    end if;

    if v_kind = 'delete' then
      v_item_id := v_op->>'itemId';
    elsif v_kind = 'create' or v_kind = 'replace' then
      v_item := v_op->'item';
      v_item_id := v_item->>'id';
    else
      v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, null, 'invalid-payload', v_op, v_owner);
      v_results := v_results || public._tcg_push_outcome(v_op_id, 'rejected', 'invalid-payload', v_op, v_rec);
      continue;
    end if;

    -- A delete addresses exactly one item through `itemId`. A nested `item` object on a
    -- delete is a crafted payload: reject it as a permanent error instead of letting its
    -- second id steer any snapshot, resolution, or tombstone.
    if v_kind = 'delete' and jsonb_typeof(v_op->'item') = 'object' then
      v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, v_item_id, 'invalid-payload', v_op, v_owner);
      v_results := v_results || public._tcg_push_outcome(v_op_id, 'rejected', 'invalid-payload', v_op, v_rec);
      continue;
    end if;

    if v_item_id is null or v_item_id !~ '^[a-zA-Z0-9-]{8,80}$' then
      v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, null, 'invalid-payload', v_op, v_owner);
      v_results := v_results || public._tcg_push_outcome(v_op_id, 'rejected', 'invalid-payload', v_op, v_rec);
      continue;
    end if;

    v_base := null;
    if v_kind = 'replace' or v_kind = 'delete' then
      v_base_text := v_op->>'baseRevision';
      if v_base_text is null or v_base_text !~ '^[0-9]{1,15}$' or v_base_text::numeric < 1 or v_base_text::numeric > 9007199254740991 then
        v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, v_item_id, 'invalid-payload', v_op, v_owner);
        v_results := v_results || public._tcg_push_outcome(v_op_id, 'rejected', 'invalid-payload', v_op, v_rec);
        continue;
      end if;
      v_base := v_base_text::bigint;
    end if;

    if v_kind = 'create' or v_kind = 'replace' then
      v_reason := public._tcg_validate_item(v_item, v_item_id);
      if v_reason is not null then
        v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || public._tcg_push_outcome(v_op_id, 'rejected', v_reason, v_op, v_rec);
        continue;
      end if;
    end if;

    if v_kind = 'create' then
      select revision, deleted into v_rev, v_deleted
      from public.collection_items
      where collection_id = v_cid and item_id = v_item_id;
      if found and v_deleted = false then
        v_reason := 'stale';
      elsif found then
        v_reason := 'deleted';
      else
        v_reason := null;
      end if;

      if v_reason is null and exists (
        select 1 from public.collection_items
        where collection_id = v_cid and not deleted
          and card_id = v_item->>'cardId' and variant = v_item->>'variant'
          and condition = v_item->>'condition' and item_id <> v_item_id
      ) then
        v_reason := 'duplicate-configuration';
      end if;

      if v_reason is not null then
        v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || public._tcg_push_outcome(v_op_id, 'conflict', v_reason, v_op, v_rec);
        continue;
      end if;

      update public.collections set remote_sequence = remote_sequence + 1
      where id = v_cid returning remote_sequence into v_seq;
      insert into public.collection_operations (collection_id, op_id, server_sequence, device_id, item_id, kind, base_revision, signature, payload, owner)
      values (v_cid, v_op_uuid, v_seq, p_device_id, v_item_id, 'create', null, v_sig, v_item, v_owner);
      insert into public.collection_items (collection_id, item_id, card_id, variant, condition, quantity, price_paid_amount, price_paid_currency, added_at, revision, deleted)
      values (v_cid, v_item_id, v_item->>'cardId', v_item->>'variant', v_item->>'condition',
              (v_item->>'quantity')::bigint,
              case when v_item->'pricePaid' is null or jsonb_typeof(v_item->'pricePaid') = 'null' then null else (v_item#>>'{pricePaid,amount}')::bigint end,
              case when v_item->'pricePaid' is null or jsonb_typeof(v_item->'pricePaid') = 'null' then null else v_item#>>'{pricePaid,currency}' end,
              (v_item->>'addedAt')::bigint, 1, false);
      v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'applied', 'reason', null, 'revision', 1);

    elsif v_kind = 'replace' then
      select revision, deleted into v_rev, v_deleted
      from public.collection_items
      where collection_id = v_cid and item_id = v_item_id
      for update;

      if not found then
        v_reason := 'missing';
      elsif v_deleted then
        v_reason := 'deleted';
      elsif v_rev <> v_base then
        v_reason := 'stale';
      elsif exists (
        select 1 from public.collection_items
        where collection_id = v_cid and not deleted
          and card_id = v_item->>'cardId' and variant = v_item->>'variant'
          and condition = v_item->>'condition' and item_id <> v_item_id
      ) then
        v_reason := 'duplicate-configuration';
      elsif v_rev >= 9007199254740991 then
        v_reason := 'revision-limit';
      else
        v_reason := null;
      end if;

      if v_reason is not null then
        v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || public._tcg_push_outcome(v_op_id, 'conflict', v_reason, v_op, v_rec);
        continue;
      end if;

      update public.collections set remote_sequence = remote_sequence + 1
      where id = v_cid returning remote_sequence into v_seq;
      insert into public.collection_operations (collection_id, op_id, server_sequence, device_id, item_id, kind, base_revision, signature, payload, owner)
      values (v_cid, v_op_uuid, v_seq, p_device_id, v_item_id, 'replace', v_base, v_sig, v_item, v_owner);
      update public.collection_items
      set card_id = v_item->>'cardId',
          variant = v_item->>'variant',
          condition = v_item->>'condition',
          quantity = (v_item->>'quantity')::bigint,
          price_paid_amount = case when v_item->'pricePaid' is null or jsonb_typeof(v_item->'pricePaid') = 'null' then null else (v_item#>>'{pricePaid,amount}')::bigint end,
          price_paid_currency = case when v_item->'pricePaid' is null or jsonb_typeof(v_item->'pricePaid') = 'null' then null else v_item#>>'{pricePaid,currency}' end,
          added_at = (v_item->>'addedAt')::bigint,
          revision = v_rev + 1,
          updated_at = now()
      where collection_id = v_cid and item_id = v_item_id;
      v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'applied', 'reason', null, 'revision', v_rev + 1);

    else -- delete
      select revision, deleted into v_rev, v_deleted
      from public.collection_items
      where collection_id = v_cid and item_id = v_item_id
      for update;

      if not found then
        v_reason := 'missing';
      elsif v_deleted then
        v_reason := 'deleted';
      elsif v_rev <> v_base then
        v_reason := 'stale';
      elsif v_rev >= 9007199254740991 then
        v_reason := 'revision-limit';
      else
        v_reason := null;
      end if;

      if v_reason is not null then
        v_rec := public._tcg_record_push_conflict(v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || public._tcg_push_outcome(v_op_id, 'conflict', v_reason, v_op, v_rec);
        continue;
      end if;

      update public.collections set remote_sequence = remote_sequence + 1
      where id = v_cid returning remote_sequence into v_seq;
      insert into public.collection_operations (collection_id, op_id, server_sequence, device_id, item_id, kind, base_revision, signature, payload, owner)
      values (v_cid, v_op_uuid, v_seq, p_device_id, v_item_id, 'delete', v_base, v_sig, null, v_owner);
      update public.collection_items
      set deleted = true, revision = v_rev + 1, updated_at = now()
      where collection_id = v_cid and item_id = v_item_id;
      v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'applied', 'reason', null, 'revision', v_rev + 1);
    end if;
  end loop;

  return jsonb_build_object(
    'status', 'ok',
    'results', v_results,
    'highWater', (select remote_sequence from public.collections where id = v_cid)
  );
end;
$$;

create function public.read_collection_push_conflicts()
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_state jsonb;
  v_result jsonb;
  r record;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;

  -- Refresh every snapshot at read time: the collection may have moved on since the
  -- conflict was recorded, and the refreshed row is what a later resolution compares
  -- against. The high water stored here is a snapshot label, never a pull cursor.
  for r in
    select sc.id, sc.collection_id, sc.item_id, sc.payload, c.remote_sequence
      from public.sync_conflicts sc
      join public.collections c on c.id = sc.collection_id
     where sc.owner = v_owner and sc.resolved_at is null
     order by sc.created_at, sc.id
  loop
    v_state := public._tcg_conflict_cloud_state(r.collection_id, r.item_id, r.payload);
    update public.sync_conflicts
       set cloud_item = v_state -> 'cloudItem',
           conflicting_item = v_state -> 'conflictingItem',
           observed_high_water = r.remote_sequence,
           refreshed_at = now()
     where id = r.id;
  end loop;

  select coalesce(jsonb_agg(jsonb_build_object(
    'id', sc.id,
    'opId', sc.op_id,
    'itemId', public._tcg_push_target_item(sc.item_id, sc.payload),
    'reason', sc.reason,
    'deviceId', sc.device_id,
    'rejectedOperation', sc.payload,
    'cloudItem', sc.cloud_item,
    'conflictingItem', sc.conflicting_item,
    'highWater', sc.observed_high_water,
    'resolution', case
      when sc.resolution_id is null then null
      else jsonb_build_object(
        'id', sc.resolution_id,
        'choice', sc.resolution_choice,
        'result', sc.resolution_result,
        'deviceId', sc.resolution_device_id)
    end,
    'createdAt', sc.created_at
  ) order by sc.created_at, sc.id), '[]'::jsonb)
  into v_result
  from public.sync_conflicts sc
  where sc.owner = v_owner and sc.resolved_at is null;

  return v_result;
end;
$$;

create function public.resolve_collection_push_conflict(p_conflict_id uuid, p_choice text, p_device_id text)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_row record;
  v_lock_id text;
  v_target text;
  v_duplicate_owner text;
  v_apply_id text;
  v_state jsonb;
  v_cloud jsonb;
  v_conflicting jsonb;
  v_high bigint;
  v_item jsonb;
  v_item_row record;
  v_item_exists boolean;
  v_signature text;
  v_resolution_id uuid;
  v_result jsonb;
  v_op_id uuid;
  v_op_kind text;
  v_base bigint;
  v_revision bigint;
  v_seq bigint;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;
  if p_choice is null or p_choice not in ('keep-local', 'keep-cloud') then
    return jsonb_build_object('status', 'invalid-resolution', 'conflictId', p_conflict_id);
  end if;
  if p_device_id is null or p_device_id !~ '^[a-zA-Z0-9-]{8,80}$' then
    raise exception 'invalid-device';
  end if;

  -- Serialize every resolution on the conflict row before touching collection state.
  select * into v_row
    from public.sync_conflicts
   where id = p_conflict_id and owner = v_owner
   for update;
  if not found then
    return jsonb_build_object('status', 'not-found', 'conflictId', p_conflict_id);
  end if;

  if v_row.resolved_at is not null then
    return jsonb_build_object(
      'status', 'already-resolved',
      'conflictId', v_row.id,
      'choice', v_row.resolution_choice,
      'resolutionId', v_row.resolution_id,
      'result', v_row.resolution_result);
  end if;

  if v_row.resolution_id is not null then
    if v_row.resolution_choice = p_choice then
      return jsonb_build_object(
        'status', 'resolved',
        'conflictId', v_row.id,
        'choice', v_row.resolution_choice,
        'resolutionId', v_row.resolution_id,
        'result', v_row.resolution_result);
    end if;
    return jsonb_build_object(
      'status', 'already-resolved',
      'conflictId', v_row.id,
      'choice', v_row.resolution_choice,
      'resolutionId', v_row.resolution_id,
      'result', v_row.resolution_result);
  end if;

  if exists (
    select 1 from public.sync_devices
     where collection_id = v_row.collection_id and device_id = p_device_id
       and revoked_at is not null
  ) then
    return jsonb_build_object('status', 'device-revoked', 'conflictId', v_row.id);
  end if;

  -- Permanent errors never expose a resolvable choice.
  if v_row.reason = 'invalid-payload'
     or v_row.reason = 'device-revoked'
     or (v_row.reason = 'revision-limit' and p_choice = 'keep-local') then
    return jsonb_build_object(
      'status', 'invalid-resolution',
      'conflictId', v_row.id,
      'reason', v_row.reason);
  end if;

  v_signature := coalesce(v_row.payload ->> 'signature', v_row.id::text);
  v_item := v_row.payload -> 'item';
  v_target := public._tcg_push_target_item(v_row.item_id, v_row.payload);

  -- A delete may only ever address its `itemId`: a forbidden nested `item` object means
  -- the payload carries a second id, so no choice may be applied from it.
  if v_row.payload ->> 'kind' = 'delete' and jsonb_typeof(v_item) = 'object' then
    return jsonb_build_object(
      'status', 'invalid-resolution',
      'conflictId', v_row.id,
      'reason', 'invalid-payload');
  end if;

  -- The target is validated at the boundary: an id-collision may retain an item id that
  -- never passed the payload checks, and it must surface as a typed refusal rather than
  -- a database CHECK violation mid-apply.
  if v_target is null or v_target !~ '^[a-zA-Z0-9-]{8,80}$' then
    return jsonb_build_object(
      'status', 'invalid-resolution',
      'conflictId', v_row.id,
      'reason', 'invalid-payload');
  end if;

  v_duplicate_owner := null;
  if v_row.reason = 'duplicate-configuration' and jsonb_typeof(v_item) = 'object' then
    v_duplicate_owner := public._tcg_find_config_owner(v_row.collection_id, v_item, v_target);
  end if;

  -- Lock the rows a choice may rewrite, in stable order, while the conflict row lock is
  -- already held. Snapshot state is only read after these locks exist.
  for v_lock_id in
    select lock_id
      from unnest(array[v_target, v_duplicate_owner]) as u (lock_id)
     where lock_id is not null
     order by lock_id
  loop
    perform 1 from public.collection_items
     where collection_id = v_row.collection_id and item_id = v_lock_id
       for update;
  end loop;

  v_state := public._tcg_conflict_cloud_state(v_row.collection_id, v_row.item_id, v_row.payload);
  v_cloud := v_state -> 'cloudItem';
  v_conflicting := v_state -> 'conflictingItem';
  select remote_sequence into v_high from public.collections where id = v_row.collection_id;

  -- The recorded comparison must still describe reality, otherwise the person is asked
  -- again against the refreshed snapshot returned here.
  if v_cloud is distinct from v_row.cloud_item
     or v_conflicting is distinct from v_row.conflicting_item then
    update public.sync_conflicts
       set cloud_item = v_cloud,
           conflicting_item = v_conflicting,
           observed_high_water = v_high,
           refreshed_at = now()
     where id = v_row.id;
    return jsonb_build_object(
      'status', 'stale-conflict',
      'conflictId', v_row.id,
      'cloudItem', v_cloud,
      'conflictingItem', v_conflicting,
      'highWater', v_high);
  end if;

  if p_choice = 'keep-cloud' then
    -- Cloud is already authoritative: record the choice for local application without
    -- mutating any server state or sequence.
    v_result := jsonb_build_object(
      'kind', 'keep-cloud',
      'operationId', null,
      'itemId', v_target,
      'localItemId', v_target,
      'conflictingItemId', v_conflicting ->> 'itemId',
      'cloudItem', v_cloud,
      'conflictingItem', v_conflicting);
  else
    if v_row.payload ->> 'kind' is null
       or v_row.payload ->> 'kind' not in ('create', 'replace', 'delete')
       or (v_row.payload ->> 'kind' <> 'delete' and jsonb_typeof(v_item) is distinct from 'object') then
      return jsonb_build_object(
        'status', 'invalid-resolution',
        'conflictId', v_row.id,
        'reason', v_row.reason);
    end if;
    if v_row.payload ->> 'kind' <> 'delete'
       and public._tcg_validate_item(v_item, v_item ->> 'id') is not null then
      return jsonb_build_object(
        'status', 'invalid-resolution',
        'conflictId', v_row.id,
        'reason', 'invalid-payload');
    end if;

    -- A duplicate-configuration keep-local adopts the existing cloud item id.
    v_apply_id := v_target;
    if v_row.reason = 'duplicate-configuration' then
      v_apply_id := v_duplicate_owner;
    end if;
    if v_apply_id is null then
      return jsonb_build_object(
        'status', 'invalid-resolution',
        'conflictId', v_row.id,
        'reason', v_row.reason);
    end if;

    if v_row.payload ->> 'kind' = 'delete' then
      select * into v_item_row
        from public.collection_items
       where collection_id = v_row.collection_id and item_id = v_apply_id;
      v_item_exists := found;

      if (not v_item_exists) or v_item_row.deleted then
        -- The cloud already lacks the item: the local choice already holds.
        v_result := jsonb_build_object(
          'kind', 'keep-local',
          'operationId', null,
          'itemId', v_apply_id,
          'localItemId', v_target,
          'conflictingItemId', v_conflicting ->> 'itemId',
          'operation', null,
          'cloudItem', v_cloud,
          'revision', case when v_item_exists then v_item_row.revision else null end);
      elsif v_item_row.revision >= 9007199254740991 then
        return jsonb_build_object(
          'status', 'invalid-resolution',
          'conflictId', v_row.id,
          'reason', 'revision-limit');
      else
        v_base := v_item_row.revision;
        update public.collections set remote_sequence = remote_sequence + 1
         where id = v_row.collection_id returning remote_sequence into v_seq;
        v_op_id := gen_random_uuid();
        insert into public.collection_operations
          (collection_id, op_id, server_sequence, device_id, item_id, kind, base_revision, signature, payload, owner)
        values
          (v_row.collection_id, v_op_id, v_seq, p_device_id, v_apply_id, 'delete', v_base, v_signature, null, v_owner);
        update public.collection_items
           set deleted = true, revision = v_item_row.revision + 1, updated_at = now()
         where collection_id = v_row.collection_id and item_id = v_apply_id
         returning revision into v_revision;
        v_result := jsonb_build_object(
          'kind', 'keep-local',
          'operationId', v_op_id,
          'itemId', v_apply_id,
          'localItemId', v_target,
          'conflictingItemId', v_conflicting ->> 'itemId',
          'operation', jsonb_build_object(
            'opId', v_op_id,
            'kind', 'delete',
            'itemId', v_apply_id,
            'baseRevision', v_base,
            'item', null),
          'cloudItem', v_cloud,
          'revision', v_revision);
      end if;
    else
      select * into v_item_row
        from public.collection_items
       where collection_id = v_row.collection_id and item_id = v_apply_id;
      v_item_exists := found;

      -- The unique active configuration rule is enforced here, from the database: if a
      -- third live item already owns this configuration, the recorded comparison is no
      -- longer applicable and no second choice may be applied.
      if public._tcg_find_config_owner(v_row.collection_id, v_item, v_apply_id) is not null then
        return jsonb_build_object(
          'status', 'invalid-resolution',
          'conflictId', v_row.id,
          'reason', 'duplicate-configuration');
      end if;

      if v_item_exists then
        if v_item_row.revision >= 9007199254740991 then
          return jsonb_build_object(
            'status', 'invalid-resolution',
            'conflictId', v_row.id,
            'reason', 'revision-limit');
        end if;
        v_base := v_item_row.revision;
        update public.collection_items
           set card_id = v_item ->> 'cardId',
               variant = v_item ->> 'variant',
               condition = v_item ->> 'condition',
               quantity = (v_item ->> 'quantity')::bigint,
               price_paid_amount = case
                 when v_item -> 'pricePaid' is null or jsonb_typeof(v_item -> 'pricePaid') = 'null' then null
                 else (v_item #>> '{pricePaid,amount}')::bigint end,
               price_paid_currency = case
                 when v_item -> 'pricePaid' is null or jsonb_typeof(v_item -> 'pricePaid') = 'null' then null
                 else v_item #>> '{pricePaid,currency}' end,
               added_at = (v_item ->> 'addedAt')::bigint,
               deleted = false,
               revision = v_item_row.revision + 1,
               updated_at = now()
         where collection_id = v_row.collection_id and item_id = v_apply_id
         returning revision into v_revision;
        v_op_kind := 'replace';
        -- A create rejected because the row already exists replays as a replace.
      else
        insert into public.collection_items
          (collection_id, item_id, card_id, variant, condition, quantity,
           price_paid_amount, price_paid_currency, added_at, revision, deleted)
        values
          (v_row.collection_id, v_apply_id, v_item ->> 'cardId', v_item ->> 'variant', v_item ->> 'condition',
           (v_item ->> 'quantity')::bigint,
           case
             when v_item -> 'pricePaid' is null or jsonb_typeof(v_item -> 'pricePaid') = 'null' then null
             else (v_item #>> '{pricePaid,amount}')::bigint end,
           case
             when v_item -> 'pricePaid' is null or jsonb_typeof(v_item -> 'pricePaid') = 'null' then null
             else v_item #>> '{pricePaid,currency}' end,
           (v_item ->> 'addedAt')::bigint, 1, false)
        returning revision into v_revision;
        v_base := null;
        v_op_kind := 'create';
      end if;

      update public.collections set remote_sequence = remote_sequence + 1
       where id = v_row.collection_id returning remote_sequence into v_seq;
      v_op_id := gen_random_uuid();
      insert into public.collection_operations
        (collection_id, op_id, server_sequence, device_id, item_id, kind, base_revision, signature, payload, owner)
      values
        (v_row.collection_id, v_op_id, v_seq, p_device_id, v_apply_id, v_op_kind, v_base, v_signature, v_item, v_owner);

      v_result := jsonb_build_object(
        'kind', 'keep-local',
        'operationId', v_op_id,
        'itemId', v_apply_id,
        'localItemId', v_target,
        'conflictingItemId', v_conflicting ->> 'itemId',
        'operation', jsonb_build_object(
          'opId', v_op_id,
          'kind', v_op_kind,
          'itemId', v_apply_id,
          'baseRevision', v_base,
          'item', v_item),
        'cloudItem', v_cloud,
        'revision', v_revision);
    end if;
  end if;

  -- Record the immutable receipt before returning it. Replay until acknowledgment.
  v_resolution_id := gen_random_uuid();
  update public.sync_conflicts
     set resolution_id = v_resolution_id,
         resolution_choice = p_choice,
         resolution_result = v_result,
         resolution_device_id = p_device_id
   where id = v_row.id;

  return jsonb_build_object(
    'status', 'resolved',
    'conflictId', v_row.id,
    'choice', p_choice,
    'resolutionId', v_resolution_id,
    'result', v_result);
end;
$$;

create function public.ack_collection_push_conflict_resolution(p_conflict_id uuid, p_resolution_id uuid)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_row record;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;

  select * into v_row
    from public.sync_conflicts
   where id = p_conflict_id and owner = v_owner
   for update;
  if not found then
    return jsonb_build_object('status', 'not-found', 'conflictId', p_conflict_id);
  end if;

  if p_resolution_id is null or v_row.resolution_id is null
     or v_row.resolution_id <> p_resolution_id then
    return jsonb_build_object(
      'status', 'stale-conflict',
      'conflictId', v_row.id,
      'resolutionId', v_row.resolution_id,
      'choice', v_row.resolution_choice);
  end if;

  if v_row.resolved_at is null then
    update public.sync_conflicts set resolved_at = now() where id = v_row.id;
  end if;

  return jsonb_build_object(
    'status', 'resolved',
    'conflictId', v_row.id,
    'resolutionId', v_row.resolution_id,
    'choice', v_row.resolution_choice);
end;
$$;

-- Legacy pull-conflict RPCs, fenced from push-protocol rows. The legacy read only ever
-- lists `legacy` rows; on a `push` row the legacy resolver mirrors its historical
-- idempotent statuses but never closes the row, so an unacknowledged receipt stays
-- replayable and the push protocol alone owns the close. Legacy rows behave exactly as
-- before.
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
    and conflict.resolved_at is null
    and conflict.source = 'legacy';

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

  if exists (
    select 1 from public.sync_conflicts
     where id = p_conflict_id and owner = v_owner and source = 'push'
  ) then
    -- Mirror the historical statuses without ever setting resolved_at: the push
    -- protocol owns visibility and close for this row. The write is fenced to open,
    -- not-yet-marked rows, so acknowledged receipts are terminal (`already-resolved`)
    -- and no legacy marker is ever written onto them.
    update public.sync_conflicts
       set resolution = p_resolution
     where id = p_conflict_id and owner = v_owner
       and source = 'push' and resolved_at is null and resolution is null;
    if found then
      return jsonb_build_object('status', 'resolved');
    end if;
    return jsonb_build_object('status', 'already-resolved');
  end if;

  -- The close itself is fenced to `legacy` rows: if a concurrent push retry flips this
  -- row's source between the check above and this statement, the predicate refuses the
  -- write instead of closing a push-protocol row (TOCTOU).
  update public.sync_conflicts
  set resolved_at = now(), resolution = p_resolution
  where id = p_conflict_id and owner = v_owner and resolved_at is null and source = 'legacy';
  if found then
    return jsonb_build_object('status', 'resolved');
  end if;

  if exists (select 1 from public.sync_conflicts where id = p_conflict_id and owner = v_owner) then
    return jsonb_build_object('status', 'already-resolved');
  end if;
  return jsonb_build_object('status', 'not-found');
end;
$$;

revoke execute on function public.push_collection_operations(text, jsonb) from public, anon;
grant execute on function public.push_collection_operations(text, jsonb) to authenticated;

revoke execute on function public._tcg_item_snapshot(uuid, text) from public, anon, authenticated;
revoke execute on function public._tcg_find_config_owner(uuid, jsonb, text) from public, anon, authenticated;
revoke execute on function public._tcg_push_target_item(text, jsonb) from public, anon, authenticated;
revoke execute on function public._tcg_conflict_cloud_state(uuid, text, jsonb) from public, anon, authenticated;
revoke execute on function public._tcg_record_push_conflict(uuid, uuid, text, text, text, jsonb, uuid) from public, anon, authenticated;
revoke execute on function public._tcg_push_outcome(text, text, text, jsonb, jsonb) from public, anon, authenticated;

revoke execute on function public.read_collection_push_conflicts() from public, anon;
grant execute on function public.read_collection_push_conflicts() to authenticated;
revoke execute on function public.resolve_collection_push_conflict(uuid, text, text) from public, anon;
grant execute on function public.resolve_collection_push_conflict(uuid, text, text) to authenticated;
revoke execute on function public.ack_collection_push_conflict_resolution(uuid, uuid) from public, anon;
grant execute on function public.ack_collection_push_conflict_resolution(uuid, uuid) to authenticated;
