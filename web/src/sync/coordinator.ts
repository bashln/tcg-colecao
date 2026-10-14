import type { CollectionOperation, SyncItem } from './policy.ts'
import type { PushConflict, PushResolutionResponse } from './supabase-transport.ts'

export interface SyncTransport {
  push(identity: string, operations: readonly CollectionOperation[]): Promise<{
    kind: 'applied' | 'already-applied' | 'conflict' | 'rejected'
    reason?: string
    sequence?: number
    conflictId?: string
  }[]>
  listPushConflicts?(expectedIdentity: string): Promise<PushConflict[]>
  resolvePushConflict?(input: { conflictId: string; choice: 'keep-local' | 'keep-cloud'; deviceId: string; expectedIdentity: string }): Promise<PushResolutionResponse>
  acknowledgePushConflictResolution?(input: { conflictId: string; resolutionId: string; expectedIdentity: string }): Promise<PushResolutionResponse>
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
  savePushConflict?(identity: string, conflict: PushConflict): Promise<boolean>
  applyPushConflictResolution?(identity: string, conflictId: string, receipt: PushResolutionResponse): Promise<boolean>
  finishPushConflictResolution?(identity: string, conflictId: string, resolutionId: string): Promise<boolean>
  deviceId?(identity: string): Promise<string | null>
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
  source?: 'pull' | 'push'
  serverConflictId?: string
  pushConflict?: PushConflict
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
  resolvePushConflict?(identity: string, conflictId: string, resolution: 'keep-local' | 'keep-cloud'): Promise<SyncResult>
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
        if (input.transport.listPushConflicts !== undefined) {
          const remoteConflicts = await input.transport.listPushConflicts(boundIdentity)
          if (boundIdentity !== identity) return { kind: 'offline', pending: null }
          for (const conflict of remoteConflicts) {
            if (input.local.savePushConflict === undefined || !await input.local.savePushConflict(boundIdentity, conflict)) {
              return { kind: 'error', pending: null }
            }
          }
        }
        const allPending = await input.local.listPendingOperations(boundIdentity)
        if (boundIdentity !== identity) return { kind: 'offline', pending: allPending.length }
        const existingConflicts = await input.local.listConflicts(boundIdentity)
        const blockedOperationIds = new Set(existingConflicts
          .filter((conflict) => conflict.source === 'push')
          .map((conflict) => conflict.pushConflict?.opId ?? conflict.pushConflict?.rejectedOperation?.opId)
          .filter((opId): opId is string => opId !== null && opId !== undefined))
        const pending = allPending.filter((operation) => !blockedOperationIds.has(operation.opId))
        const outcomes = await input.transport.push(boundIdentity, pending)
        let applied = 0
        const conflictDetails: SyncConflictDetail[] = []
        for (let index = 0; index < outcomes.length; index += 1) {
          const outcome = outcomes[index]
          const operation = pending[index]
          if (outcome === undefined || operation === undefined) continue
          if (outcome.kind === 'conflict' || outcome.kind === 'rejected') {
            conflictDetails.push({
              itemId: operation.kind === 'delete' ? operation.itemId : operation.item.id,
              reason: outcome.reason ?? 'conflict',
              source: 'push',
              serverConflictId: outcome.conflictId,
            })
          } else if (identity === boundIdentity) {
            const acknowledged = await input.local.acknowledgeOperation(operation.opId, boundIdentity)
            if (identity !== boundIdentity || !acknowledged) return { kind: 'offline', pending: pending.length }
            if (outcome.kind === 'applied') applied += 1
          }
        }
        if (identity !== boundIdentity) return { kind: 'offline', pending: pending.length }

        if (input.transport.listPushConflicts !== undefined) {
          const remoteConflicts = await input.transport.listPushConflicts(boundIdentity)
          if (boundIdentity !== identity) return { kind: 'offline', pending: null }
          for (const conflict of remoteConflicts) {
            if (input.local.savePushConflict === undefined || !await input.local.savePushConflict(boundIdentity, conflict)) {
              return { kind: 'error', pending: null }
            }
          }
        }

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
        const allConflicts = [...storedConflicts]
        for (const detail of conflictDetails) {
          if (!allConflicts.some((stored) => stored.serverConflictId === detail.serverConflictId && detail.serverConflictId !== undefined)) allConflicts.push(detail)
        }
        return allConflicts.length > 0
          ? { kind: 'conflicts', conflicts: allConflicts.length, pending: remaining, details: allConflicts }
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
    async resolvePushConflict(requestedIdentity, conflictId, resolution) {
      if (identity !== requestedIdentity) return { kind: 'offline', pending: 0 }
      const resolveRemote = input.transport.resolvePushConflict
      const acknowledgeRemote = input.transport.acknowledgePushConflictResolution
      const applyLocal = input.local.applyPushConflictResolution
      const finishLocal = input.local.finishPushConflictResolution
      if (resolveRemote === undefined || acknowledgeRemote === undefined || applyLocal === undefined || finishLocal === undefined) {
        return { kind: 'error', pending: (await input.local.listPendingOperations(requestedIdentity)).length }
      }
      try {
        const conflicts = await input.local.listConflicts(requestedIdentity)
        const detail = conflicts.find((candidate) => candidate.serverConflictId === conflictId)
        const rejected = detail?.pushConflict?.rejectedOperation
        if (detail?.source !== 'push' || rejected === null || rejected === undefined || rejected.kind !== 'create' && rejected.kind !== 'replace' && rejected.kind !== 'delete') {
          return { kind: 'error', pending: (await input.local.listPendingOperations(requestedIdentity)).length }
        }
        const deviceId = await input.local.deviceId?.(requestedIdentity) ?? rejected.deviceId
        if (identity !== requestedIdentity) return { kind: 'offline', pending: null }
        const receipt = await resolveRemote({ conflictId, choice: resolution, deviceId, expectedIdentity: requestedIdentity })
        if (identity !== requestedIdentity) return { kind: 'offline', pending: null }
        if (receipt.status === 'stale-conflict') {
          if (input.transport.listPushConflicts !== undefined) {
            for (const conflict of await input.transport.listPushConflicts(requestedIdentity)) await input.local.savePushConflict?.(requestedIdentity, conflict)
          }
          return { kind: 'conflicts', conflicts: 1, pending: (await input.local.listPendingOperations(requestedIdentity)).length, details: await input.local.listConflicts(requestedIdentity) }
        }
        if (receipt.status !== 'resolved' && receipt.status !== 'already-resolved' || receipt.resolutionId === undefined ||
          receipt.result === undefined || !await applyLocal(requestedIdentity, conflictId, receipt)) {
          return { kind: 'error', pending: (await input.local.listPendingOperations(requestedIdentity)).length }
        }
        if (identity !== requestedIdentity) return { kind: 'offline', pending: null }
        const ack = await acknowledgeRemote({ conflictId, resolutionId: receipt.resolutionId, expectedIdentity: requestedIdentity })
        if (identity !== requestedIdentity) return { kind: 'offline', pending: null }
        if (ack.status !== 'resolved' || !await finishLocal(requestedIdentity, conflictId, receipt.resolutionId)) {
          return { kind: 'error', pending: (await input.local.listPendingOperations(requestedIdentity)).length }
        }
        return this.syncOnce()
      } catch {
        const pending = await input.local.listPendingOperations(requestedIdentity).then((operations) => operations.length).catch(() => null)
        return { kind: 'error', pending }
      }
    },
  }
}
