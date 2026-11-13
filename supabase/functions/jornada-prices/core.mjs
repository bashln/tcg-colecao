const CARD_ID = /^[a-zA-Z0-9][a-zA-Z0-9._-]{1,80}$/
const KNOWN_VARIANTS = ['NORMAL', 'HOLOFOIL', 'REVERSE_HOLOFOIL', 'FIRST_EDITION', 'PROMO']
const FINISH = {
  NORMAL: 'normal',
  HOLOFOIL: 'holo',
  REVERSE_HOLOFOIL: 'reverse_holo',
}
const LANGUAGE_RANK = { PT: 0, EN: 1 }
const CONDITION_RANK = {
  near_mint: 0,
  mint: 1,
  lightly_played: 2,
  moderately_played: 3,
  heavily_played: 4,
  damaged: 5,
}

export const MAX_CARDS = 100
export const DEFAULT_MAX_AGE_HOURS = 24
export const MISS_TTL_HOURS = 24 * 7

export class RequestError extends Error {
  constructor(field, message) {
    super(message)
    this.name = 'RequestError'
    this.field = field
  }
}

function text(value, field, max) {
  if (typeof value !== 'string' || value.trim() === '' || value.length > max) {
    throw new RequestError(field, `${field} precisa ser um texto não vazio de até ${max} caracteres.`)
  }
  return value.trim()
}

export function parseRequest(body) {
  if (body === null || typeof body !== 'object' || !Array.isArray(body.cards)) {
    throw new RequestError('cards', 'Envie um objeto com a lista cards.')
  }
  if (body.cards.length === 0 || body.cards.length > MAX_CARDS) {
    throw new RequestError('cards', `A lista cards precisa ter de 1 a ${MAX_CARDS} cartas.`)
  }
  const seen = new Set()
  const cards = []
  for (const [index, raw] of body.cards.entries()) {
    if (raw === null || typeof raw !== 'object') {
      throw new RequestError(`cards[${index}]`, 'Cada carta precisa ser um objeto.')
    }
    const cardId = text(raw.cardId, `cards[${index}].cardId`, 81)
    if (!CARD_ID.test(cardId)) {
      throw new RequestError(`cards[${index}].cardId`, `cardId inválido: ${cardId}`)
    }
    if (seen.has(cardId)) continue
    seen.add(cardId)
    const name = raw.name === undefined || raw.name === null ? '' : String(raw.name)
    if (name.length > 200) {
      throw new RequestError(`cards[${index}].name`, 'name precisa ter até 200 caracteres.')
    }
    const variants = Array.isArray(raw.variants)
      ? raw.variants.filter((value) => KNOWN_VARIANTS.includes(value))
      : []
    cards.push({
      cardId,
      setId: text(raw.setId, `cards[${index}].setId`, 120),
      collectorNumber: text(raw.collectorNumber, `cards[${index}].collectorNumber`, 40),
      name,
      variants,
    })
  }
  const requestedAge = body.maxAgeHours === undefined ? DEFAULT_MAX_AGE_HOURS : Number(body.maxAgeHours)
  if (!Number.isFinite(requestedAge) || requestedAge < 1 || requestedAge > 168) {
    throw new RequestError('maxAgeHours', 'maxAgeHours precisa ficar entre 1 e 168.')
  }
  return {
    cards,
    cardIds: cards.map((card) => card.cardId),
    maxAgeHours: requestedAge,
  }
}

export function printedNumber(collectorNumber) {
  return String(collectorNumber).split('/')[0].trim()
}

export function lookupCandidates(card) {
  const candidates = [{ strategy: 'code', params: { q: card.cardId } }]
  candidates.push({
    strategy: 'set-number',
    params: { q: printedNumber(card.collectorNumber), set: card.setId },
  })
  if (card.name) {
    candidates.push({ strategy: 'name-set', params: { q: card.name, set: card.setId } })
  }
  return candidates
}

function fold(value) {
  return String(value)
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '')
}

function numberFromCode(code) {
  const cut = String(code).lastIndexOf('-')
  return cut === -1 ? String(code) : String(code).slice(cut + 1)
}

function sameNumber(left, right) {
  const digits = (value) => value.replace(/^0+/, '')
  return fold(left) === fold(right) || digits(fold(left)) === digits(fold(right))
}

export function normaliseSearch(json) {
  const data = json !== null && typeof json === 'object' && Array.isArray(json.data) ? json.data : []
  return data.map((item) => ({
    id: item.id,
    code: String(item.code ?? ''),
    name: String(item.name ?? ''),
    setCode: item.setCode === undefined ? undefined : String(item.setCode),
    rarity: item.rarity ?? null,
    kind: item.kind ?? null,
    lowestPriceCents: cents(item.lowestPriceCents),
    offerLanguages: Array.isArray(item.offerLanguages) ? item.offerLanguages : [],
  }))
}

export function matchCard(card, results) {
  if (results.length === 0) return null
  const byCode = results.find((result) => fold(result.code) === fold(card.cardId))
  if (byCode) return { item: byCode, strategy: 'code' }

  const bySetNumber = results.find((result) => {
    const setMatches = result.setCode === undefined || fold(result.setCode) === fold(card.setId)
    return setMatches && sameNumber(numberFromCode(result.code), printedNumber(card.collectorNumber))
  })
  if (bySetNumber) return { item: bySetNumber, strategy: 'set-number' }

  const byName = results.filter((result) => fold(result.name) === fold(card.name))
  const exactSet = byName.filter(
    (result) => result.setCode !== undefined && fold(result.setCode) === fold(card.setId),
  )
  const pool = exactSet.length > 0 ? exactSet : byName
  if (pool.length === 1) return { item: pool[0], strategy: 'name-set' }
  return null
}

function cents(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null
}

export function normalisePrices(json, { cardId, publicId, fetchedAt }) {
  if (json === null || typeof json !== 'object') {
    throw new Error('Jornada returned no price body')
  }
  const price = json.market?.price
  const skus = Array.isArray(json.skus) ? json.skus : []
  const seen = new Set()
  const rows = []
  for (const sku of skus) {
    const key = `${sku.condition ?? ''}|${sku.language ?? ''}|${sku.finish ?? ''}`
    if (seen.has(key)) continue
    seen.add(key)
    rows.push({
      condition: String(sku.condition ?? ''),
      language: String(sku.language ?? ''),
      finish: String(sku.finish ?? ''),
      lowestCents: cents(sku.lowestPriceCents),
      highestCents: cents(sku.highestPriceCents),
      listings: Number.isSafeInteger(sku.listings) && sku.listings >= 0 ? sku.listings : 0,
    })
  }
  return {
    cardId,
    publicId,
    code: String(json.code ?? ''),
    marketCents: cents(price?.amount),
    marketKind: typeof price?.kind === 'string' ? price.kind : null,
    liquidity: json.market?.liquidity ?? null,
    fetchedAt,
    skus: rows,
  }
}

export function variantFor(appVariant) {
  return FINISH[appVariant] ?? null
}

export function selectSku(skus, appVariant) {
  const finish = variantFor(appVariant)
  if (finish === null) return null
  const candidates = skus.filter((sku) => sku.finish === finish && sku.lowestCents !== null)
  if (candidates.length === 0) return null
  return [...candidates].sort(
    (left, right) =>
      (LANGUAGE_RANK[left.language] ?? 2) - (LANGUAGE_RANK[right.language] ?? 2) ||
      (CONDITION_RANK[left.condition] ?? 6) - (CONDITION_RANK[right.condition] ?? 6) ||
      left.lowestCents - right.lowestCents,
  )[0]
}

export function byVariant(skus, variants) {
  const result = {}
  for (const variant of variants) {
    const sku = selectSku(skus, variant)
    if (sku) result[variant] = sku
  }
  return result
}

export function isFresh(instant, now, maxAgeHours) {
  if (typeof instant !== 'string') return false
  const parsed = Date.parse(instant)
  if (!Number.isFinite(parsed)) return false
  return now.getTime() - parsed <= maxAgeHours * 60 * 60 * 1000
}

export function newQuota(day) {
  return {
    day,
    marketCalls: 0,
    marketDayLimit: 100,
    marketResetAt: null,
    catalogCalls: 0,
    catalogDayLimit: 300,
    catalogResetAt: null,
  }
}

export function quotaRemaining(quota) {
  return {
    market: Math.max(0, quota.marketDayLimit - quota.marketCalls),
    catalog: Math.max(0, quota.catalogDayLimit - quota.catalogCalls),
  }
}

function quotaForDay(now, loaded) {
  const day = now.toISOString().slice(0, 10)
  if (!loaded || loaded.day !== day) return newQuota(day)
  return { ...newQuota(day), ...loaded, day }
}

function canUse(quota, family) {
  const calls = family === 'market' ? quota.marketCalls : quota.catalogCalls
  const limit = family === 'market' ? quota.marketDayLimit : quota.catalogDayLimit
  return calls < limit
}

function applyRate(quota, family, rate) {
  if (!rate) return
  const scope = typeof rate.scope === 'string' ? rate.scope : ''
  const exhausted = rate.code === 'QUOTA_EXCEEDED' || scope.endsWith(':day')
  if (!exhausted) return
  if (family === 'market') {
    quota.marketDayLimit = Math.max(quota.marketCalls, 1)
    quota.marketResetAt = rate.resetAt ?? null
  } else {
    quota.catalogDayLimit = Math.max(quota.catalogCalls, 1)
    quota.catalogResetAt = rate.resetAt ?? null
  }
}

function retryAfterOf(response) {
  const value = Number(response?.rate?.retryAfter)
  return Number.isFinite(value) && value > 0 ? value : null
}

function responseQuote(quote, status, variants) {
  return {
    status,
    publicId: quote.publicId,
    code: quote.code,
    marketCents: quote.marketCents,
    marketKind: quote.marketKind,
    liquidity: quote.liquidity,
    fetchedAt: quote.fetchedAt,
    skus: quote.skus,
    byVariant: byVariant(quote.skus, variants ?? []),
  }
}

async function resolveCard(card, jornada, quota) {
  for (const candidate of lookupCandidates(card)) {
    let response
    try {
      response = await jornada.search(candidate.params)
    } catch {
      return { failed: true }
    }
    quota.catalogCalls += 1
    if (response.status === 429) {
      applyRate(quota, 'catalog', response.rate)
      return { rateLimited: true, retryAfter: retryAfterOf(response) }
    }
    if (!response.ok) return { failed: true }
    const match = matchCard(card, normaliseSearch(response.json))
    if (match) return { item: match.item, strategy: match.strategy }
  }
  return { item: null }
}

function pending(cached, variants, status) {
  return cached ? responseQuote(cached, status, variants) : { status: 'pending' }
}

export async function handleRequest({ body, deps, now = new Date() }) {
  const request = parseRequest(body)
  const loaded = await deps.store.load(request.cardIds)
  const quota = quotaForDay(now, loaded.quota)
  const output = { quotes: {}, misses: [], quota: null, generatedAt: now.toISOString() }
  let retryAfter = null
  let catalogBlocked = false
  let marketBlocked = false

  for (const card of request.cards) {
    const cached = loaded.quotes.get(card.cardId)
    if (cached && isFresh(cached.fetchedAt, now, request.maxAgeHours)) {
      output.quotes[card.cardId] = responseQuote(cached, 'ok', card.variants)
      continue
    }

    const map = loaded.maps.get(card.cardId)
    if (map?.missedAt && isFresh(map.missedAt, now, MISS_TTL_HOURS)) {
      output.quotes[card.cardId] = cached
        ? responseQuote(cached, 'stale', card.variants)
        : { status: 'not_found' }
      output.misses.push(card.cardId)
      continue
    }

    let publicId = map?.publicId ?? null
    if (!publicId) {
      if (catalogBlocked || !canUse(quota, 'catalog')) {
        output.quotes[card.cardId] = pending(cached, card.variants, 'stale')
        continue
      }
      const resolved = await resolveCard(card, deps.jornada, quota)
      if (resolved.rateLimited) {
        catalogBlocked = true
        retryAfter = resolved.retryAfter ?? retryAfter
        output.quotes[card.cardId] = pending(cached, card.variants, 'stale')
        continue
      }
      if (resolved.failed) {
        output.quotes[card.cardId] = cached
          ? responseQuote(cached, 'stale', card.variants)
          : { status: 'error', message: 'Catálogo indisponível.' }
        continue
      }
      if (!resolved.item) {
        await deps.store.saveMiss(card.cardId, now.toISOString())
        output.quotes[card.cardId] = { status: 'not_found' }
        output.misses.push(card.cardId)
        continue
      }
      await deps.store.saveMap({
        cardId: card.cardId,
        publicId: resolved.item.id,
        code: resolved.item.code,
        setCode: resolved.item.setCode ?? null,
        matchedBy: resolved.strategy,
        matchedAt: now.toISOString(),
      })
      publicId = resolved.item.id
    }

    if (marketBlocked || !canUse(quota, 'market')) {
      output.quotes[card.cardId] = pending(cached, card.variants, 'stale')
      continue
    }

    let response
    try {
      response = await deps.jornada.prices(publicId)
    } catch {
      output.quotes[card.cardId] = cached
        ? responseQuote(cached, 'stale', card.variants)
        : { status: 'error', message: 'Mercado indisponível.' }
      continue
    }
    quota.marketCalls += 1
    if (response.status === 429) {
      applyRate(quota, 'market', response.rate)
      marketBlocked = true
      retryAfter = retryAfterOf(response) ?? retryAfter
      output.quotes[card.cardId] = pending(cached, card.variants, 'stale')
      continue
    }
    if (!response.ok) {
      output.quotes[card.cardId] = cached
        ? responseQuote(cached, 'stale', card.variants)
        : { status: 'error', message: 'Mercado indisponível.' }
      continue
    }

    applyRate(quota, 'market', response.rate)
    const quote = normalisePrices(response.json, {
      cardId: card.cardId,
      publicId,
      fetchedAt: now.toISOString(),
    })
    await deps.store.saveQuote(quote)
    loaded.quotes.set(card.cardId, quote)
    output.quotes[card.cardId] = responseQuote(quote, 'ok', card.variants)
  }

  await deps.store.saveQuota(quota)
  const remaining = quotaRemaining(quota)
  output.quota = {
    marketRemaining: remaining.market,
    catalogRemaining: remaining.catalog,
    ...(retryAfter === null ? {} : { retryAfterSeconds: retryAfter }),
  }
  return { status: 200, body: output }
}
