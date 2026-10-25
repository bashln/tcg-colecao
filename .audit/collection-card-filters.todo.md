# Android collection card filters (variant + condition)

Playbook: Feature (`poteto-mode/playbooks/feature.md`), steps copied verbatim.

- [x] 1. `how` over the affected subsystem.
- [x] 2. `architect` for parallel design exploration. skip: subagent cannot spawn; the data shape is fixed by the brief, so there is no fork to explore.
- [x] 3. Write the throughput checkpoint as four todo items. A dimension that genuinely does not apply (single file, no fan-out) keeps its item with `n/a: <reason>` rather than being dropped:
  - [x] **Blocking first steps.** Gates run before fan-out.
  - [x] **Independent workstreams.** Disjoint files, services, or layers parallelize. Shared writes serialize.
  - [x] **Shared mutable state.** Default to splitting the target. Serialize only for real invariants.
  - [x] **Smallest safe decomposition.** If one worker is best, name why.
- [x] 4. Delegate code-writing to a subagent ... skip: this agent is forbidden to spawn; it owns the diff directly with the same review separation.
- [x] 5. Verify on the matching surface.
- [x] 6. Rebase into small, ordered commits. Stack follow-ups. One commit `94428d2`.
- [x] 7. If the design is contested, `interrogate` before shipping. skip: no contested design.
- [x] 8. Run **Opening a PR**. skip: parent owns the PR; this agent only commits on its branch.

## Throughput checkpoint

- **Blocking first steps.** Read the collection ViewModel, screen, tests, string catalog, and the `CardVariant`/`Condition` enums before editing.
- **Independent workstreams.** n/a: one feature, one owner, no subagents.
- **Shared mutable state.** n/a: single owner, single worktree, no concurrent writers.
- **Smallest safe decomposition.** One owner, because the ViewModel state shape and the screen contract are the same decision.

## Task items

- [x] ViewModel: `CollectionCardFilters` holder, filtered visible entries, unfiltered summary, reset on section/lens.
- [x] Unit tests: variant, condition, combined, clear, section/lens reset, unfiltered totals.
- [x] UI: filter chips in owned-cards section, clear action, strings.
- [x] androidTest compiles against the new controls.
- [x] Verification command + `git diff --check`, then commit.
