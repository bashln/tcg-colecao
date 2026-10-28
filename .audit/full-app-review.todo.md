# Full app review

## Poteto-mode checklist

- [x] Read the Principles section of the poteto-mode skill.
- [x] Phase A: Frame. State a falsifiable definition of done, quantify scope and effort, and identify blockers before starting the run.
- [x] Phase B: Design the workflow. Decompose the work into atomic, independently verifiable units; order the riskiest unknown first; capture a baseline before fixes.
- [x] Phase C: Run the loop. For each unit, state the hypothesis, make the smallest evidence-based change, verify the real artifact, and keep or revert it.
- [x] Phase D: Keep the audit trail. Log decisions as they happen in the canonical TSV.
- [x] Phase E: Verify and hand back. Check the whole result against the definition of done and record any gap.

## Throughput checkpoint

- [x] **Blocking first steps.** Capture branch, dirty work, module inventory, build paths, and current available verification before any new fix.
- [x] **Independent workstreams.** Review Android and core, web, Supabase, then build/release configuration in separate serial phases so each result can be checked before the next.
- [x] **Shared mutable state.** One checkout and one branch are shared. Make one scoped edit at a time and run its available check before editing the next area.
- [x] **Smallest safe decomposition.** Kept one owner for the cross-repository audit and dispatched only the trail and comment reviews required by applied skills.

## Review and correction plan

- [x] Inspect repository instructions, current diff, project boundaries, CI commands, dependency manifests, and known verification limits.
- [x] Review Android and core domain, persistence, network, sync, auth, pricing, backup, update, scan, navigation, and UI code for reproducible defects.
- [x] Review web domain, persistence, sync, auth, scan, UI, browser setup, and accessibility behavior.
- [x] Review Supabase migrations, RLS policies, RPCs, and local harness assumptions without changing hosted data.
- [x] Review release guards, CI permissions, scripts, dependency pinning, and build configuration.
- [x] Fix confirmed issues in small units, preserving unrelated dirty work and avoiding speculative refactors.
- [x] Verify each fix using the applicable build or static check. Do not run test suites unless the user explicitly asks for tests or verification.
- [x] Inspect the final diff, status, and audit trail; report fixes, evidence, and remaining blockers.

## Investment stats architecture checkpoint

- [x] Architect phase 1, Ground the affected calculation and its UI callers.
- [x] Architect phase 2, Sketch types and function/module boundaries using multiple candidates.
- [x] Architect phase 3, Agree. No user checkpoint was requested, so proceed after synthesis.
- [x] Architect phase 4, Implement against the selected sketch.
- [x] Architect phase 5, Scrap and redesign only if implementation shows the sketch is wrong.
- [x] Arena phase 1, Frame the artifact, rubric, and isolated candidate outputs.
- [x] Arena phase 2, Fan out independent design candidates.
- [x] Arena phase 3, Cross-judge the candidates.
- [x] Arena phase 4, Pick the strongest design against the rubric.
- [x] Arena phase 5, Graft useful details from rejected candidates.
- [x] Arena phase 6, Verify the synthesized design and implementation.

## Definition of done

Review risk-bearing paths across Android, core, web, Supabase, and release automation, including collection valuation, asynchronous cancellation, local persistence, sync and auth boundaries, RLS and RPC ownership checks, and user-facing coverage disclosures. Correct each confirmed, locally fixable defect found. Record an available non-test verification for each correction, inspect the final diff and workspace status, and state any blocker. Do not change hosted data, open a PR, merge, or release.

## Baseline and constraints

- Base commit: `e904938`.
- Pre-existing tracked change: `app/src/main/kotlin/app/tcg/app/ui/collection/CollectionScreen.kt`, from the prior collection layout fix.
- Pre-existing untracked audit files: `.audit/price-cache-shipping.tsv` and `.audit/sem-preco.todo.md`; preserve them.
- A local debug Kotlin compilation passed after the collection layout fix.
- The web production build passed before this audit's fixes.
- The review covers 129 source files and about 22,950 lines, plus five workflows and release scripts.
- Per developer instruction, do not add or run test suites unless the user explicitly asks for tests or verification.
- Android emulator AVD `TcgPriceCheck` cannot connect in this environment. Hardware acceleration is unavailable and software emulation remained offline.
- Test suites are excluded by the developer instruction unless the user explicitly asks to test or verify.

## Gaps recorded

- This was a risk-focused source review across the named boundaries, workflows, and migrations. It was not a line-by-line reading of all 129 inventoried files.
- Test suites were not executed by instruction. The emulator remained offline, so visual runtime behavior could not be rechecked.
- Supabase SQL was inspected statically only. No local PostgreSQL harness or hosted database was run or changed.
