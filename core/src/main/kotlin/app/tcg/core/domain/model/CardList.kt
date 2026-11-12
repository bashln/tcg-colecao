package app.tcg.core.domain.model

enum class CardListKind { WANTED, MISSING }

data class CardList(val id: String, val name: String, val kind: CardListKind, val createdAt: Long)

data class CardListSummary(val list: CardList, val cardCount: Int)

data class CardListDetail(val list: CardList, val cards: List<Card>)

data class ListAddition(val addedMemberships: Int, val existingMemberships: Int)
