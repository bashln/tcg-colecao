import type { CardSummary, CollectionEntry, CollectionItem } from '../domain/model.ts'
import { syncItem } from '../sync/outbox.ts'
import type { CollectionOperation, SyncItem } from '../sync/policy.ts'
import {
  SupabaseSyncError,
  syncSnapshotDigest,
  type LinkCommitResult,
  type LinkPreview,
  type PushOutcome,
} from '../sync/supabase-transport.ts'

/**
 * First-link controller. The preview is read-only and always comes from the server;
 * nothing is uploaded or bound until `confirmLink` runs after the person pressed an
 * explicit confirmation, and the atomic local completion is the last step of the chain.
 * Every failure path before completion leaves the local collection untouched.
 */
export interface LinkTransport {
  prepareLink(items: readonly SyncItem[]): Promise<LinkPreview>
  commitLink(plan: LinkPreview, deviceId: string, operations: readonly CollectionOperation[]): Promise<LinkCommitResult>
}

export interface LinkSnapshot {
  expectedItems: CollectionItem[]
  syncItems: SyncItem[]
}

export type LinkBlocker =
  | { kind: 'conflicts'; count: number; itemIds: string[] }
  | { kind: 'duplicates'; count: number }

export type LinkConfirmation =
  | { kind: 'linked'; highWater: number }
  | { kind: 'blocked'; message: string }
  | { kind: 'local-changed'; message: string }
  | { kind: 'rejected'; message: string }
  | { kind: 'error'; message: string }

export interface CompleteLinkInput {
  identity: string
  deviceId: string
  expectedItems: readonly CollectionItem[]
  remoteItems: readonly (SyncItem & { revision: number })[]
  remoteCards: readonly CardSummary[]
  seedRevisions: ReadonlyMap<string, number>
  highWater: number
}

export function snapshotForLink(entries: readonly CollectionEntry[]): LinkSnapshot {
  const expectedItems = entries.map((entry) => entry.item)
  return { expectedItems, syncItems: expectedItems.map(syncItem) }
}

/** Preview rows that must stop confirmation until the person resolves them explicitly. */
export function linkBlockers(preview: LinkPreview): LinkBlocker | null {
  if (preview.conflicting.length > 0) {
    return { kind: 'conflicts', count: preview.conflicting.length, itemIds: preview.conflicting.map((row) => row.itemId) }
  }
  if (preview.duplicateConfigurations.length > 0) {
    return { kind: 'duplicates', count: preview.duplicateConfigurations.length }
  }
  return null
}

export async function prepareLinkPreview(
  transport: LinkTransport,
  entries: readonly CollectionEntry[],
): Promise<LinkPreview> {
  return transport.prepareLink(snapshotForLink(entries).syncItems)
}

export async function confirmLink(input: {
  transport: LinkTransport
  identity: string
  deviceId: string
  preview: LinkPreview
  readEntries: () => Promise<CollectionEntry[]>
  resolveCard: (cardId: string) => Promise<CardSummary>
  completeLink: (value: CompleteLinkInput) => Promise<boolean>
}): Promise<LinkConfirmation> {
  const blocker = linkBlockers(input.preview)
  if (blocker !== null) return { kind: 'blocked', message: linkBlockerMessage(blocker) }

  const entries = await input.readEntries()
  const snapshot = snapshotForLink(entries)
  const digest = await syncSnapshotDigest(snapshot.syncItems)
  if (digest !== input.preview.digest) return { kind: 'local-changed', message: LOCAL_CHANGED_MESSAGE }

  const itemsById = new Map<string, CollectionItem>(snapshot.expectedItems.map((item) => [item.id, item]))
  const operations: CollectionOperation[] = []
  for (const itemId of input.preview.localOnly) {
    const item = itemsById.get(itemId)
    if (item === undefined) return { kind: 'local-changed', message: LOCAL_CHANGED_MESSAGE }
    operations.push({ kind: 'create', opId: crypto.randomUUID(), deviceId: input.deviceId, item: syncItem(item) })
  }

  // Resolve every cloud-only card before the commit so a catalog failure can never
  // leave the server seeded with rows this browser refused to import.
  const localIds = new Set(itemsById.keys())
  const cardsById = new Map<string, CardSummary>()
  for (const remote of input.preview.remoteItems) {
    if (localIds.has(remote.id) || cardsById.has(remote.cardId)) continue
    try {
      const card = await input.resolveCard(remote.cardId)
      if (card.id !== remote.cardId) throw new Error('Carta inconsistente')
      cardsById.set(card.id, card)
    } catch {
      return { kind: 'error', message: `Não foi possível carregar os dados da carta ${remote.cardId} para importar da nuvem. Verifique a conexão e tente novamente.` }
    }
  }

  let commit: LinkCommitResult
  try {
    commit = await input.transport.commitLink(input.preview, input.deviceId, operations)
  } catch (error) {
    if (error instanceof SupabaseSyncError && error.kind === 'unauthorized') {
      return { kind: 'error', message: error.message }
    }
    return { kind: 'error', message: LINK_COMMIT_UNCERTAIN_MESSAGE }
  }
  if (commit.kind !== 'linked') return { kind: 'rejected', message: rejectionMessage(commit.kind) }

  const seeds = seedRevisions(operations, commit.outcomes)
  if (seeds === null) return { kind: 'error', message: LINK_COMMITTED_LOCAL_CHANGED_MESSAGE }

  const completed = await input.completeLink({
    identity: input.identity,
    deviceId: input.deviceId,
    expectedItems: snapshot.expectedItems,
    remoteItems: input.preview.remoteItems,
    remoteCards: [...cardsById.values()],
    seedRevisions: seeds,
    highWater: commit.highWater,
  })
  if (!completed) return { kind: 'local-changed', message: LINK_COMMITTED_LOCAL_CHANGED_MESSAGE }
  return { kind: 'linked', highWater: commit.highWater }
}

export const LOCAL_CHANGED_MESSAGE =
  'Sua coleção local mudou depois da prévia. Nada foi enviado. Gere uma nova prévia para confirmar o vínculo.'

export const LINK_COMMITTED_LOCAL_CHANGED_MESSAGE =
  'A nuvem pode já ter recebido itens da prévia, mas o vínculo local não foi concluído. Gere uma nova prévia e confira qualquer conflito antes de confirmar novamente.'

export const LINK_COMMIT_UNCERTAIN_MESSAGE =
  'A resposta da nuvem não chegou e ela pode já ter recebido itens da prévia. Gere uma nova prévia e confira qualquer conflito antes de confirmar novamente.'

export function linkBlockerMessage(blocker: LinkBlocker): string {
  if (blocker.kind === 'conflicts') {
    return blocker.count === 1
      ? '1 posse tem uma versão diferente na nuvem. Revise as duas versões antes de vincular; nada foi enviado.'
      : `${blocker.count} posses têm versões diferentes na nuvem. Revise as duas versões antes de vincular; nada foi enviado.`
  }
  return blocker.count === 1
    ? '1 posse local repetiria uma configuração que já existe na nuvem. Ajuste a coleção local antes de vincular; nada foi enviado.'
    : `${blocker.count} posses locais repetiriam configurações que já existem na nuvem. Ajuste a coleção local antes de vincular; nada foi enviado.`
}

function rejectionMessage(kind: Exclude<LinkCommitResult, { kind: 'linked' }>['kind']): string {
  switch (kind) {
    case 'stale-preview':
      return 'A coleção na nuvem mudou depois da prévia. Nada foi enviado. Gere uma nova prévia.'
    case 'digest-mismatch':
      return LOCAL_CHANGED_MESSAGE
    case 'expired':
      return 'A prévia expirou. Nada foi enviado. Gere uma nova prévia para confirmar o vínculo.'
    case 'consumed':
      return 'Esta prévia já foi usada. Gere uma nova prévia para confirmar o vínculo.'
    case 'not-found':
      return 'A prévia não foi encontrada no servidor. Gere uma nova prévia para confirmar o vínculo.'
  }
}

/** Maps push outcomes back to the operations by opId; any rejected operation aborts local completion. */
function seedRevisions(
  operations: readonly CollectionOperation[],
  outcomes: readonly PushOutcome[],
): Map<string, number> | null {
  const byOpId = new Map(outcomes.filter((outcome) => outcome.opId !== undefined)
    .map((outcome) => [outcome.opId as string, outcome]))
  const seeds = new Map<string, number>()
  for (let index = 0; index < operations.length; index += 1) {
    const operation = operations[index]
    const outcome = byOpId.get(operation?.opId ?? '') ?? outcomes[index]
    if (operation === undefined || outcome === undefined || outcome.kind === 'conflict') return null
    if (outcome.sequence === undefined || !Number.isSafeInteger(outcome.sequence) || outcome.sequence < 1) return null
    const itemId = operation.kind === 'delete' ? operation.itemId : operation.item.id
    seeds.set(itemId, outcome.sequence)
  }
  return seeds
}
