import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import type { CollectionOperation, SyncItem } from './policy.ts'
import { createSupabaseTransport, syncSnapshotDigest, type SupabaseHttp } from './supabase-transport.ts'

/**
 * Cross-platform wire contract: the web transport must produce byte-identical canonical
 * JSON and SHA-256 signatures to the shared vectors in testdata/sync-wire-vectors.json,
 * which the Android transport verifies against the same file. A divergence here means the
 * server's op-ID/hash mismatch detection would silently treat the two platforms' retries
 * as different content.
 */
interface VectorCase {
  name: string
  operation: Record<string, unknown>
  canonical: string
  signature: string
  priceMajor?: number
}

const vectors = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../../testdata/sync-wire-vectors.json', import.meta.url)), 'utf8'),
) as { cases: VectorCase[]; digestCases: { name: string; items: SyncItem[]; digest: string }[]; itemWire: Record<string, unknown>; priceVector: { major: number; minor: number; rejected: { amount: number }[] } }

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value !== null && typeof value === 'object') {
    const source = value as Record<string, unknown>
    const sorted: Record<string, unknown> = {}
    for (const key of Object.keys(source).sort()) sorted[key] = canonical(source[key])
    return sorted
  }
  return value
}

/** Rebuilds a typed operation from the fixture, applying major-unit price input. */
function toOperation(vector: VectorCase): CollectionOperation {
  const raw = vector.operation
  const itemRaw = raw.item as Record<string, unknown> | undefined
  let item: SyncItem | undefined
  if (itemRaw !== undefined) {
    // The fixture stores pricePaid in minor units (wire form); the transport receives
    // major units and converts. So feed the major value when the case declares one.
    const priceRaw = vector.priceMajor !== undefined
      ? { amount: vector.priceMajor, currency: 'USD' }
      : itemRaw.pricePaid
    item = {
      id: itemRaw.id as SyncItem['id'],
      cardId: itemRaw.cardId as SyncItem['cardId'],
      quantity: itemRaw.quantity as number,
      variant: itemRaw.variant as SyncItem['variant'],
      condition: itemRaw.condition as SyncItem['condition'],
      pricePaid: priceRaw === null ? null : { amount: (priceRaw as { amount: number }).amount, currency: (priceRaw as { currency: 'USD' | 'EUR' }).currency },
      addedAt: itemRaw.addedAt as number,
    }
  }
  if (raw.kind === 'delete') {
    return {
      kind: 'delete',
      opId: raw.opId as string,
      deviceId: 'device-0001',
      itemId: raw.itemId as CollectionOperation extends never ? never : string,
      baseRevision: raw.baseRevision as number,
    } as CollectionOperation
  }
  if (raw.kind === 'create') return { kind: 'create', opId: raw.opId as string, deviceId: 'device-0001', item: item as SyncItem }
  return { kind: 'replace', opId: raw.opId as string, deviceId: 'device-0001', item: item as SyncItem, baseRevision: raw.baseRevision as number }
}

/** Captures the exact serialized body the real transport POSTs, so we assert production bytes. */
function capturingHttp(captured: { operations?: unknown[] }): SupabaseHttp {
  return {
    async rpc(_fn, body) {
      captured.operations = body.p_operations as unknown[]
      return { status: 'ok', results: [], highWater: 0 }
    },
    async user() {
      return { id: 'user-1', email: 'fixture@example.test' }
    },
  }
}

test('web canonical bytes match the shared cross-platform vectors', () => {
  for (const vector of vectors.cases) {
    assert.equal(
      JSON.stringify(canonical(JSON.parse(JSON.stringify(vector.operation)))),
      vector.canonical,
      `canonical mismatch for ${vector.name}`,
    )
  }
})

test('web transport emits the exact shared signature for each operation kind', async () => {
  for (const vector of vectors.cases) {
    const captured: { operations?: unknown[] } = {}
    const transport = createSupabaseTransport({ http: capturingHttp(captured) })
    await transport.push('account-a', [toOperation(vector)])
    const sent = (captured.operations ?? [])[0] as { signature?: string; kind?: string }
    assert.equal(sent?.kind, (vector.operation.kind as string), `kind mismatch for ${vector.name}`)
    assert.equal(
      sent?.signature,
      vector.signature,
      `signature mismatch for ${vector.name}: web must agree with Android`,
    )
  }
})

test('web serializes price to the shared minor-unit wire form', async () => {
  const vector = vectors.cases[0]!
  const captured: { operations?: unknown[] } = {}
  const transport = createSupabaseTransport({ http: capturingHttp(captured) })
  await transport.push('account-a', [toOperation(vector)])
  const sent = (captured.operations ?? [])[0] as { item?: { pricePaid?: { amount: number; currency: string } | null } }
  assert.equal(sent.item?.pricePaid?.amount, vectors.priceVector.minor, 'web must write integer minor units')
  assert.equal(sent.item?.pricePaid?.currency, 'USD')
  assert.deepEqual(vectors.priceVector.major, 12.34)
})

test('web snapshot digest matches shared canonical digest vectors', async () => {
  for (const vector of vectors.digestCases) {
    assert.equal(await syncSnapshotDigest(vector.items as SyncItem[]), vector.digest, vector.name)
  }
})
