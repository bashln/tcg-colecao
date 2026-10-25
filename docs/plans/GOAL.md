# Plano de conclusão do TCG Coleção

Entregar uma alternativa gratuita e open source ao Collectr para Android e web. As pessoas devem identificar cartas, controlar cada item da coleção e, com uma conta opcional, acessar o mesmo acervo nos dois ambientes. A interface deve ser clara para quem nunca usou o app, acessível e verificada nos aparelhos e navegadores de destino.

O goal persistente desta execução está ativo no Codex. Atualize este arquivo, `progress.json` e `.audit/project-goal-decisions.tsv` ao fechar cada unidade. Faça mudanças pequenas e verificáveis. Preserve o trabalho local existente.

## Critério de conclusão

- Android e web responsiva permitem pesquisar, identificar ou selecionar uma carta, editar seus dados de posse e armazenar a alteração.
- A coleção local funciona sem conta. Usuários autenticados veem e alteram a mesma coleção em Android e web.
- O scan por câmera tem confirmação e busca manual de recuperação. A identificação foi medida pelo caminho real com fotos sob condições de luz variadas.
- Posse, faltantes, expansões, desejos, cartas seladas e graduadas têm estados claros e operações de edição. Histórico de preço, tendências de mercado, comparação de trocas, edição em lote e export funcionam nos formatos documentados.
- Fluxos principais têm estados vazio, carregando, parcial, erro e sucesso, além de navegação por teclado na web e suporte a leitores de tela.
- Testes, build de produção e verificações em aparelhos e navegadores de destino passam. As instruções de operação e backup estão atualizadas.

## Limites de autoridade

- Não apague ou migre dados de usuário sem um plano reversível e autorização explícita.
- Não crie ou altere projeto, esquema, autenticação ou dados no Supabase remoto. Primeiro use um ambiente de desenvolvimento e valide Auth, RLS, isolamento por usuário e sincronização.
- Não publique release, implante o site, crie outro repositório público, ou altere domínio e secrets sem autorização explícita para essa ação específica.
- Não inclua chaves de serviço no app, site ou repositório. Mantenha a coleção local disponível sem login.

## Plano por unidades verificáveis

### 0. Retomar e estabilizar

- [ ] Conferir os diffs locais combinados de S3 e U1 sem descartar trabalho existente.
- [x] Revisar as correções de navegação e fechar achados da revisão independente. A revisão da ação no checklist foi concluída sem achados.
- [x] Passar `:core:test`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin` e `:app:assembleDebug` com `--rerun-tasks`; sete testes instrumentados de coleção passaram no Galaxy A32. A captura física disponível cobre a coleção vazia.
- [ ] Inspecionar fisicamente o editor no Galaxy A32 com dados isolados. A coleção pessoal não foi alterada.
- [ ] Confirmar a suíte conectada completa. `EndToEndRecognitionTest` passou isoladamente em 286,5 s (1 teste, 0 falhas; 97,8% top-1 principal e 87,5% holdout), mas UTP registrou cancelamento gRPC na finalização e o Gradle não confirmou a tarefa completa.
- [ ] Antes de novos testes conectados, proteger os dados locais: UTP desinstala `app.tcg.app` ao final.
- [ ] Revalidar o fluxo de scan com fotos reais, luz uniforme, pouca luz e reflexo. Registrar conjunto, protocolo e resultado em artefato reproduzível.
- [ ] Confirmar as mudanças de schema do Room partindo de uma instalação da release atual e preservar os dados locais.

### 1. Tornar a coleção rápida de entender e editar

- [ ] Revisar o fluxo principal em comparação com `docs/reference/collectr/` sem copiar marca ou conteúdo.
- [ ] A pessoa consegue buscar e filtrar a coleção por nome, expansão, variante, condição e status de posse.
- [x] O seletor Cartas/Expansões usa abas de largura integral para comunicar que são modos alternativos na tela Android. A compilação do APK e dos testes Compose passou; validação visual no A32 aguarda o dispositivo ficar acessível.
- [x] Unificar a identidade visual Android em indigo-violeta, terracota e verde profundo, com superfícies grafite no modo escuro; testes JVM verificam contraste WCAG AA das combinações de texto e fundo.
- [x] Em Cartas, alternar entre Tenho e Faltam em todas as expansões iniciadas. Identidades faltantes vêm apenas de catálogos completos; cobertura parcial, indisponibilidade, retry, nome das expansões pendentes e contagens separadas de cartas/cópias têm estados explícitos. Testes JVM e build passam; runtime Compose e inspeção física aguardam aparelho isolado ou A32 ocioso, pois UTP pode desinstalar o app.
- [x] A pessoa consegue editar quantidade, condição, variante e preço pago de uma posse exata sem criar entradas duplicadas por acidente. Notas seguem pendentes porque ainda não existem no modelo.
- [ ] Grade, lista, detalhe e checklist de expansão funcionam em telas pequenas e grandes.
- [ ] Cada ação destrutiva tem confirmação ou desfazer. Toda gravação tem feedback visível.

### 2. Fechar a visão de valor e histórico

- [ ] Atualizar preços sem apagar os preços conhecidos quando a rede falha.
- [ ] Mostrar moeda, fonte e data da cotação. Manter USD e EUR distintos quando não há câmbio confiável.
- [x] Usar snapshots existentes para gráfico com intervalos claros e estado vazio antes de haver histórico suficiente. A Home aplica as posses e quantidades atuais às cotações salvas e informa que isso não representa a coleção no passado.
- [ ] Exibir total e cartas mais valiosas com ordenação estável e acesso ao detalhe.

### 3. Completar gestão da coleção

- [ ] Implementar lista de desejos, produtos selados e cartas graduadas com modelos que não distorçam a contagem de cartas avulsas.
- [ ] Adicionar seleção e edição em lote com prévia das mudanças e opção de desfazer quando possível.
- [ ] Exportar CSV e JSON e documentar importação ou restauração do backup.
- [ ] Mostrar estatísticas e histórico de mudanças com definições de contagem explícitas.

### 4. Validar backend e sincronização

- [ ] Avaliar isolamento de projeto Supabase e custo, sem tocar no projeto existente do site do usuário.
- [ ] Definir schema, Auth opcional, políticas RLS, conflito entre dispositivos, exclusões e migração de anônimo para autenticado.
- [ ] Validar as regras com dois usuários e dois dispositivos no ambiente de desenvolvimento.
- [ ] Testar modo offline, retry, duplicatas, logout, restauração e export antes de habilitar sincronização para usuários.

### 5. Entregar web responsiva

- [x] Comparar React com TypeScript, Kotlin Multiplatform e componentes web nativos. A arena escolheu React + TypeScript + Vite, mantendo Android nativo.
- [x] Registrar a arquitetura e as regras de domínio web em `docs/plans/web-architecture.md`.
- [x] Implementar coleção local no IndexedDB, busca manual, inclusão com confirmação, edição exata, remoção confirmada, filtros de coleção e lista de faltantes com cobertura completa.
- [x] Implementar a interface responsiva e corrigir sobreposição dos botões vazios e do resumo em 390 px após inspeção visual.
- [ ] Verificar o endpoint TCGdex real, câmera, OCR e permissões em iPhone e desktop. A automação atual intercepta o catálogo e não exercita a câmera.
- [ ] Verificar teclado, leitores de tela, orientação, falha de rede e Safari em aparelho real.
- [x] Definir e verificar importação de backup antes de tornar a web pública.
  - A importação validada existe na web, com schema versionado, limite de 50 MB,
    mesclagem atômica e recusa de conflito. `web/tests/backup-import.spec.ts`
    cobre importação, replay idempotente, mesclagem por quantidade, recusa de
    inconsistência, recusa de overflow de revisão e migração do banco v1.

### 6. Preparar distribuição

- [x] Automatizar testes e APK assinado por CI sem vazar secrets.
  - O workflow `release` compila, assina, confere o certificado fixado e publica. Os
    guards de release têm script de teste próprio. Desde 2026-10-20 o mesmo
    workflow roda os testes de domínio e de unidade antes de compilar o APK, e
    uma suíte vermelha impede a publicação.
- [x] Criar workflow web no GitHub Actions para testar e produzir artefato estático baixável.
  - O workflow web roda a cada push e PR, e já executou com sucesso em `main`, em
    PRs e nas tags `v0.4.0` e `v0.4.1`.
- [x] Rodar o harness de PostgreSQL no CI.
  - Job novo `supabase`, que aplica as migrações verbatim e exercita RLS e RPC.
    Roda local com 58 asserções verdes.
- [ ] Validar instalação e atualização de release em aparelho limpo e com dados existentes.
- [ ] Conferir documentação de versão, backup, privacidade e recuperação.
- [ ] Parar antes de publicar. Pedir confirmação explícita para release ou deploy público.

## Estado conhecido em 2026-10-10

- S1 foi publicado anteriormente em `github.com/bashln/tcg-colecao`, releases `v0.1.1` e `v0.2.0`. O APK já rodou no Galaxy A32. A verificação por fotos reais sob diferentes condições de luz ainda precisa de evidência registrada.
- S3 persiste snapshots diários no Room. A Home agora projeta as posses atuais sobre os snapshots, separa TCGplayer/USD de Cardmarket/EUR e mostra cobertura, cotações antigas e datas de captura. Atualização periódica das cotações continua pendente.
- U1 implementa progresso de expansão e faltantes localmente. Sete testes instrumentados passaram no Galaxy A32. A grade principal, cartas salvas no fallback offline e o checklist abrem a carta correspondente. A revisão independente da última mudança segue pendente.
- E1 adiciona uma rota de edição separada do fluxo de adição. O editor atualiza a posse por `CollectionItem.id`, mantém ID/data e recusa colisão e snapshot obsoleto.
- O projeto Supabase isolado `fwzzwqakrvogafqiatgo` existe, com Auth, RLS e as oito
  migrações de sync aplicadas. As quatro migrações mais recentes, que trazem os
  RPCs de push conflict, entaram no remoto em 2026-10-20 e foram conferidas por
  SQL: 8 migrações aplicadas, 8 RPCs presentes, todas SECURITY DEFINER, com
  `authenticated` autorizado e `anon` sem EXECUTE, e as quatro tabelas vazias.
  O round-trip hospedado entre duas contas continua sem verificação, porque não
  há SMTP nem domínio de callback configurados.
- A primeira fatia web está implementada localmente em `web/`, com coleção IndexedDB e busca manual pelo TCGdex. Build, 2 arquivos unitários e 2 fluxos Playwright Chromium passaram. Os detalhes estão em `web/docs/verification-2026-10-10.md`. A API real, câmera, OCR, Safari/iPhone, sincronização e recuperação por importação ainda precisam de validação.
- Nesta sessão, o ADB não encontrou aparelhos (`no devices/emulators found`). O APK Android e os testes Compose compilaram, mas não foram instalados nem executados no Galaxy A32. Testes instrumentados podem desinstalar o app e apagar dados locais; não rode no aparelho pessoal sem proteger os dados ou usar um dispositivo isolado.
- `docs/design/android/visual-language.md` registra os tokens e a intenção visual Android. O tema, os componentes e o ícone usam indigo-violeta desde 2026-10-18; testes automatizados de contraste e de contrato do tema passam. A avaliação de espaçamento e hierarquia com arte real ainda depende de inspeção em aparelho.
