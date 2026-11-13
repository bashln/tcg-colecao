# Plano. Scan para de adivinhar quando a evidência é fraca

Esta rodada fecha a parte provável da acurácia do scanner. O app deixa de apresentar
dez cartas confiantes quando o OCR só trouxe um número solto ou um nome parcial.
Quando a leitura tem sinal forte, a ordem dos candidatos passa a refletir o sinal
que realmente identifica a carta.

O tie-breaker visual do audit continua fora. Ele precisa de fotos reais de falha e
de aparelho para calibrar limiar, e nada disso existe neste ambiente.

Quem usa o app nota duas coisas. Um scan ruim leva direto à busca manual, com
mensagem clara, em vez de uma lista errada com a primeira carta em destaque. Um
scan com número de colecionador único acerta o topo mesmo sem nome legível.

## Como ler este plano

Uma caixa é uma unidade verificável. A evidência é um teste que passa com valor
literal, um comando com saída, ou um run de CI no SHA do head.

Base: `github/main`, branch `feat/scan-confidence`.

## Goal e critério de conclusão

- [ ] `./gradlew :core:test` verde com os casos de decisão nova.
- [ ] `./gradlew :app:testDebugUnitTest` verde com o caminho de evidência fraca no `ScanViewModel`.
- [ ] `./gradlew :app:assembleDebug` verde.
- [ ] Workflow `android` e `web` verdes no CI no SHA do head.
- [ ] Diff restrito aos arquivos nomeados.

Fora de escopo, com motivo. Tie-breaker visual por pHash, porque exige fotos de
aparelho. Correção de câmbio para P&L do portfólio, porque hoje o custo é digitado
em BRL e o valor vem em USD ou EUR, e sem câmbio a conta mistura moedas.

## Desenho, decidido antes do código

Duas formas foram consideradas.

- **Limiar numérico.** `score >= 6` vira confiança. Simples e errada. O score é uma
  soma de pesos de naturezas diferentes, e o número esconde qual sinal decidiu.
- **Evidência tipada.** Cada candidato carrega o conjunto de sinais que casaram, e
  uma decisão deriva desses sinais. Escolhida. O código diz qual sinal decidiu, o
  diagnóstico registra isso, e um teste nomeia o caso.

Formas de dados.

```kotlin
enum class ScanSignal { EXACT_NAME, PARTIAL_NAME, COLLECTOR_NUMBER, SET_TOTAL, HP }

data class RankedCandidate(val card: Card, val score: Int, val signals: Set<ScanSignal>)

sealed interface ScanDecision {
    data class Confirmed(val candidates: List<RankedCandidate>) : ScanDecision
    data object TooWeak : ScanDecision
}
```

Regras de decisão, nesta ordem.

- Sem nome exato, sem número de colecionador e sem total de set no topo, a decisão é `TooWeak`.
- Nome exato no topo, a decisão é `Confirmed`, com todos os candidatos de nome exato na frente.
  Vários Pikachus continuam sendo uma lista útil e legítima.
- Número de colecionador sem nome exato, `Confirmed` só quando exatamente um candidato casa o número.
  Vários Pokémon do mesmo número em sets diferentes é `TooWeak`, que é o defeito descrito no audit.
- Total de set sozinho não confirma. Sem nome e sem número, ele só ordena.

## Unidade 1. Evidência e decisão no `:core`

**Arquivos.**

- [ ] `core/src/main/kotlin/app/tcg/core/scan/CandidateRanker.kt`
- [ ] `core/src/main/kotlin/app/tcg/core/scan/ScanDecision.kt` (novo, se o ranker ficar grande)
- [ ] `core/src/main/kotlin/app/tcg/core/domain/model/ScanResult.kt`
- [ ] `core/src/test/kotlin/app/tcg/core/scan/CandidateRankerTest.kt`
- [ ] `core/src/test/kotlin/app/tcg/core/scan/ScanDecisionTest.kt` (novo)

**Contrato.** `rank` continua devolvendo a lista ordenada, agora com os sinais de cada
candidato. Uma função nova recebe a lista ordenada e produz `ScanDecision`. Os pesos
atuais ficam, porque já estão provados pelos testes de dispositivo.

**Testes, com valor literal.**

- `Finizen` com número `42` e `Zebstrika` `42` na lista. Topo é `me05-021`, decisão `Confirmed`.
- Número `42` sozinho, três cartas de sets diferentes com esse número. Decisão `TooWeak`.
- Número `42` sozinho, uma única carta. Decisão `Confirmed`.
- Nome parcial `Furr` sem número. Decisão `TooWeak`.
- Lista vazia. Decisão `TooWeak`.
- Nome exato com três cartas homônimas. Decisão `Confirmed` com as três.
- Nome exato mais número de outra carta. O topo é o nome exato e a decisão é `Confirmed`.

## Unidade 2. O ViewModel obedece a decisão

**Arquivos.**

- [ ] `app/src/main/kotlin/app/tcg/app/ui/scan/ScanViewModel.kt`
- [ ] `app/src/test/kotlin/app/tcg/app/ui/scan/ScanViewModelTest.kt`
- [ ] `core/src/main/kotlin/app/tcg/core/scan/ScanDiagnostics.kt`
- [ ] `core/src/test/kotlin/app/tcg/core/scan/ScanDiagnosticsTest.kt`

**Contrato.** `TooWeak` limpa a lista de candidatos, liga `manualSearch` e mostra
mensagem própria, distinta da falha de rede. `Confirmed` preenche os candidatos
como hoje. O diagnóstico passa a guardar a decisão e os sinais do topo, para uma
falha de aparelho virar fixture.

**Você vê.** `ScanViewModelTest` com leitura de número solto e três candidatos
termina em `candidates` vazio, `manualSearch` verdadeiro e a mensagem nova. O caso
com nome exato termina com candidatos e `manualSearch` falso.

## Checkpoint de throughput

- **Blocking first steps.** Ler o ranker, o parser e o caminho do `ScanViewModel` antes de codar. Feito nesta sessão.
- **Independent workstreams.** As duas unidades compartilham os tipos de decisão, então serializam no mesmo checkout.
- **Shared mutable state.** Um dono único em worktree isolado, sem escrita concorrente.
- **Smallest safe decomposition.** Um dono. A superfície é um enum, um data class e uma função de decisão, com dois consumidores.

## Riscos

- **Confirmado.** Um scan com nome exato mas carta errada continua passando como `Confirmed`. Isso é o que o tie-breaker visual resolveria, e ele segue bloqueado.
- **Confirmado.** `TooWeak` aumenta o número de buscas manuais em fotos ruins. É o comportamento desejado pelo audit, e a mensagem precisa dizer o que fazer.
- **Hipótese não verificada.** Alguns sets podem ter duas cartas com o mesmo número e mesmo nome. A regra de número único trata como fraco, então o usuário escolhe.
