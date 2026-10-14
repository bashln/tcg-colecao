# Handoff for 2026-10-10 (updated 13:57 local)

## Current objective

Continue TCG Pokémon Collection as a free/open-source Collectr alternative. Android and web/iPhone should eventually share each user's collection. Keep Supabase and web repository/KMP choices future-facing until architecture is decided.

## Work completed in this session

- Implemented U1 collection expansion progress and missing-card discovery. Details and decisions are in `collection-set-progress-design.md` and `collection-set-progress.todo.md`.
- Added typed complete/unavailable set catalog, exact set-ID filtering, distinct owned-card counting, and `Todas`/`Tenho`/`Faltam` filters. Offline catalog failure does not imply missing cards.
- Independent comment review found two P2 navigation issues. Fixed locally: switching Cards/Expansions clears stale search; Android Back closes selected expansion.
- Verified `:core:test`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin`, and `:app:assembleDebug` with `--rerun-tasks`. All 7 `CollectionScreenTest` instrumentation tests passed on Galaxy A32 (SM-A325M, Android 13), including main collection-grid tap, unavailable-catalog fallback tap, and owned/missing expansion-card taps. The APK debug was reinstalled and launched afterward. Physical screenshots still only cover the empty collection; instrumentation data was isolated and did not modify personal collection data.
- Implemented E1 exact collection-item editing. The distinct `collection-item/{itemId}` route edits quantity, variant, condition, and paid price; it never calls `add()`. Room preserves item ID/date and rejects stale snapshots and occupied `(cardId, variant, condition)` keys. Collection/Home/set views now open the exact owned item, with a picker when a set row represents multiple configurations.
- Verification passed: full JVM/debug/androidTest compile and APK build with `--rerun-tasks`; 1 `CollectionItemEditNavigationTest`, 8 `CollectionScreenTest`, and 3 `HomeScreenTest` instrumentation tests passed on A32. Room/domain/ViewModel tests cover collision, stale update, idempotent retry, removal, identity preservation, and clearing paid price. Independent no-comments review of E1 found no actionable comments or suppressions.
- A later full connected instrumentation run stalled at `EndToEndRecognitionTest` before reporting completion. Do not treat it as pass or product failure; investigate this test separately. Reinstalled and launched the debug APK on the A32 afterward.
- Re-ran `EndToEndRecognitionTest` alone. XML reports 1 test, 0 failures, duration 286.517 s; logs report main top-1 45/46 (97.8%), holdout top-1 35/40 (87.5%), and holdout top-5 38/40 (95.0%). UTP logged a gRPC `CANCELLED` during result recording/teardown, so the Gradle task exit itself is not confirmed.
- UTP logs show `uninstall_after_test: true` for both `app.tcg.app` and its test package. The debug APK was reinstalled and launched afterward. The existing handoff says the personal collection was empty, but future connected instrumentation must use an isolated device/profile or protect local app data first.
- Last `git diff --check` and JSON parse should be rerun at the next checkpoint. Latest instrumentation XML: `app/build/outputs/androidTest-results/connected/debug/TEST-SM-A325M - 13-_app-.xml`; Gradle reports are under `app/build/reports/tests/testDebugUnitTest/` and `core/build/reports/tests/test/`; APK is `app/build/outputs/apk/debug/app-debug.apk`.
- No commit, PR, release, Supabase changes, or web scaffold was made. `.git` is read-only. Existing GitHub release workflow produces signed APKs from tags; do not publish a new release without user approval.

## Next steps

1. Run `git diff --check`, `jq empty progress.json`, and the JVM/debug build suite.
2. Protect app-local data before further connected instrumentation. UTP removes the target app when it finishes.
3. Use an isolated fixture/repository to inspect the edit screen on the A32; do not alter the user's saved collection.
4. Implement the Home price-history graph from existing Room observations. Label it as an estimate for the current collection composition, keep USD and EUR separate, and state when there is too little history.
5. Review combined local work carefully. Preserve earlier S3 and U1 changes. `.git` is read-only; no commits/releases were made.
6. Keep S1 photo-under-variable-light evidence open. Supabase CLI is connected, but project, Auth, RLS and sync choices remain unresolved. Do not change remote Supabase or publish without specific approval.

## Existing external handoff

Earlier handoff ID: `01a12356-e8cb-7583-afb8-cd53e7f17106`.

Persistent goal ID: `01a12357-4b04-7032-a0ad-9f86af1e9edf` (active; this is not a background runner). Current project checklist: `.audit/project-goal.todo.md`; phased plan: `docs/plans/GOAL.md`; decision log: `.audit/project-goal-decisions.tsv`.

Exact-edit architecture comparison: `/tmp/tcg-edit-architecture-a.md`, `/tmp/tcg-edit-architecture-b.md`, `/tmp/tcg-edit-architecture-judge.md` (temporary; synthesized contract is in `docs/plans/collection-item-edit.md`).
