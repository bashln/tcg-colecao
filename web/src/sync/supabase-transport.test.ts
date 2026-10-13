import assert from 'node:assert/strict'
import test from 'node:test'
import { parseCardId, parseItemId } from '../domain/model.ts'
import { createSupabaseTransport, SupabaseSyncError, type SupabaseHttp } from './supabase-transport.ts'
import type { CollectionOperation } from './policy.ts'

const itemId = parseItemId('00000000-0000-4000-8000-000000000001')!
const cardId = parseCardId('base1-4')!

function createOp(opId: string, deviceId: string, quantity = 2): Extract<CollectionOperation, { kind: 'create' }> {
  return {
    kind: 'create',
    opId,
    deviceId,
    item: {
      id: itemId,
      cardId,
      quantity,
      variant: 'NORMAL',
      condition: 'NEAR_MINT',
      pricePaid: null,
      addedAt: 1_791_667_200_000,
    },
  }
}

interface RecordedCall {
  fn: string
  body: Record<string, unknown>
  expectedUserId?: string
}

function httpStub(
  responses: Record<string, unknown> | ((call: RecordedCall) => unknown),
  record: RecordedCall[] = [],
): { http: SupabaseHttp; record: RecordedCall[] } {
  const http: SupabaseHttp = {
    async rpc(fn: string, body: Record<string, unknown>, expectedUserId?: string) {
      const call = { fn, body, expectedUserId }
      record.push(call)
      return typeof responses === 'function' ? responses(call) : (responses[fn] ?? {})
    },
    async user() {
      return { id: '00000000-0000-4000-8000-0000000000aa', email: 'a@example.com' }
    },
  }
  return { http, record }
}

test('link preview and commit pin every RPC to the preview identity', async () => {
  const { http, record } = httpStub({
    prepare_collection_link: {
      planId: '9f86d081-884c-4d65-9c67-41b405d56001', expiresAt: '2026-10-11T20:00:00Z',
      remoteHighWater: 12, preview: { localOnly: [], cloudOnly: [], identical: [], conflicting: [], duplicateConfigurations: [] },
    },
    read_collection_link_snapshot: { highWater: 12, items: [] },
    commit_collection_link: { status: 'linked', push: { status: 'ok', results: [], highWater: 12 } },
  })
  const transport = createSupabaseTransport({ http })
  const plan = await transport.prepareLink([], 'user-a')
  await transport.commitLink(plan, 'device-link-0001', [], 'user-a')

  assert.deepEqual(record.map((call) => call.expectedUserId), ['user-a', 'user-a', 'user-a'])
})

test('push serializes operations and maps per-operation server outcomes', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001')
  const { http, record } = httpStub({
    push_collection_operations: {
      status: 'ok',
      results: [{ opId: operation.opId, status: 'applied', reason: null, revision: 1 }],
      highWater: 1,
    },
  })
  const transport = createSupabaseTransport({ http })

  const outcomes = await transport.push('account-a', [operation])

  assert.equal(record.length, 1)
  assert.equal(record[0]?.fn, 'push_collection_operations')
  assert.equal(record[0]?.body.p_device_id, operation.deviceId)
  const sent = record[0]?.body.p_operations as Array<Record<string, unknown>>
  assert.equal(sent.length, 1)
  assert.equal(sent[0]?.opId, operation.opId)
  assert.equal(sent[0]?.kind, 'create')
  assert.ok(sent[0]?.signature, 'client must sign each operation for replay detection')
  assert.equal(sent[0]?.item && (sent[0].item as Record<string, unknown>).quantity, 2)

  assert.equal(outcomes.length, 1)
  assert.equal(outcomes[0]?.kind, 'applied')
})

test('push converts purchase money to minor units before sending it', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001')
  if (operation.kind !== 'create') throw new Error('expected create operation')
  operation.item.pricePaid = { amount: 12.34, currency: 'USD' }
  const { http, record } = httpStub({ push_collection_operations: { status: 'ok', results: [], highWater: 0 } })

  await createSupabaseTransport({ http }).push('account-a', [operation])

  const operations = record[0]?.body.p_operations as Array<Record<string, unknown>>
  const item = operations[0]?.item as Record<string, unknown>
  assert.deepEqual(item.pricePaid, { amount: 1234, currency: 'USD' })
})

test('push refuses purchase money with precision the database cannot store', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001')
  if (operation.kind !== 'create') throw new Error('expected create operation')
  operation.item.pricePaid = { amount: 12.345, currency: 'USD' }
  const { http, record } = httpStub({ push_collection_operations: { status: 'ok', results: [], highWater: 0 } })

  await assert.rejects(() => createSupabaseTransport({ http }).push('account-a', [operation]))
  assert.equal(record.length, 0)
})

test('push maps conflict, already-applied and server rejection distinctly', async () => {
  const base = createOp('10000000-0000-4000-8000-000000000011', '20000000-0000-4000-8000-000000000001')
  const { http } = httpStub({
    push_collection_operations: {
      status: 'ok',
      results: [
        { opId: base.opId, status: 'conflict', reason: 'stale', revision: null,
          conflictId: '30000000-0000-4000-8000-000000000001', highWater: 4,
          rejectedOperation: { kind: 'create', opId: base.opId, deviceId: base.deviceId, item: { ...base.item, pricePaid: null } },
          cloudItem: null, conflictingItem: null },
        { opId: base.opId, status: 'already-applied', reason: null, revision: 4 },
        { opId: base.opId, status: 'rejected', reason: 'invalid-payload', revision: null, conflictId: '30000000-0000-4000-8000-000000000002' },
      ],
      highWater: 4,
    },
  })
  const transport = createSupabaseTransport({ http })

  const outcomes = await transport.push('account-a', [base])

  assert.equal(outcomes[0]?.kind, 'conflict')
  assert.equal(outcomes[1]?.kind, 'already-applied')
  assert.equal(outcomes[2]?.kind, 'rejected', 'permanent server rejection is not a resolvable content conflict')
})

test('push conflict exposes validated cloud snapshot and permanent outcome stays distinct', async () => {
  const op = createOp('10000000-0000-4000-8000-000000000011', '20000000-0000-4000-8000-000000000001')
  const { http } = httpStub({ push_collection_operations: {
    status: 'ok', highWater: 7, results: [
      { opId: op.opId, status: 'conflict', conflictId: '30000000-0000-4000-8000-000000000001', reason: 'stale', highWater: 7,
        rejectedOperation: { kind: 'create', opId: op.opId, deviceId: op.deviceId, item: { ...op.item, pricePaid: null } },
        cloudItem: { itemId, cardId, quantity: 3, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1_791_667_200_000, revision: 4, deleted: false },
        conflictingItem: null },
      { opId: op.opId, status: 'rejected', reason: 'invalid-payload', conflictId: '30000000-0000-4000-8000-000000000002', highWater: 7 },
    ],
  } })

  const [conflict, rejected] = await createSupabaseTransport({ http }).push('account-a', [op])

  assert.equal(conflict?.kind, 'conflict')
  if (conflict?.kind !== 'conflict') throw new Error('expected content conflict')
  assert.equal(conflict.conflictId, '30000000-0000-4000-8000-000000000001')
  assert.equal(conflict.cloudItem?.kind, 'present')
  assert.equal(conflict.cloudItem?.item.quantity, 3)
  assert.equal(conflict.highWater, 7)
  assert.equal(rejected?.kind, 'rejected')
})

test('push conflict listing recovers same durable conflict after lost push response', async () => {
  const conflictId = '30000000-0000-4000-8000-000000000001'
  const { http, record } = httpStub({ read_collection_push_conflicts: [{
    id: conflictId, opId: '10000000-0000-4000-8000-000000000011', itemId, reason: 'stale',
    deviceId: '20000000-0000-4000-8000-000000000001',
    rejectedOperation: { kind: 'create', opId: '10000000-0000-4000-8000-000000000011', deviceId: '20000000-0000-4000-8000-000000000001', item: {
      id: itemId, cardId, quantity: 2, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1_791_667_200_000,
    } },
    cloudItem: { itemId, cardId, quantity: 3, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1_791_667_200_000, revision: 4, deleted: false },
    conflictingItem: null, highWater: 7, resolution: null,
  }] })

  const identity = '00000000-0000-4000-8000-0000000000aa'
  const conflicts = await createSupabaseTransport({ http }).listPushConflicts(identity)

  assert.equal(record[0]?.fn, 'read_collection_push_conflicts')
  assert.equal(record[0]?.expectedUserId, identity)
  assert.equal(conflicts[0]?.id, conflictId)
  assert.equal(conflicts[0]?.cloudItem?.kind, 'present')
})

test('push conflict resolution replays receipt and acknowledges local completion with identity pinning', async () => {
  const conflictId = '30000000-0000-4000-8000-000000000001'
  const resolutionId = '40000000-0000-4000-8000-000000000001'
  const { http, record } = httpStub({
    resolve_collection_push_conflict: { status: 'resolved', conflictId, choice: 'keep-cloud', resolutionId,
      result: { kind: 'keep-cloud', itemId, localItemId: itemId, cloudItem: null, conflictingItem: null } },
    ack_collection_push_conflict_resolution: { status: 'resolved', conflictId, choice: 'keep-cloud', resolutionId },
  })
  const transport = createSupabaseTransport({ http })

  const identity = '00000000-0000-4000-8000-0000000000aa'
  const receipt = await transport.resolvePushConflict({ conflictId, choice: 'keep-cloud', deviceId: '20000000-0000-4000-8000-000000000001', expectedIdentity: identity })
  const ack = await transport.acknowledgePushConflictResolution({ conflictId, resolutionId, expectedIdentity: identity })

  assert.equal(receipt.status, 'resolved')
  assert.equal(receipt.resolutionId, resolutionId)
  assert.equal(ack.status, 'resolved')
  assert.deepEqual(record.map((call) => call.expectedUserId), [identity, identity])
  assert.deepEqual(record[0]?.body, { p_conflict_id: conflictId, p_choice: 'keep-cloud', p_device_id: '20000000-0000-4000-8000-000000000001' })
  assert.deepEqual(record[1]?.body, { p_conflict_id: conflictId, p_resolution_id: resolutionId })
})

test('push conflict boundary rejects malformed snapshots and refuses dispatch after identity change', async () => {
  const bad = httpStub({ read_collection_push_conflicts: [{ id: 'bad', opId: null, itemId: 'bad', reason: 'stale', deviceId: 'bad',
    rejectedOperation: {}, cloudItem: { itemId: 'bad', deleted: false }, conflictingItem: null, highWater: -1, resolution: null }] })
  await assert.rejects(() => createSupabaseTransport({ http: bad.http }).listPushConflicts('00000000-0000-4000-8000-0000000000aa'),
    (error: unknown) => error instanceof SupabaseSyncError && error.kind === 'invalid-response')

  let calls = 0
  const changed: SupabaseHttp = {
    async rpc() { calls += 1; throw new Error('must not dispatch') },
    async user() { return { id: 'account-b', email: null } },
  }
  await assert.rejects(() => createSupabaseTransport({ http: changed }).resolvePushConflict({
    conflictId: '30000000-0000-4000-8000-000000000001', choice: 'keep-local',
    deviceId: '20000000-0000-4000-8000-000000000001', expectedIdentity: 'account-a',
  }))
  assert.equal(calls, 0)
})

test('push surfaces a revoked device and anonymous denial without retry-looping', async () => {
  for (const response of [
    { status: 'device-revoked', results: [], highWater: 0 },
    { status: 'unauthorized', message: 'permission denied for function push_collection_operations' },
  ]) {
    const { http } = httpStub({ push_collection_operations: response })
    const transport = createSupabaseTransport({ http })
    await assert.rejects(() => transport.push('account-a', [createOp('10000000-0000-4000-8000-000000000021', '20000000-0000-4000-8000-000000000001')]))
  }
})

test('pull requests the cursor page and returns ordered operations with high water mark', async () => {
  const { http, record } = httpStub({
    pull_collection_operations: {
      operations: [
        {
          opId: '10000000-0000-4000-8000-000000000031',
          serverSequence: 7,
          deviceId: '20000000-0000-4000-8000-000000000001',
          itemId: '00000000-0000-4000-8000-000000000001',
          kind: 'create',
          baseRevision: null,
          item: {
            id: '00000000-0000-4000-8000-000000000001',
            cardId: 'base1-4',
            quantity: 5,
            variant: 'NORMAL',
            condition: 'NEAR_MINT',
            pricePaid: null,
            addedAt: 1_791_667_200_000,
          },
        },
        {
          opId: '10000000-0000-4000-8000-000000000032',
          serverSequence: 8,
          deviceId: '20000000-0000-4000-8000-000000000001',
          itemId: '00000000-0000-4000-8000-000000000001',
          kind: 'delete',
          baseRevision: 1,
          item: null,
        },
      ],
      highWater: 8,
    },
  })
  const transport = createSupabaseTransport({ http })

  const page = await transport.pull('account-a', 3)

  assert.equal(record[0]?.fn, 'pull_collection_operations')
  assert.equal(record[0]?.body.p_after_sequence, 3)
  assert.equal(page.highWaterMark, 8)
  assert.equal(page.operations.length, 2)

  const create = page.operations[0]
  assert.ok(create && create.sequence === 7)
  assert.equal(create?.operation.kind, 'create')
  assert.equal(create?.operation.opId, '10000000-0000-4000-8000-000000000031')

  const remove = page.operations[1]
  assert.ok(remove && remove.sequence === 8)
  assert.equal(remove?.operation.kind, 'delete')
  assert.ok(remove && remove.operation.kind === 'delete' && remove.operation.baseRevision === 1)
})

test('pull converts remote minor units back to purchase money', async () => {
  const { http } = httpStub({
    pull_collection_operations: {
      operations: [{
        opId: '10000000-0000-4000-8000-000000000004',
        serverSequence: 1,
        deviceId: '20000000-0000-4000-8000-000000000001',
        itemId: '00000000-0000-4000-8000-000000000001',
        kind: 'create',
        baseRevision: null,
        item: {
          id: '00000000-0000-4000-8000-000000000001',
          cardId: 'base1-4',
          quantity: 1,
          variant: 'NORMAL',
          condition: 'NEAR_MINT',
          pricePaid: { amount: 1234, currency: 'USD' },
          addedAt: 1_791_667_200_000,
        },
      }],
      highWater: 1,
    },
  })

  const page = await createSupabaseTransport({ http }).pull('account-a', 0)
  const operation = page.operations[0]?.operation

  assert.ok(operation?.kind === 'create')
  assert.deepEqual(operation.item.pricePaid, { amount: 12.34, currency: 'USD' })
})

test('prepareLink sends a canonical local snapshot in minor units and parses the server preview', async () => {
  const cloudItemId = parseItemId('00000000-0000-4000-8000-000000000002')!
  const { http, record } = httpStub({
    prepare_collection_link: {
      planId: '9f86d081-884c-4d65-9c67-41b405d56001',
      expiresAt: '2026-10-11T20:00:00Z',
      remoteHighWater: 12,
      preview: {
        localOnly: [itemId], cloudOnly: [cloudItemId], identical: [], conflicting: [], duplicateConfigurations: [],
      },
    },
    read_collection_link_snapshot: { highWater: 12, items: [{
      id: cloudItemId, cardId, quantity: 1, variant: 'NORMAL', condition: 'NEAR_MINT',
      pricePaid: { amount: 1234, currency: 'USD' }, addedAt: 1_791_667_200_000, revision: 3,
    }] },
  })
  const transport = createSupabaseTransport({ http })
  const snapshot = [{
    id: itemId, cardId, quantity: 2, variant: 'NORMAL' as const, condition: 'NEAR_MINT' as const,
    pricePaid: { amount: 12.34, currency: 'USD' as const }, addedAt: 1_791_667_200_000,
  }]

  const plan = await transport.prepareLink(snapshot)

  assert.equal(record[0]?.fn, 'prepare_collection_link')
  assert.deepEqual(Object.keys(record[0]?.body ?? {}).sort(), ['p_local_digest', 'p_local_items'])
  assert.equal(record[1]?.fn, 'read_collection_link_snapshot')
  assert.equal(record[1]?.body.p_expected_high_water, 12)
  const bodyItems = record[0]?.body.p_local_items as Array<Record<string, unknown>>
  assert.deepEqual(bodyItems[0]?.pricePaid, { amount: 1234, currency: 'USD' })
  assert.equal(typeof record[0]?.body.p_local_digest, 'string')
  assert.equal(plan.localOnly[0], itemId)
  assert.equal(plan.cloudOnly[0], cloudItemId)
  assert.equal(plan.remoteHighWater, 12)
  assert.deepEqual(plan.remoteItems[0], {
    id: cloudItemId, cardId, quantity: 1, variant: 'NORMAL', condition: 'NEAR_MINT',
    pricePaid: { amount: 12.34, currency: 'USD' }, addedAt: 1_791_667_200_000, revision: 3,
  })
})

test('prepareLink refuses malformed preview data instead of inventing an empty plan', async () => {
  const { http } = httpStub({ prepare_collection_link: { preview: { localOnly: 'not-an-array' } } })

  await assert.rejects(() => createSupabaseTransport({ http }).prepareLink([]))
})

test('prepareLink refuses a remote snapshot from a different high-water mark', async () => {
  const { http } = httpStub({
    prepare_collection_link: {
      planId: '9f86d081-884c-4d65-9c67-41b405d56001',
      expiresAt: '2026-10-11T20:00:00Z',
      remoteHighWater: 12,
      preview: { localOnly: [], cloudOnly: [], identical: [], conflicting: [], duplicateConfigurations: [] },
    },
    read_collection_link_snapshot: { highWater: 13, items: [] },
  })

  await assert.rejects(() => createSupabaseTransport({ http }).prepareLink([]), /mudou durante a prévia/)
})

test('commitLink sends signed seed operations only when explicitly called and reports server result', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000099', '20000000-0000-4000-8000-000000000099')
  const { http, record } = httpStub({
    commit_collection_link: {
      status: 'linked',
      push: {
        status: 'ok',
        results: [{ opId: operation.opId, status: 'applied', revision: 1 }],
        highWater: 4,
      },
    },
  })
  const transport = createSupabaseTransport({ http })
  const plan = {
    planId: '9f86d081-884c-4d65-9c67-41b405d56001', digest: 'digest-12345678',
    expiresAt: '2026-10-11T20:00:00Z', remoteHighWater: 3,
    localOnly: [itemId], cloudOnly: [], identical: [], conflicting: [], duplicateConfigurations: [], remoteItems: [],
  }

  const result = await transport.commitLink(plan, operation.deviceId, [operation])

  assert.equal(record[0]?.fn, 'commit_collection_link')
  assert.equal(record[0]?.body.p_plan_id, plan.planId)
  assert.equal(record[0]?.body.p_digest, plan.digest)
  assert.equal(record[0]?.body.p_device_id, operation.deviceId)
  const sent = record[0]?.body.p_operations as Array<Record<string, unknown>>
  assert.equal(sent[0]?.opId, operation.opId)
  assert.equal(typeof sent[0]?.signature, 'string')
  assert.deepEqual(result, {
    kind: 'linked', highWater: 4,
    outcomes: [{ kind: 'applied', sequence: 1, opId: operation.opId }],
  })
})

test('commitLink preserves structured partial outcomes instead of reporting linked', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000099', '20000000-0000-4000-8000-000000000099')
  const { http } = httpStub({
    commit_collection_link: {
      status: 'partial',
      push: {
        status: 'ok',
        results: [{ opId: operation.opId, status: 'conflict', reason: 'id-collision', revision: null,
          conflictId: '30000000-0000-4000-8000-000000000001', highWater: 4,
          rejectedOperation: { kind: 'create', opId: operation.opId, deviceId: operation.deviceId, item: { ...operation.item, pricePaid: null } },
          cloudItem: null, conflictingItem: null }],
        highWater: 4,
      },
    },
  })
  const plan = {
    planId: '9f86d081-884c-4d65-9c67-41b405d56001', digest: 'digest-12345678',
    expiresAt: '2026-10-11T20:00:00Z', remoteHighWater: 3,
    localOnly: [itemId], cloudOnly: [], identical: [], conflicting: [], duplicateConfigurations: [], remoteItems: [],
  }

  const result = await createSupabaseTransport({ http }).commitLink(plan, operation.deviceId, [operation])

  assert.deepEqual(result, {
    kind: 'partial', highWater: 4,
    outcomes: [{ kind: 'conflict', reason: 'id-collision', opId: operation.opId, conflictId: '30000000-0000-4000-8000-000000000001', highWater: 4,
      rejectedOperation: { kind: 'create', opId: operation.opId, deviceId: operation.deviceId, item: operation.item }, cloudItem: null, conflictingItem: null }],
  })
})

test('commitLink rejects a linked server status that includes a refused operation', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000099', '20000000-0000-4000-8000-000000000099')
  const { http } = httpStub({
    commit_collection_link: {
      status: 'linked',
      push: {
        status: 'ok',
        results: [{ opId: operation.opId, status: 'conflict', reason: 'id-collision', revision: null }],
        highWater: 4,
      },
    },
  })
  const plan = {
    planId: '9f86d081-884c-4d65-9c67-41b405d56001', digest: 'digest-12345678',
    expiresAt: '2026-10-11T20:00:00Z', remoteHighWater: 3,
    localOnly: [itemId], cloudOnly: [], identical: [], conflicting: [], duplicateConfigurations: [], remoteItems: [],
  }

  await assert.rejects(
    () => createSupabaseTransport({ http }).commitLink(plan, operation.deviceId, [operation]),
    (error: unknown) => error instanceof SupabaseSyncError && error.kind === 'invalid-response',
  )
})

test('commitLink marks invalid local payload as preflight and does not dispatch', async () => {
  const operation = createOp('10000000-0000-4000-8000-000000000099', '20000000-0000-4000-8000-000000000099')
  if (operation.kind !== 'create') throw new Error('expected create operation')
  operation.item.pricePaid = { amount: 12.345, currency: 'USD' }
  const { http, record } = httpStub({ commit_collection_link: { status: 'linked' } })
  const plan = {
    planId: '9f86d081-884c-4d65-9c67-41b405d56001', digest: 'digest-12345678',
    expiresAt: '2026-10-11T20:00:00Z', remoteHighWater: 3,
    localOnly: [itemId], cloudOnly: [], identical: [], conflicting: [], duplicateConfigurations: [], remoteItems: [],
  }

  await assert.rejects(
    () => createSupabaseTransport({ http }).commitLink(plan, operation.deviceId, [operation]),
    (error: unknown) => error instanceof SupabaseSyncError && error.kind === 'preflight',
  )
  assert.equal(record.length, 0)
})
