import { expect, test } from '@playwright/test'
import type { CollectionOperation } from '../src/sync/policy.ts'
import { linkThroughUi, mockSyncRpc, seedLocalCollection, seedSignedInSession } from './helpers.ts'

const card = {
  id: 'base1-4', name: 'Charizard', localId: '4', image: null,
  set: { id: 'base1', name: 'Base Set', cardCount: { official: 102, total: 102 } },
  variants_detailed: [{ type: 'holo', pricing: {} }],
}

test('linked local add, edit, and removal write identity-bound outbox operations', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.evaluate(async () => {
    const ids = [
      '00000000-0000-4000-8000-000000000001',
      'f0000000-0000-4000-8000-000000000001',
      'a0000000-0000-4000-8000-000000000001',
      '10000000-0000-4000-8000-000000000001',
    ]
    Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => ids.shift()! })
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
  })

  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await page.getByRole('button', { name: 'Remover da coleção' }).click()
  await page.getByRole('button', { name: 'Remover posse' }).click()

  const operations = await page.evaluate(async () => {
    const opened = indexedDB.open('tcg-colecao-web')
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      opened.onsuccess = () => resolve(opened.result)
      opened.onerror = () => reject(opened.error)
    })
    const transaction = database.transaction('outbox', 'readonly')
    const rows = await new Promise<unknown[]>((resolve, reject) => {
      const request = transaction.objectStore('outbox').getAll()
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    database.close()
    return rows
  })

  expect(operations).toHaveLength(3)
  expect(operations).toEqual(expect.arrayContaining([
    expect.objectContaining({ identity: 'account-a', operation: expect.objectContaining({ kind: 'create' }) }),
    expect.objectContaining({ identity: 'account-a', operation: expect.objectContaining({ kind: 'replace', baseRevision: 1 }) }),
    expect.objectContaining({ identity: 'account-a', operation: expect.objectContaining({ kind: 'delete', baseRevision: 2 }) }),
  ]))
  const syncState = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const pending = await store.listPendingOperations('account-a')
    const acknowledged = await store.acknowledgeOperation(pending[0]!.opId, 'account-a')
    const wrongIdentityAcknowledged = await store.acknowledgeOperation(pending[1]!.opId, 'account-b')
    await store.saveSyncSequence('account-a', 10)
    await store.saveSyncSequence('account-a', 5)
    return {
      pendingCount: (await store.listPendingOperations('account-a')).length,
      acknowledged,
      wrongIdentityAcknowledged,
      sequence: await store.lastSyncSequence('account-a'),
      orderedKinds: pending.map((operation: CollectionOperation) => operation.kind),
    }
  })
  expect(syncState).toEqual({
    pendingCount: 2,
    acknowledged: true,
    wrongIdentityAcknowledged: false,
    sequence: 10,
    orderedKinds: ['create', 'replace', 'delete'],
  })
  const switchedPending = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-b', deviceId: 'device-b' })
    const previous = await store.listPendingOperations('account-a')
    const current = await store.listPendingOperations('account-b')
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    return { previous: previous.length, current: current.length, restored: (await store.listPendingOperations('account-a')).length }
  })
  expect(switchedPending).toEqual({ previous: 0, current: 0, restored: 2 })
})

test('outbox failure rolls back the collection write', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const add = IDBObjectStore.prototype.add
    IDBObjectStore.prototype.add = function (value: unknown, key?: IDBValidKey) {
      const request = key === undefined ? add.call(this, value) : add.call(this, value, key)
      if (this.name === 'outbox') this.transaction.abort()
      return request
    }
  })

  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByText('Não foi possível acessar os dados salvos neste navegador. A coleção não foi alterada. Verifique o armazenamento e tente novamente.')).toBeVisible()

  const localItems = await page.evaluate(async () => {
    const opened = indexedDB.open('tcg-colecao-web')
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      opened.onsuccess = () => resolve(opened.result)
      opened.onerror = () => reject(opened.error)
    })
    const transaction = database.transaction('items', 'readonly')
    const items = await new Promise<unknown[]>((resolve, reject) => {
      const request = transaction.objectStore('items').getAll()
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    database.close()
    return items
  })
  expect(localItems).toHaveLength(0)
})

test('failed sync leaves the local collection and pending operation intact', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
  })
  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()

  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const { createSyncCoordinator } = await import(`${location.origin}/src/sync/coordinator.ts`)
    const { createMemoryTransport } = await import(`${location.origin}/src/sync/memory-transport.ts`)
    const sync = createSyncCoordinator({
      transport: createMemoryTransport({ failNextPush: true }),
      local: {
        listPendingOperations: store.listPendingOperations,
        acknowledgeOperation: async (opId: string, identity: string) => store.acknowledgeOperation(opId, identity),
        applyRemoteOperation: async () => ({ kind: 'applied' as const }),
        listConflicts: async () => [],
        lastSequence: store.lastSyncSequence,
        saveSequence: store.saveSyncSequence,
      },
    })
    sync.bindIdentity('account-a')
    const outcome = await sync.syncOnce()
    return { outcome, pending: await store.listPendingOperations('account-a'), collection: await store.listCollection() }
  })

  expect(result.outcome).toEqual({ kind: 'error', pending: 1 })
  expect(result.pending).toHaveLength(1)
  expect(result.pending[0]).toMatchObject({ kind: 'create', item: { cardId: 'base1-4' } })
  expect(result.collection).toHaveLength(1)
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
})

test('durable push conflict blocks retry and applies keep-cloud before acknowledgment without moving pull cursor', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
  })
  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()

  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const pending = await store.listPendingOperations('account-a')
    const rejected = pending[0]!
    const conflictId = '30000000-0000-4000-8000-000000000001'
    const conflict = {
      id: conflictId, opId: rejected.opId, itemId: rejected.kind === 'delete' ? rejected.itemId : rejected.item.id,
      reason: 'stale', deviceId: rejected.deviceId, rejectedOperation: rejected,
      cloudItem: null, conflictingItem: null, highWater: 77, resolution: null,
    }
    const saved = await store.savePushConflict('account-a', conflict)
    const blocked = await store.listPendingOperations('account-a')
    await store.saveSyncSequence('account-a', 3)
    const receipt = {
      status: 'resolved', conflictId, choice: 'keep-cloud', resolutionId: '40000000-0000-4000-8000-000000000001',
      result: { kind: 'keep-cloud', itemId: conflict.itemId, localItemId: conflict.itemId, cloudItem: null, conflictingItem: null },
    }
    const applied = await store.applyPushConflictResolution('account-a', conflictId, receipt)
    const replayed = await store.applyPushConflictResolution('account-a', conflictId, receipt)
    const acknowledged = await store.finishPushConflictResolution('account-a', conflictId, receipt.resolutionId)
    return {
      saved, blocked: blocked.length, applied, replayed, acknowledged,
      remaining: (await store.listPendingOperations('account-a')).length,
      cursor: await store.lastSyncSequence('account-a'),
      conflicts: await store.listSyncConflicts('account-a'),
      collection: await store.listCollection(),
    }
  })

  expect(result).toMatchObject({ saved: true, blocked: 0, applied: true, replayed: true, acknowledged: true, remaining: 0, cursor: 3, conflicts: [], collection: [] })
})

test('operation ID failure aborts the collection transaction', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    let calls = 0
    Object.defineProperty(crypto, 'randomUUID', {
      configurable: true,
      value: () => {
        calls += 1
        if (calls === 1) return '00000000-0000-4000-8000-000000000001'
        throw new Error('Operation ID unavailable')
      },
    })
  })

  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByText('Não foi possível acessar os dados salvos neste navegador. A coleção não foi alterada. Verifique o armazenamento e tente novamente.')).toBeVisible()

  const savedCount = await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('tcg-colecao-web')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const transaction = database.transaction('items', 'readonly')
    const request = transaction.objectStore('items').count()
    const count = await new Promise<number>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    database.close()
    return count
  })
  expect(savedCount).toBe(0)
})

test('collection stays available locally without a sync binding or outbox entries', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('Carta adicionada à coleção')).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible({ timeout: 15_000 })

  const pendingCount = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return (await store.listPendingOperations('account-a')).length
  })
  expect(pendingCount).toBe(0)
})

test('v3 outbox migration restores create, replace, delete order for the same item', async ({ page }) => {
  await page.route('**/*', async (route) => {
    if (route.request().resourceType() === 'document') {
      await route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><body>database setup</body></html>' })
    } else {
      await route.abort()
    }
  })
  await page.goto('/')
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('tcg-colecao-web', 3)
      request.onupgradeneeded = () => {
        const db = request.result
        db.createObjectStore('items', { keyPath: 'id' }).createIndex('configurationKey', 'configurationKey', { unique: true })
        db.createObjectStore('cards', { keyPath: 'id' })
        db.createObjectStore('imports', { keyPath: 'itemId' })
        db.createObjectStore('outbox', { keyPath: 'opId' }).createIndex('identity', 'identity')
        db.createObjectStore('sync', { keyPath: 'key' })
      }
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const transaction = database.transaction(['outbox', 'sync'], 'readwrite')
    transaction.objectStore('sync').put({ key: 'binding', binding: { identity: 'account-a', deviceId: 'device-a' } })
    const outbox = transaction.objectStore('outbox')
    const deviceId = '20000000-0000-4000-8000-000000000001'
    const item = {
      id: '00000000-0000-4000-8000-000000000001', cardId: 'base1-4', quantity: 1,
      variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1,
    }
    const operations = [
      { kind: 'create', opId: 'f0000000-0000-4000-8000-000000000001', deviceId, item },
      { kind: 'replace', opId: 'a0000000-0000-4000-8000-000000000001', deviceId, baseRevision: 1, item: { ...item, quantity: 2 } },
      { kind: 'delete', opId: '10000000-0000-4000-8000-000000000001', deviceId, baseRevision: 2, itemId: item.id },
    ]
    for (const operation of operations) outbox.add({ opId: operation.opId, identity: 'account-a', operation })
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve()
      transaction.onabort = () => reject(transaction.error)
    })
    database.close()
  })
  await page.unroute('**/*')
  await page.goto('/')

  const kinds = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return (await store.listPendingOperations('account-a')).map((operation: CollectionOperation) => operation.kind)
  })
  expect(kinds).toEqual(['create', 'replace', 'delete'])
})

test('linked backup import creates ordered sync operations for added and merged items', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
  })
  const backup = {
    format: 'tcg-colecao-web-1',
    exportedAt: '2026-10-10T21:00:00.000Z',
    entries: [{
      item: {
        id: '00000000-0000-4000-8000-000000000001', cardId: 'base1-4', quantity: 2,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1,
        revision: 1, configurationKey: 'base1-4\u001fNORMAL\u001fNEAR_MINT',
      },
      card: {
        id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set',
        collectorNumber: '4', imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
      },
    }],
  }
  await page.getByRole('button', { name: 'Perfil' }).click()
  const input = page.getByLabel('Importar cópia da coleção')
  await input.setInputFiles({ name: 'minha-colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
  await expect(page.getByText('Cópia aplicada: 1 adicionadas, 0 combinadas.')).toBeVisible()

  const additional = structuredClone(backup)
  additional.entries[0]!.item.id = '00000000-0000-4000-8000-000000000002'
  additional.entries[0]!.item.quantity = 3
  await input.setInputFiles({ name: 'outra-colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(additional)) })
  await expect(page.getByText('Cópia aplicada: 0 adicionadas, 1 combinadas.')).toBeVisible()

  const pending = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return store.listPendingOperations('account-a')
  })
  expect(pending.map((operation: CollectionOperation) => operation.kind)).toEqual(['create', 'replace'])
  expect(pending[1]).toMatchObject({ kind: 'replace', baseRevision: 1, item: { quantity: 5 } })
})

test('backup import rolls back when its outbox write fails', async ({ page }) => {
  const backup = {
    format: 'tcg-colecao-web-1',
    exportedAt: '2026-10-10T21:00:00.000Z',
    entries: [{
      item: {
        id: '00000000-0000-4000-8000-000000000001', cardId: 'base1-4', quantity: 2,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1,
        revision: 1, configurationKey: 'base1-4\u001fNORMAL\u001fNEAR_MINT',
      },
      card: {
        id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set',
        collectorNumber: '4', imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
      },
    }],
  }
  await page.goto('/')
  const result = await page.evaluate(async (payload) => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const add = IDBObjectStore.prototype.add
    IDBObjectStore.prototype.add = function (value: unknown, key?: IDBValidKey) {
      const request = key === undefined ? add.call(this, value) : add.call(this, value, key)
      if (this.name === 'outbox') this.transaction.abort()
      return request
    }
    return store.importCollectionBackup(payload)
  }, backup)
  expect(result).toMatchObject({ kind: 'storage-error' })
  await expect(page.getByText('Sua coleção está vazia')).toBeVisible()
  const pendingCount = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return (await store.listPendingOperations('account-a')).length
  })
  expect(pendingCount).toBe(0)
})

test('remote operations update local items and cursor atomically without creating outbox entries', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const created = await store.applyRemoteOperation('account-a', 1, {
      kind: 'create', opId: 'remote-create', deviceId: 'device-b',
      item: {
        id: '00000000-0000-4000-8000-000000000001', cardId: 'base1-4', quantity: 2,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1,
      },
    }, card)
    const replaced = await store.applyRemoteOperation('account-a', 2, {
      kind: 'replace', opId: 'remote-replace', deviceId: 'device-b', baseRevision: 1,
      item: {
        id: '00000000-0000-4000-8000-000000000001', cardId: 'base1-4', quantity: 3,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1,
      },
    }, card)
    const deleted = await store.applyRemoteOperation('account-a', 3, {
      kind: 'delete', opId: 'remote-delete', deviceId: 'device-b', baseRevision: 2,
      itemId: '00000000-0000-4000-8000-000000000001',
    })
    return {
      applied: [created.kind, replaced.kind, deleted.kind],
      entries: await store.listCollection(),
      pending: await store.listPendingOperations('account-a'),
      cursor: await store.lastSyncSequence('account-a'),
    }
  })

  expect(result).toEqual({ applied: ['applied', 'applied', 'applied'], entries: [], pending: [], cursor: 3 })
})

test('unapplied pull conflict persists both values and leaves cursor before conflicted sequence', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local item')
    await store.addToCollection(card, draft)
    const local = (await store.listCollection())[0]!.item
    const applied = await store.applyRemoteOperation('account-a', 4, {
      kind: 'create', opId: 'remote-conflict', deviceId: 'device-b',
      item: {
        id: '00000000-0000-4000-8000-000000000099', cardId: 'base1-4', quantity: 7,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 2,
      },
    }, card)
    return {
      applied,
      conflicts: await store.listSyncConflicts('account-a'),
      entries: await store.listCollection(),
      cursor: await store.lastSyncSequence('account-a'),
      localId: local.id,
    }
  })

  expect(result.applied).toMatchObject({ kind: 'conflict', detail: { sequence: 4, reason: 'duplicate-configuration' } })
  expect(result.conflicts).toHaveLength(1)
  expect(result.conflicts[0]).toMatchObject({ sequence: 4, local: { id: result.localId }, remote: { item: { quantity: 7 } } })
  expect(result.entries).toHaveLength(1)
  expect(result.cursor).toBe(0)
})

test('keep-cloud applies remote conflict and advances cursor only after local write', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local item')
    await store.addToCollection(card, draft)
    const remote = {
      kind: 'create' as const, opId: 'remote-conflict', deviceId: 'device-b',
      item: { id: '00000000-0000-4000-8000-000000000099', cardId: 'base1-4', quantity: 7,
        variant: 'NORMAL' as const, condition: 'NEAR_MINT' as const, pricePaid: null, addedAt: 2 },
    }
    await store.applyRemoteOperation('account-a', 4, remote, card)
    const conflict = (await store.listSyncConflicts('account-a'))[0]!
    await store.resolveSyncConflict('account-a', conflict.id, 'keep-cloud', async () => card)
    return {
      entries: await store.listCollection(),
      conflicts: await store.listSyncConflicts('account-a'),
      cursor: await store.lastSyncSequence('account-a'),
      pending: await store.listPendingOperations('account-a'),
    }
  })

  expect(result.entries).toHaveLength(1)
  expect(result.entries[0]?.item).toMatchObject({ id: '00000000-0000-4000-8000-000000000099', quantity: 7 })
  expect(result.conflicts).toHaveLength(0)
  expect(result.cursor).toBe(4)
  expect(result.pending).toHaveLength(0)
})

test('keep-local queues a replacement and waits for server acknowledgment before cursor advance', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local item')
    await store.addToCollection(card, draft)
    const remote = {
      kind: 'create' as const, opId: 'remote-conflict', deviceId: 'device-b',
      item: { id: '00000000-0000-4000-8000-000000000099', cardId: 'base1-4', quantity: 7,
        variant: 'NORMAL' as const, condition: 'NEAR_MINT' as const, pricePaid: null, addedAt: 2 },
    }
    await store.applyRemoteOperation('account-a', 4, remote, card)
    const conflict = (await store.listSyncConflicts('account-a'))[0]!
    await store.resolveSyncConflict('account-a', conflict.id, 'keep-local', async () => card)
    const pending = await store.listPendingOperations('account-a')
    const beforeAck = await store.lastSyncSequence('account-a')
    if (pending[0] !== undefined) {
      await store.acknowledgeOperation(pending[0].opId, 'account-a')
      await store.finishSyncConflict('account-a', conflict.id, pending[0].opId)
    }
    return { pending, beforeAck, cursor: await store.lastSyncSequence('account-a'), entries: await store.listCollection() }
  })

  expect(result.pending).toHaveLength(1)
  expect(result.pending[0]).toMatchObject({ kind: 'replace', baseRevision: 1, item: { id: '00000000-0000-4000-8000-000000000099', quantity: 1 } })
  expect(result.beforeAck).toBe(0)
  expect(result.cursor).toBe(4)
  expect(result.entries).toHaveLength(1)
  expect(result.entries[0]?.item).toMatchObject({ id: '00000000-0000-4000-8000-000000000099', quantity: 1 })
})

test('retrying keep-local returns the original operation instead of creating another', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local item')
    await store.addToCollection(card, draft)
    await store.applyRemoteOperation('account-a', 4, {
      kind: 'create', opId: 'remote-conflict', deviceId: 'device-b',
      item: { id: '00000000-0000-4000-8000-000000000099', cardId: 'base1-4', quantity: 7,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 2 },
    }, card)
    const conflict = (await store.listSyncConflicts('account-a'))[0]!
    const first = await store.resolveSyncConflict('account-a', conflict.id, 'keep-local', async () => card)
    const retry = await store.resolveSyncConflict('account-a', conflict.id, 'keep-local', async () => card)
    return { first, retry, pending: await store.listPendingOperations('account-a') }
  })

  expect(result.first?.opId).toBeDefined()
  expect(result.retry?.opId).toBe(result.first?.opId)
  expect(result.pending).toHaveLength(1)
})

test('conflict completion cannot advance an old account cursor after identity switch', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local item')
    await store.addToCollection(card, draft)
    await store.applyRemoteOperation('account-a', 4, {
      kind: 'create', opId: 'remote-conflict', deviceId: 'device-b',
      item: { id: '00000000-0000-4000-8000-000000000099', cardId: 'base1-4', quantity: 7,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 2 },
    }, card)
    const conflict = (await store.listSyncConflicts('account-a'))[0]!
    const operation = await store.resolveSyncConflict('account-a', conflict.id, 'keep-local', async () => card)
    if (operation === null) throw new Error('resolution did not queue operation')
    await store.setSyncBinding({ identity: 'account-b', deviceId: 'device-b' })
    const finished = await store.finishSyncConflict('account-a', conflict.id, operation.opId)
    return { finished, cursorA: await store.lastSyncSequence('account-a') }
  })

  expect(result.finished).toBe(false)
  expect(result.cursorA).toBe(0)
})

test('profile exposes conflict choices and keep-local resolves through acknowledged replacement', async ({ page }) => {
  const remoteId = '00000000-0000-4000-8000-000000000099'
  const harness = await mockSyncRpc(page, {
    pullOperation: {
      serverSequence: 9, kind: 'create', opId: '10000000-0000-4000-8000-000000000008', deviceId: 'device-cloud',
      item: { id: remoteId, cardId: 'base1-4', quantity: 3, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 2 },
    },
  })
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await page.getByRole('button', { name: 'Perfil' }).first().click().catch(() => {})
  await expect(page.getByText('1 conflito de sincronização precisa da sua atenção.', { exact: false })).toBeVisible()
  await expect(page.getByText(/Local: 1 · NORMAL · NEAR_MINT\. Nuvem: 3 · NORMAL · NEAR_MINT\./)).toBeVisible()

  await page.getByRole('button', { name: 'Manter minha coleção' }).click()

  await expect(page.getByText('Sincronizado. Nenhuma alteração pendente.')).toBeVisible()
  const resolutionPush = harness.calls.find((call) => call.fn === 'push_collection_operations' &&
    Array.isArray(call.body.p_operations) && (call.body.p_operations as { kind: string }[]).some((operation) => operation.kind === 'replace'))
  expect(resolutionPush).toBeDefined()
  expect(resolutionPush?.body.p_operations).toEqual(expect.arrayContaining([
    expect.objectContaining({ kind: 'replace', baseRevision: 1, item: expect.objectContaining({ id: remoteId, quantity: 1 }) }),
  ]))
  expect(await page.evaluate(async () => (await (await import(`${location.origin}/src/collection/store.ts`)).listCollection()).length)).toBe(1)
})

test('push-conflict IndexedDB keep-cloud removes affected queued operations and preserves unrelated work', async ({ page }) => {
  await mockSyncRpc(page, { pushConflictReason: 'stale' })
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const card = { id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4', imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102 }
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local draft')
    await store.addToCollection(card, draft)
  })

  await expect(page.getByText(/conflito de sincronização precisa da sua atenção/)).toBeVisible()
  await expect(page.getByText(/Local: 2 · NORMAL · NEAR_MINT\. Nuvem: ausente\./)).toBeVisible()
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('tcg-colecao-web')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const binding = await new Promise<{ identity: string; deviceId: string }>((resolve, reject) => {
      const request = database.transaction('sync').objectStore('sync').get('binding')
      request.onsuccess = () => resolve(request.result.binding)
      request.onerror = () => reject(request.error)
    })
    const currentItem = (await (await import(`${location.origin}/src/collection/store.ts`)).listCollection())[0]?.item
    if (!currentItem) throw new Error('missing affected local item')
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction(['outbox', 'outboxOrder'], 'readwrite')
      const order = transaction.objectStore('outboxOrder')
      const operations = [{
        kind: 'replace', opId: 'superseded-op', deviceId: binding.deviceId, baseRevision: 1,
        item: { id: currentItem.id, cardId: currentItem.cardId, quantity: 3, variant: currentItem.variant, condition: currentItem.condition, pricePaid: currentItem.pricePaid, addedAt: currentItem.addedAt },
      }, {
        kind: 'create', opId: 'unrelated-op', deviceId: binding.deviceId,
        item: { id: 'unrelated-item', cardId: 'base1-5', quantity: 1, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 1 },
      }]
      for (const operation of operations) {
        const sequenceRequest = order.add({ opId: operation.opId, identity: binding.identity })
        sequenceRequest.onsuccess = () => transaction.objectStore('outbox').put({ opId: operation.opId, identity: binding.identity, sequence: sequenceRequest.result, operation })
      }
      transaction.oncomplete = () => resolve()
      transaction.onabort = () => reject(transaction.error)
    })
    database.close()
  })
  const queuedBeforeResolution = await page.evaluate(async () => (await import(`${location.origin}/src/collection/store.ts`)).listPendingOperations('user-ana'))
  expect(queuedBeforeResolution.length).toBeGreaterThan(1)
  const cursorBefore = await page.evaluate(async () => (await import(`${location.origin}/src/collection/store.ts`)).lastSyncSequence('user-ana'))
  const applied = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const conflict = (await store.listSyncConflicts('user-ana')).find((row: { source: string; id: string; pushConflict?: { itemId: string } }) => row.source === 'push')
    if (!conflict?.pushConflict) throw new Error('push conflict was not stored')
    const itemId = conflict.pushConflict.itemId
    return store.applyPushConflictResolution('user-ana', conflict.id, {
      status: 'resolved', conflictId: conflict.id, choice: 'keep-cloud', resolutionId: 'test-resolution',
      result: { kind: 'keep-cloud', itemId, localItemId: itemId, cloudItem: null, conflictingItem: null },
    })
  })
  expect(applied).toBe(true)
  expect(await page.evaluate(async () => (await import(`${location.origin}/src/collection/store.ts`)).listCollection())).toHaveLength(0)

  const pendingIdsAfterResolution = await page.evaluate(async () => (await import(`${location.origin}/src/collection/store.ts`)).listPendingOperations('user-ana').then((operations: { opId: string }[]) => operations.map((operation) => operation.opId)))
  expect(pendingIdsAfterResolution).toEqual(['unrelated-op'])
  const cursorAfter = await page.evaluate(async () => (await import(`${location.origin}/src/collection/store.ts`)).lastSyncSequence('user-ana'))
  expect(cursorAfter).toBe(cursorBefore)
})

test('permanent invalid-payload conflict explains next step and offers no unsafe choice', async ({ page }) => {
  await mockSyncRpc(page, { pushConflictReason: 'invalid-payload' })
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const card = { id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4', imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102 }
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid local draft')
    await store.addToCollection(card, draft)
  })

  await expect(page.getByText(/operação.*dados inválidos/i)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Manter minha coleção' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Usar versão da nuvem' })).toHaveCount(0)
  expect(await page.evaluate(async () => (await import(`${location.origin}/src/collection/store.ts`)).listCollection())).toHaveLength(1)
})

test('production local adapter pulls authenticated transport operations into IndexedDB', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const { createIndexedDbSyncLocalStore } = await import(`${location.origin}/src/sync/indexeddb-local.ts`)
    const { createSyncCoordinator } = await import(`${location.origin}/src/sync/coordinator.ts`)
    const { createMemoryTransport } = await import(`${location.origin}/src/sync/memory-transport.ts`)
    await store.setSyncBinding({ identity: 'account-a', deviceId: 'device-a' })
    const transport = createMemoryTransport()
    await transport.push('account-a', [{
      kind: 'create', opId: 'from-device-b', deviceId: 'device-b',
      item: {
        id: '00000000-0000-4000-8000-000000000002', cardId: 'base1-4', quantity: 4,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 2,
      },
    }])
    const local = createIndexedDbSyncLocalStore({ resolveCard: async () => ({
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }) })
    const sync = createSyncCoordinator({ transport, local })
    sync.bindIdentity('account-a')
    const outcome = await sync.syncOnce()
    return {
      outcome,
      entries: await store.listCollection(),
      pending: await store.listPendingOperations('account-a'),
      cursor: await store.lastSyncSequence('account-a'),
    }
  })

  expect(result.outcome).toEqual({ kind: 'complete', applied: 0, pending: 0 })
  expect(result.entries).toHaveLength(1)
  expect(result.entries[0]?.item).toMatchObject({ quantity: 4, revision: 1 })
  expect(result.entries[0]?.card.name).toBe('Charizard')
  expect(result.pending).toEqual([])
  expect(result.cursor).toBe(1)
})

test('confirmed link atomically imports cloud rows, rebases revisions, and advances cursor without outbox writes', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const localCard = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'] as const, prices: [], setTotal: 102,
    }
    const remoteCard = {
      id: 'base2-3', name: 'Blastoise', setId: 'base2', setName: 'Jungle', collectorNumber: '3',
      imageUrl: null, variants: ['NORMAL'] as const, prices: [], setTotal: 64,
    }
    const parsed = domain.parseItemDraft(domain.emptyDraft(localCard))
    if (parsed.kind !== 'valid') throw new Error('Local test draft invalid')
    await store.addToCollection(localCard, parsed)
    const before = await store.listCollection()
    const local = before[0]
    if (local === undefined) throw new Error('Local item was not created')
    const linked = await store.completeSyncLink({
      identity: 'account-a', deviceId: 'device-a', expectedItems: [local.item],
      remoteItems: [{
        id: '00000000-0000-4000-8000-000000000002', cardId: 'base2-3', quantity: 2,
        variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: null, addedAt: 2, revision: 3,
      }],
      remoteCards: [remoteCard], seedRevisions: new Map([[local.item.id, 1]]), highWater: 4,
    })
    return {
      linked,
      binding: await store.currentSyncBinding(),
      entries: await store.listCollection(),
      pending: await store.listPendingOperations('account-a'),
      cursor: await store.lastSyncSequence('account-a'),
    }
  })

  expect(result.linked).toBe(true)
  expect(result.binding).toEqual({ identity: 'account-a', deviceId: 'device-a' })
  expect(result.entries).toHaveLength(2)
  expect(result.entries.map((entry: { item: { revision: number } }) => entry.item.revision).sort()).toEqual([1, 3])
  expect(result.entries.find((entry: { card: { id: string; name: string } }) => entry.card.id === 'base2-3')?.card.name).toBe('Blastoise')
  expect(result.pending).toEqual([])
  expect(result.cursor).toBe(4)
})

test('confirmed link refuses a changed local snapshot without binding or mutating items', async ({ page }) => {
  await page.goto('/')
  const result = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const localCard = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'] as const, prices: [], setTotal: 102,
    }
    const parsed = domain.parseItemDraft(domain.emptyDraft(localCard))
    if (parsed.kind !== 'valid') throw new Error('Local test draft invalid')
    await store.addToCollection(localCard, parsed)
    const before = await store.listCollection()
    const local = before[0]
    if (local === undefined) throw new Error('Local item was not created')
    const linked = await store.completeSyncLink({
      identity: 'account-a', deviceId: 'device-a', expectedItems: [{ ...local.item, quantity: 2 }],
      remoteItems: [], remoteCards: [], seedRevisions: new Map([[local.item.id, 1]]), highWater: 1,
    })
    return {
      linked,
      binding: await store.currentSyncBinding(),
      entries: await store.listCollection(),
      pending: await store.listPendingOperations('account-a'),
    }
  })

  expect(result.linked).toBe(false)
  expect(result.binding).toBeNull()
  expect(result.entries).toHaveLength(1)
  expect(result.entries[0]?.item.quantity).toBe(1)
  expect(result.pending).toEqual([])
})
