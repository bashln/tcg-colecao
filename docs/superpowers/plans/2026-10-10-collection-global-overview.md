# Global collection ownership overview implementation plan

> **For agentic workers:** Implement this plan task by task with TDD. Prefer an isolated worktree. If the current dirty checkout contains required upstream edits and moving them would risk user work, preserve the checkout and record the exception. Do not commit, publish, or alter remote services.

**Goal:** Add a global, accurate view of missing cards from expansions already represented in the saved collection.

**Architecture:** Keep existing **Cartas/Expansões** tabs and add a **Tenho/Faltam** lens inside **Cartas**. A pure projection derives ownership and coverage from the latest collection entries and complete catalogs. The ViewModel owns one catalog registry and request per set ID. Compose renders verified missing cards, coverage states, and retries without treating unknown catalogs as empty.

**Tech Stack:** Kotlin, coroutines and Flow, Jetpack Compose, Room repository, JUnit.

**Spec:** `docs/design/android/collection-ownership-overview.md`.

## Global Constraints

- "Only `SetCatalogResult.Complete` can provide missing card identities. `RoomCardRepository` does not write set-catalog briefs into Room."
- "`Card.setTotal` and rows from the partial Room cache cannot identify missing cards."
- "A saved card absent from a complete catalog remains visible in **Tenho**. It does not count as missing and does not increase the catalog-owned count. Show a catalog mismatch notice for that expansion."
- "Catalog evidence remains in memory for the current `CollectionViewModel`. After process restart, missing cards require a fresh catalog request."
- "Do not run connected tests on the personal A32 while user data or a camera session may be active. UTP can uninstall the app."
- "Do not change Supabase, publish a release, deploy the web app, or delete collection data in this feature."

## Review Focus

- Saved IDs absent from a complete catalog remain owned, do not become missing, and do not inflate catalog completion.
- Partial coverage with zero confirmed missing remains partial and never appears complete.
- Repeated retries do not duplicate queued or active requests and retry only failed IDs.
- Removing the last owned card removes that expansion from overview scope; cached catalogs for other sets do not affect it.
- Search does not change coverage totals, and unverified expansions remain visible when no card matches.
- Initial Room loading never appears as an empty collection or an empty missing-card overview.

---

### Task 1: Define ownership projection

**Files:**
- Create `app/src/main/kotlin/app/tcg/app/ui/collection/CollectionOwnership.kt`.
- Create `app/src/test/kotlin/app/tcg/app/ui/collection/CollectionOwnershipTest.kt`.

**Interfaces:**
- Consumes `CollectionEntry`, `SetCatalog`, and `SetCatalogResult.Complete`.
- Produces `CardsLens`, `StartedSet`, `CatalogLoad`, `MissingGroup`, `GlobalMissingState`, and pure `buildGlobalMissingState(entries, loads)`.

- [x] Write tests for no started sets, not checked, one complete set, exact missing IDs, unique ownership across variants/quantities, mismatch IDs, partial coverage with zero missing, and all-unavailable.
- [x] Run `./gradlew :app:testDebugUnitTest --tests app.tcg.app.ui.collection.CollectionOwnershipTest`.
  Expected: failures identify the missing projection behavior or types.
- [x] Implement the smallest typed projection that keeps unknown, verified, partial, and complete states distinct.
- [x] Rerun the focused projection tests.
  Expected: all projection tests pass.

### Task 2: Share catalog state and request lifecycle

**Files:**
- Modify `app/src/main/kotlin/app/tcg/app/ui/collection/CollectionViewModel.kt`.
- Modify `app/src/test/kotlin/app/tcg/app/ui/collection/CollectionViewModelTest.kt`.

**Interfaces:**
- Consumes the projection from Task 1 and existing `CardRepository.getSetCatalog`.
- Produces ViewModel state for `CardsLens`, coverage, retry by set, and retry of all failed sets. The existing selected-expansion checklist uses the same catalog registry.

- [x] Write tests for Faltam demand activation, per-set results, a two-request concurrency cap, retry, live ownership updates, new and removed started sets, selected-checklist reuse, and delayed first collection emission.
- [x] Run `./gradlew :app:testDebugUnitTest --tests app.tcg.app.ui.collection.CollectionViewModelTest`.
  Expected: failures identify missing demand, status, or retry behavior.
- [x] Implement one per-set state/job owner. Reconcile after collection emissions, request only after Faltam activation or explicit checklist selection, and validate requested set IDs.
- [x] Rerun the focused ViewModel tests and all `:app:testDebugUnitTest` tests.
  Expected: both commands pass.

### Task 3: Render the global missing view

**Files:**
- Modify `app/src/main/kotlin/app/tcg/app/ui/collection/CollectionScreen.kt`.
- Create `app/src/main/kotlin/app/tcg/app/ui/collection/GlobalMissingSection.kt` if the extracted body keeps the screen readable.
- Modify `app/src/main/res/values/strings.xml`.
- Modify `app/src/androidTest/kotlin/app/tcg/app/ui/collection/CollectionScreenTest.kt`.

**Interfaces:**
- Consumes the typed projection and ViewModel callbacks from Tasks 1 and 2.
- Produces the **Tenho/Faltam** lens, qualified global coverage copy, searchable grouped missing rows, retry actions, and the existing card-detail callback for missing cards.

- [x] Write Compose tests for direct Faltam access, empty/loading/unavailable states, visible unresolved expansions, missing-card navigation, scan action, and preserved owned-item navigation.
- [x] Check for isolated emulator. Only the personal A32 is attached; no connected tests were run because UTP can uninstall the app.
- [x] Implement accessible, one-column missing rows and scrollable state content for small screens.
- [x] Compile Compose tests. Runtime test and physical 360dp review remain blocked on a safe isolated/idle device.

### Task 4: Verify and reconcile

**Files:**
- Update `docs/plans/GOAL.md`.
- Update `progress.json` and `.audit/project-goal-decisions.tsv`.
- Update `docs/design/android/collection-ownership-overview.md` if implementation accepts any design deviation.

- [x] Run `./gradlew :core:test :app:testDebugUnitTest`, `./gradlew :app:compileDebugAndroidTestKotlin`, and `./gradlew :app:assembleDebug`.
  Expected: all tasks succeed.
- [x] Inspect feature files and run independent reviews. Fix actionable findings with tests, including a RED-to-GREEN regression for retrying an unavailable selected expansion and first-snapshot loading.
- [ ] Verify screenshot or Compose runtime on an idle A32 or isolated emulator. Do not claim physical verification if only compile/build evidence exists.
- [x] Record remaining device and runtime-test limits. Leave release, Supabase, and deployment untouched.

No commit or PR is part of this run. The repository contains extensive pre-existing user changes on `main`; preserve them and leave this feature in the working tree for review.
