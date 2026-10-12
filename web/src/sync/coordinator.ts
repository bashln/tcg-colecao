import type { CollectionOperation } from './policy.ts'

export interface SyncTransport {
  push(identity: string, operations: readonly CollectionOperation[]): Promise<{
    kind: 'applied' | 'already-applied' | 'conflict'
    reason?: string
    sequence?: number
  }[]>
  pull(identity: string, afterSequence: number): Promise<{
    operations: { sequence: number; operation: CollectionOperation }[]
    highWaterMark: number
  }>
}

export interface SyncLocalStore {
  listPendingOperations(identity: string): Promise<CollectionOperation[]>
  acknowledgeOperation(opId: string, identity: string): Promise<boolean>
  applyRemoteOperation(operation: CollectionOperation, identity: string, sequence: number): Promise<boolean>
  lastSequence(identity: string): Promise<number>
  saveSequence(identity: string, sequence: number): Promise<boolean>
}

export interface SyncConflictDetail {
  itemId: string
  reason: string
}

export type SyncResult =
  | { kind: 'complete'; applied: number; pending: number }
  | { kind: 'conflicts'; conflicts: number; pending: number; details: SyncConflictDetail[] }
  | { kind: 'offline'; pending: number }
  | { kind: 'error'; pending: number }

export interface SyncCoordinator {
  bindIdentity(identity: string | null): void
  syncOnce(): Promise<SyncResult>
  status(): Promise<{ identity: string | null; pending: number; lastSequence: number }>
}

export function createSyncCoordinator(input: {
  transport: SyncTransport
  local: SyncLocalStore
}): SyncCoordinator {
  let identity: string | null = null

  return {
    bindIdentity(nextIdentity) {
      identity = nextIdentity
    },
    async syncOnce() {
      if (identity === null) return { kind: 'offline', pending: 0 }
      const boundIdentity = identity
      try {
        const pending = await input.local.listPendingOperations(boundIdentity)
        if (boundIdentity !== identity) return { kind: 'offline', pending: pending.length }
        const outcomes = await input.transport.push(boundIdentity, pending)
        let applied = 0
        const conflictDetails: SyncConflictDetail[] = []
        for (let index = 0; index < outcomes.length; index += 1) {
          const outcome = outcomes[index]
          const operation = pending[index]
          if (outcome === undefined || operation === undefined) continue
          if (outcome.kind === 'conflict') {
            conflictDetails.push({
              itemId: operation.kind === 'delete' ? operation.itemId : operation.item.id,
              reason: outcome.reason ?? 'conflict',
            })
          } else if (identity === boundIdentity) {
            const acknowledged = await input.local.acknowledgeOperation(operation.opId, boundIdentity)
            if (identity !== boundIdentity || !acknowledged) return { kind: 'offline', pending: pending.length }
            if (outcome.kind === 'applied') applied += 1
          }
        }
        if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }

        const cursor = await input.local.lastSequence(boundIdentity)
        const page = await input.transport.pull(boundIdentity, cursor)
        if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }
        for (const entry of page.operations) {
          if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }
          const applied = await input.local.applyRemoteOperation(entry.operation, boundIdentity, entry.sequence)
          if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }
          if (!applied) return { kind: 'error', pending: (await input.local.listPendingOperations(boundIdentity)).length }
        }
        if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }
        const cursorSaved = await input.local.saveSequence(boundIdentity, page.highWaterMark)
        if (identity !== boundIdentity || !cursorSaved) return { kind: 'offline', pending: pending.length }

        const remaining = (await input.local.listPendingOperations(boundIdentity)).length
        return conflictDetails.length > 0
          ? { kind: 'conflicts', conflicts: conflictDetails.length, pending: remaining, details: conflictDetails }
          : { kind: 'complete', applied, pending: remaining }
      } catch {
        if (identity !== boundIdentity) return { kind: 'offline', pending: 0 }
        const pending = identity === boundIdentity
          ? (await input.local.listPendingOperations(boundIdentity).catch(() => [])).length
          : 0
        return { kind: 'error', pending }
      }
    },
    async status() {
      if (identity === null) return { identity: null, pending: 0, lastSequence: 0 }
      const currentIdentity = identity
      const [pending, lastSequence] = await Promise.all([
        input.local.listPendingOperations(currentIdentity),
        input.local.lastSequence(currentIdentity),
      ])
      return identity === currentIdentity
        ? { identity: currentIdentity, pending: pending.length, lastSequence }
        : { identity, pending: 0, lastSequence: 0 }
    },
  }
}
