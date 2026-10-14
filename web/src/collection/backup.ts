import { z } from 'zod'
import { CONDITIONS, CURRENCIES, VARIANTS, configurationKey, parseCardId, parseItemId } from '../domain/model.ts'

export const MAX_COLLECTION_BACKUP_BYTES = 50 * 1024 * 1024

export function isCollectionBackupSizeAllowed(size: number): boolean {
  return Number.isSafeInteger(size) && size >= 0 && size <= MAX_COLLECTION_BACKUP_BYTES
}

const cardIdSchema = z.string().transform((value, context) => {
  const cardId = parseCardId(value)
  if (cardId === null) {
    context.addIssue({ code: 'custom', message: 'Invalid card ID' })
    return z.NEVER
  }
  return cardId
})

const itemIdSchema = z.string().transform((value, context) => {
  const itemId = parseItemId(value)
  if (itemId === null) {
    context.addIssue({ code: 'custom', message: 'Invalid item ID' })
    return z.NEVER
  }
  return itemId
})

const moneySchema = z.object({
  amount: z.number().finite().nonnegative(),
  currency: z.enum(CURRENCIES),
}).strict()

const quoteSchema = moneySchema.extend({
  source: z.enum(['TCGPLAYER', 'CARDMARKET']),
  variant: z.enum(VARIANTS),
  updatedAt: z.string().nullable(),
}).strict()

const cardSchema = z.object({
  id: cardIdSchema,
  name: z.string(),
  setId: z.string(),
  setName: z.string(),
  collectorNumber: z.string(),
  imageUrl: z.string().url().nullable(),
  variants: z.array(z.enum(VARIANTS)),
  prices: z.array(quoteSchema),
  setTotal: z.number().int().nonnegative().nullable(),
}).strict()

const itemSchema = z.object({
  id: itemIdSchema,
  cardId: cardIdSchema,
  quantity: z.number().int().safe().min(1).max(100_000),
  variant: z.enum(VARIANTS),
  condition: z.enum(CONDITIONS),
  pricePaid: moneySchema.nullable(),
  addedAt: z.number().finite().nonnegative(),
  revision: z.number().int().safe().positive(),
  configurationKey: z.string(),
}).strict()

const backupSchema = z.object({
  format: z.literal('tcg-colecao-web-1'),
  exportedAt: z.iso.datetime(),
  entries: z.array(z.object({ item: itemSchema, card: cardSchema }).strict()).max(10_000),
}).strict()

const importMarkerSchema = z.object({
  itemId: itemIdSchema,
  signature: z.string().min(2).max(MAX_COLLECTION_BACKUP_BYTES),
}).strict()

export type CollectionBackup = z.infer<typeof backupSchema>
export type ImportMarker = z.infer<typeof importMarkerSchema>

export type BackupParseResult =
  | { kind: 'valid'; backup: CollectionBackup }
  | { kind: 'invalid'; message: string }

export function parseCollectionBackup(value: unknown): BackupParseResult {
  const parsed = backupSchema.safeParse(value)
  if (!parsed.success) return { kind: 'invalid', message: 'Este arquivo não parece uma cópia válida da coleção.' }

  const itemIds = new Set<string>()
  const configurationKeys = new Set<string>()
  const cards = new Map<string, string>()
  for (const { item, card } of parsed.data.entries) {
    const expectedKey = configurationKey(item.cardId, item.variant, item.condition)
    const serializedCard = JSON.stringify(card)
    if (item.cardId !== card.id || item.configurationKey !== expectedKey ||
      itemIds.has(item.id) || configurationKeys.has(item.configurationKey) ||
      (cards.has(card.id) && cards.get(card.id) !== serializedCard)) {
      return { kind: 'invalid', message: 'A cópia contém cartas ou posses inconsistentes.' }
    }
    itemIds.add(item.id)
    configurationKeys.add(item.configurationKey)
    cards.set(card.id, serializedCard)
  }
  return { kind: 'valid', backup: parsed.data }
}

export function parseImportMarkers(value: unknown): ImportMarker[] | null {
  const parsed = z.array(importMarkerSchema).safeParse(value)
  if (!parsed.success || new Set(parsed.data.map((marker) => marker.itemId)).size !== parsed.data.length) return null
  return parsed.data
}

export function importSignature(entry: CollectionBackup['entries'][number]): string {
  return JSON.stringify(entry.item)
}
