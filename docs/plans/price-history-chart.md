# Gráfico do histórico de preços

## Problema

A Home mostra o valor atual da coleção, mas o gráfico ainda é um placeholder. O Room já guarda cotações diárias por carta, variante, fonte e moeda. O app não guarda mudanças antigas de quantidade ou posse. Portanto, o gráfico pode estimar como as posses atuais seriam avaliadas pelas cotações que o app registrou. Ele não pode dizer quanto a coleção valia em datas passadas.

## Uso

A Home consulta um único fluxo para a fonte escolhida:

```kotlin
repository.observeCurrentHoldingsHistory(
    source = PriceSource.TCGPLAYER,
    asOfUtcDay = LocalDate.now(Clock.systemUTC()),
)
```

O painel mostra uma série em USD para TCGplayer ou em EUR para Cardmarket. A pessoa escolhe a fonte, não uma combinação de moedas. O texto informa que a série aplica as quantidades e variantes atuais às cotações registradas em cada data.

## Forma

`CollectionRepository` ganha `observeCurrentHoldingsHistory(source, asOfUtcDay)`. `RoomCollectionRepository` implementa a leitura com uma consulta reativa que agrupa a posse atual por carta e variante, mantém as posses sem cotação e associa as observações correspondentes. A consulta filtra pela fonte e pela moeda derivada de `Valuation.currencyOf(source)`.

Uma função pura no repositório projeta as linhas Room em `CurrentHoldingsHistory`. Ela percorre as datas registradas em ordem, carrega a última cotação conhecida de cada carta/variante somente a partir da primeira data observada e multiplica essa cotação pela quantidade atual. O cálculo nunca usa uma cotação futura em uma data anterior. Quantidades sem cotação ficam fora do subtotal e entram na cobertura como ausentes. Um preço observado igual a zero continua sendo uma cotação válida.

Cada ponto contém `Money`, a data UTC, as unidades com e sem cotação e a quantidade de unidades cujo registro local tem mais de sete dias. A cotação antiga continua no subtotal, mas a interface identifica sua idade. Datas de captura descrevem quando o app salvou a cotação, não quando o mercado a atualizou.

O resultado também marca uma quebra quando o conjunto exato de cartas e variantes com cotação muda entre pontos. O gráfico não conecta pontos que representam coberturas diferentes. Alterações atuais de quantidade, variante ou posse recalculam toda a série. Isso é uma estimativa das posses de hoje, não um registro da composição da coleção em cada data.

O ViewModel mantém a seleção da fonte e estados independentes de carregamento, erro e resultado. A Home continua mostrando seu total atual mesmo se a leitura do histórico falhar. O painel apresenta estado sem dados, um único dia ou vários dias. Um único dia não recebe indicador de tendência. Os valores e datas também aparecem em texto acessível; cor e geometria do Canvas não são a única forma de comunicar preço ou mudança.

## Escolha do desenho

O candidato A foi selecionado após comparação independente: A marcou 24/25, B 22/25 e C 14/25. A oferece a série agregada da coleção atual e mantém o cálculo atrás de uma operação do repositório existente. B mostrava o histórico de uma carta selecionada, não um resumo da coleção. C acrescentava um repositório separado, repetia a regra de moeda e descartava cotações após 30 dias, o que poderia criar quedas aparentes quando a cobertura expirasse.

Do candidato B, adotamos a distinção explícita entre data em que o app salvou a cotação e data de atualização do mercado. Do candidato C, adotamos `Money` nos pontos para carregar a moeda com o valor. Não adotamos o prazo de validade de 30 dias. Cotações antigas permanecem no subtotal e são identificadas por idade, para que o fim da validade não pareça uma queda do mercado.

## Limites e alternativas

- Uma tabela de valor total por dia exigiria um histórico de posse que o banco ainda não possui. Gravá-la agora não reconstruiria datas anteriores.
- Uma série por carta mostraria preços observados diretamente, mas não responderia ao resumo de coleção que a Home precisa.
- Não há conversão entre USD e EUR.
- Este recorte não atualiza preços. As cotações novas ainda dependem de adicionar uma carta ou abrir seu detalhe completo. Atualização periódica e frequência de captura ficam para uma unidade posterior.
- A consulta lê as observações retidas para as cartas atuais. Só adicionaremos limite de retenção ou janela depois de medir o tamanho real do banco.

## Verificação

Testes de domínio devem cobrir duas variantes, posses duplicadas em condições diferentes, cotação ausente, cotação zero, as duas fontes, cotações repetidas no mesmo dia, alteração de quantidade e cotação posterior. Eles verificam subtotais, cobertura, idade, ordem e quebras de segmento.

Testes Room verificam que a consulta mantém posses sem histórico e emite novamente após gravação de observação e mudança da coleção. Testes do ViewModel e da tela cobrem troca de fonte, erro e retry, zero, uma e várias datas, cobertura parcial e texto acessível.

## Estado da implementação

O contrato tipado, a projeção, a consulta Room e o painel Compose estão implementados. A série alterna TCGplayer/USD e Cardmarket/EUR, informa unidades cobertas e capturas antigas, e lista os três registros mais recentes como texto. Testes JVM cobrem a projeção, os resultados da consulta e os estados do ViewModel.

`:core:test`, `:app:testDebugUnitTest`, `:app:compileDebugAndroidTestKotlin` e `:app:assembleDebug` passaram. O APK abriu no Galaxy A32 após `adb install -r`, que preservou os dados. A coleção estava vazia, então o aparelho confirmou apenas o estado vazio da Home. O gráfico com dados ainda precisa de uma captura em fixture isolada. Não execute instrumentação que desinstala o app no A32 sem proteger os dados locais.

A unidade seguinte deve atualizar os preços das cartas atuais com limites de frequência e tratamento offline. A série só representa uma tendência útil quando novas capturas chegam ao longo do tempo.
