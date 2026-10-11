-- Collection sync foundation: one collection per account, per-item revisions with
-- tombstones, an append-only operation log with per-collection pull cursor, devices,
-- conflicts and link plans. Clients hold SELECT grants only; every mutation will be a
-- narrow SECURITY DEFINER RPC added in a later migration. Owner always derives from
-- auth.uid(); no client-supplied owner id is trusted.

create table public.collections (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null default auth.uid(),
  remote_sequence bigint not null default 0,
  created_at timestamptz not null default now(),
  unique (owner)
);

create table public.collection_items (
  collection_id uuid not null references public.collections (id) on delete cascade,
  item_id text not null check (item_id ~ '^[a-zA-Z0-9-]{8,80}$'),
  card_id text not null check (card_id ~ '^[a-zA-Z0-9-]{1,120}$'),
  variant text not null check (variant in ('NORMAL', 'HOLOFOIL', 'REVERSE_HOLOFOIL', 'FIRST_EDITION', 'PROMO')),
  condition text not null check (condition in ('NEAR_MINT', 'LIGHTLY_PLAYED', 'MODERATELY_PLAYED', 'HEAVILY_PLAYED', 'DAMAGED')),
  quantity bigint not null check (quantity > 0 and quantity <= 100000),
  price_paid_amount bigint check (price_paid_amount is null or (price_paid_amount >= 0 and price_paid_amount <= 1000000000)),
  price_paid_currency text check (price_paid_currency is null or price_paid_currency in ('USD', 'EUR', 'BRL', 'GBP', 'JPY')),
  added_at bigint not null,
  revision bigint not null default 1 check (revision >= 1),
  deleted boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (collection_id, item_id),
  check ((price_paid_amount is null) = (price_paid_currency is null))
);

create unique index collection_items_active_configuration
  on public.collection_items (collection_id, card_id, variant, condition)
  where not deleted;

create table public.collection_operations (
  collection_id uuid not null references public.collections (id) on delete cascade,
  op_id uuid not null,
  server_sequence bigint not null,
  device_id text not null check (device_id ~ '^[a-zA-Z0-9-]{8,80}$'),
  item_id text not null check (item_id ~ '^[a-zA-Z0-9-]{8,80}$'),
  kind text not null check (kind in ('create', 'replace', 'delete')),
  base_revision bigint check (base_revision is null or base_revision >= 1),
  signature text not null,
  payload jsonb,
  owner uuid not null default auth.uid(),
  created_at timestamptz not null default now(),
  primary key (collection_id, server_sequence),
  unique (collection_id, op_id),
  check (kind = 'create' or base_revision is not null),
  check (kind <> 'create' or base_revision is null)
);

create index collection_operations_by_item
  on public.collection_operations (collection_id, item_id, server_sequence);

create table public.sync_devices (
  collection_id uuid not null references public.collections (id) on delete cascade,
  device_id text not null check (device_id ~ '^[a-zA-Z0-9-]{8,80}$'),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz,
  primary key (collection_id, device_id)
);

create table public.sync_conflicts (
  id uuid primary key default gen_random_uuid(),
  collection_id uuid not null references public.collections (id) on delete cascade,
  op_id uuid,
  device_id text,
  item_id text,
  reason text not null check (reason in ('stale', 'duplicate-configuration', 'deleted', 'missing', 'id-collision', 'revision-limit', 'invalid-payload', 'device-revoked')),
  payload jsonb,
  owner uuid not null default auth.uid(),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

create index sync_conflicts_open
  on public.sync_conflicts (collection_id, created_at)
  where resolved_at is null;

create table public.link_plans (
  id uuid primary key default gen_random_uuid(),
  collection_id uuid not null references public.collections (id) on delete cascade,
  local_digest text not null,
  remote_high_water bigint not null,
  owner uuid not null default auth.uid(),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  consumed_at timestamptz
);

alter table public.collections enable row level security;
alter table public.collections force row level security;
alter table public.collection_items enable row level security;
alter table public.collection_items force row level security;
alter table public.collection_operations enable row level security;
alter table public.collection_operations force row level security;
alter table public.sync_devices enable row level security;
alter table public.sync_devices force row level security;
alter table public.sync_conflicts enable row level security;
alter table public.sync_conflicts force row level security;
alter table public.link_plans enable row level security;
alter table public.link_plans force row level security;

create policy collections_read_own on public.collections
  for select to authenticated using (owner = auth.uid());

create policy collection_items_read_own on public.collection_items
  for select to authenticated using (exists (
    select 1 from public.collections c
    where c.id = collection_id and c.owner = auth.uid()
  ));

create policy collection_operations_read_own on public.collection_operations
  for select to authenticated using (exists (
    select 1 from public.collections c
    where c.id = collection_id and c.owner = auth.uid()
  ));

create policy sync_devices_read_own on public.sync_devices
  for select to authenticated using (exists (
    select 1 from public.collections c
    where c.id = collection_id and c.owner = auth.uid()
  ));

create policy sync_conflicts_read_own on public.sync_conflicts
  for select to authenticated using (exists (
    select 1 from public.collections c
    where c.id = collection_id and c.owner = auth.uid()
  ));

create policy link_plans_read_own on public.link_plans
  for select to authenticated using (exists (
    select 1 from public.collections c
    where c.id = collection_id and c.owner = auth.uid()
  ));

grant usage on schema public to anon, authenticated;
grant select on public.collections, public.collection_items, public.collection_operations, public.sync_devices, public.sync_conflicts, public.link_plans to authenticated;
