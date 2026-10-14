import { expect, test } from '@playwright/test'
import type { SyncItem } from '../src/sync/policy.ts'
import { syncSnapshotDigest } from '../src/sync/supabase-transport.ts'

interface RpcCall {
  fn: string
  body: Record<string, unknown>
}

async function seedSignedInSession(page: import('@playwright/test').Page, email: string, userId: string) {
  await page.addInitScript(([session]: string[]) => {
    window.sessionStorage.setItem('tcg-colecao-auth-session', session ?? '')
  }, [JSON.stringify({
    userId,
    email,
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
  })])
}

async function seedLocalCollection(page: import('@playwright/test').Page) {
  return page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base1-4', name: 'Charizard', setId: 'base1', setName: 'Base Set', collectorNumber: '4',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 102,
    }
    const parsed = domain.parseItemDraft(domain.emptyDraft(card))
    if (parsed.kind !== 'valid') throw new Error('invalid test draft')
    const result = await store.addToCollection(card, parsed)
    if (result.kind !== 'saved') throw new Error(`seed failed: ${result.kind}`)
    return store.listCollection()
  })
}

/** Mocks prepare/snapshot/commit so the first-link flow runs without a real server. */
async function mockLinkRpc(
  page: import('@playwright/test').Page,
  calls: RpcCall[],
  options: { conflicting?: boolean; partialFirstCommit?: boolean } = {},
) {
  const seeded = new Map<string, Record<string, unknown>>()
  let returnedPartial = false
  await page.route('**/rest/v1/rpc/**', async (route) => {
    const request = route.request()
    const fn = new URL(request.url()).pathname.split('/').pop() ?? 'unknown'
    const body = (request.postDataJSON() ?? {}) as Record<string, unknown>
    calls.push({ fn, body })
    const respond = (payload: unknown) => route.fulfill({
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify(payload),
    })
    if (fn === 'prepare_collection_link') {
      const items = (body.p_local_items ?? []) as SyncItem[]
      const localOnly = items.filter((item) => !seeded.has(item.id)).map((item) => item.id)
      const identical = items.filter((item) => seeded.has(item.id)).map((item) => item.id)
      const digest = await syncSnapshotDigest(items)
      return respond({
        planId: '9f86d081-884c-4d65-9c67-41b405d56001',
        digest,
        expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
        remoteHighWater: 7 + seeded.size,
        preview: {
          localOnly,
          cloudOnly: [],
          identical,
          conflicting: options.conflicting
            ? localOnly.map((itemId) => ({ itemId, reason: 'different-content' }))
            : [],
          duplicateConfigurations: [],
        },
      })
    }
    if (fn === 'read_collection_link_snapshot') {
      return respond({
        highWater: 7 + seeded.size,
        items: [...seeded.values()].map((item) => ({ ...item, revision: 1 })),
      })
    }
    if (fn === 'commit_collection_link') {
      const operations = (body.p_operations ?? []) as { opId: string; kind: string; item?: Record<string, unknown> }[]
      if (options.partialFirstCommit && !returnedPartial && operations.length > 1) {
        returnedPartial = true
        const accepted = operations[0]
        if (accepted?.item !== undefined) seeded.set(String(accepted.item.id), accepted.item)
        return respond({
          status: 'partial',
          push: {
            status: 'ok',
            results: operations.map((operation, index) => ({
              opId: operation.opId,
              status: index === 0 ? 'applied' : 'conflict',
              ...(index === 0 ? {} : {
                conflictId: '30000000-0000-4000-8000-000000000099',
                highWater: 7 + seeded.size,
                rejectedOperation: operation,
                cloudItem: null,
                conflictingItem: null,
              }),
              reason: index === 0 ? null : 'id-collision',
              revision: index === 0 ? 1 : null,
            })),
            highWater: 7 + seeded.size,
          },
        })
      }
      for (const operation of operations) {
        if (operation.item !== undefined) seeded.set(String(operation.item.id), operation.item)
      }
      return respond({
        status: 'linked',
        push: {
          status: 'ok',
          results: operations.map((operation) => ({ opId: operation.opId, status: 'applied', revision: 1 })),
          highWater: 7 + seeded.size,
        },
      })
    }
    return respond({})
  })
}

test('first link shows a server preview and uploads only after explicit confirmation', async ({ page }) => {
  const calls: RpcCall[] = []
  await mockLinkRpc(page, calls)
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)

  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()
  await expect(page.getByText('Conectado como ana@example.test.')).toBeVisible()
  await page.getByRole('button', { name: 'Revisar vínculo' }).click()

  await expect(page.getByRole('region', { name: 'Prévia do vínculo' })).toBeVisible()
  await expect(page.getByText('1 posse só neste navegador e será enviada')).toBeVisible()
  await expect(page.getByText('0 posses só na nuvem')).toBeVisible()
  expect(calls.map((call) => call.fn)).toEqual(['prepare_collection_link', 'read_collection_link_snapshot'])

  await page.getByRole('button', { name: 'Confirmar vínculo' }).click()
  await expect(page.getByText('Coleção vinculada a esta conta.')).toBeVisible()
  // After the confirmed link the automatic sync may pull, but nothing else is pushed.
  expect(calls.map((call) => call.fn).filter((fn) => fn !== 'pull_collection_operations' && fn !== 'read_collection_push_conflicts')).toEqual([
    'prepare_collection_link', 'read_collection_link_snapshot', 'commit_collection_link',
  ])
  const commit = calls.find((call) => call.fn === 'commit_collection_link')
  expect(commit?.body.p_device_id).toMatch(/^[a-zA-Z0-9-]{8,80}$/)

  const state = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return {
      binding: await store.currentSyncBinding(),
      entries: (await store.listCollection()).length,
      pending: (await store.listPendingOperations('user-ana')).length,
      cursor: await store.lastSyncSequence('user-ana'),
    }
  })
  expect(state.binding?.identity).toBe('user-ana')
  expect(state.binding?.deviceId).toMatch(/^[a-zA-Z0-9-]{8,80}$/)
  expect(state.entries).toBe(1)
  expect(state.pending).toBe(0)
  expect(state.cursor).toBe(8)
})

test('preview with conflicting rows blocks confirmation and never commits', async ({ page }) => {
  const calls: RpcCall[] = []
  await mockLinkRpc(page, calls, { conflicting: true })
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)

  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()
  await page.getByRole('button', { name: 'Revisar vínculo' }).click()

  await expect(page.getByText(/vers(ão|ões) diferente(s)? na nuvem/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Confirmar vínculo' })).toBeDisabled()

  const state = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return { binding: await store.currentSyncBinding(), entries: (await store.listCollection()).length }
  })
  expect(state.binding).toBeNull()
  expect(state.entries).toBe(1)
  expect(calls.some((call) => call.fn === 'commit_collection_link')).toBe(false)
})

test('cancelling the preview leaves the collection local with no server commit', async ({ page }) => {
  const calls: RpcCall[] = []
  await mockLinkRpc(page, calls)
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)

  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()
  await page.getByRole('button', { name: 'Revisar vínculo' }).click()
  await expect(page.getByRole('region', { name: 'Prévia do vínculo' })).toBeVisible()
  await page.getByRole('button', { name: 'Cancelar prévia' }).click()

  await expect(page.getByRole('region', { name: 'Prévia do vínculo' })).toHaveCount(0)
  expect(calls.some((call) => call.fn === 'commit_collection_link')).toBe(false)
  const state = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return { binding: await store.currentSyncBinding(), entries: (await store.listCollection()).length }
  })
  expect(state.binding).toBeNull()
  expect(state.entries).toBe(1)
})

test('partial first link stays local and fresh preview retries only remaining items', async ({ page }) => {
  const calls: RpcCall[] = []
  await mockLinkRpc(page, calls, { partialFirstCommit: true })
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    const domain = await import(`${location.origin}/src/domain/model.ts`)
    const card = {
      id: 'base2-3', name: 'Blastoise', setId: 'base2', setName: 'Jungle', collectorNumber: '3',
      imageUrl: null, variants: ['NORMAL'], prices: [], setTotal: 64,
    }
    const draft = domain.parseItemDraft(domain.emptyDraft(card))
    if (draft.kind !== 'valid') throw new Error('invalid second test draft')
    const result = await store.addToCollection(card, draft)
    if (result.kind !== 'saved') throw new Error(`second seed failed: ${result.kind}`)
  })

  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()
  await page.getByRole('button', { name: 'Revisar vínculo' }).click()
  await page.getByRole('button', { name: 'Confirmar vínculo' }).click()
  await expect(page.getByText(/A nuvem recebeu parte das operações/)).toBeVisible()

  const afterPartial = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return { binding: await store.currentSyncBinding(), entries: (await store.listCollection()).length }
  })
  expect(afterPartial.binding).toBeNull()
  expect(afterPartial.entries).toBe(2)

  await page.getByRole('button', { name: 'Revisar vínculo' }).click()
  await expect(page.getByText('1 posse só neste navegador e será enviada para a conta')).toBeVisible()
  await expect(page.getByText('1 posse já existe idêntica nas duas pontas')).toBeVisible()
  await page.getByRole('button', { name: 'Confirmar vínculo e enviar 1 posse' }).click()
  await expect(page.getByText('Coleção vinculada a esta conta.')).toBeVisible()

  const commits = calls.filter((call) => call.fn === 'commit_collection_link')
  expect(commits).toHaveLength(2)
  expect((commits[1]?.body.p_operations as unknown[])).toHaveLength(1)
  const finalState = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return { binding: await store.currentSyncBinding(), entries: (await store.listCollection()).length }
  })
  expect(finalState.binding?.identity).toBe('user-ana')
  expect(finalState.entries).toBe(2)
})
