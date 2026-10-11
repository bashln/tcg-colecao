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
