import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseRecognizedText, rankCandidates } from './recognition.ts'
import { parseCardId } from '../domain/model.ts'
import type { CardSummary } from '../domain/model.ts'

function card(id: string, name: string, collectorNumber: string, setTotal: number): CardSummary {
  const cardId = parseCardId(id)
  if (cardId === null) throw new Error('Invalid test card ID')
  return {
    id: cardId,
    name,
    setId: 'base1',
    setName: 'Base Set',
    collectorNumber,
    imageUrl: null,
    variants: ['NORMAL'],
    prices: [],
    setTotal,
  }
}

test('OCR parser extracts a Portuguese card name and corrects zero-like collector digits', () => {
  const parsed = parseRecognizedText('Charizard\nHP 120\nO4 / 1O2\nFraqueza x2')

  assert.equal(parsed.name, 'Charizard')
  assert.equal(parsed.collectorNumber, '04')
  assert.equal(parsed.setTotal, 102)
})

test('candidate ranking puts exact name, collector number, and set total first', () => {
  const exact = card('base1-4', 'Charizard', '4', 102)
  const wrongCard = card('base1-5', 'Charmeleon', '4', 101)
  const ranked = rankCandidates(parseRecognizedText('Charizard\n4/102'), [wrongCard, exact])

  assert.deepEqual(ranked.map((candidate) => candidate.id), [exact.id, wrongCard.id])
})
