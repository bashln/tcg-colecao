import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { getCatalogCard, getSetCoverage, listSets, searchCatalog } from './tcgdex.ts'

const realFetch = globalThis.fetch

function stubFetch(handler: (url: string) => Response | Promise<Response>): string[] {
  const calls: string[] = []
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    calls.push(url)
    return handler(url)
  }) as typeof fetch
  return calls
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const card = {
  id: 'swsh3-136',
  name: 'Furret',
  localId: '136',
  set: { id: 'swsh3', name: 'Darkness Ablaze', cardCount: { official: 189 } },
  image: 'https://assets.tcgdex.net/pt/swsh/swsh3/136',
  variants_detailed: [
    {
      type: 'normal',
      pricing: {
        cardmarket: { avg: 1.7, updated: '2026-11-01' },
        tcgplayer: { normal: { marketPrice: 0.27 }, updated: '2026-11-01' },
      },
    },
  ],
}

afterEach(() => {
  globalThis.fetch = realFetch
})

test('a query shorter than two characters never reaches the catalog', async () => {
  const calls = stubFetch(() => json([]))

  const result = await searchCatalog(' f ')

  assert.equal(result.kind, 'unavailable')
  assert.equal(calls.length, 0)
})

test('an HTTP failure becomes an unavailable result with the connection message', async () => {
  stubFetch(() => json({ message: 'down' }, 503))

  const result = await searchCatalog('furret')

  assert.equal(result.kind, 'unavailable')
  assert.match((result as { message: string }).message, /Verifique a conexão/)
})

test('a response that is not a list is unavailable instead of an empty catalog', async () => {
  stubFetch(() => json({ cards: [] }))

  const result = await searchCatalog('furret')

  assert.equal(result.kind, 'unavailable')
  assert.match((result as { message: string }).message, /formato inesperado/)
})

test('an unreadable entry downgrades the search to partial and keeps the valid card', async () => {
  stubFetch(() => json([card, { id: 'sem-nome' }]))

  const result = await searchCatalog('furret')

  assert.equal(result.kind, 'partial')
  assert.deepEqual((result as { value: { id: string }[] }).value.map((entry) => entry.id), ['swsh3-136'])
  assert.match((result as { message: string }).message, /incompletos/)
})

test('a complete search keeps both currencies and the catalog image size', async () => {
  stubFetch(() => json([card]))

  const result = await searchCatalog('136')

  assert.equal(result.kind, 'complete')
  const [summary] = (result as { value: { prices: { source: string; currency: string; amount: number }[]; imageUrl: string | null }[] }).value
  assert.deepEqual(
    summary.prices.map((quote) => `${quote.source}:${quote.currency}:${quote.amount}`),
    ['CARDMARKET:EUR:1.7', 'TCGPLAYER:USD:0.27'],
  )
  assert.equal(summary.imageUrl, 'https://assets.tcgdex.net/pt/swsh/swsh3/136/high.webp')
})

test('a card whose payload has no name is unavailable, not a blank card', async () => {
  stubFetch(() => json({ id: 'swsh3-136' }))

  const result = await getCatalogCard('swsh3-136' as never)

  assert.equal(result.kind, 'unavailable')
  assert.match((result as { message: string }).message, /detalhes desta carta/)
})

test('a set list with one broken entry is partial and still lists the readable set', async () => {
  stubFetch(() => json([{ id: 'swsh3', name: 'Darkness Ablaze', cardCount: { total: 189 } }, { name: 'sem id' }]))

  const result = await listSets()

  assert.equal(result.kind, 'partial')
  assert.deepEqual((result as { value: unknown[] }).value, [{ id: 'swsh3', name: 'Darkness Ablaze', expectedCount: 189 }])
})

test('a set coverage with fewer cards than the official count stays partial', async () => {
  stubFetch(() => json([card]))

  const result = await getSetCoverage({ id: 'swsh3', name: 'Darkness Ablaze', expectedCount: 189 })

  assert.equal(result.kind, 'partial')
  assert.match((result as { message: string }).message, /incompleto/)
})

test('a set coverage that matches the official count is complete', async () => {
  const second = { ...card, id: 'swsh3-137', localId: '137' }
  stubFetch(() => json([card, second]))

  const result = await getSetCoverage({ id: 'swsh3', name: 'Darkness Ablaze', expectedCount: 2 })

  assert.equal(result.kind, 'complete')
  assert.deepEqual(
    (result as { cards: { id: string }[] }).cards.map((entry) => entry.id),
    ['swsh3-136', 'swsh3-137'],
  )
})

test('a payload carrying a card from another set cannot prove completeness', async () => {
  const second = { ...card, id: 'swsh3-137', localId: '137' }
  const foreign = { ...card, id: 'swsh4-1', set: { id: 'swsh4', name: 'Vivid Voltage', cardCount: { official: 185 } } }
  stubFetch(() => json([card, second, foreign]))

  const result = await getSetCoverage({ id: 'swsh3', name: 'Darkness Ablaze', expectedCount: 2 })

  assert.equal(result.kind, 'partial')
  assert.deepEqual(
    (result as { cards: { id: string }[] }).cards.map((entry) => entry.id),
    ['swsh3-136', 'swsh3-137'],
  )
})

test('a set coverage without an official count never claims completeness', async () => {
  stubFetch(() => json([card]))

  const result = await getSetCoverage({ id: 'swsh3', name: 'Darkness Ablaze', expectedCount: null })

  assert.equal(result.kind, 'partial')
})
