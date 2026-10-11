-- Operation-log RPCs. Clients never mutate tables directly: they call these narrow
-- SECURITY DEFINER functions, which derive ownership from auth.uid() only, validate
-- every payload at the database boundary, apply compare-and-swap per item, keep
-- tombstones, record explicit conflicts, and assign the per-collection pull cursor.

create function public._tcg_ensure_collection(p_owner uuid) returns uuid
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_id uuid;
begin
  select id into v_id from public.collections where owner = p_owner;
  if v_id is not null then
    return v_id;
  end if;
  begin
    insert into public.collections (owner) values (p_owner) returning id into v_id;
  exception when unique_violation then
    select id into v_id from public.collections where owner = p_owner;
  end;
  return v_id;
end;
$$;

-- Returns null when the payload is valid, otherwise a conflict reason.
create function public._tcg_validate_item(p_item jsonb, p_item_id text) returns text
  language plpgsql immutable set search_path = public, pg_temp
as $$
declare
  v_card text;
  v_variant text;
  v_condition text;
  v_qty text;
  v_added text;
  v_price jsonb;
  v_amount text;
  v_currency text;
begin
  if p_item is null or jsonb_typeof(p_item) <> 'object' then
    return 'invalid-payload';
  end if;
  if (p_item->>'id') is distinct from p_item_id then
    return 'invalid-payload';
  end if;
  v_card := p_item->>'cardId';
  if v_card is null or v_card !~ '^[a-zA-Z0-9-]{1,120}$' then
    return 'invalid-payload';
  end if;
  v_variant := p_item->>'variant';
  if v_variant is null or v_variant not in ('NORMAL', 'HOLOFOIL', 'REVERSE_HOLOFOIL', 'FIRST_EDITION', 'PROMO') then
    return 'invalid-payload';
  end if;
  v_condition := p_item->>'condition';
  if v_condition is null or v_condition not in ('NEAR_MINT', 'LIGHTLY_PLAYED', 'MODERATELY_PLAYED', 'HEAVILY_PLAYED', 'DAMAGED') then
    return 'invalid-payload';
  end if;
  v_qty := p_item->>'quantity';
  if v_qty is null or v_qty !~ '^[0-9]{1,10}$' or v_qty::numeric not between 1 and 100000 then
    return 'invalid-payload';
  end if;
  v_added := p_item->>'addedAt';
  if v_added is null or v_added !~ '^[0-9]{1,15}$' or v_added::numeric > 9007199254740991 then
    return 'invalid-payload';
  end if;
  v_price := p_item->'pricePaid';
  if v_price is not null and jsonb_typeof(v_price) <> 'null' then
    if jsonb_typeof(v_price) <> 'object' then
      return 'invalid-payload';
    end if;
    v_amount := v_price->>'amount';
    if v_amount is null or v_amount !~ '^[0-9]{1,10}$' or v_amount::numeric > 1000000000 then
      return 'invalid-payload';
    end if;
    v_currency := v_price->>'currency';
    if v_currency is null or v_currency not in ('USD', 'EUR', 'BRL', 'GBP', 'JPY') then
      return 'invalid-payload';
    end if;
  end if;
  return null;
end;
$$;

create function public.push_collection_operations(p_device_id text, p_operations jsonb)
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
      insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
      values (v_cid, null, p_device_id, null, 'invalid-payload', v_op, v_owner);
      v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'rejected', 'reason', 'invalid-payload', 'revision', null);
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
        insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
        values (v_cid, v_op_uuid, p_device_id, coalesce(v_op->'item'->>'id', v_op->>'itemId'), 'id-collision', v_op, v_owner);
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'conflict', 'reason', 'id-collision', 'revision', null);
      end if;
      continue;
    end if;

    if v_kind = 'delete' then
      v_item_id := v_op->>'itemId';
    elsif v_kind = 'create' or v_kind = 'replace' then
      v_item := v_op->'item';
      v_item_id := v_item->>'id';
    else
      insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
      values (v_cid, v_op_uuid, p_device_id, null, 'invalid-payload', v_op, v_owner);
      v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'rejected', 'reason', 'invalid-payload', 'revision', null);
      continue;
    end if;

    if v_item_id is null or v_item_id !~ '^[a-zA-Z0-9-]{8,80}$' then
      insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
      values (v_cid, v_op_uuid, p_device_id, null, 'invalid-payload', v_op, v_owner);
      v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'rejected', 'reason', 'invalid-payload', 'revision', null);
      continue;
    end if;

    v_base := null;
    if v_kind = 'replace' or v_kind = 'delete' then
      v_base_text := v_op->>'baseRevision';
      if v_base_text is null or v_base_text !~ '^[0-9]{1,15}$' or v_base_text::numeric < 1 or v_base_text::numeric > 9007199254740991 then
        insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
        values (v_cid, v_op_uuid, p_device_id, v_item_id, 'invalid-payload', v_op, v_owner);
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'rejected', 'reason', 'invalid-payload', 'revision', null);
        continue;
      end if;
      v_base := v_base_text::bigint;
    end if;

    if v_kind = 'create' or v_kind = 'replace' then
      v_reason := public._tcg_validate_item(v_item, v_item_id);
      if v_reason is not null then
        insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
        values (v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'rejected', 'reason', v_reason, 'revision', null);
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
        insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
        values (v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'conflict', 'reason', v_reason, 'revision', null);
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
        insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
        values (v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'conflict', 'reason', v_reason, 'revision', null);
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
        insert into public.sync_conflicts (collection_id, op_id, device_id, item_id, reason, payload, owner)
        values (v_cid, v_op_uuid, p_device_id, v_item_id, v_reason, v_op, v_owner);
        v_results := v_results || jsonb_build_object('opId', v_op_id, 'status', 'conflict', 'reason', v_reason, 'revision', null);
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

create function public.pull_collection_operations(p_after_sequence bigint, p_page_limit int)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_cid uuid;
  v_limit int;
  v_high bigint;
  v_ops jsonb := '[]'::jsonb;
  r record;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;
  v_cid := public._tcg_ensure_collection(v_owner);
  v_limit := least(greatest(coalesce(p_page_limit, 500), 1), 500);

  select remote_sequence into v_high from public.collections where id = v_cid;

  for r in
    select op_id, server_sequence, device_id, item_id, kind, base_revision, payload
    from public.collection_operations
    where collection_id = v_cid and server_sequence > greatest(coalesce(p_after_sequence, 0), 0)
    order by server_sequence asc
    limit v_limit
  loop
    v_ops := v_ops || jsonb_build_array(jsonb_build_object(
      'opId', r.op_id,
      'serverSequence', r.server_sequence,
      'deviceId', r.device_id,
      'itemId', r.item_id,
      'kind', r.kind,
      'baseRevision', r.base_revision,
      'item', r.payload
    ));
  end loop;

  return jsonb_build_object('operations', v_ops, 'highWater', v_high);
end;
$$;

revoke execute on function public._tcg_ensure_collection(uuid) from public, anon, authenticated;
revoke execute on function public._tcg_validate_item(jsonb, text) from public, anon, authenticated;
revoke execute on function public.push_collection_operations(text, jsonb) from public, anon;
revoke execute on function public.pull_collection_operations(bigint, int) from public, anon;
grant execute on function public.push_collection_operations(text, jsonb) to authenticated;
grant execute on function public.pull_collection_operations(bigint, int) to authenticated;
