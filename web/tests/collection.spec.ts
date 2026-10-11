import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const card = {
  id: 'base1-4', name: 'Charizard', localId: '4', image: null,
  set: { id: 'base1', name: 'Base Set', cardCount: { official: 102, total: 102 } },
  variants_detailed: [{ type: 'holo', pricing: { tcgplayer: { updated: '2025-01-01', holofoil: { marketPrice: 12.5 } } } }],
}

test('a saved card survives reload and an exact edit, then removal returns to the empty collection', async ({ page }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })

  await page.goto('/')
  await expect(page.getByText('Sua coleção está vazia')).toBeVisible()
  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await expect(page.getByRole('heading', { name: 'Confirme a carta' })).toBeVisible()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await page.getByRole('textbox', { name: 'Preço pago' }).fill('8,50')
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('Carta adicionada à coleção')).toBeVisible()
  await page.getByRole('button', { name: 'Perfil' }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Baixar cópia da coleção' }).click()
  const download = await downloadPromise
  const exportPath = await download.path()
  if (exportPath === null) throw new Error('The JSON export did not create a file')
  const exportData: unknown = JSON.parse(await readFile(exportPath, 'utf8'))
  expect(exportData).toMatchObject({
    format: 'tcg-colecao-web-1',
    entries: [{ item: { quantity: 2 }, card: { id: 'base1-4' } }],
  })
  await page.getByRole('button', { name: 'Coleção', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  const summaryRowsDoNotOverlap = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.summary-block')].map((row) => row.getBoundingClientRect())
    const first = rows[0]
    const second = rows[1]
    return first !== undefined && second !== undefined && first.bottom <= second.top
  })
  expect(summaryRowsDoNotOverlap).toBe(true)
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await expect(page.getByRole('heading', { name: 'Editar posse' })).toBeVisible()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await page.reload()
  await expect(page.getByText('3', { exact: true }).first()).toBeVisible()
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await page.getByRole('button', { name: 'Remover da coleção' }).click()
  await expect(page.getByRole('alertdialog')).toBeVisible()
  const cancel = page.getByRole('button', { name: 'Cancelar' })
  const remove = page.getByRole('button', { name: 'Remover posse' })
  await expect(cancel).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(remove).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(cancel).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(remove).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
  const trigger = page.getByRole('button', { name: 'Remover da coleção' })
  await expect(trigger).toBeFocused()
  await trigger.click()
  const removeButton = page.getByRole('button', { name: 'Remover posse' })
  await removeButton.evaluate((element) => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  await expect(page.getByText('Posse removida da coleção.')).toBeVisible()
  await expect(page.getByText(/mudou em outra aba/)).toHaveCount(0)
  await expect(page.getByText('Sua coleção está vazia')).toBeVisible()
})

test('the mobile empty state keeps both add actions clear of navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByText('Sua coleção está vazia')).toBeVisible()

  const layout = await page.evaluate(() => {
    const navigation = document.querySelector('.bottom-nav')?.getBoundingClientRect()
    const actions = [...document.querySelectorAll('.empty-collection button')]
      .map((button) => button.getBoundingClientRect())
    const filters = [...document.querySelectorAll('.filter-select')]
      .map((filter) => filter.getBoundingClientRect())
    return {
      actionsClear: navigation !== undefined && actions.every((action) => action.bottom <= navigation.top),
      filtersFit: filters.every((filter) => filter.left >= 0 && filter.right <= window.innerWidth),
    }
  })

  expect(layout.actionsClear).toBe(true)
  expect(layout.filtersFit).toBe(true)
  await page.locator('.bottom-nav').getByRole('button', { name: 'Escanear' }).click()
  await expect(page.getByRole('heading', { name: 'Escanear carta' })).toBeVisible()
  await page.getByRole('button', { name: 'Voltar' }).click()
  await expect(page.getByRole('heading', { name: 'Coleção', exact: true })).toBeVisible()
})

test('a saved collection stays usable offline and says account sync is not available', async ({ page, context }) => {
  await page.route('https://api.tcgdex.net/v2/pt/cards**', async (route) => {
    const url = new URL(route.request().url())
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(url.pathname.endsWith('/base1-4') ? card : [card]) })
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Buscar no catálogo' }).click()
  await page.getByRole('textbox', { name: 'Nome ou número da carta' }).fill('Charizard')
  await page.getByRole('button', { name: 'Buscar', exact: true }).click()
  await page.getByRole('button', { name: /Charizard Base Set/ }).click()
  await page.getByRole('button', { name: 'Adicionar à coleção' }).click()
  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByRole('status', { name: 'Estado da conexão' })).toContainText('Você está offline')
  await page.getByRole('button', { name: 'Editar carta' }).click()
  await page.getByRole('button', { name: 'Aumentar quantidade' }).click()
  await page.getByRole('button', { name: 'Salvar alterações' }).click()
  await expect(page.getByText('Alterações salvas.')).toBeVisible()
  await context.setOffline(false)
  await page.reload()

  await expect(page.getByRole('heading', { name: 'Charizard' })).toBeVisible()
  await expect(page.getByText('2', { exact: true }).first()).toBeVisible()
})
