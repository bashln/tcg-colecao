import type { CardSummary } from '../domain/model.ts'
import {
  acknowledgeOperation,
  applyRemoteOperation,
  getCachedCard,
  lastSyncSequence,
  listSyncConflicts,
  resolveSyncConflict,
  finishSyncConflict,
  savePushConflict,
  applyPushConflictResolution,
  finishPushConflictResolution,
  syncDeviceId,
  listPendingOperations,
  saveSyncSequence,
} from '../collection/store.ts'
import type { SyncLocalStore } from './coordinator.ts'

export function createIndexedDbSyncLocalStore(input: {
  resolveCard: (cardId: string) => Promise<CardSummary>
}): SyncLocalStore {
  return {
    listPendingOperations,
    acknowledgeOperation,
    async applyRemoteOperation(operation, identity, sequence) {
      let card: CardSummary | undefined
      if (operation.kind !== 'delete') {
        card = await getCachedCard(operation.item.cardId) ?? await input.resolveCard(operation.item.cardId)
      }
      return applyRemoteOperation(identity, sequence, operation, card)
    },
    listConflicts: listSyncConflicts,
    resolveConflict(identity, conflictId, resolution) {
      return resolveSyncConflict(identity, conflictId, resolution, input.resolveCard)
    },
    finishConflict: finishSyncConflict,
    savePushConflict,
    applyPushConflictResolution,
    finishPushConflictResolution,
    deviceId: syncDeviceId,
    lastSequence: lastSyncSequence,
    saveSequence: saveSyncSequence,
  }
}
