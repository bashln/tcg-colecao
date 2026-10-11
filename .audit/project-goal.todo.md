# TCG Coleção autonomous run

## Autonomous-run playbook

- [x] State the exit condition as a checkable predicate before the first iteration (tests green, repro fixed, all N PRs merged, pixel-diff zero).
- [ ] Pick the wake mechanism using Claude Code's `loop` skill (a built-in, not a pstack skill). An event to watch (CI, a merge, a ref advancing) gets a watcher subagent that wakes you on the event, with a long time-based heartbeat as fallback. No event gets a fixed-interval heartbeat sized to when the result is worth re-checking.
- [x] Each iteration makes the smallest change the evidence justifies, verifies it against the predicate, commits if it advanced, discards changes that didn't help. Belt-and-suspenders that "might help" gets reverted, not left to ride. Sequence the work via the **sequence-verifiable-units** principle skill, verifying each unit before the next instead of batching checks at the end.
- [x] Mid-run discoveries are yours. Address broken skills, related bugs, flaky verifiers, review noise, tooling failures, orphaned follow-ups, and fixable drift yourself via poteto-mode. Put out-of-band fixes in their own PR. Do not park reversible work for the human or use `AskUserQuestion`. Surface only irreversible actions, genuine product or preference calls no experiment can settle, or a real dead end. Keep the predicate as the main drive, and return to it after each side fix.
- [x] Checkpoint every iteration via the **show-me-your-work** skill, a row for what changed and whether the predicate moved.
- [ ] Stop when the predicate is met. A plateau is not a stop, so keep going and pivot your approach to push past it. Surface a genuine dead end rather than spinning, and never relax the predicate to declare victory.

## Project work

## Next-slice architecture workflow

- [x] Ground exact-item editing through the repository/data model and UI/navigation flow.
- [x] Produce and compare two distinct architecture sketches plus an independent cross-judge.
- [x] Implement one exact-item update boundary and repository tests, rejecting uniqueness collisions.
- [x] Wire a distinct edit route/form by item ID, then verify tests/build and A32 flow.
- [x] Reconcile the design record and handoff; do not commit or publish.

- [x] Create persistent goal `01a12357-4b04-7032-a0ad-9f86af1e9edf` with product acceptance criteria and external-action gates.
- [x] Replace the stale S1-only `docs/plans/GOAL.md` with a phased project plan aligned to `docs/vision.md`.
- [x] Confirm the two previous P2 fixes, make checklist cards owned or missing clickable, and add behavior instrumentation.
- [x] Pass full JVM/debug/androidTest compile suite and run seven collection UI tests on the Galaxy A32.
- [x] Reinstall verified debug APK after the instrumented run removed the target package; verify actual empty Home and Collection screens.
- [x] Finish independent no-comments review of the latest owned-card click change; no findings.
- [ ] Close the U1 populated-screen evidence gap without modifying the user's collection.
- [x] Build the collection management increment with safe editing of existing entries.
- [ ] Continue one verified feature increment at a time toward the persistent goal.
- [ ] Keep release, remote Supabase setup, deletion/migration, and public deployment behind explicit approval.

## Throughput checkpoint

- Blocking first steps. Read and preserve the existing S3/U1 diffs. Confirmed the U1 navigation behavior through five instrumented tests on the actual phone before opening the next feature.
- Independent workstreams. Independent review can run beside the implementation. Collection editing spans a shared item model, unique Room key, repository transaction, view model, and screen, so those writes stay with one owner per branch.
- Shared mutable state. The repo has a user-owned dirty worktree and `.git` is read-only. No worker writes to the shared worktree. Review reports and design candidates write separate `/tmp` artifacts.
- Smallest safe decomposition. Land one verified collection behavior at a time. Start with exact-item edit semantics before adding batch operations or web sync.

## Evidence and open constraints

- Goal: persistent goal ID above.
- Decision trail: `.audit/project-goal-decisions.tsv`.
- Project plan: `docs/plans/GOAL.md`.
- Android screenshot: `/tmp/tcg-a32-collection-20261010.png`.
- Instrumented report: `app/build/outputs/androidTest-results/connected/debug/TEST-SM-A325M - 13-_app-.xml`.
- E1 now uses exact `CollectionItem.id`, a distinct edit route, and a transactional update; changing variant/condition into an occupied composite key is rejected until merge semantics are specified.
- Supabase CLI is connected, but no project or schema has been selected. No remote changes were made.
- The repo's `.git` directory is read-only here, so no commit, branch, or PR can be created from this environment.
- No scheduled loop/watcher tool is available in the current tool set. The persistent goal carries the objective across sessions, but it does not execute work while no agent turn is running.
- Latest UI verification: 1/1 `CollectionItemEditNavigationTest`, 8/8 `CollectionScreenTest`, and 3/3 `HomeScreenTest` passed on `SM-A325M` Android 13. Current debug APK reinstalled and launched afterward.
- Exact-edit implementation and decisions are recorded in `docs/plans/collection-item-edit.md`; tests cover collision, stale data, idempotence, removal, identity preservation, and price clearing.
- The full connected suite stopped during `EndToEndRecognitionTest` without a final result. Treat it as inconclusive and run that test separately before relying on full-suite status.
