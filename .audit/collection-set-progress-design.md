# Set progress design

## Goal

Show progress for expansions already present in the collection. A complete TCGdex catalog supplies the card identities for the numerator, denominator, and missing-card filter. Tapping a missing card opens the existing detail and add route.

## Data shape

`SetCatalog` contains one non-empty card list for exactly one `setId`. Its constructor rejects cards from another set and duplicate IDs. `SetCatalogResult` distinguishes a complete catalog from an unavailable catalog. The Room card table stays a partial cache and never stands in for a complete set.

The view model groups saved entries by card ID. Quantity, condition, and variant do not increase set completion. It retains fetched catalogs only for its lifetime. After process restart, an offline user sees their saved cards but needs a network retry to identify missing cards.

## Boundaries

`TcgdexCardSource` fetches `/cards?set=<id>`, requires a successful array response, filters exact `setId` matches, and removes duplicate IDs. The API's default filter can match partial values, so the client must keep the exact-set check. TCGdex documents pagination as opt-in, and a `set` query without pagination returns the unpaged list. See [TCGdex filtering and pagination](https://tcgdex.dev/fr/rest/filtering-sorting-pagination).

`RoomCardRepository` converts transport or empty-catalog failures to `Unavailable` and does not write catalog briefs to Room. This matters because `CardDao.cacheCard` replaces richer card rows and current prices.

`CollectionViewModel` owns section selection, the selected expansion state, and the ownership filter. `CollectionScreen` renders the states and routes missing cards through `AppNav` to the existing card detail flow.

## Alternatives

Persisting complete catalogs with a completion marker would enable offline missing-card lists after restart. It needs a schema migration and a non-destructive brief merge path. Defer that until offline checklists prove important.

Returning a plain `List<Card>` would let callers confuse partial cached cards with a full catalog. Keep completeness explicit in `SetCatalogResult`.

Using `Card.setTotal` to calculate missing numbers would not identify card IDs and could not open a missing card. Keep actual catalog identities.

## Review status

One independent architecture candidate recommended this shape. The configured runner panel and cross-judge could not run because the session thread limit rejected another agent. Treat the architecture comparison as provisional. The second design review remains open.
