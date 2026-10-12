import assert from 'node:assert/strict'
import test from 'node:test'
import { parseCardId, parseItemId } from '../domain/model.ts'
import { createSupabaseTransport, type SupabaseHttp } from './supabase-transport.ts'
import type { CollectionOperation } from './policy.ts'

const itemId = parseItemId('00000000-0000-4000-8000-000000000001')!
const cardId = parseCardId('base1-4')!

function createOp(opId: string, deviceId: string, quantity = 2): CollectionOperation {
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
}

function httpStub(
  responses: Record<string, unknown> | ((call: RecordedCall) => unknown),
  record: RecordedCall[] = [],
): { http: SupabaseHttp; record: RecordedCall[] } {
  const http: SupabaseHttp = {
    async rpc(fn: string, body: Record<string, unknown>) {
      const call = { fn, body }
      record.push(call)
      return typeof responses === 'function' ? responses(call) : (responses[fn] ?? {})
    },
    async user() {
      return { id: '00000000-0000-4000-8000-0000000000aa', email: 'a@example.com' }
    },
  }
  return { http, record }
}

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
        { opId: base.opId, status: 'conflict', reason: 'stale', revision: null },
        { opId: base.opId, status: 'already-applied', reason: null, revision: 4 },
        { opId: base.opId, status: 'rejected', reason: 'invalid-payload', revision: null },
      ],
      highWater: 4,
    },
  })
  const transport = createSupabaseTransport({ http })

  const outcomes = await transport.push('account-a', [base])

  assert.equal(outcomes[0]?.kind, 'conflict')
  assert.equal(outcomes[1]?.kind, 'already-applied')
  assert.equal(outcomes[2]?.kind, 'conflict', 'server rejection surfaces to the caller as a conflict')
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
  assert.equal(record[0]?.body.p_remote_sequence, 0)
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
