import test, { after, before, describe } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startCluster } from './helpers/platform.mjs';

const USER_A = '00000000-0000-0000-0000-00000000000a';
const USER_B = '00000000-0000-0000-0000-00000000000b';
const DEVICE_A1 = 'device-a1-0000000000000000000000';

let cluster;

function item(overrides = {}) {
  const id = overrides.id ?? `item-${randomUUID()}`;
  return {
    id,
    cardId: overrides.cardId ?? `card-${id}`,
    quantity: overrides.quantity ?? 1,
    variant: overrides.variant ?? 'NORMAL',
    condition: overrides.condition ?? 'NEAR_MINT',
    pricePaid: overrides.pricePaid ?? null,
    addedAt: overrides.addedAt ?? 1_700_000_000_000,
  };
}

function op(overrides = {}) {
  const kind = overrides.kind ?? 'create';
  const base = {
    opId: overrides.opId ?? randomUUID(),
    kind,
    signature: overrides.signature ?? randomUUID(),
  };
  if (kind === 'delete') {
    return { ...base, itemId: overrides.itemId, baseRevision: overrides.baseRevision };
  }
  return {
    ...base,
    item: overrides.item ?? item(overrides.itemId ? { id: overrides.itemId } : {}),
    ...(kind === 'replace' ? { baseRevision: overrides.baseRevision } : {}),
  };
}

async function push(actor, operations, deviceId = DEVICE_A1) {
  return actor.query('select public.push_collection_operations($1::text, $2::jsonb) as result', [
    deviceId,
    JSON.stringify(operations),
  ]);
}

async function pull(actor, after = 0, limit = 500) {
  return actor.query('select public.pull_collection_operations($1::bigint, $2::int) as result', [
    after,
    limit,
  ]);
}

function outcome(result, opId) {
  return result.results.find((row) => row.opId === opId);
}

before(async () => {
  cluster = await startCluster();
});

after(async () => {
  await cluster?.stop();
});

describe('push_collection_operations', () => {
  test('applies create, registers device, advances cursor', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const create = op({ item: item({ id: 'item-create-0001' }) });
    const response = await push(actor, [create]);
    const result = response.rows[0].result;

    const applied = outcome(result, create.opId);
    assert.equal(applied.status, 'applied');
    assert.equal(applied.revision, 1);
    assert.equal(result.highWater, 1);

    const stored = await cluster.admin.query(
      `select item_id, revision::int as revision, deleted from public.collection_items
       where item_id = 'item-create-0001'`,
    );
    assert.deepEqual(stored.rows, [{ item_id: 'item-create-0001', revision: 1, deleted: false }]);

    const device = await cluster.admin.query(
      `select d.device_id from public.sync_devices d
       join public.collections c on c.id = d.collection_id
       where c.owner = $1::uuid`,
      [USER_A],
    );
    assert.deepEqual(device.rows.map((r) => r.device_id), [DEVICE_A1]);
    await actor.end();
  });

  test('replaying an identical operation is idempotent', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const create = op({ item: item({ id: 'item-replay-0001' }) });
    const first = await push(actor, [create]);
    const highAfterFirst = first.rows[0].result.highWater;
    const again = await push(actor, [create]);
    const result = again.rows[0].result;
    assert.equal(outcome(result, create.opId).status, 'already-applied');
    assert.equal(result.highWater, highAfterFirst, 'replay must not allocate a new server sequence');

    const ops = await cluster.admin.query(
      `select count(*)::int as n from public.collection_operations where op_id = $1::uuid`,
      [create.opId],
    );
    assert.equal(ops.rows[0].n, 1);
    await actor.end();
  });

  test('reusing an operation id with different content is rejected and recorded', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const create = op({ item: item({ id: 'item-mismatch-0001' }) });
    await push(actor, [create]);
    const forged = op({ ...create, item: item({ id: 'item-mismatch-0001', quantity: 9 }), signature: randomUUID() });
    const response = await push(actor, [forged]);
    const result = response.rows[0].result;
    assert.equal(outcome(result, create.opId).status, 'conflict');
    assert.equal(outcome(result, create.opId).reason, 'id-collision');

    const stored = await cluster.admin.query(
      `select quantity::int as quantity from public.collection_items where item_id = 'item-mismatch-0001'`,
    );
    assert.equal(stored.rows[0].quantity, 1, 'colliding replay must not change the item');

    const conflicts = await cluster.admin.query(
      `select count(*)::int as n from public.sync_conflicts where reason = 'id-collision'`,
    );
    assert.ok(conflicts.rows[0].n >= 1);
    await actor.end();
  });

  test('replace honors CAS and records stale attempts', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const create = op({ item: item({ id: 'item-cas-000001' }) });
    await push(actor, [create]);

    const replace = op({ kind: 'replace', item: item({ id: 'item-cas-000001', quantity: 3 }), baseRevision: 1 });
    const ok = await push(actor, [replace]);
    assert.equal(outcome(ok.rows[0].result, replace.opId).revision, 2);

    const stale = op({ kind: 'replace', item: item({ id: 'item-cas-000001', quantity: 5 }), baseRevision: 1 });
    const staleResponse = await push(actor, [stale]);
    const staleOutcome = outcome(staleResponse.rows[0].result, stale.opId);
    assert.equal(staleOutcome.status, 'conflict');
    assert.equal(staleOutcome.reason, 'stale');

    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision from public.collection_items where item_id = 'item-cas-000001'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 3, revision: 2 }]);
    await actor.end();
  });

  test('create against duplicate live configuration conflicts', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const first = op({ item: item({ id: 'item-dup-a-00001', cardId: 'dup-card-0001' }) });
    await push(actor, [first]);
    const second = op({ item: item({ id: 'item-dup-b-00001', cardId: 'dup-card-0001' }) });
    const response = await push(actor, [second]);
    const result = response.rows[0].result;
    const created = outcome(result, second.opId);
    assert.equal(created.status, 'conflict');
    assert.equal(created.reason, 'duplicate-configuration');
    await actor.end();
  });

  test('delete creates a tombstone, later replace conflicts, pull reflects delete', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const create = op({ item: item({ id: 'item-del-000001' }) });
    await push(actor, [create]);

    const remove = op({ kind: 'delete', itemId: 'item-del-000001', baseRevision: 1 });
    const response = await push(actor, [remove]);
    assert.equal(outcome(response.rows[0].result, remove.opId).revision, 2);

    const stored = await cluster.admin.query(
      `select deleted, revision::int as revision from public.collection_items where item_id = 'item-del-000001'`,
    );
    assert.deepEqual(stored.rows, [{ deleted: true, revision: 2 }]);

    const afterDelete = op({ kind: 'replace', item: item({ id: 'item-del-000001' }), baseRevision: 2 });
    const conflictResponse = await push(actor, [afterDelete]);
    assert.equal(outcome(conflictResponse.rows[0].result, afterDelete.opId).reason, 'deleted');

    const page = await pull(actor, 0);
    const ops = page.rows[0].result.operations;
    const deleteOp = ops.find((row) => row.opId === remove.opId);
    assert.equal(deleteOp.kind, 'delete');
    await actor.end();
  });

  test('malformed request aborts the whole batch with no partial write', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const valid = op({ item: item({ id: 'item-rollback-01' }) });
    const missingSignature = { ...op({ item: item({ id: 'item-rollback-02' }) }), signature: undefined };
    delete missingSignature.signature;

    await assert.rejects(
      () => push(actor, [valid, missingSignature]),
      /invalid-signature/,
      'missing signature is a protocol violation, not a per-operation outcome',
    );

    const stored = await cluster.admin.query(
      `select item_id from public.collection_items where item_id like 'item-rollback-%'`,
    );
    assert.deepEqual(stored.rows, [], 'first valid operation must roll back with the batch');
    await actor.end();
  });

  test('devices cannot address another owner: identity comes only from the JWT', async () => {
    const actorB = await cluster.connectActor({ role: 'authenticated', sub: USER_B });
    const shared = op({ item: item({ id: 'item-shared-0001' }) });
    const response = await push(actorB, [shared]);
    assert.equal(outcome(response.rows[0].result, shared.opId).status, 'applied');

    const itemsA = await cluster.admin.query(
      `select i.item_id from public.collection_items i
       join public.collections c on c.id = i.collection_id
       where c.owner = $1::uuid and i.item_id = 'item-shared-0001'`,
      [USER_A],
    );
    assert.deepEqual(itemsA.rows, [], 'user A collection must be untouched by user B push');

    const itemsB = await cluster.admin.query(
      `select i.item_id from public.collection_items i
       join public.collections c on c.id = i.collection_id
       where c.owner = $1::uuid and i.item_id = 'item-shared-0001'`,
      [USER_B],
    );
    assert.equal(itemsB.rows.length, 1, 'user B owns the pushed item in a separate collection');
    await actorB.end();
  });

  test('rejects out-of-range quantity as an explicit conflict without mutation', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const bad = op({ item: item({ id: 'item-qty-000001', quantity: 1_000_001 }) });
    const response = await push(actor, [bad]);
    const result = response.rows[0].result;
    assert.equal(outcome(result, bad.opId).status, 'rejected');
    assert.equal(outcome(result, bad.opId).reason, 'invalid-payload');
    const stored = await cluster.admin.query(
      `select item_id from public.collection_items where item_id = 'item-qty-000001'`,
    );
    assert.deepEqual(stored.rows, []);
    await actor.end();
  });

  test('stores purchase prices in exact minor currency units and rejects major-unit payloads', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const exact = op({ item: item({
      id: 'item-money-cents-01',
      pricePaid: { amount: 1234, currency: 'USD' },
    }) });
    const accepted = await push(actor, [exact]);
    assert.equal(outcome(accepted.rows[0].result, exact.opId).status, 'applied');

    const stored = await cluster.admin.query(
      `select price_paid_amount::int as amount, price_paid_currency as currency
       from public.collection_items where item_id = 'item-money-cents-01'`,
    );
    assert.deepEqual(stored.rows, [{ amount: 1234, currency: 'USD' }]);

    const decimal = op({ item: item({
      id: 'item-money-major-01',
      pricePaid: { amount: 12.34, currency: 'USD' },
    }) });
    const rejected = await push(actor, [decimal]);
    assert.equal(outcome(rejected.rows[0].result, decimal.opId).status, 'rejected');
    assert.equal(outcome(rejected.rows[0].result, decimal.opId).reason, 'invalid-payload');
    await actor.end();
  });

  test('anon cannot call push', async () => {
    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => push(anon, [op({})]), /permission denied/);
    await anon.end();
  });

  test('authenticated without JWT claims is refused', async () => {
    const noClaims = await cluster.connectActor({ role: 'authenticated', sub: null });
    await assert.rejects(() => push(noClaims, [op({})]), /not authenticated/);
    await noClaims.end();
  });
});

describe('pull_collection_operations', () => {
  test('paginates with an ordered cursor scoped to the caller', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: USER_A });
    const base = (await pull(actor, 0)).rows[0].result.highWater;
    const first = op({ item: item({ id: 'item-page-000001' }) });
    const second = op({ kind: 'replace', item: item({ id: 'item-page-000001', quantity: 2 }), baseRevision: 1 });
    const third = op({ kind: 'delete', itemId: 'item-page-000001', baseRevision: 2 });
    await push(actor, [first, second, third]);

    const page1 = (await pull(actor, base, 2)).rows[0].result;
    assert.equal(page1.operations.length, 2);
    assert.equal(page1.highWater, base + 3);
    assert.deepEqual(page1.operations.map((row) => row.kind), ['create', 'replace']);

    const page2 = (await pull(actor, base + 2, 2)).rows[0].result;
    assert.deepEqual(page2.operations.map((row) => row.kind), ['delete']);

    const page3 = (await pull(actor, base + 3, 2)).rows[0].result;
    assert.deepEqual(page3.operations, []);
    assert.equal(page3.highWater, base + 3, 'high water stays stable so clients can persist the cursor');
    await actor.end();
  });

  test('another account pulls an empty log', async () => {
    const actorB = await cluster.connectActor({
      role: 'authenticated',
      sub: '00000000-0000-0000-0000-00000000000c',
    });
    const page = (await pull(actorB, 0)).rows[0].result;
    assert.deepEqual(page.operations, []);
    assert.equal(page.highWater, 0);
    await actorB.end();
  });

  test('anon cannot call pull', async () => {
    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => pull(anon, 0), /permission denied/);
    await anon.end();
  });
});
