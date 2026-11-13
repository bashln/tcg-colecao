import test, { after, before, describe } from 'node:test';
import assert from 'node:assert/strict';
import { startCluster } from './helpers/platform.mjs';

const TABLES = ['jornada_card_map', 'jornada_quotes', 'jornada_skus', 'jornada_quota'];
const READABLE = ['jornada_card_map', 'jornada_quotes', 'jornada_skus'];
const CARD_ID = 'sv08.5-001';

let cluster;

before(async () => {
  cluster = await startCluster();
});

after(async () => {
  await cluster?.stop();
});

describe('jornada price cache', () => {
  test('tables exist with forced row level security', async () => {
    const tables = await cluster.admin.query(
      `select c.relname, c.relrowsecurity, c.relforcerowsecurity
       from pg_class c join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'public' and c.relkind = 'r' and c.relname = any($1)`,
      [TABLES],
    );
    assert.deepEqual(
      tables.rows.map((row) => row.relname).sort(),
      [...TABLES].sort(),
    );
    for (const row of tables.rows) {
      assert.equal(row.relrowsecurity, true, `${row.relname} enables RLS`);
      assert.equal(row.relforcerowsecurity, true, `${row.relname} forces RLS`);
    }
  });

  test('anon reads the cache and cannot write it', async () => {
    await cluster.admin.query(
      `insert into public.jornada_card_map
         (card_id, public_id, code, set_code, matched_by, matched_at)
       values ($1, 103425, $1, 'sv08.5', 'code', now())`,
      [CARD_ID],
    );
    await cluster.admin.query(
      `insert into public.jornada_quotes
         (card_id, public_id, market_cents, market_kind, liquidity, fetched_at)
       values ($1, 103425, 500, 'ask', '{"level":"low","score":7}'::jsonb, now())`,
      [CARD_ID],
    );
    await cluster.admin.query(
      `insert into public.jornada_skus
         (card_id, condition, language, finish, lowest_cents, highest_cents, listings, fetched_at)
       values ($1, 'near_mint', 'PT', 'reverse_holo', 40, 100, 2, now())`,
      [CARD_ID],
    );

    const anon = await cluster.connectActor({ role: 'anon' });
    const quote = await anon.query(
      `select card_id, public_id, market_cents, market_kind from public.jornada_quotes`,
    );
    assert.equal(quote.rows.length, 1);
    assert.equal(quote.rows[0].card_id, CARD_ID);
    assert.equal(Number(quote.rows[0].market_cents), 500);

    const sku = await anon.query(
      `select card_id, condition, language, finish, lowest_cents from public.jornada_skus`,
    );
    assert.equal(sku.rows.length, 1);
    assert.equal(sku.rows[0].finish, 'reverse_holo');

    const map = await anon.query(`select card_id, public_id from public.jornada_card_map`);
    assert.equal(map.rows.length, 1);

    await assert.rejects(
      () =>
        anon.query(
          `insert into public.jornada_quotes
             (card_id, public_id, market_cents, fetched_at)
           values ('x-1', 1, 1, now())`,
        ),
      /permission denied/,
    );
    await anon.end();
  });

  test('quota stays service only', async () => {
    const anon = await cluster.connectActor({ role: 'anon' });
    await assert.rejects(
      () => anon.query(`select utc_day from public.jornada_quota`),
      /permission denied/,
    );
    await anon.end();

    await cluster.admin.query(
      `insert into public.jornada_quota (utc_day, market_calls, market_day_limit)
       values (current_date, 3, 100)`,
    );
    const row = await cluster.admin.query(
      `select market_calls, market_day_limit from public.jornada_quota where utc_day = current_date`,
    );
    assert.equal(Number(row.rows[0].market_calls), 3);
    assert.equal(Number(row.rows[0].market_day_limit), 100);
  });

  test('negative cents and listings are rejected', async () => {
    await assert.rejects(
      () =>
        cluster.admin.query(
          `insert into public.jornada_skus
             (card_id, condition, language, finish, lowest_cents, listings, fetched_at)
           values ('card-neg', 'near_mint', 'PT', 'normal', -1, 0, now())`,
        ),
      /check constraint/,
    );
    await assert.rejects(
      () =>
        cluster.admin.query(
          `insert into public.jornada_skus
             (card_id, condition, language, finish, lowest_cents, listings, fetched_at)
           values ('card-neg', 'near_mint', 'PT', 'normal', 1, -1, now())`,
        ),
      /check constraint/,
    );
  });

  test('the readable tables expose every expected column', async () => {
    const columns = await cluster.admin.query(
      `select table_name, column_name from information_schema.columns
       where table_schema = 'public' and table_name = any($1)`,
      [READABLE],
    );
    const byTable = Object.fromEntries(READABLE.map((table) => [table, new Set()]));
    for (const row of columns.rows) byTable[row.table_name].add(row.column_name);
    assert.deepEqual(
      [...byTable.jornada_card_map].sort(),
      ['card_id', 'code', 'matched_at', 'matched_by', 'missed_at', 'public_id', 'set_code'].sort(),
    );
    assert.deepEqual(
      [...byTable.jornada_quotes].sort(),
      ['card_id', 'code', 'fetched_at', 'liquidity', 'market_cents', 'market_kind', 'public_id'].sort(),
    );
    assert.deepEqual(
      [...byTable.jornada_skus].sort(),
      ['card_id', 'condition', 'fetched_at', 'finish', 'highest_cents', 'language', 'listings', 'lowest_cents'].sort(),
    );
  });
});
