# Collection set progress

- [x] 1. `how` over the affected subsystem (two independent read-only explorers).
- [x] 2. `architect` for design exploration. One candidate completed; second runner and cross-judge blocked by session thread limit. Architecture comparison remains provisional.
- [x] 3. Write the throughput checkpoint as four todo items. A dimension that genuinely does not apply (single file, no fan-out) keeps its item with `n/a: <reason>` rather than being dropped:
  - [ ] **Blocking first steps.** Gates run before fan-out.
  - [ ] **Independent workstreams.** Disjoint files, services, or layers parallelize. Shared writes serialize.
  - [ ] **Shared mutable state.** Default to splitting the target (the **separate-before-serializing-shared-state** principle skill). Serialize only for real invariants.
  - [ ] **Smallest safe decomposition.** If one worker is best, name why.
- [x] 4. Code authored by root because no delegate slot was available. Independent architecture candidate and post-implementation comment review supplied review separation. No commit because `.git` is read-only.
- [x] 5. JVM tests and Android debug build pass. Four Compose instrumented tests passed on Galaxy A32 before the final VM-only search-reset test; physical UI inspection showed empty collection. Latest ADB server unavailable, so final build was not installed on device.
- [ ] 6. Rebase into small, ordered commits. Stack follow-ups.
  - skip: `.git` is read-only in this environment, so commits and rebasing are unavailable.
  Use the **sequence-verifiable-units** skill, building, verifying, and committing each small unit before the next.
- [x] 7. No contested choice surfaced; independent architecture panel incomplete, so keep design provisional.
- [ ] 8. Run **Opening a PR**.
  - skip: `.git` is read-only here, and this request did not authorize publishing a remote PR.

- [ ] **Blocking first steps.** Inspect the reference screenshots, current collection/data flow, API set-query behavior, and existing tests before delegating.
- [ ] **Independent workstreams.** n/a: set catalog completeness, owned-card membership, and missing-card UI form one feature contract.
- [ ] **Shared mutable state.** Keep implementation isolated in `/tmp`; apply its reviewed patch to this workspace only after the delegate finishes.
- [ ] **Smallest safe decomposition.** Use one implementation owner because API mapping, cache completeness, and collection progress share the same exact-set membership invariant.
- [x] Define a typed set-progress data shape and exact-set membership rule before implementation.
- [x] Implement set progress and missing-card discovery for sets represented in the user's collection.
- [x] Verify repository behavior, loading/empty/offline/error states, Android build, and the real device UI; latest device install is still pending.
- [x] Run independent comment review and inspect its report. It found two P2 issues, both fixed locally. A fresh review after fixes remains pending.
- [x] Update project progress and vision records without marking future web/Supabase decisions as resolved.
- [ ] Add reviewer fixes: clear search on section change and intercept Android Back while a set detail is open. JVM tests and build pass after fixes.
