import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { parseCardId, parseItemId } from '../domain/model.ts'
import type { CardId } from '../domain/model.ts'
import { createSyncCoordinator } from './coordinator.ts'
import type { SyncConflictDetail } from './coordinator.ts'
import type { CollectionOperation, SyncItem } from './policy.ts'
import { createSupabaseTransport, SupabaseSyncError } from './supabase-transport.ts'
import type { PushOutcome, SupabaseHttp } from './supabase-transport.ts'

const vectors = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../../testdata/sync-wire-vectors.json', import.meta.url)), 'utf8'),
) as {
  priceVector: { major: number; minor: number; rejected: { amount: number; reason: string }[] }
  roundingCases: { name: string; major: number; minor: number }[]
}

function testCardId(): CardId {
  const parsed = parseCardId('base1-4')
  if (parsed === null) throw new Error('Invalid sync test card id')
  return parsed
}

const cardId = testCardId()

function createOperation(opId: string, pricePaid: SyncItem['pricePaid'] = null): CollectionOperation {
  const itemId = parseItemId(`item-${opId}`)
  if (itemId === null) throw new Error('Invalid sync test item id')
  return {
    kind: 'create',
    opId,
    deviceId: 'device-0001',
    item: { id: itemId, cardId, quantity: 2, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid, addedAt: 1_791_667_200_000 },
  }
}

function createOperations(count: number): CollectionOperation[] {
  return Array.from({ length: count }, (_, index) =>
    createOperation(`10000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`))
}

function itemIdOf(operation: CollectionOperation): string {
  return operation.kind === 'delete' ? operation.itemId : operation.item.id
}

function localStore(operations: CollectionOperation[], conflicts: SyncConflictDetail[] = []) {
  const pending = new Map(operations.map((operation) => [operation.opId, operation]))
  const acknowledged: string[] = []
  return {
    pending,
    acknowledged,
    async listPendingOperations() { return [...pending.values()] },
    async acknowledgeOperation(opId: string) { acknowledged.push(opId); pending.delete(opId); return true },
    async applyRemoteOperation() { return { kind: 'applied' as const } },
    async listConflicts() { return conflicts },
    async lastSequence() { return 0 },
    async saveSequence() { return true },
  }
}

function pushTransport(respond: (batch: readonly CollectionOperation[], index: number) => PushOutcome[]) {
  const batches: CollectionOperation[][] = []
  const transport = {
    async push(_identity: string, operations: readonly CollectionOperation[]) {
      batches.push([...operations])
      return respond(operations, batches.length - 1)
    },
    async pull() { return { operations: [], highWaterMark: 0 } },
  }
  return { batches, transport }
}

function capturingHttp(captured: { operations?: unknown[] }): SupabaseHttp {
  return {
    async rpc(_fn, body) {
      captured.operations = body.p_operations as unknown[]
      return { status: 'ok', results: [], highWater: 0 }
    },
    async user() { return { id: 'user-1', email: 'fixture@example.test' } },
  }
}

test('push de 250 operações pendentes chama o transporte 3 vezes, com 100, 100 e 50 operações, e confirma as 250', async () => {
  const operations = createOperations(250)
  const local = localStore(operations)
  const { batches, transport } = pushTransport((batch) =>
    batch.map((operation) => ({ kind: 'applied' as const, opId: operation.opId, sequence: 1 })))
  const coordinator = createSyncCoordinator({ transport, local })
  coordinator.bindIdentity('account-a')

  const result = await coordinator.syncOnce()

  assert.deepEqual(batches.map((batch) => batch.length), [100, 100, 50])
  assert.equal(result.kind, 'complete')
  assert.equal(result.kind === 'complete' ? result.applied : -1, 250)
  assert.equal(local.acknowledged.length, 250)
  assert.equal(local.pending.size, 0)
})

test('push de 250 operações onde o segundo bloco falha deixa o bloco que falhou e os seguintes pendentes', async () => {
  const operations = createOperations(250)
  const local = localStore(operations)
  const { batches, transport } = pushTransport((batch, index) => {
    if (index === 1) throw new Error('Transporte indisponível')
    return batch.map((operation) => ({ kind: 'applied' as const, opId: operation.opId, sequence: 1 }))
  })
  const coordinator = createSyncCoordinator({ transport, local })
  coordinator.bindIdentity('account-a')

  const result = await coordinator.syncOnce()

  assert.deepEqual(batches.map((batch) => batch.length), [100, 100])
  assert.equal(result.kind, 'error')
  assert.equal(local.acknowledged.length, 100)
  assert.equal(local.pending.size, 150)
})

test('respostas devolvidas fora de ordem são atribuídas pelo opId certo', async () => {
  const operations = createOperations(3)
  const local = localStore(operations)
  const conflictId = '30000000-0000-4000-8000-000000000001'
  const { transport } = pushTransport((batch) => [...batch].reverse().map((operation, index) =>
    index === 2
      ? { kind: 'conflict' as const, opId: operation.opId, reason: 'stale', conflictId }
      : { kind: 'applied' as const, opId: operation.opId, sequence: 1 }))
  const coordinator = createSyncCoordinator({ transport, local })
  coordinator.bindIdentity('account-a')

  const result = await coordinator.syncOnce()

  const [first, second, third] = operations
  if (first === undefined || second === undefined || third === undefined) throw new Error('missing operations')
  assert.deepEqual(local.acknowledged, [second.opId, third.opId])
  assert.equal(local.pending.has(first.opId), true)
  assert.equal(result.kind, 'conflicts')
  const details = result.kind === 'conflicts' ? result.details : []
  assert.equal(details.length, 1)
  assert.equal(details[0]?.itemId, itemIdOf(first))
  assert.equal(details[0]?.serverConflictId, conflictId)
})

test('resposta sem opId devolve erro e não confirma nada do bloco', async () => {
  const operations = createOperations(3)
  const local = localStore(operations)
  const { transport } = pushTransport((batch) => batch.map((operation, index) =>
    index === 1
      ? { kind: 'applied' as const }
      : { kind: 'applied' as const, opId: operation.opId, sequence: 1 }))
  const coordinator = createSyncCoordinator({ transport, local })
  coordinator.bindIdentity('account-a')

  const result = await coordinator.syncOnce()

  assert.equal(result.kind, 'error')
  assert.deepEqual(local.acknowledged, [])
  assert.equal(local.pending.size, 3)
})

test('resposta com opId de operação não enviada devolve erro e não confirma nada do bloco', async () => {
  const operations = createOperations(3)
  const local = localStore(operations)
  const { transport } = pushTransport((batch) => batch.map((operation, index) =>
    index === 0
      ? { kind: 'applied' as const, opId: '99999999-9999-4999-8999-999999999999', sequence: 1 }
      : { kind: 'applied' as const, opId: operation.opId, sequence: 1 }))
  const coordinator = createSyncCoordinator({ transport, local })
  coordinator.bindIdentity('account-a')

  const result = await coordinator.syncOnce()

  assert.equal(result.kind, 'error')
  assert.deepEqual(local.acknowledged, [])
  assert.equal(local.pending.size, 3)
})

test('resposta rejected vira conflito de origem push e não confirma a operação', async () => {
  const operations = createOperations(1)
  const local = localStore(operations)
  const conflictId = '30000000-0000-4000-8000-000000000001'
  const { transport } = pushTransport((batch) => batch.map((operation) => ({
    kind: 'rejected' as const, opId: operation.opId, reason: 'invalid-payload', conflictId,
  })))
  const coordinator = createSyncCoordinator({ transport, local })
  coordinator.bindIdentity('account-a')

  const result = await coordinator.syncOnce()

  const operation = operations[0]
  if (operation === undefined) throw new Error('missing operation')
  assert.equal(result.kind, 'conflicts')
  const details = result.kind === 'conflicts' ? result.details : []
  assert.equal(details.length, 1)
  assert.equal(details[0]?.source, 'push')
  assert.equal(details[0]?.reason, 'invalid-payload')
  assert.equal(details[0]?.serverConflictId, conflictId)
  assert.equal(details[0]?.itemId, itemIdOf(operation))
  assert.deepEqual(local.acknowledged, [])
  assert.equal(local.pending.has(operation.opId), true)
})

test('operação bloqueada por rejectedOperation.opId não é reenviada', async () => {
  const operations = createOperations(4)
  const conflicts: SyncConflictDetail[] = [
    {
      itemId: itemIdOf(operations[0]!), reason: 'stale', source: 'push', serverConflictId: '30000000-0000-4000-8000-000000000001',
      pushConflict: {
        id: '30000000-0000-4000-8000-000000000001', opId: null, itemId: itemIdOf(operations[0]!), reason: 'stale',
        deviceId: 'device-0001', rejectedOperation: operations[0]!, cloudItem: null, conflictingItem: null,
        highWater: 12, resolution: null,
      },
    },
    {
      itemId: itemIdOf(operations[2]!), reason: 'stale', source: 'push', serverConflictId: '30000000-0000-4000-8000-000000000002',
      pushConflict: {
        id: '30000000-0000-4000-8000-000000000002', opId: operations[1]!.opId, itemId: itemIdOf(operations[2]!), reason: 'stale',
        deviceId: 'device-0001', rejectedOperation: operations[2]!, cloudItem: null, conflictingItem: null,
        highWater: 12, resolution: null,
      },
    },
  ]
  const { batches, transport } = pushTransport((batch) =>
    batch.map((operation) => ({ kind: 'applied' as const, opId: operation.opId, sequence: 1 })))
  const coordinator = createSyncCoordinator({ transport, local: localStore(operations, conflicts) })
  coordinator.bindIdentity('account-a')

  await coordinator.syncOnce()

  assert.deepEqual(batches.map((batch) => batch.map((operation) => operation.opId)), [[operations[3]!.opId]])
})
test('toMinorUnits rejeita os preços que o contrato compartilhado rejeita', async () => {
  for (const rejected of vectors.priceVector.rejected) {
    const captured: { operations?: unknown[] } = {}
    const transport = createSupabaseTransport({ http: capturingHttp(captured) })
    const operation = createOperation('10000000-0000-4000-8000-000000000001', { amount: rejected.amount, currency: 'USD' })

    await assert.rejects(
      () => transport.push('account-a', [operation]),
      (error: unknown) => error instanceof SupabaseSyncError && error.kind === 'unavailable',
      `price ${rejected.amount} must be refused`,
    )
    assert.equal(captured.operations, undefined, `price ${rejected.amount} must not reach the server`)
  }
})

test('push recusa preço negativo com mensagem para o usuário', async () => {
  const captured: { operations?: unknown[] } = {}
  const transport = createSupabaseTransport({ http: capturingHttp(captured) })
  const operation = createOperation('10000000-0000-4000-8000-000000000001', { amount: -1, currency: 'USD' })

  await assert.rejects(() => transport.push('account-a', [operation]), /O preço pago não pode ser negativo/)
})

test('os casos de roundingCases do arquivo de vetores produzem as unidades menores esperadas', async () => {
  for (const vector of vectors.roundingCases) {
    const captured: { operations?: unknown[] } = {}
    const transport = createSupabaseTransport({ http: capturingHttp(captured) })
    const operation = createOperation('10000000-0000-4000-8000-000000000001', { amount: vector.major, currency: 'USD' })

    await transport.push('account-a', [operation])

    const sent = (captured.operations ?? [])[0] as { item?: { pricePaid?: { amount?: number } | null } }
    assert.equal(sent.item?.pricePaid?.amount, vector.minor, vector.name)
  }
})

test('push aceita o maior preço em inteiro seguro e recusa o que passa do limite', async () => {
  const captured: { operations?: unknown[] } = {}
  const transport = createSupabaseTransport({ http: capturingHttp(captured) })
  const operation = createOperation('10000000-0000-4000-8000-000000000001', { amount: 90071992547409.9, currency: 'USD' })

  await transport.push('account-a', [operation])

  const sent = (captured.operations ?? [])[0] as { item?: { pricePaid?: { amount?: number } | null } }
  assert.equal(sent.item?.pricePaid?.amount, 9007199254740991)

  const over = createOperation('10000000-0000-4000-8000-000000000002', { amount: 90071992547409.92, currency: 'USD' })
  await assert.rejects(() => transport.push('account-a', [over]), /O preço pago/)
})
