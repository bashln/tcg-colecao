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

- [x] Chunking de push em lotes de 100 operações nos dois clientes.
- [x] Mapeamento de resultado de push por `opId`, não por posição.
- [x] `rejected` já era conflito nos dois clientes. A regra ficou com teste.
- [x] Regra única de bloqueio por opId, união de `pushConflict.opId` e
      `pushConflict.rejectedOperation.opId`.
- [x] Uma função de arredondamento de dinheiro por plataforma, com recusa de
      valor negativo e vetores cross-platform de `roundingCases`.
- [x] `pricePaid` com a mesma unidade em todos os codificadores do Android.

## Slice 3. Fechar o CI

- [x] Job do harness Postgres, verde local com 58 asserções.
- [x] Workflow de release roda os testes unit antes de publicar.
- [x] Job Android compila `app/src/androidTest`.
- [x] Projeto WebKit no Playwright, com job próprio no CI.

## Slice 4. Promessas já feitas na tela

- [x] Caminho de remoção de posse no Android, com confirmação.
- [x] Preço pago no fluxo de adicionar do Android.
- [x] Job de atualização periódica de preço.
- [x] Filtros por variante e condição no Android. O status de posse usa as abas Tenho/Faltam.

## Slice 5. Documentos

- [x] `docs/releasing.md`
- [x] `docs/plans/GOAL.md`
- [x] `docs/plans/web-architecture.md`
- [x] `web/README.md`
- [x] Contagens de teste em `todo.md` e `docs/verification/accounts-and-sync.md`
