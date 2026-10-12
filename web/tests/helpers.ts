import type { Page } from '@playwright/test'
import type { SyncItem } from '../src/sync/policy.ts'
import { syncSnapshotDigest } from '../src/sync/supabase-transport.ts'

export const tcgdexCard = {
  id: 'base1-4', name: 'Charizard', localId: '4', image: null,
  set: { id: 'base1', name: 'Base Set', cardCount: { official: 102, total: 102 } },
  variants_detailed: [{ type: 'holo', pricing: {} }],
}

export interface RpcCall {
  fn: string
  body: Record<string, unknown>
  failed?: boolean
}

export interface SyncHarness {
  calls: RpcCall[]
  highWater: number
  failPushes: boolean
}

export async function seedSignedInSession(page: Page, email: string, userId: string) {
  await page.addInitScript(([session]: string[]) => {
    // Never clobber a session the test replaced explicitly between navigations.
    if (window.sessionStorage.getItem('tcg-colecao-auth-session') === null) {
      window.sessionStorage.setItem('tcg-colecao-auth-session', session ?? '')
    }
  }, [JSON.stringify({
    userId,
    email,
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
  })])
}

export async function replaceSession(page: Page, email: string, userId: string) {
  await page.evaluate(([session]: string[]) => {
    window.sessionStorage.setItem('tcg-colecao-auth-session', session ?? '')
  }, [JSON.stringify({
    userId,
    email,
    accessToken: 'access-token-2',
    refreshToken: 'refresh-token-2',
    expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
  })])
}

export async function seedLocalCollection(page: Page) {
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

export async function readOutboxIdentities(page: Page): Promise<{ identity: string; kind: string }[]> {
  return page.evaluate(async () => {
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
    return rows.map((row) => {
      const entry = row as { identity: string; operation: { kind: string } }
      return { identity: entry.identity, kind: entry.operation.kind }
    })
  })
}

/**
 * Mocks the whole Supabase sync surface (prepare/snapshot/commit plus push/pull) with a
 * coherent server high-water sequence, so link and automatic sync flows run end to end.
 */
export async function mockSyncRpc(page: Page, options: { conflicting?: boolean; pullOperation?: Record<string, unknown> } = {}): Promise<SyncHarness> {
  const harness: SyncHarness = { calls: [], highWater: 7, failPushes: false }
  await page.route('**/rest/v1/rpc/**', async (route) => {
    const request = route.request()
    const fn = new URL(request.url()).pathname.split('/').pop() ?? 'unknown'
    const body = (request.postDataJSON() ?? {}) as Record<string, unknown>
    const respond = (payload: unknown, status = 200) => route.fulfill({
      status,
      contentType: 'application/json',
      headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify(payload),
    })
    if (fn === 'push_collection_operations') {
      if (harness.failPushes) {
        harness.calls.push({ fn, body, failed: true })
        return respond({ message: 'Serviço indisponível' }, 503)
      }
      harness.calls.push({ fn, body })
      const operations = (body.p_operations ?? []) as { opId: string; kind: string; baseRevision?: number }[]
      const results = operations.map((operation) => {
        harness.highWater += 1
        return {
          opId: operation.opId,
          status: 'applied',
          revision: operation.kind === 'create' ? 1 : (operation.baseRevision ?? 0) + 1,
        }
      })
      return respond({ status: 'ok', results, highWater: harness.highWater })
    }
    harness.calls.push({ fn, body })
    if (fn === 'prepare_collection_link') {
      const items = (body.p_local_items ?? []) as SyncItem[]
      const localOnly = items.map((item) => item.id)
      const digest = await syncSnapshotDigest(items)
      return respond({
        planId: '9f86d081-884c-4d65-9c67-41b405d56001',
        digest,
        expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
        remoteHighWater: harness.highWater,
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
      return respond({ highWater: harness.highWater, items: [] })
    }
    if (fn === 'pull_collection_operations') {
      const after = Number(body.p_after_sequence ?? 0)
      const operation = options.pullOperation
      return respond({
        operations: operation !== undefined && after < Number(operation.serverSequence) ? [operation] : [],
        highWater: operation === undefined ? harness.highWater : Math.max(harness.highWater, Number(operation.serverSequence)),
      })
    }
    if (fn === 'commit_collection_link') {
      const operations = (body.p_operations ?? []) as { opId: string }[]
      const results = operations.map((operation) => {
        harness.highWater += 1
        return { opId: operation.opId, status: 'applied', revision: 1 }
      })
      return respond({ status: 'linked', push: { status: 'ok', results, highWater: harness.highWater } })
    }
    return respond({})
  })
  return harness
}

/** Runs the visible first-link flow: review preview, then confirm. */
export async function linkThroughUi(page: Page) {
  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()
  await page.getByRole('button', { name: 'Revisar vínculo' }).click()
  await page.getByRole('button', { name: 'Confirmar vínculo' }).click()
  await page.getByText('Coleção vinculada a esta conta.').waitFor()
}
