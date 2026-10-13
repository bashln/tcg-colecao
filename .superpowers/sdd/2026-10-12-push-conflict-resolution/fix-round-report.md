# Push-conflict review fix round

## Result

Addressed review findings Critical #1 and Important #2/#3. Changes remain local to `fix/push-conflict-review`; no push, merge, deploy, release, hosted Supabase mutation, remote setting change, or personal-data access occurred.

## Changes

- `supabase/migrations/20261012220000_durable_push_conflicts.sql`: when duplicate-config `keep-local` adopts cloud ID, rewrite authoritative item payload ID before inserting and returning operation. Push conflict refresh and read now filter `source = 'push'`.
- `web/src/collection/store.ts`: push `keep-cloud` removes all queued operations whose item IDs occur in the existing affected-ID set; preserves other item IDs. Same transaction as applying cloud state.
- `app/src/main/kotlin/app/tcg/app/data/sync/RoomSyncLocalStore.kt`: push `keep-cloud` deletes pending operations for affected IDs in Room transaction rather than acknowledging only rejected op ID.
- Regression tests in `supabase/tests/rpc-operations.test.mjs`, `web/tests/sync-outbox.spec.ts`, and `app/src/test/kotlin/app/tcg/app/data/sync/RoomSyncLocalStoreTest.kt`.

## TDD evidence

### Critical #1 — adopted ID consistency

- RED: `node --test --test-name-pattern='duplicate-config keep-local logs payload' rpc-operations.test.mjs` (from `supabase/tests`). Failed as expected: returned operation item ID was `item-local-original`; expected adopted ID `item-adopt-cloud`.
- GREEN: same test in focused run after fix passed. It checks inserted `collection_operations.item_id` against stored item payload ID and checks pulled operation `itemId` equals `item.id`.
- Schema note: this repository stores the item itself in `collection_operations.payload`; `pull_collection_operations` exposes that payload as operation `item`. Therefore assertion reads `payload->>'id'` and also verifies pull shape, rather than assuming raw DB column stores an `{item: ...}` wrapper.

### Important #2 — push keep-cloud outbox cleanup

- Web RED: Chromium regression observed extra affected pending operation remained after keep-cloud; expected pending IDs exactly `['unrelated-op']`.
- Web GREEN: `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npx playwright test tests/sync-outbox.spec.ts -g 'push-conflict IndexedDB keep-cloud'` — 1 passed. Exercises stored `source: 'push'` conflict through IndexedDB resolver, superseded affected operation, and unrelated operation.
- Android RED: `./gradlew :app:testDebugUnitTest --tests app.tcg.app.data.sync.RoomSyncLocalStoreTest.durable_push_conflict_blocks_only_rejected_outbox_and_local_resolution_is_atomic_without_cursor_advance` failed as expected because the affected `superseded-op` remained queued.
- Android GREEN: same focused Gradle test passed after fix. Raw Room outbox contains only `unrelated-op`; collection and cursor assertions remain covered.

### Important #3 — source separation

- RED: `node --test --test-name-pattern='push and legacy conflict reads stay separated' rpc-operations.test.mjs` (from `supabase/tests`) failed because push listing included a seeded legacy row.
- GREEN: same focused test passed after source filter. Confirms legacy listing sees only legacy row and push listing sees only push row.

## Broader verification

- PostgreSQL focused regression tests: 2 passed, 0 failed.
- Full PostgreSQL harness `npm test` in `supabase/tests`: 58 passed, 0 failed.
- Web `npm run test:unit`: 84 passed, 0 failed.
- Web `npm run build`: passed (`tsc --noEmit` and Vite production build).
- Chromium `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npx playwright test tests/sync-outbox.spec.ts`: 21 passed, 0 failed.
- Android focused `RoomSyncLocalStoreTest` and `CollectionSyncCoordinatorTest`: passed.
- Full Android app/core tests and debug build, `./gradlew :app:testDebugUnitTest :core:test :app:assembleDebug`: app 307 passed, core 65 passed, `assembleDebug` passed.
- `git diff --check`: passed.

## Test/setup notes

- Initial PostgreSQL test invocation lacked installed `embedded-postgres`; `npm ci` in `supabase/tests` resolved setup, with 0 reported vulnerabilities.
- Initial Web test fixture draft failed its own draft validation; corrected fixture before recording behavioral RED.
- A first UI-driven keep-cloud fixture let background sync consume newly added operations, obscuring the store behavior. Reworked regression to seed IndexedDB rows without emitting collection-change events and exercise `applyPushConflictResolution` on the durable push conflict directly. Final Chromium regression passes.
- One intermediate Web build caught unused fixture binding and implicit-any callback parameters; corrected, then build passed.
- Expected PostgreSQL permission-denied / invalid-input log entries appear during negative authorization tests; harness passed.
- No full Web browser suite beyond `sync-outbox.spec.ts` was run. No hosted or remote validation was attempted.

## Commits

- `c720a76` — `test(sync): cover push conflict review regressions` (tests committed first)
- `d368caf` — `fix(sync): reconcile push conflict state consistently`.

## Concerns

- None known. Hosted Supabase behavior remains unverified by design. The SQL regression reflects existing raw payload storage (item JSON) and validates the client-visible operation shape through pull.
