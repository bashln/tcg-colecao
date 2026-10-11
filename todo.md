# Phase 1: Price snapshots

- [x] 1. `how` over the affected subsystem.
- [x] 2. `architect` for parallel design exploration.
- [x] 3. Write the throughput checkpoint as four todo items. A dimension that genuinely does not apply (single file, no fan-out) keeps its item with `n/a: <reason>` rather than being dropped:
  - [x] **Blocking first steps.** Gates run before fan-out.
  - [x] **Independent workstreams.** Disjoint files, services, or layers parallelize. Shared writes serialize.
  - [x] **Shared mutable state.** Default to splitting the target (the **separate-before-serializing-shared-state** principle skill). Serialize only for real invariants.
  - [x] **Smallest safe decomposition.** If one worker is best, name why.
- [x] 4. Delegate code-writing to a subagent using your configured feature model (default in poteto-mode's Models section) with a specific scope (file paths, named data shape and its organizing structure per **principle-model-the-domain**, a state machine over scattered booleans, a table/registry over branching, a typed model over repeated shape assumptions, chosen before the delegate writes logic, and success criteria). When the implementation admits multiple valid shapes (error handling, abstraction layer, test structure), delegate via the **arena** skill instead so the runners surface the alternatives and the cross-judge guards the pick. Mandatory: no skip-with-reason escape, and Laziness Protocol does not override it (the gain is review separation, not lines saved). A subagent forbidden to spawn satisfies this by owning the diff directly with the same review separation. No "standing by" reply that waits on a nested agent. **Give every file-writing delegate its own worktree** (spawn it with `isolation: "worktree"`, or hand it an exclusive branch), and do not write files or run a suite in a worktree a delegate still holds. Fencing a file in the brief's prose is not a lock (**principle-separate-before-serializing-shared-state**). Comments per **Comments**. Surgical edits, re-ground against the source for upstream-derived files. Port shared-primitive improvements to all consumers and verify each. Commit liberally.
- [x] 5. Verify on the matching surface. "Inconclusive" or wrong-surface is not a pass. Flag it.
- [ ] 6. Rebase into small, ordered commits. Stack follow-ups.
  - skip: `.git` is read-only in this environment, so commits and rebasing are unavailable.
  Use the **sequence-verifiable-units** skill, building, verifying, and committing each small unit before the next.
- [x] 7. If the design is contested, `interrogate` before shipping.
  - n/a: the independent design judge selected a synthesis with no unresolved disagreement.
- [ ] 8. Run **Opening a PR**.
  - skip: `.git` is read-only here, and this request did not authorize publishing a remote PR.
- [x] **Blocking first steps.** Check repository state and trace the current card-price write path before the isolated implementation starts.
- [ ] **Independent workstreams.** n/a: one change spans the Room schema, repository capture, and an app-facing observable; splitting these would split one atomic data contract.
- [x] **Shared mutable state.** The implementation owns an isolated checkout under `/tmp`; main remained untouched until review.
- [ ] **Smallest safe decomposition.** One implementation owner is safest because the migration and snapshot write path share the same Room schema and transaction assumptions.
- [x] Define and persist daily card price snapshots while preserving the current latest-price cache.
- [x] Verify migration from the currently shipped database and repeated same-day refresh behavior.
- [x] Review the isolated diff and prepare the following phase, which will render portfolio history from stored snapshots.
- [x] Comment review found no additions to remove or `MUST KILL` flags.
- [ ] skip: repository `.git` is read-only in this environment, so no commit or PR can be created here.

# Phase 2: Home price-history chart

- [x] 1. Ground current snapshot capture, repository reads, currency rules, and Home UI through the `how` exploration.
- [x] 2. Sketch at least two distinct chart/data-flow designs with the `architect` arena; select one against accessible states, data correctness, and API depth.
- [x] 3. Agree: proceed without a human checkpoint under the existing full-autonomy grant.
- [x] 4. Implement the chosen domain aggregation and accessible Home chart, preserving source/currency and stating the historical-composition limit.
- [x] 5. Scrap and redesign if implementation requires repeated special cases or if available snapshots cannot support the claimed metric.
  - n/a: the implementation matched the selected design and the stored snapshots support only the explicitly labeled current-holdings estimate.
- [ ] Verify JVM/domain behavior, Android tests/build, and rendered UI without instrumented uninstall on a device containing user data.
  - JVM and build pass. ADB installed the APK without clearing data. Physical screenshot showed the empty-collection state, so chart rendering with data is still unverified.
- [x] Independent `no-comments` review of the S3 diff.
  - Done: unused `Surface` import and unused `observedAt` projection field removed; full unit suite passed.

# Phase 3: Web stack and collection experience

- [x] `how` over the affected subsystem.
- [x] `architect` for parallel design exploration.
- [x] Write the throughput checkpoint as four todo items. A dimension that genuinely does not apply (single file, no fan-out) keeps its item with `n/a: <reason>` rather than being dropped:
  - [x] **Blocking first steps.** Gates run before fan-out.
  - [x] **Independent workstreams.** Disjoint files, services, or layers parallelize. Shared writes serialize.
  - [x] **Shared mutable state.** Default to splitting the target (the **separate-before-serializing-shared-state** principle skill). Serialize only for real invariants.
  - [x] **Smallest safe decomposition.** If one worker is best, name why.
- [x] Delegate code-writing to a subagent using your configured feature model with a specific scope, named data shape and organizing structure, and success criteria. Give each writing delegate an isolated worktree or private patch artifact.
- [x] Verify on the matching surface. "Inconclusive" or wrong-surface is not a pass. Flag it.
  - Chromium desktop/mobile verified manual search, add, reload persistence, exact edit, confirmed removal, and layout at 390 px. Real TCGdex, camera/OCR, Safari, and iPhone remain unverified.
- [ ] Rebase into small, ordered commits. Stack follow-ups.
  - skip: `.git` is read-only in this environment, so commits and rebasing are unavailable.
- [x] If the design is contested, `interrogate` before shipping.
  - n/a: the independent judge and parent review selected the same base and the judge recorded why its numeric score did not determine the migration choice.
- [ ] Run **Opening a PR**.
  - skip: `.git` is read-only here, and this request did not authorize publishing a remote PR.

- [x] 1. Ground the existing Android/domain/API boundaries and the required iPhone web workflow.
- [x] 2. Sketch at least two structurally different web architectures with `architect`/`arena`, then compare them against camera access, local-first data, OCR recovery, shared-domain cost, accessibility, and future sync.
- [x] 3. Agree: choose the web approach under the full-autonomy grant; do not touch remote Supabase.
  - Decision: React + TypeScript + Vite; retain Android's native build, port parser/ranker against shared fixtures, and keep browser data local until a separately gated Supabase phase.
- [x] 4. Implement the first usable collection workflow and scan confirmation from the accepted desktop/mobile concepts.
  - Manual search, confirmation, editing, and local persistence pass in Chromium. Photo capture and OCR are implemented but unverified on a real device.
- [x] 5. Scrap and redesign if camera, OCR, local storage, and collection editing require duplicated state owners or repeated adapters.
  - n/a: the validated cut keeps domain, IndexedDB, TCGdex, and recognition separate without a second collection owner.
- [x] Verify collection search, edit, removal, reload persistence, removal-dialog keyboard focus, scan return navigation, and 390 px controls in Chromium.
  - Screenshots: `/tmp/tcg-web-mobile.png` and `/tmp/tcg-web-collection-mobile.png`. Tests intercept catalog responses.
- [ ] Verify the live TCGdex endpoint, camera, OCR, Safari/iPhone, permissions, orientation, and screen-reader use.
- [x] Add GitHub Actions for web tests, production build, and downloadable `dist/` artifact.
  - Workflow file is written; remote execution is pending.
- [ ] Implement validated JSON import before public web release.
- [ ] Independent review before shipping the slice.

- [x] **Blocking first steps.** Confirm repository scope and trace Android card, OCR, collection, and build boundaries before choosing a web shape.
- [x] **Independent workstreams.** Architecture candidates used separate output files and a separate cross-judge; implementation ownership follows synthesis.
- [x] **Shared mutable state.** Candidates write separate `/tmp` artifacts; do not overlap source edits with other work.
- [x] **Smallest safe decomposition.** One web owner can keep IndexedDB transactions, collection rules, and views behind the collection domain boundary; camera/OCR and catalog have separate browser-specific owners.
# Coleção Android, refinamento de UX

- [x] **Blocking first steps.** Gates run before fan-out. Inspecionar o estado atual, preservar o fluxo e evitar qualquer operação que desinstale ou altere dados do aparelho.
- [x] **Independent workstreams.** Disjoint files, services, or layers parallelize. `n/a: o refinamento mantém uma única tela Compose e seu teste existente; a instrução multi-agent desta sessão proíbe delegação.`
- [x] **Shared mutable state.** Default to splitting the target (the **separate-before-serializing-shared-state** principle skill). `n/a: não há escrita concorrente nem arquivo compartilhado com outro executor.`
- [x] **Smallest safe decomposition.** If one worker is best, name why. `Um responsável altera a seleção Cartas/Expansões em CollectionScreen e atualiza o teste Compose focado, mantendo o ViewModel e o estado de domínio existentes.`
- [x] Transformar os controles compactos em abas de largura integral para deixar claro que Cartas e Expansões são modos alternativos.
- [x] Atualizar as paletas claro/escuro e o ícone para uma identidade teal própria alinhada às referências, sem copiar marca ou conteúdo.
- [x] Criar teste JVM para proteger o contraste WCAG AA dos tokens de texto e superfície.
- [x] Compilar app e testes instrumentados; não executar instrumentação conectada no aparelho pessoal.
- [x] Adicionar teste Compose para o estado selecionado em Cartas/Expansões e compilar o teste.
- [ ] Executar teste Compose e inspecionar visualmente no A32 quando um dispositivo estiver acessível, sem tocar nos dados pessoais.

# Phase 4: Web backup import and restore

- [x] **Blocking first steps.** Inspect checkout, preserve unrelated work, capture web build/unit/Chromium baselines, and read current web persistence/export format.
- [x] **Independent workstreams.** `n/a: one import operation spans external JSON validation, IndexedDB migration, atomic persistence, UI feedback, and browser behavior.`
- [x] **Shared mutable state.** Keep all imported collection writes inside one IndexedDB readwrite transaction and do not let UI manipulate database records.
- [x] **Smallest safe decomposition.** Put versioned payload parsing in `web/src/collection/backup.ts`, transaction ownership in `store.ts`, and file selection/feedback in Profile.
- [x] **Data shape.** `CollectionBackup` is a strict versioned record of `{item, card}` entries; import markers map source item IDs to stable item signatures.
- [x] Add a Playwright behavior test and observe expected RED before implementation.
- [x] Implement schema validation, idempotent merge, explicit conflicts, recoverability, and v1-to-v2 IndexedDB migration.
- [x] Independent review caught replay duplication and revision overflow; add behavior tests and fix both findings.
- [x] Verify migration v1-to-v2, collection recovery, replay idempotency, conflicts, overflow refusal, and mobile flow in Chromium.
- [x] Read UI as first-time user and update Portuguese README/verification evidence.
- [x] Re-review final import behavior and inspect exact diff without disturbing pre-existing work.
- [ ] `BLOCKED: no-comments specialist unavailable in this OpenCode agent registry; do not claim its comment audit ran.`

# Phase 5: Optional account and Android-web sync design

- [x] **Blocking first steps.** Trace Android Room and repository contracts, browser IndexedDB ownership, current project list, and current Supabase security/pricing docs without changing remote state.
- [x] **Independent workstreams.** Compare three isolated sync designs using DeepSeek V4.1, GPT-6 Luna, and GPT-5.6 Terra; no source writers overlap.
- [x] **Shared mutable state.** Keep candidate artifacts in separate `/tmp/opencode/arena-sync/` paths; no one may touch the application checkout during design.
- [x] **Smallest safe decomposition.** Design one cross-platform snapshot protocol, with platform-specific Auth/session storage and one versioned Postgres write boundary.
- [x] Synthesize designs against data loss, RLS isolation, Android/web fit, recovery, and local verification criteria.
- [x] Write accepted sync design and phase plan before changing app or remote database.
- [x] Define a pure operation contract and test replay, stale writes, tombstones, and revision overflow before integrating storage.
- [x] Review the pure policy and remove mutable payload aliasing.
- [ ] Integrate the policy with a deterministic two-device fake transport and atomic local outbox.
- [ ] Implement only reversible local code/migrations until plan and cost are observable.
- [ ] `BLOCKED: remote project creation and live auth/sync require organization billing confirmation; Supabase CLI does not expose plan, and Docker socket is denied.`

> Review note: final re-review for import found one size-limit mismatch, which was fixed by sharing a tested 50 MiB export/import boundary. A later policy review caught mutable payload aliasing, fixed with a defensive copy and regression test.

# Phase 6: Android ↔ web sync (conta opcional)

- [x] **Contrato RPC inspecionado** e travado: `push_collection_operations` / `pull_collection_operations` (SECURITY DEFINER), RLS por `auth.uid()`, CAS por item, tombstone e cursor por sequência.
- [x] **`SupabaseSyncTransport` (Android) TDD RED→GREEN**: serialização de create/replace/delete, assinatura SHA-256 canônica (igual à web) para detectar reuso de `opId`, mapeamento applied/already-applied/conflict/rejected, erros tipados `UNAUTHORIZED`/`DEVICE_REVOKED`/`UNAVAILABLE`, recusa de sessão anônima antes de rede, cursor de pull por `serverSequence`.
- [x] **Room `SyncLocalStore`**: aplicação remota autoritativa e atômica (item + cursor na mesma transação), replay idempotente por cursor monotônico por identidade (tabela `sync_cursor`, migração 4→5), ack isolado e proteção contra troca de identidade; colisão de configuração falha sem sobrescrever dados locais.
- [x] **Auth core Android**: `AuthController` (restore/refresh de sessão, signUp auto-confirm vs aguardando verificação, signIn, signOut best-effort que nunca toca a coleção) + `SupabaseAuthClient` (GoTrue `/auth/v1`) com erros não-revelatórios; sessão persistida via boundary `SessionStore`. UI de conta, armazenamento seguro de plataforma e prévia de vínculo ficam na próxima unidade.
- [ ] **Acionadores automáticos**: sync após edição local, ao retomar o app e ao restaurar rede enquanto ativo; preservar modo local-only offline e no sign-out.
- [ ] **Reconciliar dinheiro**: web/kotlin usam `Double`, o servidor grava `bigint`; decidir unidades menores para não truncar centavos.
- [ ] **Migração/RLS/RPC aplicadas** ao projeto isolado `fwzzwqakrvogafqiatgo` e **roundtrip real entre dois clientes** (não mocks) antes de afirmar sync completo.
