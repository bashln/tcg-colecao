import assert from 'node:assert/strict'
import test from 'node:test'
import {
  byVariant,
  handleRequest,
  isFresh,
  lookupCandidates,
  matchCard,
  newQuota,
  normalisePrices,
  normaliseSearch,
  parseRequest,
  selectSku,
  variantFor,
} from './core.mjs'

const NOW = new Date('2026-11-13T12:00:00.000Z')
const CARD = {
  cardId: 'sv08.5-001',
  setId: 'sv08.5',
  collectorNumber: '001/131',
  name: 'Exeggcute',
  variants: ['NORMAL', 'REVERSE_HOLOFOIL'],
}
const SEARCH = {
  data: [
    {
      id: 103425,
      code: 'sv08.5-001',
      name: 'Exeggcute',
      setCode: 'sv08.5',
      lowestPriceCents: 500,
      offerLanguages: ['PT', 'EN'],
    },
  ],
}
const PRICES = {
  id: 103425,
  code: 'sv08.5-001',
  market: { price: { amount: 10, kind: 'ask' }, liquidity: { level: 'low', score: 7 } },
  skus: [
    { id: 'a', condition: 'near_mint', language: 'EN', finish: 'normal', lowestPriceCents: 10, highestPriceCents: 20, listings: 4 },
    { id: 'b', condition: 'near_mint', language: 'PT', finish: 'normal', lowestPriceCents: 14, highestPriceCents: 30, listings: 2 },
    { id: 'c', condition: 'near_mint', language: 'PT', finish: 'reverse_holo', lowestPriceCents: 40, highestPriceCents: 60, listings: 2 },
    { id: 'd', condition: 'near_mint', language: 'PT', finish: 'pokeball', lowestPriceCents: 100, highestPriceCents: 120, listings: 1 },
    { id: 'e', condition: 'lightly_played', language: 'PT', finish: 'normal', lowestPriceCents: 9, highestPriceCents: 12, listings: 1 },
  ],
}

function quoteFromPrices(fetchedAt = NOW.toISOString()) {
  return normalisePrices(PRICES, { cardId: CARD.cardId, publicId: 103425, fetchedAt })
}

function fakeDeps({ maps = [], quotes = [], quota = newQuota('2026-11-13'), search = () => ({ ok: true, status: 200, json: SEARCH }), prices = () => ({ ok: true, status: 200, json: PRICES }) } = {}) {
  const calls = { search: 0, prices: 0, savedMaps: [], misses: [], savedQuotes: [], savedQuota: null }
  return {
    calls,
    jornada: {
      async search(params) {
        calls.search += 1
        return search(params)
      },
      async prices(publicId) {
        calls.prices += 1
        return prices(publicId)
      },
    },
    store: {
      async load() {
        return { maps: new Map(maps.map((item) => [item.cardId, item])), quotes: new Map(quotes.map((item) => [item.cardId, item])), quota }
      },
      async saveMap(map) {
        calls.savedMaps.push(map)
      },
      async saveMiss(cardId, at) {
        calls.misses.push({ cardId, at })
      },
      async saveQuote(quote) {
        calls.savedQuotes.push(quote)
      },
      async saveQuota(next) {
        calls.savedQuota = next
      },
    },
  }
}

test('parseRequest accepts the documented shape and rejects overload', () => {
  const request = parseRequest({ cards: [CARD] })
  assert.equal(request.cards.length, 1)
  assert.equal(request.maxAgeHours, 24)

  assert.throws(() => parseRequest({ cards: [] }), /cards/)
  assert.throws(() => parseRequest({ cards: Array.from({ length: 101 }, () => CARD) }), /100/)
  assert.throws(() => parseRequest({ cards: [{ ...CARD, cardId: 'sv08/5-001' }] }), /cardId/)
  assert.throws(() => parseRequest({ cards: [{ ...CARD, setId: '' }] }), /setId/)
  assert.throws(() => parseRequest({ cards: [{ ...CARD, name: 'x'.repeat(201) }] }), /name/)
  assert.throws(() => parseRequest({ cards: [CARD], maxAgeHours: 0 }), /maxAgeHours/)
})

test('lookupCandidates tries code, set number and name in order', () => {
  assert.deepEqual(lookupCandidates(CARD).map((entry) => entry.strategy), ['code', 'set-number', 'name-set'])
})

test('matchCard prefers the exact code and falls back to set plus number', () => {
  const results = normaliseSearch(SEARCH)
  assert.equal(matchCard(CARD, results).strategy, 'code')
  const other = normaliseSearch({ data: [{ id: 1, code: 'sv08.5-1', name: 'Other', setCode: 'sv08.5' }] })
  assert.equal(matchCard(CARD, other).strategy, 'set-number')
})

test('normalisePrices keeps cents and null safe SKU fields', () => {
  const quote = quoteFromPrices()
  assert.equal(quote.marketCents, 10)
  assert.equal(quote.marketKind, 'ask')
  assert.equal(quote.skus.length, 5)
  assert.equal(quote.skus[3].finish, 'pokeball')
})

test('variantFor maps only the three supported finishes', () => {
  assert.equal(variantFor('NORMAL'), 'normal')
  assert.equal(variantFor('HOLOFOIL'), 'holo')
  assert.equal(variantFor('REVERSE_HOLOFOIL'), 'reverse_holo')
  assert.equal(variantFor('POKEBALL'), null)
  assert.equal(variantFor('FIRST_EDITION'), null)
})

test('selectSku prefers PT then near mint and ignores special finishes', () => {
  const quote = quoteFromPrices()
  const normal = selectSku(quote.skus, 'NORMAL')
  assert.equal(normal.language, 'PT')
  assert.equal(normal.condition, 'near_mint')
  assert.equal(normal.lowestCents, 14)
  assert.equal(selectSku(quote.skus, 'HOLOFOIL'), null)
})

test('byVariant returns only variants the card declares', () => {
  const quote = quoteFromPrices()
  const variants = byVariant(quote.skus, CARD.variants)
  assert.deepEqual(Object.keys(variants).sort(), ['NORMAL', 'REVERSE_HOLOFOIL'])
  assert.equal(variants.REVERSE_HOLOFOIL.lowestCents, 40)
})

test('isFresh compares against the requested age', () => {
  assert.equal(isFresh('2026-11-13T11:00:00.000Z', NOW, 24), true)
  assert.equal(isFresh('2026-11-11T11:00:00.000Z', NOW, 24), false)
  assert.equal(isFresh(null, NOW, 24), false)
})

test('handleRequest serves a fresh cached quote without an upstream call', async () => {
  const deps = fakeDeps({ quotes: [quoteFromPrices('2026-11-13T11:30:00.000Z')] })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.status, 200)
  assert.equal(result.body.quotes[CARD.cardId].status, 'ok')
  assert.equal(result.body.quotes[CARD.cardId].byVariant.NORMAL.lowestCents, 14)
  assert.equal(deps.calls.prices, 0)
  assert.equal(deps.calls.search, 0)
})

test('handleRequest refreshes a stale quote through the cached mapping', async () => {
  const deps = fakeDeps({
    maps: [{ cardId: CARD.cardId, publicId: 103425, code: 'sv08.5-001' }],
    quotes: [quoteFromPrices('2026-11-10T11:00:00.000Z')],
  })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'ok')
  assert.equal(deps.calls.prices, 1)
  assert.equal(deps.calls.savedQuotes.length, 1)
  assert.equal(deps.calls.savedQuotes[0].fetchedAt, NOW.toISOString())
})

test('handleRequest maps a new card and persists the map and quote', async () => {
  const deps = fakeDeps()
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'ok')
  assert.equal(deps.calls.search, 1)
  assert.equal(deps.calls.prices, 1)
  assert.deepEqual(deps.calls.savedMaps[0], {
    cardId: CARD.cardId,
    publicId: 103425,
    code: 'sv08.5-001',
    setCode: 'sv08.5',
    matchedBy: 'code',
    matchedAt: NOW.toISOString(),
  })
})

test('handleRequest records a miss after the three strategies', async () => {
  const deps = fakeDeps({ search: () => ({ ok: true, status: 200, json: { data: [] } }) })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'not_found')
  assert.deepEqual(result.body.misses, [CARD.cardId])
  assert.equal(deps.calls.search, 3)
  assert.equal(deps.calls.misses.length, 1)
  assert.equal(deps.calls.prices, 0)
})

test('handleRequest returns stale when the market quota is spent', async () => {
  const quota = { ...newQuota('2026-11-13'), marketCalls: 100 }
  const deps = fakeDeps({
    maps: [{ cardId: CARD.cardId, publicId: 103425, code: 'sv08.5-001' }],
    quotes: [quoteFromPrices('2026-11-10T11:00:00.000Z')],
    quota,
  })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'stale')
  assert.equal(deps.calls.prices, 0)
  assert.equal(result.body.quota.marketRemaining, 0)
})

test('handleRequest returns stale with retryAfter on an upstream 429', async () => {
  const deps = fakeDeps({
    maps: [{ cardId: CARD.cardId, publicId: 103425, code: 'sv08.5-001' }],
    quotes: [quoteFromPrices('2026-11-10T11:00:00.000Z')],
    prices: () => ({
      ok: false,
      status: 429,
      json: { code: 'RATE_LIMITED' },
      rate: { scope: 'market:minute', retryAfter: 42, code: 'RATE_LIMITED' },
    }),
  })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'stale')
  assert.equal(result.body.quota.retryAfterSeconds, 42)
})

test('handleRequest keeps going after an upstream 401 and never exposes a key', async () => {
  const deps = fakeDeps({
    maps: [{ cardId: CARD.cardId, publicId: 103425, code: 'sv08.5-001' }],
    prices: () => ({ ok: false, status: 401, json: { message: 'bad key' } }),
  })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.status, 200)
  assert.equal(result.body.quotes[CARD.cardId].status, 'error')
  assert.equal(JSON.stringify(result.body).includes('jg_live_'), false)
})

test('handleRequest degrades a network failure to the cached quote', async () => {
  const deps = fakeDeps({
    maps: [{ cardId: CARD.cardId, publicId: 103425, code: 'sv08.5-001' }],
    quotes: [quoteFromPrices('2026-11-10T11:00:00.000Z')],
    prices: () => {
      throw new Error('offline')
    },
  })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'stale')
})

test('a fresh miss keeps the last cached quote visible', async () => {
  const deps = fakeDeps({
    maps: [{ cardId: CARD.cardId, publicId: null, missedAt: NOW.toISOString() }],
    quotes: [quoteFromPrices('2026-11-10T11:00:00.000Z')],
  })
  const result = await handleRequest({ body: { cards: [CARD] }, deps, now: NOW })
  assert.equal(result.body.quotes[CARD.cardId].status, 'stale')
  assert.deepEqual(result.body.misses, [CARD.cardId])
})

test('normalisePrices removes repeated SKU keys', () => {
  const quote = normalisePrices({ ...PRICES, skus: [...PRICES.skus, ...PRICES.skus] }, {
    cardId: CARD.cardId,
    publicId: 103425,
    fetchedAt: NOW.toISOString(),
  })
  assert.equal(quote.skus.length, PRICES.skus.length)
})

test('handleRequest stops calling the market after the first 429', async () => {
  const other = { ...CARD, cardId: 'sv08.5-002', name: 'Exeggutor' }
  const deps = fakeDeps({
    maps: [
      { cardId: CARD.cardId, publicId: 1, code: 'sv08.5-001' },
      { cardId: other.cardId, publicId: 2, code: 'sv08.5-002' },
    ],
    quotes: [
      quoteFromPrices('2026-11-10T11:00:00.000Z'),
      { ...quoteFromPrices('2026-11-10T11:00:00.000Z'), cardId: other.cardId },
    ],
    prices: () => ({
      ok: false,
      status: 429,
      json: { code: 'RATE_LIMITED' },
      rate: { scope: 'market:minute', retryAfter: 30, code: 'RATE_LIMITED' },
    }),
  })
  const result = await handleRequest({ body: { cards: [CARD, other] }, deps, now: NOW })
  assert.equal(deps.calls.prices, 1)
  assert.equal(result.body.quotes[other.cardId].status, 'stale')
})

test('handleRequest stops before the minute limit on a spent window', async () => {
  const other = { ...CARD, cardId: 'sv08.5-002', name: 'Exeggutor' }
  const deps = fakeDeps({
    maps: [
      { cardId: CARD.cardId, publicId: 1, code: 'sv08.5-001' },
      { cardId: other.cardId, publicId: 2, code: 'sv08.5-002' },
    ],
    quotes: [
      quoteFromPrices('2026-11-10T11:00:00.000Z'),
      { ...quoteFromPrices('2026-11-10T11:00:00.000Z'), cardId: other.cardId },
    ],
    prices: () => ({
      ok: true,
      status: 200,
      json: PRICES,
      rate: { scope: 'market:minute', remaining: 0, resetAt: '2026-11-13T12:01:00.000Z' },
    }),
  })
  const result = await handleRequest({ body: { cards: [CARD, other] }, deps, now: NOW })
  assert.equal(deps.calls.prices, 1)
  assert.equal(result.body.quotes[other.cardId].status, 'stale')
  assert.equal(result.body.quota.retryAfterSeconds, 60)
})
