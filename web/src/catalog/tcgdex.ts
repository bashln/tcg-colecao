import { parseCardId } from '../domain/model.ts'
import type { CardSummary, CardVariant, MarketQuote, SetCoverage } from '../domain/model.ts'

const BASE_URL = 'https://api.tcgdex.net/v2/pt'

export interface SetOption {
  id: string
  name: string
  expectedCount: number | null
}

export type CatalogResult<T> =
  | { kind: 'complete'; value: T }
  | { kind: 'partial'; value: T; message: string }
  | { kind: 'unavailable'; message: string }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function string(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
}

function finiteNonnegative(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null
}

function variantFromType(value: unknown): CardVariant | null {
  switch (string(value)?.toLowerCase()) {
    case 'normal': return 'NORMAL'
    case 'holo':
    case 'holofoil': return 'HOLOFOIL'
    case 'reverse':
    case 'reverse-holofoil': return 'REVERSE_HOLOFOIL'
    case 'firstedition':
    case 'first-edition': return 'FIRST_EDITION'
    case 'promo': return 'PROMO'
    default: return null
  }
}

function parseVariantPrices(variant: CardVariant, pricing: unknown): MarketQuote[] {
  if (!isRecord(pricing)) return []
  const quotes: MarketQuote[] = []
  const cardmarket = pricing.cardmarket
  if (isRecord(cardmarket)) {
    const amount = finiteNonnegative(cardmarket.avg)
    if (amount !== null) {
      quotes.push({
        source: 'CARDMARKET', variant, currency: 'EUR', amount,
        updatedAt: string(cardmarket.updated),
      })
    }
  }
  const tcgplayer = pricing.tcgplayer
  if (isRecord(tcgplayer)) {
    const key = variant === 'NORMAL' ? 'normal' : variant === 'HOLOFOIL' ? 'holofoil' : variant === 'REVERSE_HOLOFOIL' ? 'reverse-holofoil' : null
    const price = key === null ? null : tcgplayer[key]
    if (isRecord(price)) {
      const amount = finiteNonnegative(price.marketPrice)
      if (amount !== null) {
        quotes.push({
          source: 'TCGPLAYER', variant, currency: 'USD', amount,
          updatedAt: string(tcgplayer.updated),
        })
      }
    }
  }
  return quotes
}

export function parseCatalogCard(raw: unknown): CardSummary | null {
  if (!isRecord(raw)) return null
  const id = parseCardId(raw.id)
  const name = string(raw.name)
  if (id === null || name === null) return null
  const set = isRecord(raw.set) ? raw.set : null
  const count = set !== null && isRecord(set.cardCount) ? set.cardCount : null
  const image = string(raw.image)
  const variantsRaw = Array.isArray(raw.variants_detailed) ? raw.variants_detailed : []
  const variants: CardVariant[] = []
  const prices: MarketQuote[] = []
  for (const candidate of variantsRaw) {
    if (!isRecord(candidate)) continue
    const variant = variantFromType(candidate.type)
    if (variant === null) continue
    if (!variants.includes(variant)) variants.push(variant)
    prices.push(...parseVariantPrices(variant, candidate.pricing))
  }
  return {
    id,
    name,
    setId: string(set?.id) ?? id.substring(0, id.lastIndexOf('-')),
    setName: string(set?.name) ?? string(set?.id) ?? 'Expansão não informada',
    collectorNumber: string(raw.localId) ?? '',
    imageUrl: image !== null && image.startsWith('https://') ? `${image}/high.webp` : null,
    variants,
    prices,
    setTotal: positiveInteger(count?.official),
  }
}

async function fetchJson(path: string, parameters?: URLSearchParams): Promise<unknown> {
  const url = `${BASE_URL}${path}${parameters === undefined ? '' : `?${parameters.toString()}`}`
  const response = await fetch(url, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(15_000) })
  if (!response.ok) throw new Error(`TCGdex HTTP ${response.status}`)
  return response.json() as Promise<unknown>
}

function errorMessage(): string {
  return 'Não foi possível consultar o catálogo. Verifique a conexão e tente novamente.'
}

function parseArray(raw: unknown): { cards: CardSummary[]; invalidCount: number } | null {
  if (!Array.isArray(raw)) return null
  const cards: CardSummary[] = []
  let invalidCount = 0
  const seen = new Set<string>()
  for (const candidate of raw) {
    const card = parseCatalogCard(candidate)
    if (card === null || seen.has(card.id)) {
      invalidCount += 1
    } else {
      cards.push(card)
      seen.add(card.id)
    }
  }
  return { cards, invalidCount }
}

export async function searchCatalog(query: string): Promise<CatalogResult<CardSummary[]>> {
  const trimmed = query.trim()
  if (trimmed.length < 2) return { kind: 'unavailable', message: 'Digite ao menos duas letras ou números para buscar.' }
  try {
    const isNumber = /^\d{1,4}$/.test(trimmed)
    const params = new URLSearchParams({ [isNumber ? 'localId' : 'name']: trimmed })
    const result = parseArray(await fetchJson('/cards', params))
    if (result === null) return { kind: 'unavailable', message: 'O catálogo respondeu em um formato inesperado. Tente novamente.' }
    if (result.invalidCount > 0) {
      return { kind: 'partial', value: result.cards, message: 'Alguns resultados vieram incompletos do catálogo.' }
    }
    return { kind: 'complete', value: result.cards }
  } catch {
    return { kind: 'unavailable', message: errorMessage() }
  }
}

export async function getCatalogCard(id: CardSummary['id']): Promise<CatalogResult<CardSummary>> {
  try {
    const card = parseCatalogCard(await fetchJson(`/cards/${encodeURIComponent(id)}`))
    return card === null
      ? { kind: 'unavailable', message: 'Os detalhes desta carta não estão disponíveis. Escolha outra carta.' }
      : { kind: 'complete', value: card }
  } catch {
    return { kind: 'unavailable', message: errorMessage() }
  }
}

export async function listSets(): Promise<CatalogResult<SetOption[]>> {
  try {
    const raw = await fetchJson('/sets')
    if (!Array.isArray(raw)) return { kind: 'unavailable', message: 'O catálogo de expansões está indisponível.' }
    const options: SetOption[] = []
    let invalidCount = 0
    for (const item of raw) {
      if (!isRecord(item)) { invalidCount += 1; continue }
      const id = string(item.id)
      const name = string(item.name)
      if (id === null || name === null) { invalidCount += 1; continue }
      const count = isRecord(item.cardCount) ? item.cardCount : null
      options.push({ id, name, expectedCount: positiveInteger(count?.total) })
    }
    options.sort((left, right) => left.name.localeCompare(right.name, 'pt-BR'))
    return invalidCount > 0
      ? { kind: 'partial', value: options, message: 'Algumas expansões não puderam ser lidas.' }
      : { kind: 'complete', value: options }
  } catch {
    return { kind: 'unavailable', message: errorMessage() }
  }
}

export async function getSetCoverage(set: SetOption): Promise<SetCoverage> {
  try {
    const result = parseArray(await fetchJson('/cards', new URLSearchParams({ set: set.id })))
    if (result === null) return { kind: 'unavailable', setId: set.id, message: 'A lista desta expansão está indisponível.' }
    const cards = result.cards.filter((card) => card.setId === set.id)
    if (set.expectedCount !== null && cards.length === set.expectedCount && result.invalidCount === 0 && cards.length === result.cards.length) {
      return { kind: 'complete', setId: set.id, cards, expectedCount: set.expectedCount }
    }
    return {
      kind: 'partial', setId: set.id, cards, expectedCount: set.expectedCount,
      message: 'O catálogo desta expansão está incompleto. Não é seguro listar cartas faltantes agora.',
    }
  } catch {
    return { kind: 'unavailable', setId: set.id, message: errorMessage() }
  }
}
