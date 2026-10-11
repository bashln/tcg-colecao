import assert from 'node:assert/strict'
import test from 'node:test'
import { parseCardId, parseItemId } from '../domain/model.ts'
import { applyOperation, type CollectionOperation, type SyncItem } from './policy.ts'

const itemId = parseItemId('00000000-0000-4000-8000-000000000001')
const cardId = parseCardId('base1-4')
if (itemId === null || cardId === null) throw new Error('Invalid sync test IDs')

const item: SyncItem = {
  id: itemId,
  cardId,
  quantity: 2,
  variant: 'NORMAL',
  condition: 'NEAR_MINT',
  pricePaid: { amount: 8.5, currency: 'USD' },
  addedAt: 1_791_667_200_000,
}

const create: CollectionOperation = {
  kind: 'create',
  opId: '10000000-0000-4000-8000-000000000001',
  deviceId: '20000000-0000-4000-8000-000000000001',
  item,
}

test('creates a row and exact replay returns prior result without changing it', () => {
  const first = applyOperation({ rows: [], appliedOperations: new Map() }, create)
  assert.equal(first.kind, 'applied')
  if (first.kind !== 'applied') return
  assert.equal(first.rows[0]?.revision, 1)

  const replay = applyOperation(first, create)
  assert.equal(replay.kind, 'already-applied')
  if (replay.kind !== 'already-applied') return
  assert.deepEqual(replay.rows, first.rows)
})

test('stale replace and delete refuse mutation while preserving current row', () => {
  const created = applyOperation({ rows: [], appliedOperations: new Map() }, create)
  assert.equal(created.kind, 'applied')
  if (created.kind !== 'applied') return

  const edit: CollectionOperation = {
    kind: 'replace',
    opId: '10000000-0000-4000-8000-000000000002',
    deviceId: create.deviceId,
    baseRevision: 0,
    item: { ...item, quantity: 5 },
  }
  const replaced = applyOperation(created, edit)
  assert.equal(replaced.kind, 'conflict')
  if (replaced.kind !== 'conflict') return
  assert.equal(replaced.reason, 'stale')
  assert.deepEqual(replaced.rows, created.rows)

  const remove: CollectionOperation = {
    kind: 'delete',
    opId: '10000000-0000-4000-8000-000000000003',
    deviceId: create.deviceId,
    itemId: item.id,
    baseRevision: 0,
  }
  const deleted = applyOperation(created, remove)
  assert.equal(deleted.kind, 'conflict')
  if (deleted.kind !== 'conflict') return
  assert.equal(deleted.reason, 'stale')
  assert.deepEqual(deleted.rows, created.rows)
})

test('delete creates a tombstone that cannot be recreated by an old create operation', () => {
  const created = applyOperation({ rows: [], appliedOperations: new Map() }, create)
  if (created.kind !== 'applied') throw new Error('Expected create to apply')
  const remove: CollectionOperation = {
    kind: 'delete',
    opId: '10000000-0000-4000-8000-000000000003',
    deviceId: create.deviceId,
    itemId: item.id,
    baseRevision: 1,
  }
  const deleted = applyOperation(created, remove)
  assert.equal(deleted.kind, 'applied')
  if (deleted.kind !== 'applied') return
  assert.equal(deleted.rows[0]?.value, null)
  assert.equal(deleted.rows[0]?.revision, 2)

  const staleCreate = applyOperation(deleted, create)
  assert.equal(staleCreate.kind, 'already-applied')
  if (staleCreate.kind !== 'already-applied') return
  assert.equal(staleCreate.rows[0]?.value, null)
})

test('refuses a replacement that would overflow the server revision', () => {
  const state = {
    rows: [{ id: item.id, revision: Number.MAX_SAFE_INTEGER, value: item }],
    appliedOperations: new Map<string, string>(),
  }
  const replace: CollectionOperation = {
    kind: 'replace',
    opId: '10000000-0000-4000-8000-000000000004',
    deviceId: create.deviceId,
    baseRevision: Number.MAX_SAFE_INTEGER,
    item: { ...item, quantity: 5 },
  }
  const result = applyOperation(state, replace)
  assert.equal(result.kind, 'conflict')
  if (result.kind !== 'conflict') return
  assert.equal(result.reason, 'revision-limit')
  assert.deepEqual(result.rows, state.rows)
})

test('applied rows do not change when caller later mutates operation payload', () => {
  const mutableItem = { ...item }
  const operation: CollectionOperation = { ...create, item: mutableItem }
  const result = applyOperation({ rows: [], appliedOperations: new Map() }, operation)
  if (result.kind !== 'applied') throw new Error('Expected create to apply')

  mutableItem.quantity = 99
  if (mutableItem.pricePaid === null) throw new Error('Expected fixture purchase price')
  mutableItem.pricePaid.amount = 99
  assert.equal(result.rows[0]?.value?.quantity, 2)
  assert.equal(result.rows[0]?.value?.pricePaid?.amount, 8.5)
})
