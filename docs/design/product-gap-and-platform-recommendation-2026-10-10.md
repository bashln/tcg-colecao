# Análise de produto e plataforma: alternativa ao Collectr

Data da análise: 10 de outubro de 2026.

## Recomendação executiva

Construir primeiro um excelente gerenciador de coleção Pokémon, não uma cópia visual nem um agregador de vários jogos. O diferencial deve ser confiança e controle: coleção local sem conta, organização por cartas e expansões, identificação assistida confirmável, preços com fonte e cobertura explícitas, backup aberto, e sincronização Android/web opcional sem sobrescrever alterações concorrentes.

Recomendação de infraestrutura: manter React/Vite e Android nativo; hospedar o frontend estático na Vercel se o plano e os termos forem adequados ao uso público; usar **um projeto Supabase novo e isolado** para Auth, Postgres, RLS e RPC quando a fase remota começar. Não usar Neon nem Vercel Functions neste primeiro backend. O Supabase Kotlin SDK é mantido pela comunidade, não pela Supabase; encapsular seu uso e considerar Ktor/REST nas operações de sync reduz acoplamento e mantém política local testável.

Não autenticar CLI nem implantar agora. Login do Vercel CLI só será necessário para link/deploy/administrar projeto pela CLI. Desenvolvimento local, testes, build, Supabase local e preview via integração Git podem seguir sem esse login. Publicação aguarda escolha confirmada de plano, domínio, custo e ambiente.

## O que a referência de produto resolve

O Collectr se apresenta como gerenciador de portfólio de colecionáveis: catálogo amplo, valor agregado, scan, custo de aquisição, variação de mercado e análise de trocas. Parte da proposta comercial atual inclui exportação, filtros e scan ilimitados no Pro. O TCG Collector enfatiza profundidade de catálogo Pokémon, progresso por expansão, listas, filtros e Pokédex.

Não precisamos igualar tamanho de catálogo, variedade de jogos, atualização contínua de preços, feed social, grading population, scanner ilimitado ou paywall. São caros, dependem de dados/licenças e não são necessários para uma coleção Pokémon confiável. Devemos cobrir os trabalhos centrais do colecionador e fazer isso melhor para a realidade do produto.

## Análise cruzada: objetivo × estado atual × próximo valor

| Capacidade | Meta do produto | Estado verificável | Lacuna e recomendação |
|---|---|---|---|
| Coleção local | Útil sem conta/rede, com posse precisa | Android Room e web IndexedDB; web tem busca, edição, remoção e backup JSON | Manter local como modo completo. Explicar armazenamento do navegador e oferecer exportação/restauração visível. |
| Identificação e entrada | Adicionar carta rápido sem gravar erro silencioso | Android scanner; web câmera/arquivo + OCR experimental, confirmação e busca manual | Melhorar precisão medida pelo caminho inteiro, mostrar candidatos e manter ação manual sempre disponível. Confirmar por foto real em aparelho antes de prometer scanner confiável. |
| Organização por expansão | Ver progresso, faltas e variantes | Android tem coleção e expansões; web lista posses e faltas condicionadas a catálogo completo | Fazer progresso de set ser verificável, distinguir indisponibilidade de carta faltante e nunca inferir falta com catálogo parcial. |
| Portfólio e valor | Entender posse, custo e estimativa de mercado | Android tem Home/Perfil, totais, custo/resultado e snapshots de preço; web exibe dados locais e preços disponíveis | Exibir cobertura, idade, fonte e moeda; evitar sugerir valor garantido de venda. Adicionar filtros e listas depois do core. |
| Catálogo | Busca confiável por nome/número e variantes | TCGdex integrado; request real respondeu 503 em teste anterior | Catalogar cache local, retry e estados offline; medir cobertura por expansão e tratar fonte/licença como dependência explícita. |
| Backup e portabilidade | Recuperação sem dependência do serviço | Web tem JSON validado, limite 50 MiB, import atômica e testes; Android ainda requer formato/intercâmbio equivalente | Definir formato de backup multiplataforma e provar round-trip Android ↔ web antes de cloud sync. |
| Android/web sync | Opcional, sem perder dado nem misturar contas | Política pura e fake de duas instâncias; outbox web IndexedDB v4; Android não tem revision/outbox ainda | Completar Room, adapters locais de apply/ack, preview de vínculo e conflitos; testar com fake antes de auth real. |
| Conta e segurança | Conta opcional, isolamento forte | Não implementado | Supabase Auth + RLS/RPC em projeto novo. Testes negativos com duas contas e usuário anônimo; nunca expor chave secreta. |
| Web entrega | Acesso fácil sem instalar | Build Vite e workflow CI/artifact; sem deploy real | Hosting estático separado do backend. Vercel é candidata de conveniência, não requisito técnico; evitar Functions redundantes. |
| Comunidade/monetização | Gratuito, acessível e sustentável | Sem cobrança | Lançar sem paywall. Apoio ou plano futuro exige decisão própria e não pode bloquear dados locais, backup ou exportação. |

## Prioridade de produto sugerida

### P0: confiança e core loop

1. Completar CRUD/backup local em Android e web; exportar e importar em ambos com schema versionado e validação atômica.
2. Concluir captura/identificação sem prometer acerto automático: candidato, confiança operacional, confirmação, busca manual e fluxo de erro recuperável.
3. Tornar catálogo e preços transparentes: cobertura, moeda, origem, atualização e comportamento quando serviço está indisponível.
4. Fechar sync local/fake dos dois clientes, migrações seguras e testes de reinício, replay, conflito, tombstone e mudança de conta.

### P1: melhor ferramenta de coleção Pokémon

1. Progresso por expansão com filtros de variante/condição e distinção visual entre possuído, faltante confirmado e desconhecido.
2. Busca/filtros por expansão, raridade, variante, condição e número; listas pessoais quando dados provarem necessidade.
3. Wishlist e planejamento de faltas, com exportação e sem impor conta.
4. Visão de valor e histórico com cobertura suficiente e indicação clara de estimativa.

### P2: social e expansão de escopo

Compartilhamento, análise de troca, feed, coleção selada/graduada, outros TCGs, notificações de preço e monetização só após uso real indicar demanda e licença/custo dos dados ser sustentável. Não usar dados fictícios nem construir feed vazio para imitar concorrente.

## Supabase versus Vercel

Eles resolvem problemas diferentes:

- **Vercel:** build/deploy e CDN do frontend Vite estático. CLI é opcional. A conta pode ser conectada pela dashboard/Git; CLI serve para `vercel link`, ambientes locais que dependem de env da conta e deploy direto.
- **Supabase:** Postgres, Auth, Data API, RLS, RPC e opcionais Realtime/Edge Functions. É o backend adequado quando a sync exigir estado remoto. Usar migrações locais e pgTAP; fazer link/push só para um projeto de desenvolvimento novo, isolado e depois de conferir custo.
- **Não escolher Neon agora:** acrescenta banco separado e Auth separada em beta sem vantagem comprovada neste projeto. Foi candidato anterior, não decisão irreversível.
- **Não usar Supabase existente:** preservar projetos atuais. Criar projeto novo só quando o gate remoto autorizar e billing/owner forem conhecidos.

Custos oficiais verificados hoje: Supabase Free inclui 500 MB de banco, 50 mil MAU e 5 GB de egress, mas pausa projeto após uma semana inativo e limita dois projetos ativos. Pro começa em US$25/mês e inclui US$10 de crédito de compute, com gasto extra por compute/uso. Vercel Hobby é gratuito, mas uso permitido é pessoal e não comercial; se o serviço público puder ser considerado comercial ou houver benefício financeiro, confirmar termos ou usar plano compatível. Neon Free tem limites utilizáveis para experimento, mas seria mais um fornecedor, enquanto Neon Managed Better Auth consta como beta e disponível em regiões AWS.

Logo, no curto prazo podemos desenvolver localmente sem login em Vercel. Antes do primeiro deploy público, verificar classificação de uso e custo do plano Vercel. Antes do primeiro backend remoto, verificar novo projeto Supabase e custo/pausa do plano. Deploy do frontend não exige que dados pessoais da coleção sejam enviados ao host: IndexedDB continua local.

## Gates antes de produção

- Android e web importam o mesmo backup e recuperam coleção após falha.
- Sync fake executa casos equivalentes em ambos; uma operação confirmada não some nem duplica após crash/retry.
- Primeiro vínculo apresenta prévia de itens locais/remotos e exige confirmação.
- RLS/RPC negam leitura/escrita cruzada, owner forjado, sessão ausente e replay com payload divergente.
- Testes exercitam projeto Supabase local; credenciais administrativas nunca entram nos clientes.
- Auth Android com deep link, renovação e logout são testados em aparelho; SDK comunitário fica atrás de adaptador.
- CI verifica build e testes web/Android; deploy de produção valida domínio, plano e custo.
- Chromium, WebKit quando disponível, aparelho Android, teclado/leitor de tela, fotos reais do scanner e fluxo de backup entram na matriz de release.

## Fontes oficiais consultadas

- [Collectr](https://getcollectr.com) e [App Store](https://apps.apple.com/py/app/collectr-tcg-collector-app/id1603892248?l=en-GB), escopo de portfolio, scan, custo, tendências e recursos Pro.
- [TCG Collector](https://www.tcgcollector.com), progresso por set, filtros, listas e Pokédex.
- [Supabase pricing](https://supabase.com/pricing), limites e preços vigentes.
- [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), grants, policies e testes recomendados.
- [Supabase Kotlin quickstart](https://supabase.com/docs/guides/getting-started/quickstarts/kotlin) e [Kotlin reference](https://supabase.com/docs/reference/kotlin/initializing), suporte Android; a biblioteca Kotlin é comunitária, não oficial.
- [Supabase local development](https://supabase.com/docs/guides/local-development), CLI, migrations e Postgres local.
- [Vercel CLI](https://vercel.com/docs/cli), autenticação só para administrar recursos/deploy.
- [Vercel Hobby](https://vercel.com/docs/plans/hobby) e [fair use](https://vercel.com/docs/limits/fair-use-guidelines), uso pessoal não comercial.
- [Neon Managed Better Auth](https://neon.com/docs/auth/overview), status beta, disponibilidade e limites.
