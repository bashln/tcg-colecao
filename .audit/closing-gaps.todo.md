# Fechamento de lacunas (2026-10-20)

Plano aprovado pelo operador em 2026-10-20. Cinco fatias, ordem fixa, cada uma
verificada antes da seguinte.

## Slice 1. Schema remoto do Supabase

- [x] Link com o projeto isolado `fwzzwqakrvogafqiatgo`.
- [x] `supabase migration list --linked` confirma 4 aplicadas e 4 pendentes.
- [x] `supabase db push --linked --dry-run` limpo.
- [x] `supabase db push --linked` aplica as 4 migrações.
- [x] Verificação remota: 8 migrações aplicadas, 8 RPCs de sync presentes, todas
      SECURITY DEFINER, `authenticated` com EXECUTE, `anon` sem EXECUTE, e as
      quatro tabelas com 0 linhas.

## Slice 2. Correção dos bugs de sync

- [ ] Chunking de push em lotes de 100 operações nos dois clientes.
- [ ] Mapeamento de resultado de push por `opId`, não por posição.
- [ ] Web trata `rejected` como conflito, igual ao Android.
- [ ] Regra única de bloqueio por opId para push conflict.
- [ ] Uma função de arredondamento de dinheiro por plataforma e vetor
      cross-platform com valor de três casas.
- [ ] `pricePaid` com a mesma unidade em todos os codificadores do Android.

## Slice 3. Fechar o CI

- [ ] Job do harness Postgres.
- [ ] Workflow de release roda os testes unit antes de publicar.
- [ ] Job Android compila `app/src/androidTest`.
- [ ] Projeto WebKit no Playwright.

## Slice 4. Promessas já feitas na tela

- [ ] Caminho de remoção de posse no Android.
- [ ] Preço pago no fluxo de adicionar do Android.
- [ ] Job de atualização periódica de preço.
- [ ] Filtros por variante, condição e status no Android.

## Slice 5. Documentos

- [ ] `docs/plans/GOAL.md`
- [ ] `docs/plans/web-architecture.md`
- [ ] `web/README.md`
- [ ] `docs/releasing.md`
- [ ] Contagens de teste em `todo.md` e `docs/verification/accounts-and-sync.md`
