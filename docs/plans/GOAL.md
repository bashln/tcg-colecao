# Plano de conclusão, para um goal-worker

Objetivo. Entregar o S1 do TCG Coleção finalizado, testado, versionado no
GitHub, com release de APK instalável pelo Obtainium, e a identificação por
foto confiável pelo caminho real de produção.

Critério de parada. O app instalado a partir de uma release do GitHub
identifica uma carta real como primeiro candidato, a suíte inteira passa, e o
repositório tem o código em `main` com uma tag de release.

Como trabalhar. Uma unidade verificável por vez. Valide no artefato real.
Registre em `progress.json` após cada passo. Mesmo erro duas vezes, mude de
abordagem. Três tentativas sem progresso no mesmo passo, pare e documente.

## Checkpoints

1. Corrigir o recall do caminho real.
   Ação. No `ScanViewModel`, remover o filtro que descarta resultados de
   número quando o nome não casa, manter o merge e o limite de 10. Persistir
   o total do set no `Room` (entidade, mappers, database) para o cache não
   perder o desempate.
   Verificação. `E2E_SUMMARY[holdout]` top1 maior ou igual a 85 por cento,
   medido pelo `ScanViewModel` com `RoomCardRepository` sobre Room em memória.
   Antes. main 87,0 por cento, holdout 57,5 por cento.
   Pronto quando. Ambos os números sobem e a suíte segue verde.

2. Verificar no aparelho.
   Ação. Instalar o debug, escanear ou buscar `Frogadier`.
   Verificação. Primeiro candidato é Frogadier, lista tem no máximo 10,
   detalhe mostra ataques e os dois preços.
   Antes. Vinha Seismitoad e 391 candidatos.

3. Merge em `main`.
   Ação. `git checkout main && git merge feat/001-scan-api-preco`.
   Verificação. Suíte verde no resultado do merge.
   Pronto quando. `main` contém o S1.

4. Publicar no GitHub.
   Ação. Criar o repositório com `gh repo create` e dar push do `main`.
   Verificação. `gh repo view` mostra o repositório, `git ls-remote` lista.
   Pronto quando. Existe remoto com o código.

5. Release do APK.
   Ação. Cadastrar os quatro secrets do keystore, criar a tag `v0.1.0` e
   dar push da tag. O workflow compila, assina e publica.
   Verificação. `gh release view v0.1.0` mostra o APK anexado e
   `apksigner verify --print-certs` confirma `CN=TCG Colecao`.
   Pronto quando. A release existe com APK assinado.

6. Obtainium.
   Ação. No aparelho, adicionar o app pela URL do repositório.
   Verificação. Obtainium lista a release e instala o APK.
   Pronto quando. O app está instalado a partir da release.

## Restrições

- TDD em código de produção. `:core` sem Android.
- Não alterar os datasets de medição.
- Sem config de agente ou IA no repositório.
- Sem deletar arquivos, mover para `./trash/` e registrar em `./trash/LOG.md`.
- UX excelente, sem esperar pedido.
