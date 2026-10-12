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
  options: { conflicting?: boolean } = {},
) {
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
      const localOnly = items.map((item) => item.id)
      const digest = await syncSnapshotDigest(items)
      return respond({
        planId: '9f86d081-884c-4d65-9c67-41b405d56001',
        digest,
        expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
        remoteHighWater: 7,
        preview: {
          localOnly,
          cloudOnly: [],
          identical: [],
          conflicting: options.conflicting
            ? localOnly.map((itemId) => ({ itemId, reason: 'different-content' }))
            : [],
          duplicateConfigurations: [],
        },
      })
    }
    if (fn === 'read_collection_link_snapshot') {
      return respond({ highWater: 7, items: [] })
    }
    if (fn === 'commit_collection_link') {
      const operations = (body.p_operations ?? []) as { opId: string }[]
      return respond({
        status: 'linked',
        push: {
          status: 'ok',
          results: operations.map((operation) => ({ opId: operation.opId, status: 'applied', revision: 1 })),
          highWater: 8,
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
  expect(calls.map((call) => call.fn).filter((fn) => fn !== 'pull_collection_operations')).toEqual([
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
