import type { CardSummary } from '../domain/model.ts'
import {
  acknowledgeOperation,
  applyRemoteOperation,
  getCachedCard,
  lastSyncSequence,
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
    lastSequence: lastSyncSequence,
    saveSequence: saveSyncSequence,
  }
}
