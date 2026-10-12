# Account Sync Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make optional account sync recoverable, account-safe, conflict-visible, and protocol-consistent on Android and Web.

**Architecture:** Evolve Supabase link RPC to return durable idempotent outcomes; make clients consume typed outcomes and keep local state until verified completion. Then align identity lifecycle, wire canonicalization, conflict handling, and pending status across Kotlin and TypeScript; prove SQL behavior with the local PostgreSQL harness and user flows with platform tests.

**Tech Stack:** PostgreSQL/PLpgSQL migrations and Node PostgreSQL harness; Kotlin, Room, coroutines, Robolectric/Gradle; TypeScript, IndexedDB, Node tests, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-12-account-sync-completion-design.md`

## Global Constraints

- Collection remains available offline and is never cleared by sign-out, account switch, unlink, or link recovery.
- No operation for account A may be sent or applied while bound to account B.
- Initial linking always requires a current server preview and explicit user confirmation.
- A server response may be described as successful only when every seed operation is accepted or already applied and local binding/cursor commit succeeds.
- A timed-out request is uncertain, not proof of failure or success. Retries must be safe and must not duplicate operations.
- Conflicting local and remote content remains inspectable until the user explicitly chooses a resolution. No implicit last-write-wins behavior.
- Auth confirmation remains enabled. Never use personal accounts, privileged client credentials, or remote ACL weakening for verification.
- Do not publish a release/deploy as part of this implementation. Hosted two-account verification may remain externally blocked and must be reported as such.

## Review Focus

1. Same link plan is committed concurrently from two requests — exactly one server mutation/result record; the other returns identical durable result.
2. Server commits operations but response is lost or malformed — UI reports uncertainty, fresh preview recovers state, and no binding is falsely reported.
3. Account changes while preview/commit or pull-apply is suspended — old identity's plan and cursor never cross into new identity.
4. Pull conflict appears before later sequence entries — conflict remains visible and cursor does not skip the unresolved entry.
5. Device locale, reordered items, null prices, or amount precision edges — digest bytes match across Kotlin and TypeScript.

---

### Task 1: Durable, idempotent first-link RPC

**Files:**
- Create: `supabase/migrations/20261012190000_recoverable_collection_link.sql`
- Modify: `supabase/tests/rpc-link.test.mjs`
- Modify: `supabase/tests/run.mjs` only if the harness requires explicit migration registration.

**Interfaces:**
- Consumes: `public.commit_collection_link(uuid,text,text,jsonb)` from `20261011181000_collection_link_and_devices.sql` and `push_collection_operations(text,jsonb)` from `20261011180500_collection_sync_rpc.sql`.
- Produces: same RPC input signature; JSON statuses `linked`, `partial`, `stale-preview`, `digest-mismatch`, `expired`, `consumed`, `not-found`, `device-revoked`; terminal `linked`/`partial` result stored with request fingerprint and replayed for identical retries.

- [ ] **Step 1: Add RED SQL tests** for exact retry, concurrent retry, payload mismatch after terminal result, partial operation outcomes, and `linked` only when all seeds are applied/already-applied.
- [ ] **Step 2: Run focused harness and observe expected failures.**

Run: `node --test --test-name-pattern='commit_collection_link' rpc-link.test.mjs`
Expected: focused idempotency/partial-result assertions fail against current `consumed` response behavior.

- [ ] **Step 3: Implement migration.** Lock the plan row before state checks; bind terminal result to owner, digest, device, and canonical operation request; call push once; persist exact outcome before return; reject changed retry payload. Ensure `linked` requires all successful outcomes. Prune expired plans and old consumed results opportunistically during preview without modifying collection/operation rows.
- [ ] **Step 4: Run focused SQL tests GREEN.**

Run: `node --test --test-name-pattern='commit_collection_link' rpc-link.test.mjs`
Expected: all focused commit/retry cases pass, including exact response replay and no duplicate operation rows.

- [ ] **Step 5: Run full PostgreSQL harness.**

Run: `npm test` from `supabase/tests`
Expected: all tests pass; RLS ownership and anon denial remain unchanged.

- [ ] **Step 6: Commit** as `fix(sync): make link commits idempotent and recoverable`.

### Task 2: Typed link outcomes and truthful recovery on both clients

**Files:**
- Modify: `web/src/sync/supabase-transport.ts`, `web/src/account/link.ts`, `web/src/account/LinkPanel.tsx`
- Test: `web/src/sync/supabase-transport.test.ts`, `web/src/account/link.test.ts`, `web/tests/link.spec.ts`
- Modify: `app/src/main/kotlin/app/tcg/app/data/sync/SupabaseSyncTransport.kt`, `SyncLinkCoordinator.kt`, `app/src/main/kotlin/app/tcg/app/ui/account/AccountViewModel.kt`, `AccountScreen.kt`, `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/app/tcg/app/data/sync/SupabaseSyncTransportTest.kt`, `SyncLinkCoordinatorTest.kt`, `app/src/test/kotlin/app/tcg/app/ui/account/AccountViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1 terminal response fields `status`, `highWater`, and ordered `{opId,status,reason,sequence/revision}` outcomes.
- Produces: typed `linked | partial | refused` link results; client errors distinguish `preflight`, `uncertain-commit`, and `invalid-response`; partial results include rejected operation identifiers/reasons and never call local completion.

- [ ] **Step 1: Add RED tests** proving preflight validation says nothing was sent; lost HTTP response is described as uncertain; malformed response is not described as a timeout; partial server result never becomes `Linked`; exact retry/fresh-preview recovery remains available in both clients.
- [ ] **Step 2: Run focused Web and Android tests; observe failures** against current broad `error`/`Partial` handling.

Run: `node --experimental-strip-types --test src/sync/supabase-transport.test.ts src/account/link.test.ts` from `web`
Expected: classification and partial-outcome tests fail.

Run: `./gradlew :app:testDebugUnitTest --tests app.tcg.app.data.sync.SupabaseSyncTransportTest --tests app.tcg.app.data.sync.SyncLinkCoordinatorTest --tests app.tcg.app.ui.account.AccountViewModelTest`
Expected: corresponding Android tests fail.

- [ ] **Step 3: Implement transport and UI mapping** from the durable RPC result. Only transport failure after dispatch is uncertain; local validation/serialization failures are preflight; structured partial results carry per-op details. Preserve actionable Android `LinkOutcome.Failed` messages.
- [ ] **Step 4: Run focused suites GREEN** using the same commands; expected every test passes and no failure path claims `linked`.
- [ ] **Step 5: Add E2E recovery test** that simulates partial result, obtains a fresh preview, then confirms only remaining local-only entries; keep both collections accessible and conflict rows visible.
- [ ] **Step 6: Run Web build and the targeted Playwright link test.**

Run: `npm run build && CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npx playwright test tests/link.spec.ts`
Expected: TypeScript build and link E2E pass.

- [ ] **Step 7: Commit** as `fix(sync): expose partial link outcomes and safe recovery`.

### Task 3: Session changes, stale previews, and link/unlink serialization

**Files:**
- Modify: `web/src/app/App.tsx`, `web/src/account/auth.ts`, `web/src/account/LinkPanel.tsx`, `web/src/collection/store.ts`
- Test: `web/src/account/auth.test.ts`, `web/src/account/link.test.ts`, `web/tests/sync-lifecycle.spec.ts`, `web/tests/link.spec.ts`
- Modify: `app/src/main/kotlin/app/tcg/app/ui/account/AccountViewModel.kt`, `app/src/main/kotlin/app/tcg/app/data/sync/SyncLifecycle.kt`, `app/src/main/kotlin/app/tcg/app/di/AppModule.kt`, `app/src/main/kotlin/app/tcg/app/ui/nav/AppNav.kt`, `app/src/main/kotlin/app/tcg/app/data/sync/SyncLinkRepository.kt`
- Test: `app/src/test/kotlin/app/tcg/app/ui/account/AccountViewModelTest.kt`, `app/src/test/kotlin/app/tcg/app/data/sync/SyncLifecycleTest.kt`

**Interfaces:**
- Consumes: auth identity state and Task 2 link result variants.
- Produces: identity-generation invalidation for active previews; serialized application-scope `persistLink`/`unlink` transition with stale writes rejected; same account may resume preserved binding after restart, a different account requires fresh preview.

- [ ] **Step 1: Add RED tests** for direct A→B session change without explicit sign-out clearing preview, A commit never dispatching after B becomes current, and unlink racing confirmed-link persistence (both completion orders).
- [ ] **Step 2: Run focused Web/Android suites and observe the expected stale-preview/race failures.**
- [ ] **Step 3: Implement one serialized binding transition per platform.** Invalidate previews when auth identity changes; make unlink and confirmed persistence compare the current auth generation before binding; do not clear collection or outbox.
- [ ] **Step 4: Rerun focused tests GREEN** and verify signed-out same-account restart still preserves binding without syncing until authenticated.
- [ ] **Step 5: Run targeted lifecycle/account Playwright tests.**

Run: `CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium CI=1 npx playwright test tests/sync-lifecycle.spec.ts tests/account.spec.ts`
Expected: all lifecycle/account tests pass with no RPC under prior identity.

- [ ] **Step 6: Commit** as `fix(sync): serialize account binding transitions`.

### Task 4: Pull conflict records, cursor safety, and explicit resolution

**Files:**
- Create: `supabase/migrations/20261012200000_sync_conflict_resolution.sql`
- Modify: `supabase/migrations/20261011180500_collection_sync_rpc.sql` via new migration replacement functions only; `supabase/tests/rpc-operations.test.mjs`, `rpc-link.test.mjs`
- Modify: `app/src/main/kotlin/app/tcg/app/data/sync/CollectionSyncCoordinator.kt`, `RoomSyncLocalStore.kt`, `SyncTransport.kt`, `SupabaseSyncTransport.kt`, Room database entities/DAOs/migrations, AccountViewModel/Screen/strings
- Modify: `web/src/sync/coordinator.ts`, `supabase-transport.ts`, `indexeddb-local.ts`, `collection/store.ts`, `account/LinkPanel.tsx`, `app/App.tsx`, styles
- Test: matching Android unit tests, `web/src/sync/coordinator.test.ts`, store/transport tests, `web/tests/sync-outbox.spec.ts`, `web/tests/account.spec.ts`

**Interfaces:**
- Consumes: sequenced remote operations, current local items/revisions, authenticated owner, and existing `sync_conflicts` owner policy.
- Produces: `RemoteApplyResult = Applied | Conflict(detail)` on both platforms; durable local conflict detail includes sequence, item ID, reason, local/remote payload; owner-scoped resolution `keep-local | keep-cloud` returns a typed result and is idempotent.

- [x] **Step 1: Add RED SQL tests** for authenticated owner-only conflict listing/resolution, retry idempotency, and rejection of another owner's conflict ID.
- [x] **Step 2: Run focused SQL tests and observe failures.**
- [x] **Step 3: Add only the RPC/schema support needed** to return conflict payloads and record explicit resolution; do not let a resolution advance another owner's cursor or mutate their collection.
- [x] **Step 4: Add RED local-store tests** proving an unapplied remote op stores both sides and does not advance cursor; `keep-cloud` applies the remote value then advances; `keep-local` emits a replacement based on remote revision and advances only after server acknowledgment.
- [x] **Step 5: Implement matching Room and IndexedDB conflict state** and adapt coordinator result types. Stop pull at first unresolved sequence; status lists item ID/reason and both values; explicit choice is accessible and not color-only.
- [x] **Step 6: Add user-flow tests** for resolving one pull conflict on Web and Android while preserving collection access and outbox invariants.
- [x] **Step 7: Run focused suites:** `npm test` in `supabase/tests`; `npm run test:unit` in `web`; targeted Android coordinator/store/ViewModel tests. Expected all pass with cursor held until resolution.
- [ ] **Step 8: Commit** as `feat(sync): add durable conflict resolution`.

### Task 5: Canonical digest parity and preview RPC cleanup

**Files:**
- Modify: `web/src/sync/supabase-transport.ts`, `web/src/sync/crossplatform.test.ts`
- Modify: `app/src/main/kotlin/app/tcg/app/data/sync/SupabaseSyncTransport.kt`, `app/src/test/kotlin/app/tcg/app/data/sync/CrossPlatformSignatureTest.kt`, `SupabaseSyncTransportTest.kt`
- Modify: `testdata/sync-wire-vectors.json`
- Create: `supabase/migrations/20261012210000_remove_link_remote_sequence.sql`
- Modify: `supabase/tests/rpc-link.test.mjs`

**Interfaces:**
- Produces: identical `syncSnapshotDigest(items)` SHA-256 over canonical JSON array bytes on Web and Android; `prepare_collection_link(text,jsonb)` has no caller-supplied remote sequence.

- [x] **Step 1: Add RED shared digest vectors** for empty input, reversed item order, Unicode IDs, null and populated price, and 0.01/maximum accepted minor units; consume same fixture in Kotlin and TypeScript.
- [x] **Step 2: Run cross-platform tests and observe digest mismatches.** Android failed against existing object-wrapped digest; SQL signature test failed against existing three-argument function. Web fixture initially was absent, then its vectors passed.
- [x] **Step 3: Implement one canonical shape**: array wrapper (no Kotlin-only object wrapper), deterministic code-unit ordering, fixed monetary integer serialization, locale-independent bytes. Add migration replacing the three-argument preview function with two-argument signature and revoke/drop old signature safely.
- [x] **Step 4: Update both clients and RPC harness tests**, then rerun Web/Kotlin vector tests and focused PostgreSQL preview tests. Expected same SHA-256 on both platforms and no `p_remote_sequence` field/signature remains.
- [ ] **Step 5: Commit** as `fix(sync): align snapshot digests and preview rpc`.

### Task 6: Truthful pending counts and status recovery

**Files:**
- Modify: `app/src/main/kotlin/app/tcg/app/data/sync/SyncLifecycle.kt`, `CollectionSyncCoordinator.kt`, `RoomSyncLocalStore.kt`, `web/src/sync/runner.ts`, `web/src/sync/coordinator.ts`
- Test: `app/src/test/kotlin/app/tcg/app/data/sync/SyncLifecycleTest.kt`, `CollectionSyncCoordinatorTest.kt`, `web/src/sync/runner.test.ts`, `coordinator.test.ts`

**Interfaces:**
- Consumes: authoritative `pending(identity)`/`listPendingOperations(identity)` after every exception path.
- Produces: error/conflict/offline status uses actual pending count; failure to query pending is represented as unavailable count (not zero).

- [x] **Step 1: Add RED tests** with one pending operation and injected transport/store failure; assert status never reports zero; add refresh test after recovery.
- [x] **Step 2: Run focused tests and observe current false-zero behavior.** Web coordinator/runner returned `pending: 0`; Android tests rejected null as impossible.
- [x] **Step 3: Implement actual-count refresh** after exceptions and model an unavailable count distinctly from zero on Web/Android UI.
- [x] **Step 4: Rerun tests GREEN** and verify linked status can recover on a later successful sync.
- [ ] **Step 5: Commit** as `fix(sync): report authoritative pending counts`.

### Task 7: Full gates, review, process records, commit, and push

**Files:**
- Modify: `progress.json`, `errors.json`, `todo.md`, `.audit/supabase-auto-sync.tsv`, `docs/verification/accounts-and-sync.md`
- Review all implementation files from Tasks 1–6.

**Interfaces:**
- Consumes: all prior task outputs and the fixed list of unresolved external gates.
- Produces: verified branch, fresh whole-branch review, documented remaining hosted/WebKit limits, commits and push to `work/collection-foundation-2026-10-11`; no release/deploy.

- [x] **Step 1: Run complete local suites:** `npm run test:unit`, `npm run build`, Chromium Playwright E2E, `npm test` in `supabase/tests`, and `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`. Reran after review fixes.
- [x] **Step 2: Run `git diff --check`, parse progress/errors JSON, verify no secrets staged, and inspect full diff.** Whitespace and JSON checks clean; commit hook reports no secrets.
- [x] **Step 3: Request one fresh-context whole-branch review.** Fixed data-loss/deadlock findings in one TDD pass, reran full gates, and ledgered deferred findings/ruling.
- [x] **Step 4: Update docs and process ledgers** with exact test counts, review outcomes, hosted block, and migration status.
- [ ] **Step 5: Commit task changes and process records; push branch.** Do not merge, deploy, or publish a release.

## Self-Review

- **Coverage:** link idempotency/retention Task 1; truthful retry and partial results Task 2; auth/preview/unlink races Task 3; pull conflict persistence and explicit resolution Task 4; digest/unused RPC argument Task 5; pending-count accuracy Task 6; all gates and evidence Task 7. Hosted round-trip is explicitly external and not falsely claimed.
- **Interfaces:** Task 1 result shape feeds Task 2; Task 2 typed outcomes feed Task 3; Task 4 introduces one shared `RemoteApplyResult` and resolution contract for both clients; Task 5 digest fixture updates link-preview request contract; Task 6 changes sync status consumed by existing UIs.
- **Review focus:** all five scenarios are assigned to tests in Tasks 1–5; no hidden scenario is left solely to final review.
- **Proportion:** seven tasks split at independently rejectable backend, link-client, identity lifecycle, conflict, protocol, status, and integration boundaries.
