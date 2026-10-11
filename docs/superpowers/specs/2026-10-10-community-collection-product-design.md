# Produto comunitário de coleção Pokémon

## Objetivo

Entregar uma alternativa gratuita e acessível ao Collector para registrar cartas, consultar preços disponíveis e acompanhar coleções em Android e web. O app atende primeiro um grupo pequeno, mas deve permitir crescimento sem refazer persistência, autenticação ou segurança.

O produto prioriza hobby e acesso comunitário. A interface deve parecer completa e confiável em qualquer escala. Baixo custo não reduz o padrão de design, acessibilidade, desempenho percebido ou proteção dos dados.

## Requisitos de produto

- O app funciona sem conta. A coleção local continua disponível sem rede.
- Criar conta e sincronizar entre Android e web são opcionais.
- O lançamento não cobra, limita scans nem bloqueia recursos essenciais.
- Exportação aberta e restauração permitem sair do serviço sem perder a coleção.
- A pessoa entende fonte, moeda, atualização e cobertura de cada preço. O app não apresenta estimativas como valor garantido de venda.
- Busca, inclusão, edição, remoção, importação e sincronização comunicam estados de carregamento, sucesso, erro e recuperação.
- A interface prioriza pt-BR, acessibilidade por teclado e leitor de tela, contraste e layout móvel.
- Referências funcionais podem se inspirar no Collector, mas marca, textos, imagens e código do produto não serão copiados.
- Apoio comunitário, doações e planos pagos podem ser avaliados no futuro. Não fazem parte do lançamento, não alteram a experiência gratuita atual e não podem ser requisito para recuperar ou exportar dados.

## Estado atual

Android nativo usa Room para a coleção local. A web React, TypeScript e Vite usa IndexedDB e oferece coleção local, busca de catálogo, captura/OCR experimental e backup JSON. A política TypeScript inicial de operações de sync é pura e local. Não há autenticação, outbox persistente, transporte remoto ou coleção compartilhada entre plataformas.

O contrato detalhado de concorrência e revisão está em `docs/plans/web-account-sync.md`. A arquitetura da web e seus limites estão em `docs/plans/web-architecture.md`. Esses documentos continuam subordinados a esta decisão de produto e às verificações de cada etapa.

## Arquitetura escolhida

- Manter app Android nativo e web React/Vite. Não migrar para Next.js nem para Compose Multiplatform nesta fase.
- Usar Vercel como candidata para servir o frontend estático React/Vite. Confirmar compatibilidade do plano com uso público/comunitário antes de publicar. Vercel Functions não são necessárias para a primeira arquitetura de sync.
- Usar um projeto Supabase novo e isolado como backend quando chegar a fase remota: Auth, Postgres, RLS e RPC. Não alterar nem vincular projetos Supabase existentes.
- Criar contratos de domínio independentes de fornecedor para identidade e transporte. A web pode usar supabase-js; Android pode usar o SDK Kotlin comunitário atrás de adaptador e Ktor para endpoints/RPC quando necessário. Nenhum cliente recebe credencial administrativa ou conexão direta ao banco.
- Usar políticas e funções PostgreSQL testáveis, com RLS/grants explícitos, operações idempotentes e autorização pelo `auth.uid()` validado. A API/RPC nunca aceita `user_id` como autoridade do cliente.
- A API valida a sessão, deriva identidade do token validado e autoriza cada operação para o proprietário autenticado. O cliente não escolhe `user_id` para gravar dados.
- Não criar projetos, bancos, contas, cobrança, deploy ou alterações remotas nesta fase de especificação e plano.

Supabase é preferido a Neon nesta etapa por reduzir fornecedores e já cobrir Postgres, Auth, RLS e RPC. O SDK Kotlin é mantido pela comunidade; sua integração deve ficar encapsulada e validada em Android. Vercel continua independente como host de frontend estático. CLI login é necessário para operações administrativas/deploy direto, não para desenvolvimento local.

Custos/termos consultados em 2026-10-10: Supabase Free oferece limite de banco/egr./MAU e pausa projetos após inatividade; Pro parte de US$25/mês com crédito de compute, com custos adicionais possíveis. Vercel Hobby é gratuito, mas limitado a uso pessoal não comercial. O uso público do produto exige confirmar classificação e plano antes de deployment. Valores e termos mudam; revisar no gate de lançamento.

## Uso local

Room e IndexedDB permanecem fontes autoritativas quando a pessoa usa o app sem conta. Falha de rede, indisponibilidade do catálogo ou sessão expirada não apaga nem bloqueia operações locais. O app diferencia dados salvos localmente de dados sincronizados.

Antes de vincular uma conta, o app mostra diferenças entre coleção local e remota. A pessoa confirma o plano de vínculo antes de enviar dados. Desvincular ou trocar conta interrompe sync, preserva dados locais e exige nova prévia. Nenhum dado da conta anterior pode ser enviado à conta seguinte.

Backup e restauração mantêm validação estrita, transação atômica, idempotência, limite de tamanho explícito e erros acionáveis. Migrações devem preservar dados existentes e ter teste de recuperação antes de release.

## Sincronização

Usar operações imutáveis por item, identificadores estáveis, revisão base, outbox local transacional e tombstones para exclusão. Aplicação repetida da mesma operação é idempotente. Escritas concorrentes produzem conflito explícito; nunca usar last-write-wins nem somar quantidades de IDs diferentes automaticamente.

Cada cliente aplica operações locais mesmo offline e grava a intenção de sync na mesma transação local. O transporte envia operações com identificador estável, aplica CAS no servidor e permite retentativa segura. O estado apresenta pendências, última sincronização, conflitos e ações de resolução.

Antes do backend real, um transporte determinístico em memória exercita dois dispositivos, falha, retry, troca de conta, edição concorrente, exclusão, replay e recuperação. A mesma suíte de contrato cobre implementação web e Android onde as plataformas diferirem.

## Conta e privacidade

Fluxos de conta incluem cadastro, verificação de email, login, logout, recuperação de senha, renovação/expiração de sessão, troca de conta e exclusão de conta. Erros não revelam se um email está cadastrado. Segredos ficam no provedor de autenticação ou armazenamento seguro do sistema. Sessões nunca ficam em armazenamento web inseguro se o provedor suportar cookies HttpOnly; a integração Android deve usar armazenamento protegido pela plataforma.

Toda tabela remota de usuário aplica isolamento por proprietário. Testes negativos provam que uma conta não pode ler, editar ou excluir dados de outra. Exclusão remota exige confirmação, aviso claro, exportação disponível e política documentada de retenção. Não executar migração destrutiva de coleção local.

## Preços, catálogo e propriedade intelectual

TCGdex é fonte atual de catálogo e preços disponíveis, sujeita a disponibilidade e termos. O app mostra moeda, fonte, idade da cotação e indisponibilidade. Não inventa histórico quando há apenas snapshots recentes. Fontes futuras só entram após validar API, licença, atribuição, limites e custo.

Scanner e OCR aceleram a entrada, mas sempre pedem confirmação humana e mantêm busca manual. Uma identificação incerta nunca cria posse silenciosamente. Fotos não são enviadas sem aviso e consentimento explícitos.

## Monetização futura

O lançamento não inclui pagamento. Antes de adicionar doações, plano pago ou compras, uma decisão de produto separada deve definir transparência, custos, política de reembolso, privacidade, impostos, lojas e quais serviços podem gerar despesa. Recursos essenciais de coleção, acesso local, backup e exportação permanecem disponíveis sem pagamento. O app não deve coletar dados financeiros nem integrar checkout nesta fase.

## Etapas e critérios de conclusão

1. **Qualidade local.** Verificar migrações, backup/restauração, operação offline e UX em web e Android. Corrigir perda de dados, erros silenciosos e estados inacessíveis antes de ampliar escopo.
2. **Protocolo de sync.** Implementar outbox atômico e simulação de dois dispositivos. Passar testes de replay, conflito, exclusão, troca de conta e recuperação sem serviços remotos.
3. **Viabilidade de Auth e hospedagem.** Provar Neon Managed Better Auth em fluxo web e cliente Kotlin, incluindo verificação, recuperação, renovação e logout. Confirmar limites e custos oficiais de Neon e Vercel. Se integração nativa ou custo não atender, comparar alternativa antes de criar projeto.
4. **Backend isolado.** Criar ambiente de desenvolvimento novo somente após verificar conta, custo e isolamento. Implementar schema, migrations, API autenticada, autorização por proprietário, rate limits e testes negativos de isolamento.
5. **Integração Android e web.** Adicionar login opcional, prévia e confirmação de vínculo, sync, conflitos e recuperação sem modificar o modo local.
6. **Prontidão de produto.** Fazer revisão de UX e acessibilidade, testes Chromium e WebKit quando disponíveis, aparelhos Android, recuperação de backup e smoke test de build implantado. Deploy ou publicação exige verificação explícita de destino, custo e blast radius.

O produto só declara sync pronto quando os fluxos reais passam de ponta a ponta nos dois clientes, incluindo falhas de rede e conflitos. Build verde sozinho não basta.

## Decisões fora de escopo

- Criar ou alterar projeto Supabase existente.
- Criar infraestrutura remota ou ativar cobrança sem validação de preço e isolamento.
- Publicar ou implantar sem confirmar domínio, ambiente, custo e impacto.
- Adicionar assinatura, paywall, anúncios ou limite pago de scan.
- Copiar identidade visual, conteúdo protegido ou código do Collector.
- Declarar câmera, OCR, Safari ou acessibilidade completos sem verificação correspondente.
