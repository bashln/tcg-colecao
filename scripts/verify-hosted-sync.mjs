import { createHash, randomUUID } from 'node:crypto'
import { pathToFileURL } from 'node:url'

export class HostedVerificationError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'HostedVerificationError'
    this.code = code
  }
}

const URL_VARIABLES = ['SUPABASE_URL', 'TCG_SUPABASE_URL']
const KEY_VARIABLES = ['SUPABASE_PUBLISHABLE_KEY', 'TCG_SUPABASE_PUBLISHABLE_KEY']

const CHECK_NAMES = [
  'settings',
  'accounts-distinct',
  'push-applied',
  'pull-own',
  'isolated',
  'cleanup-applied',
  'cleanup-pulled',
]

const CREATE_ITEM_FIELDS = ['id', 'cardId', 'quantity', 'variant', 'condition', 'pricePaid', 'addedAt']

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value !== null && typeof value === 'object') {
    const sorted = {}
    for (const key of Object.keys(value).sort()) sorted[key] = canonical(value[key])
    return sorted
  }
  return value
}

/** Byte-identical to the web client's `canonical` plus `JSON.stringify`. */
export function canonicalJson(value) {
  return JSON.stringify(canonical(value))
}

function signableOperation(operation) {
  if (operation.kind === 'delete') {
    return { kind: 'delete', opId: operation.opId, itemId: operation.itemId, baseRevision: operation.baseRevision }
  }
  const item = {}
  for (const field of CREATE_ITEM_FIELDS) item[field] = operation.item[field]
  return { kind: operation.kind, opId: operation.opId, item }
}

export async function operationSignature(operation) {
  return createHash('sha256').update(canonicalJson(signableOperation(operation))).digest('hex')
}

export function parseAuthSettings(body) {
  const settings = body !== null && typeof body === 'object' ? body : {}
  const external = settings.external !== null && typeof settings.external === 'object' ? settings.external : {}
  return {
    mailerAutoconfirm: typeof settings.mailer_autoconfirm === 'boolean' ? settings.mailer_autoconfirm : null,
    disableSignup: typeof settings.disable_signup === 'boolean' ? settings.disable_signup : null,
    externalProviders: Object.keys(external).filter((name) => external[name] === true).sort(),
  }
}

function normalizeBaseUrl(value) {
  return String(value).replace(/\/+$/, '')
}

function requireString(source, name) {
  const value = source[name]
  if (typeof value !== 'string' || value.length === 0) {
    throw new HostedVerificationError('missing-config', `Missing configuration: ${name} is not set`)
  }
  return value
}

function firstString(source, names) {
  for (const name of names) {
    if (typeof source[name] === 'string' && source[name].length > 0) return source[name]
  }
  throw new HostedVerificationError('missing-config', `Missing configuration: set ${names.join(' or ')}`)
}

export function readConfig(env) {
  return {
    baseUrl: normalizeBaseUrl(firstString(env, URL_VARIABLES)),
    publishableKey: firstString(env, KEY_VARIABLES),
    accountA: { email: requireString(env, 'HOSTED_EMAIL_A'), password: requireString(env, 'HOSTED_PASSWORD_A') },
    accountB: { email: requireString(env, 'HOSTED_EMAIL_B'), password: requireString(env, 'HOSTED_PASSWORD_B') },
  }
}

async function requestJson(fetcher, url, init) {
  const response = await fetcher(url, init)
  let body = null
  try {
    body = await response.json()
  } catch {
    body = null
  }
  return { status: response.status, body }
}

function bodyMessage(body) {
  if (body === null || typeof body !== 'object') return ''
  for (const field of ['msg', 'message', 'error_description', 'error']) {
    if (typeof body[field] === 'string' && body[field].length > 0) return body[field]
  }
  return ''
}

function messageFrom(body) {
  const message = bodyMessage(body)
  return message === '' ? '' : `: ${message.slice(0, 200)}`
}

function describeError(error) {
  return {
    code: typeof error?.code === 'string' ? error.code : 'unexpected',
    message: error instanceof Error ? error.message : String(error),
  }
}

function bearerHeaders(publishableKey, accessToken) {
  return {
    apikey: publishableKey,
    Authorization: `Bearer ${accessToken}`,
    'Content-Type': 'application/json',
  }
}

export async function probeAuthSettings({ fetcher = fetch, baseUrl, publishableKey }) {
  const { status, body } = await requestJson(fetcher, `${normalizeBaseUrl(baseUrl)}/auth/v1/settings`, {
    method: 'GET',
    headers: { apikey: publishableKey },
  })
  if (status < 200 || status >= 300) {
    throw new HostedVerificationError('settings-failed', `GET /auth/v1/settings failed with HTTP ${status}${messageFrom(body)}`)
  }
  return parseAuthSettings(body)
}

async function signIn({ fetcher, baseUrl, publishableKey, account, label }) {
  const { status, body } = await requestJson(fetcher, `${baseUrl}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: publishableKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: account.email, password: account.password }),
  })
  const accessToken = body?.access_token
  const userId = body?.user?.id
  if (/email not confirmed/i.test(bodyMessage(body))) {
    throw new HostedVerificationError(
      'email-not-confirmed',
      `Sign-in for account ${label} was rejected because the email is unconfirmed. ` +
      'Disable email confirmation for the closed beta, or configure custom SMTP and a callback domain.',
    )
  }
  if (status < 200 || status >= 300 || typeof accessToken !== 'string' || typeof userId !== 'string') {
    throw new HostedVerificationError('auth-failed', `Sign-in failed for account ${label} (HTTP ${status})${messageFrom(body)}`)
  }
  return { accessToken, userId }
}

async function pushOperations({ fetcher, baseUrl, publishableKey, accessToken, deviceId, operations }) {
  const signed = []
  for (const operation of operations) {
    signed.push({ ...operation, deviceId, signature: await operationSignature(operation) })
  }
  const { status, body } = await requestJson(fetcher, `${baseUrl}/rest/v1/rpc/push_collection_operations`, {
    method: 'POST',
    headers: bearerHeaders(publishableKey, accessToken),
    body: JSON.stringify({ p_device_id: deviceId, p_operations: signed }),
  })
  if (status < 200 || status >= 300) {
    throw new HostedVerificationError('rpc-failed', `push_collection_operations failed with HTTP ${status}${messageFrom(body)}`)
  }
  return body !== null && typeof body === 'object' ? body : {}
}

async function pullOperations({ fetcher, baseUrl, publishableKey, accessToken }) {
  const { status, body } = await requestJson(fetcher, `${baseUrl}/rest/v1/rpc/pull_collection_operations`, {
    method: 'POST',
    headers: bearerHeaders(publishableKey, accessToken),
    body: JSON.stringify({ p_after_sequence: 0, p_page_limit: 500 }),
  })
  if (status < 200 || status >= 300) {
    throw new HostedVerificationError('rpc-failed', `pull_collection_operations failed with HTTP ${status}${messageFrom(body)}`)
  }
  return Array.isArray(body?.operations) ? body.operations : []
}

function resultFor(body, opId) {
  const results = Array.isArray(body.results) ? body.results : []
  return results.find((entry) => entry?.opId === opId) ?? null
}

function containsOperation(operations, opId, itemId) {
  return operations.some((entry) => entry?.opId === opId || entry?.itemId === itemId || entry?.item?.id === itemId)
}

function requireAccount(account, name) {
  if (account === null || typeof account !== 'object') {
    throw new HostedVerificationError('missing-config', `Missing configuration: ${name} is not set`)
  }
  return {
    email: requireString(account, 'email'),
    password: requireString(account, 'password'),
  }
}

export async function runHostedVerification(options) {
  const fetcher = options.fetcher ?? fetch
  const baseUrl = normalizeBaseUrl(requireString(options, 'baseUrl'))
  const publishableKey = requireString(options, 'publishableKey')
  const accountA = requireAccount(options.accountA, 'accountA')
  const accountB = requireAccount(options.accountB, 'accountB')
  const now = options.now ?? (() => Date.now())
  const nextUuid = options.randomUuid ?? (() => randomUUID())

  const checks = []
  let authSettings = null

  try {
    authSettings = await probeAuthSettings({ fetcher, baseUrl, publishableKey })
    checks.push({ name: 'settings', ok: true, detail: authSettings })
  } catch (error) {
    checks.push({ name: 'settings', ok: false, detail: describeError(error).message })
  }

  const sessionA = await signIn({ fetcher, baseUrl, publishableKey, account: accountA, label: 'A' })
  const sessionB = await signIn({ fetcher, baseUrl, publishableKey, account: accountB, label: 'B' })

  const distinct = sessionA.userId !== sessionB.userId
  checks.push({
    name: 'accounts-distinct',
    ok: distinct,
    detail: distinct ? 'accounts resolve to different user ids' : 'both accounts resolve to the same user id',
  })

  const deviceId = `verify-${nextUuid()}`
  const itemId = `item-${nextUuid()}`
  const createOpId = nextUuid()
  const deleteOpId = nextUuid()
  const addedAt = Math.trunc(Number(now()))
  const createOperation = {
    kind: 'create',
    opId: createOpId,
    item: {
      id: itemId,
      cardId: 'base1-4',
      quantity: 1,
      variant: 'NORMAL',
      condition: 'NEAR_MINT',
      pricePaid: null,
      addedAt,
    },
  }

  let createApplied = false
  let baseRevision = null
  let failure = null

  try {
    const push = await pushOperations({
      fetcher, baseUrl, publishableKey,
      accessToken: sessionA.accessToken,
      deviceId,
      operations: [createOperation],
    })
    const createResult = resultFor(push, createOpId)
    createApplied = push.status === 'ok' && createResult?.status === 'applied'
    if (createApplied && Number.isSafeInteger(createResult.revision)) baseRevision = createResult.revision
    checks.push({
      name: 'push-applied',
      ok: createApplied,
      detail: push.status !== 'ok'
        ? `push status ${String(push.status ?? 'missing')}`
        : `create status ${String(createResult?.status ?? 'missing')}`,
    })

    const ownOperations = await pullOperations({
      fetcher, baseUrl, publishableKey, accessToken: sessionA.accessToken,
    })
    const seenByOwner = containsOperation(ownOperations, createOpId, itemId)
    checks.push({
      name: 'pull-own',
      ok: seenByOwner,
      detail: seenByOwner ? 'account A pulled the created operation' : 'account A cannot see the created operation',
    })

    const otherOperations = await pullOperations({
      fetcher, baseUrl, publishableKey, accessToken: sessionB.accessToken,
    })
    const seenByOther = containsOperation(otherOperations, createOpId, itemId)
    checks.push({
      name: 'isolated',
      ok: !seenByOther,
      detail: seenByOther ? 'account B can see the account A operation' : 'account B cannot see the account A operation',
    })
  } catch (error) {
    failure = error
  } finally {
    if (createApplied && baseRevision !== null) {
      let cleanupAppliedRecorded = false
      try {
        const deleteOperation = { kind: 'delete', opId: deleteOpId, itemId, baseRevision }
        const push = await pushOperations({
          fetcher, baseUrl, publishableKey,
          accessToken: sessionA.accessToken,
          deviceId,
          operations: [deleteOperation],
        })
        const deleteResult = resultFor(push, deleteOpId)
        const deleteApplied = push.status === 'ok' && deleteResult?.status === 'applied'
        checks.push({
          name: 'cleanup-applied',
          ok: deleteApplied,
          detail: push.status !== 'ok'
            ? `cleanup push status ${String(push.status ?? 'missing')}`
            : `delete status ${String(deleteResult?.status ?? 'missing')}`,
        })
        cleanupAppliedRecorded = true
        if (deleteApplied) {
          const ownOperations = await pullOperations({
            fetcher, baseUrl, publishableKey, accessToken: sessionA.accessToken,
          })
          const pulled = ownOperations.some((entry) => entry?.opId === deleteOpId)
          checks.push({
            name: 'cleanup-pulled',
            ok: pulled,
            detail: pulled ? 'account A pulled the delete operation' : 'account A cannot see the delete operation',
          })
        } else {
          checks.push({ name: 'cleanup-pulled', ok: false, detail: 'delete was not applied' })
        }
      } catch (error) {
        if (!cleanupAppliedRecorded) {
          checks.push({ name: 'cleanup-applied', ok: false, detail: `cleanup failed: ${describeError(error).message}` })
        }
        checks.push({ name: 'cleanup-pulled', ok: false, detail: 'delete could not be confirmed' })
      }
    } else {
      checks.push({ name: 'cleanup-applied', ok: false, detail: 'create was not applied, nothing to delete' })
      checks.push({ name: 'cleanup-pulled', ok: false, detail: 'create was not applied, nothing to delete' })
    }
  }

  if (failure !== null) {
    failure.checks = checks
    throw failure
  }

  return {
    ok: CHECK_NAMES.every((name) => checks.find((check) => check.name === name)?.ok === true),
    checks,
    authSettings,
  }
}

const USAGE = `Verify hosted Supabase sync with two accounts.

Usage:
  node scripts/verify-hosted-sync.mjs [--help] [--settings-only]

Environment:
  SUPABASE_URL or TCG_SUPABASE_URL
  SUPABASE_PUBLISHABLE_KEY or TCG_SUPABASE_PUBLISHABLE_KEY
  HOSTED_EMAIL_A, HOSTED_PASSWORD_A
  HOSTED_EMAIL_B, HOSTED_PASSWORD_B

Options:
  --settings-only  Print the /auth/v1/settings probe and exit without signing in.
  --help           Print this help and exit 0.

Exit codes: 0 when every check passed, 1 on a failed check or a typed error.`

async function main(argv, env) {
  if (argv.includes('--help')) {
    console.log(USAGE)
    return 0
  }

  if (argv.includes('--settings-only')) {
    try {
      const baseUrl = normalizeBaseUrl(firstString(env, URL_VARIABLES))
      const publishableKey = firstString(env, KEY_VARIABLES)
      const authSettings = await probeAuthSettings({ fetcher: fetch, baseUrl, publishableKey })
      console.log(JSON.stringify({ ok: true, authSettings }, null, 2))
      return 0
    } catch (error) {
      console.error(JSON.stringify({ ok: false, error: describeError(error) }, null, 2))
      return 1
    }
  }

  try {
    const config = readConfig(env)
    const result = await runHostedVerification({ fetcher: fetch, ...config })
    console.log(JSON.stringify(result, null, 2))
    return result.ok ? 0 : 1
  } catch (error) {
    console.error(JSON.stringify({ ok: false, error: describeError(error) }, null, 2))
    return 1
  }
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2), process.env)
}
