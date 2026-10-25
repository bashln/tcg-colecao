# Slice 4. Promessas já feitas na tela, Android

Cada item é uma fatia pequena, na ordem. Cada uma fecha uma promessa que o app
já faz na tela ou nos documentos.

## 4.1 Remover posse no Android

Hoje `CollectionRepository.remove` existe no domínio e no Room
(`core/.../Repositories.kt:52`, `app/.../RoomCollectionRepository.kt:98`), e a
remoção já entra no outbox de sync por `removeAndQueue`. Nenhum ViewModel chama
o método, então o usuário não apaga uma posse no Android. A web apaga com
diálogo de confirmação.

Entrega.

- Uma ação de remover na `CollectionItemCard`, com diálogo de confirmação.
- Um método no `CollectionViewModel` que chama o repositório e expõe o
  resultado para a UI.
- O item some da lista e da tela de expansões sem reiniciar o app.
- Teste de comportamento do ViewModel e teste Compose do diálogo.

Fora do escopo. desfazer. A remoção é definitiva no backend, com tombstone.

## 4.2 Preço pago ao adicionar

Hoje o fluxo de adicionar grava `pricePaid = null` fixo
(`app/.../CardDetailViewModel.kt:60`). O valor de compra nunca entra na coleção,
o que deixa o total de investimento incompleto e o gráfico de preço sem base.

Entrega.

- O formulário de adicionar aceita o preço pago, em moeda.
- O valor segue a mesma regra do editor de posse existente: no máximo duas casas
  decimais e não negativo.
- O preço pago entra no snapshot e na sincronização como os demais campos.
- Teste de comportamento do ViewModel e teste Compose do campo.

## 4.3 Atualização periódica de preço

Hoje o preço só muda quando alguém abre a carta
(`app/.../RoomCardRepository.kt:40`). O gráfico de histórico fica quase vazio
porque ninguém coleta snapshot diário com uso real.

Entrega.

- Um job periódico que atualiza os preços das cartas da coleção.
- A falha de rede não apaga preço conhecido.
- O job roda com o app fechado e sobrevive a reinício.
- Teste de comportamento do job.

Fora do escopo. Sem o job, o app continua funcionando como hoje, com o preço
oportunista. O job é complemento.

## 4.4 Filtros de variante, condição e status no Android

Hoje a busca do Android é só por nome (`app/.../CollectionViewModel.kt:181`) e
os filtros por variante, condição e status existem só na web.

Entrega.

- Busca por nome, número de colecionador e nome da expansão, como na web.
- Filtros por variante, condição e status de posse, com opção de limpar.
- A lista de faltantes ignora o filtro de condição, como na web.
- Teste de comportamento do ViewModel.

Fora do escopo. Ordenação e paginação da web.
