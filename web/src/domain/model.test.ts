import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  collectionEstimate, configurationKey, missingFromCompleteCatalog, nextRevision, parseCardId, parseItemDraft, quoteFor,
} from './model.ts'
import type { CardSummary, CollectionEntry, SetCoverage } from './model.ts'

const id = parseCardId('swsh3-136')
if (id === null) throw new Error('Invalid test card ID')

const card: CardSummary = {
  id,
  name: 'Carta do catálogo',
  setId: 'swsh3',
  setName: 'Expansão',
  collectorNumber: '136',
  imageUrl: null,
  variants: ['NORMAL', 'HOLOFOIL'],
  prices: [
    { source: 'TCGPLAYER', variant: 'NORMAL', currency: 'USD', amount: 2.5, updatedAt: '2025-01-01' },
    { source: 'CARDMARKET', variant: 'NORMAL', currency: 'EUR', amount: 1.7, updatedAt: '2025-01-01' },
  ],
  setTotal: 200,
}

const owned: CollectionEntry = {
  card,
  item: {
    id: '12345678-1234-1234-1234-123456789012' as CollectionEntry['item']['id'],
    cardId: card.id,
    quantity: 2,
    variant: 'NORMAL',
    condition: 'NEAR_MINT',
    pricePaid: { amount: 9, currency: 'USD' },
    addedAt: 1,
    revision: 1,
    configurationKey: configurationKey(card.id, 'NORMAL', 'NEAR_MINT'),
  },
}

test('draft validation keeps purchase amount and currency separate from catalog quotes', () => {
  assert.deepEqual(parseItemDraft({ quantity: '2', variant: 'NORMAL', condition: 'NEAR_MINT', paidAmount: '12,50', paidCurrency: 'EUR' }), {
    kind: 'valid', quantity: 2, variant: 'NORMAL', condition: 'NEAR_MINT', pricePaid: { amount: 12.5, currency: 'EUR' },
  })
  assert.equal(parseItemDraft({ quantity: '0', variant: 'NORMAL', condition: 'NEAR_MINT', paidAmount: '', paidCurrency: 'USD' }).kind, 'invalid')
  assert.equal(parseItemDraft({ quantity: '1', variant: 'NORMAL', condition: 'NEAR_MINT', paidAmount: '-1', paidCurrency: 'USD' }).kind, 'invalid')
})

test('market totals use only matching source and currency, with visible quote coverage', () => {
  assert.deepEqual(collectionEstimate([owned], 'USD'), { amount: 5, pricedUnits: 2, missingUnits: 0 })
  assert.deepEqual(collectionEstimate([owned], 'EUR'), { amount: 3.4, pricedUnits: 2, missingUnits: 0 })
  assert.equal(quoteFor(card, 'HOLOFOIL', 'USD'), null)
})

test('incomplete set coverage cannot prove a card is missing', () => {
  const partial: SetCoverage = { kind: 'partial', setId: 'swsh3', cards: [card], expectedCount: 200, message: 'incomplete' }
  assert.equal(missingFromCompleteCatalog(partial, []), null)
  const complete: SetCoverage = { kind: 'complete', setId: 'swsh3', cards: [card], expectedCount: 1 }
  assert.deepEqual(missingFromCompleteCatalog(complete, [owned]), [])
  assert.deepEqual(missingFromCompleteCatalog(complete, []), [card])
})

test('revision advancement stops before creating an unsafe revision', () => {
  assert.equal(nextRevision(Number.MAX_SAFE_INTEGER - 1), Number.MAX_SAFE_INTEGER)
  assert.equal(nextRevision(Number.MAX_SAFE_INTEGER), null)
})
