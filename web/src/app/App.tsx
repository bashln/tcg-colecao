import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { getCatalogCard, getSetCoverage, listSets, searchCatalog } from '../catalog/tcgdex.ts'
import type { CatalogResult, SetOption } from '../catalog/tcgdex.ts'
import { isCollectionBackupSizeAllowed } from '../collection/backup.ts'
import { addToCollection, currentSyncBinding, editCollectionItem, importCollectionBackup, listCollection, observeCollectionChanges, removeCollectionItem, setSyncBinding } from '../collection/store.ts'
import {
  CONDITIONS, CURRENCIES, VARIANTS, collectionEstimate, conditionLabels, draftFromItem, emptyDraft,
  formatMoney, missingFromCompleteCatalog, parseItemDraft, quoteFor, variantLabels,
} from '../domain/model.ts'
import type { CardSummary, CardVariant, CollectionEntry, Condition, Currency, ItemDraft, SetCoverage } from '../domain/model.ts'
import { rankCandidates, recognizePhoto } from '../scan/recognition.ts'
import { CardArtwork, Icon, ItemFields, QuantityControl } from '../ui/components.tsx'
import type { IconName } from '../ui/components.tsx'
import { browserAuthStorage, SupabaseWebAuth, type AuthState } from '../account/auth.ts'
import { LinkPanel } from '../account/LinkPanel.tsx'
import type { SyncBinding } from '../sync/outbox.ts'
import { browserDeviceId } from '../sync/device.ts'
import { resolveCardForSync } from '../sync/cards.ts'
import { createSyncCoordinator } from '../sync/coordinator.ts'
import { createIndexedDbSyncLocalStore } from '../sync/indexeddb-local.ts'
import { createSyncRunner, type SyncRunnerState } from '../sync/runner.ts'
import { createSupabaseTransport } from '../sync/supabase-transport.ts'

type Screen =
  | { kind: 'home' }
  | { kind: 'collection' }
  | { kind: 'scan' }
  | { kind: 'profile' }
  | { kind: 'catalog'; photoUrl: string | null }
  | { kind: 'confirm'; card: CardSummary; alternatives: CardSummary[]; photoUrl: string | null }
  | { kind: 'edit'; entry: CollectionEntry }

type LoadState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T }
  | { kind: 'error'; message: string }

type SearchState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'results'; result: Extract<CatalogResult<CardSummary[]>, { kind: 'complete' | 'partial' }> }
  | { kind: 'error'; message: string }

const navigation: { kind: 'home' | 'collection' | 'scan' | 'profile'; label: string; icon: IconName }[] = [
  { kind: 'home', label: 'Início', icon: 'home' },
  { kind: 'collection', label: 'Coleção', icon: 'cards' },
  { kind: 'scan', label: 'Escanear', icon: 'camera' },
  { kind: 'profile', label: 'Perfil', icon: 'profile' },
]

const supabaseAuth = import.meta.env.VITE_SUPABASE_URL && import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY
  ? new SupabaseWebAuth({
    url: import.meta.env.VITE_SUPABASE_URL,
    publishableKey: import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY,
    storage: browserAuthStorage(),
  })
  : null

const syncCoordinator = supabaseAuth === null
  ? null
  : createSyncCoordinator({
    transport: createSupabaseTransport({ http: supabaseAuth }),
    local: createIndexedDbSyncLocalStore({ resolveCard: resolveCardForSync }),
  })
const syncRunner = syncCoordinator === null ? null : createSyncRunner(syncCoordinator)

function screenNavKind(screen: Screen): (typeof navigation)[number]['kind'] {
  if (screen.kind === 'catalog' || screen.kind === 'confirm') return 'scan'
  if (screen.kind === 'edit') return 'collection'
  return screen.kind
}

function storageMessage(): string {
  return 'Não foi possível acessar os dados salvos neste navegador. A coleção não foi alterada. Verifique o armazenamento e tente novamente.'
}

function revisionLimitMessage(): string {
  return 'Esta posse atingiu o limite de edições. Nenhuma alteração foi salva. Exporte uma cópia antes de continuar.'
}

export function App() {
  const [screen, setScreen] = useState<Screen>({ kind: 'collection' })
  const [collection, setCollection] = useState<LoadState<CollectionEntry[]>>({ kind: 'loading' })
  const [notice, setNotice] = useState('')
  const [currency, setCurrency] = useState<Currency>('USD')
  const [online, setOnline] = useState(() => navigator.onLine)
  const [authState, setAuthState] = useState<AuthState>({ kind: 'signed-out' })
  const [deviceId] = useState(() => browserDeviceId())
  const [syncBinding, setSyncBindingState] = useState<SyncBinding | null>(null)
  const [syncState, setSyncState] = useState<SyncRunnerState>({ running: false, result: null })
  const photoUrl = useRef<string | null>(null)

  const refreshBinding = useCallback(async () => {
    try {
      setSyncBindingState(await currentSyncBinding())
    } catch {
      setSyncBindingState(null)
    }
  }, [])

  useEffect(() => { void refreshBinding() }, [refreshBinding])

  // Keep account binding while signed out so a browser restart or logout does not force
  // a destructive re-link. Sync still requires a live session for the same identity;
  // signing in to another account clears the old binding and requires a fresh preview.
  useEffect(() => {
    let active = true
    void (async () => {
      const binding = await currentSyncBinding().catch(() => null)
      if (!active || binding === null) return
      if (authState.kind === 'signed-in') {
        if (binding.identity === authState.session.userId) {
          setSyncBindingState(binding)
        } else {
          await setSyncBinding(null).catch(() => {})
          if (active) setSyncBindingState(null)
        }
      } else if (authState.kind === 'signed-out' && active) {
        setSyncBindingState(binding)
      }
    })()
    return () => { active = false }
  }, [authState])

  // Sync lifecycle: while the signed-in account owns the binding, sync runs on link,
  // after every local write, when the tab returns to the foreground, and when the
  // network comes back. Unbinding cancels identity-scoped syncing without touching data.
  useEffect(() => {
    if (syncCoordinator === null || syncRunner === null) return
    const bound = authState.kind === 'signed-in' && syncBinding !== null && syncBinding.identity === authState.session.userId
      ? syncBinding.identity
      : null
    syncCoordinator.bindIdentity(bound)
    if (bound === null) return
    const request = () => { void syncRunner.request() }
    const unsubscribe = syncRunner.subscribe((state) => setSyncState(state))
    request()
    const stopObserving = observeCollectionChanges(request)
    window.addEventListener('online', request)
    window.addEventListener('focus', request)
    const onVisibility = () => { if (document.visibilityState === 'visible') request() }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      unsubscribe()
      stopObserving()
      window.removeEventListener('online', request)
      window.removeEventListener('focus', request)
      document.removeEventListener('visibilitychange', onVisibility)
      syncCoordinator.bindIdentity(null)
      setSyncState({ running: false, result: null })
    }
  }, [authState, syncBinding])

  const refreshCollection = useCallback(async () => {
    try {
      setCollection({ kind: 'ready', value: await listCollection() })
    } catch {
      setCollection({ kind: 'error', message: storageMessage() })
    }
  }, [])

  useEffect(() => {
    void refreshCollection()
    return observeCollectionChanges(() => { void refreshCollection() })
  }, [refreshCollection])

  useEffect(() => {
    const updateConnection = () => setOnline(navigator.onLine)
    window.addEventListener('online', updateConnection)
    window.addEventListener('offline', updateConnection)
    return () => {
      window.removeEventListener('online', updateConnection)
      window.removeEventListener('offline', updateConnection)
    }
  }, [])

  useEffect(() => {
    if (supabaseAuth === null) return
    let active = true
    const fragment = window.location.hash
    if (fragment.includes('access_token=')) {
      void supabaseAuth.consumeConfirmation(fragment).then(async (callbackState) => {
        const state = callbackState.kind === 'signed-in' ? callbackState : await supabaseAuth.start()
        if (!active) return
        window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}`)
        setAuthState(state)
        setNotice(callbackState.kind === 'signed-in'
          ? 'E-mail confirmado. Sua coleção continua local até você revisar e confirmar o vínculo.'
          : 'O link de confirmação expirou ou não é válido. Entre novamente ou solicite outra confirmação.')
      })
    } else {
      void supabaseAuth.start().then((state) => { if (active) setAuthState(state) })
    }
    return () => { active = false }
  }, [])

  useEffect(() => () => { if (photoUrl.current !== null) URL.revokeObjectURL(photoUrl.current) }, [])

  const navigate = (next: Screen) => {
    setNotice('')
    setScreen(next)
    window.scrollTo({ top: 0, behavior: 'auto' })
  }

  const selectCatalogCard = async (card: CardSummary, alternatives: CardSummary[] = [], capturedPhoto: string | null = null) => {
    setNotice('Carregando detalhes da carta...')
    const result = await getCatalogCard(card.id)
    if (result.kind === 'unavailable') {
      setNotice(result.message)
      return
    }
    setNotice('')
    navigate({ kind: 'confirm', card: result.value, alternatives, photoUrl: capturedPhoto })
  }

  const handlePhoto = async (file: File) => {
    if (!file.type.startsWith('image/')) {
      setNotice('Escolha uma imagem de carta em formato compatível.')
      return
    }
    if (photoUrl.current !== null) URL.revokeObjectURL(photoUrl.current)
    const capturedPhoto = URL.createObjectURL(file)
    photoUrl.current = capturedPhoto
    setNotice('Lendo a foto neste navegador...')
    const recognition = await recognizePhoto(file)
    if (recognition.kind === 'unavailable') {
      navigate({ kind: 'catalog', photoUrl: capturedPhoto })
      setNotice(recognition.message)
      return
    }
    const query = recognition.parsed.name ?? recognition.parsed.collectorNumber
    if (query === null || query.length < 2) {
      navigate({ kind: 'catalog', photoUrl: capturedPhoto })
      setNotice('Não identificamos um nome ou número. Busque a carta manualmente.')
      return
    }
    setNotice('Buscando candidatos no catálogo...')
    const result = await searchCatalog(query)
    if (result.kind === 'unavailable' || result.value.length === 0) {
      navigate({ kind: 'catalog', photoUrl: capturedPhoto })
      setNotice(result.kind === 'unavailable' ? result.message : 'Nenhuma carta correspondeu à leitura. Busque manualmente.')
      return
    }
    const ranked = rankCandidates(recognition.parsed, result.value).slice(0, 8)
    const selected = ranked[0]
    if (selected === undefined) return
    await selectCatalogCard(selected, ranked, capturedPhoto)
  }

  const entries = collection.kind === 'ready' ? collection.value : []
  const activeNav = screenNavKind(screen)
  return <div className="app-shell">
    <aside className="sidebar" aria-label="Navegação principal">
      <button type="button" className="brand" onClick={() => navigate({ kind: 'collection' })}>TCG <span>Coleção</span></button>
      <nav aria-label="Seções">{navigation.map((item) => <button key={item.kind} type="button" className={`nav-link ${activeNav === item.kind ? 'selected' : ''}`} aria-current={activeNav === item.kind ? 'page' : undefined} onClick={() => navigate({ kind: item.kind })}><Icon name={item.icon} size={24} />{item.label}</button>)}</nav>
      <p className="sidebar-note">Sua coleção fica neste navegador.</p>
    </aside>
    <div className="main-column">
      <header className="mobile-brand"><button type="button" className="brand" onClick={() => navigate({ kind: 'collection' })}>TCG <span>Coleção</span></button><button type="button" className="icon-action" aria-label="Escanear carta" onClick={() => navigate({ kind: 'scan' })}><Icon name="camera" size={22} /></button></header>
      <main className="content">
        {!online ? <div className="connection-status" role="status" aria-label="Estado da conexão">
          Você está offline. Sua coleção continua disponível neste navegador; a sincronização entre aparelhos ainda não está disponível.
        </div> : null}
        {notice !== '' ? <div className="notice" role="status">{notice}<button type="button" aria-label="Fechar aviso" onClick={() => setNotice('')}><Icon name="close" size={18} /></button></div> : null}
        {screen.kind === 'collection' ? <CollectionScreen
          collection={collection} currency={currency} onCurrency={setCurrency}
          onRetry={refreshCollection} onScan={() => navigate({ kind: 'scan' })}
          onCatalog={() => navigate({ kind: 'catalog', photoUrl: null })}
          onSelectMissing={(card) => { void selectCatalogCard(card) }}
          onEdit={(entry) => navigate({ kind: 'edit', entry })}
          onQuickQuantity={async (entry, quantity) => {
            const result = await editCollectionItem(entry.item.id, entry.item.revision, {
              kind: 'valid', quantity, variant: entry.item.variant, condition: entry.item.condition,
              pricePaid: entry.item.pricePaid,
            })
            if (result.kind === 'saved') setNotice('Quantidade atualizada.')
            else if (result.kind === 'conflict') setNotice(result.reason === 'revision-limit' ? revisionLimitMessage() : 'Esta posse mudou em outra aba. A lista foi atualizada; tente novamente.')
            else setNotice(storageMessage())
            await refreshCollection()
          }} /> : null}
        {screen.kind === 'home' ? <HomeScreen entries={entries} currency={currency} onCurrency={setCurrency} onCollection={() => navigate({ kind: 'collection' })} onScan={() => navigate({ kind: 'scan' })} /> : null}
        {screen.kind === 'profile' ? <ProfileScreen entries={entries} onRetry={refreshCollection} onNotice={setNotice} syncBinding={syncBinding} deviceId={deviceId} onLinked={() => { void refreshBinding() }} syncState={syncState} onSyncNow={() => { void syncRunner?.request() }} onImport={async (file) => {
          if (!isCollectionBackupSizeAllowed(file.size)) {
            setNotice('Este arquivo é maior que 50 MB. Escolha uma cópia menor.')
            return
          }
          try {
            const payload: unknown = JSON.parse(await file.text())
            const result = await importCollectionBackup(payload)
            if (result.kind === 'imported') {
              const changed = result.added + result.merged
              setNotice(changed === 0 ? 'Esta cópia já está aplicada.' : `Cópia aplicada: ${result.added} adicionadas, ${result.merged} combinadas.`)
              await refreshCollection()
            } else if (result.kind === 'invalid') setNotice(result.message)
            else if (result.kind === 'conflict') {
              setNotice(result.reason === 'quantity-limit' ? 'A importação ultrapassaria o limite de 100.000 cartas. Nenhuma alteração foi aplicada.' : result.reason === 'revision-limit' ? revisionLimitMessage() : 'A cópia conflita com dados alterados ou inválidos neste navegador. Nenhuma alteração foi aplicada.')
            } else setNotice(storageMessage())
          } catch {
            setNotice('Não foi possível ler este arquivo. Escolha uma cópia JSON válida e tente novamente.')
          }
        }} authState={authState} auth={supabaseAuth} onAuthState={setAuthState} /> : null}
        {screen.kind === 'scan' ? <ScanScreen onBack={() => navigate({ kind: 'collection' })} onPhoto={(file) => { void handlePhoto(file) }} onCatalog={() => navigate({ kind: 'catalog', photoUrl: null })} /> : null}
        {screen.kind === 'catalog' ? <CatalogScreen photoUrl={screen.photoUrl} onBack={() => navigate({ kind: 'scan' })} onSelect={(card) => { void selectCatalogCard(card, [], screen.photoUrl) }} /> : null}
        {screen.kind === 'confirm' ? <ConfirmScreen key={screen.card.id} card={screen.card} alternatives={screen.alternatives} photoUrl={screen.photoUrl}
          onBack={() => navigate({ kind: 'catalog', photoUrl: screen.photoUrl })}
          onAlternative={(card) => { void selectCatalogCard(card, screen.alternatives, screen.photoUrl) }}
          onManual={() => navigate({ kind: 'catalog', photoUrl: screen.photoUrl })}
          onSave={async (draft) => {
            const result = await addToCollection(screen.card, draft)
            if (result.kind === 'saved') { navigate({ kind: 'collection' }); setNotice('Carta adicionada à coleção.'); await refreshCollection() }
            else if (result.kind === 'conflict') setNotice(result.reason === 'revision-limit' ? revisionLimitMessage() : 'A quantidade ultrapassa o limite. Revise a posse existente.')
            else setNotice(storageMessage())
            return result.kind === 'saved'
          }} /> : null}
        {screen.kind === 'edit' ? <EditScreen key={screen.entry.item.id} entry={screen.entry} onBack={() => navigate({ kind: 'collection' })}
          onSave={async (draft) => {
            const result = await editCollectionItem(screen.entry.item.id, screen.entry.item.revision, draft)
            if (result.kind === 'saved') { navigate({ kind: 'collection' }); setNotice('Alterações salvas.'); await refreshCollection() }
            else if (result.kind === 'conflict') {
              setNotice(result.reason === 'duplicate' ? 'Esta variante e condição já têm uma posse. Escolha outra combinação.' : result.reason === 'revision-limit' ? revisionLimitMessage() : 'Esta posse mudou em outra aba. Volte à coleção e abra a versão atual.')
            } else setNotice(storageMessage())
            return result.kind === 'saved'
          }}
          onRemove={async () => {
            const result = await removeCollectionItem(screen.entry.item.id, screen.entry.item.revision)
            if (result.kind === 'removed') { navigate({ kind: 'collection' }); setNotice('Posse removida da coleção.'); await refreshCollection() }
            else if (result.kind === 'conflict') setNotice('Esta posse mudou em outra aba. Volte à coleção e abra a versão atual.')
            else setNotice(storageMessage())
            return result.kind === 'removed'
          }} /> : null}
      </main>
    </div>
    <nav className="bottom-nav" aria-label="Navegação principal">{navigation.map((item) => <button key={item.kind} type="button" className={activeNav === item.kind ? 'selected' : ''} aria-current={activeNav === item.kind ? 'page' : undefined} onClick={() => navigate({ kind: item.kind })}><Icon name={item.icon} size={24} /><span>{item.label}</span></button>)}</nav>
  </div>
}

function CollectionScreen({ collection, currency, onCurrency, onRetry, onScan, onCatalog, onSelectMissing, onEdit, onQuickQuantity }: {
  collection: LoadState<CollectionEntry[]>; currency: Currency; onCurrency: (currency: Currency) => void;
  onRetry: () => void; onScan: () => void; onCatalog: () => void; onSelectMissing: (card: CardSummary) => void; onEdit: (entry: CollectionEntry) => void;
  onQuickQuantity: (entry: CollectionEntry, quantity: number) => void;
}) {
  const [mode, setMode] = useState<'owned' | 'missing'>('owned')
  const [search, setSearch] = useState('')
  const [setId, setSetId] = useState('')
  const [variant, setVariant] = useState<CardVariant | ''>('')
  const [condition, setCondition] = useState<Condition | ''>('')
  const [sets, setSets] = useState<LoadState<SetOption[]>>({ kind: 'loading' })
  const [coverage, setCoverage] = useState<LoadState<SetCoverage> | null>(null)
  const entries = collection.kind === 'ready' ? collection.value : []
  const ownedSetOptions = [...new Map(entries.map((entry) => [entry.card.setId, entry.card.setName])).entries()].sort((a, b) => a[1].localeCompare(b[1], 'pt-BR'))

  const loadSets = useCallback(async () => {
    setSets({ kind: 'loading' })
    const result = await listSets()
    setSets(result.kind === 'unavailable' ? { kind: 'error', message: result.message } : { kind: 'ready', value: result.value })
  }, [])

  useEffect(() => { if (mode === 'missing') void loadSets() }, [mode, loadSets])

  const chooseSet = async (nextSetId: string) => {
    setSetId(nextSetId)
    if (mode !== 'missing' || nextSetId === '' || sets.kind !== 'ready') return
    const set = sets.value.find((option) => option.id === nextSetId)
    if (set === undefined) return
    setCoverage({ kind: 'loading' })
    setCoverage({ kind: 'ready', value: await getSetCoverage(set) })
  }

  const filtered = entries.filter((entry) =>
    (setId === '' || entry.card.setId === setId) &&
    (variant === '' || entry.item.variant === variant) &&
    (condition === '' || entry.item.condition === condition) &&
    `${entry.card.name} ${entry.card.collectorNumber} ${entry.card.setName}`.toLocaleLowerCase('pt-BR').includes(search.toLocaleLowerCase('pt-BR')))
  const totalUnits = entries.reduce((sum, entry) => sum + entry.item.quantity, 0)
  const estimate = collectionEstimate(entries, currency)
  const missing = coverage?.kind === 'ready' ? missingFromCompleteCatalog(coverage.value, entries) : null
  const filteredMissing = missing?.filter((card) =>
    (variant === '' || card.variants.includes(variant)) &&
    `${card.name} ${card.collectorNumber} ${card.setName}`.toLocaleLowerCase('pt-BR').includes(search.toLocaleLowerCase('pt-BR'))) ?? []

  return <>
    <div className="page-title-row"><h1>Coleção</h1></div>
    <div className="collection-toolbar"><label className="search-box"><Icon name="search" size={22} /><span className="sr-only">Buscar na coleção</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Buscar na coleção" />{search !== '' ? <button type="button" aria-label="Limpar busca" onClick={() => setSearch('')}><Icon name="close" size={19} /></button> : null}</label><div className="mode-switch" role="group" aria-label="Tipo de coleção"><button type="button" className={mode === 'owned' ? 'active' : ''} aria-pressed={mode === 'owned'} onClick={() => { setMode('owned'); setSetId(''); setCoverage(null) }}>Tenho</button><button type="button" className={mode === 'missing' ? 'active' : ''} aria-pressed={mode === 'missing'} onClick={() => { setMode('missing'); setSetId(''); setCoverage(null) }}>Faltam</button></div><button type="button" className="primary-button toolbar-scan" onClick={onScan}><Icon name="camera" />Escanear carta</button></div>
    <div className="filters"><label className="filter-select"><span className="sr-only">Expansão</span><select value={setId} onChange={(event) => { void chooseSet(event.target.value) }}><option value="">Expansão</option>{mode === 'owned' ? ownedSetOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>) : sets.kind === 'ready' ? sets.value.map((set) => <option key={set.id} value={set.id}>{set.name}</option>) : null}</select><Icon name="chevron" size={17} /></label><label className="filter-select"><span className="sr-only">Variante</span><select value={variant} onChange={(event) => setVariant(event.target.value as CardVariant | '')}><option value="">Variante</option>{VARIANTS.map((option) => <option key={option} value={option}>{variantLabels[option]}</option>)}</select><Icon name="chevron" size={17} /></label><label className="filter-select"><span className="sr-only">Condição</span><select value={condition} disabled={mode === 'missing'} onChange={(event) => setCondition(event.target.value as Condition | '')}><option value="">Condição</option>{CONDITIONS.map((option) => <option key={option} value={option}>{conditionLabels[option]}</option>)}</select><Icon name="chevron" size={17} /></label><button type="button" className="text-button clear-filters" onClick={() => { setSearch(''); setSetId(''); setVariant(''); setCondition(''); setCoverage(null) }}>Limpar filtros</button></div>
    {mode === 'owned' ? <>
      {collection.kind === 'loading' ? <StatePanel title="Carregando sua coleção..." /> : null}
      {collection.kind === 'error' ? <StatePanel title="Coleção indisponível" description={collection.message} action="Tentar novamente" onAction={onRetry} /> : null}
      {collection.kind === 'ready' && entries.length === 0 ? <EmptyCollection onScan={onScan} onCatalog={onCatalog} /> : null}
      {collection.kind === 'ready' && entries.length > 0 ? <>
        <div className="collection-summary"><div className="summary-block"><Icon name="cards" size={28} /><div><span>Total de cartas</span><strong>{totalUnits}</strong><small>na sua coleção</small></div></div><div className="summary-block"><Icon name="tag" size={28} /><div><span>Valor estimado</span><strong>{estimate.amount === null ? 'Sem cotação' : formatMoney(estimate.amount, currency)}</strong><small>{estimate.pricedUnits} com preço · {estimate.missingUnits} sem preço</small></div><label className="summary-currency"><span className="sr-only">Moeda da estimativa</span><select value={currency} onChange={(event) => onCurrency(event.target.value as Currency)}>{CURRENCIES.map((option) => <option key={option}>{option}</option>)}</select></label></div></div>
        {filtered.length === 0 ? <StatePanel title="Nenhuma posse com estes filtros" description="Ajuste a busca ou limpe os filtros." action="Limpar filtros" onAction={() => { setSearch(''); setSetId(''); setVariant(''); setCondition('') }} /> : <div className="collection-grid">{filtered.map((entry) => <OwnedCard key={entry.item.id} entry={entry} currency={currency} onEdit={() => onEdit(entry)} onQuantity={(quantity) => onQuickQuantity(entry, quantity)} />)}</div>}
      </> : null}
    </> : <>
      {sets.kind === 'loading' ? <StatePanel title="Carregando expansões..." /> : null}
      {sets.kind === 'error' ? <StatePanel title="Expansões indisponíveis" description={sets.message} action="Tentar novamente" onAction={() => { void loadSets() }} /> : null}
      {sets.kind === 'ready' && setId === '' ? <StatePanel title="Escolha uma expansão" description="Cartas faltantes são calculadas apenas quando a lista completa da expansão está disponível." /> : null}
      {coverage?.kind === 'loading' ? <StatePanel title="Verificando a expansão..." /> : null}
      {coverage?.kind === 'ready' && coverage.value.kind !== 'complete' ? <StatePanel title="Não foi possível confirmar as cartas faltantes" description={coverage.value.message} action="Tentar novamente" onAction={() => { void chooseSet(setId) }} /> : null}
      {coverage?.kind === 'ready' && coverage.value.kind === 'complete' ? <><p className="coverage-note">Lista completa de {coverage.value.expectedCount} cartas da expansão. As cartas que você já possui não aparecem aqui.</p>{filteredMissing.length === 0 ? <StatePanel title={missing?.length === 0 ? 'Você tem todas as cartas desta expansão' : 'Nenhuma carta com estes filtros'} description={missing?.length === 0 ? 'Selecione outra expansão para continuar.' : 'Ajuste a busca ou os filtros.'} /> : <div className="missing-grid">{filteredMissing.map((card) => <div key={card.id} className="missing-card"><CardArtwork card={card} /><div><h2>{card.name}</h2><p>{card.setName}</p><p>{card.collectorNumber}</p><button type="button" className="secondary-button" onClick={() => onSelectMissing(card)}>Adicionar esta carta</button></div></div>)}</div>}</> : null}
    </>}
  </>
}

function OwnedCard({ entry, currency, onEdit, onQuantity }: { entry: CollectionEntry; currency: Currency; onEdit: () => void; onQuantity: (quantity: number) => void }) {
  const quote = quoteFor(entry.card, entry.item.variant, currency)
  return <article className="owned-card"><div className="owned-card-top"><CardArtwork card={entry.card} /><div className="owned-info"><h2>{entry.card.name}</h2><p>{entry.card.setName}</p><p>{entry.card.collectorNumber}{entry.card.setTotal === null ? '' : `/${entry.card.setTotal}`}</p><dl><div><dt>Variante</dt><dd>{variantLabels[entry.item.variant]}</dd></div><div><dt>Condição</dt><dd>{conditionLabels[entry.item.condition]}</dd></div></dl></div></div><div className="owned-card-bottom"><div><span>Quantidade</span><QuantityControl value={entry.item.quantity} onChange={onQuantity} /></div><div><span>Preço atual</span><strong>{quote === null ? 'Sem cotação' : formatMoney(quote.amount, quote.currency)}</strong><small>{quote === null ? 'Sem preço para esta variante' : `${quote.source === 'TCGPLAYER' ? 'TCGplayer' : 'Cardmarket'} · ${quote.updatedAt ?? 'data não informada'}`}</small></div></div><button type="button" className="secondary-button edit-button" onClick={onEdit}><Icon name="edit" size={18} />Editar carta</button></article>
}

function EmptyCollection({ onScan, onCatalog }: { onScan: () => void; onCatalog: () => void }) {
  return <div className="empty-collection"><div className="empty-icon"><Icon name="cards" size={78} /></div><h2>Sua coleção está vazia</h2><p>Escaneie uma carta ou busque pelo nome<br className="desktop-only" /> para adicionar a primeira.</p><button type="button" className="primary-button" onClick={onScan}><Icon name="scan" />Escanear carta</button><button type="button" className="secondary-button" onClick={onCatalog}><Icon name="search" />Buscar no catálogo</button></div>
}

function StatePanel({ title, description, action, onAction }: { title: string; description?: string; action?: string; onAction?: () => void }) {
  return <div className="state-panel" role="status"><Icon name="cards" size={32} /><h2>{title}</h2>{description ? <p>{description}</p> : null}{action && onAction ? <button type="button" className="secondary-button" onClick={onAction}>{action}</button> : null}</div>
}

function HomeScreen({ entries, currency, onCurrency, onCollection, onScan }: { entries: CollectionEntry[]; currency: Currency; onCurrency: (currency: Currency) => void; onCollection: () => void; onScan: () => void }) {
  const estimate = collectionEstimate(entries, currency)
  return <><h1>Início</h1><div className="home-panel"><p>Sua coleção neste navegador</p><strong>{entries.reduce((sum, entry) => sum + entry.item.quantity, 0)} cartas</strong><span>{estimate.amount === null ? 'Sem cotação de mercado' : `${formatMoney(estimate.amount, currency)} estimados`}</span><label className="summary-currency"><span className="sr-only">Moeda da estimativa</span><select value={currency} onChange={(event) => onCurrency(event.target.value as Currency)}>{CURRENCIES.map((option) => <option key={option}>{option}</option>)}</select></label></div><div className="home-actions"><button type="button" className="primary-button" onClick={onCollection}><Icon name="cards" />Ver coleção</button><button type="button" className="secondary-button" onClick={onScan}><Icon name="camera" />Escanear carta</button></div><p className="storage-footnote">Entre na conta e vincule a coleção para sincronizar entre seus aparelhos.</p></>
}

function ProfileScreen({ entries, onRetry, onNotice, onImport, authState, auth, onAuthState, syncBinding, deviceId, onLinked, syncState, onSyncNow }: {
  entries: CollectionEntry[]; onRetry: () => void; onNotice: (message: string) => void; onImport: (file: File) => void;
  authState: AuthState; auth: SupabaseWebAuth | null; onAuthState: (state: AuthState) => void;
  syncBinding: SyncBinding | null; deviceId: string; onLinked: () => void;
  syncState: SyncRunnerState; onSyncNow: () => void;
}) {
  const exportCopy = () => {
    const blob = new Blob([JSON.stringify({ format: 'tcg-colecao-web-1', exportedAt: new Date().toISOString(), entries }, null, 2)], { type: 'application/json' })
    if (!isCollectionBackupSizeAllowed(blob.size)) {
      onNotice('A cópia ultrapassa 50 MB e não pode ser restaurada neste navegador. Reduza a coleção antes de exportar.')
      return
    }
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `tcg-colecao-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  }
  return <><h1>Perfil</h1><div className="profile-panel"><h2>Conta e sincronização</h2>{auth === null
    ? <p>Login e sincronização não estão configurados neste ambiente. Sua coleção continua apenas neste navegador.</p>
    : <>
      <WebAccountPanel auth={auth} state={authState} onState={onAuthState} onNotice={onNotice} linked={authState.kind === 'signed-in' && syncBinding !== null && syncBinding.identity === authState.session.userId} />
      {authState.kind === 'signed-in'
        ? <LinkPanel auth={auth} identity={authState.session.userId} deviceId={deviceId} entries={entries} binding={syncBinding} onLinked={onLinked} syncState={syncState} onSyncNow={onSyncNow} />
        : null}
    </>}</div><div className="profile-panel"><h2>Coleção local</h2><p>Suas cartas ficam neste navegador. O navegador pode apagar dados locais; guarde uma cópia da sua coleção.</p><button type="button" className="primary-button" onClick={exportCopy} disabled={entries.length === 0}>Baixar cópia da coleção</button><label className="secondary-button import-backup" htmlFor="collection-backup">Restaurar ou combinar cópia</label><input id="collection-backup" className="sr-only" type="file" accept="application/json,.json" aria-label="Importar cópia da coleção" onChange={(event) => { const file = event.target.files?.[0]; if (file) onImport(file); event.target.value = '' }} /><p className="storage-footnote">Cópias de até 50 MB. Cartas repetidas são combinadas por variante e condição. Conflitos interrompem a importação sem alterar a coleção.</p><button type="button" className="text-button" onClick={onRetry}>Atualizar coleção</button></div></>
}

function WebAccountPanel({ auth, state, onState, onNotice, linked }: {
  auth: SupabaseWebAuth; state: AuthState; onState: (state: AuthState) => void; onNotice: (message: string) => void; linked: boolean;
}) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [mode, setMode] = useState<'sign-in' | 'sign-up'>('sign-in')
  const submit = async () => {
    setBusy(true)
    setError('')
    try {
      if (mode === 'sign-up') {
        const result = await auth.signUp(email.trim(), password)
        onState(result.kind === 'signed-in'
          ? { kind: 'signed-in', session: result.session }
          : { kind: 'awaiting-verification', email: result.email })
        if (result.kind === 'verification-required') setPassword('')
      } else {
        const session = await auth.signIn(email.trim(), password)
        onState({ kind: 'signed-in', session })
        setPassword('')
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Não foi possível acessar a conta.')
    } finally {
      setBusy(false)
    }
  }
  if (state.kind === 'signed-in') return <><p>Conectado como <strong>{state.session.email}</strong>.</p>{linked ? null : <p className="storage-footnote">A coleção ainda não foi vinculada. Entrar não envia suas cartas.</p>}<button type="button" className="secondary-button" disabled={busy} onClick={() => {
    setBusy(true)
    void auth.signOut().finally(() => { onState({ kind: 'signed-out' }); setBusy(false); onNotice('Sessão encerrada. Sua coleção local foi preservada.') })
  }}>{busy ? 'Saindo...' : 'Sair da conta'}</button></>
  if (state.kind === 'awaiting-verification') return <><p>Enviamos uma mensagem de confirmação para <strong>{state.email}</strong>. Confirme o endereço e depois entre.</p><button type="button" className="text-button" onClick={() => onState({ kind: 'signed-out' })}>Voltar para entrar</button></>
  return <><p>Entre ou crie uma conta. Isso não envia nem combina sua coleção local.</p><form className="account-form" onSubmit={(event) => { event.preventDefault(); void submit() }}>
    <label>E-mail<input type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} /></label>
    <label>Senha<input type="password" autoComplete={mode === 'sign-in' ? 'current-password' : 'new-password'} minLength={8} required value={password} onChange={(event) => setPassword(event.target.value)} /></label>
    {error !== '' ? <p className="form-error" role="alert">{error}</p> : null}
    <button type="submit" className="primary-button" disabled={busy}>{busy ? 'Conectando...' : mode === 'sign-in' ? 'Entrar' : 'Criar conta'}</button>
  </form><button type="button" className="text-button" disabled={busy} onClick={() => { setMode(mode === 'sign-in' ? 'sign-up' : 'sign-in'); setError('') }}>{mode === 'sign-in' ? 'Ainda não tenho conta' : 'Já tenho conta'}</button></>
}

function ScanScreen({ onBack, onPhoto, onCatalog }: { onBack: () => void; onPhoto: (file: File) => void; onCatalog: () => void }) {
  const photoInput = useRef<HTMLInputElement>(null)
  const cameraInput = useRef<HTMLInputElement>(null)
  return <><SubpageHeader title="Escanear carta" onBack={onBack} /><div className="scan-tip"><Icon name="cards" size={30} /><div><strong>Enquadre a carta inteira</strong><p>Evite reflexo e mantenha a câmera firme</p></div></div><div className="scan-stage"><Icon name="scan" size={90} /><p>Fotografe uma carta ou escolha uma imagem do aparelho.</p></div><div className="scan-actions"><button type="button" className="secondary-button" onClick={() => photoInput.current?.click()}><Icon name="image" />Escolher foto</button><button type="button" className="primary-button" onClick={() => cameraInput.current?.click()}><Icon name="camera" />Fotografar</button></div><input ref={photoInput} className="sr-only" type="file" accept="image/*" onChange={(event) => { const file = event.target.files?.[0]; if (file) onPhoto(file); event.target.value = '' }} /><input ref={cameraInput} className="sr-only" type="file" accept="image/*" capture="environment" onChange={(event) => { const file = event.target.files?.[0]; if (file) onPhoto(file); event.target.value = '' }} /><button type="button" className="text-button manual-link" onClick={onCatalog}>Buscar manualmente</button><p className="storage-footnote">A foto é processada neste navegador e não é enviada ao catálogo. O primeiro uso pode precisar baixar os dados de leitura.</p></>
}

function CatalogScreen({ photoUrl, onBack, onSelect }: { photoUrl: string | null; onBack: () => void; onSelect: (card: CardSummary) => void }) {
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState<SearchState>({ kind: 'idle' })
  const submit = async () => {
    setSearch({ kind: 'loading' })
    const result = await searchCatalog(query)
    setSearch(result.kind === 'unavailable' ? { kind: 'error', message: result.message } : { kind: 'results', result })
  }
  return <><SubpageHeader title="Buscar no catálogo" onBack={onBack} />{photoUrl !== null ? <div className="photo-context"><img src={photoUrl} alt="Foto capturada, mantida neste navegador" /><span>Confira a foto ao escolher a carta.</span></div> : null}<form className="catalog-search" onSubmit={(event) => { event.preventDefault(); void submit() }}><label className="search-box"><Icon name="search" /><span className="sr-only">Nome ou número da carta</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Nome ou número da carta" /></label><button type="submit" className="primary-button">Buscar</button></form>{search.kind === 'idle' ? <StatePanel title="Encontre a carta" description="Busque pelo nome ou número impresso na carta." /> : null}{search.kind === 'loading' ? <StatePanel title="Buscando cartas..." /> : null}{search.kind === 'error' ? <StatePanel title="Busca indisponível" description={search.message} action="Tentar novamente" onAction={() => { void submit() }} /> : null}{search.kind === 'results' ? <>{search.result.kind === 'partial' ? <p className="coverage-note">{search.result.message} A ausência de resultados não confirma que a carta não existe.</p> : null}{search.result.value.length === 0 ? <StatePanel title="Nenhuma carta encontrada" description="Confira o nome ou número e tente outra busca." /> : <div className="catalog-results">{search.result.value.slice(0, 40).map((card) => <button type="button" className="catalog-result" key={card.id} onClick={() => onSelect(card)}><CardArtwork card={card} /><span><strong>{card.name}</strong><small>{card.setName} · {card.collectorNumber}</small></span><Icon name="chevron" size={18} /></button>)}</div>}</> : null}</>
}

function SubpageHeader({ title, onBack }: { title: string; onBack: () => void }) {
  return <div className="subpage-header"><button type="button" className="back-button" onClick={onBack}><Icon name="back" size={19} />Voltar</button><h1>{title}</h1></div>
}

function CardIdentity({ card }: { card: CardSummary }) {
  return <div className="card-identity"><CardArtwork card={card} /><div><h2>{card.name}</h2><p>{card.setName}</p><p>{card.collectorNumber}{card.setTotal === null ? '' : `/${card.setTotal}`}</p></div></div>
}

function ConfirmScreen({ card, alternatives, photoUrl, onBack, onAlternative, onManual, onSave }: { card: CardSummary; alternatives: CardSummary[]; photoUrl: string | null; onBack: () => void; onAlternative: (card: CardSummary) => void; onManual: () => void; onSave: (draft: Extract<ReturnType<typeof parseItemDraft>, { kind: 'valid' }>) => Promise<boolean> }) {
  const [draft, setDraft] = useState<ItemDraft>(() => emptyDraft(card))
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const submit = async () => {
    const parsed = parseItemDraft(draft)
    if (parsed.kind === 'invalid') { setError(parsed.message); return }
    setError('')
    setSaving(true)
    const saved = await onSave(parsed)
    if (!saved) setSaving(false)
  }
  return <><SubpageHeader title="Confirme a carta" onBack={onBack} /><p className="subpage-intro">Verifique se a carta identificada está correta antes de adicionar à sua coleção.</p>{photoUrl !== null ? <div className="photo-context"><img src={photoUrl} alt="Foto capturada, mantida neste navegador" /><span><strong>Foto capturada</strong><br />Use a imagem para conferir o resultado abaixo.</span></div> : null}<section className="confirmation-card"><span className="eyebrow">Candidato selecionado</span><CardIdentity card={card} /><ItemFields draft={draft} onChange={setDraft} disabled={saving} /></section>{alternatives.filter((candidate) => candidate.id !== card.id).slice(0, 3).map((candidate) => <button key={candidate.id} type="button" className="alternative-card" onClick={() => onAlternative(candidate)}><CardArtwork card={candidate} /><span><strong>{candidate.name}</strong><small>{candidate.setName} · {candidate.collectorNumber}</small></span></button>)}{error !== '' ? <p className="form-error" role="alert">{error}</p> : null}<div className="form-actions"><button type="button" className="primary-button" disabled={saving} onClick={() => { void submit() }}>{saving ? 'Adicionando...' : 'Adicionar à coleção'}</button><button type="button" className="secondary-button" disabled={saving} onClick={onManual}>Buscar manualmente</button></div></>
}

function EditScreen({ entry, onBack, onSave, onRemove }: { entry: CollectionEntry; onBack: () => void; onSave: (draft: Extract<ReturnType<typeof parseItemDraft>, { kind: 'valid' }>) => Promise<boolean>; onRemove: () => Promise<boolean> }) {
  const [draft, setDraft] = useState<ItemDraft>(() => draftFromItem(entry.item))
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const removingRef = useRef(false)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const removeTriggerRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  useEffect(() => { if (confirmRemove) cancelRef.current?.focus() }, [confirmRemove])
  const closeRemoveConfirmation = () => {
    setConfirmRemove(false)
    window.requestAnimationFrame(() => removeTriggerRef.current?.focus())
  }
  const handleDialogKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      closeRemoveConfirmation()
      return
    }
    if (event.key !== 'Tab') return
    const buttons = dialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
    const first = buttons?.item(0)
    const last = buttons?.item(buttons.length - 1)
    if (first === undefined || last === undefined) return
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }
  const removeConfirmed = async () => {
    if (removingRef.current) return
    removingRef.current = true
    setSaving(true)
    if (!await onRemove()) {
      removingRef.current = false
      setSaving(false)
      closeRemoveConfirmation()
    }
  }
  const quote = quoteFor(entry.card, draft.variant, 'USD') ?? quoteFor(entry.card, draft.variant, 'EUR')
  const save = async () => {
    const parsed = parseItemDraft(draft)
    if (parsed.kind === 'invalid') { setError(parsed.message); return }
    setError('')
    setSaving(true)
    if (!await onSave(parsed)) setSaving(false)
  }
  return <><SubpageHeader title="Editar posse" onBack={onBack} /><p className="subpage-intro">As alterações serão aplicadas apenas nesta cópia da sua coleção.</p><CardIdentity card={entry.card} /><h2 className="section-heading">Editar detalhes da posse</h2><div className="edit-fields"><ItemFields draft={draft} onChange={setDraft} disabled={saving} /></div><div className="market-panel"><strong>Valor de mercado <small>(estimativa)</small></strong><div>{quote === null ? 'Sem cotação para esta variante' : formatMoney(quote.amount, quote.currency)}</div><p>{quote === null ? 'O catálogo não informou um preço para esta variante.' : `Fonte: ${quote.source === 'TCGPLAYER' ? 'TCGplayer' : 'Cardmarket'} · Capturado em: ${quote.updatedAt ?? 'data não informada'}`}</p></div>{error !== '' ? <p className="form-error" role="alert">{error}</p> : null}<div className="form-actions"><button type="button" className="primary-button" disabled={saving} onClick={() => { void save() }}>{saving ? 'Salvando...' : 'Salvar alterações'}</button><button ref={removeTriggerRef} type="button" className="danger-button" disabled={saving} onClick={() => setConfirmRemove(true)}><Icon name="trash" />Remover da coleção</button><p>Esta ação removerá esta posse da sua coleção. Será necessário confirmar.</p></div>{confirmRemove ? <div className="dialog-backdrop"><div ref={dialogRef} className="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="remove-title" aria-describedby="remove-description" onKeyDown={handleDialogKeyDown}><h2 id="remove-title">Remover esta posse?</h2><p id="remove-description">A carta {entry.card.name} e sua quantidade serão removidas deste navegador.</p><div><button ref={cancelRef} type="button" className="secondary-button" disabled={saving} onClick={closeRemoveConfirmation}>Cancelar</button><button type="button" className="danger-button" disabled={saving} onClick={() => { void removeConfirmed() }}>{saving ? 'Removendo...' : 'Remover posse'}</button></div></div></div> : null}</>
}
