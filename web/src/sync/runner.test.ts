import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { SyncCoordinator, SyncResult } from './coordinator.ts'
import { createSyncRunner } from './runner.ts'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((settle) => { resolve = settle })
  return { promise, resolve }
}

function fakeCoordinator(results: SyncResult[]): SyncCoordinator & { runs: number } {
  const queue = [...results]
  const coordinator = {
    runs: 0,
    bindIdentity() {},
    async status() { return { identity: null, pending: 0, lastSequence: 0 } },
    async syncOnce(): Promise<SyncResult> {
      coordinator.runs += 1
      return queue.shift() ?? { kind: 'complete', applied: 0, pending: 0 }
    },
  }
  return coordinator
}

test('overlapping requests never run the coordinator concurrently and all resolve', async () => {
  const gates = [deferred<SyncResult>(), deferred<SyncResult>()]
  let active = 0
  let maxActive = 0
  let runs = 0
  const coordinator: SyncCoordinator = {
    bindIdentity() {},
    async status() { return { identity: null, pending: 0, lastSequence: 0 } },
    async syncOnce() {
      const gate = gates[runs]
      if (gate === undefined) throw new Error('unexpected extra run')
      runs += 1
      active += 1
      maxActive = Math.max(maxActive, active)
      const result = await gate.promise
      active -= 1
      return result
    },
  }
  const runner = createSyncRunner(coordinator)

  const first = runner.request()
  const second = runner.request()
  gates[0]?.resolve({ kind: 'complete', applied: 1, pending: 0 })
  gates[1]?.resolve({ kind: 'complete', applied: 2, pending: 0 })

  const results = await Promise.all([first, second])
  assert.equal(maxActive, 1)
  assert.deepEqual(results, [
    { kind: 'complete', applied: 1, pending: 0 },
    { kind: 'complete', applied: 2, pending: 0 },
  ])
})

test('requests arriving during a run coalesce into a single trailing run', async () => {
  const coordinator = fakeCoordinator([
    { kind: 'complete', applied: 1, pending: 0 },
    { kind: 'complete', applied: 2, pending: 0 },
  ])
  const runner = createSyncRunner(coordinator)

  const first = runner.request()
  const duringA = runner.request()
  const duringB = runner.request()
  const results = await Promise.all([first, duringA, duringB])

  assert.equal(coordinator.runs, 2)
  assert.deepEqual(results[0], { kind: 'complete', applied: 1, pending: 0 })
  assert.deepEqual(results[1], { kind: 'complete', applied: 2, pending: 0 })
  assert.deepEqual(results[2], { kind: 'complete', applied: 2, pending: 0 })
})

test('a thrown sync keeps the runner usable and reports an error result', async () => {
  let calls = 0
  const coordinator: SyncCoordinator = {
    bindIdentity() {},
    async status() { return { identity: null, pending: 0, lastSequence: 0 } },
    async syncOnce() {
      calls += 1
      if (calls === 1) throw new Error('boom')
      return { kind: 'complete', applied: 1, pending: 0 }
    },
  }
  const runner = createSyncRunner(coordinator)
  const failing = await runner.request()
  const recovering = await runner.request()
  assert.deepEqual(failing, { kind: 'error', pending: 0 })
  assert.deepEqual(recovering, { kind: 'complete', applied: 1, pending: 0 })
})

test('observers receive running and result states', async () => {
  const gate = deferred<SyncResult>()
  const coordinator: SyncCoordinator = {
    bindIdentity() {},
    async status() { return { identity: null, pending: 0, lastSequence: 0 } },
    async syncOnce() { return gate.promise },
  }
  const runner = createSyncRunner(coordinator)
  const seen: string[] = []
  const unsubscribe = runner.subscribe((state) => {
    seen.push(`${state.running}:${state.result === null ? 'none' : state.result.kind}`)
  })

  const pending = runner.request()
  gate.resolve({ kind: 'offline', pending: 3 })
  await pending
  unsubscribe()
  assert.deepEqual(seen, ['true:none', 'false:offline'])
})
