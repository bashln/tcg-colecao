import { expect, test } from '@playwright/test'

test('account signup clearly requests email verification and never binds local collection', async ({ page }) => {
  const requests: string[] = []
  await page.route('**/*', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (request.method() === 'OPTIONS' && pathname.includes('/auth/v1/')) {
      await route.fulfill({ status: 204, headers: {
        'access-control-allow-origin': '*',
        'access-control-allow-methods': 'POST, OPTIONS',
        'access-control-allow-headers': 'apikey, authorization, content-type',
      } })
      return
    }
    if (!pathname.endsWith('/auth/v1/signup')) return route.continue()
    requests.push('signup')
    await route.fulfill({ status: 200, headers: { 'access-control-allow-origin': '*' }, contentType: 'application/json', body: JSON.stringify({ id: 'user-1', email: 'collector@example.test' }) })
  })
  await page.route('**/rest/v1/rpc/**', async (route) => {
    requests.push('rpc')
    await route.fulfill({ status: 200, headers: { 'access-control-allow-origin': '*' }, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/')
  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()
  await page.getByRole('button', { name: 'Ainda não tenho conta' }).click()
  await page.getByLabel('E-mail').fill('collector@example.test')
  await page.getByLabel('Senha').fill('a-long-password')
  await page.getByRole('button', { name: 'Criar conta' }).click()

  await expect(page.getByText(/Enviamos uma mensagem de confirmação/)).toBeVisible()
  await expect(page.getByText(/Entrar não envia nem combina sua coleção/)).toHaveCount(0)
  expect(requests).toEqual(['signup'])
})

test('email confirmation callback verifies the token, cleans the URL, and still does not link the collection', async ({ page }) => {
  const requests: string[] = []
  const rpcCalls: string[] = []
  await page.route('**/*', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (request.method() === 'OPTIONS' && pathname.includes('/auth/v1/')) {
      await route.fulfill({ status: 204, headers: {
        'access-control-allow-origin': '*',
        'access-control-allow-methods': 'GET, POST, OPTIONS',
        'access-control-allow-headers': 'apikey, authorization, content-type',
      } })
      return
    }
    if (!pathname.endsWith('/auth/v1/user')) return route.continue()
    requests.push(request.headers().authorization ?? '')
    await route.fulfill({ status: 200, headers: { 'access-control-allow-origin': '*' }, contentType: 'application/json', body: JSON.stringify({
      id: 'verified-user', email: 'verified@example.test',
    }) })
  })
  await page.route('**/rest/v1/rpc/**', async (route) => {
    rpcCalls.push(new URL(route.request().url()).pathname)
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/#access_token=verified-access&refresh_token=verified-refresh&expires_in=3600&type=signup')
  await expect.poll(() => page.evaluate(() => location.hash)).toBe('')
  await page.getByRole('navigation', { name: 'Seções' }).getByRole('button', { name: 'Perfil' }).click()

  await expect(page.getByText('Conectado como verified@example.test.')).toBeVisible()
  await expect(page.getByText('A coleção ainda não foi vinculada. Entrar não envia suas cartas.')).toBeVisible()
  expect(requests.length).toBeGreaterThan(0)
  expect(requests.every((authorization) => authorization === 'Bearer verified-access')).toBe(true)
  expect(rpcCalls).toEqual([])
})
