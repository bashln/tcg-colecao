import { expect, test } from '@playwright/test'

const backup = {
  format: 'tcg-colecao-web-1',
  exportedAt: '2026-10-10T21:00:00.000Z',
  entries: [{
    item: {
      id: '00000000-0000-4000-8000-000000000001',
      cardId: 'base1-4',
      quantity: 2,
      variant: 'NORMAL',
      condition: 'NEAR_MINT',
      pricePaid: { amount: 8.5, currency: 'USD' },
      addedAt: 1_791_667_200_000,
      revision: 1,
      configurationKey: 'base1-4\u001fNORMAL\u001fNEAR_MINT',
    },
    card: {
      id: 'base1-4',
      name: 'Charizard',
      setId: 'base1',
      setName: 'Base Set',
      collectorNumber: '4',
      imageUrl: null,
      variants: ['NORMAL'],
      prices: [],
      setTotal: 102,
    },
  }],
}

test('imports a valid backup into an empty collection and keeps it after reload', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'Perfil' }).click()
  await page.getByLabel('Importar cópia da coleção').setInputFiles({
    name: 'minha-colecao.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(backup)),
  })

  await expect(page.getByText('Cópia aplicada: 1 adicionadas, 0 combinadas.')).toBeVisible()
  await page.getByRole('button', { name: 'Coleção', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('2', { exact: true }).first()).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await page.getByRole('button', { name: 'Remover da coleção' }).click()
  await page.getByRole('button', { name: 'Remover posse' }).click()
  await expect(page.getByText('Posse removida da coleção.')).toBeVisible()
  await page.getByRole('button', { name: 'Perfil' }).click()
  await page.getByLabel('Importar cópia da coleção').setInputFiles({
    name: 'minha-colecao.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(backup)),
  })
  await expect(page.getByText('Cópia aplicada: 1 adicionadas, 0 combinadas.')).toBeVisible()
  await page.getByRole('button', { name: 'Coleção', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
})

test('reapplying a backup is idempotent and a different matching copy merges quantities', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'Perfil' }).click()
  const fileInput = page.getByLabel('Importar cópia da coleção')
  await fileInput.setInputFiles({ name: 'colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
  await expect(page.getByText('Cópia aplicada: 1 adicionadas, 0 combinadas.')).toBeVisible()

  await fileInput.setInputFiles({ name: 'colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
  await expect(page.getByText('Esta cópia já está aplicada.')).toBeVisible()

  const additionalCopy = structuredClone(backup)
  additionalCopy.entries[0]!.item.id = '00000000-0000-4000-8000-000000000002'
  additionalCopy.entries[0]!.item.quantity = 3
  await fileInput.setInputFiles({ name: 'outra-colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(additionalCopy)) })
  await expect(page.getByText('Cópia aplicada: 0 adicionadas, 1 combinadas.')).toBeVisible()
  const refreshedCatalogCopy = {
    ...additionalCopy,
    entries: additionalCopy.entries.map((entry) => ({
      ...entry,
      card: { ...entry.card, prices: [{ source: 'TCGPLAYER', variant: 'NORMAL', currency: 'USD', amount: 12.5, updatedAt: '2026-10-10' }] },
    })),
  }
  await fileInput.setInputFiles({ name: 'outra-colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(refreshedCatalogCopy)) })
  await expect(page.getByText('Esta cópia já está aplicada.')).toBeVisible()
  await page.getByRole('button', { name: 'Coleção', exact: true }).click()
  await expect(page.getByText('5', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await expect(page.getByRole('textbox', { name: 'Preço pago' })).toHaveValue('8,50')
})

test('rejects inconsistent backup without changing the local collection', async ({ page }) => {
  const invalidBackup = structuredClone(backup)
  invalidBackup.entries[0]!.item.cardId = 'base1-5'
  await page.goto('/')
  await page.getByRole('button', { name: 'Perfil' }).click()
  await page.getByLabel('Importar cópia da coleção').setInputFiles({
    name: 'inconsistente.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(invalidBackup)),
  })
  await expect(page.getByText('A cópia contém cartas ou posses inconsistentes.')).toBeVisible()
  await page.getByRole('button', { name: 'Coleção', exact: true }).click()
  await expect(page.getByText('Sua coleção está vazia')).toBeVisible()
})

test('refuses a merge that would overflow revision without breaking local collection', async ({ page }) => {
  const catalogCard = {
    id: 'base1-4', name: 'Charizard', localId: '4', image: null,
    set: { id: 'base1', name: 'Base Set', cardCount: { official: 102, total: 102 } },
    variants_detailed: [{ type: 'normal' }, { type: 'holo' }],
  }
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? catalogCard : [catalogCard]) })
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Perfil' }).click()
  const fileInput = page.getByLabel('Importar cópia da coleção')
  await fileInput.setInputFiles({ name: 'colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) })
  await expect(page.getByText('Cópia aplicada: 1 adicionadas, 0 combinadas.')).toBeVisible()
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('tcg-colecao-web')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const transaction = database.transaction('items', 'readwrite')
    const store = transaction.objectStore('items')
    const request = store.get('00000000-0000-4000-8000-000000000001')
    request.onsuccess = () => store.put({ ...request.result, revision: Number.MAX_SAFE_INTEGER })
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve()
      transaction.onabort = () => reject(transaction.error)
    })
  })

  const additionalCopy = structuredClone(backup)
  additionalCopy.entries[0]!.item.id = '00000000-0000-4000-8000-000000000002'
  await fileInput.setInputFiles({ name: 'outra-colecao.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(additionalCopy)) })
  await expect(page.getByText(/atingiu o limite de edições/)).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'Coleção', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('2', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: 'Editar carta' }).click()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByText(/limite de edições/)).toBeVisible()
  await page.getByRole('button', { name: 'Voltar' }).click()

  await page.getByRole('button', { name: 'Escanear carta' }).click()
  await page.getByRole('button', { name: 'Buscar manualmente' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByText(/limite de edições/)).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('2', { exact: true }).first()).toBeVisible()
})

test('upgrades the existing v1 collection database without losing saved cards', async ({ page }) => {
  await page.route('**/*', async (route) => {
    if (route.request().resourceType() === 'document') {
      await route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><body>database setup</body></html>' })
    } else {
      await route.abort()
    }
  })
  await page.goto('/')
  const [entry] = backup.entries
  if (entry === undefined) throw new Error('Backup fixture must contain one entry')
  await page.evaluate(async ({ item, card }) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('tcg-colecao-web', 1)
      request.onupgradeneeded = () => {
        const items = request.result.createObjectStore('items', { keyPath: 'id' })
        items.createIndex('configurationKey', 'configurationKey', { unique: true })
        request.result.createObjectStore('cards', { keyPath: 'id' })
      }
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    const transaction = database.transaction(['items', 'cards'], 'readwrite')
    transaction.objectStore('items').put(item)
    transaction.objectStore('cards').put(card)
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve()
      transaction.onabort = () => reject(transaction.error)
    })
    database.close()
  }, entry)
  await page.unroute('**/*')
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('2', { exact: true }).first()).toBeVisible()
})
