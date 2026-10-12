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
  /** Server echoes the operation id so callers can map results without relying on order. */
  opId?: string
}

export interface PullPage {
  operations: SequencedOperation[]
  highWaterMark: number
}

export interface LinkPreview {
  planId: string
  digest: string
  expiresAt: string
  remoteHighWater: number
  localOnly: string[]
  cloudOnly: string[]
  identical: string[]
  conflicting: { itemId: string; reason: string }[]
  duplicateConfigurations: { localItemId: string; remoteItemId: string }[]
  remoteItems: (SyncItem & { revision: number })[]
}

export type LinkCommitResult =
  | { kind: 'linked'; highWater: number; outcomes: PushOutcome[] }
  | { kind: 'partial'; highWater: number; outcomes: PushOutcome[] }
  | { kind: 'stale-preview' | 'digest-mismatch' | 'expired' | 'consumed' | 'not-found' | 'payload-mismatch' }

/**
 * Minimal authenticated boundary the transport needs. Web injects a GoTrue-backed
 * implementation; tests inject a stub. Keeping it narrow means auth token handling
 * never leaks into operation mapping.
 */
export interface SupabaseHttp {
  rpc(fn: string, body: Record<string, unknown>, expectedUserId?: string): Promise<unknown>
  user(): Promise<{ id: string; email: string | null } | null>
}

export class SupabaseSyncError extends Error {
  readonly kind: 'unauthorized' | 'device-revoked' | 'unavailable' | 'preflight' | 'invalid-response' | 'commit-uncertain'

  constructor(kind: 'unauthorized' | 'device-revoked' | 'unavailable' | 'preflight' | 'invalid-response' | 'commit-uncertain', message: string) {
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

function toMinorUnits(amount: number): number {
  const scaled = amount * 100
  const rounded = Math.round(scaled)
  if (!Number.isFinite(amount) || !Number.isSafeInteger(rounded) || Math.abs(scaled - rounded) > 1e-7) {
    throw new SupabaseSyncError('unavailable', 'O preço pago precisa ter no máximo duas casas decimais')
  }
  return rounded
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
  const item = serializeSyncItem(operation.item)
  return operation.kind === 'create'
    ? { kind: 'create', opId: operation.opId, item }
    : { kind: 'replace', opId: operation.opId, item, baseRevision: operation.baseRevision }
}

function serializeSyncItem(item: SyncItem): Record<string, unknown> {
  return {
    id: item.id,
    cardId: item.cardId,
    quantity: item.quantity,
    variant: item.variant,
    condition: item.condition,
    pricePaid: item.pricePaid === null
      ? null
      : { amount: toMinorUnits(item.pricePaid.amount), currency: item.pricePaid.currency },
    addedAt: item.addedAt,
  }
}

/** Deterministic content signature so the server can detect opId reuse with different content. */
async function signatureFor(operation: CollectionOperation): Promise<string> {
  return sha256Hex(JSON.stringify(canonical(toSerializable(operation))))
}

export async function syncSnapshotDigest(items: readonly SyncItem[]): Promise<string> {
  if (items.length > 5000) throw new SupabaseSyncError('unavailable', 'A prévia aceita até 5.000 posses')
  const localItems = items.map(serializeSyncItem).sort((left, right) =>
    String(left.id).localeCompare(String(right.id)))
  return sha256Hex(JSON.stringify(canonical(localItems)))
}

function asRecord(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object') throw new SupabaseSyncError('invalid-response', 'Resposta inválida do servidor')
  return value as Record<string, unknown>
}

function isUnauthorized(message: string): boolean {
  return /not authenticated|permission denied|JWT|jwt|conta mudou/i.test(message)
}

export function createSupabaseTransport(input: { http: SupabaseHttp }) {
  async function call(fn: string, body: Record<string, unknown>, expectedIdentity?: string): Promise<Record<string, unknown>> {
    let raw: unknown
    try {
      raw = await input.http.rpc(fn, body, expectedIdentity)
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

    async prepareLink(items: readonly SyncItem[]): Promise<LinkPreview> {
      const localItems = items.map(serializeSyncItem).sort((left, right) =>
        String(left.id).localeCompare(String(right.id)))
      const digest = await syncSnapshotDigest(items)
      const response = await call('prepare_collection_link', {
        p_local_digest: digest,
        p_local_items: localItems,
        p_remote_sequence: 0,
      })
      const preview = asRecord(response.preview)
      const planId = response.planId
      const expiresAt = response.expiresAt
      const remoteHighWater = response.remoteHighWater
      if (typeof planId !== 'string' || planId.length < 8 ||
        typeof expiresAt !== 'string' || !Number.isFinite(Date.parse(expiresAt)) ||
        typeof remoteHighWater !== 'number' || !Number.isSafeInteger(remoteHighWater) || remoteHighWater < 0) {
        throw new SupabaseSyncError('unavailable', 'A prévia de vínculo retornou dados inválidos')
      }
      const snapshot = await call('read_collection_link_snapshot', { p_expected_high_water: remoteHighWater })
      if (snapshot.highWater !== remoteHighWater || !Array.isArray(snapshot.items)) {
        throw new SupabaseSyncError('unavailable', 'A coleção remota mudou durante a prévia. Gere uma nova prévia.')
      }
      return {
        planId,
        digest,
        expiresAt,
        remoteHighWater,
        localOnly: parsePreviewIds(preview.localOnly),
        cloudOnly: parsePreviewIds(preview.cloudOnly),
        identical: parsePreviewIds(preview.identical),
        conflicting: parseConflicts(preview.conflicting),
        duplicateConfigurations: parseDuplicateConfigurations(preview.duplicateConfigurations),
        remoteItems: snapshot.items.map(parseSnapshotItem),
      }
    },

    async commitLink(plan: LinkPreview, deviceId: string, operations: readonly CollectionOperation[]): Promise<LinkCommitResult> {
      if (deviceId.length < 8 || operations.some((operation) => operation.deviceId !== deviceId)) {
        throw new SupabaseSyncError('preflight', 'Dispositivo inválido para confirmar o vínculo')
      }
      let serialized: Record<string, unknown>[]
      try {
        serialized = await Promise.all(operations.map(async (operation) => ({
          ...toSerializable(operation),
          deviceId,
          signature: await signatureFor(operation),
        })))
      } catch (error) {
        throw new SupabaseSyncError('preflight', error instanceof Error ? error.message : String(error))
      }
      let response: Record<string, unknown>
      try {
        response = await call('commit_collection_link', {
          p_plan_id: plan.planId,
          p_digest: plan.digest,
          p_device_id: deviceId,
          p_operations: serialized,
        })
      } catch (error) {
        if (error instanceof SupabaseSyncError && (error.kind === 'unauthorized' || error.kind === 'invalid-response')) throw error
        throw new SupabaseSyncError('commit-uncertain', 'A resposta da nuvem não chegou e ela pode já ter recebido itens da prévia.')
      }
      try {
        if (response.status === 'linked' || response.status === 'partial') {
          const push = asRecord(response.push)
          if ((push.status !== 'ok' && !(response.status === 'partial' && push.status === 'device-revoked')) ||
            typeof push.highWater !== 'number' || !Number.isSafeInteger(push.highWater) || push.highWater < 0) {
            throw new SupabaseSyncError('invalid-response', 'O servidor retornou um resultado de vínculo inválido')
          }
          const results = Array.isArray(push.results) ? push.results : []
          const outcomes = parsePushOutcomes(results)
          if (push.status !== 'device-revoked' && outcomes.length !== operations.length) {
            throw new SupabaseSyncError('invalid-response', 'O resultado não corresponde às operações enviadas')
          }
          if (response.status === 'linked' && outcomes.some((outcome) => outcome.kind === 'conflict')) {
            throw new SupabaseSyncError('invalid-response', 'O servidor marcou como concluído um vínculo com operações recusadas')
          }
          const result = { highWater: push.highWater, outcomes }
          return response.status === 'linked' ? { kind: 'linked', ...result } : { kind: 'partial', ...result }
        }
        if (response.status === 'stale-preview' || response.status === 'digest-mismatch' ||
          response.status === 'expired' || response.status === 'consumed' || response.status === 'not-found' ||
          response.status === 'payload-mismatch') return { kind: response.status }
        throw new SupabaseSyncError('invalid-response', `O servidor retornou um status de vínculo inválido: ${String(response.status ?? 'desconhecido')}`)
      } catch (error) {
        if (error instanceof SupabaseSyncError && error.kind === 'invalid-response') throw error
        throw new SupabaseSyncError('invalid-response', 'O servidor retornou uma resposta de vínculo inválida')
      }
    },

    async push(identity: string, operations: readonly CollectionOperation[]): Promise<PushOutcome[]> {
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
      }, identity)

      if (response.status !== 'ok') {
        throw new SupabaseSyncError('unavailable', `Push recusado: ${String(response.status ?? 'desconhecido')}`)
      }

      const results = Array.isArray(response.results) ? response.results : []
      return parsePushOutcomes(results)
    },

    async pull(identity: string, afterSequence: number): Promise<PullPage> {
      const response = await call('pull_collection_operations', {
        p_after_sequence: afterSequence,
        p_page_limit: 500,
      }, identity)

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

function parsePreviewIds(value: unknown): string[] {
  if (!Array.isArray(value)) throw new SupabaseSyncError('unavailable', 'A prévia de vínculo retornou uma lista inválida')
  return value.map((id) => {
    const parsed = parseItemId(id)
    if (parsed === null) throw new SupabaseSyncError('unavailable', 'A prévia contém um item inválido')
    return parsed
  })
}

function parseConflicts(value: unknown): { itemId: string; reason: string }[] {
  if (!Array.isArray(value)) throw new SupabaseSyncError('unavailable', 'A prévia de vínculo retornou conflitos inválidos')
  return value.map((entry) => {
    const row = asRecord(entry)
    const itemId = parseItemId(row.itemId)
    const reason = row.reason
    if (itemId === null || typeof reason !== 'string' || reason.length === 0) {
      throw new SupabaseSyncError('unavailable', 'A prévia contém um conflito inválido')
    }
    return { itemId, reason }
  })
}

function parseDuplicateConfigurations(value: unknown): { localItemId: string; remoteItemId: string }[] {
  if (!Array.isArray(value)) throw new SupabaseSyncError('unavailable', 'A prévia de vínculo retornou duplicatas inválidas')
  return value.map((entry) => {
    const row = asRecord(entry)
    const localItemId = parseItemId(row.localItemId)
    const remoteItemId = parseItemId(row.remoteItemId)
    if (localItemId === null || remoteItemId === null) {
      throw new SupabaseSyncError('unavailable', 'A prévia contém uma duplicata inválida')
    }
    return { localItemId, remoteItemId }
  })
}

function parsePushOutcomes(results: readonly unknown[]): PushOutcome[] {
  return results.map((entry): PushOutcome => {
    const row = asRecord(entry)
    const reason = typeof row.reason === 'string' ? row.reason : undefined
    const opId = typeof row.opId === 'string' ? row.opId : undefined
    const revision = typeof row.revision === 'number' && Number.isSafeInteger(row.revision) && row.revision >= 0
      ? row.revision
      : undefined
    const extra = {
      ...(reason === undefined ? {} : { reason }),
      ...(opId === undefined ? {} : { opId }),
      ...(revision === undefined ? {} : { sequence: revision }),
    }
    if (row.status === 'applied') return { kind: 'applied', ...extra }
    if (row.status === 'already-applied') return { kind: 'already-applied', ...extra }
    return { kind: 'conflict', ...extra }
  })
}

function parseSnapshotItem(value: unknown): SyncItem & { revision: number } {
  const row = asRecord(value)
  const id = parseItemId(row.id)
  const cardId = parseCardId(row.cardId)
  const quantity = Number(row.quantity)
  const addedAt = Number(row.addedAt)
  const revision = Number(row.revision)
  if (id === null || cardId === null || !isVariant(row.variant) || !isCondition(row.condition) ||
    !Number.isSafeInteger(quantity) || quantity < 1 || !Number.isSafeInteger(addedAt) ||
    !Number.isSafeInteger(revision) || revision < 1) {
    throw new SupabaseSyncError('unavailable', 'A coleção remota contém uma posse inválida')
  }
  return {
    id,
    cardId,
    quantity,
    variant: row.variant,
    condition: row.condition,
    pricePaid: parseRemotePrice(row.pricePaid),
    addedAt,
    revision,
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
  return { amount: amount / 100, currency: currency satisfies Currency }
}
