import assert from 'node:assert/strict'
import test from 'node:test'
import { configurationKey, parseCardId } from '../domain/model.ts'
import { isCollectionBackupSizeAllowed, MAX_COLLECTION_BACKUP_BYTES, parseCollectionBackup } from './backup.ts'

test('backup export and import share the same inclusive size limit', () => {
  assert.equal(isCollectionBackupSizeAllowed(0), true)
  assert.equal(isCollectionBackupSizeAllowed(MAX_COLLECTION_BACKUP_BYTES), true)
  assert.equal(isCollectionBackupSizeAllowed(MAX_COLLECTION_BACKUP_BYTES + 1), false)
  assert.equal(isCollectionBackupSizeAllowed(-1), false)
})

test('backup import accepts a card from a set whose id contains a dot', () => {
  const parsed = parseCollectionBackup({
    format: 'tcg-colecao-web-1',
    exportedAt: '2026-11-13T00:00:00.000Z',
    entries: [
      {
        item: {
          id: 'item-dot-0001',
          cardId: 'sv08.5-001',
          quantity: 1,
          variant: 'NORMAL',
          condition: 'NEAR_MINT',
          pricePaid: null,
          addedAt: 1700000000,
          revision: 1,
          configurationKey: 'sv08.5-001\u001fNORMAL\u001fNEAR_MINT',
        },
        card: {
          id: 'sv08.5-001',
          name: 'Exeggcute',
          setId: 'sv08.5',
          setName: 'Prismatic Evolutions',
          collectorNumber: '001/131',
          imageUrl: null,
          variants: ['NORMAL'],
          prices: [],
          setTotal: 180,
        },
      },
    ],
  })
  assert.equal(parsed.kind, 'valid')
})

test('backup import requires HTTPS for card image URLs', () => {
  const cardId = parseCardId('swsh3-136')!
  const baseEntry = {
    item: {
      id: 'item-0001',
      cardId,
      quantity: 1,
      variant: 'NORMAL' as const,
      condition: 'NEAR_MINT' as const,
      pricePaid: null,
      addedAt: 1700000000,
      revision: 1,
      configurationKey: configurationKey(cardId, 'NORMAL', 'NEAR_MINT'),
    },
    card: {
      id: cardId,
      name: 'Furret',
      setId: 'swsh3',
      setName: 'Darkness Ablaze',
      collectorNumber: '136',
      imageUrl: 'https://assets.tcgdex.net/pt/swsh/swsh3/136/high.webp',
      variants: ['NORMAL' as const],
      prices: [],
      setTotal: 189,
    },
  }

  const validBackup = {
    format: 'tcg-colecao-web-1',
    exportedAt: '2026-10-10T12:00:00.000Z',
    entries: [baseEntry],
  }

  const parsedValid = parseCollectionBackup(validBackup)
  assert.equal(parsedValid.kind, 'valid')

  const parsedNull = parseCollectionBackup({
    ...validBackup,
    entries: [{ ...baseEntry, card: { ...baseEntry.card, imageUrl: null } }],
  })
  assert.equal(parsedNull.kind, 'valid')

  const parsedHttp = parseCollectionBackup({
    ...validBackup,
    entries: [{ ...baseEntry, card: { ...baseEntry.card, imageUrl: 'http://insecure.test/card.png' } }],
  })
  assert.equal(parsedHttp.kind, 'invalid')
})
