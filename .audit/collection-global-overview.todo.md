# Global collection ownership overview

## Feature playbook

- [x] 1. `how` over the affected subsystem.
- [x] 2. `architect` for parallel design exploration. Three candidates and an independent cross-judge completed; candidate 1 selected with targeted grafts.
- [x] 3. Write the throughput checkpoint as four todo items. A dimension that genuinely does not apply (single file, no fan-out) keeps its item with `n/a: <reason>` rather than being dropped:
  - [x] **Blocking first steps.** Gates run before fan-out. `Ai-memory, source tree, tests, and device constraints checked first.`
  - [x] **Independent workstreams.** Disjoint files, services, or layers parallelize. Shared writes serialize. `n/a: catalog completeness, ownership membership, and missing-list state are one feature contract.`
  - [x] **Shared mutable state.** Default to splitting the target (the **separate-before-serializing-shared-state** principle skill). Serialize only for real invariants. `Design candidates use private /tmp paths; code writer will receive isolated worktree.`
  - [x] **Smallest safe decomposition.** If one worker is best, name why. `One implementation owner must keep catalog completeness, aggregate state, and UI counts consistent.`
- [x] 4. Root owns implementation. A separate worktree could not include the extensive user changes already present in collection, repository, model, and resource files; copying that entire dirty state would raise loss/conflict risk. No files were reverted. Kept edits scoped to this feature and existing collection files.
- [ ] 5. Verify on the matching surface. "Inconclusive" or wrong-surface is not a pass. Flag it.
- [ ] 6. Rebase into small, ordered commits. Stack follow-ups.
- [ ] 7. If the design is contested, `interrogate` before shipping.
- [ ] 8. Run **Opening a PR**.

## Architect phases

- [x] Ground existing collection ownership and catalog completeness.
- [x] Sketch at least two structurally distinct global overview designs and cross-judge them.
- [x] Record selected design and rejected alternatives.
- [ ] Implement using TDD, one verifiable unit at a time.
- [ ] Verify local tests/build and user-facing states.
- [ ] Review the complete diff independently.
- [ ] Update project goal, progress, and decision trail.

## Current constraints

- Goal: `01a12357-4b04-7032-a0ad-9f86af1e9edf`.
- Source handoff: `handoffs/2026-10-10-pause.md` in ai-memory.
- Repo has pre-existing local edits. Preserve all of them.
- ADB sees `SM_A325M` Android 13, but scanner camera was active during inspection. Do not interrupt, install APK, or run connected tests until the device is idle and data risk is checked.
- Missing-card totals may use only `SetCatalogResult.Complete`; never derive missing card identities from `Card.setTotal` or a partial Room cache.
- No release, deploy, Supabase remote change, or user-data deletion.

## Throughput checkpoint

- **Blocking first steps.** Reread the ai-memory handoff and goal plan; trace catalog and collection boundaries; protect active device state before any install or instrumented test.
- **Independent workstreams.** n/a: global ownership totals and missing-card membership share one catalog-completeness contract and one CollectionViewModel state flow.
- **Shared mutable state.** Keep architecture candidates in separate `/tmp/opencode/arena-tcg-ownership/` paths. Do not copy the dirty checkout into a worktree; use current checkout with scoped edits because relevant upstream changes are already uncommitted.
- **Smallest safe decomposition.** One code owner is safest: catalog loading, aggregate state, and Compose rendering must agree on what counts as a verified missing card.

## Progress

- [x] Read handoff and verified current repo/goal state.
- [x] Trace CollectionScreen, CollectionViewModel, repository catalog contract, and tests.
- [x] Capture current A32 Collection empty state without installing or writing collection data.
- [x] Compare three design candidates and record candidate 1 plus targeted retry/search/mismatch grafts.
- [x] Implement domain projection and ViewModel catalog loading with focused JVM tests.
- [x] Render UI states, search, per-set retry, and missing-card navigation. Compose runtime remains unverified.
- [x] Run full JVM tests, compile Compose instrumented tests, and build APK.
- [x] Independent reviews found and drove fixes for UX gaps, selected-set retry, and false empty state before Room's first emission.
- [ ] Inspect current-code UI on isolated/idle Android surface; connected tests remain gated by user-data safety.
- [x] Final independent review completed after first-snapshot loading fix; no actionable findings.
