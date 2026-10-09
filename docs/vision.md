# Visão e destino final

Documento norte do projeto. Ele guarda o ideal completo para que as fatias
menores nunca percam o destino.

Data. 2026-10-09.
Status. Vivo. Atualizar quando uma decisão mudar.

Capturas de referência do Collectr ficam em `docs/reference/collectr/`.

## Resumo

App gratuito, alternativa ao Collectr, com duas frentes. Um app Android e
uma versão web. O usuário escaneia uma carta física com a câmera, o app
identifica a carta via API, mostra os dados dela e os preços de TCGplayer
(USD) e Cardmarket (EUR), e guarda tudo numa coleção com valor total. As duas
frentes compartilham a mesma coleção, então quem não tem Android usa a web e
quem tem vê a própria coleção nos dois lugares. Ver a seção "Versão web e
coleção compartilhada".

## Princípios inegociáveis

1. **Grátis.** Sem paywall, sem scan limitado, sem anúncio.
2. **Conta opcional.** O uso local não exige login. A conta entra só para
   compartilhar a coleção entre web e Android.
3. **Local-first.** O app funciona offline para a coleção já baixada. A rede
   serve para identificar carta, puxar preço e sincronizar quando há conta.
4. **Sem lock-in.** Export e backup da coleção em formato aberto.
5. **Documentado e testado.** Toda fatia tem spec e testes. TDD no domínio.
6. **Português primeiro.** UI em pt-BR e dados de carta em pt-BR via TCGdex.
7. **Uma coleção, duas frentes.** Web e Android leem e escrevem a mesma
   coleção. Nada de coleção ilhada por plataforma.

## Destino final, paridade funcional com o Collectr

Derivado das 5 capturas de tela mais o pedido do usuário.

- **Scan.** Câmera com moldura guia, identificação da carta, contador de
  scans sem limite.
- **Home / Overview.** Valor total da carteira, variação em 30 dias, gráfico
  de performance em 1D, 7D, 1M, 3M, 6M, MAX, lista "Most Valuable".
- **Portfolio.** Grade de cartas com imagem, nome, set, raridade, variante,
  condição, quantidade, preço, variação. Busca na coleção. Ações de mercado,
  analisador de troca, ações em lote, exportar.
- **Profile / Stats.** Totais de cartas, selados, graduadas e valor. Estatísticas
  e histórico de transações.
- **Market Movers.** Maiores altas e maiores baixas.
- **Trade Analyzer.** Compara os dois lados de uma troca somando valores.
- **Bulk Actions.** Seleção múltipla e edição em lote.
- **Export.** CSV e JSON, mais backup manual da coleção.
- **Versão web.** Scan por câmera no navegador, coleção compartilhada com o
  Android, e visualização da própria coleção de qualquer navegador.

Fora de escopo por decisão de produto.

- Social e feed.
- Loja e venda de selados.

## Versão web e coleção compartilhada

Requisito do usuário. Sem iPhone, e sem pagar a App Store, a web é a via para
quem não tem Android. Quem usa a web escaneia a carta pela câmera do
navegador e guarda no mesmo acervo do Android. Quem usa Android abre a web e
vê a própria coleção. Tudo compartilhado.

O que isso obriga.

- Um backend com banco de dados de servidor e autenticação. Sem isso não há
  como duas plataformas verem o mesmo acervo. Isto reverte a decisão D2
  original, que era de coleção só no device.
- O Android continua local-first. O Room passa a ser cache local e a
  sincronização acontece quando há conta e rede. A coleção sem conta segue
  funcionando local.
- O scan da web precisa do mesmo cérebro do Android, parser, ranqueamento e
  valoração. O caminho natural, dado que já escolhemos Kotlin, é Kotlin
  Multiplatform no `:core`, com a UI web em Compose Multiplatform e OCR no
  navegador.
- Isto impõe uma regra sobre a S1. `:core` guarda só lógica pura e
  multiplataforma. O que é específico de plataforma, Room e o engine HTTP do
  JVM, fica atrás das interfaces de repositório, para depois trocar por um
  banco e um engine de navegador sem reescrever o domínio.

Decisões ainda em aberto, a confirmar com o usuário.

- Backend gerenciado, por exemplo Supabase no plano grátis, contra backend
  próprio. Supabase reduz custo e já traz autenticação.
- Kotlin Multiplatform desde já, ou manter Android primeiro e extrair o
  `:core` para KMP na fatia da web. Recomendação, manter a S1 focada no
  Android e só então extrair, porque o `:core` já nasce sem Android.

### Backend e sincronização, proposta

O usuário já tem uma conta Supabase, do site dele. Decisão sugerida, usar
Supabase, em um projeto novo e separado do site, para não acoplar esquema,
backup e limites dos dois.

O que fica no Supabase.

- Autenticação, pelo Supabase Auth.
- Só as linhas da coleção. Uma tabela por usuário, com chave em `user_id`,
  `card_id`, `variant`, `condition`, mais `quantity`, `price_paid` e
  `updated_at`.
- A sincronização. Reconcilição simples, last-write-wins por linha, com o
  `updated_at` do servidor. Não precisamos de CRDT, porque o caso real é a
  mesma pessoa nos próprios devices.

O que não fica no Supabase.

- O catálogo de cartas. Ele vem do TCGdex e é cache local.
- As imagens. Elas vêm do CDN do TCGdex. Assim o tráfego do plano grátis não
  vira gargalo.

Performance. Para este app a carga é ínfima. Guardamos poucas linhas por
usuário e nenhuma imagem. O peso está no TCGdex, não no Supabase. O ponto de
atenção não é desempenho, é construir a sincronização, porque o Supabase é um
Postgres com API e tempo real, não um motor de sincronização offline como o
Firestore. A sincronização é nossa e vive atrás das interfaces de repositório.

Segurança. Chave anônima mais RLS por `user_id` no app. A chave de serviço
nunca vai para Android, web ou repositório.

## Fontes de dados

| Fonte | Papel | Chave | Observação |
| --- | --- | --- | --- |
| TCGdex v2 | Primário | Não exige | Dados em pt-BR. Preços Cardmarket (EUR) e TCGplayer (USD) embutidos no card sob `pricing`. |
| pokemontcg.io v2 | Fallback opcional | Opcional | **Deprecated.** Sem novas chaves. Chaves existentes morrem em 2027-03-01. Fora do caminho crítico. |

Consequências.

- O TCGdex sozinho cobre dados de carta e os dois preços. Ele é a única
  dependência de rede no caminho crítico.
- O pokemontcg.io entra depois, atrás da mesma interface `PriceSource`, como
  cruzamento de preço. Nunca como dependência obrigatória.
- Nenhuma das APIs faz scan. O reconhecimento é nosso.

## Arquitetura alvo

Módulo Gradle `:app` (Android, Compose, CameraX, ML Kit, Room) mais módulo
Kotlin puro `:core` (domínio, parser de scan, regras de valoração, client
HTTP). O `:core` não depende de Android e testa em JVM, o que torna TDD rápido.

Fluxo de camadas.

```
Compose UI -> ViewModel -> Caso de uso -> Repositório (interface)
                                       -> Repositório (impl) -> TCGdex remoto
                                                             -> Room local
```

Regras.

- DTO da API nunca vaza para o domínio. É mapeado na borda.
- Regra de negócio em função pura. Validação e parse só nas bordas.
- Módulos de feature só nascem quando uma fronteira provar que vale.

## Modelo de domínio alvo

- `Card`. id, nome, set, número de colecionador, raridade, hp, tipos, estágio,
  ataques, habilidades, fraquezas, resistências, recuo, ilustrador, marca de
  regulação, legalidades, imagens, variantes disponíveis.
- `CardPrice`. cardId, fonte (TCGplayer, Cardmarket), moeda, variante, campos de
  preço da fonte, data de atualização.
- `CollectionItem`. id, cardId, quantidade, condição, variante, preço pago por
  unidade, moeda, data de entrada, notas.
- `PriceSnapshot`. cardId, fonte, variante, moeda, valor, instante. Base do
  gráfico, porque as APIs não dão série diária.
- `ScanResult`. texto bruto do OCR, número extraído, nome extraído, carta
  casada, confiança.

Enums.

- `Condition`. Near Mint, Lightly Played, Moderately Played, Heavily Played,
  Damaged.
- `CardVariant`. Normal, Holofoil, Reverse Holofoil, First Edition, Promo.
- `PriceSource`. TCGplayer, Cardmarket.

## Roadmap por fatias

Cada fatia tem spec, plano e termina num app que roda.

| Fatia | Entrega | Estado |
| --- | --- | --- |
| S1 | Loop essencial, scan, API, preço, salvar e listar | Spec em andamento |
| S2 | Valoração multi-mercado, USD/EUR, câmbio, variante | Backlog |
| S3 | Snapshots de preço, Home, gráfico, Most Valuable | Backlog |
| S4 | Profile e stats, selados e graduadas | Backlog |
| S5 | Bulk actions e export, backup | Backlog |
| S6 | Market movers e trade analyzer | Backlog |
| S7 | Versão web e sincronização compartilhada, backend e conta opcional | Requisito novo, a especificar |

A ordem pode mudar. O usuário pediu a web como requisito. Ela depende do
backend e da conta opcional, então entra depois de o loop essencial estar de
pé, a não ser que o usuário a priorize.

## Riscos

1. **Precisão do scan.** Nenhuma API reconhece carta. O OCR pode errar nome ou
   número. Mitigação, tela de confirmação com candidatos e fallback de busca
   manual.
2. **Gráfico de 30 dias.** As APIs não dão série diária. Mitigação, snapshots
   no device e uso de `avg1`, `avg7`, `avg30` para semear.
3. **Deprecação do pokemontcg.io.** Mitigação, TCGdex como única dependência
   crítica.
4. **Consistência de variante.** Preço depende da variante (normal, holo,
   reverse). Mitigação, o usuário escolhe a variante ao salvar.
5. **Sincronização e conflitos.** Web e Android escrevem na mesma coleção.
   Mitigação, o backend terá que tratar escrita concorrente e o app manter a
   coleção local como cache reconciliável.
6. **Custo do backend.** A web compartilhada exige servidor e autenticação.
   Mitigação, começar por um backend gerenciado no plano grátis e manter o
   uso local sem conta como caminho sem custo.

## Registro de decisões

- **D1.** Android nativo com Kotlin e Jetpack Compose. Motivo, melhor
  integração de câmera, ML Kit e vida útil.
- **D2.** **Superada por D7.** Local-first sem conta e sem backend. Motivo
  original, custo zero e simplicidade. A coleção local em Room permanece como
  cache; a conta opcional e o backend entram para compartilhar entre web e
  Android.
- **D3.** TCGdex como fonte primária. Motivo, pt-BR e os dois preços embutidos.
- **D4.** pokemontcg.io fica como fallback opcional, desligado por padrão.
  Motivo, deprecação.
- **D5.** Motor de scan inicial é OCR mais busca textual. Motivo, barato,
  on-device e sem infraestrutura. Matching de imagem fica para depois.
- **D6.** Módulo `:core` puro e testável em JVM. Motivo, TDD rápido no domínio.
- **D7.** Versão web com backend e conta opcional, coleção compartilhada com o
  Android. Motivo, requisito do usuário para quem não tem iPhone. A coleção
  local continua sendo a fonte quando não há conta. O `:core` é escrito sem
  APIs de plataforma para virar Kotlin Multiplatform na fatia da web. Decisão
  pendente de confirmação, o backend gerenciado contra próprio, e o momento
  da extração para KMP.
