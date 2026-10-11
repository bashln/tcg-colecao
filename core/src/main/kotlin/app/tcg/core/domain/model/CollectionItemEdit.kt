package app.tcg.core.domain.model

data class CollectionItemDetails(
    val item: CollectionItem,
    val card: Card?,
)

data class CollectionItemChanges(
    val quantity: Int,
    val variant: CardVariant,
    val condition: Condition,
    val pricePaid: Money?,
) {
    init {
        require(quantity > 0)
        require(pricePaid == null || pricePaid.amount.isFinite() && pricePaid.amount >= 0.0)
    }
}

sealed interface CollectionUpdate {
    data class Updated(val item: CollectionItem) : CollectionUpdate
    data object NotFound : CollectionUpdate
    data object ChangedSinceLoaded : CollectionUpdate
    data object DuplicateConfiguration : CollectionUpdate
}
