import { expect, test } from '@playwright/test'
import type { CollectionOperation } from '../src/sync/policy.ts'

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
        acknowledgeOperation: async (opId: string, identity: string) => { await store.acknowledgeOperation(opId, identity) },
        applyRemoteOperation: async () => true,
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
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()

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
