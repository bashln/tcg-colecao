> Superseded on 2026-10-12 during account-sync review follow-up. The implementation and blockers below are historical, not current. See `progress.json`, `todo.md`, and `.audit/supabase-auto-sync.tsv` for current status. Since this snapshot, Web/Android preview and sync wiring landed, local gates were rerun, F1/F2/F4/F5/F6/F7/F14 review fixes were made, and hosted round-trip remains blocked by missing confirmed test accounts/SMTP/domain. The earlier statement that the Android link RPC and lifecycle were incomplete is no longer current.

# Account sync checkpoint for shutdown

Captured 2026-10-12. Worktree: `work/collection-foundation-2026-10-11`.

## Completed and verified in this run

- Web email confirmation callback validates the bearer token through `/auth/v1/user`, stores session only after validation, clears the URL fragment, and does not link or upload the collection.
- Web sync transport supports server link preview, owner-only snapshot read at the preview high-water, canonical local digest, explicit commit request, and strict parsing of preview/snapshot data.
- Added `read_collection_link_snapshot` migration and applied it to hosted project `fwzzwqakrvogafqiatgo`. Supabase migration list records it. Hosted `collection_items` count was zero at last check.
- IndexedDB `completeSyncLink` compares current local snapshot before atomically importing remote rows, setting account/device binding, and advancing cursor. Browser tests prove stale local state refuses link without mutation.
- Android auth, encrypted session, RPC transport, Room sync store, coordinator, and Account UI foundation exist. The real link RPC and lifecycle wiring are not complete.
- Local validation passed: web unit 41/41, Chromium E2E 22/22, Postgres harness 34/34, Android `:app:testDebugUnitTest` and `:app:assembleDebug`, `git diff --check`, and JSON parse of progress/errors.

## Still open

- Web Profile has no preview/confirm UI. The web coordinator is not composed with auth, real transport, and lifecycle triggers.
- Android AccountViewModel's preview dialog is local-only. It does not call preview/commit RPCs; coordinator and automatic triggers are not wired at app composition.
- No authenticated two-account isolation test or Android↔web round-trip has run. Do not use personal collection.
- Supabase advisors still show anon-executable `public.rls_auto_enable()` SECURITY DEFINER (pre-existing), six `auth_rls_initplan` findings, one unindexed FK, and two unused indexes. Do not change remote security grants without reviewing the event trigger and its intended caller.
- Read-only SQL follow-up found `rls_auto_enable()` returns PostgreSQL `event_trigger` and is registered as `ensure_rls` on `ddl_command_end`; its ACL grants EXECUTE to anon and authenticated. A direct SQL `SELECT` attempt failed with `cannot display a value of type event_trigger`. This does not yet prove whether PostgREST exposes an invokable RPC route; no permission change was made.
- Custom SMTP credentials and production domain are unavailable. Current Auth callback allow-list is localhost-only.
- Do not publish or deploy before all safety gates pass.

## Background work

Goal Worker `ses_ed2107652ffeHx48P42TtOtnO1` was launched to continue implementation in this worktree. It was still unfinished when this checkpoint was written. Do not treat its launch as completion. Resume by checking its completion notification and inspecting the actual diff, `progress.json`, `errors.json`, and SDD ledger before trusting any summary.

## Resume order

1. Verify background Goal Worker has stopped or completed; inspect worktree state and preserve all unrelated changes.
2. Finish web preview/confirm UI and compose transport, IndexedDB adapter, and coordinator with single-flight triggers for local write, foreground, and online events.
3. Replace Android placeholder link dialog with server preview and explicit commit; wire Room store, coordinator, transport, auth, and app lifecycle.
4. Add deterministic tests for switching identities, logout, retry, offline recovery, conflicts, stale preview, and restart.
5. Run all local suites. Then use isolated test accounts/devices for authenticated isolation and bidirectional round-trip. Report any external credential/domain blocker exactly.
6. Review Supabase advisor findings and the `rls_auto_enable()` grant path. No release until all gates pass.

## Git safety

Changes remain uncommitted. Preserve untracked `codex-session-01a12357-4b04-7032-a0ad-9f86af1e9edf.md` and `supabase/.temp/`. No release, deploy, or push was performed in this run.
