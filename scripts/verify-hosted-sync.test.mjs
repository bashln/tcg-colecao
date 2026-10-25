import assert from 'node:assert/strict'
import test from 'node:test'
import {
  canonicalJson,
  operationSignature,
  parseAuthSettings,
  probeAuthSettings,
  readConfig,
  runHostedVerification,
} from './verify-hosted-sync.mjs'

const BASE_URL = 'https://project.supabase.co'
const PUBLISHABLE_KEY = 'sb_publishable_test'
const NOW = 1_791_667_200_000

const ACCOUNTS = [
  {
    id: 'a0a0a0a0-0000-4000-8000-0000000000a1',
    email: 'account-a@example.test',
    password: 'password-a-secret',
    token: 'access-token-a',
  },
  {
    id: 'b0b0b0b0-0000-4000-8000-0000000000b2',
    email: 'account-b@example.test',
    password: 'password-b-secret',
    token: 'access-token-b',
  },
]

const ACCOUNT_A = { email: ACCOUNTS[0].email, password: ACCOUNTS[0].password }
const ACCOUNT_B = { email: ACCOUNTS[1].email, password: ACCOUNTS[1].password }

const CHECK_ORDER = [
  'settings',
  'accounts-distinct',
  'push-applied',
  'pull-own',
  'isolated',
  'cleanup-applied',
  'cleanup-pulled',
]

function response(status, body) {
  return { ok: status >= 200 && status < 300, status, async json() { return body } }
}

function fakeHost({ settings = {}, unconfirmedEmails = [], leak = false, failDelete = false } = {}) {
  const requests = []
  const collections = new Map()
  const tokens = new Map(ACCOUNTS.map((account) => [account.token, account]))

  function collection(userId) {
    if (!collections.has(userId)) collections.set(userId, { operations: [], items: new Map() })
    return collections.get(userId)
  }

  function bearerAccount(headers) {
    const header = headers.Authorization ?? headers.authorization ?? ''
    if (!header.startsWith('Bearer ')) return null
    return tokens.get(header.slice('Bearer '.length)) ?? null
  }

  async function fetcher(url, init = {}) {
    const request = {
      url,
      method: init.method ?? 'GET',
      headers: init.headers ?? {},
      body: init.body === undefined ? null : JSON.parse(init.body),
    }
    requests.push(request)
    const path = new URL(url).pathname

    if (path === '/auth/v1/settings') return response(200, settings)

    if (path === '/auth/v1/token') {
      const account = ACCOUNTS.find((candidate) => candidate.email === request.body.email)
      if (unconfirmedEmails.includes(request.body.email)) {
        return response(400, { code: 400, error_code: 'email_not_confirmed', msg: 'Email not confirmed' })
      }
      if (account === undefined || account.password !== request.body.password) {
        return response(400, { error_code: 'invalid_credentials', msg: 'Invalid login credentials' })
      }
      return response(200, {
        access_token: account.token,
        refresh_token: `refresh-${account.token}`,
        expires_in: 3600,
        token_type: 'bearer',
        user: { id: account.id, email: account.email },
      })
    }

    if (path === '/rest/v1/rpc/push_collection_operations') {
      const account = bearerAccount(request.headers)
      if (account === null) return response(401, { message: 'not authenticated' })
      if (failDelete && request.body.p_operations.some((operation) => operation.kind === 'delete')) {
        return response(500, { message: 'simulated delete failure' })
      }
      const target = collection(account.id)
      const results = request.body.p_operations.map((operation) => {
        if (operation.kind === 'create') {
          if (target.items.has(operation.item.id)) {
            return { opId: operation.opId, status: 'stale', reason: 'stale', revision: 1 }
          }
          target.items.set(operation.item.id, { revision: 1 })
          target.operations.push(operation)
          return { opId: operation.opId, status: 'applied', reason: null, revision: 1 }
        }
        const item = target.items.get(operation.itemId)
        if (item === undefined) return { opId: operation.opId, status: 'conflict', reason: 'missing', revision: null }
        if (item.revision !== operation.baseRevision) {
          return { opId: operation.opId, status: 'conflict', reason: 'stale', revision: item.revision }
        }
        item.revision += 1
        target.operations.push(operation)
        return { opId: operation.opId, status: 'applied', reason: null, revision: item.revision }
      })
      return response(200, { status: 'ok', results, highWater: target.operations.length })
    }

    if (path === '/rest/v1/rpc/pull_collection_operations') {
      const account = bearerAccount(request.headers)
      if (account === null) return response(401, { message: 'not authenticated' })
      const viewer = leak && account.id === ACCOUNTS[1].id ? ACCOUNTS[0] : account
      const source = collection(viewer.id)
      return response(200, {
        highWater: source.operations.length,
        operations: source.operations.map((operation, index) => ({
          opId: operation.opId,
          serverSequence: index + 1,
          deviceId: operation.deviceId,
          itemId: operation.kind === 'delete' ? operation.itemId : operation.item.id,
          kind: operation.kind,
          baseRevision: operation.baseRevision ?? null,
          item: operation.kind === 'delete' ? null : operation.item,
        })),
      })
    }

    return response(404, { message: `unexpected path ${path}` })
  }

  return { fetcher, requests }
}

function uuidSequence() {
  let counter = 0
  return () => `00000000-0000-4000-8000-${String((counter += 1)).padStart(12, '0')}`
}

function runOptions(host, overrides = {}) {
  return {
    fetcher: host.fetcher,
    baseUrl: BASE_URL,
    publishableKey: PUBLISHABLE_KEY,
    accountA: ACCOUNT_A,
    accountB: ACCOUNT_B,
    now: () => NOW,
    randomUuid: uuidSequence(),
    ...overrides,
  }
}

function checkNamed(result, name) {
  const check = result.checks.find((candidate) => candidate.name === name)
  assert.ok(check, `check ${name} is present`)
  return check
}

test('happy path proves settings, isolation, push, pull and cleanup without leaking secrets', async () => {
  const host = fakeHost({
    settings: {
      mailer_autoconfirm: false,
      disable_signup: true,
      external: { email: true, github: true, google: false },
    },
  })

  const result = await runHostedVerification(runOptions(host))

  assert.equal(result.ok, true)
  assert.deepEqual(result.checks.map((check) => check.name), CHECK_ORDER)
  assert.deepEqual(result.authSettings, {
    mailerAutoconfirm: false,
    disableSignup: true,
    externalProviders: ['email', 'github'],
  })
  for (const check of result.checks) assert.equal(check.ok, true, `${check.name}: ${check.detail}`)

  const settingsRequest = host.requests.find((request) => request.url === `${BASE_URL}/auth/v1/settings`)
  assert.equal(settingsRequest.headers.apikey, PUBLISHABLE_KEY)

  const createPush = host.requests.find((request) =>
    request.url === `${BASE_URL}/rest/v1/rpc/push_collection_operations` &&
    request.body.p_operations[0].kind === 'create')
  assert.equal(createPush.headers.apikey, PUBLISHABLE_KEY)
  assert.equal(createPush.headers.Authorization, `Bearer ${ACCOUNTS[0].token}`)
  assert.match(createPush.body.p_device_id, /^verify-00000000-0000-4000-8000-\d{12}$/)

  const [createOperation] = createPush.body.p_operations
  assert.match(createOperation.item.id, /^item-00000000-0000-4000-8000-\d{12}$/)
  assert.deepEqual({ ...createOperation.item, id: 'item-id' }, {
    id: 'item-id',
    cardId: 'base1-4',
    quantity: 1,
    variant: 'NORMAL',
    condition: 'NEAR_MINT',
    pricePaid: null,
    addedAt: NOW,
  })
  assert.equal(createOperation.kind, 'create')
  assert.match(createOperation.opId, /^[0-9a-f-]{36}$/)
  assert.equal(createOperation.deviceId, createPush.body.p_device_id)
  assert.match(createOperation.signature, /^[0-9a-f]{64}$/)

  const deletePush = host.requests.find((request) =>
    request.url === `${BASE_URL}/rest/v1/rpc/push_collection_operations` &&
    request.body.p_operations[0].kind === 'delete')
  assert.equal(deletePush.headers.Authorization, `Bearer ${ACCOUNTS[0].token}`)
  assert.deepEqual(
    { kind: deletePush.body.p_operations[0].kind, itemId: deletePush.body.p_operations[0].itemId, baseRevision: deletePush.body.p_operations[0].baseRevision },
    { kind: 'delete', itemId: createOperation.item.id, baseRevision: 1 },
  )
  assert.match(deletePush.body.p_operations[0].signature, /^[0-9a-f]{64}$/)

  const pullRequests = host.requests.filter((request) =>
    request.url === `${BASE_URL}/rest/v1/rpc/pull_collection_operations`)
  assert.equal(pullRequests.length, 3)
  assert.deepEqual(pullRequests.map((request) => request.headers.Authorization), [
    `Bearer ${ACCOUNTS[0].token}`,
    `Bearer ${ACCOUNTS[1].token}`,
    `Bearer ${ACCOUNTS[0].token}`,
  ])
  for (const request of pullRequests) {
    assert.deepEqual(request.body, { p_after_sequence: 0, p_page_limit: 500 })
    assert.equal(request.headers.apikey, PUBLISHABLE_KEY)
  }

  const summary = JSON.stringify(result)
  for (const secret of [ACCOUNTS[0].token, ACCOUNTS[1].token, `refresh-${ACCOUNTS[0].token}`,
    ACCOUNT_A.password, ACCOUNT_B.password, 'refresh_token', 'access_token']) {
    assert.equal(summary.includes(secret), false, `summary leaks ${secret}`)
  }
})

test('isolation leak fails the isolated check and still cleans up', async () => {
  const host = fakeHost({ leak: true })

  const result = await runHostedVerification(runOptions(host))

  assert.equal(result.ok, false)
  assert.equal(checkNamed(result, 'push-applied').ok, true)
  assert.equal(checkNamed(result, 'pull-own').ok, true)
  assert.equal(checkNamed(result, 'isolated').ok, false)
  assert.match(checkNamed(result, 'isolated').detail, /account B/)
  assert.equal(checkNamed(result, 'cleanup-applied').ok, true)
  assert.equal(checkNamed(result, 'cleanup-pulled').ok, true)
})

test('cleanup failure is reported next to the original failure', async () => {
  const host = fakeHost({ leak: true, failDelete: true })

  const result = await runHostedVerification(runOptions(host))

  assert.equal(result.ok, false)
  assert.equal(checkNamed(result, 'isolated').ok, false)
  assert.equal(checkNamed(result, 'cleanup-applied').ok, false)
  assert.match(checkNamed(result, 'cleanup-applied').detail, /simulated delete failure/)
  assert.equal(checkNamed(result, 'cleanup-pulled').ok, false)
})

test('unconfirmed email throws an actionable typed error before any RPC', async () => {
  const host = fakeHost({ unconfirmedEmails: [ACCOUNT_A.email] })

  await assert.rejects(runHostedVerification(runOptions(host)), (error) => {
    assert.equal(error.code, 'email-not-confirmed')
    assert.match(error.message, /email confirmation/i)
    assert.match(error.message, /custom SMTP/i)
    assert.match(error.message, /callback domain/i)
    return true
  })

  assert.equal(host.requests.some((request) => request.url.includes('/rest/v1/rpc/')), false)
})

test('bad credentials throw an auth-failed error without echoing the password', async () => {
  const host = fakeHost()

  await assert.rejects(
    runHostedVerification(runOptions(host, { accountA: { email: ACCOUNT_A.email, password: 'wrong-password-value' } })),
    (error) => {
      assert.equal(error.code, 'auth-failed')
      assert.equal(error.message.includes('wrong-password-value'), false)
      assert.match(error.message, /account A/)
      return true
    },
  )
})

test('readConfig names the missing variable and never echoes configured values', () => {
  assert.throws(() => readConfig({}), (error) => {
    assert.equal(error.code, 'missing-config')
    assert.match(error.message, /SUPABASE_URL/)
    return true
  })

  assert.throws(
    () => readConfig({
      TCG_SUPABASE_URL: 'https://alt.supabase.co',
      TCG_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_alt',
      HOSTED_EMAIL_A: ACCOUNT_A.email,
      HOSTED_PASSWORD_A: ACCOUNT_A.password,
      HOSTED_EMAIL_B: ACCOUNT_B.email,
    }),
    (error) => {
      assert.equal(error.code, 'missing-config')
      assert.match(error.message, /HOSTED_PASSWORD_B/)
      assert.equal(error.message.includes(ACCOUNT_A.password), false)
      return true
    },
  )

  assert.deepEqual(
    readConfig({
      TCG_SUPABASE_URL: 'https://alt.supabase.co',
      TCG_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_alt',
      HOSTED_EMAIL_A: ACCOUNT_A.email,
      HOSTED_PASSWORD_A: ACCOUNT_A.password,
      HOSTED_EMAIL_B: ACCOUNT_B.email,
      HOSTED_PASSWORD_B: ACCOUNT_B.password,
    }),
    {
      baseUrl: 'https://alt.supabase.co',
      publishableKey: 'sb_publishable_alt',
      accountA: ACCOUNT_A,
      accountB: ACCOUNT_B,
    },
  )
})

test('operationSignature is order independent and matches the client hash', async () => {
  const fixed = {
    kind: 'create',
    opId: '10000000-0000-4000-8000-000000000001',
    item: {
      id: 'item-10000000-0000-4000-8000-000000000002',
      cardId: 'base1-4',
      quantity: 1,
      variant: 'NORMAL',
      condition: 'NEAR_MINT',
      pricePaid: null,
      addedAt: NOW,
    },
  }
  const reordered = {
    item: {
      addedAt: NOW,
      pricePaid: null,
      condition: 'NEAR_MINT',
      variant: 'NORMAL',
      quantity: 1,
      cardId: 'base1-4',
      id: 'item-10000000-0000-4000-8000-000000000002',
    },
    opId: '10000000-0000-4000-8000-000000000001',
    kind: 'create',
  }

  const signature = await operationSignature(fixed)

  assert.equal(signature, '820124f6cf8b37073134f55289dbb0811be0e3df7252da0db8ab0941c7e1cd25')
  assert.equal(await operationSignature(reordered), signature)
  assert.match(signature, /^[0-9a-f]{64}$/)
  assert.equal(
    canonicalJson({ b: 1, a: [{ d: 2, c: 3 }] }),
    '{"a":[{"c":3,"d":2}],"b":1}',
  )
})

test('probeAuthSettings tolerates absent fields and keeps hidden providers out', async () => {
  const host = fakeHost({ settings: {} })

  assert.deepEqual(
    await probeAuthSettings({ fetcher: host.fetcher, baseUrl: BASE_URL, publishableKey: PUBLISHABLE_KEY }),
    { mailerAutoconfirm: null, disableSignup: null, externalProviders: [] },
  )
  assert.deepEqual(parseAuthSettings({ mailer_autoconfirm: true, external: { email: false } }), {
    mailerAutoconfirm: true,
    disableSignup: null,
    externalProviders: [],
  })
})
