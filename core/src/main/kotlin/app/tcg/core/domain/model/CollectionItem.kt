package app.tcg.core.domain.model

data class CollectionItem(
    val id: String, val cardId: String, val quantity: Int,
    val condition: Condition, val variant: CardVariant,
    val pricePaid: Money?, val addedAt: Long, val revision: Long = 1,
)

data class CollectionEntry(val item: CollectionItem, val card: Card)
