import assert from 'node:assert/strict'
import test from 'node:test'
import { SupabaseWebAuth, type AuthStorage } from './auth.ts'

class MemoryStorage implements AuthStorage {
  value: string | null = null

  getItem() { return this.value }
  setItem(value: string) { this.value = value }
  removeItem() { this.value = null }
}

const sessionBody = {
  access_token: 'access-token',
  refresh_token: 'refresh-token',
  expires_at: 2_000,
  user: { id: 'user-1', email: 'collector@example.test' },
}

test('sign-up without a session reports email verification and stores no session', async () => {
  const storage = new MemoryStorage()
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async () => new Response(JSON.stringify({ id: 'user-1', email: 'collector@example.test' }), { status: 200 }),
  })

  const result = await auth.signUp('collector@example.test', 'a-long-password')

  assert.deepEqual(result, { kind: 'verification-required', email: 'collector@example.test' })
  assert.equal(storage.value, null)
})

test('sign-up with a session persists only the returned auth session', async () => {
  const storage = new MemoryStorage()
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async () => new Response(JSON.stringify(sessionBody), { status: 200 }),
  })

  const result = await auth.signUp('collector@example.test', 'a-long-password')

  assert.equal(result.kind, 'signed-in')
  assert.equal(storage.value?.includes('a-long-password'), false)
  assert.equal(JSON.parse(storage.value ?? '{}').accessToken, 'access-token')
})

test('confirmation callback validates bearer token before storing the session', async () => {
  const storage = new MemoryStorage()
  const requests: Request[] = []
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async (input, init) => {
      requests.push(new Request(input, init))
      return new Response(JSON.stringify({ id: 'user-1', email: 'collector@example.test' }), { status: 200 })
    },
  })

  const state = await auth.consumeConfirmation('#access_token=verified-access&refresh_token=verified-refresh&expires_in=3600&type=signup')

  assert.equal(state.kind, 'signed-in')
  assert.deepEqual(state.kind === 'signed-in' ? state.session : null, {
    userId: 'user-1',
    email: 'collector@example.test',
    accessToken: 'verified-access',
    refreshToken: 'verified-refresh',
    expiresAtEpochSeconds: 4_600,
  })
  assert.equal(storage.value?.includes('verified-access'), true)
  assert.equal(requests.length, 1)
  assert.equal(new URL(requests[0]!.url).pathname, '/auth/v1/user')
  assert.equal(requests[0]!.headers.get('authorization'), 'Bearer verified-access')
})

test('confirmation callback refuses to store tokens when the user endpoint rejects them', async () => {
  const storage = new MemoryStorage()
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async () => new Response('{}', { status: 401 }),
  })

  const state = await auth.consumeConfirmation('#access_token=invalid&refresh_token=untrusted&expires_in=3600&type=signup')

  assert.deepEqual(state, { kind: 'signed-out' })
  assert.equal(storage.value, null)
})

test('RPC sends publishable key and signed-in access token, never password', async () => {
  const storage = new MemoryStorage()
  const requests: Request[] = []
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async (input, init) => {
      requests.push(new Request(input, init))
      if (new URL(String(input)).pathname.endsWith('/token')) return new Response(JSON.stringify(sessionBody), { status: 200 })
      return new Response(JSON.stringify({ status: 'ok' }), { status: 200 })
    },
  })
  await auth.signIn('collector@example.test', 'a-long-password')

  const response = await auth.rpc('push_collection_operations', { p_device_id: 'device-1' })

  assert.deepEqual(response, { status: 'ok' })
  const rpc = requests.at(-1)
  assert.equal(rpc?.headers.get('apikey'), 'sb_publishable_test')
  assert.equal(rpc?.headers.get('authorization'), 'Bearer access-token')
  assert.equal(await rpc?.text(), JSON.stringify({ p_device_id: 'device-1' }))
})

test('RPC refuses an account switch before dispatching queued operations', async () => {
  const storage = new MemoryStorage()
  const requests: Request[] = []
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async (input, init) => {
      requests.push(new Request(input, init))
      return new Response(JSON.stringify(sessionBody), { status: 200 })
    },
  })
  await auth.signIn('collector@example.test', 'a-long-password')

  await assert.rejects(
    auth.rpc('push_collection_operations', { p_device_id: 'device-1' }, 'user-2'),
    /conta mudou/i,
  )

  assert.equal(requests.filter((request) => new URL(request.url).pathname.includes('/rest/v1/rpc/')).length, 0)
})

test('expired stored session refreshes before use', async () => {
  const storage = new MemoryStorage()
  storage.setItem(JSON.stringify({
    userId: 'user-1', email: 'collector@example.test', accessToken: 'expired',
    refreshToken: 'refresh-token', expiresAtEpochSeconds: 900,
  }))
  let refreshCalls = 0
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async () => {
      refreshCalls += 1
      return new Response(JSON.stringify({ ...sessionBody, access_token: 'fresh-token' }), { status: 200 })
    },
  })

  const state = await auth.start()

  assert.equal(refreshCalls, 1)
  assert.equal(state.kind, 'signed-in')
  assert.equal(state.kind === 'signed-in' ? state.session.accessToken : '', 'fresh-token')
})

test('RPC refreshes a session that expires during the active page', async () => {
  const storage = new MemoryStorage()
  let refreshCalls = 0
  let rpcAuthorization = ''
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async (input, init) => {
      const url = new URL(String(input))
      if (url.pathname.endsWith('/token')) {
        if (url.searchParams.get('grant_type') === 'password') {
          return new Response(JSON.stringify({ ...sessionBody, expires_at: 1_001 }), { status: 200 })
        }
        refreshCalls += 1
        return new Response(JSON.stringify({ ...sessionBody, access_token: 'fresh-token', expires_at: 2_000 }), { status: 200 })
      }
      rpcAuthorization = new Headers(init?.headers).get('authorization') ?? ''
      return new Response(JSON.stringify({ status: 'ok' }), { status: 200 })
    },
  })
  await auth.signIn('collector@example.test', 'a-long-password')

  await auth.rpc('pull_collection_operations', {})

  assert.equal(refreshCalls, 1)
  assert.equal(rpcAuthorization, 'Bearer fresh-token')
})

test('sign-out clears local session even when remote logout fails', async () => {
  const storage = new MemoryStorage()
  const auth = new SupabaseWebAuth({
    url: 'https://project.supabase.co',
    publishableKey: 'sb_publishable_test',
    storage,
    now: () => 1_000,
    fetcher: async (input) => new URL(String(input)).pathname.endsWith('/token')
      ? new Response(JSON.stringify(sessionBody), { status: 200 })
      : new Response('{}', { status: 503 }),
  })
  await auth.signIn('collector@example.test', 'a-long-password')

  await auth.signOut()

  assert.equal(storage.value, null)
  assert.deepEqual(await auth.start(), { kind: 'signed-out' })
})
