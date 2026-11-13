-- TCGdex card ids carry a dot in decimal sets such as sv08.5-001. The original
-- check rejected them, so those cards could not sync even though the clients
-- already stored them locally.
alter table public.collection_items
  drop constraint collection_items_card_id_check;

alter table public.collection_items
  add constraint collection_items_card_id_check
  check (card_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}$');
