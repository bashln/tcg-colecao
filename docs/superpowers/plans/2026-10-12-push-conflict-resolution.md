# Push conflict resolution implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve server-rejected sync operations through durable, explicit, replayable conflict choices on Web and Android.

**Architecture:** Extend the push RPC to return a stable conflict record and current server state, and add owner-scoped read, resolution, and local-acknowledgment RPCs. Both clients persist push conflicts, pause only their rejected outbox operation, and replay the server's recorded resolution until their local transaction commits and is acknowledged.

**Tech Stack:** PostgreSQL PL/pgSQL and Node test harness; TypeScript, IndexedDB, React, Playwright; Kotlin, Room, coroutines, Compose, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-12-push-conflict-resolution-design.md`

## Global Constraints

- Never clear unrelated local collection rows or outbox operations.
- Never claim that a rejected operation was applied.
- Do not retry a rejected operation unchanged after the person resolves it.
- Bind every read and resolution to the authenticated owner and the expected account identity.
- Make retries of conflict reads and resolutions idempotent.
- Keep the local collection usable offline. A failed cloud read must preserve the local item and pending operation.
- Do not use personal accounts, privileged client credentials, confirmation bypasses, remote ACL changes, or hosted schema changes for local verification.

## Review Focus

- Push result is lost after the server records a conflict; next sync must retrieve and display the same conflict instead of resending it or creating duplicate rows. Test in Task 1 and Task 3.
- Server item changes between conflict display and choice; the RPC must return `stale-conflict` without applying a stale operation. Test in Task 1 and Task 3.
- Local storage fails after server records a resolution but before local completion acknowledgment; replay must apply the same resolution result. Test in Task 1, Task 3, and Task 4.
- A duplicate configuration belongs to a different remote item ID; each choice must preserve the selected collection content and explain any local ID change or local-row removal. Test in Task 1, Task 3, and Task 4.
- Account changes while reading or resolving a conflict; no state or operation may cross identities. Test in Task 2 and Task 3.

---

### Task 1: Durable server push conflicts and resolution protocol

**Files:**
- Create: `supabase/migrations/20261012220000_durable_push_conflicts.sql`
- Modify: `supabase/tests/rpc-operations.test.mjs`
- Reference: `supabase/migrations/20261011180500_collection_sync_rpc.sql`, `supabase/migrations/20261012200000_sync_conflict_resolution.sql`

**Interfaces:**
- Consumes: `public.push_collection_operations(text, jsonb)` and `public.sync_conflicts`.
- Produces: push result content-conflict fields `conflictId`, `reason`, `highWater`, `rejectedOperation`, `cloudItem`, and `conflictingItem`; owner-scoped `read_collection_push_conflicts()`, `resolve_collection_push_conflict(uuid, text, text)`, and `ack_collection_push_conflict_resolution(uuid, uuid)` RPCs. Resolution statuses include `resolved`, `already-resolved`, `stale-conflict`, `not-found`, `device-revoked`, and `invalid-resolution`. `highWater` labels the observed server snapshot; it is not a pull cursor.

- [x] **Step 1: Add RED PostgreSQL tests** for stable conflict ID on same-op retry, authoritative same-item and duplicate-item snapshots, no sequence advance on rejection, owner isolation, changed-state refusal, same-choice replay, conflicting-choice refusal, and local-ack idempotency.
- [x] **Step 2: Run focused tests and verify expected failures.**

Run: `node --test --test-name-pattern='durable push conflict|push conflict resolution' rpc-operations.test.mjs`

Expected: new contract tests fail because current push response omits conflict details and resolution protocol does not exist.

- [x] **Step 3: Implement the migration.** Reuse the unresolved conflict row for the same owner and operation ID. Return an authoritative current item or tombstone without advancing high water for rejected work. Persist an immutable choice/result under conflict-row and item locks; keep it replayable until owner-scoped acknowledgment.
- [x] **Step 4: Run focused and full PostgreSQL tests.**

Run: `node --test --test-name-pattern='durable push conflict|push conflict resolution' rpc-operations.test.mjs`, then `npm test` from `supabase/tests`.

Expected: focused tests and the full harness pass; no remote migration is applied.

- [x] **Step 5: Commit** as `feat(sync): add durable push conflict rpc` (`94d1055`, hardened by `a9f665d`, `8e958cb`).

### Task 2: Typed conflict transport contracts

**Files:**
- Modify: `web/src/sync/supabase-transport.ts`, `web/src/sync/supabase-transport.test.ts`
- Modify: `app/src/main/kotlin/app/tcg/app/data/sync/SyncTransport.kt`, `app/src/main/kotlin/app/tcg/app/data/sync/SupabaseSyncTransport.kt`
- Modify: `app/src/test/kotlin/app/tcg/app/data/sync/SupabaseSyncTransportTest.kt`

**Interfaces:**
- Consumes: Task 1 push fields and RPC names.
- Produces: typed `PushOutcome.Conflict` carrying the server conflict ID and validated cloud state; transport methods to list unresolved push conflicts, begin/replay one resolution, and acknowledge local completion. Every method receives expected account identity and registered device ID where required.

- [x] **Step 1: Add RED Web and Kotlin transport tests** for parsing conflict and cloud snapshots, listing after a lost response, typed permanent errors, identity mismatch before dispatch, resolution replay, and acknowledgment payloads.
- [x] **Step 2: Run focused tests and verify expected failures.**
- [x] **Step 3: Implement transport result types and parsers.** Parse external JSON at the boundary; reject malformed item IDs, values, revisions, statuses, or resolution IDs rather than inventing empty state.
- [x] **Step 4: Run focused transport tests and both platform sync test groups.**

Run: `node --experimental-strip-types --test --test-name-pattern='push conflict' src/sync/supabase-transport.test.ts` from `web/`; `./gradlew :app:testDebugUnitTest --tests app.tcg.app.data.sync.SupabaseSyncTransportTest`.

Expected: transports return validated conflict/resolution data and refuse dispatch after an identity change.

- [x] **Step 5: Commit** as `feat(sync): expose push conflict transport contract` (`1089297`).

### Task 3: Persist conflicts and recover resolutions

**Files:**
- Modify: `web/src/collection/store.ts`, `web/src/sync/coordinator.ts`, `web/src/sync/coordinator.test.ts`
- Modify: `app/src/main/kotlin/app/tcg/app/data/sync/RoomSyncLocalStore.kt`, `CollectionSyncCoordinator.kt`, `app/src/main/kotlin/app/tcg/app/data/local/Entities.kt`, `Daos.kt`, `TcgDatabase.kt`, and `app/src/main/kotlin/app/tcg/app/di/AppModule.kt`
- Create or modify: an Android database migration test for the new Room version. Preserve all existing collection, outbox, cursor, and conflict rows.
- Modify: `app/src/test/kotlin/app/tcg/app/data/sync/RoomSyncLocalStoreTest.kt`, `CollectionSyncCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task 2 typed transport contract.
- Produces: local conflict source `push` with server ID, rejected operation, current remote value, and replayable resolution receipt; coordinator pauses only outbox operations with unresolved push conflict IDs and permits unrelated operations to sync. Store observed server high-water separately from pull cursor and never advance pull cursor during push-conflict resolution.

- [x] **Step 1: Add RED store/coordinator tests** for durable conflict persistence, retry exclusion while allowing unrelated ops, lost-response recovery through conflict listing, same-ID duplicate configuration, identity switch, and local transaction failure before server acknowledgment.
- [x] **Step 2: Run focused tests and verify expected failures.**
- [x] **Step 3: Implement atomic local persistence and replay.** `keep-cloud` applies remote state and removes only affected queued operations in one transaction. `keep-local` applies the server-accepted replacement locally, removes the rejected operation, and stores acknowledgment work in that same transaction. A failed local write must leave the server resolution replayable.
- [x] **Step 4: Run focused and full Web unit tests, plus focused Android store/coordinator and Room migration tests.**

Run: `npm run test:unit` from `web/`; `./gradlew :app:testDebugUnitTest --tests app.tcg.app.data.sync.RoomSyncLocalStoreTest --tests app.tcg.app.data.sync.CollectionSyncCoordinatorTest --tests app.tcg.app.data.local.PushConflictMigrationTest`.

Expected: resolved conflict work replays after interruption without duplicating operations, advancing unrelated state, or crossing account identity.

- [x] **Step 5: Commit** as `feat(sync): persist and replay push conflict choices`.

### Task 4: Conflict UI and actionable permanent errors

**Files:**
- Modify: `web/src/account/LinkPanel.tsx`, associated Web tests in `web/src/account` and `web/tests/sync-outbox.spec.ts`
- Modify: `app/src/main/kotlin/app/tcg/app/ui/account/AccountViewModel.kt`, `AccountScreen.kt`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/app/tcg/app/ui/account/AccountScreenTest.kt` and ViewModel tests

**Interfaces:**
- Consumes: Task 3 durable conflict details and resolution actions.
- Produces: visible local/cloud comparison, explicit `Manter minha coleção` and `Usar versão da nuvem` choices, duplicate-configuration identity/removal explanation, and distinct repair/re-authorize states for `invalid-payload`, `device-revoked`, and `revision-limit`.

- [ ] **Step 1: Add RED Web E2E and Compose tests** for both choices, duplicate-configuration consequences, disabled duplicate action while resolving, permanent-error copy, and retry after network/storage failure.
- [ ] **Step 2: Run focused tests and verify expected failures.**
- [ ] **Step 3: Implement accessible conflict status and actions.** Keep local/cloud labels, progress, errors, focus behavior, and retry visible without claiming success before both server and local completion are confirmed.
- [ ] **Step 4: Run focused account/conflict tests and complete Chromium E2E.**

Run: `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npx playwright test tests/sync-outbox.spec.ts` from `web/`; `./gradlew :app:testDebugUnitTest --tests app.tcg.app.ui.account.AccountScreenTest --tests app.tcg.app.ui.account.AccountViewModelTest`.

Expected: both choices and non-resolvable errors are understandable and preserve local data.

- [ ] **Step 5: Commit** as `feat(sync): add push conflict recovery UI`.

### Task 5: Full verification and records

**Files:**
- Modify: `docs/verification/accounts-and-sync.md`, `progress.json`, `errors.json`, `todo.md`
- Review all files changed in Tasks 1–4.

**Interfaces:**
- Consumes: Tasks 1–4 implementation and tests.
- Produces: local verified implementation, exact test evidence, explicit hosted-verification boundary, and one fresh whole-change review.

- [ ] **Step 1: Run all local gates.**

Run: `npm run test:unit`, `npm run build`, `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npx playwright test` from `web/`; `npm test` from `supabase/tests`; `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` from repository root.

Expected: all suites and builds pass; report exact counts from command output and Android XML.

- [ ] **Step 2: Inspect the full diff and run whitespace, JSON, and staged-secret checks.**
- [ ] **Step 3: Request one fresh-context whole-change review.** Fix Critical and Important findings in one TDD pass, rerun all gates, and record deferred minor findings.
- [ ] **Step 4: Update verification docs and process records.** State that hosted round-trip still requires SMTP/domain and two controlled confirmed accounts; do not claim local harness proves hosted isolation.
- [ ] **Step 5: Commit** as `test(sync): verify push conflict recovery`.

## Self-review

- SQL produces stable conflict and resolution receipts before client persistence; Task 2 parses those receipts; Task 3 stores and acknowledges them; Task 4 exposes the resulting choices.
- Lost push responses and local commit failures are both covered by replay tests before the full gate.
- Push snapshot high-water remains separate from pull cursor, including after both explicit resolution choices.
- Duplicate-configuration behavior is explicit: keep-cloud removes only the local conflicting row, while keep-local adopts the cloud item ID and chosen local content.
- Invalid payload, revoked device, and revision-limit outcomes remain separate from resolvable content conflicts.
- Hosted setup, remote schema application, personal accounts, release, and deployment remain outside this plan.
