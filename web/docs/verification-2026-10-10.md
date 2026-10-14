# Verificação local da web

Executada em 10 de outubro de 2026 no checkout local.

`npm run build` concluiu sem erros. O Vite produziu o build de produção em `web/dist/`.

`npm run test:unit` passou em seis arquivos: domínio da coleção, reconhecimento OCR, validação do backup, política de sync, coordenador e transporte em memória. Foram 22 testes, 0 falhas.

`CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:e2e` passou em 16 fluxos. Eles cobrem coleção local, restauração JSON, migrações IndexedDB v1 e v3, outbox por identidade, ordem causal, rollback de coleção e importação, replay, layout móvel, edição offline e retenção de operação após falha de sync simulada. Foram 16 testes, 0 falhas. Foi necessário permitir o servidor local do Playwright fora do sandbox, que bloqueava a porta 4175 com `listen EPERM`.

O teste de remoção dispara duas ativações no mesmo ciclo. A interface deve concluir a remoção uma vez, sem exibir conflito de posse desatualizada.

A restauração aceita arquivos de até 50 MB e valida o JSON antes de abrir uma transação. A exportação recusa arquivos acima do mesmo limite. A importação combina configurações iguais em uma única transação, registra os IDs de origem para impedir soma duplicada e recusa conflitos sem alterar os dados. Inclusão e edição também recusam avanço de revisão fora do limite seguro. O teste de migração cria um banco v1 com uma carta salva e confirma que ela permanece após a atualização.

O transporte em memória exercita duas instâncias, replay, retry, conflitos e troca de identidade sem serviço remoto. A outbox do IndexedDB registra inclusão, edição, remoção e importação no mesmo transaction local quando há vínculo definido. Falhas de outbox revertem a escrita da coleção. O fluxo ainda não está ligado à interface de conta nem a um transporte HTTP. Durante perda de rede com página já carregada, editar posse continua funcionando e banner explica que coleção segue local; ao reconectar, reload preserva a edição. Reload/cold start offline não funciona: o app shell ainda não tem cache offline.

Estas verificações foram executadas localmente em 10 de outubro de 2026. O workflow do GitHub ainda não foi executado remotamente. A consulta real a `https://api.tcgdex.net/v2/pt/cards/base1-4` retornou HTTP 503. Câmera, OCR por foto, Safari, app shell offline, sincronização entre Android e web, autenticação e backend remoto não foram verificados por estes testes. Android: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` passou; os testes Android não foram instalados em aparelho pessoal.
