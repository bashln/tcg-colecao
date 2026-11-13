import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseCatalogCard } from './tcgdex.ts'
import type { CardSummary, CardVariant } from '../domain/model.ts'

function card(variants: unknown[]): CardSummary {
  const parsed = parseCatalogCard({
    id: 'swsh3-3',
    name: 'Paras',
    localId: '3',
    set: { id: 'swsh3', name: 'Escuridão Incandescente', cardCount: { official: 189 } },
    variants_detailed: variants,
  })
  if (parsed === null) throw new Error('fixture card should parse')
  return parsed
}

function cardmarketQuote(parsed: CardSummary, variant: CardVariant) {
  return parsed.prices.find((quote) => quote.source === 'CARDMARKET' && quote.variant === variant) ?? null
}

const ALL_HOLO_FIELDS = {
  avg: 0.06,
  low: 0.02,
  trend: 0.07,
  avg1: 0.05,
  avg7: 0.05,
  avg30: 0.06,
  'avg-holo': 0.26,
  'low-holo': 0.04,
  'trend-holo': 0.26,
  'avg1-holo': 0.15,
  'avg7-holo': 0.36,
  'avg30-holo': 0.26,
}

test('holo variant reads the Cardmarket holo fields', () => {
  const parsed = card([{ type: 'holo', pricing: { cardmarket: ALL_HOLO_FIELDS } }])

  assert.deepEqual(cardmarketQuote(parsed, 'HOLOFOIL'), {
    source: 'CARDMARKET',
    variant: 'HOLOFOIL',
    currency: 'EUR',
    amount: 0.26,
    updatedAt: null,
  })
})

test('holo variant falls back to the base field when the holo field is null', () => {
  const parsed = card([{
    type: 'holo',
    pricing: { cardmarket: { ...ALL_HOLO_FIELDS, 'avg-holo': null } },
  }])

  assert.equal(cardmarketQuote(parsed, 'HOLOFOIL')?.amount, 0.06)
})

test('holo variant falls back to the base field when the holo field is zero', () => {
  const parsed = card([{
    type: 'holo',
    pricing: { cardmarket: { ...ALL_HOLO_FIELDS, 'avg-holo': 0 } },
  }])

  assert.equal(cardmarketQuote(parsed, 'HOLOFOIL')?.amount, 0.06)
})

test('normal variant ignores the Cardmarket holo fields', () => {
  const parsed = card([{ type: 'normal', pricing: { cardmarket: ALL_HOLO_FIELDS } }])

  assert.equal(cardmarketQuote(parsed, 'NORMAL')?.amount, 0.06)
})

test('reverse holo variant ignores the Cardmarket holo fields', () => {
  const parsed = card([{ type: 'reverse', pricing: { cardmarket: ALL_HOLO_FIELDS } }])

  assert.equal(cardmarketQuote(parsed, 'REVERSE_HOLOFOIL')?.amount, 0.06)
})

test('zero or negative Cardmarket prices produce no quote', () => {
  const parsed = card([{
    type: 'holo',
    pricing: { cardmarket: { avg: 0, 'avg-holo': -2 } },
  }])

  assert.equal(cardmarketQuote(parsed, 'HOLOFOIL'), null)
})

test('zero TCGplayer market price produces no quote', () => {
  const parsed = card([{
    type: 'normal',
    pricing: { tcgplayer: { normal: { productId: 1, marketPrice: 0 } } },
  }])

  assert.equal(parsed.prices.find((quote) => quote.source === 'TCGPLAYER'), undefined)
})
