# Account Sync Completion Design

## Goal

Finish optional authenticated Android↔Web collection sync with safe account isolation, recoverable first-link commits, visible conflict resolution, and a reproducible local verification path. A hosted two-account round-trip remains a separate external gate until controlled confirmed accounts and SMTP/domain access are available.

## Current State

- The account and sync foundation is implemented and pushed on `work/collection-foundation-2026-10-11`.
- Web, Android, and local PostgreSQL suites pass at current recorded counts.
- First-link preview is read-only and commit requires explicit confirmation.
- Android/Web sync requests bind expected user identity to the captured authenticated session.
- Review found gaps around uncertain/partial link commits, stale previews across account changes, conflict visibility/resolution, pending counts, digest parity, unused RPC sequence input, and unbounded link-plan retention.

## Constraints

1. Collection remains available offline and is never cleared by sign-out, account switch, unlink, or link recovery.
2. No operation for account A may be sent or applied while bound to account B.
3. Initial linking always requires a current server preview and explicit user confirmation.
4. A server response may be described as successful only when every seed operation is accepted or already applied and local binding/cursor commit succeeds.
5. A timed-out request is uncertain, not proof of failure or success. Retries must be safe and must not duplicate operations.
6. Conflicting local and remote content remains inspectable until the user explicitly chooses a resolution. No implicit last-write-wins behavior.
7. Auth confirmation remains enabled. Never use personal accounts, privileged client credentials, or remote ACL weakening for verification.
8. Do not publish a release/deploy as part of this implementation. Hosted two-account verification may remain externally blocked and must be reported as such.

## Design

### 1. Recoverable link commit contract

Treat a first-link plan as an idempotency key for one confirmed attempt. `commit_collection_link` must serialize concurrent commits per plan and persist its terminal response. Repeating the same plan/digest/device/operations returns the recorded result; a different payload for the same plan is rejected. The response distinguishes `linked`, `partial`, and pre-commit refusals. `linked` is returned only when every operation outcome is `applied` or `already-applied`; `partial` carries operation IDs, reasons, sequences, and the updated high-water mark. Do not consume a plan before durable result state can be recorded.

After `partial` or a lost response, both clients retain local data and binding state, offer a fresh preview, and clearly explain that some cloud operations may already exist. A fresh preview reconciles already-applied rows as identical. Any content mismatch is shown as a conflict; retry never reuses a stale local digest or silently adopts remote content.

Expired/consumed link plans need bounded retention. Add an owner-safe cleanup mechanism for expired plans and consumed plans after their idempotent-result retention window. It must be safe to run repeatedly and must not touch collection rows or operation history.

### 2. Conflict visibility and resolution

Every failed pull-side apply must produce a typed conflict with the item ID, reason, local value, and incoming value when available; cursor advancement must stop at the first unapplied sequence. The account/collection UI shows individual conflicts and offers explicit `keep local` or `keep cloud` resolution. A resolution is an authenticated, owner-scoped operation; it updates the chosen local representation and emits/acknowledges the required operation before advancing the cursor. Unresolved records stay visible and retryable. Dismissal is not offered unless it preserves both versions and has a user-readable recovery path.

The same conflict model covers a partially committed first link. Do not tell users to adjust local data without showing the conflicting local/cloud rows and a path to resolve them.

### 3. Identity and lifecycle correctness

Account identity changes invalidate in-memory previews and pending link plans in both clients. A link/unlink transition is serialized per device: unlink cancels or waits for in-flight link persistence; a late persistence cannot recreate a binding after sign-out. Binding persistence failure leaves UI unlinked and reports an actionable partial-link state. Local device identity remains stable across process restart and backup/restore fallback paths.

### 4. Shared wire and bounded contracts

Add cross-platform digest vectors, not only operation-signature vectors. Kotlin and TypeScript must hash identical canonical item bytes for empty, reordered, Unicode-ID, null-price, and monetary boundary cases. Sorting and number serialization are specified independent of device locale. Remove `p_remote_sequence` from the preview RPC contract because server high-water is authoritative, using one migration and updating all clients/tests together.

Pending counts shown in every status derive from actual outbox state or a trustworthy coordinator result; an unexpected exception must not display zero when operations remain queued.

### 5. Verification and release boundary

Tests cover RPC transaction/idempotency and owner isolation in local PostgreSQL, transport mappings on Android/Web, conflict resolution and retry behavior in local stores, and user-visible previews/error states in unit/E2E tests. The final report separates local proof from hosted proof. Hosted two-account round-trip is the only external gate if controlled confirmed accounts remain unavailable; it is never represented as passed by local harness tests.

## Acceptance Criteria

- Repeating a link commit after response loss returns the same durable outcome and creates no duplicate operations.
- Partial seed outcomes never produce a linked UI state; both platforms show what may already exist remotely and guide a safe fresh preview.
- Account switch invalidates any prior account's preview and blocks its commit before network dispatch.
- Unlink and link persistence cannot race into a stale bound state after sign-out.
- Pull conflicts identify both sides and preserve cursor position until explicit resolution.
- Conflict resolution is owner-scoped, idempotent, and never silently discards either version.
- Link-plan cleanup is bounded, owner-safe, and idempotent.
- Android and Web digest bytes/hash match the shared fixtures, including reorder and monetary edge cases.
- The unused remote-sequence argument is removed from SQL, Android, Web, and tests in the same change set.
- Pending counts reflect real queued operations after sync exceptions.
- Web unit/build/E2E, Android core/app/build, and PostgreSQL harness all pass.
- No release/deploy, personal account, service-role client credential, or Auth-confirmation bypass is used.

## Out of Scope

- Hosted Auth configuration, SMTP/domain provisioning, creating or deleting Auth users, changing remote ACLs, or running the hosted two-account round-trip without explicit operational prerequisites.
- WebKit/iPhone validation where the browser runtime is unavailable.
- Performance-advisor index changes without workload evidence.
- Release publication or deployment.
