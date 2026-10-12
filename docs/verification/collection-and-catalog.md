# Collection and catalog

The collection lets people find Pokémon cards, save a specific copy, edit it, and remove it without changing other copies.

## Sub-features

- `collection-empty` shows add actions when the collection has no cards.
- `catalog-search` finds cards by name or collector number.
- `collection-add` saves the chosen card and copy details.
- `collection-edit` changes one copy and survives reload.
- `collection-remove` requires confirmation and removes only the chosen copy.
- `missing-cards` shows missing cards only with complete set coverage.

## How to get to it (user POV)

- Open `Coleção` and choose `Buscar no catálogo` from the empty state.
- Search by card name or collector number and choose a result.
- Confirm the copy details and choose `Adicionar à coleção`.
- Choose `Editar carta` on a saved card to edit or remove it.
- Open `Faltam` to inspect missing cards when catalog coverage is complete.

## Driving it with Playwright

Preconditions:

- Run the Web verifier from `web/` with `CI=1` and Chromium available at `/usr/bin/chromium`.
- Start from the isolated empty collection in the Playwright page context.
- The test intercepts TCGdex and supplies Charizard `base1-4` from Base Set.

- **Open catalog.** Choose `Buscar no catálogo`. The catalog textbox `Nome ou número da carta` appears.
- **Search and choose.** Enter `Charizard`, choose `Buscar`, and select the Charizard result. The `Confirme a carta` heading appears.
- **Save a copy.** Increase quantity, enter a purchase price, and choose `Adicionar à coleção`. The Charizard heading and `Carta adicionada à coleção` status appear.
- **Edit a copy.** Reload the page, choose `Editar carta`, change quantity, and choose `Salvar alterações`. Reload again and confirm the new quantity remains visible.
- **Remove a copy.** Choose `Editar carta`, then `Remover da coleção`, then confirm with `Remover posse`. The app reports `Posse removida da coleção.` and shows the empty state.
- **Verify missing cards and mobile layout.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/collection.spec.ts`. The suite also checks mobile empty-state layout and offline editing while the app is already open.
- **Proof.** Run `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npm run test:e2e -- --trace on tests/collection.spec.ts --grep 'a saved card survives reload'`. Keep the resulting trace in `web/test-results/`.

## Gotchas

- The catalog response is mocked. The journey does not prove live TCGdex availability or CORS.
- Collection data stays in the browser unless the user explicitly links sync.
- The Web app shell does not support offline cold start or reload. The offline test edits an already-open page.
- Never use a real browser profile to create an empty-state fixture.
