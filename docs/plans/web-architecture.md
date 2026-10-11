# Web collection architecture

## Decision

Build the browser app as a React, TypeScript, and Vite package under `web/`. Keep Android's Compose app and Kotlin/JVM `:core` in place. Port the scan parser and ranker to TypeScript only with fixtures that also run against the Android implementation. Do not make the Android build a prerequisite for a web UI change.

The app owns its collection in IndexedDB until a separate, reviewed Supabase phase defines account migration, authentication, row-level security, conflict handling, and recovery. A browser collection is local to its origin and does not sync with Android. The interface must say this plainly. Keep photos in the browser. Send recognized text, never the photo, to the card catalog.

The accepted screenshots in `docs/design/web/concepts/` define the collection, empty, mobile, scan, confirmation, and edit screens. Use catalog card images in the app. Do not ship the concept's made-up card names, illustrations, collection counts, or prices.

## Why this shape

The web app needs browser camera access, local OCR, IndexedDB, and a responsive interface. The Android app already has a tested card and collection domain, but `:core` uses Kotlin/JVM, OkHttp, and `java.time.LocalDate`. Converting that module to Kotlin Multiplatform would require coordinated changes to Android repositories, date handling, Gradle targets, and tests before the web could render its first screen.

The arena compared three candidates. Candidate 1, React with a private collection module, scored 79/100 and is the base. Candidate 2, Kotlin Multiplatform with a Kotlin/JS app, scored 81.5/100. Its shared-domain value is real, but it makes a broad Android migration a prerequisite. Candidate 3, custom elements around one feature facade, scored 41.5/100. Its sketch lost the existing uniqueness rule and could not represent incomplete expansion catalogs. The independent judge recommended Candidate 1 with Candidate 2's Android-domain fidelity and responsive accessibility requirements. The parent review reached the same base choice.

## Domain contract

Mirror the current Android values. `Currency` is `USD` or `EUR`. `CardVariant` is `NORMAL`, `HOLOFOIL`, `REVERSE_HOLOFOIL`, `FIRST_EDITION`, or `PROMO`. `Condition` is `NEAR_MINT`, `LIGHTLY_PLAYED`, `MODERATELY_PLAYED`, `HEAVILY_PLAYED`, or `DAMAGED`.

`Card.id` identifies a catalog card. `CollectionItem.id` identifies one saved configuration. Preserve that item ID when a person edits it. The collection allows one row for each `(cardId, variant, condition)` configuration. Adding the same configuration increases its quantity. Editing one row into a configuration owned by another row returns a conflict and does not merge either row.

Adding quantity preserves the existing `pricePaid` value, matching `RoomCollectionRepository.add` and `CollectionDao.upsertSumming`. This value is not recalculated during aggregation. The editor can replace or clear it. Keep purchase value separate from market quotes.

Map catalog cards to a private `CardSummary` with ID, name, expansion, collector number, image URL, variants, and source prices. A `MarketQuote` includes source, variant, currency, amount, and catalog update time. Do not combine USD and EUR totals. Show missing or stale quotes as such rather than as zero.

Catalog operations return typed completeness. A search can be complete, partial, or unavailable. A `Missing` query requires one expansion and a complete card list for that expansion. An unavailable or partial list cannot prove that a card is missing. Preserve locally owned cards when the catalog is offline.

Keep form values as strings until a module boundary validates them. Collection writes run in one IndexedDB transaction. An edit includes the revision shown when the editor opened. A stale revision, duplicate configuration, invalid input, or storage failure returns an explicit outcome and keeps the draft on screen.

## Module ownership

```text
web/src/domain/       branded IDs, enum values, validation, collection projections
web/src/collection/   IndexedDB schema, migrations, atomic add/edit/remove, observation
web/src/catalog/      TCGdex requests, response parsing, cache, quote provenance
web/src/scan/         camera/file selection, local OCR worker, parser and ranker
web/src/screens/      collection, missing, scanner confirmation, exact-item editor
web/src/ui/           design tokens, navigation, cards, accessible controls
web/src/app/          routes and dependency composition
```

Screens call domain operations. They do not import IndexedDB records, OCR worker messages, or TCGdex response types. The collection module owns the IndexedDB representation and all writes. Each tab owns its unsaved form and camera session. Database transactions serialize changes to shared collection rows; cross-tab messages only tell other tabs to reload.

The scan flow accepts a camera capture or selected image. A local OCR worker returns text and line geometry. The parser and ranker suggest catalog matches. The person chooses a card and reviews variant, condition, quantity, and paid price before the app writes anything. OCR failure, camera denial, unsupported browser, unavailable OCR assets, and catalog failure all leave manual catalog search available.

Use deterministic fixtures to test catalog failure and OCR recovery. Port the Android parser and ranker with the same golden examples, including OCR line geometry. This checks behavior without introducing Kotlin/JS interop in the first web slice.

## Interface and layout contract

The desktop collection uses a left navigation rail, a three-column grid, collection search, `Tenho` and `Faltam`, expansion, variant and condition filters, a totals summary, and edit actions. The mobile layout uses a vertical list and fixed bottom navigation at 390 CSS pixels. Keep safe-area padding below the bottom bar. Preserve the concept labels `Início`, `Coleção`, `Escanear`, and `Perfil`.

Use controls at least 44 pixels high and primary actions at least 52 pixels high. Support keyboard navigation, visible focus, screen-reader labels and announcements for asynchronous loading, saved changes, conflicts, and errors. Verify text contrast against WCAG AA. Preserve the concept's charcoal palette, teal selection, spacing, card anatomy, and hierarchy. Use actual catalog imagery.

The empty collection offers `Escanear carta` and `Buscar no catálogo`. Loading, empty, partial, failed, and success states have distinct copy and actions. A catalog outage does not hide local collection data. A permission denial does not strand the person in the camera screen.

## Local data and later sync

IndexedDB is the only owner of browser collection state in this phase. The UI identifies the current browser as the storage location and does not promise permanent retention. Profile supports JSON export and validated import. Browser storage, catalog cache, and OCR asset cache have separate retention behavior.

Do not add a sync scheduler, account screen, Supabase client, or remote schema in this phase. Future sync must preserve stable item IDs, edits, and deletions. The future phase must define Auth, RLS, conflict policy, idempotent operations, and recovery before it changes remote state.

## Verification gates

Run domain tests for enum mapping, currency parsing, add aggregation, exact-item edits, duplicate conflicts, and incomplete missing-card results. Run browser tests for reload persistence, quota/storage errors, keyboard use, removal confirmation, stale edits, concurrent tabs, image-file fallback, OCR failure, catalog partial/offline states, and backup import without data loss.

Use the locked Playwright package for Chromium and WebKit checks when those browsers are installed. Check the accepted desktop screenshot at 1440 pixels and mobile screenshot at 390 pixels. Compare the rendered app and concepts with `view_image`, then keep a fidelity ledger of any mismatch and correction. Playwright WebKit does not prove camera or OCR behavior on an iPhone. Verify those on a real iPhone before claiming them complete.

The TCGdex endpoint and browser CORS are not verified from this environment. An open TCGdex report from July 2026 describes refused connections, while local shell DNS is currently unavailable. Do not treat that report or the local network error as proof of current API status. Keep catalog access behind its adapter, test the real endpoint when network access is available, and show a retryable catalog error when it is not.

## Arena record

| Candidate | Shape | Independent score | Decision |
|---|---|---:|---|
| 1 | React, TypeScript, Vite, private collection module | 79/100 | Base. Graft exact Android domain rules and responsive accessibility. |
| 2 | Kotlin Multiplatform domain and Kotlin/JS app | 81.5/100 | Defer broad migration. Reconsider if shared domain rules begin to drift. |
| 3 | Browser-native custom elements and feature facade | 41.5/100 | Reject as written. Duplicate policy and partial catalog types contradict the existing domain. |

## First implementation status

The first web slice now implements collection search, exact-item editing, quantity changes, confirmed removal, JSON export and import, catalog search, scan confirmation, camera/file input, and local OCR fallback. `web/tests/collection.spec.ts` verifies manual search, save, reload persistence, editing, removal, keyboard focus in the removal dialog, the mobile empty state, scan return navigation, and the stacked mobile summary. `web/tests/backup-import.spec.ts` verifies schema rejection, idempotent replay, quantity merge, v1 database migration, recovery after local removal, and revision overflow refusal. Parser and candidate-ranking tests live in `web/src/scan/recognition.test.ts`. Browser tests intercept the catalog. The visual comparison and remaining mismatches are recorded in `docs/design/web/fidelity-ledger.md`.

Visual inspection found and fixed two mobile issues. The empty-state search action sat behind the fixed navigation, and the two collection summary values overlapped at 390 pixels. The filters now fit in a three-column layout, both empty-state actions clear the navigation, and the summary stacks into two rows.

The local production build, twelve unit tests, and seven Chromium browser tests pass. Browser tests use a mocked TCGdex response. A live request to `https://api.tcgdex.net/v2/pt/cards/base1-4` returned HTTP 503. WebKit, real camera permissions, OCR with physical card photos, and iPhone Safari remain unverified. Tesseract.js downloads its Portuguese language data on first use. The browser app still stores data only in IndexedDB and does not sync with Android. The separate sync protocol design is in `docs/plans/web-account-sync.md`; its pure policy has tests, but auth, transport, and persistent outbox are not implemented.

The app exposes JSON export and import. Imports validate the versioned payload, merge matching configurations atomically, and refuse conflicts without modifying the collection. The GitHub Actions workflow builds and uploads `dist/` as an artifact, but the remote workflow has not run in this session. If a later code change needs a different ownership rule or user-visible outcome, update this document before closing that implementation unit.
