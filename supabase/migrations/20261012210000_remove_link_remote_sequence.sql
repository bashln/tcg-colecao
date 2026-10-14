do $$
declare
  v_definition text;
begin
  v_definition := pg_get_functiondef(
    'public._tcg_prepare_collection_link(text,jsonb,bigint)'::regprocedure
  );
  v_definition := replace(
    v_definition,
    'public._tcg_prepare_collection_link(p_local_digest text, p_local_items jsonb, p_remote_sequence bigint)',
    'public._tcg_prepare_collection_link(p_local_digest text, p_local_items jsonb)'
  );
  if v_definition = pg_get_functiondef(
    'public._tcg_prepare_collection_link(text,jsonb,bigint)'::regprocedure
  ) then
    raise exception 'Could not remove unused remote sequence from link preview implementation';
  end if;
  execute v_definition;
end;
$$;

create function public.prepare_collection_link(p_local_digest text, p_local_items jsonb)
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

  v_result := public._tcg_prepare_collection_link(p_local_digest, p_local_items);
  return v_result;
end;
$$;

revoke execute on function public.prepare_collection_link(text, jsonb, bigint) from public, anon, authenticated;
revoke execute on function public.prepare_collection_link(text, jsonb) from public, anon;
grant execute on function public.prepare_collection_link(text, jsonb) to authenticated;
revoke execute on function public._tcg_prepare_collection_link(text, jsonb) from public, anon, authenticated;
revoke execute on function public._tcg_prepare_collection_link(text, jsonb, bigint) from public, anon, authenticated;

drop function public.prepare_collection_link(text, jsonb, bigint);
drop function public._tcg_prepare_collection_link(text, jsonb, bigint);
