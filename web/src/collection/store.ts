import {
  CONDITIONS, CURRENCIES, VARIANTS, configurationKey, newItemId, nextRevision, parseCardId, parseItemId,
} from '../domain/model.ts'
import type { CardSummary, CollectionEntry, CollectionItem, ItemId, ParsedDraft } from '../domain/model.ts'
import { importSignature, parseCollectionBackup, parseImportMarkers } from './backup.ts'
import { isSyncBinding, recordCollectionDelete, recordCollectionWrite, type OutboxEntry, type SyncBinding } from '../sync/outbox.ts'
import type { CollectionOperation } from '../sync/policy.ts'

const DATABASE_NAME = 'tcg-colecao-web'
const VERSION = 4
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
