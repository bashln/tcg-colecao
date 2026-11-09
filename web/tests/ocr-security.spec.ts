import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'

// The language data is fetched separately; executable OCR code must come from this app.
test('OCR loads executable code from the application origin', async ({ page }) => {
  test.setTimeout(90_000)
  const externalExecutables: string[] = []
  const localExecutables: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.origin !== 'http://127.0.0.1:4175' && /\.(js|wasm)$/.test(url.pathname)) externalExecutables.push(url.href)
    if (url.origin === 'http://127.0.0.1:4175') localExecutables.push(url.pathname)
  })
  await page.route('**/por.traineddata.gz', (route) => route.fulfill({
    path: 'node_modules/@tesseract.js-data/por/4.0.0_best_int/por.traineddata.gz',
    headers: { 'access-control-allow-origin': '*' },
  }))
  await page.goto('/')
  const photo = [...await readFile('../app/src/androidTest/assets/cards/swsh3-42.png')]
  const result = await page.evaluate(async (bytes) => {
    const recognition = await import(`${location.origin}/src/scan/recognition.ts`)
    return recognition.recognizePhoto(new Blob([new Uint8Array(bytes)], { type: 'image/png' }))
  }, photo)
  expect(result.kind).toBe('recognized')
  expect(externalExecutables).toEqual([])
  expect(localExecutables.some((path) => path.endsWith('/worker.min.js'))).toBe(true)
  expect(localExecutables.some((path) => /\/tesseract-core-(?:(?:relaxed)?simd-)?lstm\.wasm\.js$/.test(path))).toBe(true)
})
