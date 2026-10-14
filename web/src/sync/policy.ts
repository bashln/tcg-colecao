import type { CardVariant, Condition, Currency, ItemId, CardId } from '../domain/model.ts'

export interface SyncItem {
  id: ItemId
  cardId: CardId
  quantity: number
  variant: CardVariant
  condition: Condition
  pricePaid: { amount: number; currency: Currency } | null
  addedAt: number
}

export type CollectionOperation =
  | { kind: 'create'; opId: string; deviceId: string; item: SyncItem }
  | { kind: 'replace'; opId: string; deviceId: string; baseRevision: number; item: SyncItem }
  | { kind: 'delete'; opId: string; deviceId: string; baseRevision: number; itemId: ItemId }

export interface SyncRow {
  id: ItemId
  revision: number
  value: SyncItem | null
}

export interface SyncState {
  rows: readonly SyncRow[]
  appliedOperations: ReadonlyMap<string, string>
}

export type ApplyResult =
  | { kind: 'applied'; rows: readonly SyncRow[]; appliedOperations: ReadonlyMap<string, string> }
  | { kind: 'already-applied'; rows: readonly SyncRow[]; appliedOperations: ReadonlyMap<string, string> }
  | { kind: 'conflict'; reason: 'stale' | 'duplicate-configuration' | 'deleted' | 'missing' | 'id-collision' | 'revision-limit'; rows: readonly SyncRow[]; appliedOperations: ReadonlyMap<string, string> }

export function applyOperation(state: SyncState, operation: CollectionOperation): ApplyResult {
  const signature = JSON.stringify(operation)
  const previousSignature = state.appliedOperations.get(operation.opId)
  if (previousSignature !== undefined) {
    return previousSignature === signature
      ? { ...state, kind: 'already-applied' }
      : { ...state, kind: 'conflict', reason: 'id-collision' }
  }

  const current = state.rows.find((row) => row.id === (operation.kind === 'delete' ? operation.itemId : operation.item.id))
  let nextRow: SyncRow
  if (operation.kind === 'create') {
    if (current !== undefined) return conflict(state, current.value === null ? 'deleted' : 'stale')
    if (state.rows.some((row) => row.value !== null && sameConfiguration(row.value, operation.item))) {
      return conflict(state, 'duplicate-configuration')
    }
    nextRow = { id: operation.item.id, revision: 1, value: copyItem(operation.item) }
  } else {
    if (current === undefined) return conflict(state, 'missing')
    if (current.revision !== operation.baseRevision) return conflict(state, 'stale')
    if (current.value === null) return conflict(state, 'deleted')
    const revision = current.revision + 1
    if (!Number.isSafeInteger(revision)) return conflict(state, 'revision-limit')
    if (operation.kind === 'delete') {
      nextRow = { ...current, revision, value: null }
    } else {
      if (operation.item.id !== current.id) return conflict(state, 'id-collision')
      if (state.rows.some((row) => row.id !== current.id && row.value !== null && sameConfiguration(row.value, operation.item))) {
        return conflict(state, 'duplicate-configuration')
      }
      nextRow = { id: current.id, revision, value: copyItem(operation.item) }
    }
  }

  const rows = current === undefined
    ? [...state.rows, nextRow]
    : state.rows.map((row) => row.id === nextRow.id ? nextRow : row)
  const appliedOperations = new Map(state.appliedOperations)
  appliedOperations.set(operation.opId, signature)
  return { kind: 'applied', rows, appliedOperations }
}

function conflict(state: SyncState, reason: Extract<ApplyResult, { kind: 'conflict' }>['reason']): ApplyResult {
  return { ...state, kind: 'conflict', reason }
}

function sameConfiguration(left: SyncItem, right: SyncItem): boolean {
  return left.cardId === right.cardId && left.variant === right.variant && left.condition === right.condition
}

function copyItem(item: SyncItem): SyncItem {
  return { ...item, pricePaid: item.pricePaid === null ? null : { ...item.pricePaid } }
}
