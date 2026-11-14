package app.tcg.app.ui.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tcg.app.ui.components.PricePaidDraft
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import app.tcg.core.domain.model.CardListSummary
import app.tcg.core.domain.model.CardPrice
import app.tcg.core.domain.model.CardVariant
import app.tcg.core.domain.model.Condition
import app.tcg.core.domain.model.Currency
import app.tcg.core.domain.model.Money
import app.tcg.core.domain.model.PriceSource
import app.tcg.core.domain.valuation.Valuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class CardDetailUiState(
    val card: Card,
    val variant: CardVariant,
    val condition: Condition = Condition.NEAR_MINT,
    val quantity: Int = 1,
    val pricePaid: PricePaidDraft = PricePaidDraft(),
    val purchaseAmountNotApplied: Boolean = false,
    val tcgplayer: Money? = null,
    val cardmarket: Money? = null,
    val ligapokemon: Money? = null,
    val jornada: Money? = null,
)

class CardDetailViewModel(
    card: Card,
    private val collection: CollectionRepository,
    private val cardRepository: CardRepository? = null,
    private val cardListRepository: CardListRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(stateFor(card, defaultVariant(card)))
    val state: StateFlow<CardDetailUiState> = _state.asStateFlow()

    /** Empty while no card-list repository was supplied, so older callers keep working. */
    val lists: StateFlow<List<CardListSummary>> = cardListRepository?.observeLists()
        ?.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<CardListSummary>())
        ?: MutableStateFlow<List<CardListSummary>>(emptyList()).asStateFlow()

    /**
     * Returns false when the membership could not be written, so the caller does not claim success.
     * The card itself is unaffected: lists are an add-on to the collection.
     */
    suspend fun addToLists(listIds: Set<String>): Boolean {
        val repository = cardListRepository ?: return false
        if (listIds.isEmpty()) return false
        return try {
            repository.addCards(listOf(_state.value.card), listIds)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun selectVariant(variant: CardVariant) {
        _state.update {
            it.copy(
                variant = variant,
                tcgplayer = Valuation.itemValue(it.card, variant, PriceSource.TCGPLAYER),
                cardmarket = Valuation.itemValue(it.card, variant, PriceSource.CARDMARKET),
                ligapokemon = Valuation.itemValue(it.card, variant, PriceSource.LIGAPOKEMON),
                jornada = Valuation.itemValue(it.card, variant, PriceSource.JORANDA),
            )
        }
    }

    suspend fun updateLigaPokemonPrice(priceInBrl: Double) {
        val cardRepo = cardRepository ?: return
        val currentVariant = _state.value.variant
        val money = Money(priceInBrl, Currency.BRL)
        cardRepo.saveCardPrice(
            cardId = _state.value.card.id,
            source = PriceSource.LIGAPOKEMON,
            variant = currentVariant,
            marketPrice = priceInBrl,
        )
        val newPrice = CardPrice(
            source = PriceSource.LIGAPOKEMON,
            variant = currentVariant,
            currency = Currency.BRL,
            market = priceInBrl,
            low = null, mid = null, high = null, trend = null,
            avg1 = null, avg7 = null, avg30 = null, updatedAt = null,
        )
        val filteredPrices = _state.value.card.prices.filterNot {
            it.source == PriceSource.LIGAPOKEMON && it.variant == currentVariant
        }
        val updatedCard = _state.value.card.copy(prices = filteredPrices + newPrice)
        _state.update {
            it.copy(
                card = updatedCard,
                ligapokemon = money,
            )
        }
    }

    fun selectCondition(condition: Condition) {
        _state.update { it.copy(condition = condition) }
    }

    fun setQuantity(quantity: Int) {
        _state.update { it.copy(quantity = quantity.coerceAtLeast(1)) }
    }

    fun setPricePaidAmount(amount: String) {
        _state.update { it.copy(pricePaid = it.pricePaid.copy(amount = amount)) }
    }

    fun selectPricePaidCurrency(currency: Currency) {
        _state.update { it.copy(pricePaid = it.pricePaid.copy(currency = currency)) }
    }

    suspend fun save(variant: CardVariant, condition: Condition, quantity: Int) {
        if (!_state.value.pricePaid.isValid) return
        val pricePaid = _state.value.pricePaid
        _state.update {
            stateFor(it.card, variant).copy(condition = condition, quantity = quantity, pricePaid = pricePaid)
        }
        val saved = collection.add(
            card = _state.value.card,
            variant = variant,
            condition = condition,
            quantity = quantity,
            pricePaid = pricePaid.toMoney(),
        )
        _state.update {
            it.copy(purchaseAmountNotApplied = pricePaid.toMoney() != null && saved.pricePaid != pricePaid.toMoney())
        }
    }

    private companion object {
        fun stateFor(card: Card, variant: CardVariant) = CardDetailUiState(
            card = card,
            variant = variant,
            tcgplayer = Valuation.itemValue(card, variant, PriceSource.TCGPLAYER),
            cardmarket = Valuation.itemValue(card, variant, PriceSource.CARDMARKET),
            ligapokemon = Valuation.itemValue(card, variant, PriceSource.LIGAPOKEMON),
            jornada = Valuation.itemValue(card, variant, PriceSource.JORANDA),
        )

        fun defaultVariant(card: Card): CardVariant =
            if (CardVariant.NORMAL in card.variants) CardVariant.NORMAL
            else card.variants.firstOrNull() ?: CardVariant.NORMAL
    }
}
