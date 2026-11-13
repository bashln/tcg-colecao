import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { handleRequest, RequestError } from './core.mjs'

const JORNADA = 'https://api.jornadagames.com'
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, 'Content-Type': 'application/json' },
  })
}

function numberHeader(value: string | null): number | null {
  if (value === null) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function resetIso(value: string | null): string | null {
  const seconds = numberHeader(value)
  return seconds === null ? null : new Date(seconds * 1000).toISOString()
}

async function getJson(path: string, key: string) {
  const response = await fetch(`${JORNADA}${path}`, {
    headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
    signal: AbortSignal.timeout(10_000),
  })
  const text = await response.text()
  let body: unknown = null
  try {
    body = text === '' ? null : JSON.parse(text)
  } catch {
    body = null
  }
  const code =
    body !== null && typeof body === 'object' && typeof (body as { code?: unknown }).code === 'string'
      ? (body as { code: string }).code
      : null
  return {
    ok: response.ok,
    status: response.status,
    json: body,
    rate: {
      scope: response.headers.get('x-ratelimit-scope'),
      remaining: numberHeader(response.headers.get('x-ratelimit-remaining')),
      resetAt: resetIso(response.headers.get('x-ratelimit-reset')),
      retryAfter: numberHeader(response.headers.get('retry-after')),
      code,
    },
  }
}

type Row = Record<string, any>

function toQuote(row: Row, skus: Row[]) {
  return {
    cardId: row.card_id,
    publicId: Number(row.public_id),
    code: row.code ?? '',
    marketCents: row.market_cents === null ? null : Number(row.market_cents),
    marketKind: row.market_kind,
    liquidity: row.liquidity,
    fetchedAt: row.fetched_at,
    skus: skus.map((sku) => ({
      condition: sku.condition,
      language: sku.language,
      finish: sku.finish,
      lowestCents: sku.lowest_cents === null ? null : Number(sku.lowest_cents),
      highestCents: sku.highest_cents === null ? null : Number(sku.highest_cents),
      listings: Number(sku.listings),
    })),
  }
}

function makeStore(client: any) {
  return {
    async load(cardIds: string[]) {
      const [maps, quotes, skus, quota] = await Promise.all([
        client.from('jornada_card_map').select('*').in('card_id', cardIds),
        client.from('jornada_quotes').select('*').in('card_id', cardIds),
        client.from('jornada_skus').select('*').in('card_id', cardIds),
        client.from('jornada_quota').select('*').order('utc_day', { ascending: false }).limit(1),
      ])
      for (const result of [maps, quotes, skus, quota]) {
        if (result.error) throw result.error
      }
      const skusByCard = new Map<string, Row[]>()
      for (const sku of skus.data ?? []) {
        const list = skusByCard.get(sku.card_id) ?? []
        list.push(sku)
        skusByCard.set(sku.card_id, list)
      }
      const quotaRow = quota.data?.[0]
      return {
        maps: new Map(
          (maps.data ?? []).map((row: Row) => [
            row.card_id,
            {
              cardId: row.card_id,
              publicId: row.public_id === null ? null : Number(row.public_id),
              code: row.code,
              missedAt: row.missed_at,
            },
          ]),
        ),
        quotes: new Map(
          (quotes.data ?? []).map((row: Row) => [
            row.card_id,
            toQuote(row, skusByCard.get(row.card_id) ?? []),
          ]),
        ),
        quota:
          quotaRow === undefined
            ? null
            : {
                day: quotaRow.utc_day,
                marketCalls: Number(quotaRow.market_calls),
                marketDayLimit: Number(quotaRow.market_day_limit),
                marketResetAt: quotaRow.market_reset_at,
                catalogCalls: Number(quotaRow.catalog_calls),
                catalogDayLimit: Number(quotaRow.catalog_day_limit),
                catalogResetAt: quotaRow.catalog_reset_at,
              },
      }
    },

    async saveMap(map: Row) {
      const { error } = await client.from('jornada_card_map').upsert(
        {
          card_id: map.cardId,
          public_id: map.publicId,
          code: map.code,
          set_code: map.setCode,
          matched_by: map.matchedBy,
          matched_at: map.matchedAt,
          missed_at: null,
        },
        { onConflict: 'card_id' },
      )
      if (error) throw error
    },

    async saveMiss(cardId: string, at: string) {
      const { error } = await client.from('jornada_card_map').upsert(
        {
          card_id: cardId,
          public_id: null,
          code: null,
          set_code: null,
          matched_by: null,
          matched_at: null,
          missed_at: at,
        },
        { onConflict: 'card_id' },
      )
      if (error) throw error
    },

    async saveQuote(quote: Row) {
      const fetchedAt = quote.fetchedAt
      const { error } = await client.from('jornada_quotes').upsert(
        {
          card_id: quote.cardId,
          public_id: quote.publicId,
          code: quote.code,
          market_cents: quote.marketCents,
          market_kind: quote.marketKind,
          liquidity: quote.liquidity,
          fetched_at: fetchedAt,
        },
        { onConflict: 'card_id' },
      )
      if (error) throw error
      const removed = await client.from('jornada_skus').delete().eq('card_id', quote.cardId)
      if (removed.error) throw removed.error
      if (quote.skus.length === 0) return
      const inserted = await client.from('jornada_skus').insert(
        quote.skus.map((sku: Row) => ({
          card_id: quote.cardId,
          condition: sku.condition,
          language: sku.language,
          finish: sku.finish,
          lowest_cents: sku.lowestCents,
          highest_cents: sku.highestCents,
          listings: sku.listings,
          fetched_at: fetchedAt,
        })),
      )
      if (inserted.error) throw inserted.error
    },

    async saveQuota(quota: Row) {
      const { error } = await client
        .from('jornada_quota')
        .upsert(
          {
            utc_day: quota.day,
            market_calls: quota.marketCalls,
            market_day_limit: quota.marketDayLimit,
            market_reset_at: quota.marketResetAt,
            catalog_calls: quota.catalogCalls,
            catalog_day_limit: quota.catalogDayLimit,
            catalog_reset_at: quota.catalogResetAt,
            updated_at: new Date().toISOString(),
          },
          { onConflict: 'utc_day' },
        )
      if (error) throw error
    },
  }
}

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: CORS })
  if (request.method !== 'POST') return json(405, { error: 'method_not_allowed' })

  const jornadaKey = Deno.env.get('JORNADA_API_KEY')
  const supabaseUrl = Deno.env.get('SUPABASE_URL')
  const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
  if (!jornadaKey || !supabaseUrl || !serviceKey) {
    return json(500, { error: 'not_configured' })
  }

  let body: unknown
  try {
    body = await request.json()
  } catch {
    return json(400, { error: 'invalid_json' })
  }

  const client = createClient<any>(supabaseUrl, serviceKey, { auth: { persistSession: false } })
  try {
    const result = await handleRequest({
      body,
      deps: {
        store: makeStore(client),
        jornada: {
          search: (params: Row) => {
            const url = new URL('/v1/public/cards/search', JORNADA)
            url.searchParams.set('game', 'pokemon')
            url.searchParams.set('limit', '10')
            for (const [name, value] of Object.entries(params)) url.searchParams.set(name, String(value))
            return getJson(url.pathname + url.search, jornadaKey)
          },
          prices: (publicId: number) => getJson(`/v1/public/cards/${publicId}/prices`, jornadaKey),
        },
      },
    })
    return json(result.status, result.body)
  } catch (error) {
    if (error instanceof RequestError) {
      return json(400, { error: 'invalid_request', field: error.field, message: error.message })
    }
    console.error('jornada-prices failed', error instanceof Error ? error.message : 'unknown')
    return json(500, { error: 'internal_error' })
  }
})
