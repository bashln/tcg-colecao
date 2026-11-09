import type { CardSummary } from '../domain/model.ts'

export interface ParsedCardText {
  name: string | null
  collectorNumber: string | null
  setTotal: number | null
  rawText: string
}

export type RecognitionResult =
  | { kind: 'recognized'; parsed: ParsedCardText }
  | { kind: 'unavailable'; message: string }

const NUMBER_WITH_TOTAL = /(?:^|[^\d/])([0-9oO]{1,3})\s*[/|]\s*([0-9oO]{1,4})/i
const STAGE_OR_LABEL = /^(?:B[ÁA]SICO|STAGE\s*[12]?|EST[ÁA]GIO\s*[12]?|TREINADOR|ENERGIA|TRAINER|ENERGY|HP\s*\d+)/i

export function parseRecognizedText(rawText: string): ParsedCardText {
  const lines = rawText.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  const numberMatch = lines.map((line) => NUMBER_WITH_TOTAL.exec(line)).find((match) => match !== null)
  const collectorNumber = numberMatch?.[1]?.replace(/[oO]/g, '0') ?? null
  const setTotal = Number(numberMatch?.[2]?.replace(/[oO]/g, '0'))
  const name = lines.find((line) =>
    /[\p{L}]{2,}/u.test(line) &&
    !STAGE_OR_LABEL.test(line) &&
    !NUMBER_WITH_TOTAL.test(line) &&
    !/^\d+$/.test(line) &&
    !/^(?:fraqueza|resistência|recuo|regra)/i.test(line)) ?? null
  return {
    name,
    collectorNumber,
    setTotal: Number.isSafeInteger(setTotal) && setTotal > 0 ? setTotal : null,
    rawText,
  }
}

function normalizeNumber(value: string): string {
  return value.replace(/^0+/, '') || '0'
}

export function rankCandidates(parsed: ParsedCardText, cards: CardSummary[]): CardSummary[] {
  const score = (card: CardSummary): number => {
    let points = 0
    if (parsed.collectorNumber !== null && normalizeNumber(parsed.collectorNumber) === normalizeNumber(card.collectorNumber)) points += 4
    if (parsed.setTotal !== null && parsed.setTotal === card.setTotal) points += 6
    if (parsed.name !== null) {
      const recognized = parsed.name.toLocaleLowerCase('pt-BR')
      const catalog = card.name.toLocaleLowerCase('pt-BR')
      if (recognized === catalog) points += 3
      else if (recognized.includes(catalog) || catalog.includes(recognized)) points += 1
    }
    return points
  }
  return cards.toSorted((left, right) => score(right) - score(left))
}

export async function recognizePhoto(photo: Blob): Promise<RecognitionResult> {
  try {
    const [{ createWorker }, { default: workerPath }, features] = await Promise.all([
      import('tesseract.js'),
      import('tesseract.js/dist/worker.min.js?url'),
      import('wasm-feature-detect'),
    ])
    const corePath = (await features.relaxedSimd())
      ? (await import('tesseract.js-core/tesseract-core-relaxedsimd-lstm.wasm.js?url')).default
      : (await features.simd())
        ? (await import('tesseract.js-core/tesseract-core-simd-lstm.wasm.js?url')).default
        : (await import('tesseract.js-core/tesseract-core-lstm.wasm.js?url')).default
    const worker = await createWorker('por', undefined, {
      workerPath: new URL(workerPath, window.location.href).href,
      corePath: new URL(corePath, window.location.href).href,
    })
    try {
      const result = await worker.recognize(photo)
      const text = result.data.text.trim()
      if (text === '') return { kind: 'unavailable', message: 'Não foi possível ler texto nesta foto. Tente outra imagem ou busque manualmente.' }
      return { kind: 'recognized', parsed: parseRecognizedText(text) }
    } finally {
      await worker.terminate()
    }
  } catch {
    return { kind: 'unavailable', message: 'O reconhecimento local não está disponível agora. Busque a carta pelo nome ou número.' }
  }
}
