import type { ReactNode } from 'react'
import { CONDITIONS, CURRENCIES, VARIANTS, conditionLabels, variantLabels } from '../domain/model.ts'
import type { CardSummary, CardVariant, Condition, Currency, ItemDraft } from '../domain/model.ts'

export type IconName = 'home' | 'cards' | 'camera' | 'profile' | 'search' | 'chevron' | 'back' | 'plus' | 'minus' | 'edit' | 'trash' | 'image' | 'scan' | 'tag' | 'close' | 'check' | 'retry'

export function Icon({ name, size = 22 }: { name: IconName; size?: number }) {
  const common = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  const paths: Record<IconName, ReactNode> = {
    home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z" /></>,
    cards: <><rect x="5" y="4" width="14" height="17" rx="1.8" transform="rotate(-10 5 4)" /><path d="M10 3.7 20 5a2 2 0 0 1 1.7 2.3l-1.6 10.5" /></>,
    camera: <><path d="M3 7h4l1.8-2h6.4L17 7h4v12H3z" /><circle cx="12" cy="13" r="3.5" /></>,
    profile: <><circle cx="12" cy="7" r="4" /><path d="M4.5 21v-2c0-3 3.1-5 7.5-5s7.5 2 7.5 5v2z" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 5 5" /></>,
    chevron: <path d="m5 9 7 7 7-7" />,
    back: <path d="m15 4-8 8 8 8" />,
    plus: <path d="M12 5v14M5 12h14" />,
    minus: <path d="M5 12h14" />,
    edit: <><path d="m4 20 4.6-.9L20 7.7 16.3 4 4.9 15.4z" /><path d="m14.6 5.7 3.7 3.7" /></>,
    trash: <><path d="M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6" /></>,
    image: <><rect x="3" y="3" width="18" height="18" rx="2" /><circle cx="8.5" cy="8.5" r="1.5" /><path d="m3 17 5-5 4 4 3-3 6 5" /></>,
    scan: <><path d="M8 3H5a2 2 0 0 0-2 2v3m13-5h3a2 2 0 0 1 2 2v3M3 16v3a2 2 0 0 0 2 2h3m13-5v3a2 2 0 0 1-2 2h-3" /><path d="M6 12h12" /></>,
    tag: <><path d="M3 11.5 11.5 3H20a1 1 0 0 1 1 1v8.5L12.5 21a2 2 0 0 1-2.8 0L3 14.3a2 2 0 0 1 0-2.8z" /><circle cx="16.5" cy="7.5" r="1" /></>,
    close: <path d="M5 5 19 19M19 5 5 19" />,
    check: <path d="m4 12 5 5L20 6" />,
    retry: <><path d="M20 11a8 8 0 1 1-2.4-5.7" /><path d="M20 4v5h-5" /></>,
  }
  return <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...common}>{paths[name]}</svg>
}

export function CardArtwork({ card, className = '' }: { card: CardSummary; className?: string }) {
  return <div className={`card-artwork ${className}`}>
    {card.imageUrl === null
      ? <div className="artwork-unavailable"><Icon name="cards" size={32} /><span>Imagem indisponível</span></div>
      : <img src={card.imageUrl} alt={`Carta ${card.name}`} loading="lazy" onError={(event) => { event.currentTarget.style.visibility = 'hidden'; event.currentTarget.parentElement?.classList.add('image-failed') }} />}
  </div>
}

export function QuantityControl({ value, onChange, min = 1, disabled = false }: { value: number; onChange: (value: number) => void; min?: number; disabled?: boolean }) {
  return <div className="quantity-control" aria-label="Quantidade">
    <button type="button" aria-label="Diminuir quantidade" disabled={disabled || value <= min} onClick={() => onChange(value - 1)}><Icon name="minus" size={17} /></button>
    <output aria-live="polite">{value}</output>
    <button type="button" aria-label="Aumentar quantidade" disabled={disabled || value >= 100_000} onClick={() => onChange(value + 1)}><Icon name="plus" size={17} /></button>
  </div>
}

export function ItemFields({ draft, onChange, disabled = false }: { draft: ItemDraft; onChange: (draft: ItemDraft) => void; disabled?: boolean }) {
  const quantity = Number(draft.quantity) || 1
  return <div className="item-fields">
    <label className="field-row"><span>Quantidade</span><QuantityControl value={quantity} disabled={disabled} onChange={(next) => onChange({ ...draft, quantity: String(next) })} /></label>
    <label className="field-row"><span>Variante</span><span className="select-wrap"><select value={draft.variant} disabled={disabled} onChange={(event) => onChange({ ...draft, variant: event.target.value as CardVariant })}>{VARIANTS.map((variant) => <option key={variant} value={variant}>{variantLabels[variant]}</option>)}</select><Icon name="chevron" size={18} /></span></label>
    <label className="field-row"><span>Condição</span><span className="select-wrap"><select value={draft.condition} disabled={disabled} onChange={(event) => onChange({ ...draft, condition: event.target.value as Condition })}>{CONDITIONS.map((condition) => <option key={condition} value={condition}>{conditionLabels[condition]}</option>)}</select><Icon name="chevron" size={18} /></span></label>
    <div className="field-row"><label htmlFor="paid-amount">Preço pago <small>(opcional)</small></label><div className="paid-control"><label className="sr-only" htmlFor="paid-currency">Moeda do preço pago</label><select id="paid-currency" value={draft.paidCurrency} disabled={disabled} onChange={(event) => onChange({ ...draft, paidCurrency: event.target.value as Currency })}>{CURRENCIES.map((currency) => <option key={currency} value={currency}>{currency}</option>)}</select><input id="paid-amount" type="text" inputMode="decimal" autoComplete="off" placeholder="0,00" value={draft.paidAmount} disabled={disabled} onChange={(event) => onChange({ ...draft, paidAmount: event.target.value })} /></div></div>
  </div>
}
