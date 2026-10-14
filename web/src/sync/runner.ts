import type { SyncCoordinator, SyncResult } from './coordinator.ts'

export interface SyncRunnerState {
  running: boolean
  result: SyncResult | null
}

/**
 * Single-flight wrapper around the coordinator. Triggers fired while a sync is
 * running are coalesced into exactly one trailing run, so edits, foreground, and
 * network recovery never produce overlapping pushes.
 */
export interface SyncRunner {
  /** Requests a sync; resolves with the result of the run that covered this request. */
  request(): Promise<SyncResult>
  /** Observes running/result transitions. Nothing is emitted on subscribe. */
  subscribe(observer: (state: SyncRunnerState) => void): () => void
}

export function createSyncRunner(coordinator: SyncCoordinator): SyncRunner {
  const observers = new Set<(state: SyncRunnerState) => void>()
  let state: SyncRunnerState = { running: false, result: null }
  let draining = false
  const waiting: ((result: SyncResult) => void)[] = []

  const publish = (next: SyncRunnerState) => {
    state = next
    for (const observer of [...observers]) observer(state)
  }

  const drain = async () => {
    if (draining) return
    draining = true
    try {
      while (waiting.length > 0) {
        const covered = waiting.splice(0)
        publish({ running: true, result: state.result })
        let result: SyncResult
        try {
          result = await coordinator.syncOnce()
        } catch {
          const status = await coordinator.status().catch(() => null)
          result = { kind: 'error', pending: status?.pending ?? null }
        }
        publish({ running: false, result })
        for (const resolve of covered) resolve(result)
      }
    } finally {
      draining = false
    }
  }

  return {
    request() {
      return new Promise<SyncResult>((resolve) => {
        waiting.push(resolve)
        void drain()
      })
    },
    subscribe(observer) {
      observers.add(observer)
      return () => { observers.delete(observer) }
    },
  }
}
