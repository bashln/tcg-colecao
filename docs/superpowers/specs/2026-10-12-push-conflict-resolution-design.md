# Push conflict resolution design

## Goal

Let a person recover from a sync operation rejected by the server without retrying the same stale operation forever or losing either version silently. Web and Android must show the local value and the current cloud value, then apply an explicit, durable choice.

## Current behavior

`push_collection_operations` writes rejected operations to `sync_conflicts`. Its result includes the operation ID, status, reason, and sometimes revision, but not the conflict row ID or current cloud item. The Web and Android coordinators expose push refusals only for the current sync run. They do not save them as resolvable local conflicts, so the operation remains in the outbox and is retried on the next sync.

The existing local conflict flow handles an operation pulled from the server log. Such a conflict has a server sequence and a remote operation. A rejected push has neither: the server does not add the rejected operation to the log. The existing `resolve_collection_conflict` RPC only marks a row resolved and cannot safely reconcile either collection.

## Constraints

1. Never clear unrelated local collection rows or outbox operations.
2. Never claim that a rejected operation was applied.
3. Do not retry a rejected operation unchanged after the person resolves it.
4. Bind every read and resolution to the authenticated owner and the expected account identity.
5. Make retries of conflict reads and resolutions idempotent.
6. Keep the local collection usable offline. A failed cloud read must preserve the local item and pending operation.
7. Do not use personal accounts, privileged client credentials, confirmation bypasses, remote ACL changes, or hosted schema changes for local verification.

## Design

### Return durable conflict details from push

For each content conflict, `push_collection_operations` returns the persisted conflict ID, reason, current server sequence, rejected operation, and authoritative cloud state. For a same-item conflict, cloud state is that item, including tombstones. For a duplicate-configuration conflict, cloud state includes the existing item that owns the conflicting configuration.

The RPC continues to store the rejected operation in `sync_conflicts`. It must not append a rejected operation to `collection_operations` or advance the sequence for that rejection. A retry with the same owner and operation ID reuses the same unresolved conflict row instead of creating duplicates, and refreshes its cloud snapshot. The result uses a typed status so clients distinguish content conflicts from malformed payloads and revoked devices.

The response carries enough state to render a resolution immediately. A separate read is not required for the normal path. If the response is lost, a new owner-scoped RPC lists unresolved push conflicts with their rejected payload and current cloud state. That RPC refreshes cloud state at read time because the collection may have changed since the conflict row was created.

### Persist conflicts locally

Web and Android save each push conflict with its server conflict ID, account identity, rejected operation, local item, current cloud item or tombstone, reason, and observed sequence. They associate the conflict with the rejected outbox operation. Saving the conflict must not acknowledge or remove that operation.

On later syncs, clients do not resend operations associated with unresolved server conflicts. They can still send unrelated pending operations. When the server reports that a conflict is already resolved, clients refresh the conflict and local state instead of creating a second choice.

### Resolve explicit choices

The UI uses the current conflict comparison and the existing `Manter minha coleção` and `Usar versão da nuvem` actions. Both actions require a fresh server conflict snapshot before commit. If the conflict changed, the client refreshes the comparison and asks the person to choose again.

For either choice, the server first records an immutable resolution result but keeps the conflict pending local acknowledgment. The client applies that result in one local transaction, then acknowledges local completion to the server. The conflict read returns any resolution result that has not received this acknowledgment. A crash or lost response therefore replays the same result instead of repeating the rejected operation or losing the user's choice.

For `keep-cloud`, the client applies the authoritative cloud representation locally and removes only superseded pending operations for the affected item IDs in the same local transaction. For a duplicate-configuration conflict, the UI identifies both item IDs and warns that keeping the cloud version removes the local conflicting entry from this device. It does not remove the remote item.

For `keep-local`, the server resolution RPC creates a new operation against the current cloud revision, using a new operation ID and the current registered device. It records the resolution result before returning it. Retrying the same conflict resolution returns the same operation and result. The client applies the resulting operation locally only after server acknowledgment, then removes the rejected operation and records local completion atomically. A separate acknowledgment marks the server conflict resolved after that local transaction succeeds.

For duplicate configuration, `keep-local` updates the existing cloud item's content using its cloud item ID. The local item is mapped to that ID, preserving the chosen collection content while respecting the server's unique configuration rule. The UI explains that item identity may change on this device.

### Separate content conflicts from permanent errors

Only conflicts with a valid local value and a readable current cloud state offer these two choices. `invalid-payload` remains a repair error and does not offer a resolution that would resend invalid data. `device-revoked` asks the person to reauthorize the device or create a new device identity. `revision-limit` does not offer `keep-local` until the server has a safe revision rollover design. Every permanent error remains visible and preserves local data.

### Idempotent server resolution

The resolution RPC accepts a conflict ID, a choice, and the registered device ID. It verifies `auth.uid()`, conflict ownership, unresolved state, current cloud revision, and device status in one transaction. It records the selected resolution and its durable result. Concurrent resolution attempts serialize on the conflict row. Repeating the same choice returns the recorded outcome. A different choice after resolution starts returns `already-resolved` with the recorded choice; it never applies a second choice.

An acknowledgment RPC accepts the conflict ID and the recorded resolution ID. It marks the conflict resolved only after the client confirms that its local transaction committed. The acknowledgment is owner-scoped and idempotent. If the client crashes before acknowledgment, the conflict read returns the same resolution result for local replay.

The RPC does not trust client-supplied cloud values or base revisions. It reads them from the database while holding the relevant row locks. It returns `stale-conflict` if state changed before the transaction could apply the resolution, allowing the client to refresh and ask again.

### User feedback

Show each conflict's item name, reason, and local/cloud values. State when an action changes item identity or removes the local conflicting entry. During refresh or resolution, show progress and prevent duplicate choices. On network or storage failure, keep both versions visible and offer retry. Never show a zero pending count when the outbox could not be read.

## Verification

Local PostgreSQL tests cover conflict IDs and snapshots, no sequence advance on rejection, owner isolation, duplicate configuration, stale snapshots, revoked devices, idempotent same-choice retries, and resolution replay before local acknowledgment. Web and Android tests cover outbox exclusion, durable conflict persistence, identity changes, local transaction rollback, both choices, lost responses, crash-before-ack replay, and idempotent acknowledgment. UI tests verify the explanation for duplicate configuration and permanent errors.

Run Web unit tests, production build, Chromium E2E, the PostgreSQL harness, Android core and app unit tests, and `assembleDebug`. Hosted two-account verification remains a separate gate requiring two controlled, email-confirmed accounts and SMTP/domain support.

## Acceptance criteria

- A rejected operation remains in local storage but is not resent while its durable conflict is unresolved.
- A conflict shows authoritative cloud state and the rejected local value.
- Resolving `keep-cloud` removes only affected local outbox operations and preserves unrelated work.
- Resolving `keep-local` records a new server operation against the latest cloud revision and safely maps duplicate configurations to the existing cloud ID.
- Same-choice retries return one durable outcome and never duplicate operations.
- A server-side resolution remains replayable until the client acknowledges its local transaction.
- Changed cloud state invalidates the old comparison and requires a new explicit choice.
- Invalid payloads, revoked devices, and revision-limit conflicts do not expose unsafe resolution actions.
- Account switches cannot read, resolve, or apply another account's conflict.
- Local failures preserve both the local collection and unresolved operation.

## Out of scope

- Hosted SMTP/domain configuration or hosted round-trip tests without controlled accounts.
- Automatic last-write-wins or automatic merging of quantities.
- Resolving revision-limit conflicts through revision rollover.
- Clearing a collection or unrelated pending operations during conflict recovery.
