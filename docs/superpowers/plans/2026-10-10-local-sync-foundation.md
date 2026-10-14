# Local-first sync foundation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve account-free local collections while adding transactional sync intent and a deterministic two-device test transport.

**Architecture:** IndexedDB and Room remain authoritative for local reads and writes. Each local mutation writes an immutable operation into an outbox in the same transaction. A pure protocol coordinator exchanges operations with an in-memory remote used in tests; no authentication, network service, or production credentials are introduced in this plan.

**Tech Stack:** React, TypeScript, IndexedDB, Playwright, Kotlin, Room, JUnit, existing sync policy.

**Spec:** `docs/superpowers/specs/2026-10-10-community-collection-product-design.md`

## Global Constraints

- The app works without an account. The local collection remains available without network.
- Creating an account and syncing between Android and web are optional.
- The launch does not charge, limit scans, or block essential features.
- Open export and restore allow people to leave the service without losing the collection.
- Failed network, unavailable catalog, or expired session does not erase or block local operations.
- Conflicting writes are explicit; never use last-write-wins or automatically add quantities for different IDs.
- No remote project, database, billing, deployment, or existing Supabase changes in this phase.
- Keep tests data-isolated. Never clear or overwrite user collections to prepare a test.

## Review Focus

- A storage failure between local mutation and outbox write must roll back both changes. Pin with a transaction-abort test for each platform.
- Replaying a submitted operation after timeout must not duplicate or advance the remote row twice. Pin with a two-device retry test.
- A local edit or delete made while offline must remain visible and queued after restart. Pin with persistence and reopen tests.
- An operation for account A must never be submitted after changing sync identity to account B. Pin with coordinator identity-switch test, even though production Auth is deferred.
- A concurrent edit and tombstone must report conflict rather than silently replace either device's state. Pin with simulator tests.

---

## Scope boundary

This plan delivers local transactional outboxes and protocol-level two-device verification. It does not implement account UI, real Auth, HTTP transport, Supabase schema, billing, or deployment. Those are separate plans after this foundation passes and Supabase cost and Android Auth support are verified. Vercel Functions are not part of the recommended backend.

## File map

- `web/src/collection/store.ts`: IndexedDB transaction boundary for collection writes and outbox.
- `web/src/sync/outbox.ts`: web operation creation, listing, acknowledgement, and account binding metadata.
- `web/src/sync/coordinator.ts`: pure orchestration contract for pending operations, pull/apply, conflicts, retries, and sync identity.
- `web/src/sync/memory-transport.ts`: deterministic remote fake for tests only.
- `web/src/sync/*.test.ts`: protocol and storage behavior tests.
- `web/tests/collection.spec.ts` and `web/tests/sync-outbox.spec.ts`: browser-level local-first UX, offline edits, and outbox coverage.
- `app/src/main/kotlin/app/tcg/app/data/local/TcgDatabase.kt`: Room schema version and migration registration.
- `app/src/main/kotlin/app/tcg/app/data/local/Entities.kt`: Room outbox row.
- `app/src/main/kotlin/app/tcg/app/data/local/Daos.kt`: transactional outbox queries.
- `app/src/main/kotlin/app/tcg/app/data/repository/RoomCollectionRepository.kt`: write local mutation and sync operation in one Room transaction.
- `app/src/main/kotlin/app/tcg/app/data/sync/`: Kotlin protocol coordinator and deterministic fake transport.
- `app/src/test/kotlin/app/tcg/app/data/`: migration, atomicity, replay, retry, conflict, and identity tests.

## Interfaces

The web and Kotlin implementations use equivalent protocol fields, with language-native types:

- Operation: `opId`, `deviceId`, `itemId`, `kind`, `baseRevision`, and validated item payload for create/replace. Delete carries `itemId` and `baseRevision`.
- Transport: `push(identity, operations)` returns a result for each `opId`; `pull(identity, afterSequence)` returns ordered accepted operations and an identity-scoped sequence cursor.
- Coordinator: sync only when a non-null identity is explicitly bound; pin each request to that identity; retain failed and conflicting operations in the outbox; apply remote operations and save the cursor only while the identity remains bound.
- Local repositories continue to provide collection data when no identity is bound. Outbox empty means no sync work, not an empty collection.

### Task 1: Specify two-device protocol behavior

**Files:**
- Create: `web/src/sync/coordinator.test.ts`
- Create: `web/src/sync/memory-transport.test.ts`

**Interfaces:**
- Consumes: `CollectionOperation`, `SyncState`, and `applyOperation` from `web/src/sync/policy.ts`.
- Produces: expected semantics for the simulator and coordinator tasks.

- [x] **Step 1: Read existing policy tests and add failing transport/coordinator tests for replay, batch conflicts, two devices, retry retention, and identity switching.** Existing policy tests already cover stale writes and tombstones.
- [x] **Step 2: Run focused tests and verify failures describe missing coordinator/transport behavior.** Run `cd web && node --experimental-strip-types --test src/sync/coordinator.test.ts src/sync/memory-transport.test.ts src/sync/policy.test.ts`. Expected before implementation: missing-module failures for coordinator and transport; existing policy tests pass.
- [x] **Step 3: Implement deterministic transport and coordinator in Task 2, then run focused tests.** The two tasks form one red-green unit because the new tests import modules produced by Task 2.

### Task 2: Implement deterministic in-memory remote and coordinator

**Files:**
- Create: `web/src/sync/coordinator.ts`
- Create: `web/src/sync/memory-transport.ts`
- Modify: `web/src/sync/coordinator.test.ts`
- Modify: `web/src/sync/memory-transport.test.ts`

**Interfaces:**
- Consumes: protocol contracts from Task 1 and existing `applyOperation`.
- Produces: `SyncTransport`, `SyncCoordinator`, and deterministic fake for tests.

- [x] **Step 1: Define identity-scoped `push(identity, operations)` and `pull(identity, afterSequence)` types.** Return a result for each operation and ordered remote operations with an identity-scoped sequence cursor.
- [x] **Step 2: Implement `createMemoryTransport()` with stable replay, ordered pull, isolated identity state, and injectable one-shot failures.** The fake keeps each account's server state separate.
- [x] **Step 3: Implement `createSyncCoordinator({ transport, local })`.** Expose `bindIdentity(identity)`, `syncOnce()`, and `status()`; do not reject durable operations just because their originating device ID differs from the current installation ID.
- [x] **Step 4: Test two devices, replay, failed push, retry, and independent per-operation conflicts.** Assert retries apply once and successful batch neighbors are not rejected by a conflicting operation.
- [x] **Step 5: Test account switching and conflict/tombstone behavior.** Assert account-A operations remain queued and unsent under account B; policy tests cover stale updates and tombstones.
- [x] **Step 6: Run focused and full unit tests plus build.** Run `cd web && npm run test:unit && npm run build`. Expected: 22 unit tests pass and build succeeds.

### Task 3: Add atomic IndexedDB outbox

**Files:**
- Modify: `web/src/collection/store.ts`
- Create: `web/src/sync/outbox.ts`
- Create: `web/tests/sync-outbox.spec.ts`

**Interfaces:**
- Consumes: web `CollectionOperation` from `web/src/sync/policy.ts`.
- Produces: `listPendingOperations(identity)`, `acknowledgeOperation(opId, identity)`, and transactionally recorded operations for collection writes.

- [x] **Step 1: Add browser tests for add/edit/remove creating identity-bound operations with exact base revisions; verify local writes without a binding create no operation.** IndexedDB behavior is tested in Playwright because the project has no fake IndexedDB dependency.
- [x] **Step 2: Add abort test for storage transaction.** Force outbox write failure and assert collection add rolls back after reopening the database.
- [x] **Step 3: Add identity isolation, acknowledgement, and monotonic cursor tests.** Verify account switch hides but retains prior-account operations, and wrong-identity acknowledgement fails.
- [x] **Step 4: Run focused tests and confirm expected failures.** `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npx playwright test tests/sync-outbox.spec.ts` initially failed on the missing binding API, then passed after implementation.
- [x] **Step 5: Add IndexedDB migration to version 4 without changing or dropping existing stores.** Preserve items, cards, import receipts, and v3 outbox rows; assign causal order for legacy operations before retry.
- [x] **Step 6: Route add/edit/remove and backup-import writes through an operation builder in the same transaction.** Keep current behavior unchanged without a binding; abort on operation-ID, binding-row, ordering-row, or outbox-write errors.
- [x] **Step 7: Run web unit tests, browser tests, and build.** Run `cd web && npm run test:unit && CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:e2e && npm run build`. Expected: 22 unit tests, 14 Chromium tests, and build pass.

### Task 4: Persist outbox transactionally in Room

**Files:**
- Modify: `app/src/main/kotlin/app/tcg/app/data/local/Entities.kt`
- Modify: `app/src/main/kotlin/app/tcg/app/data/local/Daos.kt`
- Modify: `app/src/main/kotlin/app/tcg/app/data/local/TcgDatabase.kt`
- Modify: `app/src/main/kotlin/app/tcg/app/data/repository/RoomCollectionRepository.kt`
- Create: `app/src/main/kotlin/app/tcg/app/data/local/CollectionOperationEntity.kt` if a focused file is clearer than `Entities.kt`.
- Modify: `app/src/test/kotlin/app/tcg/app/data/repository/CollectionRepositoryTest.kt`
- Modify: `app/src/test/kotlin/app/tcg/app/data/local/DaoTest.kt`

**Interfaces:**
- Consumes: current Room item/card transactions and the protocol operation fields from this plan.
- Produces: versioned Room outbox entity and DAO APIs for pending, acknowledge, and identity-scoped operations.

- [x] **Step 1: Add migration test from current Room version with an existing card, collection item, and price observation.** Assert all existing rows survive and outbox starts empty.
- [x] **Step 2: Add transactional repository tests for binding-scoped add/add-same-config/edit/remove operations.** Assert each outbox payload and base revision, and that no outbox row is created when unbound.
- [x] **Step 3: Add atomicity test that aborts operation insertion and proves add/edit/remove local state remains unchanged.** Use SQLite trigger `RAISE(ABORT)` in the in-memory Room database.
- [x] **Step 4: Run focused Room tests to verify new assertions fail.** Run `./gradlew :app:testDebugUnitTest --tests '*CollectionRepositoryTest*' --tests '*DaoTest*'`.
- [x] **Step 5: Add a Room migration that adds local revision and creates sync binding/outbox tables without rewriting or dropping existing user rows.** Update schema version and migration registration.
- [x] **Step 6: Write outbox rows and local revisions inside the same Room transaction as add/edit/delete when identity binding exists.** Keep unlinked collection writes local-only.
- [x] **Step 7: Run focused tests and migration validation.** Run `./gradlew :app:testDebugUnitTest --tests '*CollectionRepositoryTest*' --tests '*DaoTest*'`. Expected: all pass.

### Task 5: Add equivalent Kotlin two-device protocol tests

**Files:**
- Create: `app/src/main/kotlin/app/tcg/app/data/sync/CollectionSyncCoordinator.kt`
- Create: `app/src/main/kotlin/app/tcg/app/data/sync/SyncTransport.kt`
- Create: `app/src/test/kotlin/app/tcg/app/data/sync/CollectionSyncCoordinatorTest.kt`
- Create: `app/src/test/kotlin/app/tcg/app/data/sync/InMemorySyncTransport.kt`
- Modify: `app/src/main/kotlin/app/tcg/app/di/AppModule.kt` only if the local testable coordinator requires production construction.

**Interfaces:**
- Consumes: Room operation entity from Task 4 and the same wire-neutral semantics as the web coordinator.
- Produces: Kotlin transport and coordinator interfaces with two-device tests.

- [x] **Step 1: Add Kotlin tests for replay, retry, stale revision, tombstone conflict, and identity switch.** Assert failed/conflicting operations remain queued and local collection remains readable.
- [x] **Step 2: Run focused Kotlin tests and verify missing implementation failures.** Run `./gradlew :app:testDebugUnitTest --tests '*CollectionSyncCoordinatorTest*'`.
- [x] **Step 3: Implement Kotlin transport/coordinator using typed operation variants and the same result semantics as web.** Keep network and vendor SDKs out of these interfaces.
- [x] **Step 4: Run focused Kotlin tests.** Expected: all protocol cases pass.

### Task 6: Verify local-first UX and complete phase

**Files:**
- Modify: `web/tests/collection.spec.ts`
- Modify: `web/src/app/App.tsx` only if tests expose misleading online/account copy.
- Modify: `web/README.md`
- Modify: `docs/plans/web-account-sync.md`
- Modify: `progress.json`
- Modify: `errors.json` only if a validation attempt fails.

**Interfaces:**
- Consumes: local outbox and protocol test implementations from Tasks 1-5.
- Produces: user-visible proof that offline local collection remains usable while sync is absent or failing.

- [x] **Step 1: Add browser test that blocks network, edits an existing card, and verifies collection remains usable with explicit offline status.** No account UI is implied. Offline reload is deferred because app-shell caching is not implemented; reload after reconnect verifies persistence.
- [x] **Step 2: Add browser test that forces sync failure and verifies local collection remains visible and pending operation remains queued.**
- [x] **Step 3: Run web unit, browser, and build checks.** Run `cd web && npm run test:unit && CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:e2e && npm run build`. Expected: all pass.
- [x] **Step 4: Run Android JVM tests and build.** Run `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`. Expected: all pass; do not run instrumented tests on personal device without separate backup-safe approval.
- [x] **Step 5: Read user flows and update docs to state that account sync is still not available.** Include outbox semantics, local-first guarantee, and next gated phase.
- [x] **Step 6: Update `progress.json` after each verified task and record final test evidence.** Preserve all unrelated pending Android and user-device tasks.

## Follow-up plans

After this plan passes, write separate plans for: (1) Supabase Auth feasibility and cost verification, (2) isolated Supabase development backend and security tests, (3) Android/web account UI and remote end-to-end sync, and (4) product release readiness. Do not combine them into this plan or create remote infrastructure before their gates.
