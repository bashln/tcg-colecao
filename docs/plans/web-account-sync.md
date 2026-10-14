# Optional account and collection sync

## Implementation status (10 October 2026)

Account creation and real Android/web synchronization are **not available**. The web and Android stores now have local identity-scoped outboxes, and deterministic protocol fakes exercise retry/conflicts; there is no account UI, auth provider, HTTP transport, remote adapter, backend, or cross-device sync. Existing collections remain local-first and exportable. Treat every account/binding hook as internal groundwork, not a user-facing feature. The web app can edit while an already-open page is offline, but cannot cold-start/reload offline because its app shell is not cached.

## Problem

`web/` stores browser collection data in IndexedDB. Android stores collection rows in Room. Both use stable item IDs, local revisions, and uniqueness by `(cardId, variant, condition)`. Account sync must stay optional, preserve offline use, and never hide a concurrent edit or deletion. A Supabase project must be new and isolated. No project or billing change is part of this plan.

## Caller experience

Anonymous use remains unchanged. In Profile, a person chooses **Connect account**, reviews a preview of local and cloud collections, and confirms before any collection upload. Status shows pending operations, last successful sync, and conflicts. Sign-out stops remote access but retains local data.

```ts
const sync = createCollectionSync({ local, auth, remote })
const state = await sync.status()
const plan = await sync.prepareLink(account)
// UI shows local-only, cloud-only, equal, and conflicting items.
const result = await sync.confirmLink(plan.id, plan.digest)
await sync.syncNow()
await sync.resolve(conflict.id, { kind: 'keep-local' })
```

Android uses equivalent Kotlin methods at its composition root. Existing collection callers continue using their local repository; repository writes journal sync intent in the same local transaction only while an account is linked.

## Chosen shape

Use immutable per-item operations with a materialized current-item table and durable tombstones. Each operation has a stable `opId`, `deviceId`, `itemId`, `baseRevision`, and validated payload. Server sequence orders pulls; device clocks are display metadata only. `create`, `replace`, and `delete` are applied only if the base revision matches the current head. Replaying an identical `opId` returns its prior result. Reusing an ID with different content is rejected.

Edits and deletes use compare-and-swap against per-item server revision. Concurrent edits, edit/delete races, same-ID payload disagreement, and duplicate active configurations become visible conflicts. Never use last-write-wins. Never auto-sum two different item IDs during account linking: that could discard purchase-price provenance. Local `add` keeps existing aggregation behavior; a cross-device duplicate configuration is reviewed explicitly.

```ts
type CollectionOperation =
  | { kind: 'create'; opId: OpId; deviceId: DeviceId; item: SyncableItem }
  | { kind: 'replace'; opId: OpId; deviceId: DeviceId; item: SyncableItem; baseRevision: number }
  | { kind: 'delete'; opId: OpId; deviceId: DeviceId; itemId: ItemId; baseRevision: number }

type SyncResult =
  | { kind: 'complete'; applied: number; pending: number }
  | { kind: 'conflicts'; conflicts: SyncConflict[] }
  | { kind: 'offline'; pending: number }
  | { kind: 'error'; message: string }

interface CollectionSync {
  status(): Promise<SyncStatus>
  prepareLink(account: AuthenticatedAccount): Promise<LinkPlan>
  confirmLink(planId: PlanId, digest: string): Promise<LinkResult>
  syncNow(): Promise<SyncResult>
  listConflicts(): Promise<SyncConflict[]>
  resolve(id: ConflictId, resolution: Resolution): Promise<void>
  unlink(): Promise<void>
}
```

### First account link

1. Validate and snapshot the local collection. Keep a recoverable JSON backup and checksum.
2. Fetch the signed-in account's current remote heads. Do not accept a caller-supplied owner ID.
3. Build a preview: local-only, cloud-only, identical, same-ID/different-value, and duplicate-configuration rows. No writes occur during preview.
4. Bind preview to local checksum and remote high-water sequence. Ask for explicit confirmation. If either side changed, invalidate preview and rebuild it.
5. Apply conflict-free seed operations idempotently. Keep conflicting local and remote rows visible and recoverable. Never clear local data.
6. A lost response is retried with the same operation IDs, then reconciled from the server sequence before marking local outbox rows acknowledged.

Switching accounts always stops sync and requires a fresh preview. Data from account A is never submitted while account B is active. Unlinking retains local collection and backup.

## Ownership and modules

```text
web/src/collection/       IndexedDB item/card transactions and local outbox
web/src/sync/             protocol policy, coordinator, transport interface
web/src/account/          Auth session and explicit account switching
app/src/.../data/local/   Room item/outbox/conflict transactions and migration
app/src/.../data/sync/    Kotlin coordinator and authenticated transport
core/                     shared collection domain stays network-independent
sync-contract/fixtures/   versioned JSON contract vectors for TypeScript and Kotlin
supabase/migrations/      schema, RLS, RPCs; applied only to a new project
```

Only sync adapters call network APIs. Local repositories own collection writes and append outbox operations atomically. Auth tokens, card photos, catalog records, and market prices are not sync payloads.

## Supabase boundary

Use user-scoped `collections`, `collection_items`, `collection_operations`, and `sync_devices` tables. Keep tombstones in `collection_items`; append accepted operations with monotonically assigned server sequence. Add a unique partial index for active `(collection_id, card_id, variant, condition)` configurations.

Enable and force RLS on every user table. Clients get no direct mutation grants. Authenticated clients call narrow `SECURITY DEFINER` RPCs with fixed `search_path`, explicit `auth.uid()` ownership checks, bounded payload validation, and transactional operation-log plus projection writes. Revoke RPC access from `anon`; never accept `user_id` as an input. Account deletion cascades cloud data only after an explicit export/warning flow.

Proposed RPCs:

```text
prepare_collection_link(local_digest, local_items, remote_sequence) -> plan + preview
commit_collection_link(plan_id, digest) -> accepted operations + conflicts
push_collection_operations(device_id, operations) -> per-operation outcomes
pull_collection_operations(after_server_sequence, limit) -> page + high-water mark
resolve_collection_conflict(device_id, operation, conflict_ids) -> outcome
revoke_sync_device(device_id) -> status
```

Validate enum values, item IDs, quantity, safe money ranges, payload size, operation ID/hash reuse, configuration uniqueness, revision, device ownership, and protocol version inside the database boundary. Client-side validation is not authorization.

## Local schema and rollout

Web IndexedDB advances from v2 with `outbox`, `conflicts`, and `syncMeta` stores; existing `items`, `cards`, and `imports` remain intact. A linked item stores account scope, local revision, and last server revision. Android Room adds an exact expected-revision remove result, revision/scope metadata, outbox/conflict/meta entities, and a forward migration. Backups remain a separate recovery path.

Do not start account linking until both platform migrations are tested against populated databases. Keep the existing offline repositories usable before, during, and after account sign-out. Sync resumes on foreground/network regain and through a visible manual retry; never claim background sync when browser lifecycle cannot guarantee it.

## Verification gates

- Pure protocol fixtures run in TypeScript and Kotlin: invalid payload, exact replay, op-ID/hash mismatch, stale replace, stale delete, same-config race, conflict resolution, tombstone restore, and pagination.
- Browser tests use two IndexedDB-backed clients and deterministic in-memory transport. Prove atomic item+outbox writes, offline persistence, retry after lost response, account switching, conflict visibility, and no local mutation on failed link.
- Room tests cover populated vN migration, revision-checked remove, process restart, offline outbox, and duplicate config.
- Local PostgreSQL tests apply the actual SQL and exercise RLS with two accounts, anonymous refusal, forged IDs, transactional rollback, operation replay, digest expiry, and tombstone pull. Hosted Supabase Auth/RLS still requires a separate isolated-project gate.
- Final tests cover backup export/import and collection use with no account configured.
- Current web shell is not service-worker cached: offline CRUD is supported only while an already-open page remains loaded; offline cold start/reload is a separate P0 release gap.
- Current provider recommendation is a new isolated Supabase project for Auth/Postgres/RLS/RPC, with Vercel only a candidate for static frontend hosting. Verify current plan and commercial-use terms before any public deployment; no remote action is authorized here.

## Synthesis decision

Choose Terra's per-item operation log with revision-checked materialized rows. It matches exact-edit semantics, Android's local ownership boundary, and no-loss requirements with a smaller causal model than vector clocks. Graft Luna's explicit preview bound to local checksum and remote sequence, so first login cannot race a changing cloud collection. Keep DeepSeek's small transport seam and durable idempotency keys. Reject DeepSeek's automatic summing of distinct cross-device item IDs because it can erase paid-price provenance. Reject whole-collection snapshot CAS because unrelated edits would conflict and deletion history needs a second mechanism.

## Tradeoffs

- Keep operation history and tombstones until a separately verified compaction design exists; storage grows in exchange for replay and recovery.
- Surface conflicts rather than silently selecting a value or summing distinct item IDs.
- Add migrations to both local stores in exchange for offline atomic outboxes.
- Keep Auth provider and session storage behind platform adapters; do not make auth part of the core domain.
- Defer remote project creation until organization plan/cost is observable. Current CLI lists projects but not billing; Docker socket is unavailable locally.

## Not in scope for this phase

No Supabase project creation, migration application, production credentials, deploy, paid resource, public release, or collection upload. The first implementation unit is protocol fixtures plus a deterministic in-memory two-device simulator. Proceed to real remote transport only after plan cost and isolated project identity are verified.
