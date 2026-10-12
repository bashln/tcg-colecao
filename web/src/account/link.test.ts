import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseCardId, parseItemId } from '../domain/model.ts'
import type { CardSummary, CollectionEntry, CollectionItem } from '../domain/model.ts'
import type { CollectionOperation, SyncItem } from '../sync/policy.ts'
import { SupabaseSyncError, syncSnapshotDigest, type LinkCommitResult, type LinkPreview, type PushOutcome } from '../sync/supabase-transport.ts'
import {
  confirmLink, linkBlockers, prepareLinkPreview, snapshotForLink,
  type LinkTransport,
} from './link.ts'

const card: CardSummary = {
  id: cardId('base1-4'), name: 'Charizard', setId: 'base1', setName: 'Base Set',
  collectorNumber: '4', imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
}

const cloudCard: CardSummary = {
  id: cardId('base2-3'), name: 'Blastoise', setId: 'base2', setName: 'Jungle',
  collectorNumber: '3', imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 64,
}

/** Widens a literal to the branded CardId the domain requires. */
function cardId(value: string): CardSummary['id'] {
  const parsed = parseCardId(value)
  if (parsed === null) throw new Error(`invalid test card id: ${value}`)
  return parsed
}

/** Widens a literal to the branded ItemId the domain requires. */
function itemId(value: string): CollectionItem['id'] {
  const parsed = parseItemId(value)
  if (parsed === null) throw new Error(`invalid test item id: ${value}`)
  return parsed
}

function localItem(id: string, quantity = 1): CollectionItem {
  return {
    id: itemId(id), cardId: card.id, quantity, variant: 'NORMAL', condition: 'NEAR_MINT',
    pricePaid: null, addedAt: 1_000, revision: 1,
    configurationKey: 'base1-4\u001fNORMAL\u001fNEAR_MINT',
  }
}

function entry(item: CollectionItem): CollectionEntry {
  return { item, card }
}

function preview(overrides: Partial<LinkPreview> = {}): LinkPreview {
  return {
    planId: 'plan-12345678', digest: 'digest-value', expiresAt: new Date(Date.now() + 60_000).toISOString(),
    remoteHighWater: 7, localOnly: [], cloudOnly: [], identical: [],
    conflicting: [], duplicateConfigurations: [], remoteItems: [],
    ...overrides,
  }
}

/** A preview whose digest really matches the entries, so stale-detection tests target their subject. */
async function previewFor(entries: CollectionEntry[], overrides: Partial<LinkPreview> = {}): Promise<LinkPreview> {
  const { syncItems } = snapshotForLink(entries)
  return preview({ digest: await syncSnapshotDigest(syncItems), ...overrides })
}

function transport(input: {
  prepare?: LinkPreview
  commit?: LinkCommitResult
  commitError?: Error
  onCommit?: (plan: LinkPreview, deviceId: string, operations: readonly CollectionOperation[]) => void
  onPrepare?: (items: readonly SyncItem[]) => void
}): LinkTransport {
  return {
    async prepareLink(items) {
      input.onPrepare?.(items)
      return input.prepare ?? preview()
    },
    async commitLink(plan, deviceId, operations) {
      input.onCommit?.(plan, deviceId, operations)
      if (input.commitError !== undefined) throw input.commitError
      if (input.commit === undefined) throw new Error('commit não deveria ser chamado')
      return input.commit
    },
  }
}

function completeSpy(result: boolean) {
  const calls: unknown[] = []
  return {
    calls,
    complete: async (value: unknown) => { calls.push(value); return result },
  }
}

test('snapshotForLink converts collection entries into ordered sync items', () => {
  const snapshot = snapshotForLink([entry(localItem('00000000-0000-4000-8000-000000000001', 3))])
  assert.equal(snapshot.expectedItems.length, 1)
  assert.deepEqual(snapshot.syncItems, [{
    id: '00000000-0000-4000-8000-000000000001',
    cardId: 'base1-4',
    quantity: 3,
    variant: 'NORMAL',
    condition: 'NEAR_MINT',
    pricePaid: null,
    addedAt: 1_000,
  }])
})

test('prepareLinkPreview sends local sync items and never commits', async () => {
  let committed = false
  const transportImpl = transport({
    prepare: preview({ localOnly: ['00000000-0000-4000-8000-000000000001'] }),
    onCommit: () => { committed = true },
    onPrepare: (items) => assert.equal(items.length, 1),
  })
  const result = await prepareLinkPreview(transportImpl, [entry(localItem('00000000-0000-4000-8000-000000000001'))])
  assert.equal(result.planId, 'plan-12345678')
  assert.equal(committed, false)
})

test('linkBlockers reports conflicts before duplicates and is null on a clean preview', () => {
  assert.equal(linkBlockers(preview()), null)
  assert.deepEqual(linkBlockers(preview({
    conflicting: [{ itemId: '00000000-0000-4000-8000-000000000001', reason: 'different-content' }],
  })), { kind: 'conflicts', count: 1, itemIds: ['00000000-0000-4000-8000-000000000001'] })
  assert.deepEqual(linkBlockers(preview({
    duplicateConfigurations: [{ localItemId: 'a', remoteItemId: 'b' }],
  })), { kind: 'duplicates', count: 1 })
})

test('confirmLink pushes only local-only items and completes the link with seed revisions', async () => {
  const localId = '00000000-0000-4000-8000-000000000001'
  const cloudId = '00000000-0000-4000-8000-000000000002'
  const entries = [entry(localItem(localId))]
  const plan = await previewFor(entries, {
    localOnly: [localId], cloudOnly: [cloudId], remoteHighWater: 9,
    remoteItems: [{
      id: itemId(cloudId), cardId: cloudCard.id, quantity: 2, variant: 'NORMAL',
      condition: 'NEAR_MINT', pricePaid: null, addedAt: 2, revision: 3,
    }],
  })
  const outcomes: PushOutcome[] = [{ kind: 'applied', sequence: 1, opId: 'op-1' }]
  const spy = completeSpy(true)
  let sentDeviceId = ''
  let sentOperations: readonly CollectionOperation[] = []
  const transportImpl = transport({
    prepare: plan,
    commit: { kind: 'linked', highWater: 12, outcomes },
    onCommit: (_plan, deviceId, operations) => {
      sentDeviceId = deviceId
      sentOperations = operations
    },
  })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => entries,
    resolveCard: async (cardId) => {
      assert.equal(cardId, cloudCard.id)
      return cloudCard
    },
    completeLink: spy.complete as never,
  })

  assert.deepEqual(result, { kind: 'linked', highWater: 12 })
  assert.equal(sentDeviceId, 'device-1')
  assert.equal(sentOperations.length, 1)
  const operation = sentOperations[0]
  assert.ok(operation)
  assert.equal(operation.kind, 'create')
  assert.equal(operation.deviceId, 'device-1')
  assert.equal(operation.kind === 'create' ? operation.item.id : '', localId)

  assert.equal(spy.calls.length, 1)
  const completion = spy.calls[0] as {
    identity: string
    deviceId: string
    expectedItems: CollectionItem[]
    remoteItems: unknown[]
    remoteCards: CardSummary[]
    seedRevisions: Map<string, number>
    highWater: number
  }
  assert.equal(completion.identity, 'user-1')
  assert.equal(completion.deviceId, 'device-1')
  assert.equal(completion.expectedItems.length, 1)
  assert.equal(completion.remoteItems.length, 1)
  assert.deepEqual(completion.remoteCards, [cloudCard])
  assert.deepEqual([...completion.seedRevisions], [[localId, 1]])
  assert.equal(completion.highWater, 12)
})

test('confirmLink refuses a local collection that changed after the preview without calling commit', async () => {
  let committed = false
  const plan = preview({ localOnly: ['00000000-0000-4000-8000-000000000001'], digest: 'old-digest' })
  const spy = completeSpy(true)
  const transportImpl = transport({
    prepare: plan,
    commit: { kind: 'linked', highWater: 1, outcomes: [] },
    onCommit: () => { committed = true },
  })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [entry(localItem('00000000-0000-4000-8000-000000000001', 9))],
    resolveCard: async () => cloudCard,
    completeLink: spy.complete as never,
  })

  assert.equal(result.kind, 'local-changed')
  assert.equal(committed, false)
  assert.equal(spy.calls.length, 0)
})

test('confirmLink reports preview blockers and never calls commit or local completion', async () => {
  let committed = false
  const plan = preview({ conflicting: [{ itemId: '00000000-0000-4000-8000-000000000001', reason: 'different-content' }] })
  const spy = completeSpy(true)
  const transportImpl = transport({
    prepare: plan,
    commit: { kind: 'linked', highWater: 1, outcomes: [] },
    onCommit: () => { committed = true },
  })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [entry(localItem('00000000-0000-4000-8000-000000000001'))],
    resolveCard: async () => cloudCard,
    completeLink: spy.complete as never,
  })

  assert.equal(result.kind, 'blocked')
  assert.equal(committed, false)
  assert.equal(spy.calls.length, 0)
})

test('confirmLink does not complete locally when the server rejects an operation', async () => {
  const localId = '00000000-0000-4000-8000-000000000001'
  const plan = await previewFor([entry(localItem(localId))], { localOnly: [localId] })
  const spy = completeSpy(true)
  const transportImpl = transport({
    prepare: plan,
    commit: { kind: 'linked', highWater: 5, outcomes: [{ kind: 'conflict', reason: 'id-collision', opId: 'op-1' }] },
  })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [entry(localItem(localId))],
    resolveCard: async () => cloudCard,
    completeLink: spy.complete as never,
  })

  assert.equal(result.kind, 'error')
  assert.match((result as { message: string }).message, /pode já ter recebido/i)
  assert.equal(spy.calls.length, 0)
})

test('confirmLink explains that a committed cloud seed may exist when local completion is stale', async () => {
  const localId = '00000000-0000-4000-8000-000000000001'
  const plan = await previewFor([], { cloudOnly: [localId] })
  const spy = completeSpy(false)
  const result = await confirmLink({
    transport: transport({ prepare: plan, commit: { kind: 'linked', highWater: 5, outcomes: [] } }),
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [],
    resolveCard: async () => cloudCard,
    completeLink: spy.complete as never,
  })

  assert.equal(result.kind, 'local-changed')
  assert.match((result as { message: string }).message, /nuvem pode já ter recebido/i)
  assert.doesNotMatch((result as { message: string }).message, /nada foi enviado/i)
})

test('confirmLink treats a lost commit response as an uncertain cloud write', async () => {
  const plan = await previewFor([])
  const result = await confirmLink({
    transport: transport({ prepare: plan, commitError: new Error('connection lost') }),
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [],
    resolveCard: async () => cloudCard,
    completeLink: completeSpy(false).complete as never,
  })

  assert.equal(result.kind, 'error')
  assert.match((result as { message: string }).message, /pode já ter recebido/i)
})

test('confirmLink reports structured partial commit and never binds locally', async () => {
  const localId = '00000000-0000-4000-8000-000000000001'
  const plan = await previewFor([entry(localItem(localId))], { localOnly: [localId] })
  const spy = completeSpy(true)
  const result = await confirmLink({
    transport: transport({
      prepare: plan,
      commit: { kind: 'partial', highWater: 4, outcomes: [{ kind: 'conflict', reason: 'id-collision', opId: 'op-1' }] },
    }),
    identity: 'user-1', deviceId: 'device-1', preview: plan,
    readEntries: async () => [entry(localItem(localId))], resolveCard: async () => cloudCard,
    completeLink: spy.complete as never,
  })

  assert.equal(result.kind, 'partial')
  assert.equal(spy.calls.length, 0)
})

test('confirmLink distinguishes malformed server response from a lost response', async () => {
  const plan = await previewFor([])
  const result = await confirmLink({
    transport: transport({ prepare: plan, commitError: new SupabaseSyncError('invalid-response', 'Resposta inválida do servidor') }),
    identity: 'user-1', deviceId: 'device-1', preview: plan,
    readEntries: async () => [], resolveCard: async () => cloudCard,
    completeLink: completeSpy(false).complete as never,
  })

  assert.equal(result.kind, 'error')
  assert.match((result as { message: string }).message, /resposta.*inválida/i)
  assert.doesNotMatch((result as { message: string }).message, /resposta.*não chegou/i)
})

test('confirmLink maps a stale server preview to an explicit rejection', async () => {
  const plan = await previewFor([])
  const spy = completeSpy(true)
  const transportImpl = transport({ prepare: plan, commit: { kind: 'stale-preview' } })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [],
    resolveCard: async () => cloudCard,
    completeLink: spy.complete as never,
  })

  assert.equal(result.kind, 'rejected')
  assert.equal(spy.calls.length, 0)
})

test('confirmLink resolves cloud-only cards before any server commit', async () => {
  const cloudId = '00000000-0000-4000-8000-000000000002'
  const plan = await previewFor([], {
    cloudOnly: [cloudId],
    remoteItems: [{
      id: itemId(cloudId), cardId: cloudCard.id, quantity: 2, variant: 'NORMAL',
      condition: 'NEAR_MINT', pricePaid: null, addedAt: 2, revision: 3,
    }],
  })
  let committed = false
  const transportImpl = transport({
    prepare: plan,
    commit: { kind: 'linked', highWater: 3, outcomes: [] },
    onCommit: () => { committed = true },
  })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [],
    resolveCard: async () => { throw new Error('catálogo offline') },
    completeLink: async () => true,
  })

  assert.equal(result.kind, 'error')
  assert.equal(committed, false)
})

test('confirmLink surfaces a failed atomic local completion as local-changed', async () => {
  const plan = await previewFor([])
  const transportImpl = transport({ prepare: plan, commit: { kind: 'linked', highWater: 4, outcomes: [] } })

  const result = await confirmLink({
    transport: transportImpl,
    identity: 'user-1',
    deviceId: 'device-1',
    preview: plan,
    readEntries: async () => [],
    resolveCard: async () => cloudCard,
    completeLink: async () => false,
  })

  assert.equal(result.kind, 'local-changed')
})
