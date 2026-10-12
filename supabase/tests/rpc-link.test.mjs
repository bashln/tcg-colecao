import test, { after, before, describe } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { startCluster } from './helpers/platform.mjs';

const DEVICE = 'device-link-00000000000000000';

let cluster;

function item(id, overrides = {}) {
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

function createOp(syncItem) {
  return { opId: randomUUID(), kind: 'create', signature: randomUUID(), item: syncItem };
}

async function push(actor, operations, deviceId = DEVICE) {
  return actor.query('select public.push_collection_operations($1::text, $2::jsonb) as result', [
    deviceId,
    JSON.stringify(operations),
  ]);
}

async function prepare(actor, localItems, digest = `digest-${randomUUID()}`, remoteSequence = 0) {
  const response = await actor.query(
    'select public.prepare_collection_link($1::text, $2::jsonb, $3::bigint) as result',
    [digest, JSON.stringify(localItems), remoteSequence],
  );
  return { digest, result: response.rows[0].result };
}

async function commit(actor, planId, digest, operations, deviceId = DEVICE) {
  const response = await actor.query(
    'select public.commit_collection_link($1::uuid, $2::text, $3::text, $4::jsonb) as result',
    [planId, digest, deviceId, JSON.stringify(operations)],
  );
  return response.rows[0].result;
}

async function snapshot(actor, expectedHighWater) {
  const response = await actor.query(
    'select public.read_collection_link_snapshot($1::bigint) as result',
    [expectedHighWater],
  );
  return response.rows[0].result;
}

function newUser() {
  return `00000000-0000-0000-0000-${Math.random().toString(16).slice(2, 12).padEnd(12, '0')}`;
}

before(async () => {
  cluster = await startCluster();
});

after(async () => {
  await cluster?.stop();
});

describe('prepare_collection_link', () => {
  test('classifies local and remote items without touching the collection', async () => {
    const user = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    await push(actor, [
      createOp(item('item-rem-ident-1')),
      createOp(item('item-rem-conflict-1', { quantity: 4 })),
      createOp(item('item-rem-cloudonly-1')),
    ]);

    const before = await cluster.admin.query(
      `select (select count(*)::int from public.collection_operations o join public.collections c on c.id = o.collection_id where c.owner = $1::uuid) as ops,
              (select count(*)::int from public.collection_items i join public.collections c on c.id = i.collection_id where c.owner = $1::uuid) as items`,
      [user],
    );

    const local = [
      item('item-rem-ident-1'),
      item('item-rem-conflict-1', { quantity: 9 }),
      item('item-loc-only-1'),
      item('item-loc-dupcfg-1', { cardId: 'card-item-rem-cloudonly-1' }),
    ];
    const { result } = await prepare(actor, local);

    assert.ok(result.planId, 'preview must bind a plan id');
    assert.ok(result.expiresAt, 'preview must expire');
    const p = result.preview;
    assert.deepEqual(p.identical, ['item-rem-ident-1']);
    assert.deepEqual(p.conflicting.map((row) => row.itemId), ['item-rem-conflict-1']);
    assert.deepEqual(p.cloudOnly, ['item-rem-cloudonly-1']);
    assert.deepEqual(p.localOnly.sort(), ['item-loc-dupcfg-1', 'item-loc-only-1']);
    assert.deepEqual(p.duplicateConfigurations, [
      { localItemId: 'item-loc-dupcfg-1', remoteItemId: 'item-rem-cloudonly-1' },
    ]);

    const after = await cluster.admin.query(
      `select (select count(*)::int from public.collection_operations o join public.collections c on c.id = o.collection_id where c.owner = $1::uuid) as ops,
              (select count(*)::int from public.collection_items i join public.collections c on c.id = i.collection_id where c.owner = $1::uuid) as items`,
      [user],
    );
    assert.deepEqual(after.rows, before.rows, 'preview must not mutate items or operations');
    await actor.end();
  });

  test('rejects malformed preview items and oversized batches', async () => {
    const actor = await cluster.connectActor({ role: 'authenticated', sub: newUser() });
    await assert.rejects(
      () => prepare(actor, [item('item-bad-000001', { quantity: -3 })]),
      /invalid-preview/,
    );
    const many = Array.from({ length: 5001 }, (_, i) => item(`item-bulk-${String(i).padStart(6, '0')}`));
    await assert.rejects(() => prepare(actor, many), /invalid-preview/);
    await actor.end();
  });

  test('anon cannot prepare', async () => {
    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => prepare(anon, []), /permission denied/);
    await anon.end();
  });

  test('preview cleanup removes only the caller’s expired plans and is repeatable', async () => {
    const user = newUser();
    const otherUser = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    const otherActor = await cluster.connectActor({ role: 'authenticated', sub: otherUser });
    const { result: ownPlan } = await prepare(actor, [], 'digest-cleanup-own');
    const { result: foreignPlan } = await prepare(otherActor, [], 'digest-cleanup-foreign');
    await cluster.admin.query(
      `update public.link_plans set expires_at = now() - interval '2 days' where id = any($1::uuid[])`,
      [[ownPlan.planId, foreignPlan.planId]],
    );

    await prepare(actor, [], 'digest-cleanup-next');
    await prepare(actor, [], 'digest-cleanup-repeat');
    const remaining = await cluster.admin.query(
      'select id from public.link_plans where id = any($1::uuid[]) order by id',
      [[ownPlan.planId, foreignPlan.planId]],
    );
    assert.deepEqual(remaining.rows.map((row) => row.id), [foreignPlan.planId]);
    await actor.end();
    await otherActor.end();
  });
});

describe('commit_collection_link', () => {
  test('applies conflict-free seed operations idempotently and consumes the plan', async () => {
    const user = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    await push(actor, [createOp(item('item-rem-seed-001'))]);

    const local = [item('item-rem-seed-001'), item('item-loc-seed-0001')];
    const { digest, result: plan } = await prepare(actor, local);
    const seed = [createOp(item('item-loc-seed-0001'))];

    const first = await commit(actor, plan.planId, digest, seed);
    assert.equal(first.status, 'linked');
    assert.equal(first.push.results[0].status, 'applied');

    const stored = await cluster.admin.query(
      `select i.item_id from public.collection_items i
       join public.collections c on c.id = i.collection_id
       where c.owner = $1::uuid and i.item_id = 'item-loc-seed-0001'`,
      [user],
    );
    assert.equal(stored.rows.length, 1, 'seeded local-only item must exist remotely');

    const consumed = await cluster.admin.query(
      `select (consumed_at is not null) as consumed from public.link_plans where id = $1::uuid`,
      [plan.planId],
    );
    assert.equal(consumed.rows[0].consumed, true);

    const replayed = await commit(actor, plan.planId, digest, seed);
    assert.deepEqual(replayed, first, 'a retry must replay its durable terminal result');
    assert.equal(replayed.status, 'linked');
    const changed = await commit(actor, plan.planId, digest, [createOp(item('item-different-seed-01'))]);
    assert.equal(changed.status, 'payload-mismatch', 'a terminal plan cannot be rebound to another request');
    await actor.end();
  });

  test('returns partial when any seed operation is rejected and replays that result', async () => {
    const user = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    const existing = createOp(item('item-link-partial-01'));
    await push(actor, [existing]);
    const { digest, result: plan } = await prepare(actor, [item('item-link-partial-01', { quantity: 9 })]);
    const conflicting = {
      ...existing,
      signature: randomUUID(),
      item: item('item-link-partial-01', { quantity: 9 }),
    };

    const first = await commit(actor, plan.planId, digest, [conflicting]);
    assert.equal(first.status, 'partial');
    assert.equal(first.push.results[0].status, 'conflict');
    assert.equal(first.push.results[0].reason, 'id-collision');
    assert.deepEqual(await commit(actor, plan.planId, digest, [conflicting]), first);
    await actor.end();
  });

  test('concurrent identical commits return one shared terminal result', async () => {
    const user = newUser();
    const actorA = await cluster.connectActor({ role: 'authenticated', sub: user });
    const actorB = await cluster.connectActor({ role: 'authenticated', sub: user });
    const local = [item('item-link-race-0001')];
    const { digest, result: plan } = await prepare(actorA, local);
    const seed = [createOp(local[0])];

    const [first, concurrent] = await Promise.all([
      commit(actorA, plan.planId, digest, seed),
      commit(actorB, plan.planId, digest, seed),
    ]);
    assert.equal(first.status, 'linked');
    assert.deepEqual(concurrent, first);
    const count = await cluster.admin.query(
      `select count(*)::int as count from public.collection_operations o
       join public.collections c on c.id = o.collection_id
       where c.owner = $1::uuid and o.item_id = 'item-link-race-0001'`,
      [user],
    );
    assert.equal(count.rows[0].count, 1);
    await actorA.end();
    await actorB.end();
  });

  test('rejects digest mismatch, expiry and stale remote without writes', async () => {
    const user = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    await push(actor, [createOp(item('item-rem-guard-01'))]);

    const local = [item('item-rem-guard-01')];
    const { result: plan } = await prepare(actor, local, 'digest-original-1');

    const wrongDigest = await commit(actor, plan.planId, 'digest-other-1234', []);
    assert.equal(wrongDigest.status, 'digest-mismatch');

    await push(actor, [createOp(item('item-rem-guard-02'))]);
    const stale = await commit(actor, plan.planId, 'digest-original-1', []);
    assert.equal(stale.status, 'stale-preview', 'remote movement must invalidate the preview');

    const { result: expiredPlan } = await prepare(actor, local, 'digest-expired-1');
    await cluster.admin.query(
      `update public.link_plans set expires_at = now() - interval '1 second' where id = $1::uuid`,
      [expiredPlan.planId],
    );
    const expired = await commit(actor, expiredPlan.planId, 'digest-expired-1', []);
    assert.equal(expired.status, 'expired');

    const unknown = await commit(actor, randomUUID(), 'digest-x-1234567', []);
    assert.equal(unknown.status, 'not-found');
    await actor.end();
  });

  test('anon cannot commit', async () => {
    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(
      () => commit(anon, randomUUID(), 'digest-x-1234567', []),
      /permission denied/,
    );
    await anon.end();
  });
});

describe('read_collection_link_snapshot', () => {
  test('returns only active rows owned by the authenticated user at expected sequence', async () => {
    const user = newUser();
    const otherUser = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    const otherActor = await cluster.connectActor({ role: 'authenticated', sub: otherUser });
    await push(actor, [createOp(item('item-snapshot-0001', { pricePaid: { amount: 1234, currency: 'USD' } }))]);

    const result = await snapshot(actor, 1);
    const isolated = await snapshot(otherActor, 0);

    assert.equal(result.highWater, 1);
    assert.deepEqual(result.items, [{
      id: 'item-snapshot-0001', cardId: 'card-item-snapshot-0001', quantity: 1,
      variant: 'NORMAL', condition: 'NEAR_MINT',
      pricePaid: { amount: 1234, currency: 'USD' }, addedAt: 1_700_000_000_000, revision: 1,
    }]);
    assert.deepEqual(isolated, { highWater: 0, items: [] });
    await assert.rejects(() => snapshot(actor, 0), /stale-preview/);
    await actor.end();
    await otherActor.end();
  });

  test('anon cannot read a collection snapshot', async () => {
    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => snapshot(anon, 0), /permission denied/);
    await anon.end();
  });
});

describe('resolve_collection_conflict', () => {
  test('resolves owned conflicts idempotently and cannot touch another account', async () => {
    const user = newUser();
    const other = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    const otherActor = await cluster.connectActor({ role: 'authenticated', sub: other });

    const create = createOp(item('item-conf-res-001'));
    await push(actor, [create]);
    const colliding = { ...create, signature: randomUUID(), item: item('item-conf-res-001', { quantity: 7 }) };
    await push(actor, [colliding]);

    const open = await actor.query(
      `select id from public.sync_conflicts where reason = 'id-collision' limit 1`,
    );
    // RLS hides rows from direct reads only via policy; conflicts are readable by owner.
    let conflictId = open.rows[0]?.id;
    if (conflictId === undefined) {
      const viaAdmin = await cluster.admin.query(
        `select sc.id from public.sync_conflicts sc join public.collections c on c.id = sc.collection_id
         where c.owner = $1::uuid and sc.reason = 'id-collision' limit 1`,
        [user],
      );
      conflictId = viaAdmin.rows[0].id;
    }

    const resolved = await actor.query(
      'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
      [conflictId, 'keep-remote', DEVICE],
    );
    assert.equal(resolved.rows[0].result.status, 'resolved');

    const again = await actor.query(
      'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
      [conflictId, 'keep-remote', DEVICE],
    );
    assert.equal(again.rows[0].result.status, 'already-resolved');

    const crossAccount = await otherActor.query(
      'select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result',
      [conflictId, 'keep-remote', DEVICE],
    );
    assert.equal(crossAccount.rows[0].result.status, 'not-found', 'foreign conflicts stay untouchable');

    await assert.rejects(
      () => actor.query('select public.resolve_collection_conflict($1::uuid, $2::text, $3::text) as result', [conflictId, 'nonsense', DEVICE]),
      /invalid-resolution/,
    );
    await actor.end();
    await otherActor.end();
  });
});

describe('revoke_sync_device', () => {
  test('revoked devices cannot push while other devices continue', async () => {
    const user = newUser();
    const actor = await cluster.connectActor({ role: 'authenticated', sub: user });
    await push(actor, [createOp(item('item-revoke-0001'))]);

    const revoked = await actor.query('select public.revoke_sync_device($1::text) as result', [DEVICE]);
    assert.equal(revoked.rows[0].result.status, 'revoked');

    const blocked = await push(actor, [createOp(item('item-revoke-0002'))]);
    assert.equal(blocked.rows[0].result.status, 'device-revoked');
    const missing = await cluster.admin.query(
      `select i.item_id from public.collection_items i
       join public.collections c on c.id = i.collection_id
       where c.owner = $1::uuid and i.item_id = 'item-revoke-0002'`,
      [user],
    );
    assert.deepEqual(missing.rows, [], 'revoked device must not write');

    const other = await push(actor, [createOp(item('item-revoke-0003'))], 'device-link-9999999999999999');
    assert.equal(other.rows[0].result.results[0].status, 'applied', 'other devices keep working');

    const again = await actor.query('select public.revoke_sync_device($1::text) as result', [DEVICE]);
    assert.equal(again.rows[0].result.status, 'already-revoked');

    const foreign = await actor.query('select public.revoke_sync_device($1::text) as result', ['device-unknown-00000000000']);
    assert.equal(foreign.rows[0].result.status, 'not-found');
    await actor.end();
  });

  test('anon cannot revoke', async () => {
    const anon = await cluster.connectActor({ role: 'anon', sub: null });
    await assert.rejects(() => anon.query('select public.revoke_sync_device($1::text) as result', [DEVICE]), /permission denied/);
    await anon.end();
  });
});
