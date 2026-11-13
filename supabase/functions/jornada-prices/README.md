# jornada-prices

Cache de leitura das cotações BRL da Jornada Games. O cliente pede as cartas da coleção, a função devolve o preço por SKU e grava o cache. A chave da Jornada fica só no segredo da função.

## Contrato

`POST /functions/v1/jornada-prices`

```json
{
  "cards": [
    {
      "cardId": "sv08.5-001",
      "setId": "sv08.5",
      "collectorNumber": "001/131",
      "name": "Exeggcute",
      "variants": ["NORMAL", "REVERSE_HOLOFOIL"]
    }
  ],
  "maxAgeHours": 24
}
```

A resposta traz `quotes` por `cardId`, com `status` (`ok`, `stale`, `pending`, `not_found` ou `error`), o preço da carta em centavos, a lista de SKUs e o mapa `byVariant` para as variantes pedidas. `misses` lista as cartas sem correspondência e `quota` informa o saldo do dia.

## Deploy

```bash
supabase functions deploy jornada-prices --project-ref <ref>
supabase secrets set JORNADA_API_KEY=<chave> --project-ref <ref>
```

O workflow `.github/workflows/deploy-jornada.yml` faz os dois passos quando você o dispara. Ele precisa dos secrets `SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF` e `JORNADA_API_KEY`.

Não publique com `--no-verify-jwt`. O gateway precisa exigir um JWT do Supabase, inclusive a chave anon, para a função não virar um proxy aberto.

## Testes

```bash
node --test supabase/functions/jornada-prices/core.test.mjs
```

O harness PostgreSQL cobre as tabelas, o RLS e as constraints em `supabase/tests/jornada.test.mjs`.
