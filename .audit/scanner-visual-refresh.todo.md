1. State the exit condition as a checkable predicate before the first iteration (tests green, repro fixed, all N PRs merged, pixel-diff zero).
2. Pick the wake mechanism using Claude Code's `loop` skill (a built-in, not a pstack skill). An event to watch (CI, a merge, a ref advancing) gets a watcher subagent that wakes you on the event, with a long time-based heartbeat as fallback. No event gets a fixed-interval heartbeat sized to when the result is worth re-checking.
3. Each iteration makes the smallest change the evidence justifies, verifies it against the predicate, commits if it advanced, discards changes that didn't help. Belt-and-suspenders that "might help" gets reverted, not left to ride.
4. Mid-run discoveries are yours. Address broken skills, related bugs, flaky verifiers, review noise, tooling failures, orphaned follow-ups, and fixable drift yourself via poteto-mode. Put out-of-band fixes in their own PR. Do not park reversible work for the human or use `AskUserQuestion`. Surface only irreversible actions, genuine product or preference calls no experiment can settle, or a real dead end. Keep the predicate as the main drive, and return to it after each side fix.
5. Checkpoint every iteration via the **show-me-your-work** skill, a row for what changed and whether the predicate moved.
6. Stop when the predicate is met. A plateau is not a stop, so keep going and pivot your approach to push past it. Surface a genuine dead end rather than spinning, and never relax the predicate to declare victory.

- [x] Preserve database and package on device; avoid connected UTP runner that uninstalls app.
- [x] Normalize stage-label OCR contamination and verify stable scan on live camera.
- [x] Keep automatic shutter at two seconds, manual shutter, comparison, and alternatives.
- [x] Add continuous flash default-on, explicit toggle, unavailable/error state, and accessible action label.
- [x] Verify torch toggle and automatic result on SM-A325M with real card.
- [x] Run JVM suites and debug APK build; validate installed app remains alive and Room schema remains v8.
- [x] Independent diff review; resolve Critical/Important findings.
- [x] Record any remaining gates and report final evidence.

skip: periodic wake mechanism; no external asynchronous event or long-running process to poll.
