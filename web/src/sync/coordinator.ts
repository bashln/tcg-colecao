import type { CollectionOperation, SyncItem } from './policy.ts'

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
  applyRemoteOperation(operation: CollectionOperation, identity: string, sequence: number): Promise<RemoteApplyResult>
  listConflicts(identity: string): Promise<SyncConflictDetail[]>
  resolveConflict?(identity: string, conflictId: string, resolution: 'keep-local' | 'keep-cloud'): Promise<CollectionOperation | null>
  finishConflict?(identity: string, conflictId: string, operationId: string): Promise<boolean>
  lastSequence(identity: string): Promise<number>
  saveSequence(identity: string, sequence: number): Promise<boolean>
}

export interface SyncConflictDetail {
  identity?: string
  itemId: string
  reason: string
  id?: string
  sequence?: number
  local?: SyncItem | null
  remote?: CollectionOperation
  resolutionOperation?: CollectionOperation
}

export type RemoteApplyResult = { kind: 'applied' } | { kind: 'conflict'; detail: SyncConflictDetail }

export type SyncResult =
  | { kind: 'complete'; applied: number; pending: number }
  | { kind: 'conflicts'; conflicts: number; pending: number | null; details: SyncConflictDetail[] }
  | { kind: 'offline'; pending: number | null }
  | { kind: 'error'; pending: number | null }

export interface SyncCoordinator {
  bindIdentity(identity: string | null): void
  syncOnce(): Promise<SyncResult>
  status(): Promise<{ identity: string | null; pending: number | null; lastSequence: number }>
  resolveConflict?(identity: string, conflictId: string, resolution: 'keep-local' | 'keep-cloud'): Promise<SyncResult>
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
          if (applied.kind === 'conflict') {
            const remaining = (await input.local.listPendingOperations(boundIdentity)).length
            return { kind: 'conflicts', conflicts: 1, pending: remaining, details: [applied.detail] }
          }
        }
        if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }
        const cursorSaved = await input.local.saveSequence(boundIdentity, page.highWaterMark)
        if (identity !== boundIdentity || !cursorSaved) return { kind: 'offline', pending: pending.length }

        const remaining = (await input.local.listPendingOperations(boundIdentity)).length
        const storedConflicts = await input.local.listConflicts(boundIdentity)
        return conflictDetails.length > 0 || storedConflicts.length > 0
          ? { kind: 'conflicts', conflicts: conflictDetails.length + storedConflicts.length, pending: remaining, details: [...conflictDetails, ...storedConflicts] }
          : { kind: 'complete', applied, pending: remaining }
      } catch {
        if (identity !== boundIdentity) return { kind: 'offline', pending: null }
        const pending = await input.local.listPendingOperations(boundIdentity).then((operations) => operations.length).catch(() => null)
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
        : { identity, pending: null, lastSequence: 0 }
    },
    async resolveConflict(requestedIdentity, conflictId, resolution) {
      if (identity !== requestedIdentity) return { kind: 'offline', pending: 0 }
      try {
        if (input.local.resolveConflict === undefined || input.local.finishConflict === undefined) {
          return { kind: 'error', pending: (await input.local.listPendingOperations(requestedIdentity)).length }
        }
        const operation = await input.local.resolveConflict(requestedIdentity, conflictId, resolution)
        if (operation !== null) {
          const [outcome] = await input.transport.push(requestedIdentity, [operation])
          if (identity !== requestedIdentity) return { kind: 'offline', pending: 0 }
          if (outcome === undefined || outcome.kind === 'conflict') {
            return { kind: 'conflicts', conflicts: 1, pending: (await input.local.listPendingOperations(requestedIdentity)).length,
              details: await input.local.listConflicts(requestedIdentity) }
          }
          if (!await input.local.acknowledgeOperation(operation.opId, requestedIdentity) ||
            !await input.local.finishConflict(requestedIdentity, conflictId, operation.opId)) {
            return { kind: 'error', pending: (await input.local.listPendingOperations(requestedIdentity)).length }
          }
        }
        return this.syncOnce()
      } catch {
        const pending = await input.local.listPendingOperations(requestedIdentity).then((operations) => operations.length).catch(() => null)
        return { kind: 'error', pending }
      }
    },
  }
}
