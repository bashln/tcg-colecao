# Supabase auth and automatic Android/web sync

- [x] Read the Principles section of the poteto-mode skill and apply relevant principles.
- [x] Phase A: Frame a falsifiable completion predicate, scope, effort, rigor, and blockers.
- [x] Phase B: Explore existing app/auth/storage boundaries; compare designs for remote Auth, RLS/RPC, and automatic sync.
- [ ] Phase B: Record a four-part throughput checkpoint covering blockers, independent workstreams, shared state, and safe decomposition.
- [ ] Phase B: Write a reviewable implementation sequence and keep remote creation gated on verified plan, cost, and isolation.
- [ ] Phase C: Build the smallest verification harness first; implement one locally verifiable unit at a time with RED/GREEN evidence.
- [ ] Phase C: Validate migrations, auth boundaries, RLS/RPC isolation, replay, conflict, logout/account switch, and automatic triggers on Android and web.
- [ ] Phase D: Keep `.audit/supabase-auto-sync.tsv` append-only, with evidence for each material decision and checkpoint.
- [ ] Phase E: Verify the real artifacts and report VERIFIED, NOT VERIFIED, or INCONCLUSIVE for every completion predicate.
- [ ] Do not alter existing Supabase projects, incur paid services, deploy, or push without verified authorization and gates.
- [ ] Do not commit or push during main cleanup; preserve existing changes and work only on `work/collection-foundation-2026-10-11`.
- [ ] Account for browser limitations: automatic sync while active/resumed is required; background while closed is best-effort only.
