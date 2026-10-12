-- Returns a bounded-by-collection snapshot only to the authenticated owner.
-- The expected high-water mark prevents applying a snapshot that raced the link preview.
create function public.read_collection_link_snapshot(p_expected_high_water bigint)
  returns jsonb
  language plpgsql security definer set search_path = public, pg_temp
as $$
declare
  v_owner uuid := auth.uid();
  v_collection_id uuid;
  v_high_water bigint;
  v_items jsonb;
begin
  if v_owner is null then
    raise exception 'not authenticated';
  end if;

  select id, remote_sequence
  into v_collection_id, v_high_water
  from public.collections
  where owner = v_owner;

  if not found then
    if p_expected_high_water is not null and p_expected_high_water <> 0 then
      raise exception 'stale-preview';
    end if;
    return jsonb_build_object('highWater', 0, 'items', '[]'::jsonb);
  end if;

  if p_expected_high_water is not null and p_expected_high_water <> v_high_water then
    raise exception 'stale-preview';
  end if;

  select coalesce(jsonb_agg(
    jsonb_build_object(
      'id', item_id,
      'cardId', card_id,
      'quantity', quantity,
      'variant', variant,
      'condition', condition,
      'pricePaid', case when price_paid_amount is null then null
                        else jsonb_build_object('amount', price_paid_amount, 'currency', price_paid_currency) end,
      'addedAt', added_at,
      'revision', revision
    ) order by item_id), '[]'::jsonb)
  into v_items
  from public.collection_items
  where collection_id = v_collection_id and not deleted;

  return jsonb_build_object('highWater', v_high_water, 'items', v_items);
end;
$$;

revoke execute on function public.read_collection_link_snapshot(bigint) from public, anon;
grant execute on function public.read_collection_link_snapshot(bigint) to authenticated;
