import {
  CONDITIONS, CURRENCIES, VARIANTS, configurationKey, newItemId, nextRevision, parseCardId, parseItemId,
} from '../domain/model.ts'
import type { CardSummary, CollectionEntry, CollectionItem, ItemId, ParsedDraft } from '../domain/model.ts'
import { importSignature, parseCollectionBackup, parseImportMarkers } from './backup.ts'
import { isSyncBinding, recordCollectionDelete, recordCollectionWrite, type OutboxEntry, type SyncBinding } from '../sync/outbox.ts'
import type { CollectionOperation, SyncItem } from '../sync/policy.ts'
import type { RemoteApplyResult, SyncConflictDetail } from '../sync/coordinator.ts'

const DATABASE_NAME = 'tcg-colecao-web'
const VERSION = 5
const CHANGE_EVENT = 'tcg-collection-changed'
let databasePromise: Promise<IDBDatabase> | null = null

export type WriteResult =
  | { kind: 'saved'; item: CollectionItem }
  | { kind: 'removed' }
  | { kind: 'conflict'; reason: 'stale' | 'duplicate' | 'missing' | 'revision-limit' }
  | { kind: 'storage-error' }

export type ImportResult =
  | { kind: 'imported'; added: number; merged: number; unchanged: number }
  | { kind: 'invalid'; message: string }
  | { kind: 'conflict'; reason: 'existing-item' | 'quantity-limit' | 'revision-limit' | 'invalid-local' }
  | { kind: 'storage-error' }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isVariant(value: unknown): value is CollectionItem['variant'] {
  return VARIANTS.some((variant) => variant === value)
}

function isCondition(value: unknown): value is CollectionItem['condition'] {
  return CONDITIONS.some((condition) => condition === value)
}

function isCard(value: unknown): value is CardSummary {
  if (!isRecord(value)) return false
  return parseCardId(value.id) !== null && typeof value.name === 'string' &&
    typeof value.setId === 'string' && typeof value.setName === 'string' &&
    typeof value.collectorNumber === 'string' &&
    (value.imageUrl === null || typeof value.imageUrl === 'string') &&
    Array.isArray(value.variants) && value.variants.every((variant: unknown) => VARIANTS.some((item) => item === variant)) &&
    Array.isArray(value.prices) && value.prices.every((quote: unknown) =>
      isRecord(quote) && (quote.source === 'TCGPLAYER' || quote.source === 'CARDMARKET') &&
      VARIANTS.some((variant) => variant === quote.variant) &&
      CURRENCIES.some((currency) => currency === quote.currency) &&
      typeof quote.amount === 'number' && Number.isFinite(quote.amount) && quote.amount >= 0 &&
      (quote.updatedAt === null || typeof quote.updatedAt === 'string')) &&
    (value.setTotal === null || typeof value.setTotal === 'number')
}

function isItem(value: unknown): value is CollectionItem {
  if (!isRecord(value)) return false
  const cardId = parseCardId(value.cardId)
  if (cardId === null || parseItemId(value.id) === null) return false
  if (!isVariant(value.variant) || !isCondition(value.condition)) return false
  const price = value.pricePaid
  if (price !== null && (!isRecord(price) ||
    typeof price.amount !== 'number' || !Number.isFinite(price.amount) || price.amount < 0 ||
    !CURRENCIES.some((currency) => currency === price.currency))) return false
  return typeof value.quantity === 'number' && Number.isSafeInteger(value.quantity) && value.quantity > 0 &&
    typeof value.addedAt === 'number' && Number.isFinite(value.addedAt) &&
    typeof value.revision === 'number' && Number.isSafeInteger(value.revision) && value.revision > 0 &&
    value.configurationKey === configurationKey(cardId, value.variant, value.condition)
}

function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise !== null) return databasePromise
  const opening = new Promise<IDBDatabase>((resolve, reject) => {
    if (!('indexedDB' in window)) {
      reject(new Error('IndexedDB indisponível'))
      return
    }
    const request = indexedDB.open(DATABASE_NAME, VERSION)
    request.onupgradeneeded = (event) => {
      const db = request.result
      if (event.oldVersion < 1) {
        const items = db.createObjectStore('items', { keyPath: 'id' })
        items.createIndex('configurationKey', 'configurationKey', { unique: true })
        db.createObjectStore('cards', { keyPath: 'id' })
      }
      if (event.oldVersion < 2) db.createObjectStore('imports', { keyPath: 'itemId' })
      if (event.oldVersion < 3) {
        db.createObjectStore('outbox', { keyPath: 'opId' }).createIndex('identity', 'identity')
        db.createObjectStore('sync', { keyPath: 'key' })
      }
      if (event.oldVersion < 4) {
        const orderStore = db.createObjectStore('outboxOrder', { keyPath: 'sequence', autoIncrement: true })
        orderStore.createIndex('opId', 'opId', { unique: true })
        const transaction = request.transaction
        if (transaction === null) throw new Error('Migração de sincronização indisponível')
        const outbox = transaction.objectStore('outbox')
        if (!outbox.indexNames.contains('opId')) outbox.createIndex('opId', 'opId', { unique: true })
        const entriesRequest = outbox.getAll()
        entriesRequest.onerror = () => transaction.abort()
        entriesRequest.onsuccess = () => {
          try {
            const entries = (entriesRequest.result as Record<string, unknown>[]).sort(legacyOperationOrder)
            for (const row of entries) {
              const sequenceRequest = orderStore.add({ opId: row.opId })
              sequenceRequest.onerror = () => transaction.abort()
              sequenceRequest.onsuccess = () => {
                try {
                  outbox.put({ ...row, sequence: sequenceRequest.result })
                } catch {
                  transaction.abort()
                }
              }
            }
          } catch {
            transaction.abort()
          }
        }
      }
      if (event.oldVersion < 5) db.createObjectStore('syncConflicts', { keyPath: 'id' }).createIndex('identity', 'identity')
    }
    request.onsuccess = () => {
      request.result.onversionchange = () => request.result.close()
      resolve(request.result)
    }
    request.onerror = () => reject(request.error ?? new Error('Falha ao abrir a coleção'))
    request.onblocked = () => reject(new Error('Outra aba precisa ser fechada para atualizar a coleção'))
  }).catch((error: unknown) => {
    databasePromise = null
    throw error
  })
  databasePromise = opening
  return opening
}

function legacyOperationOrder(left: Record<string, unknown>, right: Record<string, unknown>): number {
  const leftOperation = isRecord(left.operation) ? left.operation : {}
  const rightOperation = isRecord(right.operation) ? right.operation : {}
  const leftItemId = operationItemId(leftOperation)
  const rightItemId = operationItemId(rightOperation)
  if (leftItemId !== rightItemId) return leftItemId.localeCompare(rightItemId)
  const leftRevision = operationBaseRevision(leftOperation)
  const rightRevision = operationBaseRevision(rightOperation)
  if (leftRevision !== rightRevision) return leftRevision - rightRevision
  const leftKind = operationKindOrder(leftOperation.kind)
  const rightKind = operationKindOrder(rightOperation.kind)
  return leftKind - rightKind || String(left.opId).localeCompare(String(right.opId))
}

function operationItemId(operation: Record<string, unknown>): string {
  if (operation.kind === 'delete') return typeof operation.itemId === 'string' ? operation.itemId : ''
  const item = isRecord(operation.item) ? operation.item : {}
  return typeof item.id === 'string' ? item.id : ''
}

function operationBaseRevision(operation: Record<string, unknown>): number {
  if (operation.kind === 'create') return 0
  return typeof operation.baseRevision === 'number' && Number.isSafeInteger(operation.baseRevision)
    ? operation.baseRevision
    : 0
}

function operationKindOrder(kind: unknown): number {
  if (kind === 'create') return 0
  if (kind === 'replace') return 1
  return 2
}

export async function setSyncBinding(binding: SyncBinding | null): Promise<void> {
  if (binding !== null && !isSyncBinding(binding)) throw new TypeError('Vínculo de sincronização inválido')
  const database = await openDatabase()
  const transaction = database.transaction('sync', 'readwrite')
  const store = transaction.objectStore('sync')
  if (binding === null) store.delete('binding')
  else store.put({ key: 'binding', binding })
  await transactionEnd(transaction)
}

export async function currentSyncBinding(): Promise<SyncBinding | null> {
  const database = await openDatabase()
  const transaction = database.transaction('sync', 'readonly')
  const end = transactionEnd(transaction)
  const raw: unknown = await requestResult(transaction.objectStore('sync').get('binding'))
  await end
  if (typeof raw !== 'object' || raw === null || !('binding' in raw) || !isSyncBinding(raw.binding)) return null
  return raw.binding
}

export async function getCachedCard(cardId: string): Promise<CardSummary | null> {
  const database = await openDatabase()
  const transaction = database.transaction('cards', 'readonly')
  const end = transactionEnd(transaction)
  const raw: unknown = await requestResult(transaction.objectStore('cards').get(cardId))
  await end
  if (raw === undefined) return null
  if (!isCard(raw)) throw new Error('Carta local inválida')
  return raw
}

export async function listPendingOperations(identity: string): Promise<CollectionOperation[]> {
  const database = await openDatabase()
  const transaction = database.transaction(['outbox', 'sync'], 'readonly')
  const end = transactionEnd(transaction)
  const bindingRequest = requestResult<unknown>(transaction.objectStore('sync').get('binding'))
  const entriesRequest = requestResult<unknown[]>(transaction.objectStore('outbox').index('identity').getAll(identity))
  const [bindingRow, entries] = await Promise.all([bindingRequest, entriesRequest])
  await end
  if (!isCurrentBinding(bindingRow, identity)) return []
  if (!entries.every(isOutboxEntry)) throw new Error('Operações locais inválidas')
  return entries.sort((left, right) => left.sequence - right.sequence).map((entry) => entry.operation)
}

export async function acknowledgeOperation(opId: string, identity: string): Promise<boolean> {
  const database = await openDatabase()
  const transaction = database.transaction(['outbox', 'outboxOrder', 'sync'], 'readwrite')
  const end = transactionEnd(transaction)
  const syncStore = transaction.objectStore('sync')
  const outboxStore = transaction.objectStore('outbox')
  const bindingRequest = syncStore.get('binding')
  const entryRequest = outboxStore.index('opId').get(opId)
  let acknowledged = false
  bindingRequest.onsuccess = () => {
    entryRequest.onsuccess = () => {
      const bindingRow: unknown = bindingRequest.result
      const entry: unknown = entryRequest.result
      if (!isCurrentBinding(bindingRow, identity) || !isOutboxEntry(entry) || entry.identity !== identity) return
      outboxStore.delete(entry.opId)
      transaction.objectStore('outboxOrder').delete(entry.sequence)
      acknowledged = true
    }
  }
  await end
  return acknowledged
}

export async function lastSyncSequence(identity: string): Promise<number> {
  const database = await openDatabase()
  const transaction = database.transaction('sync', 'readonly')
  const end = transactionEnd(transaction)
  const raw = await requestResult<unknown>(transaction.objectStore('sync').get(`cursor:${identity}`))
  await end
  if (typeof raw !== 'object' || raw === null || !('sequence' in raw)) return 0
  const sequence: unknown = raw.sequence
  return typeof sequence === 'number' && Number.isSafeInteger(sequence) && sequence >= 0 ? sequence : 0
}

export async function saveSyncSequence(identity: string, sequence: number): Promise<boolean> {
  if (!Number.isSafeInteger(sequence) || sequence < 0) throw new RangeError('Cursor de sincronização inválido')
  const database = await openDatabase()
  const transaction = database.transaction('sync', 'readwrite')
  const end = transactionEnd(transaction)
  const store = transaction.objectStore('sync')
  const bindingRequest = store.get('binding')
  const cursorRequest = store.get(`cursor:${identity}`)
  let bindingRow: unknown
  let cursorRow: unknown
  let bindingReady = false
  let cursorReady = false
  let saved = false
  const saveWhenReady = () => {
    if (!bindingReady || !cursorReady || !isCurrentBinding(bindingRow, identity)) return
    const previous = typeof cursorRow === 'object' && cursorRow !== null && 'sequence' in cursorRow &&
      typeof cursorRow.sequence === 'number' && Number.isSafeInteger(cursorRow.sequence) && cursorRow.sequence >= 0
      ? cursorRow.sequence
      : 0
    if (sequence >= previous) {
      store.put({ key: `cursor:${identity}`, sequence })
      saved = true
    }
  }
  bindingRequest.onsuccess = () => { bindingRow = bindingRequest.result; bindingReady = true; saveWhenReady() }
  cursorRequest.onsuccess = () => { cursorRow = cursorRequest.result; cursorReady = true; saveWhenReady() }
  await end
  return saved
}

export async function applyRemoteOperation(
  identity: string,
  sequence: number,
  operation: CollectionOperation,
  card?: CardSummary,
): Promise<RemoteApplyResult> {
  if (!Number.isSafeInteger(sequence) || sequence < 1) return { kind: 'conflict', detail: makeSyncConflict(identity, sequence, operation, null, 'invalid-sequence') }
  const remoteItem = operation.kind === 'delete' ? null : operation.item
  if (remoteItem !== null && card !== undefined && card.id !== remoteItem.cardId) {
    return { kind: 'conflict', detail: makeSyncConflict(identity, sequence, operation, null, 'invalid-card') }
  }
  const database = await openDatabase()
  return new Promise<RemoteApplyResult>((resolve) => {
    const transaction = database.transaction(['items', 'cards', 'sync', 'syncConflicts'], 'readwrite')
    const items = transaction.objectStore('items')
    const sync = transaction.objectStore('sync')
    const conflicts = transaction.objectStore('syncConflicts')
    const bindingRequest = sync.get('binding')
    const cursorRequest = sync.get(`cursor:${identity}`)
    const itemId = operation.kind === 'delete' ? operation.itemId : operation.item.id
    const itemRequest = items.get(itemId)
    let binding: unknown
    let cursor: unknown
    let current: unknown
    let bindingReady = false
    let cursorReady = false
    let itemReady = false
    let result: RemoteApplyResult = { kind: 'conflict', detail: makeSyncConflict(identity, sequence, operation, null, 'invalid-local') }
    let changed = false
    let complete = false

    const abort = () => {
      try { transaction.abort() } catch { /* The transaction already completed. */ }
    }
    const applyWhenReady = () => {
      if (!bindingReady || !cursorReady || !itemReady || complete) return
      if (!isCurrentBinding(binding, identity)) { complete = true; abort(); return }
      const existing = current === undefined ? undefined : isItem(current) ? current : null
      if (existing === null) { complete = true; abort(); return }

      const conflict = (reason: string, localItem = existing) => {
        const detail = makeSyncConflict(identity, sequence, operation, localItem === undefined ? null : toSyncItem(localItem), reason)
        conflicts.put({ ...detail, identity })
        result = { kind: 'conflict', detail }
        complete = true
      }

      const remote = remoteItem
      const isSameItem = (item: CollectionItem, value: NonNullable<typeof remote>) =>
        item.id === value.id && item.cardId === value.cardId && item.quantity === value.quantity &&
        item.variant === value.variant && item.condition === value.condition && item.addedAt === value.addedAt &&
        JSON.stringify(item.pricePaid) === JSON.stringify(value.pricePaid)

      if (operation.kind === 'delete') {
        if (existing !== undefined && existing.revision !== operation.baseRevision) {
          conflict('stale-revision')
          return
        }
        if (existing !== undefined) {
          items.delete(operation.itemId)
          changed = true
        }
      } else {
        const remote = operation.item
        const key = configurationKey(remote.cardId, remote.variant, remote.condition)
        if (operation.kind === 'create' && existing !== undefined) {
          if (existing.revision !== 1 || !isSameItem(existing, remote)) {
            conflict('id-collision')
            return
          }
        } else if (operation.kind === 'replace' && existing !== undefined &&
          existing.revision === operation.baseRevision + 1 && isSameItem(existing, remote)) {
          // The local write already reflects this server operation; advance only cursor.
        } else {
          const expectedRevision = operation.kind === 'create' ? null : operation.baseRevision
          if ((expectedRevision === null && existing !== undefined) ||
            (expectedRevision !== null && (existing === undefined || existing.revision !== expectedRevision)) ||
            card === undefined) {
            conflict(expectedRevision !== null && existing === undefined ? 'missing-local' : 'stale-revision')
            complete = true
            return
          }
          const queueItem = () => {
            const incoming: CollectionItem = {
              ...remote,
              revision: operation.kind === 'create' ? 1 : operation.baseRevision + 1,
              configurationKey: key,
            }
            items.put(incoming)
            transaction.objectStore('cards').put(card)
            changed = true
            saveCursor()
          }
          if (existing?.configurationKey === key) {
            queueItem()
            return
          }
          const duplicateRequest = items.index('configurationKey').get(key)
          duplicateRequest.onsuccess = () => {
            const duplicate: unknown = duplicateRequest.result
            if (duplicate !== undefined && (!isItem(duplicate) || duplicate.id !== itemId)) {
              conflict('duplicate-configuration', isItem(duplicate) ? duplicate : undefined)
              return
            }
            queueItem()
          }
          duplicateRequest.onerror = abort
          return
        }
      }
      saveCursor()
    }

    const saveCursor = () => {
      const storedSequence = typeof cursor === 'object' && cursor !== null && 'sequence' in cursor &&
        typeof cursor.sequence === 'number' && Number.isSafeInteger(cursor.sequence) && cursor.sequence >= 0
        ? cursor.sequence
        : 0
      if (sequence > storedSequence) sync.put({ key: `cursor:${identity}`, sequence })
      result = { kind: 'applied' }
      complete = true
    }

    bindingRequest.onsuccess = () => { binding = bindingRequest.result; bindingReady = true; applyWhenReady() }
    cursorRequest.onsuccess = () => { cursor = cursorRequest.result; cursorReady = true; applyWhenReady() }
    itemRequest.onsuccess = () => { current = itemRequest.result; itemReady = true; applyWhenReady() }
    bindingRequest.onerror = cursorRequest.onerror = itemRequest.onerror = abort
    transaction.oncomplete = () => {
      if (changed) announceChange()
      resolve(result)
    }
    transaction.onabort = transaction.onerror = () => resolve(result)
  })
}

function toSyncItem(item: CollectionItem): SyncItem {
  return {
    id: item.id, cardId: item.cardId, quantity: item.quantity, variant: item.variant,
    condition: item.condition, pricePaid: item.pricePaid, addedAt: item.addedAt,
  }
}

function makeSyncConflict(
  identity: string,
  sequence: number,
  remote: CollectionOperation,
  local: SyncItem | null,
  reason: string,
): SyncConflictDetail {
  return {
    id: `${identity}:${sequence}`,
    identity,
    sequence,
    itemId: remote.kind === 'delete' ? remote.itemId : remote.item.id,
    reason,
    local,
    remote,
  }
}

export async function listSyncConflicts(identity: string): Promise<SyncConflictDetail[]> {
  const database = await openDatabase()
  const transaction = database.transaction('syncConflicts', 'readonly')
  const end = transactionEnd(transaction)
  const rows = await requestResult<unknown[]>(transaction.objectStore('syncConflicts').index('identity').getAll(identity))
  await end
  return rows.filter((row): row is SyncConflictDetail => isRecord(row) && row.identity === identity && !('resolvedAt' in row) &&
    typeof row.id === 'string' && typeof row.sequence === 'number' && typeof row.itemId === 'string' &&
    typeof row.reason === 'string' && isCollectionOperation(row.remote) &&
    (row.resolutionOperation === undefined || isCollectionOperation(row.resolutionOperation)))
}

function isCollectionOperation(value: unknown): value is CollectionOperation {
  if (!isRecord(value) || typeof value.opId !== 'string' || typeof value.deviceId !== 'string') return false
  if (value.kind === 'delete') return parseItemId(value.itemId) !== null && Number.isSafeInteger(value.baseRevision)
  if (value.kind !== 'create' && value.kind !== 'replace') return false
  if (value.kind === 'replace' && !Number.isSafeInteger(value.baseRevision)) return false
  const item = value.item
  if (!isRecord(item) || parseItemId(item.id) === null || parseCardId(item.cardId) === null ||
    typeof item.quantity !== 'number' || !Number.isSafeInteger(item.quantity) || item.quantity < 1 ||
    typeof item.addedAt !== 'number' || !Number.isSafeInteger(item.addedAt) ||
    typeof item.variant !== 'string' || !VARIANTS.some((candidate) => candidate === item.variant) ||
    typeof item.condition !== 'string' || !CONDITIONS.some((candidate) => candidate === item.condition)) return false
  const pricePaid = item.pricePaid
  if (pricePaid === null) return true
  if (!isRecord(pricePaid)) return false
  return typeof pricePaid.amount === 'number' && Number.isFinite(pricePaid.amount) &&
    typeof pricePaid.currency === 'string' && CURRENCIES.some((candidate) => candidate === pricePaid.currency)
}

export async function resolveSyncConflict(
  identity: string,
  conflictId: string,
  resolution: 'keep-local' | 'keep-cloud',
  resolveCard: (cardId: string) => Promise<CardSummary>,
): Promise<CollectionOperation | null> {
  const current = (await listSyncConflicts(identity)).find((conflict) => conflict.id === conflictId)
  if (current?.remote === undefined) return null
  if (current.resolutionOperation !== undefined) return current.resolutionOperation
  const remote = current.remote
  const remoteItem = remote.kind === 'delete' ? null : remote.item
  const card = resolution === 'keep-cloud' && remoteItem !== null
    ? await resolveCard(remoteItem.cardId)
    : undefined
  const database = await openDatabase()
  let queued: CollectionOperation | null = null
  await new Promise<void>((resolve, reject) => {
    const stores = resolution === 'keep-local'
      ? ['items', 'sync', 'syncConflicts', 'outbox', 'outboxOrder']
      : ['items', 'cards', 'sync', 'syncConflicts']
    const transaction = database.transaction(stores, 'readwrite')
    const items = transaction.objectStore('items')
    const sync = transaction.objectStore('sync')
    const conflicts = transaction.objectStore('syncConflicts')
    const conflictRequest = conflicts.get(conflictId)
    const bindingRequest = sync.get('binding')
    const localId = current.local?.id
    const localRequest = localId === undefined ? null : items.get(localId)
    let conflictRow: unknown
    let localRow: unknown
    let bindingRow: unknown
    let conflictReady = false
    let localReady = localRequest === null
    let bindingReady = false
    const abort = () => { try { transaction.abort() } catch { /* Transaction already ended. */ } }
    const apply = () => {
      if (!conflictReady || !localReady || !bindingReady) return
      if (!isCurrentBinding(bindingRow, identity)) {
        abort()
        return
      }
      if (!isRecord(conflictRow) || conflictRow.identity !== identity || conflictRow.resolvedAt !== undefined) {
        abort()
        return
      }
      const local = localRow === undefined ? null : isItem(localRow) ? localRow : null
      if (localId !== undefined && local === null) { abort(); return }
      if (resolution === 'keep-cloud') {
        if (remoteItem === null) {
          if (remote.kind !== 'delete') { abort(); return }
          items.delete(remote.itemId)
        } else {
          if (card === undefined || card.id !== remoteItem.cardId) { abort(); return }
          if (local !== null && local.id !== remoteItem.id) items.delete(local.id)
          items.put({ ...remoteItem, revision: remote.kind === 'create' ? 1 : remote.baseRevision + 1,
            configurationKey: configurationKey(remoteItem.cardId, remoteItem.variant, remoteItem.condition) })
          transaction.objectStore('cards').put(card)
        }
        advanceLocalCursor(sync, identity, current.sequence ?? 0)
        conflicts.put({ ...conflictRow, resolvedAt: Date.now(), resolution })
      } else {
        if (local === null || current.local === undefined) { abort(); return }
        const opId = crypto.randomUUID()
        let next: CollectionItem
        let previous: CollectionItem | null
        if (remoteItem === null) {
          next = { ...local, id: newItemId(), revision: 1 }
          previous = null
        } else {
          const remoteRevision = remote.kind === 'create' ? 1 : remote.baseRevision + 1
          next = { ...local, id: remoteItem.id, revision: remoteRevision + 1 }
          previous = { ...remoteItem, revision: remoteRevision,
            configurationKey: configurationKey(remoteItem.cardId, remoteItem.variant, remoteItem.condition) }
          if (local.id !== next.id) items.delete(local.id)
        }
        items.put(next)
        recordCollectionWrite(transaction, next, previous, opId)
        removeQueuedOperations(transaction, identity, [local.id, ...(remoteItem === null ? [] : [remoteItem.id])], opId)
        const binding = isRecord(bindingRow) && isRecord(bindingRow.binding) ? bindingRow.binding : null
        if (binding === null || binding.identity !== identity) { abort(); return }
        queued = previous === null
          ? { kind: 'create', opId, deviceId: String(binding.deviceId), item: toSyncItem(next) }
          : { kind: 'replace', opId, deviceId: String(binding.deviceId), baseRevision: previous.revision, item: toSyncItem(next) }
        if (queued === null) { abort(); return }
        conflicts.put({ ...conflictRow, resolution, resolutionOperationId: opId, resolutionOperation: queued })
      }
    }
    conflictRequest.onsuccess = () => { conflictRow = conflictRequest.result; conflictReady = true; apply() }
    conflictRequest.onerror = abort
    bindingRequest.onsuccess = () => { bindingRow = bindingRequest.result; bindingReady = true; apply() }
    bindingRequest.onerror = abort
    if (localRequest !== null) {
      localRequest.onsuccess = () => { localRow = localRequest.result; localReady = true; apply() }
      localRequest.onerror = abort
    }
    transaction.oncomplete = () => resolve()
    transaction.onabort = transaction.onerror = () => reject(transaction.error ?? new Error('Conflict resolution failed'))
  })
  return queued
}

export async function finishSyncConflict(identity: string, conflictId: string, operationId: string): Promise<boolean> {
  const database = await openDatabase()
  const transaction = database.transaction(['sync', 'syncConflicts'], 'readwrite')
  const end = transactionEnd(transaction)
  const sync = transaction.objectStore('sync')
  const conflicts = transaction.objectStore('syncConflicts')
  const bindingRequest = sync.get('binding')
  const conflictRequest = conflicts.get(conflictId)
  let bindingRow: unknown
  let conflictRow: unknown
  let bindingReady = false
  let conflictReady = false
  let finished = false
  const finishIfReady = () => {
    if (!bindingReady || !conflictReady || !isCurrentBinding(bindingRow, identity)) return
    if (!isRecord(conflictRow) || conflictRow.identity !== identity || conflictRow.resolutionOperationId !== operationId) return
    advanceLocalCursor(sync, identity, Number(conflictRow.sequence))
    conflicts.put({ ...conflictRow, resolvedAt: Date.now() })
    finished = true
  }
  bindingRequest.onsuccess = () => { bindingRow = bindingRequest.result; bindingReady = true; finishIfReady() }
  conflictRequest.onsuccess = () => { conflictRow = conflictRequest.result; conflictReady = true; finishIfReady() }
  await end
  return finished
}

function advanceLocalCursor(sync: IDBObjectStore, identity: string, sequence: number) {
  const request = sync.get(`cursor:${identity}`)
  request.onsuccess = () => {
    const stored: unknown = request.result
    const previous = isRecord(stored) && typeof stored.sequence === 'number' ? stored.sequence : 0
    if (sequence > previous) sync.put({ key: `cursor:${identity}`, sequence })
  }
}

function removeQueuedOperations(transaction: IDBTransaction, identity: string, itemIds: string[], exceptOpId: string) {
  const outbox = transaction.objectStore('outbox')
  const request = outbox.index('identity').getAll(identity)
  request.onsuccess = () => {
    for (const row of request.result as OutboxEntry[]) {
      const operationItemId = row.operation.kind === 'delete' ? row.operation.itemId : row.operation.item.id
      if (row.opId === exceptOpId || !itemIds.includes(operationItemId)) continue
      outbox.delete(row.opId)
      transaction.objectStore('outboxOrder').index('opId').openCursor(row.opId).onsuccess = (event) => {
        (event.target as IDBRequest<IDBCursorWithValue | null>).result?.delete()
      }
    }
  }
}

export async function completeSyncLink(input: {
  identity: string
  deviceId: string
  expectedItems: readonly CollectionItem[]
  remoteItems: readonly (SyncItem & { revision: number })[]
  remoteCards: readonly CardSummary[]
  seedRevisions: ReadonlyMap<string, number>
  highWater: number
  isIdentityCurrent?: () => boolean
}): Promise<boolean> {
  if ((input.isIdentityCurrent !== undefined && !input.isIdentityCurrent()) ||
    !isSyncBinding({ identity: input.identity, deviceId: input.deviceId }) ||
    !Number.isSafeInteger(input.highWater) || input.highWater < 0) return false

  const expectedById = new Map(input.expectedItems.map((item) => [item.id, item]))
  if (expectedById.size !== input.expectedItems.length) return false
  const cardsById = new Map<string, CardSummary>()
  for (const card of input.remoteCards) {
    if (!isCard(card)) return false
    cardsById.set(card.id, card)
  }

  const nextItems = new Map(expectedById)
  const incomingIds = new Set<string>()
  for (const remote of input.remoteItems) {
    if (incomingIds.has(remote.id) || !Number.isSafeInteger(remote.revision) || remote.revision < 1) return false
    incomingIds.add(remote.id)
    const existing = expectedById.get(remote.id)
    if (existing !== undefined) {
      if (!sameSyncValue(existing, remote)) return false
      nextItems.set(remote.id, { ...existing, revision: remote.revision })
    } else {
      const card = cardsById.get(remote.cardId)
      if (card === undefined || card.id !== remote.cardId) return false
      nextItems.set(remote.id, {
        ...remote,
        revision: remote.revision,
        configurationKey: configurationKey(remote.cardId, remote.variant, remote.condition),
      })
    }
  }

  const configurations = new Set<string>()
  for (const item of nextItems.values()) {
    if (configurations.has(item.configurationKey)) return false
    configurations.add(item.configurationKey)
    if (!incomingIds.has(item.id)) {
      const revision = input.seedRevisions.get(item.id)
      if (revision === undefined || !Number.isSafeInteger(revision) || revision < 1) return false
      nextItems.set(item.id, { ...item, revision })
    }
  }

  const database = await openDatabase()
  let linked = false
  const transaction = database.transaction(['items', 'cards', 'sync'], 'readwrite')
  const end = transactionEnd(transaction)
  const sync = transaction.objectStore('sync')
  const bindingRequest = sync.get('binding')
  const items = transaction.objectStore('items')
  const allItemsRequest = items.getAll()
  let bindingRow: unknown
  let currentRows: unknown[] = []
  let bindingReady = false
  let rowsReady = false
  const writeWhenReady = () => {
    if (!bindingReady || !rowsReady || linked) return
    if (input.isIdentityCurrent !== undefined && !input.isIdentityCurrent()) {
      transaction.abort()
      return
    }
    if (bindingRow !== undefined) {
      const currentBinding = typeof bindingRow === 'object' && bindingRow !== null && 'binding' in bindingRow
        ? bindingRow.binding
        : null
      if (!isSyncBinding(currentBinding) || currentBinding.identity !== input.identity || currentBinding.deviceId !== input.deviceId) {
        transaction.abort()
        return
      }
    }
    if (!currentRows.every(isItem) || !sameLocalSnapshot(currentRows, input.expectedItems)) {
      transaction.abort()
      return
    }

    for (const [itemId, item] of nextItems) {
      items.put(item)
      const card = cardsById.get(item.cardId)
      if (card !== undefined) transaction.objectStore('cards').put(card)
      else if (!expectedById.has(itemId)) {
        transaction.abort()
        return
      }
    }
    sync.put({ key: 'binding', binding: { identity: input.identity, deviceId: input.deviceId } })
    const cursorKey = `cursor:${input.identity}`
    const cursorRequest = sync.get(cursorKey)
    cursorRequest.onsuccess = () => {
      if (input.isIdentityCurrent !== undefined && !input.isIdentityCurrent()) {
        transaction.abort()
        return
      }
      const raw: unknown = cursorRequest.result
      const previous = typeof raw === 'object' && raw !== null && 'sequence' in raw &&
        typeof raw.sequence === 'number' && Number.isSafeInteger(raw.sequence) && raw.sequence >= 0
        ? raw.sequence
        : 0
      sync.put({ key: cursorKey, sequence: Math.max(previous, input.highWater) })
      linked = true
    }
    cursorRequest.onerror = () => transaction.abort()
  }
  bindingRequest.onsuccess = () => { bindingRow = bindingRequest.result; bindingReady = true; writeWhenReady() }
  allItemsRequest.onsuccess = () => { currentRows = allItemsRequest.result; rowsReady = true; writeWhenReady() }
  bindingRequest.onerror = allItemsRequest.onerror = () => transaction.abort()
  try {
    await end
  } catch {
    return false
  }
  if (linked) announceChange()
  return linked
}

function sameSyncValue(item: CollectionItem, remote: SyncItem): boolean {
  return item.id === remote.id && item.cardId === remote.cardId && item.quantity === remote.quantity &&
    item.variant === remote.variant && item.condition === remote.condition && item.addedAt === remote.addedAt &&
    JSON.stringify(item.pricePaid) === JSON.stringify(remote.pricePaid)
}

function sameLocalSnapshot(current: readonly unknown[], expected: readonly CollectionItem[]): boolean {
  if (current.length !== expected.length) return false
  const currentItems = current.filter(isItem).sort((left, right) => left.id.localeCompare(right.id))
  const expectedItems = [...expected].sort((left, right) => left.id.localeCompare(right.id))
  return currentItems.length === expectedItems.length && currentItems.every((item, index) => {
    const expectedItem = expectedItems[index]
    return expectedItem !== undefined && JSON.stringify(item) === JSON.stringify(expectedItem)
  })
}

function isCurrentBinding(value: unknown, identity: string): boolean {
  if (typeof value !== 'object' || value === null || !('binding' in value)) return false
  return isSyncBinding(value.binding) && value.binding.identity === identity
}

function isOutboxEntry(value: unknown): value is OutboxEntry {
  if (typeof value !== 'object' || value === null) return false
  const row = value as Record<string, unknown>
  if (typeof row.opId !== 'string' || typeof row.identity !== 'string' || typeof row.sequence !== 'number' ||
    !Number.isSafeInteger(row.sequence) || typeof row.operation !== 'object' || row.operation === null) return false
  const operation = row.operation as Record<string, unknown>
  return operation.opId === row.opId && typeof operation.deviceId === 'string' &&
    (operation.kind === 'create' || operation.kind === 'replace' || operation.kind === 'delete')
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('Falha de leitura'))
  })
}

function transactionEnd(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error ?? new Error('A coleção não pôde ser lida'))
    transaction.onerror = () => reject(transaction.error ?? new Error('A coleção não pôde ser lida'))
  })
}

export async function listCollection(): Promise<CollectionEntry[]> {
  const database = await openDatabase()
  const transaction = database.transaction(['items', 'cards'], 'readonly')
  const end = transactionEnd(transaction)
  const itemsRequest = requestResult<unknown[]>(transaction.objectStore('items').getAll())
  const cardsRequest = requestResult<unknown[]>(transaction.objectStore('cards').getAll())
  const [items, cards] = await Promise.all([itemsRequest, cardsRequest])
  await end
  if (!items.every(isItem) || !cards.every(isCard)) throw new Error('Dados locais inválidos')
  const byId = new Map(cards.map((card) => [card.id, card]))
  const entries = items.map((item) => {
    const card = byId.get(item.cardId)
    if (card === undefined) throw new Error('Carta local ausente')
    return { item, card }
  })
  entries.sort((left, right) => right.item.addedAt - left.item.addedAt)
  return entries
}

export async function importCollectionBackup(value: unknown): Promise<ImportResult> {
  const parsed = parseCollectionBackup(value)
  if (parsed.kind === 'invalid') return parsed

  try {
    const database = await openDatabase()
    const result = await new Promise<ImportResult>((resolve) => {
      const transaction = database.transaction(['items', 'cards', 'imports', 'outbox', 'outboxOrder', 'sync'], 'readwrite')
      const itemsStore = transaction.objectStore('items')
      const cardsStore = transaction.objectStore('cards')
      const importsStore = transaction.objectStore('imports')
      let outcome: ImportResult = { kind: 'storage-error' }
      transaction.oncomplete = () => resolve(outcome)
      transaction.onabort = () => resolve(outcome.kind === 'conflict' ? outcome : { kind: 'storage-error' })
      transaction.onerror = () => resolve(outcome.kind === 'conflict' ? outcome : { kind: 'storage-error' })
      const itemsRequest = itemsStore.getAll()
      const cardsRequest = cardsStore.getAll()
      const importsRequest = importsStore.getAll()
      let items: unknown[] | null = null
      let cards: unknown[] | null = null
      let markers: unknown[] | null = null
      const applyWhenReady = () => {
        if (items === null || cards === null || markers === null) return
        const parsedMarkers = parseImportMarkers(markers)
        if (!items.every(isItem) || !cards.every(isCard)) {
          outcome = { kind: 'conflict', reason: 'invalid-local' }
          transaction.abort()
          return
        }
        if (parsedMarkers === null) {
          outcome = { kind: 'conflict', reason: 'invalid-local' }
          transaction.abort()
          return
        }

        const byId = new Map(items.map((item) => [item.id, item]))
        const byConfiguration = new Map(items.map((item) => [item.configurationKey, item]))
        const cardIds = new Set(cards.map((card) => card.id))
        const importsById = new Map(parsedMarkers.map((marker) => [marker.itemId, marker.signature]))
        let added = 0
        let merged = 0
        let unchanged = 0

        for (const { item, card } of parsed.backup.entries) {
          const signature = importSignature({ item, card })
          const importedSignature = importsById.get(item.id)
          if (importedSignature !== undefined && importedSignature !== signature) {
            outcome = { kind: 'conflict', reason: 'existing-item' }
            transaction.abort()
            return
          }

          if (!cardIds.has(card.id)) {
            cardsStore.put(card)
            cardIds.add(card.id)
          }
          const sameId = byId.get(item.id)
          if (sameId !== undefined) {
            if (JSON.stringify(sameId) !== JSON.stringify(item)) {
              outcome = { kind: 'conflict', reason: 'existing-item' }
              transaction.abort()
              return
            }
            importsStore.put({ itemId: item.id, signature })
            importsById.set(item.id, signature)
            unchanged += 1
            continue
          }

          const sameConfiguration = byConfiguration.get(item.configurationKey)
          if (sameConfiguration !== undefined) {
            if (importedSignature !== undefined) {
              unchanged += 1
              continue
            }
            const quantity = sameConfiguration.quantity + item.quantity
            if (!Number.isSafeInteger(quantity) || quantity > 100_000) {
              outcome = { kind: 'conflict', reason: 'quantity-limit' }
              transaction.abort()
              return
            }
            const revision = nextRevision(sameConfiguration.revision)
            if (revision === null) {
              outcome = { kind: 'conflict', reason: 'revision-limit' }
              transaction.abort()
              return
            }
            const mergedItem: CollectionItem = {
              ...sameConfiguration,
              quantity,
              revision,
            }
            itemsStore.put(mergedItem)
            recordCollectionWrite(transaction, mergedItem, sameConfiguration)
            byId.set(mergedItem.id, mergedItem)
            byConfiguration.set(mergedItem.configurationKey, mergedItem)
            merged += 1
          } else {
            itemsStore.put(item)
            recordCollectionWrite(transaction, item, null)
            byId.set(item.id, item)
            byConfiguration.set(item.configurationKey, item)
            added += 1
          }
          importsStore.put({ itemId: item.id, signature })
          importsById.set(item.id, signature)
        }
        outcome = { kind: 'imported', added, merged, unchanged }
      }

      itemsRequest.onsuccess = () => {
        items = itemsRequest.result
        applyWhenReady()
      }
      cardsRequest.onsuccess = () => {
        cards = cardsRequest.result
        applyWhenReady()
      }
      importsRequest.onsuccess = () => {
        markers = importsRequest.result
        applyWhenReady()
      }
    })
    if (result.kind === 'imported' && result.added + result.merged > 0) announceChange()
    return result
  } catch {
    return { kind: 'storage-error' }
  }
}

function announceChange(): void {
  window.dispatchEvent(new Event(CHANGE_EVENT))
  try {
    const channel = new BroadcastChannel(CHANGE_EVENT)
    channel.postMessage('changed')
    channel.close()
  } catch {
    // This browser may not expose BroadcastChannel. The current tab still refreshes.
  }
}

export function observeCollectionChanges(onChange: () => void): () => void {
  const localChange = () => onChange()
  window.addEventListener(CHANGE_EVENT, localChange)
  let channel: BroadcastChannel | null = null
  try {
    channel = new BroadcastChannel(CHANGE_EVENT)
    channel.onmessage = onChange
  } catch {
    channel = null
  }
  return () => {
    window.removeEventListener(CHANGE_EVENT, localChange)
    channel?.close()
  }
}

export async function addToCollection(card: CardSummary, draft: Extract<ParsedDraft, { kind: 'valid' }>): Promise<WriteResult> {
  try {
    const database = await openDatabase()
    const result = await new Promise<WriteResult>((resolve) => {
      const transaction = database.transaction(['items', 'cards', 'outbox', 'outboxOrder', 'sync'], 'readwrite')
      let outcome: WriteResult = { kind: 'storage-error' }
      transaction.oncomplete = () => resolve(outcome)
      transaction.onabort = () => resolve({ kind: 'storage-error' })
      const items = transaction.objectStore('items')
      const key = configurationKey(card.id, draft.variant, draft.condition)
      const existingRequest = items.index('configurationKey').get(key)
      existingRequest.onsuccess = () => {
        const existing: unknown = existingRequest.result
        if (existing !== undefined && !isItem(existing)) { transaction.abort(); return }
        let item: CollectionItem
        if (existing === undefined) {
          item = {
            id: newItemId(), cardId: card.id, quantity: draft.quantity, variant: draft.variant,
            condition: draft.condition, pricePaid: draft.pricePaid, addedAt: Date.now(),
            revision: 1, configurationKey: key,
          }
        } else {
          const revision = nextRevision(existing.revision)
          if (revision === null) {
            outcome = { kind: 'conflict', reason: 'revision-limit' }
            return
          }
          item = { ...existing, quantity: existing.quantity + draft.quantity, revision }
        }
        if (!Number.isSafeInteger(item.quantity) || item.quantity > 100_000) {
          outcome = { kind: 'conflict', reason: 'duplicate' }
          return
        }
        transaction.objectStore('cards').put(card)
        items.put(item)
        recordCollectionWrite(transaction, item, existing === undefined ? null : existing)
        outcome = { kind: 'saved', item }
      }
    })
    if (result.kind === 'saved') announceChange()
    return result
  } catch {
    return { kind: 'storage-error' }
  }
}

export async function editCollectionItem(itemId: ItemId, expectedRevision: number, draft: Extract<ParsedDraft, { kind: 'valid' }>): Promise<WriteResult> {
  try {
    const database = await openDatabase()
    const result = await new Promise<WriteResult>((resolve) => {
      const transaction = database.transaction(['items', 'outbox', 'outboxOrder', 'sync'], 'readwrite')
      let outcome: WriteResult = { kind: 'storage-error' }
      transaction.oncomplete = () => resolve(outcome)
      transaction.onabort = () => resolve({ kind: 'storage-error' })
      const items = transaction.objectStore('items')
      const request = items.get(itemId)
      request.onsuccess = () => {
        const raw: unknown = request.result
        if (raw === undefined) { outcome = { kind: 'conflict', reason: 'missing' }; return }
        if (!isItem(raw)) { transaction.abort(); return }
        if (raw.revision !== expectedRevision) { outcome = { kind: 'conflict', reason: 'stale' }; return }
        const revision = nextRevision(raw.revision)
        if (revision === null) { outcome = { kind: 'conflict', reason: 'revision-limit' }; return }
        const key = configurationKey(raw.cardId, draft.variant, draft.condition)
        const save = () => {
          const item: CollectionItem = {
            ...raw,
            quantity: draft.quantity,
            variant: draft.variant,
            condition: draft.condition,
            pricePaid: draft.pricePaid,
            revision,
            configurationKey: key,
          }
          items.put(item)
          recordCollectionWrite(transaction, item, raw)
          outcome = { kind: 'saved', item }
        }
        if (key === raw.configurationKey) { save(); return }
        const duplicateRequest = items.index('configurationKey').get(key)
        duplicateRequest.onsuccess = () => {
          if (duplicateRequest.result !== undefined) {
            outcome = { kind: 'conflict', reason: 'duplicate' }
          } else {
            save()
          }
        }
      }
    })
    if (result.kind === 'saved') announceChange()
    return result
  } catch {
    return { kind: 'storage-error' }
  }
}

export async function removeCollectionItem(itemId: ItemId, expectedRevision: number): Promise<WriteResult> {
  try {
    const database = await openDatabase()
    const result = await new Promise<WriteResult>((resolve) => {
      const transaction = database.transaction(['items', 'outbox', 'outboxOrder', 'sync'], 'readwrite')
      let outcome: WriteResult = { kind: 'storage-error' }
      transaction.oncomplete = () => resolve(outcome)
      transaction.onabort = () => resolve({ kind: 'storage-error' })
      const items = transaction.objectStore('items')
      const request = items.get(itemId)
      request.onsuccess = () => {
        const raw: unknown = request.result
        if (raw === undefined) { outcome = { kind: 'conflict', reason: 'missing' }; return }
        if (!isItem(raw)) { transaction.abort(); return }
        if (raw.revision !== expectedRevision) { outcome = { kind: 'conflict', reason: 'stale' }; return }
        recordCollectionDelete(transaction, raw)
        items.delete(itemId)
        outcome = { kind: 'removed' }
      }
    })
    if (result.kind === 'removed') announceChange()
    return result
  } catch {
    return { kind: 'storage-error' }
  }
}
