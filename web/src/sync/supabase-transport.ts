import {
  CONDITIONS,
  CURRENCIES,
  VARIANTS,
  parseCardId,
  parseItemId,
  type CardVariant,
  type Condition,
  type Currency,
} from '../domain/model.ts'
import type { CollectionOperation, SyncItem } from './policy.ts'

export interface SequencedOperation {
  sequence: number
  operation: CollectionOperation
}

export interface PushOutcome {
  kind: 'applied' | 'already-applied' | 'conflict'
  reason?: string
  sequence?: number
}

export interface PullPage {
  operations: SequencedOperation[]
  highWaterMark: number
}

/**
 * Minimal authenticated boundary the transport needs. Web injects a GoTrue-backed
 * implementation; tests inject a stub. Keeping it narrow means auth token handling
 * never leaks into operation mapping.
 */
export interface SupabaseHttp {
  rpc(fn: string, body: Record<string, unknown>): Promise<unknown>
  user(): Promise<{ id: string; email: string | null } | null>
}

export class SupabaseSyncError extends Error {
  readonly kind: 'unauthorized' | 'device-revoked' | 'unavailable'

  constructor(kind: 'unauthorized' | 'device-revoked' | 'unavailable', message: string) {
    super(message)
    this.kind = kind
  }
}

function isVariant(value: unknown): value is CardVariant {
  return VARIANTS.some((variant) => variant === value)
}

function isCondition(value: unknown): value is Condition {
  return CONDITIONS.some((condition) => condition === value)
}

function isCurrency(value: unknown): value is Currency {
  return CURRENCIES.some((currency) => currency === value)
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value !== null && typeof value === 'object') {
    const source = value as Record<string, unknown>
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(source).sort()) sorted[key] = canonical(source[key])
    return sorted
  }
  return value
}

async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function toSerializable(operation: CollectionOperation): Record<string, unknown> {
  if (operation.kind === 'delete') {
    return { kind: 'delete', opId: operation.opId, itemId: operation.itemId, baseRevision: operation.baseRevision }
  }
  const item = {
    id: operation.item.id,
    cardId: operation.item.cardId,
    quantity: operation.item.quantity,
    variant: operation.item.variant,
    condition: operation.item.condition,
    pricePaid: operation.item.pricePaid,
    addedAt: operation.item.addedAt,
  }
  return operation.kind === 'create'
    ? { kind: 'create', opId: operation.opId, item }
    : { kind: 'replace', opId: operation.opId, item, baseRevision: operation.baseRevision }
}

/** Deterministic content signature so the server can detect opId reuse with different content. */
async function signatureFor(operation: CollectionOperation): Promise<string> {
  return sha256Hex(JSON.stringify(canonical(toSerializable(operation))))
}

function asRecord(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object') throw new SupabaseSyncError('unavailable', 'Resposta inválida do servidor')
  return value as Record<string, unknown>
}

function isUnauthorized(message: string): boolean {
  return /not authenticated|permission denied|JWT|jwt/i.test(message)
}

export function createSupabaseTransport(input: { http: SupabaseHttp }) {
  async function call(fn: string, body: Record<string, unknown>): Promise<Record<string, unknown>> {
    let raw: unknown
    try {
      raw = await input.http.rpc(fn, body)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (isUnauthorized(message)) throw new SupabaseSyncError('unauthorized', message)
      throw new SupabaseSyncError('unavailable', message)
    }
    const response = asRecord(raw)
    const message = typeof response.message === 'string' ? response.message : ''
    if (response.status === 'unauthorized' || isUnauthorized(message)) {
      throw new SupabaseSyncError('unauthorized', message || 'Sem sessão válida')
    }
    if (response.status === 'device-revoked') {
      throw new SupabaseSyncError('device-revoked', 'Este dispositivo foi desconectado da conta')
    }
    return response
  }

  return {
    async user() {
      return input.http.user()
    },

    async push(identity: string, operations: readonly CollectionOperation[]): Promise<PushOutcome[]> {
      void identity
      if (operations.length === 0) return []
      const first = operations[0]
      if (first === undefined) return []

      const serialized = await Promise.all(
        operations.map(async (operation) => {
          const base = toSerializable(operation)
          return { ...base, deviceId: first.deviceId, signature: await signatureFor(operation) }
        }),
      )

      const response = await call('push_collection_operations', {
        p_device_id: first.deviceId,
        p_operations: serialized,
      })

      if (response.status !== 'ok') {
        throw new SupabaseSyncError('unavailable', `Push recusado: ${String(response.status ?? 'desconhecido')}`)
      }

      const results = Array.isArray(response.results) ? response.results : []
      return results.map((entry): PushOutcome => {
        const row = asRecord(entry)
        const reason = typeof row.reason === 'string' ? row.reason : undefined
        const revision = typeof row.revision === 'number' ? row.revision : undefined
        if (row.status === 'applied') return { kind: 'applied', ...(revision === undefined ? {} : { sequence: revision }) }
        if (row.status === 'already-applied') {
          return { kind: 'already-applied', ...(revision === undefined ? {} : { sequence: revision }) }
        }
        return { kind: 'conflict', ...(reason === undefined ? {} : { reason }) }
      })
    },

    async pull(identity: string, afterSequence: number): Promise<PullPage> {
      void identity
      const response = await call('pull_collection_operations', {
        p_after_sequence: afterSequence,
        p_page_limit: 500,
      })

      const raw = Array.isArray(response.operations) ? response.operations : []
      const operations: SequencedOperation[] = raw.map((entry) => {
        const row = asRecord(entry)
        const sequence = typeof row.serverSequence === 'number' ? row.serverSequence : 0
        return { sequence, operation: parseRemoteOperation(row) }
      })
      operations.sort((left, right) => left.sequence - right.sequence)

      const highWaterMark = typeof response.highWater === 'number' ? response.highWater : afterSequence
      return { operations, highWaterMark }
    },
  }
}

function parseRemoteOperation(row: Record<string, unknown>): CollectionOperation {
  const deviceId = typeof row.deviceId === 'string' ? row.deviceId : 'unknown-device'
  const opId = typeof row.opId === 'string' ? row.opId : crypto.randomUUID()
  const baseRevision = typeof row.baseRevision === 'number' ? row.baseRevision : 0

  if (row.kind === 'delete') {
    const deletedId = parseItemId(row.itemId)
    if (deletedId === null) throw new SupabaseSyncError('unavailable', 'Item remoto inválido')
    return { kind: 'delete', opId, deviceId, itemId: deletedId, baseRevision }
  }

  const rawItem = asRecord(row.item)
  const id = parseItemId(rawItem.id)
  const cardId = parseCardId(rawItem.cardId)
  const variant = rawItem.variant
  const condition = rawItem.condition
  if (id === null) throw new SupabaseSyncError('unavailable', 'Item remoto inválido')
  if (cardId === null) throw new SupabaseSyncError('unavailable', 'Carta remota inválida')
  if (!isVariant(variant)) throw new SupabaseSyncError('unavailable', 'Variante remota desconhecida')
  if (!isCondition(condition)) throw new SupabaseSyncError('unavailable', 'Condição remota desconhecida')
  const quantity = Number(rawItem.quantity)
  if (!Number.isSafeInteger(quantity) || quantity < 1) throw new SupabaseSyncError('unavailable', 'Quantidade remota inválida')
  const addedAt = Number(rawItem.addedAt)
  if (!Number.isSafeInteger(addedAt)) throw new SupabaseSyncError('unavailable', 'Data remota inválida')

  const item: SyncItem = {
    id,
    cardId,
    quantity,
    variant,
    condition,
    pricePaid: parseRemotePrice(rawItem.pricePaid),
    addedAt,
  }

  return row.kind === 'create'
    ? { kind: 'create', opId, deviceId, item }
    : { kind: 'replace', opId, deviceId, item, baseRevision }
}

function parseRemotePrice(value: unknown): SyncItem['pricePaid'] {
  if (value === null || value === undefined) return null
  const raw = asRecord(value)
  const amount = Number(raw.amount)
  const currency = raw.currency
  if (!Number.isSafeInteger(amount) || amount < 0) throw new SupabaseSyncError('unavailable', 'Preço remoto inválido')
  if (!isCurrency(currency)) throw new SupabaseSyncError('unavailable', 'Moeda remota desconhecida')
  return { amount, currency: currency satisfies Currency }
}
