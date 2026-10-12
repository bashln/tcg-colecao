import test, { after, before, describe } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { listMigrations, readMigration, startCluster } from './helpers/platform.mjs';

const DURABLE_PUSH_MIGRATION = '20261012220000_durable_push_conflicts.sql';

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

async function readPushConflicts(actor) {
  const response = await actor.query('select public.read_collection_push_conflicts() as result');
  return response.rows[0].result;
}

async function resolvePushConflict(actor, conflictId, choice, deviceId = DEVICE_A1) {
  const response = await actor.query(
    'select public.resolve_collection_push_conflict($1::uuid, $2::text, $3::text) as result',
    [conflictId, choice, deviceId],
  );
  return response.rows[0].result;
}

async function ackPushConflict(actor, conflictId, resolutionId) {
  const response = await actor.query(
    'select public.ack_collection_push_conflict_resolution($1::uuid, $2::uuid) as result',
    [conflictId, resolutionId],
  );
  return response.rows[0].result;
}

async function remoteSequence(owner) {
  const rows = await cluster.admin.query(
    'select remote_sequence::int as sequence from public.collections where owner = $1::uuid',
    [owner],
  );
  return rows.rows[0]?.sequence ?? 0;
}

async function conflictRowCount(opId) {
  const rows = await cluster.admin.query(
    'select count(*)::int as n from public.sync_conflicts where op_id = $1::uuid',
    [opId],
  );
  return rows.rows[0].n;
}

async function readLegacyConflicts(actor) {
  const response = await actor.query('select public.read_collection_conflicts() as result');
  return response.rows[0].result;
}

async function resolveLegacyConflict(actor, conflictId, choice, deviceId = DEVICE_A1) {
  const response = await actor.query(
    'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
    [conflictId, choice, deviceId],
  );
  return response.rows[0].result;
}

async function conflictRowState(conflictId) {
  const rows = await cluster.admin.query(
    `select resolved_at, resolution_id, superseded_by, source
     from public.sync_conflicts where id = $1::uuid`,
    [conflictId],
  );
  return rows.rows[0];
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

describe('sync conflict listing and resolution', () => {
  test('lists only caller conflicts and resolution retries idempotently', async () => {
    const owner = '00000000-0000-0000-0000-0000000000aa';
    const otherOwner = '00000000-0000-0000-0000-0000000000bb';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    const other = await cluster.connectActor({ role: 'authenticated', sub: otherOwner });
    const ownCollection = await cluster.admin.query('select public._tcg_ensure_collection($1::uuid) as id', [owner]);
    const foreignCollection = await cluster.admin.query('select public._tcg_ensure_collection($1::uuid) as id', [otherOwner]);
    const ownId = randomUUID();
    const foreignId = randomUUID();
    await cluster.admin.query(
      `insert into public.sync_conflicts (id, collection_id, item_id, reason, payload, owner)
       values ($1, $2, 'item-own-000001', 'stale', '{"local":{"quantity":1},"remote":{"quantity":2}}', $3),
              ($4, $5, 'item-foreign-01', 'stale', '{"local":{"quantity":1},"remote":{"quantity":3}}', $6)`,
      [ownId, ownCollection.rows[0].id, owner, foreignId, foreignCollection.rows[0].id, otherOwner],
    );

    const listed = await actor.query('select public.read_collection_conflicts() as result');
    assert.deepEqual(listed.rows[0].result.map((row) => row.id), [ownId]);
    assert.equal(listed.rows[0].result[0].payload.remote.quantity, 2);
    const resolved = await actor.query(
      'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
      [ownId, 'keep-local', DEVICE_A1],
    );
    assert.equal(resolved.rows[0].result.status, 'resolved');
    const replay = await actor.query(
      'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
      [ownId, 'keep-local', DEVICE_A1],
    );
    assert.equal(replay.rows[0].result.status, 'already-resolved');
    const foreign = await actor.query(
      'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
      [foreignId, 'keep-cloud', DEVICE_A1],
    );
    assert.equal(foreign.rows[0].result.status, 'not-found');
    const otherList = await other.query('select public.read_collection_conflicts() as result');
    assert.deepEqual(otherList.rows[0].result.map((row) => row.id), [foreignId]);
    await actor.end();
    await other.end();
  });
});

describe('durable push conflict resolution protocol', () => {
  test('durable push conflict keeps one stable row and id when the same operation is retried', async () => {
    const owner = '00000000-0000-0000-0000-00000000c001';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-stable-0001' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-stable-0001', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-stable-0001', quantity: 9 }),
      baseRevision: 1,
    });

    const first = (await push(actor, [stale])).rows[0].result;
    const firstOutcome = outcome(first, stale.opId);
    assert.equal(firstOutcome.status, 'conflict');
    assert.equal(firstOutcome.reason, 'stale');
    assert.ok(firstOutcome.conflictId, 'a content conflict must return its durable conflict id');
    assert.deepEqual(firstOutcome.rejectedOperation, stale, 'the rejected payload must echo back verbatim');
    assert.deepEqual(firstOutcome.cloudItem, {
      itemId: 'item-stable-0001',
      cardId: 'card-item-stable-0001',
      variant: 'NORMAL',
      condition: 'NEAR_MINT',
      quantity: 3,
      pricePaid: null,
      addedAt: 1_700_000_000_000,
      revision: 2,
      deleted: false,
    });
    assert.equal(firstOutcome.conflictingItem, null, 'a same-item conflict has no separate configuration owner');
    assert.equal(firstOutcome.highWater, await remoteSequence(owner), 'highWater labels the observed snapshot');

    const second = (await push(actor, [stale])).rows[0].result;
    const secondOutcome = outcome(second, stale.opId);
    assert.equal(
      secondOutcome.conflictId,
      firstOutcome.conflictId,
      'a retried operation must reuse its open conflict row',
    );
    assert.deepEqual(secondOutcome.cloudItem, firstOutcome.cloudItem, 'a retry must refresh the same snapshot');
    assert.equal(await conflictRowCount(stale.opId), 1, 'the open conflict row must not duplicate');
    await actor.end();
  });

  test('durable push conflict returns a tombstone snapshot and never advances the remote sequence', async () => {
    const owner = '00000000-0000-0000-0000-00000000c002';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-tombstone-01' }) })]);
    await push(actor, [op({ kind: 'delete', itemId: 'item-tombstone-01', baseRevision: 1 })]);
    const before = await remoteSequence(owner);
    const replace = op({
      kind: 'replace',
      item: item({ id: 'item-tombstone-01', quantity: 5 }),
      baseRevision: 2,
    });

    const first = (await push(actor, [replace])).rows[0].result;
    const firstOutcome = outcome(first, replace.opId);
    assert.equal(firstOutcome.status, 'conflict');
    assert.equal(firstOutcome.reason, 'deleted');
    assert.equal(firstOutcome.highWater, before, 'highWater is the snapshot label, not an advance');
    assert.equal(firstOutcome.cloudItem.deleted, true, 'a tombstone is authoritative cloud state');
    assert.equal(firstOutcome.cloudItem.revision, 2);
    assert.equal(firstOutcome.conflictingItem, null);

    const second = (await push(actor, [replace])).rows[0].result;
    assert.equal(
      outcome(second, replace.opId).conflictId,
      firstOutcome.conflictId,
      'the tombstone conflict must keep one stable id across retries',
    );

    assert.equal(await remoteSequence(owner), before, 'a rejected operation must not advance the remote sequence');
    const logged = await cluster.admin.query(
      `select count(*)::int as n from public.collection_operations where op_id = $1::uuid`,
      [replace.opId],
    );
    assert.equal(logged.rows[0].n, 0, 'a rejected operation must stay out of the log');
    const page = (await pull(actor, 0)).rows[0].result;
    assert.equal(page.highWater, before, 'the pull high water must ignore rejected work');
    await actor.end();
  });

  test('durable push conflict reports the duplicate configuration owner as the conflicting item', async () => {
    const owner = '00000000-0000-0000-0000-00000000c003';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-dupcloud-01', cardId: 'dupcloud-card-01' }) })]);
    const duplicate = op({ item: item({ id: 'item-dupcloud-02', cardId: 'dupcloud-card-01' }) });

    const result = (await push(actor, [duplicate])).rows[0].result;
    const conflict = outcome(result, duplicate.opId);
    assert.equal(conflict.status, 'conflict');
    assert.equal(conflict.reason, 'duplicate-configuration');
    assert.ok(conflict.conflictId);
    assert.equal(conflict.cloudItem, null, 'the rejected item id has no server row yet');
    assert.deepEqual(conflict.conflictingItem, {
      itemId: 'item-dupcloud-01',
      cardId: 'dupcloud-card-01',
      variant: 'NORMAL',
      condition: 'NEAR_MINT',
      quantity: 1,
      pricePaid: null,
      addedAt: 1_700_000_000_000,
      revision: 1,
      deleted: false,
    });
    await actor.end();
  });

  test('push conflict resolution applies keep-local once and replays the same receipt', async () => {
    const owner = '00000000-0000-0000-0000-00000000c004';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-keep-local-01' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-keep-local-01', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-keep-local-01', quantity: 9 }),
      baseRevision: 1,
    });
    const pushed = (await push(actor, [stale])).rows[0].result;
    const conflictId = outcome(pushed, stale.opId).conflictId;
    const before = await remoteSequence(owner);

    const resolved = await resolvePushConflict(actor, conflictId, 'keep-local');
    assert.equal(resolved.status, 'resolved');
    assert.equal(resolved.choice, 'keep-local');
    assert.ok(resolved.resolutionId, 'a resolution must return a durable receipt id');
    assert.equal(resolved.result.kind, 'keep-local');
    assert.equal(resolved.result.itemId, 'item-keep-local-01');
    assert.equal(resolved.result.localItemId, 'item-keep-local-01');
    assert.equal(resolved.result.operation.kind, 'replace');
    assert.equal(resolved.result.operation.baseRevision, 2, 'keep-local applies against the current cloud revision');
    assert.equal(resolved.result.revision, 3);

    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision
       from public.collection_items where item_id = 'item-keep-local-01'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 9, revision: 3 }]);

    assert.equal(
      await remoteSequence(owner),
      before + 1,
      'only the recorded keep-local operation may advance the sequence',
    );
    const page = (await pull(actor, before)).rows[0].result;
    assert.deepEqual(
      page.operations.map((row) => ({ opId: row.opId, kind: row.kind, itemId: row.itemId })),
      [{ opId: resolved.result.operationId, kind: 'replace', itemId: 'item-keep-local-01' }],
      'the new operation must be pullable by every device',
    );

    const replay = await resolvePushConflict(actor, conflictId, 'keep-local');
    assert.equal(replay.status, 'resolved');
    assert.equal(replay.resolutionId, resolved.resolutionId, 'a same-choice retry must return the same receipt');
    assert.deepEqual(replay.result, resolved.result, 'the recorded result must stay immutable');
    const operations = await cluster.admin.query(
      `select count(*)::int as n from public.collection_operations where op_id = $1::uuid`,
      [resolved.result.operationId],
    );
    assert.equal(operations.rows[0].n, 1, 'a replayed resolution must not duplicate the operation');

    const listed = await readPushConflicts(actor);
    const entry = listed.find((row) => row.id === conflictId);
    assert.ok(entry, 'the conflict stays listed until the owner acknowledges it');
    assert.equal(entry.resolution.id, resolved.resolutionId);
    assert.equal(entry.resolution.choice, 'keep-local');
    await actor.end();
  });

  test('push conflict resolution refuses a different choice after recording a receipt', async () => {
    const owner = '00000000-0000-0000-0000-00000000c005';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-keep-local-02' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-keep-local-02', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-keep-local-02', quantity: 9 }),
      baseRevision: 1,
    });
    const pushed = (await push(actor, [stale])).rows[0].result;
    const conflictId = outcome(pushed, stale.opId).conflictId;

    const resolved = await resolvePushConflict(actor, conflictId, 'keep-local');
    assert.equal(resolved.status, 'resolved');
    const sequenceAfterFirst = await remoteSequence(owner);

    const refused = await resolvePushConflict(actor, conflictId, 'keep-cloud');
    assert.equal(refused.status, 'already-resolved', 'a second, different choice must never apply');
    assert.equal(refused.choice, 'keep-local', 'the recorded choice must be reported back');
    assert.equal(refused.resolutionId, resolved.resolutionId);
    assert.deepEqual(refused.result, resolved.result);
    assert.equal(
      await remoteSequence(owner),
      sequenceAfterFirst,
      'a refused second choice must not add server operations',
    );

    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision
       from public.collection_items where item_id = 'item-keep-local-02'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 9, revision: 3 }], 'the first choice stands');
    const listed = await readPushConflicts(actor);
    assert.equal(listed.find((row) => row.id === conflictId).resolution.choice, 'keep-local');
    await actor.end();
  });

  test('push conflict resolution stays replayable until an idempotent local acknowledgment', async () => {
    const owner = '00000000-0000-0000-0000-00000000c006';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-keep-cloud-01' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-keep-cloud-01', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-keep-cloud-01', quantity: 9 }),
      baseRevision: 1,
    });
    const pushed = (await push(actor, [stale])).rows[0].result;
    const conflictId = outcome(pushed, stale.opId).conflictId;
    const before = await remoteSequence(owner);

    const listedBefore = await readPushConflicts(actor);
    const entryBefore = listedBefore.find((row) => row.id === conflictId);
    assert.ok(entryBefore, 'a lost push response is recoverable through the owner-scoped read');
    assert.equal(entryBefore.resolution, null);
    assert.deepEqual(entryBefore.rejectedOperation, stale);
    assert.equal(entryBefore.cloudItem.quantity, 3, 'the read refreshes authoritative cloud state');
    assert.equal(entryBefore.highWater, before, 'the refreshed high water is a snapshot label only');

    const wrongAck = await ackPushConflict(actor, conflictId, randomUUID());
    assert.equal(wrongAck.status, 'stale-conflict', 'an acknowledgment must bind to the recorded receipt');

    const resolved = await resolvePushConflict(actor, conflictId, 'keep-cloud');
    assert.equal(resolved.status, 'resolved');
    assert.equal(resolved.result.kind, 'keep-cloud');
    assert.equal(resolved.result.localItemId, 'item-keep-cloud-01');
    assert.equal(resolved.result.operationId, null, 'keep-cloud records no server operation');
    assert.equal(resolved.result.cloudItem.quantity, 3);
    assert.equal(resolved.result.cloudItem.revision, 2);

    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision
       from public.collection_items where item_id = 'item-keep-cloud-01'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 3, revision: 2 }], 'keep-cloud never rewrites cloud state');
    assert.equal(await remoteSequence(owner), before, 'keep-cloud must not advance the sequence');

    const listedDuring = await readPushConflicts(actor);
    const entryDuring = listedDuring.find((row) => row.id === conflictId);
    assert.ok(entryDuring, 'an unacknowledged resolution keeps the conflict listed');
    assert.equal(entryDuring.resolution.id, resolved.resolutionId, 'the receipt replays for local recovery');
    assert.equal(entryDuring.resolution.choice, 'keep-cloud');
    assert.deepEqual(entryDuring.resolution.result, resolved.result);

    const acked = await ackPushConflict(actor, conflictId, resolved.resolutionId);
    assert.equal(acked.status, 'resolved');
    const ackedAgain = await ackPushConflict(actor, conflictId, resolved.resolutionId);
    assert.equal(ackedAgain.status, 'resolved', 'acknowledgment must be idempotent');
    assert.equal(ackedAgain.resolutionId, resolved.resolutionId);

    const listedAfter = await readPushConflicts(actor);
    assert.equal(
      listedAfter.find((row) => row.id === conflictId),
      undefined,
      'an acknowledged conflict leaves the open list',
    );
    const afterAck = await resolvePushConflict(actor, conflictId, 'keep-cloud');
    assert.equal(afterAck.status, 'already-resolved');
    await actor.end();
  });

  test('push conflict resolution refuses a comparison whose cloud state changed', async () => {
    const owner = '00000000-0000-0000-0000-00000000c007';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-stale-cloud-01' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-stale-cloud-01', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-stale-cloud-01', quantity: 9 }),
      baseRevision: 1,
    });
    const pushed = (await push(actor, [stale])).rows[0].result;
    const conflictId = outcome(pushed, stale.opId).conflictId;

    const newer = op({
      kind: 'replace',
      item: item({ id: 'item-stale-cloud-01', quantity: 5 }),
      baseRevision: 2,
    });
    await push(actor, [newer], 'device-b1-0000000000000000000000');
    const sequenceAfterRival = await remoteSequence(owner);

    const refused = await resolvePushConflict(actor, conflictId, 'keep-local');
    assert.equal(refused.status, 'stale-conflict', 'changed cloud state invalidates the old comparison');
    assert.equal(refused.cloudItem.quantity, 5, 'the refusal returns the fresh snapshot');
    assert.equal(refused.cloudItem.revision, 3);
    assert.equal(refused.highWater, sequenceAfterRival);
    assert.equal(
      await remoteSequence(owner),
      sequenceAfterRival,
      'a refused comparison must not apply or advance anything',
    );
    const unrecorded = await readPushConflicts(actor);
    assert.equal(unrecorded.find((row) => row.id === conflictId).resolution, null);

    const recovery = await resolvePushConflict(actor, conflictId, 'keep-local');
    assert.equal(recovery.status, 'resolved', 'a refreshed comparison lets the owner choose again');
    assert.equal(recovery.result.revision, 4);
    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision
       from public.collection_items where item_id = 'item-stale-cloud-01'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 9, revision: 4 }]);
    await actor.end();
  });

  test('push conflict resolution and acknowledgment stay scoped to the owning account', async () => {
    const ownerA = '00000000-0000-0000-0000-00000000c008';
    const ownerB = '00000000-0000-0000-0000-00000000c009';
    const actorA = await cluster.connectActor({ role: 'authenticated', sub: ownerA });
    const actorB = await cluster.connectActor({ role: 'authenticated', sub: ownerB });
    await push(actorA, [op({ item: item({ id: 'item-owner-scope-1' }) })]);
    await push(actorA, [
      op({ kind: 'replace', item: item({ id: 'item-owner-scope-1', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-owner-scope-1', quantity: 9 }),
      baseRevision: 1,
    });
    const pushed = (await push(actorA, [stale])).rows[0].result;
    const conflictId = outcome(pushed, stale.opId).conflictId;
    const resolved = await resolvePushConflict(actorA, conflictId, 'keep-cloud');
    assert.equal(resolved.status, 'resolved');

    const listedB = await readPushConflicts(actorB);
    assert.deepEqual(listedB, [], 'another account must never see the conflict');
    const foreignResolve = await resolvePushConflict(actorB, conflictId, 'keep-local');
    assert.equal(foreignResolve.status, 'not-found', 'another account must not resolve it');
    const foreignAck = await ackPushConflict(actorB, conflictId, resolved.resolutionId);
    assert.equal(foreignAck.status, 'not-found', 'another account must not acknowledge it');

    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => readPushConflicts(anon), /permission denied/);
    await assert.rejects(
      () => resolvePushConflict(anon, conflictId, 'keep-local'),
      /permission denied/,
    );
    await assert.rejects(
      () => ackPushConflict(anon, conflictId, resolved.resolutionId),
      /permission denied/,
    );
    await anon.end();

    const noClaims = await cluster.connectActor({ role: 'authenticated', sub: null });
    await assert.rejects(() => readPushConflicts(noClaims), /not authenticated/);
    await assert.rejects(
      () => resolvePushConflict(noClaims, conflictId, 'keep-local'),
      /not authenticated/,
    );
    await assert.rejects(
      () => ackPushConflict(noClaims, conflictId, resolved.resolutionId),
      /not authenticated/,
    );
    await noClaims.end();
    await actorA.end();
    await actorB.end();
  });

  test('push conflict resolution keeps invalid payloads and revoked devices out of resolvable choices', async () => {
    const owner = '00000000-0000-0000-0000-00000000c00a';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    const bad = op({ item: item({ id: 'item-badpayload-01', quantity: 1_000_001 }) });
    const badPush = (await push(actor, [bad])).rows[0].result;
    const badOutcome = outcome(badPush, bad.opId);
    assert.equal(badOutcome.status, 'rejected');
    assert.equal(badOutcome.reason, 'invalid-payload');
    assert.ok(badOutcome.conflictId, 'permanent errors keep their visible conflict record');
    assert.equal(badOutcome.cloudItem, null);

    assert.equal(
      (await resolvePushConflict(actor, badOutcome.conflictId, 'keep-local')).status,
      'invalid-resolution',
      'an invalid payload must never offer a resolvable choice',
    );
    assert.equal(
      (await resolvePushConflict(actor, badOutcome.conflictId, 'dismiss')).status,
      'invalid-resolution',
      'unknown choices are refused as statuses, not applied',
    );

    const deviceId = 'device-rv1-000000000000000000';
    await push(actor, [op({ item: item({ id: 'item-revoke-push-01' }) })], deviceId);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-revoke-push-01', quantity: 2 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-revoke-push-01', quantity: 7 }),
      baseRevision: 1,
    });
    const conflictPush = (await push(actor, [stale], deviceId)).rows[0].result;
    const conflictId = outcome(conflictPush, stale.opId).conflictId;
    await cluster.admin.query(
      `update public.sync_devices set revoked_at = now()
       where device_id = $1
         and collection_id = (select id from public.collections where owner = $2::uuid)`,
      [deviceId, owner],
    );

    const revoked = await resolvePushConflict(actor, conflictId, 'keep-local', deviceId);
    assert.equal(revoked.status, 'device-revoked', 'a revoked device must reauthorize instead of choosing');
    const listed = await readPushConflicts(actor);
    assert.equal(
      listed.find((row) => row.id === conflictId).resolution,
      null,
      'a revoked device must not record a choice',
    );
    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision
       from public.collection_items where item_id = 'item-revoke-push-01'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 2, revision: 2 }], 'cloud state stays untouched');
    await actor.end();
  });

  test('durable push conflict rejects a delete that carries a second item id', async () => {
    const owner = '00000000-0000-0000-0000-00000000c00b';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-craft-target-1' }) })]);
    await push(actor, [op({ item: item({ id: 'item-craft-victim-1' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-craft-target-1', quantity: 4 }), baseRevision: 1 }),
    ]);

    const crafted = {
      opId: randomUUID(),
      kind: 'delete',
      signature: randomUUID(),
      itemId: 'item-craft-target-1',
      baseRevision: 1,
      item: item({ id: 'item-craft-victim-1' }),
    };
    const pushed = (await push(actor, [crafted])).rows[0].result;
    const rejection = outcome(pushed, crafted.opId);
    assert.equal(rejection.status, 'rejected', 'a delete carrying a nested item is malformed');
    assert.equal(rejection.reason, 'invalid-payload');
    assert.equal(
      rejection.cloudItem.itemId,
      'item-craft-target-1',
      'the snapshot must follow itemId for deletes, not a crafted nested id',
    );

    const refused = await resolvePushConflict(actor, rejection.conflictId, 'keep-local');
    assert.equal(refused.status, 'invalid-resolution', 'the resolver refuses the forbidden nested item');

    // A legacy-shaped row that already carries the crafted payload must also stay inert.
    const collection = await cluster.admin.query(
      'select id from public.collections where owner = $1::uuid',
      [owner],
    );
    const seededId = randomUUID();
    const seeded = { ...crafted, opId: randomUUID() };
    await cluster.admin.query(
      `insert into public.sync_conflicts
         (id, collection_id, op_id, device_id, item_id, reason, payload, owner)
       values ($1, $2, $3::uuid, $4, 'item-craft-target-1', 'stale', $5, $6)`,
      [seededId, collection.rows[0].id, seeded.opId, DEVICE_A1, JSON.stringify(seeded), owner],
    );
    const seededResolve = await resolvePushConflict(actor, seededId, 'keep-local');
    assert.equal(
      seededResolve.status,
      'invalid-resolution',
      'a stale row with a crafted nested delete must not tombstone the other item',
    );

    const items = await cluster.admin.query(
      `select item_id, revision::int as revision, deleted from public.collection_items
       where item_id in ('item-craft-target-1', 'item-craft-victim-1')
       order by item_id`,
    );
    assert.deepEqual(items.rows, [
      { item_id: 'item-craft-target-1', revision: 2, deleted: false },
      { item_id: 'item-craft-victim-1', revision: 1, deleted: false },
    ], 'neither the target nor the unrelated victim may be mutated');
    const logged = await cluster.admin.query(
      `select count(*)::int as n from public.collection_operations
       where op_id = any($1::uuid[])`,
      [[crafted.opId, seeded.opId]],
    );
    assert.equal(logged.rows[0].n, 0, 'no operation may be logged for the crafted deletes');
    await actor.end();
  });

  test('push conflict resolution refuses an id-collision payload with a malformed item id', async () => {
    const owner = '00000000-0000-0000-0000-00000000c00c';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    const applied = op({ item: item({ id: 'item-collision-ok-1' }) });
    await push(actor, [applied]);

    const forged = {
      opId: applied.opId,
      kind: 'create',
      signature: randomUUID(),
      item: {
        id: 'bad id!!',
        cardId: 'card-bad-000001',
        quantity: 1,
        variant: 'NORMAL',
        condition: 'NEAR_MINT',
        pricePaid: null,
        addedAt: 1_700_000_000_000,
      },
    };
    const pushed = (await push(actor, [forged])).rows[0].result;
    const conflict = outcome(pushed, forged.opId);
    assert.equal(conflict.status, 'conflict');
    assert.equal(conflict.reason, 'id-collision');

    const refused = await resolvePushConflict(actor, conflict.conflictId, 'keep-local');
    assert.equal(
      refused.status,
      'invalid-resolution',
      'a malformed item id must surface as a typed refusal, never a DB CHECK violation',
    );
    assert.equal(refused.reason, 'invalid-payload');

    const malformed = await cluster.admin.query(
      `select item_id from public.collection_items where item_id = 'bad id!!'`,
    );
    assert.deepEqual(malformed.rows, [], 'no row may be written under the malformed id');
    const stored = await cluster.admin.query(
      `select quantity::int as quantity, revision::int as revision
       from public.collection_items where item_id = 'item-collision-ok-1'`,
    );
    assert.deepEqual(stored.rows, [{ quantity: 1, revision: 1 }], 'cloud state stays untouched');
    const operations = await cluster.admin.query(
      `select count(*)::int as n from public.collection_operations o
       join public.collections c on c.id = o.collection_id
       where c.owner = $1::uuid`,
      [owner],
    );
    assert.equal(operations.rows[0].n, 1, 'a refused resolution must not append operations');
    await actor.end();
  });

  test('durable push conflict keeps malformed operation retries bounded and permanent', async () => {
    const owner = '00000000-0000-0000-0000-00000000c00d';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    const malformed = { kind: 'create', signature: randomUUID(), item: item({ id: 'item-malformed-01' }) };
    let stableConflictId;

    for (let attempt = 0; attempt < 3; attempt += 1) {
      const pushed = (await push(actor, [malformed])).rows[0].result;
      const outcomeRow = pushed.results[0];
      assert.equal(outcomeRow.opId, null, 'the malformed op id is echoed as null');
      assert.equal(
        outcomeRow.status,
        'rejected',
        'permanent errors stay distinguishable from content conflicts',
      );
      assert.equal(outcomeRow.reason, 'invalid-payload');
      assert.ok(outcomeRow.conflictId);
      if (stableConflictId === undefined) stableConflictId = outcomeRow.conflictId;
      assert.equal(
        outcomeRow.conflictId,
        stableConflictId,
        'a malformed retry must reuse its record instead of spawning rows',
      );
    }

    const openRows = await cluster.admin.query(
      `select count(*)::int as n from public.sync_conflicts
       where owner = $1::uuid and op_id is null and resolved_at is null`,
      [owner],
    );
    assert.equal(openRows.rows[0].n, 1, 'malformed retries must not grow the open set');

    const refused = await resolvePushConflict(actor, stableConflictId, 'keep-local');
    assert.equal(refused.status, 'invalid-resolution', 'permanent errors stay non-resolvable');
    const visible = await readPushConflicts(actor);
    const entry = visible.find((row) => row.id === stableConflictId);
    assert.ok(entry, 'a permanent error must remain visible, never silently closed');
    assert.equal(entry.reason, 'invalid-payload');
    await actor.end();
  });
});

describe('legacy conflict resolver fence', () => {
  test('legacy conflict resolver cannot close an unacknowledged receipt or push conflict', async () => {
    const owner = '00000000-0000-0000-0000-00000000c00e';
    const actor = await cluster.connectActor({ role: 'authenticated', sub: owner });
    await push(actor, [op({ item: item({ id: 'item-legacy-fence-1' }) })]);
    await push(actor, [
      op({ kind: 'replace', item: item({ id: 'item-legacy-fence-1', quantity: 3 }), baseRevision: 1 }),
    ]);
    const stale = op({
      kind: 'replace',
      item: item({ id: 'item-legacy-fence-1', quantity: 9 }),
      baseRevision: 1,
    });
    const pushed = (await push(actor, [stale])).rows[0].result;
    const conflictId = outcome(pushed, stale.opId).conflictId;

    const legacyList = await readLegacyConflicts(actor);
    assert.deepEqual(legacyList.map((row) => row.id), [], 'the legacy read must not see push-protocol rows');

    const legacyAttempt = await resolveLegacyConflict(actor, conflictId, 'keep-remote');
    assert.equal(legacyAttempt.status, 'resolved', 'legacy callers keep their idempotent statuses');
    assert.equal(
      (await conflictRowState(conflictId)).resolved_at,
      null,
      'the legacy resolver must never close a push conflict',
    );
    const stillListed = await readPushConflicts(actor);
    assert.ok(
      stillListed.find((row) => row.id === conflictId),
      'the push conflict stays owned by the protocol after a legacy attempt',
    );

    const resolved = await resolvePushConflict(actor, conflictId, 'keep-cloud');
    assert.equal(resolved.status, 'resolved');
    const secondLegacy = await resolveLegacyConflict(actor, conflictId, 'keep-local');
    assert.equal(secondLegacy.status, 'already-resolved');
    const receiptState = await conflictRowState(conflictId);
    assert.equal(receiptState.resolved_at, null, 'an unacknowledged receipt must stay open');
    assert.equal(
      receiptState.resolution_id,
      resolved.resolutionId,
      'the recorded receipt is untouched by the legacy resolver',
    );
    const replayList = await readPushConflicts(actor);
    assert.equal(
      replayList.find((row) => row.id === conflictId).resolution.id,
      resolved.resolutionId,
      'the receipt still replays after legacy attempts',
    );
    assert.equal(receiptState.source, 'push', 'new push rows carry the source discriminator');

    const legacyRowId = randomUUID();
    const collection = await cluster.admin.query(
      'select id from public.collections where owner = $1::uuid',
      [owner],
    );
    await cluster.admin.query(
      `insert into public.sync_conflicts (id, collection_id, item_id, reason, payload, owner)
       values ($1, $2, 'item-legacy-row-1', 'stale', '{"local":{},"remote":{}}', $3)`,
      [legacyRowId, collection.rows[0].id, owner],
    );
    const legacyOnly = await readLegacyConflicts(actor);
    assert.deepEqual(legacyOnly.map((row) => row.id), [legacyRowId], 'legacy rows keep their old read view');
    const legacyResolved = await resolveLegacyConflict(actor, legacyRowId, 'keep-remote');
    assert.equal(legacyResolved.status, 'resolved');
    assert.ok(
      (await conflictRowState(legacyRowId)).resolved_at,
      'legacy rows still close under the legacy resolver',
    );
    const afterLegacyClose = await readPushConflicts(actor);
    assert.equal(
      afterLegacyClose.find((row) => row.id === legacyRowId),
      undefined,
      'a closed legacy row leaves both views',
    );
    await actor.end();
  });
});

describe('duplicate open conflict migration safety', () => {
  test('duplicate open conflict migration consolidates retries without losing payloads', async () => {
    const seededCluster = await startCluster({ applyMigrations: false });
    try {
      for (const name of await listMigrations()) {
        if (name === DURABLE_PUSH_MIGRATION) continue;
        await seededCluster.admin.query(await readMigration(name));
      }

      const owner = '00000000-0000-0000-0000-00000000c00f';
      const collectionId = randomUUID();
      const operationId = randomUUID();
      const opWinnerId = randomUUID();
      const opLoserId = randomUUID();
      const malformedWinnerId = randomUUID();
      const malformedLoserId = randomUUID();
      const opWinnerPayload = { opId: '11111111-1111-1111-1111-111111111111', kind: 'replace' };
      const opLoserPayload = { opId: '22222222-2222-2222-2222-222222222222', kind: 'replace' };
      const malformedPayload = { kind: 'create', note: 'no operation id' };

      await seededCluster.admin.query(
        'insert into public.collections (id, owner) values ($1::uuid, $2::uuid)',
        [collectionId, owner],
      );
      await seededCluster.admin.query(
        `insert into public.sync_conflicts
           (id, collection_id, op_id, device_id, item_id, reason, payload, owner, created_at)
         values
           ($1::uuid, $2::uuid, $3::uuid, 'device-mig-a1-0000000000000000', 'item-migrate-01', 'stale', $4, $5, '2026-10-12 10:00:00+00'),
           ($6::uuid, $2::uuid, $3::uuid, 'device-mig-b1-0000000000000000', 'item-migrate-01', 'stale', $7, $5, '2026-10-12 11:00:00+00')`,
        [opWinnerId, collectionId, operationId, JSON.stringify(opWinnerPayload), owner, opLoserId, JSON.stringify(opLoserPayload)],
      );
      await seededCluster.admin.query(
        `insert into public.sync_conflicts
           (id, collection_id, op_id, device_id, item_id, reason, payload, owner, created_at)
         values
           ($1::uuid, $2::uuid, null, 'device-mig-a1-0000000000000000', null, 'invalid-payload', $3, $4, '2026-10-12 12:00:00+00'),
           ($5::uuid, $2::uuid, null, 'device-mig-b1-0000000000000000', null, 'invalid-payload', $3, $4, '2026-10-12 13:00:00+00')`,
        [malformedWinnerId, collectionId, JSON.stringify(malformedPayload), owner, malformedLoserId],
      );

      await seededCluster.admin.query(await readMigration(DURABLE_PUSH_MIGRATION));

      const rows = await seededCluster.admin.query(
        `select id, op_id, payload, resolved_at, superseded_by, source
         from public.sync_conflicts
         where collection_id = $1::uuid
         order by created_at`,
        [collectionId],
      );
      assert.equal(rows.rows.length, 4, 'historical conflict rows are preserved, never deleted');
      const [opWinner, opLoser, malformedWinner, malformedLoser] = rows.rows;
      assert.deepEqual(opWinner.payload, opWinnerPayload, 'the canonical payload survives');
      assert.deepEqual(opLoser.payload, opLoserPayload, 'the superseded payload survives');
      assert.deepEqual(malformedWinner.payload, malformedPayload);
      assert.deepEqual(malformedLoser.payload, malformedPayload);
      assert.equal(opWinner.id, opWinnerId, 'the oldest open row wins deterministically');
      assert.equal(opWinner.resolved_at, null, 'the canonical conflict stays open and visible');
      assert.equal(opWinner.superseded_by, null);
      assert.ok(opLoser.resolved_at, 'the duplicate retry is closed as superseded');
      assert.equal(opLoser.superseded_by, opWinnerId, 'the pointer names the canonical row');
      assert.equal(malformedWinner.id, malformedWinnerId);
      assert.equal(malformedWinner.resolved_at, null);
      assert.ok(malformedLoser.resolved_at);
      assert.equal(malformedLoser.superseded_by, malformedWinnerId);
      assert.equal(opWinner.source, 'legacy', 'pre-existing rows are classified as legacy');

      const indexes = await seededCluster.admin.query(
        `select indexname from pg_indexes
         where tablename = 'sync_conflicts'
           and indexname in ('sync_conflicts_open_operation', 'sync_conflicts_open_malformed')
         order by indexname`,
      );
      assert.deepEqual(
        indexes.rows.map((row) => row.indexname),
        ['sync_conflicts_open_malformed', 'sync_conflicts_open_operation'],
        'both uniqueness guards exist after consolidation',
      );

      await assert.rejects(
        () =>
          seededCluster.admin.query(
            `insert into public.sync_conflicts (collection_id, op_id, reason, payload, owner)
             values ($1::uuid, $2::uuid, 'stale', '{"opId":"33333333-3333-3333-3333-333333333333"}', $3::uuid)`,
            [collectionId, operationId, owner],
          ),
        /duplicate key/,
        'the operation-id uniqueness guard is enforced',
      );
      await assert.rejects(
        () =>
          seededCluster.admin.query(
            `insert into public.sync_conflicts (collection_id, op_id, reason, payload, owner)
             values ($1::uuid, null, 'invalid-payload', $2, $3::uuid)`,
            [collectionId, JSON.stringify(malformedPayload), owner],
          ),
        /duplicate key/,
        'the malformed-payload uniqueness guard is enforced',
      );
    } finally {
      await seededCluster.stop();
    }
  });
});
