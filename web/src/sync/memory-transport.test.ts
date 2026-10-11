import assert from 'node:assert/strict'
import test from 'node:test'
import { parseCardId, parseItemId } from '../domain/model.ts'
import { createMemoryTransport } from './memory-transport.ts'
import type { CollectionOperation } from './policy.ts'

const itemId = parseItemId('00000000-0000-4000-8000-000000000001')
const cardId = parseCardId('base1-4')
if (itemId === null || cardId === null) throw new Error('Invalid sync test IDs')

const item = {
  id: itemId,
  cardId,
  quantity: 2,
  variant: 'NORMAL' as const,
  condition: 'NEAR_MINT' as const,
  pricePaid: null,
  addedAt: 1_791_667_200_000,
}

const create: CollectionOperation = {
  kind: 'create',
  opId: '10000000-0000-4000-8000-000000000001',
  deviceId: '20000000-0000-4000-8000-000000000001',
  item,
}

test('same operation replay does not duplicate row or remote sequence', async () => {
  const remote = createMemoryTransport()
  const first = await remote.push('account-a', [create])
  const replay = await remote.push('account-a', [create])

  assert.equal(first[0]?.kind, 'applied')
  assert.equal(replay[0]?.kind, 'already-applied')
  assert.equal((await remote.pull('account-a', 0)).highWaterMark, 1)
})

test('one conflicting operation does not reject valid batch neighbors', async () => {
  const remote = createMemoryTransport()
  const conflict: CollectionOperation = {
    kind: 'replace',
    opId: '10000000-0000-4000-8000-000000000002',
    deviceId: create.deviceId,
    baseRevision: 1,
    item: { ...item, quantity: 5 },
  }
  const validCreate: CollectionOperation = {
    kind: 'create',
    opId: '10000000-0000-4000-8000-000000000003',
    deviceId: create.deviceId,
    item: { ...item, id: parseItemId('00000000-0000-4000-8000-000000000002')! },
  }

  const results = await remote.push('account-a', [conflict, validCreate])

  assert.equal(results[0]?.kind, 'conflict')
  assert.equal(results[1]?.kind, 'applied')
  assert.equal((await remote.pull('account-a', 0)).highWaterMark, 1)
})

test('pull returns accepted operations ordered after cursor', async () => {
  const remote = createMemoryTransport()
  await remote.push('account-a', [create])
  const page = await remote.pull('account-a', 0)

  assert.equal(page.operations.length, 1)
  assert.equal(page.operations[0]?.operation.opId, create.opId)
  assert.equal(page.operations[0]?.sequence, 1)
  assert.equal(page.highWaterMark, 1)
})
