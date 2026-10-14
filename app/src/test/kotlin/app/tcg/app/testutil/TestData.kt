package app.tcg.app.testutil

import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.CollectionEntry
import app.tcg.core.domain.model.CollectionItem
import app.tcg.core.domain.model.CollectionItemChanges
import app.tcg.core.domain.model.CollectionItemDetails
import app.tcg.core.domain.model.CollectionUpdate
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.CurrentHoldingsHistory
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import java.time.LocalDate

/** In-memory [CollectionRepository] for ViewModel tests; emits whenever [entries] changes. */
class FakeCollectionRepository(
    initial: List<CollectionEntry> = emptyList(),
) : CollectionRepository {
    val entries = MutableStateFlow(initial)
    val histories = MutableStateFlow<Map<PriceSource, CurrentHoldingsHistory>>(emptyMap())
    var failHistory = false

    override fun observe(): Flow<List<CollectionEntry>> = entries

    override fun observeCurrentHoldingsHistory(
        source: PriceSource,
        asOfUtcDay: LocalDate,
    ): Flow<CurrentHoldingsHistory> = flow {
        if (failHistory) error("history unavailable")
        emitAll(histories.map { it[source] ?: CurrentHoldingsHistory(source, asOfUtcDay, 0, emptyList(), null) })
    }

    override suspend fun add(
        card: Card,
        variant: CardVariant,
        condition: Condition,
        quantity: Int,
        pricePaid: Money?,
    ): CollectionItem = error("not used")

    override suspend fun list(): List<CollectionEntry> = entries.value

    override suspend fun getById(itemId: String): CollectionItemDetails? = entries.value
        .firstOrNull { it.item.id == itemId }
        ?.let { CollectionItemDetails(it.item, it.card) }

    override suspend fun update(original: CollectionItem, changes: CollectionItemChanges): CollectionUpdate =
        CollectionUpdate.NotFound

    override suspend fun remove(itemId: String) = Unit
}

fun testCard(
    id: String,
    name: String = id,
    setId: String = "set-$id",
    setName: String = "Set $setId",
    rarity: String? = "Comum",
    prices: List<CardPrice> = emptyList(),
) = Card(
    id = id,
    name = name,
    setId = setId,
    setName = setName,
    collectorNumber = "1",
    rarity = rarity,
    hp = null,
    types = emptyList(),
    attacks = emptyList(),
    imageUrl = null,
    variants = setOf(CardVariant.NORMAL),
    prices = prices,
)

fun testEntry(
    card: Card,
    qty: Int = 1,
    condition: Condition = Condition.NEAR_MINT,
    variant: CardVariant = CardVariant.NORMAL,
    pricePaid: Money? = null,
) = CollectionEntry(
    item = CollectionItem(
        id = "item-${card.id}-$condition-$variant",
        cardId = card.id,
        quantity = qty,
        condition = condition,
        variant = variant,
        pricePaid = pricePaid,
        addedAt = 0L,
    ),
    card = card,
)

fun testPrice(
    market: Double,
    source: PriceSource = PriceSource.TCGPLAYER,
    variant: CardVariant = CardVariant.NORMAL,
    currency: Currency = Currency.USD,
) = CardPrice(
    source = source,
    variant = variant,
    currency = currency,
    market = market,
    low = null,
    mid = null,
    high = null,
    trend = null,
    avg1 = null,
    avg7 = null,
    avg30 = null,
    updatedAt = null,
)
