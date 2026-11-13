-- Read-through cache for Jornada Games quotes. The Edge Function writes with the
-- service role; clients only read. Money is stored in BRL cents.
create table public.jornada_card_map (
  card_id text primary key check (card_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}$'),
  public_id bigint,
  code text,
  set_code text,
  matched_by text check (matched_by is null or matched_by in ('code', 'set-number', 'name-set')),
  matched_at timestamptz,
  missed_at timestamptz,
  check ((public_id is null) <> (missed_at is null))
);

create table public.jornada_quotes (
  card_id text primary key,
  public_id bigint not null check (public_id > 0),
  code text,
  market_cents bigint check (market_cents is null or market_cents >= 0),
  market_kind text check (market_kind is null or market_kind in ('ask', 'reference')),
  liquidity jsonb,
  fetched_at timestamptz not null
);

create table public.jornada_skus (
  card_id text not null references public.jornada_quotes (card_id) on delete cascade,
  condition text not null,
  language text not null,
  finish text not null,
  lowest_cents bigint check (lowest_cents is null or lowest_cents >= 0),
  highest_cents bigint check (highest_cents is null or highest_cents >= 0),
  listings integer not null default 0 check (listings >= 0),
  fetched_at timestamptz not null,
  primary key (card_id, condition, language, finish)
);

create table public.jornada_quota (
  utc_day date primary key,
  market_calls integer not null default 0 check (market_calls >= 0),
  market_day_limit integer not null default 100 check (market_day_limit > 0),
  market_reset_at timestamptz,
  catalog_calls integer not null default 0 check (catalog_calls >= 0),
  catalog_day_limit integer not null default 300 check (catalog_day_limit > 0),
  catalog_reset_at timestamptz,
  updated_at timestamptz not null default now()
);

alter table public.jornada_card_map enable row level security;
alter table public.jornada_card_map force row level security;
alter table public.jornada_quotes enable row level security;
alter table public.jornada_quotes force row level security;
alter table public.jornada_skus enable row level security;
alter table public.jornada_skus force row level security;
alter table public.jornada_quota enable row level security;
alter table public.jornada_quota force row level security;

create policy jornada_card_map_read on public.jornada_card_map
  for select to anon, authenticated using (true);
create policy jornada_quotes_read on public.jornada_quotes
  for select to anon, authenticated using (true);
create policy jornada_skus_read on public.jornada_skus
  for select to anon, authenticated using (true);

grant usage on schema public to anon, authenticated;
grant select on public.jornada_card_map, public.jornada_quotes, public.jornada_skus to anon, authenticated;
