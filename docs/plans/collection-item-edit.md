# Edição exata de uma carta salva

## Problema

Uma carta da coleção é uma posse identificada por `CollectionItem.id`, não apenas por `cardId`. Hoje o detalhe da carta chama `CollectionRepository.add`, que soma a quantidade para a combinação `(cardId, variant, condition)`. Usar esse caminho para editar faria a intenção do usuário virar uma adição. A tabela Room já guarda os campos necessários; este primeiro incremento não precisa de migração.

## Uso esperado

Uma carta da grade salva abre `collection/{itemId}` e carrega a posse exata. Scan, Início e cartas faltantes continuam abrindo `card/{cardId}` para adicionar.

```kotlin
val details = collection.getById(itemId) // posse exata; card pode estar fora da cache
if (details == null) {
    showRemovedState()
} else {
    when (val result = collection.update(details.item, changes)) {
        is CollectionUpdate.Updated -> finishWithSavedFeedback(result.item)
        CollectionUpdate.NotFound -> showRemovedState()
        CollectionUpdate.ChangedSinceLoaded -> keepDraftAndOfferReload()
        CollectionUpdate.DuplicateConfiguration -> keepDraftAndExplainConflict()
    }
}
```

## Forma sintetizada

Base escolhida: candidato A, recomendado pelo parecer independente (22/25 contra 18/25). O editor separado retém o snapshot original e um rascunho local; uma operação atômica no repositório valida o snapshot, recusa colisões e substitui os campos permitidos. `add` permanece exclusivo para adição. Adotamos de B a edição explícita de `pricePaid`; o snapshot ainda protege contra uma alteração que aconteça enquanto a tela estiver aberta.

```kotlin
data class CollectionItemDetails(val item: CollectionItem, val card: Card?)

data class CollectionItemChanges(
    val quantity: Int, // deve ser > 0
    val variant: CardVariant,
    val condition: Condition,
    val pricePaid: Money?, // null limpa o preço pago
)

sealed interface CollectionUpdate {
    data class Updated(val item: CollectionItem) : CollectionUpdate
    data object NotFound : CollectionUpdate
    data object ChangedSinceLoaded : CollectionUpdate
    data object DuplicateConfiguration : CollectionUpdate
}

interface CollectionRepository {
    suspend fun getById(itemId: String): CollectionItemDetails?
    suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate
}
```

O ViewModel do editor é keyed por `itemId`, carrega a configuração existente e mantém o draft após falha. Só `Updated` fecha a tela. `NotFound`, snapshot desatualizado e conflito de chave têm feedback distinto e acionável. `CollectionItemDetails.card` pode estar ausente se a cache estiver inconsistente; nesse caso a tela mostra o identificador da carta e continua permitindo editar a posse em vez de declarar que ela foi removida. A operação Room roda em transação: ler por ID; retornar sucesso idempotente se já estiver no estado desejado; comparar todo snapshot original; conferir se outra posse ocupa a nova chave; atualizar apenas `quantity`, `variant`, `condition` e `pricePaid` por ID. SQL não altera `id`, `cardId` ou `addedAt`. A restrição única continua como última defesa.

O candidato B contribui a linguagem de comando e a edição explícita de `pricePaid`: atualizar é uma operação desejada atômica, não coordenação de várias gravações pela UI; preço em branco limpa o valor e a moeda é selecionável entre USD e EUR. Não adicionamos observação individual reativa sem necessidade demonstrada. O parecer exige tratar ausência de metadados de carta como estado distinto de posse removida, preservar rascunho após stale/conflict e selecionar a posse exata quando uma linha agrega variantes/condições.

## Tradeoffs

- Aceitamos snapshot e um resultado de stale para não sobrescrever uma adição ou alteração feita enquanto o formulário está aberto.
- Recusamos mudar variante/condição para uma configuração já ocupada; merge é outra ação e precisa preservar quantidade e custo de aquisição com intenção explícita.
- O preço pago é editável e pode ser limpo explicitamente; o valor e a moeda ficam juntos, sem conversão implícita.
- Notas não entram nesta fatia porque o modelo não as possui e a adição exigiria migração de Room ainda não autorizada.
- O destino de edição é separado do detalhe de adição para tornar impossível chamar `add` acidentalmente pelo editor.

## Alternativas rejeitadas

- `update(CollectionItem)` aceita um objeto inteiro do chamador e expõe regras sobre quais campos são imutáveis.
- Uma tela/VM única com modo adicionar ou editar mistura fontes, significados e erros diferentes no mesmo caminho.
- Um comando de revisão sem snapshot é menor, mas pode descartar uma quantidade adicionada em paralelo enquanto o formulário estava aberto.

## Reconciliação e implementação

Implementado: tipos de domínio, atualização Room transacional, editor por ID, edição de preço pago, tratamento de conflito/remoção e seleção de posse exata nas telas Coleção, Início e expansão. Os testes Room cobrem preservação de ID/data, edição de preço pago, troca de chave, colisão atômica, snapshot desatualizado, repetição idempotente e remoção. Testes instrumentados usam repositórios isolados; não criar fixture na coleção pessoal do aparelho. A revisão independente sem comentários terminou sem achados.

## Próxima unidade

Investigar por que a suíte conectada completa parou em `EndToEndRecognitionTest`, capturar a tela real do editor com dados isolados e depois avançar para o próximo recorte de UX sem publicar.
