import { expect, test } from '@playwright/test'
import { linkThroughUi, mockSyncRpc, readOutboxIdentities, seedLocalCollection, seedSignedInSession } from './helpers.ts'

test('a local edit while linked triggers an automatic push without any button press', async ({ page }) => {
  const harness = await mockSyncRpc(page)
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await expect(page.getByText('Sincronizado. Nenhuma alteração pendente.')).toBeVisible()

  const pushesBefore = harness.calls.filter((call) => call.fn === 'push_collection_operations').length

  await page.getByRole('button', { name: 'Coleção' }).first().click()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await expect(page.getByText('Quantidade atualizada.')).toBeVisible()

  await expect.poll(() => harness.calls.filter((call) =>
    call.fn === 'push_collection_operations' &&
    (call.body.p_operations as { kind: string }[]).some((operation) => operation.kind === 'replace'),
  ).length).toBeGreaterThan(pushesBefore)
  await expect.poll(async () => (await readOutboxIdentities(page)).length).toBe(0)
})

test('network recovery triggers an automatic sync that drains the pending outbox', async ({ page }) => {
  const harness = await mockSyncRpc(page)
  harness.failPushes = true
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await expect(page.getByText('Coleção vinculada a esta conta.')).toBeVisible()

  // Seed an item while the transport is failing: the edit stays in the outbox.
  await page.getByRole('button', { name: 'Coleção' }).first().click()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await expect(page.getByText('Quantidade atualizada.')).toBeVisible()
  await expect.poll(() => (harness.calls.filter((call) => call.fn === 'push_collection_operations').length)).toBeGreaterThan(0)
  expect((await readOutboxIdentities(page)).length).toBe(1)

  // Server recovers; the browser returns online: the runner drains without a manual click.
  harness.failPushes = false
  await page.evaluate(() => window.dispatchEvent(new Event('online')))

  await expect.poll(async () => (await readOutboxIdentities(page)).length, { timeout: 10_000 }).toBe(0)
  await page.getByRole('button', { name: 'Perfil' }).click()
  await expect(page.getByText('Sincronizado. Nenhuma alteração pendente.')).toBeVisible({ timeout: 10_000 })
})

test('returning to the foreground triggers a sync while signed in and bound', async ({ page }) => {
  const harness = await mockSyncRpc(page)
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await expect(page.getByText('Sincronizado. Nenhuma alteração pendente.')).toBeVisible()

  const pullsBefore = harness.calls.filter((call) => call.fn === 'pull_collection_operations').length
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
  })

  await expect.poll(() => harness.calls.filter((call) => call.fn === 'pull_collection_operations').length)
    .toBeGreaterThan(pullsBefore)
})

test('signing out stops sync, preserves same-account binding, and keeps every local card', async ({ page }) => {
  const harness = await mockSyncRpc(page)
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await expect(page.getByText('Sincronizado. Nenhuma alteração pendente.')).toBeVisible()

  await page.getByRole('button', { name: 'Sair da conta' }).click()
  await expect(page.getByText('Entrar', { exact: true })).toBeVisible()
  await page.evaluate(() => window.sessionStorage.setItem('tcg-colecao-auth-session', '{}'))
  await page.reload()

  const localState = await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return { binding: await store.currentSyncBinding(), items: (await store.listCollection()).length }
  })
  expect(localState.binding?.identity).toBe('user-ana')
  expect(localState.binding?.deviceId).toMatch(/^[a-zA-Z0-9-]{8,80}$/)
  expect(localState.items).toBe(1)

  const callsAtSignOut = harness.calls.length
  await page.evaluate(() => window.dispatchEvent(new Event('online')))
  await page.waitForTimeout(400)
  expect(harness.calls.length).toBe(callsAtSignOut)

  await page.getByRole('button', { name: 'Perfil' }).click()
  await expect(page.getByText('Entre ou crie uma conta. Isso não envia nem combina sua coleção local.')).toBeVisible()
  await page.getByRole('button', { name: 'Coleção' }).first().click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
})

test('switching to a different account clears the old binding and requires a fresh preview', async ({ page }) => {
  await mockSyncRpc(page)
  await seedSignedInSession(page, 'ana@example.test', 'user-ana')
  await page.goto('/')
  await seedLocalCollection(page)
  await linkThroughUi(page)
  await expect(page.getByText('Sincronizado. Nenhuma alteração pendente.')).toBeVisible()

  await page.getByRole('button', { name: 'Sair da conta' }).click()
  await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible()
  await page.evaluate(() => {
    window.sessionStorage.setItem('tcg-colecao-auth-session', JSON.stringify({
      userId: 'user-bruno',
      email: 'bruno@example.test',
      accessToken: 'access-token-2',
      refreshToken: 'refresh-token-2',
      expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
    }))
  })
  await page.reload()
  await page.getByRole('button', { name: 'Perfil' }).click()
  await expect(page.getByText('Conectado como bruno@example.test.')).toBeVisible()

  expect(await page.evaluate(async () => {
    const store = await import(`${location.origin}/src/collection/store.ts`)
    return { binding: await store.currentSyncBinding(), items: (await store.listCollection()).length }
  })).toEqual({ binding: null, items: 1 })

  await expect(page.getByText('Sua coleção continua somente neste navegador até você revisar e confirmar o vínculo. Entrar não envia cartas.')).toBeVisible()
  await page.getByRole('button', { name: 'Revisar vínculo' }).click()
  await expect(page.getByRole('region', { name: 'Prévia do vínculo' })).toBeVisible()
})
