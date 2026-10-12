import { useMemo, useState } from 'react'
import type { CollectionEntry } from '../domain/model.ts'
import { completeSyncLink, listCollection } from '../collection/store.ts'
import { resolveCardForSync } from '../sync/cards.ts'
import { createSupabaseTransport, type LinkPreview } from '../sync/supabase-transport.ts'
import type { SyncConflictDetail, SyncResult } from '../sync/coordinator.ts'
import type { SyncRunnerState } from '../sync/runner.ts'
import type { SyncBinding } from '../sync/outbox.ts'
import { confirmLink, linkBlockerMessage, linkBlockers, prepareLinkPreview } from './link.ts'
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
export function LinkPanel({ auth, identity, deviceId, entries, binding, onLinked, syncState, onSyncNow }: {
  auth: SupabaseWebAuth
  identity: string
  deviceId: string
  entries: CollectionEntry[]
  binding: SyncBinding | null
  onLinked: () => void
  syncState: SyncRunnerState
  onSyncNow: () => void
}) {
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })
  const [error, setError] = useState('')
  const transport = useMemo(() => createSupabaseTransport({ http: auth }), [auth])
  const busy = phase.kind === 'loading' || phase.kind === 'committing'
  const blocker = phase.kind === 'preview' ? linkBlockers(phase.preview) : null

  if (binding !== null && binding.identity === identity) {
    return <div className="link-status" role="status">
      <p><strong>Coleção vinculada a esta conta.</strong></p>
      <SyncStatusPanel state={syncState} entries={entries} onSyncNow={onSyncNow} />
      <p className="storage-footnote">As alterações feitas neste navegador sincronizam automaticamente com a conta enquanto a página estiver aberta e on-line. Sair da conta preserva a coleção local.</p>
    </div>
  }

  const startPreview = async () => {
    setPhase({ kind: 'loading' })
    setError('')
    try {
      const preview = await prepareLinkPreview(transport, await listCollection())
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
      completeLink: completeSyncLink,
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

function SyncStatusPanel({ state, entries, onSyncNow }: {
  state: SyncRunnerState; entries: CollectionEntry[]; onSyncNow: () => void
}) {
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
            ? '1 conflito de sincronização precisa da sua atenção. A posse continuou local e pendente.'
            : `${result.conflicts} conflitos de sincronização precisam da sua atenção. As posses continuaram locais e pendentes.`)
        : result.kind === 'offline'
          ? 'Sem conexão agora. As alterações ficam pendentes e serão enviadas quando a conexão voltar.'
          : 'Não foi possível sincronizar agora. Suas alterações continuam pendentes e nada foi perdido.'}
    </p>
    {result !== null && result.kind === 'conflicts' ? <ul className="link-summary">
      {result.details.map((detail) => <li key={detail.itemId}>{conflictDetailLine(detail, entries)}</li>)}
    </ul> : null}
    <button type="button" className="secondary-button" disabled={state.running} onClick={onSyncNow}>
      {state.running ? 'Sincronizando...' : 'Sincronizar agora'}
    </button>
  </div>
}
