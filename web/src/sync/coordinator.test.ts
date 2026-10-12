import assert from 'node:assert/strict'
import test from 'node:test'
import { parseCardId, parseItemId } from '../domain/model.ts'
import { createSyncCoordinator } from './coordinator.ts'
import type { SyncConflictDetail } from './coordinator.ts'
import { createMemoryTransport } from './memory-transport.ts'
import type { CollectionOperation } from './policy.ts'

const itemId = parseItemId('00000000-0000-4000-8000-000000000001')
const cardId = parseCardId('base1-4')
const otherCardId = parseCardId('base1-5')
if (itemId === null || cardId === null || otherCardId === null) throw new Error('Invalid sync test IDs')

const item = {
  id: itemId,
  cardId,
  quantity: 2,
  variant: 'NORMAL' as const,
  condition: 'NEAR_MINT' as const,
  pricePaid: null,
  addedAt: 1_791_667_200_000,
}

function createOperation(opId: string, deviceId: string, quantity = item.quantity): CollectionOperation {
  return { kind: 'create', opId, deviceId, item: { ...item, quantity } }
}

function localStore(...operations: CollectionOperation[]) {
  const pending = new Map(operations.map((operation) => [operation.opId, operation]))
  const acknowledged: string[] = []
  const remoteReceived: string[] = []
  let sequence = 0
  return {
    pending,
    acknowledged,
    remoteReceived,
    async listPendingOperations(_identity: string) { return [...pending.values()] },
    async acknowledgeOperation(opId: string, _identity: string) {
      acknowledged.push(opId)
      pending.delete(opId)
      return true
    },
    async applyRemoteOperation(operation: CollectionOperation, _identity: string, _nextSequence: number) { remoteReceived.push(operation.opId); return { kind: 'applied' as const } },
    async listConflicts() { return [] },
    async lastSequence(_identity: string) { return sequence },
    async saveSequence(_identity: string, next: number) { sequence = next; return true },
  }
}

test('two devices exchange local operations and acknowledge successful writes', async () => {
  const transport = createMemoryTransport()
  const aOperation = createOperation('10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001')
  const bOperation = {
    ...createOperation('10000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002', 3),
    item: { ...item, id: parseItemId('00000000-0000-4000-8000-000000000002')!, cardId: otherCardId, quantity: 3 },
  }
  const a = localStore(aOperation)
  const b = localStore(bOperation)
  const deviceA = createSyncCoordinator({ transport, local: a })
  const deviceB = createSyncCoordinator({ transport, local: b })
  deviceA.bindIdentity('account-a')
  deviceB.bindIdentity('account-a')

  await deviceA.syncOnce()
  await deviceB.syncOnce()
  await deviceA.syncOnce()

  assert.deepEqual(a.acknowledged, [aOperation.opId])
  assert.deepEqual(b.acknowledged, [bOperation.opId])
  assert.deepEqual(a.remoteReceived, [aOperation.opId, bOperation.opId])
  assert.deepEqual(b.remoteReceived, [aOperation.opId, bOperation.opId])
  assert.equal((await transport.pull('account-a', 0)).highWaterMark, 2)
})

test('failed push keeps local operation pending for retry', async () => {
  const transport = createMemoryTransport({ failNextPush: true })
  const operation = createOperation('10000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000001')
  const local = localStore(operation)
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')

  const failed = await device.syncOnce()
  assert.equal(failed.kind, 'error')
  assert.equal(local.pending.has(operation.opId), true)

  const retried = await device.syncOnce()
  assert.equal(retried.kind, 'complete')
  assert.equal(local.pending.has(operation.opId), false)
  assert.equal((await transport.pull('account-a', 0)).highWaterMark, 1)
})

test('pending-count read failure is unknown rather than falsely zero and recovers on next sync', async () => {
  const transport = createMemoryTransport({ failNextPush: true })
  const operation = createOperation('10000000-0000-4000-8000-000000000034', '20000000-0000-4000-8000-000000000001')
  const local = localStore(operation)
  let pendingReads = 0
  const readPending = local.listPendingOperations
  local.listPendingOperations = async (identity) => {
    pendingReads += 1
    if (pendingReads === 2) throw new Error('storage temporarily unavailable')
    return readPending(identity)
  }
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')

  assert.deepEqual(await device.syncOnce(), { kind: 'error', pending: null })
  assert.deepEqual(await device.syncOnce(), { kind: 'complete', applied: 1, pending: 0 })
})

test('account switch never sends operations owned by previous identity', async () => {
  const transport = createMemoryTransport()
  const operation = createOperation('10000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000001')
  const operationIdentity = 'account-a'
  let boundIdentity = 'account-a'
  const acknowledged: string[] = []
  const local = {
    async listPendingOperations(requestedIdentity: string) {
      return requestedIdentity === operationIdentity ? [operation] : []
    },
    async acknowledgeOperation(opId: string, requestedIdentity: string) {
      if (requestedIdentity !== operationIdentity) return false
      acknowledged.push(opId)
      return true
    },
    async applyRemoteOperation() { return { kind: 'applied' as const } },
    async listConflicts() { return [] },
    async lastSequence() { return 0 },
    async saveSequence() { return true },
  }
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity(boundIdentity)
  boundIdentity = 'account-b'
  device.bindIdentity(boundIdentity)

  await device.syncOnce()

  assert.deepEqual(acknowledged, [])
  assert.equal((await transport.pull('account-b', 0)).highWaterMark, 0)
})

test('conflicting operation stays pending while sync reports conflict', async () => {
  const transport = createMemoryTransport()
  const remoteOperation = createOperation('10000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-000000000002')
  const localOperation = createOperation('10000000-0000-4000-8000-000000000006', '20000000-0000-4000-8000-000000000001', 4)
  await transport.push('account-a', [remoteOperation])
  const local = localStore(localOperation)
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')

  const result = await device.syncOnce()

  assert.equal(result.kind, 'conflicts')
  assert.equal(result.kind === 'conflicts' ? result.conflicts : -1, 1)
  assert.equal(result.kind === 'conflicts' ? result.pending : -1, 1)
  const expectedItemId = localOperation.kind === 'delete' ? localOperation.itemId : localOperation.item.id
  assert.equal(result.kind === 'conflicts' ? result.details[0]?.itemId : '', expectedItemId)
  assert.equal(typeof (result.kind === 'conflicts' ? result.details[0]?.reason : undefined), 'string')
  assert.equal(local.pending.has(localOperation.opId), true)
  assert.deepEqual(local.acknowledged, [])
})

test('an accepted push with a failed local acknowledgement replays safely', async () => {
  const transport = createMemoryTransport()
  const operation = createOperation('10000000-0000-4000-8000-000000000007', '20000000-0000-4000-8000-000000000001')
  const local = localStore(operation)
  const acknowledge = local.acknowledgeOperation
  let failOnce = true
  local.acknowledgeOperation = async (opId, identity) => {
    if (failOnce) {
      failOnce = false
      throw new Error('Local storage unavailable')
    }
    return acknowledge(opId, identity)
  }
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')

  assert.deepEqual(await device.syncOnce(), { kind: 'error', pending: 1 })
  assert.equal((await transport.pull('account-a', 0)).highWaterMark, 1)
  assert.deepEqual(await device.syncOnce(), { kind: 'complete', applied: 0, pending: 0 })
  assert.equal((await transport.pull('account-a', 0)).highWaterMark, 1)
})

test('switching identity during remote apply does not advance its cursor', async () => {
  const operation = createOperation('10000000-0000-4000-8000-000000000008', '20000000-0000-4000-8000-000000000002')
  const transport = createMemoryTransport()
  await transport.push('account-a', [operation])
  let resolveApply!: (applied: boolean) => void
  let savedSequence = 0
  const local = {
    async listPendingOperations() { return [] },
    async acknowledgeOperation() { return true },
    async applyRemoteOperation() { return await new Promise<import('./coordinator.ts').RemoteApplyResult>((resolve) => { resolveApply = (applied) => resolve(applied ? { kind: 'applied' } : { kind: 'conflict', detail: { id: 'conflict-1', sequence: 1, itemId, reason: 'stale', local: item, remote: operation } }) }) },
    async listConflicts() { return [] },
    async lastSequence() { return 0 },
    async saveSequence(_identity: string, sequence: number) { savedSequence = sequence; return true },
  }
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')
  const syncing = device.syncOnce()
  while (resolveApply === undefined) await new Promise((resolve) => setImmediate(resolve))
  device.bindIdentity('account-b')
  resolveApply(false)

  assert.deepEqual(await syncing, { kind: 'offline', pending: 0 })
  assert.equal(savedSequence, 0)
})

test('pull conflict persists both values and holds cursor at first unapplied sequence', async () => {
  const remoteFirst = createOperation('10000000-0000-4000-8000-000000000018', '20000000-0000-4000-8000-000000000002')
  const remoteSecond = { ...createOperation('10000000-0000-4000-8000-000000000019', '20000000-0000-4000-8000-000000000002', 5), item: { ...item, id: parseItemId('00000000-0000-4000-8000-000000000019')!, quantity: 5 } }
  const transport = createMemoryTransport()
  await transport.push('account-a', [remoteFirst, remoteSecond])
  let cursor = 0
  const stored: SyncConflictDetail[] = []
  const local = {
    async listPendingOperations() { return [] },
    async acknowledgeOperation() { return true },
    async applyRemoteOperation(operation: CollectionOperation, _identity: string, sequence: number) {
      if (sequence === 1) {
        const detail: SyncConflictDetail = { identity: 'account-a', id: `account-a:${sequence}`, sequence, itemId, reason: 'duplicate-configuration', local: item, remote: operation }
        stored.push(detail)
        return { kind: 'conflict' as const, detail }
      }
      cursor = sequence
      return { kind: 'applied' as const }
    },
    async lastSequence() { return cursor },
    async saveSequence(_identity: string, sequence: number) { cursor = sequence; return true },
    async listConflicts() { return stored },
  }
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')

  const result = await device.syncOnce()

  assert.equal(result.kind, 'conflicts')
  assert.equal(result.kind === 'conflicts' ? result.details[0]?.sequence : -1, 1)
  assert.equal(result.kind === 'conflicts' ? result.details[0]?.local?.quantity : -1, 2)
  assert.equal(result.kind === 'conflicts' ? result.details[0]?.remote?.kind : '', 'create')
  assert.equal(cursor, 0)
  assert.equal(stored.length, 1)
})

test('mid-flight account switch never pulls or acknowledges under the new identity', async () => {
  const operation = createOperation('10000000-0000-4000-8000-000000000009', '20000000-0000-4000-8000-000000000001')
  const local = localStore(operation)
  const remote = createMemoryTransport()
  let beginPush!: () => void
  let finishPush!: () => void
  let pullCount = 0
  const transport = {
    async push(identity: string, operations: readonly CollectionOperation[]) {
      assert.equal(identity, 'account-a')
      beginPush()
      await new Promise<void>((resolve) => { finishPush = resolve })
      return remote.push(identity, operations)
    },
    async pull(identity: string, sequence: number) {
      pullCount += 1
      return remote.pull(identity, sequence)
    },
  }
  const started = new Promise<void>((resolve) => { beginPush = resolve })
  const device = createSyncCoordinator({ transport, local })
  device.bindIdentity('account-a')
  const syncing = device.syncOnce()
  await started
  device.bindIdentity('account-b')
  finishPush()

  assert.deepEqual(await syncing, { kind: 'offline', pending: 1 })
  assert.deepEqual(local.acknowledged, [])
  assert.equal(pullCount, 0)
  assert.equal((await remote.pull('account-a', 0)).highWaterMark, 1)
  assert.equal((await remote.pull('account-b', 0)).highWaterMark, 0)
})
