export type CardId = string & { readonly __brand: 'CardId' }
export type ItemId = string & { readonly __brand: 'ItemId' }

export const VARIANTS = ['NORMAL', 'HOLOFOIL', 'REVERSE_HOLOFOIL', 'FIRST_EDITION', 'PROMO'] as const
export const CONDITIONS = ['NEAR_MINT', 'LIGHTLY_PLAYED', 'MODERATELY_PLAYED', 'HEAVILY_PLAYED', 'DAMAGED'] as const
export const CURRENCIES = ['USD', 'EUR'] as const

export type CardVariant = typeof VARIANTS[number]
export type Condition = typeof CONDITIONS[number]
export type Currency = typeof CURRENCIES[number]
export type PriceSource = 'TCGPLAYER' | 'CARDMARKET'

export interface Money {
  amount: number
  currency: Currency
}

export interface MarketQuote extends Money {
  source: PriceSource
  variant: CardVariant
  updatedAt: string | null
}

export interface CardSummary {
  id: CardId
  name: string
  setId: string
  setName: string
  collectorNumber: string
  imageUrl: string | null
  variants: CardVariant[]
  prices: MarketQuote[]
  setTotal: number | null
}

export interface CollectionItem {
  id: ItemId
  cardId: CardId
  quantity: number
  variant: CardVariant
  condition: Condition
  pricePaid: Money | null
  addedAt: number
  revision: number
  configurationKey: string
}

export interface CollectionEntry {
  item: CollectionItem
  card: CardSummary
}

export interface ItemDraft {
  quantity: string
  variant: CardVariant
  condition: Condition
  paidAmount: string
  paidCurrency: Currency
}

export type ParsedDraft =
  | { kind: 'valid'; quantity: number; variant: CardVariant; condition: Condition; pricePaid: Money | null }
  | { kind: 'invalid'; message: string }

export type SetCoverage =
  | { kind: 'complete'; setId: string; cards: CardSummary[]; expectedCount: number }
  | { kind: 'partial'; setId: string; cards: CardSummary[]; expectedCount: number | null; message: string }
  | { kind: 'unavailable'; setId: string; message: string }

export const variantLabels: Record<CardVariant, string> = {
  NORMAL: 'Normal',
  HOLOFOIL: 'Holofoil',
  REVERSE_HOLOFOIL: 'Reverse Holofoil',
  FIRST_EDITION: '1ª edição',
  PROMO: 'Promo',
}

export const conditionLabels: Record<Condition, string> = {
  NEAR_MINT: 'Near Mint',
  LIGHTLY_PLAYED: 'Lightly Played',
  MODERATELY_PLAYED: 'Moderately Played',
  HEAVILY_PLAYED: 'Heavily Played',
  DAMAGED: 'Damaged',
}

export function parseCardId(value: unknown): CardId | null {
  return typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9_-]{1,80}$/.test(value)
    ? value as CardId
    : null
}

export function parseItemId(value: unknown): ItemId | null {
  return typeof value === 'string' && /^[a-zA-Z0-9-]{8,80}$/.test(value)
    ? value as ItemId
    : null
}

export function newItemId(): ItemId {
  return crypto.randomUUID() as ItemId
}

export function configurationKey(cardId: CardId, variant: CardVariant, condition: Condition): string {
  return `${cardId}\u001f${variant}\u001f${condition}`
}

export function nextRevision(revision: number): number | null {
  if (!Number.isSafeInteger(revision) || revision < 1) return null
  const next = revision + 1
  return Number.isSafeInteger(next) ? next : null
}

export function parseItemDraft(draft: ItemDraft): ParsedDraft {
  const quantity = Number(draft.quantity)
  if (!/^\d+$/.test(draft.quantity) || !Number.isSafeInteger(quantity) || quantity < 1 || quantity > 100_000) {
    return { kind: 'invalid', message: 'Informe uma quantidade entre 1 e 100.000.' }
  }
  if (!VARIANTS.includes(draft.variant) || !CONDITIONS.includes(draft.condition) || !CURRENCIES.includes(draft.paidCurrency)) {
    return { kind: 'invalid', message: 'Escolha uma variante, condição e moeda válidas.' }
  }
  const enteredAmount = draft.paidAmount.trim()
  if (enteredAmount === '') {
    return { kind: 'valid', quantity, variant: draft.variant, condition: draft.condition, pricePaid: null }
  }
  if (!/^\d+(?:[.,]\d{1,2})?$/.test(enteredAmount)) {
    return { kind: 'invalid', message: 'Informe o preço pago com até duas casas decimais.' }
  }
  const amount = Number(enteredAmount.replace(',', '.'))
  if (!Number.isFinite(amount) || amount > 1_000_000_000) {
    return { kind: 'invalid', message: 'O preço pago está fora do limite aceito.' }
  }
  return {
    kind: 'valid',
    quantity,
    variant: draft.variant,
    condition: draft.condition,
    pricePaid: { amount, currency: draft.paidCurrency },
  }
}

export function draftFromItem(item: CollectionItem): ItemDraft {
  return {
    quantity: String(item.quantity),
    variant: item.variant,
    condition: item.condition,
    paidAmount: item.pricePaid === null ? '' : item.pricePaid.amount.toFixed(2).replace('.', ','),
    paidCurrency: item.pricePaid?.currency ?? 'USD',
  }
}

export function emptyDraft(card: CardSummary): ItemDraft {
  return {
    quantity: '1',
    variant: card.variants[0] ?? 'NORMAL',
    condition: 'NEAR_MINT',
    paidAmount: '',
    paidCurrency: 'USD',
  }
}

export function missingFromCompleteCatalog(coverage: SetCoverage, owned: CollectionEntry[]): CardSummary[] | null {
  if (coverage.kind !== 'complete') return null
  const ownedIds = new Set(owned.map((entry) => entry.item.cardId))
  return coverage.cards.filter((card) => !ownedIds.has(card.id))
}

export function quoteFor(card: CardSummary, variant: CardVariant, currency: Currency): MarketQuote | null {
  const source = currency === 'USD' ? 'TCGPLAYER' : 'CARDMARKET'
  return card.prices.find((quote) => quote.variant === variant && quote.source === source && quote.currency === currency) ?? null
}

export function collectionEstimate(entries: CollectionEntry[], currency: Currency): { amount: number | null; pricedUnits: number; missingUnits: number } {
  let amount = 0
  let pricedUnits = 0
  let missingUnits = 0
  for (const entry of entries) {
    const quote = quoteFor(entry.card, entry.item.variant, currency)
    if (quote === null) {
      missingUnits += entry.item.quantity
    } else {
      amount += quote.amount * entry.item.quantity
      pricedUnits += entry.item.quantity
    }
  }
  return { amount: pricedUnits === 0 ? null : amount, pricedUnits, missingUnits }
}

export function formatMoney(amount: number, currency: Currency): string {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency }).format(amount)
}
