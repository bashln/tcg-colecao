import test, { after, before, describe } from 'node:test';
import assert from 'node:assert/strict';
import { startCluster } from './helpers/platform.mjs';

const USER_A = '00000000-0000-0000-0000-00000000000a';
const USER_B = '00000000-0000-0000-0000-00000000000b';
const COLLECTION_A = '10000000-0000-0000-0000-00000000000a';
const COLLECTION_B = '10000000-0000-0000-0000-00000000000b';

const SYNC_TABLES = [
  'collections',
  'collection_items',
  'collection_operations',
  'sync_devices',
  'sync_conflicts',
  'link_plans',
];

let cluster;

before(async () => {
  cluster = await startCluster();
});

after(async () => {
  await cluster?.stop();
});

describe('collection sync schema and RLS', () => {
  test('migration applies and expected tables exist', async () => {
    assert.ok(cluster.applied.length > 0, 'at least one migration must be applied');
    const rows = await cluster.admin.query(
      `select table_name from information_schema.tables
       where table_schema = 'public' and table_name = any($1)`,
      [SYNC_TABLES],
    );
    assert.deepEqual(
      rows.rows.map((r) => r.table_name).sort(),
      [...SYNC_TABLES].sort(),
    );
  });

  test('every sync table has row level security enabled and forced', async () => {
    const rows = await cluster.admin.query(
      `select c.relname, c.relrowsecurity, c.relforcerowsecurity
       from pg_class c join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'public' and c.relkind = 'r' and c.relname = any($1)`,
      [SYNC_TABLES],
    );
    assert.equal(rows.rows.length, SYNC_TABLES.length);
    for (const row of rows.rows) {
      assert.equal(row.relrowsecurity, true, `${row.relname} must enable RLS`);
      assert.equal(row.relforcerowsecurity, true, `${row.relname} must force RLS`);
    }
  });

  test('grants: authenticated reads only, anon and direct mutations denied', async () => {
    for (const table of SYNC_TABLES) {
      const priv = await cluster.admin.query(
        `select has_table_privilege('authenticated', 'public.' || $1, 'SELECT') as can_select,
                has_table_privilege('authenticated', 'public.' || $1, 'INSERT') as can_insert,
                has_table_privilege('authenticated', 'public.' || $1, 'UPDATE') as can_update,
                has_table_privilege('authenticated', 'public.' || $1, 'DELETE') as can_delete,
                has_table_privilege('anon', 'public.' || $1, 'SELECT') as anon_select`,
        [table],
      );
      const p = priv.rows[0];
      assert.equal(p.can_select, true, `${table} readable by authenticated via RLS`);
      assert.equal(p.can_insert, false, `${table} must deny direct insert`);
      assert.equal(p.can_update, false, `${table} must deny direct update`);
      assert.equal(p.can_delete, false, `${table} must deny direct delete`);
      assert.equal(p.anon_select, false, `${table} must deny anon`);
    }
  });

  test('RLS isolates collections and items per JWT owner', async () => {
    await cluster.admin.query(
      `insert into public.collections (id, owner) values ($1::uuid, $2::uuid), ($3::uuid, $4::uuid)`,
      [COLLECTION_A, USER_A, COLLECTION_B, USER_B],
    );
    await cluster.admin.query(
      `insert into public.collection_items
         (collection_id, item_id, card_id, variant, condition, quantity, added_at)
       values
         ($1::uuid, 'item-aaaa-0001', 'base1-4', 'NORMAL', 'NEAR_MINT', 1, 1000),
         ($2::uuid, 'item-bbbb-0001', 'base1-4', 'NORMAL', 'NEAR_MINT', 2, 2000)`,
      [COLLECTION_A, COLLECTION_B],
    );

    const asA = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const seenA = await asA.query(
      `select id, owner from public.collections order by owner`,
    );
    assert.deepEqual(
      seenA.rows.map((r) => r.id),
      [COLLECTION_A],
      'user A must see only the own collection',
    );
    const itemsA = await asA.query(
      `select item_id from public.collection_items order by item_id`,
    );
    assert.deepEqual(
      itemsA.rows.map((r) => r.item_id),
      ['item-aaaa-0001'],
      'user A must see only own items',
    );
    await asA.end();

    const asB = await cluster.connectActor({ role: 'authenticated', sub: USER_B });
    const itemsB = await asB.query(`select item_id from public.collection_items`);
    assert.deepEqual(
      itemsB.rows.map((r) => r.item_id),
      ['item-bbbb-0001'],
      'user B must see only own items',
    );
    await asB.end();

    const forged = await cluster.connectActor({ role: 'authenticated', sub: USER_B });
    const attempt = await forged.query(
      `select id from public.collections where owner = $1`,
      [USER_A],
    );
    assert.deepEqual(attempt.rows, [], 'forged owner filter must return nothing');
    await forged.end();

    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(
      () => anon.query(`select id from public.collections`),
      /permission denied/,
    );
    await anon.end();
  });

  test('active configuration uniqueness holds only for live items', async () => {
    await assert.rejects(
      () =>
        cluster.admin.query(
          `insert into public.collection_items
             (collection_id, item_id, card_id, variant, condition, quantity, added_at)
           values
             ($1, 'item-aaaa-0002', 'base1-4', 'NORMAL', 'NEAR_MINT', 1, 3000)`,
          [COLLECTION_A],
        ),
      /duplicate key value violates unique constraint/,
      'two live items with same card/variant/condition must be rejected',
    );

    await cluster.admin.query(
      `update public.collection_items set deleted = true
       where collection_id = $1 and item_id = 'item-aaaa-0001'`,
      [COLLECTION_A],
    );

    await cluster.admin.query(
      `insert into public.collection_items
         (collection_id, item_id, card_id, variant, condition, quantity, added_at)
       values
         ($1, 'item-aaaa-0002', 'base1-4', 'NORMAL', 'NEAR_MINT', 1, 3000)`,
      [COLLECTION_A],
    );

    const live = await cluster.admin.query(
      `select item_id from public.collection_items
       where collection_id = $1 and card_id = 'base1-4' and not deleted`,
      [COLLECTION_A],
    );
    assert.deepEqual(
      live.rows.map((r) => r.item_id),
      ['item-aaaa-0002'],
      'tombstoned configuration must not block a new live item',
    );
  });
});
