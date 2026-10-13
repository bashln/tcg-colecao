import { useMemo, useState } from 'react'
import type { CollectionEntry } from '../domain/model.ts'
import { listCollection } from '../collection/store.ts'
import { resolveCardForSync } from '../sync/cards.ts'
import { createSupabaseTransport, type LinkPreview, type PushCloudItem } from '../sync/supabase-transport.ts'
import type { SyncConflictDetail, SyncResult } from '../sync/coordinator.ts'
import type { SyncRunnerState } from '../sync/runner.ts'
import type { SyncBinding } from '../sync/outbox.ts'
import { confirmLink, linkBlockerMessage, linkBlockers, prepareLinkPreview } from './link.ts'
import type { CompleteLinkInput } from './link.ts'
import type { SupabaseWebAuth } from './auth.ts'

type Phase =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'preview'; preview: LinkPreview }
  | { kind: 'committing' }

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message.trim().length > 0 ? cause.message : fallback
}

function localOnlyCopy(count: number): string {
  return count === 1
    ? '1 posse só neste navegador e será enviada para a conta'
    : `${count} posses só neste navegador e serão enviadas para a conta`
}

function cloudOnlyCopy(count: number): string {
  return count === 1
    ? '1 posse só na nuvem será adicionada aqui'
    : `${count} posses só na nuvem serão adicionadas aqui`
}

/**
 * First-link surface: server preview first, explicit confirmation second, upload third.
 * A signed-in person without a binding always lands on the preview flow; after the
 * atomic completion succeeds the panel switches to the linked state.
 */
export function LinkPanel({ auth, identity, deviceId, entries, binding, onLinked, isIdentityCurrent, completeLink, syncState, onSyncNow, onResolveConflict }: {
  auth: SupabaseWebAuth
  identity: string
  deviceId: string
  entries: CollectionEntry[]
  binding: SyncBinding | null
  onLinked: () => void
  isIdentityCurrent: () => boolean
  completeLink: (input: CompleteLinkInput) => Promise<boolean>
  syncState: SyncRunnerState
  onSyncNow: () => void
  onResolveConflict: (conflictId: string, resolution: 'keep-local' | 'keep-cloud') => Promise<void>
}) {
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })
  const [error, setError] = useState('')
  const transport = useMemo(() => createSupabaseTransport({ http: auth }), [auth])
  const busy = phase.kind === 'loading' || phase.kind === 'committing'
  const blocker = phase.kind === 'preview' ? linkBlockers(phase.preview) : null

  if (binding !== null && binding.identity === identity) {
    return <div className="link-status" role="status">
      <p><strong>Coleção vinculada a esta conta.</strong></p>
      <SyncStatusPanel state={syncState} entries={entries} onSyncNow={onSyncNow} onResolveConflict={onResolveConflict} />
      <p className="storage-footnote">As alterações feitas neste navegador sincronizam automaticamente com a conta enquanto a página estiver aberta e on-line. Sair da conta preserva a coleção local.</p>
    </div>
  }

  const startPreview = async () => {
    setPhase({ kind: 'loading' })
    setError('')
    try {
      const preview = await prepareLinkPreview(transport, await listCollection(), identity)
      setPhase({ kind: 'preview', preview })
    } catch (cause) {
      setPhase({ kind: 'idle' })
      setError(errorMessage(cause, 'Não foi possível gerar a prévia do vínculo. Verifique a conexão e tente novamente.'))
    }
  }

  const confirm = async () => {
    if (phase.kind !== 'preview') return
    setPhase({ kind: 'committing' })
    setError('')
    const result = await confirmLink({
      transport,
      identity,
      deviceId,
      preview: phase.preview,
      readEntries: () => listCollection(),
      resolveCard: resolveCardForSync,
      isIdentityCurrent,
      completeLink,
    })
    if (result.kind === 'linked') {
      setPhase({ kind: 'idle' })
      onLinked()
      return
    }
    setPhase({ kind: 'idle' })
    setError(result.message)
  }

  const cancel = () => {
    if (busy) return
    setPhase({ kind: 'idle' })
    setError('')
  }

  if (phase.kind === 'preview' || phase.kind === 'committing') {
    const preview = phase.kind === 'preview' ? phase.preview : null
    const sendCount = preview?.localOnly.length ?? 0
    return <section className="link-preview" role="region" aria-label="Prévia do vínculo" aria-busy={phase.kind === 'committing'}>
      <h3>Prévia do vínculo</h3>
      <p>Nada foi enviado até aqui. Confira o que acontece ao confirmar:</p>
      <ul className="link-summary">
        <li>{localOnlyCopy(preview?.localOnly.length ?? 0)}</li>
        <li>{cloudOnlyCopy(preview?.cloudOnly.length ?? 0)}</li>
        <li>{(preview?.identical.length ?? 0) === 1
          ? '1 posse já existe idêntica nas duas pontas'
          : `${preview?.identical.length ?? 0} posses já existem idênticas nas duas pontas`}</li>
      </ul>
      {blocker !== null ? <p className="form-error" role="alert">{linkBlockerMessage(blocker)}</p> : null}
      {error !== '' ? <p className="form-error" role="alert">{error}</p> : null}
      <div className="form-actions">
        <button type="button" className="primary-button" disabled={busy || blocker !== null} onClick={() => { void confirm() }}>
          {phase.kind === 'committing'
            ? 'Vinculando...'
            : `Confirmar vínculo${sendCount > 0 ? ` e enviar ${sendCount} ${sendCount === 1 ? 'posse' : 'posses'}` : ''}`}
        </button>
        <button type="button" className="secondary-button" disabled={busy} onClick={cancel}>Cancelar prévia</button>
      </div>
      <p className="storage-footnote">Ao confirmar, a coleção local passa a ser sincronizada com esta conta. Você pode sair da conta depois sem apagar as cartas deste navegador.</p>
    </section>
  }

  return <div className="link-offer">
    <p>Sua coleção continua somente neste navegador até você revisar e confirmar o vínculo. Entrar não envia cartas.</p>
    {error !== '' ? <p className="form-error" role="alert">{error}</p> : null}
    <button type="button" className="primary-button" disabled={busy} onClick={() => { void startPreview() }}>
      {phase.kind === 'loading' ? 'Gerando prévia...' : 'Revisar vínculo'}
    </button>
    <p className="storage-footnote">{entries.length === 1 ? '1 posse local será comparada com a coleção da conta' : `${entries.length} posses locais serão comparadas com a coleção da conta`} antes de qualquer envio.</p>
  </div>
}


const CONFLICT_REASON_LABELS: Record<string, string> = {
  'stale-revision': 'esta posse mudou em outro dispositivo',
  'duplicate-configuration': 'a configuração já existe na coleção da conta',
  'revision-limit': 'atingiu o limite de edições',
  'id-collision': 'conflitou com um identificador existente',
  'different-content': 'tem versões diferentes',
}

function conflictDetailLine(detail: SyncConflictDetail, entries: CollectionEntry[]): string {
  const entry = entries.find((candidate) => candidate.item.id === detail.itemId)
  const name = entry === undefined ? detail.itemId : `${entry.card.name} (${entry.card.collectorNumber})`
  const reason = CONFLICT_REASON_LABELS[detail.reason] ?? `recusada: ${detail.reason}`
  return `${name}: ${reason}`
}

function pushCloudValue(item: PushCloudItem | undefined): string {
  if (item === undefined || item === null || item.kind === 'missing') return 'ausente'
  if (item.kind === 'deleted') return 'removida na nuvem'
  return `${item.item.quantity} · ${item.item.variant} · ${item.item.condition}${item.item.pricePaid === null ? '' : ` · ${item.item.pricePaid.amount.toFixed(2)} ${item.item.pricePaid.currency}`}`
}

function conflictValues(detail: SyncConflictDetail): string {
  if (detail.source === 'push' && detail.pushConflict !== undefined) {
    const local = detail.local === null || detail.local === undefined
      ? 'ausente'
      : `${detail.local.quantity} · ${detail.local.variant} · ${detail.local.condition}${detail.local.pricePaid === null ? '' : ` · ${detail.local.pricePaid.amount.toFixed(2)} ${detail.local.pricePaid.currency}`}`
    const cloud = pushCloudValue(detail.pushConflict.cloudItem)
    const conflicting = detail.pushConflict.reason === 'duplicate-configuration'
      ? ` Configuração existente na nuvem: ${pushCloudValue(detail.pushConflict.conflictingItem)}.`
      : ''
    return `Local: ${local}. Nuvem: ${cloud}.${conflicting}`
  }
  if (detail.local === undefined && detail.remote === undefined) return ''
  const value = (item: import('../sync/policy.ts').SyncItem | null | undefined) => item === null || item === undefined
    ? 'ausente'
    : `${item.quantity} · ${item.variant} · ${item.condition}${item.pricePaid === null ? '' : ` · ${item.pricePaid.amount.toFixed(2)} ${item.pricePaid.currency}`}`
  const remote = detail.remote?.kind === 'delete' ? null : detail.remote?.item
  return `Local: ${value(detail.local)}. Nuvem: ${value(remote)}.`
}

function canResolvePushConflict(detail: SyncConflictDetail): boolean {
  if (detail.source !== 'push') return detail.remote !== undefined
  return detail.pushConflict?.rejectedOperation !== null &&
    ['stale', 'missing', 'deleted', 'id-collision', 'duplicate-configuration'].includes(detail.reason)
}

function permanentConflictCopy(reason: string): string | null {
  if (reason === 'invalid-payload') return 'Esta operação contém dados inválidos. Sua coleção local está salva; atualize a posse manualmente antes de sincronizar novamente.'
  if (reason === 'device-revoked') return 'Este dispositivo perdeu autorização para sincronizar. Sua coleção local está salva; entre novamente na conta e tente sincronizar.'
  if (reason === 'revision-limit') return 'Esta posse atingiu o limite de revisões do servidor. Nenhuma versão será escolhida automaticamente; sua coleção local permanece salva.'
  return null
}

function SyncStatusPanel({ state, entries, onSyncNow, onResolveConflict }: {
  state: SyncRunnerState; entries: CollectionEntry[]; onSyncNow: () => void
  onResolveConflict: (conflictId: string, resolution: 'keep-local' | 'keep-cloud') => Promise<void>
}) {
  const [resolvingConflictId, setResolvingConflictId] = useState<string | null>(null)
  const [resolutionError, setResolutionError] = useState('')
  const result: SyncResult | null = state.result
  return <div className="sync-status">
    <p aria-live="polite">
      {state.running ? 'Sincronizando com a conta...'
        : result === null ? 'Verificando sincronização...'
        : result.kind === 'complete'
          ? (result.pending === 0
            ? 'Sincronizado. Nenhuma alteração pendente.'
            : `Sincronizado. ${result.pending === 1 ? '1 alteração aguardando envio' : `${result.pending} alterações aguardando envio`}.`)
        : result.kind === 'conflicts'
          ? (result.conflicts === 1
            ? `1 conflito de sincronização precisa da sua atenção. ${result.pending === null ? 'A contagem de alterações pendentes está indisponível.' : 'A posse continuou local e pendente.'}`
            : `${result.conflicts} conflitos de sincronização precisam da sua atenção. ${result.pending === null ? 'A contagem de alterações pendentes está indisponível.' : 'As posses continuaram locais e pendentes.'}`)
        : result.kind === 'offline'
          ? result.pending === null
            ? 'Sem conexão agora. Não foi possível verificar a fila de envio; sua coleção local permanece segura. Tente novamente quando a conexão voltar.'
            : 'Sem conexão agora. As alterações ficam pendentes e serão enviadas quando a conexão voltar.'
          : result.pending === null
            ? 'Não foi possível verificar a fila de envio. Sua coleção local permanece segura; tente sincronizar novamente.'
            : 'Não foi possível sincronizar agora. Suas alterações continuam pendentes e nada foi perdido.'}
    </p>
    {result !== null && result.kind === 'conflicts' ? <ul className="link-summary">
      {result.details.map((detail) => <li key={detail.id ?? detail.itemId}>
        {conflictDetailLine(detail, entries)}{conflictValues(detail) === '' ? '' : <span className="conflict-values"> {conflictValues(detail)}</span>}
        {detail.reason === 'duplicate-configuration' ? <p className="storage-footnote">Manter minha coleção substitui o identificador local pelo identificador da nuvem. Usar versão da nuvem mantém a posse existente e remove a duplicata local.</p> : null}
        {permanentConflictCopy(detail.reason) !== null ? <p className="form-error" role="alert">{permanentConflictCopy(detail.reason)}</p> : null}
        {resolvingConflictId === detail.id ? <p aria-live="polite">Aplicando escolha e confirmando na nuvem…</p> : null}
        {resolutionError !== '' && resolvingConflictId === detail.id ? <p role="alert" className="form-error">{resolutionError}</p> : null}
        {detail.id !== undefined && canResolvePushConflict(detail) ? <div className="conflict-actions" aria-label="Resolver conflito" aria-busy={resolvingConflictId === detail.id}>
          {(['keep-local', 'keep-cloud'] as const).map((choice) => <button key={choice} type="button" className="secondary-button"
            disabled={state.running || resolvingConflictId !== null}
            onClick={() => {
              setResolvingConflictId(detail.id ?? null)
              setResolutionError('')
              void onResolveConflict(detail.id!, choice).catch(() => setResolutionError('Não foi possível concluir a escolha. A resolução continuará disponível para tentar novamente.'))
                .finally(() => setResolvingConflictId(null))
            }}>
            {choice === 'keep-local' ? 'Manter minha coleção' : 'Usar versão da nuvem'}
          </button>)}
        </div> : null}
      </li>)}
    </ul> : null}
    <button type="button" className="secondary-button" disabled={state.running} onClick={onSyncNow}>
      {state.running ? 'Sincronizando...' : 'Sincronizar agora'}
    </button>
  </div>
}
