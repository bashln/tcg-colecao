# Complete Account Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish optional, local-first authenticated Android↔web collection sync with explicit link preview, safe account switching, automatic active-session sync, and verified conflict handling.

**Architecture:** IndexedDB and Room remain authoritative for local data and transactional outboxes. Authenticated Supabase RPC transport exchanges immutable per-item operations; first link is bound to local digest and cloud sequence, and requires explicit user confirmation. No sync is reported complete before authenticated two-account isolation and Android/web round-trip pass.

**Tech Stack:** TypeScript, React, IndexedDB, Playwright, Kotlin, Room, coroutines, Supabase Auth/Postgres/RLS/RPC, existing CLI and migrations.

**Spec:** `docs/plans/web-account-sync.md`

## Global Constraints

- Local collection remains usable without account or network; logout never deletes local data.
- First link requires a server preview and explicit confirmation before upload.
- Account switch stops previous identity sync and requires a fresh preview.
- No implicit last-write-wins, merging different item IDs, or silent conflict resolution.
- No service-role key, SMTP password, database password, or private credential in client or tracked files.
- No release/deploy/publication until isolated authenticated round-trip, isolation, RLS, and CI gates pass.
- Preserve user collections and untracked files; test with isolated accounts/fixtures only.

## Review Focus

- Account switch during push/pull must not acknowledge or apply data under the next identity.
- A stale preview or changing local digest must refuse link without local/cloud collection mutation.
- A collision in item ID or `(cardId, variant, condition)` must remain visible and preserve both sides.
- Offline/local write, process restart, and network recovery must retain/retry outbox without data loss.
- Auth confirmation callback and expired sessions must not bind an account or upload before confirmation.

## Tasks

### Task 1: Web email confirmation callback

**Files:** `web/src/account/auth.ts`, `web/src/account/auth.test.ts`, `web/src/app/App.tsx`.
**Produces:** `SupabaseWebAuth.consumeConfirmation(fragment): Promise<AuthState>`; validates callback tokens by fetching `/auth/v1/user`, persists session only after valid user response, and lets App remove token fragment from browser history.

- [x] Test callback tokens create a stored session only after successful user validation; observed missing-method failure first.
- [x] Implement callback consumption, safe session validation, and fragment cleanup on success/error.
- [x] Run all web unit tests and E2E signup callback test.

### Task 2: Web first-link preview and confirmation

**Files:** `web/src/sync/supabase-transport.ts`, transport tests, `web/src/collection/store.ts`, account UI tests.
**Produces:** strictly parsed `prepareLink`/`commitLink` operations; preview bound to local digest and remote sequence; no binding or push until explicit confirmation.

- [x] Test preview payload, local digest, server sequence, stale plan, conflict rows, and no push before explicit confirm; observed RED for missing transport APIs.
- [x] Implement transport parsing and transactional local binding only after successful commit; preserve local rows on all failures. (Complete: transport, atomic IndexedDB completion, LinkPanel preview/confirm UI, stale-digest refusal, visible conflict/duplicate blocking.)
- [x] Run web unit (55/55), Playwright link flow (link.spec 3/3, full suite 25/25), and build.

### Task 3: Web sync lifecycle and visible status

**Files:** `web/src/app/App.tsx`, `web/src/sync/indexeddb-local.ts`, `web/src/sync/coordinator.ts`, E2E tests.
**Produces:** authenticated coordinator wiring, stable browser device ID, serialized sync after local edits/foreground/online, visible pending/conflict/error/retry states.

- [x] Test account isolation on switch/logout, automatic triggers, offline retry, visible conflicts, and local persistence; observed RED (no lifecycle wiring).
- [x] Implement lifecycle wiring and UI: single-flight SyncRunner (coalescing), coordinator bound only while signed-in account owns the binding, triggers on link/local write/foreground/online, visible pending/conflict/error/offline status with manual retry.
- [x] Run full web gates: unit 59/59, Chromium E2E 30/30 (5 new lifecycle tests), build pass.

### Task 4: Android link preview and confirmation

**Files:** `app/src/main/kotlin/app/tcg/app/data/sync/`, `app/src/main/kotlin/app/tcg/app/ui/account/`, Android unit tests.
**Produces:** authenticated preview/commit client and a confirmation-driven binding; switching/logout preserve collection.

- [x] Test preview/commit, account switch, cancellation, stale digest, and no upload before confirm; observed RED then GREEN (transport 10/10, Room completeLink 14, coordinator 7/7, VM 19, screen 10).
- [x] Implement link methods (`prepareLink`/`commitLink`) and wire AccountViewModel/AccountScreen to them (server preview with counts, async confirm, LINK_BLOCKED/LINK_STALE errors, DI).
- [x] Run focused Android tests and full gates: 270+ app unit tests, `:core:test`, `:app:assembleDebug` pass.

### Task 5: Android sync lifecycle

**Files:** Android sync DI/composition root, `RoomSyncLocalStore`, account/lifecycle integration, tests.
**Produces:** authenticated coordinator wired to Room and RPC transport; sync after edits, foreground, and connectivity regain; explicit pending/conflict/error state.

- [x] Test real composition wiring, identity switch race, offline recovery, cursor restart, and conflict visibility; observed RED (no lifecycle/binder/status).
- [x] Implement coordinator/trigger wiring (SyncLifecycle + SyncTriggerBinder + SyncTriggerSources + DI + MainActivity) and error mapping (resolveSyncIdentity, SyncStatusView, visible conflict rows).
- [x] Run full Android unit suite (270 app tests) and debug build; no device tests were run against personal app data.

### Task 6: Isolated hosted verification and completion audit

**Files:** Supabase/Postgres tests, web/Android verification docs and progress ledger.
**Produces:** evidence of two-account RLS isolation, authenticated cross-platform round-trip, conflict and retry behavior, clean hosted advisors or documented residuals.

- [x] Prove forged owner/device rejection: local Postgres harness (34/34) covers two-account RLS isolation, identity-from-JWT-only, another-account empty log, and anon refusal. Cross-platform wire contract proven by shared `testdata/sync-wire-vectors.json` consumed by both `crossplatform.test.ts` (3/3) and `CrossPlatformSignatureTest.kt` (2/2).
- [ ] Authenticated two-account hosted round-trip (both directions with real devices): BLOCKED read-only — hosted signup enforces email confirmation (no session issued, password grant returns `invalid_credentials` for the unconfirmed user, repeated signups rate-limit 429). SMTP credentials and production domain unavailable; no service-role key permitted. Exact manual steps recorded in the Decision Log; cannot be claimed complete without confirmed test accounts.
- [x] Run local Postgres suite (34/34), hosted security/performance advisors (read, residuals documented), web CI gates (unit 62/62, E2E 30/30, build), Android CI gates (`:app:testDebugUnitTest`, `:core:test`, `:app:assembleDebug`).
- [x] Review final diff: 31 changed files (+2618/-95), `git diff --check` clean, no secrets in tracked diff (only a Playwright placeholder key), `web/.env.local` ignored, sign-out clears only `sync_binding` (never collection rows), no commit/push/deploy/release performed. Verified with the documented Playwright commands (`CI=1 --trace on`): account 2/2, link 3/3, sync-lifecycle 5/5, sync-outbox 12/12, full suite 30/30 with 30 traces; Android focused + full gates pass (272 app tests).

## Decision Log

- Hosted Auth site URL and redirect allow-list are configured for `http://localhost:5173` only. Production domain remains unset.
- Existing Supabase project `fwzzwqakrvogafqiatgo` is already linked; do not reinitialize or relink.
- The existing ignored `supabase/config.toml` had unsafe drift. Remote config was pulled; remote update used an isolated minimal config and changed only site URL and local redirect allow-list.
- Hosted email confirmation is enabled. Custom SMTP is not configured because provider credentials are unavailable; do not fabricate or persist them.
- **Hosted two-account round-trip is blocked (read-only probe, 2026-10-12).** `POST /auth/v1/signup` with a publishable key returns 200 but issues no session (`confirmation_sent_at` set, no `access_token`); `grant_type=password` for that unconfirmed user returns 400 `invalid_credentials`; `.test` addresses are rejected (`email_address_invalid`) and further signups rate-limit (429). No service-role key is permitted and SMTP/domain are unavailable, so no confirmed test account can be created programmatically. Nothing remote was mutated by this probe.
- **Exact steps to finish the hosted gate once a confirmed test account exists** (two isolated accounts A and B, never a personal account):
  1. Confirm two throwaway emails A and B (custom SMTP or a production domain), then sign both in on the publishable key to obtain `access_token`s.
  2. Web → web: sign in as A in Chromium, seed a local collection, Perfil → Revisar vínculo → Confirmar vínculo; assert `prepare_collection_link`, `read_collection_link_snapshot`, `commit_collection_link` fire in order and IndexedDB binding + cursor are set.
  3. Web → Android: install the debug APK, sign in as A, link, then edit a row on web and confirm the Android pull applies it (and vice versa) through `push_collection_operations`/`pull_collection_operations`.
  4. Two-account isolation: sign in as B on a fresh device/profile, link an empty collection, and assert A's rows are invisible (B's snapshot/log are empty) and B's pushes never appear under A.
  5. Forged identity/device: with A's token, attempt `pull`/`push` naming B's `p_device_id` and assert refusal; assert anon (`no Authorization`) is refused for every RPC.
  6. Conflicts/retry: force a stale `baseRevision` and an offline→online retry on both platforms; assert the conflict stays visible and pending locally, and the outbox drains after recovery.
  7. Clean up: delete both test accounts and their rows; confirm hosted `collection_items` returns to 0.
- Local substitutes already green for this gate: Postgres harness proves per-JWT-owner RLS isolation, identity-from-JWT-only, another-account empty log, and anon refusal (34/34); shared `testdata/sync-wire-vectors.json` proves web/Android emit identical canonical bytes and signatures (web 3/3, Android 2/2).
