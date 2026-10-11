# Supabase Auth and automatic Android/web sync

## Phase A. Frame

**Outcome.** A collector can keep using Android and web locally without an account, optionally sign in, explicitly link the local collection, and have changes synchronize automatically across signed-in clients when either app is active or resumed. Existing data must never disappear or cross accounts silently.

**Completion predicate.** VERIFIED only if a new isolated Supabase project contains versioned schema/RLS/RPC; anonymous and cross-user access tests fail closed; web and Android can sign in/out and bind the authenticated Supabase user ID; local writes remain atomic with outbox; automatic triggers cover local writes, app resume, and network restoration; deterministic tests cover two-client replay, retry, tombstones, conflict, account switch, and restart; builds and user-visible login/sync states pass; existing projects and local collection data remain untouched. If remote billing cannot be verified, remote provisioning is INCONCLUSIVE and must remain undone.

**Scope estimate.** Cross-platform work across 5 system seams: Supabase migrations/RLS/RPC, web Auth and transport/storage/lifecycle, Android Auth/deep links and transport/Room/lifecycle, cross-client protocol fixtures, and end-to-end verification. High rigor is required because auth, remote writes, and migration affect user data. Sequence local-verifiable work before creating any remote project.

**Grounded blockers.** Current branch is `work/collection-foundation-2026-10-11`; main remains clean. Existing outboxes/coordinators have no production adapters or auth. Supabase CLI lists three projects (one active, two inactive) but cannot report organization billing plan. Existing org/project resources must not be changed. Local Docker was previously unavailable and must be rechecked before choosing DB verification harness.

## Phase B. Design and workflow

1. Freeze protocol/data shapes and account-binding state transitions before writing adapters.
2. Build local schema/RLS/RPC validation harness and test fixtures before client transports.
3. Implement server operation semantics locally: idempotent operation IDs, per-item CAS revisions, tombstones, ordered pull cursor, explicit conflicts, owner derived from Auth, no direct client mutations.
4. Implement optional email-based Auth and explicit first-link preview/confirmation in web and Android; retain local collection on logout/account switch.
5. Implement web and Android production adapters against the same RPC contract; apply each pull page and cursor transactionally.
6. Trigger sync after local commit, on app resume, and on connectivity return; serialize one sync loop per client identity. Android background retry and supported web Background Sync are best-effort only.
7. Test two real clients against local Supabase/Postgres; complete E2E and build checks; document any unsupported browser/platform behavior.
8. Verify organization billing and new-project isolation before remote provisioning. Do not deploy or push.

## Throughput checkpoint

- **Blocking first steps.** Preserve dirty branch state, verify plan/billing without reading or exposing credentials, and identify available local Postgres/Docker verification path.
- **Independent workstreams.** Server contract/migrations and client UI/auth can be explored separately, but implementation is serialized at the shared operation contract. Web and Android adapters can diverge only behind that contract.
- **Shared mutable state.** Room and IndexedDB each retain one local owner; Supabase RPC is the only remote writer. No overlapping workers edit the same files or schemas.
- **Smallest safe decomposition.** One goal-worker owns the implementation and updates this log; parallel read-only exploration only. One owner protects protocol consistency across clients.

## Phase B addendum. Implementation sequence (2026-10-11)

Local verification path decided before any code: Docker is unusable (`permission denied` on `/var/run/docker.sock`, no passwordless sudo) and no system PostgreSQL server exists (`psql` client only, port 5432 refused). The harness therefore runs unprivileged PostgreSQL binaries fetched through npm (`embedded-postgres`) under `supabase/tests/`, applying the real files from `supabase/migrations/`. If that fails, fall back to PGlite (WASM Postgres) and mark multi-connection races inconclusive. Remote provisioning stays gated on organization plan/cost verification, which the CLI cannot expose.

Units, each ending in a checked state:

1. **U1 Harness.** `supabase/tests/` boots a throwaway PostgreSQL cluster, applies `supabase/migrations/*.sql` verbatim, creates `anon`/`authenticated` roles and JWT claim settings. Evidence: smoke test green.
2. **U2 Schema and RLS.** Migration 0001 with `collections`, `collection_items` (revision, tombstone), `collection_operations` (unique opId, monotonic server sequence), `sync_devices`, `sync_conflicts`, `link_plans`; RLS enabled and forced; no client mutation grants. Evidence: anonymous denial, two-account isolation, forged owner id refusal tests green.
3. **U3 Push/pull RPCs.** `push_collection_operations` and `pull_collection_operations` with owner from `auth.uid()`, CAS on `base_revision`, idempotent opId replay, opId reuse rejection, tombstones, conflict rows, transactional rollback, revocation from `anon`. Evidence: replay, stale replace/delete, conflict, rollback, tombstone pull tests green.
4. **U4 Link and conflict RPCs.** `prepare_collection_link`/`commit_collection_link` bound to local digest plus remote high-water sequence with expiry, `resolve_collection_conflict`, `revoke_sync_device`. Evidence: digest mismatch/expiry, no-write preview, resolution tests green.
5. **U5 Shared protocol fixtures.** `sync-contract/fixtures/*.json` vectors executed by both TypeScript and Kotlin suites, only where they remove drift rather than add ceremony.
6. **U6 Web production adapters.** Auth session module (GoTrue REST, platform-secure storage), Supabase RPC transport behind the existing `SyncTransport` seam, local IndexedDB apply adapter, cursor persistence per identity. Evidence: unit tests against a fake HTTP boundary plus anonymous denial mapping.
7. **U7 Web wiring and UX.** Profile connect-account flow with preview/confirm, sync status, conflict list and explicit resolution, automatic triggers on local commit, visibility resume, and `online`, identity fencing on account switch, opportunistic Background Sync only when present. Evidence: Playwright flows with an intercepted RPC backend.
8. **U8 Android production adapters and UX.** Auth module, Ktor RPC transport, Room apply adapter plus per-identity cursor, triggers on outbox commit, lifecycle resume, and connectivity return, WorkManager for best-effort retry only, account switching fences in-flight sync, Profile UI with preview/confirm and explicit retry. Evidence: JVM tests, no personal-device runs.
9. **U9 Full verification and review.** Web unit, Playwright, build; Android `:core:test`, `:app:testDebugUnitTest`, `assembleDebug`; independent review of the diff; completion predicate classified VERIFIED / NOT VERIFIED / INCONCLUSIVE.

Architecture review gate: protocol shapes were already compared (operation log vs vector clock vs whole-collection CAS) and synthesized in `docs/plans/web-account-sync.md`, which fixes tables, RLS, and RPC semantics. Before U2 implementation lands, an independent design review checks the concrete migration against the documented no-data-loss invariants; findings resolve before client work.

## Phase C. Execution loop

For each unit, state the hypothesis, make the smallest reversible change, observe RED for behavior tests when applicable, implement, and verify GREEN on the real target before starting the next unit. Keep remote provisioning behind an explicit plan/cost/isolation gate. No deployment, paid resource, existing-project mutation, or data deletion.

## Phase D. Decision trail

Append material decisions and evidence to `.audit/supabase-auto-sync.tsv`. Keep log local unless a later review requires it.

## Phase E. Handback

Verify the completion predicate against source, tests, and Supabase status. Mark each item VERIFIED, NOT VERIFIED, or INCONCLUSIVE. Report user-visible behavior, maintainer inheritance, billing/remote gates, and any unsupported closed-app behavior.
