# Collection ownership overview

## Goal

Let people see saved cards and missing card identities across expansions they have started without opening each expansion first. Keep saved-item editing and the existing expansion checklist unchanged.

An expansion is started while the collection has at least one saved card from it. Removing its last saved card removes it from this overview. This feature does not add a separate list of expansions the user plans to collect.

## User flow

The existing **Cartas** and **Expansões** tabs stay in place. Inside **Cartas**, **Tenho** remains selected by default. **Faltam** opens a global, searchable list grouped by expansion. Selecting a missing card opens the existing card-detail and add flow. Selecting an owned card continues to open the exact saved item by `CollectionItem.id`.

The owned view keeps the existing value summary and card grid. The missing view replaces the value summary with collection counts and catalog coverage. It shows distinct owned card IDs separately from saved copies. It never treats those counts as complementary parts of one total.

## Evidence and counting rules

- Only `SetCatalogResult.Complete` can provide missing card identities. `RoomCardRepository` does not write set-catalog briefs into Room.
- For each started expansion, a card is missing when its ID appears in the complete catalog and no saved collection entry has that ID.
- Quantity, condition, and variant do not change expansion completion. Several saved configurations of one card still count as one distinct owned card.
- `Card.setTotal` and rows from the partial Room cache cannot identify missing cards.
- A saved card absent from a complete catalog remains visible in **Tenho**. It does not count as missing and does not increase the catalog-owned count. Show a catalog mismatch notice for that expansion.
- Catalog evidence remains in memory for the current `CollectionViewModel`. After process restart, missing cards require a fresh catalog request.
- The collection stays in its loading state until `CollectionRepository.observe()` emits its first snapshot. The `stateIn` empty seed is not treated as a real empty collection.

## Ownership types

Keep the aggregate rules in the collection UI package. The core repository already exposes a typed complete-or-unavailable catalog boundary.

```kotlin
enum class CardsLens { OWNED, MISSING }

sealed interface CatalogLoad {
    data object Queued : CatalogLoad
    data object Loading : CatalogLoad
    data class Verified(val catalog: SetCatalog) : CatalogLoad
    data object Unavailable : CatalogLoad
}

data class StartedSet(
    val id: String,
    val name: String,
    val ownedCardIds: Set<String>,
)

data class MissingGroup(
    val set: StartedSet,
    val cards: List<Card>,
    val savedIdsOutsideCatalog: Set<String>,
)

sealed interface GlobalMissingState {
    data object NoStartedSets : GlobalMissingState
    data class NotChecked(val sets: List<StartedSet>) : GlobalMissingState
    data class Loading(
        val pending: List<StartedSet>,
        val failed: List<StartedSet>,
    ) : GlobalMissingState
    data class Unavailable(val failed: List<StartedSet>) : GlobalMissingState
    data class Partial(
        val verified: List<MissingGroup>,
        val pending: List<StartedSet>,
        val failed: List<StartedSet>,
    ) : GlobalMissingState
    data class Complete(val verified: List<MissingGroup>) : GlobalMissingState
}
```

The projection owns coverage partitions and card membership. Its constructors keep these rules true:

- `NoStartedSets` has no saved entries.
- `NotChecked` has started sets but no catalog request has begun.
- `Loading` has unresolved requests and no verified group.
- `Unavailable` has failed sets and no pending or verified set.
- `Partial` has a verified group and at least one pending or failed set.
- `Complete` has one verified group for each started set.
- A verified group may contain zero missing cards.

Counts derive from the groups. No mutable missing-card counter exists.

## Request lifecycle

Selecting **Faltam** requests catalogs for started expansions without verified catalogs. Reconcile after each collection emission so a newly started expansion is also checked. Limit active requests to two. Publish each verified expansion as its response arrives; do not wait for every request before showing results.

Store each catalog state and job by exact set ID. Reuse verified catalogs for both the global list and the existing expansion checklist. Mark a request queued before launching it, ignore duplicate requests for queued or loading IDs, and retry only failed IDs. Retry a single failed expansion or all failed expansions. Keep successful results visible while retrying.

Propagate `CancellationException`. Convert other request failures and mismatched set IDs to `Unavailable`. Never use a cached partial card list as a fallback. Requests stop when the ViewModel is cleared. Removing a saved card immediately recomputes ownership against any verified catalog.

Keep the current `CollectionRepository.observe()` subscription as the source of saved entries. Share it inside the ViewModel instead of creating another database observer for the overview. Keep query filtering out of the coverage calculation.

## Screen states

| State | User sees |
| --- | --- |
| Collection is loading | Existing collection loading state. Do not show an empty collection before Room emits its first snapshot. |
| No started expansions | Explain that adding a card starts an expansion. Do not show a zero missing count. Keep the existing scan action. |
| Catalogs not checked | Explain that missing cards have not been checked yet. Selecting **Faltam** starts the check. |
| Loading, no verified catalog | Show progress by expansion. Do not show a missing count. **Tenho** remains available. |
| Partial coverage | Show missing cards from verified catalogs and label the count as confirmed. Name pending or failed expansions. |
| No catalog verified | Explain that the app could not identify missing cards. Show distinct owned-card and saved-copy counts, offer retry, and do not show zero missing. |
| Complete coverage with missing cards | Show exact missing identities grouped by expansion and state the checked-expansion scope. |
| Complete coverage with no missing cards | Say there are no missing cards in the verified catalogs. Do not claim every variant or every Pokémon card is owned. |
| Search has no results | Name the query and say no matching missing card was found in verified expansions. Keep unresolved expansions visible. |

Search matches card names and expansion names. A matching expansion includes its verified missing cards. Search does not change coverage or summary counts. The result list uses one column so names, set labels, missing status, and retry actions remain readable at 360dp.

## Design choice

Three candidates compared different interaction shapes. The selected design keeps **Tenho/Faltam** inside **Cartas**. This preserves the existing top-level **Cartas/Expansões** navigation and leaves more width for the lens controls than a third top-level tab. It also keeps the existing saved-item grid separate from the catalog-backed missing-card list.

The independent judge selected this design. It scored candidate 1 at 30/30, candidate 2 at 27/30, and candidate 3 at 15/30 against discovery, correctness, preservation of existing flows, request lifecycle, small-screen UX, and interface depth. The candidates and full review live under `/tmp/opencode/arena-tcg-ownership/` for this session.

The design also adopts candidate 2's per-expansion retry, expansion-name search, and notice for saved IDs absent from a verified catalog. It adds the explicit `NotChecked` state called out by the judge. Candidate 2's third top-level tab was rejected because it compresses the existing tab row. Candidate 3's separate overview destination was rejected because it adds navigation state without improving access to missing cards.

The review used GPT-6 Astra while the implementation session uses GPT-6 Luna. Both models come from OpenAI, so the review did not provide vendor-level model diversity.

## Verification boundary

JVM tests prove ownership and coverage rules. Compose tests prove state labels, callbacks, search, and layout bounds. `:app:compileDebugAndroidTestKotlin` proves only that instrumented tests compile.

Do not run connected tests on the personal A32 while user data or a camera session may be active. UTP can uninstall the app. Inspect the updated interface on an idle A32 or an isolated emulator before claiming physical verification. Do not change Supabase, publish a release, deploy the web app, or delete collection data in this feature.
