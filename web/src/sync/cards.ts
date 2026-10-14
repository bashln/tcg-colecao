import type { CardSummary } from '../domain/model.ts'
import { parseCardId } from '../domain/model.ts'
import { getCachedCard } from '../collection/store.ts'
import { getCatalogCard } from '../catalog/tcgdex.ts'

/** Cache-first card resolution shared by the sync coordinator and the link flow. */
export async function resolveCardForSync(cardId: string): Promise<CardSummary> {
  const parsed = parseCardId(cardId)
  if (parsed === null) throw new Error(`Carta remota inválida: ${cardId}`)
  const cached = await getCachedCard(parsed)
  if (cached !== null) return cached
  const result = await getCatalogCard(parsed)
  if (result.kind === 'unavailable') throw new Error(result.message)
  return result.value
}
