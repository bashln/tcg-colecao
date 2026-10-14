import type { CollectionItem } from '../domain/model.ts'
import type { CollectionOperation, SyncItem } from './policy.ts'

export interface SyncBinding {
  identity: string
  deviceId: string
}

export interface OutboxEntry {
  opId: string
  identity: string
  sequence: number
  operation: CollectionOperation
}

export function isSyncBinding(value: unknown): value is SyncBinding {
  if (typeof value !== 'object' || value === null) return false
  const binding = value as Record<string, unknown>
  return typeof binding.identity === 'string' && binding.identity.length > 0 &&
    typeof binding.deviceId === 'string' && binding.deviceId.length > 0
}

export function syncItem(item: CollectionItem): SyncItem {
  return {
    id: item.id,
    cardId: item.cardId,
    quantity: item.quantity,
    variant: item.variant,
    condition: item.condition,
    pricePaid: item.pricePaid,
    addedAt: item.addedAt,
  }
}

export function createCollectionOperation(
  binding: SyncBinding,
  item: CollectionItem,
  previous: CollectionItem | null,
  opId: string = crypto.randomUUID(),
): CollectionOperation {
  if (previous === null) {
    return { kind: 'create', opId, deviceId: binding.deviceId, item: syncItem(item) }
  }
  return {
    kind: 'replace', opId, deviceId: binding.deviceId, baseRevision: previous.revision, item: syncItem(item),
  }
}

export function createDeleteOperation(binding: SyncBinding, item: CollectionItem, opId: string = crypto.randomUUID()): CollectionOperation {
  return {
    kind: 'delete',
    opId,
    deviceId: binding.deviceId,
    baseRevision: item.revision,
    itemId: item.id,
  }
}

export function recordCollectionWrite(
  transaction: IDBTransaction,
  item: CollectionItem,
  previous: CollectionItem | null,
  opId?: string,
): void {
  recordBoundOperation(transaction, (binding, generatedId) => createCollectionOperation(binding, item, previous, opId ?? generatedId), opId)
}

export function recordCollectionDelete(transaction: IDBTransaction, item: CollectionItem): void {
  recordBoundOperation(transaction, (binding, opId) => createDeleteOperation(binding, item, opId))
}

function recordBoundOperation(
  transaction: IDBTransaction,
  createOperation: (binding: SyncBinding, opId: string) => CollectionOperation,
  operationId?: string,
): void {
  let opId: string
  try {
    opId = operationId ?? crypto.randomUUID()
  } catch {
    abortTransaction(transaction)
    return
  }

  let bindingRequest: IDBRequest<unknown>
  try {
    bindingRequest = transaction.objectStore('sync').get('binding')
  } catch {
    abortTransaction(transaction)
    return
  }
  bindingRequest.onerror = () => abortTransaction(transaction)
  bindingRequest.onsuccess = () => {
    try {
      const value: unknown = bindingRequest.result
      if (value === undefined) return
      if (typeof value !== 'object' || value === null || !('binding' in value) || !isSyncBinding(value.binding)) {
        abortTransaction(transaction)
        return
      }
      const binding = value.binding
      const operation = createOperation(binding, opId)
      const orderRequest = transaction.objectStore('outboxOrder').add({ opId })
      orderRequest.onerror = () => abortTransaction(transaction)
      orderRequest.onsuccess = () => {
        try {
          const sequence = Number(orderRequest.result)
          transaction.objectStore('outbox').add({ opId, identity: binding.identity, sequence, operation } satisfies OutboxEntry)
        } catch {
          abortTransaction(transaction)
        }
      }
    } catch {
      abortTransaction(transaction)
    }
  }
}

function abortTransaction(transaction: IDBTransaction): void {
  try {
    transaction.abort()
  } catch {
    // An inactive transaction has already committed or aborted.
  }
}
